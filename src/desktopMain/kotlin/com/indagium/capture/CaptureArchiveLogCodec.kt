package com.indagium.capture

/** How the `logcat.log` entry is compressed inside the ZIP. */
internal enum class CaptureArchiveLogCodec {
    /** Plain ZIP method 8; opens in every unzip tool. */
    DEFLATE,

    /** ZIP method 93 (zstd): ~3x smaller than DEFLATE on logcat, but needs zstd-aware readers. */
    ZSTD,
}

internal const val COMPACT_LOG_ZSTD_LEVEL = 12
private const val COMPACT_LOG_ZSTD_MAX_WORKERS = 4

/** One core is left to the rest of the app and zstd gains little past a few workers on a log this size. */
internal fun compactLogZstdWorkers(availableProcessors: Int): Int =
    (availableProcessors - 1).coerceIn(1, COMPACT_LOG_ZSTD_MAX_WORKERS)
