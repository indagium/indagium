package com.indagium

import com.indagium.diagram3.Seq3Authoring
import com.indagium.diagram3.Seq3Document
import com.indagium.diagram3.Seq3GenerateOptions
import com.indagium.diagram3.Seq3Lifeline
import com.indagium.diagram3.Seq3Match
import com.indagium.diagram3.Seq3Message
import com.indagium.diagram3.Seq3Occurrence
import com.indagium.diagram3.Seq3Range
import com.indagium.diagram3.Seq3RegenChangeKind
import com.indagium.diagram3.Seq3RegenDecision
import com.indagium.diagram3.Seq3RegenReview
import com.indagium.diagram3.Seq3RegenRow
import com.indagium.diagram3.acceptAllSeq3Regen
import com.indagium.diagram3.applySeq3Regeneration
import com.indagium.diagram3.rejectAllSeq3Regen
import com.indagium.diagram3.unlockSeq3RegenRow
import com.indagium.diagram3.withSeq3RegenDecision
import com.indagium.model.LogEntry
import com.indagium.model.LogLevel
import com.indagium.model.LogTab
import com.indagium.ui.AppState
import com.indagium.ui.DiagramLibraryStore
import com.indagium.ui.mkTab
import com.indagium.ui.seq3RegenRowDetail
import java.io.File
import kotlin.io.path.createTempDirectory
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Regenerate is a reviewed proposal (design spec §08) — the pure diff/decision plumbing
 *  (`diagram3.Seq3Regeneration`) plus the sheet's own wording helper (`ui.seq3RegenRowDetail`) and
 *  the "apply is one undo step" contract through `Seq3Session`. Composition itself is untested here,
 *  matching [Seq3GuidedPassTest]'s own posture. */
class Seq3RegenerateSheetTest {
    private companion object {
        const val SHARED_PID = 7
        const val SHARED_TID = 11
        const val NANOS_PER_MILLI = 1_000_000L
    }

    // ── Fixtures (pure) ──────────────────────────────────────────────────────────────────────

    private fun lifeline(id: String, ordinal: Int) = Seq3Lifeline(id, id, setOf(id), ordinal)

    private fun occurrence(entryId: Int) =
        Seq3Occurrence(entryId, entryId * 10L, "14:22:0$entryId.000", 100, 7, 'I', "msg $entryId")

    private fun message(
        id: String,
        from: String,
        to: String?,
        occurrences: List<Seq3Occurrence> = listOf(occurrence(1)),
        authoring: Seq3Authoring = Seq3Authoring.AUTO,
        labelTemplate: String = "template $id",
    ) = Seq3Message(
        id = id,
        match = Seq3Match(from, labelTemplate),
        fromLifelineId = from,
        toLifelineId = to,
        labelTemplate = labelTemplate,
        occurrences = occurrences,
        authoring = authoring,
    )

    private fun document(vararg messages: Seq3Message) = Seq3Document(
        lifelines = listOf(lifeline("A", 0), lifeline("B", 1), lifeline("C", 2)),
        messages = messages.toList(),
    )

    // ── seq3RegenRowDetail wording (spec §08) ────────────────────────────────────────────────

    @Test
    fun detailShowsTargetUnsetToNewTarget() {
        val row = Seq3RegenRow(
            id = "m1", kind = Seq3RegenChangeKind.CHANGED,
            current = message("m1", "A", null),
            fresh = message("m1", "A", "B"),
        )
        assertEquals("target unset → B", seq3RegenRowDetail(row))
    }

    @Test
    fun detailShowsOccurrenceCountChange() {
        val row = Seq3RegenRow(
            id = "m1", kind = Seq3RegenChangeKind.CHANGED,
            current = message("m1", "A", "B", occurrences = (1..6).map(::occurrence)),
            fresh = message("m1", "A", "B", occurrences = (1..9).map(::occurrence)),
        )
        assertEquals("×6 → ×9", seq3RegenRowDetail(row))
    }

    @Test
    fun detailJoinsMultipleDifferencesWithADot() {
        val row = Seq3RegenRow(
            id = "m1", kind = Seq3RegenChangeKind.CHANGED,
            current = message("m1", "A", null, occurrences = listOf(occurrence(1))),
            fresh = message("m1", "A", "B", occurrences = (1..3).map(::occurrence)),
        )
        assertEquals("target unset → B · ×1 → ×3", seq3RegenRowDetail(row))
    }

