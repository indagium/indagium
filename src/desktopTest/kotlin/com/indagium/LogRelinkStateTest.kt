package com.indagium

import com.indagium.cases.writeCaseNote
import com.indagium.model.AnnBlock
import com.indagium.model.Annotations
import com.indagium.ui.AppState
import com.indagium.ui.annotationsFromToken
import com.indagium.ui.annotationsToken
import com.indagium.ui.mkTab
import com.indagium.utils.computeLogFingerprint
import com.indagium.utils.parseLogcat
import java.io.File
import java.util.concurrent.TimeUnit
import kotlin.io.path.createTempDirectory
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

// %03d wraps the fake millisecond field back to 0 at 1000 so it always formats as exactly 3
// digits, matching threadtime's real MM-DD HH:MM:SS.mmm shape — the value itself is arbitrary.
private const val MILLIS_WRAP = 1000

/**
 * AppState-level behavior for "Locate log…" (relink-log Change 2b/2c) — AppState.
 * locateLogForCase/locateLogForTab/beginLogRelink/confirmLogRelink/cancelLogRelink. The hazard
 * this verifies against: AnnBlock.LogRef.logIds are positional per file, so relinking to a
 * genuinely different capture must never attach silently.
 *
 * Follows CaseLibraryStateTest's own conventions (real on-disk case index via writeCaseNote/
 * openCaseLibrary/previewCase; "zzqxx"-namespaced search phrases; waitUntil for the ioScope work
 * both previewCase and the relink flow itself do).
 */
class LogRelinkStateTest {
    private var openState: AppState? = null

    @AfterTest
    fun tearDown() {
        openState?.close()
    }

    private fun newState(notesDir: File): AppState {
        val state = AppState(autosaveFile = File.createTempFile("openlog-relink-test", ".cache"), notesDir = notesDir)
        openState = state
        return state
    }

    private fun waitUntil(timeoutMs: Long = 5_000, condition: () -> Boolean) {
        val deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(timeoutMs)
        while (System.nanoTime() < deadline) {
            if (condition()) return
            Thread.sleep(10)
        }
        assertTrue(condition())
    }

    private fun writeLogFile(dir: File, name: String, lines: List<String>): File =
        File(dir, name).apply { writeText(lines.joinToString("\n") + "\n") }

    private fun threadtimeLines(tag: String, count: Int): List<String> =
        (1..count).map { i -> "06-26 10:00:00.%03d  111  222 I %s: line %d".format(i % MILLIS_WRAP, tag, i) }

    /** Preview a case reachable only through "Locate log…" — sourcePath deliberately absent, so
     *  ReopenInvestigationButton would be disabled and LocateLogButton shown, mirroring
     *  CaseLibraryStateTest's own "openingNotesOnly..." fixtures. */
    private fun previewLocateOnlyCase(state: AppState, notesDir: File, baseName: String, marker: String, fingerprint: String?): String {
        val (_, annFile) = writeCaseNote(
            notesDir, baseName, title = baseName,
            issueDescription = marker,
            tags = listOf("ZzqxxRelinkTag"), decisiveTags = listOf("ZzqxxRelinkTag"),
        )
        // writeCaseNote has no fingerprint parameter of its own (it's a shared fixture used by
        // several other test files) — patch the fingerprint into the .ann it just wrote instead of
        // widening that helper's already-long parameter list.
        if (fingerprint != null) {
            val withFingerprint = requireNotNull(annFile.readText().annotationsFromToken()).copy(fingerprint = fingerprint)
            annFile.writeText(withFingerprint.annotationsToken())
        }
        state.openCaseLibrary("phantom")
        state.updateCaseLibraryQuery(marker)
        waitUntil { state.caseLibraryResults.isNotEmpty() }
        val caseId = state.caseLibraryResults.first().id
        state.previewCase(caseId)
        waitUntil { state.caseLibraryPreview?.id == caseId }
        assertEquals("Original log not found", state.caseLibraryPreview!!.reopenDisabledReason)
        return caseId
    }

