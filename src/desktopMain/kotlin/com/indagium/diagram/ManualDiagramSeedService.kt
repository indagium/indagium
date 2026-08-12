package com.indagium.diagram

enum class ManualDiagramSeedStrategy {
    SOURCE_TRACE,
    THREAD_HANDOFFS;

    val label: String
        get() = when (this) {
            SOURCE_TRACE -> "source execution trace"
            THREAD_HANDOFFS -> "same-thread handoffs"
        }
}

/** Ephemeral choices for rebuilding a manual document's starting point. These are workspace UI
 * state, not part of the persisted diagram format. A complete source trace owns structure when
 * available; same-thread handoffs are only fallback evidence for uncovered/ambiguous rows. */
data class ManualDiagramSeedConfiguration(
    val reconstructSourceTrace: Boolean = true,
    val inferThreadHandoffs: Boolean = false,
) {
    val enabled: Boolean get() = reconstructSourceTrace || inferThreadHandoffs

    val label: String
        get() = buildList {
            if (reconstructSourceTrace) add("source trace")
            if (inferThreadHandoffs) add("same-thread handoffs")
        }.joinToString(" + ")
}

/**
 * Converts an inferred model into the durable manual document used by the authoring editor.
 * Occurrences are deliberately kept as separate interactions even when they share a group key;
 * the UI can edit the group as one row and still detach one occurrence for a precise correction.
 */
fun manualDocumentFromDiagram(diagram: SeqDiagram): ManualDiagramDocument {
    val interactions = buildList {
        // Keep the primary log event for every selected row, plus source-trace structure. A source
        // trace's call/return/async arrows carry a real execution boundary and must not disappear
        // merely because they supplement the log event for the same row. Other supplemental
        // presentation (actor mirrors) remains inferred-only to avoid creating duplicate editable
        // rows for the same ordinary log evidence. Same-thread handoffs are an explicit seed
        // choice, so they become durable structure when present. Retain the old fallback for
        // callers that construct structural-only diagrams.
        val seedMessages = diagram.messages.filter {
            it.primary ||
                (it.evidence == MessageEvidence.SOURCE_INFERRED && it.kind != MessageKind.SELF) ||
                (it.evidence == MessageEvidence.THREAD_HANDOFF && it.kind != MessageKind.SELF)
        }
            .ifEmpty { diagram.messages }
        var nextOrder = 0L
        seedMessages.forEachIndexed { messageIndex, message ->
            val from = diagram.participants.getOrNull(message.fromIdx) ?: return@forEachIndexed
            val to = diagram.participants.getOrNull(message.toIdx) ?: return@forEachIndexed
            val occurrenceIds = message.representedEntryIds.ifEmpty { setOf(message.entryId) }.toList().sorted()
            val sourceMethodId = message.sourceOperationId
            val sourceLogSiteId = message.sourceLogSiteId
            // A regular log line is evidence, not a method invocation. Keep its complete text as a
            // literal label so values such as "detached=vendorId=…" are not guessed into a fake
            // operation/parameter structure. Source-inferred calls retain the structured editor
            // representation because the source index provides a real operation boundary.
            val label = stripDiagramPresentationPrefixes(message.label)
            val isSourceCall = message.evidence == MessageEvidence.SOURCE_INFERRED ||
                (sourceMethodId != null && sourceLogSiteId != null)
            val parameters = if (isSourceCall) extractManualParameters(label) else emptyList()
            val normalizedMessage = normalizeManualMessage(label)
            val operation = if (isSourceCall) manualOperationLabel(label) else label
            val groupKey = manualInteractionGroupKey(
                sourceMethodId = sourceMethodId,
                sourceLogSiteId = sourceLogSiteId,
                fromParticipantId = from.id,
                toParticipantId = to.id,
                kind = message.kind,
                label = normalizedMessage,
            )
            occurrenceIds.forEachIndexed { occurrenceIndex, entryId ->
                add(
                    ManualDiagramInteraction(
                        id = "manual:$messageIndex:$entryId:$occurrenceIndex",
                        sourceEntryIds = setOf(entryId),
                        fromParticipantId = from.id,
                        toParticipantId = to.id,
                        operation = operation,
                        parameters = parameters,
                        label = label.takeUnless { isSourceCall },
                        kind = message.kind,
                        // Each occurrence remains independently editable. Incrementing through the
                        // already-ordered model produces stable, unique ordering even for a very
                        // large collapsed run (where the old fixed stride could collide).
                        order = nextOrder++,
                        groupKey = groupKey,
                        sourceMethodId = sourceMethodId,
                        sourceLogSiteId = sourceLogSiteId,
                        sourceOwnerType = from.sourceOwnerType,
                        renderAnchorTs = message.ts,
                        renderAnchorLevel = message.level,
                    ),
                )
            }
        }
    }
    return ManualDiagramDocument(interactions = interactions)
}

