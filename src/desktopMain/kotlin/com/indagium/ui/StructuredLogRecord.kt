@file:OptIn(androidx.compose.ui.ExperimentalComposeUiApi::class)

package com.indagium.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.relocation.BringIntoViewRequester
import androidx.compose.foundation.relocation.bringIntoViewRequester
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.isPrimaryPressed
import androidx.compose.ui.input.pointer.onPointerEvent
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.indagium.model.AppSettings
import com.indagium.model.LogEntry
import com.indagium.model.LogFormat
import com.indagium.model.RecordFieldNameColor
import com.indagium.model.RecordValueAlignment

private const val MAX_JSON_CHARS = 256 * 1024
private const val MAX_JSON_DEPTH = 32
private const val MAX_JSON_CHILDREN = 128
private const val MAX_JSON_STRING_CHARS = 8 * 1024
private const val MAX_RECORD_PREVIEW_CHARS = 180
private const val MAX_RAW_LINES = 80
private const val MAX_RAW_LINE_CHARS = 600
private const val MAX_VALUE_PREVIEW_CHARS = 220
private const val MIN_KEY_CHARS = 4
private const val MAX_KEY_CHARS = 28
private const val KEY_CHAR_WIDTH_DP = 6.7f
private const val KEY_GAP_DP = 14f
private const val INDENT_PER_DEPTH_DP = 16
private const val RECORD_FONT_SP = 11

/** The most of a row an "after the name" field name may take; a longer name is ellipsized so its value always has room. */
private const val MAX_KEY_ROW_FRACTION = 0.45f
private val NODE_ROW_HEIGHT = 16.dp

/** The space under each field row, drawn inside the row's own fill so selected neighbours join up. */
private val FIELD_ROW_GAP = 1.dp

/** Width of a tree row's toggle slot (and of the empty slot of a leaf); the key column maths counts it as 20dp of indent. */
private val TREE_TOGGLE_WIDTH = 20.dp
private val HEX_SUMMARY_GAP = 8.dp

/** Width a row gives the disclosure arrow, arrow or not: the 24dp button and its 2dp gap. */
internal val STRUCTURED_DISCLOSURE_SLOT = 26.dp

/** How a record's field names are coloured: the theme accent, the plain secondary text colour, or a colour the user picked. */
internal data class RecordFieldNameStyle(val mode: RecordFieldNameColor = RecordFieldNameColor.ACCENT, val custom: Color? = null)

/** The field-name colour setting for the record tree and Message details; [App] provides it from the app settings. */
internal val LocalRecordFieldNameStyle = staticCompositionLocalOf { RecordFieldNameStyle() }

/** The style the settings ask for; a Custom choice without a stored colour reads as the accent. */
internal fun AppSettings.recordFieldNameStyle(): RecordFieldNameStyle = RecordFieldNameStyle(
    mode = recordFieldNameColor,
    custom = recordFieldNameCustomArgb?.let { Color(it.toInt()) },
)

/** The colour a field name is drawn in under [style]. */
internal fun recordFieldNameColor(style: RecordFieldNameStyle, tc: ThemeColors): Color = when (style.mode) {
    RecordFieldNameColor.ACCENT -> tc.ac
    RecordFieldNameColor.PLAIN -> tc.ts
    RecordFieldNameColor.CUSTOM -> style.custom ?: tc.ac
}

/** Where field values start in the record tree and Message details; [App] provides it from the app settings. */
internal val LocalRecordValueAlignment = staticCompositionLocalOf { RecordValueAlignment.AFTER_NAME }

/** How one tree places its values: [alignment], the shared [keyColumnDp] (COLUMN only) and the width of one space in [gapPx]. */
private class RecordValueLayout(val alignment: RecordValueAlignment, val keyColumnDp: Float, val gapPx: Int)

/** Returns the profile/DLT payload eligible for the inline record disclosure. */
internal fun expandableRecordSource(entry: LogEntry, logFormat: LogFormat): String? {
    val raw = entry.rawRecord
    if (raw != null && entry.recordMetadata?.usesProfilePresentation == true) {
        // A line that only starts with "[" (dmesg's "[    1.500000] …") is text, not a JSON array: isJsonRecord parses it.
        if (raw.contains('\n') || raw.contains('\r') || isJsonRecord(raw)) return raw
    }
    if (logFormat == LogFormat.DLT && (entry.msg.contains('\n') || entry.msg.contains('\r'))) {
        return raw ?: entry.msg
    }
    return null
}

/** Normalizes embedded physical breaks and keeps collapsed profile/DLT summaries compact. */
internal fun compactRecordSummary(text: androidx.compose.ui.text.AnnotatedString): androidx.compose.ui.text.AnnotatedString {
    if (text.text.length <= MAX_RECORD_PREVIEW_CHARS && '\n' !in text.text && '\r' !in text.text) return text
    val source = text.subSequence(0, minOf(text.length, MAX_RECORD_PREVIEW_CHARS))
    val normalizedText = source.text.map { if (it == '\n' || it == '\r') ' ' else it }.joinToString("")
    return androidx.compose.ui.text.buildAnnotatedString {
        append(normalizedText)
        source.spanStyles.forEach { addStyle(it.item, it.start, it.end) }
        val truncated = text.length > MAX_RECORD_PREVIEW_CHARS
        // A paragraph style that ran to the end of the preview must also cover the " …" after it, or
        // that suffix would become a paragraph of its own and drop to a new line.
        val suffix = " …"
        val paragraphEnd = normalizedText.length + if (truncated) suffix.length else 0
        source.paragraphStyles.forEach {
            addStyle(it.item, it.start, if (it.end >= normalizedText.length) paragraphEnd else it.end)
        }
        if (truncated) {
            pushStyle(androidx.compose.ui.text.SpanStyle(color = Color.Unspecified))
            append(suffix)
            pop()
        }
    }
}

