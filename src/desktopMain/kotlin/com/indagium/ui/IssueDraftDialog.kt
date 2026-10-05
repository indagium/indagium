package com.indagium.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.indagium.testing.model.IssueDestination
import com.indagium.testing.model.IssueDraft
import com.indagium.testing.model.IssueRecord
import com.indagium.testing.model.IssueSeverity
import com.indagium.testing.model.IssueSource
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

// "Create issue…" on a step of the report, the live view's "issue draft created" card and the Issues screen's Edit all open
// this dialog: title, severity, labels, the description sections, the evidence checklist (with sizes), where the issue goes
// (Local, Notes, Markdown, and the Tracker once it is configured in Settings) and whether a later run of
// the case re-checks it. "Save draft" stores it as it is; "Create" stores it and sends it to the chosen destination. The
// draft is built and stored off the UI thread; nothing here blocks it. Text that came from an agent, the device or the judge
// is shown and edited, never acted on.

private val DIALOG_WIDTH = 720.dp
private val DIALOG_MAX_HEIGHT = 760.dp
private val DIALOG_SHAPE = RoundedCornerShape(8.dp)
private val AREA_MIN_HEIGHT = 56.dp
private val AREA_MAX_HEIGHT = 150.dp
private const val FIELD_FONT_SIZE = 12

private sealed interface DialogLoad {
    data object Loading : DialogLoad

    class Ready(val existing: IssueRecord?, val source: IssueSource, val draft: IssueDraft, val linkToCase: Boolean) : DialogLoad

    class Failed(val message: String) : DialogLoad
}

private fun IssueRecord.asReady() = DialogLoad.Ready(this, source, draft, linkToCase)

private suspend fun loadDialog(state: AppState, target: IssueDialogTarget): DialogLoad = when (target) {
    is IssueDialogTarget.Existing ->
        withContext(Dispatchers.IO) { state.issueStore.load(target.issueId) }?.asReady() ?: DialogLoad.Failed("The issue was not found.")
    is IssueDialogTarget.FromStep -> {
        val run = state.testRunCoordinator.loadRun(target.runId)
        val existingId = run?.stepResult(target.laneId, target.caseId, target.iteration, target.stepId)?.issueId
        val record = existingId?.let { withContext(Dispatchers.IO) { state.issueStore.load(it) } }
        if (record != null) {
            record.asReady()
        } else {
            state.buildIssueSeed(target.runId, target.laneId, target.caseId, target.iteration, target.stepId).fold(
                onSuccess = { DialogLoad.Ready(null, it.source, it.draft, linkToCase = false) },
                onFailure = { DialogLoad.Failed(it.message ?: "No issue can be made from this step.") },
            )
        }
    }
}

