package com.indagium.testing.authoring

import com.indagium.testing.model.DEFAULT_DEVICE_STATE_WITHIN_MS
import com.indagium.testing.model.DEFAULT_MANUAL_STEP_TIMEOUT_MS
import com.indagium.testing.model.ManualCompletion
import com.indagium.testing.model.ScriptCall
import com.indagium.testing.model.StepCheck
import com.indagium.testing.model.StepKind

// The review rows for "something happened to the device that no input explains": a USB device was plugged in, Bluetooth went off.
// Such a row is born as a MANUAL step ("Connect the USB device 'Ultra' to the phone") with a device-state check that notices it done,
// so a run can wait for a tester, or notice the tester finished. The reviewer can instead have a library script do it (a SCRIPT step,
// or a MANUAL step with the script as its automation alternative). An external row is never an AI step: nobody can ask an agent to
// plug a cable in. All of it is pure row editing; the session and the editor call it.

/** A recorded row whose performer a person may change: a step they added, or a detected external event. Recorded inputs stay AI steps. */
internal val RecordedTestStep.performerEditable: Boolean get() = authorAdded || kind == RecordedInputKind.EXTERNAL

internal val RecordedTestStep.isExternalEvent: Boolean get() = kind == RecordedInputKind.EXTERNAL

/** The review row for [event]: a manual step with the proposed instruction and check, the event kept as its evidence. */
internal fun externalEventRow(event: EnvironmentEvent): RecordedTestStep {
    val proposal = proposeExternalStep(event, DEFAULT_MANUAL_STEP_TIMEOUT_MS)
    return RecordedTestStep(
        action = proposal.action,
        screenContext = "Detected on the device while recording: ${event.summary()}. No input explains it, so it is proposed as a step " +
            "for a tester (or a script) to do.",
        kind = RecordedInputKind.EXTERNAL,
        inputAtMs = event.atMs,
        inputStartMs = event.atMs,
        stepKind = StepKind.MANUAL,
        checks = listOfNotNull(proposal.check),
        stateChanges = listOf(event),
    )
}

/** [this] row as "wait for a tester": a manual step without an automation script. */
internal fun RecordedTestStep.waitForTester(): RecordedTestStep =
    copy(stepKind = StepKind.MANUAL, script = null, checks = checks.retimed(DEFAULT_MANUAL_STEP_TIMEOUT_MS))

/**
 * [this] row run by [call]: a SCRIPT step, or, with [keepManualFallback], a MANUAL step with the script as its automation alternative
 * (a run waits for a tester when the script fails). The device-state check keeps noticing the result; its window follows the kind.
 */
internal fun RecordedTestStep.runScript(call: ScriptCall, keepManualFallback: Boolean): RecordedTestStep {
    val kind = if (keepManualFallback) StepKind.MANUAL else StepKind.SCRIPT
    val within = if (kind == StepKind.MANUAL) DEFAULT_MANUAL_STEP_TIMEOUT_MS else DEFAULT_DEVICE_STATE_WITHIN_MS
    return copy(stepKind = kind, script = call, checks = checks.retimed(within))
}

/** Device-state checks still on a default window (the manual timeout or the plain default) move to [withinMs]; a window the person set stays. */
private fun List<StepCheck>.retimed(withinMs: Long): List<StepCheck> = map { check ->
    if (check is StepCheck.DeviceState && check.withinMs in setOf(DEFAULT_MANUAL_STEP_TIMEOUT_MS, DEFAULT_DEVICE_STATE_WITHIN_MS)) {
        check.copy(withinMs = withinMs)
    } else {
        check
    }
}

/**
 * A change the review applies to who performs a row. [script] only counts when [setScript] (so "no change" and "clear it" differ). Only
 * rows a person may re-assign ([performerEditable]) accept one, and an external event never becomes an AI step.
 */
data class RecordedPerformerEdit(
    val stepKind: StepKind? = null,
    val setScript: Boolean = false,
    val script: ScriptCall? = null,
    val manualCompletion: ManualCompletion? = null,
    val checks: List<StepCheck>? = null,
) {
    /** Whether this edit may be applied to [row] at all. */
    internal fun allowedOn(row: RecordedTestStep): Boolean =
        row.performerEditable && !(row.isExternalEvent && stepKind == StepKind.AGENT)

    internal fun applyTo(row: RecordedTestStep): RecordedTestStep {
        val kind = stepKind ?: row.stepKind
        val agent = kind == StepKind.AGENT
        return row.copy(
            stepKind = kind,
            script = (if (setScript) script else row.script).takeIf { !agent },
            manualCompletion = manualCompletion ?: row.manualCompletion,
            checks = checks ?: row.checks,
            optional = row.optional && agent,
            condition = row.condition.takeIf { agent },
        )
    }
}

// ── A script for an event ────────────────────────────────────────────

/** The command a script created from an event starts with: it fails loudly, so a step never passes before someone wrote the real command. */
internal const val EVENT_SCRIPT_PLACEHOLDER_COMMAND = "echo 'Write the command for this script first' >&2; exit 1"

/** The name and description a script created from [this] event starts with. The command is never invented: see [EVENT_SCRIPT_PLACEHOLDER_COMMAND]. */
internal class NewScriptSuggestion(val toolName: String, val description: String)

private val SCRIPT_NAMES = mapOf(
    "usb.devices" to "usb_device",
    "bt.enabled" to "bluetooth",
    "bt.connected" to "bluetooth_device",
    "wifi.enabled" to "wifi",
    "airplane" to "airplane_mode",
    "mobile_data" to "mobile_data",
    "power.source" to "charger",
    "audio.devices" to "headset",
    "screen.awake" to "screen",
    "rotation" to "rotation",
)

internal fun EnvironmentEvent.scriptSuggestion(): NewScriptSuggestion {
    val direction = when {
        key in LIST_STATE_KEYS -> if (addedItems().isNotEmpty()) "connect" else "disconnect"
        key == "power.source" -> if (to == "none") "disconnect" else "connect"
        else -> if (to == "true") "enable" else if (to == "false") "disable" else "set"
    }
    return NewScriptSuggestion("${direction}_${SCRIPT_NAMES[key] ?: "device_state"}", "${instruction()}. Replace the placeholder command with the real one.")
}
