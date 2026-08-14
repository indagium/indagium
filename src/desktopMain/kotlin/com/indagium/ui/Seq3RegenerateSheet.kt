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
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.indagium.diagram3.Seq3Range
import com.indagium.diagram3.Seq3RegenChangeKind
import com.indagium.diagram3.Seq3RegenDecision
import com.indagium.diagram3.Seq3RegenReview
import com.indagium.diagram3.Seq3RegenRow
import com.indagium.diagram3.acceptAllSeq3Regen
import com.indagium.diagram3.rejectAllSeq3Regen
import com.indagium.diagram3.unlockSeq3RegenRow
import com.indagium.diagram3.withSeq3RegenDecision

// ── Regenerate is a reviewed proposal (design spec §08) ────────────────────────────────────────
//
// "The current panel promises the draft never changes by itself, then offers a button that
// replaces it wholesale. Make regeneration a proposal you review, with your edits protected by
// default." — so nothing here ever writes the document directly: per-row decisions mutate only
// `Seq3WorkspaceSession.pendingRegenReview` (through Seq3Session.updateRegenReview), and the
// single "Apply N changes" press routes one Seq3Command.ApplyRegeneration through applyCommand,
// making the whole regeneration ONE ⌘Z step (spec: "Apply is a single undoable transaction, not 15").
//
// The scope controls live INSIDE this sheet, not at the top of the panel — spec §08's own
// reasoning: "Selection / whole view / time, the trace checkbox, and same-thread handoffs are
// inputs to THIS action, not permanent furniture at the top of the panel."

private val SHEET_WIDTH = 820.dp
private val ROW_LIST_MAX_HEIGHT = 360.dp

@Composable
internal fun Seq3RegenerateSheet(state: AppState, session: Seq3WorkspaceSession, view: Seq3ViewState) {
    val tc = tc()
    // usePlatformDefaultWidth = false is REQUIRED: the default silently clamps dialog content to a
    // ported-from-Android ~580dp preferred width, which would make every width below a no-op.
    // Same reason SettingsDialog passes it (see CLAUDE.md's Compose Desktop gotchas).
    Dialog(
        onDismissRequest = { closeSeq3RegenerateSheet(state, session, view) },
        properties = DialogProperties(usePlatformDefaultWidth = false),
    ) {
        Column(
            Modifier.width(SHEET_WIDTH).clip(RoundedCornerShape(12.dp))
                .background(tc.p, RoundedCornerShape(12.dp))
                .border(1.dp, tc.br, RoundedCornerShape(12.dp)),
        ) {
            val review = session.pendingRegenReview
            Seq3RegenHeader(review, session.regenBuilding)
            Seq3RegenScopeControls(state, session)
            if (review != null) {
                Seq3RegenRowList(state, session, review)
                Seq3RegenFooter(state, session, view, review)
            } else {
                Seq3RegenEmptyState(state, session, view, session.regenBuilding)
            }
        }
    }
}

@Composable
private fun Seq3RegenHeader(review: Seq3RegenReview?, building: Boolean) {
    val tc = tc()
    Column(
        Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 16.dp),
        verticalArrangement = Arrangement.spacedBy(9.dp),
    ) {
        AppText("Review regenerated draft", color = tc.tx, fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
        when {
            building -> AppText("Building review…", color = tc.ts, fontSize = 12.sp)
            review == null -> AppText(
                "Pick a scope, then build a review. Your edited messages stay locked.",
                color = tc.ts, fontSize = 12.sp,
            )
            else -> {
                val s = review.summary
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Seq3RegenChip("${s.newCount} new", tc.ok)
                    Seq3RegenChip("${s.changedCount} changed", tc.warn)
                    Seq3RegenChip("${s.removedCount} no longer in the log", DANGER_RED)
                    Seq3RegenChip("${s.editsKeptCount} of your edits kept", tc.ac)
                }
            }
        }
    }
    Box(Modifier.fillMaxWidth().height(1.dp).background(tc.br))
}

@Composable
private fun Seq3RegenChip(label: String, accent: Color) {
    Box(
        Modifier.clip(RoundedCornerShape(5.dp)).background(accent.copy(alpha = .16f), RoundedCornerShape(5.dp))
            .padding(horizontal = 9.dp, vertical = 3.dp),
    ) { AppText(label, color = accent, fontSize = 11.sp) }
}

