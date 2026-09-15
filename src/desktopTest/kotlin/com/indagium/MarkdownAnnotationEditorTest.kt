package com.indagium

import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.TextFieldValue
import com.indagium.model.AppSettings
import com.indagium.ui.MarkdownFormatAction
import com.indagium.ui.annotationMarkdownRenderSource
import com.indagium.ui.annotationMarkdownSoftLineBreaksEnabled
import com.indagium.ui.applyMarkdownFormat
import com.indagium.ui.continueMarkdownListOnEnter
import com.indagium.ui.restoreMarkdownSelection
import com.indagium.ui.retainedMarkdownSelectionAfterEditorUpdate
import com.indagium.ui.settingsFromJson
import com.indagium.ui.settingsJson
import org.intellij.markdown.MarkdownElementTypes
import org.intellij.markdown.ast.ASTNode
import org.intellij.markdown.flavours.commonmark.CommonMarkFlavourDescriptor
import org.intellij.markdown.parser.MarkdownParser
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
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

    @Test
    fun toolbarFocusCollapseRetainsTheNewlyFormattedSelectionForSecondClick() {
        val initial = TextFieldValue("crash", TextRange(0, 5))
        val first = applyMarkdownFormat(initial, MarkdownFormatAction.Bold)
        // This is the value BasicTextField can report when clicking the toolbar: the text is
        // unchanged but the selected range collapses at the former selection's end.
        val focusCollapse = first.copy(selection = TextRange(first.selection.end))
        val retained = retainedMarkdownSelectionAfterEditorUpdate(first, focusCollapse, null)
        val second = applyMarkdownFormat(
            restoreMarkdownSelection(focusCollapse, retained),
            MarkdownFormatAction.Bold,
        )

        assertEquals("crash", second.text)
        assertEquals(TextRange(0, 5), second.selection)
    }

    @Test
    fun inlineActionsToggleExistingFormattingOff() {
        val expected = mapOf(
            MarkdownFormatAction.Bold to "**crash**",
            MarkdownFormatAction.Italic to "*crash*",
            MarkdownFormatAction.Strikethrough to "~~crash~~",
            MarkdownFormatAction.InlineCode to "`crash`",
            MarkdownFormatAction.CodeBlock to "```\ncrash\n```",
            MarkdownFormatAction.Link to "[crash](url)",
        )
        expected.forEach { (action, formatted) ->
            val first = applyMarkdownFormat(TextFieldValue("crash", TextRange(0, 5)), action)
            val second = applyMarkdownFormat(first, action)

            assertEquals(formatted, first.text, action.name)
            assertEquals("crash", second.text, action.name)
            assertEquals(TextRange(0, 5), second.selection, action.name)
        }
    }

    @Test
    fun lineActionsToggleExistingMarkersOff() {
        val text = "first\nsecond"
        val selected = TextFieldValue(text, TextRange(0, text.length))

        val bullets = applyMarkdownFormat(selected, MarkdownFormatAction.BulletList)
        val unbulleted = applyMarkdownFormat(bullets, MarkdownFormatAction.BulletList)
        val headings = applyMarkdownFormat(selected, MarkdownFormatAction.Heading2)
        val unheaded = applyMarkdownFormat(headings, MarkdownFormatAction.Heading2)

        assertEquals("- first\n- second", bullets.text)
        assertEquals(text, unbulleted.text)
        assertEquals("## first\n## second", headings.text)
        assertEquals(text, unheaded.text)
    }

    @Test
    fun quoteAndNumberedListActionsToggleExistingMarkersOff() {
        val text = "first\nsecond"
        val selected = TextFieldValue(text, TextRange(0, text.length))

        val quotes = applyMarkdownFormat(selected, MarkdownFormatAction.Quote)
        val unquoted = applyMarkdownFormat(quotes, MarkdownFormatAction.Quote)
        val numbered = applyMarkdownFormat(selected, MarkdownFormatAction.NumberedList)
        val unnumbered = applyMarkdownFormat(numbered, MarkdownFormatAction.NumberedList)

        assertEquals("> first\n> second", quotes.text)
        assertEquals(text, unquoted.text)
        assertEquals("1. first\n2. second", numbered.text)
        assertEquals(text, unnumbered.text)
    }

    @Test
    fun mixedListSelectionNormalizesExistingMarkersWithoutNesting() {
        val bulletText = "- first\nsecond\n* third"
        val bullets = applyMarkdownFormat(
            TextFieldValue(bulletText, TextRange(0, bulletText.length)),
            MarkdownFormatAction.BulletList,
        )
        val quoteText = "> first\nsecond\n> third"
        val quotes = applyMarkdownFormat(
            TextFieldValue(quoteText, TextRange(0, quoteText.length)),
            MarkdownFormatAction.Quote,
        )
        val numberText = "1. first\nsecond\n3. third"
        val numbered = applyMarkdownFormat(
            TextFieldValue(numberText, TextRange(0, numberText.length)),
            MarkdownFormatAction.NumberedList,
        )

        assertEquals("- first\n- second\n- third", bullets.text)
        assertEquals("> first\n> second\n> third", quotes.text)
        assertEquals("1. first\n2. second\n3. third", numbered.text)
    }

    @Test
    fun inlineMarkdownSettingDefaultsOnAndRoundTripsThroughKeyedSettings() {
        assertTrue(AppSettings().renderAnnotationMarkdownInline)
        assertFalse(settingsFromJson(AppSettings(renderAnnotationMarkdownInline = false).settingsJson())!!
            .renderAnnotationMarkdownInline)
        assertTrue(settingsFromJson("{}")!!.renderAnnotationMarkdownInline)
    }

    @Test
    fun annotationRendererKeepsSoftLineBreaksVisible() {
        assertTrue(annotationMarkdownSoftLineBreaksEnabled())
    }

    @Test
    fun standaloneClosingBoldDelimiterRendersAsOneMultilineStrongSpan() {
        val source = "**first\nsecond\nthird\n**"
        val renderSource = annotationMarkdownRenderSource(source)

        assertEquals("**first\nsecond\nthird**", renderSource)
        val tree = MarkdownParser(CommonMarkFlavourDescriptor()).buildMarkdownTreeFromString(renderSource)
        val strong = assertNotNull(findNode(tree, MarkdownElementTypes.STRONG), "the normalized source must parse as a strong span")
        assertEquals("first\nsecond\nthird", renderSource.substring(strong.startOffset + 2, strong.endOffset - 2))
    }

    @Test
    fun standaloneDelimitersInsideCodeFencesRemainLiteral() {
        val source = "```\n**\nnot bold\n**\n```"

        assertEquals(source, annotationMarkdownRenderSource(source))
    }

    @Test
    fun fencedCodeBreaksRenderOnlyDelimiterTrackingWithoutTouchingFenceLines() {
        val source = "**before\n```\n**\n```\nafter\n**"

        assertEquals(source, annotationMarkdownRenderSource(source))
    }

    private fun findNode(node: ASTNode, type: org.intellij.markdown.IElementType): ASTNode? {
        if (node.type == type) return node
        return node.children.asSequence().mapNotNull { findNode(it, type) }.firstOrNull()
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
