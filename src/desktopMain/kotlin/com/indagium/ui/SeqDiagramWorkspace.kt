@file:OptIn(androidx.compose.ui.ExperimentalComposeUiApi::class, androidx.compose.foundation.ExperimentalFoundationApi::class)

package com.indagium.ui

import androidx.compose.foundation.HorizontalScrollbar
import androidx.compose.foundation.TooltipArea
import androidx.compose.foundation.VerticalScrollbar
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.rememberScrollbarAdapter
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Tune
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.isCtrlPressed
import androidx.compose.ui.input.key.isMetaPressed
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.pointer.*
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupProperties
import com.indagium.diagram.DiagramRange
import com.indagium.diagram.SeqDiagram
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.math.exp
import kotlin.math.roundToInt

private data class CanvasZoomAnchor(val content: Offset, val pointer: Offset)

/**
 * Dedicated sequence-diagram editor surface.  It is intentionally not a Dialog: the log tab bar
 * remains available, diagrams can coexist with logs, and closing a log does not close its cached
 * diagram.
 *
 * Two columns: controls on the left, a live rendered preview on the right. The preview is what
 * makes participant selection tractable — tag curation is the whole difficulty of this feature
 * (logcat tags are not architectural components), and it can't be done blind.
 */
@Composable
fun SeqDiagramWorkspace(state: AppState, workspaceId: String) {
    if (state.seqDiagrams.activeWorkspaceId != workspaceId) state.seqDiagrams.activateWorkspace(workspaceId)
    val session = state.seqDiagrams.activeSession ?: return
    val req = session.request
    val offline = session.offlineRequest
    val tab = req?.let { request -> state.tab(request.tabId) }
    val spec = req?.spec ?: offline?.spec ?: session.spec
    val readOnly = req == null || tab == null || state.seqDiagrams.libraryOpenReadOnly
    val tc = tc()

    fun requestClose() {
        state.seqDiagrams.requestCloseWorkspace(workspaceId)
    }

    Column(
        Modifier.fillMaxSize().background(tc.p).padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                AppText(
                    if (readOnly) "Diagram workspace · cached" else if (req.editingBlockId != null) "Diagram workspace" else "New diagram workspace",
                    fontSize = 15.sp, fontWeight = FontWeight.SemiBold,
                )
                if (readOnly) {
                    AppText(spec.title.ifBlank { "Untitled sequence diagram" }, fontSize = 12.sp)
                } else {
                    InlineField(
                        spec.title,
                        { state.seqDiagrams.updateSpec(spec.copy(title = it)) },
                        "Untitled sequence diagram", Modifier.fillMaxWidth(), fontSize = 12.sp,
                    )
                }
                AppText("${rangeSummary(spec.range)} · ${spec.sourceFile ?: "current log"}", color = tc.td, fontSize = 10.sp)
            }
            ToolbarBtn(
                "Inspector",
                icon = Icons.Outlined.Tune,
                showLabel = !state.settings.toolbarIconOnlyButtons,
                tooltip = "Toggle diagram inspector panel",
                active = session.inspectorOpen,
                modifier = Modifier.height(28.dp),
            ) { state.seqDiagrams.updateInspector(open = !session.inspectorOpen) }
            Spacer(Modifier.width(4.dp))
            CloseButton(onClick = ::requestClose)
        }

        Row(Modifier.weight(1f), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            if (session.inspectorOpen) {
                Column(
                    Modifier.width(session.inspectorWidth.dp).fillMaxHeight()
                        .background(tc.bg, CORNER_SM).border(1.dp, tc.br, CORNER_SM)
                        .verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    if (tab != null && !readOnly) WorkspaceInspector(tab, state, spec) { state.seqDiagrams.updateSpec(it) }
                    else OfflineInspector(spec)
                }
                HDivider { delta -> state.seqDiagrams.updateInspector(width = session.inspectorWidth + delta) }
            }
            DiagramPreviewPane(state, Modifier.weight(1f).fillMaxHeight())
        }

        WorkspaceFooter(state, req, readOnly)
    }
    if (state.seqDiagrams.pendingCloseWorkspaceId == workspaceId) {
        Dialog(onDismissRequest = { state.seqDiagrams.cancelWorkspaceClose() }, properties = DialogProperties(dismissOnClickOutside = false)) {
            Column(
                Modifier.width(460.dp).background(tc.p, RoundedCornerShape(8.dp)).border(1.dp, tc.br, RoundedCornerShape(8.dp)).padding(20.dp),
            ) {
                AppText("Save diagram draft?", fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
                Spacer(Modifier.height(6.dp))
                AppText("This workspace has unsaved changes.", color = tc.td, fontSize = 11.sp, maxLines = 3)
                Spacer(Modifier.height(14.dp))
                FlowRow(
                    Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterHorizontally),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    DialogActionButton("Save", active = true, enabled = state.seqDiagrams.preview.diagramOrNull != null) {
                        state.seqDiagrams.closeWorkspace(
                            workspaceId,
                            save = true
                        )
                    }
                    DialogActionButton("Discard", active = false, danger = true) { state.seqDiagrams.closeWorkspace(workspaceId) }
                    DialogActionButton("Cancel", active = false) { state.seqDiagrams.cancelWorkspaceClose() }
                }
            }
        }
    }
}

