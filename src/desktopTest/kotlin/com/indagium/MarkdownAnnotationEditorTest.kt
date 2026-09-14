package com.indagium

import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.TextFieldValue
import com.indagium.ui.MarkdownFormatAction
import com.indagium.ui.applyMarkdownFormat
import com.indagium.ui.continueMarkdownListOnEnter
import com.indagium.ui.restoreMarkdownSelection
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
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
        // Numbered list is the one line action that does NOT repeat the same prefix on every
        // line: each selected line gets an incrementing number instead — see the dedicated tests
        // below for the continuation-from-previous-line and empty-selection cases.
        assertEquals("1. first\n2. second\nthird", applyMarkdownFormat(selected, MarkdownFormatAction.NumberedList).text)
        assertEquals("> first\n> second\nthird", applyMarkdownFormat(selected, MarkdownFormatAction.Quote).text)
    }

    // ── NumberedList: incrementing numbers instead of a repeated "1. " ──────────────────────

    @Test
    fun numberedListNumbersEachSelectedLineIncrementally() {
        val text = "first\nsecond\nthird"
        val selected = TextFieldValue(text, TextRange(0, text.length))

        val result = applyMarkdownFormat(selected, MarkdownFormatAction.NumberedList)

        assertEquals("1. first\n2. second\n3. third", result.text)
        assertEquals(TextRange(0, result.text.length), result.selection)
    }

    @Test
    fun numberedListContinuesFromANumberedPreviousLine() {
        // "4. foo" sits just above the selected lines, so the new block should start at "5. ".
        val text = "4. foo\nbar\nbaz"
        val selected = TextFieldValue(text, TextRange(text.indexOf("bar"), text.length))

        val result = applyMarkdownFormat(selected, MarkdownFormatAction.NumberedList)

        assertEquals("4. foo\n5. bar\n6. baz", result.text)
    }

    @Test
    fun numberedListOnAnEmptySelectionProducesOne() {
        val result = applyMarkdownFormat(TextFieldValue("", TextRange.Zero), MarkdownFormatAction.NumberedList)

        assertEquals("1. ", result.text)
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

    // ── continueMarkdownListOnEnter: Enter continues a list/quote item ──────────────────────

    @Test
    fun bulletContinuesOntoTheNextLine() {
        val value = TextFieldValue("- item", TextRange(6))

        val result = continueMarkdownListOnEnter(value)

        assertEquals("- item\n- ", result?.text)
        assertEquals(TextRange(9), result?.selection)
    }

    @Test
    fun orderedListIncrementsTheNumber() {
        val value = TextFieldValue("1. item", TextRange(7))

        val result = continueMarkdownListOnEnter(value)

        assertEquals("1. item\n2. ", result?.text)
        assertEquals(TextRange(11), result?.selection)
    }

    @Test
    fun orderedListWithParenStyleIncrementsTheNumber() {
        val value = TextFieldValue("1) item", TextRange(7))

        val result = continueMarkdownListOnEnter(value)

        assertEquals("1) item\n2) ", result?.text)
    }

    @Test
    fun quoteContinuesOntoTheNextLine() {
        val value = TextFieldValue("> item", TextRange(6))

        val result = continueMarkdownListOnEnter(value)

        assertEquals("> item\n> ", result?.text)
    }

    @Test
    fun taskItemContinuesUnchecked() {
        // The source item is checked; the new item Enter creates must start unchecked.
        val value = TextFieldValue("- [x] item", TextRange(10))

        val result = continueMarkdownListOnEnter(value)

        assertEquals("- [x] item\n- [ ] ", result?.text)
    }

    @Test
    fun indentationIsPreservedOnTheContinuedLine() {
        val value = TextFieldValue("  - item", TextRange(8))

        val result = continueMarkdownListOnEnter(value)

        assertEquals("  - item\n  - ", result?.text)
    }

    @Test
    fun caretMidLineSplitsTheLineAtTheCaret() {
        val text = "- hello world"
        val caret = text.indexOf(" world") // right after "hello", before the trailing " world"
        val value = TextFieldValue(text, TextRange(caret))

        val result = continueMarkdownListOnEnter(value)

        assertEquals("- hello\n-  world", result?.text)
        assertEquals(TextRange(caret + "\n- ".length), result?.selection)
    }

    @Test
    fun emptyItemEndsTheListWithoutInsertingANewline() {
        val text = "- item\n- "
        val value = TextFieldValue(text, TextRange(text.length))

        val result = continueMarkdownListOnEnter(value)

        assertEquals("- item\n", result?.text)
        assertEquals(TextRange("- item\n".length), result?.selection)
    }

    @Test
    fun aNonListLineReturnsNull() {
        val value = TextFieldValue("plain text", TextRange(10))

        assertNull(continueMarkdownListOnEnter(value))
    }

    @Test
    fun aNonCollapsedSelectionReturnsNull() {
        val value = TextFieldValue("- item", TextRange(0, 3))

        assertNull(continueMarkdownListOnEnter(value))
    }
}
