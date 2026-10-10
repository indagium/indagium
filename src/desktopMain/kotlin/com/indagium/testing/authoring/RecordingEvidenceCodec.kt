package com.indagium.testing.authoring

import com.indagium.testing.device.MAX_UI_TREE_NODES
import com.indagium.testing.device.UiNode
import com.indagium.testing.model.ManualCompletion
import com.indagium.testing.model.StepKind
import com.indagium.testing.store.IdAllocator
import com.indagium.testing.store.bool
import com.indagium.testing.store.checkToJson
import com.indagium.testing.store.decodeCheck
import com.indagium.testing.store.decodeScriptCall
import com.indagium.testing.store.enumOr
import com.indagium.testing.store.int
import com.indagium.testing.store.long
import com.indagium.testing.store.objects
import com.indagium.testing.store.optStr
import com.indagium.testing.store.scriptCallToJson
import com.indagium.testing.store.str
import com.indagium.testing.store.strings
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonObjectBuilder
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put

// The `recording.json` of stored recording evidence: {"format":"indagium-test-recording","version":1, ...}. It carries everything the review
// panel and the AI rewrite read from a stopped recording and nothing they do not: the rows as they were applied (and, after a rewrite, the
// recorded rows they replaced), the screen readings (hierarchy nodes, with a password field's text already blank), the screenshots (as
// references to `screens/<hash>.jpg`), the device events, the steps the recording produced, and what was left out for privacy or size.
//
// Version policy: version 1 is the only one. A file with a NEWER version is refused (reopening would drop what this build cannot read and
// saving it again would lose it); a missing or mistyped field takes its default, an unknown key is ignored.

const val RECORDING_FILE_FORMAT = "indagium-test-recording"
const val RECORDING_FILE_VERSION = 1

/** A password-sensitive recording keeps no video and no screen readings or images inside these windows; this is what the file says about it. */
const val RECORDING_PRIVACY_STATEMENT =
    "A password was typed while recording. The video was not stored, and the screenshots and screen readings from the time a password could " +
        "have been on screen were not stored either; only their time span is kept."

private const val MAX_ROWS = 400
private const val MAX_SCREEN_STATES = 400
private const val MAX_PROBE_SCREENSHOTS = 400
private const val MAX_GESTURE_POINTS = 64
private const val MAX_STATE_CHANGES = 32
private const val MAX_WARNINGS = 60
private const val MAX_STEP_IDS = 400
private const val MAX_TEXT_CHARS = 20_000
private val IMAGE_REF = Regex("^[0-9a-f]{16,64}$")
private val prettyJson = Json { prettyPrint = true }

/** What a stored recording says about the privacy rules it was saved under. */
internal data class StoredPrivacy(
    /** True when any input touched a password field; the video is then not stored. */
    val passwordSensitive: Boolean = false,
    /** The wall-clock windows (inclusive, ms) in which the screen could have shown a password. */
    val windows: List<LongRange> = emptyList(),
    val droppedScreenStates: Int = 0,
    val droppedScreenshots: Int = 0,
)

/** The figures of the stored video, or why there is none. */
internal data class StoredVideoInfo(
    val stored: Boolean,
    /** Why no video was stored (null when it was). */
    val reason: String? = null,
    val bytes: Long = 0L,
    val sessionStartedAtMs: Long = 0L,
    val stoppedDurationMs: Long = 0L,
    val stoppedWallClockMs: Long = -1L,
    val gapCount: Int = 0,
    val capReached: Boolean = false,
    val warning: String? = null,
    val packetCount: Long = 0L,
)

