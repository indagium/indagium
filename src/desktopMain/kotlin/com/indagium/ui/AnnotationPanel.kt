@file:OptIn(
    androidx.compose.ui.ExperimentalComposeUiApi::class,
    androidx.compose.foundation.ExperimentalFoundationApi::class,
)

package com.indagium.ui

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.*
import androidx.compose.foundation.draganddrop.dragAndDropTarget
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draganddrop.DragAndDropEvent
import androidx.compose.ui.draganddrop.DragAndDropTarget
import androidx.compose.ui.draganddrop.dragData
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.toComposeImageBitmap
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.isAltPressed
import androidx.compose.ui.input.key.isCtrlPressed
import androidx.compose.ui.input.key.isMetaPressed
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.PointerIcon
import androidx.compose.ui.input.pointer.onPointerEvent
import androidx.compose.ui.input.pointer.pointerHoverIcon
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupProperties
import androidx.compose.ui.zIndex
import com.indagium.diagram3.DiagramExportMode
import com.indagium.diagram3.ParsedSeq3
import com.indagium.diagram3.Seq3AttachmentMode
import com.indagium.diagram3.Seq3Dialect
import com.indagium.diagram3.Seq3SourceImportResult
import com.indagium.diagram3.adoptSeq3NoteSource
import com.indagium.diagram3.encodeSeq3Note
import com.indagium.diagram3.importSeq3Source
import com.indagium.diagram3.parseSeq3Note
import com.indagium.diagram3.updateSeq3NoteCaption
import com.indagium.diagram3.updateSeq3NoteExportMode
import com.indagium.model.AnnBlock
import com.indagium.model.AppSettings
import com.indagium.model.LogEntry
import com.indagium.model.LogLevel
import com.indagium.model.LogTab
import com.indagium.model.VideoFrameReference
import com.indagium.model.resolveRows
import com.indagium.utils.LogLinePresentationContext
import com.indagium.utils.presentLogLine
import com.indagium.utils.visibleEntries
import com.mikepenz.markdown.m3.Markdown
import com.mikepenz.markdown.m3.markdownColor
import com.mikepenz.markdown.m3.markdownTypography
import com.mikepenz.markdown.model.MarkdownTypography
import com.mikepenz.markdown.model.rememberMarkdownState
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.awt.FileDialog
import java.awt.Frame
import java.awt.Toolkit
import java.io.File
import kotlin.math.roundToInt
import java.awt.Cursor as AwtCursor

// Half of Seq3Layout.kt's own (private) ROW_H=42.0 — a click within this unit-less vertical
// distance of a row's y counts as hitting it (see DiagramNoteView's tap handler).
private const val DIAGRAM_ROW_HIT_TOLERANCE = 21.0

private const val BLOCK_DRAG_SNAP_BIAS = 0.25f
private const val AUTO_SCROLL_SPEED_FACTOR = 0.6f
private const val STICK_TO_BOTTOM_THRESHOLD_DP = 24f

// How long the "can't verify this log" notice (Change 2c's unverifiable-relink case) stays up
// before auto-dismissing — long enough to read, short enough not to linger as stale chrome.
private const val UNVERIFIED_RELINK_NOTICE_MS = 8_000L

// Fixed thumbnail height for an AnnBlock.Image — shared between estimateBlockHeightPx (drag-
// reorder offset math) and the actual ImageBlockView render, so the estimate never drifts from
// what's really on screen the way a content-dependent guess (like textFieldDp for text) would.
private const val IMAGE_BLOCK_THUMBNAIL_DP = 140f

/**
 * The Notes column can contain many large diagram attachments.  A full [parseSeq3Note] decodes
 * every carried lifeline/message, which is unnecessary just to draw a folded card header.  This
 * deliberately shallow extraction reads only the small top-level metadata it displays; the full,
 * trusted parser remains the authority and runs only when a card is expanded or an action needs
 * the document.
 *
 * Unlike the deleted v1/v2 codec, [com.indagium.diagram3.Seq3Codec]'s header carries the WHOLE
 * document (lifelines/messages included, not a separate optional snapshot) — but `title`,
 * `caption`, `exportMode` and `range` are all written before the `lifelines`/`messages` arrays
 * (see `Seq3Codec.documentToMap`'s field order), so a bounded text scan still never has to reach
 * them to answer a folded card's questions.
 */
internal data class Seq3NoteSummary(
    val title: String,
    val caption: String,
    val exportMode: DiagramExportMode,
    val scope: String,
    /** v3's header carries no compact count either (see this object's own doc) — counting
     *  messages would mean scanning the unbounded array a folded card must never touch. */
    val messageCount: Int?,
    /** Maximum number of input characters inspected to produce this summary. */
    val inspectedChars: Int,
)

internal object Seq3NoteSummaryCache {
    private const val MAX_ENTRIES = 48
    internal const val MAX_INSPECTED_CHARS = 64 * 1024
    private const val MAX_LEADING_WHITESPACE = 256
    private const val DIAGRAM_MARKER = "<!-- indagium:diagram3 "
    private const val HEADER_TERMINATOR = " -->"
    private const val UNKNOWN_RANGE_ENDPOINT = "?"
    private const val SUPPORTED_VERSION = "v1"
    private val payloadKeys = listOf("\"lifelines\"", "\"messages\"")
    private val unicodeEscapeRegex = Regex("\\\\u([0-9a-fA-F]{4})")
    private val rangeRegex = Regex("\\\"range\\\"\\s*:\\s*\\{([^}]*)}")

    private data class Cached(val summary: Seq3NoteSummary?)

    private data class BoundedMetadata(val text: String, val inspectedChars: Int)

    // Identity keys are intentional. String.hashCode() scans the entire string the first time it
    // is used, which would undo the bounded parser for a multi-megabyte note before parsing even
    // began. Annotation text is immutable and Compose retains the same String instance between
    // edits, so identity provides the cache semantics this UI path actually needs in O(1).
    private val cache = java.util.IdentityHashMap<String, Cached>()
    private val insertionOrder = java.util.ArrayDeque<String>()

    fun summary(text: String): Seq3NoteSummary? {
        synchronized(cache) {
            if (cache.containsKey(text)) return cache[text]?.summary
        }
        val summary = parseSummary(text)
        synchronized(cache) {
            if (!cache.containsKey(text)) {
                while (cache.size >= MAX_ENTRIES) cache.remove(insertionOrder.removeFirst())
                insertionOrder.addLast(text)
            }
            cache[text] = Cached(summary)
        }
        return summary
    }

    private fun parseSummary(text: String): Seq3NoteSummary? {
        val metadata = boundedMetadata(text) ?: return null
        val header = metadata.text
        return Seq3NoteSummary(
            title = jsonString(header, "title").orEmpty(),
            caption = jsonString(header, "caption").orEmpty(),
            exportMode = exportModeFrom(header),
            scope = scopeFrom(header),
            messageCount = null,
            inspectedChars = metadata.inspectedChars,
        )
    }

    private fun boundedMetadata(text: String): BoundedMetadata? {
        val markerStart = markerStart(text) ?: return null
        val headerStart = markerStart + DIAGRAM_MARKER.length
        val inspectedEnd = minOf(text.length, headerStart + MAX_INSPECTED_CHARS)
        val prefix = text.substring(headerStart, inspectedEnd)
        val versionEnd = prefix.indexOf(' ')
        val version = prefix.takeIf { versionEnd > 0 }?.substring(0, versionEnd)
        if (version != SUPPORTED_VERSION) return null
        return BoundedMetadata(
            text = prefix.substring(0, metadataEnd(prefix, versionEnd)),
            inspectedChars = prefix.length,
        )
    }

    private fun markerStart(text: String): Int? {
        var start = 0
        while (start < text.length && start < MAX_LEADING_WHITESPACE && text[start].isWhitespace()) start++
        val excessiveWhitespace = start == MAX_LEADING_WHITESPACE && start < text.length && text[start].isWhitespace()
        return start.takeUnless { excessiveWhitespace }
            ?.takeIf { text.regionMatches(it, DIAGRAM_MARKER, 0, DIAGRAM_MARKER.length) }
    }

    private fun metadataEnd(prefix: String, searchStart: Int): Int {
        val payloadStarts = payloadKeys.map { prefix.indexOf(it, searchStart) }.filter { it >= 0 }
        val terminator = prefix.indexOf(HEADER_TERMINATOR, searchStart).takeIf { it >= 0 }
        return (payloadStarts + listOfNotNull(terminator)).minOrNull() ?: prefix.length
    }

    private fun jsonString(text: String, name: String): String? {
        val encoded = Regex("\\\"$name\\\"\\s*:\\s*\\\"((?:\\\\.|[^\\\"\\\\])*)\\\"")
            .find(text)?.groupValues?.get(1) ?: return null
        // This small unescaper covers JSON scalar escapes without decoding the carried document.
        return encoded.replace("\\\\\"", "\"")
            .replace("\\\\\\\\", "\\")
            .replace("\\\\n", "\n")
            .replace("\\\\r", "\r")
            .replace("\\\\t", "\t")
            .replace(unicodeEscapeRegex) { it.groupValues[1].toInt(16).toChar().toString() }
    }

    private fun jsonNumber(text: String, name: String): Long? =
        Regex("\\\"$name\\\"\\s*:\\s*(\\d+)").find(text)?.groupValues?.get(1)?.toLongOrNull()

    private fun exportModeFrom(header: String): DiagramExportMode =
        if (jsonString(header, "exportMode") == DiagramExportMode.SOURCE.name) {
            DiagramExportMode.SOURCE
        } else {
            DiagramExportMode.IMAGE
        }

    private fun scopeFrom(header: String): String {
        val range = rangeRegex.find(header)?.groupValues?.get(1).orEmpty()
        // "type", not v1/v2's "kind" — see Seq3Codec.rangeToMap. There is no v3 counterpart of
        // v1's "seqGroup" range kind (Seq3Range dropped it — see that type's own doc).
        return when (jsonString(range, "type")) {
            "ids" -> "Lines ${jsonNumber(range, "from") ?: UNKNOWN_RANGE_ENDPOINT}–" +
                "${jsonNumber(range, "to") ?: UNKNOWN_RANGE_ENDPOINT}"
            "time" -> "${jsonString(range, "fromTs").orEmpty().ifBlank { "start" }}–" +
                jsonString(range, "toTs").orEmpty().ifBlank { "end" }
            else -> "Current filtered view"
        }
    }

    internal fun clearForTest() {
        synchronized(cache) {
            cache.clear()
            insertionOrder.clear()
        }
    }
}

/** Full parser cache for the moment a folded card is expanded. */
internal object Seq3NoteParseCache {
    private const val MAX_ENTRIES = 48

    private data class Cached(val parsed: ParsedSeq3?)

    private val cache = object : LinkedHashMap<String, Cached>(MAX_ENTRIES, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Cached>?): Boolean = size > MAX_ENTRIES
    }
    private val parseCount = java.util.concurrent.atomic.AtomicInteger()

    fun parse(text: String): ParsedSeq3? {
        synchronized(cache) { cache[text] }?.let { return it.parsed }
        parseCount.incrementAndGet()
        val parsed = parseSeq3Note(text)
        synchronized(cache) { cache[text] = Cached(parsed) }
        return parsed
    }

    internal fun parseCountForTest(): Int = parseCount.get()

    internal fun clearForTest() {
        synchronized(cache) { cache.clear() }
        parseCount.set(0)
    }
}

private fun stripDiagramHeaderFast(text: String): String {
    val start = text.indexOf("<!-- indagium:diagram3 ")
    if (start < 0) return text
    val end = text.indexOf(" -->", start)
    return if (end < 0) text else text.substring(end + 4).trimStart('\r', '\n')
}

private data class ExpandedDiagram(val parsed: ParsedSeq3, val display: Seq3Display?)

// WP4: [settings] is threaded through here (rather than a pre-resolved [Seq3RasterTheme] from the
// caller) so the per-document theme can be resolved AFTER the parse, from the parsed document's
// own [com.indagium.diagram3.Seq3Document.themePresetName] — a folded card's summary
// (Seq3NoteSummary) never carries that field, since it sits after the lifelines/messages payload
// in Seq3Codec's field order and reading it would defeat the whole point of the bounded,
// payload-free summary scan (see Seq3NoteSummary's own doc). Resolving after the parse, still
// inside the SAME withContext(Dispatchers.Default) hop the parse already pays for, costs nothing
// extra and never blocks the composition thread with an eager full parse just to peek at a theme
// name.
@Composable
private fun rememberExpandedDiagram(
    noteText: String,
    settings: AppSettings,
    expanded: Boolean,
): ExpandedDiagram? {
    val result by produceState<ExpandedDiagram?>(initialValue = null, noteText, settings, expanded) {
        value = if (!expanded) null else withContext(Dispatchers.Default) {
            Seq3NoteParseCache.parse(noteText)?.let { parsed ->
                val theme = resolveSeq3ThemeColors(parsed.document, settings).toSeq3RasterTheme()
                ExpandedDiagram(parsed, Seq3RenderCache.display(parsed.document, theme))
            }
        }
    }
    return result
}

// Anchors a block's on-screen position across a height change caused by the reader expanding or
// collapsing it — originally just the diagram note's expand/collapse, now shared by the LogRef
// excerpt's expand/collapse too (see anchorBeforeBlockResize in AnnotationPanel).
private data class BlockResizeScrollAnchor(
    val blockId: String,
    val viewportTopPx: Float,
    val blockHeightPx: Float,
)

/** Where the rich Markdown editor dialog (AnnotationMarkdownEditorDialog) is currently pointed:
 *  a block's own text/caption, or one of the panel-level Prefix/Next steps fields. One `editingTarget`
 *  var and one Dialog call site in AnnotationPanel serve all three. */
private sealed class EditDialogTarget {
    data class Block(val id: String) : EditDialogTarget()
    data object Prefix : EditDialogTarget()
    data object Suffix : EditDialogTarget()
}

internal fun annotationPreviewCopyShortcutHandled(actionPressed: Boolean, key: Key, textFieldFocused: Boolean): Boolean =
    actionPressed && key == Key.C && !textFieldFocused

// Cumulative top-Y offset of each id in `orderedIds`, in that order — the building block both
// blockOrderDuringDrag (over the stable list order) and the render loop (over the live visual
// order) need, since unlike sequence rows, note blocks have no uniform row height.
internal fun cumulativeBlockOffsets(orderedIds: List<String>, heightOf: (String) -> Float): Map<String, Float> {
    val result = LinkedHashMap<String, Float>(orderedIds.size)
    var acc = 0f
    for (id in orderedIds) {
        result[id] = acc
        acc += heightOf(id)
    }
    return result
}

// Variable-height counterpart to FilterPanel's sequenceOrderDuringDrag — same "dragged center
// crosses a neighbor's center" rule, but positions come from measured per-block heights via
// cumulativeBlockOffsets instead of index * a uniform rowHeight. Looks up the dragged block's
// start position by id (via cumulativeBlockOffsets) rather than taking a start index directly,
// since with variable heights the index alone isn't enough to derive a Y position.
internal fun blockOrderDuringDrag(
    visibleIds: List<String>,
    draggedId: String?,
    dragOffsetY: Float,
    heightOf: (String) -> Float,
): List<String> {
    val dragged = draggedId?.takeIf { it in visibleIds } ?: return visibleIds
    val tops = cumulativeBlockOffsets(visibleIds, heightOf)
    val draggedTop = tops.getValue(dragged)
    val draggedHeight = heightOf(dragged)
    val sensitivityBias = draggedHeight * BLOCK_DRAG_SNAP_BIAS * dragOffsetY.compareTo(0f)
    val draggedCenter = draggedTop + draggedHeight / 2f + dragOffsetY + sensitivityBias
    val without = visibleIds.filter { it != dragged }
    val insertAt = without.indexOfFirst { id ->
        val center = tops.getValue(id) + heightOf(id) / 2f
        draggedCenter < center
    }.takeIf { it >= 0 } ?: without.size
    return without.take(insertAt) + dragged + without.drop(insertAt)
}

/** Which of [groupKeys] a given [y] position falls into, given each group's measured height —
 *  generic variable-height row hit-testing, the same idea [cumulativeBlockOffsets] runs for a
 *  drag offset. A negative [y] (above every group) is deliberately unmatched, and a [y] past the
 *  last group's bottom is deliberately unmatched too. */
internal fun manualGroupKeyAtY(
    groupKeys: List<String>,
    y: Float,
    heightOf: (String) -> Float,
): String? {
    if (y < 0f) return null
    var top = 0f
    for (groupKey in groupKeys) {
        val bottom = top + heightOf(groupKey)
        if (y < bottom) return groupKey
        top = bottom
    }
    return null
}

/** How many of a LogRef block's excerpt [rows][total] to render right now ("Note popup redesign"
 *  1b): collapsed shows the first [cap] rows plus a "show N more" link, expanded shows every row.
 *  With [total] at or under [cap] there is nothing to hide, so the full count is returned either
 *  way and the caller shows no toggle at all. Pure so the row-count math is unit-testable without
 *  composing the block. */
