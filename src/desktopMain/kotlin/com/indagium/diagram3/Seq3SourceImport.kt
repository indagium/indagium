package com.indagium.diagram3

import com.indagium.debug.Json
import java.util.Base64
import java.util.UUID

/** A location-aware rejection.  Import is deliberately all-or-nothing. */
data class Seq3SourceDiagnostic(val line: Int, val message: String)

sealed class Seq3SourceImportResult {
    data class Success(
        val document: Seq3Document,
        /** Canonical source regenerated from [document], never the user's spacing/comments. */
        val canonicalSource: String,
        /** True when a marked, evidence-backed emission was removed.  The caller must obtain an
         * explicit confirmation before committing this result. */
        val evidenceLoss: Boolean,
    ) : Seq3SourceImportResult()

    data class Failure(val diagnostics: List<Seq3SourceDiagnostic>) : Seq3SourceImportResult()
}

private data class SourceMarker(val type: String, val id: String, val entryId: Int?, val role: String? = null)

private data class ParsedArrow(val from: String, val to: String?, val label: String, val kind: Seq3Kind)

private data class LifecycleDirective(val kind: Seq3Kind, val targetAlias: String)

private const val MAX_SEQ3_IMPORT_SOURCE_CHARS = 2 * 1024 * 1024

/**
 * Conservative inverse of Indagium's two emitters.  This is intentionally not a Mermaid or
 * PlantUML parser: accepting arbitrary dialect programs would make a successful import look like
 * it preserved semantics when it did not.  Every non-comment statement must be one our emitter
 * writes.  Marked rows retain their evidence; unmarked arrows become authored/evidence-free rows.
 */