/** One stored recording, decoded: the data a stopped [TestStepRecordingSession] is made of, plus the facts about the storage. */
internal data class StoredRecording(
    val recordingId: String,
    val caseId: String,
    val deviceSerial: String,
    val createdAtMs: Long,
    val updatedAtMs: Long,
    /** The ids of the library steps this recording produced, in order. */
    val stepIds: List<String>,
    /** The reviewed rows as they were applied. */
    val rows: List<RecordedTestStep>,
    /** The recorded rows an AI rewrite replaced; null when the rows were never rewritten. */
    val rawRows: List<RecordedTestStep>?,
    val rewriteApplied: Boolean,
    val rewriteNotes: String,
    val excludedSourceInputIds: Set<String>,
    val screenStates: List<RecordingScreenState>,
    val probeScreenshots: List<RecordingScreenshotEvidence>,
    val warnings: List<String>,
    val privacy: StoredPrivacy,
    val video: StoredVideoInfo,
    /** Things the saving left out that are not privacy related (a size cap), in words. */
    val notices: List<String> = emptyList(),
)

/** Where images go in and come from: [put] stores the bytes and returns the reference, [get] reads them back (null when missing). */
internal interface RecordingImageStore {
    fun put(bytes: ByteArray): String

    fun get(ref: String): ByteArray?
}

// ── Writer ───────────────────────────────────────────────────────────

internal fun encodeStoredRecording(recording: StoredRecording, images: RecordingImageStore): String = prettyJson.encodeToString(
    JsonObject.serializer(),
    buildJsonObject {
        put("format", RECORDING_FILE_FORMAT)
        put("version", RECORDING_FILE_VERSION)
        put("recordingId", recording.recordingId)
        put("caseId", recording.caseId)
        put("deviceSerial", recording.deviceSerial)
        put("createdAt", recording.createdAtMs)
        put("updatedAt", recording.updatedAtMs)
        put("stepIds", buildJsonArray { recording.stepIds.forEach { add(JsonPrimitive(it)) } })
        put("privacy", privacyJson(recording.privacy, recording.video.stored))
        put("video", videoJson(recording.video))
        put(
            "rewrite",
            buildJsonObject {
                put("applied", recording.rewriteApplied)
                put("notes", recording.rewriteNotes)
                put("excludedSourceInputIds", buildJsonArray { recording.excludedSourceInputIds.sorted().forEach { add(JsonPrimitive(it)) } })
            },
        )
        put("rows", buildJsonArray { recording.rows.forEach { add(rowJson(it, images)) } })
        recording.rawRows?.let { raw -> put("rawRows", buildJsonArray { raw.forEach { add(rowJson(it, images)) } }) }
        put("screenStates", buildJsonArray { recording.screenStates.forEach { add(stateJson(it, images)) } })
        put("probeScreenshots", buildJsonArray { recording.probeScreenshots.forEach { add(evidenceJson(it, images)) } })
        put("warnings", buildJsonArray { recording.warnings.forEach { add(JsonPrimitive(it)) } })
        put("notices", buildJsonArray { recording.notices.forEach { add(JsonPrimitive(it)) } })
    },
)

private fun privacyJson(privacy: StoredPrivacy, videoStored: Boolean): JsonObject = buildJsonObject {
    put("passwordSensitive", privacy.passwordSensitive)
    put("videoStored", videoStored)
    put("droppedScreenStates", privacy.droppedScreenStates)
    put("droppedScreenshots", privacy.droppedScreenshots)
    put("windows", buildJsonArray { privacy.windows.forEach { add(buildJsonObject { put("startMs", it.first); put("endMs", it.last) }) } })
    if (privacy.passwordSensitive) put("statement", RECORDING_PRIVACY_STATEMENT)
}

private fun videoJson(video: StoredVideoInfo): JsonObject = buildJsonObject {
    put("stored", video.stored)
    video.reason?.let { put("reason", it) }
    if (video.stored) {
        put("bytes", video.bytes)
        put("sessionStartedAtMs", video.sessionStartedAtMs)
        put("stoppedDurationMs", video.stoppedDurationMs)
        put("stoppedWallClockMs", video.stoppedWallClockMs)
        put("gapCount", video.gapCount)
        put("capReached", video.capReached)
        video.warning?.let { put("warning", it) }
        put("packetCount", video.packetCount)
    }
}