internal fun logExcerptVisibleRowCount(total: Int, expanded: Boolean, cap: Int = 3): Int =
    if (expanded || total <= cap) total else cap

@Composable
fun AnnotationPanel(
    tab: LogTab,
    settings: AppSettings,
    recentNotes: List<String> = emptyList(),
    recentNotesMenuOpen: Boolean = false,
    // The absolute path of the note file THIS tab is currently pinned to (AppState.
    // activeNoteFilePath), or null when unpinned. Purely for RecentNotesPopup's checkmark — see its
    // own comment for why this must be a full path, not just tab.noteTargetName's bare filename.
    activeNotePath: String? = null,
    onToggleMd: () -> Unit,
    onCopy: () -> Unit,
    onCopyImage: (AnnBlock.Image) -> Unit,
    // Diagram PNGs are rendered from the model in the active theme. The app owns the platform
    // clipboard; the panel only supplies bytes plus useful plain-text fallback.
    onCopyDiagramImage: (png: ByteArray, fallbackText: String) -> Unit = { _, _ -> },
    onCopyRichPreview: () -> Unit,
    onExportFrames: () -> Unit,
    onSave: () -> Unit,
    // "New Analysis" header action — clears this tab's Notes panel to a blank analysis and pins it
    // to the next free note-file slot, without touching whatever file was previously open. See
    // AppState.newAnalysis's own doc comment for why this is one click/no prompt and what "blank"
    // includes (prefix/suffix/issueDescription too, not just blocks).
    onNewAnalysis: () -> Unit,
    onToggleRecentNotes: () -> Unit,
    onOpenNote: (File) -> Unit,
    // "Locate log…" (Change 2b/2c) — only ever shown/wired up when this tab has no log
    // (tab.logData.isEmpty()). Opens the picked file as a brand-new tab and verifies it against
    // this tab's own notes before attaching them; see AppState.locateLogForTab.
    onLocateLog: (File) -> Unit = {},
    // True only right after a "Locate log…" attach landed on THIS tab and its note's fingerprint
    // couldn't be checked at all (saved before Change 2a existed) — never true for a confirmed
    // match (silent) or a confirmed mismatch (a separate blocking dialog, see App.kt's
    // pendingLogRelink). Purely informational; the attach already happened.
    showUnverifiedRelinkNotice: Boolean = false,
    onDismissUnverifiedRelinkNotice: () -> Unit = {},
    onUpdatePrefix: (String) -> Unit,
    onUpdateSuffix: (String) -> Unit,
    onUpdateIssueDescription: (String) -> Unit,
    onUpdateBlock: (String, String) -> Unit,
    onRemoveBlock: (String) -> Unit,
    onMoveBlock: (String, Int) -> Unit,
    onReorderBlock: (String, Int) -> Unit,
    onAddNoteAfter: (String?) -> Unit,
    onAddImage: (sourceBytes: ByteArray, provenance: String, afterId: String?) -> String?,
    // Anything dropped here that isn't an image — a log, a video — is handed back to the app-wide
    // drop routing instead of being swallowed by this panel's own target.
    onUnhandledFileDrop: (List<File>) -> Unit,
    onNavigateLogRef: (AnnBlock.LogRef) -> Unit,
    onNavigateVideoFrame: (VideoFrameReference) -> Unit,
    // Diagram notes (com.indagium.diagram) are ordinary AnnBlock.Notes whose text carries a spec
    // header + fenced source — see DiagramSpecCodec. Defaulted so every existing call site (and the
    // test harness) keeps compiling; a caller that doesn't wire them just gets a non-interactive
    // diagram, never a broken one.
    onEditDiagram: (blockId: String) -> Unit = {},
    onNavigateDiagramLine: (entryId: Int) -> Unit = {},
    onImportLinkedDiagram: (
        blockId: String,
        source: String,
        dialect: Seq3Dialect,
        confirmEvidenceLoss: Boolean,
    ) -> Seq3SourceImportResult? = { _, _, _, _ -> null },
    // The panel deliberately receives library data/actions rather than reaching into AppState:
    // it stays a UI leaf and the caller owns source-identity scoping and workspace transitions.
    diagramLibraryItems: List<DiagramLibraryItem> = emptyList(),
    onCreateDiagram: () -> Unit = {},
    // "From notes" — the Diagram library's `+ diagram` menu's second option. Generates a v3
    // diagram from exactly the log lines curated into this tab's Notes document instead of the
    // current log-viewer selection; see Seq3Session.beginFromNotes and ui/Seq3NotesSelection.kt.
    // Defaulted (with notesDiagramSummary below) so every existing call site and the test harness
    // keep compiling — the file's existing convention for every other diagram-library param above.
    onCreateDiagramFromNotes: () -> Unit = {},
    // Precomputed by the caller (FileView/CompareView), not this panel — the panel stays a UI leaf
    // that only renders counts, it never walks tab.annotations itself. Null reads the same as "no
    // notes have been curated yet" for the popup's disabled-row/subtitle logic.
    notesDiagramSummary: Seq3NotesSelection? = null,
    onOpenDiagramLibraryItem: (id: String) -> Unit = {},
    onDeleteDiagramLibraryItem: (id: String) -> Unit = {},
    width: Float,
    focusRequester: FocusRequester? = null,
    onPanelFocusChanged: (Boolean) -> Unit = {},
    keyboardFocusVisible: Boolean = false,
    scrollStateStore: LogViewerScrollStateStore? = null,
    /** Session-only target supplied by a real AI note-tool result. */
    highlightedBlockId: String? = null,
    modifier: Modifier = Modifier.fillMaxHeight(),
) {
    val tc = tc()
    val mono = monoFont()
    val mainWindowSize = LocalWindowInfo.current.containerSize
    val ann = tab.annotations
    val hasAnnotationBlocks = ann.blocks.isNotEmpty()
    val hasRecentNotes = recentNotes.isNotEmpty()
    val headerButtonModifier = Modifier.height(28.dp)
    // Open+▾ split-button shapes — same joined-pair idea as TabBar's log-file Open/▾ precedent
    // (TabBar.kt's leftShape/middleShape/rightShape), just built off CORNER_MD's 4.dp radius
    // instead of the toolbar's 7.dp so the corners match every other AppButton in this header.
    val openJoinedShape = RoundedCornerShape(topStart = 4.dp, bottomStart = 4.dp)
    val recentNotesDropdownShape = RoundedCornerShape(topEnd = 4.dp, bottomEnd = 4.dp)
    var panelFocused by remember { mutableStateOf(false) }
    var prefixFocused by remember { mutableStateOf(false) }
    var issueDescFocused by remember { mutableStateOf(false) }
    var suffixFocused by remember { mutableStateOf(false) }
    // Session-only, not persisted — only the text itself needs to survive a restart.
    var issueDescExpanded by remember(tab.id) { mutableStateOf(false) }
    // The library is supporting material rather than the primary Notes workflow. Start it
    // folded on every tab, like Issue description, and let a reader opt into its list.
    var diagramLibraryExpanded by remember(tab.id) { mutableStateOf(false) }
    var pendingDiagramLibraryDeleteId by remember(tab.id) { mutableStateOf<String?>(null) }
    var blockFieldFocused by remember { mutableStateOf(false) }
    var activeBlockFieldId by remember(tab.id) { mutableStateOf<String?>(null) }
    var navIndex by remember(tab.id) { mutableStateOf(0) }
    // The rich editor deliberately keeps an independent draft.  The panel's existing inline
    // field stays available, but Update is the only action that writes this dialog's draft.
    var editingTarget by remember(tab.id) { mutableStateOf<EditDialogTarget?>(null) }
    // Log excerpt expand/collapse per LogRef block ("Note popup redesign" 1b) — panel-level so a
    // reader's choice survives recomposition, keyed by block id rather than tab.id: block ids are
    // unique across the whole session (same reasoning as blockHeights above), so this can just
    // accumulate for the session instead of needing to be re-keyed/cleared per tab.
    val logExcerptExpanded = remember { mutableStateMapOf<String, Boolean>() }
    val prefixFr = remember { FocusRequester() }
    val suffixFr = remember { FocusRequester() }
    val blockFieldRequesters = remember(ann.blocks.map { it.id }) {
        ann.blocks.associate { it.id to FocusRequester() }
    }
    val noteTargets = remember(ann.blocks, hasRecentNotes, hasAnnotationBlocks) {
        annotationKeyboardTargets(
            blockIds = ann.blocks.map { it.id },
            hasRecentNotes = hasRecentNotes,
            hasBlocks = hasAnnotationBlocks,
        )
    }

    // Drag-and-drop reorder for note blocks — same live-preview/animation recipe as sequences
    // (FilterPanel.kt), adapted for variable block heights (a LogRef block showing several log
    // lines is much taller than a short Note; see cumulativeBlockOffsets/blockOrderDuringDrag
    // above). Unlike sequences' compact rows, blocks contain free-text editors, so the drag
    // gesture is scoped to a dedicated handle (BlockControls' "⠿") rather than the whole block —
    // otherwise selecting text inside a note would fight with reordering it.
    var dragBlockId by remember { mutableStateOf<String?>(null) }
    var dragOffsetY by remember { mutableStateOf(0f) }
    var justReleasedBlockId by remember { mutableStateOf<String?>(null) }
    var liveVisualBlockIds by remember { mutableStateOf(emptyList<String>()) }
    // Deliberately never cleared/keyed on tab.id: block ids are unique across every tab (never
    // collide), and keeping a revisited tab's already-known heights around is what lets its
    // blocks render at the correct position immediately instead of needing to re-measure (see the
    // heightIn(min=...) block below) — clearing this on every tab switch was tried and reverted:
    // it forced every revisit through a brief no-real-heights-yet window, and a plain-flow
    // fallback layout that existed for that window turned out to be more fragile (lost drag
    // capability on revisit) than just letting old entries accumulate, which costs a few floats
    // per note ever created in the session — negligible.
    val blockHeights = remember { mutableStateMapOf<String, Float>() }
    val blockDensity = LocalDensity.current.density
    val autoScrollEdgePx = 56f * blockDensity

    // Used only until a block's real size arrives via onSizeChanged below. A flat guess (the old
    // 90f-for-everything constant) was too far off for long notes or multi-line LogRefs: the
    // scrollable content's height is temporarily under-reported on a tab's first-ever layout pass,
    // which makes Compose clamp the persisted ScrollState.value down to fit — and it never climbs
    // back up once the real (larger) height lands, since clamping overwrites the stored value
    // rather than remembering what it "should" be. A closer guess shrinks that under-report window
    // close to zero. Deliberately biased to overshoot slightly rather than undershoot: an
    // over-estimate just leaves temporary blank space (self-corrects, no scroll-clamp risk); an
    // under-estimate is what causes the clamp.
    fun estimateBlockHeightPx(block: AnnBlock): Float {
        val avgCharWidthDp = 6.5f
        val chromeDp = 56f
        val charsPerLine = ((width - chromeDp) / avgCharWidthDp).coerceAtLeast(10f)

        // "Note popup redesign" 1b: an empty field is now one line at rest (~28dp: 5+5dp vertical
        // padding around an ~18dp line/cursor) instead of the old fixed 40-60dp minimum — see
        // BlockTextField. Content still grows the same way as before.
        fun textFieldDp(text: String, lineHeightDp: Float, emptyDp: Float = 28f): Float {
            if (text.isEmpty()) return emptyDp
            val lines = kotlin.math.ceil(text.length / charsPerLine).coerceAtLeast(1f)
            return maxOf(emptyDp, lines * lineHeightDp + 10f)
        }
        // BlockCard's header row (a 20dp control row plus 7dp top/bottom padding), its 1-1.5dp
        // top+bottom border, the body's own 9dp bottom padding, the 7dp spacing between body
        // children, and the 8dp gap BlockCard now carries as its own bottom padding in place of
        // the old edge-to-edge coloured border (see BlockCard's doc comment).
        val headerDp = 20f + 14f
        val cardChromeDp = 3f
        val bodyBottomDp = 9f
        val spacingDp = 7f
        val gapDp = BLOCK_GAP_DP.toFloat()
        val dp = when (block) {
            is AnnBlock.Note -> {
                // Folded diagram cards do not decode or draw their carried model.  The shallow
                // summary gives us a stable header-height estimate without making a long Notes
                // document pay an O(messages) parse during layout.
                val summary = Seq3NoteSummaryCache.summary(block.text)
                if (summary != null) {
                    // Collapsed diagram row: a recessed summary row (~42dp for its two lines of
                    // text plus padding) — never parses/renders itself, matching DiagramSummaryRow.
                    val captionDp = textFieldDp(summary.caption, 20.7f)
                    headerDp + cardChromeDp + captionDp + spacingDp + 42f + bodyBottomDp + gapDp
                } else {
                    headerDp + cardChromeDp + textFieldDp(block.text, 20.7f) + bodyBottomDp + gapDp
                }
            }
            is AnnBlock.LogRef -> {
                val captionDp = textFieldDp(block.caption, 20.7f)
                val rowCount = block.resolveRows(tab).size
                // Collapsed excerpt (the common case): a 26dp summary row plus up to 3 single-line
                // rows at 16dp each, plus a 16dp "show N more" link when there's more to hide.
                val visibleRows = logExcerptVisibleRowCount(rowCount, expanded = false)
                val excerptDp = if (rowCount == 0) 0f else {
                    26f + visibleRows * 16f + (if (rowCount > 3) 16f else 0f)
                }
                val filenameBadgeDp = if (block.sourceFilename != null) 21f + spacingDp else 0f
                headerDp + cardChromeDp + filenameBadgeDp + captionDp +
                    (if (rowCount > 0) spacingDp + excerptDp else 0f) + bodyBottomDp + gapDp
            }
            is AnnBlock.Image -> {
                // Label plus its own spacing, and only when the block actually renders one — a
                // pasted/dropped image draws neither (see displayProvenance at the render site).
                val provenanceDp = if (block.displayProvenance != null) 16f + spacingDp else 0f
                val captionDp = textFieldDp(block.caption, 20.7f)
                headerDp + cardChromeDp + IMAGE_BLOCK_THUMBNAIL_DP + spacingDp + provenanceDp + captionDp + bodyBottomDp + gapDp
            }
        }
        return dp * blockDensity
    }

    fun blockHeightOf(id: String): Float = blockHeights[id]
        ?: ann.blocks.firstOrNull { it.id == id }?.let(::estimateBlockHeightPx)
        ?: (90f * blockDensity)
    val blockIds = ann.blocks.map { it.id }
    LaunchedEffect(blockIds, dragBlockId, justReleasedBlockId) {
        if (shouldSyncSequenceVisualOrder(dragBlockId, justReleasedBlockId)) {
            liveVisualBlockIds = blockIds
        }
    }
    LaunchedEffect(justReleasedBlockId) {
        if (justReleasedBlockId != null) {
            kotlinx.coroutines.delay(120)
            justReleasedBlockId = null
        }
    }
    val visualBlockIds = liveVisualBlockIds
        .takeIf { it.toSet() == blockIds.toSet() && it.size == blockIds.size } ?: blockIds
    val currentVisualBlockIds = rememberUpdatedState(visualBlockIds)
    val currentDragBlockId = rememberUpdatedState(dragBlockId)
    // pointerInput below is keyed on block.id alone (stable across reorders, unlike sequences'
    // whole-list key) so an in-progress drag isn't cancelled by the reorder it's causing — but
    // that also means detectDragGestures' coroutine is never restarted after the first drag on a
    // given block, so any plain `val` it closes over (blockIds) goes stale on every drag after
    // the first. rememberUpdatedState is what keeps it reading the current order instead.
    val currentBlockIds = rememberUpdatedState(blockIds)
    val blockTargetOffsets = cumulativeBlockOffsets(visualBlockIds, ::blockHeightOf)
    val blockStartOffsets = cumulativeBlockOffsets(blockIds, ::blockHeightOf)
    // Read inside onDrag below, same staleness reasoning as currentBlockIds.
    val currentBlockStartOffsets = rememberUpdatedState(blockStartOffsets)
    val totalBlockHeightPx = blockIds.sumOf { blockHeightOf(it).toDouble() }.toFloat()

    fun openNotePicker() {
        val fd = FileDialog(null as Frame?, "Open Note File", FileDialog.LOAD)
        fd.setFilenameFilter { _, n -> n.endsWith(".md") || n.endsWith(".txt") || n.endsWith(".ann") }
        fd.isVisible = true
        fd.file?.let { onOpenNote(File(fd.directory, it)) }
    }

    // No extension filter, unlike openNotePicker above — this picks a LOG file, and platform
    // pickers don't reliably invoke setFilenameFilter (see TabBar's own "Open Log File" picker's
    // comment); AppState.locateLogForTab validates the pick itself.
    fun openLocateLogPicker() {
        val fd = FileDialog(null as Frame?, "Locate Log File", FileDialog.LOAD)
        fd.isVisible = true
        fd.file?.let { onLocateLog(File(fd.directory, it)) }
    }

    fun moveNoteFocus(delta: Int) {
        navIndex = rovingMove(noteTargets.map { it.asRovingItem() }, navIndex, delta)
    }

    fun focusedBlockId(): String? = noteTargets.getOrNull(navIndex)
        ?.takeIf { it.kind == KeyboardTargetKind.NoteBlock }
        ?.id
        ?.removePrefix("block:")

    // Images follow the same insertion rule as a new text block: the block currently being
    // edited/roved is the anchor, otherwise evidence goes at the end. This makes a screenshot
    // pasted while writing an observation stay beside that observation rather than jumping to the
    // bottom of a long notes list.
    fun imageInsertionAfterId(): String? = activeBlockFieldId ?: focusedBlockId()

    fun addDroppedImageFiles(files: List<File>): Boolean {
        var afterId = imageInsertionAfterId()
        var addedAny = false
        files.forEach { file ->
            val bytes = imageBytesFromFile(file) ?: return@forEach
            val insertedId = onAddImage(bytes, "dropped ${file.name}", afterId)
            if (insertedId != null) {
                afterId = insertedId
                addedAny = true
            }
        }
        return addedAny
    }

    val imageDropTarget = remember(tab.id, activeBlockFieldId, navIndex, ann.blocks, onAddImage, onUnhandledFileDrop) {
        object : DragAndDropTarget {
            override fun onDrop(event: DragAndDropEvent): Boolean {
                val files = runCatching { localFilesFromDropData(event.dragData()) }.getOrDefault(emptyList())
                if (addDroppedImageFiles(files)) return true
                // Nothing here decoded as an image. Compose gives a drop to the innermost target
                // that accepted the drag and never retries the ancestor on a false return, so a
                // video or log dropped on Notes would otherwise disappear silently — hand the
                // whole batch to the same app-wide routing a drop on the log view would hit.
                if (files.isEmpty()) return false
                onUnhandledFileDrop(files)
                return true
            }
        }
    }

    fun activateNoteTarget() {
        val target = noteTargets.getOrNull(navIndex) ?: return
        when (target.kind) {
            KeyboardTargetKind.NotePreview -> if (hasAnnotationBlocks) onToggleMd()
            KeyboardTargetKind.NoteCopy -> onCopy()
            KeyboardTargetKind.NoteSave -> onSave()
            KeyboardTargetKind.NoteOpen -> openNotePicker()
            KeyboardTargetKind.NoteRecentNotes -> if (hasRecentNotes) onToggleRecentNotes()
            KeyboardTargetKind.NotePrefix -> runCatching { prefixFr.requestFocus() }
            KeyboardTargetKind.NoteSuffix -> runCatching { suffixFr.requestFocus() }
            KeyboardTargetKind.NoteAddTextBlock -> {
                val after = if (target.id == "add-at-start") null else ann.blocks.lastOrNull()?.id
                onAddNoteAfter(after)
            }
            KeyboardTargetKind.NoteBlock -> {
                val blockId = target.id.removePrefix("block:")
                val block = ann.blocks.firstOrNull { it.id == blockId }
                when (block) {
                    is AnnBlock.LogRef -> onNavigateLogRef(block)
                    is AnnBlock.Image -> block.videoFrame?.let(onNavigateVideoFrame)
                        ?: runCatching { blockFieldRequesters[blockId]?.requestFocus() }
                    else -> runCatching { blockFieldRequesters[blockId]?.requestFocus() }
                }
            }
            else -> {}
        }
    }

    fun handleBlockShortcut(ev: androidx.compose.ui.input.key.KeyEvent): Boolean {
        val blockId = focusedBlockId() ?: return false
        val idx = ann.blocks.indexOfFirst { it.id == blockId }
        if (idx < 0) return false
        return when {
            ev.isAltPressed && ev.key == Key.DirectionUp -> { onMoveBlock(blockId, -1); true }
            ev.isAltPressed && ev.key == Key.DirectionDown -> { onMoveBlock(blockId, +1); true }
            ev.isCtrlPressed && ev.key == Key.Enter -> { onAddNoteAfter(blockId); true }
            ev.isMetaPressed && ev.key == Key.Enter -> { onAddNoteAfter(blockId); true }
            ev.key == Key.Delete || ev.key == Key.Backspace -> { onRemoveBlock(blockId); true }
            else -> false
        }
    }

    // One Dialog call site for every rich-editor target ("Note popup redesign" fixes): a plain
    // Note/LogRef block, a diagram Note's caption (never its encoded header — see EditDialogTarget's
    // KDoc), or the panel-level Prefix/Next steps fields. editingDialogContent is null exactly when
    // there is nothing to show (target cleared, block since removed, or an Image block's onEdit,
    // which is never wired) so no blank Dialog ever flashes up.
    val editingDialogContent: (@Composable () -> Unit)? = when (val target = editingTarget) {
        null -> null
        EditDialogTarget.Prefix -> {
            {
                AnnotationMarkdownEditorDialog(
                    title = "Edit prefix",
                    initialText = ann.prefix,
                    confirmLabel = "Save",
                    windowSize = mainWindowSize,
                    fileLabel = tab.filename,
                    onConfirm = { onUpdatePrefix(it); editingTarget = null },
                    onDismiss = { editingTarget = null },
                )
            }
        }
        EditDialogTarget.Suffix -> {
            {
                AnnotationMarkdownEditorDialog(
                    title = "Edit next steps",
                    initialText = ann.suffix,
                    confirmLabel = "Save",
                    windowSize = mainWindowSize,
                    fileLabel = tab.filename,
                    onConfirm = { onUpdateSuffix(it); editingTarget = null },
                    onDismiss = { editingTarget = null },
                )
            }
        }
        is EditDialogTarget.Block -> {
            val block = ann.blocks.firstOrNull { it.id == target.id }
            val diagramSummary = (block as? AnnBlock.Note)?.let { Seq3NoteSummaryCache.summary(it.text) }
            when {
                block == null -> null
                // Diagram notes have a dedicated editor that keeps their model/source contract
                // intact — this dialog only ever touches the diagram's caption, never the raw
                // Note.text carrying the encoded <!-- indagium:diagram3 --> header.
                diagramSummary != null -> {
                    {
                        AnnotationMarkdownEditorDialog(
                            title = "Edit caption",
                            initialText = diagramSummary.caption,
                            confirmLabel = "Save caption",
                            windowSize = mainWindowSize,
                            fileLabel = tab.filename,
                            onConfirm = { newCaption ->
                                updateSeq3NoteCaption(block.text, newCaption)?.let { onUpdateBlock(block.id, it) }
                                editingTarget = null
                            },
                            onDismiss = { editingTarget = null },
                        )
                    }
                }
                else -> {
                    val text = when (block) {
                        is AnnBlock.Note -> block.text
                        is AnnBlock.LogRef -> block.caption
                        // Image captions are intentionally outside this prose-editor scope.
                        is AnnBlock.Image -> null
                    }
                    if (text == null) null else {
                        {
                            AnnotationMarkdownEditorDialog(
                                title = "Edit note",
                                initialText = text,
                                confirmLabel = "Save note",
                                windowSize = mainWindowSize,
                                rows = (block as? AnnBlock.LogRef)?.resolveRows(tab).orEmpty(),
                                fileLabel = (block as? AnnBlock.LogRef)?.sourceFilename ?: tab.filename,
                                onDelete = { onRemoveBlock(block.id); editingTarget = null },
                                onConfirm = { updated ->
                                    onUpdateBlock(block.id, updated)
                                    editingTarget = null
                                },
                                onDismiss = { editingTarget = null },
                            )
                        }
                    }
                }
            }
        }
    }
    editingDialogContent?.let { content ->
        Dialog(
            onDismissRequest = { editingTarget = null },
            properties = DialogProperties(
                usePlatformDefaultWidth = false,
                dismissOnClickOutside = false,
            ),
        ) { content() }
    }

    Column(
        modifier.width(width.dp).background(tc.p)
            .border(BorderStroke(1.dp, if (panelFocused && keyboardFocusVisible) tc.ac else tc.br))
            .dragAndDropTarget(
                shouldStartDragAndDrop = { event ->
                    runCatching { isFileDropData(event.dragData()) }.getOrDefault(false)
                },
                target = imageDropTarget,
            )
            .then(if (focusRequester != null) Modifier.focusRequester(focusRequester) else Modifier)
            .focusGroup()
            .focusable()
            .onFocusChanged { panelFocused = it.hasFocus; onPanelFocusChanged(it.hasFocus) }
            .onPreviewKeyEvent { ev ->
                if (ev.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
                val actionPressed = if (isMacOs) ev.isMetaPressed else ev.isCtrlPressed
                val textFieldFocused = prefixFocused || suffixFocused || blockFieldFocused || issueDescFocused
                when {
                    actionPressed && ev.key == Key.S -> { onSave(); true }
                    actionPressed && ev.key == Key.V -> {
                        val bytes = runCatching { Toolkit.getDefaultToolkit().systemClipboard.getContents(null) }
                            .getOrNull()
                            ?.let(::imageBytesFromTransferable)
                        if (bytes != null) {
                            onAddImage(bytes, "pasted from clipboard", imageInsertionAfterId())
                            true
                        } else {
                            false
                        }
                    }
                    annotationPreviewCopyShortcutHandled(actionPressed, ev.key, textFieldFocused) -> { onCopy(); true }
                    actionPressed && ev.key == Key.O -> { openNotePicker(); true }
                    textFieldFocused -> {
                        if (ev.key == Key.Escape) {
                            runCatching { focusRequester?.requestFocus() }
                            true
                        } else {
                            false
                        }
                    }
                    handleBlockShortcut(ev) -> true
                    ev.key == Key.DirectionUp -> { moveNoteFocus(-1); true }
                    ev.key == Key.DirectionDown -> { moveNoteFocus(+1); true }
                    ev.key == Key.DirectionLeft -> { moveNoteFocus(-1); true }
                    ev.key == Key.DirectionRight -> { moveNoteFocus(+1); true }
                    ev.key == Key.Enter || ev.key == Key.NumPadEnter || ev.key == Key.Spacebar -> {
                        activateNoteTarget(); true
                    }
                    ev.key == Key.Escape -> {
                        if (recentNotesMenuOpen) onToggleRecentNotes()
                        true
                    }
                    else -> false
                }
            },
    ) {
        // These controls (up to six, depending on whether a "Locate log…" reconnect is showing)
        // keep the established workflow visible rather than making note opening/history
        // discoverable only through a responsive overflow menu. Rich HTML copying belongs with
        // the rendered Preview below. FlowRow wraps to a second line at narrow panel widths
        // instead of clipping/overflowing the header (see the "Locate log…" clipping report).
        // Five controls at the common width — Preview/Copy/Save/New fit on one line with the
        // Open+▾ split button trailing them, mirroring TabBar's log-file Open/▾ pair so opening a
        // note and opening a log read as the same gesture.
        Box(
            Modifier.fillMaxWidth().heightIn(min = 36.dp).background(tc.p2)
                .border(BorderStroke(1.dp, tc.br)).padding(horizontal = 12.dp, vertical = 4.dp),
        ) {
            FlowRow(
                Modifier.align(Alignment.Center),
                verticalArrangement = Arrangement.spacedBy(4.dp),
                horizontalArrangement = Arrangement.spacedBy(4.dp, Alignment.CenterHorizontally),
            ) {
                AppButton("Preview", onClick = onToggleMd, enabled = hasAnnotationBlocks, modifier = headerButtonModifier)
                AppButton("Copy", onClick = onCopy, modifier = headerButtonModifier)
                AppButton("Save", onClick = onSave, modifier = headerButtonModifier)
                AppButton("New", onClick = onNewAnalysis, modifier = headerButtonModifier)
                // Only when this tab has no log at all (opened via Case Library's "Open notes
                // only," a blank new tab, or its own log going missing) — the guided reconnect
                // path for Change 2's "note opened without its log" hazard. See
                // openLocateLogPicker/AppState.locateLogForTab. Placed next to Open/▾ (its nearest
                // relative in purpose) rather than earlier in the row; it's a rare state and, per
                // the FlowRow comment above, is allowed to be the thing that wraps to its own line.
                if (tab.logData.isEmpty()) {
                    AppButton("Locate log…", onClick = { openLocateLogPicker() }, modifier = headerButtonModifier)
                }
                // Wrapped in its own zero-spacing Row so FlowRow's 4.dp horizontalArrangement gap
                // treats Open+▾ as one atomic child instead of prying them apart — same reason
                // TabBar never lets its own Open/▾ pair split across a wrap point.
                Row {
                    AppButton(
                        "Open",
                        onClick = { openNotePicker() },
                        modifier = headerButtonModifier,
                        shape = if (hasRecentNotes) openJoinedShape else CORNER_MD,
                    )
                    // Hidden (not disabled) when there's no history — matches TabBar's own
                    // `if (hasRecentFiles) ToolbarBtn("▾", …)` for the log-file Open button: a
                    // dropdown arrow with nothing behind it is dead chrome, not a legitimate
                    // disabled state, so it shouldn't render at all.
                    if (hasRecentNotes) {
                        Box {
                            AppButton(
                                "▾",
                                modifier = headerButtonModifier.width(18.dp),
                                horizontalPadding = 0.dp,
                                shape = recentNotesDropdownShape,
                                onClick = onToggleRecentNotes,
                            )
                            if (recentNotesMenuOpen) {
                                RecentNotesPopup(
                                    recentNotes = recentNotes,
                                    activeNotePath = activeNotePath,
                                    onOpenNote = onOpenNote,
                                    onDismiss = onToggleRecentNotes,
                                    tc = tc,
                                )
                            }
                        }
                    }
                }
            }
        }
        // "Locate log…" landed on this tab but couldn't be checked against anything (Change 2c's
        // "no fingerprint" case — a note saved before Change 2a existed). Purely informational: the
        // attach already happened by the time this shows, unlike a confirmed MISMATCH, which is a
        // separate blocking dialog (App.kt's pendingLogRelink) that gates the attach itself. Auto-
        // dismisses so it doesn't linger as stale chrome once the user's moved on, but "×" also
        // dismisses it immediately.
        if (showUnverifiedRelinkNotice) {
            LaunchedEffect(tab.id) {
                kotlinx.coroutines.delay(UNVERIFIED_RELINK_NOTICE_MS)
                onDismissUnverifiedRelinkNotice()
            }
            Row(
                Modifier.fillMaxWidth().background(tc.abg).border(BorderStroke(1.dp, tc.br))
                    .padding(horizontal = 12.dp, vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                AppText(
                    "Log attached, but this note was saved before its log could be verified — clicking a " +
                        "reference may jump to the wrong row.",
                    color = tc.tx,
                    fontSize = 10.sp,
                    maxLines = 2,
                    modifier = Modifier.weight(1f),
                )
                CloseButton(onClick = onDismissUnverifiedRelinkNotice)
            }
        }
        // Inline preview popup
        if (tab.showAnnMd && hasAnnotationBlocks) {
            MdPreviewDialog(
                tab = tab, settings = settings, mono = mono,
                onCopy = onCopy, onCopyRichPreview = onCopyRichPreview, onExportFrames = onExportFrames, onDismiss = onToggleMd,
            )
        }

        // Un-keyed rememberScrollState() here would tie the scroll position to this composable's
        // slot, not to the tab — since AnnotationPanel is recomposed in place as `tab` changes
        // (not one instance per tab), that single shared ScrollState leaks between tabs and gets
        // clamped/reset by whichever tab's content is shorter, rather than each tab keeping its
        // own remembered position. Route it through the same per-tab keyed store the log viewer
        // already uses for exactly this reason.
        val notesScrollStates = scrollStateStore ?: remember { LogViewerScrollStateStore() }
        val scroll = notesScrollStates.scrollState("${tab.id}:notes")
        val stickToBottomPx = STICK_TO_BOTTOM_THRESHOLD_DP * blockDensity
        // If the user was scrolled to (or very near) the bottom, keep them pinned there as
        // totalBlockHeightPx settles from per-block estimates to real measured heights. Without
        // this, a tab scrolled to the end lands a little short after switching away and back: the
        // content grows (guesses correcting to real sizes) after the scroll position has already
        // been restored, so the restored value is now short of the new true bottom. Reacts only to
        // content-height changes, never to the user's own scrolling, so a deliberate scroll away
        // from the bottom is never fought.
        var stickToBottom by remember(tab.id) {
            mutableStateOf(scroll.maxValue <= 0 || scroll.value >= scroll.maxValue - stickToBottomPx)
        }
        var blockResizeScrollAnchor by remember(tab.id) { mutableStateOf<BlockResizeScrollAnchor?>(null) }
        // Captures the block's current on-screen viewport position before an expand/collapse
        // toggle changes its height, so the effect below can keep that position once the new
        // height settles — shared by the diagram note's expand/collapse and every LogRef excerpt
        // expand/collapse path (the summary row, "show N more", and "show less" all funnel through
        // one onToggleExpanded callback per block, so one call here at the panel level covers all
        // of them).
        fun anchorBeforeBlockResize(blockId: String) {
            val blockTop = blockStartOffsets[blockId] ?: 0f
            blockResizeScrollAnchor = BlockResizeScrollAnchor(
                blockId = blockId,
                viewportTopPx = blockTop - scroll.value,
                blockHeightPx = blockHeightOf(blockId),
            )
            stickToBottom = false
        }
        LaunchedEffect(scroll, blockResizeScrollAnchor) {
            snapshotFlow { scroll.maxValue <= 0 || scroll.value >= scroll.maxValue - stickToBottomPx }
                .collect { if (blockResizeScrollAnchor == null) stickToBottom = it }
        }
        LaunchedEffect(totalBlockHeightPx, scroll, blockResizeScrollAnchor) {
            val anchor = blockResizeScrollAnchor
            if (anchor != null) {
                val currentHeight = blockHeightOf(anchor.blockId)
                if (kotlin.math.abs(currentHeight - anchor.blockHeightPx) > 0.5f) {
                    withFrameNanos { }
                    val blockTop = blockStartOffsets[anchor.blockId] ?: 0f
                    val targetScroll = (blockTop - anchor.viewportTopPx).roundToInt()
                    scroll.scrollTo(targetScroll.coerceIn(0, scroll.maxValue))
                    blockResizeScrollAnchor = null
                    stickToBottom = false
                }
            } else if (stickToBottom) {
                scroll.scrollTo(scroll.maxValue)
            }
        }
        // The suffix field grows as the user inserts line breaks. That changes the Notes content
        // height without changing totalBlockHeightPx, so the effect above does not get a chance to
        // keep the bottom section in view. When Next steps has focus, follow that growth after the
        // new layout is measured; this keeps the same bottom inset visible as in the one-line
        // state instead of letting the field run into the panel's rounded bottom edge.
        LaunchedEffect(ann.suffix, suffixFocused, scroll, blockResizeScrollAnchor) {
            if (!suffixFocused || blockResizeScrollAnchor != null) return@LaunchedEffect
            withFrameNanos { }
            scroll.scrollTo(scroll.maxValue)
        }
        LaunchedEffect(highlightedBlockId, tab.id, blockStartOffsets[highlightedBlockId]) {
            val target = highlightedBlockId ?: return@LaunchedEffect
            if (ann.blocks.none { it.id == target }) return@LaunchedEffect
            scroll.scrollTo((blockStartOffsets[target] ?: 0f).roundToInt())
        }
        Box(Modifier.fillMaxSize()) {
            Column(Modifier.fillMaxSize().verticalScroll(scroll).padding(end = 8.dp)) {
                // This is intentionally above Issue description: saved diagrams are log-specific
                // workspace artifacts, while the issue description is private free-form context.
                // The coordinator has already filtered the list by path + content fingerprint.
                DiagramLibrarySection(
                    items = diagramLibraryItems,
                    expanded = diagramLibraryExpanded,
                    onToggle = { diagramLibraryExpanded = !diagramLibraryExpanded },
                    onCreate = onCreateDiagram,
                    onCreateFromNotes = onCreateDiagramFromNotes,
                    selectedLineCount = tab.selected.size,
                    notesDiagramSummary = notesDiagramSummary,
                    onOpen = onOpenDiagramLibraryItem,
                    onRequestDelete = { pendingDiagramLibraryDeleteId = it },
                )
                Divider()
                // Issue description — a private working note, persisted in the .ann sidecar and
                // autosave, but deliberately never rendered into the Markdown preview/export/MCP
                // markdown so it stays out of anything shared or copied as the issue writeup.
                SectionHeader(
                    "Issue description",
                    expanded = issueDescExpanded,
                    onToggle = { issueDescExpanded = !issueDescExpanded },
                )
                if (issueDescExpanded) {
                    Box(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp)) {
                        // Keep the private issue note compact inside the panel. Once it reaches the
                        // same bounded height used by the other annotation text areas, scrolling
                        // stays inside the field and the clear action remains available.
                        ScrollableTextArea(
                            value = ann.issueDescription,
                            onValue = onUpdateIssueDescription,
                            placeholder = "Not included in previews or exports…",
                            modifier = Modifier.fillMaxWidth()
                                // ScrollableTextArea groups focus around the scrolling field, so
                                // hasFocus is the correct value for shortcut gating.
                                .onFocusChanged { issueDescFocused = it.hasFocus },
                            fontSize = 11.sp,
                            lineHeight = 16.sp,
                            minHeight = 60.dp,
                            maxHeight = 160.dp,
                            resetKey = tab.id,
                            onClear = { onUpdateIssueDescription("") },
                        )
                    }
                }
                Divider()

                // Prefix
                AnnSection(tc) {
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        AppText("Prefix", color = tc.td, fontSize = 10.sp, fontFamily = UI)
                        Spacer(Modifier.weight(1f))
                        LabelIconButton("Edit", fontSize = 10.sp, onClick = { editingTarget = EditDialogTarget.Prefix })
                    }
                    Spacer(Modifier.height(3.dp))
                    ScrollableTextArea(
                        value = ann.prefix,
                        onValue = onUpdatePrefix,
                        placeholder = "Heading, context…",
                        modifier = Modifier.fillMaxWidth()
                            .focusRequester(prefixFr)
                            // hasFocus, not isFocused — see ScrollableTextArea's own note.
                            .onFocusChanged { prefixFocused = it.hasFocus },
                        fontSize = 12.sp,
                        maxHeight = 160.dp,
                        resetKey = tab.id,
                        onClear = { onUpdatePrefix("") },
                    )
                }

                if (ann.blocks.isEmpty()) {
                    // Add note button + empty state
                    AddNoteButton(tc = tc, onClick = { onAddNoteAfter(null) })
                    Column(
                        Modifier.fillMaxWidth().padding(40.dp),
                        horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        AppText("◆", color = tc.td.copy(.33f), fontSize = 22.sp)
                        AppText("Right-click a log line\nto annotate it", color = tc.td, fontSize = 11.sp, maxLines = 2)
                    }
                }

                @Composable
                fun BlockContent(block: AnnBlock, isFirst: Boolean, isLast: Boolean, dragHandleModifier: Modifier) {
                    when (block) {
                        is AnnBlock.Note -> NoteBlock(
                            block = block, tc = tc, settings = settings, isFirst = isFirst, isLast = isLast,
                            focused = noteTargets.getOrNull(navIndex)?.id == "block:${block.id}" || highlightedBlockId == block.id,
                            fieldFocusRequester = blockFieldRequesters[block.id],
                            onFieldFocusChanged = { focused ->
                                blockFieldFocused = focused
                                if (focused) activeBlockFieldId = block.id
                                else if (activeBlockFieldId == block.id) activeBlockFieldId = null
                            },
                            onUpdate = { onUpdateBlock(block.id, it) },
                            onEdit = { editingTarget = EditDialogTarget.Block(block.id) },
                            onRemove = { onRemoveBlock(block.id) },
                            onMoveUp = { onMoveBlock(block.id, -1) },
                            onMoveDown = { onMoveBlock(block.id, 1) },
                            onAddBelow = { onAddNoteAfter(block.id) },
                            dragHandleModifier = dragHandleModifier,
                            onBeforeToggleDiagram = { anchorBeforeBlockResize(block.id) },
                            onEditDiagram = { onEditDiagram(block.id) },
                            onNavigateDiagramLine = onNavigateDiagramLine,
                            onImportLinkedDiagram = { source, dialect, confirm -> onImportLinkedDiagram(block.id, source, dialect, confirm) },
                            onCopyDiagramImage = onCopyDiagramImage,
                        )
                        is AnnBlock.LogRef -> LogRefBlock(
                            block = block, tab = tab, settings = settings, mono = mono, tc = tc,
                            isFirst = isFirst, isLast = isLast,
                            focused = noteTargets.getOrNull(navIndex)?.id == "block:${block.id}" || highlightedBlockId == block.id,
                            fieldFocusRequester = blockFieldRequesters[block.id],
                            onFieldFocusChanged = { focused ->
                                blockFieldFocused = focused
                                if (focused) activeBlockFieldId = block.id
                                else if (activeBlockFieldId == block.id) activeBlockFieldId = null
                            },
                            onUpdateCaption = { onUpdateBlock(block.id, it) },
                            onEdit = { editingTarget = EditDialogTarget.Block(block.id) },
                            onRemove = { onRemoveBlock(block.id) },
                            onMoveUp = { onMoveBlock(block.id, -1) },
                            onMoveDown = { onMoveBlock(block.id, 1) },
                            onAddBelow = { onAddNoteAfter(block.id) },
                            onNavigate = { onNavigateLogRef(block) },
                            excerptExpanded = logExcerptExpanded[block.id] ?: false,
                            onToggleExcerpt = {
                                anchorBeforeBlockResize(block.id)
                                logExcerptExpanded[block.id] = !(logExcerptExpanded[block.id] ?: false)
                            },
                            dragHandleModifier = dragHandleModifier,
                        )
                        is AnnBlock.Image -> ImageBlockView(
                            block = block, tc = tc, isFirst = isFirst, isLast = isLast,
                            focused = noteTargets.getOrNull(navIndex)?.id == "block:${block.id}" || highlightedBlockId == block.id,
                            fieldFocusRequester = blockFieldRequesters[block.id],
                            onFieldFocusChanged = { focused ->
                                blockFieldFocused = focused
                                if (focused) activeBlockFieldId = block.id
                                else if (activeBlockFieldId == block.id) activeBlockFieldId = null
                            },
                            onUpdateCaption = { onUpdateBlock(block.id, it) },
                            onRemove = { onRemoveBlock(block.id) },
                            onMoveUp = { onMoveBlock(block.id, -1) },
                            onMoveDown = { onMoveBlock(block.id, 1) },
                            onAddBelow = { onAddNoteAfter(block.id) },
                            onCopyImage = { onCopyImage(block) },
                            onNavigateVideoFrame = block.videoFrame?.let { frame -> { onNavigateVideoFrame(frame) } },
                            dragHandleModifier = dragHandleModifier,
                        )
                    }
                }

                // heightIn(min=...), not height(...): a fixed height would force that exact
                // maxHeight down onto every child during measurement (Box passes its own
                // constraints straight through), silently truncating whichever block hadn't
                // reported its real size yet. A min-height only reserves scroll space; it never
                // caps how tall a child measures. blockHeights is never cleared on tab switch (see
                // its declaration) specifically so a revisited tab's blocks — already measured
                // once — get accurate positions immediately, with no re-measure flicker and no
                // window where this whole layout would need to fall back to something else.
                // Top gap before the first block card, matching the BLOCK_GAP_DP between cards.
                Box(Modifier.fillMaxWidth().padding(top = BLOCK_GAP_DP.dp).heightIn(min = (totalBlockHeightPx / blockDensity).dp)) {
                    ann.blocks.forEach { block ->
                        key(block.id) {
                            val idx = blockIds.indexOf(block.id)
                            val isFirst = idx == 0
                            val isLast = idx == blockIds.lastIndex
                            val isDragging = dragBlockId == block.id
                            val targetY = blockTargetOffsets[block.id] ?: 0f
                            // Keyed on "has this block ever been really measured": the first time a
                            // block's real height replaces its estimate, targetY jumps from a guess
                            // to the true value. Re-keying here disposes and recreates the
                            // Animatable at exactly that moment, and a freshly-created
                            // animateFloatAsState starts AT its target (no interpolation) — so that
                            // one-time correction snaps instead of visibly gliding, which is what
                            // read as blocks "recreating". Once true, this stays true (blockHeights
                            // is never cleared), so real drags/reorders keep the spring animation.
                            val everMeasured = blockHeights.containsKey(block.id)
                            val animatedY by key(everMeasured) {
                                animateFloatAsState(
                                    targetValue = targetY,
                                    animationSpec = spring(stiffness = 650f, dampingRatio = 0.86f),
                                    label = "block-y-${block.id}",
                                )
                            }
                            val blockY = sequenceRenderY(
                                isDragging = isDragging,
                                isJustReleased = justReleasedBlockId == block.id,
                                pointerY = (blockStartOffsets[block.id] ?: 0f) + dragOffsetY,
                                targetY = targetY,
                                animatedY = animatedY,
                            )
                            val dragHandleModifier = Modifier.pointerInput(block.id) {
                                detectDragGestures(
                                    onDragStart = {
                                        dragBlockId = block.id
                                        dragOffsetY = 0f
                                        justReleasedBlockId = null
                                        liveVisualBlockIds = currentBlockIds.value
                                    },
                                    onDrag = { change, delta ->
                                        change.consume()
                                        dragOffsetY += delta.y
                                        liveVisualBlockIds = blockOrderDuringDrag(
                                            visibleIds = currentBlockIds.value,
                                            draggedId = dragBlockId,
                                            dragOffsetY = dragOffsetY,
                                            heightOf = ::blockHeightOf,
                                        )
                                        // Auto-scroll the panel while the dragged block is within
                                        // the edge margin of the visible viewport — otherwise a
                                        // note could never be dragged past whatever already fits
                                        // on screen. dispatchRawDelta (not scrollBy) since onDrag
                                        // isn't a suspend callback.
                                        val draggedTop = (currentBlockStartOffsets.value[block.id] ?: 0f) + dragOffsetY
                                        val draggedBottom = draggedTop + blockHeightOf(block.id)
                                        val viewportTop = scroll.value.toFloat()
                                        val viewportBottom = viewportTop + scroll.viewportSize
                                        val overshootTop = viewportTop + autoScrollEdgePx - draggedTop
                                        val overshootBottom = draggedBottom - (viewportBottom - autoScrollEdgePx)
                                        val wantedScrollDelta = when {
                                            overshootTop > 0f -> -overshootTop * AUTO_SCROLL_SPEED_FACTOR
                                            overshootBottom > 0f -> overshootBottom * AUTO_SCROLL_SPEED_FACTOR
                                            else -> 0f
                                        }
                                        if (wantedScrollDelta != 0f) {
                                            // dragOffsetY is a raw accumulated pointer delta — it
                                            // has no idea the content just moved underneath the
                                            // cursor. Without this compensation the dragged block
                                            // drifts away from the mouse the instant auto-scroll
                                            // starts (content scrolls one way, the block's tracked
                                            // offset doesn't follow), which is what read as "bad"
                                            // auto-scroll. Use the delta dispatchRawDelta actually
                                            // consumed (not the requested one) so this stays exact
                                            // even at the top/bottom of the scrollable range.
                                            dragOffsetY += scroll.dispatchRawDelta(wantedScrollDelta)
                                        }
                                    },
                                    onDragEnd = {
                                        val releasedId = currentDragBlockId.value ?: block.id
                                        val releasedOrder = currentVisualBlockIds.value
                                        val targetIdx = releasedOrder.indexOf(releasedId)
                                        if (targetIdx >= 0 && targetIdx != currentBlockIds.value.indexOf(releasedId)) {
                                            liveVisualBlockIds = releasedOrder
                                            onReorderBlock(releasedId, targetIdx)
                                        }
                                        justReleasedBlockId = releasedId
                                        dragBlockId = null
                                        dragOffsetY = 0f
                                    },
                                    onDragCancel = {
                                        dragBlockId = null
                                        dragOffsetY = 0f
                                    },
                                )
                            }
                            Box(
                                Modifier.fillMaxWidth()
                                    .offset { IntOffset(0, blockY.roundToInt()) }
                                    .zIndex(if (isDragging) 1f else 0f)
                                    .graphicsLayer {
                                        if (isDragging) {
                                            scaleX = 1.02f
                                            scaleY = 1.02f
                                        }
                                    }
                                    .onSizeChanged { size -> blockHeights[block.id] = size.height.toFloat() }
                                    .background(if (isDragging) tc.p else Color.Transparent),
                            ) {
                                BlockContent(block, isFirst, isLast, dragHandleModifier)
                            }
                        }
                    }
                }

                if (ann.blocks.isNotEmpty()) {
                    // Global + text block button
                    AddNoteButton(tc = tc, onClick = { onAddNoteAfter(ann.blocks.last().id) })

                    // Suffix
                    AnnSection(tc) {
                        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                            AppText("Next steps", color = tc.td, fontSize = 11.sp, fontFamily = UI)
                            Spacer(Modifier.weight(1f))
                            LabelIconButton("Edit", fontSize = 10.sp, onClick = { editingTarget = EditDialogTarget.Suffix })
                        }
                        Spacer(Modifier.height(3.dp))
                        ScrollableTextArea(
                            value = ann.suffix,
                            onValue = onUpdateSuffix,
                            placeholder = "Add follow-up notes…",
                            modifier = Modifier.fillMaxWidth()
                                .focusRequester(suffixFr)
                                // hasFocus, not isFocused — see ScrollableTextArea's own note.
                                .onFocusChanged { suffixFocused = it.hasFocus },
                            fontSize = 12.sp,
                            maxHeight = 160.dp,
                            resetKey = tab.id,
                            onClear = { onUpdateSuffix("") },
                        )
                    }
                }
            }
            VerticalScrollbar(
                adapter = rememberScrollbarAdapter(scroll),
                modifier = Modifier.align(Alignment.CenterEnd).fillMaxHeight(),
                style = appScrollbarStyle(tc),
            )
        }

        pendingDiagramLibraryDeleteId?.let { id ->
            val item = diagramLibraryItems.firstOrNull { it.id == id }
            if (item == null) {
                pendingDiagramLibraryDeleteId = null
            } else {
                DiagramLibraryDeleteDialog(
                    title = item.title,
                    onConfirm = {
                        // Deletion is intentionally confined to this explicit confirmation
                        // action; a row's Delete button only opens this dialog.
                        onDeleteDiagramLibraryItem(id)
                        pendingDiagramLibraryDeleteId = null
                    },
                    onDismiss = { pendingDiagramLibraryDeleteId = null },
                )
            }
        }
    }
}

