package com.indagium.testing.tracker

import com.indagium.ai.isLoopbackHost
import com.indagium.model.AiProviderProfile
import com.indagium.testing.model.TrackerSettings
import java.net.URI

// What a configured tracker means, as pure functions: whether the URL and the auth header are acceptable, what is still
// missing before an issue can be sent, and the header the connection carries. Nothing here touches the network or the token
// store; callers pass in whether a token is stored.

const val TRACKER_NOT_CONFIGURED_HINT = "Configure an issue tracker in Settings"
private const val HTTP = "http"
private const val HTTPS = "https"
private const val PLAIN_HTTP_WARNING =
    "This URL is plain http: the token and the issue text travel unencrypted. Use https unless the tracker is on a trusted network."
private val HEADER_NAME = Regex("[!#$%&'*+.^_`|~0-9A-Za-z-]+")

// Headers the transport sets itself, or that would change how the request is framed or routed.
private val RESERVED_HEADERS = setOf(
    "host", "content-length", "content-type", "accept", "transfer-encoding", "connection", "mcp-session-id", "mcp-protocol-version",
    "last-event-id", "upgrade", "te", "trailer",
)

/** The result of checking the MCP URL: usable (maybe with a [warning]) or not ([problem]). */
data class TrackerUrlCheck(val problem: String?, val warning: String?) {
    val valid: Boolean get() = problem == null
}

/** Checks [raw]: an http or https URL with a host, no credentials in it; plain http to a host that is not this computer gets a warning. */
fun checkTrackerUrl(raw: String): TrackerUrlCheck {
    val text = raw.trim()
    if (text.isEmpty()) return TrackerUrlCheck("Enter the tracker's MCP URL.", null)
    val uri = runCatching { URI(text) }.getOrNull() ?: return TrackerUrlCheck("That is not a valid URL.", null)
    val scheme = uri.scheme?.lowercase()
    val host = uri.host.orEmpty()
    return when {
        scheme != HTTP && scheme != HTTPS -> TrackerUrlCheck("The URL must start with http:// or https://.", null)
        host.isEmpty() -> TrackerUrlCheck("The URL needs a host name.", null)
        uri.userInfo != null -> TrackerUrlCheck("Do not put a user name or password in the URL; use the token field.", null)
        scheme == HTTP && !isLoopbackHost(host) ->
            TrackerUrlCheck(null, PLAIN_HTTP_WARNING)
        else -> TrackerUrlCheck(null, null)
    }
}

/** Null when [name] can be the auth header (blank means "no authentication"), else why not. */
fun checkAuthHeaderName(name: String): String? {
    val text = name.trim()
    return when {
        text.isEmpty() -> null
        !HEADER_NAME.matches(text) -> "That is not a valid header name."
        text.lowercase() in RESERVED_HEADERS -> "$text is set by the connection itself; choose another header."
        else -> null
    }
}

/** The header value for [token]: `Bearer <token>` or the token itself. */
fun trackerAuthHeaderValue(settings: TrackerSettings, token: String): String = if (settings.bearerPrefix) "Bearer $token" else token

/** What a tracker connection carries; [headerName] blank means no authentication. The value holds the token, so it never prints. */
class TrackerConnection(val url: String, val headerName: String, headerValue: String) {
    private val secretValue = headerValue

    /** The token-carrying header value. Read only where the HTTP request is built. */
    internal fun headerValue(): String = secretValue

    val hasAuth: Boolean get() = headerName.isNotBlank() && secretValue.isNotEmpty()

    override fun toString(): String = "TrackerConnection(url=$url, header=${headerName.ifBlank { "(none)" }})"
}

/** The connection of [settings] with [token] (which may be blank when [TrackerSettings.needsToken] is false). */
fun trackerConnection(settings: TrackerSettings, token: String): TrackerConnection =
    TrackerConnection(settings.mcpUrl.trim(), settings.authHeaderName.trim(), if (token.isEmpty()) "" else trackerAuthHeaderValue(settings, token))

/**
 * What still has to be set before an issue can be sent to the tracker, or null when it can. [tokenPresent] null means the
 * token store has not been asked yet.
 */
fun trackerProblem(settings: TrackerSettings, profiles: List<AiProviderProfile>, tokenPresent: Boolean?): String? = when {
    !settings.enabled -> TRACKER_NOT_CONFIGURED_HINT
    else -> checkTrackerUrl(settings.mcpUrl).problem
        ?: checkAuthHeaderName(settings.authHeaderName)
        ?: profileProblem(settings, profiles)
        ?: tokenProblem(settings, tokenPresent)
}

private fun profileProblem(settings: TrackerSettings, profiles: List<AiProviderProfile>): String? =
    if (profiles.none { it.id == settings.agentProfileId }) "Choose the AI profile that files the issue (Settings > Issue tracker)" else null

private fun tokenProblem(settings: TrackerSettings, tokenPresent: Boolean?): String? = when {
    !settings.needsToken -> null
    tokenPresent == null -> "Checking for the tracker's token…"
    !tokenPresent -> "Add the tracker's access token (Settings > Issue tracker)"
    else -> null
}