/**
 * Whether a row's disclosure chevron is drawn: only while the row is hovered or selected, or while
 * the record is open (so an open record always shows how to close it). A hidden chevron still holds
 * its slot, stays clickable and keeps its semantics.
 */
internal fun structuredDisclosureVisible(hovered: Boolean, selected: Boolean, expanded: Boolean): Boolean =
    hovered || selected || expanded

/** Root disclosure chevron. Its click is kept separate from the log row's selection gesture. */
@Composable
internal fun StructuredRecordDisclosure(
    entryId: Int,
    expanded: Boolean,
    color: Color,
    modifier: Modifier = Modifier,
    visible: Boolean = true,
    onBoundsChanged: (Float, Float) -> Unit = { _, _ -> },
    onClick: () -> Unit,
) {
    val tc = tc()
    CollapseChevron(
        expanded = expanded,
        color = color.takeUnless { it == Color.Unspecified } ?: tc.ts,
        onClick = onClick,
        modifier = modifier.size(width = 24.dp, height = 16.dp)
            .alpha(if (visible) 1f else 0f)
            .onGloballyPositioned { coordinates ->
                val left = coordinates.positionInRoot().x
                onBoundsChanged(left, left + coordinates.size.width)
            }
            .semantics { contentDescription = if (expanded) "Collapse record" else "Expand record" }
            .testTag("structured-record-disclosure-$entryId"),
    )
}

/**
 * Which containers of a tree start open, besides every one when it is [initiallyExpanded]: those within [depth] levels of the root
 * (a note shows the first level of a record open), and each field of [paths] with the way down to it. [nonce] changes with every new
 * request, so a tree that already composed applies it again. [scrollTo] is the field a "Go to rows" request brings into view.
 */
private class TreeOpen(val depth: Int, val paths: Set<List<String>>, val nonce: Int, val scrollTo: List<String>? = null) {
    /** [segments] is a chosen field or on the way to one. */
    fun covers(segments: List<String>): Boolean =
        paths.isNotEmpty() && paths.any { it.size >= segments.size && it.subList(0, segments.size) == segments }
}

/** What a tree's rows share: fonts and colours, how values are placed, what starts open and, in the log view, the field menu's wiring. */
private class TreeEnv(
    val mono: FontFamily,
    val tc: ThemeColors,
    val initiallyExpanded: Boolean,
    val valueLayout: RecordValueLayout,
    val open: TreeOpen,
    val fieldContext: RecordFieldContext?,
)

/**
 * Inline body used under the one logical parent row. The tree is parsed only after expansion.
 *
 * [openDepth], [openPaths] and [openNonce] say what starts open (see [TreeOpen]). With a [recordTabId] and the log view's
 * [LocalRecordFieldActions] the rows can be picked (Ctrl/Cmd-click) and add fields to the notes (right-click menu), and a "Go to rows"
 * request for this record opens its fields.
 */
