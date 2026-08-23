package com.indagium.diagram3

// ── Mermaid / PlantUML text emitters ────────────────────────────────────────────────────────
//
// Ported from `diagram/DiagramEmitters.kt` (282 lines): alias sanitization/dedup and per-dialect
// text escaping are copied essentially verbatim (they were already correct — see that file's own
// header for exactly why each escape exists). What's new for v3, because the model itself is new:
//   - one [Seq3Message] can draw as ONE arrow (collapsed, badged ×n) or MANY (every occurrence, or
//     first+last with an elision marker) depending on [Seq3Message.repeat] — see [expandMessage];
//   - [Seq3Kind.NOTE] messages render as a note, never an arrow;
//   - a null [Seq3Message.toLifelineId] renders as a dashed "needs target" stub instead of being
//     silently dropped — see the design spec's §04 "Unresolved messages draw as a dashed amber
//     stub ... never as nothing" (text dialects have no dashed-line primitive worth the trouble,
//     so both emit the same "Note ... needs target" convention `diagram.DiagramEmitters` used for
//     its own `DiagramMessage.targetless`);
//   - [Seq3Fragment]s are semantic (a user explicitly picked loop/alt/opt/par), so both dialects
//     emit the dialect's REAL nested block for it — unlike `diagram.DiagramFrame`, which was a
//     colorless auto-detected bracket and deliberately avoided Mermaid's semantic block syntax
//     (see that file's header comment). Nesting still needs the same clamp-to-parent normalization
//     `diagram.DiagramEmitters`' `normalizeFramesForNesting` used for PlantUML, generalized here to
//     both dialects since both now open real nested blocks.

private val NON_IDENTIFIER_CHAR = Regex("[^A-Za-z0-9_]")
private const val ALIAS_SUFFIX_START = 2

// Sanitizes every lifeline's id into a valid `[A-Za-z0-9_]` alias (both dialects accept the same
// charset) and dedupes with a numeric suffix — done once over the whole list so two lifelines that
// only differ in punctuation can never collide silently into the same alias.
private fun sanitizedAliases(lifelines: List<Seq3Lifeline>): List<String> {
    val used = HashSet<String>()
    return lifelines.map { lifeline ->
        val seed = lifeline.id.ifBlank { lifeline.name }
        var base = seed.replace(NON_IDENTIFIER_CHAR, "_")
        if (base.isEmpty() || base[0].isDigit()) base = "p$base"
        var candidate = base
        var suffix = ALIAS_SUFFIX_START
        while (!used.add(candidate)) {
            candidate = "${base}_$suffix"
            suffix++
        }
        candidate
    }
}

// Single pass over the ORIGINAL text, never re-scanning generated output — see
// `diagram.DiagramEmitters.mermaidEscape`'s own doc for why order matters here.
private fun mermaidEscape(text: String): String {
    val normalized = text.replace("\r\n", "\n").replace('\r', '\n')
    val sb = StringBuilder(normalized.length)
    for (c in normalized) {
        when (c) {
            '\n' -> sb.append("<br/>")
            ';' -> sb.append("#59;")
            '#' -> sb.append("#35;")
            ':' -> sb.append("#58;")
            '<' -> sb.append("#60;")
            '>' -> sb.append("#62;")
            '"' -> sb.append("#34;")
            '`' -> sb.append("#96;")
            else -> sb.append(c)
        }
    }
    return sb.toString()
}

private fun plantUmlEscape(text: String): String {
    val normalized = text.replace("\r\n", "\n").replace('\r', '\n')
    val sb = StringBuilder(normalized.length)
    for (c in normalized) {
        when (c) {
            '\\' -> sb.append("\\\\")
            '"' -> sb.append("\\\"")
            '\n' -> sb.append("\\n")
            else -> sb.append(c)
        }
    }
    return sb.toString()
}

// '×' (U+00D7) isn't in either dialect's escaped charset, so it's appended AFTER escaping the
// label itself — this only ever wraps a repeat-collapse count, never user text.
private fun repeatSuffix(count: Int): String = if (count > 1) " ×$count" else ""

// ── Expand one message into what it actually draws ──────────────────────────────────────────

private sealed class Seq3Emission {
    abstract val messageId: String
    abstract val occurrenceEntryId: Int?

    /** Task 0 (WP11 prerequisite): the chronological value this emission carries — mirrors
     *  Seq3Layout.kt's own `Emission.timestampMillis`. Pulled onto the base class (every subtype
     *  now supplies one, even [NoteLine]/[Elided], which previously had none) purely so
     *  `planEmissions` can sort the WHOLE flat list through one `seq3ChronologicalOrder` call
     *  instead of a per-subtype `when`. */
    abstract val timestampMillis: Long?

