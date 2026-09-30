@file:OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)

package com.indagium.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.TooltipArea
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CheckboxDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.PopupProperties
import com.indagium.capture.AdbMdnsService
import com.indagium.capture.CaptureDevice
import com.indagium.capture.CaptureSession
import com.indagium.capture.CaptureSettings
import com.indagium.capture.CaptureStatus
import com.indagium.capture.DeviceLogActivity
import com.indagium.capture.LogBufferSizeChoice
import com.indagium.capture.LogTagLevel
import com.indagium.capture.bufferSizeButtonLabel
import com.indagium.capture.deviceLogStatusText
import com.indagium.capture.deviceLoggingSummaryLine
import com.indagium.capture.deviceStateGuidance
import com.indagium.capture.logLevelButtonLabel
import com.indagium.capture.mainBufferIsSmall
import com.indagium.capture.matchingBufferSizeChoice
import kotlinx.coroutines.delay
import kotlin.math.roundToInt

private const val DEVICE_REFRESH_INTERVAL_MS = 3_000L

/** Extracts a version number after the word "version" (case-insensitive), e.g. "1.0.41" out of
 *  "Android Debug Bridge version 1.0.41". */
private val TOOL_VERSION_NUMBER = Regex("""version\s+([0-9]+(?:\.[0-9]+)*)""", RegexOption.IGNORE_CASE)

/** Short "adb 1.0.41" label for the Devices panel header when adb validated successfully —
 *  extracted from the first line of [CaptureService.toolStatus] (the adb line; the second line is
 *  scrcpy's — see [toolStatusLine]) rather than showing that whole, sometimes multi-line, raw
 *  `adb version` blob inline. Falls back to the untrimmed first line when no version number is
 *  found in it, so an unexpected adb build still shows something instead of a blank header. Null
 *  only when there is no status yet at all. */
internal fun adbShortStatusLabel(toolStatus: String?): String? {
    val firstLine = toolStatus?.lineSequence()?.firstOrNull()?.trim().orEmpty()
    if (firstLine.isEmpty()) return null
    val version = TOOL_VERSION_NUMBER.find(firstLine)?.groupValues?.get(1)
    return if (version != null) "adb $version" else firstLine
}

/** The device-capture launcher's content, embeddable in any surface that already knows which tab
 *  it belongs to. Split out of the old standalone `CaptureLauncher` composable so ui/HomeScreen.kt
 *  can host it as the right half of the home tab, side by side with the "Open a log" zone, instead
 *  of centered alone in its own tab — the launcher's tab (`launcherTabId`) IS the home tab (see
 *  [LogTab.isCaptureLauncher]'s KDoc), so this takes that id as a parameter rather than re-deriving
 *  it from `state.activeTab()`, which only worked when the launcher was the sole content of the
 *  active tab. One `verticalScroll` here (not a max-width-clamped centered column): the caller
 *  decides layout, this only decides content. */