    @Test
    fun locateLogForCaseAttachesSilentlyWhenTheFingerprintMatches() {
        val notesDir = createTempDirectory("openlog-relink-match-notes").toFile()
        val logDir = createTempDirectory("openlog-relink-match-logs").toFile()
        val original = writeLogFile(logDir, "original.log", threadtimeLines("DeviceManager", 30))
        val expectedFingerprint = computeLogFingerprint(parseLogcat(original))
        val state = newState(notesDir)
        val caseId = previewLocateOnlyCase(state, notesDir, "zzqxx_relink_match", "zzqxx relink match marker", expectedFingerprint)

        // Same content, different name/location — exactly the "found it under a different name"
        // scenario this feature exists for.
        val renamed = writeLogFile(logDir, "renamed_copy.log", threadtimeLines("DeviceManager", 30))
        state.locateLogForCase(caseId, renamed)

        waitUntil { state.tabs.any { it.sourcePath == renamed.absolutePath && it.annotations.decisiveTags.isNotEmpty() } }
        assertNull(state.pendingLogRelink, "a match must never raise the mismatch-warning dialog")
        assertNull(state.logRelinkUnverifiedTabId, "a confirmed match is silent, not flagged as unverifiable")
        val newTab = state.tabs.single { it.sourcePath == renamed.absolutePath }
        assertEquals(listOf("ZzqxxRelinkTag"), newTab.annotations.decisiveTags)
    }

    @Test
    fun locateLogForCaseHoldsBackTheAttachAndWarnsWhenTheFingerprintDoesNotMatch() {
        val notesDir = createTempDirectory("openlog-relink-mismatch-notes").toFile()
        val logDir = createTempDirectory("openlog-relink-mismatch-logs").toFile()
        val original = writeLogFile(logDir, "original.log", threadtimeLines("DeviceManager", 30))
        val expectedFingerprint = computeLogFingerprint(parseLogcat(original))
        val state = newState(notesDir)
        val caseId = previewLocateOnlyCase(state, notesDir, "zzqxx_relink_mismatch", "zzqxx relink mismatch marker", expectedFingerprint)

        // A different capture of the same bug: same rough shape, different actual content — the
        // exact hazard AnnBlock.LogRef.logIds being positional-per-file creates.
        val differentCapture = writeLogFile(logDir, "different_run.log", threadtimeLines("UnrelatedTag", 12))
        state.locateLogForCase(caseId, differentCapture)

        waitUntil { state.pendingLogRelink != null }
        val pending = state.pendingLogRelink!!
        assertEquals("different_run.log", pending.fileName)
        // The log is already open as its own plain tab — but nothing has been attached to it yet.
        val openTab = requireNotNull(state.tabs.find { it.id == pending.newTabId })
        assertTrue(openTab.annotations.decisiveTags.isEmpty(), "attach must be held back until the user decides")

        state.confirmLogRelink()

        waitUntil { state.tab(pending.newTabId)?.annotations?.decisiveTags?.isNotEmpty() == true }
        assertNull(state.pendingLogRelink)
        assertEquals(listOf("ZzqxxRelinkTag"), state.tab(pending.newTabId)!!.annotations.decisiveTags)
    }

