@file:Suppress("ReturnCount")

package com.indagium.diagram3

// ── Occurrence text -> Seq3Match ────────────────────────────────────────────────────────────
//
// This is what makes ×12 log lines ONE message instead of twelve: given a set of near-identical
// occurrence texts under one tag, produce a single [Seq3Match] whose `{name}` slots sit exactly
// at the runs that vary, proven against every occurrence before it is returned.
//
// Ported and adapted from `diagram/ManualMessageMatchCompiler.kt` (193 lines) — the compiler
// itself (named-value pattern first, single positional run as fallback, reject anything more
// ambiguous than that) is unchanged; what's adapted:
//   - no `tagPattern`/`tag` matching — a v3 [Seq3Match.tag] is always the exact source tag the
//     caller (Seq3Generator) already scanned occurrences under, never a regex to re-prove;
//   - the single anonymous positional capture gets a content-shaped generated name (`n`/`id`/
//     `value`) instead of the old compiler's hardcoded literal `"value"` — see
//     [anonymousCaptureName] — so a purely numeric run reads as `{n}` and a hex-ish run reads as
//     `{id}` in the label, per this phase's brief;
//   - a named value is only eligible to become a capture when at least one of its distinct
//     observed values falls OUTSIDE [GENERIC_NAMED_VALUES] — see [isGenericValueSet]. Two
//     occurrences differing only by `state=true`/`state=false` do not get a noisy `{state}` token;
//     they either merge some other way or stay separate messages, never silently mislabeled.
// `manualMessageDisplayTemplate` (`diagram/ManualDiagramMessageQueue.kt:193-239`) was read for
// the multi-occurrence label-templating idea, but not ported: in v3 the template lives directly
// on [Seq3Match]/[com.indagium.diagram3.Seq3Message.labelTemplate], there is no separate
// queue-row projection step to keep in sync with it.

/** One occurrence text handed to the tokenizer, keyed by a caller-stable id (Seq3Generator uses
 *  `LogEntry.id.toString()`) so [Seq3TokenizeResult.captureValuesByOccurrence] can be joined back
 *  to the right [Seq3Occurrence] afterwards. */
data class Seq3TokenizeInput(val occurrenceId: String, val text: String)

data class Seq3TokenizeResult(
    val match: Seq3Match? = null,
    val captureValuesByOccurrence: Map<String, Map<String, String>> = emptyMap(),
    val error: String? = null,
) {
    val compiled: Boolean get() = match != null && error == null
}

/**
 * Compiles [occurrences] (all belonging to one [tag]) into a single proven [Seq3Match], or reports
 * why it couldn't. Deliberately rejects ambiguous variation (more than one unanchored varying run)
 * rather than guessing a broad regex — a caller with a rejected result should fall back to one
 * literal message per occurrence (Seq3Generator does exactly this).
 */
fun tokenizeSeq3Messages(tag: String, occurrences: List<Seq3TokenizeInput>): Seq3TokenizeResult {
    if (occurrences.isEmpty()) return Seq3TokenizeResult(error = "At least one occurrence is required")
    if (occurrences.any { it.occurrenceId.isBlank() }) {
        return Seq3TokenizeResult(error = "Every occurrence must have a stable id")
    }
    if (occurrences.any { it.text.isEmpty() }) {
        return Seq3TokenizeResult(error = "Empty occurrence text cannot be tokenized")
    }

    val named = compileNamedValuePattern(tag, occurrences)
    val candidate = named ?: compileSingleRunPattern(tag, occurrences)
        ?: return Seq3TokenizeResult(
            error = "The selected occurrences have ambiguous variation; only merge occurrences whose stable text anchors agree",
        )

    val match = candidate.first
    val captureValues = candidate.second
    val failures = occurrences.any { input -> matchesText(match, input.text) == null }
    if (failures) {
        return Seq3TokenizeResult(error = "The inferred pattern does not exactly match every selected occurrence")
    }
    return Seq3TokenizeResult(match = match, captureValuesByOccurrence = captureValues)
}

/** The capture token names written in a durable match/label template. */
internal fun seq3CaptureTokenNames(template: String): List<String> =
    CAPTURE_TOKEN.findAll(template).map { it.groupValues[1] }.toList()

/**
 * Returns captured values when [text] is an exact match for [match]'s template, or null otherwise.
 * The token/declared-name check is intentional: silently associating a value with the wrong
 * capture name would be worse than refusing the match outright.
 */
