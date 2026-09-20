package com.indagium.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Checkbox
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.indagium.capture.CaptureDevice
import com.indagium.capture.CaptureExportRequest
import com.indagium.capture.CaptureRange
import com.indagium.capture.CaptureSession
import com.indagium.capture.CaptureSettings
import com.indagium.capture.CaptureStatus
import com.indagium.capture.captureFilenameTemplateError
import java.io.File

private const val GIB = 1024L * 1024L * 1024L
private const val MIN_CAPTURE_SIZE = 0
private const val MAX_CAPTURE_SIZE = 8_192
private const val MIN_CAPTURE_FPS = 1
private const val MAX_CAPTURE_FPS = 240
private const val MIN_CAPTURE_BITRATE_MBPS = 1
private const val MAX_CAPTURE_BITRATE_MBPS = 500

/** UI-only state supplied by the capture coordinator. No service or coroutine is owned here. */
data class CaptureWorkspaceState(
    val settings: CaptureSettings = CaptureSettings(),
    val devices: List<CaptureDevice> = emptyList(),
    val selectedDeviceSerial: String? = null,
    val sessions: List<CaptureSession> = emptyList(),
    val selectedSessionId: String? = null,
    val activeSessionId: String? = null,
    val isRecording: Boolean = false,
    val isBusy: Boolean = false,
    val previewRows: List<String> = emptyList(),
    val latestReadableTimestamp: String? = null,
    val elapsedText: String = "—",
    val storageText: String = "—",
    val videoStatus: String = "No video",
    val diagnostics: List<String> = emptyList(),
    val error: String? = null,
    val status: String? = null,
    val toolsStatus: String? = null,
    val saveDirectory: File? = null,
    val templatePreview: String? = null,
    val manualOffsetMs: Long = 0,
    val previewLatestRows: Boolean = true,
)

/** Callbacks deliberately expose user intent as values; the parent owns persistence and services. */
data class CaptureWorkspaceActions(
    val onSettingsChanged: (CaptureSettings) -> Unit = {},
    val onBrowseAdb: () -> Unit = {},
    val onBrowseScrcpy: () -> Unit = {},
    val onRecheckTools: () -> Unit = {},
    val onOpenInstallGuide: () -> Unit = {},
    val onDeviceSelected: (CaptureDevice) -> Unit = {},
    val onRefreshDevices: () -> Unit = {},
    val onSelectSession: (String) -> Unit = {},
    val onStart: () -> Unit = {},
    val onStop: () -> Unit = {},
    val onSave: (CaptureExportRequest) -> Unit = {},
    val onChooseSaveDirectory: () -> Unit = {},
    val onScreenshot: () -> Unit = {},
    val onOpenSnapshot: () -> Unit = {},
    val onDeleteSession: (String) -> Unit = {},
    val onManualOffsetChanged: (Long) -> Unit = {},
    val onCancelExport: () -> Unit = {},
    val onClose: () -> Unit = {},
)

@Composable
fun CaptureWorkspace(
    state: CaptureWorkspaceState,
    actions: CaptureWorkspaceActions,
    modifier: Modifier = Modifier,
) {
    var tab by remember { mutableStateOf(0) }
    var invalidSettings by remember { mutableStateOf(invalidCaptureSettings(state.settings)) }

    fun setFieldValidity(field: String, valid: Boolean) {
        invalidSettings = if (valid) invalidSettings - field else invalidSettings + field
    }
    Column(modifier.fillMaxSize().background(tc().bg)) {
        TabRow(selectedTabIndex = tab, containerColor = tc().p) {
            listOf("Capture", "Settings", "Diagnostics").forEachIndexed { i, label ->
                Tab(selected = tab == i, onClick = { tab = i }, text = { AppText(label, fontSize = 11.sp) })
            }
        }
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 5.dp),
            horizontalArrangement = Arrangement.End,
        ) {
            AppButton("Close", actions.onClose, ButtonVariant.Ghost)
        }
        when (tab) {
            0 -> CaptureTab(state, actions, Modifier.weight(1f), invalidSettings.isEmpty())
            1 -> SettingsTab(state, actions, Modifier.weight(1f), ::setFieldValidity)
            else -> DiagnosticsTab(state, Modifier.weight(1f))
        }
    }
}

