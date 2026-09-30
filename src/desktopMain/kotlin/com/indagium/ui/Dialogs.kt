@file:OptIn(androidx.compose.ui.ExperimentalComposeUiApi::class)

package com.indagium.ui

import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.isAltPressed
import androidx.compose.ui.input.key.isCtrlPressed
import androidx.compose.ui.input.key.isMetaPressed
import androidx.compose.ui.input.key.isShiftPressed
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.pointer.*
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.DialogProperties
import androidx.compose.ui.window.PopupProperties
import androidx.compose.ui.zIndex
import com.indagium.ai.CustomAiCommand
import com.indagium.model.*
import java.io.File
import kotlin.math.roundToInt

internal enum class MarkdownFormatAction {
    Bold,
    Italic,
    Strikethrough,
    Heading1,
    Heading2,
    Heading3,
    BulletList,
    NumberedList,
    Quote,
    InlineCode,
    CodeBlock,
    Link,
}

private data class MarkdownCaretBounds(val offset: Int, val top: Float, val bottom: Float)

/**
 * Applies a Markdown formatting action without losing the editor's selection. Inline actions wrap
 * the selected text (or select a useful placeholder), while line actions affect the current line
 * or every selected line. Keeping this pure makes the editor behaviour independently testable.
 */
internal fun applyMarkdownFormat(value: TextFieldValue, action: MarkdownFormatAction): TextFieldValue = when (action) {
    MarkdownFormatAction.Bold -> wrapMarkdown(value, "**", "**", "bold text")
    MarkdownFormatAction.Italic -> wrapMarkdown(value, "*", "*", "italic text")
    MarkdownFormatAction.Strikethrough -> wrapMarkdown(value, "~~", "~~", "struck text")
    MarkdownFormatAction.InlineCode -> wrapMarkdown(value, "`", "`", "code")
    MarkdownFormatAction.CodeBlock -> wrapMarkdown(value, "```\n", "\n```", "code")
    MarkdownFormatAction.Link -> wrapMarkdown(value, "[", "](url)", "link text")
    MarkdownFormatAction.Heading1 -> prefixMarkdownLines(value, "# ")
    MarkdownFormatAction.Heading2 -> prefixMarkdownLines(value, "## ")
    MarkdownFormatAction.Heading3 -> prefixMarkdownLines(value, "### ")
    MarkdownFormatAction.BulletList -> prefixMarkdownLines(value, "- ")
    MarkdownFormatAction.NumberedList -> numberMarkdownLines(value)
    MarkdownFormatAction.Quote -> prefixMarkdownLines(value, "> ")
}

/** Restores a just-lost text-field selection before a toolbar action consumes it. */
internal fun restoreMarkdownSelection(value: TextFieldValue, retainedSelection: TextRange?): TextFieldValue =
    if (
        value.selection.start == value.selection.end &&
        retainedSelection != null &&
        retainedSelection.start >= 0 &&
        retainedSelection.end <= value.text.length
    ) {
        value.copy(selection = retainedSelection)
    } else {
        value
    }

/**
 * Tracks the selection that the editor had immediately before a toolbar click moved focus away
 * from it. Compose can report that focus transition as a new [TextFieldValue] with a collapsed
 * caret, even though the user never collapsed the selection. Keep the previous range in that
 * specific same-text case so a second toolbar click still toggles the text just formatted.
 */
internal fun retainedMarkdownSelectionAfterEditorUpdate(
    previous: TextFieldValue,
    updated: TextFieldValue,
    retainedSelection: TextRange?,
): TextRange? = when {
    updated.selection.start != updated.selection.end -> updated.selection
    updated.text != previous.text -> null
    retainedSelection != null -> retainedSelection
    previous.selection.start != previous.selection.end -> previous.selection
    else -> null
}

private fun wrapMarkdown(value: TextFieldValue, prefix: String, suffix: String, placeholder: String): TextFieldValue {
    val start = minOf(value.selection.start, value.selection.end)
    val end = maxOf(value.selection.start, value.selection.end)

    // Clicking a toolbar button a second time should undo the same formatting. The common case
    // is the selection left inside the markers by the first click (e.g. `**|text|**`), but also
    // handle a user selecting the complete formatted fragment (`|**text**|`) so the action feels
    // like a real toggle whichever selection gesture was used.
    val hasSurroundingMarkers = start >= prefix.length && end + suffix.length <= value.text.length &&
        value.text.startsWith(prefix, start - prefix.length) && value.text.startsWith(suffix, end)
    if (hasSurroundingMarkers) {
        val text = value.text.removeRange(end, end + suffix.length)
            .removeRange(start - prefix.length, start)
        val unwrappedStart = start - prefix.length
        return TextFieldValue(text, TextRange(unwrappedStart, unwrappedStart + (end - start)))
    }

    val selectedText = value.text.substring(start, end)
    if (selectedText.length >= prefix.length + suffix.length &&
        selectedText.startsWith(prefix) && selectedText.endsWith(suffix)
    ) {
        val inner = selectedText.substring(prefix.length, selectedText.length - suffix.length)
        val text = value.text.replaceRange(start, end, inner)
        return TextFieldValue(text, TextRange(start, start + inner.length))
    }

    val content = selectedText.ifEmpty { placeholder }
    val replacement = "$prefix$content$suffix"
    val text = value.text.replaceRange(start, end, replacement)
    val selectedStart = start + prefix.length
    return TextFieldValue(text, TextRange(selectedStart, selectedStart + content.length))
}

@Suppress("CyclomaticComplexMethod")
private fun prefixMarkdownLines(value: TextFieldValue, prefix: String): TextFieldValue {
    val start = minOf(value.selection.start, value.selection.end)
    val end = maxOf(value.selection.start, value.selection.end)
    val lineStart = value.text.lastIndexOf('\n', start - 1).let { it + 1 }
    val effectiveEnd = if (end > start && end <= value.text.length && value.text[end - 1] == '\n') end - 1 else end
    val lineEnd = value.text.indexOf('\n', effectiveEnd).takeIf { it >= 0 } ?: value.text.length
    val lines = value.text.substring(lineStart, lineEnd).split('\n')
    val replacement = when {
        // Heading actions set the requested level when switching levels, and toggle that level
        // off when it is already active. This avoids producing invalid `# ## title` chains.
        prefix.startsWith("#") -> {
            val heading = Regex("^(\\s*)#{1,6}\\s")
            if (lines.all { it.startsWith(prefix) }) {
                lines.joinToString("\n") { it.removePrefix(prefix) }
            } else {
                lines.joinToString("\n") { line ->
                    val match = heading.find(line)
                    val indent = match?.groupValues?.get(1).orEmpty()
                    val content = match?.let { line.removeRange(it.range) } ?: line
                    "$indent$prefix$content"
                }
            }
        }
        // Bullets and quotes also toggle off when every selected line already has a marker. Any
        // other selected line is prefixed, preserving the existing behaviour for mixed blocks.
        prefix == "- " -> {
            val bullet = Regex("^(\\s*)[-*+]\\s")
            if (lines.all { bullet.containsMatchIn(it) }) {
                lines.joinToString("\n") { line ->
                    bullet.find(line)?.let { line.removeRange(it.range) } ?: line
                }
            } else {
                lines.joinToString("\n") { line ->
                    val match = bullet.find(line)
                    val indent = match?.groupValues?.get(1).orEmpty()
                    val content = match?.let { line.removeRange(it.range) } ?: line
                    "$indent$prefix$content"
                }
            }
        }
        prefix == "> " -> {
            val quote = Regex("^(\\s*)>\\s")
            if (lines.all { quote.containsMatchIn(it) }) {
                lines.joinToString("\n") { line -> quote.find(line)?.let { line.removeRange(it.range) } ?: line }
            } else {
                lines.joinToString("\n") { line ->
                    val match = quote.find(line)
                    val indent = match?.groupValues?.get(1).orEmpty()
                    val content = match?.let { line.removeRange(it.range) } ?: line
                    "$indent$prefix$content"
                }
            }
        }
        else -> lines.joinToString("\n") { "$prefix$it" }
    }
    val text = value.text.replaceRange(lineStart, lineEnd, replacement)
    return TextFieldValue(text, TextRange(lineStart, lineStart + replacement.length))
}

