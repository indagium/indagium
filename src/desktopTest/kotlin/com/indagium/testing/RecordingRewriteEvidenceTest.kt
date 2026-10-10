@file:Suppress("MagicNumber") // Fixture sizes and times, not tunable constants.

package com.indagium.testing

import com.indagium.testing.authoring.REWRITE_CLOSEUP_TOOL_CALLS
import com.indagium.testing.authoring.REWRITE_MAX_ATTACHED_IMAGES
import com.indagium.testing.authoring.REWRITE_MAX_ATTACHED_IMAGE_BYTES
import com.indagium.testing.authoring.REWRITE_VIDEO_LEAD_MS
import com.indagium.testing.authoring.REWRITE_VIDEO_MAX_RANGE_MS
import com.indagium.testing.authoring.REWRITE_VIDEO_TRAIL_MS
import com.indagium.testing.authoring.RecordedInputKind
import com.indagium.testing.authoring.RecordedPoint
import com.indagium.testing.authoring.RecordedTestStep
import com.indagium.testing.authoring.RecordingScreenState
import com.indagium.testing.authoring.RecordingScreenshotEvidence
import com.indagium.testing.authoring.RecordingScreenshotSource
import com.indagium.testing.authoring.RewriteEvidenceKind
import com.indagium.testing.authoring.RewriteInput
import com.indagium.testing.authoring.RewriteVideoSheet
import com.indagium.testing.authoring.RewriteVideoSheets
import com.indagium.testing.authoring.buildRewriteEvidence
import com.indagium.testing.authoring.rewriteInputsOf
import com.indagium.testing.authoring.rewriteNumberRanges
import com.indagium.testing.authoring.rewritePrompt
import com.indagium.testing.authoring.rewriteToolCallLimit
import com.indagium.testing.device.UiNode
import com.indagium.testing.model.TestCase
import com.indagium.testing.model.TestSuite
import java.awt.Color
import java.awt.image.BufferedImage
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import javax.imageio.ImageIO
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

private const val PACKAGE = "com.example.shop"
private const val STEP_MS = 1_000L

/** The evidence a rewrite's first message carries: which images, for which inputs, and what is never shown. */
class RecordingRewriteEvidenceTest {
    private val sessions = mutableListOf<com.indagium.testing.authoring.TestStepRecordingSession>()

    @AfterTest
    fun closeSessions() = sessions.forEach { it.close() }

    private fun jpeg(shade: Int, width: Int = 90, height: Int = 160): ByteArray {
        val image = BufferedImage(width, height, BufferedImage.TYPE_INT_RGB)
        val graphics = image.createGraphics()
        graphics.color = Color(shade % 256, (shade * 7) % 256, (shade * 13) % 256)
        graphics.fillRect(0, 0, width, height)
        graphics.dispose()
        return ByteArrayOutputStream().also { ImageIO.write(image, "jpg", it) }.toByteArray()
    }

    private fun shot(shade: Int, at: Long? = null) = RecordingScreenshotEvidence(jpeg(shade), RecordingScreenshotSource.SCREEN_PROBE, at, at, false)

    private fun node(left: Int, top: Int, text: String, password: Boolean = false, className: String = "android.widget.Button") =
        UiNode(left, top, left + 100, top + 40, text, "", "", className, true, true, false, PACKAGE, password, false)

    private fun state(seq: Int, at: Long, vararg nodes: UiNode) =
        RecordingScreenState(seq, at, at + 10, PACKAGE, ".Activity$seq", nodes.toList(), 1000, 2000)

