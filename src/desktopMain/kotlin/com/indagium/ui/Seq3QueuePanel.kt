@file:OptIn(androidx.compose.ui.ExperimentalComposeUiApi::class)

package com.indagium.ui

import androidx.compose.foundation.VerticalScrollbar
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollbarAdapter
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.DisableSelection
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
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.*
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupProperties
import com.indagium.diagram3.Seq3AddResult
import com.indagium.diagram3.Seq3Authoring
import com.indagium.diagram3.Seq3BulkAction
import com.indagium.diagram3.Seq3Command
import com.indagium.diagram3.Seq3CustomMessageSpec
import com.indagium.diagram3.Seq3Document
import com.indagium.diagram3.Seq3Filter
import com.indagium.diagram3.Seq3Fragment
import com.indagium.diagram3.Seq3FragmentKind
import com.indagium.diagram3.Seq3InsertionPosition
import com.indagium.diagram3.Seq3Kind
import com.indagium.diagram3.Seq3Message
import com.indagium.diagram3.Seq3Note
import com.indagium.diagram3.Seq3OccurrenceRef
import com.indagium.diagram3.Seq3PinDirection
import com.indagium.diagram3.Seq3Repeat
import com.indagium.diagram3.Seq3Selection
import com.indagium.diagram3.Seq3Sort
import com.indagium.diagram3.Seq3State
import com.indagium.diagram3.Seq3Visibility
import com.indagium.diagram3.Seq3Occurrence
import com.indagium.diagram3.addSeq3MessageFromSelection
import com.indagium.diagram3.nudgeSeq3OrderPin
import com.indagium.diagram3.parseSeq3Timestamp
import com.indagium.diagram3.seq3FilterCounts
import com.indagium.diagram3.seq3QueueRows
import com.indagium.diagram3.seq3Select
import com.indagium.model.LogEntry
import kotlinx.coroutines.delay
import java.util.UUID
import java.awt.Cursor as AwtCursor
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.OpenInNew
import androidx.compose.material.icons.outlined.Visibility
import androidx.compose.material.icons.outlined.VisibilityOff

private const val ADD_HINT_DURATION_MS = 2_500L
private const val ADD_ROW_RANGE_LIMIT = 2_000
private const val SEQ3_QUEUE_DOUBLE_CLICK_WINDOW_MS = 350L
private val SEQ3_ACTION_BADGE_SIZE = 24.dp
private val SEQ3_SUBMESSAGE_ROW_HEIGHT = 44.dp

private val MESSAGE_KIND_OPTIONS = listOf(
    Seq3Kind.CALL,
    Seq3Kind.RETURN,
    Seq3Kind.ASYNC,
    Seq3Kind.SELF,
)

private enum class Seq3AddDialog {
    ROWS,
    CUSTOM,
}

private enum class Seq3CustomPositionMode {
    START,
    END,
    BEFORE,
    AFTER,
    INDEX,
}

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
    val hasSelectableMessageSelection = view.selection.selectedIds.any { id ->
        document.messages.any { it.id == id }
    }
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
        Seq3QueueHeader(state, session, counts, view)
        if (view.messagesExpanded) {
            Column(Modifier.weight(1f).fillMaxWidth()) {
                if (counts.needsTarget > 0) {
                    Seq3NeedsTargetBanner(counts.needsTarget) {
                        // Spec §05: the banner is what starts the guided pass. `startSeq3GuidedPass`
                        // returns null only when nothing is unresolved — unreachable here, since this
                        // banner is itself gated on needsTarget > 0.
                        view.guidedPass = startSeq3GuidedPass(document)
                        runCatching { view.focusRequester.requestFocus() }
                    }
                }
                Seq3FilterChipsRow(view, counts)
                Seq3FilterTextAndSortRow(view)
                if (document.fragments.isNotEmpty() || document.notes.isNotEmpty()) {
                    Seq3FragmentsAndNotesSection(state, session, view, document)
                }
                Box(Modifier.weight(1f)) {
                    LazyColumn(
                        Modifier.fillMaxSize().padding(end = 6.dp),
                        state = listState,
                        verticalArrangement = Arrangement.spacedBy(4.dp),
                    ) {
                        items(rows, key = Seq3Message::id) { message ->
                            Seq3QueueRow(state, session, view, document, message, visibleIds)
                        }
                    }
                    VerticalScrollbar(
                        adapter = rememberScrollbarAdapter(listState),
                        modifier = Modifier.align(Alignment.CenterEnd).fillMaxHeight().width(6.dp),
                        style = appScrollbarStyle(tc),
                    )
                }
                if (view.selectedOccurrenceIds.isNotEmpty()) {
                    Seq3OccurrenceSelectionActionBar(state, session, view, document)
                } else if (hasSelectableMessageSelection) {
                    Seq3SelectionActionBar(state, session, view, document)
                } else {
                    Seq3QueueFooter(counts) { view.regenerateSheetOpen = true }
                }
            }
        }
        if (view.messagesExpanded && view.inspectorExpanded) {
            VDivider { delta ->
                // Dragging the boundary upward (negative delta) gives Inspector more room.
                view.inspectorHeightDp = seq3ClampDividerWidth(
                    view.inspectorHeightDp,
                    -delta,
                    SEQ3_INSPECTOR_HEIGHT_MIN_DP,
                    SEQ3_INSPECTOR_HEIGHT_MAX_DP,
                )
            }
        }
        SectionHeader(
            title = "Inspector",
            expanded = view.inspectorExpanded,
            onToggle = { view.inspectorExpanded = !view.inspectorExpanded },
        )
        if (view.inspectorExpanded) {
            val inspectorModifier = if (view.messagesExpanded) {
                Modifier.fillMaxWidth().height(view.inspectorHeightDp.dp)
            } else {
                Modifier.fillMaxWidth().weight(1f)
            }
            Seq3Inspector(
                state,
                session,
                view,
                inspectorModifier,
            )
        }
    }
}

/** Resolves a log tab's currently-selected row ids into the [LogEntry]s "Add ＋" (item 2) hands to
 *  [addSeq3MessageFromSelection] — pure and `internal` purely for testability, mirroring
 *  [seq3PinnableDirections]/[seq3TemplateSegments]'s own "no composition needed" split. */
internal fun seq3ResolveSelectedEntries(logData: List<LogEntry>, selectedIds: Set<Int>): List<LogEntry> =
    logData.filter { it.id in selectedIds }

/** Accepts `12`, `12, 15`, and `12-15` so the add flow can use log row ids without another picker. */
internal fun seq3ParseRowNumbers(raw: String): List<Int> = raw
    .split(',', ';', ' ', '\n', '\t')
    .mapNotNull { token ->
        val value = token.trim()
        if (value.isEmpty()) return@mapNotNull null
        val parts = value.split('-', limit = 2)
        if (parts.size == 1) {
            parts[0].toIntOrNull()?.let(::listOf)
        } else {
            val first = parts[0].toIntOrNull()
            val last = parts[1].toIntOrNull()
            if (first == null || last == null) {
                null
            } else {
                val start = minOf(first, last)
                val end = minOf(maxOf(first, last).toLong(), start.toLong() + ADD_ROW_RANGE_LIMIT - 1).toInt()
                (start..end).toList()
            }
        }
    }
    .flatten()
    .distinct()

