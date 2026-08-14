package com.indagium.diagram3

import com.indagium.model.LogEntry
import com.indagium.utils.deltaMillis
import kotlin.math.abs

// ── Correlation evidence ─────────────────────────────────────────────────────────────────────
//
// extractCorrelationTokens/sharedCorrelationToken below are a near-verbatim port of
// `diagram/CorrelationToken.kt` (68 lines) — that file is already dependency-free (no LogEntry,
// no diagram state) and correct, so it is copied rather than redesigned, per this phase's brief.
// isThreadHandoff/hasSharedCorrelationToken are adapted from
// `diagram/SeqDiagramBuilder.kt:1282-1334`'s two identically-named private functions: the
// algorithm is unchanged, only the calling convention differs — the original took
// (idx, entry, prevIdx, prevEntry) into a shared index space kept by the builder's own scan;
// this package instead deals directly in the two `LogEntry` values a caller already has adjacent
// to each other, which is all the algorithm ever actually used `idx`/`prevIdx` for (a null-vs-
// distinct guard the caller can equally enforce by only ever calling this with two different rows).

/** Extracts only high-confidence identifiers useful for adjacent-log correlation. Deliberately
 *  has no knowledge of [LogEntry] or diagram state: parsing remains a pure, bounded operation over
 *  one message at a time. Callers decide whether two adjacent messages may correlate (including
 *  the timestamp and lifeline guards — see [hasSharedCorrelationToken]). */
fun extractCorrelationTokens(message: String): List<String> {
    if (message.isBlank()) return emptyList()
    val values = linkedSetOf<String>()
    UUID_TOKEN_RE.findAll(message).forEach { match ->
        if (!isGenericIdentifierContext(message, match.range.first)) {
            normalizeUnquotedToken(match.value)?.let(values::add)
        }
    }
    COMPACT_HEX_TOKEN_RE.findAll(message).forEach { match ->
        if (!GENERIC_HEX_CONTEXT_RE.containsMatchIn(message.substring(0, match.range.first).takeLast(CONTEXT_LOOKBACK_CHARS))) {
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

private const val CONTEXT_LOOKBACK_CHARS = 32
private const val MIN_NAMED_TOKEN_LENGTH = 8

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
    GENERIC_HEX_CONTEXT_RE.containsMatchIn(message.substring(0, tokenStart).takeLast(CONTEXT_LOOKBACK_CHARS))

private fun normalizeNamedToken(raw: String): String? {
    val normalized = normalizeUnquotedToken(raw) ?: return null
    if (normalized.length < MIN_NAMED_TOKEN_LENGTH || normalized.all(Char::isDigit) || normalized in GENERIC_CORRELATION_VALUES) return null
    return normalized
}

private fun normalizeUnquotedToken(raw: String): String? = normalizeCorrelationToken(raw)
    .takeIf { it.isNotEmpty() && !it.all(Char::isDigit) }

private fun normalizeCorrelationToken(raw: String): String = raw.trim().lowercase()

// ── Adjacent-entry evidence (thread handoff / shared token) ────────────────────────────────────

/** Two thread-pool tasks reusing a recycled tid minutes or hours apart are not a call — this
 *  bounds both evidence checks below to a gap short enough that it can only plausibly be one
 *  synchronous call chain still running on the same OS thread (or, for the token check, the same
 *  short-lived request). Mirrors `diagram.THREAD_HANDOFF_MAX_GAP_MS` exactly; not user-tunable —
 *  see that constant's own doc for why. */
const val SEQ3_CORRELATION_MAX_GAP_MS = 250L

/**
 * Same-thread handoff: a real (non-zero) pid+tid match between [previous] and [current] within a
 * short time bound is actual OS-level evidence that [current] continues [previous]'s call chain.
 * `tid != 0`/`pid != 0` is non-negotiable: `LogEntry.pid`/`tid` both default to 0, so a brief/RAW
 * logcat (which carries neither) would otherwise correlate an entire log into one fake thread.
 */
fun isThreadHandoff(current: LogEntry, previous: LogEntry?): Boolean =
    previous != null &&
        current.pid != 0 && current.tid != 0 && previous.pid != 0 && previous.tid != 0 &&
        current.tid == previous.tid && current.pid == previous.pid &&
        deltaMillis(previous.ts, current.ts)?.let { abs(it) <= SEQ3_CORRELATION_MAX_GAP_MS } == true

/**
 * A shared correlation token is weaker than an OS-thread handoff and is intentionally limited to
 * the immediately preceding entry. Parsed timestamps are mandatory: a brief/RAW row must never
 * make a merely-repeated identifier look like a causal edge.
 */
fun hasSharedCorrelationToken(current: LogEntry, previous: LogEntry?): Boolean =
    previous != null &&
        deltaMillis(previous.ts, current.ts)?.let { abs(it) <= SEQ3_CORRELATION_MAX_GAP_MS } == true &&
        sharedCorrelationToken(previous.msg, current.msg) != null
