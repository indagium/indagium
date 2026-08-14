@file:OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)

package com.indagium.ui

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
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
import com.indagium.diagram.ActivationPolicy
import com.indagium.diagram.DiagramActor
import com.indagium.diagram.DiagramComponent
import com.indagium.diagram.DiagramDialect
import com.indagium.diagram.DiagramParticipantCandidate
import com.indagium.diagram.DiagramRange
import com.indagium.diagram.MAX_CODEC_COMPONENTS
import com.indagium.diagram.MAX_CODEC_TAG_IDS
import com.indagium.diagram.ParticipantKind
import com.indagium.diagram.SeqDiagramSpec
import com.indagium.diagram.displayName
import com.indagium.model.LogTab
import com.indagium.source.LogSourceResolver
import com.indagium.source.SourceIndex
import kotlin.math.roundToInt

// This file owns the workspace's two authoring surfaces. The message queue is always visible
// beside the canvas; less-frequent participant and presentation edits live in Details.

@Composable
internal fun OfflineInspector(spec: SeqDiagramSpec) {
    SectionHeader("Cached diagram")
    AppText(
        "This diagram is viewable from its saved snapshot, but its source log is not open.",
        color = tc().td,
        fontSize = 11.sp,
        maxLines = 3,
        modifier = Modifier.padding(horizontal = 12.dp)
    )
    AppText(
        "Open or relink ${spec.sourceFile ?: "the source log"} to edit, rebuild, save, or attach it.",
        color = tc().td,
        fontSize = 10.sp,
        maxLines = 3,
        modifier = Modifier.padding(horizontal = 12.dp)
    )
}

@Composable
internal fun WorkspaceMessagesPane(
    tab: LogTab,
    state: AppState,
    spec: SeqDiagramSpec,
    preview: com.indagium.diagram.SeqDiagram?,
    selectedEntryIds: Set<Int>,
    onSpec: (SeqDiagramSpec) -> Unit,
    focusedManualInteractionId: String?,
    onFocusManualInteraction: (String?) -> Unit,
    hoveredManualInteractionId: String?,
    onHoverManualInteraction: (String?) -> Unit,
) {
    // The authoring editor must use durable participant identities, never display labels: labels
    // can collide and inferred source labels can disappear on a later rebuild.  Components and
    // actors are the user-owned lifeline set; legacy participants remain available for old notes.
    val authoringLifelines = remember(spec.components, spec.actors, spec.participants, spec.lifelineOrder) {
        val declared = (spec.components.filter { it.id.isNotBlank() }.map { it.id } +
            spec.actors.map { it.id } + spec.participants.map { it.id }).distinct()
        // Keyboard digit shortcuts and the rendered canvas must share the builder's persisted
        // lifeline order; otherwise `1` can target a different participant than the first lane.
        spec.lifelineOrder.filter { it in declared } + declared.filter { it !in spec.lifelineOrder }
    }
    val anchorEntryIds = selectedEntryIds.ifEmpty { preview?.primaryEntryIds.orEmpty() }
    DiagramAuthoringSection(
        spec = spec,
        lifelineIds = authoringLifelines,
        preview = preview,
        entries = tab.logData,
        anchorEntryIds = anchorEntryIds,
        workspaceKey = state.seqDiagrams.activeWorkspaceId ?: tab.id,
        rangeContent = { RangeSection(tab, state, spec, onSpec) },
        onSpec = onSpec,
        // The applyManualSeed(configuration, force) two-arg call shape is fixed by
        // SeqDiagramManualEditor.kt's onApplySeed type (out of this change's scope); `force` is a
        // dead parameter there too and is intentionally not forwarded — see applyManualSeed's own
        // doc for why it was dropped from the coordinator function itself.
        onApplySeed = { configuration, _ -> state.seqDiagrams.applyManualSeed(configuration) },
        onRevertSeed = { state.seqDiagrams.revertManualSeed() },
        onClearAllManual = { onSpec(spec.copy(manualDocument = com.indagium.diagram.ManualDiagramDocument())) },
        onNavigateEvidence = { entryId -> state.navigateToLogLine(tab.id, entryId) },
        canOpenSourceEvidence = { entryId ->
            state.sourceIndex != null && state.resolveForLine(tab.id, entryId).isNotEmpty()
        },
        onOpenSourceEvidence = { entryId -> state.showSourceForLine(tab.id, entryId) },
        focusedManualInteractionId = focusedManualInteractionId,
        onFocusManualInteraction = onFocusManualInteraction,
        hoveredManualInteractionId = hoveredManualInteractionId,
        onHoverManualInteraction = onHoverManualInteraction,
        manualSeedReview = state.seqDiagrams.manualSeedReview,
        onUpdateSeedReview = { state.seqDiagrams.updateManualSeedReview(it) },
        onAcceptSeedReview = { state.seqDiagrams.acceptManualSeedReview() },
        onCancelSeedReview = { state.seqDiagrams.cancelManualSeedReview() },
        canRevertSeed = state.seqDiagrams.canRevertManualSeed,
        seedNeedsConfirmation = state.seqDiagrams.manualSeedNeedsConfirmation,
        seedBusy = state.seqDiagrams.manualSeedBusy,
        seedStatus = state.seqDiagrams.manualSeedStatus,
    )
}

/** The explicit Details surface keeps configuration close at hand without displacing the message
 * queue — the primary authoring loop — from the workspace's default layout. */
@Composable
internal fun WorkspaceDetailsPane(
    tab: LogTab,
    state: AppState,
    spec: SeqDiagramSpec,
    onSpec: (SeqDiagramSpec) -> Unit,
) {
    // The header Scope control opens this explicit secondary sheet; scope editing remains out of
    // the primary queue so it cannot disturb evidence-order authoring.
    RangeSection(tab, state, spec, onSpec)
    Divider()
    ParticipantsSection(tab, state, spec, onSpec)
    Divider()
    ManualPresentationSection(spec, onSpec)
}

// ── Participants ─────────────────────────────────────────────────────────────────────────────
//
// Tags-as-badges, matching the filter panel's own Tags section (FilterPanel.kt:958-973) rather
// than the rejected checkbox-row list. A component owns one or more raw tags; raw tags stay
// visible as the atomic unit everywhere (the pill flow, the picker, a component's member list) so
// grouping never hides provenance. See DiagramModel.kt's `DiagramComponent` doc and SAAD §8.3 for
// why the raw tag — not the component id — remains the routing key downstream.
//
// Model mapping (no model/codec change — see DiagramSpecCodec.kt's componentToMap/validComponents):
//   plain participating tag → DiagramComponent(id = tag, displayName = tag, tagIds = {tag})
//   alias                   → tagIds.size == 1 && displayName != the tag
//   component               → tagIds.size >= 2, a user- or suggestion-supplied displayName