@Composable
private fun Seq3QueueHeader(
    state: AppState,
    session: Seq3WorkspaceSession,
    counts: com.indagium.diagram3.Seq3FilterCounts,
    view: Seq3ViewState,
) {
    val tc = tc()
    val density = LocalDensity.current
    // A short-lived rejection hint ("Select rows from a single tag", …) — this file has no other
    // transient-message convention to match (checked, per this phase's brief), so a plain
    // remember+LaunchedEffect auto-clear is the documented fallback.
    var addHint by remember(session.id) { mutableStateOf<String?>(null) }
    var addMenuOpen by remember(session.id) { mutableStateOf(false) }
    var addDialog by remember(session.id) { mutableStateOf<Seq3AddDialog?>(null) }
    LaunchedEffect(addHint) {
        if (addHint != null) {
            delay(ADD_HINT_DURATION_MS)
            addHint = null
        }
    }

    fun addFromEntries(selectedEntries: List<LogEntry>) {
        when (val result = addSeq3MessageFromSelection(session.document, selectedEntries)) {
            is Seq3AddResult.Added ->
                state.seq3Sessions.applyCommand(session.id, Seq3Command.ReplaceDocument(result.document))
            is Seq3AddResult.Rejected -> addHint = result.reason
        }
    }

    val tab = session.sourceTabId?.let(state::tab)
    Column(Modifier.fillMaxWidth()) {
        Box(Modifier.fillMaxWidth()) {
            SectionHeader(
                title = "Messages · ${counts.all}",
                trailing = {
                    // The action stays on the shared 32dp header rhythm, but opens a visible
                    // choice surface instead of silently assuming the log selection is correct.
                    LabelIconButton(
                        text = "+ message",
                        fontSize = 10.sp,
                        onClick = { addMenuOpen = !addMenuOpen },
                        modifier = Modifier.pointerHoverIcon(
                            PointerIcon(AwtCursor.getPredefinedCursor(AwtCursor.HAND_CURSOR)),
                            overrideDescendants = true,
                        ),
                    )
                },
                expanded = view.messagesExpanded,
                onToggle = { view.messagesExpanded = !view.messagesExpanded },
            )
            if (addMenuOpen) {
                Popup(
                    alignment = Alignment.TopEnd,
                    offset = with(density) { IntOffset(0, 34.dp.roundToPx()) },
                    onDismissRequest = { addMenuOpen = false },
                    properties = PopupProperties(focusable = false),
                ) {
                    Seq3AddMessageChoiceMenu(
                        selectedCount = tab?.selected?.size ?: 0,
                        onSelectedRows = {
                            addMenuOpen = false
                            addFromEntries(tab?.let { seq3ResolveSelectedEntries(it.logData, it.selected) }.orEmpty())
                        },
                        onRows = {
                            addMenuOpen = false
                            addDialog = Seq3AddDialog.ROWS
                        },
                        onLogHandoff = {
                            addMenuOpen = false
                            session.sourceTabId?.let(state::activateTab)
                        },
                        onCustom = {
                            addMenuOpen = false
                            addDialog = Seq3AddDialog.CUSTOM
                        },
                    )
                }
            }
        }
        addHint?.let { hint ->
            AppText(hint, color = tc.warn, fontSize = 9.sp, modifier = Modifier.padding(horizontal = 12.dp, vertical = 2.dp))
        }
    }
    when (addDialog) {
        Seq3AddDialog.ROWS -> Seq3AddRowsDialog(
            entries = tab?.logData.orEmpty(),
            onDismiss = { addDialog = null },
            onAdd = { entries ->
                addDialog = null
                addFromEntries(entries)
            },
        )
        Seq3AddDialog.CUSTOM -> Seq3AddCustomDialog(
            document = session.document,
            onDismiss = { addDialog = null },
            onAdd = { spec ->
                addDialog = null
                if (!state.seq3Sessions.applyCommand(session.id, Seq3Command.AddCustomMessage(spec))) {
                    addHint = "The custom message could not be added"
                }
            },
        )
        null -> Unit
    }
}

@Composable
private fun Seq3AddMessageChoiceMenu(
    selectedCount: Int,
    onSelectedRows: () -> Unit,
    onRows: () -> Unit,
    onLogHandoff: () -> Unit,
    onCustom: () -> Unit,
) {
    val tc = tc()
    Column(
        Modifier.width(238.dp).clip(RoundedCornerShape(8.dp))
            .background(tc.p, RoundedCornerShape(8.dp))
            .border(1.dp, tc.br, RoundedCornerShape(8.dp))
            .padding(6.dp),
    ) {
        AppText("Add message from…", color = tc.td, fontSize = 10.sp, modifier = Modifier.padding(horizontal = 8.dp, vertical = 5.dp))
        AppButton(
            "Selected log rows · $selectedCount",
            onClick = onSelectedRows,
            enabled = selectedCount > 0,
            variant = ButtonVariant.Ghost,
            modifier = Modifier.fillMaxWidth(),
            horizontalPadding = 8.dp,
        )
        AppButton(
            "Enter row numbers…",
            onClick = onRows,
            variant = ButtonVariant.Ghost,
            modifier = Modifier.fillMaxWidth(),
            horizontalPadding = 8.dp,
        )
        AppButton(
            "Open log view to select rows",
            onClick = onLogHandoff,
            variant = ButtonVariant.Ghost,
            modifier = Modifier.fillMaxWidth(),
            horizontalPadding = 8.dp,
        )
        AppButton(
            "Custom message…",
            onClick = onCustom,
            variant = ButtonVariant.Ghost,
            modifier = Modifier.fillMaxWidth(),
            horizontalPadding = 8.dp,
        )
    }
}

@Composable
private fun Seq3AddRowsDialog(entries: List<LogEntry>, onDismiss: () -> Unit, onAdd: (List<LogEntry>) -> Unit) {
    val tc = tc()
    var raw by remember { mutableStateOf("") }
    val selected = remember(raw, entries) {
        val ids = seq3ParseRowNumbers(raw).toSet()
        entries.filter { it.id in ids }
    }
    val hasInput = raw.isNotBlank()
    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Column(
            Modifier.width(430.dp).clip(RoundedCornerShape(12.dp))
                .background(tc.p, RoundedCornerShape(12.dp))
                .border(1.dp, tc.br, RoundedCornerShape(12.dp))
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            AppText("Add message from log rows", color = tc.tx, fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
            AppText("Enter row numbers or ranges, for example 12, 14-16.", color = tc.ts, fontSize = 11.sp)
            InlineField(raw, { raw = it }, placeholder = "Row numbers…", modifier = Modifier.fillMaxWidth(), fontSize = 12.sp)
            AppText(
                when {
                    !hasInput -> ""
                    selected.isEmpty() -> "No matching rows"
                    else -> "${selected.size} row${if (selected.size == 1) "" else "s"} selected"
                },
                color = if (selected.isEmpty() && hasInput) tc.warn else tc.ts,
                fontSize = 10.sp,
            )
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End)) {
                AppButton("Cancel", onClick = onDismiss, variant = ButtonVariant.Ghost)
                AppButton("Add message", onClick = { onAdd(selected) }, enabled = selected.isNotEmpty(), variant = ButtonVariant.Primary)
            }
        }
    }
}

