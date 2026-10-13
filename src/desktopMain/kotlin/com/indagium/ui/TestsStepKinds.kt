@file:OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)

package com.indagium.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.indagium.testing.authoring.RecordedTestStep
import com.indagium.testing.authoring.TestStepRecordingSession
import com.indagium.testing.device.DEVICE_STATE_KEY_HINTS
import com.indagium.testing.device.KNOWN_DEVICE_STATE_KEYS
import com.indagium.testing.model.ManualCompletion
import com.indagium.testing.model.ScriptCall
import com.indagium.testing.model.ScriptParam
import com.indagium.testing.model.ScriptParamType
import com.indagium.testing.model.ScriptPermission
import com.indagium.testing.model.StepCheck
import com.indagium.testing.model.StepKind
import com.indagium.testing.model.TestScript
import com.indagium.testing.model.newCheckId
import com.indagium.testing.store.MAX_STEP_TIMEOUT_MS
import com.indagium.testing.store.StoreResult

// The editors of what is new in a step: who performs it (an AI step, a tester or a script), the library script a step runs with its
// arguments, how a manual step is completed, and the device-state check. They take plain values and callbacks, so the step editor of a
// case, of a shared step and the recording review all use them; each caller persists the change its own way.

/** The badge a REPLAY step carries in the step list and in the run report. */
internal const val REPLAY_BADGE = "Replay"
private val KIND_LABELS = mapOf(StepKind.AGENT to "AI step", StepKind.MANUAL to "Manual", StepKind.SCRIPT to "Script", StepKind.REPLAY to REPLAY_BADGE)

/** Shown under the selector while Replay cannot be chosen: only a step made from a recording has the input to replay. */
internal const val REPLAY_UNAVAILABLE_HINT = "Replay needs recorded input: apply a recording to get it."
private val COMPLETION_LABELS = mapOf(
    ManualCompletion.TESTER_ONLY to "The tester confirms",
    ManualCompletion.CHECKS_ONLY to "Its checks detect it",
    ManualCompletion.TESTER_OR_CHECKS to "The tester or its checks",
)
private val COMPLETION_HINTS = mapOf(
    ManualCompletion.TESTER_ONLY to "The lane waits for the tester to press Done. Every check is verified afterwards.",
    ManualCompletion.CHECKS_ONLY to "Needs a log-appears or device-state check: the step is done as soon as they are true.",
    ManualCompletion.TESTER_OR_CHECKS to "Done when the tester presses Done, or as soon as its log-appears and device-state checks are true.",
)
private val EXPECTATION_FIELD_WIDTH = 110.dp
private val KEY_MENU_WIDTH = 180.dp
private val WITHIN_FIELD_WIDTH = 110.dp
private val SCRIPT_MENU_WIDTH = 240.dp
private const val DEFAULT_DEVICE_STATE_REGEX = ".+"

/** Small controls side by side that wrap onto further lines when the pane is narrow (the 300dp inspector). */
@Composable
internal fun WrapRow(content: @Composable () -> Unit) {
    FlowRow(
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
        itemVerticalAlignment = Alignment.CenterVertically,
    ) { content() }
}

internal fun stepKindLabel(kind: StepKind): String = KIND_LABELS.getValue(kind)

/**
 * The kinds as a segmented control. Replay is enabled only when [replayAvailable] (the step has replayable recorded input); the hint
 * below then says why it is not, so a disabled option is never a mystery.
 */
@Composable
internal fun StepKindSelector(kind: StepKind, enabled: Boolean, replayAvailable: Boolean = true, onSelect: (StepKind) -> Unit) {
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(2.dp)) {
        // Wraps in a narrow inspector (the four buttons and the label are about 340dp together).
        WrapRow {
            AppText("Performed by", color = tc().td, fontSize = 10.sp)
            StepKind.entries.forEach { option ->
                AppButton(
                    KIND_LABELS.getValue(option),
                    onClick = { if (option != kind) onSelect(option) },
                    enabled = enabled && (option != StepKind.REPLAY || replayAvailable || kind == StepKind.REPLAY),
                    variant = if (option == kind) ButtonVariant.Primary else ButtonVariant.Secondary,
                )
            }
        }
        if (!replayAvailable && kind != StepKind.REPLAY) TestsHint(REPLAY_UNAVAILABLE_HINT)
    }
}

