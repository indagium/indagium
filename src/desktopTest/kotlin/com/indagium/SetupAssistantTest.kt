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
    fun setupAssistantDoneRoundTripsAndAMissingKeyMeansNotDone() {
        val done = settingsFromJson(AppSettings(setupAssistantDone = true).settingsJson())!!
        assertTrue(done.setupAssistantDone)

        assertFalse(settingsFromJson(AppSettings().settingsJson())!!.setupAssistantDone)
        // A blob written before the assistant existed: existing users see it once.
        assertFalse(settingsFromJson("{}")!!.setupAssistantDone)
    }

    @Test
    fun startupOpensTheAssistantOnlyWhenItIsNotDone() {
        val fresh = AppState()
        fresh.maybeShowSetupAssistantOnStartup()
        assertTrue(fresh.setupAssistantOpen)

        val done = AppState()
        done.updateSettings { it.copy(setupAssistantDone = true) }
        done.maybeShowSetupAssistantOnStartup()
        assertFalse(done.setupAssistantOpen)
    }

    @Test
    fun skippingAndFinishingBothMarkItDoneAndCloseIt() {
        for (finish in listOf(false, true)) {
            val state = AppState()
            state.maybeShowSetupAssistantOnStartup()

            if (finish) state.finishSetupAssistant() else state.skipSetupAssistant()

            assertFalse(state.setupAssistantOpen, "finish=$finish")
            assertTrue(state.settings.setupAssistantDone, "finish=$finish")
            // What autosave writes carries the flag, so it survives a relaunch.
            assertTrue(settingsFromJson(state.settings.settingsJson())!!.setupAssistantDone, "finish=$finish")
        }
    }

    @Test
    fun rerunOpensEvenWhenDoneAndClosesSettings() {
        val state = AppState()
        state.updateSettings { it.copy(setupAssistantDone = true) }
        state.settingsOpen = true

        state.rerunSetupAssistant()

        assertTrue(state.setupAssistantOpen)
        assertFalse(state.settingsOpen)
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
    }
}
