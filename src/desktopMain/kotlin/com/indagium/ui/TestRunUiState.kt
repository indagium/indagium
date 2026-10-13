package com.indagium.ui

import com.indagium.capture.CaptureDevice
import com.indagium.capture.CaptureSettings
import com.indagium.capture.MICROPHONE_DEFAULT_ID
import com.indagium.capture.MICROPHONE_OFF_ID
import com.indagium.model.AiProviderProfile
import com.indagium.testing.model.ALLOWED_RUN_REPEATS
import com.indagium.testing.model.CaseAttemptRecord
import com.indagium.testing.model.CaseResult
import com.indagium.testing.model.CaseStatus
import com.indagium.testing.model.DEFAULT_CASE_TOOL_CALL_LIMIT
import com.indagium.testing.model.DEFAULT_CONFIRMATION_TIMEOUT_MS
import com.indagium.testing.model.EvidenceFlags
import com.indagium.testing.model.JudgeInconclusivePolicy
import com.indagium.testing.model.JudgeMode
import com.indagium.testing.model.JudgeTiming
import com.indagium.testing.model.JudgeVerdict
import com.indagium.testing.model.LaneConfig
import com.indagium.testing.model.LaneKind
import com.indagium.testing.model.MAX_CASE_TOOL_CALL_LIMIT
import com.indagium.testing.model.MAX_STEP_RETRIES
import com.indagium.testing.model.MIN_CASE_TOOL_CALL_LIMIT
import com.indagium.testing.model.ManualStepsOption
import com.indagium.testing.model.RunConfig
import com.indagium.testing.model.RunStatus
import com.indagium.testing.model.RunSummary
import com.indagium.testing.model.SUITE_SETUP_CASE_ID
import com.indagium.testing.model.SUITE_TEARDOWN_CASE_ID
import com.indagium.testing.model.StepResult
import com.indagium.testing.model.StepStatus
import com.indagium.testing.model.TestCase
import com.indagium.testing.model.TestRun
import com.indagium.testing.model.TestingSettings
import com.indagium.testing.model.agentJudgeDisagrees
import com.indagium.testing.model.driverLabel
import com.indagium.testing.model.hasUnresolvedJudgeOutcome
import com.indagium.testing.model.judge
import com.indagium.testing.model.moveById
import com.indagium.testing.model.newLaneId
import com.indagium.testing.run.ComparedCasePresence
import com.indagium.testing.run.ComparedRunStep
import com.indagium.testing.run.ComparedStepPresence
import com.indagium.testing.run.TestRunReportFormat
import com.indagium.testing.run.deviceSharingWarnings
import java.text.SimpleDateFormat
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Date
import java.util.Locale

// UI-free state of the run dialog and the run report: what the dialog offers, how its inputs become a RunConfig, and
// how a run is laid out as a cases x steps x lanes matrix. Everything here is a pure function over plain data, so it is
// unit-tested without Compose (TestRunUiStateTest).

// ── Run dialog ───────────────────────────────────────────────────────

/** What a lane can be driven by: an AI profile, or [profileId] == null for an external (MCP) lane. */
internal data class LaneChoice(val profileId: String?, val label: String) {
    val isExternal: Boolean get() = profileId == null
}

internal const val EXTERNAL_LANE_LABEL = "External (MCP)"

/** The AI profiles in settings order, then the external option. */
internal fun laneChoices(profiles: List<AiProviderProfile>): List<LaneChoice> =
    profiles.map { LaneChoice(it.id, "${it.displayName.ifBlank { it.kind.label }} · ${it.kind.label}") } + LaneChoice(null, EXTERNAL_LANE_LABEL)

internal data class DeviceChoice(val serial: String, val label: String, val sharesLiveCapture: Boolean = false)

/** Ready devices a lane may use. A live manual capture is offered because the lane can borrow it safely. */
internal fun deviceChoices(devices: List<CaptureDevice>, liveCaptureSerial: String?): List<DeviceChoice> =
    devices.filter { it.available }
        .map { DeviceChoice(it.serial, if (it.model == it.serial) it.serial else "${it.model} (${it.serial})", it.serial == liveCaptureSerial) }

/**
 * One lane row of the dialog. [id] is stable so the row can be reordered; it becomes the lane's id in the run. [model] and
 * [reasoningEffort] override the chosen profile's own for this lane (null: the profile's value, see [LaneConfig.model]).
 */
internal data class LaneDraft(
    val id: String = newLaneId(),
    val choice: LaneChoice? = null,
    val deviceSerial: String? = null,
    val model: String? = null,
    val reasoningEffort: String? = null,
    val modelWasDiscovered: Boolean = false,
)

/**
 * The dialog's inputs as the user left them. The first lane is [choice] + [deviceSerial]; the others are [moreLanes], in
 * order ([allLanes] and [withLanes] treat them as one list). A judge is [judgeProfileId] + [judgeMode].
 */
internal data class RunDialogModel(
    val suiteId: String,
    val selectedCaseIds: Set<String>,
    val choice: LaneChoice?,
    val deviceSerial: String?,
    val repeat: Int = 1,
    val toolLimitText: String = DEFAULT_CASE_TOOL_CALL_LIMIT.toString(),
    val evidence: EvidenceFlags = EvidenceFlags(),
    val moreLanes: List<LaneDraft> = emptyList(),
    val judgeProfileId: String? = null,
    val judgeMode: JudgeMode = JudgeMode.OFF,
    val firstLaneId: String = newLaneId(),
    /** From Settings > Testing; the dialog has no control for it. */
    val confirmationTimeoutMs: Long = DEFAULT_CONFIRMATION_TIMEOUT_MS,
    val rerunOf: String? = null,
    /** The first lane's model / effort overrides ([moreLanes] carry their own). */
    val firstLaneModel: String? = null,
    val firstLaneEffort: String? = null,
    val judgeModel: String? = null,
    val judgeReasoningEffort: String? = null,
    /** The recording the lanes do: the saved capture settings, edited for this run only. Null until the dialog has read them. */
    val capture: CaptureSettings? = null,
    val openLaneTabs: Boolean = true,
    val firstLaneModelWasDiscovered: Boolean = false,
    val judgeModelWasDiscovered: Boolean = false,
    /** What the run does with manual steps; the dialog only offers the choice when the suite has some. */
    val manualSteps: ManualStepsOption = ManualStepsOption.USE_SCRIPT_THEN_WAIT,
    val judgeInconclusivePolicy: JudgeInconclusivePolicy = JudgeInconclusivePolicy.BLOCK,
    val judgeTiming: JudgeTiming = JudgeTiming.INLINE,
    val caseRetries: Int = 0,
    val stepRetriesOverride: Int? = null,
) {
    /** Every lane row, first lane first. */
    fun allLanes(): List<LaneDraft> =
        listOf(LaneDraft(firstLaneId, choice, deviceSerial, firstLaneModel, firstLaneEffort, firstLaneModelWasDiscovered)) + moreLanes

    /** The model with [lanes] as its lane rows (at least one: an empty list leaves the model as it is). */
    fun withLanes(lanes: List<LaneDraft>): RunDialogModel {
        val first = lanes.firstOrNull() ?: return this
        return copy(
            choice = first.choice,
            deviceSerial = first.deviceSerial,
            firstLaneId = first.id,
            firstLaneModel = first.model,
            firstLaneEffort = first.reasoningEffort,
            firstLaneModelWasDiscovered = first.modelWasDiscovered,
            moreLanes = lanes.drop(1),
        )
    }

    fun addLane(draft: LaneDraft): RunDialogModel = withLanes(allLanes() + draft)

    /** Removes a lane row; the last one stays. */
    fun removeLane(id: String): RunDialogModel = if (allLanes().size <= 1) this else withLanes(allLanes().filterNot { it.id == id })

    fun moveLane(id: String, toIndex: Int): RunDialogModel = withLanes(allLanes().moveById(id, toIndex) { it.id })

    fun updateLane(id: String, transform: (LaneDraft) -> LaneDraft): RunDialogModel = withLanes(allLanes().map { if (it.id == id) transform(it) else it })

    /** Sentences about lanes sharing a device or more devices than run at once, for the dialog to show. */
    fun deviceWarnings(): List<String> =
        deviceSharingWarnings(allLanes().mapNotNull { draft -> draft.deviceSerial?.let { LaneConfig(draft.id, LaneKind.AGENT_PROFILE, null, it) } })
}

