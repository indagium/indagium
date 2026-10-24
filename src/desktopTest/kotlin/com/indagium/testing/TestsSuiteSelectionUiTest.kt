@file:Suppress("MagicNumber")

package com.indagium.testing

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.pressKey
import androidx.compose.ui.test.waitUntilAtLeastOneExists
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.indagium.edition.Edition
import com.indagium.testing.model.CaseResult
import com.indagium.testing.model.CaseStatus
import com.indagium.testing.model.LaneConfig
import com.indagium.testing.model.LaneKind
import com.indagium.testing.model.LaneResult
import com.indagium.testing.model.RunConfig
import com.indagium.testing.model.RunStatus
import com.indagium.testing.model.StepResult
import com.indagium.testing.model.StepStatus
import com.indagium.testing.model.TestCase
import com.indagium.testing.model.TestRun
import com.indagium.testing.model.TestSuite
import com.indagium.testing.model.newRunId
import com.indagium.testing.store.StoreResult
import com.indagium.testing.store.TestRunStore
import com.indagium.ui.AppState
import com.indagium.ui.RunDialogTarget
import com.indagium.ui.TestsWorkspace
import org.junit.After
import org.junit.Rule
import org.junit.Test
import java.io.File
import kotlin.io.path.createTempDirectory
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Row selection in the suite's Cases tab: keys, the detail panel, the locked-cases notice, the nav usage meter and the last-runs squares. */
@OptIn(ExperimentalTestApi::class)
class TestsSuiteSelectionUiTest {
    @get:Rule
    val rule = createComposeRule()

    private val dir: File = createTempDirectory("tests-suite-selection-ui").toFile()
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

    private fun suiteWithNamedCases(vararg names: String): TestSuite {
        val suite = (state.createTestSuite("Smoke") as StoreResult.Ok).value
        names.forEach { state.createTestCase(suite.id, TestCase("", it)) }
        return state.testLibrary.suite(suite.id)!!
    }

    private fun open(suite: TestSuite, width: Dp = 1500.dp) {
        state.testsView.selectedSuiteId = suite.id
        rule.setContent { Box(Modifier.size(width, 900.dp)) { TestsWorkspace(state) } }
        rule.waitForIdle()
    }

    private fun press(key: Key) {
        rule.onRoot().performKeyInput { pressKey(key) }
        rule.waitForIdle()
    }

    private fun saveRun(suite: TestSuite, status: CaseStatus, createdAt: Long) {
        val lane = LaneConfig("lane-a", LaneKind.EXTERNAL, deviceSerial = "pixel-8")
        val runStatus = if (status == CaseStatus.PASS) RunStatus.PASSED else RunStatus.FAILED
        val stepStatus = if (status == CaseStatus.PASS) StepStatus.PASS else StepStatus.FAIL
        val run = TestRun(
            id = newRunId(), suite = suite, scripts = emptyList(), sharedSteps = emptyList(),
            config = RunConfig(suite.id, null, listOf(lane)),
            lanes = listOf(
                LaneResult(
                    lane.id, lane, runStatus,
                    suite.cases.map { case ->
                        CaseResult(
                            case.id, case.name, 1, status, listOf(StepResult("step-${case.id}", 1, "Act", status = stepStatus)),
                            startedAt = 1_000L, finishedAt = 49_000L,
                        )
                    },
                    startedAt = 1_000L,
                ),
            ),
            status = runStatus, createdAt = createdAt, finishedAt = createdAt + 50_000L,
        )
        TestRunStore { state.activeTestFolders().second }.save(run).getOrThrow()
    }

