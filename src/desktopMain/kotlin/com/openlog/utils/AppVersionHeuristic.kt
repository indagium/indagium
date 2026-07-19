package com.openlog.utils

import com.openlog.model.LogEntry

// Best-effort, deliberately small: only feeds Annotations.appVersion (com.openlog.cases'
// "similar past issues" retrieval), which uses it purely to down-weight stale matches — a wrong
// or missing guess here never causes a functional failure, just a slightly less sharp ranking.
// Scans a bounded prefix of a tab's parsed log data (Android version markers like
// "versionName=..." typically appear near process/app start, so scanning the whole file has no
// real benefit and would cost real time on multi-GB logs) for a handful of common markers.
private const val APP_VERSION_SCAN_LIMIT = 500

private val APP_VERSION_MARKERS = listOf(
    Regex("""versionName=([\w.\-]+)"""),
    Regex("""version[_ ]?name\s*[:=]\s*"?([\w.\-]+)"?""", RegexOption.IGNORE_CASE),
    Regex("""app[_ ]?version\s*[:=]\s*"?([\w.\-]+)"?""", RegexOption.IGNORE_CASE),
    Regex("""\bVersion:\s*([\w.\-]+)"""),
)

/** First recognizable app/build version marker found in [logData]'s first
 *  [APP_VERSION_SCAN_LIMIT] entries, or "" when none is found. */
fun extractAppVersionHeuristic(logData: List<LogEntry>): String {
    logData.asSequence().take(APP_VERSION_SCAN_LIMIT).forEach { entry ->
        APP_VERSION_MARKERS.forEach { marker ->
            marker.find(entry.msg)?.groupValues?.getOrNull(1)?.takeIf { it.isNotBlank() }?.let { return it }
        }
    }
    return ""
}
