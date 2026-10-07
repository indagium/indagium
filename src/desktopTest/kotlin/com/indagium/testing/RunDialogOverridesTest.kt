package com.indagium.testing

import com.indagium.ai.LlmModel
import com.indagium.ai.ModelDiscoveryResult
import com.indagium.capture.CaptureSettings
import com.indagium.model.AiProviderKind
import com.indagium.model.AiProviderProfile
import com.indagium.testing.model.EvidenceFlags
import com.indagium.testing.model.JudgeMode
import com.indagium.testing.model.TestingSettings
import com.indagium.ui.LaneChoice
import com.indagium.ui.LaneDraft
import com.indagium.ui.RunDialogModel
import com.indagium.ui.effortChoicesFor
import com.indagium.ui.toConfig
import com.indagium.ui.withCaptureDefaults
import com.indagium.ui.withRunConfig
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** What the run dialog does with the per-lane model and effort, the judge's, and the recording section (no Compose). */
class RunDialogOverridesTest {
    private val claude = AiProviderProfile("p1", "Claude", "", "sonnet", kind = AiProviderKind.CLAUDE_CODE_ACCOUNT)
    private val codex = AiProviderProfile("p2", "Codex", "", "", kind = AiProviderKind.CODEX_ACCOUNT)
    private val choices = listOf(LaneChoice("p1", "Claude"), LaneChoice("p2", "Codex"), LaneChoice(null, "External"))
    private val all = listOf("c1", "c2")

    @Test
    fun laneModelsEffortsAndTheJudgesBecomeTheConfigAndExternalLanesKeepNone() {
        val model = RunDialogModel(
            "suite", all.toSet(), choices[0], "SER-A", firstLaneModel = " opus ", firstLaneEffort = "max",
            moreLanes = listOf(
                LaneDraft("lane-x", choices[1], "SER-B", model = "gpt-x", reasoningEffort = ""),
                LaneDraft("lane-y", choices[2], "SER-C", model = "ignored", reasoningEffort = "high"),
            ),
            judgeProfileId = "p2", judgeMode = JudgeMode.FAILURES_ONLY, judgeModel = "judge-m", judgeReasoningEffort = "low",
        )
        val config = model.toConfig(all).getOrThrow()

        assertEquals("opus", config.lanes[0].model, "trimmed")
        assertEquals("max", config.lanes[0].reasoningEffort)
        assertEquals("gpt-x", config.lanes[1].model)
        assertEquals("", config.lanes[1].reasoningEffort, "an empty effort asks for the model's default")
        assertNull(config.lanes[2].model)
        assertNull(config.lanes[2].reasoningEffort)
        assertEquals("judge-m", config.judgeModel)
        assertEquals("low", config.judgeReasoningEffort)
    }

    @Test
    fun nothingPickedMeansNoOverrideAndAnOffJudgeCarriesNone() {
        val model = RunDialogModel("suite", all.toSet(), choices[0], "SER-A", judgeModel = "m", judgeReasoningEffort = "low")
        val config = model.toConfig(all).getOrThrow()
        assertNull(config.lanes.single().model)
        assertNull(config.lanes.single().reasoningEffort)
        assertNull(config.judgeModel, "the judge is off")
        assertNull(config.judgeReasoningEffort)
    }

    @Test
    fun theRecordingStartsFromTheSavedCaptureSettingsAndIsPartOfTheConfig() {
        val saved = CaptureSettings(recordVideo = false, audio = true, bufferMode = com.indagium.capture.CaptureBufferMode.ALL)
        val base = RunDialogModel("suite", all.toSet(), choices[0], "SER-A")
        val model = base.withCaptureDefaults(saved, TestingSettings())
        assertEquals(saved, model.capture, "defaults are the saved capture settings")
        assertTrue(model.openLaneTabs, "a live tab per lane is on by default")

        val edited = model.copy(capture = model.capture!!.copy(recordVideo = true), openLaneTabs = false)
        val config = edited.toConfig(all).getOrThrow()
        assertEquals(true, config.capture?.recordVideo)
        assertEquals(false, config.openLaneTabs)
        assertEquals(true, config.evidence.video, "recording the screen is what keeps the video as evidence")
        assertEquals(false, saved.recordVideo, "the saved settings are not edited")
    }

    @Test
    fun savedVideoEvidenceInSettingsTestingTurnsRecordingOnByDefault() {
        val saved = CaptureSettings(recordVideo = false)
        val testing = TestingSettings(evidence = EvidenceFlags(video = true))
        val model = RunDialogModel("suite", all.toSet(), choices[0], "SER-A").withCaptureDefaults(saved, testing)
        assertEquals(true, model.capture?.recordVideo)
    }

    @Test
    fun aRerunPrefillsTheOriginalOverridesAndRecording() {
        val original = RunDialogModel(
            "suite", all.toSet(), choices[0], "SER-A", firstLaneModel = "opus", firstLaneEffort = "high",
            capture = CaptureSettings(audio = true), openLaneTabs = false, judgeProfileId = "p2", judgeMode = JudgeMode.EVERY_STEP, judgeModel = "jm",
        ).toConfig(all).getOrThrow()
        val prefilled = RunDialogModel("suite", all.toSet(), choices[1], "SER-Z", capture = CaptureSettings()).withRunConfig(original, choices)

        assertEquals("opus", prefilled.firstLaneModel)
        assertEquals("high", prefilled.firstLaneEffort)
        assertEquals("jm", prefilled.judgeModel)
        assertEquals(true, prefilled.capture?.audio)
        assertEquals(false, prefilled.openLaneTabs)
        assertEquals("opus", prefilled.toConfig(all).getOrThrow().lanes.single().model)
    }

    @Test
    fun theEffortPickerOffersTheCatalogsLevelsElseTheKindsFixedSetAndNothingForCodexWithoutACatalog() {
        val catalog = ModelDiscoveryResult.Available(listOf(LlmModel("sonnet", "Sonnet", listOf("low", "high")), LlmModel("plain", "Plain")))
        assertEquals(listOf("low", "high"), effortChoicesFor(claude, "sonnet", catalog), "what the catalog lists for the model")
        assertEquals(emptyList(), effortChoicesFor(claude, "plain", catalog), "a model that exposes none hides the picker")
        assertEquals(listOf("low", "medium", "high", "xhigh", "max"), effortChoicesFor(claude, "opus", null), "no catalog: the kind's set")
        val openAi = claude.copy(kind = AiProviderKind.OPENAI_API)
        assertEquals(listOf("low", "medium", "high"), effortChoicesFor(openAi, "x", ModelDiscoveryResult.Unavailable("offline")))
        assertEquals(emptyList(), effortChoicesFor(codex, "gpt", null), "Codex lists its levels per model: without a catalog there is nothing to offer")
    }
}
