package com.indagium.debug

import com.indagium.testing.model.HookItem
import com.indagium.testing.model.MAX_STEP_CONDITION_CHARS
import com.indagium.testing.model.OnFailure
import com.indagium.testing.model.ScriptParam
import com.indagium.testing.model.ScriptParamType
import com.indagium.testing.model.ScriptPermission
import com.indagium.testing.model.ScriptTarget
import com.indagium.testing.model.SharedStep
import com.indagium.testing.model.StepCheck
import com.indagium.testing.model.StepExample
import com.indagium.testing.model.TestCase
import com.indagium.testing.model.TestScript
import com.indagium.testing.model.TestStep
import com.indagium.testing.model.TestSuite
import com.indagium.testing.model.TestVariable
import com.indagium.testing.model.newStepId
import com.indagium.testing.store.CHECK_TYPE_NAMES
import com.indagium.testing.store.EXAMPLE_TYPE_NAMES
import com.indagium.testing.store.HOOK_TYPE_NAMES
import com.indagium.testing.store.IdAllocator
import com.indagium.testing.store.decodeCheck
import com.indagium.testing.store.decodeExample
import com.indagium.testing.store.decodeHook
import com.indagium.testing.store.decodeParam
import com.indagium.testing.store.decodeVariable
import kotlinx.serialization.json.JsonPrimitive

// Turns the arguments of the test-suite tools into model values. Each `apply*Fields` takes the entity
// to start from (an empty one for create, the current one for update) and returns it with the supplied
// fields changed; unsupplied fields are untouched. Nested checks, examples, hooks, variables and params
// are decoded by the codec the suite files use, after this file has rejected what the codec would
// silently drop (unknown type, mistyped field, missing required text).

private const val ID_RESERVATION_PREFIX = "reserved-"
private val ABSOLUTE_PATH_START = Regex("^([/\\\\]|[A-Za-z]:)")

/** Marks [ids] as taken so a newly supplied entry can never receive one of them. */
private fun IdAllocator.reserve(ids: Iterable<String>) = ids.forEach { next(JsonPrimitive(it), ID_RESERVATION_PREFIX) }

private fun typeOf(item: Map<String, Any?>, label: String, allowed: List<String>): String {
    val type = item["type"] as? String
    if (type == null || type !in allowed) toolArgError("$label.type must be one of ${allowed.joinToString(", ")}.")
    return type
}

private fun requireNonBlank(item: Map<String, Any?>, label: String, key: String) {
    if ((item[key] as? String).isNullOrBlank()) toolArgError("$label.$key is required for type '${item["type"]}'.")
}

// ── Checks and examples ──────────────────────────────────────────────

private val CHECK_STRING_FIELDS = setOf("id", "type", "tag", "regex", "text", "exampleRef", "scriptId", "stdoutContains")
private val CHECK_NUMBER_FIELDS = setOf("withinMs", "forMs", "exitCode")

internal fun parseChecks(items: List<Map<String, Any?>>, ids: IdAllocator, label: String = "checks"): List<StepCheck> =
    items.mapIndexed { index, item ->
        val where = "$label[$index]"
        requireFieldTypes(item, where, strings = CHECK_STRING_FIELDS, numbers = CHECK_NUMBER_FIELDS, objects = setOf("args"))
        when (typeOf(item, where, CHECK_TYPE_NAMES)) {
            "logAppears", "logAbsent" -> requireNonBlank(item, where, "regex")
            "screenJudge", "askJudge" -> requireNonBlank(item, where, "text")
            "scriptResult" -> requireNonBlank(item, where, "scriptId")
        }
        decodeCheck(item.toJsonObject(), ids) ?: toolArgError("$where could not be read.")
    }

private val EXAMPLE_STRING_FIELDS = setOf("id", "type", "caption", "assetPath", "text")

internal fun parseExamples(items: List<Map<String, Any?>>, ids: IdAllocator, label: String = "examples"): List<StepExample> =
    items.mapIndexed { index, item ->
        val where = "$label[$index]"
        requireFieldTypes(item, where, strings = EXAMPLE_STRING_FIELDS)
        when (typeOf(item, where, EXAMPLE_TYPE_NAMES)) {
            "goldenScreenshot" -> {
                requireNonBlank(item, where, "assetPath")
                val path = item["assetPath"] as String
                if (ABSOLUTE_PATH_START.containsMatchIn(path) || path.split('/', '\\').contains("..")) {
                    toolArgError("$where.assetPath must be a relative path inside the suite's asset folder.")
                }
            }
            "referenceLog" -> requireNonBlank(item, where, "text")
        }
        decodeExample(item.toJsonObject(), ids) ?: toolArgError("$where could not be read.")
    }

private fun StepCheck.exampleRefOrNull(): String? = (this as? StepCheck.ScreenJudge)?.exampleRef

// ── Steps ────────────────────────────────────────────────────────────

private const val CHECKS_KEY = "checks"
private const val EXAMPLES_KEY = "examples"

/**
 * [base] with the supplied step fields applied. `checks` and `examples` replace the whole ordered lists.
 * The step id is never read from the arguments.
 */