/**
 * Spec §08's "The scope controls belong here": selection / whole view / time range plus the
 * same-thread-handoff and correlation-token inputs to `Seq3GenerateOptions`. Changing one does not
 * regenerate anything by itself — it only seeds the "Build review" press below.
 */
@Composable
private fun Seq3RegenScopeControls(state: AppState, session: Seq3WorkspaceSession) {
    val tc = tc()
    Column(
        Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 14.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            AppText("Scope", color = tc.tx, fontSize = 12.sp, fontWeight = FontWeight.Medium)
            val scopeLabel = when (session.range) {
                is Seq3Range.VisibleView -> "Whole view"
                is Seq3Range.Ids -> "Selection"
                is Seq3Range.Time -> "Time range"
            }
            Seq3DropdownButton(scopeLabel) { close ->
                Seq3DropdownMenuItem("Whole view", active = session.range is Seq3Range.VisibleView) {
                    state.seq3Sessions.updateScope(session.id, Seq3Range.VisibleView)
                    close()
                }
                val tab = session.sourceTabId?.let(state::tab)
                val selected = tab?.selected.orEmpty()
                Seq3DropdownMenuItem("Selection (${selected.size} rows)", active = session.range is Seq3Range.Ids) {
                    if (selected.isNotEmpty()) {
                        state.seq3Sessions.updateScope(session.id, Seq3Range.Ids(selected.min(), selected.max(), selected))
                    }
                    close()
                }
            }
        }
        Seq3RegenToggle(
            "Same-thread handoffs",
            "Infer a target from the next entry on the same pid+tid.",
            session.generateOptions.threadHandoffEnabled,
        ) { state.seq3Sessions.updateGenerateOptions(session.id) { it.copy(threadHandoffEnabled = !it.threadHandoffEnabled) } }
        Seq3RegenToggle(
            "Correlation tokens",
            "Infer a target from a shared id/uuid between adjacent entries.",
            session.generateOptions.correlationTokenEnabled,
        ) { state.seq3Sessions.updateGenerateOptions(session.id) { it.copy(correlationTokenEnabled = !it.correlationTokenEnabled) } }
    }
    Box(Modifier.fillMaxWidth().height(1.dp).background(tc.br))
}

