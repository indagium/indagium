@file:Suppress("MagicNumber") // Fixture numbers, not tunable constants.

package com.indagium.testing

import com.indagium.model.AiUsageStats
import com.indagium.testing.authoring.ImproveEvidenceSummary
import com.indagium.testing.authoring.ImproveGeneration
import com.indagium.testing.authoring.ImproveRunSource
import com.indagium.testing.authoring.ImproveStepEdit
import com.indagium.testing.authoring.TestCaseImproveProposal
import com.indagium.testing.authoring.TestCaseImproveService
import com.indagium.testing.authoring.asEdit
import com.indagium.testing.model.JudgeClassification
import com.indagium.testing.model.JudgeVerdict
import com.indagium.testing.model.StepStatus
import com.indagium.testing.model.TestCase
import com.indagium.testing.model.TestStep
import com.indagium.testing.store.StepChange
import com.indagium.testing.store.StoreResult
import com.indagium.testing.store.TestLibraryStore
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import java.io.File
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** The preview-first service: a proposal changes nothing, applying writes only the accepted steps, and a stale or spent proposal is refused. */
class TestCaseImproveServiceTest {
    private lateinit var dir: File
    private lateinit var store: TestLibraryStore
    private var clock = 1_000L
    private var preflight: () -> StoreResult<Unit> = { StoreResult.Ok(Unit) }
    private val generations = mutableListOf<ImproveGeneration>()

    @BeforeTest
    fun setUp() {
        dir = tempTestingDir()
        store = TestLibraryStore(dir)
        store.createScript(improveScript())
        val suite = (store.createSuite("Shop suite", "", "Use the staging account.") as StoreResult.Ok).value
        store.updateSuite(suite.id) { it.copy(targetPackage = "com.example.shop") }
        assertIs<StoreResult.Ok<*>>(store.createCase(suite.id, improveSuite().cases.single()))
    }

    @AfterTest
    fun tearDown() {
        dir.deleteRecursively()
    }

    private fun savedSteps(): List<TestStep> = store.library.value.findCase(IMPROVE_CASE_ID)!!.case.steps

    private fun entry(step: TestStep, action: String = step.action, expected: String = step.expected, reason: String = "unchanged", extra: String = "") =
        """{"stepId":"${step.id}","action":"$action","expected":"$expected","reason":"$reason"$extra}"""

    /** An answer that rewords the first two steps and keeps the other two. */
    private fun answer(steps: List<TestStep> = savedSteps()): String = """{"steps":[""" + listOf(
        entry(steps[0], action = "Launch the app from the launcher", reason = "More precise"),
        entry(steps[1], expected = "A list of search results is shown", reason = "An ad is not stable"),
        entry(steps[2]),
        entry(steps[3]),
    ).joinToString(",") + "]}"

    private fun service(
        generate: suspend (ImproveGeneration) -> String = { generations += it; answer() },
        runs: ImproveRunSource = FakeImproveRuns(dir),
        acceptsImages: (String) -> Boolean = { true },
        timeoutMs: Long = 30_000L,
    ) = TestCaseImproveService(
        library = { store.library.value },
        preflight = { _, _ -> preflight() },
        generate = generate,
        runs = runs,
        goldenFile = { _, path -> File(dir, path).takeIf { it.isFile } },
        applyChanges = { caseId, changes: List<StepChange>, usage -> store.updateSteps(caseId, changes, usage) },
        acceptsImagesUpFront = acceptsImages,
        now = { clock },
        timeoutMs = timeoutMs,
    )

    private fun create(service: TestCaseImproveService, note: String = "") =
        runBlocking { service.create(IMPROVE_CASE_ID, IMPROVE_PROFILE_ID, note) }

    private fun proposalOf(result: StoreResult<TestCaseImproveProposal>): TestCaseImproveProposal =
        assertIs<StoreResult.Ok<TestCaseImproveProposal>>(result).value

    // ── Preview, then apply ──────────────────────────────────────────

    @Test
    fun generatingOnlyMakesAPreviewAndApplyingWritesJustTheAcceptedStepsInOneWrite() {
        val before = store.library.value
        val service = service()
        val proposal = proposalOf(create(service))
        assertEquals(before, store.library.value, "a preview must not change the library")
        assertEquals(2, proposal.steps.count { it.changed })

        val applied = service.apply(proposal.id, proposal.steps.map { it.asEdit() }, setOf(STEP_SEARCH))

        assertEquals(listOf(STEP_SEARCH), assertIs<StoreResult.Ok<List<TestStep>>>(applied).value.map { it.id })
        val saved = savedSteps()
        assertEquals("Open the app", saved[0].action, "the step that was not accepted stays as it was")
        assertEquals("A list of search results is shown", saved[1].expected)
        assertEquals(before.findCase(IMPROVE_CASE_ID)!!.case.steps[1].checks, saved[1].checks)
        assertTrue(service.apply(proposal.id, proposal.steps.map { it.asEdit() }) is StoreResult.Invalid, "a proposal is one-use")
    }

