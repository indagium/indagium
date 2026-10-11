package com.indagium.testing.store

import com.indagium.capture.captureSettingsFromJson
import com.indagium.capture.captureSettingsToJson
import com.indagium.testing.model.CaseAttemptRecord
import com.indagium.testing.model.CaseResult
import com.indagium.testing.model.CaseStatus
import com.indagium.testing.model.CheckResult
import com.indagium.testing.model.CheckStatus
import com.indagium.testing.model.DEFAULT_CASE_TOOL_CALL_LIMIT
import com.indagium.testing.model.DEFAULT_CONFIRMATION_TIMEOUT_MS
import com.indagium.testing.model.EvidenceFlags
import com.indagium.testing.model.JudgeClassification
import com.indagium.testing.model.JudgeInconclusivePolicy
import com.indagium.testing.model.JudgeMode
import com.indagium.testing.model.JudgeTiming
import com.indagium.testing.model.LaneConfig
import com.indagium.testing.model.LaneKind
import com.indagium.testing.model.LaneResult
import com.indagium.testing.model.LaneToolCall
import com.indagium.testing.model.LaneToolCallStatus
import com.indagium.testing.model.ManualCompletion
import com.indagium.testing.model.ManualStepsOption
import com.indagium.testing.model.RunConfig
import com.indagium.testing.model.RunStatus
import com.indagium.testing.model.StepAttemptRecord
import com.indagium.testing.model.StepCompletion
import com.indagium.testing.model.StepKind
import com.indagium.testing.model.StepResult
import com.indagium.testing.model.StepStatus
import com.indagium.testing.model.TestRun
import com.indagium.testing.model.VerificationState
import com.indagium.testing.model.aiUsage
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
private const val MAX_PERSISTED_TOOL_CALLS = 300
private const val MAX_PERSISTED_TOOL_PREVIEW_CHARS = 2_000
private const val MAX_PERSISTED_STEP_ATTEMPT = 11
private const val MAX_PERSISTED_CASE_ATTEMPTS = 3
private const val MAX_PERSISTED_STEP_OBSERVATION_CHARS = 20_000
private const val MAX_PERSISTED_CHECKS_PER_STEP = 100
private const val MAX_PERSISTED_SAMPLE_SCREENSHOTS = 20
private const val MAX_PERSISTED_NOTE_CHARS = 2_000
private const val MAX_PERSISTED_STEPS_PER_CASE_ATTEMPT = 500

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
    putIfNotNull("model", lane.model)
    putIfNotNull("reasoningEffort", lane.reasoningEffort)
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
    putIfNotNull("stopAfterStepId", config.stopAfterStepId)
    putIfNotNull("rerunOf", config.rerunOf)
    putIfNotNull("judgeModel", config.judgeModel)
    putIfNotNull("judgeReasoningEffort", config.judgeReasoningEffort)
    config.capture?.let { put("capture", Json.parseToJsonElement(captureSettingsToJson(it))) }
    put("openLaneTabs", config.openLaneTabs)
    put("manualSteps", config.manualSteps.name)
    put("judgeInconclusivePolicy", config.judgeInconclusivePolicy.wire)
    put("judgeTiming", config.judgeTiming.wire)
    put("caseRetries", config.caseRetries)
    config.stepRetriesOverride?.let { put("stepRetriesOverride", it) }
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
    putIfNotNull("startScreenshotPath", step.startScreenshotPath)
    if (step.sampleScreenshotPaths.isNotEmpty()) put("sampleScreenshotPaths", buildJsonArray { step.sampleScreenshotPaths.forEach { add(JsonPrimitive(it)) } })
    if (step.attemptHistory.isNotEmpty()) put("attemptHistory", buildJsonArray { step.attemptHistory.forEach { add(stepAttemptToJson(it)) } })
    put("flaky", step.flaky)
    put("userRejected", step.userRejected)
    step.executionStatus?.let { put("executionStatus", it.name) }
    put("verificationState", step.verificationState.name)
    if (step.caseId.isNotBlank()) put("caseId", step.caseId)
    put("iteration", step.iteration)
    put("caseAttempt", step.caseAttempt)
    put("automaticRetryExcluded", step.automaticRetryExcluded)
    putIfNotNull("judgeQueueWaitMs", step.judgeQueueWaitMs)
    putIfNotNull("judgmentDurationMs", step.judgmentDurationMs)
    putIfNotNull("logStartOffset", step.logStartOffset)
    putIfNotNull("logEndOffset", step.logEndOffset)
    putIfNotNull("transcriptStartOffset", step.transcriptStartOffset)
    putIfNotNull("transcriptEndOffset", step.transcriptEndOffset)
    put("startedAt", step.startedAt)
    put("durationMs", step.durationMs)
    put("issueRequested", step.issueRequested)
    putIfNotNull("note", step.note)
    step.judge?.let { put("judge", judgementToJson(it)) }
    put("judgeInconclusive", step.judgeInconclusive)
    putIfNotNull("agentError", step.agentError)
    putIfNotNull("issueId", step.issueId)
    if (step.kind != StepKind.AGENT) put("kind", step.kind.name)
    putIfNotNull("completedBy", step.completedBy?.name)
    putIfNotNull("manualCompletion", step.manualCompletion?.name)
    putIfNotNull("classification", step.classification?.name)
    // Derived from judge.timing for the report (get_test_run_report reads the step as written); the decoder ignores them.
    step.judge?.timing?.let {
        put("judgeMs", it.totalMs)
        put("judgeModelTurns", it.modelTurns)
    }
}

