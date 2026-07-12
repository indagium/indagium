package com.openlog.ai

import com.openlog.model.AiProviderProfile
import com.openlog.model.defaultAiProviderProfile
import java.net.URI

enum class AiProviderUrlProblem(val message: String) {
    MALFORMED("Enter a complete HTTP or HTTPS endpoint URL."),
    REMOTE_DISCLOSURE_REQUIRED("Acknowledge that logs and source context may leave this device."),
}

data class AiProviderUrlValidation(val problem: AiProviderUrlProblem? = null) {
    val isValid: Boolean get() = problem == null
}

/** Local and deterministic: settings validation must never resolve arbitrary hosts over DNS. */
fun validateAiProviderProfile(profile: AiProviderProfile): AiProviderUrlValidation {
    val endpoint = runCatching { URI(profile.baseUrl.trim()) }.getOrNull()
        ?: return AiProviderUrlValidation(AiProviderUrlProblem.MALFORMED)
    val scheme = endpoint.scheme?.lowercase()
    val host = endpoint.host?.trim('[', ']')?.lowercase()
    if (scheme !in setOf("http", "https") || host.isNullOrBlank()) {
        return AiProviderUrlValidation(AiProviderUrlProblem.MALFORMED)
    }
    if (isLoopbackHost(host)) return AiProviderUrlValidation()
    // LM Studio (and many local-network OpenAI-compatible servers) only ever serve HTTP, even
    // when reached over a LAN address rather than loopback, so HTTPS cannot be required here.
    return if (profile.remoteDisclosureAcknowledged) AiProviderUrlValidation()
    else AiProviderUrlValidation(AiProviderUrlProblem.REMOTE_DISCLOSURE_REQUIRED)
}

fun isLoopbackHost(host: String): Boolean = when (host.trim('[', ']').lowercase()) {
    "localhost", "127.0.0.1", "::1", "0:0:0:0:0:0:0:1" -> true
    else -> false
}

/**
 * LM Studio's own UI displays its "Reachable at" address as a bare `http://host:port` with no
 * path, but this app only ever speaks the OpenAI-compatible `/v1` contract (`/v1/models`,
 * `/v1/chat/completions`). Pasting that bare address verbatim used to hit `/models` at the root,
 * which some servers answer with an unrelated 200 instead of a 404 - silently reporting "reachable,
 * 0 models found" rather than a clear error. Treat a path-less base URL as shorthand for `/v1`
 * without rewriting anything the user actually typed with an explicit path.
 */
fun aiProviderRequestBaseUrl(rawBaseUrl: String): String {
    val trimmed = rawBaseUrl.trim()
    val path = runCatching { URI(trimmed).rawPath.orEmpty() }.getOrDefault("")
    return if (path.isEmpty() || path == "/") trimmed.trimEnd('/') + "/v1" else trimmed
}

/** Keeps migration from old or malformed settings safe and leaves one selected profile. */
fun normalizeAiProviderProfiles(profiles: List<AiProviderProfile>): List<AiProviderProfile> {
    val distinct = profiles.filter { it.id.isNotBlank() }.distinctBy { it.id }
        .ifEmpty { listOf(defaultAiProviderProfile()) }
    val selectedId = distinct.firstOrNull { it.selected }?.id ?: distinct.first().id
    return distinct.map { it.copy(selected = it.id == selectedId) }
}
