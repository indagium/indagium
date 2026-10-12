package com.indagium.testing.store

import com.indagium.testing.model.CASE_ID_PREFIX
import com.indagium.testing.model.CHECK_ID_PREFIX
import com.indagium.testing.model.DEFAULT_DEVICE_STATE_WITHIN_MS
import com.indagium.testing.model.DEFAULT_LOG_ABSENT_FOR_MS
import com.indagium.testing.model.DEFAULT_LOG_WITHIN_MS
import com.indagium.testing.model.DEFAULT_MANUAL_STEP_TIMEOUT_MS
import com.indagium.testing.model.DEFAULT_SCRIPT_EXIT_CODE
import com.indagium.testing.model.DEFAULT_SCRIPT_OUTPUT_CAP_BYTES
import com.indagium.testing.model.DEFAULT_SCRIPT_TIMEOUT_MS
import com.indagium.testing.model.DEFAULT_STEP_MAX_TOOL_CALLS
import com.indagium.testing.model.DEFAULT_STEP_RETRIES
import com.indagium.testing.model.DEFAULT_STEP_TIMEOUT_MS
import com.indagium.testing.model.EXAMPLE_ID_PREFIX
import com.indagium.testing.model.HOOK_ID_PREFIX
import com.indagium.testing.model.HookItem
import com.indagium.testing.model.MAX_RECORDED_INPUT_DURATION_MS
import com.indagium.testing.model.MAX_RECORDED_INPUT_EVENTS
import com.indagium.testing.model.MAX_RECORDED_INPUT_GAP_MS
import com.indagium.testing.model.MAX_RECORDED_INPUT_POINTS
import com.indagium.testing.model.ManualCompletion
import com.indagium.testing.model.NormalizedGestureBounds
import com.indagium.testing.model.NormalizedGesturePoint
import com.indagium.testing.model.OnFailure
import com.indagium.testing.model.RecordedGestureHint
import com.indagium.testing.model.RecordedGestureTarget
import com.indagium.testing.model.RecordedInput
import com.indagium.testing.model.RecordedInputEvent
import com.indagium.testing.model.RecordedTouchKind
import com.indagium.testing.model.SCRIPT_ID_PREFIX
import com.indagium.testing.model.SHARED_STEP_ID_PREFIX
import com.indagium.testing.model.STEP_ID_PREFIX
import com.indagium.testing.model.SUITE_ID_PREFIX
import com.indagium.testing.model.ScriptCall
import com.indagium.testing.model.ScriptParam
import com.indagium.testing.model.ScriptParamType
import com.indagium.testing.model.ScriptPermission
import com.indagium.testing.model.ScriptTarget
import com.indagium.testing.model.SharedStep
import com.indagium.testing.model.StepCheck
import com.indagium.testing.model.StepExample
import com.indagium.testing.model.StepIntent
import com.indagium.testing.model.StepKind
import com.indagium.testing.model.TestCase
import com.indagium.testing.model.TestScript
import com.indagium.testing.model.TestStep
import com.indagium.testing.model.TestSuite
import com.indagium.testing.model.TestVariable
import com.indagium.testing.model.TimedGesturePoint
import com.indagium.testing.model.VARIABLE_ID_PREFIX
import com.indagium.testing.model.isSafeId
import com.indagium.testing.model.newPrefixedId
import com.indagium.testing.model.usesReplay
import com.indagium.testing.model.usesStepKinds
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonObjectBuilder
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put

// JSON codec for the test library files, on the kotlinx.serialization RUNTIME API only (buildJsonObject /
// parseToJsonElement) — the project does not apply the serialization compiler plugin.
//
//   suites/<id>.json  {"format":"indagium-test-suite","version":2,"suite":{...}}
//   library.json      {"format":"indagium-test-library","version":2,"suiteOrder":[...],"scripts":[...],"sharedSteps":[...]}
//
// Version policy: files are written at the BASE version (2) unless they hold something an older build would hand to the wrong
// actor: a MANUAL or SCRIPT step (it would give "plug in the USB device" to an AI agent) or a device-state check. Those are
// written at the STEP-KINDS version (3), which every build that predates them opens read-only. Recorded gesture hints use version 4,
// which earlier builds also open read-only to avoid dropping target and geometry provenance. A REPLAY step uses version 5: a build
// that predates it would read `"kind":"REPLAY"` as an unknown kind (it falls back to AGENT, so an AI agent would perform the step from
// its text) and could rewrite the file without the step's recorded input, so it must open the file read-only instead. Screenshot roles
// also require version 5: an older build would mistake a recorded BEFORE reference for an expected result. The optional
// `recordedInput` object of a step that is not REPLAY does NOT raise the version: an older build ignores it and, if it saves the
// suite, drops only that enrichment (the step stays a working AI step). TEST_*_FILE_VERSION is the newest version this build reads.
// A suite with only AI steps therefore stays byte-identical to what earlier builds wrote.
//
// Reads are tolerant: unknown keys are ignored, a missing or mistyped field takes its default, an
// entry of an unknown kind is skipped, and a missing/duplicate/unsafe id is replaced by a fresh one.
// Only the envelope is strict. A file with a NEWER version still decodes but is flagged readOnly so
// the store never rewrites it (which would drop fields this build does not know).

