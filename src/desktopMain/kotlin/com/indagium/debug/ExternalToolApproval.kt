package com.indagium.debug

import com.indagium.ai.isLoopbackHost
import com.indagium.testing.authoring.MAX_TEST_SCRIPT_FILE_BYTES
import com.indagium.testing.limits.LimitDecision
import com.indagium.testing.limits.LimitOperation
import com.indagium.testing.limits.decide
import com.indagium.testing.model.EXTERNAL_LANE_PROFILE_ID
import com.indagium.testing.model.ScriptTarget
import com.indagium.testing.run.rerunFailedCasesConfig
import com.indagium.testing.script.ScriptArgsResult
import com.indagium.testing.script.scriptArgsFromToolValues
import com.indagium.testing.script.validateScriptArgs
import com.indagium.testing.store.StoreResult
import com.indagium.ui.AppState
import com.indagium.ui.ExternalActionDetails
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

private const val APPROVAL_INSTRUCTION_PREVIEW_CHARS = 180

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
    "try_test_script", "run_test_suite", "rerun_test_step", "rerun_failed_test_cases", "test_lane_tool_call",
    "draft_test_steps", "rewrite_test_recording", "import_test_script", "export_test_script", "start_test_recording", "apply_test_recording",
    "export_test_run_report", "collect_android_bugreport", "export_issue_step_clip", "create_issue_from_step", "send_issue_to_tracker",
)

/** The approval dialog content for [toolName], or null when the call would be refused or needs no approval of its own. */
internal suspend fun describePerCallApproval(appState: AppState, toolName: String, arguments: Map<String, Any?>, clientName: String): ExternalActionDetails? =
    when (toolName) {
        "try_test_script" -> describeTryScriptCall(appState, arguments, clientName)
        "run_test_suite" -> describeRunSuiteCall(appState, arguments, clientName)
        "rerun_test_step" -> describeRerunStepCall(appState, arguments, clientName)
        "rerun_failed_test_cases" -> describeRerunFailedCall(appState, arguments, clientName)
        "draft_test_steps" -> describeDraftStepCall(appState, arguments, clientName)
        "rewrite_test_recording" -> describeRewriteRecordingCall(appState, arguments, clientName)
        "import_test_script" -> describeScriptImportCall(arguments, clientName)
        "export_test_script" -> describeScriptExportCall(appState, arguments, clientName)
        "start_test_recording" -> describeRecordingStartCall(appState, arguments, clientName)
        "apply_test_recording" -> describeRecordingApplyCall(appState, arguments, clientName)
        "export_test_run_report" -> describeReportExportCall(arguments, clientName)
        "collect_android_bugreport" -> describeBugreportCall(appState, arguments, clientName)
        "export_issue_step_clip" -> describeIssueClipCall(appState, arguments, clientName)
        "test_lane_tool_call" -> describeLaneToolCall(appState, arguments, clientName)
        "create_issue_from_step", "send_issue_to_tracker" -> describeTrackerSendCall(appState, toolName, arguments, clientName)
        else -> null
    }

@Suppress("ReturnCount") // Fail-closed argument checks each return an actionable refusal.
private fun describeDraftStepCall(appState: AppState, arguments: Map<String, Any?>, clientName: String): ExternalActionDetails? {
    val profileId = arguments["profileId"] as? String ?: return null
    val profile = appState.settings.aiProviderProfiles.firstOrNull { it.id == profileId } ?: return null
    val suiteId = arguments["suiteId"] as? String ?: return null
    val caseId = arguments["caseId"] as? String ?: return null
    val suite = appState.testLibrary.suite(suiteId) ?: return null
    val testCase = suite.cases.firstOrNull { it.id == caseId } ?: return null
    val instruction = (arguments["instruction"] as? String)?.takeIf(String::isNotBlank) ?: return null
    val endpointHost = remoteDestination(profile) ?: return null
    val model = (arguments["model"] as? String)?.takeIf(String::isNotBlank)
    return ExternalActionDetails(
        title = "Send test context to an AI provider?",
        summary =
            "$clientName wants to draft steps for '${testCase.name}'. The provider receives the request and bounded suite/case context; " +
                "it returns a preview only and does not edit the library or control a device.",
        fields = listOfNotNull(
            "Provider" to "${profile.displayName} · ${profile.kind.label}",
            model?.let { "Model" to it },
            "Destination" to endpointHost,
            "Suite and case context" to "${suite.name} / ${testCase.name}",
            "Draft request" to instruction.clip(APPROVAL_INSTRUCTION_PREVIEW_CHARS),
            "Changes" to "Preview only; applying edited steps requires a separate action",
        ),
        allowLabel = "Generate preview",
        declinedMessage = "The user declined to send this test context to the configured provider; no draft was generated.",
    )
}

