package com.indagium.testing.store

import com.indagium.testing.model.IssueAttachment
import com.indagium.testing.model.IssueAttachmentKind
import com.indagium.testing.model.IssueDestination
import com.indagium.testing.model.IssueDestinationResult
import com.indagium.testing.model.IssueDraft
import com.indagium.testing.model.IssueEnvironment
import com.indagium.testing.model.IssueRecheck
import com.indagium.testing.model.IssueRecord
import com.indagium.testing.model.IssueSeverity
import com.indagium.testing.model.IssueSource
import com.indagium.testing.model.IssueStatus
import com.indagium.testing.model.RecheckOutcome
import com.indagium.testing.model.isSafeId
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put

// JSON codec of `<issues dir>/<issueId>/issue.json`:
//   {"format":"indagium-issue","version":1,"issue":{...}}
// Same rules as the other testing codecs: reads are tolerant (unknown keys ignored, a missing or mistyped field takes its
// default, an unknown enum value falls back to a neutral one), only the envelope is strict. A file written by a NEWER
// version still decodes but is flagged readOnly so the store never rewrites it and loses fields this build does not know.
// Only what is stored is written: an attachment's transient source path or inline text never reaches the file.

const val ISSUE_FILE_FORMAT = "indagium-issue"
const val ISSUE_FILE_VERSION = 1

private val prettyJson = Json { prettyPrint = true }

// ── Writers ──────────────────────────────────────────────────────────

private fun attachmentToJson(a: IssueAttachment): JsonObject = buildJsonObject {
    put("kind", a.kind.name)
    put("label", a.label)
    put("fileName", a.fileName)
    put("sizeBytes", a.sizeBytes)
    put("include", a.include)
    a.storedPath?.let { put("storedPath", it) }
    if (a.note.isNotEmpty()) put("note", a.note)
}

private fun environmentToJson(e: IssueEnvironment): JsonObject = buildJsonObject {
    put("appPackage", e.appPackage)
    put("deviceModel", e.deviceModel)
    put("deviceSerial", e.deviceSerial)
    put("agent", e.agent)
    put("runId", e.runId)
    put("build", e.build)
}

private fun draftToJson(d: IssueDraft): JsonObject = buildJsonObject {
    put("title", d.title)
    put("severity", d.severity.name)
    put("labels", buildJsonArray { d.labels.forEach { add(JsonPrimitive(it)) } })
    put("stepsToReproduce", buildJsonArray { d.stepsToReproduce.forEach { add(JsonPrimitive(it)) } })
    put("expected", d.expected)
    put("actual", d.actual)
    put("judgeNotes", d.judgeNotes)
    put("environment", environmentToJson(d.environment))
    put("attachments", buildJsonArray { d.attachments.forEach { add(attachmentToJson(it)) } })
}

private fun sourceToJson(s: IssueSource): JsonObject = buildJsonObject {
    put("runId", s.runId)
    put("laneId", s.laneId)
    put("suiteId", s.suiteId)
    put("caseId", s.caseId)
    put("stepId", s.stepId)
    put("iteration", s.iteration)
    put("caseName", s.caseName)
    put("stepNumber", s.stepNumber)
}

private fun destinationResultToJson(r: IssueDestinationResult): JsonObject = buildJsonObject {
    put("destination", r.destination.name)
    put("ok", r.ok)
    put("message", r.message)
    put("at", r.at)
    r.reference?.let { put("reference", it) }
}

internal fun issueToJson(record: IssueRecord): JsonObject = buildJsonObject {
    put("id", record.id)
    put("status", record.status.name)
    put("createdAt", record.createdAt)
    put("updatedAt", record.updatedAt)
    put("linkToCase", record.linkToCase)
    put("source", sourceToJson(record.source))
    put("draft", draftToJson(record.draft))
    put("destinationResults", buildJsonArray { record.destinationResults.forEach { add(destinationResultToJson(it)) } })
    record.recheck?.let { check ->
        put(
            "recheck",
            buildJsonObject {
                put("runId", check.runId)
                put("outcome", check.outcome.name)
                put("checkedAt", check.checkedAt)
            },
        )
    }
}

fun encodeIssueFile(record: IssueRecord): String = prettyJson.encodeToString(
    JsonElement.serializer(),
    buildJsonObject {
        put("format", ISSUE_FILE_FORMAT)
        put("version", ISSUE_FILE_VERSION)
        put("issue", issueToJson(record))
    },
)

