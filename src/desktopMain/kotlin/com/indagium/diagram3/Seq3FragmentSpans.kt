package com.indagium.diagram3

// Fragment membership = what the canvas draws.
//
// A fragment's box spans its first to its last member row (`fragmentRowIndices` in [Seq3Layout]),
// whatever sits between. Membership left scattered by older edits (a row dragged out of the middle, a
// marquee that skipped an arrow) therefore disagreed with the box: the list rail, the Artifacts count
// and the drop rule all counted members while the diagram counted the span. [normalizeSeq3FragmentSpans]
// makes the two the same thing, once, at the command choke point and when a document is opened; the
// drop rule in `reassignSeq3FragmentMembership` then keeps it that way on every move.
//

/** Indices into [rows] (the drawn rows, in order) of the rows [fragment] currently holds. */
internal fun seq3FragmentMemberIndices(
    document: Seq3Document,
    fragment: Seq3Fragment,
    messagesById: Map<String, Seq3Message> = document.messages.associateBy { it.id },
): List<Int> {
    val rows = seq3DrawnRowRefs(document)
    val membership = seq3FragmentDrawnMembershipRows(fragment, rows, messagesById)
    return rows.indices.filter { rows[it] in membership }
}

/** How many drawn rows [fragment]'s box covers: from its first to its last member, as the layout
 *  draws it. Zero for a fragment none of whose members is drawn. */
internal fun seq3FragmentDrawnRowCount(document: Seq3Document, fragment: Seq3Fragment): Int {
    val members = seq3FragmentMemberIndices(document, fragment)
    return if (members.isEmpty()) 0 else members.last() - members.first() + 1
}

/**
 * Makes every VISIBLE fragment contain exactly the drawn rows between its first and last member.
 *
 *  - Rows are the drawn ones ([seq3DrawnRowRefs]); a hidden row is not drawn, so it neither counts as
 *    a member nor widens a span, exactly like the layout. A reference to a row that is not drawn
 *    (a hidden occurrence) is kept, so unhiding it brings it back where it was.
 *  - Nesting follows the layout: fragments are walked outermost-first and a child's span is clamped to
 *    its parent's, never the other way round, so a parent is not widened because of a child.
 *  - A message whose every drawn row is inside the span (or that is drawn as one summary row) is held
 *    through `messageIds` alone; a message that is only partly inside is held through exact
 *    `occurrenceRefs` (which take precedence over `messageIds` for their message).
 *  - A fragment that already holds exactly its span, hidden fragments and fragments with no drawn
 *    member are returned untouched, so the function is idempotent and costs nothing on a clean document.
 */
fun normalizeSeq3FragmentSpans(document: Seq3Document): Seq3Document {
    if (document.fragments.none { it.visibility == Seq3Visibility.VISIBLE }) return document
    val rows = seq3DrawnRowRefs(document)
    if (rows.isEmpty()) return document
    val messagesById = document.messages.associateBy { it.id }
    val members = document.fragments.associate { fragment ->
        fragment.id to if (fragment.visibility == Seq3Visibility.VISIBLE) {
            val membership = seq3FragmentDrawnMembershipRows(fragment, rows, messagesById)
            rows.indices.filter { rows[it] in membership }
        } else {
            emptyList()
        }
    }
    val spans = clampedSeq3FragmentSpans(document.fragments, members)
    if (spans.isEmpty()) return document
    val context = SpanContext(document, rows)
    var changed = false
    val fragments = document.fragments.map { fragment ->
        val span = spans[fragment.id] ?: return@map fragment
        val current = members.getValue(fragment.id)
        if (current.size == span.count() && current.all { it in span }) return@map fragment
        changed = true
        context.rematerialize(fragment, span)
    }
    return if (changed) document.copy(fragments = fragments) else document
}

