package com.indagium.ui

import androidx.compose.foundation.BorderStroke
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
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.indagium.capture.CaptureDevice
import com.indagium.capture.CaptureSession
import com.indagium.capture.CaptureSettings
import com.indagium.capture.CaptureStatus
import com.indagium.capture.deviceStateGuidance
import kotlinx.coroutines.delay

private const val DEVICE_REFRESH_INTERVAL_MS = 3_000L

/** The device-capture launcher's content, embeddable in any surface that already knows which tab
 *  it belongs to. Split out of the old standalone `CaptureLauncher` composable so ui/HomeScreen.kt
 *  can host it as the right half of the home tab, side by side with the "Open a log" zone, instead
 *  of centered alone in its own tab — the launcher's tab (`launcherTabId`) IS the home tab (see
 *  [LogTab.isCaptureLauncher]'s KDoc), so this takes that id as a parameter rather than re-deriving
 *  it from `state.activeTab()`, which only worked when the launcher was the sole content of the
 *  active tab. One `verticalScroll` here (not a max-width-clamped centered column): the caller
 *  decides layout, this only decides content. */
@Composable
internal fun CaptureLauncherContent(state: AppState, launcherTabId: String, modifier: Modifier = Modifier) {
    val service = state.captureService
    val draft = state.captureLaunchSettings(launcherTabId)
    // Local, presentation-only selection of which discovered device "Start capture" acts on — not
    // persisted, not part of AppState, and re-resolved against the live device list every
    // recomposition so a 3s background refresh (below) never silently resets it.
    var selectedSerial by remember { mutableStateOf<String?>(null) }
    val selectedDevice = service.devices.firstOrNull { it.serial == selectedSerial } ?: service.devices.firstOrNull()
    LaunchedEffect(service) {
        service.refreshDevices(force = true)
        while (true) {
            delay(DEVICE_REFRESH_INTERVAL_MS)
            service.refreshDevices()
        }
    }
    Column(
        modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 24.dp, vertical = 40.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            AppText("Capture from a device", fontSize = 20.sp, color = tc().tx)
            AppText(
                "Choose an Android device. The capture opens as a normal streaming log tab.",
                color = tc().td,
                fontSize = 12.sp,
            )
        }
        service.toolStatus?.let { status ->
            LauncherPanel("Capture tools") {
                AppText(status, color = tc().ts, fontFamily = FontFamily.Monospace, fontSize = 11.sp)
                service.error?.let { AppText(it, color = DANGER_RED, fontSize = 11.sp) }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    AppButton("Recheck tools", { service.recheckToolsFromSettings() })
                    AppButton("Install guidance", { service.openInstallGuidanceFromSettings() }, ButtonVariant.Ghost)
                }
            }
        }
        LauncherPanel("Devices") {
            if (service.devices.isEmpty()) {
                AppText("No devices discovered", color = tc().td)
                AppButton("Refresh devices", { service.refreshDevices(force = true) }, ButtonVariant.Ghost)
            } else {
                service.devices.forEach { device ->
                    CaptureDeviceRow(
                        device = device,
                        selected = device.serial == selectedDevice?.serial,
                        onSelect = { selectedSerial = device.serial },
                    )
                }
            }
        }
        CaptureBeforeStartRow(state = state, launcherTabId = launcherTabId, draft = draft)
        AppButton(
            "Start capture",
            { selectedDevice?.let { state.startCaptureTab(it) } },
            ButtonVariant.Primary,
            enabled = selectedDevice?.available == true && state.liveCaptureTabId == null && !state.captureStartInProgress,
            modifier = Modifier.fillMaxWidth(),
        )
        LauncherPanel("Unfinished sessions") {
            UnfinishedSessionsSection(state = state, service = service)
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
            CaptureDeviceStatePill(device)
        }
        deviceStateGuidance(device.state)?.let { hint ->
            AppText(
                hint, color = DANGER_RED, fontSize = 10.sp, maxLines = 2, overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(top = 4.dp),
            )
        }
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
private fun CaptureBeforeStartRow(state: AppState, launcherTabId: String, draft: CaptureSettings) {
    val edit: CaptureSettingsEdit = { transform -> state.updateCaptureLaunchSettings(launcherTabId, transform) }
    LauncherPanel("Before start") { CaptureStartOptions(draft, edit) }
}

/** Fixes the inverted custom-buffer toggle (was: ticking an unticked buffer removed it, a no-op,
 * and unticking a ticked one re-added it — `kernel`/`radio`/`events` could never be selected and
 * `main`/`system`/`crash` could never be cleared). Add/remove are mutually exclusive by
 * construction, so this can never introduce a duplicate, and untouched entries keep their order. */
internal fun toggleCaptureBuffer(buffers: List<String>, name: String): List<String> =
    if (name in buffers) buffers - name else buffers + name

@Composable
private fun LauncherPanel(title: String, content: @Composable () -> Unit) {
    Column(
        Modifier.fillMaxWidth().border(BorderStroke(1.dp, tc().br), CORNER_MD).padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        AppText(title, color = tc().ts, fontSize = 11.sp)
        content()
    }
}

/** The "Unfinished sessions" panel body: item 3's multi-select + bulk delete on top of the
 * pre-existing per-row Open/Save ZIP/Open folder/Discard actions and single-row Discard confirm,
 * both left untouched below. Selection is local UI state, not [AppState] — it has no meaning
 * outside this one render of the panel — and is pruned to ids that still exist whenever the
 * retained-session list changes (a session can finish exporting, or be discarded from another
 * surface, out from under an open selection). */
@Composable
private fun UnfinishedSessionsSection(state: AppState, service: CaptureService) {
    var discardId by remember { mutableStateOf<String?>(null) }
    var selectedIds by remember { mutableStateOf<Set<String>>(emptySet()) }
    var confirmDeleteSelected by remember { mutableStateOf(false) }
    var deleteSelectedError by remember { mutableStateOf<String?>(null) }

    state.captureExportError?.let { AppText("Save failed: $it", color = DANGER_RED, fontSize = 10.sp) }
    val retained = service.sessions.filter { it.status != CaptureStatus.RECORDING }
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
