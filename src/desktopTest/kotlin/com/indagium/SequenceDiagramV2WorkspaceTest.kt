package com.indagium

import com.indagium.diagram.DiagramRange
import com.indagium.model.LogEntry
import com.indagium.model.LogLevel
import com.indagium.ui.ActiveSurface
import com.indagium.ui.AppState
import com.indagium.ui.DiagramWorkspaceVariant
import com.indagium.ui.mkTab
import java.io.File
import kotlin.io.path.createTempDirectory
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull

class SequenceDiagramV2WorkspaceTest {
    @Test
    fun v2UsesContextSelectionRangeAndIndependentWorkspaceLifecycle() {
        val root = createTempDirectory("indagium-sequence-v2").toFile()
        val tab = mkTab(
            "log",
            "sample.log",
            (1..4).map { id ->
                LogEntry(id, "10:00:00.00$id", LogLevel.I, "tag$id", "message $id")
            },
        )
        val state = AppState(File(root, "state.cache"), notesDir = File(root, "notes")).also {
            it.tabs = listOf(tab)
            it.activateTab(tab.id)
        }

        state.seqDiagrams.beginV2("log", setOf(2, 4))

        val v2 = assertNotNull(state.seqDiagrams.activeSession)
        assertEquals(DiagramWorkspaceVariant.V2, v2.variant)
        assertEquals("Sequence diagram v2", v2.spec.title)
        assertEquals(DiagramRange.Ids(2, 4, setOf(2, 4)), v2.spec.range)
        assertIs<ActiveSurface.Diagram>(state.activeSurface)
        assertEquals(1, state.tabs.size)

        state.seqDiagrams.begin("log", setOf(1))

        assertEquals(2, state.seqDiagrams.workspaces.size)
        assertEquals(DiagramWorkspaceVariant.V1, state.seqDiagrams.activeSession?.variant)
        assertEquals(1, state.seqDiagrams.workspaces.count { it.variant == DiagramWorkspaceVariant.V2 })
    }
}