/** The first thing wrong with one lane row, or null. [label] is "Lane 2" style, empty for a single lane. */
private fun laneProblem(draft: LaneDraft, label: String): String? {
    val prefix = if (label.isEmpty()) "" else "$label: "
    return when {
        draft.choice == null -> prefix + (if (label.isEmpty()) "Choose what drives the lane." else "choose what drives the lane.")
        draft.deviceSerial.isNullOrBlank() -> prefix + (if (label.isEmpty()) "Choose a device." else "choose a device.")
        else -> null
    }
}

/** The model as a config, or the first thing the user still has to fix. [allCaseIds] lets "every case" be sent as null. */
internal fun RunDialogModel.toConfig(allCaseIds: List<String>): Result<RunConfig> {
    val drafts = allLanes()
    val laneProblem = drafts.withIndex().firstNotNullOfOrNull { (i, draft) -> laneProblem(draft, if (drafts.size == 1) "" else "Lane ${i + 1}") }
    val unknownCases = selectedCaseIds - allCaseIds.toSet()
    val retriesProblem = retriesProblem()
    val problem = when {
        selectedCaseIds.isEmpty() -> "Choose at least one case."
        unknownCases.isNotEmpty() -> "Some selected cases no longer exist in this suite: ${unknownCases.joinToString()}. " +
            "Refresh the suite and choose current cases."
        laneProblem != null -> laneProblem
        repeat !in ALLOWED_RUN_REPEATS -> "Repeat must be ${ALLOWED_RUN_REPEATS.joinToString(", ")}."
        retriesProblem != null -> retriesProblem
        toolLimitText.trim().toIntOrNull() !in MIN_CASE_TOOL_CALL_LIMIT..MAX_CASE_TOOL_CALL_LIMIT ->
            "The tool-call limit must be a whole number from $MIN_CASE_TOOL_CALL_LIMIT to $MAX_CASE_TOOL_CALL_LIMIT."
        judgeMode != JudgeMode.OFF && judgeProfileId.isNullOrBlank() -> "Choose the AI profile that judges the steps, or turn the judge off."
        else -> null
    }
    if (problem != null) return Result.failure(IllegalArgumentException(problem))
    val lanes = drafts.map { draft ->
        val chosen = checkNotNull(draft.choice)
        LaneConfig(
            id = draft.id,
            kind = if (chosen.isExternal) LaneKind.EXTERNAL else LaneKind.AGENT_PROFILE,
            profileId = chosen.profileId,
            deviceSerial = checkNotNull(draft.deviceSerial),
            model = draft.model?.trim()?.takeIf { it.isNotEmpty() && !chosen.isExternal },
            reasoningEffort = draft.reasoningEffort?.takeIf { !chosen.isExternal },
        )
    }
    val everyCase = selectedCaseIds == allCaseIds.toSet()
    return Result.success(
        RunConfig(
            suiteId = suiteId,
            caseIds = if (everyCase) null else allCaseIds.filter { it in selectedCaseIds },
            lanes = lanes,
            repeat = repeat,
            caseToolCallLimit = toolLimitText.trim().toInt(),
            // The recording section owns "record video"; the evidence flag only says the video is kept as evidence.
            evidence = capture?.let { evidence.copy(video = it.recordVideo) } ?: evidence,
            judgeProfileId = judgeProfileId?.takeIf { judgeMode != JudgeMode.OFF },
            judgeMode = judgeMode.wire,
            confirmationTimeoutMs = confirmationTimeoutMs,
            rerunOf = rerunOf,
            judgeModel = judgeModel?.trim()?.takeIf { it.isNotEmpty() && judgeMode != JudgeMode.OFF },
            judgeReasoningEffort = judgeReasoningEffort?.takeIf { judgeMode != JudgeMode.OFF },
            capture = capture,
            openLaneTabs = openLaneTabs,
            manualSteps = manualSteps,
            judgeInconclusivePolicy = judgeInconclusivePolicy,
            judgeTiming = judgeTiming,
            caseRetries = caseRetries,
            stepRetriesOverride = stepRetriesOverride,
        ),
    )
}

private fun RunDialogModel.retriesProblem(): String? = when {
    caseRetries !in 0..2 -> "Automatic case retries must be between 0 and 2."
    stepRetriesOverride?.let { it !in 0..MAX_STEP_RETRIES } == true ->
        "Step retry override must be between 0 and $MAX_STEP_RETRIES."
    else -> null
}

/** Prefills a rerun dialog from the original run while assigning new lane ids for this new run. */
internal fun RunDialogModel.withRunConfig(config: RunConfig, choices: List<LaneChoice>): RunDialogModel {
    val lanes = config.lanes.map { lane ->
        LaneDraft(
            id = newLaneId(),
            choice = choices.firstOrNull { it.profileId == lane.profileId },
            deviceSerial = lane.deviceSerial,
            model = lane.model,
            reasoningEffort = lane.reasoningEffort,
        )
    }
    val first = lanes.firstOrNull() ?: allLanes().first()
    return copy(
        suiteId = config.suiteId,
        selectedCaseIds = config.caseIds?.toSet() ?: selectedCaseIds,
        choice = first.choice,
        deviceSerial = first.deviceSerial,
        repeat = config.repeat,
        toolLimitText = config.caseToolCallLimit.toString(),
        evidence = config.evidence,
        moreLanes = lanes.drop(1),
        judgeProfileId = config.judgeProfileId,
        judgeMode = JudgeMode.parse(config.judgeMode) ?: JudgeMode.OFF,
        firstLaneId = first.id,
        confirmationTimeoutMs = config.confirmationTimeoutMs,
        rerunOf = config.rerunOf,
        firstLaneModel = first.model,
        firstLaneEffort = first.reasoningEffort,
        firstLaneModelWasDiscovered = first.modelWasDiscovered,
        judgeModel = config.judgeModel,
        judgeReasoningEffort = config.judgeReasoningEffort,
        capture = config.capture ?: capture,
        openLaneTabs = config.openLaneTabs,
        manualSteps = config.manualSteps,
        judgeInconclusivePolicy = config.judgeInconclusivePolicy,
        judgeTiming = config.judgeTiming,
        caseRetries = config.caseRetries,
        stepRetriesOverride = config.stepRetriesOverride,
    )
}

