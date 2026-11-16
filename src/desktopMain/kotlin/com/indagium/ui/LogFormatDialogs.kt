package com.indagium.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import com.indagium.model.*
import com.indagium.utils.ParsedLog
import kotlinx.coroutines.launch

private data class SourceFormatPreview(
    val profileId: String,
    val parsed: ParsedLog,
)

/** Per-tab view of the pinned parser, derived capabilities, and safe format-change flow. */
@Composable
internal fun LogFormatDetailsDialog(state: AppState, tabId: String, onDismiss: () -> Unit) {
    val tc = tc()
    val tab = state.tab(tabId)
    if (tab == null) {
        Dialog(onDismissRequest = onDismiss) {
            Column(Modifier.width(380.dp).background(tc.p, RoundedCornerShape(8.dp)).padding(20.dp)) {
                AppText("This log tab is no longer open.", color = tc.tx, fontSize = 13.sp)
                Spacer(Modifier.height(12.dp))
                SettingsDialogButton("Close", primary = true, onClick = onDismiss)
            }
        }
        return
    }

    val profiles = state.availableLogFormatProfiles()
    val tabCapabilities = remember(
        state,
        tab.id,
        tab.logCapabilities,
        tab.logFormatProfileId,
        tab.logFormatProfileSnapshot,
        state.settings.sourceFolders,
    ) {
        state.logCapabilitiesForTab(tab)
    }
    val formatUsed = remember(tab.logFormatProfileSnapshot, tab.logFormatProfileId, profiles) {
        runCatching { state.pinnedLogFormatProfile(tabId) }.getOrNull()
    }
    val automaticProfileId = if (tab.logFormat == LogFormat.DLT) "automotive-dlt" else "android-logcat"
    val defaultProfile = formatUsed ?: profiles.firstOrNull { it.id == automaticProfileId }
    var selectedId by remember(tabId, defaultProfile?.id) {
        mutableStateOf(defaultProfile?.id ?: profiles.firstOrNull()?.id.orEmpty())
    }
    val selectedProfile = profiles.firstOrNull { it.id == selectedId } ?: profiles.firstOrNull()
    var preview by remember(tabId) { mutableStateOf<SourceFormatPreview?>(null) }
    var previewError by remember(tabId) { mutableStateOf<String?>(null) }
    var previewing by remember(tabId) { mutableStateOf(false) }
    var reparsing by remember(tabId) { mutableStateOf(false) }
    var reparseError by remember(tabId) { mutableStateOf<String?>(null) }
    var requestNumber by remember(tabId) { mutableStateOf(0) }
    val scope = rememberCoroutineScope()

    fun selectProfile(profile: LogFormatProfile) {
        selectedId = profile.id
        preview = null
        previewError = null
        reparseError = null
        previewing = false
        reparsing = false
        requestNumber++
    }

    Dialog(onDismissRequest = onDismiss) {
        Column(
            Modifier.width(620.dp).heightIn(max = 760.dp).background(tc.p, RoundedCornerShape(8.dp))
                .border(1.dp, tc.br, RoundedCornerShape(8.dp)).padding(18.dp).testTag("tab-log-format-details-dialog"),
            verticalArrangement = Arrangement.spacedBy(9.dp),
        ) {
            AppText("Log format details", color = tc.tx, fontSize = 15.sp)
            AppText(tab.filename, color = tc.ts, fontSize = 11.sp, maxLines = 1)
            Column(
                Modifier.weight(1f, fill = false).fillMaxWidth().verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Column(
                    Modifier.fillMaxWidth().background(tc.p2, RoundedCornerShape(6.dp)).padding(10.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    AppText("Format used by this tab", color = tc.ts, fontSize = 10.sp)
                    AppText(
                        when {
                            formatUsed != null -> "${formatUsed.name} · revision ${formatUsed.revision}"
                            tab.logFormatProfileSnapshot != null -> "Saved format details could not be read"
                            tab.logFormatProfileId != null -> "Saved profile is unavailable"
                            else -> "Detected automatically · ${engineName(defaultProfile?.engine)}"
                        },
                        color = tc.tx,
                        fontSize = 12.sp,
                    )
                    formatUsed?.description?.takeIf(String::isNotBlank)?.let {
                        AppText(it, color = tc.ts, fontSize = 10.sp, maxLines = 3)
                    }
                    AppText(
                        "${tab.logData.size} records · ${engineName(formatUsed?.engine ?: defaultProfile?.engine)}",
                        color = tc.ts,
                        fontSize = 10.sp,
                    )
                    if (tab.logFormatDiagnostics.isNotEmpty()) {
                        tab.logFormatDiagnostics.take(5).forEach {
                            AppText(it, color = tc.ts, fontSize = 10.sp, maxLines = 2)
                        }
                    }
                }

                Column(
                    Modifier.fillMaxWidth().background(tc.p2, RoundedCornerShape(6.dp)).padding(10.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    AppText("Try another profile", color = tc.tx, fontSize = 12.sp)
                    AppText(
                        "Preview first. Reparse opens a separate tab with new record IDs. " +
                            "This tab's rows and annotations stay as they are.",
                        color = tc.ts,
                        fontSize = 10.sp,
                        maxLines = 3,
                    )
                    if (selectedProfile != null) {
                        SettingsDropdown(
                            options = profiles,
                            selected = selectedProfile,
                            label = LogFormatProfile::name,
                            onSelect = ::selectProfile,
                            testTag = "tab-log-format-profile",
                            modifier = Modifier.fillMaxWidth(),
                            minWidth = 300.dp,
                            optionTag = { "tab-log-format-option-${it.id}" },
                            optionNote = {
                                val kind = if (it.readOnly) " · built-in" else " · revision ${it.revision}"
                                "${engineName(it.engine)}$kind"
                            },
                        )
                    }
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        SettingsButton(
                            if (previewing) "Previewing source…" else "Preview source",
                            {
                                val profile = selectedProfile ?: return@SettingsButton
                                if (!previewing) {
                                    val thisRequest = requestNumber + 1
                                    requestNumber = thisRequest
                                    previewing = true
                                    previewError = null
                                    state.previewCurrentLogFormatProfile(tabId, profile) { result ->
                                        scope.launch {
                                            if (requestNumber == thisRequest) {
                                                result.fold(
                                                    onSuccess = {
                                                        preview = SourceFormatPreview(profile.id, it)
                                                        previewError = null
                                                    },
                                                    onFailure = {
                                                        previewError = it.message ?: "Could not preview this source."
                                                    },
                                                )
                                                previewing = false
                                            }
                                        }
                                    }
                                }
                            },
                            enabled = selectedProfile != null && !previewing,
                            modifier = Modifier.testTag("tab-log-format-preview"),
                        )
                        SettingsButton(
                            "Reparse as new tab",
                            {
                                val profile = selectedProfile ?: return@SettingsButton
                                if (preview?.profileId == profile.id) {
                                    reparsing = true
                                    reparseError = null
                                    state.reparseTabWithLogFormatProfile(tabId, profile.id) { result ->
                                        scope.launch {
                                            result.fold(
                                                onSuccess = { onDismiss() },
                                                onFailure = {
                                                    reparseError = it.message ?: "Could not create the new tab."
                                                },
                                            )
                                            reparsing = false
                                        }
                                    }
                                }
                            },
                            variant = ButtonVariant.Primary,
                            enabled = selectedProfile != null && preview?.profileId == selectedProfile.id &&
                                !previewing && !reparsing,
                            modifier = Modifier.testTag("tab-log-format-reparse-new"),
                        )
                    }
                    if (reparsing) AppText("Creating a separate tab…", color = tc.ts, fontSize = 10.sp)
                    previewError?.let { AppText(it, color = DANGER_RED, fontSize = 10.sp, maxLines = 3) }
                    reparseError?.let { AppText(it, color = DANGER_RED, fontSize = 10.sp, maxLines = 3) }
                    preview?.takeIf { it.profileId == selectedProfile?.id }?.let { SourcePreviewSummary(it.parsed) }
                }

                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    AppText("Capabilities for the records in this tab", color = tc.tx, fontSize = 12.sp)
                    if (tabCapabilities.isEmpty()) {
                        AppText(
                            "No capability details are available for this tab yet.",
                            color = tc.ts,
                            fontSize = 10.sp,
                        )
                    } else {
                        CapabilityRows(tabCapabilities)
                    }
                }
            }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End)) {
                SettingsDialogButton("Done", onClick = onDismiss)
            }
        }
    }
}