internal fun rangeSummary(range: DiagramRange): String = when (range) {
    is DiagramRange.Ids -> "Lines ${range.from}–${range.to}"
    is DiagramRange.Time -> "${range.fromTs.ifBlank { "start" }}–${range.toTs.ifBlank { "end" }}"
    DiagramRange.VisibleView -> "Current filtered view"
    is DiagramRange.SeqGroupRef -> "Sequence group ${range.gid}"
}

// ── Preview ──────────────────────────────────────────────────────────────────────────────────

/** Same bordered/clipped/divided shape as the shared `ListStepper` (Components.kt), built directly
 *  from its `StepperButton` pieces rather than calling `ListStepper` itself: zoom is a continuous
 *  Float that ctrl-wheel zoom can land anywhere in `[.15, 2.5]`, stepped by a fixed `.15f` delta —
 *  not a pick from a fixed `List<Int>` of options — so `ListStepper`'s index-snapping contract
 *  would silently change the zoom maths rather than just swap the widget. Clicking the percentage
 *  still resets to 100%, matching the ghost-button trio this replaces. */
@Composable
private fun CanvasZoomStepper(zoom: Float, onZoom: (Float) -> Unit) {
    val tc = tc()
    Row(
        Modifier.border(0.5.dp, tc.br, RoundedCornerShape(6.dp)).clip(RoundedCornerShape(6.dp)),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        StepperButton("−", enabled = true, onClick = { onZoom((zoom - .15f).coerceAtLeast(.15f)) })
        Box(Modifier.width(0.5.dp).height(28.dp).background(tc.br))
        Box(
            Modifier.height(28.dp).clickable { onZoom(1f) }.padding(horizontal = 8.dp),
            contentAlignment = Alignment.Center,
        ) {
            AppText("${(zoom * 100).toInt()}%", color = tc.tx, fontSize = 12.sp, fontFamily = MONO, fontWeight = FontWeight.Medium)
        }
        Box(Modifier.width(0.5.dp).height(28.dp).background(tc.br))
        StepperButton("+", enabled = true, onClick = { onZoom((zoom + .15f).coerceAtMost(2.5f)) })
    }
}

