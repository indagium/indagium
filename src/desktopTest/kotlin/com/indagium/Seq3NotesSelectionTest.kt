package com.indagium

import com.indagium.diagram3.Seq3Document
import com.indagium.diagram3.Seq3NoteSeed
import com.indagium.diagram3.encodeSeq3Note
import com.indagium.model.AnnBlock
import com.indagium.model.Annotations
import com.indagium.model.LogEntry
import com.indagium.model.LogLevel
import com.indagium.ui.mkTab
import com.indagium.ui.seq3NotesSelection
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** Covers `ui/Seq3NotesSelection.kt`'s pure walk over `LogTab.annotations` — plain Kotlin over a
 *  synthetic tab, no `AppState`/Compose needed. Mirrors `Seq3NoteSeedsTest`'s fixture style. */
class Seq3NotesSelectionTest {
    private fun entry(id: Int) = LogEntry(id, "10:00:00.%03d".format(id), LogLevel.I, "Tag", "line $id")

    private fun tabWith(blocks: List<AnnBlock>, entryCount: Int = 5) =
        mkTab("t", "sample.log", (1..entryCount).map(::entry)).copy(annotations = Annotations(blocks = blocks))

    @Test
    fun captionOnlyBlockUsesTheCaptionAsTheSeedText() {
        val tab = tabWith(listOf(AnnBlock.LogRef(id = "b1", logIds = listOf(1, 2), caption = "caption text")))

        val selection = seq3NotesSelection(tab)

        assertEquals(setOf(1, 2), selection.entryIds)
        assertEquals(1, selection.usedBlockCount)
        assertEquals(0, selection.skippedCrossFileCount)
        assertEquals(listOf(Seq3NoteSeed(id = "note-ann-b1", text = "caption text", anchorEntryId = 1)), selection.seeds)
    }

    @Test
    fun precedingTextBlockOnlyUsesTheNotesTextAsTheSeedText() {
        val tab = tabWith(
            listOf(
                AnnBlock.Note(id = "n1", text = "preceding prose"),
                AnnBlock.LogRef(id = "b1", logIds = listOf(3), caption = ""),
            ),
        )

        val selection = seq3NotesSelection(tab)

        assertEquals(listOf(Seq3NoteSeed(id = "note-ann-b1", text = "preceding prose", anchorEntryId = 3)), selection.seeds)
    }

    @Test
    fun bothAPrecedingNoteAndACaptionJoinWithABlankLine() {
        val tab = tabWith(
            listOf(
                AnnBlock.Note(id = "n1", text = "preceding prose"),
                AnnBlock.LogRef(id = "b1", logIds = listOf(3), caption = "caption text"),
            ),
        )

        val selection = seq3NotesSelection(tab)

        assertEquals(
            listOf(Seq3NoteSeed(id = "note-ann-b1", text = "preceding prose\n\ncaption text", anchorEntryId = 3)),
            selection.seeds,
        )
    }

    @Test
    fun aPrecedingNoteThatIsItselfADiagramNoteIsNotConsumed() {
        // A real diagram-note header/fence, exactly what `Seq3Session.confirm` would write — the
        // predicate under test (`parseSeq3Note(text) == null`) must reject it as prose, the same
        // way `seq3DiagramNotes` already tells diagram notes apart from plain ones.
        val diagramNoteText = encodeSeq3Note(Seq3Document())
        val tab = tabWith(
            listOf(
                AnnBlock.Note(id = "n1", text = diagramNoteText),
                AnnBlock.LogRef(id = "b1", logIds = listOf(3), caption = "caption only"),
            ),
        )

        val selection = seq3NotesSelection(tab)

        assertEquals(listOf(Seq3NoteSeed(id = "note-ann-b1", text = "caption only", anchorEntryId = 3)), selection.seeds)
    }

    @Test
    fun crossFileLogRefIsSkippedAndContributesNoIds() {
        val tab = tabWith(
            listOf(AnnBlock.LogRef(id = "b1", logIds = listOf(1), caption = "from another tab", sourceTabId = "other-tab")),
        )

        val selection = seq3NotesSelection(tab)

        assertEquals(1, selection.skippedCrossFileCount)
        assertTrue(selection.entryIds.isEmpty())
        assertEquals(0, selection.usedBlockCount)
        assertTrue(selection.seeds.isEmpty())
    }

    @Test
    fun idsNotPresentInRmapAreFilteredOutButSurvivingOnesStillCount() {
        val tab = tabWith(listOf(AnnBlock.LogRef(id = "b1", logIds = listOf(1, 999), caption = "caption")))

        val selection = seq3NotesSelection(tab)

        assertEquals(setOf(1), selection.entryIds, "999 has no matching log line and must be dropped")
        assertEquals(1, selection.usedBlockCount)
        assertEquals(listOf(Seq3NoteSeed(id = "note-ann-b1", text = "caption", anchorEntryId = 1)), selection.seeds)
    }

    @Test
    fun aBlockLeftWithNoSurvivingIdsContributesNothingAtAll() {
        val tab = tabWith(listOf(AnnBlock.LogRef(id = "b1", logIds = listOf(999), caption = "caption")))

        val selection = seq3NotesSelection(tab)

        assertTrue(selection.entryIds.isEmpty())
        assertEquals(0, selection.usedBlockCount)
        assertTrue(selection.seeds.isEmpty())
    }

    @Test
    fun blankCombinedTextStillPutsTheLinesInRangeButEmitsNoSeed() {
        val tab = tabWith(listOf(AnnBlock.LogRef(id = "b1", logIds = listOf(1), caption = "  ")))

        val selection = seq3NotesSelection(tab)

        assertEquals(setOf(1), selection.entryIds)
        assertEquals(1, selection.usedBlockCount)
        assertTrue(selection.seeds.isEmpty())
    }
}
