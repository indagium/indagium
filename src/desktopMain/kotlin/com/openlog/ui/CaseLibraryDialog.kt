@file:OptIn(androidx.compose.ui.ExperimentalComposeUiApi::class)

package com.openlog.ui

import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.openlog.cases.CaseSummary

private val CASE_LIBRARY_DIALOG_SHAPE = RoundedCornerShape(8.dp)
private val CASE_LIBRARY_HEADER_SHAPE = RoundedCornerShape(topStart = 8.dp, topEnd = 8.dp)
private const val CASE_TITLE_ELIDE_LEN = 60

private fun elide(text: String, maxLen: Int): String =
    if (text.length <= maxLen) text else text.take(maxLen) + "…"

/**
 * Corpus-wide "Case Library" dialog over cases/CaseSearch (design decision: a dialog, not a
 * sidebar panel or a tab inside the Notes editor — the corpus is tab-independent, unlike
 * AnnotationPanel's own editor, and a sidebar/tab would fight that panel's panel-wide
 * onPreviewKeyEvent + image drop target). Bound directly to AppState, like SettingsDialog/
 * McpInfoDialog — this is a top-level dialog, not a leaf panel, so it skips the Bound* adapter
 * pattern entirely.
 *
 * Row-click (or Enter on the highlighted one) always just *previews* the note read-only — safe
 * by default. "Load into this tab" lives only inside that preview dialog, not on the row itself,
 * so the destructive action is reachable only after the user has actually looked at what they'd
 * be replacing.
 */
