package com.indagium.ui

import com.indagium.model.TileAxis
import com.indagium.model.TileLeaf
import com.indagium.model.TileSplit
import org.junit.After
import org.junit.Test
import java.io.File
import java.nio.file.Files
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * A diagram's layout survives closing and reopening it: the pure map ([DiagramLayoutMemory]), its token form, and the session
 * rules (a layout the user set sticks to its item, a diagram without one starts from the last layout the user set anywhere).
 */
class DiagramLayoutMemoryTest {
    private val root = Files.createTempDirectory("diagram-layout-memory-").toFile()
    private val states = mutableListOf<AppState>()

    @After
    fun tearDown() {
        states.forEach { it.close() }
        root.deleteRecursively()
    }

    private fun newState(restore: Boolean = false): AppState =
        AppState(
            autosaveFile = File(root, "state.cache"),
            restoreOnCreate = restore,
            notesDir = File(root, "notes"),
            diagramLibraryStore = DiagramLibraryStore(File(root, "library.cache")),
        ).also { states += it }

    private fun closeState(state: AppState) {
        states.remove(state)
        state.close()
    }

    private fun layoutOf(preset: DiagramWorkspacePreset, locked: Boolean = false): DiagramSavedLayout =
        Seq3ViewState().also {
            applyDiagramWorkspacePreset(it, preset)
            it.diagramTilesLocked = locked
        }.savedLayout()

    private fun itemIdOf(state: AppState, sessionId: String): String =
        requireNotNull(state.seq3Sessions.sessions.single { it.id == sessionId }.libraryItemId)

    private fun Seq3ViewState.setByUser(state: AppState, preset: DiagramWorkspacePreset) {
        applyDiagramWorkspacePreset(this, preset)
        state.diagramLayoutEdited(this)
    }

    // -- the pure map --------------------------------------------------------------------------------

    @Test
    fun mapKeepsAtMostTheBoundAndDropsTheLeastRecentlyRemembered() {
        val memory = DiagramLayoutMemory(maxItems = 3)
        val a = layoutOf(DiagramWorkspacePreset.STANDARD)
        val b = layoutOf(DiagramWorkspacePreset.CANVAS_FOCUS)
        listOf("one", "two", "three").forEach { memory.remember(it, a) }
        memory.remember("one", b) // remembered again: now the most recent
        memory.remember("four", b)

        assertEquals(listOf("three", "one", "four"), memory.entries().map { it.first })
        assertNull(memory.layoutFor("two"))
        assertEquals(b, memory.layoutFor("one"))
    }

    @Test
    fun defaultBoundIsTheLibrarysItemLimit() {
        val memory = DiagramLayoutMemory()
        val layout = layoutOf(DiagramWorkspacePreset.CANVAS_FOCUS)
        repeat(MAX_DIAGRAM_LIBRARY_ITEMS + 10) { memory.remember("item-$it", layout) }
        assertEquals(MAX_DIAGRAM_LIBRARY_ITEMS, memory.entries().size)
        assertNull(memory.layoutFor("item-0"))
        assertNotNull(memory.layoutFor("item-${MAX_DIAGRAM_LIBRARY_ITEMS + 9}"))
    }

    @Test
    fun forgetAndRetainWhereRemoveEntries() {
        val memory = DiagramLayoutMemory()
        val layout = layoutOf(DiagramWorkspacePreset.CANVAS_FOCUS)
        listOf("a", "b", "c").forEach { memory.remember(it, layout) }
        memory.forget("b")
        assertEquals(listOf("a", "c"), memory.entries().map { it.first })
        memory.retainWhere { it == "c" }
        assertEquals(listOf("c"), memory.entries().map { it.first })
    }

    // -- the token ----------------------------------------------------------------------------------

