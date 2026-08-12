package com.indagium

import com.indagium.diagram.DiagramFrame
import com.indagium.diagram.DiagramMessage
import com.indagium.diagram.DiagramNoteMark
import com.indagium.diagram.DiagramOptions
import com.indagium.diagram.DiagramParticipant
import com.indagium.diagram.DiagramTheme
import com.indagium.diagram.MessageEvidence
import com.indagium.diagram.MessageKind
import com.indagium.diagram.ParticipantKind
import com.indagium.diagram.SeqDiagram
import com.indagium.diagram.SeqDiagramSpec
import com.indagium.diagram.measureLabels
import com.indagium.diagram.renderSequenceDiagram
import com.indagium.diagram.toPngBytes
import com.indagium.model.LogLevel
import java.awt.Font
import java.awt.image.BufferedImage
import java.io.ByteArrayInputStream
import javax.imageio.ImageIO
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Asserts on LAYOUT METRICS and structure, never on pixels: a golden-image comparison would be
 * brittle across JDK versions, platform font stacks and headless rasterizers, all of which differ
 * between a developer's machine and CI. Everything checked here is a property the renderer must
 * hold regardless of what the glyphs actually measure — ordering, monotonicity, scaling, clamping.
 *
 * Headless-safe by construction: BufferedImage/Graphics2D/ImageIO need no display. Nothing here
 * touches Window/Toolkit.
 */
class DiagramRendererTest {
    private val theme = DiagramTheme.LIGHT

    private fun tag(i: Int) = DiagramParticipant("T$i", "Tag $i", ParticipantKind.TAG, tag = "T$i")

    private fun actor(name: String) = DiagramParticipant(name, name, ParticipantKind.ACTOR)

    private fun msg(
        from: Int,
        to: Int,
        entryId: Int,
        label: String = "message $entryId",
        kind: MessageKind = if (from == to) MessageKind.SELF else MessageKind.CALL,
        level: LogLevel = LogLevel.I,
        repeatCount: Int = 1,
    ) = DiagramMessage(from, to, label, entryId, ts = "10:00:00.000", level = level, kind = kind, repeatCount = repeatCount)

    /** n participants, and messages ping-ponging 0->1->0->1... so every message is a real CALL. */
    private fun diagram(
        participantCount: Int = 2,
        messageCount: Int = 3,
        truncated: Boolean = false,
        title: String = "",
        frames: List<DiagramFrame> = emptyList(),
        notes: List<DiagramNoteMark> = emptyList(),
    ): SeqDiagram {
        val participants = (0 until participantCount).map { tag(it) }
        val messages = (0 until messageCount).map { i ->
            val from = i % participantCount
            val to = (i + 1) % participantCount
            msg(from, to, entryId = 100 + i)
        }
        return SeqDiagram(
            spec = SeqDiagramSpec(title = title, participants = participants),
            participants = participants,
            messages = messages,
            frames = frames,
            notes = notes,
            truncated = truncated,
        )
    }

    // ── Hit boxes ────────────────────────────────────────────────────────────────────────────

    @Test
    fun everyMessageProducesExactlyOneHitCarryingItsOwnIndexAndEntryId() {
        val d = diagram(participantCount = 3, messageCount = 6)

        val rendered = renderSequenceDiagram(d, theme)

        assertEquals(d.messages.size, rendered.hits.size)
        assertContentEquals(d.messages.indices.toList(), rendered.hits.map { it.messageIndex })
        assertContentEquals(d.messages.map { it.entryId }, rendered.hits.map { it.entryId })
    }

    @Test
    fun hitBoxesAreOrderedTopToBottomAndNeverOverlapEvenWithSelfMessagesInterleaved() {
        // Self messages reserve extra vertical space for their loop; the hit box for a self row is
        // built differently from a direct row, so interleaving them is exactly the case where an
        // off-by-one in the row pitch would let two boxes collide and make a click ambiguous.
        val participants = listOf(tag(0), tag(1))
        val messages = listOf(
            msg(0, 1, 1),
            msg(1, 1, 2),
            msg(1, 0, 3),
            msg(0, 0, 4),
            msg(0, 1, 5),
        )
        val d = SeqDiagram(
            spec = SeqDiagramSpec(participants = participants),
            participants = participants,
            messages = messages,
        )

        val hits = renderSequenceDiagram(d, theme).hits

        assertEquals(messages.size, hits.size)
        hits.zipWithNext { a, b ->
            assertTrue(a.y < b.y, "hit ${a.messageIndex} (y=${a.y}) must sit above ${b.messageIndex} (y=${b.y})")
            assertTrue(
                a.y + a.height <= b.y,
                "hit ${a.messageIndex} bottom (${a.y + a.height}) must not reach into ${b.messageIndex} top (${b.y})",
            )
        }
        assertTrue(hits.all { it.height > 0 }, "every hit box needs a positive height to be clickable")
    }

