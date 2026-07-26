package com.openlog

import com.openlog.video.defaultVideoPlayerController
import com.openlog.video.scanDurationMs
import org.bytedeco.javacv.FFmpegFrameGrabber
import kotlin.io.path.createTempDirectory
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

// Matches VideoPlayerController's own MICROS_PER_MS: grabber.lengthInTime is always microseconds.
private const val MICROS_PER_MS = 1_000L

/**
 * Exercises `scanDurationMs` (video/VideoPlayerController.kt) — the packet-only duration-recovery
 * scan for containers whose header reports no length — against two committed binary fixtures under
 * `resources/video/`. Both were generated once via `ffmpeg -f lavfi -i testsrc=duration=5:...` (see
 * CLAUDE.md's task notes for the exact commands) and are never regenerated at test time: CI runners
 * have no ffmpeg, so this test only ever reads the committed bytes.
 *
 *  - `live-noduration.mkv`: muxed with `-f matroska -live 1`, whose header duration
 *    (`AVFormatContext.duration`, what `grabber.lengthInTime` reads) is 0 despite the file playing
 *    fine — a live-mode/streaming muxer never got to write the trailer that would carry a length.
 *    This is the exact shape of file that produced the reported bug (00:01 elapsed / 00:00
 *    duration, seek slider pinned to the far right): a screen recorder that streams its output, or
 *    is killed before finalizing, produces the same header.
 *  - `normal-duration.mkv`: the same test pattern muxed normally, whose header DOES report a
 *    duration — the control case proving the fixtures differ only in exactly the property under
 *    test, not in content or length.
 *
 * Deliberately tests the extracted pure function directly rather than the whole
 * `FfmpegVideoPlayerController` (which spawns a decode thread and a duration-scan thread) — see
 * `scanDurationMs`'s own KDoc for why it takes a bare path rather than being a method on that class.
 */
class VideoDurationScanTest {
    private fun fixture(name: String): String {
        val bytes = javaClass.getResourceAsStream("/video/$name")?.readBytes()
            ?: error("Missing fixture: src/desktopTest/resources/video/$name")
        return createTempDirectory("openlog-video-duration-scan").toFile().resolve(name).apply { writeBytes(bytes) }.absolutePath
    }

    private fun declaredDurationMs(path: String): Long = FFmpegFrameGrabber(path).use { grabber ->
        grabber.start()
        grabber.lengthInTime / MICROS_PER_MS
    }

    @Test
    fun liveModeFixtureHasNoHeaderDuration() {
        // Precondition the whole feature exists to fix: proves this fixture actually reproduces
        // what a real killed-mid-recording/streamed capture reports (a header duration of 0 despite
        // the file playing back fine) rather than accidentally exercising a well-formed file.
        assertEquals(0L, declaredDurationMs(fixture("live-noduration.mkv")))
    }

    @Test
    fun recoversApproximateDurationForALiveModeFixtureViaPacketScan() {
        // Tolerance matches the technique's own verified measurement (4999000us on an identically
        // produced fixture) rather than asserting an exact figure a re-encode could shift by a frame.
        val recoveredMs = scanDurationMs(fixture("live-noduration.mkv"))
        assertTrue(recoveredMs in 4_900..5_100, "expected roughly 5000ms, got $recoveredMs")
    }

    @Test
    fun normalFixtureReportsItsDurationDirectlyWithoutNeedingAScan() {
        // openGrabber only ever calls scanDurationMs when the declared duration is <= 0 (see
        // maybeStartDurationRecoveryScan's early return) — this fixture's positive header duration
        // is exactly what keeps the real playback path from paying for a scan on an ordinary file.
        val declaredMs = declaredDurationMs(fixture("normal-duration.mkv"))
        assertTrue(declaredMs in 4_900..5_100, "expected the container's own header duration, got $declaredMs")
    }

    @Test
    fun scanIsCancellableAndReportsStillUnknownWhenCancelledBeforeAnyPacket() {
        // Mirrors what FfmpegVideoPlayerController.maybeStartDurationRecoveryScan passes as
        // isCancelled: `{ closed }`. Cancelled up front, the scan must report "still unknown" (0),
        // never a wrong/partial value, and must not have opened anything left dangling.
        val recoveredMs = scanDurationMs(fixture("live-noduration.mkv")) { true }
        assertEquals(0L, recoveredMs)
    }

    @Test
    fun realControllerPublishesARecoveredDurationForADurationlessFile() {
        // The end-to-end regression guard for the reported bug. Every other test here exercises
        // scanDurationMs directly, which would still pass if openGrabber simply never called it —
        // the wiring is precisely where the bug lived, so this drives the real controller the app
        // builds (defaultVideoPlayerController, what AppState.videoControllerFactory defaults to)
        // and waits for the background recovery thread to publish. A durationMs still 0 here is
        // exactly the "00:00 duration, slider pinned right" state the user reported.
        val controller = defaultVideoPlayerController(fixture("live-noduration.mkv"))
        try {
            val deadline = System.currentTimeMillis() + 15_000
            while (controller.durationMs <= 0L && controller.error == null && System.currentTimeMillis() < deadline) {
                Thread.sleep(50)
            }
            assertEquals(null, controller.error, "the fixture must open cleanly; only its length is unknown")
            assertTrue(
                controller.durationMs in 4_900..5_100,
                "controller should publish the scan-recovered duration, got ${controller.durationMs}",
            )
        } finally {
            controller.close()
        }
    }

    @Test
    fun missingFileFailsRatherThanHanging() {
        // The controller wraps this call in runCatching and logs the failure (see
        // maybeStartDurationRecoveryScan) — that only works if a bad path actually throws.
        assertTrue(
            runCatching { scanDurationMs("/nonexistent/openlog-duration-scan-fixture.mkv") }.isFailure,
        )
    }
}
