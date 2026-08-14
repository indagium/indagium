@file:OptIn(
    androidx.compose.foundation.ExperimentalFoundationApi::class,
    androidx.compose.ui.ExperimentalComposeUiApi::class,
)
@file:Suppress("MaxLineLength")

package com.indagium.ui

import androidx.compose.foundation.HorizontalScrollbar
import androidx.compose.foundation.Image
import androidx.compose.foundation.VerticalScrollbar
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.focusable
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.rememberScrollbarAdapter
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.input.pointer.*
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupProperties
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlin.math.roundToInt
import com.indagium.diagram.DiagramParticipant
import com.indagium.diagram.ManualDiagramEvidence
import com.indagium.diagram.ManualDiagramInteraction
import com.indagium.diagram.ManualDiagramMessageDefinition
import com.indagium.diagram.ManualMessageRepeatPolicy
import com.indagium.diagram.ManualInteractionAuthoring
import com.indagium.diagram.ManualMessageBulkAction
import com.indagium.diagram.ManualMessageFilter
import com.indagium.diagram.ManualMessageMatch
import com.indagium.diagram.ManualMessageQueueRow
import com.indagium.diagram.ManualMessageSort
import com.indagium.diagram.ManualMessageState
import com.indagium.diagram.ManualMessageStateKind
import com.indagium.diagram.ManualDiagramSeedConfiguration
import com.indagium.diagram.GuidedTargetPassState
import com.indagium.diagram.advanceGuidedTargetPass
import com.indagium.diagram.beginGuidedTargetPass
import com.indagium.diagram.guidedTargetContext
import com.indagium.diagram.guidedTargetRow
import com.indagium.diagram.setManualMessageTargetForOccurrences
import com.indagium.diagram.suggestManualTarget
import com.indagium.diagram.ManualRegenerationChangeKind
import com.indagium.diagram.ManualRegenerationReview
import com.indagium.diagram.ManualRegenerationRowDecision
import com.indagium.diagram.MessageKind
import com.indagium.diagram.SeqDiagramSpec
import com.indagium.diagram.applyManualMessageBulkAction
import com.indagium.diagram.buildManualMessageQueue
import com.indagium.diagram.matchManualMessage
import com.indagium.diagram.manualMessageDisplayTemplate
import com.indagium.diagram.manualMessageTemplate
import com.indagium.diagram.normalizeManualDocument
import com.indagium.diagram.selectManualQueueMessageIds
import com.indagium.diagram.acceptAllRegenerationRows
import com.indagium.diagram.rejectAllRegenerationRows
import com.indagium.diagram.withRowDecision
import com.indagium.model.LogEntry
import com.indagium.model.LogTab

private const val V2_MAX_CANVAS_DIM_DP = 200_000f
private val V2_SURFACE = Color(0xFFFCFBF8)
private val V2_CARD = Color(0xFFFFFFFF)
private val V2_TEXT = Color(0xFF2F3432)
private val V2_MUTED = Color(0xFF8A8A82)
private val V2_BORDER = Color(0xFFE5DED2)
private val V2_ACCENT = Color(0xFF0D756A)
private val V2_ACCENT_SOFT = Color(0xFFE2F0EB)
private val V2_AMBER = Color(0xFFC47712)
private val V2_AMBER_SOFT = Color(0xFFFFF0D8)
private val V2_HIDDEN = Color(0xFFA8A39A)

/**
 * Sequence diagram v2 is a separate shell around the same durable coordinator and message
 * authoring surface. The v1 workspace remains the compatibility editor; this route owns the
 * full-height Messages + Canvas comparison requested by the v2 design.
 */