    data class Arrow(
        override val messageId: String,
        val fromIdx: Int,
        val toIdx: Int,
        // Already `[#n] [ts] label` prefixed when the document has either WP10 toggle on — see
        // [prefixSeq3EmissionLabels]'s own doc for why that pass runs before any text is written.
        val label: String,
        val kind: Seq3Kind,
        val repeatCount: Int,
        override val occurrenceEntryId: Int? = null,
        val rawTimestamp: String = "",
        override val timestampMillis: Long? = null,
    ) : Seq3Emission()

    data class NeedsTarget(
        override val messageId: String,
        val fromIdx: Int,
        val label: String,
        override val occurrenceEntryId: Int? = null,
        val rawTimestamp: String = "",
        override val timestampMillis: Long? = null,
    ) : Seq3Emission()

    data class NoteLine(
        override val messageId: String,
        val participantIdx: Int,
        val text: String,
        override val occurrenceEntryId: Int? = null,
        override val timestampMillis: Long? = null,
    ) : Seq3Emission()

    /** [timestampMillis] seeded from the FIRST elided occurrence's own timestamp, exactly like
     *  Seq3Layout.kt's `Emission.Elision` — see that type's own doc: "a reasonable, defensible
     *  placement, not required to be exact", now load-bearing here too since this row must sort
     *  immediately after the arrow it was elided from. */
    data class Elided(
        override val messageId: String,
        val participantIdx: Int,
        val count: Int,
        override val timestampMillis: Long? = null,
    ) : Seq3Emission() {
        override val occurrenceEntryId: Int? get() = null
    }
}

// The collapsed/multi-occurrence label keeps `{name}` slots visible (the templated form); a
// single rendered arrow substitutes that occurrence's real captured values back in — the same
// distinction the design spec draws between a queue row's pattern and an individual occurrence.
private fun templatedLabel(message: Seq3Message): String = message.labelTemplate

// occurrenceLabel/collapsedRepeatLabel now live in Seq3LabelSummary.kt, shared with Seq3Layout —
// see that file's header on why (WP9: the two copies of occurrenceLabel had drifted apart once
// already, the same class of bug round 1 hit with arrow styles).

private fun expandMessage(message: Seq3Message, lifelineIndex: Map<String, Int>): List<Seq3Emission> {
    val fromIdx = lifelineIndex[message.fromLifelineId] ?: return emptyList()
    val occurrences = message.occurrences.filter { it.visibility == Seq3Visibility.VISIBLE }
    if (message.occurrences.isNotEmpty() && occurrences.isEmpty()) return emptyList()
    if (message.kind == Seq3Kind.NOTE) {
        return listOf(
            Seq3Emission.NoteLine(
                message.id,
                fromIdx,
                templatedLabel(message),
                occurrences.firstOrNull()?.entryId,
                message.primaryTimestampMillis,
            ),
        )
    }
    val toIdx = message.toLifelineId?.let(lifelineIndex::get)
    if (toIdx == null) {
        return listOf(
            Seq3Emission.NeedsTarget(
                message.id,
                fromIdx,
                templatedLabel(message),
                occurrences.firstOrNull()?.entryId,
                message.primaryRawTimestamp,
                message.primaryTimestampMillis,
            ),
        )
    }
    // Authored messages intentionally have no fabricated log occurrence. They still need one
    // drawable arrow in both source dialects, using the authored label and no evidence expansion.
    if (occurrences.isEmpty()) {
        return listOf(
            Seq3Emission.Arrow(
                message.id,
                fromIdx,
                toIdx,
                templatedLabel(message),
                message.kind,
                1,
                rawTimestamp = message.primaryRawTimestamp,
                timestampMillis = message.primaryTimestampMillis,
            ),
        )
    }
    return when (message.repeat) {
        Seq3Repeat.EVERY -> occurrences.map { occ ->
            Seq3Emission.Arrow(
                message.id,
                fromIdx,
                toIdx,
                occurrenceLabel(message, occ),
                message.kind,
                1,
                occ.entryId,
                seq3EmissionRawTimestamp(message, occ.rawTimestamp),
                seq3EmissionTimestamp(message, occ.timestampMillis),
            )
        }
        Seq3Repeat.FIRST_LAST -> firstAndLastEmissions(message, fromIdx, toIdx, occurrences)
        Seq3Repeat.COLLAPSE_ABOVE -> if (occurrences.size > message.repeatThreshold) {
            listOf(
                Seq3Emission.Arrow(
                    message.id,
                    fromIdx,
                    toIdx,
                    collapsedRepeatLabel(message, occurrences),
                    message.kind,
                    occurrences.size,
                    occurrences.first().entryId,
                    seq3EmissionRawTimestamp(message, occurrences.first().rawTimestamp),
                    seq3EmissionTimestamp(message, occurrences.first().timestampMillis),
                ),
            )
        } else {
            occurrences.map { occ ->
                Seq3Emission.Arrow(
                    message.id,
                    fromIdx,
                    toIdx,
                    occurrenceLabel(message, occ),
                    message.kind,
                    1,
                    occ.entryId,
                    seq3EmissionRawTimestamp(message, occ.rawTimestamp),
                    seq3EmissionTimestamp(message, occ.timestampMillis),
                )
            }
        }
    }
}