fun matchesText(match: Seq3Match, text: String): Map<String, String>? {
    val tokenNames = seq3CaptureTokenNames(match.template)
    val declaredNames = match.captures.map { it.name }
    if (tokenNames.distinct().size != tokenNames.size || tokenNames.toSet() != declaredNames.toSet()) return null
    if (declaredNames.isEmpty()) return text.takeIf { it == match.template }?.let { emptyMap() }
    val pattern = buildString {
        var cursor = 0
        CAPTURE_TOKEN.findAll(match.template).forEach { token ->
            append(Regex.escape(match.template.substring(cursor, token.range.first)))
            append("(.+?)")
            cursor = token.range.last + 1
        }
        append(Regex.escape(match.template.substring(cursor)))
    }
    val result = Regex("^(?:$pattern)$", setOf(RegexOption.DOT_MATCHES_ALL)).matchEntire(text) ?: return null
    if (result.groupValues.size - 1 != tokenNames.size) return null
    val valuesByToken = tokenNames.mapIndexed { index, name -> name to result.groupValues[index + 1] }.toMap()
    return declaredNames.associateWith(valuesByToken::getValue)
}

// (PERF) Bounds on NAMED_VALUE's runs — see the four constants just below for why each exists and
// why each length was chosen. Same class of problem as utils/TextMatch.kt's regex-match budget
// (SEC-2): java.util.regex has no built-in step budget, so an unbounded run next to an ambiguous
// lookahead can backtrack character-by-character from EVERY findAll start position. Unlike
// TextMatch's case (an arbitrary user-authored pattern against arbitrary text, bounded generically
// by a wall-clock deadline), NAMED_VALUE is one fixed, hand-written pattern against log message
// text this file already understands the shape of — so the fix here is structural (possessive
// quantifiers + realistic length caps on THIS pattern) rather than a generic runtime budget.
//
// Measured: a "key=value <long delimiter-less tail>" line — the value's own greedy backtracking
// combined with the lookahead's unbounded scan for the next "=" turns one findAll call into O(tail
// length²) work (924ms at 2402 chars vs 29ms after this fix — see the plan this shipped from for
// the full table). Doesn't reproduce when the tail runs straight to end-of-string (the `\s*$`
// lookahead branch succeeds immediately, no scan needed), which is why short/delimiter-terminated
// lines were never seen to be slow.
private const val NAMED_KEY_MAX_CHARS = 64
private const val NAMED_VALUE_MAX_CHARS = 256
private const val NAMED_VALUE_LOOKAHEAD_KEY_MAX_CHARS = 64

// The bound that actually fixes the asymptotics: the lookahead's own key-scan run, `{0,64}+`
// possessive. A possessive quantifier never backtracks (commits to its match, right or wrong), so
// a failed lookahead now costs O(64) — not O(remaining line length) — regardless of what follows.
// Bounded to 64 (not just possessive) because a possessive quantifier still SCANS its full run
// before failing; capping the run length is what keeps that scan itself short. No real log key is
// remotely close to 64 characters, so this never rejects a genuine "another key=value pair follows"
// lookahead — it only shortens how far a FAILED lookahead has to look before giving up.
//
// Group 1 (the KEY capture) and group 3 (the VALUE capture) are bounded and possessive for the
// same reason, deliberately, not left unbounded-but-possessive: a possessive quantifier alone stops
// ONE attempt from backtracking, but findAll still retries the whole pattern from every subsequent
// start position, so an unbounded possessive run of identifier-only characters with no delimiter
// (e.g. a long dotted/hyphenated token, or minified data with no spaces) would still cost O(run
// length) PER start position inside that run — O(run length²) overall, the same shape of blowup
// this fix exists to close, just moved from the lookahead to the key/value scan itself. Bounding
// both closes that path too:
//   - KEY (64 chars): generous for any real tag/field name this app parses (including a dotted
//     Android class-style key like "screen.mode"); a genuinely longer key just isn't recognised as
//     a NAMED_VALUE key here — matchesText/compileSingleRunPattern still have a shot at the same
//     occurrences via the positional-run fallback, so this is graceful degradation, never data loss.
//   - VALUE (256 chars, unquoted branch only): the quoted branches (`"[^"]*"`/`'[^']*'`) are NOT
//     bounded — a long quoted string is exactly where genuinely long values live in practice (a
//     path, a URL, a JSON fragment) and quoting already gives the engine an unambiguous end anchor,
//     so it never backtracks the way the unquoted run does. Only the *unquoted* value run is capped:
//     256 comfortably covers every observed unquoted token shape (ids, hashes, enum names, short
//     paths) in this app's log content, and an unquoted run past that length is a low-confidence
//     "value" to begin with — this pattern declines it rather than silently truncating the capture
//     (a truncated capture would misreport the value; declining it falls back to the positional-run
//     compiler or a literal per-occurrence message, both of which carry the FULL text).
private val NAMED_VALUE = Regex(
    "([A-Za-z_][A-Za-z0-9_.-]{0,$NAMED_KEY_MAX_CHARS}+)\\s*+(=|:)\\s*+" +
        "(\"[^\"]*\"|'[^']*'|[^=,:;\\s)]{1,$NAMED_VALUE_MAX_CHARS}+)" +
        "(?=\\s++(?:[A-Za-z_][A-Za-z0-9_.-]{0,$NAMED_VALUE_LOOKAHEAD_KEY_MAX_CHARS}+\\s*+(?:=|:))|\\s*+$|[,;)])",
)
private val CAPTURE_TOKEN = Regex("\\{([A-Za-z_][A-Za-z0-9_]*)}")

