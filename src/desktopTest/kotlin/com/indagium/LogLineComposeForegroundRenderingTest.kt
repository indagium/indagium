package com.indagium

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.indagium.model.Highlighter
import com.indagium.model.LogEntry
import com.indagium.model.LogLevel
import com.indagium.ui.SELECTION_OVERLAY_ALPHA
import com.indagium.ui.SearchHighlight
import com.indagium.ui.SelectableLogLineField
import com.indagium.ui.buildLogLineRender
import com.indagium.ui.rowSelectionOverlay
import com.indagium.ui.visualLogLineForWrapLimit
import com.indagium.utils.RegexEvaluationContext
import com.indagium.utils.visibleLogLineText
import org.junit.Rule
import org.junit.Test
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Checks foreground color after Compose lays out and paints the same annotated field used by LogRow. */
class LogLineComposeForegroundRenderingTest {
    @get:Rule
    val rule = createComposeRule()

    private val entry = LogEntry(
        1,
        "09:15:00.123",
        LogLevel.D,
        "WifiManager",
        "WifiService: setWifiState -> ENABLED",
        pid = 950,
        tid = 950,
    )

    private fun fullMatchRule(wholeLine: Boolean = false) = Highlighter(
        id = "full-row-match",
        pattern = visibleLogLineText(entry),
        regex = false,
        color = Color(FULL_MATCH_ARGB),
        on = true,
        wholeLine = wholeLine,
        textColor = Color.White,
        fontFamily = "Zapfino",
    )

    private fun messageMatchRule(foreground: Color, kloggStyle: Boolean = false) = Highlighter(
        id = "message-match",
        pattern = "WifiService",
        regex = false,
        color = Color.Yellow,
        on = true,
        textColor = foreground,
        kloggStyle = kloggStyle,
    )

    private fun renderedLine(
        rules: List<Highlighter>,
        processDisplay: String? = null,
        pidFieldWidth: Int = 5,
        wrapAt: Int? = null,
        searchHighlight: SearchHighlight? = null,
    ): AnnotatedString {
        val rendered = buildLogLineRender(
            entry = entry,
            highlighters = rules,
            tsColor = Color(TIMESTAMP_ARGB),
            pidColor = Color(PID_ARGB),
            tagColor = Color(TAG_ARGB),
            msgColor = Color(MESSAGE_ARGB),
            keywordRegexFilter = null,
            regexContext = RegexEvaluationContext(),
            processDisplay = processDisplay,
            pidFieldWidth = pidFieldWidth,
            searchHighlight = searchHighlight,
        )
        return wrapAt?.let { visualLogLineForWrapLimit(rendered.text, it) } ?: rendered.text
    }

    private fun assertPaintedStyles(
        line: AnnotatedString,
        expectedForegrounds: Map<Int, Color>,
        searchBackgroundIndex: Int? = null,
        searchBackground: Color = Color.Unspecified,
    ) {
        val text = line.text
        var layout: TextLayoutResult? = null
        rule.setContent {
            Box(
                Modifier.size(CAPTURE_WIDTH_DP.dp, CAPTURE_HEIGHT_DP.dp)
                    .background(Color(CAPTURE_BACKGROUND_ARGB))
                    .testTag("rendered-log-line"),
            ) {
                BasicTextField(
                    value = TextFieldValue(annotatedString = line),
                    onValueChange = {},
                    readOnly = true,
                    textStyle = TextStyle(
                        color = Color.DarkGray,
                        fontFamily = FontFamily.Monospace,
                        fontSize = TEXT_SIZE_SP.sp,
                        lineHeight = LINE_HEIGHT_SP.sp,
                    ),
                    modifier = Modifier.fillMaxSize(),
                    onTextLayout = { layout = it },
                )
            }
        }
        rule.waitForIdle()
        val result = assertNotNull(layout)
        val pixels = rule.onNodeWithTag("rendered-log-line").captureToImage().toPixelMap()
        expectedForegrounds.forEach { (index, expected) ->
            val box = result.getBoundingBox(index)
            val left = floor(box.left).toInt().coerceIn(0, pixels.width - 1)
            val top = floor(box.top).toInt().coerceIn(0, pixels.height - 1)
            val right = ceil(box.right).toInt().coerceIn(left + 1, pixels.width)
            val bottom = ceil(box.bottom).toInt().coerceIn(top + 1, pixels.height)
            val match = (left until right).any { x ->
                (top until bottom).any { y -> isNearColor(pixels[x, y], expected) }
            }
            assertTrue(match, "expected $expected painted in glyph at offset $index ('${text[index]}')")
        }
        searchBackgroundIndex?.let { index ->
            val box = result.getBoundingBox(index)
            val left = floor(box.left).toInt().coerceIn(0, pixels.width - 1)
            val top = floor(box.top).toInt().coerceIn(0, pixels.height - 1)
            val right = ceil(box.right).toInt().coerceIn(left + 1, pixels.width)
            val bottom = ceil(box.bottom).toInt().coerceIn(top + 1, pixels.height)
            val match = (left until right).any { x ->
                (top until bottom).any { y -> isNearColor(pixels[x, y], searchBackground) }
            }
            assertTrue(match, "expected search background $searchBackground behind glyph at offset $index")
        }
    }

