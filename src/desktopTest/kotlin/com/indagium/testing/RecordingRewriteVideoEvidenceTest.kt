@file:Suppress("MagicNumber") // Fixture sizes, colours and times, not tunable constants.

package com.indagium.testing

import com.indagium.testing.authoring.REWRITE_MAX_ATTACHED_IMAGES
import com.indagium.testing.authoring.REWRITE_NEXT_INPUT_GUARD_MS
import com.indagium.testing.authoring.REWRITE_SETTLE_MS
import com.indagium.testing.authoring.REWRITE_VIDEO_BEFORE_MS
import com.indagium.testing.authoring.RecordedInputKind
import com.indagium.testing.authoring.RecordedPoint
import com.indagium.testing.authoring.RecordedTestStep
import com.indagium.testing.authoring.RecordingScreenState
import com.indagium.testing.authoring.RecordingScreenshotEvidence
import com.indagium.testing.authoring.RecordingScreenshotSource
import com.indagium.testing.authoring.RewriteEvidenceKind
import com.indagium.testing.authoring.RewriteEvidenceSource
import com.indagium.testing.authoring.RewriteInput
import com.indagium.testing.authoring.RewriteVideoFrame
import com.indagium.testing.authoring.RewriteVideoSheet
import com.indagium.testing.authoring.RewriteVideoSheets
import com.indagium.testing.authoring.buildRewriteEvidence
import com.indagium.testing.authoring.rewritePrompt
import com.indagium.testing.device.UiNode
import com.indagium.testing.model.TestCase
import com.indagium.testing.model.TestSuite
import java.awt.Color
import java.awt.image.BufferedImage
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import javax.imageio.ImageIO
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

private const val PACKAGE = "com.example.video"
private const val GAP_MS = 5_000L
private const val TAP_MS = 40L

private val HOME = Color(30, 60, 220)
private val SPLASH = Color(220, 40, 40)
private val APP = Color(40, 200, 60)

/** The evidence of a rewrite when the recording has a video, and the "after" screen when it has not. */
class RecordingRewriteVideoEvidenceTest {
    private fun solid(color: Color, width: Int = 90, height: Int = 160): BufferedImage =
        BufferedImage(width, height, BufferedImage.TYPE_INT_RGB).also { image ->
            val graphics = image.createGraphics()
            graphics.color = color
            graphics.fillRect(0, 0, width, height)
            graphics.dispose()
        }

    private fun jpeg(color: Color): ByteArray = ByteArrayOutputStream().also { ImageIO.write(solid(color), "jpg", it) }.toByteArray()

    private fun shot(color: Color, at: Long?) = RecordingScreenshotEvidence(jpeg(color), RecordingScreenshotSource.SCREEN_PROBE, at, at, false)

    private fun state(startedAt: Long, vararg texts: String) = RecordingScreenState(
        0, startedAt, startedAt + 10, PACKAGE, ".Activity",
        texts.mapIndexed { index, text ->
            UiNode(0, index * 60, 100, index * 60 + 40, text, "", "", "android.widget.TextView", false, false, false, PACKAGE, false, false)
        },
        1000, 2000,
    )

    private fun tap(number: Int, startMs: Long, before: RecordingScreenshotEvidence? = null, after: RecordingScreenshotEvidence? = null) = RewriteInput(
        number,
        RecordedTestStep(
            action = "Tap at (50%, 25%)",
            kind = RecordedInputKind.TAP,
            gesturePath = listOf(RecordedPoint(500, 500, 1000, 2000)),
            inputStartMs = startMs,
            inputAtMs = startMs + TAP_MS,
            beforeScreenshot = before,
            afterScreenshot = after,
        ),
        null,
        null,
    )

    /** [count] taps [GAP_MS] apart, each with a stored before and after screenshot (distinct per input). */
    private fun tapsWithScreenshots(count: Int) = (1..count).map { number ->
        tap(number, number * GAP_MS, shot(Color(number * 3 % 256, 90, 90), null), shot(Color(number * 3 % 256, 150, 150), null))
    }