private fun JsonObjectBuilder.putIfNotNull(key: String, value: Long?) {
    if (value != null) put(key, value)
}

private fun evidenceJson(evidence: RecordingScreenshotEvidence, images: RecordingImageStore): JsonObject = buildJsonObject {
    put("image", images.put(evidence.jpeg))
    put("source", evidence.source.name)
    putIfNotNull("acquiredAtMs", evidence.acquiredAtMs)
    putIfNotNull("acquisitionFinishedAtMs", evidence.acquisitionFinishedAtMs)
    put("timingUncertain", evidence.timingUncertain)
}

private fun nodeJson(node: UiNode): JsonObject = buildJsonObject {
    put("bounds", buildJsonArray { listOf(node.left, node.top, node.right, node.bottom).forEach { add(JsonPrimitive(it)) } })
    // The parser already leaves a password field's text blank; this keeps it so whatever a caller hands in.
    put("text", if (node.password) "" else node.text)
    put("contentDesc", node.contentDesc)
    put("resourceId", node.resourceId)
    put("className", node.className)
    put("packageName", node.packageName)
    put("clickable", node.clickable)
    put("enabled", node.enabled)
    put("scrollable", node.scrollable)
    put("password", node.password)
    put("focused", node.focused)
}

private fun stateJson(state: RecordingScreenState, images: RecordingImageStore): JsonObject = buildJsonObject {
    put("seq", state.seq)
    put("startedAt", state.startedAt)
    put("finishedAt", state.finishedAt)
    state.packageName?.let { put("packageName", it) }
    state.activity?.let { put("activity", it) }
    put("screenWidth", state.screenWidth)
    put("screenHeight", state.screenHeight)
    state.rotation?.let { put("rotation", it) }
    state.screenshotJpeg?.let { put("image", images.put(it)) }
    putIfNotNull("screenshotStartedAt", state.screenshotStartedAt)
    putIfNotNull("screenshotFinishedAt", state.screenshotFinishedAt)
    put("nodes", buildJsonArray { state.nodes.forEach { add(nodeJson(it)) } })
}

private fun pointJson(point: RecordedPoint): JsonObject = buildJsonObject {
    put("x", point.x)
    put("y", point.y)
    put("screenWidth", point.screenWidth)
    put("screenHeight", point.screenHeight)
}

@Suppress("CyclomaticComplexMethod") // One optional key per field of a row.
private fun rowJson(row: RecordedTestStep, images: RecordingImageStore): JsonObject = buildJsonObject {
    put("id", row.id)
    put("action", row.action)
    put("expected", row.expected)
    put("optional", row.optional)
    row.condition?.let { put("condition", it) }
    row.screenContext?.let { put("screenContext", it) }
    row.screenshotJpeg?.let { put("image", images.put(it)) }
    put("useScreenshotAsExpected", row.useScreenshotAsExpected)
    put("screenshotFromAdb", row.screenshotFromAdb)
    putIfNotNull("durationMs", row.durationMs)
    put("gesturePath", buildJsonArray { row.gesturePath.forEach { add(pointJson(it)) } })
    row.before?.let { put("before", screenRefJson(it)) }
    row.after?.let { put("after", screenRefJson(it)) }
    row.tappedElement?.let { element ->
        put(
            "tappedElement",
            buildJsonObject {
                put("text", element.text)
                put("contentDesc", element.contentDesc)
                put("resourceId", element.resourceId)
                put("className", element.className)
            },
        )
    }
    row.kind?.let { put("kind", it.name) }
    put("inputAtMs", row.inputAtMs)
    put("inputStartMs", row.inputStartMs)
    if (row.sourceInputIds.isNotEmpty()) put("sourceInputIds", buildJsonArray { row.sourceInputIds.forEach { add(JsonPrimitive(it)) } })
    row.sourceHint?.let { put("sourceHint", it) }
    if (row.checks.isNotEmpty()) put("checks", buildJsonArray { row.checks.forEach { add(checkToJson(it)) } })
    row.reviewReason?.let { put("reviewReason", it) }
    row.beforeScreenshot?.let { put("beforeScreenshot", evidenceJson(it, images)) }
    row.afterScreenshot?.let { put("afterScreenshot", evidenceJson(it, images)) }
    row.screenshotSource?.let { put("screenshotSource", it.name) }
    putIfNotNull("screenshotAcquiredAtMs", row.screenshotAcquiredAtMs)
    putIfNotNull("screenshotAcquisitionFinishedAtMs", row.screenshotAcquisitionFinishedAtMs)
    put("screenshotTimingUncertain", row.screenshotTimingUncertain)
    row.screenshotVerifiedMoment?.let { put("screenshotVerifiedMoment", it) }
    put("authorAdded", row.authorAdded)
    put("stepKind", row.stepKind.name)
    row.script?.let { put("script", scriptCallToJson(it)) }
    put("manualCompletion", row.manualCompletion.name)
    if (row.stateChanges.isNotEmpty()) put("stateChanges", buildJsonArray { row.stateChanges.forEach { add(eventJson(it)) } })
}

