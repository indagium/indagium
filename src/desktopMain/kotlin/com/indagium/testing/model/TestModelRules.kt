package com.indagium.testing.model

// Pure helpers over the test model: reordering, name validators and deep copies with fresh ids.

const val MAX_SCRIPT_TOOL_NAME_CHARS = 41
const val MAX_ID_CHARS = 128
const val COPY_NAME_SUFFIX = " (copy)"
const val MAX_TAG_CHARS = 40

private val SAFE_ID_REGEX = Regex("^[A-Za-z0-9][A-Za-z0-9._-]{0,${MAX_ID_CHARS - 1}}$")
private val SCRIPT_TOOL_NAME_REGEX = Regex("^[a-z][a-z0-9_]{1,40}$")
private val SCRIPT_PARAM_NAME_REGEX = Regex("^[a-z][a-z0-9_]{0,40}$")
private const val TRACKER_TOOL_PREFIX = "tracker_"

/**
 * Names a script parameter may not use. Parameters reach a script as environment variables, so these
 * would shadow variables the run itself provides (or that the shell and loader depend on).
 */
val RESERVED_SCRIPT_PARAM_NAMES: Set<String> = setOf(
    "path", "home", "device", "package", "run_dir", "case_id", "step_id",
    "run_id", "lane_id", "suite_id", "serial", "shell", "user", "pwd", "ifs", "tmpdir", "lang", "ld_preload",
    "dyld_insert_libraries",
)

/** Names of the built-in lane tools; a script tool may not shadow them. */
val RESERVED_SCRIPT_TOOL_NAMES: Set<String> = setOf(
    "get_current_step", "dump_ui_tree", "take_screenshot", "tap", "swipe", "press_key", "input_text",
    "launch_app", "open_url", "wait_for_log", "read_log_since_step", "report_observation", "finish_step",
    "list_step_examples", "get_step_example",
)

/** An id that is safe to use as a file name and inside a path. */
fun isSafeId(id: String): Boolean = SAFE_ID_REGEX.matches(id)

/**
 * A copy of the list with the element whose id is [id] moved so it ends up at [toIndex] in the result
 * (clamped into the list). An unknown id, or a move onto its own position, returns the same list.
 */
fun <T> List<T>.moveById(id: String, toIndex: Int, idOf: (T) -> String): List<T> {
    val from = indexOfFirst { idOf(it) == id }
    if (from < 0) return this
    val target = toIndex.coerceIn(0, lastIndex)
    if (target == from) return this
    val moved = toMutableList()
    moved.add(target, moved.removeAt(from))
    return moved
}

/** The index an Alt+Up / Alt+Down press moves the row at [index] to, or null at the edge of the list. */
fun altArrowTarget(index: Int, size: Int, up: Boolean): Int? {
    if (index !in 0 until size) return null
    val target = if (up) index - 1 else index + 1
    return target.takeIf { it in 0 until size }
}

/** Why [name] cannot be a script tool name, or null when it can. */
fun scriptToolNameError(name: String): String? = when {
    !SCRIPT_TOOL_NAME_REGEX.matches(name) ->
        "Tool name must start with a lowercase letter and use only lowercase letters, digits and _ (2-$MAX_SCRIPT_TOOL_NAME_CHARS characters)."
    name in RESERVED_SCRIPT_TOOL_NAMES -> "'$name' is the name of a built-in test tool."
    name.startsWith(TRACKER_TOOL_PREFIX) -> "Tool names starting with '$TRACKER_TOOL_PREFIX' are reserved for the issue tracker."
    else -> null
}

fun isValidScriptToolName(name: String): Boolean = scriptToolNameError(name) == null

/** Why [name] cannot be a script parameter name, or null when it can. */
fun scriptParamNameError(name: String): String? = when {
    !SCRIPT_PARAM_NAME_REGEX.matches(name) ->
        "Parameter name must start with a lowercase letter and use only lowercase letters, digits and _."
    name in RESERVED_SCRIPT_PARAM_NAMES -> "'$name' is reserved; choose another parameter name."
    else -> null
}

fun isValidScriptParamName(name: String): Boolean = scriptParamNameError(name) == null

/** Tags trimmed, blanks dropped and duplicates (ignoring case) removed, keeping the first spelling and the order. */
fun normalizeTags(tags: List<String>): List<String> =
    tags.map { it.trim() }.filter { it.isNotEmpty() }.distinctBy { it.lowercase() }

/** "Name" becomes "Name (copy)". */
fun copyName(name: String): String = name + COPY_NAME_SUFFIX

fun HookItem.withId(newId: String): HookItem = when (this) {
    is HookItem.Script -> copy(id = newId)
    is HookItem.Shared -> copy(id = newId)
}

fun StepExample.withId(newId: String): StepExample = when (this) {
    is StepExample.GoldenScreenshot -> copy(id = newId)
    is StepExample.ReferenceLog -> copy(id = newId)
}

/** [exampleIds] maps old example ids to new ones so a [StepCheck.ScreenJudge] keeps pointing at its example. */
fun StepCheck.withFreshId(exampleIds: Map<String, String>): StepCheck = when (this) {
    is StepCheck.LogAppears -> copy(id = newCheckId())
    is StepCheck.LogAbsent -> copy(id = newCheckId())
    is StepCheck.ScreenJudge -> copy(id = newCheckId(), exampleRef = exampleRef?.let { exampleIds[it] ?: it })
    is StepCheck.ScriptResult -> copy(id = newCheckId())
    is StepCheck.AskJudge -> copy(id = newCheckId())
}

/** A deep copy with new ids for the step and everything inside it. */
fun TestStep.withFreshIds(): TestStep {
    val exampleIds = examples.associate { it.id to newExampleId() }
    return copy(
        id = newStepId(),
        checks = checks.map { it.withFreshId(exampleIds) },
        examples = examples.map { it.withId(exampleIds.getValue(it.id)) },
    )
}

fun TestCase.withFreshIds(): TestCase = copy(
    id = newCaseId(),
    setup = setup.map { it.withId(newHookId()) },
    teardown = teardown.map { it.withId(newHookId()) },
    steps = steps.map { it.withFreshIds() },
)

/** A deep copy with new ids for the suite and everything inside it; [TestSuite.readOnly] is cleared. */
fun TestSuite.withFreshIds(): TestSuite = copy(
    id = newSuiteId(),
    setup = setup.map { it.withId(newHookId()) },
    teardown = teardown.map { it.withId(newHookId()) },
    variables = variables.map { it.copy(id = newVariableId()) },
    cases = cases.map { it.withFreshIds() },
    readOnly = false,
)
