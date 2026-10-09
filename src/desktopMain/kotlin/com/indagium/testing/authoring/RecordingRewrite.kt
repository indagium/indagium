package com.indagium.testing.authoring

import com.indagium.debug.parseChecks
import com.indagium.debug.toPlainMap
import com.indagium.model.AiUsageStats
import com.indagium.testing.model.ManualCompletion
import com.indagium.testing.model.ScriptCall
import com.indagium.testing.model.StepCheck
import com.indagium.testing.model.StepKind
import com.indagium.testing.model.TestScript
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

/** The longest action / expected text of a step, and so of an author-added row that has to be echoed back unchanged. */
internal const val MAX_FIXED_STEP_CHARS = 2_000
private const val MAX_STEP_TEXT_CHARS = MAX_FIXED_STEP_CHARS
private const val MAX_NOTES_CHARS = 2_000
private const val MAX_CHECKS_PER_STEP = 10
private const val MAX_HINT_CHARS = 4_000
private const val MAX_REVIEW_REASON_CHARS = 500
private const val MAX_SCRIPT_ID_CHARS = 200
private const val MAX_SCRIPT_ARGS = 20
private const val MAX_SCRIPT_ARG_CHARS = 500
private const val SCREEN_REF_PREFIX = "after-of-input-"
private const val STEPS_KEY = "steps"

/** What a finished rewrite hands back: the rows now in the session and what the AI said about its choices. */
data class RecordingRewrite(
    val sessionId: String,
    val profileId: String,
    val steps: List<RecordedTestStep>,
    val notes: String,
    val usage: AiUsageStats? = null,
)

/**
 * One recorded input as the AI sees it: [number] is 1-based in review order, [before] / [after] the screen states the probe
 * attached (or null). An author-added row is [fixed]: not a recording at all, but a step the person wrote that must come back
 * unchanged and alone.
 */
internal class RewriteInput(
    val number: Int,
    val row: RecordedTestStep,
    val before: RecordingScreenState?,
    val after: RecordingScreenState?,
) {
    val fixed: Boolean get() = row.authorAdded

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
    /** Who performs the step. Anything but AGENT is only valid for a step that is exactly one external event. */
    val kind: StepKind = StepKind.AGENT,
    val script: ScriptCall? = null,
    val manualCompletion: ManualCompletion = ManualCompletion.TESTER_OR_CHECKS,
)

internal class RewriteProposal(val steps: List<ProposedStep>, val notes: String)

/** What a rewrite answer is checked against: how many inputs were shown, which are the person's own steps, which are external events. */
internal class RewriteExpectations(
    val inputCount: Int,
    /** The author-added inputs by number: each must be a step of its own whose text, optional flag and condition equal the row's. */
    val fixedInputs: Map<Int, RecordedTestStep> = emptyMap(),
    /**
     * The external-event inputs by number, with the checks each one proposed: each is a step of its own that a tester or a script
     * performs, never an AI step. An answer that gives such a step no device-state check gets the proposed ones.
     */
    val externalInputs: Map<Int, List<StepCheck>> = emptyMap(),
    /** The library's scripts, which a manual or script step may name. */
    val scripts: List<TestScript> = emptyList(),
)

/**
 * The AI's answer as a validated [RewriteProposal]. Throws [IllegalArgumentException] naming the first problem: the answer is
 * applied whole or not at all. [RewriteExpectations.inputCount] is how many inputs were shown; every one must sit in exactly one step.
 */
internal fun parseRewriteResponse(response: String, inputCount: Int, fixedInputs: Map<Int, RecordedTestStep> = emptyMap()): RewriteProposal =
    parseRewriteResponse(response, RewriteExpectations(inputCount, fixedInputs))

