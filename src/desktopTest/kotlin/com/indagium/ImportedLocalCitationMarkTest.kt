package com.indagium

import com.indagium.model.LogTab
import com.indagium.ui.AppState
import com.indagium.ui.createSharedNote
import com.indagium.ui.mkTab
import com.indagium.ui.shareTabNote
import com.indagium.ui.stopSharing
import java.io.File
import kotlin.io.path.createTempDirectory
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * `LogTab.importedLocalCitationBlockIds` is runtime-only (only a path-less notes-only tab has it, and such a tab is not restored): every
 * note replacement resets it.
 */
class ImportedLocalCitationMarkTest {
    private fun tab(id: String = "tok"): LogTab = mkTab(id, "$id.log", emptyList()).copy(sourcePath = File("$id.log").absolutePath)

    private fun appWith(dir: File): AppState = AppState(File(dir, "state.cache"), autoExportNotes = false, notesDir = File(dir, "notes")).also { s ->
        s.tabs = listOf(
            tab("a").copy(importedLocalCitationBlockIds = setOf("x")),
            tab("b"),
        )
    }

    @Test
    fun aNewAnalysisSharingTheNoteAndStoppingTheSharingAllResetTheMarkWithTheNoteThatItBelongedTo() {
        val dir = createTempDirectory("imported-local-reset").toFile()
        val app = appWith(dir)
        try {
            app.newAnalysis("a")
            assertTrue(app.tab("a")!!.importedLocalCitationBlockIds.isEmpty(), "a fresh analysis has none of the old note's blocks")

            app.upTab("a") { it.copy(importedLocalCitationBlockIds = setOf("x")) }
            assertNotNull(app.shareTabNote("a", "S", listOf("b")))
            assertTrue(app.tab("a")!!.importedLocalCitationBlockIds.isEmpty(), "the note moved to the shared host")

            val host = assertNotNull(app.sharedNoteHosts.singleOrNull()).id
            app.upTab("a") { it.copy(importedLocalCitationBlockIds = setOf("y")) }
            assertTrue(app.stopSharing(host, "a", replaceExisting = true))
            assertTrue(app.tab("a")!!.importedLocalCitationBlockIds.isEmpty(), "the recipient's note is the host's now")
        } finally {
            app.close()
            dir.deleteRecursively()
        }
    }

    @Test
    fun aSharedNoteCreatedOverTabsLeavesTheirMarksAlone() {
        val dir = createTempDirectory("imported-local-create").toFile()
        val app = appWith(dir)
        try {
            assertNotNull(app.createSharedNote("S", listOf("a", "b")))
            assertEquals(setOf("x"), app.tab("a")!!.importedLocalCitationBlockIds, "the tab's own note stays its own")
        } finally {
            app.close()
            dir.deleteRecursively()
        }
    }
}
