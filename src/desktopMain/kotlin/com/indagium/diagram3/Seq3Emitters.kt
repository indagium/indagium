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

    data class Arrow(
        override val messageId: String,
        val fromIdx: Int,
        val toIdx: Int,
        val label: String,
        val kind: Seq3Kind,
        val repeatCount: Int,
    ) : Seq3Emission()

    data class NeedsTarget(override val messageId: String, val fromIdx: Int, val label: String) : Seq3Emission()

    data class NoteLine(override val messageId: String, val participantIdx: Int, val text: String) : Seq3Emission()

    data class Elided(override val messageId: String, val participantIdx: Int, val count: Int) : Seq3Emission()
}

// The collapsed/multi-occurrence label keeps `{name}` slots visible (the templated form); a
// single rendered arrow substitutes that occurrence's real captured values back in — the same
// distinction the design spec draws between a queue row's pattern and an individual occurrence.
private fun templatedLabel(message: Seq3Message): String = message.labelTemplate

private fun occurrenceLabel(message: Seq3Message, occurrence: Seq3Occurrence): String {
    if (message.match.captures.isEmpty()) return message.labelTemplate
    var label = message.labelTemplate
    message.match.captures.forEach { capture ->
        val value = occurrence.captureValues[capture.name] ?: return@forEach
        label = label.replace("{${capture.name}}", value)
    }
    return label
}

private fun expandMessage(message: Seq3Message, lifelineIndex: Map<String, Int>): List<Seq3Emission> {
    val fromIdx = lifelineIndex[message.fromLifelineId] ?: return emptyList()
    if (message.kind == Seq3Kind.NOTE) {
        return listOf(Seq3Emission.NoteLine(message.id, fromIdx, templatedLabel(message)))
    }
    val toIdx = message.toLifelineId?.let(lifelineIndex::get)
    if (toIdx == null) {
        return listOf(Seq3Emission.NeedsTarget(message.id, fromIdx, templatedLabel(message)))
    }
    val occurrences = message.occurrences
    // Authored messages intentionally have no fabricated log occurrence. They still need one
    // drawable arrow in both source dialects, using the authored label and no evidence expansion.
    if (occurrences.isEmpty()) {
        return listOf(Seq3Emission.Arrow(message.id, fromIdx, toIdx, templatedLabel(message), message.kind, 1))
    }
    return when (message.repeat) {
        Seq3Repeat.EVERY -> occurrences.map { occ ->
            Seq3Emission.Arrow(message.id, fromIdx, toIdx, occurrenceLabel(message, occ), message.kind, 1)
        }
        Seq3Repeat.FIRST_LAST -> firstAndLastEmissions(message, fromIdx, toIdx, occurrences)
        Seq3Repeat.COLLAPSE_ABOVE -> if (occurrences.size > message.repeatThreshold) {
            listOf(Seq3Emission.Arrow(message.id, fromIdx, toIdx, templatedLabel(message), message.kind, occurrences.size))
        } else {
            occurrences.map { occ -> Seq3Emission.Arrow(message.id, fromIdx, toIdx, occurrenceLabel(message, occ), message.kind, 1) }
        }
    }
}

private fun firstAndLastEmissions(message: Seq3Message, fromIdx: Int, toIdx: Int, occurrences: List<Seq3Occurrence>): List<Seq3Emission> {
    if (occurrences.size <= 1) {
        return listOf(Seq3Emission.Arrow(message.id, fromIdx, toIdx, occurrenceLabel(message, occurrences.first()), message.kind, 1))
    }
    val elided = occurrences.size - 2
    return buildList {
        add(Seq3Emission.Arrow(message.id, fromIdx, toIdx, occurrenceLabel(message, occurrences.first()), message.kind, 1))
        if (elided > 0) add(Seq3Emission.Elided(message.id, fromIdx, elided))
        add(Seq3Emission.Arrow(message.id, fromIdx, toIdx, occurrenceLabel(message, occurrences.last()), message.kind, 1))
    }
}

