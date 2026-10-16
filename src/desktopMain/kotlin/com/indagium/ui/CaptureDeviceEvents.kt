@file:OptIn(
    androidx.compose.foundation.ExperimentalFoundationApi::class,
    androidx.compose.ui.ExperimentalComposeUiApi::class,
)

package com.indagium.ui

import androidx.compose.foundation.TooltipArea
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.DisableSelection
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.onPointerEvent
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntRect
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupPositionProvider
import androidx.compose.ui.window.PopupProperties
import com.indagium.capture.CaptureAvailabilityInterval
import com.indagium.capture.CaptureMappingRow
import com.indagium.capture.CaptureTimeline
import com.indagium.model.LogEntry
import com.indagium.model.LogLevel
import com.indagium.model.LogTab
import com.indagium.utils.RegexEvaluationContext
import com.indagium.utils.passesFilter
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.withContext
import java.lang.ref.WeakReference
import java.util.Locale

private const val CAPTURE_EVENT_TAG = "IndagiumCapture"
private val UNAVAILABLE_MARKER_PATTERN = Regex("^Device unavailable; elapsedMs=(\\d+)$")
private val RECONNECTED_MARKER_PATTERN = Regex("^Device reconnected; unavailableMs=(\\d+); reboot=(true|false|unknown)$")

internal enum class CaptureDeviceEventMarkerKind { UNAVAILABLE, RECONNECTED }

/** A genuine synthetic transition line already parsed into the tab's normal log rows. */
internal data class CaptureDeviceEventMarker(
    val entryId: Int,
    /** 1-based position in the tab's parsed log rows; capture timeline ordinals use this, not id. */
    val rowOrdinal: Int,
    val kind: CaptureDeviceEventMarkerKind,
    /** Absolute elapsed time encoded by the recorder's unavailable marker, when present. */
    val captureElapsedMs: Long? = null,
    val unavailableMs: Long? = null,
    /** Raw text is only used to reject mismatches; interval metadata remains authoritative. */
    val rebootEvidence: Boolean? = null,
    val rebootEvidenceKnown: Boolean = false,
)

internal data class CaptureDeviceEventLogTargets(
    val unavailableEntryId: Int? = null,
    val reconnectedEntryId: Int? = null,
)

internal enum class CaptureDeviceEventStatus(val label: String) {
    REBOOT_CONFIRMED("Reboot confirmed"),
    SAME_BOOT("Reconnected same boot"),
    REBOOT_UNKNOWN("Reconnected reboot unknown"),
    WAITING("Waiting"),
}

internal data class CaptureDeviceEventSummary(
    val disconnectCount: Int,
    val confirmedRebootCount: Int,
    val waitingCount: Int,
)

internal data class CaptureDeviceEventsModel(
    val intervals: List<CaptureAvailabilityInterval>,
    val timelineBaseElapsedMs: Long,
    val timelineDurationMs: Long,
    val timelineRows: List<CaptureMappingRow>,
    val targets: List<CaptureDeviceEventLogTargets>,
    val summary: CaptureDeviceEventSummary,
    val markerEnabled: Boolean?,
) {
    val unresolvedTransitions: Int
        get() = intervals.indices.sumOf { index ->
            val target = targets.getOrNull(index) ?: CaptureDeviceEventLogTargets()
            (if (target.unavailableEntryId == null) 1 else 0) +
                (if (target.reconnectedEntryId == null && intervals[index].endElapsedMs != null) 1 else 0)
        }

    val markerHint: String? get() = captureDeviceEventMarkerHint(markerEnabled, unresolvedTransitions)
    val summaryLabel: String get() = buildString {
        append(summary.disconnectCount)
        append(if (summary.disconnectCount == 1) " disconnect" else " disconnects")
        append(" · ${summary.confirmedRebootCount} reboot${if (summary.confirmedRebootCount == 1) "" else "s"} confirmed")
        if (summary.waitingCount > 0) append(" · ${summary.waitingCount} waiting")
    }
}