@Composable
internal fun CaseLibraryDialog(state: AppState, onDismiss: () -> Unit) {
    val tc = tc()
    val results = state.caseLibraryResults
    var selectedIdx by remember(results) { mutableStateOf(if (results.isEmpty()) -1 else 0) }
    val searchFr = remember { FocusRequester() }
    LaunchedEffect(Unit) { runCatching { searchFr.requestFocus() } }

    Column(
        Modifier
            .width(900.dp)
            .heightIn(max = 640.dp)
            .background(tc.p, CASE_LIBRARY_DIALOG_SHAPE)
            .border(1.dp, tc.br, CASE_LIBRARY_DIALOG_SHAPE)
            .onPreviewKeyEvent { ev ->
                if (ev.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
                when (ev.key) {
                    Key.DirectionDown -> {
                        selectedIdx = rovingMove(results.map { RovingItem(it.id) }, selectedIdx, +1)
                        true
                    }
                    Key.DirectionUp -> {
                        selectedIdx = rovingMove(results.map { RovingItem(it.id) }, selectedIdx, -1)
                        true
                    }
                    Key.Enter, Key.NumPadEnter -> {
                        results.getOrNull(selectedIdx)?.let { state.previewCase(it.id) }
                        true
                    }
                    Key.Escape -> {
                        onDismiss()
                        true
                    }
                    else -> false
                }
            },
    ) {
        Row(
            Modifier.fillMaxWidth().padding(start = 16.dp, end = 8.dp, top = 12.dp, bottom = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            AppText("Case Library", color = tc.tx, fontSize = 15.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
            if (state.caseLibrarySearching) {
                AppText("Searching…", color = tc.td, fontSize = 9.sp, modifier = Modifier.padding(end = 8.dp))
            }
            CloseButton(onClick = onDismiss)
        }
        Divider()
        Column(Modifier.fillMaxWidth().padding(16.dp)) {
            InlineField(
                value = state.caseLibraryQuery,
                onValue = { state.updateCaseLibraryQuery(it) },
                placeholder = "Search past analyses…",
                modifier = Modifier.fillMaxWidth().focusRequester(searchFr),
                onClear = { state.updateCaseLibraryQuery("") },
            )
            if (state.caseLibraryTags.isNotEmpty()) {
                Spacer(Modifier.height(6.dp))
                FlowRow(horizontalArrangement = Arrangement.spacedBy(4.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    AppText(
                        "Boosted by this tab's tags:",
                        color = tc.td,
                        fontSize = 10.sp,
                        modifier = Modifier.padding(end = 2.dp),
                    )
                    state.caseLibraryTags.take(10).forEach { tag -> CaseTagChip(tag) }
                }
            }
        }
        Box(Modifier.weight(1f).fillMaxWidth()) {
            when {
                state.caseLibraryError != null && !state.caseLibrarySearching -> CaseLibraryErrorState(
                    message = state.caseLibraryError.orEmpty(),
                    onRetry = { state.updateCaseLibraryQuery(state.caseLibraryQuery) },
                )
                state.caseLibraryIndexEmpty && !state.caseLibrarySearching -> CaseLibraryIndexPrompt(
                    indexing = state.caseLibraryIndexing,
                    onReindex = { state.reindexCaseLibrary() },
                )
                results.isEmpty() -> CaseLibraryEmptyState(searching = state.caseLibrarySearching)
                else -> CaseLibraryResultsList(
                    results = results,
                    selectedIdx = selectedIdx,
                    onSelect = { selectedIdx = it },
                    onPreview = { state.previewCase(it) },
                )
            }
        }
        Divider()
        Row(
            Modifier.fillMaxWidth().padding(12.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterHorizontally),
        ) {
            DialogActionButton("Close", active = true, onClick = onDismiss)
        }
    }

    state.caseLibraryPreview?.let { preview ->
        Dialog(onDismissRequest = { state.dismissCasePreview() }, properties = DialogProperties(usePlatformDefaultWidth = false)) {
            CasePreviewDialog(
                preview = preview,
                loading = state.caseLibraryLoadingId == preview.id,
                onLoad = {
                    state.requestLoadCase(preview.id)
                    state.dismissCasePreview()
                },
                onDismiss = { state.dismissCasePreview() },
            )
        }
    }

    state.pendingCaseLoad?.let { pending ->
        Dialog(onDismissRequest = { state.cancelLoadCase() }) {
            CaseLoadConfirmDialog(
                caseTitle = pending.caseTitle,
                replacesNotes = pending.replacesNotes,
                onConfirm = { state.confirmLoadCase() },
                onCancel = { state.cancelLoadCase() },
            )
        }
    }
}

@Composable
private fun CaseTagChip(tag: String) {
    val tc = tc()
    Box(
        Modifier.background(tc.ac.copy(.13f), CORNER_SM).border(1.dp, tc.ac.copy(.27f), CORNER_SM)
            .padding(horizontal = 6.dp, vertical = 1.dp),
    ) { AppText(tag, color = tc.ac, fontSize = 10.sp, fontFamily = MONO) }
}

@Composable
private fun CaseLibraryIndexPrompt(indexing: Boolean, onReindex: () -> Unit) {
    val tc = tc()
    Column(
        Modifier.fillMaxSize().padding(32.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        AppText("No notes indexed yet", color = tc.tx, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
        Spacer(Modifier.height(6.dp))
        AppText(
            "Build a searchable index over your previously saved analyses so past investigations show up here.",
            color = tc.td,
            fontSize = 11.sp,
            maxLines = 3,
            modifier = Modifier.widthIn(max = 380.dp),
        )
        Spacer(Modifier.height(14.dp))
        AppButton(
            if (indexing) "Indexing…" else "Index my notes",
            onClick = onReindex,
            variant = ButtonVariant.Primary,
            enabled = !indexing,
        )
    }
}

@Composable
private fun CaseLibraryEmptyState(searching: Boolean) {
    val tc = tc()
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        AppText(
            if (searching) "Searching…" else "No matching cases for this search",
            color = tc.td,
            fontSize = 12.sp,
        )
    }
}

// Surfaced when a debounced search (or a reindex) throws instead of leaving the dialog stuck on
// "Searching…" forever — see AppState.runCaseLibrarySearch's catch block.
@Composable
private fun CaseLibraryErrorState(message: String, onRetry: () -> Unit) {
    val tc = tc()
    Column(
        Modifier.fillMaxSize().padding(32.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        AppText("Search failed", color = tc.tx, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
        Spacer(Modifier.height(6.dp))
        AppText(
            message,
            color = tc.td,
            fontSize = 11.sp,
            maxLines = 3,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.widthIn(max = 380.dp),
        )
        Spacer(Modifier.height(14.dp))
        AppButton("Try again", onClick = onRetry, variant = ButtonVariant.Secondary)
    }
}

@Composable
private fun CaseLibraryResultsList(
    results: List<CaseSummary>,
    selectedIdx: Int,
    onSelect: (Int) -> Unit,
    onPreview: (String) -> Unit,
) {
    val tc = tc()
    val listState = remember { LazyListState() }
    LaunchedEffect(selectedIdx) {
        if (selectedIdx in results.indices) listState.animateScrollToItem(selectedIdx)
    }
    Box(Modifier.fillMaxSize()) {
        // Extra end padding (on top of the base horizontal padding) keeps row content clear of the
        // scrollbar rendered at CenterEnd below — same fix as CasePreviewDialog's body text.
        LazyColumn(Modifier.fillMaxSize().padding(horizontal = 8.dp).padding(end = 8.dp), state = listState) {
            itemsIndexed(results, key = { _, summary -> summary.id }) { idx, summary ->
                CaseLibraryResultRow(
                    summary = summary,
                    selected = idx == selectedIdx,
                    onClick = {
                        onSelect(idx)
                        onPreview(summary.id)
                    },
                )
            }
        }
        VerticalScrollbar(
            adapter = rememberScrollbarAdapter(listState),
            modifier = Modifier.align(Alignment.CenterEnd).fillMaxHeight(),
            style = appScrollbarStyle(tc),
        )
    }
}

// Row-click always previews (safe by default) — "Load into this tab" lives only in the preview
// dialog now, so the row itself has no destructive action and needs no loading/disabled state.
// Selection is painted with tc.abg (not the hover token) so a keyboard-selected row stays visibly
// distinct from whatever row the mouse happens to be hovering.
@Composable
private fun CaseLibraryResultRow(summary: CaseSummary, selected: Boolean, onClick: () -> Unit) {
    val tc = tc()
    HoverBox(
        modifier = Modifier.fillMaxWidth(),
        baseBg = if (selected) tc.abg else Color.Transparent,
        hoverBg = tc.hv,
        onClick = onClick,
    ) {
        Column(
            Modifier.fillMaxWidth()
                .border(1.dp, tc.br.copy(.4f))
                .padding(horizontal = 10.dp, vertical = 6.dp),
        ) {
            AppText(
                summary.title,
                color = tc.tx,
                fontSize = 11.sp,
                fontFamily = MONO,
                fontWeight = FontWeight.SemiBold,
                overflow = TextOverflow.Ellipsis,
            )
            if (summary.descriptionSnippet.isNotBlank()) {
                AppText(
                    summary.descriptionSnippet,
                    color = tc.tx,
                    fontSize = 11.sp,
                    fontFamily = MONO,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.padding(top = 3.dp),
                )
            }
            AppText(
                caseMetaLine(summary),
                color = tc.td,
                fontSize = 9.sp,
                fontFamily = MONO,
                modifier = Modifier.padding(top = 3.dp),
            )
            if (summary.matchedTags.isNotEmpty()) {
                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(4.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp),
                    modifier = Modifier.padding(top = 4.dp),
                ) {
                    summary.matchedTags.take(10).forEach { tag -> CaseTagChip(tag) }
                }
            }
        }
    }
}

// Rank/provenance line (design point 7) so two similarly-titled cases stay distinguishable without
// opening both — mirrors IssueSiteRow's 9sp metadata line. score is an unbounded relevance number
// (see CaseSearch.score), not a normalized 0-100 percentage, so it's shown as-is rather than "%".
private fun caseMetaLine(summary: CaseSummary): String = buildString {
    append("relevance ")
    append(String.format(java.util.Locale.US, "%.1f", summary.score))
    if (summary.appVersion.isNotBlank()) {
        append(" · v")
        append(summary.appVersion)
    }
}

// Read-only note text (design point 4's "Preview, always safe") — modeled on
// LicenseAgreementDialog's scrollable SelectionContainer + AppText body.
@Composable
private fun CasePreviewDialog(preview: CaseLibraryPreview, loading: Boolean, onLoad: () -> Unit, onDismiss: () -> Unit) {
    val tc = tc()
    val scroll = rememberScrollState()
    Column(
        Modifier.fillMaxWidth(0.6f).fillMaxHeight(0.75f)
            .background(tc.p, CASE_LIBRARY_DIALOG_SHAPE)
            .border(1.dp, tc.br, CASE_LIBRARY_DIALOG_SHAPE),
    ) {
        Row(
            Modifier.fillMaxWidth().height(40.dp).background(tc.p2, CASE_LIBRARY_HEADER_SHAPE)
                .padding(horizontal = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            AppText(
                preview.title,
                color = tc.tx,
                fontSize = 13.sp,
                fontWeight = FontWeight.SemiBold,
                modifier = Modifier.weight(1f),
                overflow = TextOverflow.Ellipsis,
            )
            // Secondary + isDanger (not Primary): this is the same destructive "replace this tab's
            // notes" action gated by CaseLoadConfirmDialog below, not the loudest thing in the
            // header — see McpInfoDialog's Block/Unblock button for the house pattern.
            AppButton(
                if (loading) "Loading…" else "Load into this tab",
                onClick = onLoad,
                variant = ButtonVariant.Secondary,
                isDanger = true,
                enabled = !loading,
                modifier = Modifier.height(28.dp),
            )
            CloseButton(onClick = onDismiss)
        }
        Divider()
        Box(Modifier.weight(1f).fillMaxWidth()) {
            SelectionContainer {
                AppText(
                    preview.text,
                    color = tc.ts,
                    fontSize = 11.sp,
                    fontFamily = MONO,
                    maxLines = Int.MAX_VALUE,
                    modifier = Modifier.fillMaxSize().verticalScroll(scroll).padding(16.dp).padding(end = 8.dp),
                )
            }
            VerticalScrollbar(
                adapter = rememberScrollbarAdapter(scroll),
                modifier = Modifier.align(Alignment.CenterEnd).fillMaxHeight().padding(vertical = 4.dp),
                style = appScrollbarStyle(tc),
            )
        }
    }
}

// The single most important correctness point in this feature (per the design brief): loading a
// case mutates the target tab's annotations in place — either a whole-object replace (when the
// case has an `.ann` sidecar) or an append of a new note block onto whatever's already there (a
// lone hand-copied `.md` with no sidecar) — so this confirmation is the only thing standing
// between a click and an unrecoverable change to whatever the tab already had. [replacesNotes]
// picks which of those two outcomes actually applies here (see AppState.PendingCaseLoad).
@Composable
private fun CaseLoadConfirmDialog(caseTitle: String, replacesNotes: Boolean, onConfirm: () -> Unit, onCancel: () -> Unit) {
    val tc = tc()
    // Elided before interpolation: caseTitle is arbitrary-length (a saved note's title), and this
    // sentence has only ~one line of slack at maxLines = 4 in a 380dp-wide dialog — an untruncated
    // title could otherwise clip mid-word with no ellipsis (AppText defaults to TextOverflow.Clip).
    val title = elide(caseTitle, CASE_TITLE_ELIDE_LEN)
    Column(
        Modifier.width(420.dp).background(tc.p, CASE_LIBRARY_DIALOG_SHAPE)
            .border(1.dp, tc.br, CASE_LIBRARY_DIALOG_SHAPE).padding(20.dp),
    ) {
        AppText(
            if (replacesNotes) "Replace this tab's notes?" else "Add these notes to this tab?",
            color = tc.tx,
            fontSize = 14.sp,
            fontWeight = FontWeight.SemiBold,
        )
        Spacer(Modifier.height(6.dp))
        AppText(
            if (replacesNotes) {
                "Loading \"$title\" replaces this tab's current notes entirely — nothing is merged, " +
                    "and this can't be undone."
            } else {
                "Loading \"$title\" adds it as a new note block onto this tab's current notes — nothing " +
                    "existing is removed, but this can't be undone."
            },
            color = tc.td,
            fontSize = 11.sp,
            maxLines = 4,
            overflow = TextOverflow.Ellipsis,
        )
        Spacer(Modifier.height(14.dp))
        Row(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterHorizontally),
        ) {
            DialogActionButton(
                if (replacesNotes) "Replace notes" else "Add notes",
                active = true,
                danger = replacesNotes,
                onClick = onConfirm,
            )
            DialogActionButton("Cancel", active = false, onClick = onCancel)
        }
    }
}
