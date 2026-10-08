package com.indagium.testing.store

import com.indagium.debug.requireValidAndroidPackageName
import com.indagium.testing.model.MAX_STEP_CONDITION_CHARS
import com.indagium.testing.model.MAX_TAG_CHARS
import com.indagium.testing.model.ScriptParam
import com.indagium.testing.model.SharedStep
import com.indagium.testing.model.StepCheck
import com.indagium.testing.model.TestCase
import com.indagium.testing.model.TestScript
import com.indagium.testing.model.TestStep
import com.indagium.testing.model.TestSuite
import com.indagium.testing.model.isSafeId
import com.indagium.testing.model.scriptParamNameError
import com.indagium.testing.model.scriptToolNameError

// Write-time validation for [TestLibraryStore]. Each function returns the first problem as a message
// that is safe to show to the user, or null when the value is acceptable. Reads never validate (a file
// is decoded tolerantly), so a hand-edited or imported library is never rejected, only new writes are.

const val MAX_NAME_CHARS = 200
const val MAX_STEP_TIMEOUT_MS = 60L * 60L * 1000L
const val MAX_STEP_RETRIES = 10
const val MIN_STEP_MAX_TOOL_CALLS = 1
const val MAX_STEP_MAX_TOOL_CALLS = 100
const val MAX_SCRIPT_TIMEOUT_MS = 60L * 60L * 1000L
const val MAX_SCRIPT_OUTPUT_CAP_BYTES = 8 * 1024 * 1024

internal fun validateName(label: String, name: String): String? = when {
    name.isBlank() -> "$label name must not be blank."
    name.length > MAX_NAME_CHARS -> "$label name is too long (max $MAX_NAME_CHARS characters)."
    else -> null
}

/** The suite's name, its target package (reusing the device tools' package-id rule) and its tags. */
internal fun validateSuite(suite: TestSuite): String? {
    validateName("Suite", suite.name)?.let { return it }
    if (suite.targetPackage.isNotBlank() && runCatching { requireValidAndroidPackageName(suite.targetPackage) }.isFailure) {
        return "Target package must be a reverse-domain Android package id, e.g. com.example.app."
    }
    return suite.tags.firstOrNull { it.length > MAX_TAG_CHARS }?.let { "Tag '$it' is too long (max $MAX_TAG_CHARS characters)." }
}

internal fun validateEntityId(label: String, id: String): String? =
    if (isSafeId(id)) null else "$label id '$id' is not a valid id."

private fun validateCheck(check: StepCheck): String? = when (check) {
    is StepCheck.LogAppears -> validateRegex(check.regex) ?: validateDuration("withinMs", check.withinMs)
    is StepCheck.LogAbsent -> validateRegex(check.regex) ?: validateDuration("forMs", check.forMs)
    is StepCheck.ScriptResult -> if (check.scriptId.isBlank()) "A script-result check needs a script." else null
    is StepCheck.ScreenJudge, is StepCheck.AskJudge -> null
}

private fun validateRegex(regex: String): String? = when {
    regex.isBlank() -> "A log check needs a regular expression."
    runCatching { Regex(regex) }.isFailure -> "'$regex' is not a valid regular expression."
    else -> null
}

private fun validateDuration(label: String, value: Long): String? =
    if (value in 1..MAX_STEP_TIMEOUT_MS) null else "$label must be between 1 and $MAX_STEP_TIMEOUT_MS milliseconds."

internal fun validateStep(step: TestStep): String? =
    validateStepSettings(step) ?: validateStepCondition(step) ?: step.checks.firstNotNullOfOrNull { validateCheck(it) }

private fun validateStepSettings(step: TestStep): String? = when {
    step.timeoutMs !in 1..MAX_STEP_TIMEOUT_MS -> "Step timeout must be between 1 and $MAX_STEP_TIMEOUT_MS milliseconds."
    step.retries !in 0..MAX_STEP_RETRIES -> "Step retries must be between 0 and $MAX_STEP_RETRIES."
    step.maxToolCalls !in MIN_STEP_MAX_TOOL_CALLS..MAX_STEP_MAX_TOOL_CALLS ->
        "Step max tool calls must be between $MIN_STEP_MAX_TOOL_CALLS and $MAX_STEP_MAX_TOOL_CALLS."
    step.checks.map { it.id }.distinct().size != step.checks.size -> "Check ids must be unique within a step."
    step.examples.map { it.id }.distinct().size != step.examples.size -> "Example ids must be unique within a step."
    else -> null
}

private fun validateStepCondition(step: TestStep): String? = when {
    step.optional && step.condition.isNullOrBlank() -> "An optional step needs a condition that explains when to perform it."
    !step.optional && !step.condition.isNullOrBlank() -> "A step condition can be used only on an optional step."
    step.condition != null && step.condition.length > MAX_STEP_CONDITION_CHARS ->
        "Step condition is longer than $MAX_STEP_CONDITION_CHARS characters."
    else -> null
}

internal fun validateCase(case: TestCase): String? =
    validateName("Case", case.name) ?: case.steps.firstNotNullOfOrNull { validateStep(it) }

internal fun validateSharedStep(shared: SharedStep): String? =
    validateName("Shared step", shared.name) ?: shared.steps.firstNotNullOfOrNull { validateStep(it) }

private fun validateParams(params: List<ScriptParam>): String? {
    if (params.map { it.name }.distinct().size != params.size) return "Parameter names must be unique within a script."
    return params.firstNotNullOfOrNull { scriptParamNameError(it.name) }
}

/** [others] are the library's other scripts, which the tool name must not collide with. */
internal fun validateScript(script: TestScript, others: List<TestScript>): String? = when {
    scriptToolNameError(script.toolName) != null -> scriptToolNameError(script.toolName)
    others.any { it.toolName == script.toolName } -> "A script with the tool name '${script.toolName}' already exists."
    script.commandTemplate.isBlank() -> "A script needs a command."
    script.timeoutMs !in 1..MAX_SCRIPT_TIMEOUT_MS -> "Script timeout must be between 1 and $MAX_SCRIPT_TIMEOUT_MS milliseconds."
    script.outputCapBytes !in 1..MAX_SCRIPT_OUTPUT_CAP_BYTES -> "Script output cap must be between 1 and $MAX_SCRIPT_OUTPUT_CAP_BYTES bytes."
    else -> validateParams(script.params)
}
