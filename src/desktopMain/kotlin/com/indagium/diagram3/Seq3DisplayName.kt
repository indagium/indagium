package com.indagium.diagram3

// ── Lifeline display-name resolution and wrapping ───────────────────────────────────────────
//
// A standalone file rather than a home inside Seq3Layout.kt or Seq3Queue.kt: [seq3DisplayName] is
// read by THREE independent callers that share no other code — Seq3Layout (header geometry, WP2),
// ui.Seq3QueuePanel (the lifeline row's name label and its "Diagram default / Full name / Last
// segment / ..." dropdown), and ui.Seq3GuidedPass (the "Fix these" card, item 2's second half) —
// so it belongs to none of their existing files more than the others. [seq3WrapDisplayName] rides
// along in the same file because the two are always called together (resolve, then wrap) and
// share [Seq3FontRole.LIFELINE] as their only measurement role.
//
// [seq3WrapDisplayName] is DELIBERATELY not a reuse of Seq3Layout's private `wrapLines`
// (`Seq3Layout.kt:552`): that function breaks on WHITESPACE for message labels/notes, which would
// never break `com.mycompany.myapp.Example1` at all (it has no spaces) — it would just overflow or
// get hard-split with no regard for the dots that make a dotted name actually readable when
// wrapped. This file's algorithm breaks AFTER `.` first and only falls back to a mid-token hard
// split for the one case dot-breaking cannot help: a single dotless run still wider than the
// available width.

private const val SEQ3_DISPLAY_NAME_MAX_LINES = 3
private const val SEQ3_DISPLAY_NAME_ELLIPSIS = "…"

/**
 * Resolves [name] against a per-lifeline [segments] override (null = inherit [documentDefault])
 * and keeps the last N dot-separated segments — 0 (the shared meaning of both the override and the
 * document default) means the full name, unshortened.
 *
 * Never throws on degenerate input (this package's standing posture — see Seq3Model.kt's header):
 * a dotless [name] simply has one "segment" and is returned as-is for any N >= 1; an empty [name]
 * likewise round-trips as `""`; a trailing dot produces a trailing empty segment like any other
 * split, which callers see reflected in the (possibly blank) tail of the result rather than a
 * thrown exception — see [Seq3DisplayNameTest] for the exact pinned behaviour of that edge case.
 */
fun seq3DisplayName(name: String, segments: Int?, documentDefault: Int): String {
    val resolved = (segments ?: documentDefault).coerceAtLeast(0)
    if (resolved == 0) return name
    val parts = name.split('.')
    if (resolved >= parts.size) return name
    return parts.takeLast(resolved).joinToString(".")
}

/** One run of [displayName] up to and including its next `.`, or the trailing dotless remainder.
 *  `"com.mycompany.myapp.Example1"` -> `["com.", "mycompany.", "myapp.", "Example1"]`. Keeping the
 *  `.` attached to the PRECEDING chunk (not a separate token) is what makes the greedy packer in
 *  [seq3WrapDisplayName] break "after" a dot rather than before one. Empty input yields an empty
 *  list (not `[""]`) so the wrapper's own empty-input handling stays in one place. */
private fun seq3DotChunks(text: String): List<String> {
    if (text.isEmpty()) return emptyList()
    val chunks = mutableListOf<String>()
    var start = 0
    for (i in text.indices) {
        if (text[i] == '.') {
            chunks += text.substring(start, i + 1)
            start = i + 1
        }
    }
    if (start < text.length) chunks += text.substring(start)
    return chunks
}

/** Marks [text] as truncated by trimming it (if needed) and appending an ellipsis — used ONLY on
 *  the definite-overflow path in [seq3WrapDisplayName], where [text] is always an already-flushed
 *  line and therefore ALREADY fits [maxWidth] on its own (that's the flush condition). An
 *  early-return "already fits, nothing to do" guard would make this a no-op every single time it's
 *  called and silently drop the truncation signal — so unlike a typical ellipsize helper, this one
 *  unconditionally makes room for and appends the ellipsis. */
private fun seq3EllipsizeToWidth(text: String, tm: Seq3TextMetrics, maxWidth: Double): String {
    var end = text.length
    while (end > 0 && tm.width(Seq3FontRole.LIFELINE, text.substring(0, end) + SEQ3_DISPLAY_NAME_ELLIPSIS) > maxWidth) end--
    return if (end <= 0) SEQ3_DISPLAY_NAME_ELLIPSIS else text.substring(0, end) + SEQ3_DISPLAY_NAME_ELLIPSIS
}

/**
 * Wraps an already-resolved [displayName] (the output of [seq3DisplayName]) to fit [maxWidth],
 * breaking after `.` characters first. `com.mycompany.myapp.Example1` therefore wraps to
 * `com.mycompany.` / `myapp.Example1` rather than a mid-word split — see this file's header for
 * why this can't reuse Seq3Layout's whitespace-oriented `wrapLines`. Falls back to a hard
 * character split ONLY for a single dotless chunk that still overflows [maxWidth] on its own (e.g.
 * one very long segment with no dots at all). Capped at [SEQ3_DISPLAY_NAME_MAX_LINES] lines; any
 * text still pending once that cap is hit is signalled by ellipsizing the last completed line
 * rather than silently dropping it.
 */
fun seq3WrapDisplayName(displayName: String, maxWidth: Double, tm: Seq3TextMetrics): List<String> {
    val safeWidth = if (maxWidth.isFinite() && maxWidth > 0) maxWidth else 1.0

    fun widthOf(s: String) = tm.width(Seq3FontRole.LIFELINE, s)

    val chunks = ArrayDeque(seq3DotChunks(displayName))
    val lines = mutableListOf<String>()
    var current = ""

    while (chunks.isNotEmpty()) {
        if (lines.size >= SEQ3_DISPLAY_NAME_MAX_LINES) break
        val chunk = chunks.first()
        val candidate = current + chunk
        when {
            widthOf(candidate) <= safeWidth -> {
                current = candidate
                chunks.removeFirst()
            }
            current.isNotEmpty() -> {
                // The next chunk doesn't fit alongside what's already on this line — start a new
                // one and retry the SAME chunk there (it may fit alone even if not alongside `current`).
                lines += current
                current = ""
            }
            else -> {
                // `current` is empty and even this one dot-chunk alone overflows `maxWidth`: the
                // one case this function hard-splits mid-token (see this function's own doc).
                var end = chunk.length
                while (end > 1 && widthOf(chunk.substring(0, end)) > safeWidth) end--
                current = chunk.substring(0, end)
                chunks.removeFirst()
                if (end < chunk.length) chunks.addFirst(chunk.substring(end))
            }
        }
    }

    // Reaching here with `chunks` still non-empty only happens via the `break` above, which is
    // only reached right after a flush (`current = ""`) — see the trace in this file's own tests.
    // So: chunks empty -> `current` (if any) is simply the last, still-unflushed line, nothing
    // overflowed. chunks non-empty -> genuine overflow; the cap was hit and `current` is always
    // empty, so the LAST COMPLETED line is what gets the ellipsis.
    return if (chunks.isEmpty()) {
        (if (current.isNotEmpty()) lines + current else lines).ifEmpty { listOf("") }
    } else {
        val capped = lines.ifEmpty { listOf("") }.toMutableList()
        capped[capped.lastIndex] = seq3EllipsizeToWidth(capped.last(), tm, safeWidth)
        capped
    }
}