@Composable
internal fun StructuredRecordBody(
    entryId: Int,
    source: String,
    mono: FontFamily,
    startIndent: androidx.compose.ui.unit.Dp = 46.dp,
    modifier: Modifier = Modifier,
    initiallyExpanded: Boolean = false,
    rootPath: String = "$entryId.root",
    bodyTag: String = "structured-record-body-$entryId",
    openDepth: Int = 0,
    openPaths: Set<List<String>> = emptySet(),
    openNonce: Int = 0,
    recordTabId: String? = null,
) {
    val tc = tc()
    val tree = remember(source) { runCatching { BoundedJsonReader(source).parseRoot() }.getOrNull() }
    val alignment = LocalRecordValueAlignment.current
    val keyColumn = remember(tree, alignment) {
        if (alignment == RecordValueAlignment.COLUMN) tree?.let { structuredKeyColumnDp(it) } ?: 0f else 0f
    }
    val gapPx = monoSpaceWidthPx(mono)
    val valueLayout = remember(alignment, keyColumn, gapPx) { RecordValueLayout(alignment, keyColumn, gapPx) }
    val actions = LocalRecordFieldActions.current
    val probe = remember { TreeSelectionProbe() }
    val geometry = remember { RecordTreeGeometry() }
    // The tree's own keyboard focus target: a click on a row takes it, and only while it is in the tree do the arrow keys and Copy act on
    // the picked rows (see RecordFieldKeyboard.kt).
    val treeFocus = remember { FocusRequester() }
    var treeHasFocus by remember { mutableStateOf(false) }
    val focusTree = remember(treeFocus) { { runCatching { treeFocus.requestFocus() }; Unit } }
    // A row drag clears the text selection the press began: leaving the container's focus releases it, and the focus comes straight back.
    val focusManager = LocalFocusManager.current
    val clearTextSelection = remember(treeFocus, focusManager) {
        {
            focusManager.clearFocus(force = true)
            runCatching { treeFocus.requestFocus() }
            Unit
        }
    }
    val scroller = LocalRecordTreeScroller.current
    val currentScroller by rememberUpdatedState(scroller)
    val fieldContext = if (recordTabId != null && actions != null) {
        RecordFieldContext(actions, recordTabId, entryId, actions.selectedPaths(recordTabId, entryId), source, probe, geometry, focusTree, clearTextSelection)
    } else {
        null
    }
    val currentFieldContext by rememberUpdatedState(fieldContext)
    val currentTreeHasFocus by rememberUpdatedState(treeHasFocus)
    if (fieldContext != null) {
        DisposableEffect(actions, recordTabId, entryId) {
            val handle = RecordTreeHandle(
                tabId = recordTabId.orEmpty(),
                entryId = entryId,
                geometry = geometry,
                probe = probe,
                hasFocus = { currentTreeHasFocus },
                onKey = { event -> currentFieldContext?.let { handleRecordTreeKey(it, event) } == true },
            )
            val unregister = actions?.registerTree(handle)
            onDispose { unregister?.invoke() }
        }
    }
    val request = if (recordTabId != null) actions?.openRequest(recordTabId, entryId) else null
    val scrollTo = remember(tree, request?.nonce) { if (request != null && tree != null) firstFieldOf(tree, request.paths) else null }
    val env = TreeEnv(
        mono = mono,
        tc = tc,
        initiallyExpanded = initiallyExpanded,
        valueLayout = valueLayout,
        open = TreeOpen(openDepth, request?.paths ?: openPaths, request?.nonce ?: openNonce, scrollTo),
        fieldContext = fieldContext,
    )
    ProvideSelectionProbe(probe) {
        SelectionContainer(
            modifier = modifier.fillMaxWidth()
                .padding(start = startIndent, end = 12.dp, bottom = 5.dp)
                .testTag(bodyTag)
                .then(
                    if (fieldContext != null) {
                        // No focusable of its own: the selection container's is the tree's focus target (a second one in front of it
                        // stopped the container from selecting text).
                        Modifier.onFocusChanged { treeHasFocus = it.hasFocus }
                            .focusRequester(treeFocus)
                            // Reached when a host with no key handling of its own above the tree holds focus here; the log view's
                            // FileView hands keys to the tree before its own row navigation sees them.
                            .onPreviewKeyEvent { event -> currentFieldContext?.let { handleRecordTreeKey(it, event) } == true }
                            .trackTextSelection(geometry, scroller = { currentScroller }) { currentFieldContext }
                    } else {
                        Modifier
                    },
                ),
        ) {
            // No spacing here: each field row carries its own FIELD_ROW_GAP inside its fill (see StructuredContainerRows).
            Column {
                if (tree is StructuredJsonValue.ObjectValue || tree is StructuredJsonValue.ArrayValue) {
                    StructuredContainerRows(tree, path = rootPath, segments = emptyList(), depth = 0, env = env)
                } else {
                    RawRecordLines(entryId = entryId, source = source, mono = mono, tc = tc)
                }
            }
        }
    }
}

/** The first of [paths] met walking [root] in the order its rows are drawn (a field before what it holds), or null. */
private fun firstFieldOf(root: StructuredJsonValue, paths: Set<List<String>>): List<String>? {
    fun visit(value: StructuredJsonValue, segments: List<String>): List<String>? {
        val children = when (value) {
            is StructuredJsonValue.ObjectValue -> value.fields
            is StructuredJsonValue.ArrayValue -> value.values.mapIndexed { index, child -> "[$index]" to child }
            else -> return null
        }
        for ((label, child) in children) {
            val path = segments + label
            if (path in paths) return path
            visit(child, path)?.let { return it }
        }
        return null
    }
    return visit(root, emptyList())
}

/** Whether [source] is a JSON object or array the field tree can show (within its size and depth limits). */
internal fun isStructuredJsonRecord(source: String): Boolean {
    val first = source.firstOrNull { !it.isWhitespace() }
    if (first != '{' && first != '[') return false
    val tree = runCatching { BoundedJsonReader(source).parseRoot() }.getOrNull()
    return tree is StructuredJsonValue.ObjectValue || tree is StructuredJsonValue.ArrayValue
}