private const val DIAGRAM_LIBRARY_PANEL_ROW_DP = 42
private const val DIAGRAM_LIBRARY_PANEL_MAX_HEIGHT_DP = 168

@Composable
private fun DiagramLibrarySection(
    items: List<DiagramLibraryItem>,
    expanded: Boolean,
    onToggle: () -> Unit,
    onCreate: () -> Unit,
    onCreateFromNotes: () -> Unit,
    // Read straight off `tab.selected` by the caller — this section doesn't own the log-viewer
    // selection, it only reports its size in the popup's "From selection" subtitle.
    selectedLineCount: Int,
    notesDiagramSummary: Seq3NotesSelection?,
    onOpen: (String) -> Unit,
    onRequestDelete: (String) -> Unit,
) {
    val tc = tc()
    // Local, not lifted to AppState like RecentNotesPopup's own open flag: nothing outside this
    // section needs to know the create menu is open (no keyboard shortcut targets it the way
    // KeyboardTargetKind.NoteRecentNotes does for RecentNotesPopup), so there's nothing to gain
    // from threading it through AnnotationPanel's params and every call site.
    var createMenuOpen by remember { mutableStateOf(false) }
    SectionHeader(
        "Diagram library",
        trailing = {
            AppText(items.size.toString(), color = tc.td, fontSize = 10.sp, fontFamily = MONO)
            Spacer(Modifier.width(8.dp))
            Box {
                LabelIconButton("+ diagram", fontSize = 10.sp, onClick = { createMenuOpen = true })
                if (createMenuOpen) {
                    CreateDiagramPopup(
                        selectedLineCount = selectedLineCount,
                        notesDiagramSummary = notesDiagramSummary,
                        onCreateFromSelection = { createMenuOpen = false; onCreate() },
                        onCreateFromNotes = { createMenuOpen = false; onCreateFromNotes() },
                        onDismiss = { createMenuOpen = false },
                        tc = tc,
                    )
                }
            }
        },
        expanded = expanded,
        onToggle = onToggle,
    )
    if (!expanded) return

    // AnnotationPanel's outer Column is itself vertically scrollable, so use a bounded fixed
    // viewport rather than heightIn(max): Compose otherwise receives an unbounded height and the
    // inner scrollbar cannot become useful.
    // The viewport keeps four dp of breathing room above and below each library list. Include
    // those in the measured section height so the 42-dp item and its related range line are not
    // clipped by the padded Box.
    val listHeight = (items.size * DIAGRAM_LIBRARY_PANEL_ROW_DP)
        .coerceIn(DIAGRAM_LIBRARY_PANEL_ROW_DP, DIAGRAM_LIBRARY_PANEL_MAX_HEIGHT_DP).dp
        .plus(8.dp)
    val listScroll = rememberScrollState()
    val needsScrollbar = items.size * DIAGRAM_LIBRARY_PANEL_ROW_DP > DIAGRAM_LIBRARY_PANEL_MAX_HEIGHT_DP
    Box(Modifier.fillMaxWidth().height(listHeight).padding(vertical = 4.dp)) {
        if (items.isEmpty()) {
            AppText(
                "No saved diagrams for this log.",
                color = tc.td,
                fontSize = 10.sp,
                modifier = Modifier.align(Alignment.CenterStart).padding(horizontal = 12.dp),
            )
        } else {
            Column(
                Modifier.fillMaxSize()
                    .verticalScroll(listScroll)
                    .padding(end = if (needsScrollbar) 8.dp else 0.dp),
            ) {
                items.forEachIndexed { index, item ->
                    Row(
                        Modifier.fillMaxWidth().height(DIAGRAM_LIBRARY_PANEL_ROW_DP.dp)
                            .clickable { onOpen(item.id) },
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Row(
                            Modifier.fillMaxWidth()
                                .fillMaxHeight()
                                .padding(start = 12.dp, end = if (needsScrollbar) 20.dp else 12.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(6.dp),
                        ) {
                            Column(
                                Modifier.weight(1f).fillMaxHeight(),
                                verticalArrangement = Arrangement.spacedBy(1.dp, Alignment.CenterVertically),
                            ) {
                                AppText(
                                    item.title.ifBlank { "Untitled diagram" },
                                    color = tc.tx,
                                    fontSize = 10.sp,
                                    fontWeight = FontWeight.SemiBold,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                )
                                AppText(
                                    item.parsed?.let { parsed -> rangeSummary(parsed.document.range) } ?: "Unavailable diagram data",
                                    color = tc.td,
                                    fontSize = 9.sp,
                                    maxLines = 1,
                                )
                            }
                            AppButton("Delete", { onRequestDelete(item.id) }, variant = ButtonVariant.Ghost)
                        }
                    }
                    if (index != items.lastIndex) Divider()
                }
            }
            if (needsScrollbar) {
                VerticalScrollbar(
                    adapter = rememberScrollbarAdapter(listScroll),
                    // Leave a visible gutter before AnnotationPanel's outer scrollbar; the two
                    // tracks otherwise overlap at the right edge when the library overflows.
                    modifier = Modifier.align(Alignment.CenterEnd)
                        .fillMaxHeight()
                        .padding(end = 3.dp)
                        .width(6.dp),
                    style = appScrollbarStyle(tc),
                )
            }
        }
    }
}

/**
 * The Diagram library's `+ diagram` two-option menu — "From selection" (the existing behaviour,
 * unchanged) and "From notes" (generates from exactly the log lines curated into this tab's Notes
 * document, see `Seq3Session.beginFromNotes`). Modeled directly on [RecentNotesPopup] below (same
 * `Popup` alignment/offset/`PopupProperties(focusable = true)`, bordered `Box`, hover rows, Esc/
 * Enter/arrow-key roving) rather than a split button: the trailing slot this renders into already
 * sits inside [SectionHeader]'s own `HoverBox(onClick = onToggle)`, so a single click target that
 * consumes its own tap (as [LabelIconButton]'s `clickable` already does) is the least fragile way
 * to avoid also toggling the section underneath it.
 *
 * Each row's grey subtitle is how the user is told, BEFORE creating, what a click will produce —
 * in particular, the notes row's `"· K cross-file skipped"` suffix is the only place a compare-
 * mode cross-file annotation's exclusion is surfaced at all (see `Seq3NotesSelection.
 * skippedCrossFileCount`'s own doc). The notes row disables itself (greyed, non-clickable, and
 * excluded from arrow-key roving via [RovingItem.enabled]) when there is nothing to draw.
 */
@Composable
private fun CreateDiagramPopup(
    selectedLineCount: Int,
    notesDiagramSummary: Seq3NotesSelection?,
    onCreateFromSelection: () -> Unit,
    onCreateFromNotes: () -> Unit,
    onDismiss: () -> Unit,
    tc: ThemeColors,
) {
    val density = LocalDensity.current.density
    // Null reads the same as "nothing curated yet" (the caller computes this off tab.annotations
    // and may not have run it before the first composition) — never crashes into a stale-looking
    // enabled row with no data behind it.
    val notesEntryCount = notesDiagramSummary?.entryIds?.size ?: 0
    val notesEnabled = notesEntryCount > 0
    val notesSubtitle = if (!notesEnabled) {
        "no log lines in notes"
    } else {
        buildString {
            append("$notesEntryCount lines from ${notesDiagramSummary!!.usedBlockCount} note blocks")
            if (notesDiagramSummary.skippedCrossFileCount > 0) {
                append(" · ${notesDiagramSummary.skippedCrossFileCount} cross-file skipped")
            }
        }
    }
    val selectionSubtitle = if (selectedLineCount > 0) "$selectedLineCount selected lines" else "whole visible view"
    val rovingRows = listOf(RovingItem("selection", enabled = true), RovingItem("notes", enabled = notesEnabled))
    Popup(
        alignment = Alignment.TopEnd,
        offset = IntOffset(0, (34 * density).roundToInt()),
        onDismissRequest = onDismiss,
        properties = PopupProperties(focusable = true),
    ) {
        val popupFr = remember { FocusRequester() }
        var selectedIdx by remember { mutableStateOf(0) }
        LaunchedEffect(Unit) { runCatching { popupFr.requestFocus() } }
        Box(
            Modifier.width(260.dp)
                .background(tc.p, RoundedCornerShape(7.dp))
                .border(1.dp, tc.br, RoundedCornerShape(7.dp))
                .focusRequester(popupFr)
                .focusable()
                .onPreviewKeyEvent { ev ->
                    if (ev.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
                    when (ev.key) {
                        Key.DirectionDown -> { selectedIdx = rovingMove(rovingRows, selectedIdx, +1, wrap = true); true }
                        Key.DirectionUp -> { selectedIdx = rovingMove(rovingRows, selectedIdx, -1, wrap = true); true }
                        Key.Enter, Key.NumPadEnter -> {
                            when (rovingRows.getOrNull(selectedIdx)?.id) {
                                "selection" -> onCreateFromSelection()
                                "notes" -> if (notesEnabled) onCreateFromNotes()
                            }
                            true
                        }
                        Key.Escape -> { onDismiss(); true }
                        else -> false
                    }
                },
        ) {
            Column(Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
                HoverBox(
                    modifier = Modifier.fillMaxWidth(),
                    forceHover = selectedIdx == 0,
                    onClick = onCreateFromSelection,
                ) {
                    Column(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 6.dp)) {
                        AppText("From selection", color = tc.tx, fontSize = 11.sp)
                        AppText(selectionSubtitle, color = tc.td, fontSize = 9.sp)
                    }
                }
                Divider()
                HoverBox(
                    modifier = Modifier.fillMaxWidth(),
                    forceHover = selectedIdx == 1,
                    onClick = if (notesEnabled) onCreateFromNotes else null,
                ) {
                    Column(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 6.dp)) {
                        AppText("From notes", color = if (notesEnabled) tc.tx else tc.td, fontSize = 11.sp)
                        AppText(notesSubtitle, color = tc.td, fontSize = 9.sp)
                    }
                }
            }
        }
    }
}

