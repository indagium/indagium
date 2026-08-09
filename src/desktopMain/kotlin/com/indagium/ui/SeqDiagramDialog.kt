@file:OptIn(androidx.compose.ui.ExperimentalComposeUiApi::class)

package com.indagium.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusable
import androidx.compose.foundation.HorizontalScrollbar
import androidx.compose.foundation.VerticalScrollbar
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.rememberScrollbarAdapter
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.RectangleShape
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
import com.indagium.diagram.ArrowMode
import com.indagium.diagram.DiagramDialect
import com.indagium.diagram.DiagramParticipant
import com.indagium.diagram.DiagramParticipantRepresentation
import com.indagium.diagram.DiagramRange
import com.indagium.diagram.LabelSource
import com.indagium.diagram.ParticipantKind
import com.indagium.diagram.SeqDiagramSpec
import com.indagium.diagram.SeqDiagram
import com.indagium.diagram.diagramParticipantCandidates
import com.indagium.model.LogTab
import kotlinx.coroutines.launch
import kotlin.math.exp
import kotlin.math.roundToInt

// Above this many lifelines a sequence diagram stops being readable no matter how good the layout
// is — the user is told rather than silently handed spaghetti. Matches the builder's own
// auto-derive cap.
private const val PARTICIPANT_WARN_THRESHOLD = 8

private const val WORKSPACE_WIDTH = 1_320
private const val WORKSPACE_HEIGHT = 820
private const val INSPECTOR_WIDTH = 330

private data class CanvasZoomAnchor(val content: Offset, val pointer: Offset)

