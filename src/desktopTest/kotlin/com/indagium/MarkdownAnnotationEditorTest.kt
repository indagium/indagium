package com.indagium

import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.TextFieldValue
import com.indagium.ui.MarkdownFormatAction
import com.indagium.ui.applyMarkdownFormat
import com.indagium.ui.restoreMarkdownSelection
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class MarkdownAnnotationEditorTest {
    @Test
    fun inlineActionsWrapTheSelectedText() {
        val selected = TextFieldValue("crash", TextRange(0, 5))

        assertEquals("**crash**", applyMarkdownFormat(selected, MarkdownFormatAction.Bold).text)
        assertEquals("*crash*", applyMarkdownFormat(selected, MarkdownFormatAction.Italic).text)
        assertEquals("~~crash~~", applyMarkdownFormat(selected, MarkdownFormatAction.Strikethrough).text)
        assertEquals("`crash`", applyMarkdownFormat(selected, MarkdownFormatAction.InlineCode).text)
        assertEquals("```\ncrash\n```", applyMarkdownFormat(selected, MarkdownFormatAction.CodeBlock).text)
        assertEquals("[crash](url)", applyMarkdownFormat(selected, MarkdownFormatAction.Link).text)
    }

    @Test
    fun lineActionsPrefixEverySelectedLine() {
        val selected = TextFieldValue("first\nsecond\nthird", TextRange(1, 10))

        assertEquals("# first\n# second\nthird", applyMarkdownFormat(selected, MarkdownFormatAction.Heading1).text)
        assertEquals("## first\n## second\nthird", applyMarkdownFormat(selected, MarkdownFormatAction.Heading2).text)
        assertEquals("### first\n### second\nthird", applyMarkdownFormat(selected, MarkdownFormatAction.Heading3).text)
        assertEquals("- first\n- second\nthird", applyMarkdownFormat(selected, MarkdownFormatAction.BulletList).text)
        assertEquals("1. first\n1. second\nthird", applyMarkdownFormat(selected, MarkdownFormatAction.NumberedList).text)
        assertEquals("> first\n> second\nthird", applyMarkdownFormat(selected, MarkdownFormatAction.Quote).text)
    }

    @Test
    fun everyActionCreatesEditableMarkdownForAnEmptySelection() {
        MarkdownFormatAction.entries.forEach { action ->
            val result = applyMarkdownFormat(TextFieldValue("", TextRange.Zero), action)

            assertTrue(result.text.isNotBlank(), "$action should insert Markdown")
            assertTrue(result.selection.start >= 0 && result.selection.end <= result.text.length)
        }
    }

    @Test
    fun retainedSelectionIsUsedWhenToolbarClickCollapsesTheTextFieldSelection() {
        val collapsed = TextFieldValue("crash", TextRange(5))
        val restored = restoreMarkdownSelection(collapsed, TextRange(0, 5))

        assertEquals("**crash**", applyMarkdownFormat(restored, MarkdownFormatAction.Bold).text)
    }
}