@Composable
private fun DiagramLibraryDeleteDialog(title: String, onConfirm: () -> Unit, onDismiss: () -> Unit) {
    val tc = tc()
    Dialog(onDismissRequest = onDismiss) {
        Column(
            Modifier.width(360.dp).background(tc.p, CORNER_MD).border(1.dp, tc.br, CORNER_MD).padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            AppText("Delete saved diagram?", color = tc.tx, fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
            AppText(
                "\"${title.ifBlank { "Untitled diagram" }}\" will be removed from the diagram library. Existing note snapshots are unchanged.",
                color = tc.td,
                fontSize = 11.sp,
                maxLines = 3,
            )
            Spacer(Modifier.height(6.dp))
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterHorizontally)) {
                DialogActionButton("Delete", active = true, danger = true, onClick = onConfirm)
                DialogActionButton("Cancel", active = false, onClick = onDismiss)
            }
        }
    }
}

@Composable
private fun RecentNotesPopup(
    recentNotes: List<String>,
    // Full absolute path of the note THIS tab is pinned to (AppState.activeNoteFilePath), or null.
    // Compared by exact path equality, not by File(path).name — see activeNoteFilePath's own
    // comment for why a name-only match can't disambiguate two same-named notes living in
    // different noteLookupDirs() entries, both of which can legitimately appear in this same list.
    activeNotePath: String?,
    onOpenNote: (File) -> Unit,
    onDismiss: () -> Unit,
    tc: ThemeColors,
) {
    val density = LocalDensity.current.density
    Popup(
        alignment = Alignment.TopEnd,
        offset = IntOffset(0, (34 * density).roundToInt()),
        onDismissRequest = onDismiss,
        properties = PopupProperties(focusable = true),
    ) {
        val displayNotes = recentNotes.take(10)
        val popupFr = remember { FocusRequester() }
        var selectedIdx by remember(displayNotes) { mutableStateOf(displayNotes.indexOfFirst { File(it).exists() }.coerceAtLeast(0)) }
        LaunchedEffect(Unit) { runCatching { popupFr.requestFocus() } }
        Box(
            Modifier.width(300.dp)
                .background(tc.p, RoundedCornerShape(7.dp))
                .border(1.dp, tc.br, RoundedCornerShape(7.dp))
                .focusRequester(popupFr)
                .focusable()
                .onPreviewKeyEvent { ev ->
                    if (ev.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
                    when (ev.key) {
                        Key.DirectionDown -> {
                            selectedIdx = rovingMove(
                                displayNotes.map { RovingItem(it, File(it).exists()) },
                                selectedIdx,
                                +1,
                                wrap = true,
                            )
                            true
                        }
                        Key.DirectionUp -> {
                            selectedIdx = rovingMove(
                                displayNotes.map { RovingItem(it, File(it).exists()) },
                                selectedIdx,
                                -1,
                                wrap = true,
                            )
                            true
                        }
                        Key.Enter, Key.NumPadEnter -> {
                            displayNotes.getOrNull(selectedIdx)
                                ?.let(::File)
                                ?.takeIf { it.exists() }
                                ?.let(onOpenNote)
                            true
                        }
                        Key.Escape -> { onDismiss(); true }
                        else -> false
                    }
                },
        ) {
            val popupScroll = rememberScrollState()
            Box(Modifier.heightIn(max = 260.dp)) {
                Column(Modifier.fillMaxWidth().verticalScroll(popupScroll).padding(vertical = 4.dp)) {
                    displayNotes.forEachIndexed { idx, path ->
                        val file = File(path)
                        val exists = file.exists()
                        // Exact-path match against activeNotePath — see this popup's own param
                        // comment. Deliberately independent of `forceHover`/selectedIdx below: that
                        // is keyboard-roving focus (moves with arrow keys, resets whenever the popup
                        // reopens) and says nothing about which file is actually open, while this is
                        // a fixed fact about tab state that shouldn't flicker as the user arrows
                        // around the list. Conflating the two would make "currently selected" and
                        // "currently open" indistinguishable — often the same row, but not always
                        // (e.g. arrowing to preview a different entry without opening it yet).
                        val isActive = activeNotePath != null && path == activeNotePath
                        TooltipArea(
                            tooltip = {
                                Box(
                                    Modifier
                                        .widthIn(max = 560.dp)
                                        .background(tc.p2, RoundedCornerShape(4.dp))
                                        .border(0.5.dp, tc.br, RoundedCornerShape(4.dp))
                                        .padding(horizontal = 8.dp, vertical = 4.dp),
                                ) {
                                    AppText(path, color = tc.tx, fontSize = 11.sp, fontFamily = MONO, maxLines = 3)
                                }
                            },
                        ) {
                            HoverBox(
                                modifier = Modifier.fillMaxWidth(),
                                forceHover = idx == selectedIdx,
                                onClick = if (exists) ({ onOpenNote(file) }) else null,
                            ) {
                                Row(
                                    Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 6.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                ) {
                                    // Fixed-width gutter so the checkmark's presence/absence never
                                    // shifts the filename column between rows.
                                    AppText(
                                        if (isActive) "✓" else "",
                                        color = tc.ac,
                                        fontSize = 11.sp,
                                        fontFamily = MONO,
                                        modifier = Modifier.width(14.dp),
                                    )
                                    Column(Modifier.weight(1f)) {
                                        AppText(
                                            file.name,
                                            color = if (exists) tc.tx else tc.td,
                                            fontSize = 11.sp,
                                            fontFamily = MONO,
                                            overflow = TextOverflow.Ellipsis,
                                        )
                                        AppText(file.parent ?: path, color = tc.td, fontSize = 9.sp, fontFamily = MONO, overflow = TextOverflow.Ellipsis)
                                    }
                                }
                            }
                        }
                    }
                }
                VerticalScrollbar(
                    adapter = rememberScrollbarAdapter(popupScroll),
                    modifier = Modifier.align(Alignment.CenterEnd).fillMaxHeight(),
                    style = appScrollbarStyle(tc),
                )
            }
        }
    }
}

// ── Markdown preview dialog ────────────────────────────────────────────
@Composable
private fun MdPreviewDialog(
    tab: LogTab,
    settings: AppSettings,
    mono: FontFamily,
    onCopy: () -> Unit,
    onCopyRichPreview: () -> Unit,
    onExportFrames: () -> Unit,
    onDismiss: () -> Unit,
) {
    val tc = tc()
    var copied by remember { mutableStateOf(false) }
    var richCopied by remember { mutableStateOf(false) }
    var framesExported by remember { mutableStateOf(false) }
    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Column(
            Modifier.fillMaxWidth(0.75f).fillMaxHeight(0.8f)
                .background(tc.p, RoundedCornerShape(8.dp))
                .border(1.dp, tc.br, RoundedCornerShape(8.dp)),
        ) {
            Row(
                Modifier.fillMaxWidth().height(40.dp).background(tc.p2, RoundedCornerShape(topStart = 8.dp, topEnd = 8.dp))
                    .padding(horizontal = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                AppText("Markdown Preview", color = tc.ts, fontSize = 13.sp, modifier = Modifier.weight(1f))
                AppButton(
                    if (copied) "Copied!" else "Copy",
                    onClick = {
                        onCopy()
                        copied = true
                    },
                    modifier = Modifier.height(28.dp),
                )
                TooltipArea(
                    tooltip = {
                        ToolbarTooltip(
                            "Copies text + inline images as rich HTML. Jira Cloud's comment editor generally " +
                                "accepts pasted HTML; Server/Data Center may not.",
                        )
                    },
                ) {
                    AppButton(
                        if (richCopied) "Copied!" else "Copy as HTML",
                        onClick = {
                            onCopyRichPreview()
                            richCopied = true
                        },
                        modifier = Modifier.height(28.dp),
                    )
                }
                TooltipArea(
                    tooltip = {
                        ToolbarTooltip(
                            "Writes each note image as frame-0N.jpg into a <logname>_frames folder inside " +
                                "a folder you choose. With the Jira {code:java} style, Copy's text references " +
                                "images by that filename — paste it, then attach the exported files so Jira " +
                                "renders them inline.",
                        )
                    },
                ) {
                    AppButton(
                        if (framesExported) "Exported!" else "Export frames",
                        onClick = {
                            onExportFrames()
                            framesExported = true
                        },
                        modifier = Modifier.height(28.dp),
                    )
                }
                CloseButton(onClick = onDismiss)
            }
            Box(Modifier.fillMaxWidth().height(1.dp).background(tc.br))
            val scroll = rememberScrollState()
            Box(Modifier.fillMaxSize()) {
                // Body is a plain Column inside a verticalScroll (not a LazyColumn, which
                // SelectionContainer can't span), so wrapping just the body here — not the outer
                // Column with the header Row's Copy/Copy as HTML/Export frames buttons, and not the
                // whole dialog per the b/372053402 note at CaseLibraryDialog.kt:342-352 — is safe and
                // needs no extra sizing modifier on SelectionContainer itself.
                Box(Modifier.fillMaxSize().verticalScroll(scroll).padding(16.dp)) {
                    SelectionContainer { RenderedMarkdownPreview(tab, settings, mono, tc) }
                }
                VerticalScrollbar(
                    adapter = rememberScrollbarAdapter(scroll),
                    modifier = Modifier.align(Alignment.CenterEnd).fillMaxHeight().padding(vertical = 4.dp).width(6.dp),
                    style = appScrollbarStyle(tc),
                )
            }
        }
    }
}

@Composable
private fun RenderedMarkdownPreview(tab: LogTab, settings: AppSettings, mono: FontFamily, tc: ThemeColors) {
    val label = settings.annotationPrefixLabel.trim().ifBlank { "From" }
    var blockNumber = 1
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        if (tab.annotations.prefix.isNotBlank()) {
            AnnotationMarkdownText(tab.annotations.prefix, tc)
        }
        tab.annotations.blocks.forEach { block ->
            when (block) {
                is AnnBlock.Note -> if (block.text.isNotBlank()) {
                    // Diagrams are drawn out-of-band from the Markdown renderer, the same way
                    // AnnBlock.Image is below — the renderer has no Mermaid support, and feeding it
                    // the note text raw would show a wall of spec-header JSON followed by source.
                    val summary = remember(block.text) { Seq3NoteSummaryCache.summary(block.text) }
                    val expandedDiagram = if (summary != null) {
                        rememberExpandedDiagram(block.text, settings, expanded = true)
                    } else {
                        null
                    }
                    val parsed = expandedDiagram?.parsed
                    val display = expandedDiagram?.display
                    if (parsed != null && display != null) {
                        val rendered = display.rendered
                        val bitmap = display.bitmap
                        // A sequence diagram needs every available horizontal pixel. The bounded
                        // viewport below owns vertical overflow, preserving readable text without
                        // shrinking the card to an arbitrary fraction of the prose column.
                        BoxWithConstraints(Modifier.fillMaxWidth()) {
                            val previewWidth = maxWidth
                            val aspectRatio = rendered.widthPx.toFloat() / rendered.heightPx.coerceAtLeast(1)
                            // The card's 10.dp padding is outside the image viewport, so size
                            // from the actual drawable width to keep the raster ratio exact.
                            val imageWidth = (previewWidth - 20.dp).coerceAtLeast(1.dp)
                            val renderedHeight = imageWidth / aspectRatio
                            // Keep the full-width raster at its natural aspect ratio and scroll
                            // it inside a deliberately bounded viewport. This is keyed by the
                            // note text so every diagram preview has its own resize setting.
                            var viewportHeight by remember(block.text) { mutableStateOf(280.dp) }
                            Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.TopCenter) {
                                Column(
                                    Modifier.fillMaxWidth()
                                        .background(tc.bg, CORNER_SM)
                                        .border(1.dp, tc.br, CORNER_SM)
                                        .padding(10.dp),
                                    verticalArrangement = Arrangement.spacedBy(6.dp),
                                ) {
                                    AppText(
                                        parsed.document.title.ifBlank { "Sequence diagram" },
                                        color = tc.ts,
                                        fontSize = 12.sp,
                                        fontWeight = FontWeight.SemiBold,
                                    )
                                    // The document is always drawable in v3 (Seq3Codec.kt's own
                                    // doc: it's returned even when the fence's hash no longer
                                    // matches) — only the drift warning needs surfacing here.
                                    if (!parsed.sourceHashMatches) {
                                        AppText("Diagram source has drifted from its model", color = tc.td, fontSize = 10.sp)
                                    }
                                    // Do not fit the raster into the viewport height: a sequence
                                    // diagram stays readable only when it fills the card width.
                                    // The viewport owns the vertical scrolling instead, so a tall
                                    // image never turns the surrounding Markdown preview into one
                                    // long scroll.
                                    val imageScroll = remember(block.text) { ScrollState(0) }
                                    Box(
                                        Modifier.fillMaxWidth().height(viewportHeight).clip(CORNER_SM),
                                    ) {
                                        Box(Modifier.fillMaxSize().verticalScroll(imageScroll)) {
                                            Image(
                                                bitmap = bitmap,
                                                contentDescription = "Sequence diagram",
                                                modifier = Modifier.fillMaxWidth().height(renderedHeight),
                                                contentScale = ContentScale.FillWidth,
                                            )
                                        }
                                        VerticalScrollbar(
                                            adapter = rememberScrollbarAdapter(imageScroll),
                                            modifier = Modifier.align(Alignment.CenterEnd)
                                                .fillMaxHeight().padding(vertical = 3.dp).width(6.dp),
                                            style = appScrollbarStyle(tc),
                                        )
                                    }
                                    VDivider { delta ->
                                        viewportHeight = (viewportHeight + delta.dp).coerceIn(160.dp, 700.dp)
                                    }
                                }
                            }
                        }
                        if (settings.numberAnnotationBlocks) blockNumber++
                    } else if (summary != null && expandedDiagram == null) {
                        // The dialog starts its diagram work asynchronously too.  Keep the rest of
                        // the Markdown preview responsive while large attachments rasterize.
                        AppText("Rendering ${summary.title.ifBlank { "sequence diagram" }}…", color = tc.td, fontSize = 11.sp)
                        if (settings.numberAnnotationBlocks) blockNumber++
                    } else {
                        AnnotationMarkdownText(
                            // A diagram note with no drawable model still shouldn't leak its header
                            // into the preview; stripping is a no-op for an ordinary note.
                            text = if (summary != null) stripDiagramHeaderFast(block.text) else block.text,
                            tc = tc,
                            numberPrefix = if (settings.numberAnnotationBlocks) "${blockNumber++}. " else null,
                        )
                    }
                }

                is AnnBlock.LogRef -> {
                    val rows = block.resolveRows(tab)
                    val localSource = block.sourceTabId == null && rows.all { tab.rmap[it.id] == it }
                    val context = rememberAnnotationLogLineContext(tab, settings, localSource)
                    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        if (block.caption.isNotBlank() || settings.numberAnnotationBlocks) {
                            AnnotationMarkdownText(
                                text = block.caption,
                                tc = tc,
                                numberPrefix = if (settings.numberAnnotationBlocks) "${blockNumber++}. " else null,
                            )
                        }
                        if (block.sourceFilename != null) {
                            AppText("$label ${block.sourceFilename}", color = tc.td, fontSize = 11.sp, fontFamily = mono)
                        }
                        Column(
                            Modifier.fillMaxWidth()
                                .background(tc.bg, CORNER_SM)
                                .border(1.dp, tc.br, CORNER_SM)
                                .padding(10.dp),
                            verticalArrangement = Arrangement.spacedBy(2.dp),
                        ) {
                            rows.forEach { row ->
                                AppText(
                                    presentLogLine(tab, row, settings, context, allowProcessName = localSource),
                                    color = tc.ts,
                                    fontSize = 12.sp,
                                    fontFamily = mono,
                                    maxLines = Int.MAX_VALUE,
                                    overflow = TextOverflow.Clip,
                                )
                            }
                        }
                    }
                }

                is AnnBlock.Image -> {
                    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        if (block.caption.isNotBlank() || settings.numberAnnotationBlocks) {
                            AnnotationMarkdownText(
                                text = block.caption,
                                tc = tc,
                                numberPrefix = if (settings.numberAnnotationBlocks) "${blockNumber++}. " else null,
                            )
                        }
                        block.displayProvenance?.let {
                            AppText(it, color = tc.td, fontSize = 11.sp, fontFamily = mono)
                        }
                        val bitmap = decodeImageBlockBitmap(block.bytes)
                        if (bitmap != null) {
                            Image(
                                bitmap = bitmap,
                                contentDescription = null,
                                modifier = Modifier.fillMaxWidth().heightIn(max = (IMAGE_BLOCK_THUMBNAIL_DP * 2).dp),
                                contentScale = ContentScale.Fit,
                            )
                        }
                    }
                }
            }
        }
        if (tab.annotations.suffix.isNotBlank()) {
            Box(Modifier.fillMaxWidth().height(1.dp).background(tc.br))
            AnnotationMarkdownText(tab.annotations.suffix, tc)
        }
    }
}

