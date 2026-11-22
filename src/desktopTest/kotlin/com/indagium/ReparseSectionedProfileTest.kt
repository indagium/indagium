package com.indagium

import com.indagium.model.AnnBlock
import com.indagium.model.TabContentState
import com.indagium.ui.AppState
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import java.io.File
import kotlin.io.path.createTempDirectory
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/** A plain-text tab reparsed as a sectioned profile gets the section switcher and cites the section a note comes from. */
class ReparseSectionedProfileTest {
    private val root = "Tethered Bluetooth"
    private val hci = "$root / HCI"
    private val l2cap = "$root / L2CAP"

    private fun packet(identity: String, title: String, offset: Long) =
        """{"metadata":{"identity":"$identity","source":"Demo","system_time":${BASE_TIME + offset}},"Summary":{"Note":"$title"}}"""

    private fun document() =
        """{"$root":{"HCI":[${packet("HCI.A", "hci one", HCI_ONE)},${packet("HCI.B", "hci two", HCI_TWO)}],""" +
            """"L2CAP":[${packet("L2.A", "l2cap one", L2CAP_ONE)},${packet("L2.B", "l2cap two", L2CAP_TWO)}]}}"""

    private suspend fun waitUntil(message: String, predicate: () -> Boolean) {
        withTimeout(TIMEOUT_MS) { while (!predicate()) delay(POLL_MS) }
        assertTrue(predicate(), message)
    }

    @Test
    fun aReparsedSectionedTabKeepsItsSectionsAndCitesTheSectionANoteComesFrom() = runBlocking {
        val directory = createTempDirectory("reparse-sectioned").toFile()
        val app = AppState(autosaveFile = File(directory, "state.cache"), autoExportNotes = false, notesDir = File(directory, "notes"))
        try {
            val file = File(directory, "capture.json").apply { writeText(document()) }
            val plainId = assertNotNull(app.openFileWithLogFormatProfile(file, "plain-text"))
            waitUntil("the plain-text tab loads") { app.tab(plainId)?.contentState == TabContentState.LOADED }
            assertTrue(app.tab(plainId)!!.logSections.isEmpty(), "plain text has no sections")

            val reparsedId = assertNotNull(app.reparseTabWithLogFormatProfileAwait(plainId, "metadata-json"))
            val reparsed = assertNotNull(app.tab(reparsedId))
            assertEquals(listOf(hci, l2cap), reparsed.logSections.map { it.id })
            assertEquals(hci, reparsed.selectedLogSectionId)

            app.selectLogSection(reparsedId, l2cap)
            waitUntil("the second section loads") {
                val tab = app.tab(reparsedId)
                tab?.contentState == TabContentState.LOADED && tab.selectedLogSectionId == l2cap && reparsedId !in app.pendingLogSection
            }
            val shown = app.tab(reparsedId)!!
            assertEquals(listOf("l2cap one", "l2cap two"), shown.logData.map { it.msg })

            app.requestAddAnn(reparsedId, listOf(shown.logData.first().id))
            val request = assertNotNull(app.addAnnRequest)
            app.confirmAddAnn(request, "from l2cap", request.capture?.presentation?.options ?: AnnBlock.LogRefPresentationOptions())
            val ref = app.tab(reparsedId)!!.annotations.blocks.filterIsInstance<AnnBlock.LogRef>().single()
            assertEquals(l2cap, ref.sourceSectionId)
        } finally {
            app.close()
            directory.deleteRecursively()
        }
    }

    private companion object {
        // Interleaved times, so a section's global row ids are not its own 1..n.
        const val BASE_TIME = 1_790_000_000_000_000_000L
        const val HCI_ONE = 10L
        const val L2CAP_ONE = 20L
        const val HCI_TWO = 50L
        const val L2CAP_TWO = 60L
        const val TIMEOUT_MS = 20_000L
        const val POLL_MS = 25L
    }
}
