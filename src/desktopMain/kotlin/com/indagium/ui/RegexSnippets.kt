package com.indagium.ui

import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.TextFieldValue

// The filter bar's regex-snippets menu (FilterBar.kt's ".*" button). UI-free so every template's
// output can be checked against real log lines in RegexSnippetsTest.
//
// Semantics every template is written against: the Regex filter keeps a row when the pattern is
// found ANYWHERE in the visible row text (containsMatchIn, see utils/Filter.kt), and it is already
// case-insensitive (containsPattern's ignoreCase defaults to true). That is why there is no "(?i)"
// entry, and why NOT/AND are anchored whole-row lookaheads — a bare "(?!text)" succeeds at almost
// every position of almost every row, so it excluded nothing.

internal data class RegexSnippetTemplate(
    val label: String,
    // Shown at the menu row's trailing edge.
    val preview: String,
    // Shown in the menu's footer while the row is hovered.
    val hint: String,
    val apply: (TextFieldValue) -> TextFieldValue,
)

// Most-used first: ".*" and "|" cover the bulk of real log filtering; the whole-row conditions
// and the logcat-specific presets follow.
internal val REGEX_SNIPPET_TEMPLATES = listOf(
    RegexSnippetTemplate("Any characters", ".*", "Anything in between, e.g. start.*done") { wrap(it, ".*", "", "") },
    RegexSnippetTemplate(
        "Or (alternative)",
        "a|b",
        "Adds \"|\" at the cursor: rows matching either side. Selected text becomes (text|other).",
    ) { orSnippet(it) },
    RegexSnippetTemplate("Whole word", "\\bword\\b", "Matches \"id\" but not \"android\". Wraps the selection.") { wrap(it, "\\b", "word", "\\b") },
    RegexSnippetTemplate("Exclude rows with (NOT)", "^(?!.*text)", "Hides rows containing this text. Applies to the | alternative the cursor is in.") {
        lineCondition(it, negative = true)
    },
    RegexSnippetTemplate("Number", "\\d+", "One or more digits, e.g. pid=\\d+") { wrap(it, "\\d+", "", "") },
    RegexSnippetTemplate(
        "Must also contain (AND)",
        "^(?=.*text)",
        "Row must also contain this text, anywhere. Applies to the | alternative the cursor is in.",
    ) {
        lineCondition(it, negative = false)
    },
    RegexSnippetTemplate("Literal text", "\\Q…\\E", "No special characters inside — paste class names, paths, brackets as-is.") {
        wrap(it, "\\Q", "text", "\\E")
    },
    RegexSnippetTemplate("Exception / Error", "\\w+(Exception|Error)\\b", "Any exception or error class name.") {
        wrap(it, "\\w+(Exception|Error)\\b", "", "")
    },
    RegexSnippetTemplate("Duration (ms)", DURATION_MS, "Both \"took 125ms\" and \"durationMs=568\" / \"ms: 42\".") { wrap(it, DURATION_MS, "", "") },
    RegexSnippetTemplate("Hex number", "0x[0-9a-f]+", "Addresses, flags, hash codes.") { wrap(it, "0x[0-9a-f]+", "", "") },
)

// Logcat writes durations both ways round: "took 125ms" and "durationMs=568". The \b keeps
// "5 msgs" out of the first form.
private const val DURATION_MS = "(\\d+\\s?ms\\b|ms\\s*[=:]\\s*\\d+)"

/** `before` + (selection, or [placeholder] when nothing is selected) + `after` at the caret, with
 * that middle span selected so typing replaces it. Same idea as Dialogs.kt's wrapMarkdown. */
internal fun wrap(value: TextFieldValue, before: String, placeholder: String, after: String): TextFieldValue {
    val selStart = value.selection.min
    val selEnd = value.selection.max
    val middle = if (selStart != selEnd) value.text.substring(selStart, selEnd) else placeholder
    val newText = value.text.substring(0, selStart) + before + middle + after + value.text.substring(selEnd)
    val middleStart = selStart + before.length
    return TextFieldValue(newText, TextRange(middleStart, middleStart + middle.length))
}

/**
 * - selection  → `(selection|other)`, "other" selected
 * - empty field → `(first|second)`, both selected (a lone "|" would match every row)
 * - otherwise  → a plain `|` at the caret; the user types the next alternative.
 */
internal fun orSnippet(value: TextFieldValue): TextFieldValue {
    if (value.text.isEmpty()) return wrap(value, "(", "first|second", ")")
    if (value.selection.collapsed) return wrap(value, "|", "", "")
    val selStart = value.selection.min
    val selEnd = value.selection.max
    val prefix = value.text.substring(0, selStart) + "(" + value.text.substring(selStart, selEnd) + "|"
    val placeholder = "other"
    val newText = prefix + placeholder + ")" + value.text.substring(selEnd)
    return TextFieldValue(newText, TextRange(prefix.length, prefix.length + placeholder.length))
}

/**
 * Adds a whole-row condition — `(?=.*text)` (AND) or `(?!.*text)` (NOT) — to the top-level `|`
 * alternative the caret is in, anchored at that alternative's start so it holds wherever the
 * rest of the alternative matches. Other alternatives are left alone:
 *  - empty alternative        → `^(?!.*text)`            (`a|` + caret  → `a|^(?!.*text)`)
 *  - already starts with `^`  → condition right after it  (stacks with earlier conditions)
 *  - anything else            → `^(?!.*text).*` + it      (`manager` → `^(?!.*text).*manager`)
 * The placeholder ends up selected.
 */
internal fun lineCondition(value: TextFieldValue, negative: Boolean): TextFieldValue {
    val open = if (negative) "(?!.*" else "(?=.*"
    val placeholder = "text"
    val text = value.text
    val caret = value.selection.min
    val bars = topLevelAlternationIndices(text)
    val branchStart = bars.lastOrNull { it < caret }?.plus(1) ?: 0
    val branchEnd = bars.firstOrNull { it >= caret } ?: text.length
    val branch = text.substring(branchStart, branchEnd)
    val (head, tail) = when {
        branch.isEmpty() -> "^$open" to ")"
        branch.startsWith("^") -> "^$open" to ")" + branch.substring(1)
        else -> "^$open" to ").*$branch"
    }
    val before = text.substring(0, branchStart) + head
    val newText = before + placeholder + tail + text.substring(branchEnd)
    return TextFieldValue(newText, TextRange(before.length, before.length + placeholder.length))
}

internal fun hasTopLevelAlternation(pattern: String): Boolean = topLevelAlternationIndices(pattern).isNotEmpty()

/** Indices of every `|` in [pattern] outside any group, character class or `\Q…\E` block. */
internal fun topLevelAlternationIndices(pattern: String): List<Int> {
    val out = mutableListOf<Int>()
    var depth = 0
    var inClass = false
    var i = 0
    while (i < pattern.length) {
        val c = pattern[i]
        when {
            // \Q…\E is literal text: jump past its \E (or to the end when unterminated).
            c == '\\' && pattern.getOrNull(i + 1) == 'Q' -> {
                val end = pattern.indexOf("\\E", i + 2)
                i = if (end < 0) pattern.length else end + 1
            }
            c == '\\' -> i++ // skip the escaped character
            inClass -> if (c == ']') inClass = false
            c == '[' -> inClass = true
            c == '(' -> depth++
            c == ')' -> depth = (depth - 1).coerceAtLeast(0)
            c == '|' && depth == 0 -> out += i
        }
        i++
    }
    return out
}