internal fun captureDeviceEventStatus(interval: CaptureAvailabilityInterval): CaptureDeviceEventStatus = when {
    interval.endElapsedMs == null -> CaptureDeviceEventStatus.WAITING
    interval.rebootConfirmed == true -> CaptureDeviceEventStatus.REBOOT_CONFIRMED
    interval.rebootConfirmed == false -> CaptureDeviceEventStatus.SAME_BOOT
    else -> CaptureDeviceEventStatus.REBOOT_UNKNOWN
}

internal fun captureDeviceEventSummary(intervals: List<CaptureAvailabilityInterval>): CaptureDeviceEventSummary =
    CaptureDeviceEventSummary(
        disconnectCount = intervals.size,
        confirmedRebootCount = intervals.count { captureDeviceEventStatus(it) == CaptureDeviceEventStatus.REBOOT_CONFIRMED },
        waitingCount = intervals.count { captureDeviceEventStatus(it) == CaptureDeviceEventStatus.WAITING },
    )

/** Places the menu beside its pill, clamping it inside the app window and flipping above if needed. */
internal class CaptureDeviceEventsPopupPositionProvider(
    private val marginPx: Int,
    private val gapPx: Int,
) : PopupPositionProvider {
    override fun calculatePosition(
        anchorBounds: IntRect,
        windowSize: IntSize,
        layoutDirection: LayoutDirection,
        popupContentSize: IntSize,
    ): IntOffset {
        val margin = marginPx.coerceAtLeast(0)
        val maxX = (windowSize.width - popupContentSize.width - margin).coerceAtLeast(margin)
        val preferredX = if (layoutDirection == LayoutDirection.Ltr) anchorBounds.left else anchorBounds.right - popupContentSize.width
        val x = preferredX.coerceIn(margin, maxX)
        val below = anchorBounds.bottom + gapPx
        val above = anchorBounds.top - popupContentSize.height - gapPx
        val preferredY = if (below + popupContentSize.height <= windowSize.height - margin || above < margin) below else above
        val maxY = (windowSize.height - popupContentSize.height - margin).coerceAtLeast(margin)
        return IntOffset(x, preferredY.coerceIn(margin, maxY))
    }
}

/** Convert an imported child's local timeline value back to the original capture's elapsed clock. */
internal fun captureDeviceEventSourceElapsedMs(localElapsedMs: Long, timelineBaseElapsedMs: Long): Long? =
    runCatching { Math.addExact(localElapsedMs, timelineBaseElapsedMs) }.getOrNull()

/** Parse only the two recorder-owned line shapes; ordinary log messages cannot become jump targets. */
internal fun parseCaptureDeviceEventMarker(entry: LogEntry, rowOrdinal: Int): CaptureDeviceEventMarker? {
    if (entry.tag != CAPTURE_EVENT_TAG || entry.level != LogLevel.W || entry.pid != 0 || entry.tid != 0) return null
    return parseCaptureDeviceEventMarker(entry.id, rowOrdinal, entry.msg.trim())
}

private fun parseCaptureDeviceEventMarker(entryId: Int, rowOrdinal: Int, message: String): CaptureDeviceEventMarker? {
    val unavailable = UNAVAILABLE_MARKER_PATTERN.matchEntire(message)
    if (unavailable != null) {
        val elapsed = unavailable.groupValues[1].toLongOrNull() ?: return null
        return CaptureDeviceEventMarker(
            entryId = entryId,
            rowOrdinal = rowOrdinal,
            kind = CaptureDeviceEventMarkerKind.UNAVAILABLE,
            captureElapsedMs = elapsed,
        )
    }
    val reconnected = RECONNECTED_MARKER_PATTERN.matchEntire(message) ?: return null
    val duration = reconnected.groupValues[1].toLongOrNull() ?: return null
    val rebootText = reconnected.groupValues[2]
    return CaptureDeviceEventMarker(
        entryId = entryId,
        rowOrdinal = rowOrdinal,
        kind = CaptureDeviceEventMarkerKind.RECONNECTED,
        unavailableMs = duration,
        rebootEvidence = rebootText.takeIf { it != "unknown" }?.toBooleanStrictOrNull(),
        rebootEvidenceKnown = rebootText != "unknown",
    )
}