/**
 * Keeps the two on-screen annotation surfaces in lockstep with buildMd()/rich copy. A
 * persisted or cross-tab block has no safe relationship to this tab's visible-row baseline or
 * process-name map, so it deliberately omits Δt and falls back to a numeric PID.
 */
@Composable
private fun rememberAnnotationLogLineContext(
    tab: LogTab,
    settings: AppSettings,
    localSource: Boolean,
): LogLinePresentationContext? {
    if (!localSource || !settings.copyTimeDelta || !tab.showTimeDelta) return null
    return remember(tab.id, tab.logData, tab.filter, tab.selected, settings) {
        LogLinePresentationContext(tab, settings, visibleEntries(tab))
    }
}

@Composable
internal fun AnnotationMarkdownText(text: String, tc: ThemeColors, numberPrefix: String? = null) {
    if (text.isBlank() && numberPrefix == null) return
    val content: @Composable () -> Unit = {
        val markdownState = rememberMarkdownState(content = text.ifBlank { " " })
        Markdown(
            markdownState,
            colors = annotationMarkdownColors(tc),
            typography = annotationMarkdownTypography(tc),
            modifier = Modifier.fillMaxWidth(),
        )
    }
    if (numberPrefix == null) {
        content()
    } else {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.Top) {
            AppText(numberPrefix, color = tc.tx, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
            Box(Modifier.weight(1f)) { content() }
        }
    }
}

