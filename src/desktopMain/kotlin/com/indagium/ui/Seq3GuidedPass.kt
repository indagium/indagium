package com.indagium.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.indagium.diagram3.Seq3Command
import com.indagium.diagram3.Seq3Document
import com.indagium.diagram3.Seq3GuidedPassState
import com.indagium.diagram3.Seq3Lifeline
import com.indagium.diagram3.Seq3Message
import com.indagium.diagram3.advanceSeq3GuidedPass
import com.indagium.diagram3.beginSeq3GuidedPass
import com.indagium.diagram3.seq3GuidedContext
import com.indagium.diagram3.seq3GuidedCurrentMessage
import com.indagium.diagram3.suggestSeq3Target
import com.indagium.model.LogEntry

// ── "Fix these" — the guided pass (design spec §05) ───────────────────────────────────────────
//
// A MODE, not a dialog. The spec is explicit about why: "A dialog per row costs an open and a
// close each time. A mode keeps the position, the progress, and the keyboard, so the sixth fix is
// as fast as the first." So this renders IN PLACE over the panel+canvas area of Seq3Workspace —
// there is deliberately no `Dialog(...)` anywhere in this file.
//
// All state transitions come from `diagram3.Seq3Guided` (pure, tested); every mutation routes
// through `Seq3Session.applyCommand` so ⌘Z stays uniform, exactly like Seq3QueuePanel.

/** The spec's `1`–`9` badges: only the first nine lifelines get a number key, the rest stay
 *  click-only. Nine is the spec's own ceiling (`1–9 Set target` in its §09 keyboard table). */
internal const val SEQ3_MAX_KEYED_LIFELINES = 9

private val SHEET_MAX_WIDTH = 720.dp

/**
 * Starts a pass, returning null (and entering nothing) when there is nothing to fix — the amber
 * banner that launches this disappears at zero, so an empty pass is not a reachable state.
 */
internal fun startSeq3GuidedPass(document: Seq3Document): Seq3GuidedPassState? = beginSeq3GuidedPass(document)

/**
 * The lifeline a number key selects, or null when [oneBasedKey] is past the end of the list. Split
 * out (rather than inlined into the key handler) so [Seq3GuidedPassTest] can assert the mapping
 * without a composition — the same reason `Seq3Canvas`'s hit-testing is a plain function.
 */
internal fun seq3GuidedLifelineForKey(document: Seq3Document, oneBasedKey: Int): Seq3Lifeline? =
    document.lifelines.take(SEQ3_MAX_KEYED_LIFELINES).getOrNull(oneBasedKey - 1)

@Composable
internal fun Seq3GuidedPass(
    state: AppState,
    session: Seq3WorkspaceSession,
    view: Seq3ViewState,
    modifier: Modifier,
) {
    val tc = tc()
    val pass = view.guidedPass ?: return
    val document = session.document
    val message = seq3GuidedCurrentMessage(document, pass)
    if (message == null) {
        // The current row stopped being needs-target underneath us (resolved from the canvas, or
        // undone). Re-derive rather than showing an empty card; advance drops any id the document
        // no longer reports as unresolved.
        view.guidedPass = advanceSeq3GuidedPass(document, pass)
        return
    }
    val entries = session.sourceTabId?.let(state::tab)?.logData.orEmpty()
    val suggestion = remember(message.id, document, entries) { suggestSeq3Target(message, document, entries) }

    Box(modifier.background(tc.bg), contentAlignment = Alignment.TopCenter) {
        Column(
            Modifier.widthIn(max = SHEET_MAX_WIDTH).fillMaxWidth().padding(20.dp)
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            Seq3GuidedHeader(pass) { view.guidedPass = null }
            Seq3GuidedEvidence(message)
            Seq3GuidedSurroundingLines(message, entries)
            Seq3GuidedTargetGrid(state, session, view, document, message, suggestion)
        }
    }
}