/**
 * Resolve only transition lines that can be tied to structured interval timing. Imported v4 row
 * mappings are export-local, so compare them directly to the clipped interval; the header's base
 * is used only to match the absolute elapsed value embedded in an unavailable marker. Live rows
 * have no mapping yet, so reconnect lines are paired by capture order only when the complete set
 * of closed intervals and reconnect markers is present.
 */
internal fun captureDeviceEventLogTargets(
    intervals: List<CaptureAvailabilityInterval>,
    timelineBaseElapsedMs: Long,
    timelineRows: List<CaptureMappingRow>,
    markers: List<CaptureDeviceEventMarker>,
): List<CaptureDeviceEventLogTargets> {
    if (intervals.isEmpty()) return emptyList()
    val rowsAreAuthoritative = timelineRows.isNotEmpty()
    val unavailableMarkers = markers.filter { it.kind == CaptureDeviceEventMarkerKind.UNAVAILABLE }.sortedBy { it.rowOrdinal }
    val reconnectMarkers = markers.filter { it.kind == CaptureDeviceEventMarkerKind.RECONNECTED }.sortedBy { it.rowOrdinal }
    val closed = intervals.filter { it.endElapsedMs != null }
    val canPairLiveReconnects = !rowsAreAuthoritative && reconnectMarkers.size == closed.size
    var closedIndex = 0

    return intervals.map { interval ->
        val localStart = interval.startElapsedMs
        val absoluteStart = captureDeviceEventSourceElapsedMs(localStart, timelineBaseElapsedMs)
        val unavailable = unavailableMarkers.singleOrNull { marker ->
            if (rowsAreAuthoritative) {
                marker.captureElapsedMs != null && timelineRowAtOrdinal(timelineRows, marker.rowOrdinal)?.elapsedMs == localStart
            } else {
                absoluteStart != null && marker.captureElapsedMs == absoluteStart
            }
        }?.entryId

        val localEnd = interval.endElapsedMs
        val reconnected = if (localEnd == null) {
            null
        } else if (rowsAreAuthoritative) {
            reconnectMarkers.singleOrNull { marker ->
                marker.unavailableMs == (localEnd - localStart).coerceAtLeast(0L) &&
                    markerEvidenceMatches(marker, interval.rebootConfirmed) &&
                    timelineRowAtOrdinal(timelineRows, marker.rowOrdinal)?.elapsedMs == localEnd
            }?.entryId
        } else if (canPairLiveReconnects) {
            val marker = reconnectMarkers.getOrNull(closedIndex++)
            marker?.takeIf {
                it.unavailableMs == (localEnd - localStart).coerceAtLeast(0L) &&
                    markerEvidenceMatches(it, interval.rebootConfirmed)
            }?.entryId
        } else {
            null
        }
        CaptureDeviceEventLogTargets(unavailable, reconnected)
    }
}

/** Timeline rows are stored by ordinal; binary search avoids allocating a map for large archives. */
internal fun timelineRowAtOrdinal(rows: List<CaptureMappingRow>, ordinal: Int): CaptureMappingRow? {
    var low = 0
    var high = rows.lastIndex
    while (low <= high) {
        val middle = (low + high) ushr 1
        val row = rows[middle]
        when {
            row.ordinal < ordinal -> low = middle + 1
            row.ordinal > ordinal -> high = middle - 1
            else -> return row
        }
    }
    return null
}

private fun markerEvidenceMatches(marker: CaptureDeviceEventMarker, rebootConfirmed: Boolean?): Boolean =
    marker.rebootEvidenceKnown == (rebootConfirmed != null) &&
        (!marker.rebootEvidenceKnown || marker.rebootEvidence == rebootConfirmed)