    @Test
    fun aRightToLeftMessageStillProducesAPositiveWidthHitBox() {
        // fromIdx > toIdx draws the arrow leaning left; a naive (x2 - x1) width would go negative
        // and silently make the arrow unclickable.
        val participants = listOf(tag(0), tag(1), tag(2))
        val d = SeqDiagram(
            spec = SeqDiagramSpec(participants = participants),
            participants = participants,
            messages = listOf(msg(2, 0, entryId = 7)),
        )

        val hit = renderSequenceDiagram(d, theme).hits.single()

        assertTrue(hit.width > 0, "right-to-left arrow must still have a positive-width hit box, got ${hit.width}")
        assertTrue(hit.x >= 0, "hit box must not start off-canvas, got x=${hit.x}")
    }

    @Test
    fun hitBoxesStayInsideTheRenderedImage() {
        val d = diagram(participantCount = 4, messageCount = 8, notes = listOf(DiagramNoteMark(1, 2, "boom", isError = true)))

        val rendered = renderSequenceDiagram(d, theme)

        rendered.hits.forEach { h ->
            assertTrue(h.x >= 0 && h.y >= 0, "hit ${h.messageIndex} has a negative origin (${h.x}, ${h.y})")
            assertTrue(h.x + h.width <= rendered.widthPx, "hit ${h.messageIndex} overflows width ${rendered.widthPx}")
            assertTrue(h.y + h.height <= rendered.heightPx, "hit ${h.messageIndex} overflows height ${rendered.heightPx}")
        }
    }

    // ── Growth ───────────────────────────────────────────────────────────────────────────────

    @Test
    fun imageHeightGrowsWithMessageCountAndWidthGrowsWithParticipantCount() {
        val short = renderSequenceDiagram(diagram(messageCount = 2), theme)
        val tall = renderSequenceDiagram(diagram(messageCount = 20), theme)
        assertTrue(tall.heightPx > short.heightPx, "20 messages must be taller than 2 (${tall.heightPx} vs ${short.heightPx})")

        val narrow = renderSequenceDiagram(diagram(participantCount = 2), theme)
        val wide = renderSequenceDiagram(diagram(participantCount = 6), theme)
        assertTrue(wide.widthPx > narrow.widthPx, "6 participants must be wider than 2 (${wide.widthPx} vs ${narrow.widthPx})")
    }

    @Test
    fun aTruncatedDiagramIsTallerThanTheSameDiagramWithoutTheBanner() {
        // The banner is the only thing telling the user the diagram is incomplete — if it silently
        // took no vertical space it would be overdrawing the last row instead of sitting below it.
        val plain = renderSequenceDiagram(diagram(messageCount = 5, truncated = false), theme)
        val capped = renderSequenceDiagram(diagram(messageCount = 5, truncated = true), theme)

        assertTrue(capped.heightPx > plain.heightPx, "truncation banner must add height (${capped.heightPx} vs ${plain.heightPx})")
        assertEquals(plain.widthPx, capped.widthPx, "the banner must not change the diagram's width")
    }

    @Test
    fun aTitleAddsHeightAndPushesEveryRowDown() {
        val untitled = renderSequenceDiagram(diagram(messageCount = 3), theme)
        val titled = renderSequenceDiagram(diagram(messageCount = 3, title = "Bluetooth enable path"), theme)

        assertTrue(titled.heightPx > untitled.heightPx)
        untitled.hits.zip(titled.hits).forEach { (u, t) ->
            assertTrue(t.y > u.y, "row ${u.messageIndex} must move down to make room for the title")
        }
    }

    // ── Scaling ──────────────────────────────────────────────────────────────────────────────

