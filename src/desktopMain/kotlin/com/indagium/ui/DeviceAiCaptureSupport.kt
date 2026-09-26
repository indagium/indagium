package com.indagium.ui

import com.indagium.capture.CaptureDevice
import com.indagium.capture.CaptureExportRequest
import com.indagium.capture.CaptureRange
import com.indagium.model.LogTab
import kotlinx.coroutines.CompletableDeferred
import java.io.File
import java.util.concurrent.ConcurrentHashMap

/** Pure capture-device resolution used by shared AI/MCP capture startup. */
internal sealed interface AiCaptureDeviceChoice {
    data class Selected(val device: CaptureDevice) : AiCaptureDeviceChoice

    data class NeedsSelection(val devices: List<CaptureDevice>) : AiCaptureDeviceChoice

    data class Unavailable(val serial: String) : AiCaptureDeviceChoice

    data class LiveCaptureDeviceConflict(val liveSerial: String) : AiCaptureDeviceChoice

    data object NoReadyDevices : AiCaptureDeviceChoice
}

internal fun resolveAiCaptureDevice(
    readyDevices: List<CaptureDevice>,
    liveCaptureSerial: String?,
    requestedSerial: String?,
    newCapture: Boolean,
): AiCaptureDeviceChoice {
    val requested = requestedSerial?.trim()?.takeIf(String::isNotBlank)
    if (!newCapture && liveCaptureSerial != null && requested != null && requested != liveCaptureSerial) {
        return AiCaptureDeviceChoice.LiveCaptureDeviceConflict(liveCaptureSerial)
    }
    val serial = requested ?: liveCaptureSerial.takeIf { !newCapture }
    if (serial != null) {
        return readyDevices.firstOrNull { it.serial == serial }
            ?.let(AiCaptureDeviceChoice::Selected)
            ?: AiCaptureDeviceChoice.Unavailable(serial)
    }
    return when (readyDevices.size) {
        0 -> AiCaptureDeviceChoice.NoReadyDevices
        1 -> AiCaptureDeviceChoice.Selected(readyDevices.single())
        else -> AiCaptureDeviceChoice.NeedsSelection(readyDevices)
    }
}

/** Tracks unfinished marker writes so an immediately-following snapshot includes durable evidence. */
internal class DeviceAiMarkerBarrier {
    private val pendingByTab = ConcurrentHashMap<String, ConcurrentHashMap<String, CompletableDeferred<Boolean>>>()

    fun register(tabId: String, operationId: String) {
        pendingByTab.computeIfAbsent(tabId) { ConcurrentHashMap() }[operationId] = CompletableDeferred()
    }

    fun complete(tabId: String, operationId: String, succeeded: Boolean) {
        pendingByTab[tabId]?.get(operationId)?.complete(succeeded)
    }

    suspend fun awaitAndConsume(tabId: String): Boolean {
        val pending = pendingByTab[tabId]?.entries?.toList().orEmpty()
        val succeeded = pending.map { (_, done) -> done.await() }.all { it }
        if (pending.isNotEmpty()) {
            pendingByTab[tabId]?.let { current ->
                pending.forEach { (id, done) -> current.remove(id, done) }
                if (current.isEmpty()) pendingByTab.remove(tabId, current)
            }
        }
        return succeeded
    }
}

/** Maps `export_capture_snapshot`'s `range`/`minutes` tool arguments onto [CaptureRange], the same
 *  5/10-minute presets (else a custom window) the Capture snapshot popover's own radio group uses.
 *  Pure and pinned to the tool's exact contract so it's directly testable without an [AppState]. */
internal fun resolveCaptureSnapshotRangeForAi(rangeParam: String?, minutes: Int?): CaptureRange =
    when (rangeParam?.trim()?.lowercase()) {
        null, "", "all" -> CaptureRange.ALL
        "last_minutes" -> when (minutes ?: 5) {
            5 -> CaptureRange.LAST_FIVE
            10 -> CaptureRange.LAST_TEN
            else -> CaptureRange.CUSTOM
        }
        "since_last_save" -> CaptureRange.SINCE_SAVE
        "selection" -> CaptureRange.SELECTION
        else -> error("range must be one of all, last_minutes, since_last_save, selection")
    }

// Generous upper bound on how many "-N" suffixes uniqueSnapshotDestination will try before giving
// up; a real directory hitting this would already have a much more pressing problem.
private const val MAX_SNAPSHOT_DESTINATION_ATTEMPTS = 10_000

/** Selects a collision-free archive filename without replacing existing evidence. */
internal fun uniqueSnapshotDestination(directory: File, filename: String): File {
    val original = File(directory, filename)
    if (!original.exists()) return original
    val stem = original.nameWithoutExtension
    val extension = original.extension.takeIf(String::isNotBlank)?.let { ".$it" }.orEmpty()
    for (number in 2..MAX_SNAPSHOT_DESTINATION_ATTEMPTS) {
        val candidate = File(directory, "$stem-$number$extension")
        if (!candidate.exists()) return candidate
    }
    error("Could not choose a non-overwriting snapshot file name")
}

/**
 * Builds the one [CaptureExportRequest] a live snapshot export actually runs — shared by the
 * Capture snapshot popover (CaptureStrip.kt's `CaptureSnapshotPopover`) and the AI/MCP
 * `export_capture_snapshot` tool (AppState.exportCaptureSnapshotForAi), so a change to how a
 * selection range resolves can't silently drift between the two callers. [cutoffElapsedMs] is
 * passed in rather than read off [tab] or a session, since the popover uses the currently
 * published recorder elapsed time while the AI tool uses the more precise, already-flushed
 * boundary from [com.indagium.ui.TabCaptureController.snapshotForExport].
 */
internal fun buildCaptureSnapshotExportRequest(
    tab: LogTab,
    destination: File,
    range: CaptureRange,
    customMinutes: Int,
    includeVideo: Boolean,
    cutoffElapsedMs: Long,
    overwriteExisting: Boolean,
): CaptureExportRequest {
    val selection = selectedCaptureOrdinals(tab)
    return CaptureExportRequest(
        destination = destination,
        range = range,
        customMinutes = customMinutes,
        includeVideo = includeVideo,
        cutoffElapsedMs = cutoffElapsedMs,
        selectedFirstRowOrdinal = selection?.first,
        selectedLastRowOrdinal = selection?.last,
        overwriteExisting = overwriteExisting,
    )
}
