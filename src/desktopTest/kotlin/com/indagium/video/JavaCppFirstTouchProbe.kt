package com.indagium.video

import com.indagium.capture.FfmpegAv1CompactVideoTranscoder
import com.indagium.capture.FfmpegCaptureVideoExporter
import com.indagium.capture.OpusPcmEncoder
import com.indagium.capture.StreamingMkvWriter
import com.indagium.capture.mirror.JavaCvH264Decoder
import com.indagium.capture.mirror.findOpusDecoder
import com.indagium.utils.trimJavaCppMemory
import org.bytedeco.javacv.FFmpegFrameFilter
import org.bytedeco.javacv.FFmpegFrameRecorder
import java.io.ByteArrayInputStream
import java.io.File
import java.util.concurrent.CyclicBarrier
import kotlin.random.Random
import kotlin.system.exitProcess

/**
 * Main class of the fresh JVM that [JavaCppFirstTouchDeadlockTest] starts. Every thread below is
 * the first to touch JavaCPP through a different production entry point, all released at the same
 * moment (plus a random delay of up to the second argument, in milliseconds), which is how a
 * capture preview, the video player, the mirror decoder and the heap trim can meet at startup. An
 * optional third argument limits the run to a comma-separated list of the touch names below. It exits 0 when every thread finishes, 2 when
 * one is stuck (the JavaCPP class-initialization deadlock), and never throws for a failed FFmpeg call: the inputs are junk
 * on purpose, only the first touch matters.
 */
object JavaCppFirstTouchProbe {
    private const val STUCK_EXIT_CODE = 2
    private const val THREAD_JOIN_MS = 45_000L
    private const val COVERAGE_END_MS = 1_000L

    @JvmStatic
    fun main(args: Array<String>) {
        val work = File(args[0]).apply { mkdirs() }
        val maxJitterMs = args.getOrNull(1)?.toLongOrNull() ?: 0L
        val only = args.getOrNull(2)?.takeIf { it.isNotBlank() }?.split(',')
        val junk = File(work, "junk.mkv").apply { writeBytes(ByteArray(JUNK_BYTES) { it.toByte() }) }
        val missing = File(work, "missing.mp4")

        val touches: Map<String, () -> Unit> = mapOf(
            "video-scan" to { scanPackets(missing.path) },
            "video-factory" to { awaitFfmpegNatives() },
            "export-coverage" to { FfmpegCaptureVideoExporter().coverageEndMs(junk, 0L, COVERAGE_END_MS) },
            "heap-trim" to { trimJavaCppMemory() },
            "opus-encoder" to { OpusPcmEncoder().close() },
            "opus-decoder" to { findOpusDecoder() },
            "av1-probe" to { FfmpegAv1CompactVideoTranscoder().isAlreadyAv1(junk) },
            "mkv-writer" to {
                StreamingMkvWriter(File(work, "out.mkv")).use { it.start(WIDTH, HEIGHT, ByteArray(EXTRADATA_BYTES)) }
            },
            "mirror-decoder" to { JavaCvH264Decoder().decode(ByteArrayInputStream(ByteArray(0))) { } },
            "javacv-recorder" to {
                awaitFfmpegNatives()
                FFmpegFrameRecorder(File(work, "recorder.mkv"), WIDTH, HEIGHT).release()
            },
            "javacv-filter" to {
                awaitFfmpegNatives()
                FFmpegFrameFilter("anull", 2).release()
            },
        )
        val chosen = touches.filterKeys { only == null || it in only }
        val start = CyclicBarrier(chosen.size)
        val threads = chosen.map { (name, touch) ->
            Thread({
                start.await()
                // Spreads the touches over the window in which the load thread is inside the
                // library loader, which is where the old code deadlocked.
                if (maxJitterMs > 0L) Thread.sleep(Random.nextLong(maxJitterMs))
                runCatching(touch)
            }, "probe-$name").apply { isDaemon = true }
        }
        threads.forEach(Thread::start)
        val deadline = System.currentTimeMillis() + THREAD_JOIN_MS
        for (thread in threads) {
            thread.join((deadline - System.currentTimeMillis()).coerceAtLeast(1L))
            if (thread.isAlive) {
                System.err.println("STUCK: ${thread.name}")
                exitProcess(STUCK_EXIT_CODE)
            }
        }
        exitProcess(0)
    }

    private const val WIDTH = 64
    private const val HEIGHT = 48
    private const val JUNK_BYTES = 4_096
    private const val EXTRADATA_BYTES = 8
}
