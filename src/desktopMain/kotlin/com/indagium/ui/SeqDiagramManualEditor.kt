@file:OptIn(
    androidx.compose.foundation.ExperimentalFoundationApi::class,
    androidx.compose.ui.ExperimentalComposeUiApi::class,
)

package com.indagium.ui

import androidx.compose.foundation.*
import androidx.compose.foundation.TooltipArea
import androidx.compose.foundation.background
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.onPointerEvent
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.indagium.diagram.DiagramParameter
import com.indagium.diagram.DiagramParticipant
import com.indagium.diagram.GuidedTargetPassState
import com.indagium.diagram.ManualDiagramActivation
import com.indagium.diagram.ManualDiagramDocument
import com.indagium.diagram.ManualDiagramGroup
import com.indagium.diagram.ManualDiagramInteraction
import com.indagium.diagram.ManualDiagramNote
import com.indagium.diagram.ManualDiagramSeedConfiguration
import com.indagium.diagram.ManualMessageBulkAction
import com.indagium.diagram.ManualMessageFilter
import com.indagium.diagram.ManualMessageQueueRow
import com.indagium.diagram.ManualMessageSort
import com.indagium.diagram.ManualMessageState
import com.indagium.diagram.ManualOperationVisibility
import com.indagium.diagram.ManualRegenerationReview
import com.indagium.diagram.MessageKind
import com.indagium.diagram.ParticipantKind
import com.indagium.diagram.SeqDiagramSpec
import com.indagium.diagram.advanceGuidedTargetPass
import com.indagium.diagram.applyManualMessageBulkAction
import com.indagium.diagram.beginGuidedTargetPass
import com.indagium.diagram.buildManualMessageQueue
import com.indagium.diagram.guidedTargetContext
import com.indagium.diagram.guidedTargetRow
import com.indagium.diagram.manualMessageTemplate
import com.indagium.diagram.suggestManualTarget
import com.indagium.model.LogEntry

private val SEQUENCE_EDITOR_ROW_HEIGHT = 36.dp

/**
 * Editor controls deliberately update only durable [SeqDiagramSpec] fields. Preview rebuilding is
 * handled by SeqDiagramCoordinator's latest-only lane, so typing in this panel never runs the
 * builder on the composition thread.
 */
