package com.indagium.testing.authoring

// Which screen stands for "after" an input. The screen right after an action is often a splash, a spinner or a transition: an app
// icon was tapped and the app's loading screen is what an immediate screenshot shows. The result is judged from a later look, one
// taken after a settle window or the screen the person was looking at when the next input began. Without a video, that later look is
// the next input's "before" reading; with one, RewriteVideoEvidence asks the video for the last frame before the next input.

/** The result of an input is judged from a screen at least this long after it (or the last one before the next input). */
internal const val REWRITE_SETTLE_MS = 2_000L

/** A settled frame is taken at least this long before the next input starts, so the next input's own reaction is not in it. */
internal const val REWRITE_NEXT_INPUT_GUARD_MS = 50L

/**
 * The best "after" evidence of one input. [shot] is the screenshot to show (null when none was kept); [settled] is false only when it
 * is known to have been taken less than [REWRITE_SETTLE_MS] after the input, [gapMs] being how long after. [laterState] is the screen
 * reading from when the next input began, kept only when the input's own reading is missing or was taken too early to be the result.
 */
internal class SettledAfter(
    val shot: RecordingScreenshotEvidence?,
    val settled: Boolean,
    val gapMs: Long?,
    val laterState: RecordingScreenState?,
)

/**
 * The after evidence of [input]: its own after screenshot, unless that was taken before the settle window had passed and the next
 * input's before screenshot is a later look. [next] is the input that follows in time, or null (also null when it must not be shown).
 */
internal fun settledAfterOf(input: RewriteInput, next: RewriteInput?, sensitivity: RewriteSensitivity): SettledAfter {
    val end = input.row.inputAtMs
    val laterInput = next?.takeIf { !it.fixed && !sensitivity.inputWithheld(it) }
    val laterShot = laterInput?.screenshot("before")?.takeIf { !sensitivity.screenshotWithheld(it, laterInput.row.inputStartMs) }
    val shot = pickShot(input.screenshot("after"), laterShot, end)
    val gap = shot?.acquiredAtMs?.let { it - end }
    val own = input.after
    val laterState = laterInput?.before
        ?.takeIf { later -> !later.passwordFieldLikelyEdited() && later.startedAt > end && (own == null || own.startedAt - end < REWRITE_SETTLE_MS) }
        ?.takeIf { later -> later !== own }
    return SettledAfter(shot, shot != null && (gap == null || gap >= REWRITE_SETTLE_MS), gap, laterState)
}

private fun tooEarly(shot: RecordingScreenshotEvidence, inputEndMs: Long): Boolean = shot.acquiredAtMs?.let { it - inputEndMs < REWRITE_SETTLE_MS } == true

private fun pickShot(own: RecordingScreenshotEvidence?, later: RecordingScreenshotEvidence?, inputEndMs: Long): RecordingScreenshotEvidence? = when {
    own == null -> later?.takeIf { it.acquiredAtMs == null || it.acquiredAtMs > inputEndMs }
    later == null || later === own || !tooEarly(own, inputEndMs) -> own
    else -> later.takeIf { candidate -> own.acquiredAtMs?.let { ownAt -> candidate.acquiredAtMs?.let { it > ownAt } } == true } ?: own
}

/**
 * The wall-clock moment whose frame stands for the settled screen after [input]: the end of the settle window, or just before the next
 * input starts when that comes first, and never before the input itself ended.
 */
internal fun settledAtMs(input: RewriteInput, nextStartMs: Long?): Long {
    val end = input.row.inputAtMs
    val window = end + REWRITE_SETTLE_MS
    return maxOf(end, minOf(window, nextStartMs?.let { it - REWRITE_NEXT_INPUT_GUARD_MS } ?: window))
}