@Composable
private fun CapabilityRows(capabilities: Map<LogCapability, LogCapabilityStatus>) {
    val tc = tc()
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        capabilities.toSortedMap(compareBy { it.ordinal }).forEach { (capability, status) ->
            Column(
                Modifier.fillMaxWidth().background(tc.p2, RoundedCornerShape(5.dp))
                    .padding(horizontal = 8.dp, vertical = 6.dp),
            ) {
                Row(
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    AppText(capabilityLabel(capability), color = tc.tx, fontSize = 11.sp)
                    AppText(
                        "· ${availabilityLabel(status.availability)}",
                        color = availabilityColor(status.availability),
                        fontSize = 10.sp,
                    )
                }
                AppText(status.reason, color = tc.ts, fontSize = 10.sp, maxLines = 3)
            }
        }
    }
}

@Composable
private fun SourcePreviewSummary(parsed: ParsedLog) {
    val tc = tc()
    Column(
        Modifier.fillMaxWidth().background(tc.p, RoundedCornerShape(5.dp)).padding(8.dp),
        verticalArrangement = Arrangement.spacedBy(5.dp),
    ) {
        AppText("${parsed.entries.size} records in the source preview", color = tc.tx, fontSize = 11.sp)
        parsed.diagnostics.take(4).forEach { AppText(it, color = tc.ts, fontSize = 10.sp, maxLines = 2) }
        parsed.entries.take(5).forEach { entry ->
            Column(
                Modifier.fillMaxWidth().background(tc.p2, RoundedCornerShape(4.dp)).padding(6.dp),
                verticalArrangement = Arrangement.spacedBy(2.dp),
            ) {
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    AppText(
                        entry.ts.ifBlank { "No timestamp" },
                        color = tc.ts,
                        fontSize = 10.sp,
                        fontFamily = FontFamily.Monospace,
                    )
                    AppText(entry.displaySeverityText, color = tc.ac, fontSize = 10.sp)
                    AppText(entry.tag.ifBlank { "No component" }, color = tc.tx, fontSize = 10.sp)
                }
                AppText(entry.msg.ifBlank { "No message" }, color = tc.tx, fontSize = 10.sp, maxLines = 2)
            }
        }
        if (parsed.capabilities.isNotEmpty()) {
            AppText("Preview capabilities", color = tc.tx, fontSize = 11.sp)
            CapabilityRows(parsed.capabilities)
        }
    }
}