    @Test
    fun withoutAnAcceptListEveryChangedStepIsWrittenAndTheReviewedTextIsWhatIsSaved() {
        val service = service()
        val proposal = proposalOf(create(service))
        val edits = proposal.steps.map { it.asEdit() }.map { if (it.stepId == STEP_OPEN) it.copy(action = "Open the shop app") else it }

        assertIs<StoreResult.Ok<*>>(service.apply(proposal.id, edits))

        val saved = savedSteps()
        assertEquals("Open the shop app", saved[0].action, "the person's edit wins over the proposal")
        assertEquals("A list of search results is shown", saved[1].expected)
        assertEquals(improveSteps()[2], saved[2])
        assertEquals(improveSteps()[3], saved[3])
    }

    @Test
    fun theUsageOfTheGenerationIsAddedToTheCaseOnlyWhenTheProposalIsApplied() {
        val service = service(generate = { request ->
            request.onUsage(AiUsageStats(inputTokens = 100, outputTokens = 20, totalTokens = 120))
            answer()
        })
        val proposal = proposalOf(create(service))
        assertNull(store.library.value.findCase(IMPROVE_CASE_ID)!!.case.creationUsage)
        assertEquals(120L, proposal.usage?.totalTokens)

        service.apply(proposal.id, proposal.steps.map { it.asEdit() })

        assertEquals(120L, store.library.value.findCase(IMPROVE_CASE_ID)!!.case.creationUsage?.totalTokens)
    }

    @Test
    fun anAnswerThatChangesNothingIsAProposalWithNothingToApply() {
        val service = service(generate = { """{"steps":[""" + savedSteps().joinToString(",") { entry(it) } + "]}" })
        val proposal = proposalOf(create(service))

        assertTrue(proposal.steps.none { it.changed })
        val result = service.apply(proposal.id, proposal.steps.map { it.asEdit() })

        assertTrue("nothing to apply" in assertIs<StoreResult.Invalid>(result).reason)
        assertNotNull(service.peek(proposal.id), "a refusal the person can fix keeps the proposal")
    }

    // ── Refusals ─────────────────────────────────────────────────────

    @Test
    fun aProposalWhoseCaseChangedSinceIsRefusedAndDropped() {
        val service = service()
        val proposal = proposalOf(create(service))
        store.updateStep(STEP_OPEN) { it.copy(expected = "The person edited this meanwhile") }

        val result = service.apply(proposal.id, proposal.steps.map { it.asEdit() })

        assertTrue("changed since" in assertIs<StoreResult.Invalid>(result).reason)
        assertEquals("The person edited this meanwhile", savedSteps()[0].expected)
        assertNull(service.peek(proposal.id))
    }

    @Test
    fun aCaseEditedWhileTheModelWasAnsweringGivesNoProposal() {
        val service = service(generate = {
            store.updateStep(STEP_OPEN) { it.copy(action = "Edited during generation") }
            answer()
        })

        val result = create(service)

        assertTrue("changed while the improvement" in assertIs<StoreResult.Invalid>(result).reason)
    }

    @Test
    fun theStoreWritesNothingWhenOneStepWasEditedAfterTheChangeWasPlanned() {
        val steps = savedSteps()
        val changes = listOf(
            StepChange(steps[0], steps[0].copy(action = "New 1")),
            StepChange(steps[1].copy(expected = "stale copy"), steps[1].copy(action = "New 2")),
        )

        val result = store.updateSteps(IMPROVE_CASE_ID, changes)

        assertTrue("edited after" in assertIs<StoreResult.Invalid>(result).reason)
        assertEquals(steps, savedSteps(), "all or nothing")
    }

