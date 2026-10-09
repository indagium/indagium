package com.indagium.testing.run

import com.indagium.ai.LlmImage
import com.indagium.debug.Json
import com.indagium.testing.model.CheckResult
import com.indagium.testing.model.StepExample
import com.indagium.testing.script.untrustedData
import java.util.Base64

// The first message of a judge. It carries everything the evidence tools return (the brief with the check results, an excerpt of
// the log, the texts of the reference examples) and, where the agent can take images with its first message, the screenshots as
// images: the screen at the end of the step (per lane) and the golden screenshots. A judge so briefed answers in one model turn
// instead of asking for each piece with a tool call (see JudgeService). It is built from [JudgeEvidence] alone, so it is as blind
// as the tools are: no claim, observation or status of the agent can be in it. Untrusted text (check details and log rows came
// from the device or the app) travels in the same untrusted_data envelope the tools put it in.

/** At most this many images go with the first message: the lanes' screens first, then golden screenshots in step order. */
internal const val JUDGE_MAX_ATTACHED_IMAGES = 6

/** The images of one first message add up to at most this many bytes (before base64); one that would pass it is fetched with its tool. */
internal const val JUDGE_MAX_ATTACHED_IMAGE_BYTES = 6 * 1024 * 1024

/** Rows of the log excerpt of a single-step judge: what read_step_log returns by default. */
internal const val JUDGE_BRIEF_LOG_ROWS = JUDGE_LOG_DEFAULT_ROWS

/** Rows of the log excerpt of each lane for a comparison judge, which has several lanes' worth to read. */
internal const val COMPARISON_BRIEF_LOG_ROWS = 30

/** A lane's log excerpt is at most this long (the rows are halved until it fits, so its nextOffset stays true). */
internal const val JUDGE_BRIEF_LOG_MAX_CHARS = 12_000

/** The step brief in the first message is cut after this many characters; get_step_brief has the same limit. */
internal const val JUDGE_BRIEF_MAX_CHARS = 16_000

/** A reference log in the first message is cut after this many characters; get_example has the whole text. */
internal const val JUDGE_REFERENCE_LOG_MAX_CHARS = 4_000

private const val MAX_TRUNCATION_NOTICE = "\n[Cut by Indagium; the tool has the rest.]"

/**
 * A judge's first message and what goes with it. [prompt] is what is sent when the agent takes [images]; [promptWithoutImages] is
 * the same evidence with a request to fetch the images with the tools, for an agent that cannot take them (or fails to attach them).
 * [needsTools] is true when [prompt] itself still leaves an image to fetch (the image limits were reached).
 */
internal class JudgeBrief(
    val prompt: String,
    val images: List<LlmImage>,
    val promptWithoutImages: String,
    val needsTools: Boolean,
)

/**
 * Builds the first message of the judge of [evidence]. With [attachImages] the screenshots are loaded and attached (within
 * [JUDGE_MAX_ATTACHED_IMAGES] and [JUDGE_MAX_ATTACHED_IMAGE_BYTES]); without it nothing is loaded and the message asks for them.
 * [loadExample] returns a golden screenshot already scaled by the bounded encoder.
 */
internal suspend fun buildJudgeBrief(
    evidence: JudgeEvidence,
    loadExample: suspend (StepExample.GoldenScreenshot) -> JudgeImage?,
    attachImages: Boolean,
): JudgeBrief {
    val textEvidence = textEvidence(evidence)
    val fetchPrompt = judgeHeader(evidence) + textEvidence + fetchSection(evidence) + "\n\n" + judgeFetchInstruction(evidence.isComparison)
    if (!attachImages) return JudgeBrief(fetchPrompt, emptyList(), fetchPrompt, needsTools = true)

    val attachment = ImageAttachment()
    for (lane in evidence.lanes) {
        val label = "The screen when the step ended (${lane.label})"
        attachment.add(lane.screenshot(), label, "$label is not attached; call get_step_screenshot with lane \"${lane.label}\".")
    }
    for (example in evidence.examples.filterIsInstance<StepExample.GoldenScreenshot>()) {
        val label = "Reference screenshot ${example.id}: ${example.caption.ifBlank { "(no caption)" }}"
        val fetchLine = "$label is not attached; call get_example with exampleId \"${example.id}\"."
        // Past the image limit the golden image is not even loaded: the judge is told which tool gets it.
        if (attachment.isFull()) attachment.fetch += fetchLine else attachment.add(loadExample(example), label, fetchLine)
    }
    val images = attachment.images
    val prompt = judgeHeader(evidence) + textEvidence + attachment.section() + "\n\n" +
        (if (attachment.fetch.isEmpty()) judgeAttachedInstruction(evidence.isComparison) else judgeFetchInstruction(evidence.isComparison))
    return JudgeBrief(prompt, images, fetchPrompt, needsTools = attachment.fetch.isNotEmpty())
}

/** The images collected for one first message and the lines that say what they are and what is left to fetch. */
private class ImageAttachment {
    private val attached = ArrayList<LlmImage>()
    private val labels = ArrayList<String>()
    private var bytes = 0L

    /** Images that were not attached but exist: the lines tell the judge which tool gets each. */
    val fetch = ArrayList<String>()

    /** Images that do not exist (not kept, not readable): a fact, nothing to fetch. */
    val missing = ArrayList<String>()

    val images: List<LlmImage> get() = attached.toList()

    fun isFull(): Boolean = attached.size >= JUDGE_MAX_ATTACHED_IMAGES