    @Test
    fun rememberedLayoutsAndTheLastSetLayoutRoundTripThroughTheToken() {
        val state = newState()
        val closed = state.seq3Sessions.beginStandalone()!!
        val closedItem = itemIdOf(state, closed)
        state.seq3Sessions.viewState(closed)!!.setByUser(state, DiagramWorkspacePreset.EDITOR_AND_DIAGRAM)
        state.seq3Sessions.close(closed)
        val last = layoutOf(DiagramWorkspacePreset.CANVAS_FOCUS, locked = true)
        state.seq3Sessions.layoutMemory.setLast(last)
        val remembered = state.seq3Sessions.layoutMemory.layoutFor(closedItem)
        assertNotNull(remembered)
        val token = state.seq3ViewToken()

        val fresh = newState()
        // The library file is shared, so the closed item still exists for the restore to keep.
        fresh.restoreSeq3ViewToken(token)

        assertEquals(remembered, fresh.seq3Sessions.layoutMemory.layoutFor(closedItem))
        assertEquals(last, fresh.seq3Sessions.layoutMemory.lastSet())
    }

    @Test
    fun tokenEntriesOfDeletedItemsAreDroppedOnRestore() {
        val state = newState()
        val id = state.seq3Sessions.beginStandalone()!!
        val item = itemIdOf(state, id)
        state.seq3Sessions.viewState(id)!!.setByUser(state, DiagramWorkspacePreset.EDITOR_AND_DIAGRAM)
        state.seq3Sessions.close(id)
        val token = state.seq3ViewToken()
        // Deleting through the store directly leaves the memory entry behind, as a hand-edited library would.
        DiagramLibraryStore(File(root, "library.cache")).delete(item)

        val fresh = newState()
        fresh.restoreSeq3ViewToken(token)

        assertNull(fresh.seq3Sessions.layoutMemory.layoutFor(item))
    }

    @Test
    fun anOldTokenWithoutRememberedEntriesOrALastSetEntryStillRestores() {
        val state = newState()
        val id = state.seq3Sessions.beginStandalone()!!
        val item = itemIdOf(state, id)
        val view = state.seq3Sessions.viewState(id)!!
        // The shape an older build wrote: just the open diagram's entry, fields 0-14, no blank-id entry after it.
        val oldToken = tokenFields(
            item,
            "false", "true", "true", "true", "true", "true", "220", "200", "392",
            tileLayoutJson(DiagramWorkspacePreset.CANVAS_FOCUS.layout) { it.key }.toString(),
            "messages,source", "", "true", "CANVAS_FOCUS",
        ).b64()

        state.restoreSeq3ViewToken(oldToken)

        assertEquals(DiagramWorkspacePreset.CANVAS_FOCUS.layout, view.diagramTileLayout)
        assertEquals(listOf(DiagramWorkspaceSectionId.MESSAGES, DiagramWorkspaceSectionId.SOURCE), view.diagramHiddenTiles)
        assertTrue(view.diagramTilesLocked)
        assertNull(state.seq3Sessions.layoutMemory.lastSet(), "an old token carries no last-set layout")
    }

    @Test
    fun theTokenIsBoundedByTheLibraryLimit() {
        val state = newState()
        val layout = layoutOf(DiagramWorkspacePreset.CANVAS_FOCUS)
        // More entries than the bound cannot be remembered, and the token never carries more than the bound.
        repeat(MAX_DIAGRAM_LIBRARY_ITEMS + 5) { state.seq3Sessions.layoutMemory.remember("item-$it", layout) }
        val entries = state.seq3ViewToken().split(",").filter { it.isNotBlank() }
        assertEquals(MAX_DIAGRAM_LIBRARY_ITEMS, entries.size)
    }

    // -- sessions --------------------------------------------------------------------------------------

    @Test
    fun aLayoutChangedBeforeClosingTheTabIsThereWhenTheItemIsReopened() {
        val state = newState()
        val first = state.seq3Sessions.beginStandalone()!!
        val item = itemIdOf(state, first)
        val view = state.seq3Sessions.viewState(first)!!
        view.setByUser(state, DiagramWorkspacePreset.EDITOR_AND_DIAGRAM)
        view.diagramTilesLocked = true
        view.maximizedDiagramTile = DiagramWorkspaceSectionId.PREVIEW
        val expected = view.savedLayout()
        state.seq3Sessions.close(first)

        val reopened = requireNotNull(state.seq3Sessions.openLibraryItemWithoutLog(item))
        val reopenedView = state.seq3Sessions.viewState(reopened)!!

        assertEquals(expected, reopenedView.savedLayout())
        assertTrue(reopenedView.diagramTilesLocked)
        assertTrue(diagramWorkspacePresetActive(reopenedView, DiagramWorkspacePreset.EDITOR_AND_DIAGRAM))
        assertFalse(reopenedView.messagesSectionOpen, "the older section flags follow the restored tiles")
        assertNull(reopenedView.maximizedDiagramTile, "maximize stays transient")
    }

