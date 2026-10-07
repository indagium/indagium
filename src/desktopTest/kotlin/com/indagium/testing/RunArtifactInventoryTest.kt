package com.indagium.testing

import com.indagium.testing.model.CaseResult
import com.indagium.testing.model.LaneConfig
import com.indagium.testing.model.LaneKind
import com.indagium.testing.model.LaneResult
import com.indagium.testing.model.RunConfig
import com.indagium.testing.model.RunStatus
import com.indagium.testing.model.StepResult
import com.indagium.testing.model.StepStatus
import com.indagium.testing.model.TestRun
import com.indagium.testing.model.TestSuite
import com.indagium.testing.run.availableRunArtifactPaths
import com.indagium.testing.run.isUnsafeRunArtifactPath
import com.indagium.testing.run.resolveRunArtifact
import com.indagium.testing.store.TEST_RUN_JUDGE_FILE_NAME
import java.io.File
import kotlin.io.path.createTempDirectory
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class RunArtifactInventoryTest {
    @Test
    fun resolverRejectsWindowsDriveRelativeAndAdsPathsOnEveryPlatform() {
        val root = createTempDirectory("run-artifact-paths").toFile()
        try {
            val runDir = File(root, "run").apply { mkdirs() }
            listOf("C:foo", "C:/foo", "//server/share/file", "nested/file:stream").forEach { path ->
                assertEquals(true, isUnsafeRunArtifactPath(path), "portable path policy must reject: $path")
                assertNull(resolveRunArtifact(runDir, path), "unsafe cross-platform artifact path: $path")
            }
            assertEquals(false, isUnsafeRunArtifactPath("lanes/lane-1/activity.jsonl"))
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun sharedInventoryIncludesTranscriptJudgeScreensLogsAndActivityWithSafeRelativePaths() {
        val root = createTempDirectory("run-artifact-inventory").toFile()
        try {
            val runDir = File(root, "run").apply { mkdirs() }
            val lane = LaneConfig(id = "lane-1", kind = LaneKind.EXTERNAL, deviceSerial = "fixture")
            val paths = listOf(
                "lanes/lane-1/logcat.log",
                "lanes/lane-1/transcript.jsonl",
                "lanes/lane-1/activity.jsonl",
                "lanes/lane-1/screens/step.png",
            )
            paths.forEach { relative -> File(runDir, relative).apply { parentFile.mkdirs(); writeBytes(byteArrayOf(1)) } }
            File(runDir, TEST_RUN_JUDGE_FILE_NAME).writeText("{}")
            val run = TestRun(
                id = "run-fixture",
                suite = TestSuite("suite-fixture", "Fixture"),
                scripts = emptyList(),
                sharedSteps = emptyList(),
                config = RunConfig("suite-fixture", lanes = listOf(lane)),
                lanes = listOf(LaneResult(
                    laneId = lane.id,
                    config = lane,
                    status = RunStatus.PASSED,
                    cases = listOf(CaseResult("case-1", "Case", status = com.indagium.testing.model.CaseStatus.PASS,
                        steps = listOf(StepResult("step-1", 1, "Tap", status = StepStatus.PASS, screenshotPath = paths[3])))),
                    logPath = paths[0],
                    transcriptPath = paths[1],
                    toolActivityPath = paths[2],
                )),
                status = RunStatus.PASSED,
            )

            assertEquals(paths.toSet() + TEST_RUN_JUDGE_FILE_NAME, availableRunArtifactPaths(run, runDir).toSet())
        } finally {
            root.deleteRecursively()
        }
    }
}
