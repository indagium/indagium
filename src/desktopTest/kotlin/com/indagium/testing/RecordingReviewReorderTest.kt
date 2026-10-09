@file:Suppress("MagicNumber") // Fixture counts, positions and timestamps, not tunable constants.

package com.indagium.testing

import com.indagium.capture.mirror.MirrorControlCommand
import com.indagium.capture.mirror.MirrorFrame
import com.indagium.testing.authoring.RecordedTestStep
import com.indagium.testing.authoring.RecordingRewrite
import com.indagium.testing.authoring.RecordingRewriteService
import com.indagium.testing.authoring.RecordingRewriteTools
import com.indagium.testing.authoring.RewriteGeneration
import com.indagium.testing.authoring.TestStepRecordingSession
import com.indagium.testing.authoring.parseRewriteResponse
import com.indagium.testing.authoring.rewriteInputsOf
import com.indagium.testing.store.StoreResult
import kotlinx.coroutines.runBlocking
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

private const val AUTHOR_ACTION = "Sign in with the test account"
private const val AUTHOR_EXPECTED = "The home screen is shown"

/**
 * Reordering and adding steps in the recording review: the guards, that evidence travels with a moved row, how the AI rewrite
 * numbers moved rows and keeps an author-added row, and what Undo rewrite / Restore removed inputs put back.
 */
class RecordingReviewReorderTest {
    private val sessions = mutableListOf<TestStepRecordingSession>()

    @AfterTest
    fun closeSessions() = sessions.forEach(TestStepRecordingSession::close)

    private fun frame() = MirrorFrame(16, 12, IntArray(16 * 12) { 0xff44aa77.toInt() })

    /** A stopped recording of plain text inputs "first", "second", ... */
    private fun texts(count: Int): TestStepRecordingSession = runBlocking {
        val session = TestStepRecordingSession("fixture-device").also { sessions += it }
        for (number in 1..count) {
            session.accept(MirrorControlCommand.Text(NUMBER_NAMES.getOrElse(number - 1) { "input $number" }), nowMs = number * 1_000L)
        }
        session.stopAndDrain()
        session
    }

    private fun shop() = recordedShopSession().also { sessions += it }

    private fun TestStepRecordingSession.ids() = snapshot.value.steps.map { it.id }

    private fun author(action: String = AUTHOR_ACTION, expected: String = AUTHOR_EXPECTED) = RecordedTestStep(action = action, expected = expected)

    private fun service(generate: suspend (RewriteGeneration) -> String) =
        RecordingRewriteService(library = { shopLibrary() }, preflight = { _, _ -> StoreResult.Ok(Unit) }, generate = generate, timeoutMs = 5_000L)

    private fun rewrite(session: TestStepRecordingSession, generate: suspend (RewriteGeneration) -> String): StoreResult<RecordingRewrite> =
        runBlocking { service(generate).rewrite(session, SHOP_SUITE_ID, SHOP_CASE_ID, "profile", null, null, "") }

    // ── Move ─────────────────────────────────────────────────────────

    @Test
    fun movingARowChangesOnlyItsPlaceAndKeepsEveryRowObjectAndEvidence() {
        val session = shop()
        val before = session.snapshot.value.steps

        assertTrue(session.moveReviewedRow(before[2].id, 0))

        val after = session.snapshot.value.steps
        assertEquals(listOf(before[2].id, before[0].id, before[1].id), after.map { it.id })
        assertSame(before[2], after[0], "the same row, so its screens, element and gesture travel with it")
        assertEquals(before[0], after[1])
        assertNotNull(after[1].tappedElement)
        assertNull(session.snapshot.value.rawSteps, "a move is not history")
        assertTrue(session.snapshot.value.excludedSourceInputIds.isEmpty())
    }