@Composable
private fun DiagramPreviewPane(state: AppState, modifier: Modifier) {
    val tc = tc()
    val theme = tc.toDiagramTheme()
    val preview = state.seqDiagrams.preview
    val diagram = preview.diagramOrNull
    val display by produceState<DiagramDisplay?>(initialValue = null, key1 = diagram, key2 = theme) {
        value = withContext(Dispatchers.Default) { diagram?.let { DiagramRenderCache.display(it, theme) } }
    }
    var zoom by remember { mutableStateOf(1f) }
    var fitZoom by remember { mutableStateOf(1f) }
    var fitWidthZoom by remember { mutableStateOf(1f) }
    // A newly built diagram must never inherit a previous diagram's 100% viewport.  Keep the
    // current user's viewport intact while inspecting it, but auto-fit each new render once.
    var autoFittedDiagram by remember { mutableStateOf<SeqDiagram?>(null) }
    var zoomAnchor by remember { mutableStateOf<CanvasZoomAnchor?>(null) }
    var spaceHeld by remember { mutableStateOf(false) }
    val vertical = rememberScrollState()
    val horizontal = rememberScrollState()
    val scope = rememberCoroutineScope()
    val canvasFocusRequester = remember { FocusRequester() }

    Column(modifier.background(tc.bg, CORNER_SM).border(1.dp, tc.br, CORNER_SM)) {
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 5.dp),
            horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically,
        ) {
            AppText("Canvas", fontSize = 11.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
            CanvasZoomStepper(zoom) { zoom = it }
            SegmentedControl(
                listOf("Fit", "Fit width", "Reset"),
                // None of the three is ever "selected" — these are one-shot actions grouped into
                // a single control, not a persistent mode choice, so selectedIndices stays empty.
                selectedIndices = emptySet(),
                onToggle = { idx ->
                    when (idx) {
                        0 -> zoom = fitZoom
                        1 -> zoom = fitWidthZoom
                        else -> {
                            zoom = 1f
                            zoomAnchor = null
                            scope.launch { vertical.scrollTo(0); horizontal.scrollTo(0) }
                        }
                    }
                },
            )
        }
        Divider()
        when {
            diagram != null && display == null -> CenteredHint("Rendering…", tc.td)
            diagram != null -> {
                val rendered = display!!.rendered
                val bitmap = display!!.bitmap
                BoxWithConstraints(Modifier.weight(1f).fillMaxWidth()) {
                    // Fit shows an orienting overview even for a long trace.  At that scale the
                    // diagram remains navigable through the always-visible scrollbars; Fit width
                    // is available when reading labels is the priority.
                    LaunchedEffect(rendered, maxWidth, maxHeight) {
                        val imageWidth = rendered.widthPx / rendered.scale
                        val imageHeight = rendered.heightPx / rendered.scale
                        val calculatedFit = minOf(maxWidth.value / imageWidth, maxHeight.value / imageHeight)
                            .coerceIn(.15f, 1.5f)
                        fitZoom = calculatedFit
                        fitWidthZoom = (maxWidth.value / imageWidth).coerceIn(.15f, 2.5f)
                        if (autoFittedDiagram != diagram && maxWidth.value > 0f && maxHeight.value > 0f) {
                            zoom = calculatedFit
                            autoFittedDiagram = diagram
                            vertical.scrollTo(0)
                            horizontal.scrollTo(0)
                        }
                    }
                    // Zoom uses the pointer's pre-zoom content coordinate. Applying the scroll
                    // correction after the zoom state commits keeps that exact point under the
                    // cursor instead of jumping toward the top-left.
                    LaunchedEffect(zoom, zoomAnchor) {
                        zoomAnchor?.let { anchor ->
                            horizontal.scrollTo((anchor.content.x * zoom - anchor.pointer.x).roundToInt().coerceAtLeast(0))
                            vertical.scrollTo((anchor.content.y * zoom - anchor.pointer.y).roundToInt().coerceAtLeast(0))
                            zoomAnchor = null
                        }
                    }
                    Box(
                        Modifier.fillMaxWidth().fillMaxHeight()
                            .focusRequester(canvasFocusRequester).focusable()
                            .onPreviewKeyEvent { event ->
                                if (event.key == Key.Spacebar) {
                                    spaceHeld = event.type == KeyEventType.KeyDown
                                    // Space only changes the canvas drag mode; letting it continue
                                    // avoids suppressing a platform-level shortcut unexpectedly.
                                    false
                                } else {
                                    false
                                }
                            }
                            .pointerInput(zoom, spaceHeld) {
                                awaitPointerEventScope {
                                    var panning = false
                                    var lastPosition = Offset.Zero
                                    while (true) {
                                        val event = awaitPointerEvent(PointerEventPass.Initial)
                                        val change = event.changes.firstOrNull() ?: continue
                                        when (event.type) {
                                            PointerEventType.Scroll -> {
                                                val actionPressed = event.keyboardModifiers.isCtrlPressed || event.keyboardModifiers.isMetaPressed
                                                if (actionPressed && change.scrollDelta.y != 0f) {
                                                    val nextZoom = (zoom * exp((-change.scrollDelta.y * .12f).toDouble()).toFloat()).coerceIn(.15f, 2.5f)
                                                    if (nextZoom != zoom) {
                                                        zoomAnchor = CanvasZoomAnchor(
                                                            content = Offset(
                                                                (horizontal.value + change.position.x) / zoom,
                                                                (vertical.value + change.position.y) / zoom
                                                            ),
                                                            pointer = change.position,
                                                        )
                                                        zoom = nextZoom
                                                    }
                                                    event.changes.forEach { it.consume() }
                                                }
                                                // Leave unmodified wheel events untouched so the
                                                // normal vertical/horizontal scroll modifiers run.
                                            }

                                            PointerEventType.Press -> {
                                                canvasFocusRequester.requestFocus()
                                                panning = spaceHeld || event.buttons.isTertiaryPressed
                                                lastPosition = change.position
                                                if (panning) event.changes.forEach { it.consume() }
                                            }

                                            PointerEventType.Move -> if (panning) {
                                                val delta = change.position - lastPosition
                                                horizontal.dispatchRawDelta(-delta.x)
                                                vertical.dispatchRawDelta(-delta.y)
                                                lastPosition = change.position
                                                event.changes.forEach { it.consume() }
                                            }

                                            PointerEventType.Release -> panning = false
                                            else -> Unit
                                        }
                                    }
                                }
                            }
                            .verticalScroll(vertical).horizontalScroll(horizontal),
                    ) {
                        androidx.compose.foundation.Image(
                            bitmap = bitmap,
                            contentDescription = "Sequence diagram preview",
                            modifier = Modifier
                                .width((rendered.widthPx / rendered.scale * zoom).dp)
                                .height((rendered.heightPx / rendered.scale * zoom).dp),
                        )
                    }
                    VerticalScrollbar(
                        adapter = rememberScrollbarAdapter(vertical),
                        modifier = Modifier.align(Alignment.CenterEnd).fillMaxHeight().padding(vertical = 4.dp).width(6.dp),
                        style = appScrollbarStyle(tc),
                    )
                    HorizontalScrollbar(
                        adapter = rememberScrollbarAdapter(horizontal),
                        modifier = Modifier.align(Alignment.BottomStart).fillMaxWidth().height(6.dp).padding(horizontal = 4.dp),
                        style = appScrollbarStyle(tc),
                    )
                }
                val warnings = diagram.warnings
                Column(Modifier.padding(6.dp)) {
                    AppText(
                        "${diagram.messages.size} shown / ${diagram.scannedEntries} scanned · ${diagram.participants.size} lifelines" +
                            diagram.coverage.let { coverage ->
                                buildString {
                                    if (coverage.groupedEntries > 0) append(" · ${coverage.groupedEntries} grouped")
                                    if (coverage.hiddenEntries > 0) append(" · ${coverage.hiddenEntries} hidden")
                                }
                            } +
                            if (diagram.truncated) " · truncated" else "",
                        color = tc.td, fontSize = 10.sp,
                    )
                    warnings.take(2).forEach { AppText(it, color = DANGER_RED, fontSize = 10.sp, maxLines = 2) }
                }
            }

            preview is DiagramPreviewState.Failed -> CenteredHint(preview.message, DANGER_RED)
            preview is DiagramPreviewState.Computing -> CenteredHint("Building…", tc.td)
            else -> CenteredHint("Pick participants and a range.", tc.td)
        }
    }
}