@Suppress("CyclomaticComplexMethod", "LongMethod", "ReturnCount")
fun importSeq3Source(source: String, dialect: Seq3Dialect, existing: Seq3Document): Seq3SourceImportResult {
    val lines = source.replace("\r\n", "\n").replace('\r', '\n').split('\n')
    val errors = mutableListOf<Seq3SourceDiagnostic>()

    fun fail(line: Int, message: String) {
        errors += Seq3SourceDiagnostic(line, message)
    }
    if (source.length > MAX_SEQ3_IMPORT_SOURCE_CHARS) {
        return Seq3SourceImportResult.Failure(
            listOf(Seq3SourceDiagnostic(1, "Source exceeds the import limit.")),
        )
    }

    val meaningful = lines.withIndex().firstOrNull { it.value.trim().isNotBlank() && !isSourceComment(it.value, dialect) }
    val expectedStart = if (dialect == Seq3Dialect.MERMAID) "sequenceDiagram" else "@startuml"
    if (meaningful?.value?.trim() != expectedStart) fail((meaningful?.index ?: 0) + 1, "Expected $expectedStart.")

    val knownLifelines = existing.lifelines.associateBy { it.id }
    val knownMessages = existing.messages.associateBy { it.id }
    val knownFragments = existing.fragments.associateBy { it.id }
    val knownNotes = existing.notes.associateBy { it.id }
    val knownDelays = existing.delays.associateBy { it.id }
    val knownStates = existing.stateInvariants.associateBy { it.id }
    val knownOperands = existing.fragments.flatMap { fragment ->
        fragment.elseOperands.map { it.id to (fragment.id to it) }
    }.toMap()
    val expectedRenderedLabels = sourceMarkerLabels(existing.toSource(dialect), dialect)
    val aliases = linkedMapOf<String, String>() // alias -> stable lifeline id
    existing.lifelines.forEach { aliases[it.id] = it.id } // stable ids are the normal aliases
    val lifelineEdits = mutableMapOf<String, Seq3Lifeline>()
    val messageEdits = mutableMapOf<String, Seq3Message>()
    val fragmentEdits = mutableMapOf<String, Seq3Fragment>()
    val noteEdits = mutableMapOf<String, Seq3Note>()
    val delayEdits = mutableMapOf<String, Seq3Delay>()
    val stateEdits = mutableMapOf<String, Seq3StateInvariant>()
    val rebuiltOperands = mutableMapOf<String, MutableList<Seq3Operand>>()
    val pendingOperands = mutableListOf<Pair<SourceMarker, String>>()
    val activeFragments = ArrayDeque<String>()
    val rebuiltFragmentMessages = mutableMapOf<String, MutableList<Seq3OccurrenceRef>>()
    val activeNotes = ArrayDeque<String>()
    val rebuiltNoteMessages = mutableMapOf<String, MutableList<Seq3OccurrenceRef>>()
    val seenMarkedNotes = mutableSetOf<String>()
    val seenMarkedFragments = mutableSetOf<String>()
    val seenMarkedDelays = mutableSetOf<String>()
    val seenMarkedStates = mutableSetOf<String>()
    val newNotes = mutableListOf<Seq3Note>()
    val newFragments = mutableListOf<Seq3Fragment>()
    val newDelays = mutableListOf<Seq3Delay>()
    val activeNewFragments = ArrayDeque<String>()
    val newFragmentMessages = mutableMapOf<String, MutableList<String>>()
    val seenMarkedMessages = mutableListOf<String>()
    val seenEvidence = mutableSetOf<Pair<String, Int?>>()
    val seenMarkerPayloads = mutableSetOf<String>()
    val unmarkedMessages = mutableListOf<Seq3Message>()
    val fragmentStack = ArrayDeque<String>()
    var title: String? = null
    var pending: SourceMarker? = null
    var precedingEmission: SourceMarker? = null
    var lifecycleBeforeArrow: LifecycleDirective? = null
    var lastMarkedArrow: SourceMarker? = null
    var lastUnmarkedMessageIndex: Int? = null

    fun recordEmission(marker: SourceMarker) {
        pendingOperands.toList().forEach { (operandMarker, guard) ->
            val (fragmentId, old) = knownOperands[operandMarker.id] ?: return@forEach
            rebuiltOperands.getOrPut(fragmentId, ::mutableListOf).add(
                old.copy(
                    guard = guard,
                    startsAtMessageId = marker.id,
                    startsAtOccurrenceEntryId = if (old.startsAtOccurrenceEntryId == null) null else marker.entryId,
                ),
            )
            pendingOperands.remove(operandMarker to guard)
        }
        precedingEmission = marker
        activeFragments.forEach { fragmentId ->
            rebuiltFragmentMessages.getOrPut(fragmentId, ::mutableListOf).add(Seq3OccurrenceRef(marker.id, marker.entryId ?: -1))
        }
        activeNotes.forEach { noteId ->
            rebuiltNoteMessages.getOrPut(noteId, ::mutableListOf)
                .add(Seq3OccurrenceRef(marker.id, marker.entryId ?: -1))
        }
        activeNewFragments.forEach { id -> newFragmentMessages.getOrPut(id, ::mutableListOf).add(marker.id) }
    }

    lines.forEachIndexed { index, raw ->
        val lineNo = index + 1
        val text = raw.trim()
        if (text.isBlank()) return@forEachIndexed
        parseSourceMarker(text, dialect)?.let { marker ->
            if (pending != null) {
                fail(lineNo, "Marker for ${pending!!.type} '${pending!!.id}' is not followed by a statement.")
            }
            val payloadKey = "${marker.type}:${marker.id}:${marker.entryId}:${marker.role}"
            // Repeated group emissions are allowed only when they name different evidence rows.
            if (!seenMarkerPayloads.add(payloadKey) && marker.type !in setOf("message", "state")) {
                fail(lineNo, "Duplicate Indagium marker for ${marker.type} ${marker.id}.")
            }
            if (marker.type !in SOURCE_MARKER_TYPES) {
                fail(lineNo, "Unknown Indagium marker type '${marker.type}'.")
            }
            if (marker.type == "lifeline" && marker.id !in knownLifelines) fail(lineNo, "Marker references unknown lifeline '${marker.id}'.")
            if (marker.type == "message" && marker.id !in knownMessages) fail(lineNo, "Marker references unknown message '${marker.id}'.")
            if (marker.type == "message" && marker.entryId != null && knownMessages[marker.id]?.occurrences?.none { it.entryId == marker.entryId } == true) {
                fail(lineNo, "Marker references unknown evidence entry ${marker.entryId} for '${marker.id}'.")
            }
            if (marker.type == "operand" && marker.id !in knownOperands) fail(lineNo, "Marker references unknown operand '${marker.id}'.")
            if (marker.type != "operand" && marker.role != null && marker.role !in SOURCE_MARKER_ROLES) {
                fail(lineNo, "Unknown marker role '${marker.role}'.")
            }
            if (marker.type == "fragment" && marker.id !in knownFragments) fail(lineNo, "Marker references unknown fragment '${marker.id}'.")
            if (marker.type == "note" && marker.id !in knownNotes) fail(lineNo, "Marker references unknown note '${marker.id}'.")
            if (marker.type == "delay" && marker.id !in knownDelays) fail(lineNo, "Marker references unknown delay '${marker.id}'.")
            if (marker.type == "state" && marker.id !in knownStates) fail(lineNo, "Marker references unknown state invariant '${marker.id}'.")
            if (marker.type == "fragment" && marker.role == "end") {
                // The emitter normalizes crossing user selections into drawable brackets.  Their
                // source closes can therefore be non-stack-shaped even though each id boundary is
                // still unambiguous; remove the named open boundary rather than mistaking that
                // normalization for an invalid hand edit.
                activeFragments.remove(marker.id)
            } else if (marker.type == "note" && marker.role == "span-start") {
                if (activeNotes.contains(marker.id)) {
                    fail(lineNo, "Duplicate note span start '${marker.id}'.")
                } else {
                    activeNotes.addLast(marker.id)
                }
            } else if (marker.type == "note" && marker.role == "span-end") {
                val open = activeNotes.removeLastOrNull()
                if (open != marker.id) fail(lineNo, "Crossed or unmatched note span end '${marker.id}'.")
            } else {
                pending = marker
            }
            return@forEachIndexed
        }
        if (isSourceComment(text, dialect)) return@forEachIndexed
        if (text == expectedStart || (dialect == Seq3Dialect.PLANTUML && text == "@enduml")) return@forEachIndexed
        if (text.startsWith("title ")) { title = unescapeSource(text.removePrefix("title "), dialect); return@forEachIndexed }

        parseLifecycleDirective(text, dialect)?.let { directive ->
            if (directive.kind == Seq3Kind.DESTROY && dialect == Seq3Dialect.PLANTUML) {
                val marker = lastMarkedArrow
                val previous = marker?.let { messageEdits[it.id] }
                val target = aliases[directive.targetAlias]
                if (marker != null && previous != null && previous.toLifelineId == target) {
                    val shaped = previous.copy(kind = Seq3Kind.DESTROY)
                    val original = knownMessages[marker.id]
                    messageEdits[marker.id] = shaped.copy(
                        kind = Seq3Kind.DESTROY,
                        authoring = if (original != null && sameImportedMessageShape(original, shaped)) {
                            original.authoring
                        } else {
                            Seq3Authoring.EDITED
                        },
                    )
                } else if (lastUnmarkedMessageIndex?.let { unmarkedMessages[it].toLifelineId } == target) {
                    val unmarkedIndex = requireNotNull(lastUnmarkedMessageIndex)
                    val unmarked = unmarkedMessages[unmarkedIndex]
                    unmarkedMessages[unmarkedIndex] = unmarked.copy(kind = Seq3Kind.DESTROY)
                } else {
                    fail(lineNo, "Destroy directive does not follow its marked target message.")
                }
            } else {
                lifecycleBeforeArrow = directive
            }
            return@forEachIndexed
        }

        parseParticipant(text, dialect)?.let { (alias, name, kind) ->
            lastMarkedArrow = null
            val marker = pending.also { pending = null }
            val id = marker?.takeIf { it.type == "lifeline" }?.id ?: aliases[alias] ?: "import-${UUID.randomUUID()}"
            aliases[alias] = id
            val previous = knownLifelines[id]
            if (previous != null) {
                val renderedName = seq3DisplayName(
                    previous.name,
                    previous.displaySegments,
                    existing.lifelineDisplaySegments,
                )
                lifelineEdits[id] = if (name == renderedName && kind == previous.kind) {
                    previous
                } else {
                    previous.copy(name = name, kind = kind)
                }
            } else {
                lifelineEdits[id] = Seq3Lifeline(
                    id,
                    name,
                    setOf(name),
                    existing.lifelines.size + lifelineEdits.size,
                    kind = kind,
                )
            }
            return@forEachIndexed
        }
        parseArrow(text, dialect)?.let { arrow ->
            val marker = pending.also { pending = null }
            val from = aliases[arrow.from] ?: arrow.from.takeIf { it in knownLifelines }
            val to = arrow.to?.let { aliases[it] ?: it.takeIf { candidate -> candidate in knownLifelines } }
            if (from == null || (arrow.to != null && to == null)) {
                fail(lineNo, "Arrow references an undeclared participant.")
                return@forEachIndexed
            }
            if (marker != null && marker.type != "message") {
                fail(lineNo, "${marker.type} marker cannot annotate an arrow.")
                return@forEachIndexed
            }
            if (marker != null) {
                val old = knownMessages[marker.id] ?: return@forEachIndexed
                val key = marker.id to marker.entryId
                if (!seenEvidence.add(key)) {
                    fail(lineNo, "Duplicate marked evidence emission for '${marker.id}'.")
                    return@forEachIndexed
                }
                // EVERY/FIRST_LAST rows carry occurrence-substituted text, so comparing their
                // surface label to labelTemplate would turn every unchanged export into an edit.
                val unchangedLabel = expectedRenderedLabels[MarkerLabelKey(marker.id, marker.entryId, marker.role)] == arrow.label
                val importedLabel = if (unchangedLabel) old.labelTemplate else arrow.label
                val importedKind = importedMessageKind(old, arrow, from, to, lifecycleBeforeArrow, aliases)
                lifecycleBeforeArrow = null
                val shape = old.copy(
                    fromLifelineId = from,
                    toLifelineId = to,
                    labelTemplate = importedLabel,
                    kind = importedKind,
                )
                val update = shape.copy(
                    authoring = if (sameImportedMessageShape(old, shape)) {
                        old.authoring
                    } else {
                        Seq3Authoring.EDITED
                    },
                )
                val prior = messageEdits.putIfAbsent(marker.id, update)
                if (prior != null && !sameImportedMessageShape(prior, update)) {
                    fail(lineNo, "Repeated message '${marker.id}' has inconsistent edits.")
                }
                seenMarkedMessages += marker.id
                recordEmission(marker)
                lastMarkedArrow = marker
                lastUnmarkedMessageIndex = null
            } else {
                unmarkedMessages += Seq3Message(
                    "import-${UUID.randomUUID()}",
                    Seq3Match(from, arrow.label),
                    from,
                    to,
                    arrow.label,
                    importedAuthoredMessageKind(arrow, from, to, lifecycleBeforeArrow, aliases),
                    repeat = Seq3Repeat.EVERY,
                    authoring = Seq3Authoring.EDITED,
                )
                lifecycleBeforeArrow = null
                lastMarkedArrow = null
                lastUnmarkedMessageIndex = unmarkedMessages.lastIndex
            }
            return@forEachIndexed
        }
        parseMessageNote(text, dialect)?.let { note ->
            lastMarkedArrow = null
            val marker = pending.also { pending = null }
            if (marker?.type == "message" && marker.role == "elision") {
                seenMarkedMessages += marker.id
                recordEmission(marker)
                return@forEachIndexed
            }
            if (marker?.type == "fragment") {
                val old = knownFragments[marker.id] ?: return@forEachIndexed
                val label = if (old.kind == Seq3FragmentKind.GROUP) note.label else note.label.substringAfter(' ', "")
                fragmentEdits[marker.id] = old.copy(label = label)
                seenMarkedFragments += marker.id
                fragmentStack.addLast(marker.id)
                return@forEachIndexed
            }
            // A document note uses the same dialect statement as a NOTE message.  Its identity
            // marker disambiguates it; retaining the marked structure is safer than guessing a
            // new anchor from text alone.
            if (marker?.type == "note" && (marker.role == "statement" || marker.role == null)) {
                val old = knownNotes[marker.id] ?: return@forEachIndexed
                noteEdits[marker.id] = old.copy(text = note.label)
                seenMarkedNotes += marker.id
                return@forEachIndexed
            }
            if (marker?.type == "delay") {
                val old = knownDelays[marker.id] ?: return@forEachIndexed
                val anchor = precedingEmission
                if (anchor == null) fail(lineNo, "Delay has no preceding marked emission.")
                else delayEdits[marker.id] = old.copy(label = note.label, afterMessageId = anchor.id, afterOccurrenceEntryId = anchor.entryId)
                seenMarkedDelays += marker.id
                return@forEachIndexed
            }
            if (marker?.type == "state") {
                // The model stores the capture NAME, not the rendered value.  A state marker can
                // only edit that name when the text remains `{name=value}`; a value-only change is
                // rejected because it would fabricate evidence.
                val old = knownStates[marker.id] ?: return@forEachIndexed
                val name = note.label.removePrefix("{").substringBefore('=').removeSuffix("}")
                val anchor = precedingEmission
                if (name.isBlank()) {
                    fail(lineNo, "State invariant must use {capture=value}.")
                } else if (
                    anchor == null ||
                    knownMessages[anchor.id]?.match?.captures?.none { it.name == name } != false
                ) {
                    fail(lineNo, "State invariant '$name' is not a capture on its preceding marked message.")
                } else {
                    val update = old.copy(messageId = anchor.id, captureName = name)
                    val previous = stateEdits.putIfAbsent(marker.id, update)
                    seenMarkedStates += marker.id
                    if (previous != null && previous.captureName != update.captureName) {
                        fail(lineNo, "Repeated state invariant '${marker.id}' has inconsistent edits.")
                    }
                }
                return@forEachIndexed
            }
            if (marker == null) {
                if (dialect == Seq3Dialect.MERMAID) {
                    fail(lineNo, "Unmarked Mermaid note is ambiguous; add an Indagium note-span marker.")
                    return@forEachIndexed
                }
                val anchor = precedingEmission ?: run {
                    fail(lineNo, "Unmarked note has no preceding marked message.")
                    return@forEachIndexed
                }
                newNotes += Seq3Note("import-${UUID.randomUUID()}", note.label, listOf(anchor.id))
                return@forEachIndexed
            }
            if (marker.type != "message") {
                fail(lineNo, "${marker.type} marker cannot annotate this note statement.")
                return@forEachIndexed
            }
            val old = knownMessages[marker.id] ?: return@forEachIndexed
            val kind = when {
                note.label.endsWith(" · lost") -> Seq3Kind.LOST
                note.label.endsWith(" · found") -> Seq3Kind.FOUND
                note.label.endsWith(" · needs target") -> old.kind
                else -> Seq3Kind.NOTE
            }
            val label = note.label.removeSuffix(" · lost").removeSuffix(" · found").removeSuffix(" · needs target")
            val unchanged = expectedRenderedLabels[MarkerLabelKey(marker.id, marker.entryId, marker.role)] == note.label
            val importedLabel = if (unchanged) old.labelTemplate else label
            val update = old.copy(
                fromLifelineId = aliases[note.from] ?: old.fromLifelineId,
                toLifelineId = null,
                labelTemplate = importedLabel,
                kind = kind,
                authoring = if (old.labelTemplate == importedLabel && old.kind == kind) {
                    old.authoring
                } else {
                    Seq3Authoring.EDITED
                },
            )
            messageEdits[marker.id] = update
            seenMarkedMessages += marker.id
            recordEmission(marker)
            return@forEachIndexed
        }
        parseFragmentOpen(text, dialect)?.let { (kind, label) ->
            val marker = pending.also { pending = null }
            if (marker == null) {
                val id = "import-${UUID.randomUUID()}"
                newFragments += Seq3Fragment(id, kind, label, emptyList())
                activeNewFragments.addLast(id)
                return@forEachIndexed
            }
            if (marker.type != "fragment") {
                fail(lineNo, "${marker.type} marker cannot annotate a fragment.")
                return@forEachIndexed
            }
            val old = knownFragments[marker.id] ?: return@forEachIndexed
            val fallbackKind = if (kind == Seq3FragmentKind.GROUP) {
                runCatching { Seq3FragmentKind.valueOf(label.substringBefore(' ').uppercase()) }.getOrNull()
            } else {
                null
            }
            val resolvedKind = fallbackKind ?: kind
            val resolvedLabel = if (fallbackKind != null) label.substringAfter(' ', "") else label
            fragmentEdits[marker.id] = old.copy(kind = resolvedKind, label = resolvedLabel)
            seenMarkedFragments += marker.id
            activeFragments.addLast(marker.id)
            if (resolvedKind != Seq3FragmentKind.REF) fragmentStack.addLast(marker.id)
            return@forEachIndexed
        }
        parseDelay(text, dialect)?.let { label ->
            val marker = pending.also { pending = null }
            if (marker == null) {
                val anchor = precedingEmission ?: run {
                    fail(lineNo, "Unmarked delay has no preceding marked message.")
                    return@forEachIndexed
                }
                newDelays += Seq3Delay(
                    "import-${UUID.randomUUID()}",
                    anchor.id,
                    label,
                    afterOccurrenceEntryId = anchor.entryId,
                )
                return@forEachIndexed
            }
            if (marker.type != "delay") {
                fail(lineNo, "${marker.type} marker cannot annotate a delay.")
                return@forEachIndexed
            }
            val old = knownDelays[marker.id] ?: return@forEachIndexed
            val anchor = precedingEmission
            if (anchor == null) fail(lineNo, "Delay has no preceding marked emission.")
            else delayEdits[marker.id] = old.copy(label = label, afterMessageId = anchor.id, afterOccurrenceEntryId = anchor.entryId)
            seenMarkedDelays += marker.id
            return@forEachIndexed
        }
        if (text == "end") {
            pending = null
            if (fragmentStack.isNotEmpty()) fragmentStack.removeLast()
            if (activeNewFragments.isNotEmpty()) activeNewFragments.removeLast()
            return@forEachIndexed
        }
        parseOperand(text, dialect)?.let { guard ->
            val marker = pending.also { pending = null }
            if (marker?.type != "operand") {
                fail(lineNo, "Unmarked operand divider is unsupported.")
                return@forEachIndexed
            }
            val (fragmentId, _) = knownOperands[marker.id] ?: return@forEachIndexed
            if (marker.role != fragmentId) {
                fail(lineNo, "Operand marker belongs to a different fragment.")
                return@forEachIndexed
            }
            pendingOperands += marker to guard
            return@forEachIndexed
        }
        // Structural statements emitted by both dialects are accepted but do not manufacture new
        // model entities without a marker.  Their marked state remains in the existing document.
        if (text.startsWith("rect ") && pending?.type == "fragment") return@forEachIndexed
        if (isSupportedStructural(text)) {
            pending = null
            return@forEachIndexed
        }
        fail(lineNo, "Unsupported ${dialect.name.lowercase()} statement '$text'.")
    }
    pending?.let { fail(lines.size, "Marker for ${it.type} '${it.id}' is not followed by a statement.") }
    if (pendingOperands.isNotEmpty()) fail(lines.size, "Operand divider has no following marked emission.")
    if (activeFragments.isNotEmpty()) fail(lines.size, "Missing fragment end marker for '${activeFragments.last()}'.")
    if (activeNotes.isNotEmpty()) fail(lines.size, "Missing note span end marker for '${activeNotes.last()}'.")
    if (activeNewFragments.isNotEmpty()) fail(lines.size, "Unmarked fragment is missing end.")
    if (dialect == Seq3Dialect.PLANTUML && lines.none { it.trim() == "@enduml" }) {
        fail(lines.size, "Missing @enduml.")
    }
    if (errors.isNotEmpty()) return Seq3SourceImportResult.Failure(errors)

    // Relative reorder of marked evidence is unsafe: it changes the log story without changing an
    // occurrence.  Compare only first appearances, because EVERY emits a message more than once.
    val order = seenMarkedMessages.distinct()
    val baseline = sourceMarkerMessageOrder(existing.toSource(dialect), dialect).filter { it in order }.distinct()
    if (order != baseline) {
        return Seq3SourceImportResult.Failure(
            listOf(Seq3SourceDiagnostic(1, "Marked evidence emissions were reordered.")),
        )
    }

    val removedEvidence = existing.messages.filter { message ->
        message.visibility == Seq3Visibility.VISIBLE &&
            message.occurrences.isNotEmpty() &&
            message.id !in order
    }
    val mergedLifelines = existing.lifelines.map { lifelineEdits[it.id] ?: it } +
        lifelineEdits.values.filter { it.id !in knownLifelines }
    val result = existing.copy(
        title = title ?: existing.title,
        lifelines = mergedLifelines,
        messages = existing.messages
            .filter { it.id in order || it.visibility == Seq3Visibility.HIDDEN }
            .map { messageEdits[it.id] ?: it } + unmarkedMessages,
        fragments = existing.fragments.map { fragment ->
            (fragmentEdits[fragment.id] ?: fragment).let { edited ->
                val withOperands = rebuiltOperands[fragment.id]
                    ?.takeIf { it.size == fragment.elseOperands.size }
                    ?.let { edited.copy(elseOperands = it) }
                    ?: edited
                rebuiltFragmentMessages[fragment.id]?.let { refs ->
                    withOperands.copy(
                        messageIds = refs.map { it.messageId }.distinct(),
                        occurrenceRefs = if (fragment.occurrenceRefs.isNotEmpty()) {
                            refs.filter { it.entryId >= 0 }
                        } else {
                            emptyList()
                        },
                    )
                } ?: withOperands
            }
        }.filter { fragment ->
            fragment.visibility == Seq3Visibility.HIDDEN || fragment.id in seenMarkedFragments
        } + newFragments.map { fragment ->
            fragment.copy(messageIds = newFragmentMessages[fragment.id].orEmpty().distinct())
        },
        notes = existing.notes.mapNotNull { note ->
            if (note.visibility == Seq3Visibility.VISIBLE && note.id !in seenMarkedNotes) null
            else (noteEdits[note.id] ?: note).let { edited ->
                rebuiltNoteMessages[note.id]?.let { refs ->
                    edited.copy(messageIds = refs.map { it.messageId }.distinct())
                } ?: edited
            }
        } + newNotes,
        delays = existing.delays.map { delayEdits[it.id] ?: it }
            .filter { it.visibility == Seq3Visibility.HIDDEN || it.id in seenMarkedDelays } + newDelays,
        stateInvariants = existing.stateInvariants.map { stateEdits[it.id] ?: it }
            .filter { it.visibility == Seq3Visibility.HIDDEN || it.id in seenMarkedStates },
    )
    seq3DocumentBoundsViolation(result)?.let { violation ->
        return Seq3SourceImportResult.Failure(listOf(Seq3SourceDiagnostic(1, violation)))
    }
    val lifecycleError = seq3NewLifecycleViolation(existing, result)
    if (lifecycleError != null) {
        return Seq3SourceImportResult.Failure(
            listOf(Seq3SourceDiagnostic(1, lifecycleError.rejectionReason())),
        )
    }
    if (sourceDirectives(source) != sourceDirectives(result.toSource(dialect))) {
        return Seq3SourceImportResult.Failure(
            listOf(
                Seq3SourceDiagnostic(
                    1,
                    "Activation/lifecycle directives do not match the imported messages.",
                ),
            ),
        )
    }
    return Seq3SourceImportResult.Success(
        result,
        result.toSource(dialect).trimEnd('\n'),
        removedEvidence.isNotEmpty(),
    )
}