// Values so generic that varying between them carries no real identity — see this file's own
// header. Deliberately a small, focused list (not `Seq3Correlation`'s own denylist, which exists
// for a different purpose: rejecting weak CORRELATION evidence, not gating tokenizer captures).
private val GENERIC_NAMED_VALUES = setOf("true", "false", "0", "null", "none", "unknown", "")

private fun isGenericValueSet(values: Set<String>): Boolean = values.all { it.lowercase() in GENERIC_NAMED_VALUES }

private fun compileNamedValuePattern(
    tag: String,
    occurrences: List<Seq3TokenizeInput>,
): Pair<Seq3Match, Map<String, Map<String, String>>>? {
    val matches = occurrences.map { NAMED_VALUE.findAll(it.text).toList() }
    if (matches.any { it.isEmpty() }) return null
    val firstKeys = matches.first().map { it.groupValues[1] }
    if (matches.any { it.map { part -> part.groupValues[1] } != firstKeys }) return null

    fun valuesFor(name: String): Set<String> =
        matches.map { parts -> unquote(parts.first { part -> part.groupValues[1] == name }.groupValues[3]) }.toSet()

    val varyingNames = firstKeys.filter { valuesFor(it).size > 1 }
    if (varyingNames.isEmpty()) {
        val exact = Seq3Match(tag = tag, template = occurrences.first().text)
        return exact to occurrences.associate { it.occurrenceId to emptyMap() }
    }
    // A named value that only ever varies among denylisted generic words (true/false/0/null/...)
    // carries no real identity worth a `{name}` token of its own — see this file's header. Rather
    // than silently drop it while keeping its FIRST occurrence's value as fixed template text
    // (which would then only match that one occurrence), the whole named-value path is abandoned
    // here so the positional single-run fallback below gets a chance to capture the actual
    // differing text under a generic name instead.
    //
    // The same abandonment applies when a quoted-empty value (`key: ""`) is among the varying
    // values: `unquote` strips the quotes from the STORED value but [replacements] below replaces
    // the whole quoted range in the template, so an empty unquoted value would substitute back to
    // nothing (`occurrenceLabel`'s `label.replace("{name}", "")`) — a captured value that silently
    // vanishes is worse than one that never got captured. `compileSingleRunPattern` already refuses
    // an empty middle capture for the same reason (its own `values.any { it.isEmpty() }` check); an
    // empty unquoted named value is non-capturable for the identical reason.
    if (varyingNames.any { name -> isGenericValueSet(valuesFor(name)) || valuesFor(name).any(String::isEmpty) }) return null

    // seq3CaptureTokenNames/CAPTURE_TOKEN only recognise the charset `[A-Za-z_][A-Za-z0-9_]*`, but
    // a NAMED_VALUE key may contain `.`/`-` (a real-world key like `screen.mode`). Left unsanitized,
    // the generated `{screen.mode}` token would never match CAPTURE_TOKEN, so `matchesText` (which
    // cross-checks `seq3CaptureTokenNames(template)` against `match.captures`) would fail for every
    // occurrence and the whole group would silently degrade to one literal message per occurrence.
    // Sanitise once here, keeping the raw->sanitized mapping so the template, the declared
    // captures, and the returned values map all agree on the same generated name — and de-dupe so
    // two keys that only differ by punctuation (`screen.mode` / `screen-mode`) can't collide.
    val usedCaptureNames = mutableSetOf<String>()
    val sanitizedNames = varyingNames.associateWith { sanitizeCaptureName(it, usedCaptureNames) }

    val first = occurrences.first().text
    val replacements = matches.first().mapNotNull { part ->
        val name = part.groupValues[1]
        name.takeIf { it in varyingNames }?.let { part.groups[3]!!.range to "{${sanitizedNames.getValue(it)}}" }
    }.sortedByDescending { it.first.first }
    var template = first
    replacements.forEach { (range, replacement) -> template = template.replaceRange(range, replacement) }
    val captures = varyingNames.map { Seq3Capture(sanitizedNames.getValue(it), Seq3CaptureSource.NAMED_VALUE) }
    val values = occurrences.associate { input ->
        val parts = NAMED_VALUE.findAll(input.text).toList()
        input.occurrenceId to varyingNames.associate { name ->
            sanitizedNames.getValue(name) to unquote(parts.first { it.groupValues[1] == name }.groupValues[3])
        }
    }
    return Seq3Match(tag = tag, template = template, captures = captures) to values
}

