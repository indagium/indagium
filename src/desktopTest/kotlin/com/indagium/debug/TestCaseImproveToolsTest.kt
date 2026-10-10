@file:Suppress("MagicNumber") // Fixture counts and timeouts, not tunable constants.

package com.indagium.debug

import com.indagium.ai.LlmRole
import com.indagium.ai.LlmStreamEvent
import com.indagium.edition.Edition
import com.indagium.model.AiProviderKind
import com.indagium.model.AiProviderProfile
import com.indagium.testing.AGENT_CLAIM_TEXT
import com.indagium.testing.AGENT_OBSERVATION_TEXT
import com.indagium.testing.IMPROVE_CASE_ID
import com.indagium.testing.IMPROVE_PROFILE_ID
import com.indagium.testing.STEP_OPEN
import com.indagium.testing.STEP_SEARCH
import com.indagium.testing.STEP_USB
import com.indagium.testing.Turn
import com.indagium.testing.TurnProvider
import com.indagium.testing.improveCaseResultOf
import com.indagium.testing.improveJudgement
import com.indagium.testing.improveResult
import com.indagium.testing.improveRun
import com.indagium.testing.improveScript
import com.indagium.testing.improveSuite
import com.indagium.testing.model.JudgeClassification
import com.indagium.testing.model.JudgeVerdict
import com.indagium.testing.model.StepFix
import com.indagium.testing.model.StepStatus
import com.indagium.testing.model.TestCase
import com.indagium.testing.model.TestStep
import com.indagium.testing.run.LaneAgent
import com.indagium.testing.run.LaneAgentFactory
import com.indagium.testing.run.ProviderLaneAgent
import com.indagium.testing.store.StoreResult
import com.indagium.testing.store.TestRunStore
import com.indagium.testing.writeLaneShot
import com.indagium.ui.AppState
import com.indagium.ui.TestRunOverrides
import kotlinx.coroutines.runBlocking
import java.io.File
import kotlin.io.path.createTempDirectory
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** "Improve steps with AI" over the app state and the gateway: one model turn with the evidence up front, the MCP tools, locks and approval. */
class TestCaseImproveToolsTest {
    private lateinit var dir: File
    private lateinit var state: AppState
    private lateinit var operations: IndagiumToolOperations
    private lateinit var logFile: File
    private var suiteId = ""
    private val caseId = IMPROVE_CASE_ID

    @BeforeTest
    fun setUp() {
        dir = createTempDirectory("improve-tools").toFile()
        logFile = File(dir, "app.log")
        state = AppState(
            autosaveFile = File(dir, "state.cache"),
            autoExportNotes = false,
            notesDir = File(dir, "notes"),
            archiveCacheDir = File(dir, "archive"),
            customCommandsDir = File(dir, "commands"),
            controlTokenFile = File(dir, "token"),
            sourceIndexFile = File(dir, "source-index"),
            testingDir = File(dir, "testing"),
        )
        useProfile(AiProviderKind.OPENAI_COMPATIBLE, "http://127.0.0.1:1234/v1")
        check(state.createTestScript(improveScript()) is StoreResult.Ok)
        suiteId = (state.createTestSuite("Shop suite") as StoreResult.Ok).value.id
        state.updateTestSuite(suiteId) { it.copy(targetPackage = "com.example.shop") }
        check(state.createTestCase(suiteId, improveSuite().cases.single()) is StoreResult.Ok)
        operations = IndagiumToolOperations(state)
        AppLogger.configure(enabled = true, path = logFile.absolutePath)
    }

    @AfterTest
    fun tearDown() {
        AppLogger.configure(enabled = false, path = null)
        state.close()
        dir.deleteRecursively()
    }

    private fun useProfile(kind: AiProviderKind, baseUrl: String) {
        val profile = AiProviderProfile(id = IMPROVE_PROFILE_ID, displayName = "Improve provider", baseUrl = baseUrl, model = "base-model", kind = kind)
        state.updateSettings { it.copy(aiProviderProfiles = listOf(profile)) }
    }

    private fun scripted(vararg turns: Turn, acceptsImages: Boolean = true): TurnProvider = TurnProvider(turns.toList()).also { provider ->
        state.testRunOverrides = TestRunOverrides(
            agentFactory = LaneAgentFactory { chosen, _ ->
                val inner = ProviderLaneAgent(provider, chosen)
                if (acceptsImages) inner else object : LaneAgent by inner { override val acceptsImagesUpFront: Boolean get() = false }
            },
        )
    }

