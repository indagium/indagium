@file:Suppress("MagicNumber") // Fixture counts, not tunable constants.

package com.indagium.testing

import com.indagium.testing.authoring.RecordingRewrite
import com.indagium.testing.authoring.RecordingRewriteService
import com.indagium.testing.authoring.TestStepRecordingApplyService
import com.indagium.testing.authoring.TestStepRecordingSession
import com.indagium.testing.model.StepExample
import com.indagium.testing.model.TestStep
import com.indagium.testing.store.StoreResult
import kotlinx.coroutines.runBlocking
import java.io.File
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

private const val HINT_CAPTION = "Recorded input (hint; prefer what is on screen)"

class RecordingRewriteApplyTest {
    private val sessions = mutableListOf<TestStepRecordingSession>()

    @AfterTest
    fun closeSessions() = sessions.forEach(TestStepRecordingSession::close)

    private fun rewritten(response: String = VALID_SHOP_REWRITE): TestStepRecordingSession {
        val session = recordedShopSession().also { sessions += it }
        val service = RecordingRewriteService({ shopLibrary() }, { _, _ -> StoreResult.Ok(Unit) }, { response })
        assertIs<StoreResult.Ok<RecordingRewrite>>(runBlocking { service.rewrite(session, SHOP_SUITE_ID, SHOP_CASE_ID, "p", null, null, "") })
        return session
    }

    @Test
    fun aRewrittenRowBecomesAReadableStepWithTheRecordedInputAsAHint() {
        val session = rewritten()
        val steps = session.toTestSteps { error("no golden image unless ticked") }

        assertEquals(listOf("Search for 'lofi'", "Submit the search"), steps.map { it.action })
        assertEquals(listOf("The search field shows 'lofi'", "Results for 'lofi' are listed"), steps.map { it.expected })
        steps.forEach { step ->
            val hint = step.examples.filterIsInstance<StepExample.ReferenceLog>().single()
            assertEquals(HINT_CAPTION, hint.caption)
            assertTrue(step.examples.none { it is StepExample.GoldenScreenshot })
        }
        assertEquals(
            listOf(
                "1. Tap at (54%, 31%) | element: \"Search\" id=search_button class=Button | app: $SHOP_PACKAGE",
                "2. Enter text: lofi",
            ),
            steps[0].examples.filterIsInstance<StepExample.ReferenceLog>().single().text.lines(),
        )
        assertEquals(
            listOf("3. Press Enter | app: $SHOP_PACKAGE"),
            steps[1].examples.filterIsInstance<StepExample.ReferenceLog>().single().text.lines(),
        )
    }

    @Test
    fun theGoldenScreenshotIsAttachedOnlyWhenTheReviewerTickedIt() {
        val session = rewritten()
        val rows = session.snapshot.value.steps
        val saved = mutableListOf<ByteArray>()

        assertTrue(session.updateReviewedSteps(rows.map { it.action to it.expected }, setOf(rows[1].id), rows.map { it.id }))
        val steps = session.toTestSteps { bytes -> saved += bytes; "assets/after-search.jpg" }

        assertEquals(1, saved.size, "only the ticked row is imported")
        assertTrue(SHOP_AFTER_IMAGE.contentEquals(saved.single()))
        assertTrue(steps[0].examples.none { it is StepExample.GoldenScreenshot })
        val golden = steps[1].examples.filterIsInstance<StepExample.GoldenScreenshot>().single()
        assertEquals("assets/after-search.jpg", golden.assetPath)
        assertTrue("after the recorded inputs" in golden.caption, golden.caption)
        assertTrue(steps[1].examples.first() is StepExample.ReferenceLog, "the hint stays")
    }

