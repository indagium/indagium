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
        "animations, or the content of recommendations and feeds. Transient UI that hides by itself (playback controls, toasts, " +
        "snackbars) is not a stable expected result either: describe the state that remains, not the control that flashed up."

/** What a rewrite is told to do with such content when it seems important: say so in notes, or make it a conditional optional step. */
internal const val STABLE_EXPECTED_REWRITE_REMEDY =
    "If such content seems important, mention it in notes, or propose a separate step marked optional with a condition " +
        "(for example 'Skip the ad' with condition 'A skippable ad is visible')."

/** What a step draft is told to do with such content: a draft has no notes and no optional steps, so it leaves the content out. */
internal const val STABLE_EXPECTED_DRAFT_REMEDY = "If such content matters to the request, leave it out of the expected result."

/** The review warning of a row whose expected result looks like it depends on content that changes between runs. */
internal const val INCIDENTAL_EXPECTED_WARNING = "Check this expected result: it may depend on content that changes between runs."

/** The review warning of a row whose action quotes a specific item (a long title, a title with emoji) that may differ next run. */
internal const val SPECIFIC_ACTION_WARNING =
    "Check this action: it names a specific item that may differ next run. Generalize it unless the test needs that exact item."

/** Both warnings on one row, in the order the row's fields appear. */
internal const val SPECIFIC_ACTION_AND_INCIDENTAL_WARNING = "$SPECIFIC_ACTION_WARNING $INCIDENTAL_EXPECTED_WARNING"

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
    // Controls and messages that hide by themselves are not there when the next run looks.
    Regex("\\b(?:toasts?|snackbars?)\\b", RegexOption.IGNORE_CASE),
    Regex("\\b(?:playback|player|video|media) controls?\\b", RegexOption.IGNORE_CASE),
)

/** True when [expected] names content that usually differs from run to run (see [INCIDENTAL_EXPECTED_PATTERNS]). */
internal fun looksIncidental(expected: String): Boolean = INCIDENTAL_EXPECTED_PATTERNS.any { it.containsMatchIn(expected) }

private val QUOTED_SEGMENTS: List<Regex> = listOf(Regex("\"([^\"]+)\""), Regex("\u201C([^\u201D]+)\u201D"))

// Typing text is test data the author chose on purpose: "Type "how to bake bread" in the search box" is not a specific item to generalize.
private val TYPING_ACTION = Regex("^\\s*(?:type|enter|input|write|fill(?:\\s+in)?|paste|search\\s+for)\\b", RegexOption.IGNORE_CASE)
private val TITLED_ITEM = Regex("\\b(?:titled|entitled)\\b", RegexOption.IGNORE_CASE)
private const val LONG_QUOTED_CHARS = 40
private const val LONG_QUOTED_WORDS = 6

// Pictographs (the supplementary-plane emoji blocks), the misc symbols and dingbats blocks, star, heavy circle and the emoji variation selector.
private val EMOJI_CODE_POINTS: List<IntRange> =
    listOf(0x1F000..0x1FAFF, 0x2600..0x27BF, 0x2B50..0x2B50, 0x2B55..0x2B55, 0xFE0F..0xFE0F)

private fun String.hasEmoji(): Boolean = codePoints().anyMatch { cp -> EMOJI_CODE_POINTS.any { cp in it } }

/**
 * True when [action] quotes a specific item: a long title (a media title that happened to be on screen while recording), or quoted
 * text with emoji in it, or something "titled …". A short quoted label ("Sign in") and typed text are the author's own and pass.
 */
internal fun looksSpecificAction(action: String): Boolean {
    if (TYPING_ACTION.containsMatchIn(action)) return false
    if (TITLED_ITEM.containsMatchIn(action)) return true
    return QUOTED_SEGMENTS.any { pattern ->
        pattern.findAll(action).any { match ->
            val quoted = match.groupValues[1].trim()
            val words = quoted.split(Regex("\\s+")).count { it.isNotEmpty() }
            quoted.length >= LONG_QUOTED_CHARS || words >= LONG_QUOTED_WORDS || (quoted.hasEmoji() && words >= 2)
        }
    }
}

/** The review warning for a step's [action], or null. */
internal fun specificActionWarning(action: String): String? = SPECIFIC_ACTION_WARNING.takeIf { looksSpecificAction(action) }

/** True for the reason a row carries when only a deterministic check flagged it (never a model's reason): a flag, not a blocker. */
internal fun isSoftReviewWarning(reason: String?): Boolean =
    reason == INCIDENTAL_EXPECTED_WARNING || reason == SPECIFIC_ACTION_WARNING || reason == SPECIFIC_ACTION_AND_INCIDENTAL_WARNING

/** The soft warning a step's [action] and [expected] result earn together: the action's, the expected result's, both or none. */
internal fun softReviewWarning(action: String, expected: String, optional: Boolean): String? {
    val actionWarning = specificActionWarning(action)
    val expectedWarning = incidentalExpectedWarning(expected, optional)
    return when {
        actionWarning != null && expectedWarning != null -> SPECIFIC_ACTION_AND_INCIDENTAL_WARNING
        else -> actionWarning ?: expectedWarning
    }
}

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

/** [reviewReasonAfterEdit] for a row whose [action] may also carry a soft warning: any of the soft warnings follows the new text. */
internal fun reviewReasonAfterEdit(current: String?, action: String, expected: String, optional: Boolean): String? = when {
    expected.isBlank() -> current
    isSoftReviewWarning(current) -> softReviewWarning(action, expected, optional)
    else -> null
}
