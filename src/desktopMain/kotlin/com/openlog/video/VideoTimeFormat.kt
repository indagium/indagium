package com.openlog.video

import java.util.Locale

private const val MS_PER_SECOND = 1_000L
private const val MS_PER_MINUTE = 60_000L

/**
 * Formats a video position/duration in milliseconds as "mm:ss.mmm" (e.g. "01:23.456") — the
 * transport bar's time labels (ui/VideoPanel.kt). Minutes are NOT wrapped into an hours segment:
 * a >59-minute screen recording still reads as e.g. "75:03.500" rather than switching formats
 * partway through a session, so every label in the transport bar keeps the same shape regardless
 * of the recording's length.
 *
 * Negative input (shouldn't happen — [VideoPlayerController]'s seek/position are always clamped to
 * >= 0) is treated as zero rather than producing a garbled negative string.
 */
fun formatVideoTime(ms: Long): String {
    val clamped = ms.coerceAtLeast(0L)
    val minutes = clamped / MS_PER_MINUTE
    val seconds = (clamped % MS_PER_MINUTE) / MS_PER_SECOND
    val millis = clamped % MS_PER_SECOND
    return String.format(Locale.US, "%02d:%02d.%03d", minutes, seconds, millis)
}

/**
 * Same as [formatVideoTime] but without the milliseconds segment ("mm:ss") — used by the transport
 * bar's elapsed/duration labels, where sub-second precision is just visual noise.
 */
fun formatVideoTimeShort(ms: Long): String {
    val clamped = ms.coerceAtLeast(0L)
    val minutes = clamped / MS_PER_MINUTE
    val seconds = (clamped % MS_PER_MINUTE) / MS_PER_SECOND
    return String.format(Locale.US, "%02d:%02d", minutes, seconds)
}

/**
 * Same as [formatVideoTimeShort], except a non-positive [ms] renders as "--:--" instead of
 * "00:00" — the transport bar's total-duration label uses this, never [formatVideoTimeShort]
 * directly, because 0 there is not a real (zero-length) duration. `VideoPlayerController.durationMs`
 * reads 0 both transiently (right after opening, before FFmpeg's header duration or the background
 * `scanDurationMs` recovery scan has reported one — see that function's own KDoc for why some
 * containers need it) and, for a genuinely durationless file whose scan also comes up empty,
 * potentially forever. "00:00" would misreport a video that's actually playing (a real, non-zero
 * elapsed position already shows next to it) as zero seconds long.
 */
fun formatVideoDurationShort(ms: Long): String = if (ms > 0L) formatVideoTimeShort(ms) else "--:--"
