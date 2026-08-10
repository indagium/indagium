@file:OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)

package com.indagium.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
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
import com.indagium.diagram.LabelSource
import com.indagium.diagram.MirrorDirection
import com.indagium.diagram.ParticipantKind
import com.indagium.diagram.SeqDiagramSpec
import com.indagium.diagram.UnmappedTagPolicy
import com.indagium.diagram.displayName
import com.indagium.model.LogTab

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
internal fun WorkspaceInspector(tab: LogTab, state: AppState, spec: SeqDiagramSpec, onSpec: (SeqDiagramSpec) -> Unit) {
    SectionHeader("Scope")
    RangeSection(tab, state, spec, onSpec)
    Divider()
    ParticipantsSection(tab, state, spec, onSpec)
    Divider()
    ModeSection(spec, onSpec)
    Divider()
    OptionsSection(state, spec, onSpec)
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
    val tc = tc()
    val candidateState = state.seqDiagrams.candidatePreview
    val candidates = candidateState.valuesOrEmpty
    // Source-view changes invalidate tiers, but title/component toggles don't.  The coordinator
    // does the expensive range scan in its cancellable IO lane.
    LaunchedEffect(tab.id, System.identityHashCode(tab.logData), System.identityHashCode(tab.filter), spec.range) {
        state.seqDiagrams.requestCandidates(tab.id, spec)
    }

    // EMPTY_COMPONENT_ID (SeqDiagramCoordinator's brand-new-workspace sentinel) has empty tagIds
    // and never a display identity of its own — filtering by tagIds is enough to exclude it
    // everywhere below without needing that private constant.
    val realComponents = remember(spec.components) { spec.components.filter { it.tagIds.isNotEmpty() } }
    val multiTagComponents = remember(realComponents) { realComponents.filter { it.tagIds.size > 1 } }
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
        onSpec(spec.copy(components = components))
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
                actors = spec.actors.map { if (it.mirrorComponentId == component.id) it.copy(mirrorComponentId = null) else it },
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
            spec.actors.map { if (it.mirrorComponentId == component.id) it.copy(mirrorComponentId = converted.id) else it }
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
        DiagramTagPills(filteredCandidates, ownerByTag, multiTagComponents, packagePrefixes, tc) { tag ->
            toggleTag(tag, ownerByTag[tag]?.enabled != true)
        }
    }

    Divider()

    // ── Aliases ──────────────────────────────────────────────────────────────────────────────
    val aliasComponents = realComponents.filter { it.tagIds.size == 1 && it.displayName != it.tagIds.single() }
    SectionHeader("Aliases", trailing = { AppText("${aliasComponents.size}", color = tc.td, fontSize = 10.sp) })
    aliasComponents.forEach { alias ->
        AliasRow(alias, onRename = { renameComponent(alias.id, it) }, onRemove = { deleteComponent(alias) })
    }
    Row(Modifier.padding(horizontal = 12.dp), horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
        AppButton("Add alias", { draft = ComponentDraft.Alias("", "") }, variant = ButtonVariant.Ghost, enabled = !atComponentCap && !atTagCap)
        if (atComponentCap) AppText("Component limit reached (${MAX_UI_COMPONENTS}).", color = tc.td, fontSize = 9.sp)
        else if (atTagCap) AppText("Tag limit reached (${MAX_UI_TAG_IDS}).", color = tc.td, fontSize = 9.sp)
    }
    (draft as? ComponentDraft.Alias)?.let { aliasDraft ->
        ComponentDraftEditor(aliasDraft, candidates, ownerByTag, processNames, packagePrefixes, { draft = it }, ::commitDraft) { draft = null }
    }

    Divider()

    // ── Components ───────────────────────────────────────────────────────────────────────────
    SectionHeader("Components", trailing = { AppText("${multiTagComponents.size}", color = tc.td, fontSize = 10.sp) })
    multiTagComponents.forEach { component ->
        val color = componentColor(component, multiTagComponents, tc)
        ComponentCard(
            component, color,
            onRename = { renameComponent(component.id, it) },
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

    Divider()

    // ── Actors ───────────────────────────────────────────────────────────────────────────────
    SectionHeader("Actors")
    spec.actors.forEach { actor ->
        ActorRow(actor, realComponents, onSpec, spec)
    }
    // Keep entry/exit controls for v1/v2 actors while the codec migrates them forward. Gated on
    // genuinely unmigrated records so this legacy editor and ActorRow above can never both render
    // for the same actor at once.
    spec.participants.filter { it.kind == ParticipantKind.ACTOR && spec.actors.none { a -> a.id == it.id } }.forEach { actor ->
        LegacyActorRow(actor, onSpec, spec)
    }
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
private fun AliasRow(component: DiagramComponent, onRename: (String) -> Unit, onRemove: () -> Unit) {
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
    }
}

@Composable
private fun ComponentCard(
    component: DiagramComponent,
    color: Color,
    onRename: (String) -> Unit,
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
            RoundIndicator(component.enabled, color, onToggleEnabled)
            BlankGuardedNameField(component.displayName, "Component name", component.id, Modifier.weight(1f), onRename)
            SquareIconButton("+", 12.sp, onAddTags)
            SquareIconButton("×", 12.sp, onDelete)
        }
        FlowRow(horizontalArrangement = Arrangement.spacedBy(4.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            component.tagIds.sorted().forEach { tag ->
                TagPill(tag, color, trailing = "×", active = component.enabled, tooltip = tag) { onRemoveTag(tag) }
            }
        }
    }
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
                val enabled = editingId?.let { eid -> ownerByTag.values.find { it.id == eid }?.enabled } ?: true
                onCommit(DiagramComponent(id, nameValue.trim(), draftTags, enabled = enabled))
            }, variant = ButtonVariant.Primary, enabled = canCommit)
            AppButton("Cancel", onCancel, variant = ButtonVariant.Ghost)
        }
    }
}