// ── Readers ──────────────────────────────────────────────────────────

private fun decodeAttachment(o: JsonObject): IssueAttachment? {
    val kind = IssueAttachmentKind.entries.firstOrNull { it.name == o.optStr("kind") } ?: return null
    val fileName = o.str("fileName").takeIf { it.isNotBlank() } ?: return null
    return IssueAttachment(
        kind = kind,
        label = o.str("label", fileName),
        fileName = fileName,
        sizeBytes = o.long("sizeBytes", 0L),
        include = o.bool("include", true),
        storedPath = o.optStr("storedPath"),
        note = o.str("note"),
    )
}

private fun decodeEnvironment(o: JsonObject?): IssueEnvironment {
    if (o == null) return IssueEnvironment()
    return IssueEnvironment(
        appPackage = o.str("appPackage"), deviceModel = o.str("deviceModel"), deviceSerial = o.str("deviceSerial"),
        agent = o.str("agent"), runId = o.str("runId"), build = o.str("build"),
    )
}

private fun decodeDraft(o: JsonObject): IssueDraft = IssueDraft(
    title = o.str("title"),
    severity = o.enumOr("severity", IssueSeverity.MEDIUM),
    labels = o.strings("labels"),
    stepsToReproduce = o.strings("stepsToReproduce"),
    expected = o.str("expected"),
    actual = o.str("actual"),
    judgeNotes = o.str("judgeNotes"),
    environment = decodeEnvironment(o["environment"] as? JsonObject),
    attachments = o.objects("attachments").mapNotNull { decodeAttachment(it) },
)

private fun decodeSource(o: JsonObject?): IssueSource {
    if (o == null) return IssueSource("", "", "", "", "")
    return IssueSource(
        runId = o.str("runId"), laneId = o.str("laneId"), suiteId = o.str("suiteId"), caseId = o.str("caseId"),
        stepId = o.str("stepId"), iteration = o.int("iteration", 1), caseName = o.str("caseName"), stepNumber = o.int("stepNumber", 0),
    )
}

private fun decodeDestinationResult(o: JsonObject): IssueDestinationResult? {
    val destination = IssueDestination.entries.firstOrNull { it.name == o.optStr("destination") } ?: return null
    return IssueDestinationResult(destination, o.bool("ok", false), o.str("message"), o.long("at", 0L), o.optStr("reference"))
}

private fun decodeRecheck(o: JsonObject?): IssueRecheck? {
    if (o == null) return null
    val outcome = RecheckOutcome.entries.firstOrNull { it.name == o.optStr("outcome") } ?: return null
    return IssueRecheck(o.str("runId"), outcome, o.long("checkedAt", 0L))
}

private fun decodeIssue(o: JsonObject, readOnly: Boolean): IssueRecord? {
    val id = o.str("id").takeIf { isSafeId(it) } ?: return null
    return IssueRecord(
        id = id,
        draft = decodeDraft(o["draft"] as? JsonObject ?: JsonObject(emptyMap())),
        source = decodeSource(o["source"] as? JsonObject),
        status = o.enumOr("status", IssueStatus.DRAFT),
        createdAt = o.long("createdAt", 0L),
        updatedAt = o.long("updatedAt", 0L),
        destinationResults = o.objects("destinationResults").mapNotNull { decodeDestinationResult(it) },
        linkToCase = o.bool("linkToCase", false),
        recheck = decodeRecheck(o["recheck"] as? JsonObject),
        readOnly = readOnly,
    )
}

/** Never throws; the failure message is safe to show as-is. */
fun decodeIssueFile(text: String): Result<IssueRecord> = runCatching {
    val root = runCatching { Json.parseToJsonElement(text).jsonObject }.getOrElse { error("This file is not an issue (it is not valid JSON).") }
    if (root.str("format") != ISSUE_FILE_FORMAT) error("This file is not an Indagium issue.")
    val issue = root["issue"] as? JsonObject ?: error("This issue file has no issue in it.")
    val newer = root.int("version", ISSUE_FILE_VERSION) > ISSUE_FILE_VERSION
    decodeIssue(issue, readOnly = newer) ?: error("This issue file has no usable issue.")
}