    @Test
    fun detailExplainsAnUntouchedLockedRow() {
        val row = Seq3RegenRow(
            id = "m1", kind = Seq3RegenChangeKind.EDITED_KEPT,
            current = message("m1", "A", "B", authoring = Seq3Authoring.EDITED),
            fresh = message("m1", "A", "C"),
            unlocked = false,
        )
        assertEquals("your label and target kept · regeneration skipped it", seq3RegenRowDetail(row))
    }

    @Test
    fun detailIsNullOnceUnlockedWhenNothingElseDiffers() {
        // Once unlocked, an EDITED_KEPT row falls through to the ordinary diff wording — here
        // current/fresh agree on every reviewable field, so there is nothing left to show.
        val current = message("m1", "A", "B", occurrences = listOf(occurrence(1)))
        val fresh = message("m1", "A", "B", occurrences = listOf(occurrence(1)))
        val row = Seq3RegenRow(id = "m1", kind = Seq3RegenChangeKind.EDITED_KEPT, current = current, fresh = fresh, unlocked = true)
        assertNull(seq3RegenRowDetail(row))
    }

    @Test
    fun detailIsNullForANewRowWithNoCurrentSide() {
        val row = Seq3RegenRow(id = "m2", kind = Seq3RegenChangeKind.NEW, current = null, fresh = message("m2", "A", "B"))
        assertNull(seq3RegenRowDetail(row))
    }

    @Test
    fun detailIsNullForARemovedRowWithNoFreshSide() {
        val row = Seq3RegenRow(id = "m3", kind = Seq3RegenChangeKind.REMOVED, current = message("m3", "A", "B"), fresh = null)
        assertNull(seq3RegenRowDetail(row))
    }

    // ── Review-row decision transitions ──────────────────────────────────────────────────────

    @Test
    fun withSeq3RegenDecisionSetsOnlyTheNamedRow() {
        val review = Seq3RegenReview(
            rows = listOf(
                Seq3RegenRow("m1", Seq3RegenChangeKind.NEW, null, message("m1", "A", "B")),
                Seq3RegenRow("m2", Seq3RegenChangeKind.NEW, null, message("m2", "A", "B")),
            ),
            freshDocument = document(),
        )

        val after = withSeq3RegenDecision(review, "m1", Seq3RegenDecision.ACCEPT)

        assertEquals(Seq3RegenDecision.ACCEPT, after.rows.first { it.id == "m1" }.decision)
        assertEquals(Seq3RegenDecision.PENDING, after.rows.first { it.id == "m2" }.decision)
    }

    @Test
    fun acceptAllAcceptsEveryUnlockedRowButSkipsALockedEditedRow() {
        val review = Seq3RegenReview(
            rows = listOf(
                Seq3RegenRow("m1", Seq3RegenChangeKind.NEW, null, message("m1", "A", "B")),
                Seq3RegenRow("m2", Seq3RegenChangeKind.REMOVED, message("m2", "A", "B"), null),
                Seq3RegenRow(
                    "m3", Seq3RegenChangeKind.EDITED_KEPT,
                    message("m3", "A", "B", authoring = Seq3Authoring.EDITED), message("m3", "A", "C"),
                ),
            ),
            freshDocument = document(),
        )

        val after = acceptAllSeq3Regen(review)

        assertEquals(Seq3RegenDecision.ACCEPT, after.rows.first { it.id == "m1" }.decision)
        assertEquals(Seq3RegenDecision.ACCEPT, after.rows.first { it.id == "m2" }.decision)
        assertEquals(Seq3RegenDecision.PENDING, after.rows.first { it.id == "m3" }.decision, "a locked row is untouched by accept-all")
    }

    @Test
    fun rejectAllRejectsEveryUnlockedRowButSkipsALockedEditedRow() {
        val review = Seq3RegenReview(
            rows = listOf(
                Seq3RegenRow("m1", Seq3RegenChangeKind.NEW, null, message("m1", "A", "B")),
                Seq3RegenRow(
                    "m3", Seq3RegenChangeKind.EDITED_KEPT,
                    message("m3", "A", "B", authoring = Seq3Authoring.EDITED), message("m3", "A", "C"),
                ),
            ),
            freshDocument = document(),
        )

        val after = rejectAllSeq3Regen(review)

        assertEquals(Seq3RegenDecision.REJECT, after.rows.first { it.id == "m1" }.decision)
        assertEquals(Seq3RegenDecision.PENDING, after.rows.first { it.id == "m3" }.decision, "a locked row is untouched by reject-all")
    }