    @Test
    fun aLayoutRestoredByTheAutosaveSurvivesARestartAndAReopen() {
        val state = newState()
        val first = state.seq3Sessions.beginStandalone()!!
        val item = itemIdOf(state, first)
        state.seq3Sessions.viewState(first)!!.setByUser(state, DiagramWorkspacePreset.EDITOR_AND_DIAGRAM)
        val expected = state.seq3Sessions.viewState(first)!!.savedLayout()
        state.seq3Sessions.close(first)
        state.autosaveNow()
        closeState(state)

        val restored = newState(restore = true)
        assertTrue(restored.seq3Sessions.sessions.isEmpty(), "fixture precondition: no diagram tab was open at the restart")
        val reopened = requireNotNull(restored.seq3Sessions.openLibraryItemWithoutLog(item))

        assertEquals(expected, restored.seq3Sessions.viewState(reopened)!!.savedLayout())
    }

    @Test
    fun anOpenDiagramAndAClosedOneBothComeBackAfterARestart() {
        val state = newState()
        val closed = state.seq3Sessions.beginStandalone()!!
        val closedItem = itemIdOf(state, closed)
        state.seq3Sessions.viewState(closed)!!.setByUser(state, DiagramWorkspacePreset.EDITOR_AND_DIAGRAM)
        val closedLayout = state.seq3Sessions.viewState(closed)!!.savedLayout()
        state.seq3Sessions.close(closed)
        val open = state.seq3Sessions.beginStandalone()!!
        val openItem = itemIdOf(state, open)
        state.seq3Sessions.viewState(open)!!.setByUser(state, DiagramWorkspacePreset.CANVAS_FOCUS)
        val openLayout = state.seq3Sessions.viewState(open)!!.savedLayout()
        state.autosaveNow()
        closeState(state)

        val restored = newState(restore = true)
        val restoredOpen = restored.seq3Sessions.sessions.single { it.libraryItemId == openItem }
        assertEquals(openLayout, restored.seq3Sessions.viewState(restoredOpen.id)!!.savedLayout())
        val reopened = requireNotNull(restored.seq3Sessions.openLibraryItemWithoutLog(closedItem))
        assertEquals(closedLayout, restored.seq3Sessions.viewState(reopened)!!.savedLayout())
    }

    @Test
    fun aNewDiagramStartsFromTheLastLayoutTheUserSetOnAnyDiagram() {
        val state = newState()
        val first = state.seq3Sessions.beginStandalone()!!
        state.seq3Sessions.viewState(first)!!.setByUser(state, DiagramWorkspacePreset.EDITOR_AND_DIAGRAM)
        val expected = state.seq3Sessions.viewState(first)!!.savedLayout()

        val second = state.seq3Sessions.beginStandalone()!!

        assertEquals(expected, state.seq3Sessions.viewState(second)!!.savedLayout())
        assertTrue(diagramWorkspacePresetActive(state.seq3Sessions.viewState(second)!!, DiagramWorkspacePreset.EDITOR_AND_DIAGRAM))
    }

    @Test
    fun theMostRecentlySetLayoutWinsForNewDiagrams() {
        val state = newState()
        val first = state.seq3Sessions.beginStandalone()!!
        state.seq3Sessions.viewState(first)!!.setByUser(state, DiagramWorkspacePreset.EDITOR_AND_DIAGRAM)
        val second = state.seq3Sessions.beginStandalone()!!
        state.seq3Sessions.viewState(second)!!.setByUser(state, DiagramWorkspacePreset.CANVAS_FOCUS)

        val third = state.seq3Sessions.beginStandalone()!!

        assertTrue(diagramWorkspacePresetActive(state.seq3Sessions.viewState(third)!!, DiagramWorkspacePreset.CANVAS_FOCUS))
    }

