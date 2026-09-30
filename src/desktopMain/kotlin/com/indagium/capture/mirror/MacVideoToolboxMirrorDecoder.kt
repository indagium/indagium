@file:Suppress("TooGenericExceptionCaught")

package com.indagium.capture.mirror

import com.indagium.ui.EmbeddedMirrorMacSurface
import java.io.Closeable

/** Dimensions and presentation timestamp reported by the direct macOS presentation path. */
internal data class MirrorFrameInfo(
    val width: Int,
    val height: Int,
    val presentationTimeUs: Long = 0,
) {
    init { require(width > 0 && height > 0) { "frame dimensions must be positive" } }
}

/** Decoder contract for a renderer which owns decoded-pixel lifetime outside the JVM. */
internal fun interface DirectH264Decoder : Closeable {
    fun decode(input: BoundedScrcpyPacketFeed, onFrame: (MirrorFrameInfo) -> Unit)

    override fun close() = Unit
}

/**
 * Feeds complete scrcpy Annex-B access units directly to the bundled VideoToolbox/Metal bridge.
 * It renders the newest decoded CVPixelBuffer itself; Kotlin receives only inexpensive dimensions
 * for input mapping and Compose layout.
 */
internal class MacVideoToolboxMirrorDecoder(
    private val surface: EmbeddedMirrorMacSurface,
) : DirectH264Decoder {
    @Volatile private var closed = false

    override fun decode(input: BoundedScrcpyPacketFeed, onFrame: (MirrorFrameInfo) -> Unit) {
        var lastReportNs = System.nanoTime()
        val queueWaitSamples = ArrayList<Long>(MAX_REPORT_SAMPLES)
        val submitSamples = ArrayList<Long>(MAX_REPORT_SAMPLES)
        var lastWidth = 0
        var lastHeight = 0
        while (!closed) {
            val packet = input.nextPacket() ?: return
            val decodeStartedNs = System.nanoTime()
            val queueWaitNs = (decodeStartedNs - packet.enqueuedNs).coerceAtLeast(0)
            val dimensions = surface.decode(
                packet.data,
                packet.data.size,
                packet.ptsUs,
                packet.config,
                packet.keyFrame,
                queueWaitNs,
            )
            val submitDurationNs = (System.nanoTime() - decodeStartedNs).coerceAtLeast(0)
            queueWaitSamples.add(queueWaitNs)
            submitSamples.add(submitDurationNs)

            if (dimensions != null && (dimensions.first != lastWidth || dimensions.second != lastHeight)) {
                lastWidth = dimensions.first
                lastHeight = dimensions.second
                onFrame(MirrorFrameInfo(dimensions.first, dimensions.second, packet.ptsUs))
            }
            val reportNowNs = System.nanoTime()
            if (reportNowNs - lastReportNs >= REPORT_INTERVAL_NS) {
                val queueMetrics = input.diagnostics()
                val nativeMetrics = surface.takeNativeMetrics()
                val decodeCount = nativeMetrics.getOrElse(MetricIndex.DECODE_COUNT) { 0 }
                val decodeTotalNs = nativeMetrics.getOrElse(MetricIndex.DECODE_TOTAL_NS) { 0 }
                val decodeMaxNs = nativeMetrics.getOrElse(MetricIndex.DECODE_MAX_NS) { 0 }
                val presentCount = nativeMetrics.getOrElse(MetricIndex.PRESENT_COUNT) { 0 }
                val presentTotalNs = nativeMetrics.getOrElse(MetricIndex.PRESENT_TOTAL_NS) { 0 }
                val presentMaxNs = nativeMetrics.getOrElse(MetricIndex.PRESENT_MAX_NS) { 0 }
                val renderErrors = nativeMetrics.getOrElse(MetricIndex.RENDER_ERRORS) { 0 }
                val presentP95Ns = nativeMetrics.getOrElse(MetricIndex.PRESENT_P95_NS) { 0 }
                val decodedImages = nativeMetrics.getOrElse(MetricIndex.DECODED_IMAGE_COUNT) { 0 }
                val attachAttempts = nativeMetrics.getOrElse(MetricIndex.ATTACH_ATTEMPTS) { 0 }
                val attachSuccesses = nativeMetrics.getOrElse(MetricIndex.ATTACH_SUCCESSES) { 0 }
                val attachFailures = nativeMetrics.getOrElse(MetricIndex.ATTACH_FAILURES) { 0 }
                val drawableMisses = nativeMetrics.getOrElse(MetricIndex.DRAWABLE_MISSES) { 0 }
                val attachResult = nativeMetrics.getOrElse(MetricIndex.LAST_ATTACH_RESULT) { 0 }
                val attachWidth = nativeMetrics.getOrElse(MetricIndex.LAST_ATTACH_WIDTH) { 0 }
                val attachHeight = nativeMetrics.getOrElse(MetricIndex.LAST_ATTACH_HEIGHT) { 0 }
                val decodedPixelFormat = nativeMetrics.getOrElse(MetricIndex.DECODED_PIXEL_FORMAT) { 0 }
                val decodedSampleMin = nativeMetrics.getOrElse(MetricIndex.DECODED_SAMPLE_MIN) { -1 }
                val decodedSampleMax = nativeMetrics.getOrElse(MetricIndex.DECODED_SAMPLE_MAX) { -1 }
                val hasLayerSuperlayer = nativeMetrics.getOrElse(MetricIndex.LAST_LAYER_HAS_SUPERLAYER) { 0 }
                val hasWindowLayer = nativeMetrics.getOrElse(MetricIndex.LAST_WINDOW_LAYER_AVAILABLE) { 0 }
                val layerHidden = nativeMetrics.getOrElse(MetricIndex.LAST_LAYER_HIDDEN) { 0 }
                val layerOpacity = nativeMetrics.getOrElse(MetricIndex.LAST_LAYER_OPACITY_MILLI) { 0 }
                val testPatternEnabled = nativeMetrics.getOrElse(MetricIndex.TEST_PATTERN) { 0 }
                val layerDescendsFromWindow = nativeMetrics.getOrElse(MetricIndex.LAST_LAYER_DESCENDS_FROM_WINDOW) { 0 }
                val parentHidden = nativeMetrics.getOrElse(MetricIndex.LAST_PARENT_HIDDEN) { 0 }
                val parentOpacity = nativeMetrics.getOrElse(MetricIndex.LAST_PARENT_OPACITY_MILLI) { 0 }
                val layerZ = nativeMetrics.getOrElse(MetricIndex.LAST_LAYER_Z_MILLI) { 0 }
                val layerSiblingIndex = nativeMetrics.getOrElse(MetricIndex.LAST_LAYER_SIBLING_INDEX) { -1 }
                val layerSiblingCount = nativeMetrics.getOrElse(MetricIndex.LAST_LAYER_SIBLING_COUNT) { 0 }
                val layerFrameWidth = nativeMetrics.getOrElse(MetricIndex.LAST_LAYER_FRAME_WIDTH) { 0 }
                val layerFrameHeight = nativeMetrics.getOrElse(MetricIndex.LAST_LAYER_FRAME_HEIGHT) { 0 }
                val parentFrameWidth = nativeMetrics.getOrElse(MetricIndex.LAST_PARENT_FRAME_WIDTH) { 0 }
                val parentFrameHeight = nativeMetrics.getOrElse(MetricIndex.LAST_PARENT_FRAME_HEIGHT) { 0 }
                val drawableReadbackStatus = nativeMetrics.getOrElse(MetricIndex.DRAWABLE_READBACK_STATUS) { 0 }
                val drawablePixelMin = nativeMetrics.getOrElse(MetricIndex.DRAWABLE_PIXEL_MIN) { -1 }
                val drawablePixelMax = nativeMetrics.getOrElse(MetricIndex.DRAWABLE_PIXEL_MAX) { -1 }
                val layerFrameX = nativeMetrics.getOrElse(MetricIndex.LAST_LAYER_FRAME_X) { 0 }
                val layerFrameY = nativeMetrics.getOrElse(MetricIndex.LAST_LAYER_FRAME_Y) { 0 }
                val componentBoundsX = nativeMetrics.getOrElse(MetricIndex.LAST_COMPONENT_BOUNDS_X) { 0 }
                val componentBoundsY = nativeMetrics.getOrElse(MetricIndex.LAST_COMPONENT_BOUNDS_Y) { 0 }
                val canvasWindowX = nativeMetrics.getOrElse(MetricIndex.LAST_CANVAS_WINDOW_X) { 0 }
                val canvasWindowY = nativeMetrics.getOrElse(MetricIndex.LAST_CANVAS_WINDOW_Y) { 0 }
                val drawableNonBlackSamples = nativeMetrics.getOrElse(MetricIndex.DRAWABLE_NON_BLACK_GRID_SAMPLES) { -1 }
                val siblingMaxZ = nativeMetrics.getOrElse(MetricIndex.LAST_LAYER_SIBLING_MAX_Z_MILLI) { 0 }
                val drawableAlphaMin = nativeMetrics.getOrElse(MetricIndex.DRAWABLE_ALPHA_MIN) { -1 }
                val drawableAlphaMax = nativeMetrics.getOrElse(MetricIndex.DRAWABLE_ALPHA_MAX) { -1 }
                val decodedAlphaMin = nativeMetrics.getOrElse(MetricIndex.DECODED_ALPHA_MIN) { -1 }
                val decodedAlphaMax = nativeMetrics.getOrElse(MetricIndex.DECODED_ALPHA_MAX) { -1 }
                surface.reportPerformance(
                    "Metal mirror perf packets=${queueWaitSamples.size} " +
                        "queue_wait_p95_ms=${percentileMs(queueWaitSamples)} " +
                        "decode_submit_p95_ms=${percentileMs(submitSamples)} " +
                        "queued_packets=${queueMetrics.queuedPackets} queued_config_packets=${queueMetrics.queuedConfigPackets} " +
                        "queued_bytes=${queueMetrics.queuedBytes} " +
                        "oldest_packet_age_ms=${queueMetrics.oldestPacketAgeMs} " +
                        "dropped_packets=${queueMetrics.droppedPackets} resync_events=${queueMetrics.resyncEvents} " +
                        "resync_pending=${queueMetrics.resyncPending} " +
                        "decode_callback_avg_ms=${averageMs(decodeTotalNs, decodeCount)} " +
                        "decode_callback_max_ms=${toMs(decodeMaxNs)} " +
                        "present_count=$presentCount " +
                        "presentation_age_avg_ms=${averageMs(presentTotalNs, presentCount)} " +
                        "presentation_age_p95_ms=${toMs(presentP95Ns)} " +
                        "presentation_age_max_ms=${toMs(presentMaxNs)} render_errors=$renderErrors " +
                        "decoded_images=$decodedImages attach_attempts=$attachAttempts " +
                        "attach_successes=$attachSuccesses attach_failures=$attachFailures " +
                        "last_attach_result=$attachResult attach_bounds=${attachWidth}x$attachHeight " +
                        "drawable_misses=$drawableMisses decoded_pixel_format=0x${decodedPixelFormat.toString(16)} " +
                        "decoded_sample_range=$decodedSampleMin-$decodedSampleMax " +
                        "layer_superlayer=$hasLayerSuperlayer window_layer=$hasWindowLayer " +
                        "layer_hidden=$layerHidden layer_opacity_milli=$layerOpacity " +
                        "layer_to_window=$layerDescendsFromWindow parent_hidden=$parentHidden " +
                        "parent_opacity_milli=$parentOpacity layer_z_milli=$layerZ " +
                        "layer_sibling=$layerSiblingIndex/$layerSiblingCount sibling_max_z_milli=$siblingMaxZ " +
                        "layer_origin=$layerFrameX,$layerFrameY layer_frame=${layerFrameWidth}x$layerFrameHeight " +
                        "canvas_window=$canvasWindowX,$canvasWindowY surface_bounds=$componentBoundsX,$componentBoundsY " +
                        "parent_frame=${parentFrameWidth}x$parentFrameHeight " +
                        "test_pattern=$testPatternEnabled drawable_readback_status=$drawableReadbackStatus " +
                        "drawable_sample_range=$drawablePixelMin-$drawablePixelMax " +
                        "drawable_nonblack_grid_samples=$drawableNonBlackSamples/81 " +
                        "drawable_alpha_range=$drawableAlphaMin-$drawableAlphaMax " +
                        "decoded_alpha_range=$decodedAlphaMin-$decodedAlphaMax",
                )
                queueWaitSamples.clear()
                submitSamples.clear()
                lastReportNs = reportNowNs
            }
        }
    }

    override fun close() {
        closed = true
        surface.close()
    }

    private fun percentileMs(samples: List<Long>): String {
        if (samples.isEmpty()) return "0.00"
        val sorted = samples.sorted()
        val index = ((sorted.size * P95_PERCENT + P95_ROUNDING_OFFSET) / 100 - 1).coerceIn(0, sorted.lastIndex)
        return toMs(sorted[index])
    }

    private fun averageMs(totalNs: Long, count: Long): String =
        if (count <= 0) "0.00" else toMs(totalNs / count)

    private fun toMs(nanoseconds: Long): String = "%.2f".format(nanoseconds / NANOS_PER_MILLISECOND)

    private companion object {
        const val REPORT_INTERVAL_NS = 5_000_000_000L
        const val MAX_REPORT_SAMPLES = 300
        const val NANOS_PER_MILLISECOND = 1_000_000.0
        const val P95_PERCENT = 95
        const val P95_ROUNDING_OFFSET = 99
    }

    /**
     * Index layout of the [MacVideoToolboxMirrorNative.nativeReadMetrics] `LongArray`. Names match
     * the native-side field names in `native/macos/indagium_mirror.mm`'s `nativeReadMetrics`
     * (`values[0]` through `values[48]`) so the two sides stay easy to cross-check.
     */
    private object MetricIndex {
        const val DECODE_COUNT = 0
        const val DECODE_TOTAL_NS = 1
        const val DECODE_MAX_NS = 2
        const val PRESENT_COUNT = 3
        const val PRESENT_TOTAL_NS = 4
        const val PRESENT_MAX_NS = 5
        const val RENDER_ERRORS = 6
        const val PRESENT_P95_NS = 7
        const val DECODED_IMAGE_COUNT = 8
        const val ATTACH_ATTEMPTS = 9
        const val ATTACH_SUCCESSES = 10
        const val ATTACH_FAILURES = 11
        const val DRAWABLE_MISSES = 12
        const val LAST_ATTACH_RESULT = 13
        const val LAST_ATTACH_WIDTH = 14
        const val LAST_ATTACH_HEIGHT = 15
        const val DECODED_PIXEL_FORMAT = 16
        const val DECODED_SAMPLE_MIN = 17
        const val DECODED_SAMPLE_MAX = 18
        const val LAST_LAYER_HAS_SUPERLAYER = 19
        const val LAST_WINDOW_LAYER_AVAILABLE = 20
        const val LAST_LAYER_HIDDEN = 21
        const val LAST_LAYER_OPACITY_MILLI = 22
        const val TEST_PATTERN = 23
        const val LAST_LAYER_DESCENDS_FROM_WINDOW = 24
        const val LAST_PARENT_HIDDEN = 25
        const val LAST_PARENT_OPACITY_MILLI = 26
        const val LAST_LAYER_Z_MILLI = 27
        const val LAST_LAYER_SIBLING_INDEX = 28
        const val LAST_LAYER_SIBLING_COUNT = 29
        const val LAST_LAYER_FRAME_WIDTH = 30
        const val LAST_LAYER_FRAME_HEIGHT = 31
        const val LAST_PARENT_FRAME_WIDTH = 32
        const val LAST_PARENT_FRAME_HEIGHT = 33
        const val DRAWABLE_READBACK_STATUS = 34
        const val DRAWABLE_PIXEL_MIN = 35
        const val DRAWABLE_PIXEL_MAX = 36
        const val LAST_LAYER_FRAME_X = 37
        const val LAST_LAYER_FRAME_Y = 38
        const val LAST_COMPONENT_BOUNDS_X = 39
        const val LAST_COMPONENT_BOUNDS_Y = 40
        const val LAST_CANVAS_WINDOW_X = 41
        const val LAST_CANVAS_WINDOW_Y = 42
        const val DRAWABLE_NON_BLACK_GRID_SAMPLES = 43
        const val LAST_LAYER_SIBLING_MAX_Z_MILLI = 44
        const val DRAWABLE_ALPHA_MIN = 45
        const val DRAWABLE_ALPHA_MAX = 46
        const val DECODED_ALPHA_MIN = 47
        const val DECODED_ALPHA_MAX = 48
    }
}