private fun firstAndLastEmissions(message: Seq3Message, fromIdx: Int, toIdx: Int, occurrences: List<Seq3Occurrence>): List<Seq3Emission> {
    if (occurrences.size <= 1) {
        val only = occurrences.first()
        return listOf(
            Seq3Emission.Arrow(
                message.id,
                fromIdx,
                toIdx,
                occurrenceLabel(message, only),
                message.kind,
                1,
                only.entryId,
                seq3EmissionRawTimestamp(message, only.rawTimestamp),
                seq3EmissionTimestamp(message, only.timestampMillis),
            ),
        )
    }
    val elided = occurrences.size - 2
    return buildList {
        val first = occurrences.first()
        add(
            Seq3Emission.Arrow(
                message.id,
                fromIdx,
                toIdx,
                occurrenceLabel(message, first),
                message.kind,
                1,
                first.entryId,
                seq3EmissionRawTimestamp(message, first.rawTimestamp),
                seq3EmissionTimestamp(message, first.timestampMillis),
            ),
        )
        if (elided > 0) {
            add(Seq3Emission.Elided(message.id, fromIdx, elided, seq3EmissionTimestamp(message, first.timestampMillis)))
        }
        val last = occurrences.last()
        add(
            Seq3Emission.Arrow(
                message.id,
                fromIdx,
                toIdx,
                occurrenceLabel(message, last),
                message.kind,
                1,
                last.entryId,
                seq3EmissionRawTimestamp(message, last.rawTimestamp),
                seq3EmissionTimestamp(message, last.timestampMillis),
            ),
        )
    }
}

// ── Flattened emission plan shared by both dialects ─────────────────────────────────────────

private class Seq3EmissionPlan(
    val lifelineIndex: Map<String, Int>,
    val emissions: List<Seq3Emission>,
    val firstIndexByMessage: Map<String, Int>,
    val lastIndexByMessage: Map<String, Int>,
    val indexByOccurrence: Map<Seq3OccurrenceRef, Int>,
)

private fun planEmissions(document: Seq3Document): Seq3EmissionPlan {
    // WP2: sorted by ordinal, exactly like Seq3Layout.kt's own `lifelinesSorted` — this file used
    // to iterate in plain document-list order while Seq3Layout sorted by ordinal, so an exported
    // participant order could already disagree with the canvas even before panel reorder (WP3)
    // existed. toMermaid/toPlantUml build their OWN `visibleLifelines` (for aliases/participant
    // lines) with this exact same filter+sort, so `lifelineIndex` here and `aliases` there always
    // agree index-for-index — see those functions' own comments.
    val visibleLifelines = document.lifelines.filter { it.visibility == Seq3Visibility.VISIBLE }.sortedBy { it.ordinal }
    val lifelineIndex = visibleLifelines.withIndex().associate { (i, l) -> l.id to i }
    val unordered = mutableListOf<Seq3Emission>()
    document.messages.forEach { message ->
        if (message.visibility == Seq3Visibility.HIDDEN) return@forEach
        unordered += expandMessage(message, lifelineIndex)
    }
    // Task 0 (round-2 corrections plan, WP11 prerequisite): this used to skip straight from
    // `unordered` to `firstIndex`/`lastIndex`/`indexByOccurrence` below, in plain
    // `document.messages` list order — see `seq3ChronologicalOrder`'s own doc (Seq3LabelSummary.kt)
    // for why that could show a different row order, and therefore a different `[#n]` call number,
    // than the canvas for the same document. Every index below is computed AFTER this sort, from
    // FINAL emitted positions, so fragment/note boundary lookups (`firstIndexByMessage` etc.) agree
    // with the row order that actually gets written out.
    val emissions = seq3ChronologicalOrder(
        document,
        unordered,
        messageIdOf = { emission -> emission.messageId },
        timestampMillisOf = { emission -> emission.timestampMillis },
        entryIdOf = { emission -> emission.occurrenceEntryId },
    )
    val firstIndex = HashMap<String, Int>()
    val lastIndex = HashMap<String, Int>()
    val indexByOccurrence = HashMap<Seq3OccurrenceRef, Int>()
    emissions.forEachIndexed { index, emission ->
        firstIndex.putIfAbsent(emission.messageId, index)
        lastIndex[emission.messageId] = index
        emission.occurrenceEntryId?.let { entryId ->
            indexByOccurrence[Seq3OccurrenceRef(emission.messageId, entryId)] = index
        }
    }
    val prefixed = prefixSeq3EmissionLabels(emissions, document.showSequenceNumbers, document.showTimestamps)
    return Seq3EmissionPlan(lifelineIndex, prefixed, firstIndex, lastIndex, indexByOccurrence)
}