@Composable
// `preview` is plumbed from the caller's actual preview state but not yet read in this body —
// flagged during a detekt cleanup pass rather than guessed at; kept (not deleted) since the call
// site already threads a real value through.
@Suppress("UnusedParameter")
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
    onNavigateEvidence: (Int) -> Unit,
    focusedManualInteractionId: String?,
    onFocusManualInteraction: (String?) -> Unit,
    manualSeedReview: ManualRegenerationReview?,
    onAcceptSeedReview: () -> Unit,
    onCancelSeedReview: () -> Unit,
    canRevertSeed: Boolean,
    seedNeedsConfirmation: Boolean,
    seedBusy: Boolean,
    seedStatus: String?,
) {
    var seedConfiguration by remember(workspaceKey) { mutableStateOf(ManualDiagramSeedConfiguration()) }
    var confirmApply by remember(workspaceKey) { mutableStateOf(false) }
    var guidedPass by remember(workspaceKey) { mutableStateOf<GuidedTargetPassState?>(null) }
    SectionHeader("Starting point")
    AppText(
        "Use the selected log rows to build an initial set of interactions. You can edit the result below; later builds replace it only when you apply them.",
        color = tc().td, fontSize = 10.sp, maxLines = 3, modifier = Modifier.padding(horizontal = 12.dp),
    )
    // The range selects source rows for the next build; it never reorders or otherwise changes
    // existing interactions.
    rangeContent()
    SectionHeader("Build from source", trailing = {
        if (seedBusy) AppText("building", color = tc().td, fontSize = 9.sp)
    })
    CheckRow(
        checked = seedConfiguration.reconstructSourceTrace,
        onToggle = { seedConfiguration = seedConfiguration.copy(reconstructSourceTrace = !seedConfiguration.reconstructSourceTrace) },
    ) { AppText("Use verified source trace", fontSize = 10.sp) }
    CheckRow(
        checked = seedConfiguration.inferThreadHandoffs,
        onToggle = { seedConfiguration = seedConfiguration.copy(inferThreadHandoffs = !seedConfiguration.inferThreadHandoffs) },
    ) { AppText("Include same-thread handoffs (PID + TID)", fontSize = 10.sp) }
    Row(Modifier.padding(horizontal = 12.dp), horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
        AppButton(
            "Review regeneration",
            {
                if (seedNeedsConfirmation) confirmApply = true else onApplySeed(seedConfiguration, false)
            },
            variant = ButtonVariant.Primary,
            enabled = !seedBusy,
        )
        AppButton("Reset", onRevertSeed, variant = ButtonVariant.Ghost, enabled = canRevertSeed && !seedBusy)
    }
    if (confirmApply) {
        AppText(
            "Build a review first; edited and manually created messages remain protected.",
            color = tc().td, fontSize = 9.sp, maxLines = 2, modifier = Modifier.padding(horizontal = 12.dp),
        )
        Row(Modifier.padding(horizontal = 12.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            AppButton("Cancel", { confirmApply = false }, variant = ButtonVariant.Ghost)
            AppButton("Build review", {
                confirmApply = false
                onApplySeed(seedConfiguration, true)
            }, variant = ButtonVariant.Secondary, isDanger = true, enabled = !seedBusy)
        }
    }
    if (seedBusy || seedStatus != null) {
        AppText(seedStatus ?: "Building…", color = tc().td, fontSize = 9.sp, maxLines = 2, modifier = Modifier.padding(horizontal = 12.dp))
    }
    manualSeedReview?.let { review ->
        Column(Modifier.padding(horizontal = 12.dp), verticalArrangement = Arrangement.spacedBy(3.dp)) {
            AppText(
                "Review regeneration · " + review.newCount + " new · " + review.changedAutoCount +
                    " changed auto · " + review.noLongerInSourceCount + " no longer in source · " +
                    review.editedKeptCount + " edits kept",
                color = tc().ac,
                fontSize = 9.sp,
                maxLines = 3,
            )
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                AppButton("Apply reviewed update", onAcceptSeedReview, variant = ButtonVariant.Primary)
                AppButton("Cancel review", onCancelSeedReview, variant = ButtonVariant.Ghost)
            }
        }
    }
    var selectedInteractionIds by remember(workspaceKey) { mutableStateOf<Set<String>>(emptySet()) }
    LaunchedEffect(focusedManualInteractionId, spec.manualDocument.interactions) {
        if (focusedManualInteractionId != null) {
            val focused = spec.manualDocument.interactions.firstOrNull { it.id == focusedManualInteractionId }
            if (focused != null) {
                selectedInteractionIds = spec.manualDocument.interactions
                    .filter { it.id == focused.id || (focused.groupKey != null && it.groupKey == focused.groupKey) }
                    .map { it.id }
                    .toSet()
            }
        }
    }
    if (guidedPass == null) {
        ManualMessageQueueEditor(
            spec, lifelineIds, entries, anchorEntryIds, workspaceKey, selectedInteractionIds,
            onSelectionChanged = { selectedInteractionIds = it },
            onSpec = onSpec,
            onFixTargets = { guidedPass = beginGuidedTargetPass(spec.manualDocument) },
            onNavigateEvidence = onNavigateEvidence,
            focusedManualInteractionId = focusedManualInteractionId,
            onFocusManualInteraction = onFocusManualInteraction,
        )
    } else {
        GuidedTargetPassCard(
            spec = spec,
            lifelineIds = lifelineIds,
            entries = entries,
            state = guidedPass!!,
            onExit = { guidedPass = null },
            onNavigateEvidence = onNavigateEvidence,
            onChooseTarget = { targetId, kind ->
                val currentState = guidedPass ?: return@GuidedTargetPassCard
                val row = guidedTargetRow(spec.manualDocument, currentState) ?: return@GuidedTargetPassCard
                val ids = row.interactionIds.toSet()
                val nextDocument = spec.manualDocument.copy(
                    interactions = spec.manualDocument.interactions.map { interaction ->
                        if (interaction.id in ids) {
                            interaction.copy(
                                toParticipantId = targetId,
                                kind = kind,
                                authoring = com.indagium.diagram.ManualInteractionAuthoring.EDITED,
                            )
                        } else {
                            interaction
                        }
                    },
                )
                onSpec(spec.copy(manualDocument = nextDocument))
                guidedPass = advanceGuidedTargetPass(nextDocument, currentState)
            },
            onSkip = {
                guidedPass = guidedPass?.let { advanceGuidedTargetPass(spec.manualDocument, it) }
            },
            onCreateLifeline = { id ->
                val clean = id.trim()
                if (clean.isNotEmpty() && lifelineIds.none { it == clean }) {
                    onSpec(
                        spec.copy(
                            components = spec.components + com.indagium.diagram.DiagramComponent(
                                id = clean,
                                displayName = clean,
                                tagIds = setOf(clean),
                                enabled = true,
                            ),
                        ),
                    )
                    val currentState = guidedPass
                    val row = currentState?.let { guidedTargetRow(spec.manualDocument, it) }
                    val targetDocument = row?.let { targetRow ->
                        val ids = targetRow.interactionIds.toSet()
                        spec.manualDocument.copy(
                            interactions = spec.manualDocument.interactions.map { interaction ->
                                if (interaction.id in ids) {
                                    interaction.copy(
                                        toParticipantId = clean,
                                        kind = if (interaction.fromParticipantId == clean) MessageKind.SELF else MessageKind.CALL,
                                        authoring = com.indagium.diagram.ManualInteractionAuthoring.EDITED,
                                    )
                                } else {
                                    interaction
                                }
                            },
                        )
                    }
                    if (currentState != null && targetDocument != null) {
                        onSpec(
                            spec.copy(
                                components = spec.components + com.indagium.diagram.DiagramComponent(
                                    id = clean,
                                    displayName = clean,
                                    tagIds = setOf(clean),
                                    enabled = true,
                                ),
                                manualDocument = targetDocument,
                            ),
                        )
                        guidedPass = advanceGuidedTargetPass(targetDocument, currentState)
                    }
                }
            },
        )
    }
    ManualDocumentAuxEditors(
        spec, lifelineIds, selectedInteractionIds, workspaceKey, onSpec,
        onClearAll = onClearAllManual,
    )
}

