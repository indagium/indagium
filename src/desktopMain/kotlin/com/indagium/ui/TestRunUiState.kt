package com.indagium.ui

import com.indagium.capture.CaptureDevice
import com.indagium.capture.CaptureSettings
import com.indagium.model.AiProviderProfile
import com.indagium.testing.model.ALLOWED_RUN_REPEATS
import com.indagium.testing.model.CaseResult
import com.indagium.testing.model.CaseStatus
import com.indagium.testing.model.DEFAULT_CASE_TOOL_CALL_LIMIT
import com.indagium.testing.model.DEFAULT_CONFIRMATION_TIMEOUT_MS
import com.indagium.testing.model.EvidenceFlags
import com.indagium.testing.model.JudgeMode
import com.indagium.testing.model.LaneConfig
import com.indagium.testing.model.LaneKind
import com.indagium.testing.model.MAX_CASE_TOOL_CALL_LIMIT
import com.indagium.testing.model.MIN_CASE_TOOL_CALL_LIMIT
import com.indagium.testing.model.RunConfig
import com.indagium.testing.model.RunStatus
import com.indagium.testing.model.RunSummary
import com.indagium.testing.model.SUITE_SETUP_CASE_ID
import com.indagium.testing.model.SUITE_TEARDOWN_CASE_ID
import com.indagium.testing.model.StepResult
import com.indagium.testing.model.StepStatus
import com.indagium.testing.model.TestRun
import com.indagium.testing.model.TestingSettings
import com.indagium.testing.model.hasUnresolvedJudgeOutcome
import com.indagium.testing.model.moveById
import com.indagium.testing.model.newLaneId
import com.indagium.testing.run.deviceSharingWarnings
import java.text.SimpleDateFormat
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

internal data class DeviceChoice(val serial: String, val label: String)