// ── WP10 (item 7): inline call numbering / timestamps ───────────────────────────────────────
//
// Mirrors Seq3Layout.kt's own `prefixEmissionLabels` — see that function's own doc for the "hidden
// rows never consume a number, a collapsed row takes exactly one" rules, which apply identically
// here (a hidden message is already skipped above, and COLLAPSE_ABOVE above threshold is exactly
// one Seq3Emission.Arrow). A THIRD small copy of the "which emissions are numbered" split, not a
// shared call, for the same reason expandMessage/expandForLayout already are two copies (this
// file's own header: Seq3Emitters is phase-1, not to be restructured around Seq3Layout's shape) —
// what both copies MUST share, and do, is [seq3PrefixedLabel] itself, so the literal prefix string
// can never drift between canvas/PNG and text. Only [Seq3Emission.Arrow] (covers CALL/RETURN/
// ASYNC/SELF — a self-call still expands to an Arrow here, see expandMessage) and [Seq3Emission
// .NeedsTarget] (the unresolved-target stub) are numbered; [NoteLine]/[Elided] are not calls.
//
// Numbering walks [emissions] in CHRONOLOGICAL order (Task 0, round-2 corrections plan): `plan
// Emissions` now sorts through the same `seq3ChronologicalOrder` Seq3Layout.kt's canvas geometry
// does, so a `[#n]` written here always matches the number the same row shows on screen — the
// "canvas draws in real time order, text follows the durable queue order" split this comment used
// to document is gone; a manually reordered queue can no longer show two different numbers for the
// same message.
private fun prefixSeq3EmissionLabels(emissions: List<Seq3Emission>, showSequenceNumbers: Boolean, showTimestamps: Boolean): List<Seq3Emission> {
    if (!showSequenceNumbers && !showTimestamps) return emissions
    var callNumber = 0
    return emissions.map { emission ->
        when (emission) {
            is Seq3Emission.Arrow -> {
                callNumber++
                emission.copy(
                    label = seq3PrefixedLabel(
                        emission.label,
                        callNumber,
                        emission.rawTimestamp,
                        emission.timestampMillis,
                        showSequenceNumbers,
                        showTimestamps,
                    ),
                )
            }
            is Seq3Emission.NeedsTarget -> {
                callNumber++
                emission.copy(
                    label = seq3PrefixedLabel(
                        emission.label,
                        callNumber,
                        emission.rawTimestamp,
                        emission.timestampMillis,
                        showSequenceNumbers,
                        showTimestamps,
                    ),
                )
            }
            is Seq3Emission.NoteLine, is Seq3Emission.Elided -> emission
        }
    }
}

private fun emissionParticipants(emission: Seq3Emission): List<Int> = when (emission) {
    is Seq3Emission.Arrow -> listOf(emission.fromIdx, emission.toIdx)
    is Seq3Emission.NeedsTarget -> listOf(emission.fromIdx)
    is Seq3Emission.NoteLine -> listOf(emission.participantIdx)
    is Seq3Emission.Elided -> listOf(emission.participantIdx)
}

// ── Fragment nesting ─────────────────────────────────────────────────────────────────────────
//
// A fragment's bracket spans from the earliest to the latest emission of any message it names.
// Two fragments computed independently over overlapping selections can legitimately CROSS rather
// than nest — ported from `diagram.DiagramEmitters.normalizeFramesForNesting`'s own doc for why a
// naive open/close walk would silently mislabel one of them; the clamp-to-parent algorithm is
// unchanged, just generalized to plain IntRanges instead of DiagramFrame.firstMsg/lastMsg.

private class Seq3Bracket(val fragment: Seq3Fragment, val range: IntRange, val depth: Int)