/**
 * The state a new run dialog starts from: the evidence, judge and confirmation timeout the user chose in Settings > Testing. A
 * default judge whose AI profile no longer exists in [profiles] is ignored (the judge starts off).
 */
internal fun RunDialogModel.withTestingDefaults(testing: TestingSettings, profiles: List<AiProviderProfile>): RunDialogModel {
    val judgeProfile = testing.defaultJudgeProfileId?.takeIf { id -> profiles.any { it.id == id } }
    val mode = if (judgeProfile == null) JudgeMode.OFF else testing.judgeMode
    return copy(
        evidence = testing.evidence,
        judgeProfileId = judgeProfile,
        judgeMode = mode,
        confirmationTimeoutMs = testing.confirmationTimeoutMs,
    )
}

/**
 * The recording a new run dialog starts from: the user's saved capture settings (what a manual live capture would use), with video
 * also on when Settings > Testing keeps video as default evidence. Edits in the dialog change this run only.
 */
internal fun RunDialogModel.withCaptureDefaults(saved: CaptureSettings, testing: TestingSettings): RunDialogModel =
    copy(capture = saved.copy(recordVideo = saved.recordVideo || testing.evidence.video))

// ── Run dialog summaries ─────────────────────────────────────────────

private fun stepCountLabel(count: Int): String = if (count == 1) "1 step" else "$count steps"

/**
 * The "What" row of the run dialog: "All 5 cases · 27 steps", the case's name when one is chosen, or "2 of 5 cases · 9 steps".
 * [cases] are the cases the run could include (locked ones left out); selected ids outside them do not count.
 */
internal fun runCasesSummary(cases: List<TestCase>, selectedIds: Set<String>): String {
    val chosen = cases.filter { it.id in selectedIds }
    if (chosen.isEmpty()) return "No cases selected"
    val head = when {
        chosen.size == 1 -> chosen.single().name.ifBlank { "Untitled case" }
        chosen.size == cases.size -> "All ${chosen.size} cases"
        else -> "${chosen.size} of ${cases.size} cases"
    }
    return "$head · ${stepCountLabel(chosen.sumOf { it.steps.size })}"
}

/**
 * The "Record" row of the run dialog in one line, for example "video to file · device audio · mic off · no earlier logs". Without
 * [capture] (the dialog has not read the saved settings yet) it says so; [openLaneTabs] off is the only tab state worth a mention.
 */
internal fun recordingSummary(capture: CaptureSettings?, openLaneTabs: Boolean): String {
    if (capture == null) return "Recording as in Settings"
    val parts = buildList {
        add(if (capture.recordVideo) "video to file" else "no video")
        add(if (capture.audio) "device audio" else "no device audio")
        add(
            when (capture.microphoneDeviceId) {
                MICROPHONE_OFF_ID -> "mic off"
                MICROPHONE_DEFAULT_ID -> "mic: system default"
                else -> "mic on"
            },
        )
        add(if (capture.includeBufferedLogs) "earlier logs included" else "no earlier logs")
        if (capture.audio && capture.keepDeviceAudio) add("sound kept on the device")
        if (!openLaneTabs) add("no live tab per lane")
    }
    return parts.joinToString(" · ")
}

private fun ManualStepsOption.shortLabel(): String = when (this) {
    ManualStepsOption.USE_SCRIPT_THEN_WAIT -> "script, else wait for tester"
    ManualStepsOption.WAIT_FOR_TESTER -> "wait for tester"
    ManualStepsOption.SKIP -> "skip"
}

/**
 * The "More" row of the run dialog in one line: retries, repeat, a tool-call limit that is not the default, the evidence kept and,
 * when the suite has manual steps ([showManualSteps]), what the run does with them. For example
 * "retries 1 · repeat 1× · evidence: screenshots, transcript · manual steps: wait for tester".
 */
internal fun moreSummary(model: RunDialogModel, showManualSteps: Boolean): String {
    val parts = buildList {
        add(
            "retries ${model.caseRetries}" + when (val steps = model.stepRetriesOverride) {
                null -> ""
                0 -> " · step retries off"
                else -> " · step retries $steps"
            },
        )
        add("repeat ${model.repeat}×")
        val limit = model.toolLimitText.trim()
        if (limit != DEFAULT_CASE_TOOL_CALL_LIMIT.toString()) add("$limit tool calls per case")
        val kept = listOfNotNull("screenshots".takeIf { model.evidence.screenshots }, "transcript".takeIf { model.evidence.transcript })
        add(if (kept.isEmpty()) "no extra evidence" else "evidence: ${kept.joinToString(", ")}")
        if (showManualSteps) add("manual steps: ${model.manualSteps.shortLabel()}")
    }
    return parts.joinToString(" · ")
}

// ── Report export ────────────────────────────────────────────────────

/** The label of an export format in the dialog's segmented control. */
internal fun TestRunReportFormat.label(): String = when (this) {
    TestRunReportFormat.JSON -> "JSON"
    TestRunReportFormat.MARKDOWN -> "Markdown"
    TestRunReportFormat.EVIDENCE_ZIP -> "Evidence ZIP"
}

private fun TestRunReportFormat.extension(): String = when (this) {
    TestRunReportFormat.JSON -> "json"
    TestRunReportFormat.MARKDOWN -> "md"
    TestRunReportFormat.EVIDENCE_ZIP -> "zip"
}

private const val EXPORT_NAME_MAX_CHARS = 60
private const val EXPORT_STAMP_PATTERN = "yyyyMMdd-HHmm"

/** A file name for an exported report: the suite's name reduced to safe characters, the run's start time and the format's extension. */
internal fun reportExportFileName(suiteName: String, runMoment: Long, format: TestRunReportFormat, zone: ZoneId = ZoneId.systemDefault()): String {
    val safe = suiteName.trim().replace(Regex("[^A-Za-z0-9._-]+"), "_").trim('_', '.').take(EXPORT_NAME_MAX_CHARS).ifBlank { "test_run" }
    val stamp = DateTimeFormatter.ofPattern(EXPORT_STAMP_PATTERN, Locale.ROOT).format(Instant.ofEpochMilli(runMoment).atZone(zone))
    return "${safe}_$stamp.${format.extension()}"
}