@Composable
private fun Seq3AddCustomDialog(
    document: Seq3Document,
    onDismiss: () -> Unit,
    onAdd: (Seq3CustomMessageSpec) -> Unit,
) {
    val tc = tc()
    var message by remember { mutableStateOf("") }
    var fromId by remember(document) { mutableStateOf(document.lifelines.firstOrNull()?.id) }
    var toId by remember(document) { mutableStateOf(document.lifelines.getOrNull(1)?.id ?: document.lifelines.firstOrNull()?.id) }
    var kind by remember { mutableStateOf(Seq3Kind.CALL) }
    var timestamp by remember { mutableStateOf("") }
    var positionMode by remember { mutableStateOf(Seq3CustomPositionMode.END) }
    var positionValue by remember { mutableStateOf("") }
    var fragmentId by remember { mutableStateOf<String?>(null) }

    val position = when (positionMode) {
        Seq3CustomPositionMode.START -> Seq3InsertionPosition.Start
        Seq3CustomPositionMode.END -> Seq3InsertionPosition.End
        Seq3CustomPositionMode.BEFORE -> document.messages.firstOrNull { it.id == positionValue }
            ?.let { Seq3InsertionPosition.BeforeMessage(it.id) }
        Seq3CustomPositionMode.AFTER -> document.messages.firstOrNull { it.id == positionValue }
            ?.let { Seq3InsertionPosition.AfterMessage(it.id) }
        Seq3CustomPositionMode.INDEX -> positionValue.toIntOrNull()?.let(Seq3InsertionPosition::AtIndex)
    }
    val validEndpoints = fromId != null && (kind == Seq3Kind.NOTE || toId != null)
    val canAdd = message.isNotBlank() && validEndpoints && position != null &&
        (position !is Seq3InsertionPosition.AtIndex || position.index in 0..document.messages.size)

    fun buildSpec(): Seq3CustomMessageSpec? {
        val selectedFrom = fromId ?: return null
        val selectedPosition = position ?: return null
        return Seq3CustomMessageSpec(
            fromLifelineId = selectedFrom,
            toLifelineId = if (kind == Seq3Kind.NOTE) null else toId,
            text = message,
            timestampMillis = parseSeq3Timestamp(timestamp),
            rawTimestamp = timestamp,
            position = selectedPosition,
            kind = kind,
            fragmentId = fragmentId,
        )
    }

    fun chooseKind(next: Seq3Kind) {
        kind = next
        when (next) {
            Seq3Kind.SELF -> toId = fromId
            Seq3Kind.NOTE -> toId = null
            else -> if (toId == null) toId = document.lifelines.firstOrNull { it.id != fromId }?.id ?: fromId
        }
    }

    val positionLabel = when (positionMode) {
        Seq3CustomPositionMode.START -> "At start"
        Seq3CustomPositionMode.END -> "At end"
        Seq3CustomPositionMode.INDEX -> "At index ${positionValue.ifBlank { "…" }}"
        Seq3CustomPositionMode.BEFORE -> document.messages.firstOrNull { it.id == positionValue }
            ?.let { "Before ${document.messages.indexOf(it) + 1}: ${it.labelTemplate}" } ?: "Before message…"
        Seq3CustomPositionMode.AFTER -> document.messages.firstOrNull { it.id == positionValue }
            ?.let { "After ${document.messages.indexOf(it) + 1}: ${it.labelTemplate}" } ?: "After message…"
    }

    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Column(
            Modifier.width(600.dp).clip(RoundedCornerShape(12.dp))
                .background(tc.p, RoundedCornerShape(12.dp))
                .border(1.dp, tc.br, RoundedCornerShape(12.dp))
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            AppText("Add custom message", color = tc.tx, fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Seq3CustomLifelinePicker("From", document, fromId, Modifier.weight(1f)) { fromId = it; if (kind == Seq3Kind.SELF) toId = it }
                if (kind != Seq3Kind.NOTE && kind != Seq3Kind.SELF) {
                    Seq3CustomLifelinePicker("To", document, toId, Modifier.weight(1f)) { toId = it }
                }
            }
            AppText("Kind", color = tc.td, fontSize = 10.sp)
            SegmentedControl(
                options = listOf("call", "return", "async", "self", "note"),
                selectedIndices = setOf(Seq3Kind.entries.indexOf(kind)),
                onToggle = { chooseKind(Seq3Kind.entries[it]) },
                fillWidth = true,
                modifier = Modifier.fillMaxWidth(),
            )
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                InlineField(
                    timestamp,
                    { timestamp = it },
                    placeholder = "Timestamp (optional, e.g. 09:15:16.500)",
                    modifier = Modifier.weight(1f),
                    fontSize = 12.sp,
                )
                Seq3DropdownButton(positionLabel, modifier = Modifier.weight(1f), menuWidth = 330.dp) { close ->
                    Seq3DropdownMenuItem("At start", active = positionMode == Seq3CustomPositionMode.START) {
                        positionMode = Seq3CustomPositionMode.START; positionValue = ""; close()
                    }
                    Seq3DropdownMenuItem("At end", active = positionMode == Seq3CustomPositionMode.END) {
                        positionMode = Seq3CustomPositionMode.END; positionValue = ""; close()
                    }
                    Seq3DropdownMenuItem("At exact index (0 = first)", active = positionMode == Seq3CustomPositionMode.INDEX) {
                        positionMode = Seq3CustomPositionMode.INDEX; positionValue = ""; close()
                    }
                    document.messages.forEachIndexed { index, candidate ->
                        Seq3DropdownMenuItem(
                            "Before ${index + 1}: ${candidate.labelTemplate}",
                            active = positionMode == Seq3CustomPositionMode.BEFORE && positionValue == candidate.id,
                        ) {
                            positionMode = Seq3CustomPositionMode.BEFORE; positionValue = candidate.id; close()
                        }
                        Seq3DropdownMenuItem(
                            "After ${index + 1}: ${candidate.labelTemplate}",
                            active = positionMode == Seq3CustomPositionMode.AFTER && positionValue == candidate.id,
                        ) {
                            positionMode = Seq3CustomPositionMode.AFTER; positionValue = candidate.id; close()
                        }
                    }
                }
            }
            if (positionMode == Seq3CustomPositionMode.INDEX) {
                InlineField(
                    positionValue,
                    { positionValue = it.filter(Char::isDigit) },
                    placeholder = "Insertion index…",
                    modifier = Modifier.fillMaxWidth(),
                    fontSize = 12.sp,
                )
            }
            Seq3DropdownButton(
                label = fragmentId?.let { id ->
                    document.fragments.firstOrNull { it.id == id }?.let { f -> "${f.kind.name.lowercase()}: ${f.label}" }
                } ?: "No fragment",
                modifier = Modifier.fillMaxWidth(),
                menuWidth = 430.dp,
            ) { close ->
                Seq3DropdownMenuItem("No fragment", active = fragmentId == null) { fragmentId = null; close() }
                document.fragments.forEach { fragment ->
                    Seq3DropdownMenuItem("${fragment.kind.name.lowercase()}: ${fragment.label}", active = fragment.id == fragmentId) {
                        fragmentId = fragment.id; close()
                    }
                }
            }
            InlineField(
                message,
                { message = it },
                placeholder = "Message text…",
                modifier = Modifier.fillMaxWidth(),
                fontSize = 12.sp,
                onSubmit = { if (canAdd) buildSpec()?.let(onAdd) },
            )
            if (!canAdd) {
                AppText(
                    when {
                        message.isBlank() -> "Enter message text"
                        !validEndpoints -> "Choose both From and To lifelines"
                        position == null -> "Choose a valid insertion position"
                        else -> ""
                    },
                    color = tc.warn,
                    fontSize = 10.sp,
                )
            }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End)) {
                Spacer(Modifier.weight(1f))
                AppButton("Cancel", onClick = onDismiss, variant = ButtonVariant.Ghost)
                AppButton(
                    "Add message",
                    onClick = { buildSpec()?.let(onAdd) },
                    enabled = canAdd,
                    variant = ButtonVariant.Primary,
                )
            }
        }
    }
}

