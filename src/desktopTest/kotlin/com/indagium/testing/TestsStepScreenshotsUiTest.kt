@file:Suppress("MagicNumber", "ktlint:standard:max-line-length", "MaxLineLength")

package com.indagium.testing

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.waitUntilAtLeastOneExists
import androidx.compose.ui.unit.dp
import com.indagium.testing.model.StepExample
import com.indagium.testing.model.TestCase
import com.indagium.testing.model.TestStep
import com.indagium.testing.model.newExampleId
import com.indagium.testing.model.newStepId
import com.indagium.testing.store.StoreResult
import com.indagium.ui.AppState
import com.indagium.ui.TestsWorkspace
import org.junit.After
import org.junit.Rule
import org.junit.Test
import java.io.File
import kotlin.io.path.createTempDirectory
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/** The step inspector shows a step's screenshots without opening anything: the Examples section follows "has examples", plus the thumbnail strip and the recorded screens. */
@OptIn(ExperimentalTestApi::class)
class TestsStepScreenshotsUiTest {
    @get:Rule
    val rule = createComposeRule()

    private val dir: File = createTempDirectory("tests-step-screenshots-ui").toFile()
    private val state = AppState(
        autosaveFile = File(dir, "state.cache"),
        autoExportNotes = false,
        notesDir = File(dir, "notes"),
        archiveCacheDir = File(dir, "archive"),
        customCommandsDir = File(dir, "commands"),
        controlTokenFile = File(dir, "token"),
        sourceIndexFile = File(dir, "source-index"),
        testingDir = File(dir, "testing"),
    )

    @After
    fun tearDown() {
        state.close()
        dir.deleteRecursively()
    }

    private fun golden(suiteId: String, name: String): String {
        val source = File(dir, "src").apply { mkdirs() }.let { File(it, name).apply { writeBytes(ByteArray(16)) } }
        return (state.importTestGoldenImage(suiteId, source) as StoreResult.Ok).value
    }

    /** A case with a step holding one golden screenshot, a second one holding one too, and a third without examples. */
    private fun seed(): Triple<String, String, List<TestStep>> {
        val suiteId = (state.createTestSuite("Shop") as StoreResult.Ok).value.id
        val shot1 = StepExample.GoldenScreenshot(newExampleId(), golden(suiteId, "home.png"), "Home")
        val shot2 = StepExample.GoldenScreenshot(newExampleId(), golden(suiteId, "cart.png"), "Cart")
        val steps = listOf(
            TestStep(newStepId(), "Open the app", examples = listOf(shot1)),
            TestStep(newStepId(), "Open the cart", examples = listOf(shot2)),
            TestStep(newStepId(), "Pay"),
        )
        val caseId = (state.createTestCase(suiteId, TestCase("", "Checkout", steps = steps)) as StoreResult.Ok).value.id
        return Triple(suiteId, caseId, state.testLibrary.findCase(caseId)!!.case.steps)
    }

    private fun open(suiteId: String, caseId: String) {
        state.testsView.selectedSuiteId = suiteId
        state.testsView.selectedCaseId = caseId
        rule.setContent { Box(Modifier.size(1300.dp, 900.dp)) { TestsWorkspace(state) } }
        rule.waitForIdle()
    }

    private fun thumbnails() = rule.onAllNodesWithContentDescription("Screenshot 1 of 1. Show in Examples", substring = false)

    @Test
    fun aStepWithExamplesShowsThemOpenAndAThumbnailStripWithoutAnyClick() {
        val (suiteId, caseId, steps) = seed()
        state.testsView.selectedStepId = steps[0].id
        open(suiteId, caseId)

        // The Examples section is open by itself (its golden screenshot editor is on screen) ...
        rule.onNodeWithText("Examples (1)").assertExists()
        rule.onNodeWithText("Golden screenshot").assertExists()
        // ... and the strip under the title has one thumbnail per golden screenshot.
        assertEquals(1, thumbnails().fetchSemanticsNodes().size)
    }

