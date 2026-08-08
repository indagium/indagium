package com.indagium.diagram

// ── Mermaid / PlantUML text emitters ────────────────────────────────────────────────────────
//
// Both emitters share the same two hard problems: (1) turning a DiagramParticipant.id/label into
// a syntactically valid, collision-free alias for the target dialect, and (2) escaping arbitrary
// logcat message text (which routinely contains ':', '"', '<>', backticks, embedded newlines from
// multi-line stack traces, …) into that dialect's label position without breaking the parser.
// Both are handled once, defensively, on the FULL final participant/message list — never assume a
// SeqDiagram's DiagramParticipant.id already IS a valid alias (see DiagramParticipant's own doc:
// deriving/deduping aliases is deliberately deferred here rather than done in the builder).
//
// Frame rendering (DiagramFrame → bracket around a message range) intentionally differs per
// dialect rather than sharing one code path:
//   - PlantUML's `group Label ... end` is a generic block construct with no semantic baggage, and
//     PlantUML natively supports proper nesting, so a frame is emitted as a real nested block —
//     open in firstMsg order (outer before inner), close in reverse (inner before outer).
//   - Mermaid's nesting-capable block constructs (`rect`/`opt`/`loop`) DO carry semantic meaning
//     (opt = conditional, loop = repetition) that a "this was one auto-detected sequence" frame
//     doesn't actually have, and picking `rect` for color-carrying frames vs. `opt` for colorless
//     ones would need the same open/close bookkeeping as PlantUML PLUS a second failure mode: an
//     empty frame (open and close on the same message) produces a body-less block, which some
//     Mermaid renderers reject outright. `Note over X,Y: ▶ label` / `Note over X,Y: ◀ label`
//     pairs carry the same information, are valid standalone lines regardless of nesting depth or
//     emptiness, and can never desync into invalid syntax — traded off against not actually
//     drawing a box. That trade favors Mermaid here specifically because it's the DEFAULT dialect
//     (SeqDiagramSpec.dialect) and thus the one most likely to be pasted somewhere with a less
//     forgiving renderer (Jira's Mermaid macro included). It is also why Mermaid never needs
//     normalizeFramesForNesting below, a second concrete argument for the same trade: two frames
//     the builder computed independently can legitimately CROSS (SeqComputer.assignParents only
//     reparents on full containment, not overlap — two different SequenceDefs routinely produce
//     e.g. A=[0,10] and B=[5,20]), and a standalone `Note over ...: ▶/◀ label` line can't desync
//     into a mislabeled, mis-scoped bracket the way an open/close block pair can — there is
//     nothing to normalize for Mermaid in the first place, only for PlantUML's real blocks.

private val NON_IDENTIFIER_CHAR = Regex("[^A-Za-z0-9_]")

// Sanitizes every participant's id/label into a valid `[A-Za-z0-9_]` alias (both dialects accept
// the same charset) and dedupes with a numeric suffix — done ONCE over the whole list so two
// participants that only differ in punctuation (e.g. tags "Foo!" and "Foo?") can never collide
// silently into the same alias.
private fun sanitizedAliases(participants: List<DiagramParticipant>): List<String> {
    val used = HashSet<String>()
    return participants.map { p ->
        val seed = p.id.ifBlank { p.label }
        var base = seed.replace(NON_IDENTIFIER_CHAR, "_")
        if (base.isEmpty() || base[0].isDigit()) base = "p$base"
        var candidate = base
        var suffix = 2
        while (!used.add(candidate)) {
            candidate = "${base}_$suffix"
            suffix++
        }
        candidate
    }
}

private fun frameLabel(f: DiagramFrame): String = f.label.ifBlank { "sequence" }

// '×' (U+00D7) isn't in either dialect's escaped charset, so it's appended AFTER escaping the
// label itself — this only ever wraps the fold count collapseRepeats produced, never user text.
private fun repeatSuffix(count: Int): String = if (count > 1) " ×$count" else ""

// ── Mermaid ──────────────────────────────────────────────────────────────────────────────────

