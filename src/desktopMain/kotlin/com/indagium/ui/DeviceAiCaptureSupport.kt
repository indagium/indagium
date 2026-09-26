package com.indagium.ui

import com.indagium.capture.CaptureDevice
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

/** Selects a collision-free archive filename without replacing existing evidence. */
internal fun uniqueSnapshotDestination(directory: File, filename: String): File {
    val original = File(directory, filename)
    if (!original.exists()) return original
    val stem = original.nameWithoutExtension
    val extension = original.extension.takeIf(String::isNotBlank)?.let { ".$it" }.orEmpty()
    for (number in 2..10_000) {
        val candidate = File(directory, "$stem-$number$extension")
        if (!candidate.exists()) return candidate
    }
    error("Could not choose a non-overwriting snapshot file name")
}
