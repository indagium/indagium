package com.indagium.debug

import com.indagium.testing.model.ScriptTarget
import com.indagium.testing.script.ScriptArgsResult
import com.indagium.testing.script.scriptArgsFromToolValues
import com.indagium.testing.script.validateScriptArgs
import com.indagium.ui.AppState
import com.indagium.ui.ExternalActionDetails

// Tools an EXTERNAL MCP client may call only after the user approved that exact call. CONFIRMATION_REQUIRED gates just
// Indagium's own AI panel; an external client holding the control token would otherwise run any command a script
// holds with nobody asked. Approval is per call (never remembered for the session) because each call may run a
// different command. Device tools keep their own per-session approval (ControlServer.executeExternalDeviceTool).
//
// run_test_suite starts a run that drives devices and may run scripts, so it needs the user's yes for every call; so does
// rerun_test_step (a new run of one case); so does test_lane_tool_call when it runs a SCRIPT tool of an external lane (a
// built-in lane tool such as tap needs no approval: the run itself was approved, and the lane's device is the one named
// in that approval).

// send_issue_to_tracker, and create_issue_from_step when its destination is the tracker, hand the issue text and evidence to an AI
// agent and an external service (ExternalTrackerApproval.kt); create_issue_from_step with any other destination needs no approval.
internal val PER_CALL_APPROVAL_MCP_TOOLS: Set<String> = setOf(
    "try_test_script", "run_test_suite", "rerun_test_step", "test_lane_tool_call", "create_issue_from_step", "send_issue_to_tracker",
)

/** The approval dialog content for [toolName], or null when the call would be refused or needs no approval of its own. */
internal suspend fun describePerCallApproval(appState: AppState, toolName: String, arguments: Map<String, Any?>, clientName: String): ExternalActionDetails? =
    when (toolName) {
        "try_test_script" -> describeTryScriptCall(appState, arguments, clientName)
        "run_test_suite" -> describeRunSuiteCall(appState, arguments, clientName)
        "rerun_test_step" -> describeRerunStepCall(appState, arguments, clientName)
        "test_lane_tool_call" -> describeLaneToolCall(appState, arguments, clientName)
        "create_issue_from_step", "send_issue_to_tracker" -> describeTrackerSendCall(appState, toolName, arguments, clientName)
        else -> null
    }

internal const val MAX_COMMAND_CHARS = 2_000
private const val MAX_ARG_VALUE_CHARS = 200
private const val MAX_ARGS_SHOWN = 20
private const val ELLIPSIS = "…"
private const val DECLINED_SCRIPT_MESSAGE = "The user declined to run this script, or did not answer in time; nothing was run."

/**
 * The approval dialog content for a `try_test_script` call, or null when the call would be refused anyway (unknown
 * script, invalid arguments, a device script without a device): then the tool itself reports the error and nothing
 * runs, so the user is not asked about it.
 */
internal fun describeTryScriptCall(appState: AppState, arguments: Map<String, Any?>, clientName: String): ExternalActionDetails? {
    val script = (arguments["scriptId"] as? String)?.let(appState.testLibrary::script) ?: return null
    val rawArgs = when (val given = arguments["args"]) {
        null -> emptyMap()
        is Map<*, *> -> given.entries.associate { (key, value) -> key.toString() to value }
        else -> return null
    }
    val converted = try {
        scriptArgsFromToolValues(rawArgs)
    } catch (_: IllegalArgumentException) {
        return null
    }
    val values = (validateScriptArgs(script, converted) as? ScriptArgsResult.Valid)?.values ?: return null
    val serial = (arguments["deviceSerial"] as? String)?.trim()?.takeIf(String::isNotEmpty)
    if (script.target == ScriptTarget.ADB_SHELL && serial == null) return null
    val target = when {
        script.target == ScriptTarget.ADB_SHELL -> "Android device $serial (inside the device, through adb shell)"
        serial != null -> "This computer (DEVICE=$serial)"
        else -> "This computer"
    }
    return ExternalActionDetails(
        title = "Run a script?",
        summary = "$clientName wants to run the script \"${script.toolName}\". It runs once, with exactly this command and these arguments.",
        fields = listOf(
            "Script" to script.toolName,
            "Runs on" to target,
            "Command" to script.commandTemplate.clip(MAX_COMMAND_CHARS),
            "Arguments" to argumentLines(values),
        ),
        allowLabel = "Run once",
        declinedMessage = DECLINED_SCRIPT_MESSAGE,
    )
}

internal fun argumentLines(values: Map<String, String>): String {
    if (values.isEmpty()) return "(none)"
    val shown = values.entries.take(MAX_ARGS_SHOWN).joinToString("\n") { (name, value) -> "$name = ${value.clip(MAX_ARG_VALUE_CHARS)}" }
    val hidden = values.size - MAX_ARGS_SHOWN
    return if (hidden > 0) "$shown\n+ $hidden more" else shown
}

internal fun String.clip(max: Int): String = if (length <= max) this else take(max) + "$ELLIPSIS (${length - max} more characters)"