    /** [count] taps one second apart; with [shared] the after-screenshot of a tap is the before-screenshot of the next one. */
    private fun taps(count: Int, shared: Boolean = false): List<RewriteInput> {
        var previousAfter: RecordingScreenshotEvidence? = null
        return (1..count).map { number ->
            val start = number * STEP_MS
            val before = if (shared) previousAfter ?: shot(number * 2) else shot(number * 2)
            val after = shot(number * 2 + 1)
            previousAfter = after
            val row = RecordedTestStep(
                action = "Tap at (50%, 25%)",
                kind = RecordedInputKind.TAP,
                gesturePath = listOf(RecordedPoint(500, 500, 1000, 2000)),
                inputStartMs = start,
                inputAtMs = start + 40,
                beforeScreenshot = before,
                afterScreenshot = after,
            )
            val beforeState = state(number, start, node(450, 480, "Item $number"), node(0, 1800, "Footer"))
            RewriteInput(number, row, beforeState, state(number + 100, start + 50, node(0, 0, "Page $number")))
        }
    }

    private fun suite() = TestSuite("s", "Shop suite", targetPackage = PACKAGE, cases = listOf(TestCase("c", "Search", description = "Find music.")))

    // ── Which images ─────────────────────────────────────────────────

    @Test
    fun aSmallRecordingAttachesTheBeforeAndAfterScreenshotOfEveryInputOnce() {
        val inputs = taps(3)

        val evidence = buildRewriteEvidence(inputs)

        assertEquals(RewriteEvidenceKind.SCREENSHOTS, evidence.kind)
        assertEquals(6, evidence.images.size)
        assertTrue(evidence.images.size <= REWRITE_MAX_ATTACHED_IMAGES)
        val labels = listOf("input 1 before", "input 1 after", "input 2 before", "input 2 after", "input 3 before", "input 3 after")
        assertEquals(labels, evidence.images.map { it.label })
        assertEquals(setOf(1, 2, 3), evidence.images.flatMap { it.inputs }.toSet())
        assertTrue(evidence.images.all { it.mimeType == "image/jpeg" })
        assertEquals(6, evidence.images.map { it.bytes.toList() }.distinct().size, "no screenshot is attached twice")
        assertTrue(evidence.notAttached.isEmpty())
        assertEquals(6, evidence.llmImages().size)
    }

    @Test
    fun aCaptureSharedByTwoInputsIsAttachedOnceAndNamesBoth() {
        val evidence = buildRewriteEvidence(taps(3, shared = true))

        assertEquals(4, evidence.images.size)
        assertEquals("input 1 after, input 2 before", evidence.images[1].label)
        assertEquals(listOf(1, 2), evidence.images[1].inputs)
        assertTrue("attached: Image 2 (before), Image 3 (after)" in evidence.lineExtra(2, true), evidence.lineExtra(2, true))
    }

    @Test
    fun aLongRecordingIsCoveredByContactSheetsEveryInputExactlyOnceWithinTheBudget() {
        for (count in listOf(6, 12, 30, 31, 60)) {
            val inputs = taps(count)

            val evidence = buildRewriteEvidence(inputs)

            assertEquals(RewriteEvidenceKind.SHEETS, evidence.kind, "$count inputs")
            assertTrue(evidence.images.size <= REWRITE_MAX_ATTACHED_IMAGES, "$count inputs: ${evidence.images.size} images")
            assertTrue(evidence.images.sumOf { it.bytes.size } <= REWRITE_MAX_ATTACHED_IMAGE_BYTES)
            assertEquals((1..count).toList(), evidence.images.flatMap { it.inputs }, "$count inputs, each in exactly one sheet, in order")
            assertTrue(evidence.notAttached.isEmpty(), "$count inputs")
            assertTrue(evidence.images.all { image -> ImageIO.read(ByteArrayInputStream(image.bytes)) != null }, "every sheet is a real image")
        }
        assertEquals("Image 1 (contact sheet)", buildRewriteEvidence(taps(12)).lineExtra(2, true).substringAfter("attached: "))
    }