// Matches a numbered-list line's leading "N. " marker, used only to detect the number to continue
// from — the line directly above a numbered-list toolbar selection.
private val NUMBERED_LINE_MARKER = Regex("""^(\s*)(\d+)[.)]\s""")

/** Like [prefixMarkdownLines] but for [MarkdownFormatAction.NumberedList]: each selected line gets
 *  an incrementing "1. ", "2. ", "3. ", … instead of the same literal prefix repeated on every
 *  line. Continues numbering from the line directly above the selection when that line is itself a
 *  numbered item (so appending to an existing "4. foo" starts the new block at "5. "), otherwise
 *  starts at 1. Selection-restoring behaviour matches [prefixMarkdownLines] exactly. */
private fun numberMarkdownLines(value: TextFieldValue): TextFieldValue {
    val start = minOf(value.selection.start, value.selection.end)
    val end = maxOf(value.selection.start, value.selection.end)
    val lineStart = value.text.lastIndexOf('\n', start - 1).let { it + 1 }
    val effectiveEnd = if (end > start && end <= value.text.length && value.text[end - 1] == '\n') end - 1 else end
    val lineEnd = value.text.indexOf('\n', effectiveEnd).takeIf { it >= 0 } ?: value.text.length

    val startNumber = if (lineStart > 0) {
        val prevLineEnd = lineStart - 1 // index of the '\n' right before lineStart
        val prevLineStart = value.text.lastIndexOf('\n', prevLineEnd - 1).let { it + 1 }
        val prevLine = value.text.substring(prevLineStart, prevLineEnd)
        NUMBERED_LINE_MARKER.find(prevLine)?.groupValues?.get(2)?.toIntOrNull()?.plus(1) ?: 1
    } else {
        1
    }

    val lines = value.text.substring(lineStart, lineEnd).split('\n')
    val replacement = if (lines.all { NUMBERED_LINE_MARKER.containsMatchIn(it) }) {
        lines.joinToString("\n") { line ->
            NUMBERED_LINE_MARKER.find(line)?.let { line.removeRange(it.range) } ?: line
        }
    } else {
        lines.mapIndexed { index, line ->
            val content = NUMBERED_LINE_MARKER.find(line)?.let { line.removeRange(it.range) } ?: line
            "${startNumber + index}. $content"
        }.joinToString("\n")
    }
    val text = value.text.replaceRange(lineStart, lineEnd, replacement)
    return TextFieldValue(text, TextRange(lineStart, lineStart + replacement.length))
}

// Line patterns [continueMarkdownListOnEnter] recognizes, each capturing leading indentation plus
// whatever comes after the marker. Task items are matched ahead of the plain bullet pattern since
// "- [ ] foo" also satisfies the bullet regex; the caller tries these in the order they're declared.
private val TASK_LIST_LINE = Regex("""^(\s*)([-*+]) \[([ xX])] (.*)$""")
private val ORDERED_LIST_LINE = Regex("""^(\s*)(\d+)([.)]) (.*)$""")
private val QUOTE_LIST_LINE = Regex("""^(\s*)> (.*)$""")
private val BULLET_LIST_LINE = Regex("""^(\s*)([-*+]) (.*)$""")

/**
 * Standard "Enter continues a list/quote" editor behaviour (GitHub/Notion/Obsidian): pressing plain
 * Enter with the caret on a list/quote line either continues that line's marker onto a new line, or
 * — when the item is empty — removes the marker and ends the list instead of inserting a newline.
 * Returns null when none of this applies (no selection collapse, or the caret line isn't a
 * list/quote line at all), so the caller can fall back to plain newline insertion.
 */
internal fun continueMarkdownListOnEnter(value: TextFieldValue): TextFieldValue? {
    if (value.selection.start != value.selection.end) return null
    val caret = value.selection.start
    val text = value.text
    val lineStart = text.lastIndexOf('\n', caret - 1).let { it + 1 }
    val lineEnd = text.indexOf('\n', caret).takeIf { it >= 0 } ?: text.length
    val line = text.substring(lineStart, lineEnd)

    val (indent, nextMarker, content) = TASK_LIST_LINE.find(line)?.let { m ->
        val groups = m.groupValues
        Triple(groups[1], "${groups[2]} [ ] ", groups[4])
    } ?: ORDERED_LIST_LINE.find(line)?.let { m ->
        val groups = m.groupValues
        val next = (groups[2].toIntOrNull() ?: 0) + 1
        Triple(groups[1], "$next${groups[3]} ", groups[4])
    } ?: QUOTE_LIST_LINE.find(line)?.let { m ->
        val (ind, rest) = m.destructured
        Triple(ind, "> ", rest)
    } ?: BULLET_LIST_LINE.find(line)?.let { m ->
        val (ind, bullet, rest) = m.destructured
        Triple(ind, "$bullet ", rest)
    } ?: return null

    if (content.isBlank()) {
        // Enter on an empty item: drop the marker (and its indentation) entirely, leaving a bare
        // empty line, no newline inserted — this ends the list the same way GitHub/Notion do.
        val newText = text.removeRange(lineStart, lineEnd)
        return TextFieldValue(newText, TextRange(lineStart, lineStart))
    }

    // Non-empty item: split the line at the caret and continue the marker on the new line.
    val newText = text.substring(0, caret) + "\n" + indent + nextMarker + text.substring(caret)
    val newCaret = caret + 1 + indent.length + nextMarker.length
    return TextFieldValue(newText, TextRange(newCaret, newCaret))
}

/** Read-only rollup of a note's attached log lines, feeding the evidence panel's collapsed summary
 *  row ("N lines · firstTs → lastTs") and its level-count chips. Kept pure so the summary text and
 *  chip ordering are unit-testable without composing the dialog. */
internal data class EvidenceSummary(
    val count: Int,
    val firstTs: String,
    val lastTs: String,
    val levelCounts: List<Pair<LogLevel, Int>>,
)

// Chips read worst-first (Error, Warn, Info, Debug, Verbose) per the design handoff; Assert is
// folded in after Verbose rather than given its own slot ahead of the others — LogParser never
// emits one, but a caller that somehow has one still sees a count instead of it silently vanishing.
private val EVIDENCE_LEVEL_ORDER =
    listOf(LogLevel.E, LogLevel.W, LogLevel.I, LogLevel.D, LogLevel.V, LogLevel.A)

internal fun evidenceSummary(rows: List<LogEntry>): EvidenceSummary {
    val counts = rows.groupingBy { it.level }.eachCount()
    return EvidenceSummary(
        count = rows.size,
        firstTs = rows.firstOrNull()?.ts.orEmpty(),
        lastTs = rows.lastOrNull()?.ts.orEmpty(),
        levelCounts = EVIDENCE_LEVEL_ORDER.mapNotNull { level -> counts[level]?.let { level to it } },
    )
}

/** "N lines · firstTs → lastTs", the summary row shared by the dialog and the report panel's log excerpts. */
internal fun EvidenceSummary.rangeLabel(): String =
    "$count ${if (count == 1) "line" else "lines"} · $firstTs → $lastTs"

/** Word count for the editor footer strip ("N words"). Splits on whitespace runs so Markdown
 *  punctuation (`**`, backticks, …) doesn't inflate the count. */
internal fun markdownWordCount(text: String): Int =
    text.trim().let { if (it.isEmpty()) 0 else it.split(Regex("\\s+")).size }

// ── Add annotation dialog ─────────────────────────────────────────────
@Composable
internal fun AddAnnDialog(
    rows: List<LogEntry>,
    windowSize: IntSize,
    fileLabel: String? = null,
    locked: Boolean = false,
    onConfirm: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    AnnotationMarkdownEditorDialog(
        title = "New note",
        initialText = "",
        confirmLabel = "Save note",
        rows = rows,
        windowSize = windowSize,
        fileLabel = fileLabel,
        locked = locked,
        onConfirm = onConfirm,
        onDismiss = onDismiss,
    )
}