/**
 * Compact queue surface. Rows are selectable and expandable, but their order is always the
 * durable authoring order; there is intentionally no pointer-based reorder interaction here.
 */
@Composable
// `focusedManualInteractionId` is plumbed from the caller's real focus state but not yet read in
// this body — see DiagramAuthoringSection's own note above.
@Suppress("UnusedParameter")
private fun ManualMessageQueueEditor(
    spec: SeqDiagramSpec,
    lifelineIds: List<String>,
    entries: List<LogEntry>,
    anchorEntryIds: Set<Int>,
    workspaceKey: String,
    selectedInteractionIds: Set<String>,
    onSelectionChanged: (Set<String>) -> Unit,
    onSpec: (SeqDiagramSpec) -> Unit,
    onFixTargets: () -> Unit,
    onNavigateEvidence: (Int) -> Unit,
    focusedManualInteractionId: String?,
    onFocusManualInteraction: (String?) -> Unit,
) {
    val document = spec.manualDocument
    var expandedRowId by remember(workspaceKey) { mutableStateOf<String?>(null) }
    var filter by remember(workspaceKey) { mutableStateOf(ManualMessageFilter.ALL) }
    var sort by remember(workspaceKey) { mutableStateOf(ManualMessageSort.LOG_ORDER) }
    var query by remember(workspaceKey) { mutableStateOf("") }
    val queue = buildManualMessageQueue(document, filter = filter, query = query, sort = sort)

    SectionHeader(
        "Message queue",
        trailing = {
            val suffix = if (queue.needsTargetCount > 0) {
                " · " + queue.needsTargetCount + " needs target"
            } else {
                ""
            }
            AppText(queue.rows.size.toString() + suffix, color = tc().td, fontSize = 9.sp)
        },
    )
    AppText(
        "Select rows for safe bulk actions. Each row keeps its source evidence and shows the exact From → To endpoint.",
        color = tc().td,
        fontSize = 9.sp,
        maxLines = 2,
        modifier = Modifier.padding(horizontal = 12.dp),
    )
    if (queue.needsTargetCount > 0) {
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            AppText(
                queue.needsTargetCount.toString() + " messages need a target",
                color = DANGER_RED,
                fontSize = 10.sp,
                modifier = Modifier.weight(1f),
            )
            AppButton("Fix these", onFixTargets, variant = ButtonVariant.Secondary)
        }
    }
    InlineField(
        query,
        { query = it },
        "Search messages, lifelines, or evidence row",
        Modifier.fillMaxWidth().padding(horizontal = 12.dp),
        fontSize = 10.sp,
    )
    Row(
        Modifier.padding(horizontal = 12.dp),
        horizontalArrangement = Arrangement.spacedBy(5.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        AppButton(
            "Filter: " + filter.name.lowercase().replace('_', ' '),
            {
                val values = ManualMessageFilter.entries
                filter = values[(values.indexOf(filter) + 1) % values.size]
            },
            variant = ButtonVariant.Secondary,
        )
        AppButton(
            "Sort: " + sort.name.lowercase().replace('_', ' '),
            {
                val values = ManualMessageSort.entries
                sort = values[(values.indexOf(sort) + 1) % values.size]
            },
            variant = ButtonVariant.Ghost,
        )
        if (selectedInteractionIds.isNotEmpty()) {
            AppText(selectedInteractionIds.size.toString() + " selected", color = tc().ac, fontSize = 9.sp)
        }
    }
    if (lifelineIds.isEmpty()) {
        AppText(
            "Add at least one lifeline before creating interactions.",
            color = tc().td,
            fontSize = 10.sp,
            modifier = Modifier.padding(horizontal = 12.dp),
        )
        return
    }
    BoundedScrollBoxDp(maxHeightDp = 9 * SEQUENCE_EDITOR_ROW_HEIGHT.value.toInt()) {
        Column(Modifier.fillMaxWidth()) {
            queue.rows.forEach { row ->
                key(row.id) {
                    ManualMessageQueueRowView(
                        row = row,
                        expanded = expandedRowId == row.id,
                        selected = row.interactionIds.all { it in selectedInteractionIds },
                        onToggleExpanded = {
                            expandedRowId = if (expandedRowId == row.id) null else row.id
                        },
                        onToggleSelected = {
                            val ids = row.interactionIds.toSet()
                            onSelectionChanged(
                                if (ids.all { it in selectedInteractionIds }) {
                                    selectedInteractionIds - ids
                                } else {
                                    selectedInteractionIds + ids
                                },
                            )
                        },
                        lifelineIds = lifelineIds,
                        entries = entries,
                        onNavigateEvidence = onNavigateEvidence,
                        onFocusManualInteraction = onFocusManualInteraction,
                        onChange = { changed ->
                            val ids = row.interactionIds.toSet()
                            onSpec(
                                spec.copy(
                                    manualDocument = document.copy(
                                        interactions = document.interactions.map { current ->
                                            if (current.id !in ids) {
                                                current
                                            } else {
                                                current.copy(
                                                    fromParticipantId = changed.fromParticipantId,
                                                    toParticipantId = changed.toParticipantId,
                                                    operation = changed.operation,
                                                    parameters = changed.parameters,
                                                    result = changed.result,
                                                    label = changed.label,
                                                    kind = changed.kind,
                                                    visibility = changed.visibility,
                                                    authoring = com.indagium.diagram.ManualInteractionAuthoring.EDITED,
                                                )
                                            }
                                        },
                                    ),
                                ),
                            )
                        },
                        onHide = {
                            val ids = row.interactionIds.toSet()
                            onSpec(
                                spec.copy(
                                    manualDocument = document.copy(
                                        interactions = document.interactions.map { current ->
                                            if (current.id in ids) {
                                                current.copy(enabled = false, authoring = com.indagium.diagram.ManualInteractionAuthoring.EDITED)
                                            } else {
                                                current
                                            }
                                        },
                                    ),
                                ),
                            )
                        },
                    )
                }
            }
        }
    }
    if (selectedInteractionIds.isNotEmpty()) {
        ManualMessageBulkActionBar(
            spec = spec,
            document = document,
            selectedInteractionIds = selectedInteractionIds,
            lifelineIds = lifelineIds,
            onSpec = onSpec,
            onClear = { onSelectionChanged(emptySet()) },
        )
    }
    AppButton(
        "Add message",
        {
            val first = lifelineIds.first()
            val interaction = ManualDiagramInteraction(
                id = "manual-" + System.nanoTime(),
                sourceEntryIds = anchorEntryIds,
                fromParticipantId = first,
                toParticipantId = null,
                operation = "operation",
                order = nextManualOrder(document),
                authoring = com.indagium.diagram.ManualInteractionAuthoring.EDITED,
            )
            onSpec(spec.copy(manualDocument = document.copy(interactions = document.interactions + interaction)))
        },
        modifier = Modifier.padding(horizontal = 12.dp),
    )
}

