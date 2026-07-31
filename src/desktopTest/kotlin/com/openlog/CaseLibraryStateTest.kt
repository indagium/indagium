package com.openlog

import com.openlog.cases.writeCaseNote
import com.openlog.model.AnnBlock
import com.openlog.model.Annotations
import com.openlog.model.Filter
import com.openlog.model.LogEntry
import com.openlog.model.LogLevel
import com.openlog.ui.AppState
import com.openlog.ui.casePreviewCopyText
import com.openlog.ui.mkTab
import java.io.File
import java.util.concurrent.TimeUnit
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlin.io.path.createTempDirectory
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * AppState-level behavior for the Case Library dialog (ui/CaseLibraryDialog.kt +
 * AppState.openCaseLibrary/previewCase/reopenInvestigation). Ranking/limit-clamping is already
 * covered by com.openlog.cases.CaseSearchTest — this file is about the AppState glue: query/tag
 * seeding and "Reopen investigation" (the replacement for the old destructive "Load into this
 * tab" — see AppState.reopenInvestigation's doc comment for why that was replaced).
 *
 * caseIndexFile() has no injectable seam on AppState (by design — see the feature brief), so these
 * tests go through the real on-disk case-index like CaseToolsGatewayTest already does; only
 * notesDir is injected. Search phrases/tags are namespaced with a "zzqxx" marker so they can't
 * collide with anything a real notes corpus might contain.
 *
 * previewCase/reopenInvestigation resolve CaseSearch.getCase() (and, for reopen, the actual file
 * open) on ioScope (never the Compose thread), so every test that calls either now waits for the
 * resulting state change instead of asserting immediately afterward.
 */
class CaseLibraryStateTest {
    private var openState: AppState? = null

    @AfterTest
    fun tearDown() {
        openState?.close()
    }

    private fun newState(notesDir: File): AppState {
        val state = AppState(autosaveFile = File.createTempFile("openlog-case-lib-test", ".cache"), notesDir = notesDir)
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

    private fun buildZipFixture(dir: File, name: String, entries: Map<String, String>): File {
        val file = File(dir, name)
        ZipOutputStream(file.outputStream()).use { zos ->
            entries.forEach { (path, content) ->
                zos.putNextEntry(ZipEntry(path))
                zos.write(content.toByteArray())
                zos.closeEntry()
            }
        }
        return file
    }

    @Test
    fun openingCaseLibrarySeedsTheQueryFromTheTabsIssueDescriptionAndActiveTags() {
        val notesDir = createTempDirectory("openlog-case-lib-seed").toFile()
        val state = newState(notesDir)
        state.tabs = listOf(
            mkTab("t1", "sample.log", listOf(LogEntry(1, "10:00:00.000", LogLevel.I, "App", "hello"))).copy(
                annotations = Annotations(issueDescription = "zzqxx application not responding on cold start"),
                filter = Filter(activeTags = setOf("ZzqxxActivityManager", "ZzqxxPackageManager")),
            ),
        )

        state.openCaseLibrary("t1")

        assertEquals("t1", state.caseLibraryTabId)
        assertEquals("zzqxx application not responding on cold start", state.caseLibraryQuery)
        assertEquals(setOf("ZzqxxActivityManager", "ZzqxxPackageManager"), state.caseLibraryTags.toSet())
    }

    @Test
    fun previewSurfacesTheIssueDescriptionSourceFilenameAppVersionDecisiveTagsAndFilterSummary() {
        val notesDir = createTempDirectory("openlog-case-lib-preview-meta").toFile()
        val dir = createTempDirectory("openlog-case-lib-preview-log").toFile()
        val logFile = File(dir, "device_manager.log").apply {
            writeText("06-26 10:00:00.000  123  456 I DeviceManager: root cause line\n")
        }
        writeCaseNote(
            notesDir, "zzqxx_preview_meta_case", title = "Zzqxx preview meta case",
            issueDescription = "zzqxx preview metadata marker phrase",
            tags = listOf("ZzqxxPreviewTag"), decisiveTags = listOf("ZzqxxPreviewTag"),
            appVersion = "3.2.1", sourcePath = logFile.absolutePath,
            filter = Filter(activeTags = setOf("ZzqxxPreviewTag"), levels = setOf(LogLevel.W, LogLevel.E, LogLevel.A)),
        )
        val state = newState(notesDir)

        state.openCaseLibrary("phantom")
        state.updateCaseLibraryQuery("zzqxx preview metadata marker phrase")
        waitUntil { state.caseLibraryResults.isNotEmpty() }
        val caseId = state.caseLibraryResults.first().id

        state.previewCase(caseId)

        waitUntil { state.caseLibraryPreview?.id == caseId }
        val preview = state.caseLibraryPreview!!
        assertEquals("zzqxx preview metadata marker phrase", preview.issueDescription)
        assertEquals("device_manager.log", preview.sourceFilename)
        assertEquals("3.2.1", preview.appVersion)
        assertEquals(listOf("ZzqxxPreviewTag"), preview.decisiveTags)
        assertEquals("tag=ZzqxxPreviewTag, level≥W", preview.filterSummary)
        assertNull(preview.reopenDisabledReason)
    }

    @Test
    fun previewShowsFilterNotRecordedForALegacyNoteWithNoFilterField() {
        val notesDir = createTempDirectory("openlog-case-lib-preview-nofilter").toFile()
        writeCaseNote(
            notesDir, "zzqxx_nofilter_case", title = "Zzqxx nofilter case",
            issueDescription = "zzqxx nofilter marker phrase",
            tags = listOf("ZzqxxNofilterTag"),
        )
        val state = newState(notesDir)

        state.openCaseLibrary("phantom")
        state.updateCaseLibraryQuery("zzqxx nofilter marker phrase")
        waitUntil { state.caseLibraryResults.isNotEmpty() }
        val caseId = state.caseLibraryResults.first().id

        state.previewCase(caseId)

        waitUntil { state.caseLibraryPreview?.id == caseId }
        assertEquals("Filter not recorded", state.caseLibraryPreview!!.filterSummary)
    }

    @Test
    fun reopeningAnInvestigationOpensTheOriginalLogInANewTabAttachesTheCasesNotesAndLeavesTheCurrentTabUntouched() {
        val notesDir = createTempDirectory("openlog-case-lib-reopen").toFile()
        val dir = createTempDirectory("openlog-case-lib-reopen-log").toFile()
        val logFile = File(dir, "original.log").apply {
            writeText("06-26 10:00:00.000  123  456 I DeviceManager: root cause line\n")
        }
        writeCaseNote(
            notesDir, "zzqxx_reopen_case", title = "Zzqxx reopen case",
            issueDescription = "zzqxx reopen marker phrase",
            tags = listOf("ZzqxxReopenTag"), decisiveTags = listOf("ZzqxxReopenTag"),
            sourcePath = logFile.absolutePath,
        )
        val state = newState(notesDir)
        state.tabs = listOf(
            mkTab("current", "unrelated.log", listOf(LogEntry(1, "10:00:00.000", LogLevel.I, "App", "hello"))).copy(
                annotations = Annotations(blocks = listOf(AnnBlock.Note("n1", "existing note text on current tab"))),
            ),
        )
        state.activeTabId = "current"

        state.openCaseLibrary("current")
        state.updateCaseLibraryQuery("zzqxx reopen marker phrase")
        waitUntil { state.caseLibraryResults.isNotEmpty() }
        val caseId = state.caseLibraryResults.first().id
        state.previewCase(caseId)
        waitUntil { state.caseLibraryPreview?.id == caseId }
        assertNull(state.caseLibraryPreview!!.reopenDisabledReason)

        state.reopenInvestigation(caseId)

        // Waits for the notes to actually land, not just the tab to appear — reopenInvestigation
        // opens the tab and attaches notes in the same coroutine, but a bare tab-count check can
        // observe the moment right after the tab publishes and before openNoteFile runs.
        waitUntil { state.tabs.size == 2 && state.tabs.any { it.id != "current" && it.annotations.decisiveTags.isNotEmpty() } }
        // The tab the dialog was opened from is completely untouched — same annotations object,
        // still the only tab that existed before this call.
        assertEquals(
            listOf(AnnBlock.Note("n1", "existing note text on current tab")),
            state.tab("current")!!.annotations.blocks,
        )
        val newTab = state.tabs.first { it.id != "current" }
        assertEquals(logFile.absolutePath, newTab.sourcePath)
        // Notes came from the case's .ann sidecar (openNoteFile's whole-object-replace branch) and
        // resolve against THIS tab's own log, not "current"'s.
        assertEquals(listOf("ZzqxxReopenTag"), newTab.annotations.decisiveTags)
        // The dialog closed itself — no confirmation step needed any more, nothing was destroyed.
        assertNull(state.caseLibraryTabId)
    }

    @Test
    fun reopenIsDisabledWithAClearReasonWhenNoSourcePathWasRecorded() {
        val notesDir = createTempDirectory("openlog-case-lib-reopen-nosource").toFile()
        writeCaseNote(
            notesDir, "zzqxx_nosource_case", title = "Zzqxx nosource case",
            issueDescription = "zzqxx nosource marker phrase",
            tags = listOf("ZzqxxNosourceTag"),
        )
        val state = newState(notesDir)

        state.openCaseLibrary("phantom")
        state.updateCaseLibraryQuery("zzqxx nosource marker phrase")
        waitUntil { state.caseLibraryResults.isNotEmpty() }
        val caseId = state.caseLibraryResults.first().id
        state.previewCase(caseId)
        waitUntil { state.caseLibraryPreview?.id == caseId }

        assertEquals("Original log not found", state.caseLibraryPreview!!.reopenDisabledReason)

        // Calling it anyway (e.g. a stray click racing the disabled state) must be a pure no-op.
        state.reopenInvestigation(caseId)
        Thread.sleep(300)
        assertTrue(state.tabs.isEmpty())
    }

    @Test
    fun reopenIsDisabledWithAClearReasonWhenTheOriginalLogFileNoLongerExists() {
        val notesDir = createTempDirectory("openlog-case-lib-reopen-missing").toFile()
        val dir = createTempDirectory("openlog-case-lib-reopen-missing-log").toFile()
        val goneFile = File(dir, "gone.log")
        writeCaseNote(
            notesDir, "zzqxx_missing_case", title = "Zzqxx missing case",
            issueDescription = "zzqxx missing marker phrase",
            tags = listOf("ZzqxxMissingTag"), sourcePath = goneFile.absolutePath,
        )
        val state = newState(notesDir)

        state.openCaseLibrary("phantom")
        state.updateCaseLibraryQuery("zzqxx missing marker phrase")
        waitUntil { state.caseLibraryResults.isNotEmpty() }
        val caseId = state.caseLibraryResults.first().id
        state.previewCase(caseId)
        waitUntil { state.caseLibraryPreview?.id == caseId }

        assertEquals("Original log not found", state.caseLibraryPreview!!.reopenDisabledReason)
    }

    @Test
    fun openingNotesOnlyCreatesANewLoglessTabWithTheNotesAttachedAndLeavesExistingTabsUntouched() {
        val notesDir = createTempDirectory("openlog-case-lib-notes-only").toFile()
        // No sourcePath recorded at all — "Reopen investigation" would refuse this case outright,
        // but "Open notes only" must still work: the notes are the durable artifact.
        writeCaseNote(
            notesDir, "zzqxx_notesonly_case", title = "Zzqxx notesonly case",
            issueDescription = "zzqxx notesonly marker phrase",
            tags = listOf("ZzqxxNotesOnlyTag"), decisiveTags = listOf("ZzqxxNotesOnlyTag"),
        )
        val state = newState(notesDir)
        state.tabs = listOf(
            mkTab("current", "unrelated.log", listOf(LogEntry(1, "10:00:00.000", LogLevel.I, "App", "hello"))).copy(
                annotations = Annotations(blocks = listOf(AnnBlock.Note("n1", "existing note text on current tab"))),
            ),
        )
        state.activeTabId = "current"

        state.openCaseLibrary("current")
        state.updateCaseLibraryQuery("zzqxx notesonly marker phrase")
        waitUntil { state.caseLibraryResults.isNotEmpty() }
        val caseId = state.caseLibraryResults.first().id
        state.previewCase(caseId)
        waitUntil { state.caseLibraryPreview?.id == caseId }
        assertEquals("Original log not found", state.caseLibraryPreview!!.reopenDisabledReason)

        state.openCaseNotesOnly(caseId)

        waitUntil { state.tabs.size == 2 && state.tabs.any { it.id != "current" && it.annotations.decisiveTags.isNotEmpty() } }
        // The tab the dialog was opened from is completely untouched.
        assertEquals(
            listOf(AnnBlock.Note("n1", "existing note text on current tab")),
            state.tab("current")!!.annotations.blocks,
        )
        val newTab = state.tabs.first { it.id != "current" }
        assertTrue(newTab.logData.isEmpty())
        assertEquals("Zzqxx notesonly case", newTab.filename)
        assertEquals(listOf("ZzqxxNotesOnlyTag"), newTab.annotations.decisiveTags)
        // The dialog closed itself, same as reopenInvestigation on success.
        assertNull(state.caseLibraryTabId)
    }

    @Test
    fun openingNotesOnlyStillWorksWhenTheOriginalLogFileNoLongerExists() {
        val notesDir = createTempDirectory("openlog-case-lib-notes-only-missing").toFile()
        val dir = createTempDirectory("openlog-case-lib-notes-only-missing-log").toFile()
        val goneFile = File(dir, "gone.log")
        writeCaseNote(
            notesDir, "zzqxx_notesonly_missing_case", title = "Zzqxx notesonly missing case",
            issueDescription = "zzqxx notesonly missing marker phrase",
            tags = listOf("ZzqxxNotesOnlyMissingTag"), decisiveTags = listOf("ZzqxxNotesOnlyMissingTag"),
            sourcePath = goneFile.absolutePath,
        )
        val state = newState(notesDir)

        state.openCaseLibrary("phantom")
        state.updateCaseLibraryQuery("zzqxx notesonly missing marker phrase")
        waitUntil { state.caseLibraryResults.isNotEmpty() }
        val caseId = state.caseLibraryResults.first().id
        state.previewCase(caseId)
        waitUntil { state.caseLibraryPreview?.id == caseId }
        assertEquals("Original log not found", state.caseLibraryPreview!!.reopenDisabledReason)

        state.openCaseNotesOnly(caseId)

        waitUntil { state.tabs.size == 1 && state.tabs.single().annotations.decisiveTags.isNotEmpty() }
        val newTab = state.tabs.single()
        assertTrue(newTab.logData.isEmpty())
        assertEquals(listOf("ZzqxxNotesOnlyMissingTag"), newTab.annotations.decisiveTags)
    }

    @Test
    fun copyingAPreviewedCaseIncludesTheIssueDescriptionThatBuildMdOmits() {
        val notesDir = createTempDirectory("openlog-case-lib-copy").toFile()
        writeCaseNote(
            notesDir, "zzqxx_copy_case", title = "Zzqxx copy case",
            issueDescription = "zzqxx copy marker phrase, a long description of the actual bug",
            tags = listOf("ZzqxxCopyTag"), decisiveTags = listOf("ZzqxxCopyTag"),
        )
        val state = newState(notesDir)

        state.openCaseLibrary("phantom")
        state.updateCaseLibraryQuery("zzqxx copy marker phrase")
        waitUntil { state.caseLibraryResults.isNotEmpty() }
        val caseId = state.caseLibraryResults.first().id
        state.previewCase(caseId)
        waitUntil { state.caseLibraryPreview?.id == caseId }
        val preview = state.caseLibraryPreview!!

        // The rendered note body alone (what buildMd/reconstructAnnotationsText produce, and what
        // CaseIndexer.readCaseText returns as preview.text) never contains the issue description —
        // confirms this test is actually exercising the gap, not a tautology.
        assertTrue(!preview.text.contains("a long description of the actual bug"))

        val copied = casePreviewCopyText(preview)

        assertTrue(copied.contains("zzqxx copy marker phrase, a long description of the actual bug"))
    }

    @Test
    fun reopeningAnArchiveBackedInvestigationExtractsTheEntryInsteadOfTreatingTheCompositePathAsAPlainFile() {
        val notesDir = createTempDirectory("openlog-case-lib-reopen-archive").toFile()
        val dir = createTempDirectory("openlog-case-lib-reopen-archive-zip").toFile()
        val zip = buildZipFixture(
            dir, "bugreport.zip",
            mapOf("FS/data/anr/main_log.txt" to "06-26 10:00:00.000  123  456 I DeviceManager: from the archive\n"),
        )
        val sourcePath = "${zip.absolutePath}!FS/data/anr/main_log.txt"
        writeCaseNote(
            notesDir, "zzqxx_archive_case", title = "Zzqxx archive case",
            issueDescription = "zzqxx archive marker phrase",
            tags = listOf("ZzqxxArchiveTag"), decisiveTags = listOf("ZzqxxArchiveTag"),
            sourcePath = sourcePath,
        )
        // A naive File(sourcePath) would report as not-a-file (the "!"-joined string is not a real
        // path on disk) — confirms the fixture actually exercises the archive-composite path rather
        // than accidentally being openable as a plain file.
        assertTrue(!File(sourcePath).isFile)
        val state = newState(notesDir)

        state.openCaseLibrary("phantom")
        state.updateCaseLibraryQuery("zzqxx archive marker phrase")
        waitUntil { state.caseLibraryResults.isNotEmpty() }
        val caseId = state.caseLibraryResults.first().id
        state.previewCase(caseId)
        waitUntil { state.caseLibraryPreview?.id == caseId }
        assertNull(state.caseLibraryPreview!!.reopenDisabledReason)

        state.reopenInvestigation(caseId)

        // See the analogous wait in the plain-file reopen test above — tab count alone can be true
        // before openNoteFile actually runs.
        waitUntil { state.tabs.size == 1 && !state.isLoading && state.tabs.single().annotations.decisiveTags.isNotEmpty() }
        val newTab = state.tabs.single()
        assertEquals(sourcePath, newTab.sourcePath)
        assertEquals("from the archive", newTab.logData.single().msg)
        assertEquals(listOf("ZzqxxArchiveTag"), newTab.annotations.decisiveTags)
    }
}