    @Test
    fun aMoveClampsItsTargetAndAMoveOntoItsOwnPlaceSucceedsWithoutChange() {
        val session = texts(3)
        val ids = session.ids()

        assertTrue(session.moveReviewedRow(ids[0], 99))
        assertEquals(listOf(ids[1], ids[2], ids[0]), session.ids())
        assertTrue(session.moveReviewedRow(ids[0], 2))
        assertEquals(listOf(ids[1], ids[2], ids[0]), session.ids())
        assertTrue(session.moveReviewedRow(ids[0], -5))
        assertEquals(listOf(ids[0], ids[1], ids[2]), session.ids())
        assertFalse(session.moveReviewedRow("missing", 0))
    }

    @Test
    fun movesAndInsertsAreRefusedWhileRecordingWhileSnapshotsArePendingWhileRewritingAndWhileApplying() {
        val started = CountDownLatch(1)
        val release = CountDownLatch(1)
        val slow = TestStepRecordingSession(
            "fixture-device",
            snapshotEncoder = { started.countDown(); release.await(2, TimeUnit.SECONDS); byteArrayOf(1, 2, 3) },
        ).also { sessions += it }
        slow.accept(MirrorControlCommand.Text("first"), frame())
        slow.accept(MirrorControlCommand.Text("second"), frame())
        val ids = slow.ids()
        assertFalse(slow.moveReviewedRow(ids[1], 0), "still recording")
        assertFalse(slow.insertReviewedRow(0, author()), "still recording")
        assertTrue(started.await(2, TimeUnit.SECONDS))
        slow.stop()
        assertTrue(slow.snapshot.value.pendingSnapshots > 0)
        assertFalse(slow.moveReviewedRow(ids[1], 0), "images pending")
        assertFalse(slow.insertReviewedRow(0, author()), "images pending")
        release.countDown()
        runBlocking { slow.stopAndDrain() }
        assertEquals(ids, slow.ids(), "nothing moved while refused")

        assertTrue(slow.beginRewriteUsageAttempt())
        assertFalse(slow.moveReviewedRow(ids[1], 0), "a rewrite is running")
        assertFalse(slow.insertReviewedRow(0, author()), "a rewrite is running")
        slow.finishRewriteUsageAttempt(completed = false)
        assertNotNull(slow.freezeReviewedSnapshotForApply())
        assertFalse(slow.moveReviewedRow(ids[1], 0), "reserved for apply")
        assertFalse(slow.insertReviewedRow(0, author()), "reserved for apply")
        slow.releaseApplyReservation()
        assertTrue(slow.moveReviewedRow(ids[1], 0))
        assertTrue(slow.insertReviewedRow(0, author()))
    }

    // ── Insert ───────────────────────────────────────────────────────

    @Test
    fun anInsertedRowIsAnAuthorRowWithNoRecordedFactsAndGoesWhereAsked() {
        val session = texts(2)
        val dirty = RecordedTestStep(
            action = "Added",
            expected = "Shown",
            kind = com.indagium.testing.authoring.RecordedInputKind.TAP,
            sourceInputIds = listOf("x"),
            screenshotJpeg = byteArrayOf(1),
            inputAtMs = 7,
        )

        assertTrue(session.insertReviewedRow(1, dirty))
        assertTrue(session.insertReviewedRow(0, author("Start")))
        assertTrue(session.insertReviewedRow(4, author("End")))

        val rows = session.snapshot.value.steps
        assertEquals(listOf("Start", "first", "Added", "second", "End"), rows.map { it.action.removePrefix("Enter text: ") })
        val added = rows[2]
        assertEquals(dirty.id, added.id)
        assertTrue(added.authorAdded)
        assertEquals("Shown", added.expected)
        assertNull(added.kind)
        assertTrue(added.sourceInputIds.isEmpty() && added.screenshotJpeg == null && added.inputAtMs == 0L)
        assertTrue(rows.filterNot { it.authorAdded }.none { it.id == dirty.id })
    }

