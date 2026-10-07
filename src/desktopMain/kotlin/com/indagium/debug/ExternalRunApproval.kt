package com.indagium.debug

import com.indagium.testing.model.EXTERNAL_LANE_PROFILE_ID
import com.indagium.testing.model.HookItem
import com.indagium.testing.model.JudgeMode
import com.indagium.testing.model.RESERVED_SCRIPT_TOOL_NAMES
import com.indagium.testing.model.RunConfig
import com.indagium.testing.model.ScriptPermission
import com.indagium.testing.model.ScriptTarget
import com.indagium.testing.model.StepCheck
import com.indagium.testing.model.TestCase
import com.indagium.testing.model.TestLibrary
import com.indagium.testing.model.TestScript
import com.indagium.testing.model.TestSuite
import com.indagium.testing.run.rerunConfig
import com.indagium.testing.script.ScriptArgsResult
import com.indagium.testing.script.scriptArgsFromToolValues
import com.indagium.testing.script.validateScriptArgs
import com.indagium.ui.AppState
import com.indagium.ui.ExternalActionDetails

// What the user is shown before an EXTERNAL MCP client may start a test run or run a script of an external lane. The
// dialog names the suite, the cases, every lane with its device and every script the run may execute (with its
// command), so "Allow" is an informed yes. A call the tool would refuse anyway returns null: nothing runs and the tool
// reports the error itself, so the user is not asked about it.

private const val MAX_SCRIPTS_SHOWN = 12
private const val MAX_SCRIPT_COMMAND_CHARS = 300
private const val MAX_CASE_NAMES_SHOWN = 12
internal const val DECLINED_RUN_MESSAGE = "The user declined to start this test run, or did not answer in time; nothing was started."
private const val DECLINED_LANE_SCRIPT_MESSAGE = "The user declined to run this script, or did not answer in time; nothing was run."

private fun TestScript.where(): String = if (target == ScriptTarget.ADB_SHELL) "on the device" else "on this computer"

private fun TestSuite.hookAndCheckScripts(): Map<String, MutableSet<String>> {
    val reasons = LinkedHashMap<String, MutableSet<String>>()

    fun add(scriptId: String, why: String) {
        reasons.getOrPut(scriptId) { LinkedHashSet() } += why
    }
    setup.filterIsInstance<HookItem.Script>().forEach { add(it.scriptId, "suite setup") }
    teardown.filterIsInstance<HookItem.Script>().forEach { add(it.scriptId, "suite teardown") }
    cases.forEach { case ->
        case.setup.filterIsInstance<HookItem.Script>().forEach { add(it.scriptId, "case setup") }
        case.teardown.filterIsInstance<HookItem.Script>().forEach { add(it.scriptId, "case teardown") }
        case.steps.flatMap { it.checks }.filterIsInstance<StepCheck.ScriptResult>().forEach { add(it.scriptId, "step check") }
    }
    return reasons
}

internal fun scriptLines(library: TestLibrary, suite: TestSuite): String {
    val fixed = suite.hookAndCheckScripts()
    val lines = ArrayList<String>()
    val shown = LinkedHashSet<String>()
    library.scripts.forEach { script ->
        val why = ArrayList<String>()
        when (script.permission) {
            ScriptPermission.AUTO -> why += "lane tool, runs without asking"
            ScriptPermission.ASK -> why += "lane tool, asks first"
            ScriptPermission.SETUP_TEARDOWN_ONLY -> Unit
        }
        fixed[script.id]?.let { why += it.joinToString("/") }
        if (why.isNotEmpty() && shown.add(script.id)) {
            lines += "${script.toolName} (${script.where()}; ${why.joinToString(", ")}): ${script.commandTemplate.clip(MAX_SCRIPT_COMMAND_CHARS)}"
        }
    }
    if (lines.isEmpty()) return "(none)"
    val more = lines.size - MAX_SCRIPTS_SHOWN
    val text = lines.take(MAX_SCRIPTS_SHOWN).joinToString("\n")
    return if (more > 0) "$text\n+ $more more" else text
}

