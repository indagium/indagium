package com.indagium

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.sp
import com.indagium.model.LogDisplayField
import com.indagium.model.LogEntry
import com.indagium.model.LogLevel
import com.indagium.model.LogRecordMetadata
import com.indagium.ui.HangingPlan
import com.indagium.ui.MIN_HANGING_INDENT_CHARS
import com.indagium.ui.MIN_HANGING_ROOM_CHARS
import com.indagium.ui.buildLogLineRender
import com.indagium.ui.chooseHangingIndentChars
import com.indagium.ui.compactRecordSummary
import com.indagium.ui.fieldCharsFor
import com.indagium.ui.hangingContinuationLimit
import com.indagium.ui.hangingIndentLeavesRoom
import com.indagium.ui.hangingIndentSp
import com.indagium.ui.messageBreaksOntoOwnLine
import com.indagium.ui.profileHeaderIndentChars
import com.indagium.ui.stripVisualWrapBreaks
import com.indagium.ui.visualLogLineForWrapLimit
import com.indagium.ui.withBreakBefore
import com.indagium.ui.withHangingIndent
import com.indagium.ui.withHangingPlan
import com.indagium.utils.RegexEvaluationContext
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** The pure half of a profile row's hanging indent: where the message column starts and how it wraps. */
class ProfileRowHangingIndentTest {
    private val columns = listOf(
        LogDisplayField("timestamp", "timestamp", "10:00:00"),
        LogDisplayField("component", "tag", "api"),
        LogDisplayField("message", "message", "the message of the row"),
    )
    private val widths = mapOf("timestamp" to 10, "component" to 6)

    private fun profileRow(
        message: String = "the message of the row",
        cols: List<LogDisplayField> = columns.map { if (it.id == "message") it.copy(value = message) else it },
    ) = LogEntry(
        id = 1, ts = "10:00:00", level = LogLevel.I, tag = "api", msg = message,
        recordMetadata = LogRecordMetadata(
            profileFields = emptyMap(),
            displayColumns = cols,
            rawRecord = message,
            usesProfilePresentation = true,
        ),
    )

    private fun render(entry: LogEntry, columnWidths: Map<String, Int>? = widths, showPidTid: Boolean = true) = buildLogLineRender(
        entry = entry,
        highlighters = emptyList(),
        tsColor = Color.Gray,
        pidColor = Color.Gray,
        tagColor = Color.DarkGray,
        msgColor = Color.Black,
        keywordRegexFilter = null,
        regexContext = RegexEvaluationContext(),
        profileColumnWidths = columnWidths,
        showPidTid = showPidTid,
    )

    @Test
    fun theIndentIsWhereTheMessageColumnStartsInTheRenderedRow() {
        val render = render(profileRow())
        // "10:00:00" padded to 10, two separator characters, "api" padded to 6, two separators.
        assertEquals("10:00:00    api     the message of the row", render.text.text)
        assertEquals(render.text.text.indexOf("the message"), render.messageIndentChars)
        assertEquals(10 + 2 + 6 + 2, render.messageIndentChars)
    }

    @Test
    fun theIndentFollowsTheWidthsTheRowIsPaddedTo() {
        val narrow = render(profileRow(), mapOf("timestamp" to 8, "component" to 3))
        val unpadded = render(profileRow(), null)
        assertEquals(8 + 2 + 3 + 2, narrow.messageIndentChars)
        assertEquals("10:00:00".length + 2 + "api".length + 2, unpadded.messageIndentChars)
    }

    @Test
    fun aRowWithoutAMessageAndALogcatRowHaveNoIndent() {
        val withoutMessage = profileRow(cols = columns.dropLast(1))
        assertEquals(0, render(withoutMessage).messageIndentChars)
        val logcat = LogEntry(1, "10:00:00.000", LogLevel.I, "App", "a logcat message")
        assertEquals(0, render(logcat, null).messageIndentChars)
    }

    @Test
    fun continuationLimitKeepsAFloorSoALongIndentNeverLeavesAFewCharacterLine() {
        assertEquals(100, hangingContinuationLimit(100, 0))
        assertEquals(70, hangingContinuationLimit(100, 30))
        assertEquals(20, hangingContinuationLimit(100, 95))
        assertEquals(20, hangingContinuationLimit(100, 400))
        assertEquals(10, hangingContinuationLimit(10, 8), "a tiny limit never grows")
    }

