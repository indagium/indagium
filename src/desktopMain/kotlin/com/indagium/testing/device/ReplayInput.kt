package com.indagium.testing.device

import com.indagium.debug.DEVICE_KEY_CODES
import com.indagium.testing.model.RecordedInput
import com.indagium.testing.model.StepIntent
import com.indagium.testing.model.TestStep
import com.indagium.testing.model.problem

// What a REPLAY step may contain: the keys `press_key` knows and the panels `open_system_panel` knows. Shared by the library validation
// (a step cannot be saved as REPLAY without replayable input), the editors (the Replay option is enabled only when this is null) and the
// run, so the three cannot disagree.

internal val REPLAYABLE_KEY_NAMES: Set<String> = DEVICE_KEY_CODES.keys

internal val REPLAYABLE_PANEL_NAMES: Set<String> = SystemPanel.entries.map { it.toolValue }.toSet()

/** The first reason [input] cannot be replayed, or null. */
internal fun replayProblemOf(input: RecordedInput?): String? =
    input?.problem(REPLAYABLE_KEY_NAMES, REPLAYABLE_PANEL_NAMES) ?: if (input == null) NO_RECORDED_INPUT_MESSAGE else null

/** Why this step cannot be a REPLAY step right now (it has no replayable recorded input), or null when it can. */
internal fun TestStep.replayProblem(): String? = replayProblemOf(recordedInput)

/** Replay cannot inspect an optional condition, ensure a state before acting, or establish a stated precondition. */
internal fun TestStep.replayEligibilityProblem(): String? = when {
    optional -> "A replay step cannot be optional."
    intent != StepIntent.PERFORM_ACTION -> "A replay step must perform an action; it cannot ensure a state before acting."
    !precondition.isNullOrBlank() -> "A replay step cannot establish a stated precondition."
    else -> replayProblem()
}

internal const val NO_RECORDED_INPUT_MESSAGE = "A replay step needs the input it was recorded from; apply a recording to get it."