/** What the kind means, in one sentence under the selector. */
internal fun stepKindHint(kind: StepKind): String = when (kind) {
    StepKind.AGENT -> "An AI agent chooses actions to achieve the expected result. Recorded inputs are hints; use Replay to execute the recorded sequence."
    StepKind.MANUAL -> "A tester does it by hand: the lane waits, showing the action as instructions. An automation script can do it instead."
    StepKind.SCRIPT -> "The run itself runs a library script on this step. No AI agent is involved."
    StepKind.REPLAY -> "Replays recorded positions, path and timing where the device supports them. Configured checks and judge settings verify the result."
}

/** How a manual step is completed, with a sentence about what each choice does. */
@Composable
internal fun ManualCompletionPicker(value: ManualCompletion, enabled: Boolean, onChange: (ManualCompletion) -> Unit) {
    TestsLabeled("Completed by") {
        TestsDropdown(
            selectedLabel = COMPLETION_LABELS.getValue(value),
            options = ManualCompletion.entries,
            optionLabel = { COMPLETION_LABELS.getValue(it) },
            onSelect = onChange,
            enabled = enabled,
            isSelected = { it == value },
            menuWidth = SCRIPT_MENU_WIDTH,
        )
    }
    TestsHint(COMPLETION_HINTS.getValue(value))
}

/**
 * The script a step runs: a picker over the library (scripts limited to setup and teardown are left out), a field for each of the script's
 * parameters, and what the run expects of its result. [onNewScript] creates a script and opens the script editor; [optional] adds a
 * Remove button (the automation alternative of a manual step may be absent).
 */
@Composable
internal fun ScriptCallEditor(
    call: ScriptCall?,
    enabled: Boolean,
    optional: Boolean,
    onChange: (ScriptCall?) -> StoreResult<*>,
    onNewScript: () -> Unit,
    chooseLabel: String = "Choose a script",
) {
    val ui = LocalTestsUi.current
    val scripts = ui.library.scripts
    val selected = call?.let { chosen -> scripts.firstOrNull { it.id == chosen.scriptId } }
    val offered = scripts.filter { it.permission != ScriptPermission.SETUP_TEARDOWN_ONLY || it.id == call?.scriptId }
    WrapRow {
        TestsDropdown(
            selectedLabel = when {
                call == null -> chooseLabel
                selected == null -> "(missing script)"
                else -> selected.toolName
            },
            options = offered,
            optionLabel = { it.toolName },
            onSelect = { script -> ui.report(onChange(ScriptCall(script.id, defaultArgsFor(script)))) },
            enabled = enabled,
            isSelected = { it.id == call?.scriptId },
            menuWidth = SCRIPT_MENU_WIDTH,
            emptyText = "No script yet: create one",
        )
        AppButton("+ New script", onClick = onNewScript, enabled = enabled, variant = ButtonVariant.Ghost)
        if (optional && call != null) AppButton("Remove script", onClick = { ui.report(onChange(null)) }, enabled = enabled, variant = ButtonVariant.Ghost)
    }
    if (call == null) return
    if (selected == null) {
        TestsErrorText("This script is not in the library any more. Choose another one.")
        return
    }
    if (selected.permission == ScriptPermission.ASK) TestsHint("This script asks for your permission each time the run reaches the step.")
    selected.params.forEach { param -> ScriptArgumentField(call, param, enabled, onChange) }
    ScriptExpectations(call, enabled, onChange)
}