private fun screenRefJson(ref: RecordingScreenRef): JsonObject = buildJsonObject {
    put("seq", ref.seq)
    ref.packageName?.let { put("packageName", it) }
    ref.activity?.let { put("activity", it) }
}

private fun eventJson(event: EnvironmentEvent): JsonObject = buildJsonObject {
    put("key", event.key)
    put("from", event.from)
    put("to", event.to)
    put("atMs", event.atMs)
    put("sinceMs", event.sinceMs)
}

// ── Reader ───────────────────────────────────────────────────────────

/** Never throws; the failure message is safe to show. A file of a newer version is refused, not half read. */
internal fun decodeStoredRecording(text: String, images: RecordingImageStore): Result<StoredRecording> = runCatching {
    val root = runCatching { Json.parseToJsonElement(text).jsonObject }.getOrElse { error("This file is not a stored recording (it is not valid JSON).") }
    if (root.str("format") != RECORDING_FILE_FORMAT) error("This file is not an Indagium test recording.")
    val version = (root["version"] as? JsonPrimitive)?.intOrNull ?: 1
    if (version > RECORDING_FILE_VERSION) error("This recording was saved by a newer version of Indagium. Update Indagium to reopen it.")
    val recordingId = root.str("recordingId").takeIf { it.isNotBlank() } ?: error("This recording has no id.")
    val context = DecodeContext(images)
    val rewrite = root["rewrite"] as? JsonObject
    StoredRecording(
        recordingId = recordingId,
        caseId = root.str("caseId"),
        deviceSerial = root.str("deviceSerial"),
        createdAtMs = root.long("createdAt", 0L),
        updatedAtMs = root.long("updatedAt", 0L),
        stepIds = root.strings("stepIds").take(MAX_STEP_IDS),
        rows = root.objects("rows").take(MAX_ROWS).map { decodeRow(it, context) },
        rawRows = if (root.containsKey("rawRows")) root.objects("rawRows").take(MAX_ROWS).map { decodeRow(it, context) } else null,
        rewriteApplied = rewrite?.bool("applied", false) ?: false,
        rewriteNotes = rewrite?.str("notes").orEmpty().take(MAX_TEXT_CHARS),
        excludedSourceInputIds = rewrite?.strings("excludedSourceInputIds").orEmpty().toSet(),
        screenStates = root.objects("screenStates").take(MAX_SCREEN_STATES).map { decodeState(it, context.images) },
        probeScreenshots = root.objects("probeScreenshots").take(MAX_PROBE_SCREENSHOTS).mapNotNull { context.evidence(it) },
        warnings = root.strings("warnings").take(MAX_WARNINGS),
        privacy = decodePrivacy(root["privacy"] as? JsonObject),
        video = decodeVideo(root["video"] as? JsonObject),
        notices = root.strings("notices").take(MAX_WARNINGS),
    )
}