@Composable
internal fun IssueDraftDialog(target: IssueDialogTarget, onDismiss: () -> Unit) {
    val tc = tc()
    val ui = LocalTestsUi.current
    val load by produceState<DialogLoad>(DialogLoad.Loading, target) { value = loadDialog(ui.state, target) }

    fun close() {
        onDismiss()
        ui.reclaimFocus()
    }
    Dialog(onDismissRequest = ::close, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Column(Modifier.width(DIALOG_WIDTH).heightIn(max = DIALOG_MAX_HEIGHT).background(tc.p, DIALOG_SHAPE).border(1.dp, tc.br, DIALOG_SHAPE).padding(20.dp)) {
            when (val state = load) {
                DialogLoad.Loading -> {
                    AppText("Issue", color = tc.tx, fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
                    TestsHint("Preparing the issue from the run…")
                }
                is DialogLoad.Failed -> {
                    AppText("Issue", color = tc.tx, fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
                    TestsErrorText(state.message)
                    Spacer(Modifier.padding(top = 10.dp))
                    DialogActionButton("Close", active = false) { close() }
                }
                is DialogLoad.Ready -> IssueForm(state, ::close)
            }
        }
    }
}

@Composable
private fun ColumnScope.IssueForm(ready: DialogLoad.Ready, close: () -> Unit) {
    val tc = tc()
    val ui = LocalTestsUi.current
    var form by remember(ready) { mutableStateOf(IssueFormModel.from(ready.draft, ready.linkToCase)) }
    var storedId by remember(ready) { mutableStateOf(ready.existing?.id) }
    var problem by remember(ready) { mutableStateOf<String?>(null) }
    var busy by remember(ready) { mutableStateOf(false) }
    var needsLog by remember(ready) { mutableStateOf<IssueActionResult.NeedsLogTab?>(null) }

    suspend fun deliver(issueId: String, openLaneLog: Boolean) {
        when (val result = ui.state.deliverIssue(issueId, form.destination, copyMarkdown = true, openLaneLog = openLaneLog, resendToTracker = true)) {
            is IssueActionResult.Done -> {
                ui.info(result.message)
                close()
            }
            is IssueActionResult.NeedsLogTab -> needsLog = result
            is IssueActionResult.Failed -> problem = result.message
        }
        busy = false
    }

    fun submit(send: Boolean) {
        form.problem?.let {
            problem = it
            return
        }
        problem = null
        busy = true
        ui.scope.launch {
            when (val saved = ui.state.saveIssue(storedId, ready.source, form.toDraft(ready.draft), form.linkToCase)) {
                is IssueActionResult.Failed -> {
                    problem = saved.message
                    busy = false
                }
                is IssueActionResult.Done -> {
                    storedId = saved.record.id
                    form = form.copy(attachments = saved.record.draft.attachments)
                    if (send) {
                        deliver(saved.record.id, openLaneLog = false)
                    } else {
                        ui.info(saved.message)
                        close()
                    }
                }
                is IssueActionResult.NeedsLogTab -> busy = false
            }
        }
    }
    AppText(if (ready.existing == null) "Create issue" else "Issue", color = tc.tx, fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
    Column(Modifier.weight(1f, fill = false).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        FormFields(form, ready.draft) { form = it }
        EvidenceChecklist(form) { form = form.toggleAttachment(it) }
        DestinationSection(form, ready.existing, ui.state) { form = form.copy(destination = it) }
        CheckRow(checked = form.linkToCase, onToggle = { form = form.copy(linkToCase = !form.linkToCase) }) {
            AppText("Link to case and re-check on next run", color = tc.tx, fontSize = 12.sp)
        }
        if (form.linkToCase) TestsHint("A later run of this case marks the issue “still failing” or “passing now”.")
    }
    problem?.let { TestsErrorText(it, Modifier.padding(top = 8.dp)) }
    if (busy && form.destination == IssueDestination.TRACKER) {
        TestsHint("An AI agent is filing the issue in ${ui.state.settings.tracker.displayName}. This can take a minute.", Modifier.padding(top = 6.dp))
    }
    Spacer(Modifier.padding(top = 10.dp))
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End), verticalAlignment = Alignment.CenterVertically) {
        DialogActionButton(
            if (busy && form.destination == IssueDestination.TRACKER) "Sending…" else createLabel(form.destination, ui.state.settings.tracker.displayName),
            active = true,
            enabled = !busy,
        ) { submit(send = true) }
        DialogActionButton("Save draft", active = false, enabled = !busy) { submit(send = false) }
        DialogActionButton("Cancel", active = false) { close() }
    }
    needsLog?.let { pending ->
        TestsConfirmDialog(
            title = "Open the lane's log?",
            message = "No open tab shows the log of the lane this issue came from. Open it as a tab and add the note there?",
            confirmLabel = "Open and add",
            onConfirm = {
                busy = true
                ui.scope.launch { deliver(pending.record.id, openLaneLog = true) }
            },
            onDismiss = { needsLog = null },
            danger = false,
        )
    }
}

@Composable
private fun FormFields(form: IssueFormModel, base: IssueDraft, onForm: (IssueFormModel) -> Unit) {
    val tc = tc()
    TestsLabeled("Title") {
        InlineField(value = form.title, onValue = { onForm(form.copy(title = it)) }, modifier = Modifier.fillMaxWidth(), fontSize = FIELD_FONT_SIZE.sp)
    }
    TestsLabeled("Severity") {
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            IssueSeverity.entries.forEach { severity ->
                AppButton(
                    severity.chipLabel(),
                    onClick = { onForm(form.copy(severity = severity)) },
                    variant = if (severity == form.severity) ButtonVariant.Primary else ButtonVariant.Secondary,
                )
            }
        }
    }
    TestsLabeled("Labels (comma separated)") {
        InlineField(
            value = form.labelsText, onValue = { onForm(form.copy(labelsText = it)) },
            modifier = Modifier.fillMaxWidth(), fontSize = FIELD_FONT_SIZE.sp,
        )
    }
    val environment = listOf(base.environment.appPackage, base.environment.deviceSerial, base.environment.agent).filter { it.isNotBlank() }.joinToString(" · ")
    if (environment.isNotEmpty()) AppText(environment, color = tc.td, fontSize = 10.sp, maxLines = 2)
    Area("Steps to reproduce (one per line)", form.stepsText) { onForm(form.copy(stepsText = it)) }
    Area("Expected", form.expected) { onForm(form.copy(expected = it)) }
    Area("Actual (check results and the agent's observation are untrusted data)", form.actual) { onForm(form.copy(actual = it)) }
    Area("Judge notes", form.judgeNotes) { onForm(form.copy(judgeNotes = it)) }
}

