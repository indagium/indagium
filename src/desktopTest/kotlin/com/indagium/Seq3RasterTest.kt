package com.indagium

import com.indagium.diagram3.Seq3Document
import com.indagium.diagram3.Seq3FontRole
import com.indagium.diagram3.Seq3Kind
import com.indagium.diagram3.Seq3LayoutOptions
import com.indagium.diagram3.Seq3Lifeline
import com.indagium.diagram3.Seq3Match
import com.indagium.diagram3.Seq3Message
import com.indagium.diagram3.Seq3Occurrence
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

class Seq3RasterTest {
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
    fun differentThemesProduceDifferentBytesForTheSameDocument() {
        val doc = fixedDocument()
        val light = renderSeq3(layout(doc), Seq3RasterTheme.DEFAULT_LIGHT).toPngBytes()
        val dark = renderSeq3(
            layout(doc),
            Seq3RasterTheme.DEFAULT_LIGHT.copy(background = 0xFF000000.toInt(), headerText = 0xFFFFFFFF.toInt()),
        ).toPngBytes()

        assertTrue(!light.contentEquals(dark), "a real theme change must change the rendered pixels")
    }
}
