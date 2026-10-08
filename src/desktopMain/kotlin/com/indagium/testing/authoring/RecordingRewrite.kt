package com.indagium.testing.authoring

import com.indagium.debug.parseChecks
import com.indagium.debug.toPlainMap
import com.indagium.testing.model.StepCheck
import com.indagium.testing.model.TestStep
import com.indagium.testing.store.IdAllocator
import com.indagium.testing.store.validateStep
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.longOrNull
import java.util.IdentityHashMap

// What an AI rewrite of a recording is made of: the numbered inputs the AI is shown, the JSON it must answer with, the checks
// that answer must pass, and the review rows built from it. Nothing here talks to a model or to a device; the service
// (RecordingRewriteService.kt) runs the agent and the session (TestStepRecordingSession.kt) swaps the rows.

/** At most this many steps come out of a rewrite, like a step draft. */
internal const val MAX_REWRITE_STEPS = 20
private const val MAX_STEP_TEXT_CHARS = 2_000
private const val MAX_NOTES_CHARS = 2_000
private const val MAX_CHECKS_PER_STEP = 10
private const val MAX_HINT_CHARS = 4_000
private const val MAX_REVIEW_REASON_CHARS = 500
private const val SCREEN_REF_PREFIX = "after-of-input-"
private const val STEPS_KEY = "steps"

/** What a finished rewrite hands back: the rows now in the session and what the AI said about its choices. */
data class RecordingRewrite(val sessionId: String, val profileId: String, val steps: List<RecordedTestStep>, val notes: String)

/** One recorded input as the AI sees it: [number] is 1-based, [before] / [after] the screen states the probe attached (or null). */
internal class RewriteInput(
    val number: Int,
    val row: RecordedTestStep,
    val before: RecordingScreenState?,
    val after: RecordingScreenState?,
) {
    fun screenshot(moment: String): RecordingScreenshotEvidence? = when (moment.lowercase()) {
        "before" -> row.beforeScreenshot
        "after" -> row.afterScreenshot
        "input" -> row.screenshotJpeg?.let { image ->
            val fallbackSource = if (row.screenshotFromAdb) RecordingScreenshotSource.ADB_INPUT else RecordingScreenshotSource.MIRROR_INPUT
            RecordingScreenshotEvidence(
                jpeg = image,
                source = row.screenshotSource ?: fallbackSource,
                acquiredAtMs = row.screenshotAcquiredAtMs,
                acquisitionFinishedAtMs = row.screenshotAcquisitionFinishedAtMs,
                timingUncertain = row.screenshotTimingUncertain,
            )
        }
        else -> null
    }
}

/** Stable IDs for screenshot evidence shared by inputs, such as one capture that is after one input and before the next. */
internal class RewriteEvidenceIds(inputs: List<RewriteInput>) {
    private val ids = IdentityHashMap<RecordingScreenshotEvidence, String>()

    init {
        inputs.forEach { input ->
            listOf(input.screenshot("before"), input.screenshot("after")).forEach { evidence ->
                if (evidence != null && !ids.containsKey(evidence)) ids[evidence] = "capture-${ids.size + 1}"
            }
        }
    }

    fun id(evidence: RecordingScreenshotEvidence): String? = ids[evidence]
}

/** The inputs of [rows], numbered from 1, each with the stored screen states its row points to ([screenState] looks one up). */
internal fun rewriteInputsOf(rows: List<RecordedTestStep>, screenState: (Int) -> RecordingScreenState?): List<RewriteInput> =
    rows.mapIndexed { index, row ->
        RewriteInput(index + 1, row, row.before?.let { screenState(it.seq) }, row.after?.let { screenState(it.seq) })
    }

/** One step the AI proposed. [screenInput] is the input whose after-image is the step's expected-screenshot candidate (null: the last one). */
internal class ProposedStep(
    val action: String,
    val expected: String,
    val optional: Boolean,
    val condition: String?,
    val sourceInputs: List<Int>,
    val screenInput: Int?,
    val checks: List<StepCheck>,
    val reviewReason: String?,
)

internal class RewriteProposal(val steps: List<ProposedStep>, val notes: String)

/**
 * The AI's answer as a validated [RewriteProposal]. Throws [IllegalArgumentException] naming the first problem: the answer is
 * applied whole or not at all. [inputCount] is how many inputs were shown; every one must sit in exactly one step.
 */