    @Test
    fun aLockedOrReadOnlyCaseIsRefusedBeforeAnyPaidGenerationAndAgainAtApply() {
        val service = service()
        preflight = { StoreResult.Invalid("This case is locked by the Free limit.") }
        val calls = AtomicInteger()
        val refusing = service(generate = { calls.incrementAndGet(); answer() })
        assertEquals("This case is locked by the Free limit.", assertIs<StoreResult.Invalid>(create(refusing)).reason)
        assertEquals(0, calls.get())

        preflight = { StoreResult.Ok(Unit) }
        val proposal = proposalOf(create(service))
        preflight = { StoreResult.Invalid("This case is read-only.") }
        assertEquals("This case is read-only.", assertIs<StoreResult.Invalid>(service.apply(proposal.id, proposal.steps.map { it.asEdit() })).reason)
        assertNotNull(service.peek(proposal.id), "a lock that is lifted later lets the same preview be applied")
        preflight = { StoreResult.Ok(Unit) }
        assertIs<StoreResult.Ok<*>>(service.apply(proposal.id, proposal.steps.map { it.asEdit() }))
    }

    @Test
    fun aProposalExpires() {
        val service = service()
        val proposal = proposalOf(create(service))
        clock += 31 * 60 * 1_000L

        val result = service.apply(proposal.id, proposal.steps.map { it.asEdit() })

        assertTrue("expired or was already applied" in assertIs<StoreResult.Invalid>(result).reason)
    }

    @Test
    fun anInvalidAnswerGivesNoProposalAndLeavesTheCaseFreeToTryAgain() {
        val answers = ArrayDeque(listOf("""{"steps":[]}""", answer()))
        val service = service(generate = { answers.removeFirst() })

        assertTrue("exactly one entry" in assertIs<StoreResult.Invalid>(create(service)).reason)
        assertIs<StoreResult.Ok<*>>(create(service))
    }

    @Test
    fun aSecondRequestForTheSameCaseWhileOneRunsIsRefused() = runBlocking<Unit> {
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val service = service(generate = { entered.complete(Unit); release.await(); answer() })

        val first = async { service.create(IMPROVE_CASE_ID, IMPROVE_PROFILE_ID) }
        withTimeout(5_000) { entered.await() }
        val second = service.create(IMPROVE_CASE_ID, IMPROVE_PROFILE_ID)

        assertTrue("already being generated" in assertIs<StoreResult.Invalid>(second).reason)
        release.complete(Unit)
        assertIs<StoreResult.Ok<*>>(first.await())
    }

    @Test
    fun theNoteTheCaseSizeAndTheTargetAreChecked() = runBlocking<Unit> {
        val service = service()
        assertTrue("limited to" in assertIs<StoreResult.Invalid>(service.create(IMPROVE_CASE_ID, IMPROVE_PROFILE_ID, "x".repeat(1_001))).reason)
        assertIs<StoreResult.NotFound>(service.create("case-missing", IMPROVE_PROFILE_ID))
        val suiteId = store.library.value.suites.single().id
        val empty = (store.createCase(suiteId, TestCase("", "Empty")) as StoreResult.Ok).value
        assertTrue("no steps" in assertIs<StoreResult.Invalid>(service.create(empty.id, IMPROVE_PROFILE_ID)).reason)
        val manySteps = List(41) { TestStep("step-big-$it", "Step $it", "Done $it") }
        val big = (store.createCase(suiteId, TestCase("", "Big", steps = manySteps)) as StoreResult.Ok).value
        assertTrue("up to 40 steps" in assertIs<StoreResult.Invalid>(service.create(big.id, IMPROVE_PROFILE_ID)).reason)
    }

    @Test
    fun aReviewThatDoesNotMatchTheProposalOrBreaksTheRulesIsRefusedAndKept() {
        val service = service()
        val proposal = proposalOf(create(service))
        val edits = proposal.steps.map { it.asEdit() }

        assertTrue("in its order" in assertIs<StoreResult.Invalid>(service.apply(proposal.id, edits.reversed())).reason)
        assertTrue("Unknown step id" in assertIs<StoreResult.Invalid>(service.apply(proposal.id, edits, setOf("step-nope"))).reason)
        val manualOptional = edits.map { if (it.stepId == STEP_USB) it.copy(optional = true, condition = "when at hand") else it }
        assertTrue("manual step cannot be optional" in assertIs<StoreResult.Invalid>(service.apply(proposal.id, manualOptional, setOf(STEP_USB))).reason)
        val blank = edits.map { if (it.stepId == STEP_OPEN) it.copy(action = " ") else it }
        assertTrue("Step 1" in assertIs<StoreResult.Invalid>(service.apply(proposal.id, blank)).reason)
        assertNotNull(service.peek(proposal.id))
        assertEquals(improveSteps(), savedSteps(), "nothing was written by any refused apply")
        assertIs<StoreResult.Ok<*>>(service.apply(proposal.id, edits))
    }

