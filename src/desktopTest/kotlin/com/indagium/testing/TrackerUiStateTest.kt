package com.indagium.testing

import com.indagium.debug.sendsToExternalService
import com.indagium.edition.Edition
import com.indagium.model.AiProviderKind
import com.indagium.model.AiProviderProfile
import com.indagium.testing.model.EvidenceFlags
import com.indagium.testing.model.IssueDestination
import com.indagium.testing.model.JudgeMode
import com.indagium.testing.model.TestingSettings
import com.indagium.ui.RunDialogModel
import com.indagium.ui.TRACKER_DISABLED_HINT
import com.indagium.ui.TrackerStatus
import com.indagium.ui.createLabel
import com.indagium.ui.destinationChoices
import com.indagium.ui.editionSummary
import com.indagium.ui.toConfig
import com.indagium.ui.tokenLine
import com.indagium.ui.withTestingDefaults
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** The pure state behind the tracker destination, the Testing settings and the token line. */
class TrackerUiStateTest {
    private val profiles = listOf(
        AiProviderProfile(id = "judge", displayName = "J", baseUrl = "http://127.0.0.1:1", model = "m", kind = AiProviderKind.OPENAI_COMPATIBLE),
    )

    private fun dialog() = RunDialogModel("suite-1", setOf("case-1"), choice = null, deviceSerial = null)

    @Test
    fun theTrackerDestinationIsEnabledOnlyWhenNothingIsMissing() {
        val ready = destinationChoices(trackerProblem = null).last()
        assertEquals("Tracker", ready.label)
        assertTrue(ready.enabled)
        assertNull(ready.hint)

        val missing = destinationChoices("Add the tracker's access token (Settings > Issue tracker)").last()
        assertFalse(missing.enabled)
        assertEquals("Add the tracker's access token (Settings > Issue tracker)", missing.hint)
        assertEquals(TRACKER_DISABLED_HINT, destinationChoices().last().hint, "the default is the not-configured hint")
        assertTrue(destinationChoices(null).take(3).all { it.enabled })
    }

    @Test
    fun theCreateButtonNamesTheTracker() {
        assertEquals("Create in Jira", createLabel(IssueDestination.TRACKER, "Jira"))
        assertEquals("Create in tracker", createLabel(IssueDestination.TRACKER))
        assertEquals("Create", createLabel(IssueDestination.LOCAL, "Jira"))
    }

    @Test
    fun theRunDialogStartsFromTheTestingDefaults() {
        val testing = TestingSettings(
            defaultJudgeProfileId = "judge", defaultJudgeMode = JudgeMode.FAILURES_ONLY.wire,
            evidence = EvidenceFlags(video = true, screenshots = false, logcat = false, transcript = false), confirmationTimeoutMinutes = 9,
        )

        val model = dialog().withTestingDefaults(testing, profiles)

        assertEquals("judge", model.judgeProfileId)
        assertEquals(JudgeMode.FAILURES_ONLY, model.judgeMode)
        assertEquals(testing.evidence, model.evidence)
        assertEquals(9 * 60_000L, model.confirmationTimeoutMs)
        val config = model.copy(choice = com.indagium.ui.LaneChoice("judge", "x"), deviceSerial = "SER").toConfig(listOf("case-1")).getOrThrow()
        assertEquals(9 * 60_000L, config.confirmationTimeoutMs, "the timeout reaches the run's config")
        assertEquals("judge", config.judgeProfileId)
        assertEquals(testing.evidence, config.evidence)
    }

    @Test
    fun aDefaultJudgeWhoseProfileIsGoneStartsOff() {
        val testing = TestingSettings(defaultJudgeProfileId = "deleted", defaultJudgeMode = JudgeMode.EVERY_STEP.wire)

        val model = dialog().withTestingDefaults(testing, profiles)

        assertNull(model.judgeProfileId)
        assertEquals(JudgeMode.OFF, model.judgeMode)
        assertEquals(JudgeMode.OFF, TestingSettings().judgeMode)
        assertEquals(5 * 60_000L, TestingSettings().confirmationTimeoutMs)
    }

    @Test
    fun theEditionSummaryStatesTheLimits() {
        assertEquals("Free (1 suite, 5 cases per suite)", editionSummary(Edition.FREE))
        assertEquals("Unlimited (no limits)", editionSummary(Edition.UNLIMITED))
        assertEquals("Premium (no limits)", editionSummary(Edition.PREMIUM))
    }

    @Test
    fun theTokenLineSaysWhereTheTokenIsOrThatThereIsNone() {
        assertEquals("Checking the stored token…", TrackerStatus().tokenLine())
        assertEquals("Stored in macOS Keychain", TrackerStatus(true, "Stored in macOS Keychain").tokenLine())
        assertEquals("No token saved.", TrackerStatus(false, "x").tokenLine())
        assertTrue(TrackerStatus(false, "x", "locked").tokenLine().contains("locked"))
    }

    @Test
    fun onlyTheTrackerDestinationOfCreateIssueFromStepSendsDataOut() {
        assertTrue(sendsToExternalService("create_issue_from_step", mapOf("destination" to "tracker")))
        assertTrue(sendsToExternalService("create_issue_from_step", mapOf("destination" to " TRACKER ")))
        assertFalse(sendsToExternalService("create_issue_from_step", mapOf("destination" to "markdown")))
        assertFalse(sendsToExternalService("create_issue_from_step", emptyMap()))
        assertFalse(sendsToExternalService("get_issue", mapOf("destination" to "tracker")))
    }
}
