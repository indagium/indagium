package com.indagium.testing

import com.indagium.capture.CaptureArchiveReader
import com.indagium.capture.parseMarkerHeader
import com.indagium.capture.reanchorImportedCaptureNotes
import com.indagium.model.AnnBlock
import com.indagium.testing.model.CaseStatus
import com.indagium.testing.model.IssueAttachmentKind
import com.indagium.testing.model.LaneConfig
import com.indagium.testing.model.LaneKind
import com.indagium.testing.model.RunConfig
import com.indagium.testing.model.RunStatus
import com.indagium.testing.model.TestCase
import com.indagium.testing.model.TestRun
import com.indagium.testing.model.TestStep
import com.indagium.testing.model.isPendingCaptureArchive
import com.indagium.testing.run.StartRunResult
import com.indagium.testing.store.StoreResult
import com.indagium.ui.TestRunOverrides
import com.indagium.ui.attachIssueCaptureArchive
import com.indagium.ui.buildIssueSeed
import com.indagium.ui.saveIssue
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import java.io.File
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

private const val RUN_WAIT_MS = 40_000L
private const val SERIAL_A = "SER-A"
private const val SERIAL_B = "SER-B"

/** Runs whose lanes record through real capture controllers (AppState's lane captures), with and without a tab per lane. */
class LaneRunCaptureTest {
    private lateinit var h: LaneCaptureHarness

    @BeforeTest
    fun setUp() {
        h = LaneCaptureHarness(SERIAL_A, SERIAL_B)
    }

    @AfterTest
    fun tearDown() {
        h.close()
    }

    private fun useAgents(vararg providers: Pair<String, com.indagium.ai.LlmProvider>) {
        h.state.updateSettings { it.copy(aiProviderProfiles = providers.map { (id, _) -> profileOf(id) }) }
        h.state.testRunOverrides = TestRunOverrides(
            agentFactory = agentsByProfile(providers.toMap()),
            deviceProblem = { null },
            tuning = FAST_TUNING.copy(persistDebounceMs = 50L),
        )
    }

    private fun createSuite(vararg actions: String): String {
        val suite = (h.state.createTestSuite("Lane capture suite") as StoreResult.Ok).value
        h.state.updateTestSuite(suite.id) { it.copy(targetPackage = "com.example.app") }
        val case = (h.state.createTestCase(suite.id, TestCase("", "Sign in")) as StoreResult.Ok).value
        actions.forEach { h.state.createTestStep(case.id, TestStep("", it, "It works", emptyList(), retries = 0)) }
        return suite.id
    }

    private fun lane(profileId: String, serial: String) = LaneConfig(kind = LaneKind.AGENT_PROFILE, profileId = profileId, deviceSerial = serial)

    private fun start(suiteId: String, vararg lanes: LaneConfig, openTabs: Boolean = true): StartRunResult.Started {
        val config = RunConfig(suiteId, null, lanes.toList(), capture = h.captureSettings, openLaneTabs = openTabs)
        return assertIs(runBlocking { h.state.testRunCoordinator.start(config) }, "the run starts")
    }

    private fun finished(runId: String): TestRun =
        runBlocking { withTimeout(RUN_WAIT_MS) { assertNotNull(h.state.testRunCoordinator.awaitFinished(runId)) } }

    private fun emitRows(serial: String = SERIAL_A) = repeat(4) { index -> h.emit(serial, "app row $index") }

    // ── A tab per lane ──────────────────────────────────────────────

