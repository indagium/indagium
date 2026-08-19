package com.indagium.diagram3

// ── Message-label helpers shared by Seq3Layout and Seq3Emitters ────────────────────────────────
//
// `occurrenceLabel` and the collapsed-row (`COLLAPSE_ABOVE`, above threshold) summary used to be
// two independent, character-for-character-identical copies — one in Seq3Layout (canvas/PNG row
// geometry), one in Seq3Emitters (Mermaid/PlantUML text). Round 1 had exactly this class of bug
// with arrow styles (two copies quietly drifting apart), so this round keeps both in one place.

/** Substitutes one [occurrence]'s captured values into [message]'s `{name}` template — the label
 *  drawn for a SINGLE occurrence's arrow. Never used for a collapsed/multi-occurrence row, which
 *  keeps the raw template or uses [collapsedRepeatLabel]'s summary instead — a collapsed row has
 *  no one occurrence to substitute from. */
internal fun occurrenceLabel(message: Seq3Message, occurrence: Seq3Occurrence): String {
    if (message.match.captures.isEmpty()) return message.labelTemplate
    var label = message.labelTemplate
    message.match.captures.forEach { capture ->
        val value = occurrence.captureValues[capture.name] ?: return@forEach
        label = label.replace("{${capture.name}}", value)
    }
    return label
}

/** Above this many distinct substituted values, a collapsed row falls back to the raw `{name}`
 *  template rather than trying to summarize — a collapsed row genuinely stands for many different
 *  values at that point, and a summary that grew unboundedly long would defeat collapsing at all. */
private const val COLLAPSED_SUMMARY_MAX_DISTINCT = 3

/**
 * The label a collapsed (`COLLAPSE_ABOVE`, count above [Seq3Message.repeatThreshold]) row shows.
 * A raw `{name}`-templated label is technically correct there — the row stands for many occurrences
 * — but is indistinguishable from a bug to a user (a capture-bearing message drawing a literal
 * `{onScreenChanged}` with no value in sight). When [occurrences] resolve to
 * [COLLAPSED_SUMMARY_MAX_DISTINCT] or fewer distinct substituted labels, show a compact `A|B|C`
 * summary instead; above that, keep the honest "this stands for many different values" `{name}`.
 */
internal fun collapsedRepeatLabel(message: Seq3Message, occurrences: List<Seq3Occurrence>): String {
    val distinctLabels = occurrences.map { occurrenceLabel(message, it) }.distinct()
    return if (distinctLabels.size in 1..COLLAPSED_SUMMARY_MAX_DISTINCT) {
        distinctLabels.joinToString("|")
    } else {
        message.labelTemplate
    }
}

// ── WP10: inline timestamp / sequence-number prefix ─────────────────────────────────────────
//
// Same drift hazard this file's header describes for occurrenceLabel/collapsedRepeatLabel: a call's
// `[#n] [ts]` prefix is composed once here and reused by Seq3Layout (canvas + PNG raster, which
// draws exactly the label Seq3Layout measured and built into the row geometry — see
// Seq3Raster.kt's own header on why it never re-measures) and by Seq3Emitters (Mermaid/PlantUML
// text). A caller in either file supplies its OWN already-resolved sequence number (or null when
// this row isn't itself numbered — see each file's own "which emissions get numbered" doc) and its
// own timestamp fields; this function's only job is turning those into the same literal string
// everywhere, so the three renderers can never quietly disagree about the format.

/** [message]'s effective timestamp for one emitted row: an author-supplied override wins over the
 *  row's own occurrence, exactly like [Seq3Message.primaryTimestampMillis] but per-EMISSION rather
 *  than always the first occurrence — a repeated call's 3rd drawn arrow must show the 3rd
 *  occurrence's own time, not the message's first. */
internal fun seq3EmissionTimestamp(message: Seq3Message, occurrenceTimestampMillis: Long?): Long? =
    message.manualTimestampMillis ?: occurrenceTimestampMillis

/** Display-text counterpart of [seq3EmissionTimestamp] — see that function's own doc. */
internal fun seq3EmissionRawTimestamp(message: Seq3Message, occurrenceRawTimestamp: String): String =
    message.manualRawTimestamp.ifBlank { occurrenceRawTimestamp }

/** What a renderer actually shows for one row's timestamp: the real logged text when there is one
 *  (preserves whatever precision/format the source log itself used), else [timestampMillis]
 *  formatted with the same `HH:MM:SS.mmm` convention `utils.parseMillisOfDay` parses (see that
 *  function's own doc — this is its inverse, kept as a tiny local formatter rather than importing
 *  `utils`'s own `DateTimeFormatter`-based one, which is `private` and lives in a file that imports
 *  `com.indagium.model`, off-limits to this package). Null only when neither is available — a
 *  brief/RAW row with no parseable `ts` and no manual override. */
internal fun seq3DisplayTimestamp(rawTimestamp: String, timestampMillis: Long?): String? {
    val trimmed = rawTimestamp.trim()
    if (trimmed.isNotEmpty()) return trimmed
    return timestampMillis?.let(::seq3FormatMillisOfDay)
}

private const val MILLIS_PER_SECOND = 1_000L
private const val MILLIS_PER_MINUTE = 60_000L
private const val MILLIS_PER_HOUR = 3_600_000L

private fun seq3FormatMillisOfDay(millis: Long): String {
    val clamped = millis.coerceAtLeast(0L)
    val hh = clamped / MILLIS_PER_HOUR
    val mm = (clamped % MILLIS_PER_HOUR) / MILLIS_PER_MINUTE
    val ss = (clamped % MILLIS_PER_MINUTE) / MILLIS_PER_SECOND
    val ms = clamped % MILLIS_PER_SECOND
    return "%02d:%02d:%02d.%03d".format(hh, mm, ss, ms)
}

