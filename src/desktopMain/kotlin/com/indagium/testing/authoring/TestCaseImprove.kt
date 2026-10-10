package com.indagium.testing.authoring

import com.indagium.model.AiUsageStats
import com.indagium.testing.model.MAX_STEP_CONDITION_CHARS
import com.indagium.testing.model.StepCheck
import com.indagium.testing.model.StepKind
import com.indagium.testing.model.TestScript
import com.indagium.testing.model.TestStep
import com.indagium.testing.model.withFreshId
import com.indagium.testing.store.validateStep
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull

// What an AI improvement of a SAVED case is made of: the proposal (one entry per existing step), the rules a rewritten step must keep and
// the strict reading of the model's JSON answer. Nothing here talks to a model, a run or the library; the service
// (TestCaseImproveService.kt) does, and the words of the request are in TestCaseImproveBrief.kt.

/** A case of more steps than this is not improved in one request. */
internal const val MAX_IMPROVE_STEPS = 40

/** The longest note the person may add to a request. */
internal const val MAX_IMPROVE_NOTE_CHARS = 1_000

/** The longest reason the model may give for one step. */
internal const val MAX_IMPROVE_REASON_CHARS = 500

/** The reason of a step the model leaves as it is. */
internal const val UNCHANGED_REASON = "unchanged"

private const val STEPS_KEY = "steps"
private const val STEP_ID_KEY = "stepId"

/** Keys a step of the answer must not carry (or must leave empty): an improvement rewrites words, never who performs a step or how. */
private val FIXED_STEP_KEYS = listOf("script", "scriptId", "args", "manualCompletion", "examples", "timeoutMs", "retries", "maxToolCalls", "onFailure")

/** What an improvement of the saved steps of a case proposes. [steps] has one entry per existing step, in order. */
internal data class TestCaseImproveProposal(
    val id: String,
    val suiteId: String,
    val caseId: String,
    val profileId: String,
    val runId: String?,
    val steps: List<ImproveStepProposal>,
    val evidence: ImproveEvidenceSummary,
    val usage: AiUsageStats? = null,
)

/**
 * The proposal for one existing step: [previous] is the step as it was saved, the other fields are what it would become. [checks] is the
 * whole check list of the step after the change (the checks the model cannot change are in it, untouched). [reason] is the model's.
 */
internal data class ImproveStepProposal(
    val stepId: String,
    val number: Int,
    val previous: TestStep,
    val action: String,
    val expected: String,
    val optional: Boolean,
    val condition: String?,
    val checks: List<StepCheck>,
    val reason: String,
) {
    /** The saved step with this proposal applied. */
    val proposed: TestStep
        get() = previous.copy(action = action, expected = expected, optional = optional, condition = condition, checks = checks)

    val changed: Boolean get() = proposed != previous

    /** The review warning when the expected result of an AI step looks like it depends on content that changes between runs. */
    val incidentalWarning: String?
        get() = incidentalWarningOf(previous.kind, expected, optional)
}

/** The incidental-content warning for an expected result; only the steps an AI agent performs have one. */
internal fun incidentalWarningOf(kind: StepKind, expected: String, optional: Boolean): String? =
    if (kind == StepKind.AGENT) incidentalExpectedWarning(expected, optional) else null

/** What the person (or an MCP client) hands back for one step of a preview: the fields they may edit. */
internal data class ImproveStepEdit(
    val stepId: String,
    val action: String,
    val expected: String,
    val optional: Boolean,
    val condition: String?,
)

/** The edit that leaves [proposal] as proposed. */
internal fun ImproveStepProposal.asEdit() = ImproveStepEdit(stepId, action, expected, optional, condition)

/** Whether a check is one the model may change: an AI judge question or a log check. A script, device-state or example-bound check is never touched. */
internal fun isImprovableCheck(check: StepCheck): Boolean = when (check) {
    is StepCheck.AskJudge, is StepCheck.LogAppears, is StepCheck.LogAbsent -> true
    is StepCheck.ScreenJudge -> check.exampleRef == null
    is StepCheck.ScriptResult, is StepCheck.DeviceState -> false
}

