package com.indagium

import com.indagium.utils.LOG_HEAP_BYTES_PER_FILE_BYTE
import com.indagium.utils.MemoryShortfall
import com.indagium.utils.SPLIT_PROMPT_BYTES
import com.indagium.utils.estimatedHeapBytesForLog
import com.indagium.utils.memoryShortfall
import com.indagium.utils.suggestedSplitPartCount
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class LogMemoryEstimateTest {
    private val mb = 1024L * 1024L

    @Test
    fun estimateIsTheCalibratedMultipleOfTheFileSize() {
        assertEquals(3.5, LOG_HEAP_BYTES_PER_FILE_BYTE)
        assertEquals(0L, estimatedHeapBytesForLog(0L))
        assertEquals(0L, estimatedHeapBytesForLog(-5L))
        assertEquals(3_500L, estimatedHeapBytesForLog(1_000L))
        assertEquals(350 * mb, estimatedHeapBytesForLog(100 * mb))
    }

    @Test
    fun shortfallWhenNeedExceedsFreeElseNull() {
        assertEquals(MemoryShortfall(350 * mb, 200 * mb), memoryShortfall(100 * mb, 200 * mb))
        assertNull(memoryShortfall(100 * mb, 350 * mb), "need == free fits")
        assertNull(memoryShortfall(100 * mb, 1024 * mb))
        assertNull(memoryShortfall(0L, 0L))
    }

    @Test
    fun batchNeedIsTheSumOfTheFiles() {
        // Each file fits alone (need 175 MB <= 200 MB) but together they don't (350 MB).
        assertNull(memoryShortfall(50 * mb, 200 * mb))
        val batch = memoryShortfall(listOf(50 * mb, 50 * mb), 200 * mb)
        assertEquals(MemoryShortfall(350 * mb, 200 * mb), batch)
        assertNull(memoryShortfall(emptyList(), 0L))
    }

    @Test
    fun memoryDrivenPartCountIsAtLeastTwoEvenBelowTheSizeThreshold() {
        val small = 100 * mb
        assertEquals(1, suggestedSplitPartCount(small))
        // need 350 MB vs free 200 MB -> ceil(1.75) = 2
        assertEquals(2, suggestedSplitPartCount(small, MemoryShortfall(350 * mb, 200 * mb)))
        // need 350 MB vs free 100 MB -> ceil(3.5) = 4
        assertEquals(4, suggestedSplitPartCount(small, MemoryShortfall(350 * mb, 100 * mb)))
        // Barely short still proposes a real split.
        assertEquals(2, suggestedSplitPartCount(small, MemoryShortfall(350 * mb + 1, 350 * mb)))
    }

    @Test
    fun memoryDrivenPartCountIsCappedAndKeepsTheSizeBasedCountWhenLarger() {
        assertEquals(32, suggestedSplitPartCount(100 * mb, MemoryShortfall(350 * mb, 0L)))
        assertEquals(32, suggestedSplitPartCount(100 * mb, MemoryShortfall(350 * mb, 1L)))
        assertEquals(3, suggestedSplitPartCount(SPLIT_PROMPT_BYTES * 2 + 1, null))
        assertTrue(suggestedSplitPartCount(SPLIT_PROMPT_BYTES * 2 + 1, MemoryShortfall(1, 1)) >= 3)
    }
}