/**
 * The "build a sequence diagram" dialog.
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
fun SeqDiagramDialog(state: AppState) {
    val req = state.seqDiagrams.request
    val offline = state.seqDiagrams.offlineLibraryRequest
    if (req == null && offline == null) return
    val tab = req?.let { request -> state.tab(request.tabId) }
    val spec = req?.spec ?: offline!!.spec
    val readOnly = req == null || tab == null || state.seqDiagrams.libraryOpenReadOnly
    val tc = tc()
    var inspectorOpen by remember { mutableStateOf(true) }

    Dialog(
        onDismissRequest = { state.seqDiagrams.cancel() },
        properties = DialogProperties(usePlatformDefaultWidth = false, dismissOnClickOutside = false),
    ) {
        Column(
            Modifier.width(WORKSPACE_WIDTH.dp).height(WORKSPACE_HEIGHT.dp)
                .background(tc.p, CORNER_MD)
                .border(1.dp, tc.br, CORNER_MD)
                .padding(16.dp),
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
                AppButton(if (inspectorOpen) "Inspector ▾" else "Inspector ▸", { inspectorOpen = !inspectorOpen }, variant = ButtonVariant.Ghost)
                Spacer(Modifier.width(4.dp))
                CloseButton(onClick = { state.seqDiagrams.cancel() })
            }

            Row(Modifier.weight(1f), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                DiagramPreviewPane(state, Modifier.weight(1f).fillMaxHeight())
                if (inspectorOpen) {
                    Column(
                        Modifier.width(INSPECTOR_WIDTH.dp).fillMaxHeight()
                            .background(tc.bg, CORNER_SM).border(1.dp, tc.br, CORNER_SM)
                            .verticalScroll(rememberScrollState()),
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                    ) { if (tab != null && !readOnly) WorkspaceInspector(tab, state, spec) { state.seqDiagrams.updateSpec(it) }
                        else OfflineInspector(spec) }
                }
            }

            DialogFooter(state, req, readOnly)
        }
    }
}

@Composable
private fun OfflineInspector(spec: SeqDiagramSpec) {
    SectionHeader("Cached diagram")
    AppText("This diagram is viewable from its saved snapshot, but its source log is not open.", color = tc().td, fontSize = 11.sp, maxLines = 3, modifier = Modifier.padding(horizontal = 12.dp))
    AppText("Open or relink ${spec.sourceFile ?: "the source log"} to edit, regenerate, save, or attach it.", color = tc().td, fontSize = 10.sp, maxLines = 3, modifier = Modifier.padding(horizontal = 12.dp))
}

@Composable
private fun WorkspaceInspector(tab: LogTab, state: AppState, spec: SeqDiagramSpec, onSpec: (SeqDiagramSpec) -> Unit) {
    SectionHeader("Scope")
    RangeSection(tab, spec, onSpec)
    Divider()
    ParticipantSection(tab, state, spec, onSpec)
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

@Composable
private fun ParticipantSection(tab: LogTab, state: AppState, spec: SeqDiagramSpec, onSpec: (SeqDiagramSpec) -> Unit) {
    val tc = tc()
    // This is deliberately derived from the exact resolved/filter-visible range the builder uses,
    // rather than whole-tab analysis counts. Representation choices therefore never lie about
    // what will be shown, grouped into Other, or omitted.
    val candidates = remember(tab.id, spec) {
        diagramParticipantCandidates(tab, spec)
    }
    val selectedTags = spec.participants.filter { it.kind == ParticipantKind.TAG }.mapNotNull { it.tag }.toSet()
    val actors = spec.participants.filter { it.kind == ParticipantKind.ACTOR }
    var newActor by remember { mutableStateOf("") }

    fun setRepresentation(tag: String, representation: DiagramParticipantRepresentation) {
        val existing = spec.participants.firstOrNull { it.kind == ParticipantKind.TAG && it.tag == tag }
        val next = if (existing == null) {
            spec.participants + DiagramParticipant(tag, tag, ParticipantKind.TAG, tag = tag, representation = representation)
        } else {
            spec.participants.map { if (it.id == existing.id) it.copy(representation = representation) else it }
        }
        onSpec(spec.copy(participants = next))
    }

    SectionHeader("Participants")
    if (selectedTags.isEmpty()) {
        AppText("Recommended tags are based on the selected range.", color = tc.td, fontSize = 11.sp, maxLines = 2)
    } else if (selectedTags.size + actors.size > PARTICIPANT_WARN_THRESHOLD) {
        AppText(
            "${selectedTags.size + actors.size} lifelines — diagrams get hard to read past $PARTICIPANT_WARN_THRESHOLD.",
            color = DANGER_RED, fontSize = 11.sp, maxLines = 2,
        )
    }
    Column(
        Modifier.fillMaxWidth().heightIn(max = 170.dp).verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        candidates.forEach { candidate ->
            val rep = candidate.representation
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(3.dp)) {
                AppText(candidate.tag, fontSize = 10.sp, modifier = Modifier.weight(1f), overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis)
                AppText("${candidate.entryCount}", color = tc.td, fontSize = 9.sp)
                PillBtn("Show", rep == DiagramParticipantRepresentation.SHOW) { setRepresentation(candidate.tag, DiagramParticipantRepresentation.SHOW) }
                PillBtn("Other", rep == DiagramParticipantRepresentation.OTHER) { setRepresentation(candidate.tag, DiagramParticipantRepresentation.OTHER) }
                PillBtn("Hide", rep == DiagramParticipantRepresentation.HIDE) { setRepresentation(candidate.tag, DiagramParticipantRepresentation.HIDE) }
            }
        }
    }

    // The tag/id stays immutable; the display name is what reaches the canvas and exported
    // dialect, so a short alias makes a dense diagram readable without losing its provenance.
    if (spec.participants.isNotEmpty()) {
        AppText("Display names", color = tc.td, fontSize = 10.sp)
        spec.participants.forEach { participant ->
            // A raw log tag remains the stable identity.  When the source index can connect one
            // of that tag's in-range rows to code, offer the class/file name as a deliberate
            // display alias instead of replacing the tag automatically.
            val classSuggestion = remember(tab.id, participant.tag, state.settings.sourceFolders) {
                participant.tag?.let { tag ->
                    tab.logData.firstOrNull { it.tag == tag }?.let { entry ->
                        state.resolveLogSource(tag, entry.msg, limit = 1).firstOrNull()
                            ?.site?.filePath?.substringAfterLast('/')?.substringBeforeLast('.')
                    }
                }
            }
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(5.dp)) {
                AppText(participant.tag ?: "actor", color = tc.td, fontSize = 10.sp, maxLines = 1, modifier = Modifier.width(78.dp))
                InlineField(
                    participant.alias.orEmpty(),
                    { alias -> onSpec(spec.copy(participants = spec.participants.map { if (it.id == participant.id) it.copy(alias = alias.ifBlank { null }) else it })) },
                    "Display name", Modifier.weight(1f), fontSize = 10.sp,
                )
                if (!classSuggestion.isNullOrBlank() && classSuggestion != participant.alias) {
                    AppButton("Use class", {
                        onSpec(spec.copy(participants = spec.participants.map {
                            if (it.id == participant.id) it.copy(alias = classSuggestion) else it
                        }))
                    }, variant = ButtonVariant.Ghost)
                }
            }
        }
    }

    // External actors: entities that never appear as a logcat tag (the user, a peer device, a
    // backend) but which the interaction visibly enters from or exits to.
    actors.forEach { actor ->
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            AppText(actor.label, fontSize = 11.sp, modifier = Modifier.weight(1f))
            PillBtn("in", actor.isEntryPoint) {
                onSpec(spec.copy(participants = spec.participants.map { p ->
                    when {
                        p.id == actor.id -> p.copy(isEntryPoint = !p.isEntryPoint)
                        p.kind == ParticipantKind.ACTOR -> p.copy(isEntryPoint = false) // at most one entry point
                        else -> p
                    }
                }))
            }
            PillBtn("out", actor.isExitPoint) {
                onSpec(spec.copy(participants = spec.participants.map { p ->
                    when {
                        p.id == actor.id -> p.copy(isExitPoint = !p.isExitPoint)
                        p.kind == ParticipantKind.ACTOR -> p.copy(isExitPoint = false)
                        else -> p
                    }
                }))
            }
            AppButton("×", { onSpec(spec.copy(participants = spec.participants.filterNot { it.id == actor.id })) }, variant = ButtonVariant.Ghost)
        }
    }
    Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
        InlineField(newActor, { newActor = it }, "Add external actor…", Modifier.weight(1f), fontSize = 11.sp)
        AppButton("Add", {
            val name = newActor.trim()
            if (name.isNotEmpty() && spec.participants.none { it.id == name }) {
                onSpec(spec.copy(participants = spec.participants + DiagramParticipant(name, name, ParticipantKind.ACTOR)))
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
            if (sel.size >= 2) onSpec(spec.copy(range = DiagramRange.Ids(sel.min(), sel.max())))
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
    if (range is DiagramRange.Ids && tab.selected.size < 2) {
        AppText("Select rows in the log to change this.", color = tc.td, fontSize = 10.sp, maxLines = 2)
    }
}

// ── Mode ─────────────────────────────────────────────────────────────────────────────────────

@Composable
private fun ModeSection(spec: SeqDiagramSpec, onSpec: (SeqDiagramSpec) -> Unit) {
    val tc = tc()
    SectionHeader("Interactions")
    Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
        PillBtn("Tag handoff", spec.mode == ArrowMode.TAG_TRANSITION) { onSpec(spec.copy(mode = ArrowMode.TAG_TRANSITION)) }
        PillBtn("Rules", spec.mode == ArrowMode.RULES) { onSpec(spec.copy(mode = ArrowMode.RULES)) }
        PillBtn("Timeline", spec.mode == ArrowMode.LINE_PER_MESSAGE) { onSpec(spec.copy(mode = ArrowMode.LINE_PER_MESSAGE)) }
    }
    val explanation = when (spec.mode) {
        ArrowMode.TAG_TRANSITION -> "An arrow whenever the logging tag changes."
        ArrowMode.RULES -> "Regex rules with (?<from>) (?<to>) (?<msg>) groups; unmatched lines fall back to tag handoff."
        ArrowMode.LINE_PER_MESSAGE -> "Every line as an event on its own tag's lifeline."
    }
    AppText(explanation, color = tc.td, fontSize = 10.sp, maxLines = 3)
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
                AppText(rule.pattern, fontSize = 10.sp, fontFamily = MONO, modifier = Modifier.weight(1f), maxLines = 1, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis)
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
    InteractionTemplate("HTTP request", listOf(InteractionRuleDraft("(?i)(?<from>\\S+).*\\b(?<verb>GET|POST|PUT|DELETE|PATCH)\\s+(?<to>https?://\\S+)", label = "\${verb} \${msg}"))),
    InteractionTemplate("RPC / Binder", listOf(InteractionRuleDraft("(?i)(?<from>\\S+).*\\b(?:rpc|binder)\\b.*\\bto\\s+(?<to>\\S+)"))),
    InteractionTemplate("Broadcast", listOf(InteractionRuleDraft("(?i)(?<from>\\S+).*\\bbroadcast\\b.*\\bto\\s+(?<to>\\S+)"))),
    InteractionTemplate("Worker / job", listOf(InteractionRuleDraft("(?i)(?<from>\\S+).*\\b(?:worker|job)\\b.*\\bto\\s+(?<to>\\S+)"))),
    InteractionTemplate("Socket", listOf(InteractionRuleDraft("(?i)(?<from>\\S+).*\\bsocket\\b.*\\bto\\s+(?<to>\\S+)"))),
    InteractionTemplate("Database", listOf(InteractionRuleDraft("(?i)(?<from>\\S+).*\\b(?:query|insert|update|delete)\\b.*\\b(?<to>database|db)\\b"))),
    InteractionTemplate("Request / response", listOf(
        InteractionRuleDraft("(?i)(?<from>\\S+).*\\brequest\\b.*\\bto\\s+(?<to>\\S+)"),
        InteractionRuleDraft("(?i)(?<from>\\S+).*\\bresponse\\b.*\\bfrom\\s+(?<to>\\S+)"),
    )),
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
        val diagram = preview.diagramOrNull
        when {
            diagram != null -> {
                val rendered = remember(diagram, theme) { DiagramRenderCache.render(diagram, theme) }
                val bitmap = remember(rendered) { rendered.toComposeBitmap() }
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
                                } else false
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
                                                            content = Offset((horizontal.value + change.position.x) / zoom, (vertical.value + change.position.y) / zoom),
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
private fun DialogFooter(state: AppState, request: SeqDiagramRequest?, readOnly: Boolean) {
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
        DialogActionButton("Cancel", active = false) { state.seqDiagrams.cancel() }
    }
}