/**
 * The checks of a step once the model replaced its improvable ones with [proposed]: the checks it cannot change stay (in their order),
 * the proposed ones follow. A proposed check keeps its id only when it is the same kind of check as a saved one with that id.
 */
internal fun mergeProposedChecks(saved: List<StepCheck>, proposed: List<StepCheck>): List<StepCheck> {
    val savedImprovable = saved.filter(::isImprovableCheck)
    if (proposed == savedImprovable) return saved
    val kept = saved.filterNot(::isImprovableCheck)
    val savedById = savedImprovable.associateBy { it.id }
    val usedIds = kept.mapTo(HashSet()) { it.id }
    val replaced = proposed.map { check ->
        val sameKind = savedById[check.id]?.let { it::class == check::class } == true
        val result = if (sameKind && check.id !in usedIds) check else check.withFreshId(emptyMap())
        usedIds += result.id
        result
    }
    return kept + replaced
}

/**
 * [old] with the improved fields, or an [IllegalArgumentException] naming what is wrong. Text equal to the saved text (ignoring edges)
 * stays exactly as saved. Only an AI step may be optional or have its checks changed; an expected result may be blank only on an
 * optional step or where the saved one is blank too. A step that comes out unchanged is not re-validated (an old problem is not the model's).
 */
@Suppress("LongParameterList", "ThrowsCount") // One rule set for the model's answer and for the reviewed edit: every field is checked here.
internal fun improvedStepOf(
    old: TestStep,
    action: String,
    expected: String,
    optional: Boolean,
    condition: String?,
    checks: List<StepCheck>,
    scripts: List<TestScript>,
): TestStep {
    val newAction = action.trim().let { if (it == old.action.trim()) old.action else it }
    val newExpected = expected.trim().let { if (it == old.expected.trim()) old.expected else it }
    val newCondition = condition?.trim()?.takeIf(String::isNotEmpty)?.let { if (it == old.condition?.trim()) old.condition else it }
    require(newAction.isNotBlank()) { "action must not be blank." }
    require(newAction.length <= MAX_FIXED_STEP_CHARS && newExpected.length <= MAX_FIXED_STEP_CHARS) {
        "action and expected are limited to $MAX_FIXED_STEP_CHARS characters."
    }
    require(newCondition == null || newCondition.length <= MAX_STEP_CONDITION_CHARS) { "condition is longer than $MAX_STEP_CONDITION_CHARS characters." }
    if (old.kind == StepKind.AGENT) {
        require(optional || newCondition == null) { "condition is only valid when optional is true." }
        require(!optional || newCondition != null) { "an optional step needs a condition that says when to perform it." }
        require(newExpected.isNotBlank() || optional || old.expected.isBlank()) {
            "expected may be blank only for a conditional optional step (or where the saved expected result is already blank)."
        }
    } else {
        val kind = old.kind.name.lowercase()
        require(!optional && newCondition == null) { "a $kind step cannot be optional or conditional; only its action and expected text can change." }
        require(checks == old.checks) { "the checks of a $kind step cannot change; only its action and expected text can." }
    }
    val step = old.copy(action = newAction, expected = newExpected, optional = optional, condition = newCondition, checks = checks)
    if (step != old) validateStep(step, scripts)?.let { throw IllegalArgumentException(it) }
    return step
}

/**
 * The model's answer for [steps] as proposals. Throws [IllegalArgumentException] naming the first problem: the answer is shown whole or
 * not at all. It must hold exactly one entry per existing step, in order, each naming its step id; no step is added, removed, reordered,
 * or given another kind, script or setting.
 */
