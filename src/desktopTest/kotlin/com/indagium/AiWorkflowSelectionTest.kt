package com.indagium

import com.indagium.ai.LlmModel
import com.indagium.ai.ModelDiscoveryResult
import com.indagium.model.AiProviderKind
import com.indagium.model.AiProviderProfile
import com.indagium.model.AppSettings
import com.indagium.model.WorkflowAiSelection
import com.indagium.ui.normalizeAiWorkflowSelectionForCatalog
import com.indagium.ui.resolveAiWorkflowSelection
import com.indagium.ui.settingsFromJson
import com.indagium.ui.settingsJson
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class AiWorkflowSelectionTest {
    private val profiles = listOf(
        AiProviderProfile("global", "Global", "", "global-model", selected = true),
        AiProviderProfile(
            "preferred", "Preferred", "", "preferred-model", kind = AiProviderKind.CLAUDE_CODE_ACCOUNT, reasoningEffort = "high",
        ),
    )

    @Test
    fun workflowChoicesRoundTripSeparatelyAndResolveToTheirOwnLastSelection() {
        val saved = AppSettings(
            aiProviderProfiles = profiles,
            workflowAiSelections = mapOf(
                "testStepDraft" to WorkflowAiSelection("preferred", "claude-opus", "high", modelWasDiscovered = true),
                "recordingRewrite" to WorkflowAiSelection("global", "local-llama", null),
            ),
        )
        val restored = settingsFromJson(saved.settingsJson())!!

        assertEquals(
            WorkflowAiSelection("preferred", "claude-opus", "high", modelWasDiscovered = true),
            resolveAiWorkflowSelection(restored, "testStepDraft", profiles),
        )
        assertEquals(
            WorkflowAiSelection("global", "local-llama", null),
            resolveAiWorkflowSelection(restored, "recordingRewrite", profiles),
        )
    }

    @Test
    fun removedProfileFallsBackToConfiguredThenGlobalChoiceAndDropsItsOverrides() {
        val saved = AppSettings(
            aiProviderProfiles = profiles,
            workflowAiSelections = mapOf("testRunJudge" to WorkflowAiSelection("removed", "old-model", "max")),
        )

        assertEquals(
            WorkflowAiSelection("preferred"),
            resolveAiWorkflowSelection(saved, "testRunJudge", profiles, fallbackProfileIds = listOf("preferred")),
        )
        val globalFallback = resolveAiWorkflowSelection(saved, "testRunJudge", profiles, fallbackProfileIds = listOf("gone"))
        assertEquals("global", globalFallback.profileId)
        assertNull(globalFallback.modelId)
        assertNull(globalFallback.reasoningEffort)
    }

    @Test
    fun catalogInvalidatesRemovedCatalogValuesButPreservesManuallyEnteredModels() {
        val profile = profiles.last()
        val discovered = WorkflowAiSelection(profile.id, "listed", "high", modelWasDiscovered = true)
        val custom = WorkflowAiSelection(profile.id, "custom-compatible-model", "high")
        val catalog = ModelDiscoveryResult.Available(listOf(LlmModel("listed", reasoningEfforts = listOf("low"))))

        assertEquals(
            WorkflowAiSelection(profile.id, "listed", "low", modelWasDiscovered = true),
            normalizeAiWorkflowSelectionForCatalog(discovered, profile, catalog),
        )
        assertEquals(
            WorkflowAiSelection(profile.id),
            normalizeAiWorkflowSelectionForCatalog(discovered, profile, ModelDiscoveryResult.Available(emptyList())),
        )
        assertEquals(custom, normalizeAiWorkflowSelectionForCatalog(custom, profile, catalog))
        assertEquals(discovered, normalizeAiWorkflowSelectionForCatalog(discovered, profile, ModelDiscoveryResult.Unavailable("offline")))
        assertEquals(
            WorkflowAiSelection(profile.id, "no-effort-model", "", modelWasDiscovered = true),
            normalizeAiWorkflowSelectionForCatalog(
                WorkflowAiSelection(profile.id, "no-effort-model", modelWasDiscovered = true),
                profile,
                ModelDiscoveryResult.Available(listOf(LlmModel("no-effort-model"))),
            ),
        )
    }
}