    @Test
    fun aRecordingBeyondTheSheetBudgetNamesTheInputsLeftToTheTools() {
        val evidence = buildRewriteEvidence(taps(80))

        assertEquals(REWRITE_MAX_ATTACHED_IMAGES, evidence.images.size)
        val attached = evidence.images.flatMap { it.inputs }
        assertEquals(attached.size + evidence.notAttached.size, 80)
        assertEquals((1..80).toList(), (attached + evidence.notAttached).sorted(), "every input is either on a sheet or named for the tools")
        assertTrue(attached.toSet().intersect(evidence.notAttached.toSet()).isEmpty())
        val prompt = rewritePrompt(suite(), suite().cases.single(), "", taps(80), evidence = evidence, attachImages = true)
        assertTrue("No image is attached for input(s) ${rewriteNumberRanges(evidence.notAttached)}" in prompt, prompt.take(3000))
    }

    @Test
    fun theVideoStoryboardIsPreferredForARunOfInputsAndAComposedSheetCoversTheRest() {
        val inputs = taps(12)
        val asked = mutableListOf<Pair<Long, Long>>()
        val marker = byteArrayOf(1, 2, 3)
        val video = RewriteVideoSheets { start, end ->
            asked += start to end
            if (asked.size == 1) RewriteVideoSheet(marker, "image/png", mapOf(1 to setOf("before"), 2 to setOf("after"))) else null
        }

        val evidence = buildRewriteEvidence(inputs, video)

        assertEquals(4, evidence.images.size, "12 inputs, three to a sheet")
        assertEquals(listOf(1, 2, 3), evidence.images[0].inputs)
        assertEquals(marker.toList(), evidence.images[0].bytes.toList())
        assertEquals("image/png", evidence.images[0].mimeType)
        assertTrue(evidence.images[0].label.startsWith("video storyboard"), evidence.images[0].label)
        assertEquals(mapOf(1 to setOf("before"), 2 to setOf("after")), evidence.videoMoments)
        assertTrue(evidence.images[1].label.startsWith("contact sheet"), "a group the video cannot show gets a composed sheet")
        assertEquals((1..12).toList(), evidence.images.flatMap { it.inputs })
        assertEquals(4, asked.size)
        assertEquals((1 * STEP_MS - REWRITE_VIDEO_LEAD_MS) to (3 * STEP_MS + 40 + REWRITE_VIDEO_TRAIL_MS), asked[0])
    }

    @Test
    fun aRunTooLongForTheVideoStoryboardIsNotAskedFor() {
        val inputs = taps(12).map { input ->
            val shift = input.number * REWRITE_VIDEO_MAX_RANGE_MS
            RewriteInput(input.number, input.row.copy(inputStartMs = shift, inputAtMs = shift + 40), input.before, input.after)
        }
        var asked = 0

        val evidence = buildRewriteEvidence(inputs, RewriteVideoSheets { _, _ -> asked++.let { null } })

        assertEquals(0, asked)
        assertTrue(evidence.images.all { it.label.startsWith("contact sheet") })
    }

    @Test
    fun anAuthorStepAnAnExternalEventAndAnInputWithoutAScreenGetNoImage() {
        val recorded = taps(4)
        val inputs = listOf(
            recorded[0],
            RewriteInput(2, RecordedTestStep(action = "Sign in", expected = "Home", authorAdded = true), null, null),
            RewriteInput(3, recorded[2].row.copy(kind = RecordedInputKind.EXTERNAL, action = "Connect the USB device"), null, null),
            RewriteInput(4, recorded[3].row.copy(beforeScreenshot = null, afterScreenshot = null), null, null),
            RewriteInput(5, recorded[1].row, recorded[1].before, recorded[1].after),
        )

        val evidence = buildRewriteEvidence(inputs)

        assertEquals(listOf(1, 5), evidence.images.flatMap { it.inputs }.distinct())
        assertEquals("", evidence.lineExtra(2, true))
        assertEquals("", evidence.lineExtra(3, true))
        assertEquals("", evidence.lineExtra(4, true))
        assertEquals(RewriteEvidenceKind.NONE, buildRewriteEvidence(listOf(inputs[1], inputs[2], inputs[3])).kind)
    }