private val SOURCE_MARKER_TYPES = setOf(
    "lifeline", "message", "fragment", "note", "delay", "state", "operand",
)
private val SOURCE_MARKER_ROLES = setOf(
    "occurrence", "template", "elision", "value", "start", "end", "span-start", "span-end",
    "statement",
)

private fun sameImportedMessageShape(first: Seq3Message, second: Seq3Message): Boolean =
    first.fromLifelineId == second.fromLifelineId &&
        first.toLifelineId == second.toLifelineId &&
        first.labelTemplate == second.labelTemplate &&
        first.kind == second.kind

private fun importedMessageKind(
    old: Seq3Message,
    arrow: ParsedArrow,
    from: String,
    to: String?,
    lifecycle: LifecycleDirective?,
    aliases: Map<String, String>,
): Seq3Kind = when {
    lifecycle?.targetAlias?.let { aliases[it] } == to -> lifecycle?.kind ?: arrow.kind
    from == to -> Seq3Kind.SELF
    old.kind == Seq3Kind.SELF -> arrow.kind
    else -> arrow.kind
}

private fun importedAuthoredMessageKind(
    arrow: ParsedArrow,
    from: String,
    to: String?,
    lifecycle: LifecycleDirective?,
    aliases: Map<String, String>,
): Seq3Kind = when {
    lifecycle?.targetAlias?.let { aliases[it] } == to -> lifecycle?.kind ?: arrow.kind
    from == to -> Seq3Kind.SELF
    else -> arrow.kind
}

