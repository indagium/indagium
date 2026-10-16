package com.indagium.capture

import java.io.File

/**
 * Optional extended export path for archives that preserve periods when the device was offline.
 * The ordinary [CaptureVideoExporter] remains a SAM so existing lambdas and fake exporters keep
 * working; implementations opt into this interface when they can bake availability cards into a
 * complete session-time video.
 */
interface CaptureAvailabilityVideoExporter {
    /**
     * Exports the elapsed-time interval `[timelineStartElapsedMs, timelineEndElapsedMs)`.
     *
     * [sourceElapsedOriginMs] is the session elapsed value represented by source PTS zero, so a
     * source frame at PTS `p` belongs at session time `p + sourceElapsedOriginMs`. A null source is
     * valid when [availabilityIntervals] cover the whole requested interval (for example, the
     * device never returned before Stop). Returned [CaptureVideoClip] bounds use source-equivalent
     * coordinates so existing sync-anchor calculations map a session row to
     * `elapsedMs - timelineStartElapsedMs` in the exported file.
     */
    fun exportWithAvailability(
        source: File?,
        destination: File,
        timelineStartElapsedMs: Long,
        timelineEndElapsedMs: Long,
        sourceElapsedOriginMs: Long,
        availabilityIntervals: List<CaptureAvailabilityInterval>,
        sourceIsFinal: Boolean = false,
    ): CaptureVideoClip
}
