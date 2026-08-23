package com.indagium

import com.indagium.diagram3.Seq3Authoring
import com.indagium.diagram3.Seq3BulkAction
import com.indagium.diagram3.Seq3Capture
import com.indagium.diagram3.Seq3CaptureSource
import com.indagium.diagram3.Seq3Command
import com.indagium.diagram3.Seq3Delay
import com.indagium.diagram3.Seq3Document
import com.indagium.diagram3.Seq3Filter
import com.indagium.diagram3.Seq3Fragment
import com.indagium.diagram3.Seq3FragmentKind
import com.indagium.diagram3.Seq3Kind
import com.indagium.diagram3.Seq3Lifeline
import com.indagium.diagram3.Seq3Match
import com.indagium.diagram3.Seq3Message
import com.indagium.diagram3.Seq3Note
import com.indagium.diagram3.Seq3Occurrence
import com.indagium.diagram3.Seq3OccurrenceRef
import com.indagium.diagram3.Seq3PinDirection
import com.indagium.diagram3.Seq3Repeat
import com.indagium.diagram3.Seq3Selection
import com.indagium.diagram3.Seq3Sort
import com.indagium.diagram3.Seq3Visibility
import com.indagium.diagram3.applySeq3BulkAction
import com.indagium.diagram3.applySeq3Command
import com.indagium.diagram3.nudgeSeq3OrderPin
import com.indagium.diagram3.seq3FilterCounts
import com.indagium.diagram3.seq3MessageIdsAreContiguous
import com.indagium.diagram3.seq3QueueRows
import com.indagium.diagram3.seq3Select
import com.indagium.diagram3.undoSeq3Command
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class Seq3QueueTest {
    private fun occ(id: Int, ts: Long, text: String = "line $id") = Seq3Occurrence(id, ts, "10:00:00.000", 1, 1, 'I', text)

    private fun msg(
        id: String,
        from: String,
        to: String?,
        occurrences: List<Seq3Occurrence>,
        authoring: Seq3Authoring = Seq3Authoring.AUTO,
        visibility: Seq3Visibility = Seq3Visibility.VISIBLE,
        template: String = "$id-label",
        kind: Seq3Kind = Seq3Kind.CALL,
    ) = Seq3Message(id, Seq3Match(from, template), from, to, template, kind = kind, authoring = authoring, visibility = visibility, occurrences = occurrences)

    // m1(A->B, ts100, AUTO, 3 occurrences) · m3(A->B, ts100, EDITED) · m2(A->needs-target, ts200) ·
    // m4(B->C, ts300, HIDDEN) — in this exact log order, so m1/m3 genuinely tie and m3/m2 do not.
    @Suppress("MagicNumber") // entry ids / timestamps below are fixture data, not tunable constants
    private fun baseDocument(): Seq3Document {
        val m1 = msg("m1", "A", "B", listOf(occ(1, 100), occ(11, 100), occ(12, 100)))
        val m3 = msg("m3", "A", "B", listOf(occ(3, 100)), authoring = Seq3Authoring.EDITED)
        val m2 = msg("m2", "A", null, listOf(occ(2, 200)), template = "special-target-needed")
        val m4 = msg("m4", "B", "C", listOf(occ(4, 300)), visibility = Seq3Visibility.HIDDEN)
        return Seq3Document(
            lifelines = listOf(Seq3Lifeline("A", "A", setOf("A"), 0), Seq3Lifeline("B", "B", setOf("B"), 1), Seq3Lifeline("C", "C", setOf("C"), 2)),
            messages = listOf(m1, m3, m2, m4),
        )
    }

    // ── Filter chips ─────────────────────────────────────────────────────────────────────────

    @Test
    fun filterCountsMatchEachChip() {
        val counts = seq3FilterCounts(baseDocument())
        assertEquals(4, counts.all)
        assertEquals(1, counts.needsTarget)
        assertEquals(1, counts.edited)
        assertEquals(1, counts.hidden)
    }

    @Test
    fun delaysNeverAffectFilterCountsIncludingNeedsTarget() {
        // WP11: a delay has no endpoints, no evidence, and must never enter the needs-target count
        // or the message queue — see Seq3Delay's own header. Adding one changes nothing here.
        val withDelays = baseDocument().copy(
            delays = listOf(
                Seq3Delay("d1", afterMessageId = "m1", label = "later"),
                Seq3Delay("d2", afterMessageId = "m2", label = "even later"),
            ),
        )
        val counts = seq3FilterCounts(withDelays)
        assertEquals(4, counts.all)
        assertEquals(1, counts.needsTarget, "m2's own missing target is unaffected by an unrelated delay")
        assertEquals(1, counts.edited)
        assertEquals(1, counts.hidden)
        assertEquals(
            listOf("m1", "m3", "m2", "m4"),
            seq3QueueRows(withDelays, Seq3Filter.ALL).map { it.id },
            "the message queue itself must not gain or lose a row",
        )
    }

    @Test
    fun everyFilterChipSelectsTheRightRows() {
        val doc = baseDocument()
        assertEquals(listOf("m1", "m3", "m2", "m4"), seq3QueueRows(doc, Seq3Filter.ALL).map { it.id })
        assertEquals(listOf("m2"), seq3QueueRows(doc, Seq3Filter.NEEDS_TARGET).map { it.id })
        assertEquals(listOf("m3"), seq3QueueRows(doc, Seq3Filter.EDITED).map { it.id })
        assertEquals(listOf("m4"), seq3QueueRows(doc, Seq3Filter.HIDDEN).map { it.id })
    }

    @Test
    fun hiddenFilterIncludesAGroupWhenOnlyOneOccurrenceIsHidden() {
        val doc = baseDocument().copy(
            messages = listOf(
                baseDocument().messages.first().copy(
                    occurrences = listOf(occ(1, 100), occ(11, 100).copy(visibility = Seq3Visibility.HIDDEN)),
                ),
            ),
        )

        assertEquals(1, seq3FilterCounts(doc).hidden)
        assertEquals(listOf("m1"), seq3QueueRows(doc, Seq3Filter.HIDDEN).map { it.id })
    }

    @Test
    fun textFilterMatchesTheRenderedTemplate() {
        val doc = baseDocument()
        assertEquals(listOf("m2"), seq3QueueRows(doc, Seq3Filter.ALL, textFilter = "special-target").map { it.id })
        assertTrue(seq3QueueRows(doc, Seq3Filter.ALL, textFilter = "no-such-text").isEmpty())
    }

    // ── Sort (a view, never an edit) ─────────────────────────────────────────────────────────

    @Test
    fun everySortProducesTheExpectedOrder() {
        val doc = baseDocument()
        assertEquals(listOf("m1", "m3", "m2", "m4"), seq3QueueRows(doc, Seq3Filter.ALL, sort = Seq3Sort.LOG_ORDER).map { it.id })
        assertEquals(listOf("m1", "m3", "m2", "m4"), seq3QueueRows(doc, Seq3Filter.ALL, sort = Seq3Sort.LIFELINE).map { it.id }) // A,A,A then B
        assertEquals("m1", seq3QueueRows(doc, Seq3Filter.ALL, sort = Seq3Sort.OCCURRENCES).first().id) // 3 occurrences, most of any row
        assertEquals(listOf("m2", "m3", "m1", "m4"), seq3QueueRows(doc, Seq3Filter.ALL, sort = Seq3Sort.STATE).map { it.id })
    }

    @Test
    fun sortingNeverMutatesTheDocument() {
        val doc = baseDocument()
        val before = doc.messages
        seq3QueueRows(doc, Seq3Filter.ALL, sort = Seq3Sort.OCCURRENCES)
        seq3QueueRows(doc, Seq3Filter.ALL, sort = Seq3Sort.STATE)
        seq3QueueRows(doc, Seq3Filter.ALL, sort = Seq3Sort.LIFELINE)

        assertEquals(before, doc.messages, "the canvas must never change when the sort changes (spec §07)")
    }

    // ── Selection ────────────────────────────────────────────────────────────────────────────

    @Test
    fun plainClickSelectsExactlyOneAndSetsTheAnchor() {
        val visible = listOf("m1", "m3", "m2", "m4")
        val selection = seq3Select(visible, Seq3Selection(), "m3")
        assertEquals(setOf("m3"), selection.selectedIds)
        assertEquals("m3", selection.anchorId)
    }

    @Test
    fun shiftClickExtendsARangeFromTheAnchor() {
        val visible = listOf("m1", "m3", "m2", "m4")
        val afterClick = seq3Select(visible, Seq3Selection(), "m3")
        val afterShift = seq3Select(visible, afterClick, "m4", range = true)
        assertEquals(setOf("m3", "m2", "m4"), afterShift.selectedIds)
        assertEquals("m3", afterShift.anchorId, "shift-click must keep the ORIGINAL anchor, not move it")
    }

    @Test
    fun cmdClickTogglesOneIdAdditively() {
        val visible = listOf("m1", "m3", "m2", "m4")
        val afterClick = seq3Select(visible, Seq3Selection(), "m1")
        val afterAdd = seq3Select(visible, afterClick, "m4", additive = true)
        assertEquals(setOf("m1", "m4"), afterAdd.selectedIds)
        val afterRemove = seq3Select(visible, afterAdd, "m1", additive = true)
        assertEquals(setOf("m4"), afterRemove.selectedIds)
    }

    @Test
    fun clickingAnIdOutsideTheVisibleListIsANoOp() {
        val visible = listOf("m1", "m3")
        val current = Seq3Selection(setOf("m1"), "m1")
        assertEquals(current, seq3Select(visible, current, "m4"))
    }

    // ── Bulk verbs ───────────────────────────────────────────────────────────────────────────

    @Test
    fun setFromRetargetsTheSourceLifelineAndMarksEdited() {
        val doc = baseDocument()
        val result = applySeq3BulkAction(doc, setOf("m2"), Seq3BulkAction.SetFrom("B"))
        assertTrue(result.applied)
        val m2 = result.document.messages.single { it.id == "m2" }
        assertEquals("B", m2.fromLifelineId)
        assertEquals(Seq3Authoring.EDITED, m2.authoring)
    }

    @Test
    fun setFromRejectsAnUnknownLifeline() {
        val doc = baseDocument()
        val result = applySeq3BulkAction(doc, setOf("m2"), Seq3BulkAction.SetFrom("Nope"))
        assertFalse(result.applied)
        assertEquals(doc, result.document)
    }

    @Test
    fun setToResolvesANeedsTargetMessage() {
        val doc = baseDocument()
        val result = applySeq3BulkAction(doc, setOf("m2"), Seq3BulkAction.SetTo("C"))
        assertTrue(result.applied)
        val m2 = result.document.messages.single { it.id == "m2" }
        assertEquals("C", m2.toLifelineId)
    }

    @Test
    fun setToTheMessagesOwnFromLifelineAutoFlipsKindToSelf() {
        // Bug fix: the Inspector's bulk SetTo let a user pick `to == from` without becoming a
        // self-call, same class of bug as the guided pass's applySeq3GuidedTarget.
        val doc = baseDocument()
        val result = applySeq3BulkAction(doc, setOf("m2"), Seq3BulkAction.SetTo("A"))
        assertTrue(result.applied)
        val m2 = result.document.messages.single { it.id == "m2" }
        assertEquals("A", m2.toLifelineId)
        assertEquals(Seq3Kind.SELF, m2.kind)
    }

    @Test
    fun setToADifferentLifelineLeavesKindAlone() {
        val doc = baseDocument()
        val result = applySeq3BulkAction(doc, setOf("m2"), Seq3BulkAction.SetTo("C"))
        assertTrue(result.applied)
        assertEquals(Seq3Kind.CALL, result.document.messages.single { it.id == "m2" }.kind)
    }

    @Test
    fun movingTheTargetAwayFromASelfMessageTurnsItBackIntoACall() {
        val doc = baseDocument().copy(
            messages = baseDocument().messages.map { message ->
                if (message.id == "m2") message.copy(toLifelineId = "A", kind = Seq3Kind.SELF) else message
            },
        )
        val result = applySeq3BulkAction(doc, setOf("m2"), Seq3BulkAction.SetTo("C"))

        assertTrue(result.applied)
        val message = result.document.messages.single { it.id == "m2" }
        assertEquals("C", message.toLifelineId)
        assertEquals(Seq3Kind.CALL, message.kind)
    }

    @Test
    fun hideKeepsEvidenceAndDoesNotFlipAuthoring() {
        val doc = baseDocument()
        val result = applySeq3BulkAction(doc, setOf("m1"), Seq3BulkAction.Hide)
        assertTrue(result.applied)
        val m1 = result.document.messages.single { it.id == "m1" }
        assertEquals(Seq3Visibility.HIDDEN, m1.visibility)
        assertEquals(3, m1.occurrences.size, "hiding must never drop evidence")
        assertEquals(Seq3Authoring.AUTO, m1.authoring, "hide is a visibility flag, not an edit (spec §03)")
    }

    @Test
    fun showRestoresVisibility() {
        val doc = baseDocument()
        val result = applySeq3BulkAction(doc, setOf("m4"), Seq3BulkAction.Show)
        assertTrue(result.applied)
        assertEquals(Seq3Visibility.VISIBLE, result.document.messages.single { it.id == "m4" }.visibility)
    }

    @Test
    fun groupWrapsExactlyTheSelectionInAFragment() {
        val doc = baseDocument()
        val fragment = Seq3Fragment("frag1", Seq3FragmentKind.ALT, "maybe", listOf("m1", "m3"))
        val result = applySeq3BulkAction(doc, setOf("m1", "m3"), Seq3BulkAction.Group(fragment))
        assertTrue(result.applied)
        assertEquals(listOf(fragment), result.document.fragments)
    }

    @Test
    fun groupRejectsAFragmentThatDoesNotMatchTheSelectionExactly() {
        val doc = baseDocument()
        val fragment = Seq3Fragment("frag1", Seq3FragmentKind.ALT, "maybe", listOf("m1"))
        val result = applySeq3BulkAction(doc, setOf("m1", "m3"), Seq3BulkAction.Group(fragment))
        assertFalse(result.applied)
    }

    @Test
    fun groupAllowsExplicitMarqueeSelectionAcrossNonSelectedRows() {
        val doc = baseDocument()
        val fragment = Seq3Fragment("frag1", Seq3FragmentKind.ALT, "maybe", listOf("m1", "m2"))
        assertFalse(seq3MessageIdsAreContiguous(doc, setOf("m1", "m2")))
        val result = applySeq3BulkAction(doc, setOf("m1", "m2"), Seq3BulkAction.Group(fragment))
        assertTrue(result.applied)
        assertEquals(listOf(fragment), result.document.fragments)
    }

    @Test
    fun groupAcceptsExactOccurrenceReferencesForARepeatedMessage() {
        val doc = baseDocument()
        val fragment = Seq3Fragment(
            id = "frag1",
            kind = Seq3FragmentKind.ALT,
            label = "only one occurrence",
            messageIds = emptyList(),
            occurrenceRefs = listOf(Seq3OccurrenceRef("m1", 11)),
        )
        val result = applySeq3BulkAction(doc, setOf("m1"), Seq3BulkAction.Group(fragment))

        assertTrue(result.applied, result.reason)
        assertEquals(listOf(Seq3OccurrenceRef("m1", 11)), result.document.fragments.single().occurrenceRefs)
    }

    @Test
    fun noteSpansTheSelection() {
        val doc = baseDocument()
        val note = Seq3Note("n1", "watch this", listOf("m1", "m3"))
        val result = applySeq3BulkAction(doc, setOf("m1", "m3"), Seq3BulkAction.Note(note))
        assertTrue(result.applied)
        assertEquals(listOf(note), result.document.notes)
    }

    @Test
    fun noteGeometryMovesAndResizesAsOneUndoableCommand() {
        val note = Seq3Note("n1", "watch this", listOf("m1"))
        val doc = baseDocument().copy(notes = listOf(note))
        val result = applySeq3Command(doc, Seq3Command.SetNoteGeometry("n1", 42.0, 84.0, 220.0, 64.0))

        assertTrue(result.applied)
        assertEquals(42.0, result.document.notes.single().x)
        assertEquals(84.0, result.document.notes.single().y)
        assertEquals(220.0, result.document.notes.single().width)
        assertEquals(64.0, result.document.notes.single().height)
        assertEquals(doc, undoSeq3Command(result.undo!!))
    }

    // ── Fragment/note rename (edit-in-place counterpart to Group/Note's add-only behaviour) ────

    @Test
    fun setFragmentLabelRenamesAnExistingFragmentByIdIndependentOfSelection() {
        val fragment = Seq3Fragment("frag1", Seq3FragmentKind.ALT, "original", listOf("m1", "m3"))
        val doc = baseDocument().copy(fragments = listOf(fragment))
        // The selection below is unrelated to the fragment's own messageIds — the rename must
        // still find "frag1" purely by id, per the action's own contract.
        val result = applySeq3BulkAction(doc, setOf("m4"), Seq3BulkAction.SetFragmentLabel("frag1", "renamed"))
        assertTrue(result.applied)
        val renamed = result.document.fragments.single { it.id == "frag1" }
        assertEquals("renamed", renamed.label)
        assertEquals(listOf("m1", "m3"), renamed.messageIds, "rename must not touch the fragment's own span")
    }

    @Test
    fun setFragmentLabelIsASafeNoOpForAnUnknownId() {
        val doc = baseDocument()
        val result = applySeq3BulkAction(doc, setOf("m1"), Seq3BulkAction.SetFragmentLabel("no-such-fragment", "renamed"))
        assertFalse(result.applied)
        assertEquals(doc, result.document)
    }

    // ── SetFragmentKind (WP12) ──────────────────────────────────────────────────────────────

    @Test
    fun setFragmentKindChangesAnExistingFragmentsKindByIdIndependentOfSelection() {
        val fragment = Seq3Fragment("frag1", Seq3FragmentKind.LOOP, "original", listOf("m1", "m3"))
        val doc = baseDocument().copy(fragments = listOf(fragment))
        // Same "target by id, ignore selectedIds" contract as SetFragmentLabel above.
        val result = applySeq3BulkAction(doc, setOf("m4"), Seq3BulkAction.SetFragmentKind("frag1", Seq3FragmentKind.GROUP))
        assertTrue(result.applied)
        val changed = result.document.fragments.single { it.id == "frag1" }
        assertEquals(Seq3FragmentKind.GROUP, changed.kind)
        assertEquals("original", changed.label, "changing kind must not touch the label")
        assertEquals(listOf("m1", "m3"), changed.messageIds, "changing kind must not touch the fragment's own span")
    }

    @Test
    fun setFragmentKindIsASafeNoOpForAnUnknownId() {
        val doc = baseDocument()
        val result = applySeq3BulkAction(doc, setOf("m1"), Seq3BulkAction.SetFragmentKind("no-such-fragment", Seq3FragmentKind.CRITICAL))
        assertFalse(result.applied)
        assertEquals(doc, result.document)
    }

    @Test
    fun setFragmentKindWorksWithAnEmptySelectionLikeSetFragmentLabel() {
        val fragment = Seq3Fragment("frag1", Seq3FragmentKind.OPT, "original", listOf("m1"))
        val doc = baseDocument().copy(fragments = listOf(fragment))
        val result = applySeq3BulkAction(doc, emptySet(), Seq3BulkAction.SetFragmentKind("frag1", Seq3FragmentKind.BREAK))
        assertTrue(result.applied, result.reason)
        assertEquals(Seq3FragmentKind.BREAK, result.document.fragments.single().kind)
    }

    // ── SetFragmentHideKindLabel (WP12) ─────────────────────────────────────────────────────

    @Test
    fun setFragmentHideKindLabelTogglesTheCanvasOnlyFlagByIdIndependentOfSelection() {
        val fragment = Seq3Fragment("frag1", Seq3FragmentKind.GROUP, "billing flow", listOf("m1"))
        val doc = baseDocument().copy(fragments = listOf(fragment))
        val result = applySeq3BulkAction(doc, emptySet(), Seq3BulkAction.SetFragmentHideKindLabel("frag1", true))
        assertTrue(result.applied)
        assertTrue(result.document.fragments.single().hideKindLabel)
    }

    @Test
    fun setFragmentHideKindLabelIsASafeNoOpForAnUnknownId() {
        val doc = baseDocument()
        val result = applySeq3BulkAction(doc, setOf("m1"), Seq3BulkAction.SetFragmentHideKindLabel("no-such-fragment", true))
        assertFalse(result.applied)
        assertEquals(doc, result.document)
    }

    @Test
    fun setNoteTextRenamesAnExistingNoteByIdIndependentOfSelection() {
        val note = Seq3Note("n1", "original text", listOf("m1"))
        val doc = baseDocument().copy(notes = listOf(note))
        val result = applySeq3BulkAction(doc, emptySet(), Seq3BulkAction.SetNoteText("n1", "updated text"))
        assertTrue(result.applied)
        assertEquals("updated text", result.document.notes.single { it.id == "n1" }.text)
    }

    @Test
    fun setNoteTextIsASafeNoOpForAnUnknownId() {
        val doc = baseDocument()
        val result = applySeq3BulkAction(doc, emptySet(), Seq3BulkAction.SetNoteText("no-such-note", "updated"))
        assertFalse(result.applied)
        assertEquals(doc, result.document)
    }

    // ── Fragment/note visibility (WP1, the eye button in the new "Fragments & notes" section) ──

    @Test
    fun setFragmentVisibilityHidesAnExistingFragmentByIdIndependentOfSelectionAndTouchesNoMessages() {
        val fragment = Seq3Fragment("frag1", Seq3FragmentKind.ALT, "maybe", listOf("m1", "m3"))
        val doc = baseDocument().copy(fragments = listOf(fragment))
        val result = applySeq3BulkAction(doc, emptySet(), Seq3BulkAction.SetFragmentVisibility("frag1", Seq3Visibility.HIDDEN))
        assertTrue(result.applied)
        assertEquals(Seq3Visibility.HIDDEN, result.document.fragments.single().visibility)
        assertEquals(doc.messages, result.document.messages, "hiding a fragment must not touch any of its messages")
    }

    @Test
    fun setFragmentVisibilityIsASafeNoOpForAnUnknownId() {
        val doc = baseDocument()
        val result = applySeq3BulkAction(doc, emptySet(), Seq3BulkAction.SetFragmentVisibility("no-such-fragment", Seq3Visibility.HIDDEN))
        assertFalse(result.applied)
        assertEquals(doc, result.document)
    }

    @Test
    fun setNoteVisibilityHidesAnExistingNoteByIdIndependentOfSelection() {
        val note = Seq3Note("n1", "watch this", listOf("m1"))
        val doc = baseDocument().copy(notes = listOf(note))
        val result = applySeq3BulkAction(doc, emptySet(), Seq3BulkAction.SetNoteVisibility("n1", Seq3Visibility.HIDDEN))
        assertTrue(result.applied)
        assertEquals(Seq3Visibility.HIDDEN, result.document.notes.single().visibility)
    }

    @Test
    fun setNoteVisibilityIsASafeNoOpForAnUnknownId() {
        val doc = baseDocument()
        val result = applySeq3BulkAction(doc, emptySet(), Seq3BulkAction.SetNoteVisibility("no-such-note", Seq3Visibility.HIDDEN))
        assertFalse(result.applied)
        assertEquals(doc, result.document)
    }

    // ── Delay (WP11) — add/remove/relabel, the same id-keyed/selection-independent shape as
    //    Fragment/Note rename above ─────────────────────────────────────────────────────────────

    @Test
    fun addDelayAppendsANewDelayIndependentOfSelection() {
        val doc = baseDocument()
        val delay = Seq3Delay("d1", afterMessageId = "m1", label = "5 minutes later")
        val result = applySeq3BulkAction(doc, emptySet(), Seq3BulkAction.AddDelay(delay))
        assertTrue(result.applied)
        assertEquals(listOf(delay), result.document.delays)
    }

    @Test
    fun addDelayIsASafeNoOpForABlankLabelACollidingIdOrAnUnknownAnchor() {
        val doc = baseDocument().copy(delays = listOf(Seq3Delay("d1", "m1", "existing")))

        val blankLabel = applySeq3BulkAction(doc, emptySet(), Seq3BulkAction.AddDelay(Seq3Delay("d2", "m1", "")))
        assertFalse(blankLabel.applied)
        assertEquals(doc, blankLabel.document)

        val collidingId = applySeq3BulkAction(doc, emptySet(), Seq3BulkAction.AddDelay(Seq3Delay("d1", "m3", "other")))
        assertFalse(collidingId.applied)
        assertEquals(doc, collidingId.document)

        val unknownAnchor = applySeq3BulkAction(doc, emptySet(), Seq3BulkAction.AddDelay(Seq3Delay("d2", "no-such-message", "later")))
        assertFalse(unknownAnchor.applied)
        assertEquals(doc, unknownAnchor.document)
    }

    @Test
    fun setDelayLabelRenamesAnExistingDelayByIdIndependentOfSelection() {
        val delay = Seq3Delay("d1", "m1", "original")
        val doc = baseDocument().copy(delays = listOf(delay))
        val result = applySeq3BulkAction(doc, setOf("m4"), Seq3BulkAction.SetDelayLabel("d1", "renamed"))
        assertTrue(result.applied)
        val renamed = result.document.delays.single { it.id == "d1" }
        assertEquals("renamed", renamed.label)
        assertEquals("m1", renamed.afterMessageId, "rename must not touch the delay's own anchor")
    }

    @Test
    fun setDelayLabelIsASafeNoOpForAnUnknownIdOrABlankLabel() {
        val doc = baseDocument().copy(delays = listOf(Seq3Delay("d1", "m1", "original")))
        val unknownId = applySeq3BulkAction(doc, emptySet(), Seq3BulkAction.SetDelayLabel("no-such-delay", "renamed"))
        assertFalse(unknownId.applied)
        assertEquals(doc, unknownId.document)

        val blankLabel = applySeq3BulkAction(doc, emptySet(), Seq3BulkAction.SetDelayLabel("d1", ""))
        assertFalse(blankLabel.applied)
        assertEquals(doc, blankLabel.document)
    }

    @Test
    fun deleteDelayRemovesOnlyTheRequestedDelayAndTouchesNoMessage() {
        val d1 = Seq3Delay("d1", "m1", "first")
        val d2 = Seq3Delay("d2", "m3", "second")
        val doc = baseDocument().copy(delays = listOf(d1, d2))
        val result = applySeq3BulkAction(doc, emptySet(), Seq3BulkAction.DeleteDelay("d1"))
        assertTrue(result.applied)
        assertEquals(listOf(d2), result.document.delays)
        assertEquals(doc.messages, result.document.messages)
    }

    @Test
    fun deleteDelayIsASafeNoOpForAnUnknownId() {
        val doc = baseDocument()
        val result = applySeq3BulkAction(doc, emptySet(), Seq3BulkAction.DeleteDelay("no-such-delay"))
        assertFalse(result.applied)
        assertEquals(doc, result.document)
    }

    @Test
    fun setDelayVisibilityHidesAnExistingDelayByIdIndependentOfSelectionAndTouchesNoMessages() {
        val delay = Seq3Delay("d1", "m1", "later")
        val doc = baseDocument().copy(delays = listOf(delay))
        val result = applySeq3BulkAction(doc, emptySet(), Seq3BulkAction.SetDelayVisibility("d1", Seq3Visibility.HIDDEN))
        assertTrue(result.applied)
        assertEquals(Seq3Visibility.HIDDEN, result.document.delays.single().visibility)
        assertEquals(doc.messages, result.document.messages, "hiding a delay must not touch any of its messages")
    }

    @Test
    fun setDelayVisibilityIsASafeNoOpForAnUnknownId() {
        val doc = baseDocument()
        val result = applySeq3BulkAction(doc, emptySet(), Seq3BulkAction.SetDelayVisibility("no-such-delay", Seq3Visibility.HIDDEN))
        assertFalse(result.applied)
        assertEquals(doc, result.document)
    }

    @Test
    fun deleteFragmentAndNoteRemoveOnlyTheRequestedArtifacts() {
        val fragment = Seq3Fragment("frag1", Seq3FragmentKind.ALT, "maybe", listOf("m1", "m3"))
        val note = Seq3Note("n1", "watch this", listOf("m2"))
        val doc = baseDocument().copy(fragments = listOf(fragment), notes = listOf(note))

        val withoutFragment = applySeq3BulkAction(doc, emptySet(), Seq3BulkAction.DeleteFragment("frag1"))
        assertTrue(withoutFragment.applied)
        assertTrue(withoutFragment.document.fragments.isEmpty())
        assertEquals(listOf(note), withoutFragment.document.notes)

        val withoutNote = applySeq3BulkAction(withoutFragment.document, emptySet(), Seq3BulkAction.DeleteNote("n1"))
        assertTrue(withoutNote.applied)
        assertTrue(withoutNote.document.notes.isEmpty())
        assertEquals(doc.messages, withoutNote.document.messages)
    }

    @Test
    fun mergeCollapsesCompatibleMessagesIntoOneTokenizedPatternAndKeepsEveryOccurrence() {
        val a = msg("a", "A", "B", listOf(occ(101, 100, "push id=1")))
        val b = msg("b", "A", "B", listOf(occ(102, 150, "push id=2")))
        val doc = Seq3Document(
            lifelines = listOf(Seq3Lifeline("A", "A", setOf("A"), 0), Seq3Lifeline("B", "B", setOf("B"), 1)),
            messages = listOf(a, b),
        )
        val result = applySeq3BulkAction(doc, setOf("a", "b"), Seq3BulkAction.Merge("merged"))
        assertTrue(result.applied, result.reason)
        val merged = result.document.messages.single()
        assertEquals("merged", merged.id)
        assertEquals("push id={id}", merged.match.template) // named-value pattern: "id=" stays literal, only its value tokenizes
        assertEquals(setOf(101, 102), merged.occurrences.map { it.entryId }.toSet(), "merge must be REVERSIBLE: no evidence may be lost")
    }

    @Test
    fun mergeRejectsMessagesWithDifferentEndpoints() {
        val doc = baseDocument()
        val result = applySeq3BulkAction(doc, setOf("m1", "m2"), Seq3BulkAction.Merge("merged")) // m1 targets B, m2 has no target
        assertFalse(result.applied)
        assertEquals(doc, result.document)
    }

    // ── Order pin (spec §07: only a genuine tie may be pinned) ──────────────────────────────────

    @Test
    fun nudgingATiedPairSwapsThemAndStampsBothWithAPin() {
        val doc = baseDocument() // m1 and m3 both first-occur at ts=100 and are adjacent
        val result = nudgeSeq3OrderPin(doc, "m3", Seq3PinDirection.UP)
        assertTrue(result.applied, result.reason)
        assertEquals(listOf("m3", "m1", "m2", "m4"), result.document.messages.map { it.id })
        assertEquals(100L, result.document.messages.first { it.id == "m3" }.orderPin?.tiedTimestampMillis)
        assertEquals(100L, result.document.messages.first { it.id == "m1" }.orderPin?.tiedTimestampMillis)
    }

    @Test
    fun nudgingANonTiedNeighbourIsRejected() {
        val doc = baseDocument() // m3 (ts=100) and m2 (ts=200) are adjacent but do NOT tie
        val result = nudgeSeq3OrderPin(doc, "m3", Seq3PinDirection.DOWN)
        assertFalse(result.applied)
        assertEquals(doc, result.document)
        assertNull(result.document.messages.first { it.id == "m3" }.orderPin)
    }

    @Test
    fun nudgingPastTheEdgeOfTheListIsRejected() {
        val doc = baseDocument()
        val result = nudgeSeq3OrderPin(doc, "m1", Seq3PinDirection.UP) // m1 is already first
        assertFalse(result.applied)
    }

    // ── Inspector single-message field edits (phase 4) ──────────────────────────────────────────

    @Test
    fun setKindToSelfSnapsTheTargetToTheMessagesOwnFromLifeline() {
        val doc = baseDocument()
        val result = applySeq3BulkAction(doc, setOf("m1"), Seq3BulkAction.SetKind(Seq3Kind.SELF))
        assertTrue(result.applied)
        val m1 = result.document.messages.single { it.id == "m1" }
        assertEquals(Seq3Kind.SELF, m1.kind)
        assertEquals(m1.fromLifelineId, m1.toLifelineId)
        assertEquals(Seq3Authoring.EDITED, m1.authoring)
    }

    @Test
    fun setKindAwayFromSelfLeavesAnExistingTargetAlone() {
        val doc = baseDocument()
        val result = applySeq3BulkAction(doc, setOf("m1"), Seq3BulkAction.SetKind(Seq3Kind.ASYNC))
        assertTrue(result.applied)
        val m1 = result.document.messages.single { it.id == "m1" }
        assertEquals(Seq3Kind.ASYNC, m1.kind)
        assertEquals("B", m1.toLifelineId)
    }

    @Test
    fun setPatternReplacesMatchAndLabelAndMarksEdited() {
        val doc = baseDocument()
        val newMatch = Seq3Match("A", "renamed {n}", listOf(Seq3Capture("n", Seq3CaptureSource.AUTHOR)))
        val result = applySeq3BulkAction(doc, setOf("m1"), Seq3BulkAction.SetPattern(newMatch, "renamed {n}"))
        assertTrue(result.applied)
        val m1 = result.document.messages.single { it.id == "m1" }
        assertEquals(newMatch, m1.match)
        assertEquals("renamed {n}", m1.labelTemplate)
        assertEquals(Seq3Authoring.EDITED, m1.authoring)
    }

    @Test
    fun setPatternRejectsABlankTemplate() {
        val doc = baseDocument()
        val result = applySeq3BulkAction(doc, setOf("m1"), Seq3BulkAction.SetPattern(Seq3Match("A", ""), "label"))
        assertFalse(result.applied)
        assertEquals(doc, result.document)
    }

    @Test
    fun setLabelRenamesOnlyTheLabelNotTheMatch() {
        val doc = baseDocument()
        val originalMatch = doc.messages.single { it.id == "m1" }.match
        val result = applySeq3BulkAction(doc, setOf("m1"), Seq3BulkAction.SetLabel("new label"))
        assertTrue(result.applied)
        val m1 = result.document.messages.single { it.id == "m1" }
        assertEquals("new label", m1.labelTemplate)
        assertEquals(originalMatch, m1.match)
    }

    @Test
    fun setRepeatChangesModeAndThreshold() {
        val doc = baseDocument()
        val result = applySeq3BulkAction(doc, setOf("m1"), Seq3BulkAction.SetRepeat(Seq3Repeat.EVERY, 5))
        assertTrue(result.applied)
        val m1 = result.document.messages.single { it.id == "m1" }
        assertEquals(Seq3Repeat.EVERY, m1.repeat)
        assertEquals(5, m1.repeatThreshold)
    }

    @Test
    fun setRepeatRejectsANonPositiveThreshold() {
        val doc = baseDocument()
        val result = applySeq3BulkAction(doc, setOf("m1"), Seq3BulkAction.SetRepeat(Seq3Repeat.COLLAPSE_ABOVE, 0))
        assertFalse(result.applied)
        assertEquals(doc, result.document)
    }
}