    /** A video that holds [HOME] until the first tap, a splash until [splashEndMs], then the app; null where [gap] says it has nothing. */
    private class FakeVideo(
        private val splashStartMs: Long,
        private val splashEndMs: Long,
        private val gap: (Long) -> Boolean = { false },
    ) : RewriteVideoSheets {
        val asked = mutableListOf<List<Long>>()

        override fun sheet(startWallMs: Long, endWallMs: Long): RewriteVideoSheet? = null

        override fun frames(wallClockMs: List<Long>): List<RewriteVideoFrame?> {
            asked += wallClockMs
            return wallClockMs.map { time ->
                if (gap(time)) return@map null
                val color = when {
                    time < splashStartMs -> HOME
                    time < splashEndMs -> SPLASH
                    else -> APP
                }
                RewriteVideoFrame(
                    BufferedImage(90, 160, BufferedImage.TYPE_INT_RGB).also { image ->
                        val graphics = image.createGraphics()
                        graphics.color = color
                        graphics.fillRect(0, 0, 90, 160)
                        graphics.dispose()
                    },
                    time,
                    time - GAP_MS,
                )
            }
        }
    }

    private fun suite() = TestSuite("s", "Video suite", targetPackage = PACKAGE, cases = listOf(TestCase("c", "Open", description = "Open the app.")))

    private fun sheetPixel(bytes: ByteArray, cellIndex: Int): Color {
        val sheet = ImageIO.read(ByteArrayInputStream(bytes))
        // Three 480 px columns, the picture centred in an 800 px high box.
        val x = cellIndex % 3 * 480 + 240
        val y = cellIndex / 3 * 842 + 400
        return Color(sheet.getRGB(x, y))
    }

    private fun near(expected: Color, actual: Color) =
        kotlin.math.abs(expected.red - actual.red) + kotlin.math.abs(expected.green - actual.green) + kotlin.math.abs(expected.blue - actual.blue) < 60

    // ── From the video ───────────────────────────────────────────────

    @Test
    fun theEvidenceComesFromTheVideoEvenWhenEveryScreenshotWouldFitOneImageEach() {
        val inputs = tapsWithScreenshots(5)
        val video = FakeVideo(splashStartMs = 0, splashEndMs = 0)

        val evidence = buildRewriteEvidence(inputs, video)

        assertEquals(RewriteEvidenceKind.SHEETS, evidence.kind)
        assertEquals(RewriteEvidenceSource.VIDEO, evidence.source)
        assertEquals(2, evidence.images.size, "five inputs, three to a sheet, instead of ten screenshots")
        assertEquals((1..5).toList(), evidence.images.flatMap { it.inputs }, "every input exactly once, in order")
        assertTrue(evidence.images.all { it.videoFrames > 0 && it.storedScreens == 0 && "recorded video" in it.label })
        assertEquals(5, evidence.settledAfterInputs)
        assertEquals(5, evidence.consideredInputs)
        assertTrue(evidence.notAttached.isEmpty())
        assertTrue("Image 1 (contact sheet)" in evidence.lineExtra(2, true))
        assertTrue("Image 2 (contact sheet)" in evidence.lineExtra(4, true))
        assertTrue(evidence.images.all { ImageIO.read(ByteArrayInputStream(it.bytes)) != null })
    }

    @Test
    fun theBeforeFrameIsJustBeforeTheInputAndTheSettledFrameIsASettleWindowAfterItWhenTheNextInputIsFarAway() {
        val inputs = tapsWithScreenshots(3)
        val video = FakeVideo(splashStartMs = 0, splashEndMs = 0)

        buildRewriteEvidence(inputs, video)

        val first = inputs[0].row
        val last = inputs[2].row
        assertEquals(
            listOf(
                first.inputStartMs - REWRITE_VIDEO_BEFORE_MS, first.inputAtMs + REWRITE_SETTLE_MS,
                inputs[1].row.inputStartMs - REWRITE_VIDEO_BEFORE_MS, inputs[1].row.inputAtMs + REWRITE_SETTLE_MS,
                last.inputStartMs - REWRITE_VIDEO_BEFORE_MS, last.inputAtMs + REWRITE_SETTLE_MS,
            ),
            video.asked.single(),
        )
    }

    @Test
    fun eligibleStoredScreenshotsWinOverVideoAndOnlyRenderedVideoCellsAreCredited() {
        val first = tap(1, 10_000, shot(HOME, 9_700), shot(APP, 13_000))
        val second = tap(2, 20_000)

        val evidence = buildRewriteEvidence(listOf(first, second), FakeVideo(splashStartMs = 0, splashEndMs = 0))

        assertEquals(RewriteEvidenceKind.SHEETS, evidence.kind)
        val sheet = evidence.images.single()
        assertEquals(setOf(1, 2), sheet.inputs.toSet())
        assertEquals(2, sheet.storedScreens, "the first input's eligible before and settled-after screenshots are rendered")
        assertEquals(2, sheet.videoFrames, "only the second input contributes its before and after video frames")
        assertEquals(emptySet(), sheet.videoMoments[1], "available video is not credited when screenshot cells were rendered instead")
        assertEquals(setOf("before", "after"), sheet.videoMoments[2])
        assertTrue(near(HOME, sheetPixel(sheet.bytes, 0)))
        assertTrue(near(APP, sheetPixel(sheet.bytes, 1)))
    }

