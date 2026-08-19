package com.indagium

import com.indagium.ui.Seq3KeyAction
import com.indagium.ui.Seq3PanelSection
import com.indagium.ui.seq3ClampArtifactsHeight
import com.indagium.ui.seq3ClampDividerWidth
import com.indagium.ui.seq3ClampLifelinesHeight
import com.indagium.ui.seq3KeyAction
import com.indagium.ui.seq3PanelWeightedSection
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/** The pure §09 keyboard mapper in `ui/Seq3Workspace.kt`. Composition (the actual `onKeyEvent`
 *  wiring) is untested here, matching this phase's other Seq3*Test files — only the mapping
 *  function itself, which is the whole point of `seq3KeyAction` being split out. */
class Seq3KeyActionTest {
    private fun action(key: String, shift: Boolean = false, textFieldFocused: Boolean = false, guidedActive: Boolean = false) =
        seq3KeyAction(key, shift, textFieldFocused, guidedActive)

    // ── The spec §09 table, one key at a time ────────────────────────────────────────────────

    @Test
    fun jAndKMovePrevAndNext() {
        assertEquals(Seq3KeyAction.PrevMessage, action("j"))
        assertEquals(Seq3KeyAction.NextMessage, action("k"))
    }

    @Test
    fun digitsOneToNineSetTarget() {
        for (digit in 1..9) {
            assertEquals(Seq3KeyAction.SetTarget(digit), action(digit.toString()), "digit $digit should set target")
        }
    }

    @Test
    fun shiftDigitsOneToNineSetSource() {
        for (digit in 1..9) {
            assertEquals(Seq3KeyAction.SetSource(digit), action(digit.toString(), shift = true), "shift+$digit should set source")
        }
    }

    @Test
    fun fStartsTheGuidedPass() {
        assertEquals(Seq3KeyAction.StartGuidedPass, action("f"))
    }

    @Test
    fun eEditsTheLabel() {
        assertEquals(Seq3KeyAction.EditLabel, action("e"))
    }

    @Test
    fun hTogglesHide() {
        assertEquals(Seq3KeyAction.ToggleHide, action("h"))
    }

    @Test
    fun mMergesTheSelection() {
        assertEquals(Seq3KeyAction.MergeSelection, action("m"))
    }

    @Test
    fun gGroupsTheSelectionAsAFragment() {
        assertEquals(Seq3KeyAction.GroupSelection, action("g"))
    }

    @Test
    fun lJumpsToTheLogLine() {
        assertEquals(Seq3KeyAction.JumpToLog, action("l"))
    }

    @Test
    fun slashFocusesTheFilter() {
        assertEquals(Seq3KeyAction.FocusFilter, action("/"))
    }

    // ── The text-field guard: the single most important case here ───────────────────────────────
    //
    // The map is single-letter, so without this guard typing "h" into the filter box would hide a
    // message underneath the user's cursor instead of typing the letter "h".

    @Test
    fun everyLetterKeyIsSuppressedWhileATextFieldIsFocused() {
        listOf("j", "k", "f", "e", "h", "m", "g", "l", "/", "s").forEach { key ->
            assertNull(action(key, textFieldFocused = true), "'$key' must not fire while a text field is focused")
        }
    }

    @Test
    fun everyDigitKeyIsSuppressedWhileATextFieldIsFocused() {
        for (digit in 1..9) {
            assertNull(action(digit.toString(), textFieldFocused = true), "digit $digit must not fire while a text field is focused")
            assertNull(action(digit.toString(), shift = true, textFieldFocused = true), "shift+$digit must not fire while a text field is focused")
        }
    }

    @Test
    fun enterIsSuppressedWhileATextFieldIsFocusedEvenDuringAGuidedPass() {
        assertNull(action("Enter", textFieldFocused = true, guidedActive = true))
    }

    // ── Escape is the ONE key that still fires while a text field is focused ────────────────────

    @Test
    fun escapeFiresRegardlessOfTextFieldFocus() {
        assertEquals(Seq3KeyAction.Escape, action("Escape", textFieldFocused = true))
        assertEquals(Seq3KeyAction.Escape, action("Escape", textFieldFocused = false))
    }

    @Test
    fun escapeFiresRegardlessOfGuidedPassState() {
        assertEquals(Seq3KeyAction.Escape, action("Escape", guidedActive = true))
        assertEquals(Seq3KeyAction.Escape, action("Escape", guidedActive = false))
    }

    // ── "s" only means Skip while a guided pass is active ────────────────────────────────────────

    @Test
    fun sIsNotBoundOutsideAGuidedPass() {
        assertNull(action("s", guidedActive = false))
    }

    @Test
    fun sSkipsOnlyDuringAGuidedPass() {
        assertEquals(Seq3KeyAction.SkipGuided, action("s", guidedActive = true))
    }

    // ── Enter only means confirm while a guided pass is active ──────────────────────────────────

    @Test
    fun enterIsNotBoundOutsideAGuidedPass() {
        assertNull(action("Enter", guidedActive = false))
    }

    @Test
    fun enterConfirmsOnlyDuringAGuidedPass() {
        assertEquals(Seq3KeyAction.ConfirmGuided, action("Enter", guidedActive = true))
    }

    // ── Uppercase and lowercase letters map the same ─────────────────────────────────────────────

