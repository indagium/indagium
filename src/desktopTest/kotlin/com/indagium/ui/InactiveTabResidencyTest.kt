@file:Suppress("MagicNumber")

package com.indagium.ui

import androidx.compose.ui.graphics.ImageBitmap
import com.indagium.CaptureArchiveFixtures
import com.indagium.capture.CaptureExtractionCache
import com.indagium.debug.IndagiumToolOperations
import com.indagium.model.AppSettings
import com.indagium.model.CustomIssueRule
import com.indagium.model.CustomIssueSite
import com.indagium.model.LogEntry
import com.indagium.model.LogLevel
import com.indagium.model.TabContentState
import com.indagium.model.VideoAttachment
import com.indagium.model.VideoSource
import com.indagium.utils.ParsedLog
import com.indagium.utils.computeItems
import com.indagium.utils.parseLogFileResult
import com.indagium.video.VideoPlayerController
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import org.apache.commons.compress.compressors.gzip.GzipCompressorOutputStream
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlin.io.path.createTempDirectory
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class InactiveTabResidencyTest {
    private class CountingLogRows(private val rows: List<LogEntry>) : AbstractList<LogEntry>() {
        var reads = 0

        override val size: Int get() = rows.size

        override fun get(index: Int): LogEntry {
            reads++
            return rows[index]
        }
    }

    private fun waitUntil(message: String, condition: () -> Boolean) {
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(20)
        while (!condition() && System.nanoTime() < deadline) Thread.sleep(10)
        assertTrue(condition(), message)
    }

    private fun parsedRows(count: Int) = (1..count).map { id ->
        LogEntry(id, "10:00:00.000", LogLevel.I, "Tag", "row $id")
    }

    private fun simpleLog(file: File, rows: Int = 2) {
        file.bufferedWriter().use { out ->
            repeat(rows) { index -> out.append("10:00:00.000 I/Tag: row ${index + 1}\n") }
        }
    }

    private fun newState(dir: File, clock: () -> Long = System::nanoTime) = AppState(
        autosaveFile = File(dir, "state.cache"),
        autoExportNotes = false,
        monotonicNowNanos = clock,
    ).also { it.closedTabRowsReleaseDelayMs = 10L }

    @Test
    fun releasableRowMapDelegatesMembershipWithoutScanningEntries() {
        val count = 1_024
        val rows = CountingLogRows(parsedRows(count))
        val tab = mkTab("counted", "counted.log", rows)
        val readsBeforeMembershipChecks = rows.reads

        val selected = (1..count).filterTo(linkedSetOf()) { it in tab.rmap }
        assertFalse((count + 1) in tab.rmap)

        assertEquals(count, selected.size)
        assertTrue(
            rows.reads - readsBeforeMembershipChecks <= 4 * count,
            "selection membership should use the row map lookup instead of scanning its entries",
        )
    }

    @Test
    fun idleTimeoutUsesElapsedInactivityAndDropsOrdinaryParsedRowsFromOldSnapshots() {
        val dir = createTempDirectory("inactive-tab-idle").toFile()
        var now = 0L
        val source = File(dir, "hidden.log").also { simpleLog(it) }
        val app = newState(dir) { now }
        try {
            val hidden = mkTab("hidden", source.name, parsedRows(2)).copy(
                sourcePath = source.absolutePath,
                expanded = setOf("77"),
                selected = setOf(1),
            )
            val visible = mkTab("visible", "visible.log", parsedRows(1))
            app.tabs = listOf(hidden, visible)
            app.settings = app.settings.copy(inactiveTabUnloadMinutes = 30)
            app.activateTab("hidden")
            app.activateTab("visible")
            app.sweepInactiveTabs()

            now = TimeUnit.MINUTES.toNanos(29)
            app.sweepInactiveTabs()
            assertEquals(TabContentState.LOADED, app.tab("hidden")?.contentState)
            assertEquals(TabContentState.LOADED, app.tab("visible")?.contentState)

            now += TimeUnit.MINUTES.toNanos(1)
            app.sweepInactiveTabs()
            waitUntil("hidden log should unload at 30 minutes") {
                app.sweepInactiveTabs()
                app.tab("hidden")?.contentState == TabContentState.UNLOADED
            }

            val shell = assertNotNull(app.tab("hidden"))
            assertTrue(shell.logData.isEmpty())
            assertTrue(shell.rmap.isEmpty())
            assertEquals(hidden.annotations, shell.annotations)
            assertEquals(hidden.filter, shell.filter)
            assertEquals(hidden.expanded, shell.expanded)
            assertEquals(hidden.selected, shell.selected)

            val staleSnapshot = hidden
            waitUntil("stale Compose snapshot should release its ordinary parsed list and row map") {
                staleSnapshot.logData.isEmpty() && staleSnapshot.rmap.isEmpty()
            }
            assertTrue(computeItems(staleSnapshot, applyFilter = true).isEmpty(), "the warm visible-items cache is invalidated on unload")

            val destination = File(dir, "restored-analysis.md")
            assertTrue(app.exportAnalysisTo("hidden", destination), "a synchronous export reloads a healthy source before writing")
            assertTrue(destination.isFile)
            assertEquals(2, app.tab("hidden")?.logData?.size, "export loading has restored the current source rows")

            val reloaded = runBlocking { app.awaitTabLoaded("hidden") }
            assertEquals(2, assertNotNull(reloaded).logData.size)
        } finally {
            app.close()
            dir.deleteRecursively()
        }
    }

    @Test
    fun timeoutChangeUsesExistingIdleDurationAndZeroDisablesEviction() {
        val dir = createTempDirectory("inactive-tab-setting-change").toFile()
        var now = 0L
        val source = File(dir, "hidden.log").also { simpleLog(it) }
        val app = newState(dir) { now }
        try {
            app.tabs = listOf(
                mkTab("hidden", source.name, parsedRows(1)).copy(sourcePath = source.absolutePath),
                mkTab("visible", "visible.log", parsedRows(1)),
            )
            app.settings = app.settings.copy(inactiveTabUnloadMinutes = 30)
            app.activateTab("hidden")
            app.activateTab("visible")
            app.sweepInactiveTabs()

            now = TimeUnit.MINUTES.toNanos(10)
            app.settings = app.settings.copy(inactiveTabUnloadMinutes = 5)
            app.sweepInactiveTabs()
            waitUntil("changing the timeout must apply to elapsed idle time") {
                app.sweepInactiveTabs()
                app.tab("hidden")?.contentState == TabContentState.UNLOADED
            }

            val reloaded = runBlocking { app.awaitTabLoaded("hidden") }
            assertNotNull(reloaded)
            app.settings = app.settings.copy(inactiveTabUnloadMinutes = 0)
            now += TimeUnit.HOURS.toNanos(2)
            app.sweepInactiveTabs()
            assertEquals(TabContentState.LOADED, app.tab("hidden")?.contentState)
        } finally {
            app.close()
            dir.deleteRecursively()
        }
    }

    @Test
    fun activeAndComparePanesStayLoadedAndRevisitRestartsTheFullIdleGrace() {
        val dir = createTempDirectory("inactive-tab-revisit").toFile()
        var now = 0L
        val sourceA = File(dir, "a.log").also { simpleLog(it) }
        val sourceB = File(dir, "b.log").also { simpleLog(it) }
        val app = newState(dir) { now }
        try {
            app.settings = app.settings.copy(inactiveTabUnloadMinutes = 30)
            app.tabs = listOf(
                mkTab("a", sourceA.name, parsedRows(1)).copy(sourcePath = sourceA.absolutePath),
                mkTab("b", sourceB.name, parsedRows(1)).copy(sourcePath = sourceB.absolutePath),
            )
            app.activateTab("a")
            app.compareTabId = "b"
            app.compareMode = true
            app.sweepInactiveTabs()

            now = TimeUnit.DAYS.toNanos(3)
            app.sweepInactiveTabs()
            assertEquals(TabContentState.LOADED, app.tab("a")?.contentState, "the active tab is never unloaded")
            assertEquals(TabContentState.LOADED, app.tab("b")?.contentState, "both compare panes stay loaded")

            app.compareMode = false
            now += TimeUnit.MINUTES.toNanos(20)
            app.sweepInactiveTabs()
            assertEquals(TabContentState.LOADED, app.tab("b")?.contentState)

            app.activateTab("b")
            app.activateTab("a") // b becomes hidden again and starts a fresh full timeout here
            now += TimeUnit.MINUTES.toNanos(29)
            app.sweepInactiveTabs()
            assertEquals(TabContentState.LOADED, app.tab("b")?.contentState)

            now += TimeUnit.MINUTES.toNanos(1)
            app.sweepInactiveTabs()
            waitUntil("revisited tab unloads only after a new 30 minute grace") {
                app.sweepInactiveTabs()
                app.tab("b")?.contentState == TabContentState.UNLOADED
            }
        } finally {
            app.close()
            dir.deleteRecursively()
        }
    }

    @Test
    fun compareFallbackPaneLoadsWhenCompareIdIsEmptyOrEqualsActiveTab() {
        listOf("" to false, "b" to true).forEach { (compareId, directModeSetter) ->
            val dir = createTempDirectory("inactive-tab-compare-fallback").toFile()
            val source = File(dir, "a.log").also { simpleLog(it) }
            val app = newState(dir)
            try {
                app.tabs = listOf(
                    mkTab("a", source.name, emptyList()).copy(
                        sourcePath = source.absolutePath,
                        contentState = TabContentState.UNLOADED,
                    ),
                    mkTab("b", "b.log", parsedRows(1)),
                )
                app.activeTabId = "b"
                app.activeSurface = ActiveSurface.Log("b")
                app.startPendingRestoredTabLoads()
                app.compareTabId = compareId

                if (directModeSetter) app.compareMode = true else app.updateCompareMode(true)

                assertEquals("a", app.compareTabs()?.second?.id, "the compare pane resolver chooses the fallback tab")
                waitUntil("the fallback compare pane should be loaded") {
                    app.tab("a")?.contentState == TabContentState.LOADED
                }
            } finally {
                app.close()
                dir.deleteRecursively()
            }
        }
    }

    @Test
    fun rowReaderPinDefersExpiryUntilTheReaderFinishes() {
        val dir = createTempDirectory("inactive-tab-reader-pin").toFile()
        var now = 0L
        val source = File(dir, "hidden.log").also { simpleLog(it) }
        val app = newState(dir) { now }
        try {
            val hidden = mkTab("hidden", source.name, parsedRows(2)).copy(sourcePath = source.absolutePath)
            app.tabs = listOf(hidden, mkTab("visible", "visible.log", parsedRows(1)))
            app.activateTab("hidden")
            val pin = app.pinRows(hidden)
            app.activateTab("visible")
            now = TimeUnit.MINUTES.toNanos(30)
            app.sweepInactiveTabs()
            assertEquals(TabContentState.LOADED, app.tab("hidden")?.contentState, "a background row reader protects the tab")

            pin.close()
            app.sweepInactiveTabs()
            waitUntil("expired tab unloads when the final reader releases its pin") {
                app.sweepInactiveTabs()
                app.tab("hidden")?.contentState == TabContentState.UNLOADED
            }
        } finally {
            app.close()
            dir.deleteRecursively()
        }
    }

    @Test
    fun activeVideoPlaybackProtectsHiddenRowsUntilPaused() {
        val dir = createTempDirectory("inactive-tab-video-playback").toFile()
        var now = 0L
        val source = File(dir, "hidden.log").also { simpleLog(it) }
        val controller = ResidencyVideoController()
        val app = AppState(
            autosaveFile = File(dir, "state.cache"),
            autoExportNotes = false,
            videoControllerFactory = { controller },
            monotonicNowNanos = { now },
        ).also { it.closedTabRowsReleaseDelayMs = 10L }
        try {
            app.settings = app.settings.copy(inactiveTabUnloadMinutes = 30)
            val hidden = mkTab("hidden", source.name, parsedRows(2)).copy(
                sourcePath = source.absolutePath,
                attachedVideo = VideoAttachment(VideoSource.LocalFile(File(dir, "video.mp4").absolutePath), "video.mp4"),
            )
            app.tabs = listOf(hidden, mkTab("visible", "visible.log", parsedRows(1)))
            app.activateTab("hidden")
            assertNotNull(app.videoController("hidden"))
            app.activateTab("visible")
            app.sweepInactiveTabs()

            now = TimeUnit.MINUTES.toNanos(30)
            app.sweepInactiveTabs()
            assertEquals(TabContentState.LOADED, app.tab("hidden")?.contentState)
            assertFalse(controller.closed, "the active decoder must stay open while playback continues")

            controller.isPlaying = false
            app.sweepInactiveTabs()
            waitUntil("paused media is unused and its hidden log can unload") {
                app.sweepInactiveTabs()
                app.tab("hidden")?.contentState == TabContentState.UNLOADED
            }
            waitUntil("unloading releases the paused decoder") { controller.closed }
        } finally {
            app.close()
            dir.deleteRecursively()
        }
    }

    @Test
    fun settingsIssueScanPinsHiddenRowsAndAnObsoleteRuleResultCannotPublish() {
        val dir = createTempDirectory("inactive-tab-issue-reader").toFile()
        var now = 0L
        val source = File(dir, "hidden.log").also { simpleLog(it) }
        val row = parsedRows(1).single()
        val firstStarted = CountDownLatch(1)
        val releaseFirst = CountDownLatch(1)
        val firstReturned = CountDownLatch(1)
        val latestReturned = CountDownLatch(1)
        val staleSite = CustomIssueSite("stale", row, "first", "First")
        val currentSite = CustomIssueSite("current", row, "second", "Second")
        val app = AppState(
            autosaveFile = File(dir, "state.cache"),
            autoExportNotes = false,
            monotonicNowNanos = { now },
            customIssueSitesComputer = { _, rules ->
                if (rules.single().id == "first") {
                    firstStarted.countDown()
                    releaseFirst.await(10, TimeUnit.SECONDS)
                    firstReturned.countDown()
                    listOf(staleSite)
                } else {
                    latestReturned.countDown()
                    listOf(currentSite)
                }
            },
        )
        try {
            app.updateSettings { it.copy(inactiveTabUnloadMinutes = 30) }
            app.tabs = listOf(
                mkTab("hidden", source.name, parsedRows(1)).copy(sourcePath = source.absolutePath),
                mkTab("visible", "visible.log", emptyList()),
            )
            app.activateTab("hidden")
            app.activateTab("visible")
            app.sweepInactiveTabs()
            now = TimeUnit.MINUTES.toNanos(30)

            app.updateSettings { it.copy(customIssueRules = listOf(CustomIssueRule("first", "First", "one"))) }
            assertTrue(firstStarted.await(10, TimeUnit.SECONDS), "the first settings scan should enter its worker")
            app.sweepInactiveTabs()
            assertEquals(TabContentState.LOADED, app.tab("hidden")?.contentState, "the settings scan pins hidden rows")

            app.updateSettings { it.copy(customIssueRules = listOf(CustomIssueRule("second", "Second", "two"))) }
            assertTrue(latestReturned.await(10, TimeUnit.SECONDS), "the newer rules finish while the old scan is blocked")
            waitUntil("the current rule result should publish") {
                app.tab("hidden")?.analysis?.customIssueSites == listOf(currentSite)
            }

            val publicationPin = app.pinRows(requireNotNull(app.tab("hidden")))
            releaseFirst.countDown()
            assertTrue(firstReturned.await(10, TimeUnit.SECONDS))
            Thread.sleep(50)
            assertEquals(listOf(currentSite), app.tab("hidden")?.analysis?.customIssueSites)
            publicationPin.close()
            waitUntil("the obsolete worker should release its row pin") {
                app.sweepInactiveTabs()
                app.tab("hidden")?.contentState == TabContentState.UNLOADED
            }
        } finally {
            releaseFirst.countDown()
            app.close()
            dir.deleteRecursively()
        }
    }

    @Test
    fun compressedAndArchiveEntrySourcesCanBeUnloadedAndReloaded() = runBlocking {
        val dir = createTempDirectory("inactive-tab-source-formats").toFile()
        var now = 0L
        val text = "10:00:00.000 I/Tag: row one\n10:00:00.100 E/Tag: row two\n"
        val gzip = File(dir, "compressed.log.gz").also { file ->
            GzipCompressorOutputStream(file.outputStream()).use { it.write(text.toByteArray()) }
        }
        val archive = File(dir, "bundle.zip").also { file ->
            ZipOutputStream(file.outputStream()).use { output ->
                output.putNextEntry(ZipEntry("logs/archive.log"))
                output.write(text.toByteArray())
                output.closeEntry()
            }
        }
        val app = newState(dir) { now }
        try {
            app.settings = app.settings.copy(inactiveTabUnloadMinutes = 30)
            app.tabs = listOf(
                mkTab("gzip", gzip.name, parsedRows(1)).copy(sourcePath = gzip.absolutePath),
                mkTab("archive", "archive.log", parsedRows(1)).copy(sourcePath = "${archive.absolutePath}!logs/archive.log"),
                mkTab("visible", "visible.log", parsedRows(1)),
            )
            app.activateTab("gzip")
            app.activateTab("visible")
            app.sweepInactiveTabs()
            now = TimeUnit.MINUTES.toNanos(30)
            waitUntil("compressed and archive logs should become unloaded") {
                app.sweepInactiveTabs()
                app.tab("gzip")?.contentState == TabContentState.UNLOADED &&
                    app.tab("archive")?.contentState == TabContentState.UNLOADED
            }

            val gzipRows = assertNotNull(app.awaitTabLoaded("gzip")).logData
            val archiveRows = assertNotNull(app.awaitTabLoaded("archive")).logData
            assertEquals(2, gzipRows.size)
            assertEquals(2, archiveRows.size)
            assertEquals("row two", gzipRows.last().msg)
            assertEquals("row two", archiveRows.last().msg)
        } finally {
            app.close()
            dir.deleteRecursively()
        }
    }

    @Test
    fun importedStoppedCaptureReleasesAndReacquiresItsArchiveSnapshotOnUnload() = runBlocking {
        val dir = createTempDirectory("inactive-tab-imported-capture").toFile()
        var now = 0L
        val archive = CaptureArchiveFixtures.exportWithVideo(dir)
        val app = AppState(
            autosaveFile = File(dir, "state.cache"),
            autoExportNotes = false,
            archiveCacheDir = File(dir, "cache"),
            notesDir = File(dir, "notes"),
            monotonicNowNanos = { now },
        ).also { it.closedTabRowsReleaseDelayMs = 10L }
        try {
            app.settings = app.settings.copy(inactiveTabUnloadMinutes = 30)
            val captureId = app.openCaptureFile(archive)
            waitUntil("imported capture rows and snapshot should publish") {
                app.tab(captureId)?.contentState == TabContentState.LOADED &&
                    app.captureSnapshotSessionFor(captureId) != null
            }
            val initial = assertNotNull(app.tab(captureId))
            val initialSession = assertNotNull(app.captureSnapshotSessionFor(captureId))
            val extraction = initialSession.directory
            val originalTimeline = initial.captureTimeline
            app.upAnn(captureId) { tab ->
                tab.copy(annotations = tab.annotations.copy(prefix = "preserved capture notes"))
            }
            app.tabs = app.tabs + mkTab("visible", "visible.log", parsedRows(1))
            app.activateTab("visible")
            app.sweepInactiveTabs()

            now = TimeUnit.MINUTES.toNanos(30)
            waitUntil("stopped imported capture should unload after index work and reader pins finish") {
                app.sweepInactiveTabs()
                app.tab(captureId)?.contentState == TabContentState.UNLOADED
            }
            waitUntil("unload should release the snapshot extraction claim") {
                !CaptureExtractionCache.holdsLease(extraction) && !CaptureExtractionCache.isInUse(extraction)
            }
            assertEquals("preserved capture notes", app.tab(captureId)?.annotations?.prefix)

            val restored = assertNotNull(app.awaitTabLoaded(captureId))
            val restoredSession = assertNotNull(app.captureSnapshotSessionFor(captureId))
            assertTrue(CaptureExtractionCache.holdsLease(extraction), "reload reacquires the extraction claim")
            assertEquals(3, restored.logData.size)
            assertEquals(initial.logFormat, restored.logFormat)
            assertEquals(originalTimeline, restored.captureTimeline)
            assertEquals("preserved capture notes", restored.annotations.prefix)
            assertEquals(archive.absolutePath, restored.captureArchivePath)
            assertEquals(initialSession.id, restoredSession.id)
        } finally {
            app.close()
            dir.deleteRecursively()
        }
    }

    @Test
    fun concurrentReloadRequestsShareOneJobAndCloseInvalidatesTheLatePublisher() {
        val dir = createTempDirectory("inactive-tab-reload-race").toFile()
        val source = File(dir, "hidden.log").also { simpleLog(it) }
        val enteredParser = CountDownLatch(1)
        val releaseParser = CountDownLatch(1)
        val parseCount = AtomicInteger()
        val app = AppState(
            autosaveFile = File(dir, "state.cache"),
            autoExportNotes = false,
            parser = {
                parseCount.incrementAndGet()
                enteredParser.countDown()
                releaseParser.await(10, TimeUnit.SECONDS)
                ParsedLog(com.indagium.model.LogFormat.LOGCAT, parsedRows(1))
            },
        )
        try {
            app.tabs = listOf(mkTab("hidden", source.name, emptyList()).copy(
                sourcePath = source.absolutePath,
                contentState = TabContentState.UNLOADED,
            ))
            app.activeTabId = "hidden"
            app.activeSurface = ActiveSurface.Log("hidden")

            val first = assertNotNull(app.ensureTabLoaded("hidden"))
            assertTrue(enteredParser.await(10, TimeUnit.SECONDS), "reload should enter the parser")
            val others = (1..8).map { app.ensureTabLoaded("hidden") }
            assertTrue(others.all { it === first }, "every concurrent request joins the same registered job")

            app.closeTab("hidden")
            releaseParser.countDown()
            waitUntil("the stale parser job should finish after close") { !first.isActive }
            assertTrue(app.tabs.none { it.id == "hidden" }, "a late parse result cannot recreate a closed shell")
            assertEquals(1, parseCount.get(), "only one parse is started for concurrent requests")
        } finally {
            releaseParser.countDown()
            app.close()
            dir.deleteRecursively()
        }
    }

    @Test
    fun restoreLoadsOnlyTheDisplayedTabAndExpandingACollapsedFolderDoesNotLoadMembers() {
        val dir = createTempDirectory("inactive-tab-lazy-startup").toFile()
        val autosave = File(dir, "state.cache")
        val hiddenSource = File(dir, "hidden.log").also { simpleLog(it) }
        val visibleSource = File(dir, "visible.log").also { simpleLog(it) }
        val original = newState(dir)
        var groupId = ""
        try {
            original.tabs = listOf(
                mkTab("hidden", hiddenSource.name, parsedRows(1)).copy(sourcePath = hiddenSource.absolutePath),
                mkTab("visible", visibleSource.name, parsedRows(1)).copy(sourcePath = visibleSource.absolutePath),
            )
            original.activeTabId = "visible"
            original.activeSurface = ActiveSurface.Log("visible")
            groupId = assertNotNull(original.createTabGroup(TabRef.Log("hidden")))
            original.toggleTabGroupCollapsed(groupId)
            original.autosaveNow()
        } finally {
            original.close()
        }

        val parses = AtomicInteger()
        val restored = AppState(
            autosaveFile = autosave,
            restoreOnCreate = true,
            autoExportNotes = false,
            parser = { file -> parses.incrementAndGet(); parseLogFileResult(file) },
        )
        try {
            assertTrue(restored.tabs.all { it.contentState == TabContentState.UNLOADED }, "restore initially publishes shells only")
            assertEquals(0, parses.get(), "construction does not parse hidden files")

            restored.startPendingRestoredTabLoads()
            waitUntil("the visible restored tab should load") {
                restored.tab("visible")?.contentState == TabContentState.LOADED
            }
            assertEquals(TabContentState.UNLOADED, restored.tab("hidden")?.contentState)
            assertEquals(1, parses.get(), "startup parses only the displayed tab")

            restored.toggleTabGroupCollapsed(groupId)
            assertEquals(TabContentState.UNLOADED, restored.tab("hidden")?.contentState, "folder expansion only reveals its shell")
            restored.activateTab("hidden")
            waitUntil("selecting the hidden shell should load it on demand") {
                restored.tab("hidden")?.contentState == TabContentState.LOADED
            }
            assertEquals(2, parses.get())
        } finally {
            restored.close()
            dir.deleteRecursively()
        }
    }

    @Test
    fun aMissingSourceKeepsItsShellAndRetryLoadsItAfterTheFileReturns() {
        val dir = createTempDirectory("inactive-tab-retry").toFile()
        val source = File(dir, "late.log")
        val app = newState(dir)
        try {
            app.tabs = listOf(mkTab("late", source.name, emptyList()).copy(
                sourcePath = source.absolutePath,
                contentState = TabContentState.UNLOADED,
            ))
            app.activeTabId = "late"
            app.activeSurface = ActiveSurface.Log("late")

            val failed = assertNotNull(app.ensureTabLoaded("late"))
            runBlocking { failed.join() }
            assertEquals(TabContentState.FAILED, app.tab("late")?.contentState)
            assertTrue(app.tabs.any { it.id == "late" }, "a missing source does not close the tab shell")
            assertTrue(app.tab("late")?.contentLoadError?.isNotBlank() == true)

            simpleLog(source)
            val retried = runBlocking { app.awaitTabLoaded("late") }
            assertEquals(TabContentState.LOADED, retried?.contentState)
            assertEquals(2, retried?.logData?.size)
        } finally {
            app.close()
            dir.deleteRecursively()
        }
    }

    @Test
    fun unloadedToolExportAndTailReloadBeforeUsingRows() {
        runBlocking {
            val dir = createTempDirectory("inactive-tab-tool-actions").toFile()
            val source = File(dir, "hidden.log").also { simpleLog(it, rows = 3) }
            val app = newState(dir)
            try {
                app.tabs = listOf(
                    mkTab("hidden", source.name, parsedRows(1)).copy(sourcePath = source.absolutePath, contentState = TabContentState.UNLOADED),
                    mkTab("visible", "visible.log", parsedRows(1)),
                )
                app.activeTabId = "visible"
                app.activeSurface = ActiveSurface.Log("visible")
                val gateway = IndagiumToolOperations(app).toolGateway

                val exportFile = File(dir, "filtered.txt")
                val exportResult = gateway.executeSuspending(
                    "export_filtered_log",
                    mapOf("tabId" to "hidden", "path" to exportFile.absolutePath, "format" to "txt"),
                ) as Map<*, *>
                assertEquals(true, exportResult["ok"])
                assertTrue(exportFile.readText().contains("row 3"), "the export sees all reloaded source rows")

                val tailResult = gateway.executeSuspending("start_tailing", mapOf("tabId" to "hidden")) as Map<*, *>
                assertEquals(true, tailResult["tailing"])
                assertEquals(3, app.tab("hidden")?.logData?.size, "tailing starts from the restored existing rows")
                gateway.executeSuspending("stop_tailing", mapOf("tabId" to "hidden"))
            } finally {
                app.close()
                dir.deleteRecursively()
            }
        }
    }

    @Test
    fun largeOrdinaryFileRowsAndWarmComputeCacheAreReleasedAcrossRepeatedReloads() = runBlocking {
        val dir = createTempDirectory("inactive-tab-large-file").toFile()
        val source = File(dir, "large.log")
        source.bufferedWriter().use { writer ->
            repeat(100_000) { index -> writer.append("10:00:00.000 I/Tag: row $index\n") }
        }
        var now = 0L
        val app = newState(dir) { now }
        try {
            app.settings = app.settings.copy(inactiveTabUnloadMinutes = 30)
            app.tabs = listOf(
                mkTab("large", source.name, emptyList()).copy(
                    sourcePath = source.absolutePath,
                    contentState = TabContentState.UNLOADED,
                ),
                mkTab("visible", "visible.log", parsedRows(1)),
            )
            app.activeTabId = "visible"
            app.activeSurface = ActiveSurface.Log("visible")
            app.sweepInactiveTabs()

            repeat(2) { iteration ->
                val loaded = assertNotNull(app.awaitTabLoaded("large"))
                assertEquals(100_000, loaded.logData.size)
                waitUntil("background analysis should release its reader pin before the idle sweep") {
                    app.tab("large")?.analysis?.pending == false
                }
                computeItems(loaded, applyFilter = true) // warms the global per-tab item cache
                val oldSnapshot = loaded

                now += TimeUnit.MINUTES.toNanos(30)
                waitUntil("iteration ${iteration + 1}: large ordinary rows should unload") {
                    app.sweepInactiveTabs() // retry if a short lived analysis pin won the first race
                    app.tab("large")?.contentState == TabContentState.UNLOADED
                }
                waitUntil("iteration ${iteration + 1}: old row and rmap backing should be dropped") {
                    oldSnapshot.logData.isEmpty() && oldSnapshot.rmap.isEmpty()
                }
                assertTrue(computeItems(oldSnapshot, applyFilter = true).isEmpty(), "stale compute cache does not retain reusable rows")
            }
        } finally {
            app.close()
            dir.deleteRecursively()
        }
    }

    @Test
    fun timeoutSettingIsKeyedAndMissingLegacyKeyKeepsThirtyMinuteDefault() {
        val custom = AppSettings().copy(inactiveTabUnloadMinutes = 45)
        assertEquals(45, settingsFromJson(custom.settingsJson())?.inactiveTabUnloadMinutes)

        val oldJson = Json.parseToJsonElement(AppSettings().settingsJson()).jsonObject.toMutableMap().apply {
            remove("inactiveTabUnloadMinutes")
        }
        assertEquals(30, settingsFromJson(JsonObject(oldJson).toString())?.inactiveTabUnloadMinutes)
    }
}

private class ResidencyVideoController : VideoPlayerController {
    override val currentFrame: ImageBitmap? = null
    override val positionMs: Long = 0L
    override val durationMs: Long = 0L
    override var isPlaying: Boolean = true
    override val volume: Float = 1f
    override val isMuted: Boolean = false
    override val error: String? = null
    var closed: Boolean = false
        private set

    override fun play() {
        isPlaying = true
    }

    override fun pause() {
        isPlaying = false
    }

    override fun seek(ms: Long) = Unit

    override fun setRate(rate: Float) = Unit

    override fun setVolume(volume: Float) = Unit

    override fun setMuted(muted: Boolean) = Unit

    override fun grabCurrentFrame(): ByteArray? = null

    override fun grabFrameAt(ms: Long): ByteArray? = null

    override fun close() {
        closed = true
        isPlaying = false
    }
}