private fun decodePrivacy(o: JsonObject?): StoredPrivacy {
    if (o == null) return StoredPrivacy()
    return StoredPrivacy(
        passwordSensitive = o.bool("passwordSensitive", false),
        windows = o.objects("windows").map { it.long("startMs", 0L)..it.long("endMs", 0L) },
        droppedScreenStates = o.int("droppedScreenStates", 0),
        droppedScreenshots = o.int("droppedScreenshots", 0),
    )
}

private fun decodeVideo(o: JsonObject?): StoredVideoInfo {
    if (o == null) return StoredVideoInfo(stored = false)
    return StoredVideoInfo(
        stored = o.bool("stored", false),
        reason = o.optStr("reason"),
        bytes = o.long("bytes", 0L),
        sessionStartedAtMs = o.long("sessionStartedAtMs", 0L),
        stoppedDurationMs = o.long("stoppedDurationMs", 0L),
        stoppedWallClockMs = o.long("stoppedWallClockMs", -1L),
        gapCount = o.int("gapCount", 0),
        capReached = o.bool("capReached", false),
        warning = o.optStr("warning"),
        packetCount = o.long("packetCount", 0L),
    )
}

private fun optLong(o: JsonObject, key: String): Long? = if (o.containsKey(key)) o.long(key, 0L) else null

private fun imageBytes(o: JsonObject, images: RecordingImageStore): ByteArray? =
    o.optStr("image")?.takeIf { IMAGE_REF.matches(it) }?.let(images::get)

/**
 * Reads images once and hands the same [RecordingScreenshotEvidence] to every place that stored it. The rewrite tells one picture shown as
 * an input's "after" and the next input's "before" apart by identity (RewriteEvidenceIds), so a reopened recording must share them again.
 */
private class DecodeContext(val images: RecordingImageStore, val ids: IdAllocator = IdAllocator()) {
    private val evidence = HashMap<List<Any?>, RecordingScreenshotEvidence?>()

    fun evidence(o: JsonObject): RecordingScreenshotEvidence? {
        val key = listOf(o.optStr("image"), o.optStr("source"), optLong(o, "acquiredAtMs"), optLong(o, "acquisitionFinishedAtMs"), o["timingUncertain"])
        return evidence.getOrPut(key) {
            val bytes = imageBytes(o, images) ?: return@getOrPut null
            RecordingScreenshotEvidence(
                jpeg = bytes,
                source = o.enumOr("source", RecordingScreenshotSource.SCREEN_PROBE),
                acquiredAtMs = optLong(o, "acquiredAtMs"),
                acquisitionFinishedAtMs = optLong(o, "acquisitionFinishedAtMs"),
                timingUncertain = o.bool("timingUncertain", true),
            )
        }
    }
}

private fun decodeNode(o: JsonObject): UiNode {
    val bounds = (o["bounds"] as? JsonArray)?.map { (it as? JsonPrimitive)?.intOrNull ?: 0 }.orEmpty()
    val password = o.bool("password", false)
    return UiNode(
        left = bounds.getOrElse(0) { 0 },
        top = bounds.getOrElse(1) { 0 },
        right = bounds.getOrElse(2) { 0 },
        bottom = bounds.getOrElse(3) { 0 },
        text = if (password) "" else o.str("text"),
        contentDesc = o.str("contentDesc"),
        resourceId = o.str("resourceId"),
        className = o.str("className"),
        clickable = o.bool("clickable", false),
        enabled = o.bool("enabled", true),
        scrollable = o.bool("scrollable", false),
        packageName = o.str("packageName"),
        password = password,
        focused = o.bool("focused", false),
    )
}