internal fun captureDeviceEventMarkerHint(markerEnabled: Boolean?, unresolvedTransitions: Int): String? = when {
    unresolvedTransitions <= 0 -> null
    markerEnabled == false -> "Availability log markers were disabled for this capture; event timing is still recorded here."
    else -> "No matching transition marker is loaded, so an exact log jump is unavailable for this event."
}

internal fun captureDeviceEventTargetPassesCurrentFilter(tab: LogTab, entryId: Int): Boolean {
    val entry = tab.rmap[entryId] ?: return false
    return passesFilter(entry, tab.filter, tab.analysis.processNames, RegexEvaluationContext(), tab.analysis.tagPids)
}

internal const val CAPTURE_DEVICE_EVENT_FILTERED_HINT =
    "This transition line is hidden by the current filter. Clear or adjust the filter, then try again."

internal fun navigateToCaptureDeviceEventEntry(state: AppState, tabId: String, entryId: Int): Boolean {
    val currentTab = state.tab(tabId) ?: return false
    if (!captureDeviceEventTargetPassesCurrentFilter(currentTab, entryId)) return false
    state.navigateToLogLine(tabId, entryId)
    return true
}

private class CaptureLogRowsSignal(
    val rows: List<LogEntry>,
    val sourcePath: String?,
) {
    override fun equals(other: Any?): Boolean =
        other is CaptureLogRowsSignal && rows === other.rows && sourcePath == other.sourcePath

    override fun hashCode(): Int = 31 * System.identityHashCode(rows) + (sourcePath?.hashCode() ?: 0)
}

private class CaptureRowsIdentity(val rows: List<CaptureMappingRow>) {
    override fun equals(other: Any?): Boolean = other is CaptureRowsIdentity && rows === other.rows

    override fun hashCode(): Int = System.identityHashCode(rows)
}

internal fun captureDeviceEventPrefixSamples(rows: List<LogEntry>, size: Int): List<Pair<Int, LogEntry>> {
    if (size <= 0) return emptyList()
    val positions = listOf(0, size / 4, size / 2, size * 3 / 4, size - 1).distinct()
    return positions.mapNotNull { index -> rows.getOrNull(index)?.let { index to it } }
}

internal fun captureDeviceEventPrefixUnchanged(rows: List<LogEntry>, scannedSize: Int, samples: List<Pair<Int, LogEntry>>): Boolean =
    rows.size >= scannedSize && samples.all { (index, entry) -> rows.getOrNull(index) == entry }

