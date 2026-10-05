@file:OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)

package com.indagium.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
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
import androidx.compose.runtime.mutableIntStateOf
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
import com.indagium.capture.CaptureDevice
import com.indagium.testing.model.ALLOWED_RUN_REPEATS
import com.indagium.testing.model.EvidenceFlags
import com.indagium.testing.model.JudgeMode
import com.indagium.testing.model.TestCase
import com.indagium.testing.model.TestSuite
import com.indagium.testing.run.MAX_PARALLEL_DEVICES
import com.indagium.testing.run.StartRunResult
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

// "Run suite…" / "Run this case": a cases checklist, lane rows (what drives each lane and on which device; add, remove and
// reorder them), the judge, repeat, the tool-call limit and the evidence to keep. Lanes on different devices run at the same
// time, lanes that share a device one after another (the dialog says so). Start validates through the coordinator (which refuses
// with every problem at once); on success the Runs screen opens on the new run. The device list is read with adb off the UI thread
// and never offers the device the live capture holds.

private val DIALOG_WIDTH = 640.dp
private val DIALOG_MAX_HEIGHT = 720.dp
private val DIALOG_SHAPE = RoundedCornerShape(8.dp)
private val LIMIT_FIELD_WIDTH = 90.dp
private val CASES_MAX_HEIGHT = 180.dp
private val MENU_WIDTH = 300.dp

/** What the dialog was opened for: a whole suite, or one case ([caseId]). */
internal data class RunDialogTarget(val suiteId: String, val caseId: String? = null)

@Composable
internal fun TestRunDialog(target: RunDialogTarget, onDismiss: () -> Unit) {
    val tc = tc()
    val ui = LocalTestsUi.current
    val limits = LocalTestsLimits.current
    val suite = ui.library.suite(target.suiteId)
    if (suite == null) {
        onDismiss()
        return
    }
    val runnable = suite.cases.filterNot { limits.isCaseLocked(it.id) }
    val profiles = ui.state.settings.aiProviderProfiles
    val choices = remember(profiles) { laneChoices(profiles) }
    var model by remember {
        mutableStateOf(
            RunDialogModel(
                suiteId = suite.id,
                selectedCaseIds = target.caseId?.let { setOf(it) } ?: runnable.map { it.id }.toSet(),
                choice = choices.firstOrNull { it.profileId == selectedProfileId(ui.state) } ?: choices.firstOrNull(),
                deviceSerial = null,
            ).withTestingDefaults(ui.state.settings.testing, profiles),
        )
    }
    var refresh by remember { mutableIntStateOf(0) }
    val devices by produceState<DevicesState>(DevicesState.Loading, refresh) { value = loadDevices(ui.state) }
    var problem by remember { mutableStateOf<String?>(null) }
    var starting by remember { mutableStateOf(false) }
    val deviceChoices = (devices as? DevicesState.Ready)?.choices.orEmpty()
    LaunchedEffect(deviceChoices) { model = model.withDefaultDevices(deviceChoices) }

    fun close() {
        onDismiss()
        ui.reclaimFocus()
    }

    fun start() {
        val config = model.toConfig(suite.cases.map { it.id }).getOrElse {
            problem = it.message
            return
        }
        problem = null
        starting = true
        ui.scope.launch {
            when (val started = ui.state.testRunCoordinator.start(config)) {
                is StartRunResult.Started -> {
                    ui.view.selectedRunId = started.runId
                    ui.view.nav = TestsNav.Runs
                    if (started.warnings.isNotEmpty()) ui.info(started.warnings.joinToString(" "))
                    close()
                }
                is StartRunResult.Rejected -> {
                    problem = started.errors.joinToString("\n")
                    starting = false
                }
            }
        }
    }

    Dialog(onDismissRequest = ::close, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Column(
            Modifier.width(DIALOG_WIDTH).heightIn(max = DIALOG_MAX_HEIGHT).background(tc.p, DIALOG_SHAPE).border(1.dp, tc.br, DIALOG_SHAPE).padding(20.dp),
        ) {
            val title = target.caseId?.let { id -> suite.cases.firstOrNull { it.id == id }?.name?.let { "Run case “$it”" } } ?: "Run suite “${suite.name}”"
            AppText(title, color = tc.tx, fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
            Column(Modifier.weight(1f, fill = false).verticalScroll(rememberScrollState())) {
                CasesSection(suite, model.selectedCaseIds, limits, onChange = { model = model.copy(selectedCaseIds = it) })
                LanesSection(model, { model = it }, choices, devices, deviceChoices) { refresh++ }
                JudgeSection(model, { model = it }, choices.filterNot { it.isExternal })
                SettingsSection(
                    model.repeat, { model = model.copy(repeat = it) },
                    model.toolLimitText, { model = model.copy(toolLimitText = it) },
                    model.evidence, { model = model.copy(evidence = it) },
                )
            }
            problem?.let {
                Spacer(Modifier.padding(top = 8.dp))
                TestsErrorText(it)
            }
            Spacer(Modifier.padding(top = 10.dp))
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End), verticalAlignment = Alignment.CenterVertically) {
                DialogActionButton(if (starting) "Starting…" else "Start", active = true, enabled = !starting) { start() }
                DialogActionButton("Cancel", active = false) { close() }
            }
        }
    }
}

