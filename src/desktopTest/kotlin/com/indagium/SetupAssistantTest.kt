package com.indagium

import com.indagium.model.AppSettings
import com.indagium.model.CtrlFTarget
import com.indagium.model.SearchScopeBehavior
import com.indagium.model.ThemePreset
import com.indagium.ui.AUTOSAVE_MAGIC_CURRENT
import com.indagium.ui.AppState
import com.indagium.ui.WorkspaceProfile
import com.indagium.ui.settingsFromJson
import com.indagium.ui.settingsJson
import java.io.File
import kotlin.io.path.createTempDirectory
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class SetupAssistantTest {
    @Test
    fun setupAssistantCompletionAndRevisionRoundTripAndMissingKeysUseDefaults() {
        val done = settingsFromJson(AppSettings(setupAssistantDone = true, setupAssistantRevision = 2).settingsJson())!!
        assertTrue(done.setupAssistantDone)
        assertEquals(2, done.setupAssistantRevision)

        val defaults = settingsFromJson(AppSettings().settingsJson())!!
        assertFalse(defaults.setupAssistantDone)
        assertEquals(0, defaults.setupAssistantRevision)
        // A completed blob written before the revision existed upgrades from revision zero.
        val oldCompleted = settingsFromJson("""{"setupAssistantDone":true}""")!!
        assertTrue(oldCompleted.setupAssistantDone)
        assertEquals(0, oldCompleted.setupAssistantRevision)
        assertFalse(settingsFromJson("{}")!!.setupAssistantDone)
    }

    @Test
    fun startupRoutesFreshAndOlderCompletedUsersAndLeavesCurrentRevisionClosed() {
        val fresh = AppState()
        fresh.maybeShowSetupAssistantOnStartup()
        assertTrue(fresh.setupAssistantOpen)
        assertFalse(fresh.shouldSetupAssistantStartAtSearch())

        val olderDone = AppState()
        olderDone.updateSettings { it.copy(setupAssistantDone = true, setupAssistantRevision = 1) }
        olderDone.maybeShowSetupAssistantOnStartup()
        assertTrue(olderDone.setupAssistantOpen)
        assertTrue(olderDone.shouldSetupAssistantStartAtSearch())
        // The app can temporarily unmount the wizard while license/update dialogs are shown.
        assertTrue(olderDone.shouldSetupAssistantStartAtSearch(), "The route survives a temporary remount")
        olderDone.skipSetupAssistant()
        assertFalse(olderDone.shouldSetupAssistantStartAtSearch(), "Closing the wizard clears its startup route")

        val current = AppState()
        current.updateSettings { it.copy(setupAssistantDone = true, setupAssistantRevision = 2) }
        current.maybeShowSetupAssistantOnStartup()
        assertFalse(current.setupAssistantOpen)

        val future = AppState()
        future.updateSettings { it.copy(setupAssistantDone = true, setupAssistantRevision = 9) }
        future.maybeShowSetupAssistantOnStartup()
        assertFalse(future.setupAssistantOpen)
    }

    @Test
    fun skippingAndFinishingBothMarkItDoneAndCloseIt() {
        for (finish in listOf(false, true)) {
            val dir = createTempDirectory("setup-assistant-dismissal").toFile()
            val autosave = File(dir, "autosave.cache")
            try {
                val state = AppState(autosaveFile = autosave, restoreOnCreate = false)
                state.maybeShowSetupAssistantOnStartup()
                state.updateSettings {
                    it.copy(openNewFilesWithUnfiltered = true, openUnfilteredOnCtrlF = true)
                }
                if (finish) state.finishSetupAssistant() else state.skipSetupAssistant()

                assertFalse(state.setupAssistantOpen, "finish=$finish")
                assertTrue(state.settings.setupAssistantDone, "finish=$finish")
                assertEquals(2, state.settings.setupAssistantRevision, "finish=$finish")
                state.autosaveNow()
                state.close()

                val restored = AppState(autosaveFile = autosave, restoreOnCreate = true)
                try {
                    restored.maybeShowSetupAssistantOnStartup()
                    assertFalse(restored.setupAssistantOpen, "finish=$finish")
                    assertTrue(restored.settings.setupAssistantDone, "finish=$finish")
                    assertEquals(2, restored.settings.setupAssistantRevision, "finish=$finish")
                    assertTrue(restored.settings.openNewFilesWithUnfiltered, "finish=$finish")
                    assertTrue(restored.settings.openUnfilteredOnCtrlF, "finish=$finish")
                } finally {
                    restored.close()
                }
            } finally {
                dir.deleteRecursively()
            }
        }
    }

    @Test
    fun dismissingAssistantPreservesARevisionHigherThanTheCurrentOne() {
        val state = AppState()
        state.updateSettings { it.copy(setupAssistantRevision = 12) }
        state.maybeShowSetupAssistantOnStartup()

        state.skipSetupAssistant()

        assertTrue(state.settings.setupAssistantDone)
        assertEquals(12, state.settings.setupAssistantRevision)
        assertEquals(12, settingsFromJson(state.settings.settingsJson())!!.setupAssistantRevision)
    }

    @Test
    fun rerunOpensEvenWhenDoneAndClosesSettings() {
        val state = AppState()
        state.updateSettings { it.copy(setupAssistantDone = true) }
        state.settingsOpen = true

        state.rerunSetupAssistant()

        assertTrue(state.setupAssistantOpen)
        assertFalse(state.settingsOpen)
        assertFalse(state.shouldSetupAssistantStartAtSearch(), "Manual reruns always start at Workspace")
    }

    @Test
    fun startedWithExistingDataReflectsTheAutosaveFileAtCreation() {
        val dir = createTempDirectory("setup-assistant").toFile()
        try {
            val autosave = File(dir, "autosave.cache")
            assertFalse(AppState(autosaveFile = autosave, restoreOnCreate = true).startedWithExistingData)

            autosave.writeText("$AUTOSAVE_MAGIC_CURRENT\n")
            assertTrue(AppState(autosaveFile = autosave, restoreOnCreate = true).startedWithExistingData)
            // Restoring is what makes an existing file meaningful; tests that skip it stay false.
            assertFalse(AppState(autosaveFile = autosave, restoreOnCreate = false).startedWithExistingData)
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test
    fun aFreshAppStateStartsWithoutExistingData() {
        assertFalse(AppState().startedWithExistingData)
        assertEquals(false, AppState().settings.setupAssistantDone)
        assertEquals(0, AppState().settings.setupAssistantRevision)
    }

    // ---- the assistant saves "My setup" when the choices differ ------------------------------------------------------

    private fun freshAssistant(): AppState = AppState().also { it.maybeShowSetupAssistantOnStartup() }

    @Test
    fun pickingABuiltInAndChangingNothingElseCreatesNoProfileAndKeepsTheBuiltInSelected() {
        val state = freshAssistant()
        state.applyWorkspaceProfile(WorkspaceProfile.MINIMAL) // what leaving step 1 does

        state.finishSetupAssistant()

        assertTrue(state.settings.customWorkspaceProfiles.isEmpty())
        assertEquals("minimal", state.settings.workspaceProfileId)
    }

    @Test
    fun closingWithoutAnyChoiceCreatesNoProfile() {
        val state = freshAssistant()

        state.skipSetupAssistant()

        assertTrue(state.settings.customWorkspaceProfiles.isEmpty())
        assertEquals(null, state.settings.workspaceProfileId)
    }

    @Test
    fun aChoiceThatDiffersFromThePickedProfileCreatesAndSelectsMySetup() {
        val changes = listOf<(AppSettings) -> AppSettings>(
            { it.copy(theme = ThemePreset.DRACULA) },
            { it.copy(ctrlFTarget = CtrlFTarget.TAGS) },
            { it.copy(openUnfilteredOnCtrlF = true) },
            { it.copy(openNewFilesWithUnfiltered = true) },
            { it.copy(searchScopeBehavior = SearchScopeBehavior.TWO_SEARCHES) },
            { it.copy(syncSplitPanelSelectionAndScroll = false) },
        )
        changes.forEachIndexed { index, change ->
            val state = freshAssistant()
            state.applyWorkspaceProfile(WorkspaceProfile.CLASSIC)
            state.updateSettings(change)
            val chosen = state.settings

            state.finishSetupAssistant()

            val created = state.settings.customWorkspaceProfiles.single()
            assertEquals("My setup", created.name, "change $index")
            assertEquals(created.id, state.settings.workspaceProfileId, "change $index")
            assertEquals(chosen.theme, created.spec.theme, "change $index")
            assertEquals(chosen.ctrlFTarget, created.spec.behavior?.ctrlFTarget, "change $index")
            assertEquals(chosen.searchScopeBehavior, created.spec.behavior?.searchScopeBehavior, "change $index")
            assertEquals(emptyList(), state.workspaceProfileDifferences(), "change $index")
        }
    }

    @Test
    fun theThemeAMinimalProfileGivesIsNotACustomChoice() {
        val state = freshAssistant()
        state.applyWorkspaceProfile(WorkspaceProfile.MINIMAL)
        assertEquals(ThemePreset.GRAPHITE_DIM, state.settings.theme)

        state.finishSetupAssistant()

        assertTrue(state.settings.customWorkspaceProfiles.isEmpty())
    }

    @Test
    fun rerunningCreatesAnotherUniquelyNamedProfileOnlyWhenTheChoicesDifferAgain() {
        val state = freshAssistant()
        state.applyWorkspaceProfile(WorkspaceProfile.CLASSIC)
        state.updateSettings { it.copy(ctrlFTarget = CtrlFTarget.TAGS) }
        state.finishSetupAssistant()
        assertEquals(listOf("My setup"), state.settings.customWorkspaceProfiles.map { it.name })

        // A rerun that changes nothing keeps the profile it started with.
        state.rerunSetupAssistant()
        state.finishSetupAssistant()
        assertEquals(listOf("My setup"), state.settings.customWorkspaceProfiles.map { it.name })

        state.rerunSetupAssistant()
        state.updateSettings { it.copy(searchScopeBehavior = SearchScopeBehavior.UNFILTERED) }
        state.finishSetupAssistant()

        assertEquals(listOf("My setup", "My setup (2)"), state.settings.customWorkspaceProfiles.map { it.name })
        assertEquals(state.settings.customWorkspaceProfiles.last().id, state.settings.workspaceProfileId)
    }

    @Test
    fun pickingACustomProfileInTheAssistantIsNotAChoiceThatNeedsAnotherProfile() {
        val state = AppState()
        val mine = state.saveCurrentAsWorkspaceProfile("Mine")
        state.applyWorkspaceProfile(WorkspaceProfile.CLASSIC)
        state.updateSettings { it.copy(setupAssistantDone = true) }
        state.rerunSetupAssistant()

        state.applyResolvedProfile(state.resolveWorkspaceProfile(mine.id)!!) // leaving step 1 with that card picked
        state.finishSetupAssistant()

        assertEquals(listOf("Mine"), state.settings.customWorkspaceProfiles.map { it.name })
        assertEquals(mine.id, state.settings.workspaceProfileId)
    }
}
