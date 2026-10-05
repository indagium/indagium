package com.indagium.testing

import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.pressKey
import androidx.compose.ui.unit.dp
import com.indagium.testing.model.moveById
import com.indagium.ui.AppText
import com.indagium.ui.ReorderGrip
import com.indagium.ui.ReorderMoveButtons
import com.indagium.ui.ReorderableColumn
import org.junit.Rule
import org.junit.Test
import kotlin.test.assertEquals

/** Real Compose interaction with ReorderableColumn: Alt+Up/Down on a focused row, the move buttons and a grip drag. */
@OptIn(ExperimentalTestApi::class)
class ReorderableColumnUiTest {
    @get:Rule
    val rule = createComposeRule()

    private var order by mutableStateOf(listOf("a", "b", "c"))
    private val moves = mutableListOf<Pair<String, Int>>()

    private fun install(enabled: Boolean = true) {
        order = listOf("a", "b", "c")
        moves.clear()
        rule.setContent {
            ReorderableColumn(
                items = order,
                idOf = { it },
                onMove = { id, to ->
                    moves += id to to
                    order = order.moveById(id, to) { it }
                },
                reorderEnabled = enabled,
            ) { item, row ->
                Row(Modifier.fillMaxWidth().height(30.dp).testTag("row-$item")) {
                    ReorderGrip(row, Modifier.testTag("grip-$item"))
                    AppText(item)
                    ReorderMoveButtons(row)
                }
            }
        }
    }

    private fun altArrow(tag: String, key: Key) {
        rule.onNodeWithTag(tag).performKeyInput {
            keyDown(Key.AltLeft)
            pressKey(key)
            keyUp(Key.AltLeft)
        }
    }

    @Test
    fun altDownOnTheFocusedRowMovesItOnePlaceDownAndItKeepsFocusForTheNextPress() {
        install()

        rule.onNodeWithTag("row-b").performClick()
        altArrow("row-b", Key.DirectionDown)

        assertEquals(listOf("a", "c", "b"), order)
        assertEquals(listOf("b" to 2), moves)
        altArrow("row-b", Key.DirectionUp)
        assertEquals(listOf("a", "b", "c"), order, "the moved row still has focus, so Alt+Up moves it back")
    }

    @Test
    fun altUpOnTheFirstRowDoesNothing() {
        install()

        rule.onNodeWithTag("row-a").performClick()
        altArrow("row-a", Key.DirectionUp)

        assertEquals(listOf("a", "b", "c"), order)
        assertEquals(emptyList(), moves)
    }

    @Test
    fun theMoveButtonsMoveARowAndTheEdgeButtonsAreInert() {
        install()

        rule.onAllNodesWithText("↓")[1].performClick()
        assertEquals(listOf("a", "c", "b"), order)

        rule.onAllNodesWithText("↓")[2].performClick()
        rule.onAllNodesWithText("↑")[0].performClick()
        assertEquals(listOf("a", "c", "b"), order, "↓ on the last row and ↑ on the first row do nothing")
        assertEquals(1, moves.size)
    }

    @Test
    fun draggingTheGripPastTheNeighbourCommitsOneMoveOnRelease() {
        install()

        rule.onNodeWithTag("grip-b").performTouchInput {
            down(center)
            moveBy(Offset(0f, 20f))
            moveBy(Offset(0f, 40f))
            moveBy(Offset(0f, 60f))
            up()
        }
        rule.waitForIdle()

        assertEquals(listOf("a", "c", "b"), order)
        assertEquals(listOf("b" to 2), moves)
    }

    @Test
    fun aDisabledColumnIgnoresKeysButtonsAndDrags() {
        install(enabled = false)

        rule.onNodeWithTag("row-b").performClick()
        altArrow("row-b", Key.DirectionDown)
        rule.onAllNodesWithText("↓")[0].performClick()
        rule.onNodeWithTag("grip-b").performTouchInput {
            down(center)
            moveBy(Offset(0f, 80f))
            up()
        }
        rule.waitForIdle()

        assertEquals(listOf("a", "b", "c"), order)
        assertEquals(emptyList(), moves)
    }
}
