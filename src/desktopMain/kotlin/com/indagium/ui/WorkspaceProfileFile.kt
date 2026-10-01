package com.indagium.ui

import com.indagium.model.AppSettings
import com.indagium.model.CustomWorkspaceProfile
import com.indagium.model.ProfileSpec
import com.indagium.model.ThemePreset
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import java.util.UUID

// Saved, imported and exported workspace profiles: the JSON forms of a [ProfileSpec] (inside the
// settings JSON and in a standalone profile file), plus small pure helpers the General settings
// page and AppState share. Everything here is tolerant on read — a missing or unknown value falls
// back to the Indagium Classic value rather than failing — and strict only about the file envelope.

internal const val WORKSPACE_PROFILE_FILE_FORMAT = "indagium-workspace-profile"
internal const val WORKSPACE_PROFILE_FILE_VERSION = 1
private const val CUSTOM_PROFILE_ID_PREFIX = "custom-"
private const val MIN_PROFILE_FONT_SIZE = 10
private const val MAX_PROFILE_FONT_SIZE = 24

/** One profile as the UI sees it, built-in or custom. */
internal data class ResolvedProfile(
    val id: String,
    val title: String,
    val description: String,
    val spec: ProfileSpec,
    val custom: Boolean,
)

internal fun WorkspaceProfile.resolved() = ResolvedProfile(id, title, description, spec, custom = false)

internal fun CustomWorkspaceProfile.resolved() = ResolvedProfile(id, name, describeProfileSpec(spec), spec, custom = true)

/** Card subtitle for a custom profile, e.g. "Dark (GitHub) · filter bar · notes". */
internal fun describeProfileSpec(spec: ProfileSpec): String {
    val parts = buildList {
        add(spec.theme.label)
        if (spec.filterVisible) add("filter sidebar")
        if (spec.filterBarVisible) add("filter bar")
        if (spec.annotationVisible) add("notes")
        if (spec.videoPanelVisible) add("video")
        if (spec.aiPanelVisible) add("AI panel")
        if (spec.openNewFilesWithUnfiltered) add("original panel")
        if (size == 1) add("log only")
    }
    return parts.joinToString(" · ")
}

/** The spec that captures the values a profile would apply right now. */
internal fun currentProfileSpec(settings: AppSettings, layout: LayoutSnapshot) = ProfileSpec(
    theme = settings.theme,
    fontSize = settings.fontSize,
    fontMono = settings.fontMono,
    interfaceFontFamily = settings.interfaceFontFamily,
    logFontFamily = settings.logFontFamily,
    showMinimap = settings.showMinimap,
    toolbarIconOnlyButtons = settings.toolbarIconOnlyButtons,
    openNewFilesWithUnfiltered = settings.openNewFilesWithUnfiltered,
    filterVisible = layout.filterVisible,
    filterBarVisible = layout.filterBarVisible,
    annotationVisible = layout.annotationVisible,
    videoPanelVisible = layout.videoPanelVisible,
    aiPanelVisible = layout.aiPanelVisible,
)

/** [base] made unique against [taken] (case-insensitive): "Name", "Name (2)", "Name (3)"… */
internal fun uniqueProfileName(base: String, taken: Collection<String>): String {
    val cleaned = base.trim().ifBlank { "My profile" }
    val used = taken.map { it.trim().lowercase() }.toSet()
    if (cleaned.lowercase() !in used) return cleaned
    var n = 2
    while ("$cleaned ($n)".lowercase() in used) n++
    return "$cleaned ($n)"
}

internal fun newCustomProfileId(): String = CUSTOM_PROFILE_ID_PREFIX + UUID.randomUUID()

// ── JSON ─────────────────────────────────────────────────────────────

internal fun profileSpecToJson(spec: ProfileSpec): JsonObject = buildJsonObject {
    put("theme", spec.theme.name)
    put("fontSize", spec.fontSize)
    put("fontMono", spec.fontMono)
    spec.interfaceFontFamily?.let { put("interfaceFontFamily", it) }
    spec.logFontFamily?.let { put("logFontFamily", it) }
    put("showMinimap", spec.showMinimap)
    put("toolbarIconOnlyButtons", spec.toolbarIconOnlyButtons)
    put("openNewFilesWithUnfiltered", spec.openNewFilesWithUnfiltered)
    put("filterVisible", spec.filterVisible)
    put("filterBarVisible", spec.filterBarVisible)
    put("annotationVisible", spec.annotationVisible)
    put("videoPanelVisible", spec.videoPanelVisible)
    put("aiPanelVisible", spec.aiPanelVisible)
}

