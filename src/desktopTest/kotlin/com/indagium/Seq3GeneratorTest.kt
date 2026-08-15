package com.indagium

import com.indagium.diagram3.Seq3AddResult
import com.indagium.diagram3.Seq3Document
import com.indagium.diagram3.Seq3GenerateOptions
import com.indagium.diagram3.Seq3Lifeline
import com.indagium.diagram3.Seq3Range
import com.indagium.diagram3.Seq3Repeat
import com.indagium.diagram3.Seq3State
import com.indagium.diagram3.addSeq3MessageFromSelection
import com.indagium.diagram3.generateSeq3
import com.indagium.model.LogEntry
import com.indagium.model.LogLevel
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class Seq3GeneratorTest {
    private fun entry(id: Int, ts: String, tag: String, msg: String, level: LogLevel = LogLevel.I, pid: Int = 0, tid: Int = 0) =
        LogEntry(id, ts, level, tag, msg, pid, tid)

    @Test
    fun rankingKeepsOnlyTheTopSignalTagsAsLifelinesAndCapsTheCount() {
        // 10 distinct tags: "Loud" carries every high-signal trait (errors + varied message shapes
        // + many occurrences); the other nine are single, unremarkable, low-volume rows — exactly
        // the shape the cap (DEFAULT_SEQ3_MAX_LIFELINES = 8) exists to trim down to a readable set.
        val loud = (1..5).map {
            entry(it, "10:00:00.%03d".format(it), "Loud", "failure kind $it", level = LogLevel.E)
        }
        val quiet = ('A'..'I').mapIndexed { index, tagSuffix ->
            entry(100 + index, "10:00:01.000", "Quiet$tagSuffix", "nothing much happens")
        }
        val doc = generateSeq3(loud + quiet, Seq3Range.VisibleView)

        assertTrue(doc.lifelines.size <= 8, "the lifeline count must stay capped: ${doc.lifelines.size}")
        assertEquals("Loud", doc.lifelines.first().name, "the highest-signal tag must rank first")
        assertTrue(doc.lifelines.none { it.name == "QuietI" }, "the least-signal tag must be dropped by the cap")
    }

    @Test
    fun sameThreadHandoffInfersTheAdjacentTagAsTarget() {
        val entries = (0 until 3).flatMap { i ->
            val base = i * 2 + 1
            listOf(
                entry(base, "10:00:00.%03d".format(i * 100), "Producer", "start op", pid = 7, tid = 11),
                entry(base + 1, "10:00:00.%03d".format(i * 100 + 50), "Consumer", "handle op", pid = 7, tid = 11),
            )
        }
        val doc = generateSeq3(entries, Seq3Range.VisibleView)

        val producerId = doc.lifelines.single { it.name == "Producer" }.id
        val consumerId = doc.lifelines.single { it.name == "Consumer" }.id
        val message = doc.messages.single { it.fromLifelineId == producerId }

        assertEquals(consumerId, message.toLifelineId)
        assertEquals(Seq3State.AUTO, message.state)
        assertEquals(3, message.occurrences.size)
    }

    @Test
    fun sharedCorrelationTokenInfersTheAdjacentTagAsTargetWithoutThreadIdentity() {
        val token = "abcdefghij"
        val entries = (0 until 3).flatMap { i ->
            val base = i * 2 + 1
            listOf(
                entry(base, "10:00:00.%03d".format(i * 100), "Client", "requestId=$token start"),
                entry(base + 1, "10:00:00.%03d".format(i * 100 + 50), "Server", "requestId=$token finish"),
            )
        }
        val doc = generateSeq3(entries, Seq3Range.VisibleView)

        val clientId = doc.lifelines.single { it.name == "Client" }.id
        val serverId = doc.lifelines.single { it.name == "Server" }.id
        val message = doc.messages.single { it.fromLifelineId == clientId }

        assertEquals(serverId, message.toLifelineId)
    }

    @Test
    fun anUninferableTargetIsNullAndFabricatesNoLifeline() {
        val entries = listOf(
            entry(1, "10:00:00.000", "Lonely", "hello"),
            entry(2, "10:00:05.000", "Other", "unrelated, far in time"),
        )
        val doc = generateSeq3(entries, Seq3Range.VisibleView)

        val message = doc.messages.single { it.fromLifelineId == doc.lifelines.single { l -> l.name == "Lonely" }.id }
        assertEquals(null, message.toLifelineId)
        assertEquals(Seq3State.NEEDS_TARGET, message.state)
        assertEquals(2, doc.lifelines.size, "no phantom lifeline may be created for the unresolved target")
    }

    @Test
    fun idsRangeIsOrderIndependentAndHonorsAnExactSelection() {
        val entries = (1..5).map { entry(it, "10:00:00.%03d".format(it), "A", "line $it") }

        val swapped = generateSeq3(entries, Seq3Range.Ids(from = 4, to = 2))
        assertEquals(setOf(2, 3, 4), swapped.messages.flatMap { it.occurrences.map { o -> o.entryId } }.toSet())

        val exact = generateSeq3(entries, Seq3Range.Ids(from = 0, to = 0, selectedIds = setOf(1, 5)))
        assertEquals(setOf(1, 5), exact.messages.flatMap { it.occurrences.map { o -> o.entryId } }.toSet())
    }

    @Test
    fun timeRangeCarriesForwardAnUnparseableTimestampFromThePreviousRow() {
        val entries = listOf(
            entry(1, "10:00:00.000", "A", "in range start"),
            entry(2, "", "A", "brief-format row, inherits row 1's timestamp"),
            entry(3, "10:00:10.000", "A", "well past the window"),
        )
        val doc = generateSeq3(entries, Seq3Range.Time("09:59:59.000", "10:00:01.000"))

        assertEquals(setOf(1, 2), doc.messages.flatMap { it.occurrences.map { o -> o.entryId } }.toSet())
    }

    @Test
    fun aMalformedTimeRangeBoundDegradesToAnEmptyDocumentInsteadOfThrowing() {
        val entries = listOf(entry(1, "10:00:00.000", "A", "line"))
        val doc = generateSeq3(entries, Seq3Range.Time("not-a-time", "10:00:01.000"))

        assertTrue(doc.messages.isEmpty())
        assertTrue(doc.lifelines.isEmpty())
    }

    @Test
    fun freshlyGeneratedMessagesDefaultToEveryOccurrenceNotCollapsed() {
        // "As they are, not grouped" — a freshly generated diagram must draw every occurrence as
        // its own arrow by default; collapsing behind a ×n badge is an opt-in Inspector choice.
        val entries = (1..5).map { entry(it, "10:00:00.%03d".format(it), "A", "repeated line") }
        val doc = generateSeq3(entries, Seq3Range.VisibleView)
        assertTrue(doc.messages.isNotEmpty())
        assertTrue(doc.messages.all { it.repeat == Seq3Repeat.EVERY })
    }

    @Test
    fun cancellationCheckAbortsGeneration() {
        val entries = (1..50).map { entry(it, "10:00:00.%03d".format(it % 1000), "A", "line $it") }

        assertFailsWith<IllegalStateException> {
            generateSeq3(
                entries,
                Seq3Range.VisibleView,
                Seq3GenerateOptions(),
                cancellationCheck = { throw IllegalStateException("cancelled") },
            )
        }
    }

    // ── addSeq3MessageFromSelection (queue panel's "＋ Add") ────────────────────────────────────

    @Test
    fun emptySelectionIsRejected() {
        val doc = generateSeq3(listOf(entry(1, "10:00:00.000", "A", "line")), Seq3Range.VisibleView)
        val result = addSeq3MessageFromSelection(doc, emptyList())
        assertTrue(result is Seq3AddResult.Rejected)
        assertEquals("Select at least one log row", result.reason)
    }

    @Test
    fun mixedTagSelectionIsRejectedWithTheExactReason() {
        val doc = emptyDocument()
        val selection = listOf(entry(1, "10:00:00.000", "A", "line one"), entry(2, "10:00:00.100", "B", "line two"))
        val result = addSeq3MessageFromSelection(doc, selection)
        assertTrue(result is Seq3AddResult.Rejected)
        assertEquals("Select rows from a single tag", result.reason)
    }

    @Test
    fun singleTagSelectionAddsOneNeedsTargetMessageAndCreatesALifeline() {
        val doc = emptyDocument()
        val selection = listOf(entry(10, "10:00:00.000", "NewTag", "hello world"))
        val result = addSeq3MessageFromSelection(doc, selection)
        val added = result as? Seq3AddResult.Added ?: error("expected Added, got $result")

        val lifeline = added.document.lifelines.single { it.name == "NewTag" }
        val message = added.document.messages.single { it.id == added.newMessageId }
        assertEquals(lifeline.id, message.fromLifelineId)
        assertEquals(null, message.toLifelineId, "a row selection has no adjacent-entry evidence to infer a target from")
        assertEquals(Seq3State.NEEDS_TARGET, message.state)
        assertEquals(setOf(10), message.occurrences.map { it.entryId }.toSet())
    }

    @Test
    fun selectingRowsForATagThatAlreadyHasALifelineReusesItInsteadOfDuplicating() {
        val existing = emptyDocument().copy(
            lifelines = listOf(Seq3Lifeline("A", "A", setOf("A"), 0)),
        )
        val selection = listOf(entry(1, "10:00:00.000", "A", "line one"))
        val result = addSeq3MessageFromSelection(existing, selection) as? Seq3AddResult.Added ?: error("expected Added")

        assertEquals(1, result.document.lifelines.size, "must reuse the existing lifeline, never duplicate it")
        assertEquals("A", result.document.messages.single().fromLifelineId)
    }

    @Test
    fun newMessageIdNeverCollidesWithAnExistingOne() {
        val entries = (1..3).map { entry(it, "10:00:00.%03d".format(it), "A", "line $it") }
        val generated = generateSeq3(entries, Seq3Range.VisibleView) // produces "msg-1"
        val selection = listOf(entry(10, "10:00:01.000", "B", "extra"))
        val result = addSeq3MessageFromSelection(generated, selection) as? Seq3AddResult.Added ?: error("expected Added")

        assertTrue(result.newMessageId !in generated.messages.map { it.id })
        assertTrue(result.document.messages.any { it.id == result.newMessageId })
    }

    private fun emptyDocument() = Seq3Document()
}
