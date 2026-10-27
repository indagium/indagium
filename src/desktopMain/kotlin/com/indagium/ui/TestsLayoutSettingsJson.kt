package com.indagium.ui

import com.indagium.model.AppSettings
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonObjectBuilder
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.put

// The Tests workspace's layout settings are JSON-only. AppSettings stores this compact JSON as a String so the model
// package does not depend on the UI-owned TestsWorkspaceTileId. The outer autosave settings JSON stores the value as a
// nested object. Selection, search, and maximized-tile state remain session-only.

/** Nesting deeper than this is dropped on decode, then the sanitizer restores any missing workspace tiles. */
internal const val TESTS_WORKSPACE_LAYOUT_MAX_DEPTH: Int = 8

/** Tree plus tile flags; transient selection state is intentionally not part of this value. */
internal fun testsWorkspaceLayoutJson(state: TestsWorkspaceLayoutState): JsonObject = kotlinx.serialization.json.buildJsonObject {
    put("layout", tileLayoutJson(state.layout) { it.key })
    put("hidden", idsJson(state.hidden))
    put("collapsed", idsJson(state.collapsed))
    put("locked", state.locked)
}

private fun idsJson(ids: List<TestsWorkspaceTileId>): JsonArray = buildJsonArray {
    ids.forEach { add(JsonPrimitive(it.key)) }
}

private fun JsonElement.testsWorkspaceLayoutNodeFromJson(depth: Int = 0): TestsWorkspaceLayout? =
    tileLayoutNodeFromJson(this, { key -> TestsWorkspaceTileId.entries.firstOrNull { it.key == key } }, depth, TESTS_WORKSPACE_LAYOUT_MAX_DEPTH)

private fun idsFromJson(element: JsonElement?): List<TestsWorkspaceTileId> =
    (element as? JsonArray)?.mapNotNull { value ->
        val key = (value as? JsonPrimitive)?.contentOrNull
        TestsWorkspaceTileId.entries.firstOrNull { it.key == key }
    }.orEmpty()

/** Decodes and sanitizes a persisted Tests workspace layout object; malformed fields use their fresh-workspace defaults. */
internal fun JsonObject.testsWorkspaceLayoutSettingsFromJson(): TestsWorkspaceLayoutState =
    sanitizeTestsWorkspaceLayoutState(
        layout = this["layout"]?.testsWorkspaceLayoutNodeFromJson(),
        hidden = idsFromJson(this["hidden"]),
        collapsed = idsFromJson(this["collapsed"]),
        locked = (this["locked"] as? JsonPrimitive)?.booleanOrNull ?: false,
    )

/** Returns null when no saved layout exists or the model's raw JSON is unreadable. */
internal fun AppSettings.testsWorkspaceLayoutFromSettings(): TestsWorkspaceLayoutState? = testsWorkspaceLayout?.let { raw ->
    runCatching { Json.parseToJsonElement(raw).let { (it as? JsonObject)?.testsWorkspaceLayoutSettingsFromJson() } }.getOrNull()
}

/** Stores a sanitized snapshot of the layout and its persistent tile flags in AppSettings. */
internal fun AppSettings.withTestsWorkspaceLayout(state: TestsWorkspaceLayoutState): AppSettings {
    val sanitized = sanitizeTestsWorkspaceLayoutState(state.layout, state.hidden, state.collapsed, state.locked)
    return copy(testsWorkspaceLayout = testsWorkspaceLayoutJson(sanitized).toString())
}

/** Writes a valid nested JSON object, omitting null or malformed model values. */
internal fun JsonObjectBuilder.putTestsWorkspaceLayoutSettings(settings: AppSettings) {
    settings.testsWorkspaceLayoutFromSettings()?.let { put("testsWorkspaceLayout", testsWorkspaceLayoutJson(it)) }
}

/** Reads the outer autosave setting and keeps a canonical, sanitized inner JSON snapshot in AppSettings. */
internal fun AppSettings.withTestsWorkspaceLayoutSettingsFrom(settings: JsonObject): AppSettings {
    val decoded = (settings["testsWorkspaceLayout"] as? JsonObject)?.testsWorkspaceLayoutSettingsFromJson()
    return if (decoded == null) copy(testsWorkspaceLayout = null) else withTestsWorkspaceLayout(decoded)
}
