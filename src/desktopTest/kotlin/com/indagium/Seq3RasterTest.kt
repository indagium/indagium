package com.indagium

import com.indagium.diagram3.Seq3Delay
import com.indagium.diagram3.Seq3ArtifactStyle
import com.indagium.diagram3.Seq3AwtTextMetrics
import com.indagium.diagram3.Seq3Document
import com.indagium.diagram3.Seq3FontRole
import com.indagium.diagram3.Seq3Fragment
import com.indagium.diagram3.Seq3FragmentKind
import com.indagium.diagram3.Seq3Kind
import com.indagium.diagram3.Seq3LayoutOptions
import com.indagium.diagram3.Seq3Lifeline
import com.indagium.diagram3.Seq3ManualActivation
import com.indagium.diagram3.Seq3Match
import com.indagium.diagram3.Seq3Message
import com.indagium.diagram3.Seq3MessageNoteRow
import com.indagium.diagram3.Seq3Note
import com.indagium.diagram3.Seq3Occurrence
import com.indagium.diagram3.Seq3Operand
import com.indagium.diagram3.Seq3RasterTheme
import com.indagium.diagram3.Seq3TextMetrics
import com.indagium.diagram3.layoutSeq3
import com.indagium.diagram3.renderSeq3
import com.indagium.diagram3.toPngBytes
import java.io.ByteArrayInputStream
import javax.imageio.ImageIO
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import java.io.File

class Seq3RasterTest {
    private companion object {
        const val EDGE_SEARCH_RADIUS_PX = 2
        const val BORDER_CORNER_SKIP_PX = 8
        const val BORDER_BLEND_MIN_COVERAGE = 0.08
        const val BORDER_BLEND_MAX_COVERAGE = 1.05
        const val BORDER_BLEND_CHANNEL_TOLERANCE = 5.0
    }

    private class StubMetrics : Seq3TextMetrics {
        override fun width(role: Seq3FontRole, text: String): Double = text.length * 7.0

        override fun lineHeight(role: Seq3FontRole): Double = 16.0
    }

    @Suppress("MagicNumber") // fixture entry id / timestamp, not a tunable constant
    private fun fixedDocument(): Seq3Document {
        val a = Seq3Lifeline("A", "Alpha", setOf("A"), 0)
        val b = Seq3Lifeline("B", "Beta", setOf("B"), 1)
        val occurrence = Seq3Occurrence(1, 1_000L, "10:00:00.000", pid = 1, tid = 1, level = 'I', text = "hello")
        val message = Seq3Message(
            id = "m1",
            match = Seq3Match("A", "hello"),
            fromLifelineId = "A",
            toLifelineId = "B",
            labelTemplate = "hello",
            kind = Seq3Kind.CALL,
            occurrences = listOf(occurrence),
        )
        return Seq3Document(lifelines = listOf(a, b), messages = listOf(message))
    }

    private fun layout(doc: Seq3Document) = layoutSeq3(doc, Seq3LayoutOptions(StubMetrics()))

    @Test
    fun rendersDeterministicPngBytesForAFixedDocumentAndTheme() {
        val doc = fixedDocument()
        val first = renderSeq3(layout(doc), Seq3RasterTheme.DEFAULT_LIGHT, scale = 2f).toPngBytes()
        val second = renderSeq3(layout(doc), Seq3RasterTheme.DEFAULT_LIGHT, scale = 2f).toPngBytes()

        assertContentEquals(first, second, "the same document+theme+scale must rasterize to byte-identical PNGs")
    }

    @Test
    fun rendersWithoutADisplayAndProducesADecodablePng() {
        val rendered = renderSeq3(layout(fixedDocument()), Seq3RasterTheme.DEFAULT_LIGHT, scale = 1.5f)
        val bytes = rendered.toPngBytes()

        assertTrue(bytes.isNotEmpty())
        val decoded = ImageIO.read(ByteArrayInputStream(bytes))
        assertEquals(rendered.widthPx, decoded.width)
        assertEquals(rendered.heightPx, decoded.height)
    }

