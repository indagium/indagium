package com.indagium.ui

import com.indagium.model.AppSettings
import com.indagium.model.CustomLogViewLayout
import com.indagium.model.LogViewPresetId
import com.indagium.model.LogViewSectionId
import com.indagium.model.ProfileAppearance
import com.indagium.model.ProfileBehavior
import com.indagium.model.ProfileLayoutRef
import com.indagium.model.ProfileSpec
import com.indagium.model.ThemePreset

// Built-in workspace profiles (Settings → General): one click sets the theme and log typography and
// applies the log-view layout the profile points at. A profile owns the look and the defaults; the
// panels belong to the layout it references (a preset or a saved layout), so toggling a panel never
// makes a profile "Customized". A profile is the whole look and behaviour: applying one sets every field of both the Appearance and
// the Editor behavior groups (ProfileSpec.appearance / .behavior), and a missing group or field means the app DEFAULT (AppSettings()),
// never "keep what is on screen". The built-ins therefore carry both groups in full (the defaults); custom ones carry what they were
// saved with, and older ones without groups read as the defaults. Every value is still individually editable afterwards; the
// "Customized" strip in the General section is driven by [profileDifferences], which compares the same resolved values.

/** Both groups of a profile that changes nothing from the app defaults: what every built-in carries. */
internal val DEFAULT_PROFILE_APPEARANCE: ProfileAppearance = currentProfileAppearance(AppSettings())
internal val DEFAULT_PROFILE_BEHAVIOR: ProfileBehavior = currentProfileBehavior(AppSettings())

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
            appearance = DEFAULT_PROFILE_APPEARANCE, behavior = DEFAULT_PROFILE_BEHAVIOR,
        ),
    ),
    FOCUSED(
        "focused", "Focused", "Filter bar above the log, notes only",
        ProfileSpec(
            theme = ThemePreset.WARM_PAPER, fontSize = 12, showMinimap = true,
            toolbarIconOnlyButtons = true, openNewFilesWithUnfiltered = false,
            layout = ProfileLayoutRef.Preset(LogViewPresetId.FILTER_BAR),
            appearance = DEFAULT_PROFILE_APPEARANCE, behavior = DEFAULT_PROFILE_BEHAVIOR,
        ),
    ),
    COMPARE(
        "compare", "Compare", "Original above filtered for every new file",
        ProfileSpec(
            theme = ThemePreset.WARM_PAPER, fontSize = 12, showMinimap = true,
            toolbarIconOnlyButtons = true, openNewFilesWithUnfiltered = true,
            layout = ProfileLayoutRef.Preset(LogViewPresetId.LOG_FOCUS),
            appearance = DEFAULT_PROFILE_APPEARANCE, behavior = DEFAULT_PROFILE_BEHAVIOR,
        ),
    ),
    MINIMAL(
        "minimal", "Minimal", "Just the log on a dark theme",
        ProfileSpec(
            theme = ThemePreset.GRAPHITE_DIM, fontSize = 11, showMinimap = false,
            toolbarIconOnlyButtons = true, openNewFilesWithUnfiltered = false,
            layout = ProfileLayoutRef.Preset(LogViewPresetId.LOG_FOCUS),
            appearance = DEFAULT_PROFILE_APPEARANCE, behavior = DEFAULT_PROFILE_BEHAVIOR,
        ),
    ),
    LOGCAT_QUERY(
        "logcat_query", "Logcat query", "Filter bar above the log on a dark theme",
        ProfileSpec(
            theme = ThemePreset.DARK_GITHUB, fontSize = 12, showMinimap = true,
            toolbarIconOnlyButtons = true, openNewFilesWithUnfiltered = false,
            layout = ProfileLayoutRef.Preset(LogViewPresetId.FILTER_BAR),
            appearance = DEFAULT_PROFILE_APPEARANCE, behavior = DEFAULT_PROFILE_BEHAVIOR,
        ),
    ),
    ;

    companion object {
        /** Null for a null or unknown id (e.g. a profile removed in a later version). */
        fun fromId(id: String?): WorkspaceProfile? = entries.firstOrNull { it.id == id }
    }
}

