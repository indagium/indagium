@file:OptIn(androidx.compose.ui.ExperimentalComposeUiApi::class)

package com.indagium.ui

import androidx.compose.foundation.HorizontalScrollbar
import androidx.compose.foundation.VerticalScrollbar
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.focusable
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
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.rememberScrollbarAdapter
import androidx.compose.foundation.verticalScroll
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.indagium.diagram.ActivationPolicy
import com.indagium.diagram.ArrowMode
import com.indagium.diagram.DiagramActor
import com.indagium.diagram.DiagramComponent
import com.indagium.diagram.DiagramDialect
import com.indagium.diagram.DiagramRange
import com.indagium.diagram.LabelSource
import com.indagium.diagram.MirrorDirection
import com.indagium.diagram.ParticipantKind
import com.indagium.diagram.SeqDiagram
import com.indagium.diagram.SeqDiagramSpec
import com.indagium.diagram.UnmappedTagPolicy
import com.indagium.diagram.displayName
import com.indagium.model.LogTab
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
 *
 * NOTE the `usePlatformDefaultWidth = false`: without it Compose Desktop silently clamps a Dialog's
 * content to a ported-from-Android "preferred dialog width" (~580dp here), and every width set
 * below would be a no-op. See the same flag and comment on SettingsDialog's call site in App.kt.
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
            AppButton(
                if (session.inspectorOpen) "Inspector ▾" else "Inspector ▸",
                { state.seqDiagrams.updateInspector(open = !session.inspectorOpen) },
                variant = ButtonVariant.Ghost
            )
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

        WorkspaceFooter(state, req, readOnly, onClose = ::requestClose)
    }
    if (state.seqDiagrams.pendingCloseWorkspaceId == workspaceId) {
        Dialog(onDismissRequest = { state.seqDiagrams.cancelWorkspaceClose() }, properties = DialogProperties(dismissOnClickOutside = false)) {
            Column(
                Modifier.width(330.dp).background(tc.p, CORNER_MD).border(1.dp, tc.br, CORNER_MD).padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                AppText("Save diagram draft?", fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
                AppText("This workspace has unsaved changes.", color = tc.td, fontSize = 11.sp)
                Row(horizontalArrangement = Arrangement.spacedBy(7.dp), modifier = Modifier.fillMaxWidth()) {
                    DialogActionButton("Save", active = true, enabled = state.seqDiagrams.preview.diagramOrNull != null) {
                        state.seqDiagrams.closeWorkspace(
                            workspaceId,
                            save = true
                        )
                    }
                    DialogActionButton("Discard", active = false) { state.seqDiagrams.closeWorkspace(workspaceId) }
                    DialogActionButton("Cancel", active = false) { state.seqDiagrams.cancelWorkspaceClose() }
                }
            }
        }
    }
}

