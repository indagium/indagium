package com.indagium.diagram3

import com.indagium.utils.elapsedMillisOfDay

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

/** Mermaid has no lost/found primitive (this file's own header: "text dialects have no
 *  dashed-line primitive worth the trouble") — LOST/FOUND keep the same stub-note fallback shape
 *  as a genuinely unresolved message ([Seq3Emission.NeedsTarget] covers both), but WP9 exists
 *  specifically to stop calling a resolved lost/found message "needs target", so the suffix must
 *  say what actually happened instead. Pulled out of `toMermaid` itself (not just inlined) once
 *  this branch pushed that function over detekt's CyclomaticComplexMethod threshold. */
private fun mermaidNeedsTargetSuffix(kind: Seq3Kind): String = when (kind) {
    Seq3Kind.LOST -> " · lost"
    Seq3Kind.FOUND -> " · found"
    else -> " · needs target"
}

/** PlantUML has REAL grammar for this (verified against plantuml.com's own "Incoming and outgoing
 *  messages" section, not trusted from memory): `[o->` draws an arrow FROM a filled-circle gate on
 *  the left edge INTO a participant (found), and `->o]` draws an arrow FROM a participant OUT to a
 *  filled-circle gate on the right edge (lost). An ordinary still-unresolved CALL/RETURN/ASYNC/SELF
 *  has no such real syntax to reach for, so it keeps the plain "note right of" fallback. Pulled out
 *  of `toPlantUml` itself (not just inlined) once this branch pushed that function over detekt's
 *  CyclomaticComplexMethod threshold — mirrors [mermaidNeedsTargetSuffix]'s own reason. */
