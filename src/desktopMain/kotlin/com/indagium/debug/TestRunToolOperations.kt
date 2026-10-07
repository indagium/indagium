package com.indagium.debug

import com.indagium.testing.model.DEFAULT_CASE_TOOL_CALL_LIMIT
import com.indagium.testing.model.EXTERNAL_LANE_PROFILE_ID
import com.indagium.testing.model.EvidenceFlags
import com.indagium.testing.model.JudgeMode
import com.indagium.testing.model.LaneConfig
import com.indagium.testing.model.LaneKind
import com.indagium.testing.model.LaneResult
import com.indagium.testing.model.RunConfig
import com.indagium.testing.model.TestRun
import com.indagium.testing.model.summary
import com.indagium.testing.run.PauseDecision
import com.indagium.testing.run.StartRunResult
import com.indagium.testing.run.TestRunReportFormat
import com.indagium.testing.run.availableRunArtifactPaths
import com.indagium.testing.run.compareTestRuns
import com.indagium.testing.run.toMarkdown
import com.indagium.testing.store.runToJson
import com.indagium.ui.AppState
import com.indagium.ui.ReportActionResult
import com.indagium.ui.applyStepFix
import com.indagium.ui.exportTestRunReport
import com.indagium.ui.markAgentError
import com.indagium.ui.rerunFailedCases
import com.indagium.ui.rerunStep
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

// Handlers of the AI test-RUN tools (catalogue: TestRunToolCatalog.kt), merged into IndagiumToolOperations like the
// authoring tools. Every handler returns a plain Map; every expected failure is DATA, `{ "error": message }`, never an
// exception. Everything that waits on the disk or on a run is a suspending handler so no request thread blocks.

private val RUN_JSON_DROPPED_KEYS = setOf("suite", "scripts", "sharedSteps")

internal class TestRunToolOperations(private val appState: AppState) {
    private val coordinator get() = appState.testRunCoordinator

    val handlers: Map<String, (Map<String, Any?>) -> Any?> = mapOf(
        "cancel_test_run" to tool { a -> cancelRun(a.requiredString("runId")) },
        "resolve_test_confirmation" to tool { a -> resolveConfirmation(a) },
        "resume_paused_step" to tool { a -> resume(a) },
    )

    val suspendHandlers: Map<String, suspend (Map<String, Any?>) -> Any?> = mapOf(
        "run_test_suite" to suspendTool { a -> runSuite(a) },
        "get_test_run_status" to suspendTool { a -> status(a.requiredString("runId")) },
        "list_test_runs" to suspendTool { listRuns() },
        "get_test_run_report" to suspendTool { a -> report(a) },
        "test_lane_tool_call" to suspendTool { a -> laneToolCall(a) },
        "apply_step_fix" to suspendTool { a -> applyFix(a) },
        "mark_agent_error" to suspendTool { a -> markError(a) },
        "rerun_test_step" to suspendTool { a -> rerunStep(a) },
        "rerun_failed_test_cases" to suspendTool { a -> rerunFailed(a.requiredString("runId")) },
        "compare_test_runs" to suspendTool { a -> compareRuns(a) },
        "export_test_run_report" to suspendTool { a -> exportReport(a) },
    )

    private fun tool(body: (ToolArgs) -> Any?): (Map<String, Any?>) -> Any? = { raw ->
        try {
            body(ToolArgs(raw))
        } catch (e: ToolArgException) {
            errorMap(e.message ?: "Invalid arguments.")
        }
    }

