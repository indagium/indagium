package com.openlog.ai

import com.openlog.model.AiProviderProfile
import com.openlog.model.defaultAiProviderProfile
import java.net.URI

enum class AiProviderUrlProblem(val message: String) {
    MALFORMED("Enter a complete HTTP or HTTPS endpoint URL."),
    REMOTE_HTTP("Remote model endpoints must use HTTPS."),
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
    if (scheme != "https") return AiProviderUrlValidation(AiProviderUrlProblem.REMOTE_HTTP)
    return if (profile.remoteDisclosureAcknowledged) AiProviderUrlValidation()
    else AiProviderUrlValidation(AiProviderUrlProblem.REMOTE_DISCLOSURE_REQUIRED)
}

fun isLoopbackHost(host: String): Boolean = when (host.trim('[', ']').lowercase()) {
    "localhost", "127.0.0.1", "::1", "0:0:0:0:0:0:0:1" -> true
    else -> false
}

/** Keeps migration from old or malformed settings safe and leaves one selected profile. */
fun normalizeAiProviderProfiles(profiles: List<AiProviderProfile>): List<AiProviderProfile> {
    val distinct = profiles.filter { it.id.isNotBlank() }.distinctBy { it.id }
        .ifEmpty { listOf(defaultAiProviderProfile()) }
    val selectedId = distinct.firstOrNull { it.selected }?.id ?: distinct.first().id
    return distinct.map { it.copy(selected = it.id == selectedId) }
}
