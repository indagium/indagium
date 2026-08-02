@file:OptIn(androidx.compose.ui.ExperimentalComposeUiApi::class, androidx.compose.foundation.ExperimentalFoundationApi::class)

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
import java.awt.FileDialog
import java.awt.Frame
import java.io.File

private val CASE_LIBRARY_DIALOG_SHAPE = RoundedCornerShape(8.dp)
private val CASE_LIBRARY_HEADER_SHAPE = RoundedCornerShape(topStart = 8.dp, topEnd = 8.dp)

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
                notesOnlyLoading = state.caseLibraryNotesOnlyLoadingId == preview.id,
                onReopen = { state.reopenInvestigation(preview.id) },
                onOpenNotesOnly = { state.openCaseNotesOnly(preview.id) },
                onLocateLog = { file -> state.locateLogForCase(preview.id, file) },
                onCopy = { state.copyCasePreview(preview.id) },
                onExport = { state.exportCasePreview(preview.id) },
                onDismiss = { state.dismissCasePreview() },
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
// LicenseAgreementDialog's scrollable SelectionContainer + AppText body. The metadata header above
// the rendered Markdown surfaces exactly what buildMd() deliberately never writes to the .md
// (issueDescription is private working context) plus the .ann-only fields (appVersion/
// decisiveTags/the recorded filter) — see AppState.previewCase. ONE SelectionContainer spans both
// the metadata header and the body below (not just the body, as it used to) so the issue
// description — often the longest field here, sometimes multi-paragraph — is selectable/copyable
// like everything else; per b/372053402 (see SelectionContainer's own source comment) its modifier
// must carry the `.weight(1f)` that reserves the body's share of the column, since it's the
// topmost layout node of everything it wraps. AppState.copyCasePreview's "Copy" button covers the
// same gap for users who never select text at all.
@Composable
private fun CasePreviewDialog(
    preview: CaseLibraryPreview,
    loading: Boolean,
    notesOnlyLoading: Boolean,
    onReopen: () -> Unit,
    onOpenNotesOnly: () -> Unit,
    onLocateLog: (File) -> Unit,
    onCopy: () -> Unit,
    onExport: () -> Unit,
    onDismiss: () -> Unit,
) {
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
            AppButton("Copy", onClick = onCopy, variant = ButtonVariant.Secondary, modifier = Modifier.height(28.dp))
            AppButton("Export", onClick = onExport, variant = ButtonVariant.Secondary, modifier = Modifier.height(28.dp))
            OpenNotesOnlyButton(loading = notesOnlyLoading, onClick = onOpenNotesOnly)
            if (preview.reopenDisabledReason != null) {
                LocateLogButton(onClick = onLocateLog)
            }
            ReopenInvestigationButton(loading = loading, disabledReason = preview.reopenDisabledReason, onClick = onReopen)
            CloseButton(onClick = onDismiss)
        }
        Divider()
        SelectionContainer(modifier = Modifier.weight(1f).fillMaxWidth()) {
            Column(Modifier.fillMaxSize()) {
                CasePreviewMetadata(preview)
                Divider()
                Box(Modifier.weight(1f).fillMaxWidth()) {
                    AppText(
                        preview.text,
                        color = tc.ts,
                        fontSize = 11.sp,
                        fontFamily = MONO,
                        maxLines = Int.MAX_VALUE,
                        modifier = Modifier.fillMaxSize().verticalScroll(scroll).padding(16.dp).padding(end = 8.dp),
                    )
                    VerticalScrollbar(
                        adapter = rememberScrollbarAdapter(scroll),
                        modifier = Modifier.align(Alignment.CenterEnd).fillMaxHeight().padding(vertical = 4.dp),
                        style = appScrollbarStyle(tc),
                    )
                }
            }
        }
    }
}

// Shown only when ReopenInvestigationButton is disabled (the original log can't be resolved) —
// the guided path Change 2's feature brief asks for: reconnect the note to the log wherever it
// actually ended up, instead of forcing "Open notes only" plus a manual "Open Note" in the log
// panel with no verification at all. Picks a file (no extension filter — same reasoning as
// TabBar's own "Open Log File" picker: platform pickers don't reliably invoke a filter, and
// AppState.locateLogForCase validates the pick itself) and hands it to AppState, which opens it as
// a brand-new tab and verifies its content fingerprint against this note's recorded one before
// attaching — see AppState.locateLogForCase/beginLogRelink.
@Composable
private fun LocateLogButton(onClick: (File) -> Unit) {
    TooltipArea(
        tooltip = {
            ToolbarTooltip(
                "Pick the log file wherever it ended up. It's checked against this note before " +
                    "attaching, so a different capture of the same bug won't silently mislabel every line.",
            )
        },
    ) {
        AppButton(
            "Locate log…",
            onClick = {
                val fd = FileDialog(null as Frame?, "Locate Log File", FileDialog.LOAD)
                fd.isVisible = true
                fd.file?.let { onClick(File(fd.directory, it)) }
            },
            variant = ButtonVariant.Secondary,
            modifier = Modifier.height(28.dp),
        )
    }
}