@Composable
private fun Seq3GuidedHeader(pass: Seq3GuidedPassState, onExit: () -> Unit) {
    val tc = tc()
    val done = pass.completedCount
    val total = pass.totalAtStart
    Row(
        Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        AppText("Set targets", color = tc.tx, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
        AppText("$done / $total", color = tc.ts, fontSize = 12.sp, fontFamily = FontFamily.Monospace)
        Box(Modifier.weight(1f).height(3.dp).clip(RoundedCornerShape(2.dp)).background(tc.p2)) {
            val fraction = if (total == 0) 0f else done.toFloat() / total
            Box(Modifier.fillMaxWidth(fraction.coerceIn(0f, 1f)).height(3.dp).background(tc.ac))
        }
        AppText(
            "Esc to exit", color = tc.ts, fontSize = 11.sp,
            modifier = Modifier.clip(CORNER_SM).clickable(onClick = onExit).padding(horizontal = 6.dp, vertical = 2.dp),
        )
    }
}

@Composable
private fun Seq3GuidedEvidence(message: Seq3Message) {
    val tc = tc()
    val first = message.occurrences.firstOrNull()
    Column(verticalArrangement = Arrangement.spacedBy(5.dp)) {
        val parts = buildList {
            // `entryId` IS the line identity in this codebase — LogParser numbers entries from 1
            // per file and every jump-to-log path (AppState.navigateToLogLine, the Inspector's
            // evidence list) keys off it directly. There is no separate `line` field on LogEntry.
            first?.entryId?.let { add("line $it") }
            first?.rawTimestamp?.takeIf { it.isNotBlank() }?.let(::add)
            add("tag ${message.match.tag}")
            add("×${message.occurrences.size} occurrences")
        }
        AppText(parts.joinToString(" · "), color = tc.td, fontSize = 11.sp, fontFamily = MONO)
        // Same accent-highlighted `{token}` rendering the queue row uses (spec §05 shows the
        // pattern here at a larger size), reusing Seq3QueuePanel's own splitter rather than a
        // second regex that could drift from it.
        Row(Modifier.fillMaxWidth()) {
            seq3TemplateSegments(message.match.template).forEach { segment ->
                when (segment) {
                    is Seq3TemplateSegment.Literal -> if (segment.text.isNotEmpty()) {
                        AppText(segment.text, color = tc.tx, fontSize = 15.sp, fontFamily = MONO, maxLines = 1)
                    }
                    is Seq3TemplateSegment.Token -> AppText(
                        "{${segment.name}}", color = tc.ac, fontSize = 15.sp, fontFamily = MONO,
                        fontWeight = FontWeight.SemiBold, maxLines = 1,
                    )
                }
            }
        }
    }
}

/**
 * The "Surrounding lines" box (spec §05). The spec calls this the thing that makes the flow usable
 * at all: "You cannot judge a target without the neighbouring lines. Showing three lines of log
 * here removes the trip back to the log tab." The current line is bolded; neighbours are dimmed.
 */
@Composable
private fun Seq3GuidedSurroundingLines(message: Seq3Message, entries: List<LogEntry>) {
    val tc = tc()
    val context = remember(message.id, entries) { seq3GuidedContext(message, entries) }
    if (context.current == null) return
    Column(
        Modifier.fillMaxWidth().background(tc.p2, CORNER_SM).border(1.dp, tc.br, CORNER_SM).padding(11.dp),
        verticalArrangement = Arrangement.spacedBy(3.dp),
    ) {
        AppText("SURROUNDING LINES", color = tc.td, fontSize = 10.sp, fontWeight = FontWeight.SemiBold)
        listOf(context.previous to false, context.current to true, context.next to false).forEach { (entry, isCurrent) ->
            if (entry == null) return@forEach
            AppText(
                "${entry.id}  ${entry.tag}  ${entry.msg}",
                color = if (isCurrent) tc.tx else tc.ts,
                fontSize = 11.sp,
                fontFamily = FontFamily.Monospace,
                fontWeight = if (isCurrent) FontWeight.Medium else FontWeight.Normal,
                maxLines = 1,
                overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
                modifier = Modifier.fillMaxWidth(),
            )
        }
    }
}

@Composable
private fun Seq3GuidedTargetGrid(
    state: AppState,
    session: Seq3WorkspaceSession,
    view: Seq3ViewState,
    document: Seq3Document,
    message: Seq3Message,
    suggestion: Seq3Lifeline?,
) {
    val tc = tc()
    // Pre-selected, never auto-applied (spec §05): the suggestion only seeds which card is
    // highlighted. Nothing is written until the footer button / ⏎ / a number key fires.
    var chosenId by remember(message.id) { mutableStateOf(suggestion?.id) }
    var applyToAll by remember(message.id) { mutableStateOf(true) }
    val candidates = document.lifelines.filter { it.id != message.fromLifelineId }

    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        AppText("Target lifeline", color = tc.tx, fontSize = 12.sp)
        // A plain wrapping Row rather than LazyVerticalGrid: at most a handful of lifelines, and
        // this pane already scrolls as a whole.
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            candidates.chunked(2).forEach { rowItems ->
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    rowItems.forEach { lifeline ->
                        val keyIndex = document.lifelines.indexOfFirst { it.id == lifeline.id } + 1
                        Seq3GuidedLifelineCard(
                            lifeline = lifeline,
                            keyNumber = keyIndex.takeIf { it in 1..SEQ3_MAX_KEYED_LIFELINES },
                            selected = lifeline.id == chosenId,
                            suggested = lifeline.id == suggestion?.id,
                            modifier = Modifier.weight(1f),
                        ) { chosenId = lifeline.id }
                    }
                    if (rowItems.size == 1) Spacer(Modifier.weight(1f))
                }
            }
        }
        Seq3GuidedSecondaryActions(state, session, view, document, message)
        Seq3GuidedFooter(
            occurrenceCount = message.occurrences.size,
            applyToAll = applyToAll,
            onToggleApplyToAll = { applyToAll = !applyToAll },
            enabled = chosenId != null,
        ) {
            val target = chosenId ?: return@Seq3GuidedFooter
            applySeq3GuidedChoice(state, session, view, Seq3Command.GuidedTarget(message.id, target, applyToAll))
        }
    }
}