    private fun suspendTool(body: suspend (ToolArgs) -> Any?): suspend (Map<String, Any?>) -> Any? = { raw ->
        try {
            body(ToolArgs(raw))
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (e: ToolArgException) {
            errorMap(e.message ?: "Invalid arguments.")
        }
    }

    private fun errorMap(message: String): Map<String, Any?> = mapOf("error" to message)

    // ── run_test_suite ───────────────────────────────────────────────

    private suspend fun runSuite(a: ToolArgs): Map<String, Any?> {
        val config = parseConfig(a)
        return startedMap(config, coordinator.start(config))
    }

    /** What run_test_suite and rerun_test_step answer: the new run's ids, or the refusal as data. */
    private fun startedMap(config: RunConfig, started: StartRunResult): Map<String, Any?> = when (started) {
        is StartRunResult.Started -> mapOf(
            "runId" to started.runId,
            "laneIds" to started.laneIds,
            "lanes" to config.lanes.map {
                buildMap {
                    put("laneId", it.id)
                    put("kind", it.kind.name)
                    put("deviceSerial", it.deviceSerial)
                    it.model?.let { model -> put("model", model) }
                    it.reasoningEffort?.let { effort -> put("reasoningEffort", effort) }
                }
            },
            "warnings" to started.warnings,
        )
        is StartRunResult.Rejected -> {
            val limit = started.limit
            buildMap {
                put("error", started.errors.joinToString(" "))
                put("errors", started.errors)
                if (limit != null) {
                    put(
                        "limit",
                        mapOf(
                            "kind" to limit.kind.name,
                            "max" to limit.limit,
                            "edition" to appState.editionService.current.value.name,
                            "hint" to limit.hint,
                        ),
                    )
                }
            }
        }
    }

    private fun parseConfig(a: ToolArgs): RunConfig {
        val lanes = (a.objects("lanes") ?: toolArgError("lanes is required.")).mapIndexed { index, item ->
            val where = "lanes[$index]"
            requireFieldTypes(item, where, strings = setOf("profileId", "deviceSerial", "model", "reasoningEffort"))
            val profileId = (item["profileId"] as? String)?.trim().orEmpty()
            val serial = (item["deviceSerial"] as? String)?.trim().orEmpty()
            if (profileId.isEmpty()) toolArgError("$where.profileId is required (an AI profile id, or \"$EXTERNAL_LANE_PROFILE_ID\").")
            if (serial.isEmpty()) toolArgError("$where.deviceSerial is required.")
            val model = modelOverride(item["model"])
            val effort = effortOverride(item["reasoningEffort"])
            if (profileId.equals(EXTERNAL_LANE_PROFILE_ID, ignoreCase = true)) {
                if (model != null || effort != null) toolArgError("$where: an external lane has no AI model, so model and reasoningEffort do not apply.")
                LaneConfig(kind = LaneKind.EXTERNAL, profileId = null, deviceSerial = serial)
            } else {
                LaneConfig(kind = LaneKind.AGENT_PROFILE, profileId = profileId, deviceSerial = serial, model = model, reasoningEffort = effort)
            }
        }
        val evidence = a.map["evidence"]?.asObject("evidence")
        val defaults = EvidenceFlags()
        val videoHint = if (evidence?.get("video") != null) evidence.flag("video", defaults.video) else null
        val capture = parseRunCapture(a.map["capture"], appState.settings.captureSettings, videoHint)
        return RunConfig(
            suiteId = a.requiredString("suiteId"),
            caseIds = a.strings("caseIds"),
            lanes = lanes,
            repeat = a.int("repeat") ?: 1,
            caseToolCallLimit = a.int("caseToolCallLimit") ?: DEFAULT_CASE_TOOL_CALL_LIMIT,
            evidence = EvidenceFlags(
                video = capture.recordVideo,
                screenshots = evidence.flag("screenshots", defaults.screenshots),
                logcat = evidence.flag("logcat", defaults.logcat),
                transcript = evidence.flag("transcript", defaults.transcript),
            ),
            judgeProfileId = a.string("judgeProfileId")?.trim()?.takeIf { it.isNotEmpty() },
            judgeMode = judgeModeOf(a),
            judgeModel = modelOverride(a.map["judgeModel"]),
            judgeReasoningEffort = effortOverride(a.map["judgeReasoningEffort"]),
            capture = capture,
            openLaneTabs = a.bool("openLaneTabs") ?: true,
        )
    }

    /** A model override: absent or blank is "the profile's own model". Whether the profile accepts it is checked by the run's validation. */
    private fun modelOverride(raw: Any?): String? = when (raw) {
        null -> null
        is String -> raw.trim().takeIf { it.isNotEmpty() }
        else -> toolArgError("model must be a string.")
    }

    /** An effort override: absent is "the profile's own effort", an empty string is "the model's default effort". */
    private fun effortOverride(raw: Any?): String? = when (raw) {
        null -> null
        is String -> raw.trim().lowercase()
        else -> toolArgError("reasoningEffort must be a string.")
    }

    /** The wire name of the judge mode, or a refusal for an unknown one (the codec would read it as OFF and hide the mistake). */
    private fun judgeModeOf(a: ToolArgs): String {
        val raw = a.string("judgeMode")?.trim()?.takeIf { it.isNotEmpty() } ?: return JudgeMode.OFF.wire
        return JudgeMode.parse(raw)?.wire ?: toolArgError("judgeMode must be one of ${JudgeMode.entries.joinToString(", ") { it.wire }}.")
    }

    private fun Map<String, Any?>?.flag(key: String, default: Boolean): Boolean = when (val v = this?.get(key)) {
        null -> default
        is Boolean -> v
        else -> toolArgError("evidence.$key must be true or false.")
    }

    // ── Reading ──────────────────────────────────────────────────────

    private suspend fun status(runId: String): Map<String, Any?> {
        val run = coordinator.loadRun(runId) ?: return errorMap("Run '$runId' was not found.")
        return statusMap(run)
    }

    private fun statusMap(run: TestRun): Map<String, Any?> = buildMap {
        put("runId", run.id)
        put("suiteId", run.suite.id)
        put("suiteName", run.suite.name)
        put("status", run.status.name)
        put("createdAt", run.createdAt)
        put("startedAt", run.startedAt)
        put("finishedAt", run.finishedAt)
        put("warnings", run.warnings)
        run.error?.let { put("error", it) }
        put("lanes", run.lanes.map(::laneMap))
        put("paused", coordinator.isPaused(run.id))
        put("comparisons", run.comparisons.size)
        put(
            "pendingConfirmations",
            coordinator.pendingConfirmations().filter { it.runId == run.id }.map {
                mapOf("confirmationId" to it.confirmationId, "laneId" to it.laneId, "tool" to it.toolName, "description" to it.description)
            },
        )
        put(
            "pausedLanes",
            coordinator.pausedSteps().filter { it.runId == run.id }.map {
                mapOf(
                    "laneId" to it.laneId, "case" to it.step.caseName, "stepNumber" to it.step.stepNumber, "action" to it.step.action,
                    "status" to it.step.status.name, "observation" to it.step.observation,
                )
            },
        )
        put("summary", run.summary().let { mapOf("passedSteps" to it.passedSteps, "totalSteps" to it.totalSteps, "cases" to it.caseCount) })
    }

    private fun laneMap(lane: LaneResult): Map<String, Any?> = buildMap {
        put("laneId", lane.laneId)
        put("kind", lane.config.kind.name)
        put("profileId", lane.config.profileId)
        lane.config.model?.let { put("model", it) }
        lane.config.reasoningEffort?.let { put("reasoningEffort", it) }
        put("deviceSerial", lane.config.deviceSerial)
        put("status", lane.status.name)
        lane.error?.let { put("error", it) }
        lane.currentCase?.let { put("currentCase", it) }
        lane.currentStepNumber?.let { put("currentStep", mapOf("number" to it, "action" to lane.currentStepAction)) }
        put(
            "cases",
            lane.cases.map { case ->
                mapOf(
                    "caseId" to case.caseId, "name" to case.caseName, "iteration" to case.iteration, "status" to case.status?.name,
                    "steps" to case.steps.map { it.status.name },
                )
            },
        )
    }

    private suspend fun listRuns(): Map<String, Any?> = mapOf(
        "runs" to coordinator.listRuns().map {
            mapOf(
                "runId" to it.id, "suiteName" to it.suiteName, "status" to it.status.name, "createdAt" to it.createdAt,
                "finishedAt" to it.finishedAt, "lanes" to it.laneCount, "cases" to it.caseCount,
                "passedSteps" to it.passedSteps, "totalSteps" to it.totalSteps,
            )
        },
    )

    private suspend fun report(a: ToolArgs): Map<String, Any?> {
        val runId = a.requiredString("runId")
        val format = a.string("format")?.trim()?.lowercase() ?: "json"
        if (format != "json" && format != "markdown") toolArgError("format must be json or markdown.")
        val run = coordinator.loadRun(runId) ?: return errorMap("Run '$runId' was not found.")
        val artifactPaths = withContext(Dispatchers.IO) { availableRunArtifactPaths(run, coordinator.runDir(run.id)) }
        return if (format == "markdown") {
            mapOf("runId" to run.id, "format" to "markdown", "status" to run.status.name, "markdown" to run.toMarkdown(), "artifactPaths" to artifactPaths)
        } else {
            val plain = runToJson(run).toPlainMap().filterKeys { it !in RUN_JSON_DROPPED_KEYS }
            mapOf(
                "runId" to run.id,
                "format" to "json",
                "status" to run.status.name,
                "suiteName" to run.suite.name,
                "report" to plain,
                "artifactPaths" to artifactPaths,
            )
        }
    }

    // ── Controlling ──────────────────────────────────────────────────

    private fun cancelRun(runId: String): Map<String, Any?> {
        if (coordinator.run(runId) == null) return errorMap("Run '$runId' is not running in this session.")
        return if (coordinator.cancel(runId)) mapOf("runId" to runId, "cancelling" to true) else errorMap("Run '$runId' is already over.")
    }

    private fun resolveConfirmation(a: ToolArgs): Map<String, Any?> {
        val runId = a.requiredString("runId")
        val id = a.requiredString("confirmationId")
        val allow = a.bool("allow") ?: toolArgError("allow is required.")
        return if (coordinator.resolveConfirmation(runId, id, allow)) {
            mapOf("runId" to runId, "confirmationId" to id, "allowed" to allow)
        } else {
            errorMap("No pending confirmation '$id' in run '$runId'.")
        }
    }

    private fun resume(a: ToolArgs): Map<String, Any?> {
        val runId = a.requiredString("runId")
        val laneId = a.requiredString("laneId")
        val decision = a.enum("decision", PauseDecision.entries) ?: toolArgError("decision is required.")
        return if (coordinator.resumePausedStep(runId, laneId, decision)) {
            mapOf("runId" to runId, "laneId" to laneId, "decision" to decision.name.lowercase())
        } else {
            errorMap("Lane '$laneId' of run '$runId' is not paused.")
        }
    }

    // ── Report actions ───────────────────────────────────────────────

    private fun ReportActionResult.toMap(extra: Map<String, Any?> = emptyMap()): Map<String, Any?> = when (this) {
        is ReportActionResult.Done -> extra + ("message" to message)
        is ReportActionResult.Failed -> buildMap {
            put("error", message)
            limit?.let {
                put(
                    "limit",
                    mapOf("kind" to it.kind.name, "max" to it.limit, "edition" to appState.editionService.current.value.name, "hint" to it.hint),
                )
            }
        }
    }

    private suspend fun applyFix(a: ToolArgs): Map<String, Any?> {
        val runId = a.requiredString("runId")
        val stepId = a.requiredString("stepId")
        val fixRef = a.requiredString("fixRef")
        return appState.applyStepFix(runId, stepId, fixRef).toMap(mapOf("runId" to runId, "stepId" to stepId, "fixRef" to fixRef, "applied" to true))
    }

    private suspend fun markError(a: ToolArgs): Map<String, Any?> {
        val runId = a.requiredString("runId")
        val laneId = a.requiredString("laneId")
        val caseId = a.requiredString("caseId")
        val stepId = a.requiredString("stepId")
        val result = appState.markAgentError(runId, laneId, caseId, a.int("iteration") ?: 1, stepId, a.requiredString("note"))
        return result.toMap(mapOf("runId" to runId, "laneId" to laneId, "caseId" to caseId, "stepId" to stepId, "marked" to true))
    }

    private suspend fun rerunStep(a: ToolArgs): Map<String, Any?> {
        val started = appState.rerunStep(a.requiredString("runId"), a.requiredString("laneId"), a.requiredString("caseId"), a.requiredString("stepId"))
        val config = (started as? StartRunResult.Started)?.let { coordinator.run(it.runId)?.config }
        return startedMap(config ?: RunConfig("", null, emptyList()), started)
    }

    private suspend fun rerunFailed(runId: String): Map<String, Any?> {
        val started = appState.rerunFailedCases(runId)
        val config = (started as? StartRunResult.Started)?.let { coordinator.run(it.runId)?.config }
        return startedMap(config ?: RunConfig("", null, emptyList()), started)
    }

    private suspend fun compareRuns(a: ToolArgs): Map<String, Any?> {
        val currentId = a.requiredString("runId")
        val current = coordinator.loadRun(currentId) ?: return errorMap("Run '$currentId' was not found.")
        val requestedPreviousId = a.string("previousRunId")
        val previous = if (requestedPreviousId != null) {
            coordinator.loadRun(requestedPreviousId) ?: return errorMap("Previous run '$requestedPreviousId' was not found.")
        } else {
            coordinator.previousTerminalRunOfSameSuite(current)
                ?: return errorMap("No previous terminal run of suite '${current.suite.name}' is available.")
        }
        if (!current.isFinished || !previous.isFinished) return errorMap("Compare requires two terminal runs.")
        if (previous.suite.id != current.suite.id) return errorMap("Both runs must belong to the same suite.")
        val rows = compareTestRuns(previous, current).map { row ->
            mapOf(
                "caseId" to row.caseId, "caseName" to row.caseName, "stepId" to row.stepId,
                "presence" to row.presence.name, "casePresence" to row.casePresence.name,
                "definitionChanged" to row.definitionChanged, "caseDefinitionChanged" to row.caseDefinitionChanged,
                "suiteDefinitionChanged" to row.suiteDefinitionChanged,
                "previousAction" to row.previousAction, "currentAction" to row.currentAction,
                "previousExpected" to row.previousExpected, "currentExpected" to row.currentExpected,
                "previousStatuses" to row.previousStatuses, "currentStatuses" to row.currentStatuses,
            )
        }
        return mapOf("previousRunId" to previous.id, "runId" to current.id, "changes" to rows)
    }

    private suspend fun exportReport(a: ToolArgs): Map<String, Any?> {
        val format = when (a.requiredString("format").lowercase()) {
            "json" -> TestRunReportFormat.JSON
            "markdown" -> TestRunReportFormat.MARKDOWN
            "evidence_zip" -> TestRunReportFormat.EVIDENCE_ZIP
            else -> toolArgError("format must be json, markdown, or evidence_zip.")
        }
        val result = appState.exportTestRunReport(
            runId = a.requiredString("runId"),
            destination = a.requiredString("path"),
            format = format,
            evidencePaths = a.strings("evidencePaths") ?: emptyList(),
            overwrite = a.bool("overwrite") ?: false,
        )
        return result.fold(
            onSuccess = { mapOf("path" to it.file.absolutePath, "bytes" to it.bytes, "evidenceFiles" to it.evidenceFiles) },
            onFailure = { errorMap(it.message ?: "Could not export the run report.") },
        )
    }

    private suspend fun laneToolCall(a: ToolArgs): Any? {
        val arguments = a.map["arguments"]?.asObject("arguments") ?: emptyMap()
        return coordinator.laneToolCall(a.requiredString("runId"), a.requiredString("laneId"), a.requiredString("tool"), arguments)
    }
}
