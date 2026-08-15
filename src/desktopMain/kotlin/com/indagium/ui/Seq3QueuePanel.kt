@file:OptIn(androidx.compose.ui.ExperimentalComposeUiApi::class)

package com.indagium.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
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
import com.indagium.diagram3.Seq3AddResult
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
import com.indagium.diagram3.addSeq3MessageFromSelection
import com.indagium.diagram3.nudgeSeq3OrderPin
import com.indagium.diagram3.seq3FilterCounts
import com.indagium.diagram3.seq3QueueRows
import com.indagium.diagram3.seq3Select
import com.indagium.model.LogEntry
import kotlinx.coroutines.delay
import java.util.UUID
import java.awt.Cursor as AwtCursor

private const val ADD_HINT_DURATION_MS = 2_500L

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
        Seq3QueueHeader(state, session, counts)
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
        if (document.fragments.isNotEmpty() || document.notes.isNotEmpty()) {
            Seq3FragmentsAndNotesSection(state, session, document)
        }
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

/** Resolves a log tab's currently-selected row ids into the [LogEntry]s "Add ＋" (item 2) hands to
 *  [addSeq3MessageFromSelection] — pure and `internal` purely for testability, mirroring
 *  [seq3PinnableDirections]/[seq3TemplateSegments]'s own "no composition needed" split. */
internal fun seq3ResolveSelectedEntries(logData: List<LogEntry>, selectedIds: Set<Int>): List<LogEntry> =
    logData.filter { it.id in selectedIds }

@Composable
private fun Seq3QueueHeader(state: AppState, session: Seq3WorkspaceSession, counts: com.indagium.diagram3.Seq3FilterCounts) {
    val tc = tc()
    // A short-lived rejection hint ("Select rows from a single tag", …) — this file has no other
    // transient-message convention to match (checked, per this phase's brief), so a plain
    // remember+LaunchedEffect auto-clear is the documented fallback.
    var addHint by remember(session.id) { mutableStateOf<String?>(null) }
    LaunchedEffect(addHint) {
        if (addHint != null) {
            delay(ADD_HINT_DURATION_MS)
            addHint = null
        }
    }

    fun onAddClicked() {
        val tab = session.sourceTabId?.let(state::tab)
        val selectedEntries = tab?.let { seq3ResolveSelectedEntries(it.logData, it.selected) }.orEmpty()
        when (val result = addSeq3MessageFromSelection(session.document, selectedEntries)) {
            is Seq3AddResult.Added ->
                state.seq3Sessions.applyCommand(session.id, Seq3Command.ReplaceDocument(result.document))
            is Seq3AddResult.Rejected -> addHint = result.reason
        }
    }
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
        Column(horizontalAlignment = Alignment.End) {
            // Builds one message from the log tab's currently-selected rows via
            // `addSeq3MessageFromSelection` — the resulting whole-document replacement is applied
            // through `Seq3Command.ReplaceDocument`, so this is one undoable step like every other
            // editing verb (this phase's brief: "never call session.document.copy(...) directly").
            HoverBox(
                modifier = Modifier.clip(RoundedCornerShape(4.dp))
                    .pointerHoverIcon(PointerIcon(AwtCursor.getPredefinedCursor(AwtCursor.HAND_CURSOR)), overrideDescendants = true),
                onClick = ::onAddClicked,
            ) {
                AppText("Add ＋", color = tc.ts, fontSize = 11.sp, modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp))
            }
            addHint?.let { hint -> AppText(hint, color = tc.warn, fontSize = 9.sp) }
        }
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
        HoverBox(
            modifier = Modifier.pointerHoverIcon(
                PointerIcon(AwtCursor.getPredefinedCursor(AwtCursor.HAND_CURSOR)),
                overrideDescendants = true,
            ),
            onClick = onFixThese,
        ) {
            AppText("Fix these →", color = tc.warn, fontSize = 11.sp, fontWeight = FontWeight.SemiBold)
        }
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
        // Opens spec §08's review sheet — never a silent full rebuild, which is exactly what
        // "Regenerate is a reviewed proposal, never a wholesale replace" rules out.
        HoverBox(
            modifier = Modifier.pointerHoverIcon(
                PointerIcon(AwtCursor.getPredefinedCursor(AwtCursor.HAND_CURSOR)),
                overrideDescendants = true,
            ),
            onClick = onRegenerate,
        ) {
            AppText("Regenerate…", color = tc.ac, fontSize = 11.sp, fontWeight = FontWeight.SemiBold)
        }
    }
}