@Composable
private fun Seq3CustomLifelinePicker(
    title: String,
    document: Seq3Document,
    selectedId: String?,
    modifier: Modifier,
    onSelected: (String) -> Unit,
) {
    Column(modifier, verticalArrangement = Arrangement.spacedBy(3.dp)) {
        AppText(title, color = tc().td, fontSize = 10.sp)
        Seq3DropdownButton(
            label = document.lifelines.firstOrNull { it.id == selectedId }?.name ?: "Choose lifeline",
            modifier = Modifier.fillMaxWidth(),
            menuWidth = 240.dp,
        ) { close ->
            document.lifelines.sortedBy { it.ordinal }.forEach { lifeline ->
                Seq3DropdownMenuItem(lifeline.name, active = lifeline.id == selectedId) {
                    onSelected(lifeline.id)
                    close()
                }
            }
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
        AppButton(
            "Fix these →",
            onClick = onFixThese,
            variant = ButtonVariant.Ghost,
            textColor = tc.warn,
            horizontalPadding = 0.dp,
            modifier = Modifier.pointerHoverIcon(
                PointerIcon(AwtCursor.getPredefinedCursor(AwtCursor.HAND_CURSOR)),
                overrideDescendants = true,
            ),
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
        // Opens spec §08's review sheet — never a silent full rebuild, which is exactly what
        // "Regenerate is a reviewed proposal, never a wholesale replace" rules out.
        AppButton(
            "Regenerate…",
            onClick = onRegenerate,
            variant = ButtonVariant.Ghost,
            textColor = tc.ac,
            horizontalPadding = 0.dp,
            modifier = Modifier.pointerHoverIcon(
                PointerIcon(AwtCursor.getPredefinedCursor(AwtCursor.HAND_CURSOR)),
                overrideDescendants = true,
            ),
        )
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
private fun Seq3FragmentsAndNotesSection(
    state: AppState,
    session: Seq3WorkspaceSession,
    view: Seq3ViewState,
    document: Seq3Document,
) {
    var expanded by remember(session.id) { mutableStateOf(false) }
    val total = document.fragments.size + document.notes.size
    Column(Modifier.fillMaxWidth()) {
        SectionHeader(
            title = "Fragments & notes · $total",
            expanded = expanded,
            onToggle = { expanded = !expanded },
        )
        if (expanded) {
            document.fragments.forEach { fragment -> Seq3FragmentRenameRow(state, session, view, fragment) }
            document.notes.forEach { note -> Seq3NoteRenameRow(state, session, view, note) }
        }
    }
}

@Composable
private fun Seq3FragmentRenameRow(state: AppState, session: Seq3WorkspaceSession, view: Seq3ViewState, fragment: Seq3Fragment) {
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
            InlineField(
                value = text,
                onValue = { text = it },
                fontSize = 10.sp,
                modifier = Modifier.weight(1f).onFocusChanged { view.textFieldFocused = it.hasFocus },
                onSubmit = ::commit,
            )
            SquareIconButton("✓", fontSize = 10.sp, onClick = ::commit, size = 16.dp)
            SquareIconButton("×", fontSize = 10.sp, onClick = { editing = false }, size = 16.dp)
        } else {
            Box(Modifier.weight(1f).pointerInput(fragment.id) { detectTapGestures(onDoubleTap = { editing = true }) }) {
                AppText(fragment.label, color = tc.tx, fontSize = 10.sp, maxLines = 1)
            }
            SquareIconButton("✎", fontSize = 10.sp, onClick = { editing = true }, size = 18.dp)
            SquareIconButton(
                "×",
                fontSize = 11.sp,
                onClick = {
                    state.seq3Sessions.applyCommand(
                        session.id,
                        Seq3Command.Bulk(emptySet(), Seq3BulkAction.DeleteFragment(fragment.id)),
                    )
                },
                size = 18.dp,
            )
        }
    }
}

@Composable
private fun Seq3NoteRenameRow(state: AppState, session: Seq3WorkspaceSession, view: Seq3ViewState, note: Seq3Note) {
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
            InlineField(
                value = text,
                onValue = { text = it },
                fontSize = 10.sp,
                modifier = Modifier.weight(1f).onFocusChanged { view.textFieldFocused = it.hasFocus },
                onSubmit = ::commit,
            )
            SquareIconButton("✓", fontSize = 10.sp, onClick = ::commit, size = 16.dp)
            SquareIconButton("×", fontSize = 10.sp, onClick = { editing = false }, size = 16.dp)
        } else {
            Box(Modifier.weight(1f).pointerInput(note.id) { detectTapGestures(onDoubleTap = { editing = true }) }) {
                AppText(note.text, color = tc.tx, fontSize = 10.sp, maxLines = 1)
            }
            SquareIconButton("✎", fontSize = 10.sp, onClick = { editing = true }, size = 18.dp)
            SquareIconButton(
                "×",
                fontSize = 11.sp,
                onClick = {
                    state.seq3Sessions.applyCommand(
                        session.id,
                        Seq3Command.Bulk(emptySet(), Seq3BulkAction.DeleteNote(note.id)),
                    )
                },
                size = 18.dp,
            )
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
    val inspectorSelected = view.inspectorMessageId == message.id
    val collapsedCount = seq3CollapsedOccurrenceCount(message)
    val mergeBackTarget = seq3MergeBackTarget(document, message)
    // Pin controls only make sense against LOG-ORDER adjacency (Seq3Queue's nudge is defined over
    // Seq3Document.messages' own order) — showing them under a different view sort would point at
    // a neighbour that isn't actually adjacent on screen. See this phase's report for the note.
    val pinnable = if (view.sort == Seq3Sort.LOG_ORDER) seq3PinnableDirections(document, message.id) else emptySet()

    Column(
        Modifier.fillMaxWidth()
            .clip(CORNER_SM)
            .background(
                when {
                    inspectorSelected -> tc.abg
                    selected -> tc.sl
                    needsTarget -> tc.warnBg
                    hovered -> tc.hv
                    else -> Color.Transparent
                },
                CORNER_SM,
            )
            // Inspector focus is intentionally independent from checkbox selection: the accent
            // outline identifies the row being inspected without changing its checkbox state.
            .then(if (inspectorSelected) Modifier.border(1.dp, tc.ac, CORNER_SM) else Modifier)
            .drawBehind {
                if (selected) drawRect(color = tc.ac, size = Size(3.dp.toPx(), size.height))
            }
            .onPointerEvent(PointerEventType.Enter) { view.hoveredMessageId = message.id }
            .onPointerEvent(PointerEventType.Exit) { if (view.hoveredMessageId == message.id) view.hoveredMessageId = null }
            .padding(start = 4.dp, end = 10.dp, top = 6.dp, bottom = 6.dp),
    ) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            if (message.occurrences.size > 1) {
                Seq3OccurrenceToggle(
                    expanded = message.id in view.expandedOccurrenceMessageIds,
                    modifier = Modifier.padding(top = 2.dp),
                ) {
                    view.expandedOccurrenceMessageIds = if (message.id in view.expandedOccurrenceMessageIds) {
                        view.expandedOccurrenceMessageIds - message.id
                    } else {
                        view.expandedOccurrenceMessageIds + message.id
                    }
                    runCatching { view.focusRequester.requestFocus() }
                }
            } else {
                Box(Modifier.width(16.dp).padding(top = 2.dp)) {
                    Seq3RowCheckbox(checked = selected) {
                        // Checkbox selection is independent from inspector/occurrence focus.
                        view.selection = seq3Select(visibleIds, view.selection, message.id, additive = true)
                        view.selectionFromMarquee = false
                        view.selectedCanvasRows = emptySet()
                        view.selectedOccurrenceMessageId = null
                        view.selectedOccurrenceEntryId = null
                        runCatching { view.focusRequester.requestFocus() }
                    }
                }
            }
            // Keep the Inspector-only row press handler on the message body, not on the row
            // wrapper. The checkbox is a sibling hit target, so its pointer stream cannot reach
            // this handler and can never activate/deactivate the checkbox as a side effect — even
            // with Shift/Cmd/Ctrl held down.
            Column(
                Modifier.weight(1f).pointerInput(message.id) {
                    var lastBodyClickMs = 0L
                    awaitPointerEventScope {
                        while (true) {
                            val event = awaitPointerEvent()
                            if (event.type == PointerEventType.Press && event.buttons.isPrimaryPressed &&
                                event.changes.none { it.isConsumed }
                            ) {
                                val now = System.currentTimeMillis()
                                val doubleClick = now - lastBodyClickMs <= SEQ3_QUEUE_DOUBLE_CLICK_WINDOW_MS
                                lastBodyClickMs = now
                                val modifiers = event.keyboardModifiers
                                val additive = modifiers.isCtrlPressed || modifiers.isMetaPressed
                                if (additive && !modifiers.isShiftPressed) {
                                    // Queue-body Cmd/Ctrl-clicks use the same message-row set as a
                                    // canvas marquee. This makes non-contiguous grouping available
                                    // from the side panel too, while a plain queue click remains a
                                    // normal whole-message selection.
                                    val baseRows = view.selection.selectedIds.mapTo(linkedSetOf()) {
                                        Seq3CanvasRowRef(it, occurrenceEntryId = null)
                                    }
                                    val rowRef = Seq3CanvasRowRef(message.id, occurrenceEntryId = null)
                                    val nextRows = if (rowRef in baseRows) baseRows - rowRef else baseRows + rowRef
                                    view.selectedCanvasRows = nextRows
                                    view.selection = Seq3Selection(
                                        selectedIds = nextRows.mapTo(linkedSetOf()) { it.messageId },
                                        anchorId = message.id,
                                    )
                                } else {
                                    view.selection = seq3Select(
                                        visibleIds,
                                        view.selection,
                                        message.id,
                                        additive = additive,
                                        range = modifiers.isShiftPressed,
                                    )
                                    view.selectedCanvasRows = emptySet()
                                }
                                view.selectionFromMarquee = false
                                view.selectedOccurrenceIds = emptySet()
                                view.selectedOccurrenceMessageId = null
                                view.selectedOccurrenceEntryId = null
                                view.inspectorMessageId = message.id.takeIf { view.selection.selectedIds.isNotEmpty() }
                                if (doubleClick && message.occurrences.size > 1) {
                                    view.expandedOccurrenceMessageIds = if (message.id in view.expandedOccurrenceMessageIds) {
                                        view.expandedOccurrenceMessageIds - message.id
                                    } else {
                                        view.expandedOccurrenceMessageIds + message.id
                                    }
                                }
                                runCatching { view.focusRequester.requestFocus() }
                            }
                        }
                    }
                },
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                Seq3RowPatternLine(message, collapsedCount)
                Seq3RowEndpointsLine(state, session, message)
                Seq3MessageControlsLine(state, session, message, pinnable, hidden, mergeBackTarget)
                if (hidden) {
                    Row(Modifier.fillMaxWidth().padding(top = 2.dp), horizontalArrangement = Arrangement.SpaceBetween) {
                        AppText("Hidden from canvas · evidence kept", color = tc.td, fontSize = 10.sp)
                    }
                } else if (collapsedCount != null) {
                    Row(Modifier.fillMaxWidth().padding(top = 2.dp), horizontalArrangement = Arrangement.SpaceBetween) {
                        AppText("Collapsed to one arrow · ×$collapsedCount", color = tc.td, fontSize = 10.sp)
                        AppButton(
                            "Show occurrences",
                            onClick = {
                                state.seq3Sessions.applyCommand(
                                    session.id,
                                    Seq3Command.Bulk(setOf(message.id), Seq3BulkAction.SetRepeat(Seq3Repeat.EVERY, message.repeatThreshold)),
                                )
                            },
                            variant = ButtonVariant.Ghost,
                            textColor = tc.ac,
                            horizontalPadding = 0.dp,
                            modifier = Modifier.pointerHoverIcon(
                                PointerIcon(AwtCursor.getPredefinedCursor(AwtCursor.HAND_CURSOR)),
                                overrideDescendants = true,
                            ),
                        )
                    }
                }
            }
        }
        if (message.occurrences.size > 1 && message.id in view.expandedOccurrenceMessageIds) {
            // Keep the nested list at its content height up to ten rows. A fixed height based on
            // the actual row count avoids leaving a large blank panel below short occurrence lists,
            // while still keeping the list scrollable when it exceeds the ten-row limit.
            val displayedOccurrenceCount = message.occurrences.size.coerceAtMost(10)
            val occurrenceListHeight = SEQ3_SUBMESSAGE_ROW_HEIGHT * displayedOccurrenceCount +
                3.dp * (displayedOccurrenceCount - 1).coerceAtLeast(0)
            Box(
                Modifier.fillMaxWidth()
                    .padding(start = 22.dp, top = 5.dp)
                    .height(occurrenceListHeight),
            ) {
                val submessageListState = rememberLazyListState()
                LazyColumn(
                    Modifier.fillMaxWidth().padding(end = 6.dp),
                    state = submessageListState,
                    verticalArrangement = Arrangement.spacedBy(3.dp),
                ) {
                    items(message.occurrences, key = { occurrence -> occurrenceSelectionKey(message.id, occurrence.entryId) }) { occurrence ->
                        Seq3OccurrenceSubRow(state, session, view, message, occurrence)
                    }
                }
                VerticalScrollbar(
                    adapter = rememberScrollbarAdapter(submessageListState),
                    modifier = Modifier.align(Alignment.CenterEnd).fillMaxHeight().width(6.dp),
                    style = appScrollbarStyle(tc),
                )
                }
        }
    }
}