    @Test
    fun emptyLayoutRendersAPlaceholderInsteadOfThrowingOrBeingEmpty() {
        val emptyLayout = layoutSeq3(Seq3Document(), Seq3LayoutOptions(StubMetrics()))
        val rendered = renderSeq3(emptyLayout, Seq3RasterTheme.DEFAULT_LIGHT)

        assertTrue(rendered.widthPx > 0 && rendered.heightPx > 0, "a placeholder must still be a real, decodable image")
        assertTrue(rendered.toPngBytes().isNotEmpty())
    }

    @Test
    fun aDocumentWithADelayRastersWithoutThrowingAndGrowsTallerThanWithoutIt() {
        val withDelay = fixedDocument().copy(delays = listOf(Seq3Delay("d1", afterMessageId = "m1", label = "a while later")))
        val plainLayout = layout(fixedDocument())
        val delayLayout = layout(withDelay)

        assertTrue(delayLayout.height > plainLayout.height, "reserving the delay band must grow the diagram's own height")
        val rendered = renderSeq3(delayLayout, Seq3RasterTheme.DEFAULT_LIGHT)
        val bytes = rendered.toPngBytes()
        assertTrue(bytes.isNotEmpty())
        val decoded = ImageIO.read(ByteArrayInputStream(bytes))
        assertEquals(rendered.widthPx, decoded.width)
        assertEquals(rendered.heightPx, decoded.height, "the rastered image must actually be the taller size, not silently clipped back to the plain layout")
    }

    @Test
    fun aDocumentWithActivationBarsRastersWithoutThrowingAndKeepsTheSameHeight() {
        // The load-bearing invariant from Seq3Layout's own test carries through to the raster: a
        // bar is painted ON TOP of existing geometry, never reflowing it, so the rendered image's
        // own height must be identical on vs off — only the pixels inside that same canvas change.
        val base = fixedDocument()
        val withReturn = base.copy(
            messages = base.messages + Seq3Message(
                id = "m2",
                match = Seq3Match("B", "bye"),
                fromLifelineId = "B",
                toLifelineId = "A",
                labelTemplate = "bye",
                kind = Seq3Kind.RETURN,
                occurrences = listOf(Seq3Occurrence(2, 2_000L, "10:00:01.000", pid = 1, tid = 1, level = 'I', text = "bye")),
            ),
        )
        val plainLayout = layout(withReturn)
        val activatedLayout = layout(withReturn.copy(showActivations = true))
        assertTrue(activatedLayout.activations.isNotEmpty(), "this fixture must actually produce a bar, or the height comparison below would be vacuous")
        assertEquals(plainLayout.height, activatedLayout.height, "an activation bar must never grow the diagram's own height")

        val rendered = renderSeq3(activatedLayout, Seq3RasterTheme.DEFAULT_LIGHT)
        val bytes = rendered.toPngBytes()
        assertTrue(bytes.isNotEmpty())
        val decoded = ImageIO.read(ByteArrayInputStream(bytes))
        assertEquals(rendered.widthPx, decoded.width)
        assertEquals(rendered.heightPx, decoded.height)
    }

    @Test
    fun aDocumentWithManualActivationBarsRastersWithoutThrowingAndKeepsTheSameHeight() {
        // Manual bars are drawn ON TOP of the existing lifeline/rows exactly like auto bars
        // (aDocumentWithActivationBarsRastersWithoutThrowingAndKeepsTheSameHeight above), and are
        // drawn even with showActivations off — this fixture leaves it at its default false.
        val base = fixedDocument()
        val withManualBar = base.copy(
            manualActivations = listOf(Seq3ManualActivation("bar1", lifelineId = "B", startMessageId = "m1")),
        )
        val plainLayout = layout(base)
        val manualLayout = layout(withManualBar)
        assertTrue(manualLayout.activations.isNotEmpty(), "this fixture must actually produce a bar, or the height comparison below would be vacuous")
        assertEquals(plainLayout.height, manualLayout.height, "a manual activation bar must never grow the diagram's own height")

        val rendered = renderSeq3(manualLayout, Seq3RasterTheme.DEFAULT_LIGHT)
        val bytes = rendered.toPngBytes()
        assertTrue(bytes.isNotEmpty())
        val decoded = ImageIO.read(ByteArrayInputStream(bytes))
        assertEquals(rendered.widthPx, decoded.width)
        assertEquals(rendered.heightPx, decoded.height)
    }