    private fun answer(text: String): Turn = {
        emit(LlmStreamEvent.TextDelta(text))
        emit(LlmStreamEvent.Completed)
    }

    private fun steps(): List<TestStep> = state.testLibrary.findCase(caseId)!!.case.steps

    private fun entry(step: TestStep, action: String = step.action, expected: String = step.expected, reason: String = "unchanged") =
        """{"stepId":"${step.id}","action":"$action","expected":"$expected","reason":"$reason"}"""

    private fun improvedAnswer(): String = """{"steps":[""" + listOf(
        entry(steps()[0], action = "Launch the shop app", reason = "Precise"),
        entry(steps()[1], expected = "A list of search results is shown", reason = "No ad"),
        entry(steps()[2]),
        entry(steps()[3]),
    ).joinToString(",") + "]}"

    /** Saves a finished run of the case in the app's runs folder, with an end screenshot per step and the judge's view of the failing one. */
    private fun saveRun(id: String = "run-saved", createdAt: Long = 9_000L): String {
        val store = TestRunStore { state.activeTestFolders().second }
        val folder = store.runDir(id)
        val library = state.testLibrary
        val suite = library.suite(suiteId)!!
        val results = steps().take(2).mapIndexed { index, step ->
            improveResult(
                step.id, index + 1, step.action, step.expected, if (index == 1) StepStatus.FAIL else StepStatus.PASS,
                shot = folder.writeLaneShot("lane-1", "s$index", 50 + index * 90),
                judge = improveJudgement(
                    if (index == 1) JudgeVerdict.FAIL else JudgeVerdict.PASS, "Judge says step $index", JudgeClassification.AGENT_OR_STEP_PROBLEM,
                    StepFix(expected = "A list of search results is shown").takeIf { index == 1 },
                ),
            )
        }
        val run = improveRun(id, createdAt, listOf(improveCaseResultOf(results)), suite = suite)
        check(store.save(run).isSuccess)
        return id
    }

    @Suppress("UNCHECKED_CAST")
    private fun call(tool: String, vararg args: Pair<String, Any?>): Map<String, Any?> =
        runBlocking { operations.toolGateway.executeSuspending(tool, mapOf(*args)) as Map<String, Any?> }

    @Suppress("UNCHECKED_CAST")
    private fun Map<String, Any?>.rows(key: String): List<Map<String, Any?>> = this[key] as List<Map<String, Any?>>

    // ── The AI run ───────────────────────────────────────────────────

    @Test
    fun theImprovementTakesOneModelTurnWithTheScreensOnTheFirstMessageAndNoAgentWords() {
        saveRun()
        val provider = scripted(answer(improvedAnswer()))

        val result = call(
            "improve_test_case", "caseId" to caseId, "profileId" to IMPROVE_PROFILE_ID,
            "model" to "other-model", "reasoningEffort" to "high", "note" to "No ads",
        )

        assertNull(result["error"], result.toString())
        val request = provider.requests.single()
        assertEquals(listOf("other-model", "high"), listOf(request.model, request.reasoningEffort))
        assertTrue(request.tools.isEmpty(), "the improver has no tools")
        val first = request.messages.first { it.role == LlmRole.USER }
        assertEquals(2, first.images.size, "the end screen of each step the run reached")
        val prompt = first.content.orEmpty()
        assertTrue("No ads" in prompt && "Judge says step 1" in prompt && "<untrusted_data source=\"run_evidence\">" in prompt)
        listOf(prompt, request.messages.joinToString { it.content.orEmpty() }).forEach {
            assertFalse(AGENT_CLAIM_TEXT in it || AGENT_OBSERVATION_TEXT in it)
        }
        assertEquals("run-saved", result["runId"])
        assertEquals(2, result.rows("steps").count { it["changed"] == true })
        assertEquals(4, result.rows("steps").size)
        val evidence = result["evidence"] as Map<*, *>
        assertEquals(2, evidence["runScreenshots"])
        assertEquals(1, evidence["judgeSuggestions"])
        val line = logFile.readLines().single { "improve_brief" in it }
        assertTrue("evidenceUpFront=true" in line && "images=2" in line && "run=run-saved" in line && "promptChars=" in line && "briefMs=" in line, line)
        val timing = logFile.readLines().single { "test-agent-timing" in it && "test-step-improve" in it }
        assertTrue("1 model turns" in timing && "0 tool calls" in timing, timing)
        assertEquals(steps().map { it.action }, improveSuite().cases.single().steps.map { it.action }, "nothing was applied")
    }

