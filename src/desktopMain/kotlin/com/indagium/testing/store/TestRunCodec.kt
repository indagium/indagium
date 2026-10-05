package com.indagium.testing.store

import com.indagium.testing.model.CaseResult
import com.indagium.testing.model.CaseStatus
import com.indagium.testing.model.CheckResult
import com.indagium.testing.model.CheckStatus
import com.indagium.testing.model.DEFAULT_CASE_TOOL_CALL_LIMIT
import com.indagium.testing.model.DEFAULT_CONFIRMATION_TIMEOUT_MS
import com.indagium.testing.model.EvidenceFlags
import com.indagium.testing.model.LaneConfig
import com.indagium.testing.model.LaneKind
import com.indagium.testing.model.LaneResult
import com.indagium.testing.model.RunConfig
import com.indagium.testing.model.RunStatus
import com.indagium.testing.model.StepResult
import com.indagium.testing.model.StepStatus
import com.indagium.testing.model.TestRun
import com.indagium.testing.model.isSafeId
import com.indagium.testing.model.newLaneId
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonObjectBuilder
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put

// JSON codec of `<run folder>/run.json`:
//   {"format":"indagium-test-run","version":1,"run":{...}}
// The run embeds the frozen suite, scripts and shared steps in the same shape the library files use (TestLibraryCodec).
// Reads are tolerant: unknown keys are ignored, a missing or mistyped field takes its default, an unknown enum value
// falls back to a neutral one. Only the envelope is strict, and a file written by a NEWER version still decodes (the
// report can show what it understands); the run folder is never rewritten by a finished run, so nothing is lost.

const val TEST_RUN_FILE_FORMAT = "indagium-test-run"
const val TEST_RUN_FILE_VERSION = 1

private val prettyJson = Json { prettyPrint = true }

// ── Writers ──────────────────────────────────────────────────────────

private fun JsonObjectBuilder.putIfNotNull(key: String, value: String?) {
    if (value != null) put(key, value)
}

private fun JsonObjectBuilder.putIfNotNull(key: String, value: Long?) {
    if (value != null) put(key, value)
}

private fun laneConfigToJson(lane: LaneConfig): JsonObject = buildJsonObject {
    put("id", lane.id)
    put("kind", lane.kind.name)
    putIfNotNull("profileId", lane.profileId)
    put("deviceSerial", lane.deviceSerial)
}

private fun configToJson(config: RunConfig): JsonObject = buildJsonObject {
    put("suiteId", config.suiteId)
    config.caseIds?.let { ids -> put("caseIds", buildJsonArray { ids.forEach { add(JsonPrimitive(it)) } }) }
    put("lanes", buildJsonArray { config.lanes.forEach { add(laneConfigToJson(it)) } })
    put("repeat", config.repeat)
    put("caseToolCallLimit", config.caseToolCallLimit)
    put(
        "evidence",
        buildJsonObject {
            put("video", config.evidence.video)
            put("screenshots", config.evidence.screenshots)
            put("logcat", config.evidence.logcat)
            put("transcript", config.evidence.transcript)
        },
    )
    put("confirmationTimeoutMs", config.confirmationTimeoutMs)
    putIfNotNull("judgeProfileId", config.judgeProfileId)
    put("judgeMode", config.judgeMode)
}

private fun checkResultToJson(check: CheckResult): JsonObject = buildJsonObject {
    put("checkId", check.checkId)
    put("kind", check.kind)
    put("status", check.status.name)
    put("detail", check.detail)
    put("durationMs", check.durationMs)
}

private fun stepResultToJson(step: StepResult): JsonObject = buildJsonObject {
    put("stepId", step.stepId)
    put("stepNumber", step.stepNumber)
    put("action", step.action)
    put("expected", step.expected)
    put("setup", step.setup)
    put("status", step.status.name)
    put("attempts", step.attempts)
    putIfNotNull("agentClaim", step.agentClaim)
    put("observation", step.observation)
    put("checks", buildJsonArray { step.checks.forEach { add(checkResultToJson(it)) } })
    putIfNotNull("screenshotPath", step.screenshotPath)
    putIfNotNull("logStartOffset", step.logStartOffset)
    putIfNotNull("logEndOffset", step.logEndOffset)
    putIfNotNull("transcriptStartOffset", step.transcriptStartOffset)
    putIfNotNull("transcriptEndOffset", step.transcriptEndOffset)
    put("startedAt", step.startedAt)
    put("durationMs", step.durationMs)
    put("issueRequested", step.issueRequested)
    putIfNotNull("note", step.note)
}