    @Test
    fun aManualOrScriptStepOnlyEverGetsNewWords() {
        val service = service(generate = {
            val steps = savedSteps()
            """{"steps":[""" + listOf(
                entry(steps[0]),
                entry(steps[1]),
                entry(steps[2], action = "Connect the USB stick to the phone", reason = "Clearer"),
                entry(steps[3], expected = "The app starts with no data", reason = "More exact"),
            ).joinToString(",") + "]}"
        })
        val proposal = proposalOf(create(service))

        assertIs<StoreResult.Ok<*>>(service.apply(proposal.id, proposal.steps.map { it.asEdit() }))

        val saved = savedSteps()
        assertEquals("Connect the USB stick to the phone", saved[2].action)
        assertEquals(improveSteps()[2].checks, saved[2].checks)
        assertEquals(improveSteps()[2].kind, saved[2].kind)
        assertEquals("The app starts with no data", saved[3].expected)
        assertEquals(improveSteps()[3].script, saved[3].script)
    }

    // ── What the model is given ──────────────────────────────────────

    private fun runWithEvidence(): FakeImproveRuns {
        val folder = File(dir, "run-evidence")
        val results = savedSteps().take(2).mapIndexed { index, step ->
            improveResult(
                step.id, index + 1, step.action, step.expected,
                if (index == 1) StepStatus.FAIL else StepStatus.PASS,
                shot = folder.writeLaneShot("lane-1", "s$index", 60 + index * 60),
                judge = improveJudgement(JudgeVerdict.FAIL.takeIf { index == 1 } ?: JudgeVerdict.PASS, "Judge text $index", JudgeClassification.UNKNOWN),
            )
        }
        return FakeImproveRuns(dir, improveRun("run-evidence", 5_000L, listOf(improveCaseResultOf(results))))
    }

    @Test
    fun anAgentThatTakesImagesGetsTheScreensWithTheFirstMessageAndAFallbackWithoutThem() {
        val service = service(runs = runWithEvidence())

        val proposal = proposalOf(create(service, "make it stable"))

        val request = generations.single()
        val attachment = assertNotNull(request.attachment)
        assertEquals(2, attachment.images.size, "the end screen of each of the two steps that ran")
        assertTrue("Evidence is attached to this message as 2 image(s)" in request.prompt)
        assertTrue("NOT attached" in attachment.fallbackPrompt && "NOT attached" !in request.prompt)
        assertEquals(1, attachment.fallbackToolCallLimit)
        assertEquals(2, proposal.evidence.runScreenshots)
        assertEquals("run-evidence", proposal.runId)
        assertTrue("make it stable" in request.prompt)
    }

    @Test
    fun anAgentThatTakesNoImagesGetsTheTextEvidenceOnly() {
        val service = service(runs = runWithEvidence(), acceptsImages = { false })

        val proposal = proposalOf(create(service))

        val request = generations.single()
        assertNull(request.attachment)
        assertTrue("NOT attached and you have no tool" in request.prompt)
        assertTrue("Judge text 1" in request.prompt, "the judge's reasoning still describes the screens")
        assertEquals(0, proposal.evidence.runScreenshots)
    }

    @Test
    fun theDialogCanAskWhatWouldBeSentBeforeGenerating() = runBlocking<Unit> {
        val service = service(runs = runWithEvidence())

        val summary = assertIs<StoreResult.Ok<ImproveEvidenceSummary>>(service.describeEvidence(IMPROVE_CASE_ID, null)).value

        assertEquals("run-evidence", summary.runId)
        assertEquals(2, summary.runScreenshots)
        assertEquals(1, summary.problemSteps)
        assertEquals(0, generations.size, "describing sends nothing")
        assertIs<StoreResult.NotFound>(service.describeEvidence("case-nope"))
    }

    @Test
    fun aNamedRunThatDoesNotExistIsNotFound() = runBlocking<Unit> {
        val service = service()
        assertIs<StoreResult.NotFound>(service.create(IMPROVE_CASE_ID, IMPROVE_PROFILE_ID, runId = "run-nope"))
    }

    @Test
    fun theStepEditsOfAProposalCarryExactlyTheFieldsAReviewerMayChange() {
        val proposal = proposalOf(create(service()))
        val edit: ImproveStepEdit = proposal.steps[1].asEdit()

        assertEquals(ImproveStepEdit(STEP_SEARCH, "Tap search", "A list of search results is shown", false, null), edit)
        assertFalse(proposal.steps[0].previous == proposal.steps[0].proposed)
    }
}
