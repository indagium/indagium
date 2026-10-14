package com.indagium

import com.indagium.model.AppSettings
import com.indagium.ui.AUTOSAVE_MAGIC_CURRENT
import com.indagium.ui.AppState
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
}