private fun caseResultToJson(case: CaseResult): JsonObject = buildJsonObject {
    put("caseId", case.caseId)
    put("caseName", case.caseName)
    put("iteration", case.iteration)
    putIfNotNull("status", case.status?.name)
    put("steps", buildJsonArray { case.steps.forEach { add(stepResultToJson(it)) } })
    put("startedAt", case.startedAt)
    putIfNotNull("finishedAt", case.finishedAt)
    putIfNotNull("note", case.note)
}

private fun laneResultToJson(lane: LaneResult): JsonObject = buildJsonObject {
    put("laneId", lane.laneId)
    put("config", laneConfigToJson(lane.config))
    put("status", lane.status.name)
    put("cases", buildJsonArray { lane.cases.forEach { add(caseResultToJson(it)) } })
    putIfNotNull("startedAt", lane.startedAt)
    putIfNotNull("finishedAt", lane.finishedAt)
    putIfNotNull("error", lane.error)
    putIfNotNull("currentCase", lane.currentCase)
    lane.currentStepNumber?.let { put("currentStepNumber", it) }
    putIfNotNull("currentStepAction", lane.currentStepAction)
    putIfNotNull("logPath", lane.logPath)
    putIfNotNull("transcriptPath", lane.transcriptPath)
}

internal fun runToJson(run: TestRun): JsonObject = buildJsonObject {
    put("id", run.id)
    put("status", run.status.name)
    put("createdAt", run.createdAt)
    putIfNotNull("startedAt", run.startedAt)
    putIfNotNull("finishedAt", run.finishedAt)
    putIfNotNull("error", run.error)
    put("warnings", buildJsonArray { run.warnings.forEach { add(JsonPrimitive(it)) } })
    put("config", configToJson(run.config))
    put("suite", suiteToJson(run.suite))
    put("scripts", buildJsonArray { run.scripts.forEach { add(scriptToJson(it)) } })
    put("sharedSteps", buildJsonArray { run.sharedSteps.forEach { add(sharedStepToJson(it)) } })
    put("lanes", buildJsonArray { run.lanes.forEach { add(laneResultToJson(it)) } })
}

fun encodeRunFile(run: TestRun): String = prettyJson.encodeToString(
    JsonElement.serializer(),
    buildJsonObject {
        put("format", TEST_RUN_FILE_FORMAT)
        put("version", TEST_RUN_FILE_VERSION)
        put("run", runToJson(run))
    },
)

// ── Readers ──────────────────────────────────────────────────────────

private fun JsonObject.optLong(key: String): Long? = (this[key] as? JsonPrimitive)?.longOrNull

private inline fun <reified E : Enum<E>> JsonObject.optEnum(key: String): E? =
    optStr(key)?.let { name -> enumValues<E>().firstOrNull { it.name == name } }

private fun decodeLaneConfig(o: JsonObject): LaneConfig? {
    val serial = o.str("deviceSerial").takeIf { it.isNotBlank() } ?: return null
    return LaneConfig(
        id = o.str("id").takeIf { isSafeId(it) } ?: newLaneId(),
        kind = o.enumOr("kind", LaneKind.AGENT_PROFILE),
        profileId = o.optStr("profileId"),
        deviceSerial = serial,
    )
}

private fun decodeConfig(o: JsonObject): RunConfig {
    val evidence = o["evidence"] as? JsonObject
    return RunConfig(
        suiteId = o.str("suiteId"),
        caseIds = (o["caseIds"] as? JsonArray)?.let { o.strings("caseIds") },
        lanes = o.objects("lanes").mapNotNull { decodeLaneConfig(it) },
        repeat = o.int("repeat", 1),
        caseToolCallLimit = o.int("caseToolCallLimit", DEFAULT_CASE_TOOL_CALL_LIMIT),
        evidence = EvidenceFlags(
            video = evidence?.bool("video", false) ?: false,
            screenshots = evidence?.bool("screenshots", true) ?: true,
            logcat = evidence?.bool("logcat", true) ?: true,
            transcript = evidence?.bool("transcript", true) ?: true,
        ),
        confirmationTimeoutMs = o.long("confirmationTimeoutMs", DEFAULT_CONFIRMATION_TIMEOUT_MS),
        judgeProfileId = o.optStr("judgeProfileId"),
        judgeMode = o.str("judgeMode", "off"),
    )
}