    @Test
    fun withNoLayoutEverSetANewDiagramGetsTheBuiltInDefault() {
        val state = newState()
        val id = state.seq3Sessions.beginStandalone()!!

        val view = state.seq3Sessions.viewState(id)!!

        assertEquals(Seq3ViewState().savedLayout(), view.savedLayout())
        assertEquals(diagramWorkspaceDefaultLayout(), view.diagramTileLayout)
        assertEquals(listOf(DiagramWorkspaceSectionId.SOURCE), view.diagramHiddenTiles)
    }

    @Test
    fun aLayoutAnItemWasLeftWithBeatsTheLastSetOne() {
        val state = newState()
        val first = state.seq3Sessions.beginStandalone()!!
        val item = itemIdOf(state, first)
        state.seq3Sessions.viewState(first)!!.setByUser(state, DiagramWorkspacePreset.EDITOR_AND_DIAGRAM)
        state.seq3Sessions.close(first)
        val second = state.seq3Sessions.beginStandalone()!!
        state.seq3Sessions.viewState(second)!!.setByUser(state, DiagramWorkspacePreset.CANVAS_FOCUS)

        val reopened = requireNotNull(state.seq3Sessions.openLibraryItemWithoutLog(item))

        assertTrue(diagramWorkspacePresetActive(state.seq3Sessions.viewState(reopened)!!, DiagramWorkspacePreset.EDITOR_AND_DIAGRAM))
    }

    @Test
    fun aDiagramTheUserNeverArrangedKeepsFollowingTheLastSetLayout() {
        val state = newState()
        val untouched = state.seq3Sessions.beginStandalone()!!
        val item = itemIdOf(state, untouched)
        state.seq3Sessions.viewState(untouched)!! // opened, not arranged
        state.seq3Sessions.close(untouched)
        assertNull(state.seq3Sessions.layoutMemory.layoutFor(item), "an unarranged layout is not remembered as the item's own")
        val other = state.seq3Sessions.beginStandalone()!!
        state.seq3Sessions.viewState(other)!!.setByUser(state, DiagramWorkspacePreset.EDITOR_AND_DIAGRAM)

        val reopened = requireNotNull(state.seq3Sessions.openLibraryItemWithoutLog(item))

        assertTrue(diagramWorkspacePresetActive(state.seq3Sessions.viewState(reopened)!!, DiagramWorkspacePreset.EDITOR_AND_DIAGRAM))
    }

    @Test
    fun deletingALibraryItemDropsItsRememberedLayout() {
        val state = newState()
        val id = state.seq3Sessions.beginStandalone()!!
        val item = itemIdOf(state, id)
        state.seq3Sessions.viewState(id)!!.setByUser(state, DiagramWorkspacePreset.EDITOR_AND_DIAGRAM)
        state.seq3Sessions.close(id)
        assertNotNull(state.seq3Sessions.layoutMemory.layoutFor(item))

        assertTrue(state.seq3Sessions.deleteLibraryItem(item))

        assertNull(state.seq3Sessions.layoutMemory.layoutFor(item))
        assertFalse(state.seq3ViewToken().split(",").any { it.isNotBlank() && it.unb64().tokenFields().firstOrNull() == item })
    }

    @Test
    fun deletingAnOpenItemAlsoDropsTheLayoutItsClosedTabWouldHaveLeft() {
        val state = newState()
        val id = state.seq3Sessions.beginStandalone()!!
        val item = itemIdOf(state, id)
        state.seq3Sessions.viewState(id)!!.setByUser(state, DiagramWorkspacePreset.EDITOR_AND_DIAGRAM)

        assertTrue(state.seq3Sessions.deleteLibraryItem(item))

        assertNull(state.seq3Sessions.layoutMemory.layoutFor(item), "closing the open tab must not leave an entry for a deleted item")
    }