const val TEST_SUITE_FILE_FORMAT = "indagium-test-suite"
const val TEST_LIBRARY_FILE_FORMAT = "indagium-test-library"
const val TEST_SUITE_FILE_VERSION = 5
const val TEST_LIBRARY_FILE_VERSION = 5
const val TEST_SUITE_REPLAY_FILE_VERSION = 5
const val TEST_LIBRARY_REPLAY_FILE_VERSION = 5
const val TEST_SUITE_GESTURE_HINT_FILE_VERSION = 4
const val TEST_LIBRARY_GESTURE_HINT_FILE_VERSION = 4
const val TEST_SUITE_BASE_FILE_VERSION = 2
const val TEST_LIBRARY_BASE_FILE_VERSION = 2
const val TEST_SUITE_STEP_KIND_FILE_VERSION = 3
const val TEST_LIBRARY_STEP_KIND_FILE_VERSION = 3

// The `type` values of the polymorphic entries. A reader skips an entry of any other type, so a caller
// that must not lose input silently (the MCP authoring tools) checks against these first.
val CHECK_TYPE_NAMES: List<String> = listOf("logAppears", "logAbsent", "screenJudge", "scriptResult", "askJudge", "deviceState")
val EXAMPLE_TYPE_NAMES: List<String> = listOf("goldenScreenshot", "referenceLog")
val HOOK_TYPE_NAMES: List<String> = listOf("script", "shared")

private val prettyJson = Json { prettyPrint = true }

data class DecodedSuiteFile(val suite: TestSuite, val readOnly: Boolean)

data class DecodedLibraryFile(
    val suiteOrder: List<String>,
    val scripts: List<TestScript>,
    val sharedSteps: List<SharedStep>,
    val readOnly: Boolean,
)

// ── Tolerant readers ─────────────────────────────────────────────────

internal fun JsonObject.str(key: String, default: String = ""): String = (this[key] as? JsonPrimitive)?.contentOrNull ?: default

internal fun JsonObject.optStr(key: String): String? = (this[key] as? JsonPrimitive)?.contentOrNull

internal fun JsonObject.long(key: String, default: Long): Long = (this[key] as? JsonPrimitive)?.longOrNull ?: default

internal fun JsonObject.int(key: String, default: Int): Int = (this[key] as? JsonPrimitive)?.intOrNull ?: default

internal fun JsonObject.bool(key: String, default: Boolean): Boolean = (this[key] as? JsonPrimitive)?.booleanOrNull ?: default

internal fun JsonObject.objects(key: String): List<JsonObject> = (this[key] as? JsonArray)?.mapNotNull { it as? JsonObject }.orEmpty()

internal fun JsonObject.strings(key: String): List<String> =
    (this[key] as? JsonArray)?.mapNotNull { (it as? JsonPrimitive)?.takeIf { p -> p.isString }?.content }.orEmpty()

internal fun JsonObject.stringMap(key: String): Map<String, String> {
    val obj = this[key] as? JsonObject ?: return emptyMap()
    val out = LinkedHashMap<String, String>()
    obj.forEach { (k, v) -> (v as? JsonPrimitive)?.contentOrNull?.let { out[k] = it } }
    return out
}

/** Hands out ids for one file: a stored id is kept when it is safe and not yet used, otherwise a fresh one is made. */
internal class IdAllocator {
    private val seen = HashSet<String>()

    fun next(raw: JsonElement?, prefix: String): String {
        val stored = (raw as? JsonPrimitive)?.contentOrNull
        if (stored != null && isSafeId(stored) && seen.add(stored)) return stored
        while (true) {
            val fresh = newPrefixedId(prefix)
            if (seen.add(fresh)) return fresh
        }
    }
}

internal inline fun <reified E : Enum<E>> JsonObject.enumOr(key: String, default: E): E =
    optStr(key)?.let { name -> enumValues<E>().firstOrNull { it.name == name } } ?: default

// ── Writers ──────────────────────────────────────────────────────────

private fun JsonObjectBuilder.putStringMap(key: String, map: Map<String, String>) {
    put(key, buildJsonObject { map.forEach { (k, v) -> put(k, v) } })
}

internal fun checkToJson(check: StepCheck): JsonObject = buildJsonObject {
    put("id", check.id)
    when (check) {
        is StepCheck.LogAppears -> {
            put("type", "logAppears")
            check.tag?.let { put("tag", it) }
            put("regex", check.regex)
            put("withinMs", check.withinMs)
        }
        is StepCheck.LogAbsent -> {
            put("type", "logAbsent")
            check.tag?.let { put("tag", it) }
            put("regex", check.regex)
            put("forMs", check.forMs)
        }
        is StepCheck.ScreenJudge -> {
            put("type", "screenJudge")
            put("text", check.text)
            check.exampleRef?.let { put("exampleRef", it) }
        }
        is StepCheck.ScriptResult -> {
            put("type", "scriptResult")
            put("scriptId", check.scriptId)
            putStringMap("args", check.args)
            put("exitCode", check.exitCode)
            check.stdoutContains?.let { put("stdoutContains", it) }
        }
        is StepCheck.AskJudge -> {
            put("type", "askJudge")
            put("text", check.text)
        }
        is StepCheck.DeviceState -> {
            put("type", "deviceState")
            put("key", check.key)
            put("expectedRegex", check.expectedRegex)
            put("withinMs", check.withinMs)
        }
    }
}

