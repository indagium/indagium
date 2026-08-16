package com.indagium.ui

import androidx.compose.foundation.VerticalScrollbar
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.rememberScrollbarAdapter
import androidx.compose.foundation.verticalScroll
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
import com.indagium.diagram3.Seq3InsertionPosition
import com.indagium.diagram3.Seq3Match
import com.indagium.diagram3.Seq3Message
import com.indagium.diagram3.Seq3Occurrence
import com.indagium.diagram3.Seq3Repeat
import com.indagium.diagram3.parseSeq3Timestamp

// ── The inspector — design spec §03 ─────────────────────────────────────────────────────────────
//
// One message at a time (never a bulk surface — that is the dark selection action bar's job,
// Seq3QueuePanel.kt's `Seq3SelectionActionBar`). The pattern field is "the power-user escape
// hatch" (spec's own words for the pattern field specifically, but the same posture applies to
// every field here): everything is a real, named `Seq3Command`, never a direct document mutation.
// Evidence is READ ONLY — see [Seq3EvidenceList]'s own doc. The surrounding collapsible section
// header is owned by [Seq3QueuePanel], so this composable is only the Inspector body.

@Composable
internal fun Seq3Inspector(state: AppState, session: Seq3WorkspaceSession, view: Seq3ViewState, modifier: Modifier) {
    val tc = tc()
    val messageId = view.inspectorMessageId
    val message = messageId?.let { id -> session.document.messages.firstOrNull { it.id == id } }
    Column(modifier.background(tc.p).fillMaxSize()) {
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
    val scrollState = rememberScrollState()
    Box(Modifier.fillMaxSize()) {
        Column(
            Modifier.fillMaxSize()
                .verticalScroll(scrollState)
                .padding(start = 12.dp, end = 18.dp, top = 4.dp, bottom = 4.dp),
        ) {
            Seq3InspectorPatternField(state, session, message)
            Spacer(Modifier.height(10.dp))
            Seq3InspectorLabelField(state, session, message)
            Spacer(Modifier.height(12.dp))
            Seq3InspectorPlacementControls(state, session, message)
            Spacer(Modifier.height(12.dp))
            Seq3InspectorRepeatsControl(state, session, message)
            Spacer(Modifier.height(12.dp))
            Seq3EvidenceList(state, session, message)
        }
        VerticalScrollbar(
            adapter = rememberScrollbarAdapter(scrollState),
            modifier = Modifier.align(Alignment.CenterEnd).fillMaxHeight().width(6.dp),
            style = appScrollbarStyle(tc()),
        )
    }
}

@Composable
private fun Seq3InspectorPlacementControls(state: AppState, session: Seq3WorkspaceSession, message: Seq3Message) {
    var timestamp by remember(message.id, message.primaryRawTimestamp) { mutableStateOf(message.primaryRawTimestamp) }
    val messages = session.document.messages
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Seq3FieldLabel(if (message.isCustom) "Timestamp / position" else "Timestamp")
        InlineField(
            value = timestamp,
            onValue = { timestamp = it },
            placeholder = "Optional timestamp…",
            fontSize = 11.sp,
            modifier = Modifier.fillMaxWidth(),
            onSubmit = {
                state.seq3Sessions.applyCommand(
                    session.id,
                    Seq3Command.SetMessageTimestamp(
                        messageId = message.id,
                        timestampMillis = parseSeq3Timestamp(timestamp),
                        rawTimestamp = timestamp,
                    ),
                )
            },
        )
        if (message.isCustom) {
            Seq3DropdownButton(label = "Move message…", modifier = Modifier.fillMaxWidth(), menuWidth = 330.dp) { close ->
                Seq3DropdownMenuItem("At start") {
                    state.seq3Sessions.applyCommand(session.id, Seq3Command.MoveMessage(message.id, Seq3InsertionPosition.Start))
                    close()
                }
                Seq3DropdownMenuItem("At end") {
                    state.seq3Sessions.applyCommand(session.id, Seq3Command.MoveMessage(message.id, Seq3InsertionPosition.End))
                    close()
                }
                messages.filter { it.id != message.id }.forEachIndexed { index, candidate ->
                    Seq3DropdownMenuItem("Before ${index + 1}: ${candidate.labelTemplate}") {
                        state.seq3Sessions.applyCommand(
                            session.id,
                            Seq3Command.MoveMessage(message.id, Seq3InsertionPosition.BeforeMessage(candidate.id)),
                        )
                        close()
                    }
                    Seq3DropdownMenuItem("After ${index + 1}: ${candidate.labelTemplate}") {
                        state.seq3Sessions.applyCommand(
                            session.id,
                            Seq3Command.MoveMessage(message.id, Seq3InsertionPosition.AfterMessage(candidate.id)),
                        )
                        close()
                    }
                }
            }
        }
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
        SectionHeader(
            title = "Evidence · ${occurrences.size} occurrence${if (occurrences.size == 1) "" else "s"}",
            expanded = expanded,
            onToggle = { expanded = !expanded },
        )
        val shown = if (expanded) occurrences else occurrences.take(EVIDENCE_COLLAPSED_PREVIEW)
        if (expanded) {
            ScrollableItems(
                itemCount = occurrences.size,
                rowDp = EVIDENCE_ROW_HEIGHT_DP,
                maxDp = EVIDENCE_ROW_HEIGHT_DP * 8,
                modifier = Modifier.fillMaxWidth(),
            ) {
                shown.forEach { occurrence -> Seq3EvidenceRow(state, session, occurrence) }
            }
        } else {
            Column(Modifier.fillMaxWidth()) {
                shown.forEach { occurrence -> Seq3EvidenceRow(state, session, occurrence) }
                if (occurrences.size > EVIDENCE_COLLAPSED_PREVIEW) {
                    AppButton(
                        label = "+${occurrences.size - EVIDENCE_COLLAPSED_PREVIEW} more — expand to see all",
                        onClick = { expanded = true },
                        variant = ButtonVariant.Ghost,
                        textColor = tc.td,
                        horizontalPadding = 0.dp,
                        modifier = Modifier.padding(top = 2.dp),
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