/** Each visible fragment's span, outermost first, a child clamped to the end of its parent. */
private fun clampedSeq3FragmentSpans(fragments: List<Seq3Fragment>, members: Map<String, List<Int>>): Map<String, IntRange> {
    val raw = fragments.mapNotNull { fragment ->
        val indices = members.getValue(fragment.id)
        if (indices.isEmpty()) null else fragment.id to (indices.first()..indices.last())
    }
    val sorted = raw.sortedWith(compareBy({ it.second.first }, { -it.second.last }))
    val stack = ArrayDeque<IntRange>()
    val result = LinkedHashMap<String, IntRange>()
    for ((id, range) in sorted) {
        while (stack.isNotEmpty() && stack.last().last < range.first) stack.removeLast()
        val clamped = stack.lastOrNull()?.let { range.first..minOf(range.last, it.last) } ?: range
        stack.addLast(clamped)
        result[id] = clamped
    }
    return result
}

private class SpanContext(document: Seq3Document, private val rows: List<Seq3RowRef>) {
    private val drawnByMessage = rows.groupBy { it.messageId }
    private val messagePositions = document.messages.withIndex().associate { (index, message) -> message.id to index }
    private val messagesById = document.messages.associateBy { it.id }
    private val occurrencesByMessage = document.messages.associate { message -> message.id to message.occurrences }
    private val visibleLifelineIds = document.lifelines.asSequence()
        .filter { it.visibility == Seq3Visibility.VISIBLE }
        .mapTo(HashSet()) { it.id }

    fun rematerialize(fragment: Seq3Fragment, span: IntRange): Seq3Fragment {
        val inside = rows.slice(span)
        val insideByMessage = inside.groupBy { it.messageId }
        val messageIds = insideByMessage.keys.toMutableSet()
        val refs = ArrayList<Seq3OccurrenceRef>()
        for ((messageId, insideRows) in insideByMessage) {
            addMessageMembershipRefs(fragment, messageId, insideRows, refs)
        }
        addUndrawnMembership(fragment, messageIds, refs)
        collapseFullyRepresentedMessages(insideByMessage.keys, fragment.occurrenceRefs, refs)
        return fragment.copy(
            messageIds = messageIds.sortedBy { messagePositions[it] ?: Int.MAX_VALUE },
            occurrenceRefs = refs.distinct(),
        )
    }

    private fun addMessageMembershipRefs(
        fragment: Seq3Fragment,
        messageId: String,
        insideRows: List<Seq3RowRef>,
        refs: MutableList<Seq3OccurrenceRef>,
    ) {
        val allRows = drawnByMessage.getValue(messageId)
        val exactRefs = fragment.occurrenceRefs.filter { it.messageId == messageId }
        val templateWasHeld = exactRefs.isEmpty() && messageId in fragment.messageIds
        if (templateWasHeld && allRows.all { it in insideRows }) return

        // Keep hidden/dangling refs intact, but strip visible refs for rows outside the clamped span.
        exactRefs.filterTo(refs) { ref -> visibleRowForEvidence(ref)?.let { it in insideRows } ?: true }
        if (templateWasHeld) {
            occurrencesByMessage[messageId].orEmpty()
                .filter { it.visibility == Seq3Visibility.HIDDEN }
                .mapTo(refs) { Seq3OccurrenceRef(messageId, it.entryId) }
        }
        addInsideRowRefs(messageId, insideRows, exactRefs, templateWasHeld, allRows.all { it in insideRows }, refs)
    }

    private fun addInsideRowRefs(
        messageId: String,
        insideRows: List<Seq3RowRef>,
        exactRefs: List<Seq3OccurrenceRef>,
        templateWasHeld: Boolean,
        allRowsInside: Boolean,
        refs: MutableList<Seq3OccurrenceRef>,
    ) {
        insideRows.forEach { row ->
            val entryId = row.occurrenceEntryId
            if (entryId != null) {
                if (exactRefs.isNotEmpty() || templateWasHeld || !allRowsInside) refs += Seq3OccurrenceRef(messageId, entryId)
                return@forEach
            }
            addSummaryRefs(messageId, exactRefs, templateWasHeld, allRowsInside, refs)
        }
    }