private fun isSourceComment(text: String, dialect: Seq3Dialect): Boolean =
    if (dialect == Seq3Dialect.MERMAID) text.startsWith("%%") else text.startsWith("'")

@Suppress("UNCHECKED_CAST")
private fun parseSourceMarker(text: String, dialect: Seq3Dialect): SourceMarker? {
    val prefix = if (dialect == Seq3Dialect.MERMAID) "%% indagium-seq3:" else "' indagium-seq3:"
    if (!text.startsWith(prefix)) return null
    val parts = text.removePrefix(prefix).trim().split(Regex("\\s+"), limit = 2)
    if (parts.size != 2 || parts[0] != "v1") return SourceMarker("invalid", "", null)
    val map = runCatching {
        Json.decode(String(Base64.getUrlDecoder().decode(parts[1]), Charsets.UTF_8))
    }.getOrNull() as? Map<String, Any?> ?: return SourceMarker("invalid", "", null)
    val type = map["t"] as? String ?: return SourceMarker("invalid", "", null)
    val id = map["id"] as? String ?: return SourceMarker("invalid", "", null)
    val entry = (map["entry"] as? Number)?.toInt()
    return SourceMarker(type, id, entry, map["role"] as? String)
}

private fun parseParticipant(text: String, dialect: Seq3Dialect): Triple<String, String, Seq3LifelineKind>? {
    val re = if (dialect == Seq3Dialect.MERMAID)
        Regex("^(participant|actor)\\s+(\\S+)\\s+as\\s+(.+)$")
    else Regex("^(participant|actor)\\s+\\\"(.*)\\\"\\s+as\\s+(\\S+)$")
    val m = re.matchEntire(text) ?: return null
    val kind = if (m.groupValues[1] == "actor") Seq3LifelineKind.ACTOR else Seq3LifelineKind.PARTICIPANT
    return if (dialect == Seq3Dialect.MERMAID) Triple(m.groupValues[2], unescapeSource(m.groupValues[3], dialect), kind)
    else Triple(m.groupValues[3], unescapeSource(m.groupValues[2], dialect), kind)
}