/** Where a profile's requests go; null when they stay on this computer (a loopback endpoint) and need no disclosure. */
private fun remoteDestination(profile: com.indagium.model.AiProviderProfile): String? {
    val endpointHost = if (profile.kind.usesHttpEndpoint) {
        runCatching { java.net.URI(profile.baseUrl).host }.getOrNull()?.takeIf(String::isNotBlank) ?: "provider endpoint (unresolved)"
    } else {
        "signed-in local CLI account"
    }
    return endpointHost.takeIf { !profile.kind.usesHttpEndpoint || !isLoopbackHost(endpointHost) }
}

@Suppress("ReturnCount") // Fail-closed argument checks each return an actionable refusal.
private fun describeRewriteRecordingCall(appState: AppState, arguments: Map<String, Any?>, clientName: String): ExternalActionDetails? {
    val profileId = arguments["profileId"] as? String ?: return null
    val profile = appState.settings.aiProviderProfiles.firstOrNull { it.id == profileId } ?: return null
    val sessionId = arguments["sessionId"] as? String ?: return null
    val snapshot = (appState.testStepRecordingSnapshot(sessionId) as? StoreResult.Ok)?.value ?: return null
    if (snapshot.active || snapshot.pendingSnapshots != 0 || appState.isTestStepRecordingApplying(sessionId)) return null
    val target = appState.testStepRecordingTarget ?: return null
    val suite = appState.testLibrary.suite(target.first) ?: return null
    val testCase = suite.cases.firstOrNull { it.id == target.second } ?: return null
    val destination = remoteDestination(profile) ?: return null
    val recorded = (snapshot.rawSteps ?: snapshot.steps).filterNot { it.id in snapshot.excludedSourceInputIds }
    val model = (arguments["model"] as? String)?.takeIf(String::isNotBlank) ?: profile.model
    return ExternalActionDetails(
        title = "Send a recording to an AI provider?",
        summary =
            "$clientName wants to rewrite the recording for '${testCase.name}' as readable steps. The provider receives the recorded inputs, " +
                "screen elements and text, typed text (passwords are hidden) and screenshots from the device; it returns a preview " +
                "only and does not edit the library or control a device.",
        fields = listOf(
            "Provider" to "${profile.displayName} · ${profile.kind.label}",
            "Model" to model,
            "Destination" to destination,
            "Suite and case context" to "${suite.name} / ${testCase.name}",
            "Recorded inputs" to "${recorded.size} input(s) with their screenshots and screen text",
            "Changes" to "Replaces the recording's rows for review; applying them to the case is a separate action",
        ),
        allowLabel = "Rewrite recording",
        declinedMessage = "The user declined to send this recording to the configured provider; it was not rewritten.",
    )
}

private suspend fun describeScriptImportCall(arguments: Map<String, Any?>, clientName: String): ExternalActionDetails? {
    val path = arguments["path"] as? String
    val text = arguments["text"] as? String
    if ((path == null) == (text == null)) return null
    val source = if (path != null) {
        val file = runCatching { java.io.File(path) }.getOrNull() ?: return null
        if (!file.isAbsolute) return null
        withContext(Dispatchers.IO) {
            if (!file.isFile || file.length() !in 1..MAX_TEST_SCRIPT_FILE_BYTES.toLong()) null else "${file.name} (${file.length()} bytes)"
        } ?: return null
    } else {
        if (text.isNullOrBlank() || text.toByteArray(Charsets.UTF_8).size > MAX_TEST_SCRIPT_FILE_BYTES) return null
        "inline JSON (${text.length} characters)"
    }
    return ExternalActionDetails(
        title = "Import a test script?",
        summary = "$clientName wants to read a versioned script definition into the current test library. Import does not run the script.",
        fields = listOf(
            "Source" to (path ?: source),
            "Definition" to source,
            "Effect" to "Adds one script after validation; no device or shell command is run",
        ),
        allowLabel = "Import script",
        declinedMessage = "The user declined to import the script; the library was not changed.",
    )
}

@Suppress("ReturnCount") // Destination validation returns specific approval guidance for each malformed shape.
private fun describeScriptExportCall(appState: AppState, arguments: Map<String, Any?>, clientName: String): ExternalActionDetails? {
    val id = arguments["scriptId"] as? String ?: return null
    val script = appState.testLibrary.script(id) ?: return null
    val path = arguments["path"] as? String ?: return null
    val destination = runCatching { java.io.File(path) }.getOrNull() ?: return null
    if (!destination.isAbsolute) return null
    val overwrite = arguments["overwrite"] == true
    if (destination.exists() && !overwrite) return null
    return ExternalActionDetails(
        title = "Export a test script?",
        summary = "$clientName wants to write '${script.toolName}' as a versioned JSON file.",
        fields = listOf("Script" to script.toolName, "Destination" to destination.absolutePath, "Replace existing file" to if (overwrite) "Yes" else "No"),
        allowLabel = "Export script",
        declinedMessage = "The user declined to export the script; no file was written.",
    )
}

