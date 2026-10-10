@file:Suppress("MagicNumber") // Fixture numbers, not tunable constants.

package com.indagium.testing

import com.indagium.testing.authoring.IMPROVE_MAX_ATTACHED_IMAGES
import com.indagium.testing.authoring.IMPROVE_MAX_GOLDEN_IMAGES
import com.indagium.testing.authoring.IMPROVE_SYSTEM_PROMPT
import com.indagium.testing.authoring.ImageRole
import com.indagium.testing.authoring.ImproveImages
import com.indagium.testing.authoring.ImproveRunOccurrence
import com.indagium.testing.authoring.STABLE_EXPECTED_RULE
import com.indagium.testing.authoring.buildImproveEvidencePlan
import com.indagium.testing.authoring.improvePrompt
import com.indagium.testing.authoring.loadImproveImages
import com.indagium.testing.authoring.occurrenceOf
import com.indagium.testing.authoring.pickImproveRun
import com.indagium.testing.model.CaseResult
import com.indagium.testing.model.CheckResult
import com.indagium.testing.model.CheckStatus
import com.indagium.testing.model.JudgeClassification
import com.indagium.testing.model.JudgeVerdict
import com.indagium.testing.model.RunStatus
import com.indagium.testing.model.StepExample
import com.indagium.testing.model.StepFix
import com.indagium.testing.model.StepStatus
import com.indagium.testing.model.TestRun
import com.indagium.testing.model.TestStep
import com.indagium.testing.store.StoreResult
import kotlinx.coroutines.runBlocking
import java.io.File
import kotlin.io.path.createTempDirectory
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** What an improvement is handed: the latest run's evidence per step (blind to the agent), the golden screenshots, and the words around them. */
class TestCaseImproveEvidenceTest {
    private lateinit var dir: File

    @BeforeTest
    fun setUp() {
        dir = createTempDirectory("improve-evidence").toFile()
    }

    @AfterTest
    fun tearDown() {
        dir.deleteRecursively()
    }

    private fun runDirOf(id: String) = File(dir, id)

    private fun otherCase() = CaseResult("another-case", "Other", steps = listOf(improveResult("s", 1, "a", "b")))

    private fun goldenFiles(assetPath: String): File? = File(dir, assetPath).takeIf { it.isFile }

    private fun failedRun(id: String = "run-new", createdAt: Long = 2_000L): TestRun {
        val folder = runDirOf(id)
        val openShot = folder.writeLaneShot("lane-1", "open", 40)
        val searchShot = folder.writeLaneShot("lane-1", "search", 90)
        val usbShot = folder.writeLaneShot("lane-1", "usb", 140)
        val result = improveCaseResult(
            improveResult(STEP_OPEN, 1, "Open the app", "Home is visible", shot = openShot),
            improveResult(
                STEP_SEARCH, 2, "Tap search", "The first result is an ad", StepStatus.FAIL, shot = searchShot,
                judge = improveJudgement(
                    JudgeVerdict.FAIL, "The results list is shown but no ad is.", JudgeClassification.AGENT_OR_STEP_PROBLEM,
                    StepFix(action = null, expected = "A list of search results is shown", note = "Do not require an ad."),
                ),
                checks = listOf(CheckResult("chk-log", "logAppears", CheckStatus.PASS, "matched Search ok")),
                agentError = "The agent tapped twice.",
            ),
            improveResult(STEP_USB, 3, "Plug in the USB stick", "The stick is mounted", StepStatus.TIMEOUT, shot = usbShot),
        )
        return improveRun(id, createdAt, listOf(result))
    }

    // ── Which run is the evidence ────────────────────────────────────