@Composable
internal fun CaptureLauncherContent(
    state: AppState,
    launcherTabId: String,
    modifier: Modifier = Modifier,
    onReclaimFocus: () -> Unit = {},
) {
    val service = state.captureService
    val draft = state.captureLaunchSettings(launcherTabId)
    // Local, presentation-only selection of which discovered device "Start capture" acts on — not
    // persisted, not part of AppState, and re-resolved against the live device list every
    // recomposition so a 3s background refresh (below) never silently resets it.
    var selectedSerial by remember { mutableStateOf<String?>(null) }
    val liveSelectedDevice = service.devices.firstOrNull { it.serial == selectedSerial }
    // Wi-Fi pairing dialogs: the QR one, and the code one (null = closed, "" = manual address entry,
    // otherwise prefilled from a "Ready to pair" row). Presentation-only, like selectedSerial.
    var qrPairingOpen by remember { mutableStateOf(false) }
    var codePairingAddress by remember { mutableStateOf<String?>(null) }
    // "Restart adb as root" (DeviceLoggingPanelContent below) restarts adbd, which briefly drops the
    // device off `adb devices` while it reconnects. Without this, the plain `?: service.devices.
    // firstOrNull()` fallback below would silently swap the Device logging panel to a different
    // device (or hide it) for that window. Remembered only while this serial is actually rooting —
    // the instant that clears, the live list is authoritative again.
    var lastKnownDeviceBySerial by remember { mutableStateOf<CaptureDevice?>(null) }
    LaunchedEffect(liveSelectedDevice) {
        if (liveSelectedDevice != null) lastKnownDeviceBySerial = liveSelectedDevice
    }
    val isSelectedDeviceRooting = selectedSerial != null &&
        service.deviceLogStates[selectedSerial]?.activity == DeviceLogActivity.ROOTING
    val selectedDevice = liveSelectedDevice
        ?: lastKnownDeviceBySerial?.takeIf { isSelectedDeviceRooting && it.serial == selectedSerial }
        ?: service.devices.firstOrNull()
    LaunchedEffect(service) {
        service.refreshDevices(force = true)
        while (true) {
            delay(DEVICE_REFRESH_INTERVAL_MS)
            service.refreshDevices()
        }
    }
    // Tools folds into the Devices panel header as one muted line once adb is found and valid (item
    // 2 of the "make it compact" pass) — a whole extra bordered panel just to say "adb 1.0.41" ate
    // vertical space the New tab can't spare. An invalid/missing adb, or any error, keeps the full
    // panel below exactly as before: that's the one state where this needs its old prominence.
    val toolsFoldedIntoDevicesHeader = service.toolStatus != null && service.adbAvailable && service.error == null
    Column(
        modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 24.dp, vertical = 40.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            AppText("Capture from a device", fontSize = 20.sp, color = tc().tx)
            AppText(
                "Choose an Android device. The capture opens as a normal streaming log tab.",
                color = tc().td,
                fontSize = 12.sp,
            )
        }
        if (!toolsFoldedIntoDevicesHeader) {
            service.toolStatus?.let { status ->
                LauncherPanel("Capture tools") {
                    AppText(status, color = tc().ts, fontFamily = FontFamily.Monospace, fontSize = 11.sp)
                    service.error?.let { AppText(it, color = DANGER_RED, fontSize = 11.sp) }
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        AppButton("Recheck tools", { service.recheckToolsFromSettings() }, enabled = !service.discovering)
                        AppButton("Install guidance", { service.openInstallGuidanceFromSettings() }, ButtonVariant.Ghost)
                    }
                }
            }
        }
        LauncherPanel(header = {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween) {
                AppText("Devices", color = tc().ts, fontSize = 11.sp)
                if (toolsFoldedIntoDevicesHeader) {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        adbShortStatusLabel(service.toolStatus)?.let {
                            AppText(
                                it, color = tc().td, fontFamily = FontFamily.Monospace, fontSize = 10.sp,
                                maxLines = 1, overflow = TextOverflow.Ellipsis,
                            )
                        }
                        AppButton("Recheck tools", { service.recheckToolsFromSettings() }, ButtonVariant.Ghost, enabled = !service.discovering)
                        AppButton("Install guidance", { service.openInstallGuidanceFromSettings() }, ButtonVariant.Ghost)
                    }
                }
            }
        }) {
            when {
                service.devices.isNotEmpty() -> service.devices.forEach { device ->
                    CaptureDeviceRow(
                        device = device,
                        selected = device.serial == selectedDevice?.serial,
                        onSelect = { selectedSerial = device.serial },
                    )
                }
                // Phones on a pairing screen are listed below the (empty) device rows, so say so
                // instead of the more discouraging "No devices discovered".
                service.pairingCandidates.isNotEmpty() -> AppText("No connected devices", color = tc().td)
                // Never discovered yet (the launcher's own first refresh hasn't landed): a same-
                // height placeholder instead of "No devices discovered", so a device that shows up a
                // moment later doesn't read as if the panel first claimed there was nothing there —
                // see CaptureCoordinator.kt's `hasCheckedDevicesOnce` doc (item 2 of the flicker fix).
                !service.hasCheckedDevicesOnce -> AppText(
                    "Looking for devices…",
                    color = tc().td,
                    modifier = Modifier.padding(horizontal = 10.dp, vertical = 8.dp),
                )
                else -> {
                    AppText("No devices discovered", color = tc().td)
                    AppButton("Refresh devices", { service.refreshDevices(force = true) }, ButtonVariant.Ghost, enabled = !service.discovering)
                }
            }
            service.pairingCandidates.forEach { candidate ->
                PairingCandidateRow(candidate, onPair = { codePairingAddress = candidate.address })
            }
            if (service.adbAvailable) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    AppButton("Pair over Wi-Fi…", { qrPairingOpen = true }, ButtonVariant.Ghost)
                    if (service.mdnsAvailable == false) {
                        AppButton("Pair with code…", { codePairingAddress = "" }, ButtonVariant.Ghost)
                    }
                }
                if (service.mdnsAvailable == false) {
                    AppText("Wi-Fi discovery unavailable (network may block mDNS)", color = tc().td, fontSize = 10.sp)
                }
            }
        }
        if (qrPairingOpen) {
            QrPairingDialog(
                service = service,
                onConnected = { serial -> selectedSerial = serial },
                onUseCode = {
                    qrPairingOpen = false
                    codePairingAddress = ""
                },
                onDismiss = {
                    qrPairingOpen = false
                    onReclaimFocus()
                },
            )
        }
        codePairingAddress?.let { address ->
            CodePairingDialog(
                service = service,
                initialAddress = address,
                onConnected = { serial -> selectedSerial = serial },
                onDismiss = {
                    codePairingAddress = null
                    onReclaimFocus()
                },
            )
        }
        selectedDevice?.let { device ->
            DeviceLoggingPanel(
                state = state,
                service = service,
                device = device,
                noLiveCapture = state.liveCaptureTabId == null,
                onReclaimFocus = onReclaimFocus,
            )
        }
        CaptureBeforeStartRow(state = state, launcherTabId = launcherTabId, draft = draft, onReclaimFocus = onReclaimFocus)
        AppButton(
            "Start capture",
            { selectedDevice?.let { state.startCaptureTab(it) } },
            ButtonVariant.Primary,
            enabled = selectedDevice?.available == true && state.liveCaptureTabId == null && !state.captureStartInProgress,
            modifier = Modifier.fillMaxWidth(),
        )
        // The header and its deferred content lambda must close over the same snapshot. If only the
        // header observes service.sessions, Compose can recompose the count while skipping the
        // stable content lambda and leave an empty-state body on screen.
        val unfinishedSessions = service.sessions.filter { it.status != CaptureStatus.RECORDING }
        UnfinishedCaptureSessionsPanel(unfinishedSessions) { retained ->
            UnfinishedSessionsSection(state = state, retained = retained)
        }
    }
}