@Suppress("ReturnCount") // Device/session preflight remains explicit and fail-closed.
private fun describeRecordingStartCall(appState: AppState, arguments: Map<String, Any?>, clientName: String): ExternalActionDetails? {
    val serial = (arguments["deviceSerial"] as? String)?.takeIf(String::isNotBlank) ?: return null
    val suiteId = arguments["suiteId"] as? String ?: return null
    val caseId = arguments["caseId"] as? String ?: return null
    val suite = appState.testLibrary.suite(suiteId) ?: return null
    val targetCase = suite.cases.firstOrNull { it.id == caseId } ?: return null
    if (suite.readOnly || appState.testLibrary.readOnly) return null
    when (decide(appState.testLibrary, LimitOperation.EditCase(suiteId, caseId), appState.editionService.limits())) {
        is LimitDecision.Refused -> return null
        else -> Unit
    }
    return ExternalActionDetails(
        title = "Observe test input from a live mirror?",
        summary =
            "$clientName wants to start or reuse a capture and connect its embedded mirror on $serial, then record accepted user input " +
                "for '${suite.name} / ${targetCase.name}'. Recording observes actions; it does not inject input or drive the device.",
        fields = listOf(
            "Source device" to serial,
            "Target case" to targetCase.name,
            "Capture" to "The app's configured local log/video capture plus ordered actions and bounded screen context",
        ),
        allowLabel = "Start recording",
        declinedMessage = "The user declined to observe mirror input; recording did not start.",
    )
}

@Suppress("CyclomaticComplexMethod", "ReturnCount") // Validate the whole reviewed snapshot before confirmation.
private fun describeRecordingApplyCall(appState: AppState, arguments: Map<String, Any?>, clientName: String): ExternalActionDetails? {
    val sessionId = arguments["sessionId"] as? String ?: return null
    val snapshot = (appState.testStepRecordingSnapshot(sessionId) as? StoreResult.Ok)?.value ?: return null
    if (snapshot.active || snapshot.pendingSnapshots != 0) return null
    val target = appState.testStepRecordingTarget ?: return null
    val suite = appState.testLibrary.suite(target.first) ?: return null
    val targetCase = suite.cases.firstOrNull { it.id == target.second } ?: return null
    if (suite.readOnly || appState.testLibrary.readOnly || appState.isTestStepRecordingApplying(sessionId)) return null
    when (decide(appState.testLibrary, LimitOperation.EditCase(suite.id, targetCase.id), appState.editionService.limits())) {
        is LimitDecision.Refused -> return null
        else -> Unit
    }
    val rows = arguments["steps"] as? List<*> ?: return null
    if (rows.size != snapshot.steps.size || rows.any { it !is Map<*, *> }) return null
    val rowMaps = rows.map { it as Map<*, *> }
    if (rowMaps.any { it["action"] !is String || it["expected"] !is String }) return null
    if (rowMaps.any { it.containsKey("id") } && rowMaps.mapNotNull { it["id"] as? String } != snapshot.steps.map { it.id }) return null
    val screenshotIds = snapshot.steps.filter { it.screenshotJpeg != null }.map { it.id }.toSet()
    if (rowMaps.any { it["useScreenshotAsExpected"] == true && it["id"] !in screenshotIds }) return null
    val actions = rowMaps.map { it["action"] as String }
    val usingImages = rows.count { row -> (row as? Map<*, *>)?.get("useScreenshotAsExpected") == true }
    return ExternalActionDetails(
        title = "Apply reviewed recording steps?",
        summary = "$clientName wants to add ${rows.size} edited step(s) to '${suite.name} / ${targetCase.name}'.",
        fields = listOf(
            "Actions" to actions.take(8).joinToString("\n").ifBlank { "(none)" }.let { if (actions.size > 8) "$it\n+ ${actions.size - 8} more" else it },
            "Expected screenshot choices" to "$usingImages reviewed input-time image(s)",
            "Library change" to "Adds steps at the requested position; no device action is performed",
        ),
        allowLabel = "Apply reviewed steps",
        declinedMessage = "The user declined to apply the recording; the test library was not changed.",
    )
}