internal fun exampleToJson(example: StepExample): JsonObject = buildJsonObject {
    put("id", example.id)
    put("caption", example.caption)
    when (example) {
        is StepExample.GoldenScreenshot -> {
            put("type", "goldenScreenshot")
            put("assetPath", example.assetPath)
            if (example.role != com.indagium.testing.model.ScreenshotRole.EXPECTED) put("role", example.role.name.lowercase())
        }
        is StepExample.ReferenceLog -> {
            put("type", "referenceLog")
            put("text", example.text)
        }
    }
}

internal fun scriptCallToJson(call: ScriptCall): JsonObject = buildJsonObject {
    put("scriptId", call.scriptId)
    putStringMap("args", call.args)
    put("exitCode", call.exitCode)
    call.stdoutContains?.let { put("stdoutContains", it) }
}

internal fun stepToJson(step: TestStep): JsonObject = buildJsonObject {
    put("id", step.id)
    put("action", step.action)
    put("expected", step.expected)
    put("timeoutMs", step.timeoutMs)
    put("retries", step.retries)
    put("maxToolCalls", step.maxToolCalls)
    put("onFailure", step.onFailure.name)
    put("optional", step.optional)
    step.condition?.let { put("condition", it) }
    put("checks", buildJsonArray { step.checks.forEach { add(checkToJson(it)) } })
    put("examples", buildJsonArray { step.examples.forEach { add(exampleToJson(it)) } })
    // Appended last, and only when set: a step that only an AI agent performs is written exactly as before step kinds existed.
    if (step.kind != StepKind.AGENT) put("kind", step.kind.name)
    step.script?.let { put("script", scriptCallToJson(it)) }
    if (step.kind == StepKind.MANUAL) put("manualCompletion", step.manualCompletion.name)
    // Appended last, and only when set. It links a step to evidence stored beside the suite; an older build ignores it (and drops it if it
    // saves the suite), which loses only the link, so the envelope version stays: see the version policy above.
    step.recordingRef?.let { put("recordingRef", it) }
    if (step.intent != StepIntent.PERFORM_ACTION) put("intent", step.intent.wire)
    step.precondition?.let { put("precondition", it) }
    if (step.gestureHints.isNotEmpty()) {
        put("gestureHints", buildJsonArray { step.gestureHints.take(MAX_GESTURE_HINTS).forEach { add(gestureHintToJson(it)) } })
    }
    step.recordedInput?.let { put("recordedInput", recordedInputToJson(it)) }
}

// ── Recorded input (REPLAY steps) ────────────────────────────────────

private const val COORDINATE_DECIMALS_SCALE = 100_000.0

private fun roundedUnit(value: Double): Double = Math.round(value * COORDINATE_DECIMALS_SCALE) / COORDINATE_DECIMALS_SCALE

internal fun recordedInputToJson(input: RecordedInput): JsonObject = buildJsonObject {
    if (input.settleMs > 0L) put("settleMs", input.settleMs.coerceAtMost(MAX_RECORDED_INPUT_GAP_MS))
    put("events", buildJsonArray { input.events.forEach { add(recordedEventToJson(it)) } })
}

private fun recordedEventToJson(event: RecordedInputEvent): JsonObject = buildJsonObject {
    when (event) {
        is RecordedInputEvent.Touch -> {
            put("type", "touch")
            put("kind", event.kind.name)
            put("durationMs", event.durationMs)
            event.sourceScreenWidth?.takeIf { it in MIN_GESTURE_SCREEN_DIMENSION..MAX_GESTURE_SCREEN_DIMENSION }?.let { put("screenWidth", it) }
            event.sourceScreenHeight?.takeIf { it in MIN_GESTURE_SCREEN_DIMENSION..MAX_GESTURE_SCREEN_DIMENSION }?.let { put("screenHeight", it) }
            event.orientationQuarterTurns?.takeIf { it in 0..MAX_GESTURE_ORIENTATION_QUARTER_TURNS }?.let { put("orientation", it) }
            put(
                "points",
                buildJsonArray {
                    event.points.forEach { point ->
                        add(buildJsonObject { put("x", roundedUnit(point.x)); put("y", roundedUnit(point.y)); put("t", point.tMs) })
                    }
                },
            )
        }
        is RecordedInputEvent.Key -> {
            put("type", "key")
            put("key", event.key)
        }
        is RecordedInputEvent.Panel -> {
            put("type", "panel")
            put("panel", event.panel)
        }
    }
    if (event.gapBeforeMs > 0L) put("gapBeforeMs", event.gapBeforeMs.coerceAtMost(MAX_RECORDED_INPUT_GAP_MS))
}