private fun decodeCheckResult(o: JsonObject): CheckResult = CheckResult(
    checkId = o.str("checkId"),
    kind = o.str("kind"),
    status = o.enumOr("status", CheckStatus.ERROR),
    detail = o.str("detail"),
    durationMs = o.long("durationMs", 0L),
)

private fun decodeStepResult(o: JsonObject): StepResult = StepResult(
    stepId = o.str("stepId"),
    stepNumber = o.int("stepNumber", 0),
    action = o.str("action"),
    expected = o.str("expected"),
    setup = o.bool("setup", false),
    status = o.enumOr("status", StepStatus.ERROR),
    attempts = o.int("attempts", 1),
    agentClaim = o.optStr("agentClaim"),
    observation = o.str("observation"),
    checks = o.objects("checks").map { decodeCheckResult(it) },
    screenshotPath = o.optStr("screenshotPath"),
    logStartOffset = o.optLong("logStartOffset"),
    logEndOffset = o.optLong("logEndOffset"),
    transcriptStartOffset = o.optLong("transcriptStartOffset"),
    transcriptEndOffset = o.optLong("transcriptEndOffset"),
    startedAt = o.long("startedAt", 0L),
    durationMs = o.long("durationMs", 0L),
    issueRequested = o.bool("issueRequested", false),
    note = o.optStr("note"),
)

private fun decodeCaseResult(o: JsonObject): CaseResult = CaseResult(
    caseId = o.str("caseId"),
    caseName = o.str("caseName"),
    iteration = o.int("iteration", 1),
    status = o.optEnum<CaseStatus>("status"),
    steps = o.objects("steps").map { decodeStepResult(it) },
    startedAt = o.long("startedAt", 0L),
    finishedAt = o.optLong("finishedAt"),
    note = o.optStr("note"),
)

private fun decodeLaneResult(o: JsonObject): LaneResult? {
    val config = (o["config"] as? JsonObject)?.let { decodeLaneConfig(it) } ?: return null
    return LaneResult(
        laneId = config.id,
        config = config,
        status = o.enumOr("status", RunStatus.ERROR),
        cases = o.objects("cases").map { decodeCaseResult(it) },
        startedAt = o.optLong("startedAt"),
        finishedAt = o.optLong("finishedAt"),
        error = o.optStr("error"),
        currentCase = o.optStr("currentCase"),
        currentStepNumber = (o["currentStepNumber"] as? JsonPrimitive)?.intOrNull,
        currentStepAction = o.optStr("currentStepAction"),
        logPath = o.optStr("logPath"),
        transcriptPath = o.optStr("transcriptPath"),
    )
}

private fun decodeRun(o: JsonObject): TestRun? {
    val id = o.str("id").takeIf { isSafeId(it) } ?: return null
    val suite = (o["suite"] as? JsonObject)?.let { decodeSuiteObject(it) } ?: return null
    return TestRun(
        id = id,
        suite = suite,
        scripts = decodeScriptObjects(o.objects("scripts")),
        sharedSteps = decodeSharedStepObjects(o.objects("sharedSteps")),
        config = decodeConfig(o["config"] as? JsonObject ?: JsonObject(emptyMap())),
        lanes = o.objects("lanes").mapNotNull { decodeLaneResult(it) },
        status = o.enumOr("status", RunStatus.ERROR),
        createdAt = o.long("createdAt", 0L),
        startedAt = o.optLong("startedAt"),
        finishedAt = o.optLong("finishedAt"),
        warnings = o.strings("warnings"),
        error = o.optStr("error"),
    )
}

/** Never throws; the failure message is safe to show as-is. A run that was cut off mid-write by a crash has no usable file. */
fun decodeRunFile(text: String): Result<TestRun> = runCatching {
    val root = runCatching { Json.parseToJsonElement(text).jsonObject }.getOrElse { error("This file is not a test run (it is not valid JSON).") }
    if (root.str("format") != TEST_RUN_FILE_FORMAT) error("This file is not an Indagium test run.")
    val runObject = root["run"] as? JsonObject ?: error("This test run file has no run in it.")
    decodeRun(runObject) ?: error("This test run file has no usable run.")
}
