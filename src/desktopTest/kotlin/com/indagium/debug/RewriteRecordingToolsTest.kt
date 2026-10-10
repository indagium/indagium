@file:Suppress("MagicNumber") // Fixture counts and timeouts, not tunable constants.

package com.indagium.debug

import com.indagium.ai.LlmStreamEvent
import com.indagium.ai.LlmToolCall
import com.indagium.model.AiProviderKind
import com.indagium.model.AiProviderProfile
import com.indagium.testing.Turn
import com.indagium.testing.TurnProvider
import com.indagium.testing.VALID_SHOP_REWRITE
import com.indagium.testing.authoring.RecordedTestStep
import com.indagium.testing.model.TestCase
import com.indagium.testing.recordedShopSession
import com.indagium.testing.run.LaneAgentFactory
import com.indagium.testing.run.ProviderLaneAgent
import com.indagium.testing.store.StoreResult
import com.indagium.ui.AppState
import com.indagium.ui.TestRunOverrides
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
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

private const val PROFILE_ID = "rewrite-profile"

/** The recording rewrite over the app state and the gateway: model overrides, the tools the AI gets, locks, approval and undo. */
class RewriteRecordingToolsTest {
    private lateinit var dir: File
    private lateinit var state: AppState
    private lateinit var operations: IndagiumToolOperations
    private var suiteId = ""
    private var caseId = ""

    @BeforeTest
    fun setUp() {
        dir = createTempDirectory("rewrite-recording").toFile()
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
        suiteId = (state.createTestSuite("Shop suite") as StoreResult.Ok).value.id
        caseId = (state.createTestCase(suiteId, TestCase("", "Search for music")) as StoreResult.Ok).value.id
        operations = IndagiumToolOperations(state)
    }

    @AfterTest
    fun tearDown() {
        state.close()
        dir.deleteRecursively()
    }

    private fun useProfile(kind: AiProviderKind, baseUrl: String) {
        val profile = AiProviderProfile(id = PROFILE_ID, displayName = "Rewrite provider", baseUrl = baseUrl, model = "base-model", kind = kind)
        state.updateSettings { it.copy(aiProviderProfiles = listOf(profile)) }
    }

    private fun scripted(turns: List<Turn>): TurnProvider = TurnProvider(turns).also { provider ->
        state.testRunOverrides = TestRunOverrides(agentFactory = LaneAgentFactory { chosen, _ -> ProviderLaneAgent(provider, chosen) })
    }

    private fun scripted(vararg turns: Turn): TurnProvider = scripted(turns.toList())

    private fun answer(text: String): Turn = {
        emit(LlmStreamEvent.TextDelta(text))
        emit(LlmStreamEvent.Completed)
    }

    private fun lookThenAnswer(text: String): List<Turn> = listOf(
        {
            emit(LlmStreamEvent.TextDelta("Let me look at the first input. {\"steps\": []}"))
            emit(LlmStreamEvent.ToolCall(LlmToolCall("call-1", "get_recorded_input", """{"index":1}""")))
            emit(LlmStreamEvent.Completed)
        },
        answer(text),
    )

    private fun record() = recordedShopSession().also { check(state.registerTestStepRecording(it, suiteId, caseId) is StoreResult.Ok) }

    @Suppress("UNCHECKED_CAST")
    private fun call(tool: String, vararg args: Pair<String, Any?>): Map<String, Any?> =
        runBlocking { operations.toolGateway.executeSuspending(tool, mapOf(*args)) as Map<String, Any?> }

    // ── The AI run ───────────────────────────────────────────────────

