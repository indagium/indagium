package com.indagium

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.AnnotatedString
import com.indagium.model.Filter
import com.indagium.model.FilterMode
import com.indagium.model.Highlighter
import com.indagium.model.LogEntry
import com.indagium.model.LogLevel
import com.indagium.ui.HL_MATCH_BACKGROUND_ALPHA
import com.indagium.ui.SearchHighlight
import com.indagium.ui.VISIBLE_PID_TID_SEGMENT_CHARS
import com.indagium.ui.buildLogLineRender
import com.indagium.ui.remapHiddenPidTidRange
import com.indagium.utils.RegexEvaluationContext
import com.indagium.utils.visibleLogLineText
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** buildLogLineRender(showPidTid = false): the row drops its pid/tid text and every offset follows. */
class HiddenPidTidRenderingTest {
    private val entry = LogEntry(1, "12:00:00.001", LogLevel.I, "NetTag", "alpha beta gamma beta", pid = 4242, tid = 7373)
    private val searchBg = Color(0xFFFFFF00)
    private val currentBg = Color(0xFFFF8800)
    private val highlightColor = Color(0xFF00AA00)

    private fun render(
        e: LogEntry = entry,
        showPidTid: Boolean,
        highlighters: List<Highlighter> = emptyList(),
        keywordFilter: Filter? = null,
        search: SearchHighlight? = null,
        processDisplay: String? = null,
        pidFieldWidth: Int = 5,
    ) = buildLogLineRender(
        entry = e,
        highlighters = highlighters,
        tsColor = Color.Gray,
        pidColor = Color.Gray,
        tagColor = Color.DarkGray,
        msgColor = Color.Black,
        keywordRegexFilter = keywordFilter,
        regexContext = RegexEvaluationContext(),
        searchHighlight = search,
        processDisplay = processDisplay,
        pidFieldWidth = pidFieldWidth,
        showPidTid = showPidTid,
    )

    private fun search(query: String) = SearchHighlight(query, caseSensitive = false, isCurrentRow = false, searchBg, currentBg)

    private fun AnnotatedString.backgroundRanges(color: Color): List<String> =
        spanStyles.filter { it.item.background == color }.map { text.substring(it.start, it.end) }

    @Test
    fun hidingRemovesThePidAndTidTextAndNothingElse() {
        val shown = render(showPidTid = true).text.text
        val hidden = render(showPidTid = false).text.text
        assertTrue("4242" in shown && "7373" in shown)
        assertFalse("4242" in hidden)
        assertFalse("7373" in hidden)
        assertEquals("12:00:00.001  I  NetTag: alpha beta gamma beta", hidden)
        assertEquals(shown.length - VISIBLE_PID_TID_SEGMENT_CHARS, hidden.length)
    }

    @Test
    fun showPidTidDefaultsToTheLegacyRender() {
        val legacy = buildLogLineRender(
            entry, emptyList(), Color.Gray, Color.Gray, Color.DarkGray, Color.Black, null, RegexEvaluationContext(),
            search("beta"),
        )
        assertEquals(legacy.text, render(showPidTid = true, search = search("beta")).text)
        assertEquals(visibleLogLineText(entry), legacy.text.text)
    }

    @Test
    fun processNamesAndWidePidFieldsAreIgnoredWhenHidden() {
        val hidden = render(showPidTid = false, processDisplay = "com.example.app", pidFieldWidth = 15).text.text
        assertFalse("com.example" in hidden)
        assertEquals("12:00:00.001  I  NetTag: alpha beta gamma beta", hidden)
    }

    @Test
    fun rowsWithoutAPidAreUnchanged() {
        val raw = entry.copy(pid = 0, tid = 0)
        assertEquals(render(raw, showPidTid = true).text, render(raw, showPidTid = false, search = null).text)
        val searched = render(raw, showPidTid = false, search = search("beta")).text
        assertEquals(listOf("beta", "beta"), searched.backgroundRanges(searchBg))
    }

