package com.indagium.ui

import com.indagium.model.AppSettings
import com.indagium.model.ChromeEdge
import com.indagium.model.TabLabelMode
import com.indagium.model.TabSizeMode
import com.indagium.model.TabStripPosition
import com.indagium.model.TabStripStyle
import com.indagium.model.ToolbarPlacement
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonObjectBuilder
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.put

// The window chrome's settings (AppSettings.tabStripStyle, tabSizeMode, tabLabelMode, tabShowTypeBadge, tabShowSourceStripe,
// tabCloseOnEvery, chromeLocked, tabStripPosition, toolbarPlacement, toolbarEdge), JSON only. They live here, not in
// AutosaveCodec.kt, which is already at detekt's per-file function limit. Enums are stored by name. The decoder never throws:
// an absent, unknown or wrongly typed value leaves the field at its default (a fresh AppSettings), so an old autosave, a legacy
// positional token or a hand-edited file all give the long-standing tab strip.

/** Appends the chrome keys to the settings object being built (see AutosaveCodec.settingsJson). */
internal fun JsonObjectBuilder.putChromeSettings(settings: AppSettings) {
    put("tabStripStyle", settings.tabStripStyle.name)
    put("tabSizeMode", settings.tabSizeMode.name)
    put("tabLabelMode", settings.tabLabelMode.name)
    put("tabShowTypeBadge", settings.tabShowTypeBadge)
    put("tabShowSourceStripe", settings.tabShowSourceStripe)
    put("tabCloseOnEvery", settings.tabCloseOnEvery)
    put("chromeLocked", settings.chromeLocked)
    put("tabStripPosition", settings.tabStripPosition.name)
    put("toolbarPlacement", settings.toolbarPlacement.name)
    put("toolbarEdge", settings.toolbarEdge.name)
}

private inline fun <reified E : Enum<E>> JsonElement?.enumOrDefault(default: E): E {
    val name = (this as? JsonPrimitive)?.takeIf { it.isString }?.contentOrNull
    return enumValues<E>().firstOrNull { it.name == name } ?: default
}

private fun JsonElement?.boolOrDefault(default: Boolean): Boolean = (this as? JsonPrimitive)?.takeIf { !it.isString }?.booleanOrNull ?: default

/**
 * [this] with the chrome fields read from [o] (the parsed settings object); absent or malformed keys leave this value's own
 * (default) fields. Called from AutosaveCodec.settingsFromJson.
 */
internal fun AppSettings.withChromeSettingsFrom(o: JsonObject): AppSettings = copy(
    tabStripStyle = o["tabStripStyle"].enumOrDefault<TabStripStyle>(tabStripStyle),
    tabSizeMode = o["tabSizeMode"].enumOrDefault<TabSizeMode>(tabSizeMode),
    tabLabelMode = o["tabLabelMode"].enumOrDefault<TabLabelMode>(tabLabelMode),
    tabShowTypeBadge = o["tabShowTypeBadge"].boolOrDefault(tabShowTypeBadge),
    tabShowSourceStripe = o["tabShowSourceStripe"].boolOrDefault(tabShowSourceStripe),
    tabCloseOnEvery = o["tabCloseOnEvery"].boolOrDefault(tabCloseOnEvery),
    chromeLocked = o["chromeLocked"].boolOrDefault(chromeLocked),
    tabStripPosition = o["tabStripPosition"].enumOrDefault<TabStripPosition>(tabStripPosition),
    toolbarPlacement = o["toolbarPlacement"].enumOrDefault<ToolbarPlacement>(toolbarPlacement),
    toolbarEdge = o["toolbarEdge"].enumOrDefault<ChromeEdge>(toolbarEdge),
)
