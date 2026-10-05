package com.indagium.ui

import com.indagium.ai.normalizeAiProviderProfiles
import com.indagium.security.SecretKey
import com.indagium.security.SecretResult
import com.indagium.security.secretProblem
import com.indagium.security.statusLine
import com.indagium.testing.model.TRACKER_TOKEN_ACCOUNT
import com.indagium.testing.model.TrackerSettings
import com.indagium.testing.tracker.TrackerMcpClientFactory
import com.indagium.testing.tracker.TrackerMcpException
import com.indagium.testing.tracker.checkAuthHeaderName
import com.indagium.testing.tracker.checkTrackerUrl
import com.indagium.testing.tracker.sdkTrackerMcpClientFactory
import com.indagium.testing.tracker.trackerConnection
import com.indagium.testing.tracker.trackerProblem
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

// Connects the issue tracker (testing/tracker, which knows nothing of AppState) to the app: the token in the SecretStore (read
// and written off the UI thread, never held anywhere but there), the cached "is a token stored" answer the UI shows, and the
// "Test connection" action. The token never enters AppSettings, autosave, run.json, issue.json or a transcript.

private const val CONNECTION_FAILED = "The connection failed."

internal val TRACKER_TOKEN_KEY = SecretKey(TRACKER_TOKEN_ACCOUNT)

/** What is known about the stored token. [tokenPresent] is null until the store was asked; [problem] is why it could not be read. */
internal data class TrackerStatus(val tokenPresent: Boolean? = null, val storageLine: String = "", val problem: String? = null)

/** The line under the token field. */
internal fun TrackerStatus.tokenLine(): String = when {
    tokenPresent == null -> "Checking the stored token…"
    tokenPresent -> storageLine
    problem != null -> "No token saved. The system secret store reported: $problem"
    else -> "No token saved."
}

/** Asks the secret store (on IO) whether a token is saved and publishes the answer in [AppState.trackerStatus]. */
internal suspend fun AppState.refreshTrackerStatus() {
    val outcome = withContext(Dispatchers.IO) { secretStore.read(TRACKER_TOKEN_KEY) }
    trackerStatus = when (outcome) {
        is SecretResult.Ok -> TrackerStatus(outcome.value != null, secretStore.statusLine(), null)
        is SecretResult.Failed -> TrackerStatus(false, secretStore.statusLine(), outcome.reason)
    }
}

/** Saves [token]; returns why that failed, or null. The text is never kept anywhere else. */
internal suspend fun AppState.saveTrackerToken(token: String): String? {
    val trimmed = token.trim()
    secretProblem(trimmed)?.let { return it }
    val outcome = withContext(Dispatchers.IO) { secretStore.write(TRACKER_TOKEN_KEY, trimmed) }
    refreshTrackerStatus()
    return (outcome as? SecretResult.Failed)?.reason
}

/** Removes the stored token; returns why that failed, or null. */
internal suspend fun AppState.removeTrackerToken(): String? {
    val outcome = withContext(Dispatchers.IO) { secretStore.delete(TRACKER_TOKEN_KEY) }
    refreshTrackerStatus()
    return (outcome as? SecretResult.Failed)?.reason
}

/** The stored token, or "" when there is none (or the store failed). Reads on IO. */
internal suspend fun AppState.storedTrackerToken(): String =
    withContext(Dispatchers.IO) { (secretStore.read(TRACKER_TOKEN_KEY) as? SecretResult.Ok)?.value.orEmpty() }

/** What still has to be set before an issue can go to the tracker, or null when it can. */
internal fun AppState.trackerSendProblem(): String? =
    trackerProblem(settings.tracker, normalizeAiProviderProfiles(settings.aiProviderProfiles), trackerStatus.tokenPresent)

internal fun AppState.trackerClientFactory(): TrackerMcpClientFactory = testRunOverrides.trackerClients ?: sdkTrackerMcpClientFactory

internal sealed interface TrackerTestResult {
    data class Connected(val toolNames: List<String>) : TrackerTestResult

    data class Failed(val message: String) : TrackerTestResult
}

/**
 * Connects to the tracker described by [tracker] and lists its tools. [typedToken] is a token the user typed but did not save
 * yet; blank uses the stored one. Runs on IO and never throws.
 */
@Suppress("TooGenericExceptionCaught") // A client may fail in many ways; the settings page shows one line.
internal suspend fun AppState.testTrackerConnection(tracker: TrackerSettings, typedToken: String): TrackerTestResult {
    val url = checkTrackerUrl(tracker.mcpUrl)
    url.problem?.let { return TrackerTestResult.Failed(it) }
    checkAuthHeaderName(tracker.authHeaderName)?.let { return TrackerTestResult.Failed(it) }
    val token = if (tracker.needsToken) typedToken.trim().ifEmpty { storedTrackerToken() } else ""
    if (tracker.needsToken && token.isEmpty()) return TrackerTestResult.Failed("Enter or save the access token first.")
    val client = trackerClientFactory().create(trackerConnection(tracker, token))
    return withContext(Dispatchers.IO) {
        try {
            client.connect()
            TrackerTestResult.Connected(client.listTools().map { it.name })
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: TrackerMcpException) {
            TrackerTestResult.Failed(failure.message ?: CONNECTION_FAILED)
        } catch (failure: Exception) {
            TrackerTestResult.Failed(failure.message?.let { "$CONNECTION_FAILED $it" } ?: CONNECTION_FAILED)
        } finally {
            client.close()
        }
    }
}