/**
 * Human labels of every value in [spec] that the live [settings] no longer match: the always-applied look and defaults, plus every field
 * of the [ProfileSpec.appearance] and [ProfileSpec.behavior] groups, where a missing group or field is compared as the app default (the
 * value applying it would set). The layout, the chrome lock and the toolbar fallback placement are never among them.
 */
internal fun profileDifferences(spec: ProfileSpec, settings: AppSettings): List<String> =
    buildList {
        if (settings.theme != spec.theme) add("Theme")
        if (settings.fontSize != spec.fontSize) add("Log font size")
        if (settings.fontMono != spec.fontMono || settings.logFontFamily != spec.logFontFamily) add("Log font")
        if (settings.interfaceFontFamily != spec.interfaceFontFamily) add("Interface font")
        if (settings.showMinimap != spec.showMinimap) add("Minimap")
        if (settings.toolbarIconOnlyButtons != spec.toolbarIconOnlyButtons) add("Toolbar labels")
        if (settings.openNewFilesWithUnfiltered != spec.openNewFilesWithUnfiltered) add("Original panel for new files")
        addAll(appearanceDifferences(spec.appearance.orAppDefaults(), settings))
        addAll(behaviorDifferences(spec.behavior.orAppDefaults(), settings))
    }.distinct()

/** Adds [label] when the profile defines a value ([want] non-null) and it is not the live one ([have]). */
private fun <T : Any> MutableList<String>.differs(label: String, want: T?, have: T) {
    if (want != null && want != have) add(label)
}

private fun appearanceDifferences(a: ProfileAppearance, s: AppSettings): List<String> = buildList {
    differs("Interface scale", a.interfaceScalePercent, s.interfaceScalePercent)
    differs("Row numbers", a.showRowNumbers, s.showRowNumbers)
    differs("Tab style", a.tabStripStyle, s.tabStripStyle)
    differs("Tab size", a.tabSizeMode, s.tabSizeMode)
    differs("Tab label", a.tabLabelMode, s.tabLabelMode)
    differs("File-type badge", a.tabShowTypeBadge, s.tabShowTypeBadge)
    differs("Source colour stripe", a.tabShowSourceStripe, s.tabShowSourceStripe)
    differs("Close button on every tab", a.tabCloseOnEvery, s.tabCloseOnEvery)
    differs("Close button position", a.tabCloseButtonPosition, s.tabCloseButtonPosition)
    differs("Visible tabs", a.visibleTabLimit, s.visibleTabLimit)
    differs("Tab position", a.tabStripPosition, s.tabStripPosition)
    differs("Toolbar placement", a.toolbarPlacement, s.toolbarPlacement)
    differs("Toolbar edge", a.toolbarEdge, s.toolbarEdge)
    differs("Toolbar order on the tab edge", a.toolbarOuter, s.toolbarOuter)
    differs("Toolbar alignment", a.toolbarAlign, s.toolbarAlign)
    differs("Tab bar width", a.verticalTabBarWidthDp, s.verticalTabBarWidthDp)
}

private fun behaviorDifferences(b: ProfileBehavior, s: AppSettings): List<String> = buildList {
    differs("Ctrl+F opens", b.ctrlFTarget, s.ctrlFTarget)
    differs("Ctrl+F also shows Original", b.openUnfilteredOnCtrlF, s.openUnfilteredOnCtrlF)
    differs("Search scope behavior", b.searchScopeBehavior, s.searchScopeBehavior)
    differs("Link split selection and Find", b.syncSplitPanelSelectionAndScroll, s.syncSplitPanelSelectionAndScroll)
    differs("Row wrapping", b.autoLogRowWrap, s.autoLogRowWrap)
    differs("Row wrapping", b.logRowWrapLimitChars, s.logRowWrapLimitChars)
    differs("Follow live logs", b.autoScrollWhileTailing, s.autoScrollWhileTailing)
    differs("Keyboard scroll margin", b.navScrollMargin, s.navScrollMargin)
    differs("Filter list rows", b.filterListRows, s.filterListRows)
    differs("Most-used tags", b.mostUsedTagLimit, s.mostUsedTagLimit)
    differs("Process names in new tabs", b.showProcessNamesInNewTabs, s.showProcessNamesInNewTabs)
    differs("Regex summary", b.showRegexFilterSummary, s.showRegexFilterSummary)
    differs("Video follow readout", b.showVideoFollowReadout, s.showVideoFollowReadout)
    differs("New video links: double-click seeks", b.enableDoubleClickVideoSeekOnLink, s.enableDoubleClickVideoSeekOnLink)
    differs("Crash rows", b.highlightEntireCrashGroup, s.highlightEntireCrashGroup)
}