// ── Flattened emission plan shared by both dialects ─────────────────────────────────────────

private class Seq3EmissionPlan(
    val lifelineIndex: Map<String, Int>,
    val emissions: List<Seq3Emission>,
    val firstIndexByMessage: Map<String, Int>,
    val lastIndexByMessage: Map<String, Int>,
)

private fun planEmissions(document: Seq3Document): Seq3EmissionPlan {
    val lifelineIndex = document.lifelines.withIndex().associate { (i, l) -> l.id to i }
    val emissions = mutableListOf<Seq3Emission>()
    val firstIndex = HashMap<String, Int>()
    val lastIndex = HashMap<String, Int>()
    document.messages.forEach { message ->
        if (message.visibility == Seq3Visibility.HIDDEN) return@forEach
        val expanded = expandMessage(message, lifelineIndex)
        if (expanded.isEmpty()) return@forEach
        firstIndex[message.id] = emissions.size
        emissions += expanded
        lastIndex[message.id] = emissions.size - 1
    }
    return Seq3EmissionPlan(lifelineIndex, emissions, firstIndex, lastIndex)
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
    val starts = fragment.messageIds.mapNotNull { plan.firstIndexByMessage[it] }
    val ends = fragment.messageIds.mapNotNull { plan.lastIndexByMessage[it] }
    if (starts.isEmpty() || ends.isEmpty()) return null
    return starts.min()..ends.max()
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

// ── Mermaid ──────────────────────────────────────────────────────────────────────────────────

fun Seq3Document.toMermaid(): String {
    val plan = planEmissions(this)
    val aliases = sanitizedAliases(lifelines)
    val brackets = normalizedBrackets(fragments, plan)
    val opens = brackets.groupBy { it.range.first }
    val closes = brackets.groupBy { it.range.last }
    val notesByAnchor = notes.mapNotNull { note -> noteAnchorIndex(note, plan)?.let { it to note } }.groupBy({ it.first }, { it.second })

    fun aliasOf(idx: Int) = aliases.getOrElse(idx) { "p$idx" }

    return buildString {
        append("sequenceDiagram\n")
        if (title.isNotBlank()) append("    title ").append(mermaidEscape(title)).append('\n')
        lifelines.forEachIndexed { i, l -> append("    participant ").append(aliases[i]).append(" as ").append(mermaidEscape(l.name)).append('\n') }
        plan.emissions.forEachIndexed { i, emission ->
            opens[i]?.sortedBy { it.depth }?.forEach { b ->
                append("    ").append(b.fragment.kind.name.lowercase()).append(' ').append(mermaidEscape(fragmentLabel(b.fragment))).append('\n')
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
        }
    }
}

// ── PlantUML ─────────────────────────────────────────────────────────────────────────────────

fun Seq3Document.toPlantUml(): String {
    val plan = planEmissions(this)
    val aliases = sanitizedAliases(lifelines)
    val brackets = normalizedBrackets(fragments, plan)
    val opens = brackets.groupBy { it.range.first }
    val closes = brackets.groupBy { it.range.last }
    val notesByAnchor = notes.mapNotNull { note -> noteAnchorIndex(note, plan)?.let { it to note } }.groupBy({ it.first }, { it.second })

    fun aliasOf(idx: Int) = aliases.getOrElse(idx) { "p$idx" }

    return buildString {
        append("@startuml\n")
        if (title.isNotBlank()) append("title ").append(plantUmlEscape(title)).append('\n')
        lifelines.forEachIndexed { i, l -> append("participant \"").append(plantUmlEscape(l.name)).append("\" as ").append(aliases[i]).append('\n') }
        plan.emissions.forEachIndexed { i, emission ->
            opens[i]?.sortedBy { it.depth }?.forEach { b ->
                append(b.fragment.kind.name.lowercase()).append(' ').append(plantUmlEscape(fragmentLabel(b.fragment))).append('\n')
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