    @Test
    fun theNewestFinishedRunWithTheCaseIsTheEvidenceAndAnUnfinishedOneIsSkipped() = runBlocking<Unit> {
        val older = improveRun("run-old", 100L, listOf(improveCaseResult(improveResult(STEP_OPEN, 1, "Open the app", "Home is visible"))))
        val unrelated = improveRun("run-other", 200L, listOf(otherCase()))
        val running = improveRun("run-live", 300L, listOf(improveCaseResult(improveResult(STEP_OPEN, 1, "Open the app", "Home"))), status = RunStatus.RUNNING)
        val source = FakeImproveRuns(dir, older, unrelated, running)

        val picked = assertIs<StoreResult.Ok<*>>(pickImproveRun(source, IMPROVE_SUITE_ID, IMPROVE_CASE_ID, null))
        assertEquals("run-old", (picked.value as ImproveRunOccurrence).run.id)

        val newestResult = improveCaseResult(improveResult(STEP_OPEN, 1, "Open the app", "Home is visible"))
        val newest = improveRun("run-newest", 400L, listOf(newestResult), status = RunStatus.PASSED)
        val all = FakeImproveRuns(dir, older, unrelated, running, newest)
        val again = assertIs<StoreResult.Ok<*>>(pickImproveRun(all, IMPROVE_SUITE_ID, IMPROVE_CASE_ID, null))
        assertEquals("run-newest", (again.value as ImproveRunOccurrence).run.id)
    }

    @Test
    fun aNamedRunIsUsedWhenFinishedAndRefusedWhenItIsNotOrHasNoResultsOfTheCase() = runBlocking<Unit> {
        val finished = improveRun("run-a", 100L, listOf(improveCaseResult(improveResult(STEP_OPEN, 1, "Open the app", "Home is visible"))))
        val running = improveRun("run-b", 200L, listOf(improveCaseResult(improveResult(STEP_OPEN, 1, "Open the app", "Home"))), status = RunStatus.RUNNING)
        val other = improveRun("run-c", 300L, listOf(otherCase()))
        val source = FakeImproveRuns(dir, finished, running, other)

        assertIs<StoreResult.Ok<*>>(pickImproveRun(source, IMPROVE_SUITE_ID, IMPROVE_CASE_ID, "run-a"))
        assertTrue("not finished" in assertIs<StoreResult.Invalid>(pickImproveRun(source, IMPROVE_SUITE_ID, IMPROVE_CASE_ID, "run-b")).reason)
        assertTrue("no step results" in assertIs<StoreResult.Invalid>(pickImproveRun(source, IMPROVE_SUITE_ID, IMPROVE_CASE_ID, "run-c")).reason)
        assertIs<StoreResult.NotFound>(pickImproveRun(source, IMPROVE_SUITE_ID, IMPROVE_CASE_ID, "run-missing"))
    }

    @Test
    fun withoutAnyRunOfTheCaseThereIsNoEvidenceButNotAnError() = runBlocking<Unit> {
        val result = pickImproveRun(FakeImproveRuns(dir), IMPROVE_SUITE_ID, IMPROVE_CASE_ID, null)
        assertNull(assertIs<StoreResult.Ok<*>>(result).value)
    }

    @Test
    fun ofSeveralLanesAndRepeatsTheOneWithTheMostProblemsIsUsed() {
        val good = improveCaseResult(improveResult(STEP_OPEN, 1, "Open the app", "Home is visible"), iteration = 1)
        val bad = improveCaseResult(improveResult(STEP_OPEN, 1, "Open the app", "Home is visible", StepStatus.FAIL), iteration = 2)
        val run = improveRun("run-x", 10L, listOf(good, bad), lanes = 2)

        val occurrence = assertNotNull(run.occurrenceOf(IMPROVE_CASE_ID))

        assertEquals(1, occurrence.laneNumber, "among equals the earlier lane")
        assertEquals(2, occurrence.iteration, "the repeat that went wrong")
    }

    // ── What is collected per step ───────────────────────────────────

