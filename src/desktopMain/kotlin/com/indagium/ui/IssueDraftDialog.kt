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
import androidx.compose.runtime.DisposableEffect
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
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
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

@Suppress("ktlint:standard:max-line-length", "MaxLineLength")
@Composable
private fun ColumnScope.IssueForm(ready: DialogLoad.Ready, close: () -> Unit) {
    val tc = tc()
    val ui = LocalTestsUi.current
    var form by remember(ready) { mutableStateOf(IssueFormModel.from(ready.draft, ready.linkToCase)) }
    var storedId by remember(ready) { mutableStateOf(ready.existing?.id) }
    var problem by remember(ready) { mutableStateOf<String?>(null) }
    var busy by remember(ready) { mutableStateOf(false) }
    var needsLog by remember(ready) { mutableStateOf<IssueActionResult.NeedsLogTab?>(null) }
    var bugreportJob by remember(ready) { mutableStateOf<Job?>(null) }
    var bugreportProgress by remember(ready) { mutableStateOf<String?>(null) }
    var clipJob by remember(ready) { mutableStateOf<Job?>(null) }
    var clipStartText by remember(ready) { mutableStateOf("") }
    var clipEndText by remember(ready) { mutableStateOf("") }
    var clipProgress by remember(ready) { mutableStateOf<String?>(null) }
    var defaultClipWindow by remember(ready) { mutableStateOf<com.indagium.testing.run.IssueStepClipRequest?>(null) }
    var clipAvailability by remember(ready) { mutableStateOf<String?>(null) }
    LaunchedEffect(ready.source) {
        if (ready.source.stepNumber > 0) {
            ui.state.defaultIssueStepClipWindow(ready.source).fold(
                onSuccess = { window ->
                    defaultClipWindow = window
                    clipStartText = window.startMs.toString()
                    clipEndText = window.endMs.toString()
                },
                onFailure = { clipAvailability = it.message ?: "No saved video recording is available for this step." },
            )
        }
    }
    DisposableEffect(ready) {
        onDispose { bugreportJob?.cancel(); clipJob?.cancel() }
    }

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
        EvidenceChecklist(
            form = form,
            onToggle = { if (!busy) form = form.toggleAttachment(it) },
            onCollectBugreport = {
                if (bugreportJob != null || busy) return@EvidenceChecklist
                val collectionJob = ui.scope.launch(start = CoroutineStart.LAZY) {
                    busy = true
                    try {
                        var issueId = storedId
                        if (issueId == null) {
                            when (val saved = ui.state.saveIssue(null, ready.source, form.toDraft(ready.draft), form.linkToCase)) {
                                is IssueActionResult.Done -> {
                                    issueId = saved.record.id
                                    storedId = saved.record.id
                                    form = form.copy(attachments = saved.record.draft.attachments)
                                }
                                is IssueActionResult.Failed -> {
                                    problem = saved.message
                                    return@launch
                                }
                                is IssueActionResult.NeedsLogTab -> return@launch
                            }
                        }
                        val result = ui.state.collectIssueBugreport(issueId!!) { text -> bugreportProgress = text }
                        result.fold(
                            onSuccess = { record ->
                                form = form.copy(attachments = record.draft.attachments)
                                bugreportProgress = "Bugreport is available in the attachment checklist."
                            },
                            onFailure = { problem = it.message ?: "Could not collect the Android bugreport." },
                        )
                    } finally {
                        busy = false
                        if (bugreportJob === coroutineContext[Job]) bugreportJob = null
                    }
                }
                bugreportJob = collectionJob
                collectionJob.start()
            },
            onCancelBugreport = { bugreportJob?.cancel(); bugreportProgress = "Cancelling bugreport collection…" },
            collecting = bugreportJob != null,
            progress = bugreportProgress,
            enabled = ready.draft.environment.deviceSerial.isNotBlank(),
            checklistEnabled = !busy,
        )
        if (ready.source.stepNumber > 0) {
            IssueStepClipControls(
                startText = clipStartText,
                endText = clipEndText,
                onStart = { clipStartText = it },
                onEnd = { clipEndText = it },
                defaultWindow = defaultClipWindow,
                unavailable = clipAvailability,
                progress = clipProgress,
                exporting = clipJob != null,
                enabled = !busy && clipJob == null && clipAvailability == null,
                onCancel = { clipJob?.cancel(); clipProgress = "Cancelling clip export…" },
                onExport = {
                    if (busy || clipJob != null) return@IssueStepClipControls
                    val start = clipStartText.trim().toLongOrNull()
                    val end = clipEndText.trim().toLongOrNull()
                    if (clipStartText.isNotBlank() && start == null || clipEndText.isNotBlank() && end == null) {
                        problem = "Clip bounds must be whole-number milliseconds."
                        return@IssueStepClipControls
                    }
                    val exportJob = ui.scope.launch(start = CoroutineStart.LAZY) {
                        busy = true
                        try {
                            var issueId = storedId
                            if (issueId == null) {
                                when (val saved = ui.state.saveIssue(null, ready.source, form.toDraft(ready.draft), form.linkToCase)) {
                                    is IssueActionResult.Done -> {
                                        issueId = saved.record.id
                                        storedId = saved.record.id
                                        form = form.copy(attachments = saved.record.draft.attachments)
                                    }
                                    is IssueActionResult.Failed -> {
                                        problem = saved.message
                                        return@launch
                                    }
                                    is IssueActionResult.NeedsLogTab -> return@launch
                                }
                            }
                            val savedIssueId = issueId ?: return@launch
                            val result = ui.state.exportIssueStepClip(savedIssueId, start, end) { message -> clipProgress = message }
                            result.fold(
                                onSuccess = { exported ->
                                    form = form.copy(attachments = exported.issue.draft.attachments)
                                    clipStartText = exported.actualStartMs.toString()
                                    clipEndText = exported.actualEndMs.toString()
                                    clipProgress = "Exported bounds ${exported.actualStartMs}–${exported.actualEndMs} ms. Original recording retained."
                                },
                                onFailure = { problem = it.message ?: "Could not export the step clip." },
                            )
                        } finally {
                            busy = false
                            if (clipJob === coroutineContext[Job]) clipJob = null
                        }
                    }
                    clipJob = exportJob
                    exportJob.start()
                },
            )
        }
        DestinationSection(form, ready.existing, ui.state) { form = form.copy(destination = it) }
        CheckRow(checked = form.linkToCase, onToggle = { form = form.copy(linkToCase = !form.linkToCase) }) {
            AppText("Link to case and re-check on next run", color = tc.tx, fontSize = 12.sp)
        }
        if (form.linkToCase) TestsHint("A later run of this case marks the issue “still failing” or “passing now”.")
    }
    problem?.let { TestsErrorText(it, Modifier.padding(top = 8.dp)) }
    if (busy && bugreportJob == null && form.destination == IssueDestination.TRACKER) {
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

@Suppress("ktlint:standard:max-line-length", "MaxLineLength")
@Composable
private fun IssueStepClipControls(
    startText: String,
    endText: String,
    onStart: (String) -> Unit,
    onEnd: (String) -> Unit,
    defaultWindow: com.indagium.testing.run.IssueStepClipRequest?,
    unavailable: String?,
    progress: String?,
    exporting: Boolean,
    enabled: Boolean,
    onCancel: () -> Unit,
    onExport: () -> Unit,
) {
    TestsSectionTitle("Step video clip")
    when {
        unavailable != null -> TestsHint(unavailable)
        defaultWindow != null -> TestsHint("Default: ${defaultWindow.startMs}–${defaultWindow.endMs} ms, including five seconds around the failed step and clamped to available recording coverage. Edit either bound before exporting.")
        else -> TestsHint("Finding the step recording and its available coverage…")
    }
    if (unavailable == null) {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            InlineField(startText, onStart, placeholder = "Start ms", modifier = Modifier.weight(1f), fontSize = FIELD_FONT_SIZE.sp)
            InlineField(endText, onEnd, placeholder = "End ms", modifier = Modifier.weight(1f), fontSize = FIELD_FONT_SIZE.sp)
            AppButton(if (exporting) "Exporting…" else "Export step clip", onClick = onExport, enabled = enabled)
            if (exporting) AppButton("Cancel", onClick = onCancel, variant = ButtonVariant.Ghost)
        }
    }
    progress?.let { TestsHint(it) }
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

@Suppress("ktlint:standard:max-line-length", "MaxLineLength")
@Composable
private fun EvidenceChecklist(
    form: IssueFormModel,
    onToggle: (Int) -> Unit,
    onCollectBugreport: () -> Unit,
    onCancelBugreport: () -> Unit,
    collecting: Boolean,
    progress: String?,
    enabled: Boolean,
    checklistEnabled: Boolean,
) {
    TestsSectionTitle("Evidence")
    if (form.attachments.isEmpty()) TestsHint("No evidence files were kept for this step.")
    form.attachments.forEachIndexed { index, attachment ->
        CheckRow(checked = attachment.include, onToggle = { onToggle(index) }, enabled = checklistEnabled) {
            Column {
                AppText(attachment.checklistLabel(), color = tc().tx, fontSize = 12.sp)
                if (attachment.note.isNotBlank()) AppText(attachment.note, color = tc().td, fontSize = 10.sp, maxLines = 2)
            }
        }
    }
    if (enabled) {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            AppButton(if (collecting) "Collecting bugreport…" else "Collect Android bugreport", onClick = onCollectBugreport, enabled = !collecting && checklistEnabled)
            if (collecting) AppButton("Cancel", onClick = onCancelBugreport, variant = ButtonVariant.Ghost)
        }
        progress?.let { TestsHint(it) }
    } else {
        TestsHint("A source device is not available for bugreport collection.")
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