    @Test
    fun aDocumentWithAFragmentDividerRastersWithoutThrowingAndKeepsTheSameHeight() {
        // Mirrors aDocumentWithActivationBarsRastersWithoutThrowingAndKeepsTheSameHeight exactly:
        // a divider is painted INSIDE a fragment box's own already-computed geometry (WP6's own
        // zero-pitch guard, Seq3LayoutTest.kt), so the rendered image's own height must be
        // identical with vs without the operand — only the pixels inside that same canvas change.
        val base = fixedDocument()
        val withSecondBranch = base.copy(
            messages = base.messages + Seq3Message(
                id = "m2",
                match = Seq3Match("B", "bye"),
                fromLifelineId = "B",
                toLifelineId = "A",
                labelTemplate = "bye",
                kind = Seq3Kind.RETURN,
                occurrences = listOf(Seq3Occurrence(2, 2_000L, "10:00:01.000", pid = 1, tid = 1, level = 'I', text = "bye")),
            ),
            fragments = listOf(Seq3Fragment("f1", Seq3FragmentKind.ALT, "cond0", listOf("m1", "m2"))),
        )
        val withoutOperand = withSecondBranch
        val withOperand = withSecondBranch.copy(
            fragments = listOf(
                withSecondBranch.fragments.single()
                    .copy(elseOperands = listOf(Seq3Operand("op1", "cond1", startsAtMessageId = "m2"))),
            ),
        )
        val plainLayout = layout(withoutOperand)
        val dividedLayout = layout(withOperand)
        assertTrue(
            dividedLayout.fragments.single().dividers.isNotEmpty(),
            "this fixture must actually produce a divider, or the height comparison below would be vacuous",
        )
        assertEquals(plainLayout.height, dividedLayout.height, "a fragment divider must never grow the diagram's own height")

        val rendered = renderSeq3(dividedLayout, Seq3RasterTheme.DEFAULT_LIGHT)
        val bytes = rendered.toPngBytes()
        assertTrue(bytes.isNotEmpty())
        val decoded = ImageIO.read(ByteArrayInputStream(bytes))
        assertEquals(rendered.widthPx, decoded.width)
        assertEquals(rendered.heightPx, decoded.height)
    }

    @Test
    fun differentThemesProduceDifferentBytesForTheSameDocument() {
        val doc = fixedDocument()
        val light = renderSeq3(layout(doc), Seq3RasterTheme.DEFAULT_LIGHT).toPngBytes()
        val dark = renderSeq3(
            layout(doc),
            Seq3RasterTheme.DEFAULT_LIGHT.copy(background = 0xFF000000.toInt(), headerText = 0xFFFFFFFF.toInt()),
        ).toPngBytes()

        assertTrue(!light.contentEquals(dark), "a real theme change must change the rendered pixels")
    }