internal fun parseRewriteResponse(response: String, expectations: RewriteExpectations): RewriteProposal {
    val inputCount = expectations.inputCount
    val root = parseJsonObject(response)
    val rows = root[STEPS_KEY] as? JsonArray ?: throw IllegalArgumentException("The provider response must include a 'steps' array.")
    require(rows.isNotEmpty() && rows.size <= MAX_REWRITE_STEPS) { "The provider must propose between 1 and $MAX_REWRITE_STEPS steps." }
    val steps = rows.mapIndexed { index, row ->
        val obj = row as? JsonObject ?: throw IllegalArgumentException("steps[$index] must be an object.")
        parseProposedStep(obj, index, expectations)
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

private fun parseProposedStep(obj: JsonObject, index: Int, expectations: RewriteExpectations): ProposedStep {
    val where = "$STEPS_KEY[$index]"
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
    val sources = sourceInputsOf(obj["sourceInputs"], where, expectations.inputCount)
    val fixed = sources.firstOrNull { it in expectations.fixedInputs }
    if (fixed != null) {
        val step = ProposedStep(action, expected, optional, condition, sources, null, emptyList(), reviewReason)
        requireFixedStepUnchanged(obj, where, step, fixed, requireNotNull(expectations.fixedInputs[fixed]))
        return step
    }
    val performer = performerOf(obj, where, sources, expectations)
    require(performer.kind != StepKind.AGENT || expected.isNotBlank() || reviewReason != null || optional && !condition.isNullOrBlank()) {
        "$where.expected may be blank only for a conditional optional step or when reviewReason explains what needs review."
    }
    require(performer.kind == StepKind.AGENT || !optional) { "$where: only an AI step can be optional." }
    val checks = withProposedChecks(checksOf(obj["checks"], where), sources.singleOrNull()?.let(expectations.externalInputs::get))
    val proposed = ProposedStep(
        action,
        expected,
        optional,
        condition,
        sources,
        screenInputOf(obj["expectedScreenshot"], where, sources),
        checks,
        reviewReason,
        performer.kind,
        performer.script,
        performer.manualCompletion,
    )
    val problem = validateStep(proposed.toTestStep(), expectations.scripts)
    require(problem == null) { "$where: $problem" }
    return proposed
}

private fun withProposedChecks(given: List<StepCheck>, proposed: List<StepCheck>?): List<StepCheck> =
    if (proposed == null || given.any { it is StepCheck.DeviceState }) given else given + proposed

private fun ProposedStep.toTestStep() = TestStep(
    "",
    action = action,
    expected = expected,
    checks = checks,
    optional = optional,
    condition = condition,
    kind = kind,
    script = script,
    manualCompletion = manualCompletion,
)

private class Performer(val kind: StepKind, val script: ScriptCall?, val manualCompletion: ManualCompletion)

/**
 * Who performs a step: `kind` agent (default), manual or script, with the library script a script step runs (a manual step may name
 * one as its automation alternative) and how a manual step is completed. An external event is a step of its own and is performed by a
 * tester or a script, never by the AI agent; nothing else may be anything but an AI step.
 */
private fun performerOf(obj: JsonObject, where: String, sources: List<Int>, expectations: RewriteExpectations): Performer {
    val kind = kindOf(obj["kind"], where)
    val external = sources.filter { it in expectations.externalInputs }
    require(external.isEmpty() || (sources.size == 1 && kind != StepKind.AGENT)) {
        "$where covers external event input ${external.first()}, which must be a manual or script step of its own " +
            "(kind \"manual\" or \"script\", sourceInputs [${external.first()}])."
    }
    require(kind == StepKind.AGENT || external.isNotEmpty()) {
        "$where.kind \"${kind.name.lowercase()}\" is only valid for a step that is exactly one external event input."
    }
    val scriptId = optionalTextField(obj, "scriptId", where, MAX_SCRIPT_ID_CHARS)
    val args = scriptArgsOf(obj["args"], where)
    val completion = completionOf(obj["manualCompletion"], where)
    if (kind == StepKind.AGENT) {
        require(scriptId == null && args.isEmpty() && completion == null) {
            "$where: scriptId, args and manualCompletion are only valid on a manual or script step."
        }
    }
    require(completion == null || kind == StepKind.MANUAL) { "$where.manualCompletion is only valid on a manual step." }
    require(scriptId != null || args.isEmpty()) { "$where.args needs a scriptId." }
    require(kind != StepKind.SCRIPT || scriptId != null) { "$where: a script step needs a scriptId from the library." }
    return Performer(kind, scriptId?.let { ScriptCall(it, args) }, completion ?: ManualCompletion.TESTER_OR_CHECKS)
}

private fun kindOf(element: JsonElement?, where: String): StepKind {
    if (element == null || element is JsonNull) return StepKind.AGENT
    val text = (element as? JsonPrimitive)?.takeIf { it.isString }?.content?.trim().orEmpty()
    return StepKind.entries.firstOrNull { it.name.equals(text, ignoreCase = true) }
        ?: throw IllegalArgumentException("$where.kind must be agent, manual or script.")
}

private fun completionOf(element: JsonElement?, where: String): ManualCompletion? {
    if (element == null || element is JsonNull) return null
    val text = (element as? JsonPrimitive)?.takeIf { it.isString }?.content?.trim().orEmpty()
    return ManualCompletion.entries.firstOrNull { it.name.equals(text, ignoreCase = true) }
        ?: throw IllegalArgumentException("$where.manualCompletion must be one of ${ManualCompletion.entries.joinToString(", ") { it.name }}.")
}

private fun scriptArgsOf(element: JsonElement?, where: String): Map<String, String> {
    if (element == null || element is JsonNull) return emptyMap()
    val obj = element as? JsonObject ?: throw IllegalArgumentException("$where.args must be an object of script arguments.")
    require(obj.size <= MAX_SCRIPT_ARGS) { "$where.args may hold at most $MAX_SCRIPT_ARGS arguments." }
    return obj.entries.associate { (name, value) ->
        val primitive = value as? JsonPrimitive
        require(primitive != null && primitive !is JsonNull && primitive.content.length <= MAX_SCRIPT_ARG_CHARS) {
            "$where.args.$name must be a short string, number or boolean."
        }
        name to primitive.content
    }
}

/** An author-added step comes back as its own step with the person's exact text, flags and nothing added by the AI. */
private fun requireFixedStepUnchanged(obj: JsonObject, where: String, step: ProposedStep, number: Int, row: RecordedTestStep) {
    val label = "$where covers input $number, an author-added step that must be kept as is"
    require(step.sourceInputs == listOf(number)) { "$label: it must be a step of its own (sourceInputs [$number]), never merged with other inputs." }
    require(step.action == row.action.trim() && step.expected == row.expected.trim()) {
        "$label: action and expected must equal the person's text exactly."
    }
    require(step.optional == row.optional && step.condition == row.condition?.trim()?.takeIf(String::isNotEmpty)) {
        "$label: optional and condition must equal the person's."
    }
    val extras = listOf("checks", "reviewReason", "expectedScreenshot", "scriptId", "args", "manualCompletion").filter { key -> !isEmptyAnswer(obj[key]) }
    require(extras.isEmpty()) { "$label: leave out ${extras.joinToString(", ")}." }
    val kind = kindOf(obj["kind"], where)
    require(kind == row.stepKind || obj["kind"] == null) { "$label: kind must be \"${row.stepKind.name.lowercase()}\" like the person's step." }
}

private fun isEmptyAnswer(element: JsonElement?): Boolean = when (element) {
    null, is JsonNull -> true
    is JsonArray -> element.isEmpty()
    is JsonPrimitive -> element.isString && element.content.isBlank()
    else -> false
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

/** Checks go through the same parser and rules as a step draft, minus script checks and example references; device-state checks are allowed. */
private fun checksOf(element: JsonElement?, where: String): List<StepCheck> {
    if (element == null || element is JsonNull) return emptyList()
    require(element is JsonArray) { "$where.checks must be an array." }
    val items = element.map { requireNotNull(it as? JsonObject) { "$where.checks must hold objects." } }
    require(items.size <= MAX_CHECKS_PER_STEP) { "$where.checks may hold at most $MAX_CHECKS_PER_STEP checks." }
    // The parser's own message names the offending check (it throws an IllegalArgumentException).
    val checks = parseChecks(items.map { it.toPlainMap() }, IdAllocator(), "$where.checks")
    require(checks.none { it is StepCheck.ScriptResult }) { "$where.checks must not use script checks." }
    require(checks.none { it is StepCheck.ScreenJudge && it.exampleRef != null }) { "$where.checks must not reference examples." }
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
        // An author-added row is not rewritten: the row itself (same id, same text) stays, in the place the answer gave it.
        sources.singleOrNull()?.takeIf { it.fixed }?.let { return@map it.row }
        val unclearGesture = unclearGestureIn(sources, videoInspectedInputs)
        val byAgent = step.kind == StepKind.AGENT
        val needsReview = byAgent && (unclearGesture != null || step.reviewReason != null)
        val external = sources.singleOrNull()?.takeIf { it.row.isExternalEvent }
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
            kind = if (external != null) RecordedInputKind.EXTERNAL else null,
            stepKind = step.kind,
            script = step.script,
            manualCompletion = step.manualCompletion,
            stateChanges = sources.flatMap { it.row.stateChanges }.distinct(),
            reviewReason = if (unclearGesture != null && byAgent) unclearGestureReason(unclearGesture) else step.reviewReason,
            screenshotSource = screen?.source,
            screenshotAcquiredAtMs = screen?.acquiredAtMs,
            screenshotAcquisitionFinishedAtMs = screen?.acquisitionFinishedAtMs,
            screenshotTimingUncertain = screen?.timingUncertain ?: true,
            screenshotVerifiedMoment = screen?.let { "after" },
        )
    }

/** The first tap or long press among [sources] with neither a resolved target nor inspected video nor a trustworthy before screenshot. */
private fun unclearGestureIn(sources: List<RewriteInput>, videoInspectedInputs: Set<Int>): RewriteInput? = sources.firstOrNull { input ->
    input.row.kind in setOf(RecordedInputKind.TAP, RecordedInputKind.LONG_PRESS) &&
        input.row.tappedElement == null && input.screenshot("before") == null && input.number !in videoInspectedInputs
}

private fun unclearGestureReason(input: RewriteInput): String =
    "The recorded ${input.row.kind?.name?.lowercase()} has neither a resolved target nor trustworthy inspected video/screenshot evidence. " +
        "Identify the target and expected result."

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

/** How the brief marks an author-added input; the prompt rules in RecordingRewriteBrief.kt refer to this exact label. */
internal const val FIXED_STEP_LABEL = "AUTHOR STEP, keep as is"

private const val MAX_ACTION_CHARS = 300

/**
 * One input as a line of text. The action is the row's current text (a hidden password stays hidden). BRIEF is what the AI is
 * shown (it adds the screens and which images exist); HINT is what the runner of the finished step keeps.
 */
internal fun RewriteInput.describe(mode: RewriteDescription, evidenceIds: RewriteEvidenceIds? = null): String = buildString {
    append(number).append(". ")
    if (fixed) {
        // Not a recording: the person wrote this step. It is shown whole (the answer must repeat it exactly), never cut to a preview.
        append('[').append(FIXED_STEP_LABEL).append("] action: \"").append(row.action.trim()).append("\" | expected: \"")
        append(row.expected.trim()).append('"')
        if (row.optional) append(" | optional: true | condition: \"").append(row.condition?.trim().orEmpty()).append('"')
        if (row.stepKind != StepKind.AGENT) append(" | performed by: ").append(row.stepKind.name.lowercase()).append(" (not an AI step)")
        return@buildString
    }
    if (row.isExternalEvent) {
        appendExternalEvent(this@describe, mode)
        return@buildString
    }
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
    if (row.stateChanges.isNotEmpty()) append(" | device changes after it: ").append(stateChangesText(row.stateChanges))
    if (mode == RewriteDescription.BRIEF) appendBriefDetails(this@describe, evidenceIds)
}

private const val MAX_STATE_CHANGES_TEXT_CHARS = 400

private fun stateChangesText(events: List<EnvironmentEvent>): String = events.joinToString("; ") { it.summary() }.take(MAX_STATE_CHANGES_TEXT_CHARS)

/**
 * An external event, which is not a recording of anything the person did: what was detected, the step it is proposed as (a tester or a
 * script does it) and the device-state check that would notice it done. The AI keeps it as a step of its own.
 */
private fun StringBuilder.appendExternalEvent(input: RewriteInput, mode: RewriteDescription) {
    val row = input.row
    if (mode == RewriteDescription.BRIEF) append("[EXTERNAL EVENT] ")
    append("detected: ").append(stateChangesText(row.stateChanges))
    append(" | proposed step for a tester: \"").append(row.action.take(MAX_ACTION_CHARS)).append('"')
    row.checks.filterIsInstance<StepCheck.DeviceState>().forEach { check ->
        append(" | proposed check: ").append(check.key).append(" matches /").append(check.expectedRegex.take(MAX_ACTION_CHARS)).append('/')
    }
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