private fun decodeState(o: JsonObject, images: RecordingImageStore) = RecordingScreenState(
    seq = o.int("seq", 0),
    startedAt = o.long("startedAt", 0L),
    finishedAt = o.long("finishedAt", 0L),
    packageName = o.optStr("packageName"),
    activity = o.optStr("activity"),
    nodes = o.objects("nodes").take(MAX_UI_TREE_NODES).map(::decodeNode),
    screenWidth = o.int("screenWidth", 0),
    screenHeight = o.int("screenHeight", 0),
    screenshotJpeg = imageBytes(o, images),
    screenshotStartedAt = optLong(o, "screenshotStartedAt"),
    screenshotFinishedAt = optLong(o, "screenshotFinishedAt"),
    rotation = if (o.containsKey("rotation")) o.int("rotation", 0) else null,
)

private fun decodeScreenRef(o: JsonObject?): RecordingScreenRef? =
    o?.let { RecordingScreenRef(it.int("seq", 0), it.optStr("packageName"), it.optStr("activity")) }

private fun decodePoint(o: JsonObject) = RecordedPoint(o.int("x", 0), o.int("y", 0), o.int("screenWidth", 0), o.int("screenHeight", 0))

private fun decodeEvent(o: JsonObject) = EnvironmentEvent(o.str("key"), o.str("from"), o.str("to"), o.long("atMs", 0L), o.long("sinceMs", o.long("atMs", 0L)))

private fun decodeRow(o: JsonObject, context: DecodeContext): RecordedTestStep {
    val images = context.images
    val kind = o.optStr("kind")?.let { name -> RecordedInputKind.entries.firstOrNull { it.name == name } }
    val stepKind = o.enumOr("stepKind", StepKind.AGENT)
    return RecordedTestStep(
        action = o.str("action"),
        expected = o.str("expected"),
        optional = o.bool("optional", false),
        condition = o.optStr("condition"),
        screenContext = o.optStr("screenContext"),
        screenshotJpeg = imageBytes(o, images),
        useScreenshotAsExpected = o.bool("useScreenshotAsExpected", false),
        id = o.str("id").takeIf { it.isNotBlank() } ?: java.util.UUID.randomUUID().toString(),
        screenshotFromAdb = o.bool("screenshotFromAdb", false),
        durationMs = optLong(o, "durationMs"),
        gesturePath = o.objects("gesturePath").take(MAX_GESTURE_POINTS).map(::decodePoint),
        before = decodeScreenRef(o["before"] as? JsonObject),
        after = decodeScreenRef(o["after"] as? JsonObject),
        tappedElement = (o["tappedElement"] as? JsonObject)?.let {
            TappedElement(it.str("text"), it.str("contentDesc"), it.str("resourceId"), it.str("className"))
        },
        kind = kind,
        inputAtMs = o.long("inputAtMs", 0L),
        inputStartMs = o.long("inputStartMs", 0L),
        sourceInputIds = o.strings("sourceInputIds"),
        sourceHint = o.optStr("sourceHint"),
        checks = o.objects("checks").mapNotNull { decodeCheck(it, context.ids) },
        reviewReason = o.optStr("reviewReason"),
        beforeScreenshot = (o["beforeScreenshot"] as? JsonObject)?.let(context::evidence),
        afterScreenshot = (o["afterScreenshot"] as? JsonObject)?.let(context::evidence),
        screenshotSource = o.optStr("screenshotSource")?.let { name -> RecordingScreenshotSource.entries.firstOrNull { it.name == name } },
        screenshotAcquiredAtMs = optLong(o, "screenshotAcquiredAtMs"),
        screenshotAcquisitionFinishedAtMs = optLong(o, "screenshotAcquisitionFinishedAtMs"),
        screenshotTimingUncertain = o.bool("screenshotTimingUncertain", true),
        screenshotVerifiedMoment = o.optStr("screenshotVerifiedMoment"),
        authorAdded = o.bool("authorAdded", false),
        stepKind = stepKind,
        script = (o["script"] as? JsonObject)?.let { decodeScriptCall(it) },
        manualCompletion = o.enumOr("manualCompletion", ManualCompletion.TESTER_OR_CHECKS),
        stateChanges = o.objects("stateChanges").take(MAX_STATE_CHANGES).map(::decodeEvent),
    )
}