    // -- the Editor + diagram preset ----------------------------------------------------------------

    @Test
    fun editorAndDiagramShowsOnlySourceAndPreviewSideBySideAndKeepsTheOtherTilesInPlace() {
        val preset = DiagramWorkspacePreset.EDITOR_AND_DIAGRAM
        val projected = assertNotNull(projectTileLayout(preset.layout) { it !in preset.hidden }).root
        val row = projected as TileSplit<DiagramWorkspaceSectionId>
        assertEquals(TileAxis.ROW, row.axis)
        assertEquals(
            listOf(DiagramWorkspaceSectionId.SOURCE, DiagramWorkspaceSectionId.PREVIEW),
            row.children.map { assertIs<TileLeaf<DiagramWorkspaceSectionId>>(it).section },
        )
        assertTrue(row.children[1].weight >= row.children[0].weight, "the preview is at least as wide as the editor")
        assertEquals(
            setOf(DiagramWorkspaceSectionId.MESSAGES, DiagramWorkspaceSectionId.LIFELINES, DiagramWorkspaceSectionId.ARTIFACTS),
            preset.hidden,
        )
        assertEquals(DiagramWorkspaceSectionId.entries.toSet(), tileLeafSections(preset.layout).toSet(), "hidden tiles keep their nodes")
        assertEquals(preset.layout, sanitizeDiagramWorkspaceLayoutState(preset.layout, preset.hidden.toList(), emptyList()).layout)
    }

    @Test
    fun editorAndDiagramIsActiveOnlyWhileItsTreeHiddenAndCollapsedSetsMatch() {
        val view = Seq3ViewState()
        assertFalse(diagramWorkspacePresetActive(view, DiagramWorkspacePreset.EDITOR_AND_DIAGRAM))
        applyDiagramWorkspacePreset(view, DiagramWorkspacePreset.EDITOR_AND_DIAGRAM)
        assertTrue(diagramWorkspacePresetActive(view, DiagramWorkspacePreset.EDITOR_AND_DIAGRAM))
        assertFalse(diagramWorkspacePresetActive(view, DiagramWorkspacePreset.STANDARD))
        assertFalse(diagramWorkspacePresetActive(view, DiagramWorkspacePreset.CANVAS_FOCUS))
        assertEquals(DiagramWorkspacePreset.EDITOR_AND_DIAGRAM, view.diagramLastPreset)
        assertEquals(setOf(DiagramWorkspaceSectionId.SOURCE, DiagramWorkspaceSectionId.PREVIEW), diagramWorkspaceVisibleTiles(view))

        setDiagramWorkspaceTileVisible(view, DiagramWorkspaceSectionId.MESSAGES, true)
        assertFalse(diagramWorkspacePresetActive(view, DiagramWorkspacePreset.EDITOR_AND_DIAGRAM))
        setDiagramWorkspaceTileVisible(view, DiagramWorkspaceSectionId.MESSAGES, false)
        assertTrue(diagramWorkspacePresetActive(view, DiagramWorkspacePreset.EDITOR_AND_DIAGRAM))
    }

    @Test
    fun editOpensNoSecondArrangementOverEditorAndDiagram() {
        // Edit moves Source beside Preview only in the default arrangement; this one already has it there.
        assertNull(diagramWorkspaceSourceBesidePreview(DiagramWorkspacePreset.EDITOR_AND_DIAGRAM.layout, 1600f))
        assertNotNull(diagramWorkspaceSourceBesidePreview(diagramWorkspaceDefaultLayout(), 1600f))
    }

    @Test
    fun aNarrowWindowStacksPreviewThenSourceForEditorAndDiagram() {
        val preset = DiagramWorkspacePreset.EDITOR_AND_DIAGRAM
        val narrow = diagramWorkspaceNarrowLayout(preset.layout)
        val visibleInOrder = tileLeafSections(narrow).filter { it !in preset.hidden }
        assertEquals(listOf(DiagramWorkspaceSectionId.PREVIEW, DiagramWorkspaceSectionId.SOURCE), visibleInOrder)
    }
}
