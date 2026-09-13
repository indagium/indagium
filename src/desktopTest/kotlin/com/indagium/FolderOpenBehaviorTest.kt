package com.indagium

import com.indagium.model.LogFormat
import com.indagium.model.VideoSource
import com.indagium.ui.AppState
import com.indagium.ui.SplitSource
import com.indagium.utils.SPLIT_PROMPT_BYTES
import com.indagium.utils.ZipLogCandidate
import com.indagium.utils.ZipLogCandidateKind
import java.io.File
import kotlin.io.path.createTempDirectory
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

// AppState-level behavior for opening a plain folder of unpacked logs — the "share the dialog, not
// the plumbing" half of the plan: every assertion here doubles as a guard that a folder-opened tab
// is indistinguishable from having opened its file directly (real sourcePath, no archiveCandidate,
// tailable), never routed through PendingZipPicker's archive-shaped plumbing. See
// FolderScanTest.kt for the scan itself in isolation and OpenButtonRoutingTest.kt for the Open
// button's routing (including the folder branch of openPathOrShowError).
class FolderOpenBehaviorTest {
    private fun waitUntil(timeoutMs: Long = 10_000, condition: () -> Boolean) {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            if (condition()) return
            Thread.sleep(10)
        }
        assertTrue(condition(), "condition not met within ${timeoutMs}ms")
    }

    private val sampleLog = "06-26 10:00:00.000  100  200 I App: hello\n06-26 10:00:00.500  100  200 E App: boom\n"

    // openFolder scans on ioScope (deliberately, unlike openZipFile's synchronous scan — see its
    // doc comment) — every test here must wait for the async result rather than asserting right
    // after the call, the same idiom AppStateBehaviorTest.kt's other async-load tests already use.

    @Test
    fun twoLogsShowThePickerWithNoTabsOpenedYet() {
        val dir = createTempDirectory("openlog-folder-two-logs").toFile()
        val folder = File(dir, "unpacked").apply { mkdir() }
        File(folder, "first.log").writeText("06-26 10:00:00.000  1  1 I First: loaded\n")
        File(folder, "second.log").writeText("06-26 10:00:01.000  1  1 I Second: loaded\n")
        val state = AppState(File(dir, "state.cache"))

        state.openFolder(folder)
        waitUntil { state.pendingFolderPicker != null }

        val picker = state.pendingFolderPicker!!
        assertEquals(listOf("first.log", "second.log"), picker.candidates.map { it.entryPath })
        assertTrue(state.tabs.isEmpty())
    }

    @Test
    fun oneLogAndNoVideoOpensDirectlyWithoutShowingAPicker() {
        val dir = createTempDirectory("openlog-folder-one-log").toFile()
        val folder = File(dir, "unpacked").apply { mkdir() }
        File(folder, "main.log").writeText(sampleLog)
        val state = AppState(File(dir, "state.cache"))

        state.openFolder(folder)
        waitUntil { state.tabs.size == 1 && !state.isLoading }

        assertNull(state.pendingFolderPicker)
        assertEquals("main.log", state.tabs.single().filename)
    }

    // The core anti-PendingZipPicker guarantee (PendingFolderPicker's own doc comment): a
    // folder-opened tab must come out exactly as if the file had been opened directly — a real,
    // bare sourcePath (no "!" jar-URL-style separator), archiveCandidate == null, and tailable.
    @Test
    fun openedTabsHaveARealSourcePathNoArchiveCandidateAndCanBeTailed() {
        val dir = createTempDirectory("openlog-folder-tailable").toFile()
        val folder = File(dir, "unpacked").apply { mkdir() }
        val logFile = File(folder, "main.log").apply { writeText(sampleLog) }
        val state = AppState(File(dir, "state.cache"))

        state.openFolder(folder)
        waitUntil { state.tabs.size == 1 && !state.isLoading }

        val tab = state.tabs.single()
        assertEquals(logFile.absolutePath, tab.sourcePath)
        assertFalse('!' in (tab.sourcePath ?: ""))
        assertNull(tab.archiveCandidate)

        state.startTailing(tab.id)
        assertTrue(state.tab(tab.id)!!.tailing)
    }

    @Test
    fun oversizedChildDefersAsARealFileSplitSourceNotAnArchiveEntry() {
        val dir = createTempDirectory("openlog-folder-oversized").toFile()
        val folder = File(dir, "unpacked").apply { mkdir() }
        val bigLog = File(folder, "big.log").apply { writeText("A".repeat(5_000)) }
        val state = AppState(File(dir, "state.cache"))
        val candidate = com.indagium.utils.ZipLogCandidate(
            entryPath = "big.log",
            displayName = "big.log",
            sizeBytes = bigLog.length(),
        )

        // Threshold forced down to the fixture's own on-disk length so a 5KB file can exercise the
        // "oversized" branch without a multi-hundred-MB fixture — see openFolderEntries' doc
        // comment on why the real file length() (not the candidate's sizeBytes) is what gates this.
        val tabIds = state.openFolderEntries(folder, listOf(candidate), splitPromptThresholdBytes = bigLog.length())

        assertTrue(tabIds.isEmpty())
        assertTrue(state.tabs.isEmpty())
        val pending = state.pendingSplitPrompt
        assertTrue(pending != null, "expected the oversized folder child to trigger the split prompt")
        val source = assertIs<SplitSource.RealFile>(pending!!.sources.single())
        assertEquals(bigLog.absolutePath, source.file.absolutePath)
    }

    @Test
    fun rawDltFolderChildGetsSplitPromptWhenOversizedLikeAnyOtherFormat() {
        val dir = createTempDirectory("openlog-folder-dlt-oversized").toFile()
        val folder = File(dir, "unpacked").apply { mkdir() }
        val capture = File(folder, "capture.bin").apply { writeBytes(dltTestFrame("folder DLT")) }
        val state = AppState(File(dir, "state.cache"))
        val candidate = ZipLogCandidate(
            entryPath = "capture.bin",
            displayName = "capture.bin",
            sizeBytes = SPLIT_PROMPT_BYTES,
            kind = ZipLogCandidateKind.DLT,
        )

        // Frame-aware DLT splitting (Phase 4) removed the old DLT exclusion from this gate — a
        // large DLT child now defers into the split prompt exactly like any other format.
        val tabIds = state.openFolderEntries(folder, listOf(candidate), splitPromptThresholdBytes = 1L)

        assertTrue(tabIds.isEmpty())
        assertTrue(state.tabs.isEmpty())
        val pending = state.pendingSplitPrompt
        assertTrue(pending != null, "expected the oversized DLT folder child to trigger the split prompt")
        val source = assertIs<SplitSource.RealFile>(pending!!.sources.single())
        assertEquals(capture.absolutePath, source.file.absolutePath)
    }

    @Test
    fun rawDltFolderChildOpensNormallyAndCannotBeTailed() {
        val dir = createTempDirectory("openlog-folder-dlt").toFile()
        val folder = File(dir, "unpacked").apply { mkdir() }
        val capture = File(folder, "capture.bin").apply { writeBytes(dltTestFrame("folder DLT")) }
        val state = AppState(File(dir, "state.cache"))
        val candidate = ZipLogCandidate(
            entryPath = "capture.bin",
            displayName = "capture.bin",
            sizeBytes = capture.length(),
            kind = ZipLogCandidateKind.DLT,
        )

        val tabIds = state.openFolderEntries(folder, listOf(candidate))

        assertEquals(1, tabIds.size)
        assertNull(state.pendingSplitPrompt)
        waitUntil { state.tabs.size == 1 && !state.isLoading }
        val tab = state.tabs.single()
        assertEquals(capture.absolutePath, tab.sourcePath)
        assertEquals(LogFormat.DLT, tab.logFormat)
        state.startTailing(tab.id)
        assertFalse(state.tab(tab.id)!!.tailing)
    }

    @Test
    fun twoFoldersDroppedTogetherErrorsWithNothingOpened() {
        val dir = createTempDirectory("openlog-folder-two-folders").toFile()
        val first = File(dir, "first").apply { mkdir() }
        File(first, "a.log").writeText(sampleLog)
        val second = File(dir, "second").apply { mkdir() }
        File(second, "b.log").writeText(sampleLog)
        val state = AppState(File(dir, "state.cache"))

        state.openDroppedFiles(listOf(first, second))

        assertEquals("Folders were not opened", state.openError?.title)
        assertTrue(state.tabs.isEmpty())
        assertNull(state.pendingFolderPicker)
    }

    @Test
    fun openPathsHandlesAFolderAndAPlainLogTogetherRatherThanRejectingTheFolder() {
        val dir = createTempDirectory("openlog-folder-plus-log").toFile()
        val folder = File(dir, "unpacked").apply { mkdir() }
        File(folder, "inside.log").writeText(sampleLog)
        val standaloneLog = File(dir, "standalone.log").apply { writeText(sampleLog) }
        val state = AppState(File(dir, "state.cache"))

        state.openPaths(listOf(folder, standaloneLog))

        waitUntil { state.tabs.size == 2 && !state.isLoading }
        assertNull(state.openError)
        assertEquals(
            setOf("inside.log", "standalone.log"),
            state.tabs.map { it.filename }.toSet(),
        )
    }

    @Test
    fun folderVideoAttachesAsALocalFileSourceNotAnArchiveEntry() {
        val dir = createTempDirectory("openlog-folder-video").toFile()
        val folder = File(dir, "unpacked").apply { mkdir() }
        File(folder, "main.log").writeText(sampleLog)
        File(folder, "recording.mp4").writeText("not decoded by this test")
        val state = AppState(File(dir, "state.cache"))

        state.openFolder(folder)
        waitUntil { state.pendingFolderPicker != null }
        val picker = state.pendingFolderPicker!!
        assertEquals(listOf("recording.mp4"), picker.videoCandidates.map { it.entryPath })

        state.openFolderEntries(folder, picker.candidates, picker.videoCandidates.single())

        waitUntil { state.tabs.size == 1 && state.tabs.single().attachedVideo != null }
        val source = assertIs<VideoSource.LocalFile>(state.tabs.single().attachedVideo?.source)
        assertEquals(File(folder, "recording.mp4").absolutePath, source.path)
    }
}