    @Test
    fun aFailedStepLeavesAnAiMarkerInTheLanesTabAndTheTabStaysAsAStoppedCapture() {
        useAgents(LANE_A_PROFILE_ID to AutoAgentProvider(listOf("fail" to "It broke")) { emitRows() })
        val started = start(createSuite("Open the app", "Sign in"), lane(LANE_A_PROFILE_ID, SERIAL_A))
        val run = finished(started.runId)

        assertEquals(RunStatus.FAILED, run.status, run.toString())
        val laneId = started.laneIds.single()
        val tab = assertNotNull(h.state.tabs.singleOrNull { it.testLane != null }, "one tab per lane")
        assertEquals(h.state.testRunCoordinator.run(run.id)?.lane(laneId)?.config?.id, tab.testLane?.laneId)
        assertEquals(run.id, tab.testLane?.runId)
        assertTrue(tab.filename.startsWith("Test lane — "), tab.filename)
        assertNull(tab.captureSessionId, "the capture stopped when the lane ended")
        assertNotNull(tab.captureSourceSessionId, "and the tab stays as a stopped capture tab")
        assertNull(h.state.liveCaptureTabId)
        assertNull(h.state.laneCaptureOnDevice(SERIAL_A), "the device is free again")

        val blocks = tab.annotations.blocks
        val note = blocks.filterIsInstance<AnnBlock.Note>().single { parseMarkerHeader(it.text) != null }
        val marker = assertNotNull(parseMarkerHeader(note.text))
        assertEquals("AI · Sign in · step 1 failed", marker.label)
        assertTrue(note.text.contains("**Action:** Open the app") && note.text.contains("**Expected:** It works"), note.text)
        assertTrue(note.text.contains("Agent observation** (untrusted)") && note.text.contains("> It broke"), note.text)
        assertTrue(blocks.any { it is AnnBlock.Image }, "the step's screenshot is under the marker")
        assertTrue(blocks.any { it is AnnBlock.LogRef }, "and so is the log window around the failure")
        val logPath = assertNotNull(run.lanes.single().logPath)
        assertTrue(h.state.testRunCoordinator.runDir(run.id).resolve(logPath).isFile, "the lane's log is where the report reads it")
    }

    @Test
    fun aLaneThatPassesLeavesNoMarker() {
        useAgents(LANE_A_PROFILE_ID to AutoAgentProvider(listOf("pass" to "Fine")))
        val run = finished(start(createSuite("Open the app"), lane(LANE_A_PROFILE_ID, SERIAL_A)).runId)
        assertEquals(RunStatus.PASSED, run.status, run.toString())
        val tab = h.state.tabs.single { it.testLane != null }
        assertTrue(tab.annotations.blocks.none { it is AnnBlock.Note && parseMarkerHeader(it.text) != null })
    }

    // ── No tab ──────────────────────────────────────────────────────

    @Test
    fun withoutLaneTabsTheLaneRecordsTheSameAndKeepsItsMarkersInItsFolder() {
        useAgents(LANE_A_PROFILE_ID to AutoAgentProvider(listOf("fail" to "It broke")) { emitRows() })
        val started = start(createSuite("Open the app"), lane(LANE_A_PROFILE_ID, SERIAL_A), openTabs = false)
        val run = finished(started.runId)

        assertEquals(RunStatus.FAILED, run.status, run.toString())
        assertTrue(h.state.tabs.none { it.testLane != null }, "no tab was opened")
        val laneDir = h.state.testRunCoordinator.runDir(run.id).resolve("lanes/${started.laneIds.single()}")
        val sessions = File(laneDir, "capture").listFiles { file -> file.isDirectory }.orEmpty()
        assertEquals(1, sessions.size, "the same capture session a tab lane records")
        assertTrue(File(sessions.single(), "logs/logcat.log").readText().contains("app row 3"))
        val notes = assertNotNull(com.indagium.ui.readLaneNotesFile(laneDir), "the lane's notes were kept for later issue exports")
        assertTrue(notes.blocks.any { it is AnnBlock.Note && parseMarkerHeader(it.text) != null }, notes.toString())
        assertNull(h.state.laneCaptureOnDevice(SERIAL_A))
    }

    // ── The issue's capture archive ─────────────────────────────────