    private fun addSummaryRefs(
        messageId: String,
        exactRefs: List<Seq3OccurrenceRef>,
        templateWasHeld: Boolean,
        allRowsInside: Boolean,
        refs: MutableList<Seq3OccurrenceRef>,
    ) {
        val message = messagesById[messageId]
        val firstLastElision = message?.let {
            it.kind != Seq3Kind.NOTE && it.toLifelineId != null && it.repeat == Seq3Repeat.FIRST_LAST
        } == true
        val representedIds = message?.let { seq3SummaryEvidenceEntryIds(it, firstLastElision) }.orEmpty()
        if (allRowsInside && exactRefs.isEmpty() && !templateWasHeld) return
        val previouslyRepresented = exactRefs.filter { it.entryId in representedIds }
        if (previouslyRepresented.isNotEmpty()) {
            refs += previouslyRepresented
        } else {
            // A newly enclosed summary gets exact visible evidence rather than an ID that could
            // also pull in hidden occurrences the fragment never held.
            representedIds.mapTo(refs) { Seq3OccurrenceRef(messageId, it) }
        }
    }

    private fun addUndrawnMembership(
        fragment: Seq3Fragment,
        messageIds: MutableSet<String>,
        refs: MutableList<Seq3OccurrenceRef>,
    ) {
        fragment.messageIds.filterTo(messageIds) { it !in drawnByMessage }
        fragment.occurrenceRefs.filterTo(refs) { it.messageId !in drawnByMessage && it !in refs }
    }

    private fun collapseFullyRepresentedMessages(
        messageIds: Set<String>,
        originalRefs: List<Seq3OccurrenceRef>,
        refs: MutableList<Seq3OccurrenceRef>,
    ) {
        messageIds.forEach { messageId ->
            val message = messagesById[messageId] ?: return@forEach
            if (message.occurrences.any { it.visibility != Seq3Visibility.VISIBLE }) return@forEach
            val occurrenceIds = message.occurrences.mapTo(HashSet()) { it.entryId }
            val originalMessageRefs = originalRefs.filter { it.messageId == messageId }.mapTo(HashSet()) { it.entryId }
            if (originalMessageRefs == occurrenceIds) return@forEach
            val messageRefs = refs.filter { it.messageId == messageId }.mapTo(HashSet()) { it.entryId }
            if (messageRefs == occurrenceIds) refs.removeAll { it.messageId == messageId }
        }
    }

    /** Visible evidence can be represented by a summary row with a different stable row identity
     *  (for example a FIRST_LAST middle occurrence by its elision). Keep a ref only when that
     *  representation is inside the clamped span; hidden and dangling refs stay durable. */
    private fun visibleRowForEvidence(ref: Seq3OccurrenceRef): Seq3RowRef? {
        val message = messagesById[ref.messageId] ?: return null
        val occurrence = occurrencesByMessage[ref.messageId].orEmpty().firstOrNull { it.entryId == ref.entryId } ?: return null
        if (!isVisibleEvidence(message, occurrence)) return null
        val drawnRowsForMessage = drawnByMessage[ref.messageId].orEmpty()
        val direct = drawnRowsForMessage.firstOrNull { it.occurrenceEntryId == ref.entryId }
        if (direct != null) return direct
        val firstLastElision = message.kind != Seq3Kind.NOTE && message.toLifelineId != null && message.repeat == Seq3Repeat.FIRST_LAST
        val representedIds = seq3SummaryEvidenceEntryIds(message, firstLastElision)
        return if (ref.entryId in representedIds && drawnRowsForMessage.any { it.occurrenceEntryId == null }) Seq3RowRef(ref.messageId) else null
    }

    private fun isVisibleEvidence(message: Seq3Message, occurrence: Seq3Occurrence): Boolean =
        message.visibility == Seq3Visibility.VISIBLE && occurrence.visibility == Seq3Visibility.VISIBLE &&
            message.fromLifelineId in visibleLifelineIds && message.toLifelineId?.let { it in visibleLifelineIds } != false
}
