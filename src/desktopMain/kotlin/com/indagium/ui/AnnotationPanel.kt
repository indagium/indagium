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
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.SolidColor
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
import androidx.compose.ui.input.pointer.PointerIcon
import androidx.compose.ui.input.pointer.pointerHoverIcon
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupProperties
import androidx.compose.ui.zIndex
import com.indagium.diagram.DiagramExportMode
import com.indagium.diagram.DiagramTheme
import com.indagium.diagram.ParsedDiagram
import com.indagium.diagram.parseDiagramNote
import com.indagium.diagram.updateDiagramNoteCaption
import com.indagium.diagram.updateDiagramNoteExportMode
import com.indagium.model.AnnBlock
import com.indagium.model.AppSettings
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
 * The Notes column can contain many large diagram attachments.  A full [parseDiagramNote] decodes
 * every carried message and participant, which is unnecessary just to draw a folded card header.
 * This deliberately shallow extraction reads only the small top-level metadata it displays; the
 * full, trusted parser remains the authority and runs only when a card is expanded or an action
 * needs the model.
 */
internal data class DiagramNoteSummary(
    val title: String,
    val caption: String,
    val exportMode: DiagramExportMode,
    val scope: String,
    val messageCount: Int?,
    val revision: Long?,
    /** Maximum number of input characters inspected to produce this summary. */
    val inspectedChars: Int,
)

internal object DiagramNoteSummaryCache {
    private const val MAX_ENTRIES = 48
    internal const val MAX_INSPECTED_CHARS = 64 * 1024
    private const val MAX_LEADING_WHITESPACE = 256
    private const val DIAGRAM_MARKER = "<!-- indagium:diagram "
    private const val HEADER_TERMINATOR = " -->"
    private const val UNKNOWN_RANGE_ENDPOINT = "?"
    private val supportedVersions = setOf("v1", "v2", "v3")
    private val payloadKeys = listOf("\"snapshot\"", "\"model\"")
    private val unicodeEscapeRegex = Regex("\\\\u([0-9a-fA-F]{4})")
    private val rangeRegex = Regex("\\\"range\\\"\\s*:\\s*\\{([^}]*)}")

    private data class Cached(val summary: DiagramNoteSummary?)

    private data class BoundedMetadata(val text: String, val inspectedChars: Int)

    // Identity keys are intentional. String.hashCode() scans the entire string the first time it
    // is used, which would undo the bounded parser for a multi-megabyte note before parsing even
    // began. Annotation text is immutable and Compose retains the same String instance between
    // edits, so identity provides the cache semantics this UI path actually needs in O(1).
    private val cache = java.util.IdentityHashMap<String, Cached>()
    private val insertionOrder = java.util.ArrayDeque<String>()