internal fun parseRewriteResponse(response: String, inputCount: Int): RewriteProposal {
    val root = parseJsonObject(response)
    val rows = root[STEPS_KEY] as? JsonArray ?: throw IllegalArgumentException("The provider response must include a 'steps' array.")
    require(rows.isNotEmpty() && rows.size <= MAX_REWRITE_STEPS) { "The provider must propose between 1 and $MAX_REWRITE_STEPS steps." }
    val steps = rows.mapIndexed { index, row ->
        val obj = row as? JsonObject ?: throw IllegalArgumentException("steps[$index] must be an object.")
        parseProposedStep(obj, index, inputCount)
    }
    requireFullCoverage(steps.map { it.sourceInputs }, inputCount)
    val notes = (root["notes"] as? JsonPrimitive)?.contentOrNull?.trim().orEmpty().take(MAX_NOTES_CHARS)
    return RewriteProposal(steps, notes)
}

private fun parseJsonObject(response: String): JsonObject {
    val clean = response.trim().removePrefix("```json").removePrefix("```").removeSuffix("```").trim()
    val element = runCatching { Json.parseToJsonElement(clean) }.recoverCatching {
        // Chatter around the object (an agent's remark before or after) is tolerated; anything else is not JSON.
        val start = clean.indexOf('{')
        val end = clean.lastIndexOf('}')
        require(start in 0 until end) { it.message ?: "parse error" }
        Json.parseToJsonElement(clean.substring(start, end + 1))
    }.getOrElse { throw IllegalArgumentException("The provider did not return valid JSON steps: ${it.message ?: "parse error"}") }
    return element as? JsonObject ?: throw IllegalArgumentException("The provider response must be a JSON object with a 'steps' array.")
}

private fun parseProposedStep(obj: JsonObject, index: Int, inputCount: Int): ProposedStep {
    val where = "steps[$index]"
    val action = textField(obj, "action", where)
    val expected = textField(obj, "expected", where, allowBlank = true)
    val optionalPrimitive = obj["optional"] as? JsonPrimitive
    val optional = optionalPrimitive?.booleanOrNull ?: false
    require(obj["optional"] == null || optionalPrimitive?.let { !it.isString && it.booleanOrNull != null } == true) {
        "$where.optional must be a boolean when provided."
    }
    val condition = optionalTextField(obj, "condition", where, MAX_STEP_TEXT_CHARS)
    val reviewReason = optionalTextField(obj, "reviewReason", where, MAX_REVIEW_REASON_CHARS)
    require(!optional || !condition.isNullOrBlank()) {
        "$where.condition must explain when to perform an optional step."
    }
    require(optional || condition == null) {
        "$where.condition is only valid when optional is true."
    }
    require(expected.isNotBlank() || reviewReason != null || optional && !condition.isNullOrBlank()) {
        "$where.expected may be blank only for a conditional optional step or when reviewReason explains what needs review."
    }
    val sources = sourceInputsOf(obj["sourceInputs"], where, inputCount)
    val checks = checksOf(obj["checks"], where, action)
    return ProposedStep(
        action,
        expected,
        optional,
        condition,
        sources,
        screenInputOf(obj["expectedScreenshot"], where, sources),
        checks,
        reviewReason,
    )
}

private fun textField(obj: JsonObject, key: String, where: String, allowBlank: Boolean = false): String {
    val text = (obj[key] as? JsonPrimitive)?.takeIf { it.isString }?.content?.trim()
    require(text != null && (allowBlank || text.isNotBlank())) {
        "$where.$key must be ${if (allowBlank) "a string" else "a non-blank string"}."
    }
    require(text.length <= MAX_STEP_TEXT_CHARS) { "$where.$key is longer than $MAX_STEP_TEXT_CHARS characters." }
    return text
}

private fun optionalTextField(obj: JsonObject, key: String, where: String, maxChars: Int): String? {
    if (!obj.containsKey(key)) return null
    val value = obj[key]
    if (value == null || value is JsonNull) return null
    val primitive = value as? JsonPrimitive
    require(primitive != null && primitive.isString) { "$where.$key must be a string or null when provided." }
    val text = primitive.content.trim()
    if (text.isEmpty()) return null
    require(text.length <= maxChars) { "$where.$key is longer than $maxChars characters." }
    return text
}