private fun stepAttemptToJson(attempt: StepAttemptRecord): JsonObject = buildJsonObject {
    put("caseId", attempt.caseId)
    put("iteration", attempt.iteration)
    put("caseAttempt", attempt.caseAttempt)
    put("stepId", attempt.stepId)
    put("attempt", attempt.attempt)
    put("retryReason", attempt.retryReason)
    put("status", attempt.status.name)
    putIfNotNull("agentClaim", attempt.agentClaim)
    put("observation", attempt.observation)
    put("checks", buildJsonArray { attempt.checks.forEach { add(checkResultToJson(it)) } })
    attempt.judge?.let { put("judge", judgementToJson(it)) }
    put("startedAt", attempt.startedAt)
    put("durationMs", attempt.durationMs)
    putIfNotNull("screenshotPath", attempt.screenshotPath)
    putIfNotNull("startScreenshotPath", attempt.startScreenshotPath)
    if (attempt.sampleScreenshotPaths.isNotEmpty()) {
        put("sampleScreenshotPaths", buildJsonArray { attempt.sampleScreenshotPaths.forEach { add(JsonPrimitive(it)) } })
    }
    putIfNotNull("logStartOffset", attempt.logStartOffset)
    putIfNotNull("logEndOffset", attempt.logEndOffset)
    putIfNotNull("transcriptStartOffset", attempt.transcriptStartOffset)
    putIfNotNull("transcriptEndOffset", attempt.transcriptEndOffset)
    putIfNotNull("note", attempt.note)
    attempt.agentUsage?.let { put("agentUsage", aiUsageToJson(it)) }
    attempt.judgeUsage?.let { put("judgeUsage", aiUsageToJson(it)) }
    put("userRejected", attempt.userRejected)
    attempt.executionStatus?.let { put("executionStatus", it.name) }
    put("verificationState", attempt.verificationState.name)
    put("automaticRetryExcluded", attempt.automaticRetryExcluded)
    putIfNotNull("judgeQueueWaitMs", attempt.judgeQueueWaitMs)
    putIfNotNull("judgmentDurationMs", attempt.judgmentDurationMs)
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
    case.agentUsage?.let { put("agentUsage", aiUsageToJson(it)) }
    case.judgeUsage?.let { put("judgeUsage", aiUsageToJson(it)) }
    case.externalToolCalls?.let { put("externalToolCalls", it) }
    put("caseAttempt", case.caseAttempt)
    if (case.priorAttempts.isNotEmpty()) put("priorAttempts", buildJsonArray { case.priorAttempts.forEach { add(caseAttemptToJson(it)) } })
    put("flaky", case.flaky)
}

private fun caseAttemptToJson(attempt: CaseAttemptRecord): JsonObject = buildJsonObject {
    put("caseId", attempt.caseId)
    put("iteration", attempt.iteration)
    put("attempt", attempt.attempt)
    put("status", attempt.status.name)
    put("steps", buildJsonArray { attempt.steps.forEach { add(stepResultToJson(it)) } })
    put("startedAt", attempt.startedAt)
    putIfNotNull("finishedAt", attempt.finishedAt)
    putIfNotNull("note", attempt.note)
    attempt.agentUsage?.let { put("agentUsage", aiUsageToJson(it)) }
    attempt.judgeUsage?.let { put("judgeUsage", aiUsageToJson(it)) }
    attempt.externalToolCalls?.let { put("externalToolCalls", it) }
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
    putIfNotNull("toolActivityPath", lane.toolActivityPath)
    put("toolCalls", buildJsonArray { lane.toolCalls.forEach { call ->
        add(buildJsonObject {
            put("id", call.id)
            put("caseId", call.caseId)
            put("stepId", call.stepId)
            put("iteration", call.iteration)
            put("attempt", call.attempt)
            put("toolName", call.toolName)
            put("argumentsPreview", call.argumentsPreview)
            put("resultPreview", call.resultPreview)
            put("status", call.status.name)
            put("startedAt", call.startedAt)
            call.durationMs?.let { put("durationMs", it) }
        })
    } })
    lane.aiUsage()?.let { put("usage", aiUsageToJson(it)) }
}

