package com.indagium.ui

import com.indagium.ai.ModelDiscoveryResult
import com.indagium.model.AiProviderProfile
import com.indagium.model.AppSettings
import com.indagium.model.WorkflowAiSelection
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put

/** Stable keys keep selectors in one authoring workflow independent from every other workflow. */
internal object AiWorkflow {
    const val TEST_STEP_DRAFT = "testStepDraft"
    const val RECORDING_REWRITE = "recordingRewrite"
    const val TEST_RUN_LANE = "testRunLane"
    const val TEST_RUN_JUDGE = "testRunJudge"
}

/** Resolve a stored selection against current profiles, falling back through configured choices safely. */
internal fun resolveAiWorkflowSelection(
    settings: AppSettings,
    workflow: String,
    profiles: List<AiProviderProfile>,
    fallbackProfileIds: List<String?> = emptyList(),
): WorkflowAiSelection {
    val remembered = settings.workflowAiSelections[workflow]
    val chosenId = remembered?.profileId?.takeIf { id -> profiles.any { it.id == id } }
        ?: fallbackProfileIds.firstNotNullOfOrNull { candidate -> candidate?.takeIf { id -> profiles.any { it.id == id } } }
        ?: profiles.firstOrNull { it.selected }?.id
        ?: profiles.firstOrNull()?.id
    val restoreOverrides = chosenId != null && remembered?.profileId == chosenId
    return WorkflowAiSelection(
        profileId = chosenId,
        modelId = remembered?.modelId?.trim()?.takeIf { restoreOverrides && it.isNotEmpty() },
        reasoningEffort = remembered?.reasoningEffort?.trim()?.takeIf { restoreOverrides },
        modelWasDiscovered = remembered?.let { restoreOverrides && it.modelWasDiscovered } ?: false,
    )
}

/** Clear only catalog-origin values that a fresh authoritative catalog disproves; manually entered ids remain usable. */
internal fun normalizeAiWorkflowSelectionForCatalog(
    selection: WorkflowAiSelection,
    profile: AiProviderProfile,
    discovery: ModelDiscoveryResult?,
): WorkflowAiSelection {
    if (selection.profileId != profile.id || !selection.modelWasDiscovered || selection.modelId == null) return selection
    val models = (discovery as? ModelDiscoveryResult.Available)?.models ?: return selection
    val listed = models.firstOrNull { it.id == selection.modelId } ?: return selection.copy(
        modelId = null,
        reasoningEffort = null,
        modelWasDiscovered = false,
    )
    val savedEffort = selection.reasoningEffort
    val effectiveEffort = savedEffort ?: profile.reasoningEffort.takeIf(String::isNotBlank)
    val effort = when {
        savedEffort == "" -> ""
        effectiveEffort != null && effectiveEffort in listed.reasoningEfforts -> savedEffort
        listed.reasoningEfforts.isNotEmpty() -> listed.reasoningEfforts.first()
        profile.reasoningEffort.isNotBlank() -> ""
        else -> null
    }
    return selection.copy(reasoningEffort = effort)
}

/** Persist one workflow's last choice without changing the global profile or any other workflow slot. */
internal fun AppState.rememberAiWorkflowSelection(workflow: String, selection: WorkflowAiSelection) {
    updateSettings { settings ->
        settings.copy(
            workflowAiSelections = settings.workflowAiSelections + (workflow to selection.copy(
                profileId = selection.profileId?.trim()?.takeIf(String::isNotEmpty),
                modelId = selection.modelId?.trim()?.takeIf(String::isNotEmpty),
                reasoningEffort = selection.reasoningEffort?.trim(),
                modelWasDiscovered = selection.modelWasDiscovered,
            )),
        )
    }
}

/** Settings JSON codec kept beside the workflow model so the autosave codec stays below its function-count limit. */
internal fun workflowAiSelectionsJson(selections: Map<String, WorkflowAiSelection>) = buildJsonObject {
    selections.toSortedMap().forEach { (workflow, selection) ->
        if (workflow.isNotBlank()) {
            put(
                workflow,
                buildJsonObject {
                    selection.profileId?.let { put("profileId", it) }
                    selection.modelId?.let { put("modelId", it) }
                    selection.reasoningEffort?.let { put("reasoningEffort", it) }
                    put("modelWasDiscovered", selection.modelWasDiscovered)
                },
            )
        }
    }
}

internal fun JsonObject.workflowAiSelectionsFromJson(): Map<String, WorkflowAiSelection> =
    (this["workflowAiSelections"] as? JsonObject)?.mapNotNull { (workflow, raw) ->
        val value = raw as? JsonObject ?: return@mapNotNull null
        if (workflow.isBlank()) return@mapNotNull null
        workflow to WorkflowAiSelection(
            profileId = value.workflowStringOrNull("profileId")?.takeIf(String::isNotBlank),
            modelId = value.workflowStringOrNull("modelId")?.takeIf(String::isNotBlank),
            // Empty effort requests the selected model's default rather than inheriting profile effort.
            reasoningEffort = value.workflowStringOrNull("reasoningEffort"),
            modelWasDiscovered = value.workflowBoolean("modelWasDiscovered", false),
        )
    }?.toMap().orEmpty()

private fun JsonObject.workflowStringOrNull(key: String): String? = this[key]?.jsonPrimitive?.contentOrNull

private fun JsonObject.workflowBoolean(key: String, default: Boolean): Boolean = this[key]?.jsonPrimitive?.booleanOrNull ?: default
