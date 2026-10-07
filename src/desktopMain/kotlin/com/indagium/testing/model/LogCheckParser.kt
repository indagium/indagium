package com.indagium.testing.model

import com.indagium.utils.parseLogcatLines

/** One log line interpreted as a literal log-appearance check. */
data class ParsedLogCheck(val source: String, val tag: String?, val message: String, val check: StepCheck.LogAppears)

data class LogCheckPreview(val checks: List<ParsedLogCheck>, val warnings: List<String>)

private val TAG_MESSAGE_LOG = Regex("^([^\\s:]{1,120}):\\s+(.+)$")
private val EMPTY_LEVEL_TAG_LOG = Regex("^[VDIWEF]/([^:]{1,120}):\\s*$")

/**
 * Parses pasted logcat lines. Recognized Android prefixes contribute their tag; matching text is always regex-escaped
 * so each check finds that literal message. Empty lines are ignored and output is capped to keep previews manageable.
 */
fun previewLogChecks(text: String, maxChecks: Int = 200): LogCheckPreview {
    require(maxChecks > 0)
    val warnings = mutableListOf<String>()
    val rows = text.lineSequence().map(String::trimEnd).filter(String::isNotBlank).toList()
    val checks = mutableListOf<ParsedLogCheck>()
    var index = 0
    while (index < rows.size && checks.size < maxChecks) {
        val source = rows[index]
        index++
        val parsed = parseLogLine(source)
        if (parsed.second.isBlank()) {
            warnings += "Skipped a line with no message: ${source.take(100)}"
        } else {
            checks += ParsedLogCheck(
                source = source,
                tag = parsed.first,
                message = parsed.second,
                check = StepCheck.LogAppears(newCheckId(), parsed.first, Regex.escape(parsed.second), DEFAULT_LOG_WITHIN_MS),
            )
        }
    }
    if (index < rows.size) warnings += "Only the first $maxChecks valid lines are included."
    return LogCheckPreview(checks, warnings)
}

private fun parseLogLine(source: String): Pair<String?, String> {
    val parsed = parseLogcatLines(sequenceOf(source)).firstOrNull()
    if (parsed != null && parsed.tag != "RAW") return parsed.tag.takeIf(String::isNotBlank) to parsed.msg.trim()
    EMPTY_LEVEL_TAG_LOG.matchEntire(source)?.let { return it.groupValues[1] to "" }
    TAG_MESSAGE_LOG.matchEntire(source)?.let { match -> return match.groupValues[1] to match.groupValues[2].trim() }
    return null to source.trim()
}
