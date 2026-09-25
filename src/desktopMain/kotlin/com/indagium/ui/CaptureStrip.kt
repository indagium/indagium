@file:OptIn(
    androidx.compose.ui.ExperimentalComposeUiApi::class,
    androidx.compose.foundation.ExperimentalFoundationApi::class,
)

package com.indagium.ui

import androidx.compose.foundation.Image
import androidx.compose.foundation.TooltipArea
import androidx.compose.foundation.VerticalScrollbar
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.rememberScrollbarAdapter
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.DisableSelection
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.AddAPhoto
import androidx.compose.material.icons.outlined.DeleteOutline
import androidx.compose.material.icons.outlined.FolderOpen
import androidx.compose.material.icons.outlined.MoreHoriz
import androidx.compose.material.icons.outlined.Save
import androidx.compose.material.icons.outlined.Stop
import androidx.compose.material.icons.outlined.Videocam
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.onPointerEvent
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntRect
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.PopupPositionProvider
import androidx.compose.ui.window.PopupProperties
import com.indagium.capture.CaptureExportPreview
import com.indagium.capture.CaptureExportRequest
import com.indagium.capture.CaptureMarker
import com.indagium.capture.CaptureMirrorMode
import com.indagium.capture.CaptureRange
import com.indagium.capture.CaptureSession
import com.indagium.capture.RecorderSnapshot
import com.indagium.capture.RecorderState
import com.indagium.capture.effectiveMirrorMode
import com.indagium.capture.parseMarkerHeader
import com.indagium.capture.renderCaptureFilename
import com.indagium.model.AnnBlock
import com.indagium.model.LogTab
import kotlinx.coroutines.delay
import java.io.File
import java.util.Locale

internal const val CAPTURE_STRIP_HEIGHT_DP = 48

// Fits the snapshot dialog's three DialogActionButtons (132dp each, from Dialogs.kt) plus its own
// 20dp side padding and the 8dp gaps the centered action row uses between them.
private val CAPTURE_SNAPSHOT_POPOVER_WIDTH = 480.dp
private val CAPTURE_SNAPSHOT_POPOVER_MAX_HEIGHT = 680.dp
private const val CAPTURE_DIAGNOSTICS_ROW_LIMIT = 6
private const val CAPTURE_PREVIEW_REFRESH_INTERVAL_MS = 1_000L
private const val CAPTURE_BYTES_PER_KIB = 1024L
private const val CAPTURE_BYTES_PER_MIB = CAPTURE_BYTES_PER_KIB * 1024L
private const val CAPTURE_BYTES_PER_GIB = CAPTURE_BYTES_PER_MIB * 1024L
private const val CAPTURE_MILLIS_PER_SECOND = 1_000L
private const val CAPTURE_SECONDS_PER_MINUTE = 60L
private const val CAPTURE_SECONDS_PER_HOUR = 3_600L

/** Right-aligns the panel to its trigger, keeps a gap, and flips above only when needed. */
internal class CaptureSnapshotPopupPositionProvider(
    private val marginPx: Int,
    private val gapPx: Int,
    private val placeAbove: Boolean,
) : PopupPositionProvider {
    override fun calculatePosition(
        anchorBounds: IntRect,
        windowSize: IntSize,
        layoutDirection: LayoutDirection,
        popupContentSize: IntSize,
    ): IntOffset {
        val min = marginPx.coerceAtLeast(0)
        val maxX = (windowSize.width - popupContentSize.width - min).coerceAtLeast(min)
        val requestedX = if (layoutDirection == LayoutDirection.Ltr) {
            anchorBounds.right - popupContentSize.width
        } else {
            anchorBounds.left
        }
        val x = requestedX.coerceIn(min, maxX)

        val maxY = (windowSize.height - popupContentSize.height - min).coerceAtLeast(min)
        val requestedY = if (placeAbove) {
            anchorBounds.top - popupContentSize.height - gapPx
        } else {
            anchorBounds.bottom + gapPx
        }
        val y = requestedY.coerceIn(min, maxY)
        return IntOffset(x, y)
    }
}

/** Pure display formatting kept separate from the Compose surface for focused regression tests. */
internal fun formatCaptureElapsed(elapsedMs: Long): String {
    val bounded = elapsedMs.coerceAtLeast(0L)
    val totalSeconds = bounded / CAPTURE_MILLIS_PER_SECOND
    val hours = totalSeconds / CAPTURE_SECONDS_PER_HOUR
    val minutes = (totalSeconds % CAPTURE_SECONDS_PER_HOUR) / CAPTURE_SECONDS_PER_MINUTE
    val seconds = totalSeconds % CAPTURE_SECONDS_PER_MINUTE
    return if (hours > 0L) {
        String.format(Locale.US, "%d:%02d:%02d", hours, minutes, seconds)
    } else {
        String.format(Locale.US, "%02d:%02d", minutes, seconds)
    }
}

/** Compact byte formatting used by both the strip and status card. */
internal fun formatCaptureBytes(bytes: Long): String {
    val safe = bytes.coerceAtLeast(0L)
    return when {
        safe >= CAPTURE_BYTES_PER_GIB -> String.format(Locale.US, "%.1f GiB", safe.toDouble() / CAPTURE_BYTES_PER_GIB)
        safe >= CAPTURE_BYTES_PER_MIB -> String.format(Locale.US, "%.1f MiB", safe.toDouble() / CAPTURE_BYTES_PER_MIB)
        safe >= CAPTURE_BYTES_PER_KIB -> String.format(Locale.US, "%.1f KiB", safe.toDouble() / CAPTURE_BYTES_PER_KIB)
        else -> "$safe B"
    }
}

/** Summary line for a stopped capture. Marker count leads when there is one — after a session ends
 *  it is the thing you came back for, and it is what the capture-screen design shows here. */
internal fun captureStoppedSummary(tab: LogTab, logBytes: Long): String {
    val markers = tab.annotations.blocks.count { it is AnnBlock.Note && parseMarkerHeader(it.text) != null }
    val rows = "${tab.logData.size} rows · ${formatCaptureBytes(logBytes)}"
    return if (markers > 0) "$markers marker${if (markers == 1) "" else "s"} · $rows" else rows
}

internal fun captureLogCoverageLine(preview: CaptureExportPreview?): String = when {
    preview == null -> "Log …"
    preview.logStartMs == null -> "No complete log rows in this range"
    else -> "Log ${formatCaptureElapsed(preview.logStartMs)}–${formatCaptureElapsed(preview.logEndMs ?: preview.logStartMs)}"
}

internal fun captureVideoCoverageLine(preview: CaptureExportPreview?): String {
    val shortfall = preview?.videoShortfallMs
    return when {
        preview == null -> "Video …"
        shortfall == null || shortfall <= 0L -> "Video covers the whole range."
        preview.videoCoveredEndMs != null ->
            "Video coverage ends at ${formatCaptureElapsed(preview.videoCoveredEndMs)}; shortfall ${formatCaptureElapsed(shortfall)}."
        else -> "Video coverage unavailable; shortfall ${formatCaptureElapsed(shortfall)}."
    }
}

internal fun captureVideoStatus(snapshot: RecorderSnapshot): String = when {
    snapshot.videoRecording -> "Video REC"
    snapshot.session?.settings?.recordVideo == true -> "Video idle"
    else -> "Video off"
}

/** Status only, not a toggle: video recording is decided when the session starts
 * (CaptureRecorder.kt:328 reads settings.recordVideo; there is no mid-session start/stop). Tapping
 * it opens Capture settings — the same action the gear ToolbarBtn used to offer directly before it
 * moved into the ⋯ overflow menu. 24dp fully-rounded pill; the camera icon swaps for a solid accent
 * dot and the border/background/label go accent once video is actually recording. */
