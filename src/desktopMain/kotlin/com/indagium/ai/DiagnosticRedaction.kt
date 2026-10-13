package com.indagium.ai

// Secret redaction shared by every place that keeps text from a child process or a model transcript (the Codex
// stderr tail, the AI test-run transcript). Bearer tokens, assignment values and JSON credential fields are replaced by
// markers.

private val BEARER_REGEX = Regex("""(?i)(bearer\s+)(?:"(?:\\.|[^"\\])*"|'(?:\\.|[^'\\])*'|[^\s,;]+)""")
private val JSON_SECRET_VALUE_REGEX = Regex(
    // Accept a preceding backslash before quotes too: malformed historical JSON can end inside
    // an outer string while still containing an escaped nested JSON credential. In that case
    // consume through the enclosing unescaped quote (or end of input) so no suffix of the value
    // survives redaction.
    """(?i)((?:\\?["']?)(?:api[_-]?key|token|secret|password|authorization)(?:\\?["']?)\s*:\s*\\?")(?:(?:\\.)|[^"\\])*(\\?")?""",
)
private val SECRET_ASSIGNMENT_REGEX = Regex(
    """(?i)((?:api[_-]?key|token|secret|password|authorization)\s*[=:]\s*)(?:((?:"(?:\\.|[^"\\])*"))|('(?:\\.|[^'\\])*')|([^\s,;"']+))""",
)
private const val REDACTED_REPLACEMENT = "$1[REDACTED]"

/** [text] with bearer tokens and `key=value` secrets masked. Line breaks are preserved. */
internal fun redactDiagnosticSecrets(text: String): String =
    text.replace(JSON_SECRET_VALUE_REGEX, "$1[REDACTED]$2")
        .replace(BEARER_REGEX, REDACTED_REPLACEMENT)
        .replace(SECRET_ASSIGNMENT_REGEX) { match ->
            match.groupValues[1] + when {
                match.groupValues[2].isNotEmpty() -> "\"[REDACTED]\""
                match.groupValues[3].isNotEmpty() -> "'[REDACTED]'"
                else -> "[REDACTED]"
            }
        }
