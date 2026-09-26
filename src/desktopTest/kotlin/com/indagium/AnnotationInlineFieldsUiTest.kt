package com.indagium

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertHasClickAction
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.pressKey
import androidx.compose.ui.test.waitUntilAtLeastOneExists
import com.indagium.model.AnnBlock
import com.indagium.model.Annotations
import com.indagium.model.AppSettings
import com.indagium.ui.AnnotationPanel
import com.indagium.ui.mkTab
import org.junit.Rule
import org.junit.Test
import kotlin.test.assertEquals

@OptIn(ExperimentalTestApi::class)
class AnnotationInlineFieldsUiTest {
    @get:Rule
    val rule = createComposeRule()

    @Test
    fun populatedNoteStaysEditableAcrossSeveralKeystrokes() {
        val noteText = mutableStateOf("**Existing note**")
        installPanel(note = { noteText.value }, onNote = { noteText.value = it })

        rule.onNodeWithText("Existing note").assertIsDisplayed()
        rule.onNodeWithTag("annotation-note-field").performClick()
        rule.onNodeWithTag("annotation-note-field").performKeyInput { pressKey(Key.MoveEnd) }
        rule.onNodeWithTag("annotation-note-field").performTextInput(" first")
        rule.onNodeWithTag("annotation-note-field").performTextInput(" second")

        assertEquals("**Existing note** first second", noteText.value)
    }

    @Test
    fun emptyNoteStaysEditableFromFirstCharacterUntilFocusLeaves() {
        val noteText = mutableStateOf("")
        val prefix = mutableStateOf("")
        val suffix = mutableStateOf("")
        installPanel(
            note = { noteText.value },
            prefix = { prefix.value },
            suffix = { suffix.value },
            onNote = { noteText.value = it },
            onPrefix = { prefix.value = it },
            onSuffix = { suffix.value = it },
        )

        // The three empty fields coexist, but mounting the panel must not steal focus into each
        // one. The note enters inline-edit mode only when clicked.
        rule.onNodeWithTag("annotation-note-field").assert(hasSetTextAction()).performClick()
        rule.onNodeWithTag("annotation-note-field").performTextInput("a")
        rule.onNodeWithTag("annotation-note-field").assert(hasSetTextAction())
        rule.onNodeWithTag("annotation-note-field").performTextInput("bc")
        rule.onNodeWithTag("annotation-note-field").assert(hasSetTextAction())
        assertEquals("abc", noteText.value)

        // Moving to another blank field edits that field and leaves the completed note in preview.
        rule.onNodeWithTag("annotation-prefix-field").performClick()
        rule.onNodeWithTag("annotation-prefix-field").performTextInput("prefix")
        assertEquals("prefix", prefix.value)
        rule.waitUntil(2_000) {
            runCatching {
                rule.onNode(hasTestTag("annotation-note-field").and(hasSetTextAction())).assertDoesNotExist()
            }.isSuccess
        }
        rule.onNodeWithText("abc").assertIsDisplayed()
    }

    @Test
    fun prefixAndNextStepsRenderMarkdownAndOpenForInlineEditing() {
        val prefix = mutableStateOf("**Context prefix**")
        val suffix = mutableStateOf("**Follow-up steps**")
        installPanel(
            prefix = { prefix.value },
            suffix = { suffix.value },
            onPrefix = { prefix.value = it },
            onSuffix = { suffix.value = it },
        )

        rule.onNodeWithText("Context prefix").assertIsDisplayed()
        rule.onNodeWithText("Follow-up steps").assertIsDisplayed()
        rule.onNodeWithTag("annotation-prefix-field").assertHasClickAction().performClick()
        rule.onNodeWithTag("annotation-prefix-field").performTextInput(" edited")
        rule.onNodeWithTag("annotation-prefix-field").performTextInput(" twice")
        rule.onNodeWithTag("annotation-next-steps-field").assertHasClickAction().performClick()
        rule.waitUntilAtLeastOneExists(
            hasTestTag("annotation-next-steps-field").and(hasSetTextAction()),
            2_000,
        )
        rule.onNodeWithTag("annotation-next-steps-field").performTextInput(" updated")
        rule.onNodeWithTag("annotation-next-steps-field").performTextInput(" twice")

        assertEquals("**Context prefix** edited twice", prefix.value)
        assertEquals("**Follow-up steps** updated twice", suffix.value)
    }

    private fun installPanel(
        prefix: () -> String = { "" },
        suffix: () -> String = { "" },
        note: () -> String = { "Body" },
        onPrefix: (String) -> Unit = {},
        onSuffix: (String) -> Unit = {},
        onNote: (String) -> Unit = {},
    ) {
        rule.setContent {
            val tab = mkTab("notes", "notes.log", emptyList()).copy(
                annotations = Annotations(
                    prefix = prefix(),
                    suffix = suffix(),
                blocks = listOf(AnnBlock.Note("note", note())),
                ),
            )
            AnnotationPanel(
                tab = tab,
                settings = AppSettings(renderAnnotationMarkdownInline = true),
                onToggleMd = {},
                onCopy = {},
                onCopyImage = {},
                onExportFrames = {},
                onSave = {},
                onNewAnalysis = {},
                onToggleRecentNotes = {},
                onOpenNote = {},
                onUpdatePrefix = onPrefix,
                onUpdateSuffix = onSuffix,
                onUpdateIssueDescription = {},
                onUpdateBlock = { _, value -> onNote(value) },
                onRemoveBlock = {},
                onMoveBlock = { _, _ -> },
                onReorderBlock = { _, _ -> },
                onAddNoteAfter = {},
                onAddImage = { _, _, _ -> null },
                onUnhandledFileDrop = {},
                onNavigateLogRef = {},
                onNavigateVideoFrame = {},
                width = 360f,
                modifier = Modifier.fillMaxSize(),
            )
        }
    }
}