@Composable
private fun StructuredContainerRows(
    value: StructuredJsonValue,
    path: String,
    segments: List<String>,
    depth: Int,
    env: TreeEnv,
) {
    val mono = env.mono
    val tc = env.tc
    val initiallyExpanded = env.initiallyExpanded
    val valueLayout = env.valueLayout
    val fieldContext = env.fieldContext
    val children = when (value) {
        is StructuredJsonValue.ObjectValue -> value.fields.map { it.first to it.second }
        is StructuredJsonValue.ArrayValue -> value.values.mapIndexed { index, child -> "[$index]" to child }
        else -> emptyList()
    }
    val truncated = when (value) {
        is StructuredJsonValue.ObjectValue -> value.truncated
        is StructuredJsonValue.ArrayValue -> value.truncated
        else -> false
    }
    // COLUMN: one key column for the whole tree (keyColumnDp, measured from the row's own start), so every
    // value starts at the same x whatever its depth; a row already spends depth * indent of it.
    val keyWidth = (valueLayout.keyColumnDp - depth * INDENT_PER_DEPTH_DP).dp
    val keyColor = recordFieldNameColor(LocalRecordFieldNameStyle.current, tc)
    children.forEach { (label, child) ->
        val childPath = "$path/$label"
        val container = child is StructuredJsonValue.ObjectValue || child is StructuredJsonValue.ArrayValue
        val multilineText = child is StructuredJsonValue.StringValue && recordLines(child.value).size > 1
        // Bytes spelled in hex read as a dump, not one long wrapped run.
        val hexBytes = remember(childPath, child) { (child as? StructuredJsonValue.StringValue)?.let { parseHexBytes(it.value) } }
        val expandable = container || multilineText || hexBytes != null
        val fieldPath = segments + label
        val startsOpen = initiallyExpanded || (container && depth < env.open.depth) || env.open.covers(fieldPath)
        val expandedState = remember(childPath, env.open.nonce) { mutableStateOf(startsOpen) }
        var expanded by expandedState
        val picked = fieldContext != null && fieldPath in fieldContext.selected
        var hovered by remember(childPath) { mutableStateOf(false) }
        var menuAt by remember(childPath) { mutableStateOf<Offset?>(null) }
        val currentContext by rememberUpdatedState(fieldContext)
        if (fieldContext != null) {
            // The keyboard opens and closes the row through this (Left/Right).
            val control = remember(expandedState, expandable, depth) {
                RowControl(expandable, expandedState, (depth * INDENT_PER_DEPTH_DP).dp + TREE_TOGGLE_WIDTH)
            }
            DisposableEffect(fieldContext.geometry, fieldPath, control) {
                fieldContext.geometry.setControl(fieldPath, control)
                onDispose { fieldContext.geometry.clearControl(fieldPath, control) }
            }
        }
        // "Go to rows" brings the first chosen field into view, however tall the record is.
        val scrollTarget = env.open.scrollTo == fieldPath
        val bringIntoView = remember { BringIntoViewRequester() }
        if (scrollTarget) {
            LaunchedEffect(env.open.nonce) {
                withFrameNanos { }
                bringIntoView.bringIntoView()
            }
        }
        // The keyboard moved to this row: keep it on screen however far the record is scrolled.
        val reveal = fieldContext?.geometry?.reveal?.takeIf { it.path == fieldPath }
        if (reveal != null) {
            LaunchedEffect(reveal.nonce) {
                withFrameNanos { }
                bringIntoView.bringIntoView()
            }
        }
        Row(
            modifier = Modifier.fillMaxWidth()
                .then(if (scrollTarget || reveal != null) Modifier.bringIntoViewRequester(bringIntoView) else Modifier)
                // A picked row has the fill a selected log row has, a hovered one the hover tint (never both).
                .then(
                    when {
                        picked -> Modifier.background(tc.sl)
                        hovered && fieldContext != null -> Modifier.background(tc.hv)
                        else -> Modifier
                    },
                )
                .trackFieldRow(fieldContext?.geometry, fieldPath)
                .then(
                    if (fieldContext != null) {
                        Modifier.onPointerEvent(PointerEventType.Enter) { hovered = true }
                            .onPointerEvent(PointerEventType.Exit) { hovered = false }
                            .recordFieldGestures(
                                key = childPath,
                                onToggle = { currentContext?.toggleRow(fieldPath) },
                                onMenu = {
                                    currentContext?.selectForMenu(fieldPath)
                                    menuAt = it
                                },
                                onPlainPress = { currentContext?.anchorAt(fieldPath) },
                                onClick = { repeated -> currentContext?.clickSelect(fieldPath, takeFocus = !repeated) },
                                clickStart = (depth * INDENT_PER_DEPTH_DP).dp + TREE_TOGGLE_WIDTH,
                            )
                    } else {
                        Modifier
                    },
                )
                // The 1dp between rows is inside each row, under its fill, so neighbouring picked rows read as one block.
                .padding(start = (depth * INDENT_PER_DEPTH_DP).dp, end = 3.dp, bottom = FIELD_ROW_GAP)
                // One node per field: a screen reader hears "key value" together.
                .semantics(mergeDescendants = true) {}
                .then(if (expandable) Modifier.toggleOnDoubleClick(childPath) { expanded = !expanded } else Modifier)
                .testTag("structured-record-node-${pathTag(childPath)}"),
            verticalAlignment = Alignment.Top,
        ) {
            if (expandable) {
                // The same rounded-hover chevron as the log row's record arrow and the group headers.
                CollapseChevron(
                    expanded = expanded,
                    color = tc.ts,
                    onClick = { expanded = !expanded },
                    modifier = Modifier.size(width = TREE_TOGGLE_WIDTH, height = NODE_ROW_HEIGHT)
                        .semantics { contentDescription = if (expanded) "Collapse $label" else "Expand $label" }
                        .testTag("structured-record-node-toggle-${pathTag(childPath)}"),
                )
            } else {
                // Same height as a toggle, so rows with and without one are evenly spaced.
                Spacer(Modifier.size(width = TREE_TOGGLE_WIDTH, height = NODE_ROW_HEIGHT))
            }
            KeyAndValue(
                valueLayout = valueLayout,
                keyWidth = keyWidth,
                key = { keyModifier ->
                    AppText(
                        label,
                        color = keyColor,
                        fontSize = RECORD_FONT_SP.sp,
                        fontFamily = mono,
                        fontWeight = FontWeight.Medium,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = keyModifier,
                    )
                },
                value = { valueModifier ->
                    if (hexBytes != null) {
                        StructuredHexSummary(hexBytes, mono, tc, wrap = initiallyExpanded, modifier = valueModifier)
                    } else {
                        val valueText = if (initiallyExpanded && child is StructuredJsonValue.StringValue && !multilineText) {
                            // A one-line value that ends in its line break (Docker's `log`) shows without an empty line under it.
                            recordLines(child.value).single()
                        } else {
                            scalarOrContainerSummary(child)
                        }
                        AppText(
                            valueText,
                            color = jsonValueColor(child, tc),
                            fontSize = RECORD_FONT_SP.sp,
                            fontFamily = mono,
                            maxLines = if (initiallyExpanded) Int.MAX_VALUE else 2,
                            overflow = TextOverflow.Ellipsis,
                            modifier = valueModifier,
                        )
                    }
                },
            )
            menuAt?.let { at -> if (fieldContext != null) RecordFieldMenu(fieldContext, fieldPath, at) { menuAt = null } }
        }
        if (expandable && expanded) {
            when {
                container -> StructuredContainerRows(child, childPath, fieldPath, depth + 1, env)
                hexBytes != null -> FieldValueArea(fieldContext, fieldPath, childPath, picked) {
                    StructuredHexDump(
                        entryId = path.substringBefore('.').toIntOrNull() ?: 0,
                        path = childPath,
                        bytes = hexBytes,
                        depth = depth + 1,
                        mono = mono,
                        tc = tc,
                    )
                }
                child is StructuredJsonValue.StringValue -> FieldValueArea(fieldContext, fieldPath, childPath, picked) {
                    StructuredStringLines(
                        entryId = path.substringBefore('.').toIntOrNull() ?: 0,
                        path = childPath,
                        value = child.value,
                        depth = depth + 1,
                        mono = mono,
                        tc = tc,
                        fullValues = initiallyExpanded,
                    )
                }
            }
        }
    }
    if (truncated) {
        AppText(
            "… more fields omitted",
            color = tc.ts,
            fontSize = 10.sp,
            fontFamily = mono,
            modifier = Modifier.padding(start = (depth * 16 + 20).dp, top = 2.dp),
        )
    }
}