private fun occurrenceSelectionKey(messageId: String, entryId: Int): String = "$messageId::$entryId"

@Composable
private fun Seq3OccurrenceToggle(expanded: Boolean, modifier: Modifier = Modifier, onClick: () -> Unit) {
    val tc = tc()
    Box(
        modifier.size(16.dp)
            .clip(RoundedCornerShape(4.dp))
            .clickable(onClick = onClick)
            .background(if (expanded) tc.abg else tc.p2, RoundedCornerShape(4.dp))
            .border(1.dp, if (expanded) tc.ac else tc.br, RoundedCornerShape(4.dp)),
        contentAlignment = Alignment.Center,
    ) {
        DisableSelection { AppText(if (expanded) "▾" else "▸", color = tc.ts, fontSize = 9.sp) }
    }
}

@Composable
private fun Seq3OccurrenceSubRow(
    state: AppState,
    session: Seq3WorkspaceSession,
    view: Seq3ViewState,
    message: Seq3Message,
    occurrence: Seq3Occurrence,
) {
    val tc = tc()
    val entryId = occurrence.entryId
    val key = occurrenceSelectionKey(message.id, entryId)
    val checked = key in view.selectedOccurrenceIds
    val selected = view.selectedOccurrenceMessageId == message.id && view.selectedOccurrenceEntryId == entryId
    Row(
        Modifier.fillMaxWidth()
            .clip(CORNER_SM)
            .background(if (checked || selected) tc.sl else tc.p2, CORNER_SM)
            .height(SEQ3_SUBMESSAGE_ROW_HEIGHT)
            .padding(start = 6.dp, end = 6.dp, top = 4.dp, bottom = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Seq3RowCheckbox(checked) {
            view.selectedOccurrenceIds = if (checked) view.selectedOccurrenceIds - key else view.selectedOccurrenceIds + key
            view.selectionFromMarquee = false
            view.selectedCanvasRows = emptySet()
            view.selectedOccurrenceMessageId = message.id
            view.selectedOccurrenceEntryId = entryId
            view.inspectorMessageId = message.id
            runCatching { view.focusRequester.requestFocus() }
        }
        Column(
            Modifier.weight(1f).pointerInput(key) {
                awaitPointerEventScope {
                    while (true) {
                        val event = awaitPointerEvent()
                        if (event.type == PointerEventType.Press && event.buttons.isPrimaryPressed &&
                            event.changes.none { it.isConsumed }
                        ) {
                            // Selecting an occurrence for inspection must not select its parent
                            // message. The occurrence checkbox is the explicit occurrence-level
                            // selection control.
                            view.selectedOccurrenceMessageId = message.id
                            view.selectionFromMarquee = false
                            view.selectedCanvasRows = emptySet()
                            view.selectedOccurrenceEntryId = entryId
                            view.inspectorMessageId = message.id
                            runCatching { view.focusRequester.requestFocus() }
                        }
                    }
                }
            },
            verticalArrangement = Arrangement.spacedBy(1.dp),
        ) {
            DisableSelection {
                AppText(
                    occurrence.rawTimestamp.ifBlank { "line $entryId" },
                    color = tc.td,
                    fontSize = 9.sp,
                    fontFamily = MONO,
                    maxLines = 1,
                )
                AppText(occurrence.text, color = tc.tx, fontSize = 10.sp, fontFamily = MONO, maxLines = 2)
            }
        }
        val hidden = occurrence.visibility == Seq3Visibility.HIDDEN
        ToolbarBtn(
            label = if (hidden) "Show occurrence" else "Hide occurrence",
            icon = if (hidden) Icons.Outlined.Visibility else Icons.Outlined.VisibilityOff,
            showLabel = false,
            tooltip = if (hidden) "Show only this occurrence" else "Hide only this occurrence",
            active = hidden,
            contentPadding = PaddingValues(0.dp),
            modifier = Modifier.size(SEQ3_ACTION_BADGE_SIZE),
            shape = CORNER_SM,
            onClick = {
                state.seq3Sessions.applyCommand(
                    session.id,
                    Seq3Command.SetOccurrenceVisibility(
                        message.id,
                        occurrence.entryId,
                        if (hidden) Seq3Visibility.VISIBLE else Seq3Visibility.HIDDEN,
                    ),
                )
            },
        )
        ToolbarBtn(
            label = "Move occurrence out",
            icon = Icons.AutoMirrored.Outlined.OpenInNew,
            showLabel = false,
            tooltip = "Move this occurrence out as a separate message",
            onClick = {
                state.seq3Sessions.applyCommand(session.id, Seq3Command.MoveOccurrenceOut(message.id, entryId))
                view.selectedOccurrenceIds = view.selectedOccurrenceIds - key
                view.selectedOccurrenceMessageId = null
                view.selectedOccurrenceEntryId = null
                runCatching { view.focusRequester.requestFocus() }
            },
            contentPadding = PaddingValues(0.dp),
            modifier = Modifier.size(SEQ3_ACTION_BADGE_SIZE),
            shape = CORNER_SM,
        )
    }
}

/** Item 11's original fix made this a PURE visual indicator with no hit target of its own, because
 *  its `Modifier.clickable` (hardcoded `additive = true`, no shift/⌘ awareness) fired ALONGSIDE the
 *  row's own modifier-aware `pointerInput` Press handler on every click, double-processing it: Press
 *  selected only this row first via the row handler, then the checkbox's own Release-driven toggle
 *  saw the id already selected and (being an unconditional toggle) removed it — so a checkbox click
 *  always emptied the selection instead of independently toggling it.
 *
 *  Phase-5 post-ship fix (item 11 continued): the checkbox regains its own [onClick] — always
 *  additive, matching the confirmed "checkbox click is always additive, no ⌘ needed" decision — now
 *  that [Seq3QueueRow]'s own `pointerInput` checks `event.changes.any { it.isConsumed }` before
 *  acting (see that modifier's own comment), so `clickable`'s consumption of the Press change is
 *  what stops the double-fire this time, instead of removing the checkbox's click handling
 *  entirely. */
@Composable
private fun Seq3RowCheckbox(checked: Boolean, onClick: () -> Unit) {
    val tc = tc()
    Box(
        Modifier.size(16.dp)
            .clip(RoundedCornerShape(3.dp))
            .clickable(onClick = onClick)
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
        val count = collapsedCount ?: message.occurrences.count { it.visibility == Seq3Visibility.VISIBLE }
        if (count > 1) AppText("×$count", color = tc.ts, fontSize = 10.sp)
    }
}

@Composable
private fun Seq3RowEndpointsLine(
    state: AppState,
    session: Seq3WorkspaceSession,
    message: Seq3Message,
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
            Seq3DropdownButton(
                label = "set target", labelColor = tc.warn, fillColor = tc.warnBg, alwaysFilled = true, menuWidth = 150.dp,
            ) { close ->
                document.lifelines.sortedBy { it.ordinal }.forEach { lifeline ->
                    Seq3DropdownMenuItem(lifeline.name) {
                        state.seq3Sessions.applyCommand(session.id, Seq3Command.Bulk(setOf(message.id), Seq3BulkAction.SetTo(lifeline.id)))
                        close()
                    }
                }
            }
        }
    }
}