private fun sourceInputsOf(element: JsonElement?, where: String, inputCount: Int): List<Int> {
    val numbers = (element as? JsonArray)?.map { number ->
        val value = (number as? JsonPrimitive)?.takeIf { !it.isString }?.longOrNull
        require(value != null && value in 1..inputCount) { "$where.sourceInputs must hold whole numbers from 1 to $inputCount." }
        value.toInt()
    }
    require(!numbers.isNullOrEmpty()) { "$where.sourceInputs must list the recorded inputs the step covers." }
    return numbers
}

private fun screenInputOf(element: JsonElement?, where: String, sources: List<Int>): Int? {
    if (element == null || element is JsonNull) return null
    val text = (element as? JsonPrimitive)?.takeIf { it.isString }?.content?.trim()
    val failure = "$where.expectedScreenshot must be \"${SCREEN_REF_PREFIX}N\" with N one of the step's inputs, or null."
    require(text != null) { failure }
    if (text.isEmpty()) return null
    val number = if (text.startsWith(SCREEN_REF_PREFIX)) text.removePrefix(SCREEN_REF_PREFIX).toIntOrNull() else null
    require(number != null && number in sources) { failure }
    return number
}

/** Checks go through the same parser and rules as a step draft, minus everything that points outside the step. */
private fun checksOf(element: JsonElement?, where: String, action: String): List<StepCheck> {
    if (element == null || element is JsonNull) return emptyList()
    require(element is JsonArray) { "$where.checks must be an array." }
    val items = element.map { requireNotNull(it as? JsonObject) { "$where.checks must hold objects." } }
    require(items.size <= MAX_CHECKS_PER_STEP) { "$where.checks may hold at most $MAX_CHECKS_PER_STEP checks." }
    // The parser's own message names the offending check (it throws an IllegalArgumentException).
    val checks = parseChecks(items.map { it.toPlainMap() }, IdAllocator(), "$where.checks")
    require(checks.none { it is StepCheck.ScriptResult }) { "$where.checks must not use script checks." }
    require(checks.none { it is StepCheck.ScreenJudge && it.exampleRef != null }) { "$where.checks must not reference examples." }
    val problem = validateStep(TestStep("", action = action, checks = checks))
    require(problem == null) { "$where: $problem" }
    return checks
}

/** Every input in exactly one step, in recorded order: step 1 starts at input 1 and each step starts right after the one before. */
private fun requireFullCoverage(groups: List<List<Int>>, inputCount: Int) {
    var next = 1
    groups.forEachIndexed { index, inputs ->
        inputs.forEach { number ->
            require(number >= next) { "Input $number appears in more than one step (steps[$index] repeats an input an earlier step covers)." }
            require(number == next) { "Inputs must be covered in order without gaps: input $next is missing before steps[$index] reaches input $number." }
            next++
        }
    }
    require(next == inputCount + 1) { "Every recorded input must be in a step: input $next${if (next < inputCount) " to $inputCount" else ""} is not covered." }
}

/** The review rows for [proposal]: new ids, one row per proposed step, with its after-image as an opt-in expected-screenshot candidate. */
internal fun buildRewrittenRows(
    proposal: RewriteProposal,
    inputs: List<RewriteInput>,
    videoInspectedInputs: Set<Int> = emptySet(),
): List<RecordedTestStep> =
    proposal.steps.map { step ->
        val sources = step.sourceInputs.map { inputs[it - 1] }
        val unclearGesture = sources.firstOrNull { input ->
            input.row.kind in setOf(RecordedInputKind.TAP, RecordedInputKind.LONG_PRESS) &&
                input.row.tappedElement == null && input.screenshot("before") == null && input.number !in videoInspectedInputs
        }
        val needsReview = unclearGesture != null || step.reviewReason != null
        val rawAction = sources.joinToString("\n") { it.row.action }.take(MAX_STEP_TEXT_CHARS)
        val screenInput = step.screenInput ?: step.sourceInputs.last()
        val screen = if (needsReview) null else inputs[screenInput - 1].screenshot("after")
        RecordedTestStep(
            action = if (needsReview) rawAction else step.action,
            expected = if (needsReview) "" else step.expected,
            optional = !needsReview && step.optional,
            condition = step.condition.takeUnless { needsReview },
            screenshotJpeg = screen?.jpeg,
            screenshotFromAdb = screen?.source != null && screen.source != RecordingScreenshotSource.MIRROR_INPUT,
            before = sources.first().row.before,
            after = sources.last().row.after,
            inputAtMs = sources.last().row.inputAtMs,
            inputStartMs = sources.first().row.inputStartMs,
            sourceInputIds = sources.map { it.row.id },
            sourceHint = sourceHintOf(sources),
            checks = if (needsReview) emptyList() else step.checks,
            reviewReason = when {
                unclearGesture != null -> {
                    val kind = unclearGesture.row.kind?.name?.lowercase()
                    "The recorded $kind has neither a resolved target nor trustworthy inspected video/screenshot evidence. " +
                        "Identify the target and expected result."
                }
                else -> step.reviewReason
            },
            screenshotSource = screen?.source,
            screenshotAcquiredAtMs = screen?.acquiredAtMs,
            screenshotAcquisitionFinishedAtMs = screen?.acquisitionFinishedAtMs,
            screenshotTimingUncertain = screen?.timingUncertain ?: true,
            screenshotVerifiedMoment = screen?.let { "after" },
        )
    }

