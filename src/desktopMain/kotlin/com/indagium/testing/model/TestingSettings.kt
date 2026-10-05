package com.indagium.testing.model

// The user's standing choices for the AI test suites, kept in AppSettings (JSON form only; ui/TestingSettingsCodec.kt):
// the issue tracker an issue can be sent to, and the defaults a new test run starts from. The tracker's ACCESS TOKEN is
// deliberately not here: it lives only in the SecretStore (security/SecretStore.kt) under [TRACKER_TOKEN_ACCOUNT], so no
// settings file, autosave, run.json, issue.json or transcript can ever hold it.

const val DEFAULT_TRACKER_AUTH_HEADER = "Authorization"
const val TRACKER_TOKEN_ACCOUNT = "tracker.default"
const val MIN_CONFIRMATION_TIMEOUT_MINUTES = 1
const val MAX_CONFIRMATION_TIMEOUT_MINUTES = 120
const val MAX_TRACKER_PROMPT_CHARS = 8_000
const val MAX_TRACKER_TEXT_CHARS = 200

/**
 * The issue tracker: a remote MCP server reached over HTTP with one auth header, and an AI profile that files the issue by
 * calling the tracker's tools as [prompt] describes. A blank [authHeaderName] means the server needs no authentication.
 * With [bearerPrefix] the header value is `Bearer <token>`, otherwise the token itself.
 */
data class TrackerSettings(
    val enabled: Boolean = false,
    val name: String = "",
    val mcpUrl: String = "",
    val authHeaderName: String = DEFAULT_TRACKER_AUTH_HEADER,
    val bearerPrefix: Boolean = true,
    val prompt: String = "",
    val agentProfileId: String? = null,
) {
    /** What the issue dialog and Settings call the tracker. */
    val displayName: String get() = name.trim().ifEmpty { "Issue tracker" }

    /** The tracker needs a token (an auth header is configured). */
    val needsToken: Boolean get() = authHeaderName.isNotBlank()
}

/** What a new test run starts from; the run dialog applies it and the user can still change every part there. */
data class TestingSettings(
    val defaultJudgeProfileId: String? = null,
    /** A [JudgeMode] wire name; text so a value written by a newer build still loads (and reads as OFF). */
    val defaultJudgeMode: String = JudgeMode.OFF.wire,
    val evidence: EvidenceFlags = EvidenceFlags(),
    val confirmationTimeoutMinutes: Int = (DEFAULT_CONFIRMATION_TIMEOUT_MS / MILLIS_PER_MINUTE).toInt(),
) {
    val judgeMode: JudgeMode get() = JudgeMode.parse(defaultJudgeMode) ?: JudgeMode.OFF

    val confirmationTimeoutMs: Long
        get() = confirmationTimeoutMinutes.coerceIn(MIN_CONFIRMATION_TIMEOUT_MINUTES, MAX_CONFIRMATION_TIMEOUT_MINUTES) * MILLIS_PER_MINUTE
}

private const val MILLIS_PER_MINUTE = 60_000L
