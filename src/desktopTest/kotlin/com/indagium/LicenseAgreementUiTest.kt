package com.indagium

import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.isEnabled
import androidx.compose.ui.test.isToggleable
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import com.indagium.generated.BuildInfo
import com.indagium.ui.AppState
import com.indagium.ui.LicenseAgreementDialog
import org.junit.Rule
import org.junit.Test
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

@OptIn(ExperimentalTestApi::class)
class LicenseAgreementUiTest {
    @get:Rule
    val rule = createComposeRule()

    @Test
    fun acceptingRequiresAnExplicitCheckboxAndPersistsTheSelectedAgreement() {
        val state = AppState()
        try {
            rule.setContent { LicenseAgreementDialog(mandatory = true, onAccept = state::acceptLicenseAgreement) }
            rule.onNodeWithText(BuildInfo.LICENSE_TITLE).assertExists()
            rule.onNodeWithText("Accept").assert(hasAnyAncestor(!isEnabled()))
            rule.onNode(isToggleable()).performClick()
            rule.onNodeWithText("Accept").assertIsEnabled().performClick()
            rule.runOnIdle { assertFalse(state.needsLicenseAcceptance) }
        } finally {
            state.close()
        }
    }

    @Test
    fun decliningInvokesExitWithoutRecordingAcceptance() {
        val state = AppState()
        var declined = false
        try {
            rule.setContent {
                LicenseAgreementDialog(
                    mandatory = true,
                    onAccept = state::acceptLicenseAgreement,
                    onDecline = { declined = true },
                )
            }
            rule.onNodeWithText("Decline & Exit").performClick()
            rule.runOnIdle {
                assertTrue(declined)
                assertTrue(state.needsLicenseAcceptance)
                assertNull(state.settings.acceptedLicenseVersion)
            }
        } finally {
            state.close()
        }
    }

    @Test
    fun settingsReviewHasNoAcceptanceActionAndKeepsTheSeparateOfflineViewer() {
        rule.setContent { LicenseAgreementDialog(mandatory = false) }
        rule.onNodeWithText("Accept").assertDoesNotExist()
        rule.onNodeWithText("Decline & Exit").assertDoesNotExist()
        rule.onNodeWithText("Close").assertExists()
        rule.onNodeWithText("Third-party licences…").performClick()
        rule.onNodeWithText("Third-party licences and notices").assertExists()
    }
}