    @Test
    fun anInsertIsRefusedForABadPositionADuplicateIdAnInvalidConditionOrAFullList() {
        val session = texts(2)
        val ids = session.ids()
        assertFalse(session.insertReviewedRow(-1, author()))
        assertFalse(session.insertReviewedRow(3, author()))
        assertFalse(session.insertReviewedRow(0, RecordedTestStep(action = "dup", id = ids[0])))
        assertFalse(session.insertReviewedRow(0, RecordedTestStep(action = "optional without condition", optional = true)))
        assertFalse(session.insertReviewedRow(0, RecordedTestStep(action = "condition without optional", condition = "when")))
        assertTrue(session.insertReviewedRow(0, RecordedTestStep(action = "a blank action is fine to start with")))
        assertTrue(session.insertReviewedRow(0, RecordedTestStep(action = "optional", optional = true, condition = "an ad shows")))
        assertEquals(4, session.snapshot.value.steps.size)

        val full = texts(100)
        assertFalse(full.insertReviewedRow(0, author()), "at the step limit")
    }

    // ── Remove ───────────────────────────────────────────────────────

    @Test
    fun removingAnAuthorRowDeletesItAndNeverCountsAsAnExcludedInput() {
        val session = texts(3)
        val ids = session.ids()
        val added = author()
        assertTrue(session.insertReviewedRow(1, added))
        assertTrue(session.removeReviewedRows(setOf(ids[2])))
        assertTrue(session.removeReviewedRows(setOf(added.id)))

        assertEquals(listOf(ids[0], ids[1]), session.ids())
        assertEquals(setOf(ids[2]), session.snapshot.value.excludedSourceInputIds)
        assertTrue(session.restoreExcludedInputs())
        assertEquals(ids, session.ids(), "the author row stays deleted")
        assertTrue(session.snapshot.value.rawSteps.orEmpty().none { it.id == added.id })
    }

    // ── Restore removed inputs, in the reviewed order ────────────────

    @Test
    fun restoreRemovedInputsPutsRowsBackAfterTheRowTheyFollowedInTheReviewedOrder() {
        val session = texts(4)
        val ids = session.ids()
        assertTrue(session.removeReviewedRows(setOf(ids[1])))
        assertTrue(session.moveReviewedRow(ids[3], 0))
        val added = author()
        assertTrue(session.insertReviewedRow(3, added))
        assertEquals(listOf(ids[3], ids[0], ids[2], added.id), session.ids())

        assertTrue(session.restoreExcludedInputs())

        assertEquals(listOf(ids[3], ids[0], ids[1], ids[2], added.id), session.ids())
        assertTrue(session.snapshot.value.excludedSourceInputIds.isEmpty())
    }

    @Test
    fun movedAndEditedRowsKeepTheirEditsAcrossRestoreAndTheRewriteRowsFollowTheReviewOrder() {
        val session = texts(3)
        val ids = session.ids()
        assertTrue(session.removeReviewedRows(setOf(ids[0])))
        assertTrue(session.moveReviewedRow(ids[2], 0))
        assertTrue(session.replaceSteps(session.snapshot.value.steps.map { if (it.id == ids[2]) it.copy(action = "Edited third") else it }))

        assertEquals(listOf(ids[2], ids[1]), session.rewriteRows().map { it.id })
        assertEquals("Edited third", session.rewriteRows().first().action)
        assertTrue(session.restoreExcludedInputs())
        assertEquals(listOf(ids[0], ids[2], ids[1]), session.ids())
        assertEquals("Edited third", session.snapshot.value.steps[1].action)
    }

    // ── The rewrite numbers inputs in review order ───────────────────

    @Test
    fun theRewriteNumbersMovedRowsInReviewOrderKeepsTheirEvidenceAndUndoRestoresThatOrder() {
        val session = shop()
        val recorded = session.snapshot.value.steps
        assertTrue(session.moveReviewedRow(recorded[2].id, 0))
        val prompts = mutableListOf<String>()

        val result = rewrite(session) {
            prompts += it.prompt
            """{"steps":[
                {"action":"Submit first","expected":"Something is submitted","sourceInputs":[1]},
                {"action":"Search for 'lofi'","expected":"The search field shows 'lofi'","sourceInputs":[2,3]}
            ]}"""
        }

        assertIs<StoreResult.Ok<RecordingRewrite>>(result)
        val prompt = prompts.single()
        val enterLine = prompt.lines().first { it.startsWith("1. ") }
        assertTrue("[KEY] Press Enter" in enterLine && "after activity: .ResultsActivity" in enterLine, "its evidence moved with it: $enterLine")
        assertTrue(prompt.lines().first { it.startsWith("2. ") }.contains("[TAP]"), prompt)
        assertTrue(prompt.lines().first { it.startsWith("3. ") }.contains("[TEXT]"), prompt)
        val steps = session.snapshot.value.steps
        assertEquals(listOf(listOf(recorded[2].id), listOf(recorded[0].id, recorded[1].id)), steps.map { it.sourceInputIds })

        assertTrue(session.restoreRaw())
        assertEquals(listOf(recorded[2].id, recorded[0].id, recorded[1].id), session.ids(), "raw rows come back in the reviewed order")
        assertSame(recorded[2], session.snapshot.value.steps[0])
    }

