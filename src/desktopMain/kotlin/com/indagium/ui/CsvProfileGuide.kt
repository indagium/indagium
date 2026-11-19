package com.indagium.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.indagium.model.LogFieldRole
import com.indagium.model.LogFormatAttribute
import com.indagium.model.LogFormatProfile
import com.indagium.model.LogProfileValueType
import com.indagium.utils.ParsedLog
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.Locale

private val COPIED_RAW_PREFIX = Regex("^\\s*[VDIWEAF]/RAW(?:\\s|:)")
private val CSV_ROLE_HINTS = mapOf(
    "time" to LogFieldRole.TIMESTAMP, "timestamp" to LogFieldRole.TIMESTAMP, "ts" to LogFieldRole.TIMESTAMP,
    "level" to LogFieldRole.SEVERITY, "severity" to LogFieldRole.SEVERITY,
    "component" to LogFieldRole.COMPONENT, "logger" to LogFieldRole.COMPONENT, "tag" to LogFieldRole.COMPONENT,
    "message" to LogFieldRole.MESSAGE, "msg" to LogFieldRole.MESSAGE, "pid" to LogFieldRole.PROCESS_ID,
    "tid" to LogFieldRole.THREAD_ID, "request_id" to LogFieldRole.REQUEST_ID, "trace_id" to LogFieldRole.TRACE_ID,
)

private data class CsvColumnUse(val label: String, val role: LogFieldRole? = null)
private val DETAILS_ONLY = CsvColumnUse("Details only")
private val EXTRA_COLUMN = CsvColumnUse("Extra column")
private val CSV_USES = listOf(DETAILS_ONLY, EXTRA_COLUMN) +
    LogFieldRole.entries.filter { it != LogFieldRole.SECTION }.map { CsvColumnUse(roleLabel(it), it) }

internal fun looksLikeCopiedRawRows(sample: String): Boolean =
    sample.lineSequence().firstOrNull(String::isNotBlank)?.let(COPIED_RAW_PREFIX::containsMatchIn) == true

@Composable
internal fun CsvProfileGuide(
    state: AppState,
    profile: LogFormatProfile,
    sample: String,
    onSampleChange: (String) -> Unit,
    onEdit: ((LogFormatProfile) -> LogFormatProfile) -> Unit,
) {
    val colors = tc()
    val scope = rememberCoroutineScope()
    val tab = state.activeTab()?.takeIf { it.sourcePath != null }
    var useCurrentTab by remember(profile.id) { mutableStateOf(tab != null) }
    var request by remember(profile.id) { mutableIntStateOf(0) }
    var busy by remember(profile.id) { mutableStateOf(false) }
    var error by remember(profile.id) { mutableStateOf<String?>(null) }
    var previewResult by remember(profile.id, profile.delimiter, profile.hasHeader) {
        mutableStateOf<Pair<LogFormatProfile, ParsedLog>?>(null)
    }
    val parsed = previewResult?.second
    val previewMatchesDraft = previewResult?.first == profile
    val prefixed = !useCurrentTab && looksLikeCopiedRawRows(sample)

    fun invalidate() {
        request++
        busy = false
        error = null
        previewResult = null
    }

    fun preview() {
        val requested = ++request
        busy = true
        error = null
        fun finish(result: Result<ParsedLog>) {
            if (request != requested) return
            busy = false
            result.onSuccess { previewResult = profile to it }.onFailure { error = it.message ?: "Could not read CSV data." }
        }
        if (useCurrentTab) {
            val active = tab
            if (active != null) {
                state.previewCurrentLogFormatProfile(active.id, profile) { result -> scope.launch { finish(result) } }
            }
        } else {
            scope.launch {
                finish(withContext(Dispatchers.Default) { runCatching { state.previewLogFormatProfile(profile, sample) } })
            }
        }
    }

    SettingsGroup("Set up CSV / TSV") {
        SettingsStackedRow("Source") {
            SettingsSegmented(
                options = listOf("Current tab", "Paste source"),
                selectedIndices = setOf(if (useCurrentTab) 0 else 1),
                onToggle = { useCurrentTab = it == 0; invalidate() },
            )
            if (useCurrentTab) {
                AppText(tab?.filename ?: "Open a source file in a log tab.", color = colors.ts, fontSize = 11.sp)
            } else {
                MultilineSettingsField(sample, { onSampleChange(it); invalidate() }, "Paste CSV or TSV source…", "settings-csv-guide-sample")
            }
            if (prefixed) {
                AppText(
                    "This sample starts with an I/RAW-style display prefix. Use the current tab or paste the original file text.",
                    color = colors.warn, fontSize = 11.sp,
                )
            }
            SettingsButton(
                if (busy) "Reading…" else if (parsed == null) "Read columns" else "Preview mapping",
                onClick = ::preview,
                enabled = !busy && !prefixed && if (useCurrentTab) tab != null else sample.isNotBlank(),
                modifier = Modifier.testTag("settings-csv-guide-preview"),
            )
            error?.let { AppText(it, color = DANGER_RED, fontSize = 11.sp) }
            if (parsed != null && previewMatchesDraft) {
                AppText(profilePreviewCountLabel(parsed), color = colors.ts, fontSize = 11.sp)
            }
        }
        val sources = (parsed?.csvColumns.orEmpty() + profile.fieldMappings.values + profile.attributes.map { it.source })
            .filter(String::isNotBlank).distinct()
        if (sources.isNotEmpty()) {
            SettingsStackedRow("Column mapping") {
                if (profile.hasHeader && parsed?.csvColumns?.any { csvRoleHint(it) != null } == true) {
                    SettingsButton("Use suggested mappings", onClick = {
                        val columns = parsed.csvColumns
                        onEdit { current -> suggestCsvMappings(current, columns) }
                    }, modifier = Modifier.testTag("settings-csv-guide-suggest"))
                }
                sources.forEachIndexed { index, source ->
                    val attribute = profile.attributes.firstOrNull { it.source == source }
                    val role = profile.fieldMappings.entries.firstOrNull { it.value == source }?.key
                    val selected = when {
                        role != null -> CSV_USES.firstOrNull { it.role == role } ?: DETAILS_ONLY
                        attribute != null -> EXTRA_COLUMN
                        else -> DETAILS_ONLY
                    }
                    Row(
                        Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        AppText(if (profile.hasHeader) source else "Column ${(source.toIntOrNull() ?: index) + 1}",
                            modifier = Modifier.weight(1f), color = colors.tx, fontSize = 12.sp)
                        SettingsDropdown(
                            options = CSV_USES, selected = selected, label = CsvColumnUse::label,
                            onSelect = { use ->
                                onEdit { current -> assignCsvColumn(current, source, use) }
                            },
                            testTag = "settings-csv-guide-use-$index",
                        )
                        if (attribute != null) {
                            SettingsDropdown(
                                options = LogProfileValueType.entries, selected = attribute.type, label = ::valueTypeLabel,
                                onSelect = { type ->
                                    onEdit { current ->
                                        current.copy(attributes = current.attributes.map {
                                            if (it.id == attribute.id) it.copy(type = type) else it
                                        })
                                    }
                                },
                                testTag = "settings-csv-guide-type-$index",
                            )
                        }
                    }
                }
                if (parsed?.csvColumns?.any { it.isBlank() } == true) {
                    AppText("Some header cells are empty. Give them names in the source or use column numbers.", color = colors.warn, fontSize = 11.sp)
                }
            }
        }
        parsed?.let { preview ->
            SettingsStackedRow("Preview") {
                if (previewMatchesDraft) {
                    ProfilePreviewResults(preview)
                } else {
                    AppText("Run Preview mapping to see your changes.", color = colors.ts, fontSize = 11.sp)
                }
            }
        }
    }
}