/** [path] with the extension of another export format swapped for [format]'s; any other path (or none) is left as typed. */
internal fun withReportExtension(path: String, format: TestRunReportFormat): String {
    val known = TestRunReportFormat.entries.map { ".${it.extension()}" }
    val current = known.firstOrNull { path.endsWith(it, ignoreCase = true) } ?: return path
    return path.dropLast(current.length) + ".${format.extension()}"
}

// ── Report ───────────────────────────────────────────────────────────

internal fun RunStatus.label(): String = when (this) {
    RunStatus.QUEUED -> "Queued"
    RunStatus.RUNNING -> "Running"
    RunStatus.PASSED -> "Passed"
    RunStatus.FAILED -> "Failed"
    RunStatus.CANCELLED -> "Cancelled"
    RunStatus.ERROR -> "Error"
}

internal fun StepStatus.label(): String = when (this) {
    StepStatus.PASS -> "Pass"
    StepStatus.FAIL -> "Fail"
    StepStatus.BLOCKED -> "Blocked"
    StepStatus.TIMEOUT -> "Timeout"
    StepStatus.SKIPPED -> "Skipped"
    StepStatus.ERROR -> "Error"
}

internal fun CaseStatus?.label(): String = when (this) {
    null -> "Running"
    CaseStatus.PASS -> "Pass"
    CaseStatus.FAIL -> "Fail"
    CaseStatus.BLOCKED -> "Blocked"
    CaseStatus.SKIPPED -> "Skipped"
    CaseStatus.CANCELLED -> "Cancelled"
    CaseStatus.ERROR -> "Error"
}

/** "Cancelled" for a step a cancel interrupted (its stored status is ERROR), else the status' own label. */
internal fun StepResult.statusLabel(): String = if (interrupted) "Cancelled" else status.label()

/** The advice a judge gave a step that timed out, in words ("judge: expected result met"); null for any other step. */
internal fun StepResult.timeoutAdviceLabel(): String? {
    if (status != StepStatus.TIMEOUT) return null
    return when (judge?.verdict) {
        JudgeVerdict.PASS -> "judge: expected result met"
        JudgeVerdict.FAIL -> "judge: expected result not met"
        JudgeVerdict.INCONCLUSIVE -> "judge: inconclusive"
        null -> null
    }
}

/** The label of a step's chip in the matrix: kept short for a narrow lane column (a timed-out step adds whether its result looked met). */
internal fun StepResult.matrixLabel(): String {
    if (status == StepStatus.PASS && executionStatus == StepStatus.TIMEOUT) return "Pass · recovered"
    val advice = when (judge?.verdict.takeIf { status == StepStatus.TIMEOUT }) {
        JudgeVerdict.PASS -> " · met"
        JudgeVerdict.FAIL -> " · unmet"
        else -> ""
    }
    return statusLabel() + advice
}

/** The full label ("Timeout · judge: expected result met") for the inspector. */
internal fun StepResult.detailLabel(): String =
    if (status == StepStatus.PASS && executionStatus == StepStatus.TIMEOUT) "Pass · recovered after timeout"
    else listOfNotNull(statusLabel(), timeoutAdviceLabel()).joinToString(" · ")

private const val TIME_PATTERN = "yyyy-MM-dd HH:mm"

internal fun formatRunTime(millis: Long?): String = millis?.let { SimpleDateFormat(TIME_PATTERN, Locale.ROOT).format(Date(it)) } ?: "—"

/** A one-line description of a run for the list. */
internal fun RunSummary.line(): String = "$passedSteps/$totalSteps steps" +
    (if (pendingVerificationSteps > 0) " · $pendingVerificationSteps awaiting judge" else "") +
    (if (flaky) " · flaky recovery" else "") + " · $caseCount case(s) · $laneCount lane(s)"

/** What a lane is doing right now, for the in-progress view. */
internal fun TestRun.progressLine(laneId: String): String {
    val lane = lane(laneId) ?: return ""
    val place = listOfNotNull(
        lane.currentCase,
        lane.currentStepNumber?.let { "step $it" },
        lane.currentStepAction?.takeIf { it.isNotBlank() }?.let { "“${it.take(PROGRESS_ACTION_CHARS)}”" },
    ).joinToString(", ")
    return "${lane.config.deviceSerial} · ${lane.status.label()}" + if (place.isNotEmpty() && lane.status == RunStatus.RUNNING) " · $place" else ""
}

private const val PROGRESS_ACTION_CHARS = 60

/**
 * The report's re-run button: "Re-run failed (2)", "Re-run interrupted (1)" for cases a cancel stopped, "Re-run failed and
 * interrupted (3)" for both; null when there is nothing to re-run.
 */
internal fun rerunButtonLabel(failed: Int, interrupted: Int): String? = when {
    failed > 0 && interrupted > 0 -> "Re-run failed and interrupted (${failed + interrupted})"
    interrupted > 0 -> "Re-run interrupted ($interrupted)"
    failed > 0 -> "Re-run failed ($failed)"
    else -> null
}

/** A cell of the matrix: what one lane did, or null when the lane has not got there yet. */
internal data class MatrixStepCell(val laneId: String, val result: StepResult?)

internal data class MatrixCaseCell(val laneId: String, val status: CaseStatus?, val present: Boolean, val flaky: Boolean = false, val attempts: Int = 1)

/** One row of the report matrix, in display order. */
internal sealed interface MatrixRow {
    /** The header of a case run (or of the suite's setup / teardown hooks). */
    data class Case(
        val key: String,
        val caseId: String,
        val name: String,
        val iteration: Int,
        val cells: List<MatrixCaseCell>,
        /** How many attempts the case took on its busiest lane, and which of them the rows below show (see [viewingAttempts]). */
        val attemptCount: Int = 1,
        val viewingAttempt: Int = 1,
        /** The case status of each attempt by number (a running case has null), for the attempt switcher's choices. */
        val attemptStatuses: Map<Int, CaseStatus?> = emptyMap(),
    ) : MatrixRow

    /** One step of the frozen suite (or one hook step), with the result of every lane. */
    data class Step(
        val key: String,
        val stepId: String,
        val number: Int,
        val action: String,
        val setup: Boolean,
        val cells: List<MatrixStepCell>,
        val caseId: String = "",
        val iteration: Int = 1,
    ) : MatrixRow
}

internal enum class TestRunReportFilter(val label: String) {
    FAILURES("Failures"),
    BLOCKED_OR_ERROR("Blocked / error"),
    DISAGREEMENTS("Disagreements"),
    UNRESOLVED_JUDGING("Unresolved judging"),
}

