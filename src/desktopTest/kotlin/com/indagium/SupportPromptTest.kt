package com.indagium

import com.indagium.edition.Edition
import com.indagium.ui.AppState
import com.indagium.update.SponsorChecker
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpStatusCode
import java.io.File
import kotlin.io.path.createTempDirectory
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

private const val DAY_MS = 24L * 60 * 60 * 1000

// Arbitrary anchor far from epoch 0 so "now - N days" never goes negative.
private const val ANCHOR_MS = 500L * DAY_MS

class SupportPromptTest {
    @Test
    fun supportPromptDueRequiresAFullTenDayIntervalSinceTheLastPrompt() {
        val state = newState()
        try {
            assertFalse(state.supportPromptDue(ANCHOR_MS, last = 0L)) // never prompted yet: not due
            assertFalse(state.supportPromptDue(ANCHOR_MS, last = ANCHOR_MS - (9 * DAY_MS + 23 * 60 * 60 * 1000)))
            assertTrue(state.supportPromptDue(ANCHOR_MS, last = ANCHOR_MS - 10 * DAY_MS))
            assertTrue(state.supportPromptDue(ANCHOR_MS, last = ANCHOR_MS - 11 * DAY_MS))
        } finally {
            state.close()
        }
    }

    @Test
    fun firstRunRecordsTheTimestampWithoutOpeningThePopup() {
        val state = newState()
        try {
            assertEquals(0L, state.settings.lastSupportPromptAt)

            state.maybeShowSupportPromptOnStartup(ANCHOR_MS)

            assertFalse(state.supportDialogOpen)
            assertEquals(ANCHOR_MS, state.settings.lastSupportPromptAt)
        } finally {
            state.close()
        }
    }

    @Test
    fun aDuePromptOpensAndResetsTheTimestamp() {
        val state = newState()
        try {
            state.acceptLicenseAgreement()
            state.maybeShowSupportPromptOnStartup(ANCHOR_MS) // first run: records the baseline, doesn't open

            val due = ANCHOR_MS + 11 * DAY_MS
            state.maybeShowSupportPromptOnStartup(due)

            assertTrue(state.supportDialogOpen)
            assertEquals(due, state.settings.lastSupportPromptAt)
        } finally {
            state.close()
        }
    }

    @Test
    fun notYetDueLeavesThePopupClosedAndTheTimestampUntouched() {
        val state = newState()
        try {
            state.acceptLicenseAgreement()
            state.maybeShowSupportPromptOnStartup(ANCHOR_MS)

            val stillEarly = ANCHOR_MS + 3 * DAY_MS
            state.maybeShowSupportPromptOnStartup(stillEarly)

            assertFalse(state.supportDialogOpen)
            assertEquals(ANCHOR_MS, state.settings.lastSupportPromptAt)
        } finally {
            state.close()
        }
    }

    @Test
    fun suppressedWhileLicenseAcceptanceIsStillPending() {
        val state = newState()
        try {
            // Deliberately no acceptLicenseAgreement() call — needsLicenseAcceptance stays true.
            state.maybeShowSupportPromptOnStartup(ANCHOR_MS) // first run baseline

            val due = ANCHOR_MS + 11 * DAY_MS
            state.maybeShowSupportPromptOnStartup(due)

            assertFalse(state.supportDialogOpen)
            // Suppressed, not just deferred: the timestamp isn't consumed either, so the popup is
            // still due (rather than reset) the next time the license is actually accepted.
            assertEquals(ANCHOR_MS, state.settings.lastSupportPromptAt)
        } finally {
            state.close()
        }
    }

    @Test
    fun manualOpenFromSettingsDoesNotResetTheTenDayTimer() {
        val state = newState()
        try {
            state.acceptLicenseAgreement()
            state.maybeShowSupportPromptOnStartup(ANCHOR_MS) // first run baseline
            assertFalse(state.supportDialogOpen)

            state.openSupportDialog()

            assertTrue(state.supportDialogOpen)
            // A manual open (e.g. Settings' "Support project" link) must not touch the timer that
            // governs the automatic startup prompt.
            assertEquals(ANCHOR_MS, state.settings.lastSupportPromptAt)
        } finally {
            state.close()
        }
    }

    @Test
    fun dismissClosesThePopup() {
        val state = newState()
        try {
            state.openSupportDialog()
            assertTrue(state.supportDialogOpen)

            state.dismissSupportDialog()

            assertFalse(state.supportDialogOpen)
        } finally {
            state.close()
        }
    }

    @Test
    fun nonFreeEditionsCanSaveAnOptOutThatStopsAutomaticPromptsButAllowsManualSupport() {
        for (edition in listOf(Edition.PREMIUM, Edition.FRIENDS_FAMILY, Edition.UNLIMITED)) {
            val state = newState()
            try {
                state.acceptLicenseAgreement()
                state.editionService.setForDev(edition)
                state.maybeShowSupportPromptOnStartup(ANCHOR_MS)
                assertTrue(state.setSupportPromptSuppressed(true))
                assertTrue(state.settings.suppressSupportPrompt)
                state.maybeShowSupportPromptOnStartup(ANCHOR_MS + 11 * DAY_MS)
                assertFalse(state.supportDialogOpen)
                assertEquals(ANCHOR_MS, state.settings.lastSupportPromptAt)
                state.openSupportDialog()
                assertTrue(state.supportDialogOpen)
                state.dismissSupportDialog()
                assertTrue(state.setSupportPromptSuppressed(false))
                state.maybeShowSupportPromptOnStartup(ANCHOR_MS + 11 * DAY_MS)
                assertTrue(state.supportDialogOpen)
            } finally {
                state.close()
            }
        }
    }

    @Test
    fun freeCannotSetAnOptOutButAPreviousChoiceIsStillRespected() {
        val state = newState()
        try {
            state.acceptLicenseAgreement()
            state.editionService.setForDev(Edition.FREE)
            assertFalse(state.setSupportPromptSuppressed(true))
            assertFalse(state.settings.suppressSupportPrompt)
            state.editionService.setForDev(Edition.UNLIMITED)
            assertTrue(state.setSupportPromptSuppressed(true))
            state.editionService.setForDev(Edition.FREE)
            state.maybeShowSupportPromptOnStartup(ANCHOR_MS + 11 * DAY_MS)
            assertFalse(state.supportDialogOpen)
            assertTrue(state.settings.suppressSupportPrompt)
        } finally {
            state.close()
        }
    }

    // Isolated autosave file (never the shared real app-data-dir default) and a MockEngine
    // sponsorChecker so opening the dialog never touches the network during tests.
    private fun newState(): AppState {
        val cacheFile = File(createTempDirectory("openlog-support-prompt").toFile(), "state.cache")
        val client = HttpClient(MockEngine { respond("Not Found", HttpStatusCode.NotFound) }) { expectSuccess = false }
        return AppState(autosaveFile = cacheFile, sponsorChecker = SponsorChecker(client))
    }
}