@Composable
private fun ScriptArgumentField(call: ScriptCall, param: ScriptParam, enabled: Boolean, onChange: (ScriptCall?) -> StoreResult<*>) {
    val label = param.name + if (param.required && param.defaultValue == null) " *" else ""
    TestsLabeled("$label (${param.type.name.lowercase()})") {
        CommitTextField(
            call.args[param.name].orEmpty(),
            { text ->
                // A required value with no default stays in the call (empty) so the call remains valid while it is being filled in.
                val keep = text.isNotEmpty() || (param.required && param.defaultValue == null)
                onChange(call.copy(args = if (keep) call.args + (param.name to text) else call.args - param.name))
            },
            enabled = enabled,
            placeholder = param.defaultValue?.let { "default: $it" } ?: param.description.ifBlank { "value" },
            mono = true,
        )
    }
}

@Composable
private fun ScriptExpectations(call: ScriptCall, enabled: Boolean, onChange: (ScriptCall?) -> StoreResult<*>) {
    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        TestsLabeled("Expected exit code", Modifier.width(EXPECTATION_FIELD_WIDTH + 30.dp)) {
            CommitTextField(
                value = call.exitCode?.toString().orEmpty(),
                onCommit = { text ->
                    val trimmed = text.trim()
                    val code = trimmed.toIntOrNull()
                    if (trimmed.isNotEmpty() && code == null) {
                        StoreResult.Invalid("Exit code must be a whole number, or empty for any.")
                    } else {
                        onChange(call.copy(exitCode = code))
                    }
                },
                enabled = enabled,
                placeholder = "any",
            )
        }
        TestsLabeled("Output must contain", Modifier.width(EXPECTATION_FIELD_WIDTH * 2)) {
            CommitTextField(
                call.stdoutContains.orEmpty(),
                { text -> onChange(call.copy(stdoutContains = text.ifEmpty { null })) },
                enabled = enabled,
                placeholder = "optional",
                mono = true,
            )
        }
    }
}

/** A device-state check: which property of the device, what it must match and for how long it is watched. */
@Composable
internal fun DeviceStateCheckBody(check: StepCheck.DeviceState, enabled: Boolean, onChange: (StepCheck.DeviceState) -> StoreResult<*>) {
    WrapRow {
        TestsBadge("Device state")
        TestsDropdown(
            selectedLabel = check.key.ifBlank { "Choose a property" },
            options = (KNOWN_DEVICE_STATE_KEYS + check.key).filter(String::isNotBlank).distinct(),
            optionLabel = { it },
            onSelect = { key -> onChange(check.copy(key = key)) },
            enabled = enabled,
            isSelected = { it == check.key },
            menuWidth = KEY_MENU_WIDTH,
        )
        AppText("within ms", color = tc().td, fontSize = 10.sp)
        CommitNumberField(check.withinMs, 1L, MAX_STEP_TIMEOUT_MS, { n -> onChange(check.copy(withinMs = n)) }, Modifier.width(WITHIN_FIELD_WIDTH), enabled)
    }
    DEVICE_STATE_KEY_HINTS[check.key]?.let { TestsHint(it) }
    CommitTextField(
        check.expectedRegex,
        { text -> onChange(check.copy(expectedRegex = text)) },
        enabled = enabled,
        placeholder = "regular expression its value must match, such as 0781:5581",
        mono = true,
    )
}

/** A device-state check with the placeholder text a person edits next. */
internal fun newDeviceStateCheck(): StepCheck.DeviceState =
    StepCheck.DeviceState(newCheckId(), KNOWN_DEVICE_STATE_KEYS.first(), DEFAULT_DEVICE_STATE_REGEX)

/** The step's completion settings as a block: what the tester sees, how it is completed and the optional automation script. */
@Composable
internal fun ManualStepFields(
    completion: ManualCompletion,
    script: ScriptCall?,
    enabled: Boolean,
    onCompletion: (ManualCompletion) -> Unit,
    onScript: (ScriptCall?) -> StoreResult<*>,
    onNewScript: () -> Unit,
) {
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        ManualCompletionPicker(completion, enabled, onCompletion)
        TestsLabeled("Automation script (optional)") {
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                ScriptCallEditor(script, enabled, optional = true, onChange = onScript, onNewScript = onNewScript)
                if (script == null) TestsHint("A run set to use scripts runs it instead of waiting for the tester; if it fails the lane still waits.")
            }
        }
    }
}