/** True when a step row of [run] matches [filter] (any lane's result for it, or the cross-lane / judge state of the row). */
private fun stepRowMatches(run: TestRun, row: MatrixRow.Step, filter: TestRunReportFilter): Boolean {
    val results = row.cells.mapNotNull { it.result }
    return when (filter) {
        TestRunReportFilter.FAILURES -> results.any { it.status in setOf(StepStatus.FAIL, StepStatus.TIMEOUT) }
        TestRunReportFilter.BLOCKED_OR_ERROR -> results.any { it.status in setOf(StepStatus.BLOCKED, StepStatus.ERROR) }
        TestRunReportFilter.DISAGREEMENTS -> consensusFor(run, row).tone == ConsensusTone.DISAGREE || results.any { it.agentJudgeDisagrees() }
        TestRunReportFilter.UNRESOLVED_JUDGING -> results.any { it.hasUnresolvedJudgeOutcome() }
    }
}

/** How many step rows of [run] each filter matches (a row counts for every filter it matches); zero counts are present too. */
internal fun reportFilterCounts(run: TestRun): Map<TestRunReportFilter, Int> {
    val steps = buildMatrix(run).filterIsInstance<MatrixRow.Step>()
    return TestRunReportFilter.entries.associateWith { filter -> steps.count { stepRowMatches(run, it, filter) } }
}

/** Keeps matching rows and their case headers so a filtered report remains navigable. Multiple filters are ORed. */
internal fun filterReportRows(run: TestRun, filters: Set<TestRunReportFilter>): List<MatrixRow> {
    val rows = buildMatrix(run)
    if (filters.isEmpty()) return rows
    val matchingSteps = rows.filterIsInstance<MatrixRow.Step>().filter { row -> filters.any { stepRowMatches(run, row, it) } }
    val matchingKeys = matchingSteps.map { it.caseId to it.iteration }.toSet()
    return rows.filter { row ->
        when (row) {
            is MatrixRow.Step -> row in matchingSteps
            is MatrixRow.Case -> {
                (row.caseId to row.iteration) in matchingKeys || filters.any { filter ->
                    when (filter) {
                        TestRunReportFilter.FAILURES -> row.cells.any { it.status == CaseStatus.FAIL }
                        TestRunReportFilter.BLOCKED_OR_ERROR -> row.cells.any { it.status in setOf(CaseStatus.BLOCKED, CaseStatus.ERROR) }
                        TestRunReportFilter.DISAGREEMENTS, TestRunReportFilter.UNRESOLVED_JUDGING -> false
                    }
                }
            }
        }
    }
}

private fun List<com.indagium.testing.model.LaneResult>.caseOf(laneIndex: Int, caseId: String, iteration: Int): CaseResult? =
    getOrNull(laneIndex)?.cases?.firstOrNull { it.caseId == caseId && it.iteration == iteration }

/**
 * Lays [run] out as a matrix: suite setup, then every case of the frozen suite (per iteration that any lane reached),
 * then suite teardown. Each case row is followed by its steps (setup hook steps first, flagged), with one cell per lane.
 * Cases a lane has no result for yet show an empty cell, so a run in progress already has its full shape.
 */
internal fun buildMatrix(run: TestRun): List<MatrixRow> {
    val rows = ArrayList<MatrixRow>()
    hookRows(run, SUITE_SETUP_CASE_ID, "Suite setup")?.let { rows += it }
    val selected = run.config.caseIds
    val cases = run.suite.cases.filter { selected == null || it.id in selected }
    for (iteration in 1..run.config.repeat) {
        for (case in cases) {
            val reached = run.lanes.indices.any { run.lanes.caseOf(it, case.id, iteration) != null }
            if (!reached && iteration > 1) continue
            val key = "${case.id}#$iteration"
            val results = run.lanes.indices.mapNotNull { run.lanes.caseOf(it, case.id, iteration) }
            val attemptCount = results.maxOfOrNull { it.attemptTotal() } ?: 1
            rows += MatrixRow.Case(
                key, case.id, case.name, iteration,
                run.lanes.mapIndexed { i, lane ->
                    val result = run.lanes.caseOf(i, case.id, iteration)
                    MatrixCaseCell(lane.laneId, result?.status, result != null, result?.flaky == true, result?.caseAttempt ?: 1)
                },
                attemptCount,
                results.firstOrNull { it.attemptTotal() == attemptCount }?.caseAttempt ?: 1,
                results.firstOrNull { it.attemptTotal() == attemptCount }?.attemptStatuses().orEmpty(),
            )
            val hookSteps = run.lanes.indices.flatMap { run.lanes.caseOf(it, case.id, iteration)?.steps.orEmpty() }.filter { it.setup }
            hookSteps.distinctBy { it.stepId }.forEach { rows += hookStepRow(run, key, case.id, iteration, it) }
            case.steps.forEachIndexed { index, step ->
                rows += MatrixRow.Step(
                    "$key/${step.id}", step.id, index + 1, step.action, false,
                    run.lanes.mapIndexed { i, lane ->
                        MatrixStepCell(lane.laneId, run.lanes.caseOf(i, case.id, iteration)?.steps?.firstOrNull { it.stepId == step.id && !it.setup })
                    },
                    case.id, iteration,
                )
            }
        }
    }
    hookRows(run, SUITE_TEARDOWN_CASE_ID, "Suite teardown")?.let { rows += it }
    return rows
}

/** How many attempts a case result stands for: the shown one and those before it. */
private fun CaseResult.attemptTotal(): Int = maxOf(caseAttempt, priorAttempts.size + 1)

private fun CaseResult.attemptStatuses(): Map<Int, CaseStatus?> =
    priorAttempts.associate { it.attempt to (it.status as CaseStatus?) } + (caseAttempt to status)

/** The key of a case row in the attempt choices of [viewingAttempts]: the case and its repeat. */
internal fun caseAttemptKey(caseId: String, iteration: Int): String = "$caseId#$iteration"

/**
 * [run] as it looked at the attempts the reader picked ([chosen] maps [caseAttemptKey] to an attempt number): each case that was
 * retried shows that attempt's status and steps, and the other attempts (the latest one included) move into its earlier attempts.
 * A case without that attempt, and every case not named, stays as it is, so the default view is the latest attempt.
 */
internal fun TestRun.viewingAttempts(chosen: Map<String, Int>): TestRun {
    if (chosen.isEmpty()) return this
    return copy(
        lanes = lanes.map { lane ->
            lane.copy(
                cases = lane.cases.map { case ->
                    val wanted = chosen[caseAttemptKey(case.caseId, case.iteration)]
                    if (wanted == null || wanted == case.caseAttempt) case else case.atAttempt(wanted)
                },
            )
        },
    )
}