    @Test
    fun movingRewrittenRowsThenUndoingRestoresTheRawRowsInThatOrderAndARerunNumbersThemSo() {
        val session = texts(3)
        val raw = session.snapshot.value.steps
        val merged = raw[0].copy(id = "merged", sourceInputIds = listOf(raw[0].id, raw[1].id))
        val last = raw[2].copy(id = "last", sourceInputIds = listOf(raw[2].id))
        assertTrue(session.applyRewrite(raw.map { it.id }, listOf(merged, last), "notes"))

        assertTrue(session.moveReviewedRow("last", 0))
        assertEquals(listOf(raw[2].id, raw[0].id, raw[1].id), session.rewriteRows().map { it.id })
        assertEquals(listOf("last", "merged"), session.ids())
        assertTrue(session.restoreRaw())
        assertEquals(listOf(raw[2].id, raw[0].id, raw[1].id), session.ids())
        assertFalse(session.snapshot.value.rewriteApplied)
        assertEquals(raw, session.snapshot.value.rawSteps, "the stored history is untouched")
    }

    // ── An author-added row survives a rewrite ───────────────────────

    private val authoredRewrite = """{"steps":[
        {"action":"Tap Search","expected":"The search field is focused","sourceInputs":[1]},
        {"action":"$AUTHOR_ACTION","expected":"$AUTHOR_EXPECTED","sourceInputs":[2]},
        {"action":"Search for 'lofi'","expected":"Results for 'lofi' are listed","sourceInputs":[3,4]}
    ]}"""

    @Test
    fun anAuthorRowReachesTheAiAsAFixedInputAndComesBackUnchangedInPlace() {
        val session = shop()
        val recorded = session.snapshot.value.steps
        val added = author()
        assertTrue(session.insertReviewedRow(1, added))
        val authorRow = session.snapshot.value.steps[1]
        val prompts = mutableListOf<String>()
        val tools = mutableListOf<String>()

        val result = rewrite(session) { prompts += it.prompt; tools += it.systemPrompt; authoredRewrite }

        assertIs<StoreResult.Ok<RecordingRewrite>>(result)
        val prompt = prompts.single()
        assertTrue(
            "2. [AUTHOR STEP, keep as is] action: \"$AUTHOR_ACTION\" | expected: \"$AUTHOR_EXPECTED\"" in prompt,
            prompt,
        )
        assertTrue("exactly" in prompt.lowercase() && "[AUTHOR STEP, keep as is]" in tools.single(), "the rules name the label")
        val steps = session.snapshot.value.steps
        assertEquals(3, steps.size)
        assertSame(authorRow, steps[1], "the very same row")
        assertEquals(added.id, steps[1].id)
        assertTrue(steps[1].authorAdded && steps[1].sourceInputIds.isEmpty() && steps[1].checks.isEmpty())
        assertEquals(listOf(recorded[0].id), steps[0].sourceInputIds)
        assertEquals(listOf(recorded[1].id, recorded[2].id), steps[2].sourceInputIds)
        assertEquals(listOf(recorded[0].id, added.id, recorded[1].id, recorded[2].id), session.snapshot.value.rawSteps?.map { it.id })

        // Another rewrite starts from the same inputs; Undo puts everything back with the author row where it stood.
        val again = rewrite(session) { authoredRewrite }
        assertIs<StoreResult.Ok<RecordingRewrite>>(again)
        assertTrue(session.restoreRaw())
        assertEquals(listOf(recorded[0].id, added.id, recorded[1].id, recorded[2].id), session.ids())
    }

