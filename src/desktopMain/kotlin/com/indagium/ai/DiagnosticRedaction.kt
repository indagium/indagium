package com.indagium.ai

// Secret redaction shared by every place that keeps text from a child process or a model transcript (the Codex
// stderr tail, the AI test-run transcript). A bearer token or a `token=...` style assignment is replaced by a marker.

private val BEARER_REGEX = Regex("(?i)(bearer\\s+)[^\\s,;]+")
private val SECRET_ASSIGNMENT_REGEX = Regex("(?i)((?:api[_-]?key|token|secret|password|authorization)\\s*[=:]\\s*)[^\\s,;]+")
private const val REDACTED_REPLACEMENT = "$1[REDACTED]"

/** [text] with bearer tokens and `key=value` secrets masked. Line breaks are preserved. */
internal fun redactDiagnosticSecrets(text: String): String =
    text.replace(BEARER_REGEX, REDACTED_REPLACEMENT).replace(SECRET_ASSIGNMENT_REGEX, REDACTED_REPLACEMENT)