/** One selectable row in the Devices card: model (SemiBold), serial (mono, dim) and a readiness
 * pill on the right. `adb` doesn't expose a transport field on [CaptureDevice], so unlike the
 * design's "serial + transport" copy, only the serial is shown here. An actionable hint (from the
 * same [deviceStateGuidance] the recorder itself uses to reject a start) appears under a row that
 * isn't ready. */
@Composable
private fun CaptureDeviceRow(device: CaptureDevice, selected: Boolean, onSelect: () -> Unit) {
    val colors = tc()
    val shape = CORNER_MD
    Column(
        Modifier.fillMaxWidth()
            .clip(shape)
            .background(if (selected) colors.hv else Color.Transparent, shape)
            .clickable(onClick = onSelect)
            .padding(horizontal = 10.dp, vertical = 8.dp),
    ) {
        Row(
            Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Column(Modifier.weight(1f)) {
                AppText(device.model, color = colors.tx, fontSize = 12.sp, fontWeight = FontWeight.SemiBold,
                    maxLines = 1, overflow = TextOverflow.Ellipsis)
                AppText(device.serial, color = colors.td, fontSize = 10.sp, fontFamily = FontFamily.Monospace,
                    maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            if (device.wireless) WifiTag()
            CaptureDeviceStatePill(device)
        }
        deviceStateGuidance(device.state, device.wireless)?.let { hint ->
            AppText(
                hint, color = DANGER_RED, fontSize = 10.sp, maxLines = 2, overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(top = 4.dp),
            )
        }
    }
}

/** Small neutral "Wi-Fi" tag on a device row for a phone attached over Wireless debugging. */
@Composable
private fun WifiTag() {
    val colors = tc()
    Box(
        Modifier.border(1.dp, colors.br, RoundedCornerShape(50)).padding(horizontal = 8.dp, vertical = 2.dp),
    ) {
        AppText("Wi-Fi", color = colors.td, fontSize = 10.sp, fontWeight = FontWeight.Medium)
    }
}

/** An inline "Ready to pair" row for a phone showing its pairing screen. Deliberately a row the
 * user opts into rather than a popup, so another person's phone on the same network can never put
 * a modal on this screen. */
@Composable
private fun PairingCandidateRow(candidate: AdbMdnsService, onPair: () -> Unit) {
    val colors = tc()
    Row(
        Modifier.fillMaxWidth().padding(horizontal = 10.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Column(Modifier.weight(1f)) {
            AppText("Ready to pair over Wi-Fi", color = colors.tx, fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
            AppText(
                candidate.address, color = colors.td, fontSize = 10.sp, fontFamily = FontFamily.Monospace,
                maxLines = 1, overflow = TextOverflow.Ellipsis,
            )
        }
        AppButton("Pair…", onPair, ButtonVariant.Ghost)
    }
}

/** "ready" in a calm success tone, or the raw adb state (unauthorized/offline/…) in [DANGER_RED].
 * Reuses [tc]'s `ok` role — the app's existing derived "confirmation" color (see its doc in
 * Theme.kt) — rather than a fixed green, since no literal-green token exists in [ThemeColors] and
 * every other role here already tracks the active preset instead of a fixed hue. */
@Composable
private fun CaptureDeviceStatePill(device: CaptureDevice) {
    val colors = tc()
    val ready = device.available
    val tone = if (ready) colors.ok else DANGER_RED
    val label = if (ready) "ready" else device.state
    Box(
        Modifier.background(tone.copy(alpha = 0.15f), RoundedCornerShape(50))
            .border(1.dp, tone.copy(alpha = 0.4f), RoundedCornerShape(50))
            .padding(horizontal = 8.dp, vertical = 2.dp),
    ) {
        AppText(label, color = tone, fontSize = 10.sp, fontWeight = FontWeight.Medium)
    }
}

/** The pre-start toggles as one compact row instead of a stacked list — each [CheckRow] gets an
 * equal share of the row's width via [Modifier.weight] so three fixed-width-hungry rows sit side
 * by side instead of each claiming the full row (which is what [CheckRow]'s own `fillMaxWidth`
 * would otherwise do if placed directly in a [Row]). Buffer mode and its custom-buffer picker stay
 * stacked below, since they don't fit the same one-line treatment. */
@Composable
private fun CaptureBeforeStartRow(
    state: AppState,
    launcherTabId: String,
    draft: CaptureSettings,
    onReclaimFocus: () -> Unit,
) {
    val edit: CaptureSettingsEdit = { transform -> state.updateCaptureLaunchSettings(launcherTabId, transform) }
    LauncherPanel("Before start") {
        CaptureStartOptions(
            settings = draft,
            onReclaimFocus = onReclaimFocus,
            nativeMediaSupport = state.captureNativeMediaSupport,
            scrcpyAvailable = state.captureToolResolution?.scrcpyPath != null,
            edit = edit,
        )
    }
}

/** Fixes the inverted custom-buffer toggle (was: ticking an unticked buffer removed it, a no-op,
 * and unticking a ticked one re-added it — `kernel`/`radio`/`events` could never be selected and
 * `main`/`system`/`crash` could never be cleared). Add/remove are mutually exclusive by
 * construction, so this can never introduce a duplicate, and untouched entries keep their order. */
internal fun toggleCaptureBuffer(buffers: List<String>, name: String): List<String> =
    if (name in buffers) buffers - name else buffers + name

@Composable
private fun LauncherPanel(title: String, content: @Composable () -> Unit) {
    LauncherPanel(header = { AppText(title, color = tc().ts, fontSize = 11.sp) }, content = content)
}

/** [LauncherPanel] overload for a header row richer than one label — e.g. the Devices panel folding
 *  a muted adb status line and its Recheck/Install actions in next to the title (item 2 of the
 *  "make it compact" pass). */
@Composable
private fun LauncherPanel(header: @Composable () -> Unit, content: @Composable () -> Unit) {
    Column(
        Modifier.fillMaxWidth().border(BorderStroke(1.dp, tc().br), CORNER_MD).padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        header()
        content()
    }
}

/** Shared panel seam: keep the displayed count and deferred body tied to the same retained-list
 * snapshot so Compose cannot update only the header while skipping a stable body lambda. */
@Composable
internal fun UnfinishedCaptureSessionsPanel(
    retained: List<CaptureSession>,
    content: @Composable (List<CaptureSession>) -> Unit,
) {
    LauncherPanel("Unfinished sessions · ${retained.size}") {
        content(retained)
    }
}

private const val DEVICE_LOG_OVERRIDES_ROW_LIMIT = 5

/** "Device logging": a collapsible section — expanded by default, collapsed to one summary
 *  line ([deviceLoggingSummaryLine]) plus a Refresh button usable without expanding, plus the
 *  [SectionHeader] chevron (the same collapsible idiom CaptureStrip.kt's `CaptureMarkerSection`
 *  uses, for consistent styling). Force-expands whenever there's a device-logging error or the
 *  small-buffer warning, so a real problem is never hidden behind a remembered collapse; otherwise
 *  follows [AppSettings.deviceLoggingPanelExpanded], one persisted preference for every device.
 *
 *  The refresh-on-device-change effect lives here rather than in [DeviceLoggingPanelContent] so the
 *  summary line keeps loading fresh data even while the section is collapsed and that content isn't
 *  composed at all. */
@Composable
private fun DeviceLoggingPanel(
    state: AppState,
    service: CaptureService,
    device: CaptureDevice,
    noLiveCapture: Boolean,
    onReclaimFocus: () -> Unit,
) {
    LaunchedEffect(device.serial) { service.refreshDeviceLog(device.serial) }
    val logState = service.deviceLogStates[device.serial]
    val hasProblem = logState?.error != null || mainBufferIsSmall(logState?.bufferSizes.orEmpty())
    val expanded = state.settings.deviceLoggingPanelExpanded || hasProblem
    Column(Modifier.fillMaxWidth().border(BorderStroke(1.dp, tc().br), CORNER_MD)) {
        SectionHeader(
            title = deviceLoggingSummaryLine(logState),
            trailing = {
                AppButton(
                    "Refresh", { service.refreshDeviceLog(device.serial) }, ButtonVariant.Ghost,
                    enabled = logState?.busy != true,
                )
            },
            expanded = expanded,
            onToggle = { state.toggleDeviceLoggingPanelExpanded() },
        )
        if (expanded) {
            Column(
                Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                DeviceLoggingPanelContent(service, device, noLiveCapture, onReclaimFocus)
            }
        }
    }
}

/** "Device logging" body: logd ring-buffer sizes and the `log.tag`/`log.tag.<TAG>` filter level for
 *  the selected device (items 2/3). Lets the user apply a buffer size or the global level
 *  immediately — both are DEVICE settings, not capture-session settings, hence the explicit hint
 *  and hence this reads/writes [CaptureService.deviceLogStates] (keyed by serial) rather than
 *  anything on the capture-launch draft. All three adb reads (`logcat -g`, `getprop log.tag`,
 *  `getprop`) and every apply happen off the caller's thread inside [CaptureService]; this
 *  composable only renders whatever state is there and fires the calls that change it. Rendered
 *  only while [DeviceLoggingPanel] is expanded — the refresh-on-device-change effect lives on that
 *  wrapper instead, so the summary line up there keeps working while this is collapsed. */
@Composable
private fun DeviceLoggingPanelContent(
    service: CaptureService,
    device: CaptureDevice,
    noLiveCapture: Boolean,
    onReclaimFocus: () -> Unit,
) {
    val tc = tc()
    val logState = service.deviceLogStates[device.serial]
    val busy = logState?.busy == true
    val sizes = logState?.bufferSizes.orEmpty()

    AppText(
        "These change the DEVICE's own settings: buffer size usually persists across reboots, " +
            "the log level does not.",
        color = tc.td, fontSize = 10.sp, maxLines = 2,
    )

    // One line, two dropdowns, side by side — replaces the old stacked SegmentedControls (item 3):
    // "Buffer size [4 MB ▾]" / "Log level [Verbose ▾]". Each shows the CURRENT device value as its
    // button text (via bufferSizeButtonLabel/logLevelButtonLabel), not a pending user pick, so the
    // button itself never lies about what's actually on the device between a click and the re-read
    // landing — see the status line below for the same values spelled out explicitly.
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        val currentChoice = matchingBufferSizeChoice(sizes)
        DeviceLogDropdown(
            label = "Buffer size",
            buttonText = bufferSizeButtonLabel(sizes),
            enabled = !busy,
            onReclaimFocus = onReclaimFocus,
            modifier = Modifier.weight(1f),
        ) { close ->
            LogBufferSizeChoice.entries.forEach { choice ->
                DeviceLogDropdownItem(choice.label, active = choice == currentChoice) {
                    service.setDeviceLogBufferSize(device.serial, choice)
                    close()
                }
            }
        }
        DeviceLogDropdown(
            label = "Log level",
            buttonText = logLevelButtonLabel(logState?.globalLevel),
            enabled = !busy,
            onReclaimFocus = onReclaimFocus,
            modifier = Modifier.weight(1f),
        ) { close ->
            DeviceLogDropdownItem("Default (device)", active = logState?.globalLevel == null) {
                service.setDeviceGlobalLogLevel(device.serial, null)
                close()
            }
            // LogTagLevel.selectable's own order (V D I W E S — see that field's doc for why
            // ASSERT is excluded here even though it can still show up read-only under Per-tag
            // overrides below).
            LogTagLevel.selectable.forEach { level ->
                DeviceLogDropdownItem(level.label, active = logState?.globalLevel == level) {
                    service.setDeviceGlobalLogLevel(device.serial, level)
                    close()
                }
            }
        }
    }

    // Explicit confirmation that a change actually landed, right under the dropdowns: built from
    // the re-read state (formatDeviceLogStatusLine via deviceLogStatusText), never from the dropdown
    // selection itself — see formatDeviceLogStatusLine's own doc. deviceLogStatusText keeps the last
    // good line on screen (with a "Refreshing…" suffix) for a background re-read and reserves the
    // bare "Applying…" for an actual user-requested change — see its own doc (item 3 of the New-tab
    // flicker fix): a device switch or the 3s poll must never blank a line this panel already has
    // good data for.
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        AppText(
            deviceLogStatusText(logState),
            color = tc.ts, fontSize = 11.sp, fontFamily = FontFamily.Monospace,
            maxLines = 1, overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )
    }
    if (mainBufferIsSmall(sizes)) {
        AppText("Small buffers can drop lines during bursts", color = DANGER_RED, fontSize = 10.sp)
    }
    logState?.error?.let { message ->
        Row(verticalAlignment = Alignment.Top, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            AppText(message, color = DANGER_RED, fontSize = 10.sp, maxLines = 3, modifier = Modifier.weight(1f))
            // Only for a permission failure this serial hasn't already tried rooting for (see
            // DeviceLogState.failedChange's own doc), and never while a capture is live on this
            // device — restarting adbd kills the logcat stream mid-recording.
            if (logState.failedChange != null && noLiveCapture) {
                TooltipArea(
                    tooltip = {
                        ToolbarTooltip(
                            "Restarts adbd on the device with root (userdebug/eng builds only), then retries the " +
                                "change. Production builds refuse.",
                        )
                    },
                ) {
                    AppButton(
                        "Restart adb as root",
                        { service.restartAdbAsRoot(device.serial) },
                        ButtonVariant.Ghost,
                        enabled = !busy,
                    )
                }
            }
        }
    }

    val overrides = logState?.perTagOverrides.orEmpty()
    if (overrides.isNotEmpty()) {
        Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
            AppText("Per-tag overrides", color = tc.td, fontSize = 10.sp)
            overrides.entries.take(DEVICE_LOG_OVERRIDES_ROW_LIMIT).forEach { (tag, level) ->
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    AppText(
                        "$tag: $level", color = tc.ts, fontSize = 10.sp, fontFamily = FontFamily.Monospace,
                        maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f),
                    )
                    AppButton(
                        "Remove", { service.clearDeviceLogTagOverride(device.serial, tag) },
                        ButtonVariant.Ghost, enabled = !busy,
                    )
                }
            }
            if (overrides.size > DEVICE_LOG_OVERRIDES_ROW_LIMIT) {
                AppText("+${overrides.size - DEVICE_LOG_OVERRIDES_ROW_LIMIT} more", color = tc.td, fontSize = 10.sp)
            }
        }
    }
}