    @Test
    fun uppercaseLettersMapTheSameAsLowercase() {
        assertEquals(Seq3KeyAction.PrevMessage, action("J"))
        assertEquals(Seq3KeyAction.NextMessage, action("K"))
        assertEquals(Seq3KeyAction.StartGuidedPass, action("F"))
        assertEquals(Seq3KeyAction.EditLabel, action("E"))
        assertEquals(Seq3KeyAction.ToggleHide, action("H"))
        assertEquals(Seq3KeyAction.MergeSelection, action("M"))
        assertEquals(Seq3KeyAction.GroupSelection, action("G"))
        assertEquals(Seq3KeyAction.JumpToLog, action("L"))
        assertEquals(Seq3KeyAction.SkipGuided, action("S", guidedActive = true))
    }

    // ── Everything else is unbound ────────────────────────────────────────────────────────────────

    @Test
    fun anUnrelatedKeyMapsToNull() {
        assertNull(action("z"))
        assertNull(action("q"))
    }

    @Test
    fun aDigitOutsideOneToNineMapsToNull() {
        assertNull(action("0"))
    }

    // ── seq3ClampDividerWidth: the queue-panel/inspector divider drag math (item 14) ────────────
    //
    // Same "pure piece split out of the composable" rationale as seq3KeyAction —
    // Seq3Workspace.kt's HDivider onDelta callbacks call this directly rather than inlining the
    // coerceIn themselves.

    @Test
    fun addsTheDeltaWhenWithinBounds() {
        assertEquals(300f, seq3ClampDividerWidth(current = 280f, delta = 20f, min = 200f, max = 400f))
        assertEquals(260f, seq3ClampDividerWidth(current = 280f, delta = -20f, min = 200f, max = 400f))
    }

    @Test
    fun clampsToMinWhenTheDragWouldShrinkPastIt() {
        assertEquals(200f, seq3ClampDividerWidth(current = 210f, delta = -50f, min = 200f, max = 400f))
    }

    @Test
    fun clampsToMaxWhenTheDragWouldGrowPastIt() {
        assertEquals(400f, seq3ClampDividerWidth(current = 390f, delta = 50f, min = 200f, max = 400f))
    }

    @Test
    fun aZeroDeltaLeavesTheWidthUnchanged() {
        assertEquals(300f, seq3ClampDividerWidth(current = 300f, delta = 0f, min = 200f, max = 400f))
    }

    @Test
    fun repeatedSmallDeltasAccumulateWithoutDrifting() {
        var width = 280f
        repeat(5) { width = seq3ClampDividerWidth(width, delta = 10f, min = 200f, max = 400f) }
        assertEquals(330f, width)
    }

    // ── seq3ClampLifelinesHeight: stacked queue-panel divider drag math ───────────────────────

    @Test
    fun draggingTheLifelinesDividerUpGrowsTheLowerSection() {
        assertEquals(260f, seq3ClampLifelinesHeight(current = 220f, delta = -40f))
    }

    @Test
    fun draggingTheLifelinesDividerDownShrinksTheLowerSection() {
        assertEquals(180f, seq3ClampLifelinesHeight(current = 220f, delta = 40f))
    }

    @Test
    fun lifelinesHeightClampsAtBothBounds() {
        assertEquals(120f, seq3ClampLifelinesHeight(current = 130f, delta = 40f))
        assertEquals(420f, seq3ClampLifelinesHeight(current = 410f, delta = -40f))
    }

    // ── seq3ClampArtifactsHeight: WP3 item 9's Fragments & notes divider drag math ────────────

    @Test
    fun draggingTheArtifactsDividerUpGrowsTheSection() {
        assertEquals(240f, seq3ClampArtifactsHeight(current = 200f, delta = -40f))
    }

    @Test
    fun draggingTheArtifactsDividerDownShrinksTheSection() {
        assertEquals(160f, seq3ClampArtifactsHeight(current = 200f, delta = 40f))
    }

    @Test
    fun artifactsHeightClampsAtBothBounds() {
        assertEquals(100f, seq3ClampArtifactsHeight(current = 110f, delta = 40f))
        assertEquals(360f, seq3ClampArtifactsHeight(current = 350f, delta = -40f))
    }

    // ── seq3PanelWeightedSection: WP8's Messages > Lifelines > Artifacts weight(1f) chain ──────
    //
    // The panel's three stacked sections can each be independently hidden/collapsed; exactly one
    // (or none) should ever receive Modifier.weight(1f), so the Column keeps filling its container
    // no matter which combination the user has toggled off.

    @Test
    fun messagesWinsWheneverExpandedRegardlessOfTheOtherTwo() {
        assertEquals(Seq3PanelSection.MESSAGES, seq3PanelWeightedSection(true, true, true))
        assertEquals(Seq3PanelSection.MESSAGES, seq3PanelWeightedSection(true, false, false))
        assertEquals(Seq3PanelSection.MESSAGES, seq3PanelWeightedSection(true, true, false))
        assertEquals(Seq3PanelSection.MESSAGES, seq3PanelWeightedSection(true, false, true))
    }

    @Test
    fun lifelinesStepsUpWhenMessagesIsCollapsedButLifelinesIsVisible() {
        assertEquals(Seq3PanelSection.LIFELINES, seq3PanelWeightedSection(false, true, true))
        assertEquals(Seq3PanelSection.LIFELINES, seq3PanelWeightedSection(false, true, false))
    }

    @Test
    fun artifactsStepsUpOnlyWhenNeitherMessagesNorLifelinesIsInTheRunning() {
        assertEquals(Seq3PanelSection.ARTIFACTS, seq3PanelWeightedSection(false, false, true))
    }

    @Test
    fun noSectionIsWeightedWhenAllThreeAreCollapsedOrHidden() {
        assertNull(seq3PanelWeightedSection(false, false, false))
    }
}
