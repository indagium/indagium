package com.indagium.ui

import com.indagium.model.AppSettings
import com.indagium.model.ProfileSpec
import com.indagium.model.ThemePreset

// Built-in workspace profiles (Settings → General): one click sets the theme, log typography and
// panel layout together. Every value is still individually editable afterwards; the
// "Customized" strip in the General section is driven by [profileDifferences].

/** The panel-visibility fields that live on AppState (not AppSettings) and that a profile sets. */
internal data class LayoutSnapshot(
    val filterVisible: Boolean,
    val filterBarVisible: Boolean,
    val annotationVisible: Boolean,
    val videoPanelVisible: Boolean,
    val aiPanelVisible: Boolean,
)

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
            filterVisible = true, filterBarVisible = false, annotationVisible = true,
            videoPanelVisible = true, aiPanelVisible = false,
        ),
    ),
    FOCUSED(
        "focused", "Focused", "Filter bar above the log, notes only",
        ProfileSpec(
            theme = ThemePreset.WARM_PAPER, fontSize = 12, showMinimap = true,
            toolbarIconOnlyButtons = true, openNewFilesWithUnfiltered = false,
            filterVisible = false, filterBarVisible = true, annotationVisible = true,
            videoPanelVisible = false, aiPanelVisible = false,
        ),
    ),
    COMPARE(
        "compare", "Compare", "Original above filtered for every new file",
        ProfileSpec(
            theme = ThemePreset.WARM_PAPER, fontSize = 12, showMinimap = true,
            toolbarIconOnlyButtons = true, openNewFilesWithUnfiltered = true,
            filterVisible = false, filterBarVisible = true, annotationVisible = false,
            videoPanelVisible = false, aiPanelVisible = false,
        ),
    ),
    MINIMAL(
        "minimal", "Minimal", "Just the log on a dark theme",
        ProfileSpec(
            theme = ThemePreset.GRAPHITE_DIM, fontSize = 11, showMinimap = false,
            toolbarIconOnlyButtons = true, openNewFilesWithUnfiltered = false,
            filterVisible = false, filterBarVisible = false, annotationVisible = false,
            videoPanelVisible = false, aiPanelVisible = false,
        ),
    ),
    LOGCAT_QUERY(
        "logcat_query", "Logcat query", "Filter bar above the log on a dark theme",
        ProfileSpec(
            theme = ThemePreset.DARK_GITHUB, fontSize = 12, showMinimap = true,
            toolbarIconOnlyButtons = true, openNewFilesWithUnfiltered = false,
            filterVisible = false, filterBarVisible = true, annotationVisible = false,
            videoPanelVisible = false, aiPanelVisible = false,
        ),
    ),
    ;

    companion object {
        /** Null for a null or unknown id (e.g. a profile removed in a later version). */
        fun fromId(id: String?): WorkspaceProfile? = entries.firstOrNull { it.id == id }
    }
}

/** Human labels of every value in [spec] that the live [settings]/[layout] no longer match. */
internal fun profileDifferences(spec: ProfileSpec, settings: AppSettings, layout: LayoutSnapshot): List<String> =
    buildList {
        if (settings.theme != spec.theme) add("Theme")
        if (settings.fontSize != spec.fontSize) add("Log font size")
        if (settings.fontMono != spec.fontMono || settings.logFontFamily != spec.logFontFamily) add("Log font")
        if (settings.interfaceFontFamily != spec.interfaceFontFamily) add("Interface font")
        if (settings.showMinimap != spec.showMinimap) add("Minimap")
        if (settings.toolbarIconOnlyButtons != spec.toolbarIconOnlyButtons) add("Toolbar labels")
        if (settings.openNewFilesWithUnfiltered != spec.openNewFilesWithUnfiltered) add("Original panel for new files")
        if (layout.filterVisible != spec.filterVisible) add("Filter sidebar")
        if (layout.filterBarVisible != spec.filterBarVisible) add("Filter bar")
        if (layout.annotationVisible != spec.annotationVisible) add("Notes panel")
        if (layout.videoPanelVisible != spec.videoPanelVisible) add("Video panel")
        if (layout.aiPanelVisible != spec.aiPanelVisible) add("AI panel")
    }
