package com.indagium.ui

import com.indagium.model.AppSettings
import com.indagium.model.CustomLogViewLayout
import com.indagium.model.CustomWorkspaceProfile
import com.indagium.model.LogViewPresetId
import com.indagium.model.LogViewScope
import com.indagium.model.ProfileLayoutRef
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
// back to the Indagium Classic value (no layout for a missing one) rather than failing — and strict only about the file envelope.

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

internal fun WorkspaceProfile.resolved(savedLayouts: List<CustomLogViewLayout> = emptyList()) =
    ResolvedProfile(id, title, describeProfileSpec(spec, savedLayouts), spec, custom = false)

internal fun CustomWorkspaceProfile.resolved(savedLayouts: List<CustomLogViewLayout> = emptyList()) =
    ResolvedProfile(id, name, describeProfileSpec(spec, savedLayouts), spec, custom = true)

/** The label of "no layout": the profile leaves the view as it is. */
internal const val KEEP_CURRENT_LAYOUT_LABEL = "Keep current layout"

/** The name of the layout [ref] points at; a saved layout that no longer exists behaves (and reads) like no layout at all. */
internal fun profileLayoutName(ref: ProfileLayoutRef?, savedLayouts: List<CustomLogViewLayout>): String = when (ref) {
    null -> KEEP_CURRENT_LAYOUT_LABEL
    is ProfileLayoutRef.Preset -> LogViewLayoutPreset.of(ref.id).label
    is ProfileLayoutRef.Saved -> savedLayouts.firstOrNull { it.id == ref.id }?.name ?: KEEP_CURRENT_LAYOUT_LABEL
}

/** Card subtitle, e.g. "Dark (GitHub) · 12px · Layout: Filter bar". */
internal fun describeProfileSpec(spec: ProfileSpec, savedLayouts: List<CustomLogViewLayout> = emptyList()): String =
    "${spec.theme.label} · ${spec.fontSize}px · Layout: ${profileLayoutName(spec.layout, savedLayouts)}"

/** The spec that captures the values a profile would apply right now; [layout] is the active preset or saved layout, else null. */
internal fun currentProfileSpec(settings: AppSettings, layout: ProfileLayoutRef?) = ProfileSpec(
    theme = settings.theme,
    fontSize = settings.fontSize,
    fontMono = settings.fontMono,
    interfaceFontFamily = settings.interfaceFontFamily,
    logFontFamily = settings.logFontFamily,
    showMinimap = settings.showMinimap,
    toolbarIconOnlyButtons = settings.toolbarIconOnlyButtons,
    openNewFilesWithUnfiltered = settings.openNewFilesWithUnfiltered,
    layout = layout,
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
    // The five panel flags older builds wrote are gone: the layout owns the panels. Older builds fall back to their Classic
    // values for the flags they no longer find, so a file written here still opens there.
    spec.layout?.let { put("layout", profileLayoutRefToString(it)) }
}

private const val LAYOUT_PRESET_PREFIX = "preset:"
private const val LAYOUT_SAVED_PREFIX = "saved:"

internal fun profileLayoutRefToString(ref: ProfileLayoutRef): String = when (ref) {
    is ProfileLayoutRef.Preset -> LAYOUT_PRESET_PREFIX + ref.id.name
    is ProfileLayoutRef.Saved -> LAYOUT_SAVED_PREFIX + ref.id
}

/** Null for a missing, malformed or unknown reference (a preset from a newer build, a compare-only preset, a blank saved id). */
internal fun profileLayoutRefFromString(text: String?): ProfileLayoutRef? = when {
    text == null -> null
    text.startsWith(LAYOUT_PRESET_PREFIX) ->
        LogViewPresetId.entries.firstOrNull { it.name == text.removePrefix(LAYOUT_PRESET_PREFIX) }
            ?.takeIf { LogViewLayoutPreset.of(it).scope == LogViewScope.SINGLE }
            ?.let(ProfileLayoutRef::Preset)
    text.startsWith(LAYOUT_SAVED_PREFIX) ->
        text.removePrefix(LAYOUT_SAVED_PREFIX).takeIf { it.isNotBlank() }?.let(ProfileLayoutRef::Saved)
    else -> null
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
        // Old files carried five panel flags here; they are ignored (the layout owns the panels), so such a profile keeps the current layout.
        layout = profileLayoutRefFromString((o["layout"] as? JsonPrimitive)?.contentOrNull),
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