    @Test
    fun theSettledFrameIsTheLastOneBeforeTheNextInputWhenThatStartsSoonerThanTheSettleWindow() {
        val quick = (1..3).map { number -> tap(number, 10_000L + number * 700L, shot(HOME, null), shot(APP, null)) }
        val video = FakeVideo(splashStartMs = 0, splashEndMs = 0)

        buildRewriteEvidence(quick, video)

        val times = video.asked.single()
        assertEquals(quick[1].row.inputStartMs - REWRITE_NEXT_INPUT_GUARD_MS, times[1], "input 1 settles just before input 2 starts")
        assertEquals(quick[2].row.inputStartMs - REWRITE_NEXT_INPUT_GUARD_MS, times[3])
        assertEquals(quick[2].row.inputAtMs + REWRITE_SETTLE_MS, times[5], "the last input has no next one")
    }

    @Test
    fun aLoadingFrameRightAfterTheTapIsNotTheFrameShownAsTheResult() {
        // The app's splash shows from the tap until 1.5 s later; only then is the app on screen.
        val tapAt = 10_000L
        val inputs = listOf(tap(1, tapAt), tap(2, tapAt + GAP_MS))
        val video = FakeVideo(splashStartMs = tapAt + TAP_MS, splashEndMs = tapAt + TAP_MS + 1_500)

        val evidence = buildRewriteEvidence(inputs, video)

        val sheet = evidence.images.single().bytes
        assertTrue(near(HOME, sheetPixel(sheet, 0)), "input 1 before is the home screen")
        assertTrue(near(APP, sheetPixel(sheet, 1)), "input 1 after is the settled app, not the splash")
        assertFalse(near(SPLASH, sheetPixel(sheet, 1)))
        assertTrue(near(APP, sheetPixel(sheet, 2)), "input 2 before is the app")
        assertEquals(2, evidence.settledAfterInputs)
    }

    @Test
    fun theNoteInTheAttachedPromptExplainsTheSettledFrames() {
        val inputs = tapsWithScreenshots(2)

        val evidence = buildRewriteEvidence(inputs, FakeVideo(0, 0))
        val prompt = rewritePrompt(suite(), suite().cases.single(), "", inputs, evidence = evidence, attachImages = true)

        assertTrue("after (settled)" in prompt && "loading or splash screen right after the action is not the result" in prompt, prompt)
        val screenshotsOnly = buildRewriteEvidence(inputs)
        assertFalse("after (settled)" in rewritePrompt(suite(), suite().cases.single(), "", inputs, evidence = screenshotsOnly, attachImages = true))
    }

    // ── Screenshots fill only the gaps ───────────────────────────────

    @Test
    fun aMomentTheVideoCannotShowIsFilledFromTheStoredScreenshot() {
        val inputs = tapsWithScreenshots(3)
        val second = inputs[1].row
        val video = FakeVideo(0, 0, gap = { time -> time in (second.inputStartMs - 1_000)..(second.inputAtMs + 3_000) })

        val evidence = buildRewriteEvidence(inputs, video)

        assertEquals(RewriteEvidenceSource.MIXED, evidence.source)
        val image = evidence.images.single()
        assertTrue(image.videoFrames == 4 && image.storedScreens == 2, "${image.videoFrames} frames, ${image.storedScreens} screenshots")
        assertEquals(listOf(1, 2, 3), image.inputs)
        assertEquals(3, evidence.settledAfterInputs, "a stored screenshot of unknown age counts as settled")
    }

    @Test
    fun anInputThatNeitherTheVideoNorAScreenshotShowsGetsNoReferenceAndIsNotListedAsMissing() {
        val inputs = listOf(tapsWithScreenshots(3)[0], tap(2, 2 * GAP_MS), tap(3, 3 * GAP_MS))
        val video = FakeVideo(0, 0, gap = { time -> time in (2 * GAP_MS - 1_000)..(2 * GAP_MS + 3_000) })

        val evidence = buildRewriteEvidence(inputs, video)

        assertEquals(listOf(1, 3), evidence.images.single().inputs)
        assertEquals("", evidence.lineExtra(2, true))
        assertTrue(evidence.notAttached.isEmpty())
    }