    @Test
    fun anAgentThatTakesNoImagesStillGetsAnAnswerFromTheTextEvidence() {
        saveRun()
        val provider = scripted(answer(improvedAnswer()), acceptsImages = false)

        val result = call("improve_test_case", "caseId" to caseId, "profileId" to IMPROVE_PROFILE_ID)

        assertNull(result["error"], result.toString())
        val first = provider.requests.single().messages.first { it.role == LlmRole.USER }
        assertTrue(first.images.isEmpty())
        assertTrue("NOT attached" in first.content.orEmpty())
        assertTrue("Judge says step 1" in first.content.orEmpty())
    }

    @Test
    fun aNamedRunIsTheEvidenceAndAnUnknownOneIsReported() {
        saveRun("run-old", 1_000L)
        saveRun("run-new", 2_000L)
        val provider = scripted(answer(improvedAnswer()), answer(improvedAnswer()))

        val named = call("improve_test_case", "caseId" to caseId, "profileId" to IMPROVE_PROFILE_ID, "runId" to "run-old")
        val latest = call("improve_test_case", "caseId" to caseId, "profileId" to IMPROVE_PROFILE_ID)
        val missing = call("improve_test_case", "caseId" to caseId, "profileId" to IMPROVE_PROFILE_ID, "runId" to "run-zzz")

        assertEquals("run-old", named["runId"])
        assertEquals("run-new", latest["runId"])
        assertTrue("run-zzz" in missing["error"].toString(), missing.toString())
        assertEquals(2, provider.requests.size, "the unknown run is refused before any model call")
    }

    @Test
    fun applyingWritesTheAcceptedStepsAndRefusesWhatItShould() {
        saveRun()
        scripted(answer(improvedAnswer()))
        val proposal = call("improve_test_case", "caseId" to caseId, "profileId" to IMPROVE_PROFILE_ID)
        val proposalId = proposal["proposalId"] as String
        val rows = proposal.rows("steps").map { mapOf("stepId" to it["stepId"], "action" to it["action"], "expected" to it["expected"]) }

        val wrongOrder = call("apply_test_case_improvement", "proposalId" to proposalId, "steps" to rows.reversed())
        assertTrue("in its order" in wrongOrder["error"].toString(), wrongOrder.toString())
        val manualOptional = call(
            "apply_test_case_improvement", "proposalId" to proposalId, "accept" to listOf(STEP_USB),
            "steps" to rows.map { if (it["stepId"] == STEP_USB) it + mapOf("optional" to true, "condition" to "at hand") else it },
        )
        assertTrue("manual step cannot be optional" in manualOptional["error"].toString(), manualOptional.toString())
        assertEquals(improveSuite().cases.single().steps, steps(), "refused applies write nothing")

        val applied = call("apply_test_case_improvement", "proposalId" to proposalId, "accept" to listOf(STEP_SEARCH), "steps" to rows)

        assertNull(applied["error"], applied.toString())
        assertEquals(listOf(STEP_SEARCH), applied["stepIds"])
        assertEquals("Open the app", steps()[0].action)
        assertEquals("A list of search results is shown", steps()[1].expected)
        val again = call("apply_test_case_improvement", "proposalId" to proposalId, "steps" to rows)
        assertTrue("expired or was already applied" in again["error"].toString())
    }

    @Test
    fun anInvalidAnswerIsAnErrorAndAStaleProposalCannotBeApplied() {
        saveRun()
        scripted(answer("""{"steps":[{"stepId":"$STEP_OPEN"}]}"""), answer(improvedAnswer()))
        val bad = call("improve_test_case", "caseId" to caseId, "profileId" to IMPROVE_PROFILE_ID)
        assertTrue("exactly one entry" in bad["error"].toString(), bad.toString())

        val proposal = call("improve_test_case", "caseId" to caseId, "profileId" to IMPROVE_PROFILE_ID)
        state.updateTestStep(STEP_OPEN) { it.copy(expected = "Edited by the person") }
        val rows = proposal.rows("steps").map { mapOf("stepId" to it["stepId"], "action" to it["action"], "expected" to it["expected"]) }
        val stale = call("apply_test_case_improvement", "proposalId" to proposal["proposalId"], "steps" to rows)
        assertTrue("changed since" in stale["error"].toString(), stale.toString())
        assertEquals("Edited by the person", steps()[0].expected)
    }

