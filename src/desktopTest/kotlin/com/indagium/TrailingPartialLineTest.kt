package com.indagium

import com.indagium.utils.trailingPartialLine
import java.io.File
import kotlin.io.path.createTempDirectory
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

/** Where a tail must start to read a file's unfinished last line whole. */
class TrailingPartialLineTest {
    private fun file(bytes: ByteArray, block: (File) -> Unit) {
        val dir = createTempDirectory("trailing-partial").toFile()
        try {
            block(File(dir, "f.log").apply { writeBytes(bytes) })
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test
    fun aFileEndingWithALineBreakOrEmptyHasNoUnfinishedLine() {
        file("a\nb\n".toByteArray()) { assertNull(trailingPartialLine(it)) }
        file(ByteArray(0)) { assertNull(trailingPartialLine(it)) }
    }

    @Test
    fun theUnfinishedLineStartsAfterTheLastLineBreak() {
        file("first\nsecond\nthi".toByteArray()) {
            val line = assertNotNull(trailingPartialLine(it))
            assertEquals(13L, line.startOffset)
            assertEquals("thi", line.text)
        }
    }

    @Test
    fun aCarriageReturnWaitingForItsLineFeedIsNotPartOfTheText() {
        file("first\r\nsecond\r".toByteArray()) {
            val line = assertNotNull(trailingPartialLine(it))
            assertEquals(7L, line.startOffset)
            assertEquals("second", line.text)
        }
    }

    @Test
    fun aFileWithNoLineBreakStartsAtZeroAndDropsAByteOrderMark() {
        file("﻿only line".toByteArray()) {
            val line = assertNotNull(trailingPartialLine(it))
            assertEquals(0L, line.startOffset)
            assertEquals("only line", line.text)
        }
    }

    @Test
    fun aLastLineOverAMegabyteIsLeftAlone() {
        file("x\n".toByteArray() + ByteArray(1_200_000) { 'a'.code.toByte() }) { assertNull(trailingPartialLine(it)) }
    }
}