    @Test
    fun renderingAtScaleTwoDoublesEveryDimensionAndEveryHitBox() {
        // Phase 3 draws the image at logical size on a HiDPI display, so a 2x raster must be exactly
        // a 2x raster — if hit boxes did not scale with it, clicks would land on the wrong row.
        val d = diagram(participantCount = 3, messageCount = 5)

        val at1 = renderSequenceDiagram(d, theme, scale = 1f)
        val at2 = renderSequenceDiagram(d, theme, scale = 2f)

        assertEquals(1f, at1.scale)
        assertEquals(2f, at2.scale)
        // Tolerance absorbs per-constant rounding (each base value is rounded independently at each
        // scale) and font metrics that are not perfectly linear in point size.
        assertWithinPercent(at1.widthPx * 2, at2.widthPx, 6, "width")
        assertWithinPercent(at1.heightPx * 2, at2.heightPx, 6, "height")

        at1.hits.zip(at2.hits).forEach { (h1, h2) ->
            assertEquals(h1.messageIndex, h2.messageIndex)
            assertWithinPercent(h1.y * 2, h2.y, 6, "hit ${h1.messageIndex} y")
        }
    }

    @Test
    fun imageDimensionsMatchTheReportedWidthAndHeight() {
        val rendered = renderSequenceDiagram(diagram(participantCount = 3, messageCount = 4), theme)

        assertEquals(rendered.widthPx, rendered.image.width)
        assertEquals(rendered.heightPx, rendered.image.height)
    }

    // ── Degenerate input ─────────────────────────────────────────────────────────────────────

    @Test
    fun anEmptyDiagramRendersAPlaceholderInsteadOfThrowingOrProducingAZeroSizedImage() {
        // A zero-sized BufferedImage throws on construction, so "no participants" must be handled
        // explicitly rather than falling through the normal layout path.
        val empty = SeqDiagram(spec = SeqDiagramSpec(), participants = emptyList(), messages = emptyList())

        val rendered = renderSequenceDiagram(empty, theme)

        assertTrue(rendered.widthPx > 0 && rendered.heightPx > 0)
        assertTrue(rendered.hits.isEmpty())
        assertEquals(rendered.widthPx, rendered.image.width)
    }

    @Test
    fun participantsWithNoMessagesStillRenderTheirHeadersAndLifelines() {
        val participants = listOf(tag(0), actor("User"))
        val d = SeqDiagram(spec = SeqDiagramSpec(participants = participants), participants = participants, messages = emptyList())

        val rendered = renderSequenceDiagram(d, theme)

        assertTrue(rendered.hits.isEmpty())
        assertTrue(rendered.widthPx > 0 && rendered.heightPx > 0)
    }

    @Test
    fun aMessageReferencingAParticipantIndexOutOfRangeIsSkippedRatherThanCrashing() {
        // SeqDiagram is a plain data class; a hand-built or decoded-from-JSON one can carry a stale
        // index. Rendering must degrade, never throw, in line with the rest of this package.
        val participants = listOf(tag(0), tag(1))
        val d = SeqDiagram(
            spec = SeqDiagramSpec(participants = participants),
            participants = participants,
            messages = listOf(msg(0, 1, 1), msg(0, 99, 2), msg(1, 0, 3)),
        )

        val hits = renderSequenceDiagram(d, theme).hits

        assertEquals(listOf(0, 2), hits.map { it.messageIndex }, "the out-of-range message must be dropped, the others kept")
    }

    @Test
    fun anExtremelyLargeDiagramIsClampedToTheMaximumRasterDimension() {
        // Without a clamp, a pathological diagram would try to allocate a gigapixel raster and OOM
        // the app rather than producing a merely-unreadable picture.
        val d = diagram(participantCount = 40, messageCount = 4000)

        val rendered = renderSequenceDiagram(d, theme, scale = 2f)

        assertTrue(rendered.widthPx in 1..MAX_DIM, "width ${rendered.widthPx} must be clamped to $MAX_DIM")
        assertTrue(rendered.heightPx in 1..MAX_DIM, "height ${rendered.heightPx} must be clamped to $MAX_DIM")
        assertEquals(rendered.widthPx, rendered.image.width)
        assertEquals(rendered.heightPx, rendered.image.height)
    }

    // ── PNG ──────────────────────────────────────────────────────────────────────────────────

