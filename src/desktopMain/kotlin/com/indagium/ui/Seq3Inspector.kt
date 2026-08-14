package com.indagium.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.indagium.diagram3.DEFAULT_SEQ3_REPEAT_THRESHOLD
import com.indagium.diagram3.Seq3BulkAction
import com.indagium.diagram3.Seq3Command
import com.indagium.diagram3.Seq3Kind
import com.indagium.diagram3.Seq3Match
import com.indagium.diagram3.Seq3Message
import com.indagium.diagram3.Seq3Occurrence
import com.indagium.diagram3.Seq3Repeat

// ── The inspector — design spec §03 ─────────────────────────────────────────────────────────────
//
// One message at a time (never a bulk surface — that is the dark selection action bar's job,
// Seq3QueuePanel.kt's `Seq3SelectionActionBar`). The pattern field is "the power-user escape
// hatch" (spec's own words for the pattern field specifically, but the same posture applies to
// every field here): everything is a real, named `Seq3Command`, never a direct document mutation.
// Evidence is READ ONLY — see [Seq3EvidenceList]'s own doc.

@Composable
internal fun Seq3Inspector(state: AppState, session: Seq3WorkspaceSession, view: Seq3ViewState, modifier: Modifier) {
    val tc = tc()
    val messageId = view.inspectorMessageId
    val message = messageId?.let { id -> session.document.messages.firstOrNull { it.id == id } }
    Column(modifier.background(tc.p).fillMaxSize()) {
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            AppText("Inspector", color = tc.tx, fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
            CloseButton(onClick = { view.inspectorMessageId = null })
        }
        if (message == null) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                AppText("Select a message to inspect", color = tc.td, fontSize = 11.sp)
            }
        } else {
            Seq3InspectorBody(state, session, message)
        }
    }
}

@Composable
private fun Seq3InspectorBody(state: AppState, session: Seq3WorkspaceSession, message: Seq3Message) {
    Column(Modifier.fillMaxSize().padding(horizontal = 12.dp, vertical = 4.dp)) {
        Seq3InspectorPatternField(state, session, message)
        Spacer(Modifier.height(10.dp))
        Seq3InspectorLabelField(state, session, message)
        Spacer(Modifier.height(12.dp))
        Seq3InspectorKindControl(state, session, message)
        Spacer(Modifier.height(12.dp))
        Seq3InspectorRepeatsControl(state, session, message)
        Spacer(Modifier.height(12.dp))
        Seq3EvidenceList(state, session, message)
    }
}

@Composable
private fun Seq3FieldLabel(text: String) {
    val tc = tc()
    AppText(text.uppercase(), color = tc.td, fontSize = 9.sp, fontWeight = FontWeight.Medium)
}

@Composable
private fun Seq3InspectorPatternField(state: AppState, session: Seq3WorkspaceSession, message: Seq3Message) {
    var text by remember(message.id) { mutableStateOf(message.match.template) }
    Column {
        Seq3FieldLabel("Pattern")
        InlineField(
            value = text, onValue = { text = it }, fontSize = 11.sp,
            modifier = Modifier.fillMaxWidth(),
            onSubmit = {
                if (text.isNotBlank()) {
                    val newMatch = Seq3Match(tag = message.match.tag, template = text, captures = seq3ParseTemplateCaptures(text))
                    state.seq3Sessions.applyCommand(
                        session.id,
                        Seq3Command.Bulk(setOf(message.id), Seq3BulkAction.SetPattern(newMatch, text)),
                    )
                }
            },
        )
    }
}

@Composable
private fun Seq3InspectorLabelField(state: AppState, session: Seq3WorkspaceSession, message: Seq3Message) {
    var text by remember(message.id) { mutableStateOf(message.labelTemplate) }
    Column {
        Seq3FieldLabel("Label")
        InlineField(
            value = text, onValue = { text = it }, fontSize = 11.sp,
            modifier = Modifier.fillMaxWidth(),
            onSubmit = {
                if (text.isNotBlank()) {
                    state.seq3Sessions.applyCommand(session.id, Seq3Command.Bulk(setOf(message.id), Seq3BulkAction.SetLabel(text)))
                }
            },
        )
    }
}

private val KIND_LABELS = listOf("call", "return", "async", "self", "note")

@Composable
private fun Seq3InspectorKindControl(state: AppState, session: Seq3WorkspaceSession, message: Seq3Message) {
    Column {
        Seq3FieldLabel("Kind")
        SegmentedControl(
            options = KIND_LABELS,
            selectedIndices = setOf(Seq3Kind.entries.indexOf(message.kind)),
            fillWidth = true,
            onToggle = { index ->
                state.seq3Sessions.applyCommand(session.id, Seq3Command.Bulk(setOf(message.id), Seq3BulkAction.SetKind(Seq3Kind.entries[index])))
            },
        )
    }
}

private val REPEAT_LABELS = listOf("Collapse >N", "Every", "First+last")
private val REPEAT_MODES = listOf(Seq3Repeat.COLLAPSE_ABOVE, Seq3Repeat.EVERY, Seq3Repeat.FIRST_LAST)