private fun CaseResult.atAttempt(number: Int): CaseResult {
    val record = priorAttempts.firstOrNull { it.attempt == number } ?: return this
    val latest = CaseAttemptRecord(
        caseId, iteration, caseAttempt, status ?: CaseStatus.ERROR, steps, startedAt, finishedAt, note, agentUsage, judgeUsage, externalToolCalls,
    )
    return copy(
        status = record.status,
        steps = record.steps,
        startedAt = record.startedAt,
        finishedAt = record.finishedAt,
        note = record.note,
        agentUsage = record.agentUsage,
        judgeUsage = record.judgeUsage,
        externalToolCalls = record.externalToolCalls,
        caseAttempt = number,
        priorAttempts = (priorAttempts - record + latest).sortedBy { it.attempt },
        flaky = false,
    )
}

private fun hookStepRow(run: TestRun, key: String, caseId: String, iteration: Int, sample: StepResult) = MatrixRow.Step(
    "$key/${sample.stepId}", sample.stepId, sample.stepNumber, sample.action, true,
    run.lanes.mapIndexed { i, lane -> MatrixStepCell(lane.laneId, run.lanes.caseOf(i, caseId, iteration)?.steps?.firstOrNull { it.stepId == sample.stepId }) },
    caseId, iteration,
)

private fun hookRows(run: TestRun, caseId: String, title: String): List<MatrixRow>? {
    val results = run.lanes.mapIndexed { i, _ -> run.lanes.caseOf(i, caseId, 1) }
    if (results.all { it == null }) return null
    val rows = ArrayList<MatrixRow>()
    rows += MatrixRow.Case(caseId, caseId, title, 1, run.lanes.mapIndexed { i, lane -> MatrixCaseCell(lane.laneId, results[i]?.status, results[i] != null) })
    results.filterNotNull().flatMap { it.steps }.distinctBy { it.stepId }.forEach { rows += hookStepRow(run, caseId, caseId, 1, it) }
    return rows
}

/** Where a step result's screenshot lives, or null. [runDir] is the run's folder; the stored path is relative to it. */
internal fun StepResult.screenshotFile(runDir: java.io.File): java.io.File? =
    screenshotPath?.takeIf { it.isNotBlank() }?.let { java.io.File(runDir, it) }?.takeIf { it.isFile }

private const val BYTES_PER_KB = 1024
const val LOG_EXCERPT_MAX_BYTES = 8 * BYTES_PER_KB

/**
 * The log bytes of a step ([start] until [end]) as text, at most [maxBytes] of them (the tail is cut and says so).
 * Null when the log or the range is not available. The text is untrusted data: callers show it, never act on it.
 */
internal fun readLogExcerpt(logFile: java.io.File, start: Long?, end: Long?, maxBytes: Int = LOG_EXCERPT_MAX_BYTES): String? {
    if (start == null || end == null || end < start || !logFile.isFile) return null
    val available = (minOf(end, logFile.length()) - start).coerceAtLeast(0L)
    if (available == 0L) return ""
    val toRead = minOf(available, maxBytes.toLong()).toInt()
    val buffer = ByteArray(toRead)
    java.io.RandomAccessFile(logFile, "r").use { raf ->
        raf.seek(start)
        raf.readFully(buffer)
    }
    val text = String(buffer, Charsets.UTF_8)
    return if (available > maxBytes) "$text\n… (${available - maxBytes} more bytes)" else text
}

// ── Verdict, meta line and selection ─────────────────────────────────

/** The one-sentence outcome shown at the top of a report. [headline] starts with the status ("Failed — …"). */
internal data class RunVerdict(val status: RunStatus, val headline: String)

private val BAD_STEP_STATUSES = setOf(StepStatus.FAIL, StepStatus.BLOCKED, StepStatus.TIMEOUT, StepStatus.ERROR)
private val BAD_CASE_STATUSES = setOf(CaseStatus.FAIL, CaseStatus.BLOCKED, CaseStatus.ERROR, CaseStatus.CANCELLED)
private const val VERDICT_ACTION_CHARS = 60
private const val VERDICT_ERROR_CHARS = 140

/** The first thing that did not pass, in matrix order: a step of a case (on [laneId]) or, when no step explains it, the case itself. */
internal class FirstFailure(
    val caseName: String,
    val step: MatrixRow.Step?,
    val laneId: String?,
    val stepStatus: StepStatus?,
    val caseStatus: CaseStatus?,
)

/**
 * The first failing step (FAIL, BLOCKED, TIMEOUT or ERROR) in matrix order, the lowest lane first within a row. A case that did
 * not pass without any failing step (for example one cancelled before it began) counts at its own position. Null when nothing failed.
 */
internal fun firstFailure(rows: List<MatrixRow>): FirstFailure? {
    val sections = ArrayList<Pair<MatrixRow.Case, ArrayList<MatrixRow.Step>>>()
    rows.forEach { row ->
        when (row) {
            is MatrixRow.Case -> sections += row to ArrayList()
            is MatrixRow.Step -> sections.lastOrNull()?.second?.add(row)
        }
    }
    for ((header, steps) in sections) {
        val name = header.name + if (header.iteration > 1) " (run ${header.iteration})" else ""
        for (step in steps) {
            val cell = step.cells.firstOrNull { cell -> cell.result?.let { it.status in BAD_STEP_STATUSES && !it.interrupted } == true } ?: continue
            return FirstFailure(name, step, cell.laneId, cell.result?.status, null)
        }
        header.cells.firstOrNull { it.status in BAD_CASE_STATUSES }?.let { return FirstFailure(name, null, null, null, it.status) }
    }
    return null
}

private fun StepStatus.verb(): String = when (this) {
    StepStatus.FAIL -> "failed"
    StepStatus.BLOCKED -> "blocked"
    StepStatus.TIMEOUT -> "timed out"
    StepStatus.ERROR -> "hit an error"
    StepStatus.PASS -> "passed"
    StepStatus.SKIPPED -> "was skipped"
}

private fun CaseStatus.verb(): String = when (this) {
    CaseStatus.FAIL -> "failed"
    CaseStatus.BLOCKED -> "blocked"
    CaseStatus.ERROR -> "hit an error"
    CaseStatus.CANCELLED -> "was cancelled"
    CaseStatus.PASS -> "passed"
    CaseStatus.SKIPPED -> "was skipped"
}

private fun String.oneLine(limit: Int): String {
    val flat = lineSequence().map(String::trim).firstOrNull(String::isNotEmpty).orEmpty()
    return if (flat.length > limit) flat.take(limit).trimEnd() + "…" else flat
}

private fun stepsText(count: Int): String = if (count == 1) "1 step" else "$count steps"

private fun FirstFailure.sentence(): String {
    val step = step
    if (step == null) return "$caseName ${caseStatus?.verb() ?: "did not pass"}"
    val action = step.action.oneLine(VERDICT_ACTION_CHARS)
    return "$caseName ${stepStatus?.verb() ?: "did not pass"} at step ${step.number}" + if (action.isEmpty()) "" else " “$action”"
}