private fun fragmentBounds(fragment: Seq3Fragment, plan: Seq3EmissionPlan): IntRange? {
    val exactMessageIds = fragment.occurrenceRefs.mapTo(hashSetOf()) { it.messageId }
    val exact = fragment.occurrenceRefs.mapNotNull { plan.indexByOccurrence[it] }
    val messageIds = fragment.messageIds.filterNot { it in exactMessageIds }
    val starts = messageIds.mapNotNull { plan.firstIndexByMessage[it] }
    val ends = messageIds.mapNotNull { plan.lastIndexByMessage[it] }
    if (exact.isEmpty() && (starts.isEmpty() || ends.isEmpty())) return null
    val all = exact + starts + ends
    return all.min()..all.max()
}

private fun normalizedBrackets(fragments: List<Seq3Fragment>, plan: Seq3EmissionPlan): List<Seq3Bracket> {
    val withBounds = fragments.mapNotNull { fragment -> fragmentBounds(fragment, plan)?.let { fragment to it } }
    if (withBounds.size <= 1) return withBounds.map { (fragment, range) -> Seq3Bracket(fragment, range, 0) }
    val sorted = withBounds.sortedWith(compareBy({ it.second.first }, { -it.second.last }))
    val stack = ArrayDeque<Seq3Bracket>()
    val result = ArrayList<Seq3Bracket>(sorted.size)
    sorted.forEach { (fragment, range) ->
        while (stack.isNotEmpty() && stack.last().range.last < range.first) stack.removeLast()
        val parent = stack.lastOrNull()
        val clampedEnd = if (parent != null) minOf(range.last, parent.range.last) else range.last
        val bracket = Seq3Bracket(fragment, range.first..clampedEnd, stack.size)
        result += bracket
        stack.addLast(bracket)
    }
    return result
}

private fun fragmentLabel(fragment: Seq3Fragment): String = fragment.label.ifBlank { fragment.kind.name.lowercase() }

// ── Fragment open lines (WP12) ───────────────────────────────────────────────────────────────
//
// Every [Seq3FragmentKind] except [Seq3FragmentKind.GROUP] is a real UML 2.x combined-fragment
// operator, and both dialects accept the bare keyword the same way: `kind.name.lowercase()` plus
// the label, closed by a plain `end`. [Seq3FragmentKind.GROUP] is not a UML operator at all — see
// that enum constant's own doc — and the two dialects diverge on it:
//   - PlantUML invented `group <label>` for exactly this, which happens to have the exact same
//     shape as every other operator (`kind.name.lowercase()` + label), so PlantUML needs no
//     special case.
//   - Mermaid has no equivalent, and the bare word `group` is a MERMAID PARSE ERROR. There is
//     nothing to fall back to but `rect rgb(...)` (still closed by a plain `end`, so the
//     open/close bracket machinery below is untouched) wrapping a `Note over` that carries the
//     label, so the label survives even though the construct itself does not exist in Mermaid.
//
// This is the FIRST fragment kind that needs a per-dialect branch, so the branch lives in its own
// function per dialect rather than as a special case bolted onto a shared `kind.name.lowercase()`
// call. DO NOT collapse [mermaidFragmentOpenLines] back into [plantUmlFragmentOpenLines]'s shape —
// a future reader who notices they mostly produce "one open line per fragment" will be tempted to
// "unify" them, and that would silently regress GROUP's Mermaid output back into a parse error.

private const val GROUP_RECT_COLOR = "rgb(240, 240, 240)"

/** The participant span a fragment's OWN bracket range touches — same idea as [noteSpan], but
 *  computed from a resolved [Seq3Bracket.range] (index space) instead of a note's raw messageIds,
 *  since a GROUP fragment's Mermaid `Note over` must span exactly what the bracket itself spans,
 *  not the fragment's un-clamped [Seq3Fragment.messageIds]. */
private fun bracketSpan(bracket: Seq3Bracket, plan: Seq3EmissionPlan, aliases: List<String>): String {
    val touched = bracket.range.flatMap { emissionParticipants(plan.emissions[it]) }.distinct().sorted()
    if (touched.isEmpty()) return aliases.getOrElse(0) { "p0" }
    val lo = aliases.getOrElse(touched.first()) { "p${touched.first()}" }
    val hi = aliases.getOrElse(touched.last()) { "p${touched.last()}" }
    return if (lo == hi) lo else "$lo,$hi"
}

/** Mermaid's open line(s) for one fragment bracket. Every kind but GROUP is one line; GROUP is two
 *  (`rect` + `Note over`) — see this section's own header for why. Lines carry no indentation or
 *  trailing newline; the caller applies both, same as every other emitted line in [toMermaid]. */
