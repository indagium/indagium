@file:OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)

package com.indagium.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.indagium.diagram.ActivationPolicy
import com.indagium.diagram.ArrowMode
import com.indagium.diagram.MAX_CODEC_COMPONENTS
import com.indagium.diagram.MAX_CODEC_TAG_IDS
import com.indagium.diagram.DiagramActor
import com.indagium.diagram.DiagramComponent
import com.indagium.diagram.DiagramDialect
import com.indagium.diagram.DiagramParticipantCandidate
import com.indagium.diagram.DiagramRange
import com.indagium.diagram.DiagramRuleEndpoint
import com.indagium.diagram.LabelSource
import com.indagium.diagram.MirrorDirection
import com.indagium.diagram.ParticipantKind
import com.indagium.diagram.SeqDiagramSpec
import com.indagium.diagram.UnmappedTagPolicy
import com.indagium.diagram.displayName
import com.indagium.model.LogTab
import com.indagium.source.LogSourceResolver
import com.indagium.source.SourceIndex

// This is the inspector half of the sequence-diagram workspace, split out of what used to be
// SeqDiagramDialog.kt (now ui/SeqDiagramWorkspace.kt) purely for file size — see that file's
// SeqDiagramWorkspace() for the surface that hosts this panel and DiagramPreviewPane for the
// canvas beside it. Same package, so cross-file calls below need no imports.

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
        "Open or relink ${spec.sourceFile ?: "the source log"} to edit, regenerate, save, or attach it.",
        color = tc().td,
        fontSize = 10.sp,
        maxLines = 3,
        modifier = Modifier.padding(horizontal = 12.dp)
    )
}

