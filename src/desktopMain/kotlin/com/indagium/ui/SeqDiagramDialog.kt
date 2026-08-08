package com.indagium.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.indagium.diagram.ArrowMode
import com.indagium.diagram.DiagramDialect
import com.indagium.diagram.DiagramParticipant
import com.indagium.diagram.DiagramRange
import com.indagium.diagram.LabelSource
import com.indagium.diagram.ParticipantKind
import com.indagium.diagram.SeqDiagramSpec
import com.indagium.model.LogTab

// Above this many lifelines a sequence diagram stops being readable no matter how good the layout
// is — the user is told rather than silently handed spaghetti. Matches the builder's own
// auto-derive cap.
private const val PARTICIPANT_WARN_THRESHOLD = 12

private const val DIALOG_WIDTH = 940
private const val LEFT_COLUMN_WIDTH = 330

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
    val req = state.seqDiagrams.request ?: return
    val tab = state.tab(req.tabId) ?: return
    val tc = tc()

    Dialog(
        onDismissRequest = { state.seqDiagrams.cancel() },
        properties = DialogProperties(usePlatformDefaultWidth = false, dismissOnClickOutside = false),
    ) {
        Column(
            Modifier.width(DIALOG_WIDTH.dp)
                .background(tc.p, CORNER_MD)
                .border(1.dp, tc.br, CORNER_MD)
                .padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                AppText(
                    if (req.editingBlockId != null) "Edit sequence diagram" else "Sequence diagram",
                    fontSize = 15.sp,
                    fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.weight(1f),
                )
                CloseButton(onClick = { state.seqDiagrams.cancel() })
            }

            Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                Column(
                    Modifier.width(LEFT_COLUMN_WIDTH.dp).heightIn(max = 520.dp).verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(14.dp),
                ) {
                    ParticipantSection(tab, req.spec) { state.seqDiagrams.updateSpec(it) }
                    RangeSection(tab, req.spec) { state.seqDiagrams.updateSpec(it) }
                    ModeSection(req.spec) { state.seqDiagrams.updateSpec(it) }
                    OptionsSection(state, req.spec) { state.seqDiagrams.updateSpec(it) }
                }
                DiagramPreviewPane(state, Modifier.weight(1f).heightIn(min = 300.dp, max = 520.dp))
            }

            DialogFooter(state)
        }
    }
}

// ── Participants ─────────────────────────────────────────────────────────────────────────────

