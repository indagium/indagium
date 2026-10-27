package com.indagium.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.rememberScrollState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.sp
import com.indagium.model.LogTab

/**
 * The Diagram library as a body of its own (the DIAGRAMS tile). Same rows and delete confirmation as the former Notes section, wired to the same
 * [AppState.seq3Sessions] calls the Notes call sites make; it is always open. The tile's own header carries the title and
 * [DiagramLibraryHeaderAction] the "+ diagram" create menu, so the list draws no header of its own.
 */
@Composable
internal fun DiagramLibraryTileBody(state: AppState, tab: LogTab, modifier: Modifier = Modifier) {
    val notesLocked = LocalNotesEditLocked.current
    val items = state.seq3Sessions.libraryForTab(tab)
    var pendingDeleteId by remember(tab.id) { mutableStateOf<String?>(null) }
    val listScroll = rememberScrollState()
    FlexRegion(
        scrollState = listScroll,
        modifier = modifier.fillMaxWidth().testTag("diagram-library-section"),
        capDp = DIAGRAM_LIBRARY_PANEL_MAX_HEIGHT_DP + DIAGRAM_LIBRARY_PANEL_VERTICAL_PADDING_DP,
    ) {
        DiagramLibrarySection(
            items = items,
            expanded = true,
            onToggle = {},
            onCreate = { state.seq3Sessions.begin(tab.id, tab.selected) },
            onCreateFromNotes = { state.seq3Sessions.beginFromNotes(tab.id) },
            editingEnabled = !notesLocked,
            selectedLineCount = tab.selected.size,
            // Keyed on the two inputs it reads, like the Notes panel's call sites (see seq3NotesSelection's doc).
            notesDiagramSummary = remember(tab.annotations, tab.logData) { seq3NotesSelection(tab) },
            onOpen = { id -> state.seq3Sessions.openLibraryItem(id, tab.id) },
            onRequestDelete = { pendingDeleteId = it },
            collapsible = false,
            showHeader = false,
            listScrollState = listScroll,
        )
    }
    pendingDeleteId?.let { id ->
        val item = items.firstOrNull { it.id == id }
        if (item == null) {
            pendingDeleteId = null
        } else {
            DiagramLibraryDeleteDialog(
                title = item.title,
                onConfirm = {
                    // Deletion is intentionally confined to this explicit confirmation; a row's Delete button only opens it.
                    if (notesMutationAllowed(notesLocked)) state.seq3Sessions.deleteLibraryItem(id)
                    pendingDeleteId = null
                },
                onDismiss = { pendingDeleteId = null },
            )
        }
    }
}

private const val DIAGRAM_LIBRARY_PANEL_VERTICAL_PADDING_DP = 8

/** The Diagram library tile's header action: the "+ diagram" button and its create menu (From selection / From notes). */
@Composable
internal fun RowScope.DiagramLibraryHeaderAction(state: AppState, tab: LogTab) {
    val notesLocked = LocalNotesEditLocked.current
    var createMenuOpen by remember(tab.id) { mutableStateOf(false) }
    val summary = remember(tab.annotations, tab.logData) { seq3NotesSelection(tab) }
    Box {
        LabelIconButton("+ diagram", fontSize = 10.sp, enabled = !notesLocked, onClick = { createMenuOpen = true })
        if (createMenuOpen) {
            CreateDiagramPopup(
                selectedLineCount = tab.selected.size,
                notesDiagramSummary = summary,
                onCreateFromSelection = {
                    createMenuOpen = false
                    if (notesMutationAllowed(notesLocked)) state.seq3Sessions.begin(tab.id, tab.selected)
                },
                onCreateFromNotes = {
                    createMenuOpen = false
                    if (notesMutationAllowed(notesLocked)) state.seq3Sessions.beginFromNotes(tab.id)
                },
                onDismiss = { createMenuOpen = false },
                tc = tc(),
            )
        }
    }
}
