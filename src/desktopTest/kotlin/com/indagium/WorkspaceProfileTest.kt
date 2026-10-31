package com.indagium

import com.indagium.model.AppSettings
import com.indagium.model.ChromeEdge
import com.indagium.model.CtrlFTarget
import com.indagium.model.CustomWorkspaceProfile
import com.indagium.model.LogViewPresetId
import com.indagium.model.ProfileAppearance
import com.indagium.model.ProfileBehavior
import com.indagium.model.ProfileLayoutRef
import com.indagium.model.SearchScopeBehavior
import com.indagium.model.TabStripPosition
import com.indagium.model.TabStripStyle
import com.indagium.model.ThemePreset
import com.indagium.model.ToolbarAlign
import com.indagium.model.ToolbarPlacement
import com.indagium.ui.AppState
import com.indagium.ui.LogViewLayoutPreset
import com.indagium.ui.WorkspaceProfile
import com.indagium.ui.activeLogViewLayoutRef
import com.indagium.ui.applyLogViewPreset
import com.indagium.ui.logViewPresetActive
import com.indagium.ui.profileDifferences
import com.indagium.ui.resolved
import com.indagium.ui.settingsFromJson
import com.indagium.ui.settingsJson
import com.indagium.ui.withProfileSpec
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class WorkspaceProfileTest {
    @Test
    fun applyingEachProfileMatchesItsSpecWithNoDifferences() {
        for (profile in WorkspaceProfile.entries) {
            val state = AppState()
            state.applyWorkspaceProfile(profile)

            val spec = profile.spec
            assertEquals(profile.id, state.settings.workspaceProfileId, profile.id)
            assertEquals(spec.theme, state.settings.theme, profile.id)
            assertEquals(spec.fontSize, state.settings.fontSize, profile.id)
            assertEquals(spec.showMinimap, state.settings.showMinimap, profile.id)
            assertEquals(spec.toolbarIconOnlyButtons, state.settings.toolbarIconOnlyButtons, profile.id)
            assertEquals(spec.openNewFilesWithUnfiltered, state.settings.openNewFilesWithUnfiltered, profile.id)
            assertEquals(emptyList(), state.workspaceProfileDifferences(), profile.id)
        }
    }

    @Test
    fun builtInProfilesPointAtTheirPresets() {
        val expected = mapOf(
            WorkspaceProfile.CLASSIC to LogViewPresetId.CLASSIC,
            WorkspaceProfile.FOCUSED to LogViewPresetId.FILTER_BAR,
            WorkspaceProfile.COMPARE to LogViewPresetId.LOG_FOCUS,
            WorkspaceProfile.MINIMAL to LogViewPresetId.LOG_FOCUS,
            WorkspaceProfile.LOGCAT_QUERY to LogViewPresetId.FILTER_BAR,
        )
        assertEquals(WorkspaceProfile.entries.toSet(), expected.keys)
        expected.forEach { (profile, preset) -> assertEquals(ProfileLayoutRef.Preset(preset), profile.spec.layout, profile.id) }
    }

    @Test
    fun applyingABuiltInAppliesItsPresetAndShowsItAsActive() {
        for (profile in WorkspaceProfile.entries) {
            val state = AppState()
            // Start from a different arrangement so applying has something to change.
            state.applyLogViewPreset(LogViewLayoutPreset.ANALYSIS)
            state.applyWorkspaceProfile(profile)

            val preset = LogViewLayoutPreset.of((profile.spec.layout as ProfileLayoutRef.Preset).id)
            assertEquals(preset.id, state.settings.lastLogViewPreset, profile.id)
            assertTrue(state.logViewPresetActive(preset), profile.id)
            assertEquals(preset.showsFilterBar, state.filterBarVisible, profile.id)
            assertEquals(ProfileLayoutRef.Preset(preset.id), state.activeLogViewLayoutRef(), profile.id)
        }
    }

    @Test
    fun focusedProfileTurnsTheFilterBarAndNotesOnAndTheRestOff() {
        val state = AppState()
        state.updateVideoPanelVisible(true)
        state.updateAiPanelVisible(true)
        state.applyWorkspaceProfile(WorkspaceProfile.FOCUSED)

        assertTrue(state.filterBarVisible)
        assertTrue(state.annotationVisible, "Focused keeps Notes")
        assertFalse(state.filterVisible)
        assertFalse(state.videoPanelVisible)
        assertFalse(state.aiPanelVisible)
        assertTrue(state.logViewPresetActive(LogViewLayoutPreset.FILTER_BAR))
        assertFalse(state.logViewPresetActive(LogViewLayoutPreset.LOG_FOCUS), "Filter bar and Log focus share a tree; the flags tell them apart")
    }

    @Test
    fun classicProfileTurnsNotesAndVideoOnAndMinimalTurnsEverythingOff() {
        val state = AppState()
        state.applyWorkspaceProfile(WorkspaceProfile.MINIMAL)
        assertFalse(state.annotationVisible || state.videoPanelVisible || state.aiPanelVisible || state.filterVisible || state.filterBarVisible)

        state.updateAiPanelVisible(true)
        state.applyWorkspaceProfile(WorkspaceProfile.CLASSIC)
        assertTrue(state.filterVisible && state.annotationVisible && state.videoPanelVisible)
        assertFalse(state.filterBarVisible || state.aiPanelVisible)
    }

    @Test
    fun changingTheThemeAfterApplyingReportsOnlyTheme() {
        val state = AppState()
        state.applyWorkspaceProfile(WorkspaceProfile.FOCUSED)
        state.updateSettings { it.copy(theme = ThemePreset.DARK_GITHUB) }

        assertEquals(listOf("Theme"), state.workspaceProfileDifferences())
    }

    @Test
    fun togglingPanelsNeverMakesAProfileCustomizedAndReapplyingRestoresTheLayout() {
        val state = AppState()
        state.applyWorkspaceProfile(WorkspaceProfile.MINIMAL)
        state.updateAnnotationVisible(true)
        state.updateFilterBarVisible(true)
        state.updateFilterVisible(true)
        state.updateVideoPanelVisible(true)
        state.updateAiPanelVisible(true)

        assertEquals(emptyList(), state.workspaceProfileDifferences(), "panels belong to the layout, not the profile")

        state.applyWorkspaceProfile(WorkspaceProfile.MINIMAL)
        assertFalse(state.filterBarVisible)
        assertFalse(state.filterVisible)
        assertTrue(state.logViewPresetActive(LogViewLayoutPreset.LOG_FOCUS))
    }

    @Test
    fun noDifferencesAreReportedWithoutASelectedOrKnownProfile() {
        val state = AppState()
        state.updateSettings { it.copy(theme = ThemePreset.DARK_GITHUB) }
        assertNull(state.settings.workspaceProfileId)
        assertEquals(emptyList(), state.workspaceProfileDifferences())

        state.updateSettings { it.copy(workspaceProfileId = "removed_in_a_later_version") }
        assertEquals(emptyList(), state.workspaceProfileDifferences())
    }

    @Test
    fun profileDifferencesNamesEveryMismatchedLookValue() {
        val spec = WorkspaceProfile.CLASSIC.spec
        val settings = AppSettings(
            theme = ThemePreset.DARK_GITHUB,
            fontSize = 14,
            showMinimap = !spec.showMinimap,
            toolbarIconOnlyButtons = !spec.toolbarIconOnlyButtons,
            openNewFilesWithUnfiltered = !spec.openNewFilesWithUnfiltered,
        )

        assertEquals(
            listOf("Theme", "Log font size", "Minimap", "Toolbar labels", "Original panel for new files"),
            profileDifferences(spec, settings),
        )
    }

    @Test
    fun profileIdsAreUniqueAndFromIdResolvesThem() {
        assertEquals(WorkspaceProfile.entries.size, WorkspaceProfile.entries.map { it.id }.toSet().size)
        WorkspaceProfile.entries.forEach { assertEquals(it, WorkspaceProfile.fromId(it.id)) }
        assertNull(WorkspaceProfile.fromId(null))
        assertNull(WorkspaceProfile.fromId("nope"))
        assertTrue(WorkspaceProfile.entries.none { (it.title + it.description).contains("klogg", ignoreCase = true) })
    }

    @Test
    fun workspaceProfileIdRoundTripsThroughSettingsJson() {
        val decoded = settingsFromJson(AppSettings(workspaceProfileId = "logcat_query").settingsJson())!!
        assertEquals("logcat_query", decoded.workspaceProfileId)

        assertNull(settingsFromJson(AppSettings().settingsJson())!!.workspaceProfileId)
    }

    @Test
    fun jsonWithoutTheKeyDecodesToNoProfile() {
        assertNull(settingsFromJson("{}")!!.workspaceProfileId)
    }

    // ---- Appearance / Editor behavior groups --------------------------------------------------------------------------

    private val groupedSpec = WorkspaceProfile.CLASSIC.spec.copy(
        appearance = ProfileAppearance(
            tabStripStyle = TabStripStyle.PILL,
            tabShowSourceStripe = true,
            tabStripPosition = TabStripPosition.LEFT,
            toolbarPlacement = ToolbarPlacement.OWN_EDGE,
            toolbarEdge = ChromeEdge.BOTTOM,
            toolbarAlign = ToolbarAlign.END,
            interfaceScalePercent = 120,
        ),
        behavior = ProfileBehavior(
            ctrlFTarget = CtrlFTarget.TAGS,
            searchScopeBehavior = SearchScopeBehavior.TWO_SEARCHES,
            navScrollMargin = 8,
        ),
    )

    @Test
    fun builtInProfilesDefineNeitherGroupSoApplyingOneLeavesTabStyleChromeAndCtrlFAlone() {
        for (profile in WorkspaceProfile.entries) {
            assertNull(profile.spec.appearance, profile.id)
            assertNull(profile.spec.behavior, profile.id)
        }
        val state = AppState()
        state.updateSettings {
            it.copy(
                tabStripStyle = TabStripStyle.QUIET,
                tabStripPosition = TabStripPosition.RIGHT,
                toolbarPlacement = ToolbarPlacement.OWN_EDGE,
                toolbarEdge = ChromeEdge.LEFT,
                ctrlFTarget = CtrlFTarget.KEYWORD_REGEX,
                interfaceScalePercent = 140,
                navScrollMargin = 12,
            )
        }
        val before = state.settings

        for (profile in WorkspaceProfile.entries) state.applyWorkspaceProfile(profile)

        val after = state.settings
        assertEquals(before.tabStripStyle, after.tabStripStyle)
        assertEquals(before.tabStripPosition, after.tabStripPosition)
        assertEquals(before.toolbarPlacement, after.toolbarPlacement)
        assertEquals(before.toolbarEdge, after.toolbarEdge)
        assertEquals(before.ctrlFTarget, after.ctrlFTarget)
        assertEquals(before.interfaceScalePercent, after.interfaceScalePercent)
        assertEquals(before.navScrollMargin, after.navScrollMargin)
    }

    @Test
    fun applyingACustomProfileRestoresTabStyleChromePlacementAndCtrlFTarget() {
        val state = AppState()
        val profile = CustomWorkspaceProfile("custom-g", "Grouped", groupedSpec)
        state.updateSettings { it.copy(customWorkspaceProfiles = listOf(profile)) }

        state.applyResolvedProfile(profile.resolved())

        val s = state.settings
        assertEquals("custom-g", s.workspaceProfileId)
        assertEquals(TabStripStyle.PILL, s.tabStripStyle)
        assertTrue(s.tabShowSourceStripe)
        assertEquals(TabStripPosition.LEFT, s.tabStripPosition)
        assertEquals(ToolbarPlacement.OWN_EDGE, s.toolbarPlacement)
        assertEquals(ChromeEdge.BOTTOM, s.toolbarEdge)
        assertEquals(ToolbarAlign.END, s.toolbarAlign)
        assertEquals(120, s.interfaceScalePercent)
        assertEquals(CtrlFTarget.TAGS, s.ctrlFTarget)
        assertEquals(SearchScopeBehavior.TWO_SEARCHES, s.searchScopeBehavior)
        assertEquals(8, s.navScrollMargin)
        assertEquals(emptyList(), state.workspaceProfileDifferences())
    }

    @Test
    fun aGroupFieldThatIsNullKeepsTheLiveValueWhenApplied() {
        val defaults = AppSettings()
        val live = defaults.copy(tabStripStyle = TabStripStyle.QUIET, ctrlFTarget = CtrlFTarget.MESSAGE_RULE, filterListRows = 9)
        val spec = WorkspaceProfile.CLASSIC.spec.copy(
            appearance = ProfileAppearance(tabStripPosition = TabStripPosition.BOTTOM),
            behavior = ProfileBehavior(),
        )

        val applied = live.withProfileSpec(spec)

        assertEquals(TabStripPosition.BOTTOM, applied.tabStripPosition)
        assertEquals(TabStripStyle.QUIET, applied.tabStripStyle)
        assertEquals(CtrlFTarget.MESSAGE_RULE, applied.ctrlFTarget)
        assertEquals(9, applied.filterListRows)
    }

    @Test
    fun applyingTheInLayoutToolbarPlacementRecordsItsFallbackLikeTheSettingsRadio() {
        val state = AppState()
        state.updateSettings { it.copy(toolbarPlacement = ToolbarPlacement.WITH_TABS_START) }
        val spec = WorkspaceProfile.CLASSIC.spec.copy(appearance = ProfileAppearance(toolbarPlacement = ToolbarPlacement.IN_LAYOUT))

        state.applyResolvedProfile(CustomWorkspaceProfile("custom-l", "L", spec).resolved())

        assertEquals(ToolbarPlacement.IN_LAYOUT, state.settings.toolbarPlacement)
        assertEquals(ToolbarPlacement.WITH_TABS_START, state.settings.toolbarFallbackPlacement)
    }

    @Test
    fun customizedListsOnlyTheDifferencesOfGroupsTheProfileDefines() {
        val state = AppState()
        val profile = CustomWorkspaceProfile("custom-g", "Grouped", groupedSpec)
        state.updateSettings { it.copy(customWorkspaceProfiles = listOf(profile)) }
        state.applyResolvedProfile(profile.resolved())

        state.updateSettings { it.copy(tabStripStyle = TabStripStyle.OUTLINED, ctrlFTarget = CtrlFTarget.FIND_BAR) }
        // A value the profile leaves null (filterListRows) never counts, and neither does the chrome lock.
        state.updateSettings { it.copy(filterListRows = 12, chromeLocked = true) }

        assertEquals(listOf("Tab style", "Ctrl+F opens"), state.workspaceProfileDifferences())
    }

    @Test
    fun aTabStyleChangeShowsForACustomProfileButNeverForABuiltIn() {
        val builtIn = AppState()
        builtIn.applyWorkspaceProfile(WorkspaceProfile.CLASSIC)
        builtIn.updateSettings { it.copy(tabStripStyle = TabStripStyle.PILL, ctrlFTarget = CtrlFTarget.TAGS, tabStripPosition = TabStripPosition.LEFT) }
        assertEquals(emptyList(), builtIn.workspaceProfileDifferences())

        val custom = AppState()
        val saved = custom.saveCurrentAsWorkspaceProfile("Mine")
        assertEquals(emptyList(), custom.workspaceProfileDifferences())
        custom.updateSettings { it.copy(tabStripStyle = TabStripStyle.PILL) }
        assertEquals(listOf("Tab style"), custom.workspaceProfileDifferences())
        assertEquals(saved.id, custom.selectedWorkspaceProfile?.id)
    }

    @Test
    fun profileDifferencesLabelsEveryGroupFieldReadably() {
        val live = AppSettings()
        val spec = WorkspaceProfile.CLASSIC.spec.copy(theme = live.theme, fontSize = live.fontSize, showMinimap = live.showMinimap)
            .copy(
                toolbarIconOnlyButtons = live.toolbarIconOnlyButtons,
                openNewFilesWithUnfiltered = live.openNewFilesWithUnfiltered,
                appearance = ProfileAppearance(tabStripStyle = TabStripStyle.PILL, tabStripPosition = TabStripPosition.LEFT, toolbarOuter = true),
                behavior = ProfileBehavior(openUnfilteredOnCtrlF = true, autoLogRowWrap = false, logRowWrapLimitChars = 900),
            )

        assertEquals(
            listOf("Tab style", "Tab position", "Toolbar order on the tab edge", "Ctrl+F also shows Original", "Row wrapping"),
            profileDifferences(spec, live),
        )
    }
}
