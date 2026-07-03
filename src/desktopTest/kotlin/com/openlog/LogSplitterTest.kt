package com.openlog

import com.openlog.utils.DEFAULT_SPLIT_POSTFIX
import com.openlog.utils.SPLIT_PROMPT_BYTES
import com.openlog.utils.planSplitOutputs
import com.openlog.utils.requiresSplitPrompt
import com.openlog.utils.splitStreamToFiles
import com.openlog.utils.suggestedSplitPartCount
import java.io.ByteArrayInputStream
import java.io.File
import kotlin.io.path.createTempDirectory
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class LogSplitterTest {
    @Test
    fun splitPromptThresholdStartsAtFiveHundredMegabytes() {
        assertFalse(requiresSplitPrompt(SPLIT_PROMPT_BYTES - 1))
        assertTrue(requiresSplitPrompt(SPLIT_PROMPT_BYTES))
    }

    @Test
    fun suggestedPartCountKeepsEachPartUnderThreshold() {
        assertEquals(1, suggestedSplitPartCount(SPLIT_PROMPT_BYTES - 1))
        assertEquals(1, suggestedSplitPartCount(SPLIT_PROMPT_BYTES))
        assertEquals(2, suggestedSplitPartCount(SPLIT_PROMPT_BYTES + 1))
        assertEquals(3, suggestedSplitPartCount(SPLIT_PROMPT_BYTES * 2 + 1))
    }

    @Test
    fun outputNamesPreserveExtensionAndUseDefaultPostfix() {
        val dir = createTempDirectory("openlog-split-plan").toFile()

        val outputs = planSplitOutputs(
            sourceName = "bugreport.log",
            destinationDir = dir,
            postfix = DEFAULT_SPLIT_POSTFIX,
            partCount = 3,
        )

        assertEquals(
            listOf("bugreport_part_1.log", "bugreport_part_2.log", "bugreport_part_3.log"),
            outputs.map(File::getName),
        )
    }

    @Test
    fun outputNamesAreAutoUniqueAsASetWhenAnyTargetExists() {
        val dir = createTempDirectory("openlog-split-collision").toFile()
        File(dir, "bugreport_part_2.log").writeText("existing")

        val outputs = planSplitOutputs(
            sourceName = "bugreport.log",
            destinationDir = dir,
            postfix = "part",
            partCount = 2,
        )

        assertEquals(
            listOf("bugreport_2_part_1.log", "bugreport_2_part_2.log"),
            outputs.map(File::getName),
        )
    }

    @Test
    fun splitStreamPreservesLinesAndConcatenatesBackToOriginal() {
        val dir = createTempDirectory("openlog-split-lines").toFile()
        val original = "one\nvery long line two\nthree\nfour\n"
        val outputs = planSplitOutputs("sample.log", dir, "part", 3)

        val written = splitStreamToFiles(
            input = ByteArrayInputStream(original.toByteArray()),
            outputFiles = outputs,
            sourceSizeBytes = original.toByteArray().size.toLong(),
        )

        assertEquals(outputs, written)
        assertEquals(original, written.joinToString(separator = "") { it.readText() })
        assertTrue(written.all { file ->
            val text = file.readText()
            text.isEmpty() || text.endsWith("\n")
        })
    }
}