private fun StringBuilder.appendPlantUmlNeedsTargetLine(emission: Seq3Emission.NeedsTarget, aliasOf: (Int) -> String): StringBuilder =
    when (emission.kind) {
        Seq3Kind.LOST -> append(aliasOf(emission.fromIdx)).append(" ->o]: ").append(plantUmlEscape(emission.label)).append('\n')
        Seq3Kind.FOUND -> append("[o-> ").append(aliasOf(emission.fromIdx)).append(": ").append(plantUmlEscape(emission.label)).append('\n')
        else ->
            append("note right of ").append(aliasOf(emission.fromIdx)).append(": ")
                .append(plantUmlEscape(emission.label)).append(" · needs target").append('\n')
    }

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
        // WP16: appended LAST (this file's own field-versioning convention — file-local sealed
        // type, no codec, no round-trip; see Seq3Layout.kt's `Emission.Arrow.spanEndTimestampMillis`
        // for the full doc — identical meaning here). Non-null ONLY on a [Seq3Repeat.COLLAPSE_ABOVE]
        // row above threshold, from `occurrences.last().timestampMillis` — set in `expandMessage`'s
        // COLLAPSE_ABOVE branch.
        val spanEndTimestampMillis: Long? = null,
    ) : Seq3Emission()

    data class NeedsTarget(
        override val messageId: String,
        val fromIdx: Int,
        val label: String,
        override val occurrenceEntryId: Int? = null,
        val rawTimestamp: String = "",
        override val timestampMillis: Long? = null,
        /** Appended last (this file's own field-versioning convention — see Seq3Model.kt's
         *  "append LAST" doc). Was always implicitly CALL/RETURN/ASYNC/SELF before WP9: a
         *  genuinely-unresolved message. WP9 routes [Seq3Kind.LOST]/[Seq3Kind.FOUND] through this
         *  SAME emission (both also have `toLifelineId == null`) so the two dialects can tell a
         *  real UML lost/found message apart from an ordinary "still needs a target" defect and
         *  emit real syntax for the former instead of the shared "needs target" fallback note. */
        val kind: Seq3Kind = Seq3Kind.CALL,
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
        // WP15 Part 2: was `templatedLabel(message)` — a capture-bearing NOTE emitted its literal
        // `{name}` slots into the exported text with no value in sight. See Seq3Layout.kt's
        // matching `expandForLayout` NOTE branch for the full reasoning; `collapsedRepeatLabel`
        // already handles the "no occurrences to substitute from" authored case correctly on its
        // own (falls back to the template), so nothing else changes here.
        return listOf(
            Seq3Emission.NoteLine(
                message.id,
                fromIdx,
                collapsedRepeatLabel(message, occurrences),
                occurrences.firstOrNull()?.entryId,
                message.primaryTimestampMillis,
            ),
        )
    }
    val toIdx = message.toLifelineId?.let(lifelineIndex::get)
    if (toIdx == null) {
        // WP15 Part 2: same fix as the NOTE branch above — an unresolved/LOST/FOUND stub used to
        // emit the bare template too.
        return listOf(
            Seq3Emission.NeedsTarget(
                message.id,
                fromIdx,
                collapsedRepeatLabel(message, occurrences),
                occurrences.firstOrNull()?.entryId,
                message.primaryRawTimestamp,
                message.primaryTimestampMillis,
                message.kind,
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
                    // COUNT, not "how many rows do I draw" — see Seq3Layout.expandForLayout's
                    // identical COLLAPSE_ABOVE branch for why this reads totalOccurrenceCount.
                    message.totalOccurrenceCount ?: occurrences.size,
                    occurrences.first().entryId,
                    seq3EmissionRawTimestamp(message, occurrences.first().rawTimestamp),
                    seq3EmissionTimestamp(message, occurrences.first().timestampMillis),
                    // WP16: the row's own internal span end — see Seq3Layout.expandForLayout's
                    // identical COLLAPSE_ABOVE branch (and Seq3Generator's trimSeq3MessageOccurrences)
                    // for why `.last()` is still the true last occurrence even on a trimmed message.
                    occurrences.last().timestampMillis,
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
    // COUNT, not "how many rows do I draw" — see Seq3Layout.firstLastEmissions's identical comment.
    val elided = (message.totalOccurrenceCount ?: occurrences.size) - 2
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
    val prefixed = prefixSeq3EmissionLabels(emissions, document.showSequenceNumbers, document.showTimestamps, document.showElapsed)
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
// WP15 Part 1: mirrors Seq3Layout.kt's own `prefixEmissionLabels` — see that function's own doc for
// the fold/accumulator reasoning (rule 4 especially: the accumulator advances on an untagged
// NoteLine/Elided row too, which is what makes a FIRST_LAST elision's true span show up correctly
// on the LAST row after it). A fourth small copy of this "which emissions are numbered, which
// advance the accumulator" split, not a shared call — same reason [prefixEmissionLabels] itself
// isn't shared (this file's own header: Seq3Emitters is phase-1, not restructured around
// Seq3Layout's shape). What both copies MUST share, and do, is [seq3PrefixedLabel] and
// [elapsedMillisOfDay] themselves.
private fun prefixSeq3EmissionLabels(
    emissions: List<Seq3Emission>,
    showSequenceNumbers: Boolean,
    showTimestamps: Boolean,
    showElapsed: Boolean,
): List<Seq3Emission> {
    if (!showSequenceNumbers && !showTimestamps && !showElapsed) return emissions
    var callNumber = 0
    var lastRealTimestampMillis: Long? = null
    fun elapsedFor(currentTimestampMillis: Long?): Long? {
        val previous = lastRealTimestampMillis
        return if (previous != null && currentTimestampMillis != null) elapsedMillisOfDay(previous, currentTimestampMillis) else null
    }
    return emissions.map { emission ->
        when (emission) {
            is Seq3Emission.Arrow -> {
                callNumber++
                val elapsed = elapsedFor(emission.timestampMillis)
                val result = emission.copy(
                    label = seq3PrefixedLabel(
                        emission.label,
                        callNumber,
                        emission.rawTimestamp,
                        emission.timestampMillis,
                        showSequenceNumbers,
                        showTimestamps,
                        elapsed,
                        showElapsed,
                        emission.spanEndTimestampMillis,
                    ),
                )
                lastRealTimestampMillis = emission.timestampMillis
                result
            }
            is Seq3Emission.NeedsTarget -> {
                callNumber++
                val elapsed = elapsedFor(emission.timestampMillis)
                val result = emission.copy(
                    label = seq3PrefixedLabel(
                        emission.label,
                        callNumber,
                        emission.rawTimestamp,
                        emission.timestampMillis,
                        showSequenceNumbers,
                        showTimestamps,
                        elapsed,
                        showElapsed,
                    ),
                )
                lastRealTimestampMillis = emission.timestampMillis
                result
            }
            is Seq3Emission.NoteLine, is Seq3Emission.Elided -> {
                // Untagged, but the accumulator still advances — see this function's own header
                // and Seq3Layout.prefixEmissionLabels' matching comment (rule 4).
                lastRealTimestampMillis = emission.timestampMillis
                emission
            }
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

// [mermaidFragmentOpenLines]'s non-GROUP branch and [plantUmlFragmentOpenLines] both already write
// the kind keyword themselves before appending a label — unlike [fragmentLabel] above (kept as-is
// for Seq3Layout.fragmentBoxFrom parity and the GROUP `Note over` line below, where the kind word
// is NOT otherwise shown, so falling back to it there is correct), reusing that ifBlank fallback
// at either of THESE two call sites doubled the kind word into "alt alt" / "loop loop" for a
// fragment with a blank label. A blank label must render as the bare keyword instead — no trailing
// separator either, so "alt", never "alt " or "alt alt".
private fun fragmentKeywordLine(kind: Seq3FragmentKind, label: String, escape: (String) -> String): String {
    val keyword = kind.name.lowercase()
    return if (label.isBlank()) keyword else "$keyword ${escape(label)}"
}

// ── Fragment open lines (WP12, WP11, WP17) ──────────────────────────────────────────────────
//
// Every [Seq3FragmentKind] except the members of [MERMAID_FALLBACK_FRAGMENT_KINDS] is a real UML
// 2.x combined-fragment operator that BOTH dialects accept the same bare way: `kind.name.lowercase()`
// plus the label, closed by a plain `end`. The dialects diverge on that fallback set:
//   - PlantUML needs no special case for GROUP or NEG/STRICT/CONSIDER/IGNORE. GROUP is not a UML
//     operator at all — see that enum constant's own doc — but PlantUML invented `group <label>`
//     for exactly this, which happens to have the exact same shape as every real operator
//     (`kind.name.lowercase()` + label). NEG/STRICT/CONSIDER/IGNORE (WP11) ARE real UML operators,
//     so PlantUML's own `neg`/`strict`/`consider`/`ignore` keyword falls straight out of that same
//     shared `kind.name.lowercase()` call — nothing PlantUML-side to add for them either. REF
//     (WP17) is the ONE member of this set that DOES need a PlantUML-side special case —
//     `plantUmlFragmentOpenLines`'s own doc covers the real `ref over A, B : label` syntax and why
//     its bracket also skips the generic `end` line below.
//   - Mermaid has no equivalent for any of the six, and the bare word is a MERMAID PARSE ERROR for
//     every one of them (its sequence-diagram grammar has keywords for only
//     `loop/alt/else/opt/par/and/critical/option/break/rect`, and no `ref` construct at all). There
//     is nothing to fall back to but `rect rgb(...)` (still closed by a plain `end`, so the
//     open/close bracket machinery below is untouched) wrapping a `Note over`. For GROUP the note
//     carries just the label (GROUP has no operator word worth showing — see [Seq3FragmentKind]'s
//     own doc). For the five real operators (WP11's four plus WP17's REF) the note carries the
//     operator word too (`fragmentKeywordLine`, the SAME "keyword + label, no doubling for a blank
//     label" formatting the real-operator branch below uses) — dropping it there would silently
//     erase the one thing that made picking NEG/CONSIDER/REF over LOOP/GROUP meaningful, since
//     Mermaid's rendered diagram can no longer say so itself.
//
// GROUP was the FIRST fragment kind that needed a per-dialect branch, so the branch lives in its
// own function per dialect rather than as a special case bolted onto a shared `kind.name.lowercase()`
// call. DO NOT collapse [mermaidFragmentOpenLines] back into [plantUmlFragmentOpenLines]'s shape —
// a future reader who notices they mostly produce "one open line per fragment" will be tempted to
// "unify" them, and that would silently regress GROUP's (and now NEG/STRICT/CONSIDER/IGNORE's, and
// now REF's real `ref over` syntax) output back into a parse error or the wrong PlantUML keyword.

private const val FALLBACK_RECT_COLOR = "rgb(240, 240, 240)"

/** Fragment kinds whose Mermaid rendering has no real keyword to fall back on — see this section's
 *  own header for the per-kind reasoning. [Seq3FragmentKind.GROUP] started this set (WP12); WP11
 *  adds the four real UML operators Mermaid's grammar simply never grew a keyword for. WP17 adds
 *  [Seq3FragmentKind.REF] for the same "no keyword" reason — Mermaid's sequence-diagram grammar
 *  has no `ref` construct at all, real or otherwise, so a bare `ref Retry` would be as much a
 *  Mermaid PARSE ERROR as a bare `group`/`neg` — but REF is NOT like the other four WP11 members
 *  in one respect: it still falls into the "carries the operator word too" branch below rather
 *  than GROUP's bare-label branch, since REF (unlike GROUP) IS a real UML operator whose word is
 *  worth keeping visible after the degradation, exactly like NEG/STRICT/CONSIDER/IGNORE. */
private val MERMAID_FALLBACK_FRAGMENT_KINDS = setOf(
    Seq3FragmentKind.GROUP,
    Seq3FragmentKind.NEG,
    Seq3FragmentKind.STRICT,
    Seq3FragmentKind.CONSIDER,
    Seq3FragmentKind.IGNORE,
    Seq3FragmentKind.REF,
)

/** The participant span a fragment's OWN bracket range touches — same idea as [noteSpan], but
 *  computed from a resolved [Seq3Bracket.range] (index space) instead of a note's raw messageIds,
 *  since a fallback fragment's Mermaid `Note over` must span exactly what the bracket itself spans,
 *  not the fragment's un-clamped [Seq3Fragment.messageIds]. */
private fun bracketSpan(bracket: Seq3Bracket, plan: Seq3EmissionPlan, aliases: List<String>): String {
    val touched = bracket.range.flatMap { emissionParticipants(plan.emissions[it]) }.distinct().sorted()
    if (touched.isEmpty()) return aliases.getOrElse(0) { "p0" }
    val lo = aliases.getOrElse(touched.first()) { "p${touched.first()}" }
    val hi = aliases.getOrElse(touched.last()) { "p${touched.last()}" }
    return if (lo == hi) lo else "$lo,$hi"
}

/** Mermaid's open line(s) for one fragment bracket. Every kind but the [MERMAID_FALLBACK_FRAGMENT_KINDS]
 *  members is one line; a fallback kind is two (`rect` + `Note over`) — see this section's own
 *  header for why, and for why GROUP's note carries only the label while the other five fallback
 *  kinds (WP11's four plus WP17's REF) also carry the operator word. Lines carry no indentation or
 *  trailing newline; the caller applies both, same as every other emitted line in [toMermaid]. */
private fun mermaidFragmentOpenLines(bracket: Seq3Bracket, plan: Seq3EmissionPlan, aliases: List<String>): List<String> {
    val fragment = bracket.fragment
    return if (fragment.kind in MERMAID_FALLBACK_FRAGMENT_KINDS) {
        val noteText = if (fragment.kind == Seq3FragmentKind.GROUP) {
            mermaidEscape(fragmentLabel(fragment))
        } else {
            fragmentKeywordLine(fragment.kind, fragment.label, ::mermaidEscape)
        }
        listOf(
            "rect $FALLBACK_RECT_COLOR",
            "Note over ${bracketSpan(bracket, plan, aliases)}: $noteText",
        )
    } else {
        listOf(fragmentKeywordLine(fragment.kind, fragment.label, ::mermaidEscape))
    }
}

/** PlantUML's open line for one fragment bracket. GROUP needs no special case here: PlantUML's own
 *  invented `group <label>` already has the exact `kind.name.lowercase()` + label shape every real
 *  UML operator has. Kept as its own function (rather than inlined at the one call site) so the
 *  per-dialect branch structure is symmetric with [mermaidFragmentOpenLines] and the next kind that
 *  needs a real PlantUML-side special case has an obvious place to add it — [REF] (WP17) is that
 *  next kind: unlike every other member, its real PlantUML syntax is `ref over A, B : label`, NOT
 *  `kind.name.lowercase()` + label (confirmed against plantuml.com's own sequence-diagram
 *  documentation — see [Seq3FragmentKind.REF]'s own doc for why that page, not a public grammar
 *  file, is the best available source for this dialect). [plan]/[aliases] are only needed for this
 *  one branch, to compute the `over A, B` participant span via [bracketSpan] — every other kind
 *  ignores them, same as [mermaidFragmentOpenLines] already threads both through for its own
 *  fallback branch's `Note over`. */
private fun plantUmlFragmentOpenLines(bracket: Seq3Bracket, plan: Seq3EmissionPlan, aliases: List<String>): List<String> {
    val fragment = bracket.fragment
    return if (fragment.kind == Seq3FragmentKind.REF) {
        listOf("ref over ${bracketSpan(bracket, plan, aliases)} : ${plantUmlEscape(fragmentLabel(fragment))}")
    } else {
        listOf(fragmentKeywordLine(fragment.kind, fragment.label, ::plantUmlEscape))
    }
}

// ── Fragment operand dividers (WP5) ─────────────────────────────────────────────────────────
//
// [Seq3Fragment.elseOperands] is UML's InteractionOperand list minus operand zero (see that
// field's own doc) — this renders the divider that separates each of THOSE operands from the one
// before it. Operand zero needs no divider of its own: the fragment's OPEN line, just above,
// already puts its guard exactly where UML puts operand zero's.
//
// The divider keyword is dialect- AND kind-dependent, not just dialect-dependent — the same
// "genuinely disagree" situation [mermaidFragmentOpenLines]'s own header describes for GROUP:
//   ALT       -> `else <guard>` in BOTH dialects (UML's own default, which Mermaid borrows too).
//   PAR       -> `and <guard>` in Mermaid (its own keyword for a parallel branch) but
//                `else <guard>` in PlantUML (PlantUML has no `and`; every divided operator reuses
//                `else`).
//   CRITICAL  -> `option <guard>` in Mermaid; PlantUML has NO divider syntax for `critical` AT
//                ALL — there is nothing to fall back to, so a `critical` operand's own messages
//                silently fold into the preceding branch in PlantUML text. The messages
//                themselves are still emitted in order; only the branch label is lost, which is
//                exactly what this deliverable calls for, not a bug to paper over.
//   everything else (OPT/LOOP/BREAK/GROUP) -> no divider in EITHER dialect: UML gives OPT/LOOP/
//                BREAK exactly one operand, and GROUP isn't a UML combined-fragment operator at
//                all (see [Seq3FragmentKind]'s own doc) — so a stray `elseOperands` entry left
//                behind by a kind change (preserved on purpose, see that field's own doc) is
//                simply never rendered for any of them.
//
// DO NOT fold these two into one `when (dialect)` helper — same reasoning as
// [mermaidFragmentOpenLines]'s own header: PAR and CRITICAL genuinely disagree between dialects,
// so a "unified" version would just relocate the per-dialect branch one level down instead of
// removing it, while making it easy to miss that CRITICAL has no PlantUML case at all.

/** Shared keyword+guard formatting only — NOT a dialect branch (see the header above for why the
 *  two functions below stay separate). Mirrors [fragmentKeywordLine]'s "a blank label leaves the
 *  bare keyword, no trailing separator" rule for the same reason: a guard is user-typed text and
 *  can be blank. */
private fun operandDividerLine(keyword: String, guard: String, escape: (String) -> String): String =
    if (guard.isBlank()) keyword else "$keyword ${escape(guard)}"

/** Mermaid's divider line for one [Seq3Operand] belonging to a fragment of [kind], or null when
 *  [kind] has no Mermaid divider at all (every kind but ALT/PAR/CRITICAL — see this section's own
 *  header table). */
private fun mermaidFragmentDividerLine(kind: Seq3FragmentKind, guard: String): String? = when (kind) {
    Seq3FragmentKind.ALT -> operandDividerLine("else", guard, ::mermaidEscape)
    Seq3FragmentKind.PAR -> operandDividerLine("and", guard, ::mermaidEscape)
    Seq3FragmentKind.CRITICAL -> operandDividerLine("option", guard, ::mermaidEscape)
    else -> null
}

/** PlantUML's divider line for one [Seq3Operand] belonging to a fragment of [kind], or null when
 *  [kind] has no PlantUML divider — every kind but ALT/PAR, AND (this section's own header table)
 *  CRITICAL itself: PlantUML has no `critical` divider syntax to fall back to. */
private fun plantUmlFragmentDividerLine(kind: Seq3FragmentKind, guard: String): String? = when (kind) {
    Seq3FragmentKind.ALT -> operandDividerLine("else", guard, ::plantUmlEscape)
    Seq3FragmentKind.PAR -> operandDividerLine("else", guard, ::plantUmlEscape)
    else -> null
}

/** One resolved divider: the (normalized, clamped) bracket it opens inside, and the operand
 *  supplying its guard text. Carries the bracket rather than just the fragment so the emit loop
 *  can key its dialect-specific divider-line lookup on [Seq3Bracket.fragment].kind without a
 *  second pass back through `normalizedBrackets`' output. */
private class Seq3OperandDivider(val bracket: Seq3Bracket, val operand: Seq3Operand)

/** [Seq3Operand.startsAtMessageId]/[Seq3Operand.startsAtOccurrenceEntryId] resolved to an emission
 *  index — the same two-field occurrence-pinning contract [delayAnchorIndex] resolves for
 *  [Seq3Delay], reused here for the pinned branch, but NOT for the fallback: a [Seq3Delay] anchors
 *  AFTER its message's LAST drawn row ([Seq3EmissionPlan.lastIndexByMessage]), while
 *  [Seq3Operand.startsAtMessageId]'s own doc says the operand begins AT the FIRST drawn row of its
 *  anchor message, so the un-pinned fallback here is [Seq3EmissionPlan.firstIndexByMessage]. */
private fun operandAnchorIndex(operand: Seq3Operand, plan: Seq3EmissionPlan): Int? =
    operand.startsAtOccurrenceEntryId
        ?.let { entryId -> plan.indexByOccurrence[Seq3OccurrenceRef(operand.startsAtMessageId, entryId)] }
        ?: plan.firstIndexByMessage[operand.startsAtMessageId]

/**
 * Resolves every bracket's [Seq3Fragment.elseOperands] to the emission index their divider is
 * written at, keyed the same way [toMermaid]/[toPlantUml]'s own `opens`/`closes`/`notesByAnchor`
 * /`delaysByAnchor` already are, so the emit loop can look a divider up by its own
 * `forEachIndexed` index `i`.
 *
 * THE TRAP: [brackets] must be [normalizedBrackets]' NORMALIZED, CLAMPED output — never a
 * fragment's own raw [fragmentBounds] — because two fragments that CROSS rather than nest get one
 * of their ends clamped to the other's by that function. Resolving an operand against the raw,
 * un-clamped bounds would let its divider land past the CLAMPED end, i.e. AFTER the `end` line
 * [closes] already writes for that bracket — malformed in both dialects, and neither emitter
 * validates its own output, so this fails completely silently. Below, `index !in bracket.range`
 * reads the clamped [Seq3Bracket.range] for exactly this reason. Dedicated coverage:
 * `aDividerOnACrossingFragmentIsClampedAwayRatherThanEmittedAfterEnd` in Seq3EmitterTest.kt.
 *
 * The remaining rules, applied in order, each a real pitfall rather than defensive filler:
 *   - an unresolved anchor (a stale/dangling `startsAtMessageId`) drops the divider — the same
 *     "a stale anchor drops silently, never fails the whole emission" contract [Seq3Delay]'s own
 *     doc (and [delayAnchorIndex]'s fallback) already establish for a sibling anchor type.
 *   - an index equal to the bracket's own start is operand zero's own row — no divider belongs
 *     there; [Seq3Fragment.label] already carries operand zero's guard on the OPEN line.
 *   - ties (two operands resolving to the SAME index — e.g. a guard describing an intentionally
 *     EMPTY branch immediately followed by another) are NOT deduplicated: both are stated user
 *     intent, and [List.sortedBy] is a stable sort, so [Seq3Fragment.elseOperands]' own declared
 *     order survives as the tiebreak with no extra comparator key needed.
 */
private fun operandDividersByAnchor(brackets: List<Seq3Bracket>, plan: Seq3EmissionPlan): Map<Int, List<Seq3OperandDivider>> {
    val resolved = ArrayList<Pair<Int, Seq3OperandDivider>>()
    brackets.forEach { bracket ->
        bracket.fragment.elseOperands.forEach { operand ->
            val index = operandAnchorIndex(operand, plan) ?: return@forEach
            if (index !in bracket.range) return@forEach
            if (index == bracket.range.first) return@forEach
            resolved += index to Seq3OperandDivider(bracket, operand)
        }
    }
    return resolved.sortedBy { it.first }.groupBy({ it.first }, { it.second })
}

// Shared append loop only — NOT a merge of [mermaidFragmentDividerLine]/[plantUmlFragmentDividerLine]
// themselves (those stay separate; see that pair's own header for why). [dividerLineFor] is the one
// dialect-specific piece, passed in by reference at each call site, exactly like [appendActivationLines]
// already takes [indent] as its one per-dialect knob for a shared write loop. Factored out of
// toMermaid/toPlantUml (rather than left inline) purely to keep both under detekt's
// CyclomaticComplexMethod threshold — the branching itself already lived in
// [operandDividersByAnchor]/[mermaidFragmentDividerLine]/[plantUmlFragmentDividerLine]; this loop adds
// none of its own.
private fun StringBuilder.appendDividerLines(
    dividersByAnchor: Map<Int, List<Seq3OperandDivider>>,
    i: Int,
    indent: String,
    dividerLineFor: (Seq3FragmentKind, String) -> String?,
) {
    dividersByAnchor[i]?.forEach { divider ->
        val line = dividerLineFor(divider.bracket.fragment.kind, divider.operand.guard) ?: return@forEach
        append(indent).append(line).append('\n')
    }
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

// ── State invariants (WP18) ─────────────────────────────────────────────────────────────────
//
// Neither dialect has a StateInvariant construct — both degrade to a note anchored on the ONE
// lifeline the promoted capture's message logged from ([Seq3Message.fromLifelineId], per
// [Seq3StateInvariant]'s own doc), consistent with how a [Seq3Delay]/[Seq3FragmentKind.GROUP]
// already degrade to a note when their own dialect has nothing better for them either. Braces
// around the value (`{captureName=value}`) are UML's own state-invariant/guard notation — reusing
// them here is what keeps the degraded form visually distinguishable from an ordinary note, so a
// reader of the exported text alone can still tell "this is a state assertion" from "this is a
// comment" (this deliverable's own brief).
//
// Unlike [Seq3Delay]/[Seq3Note] (one document-wide anchor each, because their own text is a single
// constant), a state invariant is anchored at EVERY emission index that draws its message — the
// row's PER-OCCURRENCE value is the whole point, the identical rule [Seq3StateInvariantBox]
// (Seq3Layout.kt) already applies to the canvas/PNG counterpart of this same marker.

private data class Seq3StateInvariantLine(val lifelineIdx: Int, val text: String)

/**
 * Resolves ONE [Seq3StateInvariant] to the `(emission index, line)` pairs it draws — see this
 * section's own header for the "every drawn row, not just one anchor" reasoning. Mirrors
 * `buildStateInvariantBoxes` (Seq3Layout.kt) exactly: same "one row standing for MORE occurrences
 * than it drew needs [collapsedStateInvariantValue]'s summary, everything else is a plain
 * per-occurrence lookup" rule, just walking [Seq3EmissionPlan.emissions] instead of already-placed
 * row geometry — the two files stay independent copies by this package's own design (this file's
 * header: Seq3Emitters is phase-1, not restructured around Seq3Layout's shape), so this is
 * deliberately its own small function rather than a shared call between the two.
 *
 * A dangling [Seq3StateInvariant.messageId] (names no message in the document, or one whose
 * `fromLifelineId` is hidden/unresolvable) or a [Seq3StateInvariant.captureName] no longer present
 * on any visible occurrence both resolve to an empty list — the same "never throw" contract
 * `buildStateInvariantBoxes` documents for itself.
 */
private fun stateInvariantLinesForOne(
    invariant: Seq3StateInvariant,
    messagesById: Map<String, Seq3Message>,
    emissionIndicesByMessage: Map<String, List<Int>>,
    plan: Seq3EmissionPlan,
): List<Pair<Int, Seq3StateInvariantLine>> {
    val message = messagesById[invariant.messageId] ?: return emptyList()
    val lifelineIdx = plan.lifelineIndex[message.fromLifelineId] ?: return emptyList()
    val indices = emissionIndicesByMessage[invariant.messageId].orEmpty()
    val visibleOccurrences = message.occurrences.filter { it.visibility == Seq3Visibility.VISIBLE }
    // See this section's own header / buildStateInvariantBoxes' identical doc for exactly why this
    // predicate (not a per-emission-type `when`) is the right "does this one row stand for more
    // than one occurrence" test.
    val collapsed = indices.size == 1 && visibleOccurrences.size > 1
    return indices.mapNotNull { index ->
        val text = if (collapsed) {
            collapsedStateInvariantValue(invariant.captureName, visibleOccurrences)
        } else {
            val entryId = plan.emissions[index].occurrenceEntryId ?: return@mapNotNull null
            visibleOccurrences.firstOrNull { it.entryId == entryId }?.captureValues?.get(invariant.captureName)
        } ?: return@mapNotNull null
        index to Seq3StateInvariantLine(lifelineIdx, "${invariant.captureName}=$text")
    }
}

/** Every VISIBLE [Seq3Document.stateInvariants], resolved and grouped by the emission index each
 *  line is written at — keyed the same way [toMermaid]/[toPlantUml]'s own `notesByAnchor`/
 *  `delaysByAnchor` already are, so the emit loop can look lines up by its own `forEachIndexed`
 *  index `i`. */
private fun stateInvariantLinesByAnchor(document: Seq3Document, plan: Seq3EmissionPlan): Map<Int, List<Seq3StateInvariantLine>> {
    if (document.stateInvariants.isEmpty()) return emptyMap()
    val messagesById = document.messages.associateBy { it.id }
    val emissionIndicesByMessage = plan.emissions.withIndex().groupBy({ it.value.messageId }, { it.index })
    return document.stateInvariants
        .filter { it.visibility == Seq3Visibility.VISIBLE }
        .flatMap { invariant -> stateInvariantLinesForOne(invariant, messagesById, emissionIndicesByMessage, plan) }
        .groupBy({ it.first }, { it.second })
}

/** Shared append loop only — NOT a merge of the two dialects' own formatting (this file's header:
 *  DO NOT unify per-dialect branches). [lineFor] is the one dialect-specific piece, passed in by
 *  reference at each call site, exactly like [appendDividerLines]'s own `dividerLineFor`. Pulled out
 *  of `toMermaid`/`toPlantUml` themselves purely to keep both under detekt's CyclomaticComplexMethod
 *  threshold — the same reason [appendDividerLines]/[appendActivationLines] already exist as their
 *  own functions rather than an inline `?.forEach` at the call site. */
private fun StringBuilder.appendStateInvariantLines(
    linesByAnchor: Map<Int, List<Seq3StateInvariantLine>>,
    i: Int,
    lineFor: (Seq3StateInvariantLine) -> String,
) {
    linesByAnchor[i]?.forEach { line -> append(lineFor(line)).append('\n') }
}

// ── Activation bars (WP3) ────────────────────────────────────────────────────────────────────
//
// `Seq3Activation.kt`'s `seq3ActivationSpans` is the ONE shared call/return pairing this file and
// `Seq3Layout.kt` both build on — see that file's own header for why it must be a single function
// rather than two independent stack machines. [activationEventOf] below is this file's twin of
// `Seq3Layout.kt`'s private `activationEventOf`: same neutral treatment of every non-arrow-with-
// -target row (a [Seq3Emission.NeedsTarget] stub, a [Seq3Emission.NoteLine], an [Seq3Emission
// .Elided] marker) folded onto `Seq3Kind.NOTE` so `seq3ActivationSpans` treats it as a no-op push
// /pop — none of them are skipped outright, because skipping would shift every later emission's
// row index out from under the spans `seq3ActivationSpans` computes for it. `visibleLifelines` is
// the same list `toMermaid`/`toPlantUml` already build (see `planEmissions`'s own comment on why
// its index-for-index alignment with `plan.lifelineIndex` is trustworthy), reused here purely to
// turn a [Seq3Emission.Arrow]'s `fromIdx`/`toIdx` (index space) back into the lifeline id string
// [Seq3ActivationEvent] wants.
// Unchecked `visibleLifelines[...]` below, deliberately — unlike `aliasOf`'s `getOrElse` a few
// screens down, an out-of-range fromIdx/toIdx/participantIdx here is NOT reachable, so a
// defensive fallback would hide a real bug instead of guarding against one:
//   - Every Seq3Emission's index fields are produced by `expandMessage` from THIS SAME call's
//     `lifelineIndex[message.fromLifelineId]`/`[message.toLifelineId]` (planEmissions' own local
//     `visibleLifelines`, `document.lifelines.filter{VISIBLE}.sortedBy{ordinal}`) — a message
//     whose lifeline id is NOT in that map short-circuits to `emptyList()`/the NeedsTarget branch
//     before an Arrow with that index is ever built (see expandMessage's early returns), so every
//     emitted index is guaranteed to be a valid key into that exact map.
//   - `toMermaid`/`toPlantUml` build the `visibleLifelines` passed in HERE via the byte-identical
//     `lifelines.filter{VISIBLE}.sortedBy{ordinal}` expression over the same immutable `this`
//     document, right after calling `planEmissions(this)` — no mutation happens in between, and
//     `filter`/`sortedBy` are pure and stable, so this list is element-for-element identical (same
//     order, same size) to the one `lifelineIndex` was built from. The two are always in the same
//     index space.
private fun activationEventOf(index: Int, emission: Seq3Emission, visibleLifelines: List<Seq3Lifeline>): Seq3ActivationEvent = when (emission) {
    is Seq3Emission.Arrow ->
        Seq3ActivationEvent(index, emission.messageId, emission.kind, visibleLifelines[emission.fromIdx].id, visibleLifelines[emission.toIdx].id)
    is Seq3Emission.NeedsTarget ->
        Seq3ActivationEvent(index, emission.messageId, Seq3Kind.NOTE, visibleLifelines[emission.fromIdx].id, null)
    is Seq3Emission.NoteLine ->
        Seq3ActivationEvent(index, emission.messageId, Seq3Kind.NOTE, visibleLifelines[emission.participantIdx].id, null)
    is Seq3Emission.Elided ->
        Seq3ActivationEvent(index, emission.messageId, Seq3Kind.NOTE, visibleLifelines[emission.participantIdx].id, null)
}

/** [activateAt]/[deactivateAt]: activation spans grouped by their start/end emission index — the
 *  same index space [toMermaid]/[toPlantUml] already walk `plan.emissions.forEachIndexed` over,
 *  so a caller keys straight off the loop's own `i`, exactly like `opens`/`closes`/`notesByAnchor`
 *  /`delaysByAnchor`. */
private class Seq3ActivationMaps(val activateAt: Map<Int, List<Seq3ActivationSpan>>, val deactivateAt: Map<Int, List<Seq3ActivationSpan>>)

/**
 * Gated on [Seq3Document.showActivations] so a document with the feature off produces two empty
 * maps and neither dialect's output changes by a single byte from before this work package — the
 * default-off contract every other WP10/WP11 toggle in this file already keeps.
 *
 * Every span [seq3ActivationSpans] returns is built from events at indices `0..plan.emissions
 * .lastIndex` (one event per emission, via `mapIndexed`), so its `startIndex`/`endIndex` are
 * always in range — the `filter` below can never actually drop anything from THIS call site. It
 * stays anyway as this package's usual posture on a geometry/index lookup that could in principle
 * fail (see `Seq3Layout.kt`'s `buildActivationBars`, which keeps an identical "not expected to be
 * reachable" `mapNotNull` for the same reason): emitting only one half of a span — an `activate`
 * with no matching `deactivate`, or vice versa — is not a cosmetic wart, it is a Mermaid PARSE
 * ERROR, so "drop the whole span" is the only acceptable failure mode here, never "emit half".
 */
private fun activationMaps(document: Seq3Document, plan: Seq3EmissionPlan, visibleLifelines: List<Seq3Lifeline>): Seq3ActivationMaps {
    if (!document.showActivations) return Seq3ActivationMaps(emptyMap(), emptyMap())
    val events = plan.emissions.mapIndexed { index, emission -> activationEventOf(index, emission, visibleLifelines) }
    val spans = seq3ActivationSpans(events, plan.emissions.lastIndex)
        .filter { it.startIndex in plan.emissions.indices && it.endIndex in plan.emissions.indices }
    return Seq3ActivationMaps(spans.groupBy { it.startIndex }, spans.groupBy { it.endIndex })
}

// `activate <alias>` / `deactivate <alias>` — unlike GROUP fragments or delays elsewhere in this
// file, NEITHER dialect lacks this construct, so unlike `mermaidFragmentOpenLines`/
// `plantUmlFragmentOpenLines` there is no per-dialect fallback to branch on. The two call sites
// differ only in indentation (Mermaid indents every body line 4 spaces; PlantUML indents nothing
// — see `toPlantUml`'s existing arrow/note/fragment lines), so that's the only thing left to the
// caller; the keyword + alias text itself is written once, here.
//
// Deliberately NOT the `->>+`/`-->>-` activate-shorthand suffix both dialects also support: a
// separate `activate`/`deactivate` line is dialect-symmetric and, decisively, never touches the
// arrow-token `when` blocks in [toMermaid]/[toPlantUml] — exactly the code this package's own
// comments flag as having drifted between dialects before (see this file's header on
// `Seq3ArrowStyle.kt`). Folding activation into the arrow token would mean re-deriving, at the
// arrow's own emission index, spans keyed by a DIFFERENT (start/end) index — needless coupling for
// no reader-visible difference in the emitted diagram.
private fun activationKeywordLine(activate: Boolean, alias: String): String = "${if (activate) "activate" else "deactivate"} $alias"

/**
 * Writes the activation lines for emission index [i], applying [indent] to each — shared by both
 * dialects (see [activationKeywordLine]'s own doc). Within each of the three groups below, spans
 * are ordered by [Seq3ActivationSpan.depth] — deactivate deepest-first, activate shallowest-first
 * — mirroring the existing fragment close/open ordering just above this function
 * (`closes[i]?.sortedByDescending { it.depth }` / `opens[i]?.sortedBy { it.depth }`) purely for
 * deterministic, readable output; balance itself does not depend on this ordering.
 *
 * The groups at index [i] emit in this order, and the order is not cosmetic — getting it wrong is
 * either "a bar nests where it shouldn't" or "Mermaid refuses to parse the output":
 *   1. **deactivate spans that opened on an earlier row** (`startIndex < i`). A `RETURN` that
 *      closes a bar on some lifeline L, immediately followed by a fresh `CALL` re-entering L, must
 *      close the old bar before opening the new one: emitting `activate L` first would nest the
 *      new bar inside the one that just ended, drawing a bar the source diagram never showed.
 *   2. **activate spans opening at [i].**
 *   3. **deactivate spans that ALSO opened at [i]** (`startIndex == endIndex == i`) — a
 *      zero-length span. [seq3ActivationSpans]' rule 1 produces these whenever an unmatched call
 *      closes on its own row, which is the ordinary case for a trailing or leaf call (nothing
 *      touches its lifeline afterwards) — and `Seq3Generator.generateSeq3` never mints a `RETURN`,
 *      so a freshly generated document is all `CALL`s, making trailing unmatched calls the normal
 *      case, not an edge case. Group 1's "close before open" reasoning assumed the close belongs
 *      to a span that started on an EARLIER row than the one now opening; a zero-length span
 *      breaks that premise — it cannot be closed before its own open has been emitted. Running
 *      this group last, after group 2's activate, is the only ordering where a zero-length span's
 *      `activate`/`deactivate` pair is emitted open-then-close. Emitting it deactivate-before-
 *      activate (grouping ALL same-index deactivates before ALL same-index activates, as this
 *      function used to) issues a `deactivate` for a bar that was never opened — Mermaid tracks
 *      activation as a real stack and rejects that outright — which is why the pre-fix version of
 *      this function produced a broken export on essentially every real document with the
 *      activation toggle on.
 */
private fun StringBuilder.appendActivationLines(maps: Seq3ActivationMaps, i: Int, plan: Seq3EmissionPlan, aliases: List<String>, indent: String) {
    fun aliasOfLifeline(lifelineId: String): String? = plan.lifelineIndex[lifelineId]?.let { aliases.getOrNull(it) }
    fun emit(activate: Boolean, span: Seq3ActivationSpan) {
        val alias = aliasOfLifeline(span.lifelineId) ?: return
        append(indent).append(activationKeywordLine(activate = activate, alias = alias)).append('\n')
    }
    val (zeroLength, closingOlder) = maps.deactivateAt[i].orEmpty().partition { it.startIndex == i }
    closingOlder.sortedByDescending { it.depth }.forEach { emit(activate = false, span = it) }
    maps.activateAt[i]?.sortedBy { it.depth }?.forEach { emit(activate = true, span = it) }
    zeroLength.sortedByDescending { it.depth }.forEach { emit(activate = false, span = it) }
}

// ── WP10: create/destroy directives ─────────────────────────────────────────────────────────

/**
 * For each lifeline index, the emission index of the `create`/`destroy` directive text should
 * attach to. Mirrors `Seq3Layout`'s own "which row creates/destroys this lifeline" resolution
 * exactly (same FIRST-wins for create, LAST-wins for destroy — see that file's doc for why:
 * truncating early would hide rows drawn after a stray earlier destroy, so the picture and the
 * exported text must agree on the same authoritative row or they'd silently disagree about when a
 * lifeline ends), so the emitted `create`/`destroy` keyword always lands on the identical message
 * that the drawn geometry treats as authoritative.
 */
private class Seq3LifecycleMaps(val createAt: Map<Int, Int>, val destroyAt: Map<Int, Int>)

private fun lifecycleMaps(plan: Seq3EmissionPlan): Seq3LifecycleMaps {
    val createAt = mutableMapOf<Int, Int>()
    val destroyAt = mutableMapOf<Int, Int>()
    plan.emissions.forEachIndexed { i, emission ->
        if (emission !is Seq3Emission.Arrow) return@forEachIndexed
        when (emission.kind) {
            Seq3Kind.CREATE -> createAt.putIfAbsent(emission.toIdx, i) // first CREATE wins, later ones ignored
            Seq3Kind.DESTROY -> destroyAt[emission.toIdx] = i // keep overwriting: last DESTROY wins
            else -> Unit
        }
    }
    return Seq3LifecycleMaps(createAt, destroyAt)
}

/** [toMermaid]'s per-lifeline header declaration line — pulled out purely to keep that function's
 *  own Cyclomatic Complexity under this file's detekt threshold (the same reason
 *  [appendDividerLines]/[appendActivationLines] already exist as their own functions rather than
 *  inlined into both dialect functions). Skips the CREATEd lifeline entirely: see the call site's
 *  own WP10 comment for why (Mermaid parses a participant that is BOTH header-declared and later
 *  `create`d as an error). */
private fun StringBuilder.appendMermaidParticipantDeclaration(
    i: Int,
    l: Seq3Lifeline,
    lifecycle: Seq3LifecycleMaps,
    aliases: List<String>,
    lifelineDisplaySegments: Int,
) {
    if (lifecycle.createAt.containsKey(i)) return
    // Item: ACTOR lifelines emit Mermaid's own `actor` keyword instead of `participant` — purely a
    // glyph/export-keyword choice (Seq3LifelineKind's own doc), and the resolved display name
    // (per-lifeline override, else the document default) rather than the raw name, matching what
    // the header chip/glyph actually shows on screen.
    val keyword = if (l.kind == Seq3LifelineKind.ACTOR) "actor" else "participant"
    val displayName = seq3DisplayName(l.name, l.displaySegments, lifelineDisplaySegments)
    append("    ").append(keyword).append(' ').append(aliases[i]).append(" as ").append(mermaidEscape(displayName)).append('\n')
}

/** [toMermaid]'s per-row `create`/`destroy` directive lines — see that function's own call site
 *  comment for the exact grammar this follows. Pulled into its own function for the identical
 *  CyclomaticComplexMethod reason as [appendMermaidParticipantDeclaration] above. */
private fun StringBuilder.appendMermaidLifecycleDirectives(
    lifecycle: Seq3LifecycleMaps,
    i: Int,
    toIdx: Int,
    visibleLifelines: List<Seq3Lifeline>,
    lifelineDisplaySegments: Int,
    aliasOf: (Int) -> String,
) {
    if (lifecycle.createAt[toIdx] == i) {
        val created = visibleLifelines[toIdx]
        val createdKeyword = if (created.kind == Seq3LifelineKind.ACTOR) "actor" else "participant"
        val createdName = seq3DisplayName(created.name, created.displaySegments, lifelineDisplaySegments)
        append("    create ").append(createdKeyword).append(' ').append(aliasOf(toIdx))
            .append(" as ").append(mermaidEscape(createdName)).append('\n')
    }
    if (lifecycle.destroyAt[toIdx] == i) {
        append("    destroy ").append(aliasOf(toIdx)).append('\n')
    }
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
    // WP5: resolved against the CLAMPED `brackets` above, not raw fragment bounds — see
    // operandDividersByAnchor's own "THE TRAP" doc for why that distinction is load-bearing.
    val dividersByAnchor = operandDividersByAnchor(brackets, plan)
    val notesByAnchor = visibleNotes.mapNotNull { note -> noteAnchorIndex(note, plan)?.let { it to note } }.groupBy({ it.first }, { it.second })
    val visibleDelays = delays.filter { it.visibility == Seq3Visibility.VISIBLE }
    val delaysByAnchor = visibleDelays.mapNotNull { d -> delayAnchorIndex(d, plan)?.let { it to d } }.groupBy({ it.first }, { it.second })
    // WP18: see this file's own "State invariants" header for the degraded-note shape.
    val stateInvariantsByAnchor = stateInvariantLinesByAnchor(this, plan)
    val activations = activationMaps(this, plan, visibleLifelines)
    // WP10: which emission index owns the `create`/`destroy` directive text for each lifeline.
    val lifecycle = lifecycleMaps(plan)

    fun aliasOf(idx: Int) = aliases.getOrElse(idx) { "p$idx" }

    return buildString {
        append("sequenceDiagram\n")
        if (title.isNotBlank()) append("    title ").append(mermaidEscape(title)).append('\n')
        visibleLifelines.forEachIndexed { i, l ->
            appendMermaidParticipantDeclaration(i, l, lifecycle, aliases, lifelineDisplaySegments)
        }
        plan.emissions.forEachIndexed { i, emission ->
            opens[i]?.sortedBy { it.depth }?.forEach { b ->
                mermaidFragmentOpenLines(b, plan, aliases).forEach { line -> append("    ").append(line).append('\n') }
            }
            // WP5: an operand's divider begins AT the message it anchors to, so it is written
            // after any fragment that opens at this same index (the divider's own fragment
            // included, when its own operand zero starts here) and before the emission's own line.
            appendDividerLines(dividersByAnchor, i, indent = "    ", dividerLineFor = ::mermaidFragmentDividerLine)
            when (emission) {
                is Seq3Emission.Arrow -> {
                    // WP10: Mermaid's own grammar requires BOTH `create ...` and `destroy ...` to
                    // appear on the line immediately BEFORE the message that creates/destroys the
                    // participant (confirmed against mermaid-js's sequenceDiagram.jison: `destroy`
                    // is `'destroy' actor 'NEWLINE'` with no trailing message clause of its own —
                    // it is always its own statement ahead of the next one) — never after, unlike
                    // PlantUML below. Extracted into its own function (like appendMermaidParticipant
                    // Declaration above) purely to keep toMermaid's own Cyclomatic Complexity under
                    // this file's detekt threshold.
                    appendMermaidLifecycleDirectives(lifecycle, i, emission.toIdx, visibleLifelines, lifelineDisplaySegments, ::aliasOf)
                    // No `else`: exhaustive on purpose (WP8) so a new Seq3Kind forces a decision here
                    // instead of silently inheriting the plain "->>" arrow token meant for CALL.
                    val arrow = when (emission.kind) {
                        Seq3Kind.RETURN -> "-->>"
                        Seq3Kind.ASYNC -> "-)"
                        // LOST/FOUND never actually reach an Arrow emission — expandMessage routes
                        // any message whose `toLifelineId` is null (which LOST/FOUND always are,
                        // by construction) to NeedsTarget instead, and that's where their real
                        // "· lost"/"· found" text lives, just below. This branch only exists
                        // because Seq3Emission.Arrow.kind's type can't statically rule out a
                        // document where a LOST/FOUND message was somehow left with a resolved
                        // `toLifelineId` (Seq3Message never enforces that as an invariant) — a
                        // defensive fallback for that inconsistent state, not the intended path,
                        // so it reads as a plain solid call rather than a crash.
                        //
                        // WP10: CREATE reuses RETURN's dashed-open-arrowhead token because
                        // `seq3ArrowStyle` already decided CREATE has that exact shape (see that
                        // file's own WP10 comment) — the text dialects and the picture must draw
                        // the same arrow, not two independently-chosen ones. DESTROY reuses CALL's
                        // plain filled-arrowhead token for the identical reason: `seq3ArrowStyle`
                        // gave DESTROY the ordinary CALL shape, since the "this is a destroy" signal
                        // is the X drawn on the lifeline (and the `destroy` directive here), not a
                        // distinct arrowhead.
                        Seq3Kind.CREATE -> "-->>"
                        Seq3Kind.CALL, Seq3Kind.SELF, Seq3Kind.NOTE, Seq3Kind.LOST, Seq3Kind.FOUND, Seq3Kind.DESTROY -> "->>"
                    }
                    val label = mermaidEscape(emission.label) + repeatSuffix(emission.repeatCount)
                    append("    ").append(aliasOf(emission.fromIdx)).append(arrow).append(aliasOf(emission.toIdx)).append(": ").append(label).append('\n')
                }
                is Seq3Emission.NeedsTarget ->
                    append("    Note right of ").append(aliasOf(emission.fromIdx)).append(": ")
                        .append(mermaidEscape(emission.label)).append(mermaidNeedsTargetSuffix(emission.kind)).append('\n')
                is Seq3Emission.NoteLine ->
                    append("    Note over ").append(aliasOf(emission.participantIdx)).append(": ").append(mermaidEscape(emission.text)).append('\n')
                is Seq3Emission.Elided ->
                    append("    Note right of ").append(aliasOf(emission.participantIdx)).append(": ⋯ ×").append(emission.count).append(" elided\n")
            }
            // Activation open/close ordering (not a flat deactivate-before-activate rule — see
            // appendActivationLines' own doc) — must run right after the emission's own line and
            // before any note anchored to the same index.
            appendActivationLines(activations, i, plan, aliases, indent = "    ")
            notesByAnchor[i]?.forEach { note ->
                append("    Note over ").append(noteSpan(note, plan, aliases)).append(": ").append(mermaidEscape(note.text)).append('\n')
            }
            // WP18: degraded StateInvariant marker — see this file's own "State invariants" header.
            appendStateInvariantLines(stateInvariantsByAnchor, i) { line -> "    Note over ${aliasOf(line.lifelineIdx)}: {${mermaidEscape(line.text)}}" }
            closes[i]?.sortedByDescending { it.depth }?.forEach { append("    end\n") }
            // WP11: Mermaid has no delay/spacer construct — see this file's own "Time-gap markers"
            // header for why this stays a `Note over`, never "unified" with PlantUML's `...` below.
            delaysByAnchor[i]?.forEach { d ->
                append("    Note over ").append(delaySpan(aliases)).append(": ").append(mermaidEscape(d.label)).append('\n')
            }
        }
    }
}

/** [toPlantUml]'s per-lifeline header declaration line — see [appendMermaidParticipantDeclaration]
 *  above for the identical CyclomaticComplexMethod reason this is its own function, and the
 *  call site's own WP10 comment for why a CREATEd lifeline is skipped here too (structural
 *  parallelism with Mermaid, even though PlantUML itself does not require it). */
private fun StringBuilder.appendPlantUmlParticipantDeclaration(
    i: Int,
    l: Seq3Lifeline,
    lifecycle: Seq3LifecycleMaps,
    aliases: List<String>,
    lifelineDisplaySegments: Int,
) {
    if (lifecycle.createAt.containsKey(i)) return
    // Same ACTOR-vs-participant keyword and resolved-display-name treatment as toMermaid.
    val keyword = if (l.kind == Seq3LifelineKind.ACTOR) "actor" else "participant"
    val displayName = seq3DisplayName(l.name, l.displaySegments, lifelineDisplaySegments)
    append(keyword).append(" \"").append(plantUmlEscape(displayName)).append("\" as ").append(aliases[i]).append('\n')
}

/** [toPlantUml]'s `create <alias>` line, BEFORE the creating message — see that function's own
 *  call site comment for the exact PlantUML convention this follows and why it is two separate
 *  one-line functions rather than one combined helper like Mermaid's
 *  [appendMermaidLifecycleDirectives] (PlantUML's create/destroy straddle the arrow line instead
 *  of both sitting before it). */
private fun StringBuilder.appendPlantUmlCreateDirective(lifecycle: Seq3LifecycleMaps, i: Int, toIdx: Int, aliasOf: (Int) -> String) {
    if (lifecycle.createAt[toIdx] == i) append("create ").append(aliasOf(toIdx)).append('\n')
}

/** [toPlantUml]'s `destroy <alias>` line, AFTER the destroying message — see
 *  [appendPlantUmlCreateDirective] just above for why this is its own function too. */
private fun StringBuilder.appendPlantUmlDestroyDirective(lifecycle: Seq3LifecycleMaps, i: Int, toIdx: Int, aliasOf: (Int) -> String) {
    if (lifecycle.destroyAt[toIdx] == i) append("destroy ").append(aliasOf(toIdx)).append('\n')
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
    // WP5: resolved against the CLAMPED `brackets` above, not raw fragment bounds — see
    // operandDividersByAnchor's own "THE TRAP" doc for why that distinction is load-bearing.
    val dividersByAnchor = operandDividersByAnchor(brackets, plan)
    val notesByAnchor = visibleNotes.mapNotNull { note -> noteAnchorIndex(note, plan)?.let { it to note } }.groupBy({ it.first }, { it.second })
    val visibleDelays = delays.filter { it.visibility == Seq3Visibility.VISIBLE }
    val delaysByAnchor = visibleDelays.mapNotNull { d -> delayAnchorIndex(d, plan)?.let { it to d } }.groupBy({ it.first }, { it.second })
    // WP18: see this file's own "State invariants" header for the degraded-note shape — same map,
    // same rule, as toMermaid, so the two dialects never disagree on which row a marker attaches to.
    val stateInvariantsByAnchor = stateInvariantLinesByAnchor(this, plan)
    val activations = activationMaps(this, plan, visibleLifelines)
    // WP10: which emission index owns the `create`/`destroy` directive text for each lifeline —
    // same map, same rule, as toMermaid, so the two dialects never disagree on which row is the
    // authoritative creation/destruction point.
    val lifecycle = lifecycleMaps(plan)

    fun aliasOf(idx: Int) = aliases.getOrElse(idx) { "p$idx" }

    return buildString {
        append("@startuml\n")
        if (title.isNotBlank()) append("title ").append(plantUmlEscape(title)).append('\n')
        visibleLifelines.forEachIndexed { i, l ->
            appendPlantUmlParticipantDeclaration(i, l, lifecycle, aliases, lifelineDisplaySegments)
        }
        plan.emissions.forEachIndexed { i, emission ->
            opens[i]?.sortedBy { it.depth }?.forEach { b ->
                plantUmlFragmentOpenLines(b, plan, aliases).forEach { line -> append(line).append('\n') }
            }
            // WP5: an operand's divider begins AT the message it anchors to — see toMermaid's
            // identical comment just above its own dividersByAnchor block.
            appendDividerLines(dividersByAnchor, i, indent = "", dividerLineFor = ::plantUmlFragmentDividerLine)
            when (emission) {
                is Seq3Emission.Arrow -> {
                    // WP10: PlantUML's own convention (confirmed against plantuml.com's own
                    // "Participant creation"/"Lifeline Activation and Destruction" sections) places
                    // `create <alias>` on the line BEFORE the message that creates it, and
                    // `destroy <alias>` on the line AFTER the message that destroys it — the
                    // opposite ordering from Mermaid's `destroy` (which is always pre-message), so
                    // this is deliberately not a shared helper with toMermaid's version — split into
                    // two tiny one-line functions (below the arrow line too) purely to keep
                    // toPlantUml's own Cyclomatic Complexity under this file's detekt threshold.
                    appendPlantUmlCreateDirective(lifecycle, i, emission.toIdx, ::aliasOf)
                    // No `else`: exhaustive on purpose (WP8) so a new Seq3Kind forces a decision here
                    // instead of silently inheriting the plain "->" arrow token meant for CALL.
                    val arrow = when (emission.kind) {
                        Seq3Kind.RETURN -> "-->"
                        Seq3Kind.ASYNC -> "->>"
                        // See toMermaid's identical branch: structurally unreachable (LOST/FOUND
                        // always take the NeedsTarget path just below, which is where their real
                        // `->o]`/`[o->` gate syntax is emitted) — a defensive fallback only, kept
                        // here because Seq3Emission.Arrow.kind's type doesn't itself rule it out.
                        //
                        // WP10: same reasoning as toMermaid's identical branch — CREATE reuses
                        // RETURN's dashed token (`seq3ArrowStyle` gave it that exact shape), DESTROY
                        // reuses CALL's plain token (the `destroy` directive/X carries the meaning,
                        // not the arrowhead).
                        Seq3Kind.CREATE -> "-->"
                        Seq3Kind.CALL, Seq3Kind.SELF, Seq3Kind.NOTE, Seq3Kind.LOST, Seq3Kind.FOUND, Seq3Kind.DESTROY -> "->"
                    }
                    val label = plantUmlEscape(emission.label) + repeatSuffix(emission.repeatCount)
                    append(aliasOf(emission.fromIdx)).append(' ').append(arrow).append(' ')
                        .append(aliasOf(emission.toIdx)).append(": ").append(label).append('\n')
                    appendPlantUmlDestroyDirective(lifecycle, i, emission.toIdx, ::aliasOf)
                }
                is Seq3Emission.NeedsTarget -> appendPlantUmlNeedsTargetLine(emission, ::aliasOf)
                is Seq3Emission.NoteLine ->
                    append("note right of ").append(aliasOf(emission.participantIdx)).append(": ").append(plantUmlEscape(emission.text)).append('\n')
                is Seq3Emission.Elided ->
                    append("note right of ").append(aliasOf(emission.participantIdx)).append(": ⋯ ×").append(emission.count).append(" elided\n")
            }
            // Activation open/close ordering (not a flat deactivate-before-activate rule — see
            // appendActivationLines' own doc) — must run right after the emission's own line and
            // before any note anchored to the same index.
            appendActivationLines(activations, i, plan, aliases, indent = "")
            notesByAnchor[i]?.forEach { note ->
                append("note over ").append(noteSpan(note, plan, aliases)).append(": ").append(plantUmlEscape(note.text)).append('\n')
            }
            // WP18: degraded StateInvariant marker — see this file's own "State invariants" header.
            // PlantUML gets no real construct either (that section's own doc), so this reuses the
            // same "note right of ONE lifeline" fallback shape [Seq3Emission.NoteLine]'s own
            // PlantUML branch already uses just above, not `note over` — a state invariant decorates
            // exactly one participant, never a span.
            appendStateInvariantLines(stateInvariantsByAnchor, i) { line -> "note right of ${aliasOf(line.lifelineIdx)}: {${plantUmlEscape(line.text)}}" }
            // WP17: REF is skipped here, never PlantUML's own generic `end\n` — real PlantUML's
            // `ref over A, B : label` (plantUmlFragmentOpenLines) is a standalone statement, not a
            // block that encloses other statements the way alt/loop/opt/par/critical/group/neg/
            // strict/consider/ignore all are (PlantUML invented `group`, and needs no special case
            // for it either, for exactly that block shape — see Seq3FragmentKind's own doc).
            // Writing `end` after a `ref over` line would be an unmatched, unparseable token; the
            // bracketed messages themselves are still emitted normally just below, in order — only
            // the (nonexistent) closing keyword is what's skipped.
            closes[i]?.sortedByDescending { it.depth }?.forEach { b ->
                if (b.fragment.kind != Seq3FragmentKind.REF) append("end\n")
            }
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