@Composable
private fun CaptureVideoPill(snapshot: RecorderSnapshot, onClick: () -> Unit) {
    val colors = tc()
    val recording = snapshot.videoRecording
    val borderColor = if (recording) colors.ac else colors.br
    val bgColor = if (recording) colors.ac.copy(alpha = .12f) else colors.p
    val labelColor = if (recording) colors.ac else colors.ts
    Row(
        Modifier
            .height(24.dp)
            .border(1.dp, borderColor, RoundedCornerShape(12.dp))
            .background(bgColor, RoundedCornerShape(12.dp))
            .clip(RoundedCornerShape(12.dp))
            .clickable(onClick = onClick)
            .padding(horizontal = 9.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        if (recording) {
            Box(Modifier.size(6.dp).background(colors.ac, RoundedCornerShape(50)))
        } else {
            Icon(Icons.Outlined.Videocam, contentDescription = null, tint = colors.ts, modifier = Modifier.size(13.dp))
        }
        DisableSelection {
            AppText(
                captureVideoStatus(snapshot),
                color = labelColor,
                fontSize = 11.sp,
                fontWeight = if (recording) FontWeight.SemiBold else FontWeight.Normal,
            )
        }
    }
}

/** SectionHeader's fixed row height — the part of the marker section that is always visible. */
private val SECTION_HEADER_HEIGHT = 32.dp

/** Initial height of the open marker list (about two rows); the divider above it changes it. */
private val MARKER_LIST_RESERVED_HEIGHT = 90.dp

private val MIN_MARKER_LIST_HEIGHT = 40.dp

/** The divider can't squeeze the picture below this. */
private val MIN_MIRROR_HEIGHT = 120.dp

/** VDivider's hit area height. */
private val DIVIDER_HEIGHT = 10.dp

private val CAPTURE_STORAGE_METER_TRACK_WIDTH = 84.dp
private val CAPTURE_STORAGE_METER_TRACK_HEIGHT = 5.dp

/** Fraction of the session storage limit already used, clamped to [0, 1]. A null/non-positive
 * limit (no session yet) reads as empty rather than crashing on the division. Pure and separate
 * from Compose so a focused test doesn't need a composition, matching formatCaptureElapsed/
 * formatCaptureBytes above (see CaptureStripTest). */
internal fun captureStorageMeterFraction(usedBytes: Long, limitBytes: Long?): Float {
    if (limitBytes == null || limitBytes <= 0L) return 0f
    return (usedBytes.toFloat() / limitBytes.toFloat()).coerceIn(0f, 1f)
}

internal const val CAPTURE_STORAGE_METER_WARN_THRESHOLD = 0.85f

internal fun captureStorageMeterIsWarn(usedBytes: Long, limitBytes: Long?): Boolean =
    captureStorageMeterFraction(usedBytes, limitBytes) >= CAPTURE_STORAGE_METER_WARN_THRESHOLD

/** Replaces the old "Storage x/y" monospace text (Problem 1 in the restyle plan: this fact was
 * printed twice, once here and once in the DEVICE card — Phase 2 removes the card's copy) with an
 * 84×5dp track plus the same formatCaptureBytes label. The track's fill colour is the only thing
 * that changes: colors.td normally, colors.warn once usage crosses
 * CAPTURE_STORAGE_METER_WARN_THRESHOLD of the session limit. */
@Composable
private fun CaptureStorageMeter(usedBytes: Long, limitBytes: Long?) {
    val colors = tc()
    val fraction = captureStorageMeterFraction(usedBytes, limitBytes)
    val warn = captureStorageMeterIsWarn(usedBytes, limitBytes)
    val label = limitBytes?.let { "${formatCaptureBytes(usedBytes)} / ${formatCaptureBytes(it)}" }
        ?: formatCaptureBytes(usedBytes)
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Box(
            Modifier.width(CAPTURE_STORAGE_METER_TRACK_WIDTH).height(CAPTURE_STORAGE_METER_TRACK_HEIGHT)
                .background(colors.br, RoundedCornerShape(50)),
        ) {
            Box(
                Modifier.fillMaxHeight().fillMaxWidth(fraction)
                    .background(if (warn) colors.warn else colors.td, RoundedCornerShape(50)),
            )
        }
        AppText(
            label, color = colors.td, fontSize = 11.sp, fontFamily = FontFamily.Monospace,
            maxLines = 1, overflow = TextOverflow.Ellipsis,
        )
    }
}

internal fun captureSinceSaveEnabled(session: CaptureSession?): Boolean =
    session?.hasSnapshotCheckpoint == true

internal fun captureSinceSaveHint(session: CaptureSession?, atElapsedMs: Long = session?.elapsedMs ?: 0L): String =
    if (session == null || !session.hasSnapshotCheckpoint) {
        "Since last save is unavailable until the first successful snapshot."
    } else {
        val age = session.snapshotCheckpointAgeMs(atElapsedMs) ?: 0L
        "Last save ${formatCaptureElapsed(age)} ago."
    }

/** No recorded video, or the file is missing/empty, for the range this snapshot would export. */
private fun captureVideoUnavailableForRange(session: CaptureSession): Boolean =
    session.videoStartElapsedMs == null || !session.videoFile.isFile || session.videoFile.length() <= 0L

/** Maps the current tab selection to source capture ordinals without assuming row ids start at 1. */
internal fun selectedCaptureOrdinals(tab: LogTab): IntRange? {
    if (tab.selected.isEmpty() || tab.logData.isEmpty()) return null
    val positions = tab.logData.mapIndexedNotNull { index, entry ->
        if (entry.id in tab.selected) index + 1 else null
    }
    if (positions.isEmpty()) return null
    return positions.minOrNull()!!..positions.maxOrNull()!!
}

/** Validates and normalizes the editable snapshot basename. */
internal fun captureArchiveName(input: String): String? {
    val stem = input.trim().removeSuffix(".zip")
    if (stem.isBlank() || stem == "." || stem == "..") return null
    if (stem.contains('/') || stem.contains('\\')) return null
    return "$stem.zip"
}

private fun recorderIsActive(snapshot: RecorderSnapshot): Boolean = when (snapshot.state) {
    RecorderState.RECORDING, RecorderState.STOPPING -> true
    RecorderState.IDLE, RecorderState.STOPPED, RecorderState.INTERRUPTED -> false
}

private fun screenshotButtonEnabled(
    snapshot: RecorderSnapshot,
    capability: CaptureScreenshotCapability,
): Boolean = snapshot.state == RecorderState.RECORDING &&
    capability.availability == CaptureScreenshotAvailability.ENABLED

/** Model/serial split for [CaptureDeviceChip], which renders the two independently instead of one
 * fixed-width string (that was the truncation bug fixed in the strip's restyle). */
private fun captureSessionDeviceParts(session: CaptureSession?, fallback: String): Pair<String, String> {
    val device = session?.device ?: return fallback to ""
    val model = device.model.ifBlank { device.serial }
    val serial = if (device.serial.isNotBlank() && device.serial != device.model) device.serial else ""
    return model to serial
}

private fun captureSnapshotState(state: AppState, tabId: String): RecorderSnapshot {
    // This helper is used only when the controller is not available during the tiny startup/stop
    // transition. Once a controller exists, the composable below collects its StateFlow directly.
    return state.captureControllerFor(tabId)?.snapshot?.value ?: RecorderSnapshot()
}

@Composable
private fun rememberCaptureSnapshot(state: AppState, tab: LogTab): RecorderSnapshot {
    val controller = state.captureControllerFor(tab.id)
    val snapshotState = if (controller != null) {
        controller.snapshot.collectAsState()
    } else {
        remember(tab.id) { mutableStateOf(captureSnapshotState(state, tab.id)) }
    }
    val snapshot by snapshotState
    return snapshot
}