@Composable
private fun ManualMessageBulkActionBar(
    spec: SeqDiagramSpec,
    document: ManualDiagramDocument,
    selectedInteractionIds: Set<String>,
    lifelineIds: List<String>,
    onSpec: (SeqDiagramSpec) -> Unit,
    onClear: () -> Unit,
) {
    var endpointMenu by remember { mutableStateOf<String?>(null) }
    var groupKey by remember { mutableStateOf("message-group") }
    var fragmentId by remember { mutableStateOf("fragment") }
    var fragmentLabel by remember { mutableStateOf("Fragment") }
    var noteText by remember { mutableStateOf("") }
    val selected = document.interactions.filter { it.id in selectedInteractionIds }
    val allHidden = selected.all { !it.enabled }

    fun apply(action: ManualMessageBulkAction) {
        val result = applyManualMessageBulkAction(document, selectedInteractionIds, action)
        if (result.applied) onSpec(spec.copy(manualDocument = result.document))
    }
    Column(
        Modifier.fillMaxWidth().background(tc().abg, CORNER_SM).padding(horizontal = 12.dp, vertical = 5.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(5.dp)) {
            AppText("Bulk actions", color = tc().ac, fontSize = 10.sp, modifier = Modifier.weight(1f))
            AppButton("Esc", onClear, variant = ButtonVariant.Ghost)
        }
        Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            AppButton("Set from", { endpointMenu = if (endpointMenu == "from") null else "from" }, variant = ButtonVariant.Secondary)
            AppButton("Set target", { endpointMenu = if (endpointMenu == "target") null else "target" }, variant = ButtonVariant.Secondary)
            AppButton("Merge", { apply(ManualMessageBulkAction.Merge(groupKey)) }, variant = ButtonVariant.Ghost, enabled = groupKey.isNotBlank())
            AppButton(
                if (allHidden) "Show" else "Hide",
                { apply(if (allHidden) ManualMessageBulkAction.Show else ManualMessageBulkAction.Hide) },
                variant = ButtonVariant.Ghost,
            )
        }
        if (endpointMenu != null) {
            FlowRow(horizontalArrangement = Arrangement.spacedBy(3.dp), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                if (endpointMenu == "target") {
                    AppButton("Needs target", { apply(ManualMessageBulkAction.SetTarget(null)); endpointMenu = null }, variant = ButtonVariant.Ghost)
                }
                lifelineIds.forEach { id ->
                    AppButton(
                        id,
                        {
                            apply(
                                if (endpointMenu == "from") ManualMessageBulkAction.SetSource(id)
                                else ManualMessageBulkAction.SetTarget(id),
                            )
                            endpointMenu = null
                        },
                        variant = ButtonVariant.Ghost,
                    )
                }
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(4.dp), verticalAlignment = Alignment.CenterVertically) {
            InlineField(groupKey, { groupKey = it }, "merge group key", Modifier.weight(1f), fontSize = 9.sp)
            InlineField(fragmentId, { fragmentId = it }, "fragment id", Modifier.weight(1f), fontSize = 9.sp)
        }
        Row(horizontalArrangement = Arrangement.spacedBy(4.dp), verticalAlignment = Alignment.CenterVertically) {
            InlineField(fragmentLabel, { fragmentLabel = it }, "fragment label", Modifier.weight(1f), fontSize = 9.sp)
            AppButton(
                "Group as fragment",
                {
                    apply(
                        ManualMessageBulkAction.GroupAsFragment(
                            com.indagium.diagram.ManualDiagramGroup(fragmentId, fragmentLabel, selectedInteractionIds.toList()),
                        ),
                    )
                },
                variant = ButtonVariant.Ghost,
                enabled = fragmentId.isNotBlank() && fragmentLabel.isNotBlank(),
            )
        }
        Row(horizontalArrangement = Arrangement.spacedBy(4.dp), verticalAlignment = Alignment.CenterVertically) {
            InlineField(noteText, { noteText = it }, "note text", Modifier.weight(1f), fontSize = 9.sp)
            AppButton(
                "Add note",
                {
                    val anchor = selected.lastOrNull()?.id ?: return@AppButton
                    val participant = selected.firstOrNull()?.fromParticipantId ?: return@AppButton
                    apply(
                        ManualMessageBulkAction.AddNote(
                            com.indagium.diagram.ManualDiagramNote(
                                id = "note-" + System.nanoTime(),
                                participantId = participant,
                                afterInteractionId = anchor,
                                text = noteText,
                            ),
                        ),
                    )
                },
                variant = ButtonVariant.Ghost,
                enabled = noteText.isNotBlank() && selected.isNotEmpty(),
            )
        }
    }
}