@Suppress("ReturnCount") // Invalid recorded fields must reject the entire event before any input can be replayed.
private fun decodeRecordedEvent(o: JsonObject): RecordedInputEvent? {
    val gap = o.strictLong("gapBeforeMs", 0L)?.takeIf { it in 0L..MAX_RECORDED_INPUT_GAP_MS } ?: return null
    return when (o.str("type")) {
        "touch" -> {
            val kind = RecordedTouchKind.entries.firstOrNull { it.name == o.str("kind") } ?: return null
            val rawPoints = o["points"] as? JsonArray ?: return null
            if (rawPoints.size !in 1..MAX_RECORDED_INPUT_POINTS || rawPoints.any { it !is JsonObject }) return null
            val points = rawPoints.map { raw ->
                val point = raw as JsonObject
                val x = point.safeUnit("x") ?: return null
                val y = point.safeUnit("y") ?: return null
                val t = point.strictLong("t")?.takeIf { it in 0L..MAX_RECORDED_INPUT_DURATION_MS } ?: return null
                TimedGesturePoint(x, y, t)
            }
            val duration = o.strictLong("durationMs")?.takeIf { it in 0L..MAX_RECORDED_INPUT_DURATION_MS } ?: return null
            RecordedInputEvent.Touch(
                kind = kind,
                points = points,
                durationMs = duration,
                sourceScreenWidth = o.int("screenWidth", 0).takeIf { it in MIN_GESTURE_SCREEN_DIMENSION..MAX_GESTURE_SCREEN_DIMENSION },
                sourceScreenHeight = o.int("screenHeight", 0).takeIf { it in MIN_GESTURE_SCREEN_DIMENSION..MAX_GESTURE_SCREEN_DIMENSION },
                orientationQuarterTurns = o.int("orientation", -1).takeIf { it in 0..MAX_GESTURE_ORIENTATION_QUARTER_TURNS },
                gapBeforeMs = gap,
            )
        }
        "key" -> o.str("key").takeIf { it.isNotBlank() }?.let { RecordedInputEvent.Key(it, gap) }
        "panel" -> o.str("panel").takeIf { it.isNotBlank() }?.let { RecordedInputEvent.Panel(it, gap) }
        else -> null
    }
}

/**
 * The recorded input of a step, or null when there is none or any of its inputs cannot be understood (an input of a kind a newer build
 * wrote): a replay of a partial list would do something other than what was recorded, so it is dropped as a whole.
 */
internal fun decodeRecordedInput(o: JsonObject?): RecordedInput? {
    o ?: return null
    val raw = o["events"] as? JsonArray ?: return null
    if (raw.isEmpty() || raw.size > MAX_RECORDED_INPUT_EVENTS || raw.any { it !is JsonObject }) return null
    val events = raw.map { decodeRecordedEvent(it as JsonObject) ?: return null }
    val settle = o.strictLong("settleMs", 0L)?.takeIf { it in 0L..MAX_RECORDED_INPUT_GAP_MS } ?: return null
    return RecordedInput(events, settle)
}

private fun JsonObject.strictLong(key: String, default: Long? = null): Long? =
    if (key !in this) default else (this[key] as? JsonPrimitive)?.longOrNull

private const val MAX_GESTURE_HINTS = 24
private const val MAX_GESTURE_HINT_PATH_POINTS = 10
private const val MAX_GESTURE_HINT_TARGETS = 3
private const val MAX_GESTURE_HINT_KIND_CHARS = 32
private const val MAX_GESTURE_HINT_DURATION_MS = 600_000L
private const val MAX_GESTURE_SCREEN_DIMENSION = 20_000
private const val MIN_GESTURE_SCREEN_DIMENSION = 1
private const val MAX_GESTURE_ORIENTATION_QUARTER_TURNS = 3
private const val MAX_GESTURE_TARGET_LABEL_CHARS = 120
private const val MAX_GESTURE_TARGET_RESOURCE_ID_CHARS = 200
private const val MAX_GESTURE_TARGET_CLASS_CHARS = 80
private const val MIN_NORMALIZED_GESTURE_COORDINATE = 0.0
private const val MAX_NORMALIZED_GESTURE_COORDINATE = 1.0

private fun gestureHintToJson(hint: RecordedGestureHint): JsonObject = buildJsonObject {
    put("kind", hint.kind.take(MAX_GESTURE_HINT_KIND_CHARS))
    hint.durationMs?.let { put("durationMs", it.coerceIn(0L, MAX_GESTURE_HINT_DURATION_MS)) }
    hint.sourceScreenWidth?.takeIf { it in MIN_GESTURE_SCREEN_DIMENSION..MAX_GESTURE_SCREEN_DIMENSION }?.let { put("sourceScreenWidth", it) }
    hint.sourceScreenHeight?.takeIf { it in MIN_GESTURE_SCREEN_DIMENSION..MAX_GESTURE_SCREEN_DIMENSION }?.let { put("sourceScreenHeight", it) }
    hint.orientationQuarterTurns?.takeIf { it in 0..MAX_GESTURE_ORIENTATION_QUARTER_TURNS }?.let { put("orientationQuarterTurns", it) }
    put(
        "path",
        buildJsonArray {
            hint.path.take(MAX_GESTURE_HINT_PATH_POINTS).forEach { point ->
                add(buildJsonObject { put("x", point.x); put("y", point.y) })
            }
        },
    )
    hint.targetUnderTouch?.let { put("targetUnderTouch", gestureTargetToJson(it)) }
    if (hint.clickableTargets.isNotEmpty()) {
        put("clickableTargets", buildJsonArray { hint.clickableTargets.take(MAX_GESTURE_HINT_TARGETS).forEach { add(gestureTargetToJson(it)) } })
    }
    hint.touchWithinTarget?.let { point -> put("touchWithinTarget", buildJsonObject { put("x", point.x); put("y", point.y) }) }
}