// The generated diagram label may contain optional presentation-only timestamp/elapsed prefixes.
// They must never become part of a durable manual interaction's operation or literal log text.
private val DIAGRAM_PRESENTATION_PREFIX = Regex(
    """^(?:[+-](?:\d+(?:\.\d{3})?|\d+m\d{2}s|\d+h\d{2}m\d{2}s)|\d{2}:\d{2}:\d{2}(?:\.\d{3})?)\s+""",
)

internal fun stripDiagramPresentationPrefixes(value: String): String {
    var result = value.trim()
    repeat(2) {
        result = DIAGRAM_PRESENTATION_PREFIX.replaceFirst(result, "").trim()
    }
    return result
}

/** Stable grouping identity used by both seeded documents and tests of grouping semantics. */
fun manualInteractionGroupKey(
    sourceMethodId: String?,
    sourceLogSiteId: String?,
    fromParticipantId: String,
    toParticipantId: String,
    kind: MessageKind,
    label: String,
): String {
    val provenance = listOfNotNull(
        sourceMethodId?.takeIf { it.isNotBlank() },
        sourceLogSiteId?.takeIf { it.isNotBlank() },
    )
    return listOf(
        if (provenance.isNotEmpty()) "source:${provenance.joinToString("|")}" else "log",
        fromParticipantId,
        toParticipantId,
        kind.name,
        normalizeManualMessage(label),
    ).joinToString("|")
}

/** Removes volatile argument values while retaining the useful message shape. */
fun normalizeManualMessage(value: String): String {
    return value.trim()
        .replace(Regex("([A-Za-z_][A-Za-z0-9_.-]*)\\s*(?:=|:)\\s*(\\\"[^\\\"]*\\\"|'[^']*'|[^,;\\s)]+)"), "$1=?")
        .replace(Regex("\\b[0-9a-fA-F]{8,}\\b"), "<value>")
        .replace(Regex("\\b\\d+(?:\\.\\d+)?\\b"), "<value>")
        .replace(Regex("\\s+"), " ")
}

/** Separates a readable operation name from named occurrence values while leaving the full
 * normalized template available to the grouping key. */
fun manualOperationLabel(value: String): String {
    val withoutNamedValues = value.replace(
        Regex("\\s+[A-Za-z_][A-Za-z0-9_.-]*\\s*(?:=|:)\\s*(\\\"[^\\\"]*\\\"|'[^']*'|[^,;\\s)]+)"),
        "",
    )
    val withoutArguments = withoutNamedValues.replace(Regex("\\(([^()]*)\\)"), "")
    return withoutArguments.trim().ifBlank { "event" }
}

/** Extracts stable named values from common log/message forms without making them part of group
 * identity. The operation remains the normalized template and each occurrence retains its own
 * values, so grouping never destroys useful evidence. */
fun extractManualParameters(value: String): List<DiagramParameter> {
    val named = Regex("([A-Za-z_][A-Za-z0-9_.-]*)\\s*(?:=|:)\\s*(\\\"[^\\\"]*\\\"|'[^']*'|[^,;\\s)]+)")
        .findAll(value)
        .map {
            val raw = it.groupValues[2]
            DiagramParameter(
                it.groupValues[1],
                raw.removePrefix("\"").removeSuffix("\"").removePrefix("'").removeSuffix("'"),
            )
        }
        .toList()
    if (named.isNotEmpty()) return named
    val call = Regex("[A-Za-z_][A-Za-z0-9_.]*\\((.*)\\)").find(value)?.groupValues?.getOrNull(1).orEmpty()
    if (call.isBlank()) return emptyList()
    return call.split(',').mapIndexedNotNull { index, raw ->
        raw.trim().takeIf(String::isNotEmpty)?.let { DiagramParameter("arg${index + 1}", it) }
    }
}
