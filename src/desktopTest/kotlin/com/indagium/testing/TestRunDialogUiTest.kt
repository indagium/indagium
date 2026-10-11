package com.indagium.testing

import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextReplacement
import androidx.compose.ui.test.waitUntilAtLeastOneExists
import com.indagium.model.AiProviderKind
import com.indagium.model.AiProviderProfile
import com.indagium.testing.model.LaneConfig
import com.indagium.testing.model.LaneKind
import com.indagium.testing.model.RunConfig
import com.indagium.testing.model.RunStatus
import com.indagium.testing.model.TestCase
import com.indagium.testing.model.TestRun
import com.indagium.testing.model.TestStep
import com.indagium.testing.model.newRunId
import com.indagium.testing.run.EngineTuning
import com.indagium.testing.run.LaneDeviceOpener
import com.indagium.testing.run.TestRunReportFormat
import com.indagium.testing.store.StoreResult
import com.indagium.testing.store.TestRunStore
import com.indagium.ui.AppState
import com.indagium.ui.LocalTestsUi
import com.indagium.ui.ReportExportDialog
import com.indagium.ui.RunDialogTarget
import com.indagium.ui.TestRunOverrides
import com.indagium.ui.TestsNav
import com.indagium.ui.TestsUi
import com.indagium.ui.TestsWorkspace
import com.indagium.ui.reportExportFileName
import org.junit.After
import org.junit.Rule
import org.junit.Test
import java.io.File
import kotlin.io.path.createTempDirectory
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

private const val WAIT_MS = 15_000L
private const val DIALOG_PROFILE_ID = "dialog-profile"

/** The run dialog (rows What / Where / Judge / Record / More) and the export dialog composed against a real AppState. */
@OptIn(ExperimentalTestApi::class)
class TestRunDialogUiTest {
    @get:Rule
    val rule = createComposeRule()

