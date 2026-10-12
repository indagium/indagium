@file:Suppress("MagicNumber") // Fixture counts, not tunable constants.

package com.indagium.testing

import com.indagium.capture.mirror.MirrorControlCommand
import com.indagium.capture.mirror.MirrorTouchAction
import com.indagium.testing.authoring.RecordingRewrite
import com.indagium.testing.authoring.RecordingRewriteService
import com.indagium.testing.authoring.TestStepRecordingApplyService
import com.indagium.testing.authoring.TestStepRecordingSession
import com.indagium.testing.authoring.isCoordinateOnlyAction
import com.indagium.testing.authoring.recordingApplyBlockedReason
import com.indagium.testing.model.ScreenshotRole
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
import kotlin.test.assertNotNull
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
        val steps = session.toTestSteps { "assets/recorded-reference.jpg" }

        assertEquals(listOf("Search for 'lofi'", "Submit the search"), steps.map { it.action })
        assertEquals(listOf("The search field shows 'lofi'", "Results for 'lofi' are listed"), steps.map { it.expected })
        steps.forEach { step ->
            val hint = step.examples.filterIsInstance<StepExample.ReferenceLog>().single()
            assertEquals(HINT_CAPTION, hint.caption)
            assertTrue(step.examples.filterIsInstance<StepExample.GoldenScreenshot>().all { it.role != ScreenshotRole.EXPECTED })
        }
        val inputHints = steps[0].examples.filterIsInstance<StepExample.ReferenceLog>().single().text.lines()
        assertEquals(2, inputHints.size)
        assertTrue("Tap at (54%, 31%)" in inputHints[0])
        assertTrue("exact node under touch: text=\"Search\"" in inputHints[0], inputHints[0])
        assertTrue("touch within node: 20%,46%" in inputHints[0], inputHints[0])
        assertTrue("normalized path: (54%,31%)" in inputHints[0], inputHints[0])
        assertTrue("gap before next input: 460 ms" in inputHints[0], inputHints[0])
        assertTrue("2. Enter text: lofi | gap before next input: 1000 ms" in inputHints[1])
        assertEquals(
            listOf("3. Press Enter | before app: unavailable | before activity: unavailable | after app: $SHOP_PACKAGE | after activity: .ResultsActivity"),
            steps[1].examples.filterIsInstance<StepExample.ReferenceLog>().single().text.lines(),
        )
    }

    @Test
    fun theGoldenScreenshotIsAttachedOnlyWhenTheReviewerTickedIt() {
        val session = rewritten()
        val rows = session.snapshot.value.steps
        val saved = mutableListOf<ByteArray>()
        val selected = assertNotNull(session.expectedScreenshotSelectionFor(rows[1].id))

        assertTrue(
            session.updateReviewedSteps(
                rows.map { it.action to it.expected },
                setOf(rows[1].id),
                rows.map { it.id },
                expectedScreenshotSelections = mapOf(rows[1].id to selected),
            ),
        )
        val steps = session.toTestSteps { bytes -> saved += bytes; "assets/after-search.jpg" }

        assertEquals(3, saved.size, "two time-aligned references and the separately reviewed expected image are imported")
        assertEquals(2, saved.count { SHOP_AFTER_IMAGE.contentEquals(it) }, "the after reference and reviewed expected can share the same capture")
        assertTrue(steps[0].examples.filterIsInstance<StepExample.GoldenScreenshot>().all { it.role != ScreenshotRole.EXPECTED })
        val golden = steps[1].examples.filterIsInstance<StepExample.GoldenScreenshot>().single { it.role == ScreenshotRole.EXPECTED }
        assertEquals("assets/after-search.jpg", golden.assetPath)
        assertTrue("verified after evidence" in golden.caption, golden.caption)
        assertTrue(steps[1].examples.first() is StepExample.ReferenceLog, "the hint stays")
    }

    @Test
    fun anAfterImageCannotBeAppliedAfterItsReviewedRowChangedDuringPreviewDecode() {
        val session = rewritten()
        val rows = session.snapshot.value.steps
        val selected = assertNotNull(session.expectedScreenshotSelectionFor(rows[1].id))
        val changedRows = rows.mapIndexed { index, row ->
            (if (index == 1) "Edited ${row.action}" else row.action) to row.expected
        }
        assertTrue(session.updateReviewedSteps(changedRows))

        assertFalse(
            session.updateReviewedSteps(
                changedRows,
                setOf(rows[1].id),
                rows.map { it.id },
                expectedScreenshotSelections = mapOf(rows[1].id to selected),
            ),
        )
        assertTrue(session.toTestSteps { "assets/after-search.jpg" }.all { step ->
            step.examples.filterIsInstance<StepExample.GoldenScreenshot>().none { it.role == ScreenshotRole.EXPECTED }
        })
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
        assertEquals(recorded, session.snapshot.value.rawSteps, "the immutable source history remains available after undo")
        assertTrue(session.snapshot.value.excludedSourceInputIds.isEmpty())
        assertFalse(session.snapshot.value.rewriteApplied)
        assertEquals("", session.snapshot.value.rewriteNotes)
        assertFalse(session.restoreRaw(), "nothing left to undo")
        val raw = session.toTestSteps()
        assertEquals(3, raw.size)
        val contexts = raw.map { it.examples.single() }
        assertTrue(contexts.all { it is StepExample.ReferenceLog && it.caption.startsWith("Recorded input context") }, "raw rows apply as before")
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
            importImage = { _, _ -> StoreResult.Ok("assets/recorded-reference.jpg") },
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
    fun aConditionedOptionalBlankExpectedIsAppliedAndKeepsItsSeparateRequiredStep() = runBlocking {
        val response = """{"steps":[
            {"action":"Dismiss the ad if Skip ad appears","expected":"","optional":true,
                "condition":"A visible Skip ad button appears.","sourceInputs":[1]},
            {"action":"Pause playback","expected":"The player shows paused state","sourceInputs":[2,3]}
        ]}"""
        val session = rewritten(response)
        val inserted = mutableListOf<TestStep>()
        val applier = TestStepRecordingApplyService(
            preflight = { _, _ -> StoreResult.Ok(Unit) },
            importImage = { _, _ -> StoreResult.Ok("assets/recorded-reference.jpg") },
            resolveImage = { _, _ -> File("unused") },
            insertSteps = { _, steps, _ -> inserted += steps; StoreResult.Ok(steps) },
        )

        assertNull(recordingApplyBlockedReason(session.snapshot.value))
        assertIs<StoreResult.Ok<List<TestStep>>>(applier.apply(session, SHOP_SUITE_ID, SHOP_CASE_ID))

        assertEquals(listOf("Dismiss the ad if Skip ad appears", "Pause playback"), inserted.map { it.action })
        assertEquals(listOf(true, false), inserted.map { it.optional })
        assertEquals("A visible Skip ad button appears.", inserted.first().condition)
        assertEquals("", inserted.first().expected)
        assertEquals("The player shows paused state", inserted.last().expected)
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

    @Test
    fun anUnresolvedTapStaysGenericAndRequiresAnEditedExpectedResultBeforeApply() = runBlocking {
        val session = TestStepRecordingSession("fixture-device").also { sessions += it }
        session.accept(
            MirrorControlCommand.Touch(action = MirrorTouchAction.DOWN, pointerId = 1, x = 54, y = 62, screenWidth = 100, screenHeight = 200),
            nowMs = 100,
        )
        session.accept(
            MirrorControlCommand.Touch(action = MirrorTouchAction.UP, pointerId = 1, x = 54, y = 62, screenWidth = 100, screenHeight = 200),
            nowMs = 120,
        )
        session.stopAndDrain()
        val response = """{"steps":[{"action":"Open settings","expected":"Settings is open","optional":true,
            "condition":"A settings shortcut is visible.","sourceInputs":[1],
            "expectedScreenshot":"after-of-input-1","checks":[{"type":"askJudge","text":"Is settings open?"}]}]}"""
        val rewrite = RecordingRewriteService({ shopLibrary() }, { _, _ -> StoreResult.Ok(Unit) }, { response })
        val result = rewrite.rewrite(session, SHOP_SUITE_ID, SHOP_CASE_ID, "p", null, null, "")
        assertIs<StoreResult.Ok<RecordingRewrite>>(result, result.toString())

        val row = session.snapshot.value.steps.single()
        assertTrue(row.reviewReason?.contains("resolved target") == true)
        assertEquals("Repeat the recorded tap", row.action)
        assertEquals("", row.expected)
        assertTrue(row.sourceHint?.contains("Tap at (54%, 31%)") == true, row.sourceHint.orEmpty())
        assertFalse(row.optional, "an unresolved gesture cannot bypass review by being marked optional")
        assertNull(row.condition)
        assertTrue(row.reviewReason.contains("resolved target"))
        assertTrue(row.reviewReason.contains("inspected before video/screenshot evidence"))
        assertTrue(row.checks.isEmpty())
        assertNull(row.screenshotJpeg)
        assertTrue(recordingApplyBlockedReason(session.snapshot.value).orEmpty().contains("Expected"))

        val inserted = mutableListOf<TestStep>()
        val applier = TestStepRecordingApplyService(
            preflight = { _, _ -> StoreResult.Ok(Unit) },
            importImage = { _, _ -> error("no uncertain screenshot may be attached") },
            resolveImage = { _, _ -> null },
            insertSteps = { _, steps, _ -> inserted += steps; StoreResult.Ok(steps) },
        )
        assertIs<StoreResult.Invalid>(applier.apply(session, SHOP_SUITE_ID, SHOP_CASE_ID))
        assertTrue(session.updateReviewedSteps(listOf("Tap Settings" to "The Settings screen is visible")))
        assertNull(session.snapshot.value.steps.single().reviewReason, "a supplied expected result clears the review-required state")
        assertIs<StoreResult.Ok<List<TestStep>>>(applier.apply(session, SHOP_SUITE_ID, SHOP_CASE_ID))
        assertEquals("Tap Settings", inserted.single().action)
    }

    @Test
    fun coordinateOnlyActionsAreReviewedWhileSemanticActionsRemainPrimary() {
        assertTrue(isCoordinateOnlyAction("Tap at (56%, 94%)"))
        assertTrue(isCoordinateOnlyAction("Swipe from (10%, 20%) to (40%, 50%)"))
        assertFalse(isCoordinateOnlyAction("Open YouTube"))
        assertFalse(isCoordinateOnlyAction("Tap Search at (56%, 94%)"))
    }

    @Test
    fun coordinateOnlyActionWithAnExpectedResultBecomesAGenericReviewedGesture() {
        val response = """{"steps":[
            {"action":"Tap at (54%, 31%)","expected":"The search field shows lofi","sourceInputs":[1]},
            {"action":"Enter text: lofi","expected":"","sourceInputs":[2],"reviewReason":"Confirm the text."},
            {"action":"Press Enter","expected":"Results for lofi are listed","sourceInputs":[3]}
        ]}"""
        val session = recordedShopSession().also { sessions += it }
        val service = RecordingRewriteService({ shopLibrary() }, { _, _ -> StoreResult.Ok(Unit) }, { response })
        val result = runBlocking {
            service.rewrite(session, SHOP_SUITE_ID, SHOP_CASE_ID, "p", null, null, "")
        }
        assertIs<StoreResult.Ok<RecordingRewrite>>(result, result.toString())

        val row = session.snapshot.value.steps.first()
        assertEquals("Repeat the recorded tap", row.action)
        assertEquals("", row.expected)
        assertTrue(row.reviewReason?.contains("recorded coordinates") == true, row.reviewReason.orEmpty())
        assertTrue(row.sourceHint?.contains("Tap at (54%, 31%)") == true, row.sourceHint.orEmpty())
    }
}