private fun gestureTargetToJson(target: RecordedGestureTarget): JsonObject = buildJsonObject {
    put("text", target.text.take(MAX_GESTURE_TARGET_LABEL_CHARS))
    put("contentDescription", target.contentDescription.take(MAX_GESTURE_TARGET_LABEL_CHARS))
    put("resourceId", target.resourceId.take(MAX_GESTURE_TARGET_RESOURCE_ID_CHARS))
    put("className", target.className.take(MAX_GESTURE_TARGET_CLASS_CHARS))
    target.bounds?.let { bounds -> put("bounds", buildJsonObject {
        put("left", bounds.left); put("top", bounds.top); put("right", bounds.right); put("bottom", bounds.bottom)
    }) }
}

private fun JsonObject.safeUnit(key: String): Double? =
    (this[key] as? JsonPrimitive)?.doubleOrNull?.takeIf {
        it.isFinite() && it in MIN_NORMALIZED_GESTURE_COORDINATE..MAX_NORMALIZED_GESTURE_COORDINATE
    }

private fun normalizedBounds(left: Double?, top: Double?, right: Double?, bottom: Double?): NormalizedGestureBounds? {
    val validLeft = left ?: return null
    val validTop = top ?: return null
    val validRight = right ?: return null
    val validBottom = bottom ?: return null
    if (validRight < validLeft || validBottom < validTop) return null
    return NormalizedGestureBounds(validLeft, validTop, validRight, validBottom)
}

private fun decodeGestureTarget(o: JsonObject): RecordedGestureTarget {
    val b = o["bounds"] as? JsonObject
    val left = b?.safeUnit("left")
    val top = b?.safeUnit("top")
    val right = b?.safeUnit("right")
    val bottom = b?.safeUnit("bottom")
    val bounds = normalizedBounds(left, top, right, bottom)
    return RecordedGestureTarget(
        text = o.str("text").take(MAX_GESTURE_TARGET_LABEL_CHARS),
        contentDescription = o.str("contentDescription").take(MAX_GESTURE_TARGET_LABEL_CHARS),
        resourceId = o.str("resourceId").take(MAX_GESTURE_TARGET_RESOURCE_ID_CHARS),
        className = o.str("className").take(MAX_GESTURE_TARGET_CLASS_CHARS),
        bounds = bounds,
    )
}

private fun decodeGestureHint(o: JsonObject): RecordedGestureHint? {
    val kind = o.str("kind").take(MAX_GESTURE_HINT_KIND_CHARS).takeIf(String::isNotBlank) ?: return null

    fun decodePoint(point: JsonObject): NormalizedGesturePoint? {
        val x = point.safeUnit("x") ?: return null
        val y = point.safeUnit("y") ?: return null
        return NormalizedGesturePoint(x, y)
    }
    return RecordedGestureHint(
        kind = kind,
        path = o.objects("path").take(MAX_GESTURE_HINT_PATH_POINTS).mapNotNull(::decodePoint),
        durationMs = (o["durationMs"] as? JsonPrimitive)?.longOrNull?.coerceIn(0L, MAX_GESTURE_HINT_DURATION_MS),
        sourceScreenWidth = o.int("sourceScreenWidth", 0).takeIf { it in MIN_GESTURE_SCREEN_DIMENSION..MAX_GESTURE_SCREEN_DIMENSION },
        sourceScreenHeight = o.int("sourceScreenHeight", 0).takeIf { it in MIN_GESTURE_SCREEN_DIMENSION..MAX_GESTURE_SCREEN_DIMENSION },
        orientationQuarterTurns = o.int("orientationQuarterTurns", -1).takeIf { it in 0..MAX_GESTURE_ORIENTATION_QUARTER_TURNS },
        targetUnderTouch = (o["targetUnderTouch"] as? JsonObject)?.let(::decodeGestureTarget),
        clickableTargets = o.objects("clickableTargets").take(MAX_GESTURE_HINT_TARGETS).map(::decodeGestureTarget),
        touchWithinTarget = (o["touchWithinTarget"] as? JsonObject)?.let(::decodePoint),
    )
}

internal fun caseToJson(case: TestCase): JsonObject = buildJsonObject {
    put("id", case.id)
    put("name", case.name)
    put("description", case.description)
    put("instructions", case.instructions)
    put("preconditions", case.preconditions)
    put("setup", buildJsonArray { case.setup.forEach { add(hookToJson(it)) } })
    put("teardown", buildJsonArray { case.teardown.forEach { add(hookToJson(it)) } })
    case.allowedTools?.let { tools -> put("allowedTools", buildJsonArray { tools.forEach { add(JsonPrimitive(it)) } }) }
    case.creationUsage?.let { put("creationUsage", aiUsageToJson(it)) }
    put("steps", buildJsonArray { case.steps.forEach { add(stepToJson(it)) } })
}

internal fun hookToJson(hook: HookItem): JsonObject = buildJsonObject {
    put("id", hook.id)
    when (hook) {
        is HookItem.Script -> {
            put("type", "script")
            put("scriptId", hook.scriptId)
            putStringMap("args", hook.args)
        }
        is HookItem.Shared -> {
            put("type", "shared")
            put("sharedStepId", hook.sharedStepId)
        }
    }
}

internal fun variableToJson(variable: TestVariable): JsonObject = buildJsonObject {
    put("id", variable.id)
    put("name", variable.name)
    put("value", variable.value)
    put("description", variable.description)
}