internal fun judgeLine(appState: AppState, judgeProfileId: String?, judgeMode: String): String? {
    val mode = JudgeMode.parse(judgeMode) ?: return null
    if (mode == JudgeMode.OFF || judgeProfileId.isNullOrBlank()) return null
    val name = appState.settings.aiProviderProfiles.firstOrNull { it.id == judgeProfileId }?.displayName ?: judgeProfileId
    return "AI profile $name (${mode.label.lowercase()})"
}

internal fun laneLine(appState: AppState, profileId: String, serial: String): String {
    val profileName = appState.settings.aiProviderProfiles.firstOrNull { it.id == profileId }?.displayName ?: profileId
    val driver = if (profileId.equals(EXTERNAL_LANE_PROFILE_ID, ignoreCase = true)) "driven by you over MCP" else "AI profile $profileName"
    return "device ${serial.ifBlank { "(no device)" }} — $driver"
}

internal fun describeRunSuiteCall(appState: AppState, arguments: Map<String, Any?>, clientName: String): ExternalActionDetails? {
    val library = appState.testLibrary
    val suite = (arguments["suiteId"] as? String)?.let(library::suite) ?: return null
    val laneItems = (arguments["lanes"] as? List<*>)?.mapNotNull { it as? Map<*, *> }.orEmpty()
    if (laneItems.isEmpty()) return null
    val laneLines = laneItems.map { item -> laneLine(appState, (item["profileId"] as? String).orEmpty(), (item["deviceSerial"] as? String).orEmpty()) }
    val chosen = (arguments["caseIds"] as? List<*>)?.filterIsInstance<String>()
    val caseNames = suite.cases.filter { chosen == null || it.id in chosen }.map { it.name }
    val casesText = if (chosen == null) "All ${suite.cases.size} case(s)" else "${caseNames.size} of ${suite.cases.size} case(s)"
    val shownCases = caseNames.take(MAX_CASE_NAMES_SHOWN).joinToString(", ") + if (caseNames.size > MAX_CASE_NAMES_SHOWN) ", …" else ""
    val judge = judgeLine(appState, arguments["judgeProfileId"] as? String, (arguments["judgeMode"] as? String).orEmpty())
    return ExternalActionDetails(
        title = "Start a test run?",
        summary = "$clientName wants to run the test suite \"${suite.name}\". The run drives the devices below and may run the scripts listed.",
        fields = listOfNotNull(
            "Suite" to suite.name,
            "Cases" to "$casesText: $shownCases",
            "Lanes" to laneLines.joinToString("\n"),
            "Repeat" to ((arguments["repeat"] as? Number)?.toInt() ?: 1).toString(),
            judge?.let { "Judge" to it },
            "Scripts that may run" to scriptLines(library, suite),
        ),
        allowLabel = "Start run",
        declinedMessage = DECLINED_RUN_MESSAGE,
    )
}

/** The case, step and lane of a re-run an external client asks for, as far as the library still has them. */
private data class RerunTarget(val config: RunConfig, val suite: TestSuite, val case: TestCase, val stepNumber: Int)

private suspend fun rerunTarget(appState: AppState, arguments: Map<String, Any?>): RerunTarget? {
    val run = (arguments["runId"] as? String)?.let { appState.testRunCoordinator.loadRun(it) }
    val laneId = arguments["laneId"] as? String
    val caseId = arguments["caseId"] as? String
    val stepId = arguments["stepId"] as? String
    if (run == null || laneId == null || caseId == null || stepId == null) return null
    val config = rerunConfig(run, laneId, caseId, stepId).getOrNull() ?: return null
    val suite = appState.testLibrary.suite(config.suiteId) ?: return null
    val case = suite.cases.firstOrNull { it.id == caseId } ?: return null
    val stepNumber = case.steps.indexOfFirst { it.id == stepId } + 1
    return if (stepNumber == 0) null else RerunTarget(config, suite, case, stepNumber)
}