// Single pass over the ORIGINAL text, never re-scanning generated output — the only way to
// guarantee a literal '#' in a log message can't be re-interpreted as the start of one of the
// synthetic `#NN;` escapes this same pass just emitted for a DIFFERENT character. \r\n / lone \r
// are normalized to \n first so a Windows-captured log's line endings don't produce a doubled
// "<br/><br/>" for what is really one line break.
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

// "Note over A,B" spans every participant a frame's messages actually touch, using the leftmost/
// rightmost participant index among them as the two endpoints — Mermaid draws the note across
// every lifeline between those two, which is the closest a Note can get to bracketing a frame that
// (unlike PlantUML's group) has no real block extent of its own.
private fun mermaidNoteSpan(f: DiagramFrame, messages: List<DiagramMessage>, aliases: List<String>): String {
    val touched = (f.firstMsg..f.lastMsg)
        .flatMap { i -> messages.getOrNull(i)?.let { listOf(it.fromIdx, it.toIdx) }.orEmpty() }
        .distinct()
        .sorted()
    if (touched.isEmpty()) return aliases.getOrElse(0) { "p0" }
    val lo = aliases.getOrElse(touched.first()) { "p${touched.first()}" }
    val hi = aliases.getOrElse(touched.last()) { "p${touched.last()}" }
    return if (lo == hi) lo else "$lo,$hi"
}

fun SeqDiagram.toMermaid(): String {
    val aliases = sanitizedAliases(participants)
    fun aliasOf(idx: Int): String = aliases.getOrElse(idx) { "p$idx" }

    val opens = frames.groupBy { it.firstMsg }
    val closes = frames.groupBy { it.lastMsg }
    val errorNotesByMsg = notes.groupBy { it.afterMsg }

    return buildString {
        append("sequenceDiagram\n")
        if (spec.title.isNotBlank()) append("    title ").append(mermaidEscape(spec.title)).append('\n')
        participants.forEachIndexed { i, p ->
            val keyword = if (p.kind == ParticipantKind.ACTOR) "actor" else "participant"
            append("    ").append(keyword).append(' ').append(aliases[i])
                .append(" as ").append(mermaidEscape(p.label)).append('\n')
        }
        messages.forEachIndexed { i, msg ->
            opens[i]?.sortedBy { it.depth }?.forEach { f ->
                append("    Note over ").append(mermaidNoteSpan(f, messages, aliases)).append(": ")
                    .append("  ".repeat(f.depth)).append("▶ ").append(mermaidEscape(frameLabel(f))).append('\n')
            }
            val arrow = if (msg.kind == MessageKind.RETURN) "-->>" else "->>"
            val label = mermaidEscape(msg.label) + repeatSuffix(msg.repeatCount)
            append("    ").append(aliasOf(msg.fromIdx)).append(arrow).append(aliasOf(msg.toIdx)).append(": ").append(label).append('\n')
            errorNotesByMsg[i]?.forEach { note ->
                append("    Note over ").append(aliasOf(note.participantIdx)).append(": ").append(mermaidEscape(note.text)).append('\n')
            }
            closes[i]?.sortedByDescending { it.depth }?.forEach { f ->
                append("    Note over ").append(mermaidNoteSpan(f, messages, aliases)).append(": ")
                    .append("  ".repeat(f.depth)).append("◀ ").append(mermaidEscape(frameLabel(f))).append('\n')
            }
        }
    }
}

// ── PlantUML ─────────────────────────────────────────────────────────────────────────────────

// Same single-pass-over-original-text discipline as mermaidEscape, for the same reason: escaping
// '\\' before turning a real newline into the two-character "\n" token means a literal backslash
// already present in a message can never be mistaken, on a later read, for the start of that
// synthetic escape.
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