internal fun suiteToJson(suite: TestSuite): JsonObject = buildJsonObject {
    put("id", suite.id)
    put("name", suite.name)
    put("description", suite.description)
    put("instructions", suite.instructions)
    put("targetPackage", suite.targetPackage)
    put("deviceProfileHint", suite.deviceProfileHint)
    put("tags", buildJsonArray { suite.tags.forEach { add(JsonPrimitive(it)) } })
    put("createdAt", suite.createdAt)
    put("updatedAt", suite.updatedAt)
    put("setup", buildJsonArray { suite.setup.forEach { add(hookToJson(it)) } })
    put("teardown", buildJsonArray { suite.teardown.forEach { add(hookToJson(it)) } })
    put("variables", buildJsonArray { suite.variables.forEach { add(variableToJson(it)) } })
    put("cases", buildJsonArray { suite.cases.forEach { add(caseToJson(it)) } })
}

internal fun paramToJson(param: ScriptParam): JsonObject = buildJsonObject {
    put("name", param.name)
    put("type", param.type.name)
    put("description", param.description)
    put("required", param.required)
    param.defaultValue?.let { put("defaultValue", it) }
}

internal fun scriptToJson(script: TestScript): JsonObject = buildJsonObject {
    put("id", script.id)
    put("toolName", script.toolName)
    put("description", script.description)
    put("commandTemplate", script.commandTemplate)
    put("target", script.target.name)
    put("timeoutMs", script.timeoutMs)
    put("outputCapBytes", script.outputCapBytes)
    script.workingDir?.let { put("workingDir", it) }
    put("permission", script.permission.name)
    put("params", buildJsonArray { script.params.forEach { add(paramToJson(it)) } })
}

internal fun sharedStepToJson(shared: SharedStep): JsonObject = buildJsonObject {
    put("id", shared.id)
    put("name", shared.name)
    put("description", shared.description)
    put("steps", buildJsonArray { shared.steps.forEach { add(stepToJson(it)) } })
}

/** The envelope version [suite] is written with: base, step-kinds, gesture-hints or replay version as needed. */
private fun TestStep.hasRecordedScreenReferences(): Boolean =
    examples.filterIsInstance<StepExample.GoldenScreenshot>().any { it.role != com.indagium.testing.model.ScreenshotRole.EXPECTED }

fun suiteFileVersionFor(suite: TestSuite): Int = when {
    suite.usesReplay() || suite.cases.any { case -> case.steps.any { it.hasRecordedScreenReferences() } } -> TEST_SUITE_REPLAY_FILE_VERSION
    suite.cases.any { case -> case.steps.any { it.gestureHints.isNotEmpty() } } -> TEST_SUITE_GESTURE_HINT_FILE_VERSION
    suite.usesStepKinds() -> TEST_SUITE_STEP_KIND_FILE_VERSION
    else -> TEST_SUITE_BASE_FILE_VERSION
}

/** The envelope version a library file with these shared steps is written with. */
fun libraryFileVersionFor(sharedSteps: List<SharedStep>): Int =
    when {
        sharedSteps.any { shared -> shared.steps.any { it.usesReplay() || it.hasRecordedScreenReferences() } } -> TEST_LIBRARY_REPLAY_FILE_VERSION
        sharedSteps.any { shared -> shared.steps.any { it.gestureHints.isNotEmpty() } } -> TEST_LIBRARY_GESTURE_HINT_FILE_VERSION
        sharedSteps.any { shared -> shared.steps.any { it.usesStepKinds() } } -> TEST_LIBRARY_STEP_KIND_FILE_VERSION
        else -> TEST_LIBRARY_BASE_FILE_VERSION
    }

fun encodeSuiteFile(suite: TestSuite, externalAssetsRequired: Boolean = false): String = prettyJson.encodeToString(
    JsonObject.serializer(),
    buildJsonObject {
        put("format", TEST_SUITE_FILE_FORMAT)
        put("version", suiteFileVersionFor(suite))
        put("suite", suiteToJson(suite))
        if (externalAssetsRequired) {
            put("externalAssetsRequired", true)
            put(
                "assetWarning",
                "This metadata-only export references golden screenshot files stored separately under assets/<suiteId>/. " +
                    "Copy those files with the suite to preserve image checks.",
            )
        }
    },
)

fun encodeLibraryFile(suiteOrder: List<String>, scripts: List<TestScript>, sharedSteps: List<SharedStep>): String =
    prettyJson.encodeToString(
        JsonObject.serializer(),
        buildJsonObject {
            put("format", TEST_LIBRARY_FILE_FORMAT)
            put("version", libraryFileVersionFor(sharedSteps))
            put("suiteOrder", buildJsonArray { suiteOrder.forEach { add(JsonPrimitive(it)) } })
            put("scripts", buildJsonArray { scripts.forEach { add(scriptToJson(it)) } })
            put("sharedSteps", buildJsonArray { sharedSteps.forEach { add(sharedStepToJson(it)) } })
        },
    )

// ── Readers ──────────────────────────────────────────────────────────

