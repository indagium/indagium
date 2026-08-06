package com.indagium

import com.indagium.ui.videoSeekReadinessMessage
import com.indagium.video.DurationRecoverySource
import com.indagium.video.PacketScanResult
import com.indagium.video.VideoSeekReadiness
import com.indagium.video.VideoSeekState
import com.indagium.video.advanceVideoSeekState
import com.indagium.video.defaultVideoPlayerController
import com.indagium.video.growDurationIfNeeded
import com.indagium.video.recoverDurationMs
import com.indagium.video.resolveScannedDurationMs
import com.indagium.video.scanDecodedTimestampDurationMs
import com.indagium.video.scanDurationMs
import com.indagium.video.scanPackets
import org.bytedeco.javacv.FFmpegFrameGrabber
import kotlin.io.path.createTempDirectory
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

// Matches VideoPlayerController's own MICROS_PER_MS: grabber.lengthInTime is always microseconds.
private const val MICROS_PER_MS = 1_000L

/**
 * Exercises the duration-recovery chain in video/VideoPlayerController.kt — `scanDurationMs` and
 * the `scanPackets`/`resolveScannedDurationMs`/`growDurationIfNeeded` pieces it's built from — for
 * containers whose header reports no length, against three committed binary fixtures under
 * `resources/video/`. All three were generated once (see CLAUDE.md's task notes / each fixture's
 * own KDoc below for the exact commands) and are never regenerated at test time: CI runners have no
 * ffmpeg, so this test only ever reads the committed bytes.
 *
 *  - `live-noduration.mkv`: muxed with `-f matroska -live 1`, whose header duration
 *    (`AVFormatContext.duration`, what `grabber.lengthInTime` reads) is 0 despite the file playing
 *    fine — a live-mode/streaming muxer never got to write the trailer that would carry a length.
 *    This file DOES carry real packet timestamps, so it resolves via step 2 (the exact
 *    packet-timestamp scan) of the recovery chain.
 *  - `normal-duration.mkv`: the same test pattern muxed normally, whose header DOES report a
 *    duration — the control case proving the fixtures differ only in exactly the property under
 *    test, not in content or length.
 *  - `raw-h264-no-timestamps.h264`: a RAW elementary H.264 stream with no container at all
 *    (`ffmpeg -f lavfi -i testsrc=duration=5:size=320x240:rate=15 -c:v libx264 raw-stream.h264`),
 *    the second, previously-unhandled shape of durationless file this task adds coverage for.
 *    Android tooling sometimes writes exactly this (occasionally even named `.mp4`). FFmpeg
 *    content-probes and plays it happily, but every one of its packets carries neither pts nor
 *    dts — step 2 finds nothing here, so this fixture is what exercises step 3, the
 *    packet-count/frame-rate fallback.
 *
 * Deliberately tests the extracted pure functions directly rather than the whole
 * `FfmpegVideoPlayerController` (which spawns a decode thread and a duration-scan thread) — see
 * `scanPackets`'s own KDoc for why it takes a bare path rather than being a method on that class.
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
    fun realControllerPublishesItsDeclaredDurationFromTheDecodeThread() {
        // Archive-backed playback and an already-restored attachment both create the controller
        // before the UI has completed its first composition. The decoder still publishes a normal
        // container's declared duration from its own thread, so this must become visible as the
        // fixed header duration — never stay at zero and later turn into the current play position.
        val controller = defaultVideoPlayerController(fixture("normal-duration.mkv"))
        try {
            controller.start()
            val deadline = System.currentTimeMillis() + 15_000
            while (controller.durationMs <= 0L && controller.error == null && System.currentTimeMillis() < deadline) {
                Thread.sleep(50)
            }
            assertEquals(null, controller.error, "the fixture must open cleanly")
            assertTrue(
                controller.durationMs in 4_900..5_100,
                "controller should retain its declared duration, got ${controller.durationMs}",
            )
        } finally {
            controller.close()
        }
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
            controller.start()
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

    @Test
    fun rawStreamFixtureHasNeitherHeaderDurationNorPacketTimestamps() {
        // Precondition the packet-count/frame-rate fallback (step 3) exists to fix: proves this
        // fixture actually reproduces a raw elementary stream's shape — no container duration AND
        // no packet ever carries a resolvable pts/dts — rather than accidentally exercising
        // something step 2 (the exact packet-timestamp scan) could already have handled. Without
        // this assertion, a change that broke the raw-stream fixture generation could silently turn
        // rawStreamFixtureResolvesViaPacketCountFallback below into a step-2 test instead.
        val path = fixture("raw-h264-no-timestamps.h264")
        assertEquals(0L, declaredDurationMs(path))
        val scanned = scanPackets(path)
        assertEquals(0L, scanned.maxEndUs, "expected no packet to carry a resolvable pts/dts")
        assertTrue(scanned.videoPacketCount > 0L, "expected the scan to have walked real video packets")
    }

    @Test
    fun rawStreamFixtureResolvesViaPacketCountFallback() {
        // 75 packets at FFmpeg's guessed 25fps for this fixture works out to 3000ms — see this
        // task's own measurements. Asserted as a floor + rough ceiling rather than an exact
        // millisecond figure since the fallback is an approximation by construction (the frame rate
        // itself is guessed, not declared) — the point of this test is "step 3 produces a real,
        // usable, roughly-right duration", not that it reproduces FFmpeg's guess to the millisecond.
        val recoveredMs = scanDurationMs(fixture("raw-h264-no-timestamps.h264"))
        assertTrue(recoveredMs in 2_000..4_000, "expected roughly 3000ms via the packet-count fallback, got $recoveredMs")
    }

    @Test
    fun decodedTimestampFallbackCanMeasureAHeaderlessStream() {
        // Production prefers the cheap packet metadata recovery, but this confirms the final
        // fallback independently measures the decoded-frame timeline for a headerless stream.
        val recoveredMs = scanDecodedTimestampDurationMs(fixture("live-noduration.mkv"))
        assertTrue(recoveredMs in 4_500..5_100, "expected roughly 5000ms via decoded timestamps, got $recoveredMs")
    }

    @Test
    fun realControllerPublishesAFallbackDurationForARawElementaryStream() {
        // The raw-stream equivalent of realControllerPublishesARecoveredDurationForADurationlessFile
        // above: the user's reported bug had TWO distinct root causes (a live-mode container with
        // real timestamps, and a raw elementary stream with none at all), and this is the one that
        // was still broken before this task — scanDurationMs found nothing via timestamps and
        // openGrabber had nothing else to fall back to. Drives the real controller end to end so the
        // wiring (openGrabber -> maybeStartDurationRecoveryScan -> scanDurationMs's step 3) is what's
        // under test, not just the pure function.
        val controller = defaultVideoPlayerController(fixture("raw-h264-no-timestamps.h264"))
        try {
            controller.start()
            val deadline = System.currentTimeMillis() + 15_000
            while (controller.durationMs <= 0L && controller.error == null && System.currentTimeMillis() < deadline) {
                Thread.sleep(50)
            }
            assertEquals(null, controller.error, "the fixture must open cleanly; only its length is unknown")
            assertTrue(
                controller.durationMs > 0L,
                "controller should publish a fallback duration via the packet-count/frame-rate step, got ${controller.durationMs}",
            )
        } finally {
            controller.close()
        }
    }

    @Test
    fun resolveScannedDurationPrefersTheExactPacketTimestampTotalWhenPresent() {
        // Step 2 wins outright over step 3 whenever it has anything to offer — even a videoPacketCount
        // of 0 (which a real scan wouldn't produce alongside a positive maxEndUs, but this is testing
        // the pure decision function in isolation) must not affect the result.
        assertEquals(5_000L, resolveScannedDurationMs(PacketScanResult(maxEndUs = 5_000_000L, videoPacketCount = 0L, videoFrameRate = 0.0)))
    }

    @Test
    fun resolveScannedDurationFallsBackToPacketCountOverFrameRateWhenNoTimestampsExist() {
        assertEquals(
            3_000L,
            resolveScannedDurationMs(PacketScanResult(maxEndUs = 0L, videoPacketCount = 75L, videoFrameRate = 25.0)),
        )
    }

    @Test
    fun resolveScannedDurationGuardsAgainstAnUnusableFrameRate() {
        // A raw stream FFmpeg couldn't even guess a frame rate for (0, negative, NaN, or Infinity —
        // e.g. a malformed or single-packet stream) must resolve to "still unknown" (0), not a
        // divide-by-zero garbage value or a crash.
        assertEquals(0L, resolveScannedDurationMs(PacketScanResult(maxEndUs = 0L, videoPacketCount = 75L, videoFrameRate = 0.0)))
        assertEquals(0L, resolveScannedDurationMs(PacketScanResult(maxEndUs = 0L, videoPacketCount = 75L, videoFrameRate = -1.0)))
        assertEquals(0L, resolveScannedDurationMs(PacketScanResult(maxEndUs = 0L, videoPacketCount = 75L, videoFrameRate = Double.NaN)))
        assertEquals(
            0L,
            resolveScannedDurationMs(PacketScanResult(maxEndUs = 0L, videoPacketCount = 75L, videoFrameRate = Double.POSITIVE_INFINITY)),
        )
        // And a frame rate that IS usable but with no packets counted (an empty/audio-only scan)
        // must not fabricate a duration out of nothing either.
        assertEquals(0L, resolveScannedDurationMs(PacketScanResult(maxEndUs = 0L, videoPacketCount = 0L, videoFrameRate = 25.0)))
    }

    @Test
    fun growDurationLeavesAnAlreadySufficientDurationUnchanged() {
        assertEquals(5_000L, growDurationIfNeeded(currentDurationMs = 5_000L, positionMs = 3_000L))
    }

    @Test
    fun growDurationRaisesAnUndershotDurationToTheObservedPosition() {
        // This is the exact scenario the user's report demands never regress: a 3000ms estimate
        // (step 3's guessed frame rate) must not re-pin the slider thumb at the far right once
        // playback actually runs past it on a genuinely-longer stream.
        assertEquals(5_000L, growDurationIfNeeded(currentDurationMs = 3_000L, positionMs = 5_000L))
    }

    @Test
    fun growDurationMakesAWhollyUnknownDurationUsableTheMomentPlaybackAdvances() {
        // Covers "even if every step above yields 0, the timeline becomes usable the moment the
        // video plays" — no recovery step needs to have found anything for the transport bar to
        // stop being stuck at "--:--" once real playback positions start arriving.
        assertEquals(1_500L, growDurationIfNeeded(currentDurationMs = 0L, positionMs = 1_500L))
    }

    @Test
    fun metadataDurationSkipsTheExpensiveDecodedTimestampFallback() {
        var decodedScanCalls = 0

        val result = recoverDurationMs(
            scanPacketMetadata = { 5_000L },
            scanDecodedTimestamps = { decodedScanCalls++; 4_900L },
        )

        assertEquals(DurationRecoverySource.PACKET_METADATA, result.source)
        assertEquals(5_000L, result.durationMs)
        assertEquals(0, decodedScanCalls, "decoded timestamp fallback must only run after metadata recovery fails")
    }

    @Test
    fun decodedTimestampFallbackMakesSeekingReadyWhenMetadataHasNoDuration() {
        val result = recoverDurationMs(
            scanPacketMetadata = { 0L },
            scanDecodedTimestamps = { 4_900L },
        )

        assertEquals(DurationRecoverySource.DECODE_TIMESTAMPS, result.source)
        assertEquals(4_900L, result.durationMs)
        val state = advanceVideoSeekState(VideoSeekState(), observedDurationMs = result.durationMs, discoveryFinished = true)
        assertEquals(VideoSeekReadiness.READY, state.readiness)
    }

    @Test
    fun cancellationAfterMetadataScanSuppressesDecodedFallbackAndCompletion() {
        var cancelled = false
        var decodedScanCalls = 0

        val result = recoverDurationMs(
            scanPacketMetadata = { cancelled = true; 0L },
            scanDecodedTimestamps = { decodedScanCalls++; 4_900L },
            isCancelled = { cancelled },
        )

        assertEquals(DurationRecoverySource.CANCELLED, result.source)
        assertEquals(0, decodedScanCalls)
        assertEquals(
            VideoSeekState(),
            advanceVideoSeekState(VideoSeekState(), observedDurationMs = result.durationMs, discoveryFinished = false),
            "a cancelled scan must not turn discovering into permanently unavailable",
        )
    }

    @Test
    fun seekReadinessAndDurationNeverRegressWhenRecoveryCompletesAfterPlayback() {
        val afterPlayback = advanceVideoSeekState(VideoSeekState(), observedDurationMs = 6_000L)
        val staleRecovery = advanceVideoSeekState(afterPlayback, observedDurationMs = 5_000L, discoveryFinished = true)

        assertEquals(VideoSeekReadiness.READY, staleRecovery.readiness)
        assertEquals(6_000L, staleRecovery.durationMs)
    }

    @Test
    fun transportExplainsDiscoveringAndUnavailableAsDifferentStates() {
        assertTrue(videoSeekReadinessMessage(VideoSeekReadiness.DISCOVERING)?.contains("Preparing timeline") == true)
        assertTrue(videoSeekReadinessMessage(VideoSeekReadiness.UNAVAILABLE)?.contains("Timeline unavailable") == true)
        assertEquals(null, videoSeekReadinessMessage(VideoSeekReadiness.READY))
    }
}