/**
 * The expanded value of a field (its hex dump or numbered string lines) as part of that field for the field menu and the pick: a
 * right-click anywhere in it opens the field's menu, a Ctrl/Cmd-click toggles the field, and a pick tints it like the field's row.
 * Dragging still selects text. Outside the log view ([fieldContext] null) it only passes [content] through.
 */
@Composable
private fun FieldValueArea(
    fieldContext: RecordFieldContext?,
    fieldPath: List<String>,
    key: String,
    picked: Boolean,
    content: @Composable () -> Unit,
) {
    if (fieldContext == null) {
        content()
        return
    }
    var menuAt by remember(key) { mutableStateOf<Offset?>(null) }
    val currentContext by rememberUpdatedState(fieldContext)
    Box(
        Modifier.fillMaxWidth()
            .then(if (picked) Modifier.background(tc().sl) else Modifier)
            .trackFieldRow(fieldContext.geometry, fieldPath, area = true)
            .recordFieldGestures(
                key = key,
                onToggle = { currentContext.toggleRow(fieldPath) },
                onMenu = {
                    currentContext.selectForMenu(fieldPath)
                    menuAt = it
                },
                onPlainPress = { currentContext.anchorAt(fieldPath) },
                onClick = { repeated -> currentContext.clickSelect(fieldPath, takeFocus = !repeated) },
            ),
    ) {
        content()
        menuAt?.let { at -> RecordFieldMenu(fieldContext, fieldPath, at) { menuAt = null } }
    }
}

/**
 * A field's key and value. COLUMN: the key is a fixed [keyWidth] and the value fills the rest. AFTER_NAME: the key takes its
 * natural width (at most [MAX_KEY_ROW_FRACTION] of the row, ellipsized beyond) and the value starts one space-gap after it,
 * wrapping under itself.
 */
@Composable
private fun RowScope.KeyAndValue(
    valueLayout: RecordValueLayout,
    keyWidth: androidx.compose.ui.unit.Dp,
    key: @Composable (Modifier) -> Unit,
    value: @Composable (Modifier) -> Unit,
) {
    if (valueLayout.alignment == RecordValueAlignment.COLUMN) {
        Row(Modifier.weight(1f), verticalAlignment = Alignment.Top) {
            key(Modifier.width(keyWidth))
            value(Modifier.weight(1f))
        }
    } else {
        val gapPx = valueLayout.gapPx
        Layout(
            content = {
                key(Modifier)
                value(Modifier)
            },
            modifier = Modifier.weight(1f),
        ) { measurables, constraints ->
            val rowWidth = constraints.maxWidth
            val bounded = rowWidth != Constraints.Infinity
            val keyMax = if (bounded) (rowWidth * MAX_KEY_ROW_FRACTION).toInt() else Constraints.Infinity
            // A key longer than its share of the row (journald's __REALTIME_TIMESTAMP in a narrow tile) is shown in full
            // and its value goes on the next line, indented, rather than the key being cut with "…".
            if (bounded && measurables[0].maxIntrinsicWidth(Constraints.Infinity) > keyMax) {
                val keyPlaceable = measurables[0].measure(Constraints(maxWidth = rowWidth))
                val indent = 2 * gapPx
                val valuePlaceable = measurables[1].measure(Constraints(maxWidth = (rowWidth - indent).coerceAtLeast(0)))
                return@Layout layout(rowWidth, keyPlaceable.height + valuePlaceable.height) {
                    keyPlaceable.placeRelative(0, 0)
                    valuePlaceable.placeRelative(indent, keyPlaceable.height)
                }
            }
            val keyPlaceable = measurables[0].measure(Constraints(maxWidth = keyMax))
            val valueMax = if (bounded) (rowWidth - keyPlaceable.width - gapPx).coerceAtLeast(0) else Constraints.Infinity
            val valuePlaceable = measurables[1].measure(Constraints(maxWidth = valueMax))
            val valueX = keyPlaceable.width + gapPx
            val width = if (bounded) rowWidth else valueX + valuePlaceable.width
            layout(width, maxOf(keyPlaceable.height, valuePlaceable.height)) {
                keyPlaceable.placeRelative(0, 0)
                valuePlaceable.placeRelative(valueX, 0)
            }
        }
    }
}

