package com.indagium

import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.TextFieldValue
import com.indagium.ui.REGEX_SNIPPET_TEMPLATES
import com.indagium.ui.hasTopLevelAlternation
import com.indagium.ui.lineCondition
import com.indagium.ui.orSnippet
import com.indagium.ui.wrap
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** The filter bar's regex-snippet menu: each template must produce a pattern that filters rows the
 * way its label promises under the Regex filter's own semantics (containsMatchIn, ignore case). */
class RegexSnippetsTest {
    private val rows = listOf(
        "09-18 10:00:01.123  1234  1300 E AndroidRuntime: java.lang.IllegalStateException: crash in onCreate",
        "09-18 10:00:02.456  1234  1301 W ActivityManager: Timeout waiting for window, took 125ms",
        "09-18 10:00:03.789  1234  1302 I WindowManager: relayout done, id=42 flags=0x1f",
        "09-18 10:00:04.000  1234  1303 D Choreographer: skipped 30 frames",
    )

    private fun matching(pattern: String) = rows.filter { Regex(pattern, RegexOption.IGNORE_CASE).containsMatchIn(it) }

    private fun type(value: TextFieldValue, text: String): TextFieldValue {
        val s = value.selection
        val out = value.text.substring(0, s.min) + text + value.text.substring(s.max)
        return TextFieldValue(out, TextRange(s.min + text.length))
    }

    private fun template(labelPrefix: String) = REGEX_SNIPPET_TEMPLATES.first { it.label.startsWith(labelPrefix) }

    @Test
    fun excludeOnEmptyFieldHidesOnlyRowsWithTheWord() {
        val v = type(lineCondition(TextFieldValue(""), negative = true), "timeout")
        assertEquals("^(?!.*timeout)", v.text)
        assertEquals(3, matching(v.text).size)
        assertTrue(matching(v.text).none { "Timeout" in it })
    }

    @Test
    fun excludeCombinesWithAnExistingPattern() {
        val v = type(lineCondition(TextFieldValue("manager", TextRange(7)), negative = true), "window")
        assertEquals("^(?!.*window).*manager", v.text)
        assertEquals(emptyList(), matching(v.text)) // both *Manager rows mention "window"
        val w = type(lineCondition(TextFieldValue("manager", TextRange(7)), negative = true), "relayout")
        assertEquals(listOf(rows[1]), matching(w.text))
    }

    @Test
    fun conditionAppliesOnlyToTheAlternativeAtTheCaretAndStacks() {
        // Caret at the end → last alternative only.
        val v = type(lineCondition(TextFieldValue("crash|frames", TextRange(12)), negative = true), "choreographer")
        assertEquals("crash|^(?!.*choreographer).*frames", v.text)
        assertEquals(listOf(rows[0]), matching(v.text))
        // Caret inside the first alternative → first one only.
        val first = type(lineCondition(TextFieldValue("crash|frames", TextRange(2)), negative = false), "onCreate")
        assertEquals("^(?=.*onCreate).*crash|frames", first.text)

        val stacked = type(lineCondition(TextFieldValue("^(?!.*a).*b", TextRange(11)), negative = true), "c")
        assertEquals("^(?!.*c)(?!.*a).*b", stacked.text)
    }

    @Test
    fun conditionAfterABarStartsANewAlternative() {
        // AND "i", then OR, then AND again — used to wrap everything into ^(?=.*text).*(?:^(?=.*i)|).
        var v = type(lineCondition(TextFieldValue(""), negative = false), "window")
        v = orSnippet(v.copy(selection = TextRange(v.text.length))) // End key, past the ")"
        assertEquals("^(?=.*window)|", v.text)
        v = type(lineCondition(v, negative = false), "crash")
        assertEquals("^(?=.*window)|^(?=.*crash)", v.text)
        assertEquals(listOf(rows[0], rows[1], rows[2]), matching(v.text))
    }

    @Test
    fun andRequiresBothWordsInAnyOrder() {
        val v = type(lineCondition(TextFieldValue("took", TextRange(4)), negative = false), "timeout")
        assertEquals("^(?=.*timeout).*took", v.text)
        assertEquals(listOf(rows[1]), matching(v.text))
    }

    @Test
    fun conditionSelectsItsPlaceholder() {
        val v = lineCondition(TextFieldValue("abc", TextRange(1)), negative = true)
        assertEquals("text", v.text.substring(v.selection.min, v.selection.max))
    }

    @Test
    fun orInsertsABarAtTheCaretButNeverAloneInAnEmptyField() {
        val typed = type(orSnippet(TextFieldValue("crash", TextRange(5))), "skipped")
        assertEquals("crash|skipped", typed.text)

        val empty = orSnippet(TextFieldValue(""))
        assertEquals("(first|second)", empty.text)
        assertEquals("first|second", empty.text.substring(empty.selection.min, empty.selection.max))

        val sel = type(orSnippet(TextFieldValue("crash", TextRange(0, 5))), "skipped")
        assertEquals("(crash|skipped)", sel.text)
        assertEquals(listOf(rows[0], rows[3]), matching(sel.text))
    }

    @Test
    fun wholeWordAndLiteralWrapTheSelection() {
        val word = wrap(TextFieldValue("id", TextRange(0, 2)), "\\b", "word", "\\b")
        assertEquals("\\bid\\b", word.text)
        assertEquals(listOf(rows[2]), matching(word.text)) // not "AndroidRuntime"

        val literal = template("Literal").apply(TextFieldValue("java.lang.IllegalStateException:", TextRange(0, 32)))
        assertEquals(listOf(rows[0]), matching(literal.text))
        assertFalse(hasTopLevelAlternation("\\Qa|b\\E"))
    }

    @Test
    fun presetPatternsMatchTypicalLogcatValues() {
        assertEquals(listOf(rows[2]), matching(template("Hex").apply(TextFieldValue("")).text))
        assertEquals(listOf(rows[0]), matching(template("Exception").apply(TextFieldValue("")).text))
        val duration = template("Duration").apply(TextFieldValue("")).text
        assertEquals(listOf(rows[1]), matching(duration))
        val durationRows = listOf("USB poll cycle exceeded budget: durationMs=568", "latency ms: 42", "Sent 3 msgs", "took 7 ms")
        assertEquals(listOf(durationRows[0], durationRows[1], durationRows[3]), durationRows.filter {
            Regex(duration, RegexOption.IGNORE_CASE).containsMatchIn(it)
        })
        val skipped = type(template("Number").apply(TextFieldValue("skipped ", TextRange(8))), " frames")
        assertEquals("skipped \\d+ frames", skipped.text)
        assertEquals(listOf(rows[3]), matching(skipped.text))
    }

    @Test
    fun everyTemplateProducesAValidPattern() {
        REGEX_SNIPPET_TEMPLATES.forEach { t ->
            listOf(TextFieldValue(""), TextFieldValue("foo|bar"), TextFieldValue("^x", TextRange(1))).forEach { start ->
                Regex(t.apply(start).text) // throws on an invalid pattern
            }
        }
    }
}