@Composable
private fun ParticipantSection(tab: LogTab, spec: SeqDiagramSpec, onSpec: (SeqDiagramSpec) -> Unit) {
    val tc = tc()
    // tagCounts is populated even while the background analysis is still pending (see LogAnalysis's
    // own doc), so this list is available the instant a file opens.
    val sortedTags = remember(tab.id, tab.analysis.tagCounts) {
        tab.analysis.tagCounts.entries.sortedByDescending { it.value }
    }
    val selectedTags = spec.participants.filter { it.kind == ParticipantKind.TAG }.mapNotNull { it.tag }.toSet()
    val actors = spec.participants.filter { it.kind == ParticipantKind.ACTOR }
    var newActor by remember { mutableStateOf("") }

    fun toggleTag(tag: String) {
        val existing = spec.participants.filter { it.kind == ParticipantKind.TAG }
        val next = if (tag in selectedTags) {
            existing.filterNot { it.tag == tag }
        } else {
            existing + DiagramParticipant(id = tag, label = tag, kind = ParticipantKind.TAG, tag = tag)
        }
        onSpec(spec.copy(participants = actors + next))
    }

    SectionHeader("Participants")
    if (selectedTags.isEmpty()) {
        AppText("None selected — the busiest tags in range are used automatically.", color = tc.td, fontSize = 11.sp, maxLines = 2)
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
        sortedTags.forEach { (tag, count) ->
            CheckRow(checked = tag in selectedTags, onToggle = { toggleTag(tag) }) {
                AppText(tag, fontSize = 11.sp, modifier = Modifier.weight(1f), overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis)
                AppText(count.toString(), color = tc.td, fontSize = 10.sp)
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
    SectionHeader("Arrows")
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

    spec.rules.forEach { rule ->
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            AppText(rule.pattern, fontSize = 10.sp, fontFamily = MONO, modifier = Modifier.weight(1f))
            AppButton("×", { onSpec(spec.copy(rules = spec.rules.filterNot { it.id == rule.id })) }, variant = ButtonVariant.Ghost)
        }
    }
    InlineField(pattern, { pattern = it }, "regex with named groups…", Modifier.fillMaxWidth(), fontSize = 10.sp)
    Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
        InlineField(from, { from = it }, "from", Modifier.weight(1f), fontSize = 10.sp)
        InlineField(to, { to = it }, "to", Modifier.weight(1f), fontSize = 10.sp)
        InlineField(label, { label = it }, "label", Modifier.weight(1f), fontSize = 10.sp)
    }
    AppButton("Add rule", {
        if (pattern.isNotBlank()) {
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
    }, enabled = pattern.isNotBlank())
}

// ── Options ──────────────────────────────────────────────────────────────────────────────────

@Composable
private fun OptionsSection(state: AppState, spec: SeqDiagramSpec, onSpec: (SeqDiagramSpec) -> Unit) {
    val tc = tc()
    val o = spec.options
    // SOURCE_METHOD/BOTH resolve each line against the source index; offering them with no folder
    // indexed would just silently fall back to the message for every arrow.
    val sourceAvailable = state.settings.sourceFolders.isNotEmpty()

    SectionHeader("Options")
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

    Column(modifier.background(tc.bg, CORNER_SM).border(1.dp, tc.br, CORNER_SM)) {
        val diagram = preview.diagramOrNull
        when {
            diagram != null -> {
                val rendered = remember(diagram, theme) { DiagramRenderCache.render(diagram, theme) }
                val bitmap = remember(rendered) { rendered.toComposeBitmap() }
                Box(Modifier.weight(1f).verticalScroll(rememberScrollState()).horizontalScroll(rememberScrollState())) {
                    androidx.compose.foundation.Image(
                        bitmap = bitmap,
                        contentDescription = "Sequence diagram preview",
                        // Drawn at logical size: the raster is 2x (see renderSequenceDiagram's
                        // scale), which is what keeps it crisp on a HiDPI display.
                        modifier = Modifier.width((rendered.widthPx / rendered.scale).dp).height((rendered.heightPx / rendered.scale).dp),
                    )
                }
                val warnings = diagram.warnings
                Column(Modifier.padding(6.dp)) {
                    AppText(
                        "${diagram.messages.size} arrows · ${diagram.participants.size} lifelines · ${diagram.scannedEntries} lines scanned" +
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
private fun DialogFooter(state: AppState) {
    val tc = tc()
    val ready = state.seqDiagrams.preview.diagramOrNull?.messages?.isNotEmpty() == true
    Row(
        Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterHorizontally),
    ) {
        DialogActionButton("Add to notes", active = true, enabled = ready) {
            // Reveal the Notes panel on success, so the block the user just created is visible
            // rather than silently appended to a hidden panel.
            state.seqDiagrams.confirm()?.let { state.annotationVisible = true }
        }
        DialogActionButton("Copy source", active = false, enabled = ready) {
            state.seqDiagrams.currentSource()?.let { state.copyToClipboard(it) }
        }
        DialogActionButton("Copy image", active = false, enabled = ready) {
            // The plain-text fallback is the dialect source: a paste target that can't take an
            // image (a code review comment, a terminal) still receives something meaningful.
            val source = state.seqDiagrams.currentSource().orEmpty()
            state.seqDiagrams.currentPng(tc.toDiagramTheme())?.let { state.copyImageToClipboard(it, source) }
        }
        DialogActionButton("Cancel", active = false) { state.seqDiagrams.cancel() }
    }
}