private fun invalidCaptureSettings(settings: CaptureSettings): Set<String> = buildSet {
    if (settings.adbPath.isNotBlank() && !File(settings.adbPath).isFile) add("adbPath")
    if (settings.scrcpyPath.isNotBlank() && !File(settings.scrcpyPath).isFile) add("scrcpyPath")
    if (settings.maxSize !in MIN_CAPTURE_SIZE..MAX_CAPTURE_SIZE) add("maxSize")
    if (settings.maxFps !in MIN_CAPTURE_FPS..MAX_CAPTURE_FPS) add("maxFps")
    if (settings.bitrateMbps !in MIN_CAPTURE_BITRATE_MBPS..MAX_CAPTURE_BITRATE_MBPS) add("bitrateMbps")
    if (settings.sessionLimitBytes <= 0L) add("sessionLimit")
    if (settings.freeSpaceReserveBytes < 0L) add("freeSpaceReserve")
    if (captureFilenameTemplateError(settings.filenameTemplate) != null) add("filenameTemplate")
}

@Composable
private fun CaptureTab(
    s: CaptureWorkspaceState,
    a: CaptureWorkspaceActions,
    modifier: Modifier,
    settingsValid: Boolean,
) {
    Row(
        modifier.fillMaxSize().padding(14.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Column(
            Modifier.weight(1.1f).verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Panel("Device") {
                if (s.devices.isEmpty()) AppText("No devices discovered", color = tc().td)
                s.devices.forEach { d ->
                    Row(
                        Modifier.fillMaxWidth().padding(vertical = 2.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        RadioButton(
                            d.serial == s.selectedDeviceSerial,
                            { a.onDeviceSelected(d) },
                            enabled = d.available,
                        )
                        Column(Modifier.weight(1f)) {
                            AppText(
                                d.model + if (d.emulator) "  (emulator)" else "",
                                fontWeight = androidx.compose.ui.text.font.FontWeight.Medium,
                            )
                            AppText(
                                "${d.serial} · ${d.state}",
                                color = if (d.available) tc().ts else tc().td,
                                fontSize = 10.sp,
                                fontFamily = FontFamily.Monospace,
                            )
                        }
                    }
                }
                AppButton("Refresh devices", a.onRefreshDevices, enabled = !s.isBusy)
                if (s.devices.any { !it.available }) {
                    AppText(
                        "Unauthorized or offline devices cannot be selected. " +
                            "Unlock the device and accept the USB debugging prompt.",
                        color = tc().td,
                        fontSize = 10.sp,
                    )
                    AppButton("Install / troubleshooting guidance", a.onOpenInstallGuide, ButtonVariant.Ghost)
                }
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    AppButton(
                        "Start",
                        a.onStart,
                        ButtonVariant.Primary,
                        enabled = s.selectedDeviceSerial != null && !s.isRecording && !s.isBusy && settingsValid,
                    )
                    AppButton("Stop", a.onStop, enabled = s.isRecording)
                    AppButton("Screenshot", a.onScreenshot, enabled = s.isRecording && !s.isBusy)
                    AppButton("Open snapshot", a.onOpenSnapshot, enabled = s.selectedSessionId != null && !s.isBusy)
                }
            }
            Panel("Current session") {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(18.dp)) {
                    Metric("Elapsed", s.elapsedText)
                    Metric("Storage", s.storageText)
                    Metric("Video", s.videoStatus)
                }
                s.latestReadableTimestamp?.let { AppText("Latest readable: $it", color = tc().ts, fontSize = 10.sp) }
                s.error?.let { AppText(it, color = Color(0xffd44f4f)) }
                s.status?.let { AppText(it, color = tc().ac) }
            }
            Panel("Recent log preview (read-only)") {
                if (s.previewRows.isEmpty()) AppText("No readable rows yet", color = tc().td)
                else LazyColumn(Modifier.fillMaxWidth().heightIn(max = 300.dp)) {
                    val rows = if (s.previewLatestRows) s.previewRows.takeLast(50000) else s.previewRows.take(50000)
                    items(rows) { row ->
                        AppText(row, fontFamily = FontFamily.Monospace, fontSize = 10.sp, maxLines = 1,
                            modifier = Modifier.fillMaxWidth().padding(vertical = 1.dp))
                    }
                }
            }
            Panel("Recovered sessions") {
                if (s.sessions.isEmpty()) AppText("No retained sessions", color = tc().td)
                s.sessions.forEach { session ->
                    Row(
                        Modifier.fillMaxWidth().padding(vertical = 3.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        val selected = s.selectedSessionId == session.id
                        Column(Modifier.weight(1f)) {
                            AppText("${session.device.model} · ${session.status}")
                            AppText(session.directory.name, color = tc().td, fontSize = 10.sp)
                        }
                        AppButton(
                            if (selected) "Selected" else "Select",
                            { a.onSelectSession(session.id) },
                            enabled = !selected,
                        )
                        if (session.status != CaptureStatus.RECORDING) {
                            AppButton(
                                "Delete",
                                { a.onDeleteSession(session.id) },
                                isDanger = true,
                                variant = ButtonVariant.Ghost,
                            )
                        }
                    }
                }
            }
        }
        Column(
            Modifier.weight(.9f).verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            SavePanel(s, a)
            AlignmentPanel(s, a)
        }
    }
}

@Composable private fun SavePanel(s: CaptureWorkspaceState, a: CaptureWorkspaceActions) {
    var range by remember { mutableStateOf(CaptureRange.ALL) }
    var custom by remember { mutableStateOf("5") }
    val selected = s.sessions.firstOrNull { it.id == s.selectedSessionId }
    var includeVideo by remember(selected?.id, selected?.settings?.recordVideo) {
        mutableStateOf(selected?.settings?.recordVideo == true)
    }
    val rangeLabel = mapOf(
        CaptureRange.ALL to "All available",
        CaptureRange.LAST_FIVE to "Last 5 minutes",
        CaptureRange.LAST_TEN to "Last 10 minutes",
        CaptureRange.CUSTOM to "Custom range",
        CaptureRange.SINCE_SAVE to "Since last save",
    )
    Panel("Save ZIP") {
        AppText("Destination", color = tc().td, fontSize = 10.sp)
        Row(verticalAlignment = Alignment.CenterVertically) {
            AppText(
                s.saveDirectory?.path ?: "Choose a directory",
                modifier = Modifier.weight(1f),
                maxLines = 1,
            )
            AppButton("Choose…", a.onChooseSaveDirectory)
        }
        CaptureRange.values().forEach { r ->
            Row(verticalAlignment = Alignment.CenterVertically) {
                RadioButton(range == r, { range = r })
                AppText(rangeLabel.getValue(r), fontSize = 11.sp)
            }
        }
        val customMinutes = custom.toIntOrNull()
        val customValid = range != CaptureRange.CUSTOM || customMinutes != null && customMinutes > 0
        if (range == CaptureRange.CUSTOM) {
            InlineField(custom, { custom = it.filter(Char::isDigit) }, "minutes", modifier = Modifier.fillMaxWidth())
            if (!customValid) {
                AppText("Enter a positive number of minutes.", color = Color(0xffd44f4f), fontSize = 10.sp)
            }
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            Checkbox(includeVideo, { includeVideo = it })
            AppText("Include video")
        }
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            AppButton("Save ZIP", {
                a.onSave(CaptureExportRequest(
                    s.saveDirectory ?: File("."), range, custom.toIntOrNull() ?: 5,
                    includeVideo, selected?.elapsedMs ?: 0L,
                ))
            }, ButtonVariant.Primary, enabled = selected != null && !s.isBusy && customValid)
            if (s.isBusy) AppButton("Cancel", a.onCancelExport, ButtonVariant.Ghost)
        }
        if (selected == null) {
            AppText("Select a retained session to export.", color = tc().td, fontSize = 10.sp)
        }
    }
}

