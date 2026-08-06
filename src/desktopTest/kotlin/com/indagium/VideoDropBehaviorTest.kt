package com.indagium

import com.indagium.model.LogEntry
import com.indagium.model.LogLevel
import com.indagium.model.VideoSource
import com.indagium.ui.AppState
import com.indagium.ui.mkTab
import com.indagium.utils.ZipLogCandidate
import com.indagium.utils.ZipLogCandidateKind
import com.indagium.utils.archiveVideoCacheFileName
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlin.io.path.createTempDirectory
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class VideoDropBehaviorTest {
    @Test
    fun standaloneVideoDropAttachesToActiveTabAndRevealsVideoPanel() {
        val dir = createTempDirectory("openlog-video-drop-alone").toFile()
        val state = stateWithActiveTab(dir)
        val video = File(dir, "recording.mp4").apply { writeText("not decoded by this test") }
        state.videoPanelVisible = false

        state.openDroppedFiles(listOf(video))

        assertEquals(video.absolutePath, state.tab("existing")?.attachedVideo?.path)
        assertTrue(state.videoPanelVisible)
    }

    @Test
    fun removeVideoDetachesAccidentalAttachmentAndHidesEmbeddedPlayer() {
        val dir = createTempDirectory("openlog-video-remove").toFile()
        val state = stateWithActiveTab(dir)
        val video = File(dir, "unrelated.mp4").apply { writeText("not decoded by this test") }
        state.attachVideoToActiveTab(video)

        state.removeVideo("existing")

        assertNull(state.tab("existing")?.attachedVideo)
        assertTrue(!state.videoPanelVisible)
    }

    @Test
    fun oneLogAndOneVideoDropWaitsForNewLogInsteadOfUsingPreviouslyActiveTab() {
        val dir = createTempDirectory("openlog-video-drop-pair").toFile()
        val parserStarted = CountDownLatch(1)
        val releaseParser = CountDownLatch(1)
        val state = AppState(
            autosaveFile = File(dir, "state.cache"),
            parser = {
                parserStarted.countDown()
                check(releaseParser.await(2, TimeUnit.SECONDS)) { "test parser was not released" }
                listOf(LogEntry(2, "10:00:00.000", LogLevel.I, "New", "loaded"))
            },
        ).also { state ->
            state.tabs = listOf(
                mkTab("existing", "existing.log", listOf(LogEntry(1, "10:00:00.000", LogLevel.I, "Old", "open"))),
            )
            state.activeTabId = "existing"
        }
        val log = File(dir, "new.log").apply { writeText("06-26 10:00:00.000  1  1 I New: loaded\n") }
        val video = File(dir, "new.mp4").apply { writeText("not decoded by this test") }
        state.videoPanelVisible = false

        state.openDroppedFiles(listOf(log, video))

        assertTrue(parserStarted.await(2, TimeUnit.SECONDS))
        // The log has not published yet, so attaching to activeTabId here would corrupt the
        // existing tab. The deferred target attachment must leave it alone.
        assertNull(state.tab("existing")?.attachedVideo)
        releaseParser.countDown()
        waitUntil {
            state.tabs.size == 2 &&
                state.tabs.singleOrNull { it.sourcePath == log.absolutePath }?.attachedVideo != null
        }
        val newTab = state.tabs.single { it.sourcePath == log.absolutePath }
        assertEquals(video.absolutePath, newTab.attachedVideo?.path)
        assertNull(state.tab("existing")?.attachedVideo)
        assertTrue(state.videoPanelVisible)
    }

    @Test
    fun ambiguousVideoDropDoesNotAttachAnyVideoToAnArbitraryTab() {
        val dir = createTempDirectory("openlog-video-drop-ambiguous").toFile()
        val state = stateWithActiveTab(dir)
        val firstLog = File(dir, "first.log").apply { writeText("06-26 10:00:00.000  1  1 I First: loaded\n") }
        val secondLog = File(dir, "second.log").apply { writeText("06-26 10:00:00.000  1  1 I Second: loaded\n") }
        val video = File(dir, "recording.mp4").apply { writeText("not decoded by this test") }

        state.openDroppedFiles(listOf(firstLog, secondLog, video))

        waitUntil { state.tabs.size == 3 && !state.isLoading }
        assertTrue(state.tabs.all { it.attachedVideo == null })
        assertEquals("Videos were not attached", state.openError?.title)
    }

    @Test
    fun archivePickerMakesVideoAttachmentExplicitAndKeepsItOutOfLogChoices() {
        val dir = createTempDirectory("openlog-video-drop-archive").toFile()
        val archive = zip(
            dir,
            "bugreport.zip",
            mapOf(
                "logs/first.log" to "06-26 10:00:00.000  1  1 I First: loaded\n",
                "logs/second.log" to "06-26 10:00:01.000  1  1 I Second: loaded\n",
                "screen/recording.mp4" to "not decoded by this test",
            ),
        )
        val state = AppState(File(dir, "state.cache"))

        state.openZipFile(archive)

        val picker = checkNotNull(state.pendingZipPicker)
        assertEquals(listOf("logs/first.log", "logs/second.log"), picker.candidates.map { it.entryPath })
        assertEquals(listOf("screen/recording.mp4"), picker.videoCandidates.map { it.entryPath })

        // Opening without a picker-confirmed video must not silently attach the lone recording.
        state.openZipEntries(archive, listOf(picker.candidates.single { it.entryPath == "logs/first.log" }))
        waitUntil { state.tabs.size == 1 && !state.isLoading }
        assertNull(state.tabs.single().attachedVideo)

        state.openZipEntries(
            archive,
            picker.candidates,
            picker.videoCandidates.single(),
        )

        waitUntil { state.tabs.size == 2 && state.tabs.all { it.attachedVideo != null } }
        state.tabs.forEach { tab ->
            val source = assertIs<VideoSource.ArchiveEntry>(tab.attachedVideo?.source)
            assertEquals(archive.absolutePath, source.archivePath)
            assertEquals("screen/recording.mp4", source.entryPath)
        }
    }

    @Test
    fun archiveWithOneLogAndOneVideoShowsPickerBeforeAnyAssociation() {
        val dir = createTempDirectory("openlog-video-drop-single-archive").toFile()
        val archive = zip(
            dir,
            "bugreport.zip",
            mapOf(
                "logs/main.log" to "06-26 10:00:00.000  1  1 I Main: loaded\n",
                "screen/recording.mp4" to "not decoded by this test",
            ),
        )
        val state = AppState(File(dir, "state.cache"))

        state.openZipFile(archive)

        val picker = checkNotNull(state.pendingZipPicker)
        assertEquals(listOf("logs/main.log"), picker.candidates.map { it.entryPath })
        assertEquals(listOf("screen/recording.mp4"), picker.videoCandidates.map { it.entryPath })
        assertTrue(state.tabs.isEmpty())

        state.openZipEntries(archive, picker.candidates, picker.videoCandidates.single())

        waitUntil { state.tabs.size == 1 && state.tabs.single().attachedVideo != null }
        val source = assertIs<VideoSource.ArchiveEntry>(state.tabs.single().attachedVideo?.source)
        assertEquals("screen/recording.mp4", source.entryPath)
    }

    // A disposable bug-report zip is routinely deleted right after its logs are opened — unlike an
    // explicitly attached local recording, which a user has no reason to remove. Extraction is
    // deferred until first playback access (attachVideoFromZip's own doc comment), so that access
    // can land after the archive is already gone. videoController's contract is "non-null whenever
    // attachedVideo is non-null" (its own doc comment) — a LocalFile source already keeps that
    // promise by handing FFmpeg a bad path and surfacing the failure through the controller's own
    // `error`, but an ArchiveEntry source used to return null one step earlier, in
    // resolveVideoPlaybackPath, with nowhere for the failure to go. That silently made the whole
    // video panel (BoundVideoPanel's `?: return`) and every Link/"Show in video" action act as if no
    // video were attached at all, instead of showing the same failure state a broken local file
    // already gets.
    @Test
    fun videoControllerReturnsAFailedControllerInsteadOfNullWhenTheArchiveHasBeenDeleted() {
        val dir = createTempDirectory("openlog-video-archive-gone").toFile()
        val archive = zip(
            dir, "bugreport.zip",
            mapOf(
                "logs/main.log" to "06-26 10:00:00.000  1  1 I Main: loaded\n",
                "screen/recording.mp4" to "not decoded by this test",
            ),
        )
        val state = AppState(File(dir, "state.cache"))
        state.openZipFile(archive)
        val picker = checkNotNull(state.pendingZipPicker)
        state.openZipEntries(archive, picker.candidates, picker.videoCandidates.single())
        waitUntil { state.tabs.size == 1 && state.tabs.single().attachedVideo != null }
        val tab = state.tabs.single()

        assertTrue(archive.delete())

        val controller = state.videoController(tab.id)
        assertNotNull(controller)
        assertNotNull(controller.error)
        assertTrue(controller.error!!.contains("bugreport.zip"))
        // A failure this early must disable playback, not merely leave positionMs/durationMs
        // looking plausible — VideoTransportBar's `playable = controller.error == null` relies on
        // this to grey out the slider/play button instead of offering controls for nothing.
        assertEquals(0L, controller.durationMs)
        assertFalse(controller.isPlaying)
    }

    // The mapping/anchor machinery (AppState.logIdToVideoMs, setVideoAnchor, navigateToVideoLog) is
    // pure and never touches the controller, so it must keep working even while the controller
    // itself reports failure — only actual playback (seeking, scrubbing, "Link to current video
    // position") is unavailable without a real decoder.
    @Test
    fun anchoringAndFollowMappingStillWorkWhenTheArchiveVideoControllerHasFailed() {
        val dir = createTempDirectory("openlog-video-archive-gone-mapping").toFile()
        val archive = zip(
            dir, "bugreport.zip",
            mapOf(
                "logs/main.log" to "06-26 10:00:00.000  1  1 I Main: first\n06-26 10:00:02.000  1  1 I Main: second\n",
                "screen/recording.mp4" to "not decoded by this test",
            ),
        )
        val state = AppState(File(dir, "state.cache"))
        state.openZipFile(archive)
        val picker = checkNotNull(state.pendingZipPicker)
        state.openZipEntries(archive, picker.candidates, picker.videoCandidates.single())
        waitUntil { state.tabs.size == 1 && state.tabs.single().attachedVideo != null }
        val tab = state.tabs.single()
        state.activeTabId = tab.id
        assertTrue(archive.delete())
        assertNotNull(state.videoController(tab.id)) // resolves (to a FailedVideoPlayerController)

        val firstId = tab.logData[0].id
        val secondId = tab.logData[1].id
        state.setVideoAnchor(tab.id, videoMs = 0L, logId = firstId)
        assertEquals(2_000L, state.logIdToVideoMs(state.tab(tab.id)!!, secondId))

        state.setVideoFollowLog(tab.id, true)
        state.navigateToVideoLog(tab.id, secondId)
        assertEquals(setOf(secondId), state.tab(tab.id)?.selected)
    }

    // Regression guard for a reported Linux bug: videoController(tabId) used to call
    // resolveVideoPlaybackPath (and, for an ArchiveEntry source, the filesystem work inside
    // extractArchiveVideoToCache — re-deriving the cache-key fingerprint from the archive's own
    // canonicalPath/length/lastModified, then stat-ing and touching the cache file's
    // setLastModified) on every single call, even once a controller already existed for that tab.
    // Harmless on a fast local disk; measured as the video panel never appearing to progress past
    // "Preparing timeline..." when the archive sits on a slow mount, because videoController is
    // called unmemoized from BoundVideoPanel's composable body — continuously while the panel is
    // visible, rapidly while playing — so the UI thread stayed perpetually busy re-resolving a
    // path it already had rather than ever getting to render the controller's (already correct)
    // published duration.
    //
    // getOrPut alone can't distinguish old from new code here: it already returns the existing map
    // entry without invoking its lambda once the key is present, so an identity check on the
    // returned controller (first === second) would pass either way — what actually changed is
    // whether resolveVideoPlaybackPath's filesystem work runs at all on the second call. Proven via
    // its one directly observable side effect: extractArchiveVideoToCache's cache-hit path touches
    // the extracted file's mtime on every call. Pin the cache file to an arbitrary past mtime after
    // the first (real) call, then assert a second videoController call leaves it untouched.
    @Test
    fun videoControllerDoesNotReTouchTheCacheFileOnceAControllerAlreadyExistsForTheTab() {
        val dir = createTempDirectory("openlog-video-no-retouch").toFile()
        val archive = zip(
            dir, "bugreport.zip",
            mapOf(
                "logs/main.log" to "06-26 10:00:00.000  1  1 I Main: loaded\n",
                "screen/recording.mp4" to "not decoded by this test",
            ),
        )
        val state = AppState(File(dir, "state.cache"))
        state.openZipFile(archive)
        val picker = checkNotNull(state.pendingZipPicker)
        state.openZipEntries(archive, picker.candidates, picker.videoCandidates.single())
        waitUntil { state.tabs.size == 1 && state.tabs.single().attachedVideo != null }
        val tab = state.tabs.single()

        assertNotNull(state.videoController(tab.id))
        // archiveCachePath is the real, shared app-data cache directory, not a per-test temp dir
        // (AppState's archiveCacheDir parameter is never overridden in these tests) — other tests'
        // extractions can already be sitting alongside this one, so the exact cache filename must
        // be computed the same way resolveVideoPlaybackPath does, not assumed to be the dir's only
        // entry.
        val candidate = ZipLogCandidate(
            entryPath = "screen/recording.mp4",
            displayName = "recording.mp4",
            sizeBytes = -1L,
            kind = ZipLogCandidateKind.VIDEO,
        )
        val cached = File(File(state.archiveCachePath, "videos"), archiveVideoCacheFileName(archive, candidate))
        assertTrue(cached.isFile, "expected the archive-entry video to already be extracted to $cached")
        val arbitraryPastMs = 1_000_000L
        assertTrue(cached.setLastModified(arbitraryPastMs))

        assertNotNull(state.videoController(tab.id))

        assertEquals(arbitraryPastMs, cached.lastModified(), "a second videoController call must not re-touch the cache file")
    }

    private fun stateWithActiveTab(dir: File): AppState = AppState(File(dir, "state.cache")).also { state ->
        state.tabs = listOf(
            mkTab("existing", "existing.log", listOf(LogEntry(1, "10:00:00.000", LogLevel.I, "Old", "open"))),
        )
        state.activeTabId = "existing"
    }

    private fun zip(dir: File, name: String, entries: Map<String, String>): File = File(dir, name).also { archive ->
        ZipOutputStream(archive.outputStream()).use { output ->
            entries.forEach { (path, content) ->
                output.putNextEntry(ZipEntry(path))
                output.write(content.toByteArray())
                output.closeEntry()
            }
        }
    }

    private fun waitUntil(timeoutMs: Long = 2_000, condition: () -> Boolean) {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (!condition() && System.currentTimeMillis() < deadline) Thread.sleep(10)
        assertTrue(condition(), "condition was not met within ${timeoutMs}ms")
    }
}