internal fun runToJson(run: TestRun): JsonObject = buildJsonObject {
    put("id", run.id)
    put("status", run.status.name)
    put("createdAt", run.createdAt)
    putIfNotNull("startedAt", run.startedAt)
    putIfNotNull("finishedAt", run.finishedAt)
    putIfNotNull("error", run.error)
    put("warnings", buildJsonArray { run.warnings.forEach { add(JsonPrimitive(it)) } })
    run.aiUsage()?.let { put("usage", aiUsageToJson(it)) }
    put("config", configToJson(run.config))
    put("suite", suiteToJson(run.suite))
    put("scripts", buildJsonArray { run.scripts.forEach { add(scriptToJson(it)) } })
    put("sharedSteps", buildJsonArray { run.sharedSteps.forEach { add(sharedStepToJson(it)) } })
    put("lanes", buildJsonArray { run.lanes.forEach { add(laneResultToJson(it)) } })
    put("comparisons", buildJsonArray { run.comparisons.forEach { add(comparisonToJson(it)) } })
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
        model = o.optStr("model"),
        reasoningEffort = o.optStr("reasoningEffort"),
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
        judgeMode = o.str("judgeMode", JudgeMode.OFF.wire),
        stopAfterStepId = o.optStr("stopAfterStepId"),
        rerunOf = o.optStr("rerunOf"),
        judgeModel = o.optStr("judgeModel"),
        judgeReasoningEffort = o.optStr("judgeReasoningEffort"),
        capture = (o["capture"] as? JsonObject)?.let { captureSettingsFromJson(it.toString()) },
        openLaneTabs = o.bool("openLaneTabs", true),
        manualSteps = o.enumOr("manualSteps", ManualStepsOption.USE_SCRIPT_THEN_WAIT),
        judgeInconclusivePolicy = o.optStr("judgeInconclusivePolicy")?.let(JudgeInconclusivePolicy::parse)
            ?: if (o["judgeInconclusivePolicy"] == null) JudgeInconclusivePolicy.KEEP_AGENT_PASS else JudgeInconclusivePolicy.BLOCK,
        judgeTiming = o.optStr("judgeTiming")?.let(JudgeTiming::parse) ?: JudgeTiming.INLINE,
        caseRetries = o.int("caseRetries", 0).coerceIn(0, 2),
        stepRetriesOverride = (o["stepRetriesOverride"] as? JsonPrimitive)?.intOrNull?.coerceIn(0, com.indagium.testing.model.MAX_STEP_RETRIES),
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
    startScreenshotPath = o.optStr("startScreenshotPath"),
    sampleScreenshotPaths = o.strings("sampleScreenshotPaths"),
    // Ten configured retries permit eleven attempts. Keep the newest bounded window so an extended
    // manual-retry history can never lose its actual final attempt while decoding.
    attemptHistory = o.objects("attemptHistory").takeLast(com.indagium.testing.model.MAX_STEP_RETRIES + 1).mapNotNull { decodeStepAttempt(it) },
    flaky = o.bool("flaky", false),
    userRejected = o.bool("userRejected", false),
    executionStatus = o.optEnum<StepStatus>("executionStatus"),
    verificationState = o.enumOr("verificationState", VerificationState.NOT_REQUESTED),
    caseId = o.str("caseId"),
    iteration = o.int("iteration", 1).coerceAtLeast(1),
    caseAttempt = o.int("caseAttempt", 1).coerceIn(1, 3),
    automaticRetryExcluded = o.bool("automaticRetryExcluded", false),
    judgeQueueWaitMs = o.optLong("judgeQueueWaitMs")?.coerceAtLeast(0L),
    judgmentDurationMs = o.optLong("judgmentDurationMs")?.coerceAtLeast(0L),
    logStartOffset = o.optLong("logStartOffset"),
    logEndOffset = o.optLong("logEndOffset"),
    transcriptStartOffset = o.optLong("transcriptStartOffset"),
    transcriptEndOffset = o.optLong("transcriptEndOffset"),
    startedAt = o.long("startedAt", 0L),
    durationMs = o.long("durationMs", 0L),
    issueRequested = o.bool("issueRequested", false),
    note = o.optStr("note"),
    judge = (o["judge"] as? JsonObject)?.let { decodeJudgement(it) },
    judgeInconclusive = o.bool("judgeInconclusive", false),
    agentError = o.optStr("agentError"),
    issueId = o.optStr("issueId")?.takeIf { isSafeId(it) },
    kind = o.enumOr("kind", StepKind.AGENT),
    completedBy = o.optEnum<StepCompletion>("completedBy"),
    manualCompletion = o.optEnum<ManualCompletion>("manualCompletion"),
    classification = o.optEnum<JudgeClassification>("classification"),
)