/** Gives every lane row without a usable device the first free one (a device no other row uses), else the first device. */
internal fun RunDialogModel.withDefaultDevices(devices: List<DeviceChoice>): RunDialogModel {
    if (devices.isEmpty()) return this
    val lanes = allLanes()
    val taken = lanes.mapNotNull { it.deviceSerial }.filter { serial -> devices.any { it.serial == serial } }.toMutableList()
    val fixed = lanes.map { draft ->
        if (draft.deviceSerial != null && devices.any { it.serial == draft.deviceSerial }) {
            draft
        } else {
            val pick = (devices.firstOrNull { it.serial !in taken } ?: devices.first()).serial
            taken += pick
            draft.copy(deviceSerial = pick)
        }
    }
    return if (fixed == lanes) this else withLanes(fixed)
}

private fun selectedProfileId(state: AppState): String? = state.settings.aiProviderProfiles.firstOrNull { it.selected }?.id

private sealed interface DevicesState {
    data object Loading : DevicesState

    data class Ready(val choices: List<DeviceChoice>) : DevicesState

    data class Failed(val message: String) : DevicesState
}

@Suppress("TooGenericExceptionCaught") // adb can fail in many ways; the dialog shows the message and offers a retry.
private suspend fun loadDevices(state: AppState): DevicesState = withContext(Dispatchers.IO) {
    try {
        val devices: List<CaptureDevice> = state.aiCaptureDevices()
        DevicesState.Ready(deviceChoices(devices, state.liveCaptureSerial()))
    } catch (failure: Exception) {
        DevicesState.Failed(failure.message ?: "Could not list the connected devices.")
    }
}

@Composable
private fun CasesSection(suite: TestSuite, selected: Set<String>, limits: TestsLimitsUiState, onChange: (Set<String>) -> Unit) {
    TestsSectionTitle("Cases")
    Column(Modifier.heightIn(max = CASES_MAX_HEIGHT).verticalScroll(rememberScrollState())) {
        suite.cases.forEach { case -> CaseCheckRow(case, case.id in selected, limits) { on -> onChange(if (on) selected + case.id else selected - case.id) } }
    }
    if (suite.cases.isEmpty()) TestsHint("This suite has no cases yet.")
}

@Composable
private fun CaseCheckRow(case: TestCase, checked: Boolean, limits: TestsLimitsUiState, onToggle: (Boolean) -> Unit) {
    val locked = limits.isCaseLocked(case.id)
    CheckRow(checked = checked && !locked, onToggle = { onToggle(!checked) }, enabled = !locked) {
        AppText(case.name.ifBlank { "Untitled case" }, color = if (locked) tc().ts else tc().tx, fontSize = 12.sp)
        AppText("${case.steps.size} step(s)", color = tc().td, fontSize = 10.sp)
        if (locked) LockBadge(limits.hint)
    }
}

@Composable
private fun LanesSection(
    model: RunDialogModel,
    onModel: (RunDialogModel) -> Unit,
    choices: List<LaneChoice>,
    devices: DevicesState,
    deviceChoices: List<DeviceChoice>,
    onRefresh: () -> Unit,
) {
    val lanes = model.allLanes()
    TestsSectionTitle("Lanes")
    TestsHint("Lanes on different devices run at the same time (up to $MAX_PARALLEL_DEVICES devices); lanes that share a device run one after another.")
    ReorderableColumn(
        items = lanes,
        idOf = { it.id },
        onMove = { id, to -> onModel(model.moveLane(id, to)) },
        rowGap = 6.dp,
        modifier = Modifier.padding(top = 6.dp),
    ) { draft, row ->
        ReorderRowCard(row, enabled = true, onRemove = if (lanes.size > 1) ({ onModel(model.removeLane(draft.id)) }) else null) {
            LaneRow(draft, choices, deviceChoices, { updated -> onModel(model.updateLane(draft.id) { updated }) }, Modifier.weight(1f))
        }
    }
    Row(Modifier.padding(top = 6.dp), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
        AppButton("+ Add lane", onClick = { onModel(model.addLane(LaneDraft(choice = lanes.last().choice)).withDefaultDevices(deviceChoices)) })
        AppButton("Refresh devices", onClick = onRefresh)
    }
    model.deviceWarnings().forEach { TestsLockedNotice(it, Modifier.padding(top = 6.dp)) }
    when (devices) {
        DevicesState.Loading -> TestsHint("Looking for devices…")
        is DevicesState.Failed -> TestsErrorText(devices.message)
        is DevicesState.Ready -> if (devices.choices.isEmpty()) TestsHint("No ready device. The device of the live capture tab is not offered.")
    }
    if (lanes.any { it.choice?.isExternal == true }) TestsHint("An external lane has no agent: drive it with the test_lane_tool_call MCP tool.")
}

