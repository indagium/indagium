@file:OptIn(
    androidx.compose.ui.ExperimentalComposeUiApi::class,
    androidx.compose.foundation.ExperimentalFoundationApi::class,
)

package com.openlog.ui

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.*
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.graphicsLayer
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
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
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
import com.openlog.model.AnnBlock
import com.openlog.model.AppSettings
import com.openlog.model.LogTab
import java.awt.FileDialog
import java.awt.Frame
import java.io.File
import kotlin.math.roundToInt
import java.awt.Cursor as AwtCursor

private const val BLOCK_DRAG_SNAP_BIAS = 0.25f
private const val AUTO_SCROLL_SPEED_FACTOR = 0.6f
private const val STICK_TO_BOTTOM_THRESHOLD_DP = 24f

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
    onToggleMd: () -> Unit,
    onCopy: () -> Unit,
    onSave: () -> Unit,
    onToggleRecentNotes: () -> Unit,
    onOpenNote: (File) -> Unit,
    onUpdatePrefix: (String) -> Unit,
    onUpdateSuffix: (String) -> Unit,
    onUpdateIssueDescription: (String) -> Unit,
    onUpdateBlock: (String, String) -> Unit,
    onRemoveBlock: (String) -> Unit,
    onMoveBlock: (String, Int) -> Unit,
    onReorderBlock: (String, Int) -> Unit,
    onAddNoteAfter: (String?) -> Unit,
    onNavigateLogRef: (AnnBlock.LogRef) -> Unit,
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
    var panelFocused by remember { mutableStateOf(false) }
    var prefixFocused by remember { mutableStateOf(false) }
    var issueDescFocused by remember { mutableStateOf(false) }
    var suffixFocused by remember { mutableStateOf(false) }
    // Session-only, not persisted — only the text itself needs to survive a restart.
    var issueDescExpanded by remember(tab.id) { mutableStateOf(false) }
    var blockFieldFocused by remember { mutableStateOf(false) }
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
            is AnnBlock.Note -> controlsDp + textFieldDp(block.text, 20.7f, 60f) + outerChromeDp
            is AnnBlock.LogRef -> {
                val captionDp = textFieldDp(block.caption, 20.7f, 52f)
                val rowCount = block.sourceEntries?.size ?: block.logIds.size
                val rowsDp = rowCount * 15f + 12f
                val filenameBadgeDp = if (block.sourceFilename != null) 21f else 0f
                controlsDp + filenameBadgeDp + captionDp + 6f + rowsDp + outerChromeDp
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

    fun moveNoteFocus(delta: Int) {
        navIndex = rovingMove(noteTargets.map { it.asRovingItem() }, navIndex, delta)
    }

    fun focusedBlockId(): String? = noteTargets.getOrNull(navIndex)
        ?.takeIf { it.kind == KeyboardTargetKind.NoteBlock }
        ?.id
        ?.removePrefix("block:")

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
                if (block is AnnBlock.LogRef) onNavigateLogRef(block)
                else runCatching { blockFieldRequesters[blockId]?.requestFocus() }
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
        // Header row 1: title + action buttons
        Box(
            Modifier.fillMaxWidth().height(36.dp).background(tc.p2)
                .border(BorderStroke(1.dp, tc.br)).padding(horizontal = 12.dp),
        ) {
            Row(
                Modifier.align(Alignment.Center),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(4.dp, Alignment.CenterHorizontally),
            ) {
                AppButton("Preview", onClick = onToggleMd, enabled = hasAnnotationBlocks, modifier = headerButtonModifier)
                AppButton("Copy", onClick = onCopy, modifier = headerButtonModifier)
                AppButton("Save", onClick = onSave, modifier = headerButtonModifier)
                AppButton("Open Note", onClick = { openNotePicker() }, modifier = headerButtonModifier)
                Box {
                    AppButton(
                        "▾ ${recentNotes.size}",
                        enabled = hasRecentNotes,
                        modifier = headerButtonModifier.widthIn(min = 40.dp),
                        onClick = onToggleRecentNotes,
                    )
                    if (recentNotesMenuOpen && hasRecentNotes) {
                        RecentNotesPopup(
                            recentNotes = recentNotes,
                            onOpenNote = onOpenNote,
                            onDismiss = onToggleRecentNotes,
                            tc = tc,
                        )
                    }
                }
            }
        }
        // Inline preview popup
        if (tab.showAnnMd && hasAnnotationBlocks) {
            MdPreviewDialog(tab = tab, settings = settings, mono = mono, onCopy = onCopy, onDismiss = onToggleMd)
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
        LaunchedEffect(scroll) {
            snapshotFlow { scroll.maxValue <= 0 || scroll.value >= scroll.maxValue - stickToBottomPx }
                .collect { stickToBottom = it }
        }
        LaunchedEffect(totalBlockHeightPx, scroll) {
            if (stickToBottom) scroll.scrollTo(scroll.maxValue)
        }
        LaunchedEffect(highlightedBlockId, tab.id, blockStartOffsets[highlightedBlockId]) {
            val target = highlightedBlockId ?: return@LaunchedEffect
            if (ann.blocks.none { it.id == target }) return@LaunchedEffect
            scroll.scrollTo((blockStartOffsets[target] ?: 0f).roundToInt())
        }
        Box(Modifier.fillMaxSize()) {
            Column(Modifier.fillMaxSize().verticalScroll(scroll).padding(end = 8.dp)) {
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
                        BasicTextField(
                            value = ann.issueDescription,
                            onValueChange = onUpdateIssueDescription,
                            textStyle = TextStyle(color = tc.tx, fontSize = 11.sp, fontFamily = FontFamily.Default, lineHeight = 16.sp),
                            cursorBrush = SolidColor(tc.ac),
                            modifier = Modifier.fillMaxWidth()
                                .background(tc.bg, CORNER_SM)
                                .border(1.dp, tc.br, CORNER_SM)
                                .onFocusChanged { issueDescFocused = it.isFocused }
                                .padding(8.dp).defaultMinSize(minHeight = 60.dp),
                            decorationBox = { inner ->
                                if (ann.issueDescription.isEmpty()) {
                                    AppText("Not included in previews or exports…", color = tc.td, fontSize = 11.sp)
                                }
                                inner()
                            },
                        )
                    }
                }
                Divider()

                // Prefix
                AnnSection(tc) {
                    AppText("Prefix", color = tc.td, fontSize = 10.sp, fontFamily = UI)
                    Spacer(Modifier.height(3.dp))
                    InlineField(
                        ann.prefix,
                        onUpdatePrefix,
                        "Heading, context…",
                        Modifier.fillMaxWidth()
                            .focusRequester(prefixFr)
                            .onFocusChanged { prefixFocused = it.isFocused },
                        fontSize = 12.sp,
                        singleLine = false,
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
                            onFieldFocusChanged = { blockFieldFocused = it },
                            onUpdate = { onUpdateBlock(block.id, it) },
                            onRemove = { onRemoveBlock(block.id) },
                            onMoveUp = { onMoveBlock(block.id, -1) },
                            onMoveDown = { onMoveBlock(block.id, 1) },
                            onAddBelow = { onAddNoteAfter(block.id) },
                            dragHandleModifier = dragHandleModifier,
                        )
                        is AnnBlock.LogRef -> LogRefBlock(
                            block = block, tab = tab, mono = mono, tc = tc,
                            isFirst = isFirst, isLast = isLast,
                            focused = noteTargets.getOrNull(navIndex)?.id == "block:${block.id}" || highlightedBlockId == block.id,
                            fieldFocusRequester = blockFieldRequesters[block.id],
                            onFieldFocusChanged = { blockFieldFocused = it },
                            onUpdateCaption = { onUpdateBlock(block.id, it) },
                            onRemove = { onRemoveBlock(block.id) },
                            onMoveUp = { onMoveBlock(block.id, -1) },
                            onMoveDown = { onMoveBlock(block.id, 1) },
                            onAddBelow = { onAddNoteAfter(block.id) },
                            onNavigate = { onNavigateLogRef(block) },
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
                        InlineField(
                            ann.suffix,
                            onUpdateSuffix,
                            "Add follow-up notes…",
                            Modifier.fillMaxWidth()
                                .focusRequester(suffixFr)
                                .onFocusChanged { suffixFocused = it.isFocused },
                            fontSize = 12.sp,
                            singleLine = false,
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
    }
}

@Composable
private fun RecentNotesPopup(
    recentNotes: List<String>,
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
                                Column(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 6.dp)) {
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
private fun MdPreviewDialog(tab: LogTab, settings: AppSettings, mono: FontFamily, onCopy: () -> Unit, onDismiss: () -> Unit) {
    val tc = tc()
    var copied by remember { mutableStateOf(false) }
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
                CloseButton(onClick = onDismiss)
            }
            Box(Modifier.fillMaxWidth().height(1.dp).background(tc.br))
            val scroll = rememberScrollState()
            Box(Modifier.fillMaxSize()) {
                Box(Modifier.fillMaxSize().verticalScroll(scroll).padding(16.dp)) {
                    RenderedMarkdownPreview(tab, settings, mono, tc)
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
            AppText(
                tab.annotations.prefix,
                color = tc.tx,
                fontSize = 16.sp,
                fontWeight = FontWeight.SemiBold,
                maxLines = Int.MAX_VALUE,
                overflow = TextOverflow.Clip,
            )
        }
        tab.annotations.blocks.forEach { block ->
            when (block) {
                is AnnBlock.Note -> if (block.text.isNotBlank()) {
                    AppText(
                        (if (settings.numberAnnotationBlocks) "${blockNumber++}. " else "") + block.text,
                        color = tc.tx,
                        fontSize = 13.sp,
                        maxLines = Int.MAX_VALUE,
                        overflow = TextOverflow.Clip,
                    )
                }

                is AnnBlock.LogRef -> {
                    val rows = block.sourceEntries ?: block.logIds.mapNotNull { tab.rmap[it] }
                    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        if (block.caption.isNotBlank() || settings.numberAnnotationBlocks) {
                            AppText(
                                (if (settings.numberAnnotationBlocks) "${blockNumber++}. " else "") + block.caption,
                                color = tc.tx,
                                fontSize = 13.sp,
                                fontWeight = FontWeight.SemiBold,
                                maxLines = Int.MAX_VALUE,
                                overflow = TextOverflow.Clip,
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
                                    "${row.ts}  ${row.level.key}/${row.tag}  ${row.msg}",
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
            }
        }
        if (tab.annotations.suffix.isNotBlank()) {
            Box(Modifier.fillMaxWidth().height(1.dp).background(tc.br))
            AppText(tab.annotations.suffix, color = tc.tx, fontSize = 13.sp, maxLines = Int.MAX_VALUE, overflow = TextOverflow.Clip)
        }
    }
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
) {
    Column(
        Modifier.fillMaxWidth()
            .border(BorderStroke(2.dp, if (focused) tc.ac else tc.ac.copy(.35f)))
            .padding(horizontal = 12.dp, vertical = 8.dp),
    ) {
        BlockControls("text", tc.ac, isFirst, isLast, onMoveUp, onMoveDown, onRemove, onAddBelow, dragHandleModifier = dragHandleModifier)
        Spacer(Modifier.height(5.dp))
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

// ── LogRef block ───────────────────────────────────────────────────────
@Composable
private fun LogRefBlock(
    block: AnnBlock.LogRef,
    tab: LogTab,
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
    val rows = block.sourceEntries ?: block.logIds.mapNotNull { tab.rmap[it] }
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
                Row(
                    Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    AppText(r.ts, color = tc.td, fontSize = 9.sp, fontFamily = mono, modifier = Modifier.width(75.dp))
                    LevelBadge(r.level)
                    AppText(r.tag, color = tc.ts, fontSize = 9.sp, fontFamily = mono,
                        modifier = Modifier.width(80.dp), overflow = TextOverflow.Ellipsis)
                    AppText(r.msg, color = tc.ts, fontSize = 9.sp, fontFamily = mono,
                        modifier = Modifier.weight(1f), overflow = TextOverflow.Ellipsis)
                }
            }
        }
    }
}

// ── Block controls (move / delete / add note) ──────────────────────────
@Composable
private fun BlockControls(
    typeLabel: String, typeColor: Color,
    isFirst: Boolean, isLast: Boolean,
    onMoveUp: () -> Unit, onMoveDown: () -> Unit,
    onRemove: () -> Unit,
    onAddBelow: () -> Unit,
    onNavigate: (() -> Unit)? = null,
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

        Spacer(Modifier.weight(1f))

        if (!isFirst) SquareIconButton("↑", fontSize = 12.sp, onClick = onMoveUp)
        if (!isLast)  SquareIconButton("↓", fontSize = 12.sp, onClick = onMoveDown)
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