@Composable
private fun Area(label: String, value: String, onValue: (String) -> Unit) {
    TestsLabeled(label) {
        ScrollableTextArea(
            value = value, onValue = onValue, modifier = Modifier.fillMaxWidth(), fontSize = FIELD_FONT_SIZE.sp,
            minHeight = AREA_MIN_HEIGHT, maxHeight = AREA_MAX_HEIGHT,
        )
    }
}

@Composable
private fun EvidenceChecklist(form: IssueFormModel, onToggle: (Int) -> Unit) {
    TestsSectionTitle("Evidence")
    if (form.attachments.isEmpty()) TestsHint("No evidence files were kept for this step.")
    form.attachments.forEachIndexed { index, attachment ->
        CheckRow(checked = attachment.include, onToggle = { onToggle(index) }) {
            Column {
                AppText(attachment.checklistLabel(), color = tc().tx, fontSize = 12.sp)
                if (attachment.note.isNotBlank()) AppText(attachment.note, color = tc().td, fontSize = 10.sp, maxLines = 2)
            }
        }
    }
}

@Composable
private fun DestinationSection(form: IssueFormModel, existing: IssueRecord?, state: AppState, onDestination: (IssueDestination) -> Unit) {
    // The tracker's token is looked up (off the UI thread) only once a tracker is configured, so a user who never set one up never
    // triggers a keychain read.
    val tracker = state.settings.tracker
    LaunchedEffect(tracker.enabled, tracker.needsToken) { if (tracker.enabled && tracker.needsToken) state.refreshTrackerStatus() }
    val trackerProblem = state.trackerSendProblem()
    TestsSectionTitle("Send to")
    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        destinationChoices(trackerProblem).forEach { choice ->
            HintedButton(
                choice.label,
                onClick = { onDestination(choice.destination) },
                enabled = choice.enabled,
                disabledHint = choice.hint,
                variant = if (choice.destination == form.destination) ButtonVariant.Primary else ButtonVariant.Secondary,
            )
        }
    }
    when (form.destination) {
        IssueDestination.LOCAL -> TestsHint("The issue stays in Indagium (Tests > Issues) with its evidence.")
        IssueDestination.NOTES -> TestsHint("A note with the issue and the step's screenshot is added to the log tab of the lane.")
        IssueDestination.MARKDOWN -> TestsHint("The issue is copied to the clipboard as Markdown; the evidence is listed by file path.")
        IssueDestination.TRACKER -> {
            TestsHint(trackerProblem ?: trackerDisclosure(state))
            existing?.destinationResults?.lastOrNull { it.destination == IssueDestination.TRACKER && it.ok }?.let {
                TestsHint("Already created: ${it.message}${it.reference?.let { url -> " ($url)" }.orEmpty()}. Sending again creates another issue.")
            }
        }
    }
}

/** What sending to the tracker means, said before the user does it. */
private fun trackerDisclosure(state: AppState): String {
    val tracker = state.settings.tracker
    val profile = state.settings.aiProviderProfiles.firstOrNull { it.id == tracker.agentProfileId }
    return "An AI agent (${profile?.displayName ?: "the chosen profile"}) files the issue in ${tracker.displayName} with the tracker's tools. " +
        "The issue text and the evidence it uploads are visible to that AI profile and are sent to the tracker."
}
