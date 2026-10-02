package com.indagium.ui

import com.indagium.capture.CaptureDevice
import com.indagium.capture.isWirelessSerial
import com.indagium.model.AppSettings
import com.indagium.model.CaptureMirrorLayout
import com.indagium.model.DEFAULT_CAPTURE_MIRROR_SPLIT
import com.indagium.model.MAX_CAPTURE_MIRROR_LAYOUTS
import com.indagium.model.MAX_CAPTURE_MIRROR_SPLIT
import com.indagium.model.MIN_CAPTURE_MIRROR_SPLIT
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.floatOrNull
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put

// Remembered capture mirror layout (AppSettings.captureMirrorLayouts / lastCaptureMirrorLayout):
// the pure key/update rules and the settings-JSON codec. The codec lives here, not in
// AutosaveCodec.kt, which is already at detekt's per-file function limit.

/**
 * The key a device's layout is stored under. A wireless serial (`host:port`, mDNS name) changes
 * between connections, so those devices are keyed by model instead; everything else by serial.
 */
internal fun captureMirrorDeviceKey(device: CaptureDevice): String =
    if (isWirelessSerial(device.serial)) device.model.ifBlank { device.serial } else device.serial

/** The layout to use for [deviceKey]: its own entry, else the last one used anywhere, else the default. */
internal fun AppSettings.captureMirrorLayoutFor(deviceKey: String?): CaptureMirrorLayout =
    deviceKey?.let { captureMirrorLayouts[it] } ?: lastCaptureMirrorLayout ?: CaptureMirrorLayout()

/**
 * Applies [update] on top of [deviceKey]'s current layout and stores the result both for that device
 * (most recently used last, the map capped at [MAX_CAPTURE_MIRROR_LAYOUTS]) and as the last layout.
 * A null [deviceKey] (device unknown) only updates the last layout.
 */
internal fun AppSettings.withCaptureMirrorLayout(
    deviceKey: String?,
    update: (CaptureMirrorLayout) -> CaptureMirrorLayout,
): AppSettings {
    val next = update(captureMirrorLayoutFor(deviceKey)).let {
        it.copy(videoSplit = it.videoSplit.coerceIn(MIN_CAPTURE_MIRROR_SPLIT, MAX_CAPTURE_MIRROR_SPLIT))
    }
    if (deviceKey == null) return copy(lastCaptureMirrorLayout = next)
    val layouts = (captureMirrorLayouts - deviceKey + (deviceKey to next)).entries
        .toList().takeLast(MAX_CAPTURE_MIRROR_LAYOUTS).associate { it.key to it.value }
    return copy(captureMirrorLayouts = layouts, lastCaptureMirrorLayout = next)
}

// Detached-window sizes outside this range are corrupt or from a vanished monitor setup; drop them
// (the window then opens at its default size) rather than restoring something unusable.
private const val MIN_DETACHED_MIRROR_DIMENSION = 200f
private const val MAX_DETACHED_MIRROR_DIMENSION = 8000f

internal fun captureMirrorLayoutJson(layout: CaptureMirrorLayout) = buildJsonObject {
    put("videoSplit", layout.videoSplit)
    layout.detachedWidth?.let { put("detachedWidth", it) }
    layout.detachedHeight?.let { put("detachedHeight", it) }
}

internal fun captureMirrorLayoutsJson(layouts: Map<String, CaptureMirrorLayout>) = buildJsonObject {
    layouts.forEach { (device, layout) -> put(device, captureMirrorLayoutJson(layout)) }
}

internal fun JsonObject.captureMirrorLayoutFromJson(): CaptureMirrorLayout {
    fun dimension(key: String): Float? = this[key]?.jsonPrimitive?.floatOrNull
        ?.takeIf { it in MIN_DETACHED_MIRROR_DIMENSION..MAX_DETACHED_MIRROR_DIMENSION }
    val split = this["videoSplit"]?.jsonPrimitive?.floatOrNull?.takeIf { it.isFinite() }
        ?: DEFAULT_CAPTURE_MIRROR_SPLIT
    return CaptureMirrorLayout(
        videoSplit = split.coerceIn(MIN_CAPTURE_MIRROR_SPLIT, MAX_CAPTURE_MIRROR_SPLIT),
        detachedWidth = dimension("detachedWidth"),
        detachedHeight = dimension("detachedHeight"),
    )
}

internal fun JsonObject.captureMirrorLayoutsFromJson(key: String): Map<String, CaptureMirrorLayout> =
    (this[key] as? JsonObject)?.entries
        ?.mapNotNull { (device, value) -> (value as? JsonObject)?.let { device to it.captureMirrorLayoutFromJson() } }
        ?.takeLast(MAX_CAPTURE_MIRROR_LAYOUTS)
        ?.toMap() ?: emptyMap()