@Composable
private fun CenteredHint(text: String, color: androidx.compose.ui.graphics.Color) {
    Box(Modifier.fillMaxWidth().heightIn(min = 260.dp), contentAlignment = Alignment.Center) {
        AppText(text, color = color, fontSize = 11.sp, maxLines = 3)
    }
}

// ── Footer ───────────────────────────────────────────────────────────────────────────────────

// Split-button shapes for the More/Attach pair — same joined-pair idea as the Notes header's
// Open/▾ split button (AnnotationPanel.kt) and TabBar's log-file Open/▾ precedent.
private val FOOTER_MORE_SHAPE = RoundedCornerShape(topStart = 4.dp, bottomStart = 4.dp)
private val FOOTER_ATTACH_SHAPE = RoundedCornerShape(topEnd = 4.dp, bottomEnd = 4.dp)

@Composable
private fun WorkspaceFooter(state: AppState, request: SeqDiagramRequest?, readOnly: Boolean) {
    val tc = tc()
    val ready = state.seqDiagrams.preview.diagramOrNull?.messages?.isNotEmpty() == true
    val offlineSource = state.seqDiagrams.offlineLibraryRequest?.item?.parsed?.source
    val linkedPrimary = state.settings.diagramLinkedNotePrimary
    var moreOpen by remember { mutableStateOf(false) }

    fun attach(link: Boolean) {
        val req = request ?: return
        // Attachments always reference a durable draft. Saving first also means a second attach
        // can choose snapshot or link without rebuilding/closing the workspace.
        val libraryId = req.libraryItemId ?: state.seqDiagrams.saveDraft()?.id ?: return
        val blockId = if (link) state.seqDiagrams.attachLibraryLink(req.tabId, libraryId) else state.seqDiagrams.attachLibrarySnapshot(req.tabId, libraryId)
        if (blockId != null) state.annotationVisible = true
    }

    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        AppButton("Save draft", { state.seqDiagrams.saveDraft() }, enabled = ready && !readOnly)
        Spacer(Modifier.weight(1f))
        // The anchor's own measured height is what lets the menu open upward — see the note on
        // DiagramFooterMoreMenu.
        var moreAnchorHeightPx by remember { mutableStateOf(0) }
        Box(Modifier.onSizeChanged { moreAnchorHeightPx = it.height }) {
            AppButton("More ▾", { moreOpen = !moreOpen }, shape = FOOTER_MORE_SHAPE, enabled = ready)
            if (moreOpen) {
                DiagramFooterMoreMenu(
                    anchorHeightPx = moreAnchorHeightPx,
                    secondaryAttachLabel = if (linkedPrimary) "Attach snapshot" else "Attach linked",
                    canAttach = ready && !readOnly,
                    canCopy = ready,
                    onAttachSecondary = { attach(!linkedPrimary) },
                    onCopySource = { (state.seqDiagrams.currentSource() ?: offlineSource)?.let { state.copyToClipboard(it) } },
                    onCopyImage = {
                        // The plain-text fallback is the dialect source: a paste target that can't
                        // take an image (a code review comment, a terminal) still gets something.
                        val source = state.seqDiagrams.currentSource() ?: offlineSource.orEmpty()
                        state.seqDiagrams.currentPng(tc.toDiagramTheme())?.let { state.copyImageToClipboard(it, source) }
                    },
                    onDismiss = { moreOpen = false },
                    tc = tc,
                )
            }
        }
        TooltipArea(
            tooltip = {
                ToolbarTooltip(if (linkedPrimary) "Attach a linked reference that follows this diagram" else "Attach a static snapshot image")
            },
        ) {
            AppButton(
                "Attach to note",
                { attach(linkedPrimary) },
                variant = ButtonVariant.Primary,
                shape = FOOTER_ATTACH_SHAPE,
                enabled = ready && !readOnly,
            )
        }
    }
}

