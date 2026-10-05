package com.indagium.debug

import com.indagium.testing.model.IssueDestination
import com.indagium.testing.model.IssueRecord
import com.indagium.testing.model.IssueSeverity
import com.indagium.testing.model.IssueStatus
import com.indagium.testing.store.issueToJson
import com.indagium.ui.AppState
import com.indagium.ui.IssueActionResult
import com.indagium.ui.IssueOverrides
import com.indagium.ui.createIssueFromStep
import com.indagium.ui.deleteIssue
import com.indagium.ui.deliverIssue
import com.indagium.ui.issueMarkdown
import com.indagium.ui.updateIssueFields
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

// Handlers of the issue tools (catalogue: IssueToolCatalog.kt), merged into IndagiumToolOperations like the other test tools.
// Every handler returns a plain Map and every expected failure is DATA, `{ "error": message }`, never an exception. They
// all suspend: the issue store is on disk, so no request thread waits on it.

private const val DEFAULT_LIST_LIMIT = 50
private const val MAX_LIST_LIMIT = 500

internal class IssueToolOperations(private val appState: AppState) {
    val suspendHandlers: Map<String, suspend (Map<String, Any?>) -> Any?> = mapOf(
        "create_issue_from_step" to suspendTool { a -> createFromStep(a) },
        "list_issues" to suspendTool { a -> list(a) },
        "get_issue" to suspendTool { a -> get(a) },
        "update_issue" to suspendTool { a -> update(a) },
        "send_issue_to_tracker" to suspendTool { a -> sendToTracker(a) },
        "delete_issue" to suspendTool { a -> delete(a.requiredString("issueId")) },
    )

    private fun suspendTool(body: suspend (ToolArgs) -> Any?): suspend (Map<String, Any?>) -> Any? = { raw ->
        try {
            body(ToolArgs(raw))
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (e: ToolArgException) {
            errorMap(e.message ?: "Invalid arguments.")
        }
    }

    private fun errorMap(message: String): Map<String, Any?> = mapOf("error" to message)

    private fun overridesOf(source: ToolArgs): IssueOverrides = IssueOverrides(
        title = source.string("title"),
        severity = source.enum("severity", IssueSeverity.entries),
        labels = source.strings("labels"),
        stepsToReproduce = source.strings("stepsToReproduce"),
        expected = source.string("expected"),
        actual = source.string("actual"),
        judgeNotes = source.string("judgeNotes"),
        linkToCase = source.bool("linkToCase"),
    )

    private fun IssueRecord.toMap(): Map<String, Any?> = issueToJson(this).toPlainMap() + ("folder" to appState.issueStore.issueDir(id).absolutePath)

    private fun IssueActionResult.Done.toMap(extra: Map<String, Any?> = emptyMap()): Map<String, Any?> = buildMap {
        put("issueId", record.id)
        put("status", record.status.name)
        put("message", message)
        if (warnings.isNotEmpty()) put("warnings", warnings)
        markdown?.let { put("markdown", it) }
        tabId?.let { put("tabId", it) }
        trackerUrl?.let { put("trackerUrl", it) }
        trackerKey?.let { put("trackerKey", it) }
        putAll(extra)
    }

    private fun IssueActionResult.toAnswer(): Map<String, Any?> = when (this) {
        is IssueActionResult.Done -> toMap()
        is IssueActionResult.Failed -> errorMap(message)
        is IssueActionResult.NeedsLogTab -> mapOf(
            "error" to "No open tab shows the lane's log. Pass tabId, or openLaneLog=true to open it as a tab.",
            "needsLogTab" to true,
            "issueId" to record.id,
            "logFile" to logFile.absolutePath,
        )
    }

    // ── create_issue_from_step ───────────────────────────────────────

    private suspend fun createFromStep(a: ToolArgs): Map<String, Any?> {
        val destination = a.enum("destination", IssueDestination.entries) ?: IssueDestination.LOCAL
        val overrides = a.map["overrides"]?.asObject("overrides")?.let { overridesOf(ToolArgs(it)) } ?: IssueOverrides()
        return appState.createIssueFromStep(
            runId = a.requiredString("runId"),
            laneId = a.requiredString("laneId"),
            caseId = a.requiredString("caseId"),
            iteration = a.int("iteration") ?: 1,
            stepId = a.requiredString("stepId"),
            destination = destination,
            overrides = overrides,
            tabId = a.string("tabId")?.trim()?.takeIf { it.isNotEmpty() },
            openLaneLog = a.bool("openLaneLog") ?: false,
            resendToTracker = a.bool("resend") ?: false,
        ).toAnswer()
    }

    // ── Reading ──────────────────────────────────────────────────────

    private suspend fun list(a: ToolArgs): Map<String, Any?> {
        val runId = a.string("runId")?.trim()?.takeIf { it.isNotEmpty() }
        val status = a.enum("status", IssueStatus.entries)
        val limit = (a.int("limit") ?: DEFAULT_LIST_LIMIT).coerceIn(1, MAX_LIST_LIMIT)
        val issues = withContext(Dispatchers.IO) { appState.issueStore.list() }
            .filter { (runId == null || it.source.runId == runId) && (status == null || it.status == status) }
            .take(limit)
        return mapOf(
            "issues" to issues.map { issue ->
                mapOf(
                    "issueId" to issue.id, "title" to issue.draft.title, "severity" to issue.draft.severity.name,
                    "status" to issue.status.name, "caseName" to issue.source.caseName, "runId" to issue.source.runId,
                    "stepId" to issue.source.stepId, "createdAt" to issue.createdAt, "linkToCase" to issue.linkToCase,
                    "recheck" to issue.recheck?.outcome?.name,
                )
            },
        )
    }

    private suspend fun get(a: ToolArgs): Map<String, Any?> {
        val issueId = a.requiredString("issueId")
        val format = a.string("format")?.trim()?.lowercase() ?: "json"
        if (format != "json" && format != "markdown") toolArgError("format must be json or markdown.")
        val record = withContext(Dispatchers.IO) { appState.issueStore.load(issueId) } ?: return errorMap("Issue '$issueId' was not found.")
        return if (format == "markdown") {
            mapOf("issueId" to record.id, "format" to "markdown", "markdown" to withContext(Dispatchers.IO) { appState.issueMarkdown(record) })
        } else {
            mapOf("issueId" to record.id, "format" to "json", "issue" to record.toMap())
        }
    }

    // ── Changing ─────────────────────────────────────────────────────

    private suspend fun update(a: ToolArgs): Map<String, Any?> {
        val issueId = a.requiredString("issueId")
        return when (val result = appState.updateIssueFields(issueId, overridesOf(a))) {
            is IssueActionResult.Done -> result.toMap(mapOf("issue" to result.record.toMap()))
            else -> result.toAnswer()
        }
    }

    private suspend fun sendToTracker(a: ToolArgs): Map<String, Any?> = appState.deliverIssue(
        a.requiredString("issueId"), IssueDestination.TRACKER, copyMarkdown = false, resendToTracker = a.bool("resend") ?: false,
    ).toAnswer()

    private suspend fun delete(issueId: String): Map<String, Any?> = when (val result = appState.deleteIssue(issueId)) {
        is IssueActionResult.Done -> mapOf("issueId" to issueId, "deleted" to true)
        else -> result.toAnswer()
    }
}