/** One per-tab scan shared by the capture-strip pill and FilterPanel's Issues subsection. */
@Composable
internal fun rememberCaptureDeviceEvents(state: AppState, tab: LogTab): CaptureDeviceEventsModel? {
    val controller = state.captureControllerFor(tab.id)
    val liveSnapshotState = controller?.snapshot?.collectAsState()
    val liveSnapshot = liveSnapshotState?.value
    val session = liveSnapshot?.session ?: state.captureSnapshotSessionFor(tab.id)
    val timeline = state.captureTimelineFor(tab.id) ?: session?.let { current ->
        CaptureTimeline(
            rows = emptyList(),
            availabilityIntervals = current.availabilityIntervals,
            timelineBaseElapsedMs = 0L,
            timelineDurationMs = current.elapsedMs.coerceAtLeast(0L),
            videoOriginElapsedMs = current.videoStartElapsedMs?.minus(current.manualOffsetMs),
            showUnavailableFrames = current.settings.showUnavailableFrames,
        )
    }
    val activeTimeline = timeline ?: return null
    val intervals = activeTimeline.availabilityIntervals
    if (intervals.isEmpty()) return null

    val latestRows by rememberUpdatedState(tab.logData)
    val latestSourcePath by rememberUpdatedState(tab.sourcePath)
    var markers by remember(tab.id) { mutableStateOf(emptyList<CaptureDeviceEventMarker>()) }
    val intervalCount = intervals.size
    LaunchedEffect(tab.id, intervalCount > 0) {
        if (intervalCount == 0) return@LaunchedEffect
        var scannedSize = 0
        var prefixSamples = emptyList<Pair<Int, LogEntry>>()
        var previousRows = WeakReference<List<LogEntry>>(null)
        var previousSourcePath: String? = null
        snapshotFlow { CaptureLogRowsSignal(latestRows, latestSourcePath) }
            .distinctUntilChanged { old, new -> old.rows === new.rows && old.sourcePath == new.sourcePath }
            .collect { signal ->
                val snapshotRows = signal.rows
                val previousList = previousRows.get()
                val sameSource = previousSourcePath == signal.sourcePath
                val prefixUnchanged = scannedSize == 0 || captureDeviceEventPrefixUnchanged(snapshotRows, scannedSize, prefixSamples)
                val fullRescan = !sameSource || snapshotRows.size < scannedSize || !prefixUnchanged ||
                    (snapshotRows.size == scannedSize && previousList !== snapshotRows)
                val start = if (fullRescan) 0 else scannedSize.coerceAtMost(snapshotRows.size)
                val found = withContext(Dispatchers.Default) {
                    val parsed = ArrayList<CaptureDeviceEventMarker>()
                    for (index in start until snapshotRows.size) {
                        if (index % 2_048 == 0) currentCoroutineContext().ensureActive()
                        parseCaptureDeviceEventMarker(snapshotRows[index], index + 1)?.let(parsed::add)
                    }
                    parsed
                }
                if (fullRescan) {
                    markers = found
                } else if (found.isNotEmpty()) {
                    markers = (markers + found).sortedBy { it.rowOrdinal }
                }
                scannedSize = snapshotRows.size
                prefixSamples = captureDeviceEventPrefixSamples(snapshotRows, scannedSize)
                previousRows = WeakReference(snapshotRows)
                previousSourcePath = signal.sourcePath
            }
    }

    val targets = remember(intervals, activeTimeline.timelineBaseElapsedMs, CaptureRowsIdentity(activeTimeline.rows), markers) {
        captureDeviceEventLogTargets(intervals, activeTimeline.timelineBaseElapsedMs, activeTimeline.rows, markers)
    }
    val summary = remember(intervals) { captureDeviceEventSummary(intervals) }
    val isImported = state.isImportedCaptureSnapshot(tab.id)
    val markerEnabled = if (isImported) null else session?.settings?.writeDeviceAvailabilityMarkers
    return remember(
        intervals,
        activeTimeline.timelineBaseElapsedMs,
        activeTimeline.timelineDurationMs,
        CaptureRowsIdentity(activeTimeline.rows),
        targets,
        summary,
        markerEnabled,
    ) {
        CaptureDeviceEventsModel(
            intervals = intervals,
            timelineBaseElapsedMs = activeTimeline.timelineBaseElapsedMs,
            timelineDurationMs = activeTimeline.timelineDurationMs,
            timelineRows = activeTimeline.rows,
            targets = targets,
            summary = summary,
            markerEnabled = markerEnabled,
        )
    }
}