@Composable
// `entries` is plumbed from the caller's real log entries but not yet read in this body — see
// DiagramAuthoringSection's own note above.
@Suppress("UnusedParameter")
private fun ManualMessageQueueRowView(
    row: ManualMessageQueueRow,
    expanded: Boolean,
    selected: Boolean,
    onToggleExpanded: () -> Unit,
    onToggleSelected: () -> Unit,
    lifelineIds: List<String>,
    entries: List<LogEntry>,
    onNavigateEvidence: (Int) -> Unit,
    onFocusManualInteraction: (String?) -> Unit,
    onChange: (ManualDiagramInteraction) -> Unit,
    onHide: () -> Unit,
) {
    val representative = row.representative
    val stateLabel = when (row.state) {
        ManualMessageState.NEEDS_TARGET -> "needs target"
        ManualMessageState.EDITED -> "edited"
        ManualMessageState.HIDDEN -> "hidden"
        ManualMessageState.AUTO -> "auto"
    }
    Column(
        Modifier.fillMaxWidth()
            .background(if (selected) tc().abg else Color.Transparent, CORNER_SM)
            .onPointerEvent(PointerEventType.Enter) {
                onFocusManualInteraction(row.interactionIds.firstOrNull())
            }
            .onPointerEvent(PointerEventType.Exit) {
                onFocusManualInteraction(null)
            }
            .padding(horizontal = 12.dp, vertical = 3.dp),
        verticalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        Row(
            Modifier.fillMaxWidth().height(SEQUENCE_EDITOR_ROW_HEIGHT),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(5.dp),
        ) {
            ManualRoundToggle(
                tooltip = if (selected) "Deselect message" else "Select message",
                active = selected,
                color = tc().td,
                onClick = onToggleSelected,
            )
            AppText(
                if (row.state == ManualMessageState.NEEDS_TARGET) "╌" else "→",
                color = if (row.state == ManualMessageState.NEEDS_TARGET) DANGER_RED else tc().td,
                fontSize = 12.sp,
            )
            AppText(
                manualMessageTemplate(representative),
                fontSize = 10.sp,
                fontWeight = FontWeight.SemiBold,
                modifier = Modifier.weight(1f),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            QueueEndpointChoice(representative.fromParticipantId, lifelineIds) { selected ->
                onChange(
                    representative.copy(
                        fromParticipantId = selected,
                        kind = manualKindAfterEndpointChange(
                            representative.kind,
                            selected,
                            representative.toParticipantId,
                        ),
                    ),
                )
            }
            AppText("→", color = tc().td, fontSize = 10.sp)
            QueueEndpointChoice(representative.toParticipantId ?: "Needs target", lifelineIds) { selected ->
                onChange(
                    representative.copy(
                        toParticipantId = selected,
                        kind = manualKindAfterEndpointChange(
                            representative.kind,
                            representative.fromParticipantId,
                            selected,
                        ),
                    ),
                )
            }
            if (row.occurrenceCount > 1) AppText(row.occurrenceCount.toString() + "×", color = tc().td, fontSize = 9.sp)
            AppText(stateLabel, color = if (row.state == ManualMessageState.NEEDS_TARGET) DANGER_RED else tc().td, fontSize = 9.sp)
            AppButton(if (expanded) "Close" else "Edit", onToggleExpanded, variant = ButtonVariant.Ghost)
        }
        AppText(
            if (row.state == ManualMessageState.NEEDS_TARGET) {
                "Evidence-backed dashed stub · rows " + row.sourceEntryIds.sorted().joinToString(", ")
            } else {
                "Evidence rows " + row.sourceEntryIds.sorted().joinToString(", ")
            },
            color = tc().td,
            fontSize = 9.sp,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(start = 28.dp),
        )
        if (row.sourceEntryIds.isNotEmpty()) {
            AppButton(
                "Open evidence",
                { row.sourceEntryIds.minOrNull()?.let(onNavigateEvidence) },
                variant = ButtonVariant.Ghost,
                modifier = Modifier.padding(start = 28.dp),
            )
        }
        if (expanded) {
            ManualInteractionCard(
                interaction = representative,
                lifelineIds = lifelineIds,
                onChange = onChange,
                onDelete = onHide,
                onDuplicate = {},
                showActions = false,
            )
        }
    }
}

@Composable
private fun QueueEndpointChoice(
    selected: String,
    lifelineIds: List<String>,
    onSelect: (String) -> Unit,
) {
    var expanded by remember(selected) { mutableStateOf(false) }
    Column(horizontalAlignment = Alignment.End) {
        AppButton(
            selected,
            { expanded = !expanded },
            variant = ButtonVariant.Secondary,
        )
        if (expanded) {
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(3.dp),
                verticalArrangement = Arrangement.spacedBy(3.dp),
            ) {
                lifelineIds.forEach { id ->
                    AppButton(
                        id,
                        { expanded = false; onSelect(id) },
                        variant = if (id == selected) ButtonVariant.Primary else ButtonVariant.Ghost,
                    )
                }
            }
        }
    }
}

