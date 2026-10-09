package com.indagium.testing.authoring

// What an expected result may and may not require, for the AI that writes steps (the recording rewrite and the step draft) and for
// the review panel that warns about a result which is likely to flap. A test is run many times on many devices: an expected
// result that names whatever happened to be on screen while it was recorded (an ad that played, a video title, a view count,
// picture-in-picture) fails on the next run for reasons that are not the app's. The prompt rule asks the AI not to write them; the
// check below is the deterministic backstop that flags (never rejects) the ones that slip through.

/** The rule, as one paragraph of an AI prompt: the stable outcome only, and the kinds of content that must never be required. */
internal const val STABLE_EXPECTED_RULE =
    "Expected results describe the stable, intended outcome of the action: what is true on every run. Never require incidental or " +
        "nondeterministic content such as advertisements or sponsored content, specific video titles, thumbnails or channels, counts " +
        "or numbers that vary (views, likes, durations), timestamps, notifications, picture-in-picture or background playback, " +
        "animations, or the content of recommendations and feeds."

/** What a rewrite is told to do with such content when it seems important: say so in notes, or make it a conditional optional step. */
internal const val STABLE_EXPECTED_REWRITE_REMEDY =
    "If such content seems important, mention it in notes, or propose a separate step marked optional with a condition " +
        "(for example 'Skip the ad' with condition 'A skippable ad is visible')."

/** What a step draft is told to do with such content: a draft has no notes and no optional steps, so it leaves the content out. */
internal const val STABLE_EXPECTED_DRAFT_REMEDY = "If such content matters to the request, leave it out of the expected result."

/** The review warning of a row whose expected result looks like it depends on content that changes between runs. */
internal const val INCIDENTAL_EXPECTED_WARNING = "Check this expected result: it may depend on content that changes between runs."

private val INCIDENTAL_EXPECTED_PATTERNS: List<Regex> = listOf(
    Regex("\\b(?:sponsored|adverts?|advertisements?|ads?)\\b", RegexOption.IGNORE_CASE),
    Regex("\\bpicture[- ]in[- ]picture\\b", RegexOption.IGNORE_CASE),
    // The abbreviation only in its usual spellings, so a word that merely contains "pip" is left alone.
    Regex("\\b(?:PiP|PIP)\\b"),
    // The notification shade and the notifications settings are stable screens; a notification that arrived is not.
    Regex("\\bnotifications?\\b(?!\\s+(?:shade|panel|drawer|tray|area|settings))", RegexOption.IGNORE_CASE),
    // "1.2M views" or "view count", not "the list view" or "views of the room".
    Regex("\\d[\\d.,]*\\s*[kmb]?\\s+views\\b|\\bview counts?\\b", RegexOption.IGNORE_CASE),
    Regex("\\b\\d{1,2}:\\d{2}(?::\\d{2})?\\b"),
)

/** True when [expected] names content that usually differs from run to run (see [INCIDENTAL_EXPECTED_PATTERNS]). */
internal fun looksIncidental(expected: String): Boolean = INCIDENTAL_EXPECTED_PATTERNS.any { it.containsMatchIn(expected) }

/**
 * The review warning for a step's [expected] result, or null. An optional step is exempt: a conditional "Skip the ad" step is the
 * one place an ad belongs, and it is only performed when its condition holds.
 */
internal fun incidentalExpectedWarning(expected: String, optional: Boolean): String? =
    INCIDENTAL_EXPECTED_WARNING.takeIf { !optional && looksIncidental(expected) }

/**
 * The review reason a reviewed row keeps after its expected result was edited to [expected]. A reason about a blank expected result
 * stays until the reviewer fills it in; the incidental warning follows the new text; any other reason ends with the edit.
 */
internal fun reviewReasonAfterEdit(current: String?, expected: String, optional: Boolean): String? = when {
    expected.isBlank() -> current
    current == INCIDENTAL_EXPECTED_WARNING -> incidentalExpectedWarning(expected, optional)
    else -> null
}