/** 48dp live-capture chrome rendered only above an active streaming log tab. */
@Composable
internal fun CaptureStrip(
    state: AppState,
    tab: LogTab,
    onReturnFocus: () -> Unit,
) {
    if (tab.captureSessionId == null) {
        state.captureFinalizationStatus(tab.id)?.let { status ->
            CaptureFinalizationBanner(status)
        }
        // Export becoming unreachable the instant a capture stops was the worst of the capture
        // regressions: captureSessionId is cleared on Stop (recording really is over), but the
        // tab's rows and its recorder session on disk are still there. captureSourceSessionId
        // (session-only, set by attachFinalizedCapture / stopCaptureTab's failure paths) is what
        // lets this strip keep offering Save ZIP / Open folder instead of disappearing along with
        // the live controller — see the field's doc in Model.kt.
        if (tab.captureSourceSessionId != null) {
            CaptureStoppedStrip(state = state, tab = tab, onReturnFocus = onReturnFocus)
        }
        return
    }

    val snapshot = rememberCaptureSnapshot(state, tab)
    LaunchedEffect(tab.id) { state.ensureScreenshotCapability(tab.id) }
    var diagnosticsOpen by remember(tab.id) { mutableStateOf(false) }
    val session = snapshot.session
    val active = recorderIsActive(snapshot)
    val screenshotCapability = state.screenshotCapability(tab.id)
    val colors = tc()
    val (deviceModel, deviceSerial) = captureSessionDeviceParts(session, tab.filename.removePrefix("Capture — "))
    var snapshotOpen by remember(tab.id) { mutableStateOf(false) }
    var snapshotTriggerBounds by remember(tab.id) { mutableStateOf<Rect?>(null) }

    fun dismissSnapshotPopover() {
        if (state.captureExportBusy) state.cancelCaptureSnapshot()
        snapshotOpen = false
        state.clearCaptureExportStatus()
        onReturnFocus()
    }
    val finalizing = state.captureFinalizationStatus(tab.id) == CAPTURE_FINALIZING_STATUS
    var overflowOpen by remember(tab.id) { mutableStateOf(false) }
    var overflowHovered by remember(tab.id) { mutableStateOf(false) }
    val density = LocalDensity.current.density

    fun openCaptureSettings() {
        state.requestedSettingsSection = SettingsSection.Capture
        state.settingsOpen = true
        onReturnFocus()
    }

    Column(Modifier.fillMaxWidth().background(colors.p2)) {
        // Status cluster (device chip + rec/state + storage/video) sits on the left; the action
        // buttons are right-aligned and never shrink — narrow windows are handled by letting the
        // status text truncate instead of the strip scrolling sideways (see Problem 1 in the
        // restyle plan this strip follows).
        Row(
            Modifier.fillMaxWidth().height(CAPTURE_STRIP_HEIGHT_DP.dp).padding(horizontal = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            CaptureDeviceChip(model = deviceModel, serial = deviceSerial, live = active)
            when {
                finalizing -> AppText(
                    CAPTURE_FINALIZING_STATUS, color = colors.ts, fontSize = 11.sp,
                    fontFamily = FontFamily.Monospace, maxLines = 1, overflow = TextOverflow.Ellipsis,
                )
                active -> CaptureRecordingIndicator(elapsedMs = sessionElapsed(snapshot))
                else -> AppText(
                    snapshot.state.name, color = colors.td, fontSize = 11.sp,
                    fontFamily = FontFamily.Monospace, maxLines = 1, overflow = TextOverflow.Ellipsis,
                )
            }
            CaptureStorageMeter(usedBytes = snapshot.logBytes, limitBytes = session?.settings?.sessionLimitBytes)
            CaptureVideoPill(snapshot = snapshot, onClick = ::openCaptureSettings)
            Spacer(Modifier.weight(1f))
            // Right action cluster: its own row with tighter 8dp spacing, every button a uniform
            // 30dp tall — deliberately kept as a nested Row (not folded into the outer 12dp
            // rhythm) so it reads as one grouped control cluster, right-aligned by the Spacer above.
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                CaptureMarkIssueButton(
                    enabled = active,
                    onClick = {
                        state.markIssue(tab.id)
                        onReturnFocus()
                    },
                )
                ToolbarBtn(
                    label = "Screenshot",
                    icon = Icons.Outlined.AddAPhoto,
                    showLabel = false,
                    tooltip = "Capture a device screenshot",
                    enabled = screenshotButtonEnabled(snapshot, screenshotCapability),
                    modifier = Modifier.height(30.dp),
                    contentPadding = PaddingValues(horizontal = 7.dp, vertical = 4.dp),
                    onClick = {
                        state.screenshotCapture(tab.id)
                        onReturnFocus()
                    },
                )
                ToolbarBtn(
                    label = "Stop",
                    icon = Icons.Outlined.Stop,
                    tooltip = "Stop capture",
                    enabled = active,
                    modifier = Modifier.height(30.dp),
                    contentPadding = PaddingValues(horizontal = 7.dp, vertical = 4.dp),
                    onClick = {
                        state.stopCaptureTab(tab.id)
                        onReturnFocus()
                    },
                )
                Box(Modifier.onGloballyPositioned { snapshotTriggerBounds = it.boundsInWindow() }) {
                    ToolbarBtn(
                        // Trailing caret rather than a split button: ToolbarBtn has no slot for a
                        // separate trailing element, so the simplest faithful rendering of "opens a
                        // popover" is baking the indicator into the label itself.
                        label = "Save snapshot ▾",
                        icon = Icons.Outlined.Save,
                        active = true,
                        tooltip = "Export a capture snapshot",
                        enabled = active && (snapshotOpen || !state.captureExportBusy),
                        modifier = Modifier.height(30.dp),
                        contentPadding = PaddingValues(horizontal = 7.dp, vertical = 4.dp),
                        onClick = {
                            if (snapshotOpen) {
                                dismissSnapshotPopover()
                            } else {
                                snapshotOpen = true
                                state.clearCaptureExportStatus()
                            }
                        },
                    )
                    if (snapshotOpen) {
                        CaptureSnapshotPopover(
                            state = state,
                            tab = tab,
                            snapshot = snapshot,
                            triggerBounds = snapshotTriggerBounds,
                            onDismiss = ::dismissSnapshotPopover,
                            onReturnFocus = onReturnFocus,
                        )
                    }
                }
                Box {
                    TooltipArea(tooltip = { ToolbarTooltip("More capture actions") }) {
                        Box(
                            Modifier.size(30.dp)
                                .background(if (overflowHovered) colors.hv else Color.Transparent, CORNER_MD)
                                .clip(CORNER_MD)
                                .clickable {
                                    overflowOpen = !overflowOpen
                                    onReturnFocus()
                                }
                                .onPointerEvent(PointerEventType.Enter) { overflowHovered = true }
                                .onPointerEvent(PointerEventType.Exit) { overflowHovered = false },
                            contentAlignment = Alignment.Center,
                        ) {
                            Icon(
                                Icons.Outlined.MoreHoriz,
                                contentDescription = "More capture actions",
                                tint = colors.ts,
                                modifier = Modifier.size(16.dp),
                            )
                        }
                    }
                    if (overflowOpen) {
                        // The gear ToolbarBtn and diagnostics PillBtn used to live in the strip itself;
                        // both are low-frequency actions, so they move here to make room for the
                        // storage meter/video pill/Mark issue button without the strip scrolling or
                        // shrinking (see the table in the restyle plan's Phase 1).
                        Popup(
                            alignment = Alignment.TopEnd,
                            offset = IntOffset(0, (CAPTURE_STRIP_HEIGHT_DP * density).toInt()),
                            onDismissRequest = {
                                overflowOpen = false
                                onReturnFocus()
                            },
                            properties = PopupProperties(focusable = true),
                        ) {
                            Column(
                                Modifier.width(220.dp)
                                    .background(colors.p, RoundedCornerShape(7.dp))
                                    .border(1.dp, colors.br, RoundedCornerShape(7.dp))
                                    .padding(vertical = 4.dp),
                            ) {
                                Seq3DropdownMenuItem("Capture settings…") {
                                    overflowOpen = false
                                    openCaptureSettings()
                                }
                                Seq3DropdownMenuItem("Open capture folder", enabled = session != null) {
                                    session?.let { state.openRetainedCaptureFolder(it.id) }
                                    overflowOpen = false
                                    onReturnFocus()
                                }
                                Seq3DropdownMenuItem(
                                    "Diagnostics (${snapshot.diagnostics.size})",
                                    active = diagnosticsOpen,
                                ) {
                                    diagnosticsOpen = !diagnosticsOpen
                                    overflowOpen = false
                                    onReturnFocus()
                                }
                            }
                        }
                    }
                }
            }
        }
        // One truncating status row replaces the previous ad hoc stack: the external-mirror error
        // (if any) takes priority since it signals a real failure, otherwise the last screenshot
        // status — the strip's only feedback for the Screenshot/Mark issue buttons now that
        // diagnostics live behind the ⋯ menu instead of always being in view.
        val mirrorError = state.captureService.error
            ?.takeIf { it.startsWith("External scrcpy mirror could not open:") }
        val statusLine = mirrorError ?: state.captureScreenshotStatus
        if (statusLine != null) {
            AppText(
                statusLine,
                color = if (mirrorError != null) DANGER_RED else colors.ts,
                fontSize = 11.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.fillMaxWidth().padding(horizontal = 10.dp, vertical = 3.dp),
            )
        }
        if (diagnosticsOpen) {
            CaptureDiagnosticsDrawer(
                state = state,
                tab = tab,
                snapshot = snapshot,
                onDismiss = {
                    diagnosticsOpen = false
                    onReturnFocus()
                },
            )
        }
    }
}

/** Pill-shaped device identity chip (Problem 2 of the restyle plan): a state dot, the model
 * (truncated with an ellipsis if it must, via [widthIn]) and the full serial in monospace, which
 * never truncates — the serial is the identifying part a fixed-width column used to cut off. */
@Composable
private fun CaptureDeviceChip(model: String, serial: String, live: Boolean, modifier: Modifier = Modifier) {
    val colors = tc()
    Row(
        modifier
            .background(colors.p, RoundedCornerShape(50))
            .border(1.dp, colors.br, RoundedCornerShape(50))
            .padding(horizontal = 10.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Box(Modifier.size(7.dp).background(if (live) colors.ok else colors.td, RoundedCornerShape(50)))
        AppText(
            model,
            color = colors.tx,
            fontSize = 12.sp,
            fontWeight = FontWeight.SemiBold,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.widthIn(max = 130.dp),
        )
        if (serial.isNotBlank()) {
            AppText(serial, color = colors.td, fontSize = 10.sp, fontFamily = FontFamily.Monospace, maxLines = 1)
        }
    }
}

/** Recording indicator shown next to (not inside) the device chip while a capture is active. */
@Composable
private fun CaptureRecordingIndicator(elapsedMs: Long) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        Box(Modifier.size(8.dp).background(DANGER_RED, RoundedCornerShape(50)))
        AppText(
            formatCaptureElapsed(elapsedMs),
            color = DANGER_RED,
            fontFamily = FontFamily.Monospace,
            fontWeight = FontWeight.SemiBold,
            fontSize = 13.sp,
        )
    }
}