@Composable
private fun Seq3MessageControlsLine(
    state: AppState,
    session: Seq3WorkspaceSession,
    message: Seq3Message,
    pinnable: Set<Seq3PinDirection>,
    hidden: Boolean,
    mergeBackTarget: Seq3Message?,
) {
    val tc = tc()
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(4.dp), verticalAlignment = Alignment.CenterVertically) {
        // Keep the hide action first on this dedicated row, matching the compact action order in
        // the reference and making parent hide semantics obvious before kind/pin controls.
        Seq3VisibilityButton(state, session, message)
        Seq3MessageKindPicker(state, session, message, fixedHeight = SEQ3_ACTION_BADGE_SIZE)
        if (pinnable.isNotEmpty()) {
            Seq3PinControls(state, session, message, pinnable)
        }
        if (mergeBackTarget != null) {
            ToolbarBtn(
                label = "Move back",
                tooltip = "Return this moved-out occurrence to its original message group",
                contentPadding = PaddingValues(horizontal = 4.dp, vertical = 3.dp),
                shape = CORNER_SM,
                onClick = {
                    state.seq3Sessions.applyCommand(
                        session.id,
                        Seq3Command.MoveOccurrenceBack(message.id),
                    )
                },
            )
        }
        Spacer(Modifier.weight(1f))
        // Item 15: "revert to generated" for ONE edited row — only ever shown once this message has
        // actually drifted from what the engine would produce (state derives EDITED from `authoring`,
        // Seq3Message's own doc), so an untouched AUTO/NEEDS_TARGET row never offers it.
        if (message.state == Seq3State.EDITED) {
            AppButton(
                "Revert",
                onClick = { state.seq3Sessions.revertMessage(session.id, message.id) },
                variant = ButtonVariant.Ghost,
                textColor = tc.ac,
                horizontalPadding = 0.dp,
                modifier = Modifier.pointerHoverIcon(
                    PointerIcon(AwtCursor.getPredefinedCursor(AwtCursor.HAND_CURSOR)),
                    overrideDescendants = true,
                ),
            )
            Spacer(Modifier.width(4.dp))
        }
        Seq3StateWord(message, hidden)
    }
}

