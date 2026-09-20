package com.indagium.capture

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.add
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put

private const val SETTINGS_FORMAT_VERSION = 1
private const val MIN_MAX_SIZE = 0
private const val MAX_MAX_SIZE = 8_192
private const val MIN_MAX_FPS = 1
private const val MAX_MAX_FPS = 240
private const val MIN_BITRATE_MBPS = 1
private const val MAX_BITRATE_MBPS = 500

/** Keyed, versioned persistence for capture preferences embedded in app settings. */
fun captureSettingsToJson(settings: CaptureSettings): String = buildJsonObject {
    put("formatVersion", SETTINGS_FORMAT_VERSION)
    put("adbPath", settings.adbPath)
    put("scrcpyPath", settings.scrcpyPath)
    put("buffers", buildJsonArray { settings.buffers.forEach { add(it) } })
    put("includeBufferedLogs", settings.includeBufferedLogs)
    put("recordVideo", settings.recordVideo)
    put("mirror", settings.mirror)
    put("audio", settings.audio)
    put("maxSize", settings.maxSize)
    put("maxFps", settings.maxFps)
    put("bitrateMbps", settings.bitrateMbps)
    put("sessionLimitBytes", settings.sessionLimitBytes)
    put("freeSpaceReserveBytes", settings.freeSpaceReserveBytes)
    put("filenameTemplate", settings.filenameTemplate)
    put("label", settings.label)
}.toString()

/** Returns null for malformed or unsupported settings instead of partially applying them. */
fun captureSettingsFromJson(raw: String): CaptureSettings? = runCatching {
    val root = Json.parseToJsonElement(raw).jsonObject
    val version = root.optional("formatVersion", SETTINGS_FORMAT_VERSION, ::intValue)
    require(version == SETTINGS_FORMAT_VERSION) { "Unsupported capture settings version: $version" }
    val defaults = CaptureSettings()
    CaptureSettings(
        adbPath = root.optional("adbPath", defaults.adbPath, ::stringValue),
        scrcpyPath = root.optional("scrcpyPath", defaults.scrcpyPath, ::stringValue),
        buffers = root.optional("buffers", defaults.buffers, ::stringListValue),
        includeBufferedLogs = root.optional("includeBufferedLogs", defaults.includeBufferedLogs, ::booleanValue),
        recordVideo = root.optional("recordVideo", defaults.recordVideo, ::booleanValue),
        mirror = root.optional("mirror", defaults.mirror, ::booleanValue),
        audio = root.optional("audio", defaults.audio, ::booleanValue),
        maxSize = root.optional("maxSize", defaults.maxSize, ::intValue).coerceIn(MIN_MAX_SIZE, MAX_MAX_SIZE),
        maxFps = root.optional("maxFps", defaults.maxFps, ::intValue).coerceIn(MIN_MAX_FPS, MAX_MAX_FPS),
        bitrateMbps = root.optional("bitrateMbps", defaults.bitrateMbps, ::intValue).coerceIn(MIN_BITRATE_MBPS, MAX_BITRATE_MBPS),
        sessionLimitBytes = root.optional("sessionLimitBytes", defaults.sessionLimitBytes, ::longValue).coerceAtLeast(0),
        freeSpaceReserveBytes = root.optional("freeSpaceReserveBytes", defaults.freeSpaceReserveBytes, ::longValue).coerceAtLeast(0),
        filenameTemplate = root.optional("filenameTemplate", defaults.filenameTemplate, ::stringValue),
        label = root.optional("label", defaults.label, ::stringValue),
    )
}.getOrNull()

private fun <T> JsonObject.optional(key: String, default: T, parser: (JsonElement) -> T?): T {
    val value = this[key] ?: return default
    return parser(value) ?: error("Capture setting $key is invalid")
}

private fun stringValue(value: JsonElement): String? =
    (value as? JsonPrimitive)?.takeIf { it.isString }?.contentOrNull

private fun intValue(value: JsonElement): Int? =
    (value as? JsonPrimitive)?.takeIf { !it.isString }?.intOrNull

private fun longValue(value: JsonElement): Long? =
    (value as? JsonPrimitive)?.takeIf { !it.isString }?.longOrNull

private fun booleanValue(value: JsonElement): Boolean? =
    (value as? JsonPrimitive)?.takeIf { !it.isString }?.booleanOrNull

private fun stringListValue(value: JsonElement): List<String>? =
    (value as? JsonArray)?.map { stringValue(it) ?: return null }

internal fun JsonObject.string(key: String): String? = stringValue(this[key] ?: return null)

internal fun JsonObject.int(key: String): Int? =
    this[key]?.let(::intValue)

internal fun JsonObject.long(key: String): Long? =
    this[key]?.let(::longValue)

internal fun JsonObject.boolean(key: String): Boolean? =
    this[key]?.let(::booleanValue)

internal fun JsonObject.stringList(key: String): List<String>? =
    this[key]?.let(::stringListValue)