@Composable
private fun ActorRow(actor: DiagramActor, realComponents: List<DiagramComponent>, onSpec: (SeqDiagramSpec) -> Unit, spec: SeqDiagramSpec) {
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
        // A cycling button hides its own option set — SegmentedControl shows every mirror target
        // (plus "No mirror") at once instead of making the user click through them one at a time.
        val targetOptions = listOf("No mirror") + realComponents.map { it.displayName }
        val targetSelected = realComponents.indexOfFirst { it.id == actor.mirrorComponentId }
            .let { idx -> setOf(if (idx >= 0) idx + 1 else 0) }
        SegmentedControl(targetOptions, targetSelected, onToggle = { idx ->
            val target = if (idx == 0) null else realComponents.getOrNull(idx - 1)?.id
            onSpec(spec.copy(actors = spec.actors.map { if (it.id == actor.id) it.copy(mirrorComponentId = target) else it }))
        })
        if (actor.mirrorComponentId != null) {
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

@Composable
private fun RangeSection(tab: LogTab, state: AppState, spec: SeqDiagramSpec, onSpec: (SeqDiagramSpec) -> Unit) {
    val tc = tc()
    val range = spec.range
    SectionHeader("Range")
    // SeqGroupRef (a persisted-but-not-user-selectable range shape) leaves every segment
    // unselected rather than misrepresenting it as one of the three pickable ranges — matching
    // the previous PillBtn trio, none of which ever lit up for that case either.
    val rangeSelected = when (range) {
        is DiagramRange.Ids -> setOf(0)
        is DiagramRange.VisibleView -> setOf(1)
        is DiagramRange.Time -> setOf(2)
        is DiagramRange.SeqGroupRef -> emptySet()
    }
    SegmentedControl(
        listOf("Selection", "Whole view", "Time"), rangeSelected,
        onToggle = { idx ->
            when (idx) {
                // Same derivation begin() seeds a fresh workspace with (SeqDiagramCoordinator.
                // selectionRange) — a collapsed fold's header selects everything it hides, exactly
                // as if the user had uncollapsed it first. The pill and the initial seed must never
                // disagree.
                0 -> state.seqDiagrams.selectionRange(tab.id)?.let { onSpec(spec.copy(range = it)) }
                1 -> onSpec(spec.copy(range = DiagramRange.VisibleView))
                else -> onSpec(spec.copy(range = DiagramRange.Time("", "")))
            }
        },
        modifier = Modifier.padding(horizontal = 12.dp),
    )
    when (range) {
        is DiagramRange.Ids -> AppText(
            "Lines ${range.from}–${range.to}", color = tc.td, fontSize = 11.sp,
            modifier = Modifier.padding(horizontal = 12.dp),
        )
        is DiagramRange.VisibleView -> AppText(
            "Everything the current filter shows.", color = tc.td, fontSize = 11.sp, maxLines = 2,
            modifier = Modifier.padding(horizontal = 12.dp),
        )

        is DiagramRange.Time -> Row(Modifier.padding(horizontal = 12.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            InlineField(range.fromTs, { onSpec(spec.copy(range = range.copy(fromTs = it))) }, "from HH:MM:SS", Modifier.weight(1f), fontSize = 11.sp)
            InlineField(range.toTs, { onSpec(spec.copy(range = range.copy(toTs = it))) }, "to HH:MM:SS", Modifier.weight(1f), fontSize = 11.sp)
        }

        is DiagramRange.SeqGroupRef -> AppText(
            "Sequence group ${range.gid}", color = tc.td, fontSize = 11.sp,
            modifier = Modifier.padding(horizontal = 12.dp),
        )
    }
    if (range is DiagramRange.Ids && tab.selected.isEmpty()) {
        AppText(
            "Select one or more rows in the log to change this.", color = tc.td, fontSize = 10.sp, maxLines = 2,
            modifier = Modifier.padding(horizontal = 12.dp),
        )
    }
}

// ── Mode ─────────────────────────────────────────────────────────────────────────────────────

@Composable
private fun ModeSection(spec: SeqDiagramSpec, onSpec: (SeqDiagramSpec) -> Unit) {
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
    }) { AppText("Add one-hop source calls", fontSize = 10.sp) }
    // An interaction-evidence choice, not a presentation one — sits beside source-call enrichment
    // rather than down in OptionsSection's presentation toggles.
    CheckRow(checked = spec.options.threadHandoffArrows, onToggle = {
        onSpec(spec.copy(options = spec.options.copy(threadHandoffArrows = !spec.options.threadHandoffArrows)))
    }) { AppText("Infer arrows from same-thread handoffs (pid + tid)", fontSize = 10.sp) }
    if (spec.mode == ArrowMode.RULES) DiagramRulesEditor(spec, onSpec)
}

@Composable
private fun DiagramRulesEditor(spec: SeqDiagramSpec, onSpec: (SeqDiagramSpec) -> Unit) {
    var pattern by remember { mutableStateOf("") }
    var from by remember { mutableStateOf("\${from}") }
    var to by remember { mutableStateOf("\${to}") }
    var label by remember { mutableStateOf("\${msg}") }

    fun invalidPattern(value: String): String? = runCatching { Regex(value) }.exceptionOrNull()?.message
    val draftError = pattern.takeIf { it.isNotBlank() }?.let(::invalidPattern)

    spec.rules.forEach { rule ->
        val error = remember(rule.pattern) { invalidPattern(rule.pattern) }
        Column(Modifier.padding(horizontal = 12.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
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
            if (error != null) AppText("Invalid regex: ${error.take(90)}", color = DANGER_RED, fontSize = 9.sp, maxLines = 2)
        }
    }
    InlineField(pattern, { pattern = it }, "regex with named groups…", Modifier.fillMaxWidth().padding(horizontal = 12.dp), fontSize = 10.sp)
    Row(Modifier.padding(horizontal = 12.dp), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
        InlineField(from, { from = it }, "from", Modifier.weight(1f), fontSize = 10.sp)
        InlineField(to, { to = it }, "to", Modifier.weight(1f), fontSize = 10.sp)
        InlineField(label, { label = it }, "label", Modifier.weight(1f), fontSize = 10.sp)
    }
    if (draftError != null) {
        AppText(
            "Invalid regex: ${draftError.take(90)}", color = DANGER_RED, fontSize = 10.sp, maxLines = 2,
            modifier = Modifier.padding(horizontal = 12.dp),
        )
    }
    AppButton(
        "Add rule", {
            if (pattern.isNotBlank() && draftError == null) {
                onSpec(
                    spec.copy(
                        rules = spec.rules + com.indagium.diagram.DiagramMessageRule(
                            id = "dr${System.nanoTime()}", pattern = pattern,
                            fromTemplate = from, toTemplate = to, labelTemplate = label,
                        ),
                    ),
                )
                pattern = ""
            }
        },
        enabled = pattern.isNotBlank() && draftError == null, modifier = Modifier.padding(horizontal = 12.dp),
    )

    AppText("Suggested interactions", color = tc().td, fontSize = 10.sp, modifier = Modifier.padding(horizontal = 12.dp))
    interactionTemplates.forEach { template ->
        Row(
            Modifier.padding(horizontal = 12.dp),
            verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(5.dp),
        ) {
            AppText(template.label, fontSize = 10.sp, modifier = Modifier.weight(1f))
            AppButton("Add", {
                // Suggestions are never inferred or injected silently. This click is the user's
                // explicit confirmation to add ordinary editable DiagramMessageRule records.
                onSpec(spec.copy(rules = spec.rules + template.rules.mapIndexed { index, draft ->
                    com.indagium.diagram.DiagramMessageRule(
                        id = "dr${System.nanoTime()}-$index", pattern = draft.pattern,
                        fromTemplate = draft.from, toTemplate = draft.to, labelTemplate = draft.label,
                    )
                }))
            }, variant = ButtonVariant.Ghost)
        }
    }
}

private data class InteractionRuleDraft(val pattern: String, val from: String = "\${from}", val to: String = "\${to}", val label: String = "\${msg}")

private data class InteractionTemplate(val label: String, val rules: List<InteractionRuleDraft>)

/** Conservative starting points, deliberately offered as explicit Add actions rather than
 * automatic detection. Users can inspect and edit the resulting ordinary rules immediately. */
private val interactionTemplates = listOf(
    InteractionTemplate(
        "HTTP request",
        listOf(InteractionRuleDraft("(?i)(?<from>\\S+).*\\b(?<verb>GET|POST|PUT|DELETE|PATCH)\\s+(?<to>https?://\\S+)", label = "\${verb} \${msg}"))
    ),
    InteractionTemplate("RPC / Binder", listOf(InteractionRuleDraft("(?i)(?<from>\\S+).*\\b(?:rpc|binder)\\b.*\\bto\\s+(?<to>\\S+)"))),
    InteractionTemplate("Broadcast", listOf(InteractionRuleDraft("(?i)(?<from>\\S+).*\\bbroadcast\\b.*\\bto\\s+(?<to>\\S+)"))),
    InteractionTemplate("Worker / job", listOf(InteractionRuleDraft("(?i)(?<from>\\S+).*\\b(?:worker|job)\\b.*\\bto\\s+(?<to>\\S+)"))),
    InteractionTemplate("Socket", listOf(InteractionRuleDraft("(?i)(?<from>\\S+).*\\bsocket\\b.*\\bto\\s+(?<to>\\S+)"))),
    InteractionTemplate("Database", listOf(InteractionRuleDraft("(?i)(?<from>\\S+).*\\b(?:query|insert|update|delete)\\b.*\\b(?<to>database|db)\\b"))),
    InteractionTemplate(
        "Request / response", listOf(
            InteractionRuleDraft("(?i)(?<from>\\S+).*\\brequest\\b.*\\bto\\s+(?<to>\\S+)"),
            InteractionRuleDraft("(?i)(?<from>\\S+).*\\bresponse\\b.*\\bfrom\\s+(?<to>\\S+)"),
        )
    ),
)

// ── Options ──────────────────────────────────────────────────────────────────────────────────

@Composable
private fun OptionsSection(state: AppState, spec: SeqDiagramSpec, onSpec: (SeqDiagramSpec) -> Unit) {
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
        AppText("Show elapsed time", fontSize = 11.sp)
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
    CheckRow(checked = o.showSelfMessages, onToggle = {
        onSpec(spec.copy(options = o.copy(showSelfMessages = !o.showSelfMessages)))
    }) { AppText("Show self events", fontSize = 11.sp) }
    if (spec.sourceEnrichment.enabled) {
        CheckRow(checked = o.showSourceInferred, onToggle = {
            onSpec(spec.copy(options = o.copy(showSourceInferred = !o.showSourceInferred)))
        }) { AppText("Show inferred source calls", fontSize = 11.sp) }
    } else {
        AppText(
            "Show inferred source calls — needs one-hop source calls enabled above.", color = tc.td, fontSize = 10.sp, maxLines = 2,
            modifier = Modifier.padding(horizontal = 12.dp),
        )
    }

    Row(
        Modifier.padding(horizontal = 12.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        AppText("Max arrows", fontSize = 11.sp)
        InlineField(
            o.maxMessages.toString(),
            { v -> v.toIntOrNull()?.takeIf { it > 0 }?.let { onSpec(spec.copy(options = o.copy(maxMessages = it))) } },
            "", Modifier.width(70.dp), fontSize = 11.sp,
        )
        AppText("Max label length", fontSize = 11.sp)
        InlineField(
            o.labelMaxChars.toString(),
            { v -> v.toIntOrNull()?.coerceIn(10, 400)?.let { onSpec(spec.copy(options = o.copy(labelMaxChars = it))) } },
            "", Modifier.width(70.dp), fontSize = 11.sp,
        )
    }
    Row(
        Modifier.padding(horizontal = 12.dp),
        horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically,
    ) {
        AppText("Label lines", fontSize = 11.sp)
        SegmentedControl(
            listOf("1", "2", "3", "4"),
            setOf((o.labelMaxLines - 1).coerceIn(0, 3)),
            onToggle = { idx -> onSpec(spec.copy(options = o.copy(labelMaxLines = idx + 1))) },
        )
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
}