@Composable
private fun OfflineInspector(spec: SeqDiagramSpec) {
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
private fun WorkspaceInspector(tab: LogTab, state: AppState, spec: SeqDiagramSpec, onSpec: (SeqDiagramSpec) -> Unit) {
    SectionHeader("Scope")
    RangeSection(tab, spec, onSpec)
    Divider()
    ComponentSection(tab, state, spec, onSpec)
    Divider()
    ModeSection(spec, onSpec)
    Divider()
    OptionsSection(state, spec, onSpec)
}

private fun rangeSummary(range: DiagramRange): String = when (range) {
    is DiagramRange.Ids -> "Lines ${range.from}–${range.to}"
    is DiagramRange.Time -> "${range.fromTs.ifBlank { "start" }}–${range.toTs.ifBlank { "end" }}"
    DiagramRange.VisibleView -> "Current filtered view"
    is DiagramRange.SeqGroupRef -> "Sequence group ${range.gid}"
}

// ── Participants ─────────────────────────────────────────────────────────────────────────────

/** Component-first curation.  A component owns one or more raw tags; raw tags remain visible in
 * the inspector so merging never obscures provenance.  The old participant editor below remains
 * only as a legacy-note compatibility reader and is not used by a new workspace. */
@Composable
private fun ComponentSection(tab: LogTab, state: AppState, spec: SeqDiagramSpec, onSpec: (SeqDiagramSpec) -> Unit) {
    val tc = tc()
    val candidateState = state.seqDiagrams.candidatePreview
    val candidates = candidateState.valuesOrEmpty
    // Source-view changes invalidate tiers, but title/component toggles don't.  The coordinator
    // does the expensive range scan in its cancellable IO lane.
    LaunchedEffect(tab.id, System.identityHashCode(tab.logData), System.identityHashCode(tab.filter), spec.range) {
        state.seqDiagrams.requestCandidates(tab.id, spec)
    }
    val ownerByTag = remember(spec.components) {
        spec.components.flatMap { component -> component.tagIds.map { it to component } }.toMap()
    }
    var mergeTags by remember(spec.components) { mutableStateOf(emptySet<String>()) }
    var newActor by remember(spec.actors) { mutableStateOf("") }
    var tagSearch by remember { mutableStateOf("") }

    fun addRealComponent(component: DiagramComponent): List<DiagramComponent> =
        spec.components.filter { it.tagIds.isNotEmpty() } + component

    fun toggleTag(tag: String, enabled: Boolean) {
        val owner = ownerByTag[tag]
        val components = if (owner == null) {
            addRealComponent(DiagramComponent(tag, tag, setOf(tag), enabled))
        } else {
            spec.components.map { if (it.id == owner.id) it.copy(enabled = enabled) else it }
        }
        onSpec(spec.copy(components = components))
    }

    fun mergeSelected() {
        if (mergeTags.size < 2) return
        val involved = spec.components.filter { component -> component.tagIds.any { it in mergeTags } }
        val tags = (involved.flatMap { it.tagIds } + mergeTags).toSet()
        val component = DiagramComponent(
            id = "component-${System.nanoTime()}", displayName = tags.first(), tagIds = tags,
            enabled = involved.any { it.enabled } || involved.isEmpty(),
        )
        onSpec(
            spec.copy(
                components = spec.components.filter { it.tagIds.isNotEmpty() && it !in involved } + component,
            ),
        )
        mergeTags = emptySet()
    }

    SectionHeader("Components")
    AppText("Enabled components participate in the diagram. Select raw tags to merge them.", color = tc.td, fontSize = 10.sp, maxLines = 2)
    InlineField(tagSearch, { tagSearch = it }, "Search all log tags…", Modifier.fillMaxWidth(), fontSize = 10.sp)
    if (tagSearch.isNotBlank()) {
        tab.analysis.tagCounts.entries.asSequence()
            .filter { (tag, _) -> tag.contains(tagSearch.trim(), ignoreCase = true) && tag !in ownerByTag }
            .sortedByDescending { it.value }.take(8).forEach { (tag, count) ->
                Row(verticalAlignment = Alignment.CenterVertically) {
                    AppText(tag, fontSize = 10.sp, modifier = Modifier.weight(1f), overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis)
                    AppText("$count total", color = tc.td, fontSize = 9.sp)
                    AppButton("Add disabled", {
                        onSpec(
                            spec.copy(
                                components = addRealComponent(
                                    DiagramComponent(tag, tag, setOf(tag), enabled = false),
                                ),
                            ),
                        )
                    }, variant = ButtonVariant.Ghost)
                }
            }
    }
    if (candidateState is DiagramCandidateState.Computing) {
        AppText("Refreshing tag tiers…", color = tc.td, fontSize = 10.sp)
    } else if (candidateState is DiagramCandidateState.Failed) {
        AppText(candidateState.message, color = DANGER_RED, fontSize = 10.sp)
    }
    Column(Modifier.fillMaxWidth().heightIn(max = 180.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(2.dp)) {
        candidates.forEach { candidate ->
            val component = ownerByTag[candidate.tag]
            val enabled = component?.enabled == true
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(3.dp)) {
                CheckRow(checked = candidate.tag in mergeTags, onToggle = {
                    mergeTags = if (candidate.tag in mergeTags) mergeTags - candidate.tag else mergeTags + candidate.tag
                }) { }
                CheckRow(checked = enabled, onToggle = { toggleTag(candidate.tag, !enabled) }) { }
                AppText(candidate.tag, fontSize = 10.sp, modifier = Modifier.weight(1f), overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis)
                AppText("${candidate.entryCount}", color = tc.td, fontSize = 9.sp)
            }
        }
    }
    Row(horizontalArrangement = Arrangement.spacedBy(5.dp), verticalAlignment = Alignment.CenterVertically) {
        AppButton("Merge selected", ::mergeSelected, enabled = mergeTags.size > 1, variant = ButtonVariant.Ghost)
        CheckRow(checked = spec.unmappedTagPolicy == UnmappedTagPolicy.GROUP_AS_OTHER, onToggle = {
            val next = if (spec.unmappedTagPolicy == UnmappedTagPolicy.HIDE) {
                UnmappedTagPolicy.GROUP_AS_OTHER
            } else {
                UnmappedTagPolicy.HIDE
            }
            onSpec(spec.copy(unmappedTagPolicy = next))
        }) { AppText("Group unmapped as Other", fontSize = 10.sp) }
    }
    spec.components.filter { it.tagIds.isNotEmpty() }.forEach { component ->
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            InlineField(component.displayName, { name ->
                onSpec(spec.copy(components = spec.components.map { if (it.id == component.id) it.copy(displayName = name) else it }))
            }, "Component name", Modifier.weight(1f), fontSize = 10.sp)
            AppText("${component.tagIds.size} tags", color = tc.td, fontSize = 9.sp)
            if (component.tagIds.size > 1) AppButton("Unmerge", {
                val split = component.tagIds.map { tag -> DiagramComponent(tag, tag, setOf(tag), component.enabled) }
                onSpec(spec.copy(components = spec.components.filterNot { it.id == component.id } + split))
            }, variant = ButtonVariant.Ghost)
        }
    }
    Divider()
    SectionHeader("Actors")
    spec.actors.forEach { actor ->
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            InlineField(
                actor.label,
                { label ->
                    onSpec(
                        spec.copy(
                            actors = spec.actors.map { if (it.id == actor.id) it.copy(label = label) else it },
                            participants = spec.participants.map {
                                if (it.id == actor.id) it.copy(label = label) else it
                            },
                        ),
                    )
                },
                "Actor",
                Modifier.weight(1f),
                fontSize = 10.sp
            )
            val targets = spec.components.filter { it.tagIds.isNotEmpty() }
            val currentTarget = targets.indexOfFirst { it.id == actor.mirrorComponentId }
            AppButton(actor.mirrorComponentId?.let { id -> targets.firstOrNull { it.id == id }?.displayName } ?: "No mirror", {
                val target = targets.getOrNull(currentTarget + 1)?.id
                onSpec(spec.copy(actors = spec.actors.map { if (it.id == actor.id) it.copy(mirrorComponentId = target) else it }))
            }, variant = ButtonVariant.Ghost)
            if (actor.mirrorComponentId != null) AppButton(actor.mirrorDirection.name.lowercase(), {
                val direction = when (actor.mirrorDirection) {
                    MirrorDirection.INBOUND -> MirrorDirection.OUTBOUND
                    MirrorDirection.OUTBOUND -> MirrorDirection.BOTH
                    MirrorDirection.BOTH -> MirrorDirection.INBOUND
                }
                onSpec(spec.copy(actors = spec.actors.map { if (it.id == actor.id) it.copy(mirrorDirection = direction) else it }))
            }, variant = ButtonVariant.Ghost)
            AppButton("×", {
                onSpec(
                    spec.copy(
                        actors = spec.actors.filterNot { it.id == actor.id },
                        participants = spec.participants.filterNot { it.id == actor.id },
                    ),
                )
            }, variant = ButtonVariant.Ghost)
        }
    }
    // Keep entry/exit controls for v1/v2 actors while the codec migrates them forward.
    spec.participants.filter { it.kind == ParticipantKind.ACTOR }.forEach { actor ->
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            AppText(actor.displayName, fontSize = 10.sp, modifier = Modifier.weight(1f))
            PillBtn(
                "in",
                actor.isEntryPoint
            ) {
                onSpec(spec.copy(participants = spec.participants.map {
                    if (it.id == actor.id) it.copy(isEntryPoint = !it.isEntryPoint) else if (it.kind == ParticipantKind.ACTOR) it.copy(
                        isEntryPoint = false
                    ) else it
                }))
            }
            PillBtn(
                "out",
                actor.isExitPoint
            ) {
                onSpec(spec.copy(participants = spec.participants.map {
                    if (it.id == actor.id) it.copy(isExitPoint = !it.isExitPoint) else if (it.kind == ParticipantKind.ACTOR) it.copy(
                        isExitPoint = false
                    ) else it
                }))
            }
        }
    }
    Row(horizontalArrangement = Arrangement.spacedBy(5.dp), verticalAlignment = Alignment.CenterVertically) {
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

// ── Range ────────────────────────────────────────────────────────────────────────────────────

@Composable
private fun RangeSection(tab: LogTab, spec: SeqDiagramSpec, onSpec: (SeqDiagramSpec) -> Unit) {
    val tc = tc()
    val range = spec.range
    SectionHeader("Range")
    Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
        PillBtn("Selection", range is DiagramRange.Ids) {
            val sel = tab.selected
            if (sel.isNotEmpty()) onSpec(spec.copy(range = DiagramRange.Ids(sel.min(), sel.max())))
        }
        PillBtn("Whole view", range is DiagramRange.VisibleView) { onSpec(spec.copy(range = DiagramRange.VisibleView)) }
        PillBtn("Time", range is DiagramRange.Time) { onSpec(spec.copy(range = DiagramRange.Time("", ""))) }
    }
    when (range) {
        is DiagramRange.Ids -> AppText("Lines ${range.from}–${range.to}", color = tc.td, fontSize = 11.sp)
        is DiagramRange.VisibleView -> AppText(
            "Everything the current filter shows.", color = tc.td, fontSize = 11.sp, maxLines = 2,
        )

        is DiagramRange.Time -> Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            InlineField(range.fromTs, { onSpec(spec.copy(range = range.copy(fromTs = it))) }, "from HH:MM:SS", Modifier.weight(1f), fontSize = 11.sp)
            InlineField(range.toTs, { onSpec(spec.copy(range = range.copy(toTs = it))) }, "to HH:MM:SS", Modifier.weight(1f), fontSize = 11.sp)
        }

        is DiagramRange.SeqGroupRef -> AppText("Sequence group ${range.gid}", color = tc.td, fontSize = 11.sp)
    }
    if (range is DiagramRange.Ids && tab.selected.isEmpty()) {
        AppText("Select one or more rows in the log to change this.", color = tc.td, fontSize = 10.sp, maxLines = 2)
    }
}