    @Test
    fun wrapLimitContinuationLinesAreShorterByTheIndentAndTheCopiedTextIsUnchanged() {
        val message = (1..40).joinToString(" ") { "word$it" }
        val render = render(profileRow(message))
        val limit = 80
        val indent = render.messageIndentChars
        val wrapped = visualLogLineForWrapLimit(render.text, limit, indent)
        val lines = wrapped.text.split('\n')
        assertTrue(lines.size > 2, "the message needs several lines")
        assertTrue(lines.first().length <= limit)
        lines.drop(1).forEach { assertTrue(it.length <= limit - indent, "continuation '$it' fits the indented width") }
        assertEquals(render.text.text, stripVisualWrapBreaks(wrapped.text))
        // Without an indent it is the old wrap, every line the full limit.
        assertEquals(visualLogLineForWrapLimit(render.text, limit), visualLogLineForWrapLimit(render.text, limit, 0))
        assertTrue(visualLogLineForWrapLimit(render.text, limit).text.split('\n').drop(1).any { it.length > limit - indent })
    }

    @Test
    fun wrapLimitKeepsSpanStylesOnTheirCharacters() {
        val message = (1..40).joinToString(" ") { "word$it" }
        val render = render(profileRow(message))
        val wrapped = visualLogLineForWrapLimit(render.text, 80, render.messageIndentChars)
        val original = render.text
        val originalColors = original.text.indices.map { colorAt(original, it) }
        val wrappedPlain = stripVisualWrapBreaks(wrapped.text)
        val wrappedColors = wrapped.text.indices.filter { wrapped.text[it] != '\n' }.map { colorAt(wrapped, it) }
        assertEquals(original.text, wrappedPlain)
        assertEquals(originalColors, wrappedColors)
    }

    @Test
    fun hangingIndentIsOneNonOverlappingParagraphOverTheWholeRowWithoutChangingItsText() {
        val line = buildAnnotatedString {
            append("10:00:00    api     first\nsecond line")
            addStyle(SpanStyle(color = Color.Red), 0, 8)
        }
        val indent = 120.sp
        val indented = line.withHangingIndent(indent)
        assertEquals(line.text, indented.text)
        assertEquals(line.spanStyles, indented.spanStyles)
        val paragraph = indented.paragraphStyles.single()
        assertEquals(0, paragraph.start)
        assertEquals(line.length, paragraph.end)
        assertEquals(0.sp, paragraph.item.textIndent!!.firstLine)
        assertEquals(indent, paragraph.item.textIndent!!.restLine)
        assertParagraphsDoNotOverlap(indented)
    }

    @Test
    fun noIndentLeavesTheTextExactlyAsItWas() {
        val line = AnnotatedString("plain")
        assertTrue(line === line.withHangingIndent(0.sp))
        val empty = AnnotatedString("")
        assertTrue(empty === empty.withHangingIndent(10.sp))
        assertFalse(line.withHangingIndent(10.sp).paragraphStyles.isEmpty())
    }

    @Test
    fun theIndentSurvivesTheCompactSummaryOfAnExpandableRecord() {
        val long = "x".repeat(300)
        val indented = AnnotatedString(long).withHangingIndent(50.sp)
        val compact = compactRecordSummary(indented)
        assertTrue(compact.text.endsWith(" …"))
        val paragraph = compact.paragraphStyles.single()
        assertEquals(0, paragraph.start)
        assertEquals(compact.length, paragraph.end, "the ellipsis stays inside the one paragraph")
        assertEquals(50.sp, paragraph.item.textIndent!!.restLine)
        // A short row passes through as it was.
        val short = AnnotatedString("short").withHangingIndent(50.sp)
        assertTrue(compactRecordSummary(short) === short)
        // A record of several lines shows its first line and how many follow; the paragraph still covers every character.
        val twoLines = AnnotatedString("one\ntwo").withHangingIndent(50.sp)
        val flattened = compactRecordSummary(twoLines)
        assertEquals("one (+1 line)", flattened.text)
        assertEquals(flattened.length, flattened.paragraphStyles.single().end)
        val trace = compactRecordSummary(AnnotatedString("ERROR boom\n  at a.b(C.kt:1)\n  at c.d(E.kt:2)\n"))
        assertEquals("ERROR boom (+2 lines)", trace.text)
    }

