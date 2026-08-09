package com.indagium

import com.indagium.diagram.DiagramOptions
import com.indagium.diagram.DiagramParticipant
import com.indagium.diagram.DiagramRange
import com.indagium.diagram.DiagramTheme
import com.indagium.diagram.ParticipantKind
import com.indagium.diagram.SeqDiagramSpec
import com.indagium.diagram.buildSequenceDiagram
import com.indagium.diagram.encodeDiagramNote
import com.indagium.diagram.parseDiagramNote
import com.indagium.diagram.renderSequenceDiagram
import com.indagium.diagram.toSource
import com.indagium.model.LogEntry
import com.indagium.model.LogLevel
import com.indagium.ui.mkTab
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * End-to-end over the whole diagram feature, in the exact order the app performs it:
 *
 *     log rows -> buildSequenceDiagram -> toSource -> encodeDiagramNote (the AnnBlock.Note text)
 *              -> parseDiagramNote (reopening the note) -> renderSequenceDiagram -> ArrowHits
 *
 * The unit suites each cover one link; this covers the joins between them, which is where the
 * feature would actually break — most importantly that a log line's id survives all the way to a
 * clickable hit box, since that round trip crosses a text format that cannot express it.
 */
class DiagramNotePipelineTest {
    private val entries = listOf(
        LogEntry(1001, "10:00:00.000", LogLevel.I, "BluetoothAdapter", "enable() called"),
        LogEntry(1002, "10:00:00.120", LogLevel.I, "BluetoothManagerService", "handleEnable"),
        LogEntry(1003, "10:00:00.300", LogLevel.E, "BluetoothManagerService", "bind failed"),
        LogEntry(1004, "10:00:00.480", LogLevel.I, "BluetoothAdapter", "STATE_OFF"),
    )

    private val spec = SeqDiagramSpec(
        title = "Bluetooth enable",
        participants = listOf(
            DiagramParticipant("User", "User", ParticipantKind.ACTOR, isEntryPoint = true),
            DiagramParticipant("BluetoothAdapter", "BluetoothAdapter", ParticipantKind.TAG, tag = "BluetoothAdapter"),
            DiagramParticipant("BluetoothManagerService", "BluetoothManagerService", ParticipantKind.TAG, tag = "BluetoothManagerService"),
        ),
        range = DiagramRange.Ids(1001, 1004),
        sourceFile = "bugreport.txt",
        options = DiagramOptions(collapseRepeats = false, showElapsed = false, seqGroupFrames = false),
    )

    @Test
    fun aDiagramSurvivesTheRoundTripThroughANoteAndStaysClickable() {
        val tab = mkTab("t1", "bugreport.txt", entries)

        val built = buildSequenceDiagram(tab, spec)
        assertTrue(built.messages.isNotEmpty(), "the fixture must produce arrows to make this test meaningful")

        // What the app writes into the AnnBlock.Note.
        val noteText = encodeDiagramNote(spec, built.toSource(spec.dialect), built)

        // What the app reads back when the note is reopened.
        val parsed = assertNotNull(parseDiagramNote(noteText), "the note we just wrote must parse as a diagram note")
        val model = assertNotNull(parsed.model, "the header must carry the model so a reopened note can be drawn")

        assertEquals(built.messages, model.messages)
        assertEquals(built.participants, model.participants)
        assertEquals("bugreport.txt", parsed.spec.sourceFile, "the note stays bound to the log it came from")

        // And what the Notes panel draws + hit-tests.
        val rendered = renderSequenceDiagram(model, DiagramTheme.LIGHT)
        assertEquals(model.messages.size, rendered.hits.size)

        val hitEntryIds = rendered.hits.map { it.entryId }.toSet()
        assertTrue(
            hitEntryIds.all { it in entries.map { e -> e.id } },
            "every clickable arrow must point at a real log line id; got $hitEntryIds",
        )
    }

    @Test
    fun theFencedSourceIsWhatSurvivesForRenderersThatOnlySpeakText() {
        val tab = mkTab("t1", "bugreport.txt", entries)
        val built = buildSequenceDiagram(tab, spec)

        val noteText = encodeDiagramNote(spec, built.toSource(spec.dialect), built)

        // The spec/model header is an HTML comment on line 1; everything a Markdown renderer
        // (GitHub, GitLab, a Jira Mermaid macro) actually acts on is the fence below it.
        assertTrue(noteText.startsWith("<!-- indagium:diagram v3 "), "the guarded header must lead, so stripping it is a prefix cut")
        assertTrue(noteText.contains("```mermaid\n"), "the fence must carry the dialect for renderers that key off it")
        assertTrue(noteText.contains("sequenceDiagram"), "the fence body must be real Mermaid")
        assertTrue(noteText.trimEnd().endsWith("```"), "the fence must be closed")
    }

    @Test
    fun aNoteReopenedWithoutItsLogStillRendersFromItsOwnCarriedModel() {
        // The Case Library's "open notes only" path, and any note whose log has since moved: there
        // is no tab to rebuild from, so the note must be self-sufficient. This is the case that
        // decided the model gets carried in the header at all.
        val built = buildSequenceDiagram(mkTab("t1", "bugreport.txt", entries), spec)
        val noteText = encodeDiagramNote(spec, built.toSource(spec.dialect), built)

        val emptyTab = mkTab("t2", "unrelated.log", emptyList())
        val parsed = assertNotNull(parseDiagramNote(noteText))
        val model = assertNotNull(parsed.model)

        assertTrue(emptyTab.logData.isEmpty(), "guard: this tab genuinely has no log behind it")
        val rendered = renderSequenceDiagram(model, DiagramTheme.DARK)
        assertTrue(rendered.widthPx > 0 && rendered.heightPx > 0)
        assertEquals(built.messages.size, rendered.hits.size, "still fully drawn and still clickable")
    }

    @Test
    fun anOrdinaryTextNoteIsNeverMistakenForADiagram() {
        // Every AnnBlock.Note in every existing .ann reaches parseDiagramNote. A false positive
        // would replace someone's written analysis with a broken picture.
        val proseWithAFence = "Here's what I found:\n\n```mermaid\nsequenceDiagram\n  A->>B: hi\n```\n"

        assertEquals(null, parseDiagramNote(proseWithAFence), "a fence alone is not a diagram note — the spec header is what marks one")
        assertEquals(null, parseDiagramNote("Just a normal note."))
        assertEquals(null, parseDiagramNote(""))
    }
}