// ── Mode ─────────────────────────────────────────────────────────────────────────────────────

@Composable
private fun ModeSection(spec: SeqDiagramSpec, onSpec: (SeqDiagramSpec) -> Unit) {
    val tc = tc()
    SectionHeader("Interactions")
    Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
        PillBtn("Component flow", spec.mode == ArrowMode.TAG_TRANSITION) { onSpec(spec.copy(mode = ArrowMode.TAG_TRANSITION)) }
        PillBtn("Rules", spec.mode == ArrowMode.RULES) { onSpec(spec.copy(mode = ArrowMode.RULES)) }
        PillBtn("Timeline", spec.mode == ArrowMode.LINE_PER_MESSAGE) { onSpec(spec.copy(mode = ArrowMode.LINE_PER_MESSAGE)) }
    }
    val explanation = when (spec.mode) {
        ArrowMode.TAG_TRANSITION -> "An arrow whenever the logging tag changes."
        ArrowMode.RULES -> "Regex rules with (?<from>) (?<to>) (?<msg>) groups; unmatched lines fall back to tag handoff."
        ArrowMode.LINE_PER_MESSAGE -> "Every line as an event on its own tag's lifeline."
    }
    AppText(explanation, color = tc.td, fontSize = 10.sp, maxLines = 3)
    val enabledComponents = spec.components.count { it.enabled && it.tagIds.isNotEmpty() }
    val oneRow = (spec.range as? DiagramRange.Ids)?.let { it.from == it.to } == true
    if (spec.mode == ArrowMode.TAG_TRANSITION && (oneRow || enabledComponents == 1)) {
        AppText(
            if (oneRow) "A one-row scope has no component handoff." else "One enabled component has no component handoff.",
            color = tc.td, fontSize = 10.sp, maxLines = 2,
        )
        AppButton("Use event timeline", { onSpec(spec.copy(mode = ArrowMode.LINE_PER_MESSAGE)) }, variant = ButtonVariant.Ghost)
    }
    CheckRow(checked = spec.sourceEnrichment.enabled, onToggle = {
        onSpec(spec.copy(sourceEnrichment = spec.sourceEnrichment.copy(enabled = !spec.sourceEnrichment.enabled)))
    }) { AppText("Add one-hop source calls", fontSize = 10.sp) }
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
        Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
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
    InlineField(pattern, { pattern = it }, "regex with named groups…", Modifier.fillMaxWidth(), fontSize = 10.sp)
    Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
        InlineField(from, { from = it }, "from", Modifier.weight(1f), fontSize = 10.sp)
        InlineField(to, { to = it }, "to", Modifier.weight(1f), fontSize = 10.sp)
        InlineField(label, { label = it }, "label", Modifier.weight(1f), fontSize = 10.sp)
    }
    if (draftError != null) AppText("Invalid regex: ${draftError.take(90)}", color = DANGER_RED, fontSize = 10.sp, maxLines = 2)
    AppButton("Add rule", {
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
    }, enabled = pattern.isNotBlank() && draftError == null)

    AppText("Suggested interactions", color = tc().td, fontSize = 10.sp)
    interactionTemplates.forEach { template ->
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(5.dp)) {
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
        AppText("Label with source method — needs an indexed source folder.", color = tc.td, fontSize = 10.sp, maxLines = 2)
    }
    CheckRow(checked = o.activationPolicy == ActivationPolicy.EVIDENCE_BACKED, onToggle = {
        val next = if (o.activationPolicy == ActivationPolicy.NONE) {
            ActivationPolicy.EVIDENCE_BACKED
        } else {
            ActivationPolicy.NONE
        }
        onSpec(spec.copy(options = o.copy(activationPolicy = next)))
    }) { AppText("Evidence-backed activations", fontSize = 11.sp) }

    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        AppText("Max arrows", fontSize = 11.sp)
        InlineField(
            o.maxMessages.toString(),
            { v -> v.toIntOrNull()?.takeIf { it > 0 }?.let { onSpec(spec.copy(options = o.copy(maxMessages = it))) } },
            "", Modifier.width(70.dp), fontSize = 11.sp,
        )
    }
    Row(horizontalArrangement = Arrangement.spacedBy(4.dp), verticalAlignment = Alignment.CenterVertically) {
        AppText("Format", fontSize = 11.sp)
        PillBtn("Mermaid", spec.dialect == DiagramDialect.MERMAID) { onSpec(spec.copy(dialect = DiagramDialect.MERMAID)) }
        PillBtn("PlantUML", spec.dialect == DiagramDialect.PLANTUML) { onSpec(spec.copy(dialect = DiagramDialect.PLANTUML)) }
    }
}