    @Test
    fun theIssueOfAFailedStepWantsTheWholeArchiveByDefaultAndItReopensWithTheMarkerAtTheFailure() {
        useAgents(LANE_A_PROFILE_ID to AutoAgentProvider(listOf("fail" to "It broke")) { emitRows() })
        val started = start(createSuite("Open the app"), lane(LANE_A_PROFILE_ID, SERIAL_A), openTabs = false)
        val run = finished(started.runId)
        val laneId = started.laneIds.single()
        val case = run.suite.cases.single()
        val step = case.steps.single()

        val seed = runBlocking { h.state.buildIssueSeed(run.id, laneId, case.id, 1, step.id) }.getOrThrow()
        val attachments = seed.draft.attachments
        val archive = attachments.first()
        assertEquals(IssueAttachmentKind.CAPTURE_ARCHIVE, archive.kind)
        assertTrue(archive.include, "the archive is checked by default")
        assertTrue(archive.isPendingCaptureArchive, "nothing is exported until the issue is created or sent")
        val byKind = attachments.groupBy { it.kind }
        assertTrue(byKind.getValue(IssueAttachmentKind.SCREENSHOT).single().include, "the step screenshot stays checked")
        assertTrue(attachments.filter { it.kind in setOf(IssueAttachmentKind.LOG_RANGE, IssueAttachmentKind.TRANSCRIPT) }.none { it.include })

        val saved = assertIs<com.indagium.ui.IssueActionResult.Done>(runBlocking { h.state.saveIssue(null, seed.source, seed.draft, false) })
        assertTrue(saved.record.draft.attachments.first().isPendingCaptureArchive, "saving a draft does not export the archive yet")
        val progress = ArrayList<String>()
        val record = runBlocking { h.state.attachIssueCaptureArchive(saved.record.id) { progress += it } }.getOrThrow()

        assertTrue(progress.any { it.contains("capture archive") }, progress.toString())
        val stored = record.draft.attachments.single { it.kind == IssueAttachmentKind.CAPTURE_ARCHIVE }
        assertNotNull(stored.storedPath)
        assertTrue(stored.include)
        val zip = assertNotNull(h.state.issueStore.attachmentFile(record, stored))
        assertTrue(CaptureArchiveReader.isCaptureArchive(zip), "the same archive a capture tab's Save ZIP writes")

        // The importer ("Bug report / archive" on the home screen) shows the log, and the marker note at the failure.
        val imported = CaptureArchiveReader.open(zip, File(h.dir, "import-cache"))
        val log = com.indagium.utils.parseLogcat(imported.logFile)
        assertTrue(log.any { it.msg.contains("app row 0") }, "the whole log is there")
        val notes = assertNotNull(imported.notes)
        val marker = notes.blocks.filterIsInstance<AnnBlock.Note>().mapNotNull { parseMarkerHeader(it.text) }.single()
        assertEquals("AI · Sign in · step 1 failed", marker.label)
        val reanchored = reanchorImportedCaptureNotes(notes, imported.descriptor.markers, log)
        val ref = reanchored.blocks.filterIsInstance<AnnBlock.LogRef>().single()
        assertTrue(ref.logIds.isNotEmpty() && ref.logIds.all { id -> log.any { it.id == id } }, "the marker's rows exist in the reopened log")
        assertTrue(reanchored.blocks.any { it is AnnBlock.Image }, "with the step screenshot")
        // Asking again changes nothing: the issue already holds the archive.
        val again = runBlocking { h.state.attachIssueCaptureArchive(saved.record.id) }.getOrThrow()
        assertEquals(1, again.draft.attachments.count { it.kind == IssueAttachmentKind.CAPTURE_ARCHIVE })
    }

    @Test
    fun anUntickedArchiveIsNeverExportedAndDeliveryIsNotBlockedByIt() {
        useAgents(LANE_A_PROFILE_ID to AutoAgentProvider(listOf("fail" to "It broke")) { emitRows() })
        val started = start(createSuite("Open the app"), lane(LANE_A_PROFILE_ID, SERIAL_A), openTabs = false)
        val run = finished(started.runId)
        val case = run.suite.cases.single()
        val seed = runBlocking { h.state.buildIssueSeed(run.id, started.laneIds.single(), case.id, 1, case.steps.single().id) }.getOrThrow()
        val unticked = seed.draft.copy(attachments = seed.draft.attachments.map { if (it.isPendingCaptureArchive) it.copy(include = false) else it })
        val saved = assertIs<com.indagium.ui.IssueActionResult.Done>(runBlocking { h.state.saveIssue(null, seed.source, unticked, false) })

        val record = runBlocking { h.state.attachIssueCaptureArchive(saved.record.id) }.getOrThrow()
        assertTrue(record.draft.attachments.none { it.kind == IssueAttachmentKind.CAPTURE_ARCHIVE && it.storedPath != null })
    }