private fun parseLifecycleDirective(text: String, dialect: Seq3Dialect): LifecycleDirective? {
    if (text.startsWith("destroy ")) {
        return text.removePrefix("destroy ").trim().takeIf(String::isNotBlank)
            ?.let { LifecycleDirective(Seq3Kind.DESTROY, it) }
    }
    if (!text.startsWith("create ")) return null
    val rest = text.removePrefix("create ").trim()
    val alias = if (dialect == Seq3Dialect.MERMAID) {
        rest.substringBefore(" as ").substringAfter(' ').trim()
    } else {
        rest.substringBefore(' ').trim()
    }
    return alias.takeIf(String::isNotBlank)?.let { LifecycleDirective(Seq3Kind.CREATE, it) }
}

private fun parseArrow(text: String, dialect: Seq3Dialect): ParsedArrow? {
    if (text.startsWith("Note ") || text.startsWith("note ") || text.startsWith("...") || text.startsWith("ref ")) return null
    if (dialect == Seq3Dialect.PLANTUML) {
        Regex("^(\\S+)\\s+->o\\]:\\s?(.*)$").matchEntire(text)?.let {
            return ParsedArrow(it.groupValues[1], null, unescapeSource(it.groupValues[2], dialect), Seq3Kind.LOST)
        }
        Regex("^\\[o->\\s+(\\S+):\\s?(.*)$").matchEntire(text)?.let {
            return ParsedArrow(it.groupValues[1], null, unescapeSource(it.groupValues[2], dialect), Seq3Kind.FOUND)
        }
    }
    val re = if (dialect == Seq3Dialect.MERMAID) Regex("^(\\S+?)(-->>|->>|-\\))(\\S+):\\s?(.*)$")
    else Regex("^(\\S+)\\s+(-->|->>|->)\\s+(\\S+):\\s?(.*)$")
    val m = re.matchEntire(text) ?: return null
    val token = m.groupValues[2]
    val kind = when {
        token == "-->" || token == "-->>" -> Seq3Kind.RETURN
        dialect == Seq3Dialect.PLANTUML && token == "->>" -> Seq3Kind.ASYNC
        dialect == Seq3Dialect.MERMAID && token == "-)" -> Seq3Kind.ASYNC
        else -> Seq3Kind.CALL
    }
    return ParsedArrow(
        m.groupValues[1],
        m.groupValues[3],
        unescapeSource(m.groupValues[4].removeSuffixRegex(Regex(" ×\\d+$")), dialect),
        kind,
    )
}

