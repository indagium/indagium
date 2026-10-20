package com.indagium

import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.TextFieldValue
import com.indagium.ai.AiRunEvent
import com.indagium.model.AiProviderKind
import com.indagium.model.AiProviderProfile
import com.indagium.model.AppSettings
import com.indagium.model.WorkflowAiSelection
import com.indagium.testing.run.profileProblems
import com.indagium.testing.run.withRunOverrides
import com.indagium.ui.AgentTextWording
import com.indagium.ui.AiWorkflow
import com.indagium.ui.applyNoteRewriteSnapshot
import com.indagium.ui.captureNoteRewriteSnapshot
import com.indagium.ui.completedAgentAnswer
import com.indagium.ui.noteRewritePrompt
import com.indagium.ui.settingsFromJson
import com.indagium.ui.settingsJson
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

class NoteRewriteTest {
    private val wording = AgentTextWording(
        sessionPrefix = "test",
        failure = "Provider failed",
        unfinished = "Provider did not finish",
        empty = "Provider returned nothing",
    )

    @Test
    fun collapsedSelectionUsesWholeDraftAndSelectedRangeIsCapturedExactly() {
        val whole = captureNoteRewriteSnapshot("note-1", TextFieldValue("keep **meaning**", TextRange(2)), null)
        assertEquals("keep **meaning**", whole?.original)
        assertEquals(0 to 16, whole?.let { it.start to it.end })

        val selected = captureNoteRewriteSnapshot("note-1", TextFieldValue("one two three", TextRange(8, 13)), null)
        assertEquals("three", selected?.original)
        assertEquals(8 to 13, selected?.let { it.start to it.end })
    }

    @Test
    fun retainedSelectionSurvivesToolbarCollapseAndEmptyTargetIsNotRewritten() {
        val selected = captureNoteRewriteSnapshot("note-1", TextFieldValue("prefix target suffix", TextRange(7)), TextRange(7, 13))
        assertEquals("target", selected?.original)
        assertNull(captureNoteRewriteSnapshot("note-1", TextFieldValue("   ", TextRange(0)), null))
        assertNull(captureNoteRewriteSnapshot("note-1", TextFieldValue("hello world", TextRange(5, 6)), null))
    }

    @Test
    fun applyRequiresTheCapturedSessionAndDraftAndPreservesTextOutsideRange() {
        val snapshot = captureNoteRewriteSnapshot("stable-session", TextFieldValue("before typo after", TextRange(7, 11)), null)!!
        assertEquals("before corrected after", applyNoteRewriteSnapshot("stable-session", snapshot.draft, snapshot, "corrected"))
        assertNull(applyNoteRewriteSnapshot("another-session", snapshot.draft, snapshot, "corrected"))
        assertNull(applyNoteRewriteSnapshot("stable-session", "newer text", snapshot, "corrected"))
    }

    @Test
    fun promptIncludesOnlyCapturedNoteTextAndOptionalInstructions() {
        val prompt = noteRewritePrompt("Only this paragraph", "Keep it concise")
        assertTrue(prompt.contains("Only this paragraph"))
        assertTrue(prompt.contains("Keep it concise"))
        assertTrue(!prompt.contains("logcat"))
        assertTrue(!prompt.contains("screenshot"))
        assertTrue(!prompt.contains("audio"))
    }

    @Test
    fun previewRequiresACompleteSuccessfulAgentRun() {
        assertEquals("complete text", completedAgentAnswer(listOf(AiRunEvent.AssistantDelta("complete text"), AiRunEvent.Done), wording))
        assertFailsWith<IllegalStateException> {
            completedAgentAnswer(listOf(AiRunEvent.AssistantDelta("partial"), AiRunEvent.Error("offline")), wording)
        }
        assertFailsWith<IllegalStateException> {
            completedAgentAnswer(listOf(AiRunEvent.AssistantDelta("partial"), AiRunEvent.Cancelled), wording)
        }
        assertFailsWith<IllegalStateException> {
            completedAgentAnswer(listOf(AiRunEvent.AssistantDelta("partial")), wording)
        }
    }

    @Test
    fun noteRewriteProfileChoicePersistsIndependentlyFromOtherAiWorkflows() {
        val profiles = listOf(
            AiProviderProfile("local", "Local", "", "llama", selected = true),
            AiProviderProfile("codex", "Codex", "", "gpt", kind = AiProviderKind.CODEX_ACCOUNT),
        )
        val settings = AppSettings(
            aiProviderProfiles = profiles,
            workflowAiSelections = mapOf(
                AiWorkflow.NOTE_REWRITE to WorkflowAiSelection("codex", "gpt-5", "high"),
                AiWorkflow.RECORDING_REWRITE to WorkflowAiSelection("local", "qwen"),
            ),
        )
        val decoded = settingsFromJson(settings.settingsJson())!!
        assertEquals(WorkflowAiSelection("codex", "gpt-5", "high"), decoded.workflowAiSelections[AiWorkflow.NOTE_REWRITE])
        assertEquals(WorkflowAiSelection("local", "qwen"), decoded.workflowAiSelections[AiWorkflow.RECORDING_REWRITE])
    }

    @Test
    fun workflowModelOverrideMakesAProfileWithBlankDefaultModelUsable() {
        val profile = AiProviderProfile(
            id = "blank-default",
            displayName = "Local provider",
            baseUrl = "http://127.0.0.1:1234/v1",
            model = "",
            kind = AiProviderKind.OPENAI_COMPATIBLE,
        )
        val effective = profile.withRunOverrides("rewrite-model", null)
        assertTrue(profileProblems("Model", effective) { "" }.isEmpty())
    }
}