    @Test
    fun theRewriteRunsWithTheChosenModelAndTheRecordingToolsAndAppliesTheAnswerAfterTheLastToolCall() {
        val session = record()
        val provider = scripted(lookThenAnswer(VALID_SHOP_REWRITE))

        val result = call(
            "rewrite_test_recording",
            "sessionId" to session.id, "profileId" to PROFILE_ID, "model" to "other-model", "reasoningEffort" to "high", "note" to "Searching",
        )

        assertNull(result["error"], result.toString())
        assertEquals(true, result["rewritten"])
        assertEquals("Merged the tap on Search with the typing.", result["notes"])
        assertEquals(2, (result["steps"] as List<*>).size)
        val first = provider.requests.first()
        assertEquals("other-model", first.model)
        assertEquals("high", first.reasoningEffort)
        assertEquals(listOf("get_recorded_input", "get_recorded_screen", "get_recorded_ui"), first.tools.map { it.name })
        val prompt = first.messages.joinToString("\n") { it.content.orEmpty() }
        assertTrue("Searching" in prompt && "<untrusted_data source=\"recorded_inputs\">" in prompt)
        assertEquals(2, provider.requests.size, "one tool round, then the answer")
        assertEquals(2, session.snapshot.value.steps.size)
        assertEquals(3, session.snapshot.value.rawSteps?.size)
        assertFalse(state.isTestStepRecordingRewriting(session.id))
    }

    @Test
    fun anInvalidAnswerLeavesTheRecordingUntouchedAndReportsWhy() {
        val session = record()
        scripted(answer("""{"steps":[{"action":"A","expected":"B","sourceInputs":[1,2]}]}"""))
        val result = call("rewrite_test_recording", "sessionId" to session.id, "profileId" to PROFILE_ID)
        assertTrue("input 3" in result["error"].toString(), result.toString())
        assertNull(session.snapshot.value.rawSteps)
        assertEquals(3, session.snapshot.value.steps.size)
    }

    @Test
    fun anEffortTheProfileKindDoesNotOfferIsRefusedBeforeAnyRun() {
        val session = record()
        val provider = scripted(answer(VALID_SHOP_REWRITE))
        val result = call("rewrite_test_recording", "sessionId" to session.id, "profileId" to PROFILE_ID, "reasoningEffort" to "turbo")
        assertTrue("reasoning effort" in result["error"].toString(), result.toString())
        assertTrue(provider.requests.isEmpty())
    }

    @Test
    fun theDraftServiceAndToolPassTheModelAndEffortToTheProvider() = runBlocking<Unit> {
        val provider = scripted(
            answer("""{"steps":[{"action":"Tap sign in","expected":"Home appears"}]}"""),
            answer("""{"steps":[{"action":"Tap it","expected":"Done"}]}"""),
        )

        val draft = state.testStepDraftService.create(suiteId, caseId, PROFILE_ID, "Add sign-in", model = "draft-model", effort = "low")
        assertIs<StoreResult.Ok<*>>(draft)
        assertEquals(listOf("draft-model", "low"), listOf(provider.requests[0].model, provider.requests[0].reasoningEffort))

        val viaTool = call(
            "draft_test_steps",
            "suiteId" to suiteId, "caseId" to caseId, "profileId" to PROFILE_ID, "instruction" to "Add it", "model" to "tool-model",
        )
        assertNull(viaTool["error"], viaTool.toString())
        assertEquals("tool-model", provider.requests[1].model)
        assertEquals("", provider.requests[1].reasoningEffort.orEmpty(), "the profile's own effort stays when none is given")
    }

    // ── Locks ────────────────────────────────────────────────────────

    @Test
    fun applyEditUndoAndDiscardAreRefusedWhileARewriteRunsAndWorkAgainAfterwards() = runBlocking<Unit> {
        val session = record()
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        scripted({
            entered.complete(Unit)
            release.await()
            emit(LlmStreamEvent.TextDelta(VALID_SHOP_REWRITE))
            emit(LlmStreamEvent.Completed)
        })
        val running = async { state.rewriteTestStepRecording(session.id, PROFILE_ID) }
        withTimeout(10_000) { entered.await() }

        assertTrue(state.isTestStepRecordingRewriting(session.id))
        val rows = session.snapshot.value.steps
        assertTrue("being rewritten" in assertIs<StoreResult.Invalid>(state.applyTestStepRecording(session.id, rows.map { it.action to "ok" })).reason)
        assertTrue("being rewritten" in assertIs<StoreResult.Invalid>(state.updateReviewedTestRecording(session.id, rows.map { it.action to "ok" })).reason)
        assertTrue("being rewritten" in assertIs<StoreResult.Invalid>(state.restoreTestStepRecordingRaw(session.id)).reason)
        assertTrue("already being rewritten" in assertIs<StoreResult.Invalid>(state.rewriteTestStepRecording(session.id, PROFILE_ID)).reason)
        assertFalse(state.clearTestStepRecording(session.id), "a recording that is being rewritten cannot be discarded")

        release.complete(Unit)
        assertIs<StoreResult.Ok<*>>(withTimeout(10_000) { running.await() })
        assertFalse(state.isTestStepRecordingRewriting(session.id))
        assertIs<StoreResult.Ok<*>>(state.restoreTestStepRecordingRaw(session.id))
        assertEquals(3, session.snapshot.value.steps.size)
        assertTrue("no AI rewrite" in assertIs<StoreResult.Invalid>(state.restoreTestStepRecordingRaw(session.id)).reason)
        assertTrue(state.clearTestStepRecording(session.id))
    }

