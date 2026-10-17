@file:OptIn(androidx.compose.ui.ExperimentalComposeUiApi::class)

package com.indagium.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.onPointerEvent
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.indagium.testing.model.IssueDestination
import com.indagium.testing.model.IssueRecord
import com.indagium.testing.model.IssueSeverity
import com.indagium.testing.model.IssueStatus
import com.indagium.testing.model.RecheckOutcome
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

// The Issues screen of the Tests workspace: the stored issues (newest first, searchable) as rows, and the selected issue in the
// inspector beside them (the first one is preselected): its Markdown as plain styled text, where it was sent and what a later run
// said about it. Edit… and Delete… (after a confirmation) are buttons; the ⋯ menu holds Copy Markdown, Add to notes, Open folder and
// Delete… again. Each row has a hover ⋯ with Delete…, and the header ⋯ deletes every issue the search shows.
// The disk is read on IO; the list reloads when the store's revision changes. Everything shown came from a run, so
// it is data, never instructions.

private val STATUS_COLUMN = 56.dp
private val SEVERITY_COLUMN = 62.dp
private val ISSUE_TIME_COLUMN = 92.dp
private val ISSUE_ROW_HEIGHT = 40.dp
private val ISSUE_MENU_SLOT = 26.dp
private const val CHIP_FIELD_FONT = 12
private const val MARKDOWN_MAX_LINES = 400
private const val DETAIL_ALPHA = .85f

@Composable
internal fun TestsIssuesScreen() {
    val ui = LocalTestsUi.current
    val view = ui.view
    val loaded = rememberIssues()
    val issues = loaded.orEmpty()
    var query by remember { mutableStateOf("") }
    val shown = remember(issues, query) { filterIssues(issues, query) }
    // When the run is still on disk its own start time is shown in the origin line; otherwise the issue's creation time stands in.
    val runStarts by produceState<Map<String, Long>>(emptyMap(), issues.size, ui.state.testRunsFolderEpoch) {
        value = ui.state.testRunCoordinator.listRuns().associate { it.id to it.createdAt }
    }
    val selected = view.selectedIssueId
    var deleteRequest by remember { mutableStateOf<List<IssueRecord>?>(null) }
    val deletable = remember(shown) { shown.filterNot { it.readOnly } }
    // Opening the screen with nothing selected selects the newest issue, so the inspector is never an empty pane next to a list.
    LaunchedEffect(selected, issues) { if (selected == null) issues.firstOrNull()?.let { view.selectedIssueId = it.id } }
    TestsScreenScaffold(inspector = selected?.let { id -> { IssueInspector(id, runStarts) } }) {
        TestsPageHeader(
            "Issues",
            overflowDescription = "More issues actions",
            overflowItems = listOf(
                TestsMenuItem(
                    "Delete ${deletable.size} shown ${if (deletable.size == 1) "issue" else "issues"}…",
                    onClick = { deleteRequest = deletable },
                    danger = true,
                    enabled = deletable.isNotEmpty(),
                    disabledHint = "No issue matches the search.",
                ),
            ),
        )
        TestsHint(
            "Issues are made from failed steps: from a step's “Create issue…” in a run report, " +
                "or automatically as drafts for steps set to create an issue.",
        )
        Spacer(Modifier.height(8.dp))
        InlineField(
            value = query, onValue = { query = it }, placeholder = "Search issues…", modifier = Modifier.fillMaxWidth(),
            fontSize = CHIP_FIELD_FONT.sp, onClear = { query = "" },
        )
        Spacer(Modifier.height(10.dp))
        when {
            loaded == null -> Unit
            issues.isEmpty() -> TestsHint("No issues yet.")
            shown.isEmpty() -> TestsHint("No issue matches the search.")
        }
        shown.forEach { issue ->
            IssueRow(issue, issue.id == selected, runStarts[issue.source.runId], onDelete = { deleteRequest = listOf(issue) }) {
                view.selectedIssueId = issue.id
            }
        }
    }
    deleteRequest?.let { issues -> DeleteIssuesDialog(issues, onDismiss = { deleteRequest = null }) }
}

