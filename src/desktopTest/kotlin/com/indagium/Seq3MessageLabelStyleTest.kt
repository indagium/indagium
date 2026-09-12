package com.indagium

import com.indagium.diagram3.Seq3ArrowRow
import com.indagium.diagram3.Seq3Capture
import com.indagium.diagram3.Seq3CaptureSource
import com.indagium.diagram3.Seq3Command
import com.indagium.diagram3.Seq3Document
import com.indagium.diagram3.Seq3FontRole
import com.indagium.diagram3.Seq3Kind
import com.indagium.diagram3.Seq3LayoutOptions
import com.indagium.diagram3.Seq3Lifeline
import com.indagium.diagram3.Seq3Match
import com.indagium.diagram3.Seq3Message
import com.indagium.diagram3.Seq3MessageLabelStyle
import com.indagium.diagram3.Seq3Occurrence
import com.indagium.diagram3.Seq3Repeat
import com.indagium.diagram3.Seq3TextMetrics
import com.indagium.diagram3.applySeq3Command
import com.indagium.diagram3.encodeSeq3Note
import com.indagium.diagram3.formatSeq3MessageLabel
import com.indagium.diagram3.layoutSeq3
import com.indagium.diagram3.parseSeq3Note
import com.indagium.diagram3.seq3MessageLabelTemplate
import com.indagium.diagram3.toMermaid
import com.indagium.diagram3.toPlantUml
import com.indagium.diagram3.undoSeq3Command
import com.indagium.ui.seq3MessageLabelStyleTooltip
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/** Focused A9 coverage: the conservative formatter is shared by every text/canvas path, and the
 *  selected style survives the note codec while old notes keep FREE_TEXT. */
class Seq3MessageLabelStyleTest {
    private object Metrics : Seq3TextMetrics {
        override fun width(role: Seq3FontRole, text: String): Double = text.length.toDouble()

        override fun lineHeight(role: Seq3FontRole): Double = 12.0
    }

    private val lifelines = listOf(
        Seq3Lifeline("A", "Alpha", setOf("A"), 0),
        Seq3Lifeline("B", "Beta", setOf("B"), 1),
    )

    private fun occurrence(id: Int, text: String, values: Map<String, String>) = Seq3Occurrence(
        entryId = id,
        timestampMillis = id.toLong(),
        rawTimestamp = "10:00:00.00$id",
        pid = 1,
        tid = 1,
        level = 'I',
        text = text,
        captureValues = values,
    )

    private fun message(
        text: String,
        captureName: String,
        occurrences: List<Seq3Occurrence>,
        kind: Seq3Kind = Seq3Kind.CALL,
        repeat: Seq3Repeat = Seq3Repeat.EVERY,
    ) = Seq3Message(
        id = "m1",
        match = Seq3Match("A", text, listOf(Seq3Capture(captureName, Seq3CaptureSource.NAMED_VALUE))),
        fromLifelineId = "A",
        toLifelineId = "B",
        labelTemplate = text,
        kind = kind,
        repeat = repeat,
        repeatThreshold = 1,
        occurrences = occurrences,
    )

    @Test
    fun formatsOnlyTheTwoConservativeSignatureShapes() {
        assertEquals(
            "onBind(uid={uid}, role={role})",
            formatSeq3MessageLabel("onBind uid={uid}, role={role}", Seq3MessageLabelStyle.UML_SIGNATURE),
        )
        assertEquals(
            "onScreenChanged(screen={screen})",
            formatSeq3MessageLabel("onScreenChanged: {screen}", Seq3MessageLabelStyle.UML_SIGNATURE),
        )
        assertEquals(
            "connect to {device} on port {port}",
            formatSeq3MessageLabel("connect to {device} on port {port}", Seq3MessageLabelStyle.UML_SIGNATURE),
        )
    }

    @Test
    fun leavesFreeTextAlreadySignaturesAndAssignmentLikeLabelsUntouched() {
        val already = "onBind(uid={uid}, role={role})"
        assertEquals(already, formatSeq3MessageLabel(already, Seq3MessageLabelStyle.UML_SIGNATURE))
        assertEquals("result = onBind uid={uid}", formatSeq3MessageLabel("result = onBind uid={uid}", Seq3MessageLabelStyle.UML_SIGNATURE))
        assertEquals("return value={value}", formatSeq3MessageLabel("return value={value}", Seq3MessageLabelStyle.UML_SIGNATURE))
        assertEquals("onBind uid={uid}", formatSeq3MessageLabel("onBind uid={uid}", Seq3MessageLabelStyle.FREE_TEXT))

        val literalBraces = Seq3Message(
            id = "literal",
            match = Seq3Match("A", "onBind uid={uid}"),
            fromLifelineId = "A",
            toLifelineId = "B",
            labelTemplate = "onBind uid={uid}",
        )
        assertEquals("onBind uid={uid}", seq3MessageLabelTemplate(literalBraces, Seq3MessageLabelStyle.UML_SIGNATURE))
    }