private fun decodeStepAttempt(o: JsonObject): StepAttemptRecord? {
    val attempt = o.int("attempt", 0)
    if (attempt !in 1..MAX_PERSISTED_STEP_ATTEMPT) return null
    return StepAttemptRecord(
        caseId = o.str("caseId"),
        iteration = o.int("iteration", 1).coerceAtLeast(1),
        caseAttempt = o.int("caseAttempt", 1).coerceIn(1, MAX_PERSISTED_CASE_ATTEMPTS),
        stepId = o.str("stepId"),
        attempt = attempt,
        retryReason = o.str("retryReason", "final").take(40),
        status = o.enumOr("status", StepStatus.ERROR),
        agentClaim = o.optStr("agentClaim"),
        observation = o.str("observation").take(MAX_PERSISTED_STEP_OBSERVATION_CHARS),
        checks = o.objects("checks").take(MAX_PERSISTED_CHECKS_PER_STEP).map { decodeCheckResult(it) },
        judge = (o["judge"] as? JsonObject)?.let { decodeJudgement(it) },
        startedAt = o.long("startedAt", 0L),
        durationMs = o.long("durationMs", 0L).coerceAtLeast(0),
        screenshotPath = o.optStr("screenshotPath"),
        startScreenshotPath = o.optStr("startScreenshotPath"),
        sampleScreenshotPaths = o.strings("sampleScreenshotPaths").take(MAX_PERSISTED_SAMPLE_SCREENSHOTS),
        logStartOffset = o.optLong("logStartOffset"),
        logEndOffset = o.optLong("logEndOffset"),
        transcriptStartOffset = o.optLong("transcriptStartOffset"),
        transcriptEndOffset = o.optLong("transcriptEndOffset"),
        note = o.optStr("note")?.take(MAX_PERSISTED_NOTE_CHARS),
        agentUsage = decodeAiUsage(o["agentUsage"]),
        judgeUsage = decodeAiUsage(o["judgeUsage"]),
        userRejected = o.bool("userRejected", false),
        executionStatus = o.optEnum<StepStatus>("executionStatus"),
        verificationState = o.enumOr("verificationState", VerificationState.NOT_REQUESTED),
        automaticRetryExcluded = o.bool("automaticRetryExcluded", false),
        judgeQueueWaitMs = o.optLong("judgeQueueWaitMs")?.coerceAtLeast(0L),
        judgmentDurationMs = o.optLong("judgmentDurationMs")?.coerceAtLeast(0L),
    )
}

private fun decodeCaseAttempt(o: JsonObject): CaseAttemptRecord? {
    val attempt = o.int("attempt", 0)
    val status = o.optEnum<CaseStatus>("status") ?: return null
    if (attempt !in 1..MAX_PERSISTED_CASE_ATTEMPTS) return null
    return CaseAttemptRecord(
        caseId = o.str("caseId"),
        iteration = o.int("iteration", 1).coerceAtLeast(1),
        attempt = attempt,
        status = status,
        steps = o.objects("steps").take(MAX_PERSISTED_STEPS_PER_CASE_ATTEMPT).map { decodeStepResult(it) },
        startedAt = o.long("startedAt", 0L),
        finishedAt = o.optLong("finishedAt"),
        note = o.optStr("note")?.take(MAX_PERSISTED_NOTE_CHARS),
        agentUsage = decodeAiUsage(o["agentUsage"]),
        judgeUsage = decodeAiUsage(o["judgeUsage"]),
        externalToolCalls = o.optLong("externalToolCalls")?.takeIf { it >= 0L },
    )
}