    fun summary(text: String): DiagramNoteSummary? {
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

    private fun parseSummary(text: String): DiagramNoteSummary? {
        val metadata = boundedMetadata(text) ?: return null
        val header = metadata.text
        return DiagramNoteSummary(
            title = jsonString(header, "title").orEmpty(),
            caption = jsonString(header, "caption").orEmpty(),
            exportMode = exportModeFrom(header),
            scope = scopeFrom(header),
            // v1-v3 do not carry a compact count. Counting model objects would scan unbounded
            // data and v1/v2 snapshots can duplicate them, so folded cards omit it honestly.
            messageCount = null,
            revision = jsonNumber(header, "revision"),
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
        if (version !in supportedVersions) return null
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
        // This small unescaper covers JSON scalar escapes without decoding the carried model.
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
        return when (jsonString(range, "kind")) {
            "ids" -> "Lines ${jsonNumber(range, "from") ?: UNKNOWN_RANGE_ENDPOINT}–" +
                "${jsonNumber(range, "to") ?: UNKNOWN_RANGE_ENDPOINT}"
            "time" -> "${jsonString(range, "fromTs").orEmpty().ifBlank { "start" }}–" +
                jsonString(range, "toTs").orEmpty().ifBlank { "end" }
            "seqGroup" -> "Sequence group ${jsonString(range, "gid").orEmpty()}"
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
internal object DiagramNoteParseCache {
    private const val MAX_ENTRIES = 48

    private data class Cached(val parsed: ParsedDiagram?)

    private val cache = object : LinkedHashMap<String, Cached>(MAX_ENTRIES, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Cached>?): Boolean = size > MAX_ENTRIES
    }
    private val parseCount = java.util.concurrent.atomic.AtomicInteger()

    fun parse(text: String): ParsedDiagram? {
        synchronized(cache) { cache[text] }?.let { return it.parsed }
        parseCount.incrementAndGet()
        val parsed = parseDiagramNote(text)
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
    val start = text.indexOf("<!-- indagium:diagram ")
    if (start < 0) return text
    val end = text.indexOf(" -->", start)
    return if (end < 0) text else text.substring(end + 4).trimStart('\r', '\n')
}

private data class ExpandedDiagram(val parsed: ParsedDiagram, val display: DiagramDisplay?)

@Composable
private fun rememberExpandedDiagram(
    noteText: String,
    theme: DiagramTheme,
    expanded: Boolean,
    allowSnapshotPreview: Boolean = false,
): ExpandedDiagram? {
    val result by produceState<ExpandedDiagram?>(initialValue = null, noteText, theme, expanded, allowSnapshotPreview) {
        value = if (!expanded) null else withContext(Dispatchers.Default) {
            DiagramNoteParseCache.parse(noteText)?.let { parsed ->
                val model = if (allowSnapshotPreview) parsed.snapshotPreviewModel else parsed.model
                ExpandedDiagram(parsed, model?.let { DiagramRenderCache.display(it, theme) })
            }
        }
    }
    return result
}

private data class DiagramScrollAnchor(
    val blockId: String,
    val viewportTopPx: Float,
    val blockHeightPx: Float,
)

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
    // The panel deliberately receives library data/actions rather than reaching into AppState:
    // it stays a UI leaf and the caller owns source-identity scoping and workspace transitions.
    diagramLibraryItems: List<DiagramLibraryItem> = emptyList(),
    onCreateDiagram: () -> Unit = {},
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

        fun textFieldDp(text: String, lineHeightDp: Float, minHeightDp: Float): Float {
            val lines = if (text.isEmpty()) 1f else kotlin.math.ceil(text.length / charsPerLine).coerceAtLeast(1f)
            return maxOf(minHeightDp, lines * lineHeightDp + 16f)
        }
        val controlsDp = 23f
        val outerChromeDp = 20f
        val dp = when (block) {
            is AnnBlock.Note -> {
                // Folded diagram cards do not decode or draw their carried model.  The shallow
                // summary gives us a stable header-height estimate without making a long Notes
                // document pay an O(messages) parse during layout.
                val summary = DiagramNoteSummaryCache.summary(block.text)
                val collapsedDiagramDp = if (summary != null) 38f else 0f
                val caption = summary?.caption.orEmpty()
                controlsDp + collapsedDiagramDp + textFieldDp(caption, 20.7f, 40f) + outerChromeDp
            }
            is AnnBlock.LogRef -> {
                val captionDp = textFieldDp(block.caption, 20.7f, 52f)
                val rowCount = block.resolveRows(tab).size
                val rowsDp = rowCount * 15f + 12f
                val filenameBadgeDp = if (block.sourceFilename != null) 21f else 0f
                controlsDp + filenameBadgeDp + captionDp + 6f + rowsDp + outerChromeDp
            }
            is AnnBlock.Image -> {
                // Label plus its trailing Spacer, and only when the block actually renders one —
                // a pasted/dropped image draws neither (see displayProvenance at the render site).
                val provenanceDp = if (block.displayProvenance != null) 16f + 5f else 0f
                val captionDp = textFieldDp(block.caption, 20.7f, 40f)
                controlsDp + IMAGE_BLOCK_THUMBNAIL_DP + 5f + provenanceDp + captionDp + outerChromeDp
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
        var diagramScrollAnchor by remember(tab.id) { mutableStateOf<DiagramScrollAnchor?>(null) }
        LaunchedEffect(scroll, diagramScrollAnchor) {
            snapshotFlow { scroll.maxValue <= 0 || scroll.value >= scroll.maxValue - stickToBottomPx }
                .collect { if (diagramScrollAnchor == null) stickToBottom = it }
        }
        LaunchedEffect(totalBlockHeightPx, scroll, diagramScrollAnchor) {
            val anchor = diagramScrollAnchor
            if (anchor != null) {
                val currentHeight = blockHeightOf(anchor.blockId)
                if (kotlin.math.abs(currentHeight - anchor.blockHeightPx) > 0.5f) {
                    withFrameNanos { }
                    val blockTop = blockStartOffsets[anchor.blockId] ?: 0f
                    val targetScroll = (blockTop - anchor.viewportTopPx).roundToInt()
                    scroll.scrollTo(targetScroll.coerceIn(0, scroll.maxValue))
                    diagramScrollAnchor = null
                    stickToBottom = false
                }
            } else if (stickToBottom) {
                scroll.scrollTo(scroll.maxValue)
            }
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
                    AppText("Prefix", color = tc.td, fontSize = 10.sp, fontFamily = UI)
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
                    Box(
                        Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp)
                            .border(1.dp, tc.br, CORNER_MD)
                            .clickable { onAddNoteAfter(null) }.padding(vertical = 5.dp),
                        contentAlignment = Alignment.Center,
                    ) { AppText("+ Add text block", color = tc.td, fontSize = 11.sp) }
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
                            block = block, tc = tc, isFirst = isFirst, isLast = isLast,
                            focused = noteTargets.getOrNull(navIndex)?.id == "block:${block.id}" || highlightedBlockId == block.id,
                            fieldFocusRequester = blockFieldRequesters[block.id],
                            onFieldFocusChanged = { focused ->
                                blockFieldFocused = focused
                                if (focused) activeBlockFieldId = block.id
                                else if (activeBlockFieldId == block.id) activeBlockFieldId = null
                            },
                            onUpdate = { onUpdateBlock(block.id, it) },
                            onRemove = { onRemoveBlock(block.id) },
                            onMoveUp = { onMoveBlock(block.id, -1) },
                            onMoveDown = { onMoveBlock(block.id, 1) },
                            onAddBelow = { onAddNoteAfter(block.id) },
                            dragHandleModifier = dragHandleModifier,
                            onBeforeToggleDiagram = {
                                val blockTop = blockStartOffsets[block.id] ?: 0f
                                diagramScrollAnchor = DiagramScrollAnchor(
                                    blockId = block.id,
                                    viewportTopPx = blockTop - scroll.value,
                                    blockHeightPx = blockHeightOf(block.id),
                                )
                                stickToBottom = false
                            },
                            onEditDiagram = { onEditDiagram(block.id) },
                            onNavigateDiagramLine = onNavigateDiagramLine,
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
                            onRemove = { onRemoveBlock(block.id) },
                            onMoveUp = { onMoveBlock(block.id, -1) },
                            onMoveDown = { onMoveBlock(block.id, 1) },
                            onAddBelow = { onAddNoteAfter(block.id) },
                            onNavigate = { onNavigateLogRef(block) },
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
                Box(Modifier.fillMaxWidth().heightIn(min = (totalBlockHeightPx / blockDensity).dp)) {
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
                    Box(
                        Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp)
                            .border(1.dp, tc.br, CORNER_MD)
                            .clickable { onAddNoteAfter(ann.blocks.last().id) }.padding(vertical = 5.dp),
                        contentAlignment = Alignment.Center,
                    ) { AppText("+ Add text block", color = tc.td, fontSize = 11.sp) }

                    // Suffix
                    AnnSection(tc) {
                        AppText("Next steps", color = tc.td, fontSize = 10.sp, fontFamily = UI)
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
    onOpen: (String) -> Unit,
    onRequestDelete: (String) -> Unit,
) {
    val tc = tc()
    SectionHeader(
        "Diagram library",
        trailing = {
            AppText(items.size.toString(), color = tc.td, fontSize = 10.sp, fontFamily = MONO)
            Spacer(Modifier.width(8.dp))
            LabelIconButton("+ diagram", fontSize = 10.sp, onClick = onCreate)
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
                                    item.parsed?.let { parsed -> diagramLibraryRangeSummary(parsed.spec) } ?: "Unavailable diagram data",
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

private fun diagramLibraryRangeSummary(spec: com.indagium.diagram.SeqDiagramSpec): String = when (val range = spec.range) {
    is com.indagium.diagram.DiagramRange.VisibleView -> "Current filtered view"
    is com.indagium.diagram.DiagramRange.Ids -> "Lines ${minOf(range.from, range.to)}–${maxOf(range.from, range.to)}"
    is com.indagium.diagram.DiagramRange.Time -> "${range.fromTs.ifBlank { "start" }}–${range.toTs.ifBlank { "end" }}"
    is com.indagium.diagram.DiagramRange.SeqGroupRef -> "Sequence group ${range.gid}"
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
                    val summary = remember(block.text) { DiagramNoteSummaryCache.summary(block.text) }
                    // `model` is deliberately null for a v2 attachment whose visible source no
                    // longer hashes to its carried model. The Markdown preview is read-only, so
                    // it may present that retained model as an explicitly labelled snapshot;
                    // otherwise v2 attachments vanish between the surrounding log blocks.
                    // Editor and hit-test paths continue to use ParsedDiagram.model only.
                    val expandedDiagram = if (summary != null) {
                        rememberExpandedDiagram(block.text, tc.toDiagramTheme(), expanded = true, allowSnapshotPreview = true)
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
                                        parsed.spec.title.ifBlank { "Sequence diagram" },
                                        color = tc.ts,
                                        fontSize = 12.sp,
                                        fontWeight = FontWeight.SemiBold,
                                    )
                                    // A retained model whose source has changed is safe to show,
                                    // but must remain distinguishable from a current rendering.
                                    if (parsed.model == null) {
                                        AppText("Saved snapshot — source changed", color = tc.td, fontSize = 10.sp)
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
private fun AnnotationMarkdownText(text: String, tc: ThemeColors, numberPrefix: String? = null) {
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
private fun annotationMarkdownColors(colors: ThemeColors) = markdownColor(
    text = colors.tx,
    codeBackground = colors.bg,
    inlineCodeBackground = colors.bg,
    dividerColor = colors.br,
    tableBackground = colors.p2,
)

@Composable
private fun annotationMarkdownTypography(colors: ThemeColors): MarkdownTypography {
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

// ── Note block ─────────────────────────────────────────────────────────
@Composable
private fun NoteBlock(
    block: AnnBlock.Note,
    tc: ThemeColors,
    isFirst: Boolean, isLast: Boolean,
    focused: Boolean,
    fieldFocusRequester: FocusRequester?,
    onFieldFocusChanged: (Boolean) -> Unit,
    onUpdate: (String) -> Unit,
    onRemove: () -> Unit,
    onMoveUp: () -> Unit, onMoveDown: () -> Unit,
    onAddBelow: () -> Unit,
    dragHandleModifier: Modifier = Modifier,
    onBeforeToggleDiagram: () -> Unit = {},
    onEditDiagram: () -> Unit = {},
    onNavigateDiagramLine: (Int) -> Unit = {},
    onCopyDiagramImage: (png: ByteArray, fallbackText: String) -> Unit = { _, _ -> },
) {
    // Diagram notes are cards, not an exposed model header plus dialect source. Opening the
    // workspace is the only normal editing route, which keeps the rendered model and saved source
    // in sync. A malformed/non-diagram note remains the ordinary editable text control below.
    val diagram = remember(block.text) { DiagramNoteSummaryCache.summary(block.text) }
    var diagramExpanded by remember(block.id, block.text) { mutableStateOf(false) }
    Column(
        Modifier.fillMaxWidth()
            .border(BorderStroke(2.dp, if (focused) tc.ac else tc.ac.copy(.35f)))
            .padding(horizontal = 12.dp, vertical = 8.dp),
    ) {
        BlockControls(
            if (diagram != null) "diagram" else "text",
            tc.ac, isFirst, isLast, onMoveUp, onMoveDown, onRemove, onAddBelow, dragHandleModifier = dragHandleModifier,
            onNavigate = if (diagram != null) onEditDiagram else null,
            onNavigateTooltip = if (diagram != null) "Open diagram workspace" else null,
            onCopyImage = diagram?.let { summary ->
                {
                    // Copy is an explicit action, so it is the right point to pay for parsing
                    // and PNG encoding. A folded card itself stays model-free.
                    DiagramNoteParseCache.parse(block.text)?.model?.let { model ->
                        onCopyDiagramImage(
                            DiagramRenderCache.pngBytes(model, tc.toDiagramTheme()),
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
        Spacer(Modifier.height(5.dp))
        if (diagram != null) {
            DiagramNoteView(
                noteText = block.text,
                summary = diagram,
                tc = tc,
                fieldFocusRequester = fieldFocusRequester,
                onFieldFocusChanged = onFieldFocusChanged,
                onUpdateDiagramText = onUpdate,
                onNavigateLine = onNavigateDiagramLine,
                expanded = diagramExpanded,
                onToggleExpanded = {
                    onBeforeToggleDiagram()
                    diagramExpanded = !diagramExpanded
                },
            )
        } else {
            BasicTextField(
                value = block.text,
                onValueChange = onUpdate,
                textStyle = TextStyle(color = tc.tx, fontSize = 12.sp, fontFamily = FontFamily.Default, lineHeight = 18.sp),
                cursorBrush = SolidColor(tc.ac),
                modifier = Modifier.fillMaxWidth()
                    .background(tc.bg, CORNER_SM)
                    .border(1.dp, tc.br, CORNER_SM)
                    .then(if (fieldFocusRequester != null) Modifier.focusRequester(fieldFocusRequester) else Modifier)
                    .onFocusChanged { onFieldFocusChanged(it.isFocused) }
                    .padding(8.dp).defaultMinSize(minHeight = 60.dp),
                decorationBox = { inner ->
                    if (block.text.isEmpty()) AppText("Write your note here…", color = tc.td, fontSize = 12.sp)
                    inner()
                },
            )
        }
    }
}

@Composable
private fun DiagramHeaderSummary(summary: DiagramNoteSummary) {
    val metrics = summary.messageCount?.let { "$it arrows" }
    val revision = summary.revision?.let { "rev $it" }
    Column(Modifier.widthIn(max = 180.dp), verticalArrangement = Arrangement.spacedBy(1.dp)) {
        AppText(
            summary.title.ifBlank { "Sequence diagram" },
            color = tc().tx,
            fontSize = 11.sp,
            fontWeight = FontWeight.SemiBold,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        AppText(
            listOfNotNull(summary.scope, metrics, revision).joinToString(" · "),
            color = tc().td,
            fontSize = 9.sp,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
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
                            updateDiagramNoteExportMode(noteText, DiagramExportMode.IMAGE)?.let(onUpdateDiagramText)
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
                            updateDiagramNoteExportMode(noteText, DiagramExportMode.SOURCE)?.let(onUpdateDiagramText)
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
    summary: DiagramNoteSummary,
    tc: ThemeColors,
    fieldFocusRequester: FocusRequester?,
    onFieldFocusChanged: (Boolean) -> Unit,
    onUpdateDiagramText: (String) -> Unit,
    onNavigateLine: (Int) -> Unit,
    expanded: Boolean,
    onToggleExpanded: () -> Unit,
) {
    BasicTextField(
        value = summary.caption,
        onValueChange = { caption ->
            updateDiagramNoteCaption(noteText, caption)?.let(onUpdateDiagramText)
        },
        textStyle = TextStyle(color = tc.tx, fontSize = 12.sp, fontFamily = FontFamily.Default, lineHeight = 18.sp),
        cursorBrush = SolidColor(tc.ac),
        modifier = Modifier.fillMaxWidth()
            .background(tc.bg, CORNER_SM)
            .border(1.dp, tc.br, CORNER_SM)
            .then(if (fieldFocusRequester != null) Modifier.focusRequester(fieldFocusRequester) else Modifier)
            .onFocusChanged { onFieldFocusChanged(it.isFocused) }
            .padding(8.dp).defaultMinSize(minHeight = 40.dp),
        decorationBox = { inner ->
            if (summary.caption.isEmpty()) AppText("Add a caption…", color = tc.td, fontSize = 12.sp)
            inner()
        },
    )
    Spacer(Modifier.height(6.dp))
    Row(
        modifier = Modifier.fillMaxWidth()
            .clickable(onClick = onToggleExpanded)
            .padding(horizontal = 4.dp, vertical = 3.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Box(
            Modifier.size(18.dp).background(tc.br.copy(.5f), CORNER_SM),
            contentAlignment = Alignment.Center,
        ) { AppText(if (expanded) "▾" else "▸", color = tc.ts, fontSize = 14.sp) }
        DiagramHeaderSummary(summary)
    }
    // Folded cards intentionally do no model decode, rasterization, or bitmap conversion.  An
    // expansion starts the full parse/render pipeline on Dispatchers.Default and publishes its
    // finished display artifact back to Compose.
    val theme = tc.toDiagramTheme()
    val expandedDiagram = rememberExpandedDiagram(noteText, theme, expanded)
    if (expanded) {
        Spacer(Modifier.height(6.dp))
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
                                        val displayWidthPx = previewWidth.value * density
                                        val displayHeightPx = previewHeight.value * density
                                        val ix = (offset.x / displayWidthPx * rendered.widthPx).toInt()
                                        val iy = (offset.y / displayHeightPx * rendered.heightPx).toInt()
                                        rendered.hits.firstOrNull { h ->
                                            ix >= h.x && ix <= h.x + h.width && iy >= h.y && iy <= h.y + h.height
                                        }?.let { if (it.entryId > 0) onNavigateLine(it.entryId) }
                                    }
                                },
                        )
                    }
                }
            }
        }
        Spacer(Modifier.height(6.dp))
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
    onRemove: () -> Unit,
    onMoveUp: () -> Unit, onMoveDown: () -> Unit,
    onAddBelow: () -> Unit,
    onNavigate: () -> Unit,
    dragHandleModifier: Modifier = Modifier,
) {
    val rows = block.resolveRows(tab)
    val localSource = block.sourceTabId == null && rows.all { tab.rmap[it.id] == it }
    val context = rememberAnnotationLogLineContext(tab, settings, localSource)
    val borderColor = rows.firstOrNull()?.level?.defaultColor ?: tc.ac

    Column(
        Modifier.fillMaxWidth()
            .border(BorderStroke(2.dp, if (focused) tc.ac else borderColor))
            .padding(horizontal = 12.dp, vertical = 8.dp),
    ) {
        BlockControls(
            "log", borderColor, isFirst, isLast, onMoveUp, onMoveDown, onRemove, onAddBelow, onNavigate,
            dragHandleModifier = dragHandleModifier,
        )
        if (block.sourceFilename != null) {
            Spacer(Modifier.height(3.dp))
            Box(
                Modifier.background(tc.ac.copy(.12f), CORNER_SM)
                    .border(1.dp, tc.ac.copy(.25f), CORNER_SM)
                    .padding(horizontal = 6.dp, vertical = 2.dp),
            ) { AppText("from ${block.sourceFilename}", color = tc.ac, fontSize = 9.sp, fontFamily = MONO) }
        }
        Spacer(Modifier.height(5.dp))
        BasicTextField(
            value = block.caption,
            onValueChange = onUpdateCaption,
            textStyle = TextStyle(color = tc.tx, fontSize = 12.sp, fontFamily = FontFamily.Default, lineHeight = 18.sp),
            cursorBrush = SolidColor(tc.ac),
            modifier = Modifier.fillMaxWidth()
                .background(tc.bg, CORNER_SM)
                .border(1.dp, tc.br, CORNER_SM)
                .then(if (fieldFocusRequester != null) Modifier.focusRequester(fieldFocusRequester) else Modifier)
                .onFocusChanged { onFieldFocusChanged(it.isFocused) }
                .padding(8.dp).defaultMinSize(minHeight = 52.dp),
            decorationBox = { inner ->
                if (block.caption.isEmpty()) AppText("Add a note or analysis…", color = tc.td, fontSize = 12.sp)
                inner()
            },
        )
        Spacer(Modifier.height(6.dp))

        // Referenced log lines shown BELOW the text
        Column(
            Modifier.fillMaxWidth()
                .background(tc.bg.copy(.7f), CORNER_SM)
                .border(1.dp, tc.br.copy(.6f), CORNER_SM)
                .padding(6.dp),
            verticalArrangement = Arrangement.spacedBy(2.dp),
        ) {
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
        }
    }
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
    Column(
        Modifier.fillMaxWidth()
            .border(BorderStroke(2.dp, if (focused) tc.ac else tc.ac.copy(.35f)))
            .padding(horizontal = 12.dp, vertical = 8.dp),
    ) {
        BlockControls(
            "image", tc.ac, isFirst, isLast, onMoveUp, onMoveDown, onRemove, onAddBelow,
            onNavigate = onNavigateVideoFrame,
            onCopyImage = onCopyImage,
            dragHandleModifier = dragHandleModifier,
        )
        Spacer(Modifier.height(5.dp))
        BasicTextField(
            value = block.caption,
            onValueChange = onUpdateCaption,
            textStyle = TextStyle(color = tc.tx, fontSize = 12.sp, fontFamily = FontFamily.Default, lineHeight = 18.sp),
            cursorBrush = SolidColor(tc.ac),
            modifier = Modifier.fillMaxWidth()
                .background(tc.bg, CORNER_SM)
                .border(1.dp, tc.br, CORNER_SM)
                .then(if (fieldFocusRequester != null) Modifier.focusRequester(fieldFocusRequester) else Modifier)
                .onFocusChanged { onFieldFocusChanged(it.isFocused) }
                .padding(8.dp).defaultMinSize(minHeight = 40.dp),
            decorationBox = { inner ->
                if (block.caption.isEmpty()) AppText("Add a caption…", color = tc.td, fontSize = 12.sp)
                inner()
            },
        )
        Spacer(Modifier.height(5.dp))
        // Only a video frame gets a "From …" line (AnnBlock.Image.displayProvenance) — the label
        // and its surrounding spacing disappear entirely for a pasted or dropped image, which is
        // why this is one nullable read rather than an empty-string AppText.
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
            Spacer(Modifier.height(5.dp))
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
    Row(
        Modifier.fillMaxWidth(),
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
        Spacer(Modifier.weight(1f))

        if (!isFirst) SquareIconButton("↑", fontSize = 12.sp, onClick = onMoveUp)
        if (!isLast)  SquareIconButton("↓", fontSize = 12.sp, onClick = onMoveDown)
        if (onCopyImage != null) LabelIconButton("copy image", fontSize = 10.sp, onClick = onCopyImage)
        LabelIconButton("+ note", fontSize = 10.sp, onClick = onAddBelow)
        SquareIconButton("×", fontSize = 14.sp, onClick = onRemove)
    }
}

@Composable
private fun AnnSection(tc: ThemeColors, content: @Composable ColumnScope.() -> Unit) {
    Column(
        Modifier.fillMaxWidth().border(BorderStroke(1.dp, tc.br.copy(.33f))).padding(horizontal = 12.dp, vertical = 8.dp),
        content = content,
    )
}