@Composable
internal fun CaptureDeviceEventsDropdown(
    state: AppState,
    tab: LogTab,
    events: CaptureDeviceEventsModel,
    onReturnFocus: () -> Unit,
) {
    val colors = tc()
    var open by remember(tab.id) { mutableStateOf(false) }
    var hovered by remember(tab.id) { mutableStateOf(false) }
    var suppressToggleUntilMs by remember(tab.id) { mutableStateOf(0L) }
    val density = LocalDensity.current
    val tooltip = buildString {
        append("Device events detected by Indagium: ")
        append(events.summary.disconnectCount)
        append(if (events.summary.disconnectCount == 1) " disconnect" else " disconnects")
        append(" · ")
        append(events.summary.confirmedRebootCount)
        append(if (events.summary.confirmedRebootCount == 1) " reboot confirmed" else " reboots confirmed")
        if (events.summary.waitingCount > 0) append(" · waiting for device")
    }
    val popupPositionProvider = remember(density) {
        CaptureDeviceEventsPopupPositionProvider(
            marginPx = with(density) { 8.dp.roundToPx() },
            gapPx = with(density) { 4.dp.roundToPx() },
        )
    }
    TooltipArea(tooltip = { ToolbarTooltip(tooltip) }) {
        Box {
            Box(
                Modifier.height(24.dp)
                    .border(1.dp, if (events.summary.waitingCount > 0) colors.warn.copy(alpha = .55f) else colors.br, RoundedCornerShape(12.dp))
                    .background(if (open) colors.p2 else Color.Transparent, RoundedCornerShape(12.dp))
                    .clip(RoundedCornerShape(12.dp))
                    .testTag("capture-device-events-trigger")
                    .onPreviewKeyEvent { event ->
                        if (open && event.type == KeyEventType.KeyDown && event.key == Key.Escape) {
                            open = false
                            suppressToggleUntilMs = System.currentTimeMillis() + 200
                            onReturnFocus()
                            true
                        } else {
                            false
                        }
                    }
                    .clickable {
                        if (System.currentTimeMillis() >= suppressToggleUntilMs) {
                            if (open) {
                                open = false
                                onReturnFocus()
                            } else {
                                open = true
                            }
                        }
                    }
                    .padding(horizontal = 9.dp)
                    .onPointerEvent(PointerEventType.Enter) { hovered = true }
                    .onPointerEvent(PointerEventType.Exit) { hovered = false },
                contentAlignment = Alignment.Center,
            ) {
                DisableSelection {
                    AppText(
                        "Events ${events.summary.disconnectCount}  ▾",
                        color = when {
                            events.summary.waitingCount > 0 || events.summary.confirmedRebootCount > 0 -> colors.warn
                            hovered || open -> colors.tx
                            else -> colors.td
                        },
                        fontSize = 10.sp,
                        fontWeight = FontWeight.Medium,
                        maxLines = 1,
                    )
                }
            }
            if (open) {
                Popup(
                    popupPositionProvider = popupPositionProvider,
                    onDismissRequest = {
                        open = false
                        suppressToggleUntilMs = System.currentTimeMillis() + 200
                        onReturnFocus()
                    },
                    properties = PopupProperties(
                        // Non-focusable layers forward outside presses to the underlying Compose
                        // content; focusable layers consume them before adjacent controls run.
                        // The trigger keeps keyboard focus, and handles Escape itself below.
                        focusable = false,
                        dismissOnBackPress = true,
                        dismissOnClickOutside = true,
                    ),
                ) {
                    Surface(
                        modifier = Modifier.width(372.dp).heightIn(max = 360.dp)
                            .shadow(8.dp, RoundedCornerShape(8.dp))
                            .border(1.dp, colors.br, RoundedCornerShape(8.dp))
                            .testTag("capture-device-events-popup"),
                        color = colors.p,
                        shape = RoundedCornerShape(8.dp),
                    ) {
                        CaptureDeviceEventsDetails(
                            state = state,
                            tab = tab,
                            events = events,
                            onReturnFocus = onReturnFocus,
                            onClose = { open = false },
                            showCaptureTip = true,
                            modifier = Modifier.fillMaxWidth().heightIn(max = 360.dp),
                        )
                    }
                }
            }
        }
    }
}