    @Test
    fun undoRestoresTheRecordedRowsAndTheirIdsExactly() {
        val session = recordedShopSession().also { sessions += it }
        val recorded = session.snapshot.value.steps
        val service = RecordingRewriteService({ shopLibrary() }, { _, _ -> StoreResult.Ok(Unit) }, { VALID_SHOP_REWRITE })
        runBlocking { service.rewrite(session, SHOP_SUITE_ID, SHOP_CASE_ID, "p", null, null, "") }
        assertEquals(2, session.snapshot.value.steps.size)

        assertTrue(session.restoreRaw())
        assertEquals(recorded, session.snapshot.value.steps)
        assertEquals(recorded.map { it.id }, session.snapshot.value.steps.map { it.id })
        assertNull(session.snapshot.value.rawSteps)
        assertEquals("", session.snapshot.value.rewriteNotes)
        assertFalse(session.restoreRaw(), "nothing left to undo")
        val raw = session.toTestSteps()
        assertEquals(3, raw.size)
        val contexts = raw.map { it.examples.single() }
        assertTrue(contexts.all { it is StepExample.ReferenceLog && it.caption.startsWith("Input-time") }, "raw rows apply as before")
    }

    @Test
    fun undoIsRefusedWhileTheRecordingIsReservedForAnApply() {
        val session = rewritten()
        assertTrue(session.freezeReviewedSnapshotForApply() != null)
        assertFalse(session.restoreRaw())
        assertEquals(2, session.snapshot.value.steps.size)
        session.releaseApplyReservation()
        assertTrue(session.restoreRaw())
    }

    @Test
    fun theReviewedEditRuleStillHoldsOnTheRewrittenRows() {
        val session = rewritten()
        val rows = session.snapshot.value.steps
        assertFalse(session.updateReviewedSteps(listOf("only one" to "row")), "the pair count must match the current rows, not the recorded ones")
        assertFalse(session.updateReviewedSteps(rows.map { it.action to it.expected }, expectedRowIds = rows.map { it.id }.reversed()))
        assertFalse(session.updateReviewedSteps(rows.map { it.action to it.expected }, expectedRowIds = session.snapshot.value.rawSteps!!.map { it.id }))
        assertTrue(session.updateReviewedSteps(listOf("Edited A" to "Edited B", "Edited C" to "Edited D"), expectedRowIds = rows.map { it.id }))
        val edited = session.snapshot.value
        assertEquals(listOf("Edited A", "Edited C"), edited.steps.map { it.action })
        assertEquals(rows.map { it.sourceInputIds }, edited.steps.map { it.sourceInputIds }, "the link to the recorded inputs survives an edit")
        assertEquals(3, edited.rawSteps!!.size)
    }

    @Test
    fun theApplyServiceInsertsTheRewrittenStepsWithChecksAndTheHint() = runBlocking {
        val response = """{"steps":[{"action":"Search for 'lofi'","expected":"Results are listed","sourceInputs":[1,2,3],
            "checks":[{"type":"askJudge","text":"Are lofi results visible?"}]}]}"""
        val session = rewritten(response)
        val inserted = mutableListOf<TestStep>()
        val applier = TestStepRecordingApplyService(
            preflight = { _, _ -> StoreResult.Ok(Unit) },
            importImage = { _, _ -> error("not ticked") },
            resolveImage = { _, _ -> File("unused") },
            insertSteps = { _, steps, _ -> inserted += steps; StoreResult.Ok(steps) },
        )

        val result = applier.apply(session, SHOP_SUITE_ID, SHOP_CASE_ID)

        assertIs<StoreResult.Ok<List<TestStep>>>(result)
        val step = inserted.single()
        assertEquals("Search for 'lofi'", step.action)
        assertEquals(1, step.checks.size)
        assertEquals(HINT_CAPTION, step.examples.filterIsInstance<StepExample.ReferenceLog>().single().caption)
    }

    @Test
    fun anUnfilledExpectedResultStillBlocksTheApplyOfARewrittenRecording() = runBlocking<Unit> {
        val session = rewritten()
        val rows = session.snapshot.value.steps
        assertTrue(session.updateReviewedSteps(rows.map { it.action to "" }))
        val applier = TestStepRecordingApplyService(
            preflight = { _, _ -> StoreResult.Ok(Unit) },
            importImage = { _, _ -> error("unused") },
            resolveImage = { _, _ -> null },
            insertSteps = { _, _, _ -> error("nothing may be inserted") },
        )
        assertIs<StoreResult.Invalid>(applier.apply(session, SHOP_SUITE_ID, SHOP_CASE_ID))
    }
}