// Plain rounded box — the tabs above no longer join it directly (see the 8dp gap in the
// Write/Preview tabs + editor box section below), so every corner stays the same radius.
private val EDITOR_BOX_SHAPE = RoundedCornerShape(6.dp)

/**
 * Shared large Markdown editor for a new log annotation and an existing annotation edit
 * ("Note editor redesign" 1a). Layout, top to bottom: header (title, file chip, ✕) → a
 * collapsed-by-default evidence summary (only when [rows] is non-empty) → Write/Preview
 * [UnderlineTabs] (the same tab visuals as FilterPanel's Tags/Regex switcher) above a separate
 * bordered editor box (borderless hover toolbar, text field or rendered preview, a footer strip
 * with the Markdown hint / word count / unsaved-changes / the save-shortcut hint) → an action bar
 * with an optional Delete, Cancel and a solid-accent Save.
 */
@Composable
internal fun AnnotationMarkdownEditorDialog(
    title: String,
    initialText: String,
    confirmLabel: String,
    windowSize: IntSize,
    rows: List<LogEntry> = emptyList(),
    fileLabel: String? = null,
    /** Stable identity of the edited block; keeps a draft alive while upstream Notes change. */
    sessionKey: Any? = null,
    locked: Boolean = false,
    onDelete: (() -> Unit)? = null,
    onConfirm: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    val tc = tc()
    val mono = monoFont()
    val density = LocalDensity.current
    val dialogWidth = minOf(780.dp, with(density) { (windowSize.width * 0.92f).toDp() })
    // Sized to 88% of the window height, same fixed-fraction approach the previous 72% used — the
    // editor area below fills whatever that leaves via weight(1f) and scrolls its own overflow.
    // Capped at 760dp so a large monitor doesn't turn this into an oversized modal.
    val dialogHeight = with(density) { (windowSize.height * 0.88f).toDp() }.coerceAtMost(760.dp)

    var baselineText by remember(sessionKey) { mutableStateOf(initialText) }
    var editorValue by remember(sessionKey) { mutableStateOf(TextFieldValue(initialText)) }
    var conflictLatest by remember(sessionKey) { mutableStateOf<String?>(null) }
    var previewMode by remember { mutableStateOf(false) }
    var evidenceExpanded by remember { mutableStateOf(false) }
    var headingMenuOpen by remember { mutableStateOf(false) }
    // See AiModelDropdown for why this guard is needed: the Popup's onDismissRequest also fires for
    // a click back on the "Heading" button itself, which would otherwise race the button's own
    // toggle and net out to "stayed open" instead of closing.
    var suppressHeadingToggleUntilMs by remember { mutableStateOf(0L) }
    // A toolbar click can cause the text field to report a collapsed selection before its click
    // callback runs. Retain the last real selection so formatting still wraps what the user saw
    // highlighted instead of appending a placeholder after it.
    var retainedSelection by remember { mutableStateOf<TextRange?>(null) }
    val editorFocusRequester = remember { FocusRequester() }
    val editorHistory = remember(sessionKey) { MarkdownEditorUndoHistory() }

    val summary = remember(rows) { evidenceSummary(rows) }
    val wordCount = remember(editorValue.text) { markdownWordCount(editorValue.text) }
    // MCP/external and AI writes can arrive while this dialog is open. Keep a pristine draft in
    // sync; preserve a dirty draft and make the user resolve it explicitly.
    LaunchedEffect(initialText) {
        val sync = reconcileMarkdownDraft(baselineText, editorValue.text, initialText)
        if (sync.conflict) {
            conflictLatest = sync.latest
        } else {
            baselineText = sync.baseline
            editorValue = TextFieldValue(sync.draft, TextRange(sync.draft.length))
            conflictLatest = null
            editorHistory.clear()
        }
    }
    val hasUnsavedChanges = editorValue.text != baselineText
    val hasConflict = conflictLatest != null
    val shortcutHint = if (isMacOs) "⌘↵ save · esc cancel" else "Ctrl↵ save · esc cancel"
    val displayTitle = if (rows.isNotEmpty()) {
        "Note on ${rows.size} log line${if (rows.size == 1) "" else "s"}"
    } else {
        title
    }

    // Runs once on open (previewMode starts false) and again every time the user switches back
    // from Preview to Write. The FocusRequester is only attached to the BasicTextField in Write
    // mode — Preview has no text field to attach it to — so without this, switching back leaves
    // the dialog with nothing focused at all: typing and the root Esc/save shortcuts go nowhere
    // until the user clicks the field themselves.
    LaunchedEffect(previewMode) {
        if (!previewMode) runCatching { editorFocusRequester.requestFocus() }
    }

    // The CLAUDE.md-documented scar: Modifier.clickable is focusable, so clicking it moves keyboard
    // focus onto it and never gives it back. Every click handler that isn't itself the text field
    // (toolbar buttons, the Heading popup) routes through one of these two reclaims so the user can
    // keep typing right after, and so this dialog's own root Esc/save shortcuts keep working.
    fun closeHeadingMenu() {
        headingMenuOpen = false
        suppressHeadingToggleUntilMs = System.currentTimeMillis() + 200
        runCatching { editorFocusRequester.requestFocus() }
    }

    fun updateEditor(updated: TextFieldValue) {
        if (locked) return
        retainedSelection = retainedMarkdownSelectionAfterEditorUpdate(editorValue, updated, retainedSelection)
        editorHistory.record(editorValue, updated)
        editorValue = updated
    }

    fun applyFormat(action: MarkdownFormatAction) {
        if (locked || hasConflict) return
        val valueForAction = restoreMarkdownSelection(editorValue, retainedSelection)
        val updated = applyMarkdownFormat(valueForAction, action)
        editorHistory.record(editorValue, updated)
        editorValue = updated
        // Keep the newly selected formatted content available if the toolbar click causes a
        // second focus-collapse callback before the next action is dispatched.
        retainedSelection = editorValue.selection.takeIf { it.start != it.end }
        runCatching { editorFocusRequester.requestFocus() }
    }

    fun reloadLatest() {
        val latest = conflictLatest ?: initialText
        baselineText = latest
        editorValue = TextFieldValue(latest, TextRange(latest.length))
        conflictLatest = null
        retainedSelection = null
        editorHistory.clear()
    }

    fun undoEditor() {
        if (locked || hasConflict) return
        editorHistory.undo(editorValue)?.let {
            editorValue = it
            retainedSelection = null
        }
    }

    fun redoEditor() {
        if (locked || hasConflict) return
        editorHistory.redo(editorValue)?.let {
            editorValue = it
            retainedSelection = null
        }
    }

    fun overwriteLatest() {
        if (locked) return
        val draft = editorValue.text
        baselineText = conflictLatest ?: initialText
        conflictLatest = null
        onConfirm(draft)
    }

    val dialogShape = RoundedCornerShape(10.dp)
    Box(
        // Make the actual dialog window, not merely its content, a same-axis fraction of the
        // main window. This keeps its aspect ratio stable while the user resizes the app.
        Modifier.width(dialogWidth).height(dialogHeight)
            .background(tc.p, dialogShape)
            .border(1.dp, tc.br, dialogShape)
            .onPreviewKeyEvent { ev ->
                if (ev.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
                when {
                    // Esc closes the Heading popup first, same as the header's own ✕/dismiss.
                    headingMenuOpen && ev.key == Key.Escape -> { closeHeadingMenu(); true }
                    ev.key == Key.Escape -> { onDismiss(); true }
                    ev.isActionKey && ev.key == Key.Z && !ev.isAltPressed && !previewMode -> {
                        if (ev.isShiftPressed) redoEditor() else undoEditor()
                        true
                    }
                    ev.isActionKey && (ev.key == Key.Enter || ev.key == Key.NumPadEnter) -> {
                        if (!locked && !hasConflict) onConfirm(editorValue.text)
                        true
                    }
                    else -> false
                }
            },
    ) {
        Column(Modifier.fillMaxSize()) {
            // ── Header ──────────────────────────────────────────────────
            Row(
                Modifier.fillMaxWidth().padding(start = 16.dp, end = 16.dp, top = 14.dp, bottom = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                AppText(displayTitle, color = tc.tx, fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
                if (fileLabel != null) {
                    Box(
                        Modifier.background(tc.ac.copy(alpha = .12f), CORNER_SM)
                            .border(1.dp, tc.ac.copy(alpha = .28f), CORNER_SM)
                            .padding(horizontal = 6.dp, vertical = 2.dp),
                    ) { AppText(fileLabel, color = tc.ac, fontSize = 10.sp, fontFamily = mono) }
                }
                Spacer(Modifier.weight(1f))
                CloseButton(onClick = onDismiss)
            }
            NoteDialogDivider()

            if (locked) {
                AppText(
                    NOTES_EDIT_LOCK_MESSAGE,
                    color = tc.td,
                    fontSize = 11.sp,
                    modifier = Modifier.fillMaxWidth().background(tc.abg).padding(horizontal = 16.dp, vertical = 7.dp),
                )
            }
            if (hasConflict) {
                Row(
                    Modifier.fillMaxWidth().background(tc.abg).padding(horizontal = 16.dp, vertical = 7.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    AppText(
                        "Notes changed elsewhere. Resolve this conflict before saving.",
                        color = tc.tx,
                        fontSize = 11.sp,
                        modifier = Modifier.weight(1f),
                    )
                    EditorDialogActionButton("Reload latest", filled = false, onClick = ::reloadLatest)
                    EditorDialogActionButton("Overwrite with my draft", filled = true, enabled = !locked, onClick = ::overwriteLatest)
                }
            }

            // ── Evidence panel (collapsed by default) ────────────────────
            if (rows.isNotEmpty()) {
                Box(Modifier.padding(start = 16.dp, end = 16.dp, top = 12.dp)) {
                    NoteEvidencePanel(
                        rows = rows,
                        summary = summary,
                        expanded = evidenceExpanded,
                        onToggle = { evidenceExpanded = !evidenceExpanded },
                        mono = mono,
                    )
                }
            }

            // ── Write / Preview tabs + editor box ────────────────────────
            Column(Modifier.weight(1f).padding(start = 16.dp, end = 16.dp, top = 14.dp)) {
                UnderlineTabs(
                    labels = listOf("Write", "Preview"),
                    selectedIndex = if (previewMode) 1 else 0,
                    onSelect = { index ->
                        previewMode = index == 1
                        // The tab is clickable, so it takes focus; re-clicking an already-active
                        // Write tab wouldn't re-run LaunchedEffect(previewMode), so reclaim here too.
                        if (index == 0) runCatching { editorFocusRequester.requestFocus() }
                    },
                )
                Spacer(Modifier.height(8.dp))
                Column(
                    Modifier.weight(1f).fillMaxWidth()
                        .border(1.dp, tc.br, EDITOR_BOX_SHAPE)
                        .background(tc.p, EDITOR_BOX_SHAPE)
                        .clip(EDITOR_BOX_SHAPE),
                ) {
                    if (!previewMode) {
                        // No weighted children here, so a horizontal scroll is safe: on a narrow
                        // window the row just scrolls instead of clipping the trailing buttons.
                        Row(
                            Modifier.fillMaxWidth().horizontalScroll(rememberScrollState())
                                .padding(horizontal = 6.dp, vertical = 5.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Box {
                                MarkdownToolbarButton(
                                    enabled = !locked && !hasConflict,
                                    onClick = {
                                        if (System.currentTimeMillis() >= suppressHeadingToggleUntilMs) {
                                            headingMenuOpen = !headingMenuOpen
                                        }
                                    },
                                ) {
                                    Row(
                                        verticalAlignment = Alignment.CenterVertically,
                                        horizontalArrangement = Arrangement.spacedBy(4.dp),
                                    ) {
                                        AppText("Heading", color = tc.ts, fontSize = 11.sp)
                                        AppText("▾", color = tc.td, fontSize = 8.sp)
                                    }
                                }
                                if (headingMenuOpen) {
                                    HeadingFormatMenu(
                                        onSelect = { action -> applyFormat(action); closeHeadingMenu() },
                                        onDismiss = { closeHeadingMenu() },
                                    )
                                }
                            }
                            MarkdownToolbarSeparator()
                            MarkdownToolbarButton(enabled = !locked && !hasConflict, onClick = { applyFormat(MarkdownFormatAction.Bold) }) {
                                AppText("B", color = tc.ts, fontSize = 12.sp, fontWeight = FontWeight.Bold)
                            }
                            MarkdownToolbarButton(enabled = !locked && !hasConflict, onClick = { applyFormat(MarkdownFormatAction.Italic) }) {
                                // AppText has no italic param; this is the one glyph that needs it. Serif,
                                // because a sans-serif italic capital I renders as a bare slash.
                                Text("I", color = tc.ts, fontSize = 13.sp, fontStyle = FontStyle.Italic, fontFamily = FontFamily.Serif)
                            }
                            MarkdownToolbarButton(enabled = !locked && !hasConflict, onClick = { applyFormat(MarkdownFormatAction.Strikethrough) }) {
                                AppText("S", color = tc.ts, fontSize = 12.sp, textDecoration = TextDecoration.LineThrough)
                            }
                            MarkdownToolbarSeparator()
                            MarkdownToolbarButton(enabled = !locked && !hasConflict, onClick = { applyFormat(MarkdownFormatAction.InlineCode) }) {
                                AppText("Code", color = tc.ts, fontSize = 11.sp)
                            }
                            MarkdownToolbarButton(enabled = !locked && !hasConflict, onClick = { applyFormat(MarkdownFormatAction.CodeBlock) }) {
                                AppText("Code block", color = tc.ts, fontSize = 11.sp)
                            }
                            MarkdownToolbarButton(enabled = !locked && !hasConflict, onClick = { applyFormat(MarkdownFormatAction.Quote) }) {
                                AppText("Quote", color = tc.ts, fontSize = 11.sp)
                            }
                            MarkdownToolbarSeparator()
                            MarkdownToolbarButton(enabled = !locked && !hasConflict, onClick = { applyFormat(MarkdownFormatAction.BulletList) }) {
                                AppText("• List", color = tc.ts, fontSize = 11.sp)
                            }
                            MarkdownToolbarButton(enabled = !locked && !hasConflict, onClick = { applyFormat(MarkdownFormatAction.NumberedList) }) {
                                AppText("1. List", color = tc.ts, fontSize = 11.sp)
                            }
                            MarkdownToolbarSeparator()
                            MarkdownToolbarButton(enabled = !locked && !hasConflict, onClick = { applyFormat(MarkdownFormatAction.Link) }) {
                                AppText("Link", color = tc.ts, fontSize = 11.sp)
                            }
                        }
                        NoteDialogDivider()
                    }

                    // A 260dp floor (this area's natural resting height) would outgrow a short
                    // window's weight(1f) share and push the action bar off the bottom of the
                    // dialog; 120dp is just enough to keep the editor usable while still yielding
                    // to the header/evidence/toolbar/footer/action-bar chrome around it.
                    Box(Modifier.weight(1f).fillMaxWidth().heightIn(min = 120.dp)) {
                        // Keep the BasicTextField mounted while Preview is active. Its legacy
                        // Compose undo manager is remembered inside the field, so conditionally
                        // removing it here used to discard the history and make Ctrl/Cmd+Z a
                        // no-op after a Preview -> Write round trip.
                        MarkdownWriteArea(
                            value = editorValue,
                            onValueChange = ::updateEditor,
                            placeholder = "Write your note…",
                            focusRequester = editorFocusRequester,
                            enabled = !locked && !hasConflict && !previewMode,
                            modifier = Modifier.alpha(if (previewMode) 0f else 1f),
                        )
                        if (previewMode) {
                            MarkdownPreviewArea(
                                text = editorValue.text,
                                tc = tc,
                                modifier = Modifier.zIndex(1f),
                            )
                        }
                    }

                    NoteDialogDivider()
                    Row(
                        Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 6.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        // "Markdown" used to sit at the trailing end of the tab row; moved here
                        // (left side of the footer strip) once the tabs stopped joining the editor
                        // box directly.
                        AppText("Markdown", color = tc.td, fontSize = 10.sp, fontFamily = mono)
                        AppText("·", color = tc.td, fontSize = 10.sp, fontFamily = mono)
                        AppText("$wordCount words", color = tc.td, fontSize = 10.sp, fontFamily = mono)
                        if (hasUnsavedChanges) {
                            AppText("·", color = tc.td, fontSize = 10.sp, fontFamily = mono)
                            AppText("unsaved changes", color = tc.td, fontSize = 10.sp, fontFamily = mono)
                        }
                        Spacer(Modifier.weight(1f))
                        AppText(shortcutHint, color = tc.td, fontSize = 11.sp)
                    }
                }
            }

            // ── Action bar ────────────────────────────────────────────────
            Spacer(Modifier.height(16.dp))
            NoteDialogDivider()
            Row(
                Modifier.fillMaxWidth().padding(16.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                if (onDelete != null) DeleteNoteButton(enabled = !locked, onClick = onDelete)
                Spacer(Modifier.weight(1f))
                EditorDialogActionButton("Cancel", filled = false, onClick = onDismiss)
                EditorDialogActionButton(confirmLabel, filled = true, enabled = !locked && !hasConflict, onClick = { onConfirm(editorValue.text) })
            }
        }
    }
}

// A lighter line than the shared Divider()'s tc.br — the design's separate "hover / divider" token
// (tc.p2) for internal seams (header underline, toolbar underline, footer overline), distinct from
// tc.br's stronger structural borders (dialog outline, evidence box, editor box).
@Composable
private fun NoteDialogDivider() {
    val tc = tc()
    Box(Modifier.fillMaxWidth().height(1.dp).background(tc.p2))
}

/** Collapsible evidence summary: a clickable "N lines · firstTs → lastTs" row with level-count
 *  chips, collapsed by default, expanding to the bounded/scrollable row list. */
@Composable
private fun NoteEvidencePanel(
    rows: List<LogEntry>,
    summary: EvidenceSummary,
    expanded: Boolean,
    onToggle: () -> Unit,
    mono: FontFamily,
) {
    val tc = tc()
    val shape = RoundedCornerShape(6.dp)
    Column(
        Modifier.fillMaxWidth()
            .background(tc.bg, shape)
            .border(1.dp, tc.br, shape)
            .clip(shape),
    ) {
        HoverBox(modifier = Modifier.fillMaxWidth(), onClick = onToggle) {
            Row(
                Modifier.fillMaxWidth().padding(horizontal = 10.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                AppText(if (expanded) "▾" else "▸", color = tc.td, fontSize = 9.sp, modifier = Modifier.width(8.dp))
                AppText(
                    summary.rangeLabel(),
                    color = tc.ts,
                    fontSize = 11.sp,
                    fontFamily = mono,
                )
                Spacer(Modifier.weight(1f))
                Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    summary.levelCounts.forEach { (level, count) -> EvidenceLevelChip(level, count, mono) }
                }
            }
        }
        if (expanded) {
            Box(Modifier.fillMaxWidth().height(1.dp).background(tc.br))
            val scroll = rememberScrollState()
            Box(Modifier.fillMaxWidth().heightIn(max = 118.dp)) {
                Column(
                    Modifier.fillMaxSize().verticalScroll(scroll)
                        .padding(start = 10.dp, end = 12.dp, top = 6.dp, bottom = 8.dp),
                    verticalArrangement = Arrangement.spacedBy(3.dp),
                ) {
                    rows.forEach { row -> NoteEvidenceRow(row, mono) }
                }
                VerticalScrollbar(
                    adapter = rememberScrollbarAdapter(scroll),
                    modifier = Modifier.align(Alignment.CenterEnd).fillMaxHeight().padding(vertical = 4.dp),
                    style = appScrollbarStyle(tc),
                )
            }
        }
    }
}

@Composable
private fun NoteEvidenceRow(row: LogEntry, mono: FontFamily) {
    val tc = tc()
    Row(
        Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        AppText(row.ts, color = tc.td, fontSize = 10.sp, fontFamily = mono, modifier = Modifier.width(78.dp))
        Box(Modifier.width(18.dp), contentAlignment = Alignment.Center) { LevelBadge(row.level) }
        AppText(
            row.tag,
            color = tc.ts,
            fontSize = 10.sp,
            fontFamily = mono,
            modifier = Modifier.width(132.dp),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        AppText(
            row.msg,
            color = tc.tx,
            fontSize = 10.sp,
            fontFamily = mono,
            modifier = Modifier.weight(1f),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

@Composable
private fun EvidenceLevelChip(level: LogLevel, count: Int, mono: FontFamily) {
    val color = level.defaultColor
    Box(
        Modifier.background(color.copy(alpha = .13f), CORNER_SM)
            .border(1.dp, color.copy(alpha = .27f), CORNER_SM)
            .padding(horizontal = 5.dp, vertical = 1.dp),
    ) {
        AppText("$count ${level.key}", color = color, fontSize = 10.sp, fontFamily = mono, fontWeight = FontWeight.SemiBold)
    }
}

/** One borderless, hover-only toolbar button (26dp min). Shared shell for both the plain-label
 *  buttons and the bespoke B/I/S glyphs, which need styling AppText's fixed param set can't express. */
@Composable
private fun MarkdownToolbarButton(enabled: Boolean = true, onClick: () -> Unit, content: @Composable () -> Unit) {
    val tc = tc()
    var hovered by remember { mutableStateOf(false) }
    val shape = RoundedCornerShape(5.dp)
    Box(
        Modifier
            .heightIn(min = 26.dp)
            .widthIn(min = 26.dp)
            .background(if (hovered) tc.hv else Color.Transparent, shape)
            .clip(shape)
            .then(if (enabled) Modifier.clickable(onClick = onClick) else Modifier)
            .onPointerEvent(PointerEventType.Enter) { hovered = true }
            .onPointerEvent(PointerEventType.Exit) { hovered = false }
            .padding(horizontal = 8.dp),
        contentAlignment = Alignment.Center,
    ) { content() }
}

@Composable
private fun MarkdownToolbarSeparator() {
    val tc = tc()
    Box(Modifier.padding(horizontal = 5.dp).width(1.dp).height(16.dp).background(tc.br))
}

/** The Heading ▾ dropdown's H1/H2/H3 menu. Non-focusable (no arrow-key roving needed for three
 *  rows) — the dialog's own root onPreviewKeyEvent handles Esc, and every path that closes this
 *  (a row click or the click-outside dismiss) reclaims the editor's focus via [onDismiss]/
 *  [onSelect] per the CLAUDE.md Popup-focus gotcha. */
@Composable
private fun HeadingFormatMenu(onSelect: (MarkdownFormatAction) -> Unit, onDismiss: () -> Unit) {
    val tc = tc()
    val density = LocalDensity.current.density
    Popup(
        alignment = Alignment.TopStart,
        offset = IntOffset(0, (30 * density).roundToInt()),
        onDismissRequest = onDismiss,
        properties = PopupProperties(focusable = false),
    ) {
        val shape = RoundedCornerShape(6.dp)
        Column(
            Modifier.width(90.dp)
                .background(tc.p, shape)
                .border(1.dp, tc.br, shape)
                .clip(shape)
                .padding(vertical = 4.dp),
        ) {
            listOf(
                "H1" to MarkdownFormatAction.Heading1,
                "H2" to MarkdownFormatAction.Heading2,
                "H3" to MarkdownFormatAction.Heading3,
            ).forEach { (label, action) ->
                HoverBox(modifier = Modifier.fillMaxWidth(), onClick = { onSelect(action) }) {
                    AppText(
                        label,
                        color = tc.tx,
                        fontSize = 11.sp,
                        modifier = Modifier.fillMaxWidth().padding(horizontal = 10.dp, vertical = 5.dp),
                    )
                }
            }
        }
    }
}

@Composable
private fun MarkdownWriteArea(
    value: TextFieldValue,
    onValueChange: (TextFieldValue) -> Unit,
    placeholder: String,
    focusRequester: FocusRequester,
    enabled: Boolean = true,
    modifier: Modifier = Modifier,
) {
    val tc = tc()
    val editorScroll = rememberScrollState()
    val density = LocalDensity.current
    var caretBounds by remember { mutableStateOf<MarkdownCaretBounds?>(null) }

    // BasicTextField owns the scrollable viewport, so BringIntoViewRequester cannot target the
    // caret (the field itself is the viewport). Use the layout's exact caret rectangle instead,
    // keeping the cursor visible as text grows past the bottom or when the user moves it with the
    // arrow keys. The effect is keyed only by editor content/selection and therefore never fights
    // an intentional mouse-wheel scroll while the user is merely reading the draft.
    LaunchedEffect(value.text, value.selection, caretBounds, editorScroll.viewportSize, editorScroll.maxValue) {
        val caret = caretBounds?.takeIf { it.offset == value.selection.end } ?: return@LaunchedEffect
        if (value.selection.start != value.selection.end) return@LaunchedEffect
        // Let the text field publish its new scroll range after a line was added before deciding
        // how far to move. Without this frame boundary a fast append can observe maxValue == 0,
        // clamp the desired target to zero, and never get another key change to correct it.
        withFrameNanos { }
        val margin = with(density) { 8.dp.toPx() }
        val viewport = editorScroll.viewportSize.toFloat()
        if (viewport <= 0f) return@LaunchedEffect
        val current = editorScroll.value.toFloat()
        val target = when {
            caret.top < current + margin -> (caret.top - margin).coerceAtLeast(0f)
            caret.bottom > current + viewport - margin -> caret.bottom - viewport + margin
            else -> return@LaunchedEffect
        }
        editorScroll.animateScrollTo(target.roundToInt().coerceIn(0, editorScroll.maxValue))
    }

    Box(modifier.fillMaxSize()) {
        BasicTextField(
            value = value,
            onValueChange = onValueChange,
            textStyle = TextStyle(
                color = tc.tx,
                fontSize = 13.sp,
                fontFamily = FontFamily.Default,
                lineHeight = 21.sp,
            ),
            cursorBrush = SolidColor(tc.ac),
            modifier = Modifier.fillMaxSize()
                .testTag("annotation-markdown-editor")
                .focusRequester(focusRequester)
                .onPreviewKeyEvent { ev ->
                    // Plain Enter/NumPadEnter only — no modifiers. The dialog root's own
                    // onPreviewKeyEvent sees ⌘/Ctrl+Enter (save) first since preview events
                    // dispatch top-down, and Shift+Enter must still insert a plain newline, so both
                    // are excluded here rather than relied on to fall through.
                    val plainEnter = ev.type == KeyEventType.KeyDown &&
                        (ev.key == Key.Enter || ev.key == Key.NumPadEnter) &&
                        !ev.isShiftPressed && !ev.isCtrlPressed && !ev.isMetaPressed && !ev.isAltPressed
                    if (!enabled || !plainEnter) return@onPreviewKeyEvent false
                    val continued = continueMarkdownListOnEnter(value)
                    if (continued != null) {
                        onValueChange(continued)
                        true
                    } else {
                        false
                    }
                }
                .padding(horizontal = 14.dp, vertical = 12.dp)
                .verticalScroll(editorScroll),
            onTextLayout = { result ->
                val offset = value.selection.end.coerceIn(0, value.text.length)
                val rect = result.getCursorRect(offset)
                val next = MarkdownCaretBounds(offset, rect.top, rect.bottom)
                if (caretBounds != next) caretBounds = next
            },
            decorationBox = { inner ->
                if (value.text.isEmpty()) AppText(placeholder, color = tc.td, fontSize = 13.sp)
                inner()
            },
            readOnly = !enabled,
        )
        VerticalScrollbar(
            adapter = rememberScrollbarAdapter(editorScroll),
            modifier = Modifier.align(Alignment.CenterEnd).fillMaxHeight().padding(vertical = 4.dp),
            style = appScrollbarStyle(tc),
        )
    }
}

@Composable
private fun MarkdownPreviewArea(text: String, tc: ThemeColors, modifier: Modifier = Modifier) {
    val scroll = rememberScrollState()
    Box(modifier.fillMaxSize().background(tc.p)) {
        Column(Modifier.fillMaxSize().verticalScroll(scroll).padding(horizontal = 14.dp, vertical = 12.dp)) {
            if (text.isBlank()) {
                AppText("Nothing to preview", color = tc.td, fontSize = 13.sp)
            } else {
                AnnotationMarkdownText(text, tc)
            }
        }
        VerticalScrollbar(
            adapter = rememberScrollbarAdapter(scroll),
            modifier = Modifier.align(Alignment.CenterEnd).fillMaxHeight().padding(vertical = 4.dp),
            style = appScrollbarStyle(tc),
        )
    }
}

@Composable
private fun DeleteNoteButton(enabled: Boolean = true, onClick: () -> Unit) {
    var hovered by remember { mutableStateOf(false) }
    val shape = RoundedCornerShape(6.dp)
    Box(
        Modifier
            .height(34.dp)
            .background(if (hovered) DANGER_RED.copy(alpha = .1f) else Color.Transparent, shape)
            .clip(shape)
            .then(if (enabled) Modifier.clickable(onClick = onClick) else Modifier)
            .onPointerEvent(PointerEventType.Enter) { hovered = true }
            .onPointerEvent(PointerEventType.Exit) { hovered = false }
            .padding(horizontal = 10.dp),
        contentAlignment = Alignment.Center,
    ) { AppText("Delete note", color = DANGER_RED, fontSize = 12.sp) }
}

/** Cancel and Save share this shell so the two read as one pair instead of Cancel (previously a
 *  plain [AppButton]) looking visibly shorter than Save: both render at the same 34dp height, 6dp
 *  corner radius, 16dp horizontal padding and 12sp label. [filled] picks which of the two looks —
 *  Save's solid `tc.ac` fill with SemiBold `tc.p` text, darkening toward black on hover (unlike
 *  [AppButton]'s Primary variant, whose fill doesn't react to hover, matching the design handoff),
 *  or Cancel's outlined look: a 1dp `tc.br` border on a `tc.p` fill, `tc.hv` on hover, `tc.tx`
 *  text. */
@Composable
private fun EditorDialogActionButton(label: String, filled: Boolean, enabled: Boolean = true, onClick: () -> Unit) {
    val tc = tc()
    var hovered by remember { mutableStateOf(false) }
    val shape = RoundedCornerShape(6.dp)
    val fill = when {
        filled -> if (hovered) lerp(tc.ac, Color.Black, 0.15f) else tc.ac
        hovered -> tc.hv
        else -> tc.p
    }
    Box(
        Modifier
            .height(34.dp)
            .then(if (!filled) Modifier.border(1.dp, tc.br, shape) else Modifier)
            .background(fill, shape)
            .clip(shape)
            .then(if (enabled) Modifier.clickable(onClick = onClick) else Modifier)
            .onPointerEvent(PointerEventType.Enter) { hovered = true }
            .onPointerEvent(PointerEventType.Exit) { hovered = false }
            .padding(horizontal = 16.dp),
        contentAlignment = Alignment.Center,
    ) {
        AppText(
            label,
            color = if (enabled) {
                if (filled) tc.p else tc.tx
            } else {
                tc.td
            },
            fontSize = 12.sp,
            fontWeight = if (filled) FontWeight.SemiBold else FontWeight.Normal,
        )
    }
}

// ── Custom AI command editor ──────────────────────────────────────────
@Composable
internal fun CustomAiCommandEditorDialog(
    state: AppState,
    target: CustomAiCommand,
    onDismiss: () -> Unit,
) {
    val tc = tc()
    val isNew = target.name.isBlank()
    var name by remember(target) { mutableStateOf(target.name) }
    var template by remember(target) { mutableStateOf(target.promptTemplate) }
    var error by remember(target) { mutableStateOf<String?>(null) }

    Column(
        Modifier.width(440.dp).background(tc.p, RoundedCornerShape(8.dp))
            .border(1.dp, tc.br, RoundedCornerShape(8.dp)).padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        AppText(
            if (isNew) "Add custom AI command" else "Edit custom AI command",
            color = tc.tx,
            fontSize = 14.sp,
            fontWeight = FontWeight.SemiBold,
        )
        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            AppText("Name (invoked as /name)", color = tc.td, fontSize = 10.sp)
            InlineField(name, { name = it }, "timeline", Modifier.fillMaxWidth(), fontSize = 12.sp)
        }
        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            AppText("Prompt template", color = tc.td, fontSize = 10.sp)
            val templateScroll = rememberScrollState()
            // Keep the dialog compact regardless of template length. verticalScroll changes its
            // child's height constraints, so placing it outside the old heightIn modifier allowed
            // a large template to grow the entire dialog before its scrollbar could take effect.
            Box(Modifier.fillMaxWidth().height(260.dp)) {
                BasicTextField(
                    value = template,
                    onValueChange = { template = it },
                    textStyle = TextStyle(color = tc.tx, fontSize = 12.sp, fontFamily = FontFamily.Default, lineHeight = 18.sp),
                    cursorBrush = SolidColor(tc.ac),
                    modifier = Modifier.fillMaxSize()
                        .background(tc.bg, CORNER_MD)
                        .border(1.dp, tc.ac.copy(.5f), CORNER_MD)
                        .padding(10.dp)
                        .verticalScroll(templateScroll),
                    decorationBox = { inner ->
                        if (template.isEmpty()) {
                            AppText(
                                "What should the assistant do when this command is invoked?",
                                color = tc.td,
                                fontSize = 12.sp,
                            )
                        }
                        inner()
                    },
                )
                VerticalScrollbar(
                    adapter = rememberScrollbarAdapter(templateScroll),
                    modifier = Modifier.align(Alignment.CenterEnd).fillMaxHeight().padding(vertical = 4.dp),
                    style = appScrollbarStyle(tc),
                )
            }
        }
        error?.let { AppText(it, color = DANGER_RED, fontSize = 10.sp) }
        Row(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterHorizontally),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            DialogActionButton("Save", active = true) {
                val saveError = state.saveCustomAiCommand(
                    name.trim(),
                    template,
                    previousName = target.name.takeIf { it.isNotBlank() },
                )
                if (saveError != null) error = saveError else onDismiss()
            }
            DialogActionButton("Cancel", active = false, onClick = onDismiss)
        }
    }
}

// ── Per-folder project info editor ────────────────────────────────────
@Composable
internal fun SourceFolderInfoDialog(
    state: AppState,
    path: String,
    onDismiss: () -> Unit,
) {
    val tc = tc()
    val existing = state.settings.sourceFolderInfo[path] ?: SourceFolderInfo()
    var description by remember(path) { mutableStateOf(existing.description) }
    var readmePath by remember(path) { mutableStateOf(existing.readmePath.orEmpty()) }

    Column(
        Modifier.width(440.dp).background(tc.p, RoundedCornerShape(8.dp))
            .border(1.dp, tc.br, RoundedCornerShape(8.dp)).padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        AppText("Project info", color = tc.tx, fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
        AppText(truncatePathForDisplay(path), color = tc.td, fontSize = 10.sp, fontFamily = MONO, maxLines = 1, overflow = TextOverflow.Ellipsis)
        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            AppText("Description", color = tc.td, fontSize = 10.sp)
            ScrollableTextArea(
                value = description,
                onValue = { description = it },
                placeholder = "What is this project / what should the AI know about it?",
                modifier = Modifier.fillMaxWidth(),
                fontSize = 12.sp,
                lineHeight = 18.sp,
                minHeight = 80.dp,
                maxHeight = 200.dp,
                resetKey = path,
                shape = CORNER_MD,
                borderColor = tc.ac.copy(.5f),
                contentPadding = PaddingValues(10.dp),
                onClear = { description = "" },
            )
        }
        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            AppText("README path (optional)", color = tc.td, fontSize = 10.sp)
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                InlineField(readmePath, { readmePath = it }, "/path/to/README.md", Modifier.weight(1f), fontSize = 12.sp, onClear = { readmePath = "" })
                AppButton(
                    "Browse",
                    onClick = { state.pickReadmeFile()?.let { readmePath = it } },
                    variant = ButtonVariant.Secondary,
                )
            }
        }
        Row(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterHorizontally),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            DialogActionButton("Save", active = true) {
                state.updateSourceFolderInfo(path, SourceFolderInfo(description, readmePath.trim().ifBlank { null }))
                onDismiss()
            }
            DialogActionButton("Cancel", active = false, onClick = onDismiss)
        }
    }
}

@Composable
internal fun SplitPromptDialog(
    state: AppState,
    pending: PendingSplitPrompt,
    onDismiss: () -> Unit,
) {
    val tc = tc()
    val firstSource = pending.sources.first()
    val isSingleSource = pending.sources.size == 1
    var destination by remember(pending) {
        mutableStateOf(state.defaultSplitDestination(firstSource).absolutePath)
    }
    var selected by remember(pending) { mutableStateOf(emptySet<String>()) }
    var postfixes by remember(pending) {
        mutableStateOf(pending.sources.associate { it.id to "part" })
    }
    var counts by remember(pending) {
        mutableStateOf(pending.sources.associate { it.id to state.defaultSplitPartCount(it) })
    }

    fun chooseDestination() {
        state.pickDirectory("Choose Split Destination", File(destination))?.let { chosen ->
            destination = chosen.absolutePath
        }
    }

    fun splitPartOptions(value: Int): List<Int> =
        (listOf(1, 2, 3, 4, 5, 6, 8, 10, 12, 16, 24, 32) + value.coerceAtLeast(1)).distinct().sorted()

    fun confirm(splitIds: Set<String>) {
        state.confirmSplitPrompt(
            modes = pending.sources.associate { source ->
                source.id to if (source.id in splitIds) SplitMode.SPLIT else SplitMode.OPEN_AS_IS
            },
            destinationDir = File(destination),
            postfix = "part",
            partCounts = counts,
            postfixes = postfixes,
        )
    }

    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(dismissOnClickOutside = false)) {
        Column(
            Modifier.width(if (isSingleSource) 520.dp else 620.dp).background(tc.p, RoundedCornerShape(8.dp))
                .border(1.dp, tc.br, RoundedCornerShape(8.dp)).padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            AppText(
                if (isSingleSource) "Split large log file" else "Split large log files",
                color = tc.tx,
                fontSize = 14.sp,
                fontWeight = FontWeight.SemiBold,
            )
            AppText(
                if (isSingleSource) {
                    "This file is large. Split it into smaller files or open the original as-is."
                } else {
                    "Choose which files should be split. Unchecked files will open as-is."
                },
                color = tc.td,
                fontSize = 11.sp,
                maxLines = 2,
            )
            Column(
                Modifier.fillMaxWidth().heightIn(max = 260.dp).verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                pending.sources.forEach { source ->
                    Column(
                        Modifier.fillMaxWidth().background(tc.bg, CORNER_MD)
                            .border(1.dp, tc.br, CORNER_MD).padding(10.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        if (isSingleSource) {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(8.dp),
                            ) {
                                AppText(
                                    source.displayName,
                                    color = tc.tx,
                                    fontSize = 12.sp,
                                    fontFamily = MONO,
                                    modifier = Modifier.weight(1f),
                                )
                                AppText(formatByteSize(source.sizeBytes), color = tc.td, fontSize = 11.sp, fontFamily = MONO)
                            }
                        } else {
                            CheckRow(
                                checked = source.id in selected,
                                onToggle = {
                                    selected = if (source.id in selected) selected - source.id else selected + source.id
                                },
                            ) {
                                AppText(
                                    source.displayName,
                                    color = tc.tx,
                                    fontSize = 12.sp,
                                    fontFamily = MONO,
                                    modifier = Modifier.weight(1f),
                                )
                                AppText(formatByteSize(source.sizeBytes), color = tc.td, fontSize = 11.sp, fontFamily = MONO)
                            }
                        }
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            AppText("Parts", color = tc.td, fontSize = 10.sp, fontWeight = FontWeight.SemiBold)
                            ListStepper(
                                options = splitPartOptions(counts[source.id] ?: 1),
                                value = counts[source.id] ?: 1,
                                onChange = { value -> counts = counts + (source.id to value) },
                            )
                            AppText("Postfix", color = tc.td, fontSize = 10.sp, fontWeight = FontWeight.SemiBold)
                            SplitDialogTextField(
                                value = postfixes[source.id].orEmpty(),
                                onValueChange = { value -> postfixes = postfixes + (source.id to value) },
                                modifier = Modifier.width(if (isSingleSource) 180.dp else 130.dp),
                            )
                        }
                    }
                }
            }
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                AppText("Destination", color = tc.td, fontSize = 10.sp, fontWeight = FontWeight.SemiBold)
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    SplitDialogTextField(
                        value = destination,
                        onValueChange = { destination = it },
                        modifier = Modifier.weight(1f),
                    )
                    AppButton("Browse", onClick = ::chooseDestination, variant = ButtonVariant.Secondary)
                }
            }
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterHorizontally),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                if (isSingleSource) {
                    DialogActionButton("Split", active = true) {
                        confirm(setOf(firstSource.id))
                    }
                    DialogActionButton("Do Not Split", active = false) {
                        confirm(emptySet())
                    }
                } else {
                    DialogActionButton("Split All", active = true) {
                        confirm(pending.sources.map { it.id }.toSet())
                    }
                    DialogActionButton(
                        "Split Selected",
                        active = selected.isNotEmpty(),
                        enabled = selected.isNotEmpty(),
                    ) {
                        confirm(selected)
                    }
                }
                DialogActionButton("Cancel", active = false, onClick = onDismiss)
            }
        }
    }
}