    @Test
    fun perOccurrenceSubstitutionUsesTheFormattedTemplateForCanvasAndBothDialects() {
        val occurrences = listOf(
            occurrence(1, "onScreenChanged: HOME", mapOf("screen" to "HOME")),
            occurrence(2, "onScreenChanged: MEDIA", mapOf("screen" to "MEDIA")),
        )
        val document = Seq3Document(
            lifelines = lifelines,
            messages = listOf(message("onScreenChanged: {screen}", "screen", occurrences)),
            messageLabelStyle = Seq3MessageLabelStyle.UML_SIGNATURE,
        )

        val layoutRows = layoutSeq3(document, Seq3LayoutOptions(Metrics)).rows.filterIsInstance<Seq3ArrowRow>()
        assertEquals(listOf("onScreenChanged(screen=HOME)", "onScreenChanged(screen=MEDIA)"), layoutRows.map { it.label })
        assertTrue(document.toMermaid().contains("onScreenChanged(screen=HOME)"))
        assertTrue(document.toMermaid().contains("onScreenChanged(screen=MEDIA)"))
        assertTrue(document.toPlantUml().contains("onScreenChanged(screen=HOME)"))
        assertTrue(document.toPlantUml().contains("onScreenChanged(screen=MEDIA)"))
    }

    @Test
    fun collapsedAndNoteAndUnresolvedRowsUseTheSameFormattedTemplate() {
        val occurrences = (1..4).map { id ->
            occurrence(id, "onBind uid=u$id, role=r$id", mapOf("uid" to "u$id", "role" to "r$id"))
        }
        val base = message("onBind uid={uid}, role={role}", "uid", occurrences, repeat = Seq3Repeat.COLLAPSE_ABOVE)
            .copy(match = Seq3Match("A", "onBind uid={uid}, role={role}", listOf(
                Seq3Capture("uid", Seq3CaptureSource.NAMED_VALUE),
                Seq3Capture("role", Seq3CaptureSource.NAMED_VALUE),
            )))
        val collapsed = Seq3Document(lifelines = lifelines, messages = listOf(base), messageLabelStyle = Seq3MessageLabelStyle.UML_SIGNATURE)
        val collapsedLayout = layoutSeq3(collapsed, Seq3LayoutOptions(Metrics)).rows.filterIsInstance<Seq3ArrowRow>().single()
        assertEquals("onBind(uid={uid}, role={role})", collapsedLayout.label)
        assertTrue(collapsed.toMermaid().contains("onBind(uid={uid}, role={role})"))

        val note = base.copy(kind = Seq3Kind.NOTE)
        val unresolved = base.copy(toLifelineId = null)
        val noteDocument = collapsed.copy(messages = listOf(note))
        val unresolvedDocument = collapsed.copy(messages = listOf(unresolved))
        assertTrue(noteDocument.toMermaid().contains("onBind(uid={uid}, role={role})"))
        assertTrue(unresolvedDocument.toPlantUml().contains("onBind(uid={uid}, role={role})"))
    }

    @Test
    fun messageLabelStyleRoundTripsAndOldNotesDefaultToFreeText() {
        val document = Seq3Document(
            lifelines = lifelines,
            messages = emptyList(),
            messageLabelStyle = Seq3MessageLabelStyle.UML_SIGNATURE,
        )
        val parsed = parseSeq3Note(encodeSeq3Note(document))
        assertNotNull(parsed)
        assertEquals(Seq3MessageLabelStyle.UML_SIGNATURE, parsed.document.messageLabelStyle)

        val legacy = encodeSeq3Note(document.copy(messageLabelStyle = Seq3MessageLabelStyle.FREE_TEXT))
            .replaceFirst(",\"messageLabelStyle\":\"FREE_TEXT\"", "")
        assertFalse(legacy.contains("\"messageLabelStyle\""), "fixture must omit the A9 field")
        val parsedLegacy = parseSeq3Note(legacy)
        assertNotNull(parsedLegacy)
        assertEquals(Seq3MessageLabelStyle.FREE_TEXT, parsedLegacy.document.messageLabelStyle)
    }

    @Test
    fun styleCommandIsUndoableAsOneDocumentEdit() {
        val before = Seq3Document()
        val result = applySeq3Command(before, Seq3Command.SetMessageLabelStyle(Seq3MessageLabelStyle.UML_SIGNATURE))
        assertTrue(result.applied)
        assertEquals(Seq3MessageLabelStyle.UML_SIGNATURE, result.document.messageLabelStyle)
        assertEquals(before, result.undo?.let(::undoSeq3Command))
    }

    @Test
    fun toolbarTooltipExplainsTheCurrentAndNextStyle() {
        assertEquals("Use UML signature labels", seq3MessageLabelStyleTooltip(Seq3MessageLabelStyle.FREE_TEXT))
        assertEquals("Use free-text labels", seq3MessageLabelStyleTooltip(Seq3MessageLabelStyle.UML_SIGNATURE))
        assertFalse(seq3MessageLabelStyleTooltip(Seq3MessageLabelStyle.FREE_TEXT).isBlank())
    }
}
