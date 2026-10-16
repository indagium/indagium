@file:Suppress("MagicNumber")

package com.indagium.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.click
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.performTouchInput
import com.indagium.capture.CaptureAvailabilityInterval
import com.indagium.capture.CaptureMappingRow
import com.indagium.capture.CaptureTimeline
import com.indagium.model.LogEntry
import com.indagium.model.LogLevel
import com.indagium.model.LogTab
import com.indagium.model.VideoAttachment
import com.indagium.model.VideoSource
import org.bytedeco.ffmpeg.global.avcodec.AV_CODEC_ID_MPEG4
import org.bytedeco.ffmpeg.global.avutil.AV_PIX_FMT_BGR24
import org.bytedeco.javacv.FFmpegFrameGrabber
import org.bytedeco.javacv.FFmpegFrameRecorder
import org.junit.Rule
import org.junit.Test
import java.awt.image.BufferedImage
import java.io.File
import java.nio.ByteBuffer
import java.nio.file.Files
import javax.imageio.ImageIO
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

@OptIn(ExperimentalTestApi::class)
class CaptureRawVideoSeekUiTest {
    @get:Rule
    val rule = createComposeRule()

    @Test
    fun pausedSeekInsideRawCaptureGapKeepsPlaceholderUntilPlaybackReachesReconnect() {
        val directory = Files.createTempDirectory("capture-raw-video-seek-ui").toFile()
        val media = File(directory, "rawscreen.mkv")
        createSparseVideo(media)
        assertSparseFixture(media)

        val paths = AppStateTestPaths(directory)
        val logRows = listOf(
            LogEntry(1, "10:00:00.000", LogLevel.I, "App", "recording starts"),
            LogEntry(2, "10:00:05.000", LogLevel.I, "App", "recording resumes"),
        )
        val timeline = CaptureTimeline(
            rows = listOf(
                CaptureMappingRow(1, elapsedMs = 0L, videoMs = 0L),
                CaptureMappingRow(2, elapsedMs = 5_000L, videoMs = 5_000L),
            ),
            availabilityIntervals = listOf(
                CaptureAvailabilityInterval(1_000L, 4_000L, rebootConfirmed = true),
            ),
            timelineBaseElapsedMs = 0L,
            timelineDurationMs = 8_000L,
            videoOriginElapsedMs = 0L,
            availabilityFramesRendered = false,
            showUnavailableFrames = true,
        )
        val tab = LogTab(
            id = "raw-capture",
            filename = "capture.log",
            logData = logRows,
            rmap = logRows.associateBy(LogEntry::id),
            attachedVideo = VideoAttachment(
                source = VideoSource.LocalFile(media.absolutePath),
                sourceLabel = media.name,
                durationMs = 8_000L,
            ),
            captureTimeline = timeline,
        )
        val state = AppState(
            autosaveFile = paths.autosave,
            notesDir = paths.notes,
            archiveCacheDir = paths.archiveCache,
            customCommandsDir = paths.customCommands,
            filterBackupsDir = paths.filterBackups,
            platformDefaultSaveRootDir = paths.saveRoot,
            autoExportNotes = false,
            controlTokenFile = paths.controlToken,
            sourceIndexFile = paths.sourceIndex,
            diagramLibraryStore = DiagramLibraryStore(paths.diagramLibrary),
            testingDir = paths.testing,
        ).also { it.tabs = listOf(tab) }
        val controller = assertNotNull(state.videoController(tab.id))

        try {
            rule.setContent {
                CompositionLocalProvider(LocalTheme provides LIGHT_THEME) {
                    Box(Modifier.fillMaxSize().testTag("raw-video-seek-test-panel")) {
                        BoundVideoPanel(state, tab, Modifier.fillMaxSize())
                    }
                }
            }

            rule.waitUntil(15_000) {
                controller.error != null || (controller.durationMs >= 8_000L && controller.currentFrame != null)
            }
            assertEquals(null, controller.error)
            assertFalse(controller.isPlaying)
            assertNotNull(controller.currentFrame)
            val firstImage = centerPixel(controller.currentFrame!!)
            assertTrue(rule.onAllNodesWithText("Device unavailable").fetchSemanticsNodes().isEmpty())

            seekSliderTo(2_000L)
            rule.waitUntil(5_000) {
                controller.positionMs in 1_900L..2_100L &&
                    rule.onAllNodesWithText("Device unavailable").fetchSemanticsNodes().isNotEmpty()
            }
            rule.onNodeWithText("Device unavailable").assertIsDisplayed()
            assertFalse(controller.isPlaying, "seeking must keep the raw player paused")
            saveEvidence("paused-gap.png", rule.onNodeWithTag("raw-video-seek-test-panel").captureToImage())

            // Let multiple decode-loop iterations run while paused. A seek into the unavailable
            // window must not publish the first frame after it as the displayed raw image.
            val stableUntil = System.currentTimeMillis() + 600L
            rule.waitUntil(2_000) { System.currentTimeMillis() >= stableUntil }
            assertTrue(controller.positionMs in 1_900L..2_100L, "paused playhead moved to ${controller.positionMs}ms")
            assertFalse(controller.isPlaying)
            assertEquals(firstImage, centerPixel(controller.currentFrame!!), "the future reconnect frame appeared during the gap")
            rule.onNodeWithText("Device unavailable").assertIsDisplayed()

            rule.onNodeWithContentDescription("Play").assertIsDisplayed()
            rule.onNodeWithContentDescription("Play").performTouchInput { click() }
            rule.waitUntil(3_000) { controller.positionMs > 2_100L }
            rule.waitUntil(8_000) { controller.positionMs >= 4_100L }
            rule.waitUntil(3_000) {
                rule.onAllNodesWithText("Device unavailable").fetchSemanticsNodes().isEmpty()
            }
            assertTrue(controller.positionMs >= 4_000L, "playback should clear the outage card after reconnect")
            assertTrue(rule.onAllNodesWithText("Device unavailable").fetchSemanticsNodes().isEmpty())
            val reconnectImage = assertNotNull(controller.currentFrame)
            assertTrue(centerPixel(reconnectImage) != firstImage, "the reconnect frame should differ from the pre-outage image")
            saveEvidence("reconnected-frame.png", rule.onNodeWithTag("raw-video-seek-test-panel").captureToImage())

            if (controller.isPlaying) {
                rule.onNodeWithContentDescription("Pause").performClick()
                rule.waitUntil(2_000) { !controller.isPlaying }
            }
            seekSliderTo(2_000L)
            rule.waitUntil(5_000) {
                controller.positionMs in 1_900L..2_100L &&
                    rule.onAllNodesWithText("Device unavailable").fetchSemanticsNodes().isNotEmpty()
            }
            rule.onNodeWithText("Device unavailable").assertIsDisplayed()
            assertFalse(controller.isPlaying)
            saveEvidence("backward-seek-gap.png", rule.onNodeWithTag("raw-video-seek-test-panel").captureToImage())
        } finally {
            state.close()
            directory.deleteRecursively()
        }
    }