    @Test
    fun eachStepGetsItsEndScreenTheStartScreenOfTheNextAndOnlyWhatTheJudgeSaid() {
        val run = failedRun()
        val case = improveSuite().cases.single()

        val plan = buildImproveEvidencePlan(case, run.occurrenceOf(IMPROVE_CASE_ID), runDirOf(run.id), ::goldenFiles)

        val search = plan.steps.getValue(STEP_SEARCH)
        assertEquals(StepStatus.FAIL, search.status)
        assertEquals("lanes/lane-1/screens/open.png", search.startShot, "the start screen is the end of the step before")
        assertEquals("lanes/lane-1/screens/search.png", search.endShot)
        assertEquals(JudgeVerdict.FAIL, search.verdict)
        assertEquals("A list of search results is shown", search.suggestedFix?.expected)
        assertEquals("The agent tapped twice.", search.personNote)
        assertNull(plan.steps[STEP_RESET], "a step the run never reached has no result")
        assertEquals(2, plan.summary.problemSteps)
        assertEquals(1, plan.summary.judgeSuggestions)
        // The end of step 2 is the start of step 3: one image, introduced under both names.
        val shared = plan.slots.single { slot -> slot.refs.any { it.stepId == STEP_SEARCH && it.role == ImageRole.END } }
        assertTrue(shared.refs.any { it.stepId == STEP_USB && it.role == ImageRole.START }, shared.label())
    }

    @Test
    fun theEvidenceHasNoFieldForTheAgentAndNoPromptCarriesItsWords() = runBlocking<Unit> {
        val run = failedRun()
        val case = improveSuite().cases.single()
        val plan = buildImproveEvidencePlan(case, run.occurrenceOf(IMPROVE_CASE_ID), runDirOf(run.id)) { null }
        val images = loadImproveImages(plan)

        val withImages = improvePrompt(improveSuite(), case, "make it stable", plan, images, attachImages = true)
        val withoutImages = improvePrompt(improveSuite(), case, "make it stable", plan, ImproveImages.NONE, attachImages = false)

        listOf(withImages, withoutImages, IMPROVE_SYSTEM_PROMPT).forEach { text ->
            assertFalse(AGENT_CLAIM_TEXT in text, "the agent's claim must not reach the improver")
            assertFalse(AGENT_OBSERVATION_TEXT in text, "the agent's observation must not reach the improver")
        }
        assertTrue("The results list is shown but no ad is." in withImages)
    }

    @Test
    fun aStepEditedAfterTheRunIsMarkedAndKeepsTheTextItRanAs() {
        val run = failedRun()
        val edited = improveSteps().map { if (it.id == STEP_SEARCH) it.copy(expected = "A list of results is shown") else it }
        val case = improveSuite(edited).cases.single()

        val plan = buildImproveEvidencePlan(case, run.occurrenceOf(IMPROVE_CASE_ID), runDirOf(run.id)) { null }

        assertTrue("The first result is an ad" in assertNotNull(plan.steps.getValue(STEP_SEARCH).textAtRun))
        assertNull(plan.steps.getValue(STEP_OPEN).textAtRun)
    }

    // ── The images ───────────────────────────────────────────────────

    @Test
    fun problemStepScreensComeFirstThenGoldenScreenshotsAndTheLimitsApply() {
        val folder = runDirOf("run-many")
        val steps = List(20) { index ->
            val golden = StepExample.GoldenScreenshot("ex-$index", "gold-$index.png", "G$index")
            TestStep("step-$index", "Step $index", "Result $index", examples = if (index < 6) listOf(golden) else emptyList())
        }
        val results = steps.mapIndexed { index, step ->
            // Steps 14 and 15 fail; every step has an end screen.
            val status = if (index == 14 || index == 15) StepStatus.FAIL else StepStatus.PASS
            improveResult(step.id, index + 1, step.action, step.expected, status, shot = folder.writeLaneShot("lane-1", "s$index", index * 10))
        }
        val case = improveSuite(steps).cases.single()
        val run = improveRun("run-many", 5L, listOf(improveCaseResultOf(results)), suite = improveSuite(steps))
        File(dir, "golden").mkdirs()
        repeat(6) { File(dir, "golden/gold-$it.png").writeBytes(improvePng(200)) }

        val plan = buildImproveEvidencePlan(case, run.occurrenceOf(IMPROVE_CASE_ID), folder) { path -> File(dir, "golden/$path").takeIf { it.isFile } }

        assertEquals(IMPROVE_MAX_ATTACHED_IMAGES, plan.slots.size)
        val labels = plan.slots.map { it.label() }
        // Problem steps 15 and 16 (and the end of step 14, their neighbour's start) come before everything else.
        assertTrue("step 15 end screen" in labels[0] || "step 15 start screen" in labels[0], labels.toString())
        assertEquals(IMPROVE_MAX_GOLDEN_IMAGES, plan.slots.count { it.golden }, "golden screenshots are capped")
        assertTrue(plan.leftOut.isNotEmpty(), "what the limits left out is named")
        assertEquals(IMPROVE_MAX_GOLDEN_IMAGES, plan.summary.goldenScreenshots)
    }