private fun mermaidFragmentOpenLines(bracket: Seq3Bracket, plan: Seq3EmissionPlan, aliases: List<String>): List<String> {
    val fragment = bracket.fragment
    return if (fragment.kind == Seq3FragmentKind.GROUP) {
        listOf(
            "rect $GROUP_RECT_COLOR",
            "Note over ${bracketSpan(bracket, plan, aliases)}: ${mermaidEscape(fragmentLabel(fragment))}",
        )
    } else {
        listOf("${fragment.kind.name.lowercase()} ${mermaidEscape(fragmentLabel(fragment))}")
    }
}

/** PlantUML's open line for one fragment bracket. GROUP needs no special case here: PlantUML's own
 *  invented `group <label>` already has the exact `kind.name.lowercase()` + label shape every real
 *  UML operator has. Kept as its own function (rather than inlined at the one call site) so the
 *  per-dialect branch structure is symmetric with [mermaidFragmentOpenLines] and the next kind that
 *  needs a real PlantUML-side special case has an obvious place to add it. */
private fun plantUmlFragmentOpenLines(bracket: Seq3Bracket): List<String> {
    val fragment = bracket.fragment
    return listOf("${fragment.kind.name.lowercase()} ${plantUmlEscape(fragmentLabel(fragment))}")
}

// ── Notes ────────────────────────────────────────────────────────────────────────────────────
//
// A note anchors right after the LAST emission of the LAST message it references, and its span
// covers every lifeline any of its referenced messages actually touched — the same idea as
// `diagram.DiagramEmitters.mermaidNoteSpan`, generalized to a multi-message selection instead of
// one frame.

private fun noteAnchorIndex(note: Seq3Note, plan: Seq3EmissionPlan): Int? =
    note.messageIds.mapNotNull { plan.lastIndexByMessage[it] }.maxOrNull()

private fun noteSpan(note: Seq3Note, plan: Seq3EmissionPlan, aliases: List<String>): String {
    val touched = note.messageIds.flatMap { id ->
        val start = plan.firstIndexByMessage[id]
        val end = plan.lastIndexByMessage[id]
        if (start == null || end == null) emptyList() else (start..end).flatMap { emissionParticipants(plan.emissions[it]) }
    }.distinct().sorted()
    if (touched.isEmpty()) return aliases.getOrElse(0) { "p0" }
    val lo = aliases.getOrElse(touched.first()) { "p${touched.first()}" }
    val hi = aliases.getOrElse(touched.last()) { "p${touched.last()}" }
    return if (lo == hi) lo else "$lo,$hi"
}

// ── Time-gap markers (WP11) ──────────────────────────────────────────────────────────────────
//
// A [Seq3Delay] is anchored to the emission right after its [Seq3Delay.afterMessageId]'s LAST
// drawn row — same anchor rule [noteAnchorIndex] already uses for a [Seq3Note], reused here rather
// than duplicated. What differs is what gets WRITTEN, and the two dialects genuinely differ here:
//
//   - PlantUML has real delay syntax — `...label...` — a construct with no participant of its own,
//     drawn as a plain divider across the WHOLE diagram (matching Seq3Layout.kt's Seq3DelayBox,
//     which also spans the full diagram width, not just the lifelines a nearby message touched).
//   - Mermaid has NO delay or spacer construct at all. There is nothing to fall back to but its
//     own `Note over` — spanning the FIRST and LAST participant column (not just the ones the
//     anchor message touched, the way [noteSpan] does for an ordinary [Seq3Note]) so the note
//     visually reads as a full-width divider too.
//
// DO NOT "unify" these two branches into one shared line-builder: a future reader who notices they
// both ultimately produce one line of text per delay will be tempted to, and that would silently
// throw away PlantUML's own delay syntax in favor of a note — a real semantic downgrade, not a
// harmless refactor. (For context: standard UML models elapsed time as a DurationConstraint,
// which neither dialect implements — being dialect-specific here is the necessary consequence of
// that gap, not sloppiness that should be cleaned up.)

// User-observed correction: this used to always resolve to the message's LAST emitted occurrence
// (`lastIndexByMessage`), regardless of which specific occurrence a delay was actually anchored to
// — right-clicking the FIRST of several repeated occurrences of a message and choosing "Insert
// delay after this" still exported the `...` (PlantUML) / gap note (Mermaid) after the LAST one.
// `afterOccurrenceEntryId` (null for every delay created before that field existed) now resolves
// through the same `indexByOccurrence` map fragment/note boundary lookups already use, falling
// back to `lastIndexByMessage` when it's null or names an occurrence that plan no longer emits
// (hidden, or the row simply doesn't repeat that many times any more).
private fun delayAnchorIndex(delay: Seq3Delay, plan: Seq3EmissionPlan): Int? =
    delay.afterOccurrenceEntryId
        ?.let { entryId -> plan.indexByOccurrence[Seq3OccurrenceRef(delay.afterMessageId, entryId)] }
        ?: plan.lastIndexByMessage[delay.afterMessageId]