@Composable
private fun GuidedTargetPassCard(
    spec: SeqDiagramSpec,
    lifelineIds: List<String>,
    entries: List<LogEntry>,
    state: GuidedTargetPassState,
    onExit: () -> Unit,
    onNavigateEvidence: (Int) -> Unit,
    onChooseTarget: (String, MessageKind) -> Unit,
    onSkip: () -> Unit,
    onCreateLifeline: (String) -> Unit,
) {
    val row = guidedTargetRow(spec.manualDocument, state)
    if (row == null) {
        AppText("All unresolved targets are complete.", color = tc().td, fontSize = 10.sp, modifier = Modifier.padding(horizontal = 12.dp))
        AppButton("Close guided pass", onExit, variant = ButtonVariant.Ghost, modifier = Modifier.padding(horizontal = 12.dp))
        return
    }
    val choices = lifelineIds.take(9)
    val suggestion = suggestManualTarget(
        row.representative,
        entries,
        lifelineIds.map { id -> DiagramParticipant(id, id, ParticipantKind.TAG, tag = id) },
    )
    var selectedTarget by remember(row.id, suggestion?.participantId, choices) {
        mutableStateOf(suggestion?.participantId ?: choices.firstOrNull())
    }
    var newLifeline by remember(row.id) { mutableStateOf("") }
    val context = guidedTargetContext(row, entries)
    val firstEvidenceId = row.sourceEntryIds.minOrNull()
    val currentNumber = state.currentIndex + 1
    val total = state.groupIds.size
    Column(
        Modifier.fillMaxWidth()
            .focusable()
            .onKeyEvent { event ->
                if (event.type != KeyEventType.KeyDown) {
                    false
                } else {
                    when (event.key) {
                        Key.Enter -> {
                            selectedTarget?.let { target ->
                                onChooseTarget(
                                    target,
                                    if (target == row.fromParticipantId) MessageKind.SELF else MessageKind.CALL,
                                )
                            }
                            true
                        }
                        Key.S -> {
                            onSkip()
                            true
                        }
                        Key.Escape -> {
                            onExit()
                            true
                        }
                        Key.One, Key.Two, Key.Three, Key.Four, Key.Five, Key.Six, Key.Seven, Key.Eight, Key.Nine -> {
                            val index = listOf(
                                Key.One, Key.Two, Key.Three, Key.Four, Key.Five,
                                Key.Six, Key.Seven, Key.Eight, Key.Nine,
                            ).indexOf(event.key)
                            choices.getOrNull(index)?.let { selectedTarget = it }
                            true
                        }
                        else -> false
                    }
                }
            }
            .padding(horizontal = 12.dp),
        verticalArrangement = Arrangement.spacedBy(5.dp),
    ) {
        SectionHeader(
            "Guided target pass",
            trailing = { AppText(currentNumber.toString() + " / " + total, color = tc().td, fontSize = 9.sp) },
        )
        AppText(
            row.fromParticipantId + " → Needs target",
            fontSize = 11.sp,
            fontWeight = FontWeight.SemiBold,
        )
        AppText(
            manualMessageTemplate(row.representative) + " · " + row.occurrenceCount + " occurrence(s)",
            color = tc().td,
            fontSize = 10.sp,
            maxLines = 2,
        )
        if (suggestion != null) {
            AppText(
                "Suggested from evidence: " + suggestion.participantId + " (" + suggestion.reason + "). Select and confirm.",
                color = tc().ac,
                fontSize = 9.sp,
                maxLines = 2,
            )
        }
        context.forEach { entry ->
            AppText(
                entry.id.toString() + "  " + entry.ts + "  " + entry.tag + ": " + entry.msg,
                color = if (entry.id in row.sourceEntryIds) tc().ac else tc().td,
                fontSize = 9.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        if (firstEvidenceId != null) {
            AppButton(
                "Open evidence row " + firstEvidenceId,
                { onNavigateEvidence(firstEvidenceId) },
                variant = ButtonVariant.Ghost,
            )
        }
        choices.forEachIndexed { index, id ->
            AppButton(
                (index + 1).toString() + ". " + id + if (id == suggestion?.participantId) " · suggested" else "",
                { selectedTarget = id },
                variant = if (id == selectedTarget) ButtonVariant.Primary else ButtonVariant.Secondary,
                modifier = Modifier.fillMaxWidth(),
            )
        }
        Row(horizontalArrangement = Arrangement.spacedBy(5.dp)) {
            AppButton(
                "Apply target",
                {
                    selectedTarget?.let { target ->
                        onChooseTarget(
                            target,
                            if (target == row.fromParticipantId) MessageKind.SELF else MessageKind.CALL,
                        )
                    }
                },
                variant = ButtonVariant.Primary,
                enabled = selectedTarget != null,
            )
            AppButton(
                "Make self-call",
                { onChooseTarget(row.fromParticipantId, MessageKind.SELF) },
                variant = ButtonVariant.Ghost,
            )
            AppButton("Skip", onSkip, variant = ButtonVariant.Ghost)
            AppButton("Esc", onExit, variant = ButtonVariant.Ghost)
        }
        Row(horizontalArrangement = Arrangement.spacedBy(5.dp), verticalAlignment = Alignment.CenterVertically) {
            InlineField(
                newLifeline,
                { newLifeline = it },
                "new declared lifeline",
                Modifier.weight(1f),
                fontSize = 10.sp,
            )
            AppButton(
                "New lifeline",
                { onCreateLifeline(newLifeline) },
                variant = ButtonVariant.Secondary,
                enabled = newLifeline.trim().isNotEmpty(),
            )
        }
        AppText("Enter apply · 1–9 select · S skip · Esc exit", color = tc().td, fontSize = 9.sp)
    }
}

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

private fun nextManualOrder(document: ManualDiagramDocument): Long =
    (document.interactions.maxOfOrNull { it.order } ?: -1L) + 1L

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
                    val groups = document.groups.map { if (it.id == group.id) it.copy(enabled = !it.enabled) else it }
                    onSpec(spec.copy(manualDocument = document.copy(groups = groups)))
                })
                InlineField(
                    group.label,
                    { value ->
                        val groups = document.groups.map { if (it.id == group.id) it.copy(label = value) else it }
                        onSpec(spec.copy(manualDocument = document.copy(groups = groups)))
                    },
                    "frame label", Modifier.weight(1f), fontSize = 10.sp,
                )
                AppText("${group.interactionIds.size} lines", color = tc().td, fontSize = 9.sp)
                AppButton(
                    "×",
                    { onSpec(spec.copy(manualDocument = document.copy(groups = document.groups.filterNot { it.id == group.id }))) },
                    variant = ButtonVariant.Ghost,
                )
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
                CompactCheckBox(
                    checked = note.enabled,
                    onToggle = {
                        val notes = document.notes.map { if (it.id == note.id) it.copy(enabled = !it.enabled) else it }
                        onSpec(spec.copy(manualDocument = document.copy(notes = notes)))
                    },
                )
                InlineField(
                    note.text,
                    { value ->
                        val notes = document.notes.map { if (it.id == note.id) it.copy(text = value) else it }
                        onSpec(spec.copy(manualDocument = document.copy(notes = notes)))
                    },
                    "note", Modifier.weight(1f), fontSize = 10.sp,
                )
                AppButton(
                    "×",
                    { onSpec(spec.copy(manualDocument = document.copy(notes = document.notes.filterNot { it.id == note.id }))) },
                    variant = ButtonVariant.Ghost,
                )
            }
            LifelineChoice("Participant", note.participantId, lifelineIds) { id ->
                val notes = document.notes.map { if (it.id == note.id) it.copy(participantId = id) else it }
                onSpec(spec.copy(manualDocument = document.copy(notes = notes)))
            }
            InteractionChoice("After", note.afterInteractionId, interactionIds) { id ->
                val notes = document.notes.map { if (it.id == note.id) it.copy(afterInteractionId = id) else it }
                onSpec(spec.copy(manualDocument = document.copy(notes = notes)))
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
                CompactCheckBox(
                    checked = activation.enabled,
                    onToggle = {
                        val activations = document.activations.map { if (it.id == activation.id) it.copy(enabled = !it.enabled) else it }
                        onSpec(spec.copy(manualDocument = document.copy(activations = activations)))
                    },
                )
                AppText("Activation", fontSize = 10.sp, modifier = Modifier.weight(1f))
                AppButton(
                    "×",
                    { onSpec(spec.copy(manualDocument = document.copy(activations = document.activations.filterNot { it.id == activation.id }))) },
                    variant = ButtonVariant.Ghost,
                )
            }
            LifelineChoice("Participant", activation.participantId, lifelineIds) { id ->
                val activations = document.activations.map { if (it.id == activation.id) it.copy(participantId = id) else it }
                onSpec(spec.copy(manualDocument = document.copy(activations = activations)))
            }
            InteractionChoice("Start", activation.startInteractionId, interactionIds) { id ->
                val activations = document.activations.map { if (it.id == activation.id) it.copy(startInteractionId = id) else it }
                onSpec(spec.copy(manualDocument = document.copy(activations = activations)))
            }
            InteractionChoice("End", activation.endInteractionId, interactionIds) { id ->
                val activations = document.activations.map { if (it.id == activation.id) it.copy(endInteractionId = id) else it }
                onSpec(spec.copy(manualDocument = document.copy(activations = activations)))
            }
        }
    }

    if (!confirmClear) {
        AppButton(
            "Clear all interactions", { confirmClear = true },
            variant = ButtonVariant.Ghost, isDanger = true, modifier = Modifier.padding(horizontal = 12.dp),
        )
    } else {
        AppText(
            "This removes interactions, frames, notes, and activations.",
            color = DANGER_RED, fontSize = 9.sp, modifier = Modifier.padding(horizontal = 12.dp),
        )
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
            AppText(
                interaction.operation.ifBlank { "Untitled interaction" },
                fontSize = 11.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f), maxLines = 1,
            )
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
        LifelineChoice("To", interaction.toParticipantId ?: "Needs target", lifelineIds) {
            onChange(interaction.copy(
                toParticipantId = it,
                kind = manualKindAfterEndpointChange(interaction.kind, interaction.fromParticipantId, it),
            ))
        }
        InlineField(interaction.operation, { onChange(interaction.copy(operation = it)) }, "operation", Modifier.fillMaxWidth(), fontSize = 10.sp)
        InlineField(
            interaction.label.orEmpty(), { value -> onChange(interaction.copy(label = value.ifBlank { null })) },
            "custom label (optional)", Modifier.fillMaxWidth(), fontSize = 10.sp,
        )
        InlineField(
            interaction.result.orEmpty(), { value -> onChange(interaction.copy(result = value.ifBlank { null })) },
            "result label (optional)", Modifier.fillMaxWidth(), fontSize = 10.sp,
        )
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

internal fun manualKindAfterEndpointChange(kind: MessageKind, fromParticipantId: String, toParticipantId: String?): MessageKind =
    when {
        toParticipantId == null -> MessageKind.CALL
        fromParticipantId == toParticipantId && kind == MessageKind.CALL -> MessageKind.SELF
        fromParticipantId != toParticipantId && kind == MessageKind.SELF -> MessageKind.CALL
        else -> kind
    }