    @Test
    fun aVideoThatCanShowNoFrameLeavesTheScreenshotEvidenceAsItWas() {
        val inputs = tapsWithScreenshots(3)

        val evidence = buildRewriteEvidence(inputs, FakeVideo(0, 0, gap = { true }))

        assertEquals(RewriteEvidenceKind.SCREENSHOTS, evidence.kind)
        assertEquals(RewriteEvidenceSource.SCREENSHOTS, evidence.source)
        assertEquals(6, evidence.images.size)
        assertEquals(RewriteEvidenceSource.SCREENSHOTS, buildRewriteEvidence(inputs, RewriteVideoSheets { _, _ -> null }).source)
    }

    @Test
    fun anInputWithoutAnyScreenshotStillGetsItsVideoFrames() {
        val inputs = listOf(tap(1, GAP_MS), tap(2, 2 * GAP_MS))

        val evidence = buildRewriteEvidence(inputs, FakeVideo(0, 0))

        assertEquals(RewriteEvidenceSource.VIDEO, evidence.source)
        assertEquals(listOf(1, 2), evidence.images.single().inputs)
    }

    @Test
    fun aRecordingBeyondTheSheetBudgetCoversEveryInputOnceAndNamesTheRest() {
        for (count in listOf(12, 30, 31, 60)) {
            val inputs = (1..count).map { tap(it, it * GAP_MS) }
            val video = FakeVideo(0, 0)

            val evidence = buildRewriteEvidence(inputs, video)

            assertTrue(evidence.images.size <= REWRITE_MAX_ATTACHED_IMAGES, "$count inputs")
            assertEquals((1..count).toList(), evidence.images.flatMap { it.inputs }.sorted(), "$count inputs, each on one sheet")
            assertTrue(evidence.notAttached.isEmpty(), "$count inputs")
            val perInput = if (count <= 30) 2 else 1
            assertEquals(count * perInput, video.asked.sumOf { it.size }, "$count inputs: before and settled, or the settled one only")
        }
        val tooMany = buildRewriteEvidence((1..90).map { tap(it, it * GAP_MS) }, FakeVideo(0, 0))
        assertEquals(REWRITE_MAX_ATTACHED_IMAGES, tooMany.images.size)
        assertEquals((1..90).toList(), (tooMany.images.flatMap { it.inputs } + tooMany.notAttached).sorted())
    }

    // ── What is never shown ──────────────────────────────────────────

    @Test
    fun aPasswordInputAndTheScreensAroundItAreNeverRequestedFromTheVideo() {
        val secret = shot(Color.MAGENTA, 12_000)
        val passwordScreen = RecordingScreenState(
            50, 11_900, 11_950, PACKAGE, ".Login",
            listOf(UiNode(0, 0, 100, 40, "Password", "", "", "android.widget.EditText", true, true, false, PACKAGE, true, true)), 1000, 2000,
        )
        val masked = tap(2, 12_000, secret, secret).let {
            RewriteInput(2, it.row.copy(action = "Enter text: ••••", kind = RecordedInputKind.TEXT), passwordScreen, passwordScreen)
        }
        val inputs = listOf(tap(1, 3_000), masked, tap(3, 60_000))
        val video = FakeVideo(0, 0)

        val evidence = buildRewriteEvidence(inputs, video)

        assertFalse(2 in evidence.images.flatMap { it.inputs })
        assertTrue("withheld" in evidence.lineExtra(2, true))
        assertTrue(video.asked.flatten().none { it in 11_000L..15_000L }, "nothing around the password input is asked of the video: ${video.asked}")
        assertTrue(evidence.images.none { image -> image.bytes.contentEquals(secret.jpeg) })
    }

    @Test
    fun aCautionAboutAnEarlyScreenshotIsDroppedWhenTheVideoShowsTheSettledScreen() {
        val inputs = listOf(tap(1, 1_000, shot(HOME, 900), shot(SPLASH, 1_100)), tap(2, 9_000))

        assertTrue("caution" in buildRewriteEvidence(inputs).lineExtra(1, true))
        assertFalse("caution" in buildRewriteEvidence(inputs, FakeVideo(0, 0)).lineExtra(1, true))
    }

    // ── Without a video ──────────────────────────────────────────────

    private fun withoutVideo(ownAfter: RecordingScreenshotEvidence?, nextBefore: RecordingScreenshotEvidence?) = listOf(
        tap(1, 1_000, shot(HOME, 900), ownAfter),
        tap(2, 9_000, nextBefore, shot(Color.GRAY, 12_000)),
    )