/** A labeled "Buffer size"/"Log level" field that opens a [Popup] menu, replacing the old
 *  SegmentedControls (item 3). Same shape as HomeScreen.kt's `RecentFilterPill` — a bordered
 *  clickable field plus a [Popup]-hosted [Column] of rows — since that's the New tab's own existing
 *  convention for a labeled value picker; rendered as a full-width field with a caption above it
 *  rather than a compact pill, since this sits in a fixed two-column row instead of a filter strip.
 *  [onReclaimFocus] is called both on dismiss and after picking a row, per this codebase's
 *  clickable/Popup focus rule (see CLAUDE.md's "A dismissed Popup... steals keyboard focus" gotcha)
 *  — the same call HomeScreen.kt's own dropdowns make. */
@Composable
private fun DeviceLogDropdown(
    label: String,
    buttonText: String,
    enabled: Boolean,
    onReclaimFocus: () -> Unit,
    modifier: Modifier = Modifier,
    menu: @Composable (close: () -> Unit) -> Unit,
) {
    val tc = tc()
    var expanded by remember { mutableStateOf(false) }
    val shape = CORNER_MD
    Column(modifier, verticalArrangement = Arrangement.spacedBy(4.dp)) {
        AppText(label, color = tc.td, fontSize = 10.sp)
        Box {
            Row(
                Modifier
                    .fillMaxWidth()
                    .clip(shape)
                    .border(1.dp, tc.br, shape)
                    .let { base -> if (enabled) base.clickable { expanded = !expanded } else base }
                    .padding(horizontal = 8.dp, vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                AppText(
                    buttonText, color = if (enabled) tc.tx else tc.td, fontSize = 11.sp,
                    maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f),
                )
                AppText(if (expanded) "▾" else "▸", color = tc.ts, fontSize = 9.sp)
            }
            if (expanded) {
                val density = LocalDensity.current.density
                Popup(
                    alignment = Alignment.TopStart,
                    offset = IntOffset(0, (32 * density).roundToInt()),
                    onDismissRequest = { expanded = false; onReclaimFocus() },
                    properties = PopupProperties(focusable = true),
                ) {
                    Column(
                        Modifier.width(180.dp)
                            .background(tc.p, RoundedCornerShape(7.dp))
                            .border(1.dp, tc.br, RoundedCornerShape(7.dp))
                            .padding(vertical = 4.dp),
                    ) {
                        menu { expanded = false; onReclaimFocus() }
                    }
                }
            }
        }
    }
}

