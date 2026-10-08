package com.indagium.testing.model

import java.util.UUID

// Domain types of the AI test-suites feature. Pure data, UI-free and immutable: every container's
// ORDER is its list order (suites, cases, steps, checks, examples, hooks, variables, scripts, params),
// so reordering is just `List.moveById` (TestModelRules.kt) and persistence needs no separate index.
// Ids are prefixed UUIDs, unique across the whole library, so a case/step can be found by id alone.

const val SUITE_ID_PREFIX = "suite-"
const val CASE_ID_PREFIX = "case-"
const val STEP_ID_PREFIX = "step-"
const val CHECK_ID_PREFIX = "chk-"
const val EXAMPLE_ID_PREFIX = "ex-"
const val SCRIPT_ID_PREFIX = "script-"
const val SHARED_STEP_ID_PREFIX = "shared-"
const val HOOK_ID_PREFIX = "hook-"
const val VARIABLE_ID_PREFIX = "var-"

const val DEFAULT_STEP_TIMEOUT_MS = 60_000L
const val DEFAULT_STEP_RETRIES = 1
const val DEFAULT_STEP_MAX_TOOL_CALLS = 15
const val DEFAULT_LOG_WITHIN_MS = 10_000L
const val DEFAULT_LOG_ABSENT_FOR_MS = 5_000L
const val DEFAULT_SCRIPT_TIMEOUT_MS = 30_000L
const val DEFAULT_SCRIPT_OUTPUT_CAP_BYTES = 64 * 1024
const val DEFAULT_SCRIPT_EXIT_CODE = 0

fun newPrefixedId(prefix: String): String = prefix + UUID.randomUUID()

fun newSuiteId(): String = newPrefixedId(SUITE_ID_PREFIX)

fun newCaseId(): String = newPrefixedId(CASE_ID_PREFIX)

fun newStepId(): String = newPrefixedId(STEP_ID_PREFIX)

fun newCheckId(): String = newPrefixedId(CHECK_ID_PREFIX)

fun newExampleId(): String = newPrefixedId(EXAMPLE_ID_PREFIX)

fun newScriptId(): String = newPrefixedId(SCRIPT_ID_PREFIX)

fun newSharedStepId(): String = newPrefixedId(SHARED_STEP_ID_PREFIX)

fun newHookId(): String = newPrefixedId(HOOK_ID_PREFIX)

fun newVariableId(): String = newPrefixedId(VARIABLE_ID_PREFIX)

/** Where a failed step sends the run. */
enum class OnFailure { STOP_CASE, CONTINUE, CREATE_ISSUE_AND_CONTINUE, PAUSE_FOR_USER }

/** Where a custom script runs: on the computer, or inside the device through `adb shell`. */
enum class ScriptTarget { HOST_SHELL, ADB_SHELL }

/** Who may start a script. SETUP_TEARDOWN_ONLY scripts are never offered to an agent as a tool. */
enum class ScriptPermission { AUTO, ASK, SETUP_TEARDOWN_ONLY }

enum class ScriptParamType { STRING, INT, BOOL }

/** One parameter of a [TestScript]. Its [name] is the key inside the script (unique per script). */
data class ScriptParam(
    val name: String,
    val type: ScriptParamType = ScriptParamType.STRING,
    val description: String = "",
    val required: Boolean = true,
    val defaultValue: String? = null,
)

/** A user-defined shell command exposed to agents as a typed tool named [toolName]. */
data class TestScript(
    val id: String,
    val toolName: String,
    val description: String = "",
    val params: List<ScriptParam> = emptyList(),
    val commandTemplate: String = "",
    val target: ScriptTarget = ScriptTarget.HOST_SHELL,
    val timeoutMs: Long = DEFAULT_SCRIPT_TIMEOUT_MS,
    val outputCapBytes: Int = DEFAULT_SCRIPT_OUTPUT_CAP_BYTES,
    val workingDir: String? = null,
    val permission: ScriptPermission = ScriptPermission.ASK,
)

/** A reusable named sequence of steps, referenced from suite setup/teardown hooks by id. */
data class SharedStep(
    val id: String,
    val name: String,
    val description: String = "",
    val steps: List<TestStep> = emptyList(),
)

/** A suite setup/teardown entry: run a library script, or a shared step. */
sealed interface HookItem {
    val id: String

    data class Script(
        override val id: String,
        val scriptId: String,
        val args: Map<String, String> = emptyMap(),
    ) : HookItem

    data class Shared(override val id: String, val sharedStepId: String) : HookItem
}

/** A named value the suite's prompts and scripts can refer to. */
data class TestVariable(
    val id: String,
    val name: String,
    val value: String = "",
    val description: String = "",
)

/** A verifiable condition on a step. Deterministic checks run first; judge checks go to the blind judge. */
sealed interface StepCheck {
    val id: String

    data class LogAppears(
        override val id: String,
        val tag: String? = null,
        val regex: String,
        val withinMs: Long = DEFAULT_LOG_WITHIN_MS,
    ) : StepCheck

