package com.indagium.debug

import com.indagium.testing.model.IssueAttachment
import com.indagium.testing.model.IssueDestination
import com.indagium.testing.model.IssueDraft
import com.indagium.testing.run.formatByteSize
import com.indagium.ui.AppState
import com.indagium.ui.ExternalActionDetails
import com.indagium.ui.buildIssueSeed
import com.indagium.ui.trackerPreflightProblem
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

// What the user is shown before an EXTERNAL MCP client may send an issue to the issue tracker (send_issue_to_tracker, or
// create_issue_from_step with destination tracker). The issue text and its evidence go to an AI agent and to an external service,
// so every call needs the user's yes and the dialog names the tracker, the agent, the issue and every attachment the agent will be
// able to read. A call the tool would refuse anyway (no tracker set up, unknown issue or step) returns null: nothing is sent and
// the tool reports the error itself, so the user is not asked about it. A create_issue_from_step call with another destination
// needs no approval of its own (it is null here too).

private const val MAX_ATTACHMENTS_SHOWN = 12
private const val DECLINED_TRACKER_MESSAGE = "The user declined to send this issue to the issue tracker, or did not answer in time; nothing was sent."
private const val TRACKER_SEND_SUMMARY =
    " wants to send an issue to the issue tracker. An AI agent files it with the tracker's tools; the text below and the attachments listed " +
        "can be read by that agent and are sent to the tracker."

private class TrackerIssuePreview(val title: String, val severity: String, val attachments: List<IssueAttachment>)

private fun IssueDraft.preview(titleOverride: String? = null) = TrackerIssuePreview(
    title = titleOverride?.trim()?.takeIf { it.isNotEmpty() } ?: title,
    severity = severity.name,
    attachments = attachments.filter { it.include },
)

private suspend fun previewOf(appState: AppState, toolName: String, arguments: Map<String, Any?>): TrackerIssuePreview? = try {
    when (toolName) {
        "send_issue_to_tracker" -> {
            val issueId = (arguments["issueId"] as? String)?.trim().orEmpty()
            withContext(Dispatchers.IO) { appState.issueStore.load(issueId) }?.draft?.preview()
        }
        "create_issue_from_step" -> previewOfStep(appState, ToolArgs(arguments))
        else -> null
    }
} catch (_: ToolArgException) {
    null
}

private suspend fun previewOfStep(appState: AppState, args: ToolArgs): TrackerIssuePreview? {
    if (args.enum("destination", IssueDestination.entries) != IssueDestination.TRACKER) return null
    val runId = args.string("runId")?.trim().orEmpty()
    val laneId = args.string("laneId")?.trim().orEmpty()
    val caseId = args.string("caseId")?.trim().orEmpty()
    val stepId = args.string("stepId")?.trim().orEmpty()
    val iteration = args.int("iteration") ?: 1
    val titleOverride = (args.map["overrides"] as? Map<*, *>)?.get("title") as? String
    val run = appState.testRunCoordinator.loadRun(runId) ?: return null
    val existing = run.stepResult(laneId, caseId, iteration, stepId)?.issueId?.let { withContext(Dispatchers.IO) { appState.issueStore.load(it) } }
    if (existing != null) return existing.draft.preview(titleOverride)
    return appState.buildIssueSeed(runId, laneId, caseId, iteration, stepId).getOrNull()?.draft?.preview(titleOverride)
}

private fun attachmentLines(attachments: List<IssueAttachment>): String {
    if (attachments.isEmpty()) return "(none)"
    val shown = attachments.take(MAX_ATTACHMENTS_SHOWN).joinToString("\n") { "${it.label} · ${formatByteSize(it.sizeBytes)}" }
    val hidden = attachments.size - MAX_ATTACHMENTS_SHOWN
    return if (hidden > 0) "$shown\n+ $hidden more" else shown
}

/** The approval dialog content for a tracker send, or null when no approval is needed or the tool would refuse the call anyway. */
internal suspend fun describeTrackerSendCall(appState: AppState, toolName: String, arguments: Map<String, Any?>, clientName: String): ExternalActionDetails? {
    if (appState.trackerPreflightProblem() != null) return null
    val preview = previewOf(appState, toolName, arguments) ?: return null
    val tracker = appState.settings.tracker
    val profile = appState.settings.aiProviderProfiles.firstOrNull { it.id == tracker.agentProfileId }
    return ExternalActionDetails(
        title = "Send an issue to the issue tracker?",
        summary = clientName + TRACKER_SEND_SUMMARY,
        fields = listOf(
            "Tracker" to "${tracker.displayName} (${tracker.mcpUrl.trim()})",
            "AI profile that files it" to (profile?.let { "${it.displayName} · ${it.kind.label}" } ?: "(none)"),
            "Issue" to "${preview.title} (severity ${preview.severity})",
            "Evidence the agent can read" to attachmentLines(preview.attachments),
        ),
        allowLabel = "Send to tracker",
        declinedMessage = DECLINED_TRACKER_MESSAGE,
    )
}