@Composable
internal fun CaptureDeviceEventsIssueRows(
    state: AppState,
    tab: LogTab,
    events: CaptureDeviceEventsModel,
    onReturnFocus: () -> Unit,
) {
    val colors = tc()
    var jumpHint by remember(tab.id) { mutableStateOf<Pair<Int, String>?>(null) }
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(3.dp)) {
        if (events.intervals.isEmpty()) {
            AppText("No device events detected during this capture.", color = colors.ts, fontSize = 10.sp, modifier = Modifier.padding(10.dp))
        }
        events.intervals.forEachIndexed { index, interval ->
            val status = captureDeviceEventStatus(interval)
            val target = events.targets.getOrNull(index) ?: CaptureDeviceEventLogTargets()
            val duration = interval.endElapsedMs?.let { (it - interval.startElapsedMs).coerceAtLeast(0L) }
                ?: (events.timelineDurationMs - interval.startElapsedMs).coerceAtLeast(0L)
            val period = if (interval.endElapsedMs != null) {
                "${formatCaptureDeviceEventElapsed(interval.startElapsedMs)}–${formatCaptureDeviceEventElapsed(interval.endElapsedMs)}"
            } else {
                "${formatCaptureDeviceEventElapsed(interval.startElapsedMs)}–now"
            }
            Column(
                Modifier.fillMaxWidth()
                    .testTag("capture-device-event-row-$index")
                    .padding(horizontal = 10.dp, vertical = 5.dp),
                verticalArrangement = Arrangement.spacedBy(2.dp),
            ) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    AppText(
                        status.label,
                        color = if (status == CaptureDeviceEventStatus.REBOOT_CONFIRMED) colors.warn else colors.ac,
                        fontSize = 9.sp,
                        fontWeight = FontWeight.SemiBold,
                    )
                    AppText(
                        formatCaptureDeviceEventElapsed(interval.startElapsedMs),
                        color = colors.td,
                        fontSize = 9.sp,
                        fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace,
                    )
                }
                AppText(
                    "$period · ${formatCaptureDeviceEventDuration(duration)}${if (interval.endElapsedMs == null) " so far" else " downtime"}",
                    color = colors.tx,
                    fontSize = 10.sp,
                    fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    target.unavailableEntryId?.takeIf { it in tab.rmap }?.let { entryId ->
                        Seq3DropdownMenuItem("Jump to disconnect line") {
                            if (navigateToCaptureDeviceEventEntry(state, tab.id, entryId)) {
                                jumpHint = null
                                onReturnFocus()
                            } else {
                                jumpHint = index to CAPTURE_DEVICE_EVENT_FILTERED_HINT
                            }
                        }
                    }
                    target.reconnectedEntryId?.takeIf { it in tab.rmap }?.let { entryId ->
                        Seq3DropdownMenuItem("Jump to reconnect line") {
                            if (navigateToCaptureDeviceEventEntry(state, tab.id, entryId)) {
                                jumpHint = null
                                onReturnFocus()
                            } else {
                                jumpHint = index to CAPTURE_DEVICE_EVENT_FILTERED_HINT
                            }
                        }
                    }
                }
                jumpHint?.takeIf { it.first == index }?.let { (_, hint) ->
                    AppText(hint, color = colors.warn, fontSize = 9.sp, maxLines = 3)
                }
            }
        }
        events.markerHint?.let { AppText(it, color = colors.ts, fontSize = 9.sp, modifier = Modifier.padding(horizontal = 10.dp, vertical = 3.dp)) }
    }
}