    @Test
    fun moveAndInsertAreRefusedWhileARewriteRunsOrAnApplyHoldsTheRecording() = runBlocking<Unit> {
        val session = record()
        val rows = session.snapshot.value.steps
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        scripted({
            entered.complete(Unit)
            release.await()
            emit(LlmStreamEvent.TextDelta(VALID_SHOP_REWRITE))
            emit(LlmStreamEvent.Completed)
        })
        val running = async { state.rewriteTestStepRecording(session.id, PROFILE_ID) }
        withTimeout(10_000) { entered.await() }

        val newRow = RecordedTestStep(action = "Added")
        assertTrue("being rewritten" in assertIs<StoreResult.Invalid>(state.moveTestRecordingRow(session.id, rows[0].id, 2)).reason)
        assertTrue("being rewritten" in assertIs<StoreResult.Invalid>(state.insertTestRecordingRow(session.id, 0, newRow)).reason)
        release.complete(Unit)
        assertIs<StoreResult.Ok<*>>(withTimeout(10_000) { running.await() })

        assertIs<StoreResult.Ok<*>>(state.restoreTestStepRecordingRaw(session.id))
        assertNotNull(session.freezeReviewedSnapshotForApply())
        assertIs<StoreResult.Invalid>(state.moveTestRecordingRow(session.id, rows[0].id, 2), "the session itself refuses while reserved for apply")
        assertIs<StoreResult.Invalid>(state.insertTestRecordingRow(session.id, 0, newRow))
        session.releaseApplyReservation()
        assertEquals(rows.map { it.id }, session.snapshot.value.steps.map { it.id }, "nothing moved or added while refused")
        assertIs<StoreResult.Ok<*>>(state.moveTestRecordingRow(session.id, rows[0].id, 2))
        assertIs<StoreResult.Invalid>(state.moveTestRecordingRow("unknown", rows[0].id, 0))
        assertIs<StoreResult.Invalid>(state.moveTestRecordingRow(session.id, "unknown", 0))
        assertEquals(listOf(rows[1].id, rows[2].id, rows[0].id), session.snapshot.value.steps.map { it.id })
    }

    @Suppress("UNCHECKED_CAST")
    private fun stepRows(response: Map<String, Any?>): List<Map<String, Any?>> = response["steps"] as List<Map<String, Any?>>

    private fun rowEdits(rows: List<Map<String, Any?>>) = rows.map { mapOf("id" to it["id"], "action" to it["action"], "expected" to it["expected"]) }

    @Test
    fun updateTestRecordingAcceptsAnyPermutationOfTheRowIdsAsTheNewOrderAndKeepsEvidence() {
        val session = record()
        val rows = stepRows(call("get_test_recording", "sessionId" to session.id))
        val ids = rows.map { it["id"] as String }
        val order = listOf(ids[2], ids[0], ids[1])
        val byId = rows.associateBy { it["id"] }

        val reordered = call("update_test_recording", "sessionId" to session.id, "steps" to order.map { rowEdits(listOf(byId.getValue(it))).single() })

        assertNull(reordered["error"], reordered.toString())
        assertEquals(order, stepRows(reordered).map { it["id"] })
        assertEquals(order, session.snapshot.value.steps.map { it.id })
        assertEquals(order, stepRows(call("get_test_recording", "sessionId" to session.id)).map { it["id"] })
        assertEquals(byId.getValue(ids[0])["tappedElement"], stepRows(reordered)[1]["tappedElement"], "the row kept its evidence")
        assertEquals(byId.getValue(ids[2])["afterActivity"], stepRows(reordered)[0]["afterActivity"])

        // The new order is also what an edit without ids and what apply are matched against.
        val edited = call(
            "update_test_recording",
            "sessionId" to session.id,
            "steps" to order.map { mapOf("action" to "edit $it", "expected" to "ok") },
        )
        assertNull(edited["error"], edited.toString())
        assertEquals(order, stepRows(edited).map { it["id"] })
        assertEquals(order.map { "edit $it" }, stepRows(edited).map { it["action"] })
        val applyOld = call("apply_test_recording", "sessionId" to session.id, "steps" to ids.map { mapOf("id" to it, "action" to "a", "expected" to "e") })
        assertTrue("error" in applyOld, "apply still wants the current order: $applyOld")
    }