// Not Primary/isDanger: replacing "Load into this tab" (the old destructive action, gated behind a
// confirmation dialog), this always opens a brand-new tab and only ever touches THAT tab's own
// notes — nothing existing is at risk, so it reads as an ordinary secondary action. Disabled (with
// a tooltip explaining why, via TooltipArea/ToolbarTooltip — same house pattern as the toolbar's
// own disabled-with-reason buttons) whenever AppState.previewCase couldn't resolve a reopenable
// source, e.g. sourcePath blank or the original log file/archive entry no longer exists. The
// tooltip spells out the consequence (not just the cause) now that a disabled button here no
// longer leaves the user stuck — [OpenNotesOnlyButton] sits right next to it as the fallback.
@Composable
private fun ReopenInvestigationButton(loading: Boolean, disabledReason: String?, onClick: () -> Unit) {
    val button = @Composable {
        AppButton(
            when {
                loading -> "Reopening…"
                else -> "Reopen investigation"
            },
            onClick = onClick,
            variant = ButtonVariant.Secondary,
            enabled = !loading && disabledReason == null,
            modifier = Modifier.height(28.dp),
        )
    }
    if (disabledReason != null && !loading) {
        TooltipArea(tooltip = { ToolbarTooltip("$disabledReason — only the notes can be opened.") }) { button() }
    } else {
        button()
    }
}

// Always enabled, unlike ReopenInvestigationButton — this is the fallback that stays available even
// when the original log can't be resolved (AppState.openCaseNotesOnly's whole point: the notes are
// the durable artifact). The tooltip is the "short hint" the feature brief asked for re: LogRef
// blocks in a log-less tab rendering but not navigating anywhere — always shown, not just when
// disabled, since there's no disabled state here to hang it off of.
@Composable
private fun OpenNotesOnlyButton(loading: Boolean, onClick: () -> Unit) {
    TooltipArea(
        tooltip = {
            ToolbarTooltip(
                "Opens these notes in a new tab with no log attached. Log references still show, but " +
                    "clicking one won't jump anywhere — there's no log loaded to jump to.",
            )
        },
    ) {
        AppButton(
            if (loading) "Opening…" else "Open notes only",
            onClick = onClick,
            variant = ButtonVariant.Secondary,
            enabled = !loading,
            modifier = Modifier.height(28.dp),
        )
    }
}

@Composable
private fun CasePreviewMetadata(preview: CaseLibraryPreview) {
    val tc = tc()
    Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 10.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        if (preview.issueDescription.isNotBlank()) {
            CaseIssueDescriptionRow(preview.issueDescription)
        }
        CaseMetadataRow("Source", preview.sourceFilename ?: "Unknown")
        if (preview.appVersion.isNotBlank()) {
            CaseMetadataRow("App version", preview.appVersion)
        }
        // Always shown, even when it reads "Filter not recorded" — an old note with no field 8
        // must say so explicitly rather than showing nothing (see AppState.previewCase).
        CaseMetadataRow("Filter", preview.filterSummary)
        if (preview.decisiveTags.isNotEmpty()) {
            Row(verticalAlignment = Alignment.Top, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                AppText(
                    "Decisive tags",
                    color = tc.td,
                    fontSize = 9.sp,
                    modifier = Modifier.width(96.dp).padding(top = 2.dp),
                )
                FlowRow(horizontalArrangement = Arrangement.spacedBy(4.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    preview.decisiveTags.forEach { tag -> CaseTagChip(tag) }
                }
            }
        }
    }
}

// Issue descriptions are frequently long, sometimes multi-paragraph — the one genuinely long
// metadata field here. An unbounded height would push the note body itself off screen (or out of
// the dialog entirely, since CasePreviewMetadata is measured before the weighted body box below
// it), so this gets its own bounded-height, independently scrollable region instead, matching the
// body's own AppText+VerticalScrollbar shape. Still covered by CasePreviewDialog's outer
// SelectionContainer, so it's selectable/copyable like the rest of the header.
private val ISSUE_DESCRIPTION_MAX_HEIGHT = 120.dp

@Composable
private fun CaseIssueDescriptionRow(text: String) {
    val tc = tc()
    val scroll = rememberScrollState()
    Row(verticalAlignment = Alignment.Top, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        AppText("Issue", color = tc.td, fontSize = 9.sp, modifier = Modifier.width(96.dp).padding(top = 1.dp))
        Box(Modifier.weight(1f).heightIn(max = ISSUE_DESCRIPTION_MAX_HEIGHT)) {
            AppText(
                text,
                color = tc.ts,
                fontSize = 11.sp,
                maxLines = Int.MAX_VALUE,
                modifier = Modifier.fillMaxWidth().verticalScroll(scroll).padding(end = 8.dp),
            )
            VerticalScrollbar(
                adapter = rememberScrollbarAdapter(scroll),
                modifier = Modifier.align(Alignment.CenterEnd).fillMaxHeight(),
                style = appScrollbarStyle(tc),
            )
        }
    }
}

// Source/App version/Filter — short fields, but a filter summary (or an unusually long source
// label) can still run past three lines; they wrap instead of ellipsizing rather than getting their
// own scroll region like Issue above, since none of them are expected to run to paragraphs.
@Composable
private fun CaseMetadataRow(label: String, value: String) {
    val tc = tc()
    Row(verticalAlignment = Alignment.Top, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        AppText(label, color = tc.td, fontSize = 9.sp, modifier = Modifier.width(96.dp).padding(top = 1.dp))
        AppText(value, color = tc.ts, fontSize = 11.sp, maxLines = Int.MAX_VALUE, modifier = Modifier.weight(1f))
    }
}
