@file:Suppress("MagicNumber")

package com.indagium

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.click
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performMouseInput
import androidx.compose.ui.test.performTextInputSelection
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.unit.dp
import com.indagium.ui.DictatedInlineField
import com.indagium.ui.DictatedTextArea
import com.indagium.ui.TestScriptDraftDialog
import com.indagium.ui.TestStepDraftDialog
import com.indagium.voice.VoiceInputState
import org.junit.After
import org.junit.Rule
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Every free-text field a person writes for an AI carries the same in-field microphone: the AI prompt dialogs of the Tests
 * workspace and the shared prose widgets behind them.
 */
class AiPromptDictationUiTest {
    @get:Rule
    val rule = createComposeRule()

    private val fixture = AiPromptDialogFixture()

    @After
    fun tearDown() = fixture.close()

    private fun idle() = rule.waitUntil(5_000) { fixture.coordinator.snapshot.value.state == VoiceInputState.Idle }

    @Test
    fun theDraftStepsPromptHasAMicrophoneAndDictationFillsIt() {
        val tag = "voice-input-test-case:${fixture.case.id}:draft-steps-prompt"
        rule.setContent { fixture.Provide { TestStepDraftDialog(fixture.suiteId, fixture.case, onDismiss = {}) } }
        rule.onNodeWithTag(tag).assertIsDisplayed()
        rule.onNodeWithTag(tag).performMouseInput { click() }
        rule.onNodeWithContentDescription("Stop voice input").performMouseInput { click() }
        idle()
        rule.onAllNodes(hasSetTextAction())[0].assertTextEquals("New words")
    }

    @Test
    fun theScriptGenerationPromptHasAMicrophoneAndDictationFillsIt() {
        val tag = "voice-input-test-script-draft:new:request"
        rule.setContent { fixture.Provide { TestScriptDraftDialog(existing = null, onApplied = {}, onDismiss = {}) } }
        rule.onNodeWithTag(tag).assertIsDisplayed()
        rule.onNodeWithTag(tag).performMouseInput { click() }
        rule.onNodeWithContentDescription("Stop voice input").performMouseInput { click() }
        idle()
        rule.onAllNodes(hasSetTextAction())[0].assertTextEquals("New words")
    }

    @Test
    fun dictationReplacesTheRetainedSelectionOfAPromptArea() {
        var text by mutableStateOf("before old after")
        rule.setContent {
            fixture.Provide {
                Column(Modifier.width(360.dp)) {
                    DictatedTextArea(text, { text = it }, proseTargetId = "qa:prompt-area", maxHeight = 120.dp)
                }
            }
        }
        val field = rule.onAllNodes(hasSetTextAction())[0]
        field.performClick()
        field.performTextInputSelection(TextRange(7, 10))
        rule.onNodeWithTag("voice-input-qa:prompt-area").performMouseInput { click() }
        rule.onNodeWithContentDescription("Stop voice input").performMouseInput { click() }
        idle()
        assertEquals("before new words after", text)
    }

    @Test
    fun anInlinePromptFieldKeepsItsMicrophoneStillWhenFocused() {
        var text by mutableStateOf("")
        rule.setContent {
            fixture.Provide {
                Column(Modifier.width(360.dp)) {
                    DictatedInlineField(text, { text = it }, proseTargetId = "qa:prompt-line", placeholder = "Say something")
                }
            }
        }
        val mic = rule.onNodeWithTag("voice-input-qa:prompt-line")
        val before = mic.fetchSemanticsNode().boundsInRoot
        rule.onAllNodes(hasSetTextAction())[0].performClick()
        rule.waitForIdle()
        assertEquals(before, rule.onNodeWithTag("voice-input-qa:prompt-line").fetchSemanticsNode().boundsInRoot)
        assertTrue(before.right <= 360f, "the microphone stays inside its field: $before")
        mic.performMouseInput { click() }
        rule.onNodeWithContentDescription("Stop voice input").performMouseInput { click() }
        idle()
        assertEquals("New words", text)
    }
}