    @Test
    fun anAfterScreenshotTakenRightAfterTheInputYieldsToTheNextInputsBeforeScreenshot() {
        val splash = shot(SPLASH, 1_100)
        val settled = shot(APP, 8_000)
        val inputs = withoutVideo(splash, settled)

        val evidence = buildRewriteEvidence(inputs)

        assertEquals(RewriteEvidenceKind.SCREENSHOTS, evidence.kind)
        val labels = evidence.images.map { it.label }
        assertTrue("input 1 after, input 2 before" in labels, labels.toString())
        assertTrue(evidence.images.none { it.bytes.contentEquals(splash.jpeg) }, "the splash is not offered as the result")
        assertEquals(2, evidence.settledAfterInputs)
        assertFalse("caution" in evidence.lineExtra(1, true))
    }

    @Test
    fun aMissingAfterScreenshotIsReplacedByTheNextInputsBeforeScreenshot() {
        val inputs = withoutVideo(ownAfter = null, nextBefore = shot(APP, 8_000))

        val evidence = buildRewriteEvidence(inputs)

        assertTrue("input 1 after, input 2 before" in evidence.images.map { it.label })
        assertTrue("Image 2 (after)" in evidence.lineExtra(1, true), evidence.lineExtra(1, true))
    }

    @Test
    fun anAfterScreenshotTakenAfterTheSettleWindowIsKept() {
        val ownSettled = shot(APP, 1_000 + TAP_MS + REWRITE_SETTLE_MS + 100)
        val inputs = withoutVideo(ownSettled, shot(Color.DARK_GRAY, 8_000))

        val evidence = buildRewriteEvidence(inputs)

        assertTrue(evidence.images.any { it.bytes.contentEquals(ownSettled.jpeg) && it.label.startsWith("input 1 after") })
        assertEquals(2, evidence.settledAfterInputs)
    }

    @Test
    fun anOnlyEarlyAfterScreenshotIsKeptButCautionedAndNotCountedAsSettled() {
        val splash = shot(SPLASH, 1_100)
        val inputs = withoutVideo(splash, nextBefore = null)

        val evidence = buildRewriteEvidence(inputs)

        assertTrue(evidence.images.any { it.bytes.contentEquals(splash.jpeg) })
        val line = evidence.lineExtra(1, true)
        assertTrue("caution: the only look at the result was taken 60 ms after the input and may show a loading or transition screen" in line, line)
        assertEquals(1, evidence.settledAfterInputs, "only input 2 has a settled look")
    }

    @Test
    fun theScreenTextOfTheNextInputsBeforeReadingIsOfferedWhenTheAfterReadingWasTakenTooSoon() {
        val early = state(1_050, "Loading")
        val later = state(8_000, "Home", "Subscriptions")
        val first = tap(1, 1_000).let { RewriteInput(1, it.row, null, early) }
        val second = tap(2, 9_000).let { RewriteInput(2, it.row, later, null) }

        val extra = buildRewriteEvidence(listOf(first, second)).lineExtra(1, true)

        assertTrue("texts on the screen after: \"Loading\"" in extra, extra)
        assertTrue("texts on the screen when the next input began: \"Home\"; \"Subscriptions\"" in extra, extra)
    }

    @Test
    fun aNextInputInAPasswordWindowIsNotUsedAsTheResultOfTheInputBeforeIt() {
        val secret = shot(Color.MAGENTA, 5_000)
        val passwordScreen = RecordingScreenState(
            50, 4_900, 4_950, PACKAGE, ".Login",
            listOf(UiNode(0, 0, 100, 40, "Password", "", "", "android.widget.EditText", true, true, false, PACKAGE, true, true)), 1000, 2000,
        )
        val splash = shot(SPLASH, 1_100)
        val first = tap(1, 1_000, shot(HOME, 900), splash)
        val masked = RewriteInput(
            2, tap(2, 5_000, secret, secret).row.copy(action = "Enter text: ••••", kind = RecordedInputKind.TEXT), passwordScreen, passwordScreen,
        )

        val evidence = buildRewriteEvidence(listOf(first, masked))

        assertTrue(evidence.images.none { it.bytes.contentEquals(secret.jpeg) })
        assertNotNull(evidence.images.firstOrNull { it.bytes.contentEquals(splash.jpeg) }, "the input's own screenshot remains the only look")
    }
}