@Composable
private fun Seq3GuidedLifelineCard(
    lifeline: Seq3Lifeline,
    keyNumber: Int?,
    selected: Boolean,
    suggested: Boolean,
    modifier: Modifier,
    onClick: () -> Unit,
) {
    val tc = tc()
    HoverBox(
        modifier = modifier.clip(RoundedCornerShape(8.dp))
            .background(if (selected) tc.abg else tc.p, RoundedCornerShape(8.dp))
            .border(if (selected) 1.5.dp else 1.dp, if (selected) tc.ac else tc.br, RoundedCornerShape(8.dp))
            .padding(horizontal = 12.dp, vertical = 10.dp),
        onClick = onClick,
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(9.dp)) {
            Box(
                Modifier.width(18.dp).height(18.dp).clip(CORNER_SM)
                    .background(if (selected) tc.ac else tc.p2, CORNER_SM),
                contentAlignment = Alignment.Center,
            ) {
                AppText(
                    keyNumber?.toString() ?: "·",
                    color = if (selected) tc.p else tc.ts,
                    fontSize = 11.sp, fontFamily = FontFamily.Monospace,
                )
            }
            AppText(
                lifeline.name, color = tc.tx, fontSize = 13.sp,
                fontWeight = if (selected) FontWeight.Medium else FontWeight.Normal,
                maxLines = 1, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f, fill = false),
            )
            if (suggested) {
                Spacer(Modifier.weight(1f))
                AppText("suggested", color = tc.ac, fontSize = 11.sp)
            }
        }
    }
}

@Composable
private fun Seq3GuidedSecondaryActions(
    state: AppState,
    session: Seq3WorkspaceSession,
    view: Seq3ViewState,
    document: Seq3Document,
    message: Seq3Message,
) {
    val tc = tc()
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Seq3GuidedSecondaryButton("＋ New lifeline") {
            val name = newSeq3LifelineName(document)
            val lifeline = Seq3Lifeline(
                id = "seq3-lifeline-${document.lifelines.size + 1}-$name",
                name = name, tagIds = emptySet(), ordinal = document.lifelines.size,
            )
            applySeq3GuidedChoice(state, session, view, Seq3Command.GuidedNewLifeline(message.id, lifeline))
        }
        Seq3GuidedSecondaryButton("Make it a self-call") {
            applySeq3GuidedChoice(state, session, view, Seq3Command.GuidedSelfCall(message.id))
        }
        Spacer(Modifier.weight(1f))
        AppText(
            "Skip · S", color = tc.ts, fontSize = 12.sp,
            modifier = Modifier.clip(CORNER_SM).clickable { skipSeq3Guided(session, view) }.padding(horizontal = 6.dp, vertical = 3.dp),
        )
    }
}