    @Test
    fun authoredArtifactColorsPaintTheirFillBorderAndTextAndNullFieldsInherit() {
        val noteStyle = Seq3ArtifactStyle(
            fillArgb = 0xFF35C759.toInt(),
            borderArgb = 0xFF1848C8.toInt(),
            textArgb = 0xFFE02020.toInt(),
        )
        val messageNoteStyle = Seq3ArtifactStyle(
            fillArgb = 0xFF35A6C7.toInt(),
            borderArgb = 0xFF174F60.toInt(),
            textArgb = 0xFF123456.toInt(),
        )
        val fragmentStyle = Seq3ArtifactStyle(
            fillArgb = 0xFFEBC9FA.toInt(),
            borderArgb = 0xFF8A2BE2.toInt(),
            textArgb = 0xFF4A235A.toInt(),
        )
        val base = fixedDocument()
        val noteMessage = Seq3Message(
            id = "mn1",
            match = Seq3Match("A", "note"),
            fromLifelineId = "A",
            toLifelineId = null,
            labelTemplate = "message note\nsecond line",
            kind = Seq3Kind.NOTE,
            occurrences = listOf(Seq3Occurrence(2, 2_000L, "10:00:01.000", pid = 1, tid = 1, level = 'I', text = "note")),
            noteStyle = messageNoteStyle,
        )
        val note = Seq3Note(
            id = "n1",
            text = "Multiline note first line\nSecond line with a longtokenwithoutspaces0123456789",
            messageIds = emptyList(),
            x = 42.0,
            y = 260.0,
            width = 214.0,
            height = 80.0,
            style = noteStyle,
        )
        val doc = base.copy(
            messages = base.messages + noteMessage,
            fragments = listOf(Seq3Fragment("f1", Seq3FragmentKind.ALT, "fragment label", listOf("m1"), style = fragmentStyle)),
            notes = listOf(note),
        )
        val layout = layoutSeq3(doc, Seq3LayoutOptions(StubMetrics(), maxLabelLines = 3))
        val rendered = renderSeq3(layout, Seq3RasterTheme.DEFAULT_LIGHT, scale = 1f)
        val noteBox = layout.notes.single()
        val fragment = layout.fragments.single()
        val fragmentHeaderBottom = fragment.box.y + fragment.box.height.coerceAtMost(28.0).coerceAtLeast(20.0)
        assertTrue(noteBox.lines.size >= 3, "the note fixture must exercise stored multiline wrapping")
        assertTrue(
            noteBox.box.y >= fragmentHeaderBottom,
            "the free note must not cover the fragment header whose text color is checked below",
        )
        assertTrue(containsColor(rendered.image, noteBox.textBox, noteStyle.textArgb!!), "note text override must be painted")
        assertEquals(noteStyle.fillArgb, rendered.image.getRGB((noteBox.box.x + noteBox.box.width - 20).toInt(), (noteBox.box.y + 10).toInt()))
        assertTrue(
            containsAntialiasedBorderNearVerticalEdge(rendered.image, noteBox.box, noteStyle.borderArgb!!, noteStyle.fillArgb!!),
            "note border override must contribute to antialiased edge pixels",
        )

        val messageNote = layout.rows.filterIsInstance<Seq3MessageNoteRow>().single()
        assertTrue(messageNote.lines.size >= 2, "message note should retain its wrapped layout lines")
        val messageNoteInteriorX = (messageNote.box.x + messageNote.box.width - 10).toInt()
        val messageNoteInteriorY = (messageNote.box.y + 10).toInt()
        assertEquals(messageNoteStyle.fillArgb, rendered.image.getRGB(messageNoteInteriorX, messageNoteInteriorY))
        assertTrue(
            containsAntialiasedBorderNearVerticalEdge(rendered.image, messageNote.box, messageNoteStyle.borderArgb!!, messageNoteStyle.fillArgb!!),
            "message-note border override must contribute to antialiased edge pixels",
        )
        assertTrue(containsColor(rendered.image, messageNote.textBox, messageNoteStyle.textArgb!!), "message-note text override must be painted")

        val fragmentInteriorX = (fragment.box.x + fragment.box.width - 14).toInt()
        val fragmentInteriorY = (fragment.box.y + fragment.box.height - 14).toInt()
        assertEquals(fragmentStyle.fillArgb, rendered.image.getRGB(fragmentInteriorX, fragmentInteriorY))
        assertTrue(
            containsAntialiasedBorderNearVerticalEdge(rendered.image, fragment.box, fragmentStyle.borderArgb!!, fragmentStyle.fillArgb!!),
            "fragment border override must contribute to antialiased edge pixels",
        )
        assertTrue(containsColor(rendered.image, fragment.box, fragmentStyle.textArgb!!), "fragment label override must be painted")

        val inheritedNote = note.copy(style = null)
        val inherited = renderSeq3(layout(doc.copy(notes = listOf(inheritedNote))), Seq3RasterTheme.DEFAULT_LIGHT, scale = 1f)
        val explicitThemeNote = note.copy(
            style = Seq3ArtifactStyle(
                fillArgb = Seq3RasterTheme.DEFAULT_LIGHT.noteFill,
                borderArgb = Seq3RasterTheme.DEFAULT_LIGHT.noteBorder,
                textArgb = Seq3RasterTheme.DEFAULT_LIGHT.noteText,
            ),
        )
        val explicitThemeColors = renderSeq3(
            layout(doc.copy(notes = listOf(explicitThemeNote))),
            Seq3RasterTheme.DEFAULT_LIGHT,
            scale = 1f,
        )
        assertTrue(inherited.toPngBytes().contentEquals(explicitThemeColors.toPngBytes()), "null note fields must inherit the raster theme roles")
    }

