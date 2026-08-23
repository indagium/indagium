package com.indagium

import com.indagium.diagram3.Seq3Document
import com.indagium.diagram3.Seq3Match
import com.indagium.diagram3.Seq3Message
import com.indagium.diagram3.Seq3Note
import com.indagium.diagram3.Seq3NoteSeed
import com.indagium.diagram3.Seq3Occurrence
import com.indagium.diagram3.Seq3Visibility
import com.indagium.diagram3.applySeq3NoteSeeds
import kotlin.test.Test
import kotlin.test.assertEquals

/** Covers `diagram3/Seq3NoteSeeds.kt`'s pure anchoring/idempotency contract — see that file's own
 *  header. Builds [Seq3Document]s directly rather than through `generateSeq3` (as most other
 *  `Seq3*Test` files do) since these cases need exact control over which entry ids land in which
 *  message's occurrences, including entry ids that belong to NO message at all. */
class Seq3NoteSeedsTest {
    private fun occurrence(entryId: Int) = Seq3Occurrence(
        entryId = entryId,
        timestampMillis = entryId.toLong(),
        rawTimestamp = "10:00:00.000",
        pid = 0,
        tid = 0,
        level = 'I',
        text = "line $entryId",
    )

    private fun message(id: String, vararg entryIds: Int) = Seq3Message(
        id = id,
        match = Seq3Match(tag = "Tag", template = "hello"),
        fromLifelineId = "l1",
        toLifelineId = "l2",
        labelTemplate = "hello",
        occurrences = entryIds.map(::occurrence),
    )

    private fun document(messages: List<Seq3Message>) = Seq3Document(messages = messages)

    @Test
    fun seedAnchorsToTheMessageWhoseOccurrenceCarriesTheAnchorEntryId() {
        val doc = document(listOf(message("msg-1", 1, 2), message("msg-2", 5, 6)))

        val result = applySeq3NoteSeeds(doc, listOf(Seq3NoteSeed(id = "note-ann-a", text = "prose", anchorEntryId = 5)))

        assertEquals(listOf("msg-2"), result.notes.single().messageIds)
    }

    @Test
    fun entryIdAbsentFromEveryMessageFallsBackToTheNextMessageForward() {
        val doc = document(listOf(message("msg-1", 1, 2), message("msg-2", 10, 11)))

        // 5 sits strictly between msg-1's occurrences (max 2) and msg-2's (min 10) — no message
        // carries it, so the next message FORWARD in log order (msg-2) must catch the note.
        val result = applySeq3NoteSeeds(doc, listOf(Seq3NoteSeed(id = "note-ann-a", text = "prose", anchorEntryId = 5)))

        assertEquals(listOf("msg-2"), result.notes.single().messageIds)
    }

    @Test
    fun entryIdPastEveryMessageFallsBackToTheLastMessage() {
        val doc = document(listOf(message("msg-1", 1, 2), message("msg-2", 10, 11)))

        val result = applySeq3NoteSeeds(doc, listOf(Seq3NoteSeed(id = "note-ann-a", text = "prose", anchorEntryId = 999)))

        assertEquals(listOf("msg-2"), result.notes.single().messageIds)
    }

    @Test
    fun reapplyingOverAUserEditedNoteKeepsTheEditAndOnlyRepointsMessageIds() {
        val doc = document(listOf(message("msg-1", 1), message("msg-2", 2))).copy(
            notes = listOf(
                Seq3Note(
                    id = "note-ann-a",
                    text = "the user's own rewritten prose",
                    messageIds = listOf("msg-1"),
                    x = 10.0,
                    y = 20.0,
                    width = 5.0,
                    height = 6.0,
                    visibility = Seq3Visibility.HIDDEN,
                ),
            ),
        )

        // A regenerate re-derives the seed's own text fresh from the notes document every time —
        // simulate that by handing back a DIFFERENT text than what's already on the canvas note.
        val result = applySeq3NoteSeeds(doc, listOf(Seq3NoteSeed(id = "note-ann-a", text = "freshly regenerated seed text", anchorEntryId = 2)))

        val note = result.notes.single()
        assertEquals("the user's own rewritten prose", note.text, "a user's canvas edit must survive a regenerate")
        assertEquals(listOf("msg-2"), note.messageIds, "the anchor must still re-point to the new msg-N id")
        assertEquals(10.0, note.x)
        assertEquals(20.0, note.y)
        assertEquals(5.0, note.width)
        assertEquals(6.0, note.height)
        assertEquals(Seq3Visibility.HIDDEN, note.visibility, "a hidden note must not silently reappear")
    }

    @Test
    fun aDocumentWithNoMessagesAtAllDropsEverySeed() {
        val doc = Seq3Document()

        val result = applySeq3NoteSeeds(doc, listOf(Seq3NoteSeed(id = "note-ann-a", text = "prose", anchorEntryId = 1)))

        assertEquals(emptyList(), result.notes)
    }

    @Test
    fun seedsBeyondMaxSeq3NotesAreDropped() {
        val messages = (1..410).map { message("msg-$it", it) }
        val doc = document(messages)
        val seeds = (1..410).map { Seq3NoteSeed(id = "note-ann-$it", text = "prose $it", anchorEntryId = it) }

        val result = applySeq3NoteSeeds(doc, seeds)

        assertEquals(400, result.notes.size, "must never emit more notes than the codec's own MAX_SEQ3_NOTES bound")
    }

    @Test
    fun noSeedsIsANoOp() {
        val doc = document(listOf(message("msg-1", 1)))

        val result = applySeq3NoteSeeds(doc, emptyList())

        assertEquals(doc, result)
    }
}
