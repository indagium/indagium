package com.indagium.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.indagium.testing.authoring.EVENT_SCRIPT_PLACEHOLDER_COMMAND
import com.indagium.testing.authoring.EnvironmentEvent
import com.indagium.testing.authoring.RecordedTestStep
import com.indagium.testing.authoring.TestStepRecordingSession
import com.indagium.testing.authoring.runScript
import com.indagium.testing.authoring.scriptSuggestion
import com.indagium.testing.authoring.summary
import com.indagium.testing.authoring.waitForTester
import com.indagium.testing.model.ScriptCall
import com.indagium.testing.model.StepKind
import com.indagium.testing.model.TestScript
import com.indagium.testing.store.StoreResult

// What the recording review shows for something that happened to the device while recording (a USB device plugged in, Bluetooth turned
// off): who does it when the test runs. Wait for a tester, or run a library script (with its arguments); a script can be created on
// the spot, named and described after the event, with a command that fails until the person writes the real one. The row keeps its
// device-state check, so a run can tell the step is done. A script step can keep the tester as a fallback.

/**
 * The chooser of an external-event row: **Wait for tester** or **Run script**. Choosing a script makes the row a script step; "keep
 * manual fallback" makes it a manual step with the script as its automation alternative instead.
 */
@Composable
internal fun ExternalEventRowEditor(session: TestStepRecordingSession, row: RecordedTestStep, enabled: Boolean) {
    val ui = LocalTestsUi.current
    val event = row.stateChanges.firstOrNull()

    fun edit(transform: (RecordedTestStep) -> RecordedTestStep): StoreResult<*> {
        updateRecorded(session, row.id, transform)
        return StoreResult.Ok(Unit)
    }
    val keepFallback = row.script != null && row.stepKind == StepKind.MANUAL
    val newScript: () -> Unit = {
        event?.let { createScriptForEvent(ui, it, row.id) }?.let { script ->
            edit { it.runScript(ScriptCall(script.id, defaultArgsFor(script)), keepFallback) }
        }
    }
    Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
        AppText("Who does it when the test runs", color = tc().td, fontSize = 10.sp)
        AppButton(
            "Wait for tester",
            onClick = { edit { it.waitForTester() } },
            enabled = enabled,
            variant = if (row.script == null) ButtonVariant.Primary else ButtonVariant.Secondary,
        )
    }
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        ScriptCallEditor(
            call = row.script,
            enabled = enabled,
            optional = false,
            onChange = { call -> edit { if (call == null) it.waitForTester() else it.runScript(call, keepFallback) } },
            onNewScript = newScript,
            chooseLabel = "Run script ▾",
        )
        if (row.script != null) {
            CheckRow(checked = keepFallback, onToggle = { edit { it.runScript(requireNotNull(it.script), !keepFallback) } }, enabled = enabled) {
                AppText("Keep manual fallback: wait for a tester when the script does not do it", color = tc().ts, fontSize = 10.sp)
            }
        }
    }
    TestsHint(
        if (row.script == null) {
            "A run waits for a tester to do this. Its device-state check lets the run notice it is done."
        } else if (keepFallback) {
            "A run uses the script when it is set to, and waits for a tester if the script fails."
        } else {
            "A run runs the script, then verifies the device-state check."
        },
    )
    if (row.stepKind == StepKind.MANUAL) ManualCompletionPicker(row.manualCompletion, enabled) { mode -> edit { it.copy(manualCompletion = mode) } }
    RecordedDeviceStateChecks(row, enabled, ::edit)
}

/**
 * A library script for [event]: named and described after it, with a placeholder command that fails until the person writes the real
 * one (no command is invented). Opens the script editor and returns the script, or null when the library refused it.
 */
internal fun createScriptForEvent(ui: TestsUi, event: EnvironmentEvent, rowId: String): TestScript? {
    val suggestion = event.scriptSuggestion()
    val name = uniqueIdentifier(suggestion.toolName, ui.library.scripts.map { it.toolName })
    val draft = TestScript(id = "", toolName = name, description = suggestion.description, commandTemplate = EVENT_SCRIPT_PLACEHOLDER_COMMAND)
    val script = (ui.report(ui.state.createTestScript(draft)) as? StoreResult.Ok)?.value ?: return null
    ui.view.openScriptEditorFor(script.id, rowId)
    return script
}

/** The device changes that followed an AI step while recording, with a shortcut to use them as its expected result. */
@Composable
internal fun RecordedStateChangesHint(session: TestStepRecordingSession, row: RecordedTestStep, enabled: Boolean) {
    val text = row.stateChanges.joinToString("; ") { it.summary() }
    TestsHint("On the device after this step: $text.")
    if (row.expected.isBlank()) {
        AppButton(
            "Use as the expected result",
            onClick = { updateRecorded(session, row.id) { it.copy(expected = text) } },
            enabled = enabled,
            variant = ButtonVariant.Ghost,
        )
    }
}