    // ── Cancel, close and a closed tab ──────────────────────────────

    @Test
    fun cancellingARunStopsEveryLaneCaptureAndKeepsTheTabs() {
        val hanging = TurnProvider(listOf(hangingTurn))
        useAgents(LANE_A_PROFILE_ID to hanging, LANE_B_PROFILE_ID to hanging)
        val started = start(createSuite("Open the app"), lane(LANE_A_PROFILE_ID, SERIAL_A), lane(LANE_B_PROFILE_ID, SERIAL_B))
        awaitTrue("both lane tabs recording") { h.state.tabs.count { it.testLane != null && it.captureSessionId != null } == 2 }
        assertNull(h.state.liveCaptureTabId, "two lane captures at once and still no manual live capture")

        assertTrue(h.state.testRunCoordinator.cancel(started.runId))
        val run = finished(started.runId)

        assertEquals(RunStatus.CANCELLED, run.status)
        val tabs = h.state.tabs.filter { it.testLane != null }
        assertEquals(2, tabs.size)
        assertTrue(tabs.all { it.captureSessionId == null && it.captureSourceSessionId != null }, "stopped capture tabs")
        assertTrue(tabs.all { h.state.captureControllerFor(it.id) == null })
        assertNull(h.state.laneCaptureOnDevice(SERIAL_A))
        assertNull(h.state.laneCaptureOnDevice(SERIAL_B))
    }

    @Test
    fun closingTheAppMidRunStopsTheLaneCapturesWithinABound() {
        val hanging = TurnProvider(listOf(hangingTurn))
        useAgents(LANE_A_PROFILE_ID to hanging)
        start(createSuite("Open the app"), lane(LANE_A_PROFILE_ID, SERIAL_A))
        awaitTrue("lane tab recording") { h.state.tabs.any { it.testLane != null && it.captureSessionId != null } }
        val tabId = h.state.tabs.single { it.testLane != null }.id

        val startedNanos = System.nanoTime()
        h.state.close()
        val tookMs = (System.nanoTime() - startedNanos) / 1_000_000L

        assertTrue(tookMs < APP_CLOSE_BOUND_MS, "closing took $tookMs ms")
        assertNull(h.state.captureControllerFor(tabId), "the lane's recorder was stopped")
    }

    @Test
    fun aLaneWhoseTabIsClosedFailsAloneAndTheOtherLaneFinishes() {
        val gate = java.util.concurrent.CountDownLatch(1)
        val hanging = AutoAgentProvider(listOf("pass" to "ok")) { _ -> gate.await() }
        val quick = AutoAgentProvider(listOf("pass" to "ok"))
        useAgents(LANE_A_PROFILE_ID to hanging, LANE_B_PROFILE_ID to quick)
        val started = start(createSuite("Open the app"), lane(LANE_A_PROFILE_ID, SERIAL_A), lane(LANE_B_PROFILE_ID, SERIAL_B))
        awaitTrue("both lane tabs recording") { h.state.tabs.count { it.testLane != null && it.captureSessionId != null } == 2 }
        val laneA = started.laneIds[0]
        val tabA = h.state.tabs.single { it.testLane?.laneId == laneA }

        h.state.closeTab(tabA.id)
        val run = finished(started.runId)
        gate.countDown()

        val resultA = run.lane(laneA)!!
        val resultB = run.lane(started.laneIds[1])!!
        assertEquals(RunStatus.ERROR, resultA.status, run.toString())
        assertTrue(resultA.error.orEmpty().contains("capture ended"), resultA.error)
        assertEquals(RunStatus.PASSED, resultB.status, "the other lane is untouched")
        assertEquals(CaseStatus.PASS, resultB.cases.single().status)
        assertFalse(h.state.tabs.any { it.id == tabA.id }, "the closed tab stays closed")
    }

    private companion object {
        const val APP_CLOSE_BOUND_MS = 20_000L
    }
}
