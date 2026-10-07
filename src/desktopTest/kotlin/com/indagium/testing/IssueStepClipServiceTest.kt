package com.indagium.testing

import com.indagium.capture.CaptureDevice
import com.indagium.capture.CaptureSession
import com.indagium.capture.CaptureSettings
import com.indagium.capture.CaptureStatus
import com.indagium.capture.CaptureVideoClip
import com.indagium.capture.CaptureVideoCoverageProbe
import com.indagium.capture.CaptureVideoExporter
import com.indagium.capture.sessionJson
import com.indagium.testing.model.CaseResult
import com.indagium.testing.model.CaseStatus
import com.indagium.testing.model.IssueAttachmentKind
import com.indagium.testing.model.IssueDraft
import com.indagium.testing.model.IssueEnvironment
import com.indagium.testing.model.IssueSource
import com.indagium.testing.model.LaneConfig
import com.indagium.testing.model.LaneKind
import com.indagium.testing.model.LaneResult
import com.indagium.testing.model.RunConfig
import com.indagium.testing.model.RunStatus
import com.indagium.testing.model.StepResult
import com.indagium.testing.model.StepStatus
import com.indagium.testing.model.TestRun
import com.indagium.testing.run.defaultIssueStepClipRequest
import com.indagium.testing.run.defaultIssueStepClipWindow
import com.indagium.testing.run.exportIssueStepClip
import com.indagium.testing.store.IssueStore
import com.indagium.testing.store.StoreResult
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.junit.Assume.assumeTrue
import java.io.File
import java.nio.file.Files
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.io.path.createTempDirectory
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertTrue

class IssueStepClipServiceTest {
    @Test
    fun defaultClipPadsFailureAndClampsToVideoCoverage() {
        val request = defaultIssueStepClipRequest(
            captureStartedEpochMs = 1_791_276_471_872,
            videoStartElapsedMs = 4_591,
            captureElapsedMs = 14_516,
            stepStartedEpochMs = 1_791_276_479_738,
            stepDurationMs = 1_650,
        )
        assertEquals(0, request.startMs)
        assertEquals(9_925, request.endMs)
    }

    @Test
    fun defaultClipRejectsARecordingThatEndsBeforeTheFailedStep() {
        assertFailsWith<IllegalArgumentException> {
            defaultIssueStepClipRequest(1_000, 2_000, 2_500, 10_000, 1_000)
        }
    }

    @Test
    fun defaultClipRejectsAFailedStepEntirelyBeforeVideoCoverage() {
        assertFailsWith<IllegalArgumentException> {
            defaultIssueStepClipRequest(1_000, 20_000, 30_000, 1_000, 500)
        }
    }