// validSpec (DiagramSpecCodec.kt) enforces these only on *decode*, never on save — so this editor
// is the only thing standing between a bad edit and a note that silently refuses to reopen.
// Aliased straight off the codec's own constants rather than re-declared here: a second copy of
// the numbers would drift the moment either moves, and the direction that drifts silently is the
// one that brings the data loss back.
private const val MAX_UI_COMPONENTS = MAX_CODEC_COMPONENTS
private const val MAX_UI_TAG_IDS = MAX_CODEC_TAG_IDS
private val UNIFIED_LIFELINE_ROW_HEIGHT = 36.dp

/** What the inline "+" tag picker is currently building. A component may own one or more raw
 * tags, and its display name is the lifeline alias. [Component.editingId] is non-null only when
 * the editor was opened from an existing component's own "+" button (adding member tags). */
private sealed interface ComponentDraft {
    data class Component(
        val editingId: String?,
        val tags: Set<String>, val name: String,
        // suppresses re-proposal once the user has typed their own name
        val nameTouched: Boolean,
    ) : ComponentDraft
}

/** Stable per-tag colour for a multi-tag component's pills, so "which tags are in which
 * component" reads at a glance in the top tag list, the component card, and the picker alike.
 * Round-robins through [SEQ_COLORS] by the component's position among enabled multi-tag
 * components — the same convention [DiagramFooterMoreMenu]'s sibling call sites use elsewhere
 * (AppState.nextSequenceColor, IndagiumToolOperations.kt). Plain tags and aliases keep the
 * ordinary accent colour; they own exactly one tag each, so there's nothing to visually group. */
private fun componentColor(component: DiagramComponent?, multiTagComponents: List<DiagramComponent>, tc: ThemeColors): Color {
    if (component == null || component.tagIds.size < 2) return tc.ac
    val idx = multiTagComponents.indexOfFirst { it.id == component.id }
    return if (idx >= 0) SEQ_COLORS[idx % SEQ_COLORS.size] else tc.ac
}

/** Keep numeric controls editable while guaranteeing that every committed value is in the
 * renderer/codec-safe range. The local text buffer also lets a user clear and retype a value
 * without the parent spec immediately replacing the empty intermediate string. */
@Composable
private fun BoundedIntField(
    value: Int,
    range: IntRange,
    modifier: Modifier = Modifier.width(70.dp),
    onValue: (Int) -> Unit,
) {
    var text by remember(value) { mutableStateOf(value.toString()) }
    LaunchedEffect(value) {
        if (text.toIntOrNull() != value) text = value.toString()
    }

    fun commit() {
        val parsed = text.toIntOrNull()
        if (parsed == null) {
            text = value.toString()
        } else {
            val bounded = parsed.coerceIn(range)
            text = bounded.toString()
            onValue(bounded)
        }
    }
    InlineField(
        text,
        { next ->
            text = next
            next.toIntOrNull()?.takeIf { it in range }?.let(onValue)
        },
        placeholder = range.first.toString(),
        modifier = modifier,
        fontSize = 11.sp,
        onSubmit = ::commit,
    )
}

private fun mirrorComponentIds(actor: DiagramActor): Set<String> =
    actor.mirrorComponentIds.ifEmpty { setOfNotNull(actor.mirrorComponentId) }

/** Writes both representations while the singular field remains the compatibility bridge for
 * older builders/notes. New builders consume [mirrorComponentIds]; old ones still mirror the first
 * selected component instead of becoming completely blank when a multi-selection is edited. */
private fun DiagramActor.withMirrorComponentIds(ids: Set<String>): DiagramActor = copy(
    mirrorComponentId = ids.firstOrNull(),
    mirrorComponentIds = ids,
)

/** A component's `displayName` must never reach the spec blank (`validComponents` rejects it, but
 * only on decode — see this section's header). Buffers keystrokes locally and forwards only once
 * non-blank, so backspacing to retype doesn't momentarily write (and risk saving) an invalid name. */
@Composable
private fun BlankGuardedNameField(name: String, placeholder: String, resetKey: Any, modifier: Modifier, onRename: (String) -> Unit) {
    var text by remember(resetKey) { mutableStateOf(name) }
    LaunchedEffect(name) { text = name }
    InlineField(text, { value -> text = value; if (value.isNotBlank()) onRename(value) }, placeholder, modifier, fontSize = 10.sp)
}

@Composable
private fun ParticipantsSection(tab: LogTab, state: AppState, spec: SeqDiagramSpec, onSpec: (SeqDiagramSpec) -> Unit) {
    UnifiedLifelinesSection(tab, state, spec, onSpec)
}