private fun engineName(engine: LogFormatEngine?): String = when (engine) {
    LogFormatEngine.LOGCAT -> "Android Logcat"
    LogFormatEngine.DLT -> "Automotive DLT"
    LogFormatEngine.PLAIN_TEXT -> "Plain text"
    LogFormatEngine.REGEX -> "Regular expression"
    LogFormatEngine.JSON_LINES -> "JSON Lines"
    LogFormatEngine.CSV -> "CSV / TSV"
    LogFormatEngine.SYSLOG_RFC5424 -> "Syslog RFC 5424"
    LogFormatEngine.SYSLOG_RFC3164 -> "Syslog RFC 3164"
    LogFormatEngine.LOGFMT -> "logfmt"
    LogFormatEngine.CRI -> "Kubernetes CRI"
    null -> "legacy parser"
}

private fun capabilityLabel(capability: LogCapability): String = capability.name.lowercase()
    .replace('_', ' ').replaceFirstChar(Char::uppercase)

private fun availabilityLabel(availability: CapabilityAvailability): String = availability.name.lowercase()
    .replaceFirstChar(Char::uppercase)

@Composable
private fun availabilityColor(availability: CapabilityAvailability) = when (availability) {
    CapabilityAvailability.AVAILABLE -> tc().success
    CapabilityAvailability.PARTIAL -> tc().warn
    CapabilityAvailability.UNAVAILABLE -> tc().td
}
