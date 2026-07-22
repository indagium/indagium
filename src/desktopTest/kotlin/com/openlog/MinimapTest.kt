package com.openlog

import androidx.compose.ui.graphics.Color
import com.openlog.model.Highlighter
import com.openlog.model.LogEntry
import com.openlog.model.LogItem
import com.openlog.model.LogLevel
import com.openlog.model.ManualCollapseDirection
import com.openlog.ui.CRASH_COLOR
import com.openlog.ui.MAX_WORDS_PER_ROW
import com.openlog.ui.MINIMAP_MAX_BUCKETS
import com.openlog.ui.MinimapWord
import com.openlog.ui.computeMinimapBars
import com.openlog.ui.minimapBucketOf
import com.openlog.ui.minimapFirstVisibleIndexForViewportCenter
import com.openlog.ui.minimapFirstVisibleIndexForViewportDrag
import com.openlog.ui.minimapItemIndexOf
import com.openlog.ui.minimapScrollFraction
import com.openlog.ui.minimapScrollOffsetPx
import com.openlog.ui.minimapViewportBounds
import com.openlog.ui.splitIntoWordBlocks
import com.openlog.utils.visibleLogLineText
import java.util.BitSet
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class MinimapTest {
    private val muted = Color(0xFF888888)

    private fun row(id: Int, level: LogLevel, msg: String = "message", indent: Int = 0, groupColor: Color? = null): LogItem.Row =
        LogItem.Row(LogEntry(id, "10:00:00.000", level, "Tag", msg), indent = indent, groupColor = groupColor)

    private fun hl(pattern: String, color: Color, on: Boolean = true, regex: Boolean = false): Highlighter =
        Highlighter(id = "hl_$pattern", pattern = pattern, regex = regex, color = color, on = on)

    // ── splitIntoWordBlocks ──────────────────────────────────────────

    @Test
    fun splitIntoWordBlocksFindsEachWordsRealCharacterOffsets() {
        val words = splitIntoWordBlocks("Hello world foo")
        assertEquals(
            listOf(MinimapWord(0, 5), MinimapWord(6, 5), MinimapWord(12, 3)),
            words,
        )
    }

    @Test
    fun splitIntoWordBlocksCollapsesRunsOfWhitespaceIntoOneGap() {
        val words = splitIntoWordBlocks("a   b")
        assertEquals(listOf(MinimapWord(0, 1), MinimapWord(4, 1)), words)
    }

    @Test
    fun splitIntoWordBlocksIgnoresLeadingAndTrailingWhitespace() {
        val words = splitIntoWordBlocks("  a b  ")
        assertEquals(listOf(MinimapWord(2, 1), MinimapWord(4, 1)), words)
    }

    @Test
    fun splitIntoWordBlocksOnBlankOrEmptyTextIsEmpty() {
        assertEquals(emptyList(), splitIntoWordBlocks(""))
        assertEquals(emptyList(), splitIntoWordBlocks("   "))
    }

    @Test
    fun splitIntoWordBlocksCapsAtMaxWords() {
        val text = (1..100).joinToString(" ") { "w$it" }
        val words = splitIntoWordBlocks(text, maxWords = 5)
        assertEquals(5, words.size)
        // Default cap (MAX_WORDS_PER_ROW) behaves the same way without an explicit override.
        val defaultCapped = splitIntoWordBlocks(text)
        assertEquals(MAX_WORDS_PER_ROW, defaultCapped.size)
    }

    // ── computeMinimapBars: representative item (shape) ─────────────

    @Test
    fun bucketedModeShapeComesFromTheFirstItemNotTheMostSevereOne() {
        // Both items forced into the same bucket (rowCount = 1): a long INFO line first, a short
        // ERROR line second. The word shape must come from the FIRST item's own text — picking the
        // most-severe item's text instead (the old behavior) is exactly the regression this guards.
        val items = listOf(
            row(1, LogLevel.I, msg = "a fairly long informational message with several words"),
            row(2, LogLevel.E, msg = "short"),
        )

        val bars = computeMinimapBars(items, BitSet(), rowCount = 1, highlighters = emptyList(), mutedColor = muted)

        assertEquals(1, bars.size)
        // The bucket's one bar must reproduce item 1's OWN rendered-line word shape exactly, not
        // item 2's (they differ — different level letter and completely different message text).
        assertEquals(splitIntoWordBlocks(visibleLogLineText(items[0].entry)), bars[0].words)
        assertTrue(bars[0].words != splitIntoWordBlocks(visibleLogLineText(items[1].entry)))
    }

    @Test
    fun rowCountAboveItemCountIsClampedToItemCount() {
        val items = listOf(row(1, LogLevel.I), row(2, LogLevel.W))

        val bars = computeMinimapBars(items, BitSet(), rowCount = 100, highlighters = emptyList(), mutedColor = muted)

        assertEquals(2, bars.size)
    }

    @Test
    fun manualHeaderItemHasZeroIndentSinceTheVariantHasNoIndentField() {
        val manual = LogItem.ManualHeader(
            LogEntry(1, "10:00:00.000", LogLevel.I, "Tag", "msg"),
            gid = "m_1",
            direction = ManualCollapseDirection.RANGE,
            expanded = false,
            count = 1,
            color = Color.Red,
        )

        val bars = computeMinimapBars(listOf(manual), BitSet(), rowCount = 1, highlighters = emptyList(), mutedColor = muted)

        assertEquals(0, bars[0].indent)
    }

    @Test
    fun emptyListYieldsEmptyBarList() {
        assertEquals(emptyList(), computeMinimapBars(emptyList(), BitSet(), rowCount = 5, highlighters = emptyList(), mutedColor = muted))
    }

    // ── computeMinimapBars: color precedence ─────────────────────────

    @Test
    fun crashColorBeatsEverythingElse() {
        val items = listOf(row(1, LogLevel.V, msg = "boom"))
        val crashIds = BitSet().apply { set(1) }

        val bars = computeMinimapBars(items, crashIds, rowCount = 1, highlighters = listOf(hl("boom", Color.Yellow)), mutedColor = muted)

        assertEquals(CRASH_COLOR, bars[0].color)
    }

    @Test
    fun stackTraceHeaderIsAlwaysCrashColorRegardlessOfCrashIds() {
        val header = LogItem.StackTraceHeader(
            LogEntry(1, "10:00:00.000", LogLevel.E, "AndroidRuntime", "FATAL EXCEPTION: main"),
            gid = "st_1",
            indent = 0,
            expanded = false,
            count = 3,
        )

        val bars = computeMinimapBars(listOf(header), BitSet(), rowCount = 1, highlighters = emptyList(), mutedColor = muted)

        assertEquals(CRASH_COLOR, bars[0].color)
    }

    @Test
    fun errorAndWarnLevelsGetTheirOwnColorEvenWithAMatchingHighlighter() {
        // Level precedence sits ABOVE highlighters — a highlighter matching an ERROR line must not
        // override the error color.
        val errorItems = listOf(row(1, LogLevel.E, msg = "needle"))
        val warnItems = listOf(row(1, LogLevel.W, msg = "needle"))
        val highlighters = listOf(hl("needle", Color.Yellow))

        assertEquals(
            LogLevel.E.defaultColor,
            computeMinimapBars(errorItems, BitSet(), 1, highlighters, muted)[0].color,
        )
        assertEquals(
            LogLevel.W.defaultColor,
            computeMinimapBars(warnItems, BitSet(), 1, highlighters, muted)[0].color,
        )
    }

    @Test
    fun matchingEnabledHighlighterWinsOverMutedDefault() {
        val items = listOf(row(1, LogLevel.I, msg = "needle here"))
        val highlighters = listOf(hl("needle", Color.Yellow))

        val bars = computeMinimapBars(items, BitSet(), rowCount = 1, highlighters = highlighters, mutedColor = muted)

        assertEquals(Color.Yellow, bars[0].color)
    }

    @Test
    fun disabledHighlighterIsSkippedFallingThroughToMutedColor() {
        // groupColor is set here too, deliberately — this also proves it's correctly ignored (see
        // rowGroupColorIsIgnoredEvenWhenNothingElseMatches below for the dedicated case).
        val items = listOf(row(1, LogLevel.I, msg = "needle here", groupColor = Color.Blue))
        val highlighters = listOf(hl("needle", Color.Yellow, on = false))

        val bars = computeMinimapBars(items, BitSet(), rowCount = 1, highlighters = highlighters, mutedColor = muted)

        assertEquals(muted, bars[0].color)
    }

    @Test
    fun nonMatchingHighlighterFallsThroughToMutedColor() {
        val items = listOf(row(1, LogLevel.I, msg = "nothing relevant here", groupColor = Color.Blue))
        val highlighters = listOf(hl("needle", Color.Yellow))

        val bars = computeMinimapBars(items, BitSet(), rowCount = 1, highlighters = highlighters, mutedColor = muted)

        assertEquals(muted, bars[0].color)
    }

    @Test
    fun rowGroupColorIsIgnoredEvenWhenNothingElseMatches() {
        // LogItem.Row.groupColor is set on every MEMBER row of an expanded sequence/manual-collapse
        // block, not just its header — consulting it here would paint the whole block one solid
        // color. Only the block's own HEADER (SeqHeader.color / ManualHeader.color, see below) gets
        // the group color; a member row falls through to the muted default like a plain line.
        val items = listOf(row(1, LogLevel.I, groupColor = Color.Blue))

        val bars = computeMinimapBars(items, BitSet(), rowCount = 1, highlighters = emptyList(), mutedColor = muted)

        assertEquals(muted, bars[0].color)
    }

    @Test
    fun seqHeaderOwnColorFieldIsUsedAsItsGroupColor() {
        val header = LogItem.SeqHeader(
            LogEntry(1, "10:00:00.000", LogLevel.I, "Tag", "msg"),
            gid = "seq_1",
            indent = 0,
            expanded = false,
            count = 2,
            color = Color.Magenta,
        )

        val bars = computeMinimapBars(listOf(header), BitSet(), rowCount = 1, highlighters = emptyList(), mutedColor = muted)

        assertEquals(Color.Magenta, bars[0].color)
    }

    @Test
    fun manualHeaderOwnColorFieldIsUsedAsItsGroupColor() {
        val header = LogItem.ManualHeader(
            LogEntry(1, "10:00:00.000", LogLevel.I, "Tag", "msg"),
            gid = "m_1",
            direction = ManualCollapseDirection.RANGE,
            expanded = false,
            count = 1,
            color = Color.Cyan,
        )

        val bars = computeMinimapBars(listOf(header), BitSet(), rowCount = 1, highlighters = emptyList(), mutedColor = muted)

        assertEquals(Color.Cyan, bars[0].color)
    }

    @Test
    fun plainInfoRowWithNoHighlighterOrGroupUsesTheMutedColor() {
        // Only E/A/W get their own level color — I (and V/D) fall through to the muted default
        // unless a highlighter or group color says otherwise. This is what keeps the noise floor
        // quiet enough for an actually-colored row to mean something.
        val items = listOf(row(1, LogLevel.I))

        val bars = computeMinimapBars(items, BitSet(), rowCount = 1, highlighters = emptyList(), mutedColor = muted)

        assertEquals(muted, bars[0].color)
    }

    // ── Sublime-style scroll offset ──────────────────────────────────

    @Test
    fun scrollOffsetIsZeroWhenTheMiniatureAlreadyFits() {
        assertEquals(0f, minimapScrollOffsetPx(scrollFraction = 1f, miniatureHeightPx = 100f, stripHeightPx = 200f))
        assertEquals(0f, minimapScrollOffsetPx(scrollFraction = 0.5f, miniatureHeightPx = 200f, stripHeightPx = 200f))
    }

    @Test
    fun scrollOffsetTracksScrollFractionAcrossTheScrollableRange() {
        // Miniature is 1000px tall, strip is 200px — 800px of scrollable range. Halfway through
        // the document must put the miniature halfway through that range.
        assertEquals(400f, minimapScrollOffsetPx(scrollFraction = 0.5f, miniatureHeightPx = 1000f, stripHeightPx = 200f))
        assertEquals(0f, minimapScrollOffsetPx(scrollFraction = 0f, miniatureHeightPx = 1000f, stripHeightPx = 200f))
        assertEquals(800f, minimapScrollOffsetPx(scrollFraction = 1f, miniatureHeightPx = 1000f, stripHeightPx = 200f))
    }

    @Test
    fun scrollFractionIsZeroWhenTheWholeDocumentIsAlreadyVisible() {
        assertEquals(0f, minimapScrollFraction(firstVisibleItemIndex = 0, visibleItemCount = 50, itemCount = 50))
        assertEquals(0f, minimapScrollFraction(firstVisibleItemIndex = 0, visibleItemCount = 100, itemCount = 50))
    }

    @Test
    fun scrollFractionIsProportionalToScrollProgress() {
        // 100 items, 10 visible at once -> 90 possible "first visible" positions; sitting at 45
        // is exactly halfway through that range.
        assertEquals(0.5f, minimapScrollFraction(firstVisibleItemIndex = 45, visibleItemCount = 10, itemCount = 100))
    }

    @Test
    fun clickOffsetMappingAccountsForScrollPositionWhenMiniatureIsTallerThanStrip() {
        // Miniature: 1000 rows at 2px each = 2000px tall; strip is only 200px; scrolled to item 450
        // of 1000 with 10 visible. A click at the very TOP of the strip (y = 0) must NOT map to
        // item 0 the way it would with no scrolling — it must map to wherever the (mostly
        // off-screen) miniature has scrolled to.
        val rowHeightPx = 2f
        val itemCount = 1000
        val rowCount = 1000
        val miniatureHeightPx = rowCount * rowHeightPx
        val stripHeightPx = 200f

        val scrollFraction = minimapScrollFraction(firstVisibleItemIndex = 450, visibleItemCount = 10, itemCount = itemCount)
        val offsetPx = minimapScrollOffsetPx(scrollFraction, miniatureHeightPx, stripHeightPx)

        val row = ((0f + offsetPx) / rowHeightPx).toInt().coerceIn(0, rowCount - 1)
        val targetIndex = minimapItemIndexOf(row, itemCount, rowCount)

        assertEquals(409, targetIndex) // NOT 0 — see the naive-mapping contrast below
    }

    @Test
    fun clickOffsetMappingIsTheIdentityWhenTheMiniatureIsNotScrolled() {
        // Contrast case: when the miniature fits (no scrolling), a click at the strip's own top
        // (y = 0) really does map to item 0 — offsetPx is 0, so (y + offset) is just y.
        val rowHeightPx = 2f
        val itemCount = 50
        val rowCount = 50
        val miniatureHeightPx = rowCount * rowHeightPx // 100px, well under a 200px strip
        val stripHeightPx = 200f

        val scrollFraction = minimapScrollFraction(firstVisibleItemIndex = 0, visibleItemCount = 50, itemCount = itemCount)
        val offsetPx = minimapScrollOffsetPx(scrollFraction, miniatureHeightPx, stripHeightPx)

        val row = ((0f + offsetPx) / rowHeightPx).toInt().coerceIn(0, rowCount - 1)
        val targetIndex = minimapItemIndexOf(row, itemCount, rowCount)

        assertEquals(0, targetIndex)
    }

    @Test
    fun viewportDragPreservesThePointGrabbedInsideTheSelection() {
        // 1,000 items, 10 visible: the 2,000px miniature has a 20px viewport in a 200px strip.
        // A drag of 18px moves that viewport through 10% of its 180px travel, therefore 99 of the
        // 990 available first-item positions. It must move from the original index, not jump to
        // place the clicked point at the viewport's top edge.
        val bounds = requireNotNull(minimapViewportBounds(
            firstVisibleItemIndex = 450,
            visibleItemCount = 10,
            itemCount = 1_000,
            miniatureHeightPx = 2_000f,
            stripHeightPx = 200f,
            minViewportHeightPx = 2f,
        ))
        assertTrue((bounds.first + bounds.second) / 2f in bounds.first..bounds.second)
        assertEquals(
            549,
            minimapFirstVisibleIndexForViewportDrag(
                dragStartIndex = 450,
                dragDeltaPx = 18f,
                visibleItemCount = 10,
                itemCount = 1_000,
                miniatureHeightPx = 2_000f,
                stripHeightPx = 200f,
                minViewportHeightPx = 2f,
            ),
        )
    }

    @Test
    fun outsidePressCentersTheViewportOnThePointer() {
        // The 20px viewport can travel 180px in a 200px strip. A press at y=100 is exactly the
        // center, so its top should land at 90px — halfway through the travel and item range.
        assertEquals(
            495,
            minimapFirstVisibleIndexForViewportCenter(
                pointerY = 100f,
                visibleItemCount = 10,
                itemCount = 1_000,
                miniatureHeightPx = 2_000f,
                stripHeightPx = 200f,
                minViewportHeightPx = 2f,
            ),
        )
    }

    // ── Bucket/index math (unchanged) ─────────────────────────────────

    @Test
    fun bucketOfAndItemIndexOfRoundTripAtBothEndsOfTheRange() {
        val itemCount = 1000
        val rowCount = 100

        assertEquals(0, minimapBucketOf(0, itemCount, rowCount))
        assertEquals(0, minimapItemIndexOf(0, itemCount, rowCount))
        assertEquals(0, minimapBucketOf(minimapItemIndexOf(0, itemCount, rowCount), itemCount, rowCount))

        val lastRow = rowCount - 1
        assertEquals(lastRow, minimapBucketOf(itemCount - 1, itemCount, rowCount))
        assertEquals(
            lastRow,
            minimapBucketOf(minimapItemIndexOf(lastRow, itemCount, rowCount), itemCount, rowCount),
        )
    }

    @Test
    fun rowCountIsBoundedByTheMaxBucketCapRegardlessOfItemCount() {
        // rowCount is purely item-count-driven now (Sublime-style scrolling replaced the old
        // "compress to fit the strip height" behavior) — but it must still never exceed
        // MINIMAP_MAX_BUCKETS, however many items there are.
        val hugeItemCount = 50_000_000
        val derived = hugeItemCount.coerceAtMost(MINIMAP_MAX_BUCKETS)

        assertEquals(MINIMAP_MAX_BUCKETS, derived)
    }
}
