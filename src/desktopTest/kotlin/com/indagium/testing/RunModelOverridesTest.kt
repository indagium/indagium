package com.indagium.testing

import com.indagium.capture.CaptureBufferMode
import com.indagium.capture.CaptureMirrorMode
import com.indagium.capture.CaptureSettings
import com.indagium.capture.effectiveMirrorMode
import com.indagium.edition.EditionLimits
import com.indagium.model.AiProviderKind
import com.indagium.model.AiProviderProfile
import com.indagium.testing.model.JudgeMode
import com.indagium.testing.model.LaneConfig
import com.indagium.testing.model.LaneKind
import com.indagium.testing.model.RunConfig
import com.indagium.testing.model.RunStatus
import com.indagium.testing.model.TestRun
import com.indagium.testing.model.driverLabel
import com.indagium.testing.model.modelAndEffortLabel
import com.indagium.testing.run.LaneAgentFactory
import com.indagium.testing.run.ProviderLaneAgent
import com.indagium.testing.run.StartRunResult
import com.indagium.testing.run.overrideProblems
import com.indagium.testing.run.validateRun
import com.indagium.testing.run.withEffectiveModels
import com.indagium.testing.run.withRunOverrides
import com.indagium.testing.store.decodeRunFile
import com.indagium.testing.store.encodeRunFile
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

private const val AWAIT_MS = 20_000L

/** [element] without any object key in [keys], at any depth: what a run file written before those fields existed looks like. */
private fun dropKeys(element: JsonElement, keys: Set<String>): JsonElement = when (element) {
    is JsonObject -> JsonObject(element.filterKeys { it !in keys }.mapValues { dropKeys(it.value, keys) })
    is JsonArray -> JsonArray(element.map { dropKeys(it, keys) })
    else -> element
}

/** Per-lane and judge model / reasoning-effort overrides: applied to the agents, validated, frozen into the run and stored. */
class RunModelOverridesTest {
    private var harness: RunHarness? = null

    @AfterTest
    fun tearDown() {
        harness?.close()
    }

    private fun profileOfKind(kind: AiProviderKind, model: String = "base-model", effort: String = "") =
        AiProviderProfile("p-${kind.name}", kind.name, if (kind.usesHttpEndpoint) "http://127.0.0.1:1234" else "", model, kind = kind, reasoningEffort = effort)

    // ── Applied to the agents ───────────────────────────────────────

    @Test
    fun aLaneOverrideReachesTheHttpModelRequestAndTheProfilesOwnValueIsTheDefault() {
        val suite = suiteOf(caseOf("Overrides", step("Open the app")), caseOf("Defaults", step("Open it again")))
        val provider = TurnProvider(listOf(finishTurn("pass"), finishTurn("pass")))
        val seen = CopyOnWriteArrayList<AiProviderProfile>()
        val h = RunHarness(
            libraryOf(suite), provider = provider,
            agentFactory = LaneAgentFactory { chosen, _ -> seen += chosen; ProviderLaneAgent(provider, chosen) },
        ).also { harness = it }
        val lane = agentLane(TEST_PROFILE_ID).copy(model = "other-model", reasoningEffort = "high")
        val started = assertIs<StartRunResult.Started>(runBlocking { h.coordinator.start(h.config(suite, lane, caseIds = listOf(suite.cases[0].id))) })
        val run = runBlocking { withTimeout(AWAIT_MS) { assertNotNull(h.coordinator.awaitFinished(started.runId)) } }

        assertEquals(RunStatus.PASSED, run.status, run.toString())
        val first = provider.requests.first()
        assertEquals("other-model", first.model)
        assertEquals("high", first.reasoningEffort)
        assertEquals("other-model", seen.single().model)
        assertEquals("high", seen.single().reasoningEffort)
        assertEquals("test-model", testProfile.model, "the library profile itself is not changed")
    }

    @Test
    fun withoutAnOverrideTheProfilesModelIsUsedAndTheRunRecordsWhatRan() {
        val suite = suiteOf(caseOf("Defaults", step("Open the app")))
        val provider = TurnProvider(listOf(finishTurn("pass")))
        val h = RunHarness(libraryOf(suite), provider = provider, profile = testProfile.copy(reasoningEffort = "medium")).also { harness = it }
        val started = assertIs<StartRunResult.Started>(runBlocking { h.coordinator.start(h.config(suite)) })
        val run = runBlocking { withTimeout(AWAIT_MS) { assertNotNull(h.coordinator.awaitFinished(started.runId)) } }

        assertEquals("test-model", provider.requests.first().model)
        assertEquals("medium", provider.requests.first().reasoningEffort)
        val recorded = run.config.lanes.single()
        assertEquals("test-model", recorded.model, "the stored run says which model ran")
        assertEquals("medium", recorded.reasoningEffort)
        assertEquals("test-model · medium effort", recorded.driverLabel("Test model").removePrefix("Test model · "))
    }

