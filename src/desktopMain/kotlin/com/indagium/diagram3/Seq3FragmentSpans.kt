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

/** Indices into [rows] (the drawn rows, in order) of the rows [fragment] currently holds. A summary
 *  row (a collapsed `×N` message, a first/last elision) has no entry id, so it is held when the
 *  fragment names its message in either way. */
internal fun seq3FragmentMemberIndices(rows: List<Seq3RowRef>, fragment: Seq3Fragment): List<Int> =
    rows.indices.filter { index ->
        val row = rows[index]
        if (row.occurrenceEntryId != null) {
            fragmentContainsRow(fragment, row)
        } else {
            row.messageId in fragment.messageIds || fragment.occurrenceRefs.any { it.messageId == row.messageId }
        }
    }

/** How many drawn rows [fragment]'s box covers: from its first to its last member, as the layout
 *  draws it. Zero for a fragment none of whose members is drawn. */
internal fun seq3FragmentDrawnRowCount(document: Seq3Document, fragment: Seq3Fragment): Int {
    val members = seq3FragmentMemberIndices(seq3DrawnRowRefs(document), fragment)
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
    val members = document.fragments.associate { fragment ->
        fragment.id to if (fragment.visibility == Seq3Visibility.VISIBLE) seq3FragmentMemberIndices(rows, fragment) else emptyList()
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
    private val drawnRows = rows.toHashSet()
    private val drawnByMessage = rows.groupBy { it.messageId }
    private val messagePositions = document.messages.withIndex().associate { (index, message) -> message.id to index }

    fun rematerialize(fragment: Seq3Fragment, span: IntRange): Seq3Fragment {
        val inside = rows.slice(span)
        val insideByMessage = inside.groupBy { it.messageId }
        val messageIds = insideByMessage.keys.toMutableSet()
        val refs = ArrayList<Seq3OccurrenceRef>()
        for ((messageId, insideRows) in insideByMessage) {
            if (insideRows.any { it.occurrenceEntryId == null }) continue // a summary row can only be held by message id
            val undrawnRefs = fragment.occurrenceRefs.filter { it.messageId == messageId && Seq3RowRef(it.messageId, it.entryId) !in drawnRows }
            val partial = insideRows.size < drawnByMessage.getValue(messageId).size || undrawnRefs.isNotEmpty()
            if (!partial) continue
            insideRows.mapNotNullTo(refs) { row -> row.occurrenceEntryId?.let { Seq3OccurrenceRef(messageId, it) } }
            refs += undrawnRefs
        }
        // What the fragment holds without it being drawn (a hidden message or occurrence) stays held.
        fragment.messageIds.filterTo(messageIds) { it !in drawnByMessage }
        fragment.occurrenceRefs.filterTo(refs) { it.messageId !in drawnByMessage && it !in refs }
        return fragment.copy(
            messageIds = messageIds.sortedBy { messagePositions[it] ?: Int.MAX_VALUE },
            occurrenceRefs = refs.distinct(),
        )
    }
}