    @Test
    fun unlockTurnsALockedRowIntoAnOrdinaryDecidableRow() {
        val review = Seq3RegenReview(
            rows = listOf(
                Seq3RegenRow(
                    "m1", Seq3RegenChangeKind.EDITED_KEPT,
                    message("m1", "A", "B", authoring = Seq3Authoring.EDITED), message("m1", "A", "C"),
                ),
            ),
            freshDocument = document(),
        )

        val unlocked = unlockSeq3RegenRow(review, "m1")
        assertTrue(unlocked.rows.single().unlocked)

        val accepted = acceptAllSeq3Regen(unlocked)
        assertEquals(Seq3RegenDecision.ACCEPT, accepted.rows.single().decision, "once unlocked, accept-all reaches it")
    }

    @Test
    fun unlockIsANoOpOnANonEditedKeptRowOrAnUnknownId() {
        val review = Seq3RegenReview(
            rows = listOf(Seq3RegenRow("m1", Seq3RegenChangeKind.NEW, null, message("m1", "A", "B"))),
            freshDocument = document(),
        )

        assertEquals(review, unlockSeq3RegenRow(review, "m1"))
        assertEquals(review, unlockSeq3RegenRow(review, "does-not-exist"))
    }

    // ── Edited rows are locked: reported, not replaced, until unlocked ──────────────────────────

    @Test
    fun anEditedKeptRowIsReportedNotReplacedRegardlessOfDecision() {
        val currentMsg = message("m1", "A", "B", authoring = Seq3Authoring.EDITED)
        val freshMsg = message("m1", "A", "C")
        val row = Seq3RegenRow("m1", Seq3RegenChangeKind.EDITED_KEPT, currentMsg, freshMsg, decision = Seq3RegenDecision.ACCEPT)
        val review = Seq3RegenReview(rows = listOf(row), freshDocument = document(freshMsg))

        val applied = applySeq3Regeneration(document(currentMsg), review)

        assertEquals(currentMsg, applied.messages.single(), "ACCEPT is ignored while the row is still locked")
    }

    @Test
    fun unlockingThenAcceptingAppliesTheFreshProposal() {
        val currentMsg = message("m1", "A", "B", authoring = Seq3Authoring.EDITED)
        val freshMsg = message("m1", "A", "C")
        val row = Seq3RegenRow(
            "m1", Seq3RegenChangeKind.EDITED_KEPT, currentMsg, freshMsg,
            unlocked = true, decision = Seq3RegenDecision.ACCEPT,
        )
        val review = Seq3RegenReview(rows = listOf(row), freshDocument = document(freshMsg))

        val applied = applySeq3Regeneration(document(currentMsg), review)

        assertEquals(freshMsg, applied.messages.single())
    }

    // ── UNCHANGED rows: never a decision, still survive apply ───────────────────────────────────

    @Test
    fun unchangedRowsAreExcludedFromTheSummaryButSurviveApply() {
        val msg = message("m1", "A", "B")
        val row = Seq3RegenRow("m1", Seq3RegenChangeKind.UNCHANGED, msg, msg)
        val review = Seq3RegenReview(rows = listOf(row), freshDocument = document(msg))

        val s = review.summary
        assertEquals(0, s.newCount + s.changedCount + s.removedCount + s.editsKeptCount, "an UNCHANGED row is never a decision")

        val applied = applySeq3Regeneration(document(msg), review)
        assertEquals(listOf(msg), applied.messages, "apply still needs UNCHANGED rows to rebuild the full message list")
    }

    // ── Apply is ONE undo step (spec §08: "not 15") ──────────────────────────────────────────────

    private fun twoTagEntries(): List<LogEntry> = listOf(
        LogEntry(1, "10:00:00.000", LogLevel.I, "Producer", "start op", SHARED_PID, SHARED_TID),
        LogEntry(2, "10:00:00.050", LogLevel.I, "Consumer", "handle op", SHARED_PID, SHARED_TID),
    )

    private fun stateFor(tab: LogTab): AppState {
        val root = createTempDirectory("indagium-seq3-regen-sheet").toFile()
        return AppState(
            File(root, "state.cache"),
            notesDir = File(root, "notes"),
            diagramLibraryStore = DiagramLibraryStore(File(root, "library.cache")),
        ).also { s ->
            s.tabs = listOf(tab)
            s.activateTab(tab.id)
        }
    }