private val CAPTURE_MARK_ISSUE_CORNER = RoundedCornerShape(8.dp)

/** Not a plain [ToolbarBtn] call: that widget's icon slot is a fixed-size
 * [androidx.compose.material.icons.Icons] vector tinted from the row's normal palette, and this
 * control needs to read as a filled danger action with a 16dp radial-gradient "dome" rather than a
 * flat Material icon — so it copies ToolbarBtn's chrome (30dp tall, matching its Screenshot/Stop/
 * Save snapshot siblings) instead of reusing it directly. Enabled by `recorderIsActive(snapshot)`,
 * matching the Stop button's own gate exactly since a marker only makes sense while a capture is
 * running. */
@Composable
private fun CaptureMarkIssueButton(enabled: Boolean, onClick: () -> Unit) {
    val colors = tc()
    var hovered by remember { mutableStateOf(false) }
    val mutedColor = colors.td.copy(alpha = .5f)
    val domeHighlight = remember { lerp(DANGER_RED, Color.White, 0.5f) }
    TooltipArea(tooltip = { ToolbarTooltip("Mark this moment as an issue") }) {
        Box(
            Modifier
                .height(30.dp)
                .border(1.dp, if (enabled) DANGER_RED.copy(alpha = .6f) else colors.br, CAPTURE_MARK_ISSUE_CORNER)
                .background(
                    when {
                        enabled && hovered -> DANGER_RED.copy(alpha = .16f)
                        enabled -> DANGER_RED.copy(alpha = .10f)
                        else -> Color.Transparent
                    },
                    CAPTURE_MARK_ISSUE_CORNER,
                )
                .clip(CAPTURE_MARK_ISSUE_CORNER)
                .then(if (enabled) Modifier.clickable(onClick = onClick) else Modifier)
                .onPointerEvent(PointerEventType.Enter) { hovered = true }
                .onPointerEvent(PointerEventType.Exit) { hovered = false }
                .padding(start = 10.dp, end = 12.dp),
            contentAlignment = Alignment.Center,
        ) {
            DisableSelection {
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
                    Box(
                        Modifier.size(16.dp).drawBehind {
                            if (enabled) {
                                drawCircle(
                                    brush = Brush.radialGradient(
                                        colors = listOf(domeHighlight, DANGER_RED),
                                        center = Offset(size.width * 0.34f, size.height * 0.30f),
                                        radius = size.maxDimension * 0.75f,
                                    ),
                                )
                            } else {
                                drawCircle(color = mutedColor)
                            }
                        },
                    )
                    AppText(
                        "Mark issue",
                        color = if (enabled) DANGER_RED else mutedColor,
                        fontSize = 12.5.sp,
                        fontWeight = FontWeight.SemiBold,
                    )
                }
            }
        }
    }
}

/**
 * Chrome for a capture tab whose recording has stopped: no live controller/recorder exists any
 * more, so this reads the retained [CaptureSession] straight off disk (via [CaptureService],
 * already kept fresh by [AppState.stopCaptureTab]'s `updateSessions()` call) instead of collecting
 * a [RecorderSnapshot]. Save ZIP and Open folder deliberately reuse the exact same
 * [AppState.saveRetainedCapture] / [AppState.openRetainedCaptureFolder] calls the "Retained
 * sessions" list in [CaptureLauncherContent] already uses for a session with no open tab — the
 * underlying export has never needed a live recorder (CaptureArchiveExporter.export takes a
 * CaptureSession, not a controller), only the previous wiring did.
 */
