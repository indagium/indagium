package com.indagium.testing

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.foundation.layout.wrapContentSize
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.waitUntilAtLeastOneExists
import androidx.compose.ui.unit.dp
import com.indagium.testing.model.CaseResult
import com.indagium.testing.model.CaseStatus
import com.indagium.testing.model.LaneConfig
import com.indagium.testing.model.LaneKind
import com.indagium.testing.model.LaneResult
import com.indagium.testing.model.RunConfig
import com.indagium.testing.model.RunStatus
import com.indagium.testing.model.TestRun
import com.indagium.testing.model.newRunId
import com.indagium.testing.store.TestRunStore
import com.indagium.ui.AppState
import com.indagium.ui.TestsNav
import com.indagium.ui.TestsWorkspace
import org.junit.After
import org.junit.Rule
import org.junit.Test
import java.io.File
import kotlin.io.path.createTempDirectory

/** Keeps run-level context useful in Details when a finished run has no selected step. */
@OptIn(ExperimentalTestApi::class)
class TestsRunContextUiTest {
    @get:Rule
    val rule = createComposeRule()

    private val dir = createTempDirectory("tests-run-context-ui").toFile()
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
    fun passedRunWithoutSelectedStepShowsRunScopeInDetails() {
        val suite = suiteWithCases("Empty run", 1)
        val case = suite.cases.single()
        val lane = LaneConfig(id = "lane-a", kind = LaneKind.EXTERNAL, deviceSerial = "pixel-8")
        val now = System.currentTimeMillis()
        val run = TestRun(
            id = newRunId(),
            suite = suite,
            scripts = emptyList(),
            sharedSteps = emptyList(),
            config = RunConfig(suite.id, null, listOf(lane)),
            lanes = listOf(
                LaneResult(
                    laneId = lane.id,
                    config = lane,
                    status = RunStatus.PASSED,
                    cases = listOf(CaseResult(case.id, case.name, status = CaseStatus.PASS, startedAt = now, finishedAt = now)),
                    startedAt = now,
                    finishedAt = now,
                ),
            ),
            status = RunStatus.PASSED,
            createdAt = now,
            startedAt = now,
            finishedAt = now,
        )
        TestRunStore { state.activeTestFolders().second }.save(run).getOrThrow()

        state.testsView.nav = TestsNav.Runs
        state.testsView.selectedRunId = run.id
        rule.setContent {
            Box(Modifier.wrapContentSize(Alignment.TopStart, unbounded = true).requiredSize(1500.dp, 900.dp)) {
                TestsWorkspace(state)
            }
        }
        rule.waitUntilAtLeastOneExists(androidx.compose.ui.test.hasText("Select a step in Results to inspect its checks, judge result, and actions."), 10_000)

        rule.onNodeWithText("Run scope").assertExists()
        rule.onNodeWithText("1 case · 1 lane").assertExists()
    }
}