// ── Fragments & notes (item 3): visibility + rename for what `Group ▾`/`Note` create ───────────
//
// `Group ▾`/`Note` (in the selection action bar below) are structurally add-only — this is the
// missing edit-in-place counterpart: a compact expandable list surfacing every existing fragment/
// note by id with its current label/text, each double-click-to-edit inline (same convention as
// `Seq3Canvas.kt`'s `Seq3InlineLabelEditor`/`Seq3LifelineChip` label editors). Both rename actions
// are id-keyed, not selection-keyed (`Seq3BulkAction.SetFragmentLabel`/`SetNoteText`), so they route
// through `Seq3Command.Bulk(emptySet(), …)` — `applySeq3BulkAction`'s own empty-selection guard
// already exempts exactly these two actions (Seq3Queue.kt's own comment on that guard).

@Composable
private fun Seq3FragmentsAndNotesSection(state: AppState, session: Seq3WorkspaceSession, document: Seq3Document) {
    val tc = tc()
    var expanded by remember(session.id) { mutableStateOf(false) }
    val total = document.fragments.size + document.notes.size
    Column(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp)) {
        Row(
            Modifier.fillMaxWidth().clickable { expanded = !expanded },
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            AppText("Fragments & notes · $total", color = tc.ts, fontSize = 11.sp, fontWeight = FontWeight.Medium)
            AppText(if (expanded) "▴" else "▾", color = tc.ts, fontSize = 9.sp)
        }
        if (expanded) {
            document.fragments.forEach { fragment -> Seq3FragmentRenameRow(state, session, fragment) }
            document.notes.forEach { note -> Seq3NoteRenameRow(state, session, note) }
        }
    }
}

@Composable
private fun Seq3FragmentRenameRow(state: AppState, session: Seq3WorkspaceSession, fragment: Seq3Fragment) {
    val tc = tc()
    var editing by remember(fragment.id) { mutableStateOf(false) }
    Row(Modifier.fillMaxWidth().padding(vertical = 3.dp), verticalAlignment = Alignment.CenterVertically) {
        AppText(fragment.kind.name.lowercase(), color = tc.td, fontSize = 10.sp)
        Spacer(Modifier.width(6.dp))
        if (editing) {
            var text by remember(fragment.id) { mutableStateOf(fragment.label) }

            fun commit() {
                state.seq3Sessions.applyCommand(
                    session.id,
                    Seq3Command.Bulk(emptySet(), Seq3BulkAction.SetFragmentLabel(fragment.id, text)),
                )
                editing = false
            }
            InlineField(value = text, onValue = { text = it }, fontSize = 10.sp, modifier = Modifier.weight(1f), onSubmit = ::commit)
            SquareIconButton("✓", fontSize = 10.sp, onClick = ::commit, size = 16.dp)
            SquareIconButton("×", fontSize = 10.sp, onClick = { editing = false }, size = 16.dp)
        } else {
            Box(Modifier.weight(1f).pointerInput(fragment.id) { detectTapGestures(onDoubleTap = { editing = true }) }) {
                AppText(fragment.label, color = tc.tx, fontSize = 10.sp, maxLines = 1)
            }
        }
    }
}

@Composable
private fun Seq3NoteRenameRow(state: AppState, session: Seq3WorkspaceSession, note: Seq3Note) {
    val tc = tc()
    var editing by remember(note.id) { mutableStateOf(false) }
    Row(Modifier.fillMaxWidth().padding(vertical = 3.dp), verticalAlignment = Alignment.CenterVertically) {
        AppText("note", color = tc.td, fontSize = 10.sp)
        Spacer(Modifier.width(6.dp))
        if (editing) {
            var text by remember(note.id) { mutableStateOf(note.text) }

            fun commit() {
                state.seq3Sessions.applyCommand(session.id, Seq3Command.Bulk(emptySet(), Seq3BulkAction.SetNoteText(note.id, text)))
                editing = false
            }
            InlineField(value = text, onValue = { text = it }, fontSize = 10.sp, modifier = Modifier.weight(1f), onSubmit = ::commit)
            SquareIconButton("✓", fontSize = 10.sp, onClick = ::commit, size = 16.dp)
            SquareIconButton("×", fontSize = 10.sp, onClick = { editing = false }, size = 16.dp)
        } else {
            Box(Modifier.weight(1f).pointerInput(note.id) { detectTapGestures(onDoubleTap = { editing = true }) }) {
                AppText(note.text, color = tc.tx, fontSize = 10.sp, maxLines = 1)
            }
        }
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
                Seq3RowCheckbox(checked = selected)
            }
            Column(Modifier.weight(1f)) {
                Seq3RowPatternLine(message, collapsedCount)
                Spacer(Modifier.width(2.dp))
                Seq3RowEndpointsLine(state, session, message, pinnable, hidden)
                if (hidden) {
                    Row(Modifier.fillMaxWidth().padding(top = 2.dp), horizontalArrangement = Arrangement.SpaceBetween) {
                        AppText("Hidden from canvas · evidence kept", color = tc.td, fontSize = 10.sp)
                        HoverBox(
                            modifier = Modifier.pointerHoverIcon(
                                PointerIcon(AwtCursor.getPredefinedCursor(AwtCursor.HAND_CURSOR)),
                                overrideDescendants = true,
                            ),
                            onClick = {
                                state.seq3Sessions.applyCommand(session.id, Seq3Command.Bulk(setOf(message.id), Seq3BulkAction.Show))
                            },
                        ) {
                            AppText("Show", color = tc.ac, fontSize = 10.sp)
                        }
                    }
                } else if (collapsedCount != null) {
                    Row(Modifier.fillMaxWidth().padding(top = 2.dp), horizontalArrangement = Arrangement.SpaceBetween) {
                        AppText("Collapsed to one arrow · ×$collapsedCount", color = tc.td, fontSize = 10.sp)
                        HoverBox(
                            modifier = Modifier.pointerHoverIcon(
                                PointerIcon(AwtCursor.getPredefinedCursor(AwtCursor.HAND_CURSOR)),
                                overrideDescendants = true,
                            ),
                            onClick = {
                                state.seq3Sessions.applyCommand(
                                    session.id,
                                    Seq3Command.Bulk(setOf(message.id), Seq3BulkAction.SetRepeat(Seq3Repeat.EVERY, message.repeatThreshold)),
                                )
                            },
                        ) {
                            AppText("Show occurrences", color = tc.ac, fontSize = 10.sp)
                        }
                    }
                }
            }
        }
    }
}