    @Test
    fun cancellingAMismatchWarningLeavesTheOpenedTabWithoutAnyNotesAttached() {
        val notesDir = createTempDirectory("openlog-relink-cancel-notes").toFile()
        val logDir = createTempDirectory("openlog-relink-cancel-logs").toFile()
        val original = writeLogFile(logDir, "original.log", threadtimeLines("DeviceManager", 30))
        val expectedFingerprint = computeLogFingerprint(parseLogcat(original))
        val state = newState(notesDir)
        val caseId = previewLocateOnlyCase(state, notesDir, "zzqxx_relink_cancel", "zzqxx relink cancel marker", expectedFingerprint)
        val differentCapture = writeLogFile(logDir, "different_run.log", threadtimeLines("UnrelatedTag", 12))

        state.locateLogForCase(caseId, differentCapture)
        waitUntil { state.pendingLogRelink != null }
        val newTabId = state.pendingLogRelink!!.newTabId

        state.cancelLogRelink()

        assertNull(state.pendingLogRelink)
        // The tab stays open (it's a perfectly ordinary tab now) but no notes ever landed on it.
        assertTrue(state.tab(newTabId)!!.annotations.blocks.isEmpty())
        Thread.sleep(200)
        assertTrue(state.tab(newTabId)!!.annotations.blocks.isEmpty(), "cancel must be permanent, not racy")
    }

    @Test
    fun locateLogForCaseAttachesAutomaticallyButFlagsAsUnverifiedWhenNoFingerprintWasRecorded() {
        val notesDir = createTempDirectory("openlog-relink-nofp-notes").toFile()
        val logDir = createTempDirectory("openlog-relink-nofp-logs").toFile()
        val state = newState(notesDir)
        // fingerprint = null (the default) — a note saved before Change 2a existed.
        val caseId = previewLocateOnlyCase(state, notesDir, "zzqxx_relink_nofp", "zzqxx relink nofp marker", fingerprint = null)
        val anyLog = writeLogFile(logDir, "whatever.log", threadtimeLines("DeviceManager", 10))

        state.locateLogForCase(caseId, anyLog)

        waitUntil { state.tabs.any { it.sourcePath == anyLog.absolutePath && it.annotations.decisiveTags.isNotEmpty() } }
        assertNull(state.pendingLogRelink, "unverifiable must never be treated as a mismatch")
        val newTab = state.tabs.single { it.sourcePath == anyLog.absolutePath }
        assertEquals(newTab.id, state.logRelinkUnverifiedTabId, "unverifiable must be flagged, distinctly from a silent match")

        state.dismissLogRelinkUnverifiedNotice()
        assertNull(state.logRelinkUnverifiedTabId)
    }

    @Test
    fun locateLogForTabCopiesTheLogLessTabsOwnInMemoryAnnotationsRatherThanRereadingDisk() {
        val notesDir = createTempDirectory("openlog-relink-fromtab-notes").toFile()
        val logDir = createTempDirectory("openlog-relink-fromtab-logs").toFile()
        val original = writeLogFile(logDir, "original.log", threadtimeLines("DeviceManager", 20))
        val expectedFingerprint = computeLogFingerprint(parseLogcat(original))
        val state = newState(notesDir)
        // A log-less tab holding annotations only in memory (e.g. edited since the last save,
        // never written back to disk) — exactly what openCaseNotesOnly/a blank tab produces.
        val loglessTab = mkTab("logless", "Some case", emptyList()).copy(
            logData = emptyList(),
            annotations = Annotations(
                blocks = listOf(AnnBlock.Note("n1", "unsaved observation")),
                fingerprint = expectedFingerprint,
            ),
        )
        state.tabs = listOf(loglessTab)

        val renamed = writeLogFile(logDir, "renamed.log", threadtimeLines("DeviceManager", 20))
        state.locateLogForTab("logless", renamed)

        waitUntil { state.tabs.size == 2 && state.tabs.any { it.id != "logless" && it.annotations.blocks.isNotEmpty() } }
        assertNull(state.pendingLogRelink)
        val newTab = state.tabs.first { it.id != "logless" }
        assertEquals(listOf(AnnBlock.Note("n1", "unsaved observation")), newTab.annotations.blocks)
        // The original log-less tab is left exactly as it was.
        assertTrue(state.tab("logless")!!.logData.isEmpty())
        assertEquals(listOf(AnnBlock.Note("n1", "unsaved observation")), state.tab("logless")!!.annotations.blocks)
    }
}
