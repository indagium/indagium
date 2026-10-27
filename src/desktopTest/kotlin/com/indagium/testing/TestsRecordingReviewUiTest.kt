@file:Suppress("MagicNumber")

package com.indagium.testing

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.unit.dp
import com.indagium.capture.mirror.MirrorTouchAction
import com.indagium.testing.authoring.InputSource
import com.indagium.testing.authoring.TestStepRecordingSession
import com.indagium.testing.authoring.touchCommand
import com.indagium.testing.model.TestCase
import com.indagium.testing.store.StoreResult
import com.indagium.ui.AppState
import com.indagium.ui.TestsNav
import com.indagium.ui.TestsWorkspace
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Rule
import org.junit.Test
import java.io.File
import kotlin.io.path.createTempDirectory
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** A stopped recording remains pinned to its original case while the user browses elsewhere and reviews/applies it. */
@OptIn(ExperimentalTestApi::class)
class TestsRecordingReviewUiTest {
    @get:Rule
    val rule = createComposeRule()

    private val dir = createTempDirectory("tests-recording-review-ui").toFile()
    private val state = AppState(
        autosaveFile = File(dir, "state.cache"),
        restoreOnCreate = false,
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

    @Test
    fun reviewAndApplyStayPinnedAfterBrowsingAnotherCaseAndScripts() {
        rule.setTestsViewport(1600, 900)
        val suite = (state.createTestSuite("Pinned suite") as StoreResult.Ok).value
        val original = (state.createTestCase(suite.id, TestCase("", "Original case")) as StoreResult.Ok).value
        val elsewhere = (state.createTestCase(suite.id, TestCase("", "Other case")) as StoreResult.Ok).value
        val session = TestStepRecordingSession("fixture-device")
        session.accept(touchCommand(MirrorTouchAction.DOWN, 540, 1200), null, 960, InputSource.DEVICE)
        session.accept(touchCommand(MirrorTouchAction.UP, 540, 1200), null, 1_000, InputSource.DEVICE)
        runBlocking { session.stopAndDrain() }
        val reviewed = session.snapshot.value.steps.map { it.copy(action = "Tap the saved control", expected = "The control responds") }
        assertTrue(session.replaceSteps(reviewed))
        assertTrue(state.registerTestStepRecording(session, suite.id, original.id) is StoreResult.Ok)

        state.testsView.selectedSuiteId = suite.id
        state.testsView.selectedCaseId = original.id
        rule.setContent {
            Box(Modifier.requiredSize(1600.dp, 900.dp)) { TestsWorkspace(state) }
        }
        rule.waitForIdle()

        rule.runOnIdle {
            state.testsView.selectedCaseId = elsewhere.id
            state.testsView.nav = TestsNav.Scripts
        }
        rule.waitForIdle()
        rule.onNodeWithText("Pinned suite / Original case").assertExists()
        rule.onNodeWithText("Browsing another Tests area; this recording stays with its original target.").assertExists()

        rule.onNodeWithText("Review draft").performSemanticsAction(SemanticsActions.OnClick)
        rule.waitForIdle()
        rule.onNode(
            hasText("Tap the saved control") and
                hasContentDescription("Drag to reorder", substring = true) and
                hasAnyAncestor(hasTestTag("tests-tile-card-execution")),
        ).assertExists()
        rule.onNodeWithText("Apply to original case").assertExists()
        rule.onNodeWithText("Recorded evidence").assertExists()
        rule.runOnIdle {
            state.testsView.nav = TestsNav.Suites
            state.testsView.selectedCaseId = elsewhere.id
        }
        rule.waitForIdle()
        assertTrue(!state.testsView.recordingReviewOpen, "normal selection restores contextual Details and Evidence")
        assertEquals(session.id, state.testStepRecordingSession?.id)
        assertEquals(suite.id to original.id, state.testStepRecordingTarget)
        rule.runOnIdle { state.testsView.nav = TestsNav.Scripts }
        rule.waitForIdle()
        rule.onNodeWithText("Review draft").performSemanticsAction(SemanticsActions.OnClick)
        rule.waitForIdle()
        rule.onNodeWithText("Apply to original case").performSemanticsAction(SemanticsActions.OnClick)
        rule.waitUntil(10_000) { state.testStepRecordingSession == null }

        val updatedSuite = state.testLibrary.suite(suite.id)!!
        assertEquals("Tap the saved control", updatedSuite.cases.first { it.id == original.id }.steps.single().action)
        assertTrue(updatedSuite.cases.first { it.id == elsewhere.id }.steps.isEmpty(), "the browsed case must not receive the recording")
        assertEquals(TestsNav.Scripts, state.testsView.nav, "applying the recording must leave the current workspace selection alone")
        assertNull(state.testStepRecordingTarget)
    }
}
