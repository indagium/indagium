package com.indagium.diagram

/**
 * Extracts only high-confidence identifiers that are useful for adjacent-log correlation.
 *
 * This helper deliberately has no knowledge of [com.indagium.model.LogEntry] or diagram state:
 * parsing remains a pure, bounded operation over one message at a time. Callers decide whether
 * two adjacent messages may correlate (including the timestamp and lifeline guards).
 */
fun extractCorrelationTokens(message: String): List<String> {
    if (message.isBlank()) return emptyList()
    val values = linkedSetOf<String>()
    UUID_TOKEN_RE.findAll(message).forEach { match ->
        if (!isGenericIdentifierContext(message, match.range.first)) {
            normalizeUnquotedToken(match.value)?.let(values::add)
        }
    }
    COMPACT_HEX_TOKEN_RE.findAll(message).forEach { match ->
        if (!GENERIC_HEX_CONTEXT_RE.containsMatchIn(message.substring(0, match.range.first).takeLast(32))) {
            normalizeUnquotedToken(match.value)?.let(values::add)
        }
    }
    NAMED_TOKEN_RE.findAll(message).forEach { match ->
        val value = match.groupValues.drop(1).firstOrNull(String::isNotBlank).orEmpty()
        normalizeNamedToken(value)?.let(values::add)
    }
    return values.toList()
}

/** Returns the deterministic strongest shared token for two adjacent messages, if any. */
fun sharedCorrelationToken(previousMessage: String, currentMessage: String): String? {
    val previous = extractCorrelationTokens(previousMessage).toSet()
    return extractCorrelationTokens(currentMessage)
        .asSequence()
        .filter(previous::contains)
        .sortedWith(compareByDescending<String> { it.length }.thenBy { it })
        .firstOrNull()
}

private val UUID_TOKEN_RE = Regex(
    """(?i)\b[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}\b""",
)
private val COMPACT_HEX_TOKEN_RE = Regex("""(?i)\b[0-9a-f]{16,}\b""")
private val GENERIC_HEX_CONTEXT_RE = Regex("""(?i)\b(?:id|status|state|code|result|error)\s*[:=]\s*["']?$""")
private val NAMED_TOKEN_RE = Regex(
    """(?i)\b(?:requestId|request_id|sessionId|session_id|traceId|trace_id|correlationId|correlation_id|spanId)""" +
        """\s*[:=]\s*(?:"([^"]+)"|'([^']+)'|([A-Za-z0-9][A-Za-z0-9._:/-]*))""",
)

private val GENERIC_CORRELATION_VALUES = setOf(
    "request", "response", "session", "trace", "span", "correlation", "status", "state",
    "success", "error", "failure", "failed", "ok", "unknown", "none", "null", "true", "false",
    "started", "starting", "complete", "completed", "pending", "cancelled", "canceled",
)

private fun isGenericIdentifierContext(message: String, tokenStart: Int): Boolean =
    GENERIC_HEX_CONTEXT_RE.containsMatchIn(message.substring(0, tokenStart).takeLast(32))

private fun normalizeNamedToken(raw: String): String? {
    val normalized = normalizeUnquotedToken(raw) ?: return null
    if (normalized.length < 8 || normalized.all(Char::isDigit) || normalized in GENERIC_CORRELATION_VALUES) return null
    return normalized
}

private fun normalizeUnquotedToken(raw: String): String? = normalizeCorrelationToken(raw)
    .takeIf { it.isNotEmpty() && !it.all(Char::isDigit) }

private fun normalizeCorrelationToken(raw: String): String = raw.trim().lowercase()
