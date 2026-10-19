@file:Suppress("MagicNumber")

package com.indagium

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.test.SemanticsNodeInteraction
import androidx.compose.ui.test.junit4.ComposeContentTestRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performMouseInput

/** The suggestion popup only opens from the pointer (or Alt+Enter), so tests probe the first line for a marked word. */
internal const val PROOFREADING_POPUP_TAG = "proofreading-suggestion-popup"

internal fun ComposeContentTestRule.suggestionPopupExists(): Boolean =
    runCatching { onNodeWithTag(PROOFREADING_POPUP_TAG).fetchSemanticsNode() }.isSuccess

/**
 * Lets the 600 ms debounce elapse and the check (which runs on its own worker thread) deliver its result,
 * so the marks exist before a test moves the pointer.
 */
internal fun ComposeContentTestRule.letProofreadingSettle() {
    mainClock.advanceTimeBy(700)
    repeat(6) {
        Thread.sleep(80)
        mainClock.advanceTimeBy(50)
    }
    waitForIdle()
}

/**
 * Sweeps the pointer along [y] of the first text line until a marked word opens its popup by hover. The sweep
 * stops at the first hit, so the pointer is left on the word that opened it. Call [letProofreadingSettle] first.
 */
internal fun ComposeContentTestRule.hoverUntilSuggestionOpens(
    editor: SemanticsNodeInteraction,
    y: Float = 9f,
    xRange: IntProgression = 24..260 step 8,
    hoverMillis: Long = 400,
): Offset {
    for (x in xRange) {
        val position = Offset(x.toFloat(), y)
        editor.performMouseInput { moveTo(position) }
        mainClock.advanceTimeBy(hoverMillis)
        waitForIdle()
        if (suggestionPopupExists()) return position
    }
    error("no marked word opened a suggestion on the first line")
}