    @Test
    fun updateTestRecordingRefusesAnOrderThatIsNotAPermutationOfEveryCurrentRowId() {
        val session = record()
        val ids = session.snapshot.value.steps.map { it.id }

        fun edits(vararg rowIds: String) = rowIds.map { mapOf("id" to it, "action" to "a", "expected" to "e") }

        listOf(
            edits(ids[0], ids[0], ids[1]),
            edits(ids[0], ids[1], "other"),
            edits(ids[0], ids[1]),
            edits(ids[0], ids[1], ids[2], ids[0]),
        ).forEach { steps ->
            val result = call("update_test_recording", "sessionId" to session.id, "steps" to steps)
            assertTrue("error" in result, result.toString())
            assertEquals(ids, session.snapshot.value.steps.map { it.id })
        }
        val partialIds = call(
            "update_test_recording",
            "sessionId" to session.id,
            "steps" to listOf(
                mapOf("id" to ids[0], "action" to "a", "expected" to "e"),
                mapOf("action" to "b", "expected" to "e"),
                mapOf("action" to "c", "expected" to "e"),
            ),
        )
        assertTrue("every row" in partialIds["error"].toString(), partialIds.toString())
    }

    @Test
    fun insertTestRecordingRowAddsAnAuthorStepTheCallerCanEditApplyAndDeleteWithoutApproval() {
        val session = record()
        val ids = session.snapshot.value.steps.map { it.id }
        assertFalse("insert_test_recording_row" in PER_CALL_APPROVAL_MCP_TOOLS)
        assertEquals(
            IndagiumToolActionPolicy.AUTOMATIC,
            operations.toolGateway.actionPolicy("insert_test_recording_row", mapOf("sessionId" to session.id)),
            "the same policy as update_test_recording",
        )
        assertEquals(
            operations.toolGateway.actionPolicy("update_test_recording", mapOf("sessionId" to session.id)),
            operations.toolGateway.actionPolicy("insert_test_recording_row", mapOf("sessionId" to session.id)),
        )

        val inserted = call("insert_test_recording_row", "sessionId" to session.id, "index" to 1, "action" to "Sign in", "expected" to "Home is shown")
        assertNull(inserted["error"], inserted.toString())
        val rows = stepRows(inserted)
        assertEquals(listOf(ids[0], inserted["rowId"], ids[1], ids[2]), rows.map { it["id"] })
        assertEquals(true, rows[1]["authorAdded"])
        assertNull(rows[0]["authorAdded"])
        assertEquals(1, inserted["index"])
        assertEquals(false, inserted["rewritten"])
        assertEquals(emptyList<String>(), inserted["excludedSourceInputIds"])

        assertTrue("index" in call("insert_test_recording_row", "sessionId" to session.id, "index" to 9, "action" to "x")["error"].toString().lowercase())
        assertTrue("action is required" in call("insert_test_recording_row", "sessionId" to session.id, "index" to 0, "action" to " ")["error"].toString())
        assertTrue("no longer active" in call("insert_test_recording_row", "sessionId" to "unknown", "index" to 0, "action" to "x")["error"].toString())
        val tooLong = call("insert_test_recording_row", "sessionId" to session.id, "index" to 0, "action" to "x".repeat(2_001))
        assertTrue("limited to" in tooLong["error"].toString())
        assertEquals(4, session.snapshot.value.steps.size)

        val blankExpected = call("insert_test_recording_row", "sessionId" to session.id, "index" to 4, "action" to "Last")
        assertNull(blankExpected["error"], blankExpected.toString())
        assertEquals("", stepRows(blankExpected).last()["expected"], "expected may be left blank for now")
        val removed = call("remove_test_recording_steps", "sessionId" to session.id, "rowIds" to listOf(blankExpected["rowId"]))
        assertNull(removed["error"], removed.toString())
        assertEquals(emptyList<String>(), removed["excludedSourceInputIds"], "an inserted row is deleted, not excluded")

        val applied = call(
            "apply_test_recording",
            "sessionId" to session.id,
            "steps" to rowEdits(stepRows(removed)).map { it + ("expected" to (it["expected"] as String).ifBlank { "ok" }) },
        )
        assertNull(applied["error"], applied.toString())
        val steps = state.testLibrary.suite(suiteId)!!.cases.single { it.id == caseId }.steps
        assertEquals(listOf("Sign in"), steps.filter { it.action == "Sign in" }.map { it.action })
        assertEquals(1, steps.indexOfFirst { it.action == "Sign in" })
        assertEquals("Home is shown", steps[1].expected)
    }