/** The width in px of one space of [mono] at the tree's font size, measured as "a a" minus "aa" (a trailing space may not count). */
@Composable
private fun monoSpaceWidthPx(mono: FontFamily): Int {
    val measurer = rememberTextMeasurer()
    val density = LocalDensity.current
    return remember(mono, density) {
        val style = TextStyle(fontFamily = mono, fontSize = RECORD_FONT_SP.sp)
        val spaced = measurer.measure("a a", style, softWrap = false, maxLines = 1).size.width
        val tight = measurer.measure("aa", style, softWrap = false, maxLines = 1).size.width
        (spaced - tight).coerceAtLeast(0)
    }
}

@Composable
private fun RawRecordLines(entryId: Int, source: String, mono: FontFamily, tc: ThemeColors) {
    val lines = remember(source) { source.lineSequence().take(MAX_RAW_LINES + 1).toList() }
    lines.take(MAX_RAW_LINES).forEachIndexed { index, line ->
        AppText(
            line.take(MAX_RAW_LINE_CHARS),
            color = tc.tx,
            fontSize = 11.sp,
            fontFamily = mono,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.fillMaxWidth()
                .padding(start = 20.dp)
                .testTag("structured-record-raw-line-$entryId-$index"),
        )
    }
    if (lines.size > MAX_RAW_LINES) {
        AppText(
            "… ${lines.size - MAX_RAW_LINES} more lines omitted",
            color = tc.ts,
            fontSize = 10.sp,
            fontFamily = mono,
            modifier = Modifier.padding(start = 20.dp),
        )
    }
}

@Composable
private fun StructuredStringLines(
    entryId: Int,
    path: String,
    value: String,
    depth: Int,
    mono: FontFamily,
    tc: ThemeColors,
    fullValues: Boolean = false,
) {
    val lines = remember(value) { recordLines(value) }
    val visible = lines.take(MAX_RAW_LINES)
    Column(Modifier.padding(start = (depth * 16 + 20).dp)) {
        visible.forEachIndexed { index, line ->
            Row(
                Modifier.fillMaxWidth()
                    .semantics(mergeDescendants = true) {}
                    .testTag("structured-record-string-line-$entryId-${pathTag(path)}-$index"),
                verticalAlignment = Alignment.Top,
            ) {
                AppText(
                    (index + 1).toString(),
                    color = tc.ts,
                    fontSize = 10.sp,
                    fontFamily = mono,
                    modifier = Modifier.width(30.dp),
                )
                AppText(
                    if (fullValues) line else line.take(MAX_RAW_LINE_CHARS),
                    color = tc.tx,
                    fontSize = 11.sp,
                    fontFamily = mono,
                    maxLines = if (fullValues) Int.MAX_VALUE else 2,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f),
                )
            }
        }
        if (lines.size > MAX_RAW_LINES) {
            AppText("… ${lines.size - MAX_RAW_LINES} more lines omitted", color = tc.ts, fontSize = 10.sp)
        }
    }
}

/** A hex field's line: the bytes as quoted text when they decode to printable UTF-8, then "hex · N bytes" in dim text. */
@Composable
private fun StructuredHexSummary(bytes: ByteArray, mono: FontFamily, tc: ThemeColors, wrap: Boolean, modifier: Modifier = Modifier) {
    val decoded = remember(bytes) { decodeHexText(bytes) }
    Row(modifier, horizontalArrangement = Arrangement.spacedBy(HEX_SUMMARY_GAP), verticalAlignment = Alignment.Top) {
        if (decoded != null) {
            AppText(
                "\"${escapeJsonPreview(decoded)}\"",
                color = tc.tx,
                fontSize = 11.sp,
                fontFamily = mono,
                maxLines = if (wrap) Int.MAX_VALUE else 2,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f, fill = false),
            )
        }
        AppText(hexByteCountLabel(bytes.size), color = tc.td, fontSize = 11.sp, fontFamily = mono, maxLines = 1)
    }
}

/** The expanded hex field shows an offset and up to sixteen byte pairs per line. */
@Composable
private fun StructuredHexDump(entryId: Int, path: String, bytes: ByteArray, depth: Int, mono: FontFamily, tc: ThemeColors) {
    val lines = remember(bytes) { hexDumpLines(bytes) }
    // Keep byte pairs on the same line even when the tile is narrow.
    Box(
        Modifier.padding(start = (depth * INDENT_PER_DEPTH_DP + 20).dp, end = 3.dp, top = 2.dp, bottom = 2.dp)
            .fillMaxWidth()
            .background(tc.bg, CORNER_SM)
            .testTag("structured-record-hex-${pathTag(path)}"),
    ) {
        val hexScroll = rememberScrollState()
        // Room under the text for the scrollbar, when there is one, so it does not cover the last line.
        val bottom = if (hexScroll.maxValue > 0) (4 + THIN_SCROLLBAR_DP).dp else 4.dp
        Column(Modifier.horizontalScroll(hexScroll).padding(start = 8.dp, end = 8.dp, top = 4.dp, bottom = bottom)) {
            lines.take(MAX_RAW_LINES).forEachIndexed { index, line ->
                androidx.compose.material3.Text(
                    buildAnnotatedString {
                        withStyle(SpanStyle(color = tc.td)) { append(line.offset) }
                        append("  ")
                        append(line.hex)
                    },
                    color = tc.tx,
                    fontSize = 11.sp,
                    fontFamily = mono,
                    maxLines = 1,
                    softWrap = false,
                    modifier = Modifier.testTag("structured-record-hex-line-$entryId-${pathTag(path)}-$index"),
                )
            }
            if (lines.size > MAX_RAW_LINES) {
                AppText("… ${lines.size - MAX_RAW_LINES} more lines omitted", color = tc.ts, fontSize = 10.sp, fontFamily = mono)
            }
        }
        // The dump scrolls sideways in a narrow tile; the thin bar says so.
        if (hexScroll.maxValue > 0) {
            ThinHorizontalScrollbar(hexScroll, Modifier.align(Alignment.BottomStart).fillMaxWidth().testTag("structured-record-hex-scrollbar-${pathTag(path)}"))
        }
    }
}