    @Test
    fun searchMatchesLandOnTheMessageAndTagCharacters() {
        val message = render(showPidTid = false, search = search("beta")).text
        assertEquals(listOf("beta", "beta"), message.backgroundRanges(searchBg))
        // The second "beta" is the last word, the first sits right after "alpha ".
        val ranges = message.spanStyles.filter { it.item.background == searchBg }.map { it.start }
        assertEquals(listOf(message.text.indexOf("beta"), message.text.lastIndexOf("beta")), ranges)

        val tag = render(showPidTid = false, search = search("NetTag")).text
        assertEquals(listOf("NetTag"), tag.backgroundRanges(searchBg))
        assertEquals(tag.text.indexOf("NetTag"), tag.spanStyles.first { it.item.background == searchBg }.start)
    }

    @Test
    fun theCurrentMatchUsesItsOwnBackgroundAndRegexQueriesStillWork() {
        val current = SearchHighlight("gam+a", caseSensitive = true, isCurrentRow = true, searchBg, currentBg)
        val text = render(showPidTid = false, search = current).text
        assertEquals(listOf("gamma"), text.backgroundRanges(currentBg))
    }

    @Test
    fun aSearchMatchInsideThePidTidSegmentIsDroppedNotShiftedOntoOtherText() {
        // 4242 only exists in the hidden segment of the visible line.
        val text = render(showPidTid = false, search = search("4242")).text
        assertTrue(text.backgroundRanges(searchBg).isEmpty())
        // ...and the same query highlights the pid when it is shown.
        assertEquals(listOf(" 4242"), render(showPidTid = true, search = search("4242")).text.backgroundRanges(searchBg))
    }

    @Test
    fun highlighterMatchesLandOnTheRightCharacters() {
        val hl = Highlighter("hl", "gamma", false, highlightColor, true)
        val text = render(showPidTid = false, highlighters = listOf(hl)).text
        val spans = text.spanStyles.filter { it.item.background != Color.Unspecified }
        assertTrue(spans.isNotEmpty())
        spans.forEach { assertEquals("gamma", text.text.substring(it.start, it.end)) }

        val tagHl = Highlighter("tag", "NetTag", false, highlightColor, true)
        val tagText = render(showPidTid = false, highlighters = listOf(tagHl)).text
        val tagSpans = tagText.spanStyles.filter { it.item.background != Color.Unspecified }
        assertTrue(tagSpans.isNotEmpty())
        tagSpans.forEach { assertEquals("NetTag", tagText.text.substring(it.start, it.end)) }
    }

    @Test
    fun keywordRegexHighlightsLandOnTheRightCharacters() {
        val filter = Filter(mode = FilterMode.KEYWORD, kwText = "al.ha|NetTag", kwRegex = true, kwHighlightColor = highlightColor)
        val keywordBg = highlightColor.copy(alpha = HL_MATCH_BACKGROUND_ALPHA)
        val text = render(showPidTid = false, keywordFilter = filter).text
        assertEquals(listOf("NetTag", "alpha"), text.backgroundRanges(keywordBg))
    }

    @Test
    fun aWholeLineHighlighterStillCoversTheShorterRow() {
        val hl = Highlighter("whole", "alpha", false, highlightColor, true, wholeLine = true, textColor = Color.Green)
        val rendered = render(showPidTid = false, highlighters = listOf(hl))
        assertEquals(hl.copy(), rendered.wholeLine)
        val text = rendered.text
        text.text.indices.forEach { i ->
            val style = text.spanStyles.filter { i >= it.start && i < it.end }.mapNotNull { it.item.color.takeIf { c -> c != Color.Unspecified } }
            assertTrue(style.lastOrNull() == Color.Green, "char $i is the rule colour")
        }
    }

    @Test
    fun remapHiddenPidTidRangeShiftsAfterTheSegmentAndCollapsesInsideIt() {
        val start = 12
        val end = start + VISIBLE_PID_TID_SEGMENT_CHARS
        assertEquals(2 to 8, remapHiddenPidTidRange(2 to 8, start))
        assertEquals(0 to start, remapHiddenPidTidRange(0 to start, start))
        assertEquals(start to start, remapHiddenPidTidRange(start + 2 to start + 7, start))
        assertEquals(start + 2 to start + 6, remapHiddenPidTidRange(end + 2 to end + 6, start))
        assertEquals(8 to start, remapHiddenPidTidRange(8 to start + 4, start))
        assertEquals(start to start + 3, remapHiddenPidTidRange(start + 4 to end + 3, start))
        assertEquals(4 to end - VISIBLE_PID_TID_SEGMENT_CHARS + 5, remapHiddenPidTidRange(4 to end + 5, start))
    }
}