    @Test
    fun anUnreadableImageIsNamedAsUnavailableAndTheRestStillGoes() {
        val run = failedRun()
        File(runDirOf(run.id), "lanes/lane-1/screens/search.png").writeBytes(byteArrayOf(1, 2, 3))
        val case = improveSuite().cases.single()
        val plan = buildImproveEvidencePlan(case, run.occurrenceOf(IMPROVE_CASE_ID), runDirOf(run.id)) { null }

        val images = loadImproveImages(plan)

        assertTrue(images.unavailable.any { "step 2 end screen" in it }, images.unavailable.toString())
        assertTrue(images.images.isNotEmpty())
    }

    // ── The words ────────────────────────────────────────────────────

    @Test
    fun thePromptFencesTheRunEvidenceAndHintsStatesTheRulesAndNamesTheImages() = runBlocking<Unit> {
        val run = failedRun()
        File(dir, "home.png").writeBytes(improvePng(10))
        val case = improveSuite().cases.single()
        val plan = buildImproveEvidencePlan(case, run.occurrenceOf(IMPROVE_CASE_ID), runDirOf(run.id), ::goldenFiles)
        val images = loadImproveImages(plan)

        val prompt = improvePrompt(improveSuite(), case, "no ads please", plan, images, attachImages = true)

        assertTrue("What the person wants improved: no ads please" in prompt)
        val evidenceFence = "<untrusted_data source=\"run_evidence\">"
        val logFence = "<untrusted_data source=\"reference_logs\">"
        assertTrue(evidenceFence in prompt && "The results list is shown but no ad is." in prompt.substringAfter(evidenceFence))
        assertTrue(logFence in prompt && IMPROVE_HINT_CAPTION in prompt.substringAfter(logFence))
        assertFalse("ignore previous instructions" in prompt.substringBefore(logFence), "a hint is only ever inside its fence")
        assertTrue("id=$STEP_SEARCH" in prompt && "performed by: a tester, manual" in prompt && "locked checks" in prompt)
        assertTrue("Evidence is attached to this message as ${images.images.size} image(s)" in prompt && "Image 1:" in prompt)
        assertTrue(STABLE_EXPECTED_RULE in IMPROVE_SYSTEM_PROMPT)
        assertTrue("never add" in IMPROVE_SYSTEM_PROMPT.lowercase() || "can never add" in IMPROVE_SYSTEM_PROMPT)
        assertTrue("Never add, remove, reorder, merge or split steps" in prompt)
    }

    @Test
    fun withoutImagesThePromptSaysSoAndHasNoToolToFetchThem() = runBlocking<Unit> {
        val run = failedRun()
        val case = improveSuite().cases.single()
        val plan = buildImproveEvidencePlan(case, run.occurrenceOf(IMPROVE_CASE_ID), runDirOf(run.id)) { null }

        val prompt = improvePrompt(improveSuite(), case, "", plan, ImproveImages.NONE, attachImages = false)

        assertTrue("NOT attached and you have no tool" in prompt, prompt)
        assertFalse("Image 1" in prompt)
    }

    @Test
    fun withoutARunThePromptWorksFromTheStepsAlone() = runBlocking<Unit> {
        val case = improveSuite().cases.single()
        val plan = buildImproveEvidencePlan(case, null, null) { null }

        val prompt = improvePrompt(improveSuite(), case, "", plan, ImproveImages.NONE, attachImages = false)

        assertTrue("No run of this case is available" in prompt)
        assertFalse("run_evidence" in prompt)
        assertTrue("There are no screenshots." in prompt)
    }
}
