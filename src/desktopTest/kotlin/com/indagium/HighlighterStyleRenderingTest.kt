package com.indagium

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import com.indagium.model.Highlighter
import com.indagium.model.LogEntry
import com.indagium.model.LogLevel
import com.indagium.ui.FontCatalog
import com.indagium.ui.SearchHighlight
import com.indagium.ui.buildLogLineRender
import com.indagium.ui.visualLogLineForWrapLimit
import com.indagium.utils.RegexEvaluationContext
import com.indagium.utils.kloggVariedColors
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Acceptance checks against the actual annotated log text, including field gaps and wrapping. */
class HighlighterStyleRenderingTest {
    private val entry = LogEntry(1, "12:00:00.001", LogLevel.I, "Demo", "green green wrapped message", pid = 123, tid = 456)

    private fun rule() = Highlighter("style", "green", false, Color.Yellow, true)

    private fun render(
        rules: List<Highlighter>,
        suppressForeground: Boolean = false,
        search: SearchHighlight? = null,
    ) = buildLogLineRender(
        entry = entry,
        highlighters = rules,
        tsColor = Color.Gray,
        pidColor = Color.Gray,
        tagColor = Color.DarkGray,
        msgColor = Color.Black,
        keywordRegexFilter = null,
        regexContext = RegexEvaluationContext(),
        suppressLineTextColor = suppressForeground,
        searchHighlight = search,
    )

    private fun AnnotatedString.styleAt(index: Int): SpanStyle =
        spanStyles.filter { index >= it.start && index < it.end }.fold(SpanStyle()) { style, range -> style.merge(range.item) }

    @Test
    fun nativeForegroundOnlyChangesMatchesWithoutPaintingABackground() {
        val hl = rule().copy(textColor = Color.Green, backgroundEnabled = false, bold = false, italic = true)
        val line = render(listOf(hl))
        val start = line.text.text.indexOf("green")
        assertNull(line.wholeLine)
        assertEquals(Color.Green, line.text.styleAt(start).color)
        assertEquals(Color.Unspecified, line.text.styleAt(start).background)
        assertEquals(FontWeight.Normal, line.text.styleAt(start).fontWeight)
        assertEquals(FontStyle.Italic, line.text.styleAt(start).fontStyle)
        assertEquals(Color.Black, line.text.styleAt(line.text.text.indexOf("wrapped")).color)
    }

    @Test
    fun wholeLineForegroundAndTypographySurviveWrappingIncludingFieldGaps() {
        val family = FontCatalog.families.firstOrNull()
        val hl = rule().copy(
            wholeLine = true,
            textColor = Color.Green,
            backgroundEnabled = false,
            fontFamily = family,
            bold = true,
            italic = true,
        )
        val rendered = render(listOf(hl))
        assertFalse(rendered.wholeLine!!.backgroundEnabled)
        val wrapped = visualLogLineForWrapLimit(rendered.text, 18)
        assertTrue(wrapped.text.contains('\n'))
        wrapped.text.forEachIndexed { index, char ->
            if (char != '\n') {
                val style = wrapped.styleAt(index)
                assertEquals(Color.Green, style.color, "foreground at $index")
                assertEquals(FontWeight.Bold, style.fontWeight, "weight at $index")
                assertEquals(FontStyle.Italic, style.fontStyle, "italic at $index")
                assertEquals(Color.Unspecified, style.background, "background at $index")
                if (family != null) assertEquals(FontCatalog.resolveOrNull(family), style.fontFamily, "family at $index")
            }
        }
    }

    @Test
    fun unavailableRuleFamilyLeavesBaseFontInheritanceIntact() {
        val hl = rule().copy(fontFamily = "Indagium nonexistent test family", backgroundEnabled = false)
        val line = render(listOf(hl)).text
        assertNull(line.styleAt(line.text.indexOf("green")).fontFamily)
        val whole = render(listOf(hl.copy(wholeLine = true))).text
        assertTrue(whole.spanStyles.none { it.item.fontFamily != null })
    }

    @Test
    fun nativeForegroundDoesNotChangeNativePaintOrder() {
        val first = rule().copy(id = "first", textColor = Color.Green, backgroundEnabled = false)
        val last = first.copy(id = "last", textColor = Color.Blue)
        val native = render(listOf(first, last)).text
        assertEquals(Color.Blue, native.styleAt(native.text.indexOf("green")).color)
        val imported = render(listOf(first.copy(kloggStyle = true), last.copy(kloggStyle = true))).text
        assertEquals(Color.Green, imported.styleAt(imported.text.indexOf("green")).color)
    }

    @Test
    fun disablingWholeLineForegroundForSelectionRetainsOrdinaryColors() {
        val hl = rule().copy(wholeLine = true, textColor = Color.Green, backgroundEnabled = false)
        val plain = render(emptyList()).text
        val selected = render(listOf(hl), suppressForeground = true).text
        assertEquals(plain, selected)
    }

    @Test
    fun selectedAlphaIsPreservedInNativeWashAndExactForImportedColors() {
        val translucent = Color(0x80112233)
        val native = render(listOf(rule().copy(color = translucent))).text
        val nativeBackground = native.styleAt(native.text.indexOf("green")).background
        assertEquals(translucent.alpha * 0.6f, nativeBackground.alpha, 0.005f)
        val imported = render(listOf(rule().copy(color = translucent, textColor = Color.Red, kloggStyle = true))).text
        val importedStyle = imported.styleAt(imported.text.indexOf("green"))
        assertEquals(translucent, importedStyle.background)
        assertEquals(Color.Red, importedStyle.color)
        assertNull(importedStyle.fontWeight)
    }

    @Test
    fun importedForegroundVarianceWorksWithDisabledBackground() {
        val hl = rule().copy(textColor = Color.Green, kloggStyle = true, colorVariance = 25, backgroundEnabled = false)
        val line = render(listOf(hl)).text
        val expected = kloggVariedColors(Color.Green, hl.color, 25, "green").first
        val style = line.styleAt(line.text.indexOf("green"))
        assertEquals(expected, style.color)
        assertEquals(Color.Unspecified, style.background)
    }

    @Test
    fun importedBackgroundVarianceWorksWithInheritedForeground() {
        val hl = rule().copy(kloggStyle = true, colorVariance = 25, textColor = null)
        val line = render(listOf(hl)).text
        val expected = kloggVariedColors(Color.Black, hl.color, 25, "green").second
        val style = line.styleAt(line.text.indexOf("green"))
        assertEquals(expected, style.background)
        assertEquals(Color.Black, style.color)
    }

    @Test
    fun findBackgroundWinsWithoutDiscardingRuleForegroundOrItalic() {
        val hl = rule().copy(textColor = Color.Green, color = Color.Yellow, italic = true)
        val search = SearchHighlight("green", false, true, Color.Cyan, Color.Magenta)
        val line = render(listOf(hl), search = search).text
        val style = line.styleAt(line.text.indexOf("green"))
        assertEquals(Color.Magenta, style.background)
        assertEquals(Color.Green, style.color)
        assertEquals(FontStyle.Italic, style.fontStyle)
    }
}
