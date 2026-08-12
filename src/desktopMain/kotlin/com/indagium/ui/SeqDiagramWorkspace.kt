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
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
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
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
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
import com.indagium.diagram.ArrowHit
import com.indagium.diagram.DiagramCallOverride
import com.indagium.diagram.DiagramMessageOverride
import com.indagium.diagram.DiagramParameter
import com.indagium.diagram.MessageOriginKey
import com.indagium.diagram.MessageKind
import com.indagium.diagram.DiagramRange
import com.indagium.diagram.RenderedDiagram
import com.indagium.diagram.displayName
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.math.exp
import kotlin.math.roundToInt
import java.awt.Cursor as AwtCursor

private data class CanvasZoomAnchor(val content: Offset, val pointer: Offset)

private data class CallCorrectionDraft(
    val entryId: Int,
    val edgeOrdinal: Int,
    val fromId: String,
    val toId: String,
)

private fun messageOrigins(message: com.indagium.diagram.DiagramMessage): Set<MessageOriginKey> =
    message.originKeys.ifEmpty { setOf(MessageOriginKey(message.entryId, generatedOrdinal = message.edgeOrdinal)) }

private fun manualSnapshotFor(
    diagram: com.indagium.diagram.SeqDiagram,
    selectedOrigins: Set<MessageOriginKey>,
): List<com.indagium.diagram.ManualDiagramInteraction> = diagram.messages.mapNotNull { message ->
    if (messageOrigins(message).none { it in selectedOrigins }) return@mapNotNull null
    com.indagium.diagram.DiagramProposalService.manualSeedFromVerifiedSourceMessage(message, diagram.participants)
        ?: run {
            val from = diagram.participants.getOrNull(message.fromIdx)?.id ?: return@run null
            val to = diagram.participants.getOrNull(message.toIdx)?.id ?: return@run null
            val origin = messageOrigins(message).firstOrNull { it in selectedOrigins }
                ?: return@run null
            com.indagium.diagram.ManualDiagramInteraction(
                id = origin.manualInteractionId ?: "snapshot:${origin.entryId}:${origin.generatedOrdinal}:${origin.ruleId.orEmpty()}",
                sourceEntryIds = message.representedEntryIds.ifEmpty { setOf(message.entryId) },
                fromParticipantId = from, toParticipantId = to, operation = message.label, label = message.label,
                kind = message.kind, order = message.entryId.toLong(),
                groupKey = com.indagium.diagram.manualInteractionGroupKey(
                    message.sourceOperationId, message.sourceLogSiteId, from, to, message.kind, message.label,
                ),
                sourceMethodId = message.sourceOperationId,
                sourceLogSiteId = message.sourceLogSiteId,
                sourceOwnerType = diagram.participants.getOrNull(message.fromIdx)?.sourceOwnerType,
            )
        }
}.distinctBy { it.id }

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
    // Source indexing is asynchronous.  A workspace can be opened before the index finishes,
    // which previously left its preview permanently in the source-free/self-event state until
    // the user changed an unrelated inspector option.  Rebuild when a new index snapshot is
    // published, even when the inspector is collapsed.
    val sourceIndexBuiltAt = state.sourceIndex?.builtAt
    LaunchedEffect(workspaceId, sourceIndexBuiltAt) {
        if (!readOnly && sourceIndexBuiltAt != null) {
            state.seqDiagrams.requestPreview(tab.id, spec)
        }
    }
    val tc = tc()
    var correction by remember(workspaceId) { mutableStateOf<CallCorrectionDraft?>(null) }
    var selectedOrigins by remember(workspaceId) { mutableStateOf<Set<MessageOriginKey>>(emptySet()) }
    var batchEditorOpen by remember(workspaceId) { mutableStateOf(false) }

    fun requestClose() {
        state.seqDiagrams.requestCloseWorkspace(workspaceId)
    }

    Column(
        Modifier.fillMaxSize().background(tc.p).padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Row(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
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
            Row(
                horizontalArrangement = Arrangement.spacedBy(4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                if (!readOnly && selectedOrigins.isNotEmpty()) {
                    AppButton("Edit ${selectedOrigins.size} selected", { batchEditorOpen = true }, variant = ButtonVariant.Secondary)
                    val focus = session.preview.diagramOrNull?.messages?.firstOrNull { messageOrigins(it).any { it in selectedOrigins } }
                    fun addCohort(predicate: (com.indagium.diagram.DiagramMessage) -> Boolean) {
                        val additions = session.preview.diagramOrNull?.messages.orEmpty()
                            .filter(predicate).flatMapTo(linkedSetOf(), ::messageOrigins)
                        selectedOrigins += additions
                    }
                    focus?.let { message ->
                        AppButton("Same ends", { addCohort { it.fromIdx == message.fromIdx && it.toIdx == message.toIdx } }, variant = ButtonVariant.Ghost)
                        AppButton("Same label", { addCohort { it.label == message.label } }, variant = ButtonVariant.Ghost)
                        AppButton("Same evidence", { addCohort { it.evidence == message.evidence } }, variant = ButtonVariant.Ghost)
                        message.sourceOperationId?.let { operation ->
                            AppButton("Same operation", { addCohort { it.sourceOperationId == operation } }, variant = ButtonVariant.Ghost)
                        }
                    }
                    AppButton("Make manual", {
                        val diagram = session.preview.diagramOrNull ?: return@AppButton
                        val snapshot = manualSnapshotFor(diagram, selectedOrigins)
                        if (snapshot.isEmpty()) return@AppButton
                        val existing = spec.manualDocument.interactions.associateBy { it.id }
                        state.seqDiagrams.updateSpec(spec.copy(
                            authoringMode = com.indagium.diagram.DiagramAuthoringMode.MANUAL,
                            lifelineOrder = diagram.participants.map { it.id },
                            manualDocument = spec.manualDocument.copy(
                                interactions = (existing + snapshot.associateBy { it.id }).values.sortedBy { it.order },
                            ),
                        ))
                        selectedOrigins = emptySet()
                    }, variant = ButtonVariant.Ghost)
                    AppButton("Clear", { selectedOrigins = emptySet() }, variant = ButtonVariant.Ghost)
                }
                ToolbarBtn(
                    "Inspector",
                    icon = Icons.Outlined.Tune,
                    showLabel = false,
                    tooltip = "Toggle diagram inspector panel",
                    active = session.inspectorOpen,
                    // CloseButton owns a 24.dp hit box; use the same box with no vertical offset
                    // so the two controls share one center line.
                    modifier = Modifier.size(24.dp),
                    contentPadding = PaddingValues(0.dp),
                ) { state.seqDiagrams.updateInspector(open = !session.inspectorOpen) }
                CloseButton(onClick = ::requestClose)
            }
        }

        Row(Modifier.weight(1f), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            if (session.inspectorOpen) {
                Column(
                    Modifier.width(session.inspectorWidth.dp).fillMaxHeight()
                        .background(tc.bg, CORNER_SM).border(1.dp, tc.br, CORNER_SM)
                        .verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    if (tab != null && !readOnly) WorkspaceInspector(
                        tab = tab,
                        state = state,
                        spec = spec,
                        preview = session.preview.diagramOrNull,
                        selectedEntryIds = selectedOrigins.mapTo(linkedSetOf()) { it.entryId },
                        onSpec = { state.seqDiagrams.updateSpec(it) },
                    )
                    else OfflineInspector(spec)
                }
                HDivider { delta -> state.seqDiagrams.resizeInspectorBy(delta) }
            }
            DiagramPreviewPane(state, session, Modifier.weight(1f).fillMaxHeight()) { _, message ->
                // Context-click is additive and works on collapsed arrows too: their origin set
                // contains every member, so one selection can batch-correct all represented rows.
                val origins = messageOrigins(message)
                selectedOrigins = if (origins.all { it in selectedOrigins }) selectedOrigins - origins else selectedOrigins + origins
            }
        }

        WorkspaceFooter(state, req, readOnly)
    }
    if (correction != null && !readOnly) {
        val current = correction!!
        val previewDiagram = session.preview.diagramOrNull
        if (previewDiagram != null) {
            CallCorrectionDialog(
                diagram = previewDiagram,
                draft = current,
                existing = spec.callOverrides.firstOrNull { it.entryId == current.entryId && it.edgeOrdinal == current.edgeOrdinal },
                onSave = { from, to ->
                    val override = DiagramCallOverride(current.entryId, current.edgeOrdinal, from, to)
                    state.seqDiagrams.updateSpec(
                        spec.copy(
                            callOverrides = spec.callOverrides.filterNot {
                                it.entryId == current.entryId && it.edgeOrdinal == current.edgeOrdinal
                            } + override,
                        ),
                    )
                    correction = null
                },
                onRemove = {
                    state.seqDiagrams.updateSpec(
                        spec.copy(callOverrides = spec.callOverrides.filterNot {
                            it.entryId == current.entryId && it.edgeOrdinal == current.edgeOrdinal
                        }),
                    )
                    correction = null
                },
                onDismiss = { correction = null },
            )
        }
    }
    if (batchEditorOpen && !readOnly) {
        session.preview.diagramOrNull?.let { diagram ->
            MessageBatchEditDialog(
                diagram = diagram,
                selectedOrigins = selectedOrigins,
                onSave = { from, to, label, kind, parameters ->
                    val replacements = selectedOrigins.map { origin ->
                        DiagramMessageOverride(
                            origin = origin, fromParticipantId = from, toParticipantId = to,
                            label = label.ifBlank { null }, kind = kind, parameters = parameters,
                        )
                    }
                    state.seqDiagrams.updateSpec(spec.copy(
                        messageOverrides = spec.messageOverrides.filterNot { it.origin in selectedOrigins } + replacements,
                    ))
                    batchEditorOpen = false
                },
                onRequestReset = {
                    state.seqDiagrams.updateSpec(spec.copy(messageOverrides = spec.messageOverrides.filterNot { it.origin in selectedOrigins }))
                    batchEditorOpen = false
                },
                onDismiss = { batchEditorOpen = false },
            )
        }
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
    DiagramRange.VisibleView -> "Whole log range"
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

// A plain left-drag starts panning only once the pointer has moved this many RAW (device) pixels
// from its Press position — short of that, Release is treated as a click and hit-tested against
// the canvas (see resolveCanvasClickHit). Compared directly against another raw-pixel Offset, so
// (unlike a dp-based layout size) this needs no LocalDensity conversion — see CLAUDE.md's own note
// that pointer positions/deltas are already in raw pixels.
private const val CANVAS_PAN_SLOP_PX = 5f

/**
 * Maps a canvas pointer position to the underlying [RenderedDiagram]'s own image-pixel space and
 * resolves the [ArrowHit] under it, if any — the click-to-navigate counterpart of
 * `AnnotationPanel.kt`'s read-only `DiagramNoteView` tap handler, generalized to account for BOTH
 * pan (scroll offset) and zoom, which that simpler note-card preview never has (it always shows
 * the whole image at a fitted size, with no scrolling).
 *
 * The canvas `Image` is laid out at `(rendered.widthPx / rendered.scale * zoom).dp` inside a
 * `verticalScroll`/`horizontalScroll` pair. [clickPositionPx] and the two `ScrollState.value`s are
 * already in the SAME raw-device-pixel frame (Compose scroll offsets are pixels, exactly like
 * pointer positions — see CLAUDE.md), so they can be added directly with no density conversion;
 * [density] is only needed to convert the Image's `*.dp*` on-screen size into that same pixel
 * frame, and [RenderedDiagram.scale] then converts from there down to the image's OWN pixel grid —
 * the one [RenderedDiagram.hits] is expressed in. So, in order:
 * `contentPx = clickPositionPx + scrollPx` (undo pan) → `contentPx / density / zoom` (undo the
 * `.dp` zoom-scaled layout size, landing in the SAME "1x" unit `rendered.widthPx / rendered.scale`
 * is in) → `* rendered.scale` (undo the renderer's own 2x/etc raster scale, landing in image
 * pixels).
 */
internal fun resolveCanvasClickHit(
    rendered: RenderedDiagram,
    clickPositionPx: Offset,
    horizontalScrollPx: Float,
    verticalScrollPx: Float,
    zoom: Float,
    density: Float,
): ArrowHit? {
    if (zoom <= 0f || density <= 0f) return null
    val contentX = clickPositionPx.x + horizontalScrollPx
    val contentY = clickPositionPx.y + verticalScrollPx
    val imageX = (contentX / density / zoom * rendered.scale).roundToInt()
    val imageY = (contentY / density / zoom * rendered.scale).roundToInt()
    return rendered.hits.firstOrNull { h ->
        imageX >= h.x && imageX <= h.x + h.width && imageY >= h.y && imageY <= h.y + h.height
    }
}

@Composable
private fun DiagramPreviewPane(
    state: AppState,
    session: DiagramWorkspaceSession,
    modifier: Modifier,
    onCorrection: (ArrowHit, com.indagium.diagram.DiagramMessage) -> Unit,
) {
    val tc = tc()
    val theme = tc.toDiagramTheme()
    val preview = state.seqDiagrams.preview
    val diagram = preview.diagramOrNull
    val display by produceState<DiagramDisplay?>(initialValue = null, key1 = diagram, key2 = theme) {
        value = withContext(Dispatchers.Default) { diagram?.let { DiagramRenderCache.display(it, theme) } }
    }
    val zoom = session.zoom
    var fitZoom by remember { mutableStateOf(1f) }
    var fitWidthZoom by remember { mutableStateOf(1f) }
    // Guards ONLY the one-time scroll-to-origin a freshly mounted workspace gets. This composable
    // is remounted per diagram tab (App.kt keys SeqDiagramWorkspace on the workspace id), so "this
    // composable's first LaunchedEffect run" and "the workspace's first render" are the same event
    // — switching back to an already-visited workspace mounts a fresh Box/ScrollState anyway, so
    // there is no stale scroll position to preserve. Every LATER effect run in the same mount (a
    // spec edit rebuilding the diagram, a window resize) still reapplies a sticky FIT/FIT_WIDTH
    // zoom, but must not also yank the user's scroll position back to (0,0).
    var hasFittedOnce by remember { mutableStateOf(false) }
    var zoomAnchor by remember { mutableStateOf<CanvasZoomAnchor?>(null) }
    var spaceHeld by remember { mutableStateOf(false) }
    val vertical = rememberScrollState()
    val horizontal = rememberScrollState()
    val scope = rememberCoroutineScope()
    val canvasFocusRequester = remember { FocusRequester() }
    val density = LocalDensity.current.density

    fun setZoom(next: Float) = state.seqDiagrams.updateViewport(zoom = next, mode = DiagramZoomMode.MANUAL)

    Column(modifier.background(tc.bg, CORNER_SM).border(1.dp, tc.br, CORNER_SM)) {
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 5.dp),
            horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically,
        ) {
            AppText("Canvas", fontSize = 11.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
            CanvasZoomStepper(zoom) { setZoom(it) }
            SegmentedControl(
                listOf("Fit", "Fit width", "Reset"),
                // Fit/Fit width are now sticky modes (Part B), reflected here for real; Reset stays
                // a one-shot action that drops back to MANUAL, so it never shows as "selected".
                selectedIndices = when (session.zoomMode) {
                    DiagramZoomMode.FIT -> setOf(0)
                    DiagramZoomMode.FIT_WIDTH -> setOf(1)
                    DiagramZoomMode.MANUAL -> emptySet()
                },
                onToggle = { idx ->
                    when (idx) {
                        0 -> state.seqDiagrams.updateViewport(mode = DiagramZoomMode.FIT)
                        1 -> state.seqDiagrams.updateViewport(mode = DiagramZoomMode.FIT_WIDTH)
                        else -> {
                            state.seqDiagrams.updateViewport(zoom = 1f, mode = DiagramZoomMode.MANUAL)
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
                    // is available when reading labels is the priority. Re-runs on every rebuild
                    // (spec edit → new `rendered`) AND on a window resize (maxWidth/maxHeight), and
                    // on an explicit mode switch (session.zoomMode) — each time, it recomputes both
                    // candidate fits (needed so a resize doesn't let fitWidthZoom quietly go stale
                    // while a sticky Fit-width selection keeps showing the OLD value) and applies
                    // whichever one the current mode calls for; MANUAL leaves the user's own zoom
                    // alone entirely.
                    LaunchedEffect(rendered, maxWidth, maxHeight, session.zoomMode) {
                        if (maxWidth.value <= 0f || maxHeight.value <= 0f) return@LaunchedEffect
                        val imageWidth = rendered.widthPx / rendered.scale
                        val imageHeight = rendered.heightPx / rendered.scale
                        val calculatedFit = minOf(maxWidth.value / imageWidth, maxHeight.value / imageHeight)
                            .coerceIn(.15f, 1.5f)
                        fitZoom = calculatedFit
                        fitWidthZoom = (maxWidth.value / imageWidth).coerceIn(.15f, 2.5f)
                        val target = when (session.zoomMode) {
                            DiagramZoomMode.FIT -> calculatedFit
                            DiagramZoomMode.FIT_WIDTH -> fitWidthZoom
                            DiagramZoomMode.MANUAL -> null
                        }
                        if (target != null && target != session.zoom) state.seqDiagrams.updateViewport(zoom = target)
                        if (!hasFittedOnce) {
                            hasFittedOnce = true
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
                    // spaceHeld (onPreviewKeyEvent below) only ever changes while this Box holds
                    // keyboard focus, which used to require an in-canvas click first — request it
                    // as soon as the pane appears so space-to-pan works immediately, and drop it on
                    // focus loss so a held key can never get stuck "on" for a pane that can no
                    // longer see key events at all.
                    LaunchedEffect(Unit) { runCatching { canvasFocusRequester.requestFocus() } }
                    Box(
                        Modifier.fillMaxWidth().fillMaxHeight()
                            .focusRequester(canvasFocusRequester).focusable()
                            .onFocusChanged { if (!it.isFocused) spaceHeld = false }
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
                            // MOVE_CURSOR (the same "draggable" affordance AnnotationPanel's own
                            // reorder handle uses) rather than a literal open/closed hand — java.awt
                            // has no distinct grab/grabbing pair to switch between on press.
                            .pointerHoverIcon(PointerIcon(AwtCursor.getPredefinedCursor(AwtCursor.MOVE_CURSOR)))
                            .pointerInput(zoom, spaceHeld, rendered) {
                                awaitPointerEventScope {
                                    var panning = false
                                    // Armed on a plain left Press, resolved into either a pan (once
                                    // movement crosses CANVAS_PAN_SLOP_PX) or a click (on Release
                                    // while still below it) — see this file's own doc on
                                    // resolveCanvasClickHit for the click side.
                                    var pendingPan = false
                                    var downPosition = Offset.Zero
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
                                                        setZoom(nextZoom)
                                                    }
                                                    event.changes.forEach { it.consume() }
                                                }
                                                // Leave unmodified wheel events untouched so the
                                                // normal vertical/horizontal scroll modifiers run.
                                            }

                            PointerEventType.Press -> {
                                canvasFocusRequester.requestFocus()
                                downPosition = change.position
                                lastPosition = change.position
                                when {
                                    event.buttons.isSecondaryPressed -> {
                                        val hit = resolveCanvasClickHit(
                                            rendered = rendered,
                                            clickPositionPx = change.position,
                                            horizontalScrollPx = horizontal.value.toFloat(),
                                            verticalScrollPx = vertical.value.toFloat(),
                                            zoom = zoom,
                                            density = density,
                                        )
                                        val message = hit?.messageIndex?.let { diagram.messages.getOrNull(it) }
                                        if (hit != null && message != null && message.kind != MessageKind.RETURN) onCorrection(hit, message)
                                        event.changes.forEach { it.consume() }
                                        pendingPan = false
                                        panning = false
                                    }
                                    spaceHeld || event.buttons.isTertiaryPressed -> {
                                                        panning = true
                                                        pendingPan = false
                                                        event.changes.forEach { it.consume() }
                                                    }
                                                    event.buttons.isPrimaryPressed -> {
                                                        // Don't consume yet: a plain press that never
                                                        // moves must still resolve as a click below.
                                                        panning = false
                                                        pendingPan = true
                                                    }
                                                    else -> {
                                                        panning = false
                                                        pendingPan = false
                                                    }
                                                }
                                            }

                                            PointerEventType.Move -> {
                                                if (panning) {
                                                    val delta = change.position - lastPosition
                                                    horizontal.dispatchRawDelta(-delta.x)
                                                    vertical.dispatchRawDelta(-delta.y)
                                                    lastPosition = change.position
                                                    event.changes.forEach { it.consume() }
                                                } else if (pendingPan) {
                                                    val moved = change.position - downPosition
                                                    if (moved.getDistance() > CANVAS_PAN_SLOP_PX) {
                                                        panning = true
                                                        pendingPan = false
                                                        lastPosition = change.position
                                                        event.changes.forEach { it.consume() }
                                                    }
                                                }
                                            }

                                            PointerEventType.Release -> {
                                                if (pendingPan && !panning) {
                                                val hit = resolveCanvasClickHit(
                                                        rendered = rendered,
                                                        clickPositionPx = change.position,
                                                        horizontalScrollPx = horizontal.value.toFloat(),
                                                        verticalScrollPx = vertical.value.toFloat(),
                                                        zoom = zoom,
                                                        density = density,
                                                    )
                                                    // Matches AnnotationPanel's DiagramNoteView guard
                                                    // (entryId > 0); a null sourceTabId means either
                                                    // an offline/library workspace or one whose log
                                                    // was closed — navigateToLogLine no-ops safely on
                                                    // a missing tab either way, so this never crashes.
                                                    if (hit != null && hit.entryId > 0) {
                                                        session.sourceTabId?.let { tabId -> state.navigateToLogLine(tabId, hit.entryId) }
                                                    }
                                                }
                                                panning = false
                                                pendingPan = false
                                            }

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
                val traceDiagnostics = diagram.resolvedTrace?.diagnostics
                Column(Modifier.padding(6.dp)) {
                    AppText(
                        "${diagram.traceMode.name.lowercase().replace('_', ' ')} · ${diagram.messages.size} shown / ${diagram.scannedEntries} scanned · ${diagram.participants.size} lifelines" +
                            diagram.coverage.let { coverage ->
                                buildString {
                                    if (coverage.groupedEntries > 0) append(" · ${coverage.groupedEntries} grouped")
                                    if (coverage.hiddenEntries > 0) append(" · ${coverage.hiddenEntries} hidden")
                                }
                            } +
                            if (diagram.truncated) " · truncated" else "",
                        color = tc.td, fontSize = 10.sp,
                    )
                    if (diagram.resolvedTrace != null) {
                        AppText(
                            "Source mappings: ${diagram.resolvedTrace.events.size} logs · ${diagram.resolvedTrace.calls.size} invocations · ${diagram.resolvedTrace.operations.size} operations",
                            color = tc.td,
                            fontSize = 10.sp,
                            maxLines = 1,
                        )
                    }
                    warnings.take(2).forEach { AppText(it, color = DANGER_RED, fontSize = 10.sp, maxLines = 2) }
                    if (traceDiagnostics != null && traceDiagnostics.diagnostics.isNotEmpty()) {
                        AppText(
                            "Trace diagnostics: ${traceDiagnostics.diagnostics.size}" +
                                if (traceDiagnostics.truncated) " · capped" else "",
                            color = tc.td,
                            fontSize = 10.sp,
                            maxLines = 1,
                        )
                        traceDiagnostics.diagnostics.take(2).forEach { diagnostic ->
                            AppText(
                                "${diagnostic.reason.name.lowercase()}" +
                                    (diagnostic.entryId?.let { " · entry $it" } ?: "") +
                                    (diagnostic.detail?.let { " · $it" } ?: ""),
                                color = DANGER_RED,
                                fontSize = 10.sp,
                                maxLines = 2,
                            )
                        }
                    }
                }
            }

            preview is DiagramPreviewState.Failed -> Box(
                Modifier.fillMaxWidth().heightIn(min = 260.dp),
                contentAlignment = Alignment.Center,
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    AppText(preview.message, color = DANGER_RED, fontSize = 11.sp, maxLines = 3)
                    state.seqDiagrams.request?.let { request ->
                        AppButton("Retry preview", {
                            state.seqDiagrams.requestPreview(request.tabId, request.spec)
                        }, variant = ButtonVariant.Ghost)
                    }
                }
            }
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

@Composable
private fun CallCorrectionDialog(
    diagram: com.indagium.diagram.SeqDiagram,
    draft: CallCorrectionDraft,
    existing: DiagramCallOverride?,
    onSave: (String, String) -> Unit,
    onRemove: () -> Unit,
    onDismiss: () -> Unit,
) {
    val tc = tc()
    var fromId by remember(draft) { mutableStateOf(draft.fromId) }
    var toId by remember(draft) { mutableStateOf(draft.toId) }
    var fromSearch by remember(draft) { mutableStateOf("") }
    var toSearch by remember(draft) { mutableStateOf("") }
    val participants = diagram.participants
    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Column(
            Modifier.width(620.dp).background(tc.p, RoundedCornerShape(8.dp)).border(1.dp, tc.br, RoundedCornerShape(8.dp)).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            AppText("Correct rendered call", fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
            AppText("Entry ${draft.entryId}, generated edge ${draft.edgeOrdinal}. Choose the exact lifelines for this edge.", color = tc.td, fontSize = 10.sp, maxLines = 2)
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                CallCorrectionParticipantPicker("Begin component", fromId, fromSearch, participants, Modifier.weight(1f), { fromSearch = it }, { fromId = it })
                CallCorrectionParticipantPicker("End component", toId, toSearch, participants, Modifier.weight(1f), { toSearch = it }, { toId = it })
            }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End)) {
                if (existing != null) AppButton("Remove correction", onRemove, variant = ButtonVariant.Ghost)
                AppButton("Cancel", onDismiss, variant = ButtonVariant.Ghost)
                AppButton("Save correction", { onSave(fromId, toId) }, variant = ButtonVariant.Primary, enabled = fromId.isNotBlank() && toId.isNotBlank())
            }
        }
    }
}

@Composable
private fun CallCorrectionParticipantPicker(
    title: String,
    selectedId: String,
    search: String,
    participants: List<com.indagium.diagram.DiagramParticipant>,
    modifier: Modifier,
    onSearch: (String) -> Unit,
    onSelect: (String) -> Unit,
) {
    val tc = tc()
    val filtered = participants.filter {
        search.isBlank() || it.id.contains(search.trim(), true) || it.displayName.contains(search.trim(), true)
    }
    Column(modifier, verticalArrangement = Arrangement.spacedBy(5.dp)) {
        AppText(title, fontSize = 10.sp, fontWeight = FontWeight.SemiBold)
        InlineField(search, onSearch, "Search lifelines…", Modifier.fillMaxWidth(), fontSize = 10.sp)
        BoundedScrollBoxDp(150) {
            filtered.forEach { participant ->
                AppButton(
                    if (participant.id == selectedId) "✓ ${participant.displayName}" else participant.displayName,
                    { onSelect(participant.id) },
                    variant = if (participant.id == selectedId) ButtonVariant.Primary else ButtonVariant.Ghost,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        }
        AppText(selectedId, color = tc.td, fontSize = 9.sp, fontFamily = MONO, maxLines = 1, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis)
    }
}

/** Bulk editor for stable message origins.  It is intentionally separate from the legacy
 * CallCorrectionDialog: one displayed arrow can represent many origins after repeat collapsing,
 * and a RETURN is a legitimate batch target. */
@Composable
private fun MessageBatchEditDialog(
    diagram: com.indagium.diagram.SeqDiagram,
    selectedOrigins: Set<MessageOriginKey>,
    onSave: (String, String, String, MessageKind, List<DiagramParameter>) -> Unit,
    onRequestReset: () -> Unit,
    onDismiss: () -> Unit,
) {
    val tc = tc()
    val first = diagram.messages.firstOrNull { messageOrigins(it).any { origin -> origin in selectedOrigins } }
    var fromId by remember(selectedOrigins) { mutableStateOf(first?.fromIdx?.let { diagram.participants.getOrNull(it)?.id }.orEmpty()) }
    var toId by remember(selectedOrigins) { mutableStateOf(first?.toIdx?.let { diagram.participants.getOrNull(it)?.id }.orEmpty()) }
    var label by remember(selectedOrigins) { mutableStateOf("") }
    var parameters by remember(selectedOrigins) { mutableStateOf("") }
    var kind by remember(selectedOrigins) { mutableStateOf(first?.kind ?: MessageKind.CALL) }
    var confirmReset by remember(selectedOrigins) { mutableStateOf(false) }
    val kinds = listOf(MessageKind.CALL, MessageKind.RETURN, MessageKind.SELF, MessageKind.ASYNC)

    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Column(
            Modifier.width(680.dp).background(tc.p, RoundedCornerShape(8.dp)).border(1.dp, tc.br, RoundedCornerShape(8.dp)).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            AppText("Edit ${selectedOrigins.size} selected message${if (selectedOrigins.size == 1) "" else "s"}", fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
            AppText(
                "Changes apply to every stable origin selected on the canvas, including every row represented by a collapsed arrow.",
                color = tc.td, fontSize = 10.sp, maxLines = 2,
            )
            BatchParticipantPicker("From", fromId, diagram.participants) { fromId = it }
            BatchParticipantPicker("To", toId, diagram.participants) { toId = it }
            InlineField(label, { label = it }, "custom label (leave blank to keep generated label)", Modifier.fillMaxWidth(), fontSize = 10.sp)
            InlineField(parameters, { parameters = it }, "parameters: name=value; …", Modifier.fillMaxWidth(), fontSize = 10.sp)
            SegmentedControl(kinds.map { it.name.lowercase() }, setOf(kinds.indexOf(kind)), onToggle = { kind = kinds[it] })
            if (confirmReset) {
                AppText("Remove all saved edits for these selected messages?", color = DANGER_RED, fontSize = 10.sp)
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    AppButton("Cancel", { confirmReset = false }, variant = ButtonVariant.Ghost)
                    AppButton("Reset selected", onRequestReset, variant = ButtonVariant.Secondary, isDanger = true)
                }
            } else {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End)) {
                    AppButton("Reset selected", { confirmReset = true }, variant = ButtonVariant.Ghost, isDanger = true)
                    AppButton("Cancel", onDismiss, variant = ButtonVariant.Ghost)
                    AppButton(
                        "Apply to selected",
                        { onSave(fromId, toId, label, kind, parameters.parseBatchParameters()) },
                        variant = ButtonVariant.Primary,
                        enabled = fromId.isNotBlank() && toId.isNotBlank(),
                    )
                }
            }
        }
    }
}

@Composable
private fun BatchParticipantPicker(
    title: String,
    selectedId: String,
    participants: List<com.indagium.diagram.DiagramParticipant>,
    onSelect: (String) -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(3.dp)) {
        AppText(title, color = tc().td, fontSize = 9.sp)
        FlowRow(horizontalArrangement = Arrangement.spacedBy(3.dp), verticalArrangement = Arrangement.spacedBy(3.dp)) {
            participants.forEach { participant ->
                AppButton(
                    participant.displayName,
                    { onSelect(participant.id) },
                    variant = if (participant.id == selectedId) ButtonVariant.Primary else ButtonVariant.Ghost,
                )
            }
        }
    }
}

private fun String.parseBatchParameters(): List<DiagramParameter> = split(';').mapNotNull { raw ->
    val text = raw.trim()
    if (text.isBlank()) return@mapNotNull null
    val split = text.indexOf('=')
    if (split < 0) DiagramParameter(value = text)
    else DiagramParameter(text.substring(0, split).trim(), text.substring(split + 1).trim())
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