    @Test
    fun cancellingARewriteReleasesTheLockAndChangesNothing() = runBlocking<Unit> {
        val session = record()
        val entered = CompletableDeferred<Unit>()
        scripted({ entered.complete(Unit); kotlinx.coroutines.awaitCancellation() })
        val running = async(start = CoroutineStart.DEFAULT) { state.rewriteTestStepRecording(session.id, PROFILE_ID) }
        withTimeout(10_000) { entered.await() }
        running.cancelAndJoin()

        assertFalse(state.isTestStepRecordingRewriting(session.id))
        assertNull(session.snapshot.value.rawSteps)
        assertEquals(3, session.snapshot.value.steps.size)
    }

    @Test
    fun anApplyThatAlreadyHoldsTheRecordingRefusesTheRewrite() = runBlocking<Unit> {
        val session = record()
        scripted(answer(VALID_SHOP_REWRITE))
        assertNotNull(session.freezeReviewedSnapshotForApply())
        val result = state.rewriteTestStepRecording(session.id, PROFILE_ID)
        assertTrue("being applied" in assertIs<StoreResult.Invalid>(result).reason)
        assertFalse(state.isTestStepRecordingRewriting(session.id))
    }

    // ── The two tools ────────────────────────────────────────────────

    @Test
    fun restoreTestRecordingRawPutsTheRecordedRowsBackAndIsNotApprovalGated() {
        val session = record()
        val recorded = session.snapshot.value.steps
        scripted(answer(VALID_SHOP_REWRITE))
        call("rewrite_test_recording", "sessionId" to session.id, "profileId" to PROFILE_ID)

        val restored = call("restore_test_recording_raw", "sessionId" to session.id)

        assertNull(restored["error"], restored.toString())
        assertEquals(false, restored["rewritten"])
        assertEquals(recorded.map { it.id }, (restored["steps"] as List<*>).map { (it as Map<*, *>)["id"] })
        assertTrue("no AI rewrite" in call("restore_test_recording_raw", "sessionId" to session.id)["error"].toString())
        assertTrue("no longer active" in call("restore_test_recording_raw", "sessionId" to "unknown")["error"].toString())
        assertTrue("no longer active" in call("rewrite_test_recording", "sessionId" to "unknown", "profileId" to PROFILE_ID)["error"].toString())
        assertFalse("restore_test_recording_raw" in PER_CALL_APPROVAL_MCP_TOOLS)
        assertEquals(IndagiumToolActionPolicy.AUTOMATIC, operations.toolGateway.actionPolicy("restore_test_recording_raw"))
    }

