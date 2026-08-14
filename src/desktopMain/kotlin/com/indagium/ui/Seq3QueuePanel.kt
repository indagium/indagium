@file:OptIn(androidx.compose.ui.ExperimentalComposeUiApi::class)

package com.indagium.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.*
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.indagium.diagram3.Seq3BulkAction
import com.indagium.diagram3.Seq3Command
import com.indagium.diagram3.Seq3Document
import com.indagium.diagram3.Seq3Filter
import com.indagium.diagram3.Seq3Fragment
import com.indagium.diagram3.Seq3FragmentKind
import com.indagium.diagram3.Seq3Kind
import com.indagium.diagram3.Seq3Message
import com.indagium.diagram3.Seq3Note
import com.indagium.diagram3.Seq3PinDirection
import com.indagium.diagram3.Seq3Repeat
import com.indagium.diagram3.Seq3Sort
import com.indagium.diagram3.Seq3State
import com.indagium.diagram3.Seq3Visibility
import com.indagium.diagram3.nudgeSeq3OrderPin
import com.indagium.diagram3.seq3FilterCounts
import com.indagium.diagram3.seq3QueueRows
import com.indagium.diagram3.seq3Select
import java.util.UUID

// ── The panel is a queue — design spec §04 + §06 + §07 ─────────────────────────────────────────
//
// A thin composable shell around `diagram3.Seq3Queue` (filter/sort/selection math) and
// `diagram3.Seq3Commands` (every mutation) — exactly the split `diagram.ManualDiagramMessageQueue`
// had for the v1/v2 panel. EVERY editing verb below routes through
// `state.seq3Sessions.applyCommand(session.id, Seq3Command…)`; nothing here ever calls
// `session.document.copy(...)` directly, so ⌘Z stays uniform (see this phase's brief).

@Composable
internal fun Seq3QueuePanel(state: AppState, session: Seq3WorkspaceSession, view: Seq3ViewState, modifier: Modifier) {
    val tc = tc()
    val document = session.document
    val counts = seq3FilterCounts(document)
    val rows = remember(document, view.filter, view.textFilter, view.sort) {
        seq3QueueRows(document, view.filter, view.textFilter, view.sort)
    }
    val visibleIds = remember(rows) { rows.map(Seq3Message::id) }
    val listState = rememberLazyListState()

    // Two-way row<->arrow (spec §04): a canvas arrow click already reset the filter/text before
    // setting scrollRequestId (see Seq3Canvas), so by the time `rows` reflects that reset, the
    // target id is guaranteed visible and this only has to find and scroll to it.
    LaunchedEffect(view.scrollRequestId, rows) {
        val targetId = view.scrollRequestId ?: return@LaunchedEffect
        val index = rows.indexOfFirst { it.id == targetId }
        if (index >= 0) {
            listState.animateScrollToItem(index)
            view.scrollRequestId = null
        }
    }

    Column(modifier.background(tc.p)) {
        Seq3QueueHeader(counts)
        if (counts.needsTarget > 0) {
            Seq3NeedsTargetBanner(counts.needsTarget) {
                // Spec §05: the banner is what starts the guided pass. `startSeq3GuidedPass`
                // returns null only when nothing is unresolved — unreachable here, since this
                // banner is itself gated on needsTarget > 0.
                view.guidedPass = startSeq3GuidedPass(document)
            }
        }
        Seq3FilterChipsRow(view, counts)
        Seq3FilterTextAndSortRow(view)
        Box(Modifier.weight(1f)) {
            LazyColumn(Modifier.fillMaxWidth(), state = listState) {
                items(rows, key = Seq3Message::id) { message ->
                    Seq3QueueRow(state, session, view, document, message, visibleIds)
                }
            }
        }
        if (view.selection.selectedIds.isNotEmpty()) {
            Seq3SelectionActionBar(state, session, view, document)
        } else {
            Seq3QueueFooter(counts) { view.regenerateSheetOpen = true }
        }
    }
}

