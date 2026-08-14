@file:Suppress("ReturnCount")

package com.indagium.diagram

/** One occurrence supplied to the pure message-match compiler. */
data class ManualMessageMatchInput(
    val occurrenceId: String,
    val text: String,
    val tag: String? = null,
)

data class ManualMessageMatchCompilation(
    val match: ManualMessageMatch? = null,
    val captureValuesByOccurrence: Map<String, Map<String, String>> = emptyMap(),
    val preview: String = "",
    val warnings: List<String> = emptyList(),
    val error: String? = null,
) {
    val compiled: Boolean get() = match != null && error == null
}

/**
 * Compiles a small, readable match syntax shared by seed and Merge. It proves the resulting
 * template against every supplied occurrence before returning it. The compiler intentionally
 * rejects multiple unanchored varying runs instead of turning them into a broad regex.
 */
fun compileManualMessageMatch(occurrences: List<ManualMessageMatchInput>): ManualMessageMatchCompilation {
    if (occurrences.isEmpty()) return ManualMessageMatchCompilation(error = "At least one occurrence is required")
    if (occurrences.any { it.occurrenceId.isBlank() }) {
        return ManualMessageMatchCompilation(error = "Every occurrence must have a stable id")
    }
    if (occurrences.any { it.text.isEmpty() }) {
        return ManualMessageMatchCompilation(error = "Empty message text cannot be compiled")
    }

    val named = compileNamedValuePattern(occurrences)
    val candidate = named ?: compileSingleRunPattern(occurrences)
        ?: return ManualMessageMatchCompilation(
            error = "The selected occurrences have ambiguous variation; merge them only when their stable text anchors agree",
        )

    val match = candidate.first
    val captureValues = candidate.second
    val failures = occurrences.filter { input ->
        matchManualMessageText(match, input.text) == null
    }
    if (failures.isNotEmpty()) {
        return ManualMessageMatchCompilation(
            preview = match.textPattern,
            error = "The inferred pattern does not exactly match every selected occurrence",
        )
    }
    return ManualMessageMatchCompilation(
        match = match,
        captureValuesByOccurrence = captureValues,
        preview = match.textPattern,
    )
}

/** The capture token names written in a durable match or label template. */
internal fun manualCaptureTokenNames(template: String): List<String> =
    CAPTURE_TOKEN.findAll(template).map { it.groupValues[1] }.toList()

/**
 * Returns captured values when [text] (and, when present, [tag]) is an exact match for the
 * durable template.  The token/name check is intentional: accepting `{peer}` while the durable
 * capture declaration says `device` would silently associate the wrong value with a message.
 */
fun matchManualMessage(
    match: ManualMessageMatch,
    text: String,
    tag: String? = null,
): Map<String, String>? {
    if (match.tagPattern != null) {
        val tagValue = tag ?: return null
        val tagMatches = runCatching { Regex("^(?:${match.tagPattern})$").matches(tagValue) }.getOrDefault(false)
        if (!tagMatches) return null
    }
    val tokenNames = manualCaptureTokenNames(match.textPattern)
    val declaredNames = match.captures.map { it.name }
    if (tokenNames.distinct().size != tokenNames.size || tokenNames.toSet() != declaredNames.toSet()) return null
    if (declaredNames.isEmpty()) return text.takeIf { it == match.textPattern }?.let { emptyMap() }
    val pattern = buildString {
        var cursor = 0
        CAPTURE_TOKEN.findAll(match.textPattern).forEach { token ->
            append(Regex.escape(match.textPattern.substring(cursor, token.range.first)))
            append("(.+?)")
            cursor = token.range.last + 1
        }
        append(Regex.escape(match.textPattern.substring(cursor)))
    }
    val result = Regex("^(?:$pattern)$", setOf(RegexOption.DOT_MATCHES_ALL)).matchEntire(text) ?: return null
    if (result.groupValues.size - 1 != tokenNames.size) return null
    val valuesByToken = tokenNames.mapIndexed { index, name -> name to result.groupValues[index + 1] }.toMap()
    return declaredNames.associateWith(valuesByToken::getValue)
}

/** Backwards-compatible text-only matcher used by existing callers. */
fun matchManualMessageText(match: ManualMessageMatch, text: String): Map<String, String>? =
    matchManualMessage(match, text)

private val NAMED_VALUE = Regex(
    "([A-Za-z_][A-Za-z0-9_.-]*)\\s*(=|:)\\s*(\"[^\"]*\"|'[^']*'|[^=,:;\\s)]+)(?=\\s+(?:[A-Za-z_][A-Za-z0-9_.-]*\\s*(?:=|:))|\\s*$|[,;)])",
)
private val CAPTURE_TOKEN = Regex("\\{([A-Za-z_][A-Za-z0-9_]*)}")

private fun compileNamedValuePattern(
    occurrences: List<ManualMessageMatchInput>,
): Pair<ManualMessageMatch, Map<String, Map<String, String>>>? {
    val matches = occurrences.map { NAMED_VALUE.findAll(it.text).toList() }
    if (matches.any { it.isEmpty() }) return null
    val firstKeys = matches.first().map { it.groupValues[1] }
    if (matches.any { it.map { part -> part.groupValues[1] } != firstKeys }) return null

    val varyingNames = firstKeys.filter { name ->
        matches.map { parts ->
            parts.first { part -> part.groupValues[1] == name }.groupValues[3]
        }.toSet().size > 1
    }
    if (varyingNames.isEmpty()) {
        val exact = ManualMessageMatch(textPattern = occurrences.first().text)
        return exact to occurrences.associate { it.occurrenceId to emptyMap() }
    }

    val first = occurrences.first().text
    val replacements = matches.first().mapNotNull { part ->
        val name = part.groupValues[1]
        name.takeIf { it in varyingNames }?.let { part.groups[3]!!.range to "{$it}" }
    }.sortedByDescending { it.first.first }
    var pattern = first
    replacements.forEach { (range, replacement) -> pattern = pattern.replaceRange(range, replacement) }
    val captures = varyingNames.map { ManualMessageCapture(it, ManualCaptureSource.NAMED_VALUE) }
    val values = occurrences.associate { input ->
        val parts = NAMED_VALUE.findAll(input.text).toList()
        input.occurrenceId to varyingNames.associateWith { name ->
            unquote(parts.first { it.groupValues[1] == name }.groupValues[3])
        }
    }
    return ManualMessageMatch(textPattern = pattern, captures = captures) to values
}

private fun compileSingleRunPattern(
    occurrences: List<ManualMessageMatchInput>,
): Pair<ManualMessageMatch, Map<String, Map<String, String>>>? {
    val first = occurrences.first().text
    val texts = occurrences.map { it.text }
    val prefixLength = texts.minOf { commonPrefix(first, it) }
    val suffixLength = texts.minOf { commonSuffix(first, it) }
    val maxSuffix = (first.length - prefixLength).coerceAtLeast(0)
    val safeSuffix = suffixLength.coerceAtMost(maxSuffix)
    if (prefixLength == first.length && texts.all { it == first }) {
        return ManualMessageMatch(textPattern = first) to occurrences.associate { it.occurrenceId to emptyMap() }
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
    val pattern = prefix + "{value}" + suffix
    return ManualMessageMatch(
        textPattern = pattern,
        captures = listOf(ManualMessageCapture("value", ManualCaptureSource.POSITIONAL_RUN)),
    ) to occurrences.mapIndexed { index, input ->
        input.occurrenceId to mapOf("value" to values[index])
    }.toMap()
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