    fun add(image: JudgeImage?, label: String, fetchLine: String) {
        if (image == null) {
            missing += "$label is not available."
        } else if (isFull() || bytes + image.bytes.size > JUDGE_MAX_ATTACHED_IMAGE_BYTES) {
            fetch += fetchLine
        } else {
            bytes += image.bytes.size
            attached += LlmImage(Base64.getEncoder().encodeToString(image.bytes), image.mimeType)
            labels += label
        }
    }

    fun section(): String = buildString {
        if (labels.isNotEmpty()) {
            append("\n\nAttached images, in this order:")
            labels.forEachIndexed { index, label -> append("\n").append(index + 1).append(". ").append(label) }
        }
        (missing + fetch).forEach { append("\n").append(it) }
    }
}

/** What the images section of [promptWithoutImages] says: every screenshot there is to see and the tool that gets it. */
private fun fetchSection(evidence: JudgeEvidence): String = buildString {
    append("\n\nThe images are NOT attached. Get them with these tools:")
    evidence.lanes.forEach { lane ->
        append("\n- The screen when the step ended (").append(lane.label).append("): get_step_screenshot with lane \"").append(lane.label).append("\".")
    }
    evidence.examples.filterIsInstance<StepExample.GoldenScreenshot>().forEach { example ->
        append("\n- Reference screenshot ").append(example.id).append(": ").append(example.caption.ifBlank { "(no caption)" })
        append(": get_example with exampleId \"").append(example.id).append("\".")
    }
}

/** The text evidence, the same for both variants: the brief, the log excerpts and the reference logs. */
private suspend fun textEvidence(evidence: JudgeEvidence): String = buildString {
    append("\n\nStep brief (what get_step_brief returns):\n").append(clip(Json.encode(judgeBriefMap(evidence)), JUDGE_BRIEF_MAX_CHARS))
    val rows = if (evidence.isComparison) COMPARISON_BRIEF_LOG_ROWS else JUDGE_BRIEF_LOG_ROWS
    for (lane in evidence.lanes) {
        append("\n\nLog rows written while the step ran")
        if (evidence.isComparison) append(" (").append(lane.label).append(")")
        append(", the first ").append(rows).append(" at most (what read_step_log returns by default):\n")
        append(Json.encode(logExcerpt(lane, rows)))
    }
    for (example in evidence.examples.filterIsInstance<StepExample.ReferenceLog>()) {
        append("\n\nReference log ").append(example.id).append(" (what get_example returns):\n")
        append(clip(Json.encode(referenceLogResult(example)), JUDGE_REFERENCE_LOG_MAX_CHARS))
    }
}

private suspend fun logExcerpt(lane: JudgeLaneEvidence, rows: Int): Map<String, Any?> {
    var limit = rows
    var slice = lane.log(0L, limit)
    while (slice != null && limit > 1 && slice.rows.sumOf { it.length + 1 } > JUDGE_BRIEF_LOG_MAX_CHARS) {
        limit = (limit / 2).coerceAtLeast(1)
        slice = lane.log(0L, limit)
    }
    return if (slice == null) mapOf("error" to "No log was kept for ${lane.label}.") else judgeLogResult(slice)
}

private fun clip(text: String, maxChars: Int): String = if (text.length <= maxChars) text else text.take(maxChars) + MAX_TRUNCATION_NOTICE

// ── What the evidence tools return, shared with the first message ────────────────────────

/** What get_step_brief returns: the step, the checks the judge answers, the examples, the lanes' automatic check results and their details (untrusted). */
internal fun judgeBriefMap(evidence: JudgeEvidence): Map<String, Any?> {
    val laneMaps = evidence.lanes.map { lane ->
        buildMap<String, Any?> {
            put("lane", lane.label)
            put("automaticChecks", deterministicChecksMap(lane.deterministic))
            put("logBytes", lane.logBytes)
        }
    }
    val details = evidence.lanes.map { lane ->
        val laneDetails = lane.deterministic.filter { it.detail.isNotBlank() }.map { mapOf("checkId" to it.checkId, "detail" to it.detail) }
        mapOf("lane" to lane.label, "details" to laneDetails)
    }
    return buildMap {
        put("case", evidence.caseName)
        put("stepNumber", evidence.stepNumber)
        put("stepCount", evidence.stepCount)
        put("action", evidence.action)
        put("expected", evidence.expected)
        put(
            "judgeChecks",
            evidence.judgeChecks.map { check ->
                buildMap<String, Any?> {
                    put("checkId", check.checkId)
                    put("kind", check.kind)
                    put("question", check.text)
                    check.exampleId?.let { put("compareWithExample", it) }
                }
            },
        )
        put("examples", evidence.examples.map { mapOf("exampleId" to it.id, "kind" to exampleKind(it), "caption" to it.caption) })
        put("lanes", laneMaps)
        putAll(untrustedData("automatic_check_details", mapOf("lanes" to details)))
    }
}

private fun deterministicChecksMap(results: List<CheckResult>): List<Map<String, Any?>> =
    results.map { mapOf("checkId" to it.checkId, "kind" to it.kind, "result" to it.status.name.lowercase()) }

private fun exampleKind(example: StepExample): String = if (example is StepExample.GoldenScreenshot) "goldenScreenshot" else "referenceLog"

/** What read_step_log returns for [slice]: the position to continue from and the rows, which are untrusted. */
internal fun judgeLogResult(slice: StepLogSlice): Map<String, Any?> =
    mapOf("count" to slice.rows.size, "nextOffset" to slice.nextOffset, "more" to slice.more, "totalBytes" to slice.totalBytes) +
        untrustedData("logcat", mapOf("rows" to slice.rows))

/** What get_example returns for a reference log. */
internal fun referenceLogResult(example: StepExample.ReferenceLog): Map<String, Any?> = mapOf("caption" to example.caption, "text" to example.text)
