package com.openlog

import com.openlog.model.LogEntry
import com.openlog.model.LogLevel
import com.openlog.model.VideoSource
import com.openlog.ui.AppState
import com.openlog.ui.mkTab
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlin.io.path.createTempDirectory
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
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