    @Test
    fun mcpRemovalExcludesSourcesOnRewriteAndRestoreReturnsTheOriginalRows() {
        val session = record()
        val recorded = session.snapshot.value.steps

        val removed = call("remove_test_recording_steps", "sessionId" to session.id, "rowIds" to listOf(recorded[1].id))
        assertNull(removed["error"], removed.toString())
        assertEquals(false, removed["rewritten"])
        assertEquals(listOf(recorded[0].id, recorded[2].id), (removed["steps"] as List<*>).map { (it as Map<*, *>)["id"] })
        assertEquals(listOf(recorded[1].id), removed["excludedSourceInputIds"])

        val badRow = call("remove_test_recording_steps", "sessionId" to session.id, "rowIds" to listOf("missing-row"))
        assertTrue("current stopped preview" in badRow["error"].toString(), badRow.toString())
        val badSession = call("restore_test_recording_inputs", "sessionId" to "missing-session")
        assertTrue("no longer active" in badSession["error"].toString(), badSession.toString())

        val response = """{"steps":[
            {"action":"Search","expected":"Search is ready","sourceInputs":[1]},
            {"action":"Submit","expected":"Results appear","sourceInputs":[2]}
        ]}"""
        scripted(answer(response))
        val rewrite = call("rewrite_test_recording", "sessionId" to session.id, "profileId" to PROFILE_ID)
        assertNull(rewrite["error"], rewrite.toString())
        assertEquals(true, rewrite["rewritten"])
        assertEquals(listOf(recorded[0].id, recorded[2].id), (rewrite["steps"] as List<*>).flatMap { row ->
            ((row as Map<*, *>)["sourceInputIds"] as? List<*>)?.filterIsInstance<String>().orEmpty()
        })
        assertEquals(listOf(recorded[1].id), rewrite["excludedSourceInputIds"])

        val restored = call("restore_test_recording_inputs", "sessionId" to session.id)
        assertNull(restored["error"], restored.toString())
        assertEquals(false, restored["rewritten"])
        assertEquals(emptyList<String>(), restored["excludedSourceInputIds"])
        assertEquals(recorded.map { it.id }, (restored["steps"] as List<*>).map { (it as Map<*, *>)["id"] })
        assertEquals(recorded, session.snapshot.value.rawSteps)
    }

    @Test
    fun theRewriteToolReadsEditsApplyAndGetShowTheRewrittenRows() {
        val session = record()
        scripted(answer(VALID_SHOP_REWRITE))
        val rewritten = call("rewrite_test_recording", "sessionId" to session.id, "profileId" to PROFILE_ID)

        @Suppress("UNCHECKED_CAST")
        val rows = rewritten["steps"] as List<Map<String, Any?>>
        assertEquals(listOf(2, 1), rows.map { (it["sourceInputIds"] as List<*>).size })

        val edited = call(
            "update_test_recording",
            "sessionId" to session.id,
            "steps" to rows.map { mapOf("id" to it["id"], "action" to "${it["action"]} (edited)", "expected" to it["expected"]) },
        )
        assertNull(edited["error"], edited.toString())
        assertEquals(true, edited["rewritten"])

        val applied = call(
            "apply_test_recording",
            "sessionId" to session.id,
            "steps" to rows.map { mapOf("id" to it["id"], "action" to it["action"], "expected" to it["expected"]) },
        )
        assertNull(applied["error"], applied.toString())
        val hint = state.testLibrary.suite(suiteId)!!.cases.single { it.id == caseId }.steps.first().examples.single()
        assertEquals("Recorded input (hint; prefer what is on screen)", hint.caption)
    }