/** One row of a [DeviceLogDropdown]'s menu — accent tint when it's the device's current value,
 *  same visual language as `RecentFilterPill`'s own menu rows. */
@Composable
private fun DeviceLogDropdownItem(label: String, active: Boolean, onClick: () -> Unit) {
    val tc = tc()
    HoverBox(modifier = Modifier.fillMaxWidth(), onClick = onClick) {
        AppText(
            label,
            color = if (active) tc.ac else tc.tx,
            fontSize = 11.sp,
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
        )
    }
}

/** The "Unfinished sessions" panel body: item 3's multi-select + bulk delete on top of the
 * pre-existing per-row Open/Save ZIP/Open folder/Discard actions and single-row Discard confirm,
 * both left untouched below. Selection is local UI state, not [AppState] — it has no meaning
 * outside this one render of the panel — and is pruned to ids that still exist whenever the
 * retained-session list changes (a session can finish exporting, or be discarded from another
 * surface, out from under an open selection). */
@Composable
internal fun UnfinishedSessionsSection(state: AppState, retained: List<CaptureSession>) {
    var discardId by remember { mutableStateOf<String?>(null) }
    var selectedIds by remember { mutableStateOf<Set<String>>(emptySet()) }
    var confirmDeleteSelected by remember { mutableStateOf(false) }
    var deleteSelectedError by remember { mutableStateOf<String?>(null) }

    state.captureExportError?.let { AppText("Save failed: $it", color = DANGER_RED, fontSize = 10.sp) }
    val retainedIds = retained.map { it.id }.toSet()
    LaunchedEffect(retainedIds) { selectedIds = selectedIds.intersect(retainedIds) }

    if (retained.isEmpty()) {
        AppText("No interrupted sessions", color = tc().td)
        return
    }

    deleteSelectedError?.let { AppText(it, color = DANGER_RED, fontSize = 10.sp) }
    UnfinishedSessionsHeaderRow(
        allSelected = selectedIds.isNotEmpty() && selectedIds.size == retained.size,
        selectedCount = selectedIds.size,
        onToggleSelectAll = { selectedIds = if (selectedIds.size == retained.size) emptySet() else retainedIds },
        onDeleteSelected = { confirmDeleteSelected = true },
    )
    // Capped and scrollable: a user with 15+ retained sessions otherwise gets an
    // unbounded panel that pushes the "Start capture" button and everything below it
    // off-screen, crowding out the rest of the capture zone. ~44dp/row covers the
    // two-line (12sp + 10sp) text column plus the 8dp gap to the next row.
    BoundedScrollBox(rowLimit = 15, rowDp = 44) {
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            retained.forEach { session ->
                UnfinishedSessionRow(
                    state = state,
                    session = session,
                    selected = session.id in selectedIds,
                    onToggleSelected = {
                        selectedIds = if (session.id in selectedIds) selectedIds - session.id else selectedIds + session.id
                    },
                    discardId = discardId,
                    onDiscardId = { discardId = it },
                )
            }
        }
    }
    if (confirmDeleteSelected) {
        ConfirmDeleteSessionsDialog(
            selectedCount = selectedIds.size,
            requiresTyped = requiresTypedDeleteConfirmation(selectedIds.size, retained.size),
            onConfirm = {
                val failures = selectedIds.count { !state.discardRetainedCapture(it) }
                deleteSelectedError = if (failures > 0) "Couldn't delete $failures session(s)" else null
                selectedIds = emptySet()
                confirmDeleteSelected = false
            },
            onDismiss = { confirmDeleteSelected = false },
        )
    }
}