private fun csvRoleHint(source: String): LogFieldRole? =
    CSV_ROLE_HINTS[source.trim().lowercase(Locale.ROOT).replace('-', '_').replace(' ', '_')]

internal fun suggestCsvMappings(profile: LogFormatProfile, columns: List<String>): LogFormatProfile {
    val existing = profile.fieldMappings.filterValues { it in columns }
    val suggested = columns.fold(existing) { mappings, source ->
        val role = csvRoleHint(source)
        if (role == null || role in mappings || source in mappings.values || profile.attributes.any { it.source == source }) {
            mappings
        } else {
            mappings + (role to source)
        }
    }
    var updated = profile.copy(fieldMappings = suggested, visibleColumns = csvColumns(profile, suggested.keys))
    columns.filter { it.isNotBlank() && it !in suggested.values && updated.attributes.none { attribute -> attribute.source == it } }
        .forEach { source -> updated = assignCsvColumn(updated, source, EXTRA_COLUMN) }
    return updated
}

private fun csvColumns(profile: LogFormatProfile, mapped: Set<LogFieldRole>): List<LogFieldRole> =
    ((profile.visibleColumns.filter { it in mapped } + mapped).distinct() - LogFieldRole.MESSAGE) +
        listOfNotNull(LogFieldRole.MESSAGE.takeIf { it in mapped })

private fun assignCsvColumn(profile: LogFormatProfile, source: String, use: CsvColumnUse): LogFormatProfile {
    val previous = profile.attributes.firstOrNull { it.source == source }
    val retained = profile.attributes.filterNot { it.source == source }
    val mappings = profile.fieldMappings.filterValues { it != source }.toMutableMap()
    use.role?.let { mappings[it] = source }
    val attribute = if (use == EXTRA_COLUMN) {
        previous ?: run {
            val base = ("csv_" + source.lowercase(Locale.ROOT).replace(Regex("[^a-zA-Z0-9_]"), "_")).take(90)
            val id = generateSequence(1) { it + 1 }.map { if (it == 1) base else "${base}_$it" }
                .first { candidate -> retained.none { it.id == candidate } }
            val type = if (source.endsWith("_ms", ignoreCase = true)) LogProfileValueType.NUMBER else LogProfileValueType.TEXT
            LogFormatAttribute(id, source.take(100), source, type, visibleByDefault = true)
        }
    } else {
        null
    }
    return profile.copy(
        fieldMappings = mappings,
        attributes = retained + listOfNotNull(attribute),
        visibleColumns = csvColumns(profile, mappings.keys),
    )
}