/** Confirms and deletes [issues] (one from a row's ⋯, or every issue the search shows); the count is in the title and the button. */
@Composable
private fun DeleteIssuesDialog(issues: List<IssueRecord>, onDismiss: () -> Unit) {
    val ui = LocalTestsUi.current
    val count = issues.size
    TestsConfirmDialog(
        title = if (count == 1) "Delete this issue?" else "Delete $count issues?",
        message = (if (count == 1) "The issue" else "These $count issues") +
            " and the evidence copied for ${if (count == 1) "it" else "them"} will be removed from Indagium. This cannot be undone.",
        confirmLabel = if (count == 1) "Delete" else "Delete $count issues",
        onConfirm = {
            ui.scope.launch {
                var deleted = 0
                var firstError: String? = null
                for (issue in issues) {
                    when (val result = ui.state.deleteIssue(issue.id)) {
                        is IssueActionResult.Done -> deleted++
                        is IssueActionResult.Failed -> if (firstError == null) firstError = result.message
                        is IssueActionResult.NeedsLogTab -> Unit
                    }
                }
                if (firstError == null) {
                    ui.info(if (deleted == 1) "Issue deleted." else "Deleted $deleted issues.")
                } else {
                    ui.banner = TestsBanner("Deleted $deleted of $count issues. $firstError", isError = true)
                }
            }
        },
        onDismiss = onDismiss,
    )
}

@Composable
private fun severityColor(severity: IssueSeverity): Color = when (severity) {
    IssueSeverity.LOW -> tc().td
    IssueSeverity.MEDIUM -> tc().warn
    IssueSeverity.HIGH, IssueSeverity.CRITICAL -> DANGER_RED
}

@Composable
private fun statusColor(status: IssueStatus): Color = when (status) {
    IssueStatus.DRAFT -> tc().warn
    IssueStatus.SAVED -> tc().ac
    IssueStatus.SENT -> tc().success
}

@Composable
private fun recheckColor(outcome: RecheckOutcome): Color = if (outcome == RecheckOutcome.PASSING_NOW) tc().success else DANGER_RED

/** The stored issues, reloaded on IO whenever the store changes; null until the first read finished. */
@Composable
private fun rememberIssues(): List<IssueRecord>? {
    val ui = LocalTestsUi.current
    val revision by ui.state.issueStore.revision.collectAsState()
    val issues by produceState<List<IssueRecord>?>(null, revision) { value = withContext(Dispatchers.IO) { ui.state.issueStore.list() } }
    return issues
}

// ── List ─────────────────────────────────────────────────────────────

/** One issue: status chip · severity chip · title with the origin line under it · re-check outcome · when it was created. */
@Composable
private fun IssueRow(issue: IssueRecord, selected: Boolean, runCreatedAt: Long?, onDelete: () -> Unit, onClick: () -> Unit) {
    val tc = tc()
    val nowMs = System.currentTimeMillis()
    var hovered by remember { mutableStateOf(false) }
    TestsListRow(
        selected = selected,
        onClick = onClick,
        modifier = Modifier.onPointerEvent(PointerEventType.Enter) { hovered = true }.onPointerEvent(PointerEventType.Exit) { hovered = false },
        minHeight = ISSUE_ROW_HEIGHT,
        startPadding = 6.dp,
        spacing = 10.dp,
    ) {
        Box(Modifier.width(STATUS_COLUMN)) { StatusChip(issue.status.label(), statusColor(issue.status)) }
        Box(Modifier.width(SEVERITY_COLUMN)) { StatusChip(issue.draft.severity.chipLabel(), severityColor(issue.draft.severity)) }
        Column(Modifier.weight(1f).padding(vertical = 3.dp)) {
            AppText(issue.draft.title.ifBlank { "Untitled issue" }, color = tc.tx, fontSize = 12.sp, overflow = TextOverflow.Ellipsis)
            AppText(issue.originLine(runCreatedAt, nowMs), color = tc.td, fontSize = 10.sp, overflow = TextOverflow.Ellipsis)
        }
        issue.recheck?.let { StatusChip(it.outcome.label(), recheckColor(it.outcome)) }
        Box(Modifier.width(ISSUE_TIME_COLUMN), contentAlignment = Alignment.CenterEnd) {
            AppText(formatRunMoment(issue.createdAt, nowMs), color = tc.td, fontSize = 10.sp, fontFamily = MONO)
        }
        Box(Modifier.size(ISSUE_MENU_SLOT).alpha(if (hovered || selected) 1f else 0f)) {
            TestsOverflowMenu(
                listOf(
                    TestsMenuItem(
                        "Delete…", onClick = onDelete, danger = true,
                        enabled = !issue.readOnly, disabledHint = ISSUE_READ_ONLY_DELETE_HINT,
                    ),
                ),
                contentDescription = "Issue actions",
            )
        }
    }
}