    @Test
    fun anEmptyEffortOverrideAsksForTheModelsDefaultEvenWhenTheProfileSetsOne() {
        val profile = profileOfKind(AiProviderKind.OPENAI_COMPATIBLE, effort = "high")
        val overridden = profile.withRunOverrides(model = "  ", reasoningEffort = "")
        assertEquals("base-model", overridden.model, "a blank model is no override")
        assertEquals("", overridden.reasoningEffort)
        assertEquals("high", profile.withRunOverrides(null, null).reasoningEffort)
    }

    @Test
    fun theJudgeOverrideReachesTheJudgesAgentAndLanesOfOneRunKeepTheirOwn() {
        val suite = suiteOf(caseOf("Judged", step("Open the app")))
        val provider = TurnProvider(listOf(finishTurn("pass")))
        val seen = CopyOnWriteArrayList<AiProviderProfile>()
        val h = RunHarness(
            libraryOf(suite), provider = provider, extraProfiles = listOf(judgeProfile),
            agentFactory = LaneAgentFactory { chosen, _ -> seen += chosen; ProviderLaneAgent(provider, chosen) },
        ).also { harness = it }
        val config = h.config(suite, agentLane(TEST_PROFILE_ID).copy(model = "lane-model")).copy(
            judgeProfileId = JUDGE_PROFILE_ID, judgeMode = JudgeMode.FAILURES_ONLY.wire,
            judgeModel = "judge-model", judgeReasoningEffort = "low",
        )
        val started = assertIs<StartRunResult.Started>(runBlocking { h.coordinator.start(config) })
        val run = runBlocking { withTimeout(AWAIT_MS) { assertNotNull(h.coordinator.awaitFinished(started.runId)) } }

        val lane = seen.first { it.id == TEST_PROFILE_ID }
        val judge = seen.first { it.id == JUDGE_PROFILE_ID }
        assertEquals("lane-model", lane.model)
        assertEquals("judge-model", judge.model)
        assertEquals("low", judge.reasoningEffort)
        assertEquals("judge-model", run.config.judgeModel)
        assertEquals("low", run.config.judgeReasoningEffort)
    }

    // ── Validation ──────────────────────────────────────────────────

    @Test
    fun anEffortThatTheKindDoesNotOfferIsRefusedAndAnUnknownProfileKindsListIsNotGuessed() {
        val codex = profileOfKind(AiProviderKind.CODEX_ACCOUNT, model = "")
        val claude = profileOfKind(AiProviderKind.CLAUDE_CODE_ACCOUNT, model = "")
        val openAi = profileOfKind(AiProviderKind.OPENAI_API)
        assertTrue(overrideProblems("Lane 1", openAi, null, "max").single().contains("not offered by OpenAI API"))
        assertTrue(overrideProblems("Lane 1", openAi, null, "medium").isEmpty())
        assertTrue(overrideProblems("Lane 1", claude, null, "max").isEmpty())
        assertTrue(overrideProblems("Lane 1", claude, null, "").isEmpty())
        assertTrue(overrideProblems("Lane 1", claude, null, "Max!").single().contains("lowercase"))
        assertTrue(overrideProblems("Lane 1", codex, null, "minimal").isEmpty(), "Codex lists its levels per model: only the shape is checked")
        assertTrue(overrideProblems("Lane 1", openAi, "a\nb", null).single().contains("single line"))
    }

    @Test
    fun runValidationReportsBadOverridesOfLanesAndTheJudgeAndExternalLanesTakeNone() {
        val suite = suiteOf(caseOf("Case", step("Step")))
        val library = libraryOf(suite)
        val profile = profileOfKind(AiProviderKind.OPENAI_COMPATIBLE)
        val lanes = listOf(
            LaneConfig(kind = LaneKind.AGENT_PROFILE, profileId = profile.id, deviceSerial = FIXTURE_SERIAL, reasoningEffort = "xhigh"),
            LaneConfig(kind = LaneKind.EXTERNAL, deviceSerial = "SERIAL-2", model = "m"),
        )
        val config = RunConfig(suite.id, null, lanes, judgeProfileId = profile.id, judgeMode = JudgeMode.EVERY_STEP.wire, judgeReasoningEffort = "max")
        val errors = validateRun(config, library, EditionLimits.UNLIMITED, listOf(profile)) { "key" }.errors
        assertTrue(errors.any { it.startsWith("Lane 1") && "xhigh" in it }, errors.toString())
        assertTrue(errors.any { it.startsWith("Lane 2") && "external lane" in it }, errors.toString())
        assertTrue(errors.any { it.startsWith("Judge") && "max" in it }, errors.toString())
    }

