package com.indagium

import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertIsOff
import androidx.compose.ui.test.assertIsOn
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import com.indagium.edition.Edition
import com.indagium.ui.AppState
import com.indagium.ui.SupportDialog
import org.junit.After
import org.junit.Rule
import org.junit.Test
import java.io.File
import kotlin.io.path.createTempDirectory
import kotlin.test.assertFalse
import kotlin.test.assertTrue

@OptIn(ExperimentalTestApi::class)
class SupportDialogUiTest {
    @get:Rule
    val rule = createComposeRule()
    private val dir = createTempDirectory("support-dialog-ui").toFile()
    private val state = AppState(
        autosaveFile = File(dir, "state.cache"), autoExportNotes = false,
        notesDir = File(dir, "notes"), archiveCacheDir = File(dir, "archive"),
        customCommandsDir = File(dir, "commands"), controlTokenFile = File(dir, "token"),
        sourceIndexFile = File(dir, "source-index"), testingDir = File(dir, "testing"),
    )

    @After
    fun tearDown() {
        state.close()
        dir.deleteRecursively()
    }

    @Test
    fun theCheckboxAndItsLabelToggleTheSavedPreferenceInEveryNonFreeEdition() {
        rule.setContent { SupportDialog(state) }
        for (edition in listOf(Edition.UNLIMITED, Edition.PREMIUM, Edition.FRIENDS_FAMILY)) {
            rule.runOnIdle { state.editionService.setForDev(edition) }
            rule.waitForIdle()
            val checkbox = rule.onNodeWithContentDescription("Don't remind me again")
            checkbox.assertIsEnabled().assertIsOff().performClick()
            rule.waitForIdle()
            checkbox.assertIsOn()
            assertTrue(state.settings.suppressSupportPrompt)
            rule.onNodeWithText("Don't remind me again").performClick()
            rule.waitForIdle()
            checkbox.assertIsOff()
            assertFalse(state.settings.suppressSupportPrompt)
        }
    }

    @Test
    fun changingFromFreeToUnlimitedUnlocksTheExistingDialog() {
        state.editionService.setForDev(Edition.FREE)
        rule.setContent { SupportDialog(state) }
        val checkbox = rule.onNodeWithContentDescription("Don't remind me again")
        checkbox.assertIsNotEnabled().assertIsOff()
        rule.runOnIdle { state.editionService.setForDev(Edition.UNLIMITED) }
        rule.waitForIdle()
        checkbox.assertIsEnabled().performClick()
        rule.waitForIdle()
        checkbox.assertIsOn()
        assertTrue(state.settings.suppressSupportPrompt)
    }
}