/** The error text of a run that ended with an error: the run's own, else a lane's (named when there are several lanes). */
private fun errorText(run: TestRun): String {
    run.error?.takeIf { it.isNotBlank() }?.let { return it.oneLine(VERDICT_ERROR_CHARS) }
    run.lanes.forEachIndexed { index, lane ->
        lane.error?.takeIf { it.isNotBlank() }?.let { return (if (run.lanes.size > 1) "Lane ${index + 1}: " else "") + it.oneLine(VERDICT_ERROR_CHARS) }
    }
    return "the run ended with an error"
}

/**
 * The outcome of [run] in one sentence. Passed: all steps passed; Failed / Cancelled / Error name what went wrong first (the
 * first failing case and step in matrix order, the steps that ran before a cancel, the run's or a lane's error); Running and Queued
 * say how far along the run is.
 */
internal fun runVerdict(run: TestRun): RunVerdict {
    val rows = buildMatrix(run)
    val steps = rows.filterIsInstance<MatrixRow.Step>()
    val ran = steps.count { row -> row.cells.any { it.result != null } }
    val prefix = "${run.status.label()} — "
    val body = when (run.status) {
        RunStatus.PASSED -> {
            val passing = steps.count { row -> row.cells.any { it.result != null && it.result.status != StepStatus.SKIPPED } }
            val skipped = ran - passing
            val lanes = if (run.lanes.size > 1) " on ${run.lanes.size} lanes" else ""
            when {
                ran == 0 -> "no steps were run"
                else -> "all ${stepsText(passing)} passed$lanes" + if (skipped > 0) " ($skipped skipped)" else ""
            }
        }
        RunStatus.FAILED -> firstFailure(rows)?.sentence() ?: run.error?.takeIf { it.isNotBlank() }?.oneLine(VERDICT_ERROR_CHARS) ?: "see the results below"
        RunStatus.CANCELLED -> cancelledAfterRetryText(run) ?: if (ran == 0) "stopped before any step ran" else "stopped after $ran of ${stepsText(steps.size)}"
        RunStatus.ERROR -> errorText(run)
        RunStatus.RUNNING -> "$ran of ${stepsText(steps.size)} done"
        RunStatus.QUEUED -> "waiting to start"
    }
    return RunVerdict(run.status, prefix + body)
}

private fun StepResult.failedForReport(): Boolean = !interrupted && status in BAD_STEP_STATUSES

/** A step the judge failed although the agent's own outcome was a pass: the judge, not the agent, made it a failure. */
private fun StepResult.failedByJudge(): Boolean = status == StepStatus.FAIL && judge?.verdict == JudgeVerdict.FAIL && executionStatus == StepStatus.PASS

private fun naturalList(items: List<String>): String = when (items.size) {
    0, 1 -> items.joinToString()
    else -> items.dropLast(1).joinToString(", ") + " and " + items.last()
}

/** "attempt 1 failed at steps 2 and 7 (judge)": what an earlier, completed attempt of a case got wrong. */
private fun CaseAttemptRecord.failureSentence(): String {
    val failing = steps.filter { !it.setup && it.failedForReport() }
    val head = "attempt $attempt ${status.verb()}"
    if (failing.isEmpty()) return head
    val allJudge = failing.all { it.failedByJudge() }
    val numbers = failing.map { step -> step.stepNumber.toString() + if (!allJudge && step.failedByJudge()) " (judge)" else "" }
    return head + (if (failing.size == 1) " at step " else " at steps ") + naturalList(numbers) + if (allJudge) " (judge)" else ""
}

/**
 * The verdict body of a run cancelled while a case was on a retry: where the cancel stopped it and what the attempts before it got
 * wrong ("stopped during attempt 2 at step 3; attempt 1 failed at steps 2 and 7 (judge)"). Null when no case was retried.
 */
internal fun cancelledAfterRetryText(run: TestRun): String? {
    val case = run.lanes.flatMap { it.cases }.firstOrNull { it.priorAttempts.isNotEmpty() && it.status == CaseStatus.CANCELLED } ?: return null
    val stopped = case.steps.lastOrNull { it.interrupted } ?: case.steps.lastOrNull { !it.setup }
    val where = stopped?.let { " at step ${it.stepNumber}" } ?: " before its first step"
    val earlier = case.priorAttempts.sortedBy { it.attempt }.map { it.failureSentence() }
    return (listOf("stopped during attempt ${case.caseAttempt}$where") + earlier).joinToString("; ")
}

/** The glyph in front of a verdict: ✓ passed, ✕ failed, ! error, ■ cancelled, ● running or queued. */
internal fun verdictGlyph(status: RunStatus): String = when (status) {
    RunStatus.PASSED -> "✓"
    RunStatus.FAILED -> "✕"
    RunStatus.ERROR -> "!"
    RunStatus.CANCELLED -> "■"
    RunStatus.RUNNING, RunStatus.QUEUED -> "●"
}

/**
 * The line under the verdict: when the run happened (start → finish and its duration), how many cases (× repeats), the lanes
 * (device and driver, [profileName] resolves a profile id to its display name) and the judge mode.
 */
internal fun runMetaLine(run: TestRun, nowMs: Long, profileName: (String?) -> String?, zone: ZoneId = ZoneId.systemDefault()): String {
    val start = (run.startedAt ?: run.createdAt).takeIf { it > 0L }
    val finish = run.finishedAt?.takeIf { start != null && it > 0L }
    val time = when {
        start == null -> null
        finish == null -> "started ${formatRunMoment(start, nowMs, zone)}"
        else -> {
            val sameDay = Instant.ofEpochMilli(start).atZone(zone).toLocalDate() == Instant.ofEpochMilli(finish).atZone(zone).toLocalDate()
            val end = if (sameDay) formatClock(finish, zone) else formatRunMoment(finish, nowMs, zone)
            "${formatRunMoment(start, nowMs, zone)} → $end (${formatDuration(finish - start)})"
        }
    }
    val caseCount = run.config.caseIds?.size ?: run.suite.cases.size
    val cases = "$caseCount ${if (caseCount == 1) "case" else "cases"}" + if (run.config.repeat > 1) " × ${run.config.repeat} repeats" else ""
    val lanes = run.lanes.joinToString("; ") { lane ->
        val driver = if (lane.config.kind == LaneKind.EXTERNAL) EXTERNAL_LANE_LABEL else lane.config.driverLabel(profileName(lane.config.profileId))
        listOf(lane.config.deviceSerial, driver).filter(String::isNotBlank).joinToString(" · ")
    }
    val laneText = if (run.lanes.size == 1) "1 lane: $lanes" else "${run.lanes.size} lanes: $lanes"
    val judge = when (run.config.judge) {
        JudgeMode.OFF -> "no judge"
        else -> "judge: ${run.config.judge.label.lowercase()}${judgeTimingSuffix(run.config.judgeTiming)}"
    }
    return listOfNotNull(time, cases, laneText.takeIf { run.lanes.isNotEmpty() }, judge).joinToString(" · ")
}