internal fun decodeCheck(o: JsonObject, ids: IdAllocator): StepCheck? {
    val id by lazy { ids.next(o["id"], CHECK_ID_PREFIX) }
    return when (o.str("type")) {
        "logAppears" -> StepCheck.LogAppears(id, o.optStr("tag"), o.str("regex"), o.long("withinMs", DEFAULT_LOG_WITHIN_MS))
        "logAbsent" -> StepCheck.LogAbsent(id, o.optStr("tag"), o.str("regex"), o.long("forMs", DEFAULT_LOG_ABSENT_FOR_MS))
        "screenJudge" -> StepCheck.ScreenJudge(id, o.str("text"), o.optStr("exampleRef"))
        "scriptResult" -> StepCheck.ScriptResult(
            id = id,
            scriptId = o.str("scriptId"),
            args = o.stringMap("args"),
            // An absent key means the default; an explicit null means "any exit code".
            exitCode = if (o.containsKey("exitCode")) (o["exitCode"] as? JsonPrimitive)?.intOrNull else DEFAULT_SCRIPT_EXIT_CODE,
            stdoutContains = o.optStr("stdoutContains"),
        )
        "askJudge" -> StepCheck.AskJudge(id, o.str("text"))
        "deviceState" -> StepCheck.DeviceState(id, o.str("key"), o.str("expectedRegex"), o.long("withinMs", DEFAULT_DEVICE_STATE_WITHIN_MS))
        else -> null
    }
}

internal fun decodeScriptCall(o: JsonObject): ScriptCall? {
    val scriptId = o.str("scriptId").takeIf { it.isNotBlank() } ?: return null
    return ScriptCall(
        scriptId = scriptId,
        args = o.stringMap("args"),
        // An absent key means the default; an explicit null means "any exit code".
        exitCode = if (o.containsKey("exitCode")) (o["exitCode"] as? JsonPrimitive)?.intOrNull else DEFAULT_SCRIPT_EXIT_CODE,
        stdoutContains = o.optStr("stdoutContains"),
    )
}

internal fun decodeExample(o: JsonObject, ids: IdAllocator): StepExample? {
    val id by lazy { ids.next(o["id"], EXAMPLE_ID_PREFIX) }
    return when (o.str("type")) {
        "goldenScreenshot" -> StepExample.GoldenScreenshot(
            id, o.str("assetPath"), o.str("caption"),
            o.optStr("role")?.let { name ->
                com.indagium.testing.model.ScreenshotRole.entries.firstOrNull { it.name.equals(name, ignoreCase = true) }
                    ?: throw IllegalArgumentException("Unknown screenshot role '$name'.")
            } ?: com.indagium.testing.model.ScreenshotRole.EXPECTED,
        )
        "referenceLog" -> StepExample.ReferenceLog(id, o.str("text"), o.str("caption"))
        else -> null
    }
}

private fun decodeStep(o: JsonObject, ids: IdAllocator): TestStep {
    val kind = o.enumOr("kind", StepKind.AGENT)
    return TestStep(
        id = ids.next(o["id"], STEP_ID_PREFIX),
        action = o.str("action"),
        expected = o.str("expected"),
        checks = o.objects("checks").mapNotNull { decodeCheck(it, ids) },
        examples = o.objects("examples").mapNotNull { decodeExample(it, ids) },
        timeoutMs = o.long("timeoutMs", if (kind == StepKind.MANUAL) DEFAULT_MANUAL_STEP_TIMEOUT_MS else DEFAULT_STEP_TIMEOUT_MS),
        retries = o.int("retries", DEFAULT_STEP_RETRIES),
        maxToolCalls = o.int("maxToolCalls", DEFAULT_STEP_MAX_TOOL_CALLS),
        onFailure = o.enumOr("onFailure", OnFailure.STOP_CASE),
        optional = o.bool("optional", false),
        condition = o.optStr("condition"),
        kind = kind,
        script = (o["script"] as? JsonObject)?.let { decodeScriptCall(it) },
        manualCompletion = o.enumOr("manualCompletion", ManualCompletion.TESTER_OR_CHECKS),
        recordingRef = o.optStr("recordingRef")?.takeIf { isSafeId(it) },
        intent = StepIntent.parse(o.optStr("intent")) ?: StepIntent.PERFORM_ACTION,
        precondition = o.optStr("precondition"),
        gestureHints = o.objects("gestureHints").take(MAX_GESTURE_HINTS).mapNotNull(::decodeGestureHint),
        recordedInput = decodeRecordedInput(o["recordedInput"] as? JsonObject),
    )
}

private fun decodeCase(o: JsonObject, ids: IdAllocator): TestCase = TestCase(
    id = ids.next(o["id"], CASE_ID_PREFIX),
    name = o.str("name"),
    description = o.str("description"),
    instructions = o.str("instructions"),
    preconditions = o.str("preconditions"),
    setup = o.objects("setup").mapNotNull { decodeHook(it, ids) },
    teardown = o.objects("teardown").mapNotNull { decodeHook(it, ids) },
    steps = o.objects("steps").map { decodeStep(it, ids) },
    allowedTools = (o["allowedTools"] as? JsonArray)?.let { o.strings("allowedTools").toCollection(LinkedHashSet()) },
    creationUsage = decodeAiUsage(o["creationUsage"]),
)

internal fun decodeHook(o: JsonObject, ids: IdAllocator): HookItem? {
    val id by lazy { ids.next(o["id"], HOOK_ID_PREFIX) }
    return when (o.str("type")) {
        "script" -> HookItem.Script(id, o.str("scriptId"), o.stringMap("args"))
        "shared" -> HookItem.Shared(id, o.str("sharedStepId"))
        else -> null
    }
}

internal fun decodeVariable(o: JsonObject, ids: IdAllocator): TestVariable =
    TestVariable(ids.next(o["id"], VARIABLE_ID_PREFIX), o.str("name"), o.str("value"), o.str("description"))