/** The raw inputs of one rewritten step, for the AI that later runs it: the precise gesture when a label is ambiguous. */
private fun sourceHintOf(sources: List<RewriteInput>): String {
    val lines = ArrayList<String>()
    var used = 0
    for (input in sources) {
        val line = input.describe(RewriteDescription.HINT)
        if (used + line.length > MAX_HINT_CHARS) {
            lines += "… and ${sources.size - lines.size} more input(s)"
            break
        }
        lines += line
        used += line.length + 1
    }
    return lines.joinToString("\n")
}

internal enum class RewriteDescription { BRIEF, HINT }

private const val MAX_ACTION_CHARS = 300

/**
 * One input as a line of text. The action is the row's current text (a hidden password stays hidden). BRIEF is what the AI is
 * shown (it adds the screens and which images exist); HINT is what the runner of the finished step keeps.
 */
internal fun RewriteInput.describe(mode: RewriteDescription, evidenceIds: RewriteEvidenceIds? = null): String = buildString {
    append(number).append(". ")
    if (mode == RewriteDescription.BRIEF) append('[').append(row.kind?.name ?: "INPUT").append("] ")
    append(row.action.take(MAX_ACTION_CHARS))
    row.tappedElement?.let { element ->
        append(" | element: \"").append(element.label()).append('"')
        val id = element.resourceId.substringAfter(":id/")
        if (id.isNotBlank()) append(" id=").append(id)
        if (element.className.isNotBlank()) append(" class=").append(element.className.substringAfterLast('.'))
    }
    if (row.kind == RecordedInputKind.LONG_PRESS) row.durationMs?.let { append(" | held ").append(it).append(" ms") }
    append(" | before app: ").append(row.before?.packageName ?: "unavailable")
    append(" | before activity: ").append(row.before?.activity ?: "unavailable")
    append(" | after app: ").append(row.after?.packageName ?: "unavailable")
    append(" | after activity: ").append(row.after?.activity ?: "unavailable")
    if (mode == RewriteDescription.BRIEF) appendBriefDetails(this@describe, evidenceIds)
}

private fun StringBuilder.appendBriefDetails(input: RewriteInput, evidenceIds: RewriteEvidenceIds?) {
    val before = input.screenshot("before")
    val after = input.screenshot("after")
    append(" | before screenshot: ").append(before?.let { screenshotSummary(it, evidenceIds?.id(it)) } ?: "unavailable")
    append(" | after screenshot: ").append(after?.let { screenshotSummary(it, evidenceIds?.id(it)) } ?: "unavailable")
    append(" | before UI: ").append(if (input.before?.nodes?.isNotEmpty() == true) "available" else "unavailable")
    append(" | after UI: ").append(if (input.after?.nodes?.isNotEmpty() == true) "available" else "unavailable")
    val raw = input.screenshot("input")
    val rawSummary = raw?.let { screenshotSummary(it) + if (it.timingUncertain) " (timing uncertain)" else "" } ?: "unavailable"
    append(" | raw input preview: ").append(rawSummary)
    if (input.row.expected.isNotBlank()) append(" | typed note: \"").append(input.row.expected.take(MAX_ACTION_CHARS)).append('"')
}

private fun screenshotSummary(evidence: RecordingScreenshotEvidence, evidenceId: String? = null): String = buildString {
    append(evidence.source.name.lowercase().replace('_', ' '))
    evidenceId?.let { append(" [").append(it).append(']') }
    val start = evidence.acquiredAtMs
    val end = evidence.acquisitionFinishedAtMs
    if (start != null && end != null) append(" [").append(start).append("..").append(end).append(" ms]")
}