    private fun await(timeoutMs: Long = 4_000, condition: () -> Boolean) {
        val deadline = System.nanoTime() + timeoutMs * NANOS_PER_MILLI
        while (!condition() && System.nanoTime() < deadline) Thread.sleep(10)
        assertTrue(condition(), "condition did not become true within ${timeoutMs}ms")
    }

    // ── Scope controls (item 4) ─────────────────────────────────────────────────────────────────
    //
    // Seq3RegenScopeControls' own composable isn't exercised here (this file follows
    // Seq3GuidedPassTest's posture: no Compose testing harness in this codebase) — these assert the
    // exact `Seq3Session` calls the sheet's new Time-range menu item / source-trace toggle make.

    @Test
    fun theTimeRangeScopeItemCallsUpdateScopeAndNeverTriggersARegenerate() {
        val state = stateFor(mkTab("log", "sample.log", twoTagEntries()))
        val id = state.seq3Sessions.begin("log")!!
        await { state.seq3Sessions.sessions.single().generating == false }
        val runsBefore = state.seq3Sessions.generateRunCount.get()

        // What Seq3RegenScopeControls' "Time range" dropdown item does on click.
        state.seq3Sessions.updateScope(id, Seq3Range.Time("10:00:00.000", "10:00:05.000"))

        assertEquals(Seq3Range.Time("10:00:00.000", "10:00:05.000"), state.seq3Sessions.sessions.single().range)
        assertEquals(runsBefore, state.seq3Sessions.generateRunCount.get(), "the scope picker only seeds the next Build review, never regenerates on its own")
    }

    @Test
    fun theSourceTraceToggleFlipsGenerateOptionsWithoutRegenerating() {
        val state = stateFor(mkTab("log", "sample.log", twoTagEntries()))
        val id = state.seq3Sessions.begin("log")!!
        await { state.seq3Sessions.sessions.single().generating == false }
        assertTrue(state.seq3Sessions.sessions.single().generateOptions.sourceTraceEnabled, "default is on")
        val runsBefore = state.seq3Sessions.generateRunCount.get()

        // What Seq3RegenToggle's onToggle for the "Source trace" row does on click.
        state.seq3Sessions.updateGenerateOptions(id) { it.copy(sourceTraceEnabled = !it.sourceTraceEnabled) }

        assertFalse(state.seq3Sessions.sessions.single().generateOptions.sourceTraceEnabled)
        assertEquals(runsBefore, state.seq3Sessions.generateRunCount.get(), "a toggle is an input to the next Build review, not a rebuild itself")
    }

    @Test
    fun applyRegenReviewIsOneUndoStepThatFullyRestoresThePriorDocument() {
        val state = stateFor(mkTab("log", "sample.log", twoTagEntries()))
        val id = state.seq3Sessions.begin("log")!!
        await { state.seq3Sessions.sessions.single().generating == false }
        val before = state.seq3Sessions.sessions.single().document
        val producerBefore = before.messages.first { it.fromLifelineId == "Producer" }
        assertEquals("Consumer", producerBefore.toLifelineId, "thread-handoff inference should have resolved a target")

        // Build a review with handoff/correlation inference turned off — the fresh scan then can't
        // resolve the same target, giving a genuine CHANGED row for the accept-all below to apply.
        val offOptions = Seq3GenerateOptions(threadHandoffEnabled = false, correlationTokenEnabled = false)
        state.seq3Sessions.requestRegenReview(id, Seq3Range.VisibleView, offOptions)
        await { state.seq3Sessions.sessions.single().regenBuilding == false }
        val review = assertNotNull(state.seq3Sessions.sessions.single().pendingRegenReview)
        assertTrue(review.rows.any { it.kind == Seq3RegenChangeKind.CHANGED }, "turning off inference should change the producer's target")
        state.seq3Sessions.updateRegenReview(id, ::acceptAllSeq3Regen)

        val applied = state.seq3Sessions.applyRegenReview(id)

        assertTrue(applied)
        assertNull(state.seq3Sessions.sessions.single().pendingRegenReview, "a consumed review clears from the session")
        val producerAfter = state.seq3Sessions.sessions.single().document.messages.first { it.fromLifelineId == "Producer" }
        assertNull(producerAfter.toLifelineId, "the accepted change should have applied")
        assertTrue(state.seq3Sessions.canUndo(id))

        assertTrue(state.seq3Sessions.undo(id))

        assertEquals(before, state.seq3Sessions.sessions.single().document, "one undo restores the whole pre-regeneration document")
        assertFalse(state.seq3Sessions.canUndo(id))
    }
}