/** "Select all" + right-aligned bulk delete, above the retained-session list. */
@Composable
private fun UnfinishedSessionsHeaderRow(
    allSelected: Boolean,
    selectedCount: Int,
    onToggleSelectAll: () -> Unit,
    onDeleteSelected: () -> Unit,
) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.weight(1f)) {
            CheckRow(allSelected, onToggleSelectAll) { AppText("Select all", color = tc().tx, fontSize = 11.sp) }
        }
        AppButton(
            "Delete selected ($selectedCount)",
            onClick = onDeleteSelected,
            variant = ButtonVariant.Secondary,
            isDanger = true,
            enabled = selectedCount > 0,
        )
    }
}

/** One retained-session row: a leading selection checkbox, then the pre-existing model/status text
 * and Open/Save ZIP/Open folder/Discard action row, unchanged. */
@Composable
private fun UnfinishedSessionRow(
    state: AppState,
    session: CaptureSession,
    selected: Boolean,
    onToggleSelected: () -> Unit,
    discardId: String?,
    onDiscardId: (String?) -> Unit,
) {
    val tc = tc()
    Column {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Checkbox(
                checked = selected,
                onCheckedChange = { onToggleSelected() },
                colors = CheckboxDefaults.colors(checkedColor = tc.ac, uncheckedColor = tc.td, checkmarkColor = tc.bg),
                modifier = Modifier.size(16.dp),
            )
            Column(Modifier.weight(1f).padding(start = 6.dp)) {
                AppText("${session.device.model} · ${session.status}")
                AppText(session.directory.name, color = tc.td, fontSize = 10.sp)
            }
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                AppButton("Open", { state.openRetainedCapture(session.id) }, ButtonVariant.Secondary)
                AppButton("Save ZIP", { state.saveRetainedCapture(session.id) }, ButtonVariant.Secondary)
                AppButton("Open folder", { state.openRetainedCaptureFolder(session.id) }, ButtonVariant.Ghost)
                AppButton("Discard", { onDiscardId(session.id) }, ButtonVariant.Ghost)
            }
        }
        if (discardId == session.id) {
            AppText("Discard this retained capture? The raw session directory will be removed.", color = DANGER_RED, fontSize = 10.sp)
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                AppButton("Confirm discard", {
                    state.discardRetainedCapture(session.id)
                    onDiscardId(null)
                }, ButtonVariant.Primary)
                AppButton("Cancel", { onDiscardId(null) }, ButtonVariant.Ghost)
            }
        }
    }
}