    @Test
    fun writesRepresentativeArtifactStylePngFixtureForVisualReview() {
        val a = Seq3Lifeline("A", "com.example.client.gateway.PublicApi", setOf("A"), 0, headerWidthDp = 330.0)
        val b = Seq3Lifeline("B", "Orders Service", setOf("B"), 1)
        val c = Seq3Lifeline("C", "Database", setOf("C"), 2)
        fun message(id: String, from: String, to: String?, label: String, timestamp: Long, kind: Seq3Kind = Seq3Kind.CALL): Seq3Message {
            val occurrence = Seq3Occurrence(
                entryId = timestamp.toInt(),
                timestampMillis = timestamp,
                rawTimestamp = "10:00:0${timestamp / 1_000}.000",
                pid = 1,
                tid = 1,
                level = 'I',
                text = label,
            )
            return Seq3Message(
                id = id,
                match = Seq3Match(from, label),
                fromLifelineId = from,
                toLifelineId = to,
                labelTemplate = label,
                kind = kind,
                occurrences = listOf(occurrence),
            )
        }
        val fixtureDoc = Seq3Document(
            lifelines = listOf(a, b, c),
            messages = listOf(
                message("m1", "A", "B", "GET /api/orders", 1_000),
                message("note-row", "B", null, "message note: inventory is pending\nlongtokenwithoutspaces0123456789", 2_000, Seq3Kind.NOTE)
                    .copy(noteStyle = Seq3ArtifactStyle(0xFFE9F7EF.toInt(), 0xFF27985A.toInt(), 0xFF135C37.toInt())),
                message("m2", "B", "C", "lookup=unbrokenidentifier0123456789ABCDEFGHIJKLMNOPQRSTUVWXYZ", 3_000),
                message("m3", "C", "A", "result returned", 4_000, Seq3Kind.RETURN),
            ),
            fragments = listOf(
                Seq3Fragment(
                    id = "outer-group",
                    kind = Seq3FragmentKind.ALT,
                    label = "request routing",
                    messageIds = listOf("m1", "note-row"),
                    style = Seq3ArtifactStyle(0x22A855F7, 0xFF3388CC.toInt(), 0xFF174A6E.toInt()),
                ),
                Seq3Fragment(
                    id = "next-group",
                    kind = Seq3FragmentKind.LOOP,
                    label = "database round trip",
                    messageIds = listOf("m2", "m3"),
                    style = Seq3ArtifactStyle(0x22D97706, 0xFFC56A11.toInt(), 0xFF663300.toInt()),
                ),
            ),
            notes = listOf(
                Seq3Note(
                    id = "multiline-note",
                    text = listOf(
                        "Multiline note",
                        "The handoff may wait for the order lock.",
                        "Long token: verylongunbrokenidentifier0123456789ABCDEFGHIJKLMNOPQRSTUVWXYZ",
                    ).joinToString("\n"),
                    messageIds = emptyList(),
                    x = 650.0,
                    y = 74.0,
                    width = 330.0,
                    height = 92.0,
                    style = Seq3ArtifactStyle(0xFFFFF0C2.toInt(), 0xFFB36B00.toInt(), 0xFF593600.toInt()),
                ),
            ),
            delays = listOf(
                Seq3Delay("delay-inside", afterMessageId = "m1", label = "lock acquired"),
                Seq3Delay("delay-between", afterMessageId = "note-row", label = "processing time"),
            ),
        )
        val layout = layoutSeq3(fixtureDoc, Seq3LayoutOptions(Seq3AwtTextMetrics(), maxLabelLines = 3))
        assertEquals(2, layout.fragments.size, "fixture must show adjacent grouping regions")
        assertEquals(2, layout.delays.size, "fixture must show a delay inside a group and between groups")
        assertTrue(layout.notes.single().lines.size >= 3, "fixture must show a multiline note with long-token wrapping")

        val output = File("temp_visuals_not_for_git/seq3-artifact-style-raster-qa.png")
        output.parentFile?.mkdirs()
        val rendered = renderSeq3(layout, Seq3RasterTheme.DEFAULT_LIGHT, scale = 1.5f)
        assertTrue(ImageIO.write(rendered.image, "png", output), "PNG writer should be available")
        assertTrue(output.isFile && output.length() > 0, "fixture should be written for visual review")
    }