    private fun seekSliderTo(positionMs: Long) {
        var accepted = false
        rule.onNode(SemanticsMatcher.keyIsDefined(SemanticsProperties.ProgressBarRangeInfo))
            .performSemanticsAction(SemanticsActions.SetProgress) { setProgress ->
                accepted = setProgress(positionMs.toFloat())
            }
        assertTrue(accepted, "video seek slider rejected $positionMs ms")
    }

    private fun createSparseVideo(output: File) {
        val width = 64
        val height = 48
        val pixels = ByteBuffer.allocate(width * height * 3)
        val recorder = FFmpegFrameRecorder(output, width, height).apply {
            format = "matroska"
            frameRate = 10.0
            videoCodec = AV_CODEC_ID_MPEG4
            videoBitrate = 300_000
            gopSize = 1
        }
        try {
            recorder.start()
            repeat(10) { frameIndex ->
                recordSolidFrame(recorder, pixels, width, height, frameIndex * 100_000L, 20, 70, 225)
            }
            recordSolidFrame(recorder, pixels, width, height, 4_000_000L, 240, 30, 15)
            // Keep a few seconds of post-reconnect footage so the UI test can interact with Play
            // without racing the end of a very short sparse clip.
            recordSolidFrame(recorder, pixels, width, height, 7_900_000L, 240, 30, 15)
            recorder.stop()
        } finally {
            runCatching { recorder.release() }
        }
        assertTrue(output.isFile && output.length() > 0L, "failed to create sparse MKV fixture")
    }

    private fun recordSolidFrame(
        recorder: FFmpegFrameRecorder,
        pixels: ByteBuffer,
        width: Int,
        height: Int,
        timestampUs: Long,
        red: Int,
        green: Int,
        blue: Int,
    ) {
        pixels.clear()
        repeat(width * height) {
            pixels.put(blue.toByte()).put(green.toByte()).put(red.toByte())
        }
        pixels.flip()
        recorder.timestamp = timestampUs
        recorder.recordImage(width, height, 8, 3, width * 3, AV_PIX_FMT_BGR24, pixels)
    }

    private fun assertSparseFixture(file: File) {
        FFmpegFrameGrabber(file).use { grabber ->
            grabber.start()
            val timestamps = buildList {
                while (true) {
                    if (grabber.grabImage() == null) break
                    add(grabber.timestamp)
                }
            }
            assertTrue(timestamps.size >= 2, "fixture needs frames on both sides of the gap")
            assertTrue(timestamps.first() <= 100_000L)
            assertTrue(timestamps.last() >= 7_800_000L, "fixture must include post-reconnect playback time: $timestamps")
            assertTrue(timestamps.none { it in 1_000_000L..3_900_000L }, "fixture unexpectedly contains a gap frame: $timestamps")
        }
    }

    private fun centerPixel(image: ImageBitmap): Int =
        image.toPixelMap()[image.width / 2, image.height / 2].toArgb()

    private fun saveEvidence(name: String, image: ImageBitmap) {
        val directory = File("build/verification/playback-before-save").apply { mkdirs() }
        val pixels = image.toPixelMap()
        val rendered = BufferedImage(image.width, image.height, BufferedImage.TYPE_INT_ARGB)
        for (y in 0 until image.height) {
            for (x in 0 until image.width) rendered.setRGB(x, y, pixels[x, y].toArgb())
        }
        check(ImageIO.write(rendered, "png", File(directory, name))) { "No PNG writer is available for video UI evidence" }
    }

    private data class AppStateTestPaths(val root: File) {
        val autosave = File(root, "app/autosave.cache")
        val notes = File(root, "notes")
        val archiveCache = File(root, "archive-cache")
        val customCommands = File(root, "commands")
        val filterBackups = File(root, "filter-backups")
        val saveRoot = File(root, "save-root")
        val controlToken = File(root, "control.token")
        val sourceIndex = File(root, "source-index")
        val diagramLibrary = File(root, "diagram-library.cache")
        val testing = File(root, "testing")

        init {
            listOf(autosave.parentFile, notes, archiveCache, customCommands, filterBackups, saveRoot, testing)
                .forEach { it.mkdirs() }
        }
    }
}
