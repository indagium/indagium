package com.indagium.diagram3

import com.indagium.debug.Json
import java.util.Base64
import java.util.UUID

/** One pure source edit that resolves a diagnostic: insert [text] as a new line before raw source
 *  line [insertLine] (1-based; one past the last line appends).  [text] carries its own indent. */
data class Seq3SourceFix(val label: String, val insertLine: Int, val text: String)

/** A location-aware rejection.  Import is deliberately all-or-nothing. */
data class Seq3SourceDiagnostic(val line: Int, val message: String, val fix: Seq3SourceFix? = null)

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

private data class ParsedArrow(
    val from: String,
    val to: String?,
    val label: String,
    val kind: Seq3Kind,
    /** Mermaid `A->>+B` shorthand: activate the target after this row. */
    val activateTarget: Boolean = false,
    /** Mermaid `B-->>-A` shorthand: deactivate the sender after this row. */
    val deactivateSource: Boolean = false,
)

/** [name]/[lifelineKind] are set by Mermaid's `create participant X as Y`, which also declares X. */
private data class LifecycleDirective(
    val kind: Seq3Kind,
    val targetAlias: String,
    val name: String? = null,
    val lifelineKind: Seq3LifelineKind? = null,
)

private data class ParsedOperand(val keyword: String, val guard: String)

/** A divider waiting for the next emitted row.  [marker] is null for a hand-typed divider. */
private data class PendingOperand(
    val marker: SourceMarker?,
    val fragmentId: String,
    val keyword: String,
    val guard: String,
    val line: Int,
    val newOperandId: String = "import-${UUID.randomUUID()}",
)

/** [afterOwnRow] is true for the Mermaid `+`/`-` arrow shorthand, whose directive belongs to the
 *  row on its own line rather than to the previous one. */
private data class SourceActivationDirective(
    val line: Int,
    val activate: Boolean,
    val alias: String,
    val afterOwnRow: Boolean = false,
)

/** A marked message's physical source line: [line] is 1-based and raw (marker lines included). The
 *  editor runs [sourceEmissionAnchors] on its live text to link a caret line to a diagram message. */