private fun parseMessageNote(text: String, dialect: Seq3Dialect): ParsedArrow? {
    val re = if (dialect == Seq3Dialect.MERMAID) Regex("^Note (?:right of|over) (\\S+):\\s?(.*)$")
    else Regex("^note (?:right of|over) (\\S+):\\s?(.*)$")
    val m = re.matchEntire(text) ?: return null
    return ParsedArrow(m.groupValues[1], null, unescapeSource(m.groupValues[2], dialect), Seq3Kind.NOTE)
}

private fun parseFragmentOpen(text: String, dialect: Seq3Dialect): Pair<Seq3FragmentKind, String>? {
    val normalized = text.trim()
    if (dialect == Seq3Dialect.PLANTUML && normalized.startsWith("ref over ")) {
        return Seq3FragmentKind.REF to unescapeSource(normalized.substringAfter(": ", ""), dialect)
    }
    val keyword = normalized.substringBefore(' ').lowercase()
    val kind = runCatching { Seq3FragmentKind.valueOf(keyword.uppercase()) }.getOrNull() ?: return null
    if (kind == Seq3FragmentKind.REF) return null
    return kind to unescapeSource(normalized.substringAfter(' ', ""), dialect)
}

private fun parseOperand(text: String, dialect: Seq3Dialect): String? {
    val keyword = text.substringBefore(' ').lowercase()
    if (keyword !in setOf("else", "and", "option")) return null
    return unescapeSource(text.substringAfter(' ', ""), dialect)
}

