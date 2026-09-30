package com.indagium.utils

import kotlin.math.ceil

/**
 * Heap bytes a parsed log needs per byte of file, used by the pre-open memory check.
 *
 * Calibration: a 148 MB logcat file measured about 330 MB of `LogEntry` rows plus the derived lists and
 * indexes (analysis, filter memo, id maps), i.e. roughly 2.2x. The factor is deliberately generous
 * (3.5x) because a false "open anyway?" prompt is cheap, while an OutOfMemoryError mid-load is not;
 * files with short lines (more rows per byte) sit at the expensive end of the range.
 */
const val LOG_HEAP_BYTES_PER_FILE_BYTE: Double = 3.5

/** Largest part count a memory-driven split suggestion proposes (matches the dialog's stepper range). */
private const val MAX_MEMORY_SPLIT_PARTS = 32

/** The estimated heap need of a log batch exceeds the free heap. [neededBytes] is the whole batch's need. */
data class MemoryShortfall(val neededBytes: Long, val freeBytes: Long)

/** Estimated resident heap bytes once a file of [fileBytes] is parsed and open in a tab. */
fun estimatedHeapBytesForLog(fileBytes: Long): Long =
    if (fileBytes <= 0L) 0L else (fileBytes * LOG_HEAP_BYTES_PER_FILE_BYTE).toLong()

/** A [MemoryShortfall] when opening [fileBytes] needs more than [freeBytes] of heap, else null. */
fun memoryShortfall(fileBytes: Long, freeBytes: Long): MemoryShortfall? {
    val needed = estimatedHeapBytesForLog(fileBytes)
    return if (needed > freeBytes) MemoryShortfall(needed, freeBytes) else null
}

/**
 * Batch form: files opened together are all resident at once, so the need is the SUM of the files.
 * One shortfall describes the whole batch.
 */
fun memoryShortfall(fileSizes: List<Long>, freeBytes: Long): MemoryShortfall? =
    memoryShortfall(fileSizes.sumOf { it.coerceAtLeast(0L) }, freeBytes)

/**
 * Split-part suggestion for a prompt raised by a [shortfall]. A file under the size threshold gets 1
 * from [suggestedSplitPartCount], which would make "Split" a no-op, so the result is at least 2 and is
 * widened to ceil(needed / free) so each part is, roughly, a slice the free heap could hold; capped at
 * 32 (the dialog's stepper range) because a near-zero free estimate must not suggest thousands of parts.
 * Without a shortfall this is exactly [suggestedSplitPartCount].
 */
fun suggestedSplitPartCount(sizeBytes: Long, shortfall: MemoryShortfall?): Int {
    val bySize = suggestedSplitPartCount(sizeBytes)
    if (shortfall == null) return bySize
    val byMemory = if (shortfall.freeBytes <= 0L) {
        MAX_MEMORY_SPLIT_PARTS
    } else {
        ceil(shortfall.neededBytes.toDouble() / shortfall.freeBytes.toDouble()).toInt()
    }
    return maxOf(bySize, byMemory, 2).coerceAtMost(maxOf(bySize, MAX_MEMORY_SPLIT_PARTS))
}