    @Test
    fun anOverriddenModelFillsABlankProfileModelSoTheRunCanStart() {
        val suite = suiteOf(caseOf("Case", step("Step")))
        val blank = profileOfKind(AiProviderKind.OPENAI_COMPATIBLE, model = "")
        val lane = LaneConfig(kind = LaneKind.AGENT_PROFILE, profileId = blank.id, deviceSerial = FIXTURE_SERIAL)
        val without = validateRun(RunConfig(suite.id, null, listOf(lane)), libraryOf(suite), EditionLimits.UNLIMITED, listOf(blank)) { "key" }
        assertTrue(without.errors.any { "choose a model" in it }, without.errors.toString())
        val picked = RunConfig(suite.id, null, listOf(lane.copy(model = "picked")))
        val with = validateRun(picked, libraryOf(suite), EditionLimits.UNLIMITED, listOf(blank)) { "key" }
        assertTrue(with.errors.none { "choose a model" in it }, with.errors.toString())
    }

    // ── Stored run ──────────────────────────────────────────────────

    @Test
    fun theRunFileRoundTripsOverridesRecordingAndLaneTabChoicesAndAnOlderFileStillLoads() {
        val suite = suiteOf(caseOf("Case", step("Step")))
        val capture = CaptureSettings(
            recordVideo = false, audio = true, includeBufferedLogs = true, keepDeviceAudio = true, bufferMode = CaptureBufferMode.ALL,
            microphoneDeviceId = "mic-1", maxSize = 1600, maxFps = 30, bitrateMbps = 6,
        ).let { it.copy(mirror = false, mirrorMode = CaptureMirrorMode.DISABLED) }
        val config = RunConfig(
            suite.id, null,
            listOf(
                agentLane().copy(model = "m-1", reasoningEffort = ""),
                externalLane("SERIAL-2"),
            ),
            judgeProfileId = "judge", judgeMode = JudgeMode.EVERY_STEP.wire, judgeModel = "jm", judgeReasoningEffort = "low",
            capture = capture, openLaneTabs = false,
        )
        val run = TestRun("run-1", suite, emptyList(), emptyList(), config, config.lanes.map { com.indagium.testing.model.LaneResult(it.id, it) })
        val decoded = decodeRunFile(encodeRunFile(run)).getOrThrow().config

        assertEquals("m-1", decoded.lanes[0].model)
        assertEquals("", decoded.lanes[0].reasoningEffort)
        assertNull(decoded.lanes[1].model)
        assertNull(decoded.lanes[1].reasoningEffort)
        assertEquals("jm", decoded.judgeModel)
        assertEquals("low", decoded.judgeReasoningEffort)
        assertEquals(false, decoded.openLaneTabs)
        assertEquals(capture.copy(adbPath = capture.adbPath), decoded.capture)
        assertEquals(CaptureMirrorMode.DISABLED, decoded.capture?.effectiveMirrorMode)

        val newerKeys = setOf("model", "reasoningEffort", "judgeModel", "judgeReasoningEffort", "openLaneTabs", "capture")
        val old = dropKeys(Json.parseToJsonElement(encodeRunFile(run)), newerKeys)
        val oldConfig = decodeRunFile(old.toString()).getOrThrow().config
        assertNull(oldConfig.lanes[0].model)
        assertNull(oldConfig.judgeModel)
        assertNull(oldConfig.capture)
        assertEquals(true, oldConfig.openLaneTabs, "an older run file had a lane tab for nobody: the default stays on")
    }

    @Test
    fun effectiveModelsAreWrittenIntoLanesThatHaveAProfileAndExternalLanesStayAsTheyAre() {
        val profile = profileOfKind(AiProviderKind.ANTHROPIC_API, model = "claude-x", effort = "low")
        val config = RunConfig(
            "suite", null,
            listOf(
                LaneConfig(kind = LaneKind.AGENT_PROFILE, profileId = profile.id, deviceSerial = "A", reasoningEffort = "high"),
                LaneConfig(kind = LaneKind.EXTERNAL, deviceSerial = "B"),
            ),
            judgeProfileId = profile.id, judgeMode = JudgeMode.EVERY_STEP.wire,
        )
        val frozen = config.withEffectiveModels(listOf(profile))
        assertEquals("claude-x", frozen.lanes[0].model)
        assertEquals("high", frozen.lanes[0].reasoningEffort)
        assertNull(frozen.lanes[1].model)
        assertEquals("claude-x", frozen.judgeModel)
        assertEquals("low", frozen.judgeReasoningEffort)
        assertEquals("claude-x · high effort", frozen.lanes[0].modelAndEffortLabel())
    }
}