    // ── What is never shown ──────────────────────────────────────────

    @Test
    fun aMaskedPasswordRowAndTheScreensAroundItContributeNothing() {
        val secret = shot(200, at = 1_000)
        val rows = taps(4)
        // Input 2 types a password: its row is masked, its screens show the focused password field. Input 4 happens after the
        // screen without a password field was reached again.
        val passwordScreen = state(50, 2 * STEP_MS, node(100, 100, "Password", password = true).copy(focused = true))
        val clean = state(60, 3 * STEP_MS + 100, node(0, 0, "Home"))
        val maskedRow = rows[1].row.copy(action = "Enter text: ••••", kind = RecordedInputKind.TEXT, beforeScreenshot = secret, afterScreenshot = secret)
        val inputs = listOf(
            rows[0],
            RewriteInput(2, maskedRow, passwordScreen, passwordScreen),
            RewriteInput(3, rows[2].row, rows[2].before, clean),
            RewriteInput(4, rows[3].row.copy(inputStartMs = 6 * STEP_MS, inputAtMs = 6 * STEP_MS + 40), rows[3].before, rows[3].after),
        )

        val evidence = buildRewriteEvidence(inputs)

        val shown = evidence.images.flatMap { it.inputs }
        assertFalse(2 in shown, "the masked row has no image")
        assertFalse(1 in shown, "an earlier screen may be the password screen's pre-roll")
        assertFalse(3 in shown)
        assertEquals(setOf(4), shown.toSet())
        assertTrue(evidence.images.none { it.bytes.contentEquals(secret.jpeg) })
        assertTrue("withheld" in evidence.lineExtra(2, true) && "Password" !in evidence.lineExtra(2, true))
        val prompt = rewritePrompt(suite(), suite().cases.single(), "", inputs, evidence = evidence, attachImages = true)
        assertFalse("Image 1: input 2" in prompt)
    }

    @Test
    fun inputsWellBeforeThePasswordKeepTheirScreens() {
        val rows = taps(3)
        val passwordAt = 10 * STEP_MS
        val passwordScreen = state(50, passwordAt, node(100, 100, "Password", password = true).copy(focused = true))
        val masked = rows[2].row.copy(action = "Enter text: ••••", kind = RecordedInputKind.TEXT, inputStartMs = passwordAt, inputAtMs = passwordAt + 40)
        val inputs = listOf(rows[0], rows[1], RewriteInput(3, masked, passwordScreen, passwordScreen))

        val evidence = buildRewriteEvidence(inputs)

        val shown = evidence.images.flatMap { it.inputs }.toSet()
        assertEquals(setOf(1, 2), shown, "opening the app and tapping Login happen long before the password and stay visible")
        assertTrue("withheld" in evidence.lineExtra(3, true))
    }

    @Test
    fun aScreenshotTakenInASensitiveWindowIsNotAttachedEvenForAnOrdinaryInput() {
        val rows = taps(3)
        val passwordScreen = state(50, 2_000, node(100, 100, "Password", password = true).copy(focused = true))
        val masked = rows[1].row.copy(action = "Enter text: ••••", kind = RecordedInputKind.TEXT)
        val late = shot(77, at = 1_500)
        val inputs = listOf(
            RewriteInput(1, rows[0].row.copy(inputStartMs = 5_000, inputAtMs = 5_040, afterScreenshot = late), null, null),
            RewriteInput(2, masked.copy(inputStartMs = 2_000, inputAtMs = 2_040), passwordScreen, passwordScreen),
        )

        val evidence = buildRewriteEvidence(inputs)

        assertTrue(evidence.images.isEmpty(), "no screen proves the password screen is gone, so everything before the end is withheld")
        assertTrue("withheld" in evidence.lineExtra(1, true))
    }