private fun decodeSuite(o: JsonObject, readOnly: Boolean): TestSuite? {
    val ids = IdAllocator()
    // The suite id names its file, so unlike nested ids a bad one is not repaired: the file is not usable.
    val suiteId = (o["id"] as? JsonPrimitive)?.contentOrNull?.takeIf { isSafeId(it) } ?: return null
    ids.next(JsonPrimitive(suiteId), SUITE_ID_PREFIX)
    return TestSuite(
        id = suiteId,
        name = o.str("name"),
        description = o.str("description"),
        instructions = o.str("instructions"),
        targetPackage = o.str("targetPackage"),
        deviceProfileHint = o.str("deviceProfileHint"),
        tags = o.strings("tags"),
        setup = o.objects("setup").mapNotNull { decodeHook(it, ids) },
        teardown = o.objects("teardown").mapNotNull { decodeHook(it, ids) },
        variables = o.objects("variables").map { decodeVariable(it, ids) },
        cases = o.objects("cases").map { decodeCase(it, ids) },
        createdAt = o.long("createdAt", 0L),
        updatedAt = o.long("updatedAt", 0L),
        readOnly = readOnly,
    )
}

internal fun decodeParam(o: JsonObject): ScriptParam? {
    val name = o.str("name").takeIf { it.isNotBlank() } ?: return null
    return ScriptParam(
        name = name,
        type = o.enumOr("type", ScriptParamType.STRING),
        description = o.str("description"),
        required = o.bool("required", true),
        defaultValue = o.optStr("defaultValue"),
    )
}

internal fun decodeScript(o: JsonObject, ids: IdAllocator): TestScript? {
    val toolName = o.str("toolName").takeIf { it.isNotBlank() } ?: return null
    return TestScript(
        id = ids.next(o["id"], SCRIPT_ID_PREFIX),
        toolName = toolName,
        description = o.str("description"),
        params = o.objects("params").mapNotNull { decodeParam(it) },
        commandTemplate = o.str("commandTemplate"),
        target = o.enumOr("target", ScriptTarget.HOST_SHELL),
        timeoutMs = o.long("timeoutMs", DEFAULT_SCRIPT_TIMEOUT_MS),
        outputCapBytes = o.int("outputCapBytes", DEFAULT_SCRIPT_OUTPUT_CAP_BYTES),
        workingDir = o.optStr("workingDir")?.takeIf { it.isNotBlank() },
        permission = o.enumOr("permission", ScriptPermission.ASK),
    )
}

private fun decodeSharedStep(o: JsonObject, ids: IdAllocator): SharedStep = SharedStep(
    id = ids.next(o["id"], SHARED_STEP_ID_PREFIX),
    name = o.str("name"),
    description = o.str("description"),
    steps = o.objects("steps").map { decodeStep(it, ids) },
)

/** Validates the envelope shared by both file kinds; the failure message is safe to show as-is. */
private fun readEnvelope(text: String, expectedFormat: String, what: String): Pair<JsonObject, Boolean> {
    val root = runCatching { Json.parseToJsonElement(text).jsonObject }
        .getOrElse { error("This file is not a $what (it is not valid JSON).") }
    if (root.str("format") != expectedFormat) error("This file is not an Indagium $what.")
    val version = (root["version"] as? JsonPrimitive)?.intOrNull ?: 1
    val supported = if (expectedFormat == TEST_SUITE_FILE_FORMAT) TEST_SUITE_FILE_VERSION else TEST_LIBRARY_FILE_VERSION
    return root to (version > supported)
}

/** Never throws; failure carries a message that is safe to show as-is. */
fun decodeSuiteFile(text: String): Result<DecodedSuiteFile> = runCatching {
    val (root, newer) = readEnvelope(text, TEST_SUITE_FILE_FORMAT, "test suite")
    val suiteObject = root["suite"] as? JsonObject ?: error("This test suite file has no suite in it.")
    DecodedSuiteFile(decodeSuite(suiteObject, readOnly = newer) ?: error("This test suite file has no usable suite id."), newer)
}

/** Never throws; failure carries a message that is safe to show as-is. */
fun decodeLibraryFile(text: String): Result<DecodedLibraryFile> = runCatching {
    val (root, newer) = readEnvelope(text, TEST_LIBRARY_FILE_FORMAT, "test library")
    val ids = IdAllocator()
    DecodedLibraryFile(
        suiteOrder = root.strings("suiteOrder").distinct(),
        scripts = root.objects("scripts").mapNotNull { decodeScript(it, ids) },
        sharedSteps = root.objects("sharedSteps").map { decodeSharedStep(it, ids) },
        readOnly = newer,
    )
}

// ── Run snapshots ────────────────────────────────────────────────────
// A test run freezes the suite, the library scripts and the shared steps it used inside run.json (TestRunCodec.kt),
// in exactly the shape these files use; the helpers below let that codec read them back without exposing the privates.

internal fun decodeSuiteObject(o: JsonObject): TestSuite? = decodeSuite(o, readOnly = false)

internal fun decodeScriptObjects(objects: List<JsonObject>): List<TestScript> {
    val ids = IdAllocator()
    return objects.mapNotNull { decodeScript(it, ids) }
}

internal fun decodeSharedStepObjects(objects: List<JsonObject>): List<SharedStep> {
    val ids = IdAllocator()
    return objects.map { decodeSharedStep(it, ids) }
}