internal fun applyStepFields(args: ToolArgs, base: TestStep, label: String = "step"): TestStep {
    val optionalFields = parseStepOptionalFields(args, base, label)
    val collections = parseStepCollections(args, base, label)
    val step = base.copy(
        action = readStepAction(args, base, label),
        expected = args.string("expected") ?: base.expected,
        timeoutMs = args.long("timeoutMs") ?: base.timeoutMs,
        retries = args.int("retries") ?: base.retries,
        maxToolCalls = args.int("maxToolCalls") ?: base.maxToolCalls,
        onFailure = args.enum("onFailure", OnFailure.entries) ?: base.onFailure,
        optional = optionalFields.optional,
        condition = optionalFields.condition,
        checks = collections.checks,
        examples = collections.examples,
    )
    validateStepOptionalFields(step, label)
    validateStepExampleReferences(step, label)
    return step
}

private data class StepOptionalFields(val optional: Boolean, val condition: String?)

private data class StepCollections(val checks: List<StepCheck>, val examples: List<StepExample>)

private fun parseStepOptionalFields(args: ToolArgs, base: TestStep, label: String): StepOptionalFields {
    if (args.hasKey("optional") && args.map["optional"] !is Boolean) toolArgError("$label.optional must be a boolean.")
    if (args.hasKey("condition") && args.map["condition"] != null && args.map["condition"] !is String) {
        toolArgError("$label.condition must be a string or null.")
    }
    val requestedOptional = args.bool("optional")
    val optional = requestedOptional ?: base.optional
    val condition = when {
        args.hasKey("condition") -> args.string("condition")?.trim()?.takeIf(String::isNotEmpty)
        requestedOptional == false -> null
        else -> base.condition
    }
    val fields = StepOptionalFields(optional, condition)
    validateStepOptionalFields(fields, label)
    return fields
}

private fun parseStepCollections(args: ToolArgs, base: TestStep, label: String): StepCollections {
    val ids = IdAllocator()
    val examples = args.objects(EXAMPLES_KEY)?.let { parseExamples(it, ids, "$label.$EXAMPLES_KEY") } ?: base.examples.also {
        ids.reserve(it.map { example -> example.id })
    }
    val checks = args.objects(CHECKS_KEY)?.let { parseChecks(it, ids, "$label.$CHECKS_KEY") } ?: base.checks.also {
        ids.reserve(it.map { check -> check.id })
    }
    return StepCollections(checks, examples)
}

private fun readStepAction(args: ToolArgs, base: TestStep, label: String): String =
    args.string("action")?.also { if (it.isBlank()) toolArgError("$label.action must not be blank.") } ?: base.action

private fun validateStepOptionalFields(step: TestStep, label: String) =
    validateStepOptionalFields(StepOptionalFields(step.optional, step.condition), label)

private fun validateStepOptionalFields(fields: StepOptionalFields, label: String) {
    if (fields.optional && fields.condition.isNullOrBlank()) toolArgError("$label.condition is required for an optional step.")
    if (!fields.optional && !fields.condition.isNullOrBlank()) toolArgError("$label.condition requires optional=true.")
    if (fields.condition != null && fields.condition.length > MAX_STEP_CONDITION_CHARS) {
        toolArgError("$label.condition may not exceed $MAX_STEP_CONDITION_CHARS characters.")
    }
}

private fun validateStepExampleReferences(step: TestStep, label: String) {
    val dangling = step.checks.mapNotNull { it.exampleRefOrNull() }.firstOrNull { ref -> step.examples.none { it.id == ref } }
    if (dangling != null) toolArgError("$label: a screenJudge check's exampleRef '$dangling' does not match an example of this step.")
}

/** A brand-new step (fresh id) from one element of a `steps` array. */
internal fun parseNewStep(item: Map<String, Any?>, label: String): TestStep {
    val step = applyStepFields(ToolArgs(item), TestStep(newStepId(), action = ""), label)
    if (step.action.isBlank()) toolArgError("$label.action is required.")
    return step
}

internal fun parseNewSteps(args: ToolArgs, key: String): List<TestStep>? =
    args.objects(key)?.mapIndexed { index, item -> parseNewStep(item, "$key[$index]") }

// ── Cases ────────────────────────────────────────────────────────────

/** [base] with the supplied case fields applied (`description` is the case's goal); steps are never changed here. */
internal fun applyCaseFields(args: ToolArgs, base: TestCase): TestCase {
    val ids = IdAllocator()
    val setupItems = args.objects("setup")
    val teardownItems = args.objects("teardown")
    if (setupItems == null) ids.reserve(base.setup.map { it.id })
    if (teardownItems == null) ids.reserve(base.teardown.map { it.id })
    val allowedTools = when {
        args.bool("allowAllTools") == true -> null
        args.has("allowedTools") -> args.strings("allowedTools")?.toCollection(LinkedHashSet())
        else -> base.allowedTools
    }
    return base.copy(
        name = args.string("name") ?: base.name,
        description = args.string("description") ?: base.description,
        instructions = args.string("instructions") ?: base.instructions,
        preconditions = args.string("preconditions") ?: base.preconditions,
        setup = setupItems?.let { parseHooks(it, ids, "setup") } ?: base.setup,
        teardown = teardownItems?.let { parseHooks(it, ids, "teardown") } ?: base.teardown,
        allowedTools = allowedTools,
    )
}