private fun delaySpan(aliases: List<String>): String {
    val first = aliases.firstOrNull() ?: return "p0"
    val last = aliases.lastOrNull() ?: first
    return if (first == last) first else "$first,$last"
}

// ── Mermaid ──────────────────────────────────────────────────────────────────────────────────

fun Seq3Document.toMermaid(): String {
    val plan = planEmissions(this)
    // Same filter+sort as planEmissions' own `visibleLifelines` — see that function's own comment
    // for why they must stay identical (index-for-index alignment between `aliases` here and
    // `lifelineIndex` there).
    val visibleLifelines = lifelines.filter { it.visibility == Seq3Visibility.VISIBLE }.sortedBy { it.ordinal }
    val aliases = sanitizedAliases(visibleLifelines)
    // Hidden fragments/notes are dropped exactly like a hidden lifeline/message already is —
    // Seq3Fragment.visibility/Seq3Note.visibility's own "drop the box, keep the row" contract.
    val visibleFragments = fragments.filter { it.visibility == Seq3Visibility.VISIBLE }
    val visibleNotes = notes.filter { it.visibility == Seq3Visibility.VISIBLE }
    val brackets = normalizedBrackets(visibleFragments, plan)
    val opens = brackets.groupBy { it.range.first }
    val closes = brackets.groupBy { it.range.last }
    val notesByAnchor = visibleNotes.mapNotNull { note -> noteAnchorIndex(note, plan)?.let { it to note } }.groupBy({ it.first }, { it.second })
    val visibleDelays = delays.filter { it.visibility == Seq3Visibility.VISIBLE }
    val delaysByAnchor = visibleDelays.mapNotNull { d -> delayAnchorIndex(d, plan)?.let { it to d } }.groupBy({ it.first }, { it.second })

    fun aliasOf(idx: Int) = aliases.getOrElse(idx) { "p$idx" }

    return buildString {
        append("sequenceDiagram\n")
        if (title.isNotBlank()) append("    title ").append(mermaidEscape(title)).append('\n')
        visibleLifelines.forEachIndexed { i, l ->
            // Item: ACTOR lifelines emit Mermaid's own `actor` keyword instead of `participant` —
            // purely a glyph/export-keyword choice (Seq3LifelineKind's own doc), and the resolved
            // display name (per-lifeline override, else the document default) rather than the raw
            // name, matching what the header chip/glyph actually shows on screen.
            val keyword = if (l.kind == Seq3LifelineKind.ACTOR) "actor" else "participant"
            val displayName = seq3DisplayName(l.name, l.displaySegments, lifelineDisplaySegments)
            append("    ").append(keyword).append(' ').append(aliases[i]).append(" as ").append(mermaidEscape(displayName)).append('\n')
        }
        plan.emissions.forEachIndexed { i, emission ->
            opens[i]?.sortedBy { it.depth }?.forEach { b ->
                mermaidFragmentOpenLines(b, plan, aliases).forEach { line -> append("    ").append(line).append('\n') }
            }
            when (emission) {
                is Seq3Emission.Arrow -> {
                    val arrow = when (emission.kind) {
                        Seq3Kind.RETURN -> "-->>"
                        Seq3Kind.ASYNC -> "-)"
                        else -> "->>"
                    }
                    val label = mermaidEscape(emission.label) + repeatSuffix(emission.repeatCount)
                    append("    ").append(aliasOf(emission.fromIdx)).append(arrow).append(aliasOf(emission.toIdx)).append(": ").append(label).append('\n')
                }
                is Seq3Emission.NeedsTarget ->
                    append("    Note right of ").append(aliasOf(emission.fromIdx)).append(": ")
                        .append(mermaidEscape(emission.label)).append(" · needs target").append('\n')
                is Seq3Emission.NoteLine ->
                    append("    Note over ").append(aliasOf(emission.participantIdx)).append(": ").append(mermaidEscape(emission.text)).append('\n')
                is Seq3Emission.Elided ->
                    append("    Note right of ").append(aliasOf(emission.participantIdx)).append(": ⋯ ×").append(emission.count).append(" elided\n")
            }
            notesByAnchor[i]?.forEach { note ->
                append("    Note over ").append(noteSpan(note, plan, aliases)).append(": ").append(mermaidEscape(note.text)).append('\n')
            }
            closes[i]?.sortedByDescending { it.depth }?.forEach { append("    end\n") }
            // WP11: Mermaid has no delay/spacer construct — see this file's own "Time-gap markers"
            // header for why this stays a `Note over`, never "unified" with PlantUML's `...` below.
            delaysByAnchor[i]?.forEach { d ->
                append("    Note over ").append(delaySpan(aliases)).append(": ").append(mermaidEscape(d.label)).append('\n')
            }
        }
    }
}