@Composable
private fun CaptureDeviceEventsDetails(
    state: AppState,
    tab: LogTab,
    events: CaptureDeviceEventsModel,
    onReturnFocus: () -> Unit,
    onClose: () -> Unit,
    showCaptureTip: Boolean,
    modifier: Modifier = Modifier,
) {
    val colors = tc()
    var jumpHint by remember(tab.id) { mutableStateOf<Pair<Int, String>?>(null) }
    val scroll = rememberScrollState()
    Column(
        modifier = modifier.verticalScroll(scroll).padding(horizontal = 7.dp, vertical = 5.dp),
        verticalArrangement = Arrangement.spacedBy(5.dp),
    ) {
        AppText(events.summaryLabel, color = colors.tx, fontSize = 10.sp, fontWeight = FontWeight.Medium)
        if (showCaptureTip) {
            AppText(
                "Search tag: IndagiumCapture · reboot=true for confirmed reboots",
                color = colors.td,
                fontSize = 9.sp,
                fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace,
            )
        }
        events.intervals.forEachIndexed { index, interval ->
            val status = captureDeviceEventStatus(interval)
            val target = events.targets.getOrNull(index) ?: CaptureDeviceEventLogTargets()
            val duration = interval.endElapsedMs?.let { (it - interval.startElapsedMs).coerceAtLeast(0L) }
                ?: (events.timelineDurationMs - interval.startElapsedMs).coerceAtLeast(0L)
            Surface(color = colors.p2, shape = RoundedCornerShape(5.dp)) {
                Column(Modifier.fillMaxWidth().padding(horizontal = 7.dp, vertical = 5.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        AppText(status.label, color = if (status == CaptureDeviceEventStatus.REBOOT_CONFIRMED) colors.warn else colors.ac, fontSize = 10.sp)
                        AppText(
                            formatCaptureDeviceEventElapsed(interval.startElapsedMs),
                            color = colors.tx,
                            fontSize = 10.sp,
                            fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace,
                        )
                    }
                    val period = if (interval.endElapsedMs != null) {
                        "${formatCaptureDeviceEventElapsed(interval.startElapsedMs)}–${formatCaptureDeviceEventElapsed(interval.endElapsedMs)}"
                    } else {
                        "${formatCaptureDeviceEventElapsed(interval.startElapsedMs)}–now"
                    }
                    AppText(
                        "$period · ${formatCaptureDeviceEventDuration(duration)}${if (interval.endElapsedMs == null) " so far" else " downtime"}",
                        color = colors.ts,
                        fontSize = 9.sp,
                        fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace,
                    )
                    target.unavailableEntryId?.takeIf { it in tab.rmap }?.let { entryId ->
                        Seq3DropdownMenuItem("Jump to disconnect line") {
                            if (navigateToCaptureDeviceEventEntry(state, tab.id, entryId)) {
                                jumpHint = null
                                onClose()
                                onReturnFocus()
                            } else {
                                jumpHint = index to CAPTURE_DEVICE_EVENT_FILTERED_HINT
                            }
                        }
                    }
                    target.reconnectedEntryId?.takeIf { it in tab.rmap }?.let { entryId ->
                        Seq3DropdownMenuItem("Jump to reconnect line") {
                            if (navigateToCaptureDeviceEventEntry(state, tab.id, entryId)) {
                                jumpHint = null
                                onClose()
                                onReturnFocus()
                            } else {
                                jumpHint = index to CAPTURE_DEVICE_EVENT_FILTERED_HINT
                            }
                        }
                    }
                    jumpHint?.takeIf { it.first == index }?.let { (_, hint) ->
                        AppText(hint, color = colors.warn, fontSize = 9.sp, maxLines = 3)
                    }
                }
            }
        }
        events.markerHint?.let { AppText(it, color = colors.ts, fontSize = 9.sp) }
    }
}

private fun formatCaptureDeviceEventElapsed(elapsedMs: Long): String {
    val totalSeconds = elapsedMs.coerceAtLeast(0L) / CAPTURE_MILLIS_PER_SECOND
    val hours = totalSeconds / CAPTURE_SECONDS_PER_HOUR
    val minutes = totalSeconds % CAPTURE_SECONDS_PER_HOUR / CAPTURE_SECONDS_PER_MINUTE
    val seconds = totalSeconds % CAPTURE_SECONDS_PER_MINUTE
    return if (hours > 0L) String.format(Locale.ROOT, "%d:%02d:%02d", hours, minutes, seconds)
    else String.format(Locale.ROOT, "%02d:%02d", minutes, seconds)
}

private fun formatCaptureDeviceEventDuration(durationMs: Long): String {
    val seconds = durationMs.coerceAtLeast(0L) / CAPTURE_MILLIS_PER_SECOND
    return when {
        seconds < CAPTURE_SECONDS_PER_MINUTE -> "${seconds}s"
        seconds < CAPTURE_SECONDS_PER_HOUR -> "${seconds / CAPTURE_SECONDS_PER_MINUTE}m ${seconds % CAPTURE_SECONDS_PER_MINUTE}s"
        else -> "${seconds / CAPTURE_SECONDS_PER_HOUR}h ${seconds % CAPTURE_SECONDS_PER_HOUR / CAPTURE_SECONDS_PER_MINUTE}m"
    }
}