private suspend fun describeRerunFailedCall(appState: AppState, arguments: Map<String, Any?>, clientName: String): ExternalActionDetails? {
    val runId = arguments["runId"] as? String ?: return null
    val run = appState.testRunCoordinator.loadRun(runId) ?: return null
    val config = rerunFailedCasesConfig(run).getOrNull() ?: return null
    val suite = appState.testLibrary.suite(config.suiteId) ?: return null
    val selected = config.caseIds.orEmpty()
    if (selected.isEmpty() || selected.any { id -> suite.cases.none { it.id == id } }) return null
    return ExternalActionDetails(
        title = "Re-run failed cases?",
        summary =
            "$clientName wants to run ${selected.size} case(s) that failed, blocked, or errored in ${suite.name}. " +
                "It drives the configured devices and may run scripts.",
        fields = listOfNotNull(
            "Cases" to suite.cases.filter { it.id in selected }.joinToString(", ") { it.name },
            "Lanes" to config.lanes.joinToString("\n") {
                laneLine(appState, it.profileId ?: EXTERNAL_LANE_PROFILE_ID, it.deviceSerial, it.model, it.reasoningEffort)
            },
            "Repeat" to config.repeat.toString(),
            judgeLine(appState, config.judgeProfileId, config.judgeMode, config.judgeModel, config.judgeReasoningEffort)?.let { "Judge" to it },
            config.capture?.let { "Recording" to it.recordingSummary(config.openLaneTabs) },
            "Scripts that may run" to scriptLines(appState.testLibrary, suite),
        ),
        allowLabel = "Start run",
        declinedMessage = DECLINED_RUN_MESSAGE,
    )
}

private fun describeReportExportCall(arguments: Map<String, Any?>, clientName: String): ExternalActionDetails? {
    val runId = (arguments["runId"] as? String)?.takeIf(String::isNotBlank) ?: return null
    val path = (arguments["path"] as? String)?.takeIf(String::isNotBlank) ?: return null
    val format = (arguments["format"] as? String)?.takeIf(String::isNotBlank) ?: return null
    val evidence = (arguments["evidencePaths"] as? List<*>)?.filterIsInstance<String>().orEmpty()
    val overwrite = arguments["overwrite"] == true
    return ExternalActionDetails(
        title = "Export a test run report?",
        summary = "$clientName wants to write a local $format report for run $runId.",
        fields = listOf(
            "Destination" to path,
            "Selected evidence" to if (evidence.isEmpty()) {
                "None"
            } else {
                evidence.take(8).joinToString("\n") + if (evidence.size > 8) "\n+ ${evidence.size - 8} more" else ""
            },
            "Replace existing file" to if (overwrite) "Yes" else "No",
        ),
        allowLabel = "Export file",
        declinedMessage = "The user declined to export the report; no file was written.",
    )
}

private suspend fun describeBugreportCall(appState: AppState, arguments: Map<String, Any?>, clientName: String): ExternalActionDetails? {
    val issueId = arguments["issueId"] as? String ?: return null
    val issue = withContext(Dispatchers.IO) { appState.issueStore.load(issueId) } ?: return null
    if (issue.readOnly) return null
    val serial = issue.draft.environment.deviceSerial.takeIf(String::isNotBlank) ?: return null
    return ExternalActionDetails(
        title = "Collect an Android bugreport?",
        summary = "$clientName wants to collect diagnostics from the source Android device and add them to issue '${issue.draft.title}'.",
        fields = listOf("Device" to serial, "Time limit" to "Five minutes", "Attachment" to "Added unchecked; selection remains yours"),
        allowLabel = "Collect bugreport",
        declinedMessage = "The user declined bugreport collection; the device was not queried.",
    )
}

private suspend fun describeIssueClipCall(appState: AppState, arguments: Map<String, Any?>, clientName: String): ExternalActionDetails? {
    val issueId = arguments["issueId"] as? String ?: return null
    val issue = withContext(Dispatchers.IO) { appState.issueStore.load(issueId) } ?: return null
    if (issue.readOnly) return null
    val run = appState.testRunCoordinator.loadRun(issue.source.runId) ?: return null
    val step = run.stepResult(issue.source.laneId, issue.source.caseId, issue.source.iteration, issue.source.stepId) ?: return null
    val requested = listOfNotNull(
        arguments["startMs"]?.toString()?.let { "Requested start" to "$it ms" },
        arguments["endMs"]?.toString()?.let { "Requested end" to "$it ms" },
    )
    return ExternalActionDetails(
        title = "Export a step video clip?",
        summary = "$clientName wants to save evidence for '${issue.draft.title}' from the source lane recording.",
        fields = listOf("Failed step" to step.action, "Default bounds" to "Five seconds before and after, clamped to recording coverage") + requested +
            listOf("Original recording" to "Kept unchanged", "Issue attachment" to "Added to the selectable checklist"),
        allowLabel = "Export clip",
        declinedMessage = "The user declined to export a video clip; no file was written.",
    )
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
