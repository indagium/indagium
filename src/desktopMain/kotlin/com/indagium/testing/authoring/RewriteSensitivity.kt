package com.indagium.testing.authoring

// Which parts of a recording the rewrite must not show an AI. One rule for the video storyboard and for the screenshots that go
// with the first message: a password that was typed is hidden in the rows ("••••"), and the screens around it are withheld too.

/** What a hidden password reads as in a row's action (TestStepRecordingSession.MASKED_TEXT). */
internal const val REWRITE_MASKED_TEXT = "••••"

/**
 * How long before the first password input the window opens: the screen read before it, keyboard taps that started the typing burst
 * and a frame held over from just before all fall inside it.
 */
internal const val PASSWORD_WINDOW_LEAD_MS = 3_000L

/**
 * The wall-clock windows in which the screen may show a password: from [PASSWORD_WINDOW_LEAD_MS] before the first password input
 * (or that input's earlier screen read) until the first screen after the last password input that proves the password screen is
 * gone. When the recorder never proves that, everything up to [recordingEndMs] is withheld. Inputs well before the password (open
 * the app, tap Login) keep their screens. Empty when no input touched a password field.
 */
internal fun rewriteSensitiveIntervals(inputs: List<RewriteInput>, recordingEndMs: Long): List<LongRange> {
    val passwordInputs = inputs.indices.filter { index -> inputs[index].touchesPassword() }
    if (passwordInputs.isEmpty()) return emptyList()
    val firstSensitiveAt = passwordInputs.minOf { index ->
        val input = inputs[index]
        minOf(input.row.inputStartMs, input.before?.startedAt ?: Long.MAX_VALUE)
    }
    val windowStart = if (firstSensitiveAt <= Long.MIN_VALUE + PASSWORD_WINDOW_LEAD_MS) Long.MIN_VALUE else firstSensitiveAt - PASSWORD_WINDOW_LEAD_MS
    val lastSensitiveAt = passwordInputs.maxOf { inputs[it].row.inputAtMs }
    val passwordScreens = passwordInputs.flatMap { index ->
        listOfNotNull(inputs[index].before, inputs[index].after)
            .filter(RecordingScreenState::passwordFieldLikelyEdited)
            .map { it.packageName to it.activity }
    }.toSet()
    val safeExit = inputs.asSequence().flatMap { input -> sequenceOf(input.before, input.after) }
        .filterNotNull()
        .filter { state ->
            val location = state.packageName to state.activity
            state.finishedAt >= lastSensitiveAt && state.nodes.isNotEmpty() && state.nodes.none { it.password } &&
                location.first != null && location.second != null && location !in passwordScreens
        }
        .minOfOrNull { it.finishedAt }
    return listOf(windowStart..(safeExit ?: recordingEndMs))
}

/** True when this input typed into a password field: its action is masked or a screen around it shows a password field being edited. */
private fun RewriteInput.touchesPassword(): Boolean =
    row.action.contains(REWRITE_MASKED_TEXT) || before?.passwordFieldLikelyEdited() == true || after?.passwordFieldLikelyEdited() == true

/** The rewrite's view of which inputs and screenshots are password-sensitive; see [rewriteSensitiveIntervals]. */
internal class RewriteSensitivity(inputs: List<RewriteInput>) {
    private val intervals = rewriteSensitiveIntervals(inputs.filterNot { it.fixed }, Long.MAX_VALUE)

    /** True for an input that typed a hidden password or whose own time lies in a sensitive window: it gets no image and no screen text. */
    fun inputWithheld(input: RewriteInput): Boolean =
        input.touchesPassword() || inWindow(input.row.inputStartMs) || inWindow(input.row.inputAtMs)

    /** True for a screenshot taken in a sensitive window; one without a time is judged by the input it belongs to ([fallbackMs]). */
    fun screenshotWithheld(evidence: RecordingScreenshotEvidence, fallbackMs: Long): Boolean {
        val times = listOfNotNull(evidence.acquiredAtMs, evidence.acquisitionFinishedAtMs).ifEmpty { listOf(fallbackMs) }
        return times.any(::inWindow)
    }

    private fun inWindow(wallClockMs: Long): Boolean = intervals.any { wallClockMs in it }
}