@Composable
fun SeqDiagramWorkspaceV2(state: AppState, workspaceId: String) {
    if (state.seqDiagrams.activeWorkspaceId != workspaceId) {
        state.seqDiagrams.activateWorkspace(workspaceId)
    }
    val session = state.seqDiagrams.activeSession ?: return
    val request = session.request
    val offline = session.offlineRequest
    val tab = request?.let { state.tab(it.tabId) }
    val spec = request?.spec ?: offline?.spec ?: session.spec
    val readOnly = request == null || tab == null || state.seqDiagrams.libraryOpenReadOnly
    val sourceIndexBuiltAt = state.sourceIndex?.builtAt
    val tc = tc()

    LaunchedEffect(workspaceId, sourceIndexBuiltAt) {
        if (!readOnly && sourceIndexBuiltAt != null) {
            state.seqDiagrams.requestPreview(tab.id, spec)
        }
    }

    Column(Modifier.fillMaxSize().background(V2_SURFACE)) {
        V2WorkspaceHeader(state, session, spec, tab?.filename, tab?.logData?.size ?: 0, readOnly)

        Row(
            Modifier.weight(1f).fillMaxWidth().padding(horizontal = 14.dp),
            horizontalArrangement = Arrangement.spacedBy(0.dp),
        ) {
            Column(
                Modifier.weight(.30f).fillMaxHeight().padding(end = 10.dp),
            ) {
                V2MessageQueue(
                    state = state,
                    tab = tab,
                    spec = spec,
                    readOnly = readOnly,
                    onSpec = { state.seqDiagrams.updateSpec(it) },
                )
            }
            Box(Modifier.width(1.dp).fillMaxHeight().background(V2_BORDER))
            V2NativeCanvasPane(
                state = state,
                session = session,
                modifier = Modifier.weight(.70f).fillMaxHeight().padding(start = 10.dp),
            )
        }

        V2WorkspaceFooter(state, request, readOnly)
    }

    if (state.seqDiagrams.pendingCloseWorkspaceId == workspaceId) {
        Dialog(
            onDismissRequest = { state.seqDiagrams.cancelWorkspaceClose() },
            properties = DialogProperties(dismissOnClickOutside = false),
        ) {
            Column(
                Modifier.width(420.dp).background(tc.p, CORNER_SM)
                    .border(1.dp, tc.br, CORNER_SM).padding(18.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                AppText("Save diagram?", fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
                AppText("This v2 workspace has unsaved changes.", color = tc.td, fontSize = 11.sp)
                Row(
                    Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End),
                ) {
                    AppButton("Cancel", { state.seqDiagrams.cancelWorkspaceClose() }, variant = ButtonVariant.Ghost)
                    AppButton(
                        "Discard",
                        { state.seqDiagrams.closeWorkspace(workspaceId) },
                        variant = ButtonVariant.Ghost,
                        isDanger = true,
                    )
                    AppButton(
                        "Save",
                        { state.seqDiagrams.closeWorkspace(workspaceId, save = true) },
                        variant = ButtonVariant.Primary,
                        enabled = state.seqDiagrams.preview.diagramOrNull != null,
                    )
                }
            }
        }
    }
}

@Composable
private fun V2WorkspaceHeader(
    state: AppState,
    session: DiagramWorkspaceSession,
    spec: com.indagium.diagram.SeqDiagramSpec,
    filename: String?,
    rowCount: Int,
    readOnly: Boolean,
) {
    val sourceName = spec.sourceFile ?: filename ?: "current log"
    Row(
        Modifier.fillMaxWidth().height(68.dp).padding(horizontal = 18.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(5.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(9.dp)) {
                AppText(
                    spec.title.ifBlank { "Sequence diagram v2" },
                    V2_TEXT,
                    17.sp,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                V2Badge("V2")
            }
            AppText(
                "$sourceName · ${rangeSummary(spec.range)} · $rowCount rows",
                color = V2_MUTED,
                fontSize = 10.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        AppText("Scope: ${rangeSummary(spec.range)} ▾", color = V2_ACCENT, fontSize = 11.sp)
        CloseButton(onClick = { state.seqDiagrams.requestCloseWorkspace(session.id) })
    }
}

@Composable
private fun V2Badge(label: String) {
    AppText(
        label,
        color = V2_ACCENT,
        fontSize = 10.sp,
        fontWeight = FontWeight.Bold,
        modifier = Modifier.background(V2_ACCENT_SOFT, RoundedCornerShape(5.dp))
            .padding(horizontal = 7.dp, vertical = 3.dp),
    )
}

/**
 * The v2 queue is intentionally implemented here instead of wrapping the v1 inspector.  It is a
 * message-first surface: one durable message owns the row, its endpoint controls, its repeat
 * count, and its evidence disclosure.  The old authoring section remains available to v1 only.
 */
@Composable
private fun V2MessageQueue(
    state: AppState,
    tab: LogTab?,
    spec: SeqDiagramSpec,
    readOnly: Boolean,
    onSpec: (SeqDiagramSpec) -> Unit,
) {
    val document = spec.manualDocument
    val entries = tab?.logData.orEmpty()
    val diagram = state.seqDiagrams.preview.diagramOrNull
    val lifelines = remember(diagram, spec.participants, spec.components) {
        (diagram?.participants ?: emptyList())
            .ifEmpty { spec.participants }
            .map(DiagramParticipant::id)
            .distinct()
    }
    var filter by remember { mutableStateOf(ManualMessageFilter.ALL) }
    var sort by remember { mutableStateOf(ManualMessageSort.LOG_ORDER) }
    var query by remember { mutableStateOf("") }
    var selectedIds by remember { mutableStateOf<Set<String>>(emptySet()) }
    var selectionAnchor by remember { mutableStateOf<String?>(null) }
    var expandedId by remember { mutableStateOf<String?>(null) }
    var draftOpen by remember { mutableStateOf(false) }
    var regenerateOpen by remember { mutableStateOf(false) }
    var guidedPass by remember { mutableStateOf<GuidedTargetPassState?>(null) }

    LaunchedEffect(state.seqDiagrams.activeSession?.focusedManualInteractionId) {
        state.seqDiagrams.activeSession?.focusedManualInteractionId?.let { focused ->
            expandedId = focused
            selectedIds = setOf(focused)
            selectionAnchor = focused
        }
    }

    val allRows = remember(document) { buildManualMessageQueue(document).rows }
    val queue = buildManualMessageQueue(document, filter = filter, query = query, sort = sort)
    val needsTargetCount = allRows.count { it.state == ManualMessageState.NEEDS_TARGET }
    val editedCount = allRows.count { it.state == ManualMessageState.EDITED }
    val hiddenCount = allRows.count { it.state == ManualMessageState.HIDDEN }

    fun choose(rowId: String, additive: Boolean, range: Boolean) {
        val (next, nextAnchor) = selectManualQueueMessageIds(
            queue.rows.map(ManualMessageQueueRow::id),
            selectedIds,
            selectionAnchor,
            rowId,
            additive = additive,
            range = range,
        )
        selectedIds = next
        selectionAnchor = nextAnchor
        expandedId = rowId
        state.seqDiagrams.focusManualInteraction(rowId)
    }

    Column(
        Modifier.fillMaxSize().background(V2_CARD).border(1.dp, V2_BORDER, CORNER_SM),
    ) {
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            AppText("Messages", V2_TEXT, 15.sp, fontWeight = FontWeight.SemiBold)
            AppText(" ${allRows.size}", V2_MUTED, 13.sp)
            Spacer(Modifier.weight(1f))
            AppButton("Add +", { draftOpen = true }, variant = ButtonVariant.Ghost, enabled = !readOnly)
        }

        if (needsTargetCount > 0) {
            Row(
                Modifier.fillMaxWidth().padding(horizontal = 12.dp).background(V2_AMBER_SOFT, CORNER_SM)
                    .border(1.dp, V2_AMBER.copy(alpha = .32f), CORNER_SM).padding(10.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                V2CountDot(needsTargetCount.toString(), V2_AMBER)
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    AppText(
                        "$needsTargetCount messages need a target",
                        color = V2_AMBER,
                        fontSize = 11.sp,
                        fontWeight = FontWeight.SemiBold,
                    )
                    AppText("Resolve endpoints before drawing the final arrow.", V2_AMBER.copy(alpha = .8f), 9.sp)
                }
                AppButton(
                    "Fix these →",
                    {
                        guidedPass = beginGuidedTargetPass(document)
                    },
                    variant = ButtonVariant.Primary,
                    enabled = !readOnly,
                )
            }
        }

        Row(
            Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 9.dp),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            V2FilterChip("All ${allRows.size}", filter == ManualMessageFilter.ALL) { filter = ManualMessageFilter.ALL }
            V2FilterChip("Needs target $needsTargetCount", filter == ManualMessageFilter.NEEDS_TARGET) {
                filter = ManualMessageFilter.NEEDS_TARGET
            }
            V2FilterChip("Edited $editedCount", filter == ManualMessageFilter.EDITED) { filter = ManualMessageFilter.EDITED }
            V2FilterChip("Hidden $hiddenCount", filter == ManualMessageFilter.HIDDEN) { filter = ManualMessageFilter.HIDDEN }
        }

        Row(
            Modifier.fillMaxWidth().padding(horizontal = 12.dp).padding(bottom = 9.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(7.dp),
        ) {
            InlineField(
                query,
                { query = it },
                "Filter messages…",
                Modifier.weight(1f),
                fontSize = 11.sp,
                onClear = { query = "" },
            )
            AppButton(
                "${sortLabel(sort)} ▾",
                {
                    sort = when (sort) {
                        ManualMessageSort.LOG_ORDER -> ManualMessageSort.LIFELINE
                        ManualMessageSort.LIFELINE -> ManualMessageSort.OCCURRENCES
                        ManualMessageSort.OCCURRENCES -> ManualMessageSort.STATE
                        ManualMessageSort.STATE -> ManualMessageSort.LOG_ORDER
                    }
                },
                variant = ButtonVariant.Ghost,
            )
        }

        Box(Modifier.fillMaxWidth().height(1.dp).background(V2_BORDER))
        Column(Modifier.weight(1f).fillMaxWidth().verticalScroll(rememberScrollState())) {
            if (queue.rows.isEmpty()) {
                V2EmptyQueue(filter, query)
            } else {
                queue.rows.forEach { row ->
                    V2MessageRow(
                        row = row,
                        expanded = expandedId == row.id,
                        selected = row.id in selectedIds,
                        lifelines = lifelines,
                        entries = entries,
                        readOnly = readOnly,
                        onSelect = { additive, range -> choose(row.id, additive, range) },
                        onExpand = {
                            expandedId = if (expandedId == row.id) null else row.id
                            state.seqDiagrams.focusManualInteraction(row.id)
                        },
                        onEndpoint = { action ->
                            val result = applyManualMessageBulkAction(document, setOf(row.id), action)
                            if (result.applied) onSpec(spec.copy(manualDocument = result.document))
                        },
                        onLabel = { value -> onSpec(updateV2MessageLabel(spec, row.id, value)) },
                    )
                }
            }
        }

        Row(
            Modifier.fillMaxWidth().border(0.5.dp, V2_BORDER).padding(horizontal = 12.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            AppText("${allRows.size} messages · $needsTargetCount need a target", V2_MUTED, 10.sp)
            Spacer(Modifier.weight(1f))
            AppButton("Regenerate…", { regenerateOpen = true }, variant = ButtonVariant.Ghost, enabled = !readOnly)
        }
    }

    if (draftOpen) {
        V2DraftMessageDialog(
            lifelines = lifelines,
            entries = entries,
            onDismiss = { draftOpen = false },
            onCommit = { matchText, label, from, to, evidenceId ->
                val evidence = entries.firstOrNull { it.id == evidenceId }
                if (evidence == null || matchText.isBlank() || from.isBlank()) return@V2DraftMessageDialog
                if (matchManualMessage(ManualMessageMatch(textPattern = matchText), evidence.msg, evidence.tag) == null) return@V2DraftMessageDialog
                val normalized = normalizeManualDocument(document)
                val id = "manual-message:v2:" + System.nanoTime()
                val occurrenceId = "$id:occurrence"
                val kind = when {
                    to == null -> MessageKind.CALL
                    to == from -> MessageKind.SELF
                    else -> MessageKind.CALL
                }
                val interaction = ManualDiagramInteraction(
                    id = occurrenceId,
                    sourceEntryIds = setOf(evidence.id),
                    fromParticipantId = from,
                    toParticipantId = to,
                    operation = label.ifBlank { matchText },
                    label = label.ifBlank { null },
                    kind = kind,
                    order = normalized.interactions.maxOfOrNull { it.order }?.plus(1) ?: 0L,
                    authoring = ManualInteractionAuthoring.EDITED,
                    evidence = listOf(ManualDiagramEvidence(evidence.id, evidence.ts, evidence.level)),
                    matchText = evidence.msg,
                )
                val definition = ManualDiagramMessageDefinition(
                    id = id,
                    occurrenceIds = listOf(occurrenceId),
                    match = ManualMessageMatch(textPattern = matchText),
                    fromParticipantId = from,
                    toParticipantId = to,
                    labelTemplate = label.ifBlank { matchText },
                    kind = kind,
                    repeatPolicy = ManualMessageRepeatPolicy(),
                    state = if (to == null) ManualMessageStateKind.NEEDS_TARGET else ManualMessageStateKind.EDITED,
                    authoring = ManualInteractionAuthoring.EDITED,
                )
                onSpec(spec.copy(manualDocument = normalized.copy(
                    interactions = normalized.interactions + interaction,
                    messages = normalized.messages + definition,
                )))
                draftOpen = false
            },
        )
    }

    if (regenerateOpen || state.seqDiagrams.manualSeedReview != null) {
        V2RegenerationDialog(
            state = state,
            onDismiss = { regenerateOpen = false; state.seqDiagrams.cancelManualSeedReview() },
        )
    }
    guidedPass?.let { pass ->
        V2GuidedTargetDialog(
            state = state,
            spec = spec,
            entries = entries,
            lifelines = lifelines,
            pass = pass,
            onSpec = onSpec,
            onPass = { guidedPass = it },
            onDismiss = { guidedPass = null },
        )
    }
}

@Composable
private fun V2GuidedTargetDialog(
    state: AppState,
    spec: SeqDiagramSpec,
    entries: List<LogEntry>,
    lifelines: List<String>,
    pass: GuidedTargetPassState,
    onSpec: (SeqDiagramSpec) -> Unit,
    onPass: (GuidedTargetPassState?) -> Unit,
    onDismiss: () -> Unit,
) {
    val row = guidedTargetRow(spec.manualDocument, pass)
    if (row == null) {
        onDismiss()
        return
    }
    val choices = lifelines.take(9)
    val participants = spec.participants
    val suggestion = suggestManualTarget(row.representative, entries, participants)
    val context = guidedTargetContext(row, entries)
    var selected by remember(row.id, suggestion?.participantId) { mutableStateOf(suggestion?.participantId ?: choices.firstOrNull()) }
    var applyAll by remember(row.id) { mutableStateOf(true) }
    var newLifeline by remember(row.id) { mutableStateOf("") }
    val focusRequester = remember(row.id) { FocusRequester() }
    LaunchedEffect(row.id) { runCatching { focusRequester.requestFocus() } }
    fun resolve(target: String) {
        val nextDocument = setManualMessageTargetForOccurrences(
            spec.manualDocument,
            row.id,
            if (applyAll) row.interactionIds.toSet() else setOf(row.representative.id),
            target,
            if (target == row.fromParticipantId) MessageKind.SELF else MessageKind.CALL,
        )
        onSpec(spec.copy(manualDocument = nextDocument))
        onPass(advanceGuidedTargetPass(nextDocument, pass))
    }
    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(dismissOnClickOutside = false)) {
        Column(
            Modifier.width(500.dp).background(V2_CARD, CORNER_MD).border(1.dp, V2_BORDER, CORNER_MD).padding(17.dp)
                .focusRequester(focusRequester).focusable()
                .onPreviewKeyEvent { event ->
                    if (event.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
                    when (event.key) {
                        Key.Escape -> { onDismiss(); true }
                        Key.Enter -> { selected?.let(::resolve); true }
                        else -> {
                            val index = listOf(Key.One, Key.Two, Key.Three, Key.Four, Key.Five, Key.Six, Key.Seven, Key.Eight, Key.Nine).indexOf(event.key)
                            if (index >= 0 && index < choices.size) { selected = choices[index]; true } else false
                        }
                    }
                },
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                AppText("Fix targets", V2_TEXT, 16.sp, fontWeight = FontWeight.SemiBold)
                Spacer(Modifier.weight(1f))
                AppText("${pass.completedCount + 1} / ${pass.groupIds.size}", V2_ACCENT, 10.sp)
            }
            AppText("${row.fromParticipantId} → needs a target", V2_AMBER, 11.sp, fontWeight = FontWeight.SemiBold)
            AppText(manualMessageTemplate(row.representative) + " · ×${row.occurrenceCount}", V2_TEXT, 10.sp, maxLines = 2)
            if (suggestion != null) {
                AppText("Suggested: ${suggestion.participantId} · ${suggestion.reason}. Confirm below.", V2_ACCENT, 9.sp, maxLines = 2)
            }
            Column(Modifier.fillMaxWidth().background(V2_SURFACE, CORNER_SM).padding(7.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                context.take(3).forEach { entry ->
                    AppText("${entry.id}  ${entry.ts}  ${entry.tag}: ${entry.msg}", if (entry.id in row.sourceEntryIds) V2_ACCENT else V2_MUTED, 9.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(5.dp)) {
                choices.forEachIndexed { index, id ->
                    AppButton("${index + 1}  $id", { selected = id }, variant = if (selected == id) ButtonVariant.Primary else ButtonVariant.Ghost)
                }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                AppButton("Apply target", { selected?.let(::resolve) }, variant = ButtonVariant.Primary, enabled = selected != null)
                AppButton("Skip", { onPass(advanceGuidedTargetPass(spec.manualDocument, pass)) }, variant = ButtonVariant.Ghost)
                AppButton("Esc", onDismiss, variant = ButtonVariant.Ghost)
            }
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                InlineField(newLifeline, { newLifeline = it }, "new lifeline", Modifier.weight(1f), fontSize = 10.sp)
                AppButton(
                    "New lifeline",
                    {
                        val clean = newLifeline.trim()
                        if (clean.isNotEmpty() && lifelines.none { it == clean }) {
                            onSpec(spec.copy(
                                participants = spec.participants + DiagramParticipant(clean, clean, com.indagium.diagram.ParticipantKind.TAG),
                                lifelineOrder = (spec.lifelineOrder + clean).distinct(),
                            ))
                            resolve(clean)
                        }
                    },
                    variant = ButtonVariant.Secondary,
                    enabled = newLifeline.trim().isNotEmpty(),
                )
            }
            if (row.occurrenceCount > 1) {
                V2DialogCheck("Apply to all ×${row.occurrenceCount} occurrences", applyAll) { applyAll = !applyAll }
            }
            AppText("Confirm a suggestion · 1–9 choose · Esc close", V2_MUTED, 9.sp)
        }
    }
}

@Composable
private fun V2RegenerationDialog(
    state: AppState,
    onDismiss: () -> Unit,
) {
    val review = state.seqDiagrams.manualSeedReview
    var trace by remember { mutableStateOf(true) }
    var handoffs by remember { mutableStateOf(false) }
    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(dismissOnClickOutside = false)) {
        Column(
            Modifier.width(560.dp).background(V2_CARD, CORNER_MD).border(1.dp, V2_BORDER, CORNER_MD).padding(18.dp),
            verticalArrangement = Arrangement.spacedBy(9.dp),
        ) {
            AppText("Regenerate messages", V2_TEXT, 16.sp, fontWeight = FontWeight.SemiBold)
            if (review == null) {
                AppText(
                    "Build a review from the current evidence. Edited messages stay protected until you apply the review.",
                    V2_MUTED,
                    10.sp,
                    maxLines = 3,
                )
                V2DialogCheck("Source execution trace", trace) { trace = !trace }
                V2DialogCheck("Same-thread handoffs", handoffs) { handoffs = !handoffs }
                if (state.seqDiagrams.manualSeedBusy) {
                    AppText(state.seqDiagrams.manualSeedStatus ?: "Building review…", V2_ACCENT, 10.sp)
                }
                Row(horizontalArrangement = Arrangement.spacedBy(7.dp)) {
                    AppButton("Cancel", onDismiss, variant = ButtonVariant.Ghost)
                    AppButton(
                        "Build review",
                        {
                            state.seqDiagrams.applyManualSeed(
                                ManualDiagramSeedConfiguration(trace, handoffs),
                            )
                        },
                        variant = ButtonVariant.Primary,
                        enabled = !state.seqDiagrams.manualSeedBusy && (trace || handoffs),
                    )
                }
            } else {
                V2RegenerationReviewBody(state, review)
                Row(horizontalArrangement = Arrangement.spacedBy(7.dp)) {
                    AppButton("Cancel review", onDismiss, variant = ButtonVariant.Ghost)
                    if (review.rows.isNotEmpty()) {
                        AppButton("Apply review", { state.seqDiagrams.acceptManualSeedReview() }, variant = ButtonVariant.Primary)
                    }
                }
            }
        }
    }
}

@Composable
private fun V2DialogCheck(label: String, checked: Boolean, onToggle: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().clickable(onClick = onToggle).padding(vertical = 3.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        CompactCheckBox(checked = checked, onToggle = onToggle, accentColor = V2_ACCENT)
        AppText(label, V2_TEXT, 10.sp)
    }
}

@Composable
private fun V2RegenerationReviewBody(state: AppState, review: ManualRegenerationReview) {
    if (review.rows.isEmpty()) {
        AppText("No new, changed, or orphaned messages were found.", V2_MUTED, 10.sp)
        return
    }
    AppText(
        "${review.newCount} new · ${review.changedAutoCount} changed · ${review.noLongerInSourceCount} orphaned · ${review.editedKeptCount} edited kept",
        V2_ACCENT,
        10.sp,
        maxLines = 2,
    )
    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        AppButton("Accept all", { state.seqDiagrams.updateManualSeedReview(acceptAllRegenerationRows(review)) }, variant = ButtonVariant.Ghost)
        AppButton("Reject all", { state.seqDiagrams.updateManualSeedReview(rejectAllRegenerationRows(review)) }, variant = ButtonVariant.Ghost)
    }
    Column(Modifier.fillMaxWidth().heightIn(max = 260.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        review.rows.forEachIndexed { index, row ->
            val title = when (row.kind) {
                ManualRegenerationChangeKind.NEW -> "NEW"
                ManualRegenerationChangeKind.CHANGED_AUTO -> "CHANGED"
                ManualRegenerationChangeKind.NO_LONGER_IN_SOURCE -> "ORPHANED"
                ManualRegenerationChangeKind.EDITED_KEPT -> "EDITED · KEPT"
            }
            Row(
                Modifier.fillMaxWidth().background(if (row.kind == ManualRegenerationChangeKind.EDITED_KEPT) V2_ACCENT_SOFT else V2_SURFACE, CORNER_SM)
                    .border(.5.dp, V2_BORDER, CORNER_SM).padding(7.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(7.dp),
            ) {
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    AppText(title, if (row.kind == ManualRegenerationChangeKind.EDITED_KEPT) V2_ACCENT else V2_MUTED, 9.sp, fontWeight = FontWeight.SemiBold)
                    AppText(row.candidate?.label ?: row.existing?.label ?: "message", V2_TEXT, 10.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
                if (row.kind != ManualRegenerationChangeKind.EDITED_KEPT) {
                    AppButton("Keep", { state.seqDiagrams.updateManualSeedReview(withRowDecision(review, index, ManualRegenerationRowDecision.ACCEPT)) }, variant = ButtonVariant.Ghost)
                    AppButton("Drop", { state.seqDiagrams.updateManualSeedReview(withRowDecision(review, index, ManualRegenerationRowDecision.REJECT)) }, variant = ButtonVariant.Ghost)
                }
            }
        }
    }
}

@Composable
private fun V2MessageRow(
    row: ManualMessageQueueRow,
    expanded: Boolean,
    selected: Boolean,
    lifelines: List<String>,
    entries: List<LogEntry>,
    readOnly: Boolean,
    onSelect: (Boolean, Boolean) -> Unit,
    onExpand: () -> Unit,
    onEndpoint: (ManualMessageBulkAction) -> Unit,
    onLabel: (String) -> Unit,
) {
    var additive by remember(row.id) { mutableStateOf(false) }
    var range by remember(row.id) { mutableStateOf(false) }
    val stateColor = when (row.state) {
        ManualMessageState.NEEDS_TARGET -> V2_AMBER
        ManualMessageState.EDITED -> V2_ACCENT
        ManualMessageState.HIDDEN -> V2_HIDDEN
        ManualMessageState.AUTO -> V2_MUTED
    }
    val label = manualMessageDisplayTemplate(row)
    val match = row.message?.match?.textPattern ?: label
    val endpointFrom = row.message?.fromParticipantId ?: row.fromParticipantId
    val endpointTo = row.message?.toParticipantId ?: row.toParticipantId
    Column(
        Modifier.fillMaxWidth()
            .background(if (selected) V2_ACCENT_SOFT.copy(alpha = .7f) else Color.Transparent)
            .border(if (selected) 1.dp else .5.dp, if (selected) V2_ACCENT.copy(alpha = .55f) else V2_BORDER)
            .onPointerEvent(PointerEventType.Press) { event ->
                additive = event.keyboardModifiers.isCtrlPressed || event.keyboardModifiers.isMetaPressed
                range = event.keyboardModifiers.isShiftPressed
            }
            .clickable {
                onSelect(additive, range)
                additive = false
                range = false
            }
            .padding(horizontal = 11.dp, vertical = 9.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.Top, horizontalArrangement = Arrangement.spacedBy(7.dp)) {
            CompactCheckBox(checked = selected, onToggle = { onSelect(additive, range) }, accentColor = V2_ACCENT)
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    AppText(
                        label,
                        V2_TEXT,
                        11.sp,
                        fontWeight = FontWeight.Medium,
                        modifier = Modifier.weight(1f),
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                    if (row.occurrenceCount > 1) AppText("×${row.occurrenceCount}", V2_MUTED, 10.sp)
                }
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(5.dp)) {
                    V2EndpointPicker(endpointFrom, lifelines, false, enabled = !readOnly) { onEndpoint(ManualMessageBulkAction.SetSource(it)) }
                    AppText("→", V2_MUTED, 11.sp)
                    V2EndpointPicker(endpointTo ?: "set target", lifelines, true, enabled = !readOnly) {
                        onEndpoint(ManualMessageBulkAction.SetTarget(it.ifBlank { null }))
                    }
                    AppText(stateLabel(row.state), stateColor, 9.sp, fontWeight = FontWeight.Medium)
                    Spacer(Modifier.weight(1f))
                    AppButton(if (expanded) "Hide evidence" else "Evidence", onExpand, variant = ButtonVariant.Ghost)
                }
            }
        }
        if (row.hidden) {
            AppText("Hidden from canvas · evidence retained", V2_HIDDEN, 9.sp, modifier = Modifier.padding(start = 27.dp))
        }
        if (expanded) {
            Column(
                Modifier.fillMaxWidth().padding(start = 27.dp).background(V2_SURFACE, CORNER_SM)
                    .border(.5.dp, V2_BORDER, CORNER_SM).padding(9.dp),
                verticalArrangement = Arrangement.spacedBy(5.dp),
            ) {
                AppText("Message", V2_MUTED, 9.sp, fontWeight = FontWeight.SemiBold)
                if (!readOnly) InlineField(row.message?.labelTemplate ?: row.label, onLabel, "Label", Modifier.fillMaxWidth(), 10.sp)
                else AppText(row.message?.labelTemplate ?: row.label, V2_TEXT, 10.sp, maxLines = 2)
                AppText("Match · $match", V2_MUTED, 9.sp, maxLines = 2, overflow = TextOverflow.Ellipsis)
                AppText("Evidence · ${row.sourceEntryIds.sorted().joinToString(", ")}", V2_MUTED, 9.sp)
                entries.filter { it.id in row.sourceEntryIds }.take(3).forEach { evidence ->
                    AppText(
                        "#${evidence.id}  ${evidence.ts}  ${evidence.tag}: ${evidence.msg}",
                        V2_TEXT,
                        9.sp,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                if (row.sourceEntryIds.none { id -> entries.any { it.id == id } }) {
                    AppText("Source row unavailable; durable evidence is retained.", V2_MUTED, 9.sp)
                }
            }
        }
    }
}

@Composable
private fun V2FilterChip(label: String, selected: Boolean, onClick: () -> Unit) {
    Box(
        Modifier.background(if (selected) V2_ACCENT_SOFT else Color.Transparent, RoundedCornerShape(6.dp))
            .border(.7.dp, if (selected) V2_ACCENT.copy(alpha = .35f) else V2_BORDER, RoundedCornerShape(6.dp))
            .clickable(onClick = onClick).padding(horizontal = 8.dp, vertical = 5.dp),
    ) {
        AppText(label, if (selected) V2_ACCENT else V2_MUTED, 9.sp, fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal)
    }
}

@Composable
private fun V2CountDot(value: String, color: Color) {
    Box(Modifier.size(20.dp).background(color, RoundedCornerShape(50)), contentAlignment = Alignment.Center) {
        AppText(value, Color.White, 9.sp, fontWeight = FontWeight.Bold)
    }
}

@Composable
private fun V2EndpointPicker(
    selected: String,
    lifelines: List<String>,
    allowNeedsTarget: Boolean,
    enabled: Boolean = true,
    onSelect: (String) -> Unit,
) {
    var expanded by remember(selected) { mutableStateOf(false) }
    var anchorHeight by remember { mutableStateOf(0) }
    Box(Modifier.onSizeChanged { anchorHeight = it.height }) {
        AppButton(
            if (selected == "set target") "set target ▾" else "$selected ▾",
            { expanded = !expanded },
            variant = if (selected == "set target") ButtonVariant.Secondary else ButtonVariant.Ghost,
            horizontalPadding = 7.dp,
            enabled = enabled,
        )
        if (expanded) {
            Popup(
                alignment = Alignment.TopStart,
                offset = IntOffset(0, anchorHeight + 3),
                onDismissRequest = { expanded = false },
                properties = PopupProperties(focusable = true),
            ) {
                Column(
                    Modifier.width(170.dp).background(V2_CARD, CORNER_SM).border(1.dp, V2_BORDER, CORNER_SM).padding(4.dp),
                    verticalArrangement = Arrangement.spacedBy(2.dp),
                ) {
                    if (allowNeedsTarget) AppButton("Needs target", { expanded = false; onSelect("") }, variant = ButtonVariant.Ghost, modifier = Modifier.fillMaxWidth())
                    lifelines.forEach { id ->
                        AppButton(id, { expanded = false; onSelect(id) }, variant = ButtonVariant.Ghost, modifier = Modifier.fillMaxWidth())
                    }
                }
            }
        }
    }
}

private fun sortLabel(sort: ManualMessageSort): String = when (sort) {
    ManualMessageSort.LOG_ORDER -> "Log order"
    ManualMessageSort.LIFELINE -> "Lifeline"
    ManualMessageSort.OCCURRENCES -> "Occurrences"
    ManualMessageSort.STATE -> "State"
}

private fun stateLabel(state: ManualMessageState): String = when (state) {
    ManualMessageState.NEEDS_TARGET -> "NEEDS TARGET"
    ManualMessageState.EDITED -> "EDITED"
    ManualMessageState.HIDDEN -> "HIDDEN"
    ManualMessageState.AUTO -> "AUTO"
}

@Composable
private fun V2EmptyQueue(filter: ManualMessageFilter, query: String) {
    Column(Modifier.fillMaxWidth().padding(22.dp), verticalArrangement = Arrangement.spacedBy(5.dp)) {
        AppText(if (query.isNotBlank()) "No messages match this filter." else "No messages in this view.", V2_TEXT, 11.sp)
        AppText("${sortLabel(ManualMessageSort.LOG_ORDER)} · ${filter.name.lowercase().replace('_', ' ')}", V2_MUTED, 9.sp)
    }
}

@Composable
private fun V2DraftMessageDialog(
    lifelines: List<String>,
    entries: List<LogEntry>,
    onDismiss: () -> Unit,
    onCommit: (String, String, String, String?, Int) -> Unit,
) {
    var match by remember { mutableStateOf("") }
    var label by remember { mutableStateOf("") }
    var from by remember(lifelines) { mutableStateOf(lifelines.firstOrNull().orEmpty()) }
    var to by remember { mutableStateOf<String?>(null) }
    var evidenceId by remember { mutableStateOf<Int?>(entries.firstOrNull()?.id) }
    var error by remember { mutableStateOf<String?>(null) }
    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(dismissOnClickOutside = false)) {
        Column(
            Modifier.width(520.dp).background(V2_CARD, CORNER_SM).border(1.dp, V2_BORDER, CORNER_SM).padding(18.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    AppText("Add message", V2_TEXT, 16.sp, fontWeight = FontWeight.SemiBold)
                    AppText("A draft is committed only with a matching evidence row.", V2_MUTED, 10.sp)
                }
                CloseButton(onDismiss)
            }
            InlineField(match, { match = it }, "Match pattern from log text", Modifier.fillMaxWidth(), 11.sp)
            InlineField(label, { label = it }, "Label shown on canvas (optional)", Modifier.fillMaxWidth(), 11.sp)
            Row(horizontalArrangement = Arrangement.spacedBy(7.dp), verticalAlignment = Alignment.CenterVertically) {
                V2EndpointPicker(from, lifelines, false) { from = it }
                AppText("→", V2_MUTED, 11.sp)
                V2EndpointPicker(to ?: "set target", lifelines, true) { to = it.ifBlank { null } }
            }
            AppText("Choose evidence", V2_MUTED, 10.sp, fontWeight = FontWeight.SemiBold)
            Column(
                Modifier.fillMaxWidth().height(150.dp).verticalScroll(rememberScrollState())
                    .border(.7.dp, V2_BORDER, CORNER_SM).padding(5.dp),
            ) {
                entries.take(40).forEach { entry ->
                    Row(
                        Modifier.fillMaxWidth().background(if (entry.id == evidenceId) V2_ACCENT_SOFT else Color.Transparent, CORNER_SM)
                            .clickable { evidenceId = entry.id }.padding(6.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        AppText("#${entry.id}", V2_MUTED, 9.sp, modifier = Modifier.width(46.dp))
                        AppText("${entry.tag}: ${entry.msg}", V2_TEXT, 9.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                }
            }
            error?.let { AppText(it, V2_AMBER, 10.sp, maxLines = 2) }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End)) {
                AppButton("Cancel", onDismiss, variant = ButtonVariant.Ghost)
                AppButton(
                    "Add message",
                    {
                        val evidence = entries.firstOrNull { it.id == evidenceId }
                        error = when {
                            match.isBlank() -> "Enter a match pattern."
                            evidence == null -> "Select one evidence row."
                            from.isBlank() -> "Choose a source lifeline."
                            matchManualMessage(ManualMessageMatch(textPattern = match), evidence.msg, evidence.tag) == null -> "Match does not exactly cover the selected evidence row."
                            else -> null
                        }
                        if (error == null && evidenceId != null) onCommit(match, label, from, to, evidenceId!!)
                    },
                    variant = ButtonVariant.Primary,
                )
            }
        }
    }
}

private fun updateV2MessageLabel(spec: SeqDiagramSpec, rowId: String, value: String): SeqDiagramSpec {
    val document = normalizeManualDocument(spec.manualDocument)
    val definition = document.messages.firstOrNull { it.id == rowId }
    if (definition != null) {
        val updated = document.messages.map { message ->
            if (message.id == rowId) message.copy(
                labelTemplate = value,
                state = ManualMessageStateKind.EDITED,
                authoring = ManualInteractionAuthoring.EDITED,
            ) else message
        }
        return spec.copy(manualDocument = document.copy(messages = updated))
    }
    val row = buildManualMessageQueue(document).rows.firstOrNull { it.id == rowId } ?: return spec
    val ids = row.interactionIds.toSet()
    return spec.copy(manualDocument = document.copy(interactions = document.interactions.map { interaction ->
        if (interaction.id in ids) interaction.copy(label = value, authoring = ManualInteractionAuthoring.EDITED) else interaction
    }))
}

@Composable
private fun V2NativeCanvasPane(
    state: AppState,
    session: DiagramWorkspaceSession,
    modifier: Modifier,
) {
    val tc = tc()
    val diagram = state.seqDiagrams.preview.diagramOrNull
    val vertical = rememberScrollState()
    val horizontal = rememberScrollState()
    val density = LocalDensity.current.density
    val participants = diagram?.participants.orEmpty()
    val allMessages = diagram?.messages.orEmpty()
    // Compose Desktop rejects children above its platform constraint ceiling. Large logs can
    // produce thousands of rendered rows, so keep the interactive canvas bounded while the
    // queue/footer still report the complete source coverage.
    val maxCanvasMessages = ((V2_MAX_CANVAS_DIM_DP - 170f) / 72f).toInt().coerceAtLeast(1)
    val messages = allMessages.take(maxCanvasMessages)
    val contentWidth = maxOf(1100f, 150f + participants.size.coerceAtLeast(1) * 300f)
    val contentHeight = maxOf(540f, 170f + messages.size * 72f)
    val xFor = { index: Int -> 95f + index.coerceIn(0, participants.lastIndex.coerceAtLeast(0)) * 300f }
    val yFor = { index: Int -> 138f + index * 72f }

    Column(modifier.background(V2_CARD, CORNER_SM).border(1.dp, V2_BORDER, CORNER_SM)) {
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            AppText("Canvas", V2_TEXT, 12.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
            V2ZoomControls(session.zoom) {
                state.seqDiagrams.updateViewport(zoom = it, mode = DiagramZoomMode.MANUAL)
            }
            SegmentedControl(
                listOf("Fit", "Fit width", "Reset"),
                selectedIndices = when (session.zoomMode) {
                    DiagramZoomMode.FIT -> setOf(0)
                    DiagramZoomMode.FIT_WIDTH -> setOf(1)
                    DiagramZoomMode.MANUAL -> emptySet()
                },
                onToggle = { index ->
                    when (index) {
                        0 -> state.seqDiagrams.updateViewport(mode = DiagramZoomMode.FIT)
                        1 -> state.seqDiagrams.updateViewport(mode = DiagramZoomMode.FIT_WIDTH)
                        else -> state.seqDiagrams.updateViewport(zoom = 1f, mode = DiagramZoomMode.MANUAL)
                    }
                },
            )
        }
        Box(Modifier.fillMaxWidth().height(1.dp).background(V2_BORDER))

        if (diagram == null) {
            V2CenteredHint("No rendered messages yet.", V2_MUTED)
        } else {
            BoxWithConstraints(Modifier.weight(1f).fillMaxWidth()) {
                if (maxWidth.value <= 0f || maxHeight.value <= 0f) {
                    V2CenteredHint("Preparing canvas…", V2_MUTED)
                } else {
                    val zoom = minOf(
                        session.zoom.coerceIn(.35f, 2.5f),
                        V2_MAX_CANVAS_DIM_DP / contentHeight.coerceAtLeast(1f),
                    )
                    LaunchedEffect(maxWidth, maxHeight, contentWidth, contentHeight, session.zoomMode) {
                        if (session.zoomMode != DiagramZoomMode.MANUAL) {
                            val fit = minOf(
                                (maxWidth.value - 24f) / contentWidth,
                                (maxHeight.value - 24f) / contentHeight,
                            ).coerceIn(.35f, 2.5f)
                            val fitWidth = ((maxWidth.value - 24f) / contentWidth).coerceIn(.35f, 2.5f)
                            state.seqDiagrams.updateViewport(
                                zoom = if (session.zoomMode == DiagramZoomMode.FIT) fit else fitWidth,
                            )
                        }
                    }
                    Box(Modifier.fillMaxSize().verticalScroll(vertical).horizontalScroll(horizontal)) {
                        Box(
                            Modifier.width((contentWidth * zoom).dp).height((contentHeight * zoom).dp),
                        ) {
                        Canvas(Modifier.fillMaxSize()) {
                            // Compose overlays use dp while DrawScope uses px. Scale the entire
                            // native canvas once so pills, lifelines, arrows, and overlay labels
                            // share the same geometry on Retina and non-Retina displays.
                            scale(density) {
                            val sx = { x: Float -> x * zoom }
                            val sy = { y: Float -> y * zoom }
                            val lineColor = Color(0xFFB9B7AF)
                            val arrowColor = Color(0xFF4B504D)
                            val dash = PathEffect.dashPathEffect(floatArrayOf(4f * zoom, 5f * zoom), 0f)

                            participants.forEachIndexed { index, participant ->
                                val x = sx(xFor(index))
                                val focusedLifeline = messages.any { message ->
                                    val identity = message.originKeys.firstNotNullOfOrNull { it.manualInteractionId } ?: message.manualGroupKey
                                    identity == session.focusedManualInteractionId &&
                                        (message.fromIdx == index || message.toIdx == index)
                                }
                                drawRoundRect(
                                    color = if (focusedLifeline) V2_ACCENT_SOFT else Color(0xFFF0EDE7),
                                    topLeft = Offset(x - 72f * zoom, 24f * zoom),
                                    size = androidx.compose.ui.geometry.Size(144f * zoom, 34f * zoom),
                                    cornerRadius = androidx.compose.ui.geometry.CornerRadius(8f * zoom),
                                )
                                drawRoundRect(
                                    color = if (focusedLifeline) V2_ACCENT else V2_BORDER,
                                    topLeft = Offset(x - 72f * zoom, 24f * zoom),
                                    size = androidx.compose.ui.geometry.Size(144f * zoom, 34f * zoom),
                                    cornerRadius = androidx.compose.ui.geometry.CornerRadius(8f * zoom),
                                    style = Stroke(width = 1.5f * zoom),
                                )
                                drawLine(
                                    lineColor,
                                    Offset(x, 70f * zoom),
                                    Offset(x, contentHeight * zoom - 22f * zoom),
                                    strokeWidth = 1f * zoom,
                                    pathEffect = dash,
                                )
                            }

                            messages.forEachIndexed { index, message ->
                                val y = sy(yFor(index))
                                val from = sx(xFor(message.fromIdx))
                                val to = sx(xFor(message.toIdx))
                                val focused = message.originKeys.any { it.manualInteractionId == session.focusedManualInteractionId } ||
                                    message.manualGroupKey == session.focusedManualInteractionId
                                val color = if (focused) V2_ACCENT else if (message.targetless) V2_AMBER else arrowColor
                                if (message.kind == MessageKind.SELF) {
                                    val path = Path().apply {
                                        moveTo(from, y)
                                        lineTo(from + 34f * zoom, y)
                                        lineTo(from + 34f * zoom, y + 28f * zoom)
                                        lineTo(from, y + 28f * zoom)
                                    }
                                    drawPath(path, color, style = Stroke(width = 1.5f * zoom))
                                    drawLine(color, Offset(from, y + 28f * zoom), Offset(from + 7f * zoom, y + 28f * zoom), strokeWidth = 1.5f * zoom)
                                } else {
                                    val end = if (message.targetless) from + 78f * zoom else to
                                    drawLine(
                                        color,
                                        Offset(from, y),
                                        Offset(end, y),
                                        strokeWidth = if (focused) 2.2f * zoom else 1.35f * zoom,
                                        pathEffect = if (message.targetless) dash else null,
                                        cap = StrokeCap.Round,
                                    )
                                    if (!message.targetless) {
                                        val direction = if (end >= from) 1f else -1f
                                        val head = Path().apply {
                                            moveTo(end, y)
                                            lineTo(end - direction * 9f * zoom, y - 5f * zoom)
                                            lineTo(end - direction * 9f * zoom, y + 5f * zoom)
                                            close()
                                        }
                                        drawPath(head, color)
                                    }
                                }
                            }
                            }
                        }

                        participants.forEachIndexed { index, participant ->
                            AppText(
                                v2ParticipantLabel(participant),
                                V2_TEXT,
                                10.sp,
                                fontWeight = FontWeight.Medium,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                modifier = Modifier.offset((xFor(index) * zoom - 62f * zoom).dp, 32.dp)
                                    .width(124.dp),
                            )
                        }
                        messages.forEachIndexed { index, message ->
                            val start = minOf(xFor(message.fromIdx), xFor(message.toIdx))
                            val end = maxOf(xFor(message.fromIdx), xFor(message.toIdx))
                            val labelWidth = maxOf(160f, end - start - 12f)
                            val identity = message.originKeys.firstNotNullOfOrNull { it.manualInteractionId } ?: message.manualGroupKey
                            AppText(
                                message.label + if (message.repeatCount > 1) "  ×${message.repeatCount}" else "",
                                if (message.targetless) V2_AMBER else V2_TEXT,
                                10.sp,
                                maxLines = 2,
                                overflow = TextOverflow.Ellipsis,
                                modifier = Modifier.offset((start * zoom + 8f * zoom).dp, (yFor(index) * zoom - 21f * zoom).dp)
                                    .width((labelWidth * zoom).dp)
                                    .combinedClickable(
                                        onClick = { if (identity != null) state.seqDiagrams.focusManualInteraction(identity) },
                                        onDoubleClick = { if (identity != null) state.seqDiagrams.focusManualInteraction(identity) },
                                        onLongClick = null,
                                    ),
                            )
                        }
                    }
                }
            }
            Row(
                Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 7.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                AppText(
                    "${messages.size} shown · ${diagram.scannedEntries} scanned · ${participants.size} lifelines",
                    V2_MUTED,
                    9.sp,
                )
                Spacer(Modifier.weight(1f))
                if (messages.any { it.targetless }) AppText("dashed stubs need a target", V2_AMBER, 9.sp)
            }
        }
    }
}

}

private fun v2ParticipantLabel(participant: DiagramParticipant): String =
    participant.alias?.trim()?.takeUnless { it.isNullOrEmpty() }
        ?: participant.label.trim().takeUnless { it.isEmpty() }
        ?: participant.id.substringAfterLast('.').ifBlank { "Lifeline" }

@Composable
private fun V2LegacyRasterCanvasPane(
    state: AppState,
    session: DiagramWorkspaceSession,
    modifier: Modifier,
) {
    val tc = tc()
    val theme = tc.toDiagramTheme()
    val diagram = state.seqDiagrams.preview.diagramOrNull
    val display by produceState<DiagramDisplay?>(initialValue = null, key1 = diagram, key2 = theme) {
        value = withContext(Dispatchers.Default) {
            diagram?.let { DiagramRenderCache.display(it, theme) }
        }
    }
    val vertical = rememberScrollState()
    val horizontal = rememberScrollState()
    val density = LocalDensity.current.density

    Column(modifier.background(V2_CARD, CORNER_SM).border(1.dp, V2_BORDER, CORNER_SM)) {
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 10.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(7.dp),
        ) {
            AppText("Canvas", fontSize = 12.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
            V2ZoomControls(session.zoom) {
                state.seqDiagrams.updateViewport(zoom = it, mode = DiagramZoomMode.MANUAL)
            }
            SegmentedControl(
                listOf("Fit", "Fit width", "Reset"),
                selectedIndices = when (session.zoomMode) {
                    DiagramZoomMode.FIT -> setOf(0)
                    DiagramZoomMode.FIT_WIDTH -> setOf(1)
                    DiagramZoomMode.MANUAL -> emptySet()
                },
                onToggle = { index ->
                    when (index) {
                        0 -> state.seqDiagrams.updateViewport(mode = DiagramZoomMode.FIT)
                        1 -> state.seqDiagrams.updateViewport(mode = DiagramZoomMode.FIT_WIDTH)
                        else -> {
                            state.seqDiagrams.updateViewport(zoom = 1f, mode = DiagramZoomMode.MANUAL)
                            vertical.dispatchRawDelta(-vertical.value.toFloat())
                            horizontal.dispatchRawDelta(-horizontal.value.toFloat())
                        }
                    }
                },
            )
        }
        Box(Modifier.fillMaxWidth().height(1.dp).background(V2_BORDER))

        when {
            diagram == null && state.seqDiagrams.preview is DiagramPreviewState.Computing ->
                V2CenteredHint("Building…", tc.td)
            diagram == null && state.seqDiagrams.preview is DiagramPreviewState.Failed ->
                V2CenteredHint((state.seqDiagrams.preview as DiagramPreviewState.Failed).message, DANGER_RED)
            diagram == null -> V2CenteredHint("No rendered messages yet.", tc.td)
            display == null -> V2CenteredHint("Rendering…", tc.td)
            else -> {
                val rendered = display!!.rendered
                val bitmap = display!!.bitmap
                BoxWithConstraints(Modifier.weight(1f).fillMaxWidth()) {
                    val imageWidth = rendered.widthPx / rendered.scale
                    val imageHeight = rendered.heightPx / rendered.scale
                    val effectiveZoom = minOf(
                        session.zoom,
                        V2_MAX_CANVAS_DIM_DP / maxOf(imageWidth, imageHeight).coerceAtLeast(1f),
                    )
                    LaunchedEffect(rendered, maxWidth, maxHeight, session.zoomMode) {
                        if (maxWidth.value <= 0f || maxHeight.value <= 0f) return@LaunchedEffect
                        val fit = minOf(maxWidth.value / imageWidth, maxHeight.value / imageHeight)
                            .coerceIn(.15f, 1.5f)
                        val fitWidth = (maxWidth.value / imageWidth).coerceIn(.15f, 2.5f)
                        val target = when (session.zoomMode) {
                            DiagramZoomMode.FIT -> fit
                            DiagramZoomMode.FIT_WIDTH -> fitWidth
                            DiagramZoomMode.MANUAL -> null
                        }
                        if (target != null && target != session.zoom) {
                            state.seqDiagrams.updateViewport(zoom = target)
                        }
                    }
                    Box(Modifier.fillMaxSize()) {
                        Box(
                            Modifier.fillMaxSize()
                                .verticalScroll(vertical)
                                .horizontalScroll(horizontal)
                                .pointerInput(rendered, effectiveZoom, density) {
                                    awaitPointerEventScope {
                                        var endpointDrag: CanvasEndpointDragTarget? = null
                                        var pendingClick = false
                                        var downPosition = Offset.Zero
                                        var lastClickMillis = 0L
                                        var lastClickRowId: String? = null
                                        while (true) {
                                            val event = awaitPointerEvent(PointerEventPass.Initial)
                                            val change = event.changes.firstOrNull() ?: continue
                                            when (event.type) {
                                                PointerEventType.Press -> if (event.buttons.isPrimaryPressed) {
                                                    downPosition = change.position
                                                    endpointDrag = if (session.request != null && !state.seqDiagrams.libraryOpenReadOnly) {
                                                        resolveCanvasEndpointDragTarget(
                                                            rendered, change.position, horizontal.value.toFloat(),
                                                            vertical.value.toFloat(), effectiveZoom, density,
                                                        )
                                                    } else null
                                                    pendingClick = endpointDrag == null
                                                    if (endpointDrag != null) change.consume()
                                                }
                                                PointerEventType.Move -> {
                                                    if (endpointDrag != null) {
                                                        // The write is intentionally deferred until release. This gives
                                                        // endpoint dragging a cancel path without mutating evidence mid-gesture.
                                                        change.consume()
                                                    } else if (pendingClick &&
                                                        (change.position - downPosition).getDistance() > 8f
                                                    ) {
                                                        pendingClick = false
                                                    }
                                                }
                                                PointerEventType.Release -> {
                                                    val drag = endpointDrag
                                                    if (drag != null) {
                                                        val imageX = ((change.position.x + horizontal.value) / density / effectiveZoom * rendered.scale).roundToInt()
                                                        val participantIndex = nearestCanvasParticipantIndex(rendered, imageX)
                                                        val targetId = participantIndex?.let { diagram.participants.getOrNull(it)?.id }
                                                        val rowId = manualQueueRowIdentity(
                                                            session.spec.manualDocument,
                                                            interactionId = drag.hit.manualInteractionId,
                                                            groupKey = drag.hit.groupKey,
                                                        )
                                                        if (targetId != null && rowId != null) {
                                                            val action = if (drag.side == CanvasEndpointSide.SOURCE) {
                                                                ManualMessageBulkAction.SetSource(targetId)
                                                            } else {
                                                                ManualMessageBulkAction.SetTarget(targetId)
                                                            }
                                                            val result = applyManualMessageBulkAction(
                                                                session.spec.manualDocument,
                                                                setOf(rowId),
                                                                action,
                                                            )
                                                            if (result.applied) {
                                                                state.seqDiagrams.updateSpec(
                                                                    session.spec.copy(manualDocument = result.document),
                                                                )
                                                            }
                                                        }
                                                        change.consume()
                                                    } else if (pendingClick) {
                                                        val hit = resolveCanvasClickHit(
                                                            rendered, change.position, horizontal.value.toFloat(),
                                                            vertical.value.toFloat(), effectiveZoom, density,
                                                        )
                                                        val rowId = hit?.let {
                                                            manualQueueRowIdentity(
                                                                session.spec.manualDocument,
                                                                interactionId = it.manualInteractionId,
                                                                groupKey = it.groupKey,
                                                            )
                                                        }
                                                        if (rowId != null) {
                                                            val now = System.currentTimeMillis()
                                                            val doubleClick = now - lastClickMillis <= 340L && lastClickRowId == rowId
                                                            lastClickMillis = now
                                                            lastClickRowId = rowId
                                                            // A double click expands the queue row; its inline label
                                                            // field is the separate label-editing surface.
                                                            state.seqDiagrams.focusManualInteraction(rowId)
                                                            if (doubleClick) state.seqDiagrams.focusManualInteraction(rowId)
                                                        }
                                                    }
                                                    endpointDrag = null
                                                    pendingClick = false
                                                }
                                                else -> Unit
                                            }
                                        }
                                    }
                                },
                        ) {
                            Image(
                                bitmap = bitmap,
                                contentDescription = "Sequence diagram v2 canvas preview",
                                modifier = Modifier
                                    .width((imageWidth * effectiveZoom).dp)
                                    .height((imageHeight * effectiveZoom).dp),
                            )
                            session.focusedManualInteractionId?.let { focusedId ->
                                rendered.hits.firstOrNull { hit ->
                                    manualQueueRowIdentity(
                                        session.spec.manualDocument,
                                        interactionId = hit.manualInteractionId,
                                        groupKey = hit.groupKey,
                                    ) == focusedId
                                }?.let { hit ->
                                    canvasHitOverlayBounds(hit, rendered, effectiveZoom)?.let { bounds ->
                                        Box(
                                            Modifier.offset(bounds.xDp.dp, bounds.yDp.dp)
                                                .size(bounds.widthDp.dp, bounds.heightDp.dp)
                                                .background(tc.ac.copy(alpha = .16f), RoundedCornerShape(4.dp))
                                                .border(1.dp, tc.ac.copy(alpha = .7f), RoundedCornerShape(4.dp)),
                                        )
                                    }
                                }
                            }
                        }
                        VerticalScrollbar(
                            adapter = rememberScrollbarAdapter(vertical),
                            modifier = Modifier.align(Alignment.CenterEnd).fillMaxHeight()
                                .padding(vertical = 4.dp).width(7.dp),
                            style = appScrollbarStyle(tc),
                        )
                        HorizontalScrollbar(
                            adapter = rememberScrollbarAdapter(horizontal),
                            modifier = Modifier.align(Alignment.BottomStart).fillMaxWidth()
                                .height(7.dp).padding(horizontal = 4.dp),
                            style = appScrollbarStyle(tc),
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun V2ZoomControls(zoom: Float, onZoom: (Float) -> Unit) {
    val tc = tc()
    Row(
        Modifier.border(0.5.dp, tc.br, RoundedCornerShape(6.dp)),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        AppButton("−", { onZoom((zoom - .15f).coerceAtLeast(.15f)) }, variant = ButtonVariant.Ghost)
        AppText(
            (zoom * 100).roundToInt().toString() + "%",
            color = tc.tx,
            fontSize = 11.sp,
            modifier = Modifier.padding(horizontal = 7.dp),
        )
        AppButton("+", { onZoom((zoom + .15f).coerceAtMost(2.5f)) }, variant = ButtonVariant.Ghost)
    }
}

@Composable
private fun V2WorkspaceFooter(
    state: AppState,
    request: SeqDiagramRequest?,
    readOnly: Boolean,
) {
    val tc = tc()
    val ready = state.seqDiagrams.preview.diagramOrNull?.messages?.isNotEmpty() == true

    fun attach(linked: Boolean) {
        val req = state.seqDiagrams.request ?: request ?: return
        val saved = if (req.libraryItemId == null) state.seqDiagrams.saveDraft() else null
        val libraryId = req.libraryItemId ?: saved?.id ?: return
        val blockId = if (linked) {
            state.seqDiagrams.attachLibraryLink(req.tabId, libraryId)
        } else {
            state.seqDiagrams.attachLibrarySnapshot(req.tabId, libraryId)
        }
        if (blockId != null) state.annotationVisible = true
    }

    Row(
        Modifier.fillMaxWidth().background(V2_CARD).border(1.dp, V2_BORDER).padding(horizontal = 4.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        AppButton("Save draft", { state.seqDiagrams.saveDraft() }, enabled = ready && !readOnly)
        AppButton(
            "Refresh canvas",
            { state.seqDiagrams.request?.let { state.seqDiagrams.requestPreview(it.tabId, it.spec) } },
            variant = ButtonVariant.Ghost,
            enabled = request != null && !readOnly,
        )
        Spacer(Modifier.weight(1f))
        AppText(
            if (ready) "Canvas synced with Messages" else "Waiting for rendered messages",
            color = V2_MUTED,
            fontSize = 10.sp,
        )
        AppButton(
            "Attach snapshot",
            { attach(linked = false) },
            variant = ButtonVariant.Ghost,
            enabled = ready && !readOnly,
        )
        AppButton(
            "Attach linked",
            { attach(linked = true) },
            variant = ButtonVariant.Primary,
            enabled = ready && !readOnly,
        )
    }
}

@Composable
private fun V2CenteredHint(text: String, color: Color) {
    Box(Modifier.fillMaxWidth().height(260.dp), contentAlignment = Alignment.Center) {
        AppText(text, color = color, fontSize = 11.sp, maxLines = 3)
    }
}