// ── PlantUML ─────────────────────────────────────────────────────────────────────────────────

fun Seq3Document.toPlantUml(): String {
    val plan = planEmissions(this)
    // See toMermaid's own comment: must stay the exact same filter+sort as planEmissions'
    // `visibleLifelines` so `aliases` here and `lifelineIndex` there agree index-for-index.
    val visibleLifelines = lifelines.filter { it.visibility == Seq3Visibility.VISIBLE }.sortedBy { it.ordinal }
    val aliases = sanitizedAliases(visibleLifelines)
    val visibleFragments = fragments.filter { it.visibility == Seq3Visibility.VISIBLE }
    val visibleNotes = notes.filter { it.visibility == Seq3Visibility.VISIBLE }
    val brackets = normalizedBrackets(visibleFragments, plan)
    val opens = brackets.groupBy { it.range.first }
    val closes = brackets.groupBy { it.range.last }
    val notesByAnchor = visibleNotes.mapNotNull { note -> noteAnchorIndex(note, plan)?.let { it to note } }.groupBy({ it.first }, { it.second })
    val visibleDelays = delays.filter { it.visibility == Seq3Visibility.VISIBLE }
    val delaysByAnchor = visibleDelays.mapNotNull { d -> delayAnchorIndex(d, plan)?.let { it to d } }.groupBy({ it.first }, { it.second })

    fun aliasOf(idx: Int) = aliases.getOrElse(idx) { "p$idx" }

    return buildString {
        append("@startuml\n")
        if (title.isNotBlank()) append("title ").append(plantUmlEscape(title)).append('\n')
        visibleLifelines.forEachIndexed { i, l ->
            // Same ACTOR-vs-participant keyword and resolved-display-name treatment as toMermaid.
            val keyword = if (l.kind == Seq3LifelineKind.ACTOR) "actor" else "participant"
            val displayName = seq3DisplayName(l.name, l.displaySegments, lifelineDisplaySegments)
            append(keyword).append(" \"").append(plantUmlEscape(displayName)).append("\" as ").append(aliases[i]).append('\n')
        }
        plan.emissions.forEachIndexed { i, emission ->
            opens[i]?.sortedBy { it.depth }?.forEach { b ->
                plantUmlFragmentOpenLines(b).forEach { line -> append(line).append('\n') }
            }
            when (emission) {
                is Seq3Emission.Arrow -> {
                    val arrow = when (emission.kind) {
                        Seq3Kind.RETURN -> "-->"
                        Seq3Kind.ASYNC -> "->>"
                        else -> "->"
                    }
                    val label = plantUmlEscape(emission.label) + repeatSuffix(emission.repeatCount)
                    append(aliasOf(emission.fromIdx)).append(' ').append(arrow).append(' ')
                        .append(aliasOf(emission.toIdx)).append(": ").append(label).append('\n')
                }
                is Seq3Emission.NeedsTarget ->
                    append("note right of ").append(aliasOf(emission.fromIdx)).append(": ")
                        .append(plantUmlEscape(emission.label)).append(" · needs target").append('\n')
                is Seq3Emission.NoteLine ->
                    append("note right of ").append(aliasOf(emission.participantIdx)).append(": ").append(plantUmlEscape(emission.text)).append('\n')
                is Seq3Emission.Elided ->
                    append("note right of ").append(aliasOf(emission.participantIdx)).append(": ⋯ ×").append(emission.count).append(" elided\n")
            }
            notesByAnchor[i]?.forEach { note ->
                append("note over ").append(noteSpan(note, plan, aliases)).append(": ").append(plantUmlEscape(note.text)).append('\n')
            }
            closes[i]?.sortedByDescending { it.depth }?.forEach { append("end\n") }
            // WP11: PlantUML's REAL delay syntax — `...label...`, no participant reference at all
            // (it draws as a full-width divider natively) — see this file's own "Time-gap markers"
            // header for why this must NOT be folded into the same branch as toMermaid's Note over.
            delaysByAnchor[i]?.forEach { d -> append("...").append(plantUmlEscape(d.label)).append("...\n") }
        }
        append("@enduml\n")
    }
}

/** Convenience dispatcher mirroring `diagram.DiagramEmitters.toSource`. */
enum class Seq3Dialect { MERMAID, PLANTUML }

fun Seq3Document.toSource(dialect: Seq3Dialect): String = when (dialect) {
    Seq3Dialect.MERMAID -> toMermaid()
    Seq3Dialect.PLANTUML -> toPlantUml()
}
