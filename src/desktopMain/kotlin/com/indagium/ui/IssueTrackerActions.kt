package com.indagium.ui

import com.indagium.ai.normalizeAiProviderProfiles
import com.indagium.testing.model.IssueDestination
import com.indagium.testing.model.IssueDestinationResult
import com.indagium.testing.model.IssueRecord
import com.indagium.testing.run.profileOrNull
import com.indagium.testing.run.profileProblems
import com.indagium.testing.store.StoreResult
import com.indagium.testing.tracker.TrackerIssueCreator
import com.indagium.testing.tracker.TrackerSendRequest
import com.indagium.testing.tracker.TrackerSendResult
import com.indagium.testing.tracker.trackerConnection
import com.indagium.testing.tracker.trackerProblem
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.concurrent.ConcurrentHashMap

// The issue tracker destination: an AI agent (the profile chosen in Settings > Issue tracker) files the issue by calling the
// tracker's MCP tools as the user's prompt says (testing/tracker/TrackerIssueCreator.kt). What reaches the tracker is the
// issue text and the evidence the agent chooses to upload from THIS issue's attachments. The user (the dialog's Create button),
// the in-app AI (a confirmation card) or an external MCP client (a per-call approval) has agreed before this runs. The access
// token is read here, on IO, only to open the connection; it goes nowhere else. A failed attempt is noted on the issue (ok=false)
// and the issue keeps its status; a successful one makes it SENT with the tracker's URL as the destination's reference.

private const val MAX_DESTINATION_RESULTS = 20
private val trackerSendsInFlight: MutableSet<String> = ConcurrentHashMap.newKeySet()

/** What is wrong with the tracker setup, ignoring the token (so nothing asks the secret store); null when a send could start. */
internal fun AppState.trackerPreflightProblem(): String? =
    trackerProblem(settings.tracker, normalizeAiProviderProfiles(settings.aiProviderProfiles), tokenPresent = true)

private fun IssueRecord.trackerCreation(): IssueDestinationResult? =
    destinationResults.lastOrNull { it.destination == IssueDestination.TRACKER && it.ok }

/** Why [record] cannot be sent now (setup, token, already created), or null. Reads the secret store (on IO) only for a tracker that needs a token. */
private suspend fun AppState.trackerSendBlocker(record: IssueRecord, resend: Boolean): String? {
    trackerPreflightProblem()?.let { return it }
    if (settings.tracker.needsToken) refreshTrackerStatus()
    trackerSendProblem()?.let { return it }
    val created = record.trackerCreation()?.takeIf { !resend } ?: return null
    val where = created.reference?.let { url -> ", $url" }.orEmpty()
    return "This issue was already created in the tracker (${created.message}$where). Send it again only to create another issue."
}

/** Sends [record] to the tracker. [resend]: create another tracker issue although one was already created for this record. */
internal suspend fun AppState.deliverToTracker(record: IssueRecord, resend: Boolean): IssueActionResult {
    trackerSendBlocker(record, resend)?.let { return IssueActionResult.Failed(it) }
    if (!trackerSendsInFlight.add(record.id)) return IssueActionResult.Failed("This issue is already being sent to the tracker.")
    try {
        return sendThroughAgent(record)
    } finally {
        trackerSendsInFlight.remove(record.id)
    }
}

@Suppress("TooGenericExceptionCaught") // Starting the agent can fail in many ways; every one is the message the user sees.
private suspend fun AppState.sendThroughAgent(record: IssueRecord): IssueActionResult {
    val tracker = settings.tracker
    val profile = normalizeAiProviderProfiles(settings.aiProviderProfiles).profileOrNull(tracker.agentProfileId)
        ?: return IssueActionResult.Failed("The AI profile that files issues does not exist any more (Settings > Issue tracker).")
    profileProblems("The issue agent", profile, ::aiProviderApiKey).firstOrNull()?.let { return IssueActionResult.Failed(it) }
    val connection = trackerConnection(tracker, if (tracker.needsToken) storedTrackerToken() else "")
    val agent = try {
        productionAgentFactory(testRunOverrides).create(profile, aiProviderApiKey(profile.id))
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (failure: Exception) {
        return IssueActionResult.Failed("The AI profile could not start: ${failure.message ?: failure::class.simpleName}")
    }
    val creator = TrackerIssueCreator(connection, trackerClientFactory(), agent, testRunOverrides.tuning)
    val request = TrackerSendRequest(record, tracker.displayName, tracker.prompt) { attachment -> issueStore.attachmentFile(record, attachment) }
    return when (val result = creator.create(request)) {
        is TrackerSendResult.Created -> {
            val message = "Created ${result.key.ifBlank { "the issue" }} in ${tracker.displayName}"
            recordDelivery(record, IssueDestination.TRACKER, message, reference = result.url, trackerKey = result.key.ifBlank { null })
        }
        is TrackerSendResult.Failed -> {
            recordTrackerFailure(record, result.message)
            IssueActionResult.Failed(result.message)
        }
    }
}

/** Notes a failed attempt on the issue without changing its status. */
private suspend fun AppState.recordTrackerFailure(record: IssueRecord, message: String) = withContext(Dispatchers.IO) {
    val entry = IssueDestinationResult(IssueDestination.TRACKER, ok = false, message = message, at = System.currentTimeMillis())
    val result = issueStore.update(record.id) { it.copy(destinationResults = (it.destinationResults + entry).takeLast(MAX_DESTINATION_RESULTS)) }
    result is StoreResult.Ok
}