    @Test
    fun toPngBytesEmitsARealPngThatRoundTripsAtTheSameDimensions() {
        val rendered = renderSequenceDiagram(diagram(participantCount = 3, messageCount = 4), theme)

        val bytes = rendered.toPngBytes()

        assertTrue(bytes.size > 8, "expected a non-trivial PNG, got ${bytes.size} bytes")
        assertContentEquals(
            byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47),
            bytes.copyOfRange(0, 4),
            "must start with the PNG magic number",
        )
        val reread = assertNotNull(ImageIO.read(ByteArrayInputStream(bytes)), "PNG bytes must be readable back")
        assertEquals(rendered.widthPx, reread.width)
        assertEquals(rendered.heightPx, reread.height)
    }

    @Test
    fun theBackgroundIsOpaqueSoTheDiagramStaysReadableWhereverItIsPasted() {
        // A fresh TYPE_INT_ARGB raster is fully transparent; pasting a transparent PNG with dark
        // text onto a dark Jira/Confluence page would render it invisible.
        val rendered = renderSequenceDiagram(diagram(), theme)

        val corner = rendered.image.getRGB(0, 0)
        assertEquals(0xFF, corner ushr 24 and 0xFF, "the top-left pixel must be fully opaque")
    }

    // ── Frames and notes ─────────────────────────────────────────────────────────────────────

    @Test
    fun aFrameSpanningRowsDoesNotDisturbHitBoxOrdering() {
        // Frames paint behind the rows; if their layout leaked into the row pitch, the boxes would
        // shift and clicks would land on the neighbouring message.
        val plain = renderSequenceDiagram(diagram(messageCount = 6), theme)
        val framed = renderSequenceDiagram(
            diagram(messageCount = 6, frames = listOf(DiagramFrame("outer", null, 1, 4, 0), DiagramFrame("inner", null, 2, 3, 1))),
            theme,
        )

        assertContentEquals(plain.hits.map { it.y }, framed.hits.map { it.y })
    }

    @Test
    fun aFrameWithOutOfRangeMessageIndicesIsSkippedRatherThanCrashing() {
        val d = diagram(messageCount = 3, frames = listOf(DiagramFrame("stale", null, 5, 9, 0)))

        val rendered = renderSequenceDiagram(d, theme)

        assertEquals(3, rendered.hits.size)
        assertTrue(rendered.heightPx > 0)
    }

    @Test
    fun anErrorNoteWidensTheCanvasRatherThanBeingClippedAway() {
        val plain = renderSequenceDiagram(diagram(participantCount = 2, messageCount = 3), theme)
        val noted = renderSequenceDiagram(
            diagram(
                participantCount = 2,
                messageCount = 3,
                notes = listOf(DiagramNoteMark(1, 1, "FATAL EXCEPTION: main", isError = true)),
            ),
            theme,
        )

        assertTrue(noted.widthPx > plain.widthPx, "a note must extend the canvas (${noted.widthPx} vs ${plain.widthPx})")
    }

    // ── Measured labels + per-gap columns (Parts 1-2) ────────────────────────────────────────

    @Test
    fun repeatSuffixSurvivesOnALabelLongEnoughToWrapAndEllipsize() {
        // Direct unit test of measureLabels (internal — visible to this same-module test, see
        // DiagramSpecCodec.kt's MAX_CODEC_COMPONENTS for the same cross-package precedent):
        // appending the "×N" suffix BEFORE ellipsizing (the pre-Part-2 bug) would lose it on any
        // label long enough to need truncation; measureLabels reserves room for it instead.
        val img = BufferedImage(1, 1, BufferedImage.TYPE_INT_ARGB)
        val g = img.createGraphics()
        val fm = g.getFontMetrics(Font(Font.SANS_SERIF, Font.PLAIN, 12))
        val longLabel = "a very long repeated operation that will not fit on a single short line at all"
        val message = DiagramMessage(0, 1, longLabel, 1, "10:00:00.000", LogLevel.I, MessageKind.CALL, repeatCount = 7)

        val measured = measureLabels(listOf(message), fm, maxLines = 1, maxWidthPx = 120).single()

        assertTrue(measured.lines.last().endsWith("×7"), "the repeat suffix must survive on the last line: ${measured.lines}")
    }

    @Test
    fun labelMaxLinesOneAlwaysProducesASingleLineRegardlessOfTextLength() {
        val img = BufferedImage(1, 1, BufferedImage.TYPE_INT_ARGB)
        val g = img.createGraphics()
        val fm = g.getFontMetrics(Font(Font.SANS_SERIF, Font.PLAIN, 12))
        val longLabel = "this label has plenty of words and would normally wrap onto several lines"
        val message = DiagramMessage(0, 1, longLabel, 1, "10:00:00.000", LogLevel.I, MessageKind.CALL)

        val measured = measureLabels(listOf(message), fm, maxLines = 1, maxWidthPx = 120).single()

        assertEquals(1, measured.lines.size, "${measured.lines}")
    }

    @Test
    fun aLongSelfLabelOnTheRightmostLifelineWidensTheCanvasInsteadOfClippingItsHitBox() {
        // The direct regression test for the reported bug: a self message on the LAST column has
        // no gap to its right to widen, so its required width must instead extend widthPx itself.
        val participants = listOf(tag(0), tag(1))
        val shortLabel = msg(1, 1, entryId = 1, label = "ok")
        val longLabel = msg(
            1, 1, entryId = 1,
            label = "waiting for a very long-winded response that keeps going and going and going",
        )
        val short = renderSequenceDiagram(
            SeqDiagram(spec = SeqDiagramSpec(participants = participants), participants = participants, messages = listOf(shortLabel)),
            theme,
        )
        val long = renderSequenceDiagram(
            SeqDiagram(spec = SeqDiagramSpec(participants = participants), participants = participants, messages = listOf(longLabel)),
            theme,
        )

        assertTrue(long.widthPx > short.widthPx, "a long self label on the last column must widen the canvas (${long.widthPx} vs ${short.widthPx})")
        long.hits.forEach { h ->
            assertTrue(h.x + h.width <= long.widthPx, "hit ${h.messageIndex} overflows width ${long.widthPx} (x=${h.x} w=${h.width})")
        }
    }

    @Test
    fun aSelfArrowHitCoversTheLoopPlusItsLabelNotJustTheLoop() {
        val participants = listOf(tag(0), tag(1), tag(2))

        fun render(label: String) = renderSequenceDiagram(
            SeqDiagram(
                spec = SeqDiagramSpec(participants = participants),
                participants = participants,
                messages = listOf(msg(0, 0, entryId = 1, label = label)),
            ),
            theme,
        )

        val shortHit = render("hi").hits.single()
        val longHit = render("a substantially longer self-message label than the short one above").hits.single()

        assertTrue(longHit.width > shortHit.width, "a longer self label must produce a wider hit box (${longHit.width} vs ${shortHit.width})")
    }

    @Test
    fun aWrappingLabelWidensOnlyItsOwnRowAndPushesLaterRowsDownWithoutChangingTheirOwnPitch() {
        // labelMaxLines=6 with a genuinely long label: two wrapped lines alone don't exceed the
        // BASE_ROW_H floor at this font size (a shorter wrap would leave pitch unchanged, which is
        // correct but not what this test is checking), so this needs enough words to wrap onto
        // several lines and actually grow past that floor.
        val optionsWithWrap = DiagramOptions(labelMaxLines = 6)
        val participants = listOf(tag(0), tag(1))
        val longLabel = "this particular label has quite a few separate words in it and will wrap onto " +
            "several lines of text rather than staying on just one or two lines like the others around it"
        val baseline = listOf(msg(0, 1, 1, label = "hi"), msg(1, 0, 2, label = "hi"), msg(0, 1, 3, label = "hi"))
        val wrapped = listOf(
            msg(0, 1, 1, label = "hi"),
            msg(1, 0, 2, label = longLabel),
            msg(0, 1, 3, label = "hi"),
        )
        val base = renderSequenceDiagram(
            SeqDiagram(spec = SeqDiagramSpec(participants = participants, options = optionsWithWrap), participants = participants, messages = baseline),
            theme,
        )
        val long = renderSequenceDiagram(
            SeqDiagram(spec = SeqDiagramSpec(participants = participants, options = optionsWithWrap), participants = participants, messages = wrapped),
            theme,
        )

        // A direct hit box's TOP is pinned to the PRECEDING row's reserved space (buildHits'
        // neighbor-clamped shape), so a row's own pitch growth shows up as that row's hit growing
        // TALLER, not as its top moving — row 1's hit box must grow to make room for its own wrap.
        assertTrue(long.hits[1].height > base.hits[1].height, "row 1's own hit must grow taller (${long.hits[1].height} vs ${base.hits[1].height})")

        // Row 2's own pitch (its hit box's OWN height) is unaffected by row 1's wrap...
        assertEquals(base.hits[2].height, long.hits[2].height, "row 2's own pitch must be unaffected by row 1's wrap")
        // ...even though row 2 is still pushed further down the canvas overall.
        assertTrue(long.hits[2].y > base.hits[2].y, "row 2 must be pushed down overall (${long.hits[2].y} vs ${base.hits[2].y})")
    }

    @Test
    fun mixedOneLineAndFourLineLabelsKeepHitBoxesOrderedAndNonOverlapping() {
        val participants = listOf(tag(0), tag(1))
        val messages = listOf(
            msg(0, 1, 1, label = "short"),
            msg(1, 0, 2, label = "a much longer label with many separate words that will wrap across several lines of text"),
            msg(0, 1, 3, label = "short again"),
            msg(1, 1, 4, label = "and a self message with its own reasonably long label attached to it as well"),
            msg(0, 1, 5, label = "short"),
        )
        val d = SeqDiagram(
            spec = SeqDiagramSpec(participants = participants, options = DiagramOptions(labelMaxLines = 4)),
            participants = participants,
            messages = messages,
        )

        val hits = renderSequenceDiagram(d, theme).hits

        assertEquals(messages.size, hits.size)
        hits.zipWithNext { a, b ->
            assertTrue(a.y < b.y, "hit ${a.messageIndex} must sit above ${b.messageIndex}")
            assertTrue(a.y + a.height <= b.y, "hit ${a.messageIndex} bottom must not reach into ${b.messageIndex} top")
        }
    }

    @Test
    fun aFiveThousandCharacterLabelDoesNotForceAGlobalDownscale() {
        val participants = listOf(tag(0), tag(1))
        val hugeLabel = "word ".repeat(1000).trim()
        val d = SeqDiagram(
            spec = SeqDiagramSpec(participants = participants),
            participants = participants,
            messages = listOf(msg(0, 1, 1, label = hugeLabel)),
        )

        val rendered = renderSequenceDiagram(d, theme, scale = 2f)

        assertEquals(2f, rendered.scale, "a pathological label must be wrapped/ellipsized within its own bounded budget, never shrink the whole diagram")
        assertTrue(rendered.widthPx in 1..MAX_DIM)
        assertTrue(rendered.heightPx in 1..MAX_DIM)
    }

    @Test
    fun sourceInferredEvidenceStillRendersAsADashedLine() {
        // Not a new behavior — a regression guard that paintDirectMessage's dashed-stroke branch
        // (SOURCE_INFERRED, or kind RETURN) still compiles/renders after the Part 3 label-drawing
        // rewrite. Asserted structurally (a valid, non-empty hit) since this file never inspects
        // pixels.
        val participants = listOf(tag(0), tag(1))
        val message = msg(0, 1, 1, kind = MessageKind.CALL).copy(evidence = MessageEvidence.SOURCE_INFERRED)
        val d = SeqDiagram(spec = SeqDiagramSpec(participants = participants), participants = participants, messages = listOf(message))

        val hit = renderSequenceDiagram(d, theme).hits.single()

        assertTrue(hit.width > 0 && hit.height > 0)
    }

    @Test
    fun asyncMessageUsesDirectGeometryAndKeepsItsClickableHitBox() {
        // Async rendering changes only the stroke/head treatment. It remains a direct message,
        // so its hit region must cover the same source-to-target span and stay disjoint from its
        // neighboring rows just as a normal CALL does.
        val participants = listOf(tag(0), tag(1))
        val messages = listOf(
            msg(0, 1, 1, kind = MessageKind.CALL),
            msg(1, 0, 2, kind = MessageKind.ASYNC, label = "dispatch"),
            msg(0, 1, 3, kind = MessageKind.RETURN),
        )
        val rendered = renderSequenceDiagram(
            SeqDiagram(spec = SeqDiagramSpec(participants = participants), participants = participants, messages = messages),
            theme,
        )

        assertEquals(messages.indices.toList(), rendered.hits.map { it.messageIndex })
        val asyncHit = rendered.hits[1]
        assertEquals(2, asyncHit.entryId)
        assertTrue(asyncHit.width > 0 && asyncHit.height > 0)
        assertTrue(asyncHit.x >= 0 && asyncHit.x + asyncHit.width <= rendered.widthPx)
        assertTrue(asyncHit.y >= 0 && asyncHit.y + asyncHit.height <= rendered.heightPx)
        rendered.hits.zipWithNext { first, second ->
            assertTrue(first.y + first.height <= second.y, "neighboring message hits must not overlap")
        }
    }

    private fun assertWithinPercent(expected: Int, actual: Int, tolerancePercent: Int, what: String) {
        val allowed = (expected * tolerancePercent / 100.0).coerceAtLeast(2.0)
        assertTrue(
            abs(actual - expected) <= allowed,
            "$what: expected ~$expected (±$tolerancePercent%), got $actual",
        )
    }

    private companion object {
        // Mirrors SeqDiagramRenderer's own private MAX_IMAGE_DIM_PX. Duplicated deliberately: the
        // test asserts the published contract ("never larger than 8000px"), so it should fail if
        // someone quietly raises the constant, not silently follow it.
        const val MAX_DIM = 8000
    }
}
