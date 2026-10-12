package com.indagium.ui

import com.indagium.model.AiUsageStats
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

// Display formatting for the Tests workspace: durations, token counts and run timestamps in the short forms the
// lists and report headers use ("6m 52s", "1.36M", "yesterday 19:27"). Pure and Compose-free so they are unit-tested.

private const val MILLIS_PER_SECOND = 1000L
private const val SECONDS_PER_MINUTE = 60L
private const val SECONDS_PER_HOUR = 3600L
private const val TOKENS_PER_THOUSAND = 1000L
private const val TOKENS_PER_MILLION = 1_000_000L
private const val TOKENS_ONE_DECIMAL_LIMIT = 10_000L
private val CLOCK_FORMAT = DateTimeFormatter.ofPattern("HH:mm", Locale.ROOT)
private val DAY_FORMAT = DateTimeFormatter.ofPattern("MMM d", Locale.ENGLISH)
private val DAY_YEAR_FORMAT = DateTimeFormatter.ofPattern("MMM d, yyyy", Locale.ENGLISH)

/** "48s", "6m 52s", "1h 03m": a duration rounded to the nearest second (whole minutes once it passes an hour). */
internal fun formatDuration(ms: Long): String {
    val totalSeconds = (ms.coerceAtLeast(0) + MILLIS_PER_SECOND / 2) / MILLIS_PER_SECOND
    val hours = totalSeconds / SECONDS_PER_HOUR
    val minutes = totalSeconds % SECONDS_PER_HOUR / SECONDS_PER_MINUTE
    val seconds = totalSeconds % SECONDS_PER_MINUTE
    return when {
        hours > 0 -> "${hours}h ${minutes.toString().padStart(2, '0')}m"
        minutes > 0 -> "${minutes}m ${seconds.toString().padStart(2, '0')}s"
        else -> "${seconds}s"
    }
}

/** "980", "150K", "1.36M": a token count with at most two decimals on millions and none once thousands pass ten. */
internal fun formatTokens(n: Long): String {
    val count = n.coerceAtLeast(0)
    return when {
        count < TOKENS_PER_THOUSAND -> count.toString()
        count < TOKENS_ONE_DECIMAL_LIMIT -> trimmedDecimal(count / TOKENS_PER_THOUSAND.toDouble(), 1) + "K"
        // A value that rounds up to 1000K reads better as 1M.
        (count + TOKENS_PER_THOUSAND / 2) / TOKENS_PER_THOUSAND < TOKENS_PER_THOUSAND ->
            ((count + TOKENS_PER_THOUSAND / 2) / TOKENS_PER_THOUSAND).toString() + "K"
        else -> trimmedDecimal(count / TOKENS_PER_MILLION.toDouble(), 2) + "M"
    }
}

private const val BYTES_PER_KIB = 1024L
private const val KIB_PER_MIB = 1024L

/** "0 B", "340 KB", "12.4 MB", "1.2 GB": a disk size in binary units, one decimal from megabytes up. */
internal fun formatBytes(bytes: Long): String {
    val size = bytes.coerceAtLeast(0)
    val kib = size / BYTES_PER_KIB.toDouble()
    return when {
        size < BYTES_PER_KIB -> "$size B"
        kib < KIB_PER_MIB -> "${kib.toLong()} KB"
        kib / KIB_PER_MIB < KIB_PER_MIB -> String.format(Locale.ROOT, "%.1f MB", kib / KIB_PER_MIB)
        else -> String.format(Locale.ROOT, "%.1f GB", kib / KIB_PER_MIB / KIB_PER_MIB)
    }
}

private fun trimmedDecimal(value: Double, decimals: Int): String =
    String.format(Locale.ROOT, "%.${decimals}f", value).trimEnd('0').trimEnd('.')

/**
 * When a run happened, relative to [nowMs] in [zone]: "today 13:25", "yesterday 19:27", "Oct 7, 23:25" (the year is
 * added for another calendar year: "Oct 7, 2025, 23:25").
 */
internal fun formatRunMoment(epochMs: Long, nowMs: Long, zone: ZoneId = ZoneId.systemDefault()): String {
    val moment = Instant.ofEpochMilli(epochMs).atZone(zone)
    val today = Instant.ofEpochMilli(nowMs).atZone(zone).toLocalDate()
    val day = moment.toLocalDate()
    val clock = CLOCK_FORMAT.format(moment)
    return when {
        day == today -> "today $clock"
        day == today.minusDays(1) -> "yesterday $clock"
        day.year == today.year -> "${DAY_FORMAT.format(day)}, $clock"
        else -> "${DAY_YEAR_FORMAT.format(day)}, $clock"
    }
}

/** "13:27": the clock time of [epochMs] in [zone], for a moment that shares its day with one already shown. */
internal fun formatClock(epochMs: Long, zone: ZoneId = ZoneId.systemDefault()): String =
    CLOCK_FORMAT.format(Instant.ofEpochMilli(epochMs).atZone(zone))

/**
 * An AI usage total in the short form the report shows: "43 tool calls · 1.36M tokens · partial count". A metric the source did
 * not report reads "unavailable" instead of looking like zero. Null (no usage at all) is "Unavailable".
 */
internal fun formatUsage(usage: AiUsageStats?): String {
    if (usage == null) return "Unavailable"
    val input = usage.inputTokens
    val output = usage.outputTokens
    val total = usage.totalTokens
    val tools = usage.toolCalls?.let { "$it tool ${if (it == 1L) "call" else "calls"}" } ?: "tool calls unavailable"
    val tokens = when {
        total != null -> "${formatTokens(total)} tokens"
        input != null && output != null -> "${formatTokens(input)} in · ${formatTokens(output)} out"
        input != null -> "${formatTokens(input)} in · out unavailable"
        output != null -> "in unavailable · ${formatTokens(output)} out"
        else -> "tokens unavailable"
    }
    return listOfNotNull(tools, tokens, "partial count".takeIf { usage.partial }).joinToString(" · ")
}
