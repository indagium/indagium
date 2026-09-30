package com.indagium

import com.indagium.model.AppSettings
import com.indagium.model.ThemePreset
import com.indagium.ui.AppState
import com.indagium.ui.WorkspaceProfile
import com.indagium.ui.profileDifferences
import com.indagium.ui.settingsFromJson
import com.indagium.ui.settingsJson
import kotlin.test.Test
import kotlin.test.assertEquals
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
            assertEquals(spec.filterVisible, state.filterVisible, profile.id)
            assertEquals(spec.filterBarVisible, state.filterBarVisible, profile.id)
            assertEquals(spec.annotationVisible, state.annotationVisible, profile.id)
            assertEquals(spec.videoPanelVisible, state.videoPanelVisible, profile.id)
            assertEquals(spec.aiPanelVisible, state.aiPanelVisible, profile.id)
            assertEquals(emptyList(), state.workspaceProfileDifferences(), profile.id)
        }
    }

    @Test
    fun changingTheThemeAfterApplyingReportsOnlyTheme() {
        val state = AppState()
        state.applyWorkspaceProfile(WorkspaceProfile.FOCUSED)
        state.updateSettings { it.copy(theme = ThemePreset.DARK_GITHUB) }

        assertEquals(listOf("Theme"), state.workspaceProfileDifferences())
    }

    @Test
    fun layoutChangesAreReportedAndResetByReapplyingTheProfile() {
        val state = AppState()
        state.applyWorkspaceProfile(WorkspaceProfile.MINIMAL)
        state.updateAnnotationVisible(true)
        state.updateFilterBarVisible(true)

        assertEquals(listOf("Filter bar", "Notes panel"), state.workspaceProfileDifferences())

        state.applyWorkspaceProfile(WorkspaceProfile.MINIMAL)
        assertEquals(emptyList(), state.workspaceProfileDifferences())
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
    fun profileDifferencesNamesEveryMismatchedValue() {
        val spec = WorkspaceProfile.CLASSIC.spec
        val settings = AppSettings(
            theme = ThemePreset.DARK_GITHUB,
            fontSize = 14,
            showMinimap = !spec.showMinimap,
            toolbarIconOnlyButtons = !spec.toolbarIconOnlyButtons,
            openNewFilesWithUnfiltered = !spec.openNewFilesWithUnfiltered,
        )
        val state = AppState()
        val layout = state.layoutSnapshot().copy(
            filterVisible = !spec.filterVisible,
            filterBarVisible = !spec.filterBarVisible,
            annotationVisible = !spec.annotationVisible,
            videoPanelVisible = !spec.videoPanelVisible,
            aiPanelVisible = !spec.aiPanelVisible,
        )

        assertEquals(
            listOf(
                "Theme", "Log font size", "Minimap", "Toolbar labels", "Original panel for new files",
                "Filter sidebar", "Filter bar", "Notes panel", "Video panel", "AI panel",
            ),
            profileDifferences(spec, settings, layout),
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