    @Test
    fun anAuthorRowAddedAfterARewriteStaysThroughUndoAndIsDeletedByRemove() {
        val session = shop()
        val recorded = session.snapshot.value.steps
        assertIs<StoreResult.Ok<RecordingRewrite>>(rewrite(session) { VALID_SHOP_REWRITE })
        val added = author()
        assertTrue(session.insertReviewedRow(1, added))

        assertEquals(listOf(recorded[0].id, recorded[1].id, added.id, recorded[2].id), session.rewriteRows().map { it.id })
        assertTrue(session.removeReviewedRows(setOf(added.id)))
        assertTrue(session.snapshot.value.rawSteps.orEmpty().none { it.id == added.id })
        assertTrue(session.insertReviewedRow(2, added))
        assertTrue(session.restoreRaw())
        assertEquals(listOf(recorded[0].id, recorded[1].id, recorded[2].id, added.id), session.ids())
        assertTrue(session.snapshot.value.excludedSourceInputIds.isEmpty())
    }

    @Test
    fun aRewriteAnswerThatDropsMergesChangesOrDecoratesAnAuthorRowIsRejectedAndChangesNothing() {
        fun rejected(answer: String, expected: String) {
            val session = shop()
            assertTrue(session.insertReviewedRow(1, author()))
            val before = session.snapshot.value
            val result = rewrite(session) { answer }
            val reason = assertIs<StoreResult.Invalid>(result).reason
            assertTrue(expected in reason, reason)
            assertEquals(before.steps, session.snapshot.value.steps)
            assertNull(session.snapshot.value.rawSteps)
            assertFalse(session.snapshot.value.rewriteApplied)
        }
        val tap = """{"action":"Tap Search","expected":"Focused","sourceInputs":[1]}"""
        val author = """{"action":"$AUTHOR_ACTION","expected":"$AUTHOR_EXPECTED","sourceInputs":[2]}"""
        val rest = """{"action":"Search","expected":"Results","sourceInputs":[3,4]}"""

        rejected("""{"steps":[$tap,$rest]}""", "input 2")
        rejected("""{"steps":[{"action":"Tap and sign in","expected":"x","sourceInputs":[1,2]},$rest]}""", "never merged")
        rejected(
            """{"steps":[$tap,{"action":"Log in","expected":"$AUTHOR_EXPECTED","sourceInputs":[2]},$rest]}""",
            "action and expected must equal the person's text exactly",
        )
        rejected(
            """{"steps":[$tap,{"action":"$AUTHOR_ACTION","expected":"The home screen is visible","sourceInputs":[2]},$rest]}""",
            "action and expected must equal",
        )
        rejected(
            """{"steps":[$tap,{"action":"$AUTHOR_ACTION","expected":"$AUTHOR_EXPECTED","optional":true,"condition":"when","sourceInputs":[2]},$rest]}""",
            "optional and condition must equal",
        )
        rejected(
            """{"steps":[$tap,{"action":"$AUTHOR_ACTION","expected":"$AUTHOR_EXPECTED","sourceInputs":[2],""" +
                """"checks":[{"type":"askJudge","text":"ok"}]},$rest]}""",
            "leave out checks",
        )
        rejected("""{"steps":[$tap,$rest,$author]}""", "Inputs must be covered in order")
        rejected("""{"steps":[$author,$tap,$rest]}""", "Inputs must be covered in order")
    }

    @Test
    fun aFixedStepMayHaveBlankExpectedTextAndAnOptionalConditionWhenTheAnswerRepeatsThemExactly() {
        val fixed = mapOf(
            2 to RecordedTestStep(action = "Dismiss the dialog", expected = "", optional = true, condition = "A dialog is visible", authorAdded = true),
        )
        val proposal = parseRewriteResponse(
            """{"steps":[
                {"action":"Tap","expected":"Done","sourceInputs":[1]},
                {"action":"Dismiss the dialog","expected":"","optional":true,"condition":"A dialog is visible","sourceInputs":[2]}
            ]}""",
            2,
            fixed,
        )
        assertEquals(listOf(listOf(1), listOf(2)), proposal.steps.map { it.sourceInputs })
        assertFailsWith<IllegalArgumentException> {
            parseRewriteResponse("""{"steps":[{"action":"Tap Dismiss","expected":"","sourceInputs":[1,2]}]}""", 2, fixed)
        }
    }