// One trailing line break ends the last line rather than starting an empty one: Docker's `log` field is "text\n".
private fun recordLines(value: String): List<String> =
    value.replace("\r\n", "\n").replace('\r', '\n').removeSuffix("\n").split('\n')

private fun scalarOrContainerSummary(value: StructuredJsonValue): String = when (value) {
    is StructuredJsonValue.ObjectValue -> "{${value.fields.size} fields}"
    is StructuredJsonValue.ArrayValue -> "[${value.values.size} items]"
    is StructuredJsonValue.StringValue -> if (recordLines(value.value).size > 1) {
        "${recordLines(value.value).size} lines"
    } else {
        "\"${escapeJsonPreview(value.value)}\""
    }
    is StructuredJsonValue.NumberValue -> value.value
    is StructuredJsonValue.BooleanValue -> value.value.toString()
    StructuredJsonValue.NullValue -> "null"
}

private fun escapeJsonPreview(value: String): String = buildString {
    value.take(MAX_VALUE_PREVIEW_CHARS).forEach { char ->
        append(when (char) {
            '\\' -> "\\\\"
            '"' -> "\\\""
            '\n' -> "\\n"
            '\r' -> "\\r"
            '\t' -> "\\t"
            else -> char.toString()
        })
    }
    if (value.length > MAX_VALUE_PREVIEW_CHARS) append('…')
}

private fun jsonValueColor(value: StructuredJsonValue, tc: ThemeColors): Color = when (value) {
    is StructuredJsonValue.ObjectValue, is StructuredJsonValue.ArrayValue -> tc.td
    is StructuredJsonValue.StringValue, is StructuredJsonValue.NumberValue, is StructuredJsonValue.BooleanValue -> tc.tx
    StructuredJsonValue.NullValue -> tc.td
}

/**
 * Width, in dp from a row's start, of the key column shared by every row of one tree: the widest
 * (indent of its depth + clamped key text + gap) over all nodes, array labels "[0]" included. The
 * whole parsed tree is measured, so toggling a node never moves the values.
 */
private fun structuredKeyColumnDp(root: StructuredJsonValue): Float {
    var widest = 0f

    fun visit(value: StructuredJsonValue, depth: Int) {
        val children = when (value) {
            is StructuredJsonValue.ObjectValue -> value.fields
            is StructuredJsonValue.ArrayValue -> value.values.mapIndexed { index, child -> "[$index]" to child }
            else -> return
        }
        children.forEach { (label, child) ->
            val width = depth * INDENT_PER_DEPTH_DP +
                label.length.coerceIn(MIN_KEY_CHARS, MAX_KEY_CHARS) * KEY_CHAR_WIDTH_DP + KEY_GAP_DP
            if (width > widest) widest = width
            visit(child, depth + 1)
        }
    }
    visit(root, 0)
    return widest
}

/**
 * Opens or closes a tree row on a double-click anywhere on it except its chevron (which has its own click). It only watches
 * presses and never consumes them, so the surrounding text selection keeps working.
 */
private fun Modifier.toggleOnDoubleClick(key: Any, onToggle: () -> Unit): Modifier = pointerInput(key) {
    var lastPressMs = NO_PRESS
    awaitPointerEventScope {
        while (true) {
            val event = awaitPointerEvent()
            val change = event.changes.firstOrNull()
            if (event.type != PointerEventType.Press || !event.buttons.isPrimaryPressed || change == null) continue
            if (change.position.x < TREE_TOGGLE_WIDTH.toPx()) {
                lastPressMs = NO_PRESS
                continue
            }
            if (change.uptimeMillis - lastPressMs <= viewConfiguration.doubleTapTimeoutMillis) {
                lastPressMs = NO_PRESS
                onToggle()
            } else {
                lastPressMs = change.uptimeMillis
            }
        }
    }
}

/** "No earlier press": far enough in the past that the next press never counts as the second click. */
private const val NO_PRESS = Long.MIN_VALUE / 2

private fun pathTag(path: String): String = path.map { if (it.isLetterOrDigit()) it else '-' }
    .joinToString("").replace(Regex("-+"), "-").trim('-')

private sealed interface StructuredJsonValue {
    data class ObjectValue(val fields: List<Pair<String, StructuredJsonValue>>, val truncated: Boolean) : StructuredJsonValue

    data class ArrayValue(val values: List<StructuredJsonValue>, val truncated: Boolean) : StructuredJsonValue

    data class StringValue(val value: String) : StructuredJsonValue

    data class NumberValue(val value: String) : StructuredJsonValue