@Composable
private fun UnifiedLifelinesSection(
    tab: LogTab,
    state: AppState,
    spec: SeqDiagramSpec,
    onSpec: (SeqDiagramSpec) -> Unit,
) {
    val tc = tc()
    val candidateState = state.seqDiagrams.candidatePreview
    val candidates = candidateState.valuesOrEmpty
    LaunchedEffect(tab.id, System.identityHashCode(tab.logData), System.identityHashCode(tab.filter), spec.range, spec.options.includeRowsHiddenByFilter) {
        state.seqDiagrams.requestCandidates(tab.id, spec)
    }
    val components = remember(spec.components) { spec.components.filter { it.tagIds.isNotEmpty() } }
    val ownerByTag = remember(components) {
        components.flatMap { component -> component.tagIds.map { tag -> tag to component } }.toMap()
    }
    val multiTagComponents = remember(components) { components.filter { it.tagIds.size > 1 } }
    val packagePrefixes = remember(multiTagComponents) {
        multiTagComponents.mapNotNull { component -> commonPackagePrefix(component.tagIds) }.toSet()
    }
    val processNames = tab.analysis.processNames
    var expanded by remember(tab.id) { mutableStateOf(true) }
    var search by remember(tab.id) { mutableStateOf("") }
    var selectedIds by remember(tab.id) { mutableStateOf(emptySet<String>()) }
    var draft by remember(tab.id) { mutableStateOf<ComponentDraft?>(null) }
    var newActor by remember(tab.id) { mutableStateOf("") }
    var addOpen by remember(tab.id) { mutableStateOf(false) }
    var addActorMode by remember(tab.id) { mutableStateOf(false) }

    val needle = search.trim()
    val legacyActorIds = remember(spec.participants, spec.actors) {
        spec.participants.filter { participant ->
            participant.kind == ParticipantKind.ACTOR && spec.actors.none { it.id == participant.id }
        }.map { it.id }
    }
    val lifelineIds = remember(components, spec.actors, legacyActorIds) {
        (components.map { it.id } + spec.actors.map { it.id } + legacyActorIds).distinct()
    }
    var orderedLifelineIds by remember(spec.lifelineOrder, lifelineIds) {
        mutableStateOf((spec.lifelineOrder.filter { it in lifelineIds } + lifelineIds.filter { it !in spec.lifelineOrder }).distinct())
    }
    val visibleOrderedLifelineIds = remember(orderedLifelineIds, components, spec.actors, spec.participants, needle) {
        orderedLifelineIds.filter { id ->
            if (needle.isBlank()) return@filter true
            components.firstOrNull { it.id == id }?.let { component ->
                return@filter component.displayName.contains(needle, ignoreCase = true) || component.tagIds.any { it.contains(needle, ignoreCase = true) }
            }
            spec.actors.firstOrNull { it.id == id }?.let { actor -> return@filter actor.label.contains(needle, ignoreCase = true) }
            spec.participants.firstOrNull { it.id == id }?.let { participant -> return@filter participant.displayName.contains(needle, ignoreCase = true) }
            false
        }
    }
    val matchingNewTags = remember(candidates, ownerByTag, needle) {
        if (needle.isBlank()) emptyList() else candidates.filter {
            it.tag.contains(needle, ignoreCase = true) && ownerByTag[it.tag] == null
        }
    }
    val totalTagIds = components.sumOf { it.tagIds.size }
    val canAdd = components.size < MAX_UI_COMPONENTS && totalTagIds < MAX_UI_TAG_IDS

    fun updateComponents(next: List<DiagramComponent>, nextSelectedIds: Set<String> = selectedIds) {
        selectedIds = nextSelectedIds
        onSpec(spec.copy(components = next, options = spec.options.copy(includeRowsHiddenByFilter = true)))
    }

    fun addTag(tag: String) {
        if (ownerByTag[tag] != null || !canAdd) return
        val nextOrder = (orderedLifelineIds + tag).distinct()
        orderedLifelineIds = nextOrder
        selectedIds = emptySet()
        onSpec(spec.copy(
            components = components + DiagramComponent(tag, tag, setOf(tag), enabled = true),
            lifelineOrder = nextOrder,
            options = spec.options.copy(includeRowsHiddenByFilter = true),
        ))
    }

    fun deleteComponent(component: DiagramComponent) {
        val next = components.filterNot { it.id == component.id }
        val actors = spec.actors.map { actor ->
            val mirrors = mirrorComponentIds(actor) - component.id
            actor.withMirrorComponentIds(mirrors)
        }
        selectedIds = selectedIds - component.id
        orderedLifelineIds = orderedLifelineIds - component.id
        onSpec(spec.copy(components = next, actors = actors, lifelineOrder = orderedLifelineIds))
    }

    fun mergeSelected() {
        val selected = components.filter { it.id in selectedIds }
        if (selected.size < 2) return
        val primary = selected.first()
        val removedIds = selected.drop(1).map { it.id }.toSet()
        val merged = primary.copy(
            tagIds = selected.flatMap { it.tagIds }.toSet(),
            enabled = selected.any { it.enabled },
        )
        val next = components.map { component ->
            when {
                component.id == primary.id -> merged
                component.id in removedIds -> null
                else -> component
            }
        }.filterNotNull()
        val nextOrder = orderedLifelineIds.filterNot { it in removedIds }
        orderedLifelineIds = nextOrder
        val actors = spec.actors.map { actor ->
            val mirrors = mirrorComponentIds(actor).map { id -> if (id in removedIds) primary.id else id }.toSet()
            actor.withMirrorComponentIds(mirrors)
        }
        selectedIds = setOf(primary.id)
        onSpec(spec.copy(components = next, actors = actors, lifelineOrder = nextOrder))
    }

    fun unmerge(component: DiagramComponent) {
        if (component.tagIds.size < 2) return
        val tags = component.tagIds.sorted()
        val split = tags.mapIndexed { index, tag ->
            if (index == 0) component.copy(tagIds = setOf(tag))
            else DiagramComponent(tag, tag, setOf(tag), enabled = component.enabled)
        }
        val next = components.flatMap { current -> if (current.id == component.id) split else listOf(current) }
        val nextOrder = orderedLifelineIds.flatMap { id -> if (id == component.id) split.map { it.id } else listOf(id) }
        orderedLifelineIds = nextOrder
        selectedIds = split.map { it.id }.toSet()
        onSpec(spec.copy(components = next, lifelineOrder = nextOrder))
    }

    fun commitDraft(component: DiagramComponent) {
        val others = components.filter { it.id != component.id }
            .map { it.copy(tagIds = it.tagIds - component.tagIds) }
            .filter { it.tagIds.isNotEmpty() }
        val next = others + component
        if (next.size > MAX_UI_COMPONENTS || next.sumOf { it.tagIds.size } > MAX_UI_TAG_IDS) return
        selectedIds = setOf(component.id)
        val nextOrder = (orderedLifelineIds + component.id).distinct()
        orderedLifelineIds = nextOrder
        onSpec(spec.copy(components = next, lifelineOrder = nextOrder, options = spec.options.copy(includeRowsHiddenByFilter = true)))
        draft = null
        addOpen = false
    }

    fun commitActor() {
        val label = newActor.trim()
        if (label.isEmpty()) return
        val id = "actor-${System.nanoTime()}"
        onSpec(spec.copy(
            actors = spec.actors + DiagramActor(id, label),
            participants = spec.participants + com.indagium.diagram.DiagramParticipant(id, label, ParticipantKind.ACTOR),
            lifelineOrder = (orderedLifelineIds + id).distinct(),
        ))
        orderedLifelineIds = (orderedLifelineIds + id).distinct()
        newActor = ""
        addOpen = false
    }

    val enabledCount = components.count { it.enabled }
    SectionHeader(
        "Lifelines",
        trailing = {
            AppText("$enabledCount enabled · ${lifelineIds.size} total", color = tc.td, fontSize = 10.sp)
            SquareIconButton(if (addOpen) "×" else "+", 14.sp, {
                expanded = true
                addOpen = !addOpen
                if (!addOpen) draft = null
                else if (!addActorMode) draft = ComponentDraft.Component(null, emptySet(), "", false)
            })
        },
        expanded = expanded,
        onToggle = { expanded = !expanded },
    )
    if (!expanded) return

    InlineField(
        search,
        { search = it },
        "Search tags to add lifelines…",
        Modifier.fillMaxWidth().padding(horizontal = 12.dp),
        fontSize = 10.sp,
        onClear = { search = "" },
    )
    if (candidateState is DiagramCandidateState.Computing) {
        AppText("Refreshing tags…", color = tc.td, fontSize = 10.sp, modifier = Modifier.padding(horizontal = 12.dp))
    } else if (candidateState is DiagramCandidateState.Failed) {
        AppText(candidateState.message, color = DANGER_RED, fontSize = 10.sp, modifier = Modifier.padding(horizontal = 12.dp))
    }
    if (visibleOrderedLifelineIds.isEmpty() && matchingNewTags.isEmpty()) {
        AppText(
            if (needle.isBlank()) "No lifelines yet. Use + to add a lifeline or actor." else "No matching lifelines or tags.",
            color = tc.td, fontSize = 10.sp, modifier = Modifier.padding(horizontal = 12.dp),
        )
    }

    if (addOpen) {
        Column(Modifier.padding(horizontal = 12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            SegmentedControl(listOf("Lifeline", "Actor"), if (addActorMode) setOf(1) else setOf(0), onToggle = { index ->
                addActorMode = index == 1
                draft = if (index == 0) ComponentDraft.Component(null, emptySet(), "", false) else null
            })
            if (addActorMode) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    InlineField(newActor, { newActor = it }, "Actor name…", Modifier.weight(1f), fontSize = 10.sp)
                    AppButton("Add", ::commitActor, enabled = newActor.isNotBlank())
                }
            }
        }
    }

    ReorderableLifelineRows(
        ids = visibleOrderedLifelineIds,
        onCommit = { nextVisibleIds ->
            var nextIndex = 0
            val nextOrder = orderedLifelineIds.map { id -> if (id in visibleOrderedLifelineIds) nextVisibleIds[nextIndex++] else id }
            orderedLifelineIds = nextOrder
            onSpec(spec.copy(lifelineOrder = nextOrder))
        },
    ) { id, modifier, dragging ->
        components.firstOrNull { it.id == id }?.let { component ->
            val isSelected = component.id in selectedIds
            UnifiedLifelineRow(
                modifier = modifier,
                dragging = dragging,
                component = component,
                color = componentColor(component, multiTagComponents, tc),
                selected = isSelected,
                onToggleSelected = { selectedIds = if (isSelected) selectedIds - component.id else selectedIds + component.id },
                onToggleEnabled = { onSpec(spec.copy(components = components.map { if (it.id == component.id) it.copy(enabled = !it.enabled) else it })) },
                onRename = { name -> onSpec(spec.copy(components = components.map { if (it.id == component.id) it.copy(displayName = name) else it })) },
                onUnmerge = { unmerge(component) },
                onRemove = { deleteComponent(component) },
                sourceOwnerTypes = indexedOwnerTypesForLog(state.sourceIndex, component.tagIds, tab.logData),
                onSourceOwners = { owners ->
                    onSpec(spec.copy(components = components.map { if (it.id == component.id) it.copy(sourceOwnerTypes = owners) else it }))
                },
            )
        }
        spec.actors.firstOrNull { it.id == id }?.let { actor ->
            UnifiedActorRow(
                actor = actor,
                selected = actor.id in selectedIds,
                modifier = modifier,
                dragging = dragging,
                onToggleSelected = { selectedIds = if (actor.id in selectedIds) selectedIds - actor.id else selectedIds + actor.id },
                onRename = { label ->
                    onSpec(
                        spec.copy(
                            actors = spec.actors.map { if (it.id == actor.id) it.copy(label = label) else it },
                            participants = spec.participants.map { if (it.id == actor.id) it.copy(label = label) else it },
                        ),
                    )
                },
                onRemove = {
                    val nextOrder = orderedLifelineIds - actor.id
                    orderedLifelineIds = nextOrder
                    onSpec(
                        spec.copy(
                            actors = spec.actors.filterNot { it.id == actor.id },
                            participants = spec.participants.filterNot { it.id == actor.id },
                            lifelineOrder = nextOrder,
                        ),
                    )
                },
            )
        }
        spec.participants.firstOrNull { it.id == id && it.kind == ParticipantKind.ACTOR && spec.actors.none { actor -> actor.id == id } }?.let { actor ->
            UnifiedLegacyActorRow(actor, modifier, dragging, onRemove = {
                val nextOrder = orderedLifelineIds - actor.id
                orderedLifelineIds = nextOrder
                onSpec(spec.copy(participants = spec.participants.filterNot { it.id == actor.id }, lifelineOrder = nextOrder))
            })
        }
    }
    matchingNewTags.forEach { candidate ->
        UnifiedCandidateRow(candidate.tag, candidate.entryCount, onAdd = { addTag(candidate.tag) })
    }
    if (selectedIds.size >= 2) {
        Row(Modifier.padding(horizontal = 12.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            AppButton("Merge selected lifelines", ::mergeSelected, variant = ButtonVariant.Primary)
            AppText("${selectedIds.size} selected", color = tc.td, fontSize = 10.sp, modifier = Modifier.align(Alignment.CenterVertically))
        }
    }
    (draft as? ComponentDraft.Component)?.let { componentDraft ->
        ComponentDraftEditor(componentDraft, candidates, ownerByTag, processNames, packagePrefixes, { draft = it }, ::commitDraft) { draft = null }
    }
}

