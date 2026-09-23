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
                val decodeCount = nativeMetrics.getOrElse(0) { 0 }
                val decodeTotalNs = nativeMetrics.getOrElse(1) { 0 }
                val decodeMaxNs = nativeMetrics.getOrElse(2) { 0 }
                val presentCount = nativeMetrics.getOrElse(3) { 0 }
                val presentTotalNs = nativeMetrics.getOrElse(4) { 0 }
                val presentMaxNs = nativeMetrics.getOrElse(5) { 0 }
                val renderErrors = nativeMetrics.getOrElse(6) { 0 }
                val presentP95Ns = nativeMetrics.getOrElse(7) { 0 }
                val decodedImages = nativeMetrics.getOrElse(8) { 0 }
                val attachAttempts = nativeMetrics.getOrElse(9) { 0 }
                val attachSuccesses = nativeMetrics.getOrElse(10) { 0 }
                val attachFailures = nativeMetrics.getOrElse(11) { 0 }
                val drawableMisses = nativeMetrics.getOrElse(12) { 0 }
                val attachResult = nativeMetrics.getOrElse(13) { 0 }
                val attachWidth = nativeMetrics.getOrElse(14) { 0 }
                val attachHeight = nativeMetrics.getOrElse(15) { 0 }
                val decodedPixelFormat = nativeMetrics.getOrElse(16) { 0 }
                val decodedSampleMin = nativeMetrics.getOrElse(17) { -1 }
                val decodedSampleMax = nativeMetrics.getOrElse(18) { -1 }
                val hasLayerSuperlayer = nativeMetrics.getOrElse(19) { 0 }
                val hasWindowLayer = nativeMetrics.getOrElse(20) { 0 }
                val layerHidden = nativeMetrics.getOrElse(21) { 0 }
                val layerOpacity = nativeMetrics.getOrElse(22) { 0 }
                val testPatternEnabled = nativeMetrics.getOrElse(23) { 0 }
                val layerDescendsFromWindow = nativeMetrics.getOrElse(24) { 0 }
                val parentHidden = nativeMetrics.getOrElse(25) { 0 }
                val parentOpacity = nativeMetrics.getOrElse(26) { 0 }
                val layerZ = nativeMetrics.getOrElse(27) { 0 }
                val layerSiblingIndex = nativeMetrics.getOrElse(28) { -1 }
                val layerSiblingCount = nativeMetrics.getOrElse(29) { 0 }
                val layerFrameWidth = nativeMetrics.getOrElse(30) { 0 }
                val layerFrameHeight = nativeMetrics.getOrElse(31) { 0 }
                val parentFrameWidth = nativeMetrics.getOrElse(32) { 0 }
                val parentFrameHeight = nativeMetrics.getOrElse(33) { 0 }
                val drawableReadbackStatus = nativeMetrics.getOrElse(34) { 0 }
                val drawablePixelMin = nativeMetrics.getOrElse(35) { -1 }
                val drawablePixelMax = nativeMetrics.getOrElse(36) { -1 }
                val layerFrameX = nativeMetrics.getOrElse(37) { 0 }
                val layerFrameY = nativeMetrics.getOrElse(38) { 0 }
                val componentBoundsX = nativeMetrics.getOrElse(39) { 0 }
                val componentBoundsY = nativeMetrics.getOrElse(40) { 0 }
                val canvasWindowX = nativeMetrics.getOrElse(41) { 0 }
                val canvasWindowY = nativeMetrics.getOrElse(42) { 0 }
                val drawableNonBlackSamples = nativeMetrics.getOrElse(43) { -1 }
                val siblingMaxZ = nativeMetrics.getOrElse(44) { 0 }
                val drawableAlphaMin = nativeMetrics.getOrElse(45) { -1 }
                val drawableAlphaMax = nativeMetrics.getOrElse(46) { -1 }
                val decodedAlphaMin = nativeMetrics.getOrElse(47) { -1 }
                val decodedAlphaMax = nativeMetrics.getOrElse(48) { -1 }
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
        val index = ((sorted.size * 95 + 99) / 100 - 1).coerceIn(0, sorted.lastIndex)
        return toMs(sorted[index])
    }

    private fun averageMs(totalNs: Long, count: Long): String =
        if (count <= 0) "0.00" else toMs(totalNs / count)

    private fun toMs(nanoseconds: Long): String = "%.2f".format(nanoseconds / NANOS_PER_MILLISECOND)

    private companion object {
        const val REPORT_INTERVAL_NS = 5_000_000_000L
        const val MAX_REPORT_SAMPLES = 300
        const val NANOS_PER_MILLISECOND = 1_000_000.0
    }
}