private fun parseDelay(text: String, dialect: Seq3Dialect): String? =
    if (dialect == Seq3Dialect.PLANTUML && text.startsWith("...") && text.endsWith("...") && text.length >= 6)
        unescapeSource(text.removePrefix("...").removeSuffix("..."), dialect) else null

private data class MarkerLabelKey(val messageId: String, val entryId: Int?, val role: String?)

/** Reads the exporter’s own rendered labels once, avoiding a duplicate implementation of A9’s
 * signature/prefix pipeline in the importer. */
private fun sourceMarkerLabels(source: String, dialect: Seq3Dialect): Map<MarkerLabelKey, String> {
    val result = mutableMapOf<MarkerLabelKey, String>()
    var pending: SourceMarker? = null
    source.lineSequence().forEach { raw ->
        val text = raw.trim()
        parseSourceMarker(text, dialect)?.let { pending = it; return@forEach }
        val marker = pending ?: return@forEach
        val label = parseArrow(text, dialect)?.label ?: parseMessageNote(text, dialect)?.label
        if (marker.type == "message" && label != null) {
            result[MarkerLabelKey(marker.id, marker.entryId, marker.role)] = label
            pending = null
        } else if (text.isNotBlank() && !isSourceComment(text, dialect) && !text.startsWith("create ") && !text.startsWith("destroy ")) {
            // A source marker binds to the next semantic statement, never another marker.
            pending = null
        }
    }
    return result
}

