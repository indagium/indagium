package com.indagium

import com.indagium.model.AppSettings
import com.indagium.model.LogViewPresetId
import com.indagium.model.ProfileLayoutRef
import com.indagium.model.ThemePreset
import com.indagium.ui.AppState
import com.indagium.ui.LogViewLayoutPreset
import com.indagium.ui.WorkspaceProfile
import com.indagium.ui.activeLogViewLayoutRef
import com.indagium.ui.applyLogViewPreset
import com.indagium.ui.logViewPresetActive
import com.indagium.ui.profileDifferences
import com.indagium.ui.settingsFromJson
import com.indagium.ui.settingsJson
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
}