    @Test
    fun aRewriteIsBlockedUntilEveryAddedStepHasAnActionAndSomeRecordedInputRemains() {
        val session = texts(2)
        val ids = session.ids()
        val blank = RecordedTestStep(action = "")
        assertTrue(session.insertReviewedRow(1, blank))
        assertEquals("An added step has no action. Fill it in or remove it before rewriting.", session.rewriteBlockedReason())
        assertTrue(session.replaceSteps(session.snapshot.value.steps.map { if (it.id == blank.id) it.copy(action = "Filled in") else it }))
        assertNull(session.rewriteBlockedReason())
        assertTrue(session.replaceSteps(session.snapshot.value.steps.map { if (it.id == blank.id) it.copy(expected = "x".repeat(2_001)) else it }))
        assertTrue("longer than 2000" in session.rewriteBlockedReason().orEmpty())

        assertTrue(session.removeReviewedRows(setOf(blank.id)))
        assertTrue(session.removeReviewedRows(ids.toSet()))
        assertTrue(session.insertReviewedRow(0, author()))
        assertTrue(
            session.rewriteBlockedReason().orEmpty().startsWith("No recorded inputs remain"),
            "an author row alone is nothing to rewrite",
        )
    }

    @Test
    fun theInputToolReportsAnAuthorRowAsFixedWithoutRecordedFacts() {
        val session = shop()
        assertTrue(session.insertReviewedRow(1, author()))
        val inputs = rewriteInputsOf(session.snapshot.value.steps, session::screenState)
        val gateway = RecordingRewriteTools(inputs).gateway

        @Suppress("UNCHECKED_CAST")
        val fixed = gateway.execute("get_recorded_input", mapOf("index" to 2)) as Map<String, Any?>
        assertEquals("AUTHOR_STEP", fixed["kind"])
        assertEquals(true, fixed["fixed"])
        assertTrue(AUTHOR_ACTION in fixed.toString() && "hasBeforeScreen" !in fixed)
        @Suppress("UNCHECKED_CAST")
        val text = gateway.execute("get_recorded_input", mapOf("index" to 3)) as Map<String, Any?>
        assertEquals("TEXT", text["kind"], "numbers follow the review order")
    }

    // ── update with a new order ──────────────────────────────────────

    @Test
    fun updateReviewedStepsReordersOnlyWhenAllowedAndOnlyForACompletePermutation() {
        val session = texts(3)
        val ids = session.ids()
        val edits = listOf("c" to "ec", "a" to "ea", "b" to "eb")
        val order = listOf(ids[2], ids[0], ids[1])

        assertFalse(session.updateReviewedSteps(edits, expectedRowIds = order), "apply never reorders")
        assertFalse(session.updateReviewedSteps(edits, expectedRowIds = listOf(ids[2], ids[0], ids[0]), allowReorder = true))
        assertFalse(session.updateReviewedSteps(edits, expectedRowIds = listOf(ids[2], ids[0], "other"), allowReorder = true))
        assertFalse(session.updateReviewedSteps(edits.take(2), expectedRowIds = order.take(2), allowReorder = true))
        assertEquals(ids, session.ids())

        assertTrue(session.updateReviewedSteps(edits, expectedRowIds = order, allowReorder = true))
        assertEquals(order, session.ids())
        assertEquals(listOf("c", "a", "b"), session.snapshot.value.steps.map { it.action })
        assertEquals(listOf("ec", "ea", "eb"), session.snapshot.value.steps.map { it.expected })
    }

    private companion object {
        val NUMBER_NAMES = listOf("first", "second", "third", "fourth", "fifth", "sixth")
    }
}