    data class BooleanValue(val value: Boolean) : StructuredJsonValue

    data object NullValue : StructuredJsonValue
}

/** Small bounded parser so expanding an untrusted record cannot recurse without limit or allocate unbounded UI state. */
private class BoundedJsonReader(private val source: String) {
    private var cursor = 0
    private var nodeCount = 0

    fun parseRoot(): StructuredJsonValue? {
        if (source.length > MAX_JSON_CHARS) return null
        skipWhitespace()
        val value = parseValue(0)
        skipWhitespace()
        return value?.takeIf { cursor == source.length }
    }

    private fun parseValue(depth: Int): StructuredJsonValue? {
        if (depth > MAX_JSON_DEPTH || ++nodeCount > MAX_JSON_CHILDREN * MAX_JSON_DEPTH) return null
        skipWhitespace()
        return when (peek()) {
            '{' -> parseObject(depth)
            '[' -> parseArray(depth)
            '"' -> parseString()?.let(StructuredJsonValue::StringValue)
            't' -> literal("true")?.let { StructuredJsonValue.BooleanValue(true) }
            'f' -> literal("false")?.let { StructuredJsonValue.BooleanValue(false) }
            'n' -> literal("null")?.let { StructuredJsonValue.NullValue }
            '-', in '0'..'9' -> parseNumber()?.let(StructuredJsonValue::NumberValue)
            else -> null
        }
    }

    private fun parseObject(depth: Int): StructuredJsonValue.ObjectValue? {
        cursor++
        skipWhitespace()
        if (consume('}')) return StructuredJsonValue.ObjectValue(emptyList(), truncated = false)
        val fields = mutableListOf<Pair<String, StructuredJsonValue>>()
        var truncated = false
        while (true) {
            skipWhitespace()
            val key = parseString() ?: return null
            skipWhitespace()
            if (!consume(':')) return null
            val value = parseValue(depth + 1) ?: return null
            if (fields.size < MAX_JSON_CHILDREN) fields += key to value else truncated = true
            skipWhitespace()
            if (consume('}')) break
            if (!consume(',')) return null
            if (fields.size >= MAX_JSON_CHILDREN) {
                // Consume the rest using the same grammar, but skip materializing additional nodes.
                truncated = true
            }
            if (fields.size >= MAX_JSON_CHILDREN && source.getOrNull(cursor)?.let { it == '}' } == true) {
                cursor++
                break
            }
        }
        return StructuredJsonValue.ObjectValue(fields, truncated)
    }

    private fun parseArray(depth: Int): StructuredJsonValue.ArrayValue? {
        cursor++
        skipWhitespace()
        if (consume(']')) return StructuredJsonValue.ArrayValue(emptyList(), truncated = false)
        val values = mutableListOf<StructuredJsonValue>()
        var truncated = false
        while (true) {
            val value = parseValue(depth + 1) ?: return null
            if (values.size < MAX_JSON_CHILDREN) values += value else truncated = true
            skipWhitespace()
            if (consume(']')) break
            if (!consume(',')) return null
            if (values.size >= MAX_JSON_CHILDREN) truncated = true
        }
        return StructuredJsonValue.ArrayValue(values, truncated)
    }

    @Suppress("ReturnCount") // Each malformed-input case ends the scan where it is found.
    private fun parseString(): String? {
        if (!consume('"')) return null
        val result = StringBuilder()
        while (cursor < source.length) {
            val char = source[cursor++]
            when {
                char == '"' -> return result.toString()
                char == '\\' -> {
                    val escaped = source.getOrNull(cursor++) ?: return null
                    when (escaped) {
                        '"', '\\', '/' -> result.append(escaped)
                        'b' -> result.append('\b')
                        'f' -> result.append('\u000c')
                        'n' -> result.append('\n')
                        'r' -> result.append('\r')
                        't' -> result.append('\t')
                        'u' -> {
                            val hex = source.substringOrNull(cursor, cursor + 4) ?: return null
                            val code = hex.toIntOrNull(16) ?: return null
                            result.append(code.toChar())
                            cursor += 4
                        }
                        else -> return null
                    }
                }
                char.code < 0x20 -> return null
                else -> result.append(char)
            }
            if (result.length > MAX_JSON_STRING_CHARS) return null
        }
        return null
    }

    private fun parseNumber(): String? {
        val start = cursor
        if (peek() == '-') cursor++
        if (peek() == '0') {
            cursor++
        } else {
            if (peek() !in '1'..'9') return null
            while (peek() in '0'..'9') cursor++
        }
        if (peek() == '.') {
            cursor++
            if (peek() !in '0'..'9') return null
            while (peek() in '0'..'9') cursor++
        }
        if (peek() == 'e' || peek() == 'E') {
            cursor++
            if (peek() == '+' || peek() == '-') cursor++
            if (peek() !in '0'..'9') return null
            while (peek() in '0'..'9') cursor++
        }
        return source.substring(start, cursor)
    }

    private fun literal(value: String): String? {
        if (!source.startsWith(value, cursor)) return null
        cursor += value.length
        return value
    }

    private fun skipWhitespace() {
        while (peek() == ' ' || peek() == '\n' || peek() == '\r' || peek() == '\t') cursor++
    }

    private fun peek(): Char = source.getOrNull(cursor) ?: '\u0000'

    private fun consume(char: Char): Boolean {
        if (peek() != char) return false
        cursor++
        return true
    }

    private fun String.substringOrNull(start: Int, end: Int): String? =
        if (start >= 0 && end <= length) substring(start, end) else null
}
