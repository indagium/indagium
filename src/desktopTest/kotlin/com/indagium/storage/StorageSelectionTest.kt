package com.indagium.storage

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class StorageSelectionTest {
    private val generation = SelectionGeneration(SourceId("source"), SourceVersion("lifetime", 1), "query", "projection", 2, 3)
    private val anchor = SelectionOccurrence(EntryId(7), 10, 1)
    private val endpoint = SelectionOccurrence(EntryId(9), 3, 0)

    @Test
    fun rangeRetainsClickedOccurrencesAndUsesInclusiveDisplayOrder() {
        val range = SelectionBase.DisplayRange(anchor, endpoint)
        val selection = StorageSelection(generation, range)
        assertEquals(anchor, selection.anchor)
        assertEquals(3L, range.firstPosition)
        assertEquals(10L, range.lastPosition)
        assertEquals(1L, range.anchor.occurrence)
        assertFailsWith<StorageFailure.InvalidRequest> { StorageSelection(generation, range, endpoint) }
        assertFailsWith<StorageFailure.InvalidRequest> { SelectionOccurrence(EntryId(1), -1, 0) }
        assertFailsWith<StorageFailure.InvalidRequest> { SelectionOccurrence(EntryId(1), 0, -1) }
    }

    @Test
    fun generationsMustMatchWithoutChangingSelection() {
        val selection = StorageSelection(generation, SelectionBase.AllDisplayedRows, anchor)
        assertEquals(SelectionGenerationStatus.CURRENT, selection.generationStatus(generation))
        val changes = listOf(
            generation.copy(sourceId = SourceId("other")),
            generation.copy(sourceVersion = SourceVersion("lifetime", 2)),
            generation.copy(queryId = "other"), generation.copy(projectionId = "other"),
            generation.copy(foldGeneration = 4), generation.copy(paneGeneration = 4),
        )
        changes.forEach { assertEquals(SelectionGenerationStatus.MISMATCH, selection.generationStatus(it)) }
        assertEquals(anchor, selection.anchor)
        assertEquals(SelectionBase.AllDisplayedRows, selection.base)
    }

    @Test
    fun overridesAreIdLevelOrderedAndDefensivelyCopied() {
        val included = linkedSetOf(EntryId(7), EntryId(2))
        val excluded = linkedSetOf(EntryId(9))
        val selection = StorageSelection(generation, SelectionBase.AllDisplayedItems, inclusions = included, exclusions = excluded)
        included.clear()
        excluded.clear()
        assertEquals(listOf(EntryId(7), EntryId(2)), selection.inclusions.toList())
        assertEquals(SelectionOverride.INCLUDED, selection.overrideFor(anchor.id))
        assertEquals(SelectionOverride.EXCLUDED, selection.overrideFor(endpoint.id))
        assertEquals(SelectionOverride.NONE, selection.overrideFor(EntryId(10)))
        assertFailsWith<StorageFailure.InvalidRequest> {
            StorageSelection(generation, SelectionBase.Explicit, inclusions = setOf(EntryId(7)), exclusions = setOf(EntryId(7)))
        }
    }

    @Test
    fun selectAllStoresNoEnumeratedIds() {
        for (base in listOf(SelectionBase.AllDisplayedRows, SelectionBase.AllDisplayedItems)) {
            val selection = StorageSelection(generation, base)
            assertTrue(selection.inclusions.isEmpty())
            assertTrue(selection.exclusions.isEmpty())
        }
    }
}