@Composable private fun AlignmentPanel(s: CaptureWorkspaceState, a: CaptureWorkspaceActions) {
    var offset by remember { mutableStateOf(s.manualOffsetMs.toString()) }
    Panel("Video alignment") {
        InlineField(
            offset,
            {
                offset = it.filter { c -> c == '-' || c.isDigit() }
                offset.toLongOrNull()?.let(a.onManualOffsetChanged)
            },
            "offset in milliseconds",
            modifier = Modifier.fillMaxWidth(),
        )
    }
}

@Composable
private fun SettingsTab(
    s: CaptureWorkspaceState,
    a: CaptureWorkspaceActions,
    modifier: Modifier,
    setFieldValidity: (String, Boolean) -> Unit,
) {
    var cfg by remember(s.settings) { mutableStateOf(s.settings) }

    fun update(next: CaptureSettings) {
        cfg = next
        a.onSettingsChanged(next)
    }
    Column(
        modifier.verticalScroll(rememberScrollState()).padding(14.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Panel("Tools") {
            ToolField("ADB path", cfg.adbPath, {
                update(cfg.copy(adbPath = it))
                setFieldValidity("adbPath", it.isBlank() || File(it).isFile)
            }, a.onBrowseAdb)
            ToolField("scrcpy path", cfg.scrcpyPath, {
                update(cfg.copy(scrcpyPath = it))
                setFieldValidity("scrcpyPath", it.isBlank() || File(it).isFile)
            }, a.onBrowseScrcpy)
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                AppButton("Recheck tools", a.onRecheckTools)
                AppButton("Install guidance", a.onOpenInstallGuide, ButtonVariant.Ghost)
            }
            s.toolsStatus?.let { AppText(it, color = tc().ts, fontSize = 10.sp) }
        }
        Panel("Log buffers") {
            listOf("main", "system", "crash", "events", "radio").forEach { name ->
                CheckRow(name, name in cfg.buffers) { checked ->
                    val next = if (checked) cfg.buffers + name else cfg.buffers - name
                    update(cfg.copy(buffers = next.distinct()))
                }
            }
            CheckRow("Include buffered logs", cfg.includeBufferedLogs) {
                update(cfg.copy(includeBufferedLogs = it))
            }
        }
        Panel("Video and limits") {
            CheckRow("Record video", cfg.recordVideo) { update(cfg.copy(recordVideo = it)) }
            CheckRow("Mirror device", cfg.mirror) { update(cfg.copy(mirror = it)) }
            CheckRow("Capture audio", cfg.audio) { update(cfg.copy(audio = it)) }
            NumericField("Max size", cfg.maxSize.toString()) {
                val value = it.toIntOrNull()
                setFieldValidity("maxSize", value != null && value in MIN_CAPTURE_SIZE..MAX_CAPTURE_SIZE)
                value?.let { update(cfg.copy(maxSize = it)) }
            }
            NumericField("Max FPS", cfg.maxFps.toString()) {
                val value = it.toIntOrNull()
                setFieldValidity("maxFps", value != null && value in MIN_CAPTURE_FPS..MAX_CAPTURE_FPS)
                value?.let { update(cfg.copy(maxFps = it)) }
            }
            NumericField("Bitrate Mbps", cfg.bitrateMbps.toString()) {
                val value = it.toIntOrNull()
                setFieldValidity(
                    "bitrateMbps",
                    value != null && value in MIN_CAPTURE_BITRATE_MBPS..MAX_CAPTURE_BITRATE_MBPS,
                )
                value?.let { update(cfg.copy(bitrateMbps = it)) }
            }
            NumericField("Session limit GiB", (cfg.sessionLimitBytes / GIB).toString()) {
                val value = it.toLongOrNull()
                setFieldValidity("sessionLimit", value != null && value > 0)
                value?.let { update(cfg.copy(sessionLimitBytes = it * GIB)) }
            }
            NumericField("Reserve GiB", (cfg.freeSpaceReserveBytes / GIB).toString()) {
                val value = it.toLongOrNull()
                setFieldValidity("freeSpaceReserve", value != null && value >= 0)
                value?.let { update(cfg.copy(freeSpaceReserveBytes = it * GIB)) }
            }
        }
        Panel("Naming") {
            ToolField("Template", cfg.filenameTemplate, {
                update(cfg.copy(filenameTemplate = it))
                setFieldValidity("filenameTemplate", captureFilenameTemplateError(it) == null)
            })
            ToolField("Label", cfg.label, { update(cfg.copy(label = it)) })
            AppText(
                s.templatePreview ?: "Preview will appear when a session is selected",
                color = tc().ts,
                fontFamily = FontFamily.Monospace,
                fontSize = 10.sp,
            )
        }
    }
}