/** The arguments a freshly chosen script starts with: an empty value of the right type for each required parameter that has no default. */
internal fun defaultArgsFor(script: TestScript): Map<String, String> = script.params
    .filter { it.required && it.defaultValue == null }
    .associate { param ->
        param.name to when (param.type) {
            ScriptParamType.STRING -> ""
            ScriptParamType.INT -> "0"
            ScriptParamType.BOOL -> "false"
        }
    }

/** A script created from a step: a placeholder command to edit, returned as the step's script. Null when the library refused it. */
internal fun createScriptForStep(ui: TestsUi, stepId: String): TestScript? {
    val script = createPlaceholderScript(ui) ?: return null
    ui.view.openScriptEditorFor(script.id, stepId)
    return script
}

/**
 * The kind chooser of an author-added row of the recording review (recorded inputs stay AI steps): the same controls a step has, kept
 * on the row until the recording is applied. A script row starts with the first script the library offers.
 */
@Composable
internal fun RecordedRowKindEditor(session: TestStepRecordingSession, row: RecordedTestStep, enabled: Boolean) {
    val ui = LocalTestsUi.current

    fun edit(transform: (RecordedTestStep) -> RecordedTestStep): StoreResult<*> {
        updateRecorded(session, row.id, transform)
        return StoreResult.Ok(Unit)
    }
    val addScript: () -> Unit = {
        createScriptForStep(ui, row.id)?.let { script -> edit { it.copy(script = ScriptCall(script.id, defaultArgsFor(script))) } }
    }
    StepKindSelector(row.stepKind, enabled, replayAvailable = false) { kind ->
        val starting = if (kind == StepKind.SCRIPT && row.script == null) {
            ui.library.scripts.firstOrNull { it.permission != ScriptPermission.SETUP_TEARDOWN_ONLY }?.let { ScriptCall(it.id, defaultArgsFor(it)) }
        } else {
            row.script
        }
        edit { it.copy(stepKind = kind, script = starting.takeIf { kind != StepKind.AGENT }, optional = false, condition = null) }
    }
    TestsHint(stepKindHint(row.stepKind))
    when (row.stepKind) {
        StepKind.AGENT, StepKind.REPLAY -> Unit
        StepKind.SCRIPT -> TestsLabeled("Script to run") {
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                ScriptCallEditor(row.script, enabled, optional = false, onChange = { call -> edit { it.copy(script = call) } }, onNewScript = addScript)
            }
        }
        StepKind.MANUAL -> {
            ManualStepFields(
                completion = row.manualCompletion,
                script = row.script,
                enabled = enabled,
                onCompletion = { mode -> edit { it.copy(manualCompletion = mode) } },
                onScript = { call -> edit { it.copy(script = call) } },
                onNewScript = addScript,
            )
            RecordedDeviceStateChecks(row, enabled, ::edit)
        }
    }
}

@Composable
internal fun RecordedDeviceStateChecks(row: RecordedTestStep, enabled: Boolean, edit: ((RecordedTestStep) -> RecordedTestStep) -> StoreResult<*>) {
    TestsSectionTitle("Device-state checks (${row.checks.count { it is StepCheck.DeviceState }})") {
        AppButton("+ Add check", onClick = { edit { it.copy(checks = it.checks + newDeviceStateCheck()) } }, enabled = enabled, variant = ButtonVariant.Ghost)
    }
    row.checks.filterIsInstance<StepCheck.DeviceState>().forEach { check ->
        Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            DeviceStateCheckBody(check, enabled) { changed ->
                edit { current -> current.copy(checks = current.checks.map { if (it.id == check.id) changed else it }) }
            }
            AppButton(
                "Remove check",
                onClick = { edit { current -> current.copy(checks = current.checks.filterNot { it.id == check.id }) } },
                enabled = enabled,
                variant = ButtonVariant.Ghost,
            )
        }
    }
}