@Composable
private fun Seq3QueueHeader(counts: com.indagium.diagram3.Seq3FilterCounts) {
    val tc = tc()
    Row(
        Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            AppText("Messages", color = tc.tx, fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
            Spacer(Modifier.width(6.dp))
            AppText("${counts.all}", color = tc.ts, fontSize = 12.sp)
        }
        // No `Seq3Command`/`Seq3BulkAction` exists to create a brand-new, evidence-less message —
        // every message in this engine is generated from a log range (Seq3Generator.kt's own
        // header: "no fabricated/synthetic lifeline"), and this phase's brief only names "Fix
        // these" and "Regenerate…" as the TODO hooks phase 5 fills. "Add ＋" is wired the same way
        // rather than inventing engine surface outside this phase's scope — see this phase's
        // report for the full note.
        AppText(
            "Add ＋", color = tc.ts, fontSize = 11.sp,
            modifier = Modifier.clip(RoundedCornerShape(4.dp)).clickable { /* TODO(phase 5+): no engine hook yet */ }
                .padding(horizontal = 6.dp, vertical = 2.dp),
        )
    }
}

@Composable
private fun Seq3NeedsTargetBanner(count: Int, onFixThese: () -> Unit) {
    val tc = tc()
    Row(
        Modifier.fillMaxWidth().background(tc.warnBg)
            .padding(horizontal = 12.dp, vertical = 6.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        AppText("$count message${if (count == 1) "" else "s"} need a target", color = tc.warn, fontSize = 11.sp)
        AppText(
            "Fix these →", color = tc.warn, fontSize = 11.sp, fontWeight = FontWeight.SemiBold,
            modifier = Modifier.clickable(onClick = onFixThese),
        )
    }
}

@Composable
private fun Seq3FilterChipsRow(view: Seq3ViewState, counts: com.indagium.diagram3.Seq3FilterCounts) {
    Row(
        Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        PillBtn("All ${counts.all}", active = view.filter == Seq3Filter.ALL) { view.filter = Seq3Filter.ALL }
        PillBtn("Needs target ${counts.needsTarget}", active = view.filter == Seq3Filter.NEEDS_TARGET) { view.filter = Seq3Filter.NEEDS_TARGET }
        PillBtn("Edited ${counts.edited}", active = view.filter == Seq3Filter.EDITED) { view.filter = Seq3Filter.EDITED }
        PillBtn("Hidden ${counts.hidden}", active = view.filter == Seq3Filter.HIDDEN) { view.filter = Seq3Filter.HIDDEN }
    }
}

private val SORT_LABELS = mapOf(
    Seq3Sort.LOG_ORDER to "Log order",
    Seq3Sort.LIFELINE to "By lifeline",
    Seq3Sort.OCCURRENCES to "By occurrence count",
    Seq3Sort.STATE to "By state",
)

@Composable
private fun Seq3FilterTextAndSortRow(view: Seq3ViewState) {
    Row(
        Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        InlineField(
            value = view.textFilter,
            onValue = { view.textFilter = it },
            placeholder = "Filter messages…",
            modifier = Modifier.weight(1f),
            fontSize = 11.sp,
            onClear = if (view.textFilter.isNotEmpty()) { { view.textFilter = "" } } else {
                null
            },
        )
        // Sort is a VIEW, never an edit (spec §07) — Seq3QueuePanel's own header — so this only
        // ever writes `view.sort`, never dispatches a Seq3Command.
        Seq3DropdownButton(label = SORT_LABELS.getValue(view.sort), menuWidth = 176.dp) { close ->
            Seq3Sort.entries.forEach { option ->
                Seq3DropdownMenuItem(SORT_LABELS.getValue(option), active = option == view.sort) {
                    view.sort = option
                    close()
                }
            }
        }
    }
}

@Composable
private fun Seq3QueueFooter(counts: com.indagium.diagram3.Seq3FilterCounts, onRegenerate: () -> Unit) {
    val tc = tc()
    Row(
        Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        AppText("${counts.all} messages · ${counts.needsTarget} need a target", color = tc.ts, fontSize = 11.sp)
        AppText(
            "Regenerate…", color = tc.ac, fontSize = 11.sp, fontWeight = FontWeight.SemiBold,
            // Opens spec §08's review sheet — never a silent full rebuild, which is exactly what
            // "Regenerate is a reviewed proposal, never a wholesale replace" rules out.
            modifier = Modifier.clickable(onClick = onRegenerate),
        )
    }
}

// ── One queue row (spec §04) ────────────────────────────────────────────────────────────────

@Composable
private fun Seq3QueueRow(
    state: AppState,
    session: Seq3WorkspaceSession,
    view: Seq3ViewState,
    document: Seq3Document,
    message: Seq3Message,
    visibleIds: List<String>,
) {
    val tc = tc()
    val selected = message.id in view.selection.selectedIds
    val needsTarget = message.state == Seq3State.NEEDS_TARGET
    val hidden = message.visibility == Seq3Visibility.HIDDEN
    val hovered = view.hoveredMessageId == message.id
    val collapsedCount = seq3CollapsedOccurrenceCount(message)
    // Pin controls only make sense against LOG-ORDER adjacency (Seq3Queue's nudge is defined over
    // Seq3Document.messages' own order) — showing them under a different view sort would point at
    // a neighbour that isn't actually adjacent on screen. See this phase's report for the note.
    val pinnable = if (view.sort == Seq3Sort.LOG_ORDER) seq3PinnableDirections(document, message.id) else emptySet()

    Column(
        Modifier.fillMaxWidth()
            .background(
                when {
                    selected -> tc.sl
                    needsTarget -> tc.warnBg
                    hovered -> tc.hv
                    else -> Color.Transparent
                },
            )
            .drawBehind {
                if (selected) drawRect(color = tc.ac, size = Size(3.dp.toPx(), size.height))
            }
            .onPointerEvent(PointerEventType.Enter) { view.hoveredMessageId = message.id }
            .onPointerEvent(PointerEventType.Exit) { if (view.hoveredMessageId == message.id) view.hoveredMessageId = null }
            .pointerInput(message.id, visibleIds) {
                awaitPointerEventScope {
                    while (true) {
                        val event = awaitPointerEvent()
                        if (event.type == PointerEventType.Press && event.buttons.isPrimaryPressed) {
                            val mods = event.keyboardModifiers
                            view.selection = seq3Select(
                                visibleIds, view.selection, message.id,
                                additive = mods.isMetaPressed || mods.isCtrlPressed,
                                range = mods.isShiftPressed,
                            )
                            view.inspectorMessageId = message.id
                        }
                    }
                }
            }
            .padding(start = 4.dp, end = 10.dp, top = 6.dp, bottom = 6.dp),
    ) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            Box(Modifier.width(16.dp).padding(top = 2.dp)) {
                Seq3RowCheckbox(checked = selected) {
                    view.selection = seq3Select(visibleIds, view.selection, message.id, additive = true)
                }
            }
            Column(Modifier.weight(1f)) {
                Seq3RowPatternLine(message, collapsedCount)
                Spacer(Modifier.width(2.dp))
                Seq3RowEndpointsLine(state, session, message, pinnable, hidden)
                if (hidden) {
                    Row(Modifier.fillMaxWidth().padding(top = 2.dp), horizontalArrangement = Arrangement.SpaceBetween) {
                        AppText("Hidden from canvas · evidence kept", color = tc.td, fontSize = 10.sp)
                        AppText(
                            "Show", color = tc.ac, fontSize = 10.sp,
                            modifier = Modifier.clickable {
                                state.seq3Sessions.applyCommand(session.id, Seq3Command.Bulk(setOf(message.id), Seq3BulkAction.Show))
                            },
                        )
                    }
                } else if (collapsedCount != null) {
                    Row(Modifier.fillMaxWidth().padding(top = 2.dp), horizontalArrangement = Arrangement.SpaceBetween) {
                        AppText("Collapsed to one arrow · ×$collapsedCount", color = tc.td, fontSize = 10.sp)
                        AppText(
                            "Show occurrences", color = tc.ac, fontSize = 10.sp,
                            modifier = Modifier.clickable {
                                state.seq3Sessions.applyCommand(
                                    session.id,
                                    Seq3Command.Bulk(setOf(message.id), Seq3BulkAction.SetRepeat(Seq3Repeat.EVERY, message.repeatThreshold)),
                                )
                            },
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun Seq3RowCheckbox(checked: Boolean, onToggle: () -> Unit) {
    val tc = tc()
    Box(
        Modifier.size(16.dp)
            .background(if (checked) tc.ac else Color.Transparent, RoundedCornerShape(3.dp))
            .border(1.dp, if (checked) tc.ac else tc.br, RoundedCornerShape(3.dp))
            .clickable(onClick = onToggle),
        contentAlignment = Alignment.Center,
    ) {
        if (checked) AppText("✓", color = tc.bg, fontSize = 9.sp)
    }
}

@Composable
private fun Seq3RowPatternLine(message: Seq3Message, collapsedCount: Int?) {
    val tc = tc()
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
        Row(Modifier.weight(1f)) {
            seq3TemplateSegments(message.match.template).forEach { segment ->
                when (segment) {
                    is Seq3TemplateSegment.Literal -> if (segment.text.isNotEmpty()) {
                        AppText(segment.text, color = tc.tx, fontSize = 11.sp, fontFamily = MONO, maxLines = 1)
                    }
                    is Seq3TemplateSegment.Token -> AppText(
                        "{${segment.name}}", color = tc.ac, fontSize = 11.sp, fontFamily = MONO,
                        fontWeight = FontWeight.SemiBold, maxLines = 1,
                    )
                }
            }
        }
        val count = collapsedCount ?: message.occurrences.size
        if (count > 1) AppText("×$count", color = tc.ts, fontSize = 10.sp)
    }
}

@Composable
private fun Seq3RowEndpointsLine(
    state: AppState,
    session: Seq3WorkspaceSession,
    message: Seq3Message,
    pinnable: Set<Seq3PinDirection>,
    hidden: Boolean,
) {
    val tc = tc()
    val document = session.document
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(4.dp), verticalAlignment = Alignment.CenterVertically) {
        Seq3EndpointChip(document, message.fromLifelineId) { lifelineId ->
            state.seq3Sessions.applyCommand(session.id, Seq3Command.Bulk(setOf(message.id), Seq3BulkAction.SetFrom(lifelineId)))
        }
        AppText("→", color = tc.td, fontSize = 10.sp)
        if (message.toLifelineId != null) {
            Seq3EndpointChip(document, message.toLifelineId) { lifelineId ->
                state.seq3Sessions.applyCommand(session.id, Seq3Command.Bulk(setOf(message.id), Seq3BulkAction.SetTo(lifelineId)))
            }
        } else if (message.kind != Seq3Kind.NOTE) {
            Seq3DropdownButton(label = "set target", labelColor = tc.warn, fillColor = tc.warnBg, menuWidth = 150.dp) { close ->
                document.lifelines.sortedBy { it.ordinal }.forEach { lifeline ->
                    Seq3DropdownMenuItem(lifeline.name) {
                        state.seq3Sessions.applyCommand(session.id, Seq3Command.Bulk(setOf(message.id), Seq3BulkAction.SetTo(lifeline.id)))
                        close()
                    }
                }
            }
        }
        if (pinnable.isNotEmpty()) {
            Seq3PinControls(state, session, message, pinnable)
        }
        Spacer(Modifier.weight(1f))
        Seq3StateWord(message, hidden)
    }
}

@Composable
private fun Seq3EndpointChip(document: Seq3Document, lifelineId: String, onReassign: (String) -> Unit) {
    val tc = tc()
    val name = document.lifelines.firstOrNull { it.id == lifelineId }?.name ?: lifelineId
    Seq3DropdownButton(label = name, labelColor = tc.ts, fillColor = tc.p2, menuWidth = 150.dp) { close ->
        document.lifelines.sortedBy { it.ordinal }.forEach { lifeline ->
            Seq3DropdownMenuItem(lifeline.name, active = lifeline.id == lifelineId) {
                onReassign(lifeline.id)
                close()
            }
        }
    }
}

@Composable
private fun Seq3PinControls(state: AppState, session: Seq3WorkspaceSession, message: Seq3Message, pinnable: Set<Seq3PinDirection>) {
    val tc = tc()
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(2.dp)) {
        if (Seq3PinDirection.UP in pinnable) {
            AppText(
                "▲", color = tc.ts, fontSize = 9.sp,
                modifier = Modifier.clickable {
                    state.seq3Sessions.applyCommand(session.id, Seq3Command.NudgePin(message.id, Seq3PinDirection.UP))
                },
            )
        }
        if (Seq3PinDirection.DOWN in pinnable) {
            AppText(
                "▼", color = tc.ts, fontSize = 9.sp,
                modifier = Modifier.clickable {
                    state.seq3Sessions.applyCommand(session.id, Seq3Command.NudgePin(message.id, Seq3PinDirection.DOWN))
                },
            )
        }
        if (message.orderPin != null) {
            AppText(
                "pinned", color = tc.ac, fontSize = 9.sp,
                modifier = Modifier.clickable { state.seq3Sessions.applyCommand(session.id, Seq3Command.ClearPin(message.id)) },
            )
        } else {
            AppText("same ms", color = tc.td, fontSize = 9.sp)
        }
    }
}

@Composable
private fun Seq3StateWord(message: Seq3Message, hidden: Boolean) {
    val tc = tc()
    val (label, color) = when {
        hidden -> "hidden" to tc.td
        message.state == Seq3State.NEEDS_TARGET -> "needs target" to tc.warn
        message.state == Seq3State.EDITED -> "edited" to tc.ok
        else -> "auto" to tc.ts
    }
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(3.dp)) {
        if (message.state == Seq3State.EDITED) Box(Modifier.size(5.dp).background(tc.ok, RoundedCornerShape(50)))
        AppText(
            label.uppercase(), color = color, fontSize = 10.sp, fontWeight = FontWeight.Medium,
            textDecoration = if (hidden) TextDecoration.LineThrough else null,
        )
    }
}

// ── Selection needs verbs — spec §06 ────────────────────────────────────────────────────────────
//
// Dark (inverted-contrast) action bar: `tc.tx` as the fill and `tc.bg` as the foreground text is
// this theme's own primary-text/background pair swapped, so it reads as "the one high-contrast
// bar on this screen" on every preset (light OR dark) without a hardcoded near-black hex — the
// mock's literal dark bar, reproduced as a relationship instead of a color (see the v3 rewrite
// plan's palette decision: "hues track the active preset; layout and spacing will not").

@Composable
private fun Seq3SelectionActionBar(state: AppState, session: Seq3WorkspaceSession, view: Seq3ViewState, document: Seq3Document) {
    val tc = tc()
    val selectedIds = view.selection.selectedIds

    fun dispatch(action: Seq3BulkAction) {
        state.seq3Sessions.applyCommand(session.id, Seq3Command.Bulk(selectedIds, action))
    }
    Row(
        Modifier.fillMaxWidth().background(tc.tx)
            .padding(horizontal = 12.dp, vertical = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        AppText("${selectedIds.size} selected", color = tc.bg, fontSize = 11.sp, fontWeight = FontWeight.SemiBold)
        Seq3DropdownButton(label = "Set from", labelColor = tc.bg, fillColor = tc.tx.copy(alpha = 0f)) { close ->
            document.lifelines.sortedBy { it.ordinal }.forEach { lifeline ->
                Seq3DropdownMenuItem(lifeline.name) { dispatch(Seq3BulkAction.SetFrom(lifeline.id)); close() }
            }
        }
        Seq3DropdownButton(label = "Set to", labelColor = tc.bg, fillColor = tc.tx.copy(alpha = 0f)) { close ->
            document.lifelines.sortedBy { it.ordinal }.forEach { lifeline ->
                Seq3DropdownMenuItem(lifeline.name) { dispatch(Seq3BulkAction.SetTo(lifeline.id)); close() }
            }
        }
        Seq3ActionBarLabel("Merge", tc.bg) {
            val mergedId = document.messages.firstOrNull { it.id in selectedIds }?.id ?: return@Seq3ActionBarLabel
            dispatch(Seq3BulkAction.Merge(mergedId))
            view.selection = com.indagium.diagram3.Seq3Selection()
        }
        Seq3DropdownButton(label = "Group", labelColor = tc.bg, fillColor = tc.tx.copy(alpha = 0f)) { close ->
            Seq3FragmentKind.entries.forEach { kind ->
                Seq3DropdownMenuItem(kind.name.lowercase()) {
                    val fragment = Seq3Fragment("frag-${UUID.randomUUID()}", kind, kind.name.lowercase(), selectedIds.toList())
                    dispatch(Seq3BulkAction.Group(fragment))
                    close()
                }
            }
        }
        Seq3ActionBarLabel("Hide", tc.bg) {
            dispatch(Seq3BulkAction.Hide)
            view.selection = com.indagium.diagram3.Seq3Selection()
        }
        Seq3ActionBarLabel("Note", tc.bg) {
            val note = Seq3Note("note-${UUID.randomUUID()}", "Note", selectedIds.toList())
            dispatch(Seq3BulkAction.Note(note))
        }
        Spacer(Modifier.weight(1f))
        Seq3ActionBarLabel("Esc", tc.bg.copy(alpha = .7f)) { view.selection = com.indagium.diagram3.Seq3Selection() }
    }
}

@Composable
private fun Seq3ActionBarLabel(label: String, color: Color, onClick: () -> Unit) {
    AppText(label, color = color, fontSize = 11.sp, fontWeight = FontWeight.Medium, modifier = Modifier.clickable(onClick = onClick))
}

// ── Pure helpers — testable without a composition (Seq3QueuePanelTest) ─────────────────────────

/** True only when [messageId]'s first occurrence genuinely ties with an immediate LOG-ORDER
 *  neighbour — surfaces [nudgeSeq3OrderPin]'s own validity check (a dry run, its returned document
 *  is discarded) rather than reimplementing the tie rule, per this phase's brief. */
internal fun seq3PinnableDirections(document: Seq3Document, messageId: String): Set<Seq3PinDirection> =
    Seq3PinDirection.entries.filterTo(linkedSetOf()) { direction -> nudgeSeq3OrderPin(document, messageId, direction).applied }

/** Non-null exactly when [message] is currently drawn as one collapsed/badged arrow (spec §04's
 *  third inset row line) — the same condition Seq3Layout's own `expandForLayout` uses for
 *  [Seq3Repeat.COLLAPSE_ABOVE]. */
internal fun seq3CollapsedOccurrenceCount(message: Seq3Message): Int? =
    if (message.repeat == Seq3Repeat.COLLAPSE_ABOVE && message.occurrences.size > message.repeatThreshold) message.occurrences.size else null

internal sealed class Seq3TemplateSegment {
    data class Literal(val text: String) : Seq3TemplateSegment()

    data class Token(val name: String) : Seq3TemplateSegment()
}

private val TEMPLATE_TOKEN = Regex("\\{([A-Za-z_][A-Za-z0-9_]*)}")

/** Splits a match/label template into literal runs and `{token}` slots, for the pattern line's
 *  accent-highlighted rendering (spec §04). Pure and independent of [Seq3Message] so
 *  Seq3QueuePanelTest can exercise it directly. */
internal fun seq3TemplateSegments(template: String): List<Seq3TemplateSegment> {
    val segments = mutableListOf<Seq3TemplateSegment>()
    var cursor = 0
    TEMPLATE_TOKEN.findAll(template).forEach { match ->
        if (match.range.first > cursor) segments += Seq3TemplateSegment.Literal(template.substring(cursor, match.range.first))
        segments += Seq3TemplateSegment.Token(match.groupValues[1])
        cursor = match.range.last + 1
    }
    if (cursor < template.length) segments += Seq3TemplateSegment.Literal(template.substring(cursor))
    return segments
}

/** Parses `{name}` tokens out of a freely-typed template (Inspector's pattern field, spec §03) into
 *  [com.indagium.diagram3.Seq3Capture]s with [com.indagium.diagram3.Seq3CaptureSource.AUTHOR] — the
 *  source reserved for "a capture a user names by hand" (Seq3Model.kt's own doc on that enum
 *  value). Duplicate token names keep only their first occurrence. */
internal fun seq3ParseTemplateCaptures(template: String): List<com.indagium.diagram3.Seq3Capture> =
    TEMPLATE_TOKEN.findAll(template).map { it.groupValues[1] }.distinct()
        .map { com.indagium.diagram3.Seq3Capture(it, com.indagium.diagram3.Seq3CaptureSource.AUTHOR) }
        .toList()