private fun sourceMarkerMessageOrder(source: String, dialect: Seq3Dialect): List<String> = source.lineSequence().mapNotNull { line ->
    parseSourceMarker(line.trim(), dialect)?.takeIf { it.type == "message" }?.id
}.toList()

private fun sourceDirectives(source: String): List<String> = source.lineSequence().map { it.trim() }
    .filter { it.startsWith("activate ") || it.startsWith("deactivate ") || it.startsWith("create ") || it.startsWith("destroy ") }
    .toList()

private fun String.removeSuffixRegex(regex: Regex): String = replace(regex, "")

private fun unescapeSource(value: String, dialect: Seq3Dialect): String = if (dialect == Seq3Dialect.MERMAID) {
    value.replace("<br/>", "\n").replace("#59;", ";").replace("#35;", "#").replace("#58;", ":")
        .replace("#60;", "<").replace("#62;", ">").replace("#34;", "\"").replace("#96;", "`")
} else {
    value.replace("\\n", "\n").replace("\\\"", "\"").replace("\\\\", "\\")
}

private fun isSupportedStructural(text: String): Boolean {
    val lower = text.lowercase()
    return lower == "end" || lower.startsWith("else") || lower.startsWith("and") || lower.startsWith("option") ||
        lower.startsWith("loop") || lower.startsWith("alt") || lower.startsWith("opt") || lower.startsWith("par") ||
        lower.startsWith("critical") || lower.startsWith("break") || lower.startsWith("group") || lower.startsWith("rect ") ||
        lower.startsWith("activate ") || lower.startsWith("deactivate ") || lower.startsWith("create ") || lower.startsWith("destroy ") ||
        lower.startsWith("note ") || lower.startsWith("ref ") || text.startsWith("...")
}
