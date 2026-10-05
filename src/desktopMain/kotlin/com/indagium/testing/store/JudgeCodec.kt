package com.indagium.testing.store

import com.indagium.testing.model.JudgeClassification
import com.indagium.testing.model.JudgeComparison
import com.indagium.testing.model.JudgeVerdict
import com.indagium.testing.model.StepFix
import com.indagium.testing.model.StepJudgement
import com.indagium.testing.model.isSafeId
import com.indagium.testing.model.newComparisonId
import com.indagium.testing.model.newJudgementId
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

// JSON of the judge's results inside run.json (the same tolerant style as TestRunCodec: unknown keys are ignored, a
// missing or mistyped field takes its default, an unknown enum value falls back to a neutral one).

private fun fixToJson(fix: StepFix): JsonObject = buildJsonObject {
    fix.action?.let { put("action", it) }
    fix.expected?.let { put("expected", it) }
    fix.note?.let { put("note", it) }
}

private fun decodeFix(o: JsonObject?): StepFix? {
    if (o == null) return null
    val fix = StepFix(o.optStr("action"), o.optStr("expected"), o.optStr("note"))
    return fix.takeIf { it.action != null || it.expected != null || it.note != null }
}

internal fun judgementToJson(judgement: StepJudgement): JsonObject = buildJsonObject {
    put("id", judgement.id)
    put("verdict", judgement.verdict.name)
    put("reasoning", judgement.reasoning)
    put("classification", judgement.classification.name)
    judgement.suggestedFix?.let { put("suggestedFix", fixToJson(it)) }
    put("judgedAt", judgement.judgedAt)
    put("durationMs", judgement.durationMs)
    judgement.error?.let { put("error", it) }
    put("fixApplied", judgement.fixApplied)
}

internal fun decodeJudgement(o: JsonObject): StepJudgement = StepJudgement(
    id = o.str("id").takeIf { isSafeId(it) } ?: newJudgementId(),
    verdict = o.enumOr("verdict", JudgeVerdict.INCONCLUSIVE),
    reasoning = o.str("reasoning"),
    classification = o.enumOr("classification", JudgeClassification.UNKNOWN),
    suggestedFix = decodeFix(o["suggestedFix"] as? JsonObject),
    judgedAt = o.long("judgedAt", 0L),
    durationMs = o.long("durationMs", 0L),
    error = o.optStr("error"),
    fixApplied = o.bool("fixApplied", false),
)

internal fun comparisonToJson(comparison: JudgeComparison): JsonObject = buildJsonObject {
    put("id", comparison.id)
    put("caseId", comparison.caseId)
    put("iteration", comparison.iteration)
    put("stepId", comparison.stepId)
    put("stepNumber", comparison.stepNumber)
    put("action", comparison.action)
    put("verdicts", buildJsonObject { comparison.verdicts.forEach { (laneId, verdict) -> put(laneId, verdict.name) } })
    put("classification", comparison.classification.name)
    put("explanation", comparison.explanation)
    comparison.suggestedFix?.let { put("suggestedFix", fixToJson(it)) }
    put("judgedAt", comparison.judgedAt)
    comparison.error?.let { put("error", it) }
    put("fixApplied", comparison.fixApplied)
}

internal fun decodeComparison(o: JsonObject): JudgeComparison? {
    val stepId = o.str("stepId").takeIf { it.isNotBlank() } ?: return null
    val verdicts = (o["verdicts"] as? JsonObject)?.entries.orEmpty().mapNotNull { (laneId, value) ->
        val verdict = JudgeVerdict.entries.firstOrNull { it.name == (value as? JsonPrimitive)?.content }
        verdict?.let { laneId to it }
    }.toMap()
    return JudgeComparison(
        id = o.str("id").takeIf { isSafeId(it) } ?: newComparisonId(),
        caseId = o.str("caseId"),
        iteration = o.int("iteration", 1),
        stepId = stepId,
        stepNumber = o.int("stepNumber", 0),
        action = o.str("action"),
        verdicts = verdicts,
        classification = o.enumOr("classification", JudgeClassification.UNKNOWN),
        explanation = o.str("explanation"),
        suggestedFix = decodeFix(o["suggestedFix"] as? JsonObject),
        judgedAt = o.long("judgedAt", 0L),
        error = o.optStr("error"),
        fixApplied = o.bool("fixApplied", false),
    )
}