private fun decodeCaseResult(o: JsonObject): CaseResult = CaseResult(
    caseId = o.str("caseId"),
    caseName = o.str("caseName"),
    iteration = o.int("iteration", 1),
    status = o.optEnum<CaseStatus>("status"),
    steps = o.objects("steps").map { decodeStepResult(it) },
    startedAt = o.long("startedAt", 0L),
    finishedAt = o.optLong("finishedAt"),
    note = o.optStr("note"),
    agentUsage = decodeAiUsage(o["agentUsage"]),
    judgeUsage = decodeAiUsage(o["judgeUsage"]),
    externalToolCalls = o.optLong("externalToolCalls")?.takeIf { it >= 0L },
    caseAttempt = o.int("caseAttempt", 1).coerceIn(1, 3),
    priorAttempts = o.objects("priorAttempts").take(2).mapNotNull { decodeCaseAttempt(it) },
    flaky = o.bool("flaky", false),
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
        toolActivityPath = o.optStr("toolActivityPath"),
        toolCalls = o.objects("toolCalls").takeLast(MAX_PERSISTED_TOOL_CALLS).mapNotNull { call ->
            val id = call.str("id").takeIf(::isSafeId) ?: return@mapNotNull null
            val stepId = call.str("stepId").takeIf(::isSafeId) ?: return@mapNotNull null
            val caseId = call.str("caseId").takeIf(::isSafeId) ?: return@mapNotNull null
            LaneToolCall(
                id = id,
                caseId = caseId,
                stepId = stepId,
                iteration = call.int("iteration", 1).coerceAtLeast(1),
                attempt = call.int("attempt", 1).coerceAtLeast(1),
                toolName = call.str("toolName").take(100),
                argumentsPreview = call.str("argumentsPreview").take(MAX_PERSISTED_TOOL_PREVIEW_CHARS),
                resultPreview = call.str("resultPreview").take(MAX_PERSISTED_TOOL_PREVIEW_CHARS),
                status = call.enumOr("status", LaneToolCallStatus.FAILED),
                startedAt = call.long("startedAt", 0L),
                durationMs = call.optLong("durationMs"),
            )
        },
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
        comparisons = o.objects("comparisons").mapNotNull { decodeComparison(it) },
    )
}

/** Never throws; the failure message is safe to show as-is. A run that was cut off mid-write by a crash has no usable file. */
fun decodeRunFile(text: String): Result<TestRun> = runCatching {
    val root = runCatching { Json.parseToJsonElement(text).jsonObject }.getOrElse { error("This file is not a test run (it is not valid JSON).") }
    if (root.str("format") != TEST_RUN_FILE_FORMAT) error("This file is not an Indagium test run.")
    val runObject = root["run"] as? JsonObject ?: error("This test run file has no run in it.")
    val run = decodeRun(runObject) ?: error("This test run file has no usable run.")

    fun StepResult.hasPendingVerification(): Boolean =
        verificationState == VerificationState.PENDING || attemptHistory.any { it.verificationState == VerificationState.PENDING }

    fun CaseResult.hasPendingVerification(): Boolean =
        steps.any(StepResult::hasPendingVerification) || priorAttempts.any { attempt -> attempt.steps.any(StepResult::hasPendingVerification) }
    val unresolved = run.lanes.any { lane ->
        lane.cases.any(CaseResult::hasPendingVerification)
    }
    if (unresolved && run.status == RunStatus.PASSED) {
        run.copy(
            status = RunStatus.ERROR,
            error = listOfNotNull(run.error, "Stored run had unresolved verification and cannot be shown as passed.").joinToString(" "),
            lanes = run.lanes.map { lane ->
                val lanePending = lane.cases.any(CaseResult::hasPendingVerification)
                lane.copy(
                    status = if (lanePending) RunStatus.ERROR else lane.status,
                    cases = lane.cases.map { case ->
                        val pending = case.hasPendingVerification()
                        case.copy(
                            status = if (pending && case.status == CaseStatus.PASS) CaseStatus.BLOCKED else case.status,
                            priorAttempts = case.priorAttempts.map { prior ->
                                val priorPending = prior.steps.any(StepResult::hasPendingVerification)
                                if (priorPending && prior.status == CaseStatus.PASS) prior.copy(status = CaseStatus.BLOCKED) else prior
                            },
                        )
                    },
                )
            },
        )
    } else {
        run
    }
}
