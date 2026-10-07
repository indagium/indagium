package com.indagium.testing.run

import com.indagium.debug.encodeBoundedDeviceScreen
import com.indagium.testing.device.CaptureLogReader
import com.indagium.testing.model.CheckResult
import com.indagium.testing.model.StepCheck
import com.indagium.testing.model.StepExample
import com.indagium.testing.model.TestStep
import java.io.File

// The evidence a judge may look at for one step. BLIND by construction: there is no field for what the agent claimed or
// reported, so no tool, prompt or transcript line built from this can leak it. A lane's evidence is its deterministic
// check results, the screenshot taken when the step ended and the log rows written during the step. A single-step judge
// has one lane; a comparison judge one per lane that took part.

private const val JUDGE_LOG_CHUNK_BYTES = 256 * 1024
internal const val JUDGE_LOG_DEFAULT_ROWS = 60
internal const val JUDGE_LOG_MAX_ROWS = 200

/** A ScreenJudge or AskJudge check of the step: [text] is what the judge is asked, [exampleId] the example to compare with. */
internal data class JudgeCheckBrief(val checkId: String, val kind: String, val text: String, val exampleId: String?)

/** Rows of the step's log range, formatted for the model. [nextOffset] continues the read; offsets count from the step's start. */
internal class StepLogSlice(val rows: List<String>, val nextOffset: Long, val more: Boolean, val totalBytes: Long)

/** An image for the model: JPEG or PNG [bytes]. */
internal class JudgeImage(val bytes: ByteArray, val mimeType: String)

/** One lane's evidence. [label] is what the judge calls the lane ("Lane 1"); [screenshot] and [log] return null when none was kept. */
internal class JudgeLaneEvidence(
    val label: String,
    val laneId: String,
    val deterministic: List<CheckResult>,
    val screenshot: suspend () -> JudgeImage?,
    val log: suspend (offset: Long, limit: Int) -> StepLogSlice?,
    val logBytes: Long?,
)

internal class JudgeEvidence(
    val caseName: String,
    val stepNumber: Int,
    val stepCount: Int,
    val action: String,
    val expected: String,
    val judgeChecks: List<JudgeCheckBrief>,
    val examples: List<StepExample>,
    val lanes: List<JudgeLaneEvidence>,
) {
    val isComparison: Boolean get() = lanes.size > 1

    /** The lane named [label] or having that id; the only lane when [label] is null and there is one. */
    fun lane(label: String?): JudgeLaneEvidence? =
        if (label.isNullOrBlank()) lanes.singleOrNull() else lanes.firstOrNull { it.label.equals(label.trim(), true) || it.laneId == label.trim() }
}

/** The judge checks of [step]: what the judge is asked on top of "does it match the expected result". */
internal fun judgeChecksOf(step: TestStep): List<JudgeCheckBrief> = step.checks.mapNotNull { check ->
    when (check) {
        is StepCheck.ScreenJudge -> JudgeCheckBrief(check.id, "screenJudge", check.text, check.exampleRef)
        is StepCheck.AskJudge -> JudgeCheckBrief(check.id, "askJudge", check.text, null)
        else -> null
    }
}

/** The checks a judge may see results of: the deterministic ones. A judge check's result is a judge's own verdict. */
internal fun deterministicOnly(results: List<CheckResult>): List<CheckResult> =
    results.filter { it.kind != "screenJudge" && it.kind != "askJudge" }

/**
 * The rows of [file] between [start] and [end] (byte offsets), beginning [offset] bytes into that range, at most [limit]
 * of them. Null when the log or the range is not available (not recorded, or deleted with the run's evidence).
 */
internal fun readStepLogSlice(file: File, start: Long?, end: Long?, offset: Long, limit: Int): StepLogSlice? {
    if (start == null || end == null || end < start || !file.isFile) return null
    val upper = minOf(end, file.length())
    val reader = CaptureLogReader(file)
    val wanted = limit.coerceIn(1, JUDGE_LOG_MAX_ROWS)
    val rows = ArrayList<String>()
    var cursor = start + offset.coerceAtLeast(0L)
    var done = false
    while (!done && rows.size < wanted && cursor < upper) {
        val chunk = reader.readRows(cursor, JUDGE_LOG_CHUNK_BYTES)
        if (chunk.rows.isEmpty()) break
        for (row in chunk.rows) {
            if (row.endOffset > upper) {
                cursor = upper
                done = true
                break
            }
            rows += formatLogRow(row.entry)
            cursor = row.endOffset
            if (rows.size == wanted) {
                done = true
                break
            }
        }
        if (!done) cursor = chunk.endOffset
    }
    val next = minOf(cursor, upper)
    return StepLogSlice(rows, (next - start).coerceAtLeast(0L), more = rows.size >= wanted && next < upper, totalBytes = upper - start)
}

private const val MIME_JPEG = "image/jpeg"

/** Strictly decode, dimension-check, scale and compress; never send unsafe or undecodable raw bytes as a fallback. */
internal fun boundedJudgeImage(bytes: ByteArray): JudgeImage? {
    if (bytes.isEmpty() || bytes.size > com.indagium.testing.store.MAX_GOLDEN_IMAGE_BYTES) return null
    val scaled = runCatching { encodeBoundedDeviceScreen(bytes).bytes }.getOrNull()
    return scaled?.let { JudgeImage(it, MIME_JPEG) }
}
