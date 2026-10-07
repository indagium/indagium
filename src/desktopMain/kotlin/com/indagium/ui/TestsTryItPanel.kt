package com.indagium.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.indagium.capture.CaptureDevice
import com.indagium.testing.model.ScriptParam
import com.indagium.testing.model.ScriptParamType
import com.indagium.testing.model.ScriptTarget
import com.indagium.testing.model.TestScript
import com.indagium.testing.script.ScriptRunOutcome
import com.indagium.testing.script.ScriptRunResult
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

// The console of the Scripts screen: pick a device, fill the script's parameters, run it once and read what it
// printed. It runs exactly what an agent's tool call would (TestScriptRunner through AppState.tryTestScript), so
// "it worked here" means the script and its parameters are right. Closing the screen or pressing Stop kills it.

private val TRY_PANEL_PADDING = 10.dp
private const val OUTPUT_MIN_HEIGHT_DP = 70
private const val OUTPUT_MAX_HEIGHT_DP = 260
private const val BOOL_TRUE = "true"
private const val BOOL_FALSE = "false"
private const val NO_DEVICE_LABEL = "No device"
private const val CHOOSE_DEVICE_LABEL = "Choose a device"

@Composable
internal fun TryItPanel(script: TestScript) {
    val tc = tc()
    val ui = LocalTestsUi.current
    val devices = ui.state.captureService.devices.filter { it.available }
    var chosen by remember(script.id) { mutableStateOf<CaptureDevice?>(null) }
    val values = remember(script.id, script.params.map { it.name }) {
        mutableStateMapOf<String, String>().apply { script.params.forEach { put(it.name, it.defaultValue.orEmpty()) } }
    }
    var running by remember(script.id) { mutableStateOf(false) }
    var output by remember(script.id) { mutableStateOf("") }
    var job by remember(script.id) { mutableStateOf<Job?>(null) }
    val needsDevice = script.target == ScriptTarget.ADB_SHELL
    LaunchedEffect(script.id) { ui.state.captureService.refreshDevices() }
    DisposableEffect(script.id) { onDispose { job?.cancel() } }
    // A device that was unplugged since it was picked is no longer a valid choice.
    val device = chosen?.takeIf { picked -> devices.any { it.serial == picked.serial } }

    TestsSectionTitle("Try it")
    Column(
        Modifier.fillMaxWidth().border(1.dp, tc.br, CORNER_MD).background(tc.p2, CORNER_MD).padding(TRY_PANEL_PADDING),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        TestsHint("Runs the script once, the way an agent's tool call would. Nothing is saved.")
        TestsLabeled(if (needsDevice) "Device" else "Device (optional)") {
            TestsDropdown(
                selectedLabel = device?.let { "${it.model} (${it.serial})" } ?: if (needsDevice) CHOOSE_DEVICE_LABEL else NO_DEVICE_LABEL,
                options = if (needsDevice) devices else listOf<CaptureDevice?>(null) + devices,
                optionLabel = { it?.let { d -> "${d.model} (${d.serial})" } ?: NO_DEVICE_LABEL },
                onSelect = { chosen = it },
                isSelected = { it?.serial == device?.serial },
                emptyText = "No device is connected",
            )
        }
        script.params.forEach { param ->
            TestsLabeled(paramLabel(param)) {
                ParamValueField(param, values[param.name].orEmpty(), enabled = !running) { values[param.name] = it }
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            HintedButton(
                "Run",
                onClick = {
                    running = true
                    output = "Running…"
                    val arguments = values.filterValues { it.isNotEmpty() }.toMap()
                    job = ui.scope.launch {
                        try {
                            output = describeOutcome(ui.state.tryTestScript(script, arguments, device?.serial))
                        } catch (cancelled: CancellationException) {
                            output = "Stopped."
                            throw cancelled
                        } finally {
                            running = false
                        }
                    }
                },
                enabled = !running && (!needsDevice || device != null),
                disabledHint = if (running) "Already running" else "Choose a device first",
                variant = ButtonVariant.Primary,
            )
            if (running) HintedButton("Stop", onClick = { job?.cancel() }, isDanger = true)
        }
        if (output.isNotEmpty()) {
            ScrollableTextArea(
                value = output,
                onValue = {},
                modifier = Modifier.fillMaxWidth(),
                minHeight = OUTPUT_MIN_HEIGHT_DP.dp,
                maxHeight = OUTPUT_MAX_HEIGHT_DP.dp,
                enabled = false,
            )
        }
    }
}

private fun paramLabel(param: ScriptParam): String {
    val kind = param.type.name.lowercase()
    return if (param.required && param.defaultValue == null) "${param.name} ($kind, required)" else "${param.name} ($kind)"
}

@Composable
private fun ParamValueField(param: ScriptParam, value: String, enabled: Boolean, onValue: (String) -> Unit) {
    if (param.type == ScriptParamType.BOOL) {
        TestsDropdown(
            selectedLabel = value.ifEmpty { "(not set)" },
            options = listOf("", BOOL_TRUE, BOOL_FALSE),
            optionLabel = { it.ifEmpty { "(not set)" } },
            onSelect = onValue,
            enabled = enabled,
            isSelected = { it == value },
        )
    } else {
        InlineField(value = value, onValue = onValue, placeholder = param.description, modifier = Modifier.fillMaxWidth())
    }
}

private fun describeOutcome(outcome: ScriptRunOutcome): String = when (outcome) {
    is ScriptRunOutcome.Rejected -> outcome.message
    is ScriptRunOutcome.Finished -> describeResult(outcome.result)
}

private fun describeResult(result: ScriptRunResult): String = buildString {
    append(if (result.timedOut) "Timed out and was stopped" else "Exit code ${result.exitCode}")
    append(" after ${result.durationMs} ms")
    if (result.truncated) append(" (output was cut at the script's output cap)")
    result.warnings.forEach { append("\n\nWarning: ").append(it) }
    if (result.stdout.isNotEmpty()) append("\n\n--- output ---\n").append(result.stdout.trimEnd())
    if (result.stderr.isNotEmpty()) append("\n\n--- errors ---\n").append(result.stderr.trimEnd())
}