@Composable
private fun Seq3InspectorRepeatsControl(state: AppState, session: Seq3WorkspaceSession, message: Seq3Message) {
    var thresholdText by remember(message.id) { mutableStateOf(message.repeatThreshold.toString()) }
    Column {
        Seq3FieldLabel("Repeats")
        SegmentedControl(
            options = REPEAT_LABELS,
            selectedIndices = setOf(REPEAT_MODES.indexOf(message.repeat)),
            fillWidth = true,
            onToggle = { index ->
                val threshold = thresholdText.toIntOrNull()?.takeIf { it > 0 } ?: DEFAULT_SEQ3_REPEAT_THRESHOLD
                state.seq3Sessions.applyCommand(
                    session.id,
                    Seq3Command.Bulk(setOf(message.id), Seq3BulkAction.SetRepeat(REPEAT_MODES[index], threshold)),
                )
            },
        )
        if (message.repeat == Seq3Repeat.COLLAPSE_ABOVE) {
            Row(Modifier.padding(top = 4.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                AppText("Collapse above", color = tc().ts, fontSize = 10.sp)
                InlineField(
                    value = thresholdText,
                    onValue = { value -> if (value.all(Char::isDigit)) thresholdText = value },
                    fontSize = 10.sp,
                    modifier = Modifier.width(48.dp),
                    onSubmit = {
                        val threshold = thresholdText.toIntOrNull()?.takeIf { it > 0 } ?: return@InlineField
                        state.seq3Sessions.applyCommand(
                            session.id,
                            Seq3Command.Bulk(setOf(message.id), Seq3BulkAction.SetRepeat(Seq3Repeat.COLLAPSE_ABOVE, threshold)),
                        )
                    },
                )
                AppText("occurrences", color = tc().ts, fontSize = 10.sp)
            }
        }
    }
}

// ── Evidence — read only (spec §03: "the trust anchor") ────────────────────────────────────────
//
// This list only ever READS `message.occurrences`; nothing in this file writes to it. Clicking a
// line calls `AppState.navigateToLogLine`, the same jump-to-source contract
// `diagram.DiagramMessage.entryId` already gave v1/v2's inspector — evidence stays clickable
// through v3 without a format change (see the v3 rewrite plan's own "must survive" list).

private const val EVIDENCE_COLLAPSED_PREVIEW = 3
private const val EVIDENCE_ROW_HEIGHT_DP = 40

@Composable
private fun Seq3EvidenceList(state: AppState, session: Seq3WorkspaceSession, message: Seq3Message) {
    val tc = tc()
    var expanded by remember(message.id) { mutableStateOf(false) }
    val occurrences = message.occurrences
    Column(Modifier.fillMaxWidth()) {
        Row(
            Modifier.fillMaxWidth().clickable { expanded = !expanded },
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Seq3FieldLabel("Evidence · ${occurrences.size} occurrence${if (occurrences.size == 1) "" else "s"}")
            AppText(if (expanded) "▾" else "▸", color = tc.td, fontSize = 10.sp)
        }
        val shown = if (expanded) occurrences else occurrences.take(EVIDENCE_COLLAPSED_PREVIEW)
        if (expanded) {
            LazyColumn(Modifier.fillMaxWidth().height((EVIDENCE_ROW_HEIGHT_DP * minOf(occurrences.size, 8)).dp)) {
                items(shown, key = Seq3Occurrence::entryId) { occurrence -> Seq3EvidenceRow(state, session, occurrence) }
            }
        } else {
            Column(Modifier.fillMaxWidth()) {
                shown.forEach { occurrence -> Seq3EvidenceRow(state, session, occurrence) }
                if (occurrences.size > EVIDENCE_COLLAPSED_PREVIEW) {
                    AppText(
                        "+${occurrences.size - EVIDENCE_COLLAPSED_PREVIEW} more — expand to see all",
                        color = tc.td, fontSize = 9.sp, modifier = Modifier.clickable { expanded = true }.padding(top = 2.dp),
                    )
                }
            }
        }
    }
}

@Composable
private fun Seq3EvidenceRow(state: AppState, session: Seq3WorkspaceSession, occurrence: Seq3Occurrence) {
    val tc = tc()
    val tabId = session.sourceTabId
    HoverBox(
        modifier = Modifier.fillMaxWidth().padding(vertical = 2.dp),
        onClick = if (tabId != null) { { state.navigateToLogLine(tabId, occurrence.entryId) } } else {
            null
        },
    ) {
        Column(Modifier.padding(vertical = 3.dp)) {
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                AppText("#${occurrence.entryId}", color = tc.td, fontSize = 9.sp, fontFamily = MONO)
                AppText(occurrence.rawTimestamp, color = tc.ts, fontSize = 9.sp, fontFamily = MONO)
            }
            AppText(occurrence.text, color = tc.tx, fontSize = 10.sp, fontFamily = MONO, maxLines = 1)
        }
    }
}