@Composable
private fun CaptureStoppedStrip(
    state: AppState,
    tab: LogTab,
    onReturnFocus: () -> Unit,
) {
    val sessionId = tab.captureSourceSessionId ?: return
    val session = state.captureService.sessions.firstOrNull { it.id == sessionId }
    val colors = tc()
    val (deviceModel, deviceSerial) = captureSessionDeviceParts(session, tab.filename.removePrefix("Capture — "))
    Column(Modifier.fillMaxWidth().background(colors.p2)) {
        Row(
            Modifier.fillMaxWidth().height(CAPTURE_STRIP_HEIGHT_DP.dp).padding(horizontal = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            CaptureDeviceChip(model = deviceModel, serial = deviceSerial, live = false)
            AppText(
                "Stopped · ${formatCaptureElapsed(session?.elapsedMs ?: 0L)}",
                color = colors.td, fontSize = 11.sp, fontFamily = FontFamily.Monospace,
                maxLines = 1, overflow = TextOverflow.Ellipsis,
            )
            // No weight on this text. It used to carry weight(1f, fill = false) *and* be followed
            // by a Spacer(weight(1f)): two weighted children split the row's spare space in half,
            // which left a gap in the middle of the strip and pushed the actions hard against the
            // right edge, detached from everything else. The Spacer alone is what right-aligns the
            // cluster, exactly as in the live strip.
            AppText(
                captureStoppedSummary(tab, session?.logFile?.length() ?: 0L),
                color = colors.td, fontSize = 11.sp, fontFamily = FontFamily.Monospace,
                maxLines = 1, overflow = TextOverflow.Ellipsis,
            )
            Spacer(Modifier.weight(1f))
            ToolbarBtn(
                label = "Save ZIP",
                icon = Icons.Outlined.Save,
                active = true,
                tooltip = "Export this capture as a ZIP",
                enabled = session != null && !state.captureExportBusy,
                modifier = Modifier.height(30.dp),
                contentPadding = PaddingValues(horizontal = 7.dp, vertical = 4.dp),
                onClick = {
                    state.saveRetainedCapture(sessionId, tab.id)
                    onReturnFocus()
                },
            )
            ToolbarBtn(
                label = "Open folder",
                icon = Icons.Outlined.FolderOpen,
                tooltip = "Open the capture's session folder",
                enabled = session != null,
                modifier = Modifier.height(30.dp),
                contentPadding = PaddingValues(horizontal = 7.dp, vertical = 4.dp),
                onClick = {
                    state.openRetainedCaptureFolder(sessionId)
                    onReturnFocus()
                },
            )
        }
        if (session == null || state.captureExportError != null || state.captureExportResult != null) {
            Row(
                Modifier.fillMaxWidth().padding(horizontal = 10.dp, vertical = 4.dp),
                horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                if (session == null) {
                    AppText("Session no longer on disk", color = DANGER_RED, fontSize = 10.sp)
                }
                state.captureExportError?.let {
                    AppText("Save failed: $it", color = DANGER_RED, fontSize = 10.sp, maxLines = 2, overflow = TextOverflow.Ellipsis)
                }
                state.captureExportResult?.let { AppText("Saved ${it.file.name}", color = colors.ac, fontSize = 10.sp) }
            }
        }
    }
}

@Composable
private fun CaptureFinalizationBanner(status: String) {
    val colors = tc()
    Row(
        Modifier.fillMaxWidth().background(colors.p2).padding(horizontal = 10.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        AppText(
            status,
            color = if (status == CAPTURE_FINALIZING_STATUS) colors.ts else DANGER_RED,
            fontSize = 10.sp,
            fontFamily = FontFamily.Monospace,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

private enum class SnapshotRangeChoice(val label: String) {
    ALL("All"),
    LAST_MINUTES("Last N minutes"),
    SINCE_SAVE("Since last save"),
    SELECTION("Current selection"),
}

@Composable
private fun CaptureSnapshotPopover(
    state: AppState,
    tab: LogTab,
    snapshot: RecorderSnapshot,
    triggerBounds: Rect?,
    onDismiss: () -> Unit,
    onReturnFocus: () -> Unit,
) {
    val colors = tc()
    val density = LocalDensity.current
    val hostWindowSize = LocalWindowInfo.current.containerSize
    val hostWidth = with(density) { hostWindowSize.width.toDp() }
    val popupWidth = CAPTURE_SNAPSHOT_POPOVER_WIDTH.coerceAtMost((hostWidth - 16.dp).coerceAtLeast(1.dp))
    val marginPx = with(density) { 8.dp.roundToPx() }
    val gapPx = with(density) { 8.dp.roundToPx() }
    val belowSpacePx = triggerBounds?.let {
        (hostWindowSize.height - it.bottom.toInt() - marginPx - gapPx - 2).coerceAtLeast(1)
    } ?: (hostWindowSize.height - 2 * marginPx).coerceAtLeast(1)
    val aboveSpacePx = triggerBounds?.let {
        (it.top.toInt() - marginPx - gapPx - 2).coerceAtLeast(1)
    } ?: 0
    val placeAbove = aboveSpacePx > belowSpacePx
    val availableHeightPx = if (placeAbove) aboveSpacePx else belowSpacePx
    val popupHeight = with(density) { availableHeightPx.toDp() }
        .coerceAtMost(CAPTURE_SNAPSHOT_POPOVER_MAX_HEIGHT)
    val popupPositionProvider = remember(density, placeAbove) {
        with(density) {
            CaptureSnapshotPopupPositionProvider(marginPx = marginPx, gapPx = gapPx, placeAbove = placeAbove)
        }
    }
    val bodyScroll = rememberScrollState()
    // This popover already resolves to the package-local `Popup` in MirrorOccludingLayers.kt,
    // which registers mirror occlusion for its own composition scope (see
    // RegisterMirrorOcclusionForCurrentWindow) — an explicit occlusion source here would only
    // double-register the same mask, so none is registered in this function.
    val session = snapshot.session
    val recordVideoConfigured = session?.settings?.recordVideo
    val canIncludeVideo = recordVideoConfigured == true
    val selection = remember(tab.id, tab.selected, tab.logData) { selectedCaptureOrdinals(tab) }
    var rangeChoice by remember(tab.id) { mutableStateOf(SnapshotRangeChoice.ALL) }
    var customMinutesText by remember(tab.id) { mutableStateOf("5") }
    var includeVideo by remember(tab.id, recordVideoConfigured) { mutableStateOf(canIncludeVideo) }
    var overwriteConfirmed by remember(tab.id) { mutableStateOf(false) }
    var saveAndOpen by remember(tab.id) { mutableStateOf(false) }
    val customMinutes = customMinutesText.toIntOrNull()?.coerceAtLeast(1) ?: 0
    val range = when (rangeChoice) {
        SnapshotRangeChoice.ALL -> CaptureRange.ALL
        SnapshotRangeChoice.LAST_MINUTES -> when (customMinutes) {
            5 -> CaptureRange.LAST_FIVE
            10 -> CaptureRange.LAST_TEN
            else -> CaptureRange.CUSTOM
        }
        SnapshotRangeChoice.SINCE_SAVE -> CaptureRange.SINCE_SAVE
        SnapshotRangeChoice.SELECTION -> CaptureRange.SELECTION
    }
    val defaultFilename = session?.let {
        renderCaptureFilename(
            template = it.settings.filenameTemplate,
            device = it.device,
            startEpochMs = it.startedEpochMs,
            range = range,
            counter = it.exportCounter,
            label = it.settings.label,
        )
    } ?: "capture.zip"
    var basename by remember(tab.id, defaultFilename) {
        mutableStateOf(defaultFilename.removeSuffix(".zip"))
    }
    val destinationDirectory = state.settings.defaultSaveDir?.let(::File) ?: File(".")
    val filename = captureArchiveName(basename)
    val destination = filename?.let { File(destinationDirectory, it) } ?: File(destinationDirectory, "capture.zip")
    val sinceSaveEnabled = captureSinceSaveEnabled(session)
    val enabled = session != null && !state.captureExportBusy &&
        (rangeChoice != SnapshotRangeChoice.LAST_MINUTES || customMinutes > 0) &&
        (rangeChoice != SnapshotRangeChoice.SINCE_SAVE || sinceSaveEnabled) &&
        (rangeChoice != SnapshotRangeChoice.SELECTION || selection != null) && filename != null
    val request = session?.let {
        CaptureExportRequest(
            destination = destination,
            range = range,
            customMinutes = customMinutes,
            includeVideo = includeVideo,
            cutoffElapsedMs = it.elapsedMs,
            selectedFirstRowOrdinal = selection?.first,
            selectedLastRowOrdinal = selection?.last,
            overwriteExisting = overwriteConfirmed,
        )
    }
    LaunchedEffect(rangeChoice, customMinutes, includeVideo, selection, basename, enabled) {
        if (!enabled || request == null) return@LaunchedEffect
        // Recorder state is published every ~250ms. Previewing on that raw elapsed value starts
        // an unbounded stream of index scans/remuxes while the popover is open. Controls still
        // refresh immediately (effect keys above), while this bounded tick keeps growing-video
        // coverage reasonably current without coupling preview work to recorder publication.
        while (true) {
            state.previewCaptureSnapshot(tab.id, request)
            delay(CAPTURE_PREVIEW_REFRESH_INTERVAL_MS)
        }
    }

    // Keep a fixed viewport and pin the action row so live preview/error changes cannot move the
    // popup or push its buttons below the screen. The provider places it below and right-aligned
    // with the trigger, flipping above only when the window has no room below.
    Popup(
        popupPositionProvider = popupPositionProvider,
        onDismissRequest = onDismiss,
        properties = PopupProperties(focusable = true),
    ) {
        Column(
            // heightIn(max=), not height(): a fixed height made the panel as tall as the whole
            // window below the trigger (capped at 680dp) no matter how little it had to show, so
            // a three-radio range picker rendered as a mostly-empty column. The body's own
            // verticalScroll still handles the case where the content genuinely exceeds the space.
            Modifier.width(popupWidth).heightIn(max = popupHeight)
                .background(colors.p, RoundedCornerShape(8.dp))
                .border(1.dp, colors.br, RoundedCornerShape(8.dp))
                .padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                AppText("Capture snapshot", color = colors.tx, fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
                Spacer(Modifier.weight(1f))
                SquareIconButton("×", fontSize = 14.sp, enabled = !state.captureExportBusy, onClick = {
                    onDismiss()
                    onReturnFocus()
                })
            }
            Column(
                // fill = false so the body takes only the height it needs; a plain weight(1f)
                // would stretch to the parent's max constraint and re-introduce the empty panel
                // that heightIn(max=) above exists to avoid.
                Modifier.weight(1f, fill = false).verticalScroll(bodyScroll),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    AppText("Range", color = colors.td, fontSize = 10.sp, fontWeight = FontWeight.SemiBold)
                    // Range is mutually exclusive (exactly one of All/Last N minutes/Since last
                    // save/Current selection applies to the export), so this is a radio group — see
                    // RadioRow in Components.kt — not a checkbox list. A checkbox implies "choose any",
                    // which was the semantic bug here even though it happened to render one selection
                    // at a time.
                    SnapshotRangeChoice.entries.forEach { choice ->
                        val choiceEnabled = when (choice) {
                            SnapshotRangeChoice.SELECTION -> selection != null
                            SnapshotRangeChoice.SINCE_SAVE -> sinceSaveEnabled
                            else -> true
                        }
                        RadioRow(
                            selected = rangeChoice == choice,
                            onSelect = {
                                rangeChoice = choice
                                onReturnFocus()
                            },
                            enabled = choiceEnabled,
                        ) {
                            AppText(choice.label, color = if (choiceEnabled) colors.ts else colors.td, fontSize = 11.sp)
                        }
                    }
                    AppText(
                        captureSinceSaveHint(session),
                        color = if (sinceSaveEnabled) colors.td else colors.ts,
                        fontSize = 10.sp,
                    )
                }
                if (rangeChoice == SnapshotRangeChoice.LAST_MINUTES) {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        AppText("Minutes", color = colors.td, fontSize = 10.sp, fontWeight = FontWeight.SemiBold)
                        InlineField(
                            value = customMinutesText,
                            onValue = { value -> customMinutesText = value.filter(Char::isDigit).take(4) },
                            modifier = Modifier.width(64.dp),
                        )
                        if (customMinutes == 0) AppText("Enter a positive number", color = DANGER_RED, fontSize = 10.sp)
                    }
                }
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    AppText("Archive name", color = colors.td, fontSize = 10.sp, fontWeight = FontWeight.SemiBold)
                    InlineField(
                        value = basename,
                        onValue = { basename = it.take(180) },
                        modifier = Modifier.fillMaxWidth(),
                    )
                    if (filename == null) AppText("Enter a valid archive basename", color = DANGER_RED, fontSize = 10.sp)
                }
                CheckRow(
                    includeVideo,
                    {
                        if (canIncludeVideo) includeVideo = !includeVideo
                        onReturnFocus()
                    },
                    enabled = canIncludeVideo,
                ) {
                    AppText("Include video", color = if (canIncludeVideo) colors.ts else colors.td, fontSize = 11.sp)
                }
                if (recordVideoConfigured == false) {
                    AppText(
                        "Video recording was disabled for this capture. This archive will contain logs only.",
                        color = colors.td,
                        fontSize = 10.sp,
                    )
                }
                if (includeVideo && canIncludeVideo && captureVideoUnavailableForRange(session)) {
                    AppText(
                        "Video is unavailable for the current range; the archive will still save logs and advance Since last save.",
                        color = colors.ts,
                        fontSize = 10.sp,
                    )
                }
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    AppText("Destination", color = colors.td, fontSize = 10.sp, fontWeight = FontWeight.SemiBold)
                    AppText(
                        destination.absolutePath,
                        color = colors.ts,
                        fontSize = 10.sp,
                        fontFamily = FontFamily.Monospace,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                    AppButton(
                        "Choose folder…",
                        { state.pickSaveFolder(); onReturnFocus() },
                        ButtonVariant.Secondary,
                        enabled = !state.captureExportBusy,
                    )
                }
                if (rangeChoice == SnapshotRangeChoice.SELECTION && selection != null) {
                    AppText("Rows ${selection.first}–${selection.last}; separators between those rows are included.", color = colors.td, fontSize = 10.sp)
                }
                // Coverage lines are always laid out (placeholder until the async preview lands), so the
                // content-sized popover doesn't grow right after opening; the video line exists exactly
                // when video is included, whatever its outcome.
                val preview = state.captureExportPreview
                AppText(
                    captureLogCoverageLine(preview),
                    color = if (preview == null) colors.td else colors.ts,
                    fontSize = 10.sp,
                    fontFamily = FontFamily.Monospace,
                )
                if (includeVideo) {
                    AppText(
                        captureVideoCoverageLine(preview),
                        color = if (preview == null) colors.td else colors.ts,
                        fontSize = 10.sp,
                        maxLines = 2,
                    )
                }
                if (destination.exists() && !overwriteConfirmed) {
                    AppText(
                        "An archive with this name already exists. Confirm overwrite to continue.",
                        color = DANGER_RED,
                        fontSize = 10.sp,
                    )
                    AppButton("Confirm overwrite", { overwriteConfirmed = true; onReturnFocus() }, ButtonVariant.Secondary)
                }
                if (state.captureExportBusy) {
                    state.captureExportBusyMessage?.let {
                        AppText(it, color = colors.td, fontSize = 10.sp)
                    }
                }
                state.captureExportError?.let { error ->
                    AppText("Snapshot failed: $error", color = DANGER_RED, fontSize = 10.sp, maxLines = 3)
                }
                state.captureExportResult?.let { result ->
                    AppText("Saved ${result.file.name}: ${result.message}", color = colors.ac, fontSize = 10.sp, maxLines = 2)
                    if (includeVideo && result.videoCoveredEndMs != null && result.videoCoveredEndMs < result.logCoveredEndMs) {
                        AppText(
                            "Video coverage ends at ${formatCaptureElapsed(result.videoCoveredEndMs)}; " +
                                "log coverage ends at ${formatCaptureElapsed(result.logCoveredEndMs)}.",
                            color = colors.ts,
                            fontSize = 10.sp,
                            maxLines = 2,
                        )
                    }
                }
            }
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterHorizontally),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                if (state.captureExportBusy) {
                    DialogActionButton("Cancel export", active = true, danger = true) {
                        state.cancelCaptureSnapshot()
                        onDismiss()
                    }
                } else {
                    DialogActionButton("Cancel", active = false) {
                        onDismiss()
                    }
                    // "Save ZIP" is the primary action (active = true, the accent-filled style);
                    // "Save + open" is the secondary variant of the same action, matching
                    // DialogActionButton's active/inactive convention elsewhere (e.g.
                    // SplitPromptDialog's "Split"/"Do Not Split").
                    DialogActionButton("Save ZIP", active = true, enabled = enabled) {
                        saveAndOpen = false
                        request?.let { state.exportCaptureSnapshot(tab.id, it) }
                        onReturnFocus()
                    }
                    DialogActionButton("Save + open", active = false, enabled = enabled) {
                        saveAndOpen = true
                        request?.let { state.exportCaptureSnapshot(tab.id, it) }
                        onReturnFocus()
                    }
                }
            }
        }
    }
    LaunchedEffect(state.captureExportResult) {
        if (saveAndOpen) {
            state.captureExportResult?.file?.let { file ->
                // The export is always our own capture ZIP (a portable archive with a
                // capture.indagium.json descriptor), never a plain text log — routing it through
                // plain openFile() parsed the ZIP's raw bytes as a text log and produced a tab full
                // of "RAW: PK ..." mojibake, with no video attached. openCaptureFile is the designed
                // entry point: it validates the descriptor, parses the log inside the zip, and
                // attaches the video. isCaptureArchive still gates it so a hand-edited/corrupted
                // export that genuinely isn't a capture archive falls back to plain openFile instead
                // of throwing deep inside openCaptureFile's coroutine.
                if (com.indagium.capture.CaptureArchiveReader.isCaptureArchive(file)) {
                    state.openCaptureFile(file)
                } else {
                    state.openFile(file)
                }
                saveAndOpen = false
                onDismiss()
            }
        }
    }
}