/** What the user is asked before an external client may re-run a step: a new run of one case, on the lane's device. Null when it would be refused anyway. */
internal suspend fun describeRerunStepCall(appState: AppState, arguments: Map<String, Any?>, clientName: String): ExternalActionDetails? {
    val target = rerunTarget(appState, arguments) ?: return null
    val (config, suite, case) = target
    val stepNumber = target.stepNumber
    val lane = config.lanes.single()
    return ExternalActionDetails(
        title = "Run a step again?",
        summary = "$clientName wants to start a new test run of the case \"${case.name}\" in \"${suite.name}\", from its first step up to " +
            "step $stepNumber. The run drives the device below and may run the scripts listed.",
        fields = listOfNotNull(
            "Suite" to suite.name,
            "Case" to "${case.name} (steps 1–$stepNumber of ${case.steps.size})",
            "Lane" to laneLine(appState, lane.profileId ?: EXTERNAL_LANE_PROFILE_ID, lane.deviceSerial),
            judgeLine(appState, config.judgeProfileId, config.judgeMode)?.let { "Judge" to it },
            "Scripts that may run" to scriptLines(appState.testLibrary, suite),
        ),
        allowLabel = "Start run",
        declinedMessage = DECLINED_RUN_MESSAGE,
    )
}

/** The script tool a `test_lane_tool_call` names and the serial of the lane's device, or null for a built-in tool or an unknown run or lane. */
private fun laneScriptTarget(appState: AppState, arguments: Map<String, Any?>): Pair<TestScript, String>? {
    val tool = (arguments["tool"] as? String)?.trim().orEmpty()
    if (tool.isEmpty() || tool in RESERVED_SCRIPT_TOOL_NAMES) return null
    val run = (arguments["runId"] as? String)?.let(appState.testRunCoordinator::run) ?: return null
    val script = run.scripts.firstOrNull { it.toolName == tool } ?: return null
    val serial = (arguments["laneId"] as? String)?.let { run.lane(it)?.config?.deviceSerial } ?: return null
    return script to serial
}

/** The call's `arguments` validated against [script]'s parameters, or null when the tool would refuse them anyway. */
private fun laneScriptArguments(script: TestScript, arguments: Map<String, Any?>): Map<String, String>? {
    val rawArgs = when (val given = arguments["arguments"]) {
        null -> emptyMap()
        is Map<*, *> -> given.entries.associate { (key, value) -> key.toString() to value }
        else -> return null
    }
    val converted = try {
        scriptArgsFromToolValues(rawArgs)
    } catch (_: IllegalArgumentException) {
        return null
    }
    return (validateScriptArgs(script, converted) as? ScriptArgsResult.Valid)?.values
}

internal fun describeLaneToolCall(appState: AppState, arguments: Map<String, Any?>, clientName: String): ExternalActionDetails? {
    val (script, serial) = laneScriptTarget(appState, arguments) ?: return null
    val values = laneScriptArguments(script, arguments) ?: return null
    return ExternalActionDetails(
        title = "Run a script in a test run?",
        summary = "$clientName wants to run the script \"${script.toolName}\" on the external lane of a test run. It runs once, with exactly this command.",
        fields = listOf(
            "Script" to script.toolName,
            "Runs on" to
                if (script.target == ScriptTarget.ADB_SHELL) {
                    "Android device $serial (inside the device, through adb shell)"
                } else {
                    "This computer (DEVICE=$serial)"
                },
            "Command" to script.commandTemplate.clip(MAX_COMMAND_CHARS),
            "Arguments" to argumentLines(values),
        ),
        allowLabel = "Run once",
        declinedMessage = DECLINED_LANE_SCRIPT_MESSAGE,
    )
}