@Composable
internal fun annotationMarkdownColors(colors: ThemeColors) = markdownColor(
    text = colors.tx,
    codeBackground = colors.bg,
    inlineCodeBackground = colors.bg,
    dividerColor = colors.br,
    tableBackground = colors.p2,
)

@Composable
internal fun annotationMarkdownTypography(colors: ThemeColors): MarkdownTypography {
    val body = TextStyle(color = colors.tx, fontSize = 13.sp, fontFamily = UI)
    val code = TextStyle(color = colors.ts, fontSize = 12.sp, fontFamily = FontFamily.Monospace)
    val heading = body.copy(fontWeight = FontWeight.SemiBold)
    return markdownTypography(
        h1 = heading.copy(fontSize = 16.sp),
        h2 = heading.copy(fontSize = 15.sp),
        h3 = heading.copy(fontSize = 14.sp),
        h4 = heading.copy(fontSize = 13.sp),
        h5 = heading.copy(fontSize = 13.sp),
        h6 = heading.copy(fontSize = 13.sp),
        text = body,
        code = code,
        inlineCode = code,
        quote = body.copy(fontStyle = FontStyle.Italic),
        paragraph = body,
        ordered = body,
        bullet = body,
        list = body,
        table = body,
    )
}

// ── Shared block chrome ("Note popup redesign" 1b) ─────────────────────

// Card shape for every report-panel block — a bit rounder than the shared CORNER_MD (buttons) so
// note/log/image/diagram cards read as a distinct container.
private val BLOCK_CORNER = RoundedCornerShape(6.dp)

// Text fields and recessed rows (log excerpt summary, diagram collapsed row) share this slightly
// tighter radius, matching the "Note popup redesign" 1b handoff's field radius — distinct from the
// card's own BLOCK_CORNER above and from the shared CORNER_SM/CORNER_MD tokens used elsewhere.
private val FIELD_CORNER = RoundedCornerShape(5.dp)

// The gap between consecutive blocks, replacing the old edge-to-edge 2dp coloured border. Lives as
// BlockCard's own bottom padding rather than a separate Spacer between blocks — see BlockCard's doc.
private const val BLOCK_GAP_DP = 8

// A dashed rounded-rect outline, drawn rather than borrowed from Modifier.border (which has no
// dashed variant) — used only by AddNoteButton below.
private fun Modifier.dashedOutline(color: Color, corner: Dp, strokeWidth: Dp = 1.dp): Modifier = this.drawBehind {
    val stroke = strokeWidth.toPx()
    drawRoundRect(
        color = color,
        style = Stroke(width = stroke, pathEffect = PathEffect.dashPathEffect(floatArrayOf(4.dp.toPx(), 3.dp.toPx()))),
        cornerRadius = CornerRadius(corner.toPx(), corner.toPx()),
        topLeft = Offset(stroke / 2f, stroke / 2f),
        size = Size(size.width - stroke, size.height - stroke),
    )
}

/**
 * Shared chrome for every report-panel block card ("Note popup redesign" 1b): a 1dp `tc.br` border
 * (1.5dp `tc.ac` when [focused] — keyboard nav or the AI `highlightedBlockId`, same signal as
 * before, just thinner) around a 6dp-rounded `tc.p` card, with a 3dp left edge in [edgeColor]
 * identifying the block's type/level (replacing the old edge-to-edge 2dp coloured border). The
 * gap between consecutive blocks lives HERE, as this composable's own bottom padding — not as a
 * separate Spacer between blocks — so it stays inside the Box [AnnotationPanel] measures via
 * `onSizeChanged` for drag-reorder offsets; a gap living outside that Box would desync
 * `blockHeights` from the actual on-screen block spacing and break the drag math.
 */
@Composable
private fun BlockCard(
    tc: ThemeColors,
    edgeColor: Color,
    focused: Boolean,
    header: @Composable () -> Unit,
    body: @Composable ColumnScope.() -> Unit,
) {
    Column(Modifier.fillMaxWidth().padding(start = 10.dp, end = 10.dp, bottom = BLOCK_GAP_DP.dp)) {
        Column(
            Modifier.fillMaxWidth()
                .clip(BLOCK_CORNER)
                .background(tc.p, BLOCK_CORNER)
                .border(if (focused) 1.5.dp else 1.dp, if (focused) tc.ac else tc.br, BLOCK_CORNER)
                // Drawn last (after the border above) so the edge overpaints the border's own left
                // segment, the same visual as a CSS border-left override in the design handoff.
                .drawBehind { drawRect(edgeColor, size = Size(3.dp.toPx(), size.height)) },
        ) {
            Box(Modifier.fillMaxWidth().padding(horizontal = 9.dp, vertical = 7.dp)) { header() }
            Column(
                Modifier.fillMaxWidth().padding(start = 9.dp, end = 9.dp, bottom = 9.dp),
                verticalArrangement = Arrangement.spacedBy(7.dp),
                content = body,
            )
        }
    }
}

