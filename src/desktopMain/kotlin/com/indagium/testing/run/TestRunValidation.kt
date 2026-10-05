package com.indagium.testing.run

import com.indagium.ai.isLoopbackHost
import com.indagium.ai.validateAiProviderProfile
import com.indagium.edition.EditionLimits
import com.indagium.model.AiProviderProfile
import com.indagium.testing.limits.LimitDecision
import com.indagium.testing.limits.LimitOperation
import com.indagium.testing.limits.decide
import com.indagium.testing.model.ALLOWED_RUN_REPEATS
import com.indagium.testing.model.LaneKind
import com.indagium.testing.model.MAX_CASE_TOOL_CALL_LIMIT
import com.indagium.testing.model.MIN_CASE_TOOL_CALL_LIMIT
import com.indagium.testing.model.RunConfig
import com.indagium.testing.model.TestLibrary
import com.indagium.testing.model.TestSuite
import java.net.URI

// Checks a RunConfig against the library, the edition limits and the AI profiles BEFORE anything starts. Every
// problem is returned as data (a list of messages, plus the limit decision when the edition refused), never thrown, so
// the UI and the MCP tool can show them all at once. Device availability is checked by the coordinator (it needs adb).

internal data class RunValidation(
    val suite: TestSuite?,
    val plan: CasePlan,
    val errors: List<String>,
    val warnings: List<String>,
    val limit: LimitDecision.Refused?,
)

/** Whether a missing API key is a problem for [profile]: not for account agents and not for a local (loopback) endpoint. */
internal fun profileNeedsApiKey(profile: AiProviderProfile): Boolean {
    if (!profile.kind.usesApiKey) return false
    val host = runCatching { URI(profile.baseUrl.trim()).host }.getOrNull()?.trim('[', ']')?.lowercase()
    return host == null || !isLoopbackHost(host)
}

private fun laneProblems(config: RunConfig, profiles: List<AiProviderProfile>, apiKey: (String) -> String): List<String> {
    val errors = ArrayList<String>()
    if (config.lanes.isEmpty()) errors += "A run needs at least one lane."
    val seenIds = HashSet<String>()
    for ((position, lane) in config.lanes.withIndex()) {
        val label = "Lane ${position + 1}"
        if (!seenIds.add(lane.id)) errors += "$label has the same id as another lane."
        if (lane.deviceSerial.isBlank()) errors += "$label needs a device."
        if (lane.kind == LaneKind.EXTERNAL) continue
        val profile = profiles.profileOrNull(lane.profileId)
        if (profile == null) {
            errors += "$label: the AI profile '${lane.profileId}' does not exist."
            continue
        }
        val validation = validateAiProviderProfile(profile)
        when {
            !validation.isValid -> errors += "$label: ${validation.problem!!.message}"
            profile.kind.usesHttpEndpoint && profile.model.isBlank() -> errors += "$label: choose a model for the profile '${profile.displayName}'."
            profileNeedsApiKey(profile) && apiKey(profile.id).isBlank() ->
                errors += "$label: the profile '${profile.displayName}' needs an API key (enter it in Settings > AI providers)."
        }
    }
    return errors
}

@Suppress("LongParameterList")
internal fun validateRun(
    config: RunConfig,
    library: TestLibrary,
    limits: EditionLimits,
    profiles: List<AiProviderProfile>,
    apiKey: (String) -> String,
): RunValidation {
    val errors = ArrayList<String>()
    val warnings = ArrayList<String>()
    var refusal: LimitDecision.Refused? = null
    val suite = library.suite(config.suiteId)
    if (suite == null) errors += "Suite '${config.suiteId}' was not found."
    if (config.repeat !in ALLOWED_RUN_REPEATS) errors += "repeat must be one of ${ALLOWED_RUN_REPEATS.joinToString(", ")}."
    if (config.caseToolCallLimit !in MIN_CASE_TOOL_CALL_LIMIT..MAX_CASE_TOOL_CALL_LIMIT) {
        errors += "caseToolCallLimit must be between $MIN_CASE_TOOL_CALL_LIMIT and $MAX_CASE_TOOL_CALL_LIMIT."
    }
    if (config.confirmationTimeoutMs <= 0) errors += "The confirmation timeout must be positive."
    var plan = CasePlan(emptyList(), emptyList())
    if (suite != null) {
        when (val decision = decide(library, LimitOperation.RunSuite(suite.id), limits)) {
            is LimitDecision.Refused -> {
                refusal = decision
                errors += decision.message
            }
            is LimitDecision.AllowedWithWarning -> warnings += decision.warning
            LimitDecision.Allowed -> Unit
        }
        val unknown = config.caseIds.orEmpty().filter { id -> suite.cases.none { it.id == id } }
        if (unknown.isNotEmpty()) errors += "Unknown case id(s): ${unknown.joinToString(", ")}."
        plan = planCases(library, suite.id, config.caseIds, limits)
        plan.locked.forEach { (case, _) -> warnings += "Case '${case.name}' is locked by the edition limit and will not run." }
        if (refusal == null && unknown.isEmpty() && plan.cases.isEmpty()) errors += "There is no runnable case to run."
    }
    errors += laneProblems(config, profiles, apiKey)
    return RunValidation(suite, plan, errors, warnings, refusal)
}