private fun sessionElapsed(snapshot: RecorderSnapshot): Long =
    snapshot.session?.elapsedMs?.coerceAtLeast(0L) ?: 0L

@Composable
private fun CaptureDiagnosticsDrawer(
    state: AppState,
    tab: LogTab,
    snapshot: RecorderSnapshot,
    onDismiss: () -> Unit,
) {
    val colors = tc()
    Column(
        Modifier.fillMaxWidth().border(1.dp, colors.br).background(colors.p).padding(10.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            AppText("Capture diagnostics", color = colors.tx, fontSize = 11.sp, fontWeight = FontWeight.SemiBold)
            Spacer(Modifier.weight(1f))
            AppButton("Close", onDismiss, ButtonVariant.Ghost)
        }
        AppText("Recorder: ${snapshot.state.name} · rows: ${snapshot.indexedRows} · log: ${formatCaptureBytes(snapshot.logBytes)}",
            color = colors.td, fontSize = 10.sp, fontFamily = FontFamily.Monospace)
        state.captureToolStatus?.let {
            AppText(it, color = colors.td, fontSize = 10.sp, fontFamily = FontFamily.Monospace, maxLines = 4)
        }
        state.captureScreenshotStatus?.let { AppText(it, color = colors.ts, fontSize = 10.sp) }
        state.screenshotCapability(tab.id).reason?.let {
            AppText("Screenshot unavailable: $it", color = DANGER_RED, fontSize = 10.sp, maxLines = 2)
        }
        val diagnostics = snapshot.diagnostics.takeLast(CAPTURE_DIAGNOSTICS_ROW_LIMIT)
        if (diagnostics.isEmpty()) {
            AppText("No recorder diagnostics", color = colors.td, fontSize = 10.sp)
        } else {
            diagnostics.forEach { line ->
                AppText(line, color = colors.ts, fontSize = 10.sp, maxLines = 2, overflow = TextOverflow.Ellipsis)
            }
        }
    }
}

/** Right-sidebar card for a live capture. The embedded mirror is deliberately kept separate from
 * recorder state: a mirror failure only updates its own status and never stops log recording.
 *
 * Layout, top to bottom: when embedded and attached there is no header at all — the picture
 * starts at the top and the status dot, Connect/Disconnect and detach live in its attached control
 * bar, because every dp of a header came out of the picture. The other branches (external, off,
 * detached) have no picture to protect and keep a full-bleed [DeviceHeaderRow]. Then the mirror
 * surface + its attached control bar (still `weight(1f)`, see the `flexible` doc in
 * EmbeddedMirrorPanel.kt for why that must not change), whose bar also opens the text-to-device
 * row; then a bounded/scrollable marker list. */