@Composable
private fun LaneRow(draft: LaneDraft, choices: List<LaneChoice>, deviceChoices: List<DeviceChoice>, onChange: (LaneDraft) -> Unit, modifier: Modifier) {
    FlowRow(
        modifier,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
        itemVerticalAlignment = Alignment.CenterVertically,
    ) {
        TestsDropdown(
            selectedLabel = draft.choice?.label ?: "Choose what drives the lane",
            options = choices,
            optionLabel = { it.label },
            onSelect = { onChange(draft.copy(choice = it)) },
            menuWidth = MENU_WIDTH,
            isSelected = { it == draft.choice },
        )
        TestsDropdown(
            selectedLabel = deviceChoices.firstOrNull { it.serial == draft.deviceSerial }?.label ?: "Choose a device",
            options = deviceChoices,
            optionLabel = { it.label },
            onSelect = { onChange(draft.copy(deviceSerial = it.serial)) },
            menuWidth = MENU_WIDTH,
            isSelected = { it.serial == draft.deviceSerial },
            emptyText = "No ready device found",
        )
    }
}

/** The judge: which AI profile and when. Any profile kind works (Claude Code and Codex too); the judge never sees what an agent claimed. */
@Composable
private fun JudgeSection(model: RunDialogModel, onModel: (RunDialogModel) -> Unit, profileChoices: List<LaneChoice>) {
    TestsSectionTitle("Judge")
    TestsHint(
        "A blind AI judge compares each step's expected result with the screenshot, the log and the check results, " +
            "without seeing what the agent claimed.",
    )
    Row(Modifier.padding(top = 6.dp), horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
        JudgeMode.entries.forEach { mode ->
            AppButton(
                mode.label,
                onClick = {
                    val profile = model.judgeProfileId ?: profileChoices.firstOrNull()?.profileId
                    onModel(model.copy(judgeMode = mode, judgeProfileId = profile))
                },
                variant = if (mode == model.judgeMode) ButtonVariant.Primary else ButtonVariant.Secondary,
            )
        }
    }
    if (model.judgeMode != JudgeMode.OFF) {
        val selected = profileChoices.firstOrNull { it.profileId == model.judgeProfileId }
        TestsDropdown(
            selectedLabel = selected?.label ?: "Choose the judge's AI profile",
            options = profileChoices,
            optionLabel = { it.label },
            onSelect = { onModel(model.copy(judgeProfileId = it.profileId)) },
            menuWidth = MENU_WIDTH,
            isSelected = { it.profileId == model.judgeProfileId },
            modifier = Modifier.padding(top = 6.dp),
            emptyText = "No AI profile configured (Settings > AI providers)",
        )
    }
}

@Composable
private fun SettingsSection(
    repeat: Int,
    onRepeat: (Int) -> Unit,
    limit: String,
    onLimit: (String) -> Unit,
    evidence: EvidenceFlags,
    onEvidence: (EvidenceFlags) -> Unit,
) {
    TestsSectionTitle("Settings")
    Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
        AppText("Repeat", color = tc().ts, fontSize = 11.sp)
        ALLOWED_RUN_REPEATS.forEach { n ->
            AppButton("$n×", onClick = { onRepeat(n) }, variant = if (n == repeat) ButtonVariant.Primary else ButtonVariant.Secondary)
        }
        Spacer(Modifier.width(12.dp))
        AppText("Tool calls per case", color = tc().ts, fontSize = 11.sp)
        InlineField(value = limit, onValue = onLimit, modifier = Modifier.width(LIMIT_FIELD_WIDTH), fontSize = 12.sp)
    }
    TestsFieldLabel("Evidence to keep")
    EvidenceRow("Screenshots", evidence.screenshots) { onEvidence(evidence.copy(screenshots = !evidence.screenshots)) }
    EvidenceRow("Logcat", evidence.logcat) { onEvidence(evidence.copy(logcat = !evidence.logcat)) }
    EvidenceRow("Agent transcript", evidence.transcript) { onEvidence(evidence.copy(transcript = !evidence.transcript)) }
    EvidenceRow("Video (needs scrcpy)", evidence.video) { onEvidence(evidence.copy(video = !evidence.video)) }
}

@Composable
private fun EvidenceRow(label: String, checked: Boolean, onToggle: () -> Unit) {
    CheckRow(checked, onToggle) { AppText(label, color = tc().tx, fontSize = 12.sp) }
}