@Composable
private fun Seq3RegenToggle(label: String, hint: String, checked: Boolean, onToggle: () -> Unit) {
    val tc = tc()
    Row(
        Modifier.fillMaxWidth().clip(CORNER_SM).clickable(onClick = onToggle).padding(vertical = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(9.dp),
    ) {
        Box(
            Modifier.width(13.dp).height(13.dp).clip(CORNER_SM)
                .background(if (checked) tc.ac else Color.Transparent, CORNER_SM)
                .border(1.5.dp, if (checked) tc.ac else tc.br, CORNER_SM),
            contentAlignment = Alignment.Center,
        ) { if (checked) AppText("✓", color = tc.p, fontSize = 9.sp) }
        AppText(label, color = tc.tx, fontSize = 12.sp)
        AppText(hint, color = tc.td, fontSize = 11.sp, maxLines = 1)
    }
}

@Composable
private fun Seq3RegenRowList(state: AppState, session: Seq3WorkspaceSession, review: Seq3RegenReview) {
    // UNCHANGED rows exist only so `applySeq3Regeneration` can rebuild the full message list from
    // rows alone (see Seq3Regeneration.kt) — they are not decisions, so they are never shown.
    val visible = remember(review) { review.rows.filter { it.kind != Seq3RegenChangeKind.UNCHANGED } }
    LazyColumn(Modifier.fillMaxWidth().heightIn(max = ROW_LIST_MAX_HEIGHT)) {
        items(visible, key = Seq3RegenRow::id) { row -> Seq3RegenReviewRow(state, session, row) }
    }
}

@Composable
private fun Seq3RegenReviewRow(state: AppState, session: Seq3WorkspaceSession, row: Seq3RegenRow) {
    val tc = tc()
    val glyph = when (row.kind) {
        Seq3RegenChangeKind.NEW -> "＋"
        Seq3RegenChangeKind.CHANGED -> "±"
        Seq3RegenChangeKind.REMOVED -> "−"
        Seq3RegenChangeKind.EDITED_KEPT -> "🔒"
        Seq3RegenChangeKind.UNCHANGED -> "·"
    }
    val glyphColor = when (row.kind) {
        Seq3RegenChangeKind.NEW -> tc.ok
        Seq3RegenChangeKind.CHANGED -> tc.warn
        Seq3RegenChangeKind.REMOVED -> DANGER_RED
        else -> tc.ac
    }
    Column {
        Row(
            Modifier.fillMaxWidth()
                .background(if (row.kind == Seq3RegenChangeKind.EDITED_KEPT) tc.p2 else Color.Transparent)
                .padding(horizontal = 20.dp, vertical = 11.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Box(Modifier.width(15.dp), contentAlignment = Alignment.Center) {
                AppText(glyph, color = glyphColor, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
            }
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                AppText(
                    (row.fresh ?: row.current)?.match?.template.orEmpty(),
                    color = if (row.kind == Seq3RegenChangeKind.REMOVED) tc.ts else tc.tx,
                    fontSize = 12.sp, fontFamily = MONO, maxLines = 1,
                    overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
                )
                seq3RegenRowDetail(row)?.let { detail ->
                    AppText(
                        detail,
                        color = if (row.kind == Seq3RegenChangeKind.EDITED_KEPT) tc.ac else tc.ts,
                        fontSize = 11.sp, maxLines = 1,
                        overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
                    )
                }
            }
            Seq3RegenRowActions(state, session, row)
        }
        Box(Modifier.fillMaxWidth().height(1.dp).background(tc.br.copy(alpha = .5f)))
    }
}

@Composable
private fun Seq3RegenRowActions(state: AppState, session: Seq3WorkspaceSession, row: Seq3RegenRow) {
    val tc = tc()
    // An EDITED_KEPT row is locked — regeneration reports what it would have done and moves on
    // (spec §08: "Edited means locked"). Its only verb is unlock, which converts it into an
    // ordinary decidable row.
    if (row.kind == Seq3RegenChangeKind.EDITED_KEPT && !row.unlocked) {
        AppText(
            "unlock", color = tc.ts, fontSize = 11.sp,
            modifier = Modifier.clip(CORNER_SM)
                .clickable { state.seq3Sessions.updateRegenReview(session.id) { unlockSeq3RegenRow(it, row.id) } }
                .padding(horizontal = 6.dp, vertical = 3.dp),
        )
        return
    }
    val (rejectLabel, acceptLabel) = when (row.kind) {
        Seq3RegenChangeKind.NEW -> "skip" to "add"
        Seq3RegenChangeKind.REMOVED -> "keep" to "remove"
        else -> "keep mine" to "accept"
    }
    val acceptAccent = when (row.kind) {
        Seq3RegenChangeKind.NEW -> tc.ok
        Seq3RegenChangeKind.REMOVED -> DANGER_RED
        else -> tc.warn
    }
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Seq3RegenDecisionButton(rejectLabel, active = row.decision == Seq3RegenDecision.REJECT, accent = tc.ts) {
            state.seq3Sessions.updateRegenReview(session.id) { withSeq3RegenDecision(it, row.id, Seq3RegenDecision.REJECT) }
        }
        Seq3RegenDecisionButton(acceptLabel, active = row.decision == Seq3RegenDecision.ACCEPT, accent = acceptAccent) {
            state.seq3Sessions.updateRegenReview(session.id) { withSeq3RegenDecision(it, row.id, Seq3RegenDecision.ACCEPT) }
        }
    }
}

@Composable
private fun Seq3RegenDecisionButton(label: String, active: Boolean, accent: Color, onClick: () -> Unit) {
    val tc = tc()
    HoverBox(
        modifier = Modifier.clip(RoundedCornerShape(5.dp))
            .background(if (active) accent.copy(alpha = .18f) else Color.Transparent, RoundedCornerShape(5.dp))
            .padding(horizontal = 10.dp, vertical = 3.dp),
        onClick = onClick,
    ) {
        AppText(
            label,
            color = if (active) accent else tc.ts,
            fontSize = 11.sp,
            fontWeight = if (active) FontWeight.Medium else FontWeight.Normal,
        )
    }
}

@Composable
private fun Seq3RegenFooter(state: AppState, session: Seq3WorkspaceSession, view: Seq3ViewState, review: Seq3RegenReview) {
    val tc = tc()
    val pendingCount = review.rows.count { it.decision == Seq3RegenDecision.ACCEPT }
    Box(Modifier.fillMaxWidth().height(1.dp).background(tc.br))
    Row(
        Modifier.fillMaxWidth().background(tc.p2).padding(horizontal = 20.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        AppText(
            "Accept all", color = tc.ac, fontSize = 12.sp,
            modifier = Modifier.clip(CORNER_SM)
                .clickable { state.seq3Sessions.updateRegenReview(session.id, ::acceptAllSeq3Regen) }
                .padding(horizontal = 6.dp, vertical = 3.dp),
        )
        AppText("·", color = tc.td, fontSize = 12.sp)
        AppText(
            "reject all", color = tc.ts, fontSize = 12.sp,
            modifier = Modifier.clip(CORNER_SM)
                .clickable { state.seq3Sessions.updateRegenReview(session.id, ::rejectAllSeq3Regen) }
                .padding(horizontal = 6.dp, vertical = 3.dp),
        )
        Spacer(Modifier.weight(1f))
        Seq3SheetButton("Cancel", primary = false) { closeSeq3RegenerateSheet(state, session, view) }
        Seq3SheetButton("Apply $pendingCount changes", primary = true, enabled = pendingCount > 0) {
            state.seq3Sessions.applyRegenReview(session.id)
            view.regenerateSheetOpen = false
        }
    }
}

@Composable
private fun Seq3RegenEmptyState(state: AppState, session: Seq3WorkspaceSession, view: Seq3ViewState, building: Boolean) {
    val tc = tc()
    Box(Modifier.fillMaxWidth().height(1.dp).background(tc.br))
    Row(
        Modifier.fillMaxWidth().background(tc.p2).padding(horizontal = 20.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Spacer(Modifier.weight(1f))
        Seq3SheetButton("Cancel", primary = false) { closeSeq3RegenerateSheet(state, session, view) }
        Seq3SheetButton("Build review", primary = true, enabled = !building && session.sourceTabId != null) {
            state.seq3Sessions.requestRegenReview(session.id, session.range, session.generateOptions)
        }
    }
}

@Composable
private fun Seq3SheetButton(label: String, primary: Boolean, enabled: Boolean = true, onClick: () -> Unit) {
    val tc = tc()
    HoverBox(
        modifier = Modifier.clip(RoundedCornerShape(7.dp))
            .background(if (primary && enabled) tc.ac else tc.p, RoundedCornerShape(7.dp))
            .border(1.dp, if (primary && enabled) tc.ac else tc.br, RoundedCornerShape(7.dp))
            .padding(horizontal = 14.dp, vertical = 6.dp),
        hoverEnabled = enabled,
        onClick = { if (enabled) onClick() },
    ) {
        AppText(
            label,
            color = when {
                !enabled -> tc.td
                primary -> tc.p
                else -> tc.tx
            },
            fontSize = 12.sp,
            fontWeight = if (primary) FontWeight.Medium else FontWeight.Normal,
        )
    }
}

// ── Shared helpers (also used by Seq3WorkspaceTest / the keyboard handler) ─────────────────────

/** Cancel (spec §08): drops the pending review AND closes the sheet, leaving the document alone. */
internal fun closeSeq3RegenerateSheet(state: AppState, session: Seq3WorkspaceSession, view: Seq3ViewState) {
    state.seq3Sessions.cancelRegenReview(session.id)
    view.regenerateSheetOpen = false
}

/**
 * The `target unset → UsbDeviceManager · ×6 → ×9` sub-line (spec §08), or the lock explanation for
 * an untouched edited row. Pure so [Seq3RegenerateSheetTest] can assert the wording directly.
 */
internal fun seq3RegenRowDetail(row: Seq3RegenRow): String? {
    if (row.kind == Seq3RegenChangeKind.EDITED_KEPT && !row.unlocked) {
        return "your label and target kept · regeneration skipped it"
    }
    val current = row.current ?: return null
    val fresh = row.fresh ?: return null
    val parts = buildList {
        if (current.toLifelineId != fresh.toLifelineId) {
            add("target ${current.toLifelineId ?: "unset"} → ${fresh.toLifelineId ?: "unset"}")
        }
        if (current.occurrences.size != fresh.occurrences.size) {
            add("×${current.occurrences.size} → ×${fresh.occurrences.size}")
        }
        if (current.labelTemplate != fresh.labelTemplate) add("label changed")
    }
    return parts.joinToString(" · ").takeIf { it.isNotEmpty() }
}
