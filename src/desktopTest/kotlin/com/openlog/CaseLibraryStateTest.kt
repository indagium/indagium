package com.openlog

import com.openlog.cases.writeCaseNote
import com.openlog.model.AnnBlock
import com.openlog.model.Annotations
import com.openlog.model.Filter
import com.openlog.model.LogEntry
import com.openlog.model.LogLevel
import com.openlog.ui.AppState
import com.openlog.ui.mkTab
import java.io.File
import java.util.concurrent.TimeUnit
import kotlin.io.path.createTempDirectory
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * AppState-level behavior for the Case Library dialog (ui/CaseLibraryDialog.kt +
 * AppState.openCaseLibrary/requestLoadCase/confirmLoadCase). Ranking/limit-clamping is already
 * covered by com.openlog.cases.CaseSearchTest — this file is about the AppState glue: query/tag
 * seeding and the destructive-load confirmation gate.
 *
 * caseIndexFile() has no injectable seam on AppState (by design — see the feature brief), so these
 * tests go through the real on-disk case-index like CaseToolsGatewayTest already does; only
 * notesDir is injected. Search phrases/tags are namespaced with a "zzqxx" marker so they can't
 * collide with anything a real notes corpus might contain.
 *
 * requestLoadCase/confirmLoadCase resolve CaseSearch.getCase() on ioScope (never the Compose
 * thread — see AppState.requestLoadCase's doc comment), so every test that calls either now waits
 * for the resulting state change instead of asserting immediately afterward.
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
    fun loadingACaseIntoATabWithExistingNotesRequiresConfirmationInsteadOfLoadingImmediately() {
        val notesDir = createTempDirectory("openlog-case-lib-confirm").toFile()
        writeCaseNote(
            notesDir, "zzqxx_confirm_case", title = "Zzqxx confirm case",
            issueDescription = "zzqxx confirmation gate marker phrase",
            tags = listOf("ZzqxxConfirmTag"), decisiveTags = listOf("ZzqxxConfirmTag"),
        )
        val state = newState(notesDir)
        state.tabs = listOf(
            mkTab("withNotes", "sample.log", listOf(LogEntry(1, "10:00:00.000", LogLevel.I, "App", "hello"))).copy(
                annotations = Annotations(
                    issueDescription = "zzqxx confirmation gate marker phrase",
                    blocks = listOf(AnnBlock.Note("n1", "existing note text")),
                ),
            ),
        )

        state.openCaseLibrary("withNotes")
        waitUntil { state.caseLibraryResults.isNotEmpty() }
        val caseId = state.caseLibraryResults.first().id

        state.requestLoadCase(caseId)

        waitUntil { state.pendingCaseLoad != null }
        val pending = assertNotNull(state.pendingCaseLoad)
        assertEquals("withNotes", pending.tabId)
        assertEquals(caseId, pending.caseId)
        // The stock fixture note has both a .md and a matching .ann sidecar, so loading it takes
        // openNoteFile's whole-object-replace branch, not the append fallback.
        assertTrue(pending.replacesNotes)
        // Nothing mutated yet — the tab's original note block is still exactly there.
        assertEquals(listOf(AnnBlock.Note("n1", "existing note text")), state.tab("withNotes")!!.annotations.blocks)
    }

    @Test
    fun loadingACaseIntoATabWithTypedTextButNoBlocksStillRequiresConfirmation() {
        // Regression coverage for the gate's original hole: it used to check only
        // annotations.blocks.isNotEmpty(), so a tab with a typed issue description/prefix/suffix
        // but zero blocks (the normal state while still framing the problem — exactly when
        // openCaseLibrary's seeded query comes FROM that same issueDescription) would load
        // immediately with no confirmation and silently discard the write-up. The gate now
        // compares the whole Annotations object against a fresh Annotations().
        val notesDir = createTempDirectory("openlog-case-lib-textonly").toFile()
        writeCaseNote(
            notesDir, "zzqxx_textonly_case", title = "Zzqxx textonly case",
            issueDescription = "zzqxx textonly marker phrase",
            tags = listOf("ZzqxxTextonlyTag"), decisiveTags = listOf("ZzqxxTextonlyTag"),
        )
        val state = newState(notesDir)
        state.tabs = listOf(
            mkTab("textOnly", "sample3.log", listOf(LogEntry(1, "10:00:00.000", LogLevel.I, "App", "hello"))).copy(
                annotations = Annotations(
                    issueDescription = "zzqxx textonly marker phrase",
                    prefix = "typed prefix, never backed by a block",
                    suffix = "typed suffix, never backed by a block",
                ),
            ),
        )
        assertTrue(state.tab("textOnly")!!.annotations.blocks.isEmpty())

        state.openCaseLibrary("textOnly")
        waitUntil { state.caseLibraryResults.isNotEmpty() }

        state.requestLoadCase(state.caseLibraryResults.first().id)

        waitUntil { state.pendingCaseLoad != null }
        assertEquals("textOnly", state.pendingCaseLoad!!.tabId)
        // Still untouched — the gate fired instead of loading immediately.
        assertEquals("typed prefix, never backed by a block", state.tab("textOnly")!!.annotations.prefix)
        assertEquals("typed suffix, never backed by a block", state.tab("textOnly")!!.annotations.suffix)
    }

    @Test
    fun loadingACaseIntoATabWithNoNotesLoadsImmediatelyWithNoConfirmation() {
        val notesDir = createTempDirectory("openlog-case-lib-immediate").toFile()
        writeCaseNote(
            notesDir, "zzqxx_immediate_case", title = "Zzqxx immediate case",
            issueDescription = "zzqxx immediate load marker phrase",
            tags = listOf("ZzqxxImmediateTag"), decisiveTags = listOf("ZzqxxImmediateTag"),
        )
        val state = newState(notesDir)
        // A genuinely fresh tab — the default Annotations(), not merely "no blocks" (mkTab's own
        // default annotations has a non-empty prefix, so it's overridden here to a real fresh one).
        state.tabs = listOf(
            mkTab("empty", "sample2.log", listOf(LogEntry(1, "10:00:00.000", LogLevel.I, "App", "hello"))).copy(
                annotations = Annotations(),
            ),
        )
        assertEquals(Annotations(), state.tab("empty")!!.annotations)

        // The tab has no issueDescription to seed the query from (that's the whole point of "no
        // notes" now), so search for the case explicitly instead of relying on the seed.
        state.openCaseLibrary("empty")
        state.updateCaseLibraryQuery("zzqxx immediate load marker phrase")
        waitUntil { state.caseLibraryResults.isNotEmpty() }
        val caseId = state.caseLibraryResults.first().id

        state.requestLoadCase(caseId)

        waitUntil { state.tab("empty")!!.annotations.blocks.isNotEmpty() }
        assertNull(state.pendingCaseLoad)
        assertEquals("zzqxx immediate load marker phrase", state.tab("empty")!!.annotations.issueDescription)
    }

    @Test
    fun loadingACaseWithOnlyAHandCopiedAnnFileAndNoMdLoadsFromTheAnnAlone() {
        // loadCaseIntoTab's mdPath == null branch: a note with a lone `.ann` (hand-copied into the
        // notes folder, no paired `.md`) still has to resolve to *some* file to hand to
        // openNoteFile — CaseRecord.mdPath is null here, so it must fall back to annPath.
        val notesDir = createTempDirectory("openlog-case-lib-annonly").toFile()
        writeCaseNote(
            notesDir, "zzqxx_annonly_case", title = "Zzqxx annonly case",
            issueDescription = "zzqxx annonly marker phrase",
            tags = listOf("ZzqxxAnnonlyTag"), decisiveTags = listOf("ZzqxxAnnonlyTag"),
            writeMd = false,
        )
        val state = newState(notesDir)
        state.tabs = listOf(
            mkTab("empty", "sample4.log", listOf(LogEntry(1, "10:00:00.000", LogLevel.I, "App", "hello"))).copy(
                annotations = Annotations(),
            ),
        )

        state.openCaseLibrary("empty")
        state.updateCaseLibraryQuery("zzqxx annonly marker phrase")
        waitUntil { state.caseLibraryResults.isNotEmpty() }
        val caseId = state.caseLibraryResults.first().id

        state.requestLoadCase(caseId)

        waitUntil { state.tab("empty")!!.annotations.decisiveTags == listOf("ZzqxxAnnonlyTag") }
        assertNull(state.pendingCaseLoad)
        assertEquals("zzqxx annonly marker phrase", state.tab("empty")!!.annotations.issueDescription)
    }

    @Test
    fun confirmingAPendingCaseLoadReplacesTheTargetTabsAnnotations() {
        val notesDir = createTempDirectory("openlog-case-lib-confirm2").toFile()
        writeCaseNote(
            notesDir, "zzqxx_confirmed_case", title = "Zzqxx confirmed case",
            issueDescription = "zzqxx confirmed load marker phrase",
            tags = listOf("ZzqxxConfirmedTag"), decisiveTags = listOf("ZzqxxConfirmedTag"),
            extraMdText = "Root cause: the confirmed load marker.",
        )
        val state = newState(notesDir)
        state.tabs = listOf(
            mkTab("withNotes", "sample.log", listOf(LogEntry(1, "10:00:00.000", LogLevel.I, "App", "hello"))).copy(
                annotations = Annotations(
                    issueDescription = "zzqxx confirmed load marker phrase",
                    blocks = listOf(AnnBlock.Note("n1", "existing note text")),
                ),
            ),
        )
        // Starts empty so its later value is real proof the load happened, unlike issueDescription
        // below (which is identical before and after by construction of this fixture, so equaling
        // it proves nothing about whether the load actually ran).
        assertTrue(state.tab("withNotes")!!.annotations.decisiveTags.isEmpty())

        state.openCaseLibrary("withNotes")
        waitUntil { state.caseLibraryResults.isNotEmpty() }
        state.requestLoadCase(state.caseLibraryResults.first().id)
        waitUntil { state.pendingCaseLoad != null }

        state.confirmLoadCase()

        // Proof the case's own content actually arrived — decisiveTags started empty and only
        // exists on the loaded case's .ann sidecar, unlike issueDescription which was identical
        // on the tab before the load ever happened (see the seeded fixture above).
        waitUntil { state.tab("withNotes")!!.annotations.decisiveTags == listOf("ZzqxxConfirmedTag") }
        assertNull(state.pendingCaseLoad)
        // The confirmed load replaced the old block entirely — the pre-existing note text is gone.
        assertTrue(state.tab("withNotes")!!.annotations.blocks.none { it is AnnBlock.Note && it.text == "existing note text" })
    }

    @Test
    fun cancelingAPendingCaseLoadLeavesTheTargetTabsAnnotationsUnchanged() {
        val notesDir = createTempDirectory("openlog-case-lib-cancel").toFile()
        writeCaseNote(
            notesDir, "zzqxx_canceled_case", title = "Zzqxx canceled case",
            issueDescription = "zzqxx canceled load marker phrase",
            tags = listOf("ZzqxxCanceledTag"), decisiveTags = listOf("ZzqxxCanceledTag"),
        )
        val state = newState(notesDir)
        state.tabs = listOf(
            mkTab("withNotes", "sample.log", listOf(LogEntry(1, "10:00:00.000", LogLevel.I, "App", "hello"))).copy(
                annotations = Annotations(
                    issueDescription = "zzqxx canceled load marker phrase",
                    blocks = listOf(AnnBlock.Note("n1", "existing note text")),
                ),
            ),
        )

        state.openCaseLibrary("withNotes")
        waitUntil { state.caseLibraryResults.isNotEmpty() }
        state.requestLoadCase(state.caseLibraryResults.first().id)
        waitUntil { state.pendingCaseLoad != null }

        state.cancelLoadCase()

        assertNull(state.pendingCaseLoad)
        // requestLoadCase/confirmLoadCase resolve on ioScope (finding #2), so a synchronous check
        // right after cancelLoadCase() can't prove a delayed/mis-ordered async write from some
        // still-in-flight or newly-fired coroutine never lands afterward. Give one a real chance
        // to, then re-check both the confirmation state and the dialog's own tab binding.
        Thread.sleep(500)
        assertNull(state.pendingCaseLoad)
        assertEquals("withNotes", state.caseLibraryTabId)
        assertEquals(listOf(AnnBlock.Note("n1", "existing note text")), state.tab("withNotes")!!.annotations.blocks)
    }
}
