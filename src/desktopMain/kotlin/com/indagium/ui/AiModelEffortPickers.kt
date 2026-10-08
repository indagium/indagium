package com.indagium.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.indagium.ai.LlmModel
import com.indagium.ai.ModelDiscoveryResult
import com.indagium.ai.aiProviderFallbackReasoningEfforts
import com.indagium.model.AiProviderProfile
import kotlinx.coroutines.launch

// The model and reasoning-effort pickers every AI-profile choice of the tests surface shares: a lane or the judge of a run
// (TestRunDialog.kt), the "Draft steps with AI" dialog and the recording rewrite (TestsStepAuthoring.kt).

private val PICKER_WIDTH = 190.dp
private val EFFORT_PICKER_WIDTH = 130.dp

/** Finds the models of an AI profile on request, once per profile and dialog; the result feeds the model and effort pickers. */
internal class ModelCatalog {
    private val results = mutableStateMapOf<String, ModelDiscoveryResult?>()
    private val searching = HashSet<String>()

    /** The last result for [profileId]; null while it is being found or was never asked. */
    fun result(profileId: String): ModelDiscoveryResult? = results[profileId]

    /** Finds the models the first time a profile's picker is shown, like the AI sidebar does; later pickers of the same profile reuse it. */
    fun ensure(state: AppState, scope: kotlinx.coroutines.CoroutineScope, profile: AiProviderProfile) {
        if (results[profile.id] == null) find(state, scope, profile)
    }

    fun find(state: AppState, scope: kotlinx.coroutines.CoroutineScope, profile: AiProviderProfile) {
        if (!searching.add(profile.id)) return
        results.remove(profile.id)
        scope.launch {
            val found = runCatching { state.aiSidebarRuntime.discoverModels(profile, state.aiProviderApiKey(profile.id)) }
                .getOrElse { ModelDiscoveryResult.Unavailable(it.message ?: "The models could not be listed.") }
            results[profile.id] = found
            searching.remove(profile.id)
        }
    }
}

/** The reasoning efforts to offer for [model] of [profile]: the catalog's when it lists the model, else the kind's fixed set; empty hides the picker. */
internal fun effortChoicesFor(profile: AiProviderProfile, model: String, discovery: ModelDiscoveryResult?): List<String> {
    val listed: LlmModel? = (discovery as? ModelDiscoveryResult.Available)?.models?.firstOrNull { it.id == model }
    return listed?.reasoningEfforts ?: aiProviderFallbackReasoningEfforts(profile.kind).orEmpty()
}

/** Keep the previous effort only when the newly selected catalog model supports it. Empty suppresses a profile-level effort. */
internal fun effortOverrideForModel(
    profile: AiProviderProfile,
    selectedModel: String?,
    previousEffort: String?,
    discovery: ModelDiscoveryResult?,
): String? {
    if (selectedModel == null) return null
    val listed = (discovery as? ModelDiscoveryResult.Available)?.models?.firstOrNull { it.id == selectedModel } ?: return previousEffort
    val effective = previousEffort ?: profile.reasoningEffort.takeIf(String::isNotBlank)
    if (effective != null && effective in listed.reasoningEfforts) return previousEffort
    if (listed.reasoningEfforts.isNotEmpty()) return listed.reasoningEfforts.first()
    return "".takeIf { profile.reasoningEffort.isNotBlank() }
}

/**
 * A model picker (the one of the AI sidebar: the profile's models when they can be found, a free-text field when they cannot) and,
 * when the model has reasoning levels, an effort picker. They show the profile's own values until the user picks something; [onChange]
 * gets the overrides (null = the profile's own).
 */
@Composable
internal fun ModelAndEffortPickers(
    profile: AiProviderProfile,
    catalog: ModelCatalog,
    model: String?,
    effort: String?,
    modelWasDiscovered: Boolean = false,
    onChange: (model: String?, effort: String?, modelWasDiscovered: Boolean) -> Unit,
) {
    val ui = LocalTestsUi.current
    LaunchedEffect(profile.id) { catalog.ensure(ui.state, ui.scope, profile) }
    LaunchedEffect(profile.id, model, effort, modelWasDiscovered, catalog.result(profile.id)) {
        val normalized = normalizeAiWorkflowSelectionForCatalog(
            com.indagium.model.WorkflowAiSelection(profile.id, model, effort, modelWasDiscovered),
            profile,
            catalog.result(profile.id),
        )
        if (
            normalized.modelId != model || normalized.reasoningEffort != effort ||
            normalized.modelWasDiscovered != modelWasDiscovered
        ) {
            onChange(normalized.modelId, normalized.reasoningEffort, normalized.modelWasDiscovered)
        }
    }
    val shownModel = model ?: profile.model
    val shownEffort = effort ?: profile.reasoningEffort
    val efforts = effortChoicesFor(profile, shownModel, catalog.result(profile.id))
    Column(Modifier.width(PICKER_WIDTH)) {
        AiModelDropdown(
            model = shownModel,
            discovery = catalog.result(profile.id),
            onDiscoverModels = { catalog.find(ui.state, ui.scope, profile) },
            onPickModel = { picked ->
                val override = picked.trim().takeIf { it.isNotEmpty() && it != profile.model }
                val catalogued = override != null &&
                    (catalog.result(profile.id) as? ModelDiscoveryResult.Available)?.models?.any { it.id == override } == true
                onChange(override, effortOverrideForModel(profile, override, effort, catalog.result(profile.id)), catalogued)
            },
        )
    }
    if (efforts.isNotEmpty()) {
        Column(Modifier.width(EFFORT_PICKER_WIDTH)) {
            AiReasoningEffortDropdown(
                efforts = efforts,
                selected = shownEffort,
                onPick = { picked -> onChange(model, picked.takeIf { it != profile.reasoningEffort }, modelWasDiscovered) },
            )
        }
    }
}