/** Where in time the judge ran, when it was not between the steps: a verdict that lands after the case explains a late FAIL. */
internal fun judgeTimingSuffix(timing: JudgeTiming): String = when (timing) {
    JudgeTiming.INLINE -> ""
    JudgeTiming.AFTER_CASE -> ", after the case"
    JudgeTiming.BACKGROUND -> ", in the background"
}

/** The step to show in the inspector when a report opens: the first one that did not pass, or null when every step passed. */
internal fun firstNonPassingSelection(run: TestRun): StepSelection? {
    val rows = buildMatrix(run)
    val failure = firstFailure(rows)
    val step = failure?.step
    if (failure != null && step != null) return failure.laneId?.let { StepSelection(step.key, it) }
    // A run that was only cancelled has no failing step: show the step the cancel interrupted.
    val interrupted = rows.filterIsInstance<MatrixRow.Step>().firstOrNull { row -> row.cells.any { it.result?.interrupted == true } } ?: return null
    return StepSelection(interrupted.key, interrupted.cells.first { it.result?.interrupted == true }.laneId)
}

/**
 * The selection [delta] rows from [current] among the rows that have a result (the clicked lane stays when the next row has a
 * result for it). With nothing selected ↓ picks the first row and ↑ the last. Null when no row has a result.
 */
internal fun reportSelectionMovedBy(rows: List<MatrixRow>, current: StepSelection?, delta: Int): StepSelection? {
    val selectable = rows.filterIsInstance<MatrixRow.Step>().filter { row -> row.cells.any { it.result != null } }
    if (selectable.isEmpty()) return null
    val index = selectable.indexOfFirst { it.key == current?.rowKey }
    val target = if (index < 0) (if (delta > 0) 0 else selectable.lastIndex) else (index + delta).coerceIn(0, selectable.lastIndex)
    val row = selectable[target]
    val cell = row.cells.firstOrNull { it.laneId == current?.laneId && it.result != null } ?: row.cells.first { it.result != null }
    return StepSelection(row.key, cell.laneId)
}

/** The name of a case of [run] by id, with the suite hooks named too ("Suite setup"); the id itself when nothing knows it. */
internal fun matrixCaseName(run: TestRun, caseId: String): String = when (caseId) {
    SUITE_SETUP_CASE_ID -> "Suite setup"
    SUITE_TEARDOWN_CASE_ID -> "Suite teardown"
    else -> (
        run.suite.cases.firstOrNull { it.id == caseId }?.name
            ?: run.lanes.flatMap { it.cases }.firstOrNull { it.caseId == caseId }?.caseName
    )?.ifBlank { "Untitled case" } ?: caseId
}

/** The report tabs. */
internal enum class RunReportTab(val label: String) {
    Results("Results"),
    Compare("Compare"),
    Activity("Activity"),
    Usage("Usage"),
}

/** "Activity 37" once the run has tool activity to show, otherwise just the tab name. */
internal fun runReportTabLabel(tab: RunReportTab, activityCount: Int): String =
    if (tab == RunReportTab.Activity && activityCount > 0) "${tab.label} $activityCount" else tab.label

// ── Compare tab ──────────────────────────────────────────────────────

/** One row of the Compare tab: what changed ([change]) about what ([subject]), its result [before] and [now], and an optional wording [detail]. */
internal data class CompareLine(val change: String, val subject: String, val before: String, val now: String, val detail: String?)

/** "EXTERNAL//pixel/i1:PASS, …" (the comparison's raw key) as "Pass", or "Pass, Fail" for lanes that differ; "Not run" stays. */
internal fun friendlyStatuses(raw: String): String {
    if (raw.isBlank() || raw == "Not run") return "Not run"
    return raw.split(", ").map { it.substringAfterLast(':').lowercase().replaceFirstChar(Char::uppercase) }.distinct().joinToString(", ")
}

private const val COMPARE_TEXT_CHARS = 80

private fun changed(label: String, before: String?, now: String?): String? =
    if (before != null && now != null && before != now) "$label: “${before.oneLine(COMPARE_TEXT_CHARS)}” → “${now.oneLine(COMPARE_TEXT_CHARS)}”" else null

private fun ComparedRunStep.changeLabel(): String = when {
    suiteDefinitionChanged -> "Suite context changed"
    casePresence == ComparedCasePresence.ADDED -> "Case added"
    casePresence == ComparedCasePresence.REMOVED -> "Case removed"
    caseDefinitionChanged -> "Case context changed"
    presence == ComparedStepPresence.ADDED -> "Step added"
    presence == ComparedStepPresence.REMOVED -> "Step removed"
    definitionChanged -> "Step changed"
    else -> "Result changed"
}

private fun ComparedRunStep.subjectText(): String {
    val action = (currentAction ?: previousAction).orEmpty().oneLine(COMPARE_TEXT_CHARS)
    return when {
        suiteDefinitionChanged -> ""
        stepId.isBlank() -> caseName
        else -> caseName + " · " + if (action.isEmpty()) "a step" else "“$action”"
    }
}

private fun ComparedRunStep.detailText(): String? = when {
    !definitionChanged -> null
    stepId.isBlank() ->
        listOfNotNull(previousAction?.let { "Before: $it" }, currentAction?.let { "Now: $it" }).joinToString("\n").ifBlank { null }
    else ->
        listOfNotNull(changed("Action", previousAction, currentAction), changed("Expected", previousExpected, currentExpected))
            .joinToString("\n").ifBlank { null }
}

/** The Compare tab's rows in words: the step's action text instead of its id, readable results instead of lane keys. */
internal fun compareLines(rows: List<ComparedRunStep>): List<CompareLine> = rows.map { row ->
    val before = if (row.casePresence == ComparedCasePresence.ADDED || row.presence == ComparedStepPresence.ADDED) {
        "Not in that run"
    } else {
        friendlyStatuses(row.previousStatuses)
    }
    val now = if (row.casePresence == ComparedCasePresence.REMOVED || row.presence == ComparedStepPresence.REMOVED) {
        "Not in this run"
    } else {
        friendlyStatuses(row.currentStatuses)
    }
    CompareLine(row.changeLabel(), row.subjectText(), before, now, row.detailText())
}

// ── Activity tab ─────────────────────────────────────────────────────

/** "Login · step 2 · run 2 · attempt 3": where a tool call happened, from the frozen suite (the ids when the case is gone). */
internal fun activityContext(run: TestRun, caseId: String, stepId: String, iteration: Int, attempt: Int): String {
    val case = run.suite.cases.firstOrNull { it.id == caseId }
    val stepIndex = case?.steps?.indexOfFirst { it.id == stepId } ?: -1
    return listOfNotNull(
        matrixCaseName(run, caseId).takeIf { caseId.isNotBlank() },
        if (stepIndex >= 0) "step ${stepIndex + 1}" else stepId.takeIf { it.isNotBlank() }?.let { "a hook step" },
        "run $iteration".takeIf { iteration > 1 },
        "attempt $attempt".takeIf { attempt > 1 },
    ).joinToString(" · ")
}