@Composable
internal fun WorkspaceInspector(
    tab: LogTab,
    state: AppState,
    spec: SeqDiagramSpec,
    preview: com.indagium.diagram.SeqDiagram?,
    selectedEntryIds: Set<Int>,
    onSpec: (SeqDiagramSpec) -> Unit,
) {
    // The authoring editor must use durable participant identities, never display labels: labels
    // can collide and inferred source labels can disappear on a later rebuild.  Components and
    // actors are the user-owned lifeline set; legacy participants remain available for old notes.
    val authoringLifelines = remember(spec.components, spec.actors, spec.participants) {
        (spec.components.filter { it.id.isNotBlank() }.map { it.id } +
            spec.actors.map { it.id } + spec.participants.map { it.id }).distinct()
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
        onApplySeed = { configuration, force -> state.seqDiagrams.applyManualSeed(configuration, force) },
        onRevertSeed = { state.seqDiagrams.revertManualSeed() },
        onClearAllManual = { onSpec(spec.copy(manualDocument = com.indagium.diagram.ManualDiagramDocument())) },
        canRevertSeed = state.seqDiagrams.canRevertManualSeed,
        seedNeedsConfirmation = state.seqDiagrams.manualSeedNeedsConfirmation,
        seedBusy = state.seqDiagrams.manualSeedBusy,
        seedStatus = state.seqDiagrams.manualSeedStatus,
    )
    Divider()
    if (spec.authoringMode != com.indagium.diagram.DiagramAuthoringMode.MANUAL) {
        RangeSection(tab, state, spec, onSpec)
        Divider()
    }
    ParticipantsSection(tab, state, spec, onSpec)
    if (spec.authoringMode != com.indagium.diagram.DiagramAuthoringMode.MANUAL) {
        Divider()
        ModeSection(tab, spec, onSpec)
    }
    Divider()
    OptionsSection(state, spec, onSpec, manualMode = spec.authoringMode == com.indagium.diagram.DiagramAuthoringMode.MANUAL)
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

/** What the inline "Add alias"/"Add component"/"+" tag picker is currently building.  Alias is
 * always exactly one tag (its own name is the user's words, not a merge); Component supports any
 * number.  [Component.editingId] is non-null only when the editor was opened from an existing
 * component's own "+" button (adding member tags), as opposed to a fresh "Add component" click. */
private sealed interface ComponentDraft {
    data class Alias(val tag: String, val name: String) : ComponentDraft
    data class Component(
        val editingId: String?,
        val tags: Set<String>, val name: String,
        val nameTouched: Boolean, // suppresses re-proposal once the user has typed their own name
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

@Suppress("UNUSED_FUNCTION")
@Composable
private fun LegacyParticipantsSection(tab: LogTab, state: AppState, spec: SeqDiagramSpec, onSpec: (SeqDiagramSpec) -> Unit) {
    val tc = tc()
    val candidateState = state.seqDiagrams.candidatePreview
    val candidates = candidateState.valuesOrEmpty
    // Source-view changes invalidate tiers, but title/component toggles don't.  The coordinator
    // does the expensive range scan in its cancellable IO lane.
    LaunchedEffect(tab.id, System.identityHashCode(tab.logData), System.identityHashCode(tab.filter), spec.range, spec.options.includeRowsHiddenByFilter) {
        state.seqDiagrams.requestCandidates(tab.id, spec)
    }

    // EMPTY_COMPONENT_ID (SeqDiagramCoordinator's brand-new-workspace sentinel) has empty tagIds
    // and never a display identity of its own — filtering by tagIds is enough to exclude it
    // everywhere below without needing that private constant.
    val realComponents = remember(spec.components) { spec.components.filter { it.tagIds.isNotEmpty() } }
    val multiTagComponents = remember(realComponents) { realComponents.filter { it.tagIds.size > 1 } }
    // Plain single-tag components are the default participant records, not user-defined
    // components. Keep them in Active tags; showing a card for each one would expose a source
    // mapping control before the user has chosen to create a mapping/grouping.
    // Default one-tag records are represented by the compact Active tags pills. Large cards are
    // reserved for grouped lifelines and explicit source mappings; aliases already have their own
    // compact section above and therefore do not get duplicated here.
    val componentCards = realComponents.filter { component ->
        component.tagIds.size > 1 || component.sourceOwnerTypes.isNotEmpty()
    }
    val ownerByTag = remember(realComponents) {
        realComponents.flatMap { component -> component.tagIds.map { it to component } }.toMap()
    }
    // A component built from tags that already share a package prefix is worth shortening
    // everywhere that tag reappears (the pill list, its own card, the picker) — the same
    // shortening the filter panel applies for its own user-picked prefixes (displayTagForPrefix).
    val packagePrefixes = remember(multiTagComponents) {
        multiTagComponents.mapNotNull { commonPackagePrefix(it.tagIds) }.toSet()
    }
    val processNames = tab.analysis.processNames
    // Fix for the wipe bug: keying on spec.components/spec.actors (the objects every edit
    // rewrites) reset these on every toggle and discarded in-progress typing. Keyed on tab.id only.
    var draft by remember(tab.id) { mutableStateOf<ComponentDraft?>(null) }
    var tagSearch by remember(tab.id) { mutableStateOf("") }
    var tagsExpanded by remember(tab.id) { mutableStateOf(true) }
    var aliasesExpanded by remember(tab.id) { mutableStateOf(true) }
    var componentsExpanded by remember(tab.id) { mutableStateOf(true) }
    var actorsExpanded by remember(tab.id) { mutableStateOf(spec.actors.isNotEmpty()) }
    var newActor by remember(tab.id) { mutableStateOf("") }

    val totalTagIds = realComponents.sumOf { it.tagIds.size }
    val atComponentCap = realComponents.size >= MAX_UI_COMPONENTS
    val atTagCap = totalTagIds >= MAX_UI_TAG_IDS

    fun toggleTag(tag: String, enabled: Boolean) {
        val owner = ownerByTag[tag]
        val components = if (owner == null) {
            realComponents + DiagramComponent(tag, tag, setOf(tag), enabled)
        } else {
            realComponents.map { if (it.id == owner.id) it.copy(enabled = enabled) else it }
        }
        // Searching the inspector is the explicit opt-in for a tag. Once selected, its rows must
        // remain eligible even when the main log filter currently hides that tag.
        onSpec(spec.copy(components = components, options = spec.options.copy(includeRowsHiddenByFilter = true)))
    }

    // The single place a draft becomes a real DiagramComponent. Stripping the committed tags out
    // of every OTHER component (rather than trusting the picker alone) is what keeps "a tag
    // belongs to at most one component" true by construction, including for the free-typed "+use"
    // path the picker can't pre-validate against. The cap check is authoritative — the Add
    // buttons below only disable the common path; this is what can never be bypassed.
    fun commitDraft(component: DiagramComponent) {
        val others = realComponents.filter { it.id != component.id }
            .map { it.copy(tagIds = it.tagIds - component.tagIds) }
            .filter { it.tagIds.isNotEmpty() }
        val merged = others + component
        if (merged.size > MAX_UI_COMPONENTS || merged.sumOf { it.tagIds.size } > MAX_UI_TAG_IDS) return
        onSpec(spec.copy(components = merged))
        draft = null
    }

    fun renameComponent(componentId: String, name: String) {
        onSpec(spec.copy(components = spec.components.map { if (it.id == componentId) it.copy(displayName = name) else it }))
    }

    fun deleteComponent(component: DiagramComponent) {
        onSpec(
            spec.copy(
                components = spec.components.filter { it.tagIds.isNotEmpty() && it.id != component.id },
                actors = spec.actors.map {
                    if (component.id in mirrorComponentIds(it)) it.withMirrorComponentIds(mirrorComponentIds(it) - component.id) else it
                },
            ),
        )
    }

    // Removing the second-to-last tag demotes the record to a single-tag one — re-keyed to
    // `id = tag` to match every other single-tag record's id convention (sourceInteractionResolver's
    // matcher leans on that stability, per this section's own model-mapping note). Removing the
    // last tag deletes it outright. Either way, an actor mirroring the old id is repointed/cleared
    // rather than left dangling.
    fun removeComponentTag(component: DiagramComponent, tag: String) {
        val remaining = component.tagIds - tag
        if (remaining.isEmpty()) {
            deleteComponent(component)
            return
        }
        val converted = if (remaining.size == 1) component.copy(id = remaining.single(), tagIds = remaining) else component.copy(tagIds = remaining)
        val components = realComponents.map { if (it.id == component.id) converted else it }
        val actors = if (converted.id != component.id) {
            spec.actors.map {
                val mirrors = mirrorComponentIds(it)
                if (component.id in mirrors) it.withMirrorComponentIds((mirrors - component.id) + converted.id) else it
            }
        } else {
            spec.actors
        }
        onSpec(spec.copy(components = components, actors = actors))
    }

    // ── Active tags ──────────────────────────────────────────────────────────────────────────
    val enabledCount = candidates.count { ownerByTag[it.tag]?.enabled == true }
    SectionHeader(
        "Active tags",
        trailing = { AppText("$enabledCount of ${candidates.size}", color = tc.td, fontSize = 10.sp) },
        expanded = tagsExpanded,
        onToggle = { tagsExpanded = !tagsExpanded },
    )
    if (tagsExpanded) {
        InlineField(
            tagSearch, { tagSearch = it }, "Filter tags…",
            Modifier.fillMaxWidth().padding(horizontal = 12.dp), fontSize = 10.sp,
            onClear = { tagSearch = "" },
        )
        if (candidateState is DiagramCandidateState.Computing) {
            AppText("Refreshing tag tiers…", color = tc.td, fontSize = 10.sp, modifier = Modifier.padding(horizontal = 12.dp))
        } else if (candidateState is DiagramCandidateState.Failed) {
            AppText(candidateState.message, color = DANGER_RED, fontSize = 10.sp, modifier = Modifier.padding(horizontal = 12.dp))
        }
        val filteredCandidates = remember(candidates, tagSearch) {
            if (tagSearch.isBlank()) candidates else candidates.filter { it.tag.contains(tagSearch.trim(), ignoreCase = true) }
        }
        val visibleCandidates = if (tagSearch.isBlank()) {
            filteredCandidates.filter { ownerByTag[it.tag]?.enabled == true }
        } else {
            filteredCandidates
        }
        if (visibleCandidates.isEmpty()) {
            AppText(
                if (tagSearch.isBlank()) "No active tags. Search to add another tag." else "No matching tags.",
                color = tc.td, fontSize = 10.sp, modifier = Modifier.padding(horizontal = 12.dp),
            )
        }
        DiagramTagPills(visibleCandidates, ownerByTag, multiTagComponents, packagePrefixes, tc) { tag ->
            toggleTag(tag, ownerByTag[tag]?.enabled != true)
        }
    }

    Divider()

    // ── Aliases ──────────────────────────────────────────────────────────────────────────────
    val aliasComponents = realComponents.filter { it.tagIds.size == 1 && it.displayName != it.tagIds.single() }
    SectionHeader(
        "Aliases",
        trailing = { AppText("${aliasComponents.size}", color = tc.td, fontSize = 10.sp) },
        expanded = aliasesExpanded,
        onToggle = { aliasesExpanded = !aliasesExpanded },
    )
    if (aliasesExpanded) {
        aliasComponents.forEach { alias ->
            AliasRow(
                alias,
                indexedOwnerTypesForLog(state.sourceIndex, alias.tagIds, tab.logData),
                onRename = { renameComponent(alias.id, it) },
                onSourceOwners = { owners -> onSpec(spec.copy(components = spec.components.map { if (it.id == alias.id) it.copy(sourceOwnerTypes = owners) else it })) },
                onRemove = { deleteComponent(alias) },
            )
        }
        Row(Modifier.padding(horizontal = 12.dp), horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
            AppButton("Add alias", { draft = ComponentDraft.Alias("", "") }, variant = ButtonVariant.Ghost, enabled = !atComponentCap && !atTagCap)
            if (atComponentCap) AppText("Component limit reached (${MAX_UI_COMPONENTS}).", color = tc.td, fontSize = 9.sp)
            else if (atTagCap) AppText("Tag limit reached (${MAX_UI_TAG_IDS}).", color = tc.td, fontSize = 9.sp)
        }
        (draft as? ComponentDraft.Alias)?.let { aliasDraft ->
            ComponentDraftEditor(aliasDraft, candidates, ownerByTag, processNames, packagePrefixes, { draft = it }, ::commitDraft) { draft = null }
        }
    }

    Divider()

    // ── Components ───────────────────────────────────────────────────────────────────────────
    SectionHeader(
        "Lifeline customizations",
        trailing = { AppText("${componentCards.size}", color = tc.td, fontSize = 10.sp) },
        expanded = componentsExpanded,
        onToggle = { componentsExpanded = !componentsExpanded },
    )
    AppText(
        "Default one-tag lifelines stay in Active tags. These cards are only grouped lifelines or explicit source mappings; aliases are kept compact above.",
        color = tc.td, fontSize = 10.sp, maxLines = 5, modifier = Modifier.padding(horizontal = 12.dp),
    )
    if (componentsExpanded) {
        componentCards.forEach { component ->
            val color = componentColor(component, multiTagComponents, tc)
            ComponentCard(
                component, color,
                sourceOwnerTypes = indexedOwnerTypesForLog(state.sourceIndex, component.tagIds, tab.logData),
                onRename = { renameComponent(component.id, it) },
                onSourceOwners = { owners -> onSpec(spec.copy(components = spec.components.map { if (it.id == component.id) it.copy(sourceOwnerTypes = owners) else it })) },
                onRemoveTag = { removeComponentTag(component, it) },
                onAddTags = { draft = ComponentDraft.Component(component.id, component.tagIds, component.displayName, nameTouched = true) },
                onToggleEnabled = { onSpec(spec.copy(components = spec.components.map { if (it.id == component.id) it.copy(enabled = !it.enabled) else it })) },
                onDelete = { deleteComponent(component) },
            )
            val editingDraft = draft as? ComponentDraft.Component
            if (editingDraft != null && editingDraft.editingId == component.id) {
                ComponentDraftEditor(editingDraft, candidates, ownerByTag, processNames, packagePrefixes, { draft = it }, ::commitDraft) { draft = null }
            }
        }
        Row(Modifier.padding(horizontal = 12.dp), horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
            AppButton(
                "Add component",
                { draft = ComponentDraft.Component(editingId = null, tags = emptySet(), name = "", nameTouched = false) },
                variant = ButtonVariant.Ghost, enabled = !atComponentCap && !atTagCap,
            )
            if (atComponentCap) AppText("Component limit reached (${MAX_UI_COMPONENTS}).", color = tc.td, fontSize = 9.sp)
            else if (atTagCap) AppText("Tag limit reached (${MAX_UI_TAG_IDS}).", color = tc.td, fontSize = 9.sp)
        }
        (draft as? ComponentDraft.Component)?.takeIf { it.editingId == null }?.let { newDraft ->
            ComponentDraftEditor(newDraft, candidates, ownerByTag, processNames, packagePrefixes, { draft = it }, ::commitDraft) { draft = null }
        }
    }

    Divider()

    // ── Actors ───────────────────────────────────────────────────────────────────────────────
    SectionHeader(
        "Actors",
        trailing = { AppText("${spec.actors.size}", color = tc.td, fontSize = 10.sp) },
        expanded = actorsExpanded,
        onToggle = { actorsExpanded = !actorsExpanded },
    )
    if (actorsExpanded) {
        spec.actors.forEach { actor ->
            ActorRow(actor, realComponents, onSpec, spec)
        }
        // Keep entry/exit controls for v1/v2 actors while the codec migrates them forward. Gated on
        // genuinely unmigrated records so this legacy editor and ActorRow above can never both render
        // for the same actor at once.
        spec.participants.filter { it.kind == ParticipantKind.ACTOR && spec.actors.none { a -> a.id == it.id } }.forEach { actor ->
            LegacyActorRow(actor, onSpec, spec)
        }
    }
    // Keep the creation affordance visible even when the empty Actors section is collapsed.
    if (actorsExpanded || spec.actors.isEmpty()) {
        Row(
            Modifier.padding(horizontal = 12.dp), horizontalArrangement = Arrangement.spacedBy(5.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            InlineField(newActor, { newActor = it }, "Add actor…", Modifier.weight(1f), fontSize = 10.sp)
            AppButton("Add", {
                val label = newActor.trim()
                if (label.isNotEmpty()) {
                    val id = "actor-${System.nanoTime()}"
                    onSpec(
                        spec.copy(
                            actors = spec.actors + DiagramActor(id, label),
                            participants = spec.participants + com.indagium.diagram.DiagramParticipant(
                                id = id,
                                label = label,
                                kind = ParticipantKind.ACTOR,
                            ),
                        ),
                    )
                    newActor = ""
                }
            }, enabled = newActor.isNotBlank())
        }
    }

    Divider()

    // ── Unmapped rows ────────────────────────────────────────────────────────────────────────
    // On its own line: sharing a Row with "Merge selected" (now deleted) was the exact bug that
    // pushed a checkbox off the panel's edge — CheckRow hard-codes fillMaxWidth() and takes no
    // modifier, so a second fillMaxWidth() sibling in the same Row measured at ~0 width and got
    // placed past the panel's right edge, still painted because Row doesn't clip. A CheckRow must
    // always be the only thing in its Row.
    SectionHeader("Unmapped rows")
    CheckRow(checked = spec.unmappedTagPolicy == UnmappedTagPolicy.GROUP_AS_OTHER, onToggle = {
        val next = if (spec.unmappedTagPolicy == UnmappedTagPolicy.HIDE) {
            UnmappedTagPolicy.GROUP_AS_OTHER
        } else {
            UnmappedTagPolicy.HIDE
        }
        onSpec(spec.copy(unmappedTagPolicy = next))
    }) { AppText("Group unmapped as Other", fontSize = 10.sp) }
}

/**
 * The participant editor deliberately has one durable concept: a lifeline.  Tags are the raw
 * source identities behind a lifeline, aliases are its display name, grouped tags are a merge,
 * and actors are another kind of lifeline.  Keeping those operations in one list makes the row
 * selection affordance useful instead of making the user translate between four separate lists.
 */
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

    val needle = search.trim()
    val visibleComponents = remember(components, needle) {
        if (needle.isBlank()) components else components.filter { component ->
            component.displayName.contains(needle, ignoreCase = true) ||
                component.tagIds.any { it.contains(needle, ignoreCase = true) }
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
        updateComponents(components + DiagramComponent(tag, tag, setOf(tag), enabled = true), nextSelectedIds = emptySet())
    }

    fun deleteComponent(component: DiagramComponent) {
        val next = components.filterNot { it.id == component.id }
        val actors = spec.actors.map { actor ->
            val mirrors = mirrorComponentIds(actor) - component.id
            actor.withMirrorComponentIds(mirrors)
        }
        selectedIds = selectedIds - component.id
        onSpec(spec.copy(components = next, actors = actors))
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
        val actors = spec.actors.map { actor ->
            val mirrors = mirrorComponentIds(actor).map { id -> if (id in removedIds) primary.id else id }.toSet()
            actor.withMirrorComponentIds(mirrors)
        }
        selectedIds = setOf(primary.id)
        onSpec(spec.copy(components = next, actors = actors))
    }

    fun unmerge(component: DiagramComponent) {
        if (component.tagIds.size < 2) return
        val tags = component.tagIds.sorted()
        val split = tags.mapIndexed { index, tag ->
            if (index == 0) component.copy(tagIds = setOf(tag))
            else DiagramComponent(tag, tag, setOf(tag), enabled = component.enabled)
        }
        val next = components.flatMap { current -> if (current.id == component.id) split else listOf(current) }
        selectedIds = split.map { it.id }.toSet()
        onSpec(spec.copy(components = next))
    }

    fun commitDraft(component: DiagramComponent) {
        val others = components.filter { it.id != component.id }
            .map { it.copy(tagIds = it.tagIds - component.tagIds) }
            .filter { it.tagIds.isNotEmpty() }
        val next = others + component
        if (next.size > MAX_UI_COMPONENTS || next.sumOf { it.tagIds.size } > MAX_UI_TAG_IDS) return
        selectedIds = setOf(component.id)
        onSpec(spec.copy(components = next, options = spec.options.copy(includeRowsHiddenByFilter = true)))
        draft = null
    }

    val enabledCount = components.count { it.enabled }
    SectionHeader(
        "Lifelines",
        trailing = { AppText("$enabledCount enabled · ${components.size + spec.actors.size} total", color = tc.td, fontSize = 10.sp) },
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
    if (visibleComponents.isEmpty() && matchingNewTags.isEmpty() && spec.actors.isEmpty()) {
        AppText(
            if (needle.isBlank()) "No lifelines yet. Search for a tag or add an actor." else "No matching lifelines or tags.",
            color = tc.td, fontSize = 10.sp, modifier = Modifier.padding(horizontal = 12.dp),
        )
    }
    visibleComponents.forEach { component ->
        val isSelected = component.id in selectedIds
        UnifiedLifelineRow(
            component = component,
            color = componentColor(component, multiTagComponents, tc),
            selected = isSelected,
            onToggleSelected = {
                selectedIds = if (isSelected) selectedIds - component.id else selectedIds + component.id
            },
            onToggleEnabled = {
                onSpec(spec.copy(components = components.map { if (it.id == component.id) it.copy(enabled = !it.enabled) else it }))
            },
            onRename = { name -> onSpec(spec.copy(components = components.map { if (it.id == component.id) it.copy(displayName = name) else it })) },
            onUnmerge = { unmerge(component) },
            onRemove = { deleteComponent(component) },
            sourceOwnerTypes = indexedOwnerTypesForLog(state.sourceIndex, component.tagIds, tab.logData),
            onSourceOwners = { owners -> onSpec(spec.copy(components = components.map { if (it.id == component.id) it.copy(sourceOwnerTypes = owners) else it })) },
        )
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
    (draft as? ComponentDraft.Alias)?.let { aliasDraft ->
        ComponentDraftEditor(aliasDraft, candidates, ownerByTag, processNames, packagePrefixes, { draft = it }, ::commitDraft) { draft = null }
    }

    spec.actors.forEach { actor -> ActorRow(actor, components, onSpec, spec) }
    spec.participants.filter { it.kind == ParticipantKind.ACTOR && spec.actors.none { actor -> actor.id == it.id } }
        .forEach { LegacyActorRow(it, onSpec, spec) }

    Row(Modifier.padding(horizontal = 12.dp), verticalAlignment = Alignment.CenterVertically) {
        InlineField(newActor, { newActor = it }, "Actor name…", Modifier.weight(1f), fontSize = 10.sp)
        AppButton("Add actor", {
            val label = newActor.trim()
            if (label.isNotEmpty()) {
                val id = "actor-${System.nanoTime()}"
                onSpec(spec.copy(
                    actors = spec.actors + DiagramActor(id, label),
                    participants = spec.participants + com.indagium.diagram.DiagramParticipant(id, label, ParticipantKind.ACTOR),
                ))
                newActor = ""
            }
        }, enabled = newActor.isNotBlank())
    }
    Row(
        Modifier.fillMaxWidth().padding(horizontal = 12.dp),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        AppButton("Add lifeline", { draft = ComponentDraft.Component(null, emptySet(), "", false) }, variant = ButtonVariant.Ghost, enabled = canAdd)
        AppButton("Add alias", { draft = ComponentDraft.Alias("", "") }, variant = ButtonVariant.Ghost, enabled = canAdd)
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
) {
    val tc = tc()
    var renaming by remember(component.id) { mutableStateOf(false) }
    HoverBox(
        modifier = Modifier.fillMaxWidth(),
        baseBg = if (selected) tc.abg else Color.Transparent,
    ) {
        Row(
            Modifier.fillMaxWidth().height(UNIFIED_LIFELINE_ROW_HEIGHT).padding(horizontal = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            AppText("☷", color = tc.td, fontSize = 12.sp)
            RoundIndicator(active = component.enabled, color = color, onClick = onToggleEnabled)
            RoundIndicator(active = selected, color = tc.td, onClick = onToggleSelected)
            if (renaming) {
                BlankGuardedNameField(component.displayName, "lifeline alias", component.id, Modifier.weight(1f), onRename)
            } else {
                AppText(component.displayName, fontSize = 11.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
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
private fun DiagramTagPills(
    candidates: List<DiagramParticipantCandidate>,
    ownerByTag: Map<String, DiagramComponent>,
    multiTagComponents: List<DiagramComponent>,
    packagePrefixes: Set<String>,
    tc: ThemeColors,
    onToggle: (String) -> Unit,
) {
    BoundedScrollBoxDp(132) {
        FlowRow(
            Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp),
            horizontalArrangement = Arrangement.spacedBy(4.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            candidates.forEach { candidate ->
                val owner = ownerByTag[candidate.tag]
                val (label, _) = displayTagForPrefix(candidate.tag, packagePrefixes)
                TagPill(
                    label, componentColor(owner, multiTagComponents, tc),
                    trailing = "${candidate.entryCount}",
                    active = owner?.enabled == true,
                    tooltip = candidate.tag,
                    onClick = { onToggle(candidate.tag) },
                )
            }
        }
    }
}

@Composable
private fun AliasRow(
    component: DiagramComponent,
    sourceOwnerTypes: List<String>,
    onRename: (String) -> Unit,
    onSourceOwners: (Set<String>) -> Unit,
    onRemove: () -> Unit,
) {
    val tc = tc()
    val tag = component.tagIds.single()
    HoverBox(modifier = Modifier.fillMaxWidth()) {
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 3.dp),
            verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            FullTextHint(tag, modifier = Modifier.weight(1f)) { onTextLayout ->
                AppText(
                    tag, fontSize = 10.sp, fontFamily = MONO, maxLines = 1, overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.fillMaxWidth(), onTextLayout = onTextLayout,
                )
            }
            AppText("→", color = tc.td, fontSize = 10.sp)
            BlankGuardedNameField(component.displayName, "alias name", component.id, Modifier.weight(1f), onRename)
            SquareIconButton("×", 12.sp, onRemove)
        }
        SourceOwnerPicker(component.sourceOwnerTypes, sourceOwnerTypes, onSourceOwners)
    }
}

@Composable
private fun ComponentCard(
    component: DiagramComponent,
    color: Color,
    sourceOwnerTypes: List<String>,
    onRename: (String) -> Unit,
    onSourceOwners: (Set<String>) -> Unit,
    onRemoveTag: (String) -> Unit,
    onAddTags: () -> Unit,
    onToggleEnabled: () -> Unit,
    onDelete: () -> Unit,
) {
    val tc = tc()
    Column(
        Modifier.fillMaxWidth().padding(horizontal = 12.dp)
            .background(tc.p2, CORNER_SM).border(1.dp, tc.br, CORNER_SM).padding(6.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            BlankGuardedNameField(component.displayName, "Component name", component.id, Modifier.weight(1f), onRename)
            SquareIconButton("+", 12.sp, onAddTags)
            SquareIconButton("×", 12.sp, onDelete)
        }
        CheckRow(checked = component.enabled, onToggle = onToggleEnabled) {
            AppText(if (component.enabled) "Included in diagram" else "Excluded from diagram", fontSize = 10.sp)
        }
        FlowRow(horizontalArrangement = Arrangement.spacedBy(4.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            component.tagIds.sorted().forEach { tag ->
                TagPill(tag, color, trailing = "×", active = component.enabled, tooltip = tag) { onRemoveTag(tag) }
            }
        }
        SourceOwnerPicker(component.sourceOwnerTypes, sourceOwnerTypes, onSourceOwners)
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
            "Optional: leave this empty for automatic source-call matching. Add one or more indexed classes only when automatic ownership is ambiguous or wrong. A mapping takes precedence for call direction; it does not create components or change which log rows are shown.",
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
            AppText("No suggestions match this log's tags. Index the relevant source folder and rebuild its index.", color = tc.td, fontSize = 9.sp, maxLines = 3)
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
    val isAlias = draft is ComponentDraft.Alias
    val editingId = (draft as? ComponentDraft.Component)?.editingId
    val draftTags = when (draft) {
        is ComponentDraft.Alias -> setOfNotNull(draft.tag.takeIf { it.isNotBlank() })
        is ComponentDraft.Component -> draft.tags
    }
    val nameValue = when (draft) {
        is ComponentDraft.Alias -> draft.name
        is ComponentDraft.Component -> draft.name
    }

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
                            onDraft(
                                when (draft) {
                                    is ComponentDraft.Alias -> draft.copy(tag = if (inDraft) "" else candidate.tag)
                                    is ComponentDraft.Component -> draft.copy(tags = if (inDraft) draft.tags - candidate.tag else draft.tags + candidate.tag)
                                },
                            )
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
                    onDraft(
                        when (draft) {
                            is ComponentDraft.Alias -> draft.copy(tag = typed)
                            is ComponentDraft.Component -> draft.copy(tags = draft.tags + typed)
                        },
                    )
                    search = ""
                })
            }
        }
        InlineField(
            nameValue,
            { value ->
                onDraft(
                    when (draft) {
                        is ComponentDraft.Alias -> draft.copy(name = value)
                        is ComponentDraft.Component -> draft.copy(name = value, nameTouched = true)
                    },
                )
            },
            if (isAlias) "Alias" else "Component name", Modifier.fillMaxWidth(), fontSize = 10.sp,
        )
        // Suggestions are never inferred or injected silently, matching the interaction-rule Add
        // buttons' own convention (this file's DiagramRulesEditor) — an explicit "use" click only.
        if (draft is ComponentDraft.Component && !draft.nameTouched) {
            val suggestion = proposeComponentName(
                tags = draft.tags,
                pidsByTag = candidates.associate { it.tag to it.pids },
                processNames = processNames,
            )
            if (suggestion != null) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(5.dp)) {
                    AppText("Suggested: $suggestion", color = tc.td, fontSize = 10.sp, modifier = Modifier.weight(1f))
                    LabelIconButton("use", 10.sp, onClick = { onDraft(draft.copy(name = suggestion, nameTouched = true)) })
                }
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            val canCommit = draftTags.isNotEmpty() && nameValue.isNotBlank()
            val actionLabel = when {
                isAlias -> "Add alias"
                editingId != null -> "Add tags"
                else -> "Add component"
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

@Composable
private fun ActorRow(actor: DiagramActor, realComponents: List<DiagramComponent>, onSpec: (SeqDiagramSpec) -> Unit, spec: SeqDiagramSpec) {
    val tc = tc()
    var mirrorSearch by remember(actor.id) { mutableStateOf("") }
    var mirrorsExpanded by remember(actor.id) { mutableStateOf(false) }
    val selectedIds = mirrorComponentIds(actor)
    val filteredComponents = realComponents.filter {
        mirrorSearch.isBlank() || it.displayName.contains(mirrorSearch.trim(), ignoreCase = true) ||
            it.tagIds.any { tag -> tag.contains(mirrorSearch.trim(), ignoreCase = true) }
    }
    Column(Modifier.padding(horizontal = 12.dp, vertical = 3.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            InlineField(
                actor.label,
                { label ->
                    onSpec(
                        spec.copy(
                            actors = spec.actors.map { if (it.id == actor.id) it.copy(label = label) else it },
                            participants = spec.participants.map { if (it.id == actor.id) it.copy(label = label) else it },
                        ),
                    )
                },
                "Actor", Modifier.weight(1f), fontSize = 10.sp,
            )
            SquareIconButton("×", 12.sp, {
                onSpec(
                    spec.copy(
                        actors = spec.actors.filterNot { it.id == actor.id },
                        participants = spec.participants.filterNot { it.id == actor.id },
                    ),
                )
            })
        }
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(5.dp)) {
            AppText("Mirror components (${selectedIds.size} selected)", color = tc.td, fontSize = 10.sp, modifier = Modifier.weight(1f))
            AppButton(if (mirrorsExpanded) "Hide" else "Choose", { mirrorsExpanded = !mirrorsExpanded }, variant = ButtonVariant.Ghost)
        }
        if (!mirrorsExpanded && selectedIds.isNotEmpty()) {
            AppText(
                selectedIds.mapNotNull { id -> realComponents.firstOrNull { it.id == id }?.displayName }.joinToString(", "),
                color = tc.td, fontSize = 9.sp, maxLines = 1, overflow = TextOverflow.Ellipsis,
            )
        }
        if (mirrorsExpanded) {
            InlineField(
                mirrorSearch,
                { mirrorSearch = it },
                "Search components…",
                Modifier.fillMaxWidth(),
                fontSize = 10.sp,
                onClear = { mirrorSearch = "" },
            )
            BoundedScrollBoxDp(144) {
                if (filteredComponents.isEmpty()) {
                    AppText("No matching components.", color = tc.td, fontSize = 10.sp, modifier = Modifier.padding(horizontal = 12.dp, vertical = 4.dp))
                } else {
                    filteredComponents.forEach { component ->
                        CheckRow(
                            checked = component.id in selectedIds,
                            onToggle = {
                                val next = if (component.id in selectedIds) selectedIds - component.id else selectedIds + component.id
                                onSpec(spec.copy(actors = spec.actors.map { if (it.id == actor.id) it.withMirrorComponentIds(next) else it }))
                            },
                        ) {
                            Column(Modifier.weight(1f)) {
                                AppText(component.displayName, fontSize = 10.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                AppText(component.tagIds.sorted().joinToString(", "), color = tc.td, fontSize = 9.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            }
                        }
                    }
                }
            }
        }
        if (selectedIds.isNotEmpty()) {
            val directionSelected = setOf(
                when (actor.mirrorDirection) {
                    MirrorDirection.INBOUND -> 0
                    MirrorDirection.OUTBOUND -> 1
                    MirrorDirection.BOTH -> 2
                },
            )
            SegmentedControl(listOf("in", "out", "both"), directionSelected, onToggle = { idx ->
                val direction = when (idx) {
                    0 -> MirrorDirection.INBOUND
                    1 -> MirrorDirection.OUTBOUND
                    else -> MirrorDirection.BOTH
                }
                onSpec(spec.copy(actors = spec.actors.map { if (it.id == actor.id) it.copy(mirrorDirection = direction) else it }))
            })
            AppText(
                "out: actor replaces the component as caller · in: actor replaces it as receiver · both: both directions",
                color = tc.td, fontSize = 9.sp, maxLines = 2,
            )
        }
    }
}

/** v1/v2 actors that predate the [DiagramActor] editor above — kept only until `migrateLegacyComponents`
 * (DiagramSpecCodec.kt) has a chance to run, which is why [ParticipantsSection] gates this on records
 * with no matching `spec.actors` entry: the two editors must never both render for the same actor. */
@Composable
private fun LegacyActorRow(actor: com.indagium.diagram.DiagramParticipant, onSpec: (SeqDiagramSpec) -> Unit, spec: SeqDiagramSpec) {
    Row(
        Modifier.padding(horizontal = 12.dp, vertical = 3.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        AppText(actor.displayName, fontSize = 10.sp, modifier = Modifier.weight(1f))
        val selected = buildSet { if (actor.isEntryPoint) add(0); if (actor.isExitPoint) add(1) }
        SegmentedControl(listOf("in", "out"), selected, onToggle = { idx ->
            onSpec(
                spec.copy(
                    participants = spec.participants.map {
                        when {
                            it.id == actor.id && idx == 0 -> it.copy(isEntryPoint = !it.isEntryPoint)
                            it.id == actor.id -> it.copy(isExitPoint = !it.isExitPoint)
                            it.kind == ParticipantKind.ACTOR && idx == 0 -> it.copy(isEntryPoint = false)
                            it.kind == ParticipantKind.ACTOR -> it.copy(isExitPoint = false)
                            else -> it
                        }
                    },
                ),
            )
        })
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
        is DiagramRange.Ids -> if (stored.selectedIds.isNotEmpty()) stored.selectedIds else {
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

// ── Mode ─────────────────────────────────────────────────────────────────────────────────────

@Composable
private fun ModeSection(tab: LogTab, spec: SeqDiagramSpec, onSpec: (SeqDiagramSpec) -> Unit) {
    val tc = tc()
    SectionHeader("Interactions")
    val modeSelected = when (spec.mode) {
        ArrowMode.EVIDENCE_FLOW -> setOf(0)
        ArrowMode.RULES -> setOf(1)
        ArrowMode.LINE_PER_MESSAGE -> setOf(2)
    }
    SegmentedControl(
        listOf("Component flow", "Rules", "Timeline"), modeSelected,
        onToggle = { idx ->
            onSpec(
                spec.copy(
                    mode = when (idx) {
                        0 -> ArrowMode.EVIDENCE_FLOW
                        1 -> ArrowMode.RULES
                        else -> ArrowMode.LINE_PER_MESSAGE
                    },
                ),
            )
        },
        modifier = Modifier.padding(horizontal = 12.dp),
    )
    // Neither explanation claims an arrow from a bare tag change anymore — EVIDENCE_FLOW draws one
    // only where the log actually carries evidence of it (see DiagramModel.kt's ArrowMode doc);
    // everything else renders as an event on its own lifeline, same as Timeline's own shape.
    val explanation = when (spec.mode) {
        ArrowMode.EVIDENCE_FLOW -> "An arrow only where the log has real evidence of one — a declared entry actor below, " +
            "an optional same-thread handoff, or a matched rule. Every other line is an event on its own lifeline."
        ArrowMode.RULES -> "Regex rules with (?<from>) (?<to>) (?<msg>) groups; an unmatched line falls back to an event " +
            "on its own lifeline (same fallback Component flow uses)."
        ArrowMode.LINE_PER_MESSAGE -> "Every line as an event on its own tag's lifeline."
    }
    AppText(explanation, color = tc.td, fontSize = 10.sp, maxLines = 4, modifier = Modifier.padding(horizontal = 12.dp))
    // A single enabled component is a perfectly good evidence-flow diagram now (it just has no
    // OTHER lifeline to draw a cross-component arrow to) — only a genuinely empty one-row scope has
    // nothing at all to show.
    val oneRow = (spec.range as? DiagramRange.Ids)?.let { it.from == it.to } == true
    if (spec.mode == ArrowMode.EVIDENCE_FLOW && oneRow) {
        AppText(
            "A one-row scope has nothing to correlate.",
            color = tc.td, fontSize = 10.sp, maxLines = 2, modifier = Modifier.padding(horizontal = 12.dp),
        )
        AppButton(
            "Use event timeline", { onSpec(spec.copy(mode = ArrowMode.LINE_PER_MESSAGE)) },
            variant = ButtonVariant.Ghost, modifier = Modifier.padding(horizontal = 12.dp),
        )
    }
    CheckRow(checked = spec.sourceEnrichment.enabled, onToggle = {
        onSpec(spec.copy(sourceEnrichment = spec.sourceEnrichment.copy(enabled = !spec.sourceEnrichment.enabled)))
    }) { AppText("Reconstruct source execution trace", fontSize = 10.sp) }
    // An interaction-evidence choice, not a presentation one — sits beside source-call enrichment
    // rather than down in OptionsSection's presentation toggles.
    CheckRow(checked = spec.options.threadHandoffArrows, onToggle = {
        onSpec(spec.copy(options = spec.options.copy(threadHandoffArrows = !spec.options.threadHandoffArrows)))
    }) { AppText("Infer arrows from same-thread handoffs (pid + tid)", fontSize = 10.sp) }
    AppText(
        "Source trace mode derives call/return arrows and activations from the indexed invocation path. If the path is ambiguous or stale, the diagram reports fallback mode; PID/TID only refines lane selection.",
        color = tc.td, fontSize = 10.sp, maxLines = 3, modifier = Modifier.padding(horizontal = 12.dp),
    )
    if (spec.mode == ArrowMode.RULES) DiagramRulesEditor(tab, spec, onSpec)
}

@Composable
private fun DiagramRulesEditor(tab: LogTab, spec: SeqDiagramSpec, onSpec: (SeqDiagramSpec) -> Unit) {
    var pattern by remember { mutableStateOf("") }
    var label by remember { mutableStateOf("\${msg}") }
    var proposalRevision by remember(tab.id) { mutableStateOf(0) }
    val lifelineIds = remember(spec.components, spec.actors, spec.participants) {
        (spec.components.filter { it.enabled && it.id.isNotBlank() }.map { it.id } +
            spec.actors.map { it.id } + spec.participants.map { it.id }).distinct()
    }
    val availableParticipants = remember(spec.components, spec.actors, spec.participants, lifelineIds) {
        // Keep component tag bindings in this preview-only list.  The same component id can occur
        // once per tag here; the proposal service uses tags for CurrentEntry and IDs for endpoint
        // validity, while the actual builder still emits one lifeline per component.
        val componentBindings = spec.components.filter { it.enabled }.flatMap { component ->
            component.tagIds.map { tag -> com.indagium.diagram.DiagramParticipant(component.id, component.displayName, ParticipantKind.TAG, tag = tag) }
        }
        componentBindings + spec.actors.map { actor ->
            com.indagium.diagram.DiagramParticipant(actor.id, actor.label, ParticipantKind.ACTOR)
        } + spec.participants
    }
    var fromEndpoint by remember(lifelineIds) { mutableStateOf<DiagramRuleEndpoint>(DiagramRuleEndpoint.CurrentEntry) }
    var toEndpoint by remember(lifelineIds) { mutableStateOf<DiagramRuleEndpoint?>(lifelineIds.firstOrNull()?.let(DiagramRuleEndpoint::ExistingParticipant)) }

    fun invalidPattern(value: String): String? = runCatching { Regex(value) }.exceptionOrNull()?.message
    val draftError = pattern.takeIf { it.isNotBlank() }?.let(::invalidPattern)

    AppText(
        "Rules match log messages in order. Endpoints are explicit diagram lifelines, so typed rules never invent a lifeline from captured text. " +
            "Use captures in labels (for example \${msg}); unmatched rows remain self events.",
        color = tc().td, fontSize = 10.sp, maxLines = 4, modifier = Modifier.padding(horizontal = 12.dp),
    )

    spec.rules.forEach { rule ->
        val error = remember(rule.pattern) { invalidPattern(rule.pattern) }
        val proposal = remember(rule, tab.logData, availableParticipants, proposalRevision) {
            com.indagium.diagram.DiagramProposalService.evaluateRules(tab.logData, listOf(rule), availableParticipants).candidates.single()
        }
        Column(Modifier.padding(horizontal = 12.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                CheckRow(checked = rule.enabled, onToggle = {
                    onSpec(spec.copy(rules = spec.rules.map { if (it.id == rule.id) it.copy(enabled = !it.enabled) else it }))
                }) {}
                AppText(
                    rule.pattern,
                    fontSize = 10.sp,
                    fontFamily = MONO,
                    modifier = Modifier.weight(1f),
                    maxLines = 1,
                    overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis
                )
                AppButton("×", { onSpec(spec.copy(rules = spec.rules.filterNot { it.id == rule.id })) }, variant = ButtonVariant.Ghost)
            }
            InlineField(rule.pattern, { value ->
                onSpec(spec.copy(rules = spec.rules.map { if (it.id == rule.id) it.copy(pattern = value) else it }))
            }, "regex", Modifier.fillMaxWidth(), fontSize = 10.sp)
            RuleEndpointPicker("From", rule.fromEndpoint, lifelineIds) { endpoint ->
                onSpec(spec.copy(rules = spec.rules.map { if (it.id == rule.id) it.copy(fromEndpoint = endpoint) else it }))
            }
            RuleEndpointPicker("To", rule.toEndpoint, lifelineIds) { endpoint ->
                onSpec(spec.copy(rules = spec.rules.map { if (it.id == rule.id) it.copy(toEndpoint = endpoint) else it }))
            }
            InlineField(rule.labelTemplate, { value ->
                onSpec(spec.copy(rules = spec.rules.map { if (it.id == rule.id) it.copy(labelTemplate = value) else it }))
            }, "label (for example \${msg})", Modifier.fillMaxWidth(), fontSize = 10.sp)
            AppText(
                "${proposal.matchedEntryIds.size} matches · ${proposal.resolvedEndpointIds.joinToString().ifBlank { "no resolved lifelines" }}",
                color = if (proposal.applyBlocked) DANGER_RED else tc().td, fontSize = 9.sp, maxLines = 2,
            )
            proposal.issues.take(2).forEach { issue -> AppText(issue.detail, color = DANGER_RED, fontSize = 9.sp, maxLines = 2) }
            proposal.samples.firstOrNull()?.let { sample ->
                AppText("Example ${sample.entryId}: ${sample.message}", color = tc().td, fontSize = 9.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            if (rule.fromEndpoint == null || rule.toEndpoint == null) {
                AppText("Legacy rule: choose both lifelines before relying on it; unbound templates may create actors.", color = DANGER_RED, fontSize = 9.sp, maxLines = 2)
            }
            if (error != null) AppText("Invalid regex: ${error.take(90)}", color = DANGER_RED, fontSize = 9.sp, maxLines = 2)
        }
    }
    AppButton("Refresh rule proposals", { proposalRevision++ }, variant = ButtonVariant.Ghost, modifier = Modifier.padding(horizontal = 12.dp))
    InlineField(pattern, { pattern = it }, "regex with named groups…", Modifier.fillMaxWidth().padding(horizontal = 12.dp), fontSize = 10.sp)
    RuleEndpointPicker("From", fromEndpoint, lifelineIds) { fromEndpoint = it ?: DiagramRuleEndpoint.CurrentEntry }
    RuleEndpointPicker("To", toEndpoint, lifelineIds) { toEndpoint = it }
    InlineField(label, { label = it }, "label (for example \${msg})", Modifier.fillMaxWidth().padding(horizontal = 12.dp), fontSize = 10.sp)
    if (draftError != null) {
        AppText(
            "Invalid regex: ${draftError.take(90)}", color = DANGER_RED, fontSize = 10.sp, maxLines = 2,
            modifier = Modifier.padding(horizontal = 12.dp),
        )
    }
    val draftProposal = remember(pattern, fromEndpoint, toEndpoint, label, tab.logData, availableParticipants) {
        if (pattern.isBlank() || draftError != null || toEndpoint == null) null else {
            val rule = com.indagium.diagram.DiagramMessageRule(
                id = "draft", pattern = pattern, fromTemplate = "", toTemplate = "", labelTemplate = label,
                fromEndpoint = fromEndpoint, toEndpoint = toEndpoint,
            )
            com.indagium.diagram.DiagramProposalService.evaluateRules(tab.logData, listOf(rule), availableParticipants).candidates.single()
        }
    }
    draftProposal?.let { proposal ->
        AppText(
            "Proposal: ${proposal.matchedEntryIds.size} matches · ${proposal.resolvedEndpointIds.joinToString().ifBlank { "no resolved lifelines" }}",
            color = if (proposal.applyBlocked) DANGER_RED else tc().td, fontSize = 9.sp, maxLines = 2,
            modifier = Modifier.padding(horizontal = 12.dp),
        )
        proposal.issues.take(2).forEach { issue ->
            AppText(issue.detail, color = DANGER_RED, fontSize = 9.sp, maxLines = 2, modifier = Modifier.padding(horizontal = 12.dp))
        }
    }
    AppButton(
        "Add rule", {
            if (pattern.isNotBlank() && draftError == null && toEndpoint != null && draftProposal?.applyBlocked != true) {
                onSpec(
                    spec.copy(
                        rules = spec.rules + com.indagium.diagram.DiagramMessageRule(
                            id = "dr${System.nanoTime()}", pattern = pattern,
                            fromTemplate = "", toTemplate = "", labelTemplate = label,
                            fromEndpoint = fromEndpoint, toEndpoint = toEndpoint,
                        ),
                    ),
                )
                pattern = ""
            }
        },
        enabled = pattern.isNotBlank() && draftError == null && toEndpoint != null && draftProposal?.applyBlocked != true,
        modifier = Modifier.padding(horizontal = 12.dp),
    )
}

@Composable
private fun RuleEndpointPicker(
    title: String,
    selected: DiagramRuleEndpoint?,
    lifelineIds: List<String>,
    onSelect: (DiagramRuleEndpoint?) -> Unit,
) {
    Column(Modifier.padding(horizontal = 12.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
        AppText(title, color = tc().td, fontSize = 9.sp)
        FlowRow(horizontalArrangement = Arrangement.spacedBy(3.dp), verticalArrangement = Arrangement.spacedBy(3.dp)) {
            AppButton("This log row", { onSelect(DiagramRuleEndpoint.CurrentEntry) }, variant = if (selected == DiagramRuleEndpoint.CurrentEntry) ButtonVariant.Primary else ButtonVariant.Ghost)
            lifelineIds.forEach { id ->
                val endpoint = DiagramRuleEndpoint.ExistingParticipant(id)
                AppButton(id, { onSelect(endpoint) }, variant = if (selected == endpoint) ButtonVariant.Primary else ButtonVariant.Ghost)
            }
            AppButton("Captured value", { onSelect(DiagramRuleEndpoint.CapturedValue("value", emptyList())) }, variant = if (selected is DiagramRuleEndpoint.CapturedValue) ButtonVariant.Primary else ButtonVariant.Ghost)
            AppButton("Explicit actor", { onSelect(DiagramRuleEndpoint.ExplicitActor("actor", "Actor")) }, variant = if (selected is DiagramRuleEndpoint.ExplicitActor) ButtonVariant.Primary else ButtonVariant.Ghost)
        }
        when (selected) {
            is DiagramRuleEndpoint.CapturedValue -> {
                InlineField(selected.captureName, { capture ->
                    onSelect(selected.copy(captureName = capture))
                }, "capture name", Modifier.fillMaxWidth(), fontSize = 9.sp)
                InlineField(selected.bindings.formatRuleBindings(), { text ->
                    onSelect(selected.copy(bindings = text.parseRuleBindings(lifelineIds)))
                }, "bindings: captured=lifeline id; …", Modifier.fillMaxWidth(), fontSize = 9.sp)
            }
            is DiagramRuleEndpoint.ExplicitActor -> {
                InlineField(selected.id, { id -> onSelect(selected.copy(id = id)) }, "actor id", Modifier.fillMaxWidth(), fontSize = 9.sp)
                InlineField(selected.label, { label -> onSelect(selected.copy(label = label)) }, "actor label", Modifier.fillMaxWidth(), fontSize = 9.sp)
            }
            else -> Unit
        }
    }
}

private fun List<com.indagium.diagram.DiagramRuleCaptureBinding>.formatRuleBindings(): String =
    joinToString("; ") { "${it.capturedValue}=${it.participantId}" }

private fun String.parseRuleBindings(lifelineIds: List<String>): List<com.indagium.diagram.DiagramRuleCaptureBinding> =
    split(';').mapNotNull { raw ->
        val pair = raw.trim()
        val separator = pair.indexOf('=')
        if (separator <= 0) return@mapNotNull null
        val captured = pair.substring(0, separator).trim()
        val participant = pair.substring(separator + 1).trim()
        if (captured.isBlank() || participant !in lifelineIds) null
        else com.indagium.diagram.DiagramRuleCaptureBinding(captured, participant)
    }.distinct()

// ── Options ──────────────────────────────────────────────────────────────────────────────────

@Composable
private fun OptionsSection(state: AppState, spec: SeqDiagramSpec, onSpec: (SeqDiagramSpec) -> Unit, manualMode: Boolean = false) {
    if (manualMode) {
        ManualPresentationSection(spec, onSpec)
        return
    }
    val tc = tc()
    val o = spec.options
    // SOURCE_METHOD/BOTH resolve each line against the source index; offering them with no folder
    // indexed would just silently fall back to the message for every arrow.
    val sourceAvailable = state.settings.sourceFolders.isNotEmpty()

    SectionHeader("Presentation")
    CheckRow(checked = o.collapseRepeats, onToggle = { onSpec(spec.copy(options = o.copy(collapseRepeats = !o.collapseRepeats))) }) {
        AppText("Collapse repeated messages", fontSize = 11.sp)
    }
    CheckRow(checked = o.seqGroupFrames, onToggle = { onSpec(spec.copy(options = o.copy(seqGroupFrames = !o.seqGroupFrames))) }) {
        AppText("Frame sequence groups", fontSize = 11.sp)
    }
    CheckRow(checked = o.notesForErrors, onToggle = { onSpec(spec.copy(options = o.copy(notesForErrors = !o.notesForErrors))) }) {
        AppText("Note errors and crashes", fontSize = 11.sp)
    }
    CheckRow(checked = o.showElapsed, onToggle = { onSpec(spec.copy(options = o.copy(showElapsed = !o.showElapsed))) }) {
        AppText("Show time delta", fontSize = 11.sp)
    }
    CheckRow(checked = o.showTimestamps, onToggle = { onSpec(spec.copy(options = o.copy(showTimestamps = !o.showTimestamps))) }) {
        AppText("Show timestamps", fontSize = 11.sp)
    }
    if (sourceAvailable) {
        CheckRow(
            checked = o.labelSource != LabelSource.MESSAGE,
            onToggle = {
                onSpec(spec.copy(options = o.copy(labelSource = if (o.labelSource == LabelSource.MESSAGE) LabelSource.BOTH else LabelSource.MESSAGE)))
            },
        ) { AppText("Label with source method", fontSize = 11.sp) }
    } else {
        AppText(
            "Label with source method — needs an indexed source folder.", color = tc.td, fontSize = 10.sp, maxLines = 2,
            modifier = Modifier.padding(horizontal = 12.dp),
        )
    }
    CheckRow(checked = o.activationPolicy == ActivationPolicy.EVIDENCE_BACKED, onToggle = {
        val next = if (o.activationPolicy == ActivationPolicy.NONE) {
            ActivationPolicy.EVIDENCE_BACKED
        } else {
            ActivationPolicy.NONE
        }
        onSpec(spec.copy(options = o.copy(activationPolicy = next)))
    }) { AppText("Evidence-backed activations", fontSize = 11.sp) }
    AppText(
        "Enabled by default. Bars are drawn only for evidence-backed call/return spans, so a self-event-only log has no activation to show.",
        color = tc.td, fontSize = 10.sp, maxLines = 3, modifier = Modifier.padding(horizontal = 12.dp),
    )
    CheckRow(checked = o.showSelfMessages, onToggle = {
        onSpec(spec.copy(options = o.copy(showSelfMessages = !o.showSelfMessages)))
    }) { AppText("Show self events", fontSize = 11.sp) }
    if (spec.sourceEnrichment.enabled) {
        CheckRow(checked = o.showSourceInferred, onToggle = {
            onSpec(spec.copy(options = o.copy(showSourceInferred = !o.showSourceInferred)))
        }) { AppText("Show inferred source calls", fontSize = 11.sp) }
    } else {
        AppText(
            "Show source-trace structure — requires a current source index above.", color = tc.td, fontSize = 10.sp, maxLines = 2,
            modifier = Modifier.padding(horizontal = 12.dp),
        )
    }

    NumericSettingRow("Max arrows", o.maxMessages, 1..1000) {
        onSpec(spec.copy(options = o.copy(maxMessages = it)))
    }
    NumericSettingRow("Message label chars", o.labelMaxChars, 10..400) {
        onSpec(spec.copy(options = o.copy(labelMaxChars = it)))
    }
    NumericSettingRow("Label lines", o.labelMaxLines, 1..8) {
        onSpec(spec.copy(options = o.copy(labelMaxLines = it)))
    }
    AppText(
        "Message labels are truncated and wrapped independently from participant/component labels.",
        color = tc.td, fontSize = 10.sp, maxLines = 2, modifier = Modifier.padding(horizontal = 12.dp),
    )
    NumericSettingRow("Component label chars", o.participantLabelMaxChars, 10..120) {
        onSpec(spec.copy(options = o.copy(participantLabelMaxChars = it)))
    }
    NumericSettingRow("Component label lines", o.participantLabelMaxLines, 1..4) {
        onSpec(spec.copy(options = o.copy(participantLabelMaxLines = it)))
    }
    AppText(
        "Component labels use these limits for their lifeline headers; raw tags remain available in the inspector.",
        color = tc.td, fontSize = 10.sp, maxLines = 3, modifier = Modifier.padding(horizontal = 12.dp),
    )
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
    }) { AppText("Show manual activation spans", fontSize = 11.sp) }
    AppText(
        "Manual lines are authoritative. Only the settings below affect their build or shared canvas rendering.",
        color = tc().td, fontSize = 10.sp, maxLines = 3, modifier = Modifier.padding(horizontal = 12.dp),
    )
    NumericSettingRow("Max arrows", o.maxMessages, 1..1000) {
        onSpec(spec.copy(options = o.copy(maxMessages = it)))
    }
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