// ── Preview ──────────────────────────────────────────────────────────────────────────────────

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
            horizontalArrangement = Arrangement.spacedBy(4.dp), verticalAlignment = Alignment.CenterVertically,
        ) {
            AppText("Canvas", fontSize = 11.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
            AppButton("−", { zoom = (zoom - .15f).coerceAtLeast(.15f) }, variant = ButtonVariant.Ghost)
            AppButton("${(zoom * 100).toInt()}%", { zoom = 1f }, variant = ButtonVariant.Ghost)
            AppButton("+", { zoom = (zoom + .15f).coerceAtMost(2.5f) }, variant = ButtonVariant.Ghost)
            AppButton("Fit", { zoom = fitZoom }, variant = ButtonVariant.Ghost)
            AppButton("Fit width", { zoom = fitWidthZoom }, variant = ButtonVariant.Ghost)
            AppButton("Reset", {
                zoom = 1f
                zoomAnchor = null
                scope.launch { vertical.scrollTo(0); horizontal.scrollTo(0) }
            }, variant = ButtonVariant.Ghost)
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
                        modifier = Modifier.align(Alignment.CenterEnd).fillMaxHeight().padding(vertical = 4.dp).width(7.dp),
                        style = appScrollbarStyle(tc),
                    )
                    HorizontalScrollbar(
                        adapter = rememberScrollbarAdapter(horizontal),
                        modifier = Modifier.align(Alignment.BottomStart).fillMaxWidth().height(7.dp).padding(horizontal = 4.dp),
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

@Composable
private fun WorkspaceFooter(state: AppState, request: SeqDiagramRequest?, readOnly: Boolean, onClose: () -> Unit) {
    val tc = tc()
    val ready = state.seqDiagrams.preview.diagramOrNull?.messages?.isNotEmpty() == true
    val offlineSource = state.seqDiagrams.offlineLibraryRequest?.item?.parsed?.source
    val linkedPrimary = state.settings.diagramLinkedNotePrimary

    fun attach(link: Boolean) {
        val req = request ?: return
        // Attachments always reference a durable draft. Saving first also means a second attach
        // can choose snapshot or link without rebuilding/closing the workspace.
        val libraryId = req.libraryItemId ?: state.seqDiagrams.saveDraft()?.id ?: return
        val blockId = if (link) state.seqDiagrams.attachLibraryLink(req.tabId, libraryId) else state.seqDiagrams.attachLibrarySnapshot(req.tabId, libraryId)
        if (blockId != null) state.annotationVisible = true
    }
    Row(
        Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterHorizontally),
    ) {
        DialogActionButton("Save draft", active = false, enabled = ready && !readOnly) {
            state.seqDiagrams.saveDraft()
        }
        DialogActionButton(if (linkedPrimary) "Attach linked" else "Attach snapshot", active = true, enabled = ready && !readOnly) {
            attach(linkedPrimary)
        }
        DialogActionButton(if (linkedPrimary) "Attach snapshot" else "Attach linked", active = false, enabled = ready && !readOnly) {
            attach(!linkedPrimary)
        }
        DialogActionButton("Copy source", active = false, enabled = ready) {
            (state.seqDiagrams.currentSource() ?: offlineSource)?.let { state.copyToClipboard(it) }
        }
        DialogActionButton("Copy image", active = false, enabled = ready) {
            // The plain-text fallback is the dialect source: a paste target that can't take an
            // image (a code review comment, a terminal) still receives something meaningful.
            val source = state.seqDiagrams.currentSource() ?: offlineSource.orEmpty()
            state.seqDiagrams.currentPng(tc.toDiagramTheme())?.let { state.copyImageToClipboard(it, source) }
        }
        DialogActionButton("Close", active = false, onClick = onClose)
    }
}
