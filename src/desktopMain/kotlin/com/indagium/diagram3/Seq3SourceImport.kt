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

private data class SourceActivationDirective(val line: Int, val activate: Boolean, val alias: String)

private data class SourceEmissionAnchor(val line: Int, val messageId: String, val occurrenceEntryId: Int?)

private data class OpenSourceActivation(val alias: String, val start: SourceEmissionAnchor)

private data class BoundSourceActivation(
    val activate: Boolean,
    val alias: String,
    val messageId: String,
    val occurrenceEntryId: Int?,
)

private const val MAX_SEQ3_IMPORT_SOURCE_CHARS = 2 * 1024 * 1024
private val SAFE_SOURCE_LIFELINE_ID = Regex("^[A-Za-z_][A-Za-z0-9_]*$")
private val RESERVED_SOURCE_LIFELINE_IDS = setOf(
    "participant", "actor", "as", "end", "loop", "alt", "opt", "par", "critical", "break", "group",
    "else", "and", "option", "rect", "activate", "deactivate", "create", "destroy", "note", "ref",
)

/**
 * Conservative inverse of Indagium's two emitters.  This is intentionally not a Mermaid or
 * PlantUML parser: accepting arbitrary dialect programs would make a successful import look like
 * it preserved semantics when it did not.  Every non-comment statement must be one our emitter
 * writes.  Marked rows retain their evidence; unmarked arrows become authored/evidence-free rows.
 */