    @Test
    fun theScopeArgumentKeepsTheAcceptedStepsOrRewritesEverythingAgain() {
        val session = record()
        val flagged = """{"steps":[{"action":"Search for 'lofi'","expected":"The search field shows 'lofi'","sourceInputs":[1,2]},""" +
            """{"action":"Press Enter","expected":"","sourceInputs":[3],"reviewReason":"Confirm the result."}]}"""
        val resolved = """{"steps":[{"action":"Search for 'lofi'","expected":"The search field shows 'lofi'","sourceInputs":[1,2]},""" +
            """{"action":"Submit the search","expected":"Results for 'lofi' are listed","sourceInputs":[3]}]}"""
        val provider = scripted(answer(flagged), answer(resolved), answer(VALID_SHOP_REWRITE))
        val descriptor = operations.toolGateway.tools.single { it.name == "rewrite_test_recording" }
        assertTrue("scope" in descriptor.schema.toString() && "needs_review" in descriptor.schema.toString(), descriptor.schema.toString())

        assertNull(call("rewrite_test_recording", "sessionId" to session.id, "profileId" to PROFILE_ID)["error"])
        val second = call("rewrite_test_recording", "sessionId" to session.id, "profileId" to PROFILE_ID)
        assertNull(second["error"], second.toString())
        val kept = provider.requests[1].messages.joinToString("\n") { it.content.orEmpty() }
        assertTrue("1. [TAP]" in kept && "2. [TEXT]" in kept && "3. [KEY] Press Enter" in kept, kept)
        assertFalse("[LOCKED STEP" in kept, "the adjacent autogenerated helper can be reconsidered with the unresolved row:\n$kept")
        assertEquals(listOf("Search for 'lofi'", "Submit the search"), (second["steps"] as List<*>).map { (it as Map<*, *>)["action"] })

        val nothing = call("rewrite_test_recording", "sessionId" to session.id, "profileId" to PROFILE_ID, "scope" to "needs_review")
        assertTrue("no step needs review" in nothing["error"].toString(), nothing.toString())
        val unknown = call("rewrite_test_recording", "sessionId" to session.id, "profileId" to PROFILE_ID, "scope" to "some")
        assertTrue("needs_review or all" in unknown["error"].toString(), unknown.toString())
        assertEquals(2, provider.requests.size, "neither refusal reached the model")

        val all = call("rewrite_test_recording", "sessionId" to session.id, "profileId" to PROFILE_ID, "scope" to "all")
        assertNull(all["error"], all.toString())
        val everything = provider.requests[2].messages.joinToString("\n") { it.content.orEmpty() }
        assertTrue("1. [TAP]" in everything && "covers input(s)" !in everything, everything)
    }

    // ── Approval ─────────────────────────────────────────────────────

    @Test
    fun aRemoteProviderNeedsPerCallApprovalAndALoopbackOneDoesNot() {
        val session = record()
        val args = mapOf("sessionId" to session.id, "profileId" to PROFILE_ID, "model" to "big-model")
        assertTrue("rewrite_test_recording" in PER_CALL_APPROVAL_MCP_TOOLS)

        assertEquals(IndagiumToolActionPolicy.AUTOMATIC, operations.toolGateway.actionPolicy("rewrite_test_recording", args))
        assertNull(runBlocking { describePerCallApproval(state, "rewrite_test_recording", args, "Fixture client") })

        useProfile(AiProviderKind.OPENAI_COMPATIBLE, "https://provider.example/v1")
        assertEquals(IndagiumToolActionPolicy.CONFIRMATION_REQUIRED, operations.toolGateway.actionPolicy("rewrite_test_recording", args))
        val remote = runBlocking { describePerCallApproval(state, "rewrite_test_recording", args, "Fixture client") }
        assertNotNull(remote)
        assertTrue(remote.title.contains("recording", ignoreCase = true))
        assertTrue(remote.summary.contains("screenshots from the device") && remote.summary.contains("passwords are hidden"))
        assertTrue(remote.fields.any { it.first == "Destination" && it.second == "provider.example" })
        assertTrue(remote.fields.any { it.first == "Model" && it.second == "big-model" })
        assertTrue(remote.fields.any { it.first == "Recorded inputs" && it.second.startsWith("3 input") })

        useProfile(AiProviderKind.CODEX_ACCOUNT, "")
        assertEquals(IndagiumToolActionPolicy.CONFIRMATION_REQUIRED, operations.toolGateway.actionPolicy("rewrite_test_recording", args))
        val account = runBlocking { describePerCallApproval(state, "rewrite_test_recording", args, "Fixture client") }
        assertNotNull(account)
        assertTrue(account.fields.any { it.first == "Destination" && it.second == "signed-in local CLI account" })
    }

    @Test
    fun noApprovalDialogIsOfferedForACallTheToolWouldRefuseAnyway() {
        val session = record()
        useProfile(AiProviderKind.OPENAI_COMPATIBLE, "https://provider.example/v1")

        fun details(vararg args: Pair<String, Any?>) = runBlocking { describePerCallApproval(state, "rewrite_test_recording", mapOf(*args), "Fixture client") }

        assertNull(details("sessionId" to "unknown", "profileId" to PROFILE_ID))
        assertNull(details("sessionId" to session.id, "profileId" to "no-such-profile"))
        assertNull(details("profileId" to PROFILE_ID))
        assertNotNull(details("sessionId" to session.id, "profileId" to PROFILE_ID))
    }
}
