package com.indagium.video

import org.bytedeco.ffmpeg.global.avutil.AV_NOPTS_VALUE
import org.bytedeco.javacv.FFmpegFrameGrabber

private const val MICROS_PER_SECOND = 1_000_000L

/**
 * `FFmpegFrameGrabber.setTimestamp(t)` in JavaCV 1.5.13 seeks to `t + AVFormatContext.start_time` and
 * then first grabs (up to 1000 times, audio and video together) until a grabbed timestamp is no
 * later than that target. A container's `start_time` is the minimum over ALL streams, and libopus'
 * pre-skip makes ffmpeg-made MKV/MP4 files start their audio a few milliseconds BEFORE the video
 * (first Opus packet at pts -7 ms, first video frame at 0): `start_time` is then negative although no
 * decoder ever emits a frame before the video's first one. A target of 0..6 ms becomes a negative
 * container time that nothing satisfies, so the loop burns its whole 1000-grab budget and lands
 * about 12 s into the file (a seek to 7 ms or later is unaffected).
 *
 * Returns [targetUs] raised just enough that `target + containerStartUs` is not before the video
 * stream's own first timestamp ([videoStartUs]). Anything else (no video, a start time that is not
 * negative or not below the video's, a target already past it) is returned unchanged, so every seek
 * that already worked keeps landing exactly where it did.
 */
internal fun clampSeekTargetToVideoStartUs(targetUs: Long, containerStartUs: Long?, videoStartUs: Long?): Long {
    if (containerStartUs == null || videoStartUs == null) return targetUs
    if (containerStartUs >= 0L || containerStartUs >= videoStartUs) return targetUs
    return maxOf(targetUs, videoStartUs - containerStartUs)
}

/**
 * [FFmpegFrameGrabber.setTimestamp] that cannot be derailed by a negative container start time — see
 * [clampSeekTargetToVideoStartUs]. A single seek in the container's own timeline, so seeks to any
 * other target are exactly as fast and as frame-accurate as before.
 */
internal fun FFmpegFrameGrabber.seekToTimestampUs(targetUs: Long) {
    setTimestamp(clampSeekTargetToVideoStartUs(targetUs, containerStartUs(), videoStreamStartUs()))
}

private fun FFmpegFrameGrabber.containerStartUs(): Long? =
    formatContext?.start_time()?.takeIf { it != AV_NOPTS_VALUE }

private fun FFmpegFrameGrabber.videoStreamStartUs(): Long? {
    val format = formatContext ?: return null
    if (!hasVideo() || videoStream < 0) return null
    val stream = format.streams(videoStream) ?: return null
    val startTicks = stream.start_time().takeIf { it != AV_NOPTS_VALUE } ?: return null
    val timeBase = stream.time_base()
    if (timeBase.den() == 0) return null
    return MICROS_PER_SECOND * startTicks * timeBase.num() / timeBase.den()
}