/** [this] with every null field replaced by the app default (a null group is all defaults): what applying or comparing it means. */
internal fun ProfileAppearance?.orAppDefaults(): ProfileAppearance {
    val d = DEFAULT_PROFILE_APPEARANCE
    val a = this ?: return d
    return ProfileAppearance(
        interfaceScalePercent = a.interfaceScalePercent ?: d.interfaceScalePercent,
        showRowNumbers = a.showRowNumbers ?: d.showRowNumbers,
        tabStripStyle = a.tabStripStyle ?: d.tabStripStyle,
        tabSizeMode = a.tabSizeMode ?: d.tabSizeMode,
        tabLabelMode = a.tabLabelMode ?: d.tabLabelMode,
        tabShowTypeBadge = a.tabShowTypeBadge ?: d.tabShowTypeBadge,
        tabShowSourceStripe = a.tabShowSourceStripe ?: d.tabShowSourceStripe,
        tabCloseOnEvery = a.tabCloseOnEvery ?: d.tabCloseOnEvery,
        tabCloseButtonPosition = a.tabCloseButtonPosition ?: d.tabCloseButtonPosition,
        visibleTabLimit = a.visibleTabLimit ?: d.visibleTabLimit,
        tabStripPosition = a.tabStripPosition ?: d.tabStripPosition,
        toolbarPlacement = a.toolbarPlacement ?: d.toolbarPlacement,
        toolbarEdge = a.toolbarEdge ?: d.toolbarEdge,
        toolbarOuter = a.toolbarOuter ?: d.toolbarOuter,
        toolbarAlign = a.toolbarAlign ?: d.toolbarAlign,
        verticalTabBarWidthDp = a.verticalTabBarWidthDp ?: d.verticalTabBarWidthDp,
    )
}

/** See [orAppDefaults] for [ProfileAppearance]. */
internal fun ProfileBehavior?.orAppDefaults(): ProfileBehavior {
    val d = DEFAULT_PROFILE_BEHAVIOR
    val b = this ?: return d
    return ProfileBehavior(
        ctrlFTarget = b.ctrlFTarget ?: d.ctrlFTarget,
        openUnfilteredOnCtrlF = b.openUnfilteredOnCtrlF ?: d.openUnfilteredOnCtrlF,
        searchScopeBehavior = b.searchScopeBehavior ?: d.searchScopeBehavior,
        syncSplitPanelSelectionAndScroll = b.syncSplitPanelSelectionAndScroll ?: d.syncSplitPanelSelectionAndScroll,
        autoLogRowWrap = b.autoLogRowWrap ?: d.autoLogRowWrap,
        logRowWrapLimitChars = b.logRowWrapLimitChars ?: d.logRowWrapLimitChars,
        autoScrollWhileTailing = b.autoScrollWhileTailing ?: d.autoScrollWhileTailing,
        navScrollMargin = b.navScrollMargin ?: d.navScrollMargin,
        filterListRows = b.filterListRows ?: d.filterListRows,
        mostUsedTagLimit = b.mostUsedTagLimit ?: d.mostUsedTagLimit,
        showProcessNamesInNewTabs = b.showProcessNamesInNewTabs ?: d.showProcessNamesInNewTabs,
        showRegexFilterSummary = b.showRegexFilterSummary ?: d.showRegexFilterSummary,
        showVideoFollowReadout = b.showVideoFollowReadout ?: d.showVideoFollowReadout,
        enableDoubleClickVideoSeekOnLink = b.enableDoubleClickVideoSeekOnLink ?: d.enableDoubleClickVideoSeekOnLink,
        highlightEntireCrashGroup = b.highlightEntireCrashGroup ?: d.highlightEntireCrashGroup,
    )
}

