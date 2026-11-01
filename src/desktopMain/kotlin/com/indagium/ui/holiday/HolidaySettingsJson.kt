package com.indagium.ui.holiday

import com.indagium.model.AppSettings
import com.indagium.model.HolidayIntensity
import com.indagium.model.HolidayLightness
import com.indagium.model.HolidayMode
import com.indagium.model.HolidayPack
import com.indagium.model.HolidaySettings
import com.indagium.ui.*
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonObjectBuilder
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.put

// The seasonal-theme choices (AppSettings.holiday), JSON only: one nested "holiday" object. They live here, not in
// AutosaveCodec.kt, which is already at detekt's per-file function limit. Enums are stored by name. The decoder never throws:
// an absent, unknown or wrongly typed value leaves the field at its default (Off), so an old autosave, a legacy positional token
// or a hand-edited file all give the long-standing look.

/** Appends the "holiday" object to the settings object being built (see AutosaveCodec.settingsJson). */
internal fun JsonObjectBuilder.putHolidaySettings(settings: AppSettings) {
    val h = settings.holiday
    put(
        "holiday",
        buildJsonObject {
            put("mode", h.mode.name)
            put("pack", h.pack.name)
            put("lightness", h.lightness.name)
            put("intensity", h.intensity.name)
            put("funIcons", h.funIcons)
            put("pauseWhileBusy", h.pauseWhileBusy)
            put("effects", buildJsonObject { h.effectToggles.toSortedMap().forEach { (id, on) -> put(id, on) } })
            put("tabScene", h.tabScene)
            put("filterPicture", h.filterPicture)
            put("animatePicture", h.animatePicture)
            put("notesCompanion", h.notesCompanion)
        },
    )
}

private inline fun <reified E : Enum<E>> JsonElement?.holidayEnumOrDefault(default: E): E {
    val name = (this as? JsonPrimitive)?.takeIf { it.isString }?.contentOrNull
    return enumValues<E>().firstOrNull { it.name == name } ?: default
}

private fun JsonElement?.holidayBoolOrDefault(default: Boolean): Boolean =
    (this as? JsonPrimitive)?.takeIf { !it.isString }?.booleanOrNull ?: default

// The per-effect choices: only boolean entries are kept; anything else is skipped, and an absent object leaves the default.
private fun JsonObject?.holidayToggles(default: Map<String, Boolean>): Map<String, Boolean> {
    if (this == null) return default
    return entries.mapNotNull { (id, v) -> (v as? JsonPrimitive)?.takeIf { !it.isString }?.booleanOrNull?.let { id to it } }.toMap()
}

/**
 * [this] with the holiday settings read from [o] (the parsed settings object); an absent or malformed "holiday" object, or key
 * inside it, leaves this value's own (default) field. Called from AutosaveCodec.settingsFromJson.
 */
internal fun AppSettings.withHolidaySettingsFrom(o: JsonObject): AppSettings {
    val h = o["holiday"] as? JsonObject ?: return this
    val d = holiday
    return copy(
        holiday = HolidaySettings(
            mode = h["mode"].holidayEnumOrDefault<HolidayMode>(d.mode),
            pack = h["pack"].holidayEnumOrDefault<HolidayPack>(d.pack),
            lightness = h["lightness"].holidayEnumOrDefault<HolidayLightness>(d.lightness),
            intensity = h["intensity"].holidayEnumOrDefault<HolidayIntensity>(d.intensity),
            funIcons = h["funIcons"].holidayBoolOrDefault(d.funIcons),
            pauseWhileBusy = h["pauseWhileBusy"].holidayBoolOrDefault(d.pauseWhileBusy),
            effectToggles = (h["effects"] as? JsonObject).holidayToggles(d.effectToggles),
            tabScene = h["tabScene"].holidayBoolOrDefault(d.tabScene),
            filterPicture = h["filterPicture"].holidayBoolOrDefault(d.filterPicture),
            animatePicture = h["animatePicture"].holidayBoolOrDefault(d.animatePicture),
            notesCompanion = h["notesCompanion"].holidayBoolOrDefault(d.notesCompanion),
        ),
    )
}