    private fun isNearColor(actual: Color, expected: Color): Boolean =
        abs(actual.red - expected.red) < COLOR_CHANNEL_TOLERANCE &&
            abs(actual.green - expected.green) < COLOR_CHANNEL_TOLERANCE &&
            abs(actual.blue - expected.blue) < COLOR_CHANNEL_TOLERANCE

    private fun fullLineSampleIndexes(text: String): List<Int> = listOf(
        text.indexOf('0'),
        text.indexOf("950"),
        text.indexOf("950", text.indexOf("950") + 1),
        text.indexOf(" D ") + 1,
        text.indexOf("WifiManager"),
        text.indexOf("WifiService"),
    )

    @Test
    fun fullLineNativeMatchForegroundPaintsAcrossAllLogFields() {
        val line = renderedLine(listOf(fullMatchRule()))
        assertPaintedStyles(line, fullLineSampleIndexes(line.text).associateWith { Color.White })
    }

    @Test
    fun wholeLineStylePaintsAcrossFieldsAfterProcessRemapAndVisualWrapping() {
        val line = renderedLine(
            listOf(fullMatchRule(wholeLine = true)),
            processDisplay = "wifi_process_name",
            pidFieldWidth = 18,
            wrapAt = 28,
        )
        val text = line.text
        assertTrue('\n' in text)
        val indexes = listOf(
            text.indexOf('0'),
            text.indexOf("wifi_process_name"),
            text.indexOf("950"),
            text.indexOf(" D ") + 1,
            text.indexOf("WifiManager"),
            text.indexOf("WifiService"),
        )
        assertPaintedStyles(line, indexes.associateWith { Color.White })
    }

    @Test
    fun overlappingMatchAndFindStylesKeepTheirPaintPriorityAcrossFields() {
        val nestedForeground = Color(0xFF26C6DA)
        val findBackground = Color(0xFF55CC33)
        val line = renderedLine(
            listOf(
                fullMatchRule(),
                messageMatchRule(nestedForeground),
            ),
            searchHighlight = SearchHighlight(
                query = "WifiService",
                caseSensitive = true,
                isCurrentRow = false,
                matchBg = findBackground,
                currentBg = Color.Red,
            ),
        )
        val whiteSamples = fullLineSampleIndexes(line.text).dropLast(1).associateWith { Color.White }
        val messageIndex = line.text.indexOf("WifiService")
        val overriddenForeground = (messageIndex + 1).coerceAtMost(line.text.lastIndex)
        assertPaintedStyles(
            line,
            whiteSamples + (overriddenForeground to nestedForeground),
            searchBackgroundIndex = messageIndex,
            searchBackground = findBackground,
        )
    }

    @Test
    fun laterEnclosingNativeMatchWinsOverEarlierNestedMatch() {
        val line = renderedLine(
            listOf(messageMatchRule(Color.Cyan), fullMatchRule()),
        )
        assertPaintedStyles(line, fullLineSampleIndexes(line.text).associateWith { Color.White })
    }

    @Test
    fun firstKloggMatchRetainsPrecedenceOverLaterNestedMatch() {
        val full = fullMatchRule().copy(kloggStyle = true)
        val nested = messageMatchRule(Color.Cyan, kloggStyle = true)
        val line = renderedLine(listOf(full, nested))
        assertPaintedStyles(line, fullLineSampleIndexes(line.text).associateWith { Color.White })
    }

