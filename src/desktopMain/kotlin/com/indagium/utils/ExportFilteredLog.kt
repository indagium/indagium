package com.indagium.utils

import com.indagium.model.AppSettings
import com.indagium.model.LogTab
import java.io.File

// Always the full filtered data (visibleEntries — same source computeItems() uses), regardless
// of collapsed/expanded sequence or stack-trace headers: those are a viewing convenience, not a
// filter, so an export should never silently drop lines a user just happened to have folded.
private fun csvField(value: String): String =
    if (value.any { it == ',' || it == '"' || it == '\n' || it == '\r' }) {
        "\"${value.replace("\"", "\"\"")}\""
    } else {
        value
    }

// String-returning builders — kept for callers that want the content in memory (existing tests
// use these as the parity oracle for exportFilteredToFile below).
fun buildFilteredTxt(tab: LogTab, settings: AppSettings = AppSettings()): String =
    buildFilteredTxt(tab, settings, RegexEvaluationContext())

internal fun buildFilteredTxt(tab: LogTab, regexContext: RegexEvaluationContext): String =
    buildFilteredTxt(tab, AppSettings(), regexContext)

internal fun buildFilteredTxt(tab: LogTab, settings: AppSettings, regexContext: RegexEvaluationContext): String = buildString {
    val entries = visibleEntries(tab, applyFilter = true, regexContext = regexContext)
    val context = LogLinePresentationContext(tab, settings, entries)
    entries.forEach { entry -> appendLine(presentLogLine(tab, entry, settings, context)) }
}

fun buildFilteredCsv(tab: LogTab, settings: AppSettings = AppSettings()): String =
    buildFilteredCsv(tab, settings, RegexEvaluationContext())

internal fun buildFilteredCsv(tab: LogTab, regexContext: RegexEvaluationContext): String =
    buildFilteredCsv(tab, AppSettings(), regexContext)

internal fun buildFilteredCsv(tab: LogTab, settings: AppSettings, regexContext: RegexEvaluationContext): String = buildString {
    val entries = visibleEntries(tab, applyFilter = true, regexContext = regexContext)
    val context = LogLinePresentationContext(tab, settings, entries)
    appendLine(filteredCsvHeader(tab, settings).joinToString(","))
    entries.forEach { entry -> appendLine(filteredCsvValues(tab, entry, settings, context).joinToString(",") { csvField(it) }) }
}

// Streams the same content buildFilteredTxt/buildFilteredCsv produce straight to [destination] —
// one row at a time through writeFileAtomically — instead of materializing the entire export as a
// single in-memory String first (P-03: unbounded allocation proportional to output size). The
// write is also crash-safe: writeFileAtomically only replaces destination once every row has been
// written successfully, so a failure or cancellation partway through never corrupts or truncates
// an existing export at that path.
fun exportFilteredToFile(tab: LogTab, destination: File, csv: Boolean, settings: AppSettings = AppSettings()) =
    exportFilteredToFile(tab, destination, csv, settings, RegexEvaluationContext())

internal fun exportFilteredToFile(
    tab: LogTab,
    destination: File,
    csv: Boolean,
    regexContext: RegexEvaluationContext,
) = exportFilteredToFile(tab, destination, csv, AppSettings(), regexContext)

internal fun exportFilteredToFile(
    tab: LogTab,
    destination: File,
    csv: Boolean,
    settings: AppSettings,
    regexContext: RegexEvaluationContext,
) {
    writeFileAtomically(destination) { writer ->
        val entries = visibleEntries(tab, applyFilter = true, regexContext = regexContext)
        val context = LogLinePresentationContext(tab, settings, entries)
        if (csv) writer.appendLine(filteredCsvHeader(tab, settings).joinToString(","))
        entries.forEach { entry ->
            val line = if (csv) {
                filteredCsvValues(tab, entry, settings, context).joinToString(",") { csvField(it) }
            } else {
                presentLogLine(tab, entry, settings, context)
            }
            writer.appendLine(line)
        }
    }
}