    @Test
    fun aStepWithoutExamplesKeepsTheSectionClosedAndHasNoStrip() {
        val (suiteId, caseId, steps) = seed()
        state.testsView.selectedStepId = steps[2].id
        open(suiteId, caseId)

        rule.onNodeWithText("Examples (0)").assertExists()
        assertTrue(rule.onAllNodesWithText("None.").fetchSemanticsNodes().isEmpty(), "closed: no 'None.' hint")
        assertEquals(0, thumbnails().fetchSemanticsNodes().size)
    }

    @Test
    fun collapsingOneStepsExamplesDoesNotHideTheExamplesOfAnotherStep() {
        val (suiteId, caseId, steps) = seed()
        state.testsView.selectedStepId = steps[0].id
        open(suiteId, caseId)

        rule.onNodeWithText("Examples (1)").performScrollTo().performClick()
        rule.waitForIdle()
        assertFalse(state.testsView.examplesExpanded(steps[0].id, hasExamples = true))
        rule.onNodeWithText("Golden screenshot").assertDoesNotExist()

        state.testsView.selectedStepId = steps[1].id
        rule.waitForIdle()
        rule.onNodeWithText("Golden screenshot").assertExists()
        assertTrue(state.testsView.examplesExpanded(steps[1].id, hasExamples = true))

        // Back on the first step the user's collapse is still remembered (in memory), and the strip is still there.
        state.testsView.selectedStepId = steps[0].id
        rule.waitForIdle()
        rule.onNodeWithText("Golden screenshot").assertDoesNotExist()
        assertEquals(1, thumbnails().fetchSemanticsNodes().size)
    }

    @Test
    fun clickingAThumbnailOpensTheCollapsedExamples() {
        val (suiteId, caseId, steps) = seed()
        state.testsView.selectedStepId = steps[0].id
        open(suiteId, caseId)
        rule.onNodeWithText("Examples (1)").performScrollTo().performClick()
        rule.waitForIdle()
        rule.onNodeWithText("Golden screenshot").assertDoesNotExist()

        thumbnails()[0].performClick()
        rule.waitForIdle()

        rule.onNodeWithText("Golden screenshot").assertExists()
        assertTrue(state.testsView.examplesExpanded(steps[0].id, hasExamples = false))
    }

    @Test
    fun aRecordedStepShowsItsStoredBeforeAndAfterScreens() {
        state.updateSettings { it.copy(testing = it.testing.copy(keepRecordingEvidence = true)) }
        val suiteId = (state.createTestSuite("Shop") as StoreResult.Ok).value.id
        val caseId = (state.createTestCase(suiteId, TestCase("", "Search")) as StoreResult.Ok).value.id
        val session = recordedShopSession("lofi")
        check(state.registerTestStepRecording(session, suiteId, caseId) is StoreResult.Ok)
        val rows = session.snapshot.value.steps
        val made = (kotlinx.coroutines.runBlocking { state.applyTestStepRecording(session.id, rows.map { it.action to "It works" }, null, emptySet(), rows.map { it.id }) } as StoreResult.Ok).value

        val screens = assertNotNull(state.recordedStepScreens(suiteId, made[0].recordingRef!!, made[0].id), "the first step has stored screens")
        assertTrue(screens.before!!.contentEquals(SHOP_BEFORE_IMAGE), "its 'before' is the start screen reading's image")

        // At least one recorded step also kept the reading taken after it; it shows both.
        val withAfter = made.first { state.recordedStepScreens(suiteId, it.recordingRef!!, it.id)?.after != null }
        state.testsView.selectedStepId = withAfter.id
        open(suiteId, caseId)
        rule.waitUntilAtLeastOneExists(hasText("Recorded screens"), 10_000)
        rule.onNodeWithText("After").assertExists()
        // The fixture images are real JPEGs, so the bounded decoder shows them (otherwise the tile says "no preview").
        assertTrue(com.indagium.ui.decodeBoundedPreviewImage(SHOP_BEFORE_IMAGE) != null)
        rule.waitUntilAtLeastOneExists(hasContentDescription("Recorded screen: After"), 10_000)
    }

    @Test
    fun aStepWithoutARecordingShowsNoRecordedScreens() {
        val (suiteId, caseId, steps) = seed()
        state.testsView.selectedStepId = steps[0].id
        open(suiteId, caseId)

        rule.onNodeWithText("Recorded screens").assertDoesNotExist()
    }
}
