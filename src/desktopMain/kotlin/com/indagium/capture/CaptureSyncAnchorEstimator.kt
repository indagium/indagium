package com.indagium.capture

// Archive v3's log<->video sync: instead of shipping a per-row `log-video-sync.jsonl` mapping (one
// JSON line per log row, ~440KB for an 80s capture), the export computes ONE anchor point and lets
// AppState re-derive every other row's video position from it exactly the way a manual link already
// does — see Model.kt's VideoAnchor doc ("slope 1": videoMs = anchorVideoMs + (rowLogTimeMs -
// anchorLogTimeMs)) and AppState.logIdToVideoMs/videoMsToNearestLogId, which fall back to that exact
// arithmetic whenever a tab has no CaptureTimeline. This file's only job is picking that one anchor
// point well.

/**
 * One export-local row's evidence for estimating the single log<->video offset: [ordinal] is the
 * export-local, 1-based row number (matches the row LogParser assigns when the archive's own
 * `logcat.log` is reopened — see CaptureArchiveTest for the invariant this relies on).
 * [logTimeMs] is that row's own device-embedded timestamp, day-unrolled the same way
 * AppState.monotonicLogElapsedById computes it for a manual anchor (both go through
 * com.indagium.utils.unrollLogTimeline over the SAME parsed rows) — so the offset this produces
 * agrees with the manual-anchor math a reopened archive will use at import time. [videoMs] is the
 * host-observed video position for that row (already clip-relative when the export remuxed a clip),
 * or null when the row falls outside whatever video coverage this export has. Either field may be
 * null on its own; only a row with BOTH contributes an offset sample — see
 * [estimateCaptureSyncAnchor].
 */
data class CaptureSyncSample(val ordinal: Int, val logTimeMs: Long?, val videoMs: Long?)

/** One log-row <-> video-position pin: row [row] (export-local ordinal) sits at [videoMs] in the
 *  archive's own video. */
data class CaptureSyncAnchor(val row: Int, val videoMs: Long)

// Host-receive time can only ever LAG the device's own embedded timestamp (network/USB/adb
// transport latency is never negative), so across many rows the TRUE constant offset is the LOW
// end of the observed (videoMs - logTimeMs) distribution — never the middle (latency jitter only
// ever pushes individual samples UP from the true offset, so the median is biased high) and not
// the raw minimum either (a single clock glitch/out-of-order sample can pull the minimum below the
// true offset; a low percentile absorbs a handful of such outliers instead of anchoring on one of
// them). 2% is a compromise between "resists a rare bad sample" and "still low enough to reflect
// genuine best-case latency, not the jittery bulk of the distribution."
private const val ANCHOR_OFFSET_LOW_PERCENTILE = 2.0

/**
 * Reduces a whole export's per-row (log time, video time) observations to the single anchor point
 * archive v3 ships in its descriptor's `sync` key, in place of the old row-by-row mapping file.
 *
 * The offset is estimated from every row that has both a parsed log timestamp and a known video
 * position (see [CaptureSyncSample]), using a low percentile of (videoMs - logTimeMs) rather than
 * the raw minimum (see [ANCHOR_OFFSET_LOW_PERCENTILE]'s own doc for why). The anchor ROW is then
 * chosen among every row that has a log timestamp (not only rows with a known video position): the
 * first one, in ordinal order, whose PREDICTED video position (that row's own logTimeMs + the
 * estimated offset) actually falls inside [videoDurationMs]'s coverage — preferring an anchor near
 * the start of the video over the first ordinal that happens to have a timestamp, which may predict
 * a position before the video even starts (e.g. a log row captured before recording began). Falls
 * back to the first timestamped row, clamped into range, if no candidate's prediction lands inside
 * coverage at all.
 *
 * Returns null when there is nothing to anchor: no row has both a timestamp and a video position
 * (including "this export has no video at all"), or no row has a timestamp whatsoever.
 */
fun estimateCaptureSyncAnchor(samples: List<CaptureSyncSample>, videoDurationMs: Long? = null): CaptureSyncAnchor? {
    val offsetSamples = samples.filter { it.logTimeMs != null && it.videoMs != null }
    if (offsetSamples.isEmpty()) return null
    val offsets = offsetSamples.map { it.videoMs!! - it.logTimeMs!! }.sorted()
    val chosenOffsetMs = lowPercentile(offsets, ANCHOR_OFFSET_LOW_PERCENTILE)

    val candidates = samples.filter { it.logTimeMs != null }.sortedBy { it.ordinal }
    if (candidates.isEmpty()) return null
    val preferred = candidates.firstOrNull { candidate ->
        val predicted = candidate.logTimeMs!! + chosenOffsetMs
        predicted in 0..(videoDurationMs ?: Long.MAX_VALUE)
    }
    val chosen = preferred ?: candidates.first()
    val predicted = (chosen.logTimeMs!! + chosenOffsetMs)
        .coerceAtLeast(0L)
        .let { if (videoDurationMs != null) it.coerceAtMost(videoDurationMs) else it }
    return CaptureSyncAnchor(row = chosen.ordinal, videoMs = predicted)
}

/** Nearest-rank percentile over an already-ascending-sorted list ([percentile] in `0..100`). Index
 *  interpolation is intentionally simple (no averaging between ranks) — this only ever feeds a
 *  single anchor point, not a statistic anyone inspects directly. */
private fun lowPercentile(sortedAscending: List<Long>, percentile: Double): Long {
    if (sortedAscending.isEmpty()) return 0L
    val index = ((percentile / 100.0) * (sortedAscending.size - 1))
        .toInt()
        .coerceIn(0, sortedAscending.size - 1)
    return sortedAscending[index]
}