    // Selection is painted above the text, so it must show even over an opaque (klogg) span
    // background; the stock field fills the selection first and the span background hides it.
    @Test
    fun selectionStaysVisibleOverOpaqueSpanBackground() {
        val line = renderedLine(listOf(messageMatchRule(Color.Black, kloggStyle = true)))
        val start = line.text.indexOf("WifiService")
        var layout: TextLayoutResult? = null
        var selection by mutableStateOf(TextRange.Zero)
        rule.setContent {
            Box(
                Modifier.size(CAPTURE_WIDTH_DP.dp, CAPTURE_HEIGHT_DP.dp)
                    .background(Color(CAPTURE_BACKGROUND_ARGB))
                    .testTag("rendered-log-line"),
            ) {
                SelectableLogLineField(
                    value = TextFieldValue(annotatedString = line, selection = selection),
                    onValueChange = {},
                    textStyle = TextStyle(
                        color = Color.DarkGray,
                        fontFamily = FontFamily.Monospace,
                        fontSize = TEXT_SIZE_SP.sp,
                        lineHeight = LINE_HEIGHT_SP.sp,
                    ),
                    selectionColor = Color(SELECTION_ARGB).copy(alpha = SELECTION_OVERLAY_ALPHA),
                    handleColor = Color(SELECTION_ARGB),
                    modifier = Modifier.fillMaxSize(),
                    onTextLayout = { layout = it },
                )
            }
        }
        rule.waitForIdle()
        val result = assertNotNull(layout)
        val unselected = rule.onNodeWithTag("rendered-log-line").captureToImage().toPixelMap()
        rule.runOnIdle { selection = TextRange(start, start + "WifiService".length) }
        rule.waitForIdle()
        val selected = rule.onNodeWithTag("rendered-log-line").captureToImage().toPixelMap()

        val box = result.getBoundingBox(start + 1)
        val left = ceil(box.left).toInt()
        val top = floor(box.top).toInt() + 1
        val right = floor(box.right).toInt() - 1
        val bottom = ceil(box.bottom).toInt() - 1
        val changed = (left until right).sumOf { x ->
            (top until bottom).count { y -> channelDelta(selected[x, y], unselected[x, y]) > SELECTION_VISIBLE_DELTA }
        }
        val total = (right - left) * (bottom - top)
        assertTrue(changed * 2 > total, "selection barely changed the span's pixels ($changed of $total)")

        // Outside the selected range nothing may change.
        val outside = result.getBoundingBox(0)
        val ox = floor(outside.left).toInt() + 1
        val oy = floor(outside.top).toInt() + 1
        assertTrue(channelDelta(selected[ox, oy], unselected[ox, oy]) <= SELECTION_VISIBLE_DELTA)
    }

    @Test
    fun collapsedSelectionPaintsNothingExtra() {
        val line = renderedLine(listOf(messageMatchRule(Color.Black, kloggStyle = true)))
        val caret = line.text.indexOf("WifiService")
        var selection by mutableStateOf(TextRange.Zero)
        rule.setContent {
            Box(Modifier.size(CAPTURE_WIDTH_DP.dp, CAPTURE_HEIGHT_DP.dp).testTag("rendered-log-line")) {
                SelectableLogLineField(
                    value = TextFieldValue(annotatedString = line, selection = selection),
                    onValueChange = {},
                    textStyle = TextStyle(fontFamily = FontFamily.Monospace, fontSize = TEXT_SIZE_SP.sp),
                    selectionColor = Color.Red,
                    handleColor = Color.Red,
                    modifier = Modifier.fillMaxSize(),
                )
            }
        }
        rule.waitForIdle()
        val before = rule.onNodeWithTag("rendered-log-line").captureToImage().toPixelMap()
        rule.runOnIdle { selection = TextRange(caret) }
        rule.waitForIdle()
        val after = rule.onNodeWithTag("rendered-log-line").captureToImage().toPixelMap()
        val differs = (0 until before.width).any { x ->
            (0 until before.height).any { y -> before[x, y] != after[x, y] }
        }
        assertTrue(!differs, "a collapsed selection must not paint")
    }

    @Test
    fun rowSelectionOverlayUsesStrongerTintOnlyOverARowWash() {
        val accent = Color(SELECTION_ARGB)
        val plain = accent.copy(alpha = PLAIN_SELECTION_ALPHA)
        assertNull(rowSelectionOverlay(false, true, accent, plain))
        assertEquals(plain, rowSelectionOverlay(true, false, accent, plain))
        val overWash = assertNotNull(rowSelectionOverlay(true, true, accent, plain))
        assertEquals(accent.red, overWash.red)
        assertTrue(overWash.alpha > plain.alpha, "a row with its own wash needs the stronger selection tint")
    }

    private fun channelDelta(a: Color, b: Color): Float =
        maxOf(abs(a.red - b.red), abs(a.green - b.green), abs(a.blue - b.blue))

    private companion object {
        const val FULL_MATCH_ARGB = 0xFFCF2E8B.toInt()
        const val TIMESTAMP_ARGB = 0xFF777777.toInt()
        const val PID_ARGB = 0xFF33BB55.toInt()
        const val TAG_ARGB = 0xFF4488FF.toInt()
        const val MESSAGE_ARGB = 0xFFFF8844.toInt()
        const val CAPTURE_BACKGROUND_ARGB = 0xFF202020.toInt()
        const val CAPTURE_WIDTH_DP = 900
        const val CAPTURE_HEIGHT_DP = 240
        const val TEXT_SIZE_SP = 22
        const val LINE_HEIGHT_SP = 28
        const val COLOR_CHANNEL_TOLERANCE = 0.08f
        const val SELECTION_ARGB = 0xFF388BFD.toInt()
        const val PLAIN_SELECTION_ALPHA = 0.18f
        const val SELECTION_VISIBLE_DELTA = 0.05f
    }
}