internal data class SourceEmissionAnchor(val line: Int, val messageId: String, val occurrenceEntryId: Int?)

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

    fun fail(line: Int, message: String, fix: Seq3SourceFix? = null) {
        errors += Seq3SourceDiagnostic(line, message, fix)
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
    val emittedOperandIds = sourceMarkerOperandIds(existingSource, dialect)
    val pendingOperands = mutableListOf<PendingOperand>()
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
    val newFragmentLines = mutableMapOf<String, Int>()
    val createdDeclared = mutableSetOf<String>() // lifelines declared by `create`, not in the header
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
    var headerSeen = false
    var autonumber = false

    /** Binds every divider typed or marked since the last row to the row that is emitted now. */
    fun consumePendingOperands(messageId: String, entryId: Int?) {
        pendingOperands.toList().forEach { pendingOperand ->
            val operandMarker = pendingOperand.marker
            if (operandMarker == null) {
                rebuiltOperands.getOrPut(pendingOperand.fragmentId, ::mutableListOf).add(
                    Seq3Operand(pendingOperand.newOperandId, pendingOperand.guard, messageId, entryId),
                )
                return@forEach
            }
            val (fragmentId, old) = knownOperands[operandMarker.id] ?: return@forEach
            rebuiltOperands.getOrPut(fragmentId, ::mutableListOf).add(
                old.copy(
                    guard = pendingOperand.guard,
                    startsAtMessageId = messageId,
                    startsAtOccurrenceEntryId = if (old.startsAtOccurrenceEntryId == null) null else entryId,
                ),
            )
        }
        pendingOperands.clear()
    }

    fun recordEmission(marker: SourceMarker, line: Int? = null) {
        importedRowOrder += Seq3RowRef(marker.id, marker.entryId)
        if (line != null) sourceEmissions += SourceEmissionAnchor(line, marker.id, marker.entryId)
        precedingMessageId = marker.id
        consumePendingOperands(marker.id, marker.entryId)
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

    /** The one-line "Add participant X" edit: after the last declaration above [beforeLine], or
     *  after the header/title when nothing is declared yet. */
    fun declarationFix(alias: String, beforeLine: Int): Seq3SourceFix? {
        if (alias.any { it == '"' || it == '\\' || it.isWhitespace() }) return null
        var lastDeclaration = -1
        var headerOrTitle = -1
        for (i in 0 until minOf(beforeLine - 1, lines.size)) {
            val t = lines[i].trim()
            when {
                t == expectedStart && headerOrTitle < 0 -> headerOrTitle = i
                t.startsWith("title ") -> headerOrTitle = i
                parseParticipant(t, dialect) != null -> lastDeclaration = i
            }
        }
        val anchor = if (lastDeclaration >= 0) lastDeclaration else headerOrTitle
        val indent = when {
            lastDeclaration >= 0 -> lines[lastDeclaration].takeWhile { it == ' ' || it == '\t' }
            dialect == Seq3Dialect.MERMAID -> "    "
            else -> ""
        }
        val declaration = if (dialect == Seq3Dialect.MERMAID) "participant $alias as $alias" else "participant \"$alias\" as $alias"
        return Seq3SourceFix("Add participant $alias", anchor + 2, indent + declaration)
    }

    fun failUndeclared(line: Int, alias: String) =
        fail(line, "Participant '$alias' isn't declared.", declarationFix(alias, line))

    /** Registers one participant declaration.  [name]/[kind] are null when the statement does not
     *  restate them (PlantUML `create X`), which keeps a known lifeline unchanged. */
    fun declareParticipant(
        lineNo: Int,
        alias: String,
        name: String?,
        kind: Seq3LifelineKind?,
        marker: SourceMarker?,
        created: Boolean = false,
    ) {
        val id = marker?.takeIf { it.type == "lifeline" }?.id ?: aliases[alias]
            ?: newImportedLifelineId(alias, existing.lifelines, lifelineEdits.values)
        aliases[alias] = id
        if (!declaredAliases.add(alias) || id in declaredLifelines) {
            fail(lineNo, "Duplicate participant declaration '$alias'.")
        }
        declaredLifelines += id
        if (created) createdDeclared += id
        val previous = knownLifelines[id]
        if (previous != null) {
            val renderedName = seq3DisplayName(
                previous.name,
                previous.displaySegments,
                existing.lifelineDisplaySegments,
            )
            lifelineEdits[id] = if ((name == null || name == renderedName) && (kind == null || kind == previous.kind)) {
                previous
            } else {
                previous.copy(name = name ?: previous.name, kind = kind ?: previous.kind)
            }
        } else {
            val resolvedName = name ?: alias
            lifelineEdits[id] = Seq3Lifeline(
                id,
                resolvedName,
                setOf(resolvedName),
                existing.lifelines.size + lifelineEdits.size,
                kind = kind ?: Seq3LifelineKind.PARTICIPANT,
            )
        }
    }

    /** The Mermaid `+`/`-` arrow shorthand is an activate/deactivate directive after its own row. */
    fun addShorthandActivation(arrow: ParsedArrow, lineNo: Int) {
        if (arrow.activateTarget && arrow.to != null) {
            sourceActivationDirectives += SourceActivationDirective(lineNo, true, arrow.to, afterOwnRow = true)
        }
        if (arrow.deactivateSource) {
            sourceActivationDirectives += SourceActivationDirective(lineNo, false, arrow.from, afterOwnRow = true)
        }
    }

    fun fragmentKindOf(fragmentId: String): Seq3FragmentKind? =
        fragmentEdits[fragmentId]?.kind
            ?: knownFragments[fragmentId]?.kind
            ?: newFragments.firstOrNull { it.id == fragmentId }?.kind

    fun fragmentHasRows(fragmentId: String, isNew: Boolean): Boolean =
        if (isNew) !newFragmentMessages[fragmentId].isNullOrEmpty() else !rebuiltFragmentMessages[fragmentId].isNullOrEmpty()

    /** A divider block can no longer own a divider that waits for a row it will never get. */
    fun failDanglingDividers(fragmentId: String) {
        pendingOperands.filter { it.fragmentId == fragmentId }.forEach {
            fail(it.line, "Add a message below this '${it.keyword}' before the block ends.")
            pendingOperands.remove(it)
        }
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
        if (text == expectedStart) {
            if (headerSeen) fail(lineNo, "Only one diagram per source.")
            headerSeen = true
            return@forEachIndexed
        }
        if (dialect == Seq3Dialect.PLANTUML && text == "@enduml") return@forEachIndexed
        if (text == "autonumber") { autonumber = true; return@forEachIndexed }
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
                // The creating statement is the created lifeline's only declaration, so it must
                // register the alias for a source that owns its lifelines.  A header declaration
                // of the same alias wins; the lifecycle check then judges the `create` itself.
                if (directive.kind == Seq3Kind.CREATE && directive.targetAlias !in declaredAliases) {
                    declareParticipant(
                        lineNo,
                        directive.targetAlias,
                        directive.name,
                        directive.lifelineKind,
                        marker = null,
                        created = true,
                    )
                }
                lifecycleBeforeArrow = directive
            }
            return@forEachIndexed
        }

        parseParticipant(text, dialect)?.let { (alias, name, kind) ->
            lastMarkedArrow = null
            val marker = pending.also { pending = null }
            declareParticipant(lineNo, alias, name, kind, marker)
            return@forEachIndexed
        }
        parseArrow(text, dialect)?.let { arrow ->
            val marker = pending.also { pending = null }
            val from = aliases[arrow.from] ?: arrow.from.takeIf { it in knownLifelines }
            val to = arrow.to?.let { aliases[it] ?: it.takeIf { candidate -> candidate in knownLifelines } }
            val undeclared = listOfNotNull(arrow.from, arrow.to).distinct().filter { alias ->
                val resolved = (aliases[alias] ?: alias.takeIf { it in knownLifelines }) != null
                !resolved || (sourceOwnsVisibleLifelines && alias !in declaredAliases)
            }
            if (undeclared.isNotEmpty()) {
                undeclared.forEach { failUndeclared(lineNo, it) }
                return@forEachIndexed
            }
            if (from == null || (arrow.to != null && to == null)) return@forEachIndexed
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
                addShorthandActivation(arrow, lineNo)
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
                consumePendingOperands(newMessage.id, null)
                addShorthandActivation(arrow, lineNo)
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
                if (pendingOperands.isNotEmpty()) {
                    // The emitter writes a block's opener before the divider that shares its first
                    // row, so a divider directly above an opener would move into that block.
                    pendingOperands.forEach {
                        fail(it.line, "Not supported yet: a block opening right after '${it.keyword}'. Put a message in between.")
                    }
                    pendingOperands.clear()
                }
                val id = "import-${UUID.randomUUID()}"
                newFragments += Seq3Fragment(id, kind, label, emptyList())
                newFragmentLines[id] = lineNo
                activeNewFragments.addLast(id)
                fragmentOpenOrder.addLast(true to id)
                return@forEachIndexed
            }
            if (marker.type != "fragment") {
                fail(lineNo, "${marker.type} marker cannot annotate a fragment.")
                return@forEachIndexed
            }
            val old = knownFragments[marker.id] ?: return@forEachIndexed
            if (pendingOperands.isNotEmpty()) {
                pendingOperands.forEach {
                    fail(it.line, "Not supported yet: a block opening right after '${it.keyword}'. Put a message in between.")
                }
                pendingOperands.clear()
            }
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
            if (fragmentId == UNSUPPORTED_BLOCK) return@forEachIndexed
            failDanglingDividers(fragmentId)
            if (isNew) {
                val openId = activeNewFragments.removeLastOrNull()
                if (openId != fragmentId) fail(lineNo, "Fragment ends do not match their start order.")
                if (newFragmentMessages[fragmentId].isNullOrEmpty()) {
                    val kindWord = fragmentKindOf(fragmentId)?.name?.lowercase() ?: "block"
                    fail(newFragmentLines[fragmentId] ?: lineNo, "Add a message inside $kindWord.")
                }
            } else if (fragmentStack.isNotEmpty()) {
                fragmentStack.removeLast()
            } else {
                fail(lineNo, "Fragment end has no matching fragment start.")
            }
            return@forEachIndexed
        }
        parseOperand(text, dialect)?.let { divider ->
            val marker = pending.also { pending = null }
            if (marker != null && marker.type != "operand") {
                fail(lineNo, "${marker.type} marker cannot annotate a divider.")
                return@forEachIndexed
            }
            if (marker != null) {
                val (fragmentId, _) = knownOperands[marker.id] ?: return@forEachIndexed
                if (marker.role != fragmentId) {
                    fail(lineNo, "Operand marker belongs to a different fragment.")
                    return@forEachIndexed
                }
                pendingOperands += PendingOperand(marker, fragmentId, divider.keyword, divider.guard, lineNo)
                return@forEachIndexed
            }
            val (ownerIsNew, ownerId) = fragmentOpenOrder.lastOrNull() ?: run {
                fail(lineNo, "'${divider.keyword}' needs an open alt, par or critical block.")
                return@forEachIndexed
            }
            val ownerKind = if (ownerId == UNSUPPORTED_BLOCK) null else fragmentKindOf(ownerId)
            val dividerError = dividerKindError(divider.keyword, ownerKind, dialect)
            if (dividerError != null) {
                fail(lineNo, dividerError)
                return@forEachIndexed
            }
            if (!fragmentHasRows(ownerId, ownerIsNew)) {
                fail(lineNo, "Add a message before '${divider.keyword}'; a branch needs a row above it.")
                return@forEachIndexed
            }
            pendingOperands += PendingOperand(null, ownerId, divider.keyword, divider.guard, lineNo)
            return@forEachIndexed
        }
        // Structural statements the emitters write are consumed above; a marked Mermaid `rect`
        // is the one that carries no model entity of its own.
        if (RECT_STATEMENT.containsMatchIn(text) && pending?.type == "fragment") return@forEachIndexed
        pending = null
        if (dialect == Seq3Dialect.MERMAID && RECT_STATEMENT.containsMatchIn(text)) {
            // Its `end` must close this block, not an enclosing fragment.
            fragmentOpenOrder.addLast(false to UNSUPPORTED_BLOCK)
        }
        fail(lineNo, unparsedStatementMessage(text))
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
        sourceActivationDirectives.forEach { directive ->
            if (aliases[directive.alias] == null) {
                failUndeclared(directive.line, directive.alias)
                return@forEach
            }
            val anchor = anchorForDirective(sourceEmissions, directive)
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
    pendingOperands.forEach { fail(it.line, "Add a message below this '${it.keyword}' before the block ends.") }
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
        orderDeclaredLifelines(declaredLifelines, createdDeclared, existing.lifelines)
            .mapIndexedNotNull { index, id -> lifelineEdits[id]?.copy(ordinal = index) } +
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
                // A fragment that is still in the source owns its dividers: a divider added or
                // removed there is a real edit.  Operands the source never showed (an anchor the
                // emitter drops) and kinds that draw no dividers keep their stored value.
                val withOperands = if (fragment.id in seenMarkedFragments && edited.kind in OPERAND_FRAGMENT_KINDS) {
                    val rebuilt = rebuiltOperands[fragment.id].orEmpty()
                    edited.copy(
                        elseOperands = rebuilt + fragment.elseOperands.filter { old ->
                            old.id !in emittedOperandIds && rebuilt.none { it.id == old.id }
                        },
                    )
                } else {
                    edited
                }
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
            fragment.copy(
                messageIds = newFragmentMessages[fragment.id].orEmpty().distinct(),
                elseOperands = rebuiltOperands[fragment.id].orEmpty(),
            )
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
        showSequenceNumbers = existing.showSequenceNumbers || autonumber,
    )
    val orderedBase = if (orderChanged) {
        resultBase.copy(rowOrder = mergeImportedRowOrder(existing, resultBase, baselineRows, observedRows, missingRows))
    } else {
        resultBase
    }
    val result = reconcileSeq3RowOrder(existing, orderedBase)
    seq3DocumentBoundsViolation(result)?.let { violation ->
        if (violation == SEQ3_HEADER_SIZE_VIOLATION) {
            return Seq3SourceImportResult.Failure(
                listOf(
                    Seq3SourceDiagnostic(
                        sourceEmissions.lastOrNull()?.line ?: 1,
                        "Too many rows for one diagram (${result.messages.size}).",
                    ),
                ),
            )
        }
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

/** Stands in `fragmentOpenOrder` for a block the model cannot hold, so its `end` closes it. */
private const val UNSUPPORTED_BLOCK = ""
private val RECT_STATEMENT = Regex("^rect\\b")
private val OPERAND_FRAGMENT_KINDS = setOf(Seq3FragmentKind.ALT, Seq3FragmentKind.PAR, Seq3FragmentKind.CRITICAL)

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

private const val BARE_ALIAS = "[^\\s\"'@{}<>#:;,]+"

private fun parseParticipant(text: String, dialect: Seq3Dialect): Triple<String, String, Seq3LifelineKind>? {
    fun kindOf(word: String) = if (word == "actor") Seq3LifelineKind.ACTOR else Seq3LifelineKind.PARTICIPANT
    if (dialect == Seq3Dialect.MERMAID) {
        Regex("^(participant|actor)\\s+(\\S+)\\s+as\\s+(.+)$").matchEntire(text)?.let {
            return Triple(it.groupValues[2], unescapeSource(it.groupValues[3], dialect), kindOf(it.groupValues[1]))
        }
        Regex("^(participant|actor)\\s+($BARE_ALIAS)$").matchEntire(text)?.let {
            return Triple(it.groupValues[2], it.groupValues[2], kindOf(it.groupValues[1]))
        }
        return null
    }
    Regex("^(participant|actor)\\s+\"(.*)\"\\s+as\\s+(\\S+)$").matchEntire(text)?.let {
        return Triple(it.groupValues[3], unescapeSource(it.groupValues[2], dialect), kindOf(it.groupValues[1]))
    }
    Regex("^(participant|actor)\\s+($BARE_ALIAS)\\s+as\\s+($BARE_ALIAS)$").matchEntire(text)?.let {
        return Triple(it.groupValues[3], it.groupValues[2], kindOf(it.groupValues[1]))
    }
    Regex("^(participant|actor)\\s+($BARE_ALIAS)$").matchEntire(text)?.let {
        return Triple(it.groupValues[2], it.groupValues[2], kindOf(it.groupValues[1]))
    }
    return null
}

/** The editor's view of a participant declaration line, so it accepts exactly what the import does. */
internal fun parseSeq3SourceParticipant(text: String, dialect: Seq3Dialect): Triple<String, String, Seq3LifelineKind>? =
    parseParticipant(text.trim(), dialect)

/** The sender and receiver of an arrow line, as the import reads it. */
internal fun parseSeq3SourceArrowEndpoints(text: String, dialect: Seq3Dialect): Pair<String, String?>? =
    parseArrow(text.trim(), dialect)?.let { it.from to it.to }

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

private val MERMAID_CREATE = Regex("^create\\s+(participant|actor)\\s+(\\S+)(?:\\s+as\\s+(.+))?$")

private fun parseLifecycleDirective(text: String, dialect: Seq3Dialect): LifecycleDirective? {
    if (text.startsWith("destroy ")) {
        return text.removePrefix("destroy ").trim().takeIf(String::isNotBlank)
            ?.let { LifecycleDirective(Seq3Kind.DESTROY, it) }
    }
    if (!text.startsWith("create ")) return null
    if (dialect == Seq3Dialect.MERMAID) {
        val match = MERMAID_CREATE.matchEntire(text) ?: return null
        return LifecycleDirective(
            Seq3Kind.CREATE,
            match.groupValues[2],
            match.groups[3]?.value?.let { unescapeSource(it, dialect) },
            if (match.groupValues[1] == "actor") Seq3LifelineKind.ACTOR else Seq3LifelineKind.PARTICIPANT,
        )
    }
    val alias = text.removePrefix("create ").trim().substringBefore(' ').trim()
    return alias.takeIf(String::isNotBlank)?.let { LifecycleDirective(Seq3Kind.CREATE, it) }
}

private fun parseActivationDirective(text: String): Pair<Boolean, String>? {
    val match = Regex("^(activate|deactivate)\\s+(\\S+)$").matchEntire(text) ?: return null
    return (match.groupValues[1] == "activate") to match.groupValues[2]
}

private val NOTE_STATEMENT_START = Regex("(?i)^note\\s+(left|right|over|top|bottom)\\b")
private val MERMAID_ARROW = Regex("^(\\S+?)\\s*(-->>|->>|-\\))\\s*([+-]?)(\\S+?)\\s*:\\s?(.*)$")
private val PLANTUML_ARROW = Regex("^(\\S+?)\\s*(-->|->>|->)\\s*(\\S+?)\\s*:\\s?(.*)$")
private val PLANTUML_LOST = Regex("^(\\S+?)\\s*->o\\]\\s*:\\s?(.*)$")
private val PLANTUML_FOUND = Regex("^\\[o->\\s*(\\S+?)\\s*:\\s?(.*)$")

private fun parseArrow(text: String, dialect: Seq3Dialect): ParsedArrow? {
    if (NOTE_STATEMENT_START.containsMatchIn(text) || text.startsWith("...") || text.startsWith("ref ")) return null
    if (dialect == Seq3Dialect.PLANTUML) {
        PLANTUML_LOST.matchEntire(text)?.let {
            return ParsedArrow(it.groupValues[1], null, unescapeSource(it.groupValues[2], dialect), Seq3Kind.LOST)
        }
        PLANTUML_FOUND.matchEntire(text)?.let {
            return ParsedArrow(it.groupValues[1], null, unescapeSource(it.groupValues[2], dialect), Seq3Kind.FOUND)
        }
    }
    val m: MatchResult
    val token: String
    val sign: String
    val to: String
    val label: String
    if (dialect == Seq3Dialect.MERMAID) {
        m = MERMAID_ARROW.matchEntire(text) ?: return null
        token = m.groupValues[2]
        sign = m.groupValues[3]
        to = m.groupValues[4]
        label = m.groupValues[5]
    } else {
        m = PLANTUML_ARROW.matchEntire(text) ?: return null
        token = m.groupValues[2]
        sign = ""
        to = m.groupValues[3]
        label = m.groupValues[4]
    }
    val kind = when {
        token == "-->" || token == "-->>" -> Seq3Kind.RETURN
        dialect == Seq3Dialect.PLANTUML && token == "->>" -> Seq3Kind.ASYNC
        dialect == Seq3Dialect.MERMAID && token == "-)" -> Seq3Kind.ASYNC
        else -> Seq3Kind.CALL
    }
    return ParsedArrow(
        m.groupValues[1],
        to,
        unescapeSource(label.removeSuffixRegex(Regex(" ×\\d+$")), dialect),
        kind,
        activateTarget = sign == "+",
        deactivateSource = sign == "-",
    )
}

private val MESSAGE_NOTE = Regex("(?i)^note\\s+(?:right\\s+of|over)\\s+([^:]+?)\\s*:\\s?(.*)$")

private fun parseMessageNote(text: String, dialect: Seq3Dialect): ParsedArrow? {
    val m = MESSAGE_NOTE.matchEntire(text) ?: return null
    val scope = m.groupValues[1].split(',').joinToString(",") { it.trim() }
    return ParsedArrow(scope, null, unescapeSource(m.groupValues[2], dialect), Seq3Kind.NOTE)
}

private val ARROW_AFTER_KEYWORD = Regex("^(?:-->>|->>|-->|->|-\\)|->o\\]|\\[o->)")

private fun parseFragmentOpen(text: String, dialect: Seq3Dialect): Pair<Seq3FragmentKind, String>? {
    val normalized = text.trim()
    if (dialect == Seq3Dialect.PLANTUML) {
        val ref = parsePlantUmlRef(normalized)
        if (ref != null) return Seq3FragmentKind.REF to unescapeSource(ref.second, dialect)
    }
    val word = normalized.takeWhile { !it.isWhitespace() }
    val kind = runCatching { Seq3FragmentKind.valueOf(word.uppercase()) }.getOrNull() ?: return null
    if (kind == Seq3FragmentKind.REF) return null
    val rest = normalized.removePrefix(word)
    // `Loop -> B : x` is a message from a participant that happens to be called Loop.
    if (ARROW_AFTER_KEYWORD.containsMatchIn(rest.trimStart())) return null
    return kind to unescapeSource(if (rest.isEmpty()) "" else rest.substring(1), dialect)
}

private fun parsePlantUmlRef(text: String): Pair<String, String>? {
    val match = Regex("^ref over (\\S+)\\s+:\\s?(.*)$").matchEntire(text) ?: return null
    return match.groupValues[1] to match.groupValues[2]
}

private fun parsePlantUmlRefScope(text: String): String? = parsePlantUmlRef(text)?.first

private fun parseOperand(text: String, dialect: Seq3Dialect): ParsedOperand? {
    val word = text.takeWhile { !it.isWhitespace() }
    val keyword = word.lowercase()
    if (keyword != "else" && keyword != "and" && keyword != "option") return null
    val rest = text.removePrefix(word)
    return ParsedOperand(keyword, unescapeSource(if (rest.isEmpty()) "" else rest.substring(1), dialect))
}

/** Why [keyword] cannot divide a block of [kind], or null when the emitters would write it back. */
private fun dividerKindError(keyword: String, kind: Seq3FragmentKind?, dialect: Seq3Dialect): String? {
    if (kind == null || kind !in OPERAND_FRAGMENT_KINDS) {
        val owner = kind?.name?.lowercase() ?: "this"
        return "A $owner block can't be divided by '$keyword'; only alt, par and critical blocks can."
    }
    val expected = when {
        dialect == Seq3Dialect.PLANTUML -> "else"
        kind == Seq3FragmentKind.ALT -> "else"
        kind == Seq3FragmentKind.PAR -> "and"
        else -> "option"
    }
    if (keyword == expected) return null
    val dialectName = if (dialect == Seq3Dialect.MERMAID) "Mermaid" else "PlantUML"
    return "$dialectName divides a ${kind.name.lowercase()} block with '$expected', not '$keyword'."
}

/** Valid dialect syntax the model cannot hold gets "Not supported yet"; anything else is plain noise. */
private val NOT_SUPPORTED_WORDS = setOf(
    "autonumber", "box", "link", "links", "properties", "details", "rect", "participant", "actor", "boundary",
    "control", "entity", "database", "collections", "queue", "note", "hnote", "rnote", "return", "skinparam",
    "hide", "show", "header", "footer", "legend", "newpage", "ref", "activate", "deactivate", "create", "destroy",
    "end", "title", "theme",
)
private val ARROW_SHAPE = Regex("\\S\\s*(?:<{1,2}-{1,2}>{0,2}|-{1,2}>{1,2}|-{1,2}[x)]|-{1,2}>[ox]?)\\s*\\S")

private fun unparsedStatementMessage(text: String): String {
    val word = text.takeWhile { !it.isWhitespace() }.lowercase()
    val recognised = word in NOT_SUPPORTED_WORDS ||
        text.startsWith("==") || text.startsWith("|||") || text.startsWith("||") || text.startsWith("...") ||
        text.startsWith("!") || arrowAfterSingleParticipant(text.substringBefore(':'))
    return if (recognised) "Not supported yet: $text" else "Unrecognized statement: $text"
}

/** An arrow whose left side is one participant token, an identifier or a quoted name: an arrow form
 *  the model cannot hold. Anything else in front of an arrow (`x    B -> Alice`) is noise, not a
 *  statement the dialect has. */
private fun arrowAfterSingleParticipant(head: String): Boolean {
    val arrow = ARROW_SHAPE.find(head) ?: return false
    val before = head.substring(0, arrow.range.first + 1).trim()
    return SINGLE_PARTICIPANT_TOKEN.matches(before)
}

private val SINGLE_PARTICIPANT_TOKEN = Regex("(?:\"[^\"]+\"|[\\p{L}\\p{N}_.$-]+)")

private fun sourceMarkerOperandIds(source: String, dialect: Seq3Dialect): Set<String> =
    source.lineSequence().mapNotNull { line ->
        parseSourceMarker(line.trim(), dialect)?.takeIf { it.type == "operand" }?.id
    }.toSet()

/** Header-declared participants keep their text order.  A participant declared by `create` has no
 *  header line, so it keeps its prior column instead of sliding to where its `create` statement
 *  sits; one that is new goes last. */
private fun orderDeclaredLifelines(
    declared: List<String>,
    created: Set<String>,
    existing: List<Seq3Lifeline>,
): List<String> {
    val ordered = declared.filter { it !in created }.toMutableList()
    declared.filter { it in created }.forEach { id ->
        val oldIndex = existing.indexOfFirst { it.id == id }
        if (oldIndex < 0) {
            ordered += id
            return@forEach
        }
        val prior = existing.subList(0, oldIndex).map { it.id }.lastOrNull { it in ordered }
        ordered.add(if (prior == null) 0 else ordered.indexOf(prior) + 1, id)
    }
    return ordered
}

/** The row a directive belongs to: the previous row, or the row on its own line for `+`/`-`. */
private fun anchorForDirective(emissions: List<SourceEmissionAnchor>, directive: SourceActivationDirective): SourceEmissionAnchor? =
    emissions.lastOrNull { it.line < directive.line || (directive.afterOwnRow && it.line == directive.line) }
        ?: emissions.firstOrNull()

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
internal fun sourceEmissionAnchors(source: String, dialect: Seq3Dialect): List<SourceEmissionAnchor> {
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
    val result = mutableListOf<BoundSourceActivation>()
    val open = mutableListOf<String>()
    directives.forEach { directive ->
        val anchor = anchorForDirective(emissions, directive) ?: return@forEach
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