@Composable
internal fun SplitDialogTextField(
    value: String,
    onValueChange: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val tc = tc()
    BasicTextField(
        value = value,
        onValueChange = onValueChange,
        singleLine = true,
        textStyle = TextStyle(color = tc.tx, fontSize = 11.sp, fontFamily = MONO),
        cursorBrush = SolidColor(tc.ac),
        modifier = modifier
            .height(32.dp)
            .background(tc.bg, CORNER_MD)
            .border(1.dp, tc.br, CORNER_MD)
            .padding(horizontal = 8.dp, vertical = 7.dp),
    )
}

// How long isLoading must stay continuously true before the watchdog offers to intervene. Large
// real files legitimately take a few seconds; 30s is comfortably past that so this doesn't fire
// on normal big-file loads, only on something that's actually stuck.
internal const val STUCK_LOADING_PROMPT_DELAY_MS = 30_000L

@Composable
internal fun StuckLoadingDialog(
    status: String?,
    onCancelLoading: () -> Unit,
    onCloseAllTabs: () -> Unit,
    onClearCache: () -> Unit,
    onKeepWaiting: () -> Unit,
) {
    val tc = tc()
    Dialog(onDismissRequest = onKeepWaiting) {
        Column(
            Modifier.width(360.dp).background(tc.p, RoundedCornerShape(8.dp))
                .border(1.dp, tc.br, RoundedCornerShape(8.dp)).padding(20.dp),
        ) {
            AppText("Still loading…", color = tc.tx, fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
            Spacer(Modifier.height(6.dp))
            AppText(
                "This has been loading for a while" + (status?.let { " ($it)" } ?: "") +
                    ". If it looks stuck, you can:",
                color = tc.td,
                fontSize = 11.sp,
                maxLines = 4,
            )
            Spacer(Modifier.height(14.dp))
            AppButton("Cancel loading", onClick = onCancelLoading, modifier = Modifier.fillMaxWidth())
            Spacer(Modifier.height(6.dp))
            AppButton("Close all tabs", onClick = onCloseAllTabs, isDanger = true, modifier = Modifier.fillMaxWidth())
            Spacer(Modifier.height(6.dp))
            AppButton("Clear temporary data…", onClick = onClearCache, modifier = Modifier.fillMaxWidth())
            Spacer(Modifier.height(10.dp))
            AppButton("Keep waiting", onClick = onKeepWaiting, variant = ButtonVariant.Ghost, modifier = Modifier.fillMaxWidth())
        }
    }
}

@Composable
internal fun DialogActionButton(
    label: String,
    active: Boolean,
    danger: Boolean = false,
    enabled: Boolean = true,
    onClick: () -> Unit,
) {
    val tc = tc()
    val accent = if (danger) DANGER_RED else tc.ac
    val shape = RoundedCornerShape(5.dp)
    Box(
        Modifier
            .width(132.dp)
            .height(38.dp)
            .border(1.dp, if (active) accent else tc.br, shape)
            .background(if (active) accent.copy(.18f) else Color.Transparent, shape)
            .clip(shape)
            .clickable(enabled = enabled, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        AppText(label, color = if (active) accent else tc.ts, fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
    }
}

@Composable
internal fun KeyboardShortcutsDialog(onDismiss: () -> Unit) {
    val tc = tc()
    val scroll = rememberScrollState()
    val shape = RoundedCornerShape(8.dp)
    val groups = keyboardShortcutHelpGroups()

    // Split the groups across 3 columns, balanced by row count, so every shortcut is visible
    // without scrolling instead of relying on an invisible overflow scrollbar.
    val columns = splitShortcutGroupsIntoColumns(groups, 3).filter { it.isNotEmpty() }

    @Composable
    fun PageColumn(page: List<ShortcutHelpGroup>, modifier: Modifier) {
        Column(modifier, verticalArrangement = Arrangement.spacedBy(14.dp)) {
            page.forEach { group ->
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    AppText(
                        group.title,
                        color = tc.td,
                        fontSize = 10.sp,
                        fontFamily = UI,
                        fontWeight = FontWeight.SemiBold,
                    )
                    group.rows.forEach { row ->
                        Row(
                            Modifier.fillMaxWidth().padding(vertical = 1.dp),
                            horizontalArrangement = Arrangement.spacedBy(10.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Box(
                                Modifier
                                    .widthIn(min = 130.dp)
                                    .background(tc.p2, RoundedCornerShape(4.dp))
                                    .border(0.5.dp, tc.br, RoundedCornerShape(4.dp))
                                    .padding(horizontal = 8.dp, vertical = 2.dp),
                                contentAlignment = Alignment.CenterStart,
                            ) {
                                AppText(row.label, color = tc.tx, fontSize = 11.sp, fontFamily = MONO)
                            }
                            AppText(
                                row.description,
                                color = tc.ts,
                                fontSize = 11.sp,
                                modifier = Modifier.weight(1f),
                                maxLines = 2,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                    }
                }
            }
        }
    }

    Box(
        Modifier
            .width(1150.dp)
            .heightIn(max = 640.dp)
            .clip(shape)
            .background(tc.p)
            .border(1.dp, tc.br, shape),
    ) {
        Column(
            Modifier.verticalScroll(scroll).padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                AppText("Keyboard Shortcuts", color = tc.tx, fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
                CloseButton(onClick = onDismiss)
            }
            Row(Modifier.fillMaxWidth()) {
                columns.forEachIndexed { idx, page ->
                    PageColumn(
                        page,
                        Modifier.weight(1f).padding(
                            start = if (idx == 0) 0.dp else 20.dp,
                            end = if (idx == columns.lastIndex) 0.dp else 20.dp,
                        ),
                    )
                    if (idx != columns.lastIndex) {
                        Box(Modifier.width(1.dp).fillMaxHeight().background(tc.br))
                    }
                }
            }
            Row(horizontalArrangement = Arrangement.End, modifier = Modifier.fillMaxWidth()) {
                AppButton("Done", onClick = onDismiss, variant = ButtonVariant.Primary)
            }
        }
    }
}
