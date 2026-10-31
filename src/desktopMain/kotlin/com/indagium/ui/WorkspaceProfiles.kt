package com.indagium.ui

import com.indagium.model.AppSettings
import com.indagium.model.CustomLogViewLayout
import com.indagium.model.LogViewPresetId
import com.indagium.model.LogViewSectionId
import com.indagium.model.ProfileLayoutRef
import com.indagium.model.ProfileSpec
import com.indagium.model.ThemePreset

// Built-in workspace profiles (Settings → General): one click sets the theme and log typography and
// applies the log-view layout the profile points at. A profile owns the look and the defaults; the
// panels belong to the layout it references (a preset or a saved layout), so toggling a panel never
// makes a profile "Customized". Every value is still individually editable afterwards; the
// "Customized" strip in the General section is driven by [profileDifferences].

internal enum class WorkspaceProfile(
    val id: String,
    val title: String,
    val description: String,
    val spec: ProfileSpec,
) {
    CLASSIC(
        "classic", "Indagium Classic", "Filter sidebar, notes and video panels",
        ProfileSpec(
            theme = ThemePreset.WARM_PAPER, fontSize = 12, showMinimap = true,
            toolbarIconOnlyButtons = true, openNewFilesWithUnfiltered = false,
            layout = ProfileLayoutRef.Preset(LogViewPresetId.CLASSIC),
        ),
    ),
    FOCUSED(
        "focused", "Focused", "Filter bar above the log, notes only",
        ProfileSpec(
            theme = ThemePreset.WARM_PAPER, fontSize = 12, showMinimap = true,
            toolbarIconOnlyButtons = true, openNewFilesWithUnfiltered = false,
            layout = ProfileLayoutRef.Preset(LogViewPresetId.FILTER_BAR),
        ),
    ),
    COMPARE(
        "compare", "Compare", "Original above filtered for every new file",
        ProfileSpec(
            theme = ThemePreset.WARM_PAPER, fontSize = 12, showMinimap = true,
            toolbarIconOnlyButtons = true, openNewFilesWithUnfiltered = true,
            layout = ProfileLayoutRef.Preset(LogViewPresetId.LOG_FOCUS),
        ),
    ),
    MINIMAL(
        "minimal", "Minimal", "Just the log on a dark theme",
        ProfileSpec(
            theme = ThemePreset.GRAPHITE_DIM, fontSize = 11, showMinimap = false,
            toolbarIconOnlyButtons = true, openNewFilesWithUnfiltered = false,
            layout = ProfileLayoutRef.Preset(LogViewPresetId.LOG_FOCUS),
        ),
    ),
    LOGCAT_QUERY(
        "logcat_query", "Logcat query", "Filter bar above the log on a dark theme",
        ProfileSpec(
            theme = ThemePreset.DARK_GITHUB, fontSize = 12, showMinimap = true,
            toolbarIconOnlyButtons = true, openNewFilesWithUnfiltered = false,
            layout = ProfileLayoutRef.Preset(LogViewPresetId.FILTER_BAR),
        ),
    ),
    ;

    companion object {
        /** Null for a null or unknown id (e.g. a profile removed in a later version). */
        fun fromId(id: String?): WorkspaceProfile? = entries.firstOrNull { it.id == id }
    }
}

/** Human labels of every look/default value in [spec] that the live [settings] no longer match (the layout is not one of them). */
internal fun profileDifferences(spec: ProfileSpec, settings: AppSettings): List<String> =
    buildList {
        if (settings.theme != spec.theme) add("Theme")
        if (settings.fontSize != spec.fontSize) add("Log font size")
        if (settings.fontMono != spec.fontMono || settings.logFontFamily != spec.logFontFamily) add("Log font")
        if (settings.interfaceFontFamily != spec.interfaceFontFamily) add("Interface font")
        if (settings.showMinimap != spec.showMinimap) add("Minimap")
        if (settings.toolbarIconOnlyButtons != spec.toolbarIconOnlyButtons) add("Toolbar labels")
        if (settings.openNewFilesWithUnfiltered != spec.openNewFilesWithUnfiltered) add("Original panel for new files")
    }

/** Which side panels a profile's wireframe draws. */
internal data class ProfilePanels(
    val filter: Boolean = false,
    val filterBar: Boolean = false,
    val notes: Boolean = false,
    val video: Boolean = false,
)

/**
 * The panels [ref] shows, for the card wireframe and the Layout row's thumbnail: a preset shows what it does not hide, a saved layout
 * what its own flags and hidden list allow. Null for no layout, or a saved one that is gone (the caller shows what is on screen).
 */
internal fun profilePanelsOf(ref: ProfileLayoutRef?, saved: List<CustomLogViewLayout>): ProfilePanels? = when (ref) {
    null -> null
    is ProfileLayoutRef.Preset -> LogViewLayoutPreset.of(ref.id).let { preset ->
        preset.panels?.let { ProfilePanels(filter = it.filter, filterBar = it.filterBar, notes = it.notes, video = it.video) }
            ?: ProfilePanels(
                filter = !LOG_VIEW_FILTER_TILES.all { it in preset.hidden },
                filterBar = false,
                notes = LogViewSectionId.NOTES !in preset.hidden,
                video = LogViewSectionId.VIDEO !in preset.hidden,
            )
    }
    is ProfileLayoutRef.Saved -> saved.firstOrNull { it.id == ref.id }?.let { layout ->
        ProfilePanels(
            filter = layout.filterVisible && !LOG_VIEW_FILTER_TILES.all { it in layout.hidden },
            filterBar = layout.filterBarVisible == true,
            notes = layout.annotationVisible && LogViewSectionId.NOTES !in layout.hidden,
            video = layout.videoPanelVisible && LogViewSectionId.VIDEO !in layout.hidden,
        )
    }
}
