package com.indagium.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import com.indagium.model.LogTab

/**
 * The Diagram library as a body of its own (the DIAGRAMS tile), for when AnnotationPanel is shown with `showDiagramLibrary =
 * false`. Same rows, create menu and delete confirmation as the section at the top of the Notes panel, wired to the same
 * [AppState.seq3Sessions] calls the Notes call sites make; it is always open, so its header has no chevron.
 */
@Composable
internal fun DiagramLibraryTileBody(state: AppState, tab: LogTab, modifier: Modifier = Modifier) {
    val notesLocked = LocalNotesEditLocked.current
    val items = state.seq3Sessions.libraryForTab(tab)
    var pendingDeleteId by remember(tab.id) { mutableStateOf<String?>(null) }
    Column(modifier.fillMaxWidth()) {
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