    @Test
    fun aRecordedPasswordSessionOffersNoImagesAtAll() {
        val session = recordedShopSession(typed = "hunter2", passwordField = true).also { sessions += it }
        val inputs = rewriteInputsOf(session.snapshot.value.steps, session::screenState)

        val evidence = buildRewriteEvidence(inputs)

        assertTrue(inputs.first().row.action.contains("••••"))
        assertTrue(evidence.images.isEmpty())
        val prompt = rewritePrompt(suite(), suite().cases.single(), "", inputs, evidence = evidence, attachImages = true)
        assertFalse("hunter2" in prompt)
    }

    // ── The text ─────────────────────────────────────────────────────

    @Test
    fun theInputLinesCarryTheElementsNearTheTouchAndTheScreenTextAfterIt() {
        val inputs = taps(2)
        val withPassword = inputs[0].before!!.copy(
            nodes = inputs[0].before!!.nodes + node(450, 520, "SECRET", password = true) + node(0, 900, "Query").copy(focused = true),
        )
        val evidence = buildRewriteEvidence(listOf(RewriteInput(1, inputs[0].row, withPassword, inputs[0].after), inputs[1]))

        val extra = evidence.lineExtra(1, withImages = true)

        assertTrue("UI near the touch (before): \"Item 1\" (Button, clickable) at 50%,25%" in extra, extra)
        assertTrue("texts on the screen after: \"Page 1\"" in extra, extra)
        assertFalse("SECRET" in extra)
        assertTrue("attached: Image 1 (before), Image 2 (after)" in extra, extra)
        assertFalse("attached" in evidence.lineExtra(1, withImages = false))
    }

    @Test
    fun theAttachedPromptListsTheImagesInOrderAndKeepsTheScreenTextInsideTheFence() {
        val inputs = taps(2)
        val evidence = buildRewriteEvidence(inputs)

        val prompt = rewritePrompt(suite(), suite().cases.single(), "", inputs, evidence = evidence, attachImages = true)
        val fenced = prompt.substringAfter("<untrusted_data source=\"recorded_inputs\">").substringBefore("</untrusted_data>")

        assertTrue("Evidence is attached to this message as 4 image(s), in this order:" in prompt, prompt)
        assertTrue("Image 3: input 2 before" in prompt)
        assertTrue("close-ups only" in prompt)
        assertTrue("UI near the touch (before)" in fenced && "attached: Image 1 (before)" in fenced, fenced)
        assertFalse("NOT attached" in prompt)
    }

    @Test
    fun theFallbackPromptNamesTheToolsInsteadOfTheImages() {
        val inputs = taps(2)
        val evidence = buildRewriteEvidence(inputs)

        val prompt = rewritePrompt(suite(), suite().cases.single(), "", inputs, evidence = evidence, attachImages = false)

        assertTrue("The screenshots are NOT attached" in prompt, prompt)
        assertTrue("get_recorded_screen (index, moment before|after)" in prompt)
        assertFalse("Image 1:" in prompt)
        assertTrue("UI near the touch (before)" in prompt, "the screen text is cheap and goes either way")
        assertFalse("attached: Image" in prompt)
    }

    // ── The budget ───────────────────────────────────────────────────

    @Test
    fun theToolBudgetIsASmallFixedNumberOfCloseUpsWhenTheEvidenceIsUpFront() {
        assertEquals(30, rewriteToolCallLimit(10, videoAvailable = false))
        assertEquals(40, rewriteToolCallLimit(10, videoAvailable = true))
        assertEquals(REWRITE_CLOSEUP_TOOL_CALLS, rewriteToolCallLimit(10, videoAvailable = false, evidenceUpFront = true))
        assertEquals(REWRITE_CLOSEUP_TOOL_CALLS, rewriteToolCallLimit(60, videoAvailable = true, evidenceUpFront = true))
        assertEquals(3, rewriteToolCallLimit(1, videoAvailable = false, evidenceUpFront = true), "never more than the full budget")
    }
}