/** Sanitises a NAMED_VALUE key into the charset [CAPTURE_TOKEN] recognises (`.`/`-` -> `_`), and
 *  de-dupes against [used] with a numeric suffix — same idiom as `Seq3Emitters.sanitizedAliases`. */
private fun sanitizeCaptureName(rawName: String, used: MutableSet<String>): String {
    val base = rawName.replace(CAPTURE_NAME_INVALID_CHAR, "_")
    var candidate = base
    var suffix = 2
    while (!used.add(candidate)) {
        candidate = "${base}_$suffix"
        suffix++
    }
    return candidate
}

private val CAPTURE_NAME_INVALID_CHAR = Regex("[^A-Za-z0-9_]")

private fun compileSingleRunPattern(
    tag: String,
    occurrences: List<Seq3TokenizeInput>,
): Pair<Seq3Match, Map<String, Map<String, String>>>? {
    val first = occurrences.first().text
    val texts = occurrences.map { it.text }
    val prefixLength = texts.minOf { commonPrefix(first, it) }
    val suffixLength = texts.minOf { commonSuffix(first, it) }
    val maxSuffix = (first.length - prefixLength).coerceAtLeast(0)
    val safeSuffix = suffixLength.coerceAtMost(maxSuffix)
    if (prefixLength == first.length && texts.all { it == first }) {
        return Seq3Match(tag = tag, template = first) to occurrences.associate { it.occurrenceId to emptyMap() }
    }
    if (prefixLength == 0 && safeSuffix == 0) return null

    val prefix = first.take(prefixLength)
    val suffix = first.takeLast(safeSuffix).takeIf { safeSuffix > 0 }.orEmpty()
    val values = mutableListOf<String>()
    occurrences.forEach { input ->
        val text = input.text
        val middleEnd = text.length - safeSuffix
        if (middleEnd >= prefixLength && text.startsWith(prefix) && text.endsWith(suffix)) {
            values += text.substring(prefixLength, middleEnd)
        }
    }
    if (values.size != occurrences.size) return null
    if (values.any { it.isEmpty() }) return null
    val name = anonymousCaptureName(values)
    val template = prefix + "{$name}" + suffix
    return Seq3Match(
        tag = tag,
        template = template,
        captures = listOf(Seq3Capture(name, Seq3CaptureSource.POSITIONAL_RUN)),
    ) to occurrences.mapIndexed { index, input -> input.occurrenceId to mapOf(name to values[index]) }.toMap()
}

private val ALL_DIGITS_RE = Regex("^[0-9]+$")
private val HEX_ISH_RE = Regex("(?i)^[0-9a-f]{4,}$")

/**
 * A stable, content-shaped name for the one anonymous varying run [compileSingleRunPattern] found
 * — pure function of [values], so the SAME set of occurrences always tokenizes to the SAME token
 * name (the brief's "stable and deterministic" requirement; these names end up in user-visible
 * labels, so they must never depend on iteration order, a random id, or wall-clock time).
 */
private fun anonymousCaptureName(values: List<String>): String = when {
    values.all { ALL_DIGITS_RE.matches(it) } -> "n"
    values.all { HEX_ISH_RE.matches(it) } -> "id"
    else -> "value"
}

private fun commonPrefix(a: String, b: String): Int {
    val limit = minOf(a.length, b.length)
    var index = 0
    while (index < limit && a[index] == b[index]) index++
    return index
}

private fun commonSuffix(a: String, b: String): Int {
    val limit = minOf(a.length, b.length)
    var index = 0
    while (index < limit && a[a.length - index - 1] == b[b.length - index - 1]) index++
    return index
}

private fun unquote(value: String): String = value
    .removePrefix("\"").removeSuffix("\"")
    .removePrefix("'").removeSuffix("'")