    private fun containsColor(image: java.awt.image.BufferedImage, box: com.indagium.diagram3.Seq3Box, argb: Int): Boolean {
        val left = box.x.toInt().coerceIn(0, image.width)
        val top = box.y.toInt().coerceIn(0, image.height)
        val right = (box.x + box.width).toInt().coerceIn(left, image.width)
        val bottom = (box.y + box.height).toInt().coerceIn(top, image.height)
        for (y in top until bottom) for (x in left until right) if (image.getRGB(x, y) == argb) return true
        return false
    }

    /**
     * BasicStroke(1f) is centered on the rounded-rect path. With antialiasing enabled, the
     * boundary pixel is a real blend of the stroke and the opaque fill, rather than an exact copy
     * of the stroke ARGB. Project candidate pixels onto that expected blend segment and accept
     * only pixels with a measurable stroke contribution.
     */
    private fun containsAntialiasedBorderNearVerticalEdge(
        image: java.awt.image.BufferedImage,
        box: com.indagium.diagram3.Seq3Box,
        borderArgb: Int,
        fillArgb: Int,
    ): Boolean {
        val border = java.awt.Color(borderArgb, true)
        val fill = java.awt.Color(fillArgb, true)
        val redDelta = (border.red - fill.red).toDouble()
        val greenDelta = (border.green - fill.green).toDouble()
        val blueDelta = (border.blue - fill.blue).toDouble()
        val lengthSquared = redDelta * redDelta + greenDelta * greenDelta + blueDelta * blueDelta
        if (lengthSquared == 0.0) return false

        val edgeX = box.x.toInt()
        val left = (edgeX - EDGE_SEARCH_RADIUS_PX).coerceAtLeast(0)
        val right = (edgeX + EDGE_SEARCH_RADIUS_PX + 1).coerceAtMost(image.width)
        val top = (box.y.toInt() + BORDER_CORNER_SKIP_PX).coerceAtLeast(0)
        val bottom = (box.y + box.height).toInt().coerceAtMost(image.height) - BORDER_CORNER_SKIP_PX
        for (y in top until bottom) for (x in left until right) {
            val pixel = java.awt.Color(image.getRGB(x, y), true)
            val pixelRed = (pixel.red - fill.red).toDouble()
            val pixelGreen = (pixel.green - fill.green).toDouble()
            val pixelBlue = (pixel.blue - fill.blue).toDouble()
            val strokeCoverage = (pixelRed * redDelta + pixelGreen * greenDelta + pixelBlue * blueDelta) / lengthSquared
            if (strokeCoverage !in BORDER_BLEND_MIN_COVERAGE..BORDER_BLEND_MAX_COVERAGE) continue
            val expectedRed = fill.red + strokeCoverage * redDelta
            val expectedGreen = fill.green + strokeCoverage * greenDelta
            val expectedBlue = fill.blue + strokeCoverage * blueDelta
            if (kotlin.math.abs(pixel.red - expectedRed) <= BORDER_BLEND_CHANNEL_TOLERANCE &&
                kotlin.math.abs(pixel.green - expectedGreen) <= BORDER_BLEND_CHANNEL_TOLERANCE &&
                kotlin.math.abs(pixel.blue - expectedBlue) <= BORDER_BLEND_CHANNEL_TOLERANCE
            ) return true
        }
        return false
    }
}