/** Opens UPWARD, unlike the toolbar menus it is otherwise modelled on (LogViewer's
 *  ExportMenuPopup, TabBar's recent-files list).  Those anchor to a bar along the top of the
 *  window, so a downward `Alignment.TopEnd` + positive-y offset lands the popup just below the
 *  button; this footer sits on the *bottom* edge, where the same offset would place the menu past
 *  it and out of the window.  `Alignment.BottomEnd` puts the popup's bottom-right on the anchor's
 *  bottom-right — covering the anchor and extending up — so lifting it by the anchor's own
 *  measured height plus a gap clears the button entirely.  Measured, not a guessed constant,
 *  because AppButton's height is content-derived and moves with the base font size.
 */
@Composable
private fun DiagramFooterMoreMenu(
    anchorHeightPx: Int,
    secondaryAttachLabel: String,
    canAttach: Boolean,
    canCopy: Boolean,
    onAttachSecondary: () -> Unit,
    onCopySource: () -> Unit,
    onCopyImage: () -> Unit,
    onDismiss: () -> Unit,
    tc: ThemeColors,
) {
    val gapPx = with(LocalDensity.current) { 4.dp.roundToPx() }
    Popup(
        alignment = Alignment.BottomEnd,
        offset = IntOffset(0, -(anchorHeightPx + gapPx)),
        onDismissRequest = onDismiss,
        properties = PopupProperties(focusable = true),
    ) {
        Column(
            Modifier.width(180.dp)
                .background(tc.p, RoundedCornerShape(7.dp))
                .border(1.dp, tc.br, RoundedCornerShape(7.dp))
                .padding(vertical = 4.dp),
        ) {
            DiagramFooterMenuItem(secondaryAttachLabel, canAttach, tc) { onAttachSecondary(); onDismiss() }
            DiagramFooterMenuItem("Copy source", canCopy, tc) { onCopySource(); onDismiss() }
            DiagramFooterMenuItem("Copy image", canCopy, tc) { onCopyImage(); onDismiss() }
        }
    }
}

// Gate on real applicability rather than hiding (TabBar.kt's Compare/Video convention) — a
// dimmed, unclickable row still tells the reader the action exists and why it can't fire yet.
@Composable
private fun DiagramFooterMenuItem(label: String, enabled: Boolean, tc: ThemeColors, onClick: () -> Unit) {
    HoverBox(modifier = Modifier.fillMaxWidth(), onClick = if (enabled) onClick else null) {
        AppText(
            label,
            color = if (enabled) tc.tx else tc.td.copy(.5f),
            fontSize = 11.sp,
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
        )
    }
}