/**
 * Shared "empty fields collapse to one line" text field for a block's caption/note text: no fixed
 * minimum height, so an empty-and-unfocused field sits at its natural one-line height instead of
 * the old fixed 40-60dp box that was the main source of empty space between blocks. Focusing an
 * empty field grows it to a comfortable 52dp so there's room to start typing; a field with content
 * already sizes to that content regardless of focus, exactly as before.
 */
@Composable
private fun BlockTextField(
    value: String,
    onValueChange: (String) -> Unit,
    placeholder: String,
    tc: ThemeColors,
    fieldFocusRequester: FocusRequester?,
    onFieldFocusChanged: (Boolean) -> Unit,
) {
    var isFocused by remember { mutableStateOf(false) }
    BasicTextField(
        value = value,
        onValueChange = onValueChange,
        textStyle = TextStyle(color = tc.tx, fontSize = 12.sp, fontFamily = FontFamily.Default, lineHeight = 18.sp),
        cursorBrush = SolidColor(tc.ac),
        modifier = Modifier.fillMaxWidth()
            .background(tc.bg, FIELD_CORNER)
            .border(1.dp, tc.br, FIELD_CORNER)
            .then(if (fieldFocusRequester != null) Modifier.focusRequester(fieldFocusRequester) else Modifier)
            .onFocusChanged { isFocused = it.isFocused; onFieldFocusChanged(it.isFocused) }
            .then(if (value.isEmpty() && isFocused) Modifier.heightIn(min = 52.dp) else Modifier)
            .padding(horizontal = 8.dp, vertical = 5.dp),
        decorationBox = { inner ->
            if (value.isEmpty()) AppText(placeholder, color = tc.td, fontSize = 12.sp)
            inner()
        },
    )
}

/** Renamed from "+ Add text block" per the note popup redesign's user-decision override: the
 *  button stays (no "Add block ▾" menu) since this panel still only creates text-note blocks
 *  directly — images/diagrams/log refs come from the log viewer, paste/drop, or the diagram
 *  library. Dashed outline so it reads as an affordance to add something, not another block. */
@Composable
private fun AddNoteButton(tc: ThemeColors, onClick: () -> Unit) {
    var hovered by remember { mutableStateOf(false) }
    Box(
        Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp)
            .clip(BLOCK_CORNER)
            .background(if (hovered) tc.hv else Color.Transparent, BLOCK_CORNER)
            .dashedOutline(if (hovered) tc.td else tc.br, 6.dp)
            .clickable(onClick = onClick)
            .onPointerEvent(PointerEventType.Enter) { hovered = true }
            .onPointerEvent(PointerEventType.Exit) { hovered = false }
            .padding(vertical = 5.dp),
        contentAlignment = Alignment.Center,
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            AppText("+", color = tc.td, fontSize = 11.sp)
            AppText("Add note", color = tc.ts, fontSize = 11.sp)
        }
    }
}

// ── Note block ─────────────────────────────────────────────────────────
@Composable
private fun NoteBlock(
    block: AnnBlock.Note,
    tc: ThemeColors,
    settings: AppSettings,
    isFirst: Boolean, isLast: Boolean,
    focused: Boolean,
    fieldFocusRequester: FocusRequester?,
    onFieldFocusChanged: (Boolean) -> Unit,
    onUpdate: (String) -> Unit,
    onEdit: () -> Unit,
    onRemove: () -> Unit,
    onMoveUp: () -> Unit, onMoveDown: () -> Unit,
    onAddBelow: () -> Unit,
    dragHandleModifier: Modifier = Modifier,
    onBeforeToggleDiagram: () -> Unit = {},
    onEditDiagram: () -> Unit = {},
    onNavigateDiagramLine: (Int) -> Unit = {},
    onImportLinkedDiagram: (String, Seq3Dialect, Boolean) -> Seq3SourceImportResult? = { _, _, _ -> null },
    onCopyDiagramImage: (png: ByteArray, fallbackText: String) -> Unit = { _, _ -> },
) {
    // Diagram notes are cards, not an exposed model header plus dialect source. Opening the
    // workspace is the normal route for editing the model itself, which keeps the rendered model
    // and saved source in sync — but the caption alongside it is plain text, so Edit still opens
    // the rich editor dialog on that caption (never on the raw Note.text carrying the encoded
    // header; see the editingTarget handling in AnnotationPanel). A malformed/non-diagram note
    // remains the ordinary editable text control below.
    val diagram = remember(block.text) { Seq3NoteSummaryCache.summary(block.text) }
    var diagramExpanded by remember(block.id, block.text) { mutableStateOf(false) }
    BlockCard(
        tc = tc,
        edgeColor = if (diagram != null) tc.ac else tc.br,
        focused = focused,
        header = {
            BlockControls(
                if (diagram != null) "diagram" else "text",
                tc.ac, isFirst, isLast, onMoveUp, onMoveDown, onRemove, onAddBelow, dragHandleModifier = dragHandleModifier,
                onEdit = onEdit,
                onNavigate = if (diagram != null) onEditDiagram else null,
                onNavigateTooltip = if (diagram != null) "Open diagram workspace" else null,
                onCopyImage = diagram?.let { summary ->
                    {
                        // Copy is an explicit action, so it is the right point to pay for parsing
                        // and rasterizing. A folded card itself stays document-free. WP4: this
                        // document is fully in hand here, so it resolves ITS OWN theme rather than
                        // the ambient app theme.
                        Seq3NoteParseCache.parse(block.text)?.document?.let { document ->
                            onCopyDiagramImage(
                                Seq3RenderCache.brandedPngBytes(
                                    Seq3RenderCache.layout(document),
                                    resolveSeq3ThemeColors(document, settings).toSeq3RasterTheme(),
                                ),
                                "Sequence diagram: ${summary.title.ifBlank { "Sequence diagram" }}",
                            )
                        }
                    }
                },
                afterBadgeContent = diagram?.let { summary ->
                    {
                        DiagramExportModeSwitcher(
                            noteText = block.text,
                            exportMode = summary.exportMode,
                            onUpdateDiagramText = onUpdate,
                        )
                    }
                },
            )
        },
    ) {
        if (diagram != null) {
            DiagramNoteView(
                noteText = block.text,
                summary = diagram,
                tc = tc,
                settings = settings,
                fieldFocusRequester = fieldFocusRequester,
                onFieldFocusChanged = onFieldFocusChanged,
                onUpdateDiagramText = onUpdate,
                onNavigateLine = onNavigateDiagramLine,
                onImportLinkedDiagram = onImportLinkedDiagram,
                expanded = diagramExpanded,
                onToggleExpanded = {
                    onBeforeToggleDiagram()
                    diagramExpanded = !diagramExpanded
                },
            )
        } else {
            BlockTextField(
                value = block.text,
                onValueChange = onUpdate,
                placeholder = "Write your note…",
                tc = tc,
                fieldFocusRequester = fieldFocusRequester,
                onFieldFocusChanged = onFieldFocusChanged,
            )
        }
    }
}

@Composable
private fun DiagramExportModeSwitcher(
    noteText: String,
    exportMode: DiagramExportMode,
    onUpdateDiagramText: (String) -> Unit,
) {
    val tc = tc()
    val shape = RoundedCornerShape(6.dp)
    Row(
        modifier = Modifier.height(18.dp).border(0.5.dp, tc.br, shape).clip(shape),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        TooltipArea(
            tooltip = { ToolbarTooltip("Img exports this diagram as a PNG/image attachment.") },
        ) {
            Box(
                Modifier.defaultMinSize(minWidth = 30.dp)
                    .fillMaxHeight()
                    .background(if (exportMode == DiagramExportMode.IMAGE) tc.ac.copy(.2f) else Color.Transparent)
                    .clickable {
                        if (exportMode != DiagramExportMode.IMAGE) {
                            updateSeq3NoteExportMode(noteText, DiagramExportMode.IMAGE)?.let(onUpdateDiagramText)
                        }
                    }
                    .padding(horizontal = 7.dp),
                contentAlignment = Alignment.Center,
            ) {
                AppText(
                    "Img",
                    color = if (exportMode == DiagramExportMode.IMAGE) tc.ac else tc.ts,
                    fontSize = 10.sp,
                    fontWeight = if (exportMode == DiagramExportMode.IMAGE) FontWeight.Medium else FontWeight.Normal,
                )
            }
        }
        Box(Modifier.width(0.5.dp).fillMaxHeight().background(tc.br))
        TooltipArea(
            tooltip = { ToolbarTooltip("Src exports this diagram as Mermaid/PlantUML source.") },
        ) {
            Box(
                Modifier.defaultMinSize(minWidth = 30.dp)
                    .fillMaxHeight()
                    .background(if (exportMode == DiagramExportMode.SOURCE) tc.ac.copy(.2f) else Color.Transparent)
                    .clickable {
                        if (exportMode != DiagramExportMode.SOURCE) {
                            updateSeq3NoteExportMode(noteText, DiagramExportMode.SOURCE)?.let(onUpdateDiagramText)
                        }
                    }
                    .padding(horizontal = 7.dp),
                contentAlignment = Alignment.Center,
            ) {
                AppText(
                    "Src",
                    color = if (exportMode == DiagramExportMode.SOURCE) tc.ac else tc.ts,
                    fontSize = 10.sp,
                    fontWeight = if (exportMode == DiagramExportMode.SOURCE) FontWeight.Medium else FontWeight.Normal,
                )
            }
        }
    }
}

// ── Diagram note view ──────────────────────────────────────────────────

/**
 * The picture half of a diagram note: the rendered sequence diagram, its stats line, and the
 * actions that only make sense for a diagram.
 *
 * Clicking an arrow jumps to the log line that produced it. That is the entire reason this feature
 * renders in-app instead of shelling out to PlantUML, and it works because the note's spec header
 * carries the built model — including each message's entryId, which no diagram dialect's syntax can
 * express (see DiagramSpecCodec's modelToMap).
 */
@Composable
private fun DiagramNoteView(
    noteText: String,
    summary: Seq3NoteSummary,
    tc: ThemeColors,
    settings: AppSettings,
    fieldFocusRequester: FocusRequester?,
    onFieldFocusChanged: (Boolean) -> Unit,
    onUpdateDiagramText: (String) -> Unit,
    onNavigateLine: (Int) -> Unit,
    onImportLinkedDiagram: (String, Seq3Dialect, Boolean) -> Seq3SourceImportResult?,
    expanded: Boolean,
    onToggleExpanded: () -> Unit,
) {
    var pendingEvidenceImport by remember(noteText) { mutableStateOf<Seq3SourceImportResult.Success?>(null) }
    var importFailure by remember(noteText) { mutableStateOf<String?>(null) }
    // Wrapped in a single Column (its own 7dp rhythm, same as BlockCard's body) so this whole
    // function contributes exactly one child to the block card's body — the caller relies on that
    // to keep the outer 7dp block-body spacing from doubling up with spacing in here.
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(7.dp)) {
        BlockTextField(
            value = summary.caption,
            onValueChange = { caption -> updateSeq3NoteCaption(noteText, caption)?.let(onUpdateDiagramText) },
            placeholder = "Add a caption…",
            tc = tc,
            fieldFocusRequester = fieldFocusRequester,
            onFieldFocusChanged = onFieldFocusChanged,
        )
        DiagramSummaryRow(summary = summary, tc = tc, expanded = expanded, onToggleExpanded = onToggleExpanded)
        // Folded cards intentionally do no model decode, rasterization, or bitmap conversion.  An
        // expansion starts the full parse/render pipeline on Dispatchers.Default and publishes its
        // finished display artifact back to Compose. WP4: the resolved theme comes from the parsed
        // document itself (inside rememberExpandedDiagram), not the ambient app theme — see that
        // function's own doc.
        val expandedDiagram = rememberExpandedDiagram(noteText, settings, expanded)
        if (expanded) {
            // WP12: the Notes-panel card is the ordinary place a user looks — before this, the drift
            // warning only ever showed in the Preview dialog (~line 1784, same copy reused verbatim
            // below), so a hand-edited fence (e.g. via the MCP update_note_block tool) could go
            // unnoticed here indefinitely. adoptSeq3NoteSource is the way out: see its own KDoc for why
            // adopting also forces Src export mode rather than leaving IMAGE pointing at a picture that
            // now disagrees with the text.
            if (expandedDiagram != null && !expandedDiagram.parsed.sourceHashMatches) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    AppText(
                        "Diagram source has drifted from its model",
                        color = tc.td,
                        fontSize = 10.sp,
                        modifier = Modifier.weight(1f),
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Box(
                        Modifier.clickable {
                            val parsed = expandedDiagram.parsed
                            val linked = parsed.attachment?.mode == Seq3AttachmentMode.LINKED
                            val imported = if (linked) onImportLinkedDiagram(parsed.source, parsed.dialect, false)
                            else importSeq3Source(parsed.source, parsed.dialect, parsed.document)
                            when (imported) {
                                is Seq3SourceImportResult.Success -> {
                                    if (imported.evidenceLoss) {
                                        pendingEvidenceImport = imported
                                    } else if (!linked) {
                                        encodeSeq3Note(
                                            imported.document,
                                            parsed.dialect,
                                            parsed.caption,
                                            parsed.exportMode,
                                            attachment = parsed.attachment,
                                            sourceOverride = imported.canonicalSource,
                                        ).let(onUpdateDiagramText)
                                    }
                                }
                                is Seq3SourceImportResult.Failure -> {
                                    importFailure = imported.diagnostics.joinToString(" ") { it.message }
                                        .ifBlank { "No matching open diagram session." }
                                }
                                null -> importFailure = "No matching open diagram session."
                            }
                        }.padding(horizontal = 4.dp, vertical = 2.dp),
                    ) { AppText("Import edits", color = tc.ac, fontSize = 10.sp, fontWeight = FontWeight.Medium) }
                    TooltipArea(
                        tooltip = {
                            ToolbarTooltip(
                                "Keeps the hand-edited text exactly as written and switches this " +
                                    "note to Src export. It will not regenerate the picture.",
                            )
                        },
                    ) {
                        Box(
                            Modifier.clickable {
                                adoptSeq3NoteSource(noteText)?.let(onUpdateDiagramText)
                            }.padding(horizontal = 4.dp, vertical = 2.dp),
                        ) {
                            AppText(
                                "Keep source only",
                                color = tc.ac,
                                fontSize = 10.sp,
                                fontWeight = FontWeight.Medium,
                            )
                        }
                    }
                }
                pendingEvidenceImport?.let {
                    AppText("Import removes marked log evidence. Confirm to continue.", color = tc.td, fontSize = 10.sp)
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Box(Modifier.clickable { pendingEvidenceImport = null }.padding(3.dp)) { AppText("Cancel", color = tc.td, fontSize = 10.sp) }
                        Box(Modifier.clickable {
                            val parsed = expandedDiagram.parsed
                            if (parsed.attachment?.mode == Seq3AttachmentMode.LINKED) {
                                when (val result = onImportLinkedDiagram(parsed.source, parsed.dialect, true)) {
                                    is Seq3SourceImportResult.Failure -> importFailure = result.diagnostics.joinToString(" ") { it.message }
                                    else -> pendingEvidenceImport = null
                                }
                            } else {
                                val success = pendingEvidenceImport ?: return@clickable
                                encodeSeq3Note(success.document, parsed.dialect, parsed.caption, parsed.exportMode, attachment = parsed.attachment,
                                    sourceOverride = success.canonicalSource).let(onUpdateDiagramText)
                                pendingEvidenceImport = null
                            }
                        }.padding(3.dp)) { AppText("Import anyway", color = tc.ac, fontSize = 10.sp, fontWeight = FontWeight.Medium) }
                    }
                }
                importFailure?.let { AppText(it, color = tc.td, fontSize = 10.sp) }
            }
            when {
                expandedDiagram == null -> {
                    AppText("Rendering diagram…", color = tc.td, fontSize = 11.sp)
                }
                expandedDiagram.display == null -> {
                    // A diagram note written by an older build, or hand-authored: the fence still exports
                    // wherever Mermaid is supported, but there is no model to draw or click here.
                    AppText("Diagram source only — regenerate to see and click the picture.", color = tc.td, fontSize = 11.sp, maxLines = 2)
                }
                else -> {
                    val display = expandedDiagram.display
                    val rendered = display.rendered
                    val bitmap = display.bitmap
                    // The renderer uses a generously sized editor canvas. A note card must not inherit
                    // that raw canvas size, so fit the visible preview within the available width and a
                    // fixed height while retaining the exact aspect ratio for hit testing.
                    BoxWithConstraints(Modifier.fillMaxWidth()) {
                        val maxPreviewHeight = 300.dp
                        val aspectRatio = rendered.widthPx.toFloat() / rendered.heightPx.coerceAtLeast(1)
                        val previewWidth = minOf(maxWidth, maxPreviewHeight * aspectRatio)
                        val previewHeight = previewWidth / aspectRatio
                        val density = LocalDensity.current.density
                        Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
                            Image(
                                bitmap = bitmap,
                                contentDescription = "Sequence diagram",
                                modifier = Modifier
                                    .width(previewWidth)
                                    .height(previewHeight)
                                    .pointerInput(rendered, previewWidth, previewHeight) {
                                        detectTapGestures { offset ->
                                            // RenderedSeq3 carries no per-arrow hit list (unlike v1/v2's
                                            // RenderedDiagram.hits) — Seq3Layout.kt's own header names
                                            // it as the ONE shared geometry source for both this static
                                            // preview and the live Compose canvas (Seq3Canvas.kt), so
                                            // hit-testing here maps the tap back into that same unit-
                                            // less layout space and finds the nearest row by y, rather
                                            // than duplicating a second hit-region format.
                                            val displayWidthPx = previewWidth.value * density
                                            val displayHeightPx = previewHeight.value * density
                                            val unitX = (offset.x / displayWidthPx * rendered.widthPx) / rendered.scale
                                            val unitY = (offset.y / displayHeightPx * rendered.heightPx) / rendered.scale
                                            val layout = Seq3RenderCache.layout(expandedDiagram.parsed.document)
                                            layout.rows.minByOrNull { kotlin.math.abs(it.y - unitY) }
                                                ?.takeIf { kotlin.math.abs(it.y - unitY) <= DIAGRAM_ROW_HIT_TOLERANCE && unitX >= 0.0 }
                                                ?.occurrenceEntryId
                                                ?.let(onNavigateLine)
                                        }
                                    },
                            )
                        }
                    }
                }
            }
        }
    }
}

