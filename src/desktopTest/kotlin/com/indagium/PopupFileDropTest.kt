package com.indagium

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draganddrop.DragAndDropEvent
import androidx.compose.ui.draganddrop.DragAndDropTarget
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.click
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performMouseInput
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.PopupProperties
import com.indagium.ui.LocalFileDropTarget
import com.indagium.ui.Popup
import com.indagium.ui.clipPopupPosition
import org.junit.Rule
import org.junit.Test
import kotlin.test.assertEquals

private const val MENU_GAP_PX = 25

/**
 * A focusable popup is a window-sized scene layer when the app's file drop target is available, so a file dragged in from the OS
 * file manager still reaches the app while a dropdown is open (the scene hands drag-and-drop to the focused layer only). It must
 * otherwise behave like Compose's own Popup: same placement, dismissal on an outside press only.
 */
@OptIn(ExperimentalTestApi::class)
class PopupFileDropTest {
    @get:Rule
    val rule = createComposeRule()

    private val target = object : DragAndDropTarget {
        override fun onDrop(event: DragAndDropEvent) = false
    }

    private var withDropTarget by mutableStateOf(false)
    private var open by mutableStateOf(true)
    private var dismissals = 0

    private fun setMenu() {
        rule.setContent {
            CompositionLocalProvider(LocalFileDropTarget provides if (withDropTarget) target else null) {
                Box(Modifier.fillMaxSize().testTag("window")) {
                    Box(Modifier.offset(40.dp, 30.dp).size(60.dp, 20.dp)) {
                        if (open) {
                            Popup(
                                alignment = Alignment.TopStart,
                                offset = IntOffset(0, MENU_GAP_PX),
                                onDismissRequest = {
                                    dismissals++
                                    open = false
                                },
                                properties = PopupProperties(focusable = true),
                            ) {
                                Box(Modifier.size(120.dp, 80.dp).background(Color.Gray).testTag("menu")) { BasicText("Menu body") }
                            }
                        }
                    }
                }
            }
        }
        rule.waitForIdle()
    }

    @Test
    fun theMenuIsPlacedExactlyWhereComposesOwnPopupPlacesIt() {
        setMenu()
        val plain = rule.onNodeWithTag("menu").getUnclippedBoundsInRoot()
        rule.runOnIdle { withDropTarget = true }
        rule.waitForIdle()
        rule.onNodeWithTag("menu").assertIsDisplayed()
        val windowSized = rule.onNodeWithTag("menu").getUnclippedBoundsInRoot()
        assertEquals(plain, windowSized)
    }

    @Test
    fun anOutsidePressDismissesButAPressOnTheBlankMenuBodyDoesNot() {
        withDropTarget = true
        setMenu()
        rule.onNodeWithTag("menu").performMouseInput { click(Offset(width - 4f, height - 4f)) }
        rule.waitForIdle()
        rule.onNodeWithTag("menu").assertIsDisplayed()
        assertEquals(0, dismissals)

        rule.onNodeWithTag("window").performMouseInput { click(Offset(width - 4f, height - 4f)) }
        rule.waitForIdle()
        rule.onNodeWithTag("menu").assertDoesNotExist()
        assertEquals(1, dismissals)
    }

    @Test
    fun withoutADropTargetThePopupIsComposesOwn() {
        setMenu()
        rule.onNodeWithTag("window").performMouseInput { click(Offset(width - 4f, height - 4f)) }
        rule.waitForIdle()
        rule.onNodeWithTag("menu").assertDoesNotExist()
        assertEquals(1, dismissals)
    }

    @Test
    fun clippingKeepsAPopupSmallerThanTheWindowInsideIt() {
        val window = IntSize(400, 300)
        val content = IntSize(100, 50)
        assertEquals(IntOffset(0, 0), clipPopupPosition(IntOffset(-20, -5), content, window))
        assertEquals(IntOffset(300, 250), clipPopupPosition(IntOffset(380, 290), content, window))
        assertEquals(IntOffset(10, 20), clipPopupPosition(IntOffset(10, 20), content, window))
        assertEquals(IntOffset(0, 0), clipPopupPosition(IntOffset(50, 50), IntSize(500, 400), window))
    }
}
