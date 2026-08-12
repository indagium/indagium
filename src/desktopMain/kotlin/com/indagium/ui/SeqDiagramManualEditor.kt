@file:OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)

package com.indagium.ui

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.background
import androidx.compose.foundation.*
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.TooltipArea
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.zIndex
import com.indagium.diagram.DiagramAuthoringMode
import com.indagium.diagram.DiagramParameter
import com.indagium.diagram.ManualDiagramDocument
import com.indagium.diagram.ManualDiagramActivation
import com.indagium.diagram.ManualDiagramGroup
import com.indagium.diagram.ManualDiagramInteraction
import com.indagium.diagram.ManualDiagramNote
import com.indagium.diagram.ManualDiagramSeedConfiguration
import com.indagium.diagram.ManualOperationVisibility
import com.indagium.diagram.MessageKind
import com.indagium.diagram.SeqDiagramSpec
import com.indagium.diagram.manualDocumentFromDiagram
import com.indagium.model.LogEntry
import kotlin.math.roundToInt

private val SEQUENCE_EDITOR_ROW_HEIGHT = 36.dp

/**
 * Authoring controls deliberately edit only durable [SeqDiagramSpec] fields.  Generation remains
 * in SeqDiagramCoordinator's latest-only preview lane, so typing in this panel never runs the
 * builder on the composition thread.
 */