/** The ready devices a lane may use: never the one the live capture tab holds. */
internal fun deviceChoices(devices: List<CaptureDevice>, liveCaptureSerial: String?): List<DeviceChoice> =
    devices.filter { it.available && it.serial != liveCaptureSerial }
        .map { DeviceChoice(it.serial, if (it.model == it.serial) it.serial else "${it.model} (${it.serial})") }

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
) {
    /** Every lane row, first lane first. */
    fun allLanes(): List<LaneDraft> = listOf(LaneDraft(firstLaneId, choice, deviceSerial, firstLaneModel, firstLaneEffort)) + moreLanes

    /** The model with [lanes] as its lane rows (at least one: an empty list leaves the model as it is). */
    fun withLanes(lanes: List<LaneDraft>): RunDialogModel {
        val first = lanes.firstOrNull() ?: return this
        return copy(
            choice = first.choice,
            deviceSerial = first.deviceSerial,
            firstLaneId = first.id,
            firstLaneModel = first.model,
            firstLaneEffort = first.reasoningEffort,
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
    val problem = when {
        selectedCaseIds.isEmpty() -> "Choose at least one case."
        unknownCases.isNotEmpty() -> "Some selected cases no longer exist in this suite: ${unknownCases.joinToString()}. " +
            "Refresh the suite and choose current cases."
        laneProblem != null -> laneProblem
        repeat !in ALLOWED_RUN_REPEATS -> "Repeat must be ${ALLOWED_RUN_REPEATS.joinToString(", ")}."
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
        ),
    )
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
        judgeModel = config.judgeModel,
        judgeReasoningEffort = config.judgeReasoningEffort,
        capture = config.capture ?: capture,
        openLaneTabs = config.openLaneTabs,
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

private const val TIME_PATTERN = "yyyy-MM-dd HH:mm"

internal fun formatRunTime(millis: Long?): String = millis?.let { SimpleDateFormat(TIME_PATTERN, Locale.ROOT).format(Date(it)) } ?: "—"

/** A one-line description of a run for the list. */
internal fun RunSummary.line(): String = "$passedSteps/$totalSteps steps · $caseCount case(s) · $laneCount lane(s)"

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

/** A cell of the matrix: what one lane did, or null when the lane has not got there yet. */
internal data class MatrixStepCell(val laneId: String, val result: StepResult?)

internal data class MatrixCaseCell(val laneId: String, val status: CaseStatus?, val present: Boolean)

/** One row of the report matrix, in display order. */
internal sealed interface MatrixRow {
    /** The header of a case run (or of the suite's setup / teardown hooks). */
    data class Case(val key: String, val caseId: String, val name: String, val iteration: Int, val cells: List<MatrixCaseCell>) : MatrixRow

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

internal data class TestRunReportMetrics(
    val passedCases: Int,
    val failedCases: Int,
    val blockedOrErrorCases: Int,
    val disagreements: Int,
    val unresolvedJudging: Int,
    val caseIterations: Int,
)

/** Metrics count final case/step outcomes; earlier retries remain visible in each step's attempts count. */
internal fun reportMetrics(run: TestRun): TestRunReportMetrics {
    val caseResults = run.lanes.flatMap { it.cases }.filter { it.caseId !in setOf(SUITE_SETUP_CASE_ID, SUITE_TEARDOWN_CASE_ID) }
    val stepRows = buildMatrix(run).filterIsInstance<MatrixRow.Step>()
    val results = run.lanes.flatMap { lane -> lane.cases.flatMap { it.steps } }
    return TestRunReportMetrics(
        passedCases = caseResults.count { it.status == CaseStatus.PASS },
        failedCases = caseResults.count { it.status == CaseStatus.FAIL },
        blockedOrErrorCases = caseResults.count { it.status in setOf(CaseStatus.BLOCKED, CaseStatus.ERROR) },
        disagreements = stepRows.count { consensusFor(run, it).tone == ConsensusTone.DISAGREE },
        unresolvedJudging = results.count { it.hasUnresolvedJudgeOutcome() },
        caseIterations = caseResults.map { it.caseId to it.iteration }.distinct().size,
    )
}

/** Keeps matching rows and their case headers so a filtered report remains navigable. Multiple filters are ORed. */
internal fun filterReportRows(run: TestRun, filters: Set<TestRunReportFilter>): List<MatrixRow> {
    val rows = buildMatrix(run)
    if (filters.isEmpty()) return rows
    val matchingSteps = rows.filterIsInstance<MatrixRow.Step>().filter { row ->
        val results = row.cells.mapNotNull { it.result }
        filters.any { filter ->
            when (filter) {
                TestRunReportFilter.FAILURES -> results.any { it.status in setOf(StepStatus.FAIL, StepStatus.TIMEOUT) }
                TestRunReportFilter.BLOCKED_OR_ERROR -> results.any { it.status in setOf(StepStatus.BLOCKED, StepStatus.ERROR) }
                TestRunReportFilter.DISAGREEMENTS -> consensusFor(run, row).tone == ConsensusTone.DISAGREE
                TestRunReportFilter.UNRESOLVED_JUDGING -> results.any { it.hasUnresolvedJudgeOutcome() }
            }
        }
    }
    val matchingKeys = matchingSteps.map { it.caseId to it.iteration }.toSet()
    return rows.filter { row ->
        when (row) {
            is MatrixRow.Step -> row in matchingSteps
            is MatrixRow.Case -> {
                (row.caseId to row.iteration) in matchingKeys || filters.any { filter ->
                    when (filter) {
                        TestRunReportFilter.FAILURES -> row.cells.any { it.status == com.indagium.testing.model.CaseStatus.FAIL }
                        TestRunReportFilter.BLOCKED_OR_ERROR -> row.cells.any {
                            it.status in setOf(com.indagium.testing.model.CaseStatus.BLOCKED, com.indagium.testing.model.CaseStatus.ERROR)
                        }
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
            rows += MatrixRow.Case(
                key, case.id, case.name, iteration,
                run.lanes.mapIndexed { i, lane ->
                    val result = run.lanes.caseOf(i, case.id, iteration)
                    MatrixCaseCell(lane.laneId, result?.status, result != null)
                },
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