    @Test
    fun theIndentIsTheMeasuredAdvanceTimesTheCharactersInScaledSp() {
        val plain = Density(density = 2f, fontScale = 1f)
        assertEquals(60f, hangingIndentSp(10, advancePx = 12f, density = plain).value, 0.001f)
        // 10 chars of 12 px at 2x density and 1.5x font scale is 120 px = 40 sp.
        val scaled = Density(density = 2f, fontScale = 1.5f)
        assertEquals(40f, hangingIndentSp(10, advancePx = 12f, density = scaled).value, 0.001f)
    }

    @Test
    fun theFieldIsMeasuredInCharactersAndAnUnmeasuredOneIsZero() {
        assertEquals(96f, fieldCharsFor(fieldWidthPx = 960, advancePx = 10f))
        assertEquals(0f, fieldCharsFor(fieldWidthPx = 0, advancePx = 10f))
        assertEquals(0f, fieldCharsFor(fieldWidthPx = 960, advancePx = 0f))
    }

    @Test
    fun anIndentFitsWhenThirtyCharactersRemainAfterIt() {
        assertEquals(30, MIN_HANGING_ROOM_CHARS)
        assertTrue(hangingIndentLeavesRoom(66, 96f))
        assertFalse(hangingIndentLeavesRoom(67, 96f))
        assertTrue(hangingIndentLeavesRoom(500, 0f), "an unmeasured field counts as wide enough")
        assertFalse(hangingIndentLeavesRoom(30, 59f))
    }

    private fun assertParagraphsDoNotOverlap(text: AnnotatedString) {
        val ranges = text.paragraphStyles.sortedBy { it.start }
        ranges.zipWithNext().forEach { (a, b) -> assertTrue(a.end <= b.start, "paragraph ranges $a and $b overlap") }
    }

    private fun colorAt(text: AnnotatedString, offset: Int): Color =
        text.spanStyles.filter { offset >= it.start && offset < it.end }
            .fold(SpanStyle()) { style, range -> style.merge(range.item) }
            .color

    @Test
    fun theFallbackIndentIsWhereTheSecondColumnStarts() {
        val render = render(profileRow())
        assertEquals(10 + 2, render.fallbackIndentChars)
        assertEquals(render.text.text.indexOf("api"), render.fallbackIndentChars)
        // A row without a message (an access log) still gets one.
        val withoutMessage = profileRow(cols = columns.filter { it.id != "message" })
        assertEquals(0, render(withoutMessage).messageIndentChars)
        assertEquals(10 + 2, render(withoutMessage).fallbackIndentChars)
    }

    @Test
    fun wrappedLinesHangAtTheMessageThenAfterTheFirstColumnThenNowhere() {
        // The message column leaves room: wrap under it.
        assertEquals(40, chooseHangingIndentChars(40, 12, 100f))
        // The message starts too far right (many columns): wrap just after the first column, never under it.
        assertEquals(12, chooseHangingIndentChars(80, 12, 100f))
        // No message at all: the same fallback.
        assertEquals(12, chooseHangingIndentChars(0, 12, 100f))
        // A pane too narrow even for that: a short fixed step, so a continuation line still differs from a new row.
        assertEquals(MIN_HANGING_INDENT_CHARS, chooseHangingIndentChars(80, 30, 50f))
        // Narrower still (the step would be over half the field), or a row with no columns to hang from: no indent.
        assertEquals(0, chooseHangingIndentChars(80, 30, 7f))
        assertEquals(0, chooseHangingIndentChars(0, 0, 100f))
        // A field not measured yet takes the message column.
        assertEquals(80, chooseHangingIndentChars(80, 12, 0f))
    }

    @Test
    fun aSqueezedMessageHangsAtTheSecondColumnAndStartsOnItsOwnLine() {
        // A custom CSV in a 96-character field: the message starts at 83, 13 characters remain, the second column starts at 26.
        assertEquals(26, chooseHangingIndentChars(83, 26, 96f))
        assertTrue(messageBreaksOntoOwnLine(messageChars = 83, firstLineChars = 103, fieldChars = 96f))
    }

    @Test
    fun aMessageWithRoomLeftIsNotMovedEvenPastHalfTheField() {
        // Multi-line regex rows: the message at 56 of 96 characters (59%) leaves 40, so it keeps its own column and does not break.
        assertEquals(56, chooseHangingIndentChars(56, 12, 96f))
        assertFalse(messageBreaksOntoOwnLine(messageChars = 56, firstLineChars = 150, fieldChars = 96f))
    }