/** Never throws: a missing, mistyped or unknown value (e.g. a theme from a newer version) takes the Classic value. */
internal fun profileSpecFromJson(o: JsonObject): ProfileSpec {
    val base = WorkspaceProfile.CLASSIC.spec

    fun bool(key: String, default: Boolean) = (o[key] as? JsonPrimitive)?.booleanOrNull ?: default
    return ProfileSpec(
        theme = (o["theme"] as? JsonPrimitive)?.contentOrNull
            ?.let { name -> ThemePreset.entries.firstOrNull { it.name == name } } ?: base.theme,
        fontSize = ((o["fontSize"] as? JsonPrimitive)?.intOrNull ?: base.fontSize)
            .coerceIn(MIN_PROFILE_FONT_SIZE, MAX_PROFILE_FONT_SIZE),
        fontMono = bool("fontMono", base.fontMono),
        interfaceFontFamily = (o["interfaceFontFamily"] as? JsonPrimitive)?.contentOrNull?.takeIf { it.isNotBlank() },
        logFontFamily = (o["logFontFamily"] as? JsonPrimitive)?.contentOrNull?.takeIf { it.isNotBlank() },
        showMinimap = bool("showMinimap", base.showMinimap),
        toolbarIconOnlyButtons = bool("toolbarIconOnlyButtons", base.toolbarIconOnlyButtons),
        openNewFilesWithUnfiltered = bool("openNewFilesWithUnfiltered", base.openNewFilesWithUnfiltered),
        filterVisible = bool("filterVisible", base.filterVisible),
        filterBarVisible = bool("filterBarVisible", base.filterBarVisible),
        annotationVisible = bool("annotationVisible", base.annotationVisible),
        videoPanelVisible = bool("videoPanelVisible", base.videoPanelVisible),
        aiPanelVisible = bool("aiPanelVisible", base.aiPanelVisible),
    )
}

internal fun customProfilesToJson(profiles: List<CustomWorkspaceProfile>): JsonArray = buildJsonArray {
    profiles.forEach { p ->
        add(
            buildJsonObject {
                put("id", p.id)
                put("name", p.name)
                put("spec", profileSpecToJson(p.spec))
            },
        )
    }
}

/**
 * Tolerant read of the settings' profile list: entries that aren't objects or have a blank name are
 * skipped, and an id that is missing, repeated or could collide with a built-in id is replaced by a
 * fresh `custom-<uuid>` one.
 */
internal fun customProfilesFromJson(element: JsonElement?): List<CustomWorkspaceProfile> {
    val array = element as? JsonArray ?: return emptyList()
    val builtInIds = WorkspaceProfile.entries.map { it.id }.toSet()
    val seen = mutableSetOf<String>()
    return array.mapNotNull { item ->
        val o = item as? JsonObject ?: return@mapNotNull null
        val name = (o["name"] as? JsonPrimitive)?.contentOrNull?.trim().orEmpty()
        if (name.isEmpty()) return@mapNotNull null
        val stored = (o["id"] as? JsonPrimitive)?.contentOrNull
        val id = stored?.takeIf { it.startsWith(CUSTOM_PROFILE_ID_PREFIX) && it !in builtInIds && seen.add(it) }
            ?: newCustomProfileId().also { seen.add(it) }
        CustomWorkspaceProfile(id, name, profileSpecFromJson(o["spec"] as? JsonObject ?: JsonObject(emptyMap())))
    }
}

// ── Profile file ─────────────────────────────────────────────────────

internal data class WorkspaceProfileFile(val name: String, val spec: ProfileSpec)

private val profileFileJson = Json { prettyPrint = true }

internal fun encodeWorkspaceProfileFile(name: String, spec: ProfileSpec): String =
    profileFileJson.encodeToString(
        JsonObject.serializer(),
        buildJsonObject {
            put("format", WORKSPACE_PROFILE_FILE_FORMAT)
            put("version", WORKSPACE_PROFILE_FILE_VERSION)
            put("name", name)
            put("spec", profileSpecToJson(spec))
        },
    )

/** Failure carries a message that is safe to show as-is. */
internal fun decodeWorkspaceProfileFile(text: String): Result<WorkspaceProfileFile> = runCatching {
    val root = runCatching { Json.parseToJsonElement(text).jsonObject }
        .getOrElse { error("This file is not a workspace profile (it is not valid JSON).") }
    val format = (root["format"] as? JsonPrimitive)?.contentOrNull
    if (format != WORKSPACE_PROFILE_FILE_FORMAT) error("This file is not an Indagium workspace profile.")
    val version = (root["version"] as? JsonPrimitive)?.intOrNull ?: error("This profile file has no version number.")
    if (version > WORKSPACE_PROFILE_FILE_VERSION) {
        error("This profile was saved by a newer version of Indagium (file version $version). Update Indagium to import it.")
    }
    val name = (root["name"] as? JsonPrimitive)?.contentOrNull?.trim().orEmpty().ifBlank { "Imported profile" }
    val spec = profileSpecFromJson(root["spec"]?.let { it as? JsonObject } ?: error("This profile file has no settings in it."))
    WorkspaceProfileFile(name, spec)
}