@Composable
internal fun CaptureCard(state: AppState, tab: LogTab) {
    if (tab.captureSessionId == null) return
    val snapshot = rememberCaptureSnapshot(state, tab)
    val colors = tc()
    val session = snapshot.session
    val mirrorMode = session?.settings?.effectiveMirrorMode ?: CaptureMirrorMode.DISABLED
    LaunchedEffect(tab.id, session?.id, mirrorMode) {
        if (mirrorMode == CaptureMirrorMode.EMBEDDED) {
            state.ensureEmbeddedMirror(tab.id, autoStart = true)
        }
    }
    val mirror = state.embeddedMirrorFor(tab.id)
    val clipboardState = remember(tab.id) { MirrorClipboardState() }
    // Collapsed by default: the list is something you open when you want it, the picture is not.
    var markersExpanded by remember(tab.id) { mutableStateOf(false) }
    val toggleMarkers = { markersExpanded = !markersExpanded }
    // Height of the open marker list, set by dragging the divider above it; the picture gets the rest.
    var markerListHeight by remember(tab.id) { mutableStateOf(MARKER_LIST_RESERVED_HEIGHT) }
    var cardHeight by remember(tab.id) { mutableStateOf(0.dp) }
    // What the list actually has right now. A width-limited picture leaves spare height, so the
    // list can already be taller than markerListHeight; dragging starts from this, not from the
    // stored value, or the first part of every drag would do nothing.
    var markerListSpace by remember(tab.id) { mutableStateOf(0.dp) }
    val density = LocalDensity.current
    val detached = state.isEmbeddedMirrorDetached(tab.id)
    // This card fills RightSidebarPanel's video slot, which is a *weighted* share of the sidebar
    // (videoSplit, default 0.42) — a fixed height, not a free-growing column. It therefore must NOT
    // scroll: inside a verticalScroll the incoming max height is infinite, so the mirror kept its
    // full 420dp however small the slot was, overflowed, and got clipped at the slot edge — which
    // reads as "Notes is drawn on top of the video". Laying it out as a plain Column instead lets
    // the surface take exactly the space left over, with no guess about how much the rows below it
    // need. Resizing is the sidebar's own video/notes divider, one level up.
    // The live mirror goes through MirrorAboveMarkersLayout so opening the bar's text row pushes
    // the markers down instead of shrinking the picture.
    if (mirrorMode == CaptureMirrorMode.EMBEDDED && !detached) {
        MirrorAboveMarkersLayout(
            extraMirrorHeight = if (clipboardState.expanded) MIRROR_TEXT_ROW_HEIGHT else 0.dp,
            belowReserve = SECTION_HEADER_HEIGHT + if (markersExpanded) DIVIDER_HEIGHT + markerListHeight else 0.dp,
            mirror = {
                EmbeddedMirrorPanel(
                    handle = mirror,
                    setupError = state.embeddedMirrorSetupError(tab.id),
                    onConnect = { state.openCaptureMirror(tab.id) },
                    onDisconnect = { state.stopEmbeddedMirror(tab.id) },
                    onDetach = { state.detachEmbeddedMirror(tab.id) },
                    fillAvailableHeight = true,
                    clipboardState = clipboardState,
                    modifier = Modifier.fillMaxWidth(),
                )
            },
            below = {
                Column {
                    if (markersExpanded) {
                        VDivider { dy ->
                            // Up (negative dy) grows the list. Keep MIN_MIRROR_HEIGHT for the picture.
                            val maxList = (cardHeight - SECTION_HEADER_HEIGHT - DIVIDER_HEIGHT - MIN_MIRROR_HEIGHT)
                                .coerceAtLeast(MIN_MARKER_LIST_HEIGHT)
                            val current = maxOf(markerListHeight, markerListSpace)
                            markerListHeight = (current - dy.dp).coerceIn(MIN_MARKER_LIST_HEIGHT, maxList)
                        }
                    }
                    CaptureMarkerSection(state, tab, markersExpanded, toggleMarkers)
                }
            },
            onBelowSpace = { px -> markerListSpace = with(density) { px.toDp() } - SECTION_HEADER_HEIGHT - DIVIDER_HEIGHT },
            modifier = Modifier.fillMaxSize().background(colors.p).clipToBounds()
                .onSizeChanged { cardHeight = with(density) { it.height.toDp() } },
        )
        return
    }
    Column(Modifier.fillMaxSize().background(colors.p)) {
        when (mirrorMode) {
            // Embedded but detached — the attached case returned above.
            CaptureMirrorMode.EMBEDDED -> {
                DeviceHeaderRow("DEVICE")
                Box(
                    Modifier.fillMaxWidth().padding(12.dp).height(76.dp)
                        .border(1.dp, colors.br, RoundedCornerShape(8.dp)),
                    contentAlignment = Alignment.Center,
                ) {
                    AppText(
                        "Device mirror is open in its own window.",
                        color = colors.td,
                        fontSize = 10.sp,
                        modifier = Modifier.padding(horizontal = 12.dp),
                    )
                }
            }
            CaptureMirrorMode.EXTERNAL -> {
                DeviceHeaderRow("DEVICE")
                Column(Modifier.fillMaxWidth().padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    AppText(
                        "Device display is running in an external scrcpy window.",
                        color = colors.td,
                        fontSize = 10.sp,
                        maxLines = 2,
                    )
                    AppButton("Open scrcpy window", { state.openExternalCaptureMirror(tab.id) }, ButtonVariant.Secondary)
                }
            }
            CaptureMirrorMode.DISABLED -> {
                DeviceHeaderRow("DEVICE")
                Column(Modifier.fillMaxWidth().padding(12.dp)) {
                    AppText("Device display is off for this capture.", color = colors.td, fontSize = 10.sp)
                }
            }
        }
        CaptureMarkerSection(state, tab, markersExpanded, toggleMarkers)
    }
}

/**
 * The mirror panel above the marker section, in a fixed-height card, with the picture taking
 * priority. The markers always get [belowReserve] (their header, plus a couple of rows while the
 * list is open); the mirror may use everything else, and is measured at its natural height within
 * that. The markers then get whatever is left and scroll inside it. So adding markers never resizes
 * the picture — only opening or closing the list, or the card itself changing size, does.
 *
 * [extraMirrorHeight] is the bar's text row while it is open: the panel takes that row back out
 * before sizing the picture, so the row pushes the markers down rather than shrinking the picture,
 * and whatever then passes the card's bottom edge is clipped by the caller. Needs a bounded height,
 * which the sidebar slot always is.
 */
@Composable
private fun MirrorAboveMarkersLayout(
    extraMirrorHeight: Dp,
    belowReserve: Dp,
    mirror: @Composable () -> Unit,
    below: @Composable () -> Unit,
    modifier: Modifier = Modifier,
    onBelowSpace: (Int) -> Unit = {},
) {
    Layout(contents = listOf(mirror, below), modifier = modifier) { (mirrorMeasurables, belowMeasurables), constraints ->
        val width = constraints.maxWidth
        val height = if (constraints.hasBoundedHeight) constraints.maxHeight else MIRROR_DEFAULT_HEIGHT.roundToPx()
        val mirrorMax = (height - belowReserve.roundToPx()).coerceAtLeast(0) + extraMirrorHeight.roundToPx()
        val mirrorPlaceables = mirrorMeasurables.map {
            it.measure(Constraints(minWidth = width, maxWidth = width, maxHeight = mirrorMax))
        }
        val mirrorHeight = mirrorPlaceables.maxOfOrNull { it.height } ?: 0
        val belowMax = (height - mirrorHeight).coerceAtLeast(0)
        val belowPlaceables = belowMeasurables.map {
            it.measure(Constraints(minWidth = width, maxWidth = width, maxHeight = belowMax))
        }
        layout(width, height) {
            onBelowSpace(belowMax)
            mirrorPlaceables.forEach { it.placeRelative(0, 0) }
            var y = mirrorHeight
            belowPlaceables.forEach {
                it.placeRelative(0, y)
                y += it.height
            }
        }
    }
}

/** Device/Elapsed/Storage/Video moved into the strip itself (Problem 1 in the restyle plan: they
 * used to be printed twice); this freed space is for markers instead.
 *
 * The list is always DERIVED from `<!-- indagium:marker v1 ... -->` Note headers in
 * `tab.annotations.blocks` (capture/CaptureMarkerCodec.kt, mirroring `diagram3/Seq3Codec`'s
 * approach for diagrams) — nothing about a marker is stored anywhere else, so this is the only
 * place that needs to know the on-disk shape. Memoised on the block list's own identity so a
 * completely unrelated annotation edit (e.g. typing in the prefix) doesn't re-walk every block.
 *
 * The header collapses the list; the caller owns that state because it also decides how much room
 * the list gets (see MirrorAboveMarkersLayout). The header and the short-lived Undo row sit outside
 * the scroll area so they stay put, and an Undo stays reachable while the list is collapsed. The
 * list takes whatever height is left, scrolls, and follows a newly added marker to the bottom. */