    @Test
    fun exportUsesRequestedBoundsStoresSelectableClipAndKeepsOriginalRecording() = kotlinx.coroutines.runBlocking {
        val root = createTempDirectory("issue-step-clip").toFile()
        try {
            val fixture = fixture(root)
            val sourceVideo = fixture.session.videoFile
            sourceVideo.parentFile.mkdirs()
            sourceVideo.writeBytes(byteArrayOf(7, 8, 9))
            val exporter = CaptureVideoExporter { source, destination, start, end ->
                assertEquals(sourceVideo.canonicalFile, source.canonicalFile)
                assertEquals(0, start)
                assertEquals(9_925, end)
                destination.writeBytes(byteArrayOf(1, 2, 3, 4))
                CaptureVideoClip(start, end, end - start)
            }
            val result = exportIssueStepClip(fixture.store, fixture.issue.id, fixture.run, fixture.runDir, exporter = exporter).getOrThrow()
            val clip = result.issue.draft.attachments.single { it.kind == IssueAttachmentKind.VIDEO_CLIP }
            assertEquals(0, result.actualStartMs)
            assertEquals(9_925, result.actualEndMs)
            assertTrue(clip.include)
            assertTrue(fixture.store.attachmentFile(result.issue, clip)!!.isFile)
            assertTrue(sourceVideo.isFile, "the original lane recording remains untouched")
            assertTrue(File(fixture.store.issueDir(fixture.issue.id).path).listFiles().orEmpty().none { it.name.startsWith(".clip-") })
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun videoOnlyRunCanExportAfterItsLogFileWasRemoved() = kotlinx.coroutines.runBlocking {
        val root = createTempDirectory("issue-step-clip-video-only").toFile()
        try {
            val fixture = fixture(root)
            fixture.session.logFile.delete()
            val sourceVideo = fixture.session.videoFile
            sourceVideo.parentFile.mkdirs()
            sourceVideo.writeBytes(byteArrayOf(7, 8, 9))
            val exporter = CaptureVideoExporter { _, destination, start, end ->
                destination.writeBytes(byteArrayOf(1, 2))
                CaptureVideoClip(start, end, end - start)
            }
            val result = exportIssueStepClip(fixture.store, fixture.issue.id, fixture.run, fixture.runDir, exporter = exporter).getOrThrow()
            assertTrue(result.issue.draft.attachments.any { it.kind == IssueAttachmentKind.VIDEO_CLIP })
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun explicitBoundsDoNotRequireTheFailedStepDefaultToBeAvailable() = kotlinx.coroutines.runBlocking {
        val root = createTempDirectory("issue-step-clip-explicit").toFile()
        try {
            val fixture = fixture(root)
            fixture.session.videoFile.parentFile.mkdirs()
            fixture.session.videoFile.writeBytes(byteArrayOf(7, 8, 9))
            val shiftedLane = fixture.run.lanes.single().copy(
                cases = fixture.run.lanes.single().cases.map { case ->
                    case.copy(steps = case.steps.map { it.copy(startedAt = 1_000) })
                },
            )
            val shiftedRun = fixture.run.copy(lanes = listOf(shiftedLane))
            val exporter = object : CaptureVideoExporter, CaptureVideoCoverageProbe {
                override fun export(source: File, destination: File, requestedStartMs: Long, requestedEndMs: Long): CaptureVideoClip {
                    assertEquals(100, requestedStartMs)
                    assertEquals(300, requestedEndMs)
                    destination.writeBytes(byteArrayOf(1, 2))
                    return CaptureVideoClip(requestedStartMs, requestedEndMs, requestedEndMs - requestedStartMs)
                }

                override fun coverageEndMs(source: File, requestedStartMs: Long, requestedEndMs: Long): Long = requestedEndMs
            }
            val exported = exportIssueStepClip(
                fixture.store, fixture.issue.id, shiftedRun, fixture.runDir,
                requestedStartMs = 100, requestedEndMs = 300, exporter = exporter,
            ).getOrThrow()
            assertEquals(100, exported.actualStartMs)
            assertEquals(300, exported.actualEndMs)
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun defaultWindowRejectsVideoSymlinkEscapingRunFolder() = kotlinx.coroutines.runBlocking {
        val root = createTempDirectory("issue-step-clip-escape").toFile()
        try {
            val fixture = fixture(root)
            val outside = File(root.parentFile, "outside-${root.name}.mkv").apply { writeBytes(byteArrayOf(1, 2, 3)) }
            fixture.session.videoFile.parentFile.mkdirs()
            val link = try {
                Result.success(Files.createSymbolicLink(fixture.session.videoFile.toPath(), outside.toPath()))
            } catch (unsupported: UnsupportedOperationException) {
                Result.failure<java.nio.file.Path>(unsupported)
            } catch (unsupported: java.nio.file.FileSystemException) {
                Result.failure<java.nio.file.Path>(unsupported)
            }
            assumeTrue("The host does not support test symlinks: ${link.exceptionOrNull()?.message}", link.isSuccess)
            link.getOrThrow()
            val failure = defaultIssueStepClipWindow(fixture.run, fixture.runDir, fixture.issue.source)
            assertTrue(failure.isFailure)
            outside.delete()
            Unit
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun defaultCoverageProbeIsInterruptedWhenCallerCancels() = kotlinx.coroutines.runBlocking {
        val root = createTempDirectory("issue-step-clip-probe-cancel").toFile()
        val probeStarted = CountDownLatch(1)
        try {
            val fixture = fixture(root)
            fixture.session.videoFile.parentFile.mkdirs()
            fixture.session.videoFile.writeBytes(byteArrayOf(7, 8, 9))
            val job = launch(Dispatchers.Default) {
                defaultIssueStepClipWindow(fixture.run, fixture.runDir, fixture.issue.source) { _, _, _ ->
                    probeStarted.countDown()
                    Thread.sleep(10_000)
                    9_925
                }
            }
            assertTrue(withContext(Dispatchers.IO) { probeStarted.await(5, TimeUnit.SECONDS) })
            job.cancelAndJoin()
            assertTrue(job.isCancelled)
        } finally {
            root.deleteRecursively()
        }
    }

    private data class Fixture(
        val store: IssueStore,
        val issue: com.indagium.testing.model.IssueRecord,
        val run: TestRun,
        val runDir: File,
        val session: CaptureSession,
    )

    private fun fixture(root: File): Fixture {
        val runDir = File(root, "run").apply { mkdirs() }
        val sessionDir = File(runDir, "lanes/lane-1/capture/session-1").apply { mkdirs() }
        val log = File(sessionDir, "logs/logcat.log").apply { parentFile.mkdirs(); writeText("") }
        val session = CaptureSession(
            id = "session-1", directory = sessionDir,
            device = CaptureDevice("SERIAL-1", "device", "fixture-device", false),
            settings = CaptureSettings(recordVideo = true),
            startedEpochMs = 1_791_276_471_872,
            elapsedMs = 14_516,
            status = CaptureStatus.STOPPED,
            videoStartElapsedMs = 4_591,
        )
        File(sessionDir, "session.json").writeText(sessionJson(session))
        val definition = step("Crash on launch").copy(id = "step-1")
        val result = StepResult(
            definition.id, 1, definition.action, status = StepStatus.FAIL,
            startedAt = 1_791_276_479_738, durationMs = 1_650,
        )
        val testCase = caseOf("Checkout", definition).copy(id = "case-1")
        val suite = suiteOf(testCase).copy(id = "suite-1")
        val lane = LaneConfig("lane-1", LaneKind.EXTERNAL, null, "SERIAL-1")
        val run = TestRun(
            id = "run-1", suite = suite, scripts = emptyList(), sharedSteps = emptyList(),
            config = RunConfig(suite.id, listOf(testCase.id), listOf(lane)),
            lanes = listOf(LaneResult(
                lane.id, lane, RunStatus.FAILED,
                cases = listOf(CaseResult(testCase.id, testCase.name, status = CaseStatus.FAIL, steps = listOf(result))),
                logPath = log.relativeTo(runDir).invariantSeparatorsPath,
            )), status = RunStatus.FAILED,
        )
        val store = IssueStore(File(root, "issues"))
        val issue = assertIs<StoreResult.Ok<com.indagium.testing.model.IssueRecord>>(
            store.create(
                IssueDraft("Synthetic failure", environment = IssueEnvironment(deviceSerial = lane.deviceSerial)),
                IssueSource(run.id, lane.id, suite.id, testCase.id, definition.id, caseName = testCase.name, stepNumber = 1),
            ),
        ).value
        return Fixture(store, issue, run, runDir, session)
    }
}