internal fun parseImproveResponse(response: String, steps: List<TestStep>, scripts: List<TestScript>): List<ImproveStepProposal> {
    val root = parseJsonObject(response)
    val rows = root[STEPS_KEY] as? JsonArray ?: throw IllegalArgumentException("The provider response must include a 'steps' array.")
    require(rows.size == steps.size) {
        "The provider must return exactly one entry for each of the ${steps.size} existing step(s), in order (it returned ${rows.size}); " +
            "steps cannot be added, removed or reordered."
    }
    return rows.mapIndexed { index, row ->
        val obj = row as? JsonObject ?: throw IllegalArgumentException("$STEPS_KEY[$index] must be an object.")
        parseImprovedStep(obj, index, steps[index], scripts)
    }
}

private fun parseImprovedStep(obj: JsonObject, index: Int, old: TestStep, scripts: List<TestScript>): ImproveStepProposal {
    val where = "$STEPS_KEY[$index]"
    val id = (obj[STEP_ID_KEY] as? JsonPrimitive)?.takeIf { it.isString }?.content?.trim()
    require(id == old.id) { "$where.$STEP_ID_KEY must be \"${old.id}\" (existing step ${index + 1}); steps cannot be added, removed or reordered." }
    val sent = FIXED_STEP_KEYS.filter { key -> !isEmptyAnswer(obj[key]) }
    require(sent.isEmpty()) {
        "$where: ${sent.joinToString(", ")} cannot be changed by an improvement (only action, expected, optional, condition and checks)."
    }
    val kind = obj["kind"]
    require(isEmptyAnswer(kind) || (kind as? JsonPrimitive)?.contentOrNull?.trim().equals(old.kind.name, ignoreCase = true)) {
        "$where.kind cannot change in an improvement (this is a ${old.kind.name.lowercase()} step)."
    }
    val action = textField(obj, "action", where)
    val expected = textField(obj, "expected", where, allowBlank = true)
    val optional = optionalFlag(obj["optional"], where) ?: old.optional
    val condition = if (obj.containsKey("condition")) {
        optionalTextField(obj, "condition", where, MAX_STEP_CONDITION_CHARS)
    } else {
        old.condition.takeIf { optional }
    }
    val reason = optionalTextField(obj, "reason", where, MAX_IMPROVE_REASON_CHARS)
        ?: throw IllegalArgumentException("$where.reason is required (write \"$UNCHANGED_REASON\" when the step stays as it is).")
    val checks = checksOfImprovement(obj["checks"], where, old)
    val step = try {
        improvedStepOf(old, action, expected, optional, condition, checks, scripts)
    } catch (invalid: IllegalArgumentException) {
        throw IllegalArgumentException("$where: ${invalid.message}", invalid)
    }
    return ImproveStepProposal(old.id, index + 1, old, step.action, step.expected, step.optional, step.condition, step.checks, reason)
}

private fun optionalFlag(element: JsonElement?, where: String): Boolean? {
    if (element == null || element is JsonNull) return null
    val primitive = element as? JsonPrimitive
    require(primitive != null && !primitive.isString && primitive.booleanOrNull != null) { "$where.optional must be a boolean when provided." }
    return primitive.booleanOrNull
}

/** The step's checks after the answer's `checks`: absent or null keeps them; an array replaces the improvable ones (an AI step only). */
private fun checksOfImprovement(element: JsonElement?, where: String, old: TestStep): List<StepCheck> {
    if (element == null || element is JsonNull) return old.checks
    if (old.kind != StepKind.AGENT) {
        require(isEmptyAnswer(element)) { "$where.checks cannot change on a ${old.kind.name.lowercase()} step; only its action and expected text can." }
        return old.checks
    }
    val proposed = checksOf(element, where)
    require(proposed.all(::isImprovableCheck)) {
        "$where.checks may only hold askJudge, screenJudge (without an example), logAppears and logAbsent checks; the step's other checks are kept as they are."
    }
    return mergeProposedChecks(old.checks, proposed)
}