// buildFrames computes frames independently per top-level SeqGroup (and, one level down, per
// NestedSeqGroup) — nothing there guarantees the RESULT is a proper forest. SeqComputer.
// assignParents only reparents a group when an earlier candidate's [idx, endExclusive) range
// fully CONTAINS the later one; two ranges that merely CROSS (A=[0,10), B=[5,20) — neither
// containing the other) both come back as top-level roots. Emitting `group A / group B / end /
// end` for that pair is balanced (PlantUML's parser accepts it) but silently WRONG: the first
// `end` closes B, not A, so both brackets end up with the wrong extent and, visually, the wrong
// label attached to the wrong span — worse than a parse error, since nothing flags it.
//
// This walks the frames sorted by (firstMsg asc, lastMsg desc) — the same comparator Filter.kt's
// selectTopLevelManualRanges uses for the analogous manual-collapse-range problem — with an
// explicit stack: whichever frame is still open when a new one starts becomes that new frame's
// parent, and if the new frame would close after its parent does, it's clamped to close exactly
// when the parent does. depth is recomputed from the resulting stack height so the existing
// same-index open/close ordering (sortedBy/sortedByDescending on depth in toPlantUml below) stays
// correct against this NORMALIZED shape rather than whatever depth buildFrames originally
// assigned. A small, separately callable helper so this is obviously unit-testable on its own —
// see DiagramEmitterTest's crossing-frames case. Only toPlantUml calls this; see this file's
// header comment for why Mermaid's Note-based frames never need it.
private fun normalizeFramesForNesting(frames: List<DiagramFrame>): List<DiagramFrame> {
    if (frames.size <= 1) return frames
    val sorted = frames.sortedWith(compareBy<DiagramFrame> { it.firstMsg }.thenByDescending { it.lastMsg })
    val stack = ArrayDeque<DiagramFrame>()
    val result = ArrayList<DiagramFrame>(sorted.size)
    for (f in sorted) {
        // Pop any frame that has already closed (its clamped end precedes this one's start) —
        // it can no longer be anyone's parent.
        while (stack.isNotEmpty() && stack.last().lastMsg < f.firstMsg) stack.removeLast()
        val parent = stack.lastOrNull()
        // parent.lastMsg >= f.firstMsg is guaranteed by the pop above (or there's no parent at
        // all), so this can never clamp lastMsg below firstMsg.
        val clampedLastMsg = if (parent != null) minOf(f.lastMsg, parent.lastMsg) else f.lastMsg
        val normalized = f.copy(lastMsg = clampedLastMsg, depth = stack.size)
        result += normalized
        stack.addLast(normalized)
    }
    return result
}

fun SeqDiagram.toPlantUml(): String {
    val aliases = sanitizedAliases(participants)
    fun aliasOf(idx: Int): String = aliases.getOrElse(idx) { "p$idx" }

    val normalizedFrames = normalizeFramesForNesting(frames)
    val opens = normalizedFrames.groupBy { it.firstMsg }
    val closes = normalizedFrames.groupBy { it.lastMsg }
    val errorNotesByMsg = notes.groupBy { it.afterMsg }

    return buildString {
        append("@startuml\n")
        if (spec.title.isNotBlank()) append("title ").append(plantUmlEscape(spec.title)).append('\n')
        participants.forEachIndexed { i, p ->
            val keyword = if (p.kind == ParticipantKind.ACTOR) "actor" else "participant"
            append(keyword).append(" \"").append(plantUmlEscape(p.label)).append("\" as ").append(aliases[i]).append('\n')
        }
        messages.forEachIndexed { i, msg ->
            // group/end genuinely nests in PlantUML (unlike Mermaid's Note-based stand-in above),
            // so opens close in strict reverse (innermost first) to keep the block structure valid.
            opens[i]?.sortedBy { it.depth }?.forEach { f ->
                append("group ").append(plantUmlEscape(frameLabel(f))).append('\n')
            }
            val arrow = if (msg.kind == MessageKind.RETURN) "-->" else "->"
            val label = plantUmlEscape(msg.label) + repeatSuffix(msg.repeatCount)
            append(aliasOf(msg.fromIdx)).append(' ').append(arrow).append(' ').append(aliasOf(msg.toIdx)).append(": ").append(label).append('\n')
            errorNotesByMsg[i]?.forEach { note ->
                append("note right of ").append(aliasOf(note.participantIdx)).append(": ").append(plantUmlEscape(note.text)).append('\n')
            }
            closes[i]?.sortedByDescending { it.depth }?.forEach { append("end\n") }
        }
        append("@enduml\n")
    }
}

fun SeqDiagram.toSource(dialect: DiagramDialect = spec.dialect): String = when (dialect) {
    DiagramDialect.MERMAID -> toMermaid()
    DiagramDialect.PLANTUML -> toPlantUml()
}