/** Collapsed/expanded toggle row for a diagram note ("Note popup redesign" 1b): a recessed row
 *  with an accent caret, the diagram's title and scope/metrics line, and a "show"/"hide" link —
 *  replaces the old plain caret + [Seq3NoteSummary]-only header so the row itself reads as a
 *  clickable invitation rather than a bare disclosure triangle. */
@Composable
private fun DiagramSummaryRow(
    summary: Seq3NoteSummary,
    tc: ThemeColors,
    expanded: Boolean,
    onToggleExpanded: () -> Unit,
) {
    val metrics = summary.messageCount?.let { "$it arrows" }
    Row(
        Modifier.fillMaxWidth()
            .background(tc.bg, FIELD_CORNER)
            .border(1.dp, tc.br, FIELD_CORNER)
            .clip(FIELD_CORNER)
            .clickable(onClick = onToggleExpanded)
            .padding(horizontal = 8.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        AppText(if (expanded) "▾" else "▸", color = tc.ac, fontSize = 9.sp, modifier = Modifier.width(8.dp))
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(1.dp)) {
            AppText(
                summary.title.ifBlank { "Sequence diagram" },
                color = tc.tx,
                fontSize = 12.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            AppText(
                listOfNotNull(summary.scope, metrics).joinToString(" · "),
                color = tc.td,
                fontSize = 10.sp,
                fontFamily = MONO,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        AppText(if (expanded) "hide" else "show", color = tc.ac, fontSize = 10.sp, fontWeight = FontWeight.Medium)
    }
}

// ── LogRef block ───────────────────────────────────────────────────────
@Composable
private fun LogRefBlock(
    block: AnnBlock.LogRef,
    tab: LogTab,
    settings: AppSettings,
    mono: FontFamily,
    tc: ThemeColors,
    isFirst: Boolean, isLast: Boolean,
    focused: Boolean,
    fieldFocusRequester: FocusRequester?,
    onFieldFocusChanged: (Boolean) -> Unit,
    onUpdateCaption: (String) -> Unit,
    onEdit: () -> Unit,
    onRemove: () -> Unit,
    onMoveUp: () -> Unit, onMoveDown: () -> Unit,
    onAddBelow: () -> Unit,
    onNavigate: () -> Unit,
    excerptExpanded: Boolean,
    onToggleExcerpt: () -> Unit,
    dragHandleModifier: Modifier = Modifier,
) {
    val rows = block.resolveRows(tab)
    val localSource = block.sourceTabId == null && rows.all { tab.rmap[it.id] == it }
    val context = rememberAnnotationLogLineContext(tab, settings, localSource)
    val borderColor = rows.firstOrNull()?.level?.defaultColor ?: tc.ac

    BlockCard(
        tc = tc,
        edgeColor = borderColor,
        focused = focused,
        header = {
            BlockControls(
                "log", borderColor, isFirst, isLast, onMoveUp, onMoveDown, onRemove, onAddBelow, onNavigate,
                onNavigateTooltip = "Show in log",
                onEdit = onEdit,
                dragHandleModifier = dragHandleModifier,
            )
        },
    ) {
        if (block.sourceFilename != null) {
            Box(
                Modifier.background(tc.ac.copy(.12f), CORNER_SM)
                    .border(1.dp, tc.ac.copy(.25f), CORNER_SM)
                    .padding(horizontal = 6.dp, vertical = 2.dp),
            ) { AppText("from ${block.sourceFilename}", color = tc.ac, fontSize = 9.sp, fontFamily = MONO) }
        }
        BlockTextField(
            value = block.caption,
            onValueChange = onUpdateCaption,
            placeholder = "Add a note…",
            tc = tc,
            fieldFocusRequester = fieldFocusRequester,
            onFieldFocusChanged = onFieldFocusChanged,
        )
        LogExcerpt(
            rows = rows, tab = tab, settings = settings, context = context, localSource = localSource,
            mono = mono, tc = tc, expanded = excerptExpanded, onToggleExpanded = onToggleExcerpt,
        )
    }
}

/**
 * The referenced log lines below a [LogRefBlock]'s caption ("Note popup redesign" 1b): a recessed
 * box with a clickable "N lines · firstTs → lastTs" summary (reusing [evidenceSummary], same as
 * the full editor dialog) plus the worst level present. With more than 3 rows this collapses to
 * the first 3 as single-line ellipsis rows with a "show N more" link; with 3 or fewer there is
 * nothing to hide, so every row renders exactly as before and the toggle is dropped (the summary
 * row still shows, for consistency, but isn't clickable).
 */
@Composable
private fun LogExcerpt(
    rows: List<LogEntry>,
    tab: LogTab,
    settings: AppSettings,
    context: LogLinePresentationContext?,
    localSource: Boolean,
    mono: FontFamily,
    tc: ThemeColors,
    expanded: Boolean,
    onToggleExpanded: () -> Unit,
) {
    if (rows.isEmpty()) return
    val summary = remember(rows) { evidenceSummary(rows) }
    val canToggle = rows.size > 3
    val worst = summary.levelCounts.firstOrNull()
    Column(
        Modifier.fillMaxWidth()
            .background(tc.bg, FIELD_CORNER)
            .border(1.dp, tc.br, FIELD_CORNER)
            .clip(FIELD_CORNER),
    ) {
        Row(
            Modifier.fillMaxWidth()
                .then(if (canToggle) Modifier.clickable(onClick = onToggleExpanded) else Modifier)
                .padding(horizontal = 8.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            AppText(
                if (!canToggle) "" else if (expanded) "▾" else "▸",
                color = tc.ts, fontSize = 9.sp, modifier = Modifier.width(8.dp),
            )
            // Ellipsizes before the level chip on a narrow panel instead of pushing the chip out.
            AppText(
                summary.rangeLabel(),
                color = tc.ts, fontSize = 10.sp, fontFamily = mono,
                maxLines = 1, overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
            worst?.let { (level, count) -> LogExcerptLevelChip(level, count, mono) }
        }
        Box(Modifier.fillMaxWidth().height(1.dp).background(tc.br))
        Column(
            Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 6.dp),
            verticalArrangement = Arrangement.spacedBy(2.dp),
        ) {
            if (expanded || !canToggle) {
                // Expanded (or nothing to collapse): every row exactly as before — presentLogLine,
                // wrapped, no truncation. Nothing is lost by collapsing first.
                rows.forEach { r ->
                    AppText(
                        presentLogLine(tab, r, settings, context, allowProcessName = localSource),
                        color = tc.ts,
                        fontSize = 9.sp,
                        fontFamily = mono,
                        maxLines = Int.MAX_VALUE,
                        overflow = TextOverflow.Clip,
                    )
                }
                if (canToggle) {
                    AppText(
                        "show less",
                        color = tc.ac, fontSize = 10.sp, fontFamily = mono, fontWeight = FontWeight.Medium,
                        modifier = Modifier.clickable(onClick = onToggleExpanded).padding(top = 2.dp),
                    )
                }
            } else {
                val visibleCount = logExcerptVisibleRowCount(rows.size, expanded = false)
                rows.take(visibleCount).forEach { r ->
                    Row(
                        Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Box(Modifier.width(16.dp), contentAlignment = Alignment.Center) { LevelBadge(r.level) }
                        AppText(
                            "${r.tag}  ${r.msg}",
                            color = tc.tx, fontSize = 10.sp, fontFamily = mono,
                            maxLines = 1, overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.weight(1f),
                        )
                    }
                }
                AppText(
                    "show ${rows.size - visibleCount} more",
                    color = tc.ac, fontSize = 10.sp, fontFamily = mono, fontWeight = FontWeight.Medium,
                    modifier = Modifier.clickable(onClick = onToggleExpanded).padding(top = 2.dp),
                )
            }
        }
    }
}

@Composable
private fun LogExcerptLevelChip(level: LogLevel, count: Int, mono: FontFamily) {
    val color = level.defaultColor
    Box(
        Modifier.background(color.copy(alpha = .13f), CORNER_SM)
            .border(1.dp, color.copy(alpha = .27f), CORNER_SM)
            .padding(horizontal = 5.dp, vertical = 1.dp),
    ) { AppText("$count ${level.key}", color = color, fontSize = 10.sp, fontFamily = mono, fontWeight = FontWeight.SemiBold) }
}

// ── Image block ──────────────────────────────────────────────────────
@Composable
private fun ImageBlockView(
    block: AnnBlock.Image,
    tc: ThemeColors,
    isFirst: Boolean, isLast: Boolean,
    focused: Boolean,
    fieldFocusRequester: FocusRequester?,
    onFieldFocusChanged: (Boolean) -> Unit,
    onUpdateCaption: (String) -> Unit,
    onRemove: () -> Unit,
    onMoveUp: () -> Unit, onMoveDown: () -> Unit,
    onAddBelow: () -> Unit,
    onCopyImage: () -> Unit,
    onNavigateVideoFrame: (() -> Unit)? = null,
    dragHandleModifier: Modifier = Modifier,
) {
    // Keyed on the byte array's own identity (stable across recompositions and across a caption
    // edit — updateBlock's b.copy(caption = ...) reuses the same bytes reference), so decoding
    // only happens once per distinct image, not on every recomposition.
    val bitmap = remember(block.bytes) { decodeImageBlockBitmap(block.bytes) }
    BlockCard(
        tc = tc,
        edgeColor = tc.br,
        focused = focused,
        header = {
            BlockControls(
                "image", tc.ac, isFirst, isLast, onMoveUp, onMoveDown, onRemove, onAddBelow,
                onNavigate = onNavigateVideoFrame,
                onCopyImage = onCopyImage,
                dragHandleModifier = dragHandleModifier,
            )
        },
    ) {
        BlockTextField(
            value = block.caption,
            onValueChange = onUpdateCaption,
            placeholder = "Add a caption…",
            tc = tc,
            fieldFocusRequester = fieldFocusRequester,
            onFieldFocusChanged = onFieldFocusChanged,
        )
        // Only a video frame gets a "From …" line (AnnBlock.Image.displayProvenance) — it
        // disappears entirely for a pasted or dropped image, which is why this is one nullable
        // read rather than an empty-string AppText.
        block.displayProvenance?.let { provenance ->
            val provenanceModifier = if (onNavigateVideoFrame != null) {
                Modifier
                    .pointerHoverIcon(PointerIcon(AwtCursor.getPredefinedCursor(AwtCursor.HAND_CURSOR)))
                    .clickable(onClick = onNavigateVideoFrame)
            } else {
                Modifier
            }
            AppText(
                provenance,
                color = tc.td,
                fontSize = 9.sp,
                fontFamily = MONO,
                modifier = provenanceModifier,
            )
        }
        if (bitmap != null) {
            Image(
                bitmap = bitmap,
                contentDescription = null,
                modifier = Modifier.fillMaxWidth().heightIn(max = IMAGE_BLOCK_THUMBNAIL_DP.dp)
                    .background(Color.Black, CORNER_SM),
                contentScale = ContentScale.Fit,
            )
        } else {
            Box(
                Modifier.fillMaxWidth().height(IMAGE_BLOCK_THUMBNAIL_DP.dp)
                    .background(tc.bg, CORNER_SM).border(1.dp, tc.br, CORNER_SM),
                contentAlignment = Alignment.Center,
            ) { AppText("Couldn't decode image", color = tc.td, fontSize = 11.sp) }
        }
    }
}

// Pure decode of a stored image block's bytes into something Compose can draw. Returns null
// (rendered as a placeholder above) rather than throwing on a corrupt/unsupported blob — an
// image block should never crash the panel it's part of.
private fun decodeImageBlockBitmap(bytes: ByteArray): ImageBitmap? =
    runCatching { org.jetbrains.skia.Image.makeFromEncoded(bytes).toComposeImageBitmap() }.getOrNull()

// ── Block controls (move / delete / add note) ──────────────────────────
@Composable
private fun BlockControls(
    typeLabel: String, typeColor: Color,
    isFirst: Boolean, isLast: Boolean,
    onMoveUp: () -> Unit, onMoveDown: () -> Unit,
    onRemove: () -> Unit,
    onAddBelow: () -> Unit,
    onNavigate: (() -> Unit)? = null,
    onNavigateTooltip: String? = null,
    onCopyImage: (() -> Unit)? = null,
    onEdit: (() -> Unit)? = null,
    afterBadgeContent: (@Composable () -> Unit)? = null,
    dragHandleModifier: Modifier = Modifier,
) {
    val badgeShape = CORNER_SM
    val isNavigationBadge = onNavigate != null
    val badgeModifier = Modifier.height(18.dp)
        .defaultMinSize(minWidth = if (isNavigationBadge) 48.dp else 34.dp)
        .background(typeColor.copy(if (onNavigate != null) .24f else .14f), badgeShape)
        .border(1.dp, typeColor.copy(if (onNavigate != null) .9f else .35f), badgeShape)
        .clip(badgeShape)
        .then(
            if (onNavigate != null) {
                Modifier
                    .pointerHoverIcon(PointerIcon(AwtCursor.getPredefinedCursor(AwtCursor.HAND_CURSOR)))
                    .clickable(onClick = onNavigate)
            } else {
                Modifier
            },
        )
        .padding(horizontal = 6.dp)
    // Two groups in a FlowRow: the drag handle/type chip/afterBadgeContent, then the actions
    // right-aligned. When a narrow panel can't fit both on one line the actions wrap onto a second
    // line instead of being squeezed out (× vanishing) or clipping Img|Src away.
    FlowRow(
        Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Row(
            Modifier.height(20.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            AppText(
                "⠿",
                color = tc().td,
                fontSize = 12.sp,
                modifier = dragHandleModifier.pointerHoverIcon(PointerIcon(AwtCursor.getPredefinedCursor(AwtCursor.MOVE_CURSOR))),
            )
            val badge: @Composable () -> Unit = {
                Box(
                    badgeModifier,
                    contentAlignment = Alignment.Center,
                ) {
                    if (isNavigationBadge) {
                        androidx.compose.material3.Text(
                            "$typeLabel ↗",
                            color = typeColor,
                            fontSize = 9.sp,
                            fontWeight = FontWeight.SemiBold,
                            textDecoration = TextDecoration.Underline,
                            maxLines = 1,
                        )
                    } else {
                        AppText(
                            typeLabel,
                            color = typeColor,
                            fontSize = 9.sp,
                            fontWeight = FontWeight.Medium,
                        )
                    }
                }
            }
            if (onNavigateTooltip != null) {
                TooltipArea(tooltip = { ToolbarTooltip(onNavigateTooltip) }) { badge() }
            } else {
                badge()
            }
            afterBadgeContent?.let {
                it()
            }
        }

        Row(
            Modifier.weight(1f).height(20.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp, Alignment.End),
        ) {
            if (!isFirst) SquareIconButton("↑", fontSize = 12.sp, onClick = onMoveUp)
            if (!isLast)  SquareIconButton("↓", fontSize = 12.sp, onClick = onMoveDown)
            if (onCopyImage != null) LabelIconButton("copy image", fontSize = 10.sp, onClick = onCopyImage)
            // Renamed from the bare "✎" glyph, which at this size read as a paperclip rather than the
            // button that opens the full editor dialog.
            onEdit?.let { LabelIconButton("Edit", fontSize = 10.sp, onClick = it) }
            LabelIconButton("+ Note", fontSize = 10.sp, onClick = onAddBelow)
            SquareIconButton("×", fontSize = 14.sp, onClick = onRemove)
        }
    }
}

@Composable
private fun AnnSection(tc: ThemeColors, content: @Composable ColumnScope.() -> Unit) {
    Column(
        Modifier.fillMaxWidth().border(BorderStroke(1.dp, tc.br.copy(.33f))).padding(horizontal = 12.dp, vertical = 8.dp),
        content = content,
    )
}