    @Test
    fun aNarrowFieldFallsBackToTheShortStep() {
        assertEquals(MIN_HANGING_INDENT_CHARS, chooseHangingIndentChars(83, 26, 51f))
        assertTrue(messageBreaksOntoOwnLine(messageChars = 83, firstLineChars = 100, fieldChars = 51f))
    }

    @Test
    fun anUnmeasuredFieldTakesTheMessageColumnAndNeverBreaks() {
        assertEquals(83, chooseHangingIndentChars(83, 26, 0f))
        assertFalse(messageBreaksOntoOwnLine(messageChars = 83, firstLineChars = 200, fieldChars = 0f))
    }

    @Test
    fun aRowThatFitsTheFieldDoesNotBreak() {
        assertFalse(messageBreaksOntoOwnLine(messageChars = 83, firstLineChars = 96, fieldChars = 96f))
        assertFalse(messageBreaksOntoOwnLine(messageChars = 83, firstLineChars = 90, fieldChars = 96.4f))
        assertFalse(messageBreaksOntoOwnLine(messageChars = 0, firstLineChars = 200, fieldChars = 96f), "no message column")
    }

    @Test
    fun theBreakIsInsertedBeforeTheMessageAndCopyingGivesTheOriginalText() {
        val render = render(profileRow("a message that is long enough to need a second line"))
        val original = render.text
        val broken = original.withBreakBefore(render.messageIndentChars)
        assertEquals(original.length + 1, broken.length)
        assertEquals('\n', broken.text[render.messageIndentChars])
        assertEquals(original.text, stripVisualWrapBreaks(broken.text))
        // The first line ends with the separator that was there; the message starts the second line.
        assertTrue(broken.text.split('\n')[1].startsWith("a message"))
    }

    @Test
    fun theBreakKeepsEverySpanOnItsCharacters() {
        val render = render(profileRow("a message that is long enough to need a second line"))
        val original = buildAnnotatedString {
            append(render.text)
            // A span across the break point, and one in the message.
            addStyle(SpanStyle(color = Color.Red), render.messageIndentChars - 4, render.messageIndentChars + 4)
            addStyle(SpanStyle(color = Color.Blue), render.messageIndentChars + 10, render.messageIndentChars + 15)
        }
        val broken = original.withBreakBefore(render.messageIndentChars)
        val originalColors = original.text.indices.map { colorAt(original, it) }
        val brokenColors = broken.text.indices.filter { broken.text[it] != '\n' }.map { colorAt(broken, it) }
        assertEquals(originalColors, brokenColors)
        assertEquals(original.text, stripVisualWrapBreaks(broken.text))
    }

    @Test
    fun noBreakIsInsertedOutsideTheText() {
        val line = AnnotatedString("short row")
        assertTrue(line === line.withBreakBefore(0))
        assertTrue(line === line.withBreakBefore(-1))
        assertTrue(line === line.withBreakBefore(line.length))
        assertTrue(line === line.withBreakBefore(99))
    }

    @Test
    fun aPlanAppliesTheBreakBeforeTheIndentAndKeepsTheOneParagraphOverEverything() {
        val line = AnnotatedString("first column  second column  the message")
        val offset = line.text.indexOf("the message")
        val withBreak = line.withHangingPlan(HangingPlan(40.sp, messageOnOwnLine = true), offset)
        assertEquals(line.text.substring(0, offset) + "\n" + "the message", withBreak.text)
        val paragraph = withBreak.paragraphStyles.single()
        assertEquals(0, paragraph.start)
        assertEquals(withBreak.length, paragraph.end)
        assertEquals(40.sp, paragraph.item.textIndent!!.restLine)
        // Without a break or an indent the text is as it was.
        assertTrue(line === line.withHangingPlan(HangingPlan(null, messageOnOwnLine = false), offset))
        assertEquals(line.text, line.withHangingPlan(HangingPlan(40.sp, messageOnOwnLine = false), offset).text)
    }

    @Test
    fun theHeaderHangsAtItsMessageLabelOrItsSecondLabel() {
        assertEquals(20 to 11, profileHeaderIndentChars("TIMESTAMP  LVL      MESSAGE"))
        assertEquals(0 to 22, profileHeaderIndentChars("16/Nov/2026:12:00:00  HOST  HTTP METHOD"))
    }
}