    @Test
    fun aLockedCaseIsRefusedBeforeAnyModelCall() {
        saveRun()
        val provider = scripted(answer(improvedAnswer()))
        // The Free edition locks every case past the fifth of the suite.
        repeat(6) { check(state.createTestCase(suiteId, TestCase("", "Extra $it", steps = listOf(TestStep("", "Do it", "Done")))) is StoreResult.Ok) }
        check(state.editionService.setForDev(Edition.FREE))
        val lockedCase = state.testLibrary.suite(suiteId)!!.cases.last()

        val locked = call("improve_test_case", "caseId" to lockedCase.id, "profileId" to IMPROVE_PROFILE_ID)

        assertTrue(locked["error"] != null && locked["limit"] != null, locked.toString())
        assertTrue(provider.requests.isEmpty())
    }

    // ── Approval ─────────────────────────────────────────────────────

    @Test
    fun theToolsAreInTheCatalogueAndApplyAsksInsideTheAiPanelToo() {
        val names = operations.toolGateway.tools.map { it.name }
        assertTrue("improve_test_case" in names && "apply_test_case_improvement" in names)
        assertEquals(IndagiumToolActionPolicy.CONFIRMATION_REQUIRED, operations.toolGateway.actionPolicy("apply_test_case_improvement"))
        assertTrue("improve_test_case" in PER_CALL_APPROVAL_MCP_TOOLS)
        val schema = operations.toolGateway.tools.single { it.name == "improve_test_case" }.schema
        assertEquals(listOf("caseId", "profileId"), schema.required)
    }

    @Test
    fun aRemoteProviderNeedsPerCallApprovalWithDisclosureAndALoopbackOneDoesNot() {
        val args = mapOf("caseId" to caseId, "profileId" to IMPROVE_PROFILE_ID, "model" to "big-model", "note" to "Be stable", "runId" to "run-saved")

        assertEquals(IndagiumToolActionPolicy.AUTOMATIC, operations.toolGateway.actionPolicy("improve_test_case", args))
        assertNull(runBlocking { describePerCallApproval(state, "improve_test_case", args, "Fixture client") })

        useProfile(AiProviderKind.OPENAI_COMPATIBLE, "https://provider.example/v1")
        assertEquals(IndagiumToolActionPolicy.CONFIRMATION_REQUIRED, operations.toolGateway.actionPolicy("improve_test_case", args))
        val remote = runBlocking { describePerCallApproval(state, "improve_test_case", args, "Fixture client") }
        assertNotNull(remote)
        assertTrue("screenshots" in remote.summary && "preview only" in remote.summary)
        assertTrue(remote.fields.any { it.first == "Destination" && it.second == "provider.example" })
        assertTrue(remote.fields.any { it.first == "Model" && it.second == "big-model" })
        assertTrue(remote.fields.any { it.first == "Steps" && it.second.startsWith("4 saved") })
        assertTrue(remote.fields.any { it.first == "Run evidence" && it.second == "run-saved" })
        assertTrue(remote.fields.any { it.first == "Request" && it.second == "Be stable" })

        useProfile(AiProviderKind.CODEX_ACCOUNT, "")
        val account = runBlocking { describePerCallApproval(state, "improve_test_case", args, "Fixture client") }
        assertNotNull(account)
        assertTrue(account.fields.any { it.first == "Destination" && it.second == "signed-in local CLI account" })
    }

    @Test
    fun noApprovalDialogIsOfferedForACallTheToolWouldRefuseAnyway() {
        useProfile(AiProviderKind.OPENAI_COMPATIBLE, "https://provider.example/v1")

        fun details(vararg args: Pair<String, Any?>) = runBlocking { describePerCallApproval(state, "improve_test_case", mapOf(*args), "Fixture client") }

        assertNull(details("caseId" to "case-unknown", "profileId" to IMPROVE_PROFILE_ID))
        assertNull(details("caseId" to caseId, "profileId" to "no-such-profile"))
        assertNull(details("profileId" to IMPROVE_PROFILE_ID))
        assertNotNull(details("caseId" to caseId, "profileId" to IMPROVE_PROFILE_ID))
    }
}