// ── Suites ───────────────────────────────────────────────────────────

private val HOOK_STRING_FIELDS = setOf("id", "type", "scriptId", "sharedStepId")

private fun parseHooks(items: List<Map<String, Any?>>, ids: IdAllocator, label: String): List<HookItem> =
    items.mapIndexed { index, item ->
        val where = "$label[$index]"
        requireFieldTypes(item, where, strings = HOOK_STRING_FIELDS, objects = setOf("args"))
        when (typeOf(item, where, HOOK_TYPE_NAMES)) {
            "script" -> requireNonBlank(item, where, "scriptId")
            "shared" -> requireNonBlank(item, where, "sharedStepId")
        }
        decodeHook(item.toJsonObject(), ids) ?: toolArgError("$where could not be read.")
    }

private fun parseVariables(items: List<Map<String, Any?>>, ids: IdAllocator): List<TestVariable> {
    val variables = items.mapIndexed { index, item ->
        val where = "variables[$index]"
        requireFieldTypes(item, where, strings = setOf("id", "name", "value", "description"))
        requireNonBlank(item, where, "name")
        decodeVariable(item.toJsonObject(), ids)
    }
    if (variables.map { it.name }.distinct().size != variables.size) toolArgError("Variable names must be unique within a suite.")
    return variables
}

/** [base] with the supplied suite fields applied; cases are never changed here. */
internal fun applySuiteFields(args: ToolArgs, base: TestSuite): TestSuite {
    val ids = IdAllocator()
    val setupItems = args.objects("setup")
    val teardownItems = args.objects("teardown")
    val variableItems = args.objects("variables")
    // Ids that stay (the groups not being replaced) are reserved first so a supplied id can't duplicate one.
    if (setupItems == null) ids.reserve(base.setup.map { it.id })
    if (teardownItems == null) ids.reserve(base.teardown.map { it.id })
    if (variableItems == null) ids.reserve(base.variables.map { it.id })
    return base.copy(
        name = args.string("name") ?: base.name,
        description = args.string("description") ?: base.description,
        instructions = args.string("instructions") ?: base.instructions,
        targetPackage = args.string("targetPackage") ?: base.targetPackage,
        deviceProfileHint = args.string("deviceProfileHint") ?: base.deviceProfileHint,
        tags = args.strings("tags") ?: base.tags,
        setup = setupItems?.let { parseHooks(it, ids, "setup") } ?: base.setup,
        teardown = teardownItems?.let { parseHooks(it, ids, "teardown") } ?: base.teardown,
        variables = variableItems?.let { parseVariables(it, ids) } ?: base.variables,
    )
}

// ── Scripts ──────────────────────────────────────────────────────────

private fun parseParams(items: List<Map<String, Any?>>): List<ScriptParam> =
    items.mapIndexed { index, item ->
        val where = "params[$index]"
        requireFieldTypes(item, where, strings = setOf("name", "type", "description"), booleans = setOf("required"))
        requireNonBlank(item, where, "name")
        // A default may be written as a number or boolean for an INT/BOOL parameter; it is stored as text.
        if (item["defaultValue"] is Map<*, *> || item["defaultValue"] is List<*>) toolArgError("$where.defaultValue must be a single value.")
        val type = (item["type"] as? String)?.uppercase()
        if (type != null && ScriptParamType.entries.none { it.name == type }) {
            toolArgError("$where.type must be one of ${ScriptParamType.entries.joinToString(", ") { it.name }}.")
        }
        val normalized = if (type != null) item + ("type" to type) else item
        decodeParam(normalized.toJsonObject()) ?: toolArgError("$where could not be read.")
    }

/** [base] with the supplied script fields applied. An empty `workingDir` clears it. */
internal fun applyScriptFields(args: ToolArgs, base: TestScript): TestScript = base.copy(
    toolName = args.string("toolName") ?: base.toolName,
    description = args.string("description") ?: base.description,
    params = args.objects("params")?.let { parseParams(it) } ?: base.params,
    commandTemplate = args.string("commandTemplate") ?: base.commandTemplate,
    target = args.enum("target", ScriptTarget.entries) ?: base.target,
    timeoutMs = args.long("timeoutMs") ?: base.timeoutMs,
    outputCapBytes = args.int("outputCapBytes") ?: base.outputCapBytes,
    workingDir = if (args.has("workingDir")) args.string("workingDir")?.takeIf { it.isNotBlank() } else base.workingDir,
    permission = args.enum("permission", ScriptPermission.entries) ?: base.permission,
)

// ── Shared steps ─────────────────────────────────────────────────────

/** [base] with the supplied shared-step fields applied; `steps` replaces the whole list with brand-new steps. */
internal fun applySharedStepFields(args: ToolArgs, base: SharedStep): SharedStep = base.copy(
    name = args.string("name") ?: base.name,
    description = args.string("description") ?: base.description,
    steps = parseNewSteps(args, "steps") ?: base.steps,
)