/**
 * Composes the displayed label as `[#n] [ts] label` — the ONE place this string is built (see this
 * section's own header). [sequenceNumber] is this row's own already-assigned call number (1-based,
 * counting only numbered rows — Arrow/Self/Stub in Seq3Layout, Arrow/NeedsTarget in Seq3Emitters —
 * in canvas/emission order; never assigned to a Note or an elision marker, which aren't calls); pass
 * null when [showSequenceNumbers] is off or this row isn't numbered. Returns [label] byte-identical
 * when both toggles are off or neither prefix has anything to show, so a caller never needs its own
 * "did anything change" branch.
 */
internal fun seq3PrefixedLabel(
    label: String,
    sequenceNumber: Int?,
    rawTimestamp: String,
    timestampMillis: Long?,
    showSequenceNumbers: Boolean,
    showTimestamps: Boolean,
): String {
    val tags = buildList {
        if (showSequenceNumbers && sequenceNumber != null) add("#$sequenceNumber")
        if (showTimestamps) seq3DisplayTimestamp(rawTimestamp, timestampMillis)?.let(::add)
    }
    if (tags.isEmpty()) return label
    return tags.joinToString(separator = "") { "[$it] " } + label
}

// ── Chronological emission order shared by canvas layout and text export ───────────────────────
//
// [Seq3Layout] draws every row at its TRUE chronological position (item 5 of the phase-5 post-ship
// plan) rather than in `Seq3Document.messages`' own list order — a repeated queue reorder
// (`Seq3Command.MoveMessage`) or an authored message inserted with a `manualTimestampMillis` that
// disagrees with its list position both leave the list order stale. `Seq3Emitters.planEmissions`
// used to never re-sort at all: it walked `document.messages` in plain list order, so the exported
// Mermaid/PlantUML text could draw a DIFFERENT row order — and therefore a different `[#n]` call
// number — for the exact same document the canvas just showed. [seq3ChronologicalFallbacks] (the
// untimestamped-message interpolation) and [seq3ChronologicalOrder] (the sort itself) are now the
// ONE place this happens; both Seq3Layout.kt and Seq3Emitters.kt call through here instead of
// keeping their own copy — the same anti-drift reasoning as `occurrenceLabel` above, applied to
// row ORDER instead of row TEXT.

/**
 * For every message in [document] with no timestamp of its own, interpolates a stable fallback
 * timestamp strictly between its nearest timestamped neighbours in LIST order (halfway when both
 * neighbours exist, one millisecond off the single neighbour that does) — this is what lets an
 * untimestamped AUTHORED message (inserted at `Start`/`AtIndex`/`BeforeMessage`/`AfterMessage`)
 * still draw at the queue position it was inserted at, rather than collapsing to "no timestamp,
 * sorts last" alongside every other untimestamped row. A message with a real
 * `primaryTimestampMillis` never appears in the returned map; a caller falls back to
 * `Long.MAX_VALUE` (sorts last) when a message has neither a real timestamp nor a fallback here
 * (every message in the document is untimestamped, so there is nothing to interpolate between).
 */
internal fun seq3ChronologicalFallbacks(document: Seq3Document): Map<String, Long> {
    val primaryTimestamps = document.messages.map { it.primaryTimestampMillis }
    return document.messages.mapIndexedNotNull { index, message ->
        if (message.primaryTimestampMillis != null) return@mapIndexedNotNull null
        val previous = (index - 1 downTo 0).firstNotNullOfOrNull { primaryTimestamps[it] }
        val next = (index + 1 until primaryTimestamps.size).firstNotNullOfOrNull { primaryTimestamps[it] }
        val fallback = when {
            previous != null && next != null && previous < next -> previous + ((next - previous) / 2).coerceAtLeast(1L)
            previous != null -> previous + 1L
            next != null -> next - 1L
            else -> null
        }
        fallback?.let { message.id to it }
    }.toMap()
}

/**
 * Sorts [items] into the SAME chronological row order [Seq3Layout] and [Seq3Emitters] must both
 * draw: real timestamp (or [seq3ChronologicalFallbacks]'s interpolated stand-in for an
 * untimestamped authored message) first; then the owning message's position in
 * `document.messages` (a stable tiebreak between two DIFFERENT messages that land on the exact
 * same instant); then [entryIdOf] (a stable tiebreak between several emissions of the SAME
 * message — e.g. every occurrence of an [Seq3Repeat.EVERY] call, which share one timestamp
 * fallback but must still draw in evidence order).
 *
 * Generic over [T] because [Seq3Layout]'s private `Emission` and [Seq3Emitters]'s private
 * `Seq3Emission` are two independent sealed hierarchies (each file's own header explains why they
 * are not unified into one shared shape) that nonetheless both need this EXACT comparator; this
 * function is the one place it is written down, so the two can never again quietly diverge on
 * ORDER the way they already had on the untimestamped-fallback math itself.
 */
internal fun <T> seq3ChronologicalOrder(
    document: Seq3Document,
    items: List<T>,
    messageIdOf: (T) -> String,
    timestampMillisOf: (T) -> Long?,
    entryIdOf: (T) -> Int?,
): List<T> {
    val messageOrder = document.messages.withIndex().associate { (index, message) -> message.id to index }
    val fallbacks = seq3ChronologicalFallbacks(document)
    return items.sortedWith(
        compareBy<T> { item -> timestampMillisOf(item) ?: fallbacks[messageIdOf(item)] ?: Long.MAX_VALUE }
            .thenBy { item -> messageOrder[messageIdOf(item)] ?: Int.MAX_VALUE }
            .thenBy { item -> entryIdOf(item) ?: Int.MAX_VALUE },
    )
}