@Composable
internal fun DiagramAuthoringSection(
    spec: SeqDiagramSpec,
    lifelineIds: List<String>,
    preview: com.indagium.diagram.SeqDiagram?,
    entries: List<LogEntry>,
    anchorEntryIds: Set<Int>,
    workspaceKey: String,
    rangeContent: @Composable () -> Unit,
    onSpec: (SeqDiagramSpec) -> Unit,
    onApplySeed: (ManualDiagramSeedConfiguration, Boolean) -> Unit,
    onRevertSeed: () -> Unit,
    onClearAllManual: () -> Unit,
    canRevertSeed: Boolean,
    seedNeedsConfirmation: Boolean,
    seedBusy: Boolean,
    seedStatus: String?,
) {
    val manual = spec.authoringMode == DiagramAuthoringMode.MANUAL
    var seedConfiguration by remember(workspaceKey) { mutableStateOf(ManualDiagramSeedConfiguration()) }
    var confirmApply by remember(workspaceKey) { mutableStateOf(false) }
    SectionHeader("Authoring")
    SegmentedControl(
        listOf("Inferred", "Manual"),
        if (spec.authoringMode == DiagramAuthoringMode.INFERRED) setOf(0) else setOf(1),
        onToggle = { selected ->
            val next = if (selected == 0) DiagramAuthoringMode.INFERRED else DiagramAuthoringMode.MANUAL
            if (next != spec.authoringMode) {
                val seeded = if (next == DiagramAuthoringMode.MANUAL && spec.manualDocument.interactions.isEmpty()) {
                    preview?.let(::manualDocumentFromDiagram)
                        ?: spec.manualDocument
                } else spec.manualDocument
                onSpec(spec.copy(authoringMode = next, manualDocument = seeded))
            }
        },
        modifier = Modifier.padding(horizontal = 12.dp),
    )
    AppText(
        if (spec.authoringMode == DiagramAuthoringMode.INFERRED)
            "Adjust inferred messages below, or switch to Manual to keep a durable interaction document independent of source inference."
        else
            "Manual interactions are ordered and durable. Add lifelines in Participants, then select only those lifelines for each interaction.",
        color = tc().td, fontSize = 10.sp, maxLines = 3, modifier = Modifier.padding(horizontal = 12.dp),
    )
    if (manual) {
        // Range is deliberately the first manual-mode content block after the mode switch.
        rangeContent()
        SectionHeader("Build starting point", trailing = {
            if (seedBusy) AppText("working", color = tc().td, fontSize = 9.sp)
        })
        CheckRow(
            checked = seedConfiguration.reconstructSourceTrace,
            onToggle = { seedConfiguration = seedConfiguration.copy(reconstructSourceTrace = !seedConfiguration.reconstructSourceTrace) },
        ) { AppText("Reconstruct source execution trace", fontSize = 10.sp) }
        CheckRow(
            checked = seedConfiguration.inferThreadHandoffs,
            onToggle = { seedConfiguration = seedConfiguration.copy(inferThreadHandoffs = !seedConfiguration.inferThreadHandoffs) },
        ) { AppText("Infer same-thread handoffs (PID + TID)", fontSize = 10.sp) }
        Row(Modifier.padding(horizontal = 12.dp), horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
            AppButton(
                "Apply to manual lines",
                {
                    if (seedNeedsConfirmation) confirmApply = true else onApplySeed(seedConfiguration, false)
                },
                variant = ButtonVariant.Primary,
                enabled = !seedBusy,
            )
            AppButton("Reset", onRevertSeed, variant = ButtonVariant.Ghost, enabled = canRevertSeed && !seedBusy)
        }
        if (confirmApply) {
            AppText("This replaces manual edits made since the last seed.", color = DANGER_RED, fontSize = 9.sp, maxLines = 2, modifier = Modifier.padding(horizontal = 12.dp))
            Row(Modifier.padding(horizontal = 12.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                AppButton("Cancel", { confirmApply = false }, variant = ButtonVariant.Ghost)
                AppButton("Replace manual lines", {
                    confirmApply = false
                    onApplySeed(seedConfiguration, true)
                }, variant = ButtonVariant.Secondary, isDanger = true, enabled = !seedBusy)
            }
        }
        if (seedBusy || seedStatus != null) {
            AppText(seedStatus ?: "Applying…", color = tc().td, fontSize = 9.sp, maxLines = 2, modifier = Modifier.padding(horizontal = 12.dp))
        }
        var selectedInteractionIds by remember(workspaceKey) { mutableStateOf<Set<String>>(emptySet()) }
        ManualInteractionEditor(
            spec, lifelineIds, entries, anchorEntryIds, selectedInteractionIds,
            onSelectionChanged = { selectedInteractionIds = it }, onSpec = onSpec,
        )
        LifelineOrderEditor(spec, lifelineIds, workspaceKey, onSpec)
        ManualDocumentAuxEditors(
            spec, lifelineIds, selectedInteractionIds, workspaceKey, onSpec,
            onClearAll = onClearAllManual,
        )
    }
}

@Composable
private fun LifelineOrderEditor(spec: SeqDiagramSpec, lifelineIds: List<String>, workspaceKey: String, onSpec: (SeqDiagramSpec) -> Unit) {
    var ordered by remember(spec.lifelineOrder, lifelineIds) {
        mutableStateOf((spec.lifelineOrder.filter { it in lifelineIds } + lifelineIds.filter { it !in spec.lifelineOrder }).distinct())
    }
    val activeIds = remember(spec.manualDocument.interactions) {
        spec.manualDocument.interactions.filter { it.enabled }
            .flatMapTo(linkedSetOf()) { listOf(it.fromParticipantId, it.toParticipantId) }
    }
    val visibleOrdered = ordered.filter { it in activeIds }
    var expanded by remember(workspaceKey) { mutableStateOf(true) }
    var dragId by remember { mutableStateOf<String?>(null) }
    var dragStartIndex by remember { mutableStateOf(-1) }
    var dragStartTopY by remember { mutableStateOf(0f) }
    var dragOffsetY by remember { mutableStateOf(0f) }
    var justReleasedId by remember { mutableStateOf<String?>(null) }
    var liveVisualIds by remember { mutableStateOf(emptyList<String>()) }
    val density = LocalDensity.current
    val rowHeightPx = with(density) { SEQUENCE_EDITOR_ROW_HEIGHT.toPx() }
    val rowHeightDp = SEQUENCE_EDITOR_ROW_HEIGHT
    val currentSpec = rememberUpdatedState(spec)
    val currentOnSpec = rememberUpdatedState(onSpec)
    val currentOrdered = rememberUpdatedState(ordered)
    val visibleIds = visibleOrdered
    androidx.compose.runtime.LaunchedEffect(visibleIds, dragId, justReleasedId) {
        if (shouldSyncSequenceVisualOrder(dragId, justReleasedId)) liveVisualIds = visibleIds
    }
    androidx.compose.runtime.LaunchedEffect(justReleasedId) {
        if (justReleasedId != null) {
            kotlinx.coroutines.delay(120)
            justReleasedId = null
        }
    }
    val visualIds = liveVisualIds.takeIf { it.toSet() == visibleIds.toSet() && it.size == visibleIds.size } ?: visibleIds
    val currentVisualIds = rememberUpdatedState(visualIds)
    val currentDragId = rememberUpdatedState(dragId)

    fun commitVisibleOrder(nextVisibleIds: List<String>) {
        var nextIndex = 0
        val nextOrdered = currentOrdered.value.map { id -> if (id in visibleIds) nextVisibleIds[nextIndex++] else id }
        ordered = nextOrdered
        currentOnSpec.value(currentSpec.value.copy(lifelineOrder = nextOrdered))
    }
    SectionHeader(
        "Lifeline order",
        trailing = { AppText("${visibleOrdered.size}", color = tc().td, fontSize = 9.sp) },
        expanded = expanded,
        onToggle = { expanded = !expanded },
    )
    if (visibleOrdered.isEmpty()) {
        AppText("Enable a manual line to show its lifelines here.", color = tc().td, fontSize = 10.sp, modifier = Modifier.padding(horizontal = 12.dp))
        return
    }
    if (expanded) {
        BoundedScrollBoxDp(maxHeightDp = 8 * SEQUENCE_EDITOR_ROW_HEIGHT.value.toInt()) {
            Box(
                Modifier.fillMaxWidth()
                    .height(rowHeightDp * visibleIds.size)
                    .pointerInput(visibleIds, rowHeightPx) {
                        var downPos = Offset.Zero
                        var downId: String? = null
                        var dragging = false
                        awaitPointerEventScope {
                            while (true) {
                                val event = awaitPointerEvent(PointerEventPass.Initial)
                                val change = event.changes.firstOrNull() ?: continue
                                when (event.type) {
                                    PointerEventType.Press -> {
                                        downPos = change.position
                                        dragging = false
                                        downId = visibleIds.getOrNull((change.position.y / rowHeightPx).toInt())
                                    }
                                    PointerEventType.Move -> {
                                        if (downId != null && !dragging && (change.position - downPos).getDistance() > 8f) {
                                            dragId = downId
                                            dragStartIndex = visibleIds.indexOf(downId)
                                            dragStartTopY = dragStartIndex * rowHeightPx
                                            dragOffsetY = 0f
                                            justReleasedId = null
                                            liveVisualIds = visibleIds
                                            dragging = true
                                        }
                                        if (dragging && dragId != null) {
                                            change.consume()
                                            dragOffsetY = change.position.y - downPos.y
                                            liveVisualIds = sequenceOrderDuringDrag(visibleIds, dragId, dragStartIndex, dragOffsetY, rowHeightPx)
                                        }
                                    }
                                    PointerEventType.Release -> {
                                        if (dragging && dragId != null) {
                                            val releasedId = currentDragId.value ?: dragId
                                            val releasedOrder = currentVisualIds.value
                                            val targetIndex = releasedOrder.indexOf(releasedId)
                                            if (releasedId != null && targetIndex >= 0 && targetIndex != visibleIds.indexOf(releasedId)) {
                                                liveVisualIds = releasedOrder
                                                commitVisibleOrder(releasedOrder)
                                            }
                                            justReleasedId = releasedId
                                        }
                                        dragId = null
                                        dragStartIndex = -1
                                        dragStartTopY = 0f
                                        dragOffsetY = 0f
                                        downId = null
                                        dragging = false
                                    }
                                    else -> Unit
                                }
                            }
                        }
                    },
            ) {
                visibleIds.forEach { id ->
                    key(id) {
                        val isDragging = dragId == id
                        val targetIndex = visualIds.indexOf(id).coerceAtLeast(0)
                        val targetY = targetIndex * rowHeightPx
                        val animatedY by animateFloatAsState(targetY, spring(stiffness = 650f, dampingRatio = 0.86f), label = "lifeline-y-$id")
                        val y = sequenceRenderY(isDragging, justReleasedId == id, dragStartTopY + dragOffsetY, targetY, animatedY)
                        Row(
                            Modifier.fillMaxWidth().height(rowHeightDp)
                                .offset { IntOffset(0, y.roundToInt()) }
                                .zIndex(if (isDragging) 1f else 0f)
                                .graphicsLayer { if (isDragging) { scaleX = 1.02f; scaleY = 1.02f } }
                                .padding(horizontal = 12.dp),
                            horizontalArrangement = Arrangement.spacedBy(4.dp), verticalAlignment = Alignment.CenterVertically,
                        ) {
                            AppText("☷", color = if (isDragging) tc().ac else tc().td, fontSize = 12.sp)
                            AppText(
                                id,
                                fontSize = 11.sp,
                                fontWeight = FontWeight.SemiBold,
                                modifier = Modifier.weight(1f),
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun ManualInteractionEditor(
    spec: SeqDiagramSpec,
    lifelineIds: List<String>,
    entries: List<LogEntry>,
    anchorEntryIds: Set<Int>,
    selectedInteractionIds: Set<String>,
    onSelectionChanged: (Set<String>) -> Unit,
    onSpec: (SeqDiagramSpec) -> Unit,
) {
    val document = spec.manualDocument
    var expanded by remember { mutableStateOf(true) }
    SectionHeader("Manual interactions", expanded = expanded, onToggle = { expanded = !expanded })
    if (!expanded) return
    if (lifelineIds.isEmpty()) {
        AppText("Manual interactions need at least one configured lifeline.", color = tc().td, fontSize = 10.sp, modifier = Modifier.padding(horizontal = 12.dp))
        return
    }
    val groups = remember(document.interactions) {
        groupedManualInteractions(document)
    }
    val groupKeys = groups.map { it.groupKey() }
    var expandedGroups by remember(groupKeys) { mutableStateOf(emptySet<String>()) }
    val currentSpec = rememberUpdatedState(spec)
    val currentOnSpec = rememberUpdatedState(onSpec)
    val rowHeightPx = with(LocalDensity.current) { SEQUENCE_EDITOR_ROW_HEIGHT.toPx() }
    val rowHeightDp = SEQUENCE_EDITOR_ROW_HEIGHT
    var dragId by remember { mutableStateOf<String?>(null) }
    var dragStartIndex by remember { mutableStateOf(-1) }
    var dragStartTopY by remember { mutableStateOf(0f) }
    var dragOffsetY by remember { mutableStateOf(0f) }
    var justReleasedId by remember { mutableStateOf<String?>(null) }
    var liveVisualGroupKeys by remember { mutableStateOf(emptyList<String>()) }
    androidx.compose.runtime.LaunchedEffect(groupKeys, dragId, justReleasedId) {
        if (shouldSyncSequenceVisualOrder(dragId, justReleasedId)) liveVisualGroupKeys = groupKeys
    }
    androidx.compose.runtime.LaunchedEffect(justReleasedId) {
        if (justReleasedId != null) {
            kotlinx.coroutines.delay(120)
            justReleasedId = null
        }
    }
    val visualGroupKeys = liveVisualGroupKeys.takeIf { it.toSet() == groupKeys.toSet() && it.size == groupKeys.size } ?: groupKeys
    val currentVisualGroupKeys = rememberUpdatedState(visualGroupKeys)
    val currentDragId = rememberUpdatedState(dragId)
    val groupsByKey = groups.associateBy { it.groupKey() }
    BoundedScrollBoxDp(maxHeightDp = 8 * SEQUENCE_EDITOR_ROW_HEIGHT.value.toInt()) {
        Box(
            Modifier.fillMaxWidth().height(rowHeightDp * groups.size)
                .pointerInput(groupKeys, rowHeightPx) {
                    var downPos = Offset.Zero
                    var downId: String? = null
                    var dragging = false
                    awaitPointerEventScope {
                        while (true) {
                            val event = awaitPointerEvent(PointerEventPass.Initial)
                            val change = event.changes.firstOrNull() ?: continue
                            when (event.type) {
                                PointerEventType.Press -> {
                                    downPos = change.position
                                    dragging = false
                                    downId = groupKeys.getOrNull((change.position.y / rowHeightPx).toInt())
                                }
                                PointerEventType.Move -> {
                                    if (downId != null && !dragging && (change.position - downPos).getDistance() > 8f) {
                                        dragId = downId
                                        dragStartIndex = groupKeys.indexOf(downId)
                                        dragStartTopY = dragStartIndex * rowHeightPx
                                        dragOffsetY = 0f
                                        justReleasedId = null
                                        liveVisualGroupKeys = groupKeys
                                        dragging = true
                                    }
                                    if (dragging && dragId != null) {
                                        change.consume()
                                        dragOffsetY = change.position.y - downPos.y
                                        liveVisualGroupKeys = sequenceOrderDuringDrag(groupKeys, dragId, dragStartIndex, dragOffsetY, rowHeightPx)
                                    }
                                }
                                PointerEventType.Release -> {
                                    if (dragging && dragId != null) {
                                        val releasedId = currentDragId.value ?: dragId
                                        val releasedOrder = currentVisualGroupKeys.value
                                        val targetIndex = releasedOrder.indexOf(releasedId)
                                        if (releasedId != null && targetIndex >= 0 && targetIndex != groupKeys.indexOf(releasedId)) {
                                            liveVisualGroupKeys = releasedOrder
                                            reorderManualGroupsByKeys(currentSpec.value, releasedOrder, currentOnSpec.value)
                                        }
                                        justReleasedId = releasedId
                                    }
                                    dragId = null
                                    dragStartIndex = -1
                                    dragStartTopY = 0f
                                    dragOffsetY = 0f
                                    downId = null
                                    dragging = false
                                }
                                else -> Unit
                            }
                        }
                    }
                },
        ) {
            visualGroupKeys.forEach { groupKey ->
                val members = groupsByKey[groupKey] ?: return@forEach
                key(groupKey) {
                    val isDragging = dragId == groupKey
                    val targetIndex = visualGroupKeys.indexOf(groupKey).coerceAtLeast(0)
                    val targetY = targetIndex * rowHeightPx
                    val animatedY by animateFloatAsState(targetY, spring(stiffness = 650f, dampingRatio = 0.86f), label = "manual-group-y-$groupKey")
                    val y = sequenceRenderY(isDragging, justReleasedId == groupKey, dragStartTopY + dragOffsetY, targetY, animatedY)
                    ManualInteractionGroupRow(
                    members = members,
                    expanded = members.groupKey() in expandedGroups,
                    selected = members.any { it.id in selectedInteractionIds },
                    dragging = isDragging,
                    visualOffsetPx = y,
                    onToggleExpanded = {
                        val key = members.groupKey()
                        expandedGroups = if (key in expandedGroups) expandedGroups - key else expandedGroups + key
                    },
                    lifelineIds = lifelineIds,
                    onToggleSelected = {
                        val ids = members.map { it.id }.toSet()
                        onSelectionChanged(if (ids.all { it in selectedInteractionIds }) selectedInteractionIds - ids else selectedInteractionIds + ids)
                    },
                    entries = entries,
                    onGroupChange = { change ->
                        val ids = members.map { it.id }.toSet()
                        val changed = document.interactions.map { interaction ->
                            if (interaction.id in ids) change(interaction) else interaction
                        }
                        onSpec(spec.copy(manualDocument = document.copy(interactions = changed)))
                    },
                    onDelete = {
                        val ids = members.map { it.id }.toSet()
                        onSpec(spec.copy(manualDocument = document.copy(interactions = document.interactions.filterNot { it.id in ids })))
                    },
                )
            }
        }
    }
    }
    AppButton(
        "Add interaction",
        {
            val active = document.interactions.filter { it.enabled }.flatMap { listOf(it.fromParticipantId, it.toParticipantId) }.distinct()
            val first = active.firstOrNull() ?: lifelineIds.first()
            val second = active.drop(1).firstOrNull() ?: lifelineIds.firstOrNull { it != first } ?: first
            val interaction = ManualDiagramInteraction(
                id = "manual-${System.nanoTime()}", sourceEntryIds = anchorEntryIds, fromParticipantId = first,
                toParticipantId = second, operation = "operation", order = nextManualOrder(document),
            )
            onSpec(spec.copy(manualDocument = document.copy(interactions = document.interactions + interaction)))
        },
        modifier = Modifier.padding(horizontal = 12.dp),
    )
}

private fun groupedManualInteractions(document: ManualDiagramDocument): List<List<ManualDiagramInteraction>> =
    document.interactions.groupBy { it.groupKey?.takeIf(String::isNotBlank) ?: "__individual:${it.id}" }
        .values
        .map { members -> members.sortedWith(compareBy<ManualDiagramInteraction> { it.order }.thenBy { it.id }) }
        .sortedWith(compareBy<List<ManualDiagramInteraction>> { it.minOfOrNull(ManualDiagramInteraction::order) ?: Long.MAX_VALUE }
            .thenBy { it.firstOrNull()?.id.orEmpty() })

private fun List<ManualDiagramInteraction>.groupKey(): String =
    firstOrNull()?.groupKey?.takeIf(String::isNotBlank) ?: "__individual:${firstOrNull()?.id.orEmpty()}"

@Composable
private fun ManualRoundToggle(
    tooltip: String,
    active: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    color: Color = tc().ac,
    indeterminate: Boolean = false,
) {
    TooltipArea(tooltip = { ToolbarTooltip(tooltip) }) {
        RoundIndicator(
            active = active,
            color = color,
            indeterminate = indeterminate,
            onClick = onClick,
            modifier = modifier,
        )
    }
}

@Composable
private fun ManualInteractionGroupRow(
    members: List<ManualDiagramInteraction>,
    expanded: Boolean,
    selected: Boolean,
    onToggleExpanded: () -> Unit,
    lifelineIds: List<String>,
    onToggleSelected: () -> Unit,
    entries: List<LogEntry>,
    onGroupChange: ((ManualDiagramInteraction) -> ManualDiagramInteraction) -> Unit,
    dragging: Boolean,
    visualOffsetPx: Float,
    onDelete: () -> Unit,
) {
    val representative = members.firstOrNull() ?: return
    val allEnabled = members.all { it.enabled }
    val someEnabled = members.any { it.enabled }
    Column(
        Modifier.fillMaxWidth()
            .offset { IntOffset(0, visualOffsetPx.roundToInt()) }
            .zIndex(if (dragging) 1f else 0f)
            .graphicsLayer { if (dragging) { scaleX = 1.02f; scaleY = 1.02f } }
            .background(if (selected) tc().abg else Color.Transparent, CORNER_SM)
            .padding(horizontal = 12.dp),
    ) {
        Row(
            Modifier.fillMaxWidth().height(SEQUENCE_EDITOR_ROW_HEIGHT),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(5.dp),
        ) {
            AppText(
                "☷",
                color = if (dragging) tc().ac else tc().td,
                fontSize = 12.sp,
            )
            ManualRoundToggle(
                tooltip = if (allEnabled) "Disable all occurrences" else "Enable all occurrences",
                active = allEnabled,
                indeterminate = someEnabled && !allEnabled,
                onClick = { onGroupChange { it.copy(enabled = !allEnabled) } },
            )
            ManualRoundToggle(
                tooltip = if (selected) "Deselect for advanced structure" else "Select for advanced structure",
                active = selected,
                color = tc().td,
                onClick = onToggleSelected,
            )
            AppText(
                representative.operation.ifBlank { representative.label ?: "Untitled interaction" },
                fontSize = 11.sp,
                fontWeight = FontWeight.SemiBold,
                modifier = Modifier.weight(1f),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            if (members.size > 1) AppText("×${members.size}", color = tc().td, fontSize = 10.sp)
            AppButton(if (expanded) "Collapse" else "Expand", onToggleExpanded, variant = ButtonVariant.Ghost)
            AppButton("×", onDelete, variant = ButtonVariant.Ghost)
        }
        if (expanded) {
            ManualInteractionCard(
                interaction = representative,
                lifelineIds = lifelineIds,
                onChange = { changed ->
                    onGroupChange { current ->
                        current.copy(
                            fromParticipantId = changed.fromParticipantId,
                            toParticipantId = changed.toParticipantId,
                            operation = changed.operation,
                            // Parameters and literal labels belong to occurrences. A group edit
                            // changes the shared operation/endpoints/type/visibility only.
                            parameters = current.parameters,
                            result = current.result,
                            label = current.label,
                            kind = changed.kind,
                            visibility = changed.visibility,
                        )
                    }
                },
                onDelete = onDelete, onDuplicate = {}, showActions = false,
            )
            AppText("Occurrences", color = tc().td, fontSize = 9.sp)
            members.forEach { occurrence ->
                Row(
                    Modifier.fillMaxWidth().padding(start = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(5.dp),
                ) {
                    ManualRoundToggle(
                        tooltip = if (occurrence.enabled) "Disable occurrence" else "Enable occurrence",
                        active = occurrence.enabled,
                        onClick = {
                            onGroupChange { current -> if (current.id == occurrence.id) current.copy(enabled = !current.enabled) else current }
                        },
                    )
                    AppText(
                        occurrenceEvidence(occurrence, entries),
                        fontSize = 9.sp,
                        modifier = Modifier.weight(1f),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    AppButton("Move out", {
                        onGroupChange { current -> if (current.id == occurrence.id) current.copy(groupKey = null) else current }
                    }, variant = ButtonVariant.Ghost)
                }
            }
        }
    }
}

private fun occurrenceEvidence(interaction: ManualDiagramInteraction, entries: List<LogEntry>): String {
    val evidence = interaction.sourceEntryIds.sorted().mapNotNull { id ->
        entries.firstOrNull { it.id == id }?.let { entry -> "${entry.ts} ${entry.tag}: ${entry.msg}" }
    }
    if (evidence.isNotEmpty()) return evidence.joinToString(" · ")
    val params = interaction.parameters.formatParameters()
    return "rows ${interaction.sourceEntryIds.sorted().joinToString(",")}" + params.takeIf { it.isNotBlank() }?.let { " · $it" }.orEmpty()
}

private fun nextManualOrder(document: ManualDiagramDocument): Long =
    (document.interactions.maxOfOrNull { it.order } ?: -1L) + 1L

private fun updateManualInteraction(spec: SeqDiagramSpec, interaction: ManualDiagramInteraction, onSpec: (SeqDiagramSpec) -> Unit) {
    onSpec(spec.copy(manualDocument = spec.manualDocument.copy(
        interactions = spec.manualDocument.interactions.map { if (it.id == interaction.id) interaction else it },
    )))
}

private fun reorderManualInteractions(
    spec: SeqDiagramSpec,
    ordered: List<ManualDiagramInteraction>,
    from: Int,
    to: Int,
    onSpec: (SeqDiagramSpec) -> Unit,
) {
    if (from !in ordered.indices || to !in ordered.indices) return
    val reordered = ordered.toMutableList().also { list ->
        val item = list.removeAt(from)
        list.add(to, item)
    }.mapIndexed { index, interaction -> interaction.copy(order = index.toLong()) }
    onSpec(spec.copy(manualDocument = spec.manualDocument.copy(interactions = reordered)))
}

private fun reorderManualGroupByKey(
    spec: SeqDiagramSpec,
    groupKey: String,
    delta: Int,
    onSpec: (SeqDiagramSpec) -> Unit,
) {
    val groups = groupedManualInteractions(spec.manualDocument)
    val from = groups.indexOfFirst { it.groupKey() == groupKey }
    reorderManualGroups(spec, groups, from, delta, onSpec)
}

private fun reorderManualGroupsByKeys(
    spec: SeqDiagramSpec,
    orderedKeys: List<String>,
    onSpec: (SeqDiagramSpec) -> Unit,
) {
    val groupsByKey = groupedManualInteractions(spec.manualDocument).associateBy { it.groupKey() }
    val reorderedGroups = orderedKeys.mapNotNull(groupsByKey::get)
    if (reorderedGroups.size != groupsByKey.size) return
    val orderById = reorderedGroups
        .flatMap { it }
        .mapIndexed { index, interaction -> interaction.id to index.toLong() }
        .toMap()
    onSpec(spec.copy(manualDocument = spec.manualDocument.copy(
        interactions = spec.manualDocument.interactions.map { interaction ->
            interaction.copy(order = orderById[interaction.id] ?: interaction.order)
        },
    )))
}

private fun reorderManualGroups(
    spec: SeqDiagramSpec,
    groups: List<List<ManualDiagramInteraction>>,
    from: Int,
    delta: Int,
    onSpec: (SeqDiagramSpec) -> Unit,
) {
    val to = from + delta
    if (from !in groups.indices || to !in groups.indices) return
    val reorderedGroups = groups.toMutableList().also { list ->
        val moved = list.removeAt(from)
        list.add(to, moved)
    }
    val orderById = reorderedGroups.flatMap { it }.mapIndexed { index, interaction -> interaction.id to index.toLong() }.toMap()
    onSpec(spec.copy(manualDocument = spec.manualDocument.copy(
        interactions = spec.manualDocument.interactions.map { interaction -> interaction.copy(order = orderById[interaction.id] ?: interaction.order) },
    )))
}

@Composable
private fun ManualDocumentAuxEditors(
    spec: SeqDiagramSpec,
    lifelineIds: List<String>,
    selectedInteractionIds: Set<String>,
    workspaceKey: String,
    onSpec: (SeqDiagramSpec) -> Unit,
    onClearAll: () -> Unit,
) {
    val document = spec.manualDocument
    val interactionIds = document.interactions.map { it.id }
    val selected = document.interactions.filter { it.id in selectedInteractionIds }.sortedBy { it.order }
    var expanded by remember(workspaceKey) { mutableStateOf(false) }
    var confirmClear by remember(workspaceKey) { mutableStateOf(false) }
    SectionHeader(
        "Advanced structure",
        trailing = { AppText("${document.groups.size + document.notes.size + document.activations.size}", color = tc().td, fontSize = 9.sp) },
        expanded = expanded,
        onToggle = { expanded = !expanded },
    )
    if (!expanded) return
    AppText(
        "Use the first dot to enable a line. Use the second dot to select lines for a frame, note, or activation. Existing structure remains editable below.",
        color = tc().td, fontSize = 9.sp, maxLines = 3, modifier = Modifier.padding(horizontal = 12.dp),
    )
    AppButton(
        if (selected.size >= 2) "Add frame/group (${selected.size} selected)" else "Select 2+ lines for frame/group",
        {
            val group = ManualDiagramGroup("group-${System.nanoTime()}", "Group", selected.map { it.id })
            onSpec(spec.copy(manualDocument = document.copy(groups = document.groups + group)))
        },
        variant = ButtonVariant.Ghost,
        enabled = selected.size >= 2,
        modifier = Modifier.padding(horizontal = 12.dp),
    )
    document.groups.forEach { group ->
        Column(Modifier.padding(horizontal = 12.dp), verticalArrangement = Arrangement.spacedBy(3.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                CompactCheckBox(checked = group.enabled, onToggle = {
                    onSpec(spec.copy(manualDocument = document.copy(groups = document.groups.map { if (it.id == group.id) it.copy(enabled = !it.enabled) else it })))
                })
                InlineField(group.label, { value -> onSpec(spec.copy(manualDocument = document.copy(groups = document.groups.map { if (it.id == group.id) it.copy(label = value) else it }))) }, "frame label", Modifier.weight(1f), fontSize = 10.sp)
                AppText("${group.interactionIds.size} lines", color = tc().td, fontSize = 9.sp)
                AppButton("×", { onSpec(spec.copy(manualDocument = document.copy(groups = document.groups.filterNot { it.id == group.id }))) }, variant = ButtonVariant.Ghost)
            }
        }
    }

    AppButton(
        if (selected.size == 1) "Add note after selected line" else "Select 1 line for note",
        {
            val participant = selected.firstOrNull()?.fromParticipantId ?: return@AppButton
            val anchor = selected.singleOrNull()?.id ?: return@AppButton
            val note = ManualDiagramNote("note-${System.nanoTime()}", participant, anchor, "Note")
            onSpec(spec.copy(manualDocument = document.copy(notes = document.notes + note)))
        },
        variant = ButtonVariant.Ghost,
        enabled = selected.size == 1 && lifelineIds.isNotEmpty(),
        modifier = Modifier.padding(horizontal = 12.dp),
    )
    document.notes.forEach { note ->
        Column(Modifier.padding(horizontal = 12.dp), verticalArrangement = Arrangement.spacedBy(3.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                CompactCheckBox(checked = note.enabled, onToggle = { onSpec(spec.copy(manualDocument = document.copy(notes = document.notes.map { if (it.id == note.id) it.copy(enabled = !it.enabled) else it }))) })
                InlineField(note.text, { value -> onSpec(spec.copy(manualDocument = document.copy(notes = document.notes.map { if (it.id == note.id) it.copy(text = value) else it }))) }, "note", Modifier.weight(1f), fontSize = 10.sp)
                AppButton("×", { onSpec(spec.copy(manualDocument = document.copy(notes = document.notes.filterNot { it.id == note.id }))) }, variant = ButtonVariant.Ghost)
            }
            LifelineChoice("Participant", note.participantId, lifelineIds) { id -> onSpec(spec.copy(manualDocument = document.copy(notes = document.notes.map { if (it.id == note.id) it.copy(participantId = id) else it }))) }
            InteractionChoice("After", note.afterInteractionId, interactionIds) { id ->
                onSpec(spec.copy(manualDocument = document.copy(notes = document.notes.map { if (it.id == note.id) it.copy(afterInteractionId = id) else it })))
            }
        }
    }

    AppButton(
        if (selected.isNotEmpty()) "Add activation for selected span" else "Select a line span for activation",
        {
            val participant = selected.firstOrNull()?.fromParticipantId ?: return@AppButton
            val activation = ManualDiagramActivation("activation-${System.nanoTime()}", participant, selected.first().id, selected.last().id)
            onSpec(spec.copy(manualDocument = document.copy(activations = document.activations + activation)))
        },
        variant = ButtonVariant.Ghost,
        enabled = selected.isNotEmpty(),
        modifier = Modifier.padding(horizontal = 12.dp),
    )
    document.activations.forEach { activation ->
        Column(Modifier.padding(horizontal = 12.dp), verticalArrangement = Arrangement.spacedBy(3.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                CompactCheckBox(checked = activation.enabled, onToggle = { onSpec(spec.copy(manualDocument = document.copy(activations = document.activations.map { if (it.id == activation.id) it.copy(enabled = !it.enabled) else it }))) })
                AppText("Activation", fontSize = 10.sp, modifier = Modifier.weight(1f))
                AppButton("×", { onSpec(spec.copy(manualDocument = document.copy(activations = document.activations.filterNot { it.id == activation.id }))) }, variant = ButtonVariant.Ghost)
            }
            LifelineChoice("Participant", activation.participantId, lifelineIds) { id -> onSpec(spec.copy(manualDocument = document.copy(activations = document.activations.map { if (it.id == activation.id) it.copy(participantId = id) else it }))) }
            InteractionChoice("Start", activation.startInteractionId, interactionIds) { id ->
                onSpec(spec.copy(manualDocument = document.copy(activations = document.activations.map { if (it.id == activation.id) it.copy(startInteractionId = id) else it })))
            }
            InteractionChoice("End", activation.endInteractionId, interactionIds) { id ->
                onSpec(spec.copy(manualDocument = document.copy(activations = document.activations.map { if (it.id == activation.id) it.copy(endInteractionId = id) else it })))
            }
        }
    }

    if (!confirmClear) {
        AppButton("Clear all manual lines", { confirmClear = true }, variant = ButtonVariant.Ghost, isDanger = true, modifier = Modifier.padding(horizontal = 12.dp))
    } else {
        AppText("This removes interactions, frames, notes, and activations.", color = DANGER_RED, fontSize = 9.sp, modifier = Modifier.padding(horizontal = 12.dp))
        Row(Modifier.padding(horizontal = 12.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            AppButton("Cancel", { confirmClear = false }, variant = ButtonVariant.Ghost)
            AppButton("Clear", { confirmClear = false; onClearAll() }, variant = ButtonVariant.Secondary, isDanger = true)
        }
    }
}

@Composable
private fun InteractionChoice(label: String, selected: String, interactionIds: List<String>, onSelect: (String) -> Unit) {
    var expanded by remember(label, selected) { mutableStateOf(false) }
    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
        AppText(label, color = tc().td, fontSize = 9.sp)
        AppButton("$selected ▾", { expanded = !expanded }, variant = ButtonVariant.Secondary, modifier = Modifier.fillMaxWidth())
        if (expanded) {
            FlowRow(horizontalArrangement = Arrangement.spacedBy(3.dp), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                interactionIds.forEach { id ->
                    AppButton(id, { expanded = false; onSelect(id) }, variant = if (id == selected) ButtonVariant.Primary else ButtonVariant.Ghost)
                }
            }
        }
    }
}

@Composable
private fun ManualInteractionCard(
    interaction: ManualDiagramInteraction,
    lifelineIds: List<String>,
    onChange: (ManualDiagramInteraction) -> Unit,
    onDelete: () -> Unit,
    onDuplicate: () -> Unit,
    showActions: Boolean = true,
) {
    val tc = tc()
    var parameters by remember(interaction.id, interaction.parameters) { mutableStateOf(interaction.parameters.formatParameters()) }
    Column(Modifier.padding(horizontal = 12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(5.dp)) {
            ManualRoundToggle(
                tooltip = if (interaction.enabled) "Disable interaction" else "Enable interaction",
                active = interaction.enabled,
                onClick = { onChange(interaction.copy(enabled = !interaction.enabled)) },
            )
            AppText(interaction.operation.ifBlank { "Untitled interaction" }, fontSize = 11.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f), maxLines = 1)
            if (showActions) {
                AppButton("Duplicate", onDuplicate, variant = ButtonVariant.Ghost)
                AppButton("×", onDelete, variant = ButtonVariant.Ghost)
            }
        }
        LifelineChoice("From", interaction.fromParticipantId, lifelineIds) {
            onChange(interaction.copy(
                fromParticipantId = it,
                kind = manualKindAfterEndpointChange(interaction.kind, it, interaction.toParticipantId),
            ))
        }
        LifelineChoice("To", interaction.toParticipantId, lifelineIds) {
            onChange(interaction.copy(
                toParticipantId = it,
                kind = manualKindAfterEndpointChange(interaction.kind, interaction.fromParticipantId, it),
            ))
        }
        InlineField(interaction.operation, { onChange(interaction.copy(operation = it)) }, "operation", Modifier.fillMaxWidth(), fontSize = 10.sp)
        InlineField(interaction.label.orEmpty(), { value -> onChange(interaction.copy(label = value.ifBlank { null })) }, "custom label (optional)", Modifier.fillMaxWidth(), fontSize = 10.sp)
        InlineField(interaction.result.orEmpty(), { value -> onChange(interaction.copy(result = value.ifBlank { null })) }, "result label (optional)", Modifier.fillMaxWidth(), fontSize = 10.sp)
        InlineField(parameters, { value ->
            parameters = value
            onChange(interaction.copy(parameters = value.parseParameters()))
        }, "parameters: name=value; …", Modifier.fillMaxWidth(), fontSize = 10.sp)
        val kinds = listOf(MessageKind.CALL, MessageKind.RETURN, MessageKind.SELF, MessageKind.ASYNC)
        SegmentedControl(kinds.map { it.name.lowercase() }, setOf(kinds.indexOf(interaction.kind)), onToggle = { index ->
            onChange(interaction.copy(kind = kinds[index]))
        })
        ManualVisibilityChoice(interaction.visibility) { onChange(interaction.copy(visibility = it)) }
        AppText("Only configured lifelines are selectable. Parameters are shown in the operation label.", color = tc.td, fontSize = 9.sp, maxLines = 2)
    }
}

@Composable
private fun LifelineChoice(label: String, selected: String, lifelineIds: List<String>, onSelect: (String) -> Unit) {
    var expanded by remember(label, selected) { mutableStateOf(false) }
    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
        AppText(label, color = tc().td, fontSize = 9.sp)
        AppButton(
            "$selected ▾",
            { expanded = !expanded },
            variant = ButtonVariant.Secondary,
            modifier = Modifier.fillMaxWidth(),
        )
        if (expanded) {
            FlowRow(horizontalArrangement = Arrangement.spacedBy(3.dp), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                lifelineIds.forEach { id ->
                    AppButton(id, { expanded = false; onSelect(id) }, variant = if (id == selected) ButtonVariant.Primary else ButtonVariant.Ghost)
                }
            }
        }
    }
}

@Composable
private fun ManualVisibilityChoice(value: ManualOperationVisibility, onSelect: (ManualOperationVisibility) -> Unit) {
    val values = ManualOperationVisibility.entries
    SegmentedControl(
        values.map { it.name.lowercase().replace('_', ' ') },
        setOf(values.indexOf(value)),
        onToggle = { index -> onSelect(values[index]) },
    )
}

private fun List<DiagramParameter>.formatParameters(): String = joinToString("; ") { parameter ->
    if (parameter.name.isBlank()) parameter.value else "${parameter.name}=${parameter.value}"
}

private fun String.parseParameters(): List<DiagramParameter> = split(';').mapNotNull { raw ->
    val item = raw.trim()
    if (item.isBlank()) return@mapNotNull null
    val equals = item.indexOf('=')
    if (equals < 0) DiagramParameter(value = item)
    else DiagramParameter(item.substring(0, equals).trim(), item.substring(equals + 1).trim())
}

internal fun manualKindAfterEndpointChange(kind: MessageKind, fromParticipantId: String, toParticipantId: String): MessageKind =
    when {
        fromParticipantId == toParticipantId && kind == MessageKind.CALL -> MessageKind.SELF
        fromParticipantId != toParticipantId && kind == MessageKind.SELF -> MessageKind.CALL
        else -> kind
    }