@Composable
private fun ReorderableLifelineRows(
    ids: List<String>,
    onCommit: (List<String>) -> Unit,
    rowContent: @Composable (String, Modifier, Boolean) -> Unit,
) {
    if (ids.isEmpty()) return
    var dragId by remember { mutableStateOf<String?>(null) }
    var dragStartIndex by remember { mutableStateOf(-1) }
    var dragStartTopY by remember { mutableStateOf(0f) }
    var dragOffsetY by remember { mutableStateOf(0f) }
    var justReleasedId by remember { mutableStateOf<String?>(null) }
    var liveVisualIds by remember { mutableStateOf(emptyList<String>()) }
    val density = LocalDensity.current
    val rowHeightPx = with(density) { UNIFIED_LIFELINE_ROW_HEIGHT.toPx() }
    val currentIds = rememberUpdatedState(ids)
    val currentVisualIds = rememberUpdatedState(liveVisualIds)
    val currentDragId = rememberUpdatedState(dragId)
    LaunchedEffect(ids, dragId, justReleasedId) {
        if (shouldSyncSequenceVisualOrder(dragId, justReleasedId)) liveVisualIds = ids
    }
    LaunchedEffect(justReleasedId) {
        if (justReleasedId != null) {
            kotlinx.coroutines.delay(120)
            justReleasedId = null
        }
    }
    val visualIds = liveVisualIds.takeIf { it.toSet() == ids.toSet() && it.size == ids.size } ?: ids
    BoundedScrollBoxDp(maxHeightDp = 8 * UNIFIED_LIFELINE_ROW_HEIGHT.value.toInt()) {
        Box(
            Modifier.fillMaxWidth()
                .height(UNIFIED_LIFELINE_ROW_HEIGHT * ids.size)
                .pointerInput(ids, rowHeightPx) {
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
                                    downId = ids.getOrNull((change.position.y / rowHeightPx).toInt())
                                }
                                PointerEventType.Move -> {
                                    if (downId != null && !dragging && (change.position - downPos).getDistance() > 8f) {
                                        dragId = downId
                                        dragStartIndex = ids.indexOf(downId)
                                        dragStartTopY = dragStartIndex * rowHeightPx
                                        dragOffsetY = 0f
                                        justReleasedId = null
                                        liveVisualIds = ids
                                        dragging = true
                                    }
                                    if (dragging && dragId != null) {
                                        change.consume()
                                        dragOffsetY = change.position.y - downPos.y
                                        liveVisualIds = sequenceOrderDuringDrag(ids, dragId, dragStartIndex, dragOffsetY, rowHeightPx)
                                    }
                                }
                                PointerEventType.Release -> {
                                    if (dragging && dragId != null) {
                                        val releasedId = currentDragId.value ?: dragId
                                        val releasedOrder = currentVisualIds.value.takeIf { it.isNotEmpty() } ?: currentIds.value
                                        val targetIndex = releasedOrder.indexOf(releasedId)
                                        if (releasedId != null && targetIndex >= 0 && targetIndex != ids.indexOf(releasedId)) {
                                            liveVisualIds = releasedOrder
                                            onCommit(releasedOrder)
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
            ids.forEach { id ->
                key(id) {
                    val isDragging = dragId == id
                    val targetIndex = visualIds.indexOf(id).coerceAtLeast(0)
                    val targetY = targetIndex * rowHeightPx
                    val animatedY by animateFloatAsState(targetY, spring(stiffness = 650f, dampingRatio = 0.86f), label = "lifeline-y-$id")
                    val y = sequenceRenderY(isDragging, justReleasedId == id, dragStartTopY + dragOffsetY, targetY, animatedY)
                    rowContent(
                        id,
                        Modifier.fillMaxWidth()
                            .height(UNIFIED_LIFELINE_ROW_HEIGHT)
                            .offset { IntOffset(0, y.roundToInt()) }
                            .zIndex(if (isDragging) 1f else 0f)
                            .graphicsLayer { if (isDragging) { scaleX = 1.02f; scaleY = 1.02f } },
                        isDragging,
                    )
                }
            }
        }
    }
}

@Composable
private fun UnifiedLifelineRow(
    component: DiagramComponent,
    color: Color,
    selected: Boolean,
    onToggleSelected: () -> Unit,
    onToggleEnabled: () -> Unit,
    onRename: (String) -> Unit,
    onUnmerge: () -> Unit,
    onRemove: () -> Unit,
    sourceOwnerTypes: List<String>,
    onSourceOwners: (Set<String>) -> Unit,
    modifier: Modifier = Modifier,
    dragging: Boolean = false,
) {
    val tc = tc()
    var renaming by remember(component.id) { mutableStateOf(false) }
    HoverBox(
        modifier = modifier.fillMaxWidth(),
        baseBg = if (selected) tc.abg else Color.Transparent,
    ) {
        Row(
            Modifier.fillMaxWidth().height(UNIFIED_LIFELINE_ROW_HEIGHT).padding(horizontal = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            AppText("☷", color = if (dragging) tc.ac else tc.td, fontSize = 12.sp)
            RoundIndicator(active = component.enabled, color = color, onClick = onToggleEnabled)
            RoundIndicator(active = selected, color = tc.td, onClick = onToggleSelected)
            if (renaming) {
                BlankGuardedNameField(component.displayName, "lifeline alias", component.id, Modifier.weight(1f), onRename)
            } else {
                AppText(
                    component.displayName, fontSize = 11.sp, fontWeight = FontWeight.SemiBold,
                    maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f),
                )
            }
            if (component.tagIds.size > 1) AppText("×${component.tagIds.size}", color = tc.td, fontSize = 10.sp)
            SquareIconButton(if (renaming) "✓" else "✎", 12.sp, { renaming = !renaming })
            if (component.tagIds.size > 1) SquareIconButton("↔", 12.sp, onUnmerge)
            SquareIconButton("×", 12.sp, onRemove)
        }
    }
    if (selected && sourceOwnerTypes.isNotEmpty()) {
        SourceOwnerPicker(component.sourceOwnerTypes, sourceOwnerTypes, onSourceOwners)
    }
}

@Composable
private fun UnifiedActorRow(
    actor: DiagramActor,
    selected: Boolean,
    modifier: Modifier,
    dragging: Boolean,
    onToggleSelected: () -> Unit,
    onRename: (String) -> Unit,
    onRemove: () -> Unit,
) {
    val tc = tc()
    var renaming by remember(actor.id) { mutableStateOf(false) }
    HoverBox(modifier = modifier.fillMaxWidth(), baseBg = if (selected) tc.abg else Color.Transparent) {
        Row(
            Modifier.fillMaxWidth().height(UNIFIED_LIFELINE_ROW_HEIGHT).padding(horizontal = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            AppText("☷", color = if (dragging) tc.ac else tc.td, fontSize = 12.sp)
            AppText("●", color = tc.ac, fontSize = 11.sp)
            RoundIndicator(active = selected, color = tc.td, onClick = onToggleSelected)
            if (renaming) {
                InlineField(actor.label, { if (it.isNotBlank()) onRename(it) }, "actor", Modifier.weight(1f), fontSize = 10.sp)
            } else {
                AppText(
                    actor.label, fontSize = 11.sp, fontWeight = FontWeight.SemiBold,
                    maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f),
                )
            }
            SquareIconButton(if (renaming) "✓" else "✎", 12.sp, { renaming = !renaming })
            SquareIconButton("×", 12.sp, onRemove)
        }
    }
}

@Composable
private fun UnifiedLegacyActorRow(
    actor: com.indagium.diagram.DiagramParticipant,
    modifier: Modifier,
    dragging: Boolean,
    onRemove: () -> Unit,
) {
    val tc = tc()
    HoverBox(modifier = modifier.fillMaxWidth()) {
        Row(
            Modifier.fillMaxWidth().height(UNIFIED_LIFELINE_ROW_HEIGHT).padding(horizontal = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            AppText("☷", color = if (dragging) tc.ac else tc.td, fontSize = 12.sp)
            AppText("●", color = tc.ac, fontSize = 11.sp)
            AppText(
                actor.displayName, fontSize = 11.sp, fontWeight = FontWeight.SemiBold,
                maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f),
            )
            SquareIconButton("×", 12.sp, onRemove)
        }
    }
}

@Composable
private fun UnifiedCandidateRow(tag: String, count: Int, onAdd: () -> Unit) {
    val tc = tc()
    HoverBox(modifier = Modifier.fillMaxWidth(), onClick = onAdd) {
        Row(
            Modifier.fillMaxWidth().height(UNIFIED_LIFELINE_ROW_HEIGHT).padding(horizontal = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            AppText("☷", color = tc.td, fontSize = 12.sp)
            RoundIndicator(active = false, color = tc.ac, onClick = {})
            RoundIndicator(active = false, color = tc.td, onClick = {})
            AppText(tag, fontSize = 11.sp, fontFamily = MONO, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
            AppText("$count", color = tc.td, fontSize = 9.sp)
            AppText("+", color = tc.ac, fontSize = 14.sp)
        }
    }
}

@Composable
private fun SourceOwnerPicker(selected: Set<String>, allOwners: List<String>, onChange: (Set<String>) -> Unit) {
    val tc = tc()
    var search by remember { mutableStateOf("") }
    var expanded by remember { mutableStateOf(false) }
    val filtered = remember(allOwners, search) {
        if (search.isBlank()) allOwners else allOwners.filter { it.contains(search.trim(), ignoreCase = true) }
    }
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(5.dp)) {
        AppText(
            if (selected.isEmpty()) "Optional source mapping" else "Source owners (${selected.size})",
            color = tc.td, fontSize = 9.sp, maxLines = 1, modifier = Modifier.weight(1f),
        )
        AppButton(
            when {
                expanded -> "Hide"
                selected.isEmpty() -> "Add mapping"
                else -> "Edit mapping"
            },
            { expanded = !expanded },
            variant = ButtonVariant.Ghost,
        )
    }
    if (!expanded && selected.isNotEmpty()) {
        AppText(
            selected.sorted().joinToString(", "), color = tc.td, fontSize = 9.sp, maxLines = 1,
            overflow = TextOverflow.Ellipsis, modifier = Modifier.fillMaxWidth(),
        )
    }
    if (expanded) {
        AppText(
            "Optional: leave this empty for automatic source-call matching. Add one or more indexed classes only " +
                "when automatic ownership is ambiguous or wrong. A mapping takes precedence for call direction; " +
                "it does not create components or change which log rows are shown.",
            color = tc.td, fontSize = 9.sp, maxLines = 4,
        )
        InlineField(search, { search = it }, "Search indexed owner types…", Modifier.fillMaxWidth(), fontSize = 9.sp, onClear = { search = "" })
        if (selected.isNotEmpty()) {
            AppText(
                "Selected: ${selected.sorted().joinToString(", ")}",
                color = tc.td, fontSize = 9.sp, maxLines = 2, overflow = TextOverflow.Ellipsis,
            )
        }
        if (allOwners.isEmpty()) {
            AppText(
                "No suggestions match this log's tags. Index the relevant source folder and rebuild its index.",
                color = tc.td, fontSize = 9.sp, maxLines = 3,
            )
        } else if (search.isBlank()) {
            // Do not open a long package/class list for every component.  Mappings are optional
            // corrections, not defaults; the user must type a class/package fragment to search.
            // Existing selections remain visible in the compact summary above.
            AppText("Type a class or package fragment to search; no mapping is selected by default.", color = tc.td, fontSize = 9.sp, maxLines = 2)
        } else {
            AppText(
                "Suggestions from this log — nothing is saved until you check a row.",
                color = tc.td, fontSize = 9.sp, maxLines = 2,
            )
            BoundedScrollBoxDp(86) {
                filtered.forEach { owner ->
                    CheckRow(checked = owner in selected, onToggle = {
                        onChange(if (owner in selected) selected - owner else selected + owner)
                    }) {
                        AppText(owner, fontSize = 9.sp, fontFamily = MONO, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                }
            }
        }
    }
}

private fun indexedOwnerTypesForLog(index: SourceIndex?, logTags: Set<String>, entries: List<com.indagium.model.LogEntry>): List<String> {
    if (index == null || logTags.isEmpty() || entries.isEmpty()) return emptyList()
    // Source roots can contain several apps or fixture logs. Use the same tag precedence and
    // generic-match suppression as normal source resolution instead of independently accepting
    // every site's regex match; otherwise weak sites from unrelated indexed trees leak into this
    // picker's owner list whenever they share a tag/message shape with the open log.
    val resolver = LogSourceResolver(index)
    val resolutionLimit = maxOf(index.sites.size, 1)
    return entries.asSequence()
        .filter { it.tag in logTags }
        .flatMap { entry ->
            resolver.resolve(entry.tag, entry.msg, resolutionLimit)
                .asSequence()
                .filter { it.site.tag == entry.tag }
                .map { it.site }
        }
        .flatMap { site ->
            sequence {
                site.owningType?.let { yield(it) }
                site.directCalls.forEach { call -> yield(call.targetOwnerType) }
            }
        }
        .distinct()
        .sorted()
        .toList()
}

/** Inline expander, not a Dialog — the whole point of this surface is that the live preview above
 * makes tag curation tractable (this file's own top-level KDoc), and a modal would cover the
 * canvas and sever that loop. `EntryPickerDialog` also isn't a structural fit: it's typed to
 * `List<ZipLogCandidate>` and built from the very `CheckRow` list this feature replaces. */
@Composable
private fun ComponentDraftEditor(
    draft: ComponentDraft,
    candidates: List<DiagramParticipantCandidate>,
    ownerByTag: Map<String, DiagramComponent>,
    processNames: Map<Int, String>,
    packagePrefixes: Set<String>,
    onDraft: (ComponentDraft) -> Unit,
    onCommit: (DiagramComponent) -> Unit,
    onCancel: () -> Unit,
) {
    val tc = tc()
    var search by remember { mutableStateOf("") }
    val editingId = (draft as? ComponentDraft.Component)?.editingId
    val componentDraft = draft as ComponentDraft.Component
    val draftTags = componentDraft.tags
    val nameValue = componentDraft.name

    Column(
        Modifier.fillMaxWidth().padding(horizontal = 12.dp)
            .background(tc.bg, CORNER_SM).border(1.dp, tc.ac.copy(.4f), CORNER_SM).padding(8.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        InlineField(search, { search = it }, "Search tags…", Modifier.fillMaxWidth(), fontSize = 10.sp, onClear = { search = "" })
        BoundedScrollBoxDp(96) {
            FlowRow(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(4.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                candidates.filter { search.isBlank() || it.tag.contains(search.trim(), ignoreCase = true) }.forEach { candidate ->
                    val owner = ownerByTag[candidate.tag]
                    // Only a MULTI-tag owner is protected: stealing one of its tags could silently
                    // shrink or delete another named component the user already curated. A trivial
                    // single-tag owner (a plain participating tag, or another alias) has no such
                    // side effect — re-picking it here just renames/merges it in place, which is
                    // exactly the "type a tag name and an alias for it" workflow being built.
                    val ownedElsewhere = owner != null && owner.id != editingId && owner.tagIds.size > 1
                    val inDraft = candidate.tag in draftTags
                    val (label, _) = displayTagForPrefix(candidate.tag, packagePrefixes)
                    TagPill(
                        label,
                        if (inDraft) tc.ac else tc.td,
                        trailing = "${candidate.entryCount}",
                        active = inDraft,
                        tooltip = if (ownedElsewhere) "${candidate.tag} — already in \"${owner.displayName}\"" else candidate.tag,
                        onClick = {
                            if (ownedElsewhere) return@TagPill
                            onDraft(componentDraft.copy(tags = if (inDraft) draftTags - candidate.tag else draftTags + candidate.tag))
                        },
                    )
                }
            }
        }
        val typed = search.trim()
        if (typed.isNotEmpty() && candidates.none { it.tag.equals(typed, ignoreCase = true) }) {
            // A tag outside the resolved range can still be named here — matches the "choose/
            // add/type" ask verbatim. Guarded the same way as a picked pill: never silently steal
            // from a multi-tag component.
            val typedOwnedElsewhere = ownerByTag[typed]?.let { it.id != editingId && it.tagIds.size > 1 } == true
            if (!typedOwnedElsewhere) {
                LabelIconButton("+ use \"$typed\"", 10.sp, {
                    onDraft(componentDraft.copy(tags = draftTags + typed))
                    search = ""
                })
            }
        }
        InlineField(
            nameValue,
            { value ->
                onDraft(componentDraft.copy(name = value, nameTouched = true))
            },
            "Lifeline name", Modifier.fillMaxWidth(), fontSize = 10.sp,
        )
        // Suggestions are never inferred or injected silently, matching the interaction-rule Add
        // buttons' own convention (this file's DiagramRulesEditor) — an explicit "use" click only.
        if (!componentDraft.nameTouched) {
            val suggestion = proposeComponentName(
                tags = draftTags,
                pidsByTag = candidates.associate { it.tag to it.pids },
                processNames = processNames,
            )
            if (suggestion != null) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(5.dp)) {
                    AppText("Suggested: $suggestion", color = tc.td, fontSize = 10.sp, modifier = Modifier.weight(1f))
                    LabelIconButton("use", 10.sp, onClick = { onDraft(componentDraft.copy(name = suggestion, nameTouched = true)) })
                }
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            val canCommit = draftTags.isNotEmpty() && nameValue.isNotBlank()
            val actionLabel = when {
                editingId != null -> "Add tags"
                else -> "Add lifeline"
            }
            AppButton(actionLabel, {
                val id = editingId ?: draftTags.singleOrNull() ?: "cmp-${java.util.UUID.randomUUID()}"
                // Editing an existing (possibly disabled) component must not silently re-enable
                // it just because tags were added — a new alias/component defaults to enabled,
                // matching toggleTag's own convention for a freshly created record.
                val existing = editingId?.let { eid -> ownerByTag.values.find { it.id == eid } }
                val enabled = existing?.enabled ?: true
                onCommit(DiagramComponent(id, nameValue.trim(), draftTags, enabled = enabled, sourceOwnerTypes = existing?.sourceOwnerTypes.orEmpty()))
            }, variant = ButtonVariant.Primary, enabled = canCommit)
            AppButton("Cancel", onCancel, variant = ButtonVariant.Ghost)
        }
    }
}

// ── Range ────────────────────────────────────────────────────────────────────────────────────

private enum class RangeEditorMode { SELECTION, WHOLE_VIEW, TIME }

@Composable
private fun RangeSection(tab: LogTab, state: AppState, spec: SeqDiagramSpec, onSpec: (SeqDiagramSpec) -> Unit) {
    val tc = tc()
    val range = spec.range
    val workspaceKey = state.seqDiagrams.activeWorkspaceId ?: tab.id
    var expanded by remember(workspaceKey) { mutableStateOf(true) }
    var editorMode by remember(workspaceKey) { mutableStateOf(rangeEditorMode(range)) }
    var selectionFrom by remember(workspaceKey) { mutableStateOf((range as? DiagramRange.Ids)?.from?.toString().orEmpty()) }
    var selectionTo by remember(workspaceKey) { mutableStateOf((range as? DiagramRange.Ids)?.to?.toString().orEmpty()) }
    var timeFrom by remember(workspaceKey) { mutableStateOf((range as? DiagramRange.Time)?.fromTs.orEmpty()) }
    var timeTo by remember(workspaceKey) { mutableStateOf((range as? DiagramRange.Time)?.toTs.orEmpty()) }
    var selectionError by remember(workspaceKey) { mutableStateOf<String?>(null) }
    var timeError by remember(workspaceKey) { mutableStateOf<String?>(null) }

    val rangeSummary = when (range) {
        is DiagramRange.Ids -> {
            val count = if (range.selectedIds.isNotEmpty()) range.selectedIds.size
            else tab.logData.count { it.id in minOf(range.from, range.to)..maxOf(range.from, range.to) }
            "$count rows · ${range.from}–${range.to}"
        }
        is DiagramRange.Time -> "time · ${range.fromTs}–${range.toTs}"
        is DiagramRange.SeqGroupRef -> "sequence group · ${range.gid}"
        is DiagramRange.VisibleView -> "whole view · ${tab.logData.size} rows"
    }
    // Switching the presentation mode alone must not rewrite the persisted range. This keeps the
    // preview stable while the user chooses whether to edit its bounds as rows or timestamps.
    LaunchedEffect(range) {
        editorMode = rangeEditorMode(range)
        when (range) {
            is DiagramRange.Ids -> {
                selectionFrom = range.from.toString()
                selectionTo = range.to.toString()
            }
            is DiagramRange.Time -> {
                timeFrom = range.fromTs
                timeTo = range.toTs
            }
            else -> Unit
        }
        selectionError = null
        timeError = null
    }

    fun idsForStoredRange(): Set<Int> = when (val stored = spec.range) {
        is DiagramRange.Ids -> if (stored.selectedIds.isNotEmpty()) {
            stored.selectedIds
        } else {
            tab.logData.filter { it.id in minOf(stored.from, stored.to)..maxOf(stored.from, stored.to) }.map { it.id }.toSet()
        }
        is DiagramRange.Time -> rowsForTimeRange(tab, stored.fromTs, stored.toTs).selectedIds.toSet()
        else -> tab.selected
    }

    fun updateSelection(fromText: String, toText: String) {
        val from = fromText.toIntOrNull()
        val to = toText.toIntOrNull()
        if (from == null || to == null) {
            selectionError = "Enter numeric start and end row IDs."
            return
        }
        val low = minOf(from, to)
        val high = maxOf(from, to)
        val ids = tab.logData.filter { it.id in low..high }.map { it.id }
        if (ids.isEmpty()) {
            selectionError = "No log rows exist in that ID range."
            return
        }
        selectionError = null
        editorMode = RangeEditorMode.SELECTION
        state.setSelectedRows(tab.id, ids)
        onSpec(spec.copy(range = DiagramRange.Ids(from, to)))
    }

    fun updateTime(fromText: String, toText: String) {
        val result = rowsForTimeRange(tab, fromText, toText)
        timeError = result.error
        if (result.error != null) return
        editorMode = RangeEditorMode.TIME
        state.setSelectedRows(tab.id, result.selectedIds)
        onSpec(spec.copy(range = DiagramRange.Time(fromText, toText)))
    }

    // Replace the simple header with a collapsible summary once all state used to derive it exists.
    SectionHeader(
        "Scope / Range",
        trailing = { AppText(rangeSummary, color = tc.td, fontSize = 9.sp, maxLines = 1, overflow = TextOverflow.Ellipsis) },
        expanded = expanded,
        onToggle = { expanded = !expanded },
    )
    if (!expanded) return
    val rangeSelected = when (editorMode) {
        RangeEditorMode.SELECTION -> setOf(0)
        RangeEditorMode.WHOLE_VIEW -> setOf(1)
        RangeEditorMode.TIME -> setOf(2)
    }
    SegmentedControl(
        listOf("Selection", "Whole view", "Time"), rangeSelected,
        onToggle = { idx ->
            when (idx) {
                0 -> {
                    editorMode = RangeEditorMode.SELECTION
                    val selected = tab.selected.ifEmpty { idsForStoredRange() }
                    selectionRangeForRows(tab, selected)?.let {
                        selectionFrom = it.from.toString()
                        selectionTo = it.to.toString()
                        selectionError = null
                        if (tab.selected.isEmpty()) state.setSelectedRows(tab.id, selected.toList())
                    }
                }
                1 -> {
                    editorMode = RangeEditorMode.WHOLE_VIEW
                    onSpec(spec.copy(range = DiagramRange.VisibleView))
                }
                else -> {
                    editorMode = RangeEditorMode.TIME
                    val seeded = timeRangeForRows(tab, tab.selected.ifEmpty { idsForStoredRange() })
                    timeFrom = seeded?.fromTs.orEmpty()
                    timeTo = seeded?.toTs.orEmpty()
                    timeError = null
                }
            }
        },
        modifier = Modifier.padding(horizontal = 12.dp),
    )
    when (editorMode) {
        RangeEditorMode.SELECTION -> {
            Row(Modifier.padding(horizontal = 12.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                InlineField(selectionFrom, { value ->
                    selectionFrom = value
                    updateSelection(value, selectionTo)
                }, "from row", Modifier.weight(1f), fontSize = 11.sp)
                InlineField(selectionTo, { value ->
                    selectionTo = value
                    updateSelection(selectionFrom, value)
                }, "to row", Modifier.weight(1f), fontSize = 11.sp)
            }
            AppText(
                "Rows ${selectionFrom.ifBlank { "—" }}–${selectionTo.ifBlank { "—" }} (inclusive) · edit either bound to resync selection",
                color = tc.td, fontSize = 10.sp, maxLines = 2, modifier = Modifier.padding(horizontal = 12.dp),
            )
        }
        RangeEditorMode.WHOLE_VIEW -> AppText(
            "All rows in the log range; choose a tag above to include its messages.",
            color = tc.td, fontSize = 11.sp, maxLines = 2, modifier = Modifier.padding(horizontal = 12.dp),
        )
        RangeEditorMode.TIME -> {
            Row(Modifier.padding(horizontal = 12.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                InlineField(timeFrom, { value ->
                    timeFrom = value
                    updateTime(value, timeTo)
                }, "from HH:MM:SS", Modifier.weight(1f), fontSize = 11.sp)
                InlineField(timeTo, { value ->
                    timeTo = value
                    updateTime(timeFrom, value)
                }, "to HH:MM:SS", Modifier.weight(1f), fontSize = 11.sp)
            }
            AppText(
                "Time bounds resolve to the nearest log rows; edit either bound to resync selection.",
                color = tc.td, fontSize = 10.sp, maxLines = 2, modifier = Modifier.padding(horizontal = 12.dp),
            )
        }
    }
    selectionError?.let { AppText(it, color = DANGER_RED, fontSize = 10.sp, maxLines = 2, modifier = Modifier.padding(horizontal = 12.dp)) }
    timeError?.let { AppText(it, color = DANGER_RED, fontSize = 10.sp, maxLines = 2, modifier = Modifier.padding(horizontal = 12.dp)) }
}

private fun rangeEditorMode(range: DiagramRange): RangeEditorMode = when (range) {
    is DiagramRange.Ids -> RangeEditorMode.SELECTION
    is DiagramRange.VisibleView -> RangeEditorMode.WHOLE_VIEW
    is DiagramRange.Time -> RangeEditorMode.TIME
    is DiagramRange.SeqGroupRef -> RangeEditorMode.SELECTION
}

// ── Options ──────────────────────────────────────────────────────────────────────────────────

/** Presentation controls audited against ManualDiagramBuilder and the shared renderer. Inferred
 * filtering, repeat collapsing, source labels, and generated error notes are intentionally absent
 * here because the manual document is already the semantic source of truth. */
@Composable
private fun ManualPresentationSection(spec: SeqDiagramSpec, onSpec: (SeqDiagramSpec) -> Unit) {
    val o = spec.options
    SectionHeader("Presentation")
    CheckRow(checked = o.activationPolicy != ActivationPolicy.NONE, onToggle = {
        onSpec(spec.copy(options = o.copy(
            activationPolicy = if (o.activationPolicy == ActivationPolicy.NONE) ActivationPolicy.EVIDENCE_BACKED else ActivationPolicy.NONE,
        )))
    }) { AppText("Show activation spans", fontSize = 11.sp) }
    AppText(
        "Interactions are authoritative. Only the settings below affect their build or shared canvas rendering.",
        color = tc().td, fontSize = 10.sp, maxLines = 3, modifier = Modifier.padding(horizontal = 12.dp),
    )
    NumericSettingRow("Message label chars", o.labelMaxChars, 10..400) {
        onSpec(spec.copy(options = o.copy(labelMaxChars = it)))
    }
    NumericSettingRow("Label lines", o.labelMaxLines, 1..8) {
        onSpec(spec.copy(options = o.copy(labelMaxLines = it)))
    }
    NumericSettingRow("Lifeline label chars", o.participantLabelMaxChars, 10..120) {
        onSpec(spec.copy(options = o.copy(participantLabelMaxChars = it)))
    }
    NumericSettingRow("Lifeline label lines", o.participantLabelMaxLines, 1..4) {
        onSpec(spec.copy(options = o.copy(participantLabelMaxLines = it)))
    }
    Row(
        Modifier.padding(horizontal = 12.dp),
        horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically,
    ) {
        AppText("Format", fontSize = 11.sp)
        SegmentedControl(
            listOf("Mermaid", "PlantUML"),
            setOf(if (spec.dialect == DiagramDialect.MERMAID) 0 else 1),
            onToggle = { idx -> onSpec(spec.copy(dialect = if (idx == 0) DiagramDialect.MERMAID else DiagramDialect.PLANTUML)) },
        )
    }
    Spacer(Modifier.height(8.dp))
}

@Composable
private fun NumericSettingRow(label: String, value: Int, range: IntRange, onValue: (Int) -> Unit) {
    Row(
        Modifier.fillMaxWidth().padding(horizontal = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        AppText(label, fontSize = 11.sp, modifier = Modifier.weight(1f))
        BoundedIntField(value, range, onValue = onValue)
    }
}
