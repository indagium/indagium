package com.indagium.proofread

import com.indagium.ui.proofreadingDetailMessage
import com.indagium.ui.shiftProofreadingIssues
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class ProofreadingIssueShiftTest {
    private fun issue(start: Int, end: Int, category: ProofreadingCategory = ProofreadingCategory.SPELLING) =
        ProofreadingIssue(start, end, "m", listOf("x"), "r", category)

    @Test
    fun typingBeforeAMarkShiftsItByTheInsertedLength() {
        val old = "A speling mistake"
        val shifted = shiftProofreadingIssues(listOf(issue(2, 9)), old, "Oh. A speling mistake")

        assertEquals(listOf(issue(6, 13)), shifted)
    }

    @Test
    fun typingAfterAMarkLeavesItAlone() {
        val old = "A speling mistake"

        assertEquals(listOf(issue(2, 9)), shiftProofreadingIssues(listOf(issue(2, 9)), old, "A speling mistake here"))
    }

    @Test
    fun deletingBeforeAMarkMovesItBack() {
        assertEquals(listOf(issue(0, 7)), shiftProofreadingIssues(listOf(issue(2, 9)), "A speling mistake", "speling mistake"))
    }

    @Test
    fun anEditInsideTheMarkedRangeDropsIt() {
        assertEquals(emptyList(), shiftProofreadingIssues(listOf(issue(2, 9)), "A speling mistake", "A spelling mistake"))
    }

    @Test
    fun continuingTheMarkedWordDropsItButTypingASpaceAfterItKeepsIt() {
        val old = "A speling"

        assertEquals(emptyList(), shiftProofreadingIssues(listOf(issue(2, 9)), old, "A spelings"))
        assertEquals(listOf(issue(2, 9)), shiftProofreadingIssues(listOf(issue(2, 9)), old, "A speling "))
    }

    @Test
    fun joiningTheMarkToTheWordBeforeItDropsIt() {
        assertEquals(emptyList(), shiftProofreadingIssues(listOf(issue(2, 9)), "A speling", "Aspeling"))
    }

    @Test
    fun onlyMarksTheEditTouchesAreDropped() {
        val old = "speling and alot more"
        val marks = listOf(issue(0, 7), issue(12, 16, ProofreadingCategory.GRAMMAR))
        val shifted = shiftProofreadingIssues(marks, old, "spelling and alot more")

        assertEquals(listOf(issue(13, 17, ProofreadingCategory.GRAMMAR)), shifted)
    }

    @Test
    fun replacingTheWholeTextWithSomethingElseKeepsNothing() {
        assertEquals(emptyList(), shiftProofreadingIssues(listOf(issue(2, 9)), "A speling mistake", "Completely different"))
    }

    @Test
    fun spellingIsTheDefaultKindAndOnlyInformativeMessagesAreShown() {
        assertEquals(ProofreadingCategory.SPELLING, ProofreadingIssue(0, 1, "m", emptyList(), "r").category)
        assertNull(proofreadingDetailMessage(issue(0, 1).copy(message = "Possible spelling mistake found.")))
        assertNull(proofreadingDetailMessage(issue(0, 1).copy(message = "Spelling")))
        assertNull(proofreadingDetailMessage(issue(0, 1, ProofreadingCategory.GRAMMAR).copy(message = "Grammar")))
        assertEquals(
            "Use 'a lot' instead.",
            proofreadingDetailMessage(issue(0, 1, ProofreadingCategory.GRAMMAR).copy(message = "Use 'a lot' instead.")),
        )
    }
}