/** Item 11 fix: a PURE visual indicator of [checked], no independent hit target. The checkbox used
 *  to carry its own `Modifier.clickable` (hardcoded `additive = true`, no shift/⌘ awareness) that
 *  fired alongside the row's own modifier-aware `pointerInput` Press handler on every click — Press
 *  selected only this row first, then the checkbox's Release-driven toggle saw the id already
 *  selected and (being an unconditional toggle) removed it, so a checkbox click always emptied the
 *  selection. The row's Press handler is now the ONLY selection entry point for the whole row,
 *  checkbox included, matching the design spec's click/⇧click/⌘click-on-the-row model. */
@Composable
private fun Seq3RowCheckbox(checked: Boolean) {
    val tc = tc()
    Box(
        Modifier.size(16.dp)
            .background(if (checked) tc.ac else Color.Transparent, RoundedCornerShape(3.dp))
            .border(1.dp, if (checked) tc.ac else tc.br, RoundedCornerShape(3.dp)),
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
        // Item 15: "revert to generated" for ONE edited row — only ever shown once this message has
        // actually drifted from what the engine would produce (state derives EDITED from `authoring`,
        // Seq3Message's own doc), so an untouched AUTO/NEEDS_TARGET row never offers it.
        if (message.state == Seq3State.EDITED) {
            HoverBox(
                modifier = Modifier.pointerHoverIcon(
                    PointerIcon(AwtCursor.getPredefinedCursor(AwtCursor.HAND_CURSOR)),
                    overrideDescendants = true,
                ),
                onClick = { state.seq3Sessions.revertMessage(session.id, message.id) },
            ) {
                AppText("Revert", color = tc.ac, fontSize = 10.sp)
            }
            Spacer(Modifier.width(4.dp))
        }
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
            HoverBox(
                modifier = Modifier.pointerHoverIcon(
                    PointerIcon(AwtCursor.getPredefinedCursor(AwtCursor.HAND_CURSOR)),
                    overrideDescendants = true,
                ),
                onClick = {
                    state.seq3Sessions.applyCommand(session.id, Seq3Command.NudgePin(message.id, Seq3PinDirection.UP))
                },
            ) {
                AppText("▲", color = tc.ts, fontSize = 9.sp)
            }
        }
        if (Seq3PinDirection.DOWN in pinnable) {
            HoverBox(
                modifier = Modifier.pointerHoverIcon(
                    PointerIcon(AwtCursor.getPredefinedCursor(AwtCursor.HAND_CURSOR)),
                    overrideDescendants = true,
                ),
                onClick = {
                    state.seq3Sessions.applyCommand(session.id, Seq3Command.NudgePin(message.id, Seq3PinDirection.DOWN))
                },
            ) {
                AppText("▼", color = tc.ts, fontSize = 9.sp)
            }
        }
        if (message.orderPin != null) {
            HoverBox(
                modifier = Modifier.pointerHoverIcon(
                    PointerIcon(AwtCursor.getPredefinedCursor(AwtCursor.HAND_CURSOR)),
                    overrideDescendants = true,
                ),
                onClick = { state.seq3Sessions.applyCommand(session.id, Seq3Command.ClearPin(message.id)) },
            ) {
                AppText("pinned", color = tc.ac, fontSize = 9.sp)
            }
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
    HoverBox(
        modifier = Modifier.pointerHoverIcon(
            PointerIcon(AwtCursor.getPredefinedCursor(AwtCursor.HAND_CURSOR)),
            overrideDescendants = true,
        ),
        onClick = onClick,
    ) {
        AppText(label, color = color, fontSize = 11.sp, fontWeight = FontWeight.Medium)
    }
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