/** Per-row message kind control. Notes remain supported for custom-message creation, but are not
 * offered as an option in the compact queue menu. */
@Composable
private fun Seq3MessageKindPicker(
    state: AppState,
    session: Seq3WorkspaceSession,
    message: Seq3Message,
    fixedHeight: androidx.compose.ui.unit.Dp? = null,
) {
    val tc = tc()
    Seq3DropdownButton(
        label = message.kind.name.lowercase(),
        labelColor = tc.ts,
        fillColor = tc.p2,
        menuWidth = 120.dp,
        fixedHeight = fixedHeight,
    ) { close ->
        MESSAGE_KIND_OPTIONS.forEach { kind ->
            Seq3DropdownMenuItem(kind.name.lowercase(), active = kind == message.kind) {
                state.seq3Sessions.applyCommand(
                    session.id,
                    Seq3Command.Bulk(setOf(message.id), Seq3BulkAction.SetKind(kind)),
                )
                close()
            }
        }
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
            ToolbarBtn(
                label = "▲",
                contentPadding = PaddingValues(horizontal = 2.dp, vertical = 2.dp),
                shape = CORNER_SM,
                onClick = {
                    state.seq3Sessions.applyCommand(session.id, Seq3Command.NudgePin(message.id, Seq3PinDirection.UP))
                },
            )
        }
        if (Seq3PinDirection.DOWN in pinnable) {
            ToolbarBtn(
                label = "▼",
                contentPadding = PaddingValues(horizontal = 2.dp, vertical = 2.dp),
                shape = CORNER_SM,
                onClick = {
                    state.seq3Sessions.applyCommand(session.id, Seq3Command.NudgePin(message.id, Seq3PinDirection.DOWN))
                },
            )
        }
        if (message.orderPin != null) {
            ToolbarBtn(
                label = "pinned",
                active = true,
                contentPadding = PaddingValues(horizontal = 4.dp, vertical = 2.dp),
                shape = CORNER_SM,
                onClick = { state.seq3Sessions.applyCommand(session.id, Seq3Command.ClearPin(message.id)) },
            )
        } else {
            AppText("same ms", color = tc.td, fontSize = 9.sp)
        }
    }
}

