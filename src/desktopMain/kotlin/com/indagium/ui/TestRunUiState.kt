package com.indagium.ui

import com.indagium.capture.CaptureDevice
import com.indagium.model.AiProviderProfile
import com.indagium.testing.model.ALLOWED_RUN_REPEATS
import com.indagium.testing.model.CaseResult
import com.indagium.testing.model.CaseStatus
import com.indagium.testing.model.DEFAULT_CASE_TOOL_CALL_LIMIT
import com.indagium.testing.model.EvidenceFlags
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

/** The dialog's inputs as the user left them. */
internal data class RunDialogModel(
    val suiteId: String,
    val selectedCaseIds: Set<String>,
    val choice: LaneChoice?,
    val deviceSerial: String?,
    val repeat: Int = 1,
    val toolLimitText: String = DEFAULT_CASE_TOOL_CALL_LIMIT.toString(),
    val evidence: EvidenceFlags = EvidenceFlags(),
)

/** The model as a config, or the first thing the user still has to fix. [allCaseIds] lets "every case" be sent as null. */
internal fun RunDialogModel.toConfig(allCaseIds: List<String>): Result<RunConfig> {
    val problem = when {
        selectedCaseIds.isEmpty() -> "Choose at least one case."
        choice == null -> "Choose what drives the lane."
        deviceSerial.isNullOrBlank() -> "Choose a device."
        repeat !in ALLOWED_RUN_REPEATS -> "Repeat must be ${ALLOWED_RUN_REPEATS.joinToString(", ")}."
        toolLimitText.trim().toIntOrNull() !in MIN_CASE_TOOL_CALL_LIMIT..MAX_CASE_TOOL_CALL_LIMIT ->
            "The tool-call limit must be a whole number from $MIN_CASE_TOOL_CALL_LIMIT to $MAX_CASE_TOOL_CALL_LIMIT."
        else -> null
    }
    if (problem != null) return Result.failure(IllegalArgumentException(problem))
    val lane = LaneConfig(
        kind = if (choice!!.isExternal) LaneKind.EXTERNAL else LaneKind.AGENT_PROFILE,
        profileId = choice.profileId,
        deviceSerial = deviceSerial!!,
    )
    val everyCase = selectedCaseIds.containsAll(allCaseIds)
    return Result.success(
        RunConfig(
            suiteId = suiteId,
            caseIds = if (everyCase) null else allCaseIds.filter { it in selectedCaseIds },
            lanes = listOf(lane),
            repeat = repeat,
            caseToolCallLimit = toolLimitText.trim().toInt(),
            evidence = evidence,
        ),
    )
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
    data class Step(val key: String, val stepId: String, val number: Int, val action: String, val setup: Boolean, val cells: List<MatrixStepCell>) : MatrixRow
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
