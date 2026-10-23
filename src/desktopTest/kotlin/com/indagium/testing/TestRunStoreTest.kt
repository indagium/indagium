package com.indagium.testing

import com.indagium.model.AiUsageStats
import com.indagium.testing.model.CaseAttemptRecord
import com.indagium.testing.model.CaseResult
import com.indagium.testing.model.CaseStatus
import com.indagium.testing.model.CheckResult
import com.indagium.testing.model.CheckStatus
import com.indagium.testing.model.EvidenceFlags
import com.indagium.testing.model.JudgeComparison
import com.indagium.testing.model.JudgeVerdict
import com.indagium.testing.model.LaneConfig
import com.indagium.testing.model.LaneKind
import com.indagium.testing.model.LaneResult
import com.indagium.testing.model.LaneToolCall
import com.indagium.testing.model.LaneToolCallStatus
import com.indagium.testing.model.RunConfig
import com.indagium.testing.model.RunStatus
import com.indagium.testing.model.StepAttemptRecord
import com.indagium.testing.model.StepIntent
import com.indagium.testing.model.StepJudgement
import com.indagium.testing.model.StepResult
import com.indagium.testing.model.StepStatus
import com.indagium.testing.model.TestRun
import com.indagium.testing.model.VerificationState
import com.indagium.testing.model.newRunId
import com.indagium.testing.store.RunPersister
import com.indagium.testing.store.TEST_RUN_FILE_NAME
import com.indagium.testing.store.TestRunStore
import com.indagium.testing.store.TranscriptWriter
import com.indagium.testing.store.decodeRunFile
import com.indagium.testing.store.encodeRunFile
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import java.io.File
import java.util.concurrent.atomic.AtomicInteger
import kotlin.concurrent.thread
import kotlin.io.path.createTempDirectory
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class TestRunStoreTest {
    private val dir: File = createTempDirectory("indagium-run-store").toFile()
    private val store = TestRunStore { File(dir, "test-runs") }

    @AfterTest
    fun tearDown() {
        dir.deleteRecursively()
    }

    @Suppress("MagicNumber") // Arbitrary synthetic timestamps and counts.
    private fun sampleRun(status: RunStatus = RunStatus.FAILED, createdAt: Long = 1_000L): TestRun {
        val script = sampleScript()
        val shared = sampleSharedStep()
        val suite = fullSuite(script, shared)
        val lane = LaneConfig(kind = LaneKind.AGENT_PROFILE, profileId = "profile-1", deviceSerial = "SER-9")
        val external = LaneConfig(kind = LaneKind.EXTERNAL, deviceSerial = "SER-10")
        val step = StepResult(
            stepId = suite.cases.first().steps.first().id, stepNumber = 1, action = "Open settings", expected = "Settings shown",
            setup = false, status = StepStatus.FAIL, attempts = 2, agentClaim = "pass", observation = "It showed a dialog\nwith a second line",
            checks = listOf(
                CheckResult("chk-1", "logAppears", CheckStatus.FAIL, "No row", 310L),
                CheckResult("chk-2", "screenJudge", CheckStatus.NOT_EVALUATED, "Needs the judge"),
            ),
            screenshotPath = "lanes/lane-1/screens/c1-i1-s1-a2.png", logStartOffset = 10L, logEndOffset = 99L,
            transcriptStartOffset = 0L, transcriptEndOffset = 512L, startedAt = 5_000L, durationMs = 1_234L, issueRequested = true, note = "Attempt 1 fail.",
        )
        return TestRun(
            id = newRunId(), suite = suite, scripts = listOf(script), sharedSteps = listOf(shared),
            config = RunConfig(
                suite.id, listOf(suite.cases.first().id), listOf(lane, external), repeat = 3, caseToolCallLimit = 25,
                evidence = EvidenceFlags(video = true, screenshots = false, logcat = true, transcript = false),
                confirmationTimeoutMs = 60_000L, judgeProfileId = "judge-1", judgeMode = "blind",
            ),
            lanes = listOf(
                LaneResult(
                    laneId = lane.id, config = lane, status = RunStatus.FAILED,
                    cases = listOf(CaseResult(suite.cases.first().id, "Settings", 2, CaseStatus.FAIL, listOf(step), 4_000L, 9_000L, "A note")),
                    startedAt = 3_000L, finishedAt = 9_500L, error = null, currentCase = "Settings", currentStepNumber = 1, currentStepAction = "Open settings",
                    logPath = "lanes/${lane.id}/capture/logcat.log", transcriptPath = "lanes/${lane.id}/transcript.jsonl",
                    toolActivityPath = "lanes/${lane.id}/tool-activity.jsonl",
                    toolCalls = listOf(LaneToolCall(
                        id = "call-1", caseId = suite.cases.first().id, stepId = step.stepId, iteration = 2, attempt = 1,
                        toolName = "tap", argumentsPreview = "{\"x\":4}", resultPreview = "{\"ok\":true}",
                        status = LaneToolCallStatus.SUCCEEDED, startedAt = 5_100L, durationMs = 20L,
                    )),
                ),
                LaneResult(external.id, external, RunStatus.CANCELLED),
            ),
            status = status, createdAt = createdAt, startedAt = 2_000L, finishedAt = 10_000L, warnings = listOf("Case 'Two' is locked."), error = "Boom",
        )
    }

    // ── Layout and ids ──────────────────────────────────────────────

    @Test
    fun runFoldersLiveUnderTheBaseAndUnsafeIdsAreRefused() {
        val run = sampleRun()
        assertEquals(File(dir, "test-runs/${run.id}"), store.runDir(run.id))
        assertEquals(File(dir, "test-runs/${run.id}/lanes/lane-a"), store.laneDir(run.id, "lane-a"))
        assertFailsWith<IllegalArgumentException> { store.runDir("../outside") }
        assertFailsWith<IllegalArgumentException> { store.laneDir(run.id, "a/b") }
        assertNull(store.load("../outside"))
        assertFalse(File(dir, "test-runs").exists(), "nothing is created until a run is saved")
    }

    // ── Round trip ──────────────────────────────────────────────────

    @Test
    fun aSavedRunLoadsBackExactly() {
        val run = sampleRun()
        assertTrue(store.save(run).isSuccess)

        val loaded = assertNotNull(store.load(run.id))
        assertEquals(run.copy(suite = run.suite), loaded.copy(suite = loaded.suite))
        assertEquals(run.lanes, loaded.lanes)
        assertEquals(run.config, loaded.config)
        assertEquals(run.suite, loaded.suite)
        assertEquals(run.scripts, loaded.scripts)
        assertEquals(run.sharedSteps, loaded.sharedSteps)
        assertEquals(run, loaded)
        val text = File(store.runDir(run.id), TEST_RUN_FILE_NAME).readText()
        assertEquals("indagium-test-run", Json.parseToJsonElement(text).jsonObject["format"]?.let { (it as kotlinx.serialization.json.JsonPrimitive).content })
    }

    @Test
    fun aRunSnapshotKeepsPlaybackIntentAndPrecondition() {
        val base = sampleRun()
        val case = base.suite.cases.first()
        val step = case.steps.first().copy(intent = StepIntent.ENSURE_STATE, precondition = "Playback is active")
        val suite = base.suite.copy(cases = listOf(case.copy(steps = listOf(step) + case.steps.drop(1))) + base.suite.cases.drop(1))
        val run = base.copy(suite = suite)

        assertEquals(run, decodeRunFile(encodeRunFile(run)).getOrThrow())
    }

    @Test
    fun authoringAgentJudgeExternalAndComparisonUsageSurviveRunJsonAndOldDefaultsStayNull() {
        val oldRun = sampleRun()
        val oldRoundTrip = decodeRunFile(encodeRunFile(oldRun)).getOrThrow()
        assertNull(oldRoundTrip.lanes.first().cases.single().agentUsage)
        assertNull(oldRoundTrip.lanes.first().cases.single().judgeUsage)
        assertNull(oldRoundTrip.lanes.first().cases.single().externalToolCalls)

        val base = sampleRun()
        val lane = base.lanes.first()
        val case = lane.cases.single()
        val step = case.steps.single().copy(
            judge = StepJudgement(verdict = JudgeVerdict.PASS, usage = AiUsageStats(inputTokens = 6, outputTokens = 2, totalTokens = 8)),
        )
        val usageRun = base.copy(
            lanes = base.lanes.map { result ->
                if (result.laneId != lane.laneId) result else result.copy(
                    cases = listOf(case.copy(
                        steps = listOf(step),
                        agentUsage = AiUsageStats(toolCalls = 4, inputTokens = 90, outputTokens = 20, totalTokens = 110, partial = true),
                        judgeUsage = AiUsageStats(toolCalls = 1, inputTokens = 6, outputTokens = 2, totalTokens = 8),
                        externalToolCalls = 12,
                    )),
                )
            },
            comparisons = listOf(
                JudgeComparison(
                    caseId = case.caseId,
                    iteration = 2,
                    stepId = step.stepId,
                    stepNumber = 1,
                    action = step.action,
                    verdicts = emptyMap(),
                    usage = AiUsageStats(toolCalls = 2, inputTokens = 25, outputTokens = 5, totalTokens = 30),
                ),
            ),
        )

        assertEquals(usageRun, decodeRunFile(encodeRunFile(usageRun)).getOrThrow())
    }

    @Test
    fun fullRunListingCanRestoreSuiteHistoryAfterRestart() {
        val run = sampleRun(RunStatus.CANCELLED)
        assertTrue(store.save(run).isSuccess)

        val listed = store.listRecords()

        assertEquals(listOf(run), listed)
        assertEquals(run.lanes, listed.single().lanes)
        assertEquals(run.config, listed.single().config)
    }

    @Test
    fun savingLeavesNoTemporaryFilesAndReplacesTheFileInPlace() {
        val run = sampleRun()
        store.save(run)
        store.save(run.copy(status = RunStatus.PASSED))
        val files = store.runDir(run.id).listFiles().orEmpty().map { it.name }
        assertEquals(listOf(TEST_RUN_FILE_NAME), files)
        assertEquals(RunStatus.PASSED, store.load(run.id)?.status)
    }

    @Test
    fun aReaderNeverSeesAPartialFileWhileAWriterKeepsReplacingIt() {
        val run = sampleRun()
        store.save(run)
        val stop = java.util.concurrent.atomic.AtomicBoolean(false)
        val bad = AtomicInteger()
        val writer = thread {
            var n = 0
            while (!stop.get()) store.save(run.copy(warnings = List(++n % 40) { "warning $it" }))
        }
        repeat(300) { if (store.load(run.id) == null) bad.incrementAndGet() }
        stop.set(true)
        writer.join()
        assertEquals(0, bad.get(), "every read saw a complete run.json")
    }

    @Test
    fun aFailedWriteIsReportedNotThrown() {
        val run = sampleRun()
        File(dir, "test-runs").mkdirs()
        File(dir, "test-runs/${run.id}").writeText("a file where the run folder should be")
        val result = store.save(run)
        assertTrue(result.isFailure)
        assertNotNull(store.lastError)
    }

    // ── Tolerant decode ─────────────────────────────────────────────

    @Test
    fun unknownKeysMissingFieldsAndUnknownEnumsDecodeToDefaults() {
        val run = sampleRun()
        val root = Json.parseToJsonElement(encodeRunFile(run)).jsonObject
        val mangled = Json.encodeToString(
            kotlinx.serialization.json.JsonObject.serializer(),
            kotlinx.serialization.json.JsonObject(
                root + mapOf(
                    "futureTopLevel" to kotlinx.serialization.json.JsonPrimitive(1),
                    "version" to kotlinx.serialization.json.JsonPrimitive(99),
                    "run" to kotlinx.serialization.json.JsonObject(
                        root.getValue("run").jsonObject + mapOf(
                            "status" to kotlinx.serialization.json.JsonPrimitive("SOMETHING_NEW"),
                            "lanes" to kotlinx.serialization.json.JsonArray(emptyList()),
                            "unknown" to kotlinx.serialization.json.JsonPrimitive("x"),
                        ),
                    ),
                ),
            ),
        )
        val decoded = decodeRunFile(mangled).getOrThrow()
        assertEquals(RunStatus.ERROR, decoded.status, "an unknown status is a neutral one")
        assertTrue(decoded.lanes.isEmpty())
        assertEquals(run.suite, decoded.suite)

        val minimal = """{"format":"indagium-test-run","version":1,"run":{"id":"run-min","suite":{"id":"suite-min","name":"Tiny"}}}"""
        val tiny = decodeRunFile(minimal).getOrThrow()
        assertEquals("run-min", tiny.id)
        assertEquals("Tiny", tiny.suite.name)
        assertEquals(1, tiny.config.repeat)
        assertTrue(tiny.config.evidence.screenshots)
    }

    @Test
    fun aStoredTerminalPassWithPendingCurrentOrHistoricalJudgmentCannotDecodeAsPassed() {
        val base = sampleRun(RunStatus.PASSED)
        val lane = base.lanes.first()
        val case = lane.cases.single()
        val original = case.steps.single()
        val pendingAttempt = StepAttemptRecord(
            caseId = case.caseId,
            iteration = case.iteration,
            caseAttempt = 1,
            stepId = original.stepId,
            attempt = 1,
            status = StepStatus.PASS,
            executionStatus = StepStatus.PASS,
            verificationState = VerificationState.PENDING,
            judgeQueueWaitMs = 27L,
            judgmentDurationMs = 60_000L,
        )
        val pendingStep = original.copy(
            status = StepStatus.PASS,
            executionStatus = StepStatus.PASS,
            verificationState = VerificationState.PENDING,
            attemptHistory = listOf(pendingAttempt),
            judgeQueueWaitMs = 27L,
            judgmentDurationMs = 60_000L,
        )
        val passed = base.copy(
            lanes = base.lanes.map { current ->
                if (current.laneId != lane.laneId) current else current.copy(
                    status = RunStatus.PASSED,
                    cases = listOf(case.copy(status = CaseStatus.PASS, steps = listOf(pendingStep))),
                )
            },
            status = RunStatus.PASSED,
        )

        val decoded = decodeRunFile(encodeRunFile(passed)).getOrThrow()

        assertEquals(RunStatus.ERROR, decoded.status)
        assertTrue(decoded.error.orEmpty().contains("unresolved verification"))
        assertEquals(RunStatus.ERROR, decoded.lanes.first().status)
        assertEquals(CaseStatus.BLOCKED, decoded.lanes.first().cases.single().status)
        assertEquals(VerificationState.PENDING, decoded.lanes.first().cases.single().steps.single().verificationState)
        assertEquals(27L, decoded.lanes.first().cases.single().steps.single().judgeQueueWaitMs)
        assertEquals(60_000L, decoded.lanes.first().cases.single().steps.single().judgmentDurationMs)
        assertEquals(27L, decoded.lanes.first().cases.single().steps.single().attemptHistory.single().judgeQueueWaitMs)

        val interruptedEarlier = base.copy(
            lanes = base.lanes.map { current ->
                if (current.laneId != lane.laneId) current else current.copy(
                    status = RunStatus.PASSED,
                    cases = listOf(
                        case.copy(
                            status = CaseStatus.PASS,
                            steps = listOf(original),
                            priorAttempts = listOf(CaseAttemptRecord(
                                caseId = case.caseId,
                                iteration = case.iteration,
                                attempt = 1,
                                status = CaseStatus.PASS,
                                steps = listOf(pendingStep.copy(attemptHistory = emptyList())),
                            )),
                        ),
                    ),
                )
            },
            status = RunStatus.PASSED,
        )
        val decodedEarlier = decodeRunFile(encodeRunFile(interruptedEarlier)).getOrThrow()
        assertEquals(RunStatus.ERROR, decodedEarlier.status)
        assertEquals(CaseStatus.BLOCKED, decodedEarlier.lanes.first().cases.single().status)
        assertEquals(CaseStatus.BLOCKED, decodedEarlier.lanes.first().cases.single().priorAttempts.single().status)
    }

    @Test
    fun brokenFilesAreRejectedWithASafeMessage() {
        assertTrue(decodeRunFile("not json").exceptionOrNull()?.message.orEmpty().contains("not valid JSON"))
        assertTrue(decodeRunFile("""{"format":"something-else","version":1}""").exceptionOrNull()?.message.orEmpty().contains("not an Indagium test run"))
        assertTrue(decodeRunFile("""{"format":"indagium-test-run","version":1}""").isFailure)
        assertTrue(decodeRunFile("""{"format":"indagium-test-run","version":1,"run":{"id":"../bad","suite":{"id":"suite-1"}}}""").isFailure)

        val run = sampleRun()
        store.save(run)
        val file = File(store.runDir(run.id), TEST_RUN_FILE_NAME)
        file.writeText(file.readText().take(200))
        assertNull(store.load(run.id), "a truncated file loads as nothing instead of throwing")
    }

    // ── Listing ─────────────────────────────────────────────────────

    @Test
    fun listingReturnsSummariesNewestFirstAndSkipsUnreadableFolders() {
        val older = sampleRun(createdAt = 1_000L)
        val newer = sampleRun(RunStatus.PASSED, createdAt = 9_000L)
        store.save(older)
        store.save(newer)
        File(dir, "test-runs/run-broken").mkdirs()
        File(dir, "test-runs/run-broken/$TEST_RUN_FILE_NAME").writeText("{")
        File(dir, "test-runs/stray.txt").writeText("x")

        val listed = store.list()
        assertEquals(listOf(newer.id, older.id), listed.map { it.id })
        assertEquals(RunStatus.PASSED, listed.first().status)
        assertEquals("Smoke", listed.first().suiteName)
        assertEquals(0, listed.first().passedSteps)
        assertEquals(1, listed.first().totalSteps)
    }

    @Test
    fun suiteHistoryFindsPersistedRunsBeyondGlobalRecentWindowAfterReload() {
        val targetSuiteId = "suite-target-history"

        fun forSuite(id: String, suiteId: String, createdAt: Long): TestRun {
            val sample = sampleRun(createdAt = createdAt)
            return sample.copy(id = id, suite = sample.suite.copy(id = suiteId), config = sample.config.copy(suiteId = suiteId))
        }
        val targetRuns = (0 until 8).map { index -> forSuite("run-target-$index", targetSuiteId, 1_000L + index) }
        targetRuns.forEach { run ->
            assertTrue(store.save(run).isSuccess)
            File(store.runDir(run.id), TEST_RUN_FILE_NAME).setLastModified(run.createdAt)
        }
        repeat(220) { index ->
            val foreign = forSuite("run-foreign-$index", "suite-foreign-$index", 10_000L + index)
            assertTrue(store.save(foreign).isSuccess)
            File(store.runDir(foreign.id), TEST_RUN_FILE_NAME).setLastModified(10_000L + index)
        }

        val reloadedStore = TestRunStore { File(dir, "test-runs") }
        assertTrue(reloadedStore.listRecords().none { it.suite.id == targetSuiteId }, "global newest-200 list is allowed to omit old target runs")
        assertEquals(targetRuns.takeLast(5).asReversed().map { it.id }, reloadedStore.listRecordsForSuite(targetSuiteId).map { it.id })
        assertEquals(targetRuns.asReversed().map { it.id }, reloadedStore.listSummariesForSuite(targetSuiteId).map { it.id })
    }

    // ── Latest finished run per suite ───────────────────────────────

    private fun savedRunFor(suiteId: String, runId: String, status: RunStatus, createdAt: Long): TestRun {
        val sample = sampleRun(status, createdAt)
        val run = sample.copy(id = runId, suite = sample.suite.copy(id = suiteId), config = sample.config.copy(suiteId = suiteId))
        assertTrue(store.save(run).isSuccess)
        File(store.runDir(run.id), TEST_RUN_FILE_NAME).setLastModified(createdAt)
        return run
    }

    @Test
    fun latestSummariesStopReadingOnceEverySuiteHasAFinishedRun() {
        val baseDirCalls = AtomicInteger()
        val counting = TestRunStore {
            baseDirCalls.incrementAndGet()
            File(dir, "test-runs")
        }
        val runs = (1..10).map { savedRunFor("suite-a", "run-a-$it", RunStatus.PASSED, createdAt = it * 1_000L) }
        baseDirCalls.set(0)

        val latest = counting.latestSummariesBySuite(setOf("suite-a"))

        assertEquals(runs.last().id, latest.bySuite.getValue("suite-a").id)
        assertEquals(runs.last().id, latest.latestOverall?.id)
        // One folder listing plus one run.json read: the nine older runs were never opened.
        assertEquals(2, baseDirCalls.get())
    }

    @Test
    fun latestSummariesIgnoreUnfinishedRuns() {
        val finished = savedRunFor("suite-a", "run-a-old", RunStatus.PASSED, createdAt = 1_000L)
        savedRunFor("suite-a", "run-a-running", RunStatus.RUNNING, createdAt = 5_000L)
        savedRunFor("suite-b", "run-b-queued", RunStatus.QUEUED, createdAt = 6_000L)

        val latest = store.latestSummariesBySuite(setOf("suite-a", "suite-b"))

        assertEquals(finished.id, latest.bySuite.getValue("suite-a").id)
        assertNull(latest.bySuite["suite-b"], "a suite whose only run is unfinished has no latest run")
        assertEquals(finished.id, latest.latestOverall?.id)
    }

    @Test
    fun latestSummariesGiveTheNewestFinishedRunOverallEvenForAnUnrequestedSuite() {
        val older = savedRunFor("suite-a", "run-a", RunStatus.FAILED, createdAt = 2_000L)
        val newest = savedRunFor("suite-other", "run-other", RunStatus.PASSED, createdAt = 8_000L)

        val latest = store.latestSummariesBySuite(setOf("suite-a"))

        assertEquals(setOf("suite-a"), latest.bySuite.keys)
        assertEquals(older.id, latest.bySuite.getValue("suite-a").id)
        assertEquals(newest.id, latest.latestOverall?.id)
    }

    @Test
    fun latestSummariesAreBoundedByTheScanLimitAndEmptyWithoutAFolder() {
        assertEquals(emptyMap(), store.latestSummariesBySuite(setOf("suite-a")).bySuite)
        assertNull(store.latestSummariesBySuite(setOf("suite-a")).latestOverall)

        savedRunFor("suite-a", "run-a", RunStatus.PASSED, createdAt = 1_000L)
        for (index in 1..3) savedRunFor("suite-x", "run-x-$index", RunStatus.PASSED, createdAt = 2_000L + index)

        assertNull(store.latestSummariesBySuite(setOf("suite-a"), scanLimit = 3).bySuite["suite-a"])
        assertEquals("run-a", store.latestSummariesBySuite(setOf("suite-a"), scanLimit = 4).bySuite["suite-a"]?.id)
    }

    // ── Finished outcomes of a suite ────────────────────────────────

    @Test
    fun suiteHistoryKeepsTheNewestTwelveFinishedOutcomesAsCompactEntries() {
        val runs = (1..15).map { savedRunFor("suite-a", "run-a-$it", RunStatus.PASSED, createdAt = it * 1_000L) }
        savedRunFor("suite-a", "run-a-running", RunStatus.RUNNING, createdAt = 50_000L)
        savedRunFor("suite-a", "run-a-queued", RunStatus.QUEUED, createdAt = 51_000L)
        savedRunFor("suite-b", "run-b", RunStatus.PASSED, createdAt = 99_000L)

        val history = store.listSuiteHistory("suite-a")

        assertEquals(runs.takeLast(12).reversed().map { it.id }, history.finishedOutcomes.map { it.runId }, "newest first, finished only, this suite only")
        assertTrue(history.finishedOutcomes.all { it.suiteId == "suite-a" && it.isFinished })
        assertEquals(17, history.summaries.size, "the Runs tab still lists every run")
        // Unchanged by the outcomes: the five newest runs (queued, running, three finished) and the latest terminal one, which is among them.
        assertEquals(5, history.recentRecords.size)
    }

    @Test
    fun suiteHistoryOutcomesAreOrderedByCreationNotByFileTime() {
        val older = savedRunFor("suite-a", "run-a-old", RunStatus.FAILED, createdAt = 1_000L)
        val newer = savedRunFor("suite-a", "run-a-new", RunStatus.PASSED, createdAt = 9_000L)
        // The old run was rewritten last, so a scan by file time reads it first.
        File(store.runDir(older.id), TEST_RUN_FILE_NAME).setLastModified(50_000L)

        val outcomes = store.listSuiteHistory("suite-a").finishedOutcomes

        assertEquals(listOf(newer.id, older.id), outcomes.map { it.runId })
        assertEquals(RunStatus.FAILED, outcomes.last().status)
    }

    @Test
    fun suiteHistoryOfAnUnsafeOrEmptySuiteHasNoOutcomes() {
        assertTrue(store.listSuiteHistory("../escape").finishedOutcomes.isEmpty())
        assertTrue(store.listSuiteHistory("suite-none").finishedOutcomes.isEmpty())
    }

    // ── Transcript ──────────────────────────────────────────────────

    @Test
    fun theTranscriptRedactsSecretsAndEveryLineIsJson() {
        val writer = TranscriptWriter(File(dir, "lane/transcript.jsonl"), clock = { 42L })
        assertEquals(0L, writer.sizeBytes)
        val echoed = "Authorization: Bearer abc.def.ghi and api_key=SECRET123 done"
        writer.append("tool_result", mapOf("tool" to "run_script", "result" to echoed, "n" to 3, "ok" to true))
        writer.append("note", mapOf("text" to "token: hunter2"))

        val text = writer.file.readText()
        assertFalse(text.contains("abc.def.ghi"), text)
        assertFalse(text.contains("SECRET123"), text)
        assertFalse(text.contains("hunter2"), text)
        assertTrue(text.contains("[REDACTED]"), text)
        val lines = text.trim().lines()
        assertEquals(2, lines.size)
        lines.forEach { Json.parseToJsonElement(it).jsonObject }
        assertEquals(text.toByteArray().size.toLong(), writer.sizeBytes)
        assertTrue(lines.first().contains("\"t\":42"))
    }

    // ── Debounced persistence ───────────────────────────────────────

    @Test
    fun requestsAreDebouncedIntoOneSaveAndFlushWritesTheFinalRunAtOnce() {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        val run = sampleRun(RunStatus.RUNNING)
        val current = java.util.concurrent.atomic.AtomicReference(run)
        val persister = RunPersister(store, scope, { current.get() }, debounceMs = 150L)
        try {
            persister.request()
            persister.request()
            current.set(run.copy(status = RunStatus.FAILED))
            persister.request()
            assertNull(store.load(run.id), "nothing is written inside the debounce window")
            Thread.sleep(600)
            assertEquals(RunStatus.FAILED, store.load(run.id)?.status, "the save read the freshest run")

            current.set(run.copy(status = RunStatus.PASSED))
            persister.request()
            runBlocking { persister.flush() }
            assertEquals(RunStatus.PASSED, store.load(run.id)?.status, "flush writes immediately")
        } finally {
            scope.cancel()
        }
    }
}