@Composable
private fun Seq3VisibilityButton(state: AppState, session: Seq3WorkspaceSession, message: Seq3Message) {
    val hidden = message.visibility == Seq3Visibility.HIDDEN
    ToolbarBtn(
        label = if (hidden) "Show message" else "Hide message",
        icon = if (hidden) Icons.Outlined.Visibility else Icons.Outlined.VisibilityOff,
        showLabel = false,
        tooltip = if (hidden) "Show this message and all of its occurrences" else "Hide this message and all of its occurrences",
        active = hidden,
        contentPadding = PaddingValues(0.dp),
        modifier = Modifier.size(SEQ3_ACTION_BADGE_SIZE),
        shape = CORNER_SM,
        onClick = {
            state.seq3Sessions.applyCommand(
                session.id,
                Seq3Command.Bulk(setOf(message.id), if (hidden) Seq3BulkAction.Show else Seq3BulkAction.Hide),
            )
        },
    )
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
private fun Seq3OccurrenceSelectionActionBar(
    state: AppState,
    session: Seq3WorkspaceSession,
    view: Seq3ViewState,
    document: Seq3Document,
) {
    val tc = tc()
    val selected = document.messages.flatMap { message ->
        message.occurrences.mapNotNull { occurrence ->
            val key = occurrenceSelectionKey(message.id, occurrence.entryId)
            if (key in view.selectedOccurrenceIds) Seq3OccurrenceRef(message.id, occurrence.entryId) else null
        }
    }
    val canMoveOut = selected.isNotEmpty() && selected.groupBy { it.messageId }.all { (messageId, refs) ->
        document.messages.firstOrNull { it.id == messageId }?.let { refs.size < it.occurrences.size } == true
    }
    val selectedMessageIds = selected.map { it.messageId }.toSet()

    fun clearSelection() {
        view.selectedOccurrenceIds = emptySet()
        view.selectedOccurrenceMessageId = null
        view.selectedOccurrenceEntryId = null
    }

    Row(
        Modifier.fillMaxWidth().background(tc.tx)
            .padding(horizontal = 12.dp, vertical = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        AppText("${selected.size} selected", color = tc.bg, fontSize = 11.sp, fontWeight = FontWeight.SemiBold)
        Seq3ActionBarButton(
            "Move out",
            enabled = canMoveOut,
            tooltip = if (canMoveOut) {
                "Move the selected occurrences into standalone messages"
            } else {
                "Keep at least one occurrence in each message group"
            },
        ) {
            if (canMoveOut && state.seq3Sessions.applyCommand(session.id, Seq3Command.MoveOccurrencesOut(selected))) {
                clearSelection()
            }
        }
        Seq3ActionBarButton("Note") {
            seq3AddNote(state, session, view, document, selectedMessageIds)
        }
        if (seq3CanGroupSelection(document, view, selectedMessageIds)) {
            Seq3DropdownButton(label = "Group") { close ->
                Seq3FragmentKind.entries.forEach { kind ->
                    Seq3DropdownMenuItem(kind.name.lowercase()) {
                        seq3GroupMessages(state, session, view, selectedMessageIds, kind)
                        close()
                    }
                }
            }
        }
        Spacer(Modifier.weight(1f))
        Seq3ActionBarButton("Esc", active = false, onClick = ::clearSelection)
    }
}

@Composable
private fun Seq3SelectionActionBar(state: AppState, session: Seq3WorkspaceSession, view: Seq3ViewState, document: Seq3Document) {
    val tc = tc()
    val selectedIds = view.selection.selectedIds.filterTo(linkedSetOf()) { id -> document.messages.any { it.id == id } }
    val selectedRowCount = if (view.selectedCanvasRows.isNotEmpty()) {
        view.selectedCanvasRows.size
    } else {
        selectedIds.size
    }

    fun clearSelection() {
        seq3ClearSelection(view)
    }

    fun dispatch(action: Seq3BulkAction): Boolean =
        state.seq3Sessions.applyCommand(session.id, Seq3Command.Bulk(selectedIds, action))
    Row(
        Modifier.fillMaxWidth().background(tc.tx)
            .padding(horizontal = 12.dp, vertical = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        AppText("$selectedRowCount selected", color = tc.bg, fontSize = 11.sp, fontWeight = FontWeight.SemiBold)
        Seq3DropdownButton(label = "Set from", labelColor = tc.bg) { close ->
            document.lifelines.sortedBy { it.ordinal }.forEach { lifeline ->
                Seq3DropdownMenuItem(lifeline.name) { dispatch(Seq3BulkAction.SetFrom(lifeline.id)); close() }
            }
        }
        Seq3DropdownButton(label = "Set to", labelColor = tc.bg) { close ->
            document.lifelines.sortedBy { it.ordinal }.forEach { lifeline ->
                Seq3DropdownMenuItem(lifeline.name) { dispatch(Seq3BulkAction.SetTo(lifeline.id)); close() }
            }
        }
        if (seq3CanGroupSelection(document, view, selectedIds)) {
            Seq3DropdownButton(label = "Group", labelColor = tc.bg) { close ->
                Seq3FragmentKind.entries.forEach { kind ->
                    Seq3DropdownMenuItem(kind.name.lowercase()) {
                        seq3GroupMessages(state, session, view, selectedIds, kind)
                        close()
                    }
                }
            }
        }
        Seq3ActionBarButton("Hide") {
            dispatch(Seq3BulkAction.Hide)
            clearSelection()
        }
        Seq3ActionBarButton("Note") {
            val note = Seq3Note("note-${UUID.randomUUID()}", "Note", selectedIds.toList())
            dispatch(Seq3BulkAction.Note(note))
        }
        Spacer(Modifier.weight(1f))
        Seq3ActionBarButton("Esc", active = false, onClick = ::clearSelection)
    }
}

@Composable
private fun Seq3ActionBarButton(
    label: String,
    active: Boolean = true,
    enabled: Boolean = true,
    tooltip: String? = null,
    onClick: () -> Unit,
) {
    ToolbarBtn(
        label = label,
        active = active,
        enabled = enabled,
        tooltip = tooltip,
        contentPadding = PaddingValues(horizontal = 4.dp, vertical = 4.dp),
        shape = CORNER_SM,
        modifier = Modifier.pointerHoverIcon(
            PointerIcon(AwtCursor.getPredefinedCursor(AwtCursor.HAND_CURSOR)),
            overrideDescendants = true,
        ),
        onClick = onClick,
    )
}

/** Finds the exact group recorded by [MoveOccurrenceOut]. The action stays hidden when that group
 * is no longer present or its endpoints/kind have become incompatible. */
private fun seq3MergeBackTarget(document: Seq3Document, message: Seq3Message): Seq3Message? {
    if (message.authoring != Seq3Authoring.EDITED || message.occurrences.size != 1) return null
    val targetId = message.movedOutFromMessageId ?: return null
    return document.messages.singleOrNull { candidate ->
        candidate.id == targetId &&
            candidate.occurrences.isNotEmpty() &&
            candidate.fromLifelineId == message.fromLifelineId &&
            candidate.toLifelineId == message.toLifelineId &&
            candidate.kind == message.kind
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
    message.occurrences.count { it.visibility == Seq3Visibility.VISIBLE }.let { visibleCount ->
        if (message.repeat == Seq3Repeat.COLLAPSE_ABOVE && visibleCount > message.repeatThreshold) visibleCount else null
    }

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