/**
 * [this] with everything [spec] defines applied (the layout is applied separately): the always-applied look and defaults, then every
 * field of both groups, a missing group or field being the app default (a profile is the whole look, never "keep the current value").
 * `chromeLocked` and `toolbarFallbackPlacement` are not part of a profile. The toolbar placement goes through [withToolbarPlacement]
 * last, so choosing "In the log view layout" records its fallback and puts the toolbar tile into / out of the stored layout exactly as
 * the Settings radio does.
 */
internal fun AppSettings.withProfileSpec(spec: ProfileSpec): AppSettings =
    copy(
        theme = spec.theme,
        fontSize = spec.fontSize,
        fontMono = spec.fontMono,
        interfaceFontFamily = spec.interfaceFontFamily,
        logFontFamily = spec.logFontFamily,
        showMinimap = spec.showMinimap,
        toolbarIconOnlyButtons = spec.toolbarIconOnlyButtons,
        openNewFilesWithUnfiltered = spec.openNewFilesWithUnfiltered,
    ).withProfileAppearance(spec.appearance.orAppDefaults())
        .withProfileBehavior(spec.behavior.orAppDefaults())

private fun AppSettings.withProfileAppearance(a: ProfileAppearance): AppSettings =
    copy(
        interfaceScalePercent = a.interfaceScalePercent ?: interfaceScalePercent,
        showRowNumbers = a.showRowNumbers ?: showRowNumbers,
        tabStripStyle = a.tabStripStyle ?: tabStripStyle,
        tabSizeMode = a.tabSizeMode ?: tabSizeMode,
        tabLabelMode = a.tabLabelMode ?: tabLabelMode,
        tabShowTypeBadge = a.tabShowTypeBadge ?: tabShowTypeBadge,
        tabShowSourceStripe = a.tabShowSourceStripe ?: tabShowSourceStripe,
        tabCloseOnEvery = a.tabCloseOnEvery ?: tabCloseOnEvery,
        tabCloseButtonPosition = a.tabCloseButtonPosition ?: tabCloseButtonPosition,
        visibleTabLimit = a.visibleTabLimit ?: visibleTabLimit,
        tabStripPosition = a.tabStripPosition ?: tabStripPosition,
        toolbarEdge = a.toolbarEdge ?: toolbarEdge,
        toolbarOuter = a.toolbarOuter ?: toolbarOuter,
        toolbarAlign = a.toolbarAlign ?: toolbarAlign,
        verticalTabBarWidthDp = a.verticalTabBarWidthDp ?: verticalTabBarWidthDp,
    ).let { next -> a.toolbarPlacement?.let(next::withToolbarPlacement) ?: next }

private fun AppSettings.withProfileBehavior(b: ProfileBehavior): AppSettings =
    copy(
        ctrlFTarget = b.ctrlFTarget ?: ctrlFTarget,
        openUnfilteredOnCtrlF = b.openUnfilteredOnCtrlF ?: openUnfilteredOnCtrlF,
        searchScopeBehavior = b.searchScopeBehavior ?: searchScopeBehavior,
        syncSplitPanelSelectionAndScroll = b.syncSplitPanelSelectionAndScroll ?: syncSplitPanelSelectionAndScroll,
        autoLogRowWrap = b.autoLogRowWrap ?: autoLogRowWrap,
        logRowWrapLimitChars = b.logRowWrapLimitChars ?: logRowWrapLimitChars,
        autoScrollWhileTailing = b.autoScrollWhileTailing ?: autoScrollWhileTailing,
        navScrollMargin = b.navScrollMargin ?: navScrollMargin,
        filterListRows = b.filterListRows ?: filterListRows,
        mostUsedTagLimit = b.mostUsedTagLimit ?: mostUsedTagLimit,
        showProcessNamesInNewTabs = b.showProcessNamesInNewTabs ?: showProcessNamesInNewTabs,
        showRegexFilterSummary = b.showRegexFilterSummary ?: showRegexFilterSummary,
        showVideoFollowReadout = b.showVideoFollowReadout ?: showVideoFollowReadout,
        enableDoubleClickVideoSeekOnLink = b.enableDoubleClickVideoSeekOnLink ?: enableDoubleClickVideoSeekOnLink,
        highlightEntireCrashGroup = b.highlightEntireCrashGroup ?: highlightEntireCrashGroup,
    )

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