    private val dir: File = createTempDirectory("run-dialog-ui").toFile()
    private val adb = ScriptedAdbRunner()
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
        state.testsView.selectedRunId?.let { state.testRunCoordinator.cancel(it) }
        state.close()
        dir.deleteRecursively()
    }

    /** A suite "Shop suite" with the cases Login (2 steps) and Checkout (3 steps), and one AI profile. */
    private fun seed(): String {
        val profile = AiProviderProfile(DIALOG_PROFILE_ID, "Dialog model", "http://127.0.0.1:1/v1", "base-model", kind = AiProviderKind.OPENAI_COMPATIBLE)
        state.updateSettings { it.copy(aiProviderProfiles = listOf(profile)) }
        val suite = (state.createTestSuite("Shop suite") as StoreResult.Ok).value
        state.updateTestSuite(suite.id) { it.copy(targetPackage = "com.example.app") }
        listOf("Login" to 2, "Checkout" to 3).forEach { (name, steps) ->
            val case = (state.createTestCase(suite.id, TestCase("", name)) as StoreResult.Ok).value
            repeat(steps) { state.createTestStep(case.id, TestStep("", "Do $it", "Done")) }
        }
        return suite.id
    }

    private fun openDialog(target: RunDialogTarget) {
        rule.setContent { TestsWorkspace(state) }
        state.testsView.runDialog = target
        rule.waitUntilAtLeastOneExists(hasText("Start"), WAIT_MS)
    }

    private fun clickChange(index: Int = 0) {
        rule.onAllNodesWithText("Change ▸")[index].performScrollTo().performClick()
        rule.waitForIdle()
    }

    @Test
    fun theDialogIsRowsWithSummariesAndTheChecklistOpensInPlace() {
        val suiteId = seed()
        openDialog(RunDialogTarget(suiteId))

        listOf("What", "Where", "Judge").forEach { rule.onNodeWithText(it).assertExists() }
        rule.onNodeWithText("All 2 cases · 5 steps").assertExists()
        rule.onNodeWithText("Manage profiles…").assertExists()
        rule.onNodeWithText("+ Add lane").assertExists()
        rule.onNodeWithText("Refresh devices").assertExists()
        rule.onNodeWithText("video to file", substring = true).assertExists()
        rule.onNodeWithText("retries 0 · repeat 1×", substring = true).assertExists()
        assertEquals(2, rule.onAllNodesWithText("Change ▸").fetchSemanticsNodes().size, "Record and More both open in place")
        rule.onNodeWithText("3 step(s)").assertDoesNotExist()

        rule.onNodeWithText("All 2 cases · 5 steps").performClick()
        rule.waitUntilAtLeastOneExists(hasText("3 step(s)"), WAIT_MS)
        rule.onNodeWithText("2 step(s)").assertExists()
        rule.onNodeWithText("3 step(s)").performClick()
        rule.waitUntilAtLeastOneExists(hasText("Login · 2 steps"), WAIT_MS)
    }

    @Test
    fun theJudgeIsASegmentedControlAndRecordAndMoreOpenTheirControls() {
        val suiteId = seed()
        openDialog(RunDialogTarget(suiteId))

        listOf("No judge", "Failed steps only", "Every step").forEach { rule.onNodeWithText(it).assertExists() }
        rule.onNodeWithText("Judgment schedule").assertDoesNotExist()
        rule.onNodeWithText("Failed steps only").performClick()
        rule.waitUntilAtLeastOneExists(hasText("Judgment schedule"), WAIT_MS)

        rule.onNodeWithText("Open a live tab per lane").assertDoesNotExist()
        clickChange(0)
        rule.onNodeWithText("Open a live tab per lane").assertExists()
        rule.onNodeWithText("Done").assertExists()
        rule.onNodeWithText("Additional case attempts").assertDoesNotExist()

        clickChange(0)
        rule.onNodeWithText("Additional case attempts").assertExists()
        rule.onNodeWithText("Step retries").assertExists()
        rule.onNodeWithText("Tool calls per case").assertExists()
        rule.onNodeWithText("3×").performScrollTo().performClick()
        rule.waitUntilAtLeastOneExists(hasText("repeat 3×", substring = true), WAIT_MS)
    }

    @Test
    fun startStillStartsARunWithTheChosenConfig() {
        val suiteId = seed()
        state.testRunOverrides = TestRunOverrides(
            openDevice = LaneDeviceOpener { _, laneDir, _ -> openFixtureSession(adb, laneDir) },
            deviceProblem = { null },
            tuning = EngineTuning(persistDebounceMs = 50L),
        )
        val lane = LaneConfig(kind = LaneKind.EXTERNAL, profileId = null, deviceSerial = FIXTURE_SERIAL)
        openDialog(RunDialogTarget(suiteId, initialConfig = RunConfig(suiteId, null, listOf(lane))))

        rule.onNodeWithText("Every step").performClick()
        clickChange(1)
        rule.onNodeWithText("3×").performScrollTo().performClick()
        rule.onNodeWithText("Start").performClick()

        rule.waitUntil(WAIT_MS) { state.testsView.selectedRunId != null }
        assertEquals(TestsNav.Runs, state.testsView.nav)
        val run = assertNotNull(state.testRunCoordinator.run(state.testsView.selectedRunId!!))
        assertEquals(3, run.config.repeat)
        assertEquals("every_step", run.config.judgeMode)
        assertEquals(DIALOG_PROFILE_ID, run.config.judgeProfileId)
        assertEquals(LaneKind.EXTERNAL, run.config.lanes.single().kind)
        rule.waitUntil(WAIT_MS) { state.testsView.runDialog == null }
    }

    // ── Export dialog ────────────────────────────────────────────────

    private fun savedRun(): TestRun {
        val suite = suiteOf(caseOf("Login", step("Open the app")))
        val lane = LaneConfig("lane-a", LaneKind.EXTERNAL, deviceSerial = "pixel-8")
        val run = TestRun(
            id = newRunId(), suite = suite, scripts = emptyList(), sharedSteps = emptyList(),
            config = RunConfig(suite.id, null, listOf(lane)), lanes = emptyList(), status = RunStatus.PASSED, createdAt = 1_700_000_000_000L,
        )
        TestRunStore { state.activeTestFolders().second }.save(run).getOrThrow()
        return run
    }

    @Composable
    private fun WithTestsUi(content: @Composable () -> Unit) {
        val scope = rememberCoroutineScope()
        val ui = remember { TestsUi(state, state.testsView, FocusRequester(), scope) }
        CompositionLocalProvider(LocalTestsUi provides ui) { content() }
    }

    @Test
    fun theExportDialogHasSegmentedFormatsAPathFieldAndAChooseButton() {
        val run = savedRun()
        val picks = mutableListOf<Pair<String, File?>>()
        val chosen = File(dir, "out/report.json")
        rule.setContent {
            WithTestsUi {
                ReportExportDialog(run, File(dir, "run"), onDismiss = {}, chooseFile = { _, name, folder ->
                    picks += name to folder
                    chosen
                })
            }
        }

        listOf("JSON", "Markdown", "Evidence ZIP", "Choose…", "Close").forEach { rule.onNodeWithText(it).assertExists() }
        rule.onNodeWithText("EVIDENCE_ZIP").assertDoesNotExist()
        rule.onNodeWithText("Saved evidence").assertDoesNotExist()
        rule.onNodeWithText("Export").assertIsNotEnabled()

        rule.onNodeWithText("Choose…").performClick()
        rule.waitUntilAtLeastOneExists(hasText(chosen.absolutePath), WAIT_MS)
        assertEquals(reportExportFileName(run.suite.name, run.createdAt, TestRunReportFormat.JSON), picks.single().first, "the save prompt is prefilled")

        rule.onNodeWithText("Markdown").performClick()
        rule.waitUntilAtLeastOneExists(hasText(File(dir, "out/report.md").absolutePath), WAIT_MS)

        rule.onNodeWithText("Evidence ZIP").performClick()
        rule.waitUntilAtLeastOneExists(hasText("Saved evidence"), WAIT_MS)

        val typed = File(dir, "typed/report.zip").absolutePath
        rule.onNode(hasSetTextAction()).performTextReplacement(typed)
        rule.waitUntilAtLeastOneExists(hasText(typed), WAIT_MS)
    }

    @Test
    fun exportWritesTheChosenFileAndTheCloseButtonDismisses() {
        val run = savedRun()
        var dismissed = 0
        val target = File(dir, "out/report.json")
        rule.setContent {
            WithTestsUi { ReportExportDialog(run, File(dir, "run"), onDismiss = { dismissed++ }, chooseFile = { _, _, _ -> target }) }
        }

        rule.onNodeWithText("Choose…").performClick()
        rule.waitUntilAtLeastOneExists(hasText(target.absolutePath), WAIT_MS)
        rule.onNodeWithText("Export").performClick()
        rule.waitUntil(WAIT_MS) { target.isFile }
        rule.waitUntilAtLeastOneExists(hasText("Saved ${target.absolutePath}", substring = true), WAIT_MS)
        assertTrue(target.readText().contains(run.id))

        rule.onNodeWithText("Close").performClick()
        assertEquals(1, dismissed)
    }
}