    @Test
    fun arrowKeysSelectEnterOpensAndEscapeClearsTheSelection() {
        val suite = suiteWithNamedCases("Login", "Checkout", "Logout")
        val (first, second, third) = suite.cases.map { it.id }
        open(suite)

        press(Key.DirectionDown)
        assertEquals(first, state.testsView.previewCaseId, "Down with nothing selected picks the first case")
        rule.onNodeWithText("Case 1").assertExists()
        press(Key.DirectionDown)
        assertEquals(second, state.testsView.previewCaseId)
        press(Key.DirectionDown)
        press(Key.DirectionDown)
        assertEquals(third, state.testsView.previewCaseId, "clamped at the last case")
        press(Key.DirectionUp)
        assertEquals(second, state.testsView.previewCaseId)

        press(Key.Escape)
        assertNull(state.testsView.previewCaseId)
        rule.onNodeWithText("Edit steps").assertDoesNotExist()
        assertNull(state.testsView.selectedCaseId)

        press(Key.DirectionUp)
        assertEquals(third, state.testsView.previewCaseId, "Up with nothing selected picks the last case")
        press(Key.Enter)
        assertEquals(third, state.testsView.selectedCaseId, "Enter opens the selected case")
    }

    @Test
    fun thePanelsRunCaseOpensTheRunDialogForThatCase() {
        val suite = suiteWithNamedCases("Login", "Checkout")
        val checkout = suite.cases[1]
        open(suite)
        state.testsView.previewCaseId = checkout.id
        rule.waitForIdle()

        rule.onNodeWithText("Run case").performClick()
        rule.waitForIdle()

        assertEquals(RunDialogTarget(suite.id, checkout.id), state.testsView.runDialog)
    }

    @Test
    fun underFreeTheNoticeNamesTheLockedCasesAndTheCrossHidesIt() {
        val suite = suiteWithNamedCases("A", "B", "C", "D", "E", "F", "G")
        assertTrue(state.editionService.setForDev(Edition.FREE))
        open(suite)

        val text = "Cases 6–7 are read-only on Free. You can still view, export, reorder and delete them."
        rule.onNodeWithText(text).assertExists()

        rule.onNodeWithContentDescription("Dismiss locked cases notice").performClick()
        rule.waitForIdle()

        rule.onNodeWithText(text).assertDoesNotExist()
        assertTrue(state.testsView.lockedCasesNoticeDismissed)
    }

    @Test
    fun noNoticeAndNoMeterOnUnlimitedWhichKeepsTheBadgeAndCaseCount() {
        val suite = suiteWithNamedCases("A", "B", "C", "D", "E", "F", "G")
        open(suite)

        assertTrue(rule.onAllNodesWithText("read-only on", substring = true).fetchSemanticsNodes().isEmpty())
        rule.onNodeWithText(state.editionService.current.value.label).assertExists()
        rule.onNodeWithText("7 cases").assertExists()
        assertTrue(rule.onAllNodesWithText("of 5 cases", substring = true).fetchSemanticsNodes().isEmpty())
    }

    @Test
    fun underFreeTheNavFooterShowsTheUsageMeterInsteadOfTheBadge() {
        val suite = suiteWithNamedCases("A", "B", "C", "D", "E", "F", "G")
        assertTrue(state.editionService.setForDev(Edition.FREE))
        open(suite)

        rule.onNodeWithText("Free · 1 of 1 suite · 5 of 5 cases").assertExists()
        rule.onNodeWithText("2 cases read-only").assertExists()
        rule.onNodeWithText("7 cases").assertDoesNotExist()
    }

    @Test
    fun theLastRunsColumnDrawsOneDescribedSquarePerRunOldestFirst() {
        val suite = suiteWithNamedCases("Login")
        saveRun(suite, CaseStatus.PASS, createdAt = 1_000_000L)
        saveRun(suite, CaseStatus.FAIL, createdAt = 2_000_000L)
        open(suite)

        rule.waitUntilAtLeastOneExists(hasContentDescription("#2 failed"), timeoutMillis = 10_000)
        rule.onNodeWithText("Last 6 runs").assertExists()
        assertTrue(rule.onAllNodes(hasContentDescription("#1 passed")).fetchSemanticsNodes().isNotEmpty())

        state.testsView.previewCaseId = suite.cases.single().id
        rule.waitForIdle()
        rule.waitUntilAtLeastOneExists(hasText("Open report →"), timeoutMillis = 10_000)
        rule.onNodeWithText("#2", substring = true).assertExists()
    }
}
