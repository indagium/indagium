package com.indagium

import androidx.compose.ui.input.key.Key
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.pressKey
import androidx.compose.ui.test.withKeyDown
import androidx.compose.ui.unit.IntSize
import com.indagium.ui.AnnotationMarkdownEditorDialog
import com.indagium.ui.isMacOs
import org.junit.Rule
import org.junit.Test

class MarkdownAnnotationEditorUiTest {
    @get:Rule
    val rule = createComposeRule()

    @Test
    fun undoSurvivesPreviewToWriteRoundTrip() {
        rule.setContent {
            AnnotationMarkdownEditorDialog(
                title = "Edit note",
                initialText = "before",
                confirmLabel = "Save note",
                windowSize = IntSize(800, 800),
                sessionKey = "undo-round-trip",
                onConfirm = {},
                onDismiss = {},
            )
        }

        val editor = rule.onNodeWithTag("annotation-markdown-editor")
        editor.performClick()
        editor.performTextInput(" after")
        rule.onNodeWithText("Preview").performClick()
        rule.onNodeWithText("Write").performClick()

        editor.performKeyInput {
            withKeyDown(if (isMacOs) Key.MetaLeft else Key.CtrlLeft) {
                pressKey(Key.Z)
            }
        }

        editor.assertTextEquals("before")
    }
}
