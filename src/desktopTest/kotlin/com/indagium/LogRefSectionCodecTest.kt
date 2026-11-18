package com.indagium

import com.indagium.model.AnnBlock
import com.indagium.model.Annotations
import com.indagium.model.LogEntry
import com.indagium.model.LogLevel
import com.indagium.ui.AppState
import com.indagium.ui.annotationsFromToken
import com.indagium.ui.annotationsToken
import com.indagium.ui.mkTab
import java.io.File
import kotlin.io.path.createTempDirectory
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

/**
 * [AnnBlock.LogRef.sourceSectionId] is the last field of the "R" token that autosave and the .ann sidecar share: it survives
 * both, and a token written before it existed (or a reference without a section) decodes with no section.
 */
class LogRefSectionCodecTest {
    private fun entry(id: Int, msg: String) = LogEntry(id, "10:00:00.%03d".format(id), LogLevel.I, "HCI", msg)

    private fun ref(id: String, section: String?, source: String? = "j") = AnnBlock.LogRef(
        id = id,
        logIds = listOf(1, 2),
        caption = "rows | with a bar",
        sourceTabId = source,
        sourceFilename = source?.let { "Untitled.json" },
        sourceEntries = listOf(entry(1, "one"), entry(2, "two")),
        sourceSectionId = section,
    )

    private fun notes(vararg blocks: AnnBlock) = Annotations(prefix = "p", suffix = "s", blocks = blocks.toList())

    @Test
    fun theSectionSurvivesTheAnnSidecarToken() {
        val original = notes(ref("r1", "Tethered Bluetooth / HCI"), ref("r2", null), ref("r3", "G | with / odd | chars", source = null))

        val decoded = assertNotNull(original.annotationsToken().annotationsFromToken())

        val refs = decoded.blocks.filterIsInstance<AnnBlock.LogRef>()
        assertEquals(listOf("Tethered Bluetooth / HCI", null, "G | with / odd | chars"), refs.map { it.sourceSectionId })
        assertEquals(original.blocks, decoded.blocks)
    }

    @Test
    fun aReferenceWithoutASectionEncodesExactlyAsBeforeAndDecodesWithNone() {
        val withSection = notes(ref("r1", "G / S")).annotationsToken()
        val without = notes(ref("r1", null)).annotationsToken()

        // The block tokens are base64 inside the outer token; a section adds a field, none adds nothing.
        assertEquals(false, withSection == without)
        assertNull(assertNotNull(without.annotationsFromToken()).blocks.filterIsInstance<AnnBlock.LogRef>().single().sourceSectionId)
        // A hand-padded empty ninth field (what a writer that always emitted the field would produce) reads as no section too.
        val padded = notes(ref("r1", "x")).annotationsToken().annotationsFromToken()!!
            .let { it.copy(blocks = it.blocks.map { b -> (b as AnnBlock.LogRef).copy(sourceSectionId = "") }) }
            .annotationsToken().annotationsFromToken()!!
        assertNull(padded.blocks.filterIsInstance<AnnBlock.LogRef>().single().sourceSectionId)
    }

    @Test
    fun theSectionSurvivesAnAutosaveRoundTrip() {
        val dir = createTempDirectory("logref-section-autosave").toFile()
        val app = AppState(File(dir, "state.cache"), autoExportNotes = false)
        try {
            val source = File(dir, "j.log").apply { writeText("06-26 10:00:00.000  1  1 I HCI: one\n") }
            app.tabs = listOf(mkTab("j", "j.log", listOf(entry(1, "one"), entry(2, "two"))).copy(sourcePath = source.absolutePath))
            app.activeTabId = "j"
            app.upAnn("j") { it.copy(annotations = notes(ref("r1", "Tethered Bluetooth / HCI"), ref("r2", null, source = null))) }
            app.autosaveNow()

            val restored = AppState(File(dir, "state.cache"), restoreOnCreate = true, autoExportNotes = false)
            try {
                val refs = restored.tab("j")!!.annotations.blocks.filterIsInstance<AnnBlock.LogRef>()
                assertEquals(listOf("Tethered Bluetooth / HCI", null), refs.map { it.sourceSectionId })
                assertEquals("j", refs.first().sourceTabId)
            } finally {
                restored.close()
            }
        } finally {
            app.close()
            dir.deleteRecursively()
        }
    }
}