@Composable
private fun Seq3GuidedSecondaryButton(label: String, onClick: () -> Unit) {
    val tc = tc()
    HoverBox(
        modifier = Modifier.clip(RoundedCornerShape(7.dp)).background(tc.p, RoundedCornerShape(7.dp))
            .border(1.dp, tc.br, RoundedCornerShape(7.dp)).padding(horizontal = 12.dp, vertical = 7.dp),
        onClick = onClick,
    ) { AppText(label, color = tc.tx, fontSize = 12.sp) }
}

@Composable
private fun Seq3GuidedFooter(
    occurrenceCount: Int,
    applyToAll: Boolean,
    onToggleApplyToAll: () -> Unit,
    enabled: Boolean,
    onConfirm: () -> Unit,
) {
    val tc = tc()
    Row(
        Modifier.fillMaxWidth().padding(top = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Row(
            Modifier.clip(CORNER_SM).clickable(onClick = onToggleApplyToAll).padding(vertical = 2.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Box(
                Modifier.width(13.dp).height(13.dp).clip(CORNER_SM)
                    .background(if (applyToAll) tc.ac else Color.Transparent, CORNER_SM)
                    .border(1.5.dp, if (applyToAll) tc.ac else tc.br, CORNER_SM),
                contentAlignment = Alignment.Center,
            ) { if (applyToAll) AppText("✓", color = tc.p, fontSize = 9.sp) }
            AppText("Apply to all ×$occurrenceCount occurrences", color = tc.tx, fontSize = 12.sp)
        }
        Spacer(Modifier.weight(1f))
        HoverBox(
            modifier = Modifier.clip(RoundedCornerShape(7.dp))
                .background(if (enabled) tc.ac else tc.p2, RoundedCornerShape(7.dp))
                .padding(horizontal = 16.dp, vertical = 8.dp),
            hoverEnabled = enabled,
            onClick = { if (enabled) onConfirm() },
        ) {
            AppText(
                "Set target & next ⏎",
                color = if (enabled) tc.p else tc.td,
                fontSize = 13.sp, fontWeight = FontWeight.Medium,
            )
        }
    }
}

// ── Transitions (shared with the keyboard handler in Seq3Workspace) ───────────────────────────

/** Applies one guided choice and advances. Routes through `applyCommand` like every other v3
 *  mutation, so a guided fix is one ⌘Z step. Advancing re-reads the POST-command document, which
 *  is what drops the just-resolved id from the remaining queue. */
internal fun applySeq3GuidedChoice(
    state: AppState,
    session: Seq3WorkspaceSession,
    view: Seq3ViewState,
    command: Seq3Command,
) {
    val pass = view.guidedPass ?: return
    state.seq3Sessions.applyCommand(session.id, command)
    val updated = state.seq3Sessions.sessions.firstOrNull { it.id == session.id }?.document ?: return
    view.guidedPass = advanceSeq3GuidedPass(updated, pass)
}

/** "Skip · S" — drops the current row from this pass without editing it. A skipped row is never
 *  revisited during the pass (`advanceSeq3GuidedPass`'s own rule) but is still needs-target, so it
 *  reappears in the banner count and in a later pass. */
internal fun skipSeq3Guided(session: Seq3WorkspaceSession, view: Seq3ViewState) {
    val pass = view.guidedPass ?: return
    view.guidedPass = advanceSeq3GuidedPass(session.document, pass)
}

/** A distinct placeholder name for "＋ New lifeline", so two presses never collide on one id. */
internal fun newSeq3LifelineName(document: Seq3Document): String {
    val existing = document.lifelines.mapTo(HashSet()) { it.name }
    var n = document.lifelines.size + 1
    while ("Lifeline $n" in existing) n++
    return "Lifeline $n"
}