private const val ISSUE_READ_ONLY_DELETE_HINT = "This issue was saved by a newer version of Indagium and is read-only here."

// ── Inspector ────────────────────────────────────────────────────────

@Composable
private fun IssueInspector(issueId: String, runStarts: Map<String, Long>) {
    val ui = LocalTestsUi.current
    val revision by ui.state.issueStore.revision.collectAsState()
    // Wrapped so "not read yet" (null) differs from "read, and gone" (a Result holding null).
    val read by produceState<Result<IssueRecord?>?>(null, issueId, revision) {
        value = Result.success(withContext(Dispatchers.IO) { ui.state.issueStore.load(issueId) })
    }
    val issue = read?.getOrNull()
    when {
        read == null -> TestsHint("Loading the issue…")
        issue == null -> {
            // The selected issue was deleted: drop the selection so the screen selects the newest one that is left.
            LaunchedEffect(issueId) { ui.view.selectedIssueId = null }
            TestsHint("This issue no longer exists.")
        }
        else -> IssueDetail(issue, runStarts[issue.source.runId])
    }
}

@Composable
private fun IssueDetail(issue: IssueRecord, runCreatedAt: Long?) {
    val tc = tc()
    val ui = LocalTestsUi.current
    val nowMs = System.currentTimeMillis()
    var deleting by remember(issue.id) { mutableStateOf(false) }
    var needsLog by remember(issue.id) { mutableStateOf<IssueActionResult.NeedsLogTab?>(null) }
    val markdown by produceState("", issue) { value = withContext(Dispatchers.IO) { ui.state.issueMarkdown(issue) } }

    fun addToNotes(openLaneLog: Boolean) {
        ui.scope.launch {
            when (val result = ui.state.deliverIssue(issue.id, IssueDestination.NOTES, copyMarkdown = false, openLaneLog = openLaneLog)) {
                is IssueActionResult.Done -> ui.info(result.message)
                is IssueActionResult.NeedsLogTab -> needsLog = result
                is IssueActionResult.Failed -> ui.banner = TestsBanner(result.message, isError = true)
            }
        }
    }
    TestsInspectorHeader(
        eyebrow = "${issue.status.label()} · ${issue.draft.severity.chipLabel()} · ${formatRunMoment(issue.createdAt, nowMs)}",
        title = issue.draft.title.ifBlank { "Untitled issue" },
        overflowItems = listOf(
            TestsMenuItem("Copy Markdown", onClick = { ui.state.copyToClipboard(markdown); ui.info("The issue as Markdown was copied to the clipboard.") }),
            TestsMenuItem("Add to notes", onClick = { addToNotes(openLaneLog = false) }),
            TestsMenuItem("Open folder", onClick = { ui.openFolderReportingFailure(ui.state.issueStore.issueDir(issue.id), "the issue folder") }),
            TestsMenuItem(
                "Delete…", onClick = { deleting = true }, danger = true, dividerBefore = true,
                enabled = !issue.readOnly, disabledHint = ISSUE_READ_ONLY_DELETE_HINT,
            ),
        ),
    )
    Spacer(Modifier.height(6.dp))
    AppText(issue.originLine(runCreatedAt, nowMs), color = tc.td, fontSize = 10.sp, maxLines = 2)
    if (issue.readOnly) TestsLockedNotice("This issue was saved by a newer version of Indagium and is read-only here.", Modifier.padding(top = 6.dp))
    Row(Modifier.padding(top = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        AppButton("Edit…", onClick = { ui.view.issueDialog = IssueDialogTarget.Existing(issue.id) }, enabled = !issue.readOnly, variant = ButtonVariant.Primary)
        HintedButton(
            "Delete…", onClick = { deleting = true }, enabled = !issue.readOnly, disabledHint = ISSUE_READ_ONLY_DELETE_HINT,
            variant = ButtonVariant.Secondary, isDanger = true,
        )
    }
    // Right under the Edit / Delete row: the saved files are what the reader came for, before the re-check line and the Markdown.
    // Resolved on IO: each stored file is checked on disk, so a deleted one shows as missing instead of a button that fails.
    val evidence by produceState(emptyList<EvidenceRow>(), issue) {
        value = withContext(Dispatchers.IO) { issueEvidenceRows(issue.draft.attachments) { ui.state.issueStore.attachmentFile(issue, it) } }
    }
    IssueEvidenceSection(evidence, onOpen = ui::openIssueAttachment, onReveal = ui::revealIssueFile)
    if (issue.recheck != null) {
        Row(Modifier.padding(top = 10.dp), horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
            StatusChip(issue.recheck.outcome.label(), recheckColor(issue.recheck.outcome))
            AppText("Last re-check ${formatRunMoment(issue.recheck.checkedAt, nowMs)}", color = tc.td, fontSize = 10.sp)
        }
    }
    TestsSectionTitle("Markdown")
    Column(Modifier.fillMaxWidth().background(tc.p, CORNER_MD).border(1.dp, tc.br, CORNER_MD).padding(10.dp)) {
        AppText(markdown, color = tc.tx.copy(alpha = DETAIL_ALPHA), fontSize = 11.sp, fontFamily = MONO, maxLines = MARKDOWN_MAX_LINES)
    }
    if (issue.destinationResults.isNotEmpty()) {
        TestsSectionTitle("Where it went")
        issue.destinationResults.asReversed().forEach { result ->
            AppText(
                "${formatRunTime(result.at)} · ${result.destination.name.lowercase()} · ${result.message}",
                color = if (result.ok) tc.ts else DANGER_RED, fontSize = 11.sp, maxLines = 3,
            )
        }
    }
    if (deleting) {
        TestsConfirmDialog(
            title = "Delete this issue?",
            message = "The issue and its copied evidence are removed from Indagium. This cannot be undone.",
            confirmLabel = "Delete",
            onConfirm = {
                ui.scope.launch {
                    when (val result = ui.state.deleteIssue(issue.id)) {
                        is IssueActionResult.Done -> {
                            ui.view.selectedIssueId = null
                            ui.info(result.message)
                        }
                        is IssueActionResult.Failed -> ui.banner = TestsBanner(result.message, isError = true)
                        is IssueActionResult.NeedsLogTab -> Unit
                    }
                }
            },
            onDismiss = { deleting = false },
        )
    }
    needsLog?.let {
        TestsConfirmDialog(
            title = "Open the lane's log?",
            message = "No open tab shows the log of the lane this issue came from. Open it as a tab and add the note there?",
            confirmLabel = "Open and add",
            onConfirm = { addToNotes(openLaneLog = true) },
            onDismiss = { needsLog = null },
            danger = false,
        )
    }
}