@Composable
private fun ColumnScope.CaptureMarkerSection(state: AppState, tab: LogTab, expanded: Boolean, onToggle: () -> Unit) {
    val colors = tc()
    val entries = remember(tab.id, tab.annotations.blocks) { deriveMarkerEntries(tab.annotations.blocks) }
    var pendingDelete by remember(tab.id) { mutableStateOf<MarkerListEntry?>(null) }
    SectionHeader("MARKERS · ${entries.size}", expanded = expanded, onToggle = onToggle)
    val undo = state.markerUndoByTab[tab.id]
    if (undo != null) {
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 12.dp).padding(bottom = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            AppText("Marker added", color = colors.td, fontSize = 11.sp, modifier = Modifier.weight(1f))
            AppButton("Undo", { state.undoMarkIssue(tab.id) }, ButtonVariant.Secondary)
        }
    }
    pendingDelete?.let { entry ->
        ConfirmDeleteMarkerDialog(
            entry = entry,
            onConfirm = {
                entry.marker.noteBlockId?.let { state.deleteMarker(tab.id, it) }
                pendingDelete = null
            },
            onDismiss = { pendingDelete = null },
        )
    }
    if (!expanded) return
    val scroll = rememberScrollState()
    LaunchedEffect(entries.size) {
        withFrameNanos { } // let the new row lay out so maxValue includes it
        scroll.animateScrollTo(scroll.maxValue)
    }
    Box(Modifier.fillMaxWidth().weight(1f, fill = false).padding(bottom = 10.dp)) {
        Column(
            Modifier.fillMaxWidth().padding(start = 12.dp, end = 14.dp).verticalScroll(scroll),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            if (entries.isEmpty()) {
                AppText("No markers yet", color = colors.td, fontSize = 11.sp)
            } else {
                entries.forEachIndexed { index, entry ->
                    CaptureMarkerRow(state, tab, entry, highlighted = index == entries.lastIndex, onDelete = { pendingDelete = entry })
                }
            }
        }
        // Only when the list overflows (an always-on track would read as a disabled control) — and
        // then this Box is already at its full height, so fillMaxHeight matches the visible list.
        if (scroll.maxValue > 0) {
            VerticalScrollbar(
                adapter = rememberScrollbarAdapter(scroll),
                modifier = Modifier.align(Alignment.CenterEnd).fillMaxHeight().padding(end = 2.dp),
                style = appScrollbarStyle(colors),
            )
        }
    }
}

/** Asks before deleting: a marker is evidence, and its note may already hold typed analysis. */
@Composable
private fun ConfirmDeleteMarkerDialog(entry: MarkerListEntry, onConfirm: () -> Unit, onDismiss: () -> Unit) {
    val colors = tc()
    Dialog(onDismissRequest = onDismiss) {
        Column(
            Modifier.width(360.dp).background(colors.p, RoundedCornerShape(8.dp))
                .border(1.dp, colors.br, RoundedCornerShape(8.dp)).padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            AppText("Delete marker?", color = colors.tx, fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
            AppText(
                "\"${entry.marker.label}\" at ${formatCaptureElapsed(entry.marker.elapsedMs.coerceAtLeast(0L))} — " +
                    "its note, screenshot and log excerpt are removed from Notes, including anything typed in that note.",
                color = colors.td,
                fontSize = 11.sp,
                maxLines = 4,
            )
            Row(
                Modifier.fillMaxWidth().padding(top = 8.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterHorizontally),
            ) {
                DialogActionButton("Cancel", active = false, onClick = onDismiss)
                DialogActionButton("Delete", active = true, danger = true, onClick = onConfirm)
            }
        }
    }
}

private val CAPTURE_MARKER_ROW_CORNER = RoundedCornerShape(7.dp)
private val CAPTURE_MARKER_THUMB_CORNER = RoundedCornerShape(4.dp)
private val CAPTURE_MARKER_THUMB_WIDTH = 26.dp
private val CAPTURE_MARKER_THUMB_HEIGHT = 20.dp

/** One marker: elapsed + label (+ "(collecting…)" while its trailing LogRef hasn't landed yet —
 * see AppState.finishMarkerWindow) and a screenshot thumbnail on the right when the press took
 * one, inside a one-line bordered card. Single-click is only wired once a LogRef exists — before
 * that there is nothing yet for [AppState.requestAnnotationNavigation] to select — but
 * double-click always reveals the marker's own note in Notes, via the same channel AI evidence
 * cards use ([AppState.revealNoteBlock]/`aiEvidenceNoteTarget`), since that works off the note
 * block id alone and needs no LogRef. [highlighted] marks the newest marker (last in note order). */
@Composable
private fun CaptureMarkerRow(state: AppState, tab: LogTab, entry: MarkerListEntry, highlighted: Boolean, onDelete: () -> Unit) {
    val colors = tc()
    val logRef = entry.logRef
    val borderColor = if (highlighted) DANGER_RED.copy(alpha = .35f) else colors.br
    val bgColor = if (highlighted) DANGER_RED.copy(alpha = .07f) else colors.p2
    val timeColor = if (highlighted) DANGER_RED else colors.ts
    Row(
        Modifier.fillMaxWidth()
            .background(bgColor, CAPTURE_MARKER_ROW_CORNER)
            .border(1.dp, borderColor, CAPTURE_MARKER_ROW_CORNER)
            .clip(CAPTURE_MARKER_ROW_CORNER)
            .combinedClickable(
                onClick = { if (logRef != null) state.requestAnnotationNavigation(tab.id, logRef) },
                onDoubleClick = { entry.marker.noteBlockId?.let { state.revealNoteBlock(tab.id, it) } },
            )
            .padding(horizontal = 7.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(9.dp),
    ) {
        AppText(
            formatCaptureElapsed(entry.marker.elapsedMs.coerceAtLeast(0L)),
            color = timeColor,
            fontSize = 11.sp,
            fontFamily = FontFamily.Monospace,
            fontWeight = FontWeight.SemiBold,
        )
        AppText(
            if (logRef == null) "${entry.marker.label} (collecting…)" else entry.marker.label,
            color = colors.tx,
            fontSize = 11.5.sp,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )
        entry.screenshot?.let { image ->
            val bitmap = remember(image.id) { decodeImageBlockBitmap(image.bytes) }
            if (bitmap != null) {
                Image(
                    bitmap = bitmap,
                    contentDescription = null,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.size(width = CAPTURE_MARKER_THUMB_WIDTH, height = CAPTURE_MARKER_THUMB_HEIGHT)
                        .clip(CAPTURE_MARKER_THUMB_CORNER),
                )
            }
        }
        MarkerDeleteButton(onDelete)
    }
}

@Composable
private fun MarkerDeleteButton(onClick: () -> Unit) {
    val colors = tc()
    var hovered by remember { mutableStateOf(false) }
    TooltipArea(tooltip = { ToolbarTooltip("Delete marker") }) {
        Box(
            Modifier.size(22.dp)
                .background(if (hovered) colors.hv else Color.Transparent, CORNER_MD)
                .clip(CORNER_MD)
                .clickable(onClick = onClick)
                .onPointerEvent(PointerEventType.Enter) { hovered = true }
                .onPointerEvent(PointerEventType.Exit) { hovered = false },
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                Icons.Outlined.DeleteOutline,
                contentDescription = "Delete marker",
                tint = if (hovered) DANGER_RED else colors.td,
                modifier = Modifier.size(15.dp),
            )
        }
    }
}

/** One derived marker plus the screenshot/LogRef blocks written alongside it — everything between
 * its Note and the next one (or the end of Notes), matching the exact insertion order
 * AppState.markIssue writes: Note, then optionally an Image, then eventually a LogRef. */
private data class MarkerListEntry(
    val marker: CaptureMarker,
    val screenshot: AnnBlock.Image?,
    val logRef: AnnBlock.LogRef?,
)

/** The blocks a marker owns: its Note, then the first Image and first LogRef before the next Note —
 * the same grouping [deriveMarkerEntries] shows as one row, so deleting a row removes exactly what
 * the row displayed. */
internal fun markerOwnedBlockIds(blocks: List<AnnBlock>, noteId: String): List<String> {
    val start = blocks.indexOfFirst { it.id == noteId }
    if (start < 0) return emptyList()
    val following = blocks.drop(start + 1).takeWhile { it !is AnnBlock.Note }
    return listOfNotNull(
        noteId,
        following.firstOrNull { it is AnnBlock.Image }?.id,
        following.firstOrNull { it is AnnBlock.LogRef }?.id,
    )
}

private fun deriveMarkerEntries(blocks: List<AnnBlock>): List<MarkerListEntry> {
    val entries = mutableListOf<MarkerListEntry>()
    var i = 0
    while (i < blocks.size) {
        val block = blocks[i]
        val marker = (block as? AnnBlock.Note)?.let { parseMarkerHeader(it.text) }
        if (marker != null) {
            var screenshot: AnnBlock.Image? = null
            var logRef: AnnBlock.LogRef? = null
            var j = i + 1
            while (j < blocks.size && blocks[j] !is AnnBlock.Note) {
                when (val sibling = blocks[j]) {
                    is AnnBlock.Image -> if (screenshot == null) screenshot = sibling
                    is AnnBlock.LogRef -> if (logRef == null) logRef = sibling
                    else -> Unit
                }
                j++
            }
            entries.add(MarkerListEntry(marker.copy(noteBlockId = block.id), screenshot, logRef))
        }
        i++
    }
    return entries
}