@Composable private fun DiagnosticsTab(s: CaptureWorkspaceState, modifier: Modifier) {
    var open by remember { mutableStateOf(true) }
    Column(modifier.verticalScroll(rememberScrollState()).padding(14.dp)) {
        SectionHeader("Diagnostics", expanded = open, onToggle = { open = !open })
        if (open) {
            s.diagnostics.forEach {
                AppText(it, fontFamily = FontFamily.Monospace, fontSize = 10.sp,
                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 2.dp))
            }
        }
    }
}

@Composable
private fun Panel(title: String, content: @Composable ColumnScope.() -> Unit) {
    Column(
        Modifier.fillMaxWidth().border(BorderStroke(1.dp, tc().br), CORNER_SM).padding(10.dp),
        verticalArrangement = Arrangement.spacedBy(5.dp),
    ) {
        AppText(title, color = tc().ts,
            fontWeight = androidx.compose.ui.text.font.FontWeight.SemiBold, fontSize = 11.sp)
        content()
    }
}

@Composable
private fun RowScope.Metric(label: String, value: String) {
    Column(Modifier.weight(1f)) {
        AppText(label, color = tc().td, fontSize = 10.sp)
        AppText(value, fontFamily = FontFamily.Monospace, fontSize = 11.sp)
    }
}

@Composable
private fun CheckRow(label: String, checked: Boolean, onChecked: (Boolean) -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Checkbox(checked, onChecked)
        AppText(label, fontSize = 11.sp)
    }
}

@Composable private fun ToolField(
    label: String,
    value: String,
    onValue: (String) -> Unit,
    browse: (() -> Unit)? = null,
) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            AppText(label, color = tc().td, fontSize = 10.sp)
            InlineField(value, onValue, modifier = Modifier.fillMaxWidth())
        }
        browse?.let { AppButton("Browse…", it) }
    }
}

@Composable private fun NumericField(label: String, value: String, onValue: (String) -> Unit) {
    ToolField(label, value, { onValue(it.filter(Char::isDigit)) })
}