/** Whether "Delete selected" must additionally make the user type "delete" before it's enabled —
 * true only when every retained session is selected and there are at least two of them (a lone
 * session's plain confirmation is already enough; see item 3's own spec). */
internal fun requiresTypedDeleteConfirmation(selectedCount: Int, totalCount: Int): Boolean =
    totalCount >= 2 && selectedCount == totalCount

/** Bulk-delete confirmation, styled like CaptureStrip.kt's `ConfirmDeleteMarkerDialog` (360dp
 * card, centered Cancel/Delete). When [requiresTyped] is set, Delete stays disabled until the
 * typed text trims to "delete" (case-insensitive). */
@Composable
private fun ConfirmDeleteSessionsDialog(
    selectedCount: Int,
    requiresTyped: Boolean,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
) {
    val tc = tc()
    var typed by remember { mutableStateOf("") }
    val canConfirm = !requiresTyped || typed.trim().equals("delete", ignoreCase = true)
    Dialog(onDismissRequest = onDismiss) {
        Column(
            Modifier.width(360.dp).background(tc.p, RoundedCornerShape(8.dp))
                .border(1.dp, tc.br, RoundedCornerShape(8.dp)).padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            val title = when {
                selectedCount == 1 -> "Delete this session?"
                requiresTyped -> "Delete all $selectedCount sessions?"
                else -> "Delete $selectedCount sessions?"
            }
            AppText(title, color = tc.tx, fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
            AppText(
                "The raw session folders are removed. This can't be undone.",
                color = tc.td,
                fontSize = 11.sp,
                maxLines = 3,
            )
            if (requiresTyped) {
                AppText("Type \"delete\" to confirm.", color = tc.td, fontSize = 11.sp)
                // Placeholder is not the word itself, so the field never looks already filled in.
                InlineField(value = typed, onValue = { typed = it }, placeholder = "Type here", modifier = Modifier.fillMaxWidth())
            }
            Row(
                Modifier.fillMaxWidth().padding(top = 8.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterHorizontally),
            ) {
                DialogActionButton("Cancel", active = false, onClick = onDismiss)
                DialogActionButton("Delete", active = true, danger = true, enabled = canConfirm, onClick = onConfirm)
            }
        }
    }
}
