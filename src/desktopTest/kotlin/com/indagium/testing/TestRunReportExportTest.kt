package com.indagium.testing

import com.indagium.testing.model.EvidenceFlags
import com.indagium.testing.model.LaneConfig
import com.indagium.testing.model.LaneKind
import com.indagium.testing.model.LaneResult
import com.indagium.testing.model.RunConfig
import com.indagium.testing.model.RunStatus
import com.indagium.testing.model.TestRun
import com.indagium.testing.run.TestRunReportFormat
import com.indagium.testing.run.exportTestRunReport
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.io.File
import java.util.zip.ZipFile
import kotlin.io.path.createTempDirectory
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class TestRunReportExportTest {
    @Test
    fun jsonMarkdownAndSelectedEvidenceZipUseSafeRunRelativePaths() = runBlocking {
        val root = createTempDirectory("run-export").toFile()
        try {
            val runDir = File(root, "run").apply { mkdirs() }
            val artifact = File(runDir, "lanes/lane-1/screens/shot.png").apply { parentFile.mkdirs(); writeBytes(byteArrayOf(1, 2, 3)) }
            val run = sampleRun()
            val json = File(root, "report.json")
            val markdown = File(root, "report.md")
            val zip = File(root, "report.zip")
            assertTrue(exportTestRunReport(run, runDir, json, TestRunReportFormat.JSON).isSuccess)
            assertTrue(exportTestRunReport(run, runDir, markdown, TestRunReportFormat.MARKDOWN).isSuccess)
            assertTrue(markdown.readText().contains("Synthetic suite"))
            assertTrue(exportTestRunReport(run, runDir, zip, TestRunReportFormat.EVIDENCE_ZIP, listOf("lanes/lane-1/screens/shot.png")).isSuccess)
            ZipFile(zip).use { archive ->
                assertTrue(archive.getEntry("report.json") != null)
                assertTrue(archive.getEntry("report.md") != null)
                assertEquals(3L, archive.getEntry("evidence/lanes/lane-1/screens/shot.png").size)
            }
            assertTrue(exportTestRunReport(run, runDir, File(root, "unsafe.zip"), TestRunReportFormat.EVIDENCE_ZIP, listOf("../outside")).isFailure)
            listOf("C:foo", "C:/foo", "//server/share/file", "nested/file:stream").forEach { unsafe ->
                assertTrue(
                    exportTestRunReport(run, runDir, File(root, "unsafe-${unsafe.hashCode()}.zip"), TestRunReportFormat.EVIDENCE_ZIP, listOf(unsafe)).isFailure,
                    "unsafe evidence path must be rejected: $unsafe",
                )
            }
            assertTrue(artifact.exists())
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun cancellationDoesNotPublishAPartialZipOrLeaveItsStagingFile() = runBlocking {
        val root = createTempDirectory("run-export-cancel").toFile()
        try {
            val runDir = File(root, "run").apply { mkdirs() }
            File(runDir, "large.bin").writeBytes(ByteArray(2 * 1024 * 1024) { 7 })
            val destination = File(root, "cancelled.zip")
            try {
                exportTestRunReport(
                    sampleRun(), runDir, destination, TestRunReportFormat.EVIDENCE_ZIP, listOf("large.bin"),
                ) { throw CancellationException("cancel export") }
                error("expected cancellation")
            } catch (_: CancellationException) {
                // Cancellation is propagated so the UI and MCP caller can stop the export.
            }
            assertFalse(destination.exists())
            assertTrue(root.listFiles().orEmpty().none { it.name.startsWith(".test-run-export-") })
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun fullExternalActivitySurvivesLiveCacheRotationInJsonAndMarkdownExports() = runBlocking {
        val root = createTempDirectory("run-export-activity").toFile()
        try {
            val runDir = File(root, "run").apply { mkdirs() }
            val activity = File(runDir, "lanes/lane-1/activity.jsonl").apply { parentFile.mkdirs() }
            activity.writeText((0 until 320).joinToString("\n") { "{\"sequence\":$it,\"tool\":\"fixture\"}" })
            val base = sampleRun()
            val run = base.copy(lanes = base.lanes.map { it.copy(toolActivityPath = "lanes/lane-1/activity.jsonl") })
            val json = File(root, "complete.json")
            val markdown = File(root, "complete.md")
            assertTrue(exportTestRunReport(run, runDir, json, TestRunReportFormat.JSON).isSuccess)
            assertTrue(exportTestRunReport(run, runDir, markdown, TestRunReportFormat.MARKDOWN).isSuccess)
            val rows = Json.parseToJsonElement(json.readText()).jsonObject["laneActivity"]!!.jsonObject["lane-1"]!!.jsonArray
            assertEquals(320, rows.size)
            assertEquals(0, rows.first().jsonObject["sequence"]!!.jsonPrimitive.content.toInt())
            assertEquals(319, rows.last().jsonObject["sequence"]!!.jsonPrimitive.content.toInt())
            assertTrue(markdown.readText().contains("\"sequence\":0"))
            assertTrue(markdown.readText().contains("\"sequence\":319"))
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun cancellingCallerDuringEvidenceCopyLeavesDestinationAndPriorFileUntouched() = runBlocking {
        val root = createTempDirectory("run-export-caller-cancel").toFile()
        try {
            val runDir = File(root, "run").apply { mkdirs() }
            File(runDir, "large.bin").writeBytes(ByteArray(4 * 1024 * 1024) { 3 })
            val destination = File(root, "existing.zip").apply { writeText("prior") }
            lateinit var job: Job
            job = launch(start = CoroutineStart.LAZY) {
                exportTestRunReport(
                    sampleRun(), runDir, destination, TestRunReportFormat.EVIDENCE_ZIP,
                    listOf("large.bin"), overwrite = true,
                ) { job.cancel() }
            }
            job.start()
            job.join()
            assertTrue(job.isCancelled)
            assertEquals("prior", destination.readText())
            assertTrue(root.listFiles().orEmpty().none { it.name.startsWith(".test-run-export-") })
        } finally {
            root.deleteRecursively()
        }
    }

    private fun sampleRun(): TestRun {
        val suite = suiteOf(caseOf("Exported", step("Open screen")))
        val lane = LaneConfig("lane-1", LaneKind.EXTERNAL, null, "fixture")
        return TestRun(
            id = "run-1", suite = suite, scripts = emptyList(), sharedSteps = emptyList(),
            config = RunConfig(suite.id, null, listOf(lane), evidence = EvidenceFlags()),
            lanes = listOf(LaneResult(lane.id, lane, RunStatus.FAILED)), status = RunStatus.FAILED,
        )
    }
}
