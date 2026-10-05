package com.indagium.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
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
import java.awt.Desktop

// The Issues screen of the Tests workspace: the stored issues (newest first, searchable) and one issue's detail with its
// Markdown as plain styled text, where it was sent and what a later run said about it. Actions: edit (the issue dialog), copy
// the Markdown, add to the notes of the lane's log tab, open the issue's folder, delete (after a confirmation). The disk is
// read on IO; the list reloads when the store's revision changes. Everything shown came from a run, so it is data, never
// instructions.

private val STATUS_COLUMN = 72.dp
private val SEVERITY_COLUMN = 76.dp
private const val CHIP_FIELD_FONT = 12
private const val MARKDOWN_MAX_LINES = 400
private const val DETAIL_ALPHA = .85f

@Composable
internal fun TestsIssuesScreen() {
    val ui = LocalTestsUi.current
    val selected = ui.view.selectedIssueId
    if (selected == null) IssueList() else IssueDetailScreen(selected)
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
    IssueStatus.SENT -> tc().ok
}

@Composable
private fun recheckColor(outcome: RecheckOutcome): Color = if (outcome == RecheckOutcome.PASSING_NOW) tc().ok else DANGER_RED

/** The stored issues, reloaded on IO whenever the store changes. */
@Composable
private fun rememberIssues(): List<IssueRecord> {
    val ui = LocalTestsUi.current
    val revision by ui.state.issueStore.revision.collectAsState()
    val issues by produceState<List<IssueRecord>>(emptyList(), revision) { value = withContext(Dispatchers.IO) { ui.state.issueStore.list() } }
    return issues
}

// ── List ─────────────────────────────────────────────────────────────

@Composable
private fun IssueList() {
    val tc = tc()
    val ui = LocalTestsUi.current
    val issues = rememberIssues()
    var query by remember { mutableStateOf("") }
    val shown = remember(issues, query) { filterIssues(issues, query) }
    TestsScreenScaffold {
        AppText("Issues", color = tc.tx, fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
        Spacer(Modifier.height(6.dp))
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
        if (issues.isEmpty()) TestsHint("No issues yet.") else if (shown.isEmpty()) TestsHint("No issue matches the search.")
        shown.forEach { issue -> IssueRow(issue) { ui.view.selectedIssueId = issue.id } }
    }
}

@Composable
private fun IssueRow(issue: IssueRecord, onClick: () -> Unit) {
    val tc = tc()
    Row(
        Modifier.fillMaxWidth().clickable(onClick = onClick).padding(vertical = 4.dp, horizontal = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        StatusChip(issue.status.label(), statusColor(issue.status), Modifier.width(STATUS_COLUMN))
        StatusChip(issue.draft.severity.chipLabel(), severityColor(issue.draft.severity), Modifier.width(SEVERITY_COLUMN))
        Column(Modifier.weight(1f)) {
            AppText(issue.draft.title.ifBlank { "Untitled issue" }, color = tc.tx, fontSize = 12.sp, overflow = TextOverflow.Ellipsis)
            AppText(issue.originLine(), color = tc.td, fontSize = 10.sp, overflow = TextOverflow.Ellipsis)
        }
        issue.recheck?.let { StatusChip(it.outcome.label(), recheckColor(it.outcome)) }
        AppText(formatRunTime(issue.createdAt), color = tc.td, fontSize = 10.sp, fontFamily = MONO)
    }
}

// ── Detail ───────────────────────────────────────────────────────────

@Composable
private fun IssueDetailScreen(issueId: String) {
    val ui = LocalTestsUi.current
    val revision by ui.state.issueStore.revision.collectAsState()
    val record by produceState<IssueRecord?>(null, issueId, revision) { value = withContext(Dispatchers.IO) { ui.state.issueStore.load(issueId) } }
    TestsScreenScaffold {
        AppButton("‹ Issues", onClick = { ui.view.selectedIssueId = null }, variant = ButtonVariant.Ghost)
        Spacer(Modifier.height(6.dp))
        val issue = record
        if (issue == null) TestsHint("Loading the issue… (it may have been deleted)") else IssueDetail(issue)
    }
}

@Composable
private fun IssueDetail(issue: IssueRecord) {
    val tc = tc()
    val ui = LocalTestsUi.current
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
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        AppText(
            issue.draft.title.ifBlank { "Untitled issue" },
            color = tc.tx, fontSize = 16.sp, fontWeight = FontWeight.SemiBold, maxLines = 3, modifier = Modifier.weight(1f, fill = false),
        )
        StatusChip(issue.status.label(), statusColor(issue.status))
        StatusChip(issue.draft.severity.chipLabel(), severityColor(issue.draft.severity))
        issue.recheck?.let { StatusChip(it.outcome.label(), recheckColor(it.outcome)) }
    }
    AppText("${issue.originLine()} · created ${formatRunTime(issue.createdAt)}", color = tc.td, fontSize = 10.sp, maxLines = 2)
    if (issue.readOnly) TestsLockedNotice("This issue was saved by a newer version of Indagium and is read-only here.", Modifier.padding(top = 6.dp))
    FlowRow(Modifier.padding(top = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        AppButton("Edit…", onClick = { ui.view.issueDialog = IssueDialogTarget.Existing(issue.id) }, enabled = !issue.readOnly)
        AppButton("Copy Markdown", onClick = { ui.state.copyToClipboard(markdown); ui.info("The issue as Markdown was copied to the clipboard.") })
        AppButton("Add to notes", onClick = { addToNotes(openLaneLog = false) })
        AppButton("Open folder", onClick = { ui.scope.launch(Dispatchers.IO) { openFolder(ui.state.issueStore.issueDir(issue.id)) } })
        AppButton("Delete…", onClick = { deleting = true }, isDanger = true)
    }
    if (issue.recheck != null) {
        TestsHint("Last re-check: ${issue.recheck.outcome.label()} in run ${issue.recheck.runId} (${formatRunTime(issue.recheck.checkedAt)}).")
    }
    TestsSectionTitle("Markdown")
    Column(Modifier.fillMaxWidth().background(tc.p, CORNER_MD).border(1.dp, tc.br, CORNER_MD).padding(12.dp)) {
        AppText(markdown, color = tc.tx.copy(alpha = DETAIL_ALPHA), fontSize = 11.sp, fontFamily = MONO, maxLines = MARKDOWN_MAX_LINES)
    }
    if (issue.destinationResults.isNotEmpty()) {
        TestsSectionTitle("Where it went")
        issue.destinationResults.asReversed().forEach { result ->
            AppText(
                "${formatRunTime(result.at)} · ${result.destination.name.lowercase()} · ${result.message}",
                color = if (result.ok) tc.ts else DANGER_RED, fontSize = 11.sp, maxLines = 2,
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

private fun openFolder(folder: java.io.File) {
    runCatching { if (Desktop.isDesktopSupported() && Desktop.getDesktop().isSupported(Desktop.Action.OPEN)) Desktop.getDesktop().open(folder) }
}