    data class LogAbsent(
        override val id: String,
        val tag: String? = null,
        val regex: String,
        val forMs: Long = DEFAULT_LOG_ABSENT_FOR_MS,
    ) : StepCheck

    /** [exampleRef] is the id of a [StepExample] in the same step the judge should compare against. */
    data class ScreenJudge(override val id: String, val text: String, val exampleRef: String? = null) : StepCheck

    /** [exitCode] null means "any exit code"; [stdoutContains] null means "any output". */
    data class ScriptResult(
        override val id: String,
        val scriptId: String,
        val args: Map<String, String> = emptyMap(),
        val exitCode: Int? = DEFAULT_SCRIPT_EXIT_CODE,
        val stdoutContains: String? = null,
    ) : StepCheck

    data class AskJudge(override val id: String, val text: String) : StepCheck
}

/** Reference material for the judge. */
sealed interface StepExample {
    val id: String
    val caption: String

    /** [assetPath] is relative to the suite's asset folder; the image file itself is not part of the suite file. */
    data class GoldenScreenshot(override val id: String, val assetPath: String, override val caption: String = "") : StepExample

    data class ReferenceLog(override val id: String, val text: String, override val caption: String = "") : StepExample
}

data class TestStep(
    val id: String,
    val action: String,
    val expected: String = "",
    val checks: List<StepCheck> = emptyList(),
    val examples: List<StepExample> = emptyList(),
    val timeoutMs: Long = DEFAULT_STEP_TIMEOUT_MS,
    val retries: Int = DEFAULT_STEP_RETRIES,
    /** How many lane-tool calls the agent may spend on this step before the run gives up on it. */
    val maxToolCalls: Int = DEFAULT_STEP_MAX_TOOL_CALLS,
    val onFailure: OnFailure = OnFailure.STOP_CASE,
    /** When true, the lane may skip this step if [condition] is not present on the device. */
    val optional: Boolean = false,
    /** A visible, testable condition that must be present before an optional step is performed. */
    val condition: String? = null,
)

/**
 * [description] is the case's GOAL (what it sets out to verify). [preconditions] is free text about the
 * state the case expects; [setup]/[teardown] are hooks run around just this case.
 * [allowedTools] null means every lane tool is allowed; an empty set means none.
 */
data class TestCase(
    val id: String,
    val name: String,
    val description: String = "",
    val instructions: String = "",
    val preconditions: String = "",
    val setup: List<HookItem> = emptyList(),
    val teardown: List<HookItem> = emptyList(),
    val steps: List<TestStep> = emptyList(),
    val allowedTools: Set<String>? = null,
)

/**
 * [targetPackage] is the Android package under test (blank = unspecified), [deviceProfileHint] free text
 * about the device the suite wants, [tags] short labels (trimmed, unique, see [normalizeTags]).
 * [readOnly] is never persisted: it marks a suite that was loaded from a file written by a NEWER
 * version of Indagium. Such a suite can be read, exported and deleted, but is never rewritten, so
 * fields this build does not understand are not silently lost.
 */
data class TestSuite(
    val id: String,
    val name: String,
    val description: String = "",
    val instructions: String = "",
    val targetPackage: String = "",
    val deviceProfileHint: String = "",
    val tags: List<String> = emptyList(),
    val setup: List<HookItem> = emptyList(),
    val teardown: List<HookItem> = emptyList(),
    val variables: List<TestVariable> = emptyList(),
    val cases: List<TestCase> = emptyList(),
    val createdAt: Long = 0L,
    val updatedAt: Long = 0L,
    val readOnly: Boolean = false,
)

/** Where a case lives. */
data class CaseLocation(val suite: TestSuite, val case: TestCase, val index: Int)

/** Where a step lives. */
data class StepLocation(val suite: TestSuite, val case: TestCase, val step: TestStep, val index: Int)

/**
 * The whole library. [suites] is the user's suite order. [readOnly] marks a `library.json` written by
 * a newer version: everything stored in that file (suite order, scripts, shared steps) is then not
 * rewritten by this build.
 */
data class TestLibrary(
    val suites: List<TestSuite> = emptyList(),
    val scripts: List<TestScript> = emptyList(),
    val sharedSteps: List<SharedStep> = emptyList(),
    val readOnly: Boolean = false,
) {
    fun suite(id: String): TestSuite? = suites.firstOrNull { it.id == id }

    fun script(id: String): TestScript? = scripts.firstOrNull { it.id == id }

    fun sharedStep(id: String): SharedStep? = sharedSteps.firstOrNull { it.id == id }

    fun findCase(caseId: String): CaseLocation? {
        for (suite in suites) {
            val index = suite.cases.indexOfFirst { it.id == caseId }
            if (index >= 0) return CaseLocation(suite, suite.cases[index], index)
        }
        return null
    }

    fun findStep(stepId: String): StepLocation? {
        for (suite in suites) {
            for (case in suite.cases) {
                val index = case.steps.indexOfFirst { it.id == stepId }
                if (index >= 0) return StepLocation(suite, case, case.steps[index], index)
            }
        }
        return null
    }
}