@Suppress("CyclomaticComplexMethod", "LongMethod", "ReturnCount")
fun importSeq3Source(
    source: String,
    dialect: Seq3Dialect,
    existing: Seq3Document,
    sourceOwnsVisibleLifelines: Boolean = false,
): Seq3SourceImportResult {
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
    val existingSource = existing.toSource(dialect)
    val expectedRenderedLabels = sourceMarkerLabels(existingSource, dialect)
    val expectedFragmentScopes = sourceMarkerFragmentScopes(existingSource, dialect)
    val aliases = linkedMapOf<String, String>() // alias -> stable lifeline id
    existing.lifelines.forEach { aliases[it.id] = it.id } // stable ids are the normal aliases
    val lifelineEdits = mutableMapOf<String, Seq3Lifeline>()
    val declaredAliases = mutableSetOf<String>()
    val declaredLifelines = mutableListOf<String>()
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
    val importedRowOrder = mutableListOf<Seq3RowRef>()
    val seenEvidence = mutableSetOf<Pair<String, Int?>>()
    val seenMarkerPayloads = mutableSetOf<String>()
    val unmarkedMessages = mutableListOf<Seq3Message>()
    val sourceActivationDirectives = mutableListOf<SourceActivationDirective>()
    val sourceEmissions = mutableListOf<SourceEmissionAnchor>()
    val fragmentStack = ArrayDeque<String>()
    val fragmentOpenOrder = ArrayDeque<Pair<Boolean, String>>() // true for an unmarked fragment
    var title: String? = null
    var pending: SourceMarker? = null
    var precedingEmission: SourceMarker? = null
    var lifecycleBeforeArrow: LifecycleDirective? = null
    var lastMarkedArrow: SourceMarker? = null
    var lastUnmarkedMessageIndex: Int? = null
    var precedingMessageId: String? = null

    fun recordEmission(marker: SourceMarker, line: Int? = null) {
        importedRowOrder += Seq3RowRef(marker.id, marker.entryId)
        if (line != null) sourceEmissions += SourceEmissionAnchor(line, marker.id, marker.entryId)
        precedingMessageId = marker.id
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

    fun expectedNoteScope(messageId: String): String? {
        val message = messageEdits[messageId]
            ?: knownMessages[messageId]
            ?: unmarkedMessages.firstOrNull { it.id == messageId }
            ?: return null
        val lifelines = existing.lifelines + lifelineEdits.values.filter { candidate ->
            existing.lifelines.none { it.id == candidate.id }
        }
        val order = lifelines.withIndex().associate { it.value.id to it.index }
        val aliasById = aliases.entries.associate { (alias, id) -> id to alias }
        val endpoints = listOfNotNull(message.fromLifelineId, message.toLifelineId)
            .distinct()
            .sortedBy { order[it] ?: Int.MAX_VALUE }
        return endpoints.mapNotNull { aliasById[it] }.takeIf { it.isNotEmpty() }?.joinToString(",")
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

        parseActivationDirective(text)?.let { (activate, alias) ->
            sourceActivationDirectives += SourceActivationDirective(lineNo, activate, alias)
            return@forEachIndexed
        }

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
            val id = marker?.takeIf { it.type == "lifeline" }?.id ?: aliases[alias]
                ?: newImportedLifelineId(alias, existing.lifelines, lifelineEdits.values)
            aliases[alias] = id
            if (!declaredAliases.add(alias) || id in declaredLifelines) {
                fail(lineNo, "Duplicate participant declaration '$alias'.")
            }
            declaredLifelines += id
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
            if (sourceOwnsVisibleLifelines &&
                (arrow.from !in declaredAliases || (arrow.to != null && arrow.to !in declaredAliases))) {
                fail(lineNo, "Arrow references an undeclared participant.")
                return@forEachIndexed
            }
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
                recordEmission(marker, lineNo)
                lastMarkedArrow = marker
                lastUnmarkedMessageIndex = null
            } else {
                val newMessage = Seq3Message(
                    "import-${UUID.randomUUID()}",
                    Seq3Match(from, arrow.label),
                    from,
                    to,
                    arrow.label,
                    importedAuthoredMessageKind(arrow, from, to, lifecycleBeforeArrow, aliases),
                    repeat = Seq3Repeat.EVERY,
                    authoring = Seq3Authoring.EDITED,
                )
                unmarkedMessages += newMessage
                sourceEmissions += SourceEmissionAnchor(lineNo, newMessage.id, null)
                importedRowOrder += Seq3RowRef(unmarkedMessages.last().id)
                precedingMessageId = newMessage.id
                activeFragments.forEach { fragmentId ->
                    rebuiltFragmentMessages.getOrPut(fragmentId, ::mutableListOf).add(Seq3OccurrenceRef(newMessage.id, -1))
                }
                activeNotes.forEach { noteId ->
                    rebuiltNoteMessages.getOrPut(noteId, ::mutableListOf).add(Seq3OccurrenceRef(newMessage.id, -1))
                }
                activeNewFragments.forEach { id -> newFragmentMessages.getOrPut(id, ::mutableListOf).add(newMessage.id) }
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
                recordEmission(marker, lineNo)
                return@forEachIndexed
            }
            if (marker?.type == "fragment") {
                val old = knownFragments[marker.id] ?: return@forEachIndexed
                val expectedScope = expectedFragmentScopes[marker.id]
                if (expectedScope != null && note.from != expectedScope) {
                    fail(lineNo, "Fragment participant span is not editable in this source form.")
                    return@forEachIndexed
                }
                val label = if (old.kind == Seq3FragmentKind.GROUP) note.label else note.label.substringAfter(' ', "")
                fragmentEdits[marker.id] = old.copy(label = label)
                seenMarkedFragments += marker.id
                activeFragments.addLast(marker.id)
                fragmentStack.addLast(marker.id)
                fragmentOpenOrder.addLast(false to marker.id)
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
                val invalidAlias = note.from.split(',').map(String::trim).firstOrNull { aliases[it] == null }
                val anchor = precedingMessageId ?: run {
                    fail(lineNo, "Unmarked note has no preceding message.")
                    return@forEachIndexed
                }
                val expectedScope = expectedNoteScope(anchor)
                if (invalidAlias != null || expectedScope == null) {
                    fail(lineNo, "Note references an undeclared participant or an unsupported message scope.")
                    return@forEachIndexed
                }
                if (note.from.split(',').map(String::trim).joinToString(",") != expectedScope) {
                    fail(lineNo, "Note scope does not match the participants touched by its preceding message.")
                    return@forEachIndexed
                }
                newNotes += Seq3Note("import-${UUID.randomUUID()}", note.label, listOf(anchor))
                return@forEachIndexed
            }
            if (marker.type != "message") {
                fail(lineNo, "${marker.type} marker cannot annotate this note statement.")
                return@forEachIndexed
            }
            if (marker.role == "elision") {
                // FIRST_LAST writes an elision glyph as a marked annotation row. It is a renderer
                // summary, not a NOTE message edit or omitted evidence, so preserve its parent and
                // record only the stable summary-row position.
                seenMarkedMessages += marker.id
                recordEmission(marker, lineNo)
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
            recordEmission(marker, lineNo)
            return@forEachIndexed
        }
        parseFragmentOpen(text, dialect)?.let { (kind, label) ->
            val marker = pending.also { pending = null }
            if (marker == null) {
                if (kind == Seq3FragmentKind.REF) {
                    fail(lineNo, "An unmarked PlantUML ref cannot be mapped to a message span.")
                    return@forEachIndexed
                }
                val id = "import-${UUID.randomUUID()}"
                newFragments += Seq3Fragment(id, kind, label, emptyList())
                activeNewFragments.addLast(id)
                fragmentOpenOrder.addLast(true to id)
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
            if (resolvedKind == Seq3FragmentKind.REF) {
                val expectedScope = expectedFragmentScopes[marker.id]
                val importedScope = parsePlantUmlRefScope(text)
                if (expectedScope != null && importedScope != expectedScope) {
                    fail(lineNo, "Fragment participant span is not editable in this source form.")
                    return@forEachIndexed
                }
            }
            fragmentEdits[marker.id] = old.copy(kind = resolvedKind, label = resolvedLabel)
            seenMarkedFragments += marker.id
            // PlantUML ref-over is a standalone statement. Its source has no closing `end`, so
            // retain the known message span rather than treating every later row as its member.
            if (resolvedKind != Seq3FragmentKind.REF) {
                activeFragments.addLast(marker.id)
                fragmentStack.addLast(marker.id)
                fragmentOpenOrder.addLast(false to marker.id)
            }
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
            val (isNew, fragmentId) = fragmentOpenOrder.removeLastOrNull() ?: run {
                fail(lineNo, "Fragment end has no matching fragment start.")
                return@forEachIndexed
            }
            if (isNew) {
                val openId = activeNewFragments.removeLastOrNull()
                if (openId != fragmentId) fail(lineNo, "Fragment ends do not match their start order.")
                if (newFragmentMessages[fragmentId].isNullOrEmpty()) {
                    fail(lineNo, "A fragment must contain at least one message row.")
                }
            } else if (fragmentStack.isNotEmpty()) {
                fragmentStack.removeLast()
            } else {
                fail(lineNo, "Fragment end has no matching fragment start.")
            }
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
    val canonicalSource = existing.toSource(dialect)
    val canonicalActivationAnchors = bindSourceActivations(
        canonicalSource,
        sourceEmissionAnchors(canonicalSource, dialect),
    )
    val importedActivationAnchors = bindSourceActivations(sourceActivationDirectives, sourceEmissions)
    val activationDirectivesChanged = importedActivationAnchors != canonicalActivationAnchors
    var importedManualActivations: List<Seq3ManualActivation>? = null
    if (activationDirectivesChanged) {
        val open = mutableListOf<OpenSourceActivation>()
        val imported = mutableListOf<Seq3ManualActivation>()
        fun anchorFor(line: Int): SourceEmissionAnchor? =
            sourceEmissions.lastOrNull { it.line < line } ?: sourceEmissions.firstOrNull()
        sourceActivationDirectives.forEach { directive ->
            if (aliases[directive.alias] == null) {
                fail(directive.line, "Activation references an undeclared participant '${directive.alias}'.")
                return@forEach
            }
            val anchor = anchorFor(directive.line)
            if (anchor == null) {
                fail(directive.line, "Activation has no message row to anchor to.")
                return@forEach
            }
            if (directive.activate) {
                open += OpenSourceActivation(directive.alias, anchor)
            } else {
                val openIndex = open.indexOfLast { it.alias == directive.alias }
                val start = if (openIndex >= 0) open.removeAt(openIndex) else null
                if (start == null) {
                    fail(directive.line, "Deactivation for '${directive.alias}' has no matching activation.")
                } else {
                    val lifelineId = aliases[directive.alias] ?: return@forEach
                    imported += Seq3ManualActivation(
                        id = "source-${UUID.randomUUID()}",
                        lifelineId = lifelineId,
                        startMessageId = start.start.messageId,
                        startOccurrenceEntryId = start.start.occurrenceEntryId,
                        endMessageId = anchor.messageId,
                        endOccurrenceEntryId = anchor.occurrenceEntryId,
                    )
                }
            }
        }
        open.asReversed().forEach { start ->
            val lifelineId = aliases[start.alias] ?: return@forEach
            imported += Seq3ManualActivation(
                id = "source-${UUID.randomUUID()}",
                lifelineId = lifelineId,
                startMessageId = start.start.messageId,
                startOccurrenceEntryId = start.start.occurrenceEntryId,
            )
        }
        importedManualActivations = imported
    }
    pending?.let { fail(lines.size, "Marker for ${it.type} '${it.id}' is not followed by a statement.") }
    if (pendingOperands.isNotEmpty()) fail(lines.size, "Operand divider has no following marked emission.")
    if (activeFragments.isNotEmpty()) fail(lines.size, "Missing fragment end marker for '${activeFragments.last()}'.")
    if (activeNotes.isNotEmpty()) fail(lines.size, "Missing note span end marker for '${activeNotes.last()}'.")
    if (activeNewFragments.isNotEmpty()) fail(lines.size, "Unmarked fragment is missing end.")
    if (fragmentOpenOrder.isNotEmpty()) fail(lines.size, "Fragment is missing its end statement.")
    if (dialect == Seq3Dialect.PLANTUML && lines.none { it.trim() == "@enduml" }) {
        fail(lines.size, "Missing @enduml.")
    }
    if (errors.isNotEmpty()) return Seq3SourceImportResult.Failure(errors)

    // The exported row order is editable.  Compare full stable row refs (not only parent ids) so a
    // source editor can reorder repeated emissions and preserve that order durably.
    val order = seenMarkedMessages.distinct()
    val baselineRows = sourceMarkerRowOrder(existing.toSource(dialect), dialect).distinct()
    val baselineRowSet = baselineRows.toSet()
    val observedRows = importedRowOrder.distinct()
    val observedExistingRows = observedRows.filter { it in baselineRowSet }
    val baselineSurvivors = baselineRows.filter { it in observedExistingRows.toSet() }
    val orderChanged = observedExistingRows != baselineSurvivors || observedRows.any { it !in baselineRowSet }
    val missingRows = baselineRowSet - observedRows.toSet()
    val missingOccurrenceRows = missingRows.filter { it.occurrenceEntryId != null && it.messageId in order }

    val removedEvidence = existing.messages.filter { message ->
        message.visibility == Seq3Visibility.VISIBLE &&
            message.occurrences.isNotEmpty() &&
            message.id !in order
    }
    val mergedLifelines = if (sourceOwnsVisibleLifelines) {
        declaredLifelines.mapIndexedNotNull { index, id -> lifelineEdits[id]?.copy(ordinal = index) } +
            existing.lifelines.filter { it.visibility == Seq3Visibility.HIDDEN && it.id !in declaredLifelines }
    } else {
        existing.lifelines.map { lifelineEdits[it.id] ?: it } +
            lifelineEdits.values.filter { it.id !in knownLifelines }
    }
    val importedMessages = existing.messages
        .filter { it.id in order || it.visibility == Seq3Visibility.HIDDEN }
        .map { message ->
            val edited = messageEdits[message.id] ?: message
            val removedEntries = missingOccurrenceRows.asSequence()
                .filter { it.messageId == message.id }
                .mapNotNull { it.occurrenceEntryId }
                .toSet()
            if (removedEntries.isEmpty()) edited else edited.copy(
                occurrences = edited.occurrences.map { occurrence ->
                    if (occurrence.entryId in removedEntries) occurrence.copy(visibility = Seq3Visibility.HIDDEN) else occurrence
                },
            )
        } + unmarkedMessages
    val resultBase = existing.copy(
        title = title ?: existing.title,
        lifelines = mergedLifelines,
        messages = importedMessages,
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
        manualActivations = importedManualActivations ?: existing.manualActivations,
        showActivations = if (activationDirectivesChanged) false else existing.showActivations,
    )
    val orderedBase = if (orderChanged) {
        resultBase.copy(rowOrder = mergeImportedRowOrder(existing, resultBase, baselineRows, observedRows, missingRows))
    } else {
        resultBase
    }
    val result = reconcileSeq3RowOrder(existing, orderedBase)
    seq3DocumentBoundsViolation(result)?.let { violation ->
        return Seq3SourceImportResult.Failure(listOf(Seq3SourceDiagnostic(1, violation)))
    }
    val lifecycleError = seq3NewLifecycleViolation(existing, result)
    if (lifecycleError != null) {
        return Seq3SourceImportResult.Failure(
            listOf(Seq3SourceDiagnostic(1, lifecycleError.rejectionReason())),
        )
    }
    val expectedSource = result.toSource(dialect)
    val expectedDirectives = sourceDirectives(expectedSource)
    val actualDirectives = sourceDirectives(source)
    val directiveMismatch = if (activationDirectivesChanged) {
        val expectedLifecycle = expectedDirectives.filterNot { it.startsWith("activate ") || it.startsWith("deactivate ") }
        val actualLifecycle = actualDirectives.filterNot { it.startsWith("activate ") || it.startsWith("deactivate ") }
        bindSourceActivations(expectedSource, sourceEmissionAnchors(expectedSource, dialect)) !=
            bindSourceActivations(sourceActivationDirectives, sourceEmissions) ||
            actualLifecycle != expectedLifecycle
    } else {
        actualDirectives != expectedDirectives
    }
    if (directiveMismatch) {
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
        removedEvidence.isNotEmpty() || missingOccurrenceRows.isNotEmpty(),
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

private fun newImportedLifelineId(
    alias: String,
    existing: List<Seq3Lifeline>,
    edits: Collection<Seq3Lifeline>,
): String {
    val knownLifelines = (existing + edits).distinctBy { it.id }
    val existingIds = knownLifelines.mapTo(hashSetOf()) { it.id }
    val emittedAliases = sanitizedSourceLifelineAliases(knownLifelines.filter { it.visibility == Seq3Visibility.VISIBLE })
    val canKeepAlias = SAFE_SOURCE_LIFELINE_ID.matches(alias) &&
        alias !in RESERVED_SOURCE_LIFELINE_IDS &&
        alias !in existingIds &&
        alias !in emittedAliases
    return if (canKeepAlias) alias else "import-${UUID.randomUUID()}"
}

/** Mirrors the emitter's alias sanitizer so a new stable id is only chosen when it will remain
 * exactly the source alias after the whole document is rendered and deduplicated. */
private fun sanitizedSourceLifelineAliases(lifelines: List<Seq3Lifeline>): Set<String> {
    val used = hashSetOf<String>()
    return lifelines.map { lifeline ->
        val seed = lifeline.id.ifBlank { lifeline.name }
        var base = seed.replace(Regex("[^A-Za-z0-9_]"), "_")
        if (base.isEmpty() || base[0].isDigit()) base = "p$base"
        var candidate = base
        var suffix = 2
        while (!used.add(candidate)) {
            candidate = "${base}_$suffix"
            suffix++
        }
        candidate
    }.toSet()
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

private fun parseActivationDirective(text: String): Pair<Boolean, String>? {
    val match = Regex("^(activate|deactivate)\\s+(\\S+)$").matchEntire(text) ?: return null
    return (match.groupValues[1] == "activate") to match.groupValues[2]
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
    if (dialect == Seq3Dialect.PLANTUML) {
        val ref = parsePlantUmlRef(normalized)
        if (ref != null) return Seq3FragmentKind.REF to unescapeSource(ref.second, dialect)
    }
    val keyword = normalized.substringBefore(' ').lowercase()
    val kind = runCatching { Seq3FragmentKind.valueOf(keyword.uppercase()) }.getOrNull() ?: return null
    if (kind == Seq3FragmentKind.REF) return null
    return kind to unescapeSource(normalized.substringAfter(' ', ""), dialect)
}

private fun parsePlantUmlRef(text: String): Pair<String, String>? {
    val match = Regex("^ref over (\\S+)\\s+:\\s?(.*)$").matchEntire(text) ?: return null
    return match.groupValues[1] to match.groupValues[2]
}

private fun parsePlantUmlRefScope(text: String): String? = parsePlantUmlRef(text)?.first

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

/** Scopes that text editing cannot represent on the fragment model itself: Mermaid's fallback
 * note and PlantUML's standalone ref statement. Their marked source remains editable for labels,
 * but changing the participant span must be rejected rather than silently discarded. */
private fun sourceMarkerFragmentScopes(source: String, dialect: Seq3Dialect): Map<String, String> {
    val result = mutableMapOf<String, String>()
    var pending: SourceMarker? = null
    source.lineSequence().forEach { raw ->
        val text = raw.trim()
        parseSourceMarker(text, dialect)?.let { marker ->
            pending = marker.takeIf { it.type == "fragment" && it.role == "start" }
            return@forEach
        }
        val marker = pending ?: return@forEach
        if (text.isBlank() || isSourceComment(text, dialect)) return@forEach
        if (dialect == Seq3Dialect.MERMAID && text.startsWith("rect ")) return@forEach
        val scope = parseMessageNote(text, dialect)?.from ?: parsePlantUmlRefScope(text)
        if (scope != null) {
            result[marker.id] = scope
            pending = null
        } else if (parseFragmentOpen(text, dialect) != null || parseArrow(text, dialect) != null) {
            pending = null
        }
    }
    return result
}

private fun sourceMarkerRowOrder(source: String, dialect: Seq3Dialect): List<Seq3RowRef> = source.lineSequence().mapNotNull { line ->
    parseSourceMarker(line.trim(), dialect)?.takeIf { it.type == "message" }?.let { Seq3RowRef(it.id, it.entryId) }
}.toList()

/** Uses the complete observed source sequence, including new unmarked rows. Hidden, removed-but-
 *  retained, and elided refs stay anchored to the nearest preceding old visible row, so they remain
 *  near their prior position if that row becomes visible again. */
private fun mergeImportedRowOrder(
    existing: Seq3Document,
    imported: Seq3Document,
    baselineRows: List<Seq3RowRef>,
    observedRows: List<Seq3RowRef>,
    missingRows: Set<Seq3RowRef>,
): List<Seq3RowRef> {
    val knownMessages = imported.messages.associateBy { it.id }

    fun isValid(ref: Seq3RowRef): Boolean {
        val message = knownMessages[ref.messageId] ?: return false
        return ref.occurrenceEntryId == null || message.occurrences.any { it.entryId == ref.occurrenceEntryId }
    }
    val baselineSet = baselineRows.toSet()
    val oldOrder = (existing.rowOrder.ifEmpty { baselineRows }).distinct()
    val preservedBeforeFirst = mutableListOf<Seq3RowRef>()
    val preservedAfter = linkedMapOf<Seq3RowRef, MutableList<Seq3RowRef>>()
    var previousBaseline: Seq3RowRef? = null
    oldOrder.forEach { old ->
        if (!isValid(old)) return@forEach
        if (old !in baselineSet || old in missingRows) {
            if (previousBaseline == null) preservedBeforeFirst += old
            else preservedAfter.getOrPut(requireNotNull(previousBaseline)) { mutableListOf() } += old
        }
        if (old in baselineSet && old !in missingRows) previousBaseline = old
    }
    val merged = mutableListOf<Seq3RowRef>()
    val present = hashSetOf<Seq3RowRef>()

    fun append(ref: Seq3RowRef) { if (isValid(ref) && present.add(ref)) merged += ref }
    preservedBeforeFirst.forEach(::append)
    observedRows.forEach { ref ->
        append(ref)
        if (ref in baselineSet) preservedAfter[ref].orEmpty().forEach(::append)
    }
    return merged
}

private fun sourceDirectives(source: String): List<String> = source.lineSequence().map { it.trim() }
    .filter { it.startsWith("activate ") || it.startsWith("deactivate ") || it.startsWith("create ") || it.startsWith("destroy ") }
    .toList()

/** Finds the marked emissions in canonical exporter output, retaining their physical source line. */
private fun sourceEmissionAnchors(source: String, dialect: Seq3Dialect): List<SourceEmissionAnchor> {
    val emissions = mutableListOf<SourceEmissionAnchor>()
    var pending: SourceMarker? = null
    source.lineSequence().forEachIndexed { index, raw ->
        val text = raw.trim()
        parseSourceMarker(text, dialect)?.let { marker ->
            pending = marker.takeIf { it.type == "message" }
            return@forEachIndexed
        }
        val marker = pending ?: return@forEachIndexed
        if (parseArrow(text, dialect) != null || parseMessageNote(text, dialect) != null) {
            emissions += SourceEmissionAnchor(index + 1, marker.id, marker.entryId)
            pending = null
        } else if (text.isNotBlank() && !isSourceComment(text, dialect) &&
            !text.startsWith("activate ") && !text.startsWith("deactivate ") &&
            !text.startsWith("create ") && !text.startsWith("destroy ")
        ) {
            pending = null
        }
    }
    return emissions
}

/** Binds a directive to the previous source emission, as the emitters place it after that row.
 *  Before the first row, it binds to the first emission. This uses statement order rather than
 *  line distance so added whitespace or comments cannot change the associated row. An open
 *  activation is the model's "through the end" form and is normalized to an explicit close at the
 *  last emission. */
private fun bindSourceActivations(
    source: String,
    emissions: List<SourceEmissionAnchor>,
): List<BoundSourceActivation> {
    val directives = source.lineSequence().mapIndexedNotNull { index, raw ->
        parseActivationDirective(raw.trim())?.let { (activate, alias) ->
            SourceActivationDirective(index + 1, activate, alias)
        }
    }.toList()
    return bindSourceActivations(directives, emissions)
}

private fun bindSourceActivations(
    directives: List<SourceActivationDirective>,
    emissions: List<SourceEmissionAnchor>,
): List<BoundSourceActivation> {
    fun anchorFor(line: Int): SourceEmissionAnchor? = emissions.lastOrNull { it.line < line } ?: emissions.firstOrNull()

    val result = mutableListOf<BoundSourceActivation>()
    val open = mutableListOf<String>()
    directives.forEach { directive ->
        val anchor = anchorFor(directive.line) ?: return@forEach
        result += BoundSourceActivation(directive.activate, directive.alias, anchor.messageId, anchor.occurrenceEntryId)
        if (directive.activate) {
            open += directive.alias
        } else {
            val openIndex = open.indexOfLast { it == directive.alias }
            if (openIndex >= 0) open.removeAt(openIndex)
        }
    }
    val end = emissions.lastOrNull()
    if (end != null) {
        open.asReversed().forEach { alias ->
            result += BoundSourceActivation(false, alias, end.messageId, end.occurrenceEntryId)
        }
    }
    return result
}

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
        lower.startsWith("note ") || text.startsWith("...")
}
