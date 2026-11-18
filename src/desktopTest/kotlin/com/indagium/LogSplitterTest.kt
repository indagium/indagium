package com.indagium

import com.indagium.model.LogFieldRole
import com.indagium.model.LogFormatEngine
import com.indagium.model.LogFormatProfile
import com.indagium.utils.DEFAULT_SPLIT_POSTFIX
import com.indagium.utils.SPLIT_PROMPT_BYTES
import com.indagium.utils.parseLogContent
import com.indagium.utils.parseLogcat
import com.indagium.utils.parseProfileLines
import com.indagium.utils.planSplitOutputs
import com.indagium.utils.requiresSplitPrompt
import com.indagium.utils.splitProfileRecordsToFiles
import com.indagium.utils.splitStreamToFiles
import com.indagium.utils.suggestedSplitPartCount
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

    @Test
    fun csvProfileSplitKeepsQuotedMultilineRowsWholeAndRepeatsHeaders() {
        val profile = LogFormatProfile(
            id = "split-csv",
            name = "Split CSV",
            engine = LogFormatEngine.CSV,
            fieldMappings = mapOf(LogFieldRole.MESSAGE to "message"),
        )
        val source = "time,message\n1,\"first line\nsecond line\"\n2,plain\n3,last\n"
        val directory = createTempDirectory("openlog-profile-csv-split").toFile()
        val outputs = planSplitOutputs("sample.csv", directory, "part", 3)

        splitProfileRecordsToFiles(ByteArrayInputStream(source.toByteArray()), outputs, source.toByteArray().size.toLong(), profile)

        assertTrue(outputs.drop(1).all { it.readLines().first() == "time,message" })
        val entries = outputs.flatMap { file -> parseProfileLines(file.readLines(), profile).entries }
        assertEquals(listOf("first line\nsecond line", "plain", "last"), entries.map { it.msg })
    }

    @Test
    fun jsonArraySplitProducesValidArrayPartsWithWholeEvents() {
        val profile = LogFormatProfile(
            id = "split-json-array",
            name = "Split JSON array",
            engine = LogFormatEngine.JSON_LINES,
            fieldMappings = mapOf(LogFieldRole.MESSAGE to "message"),
        )
        val source = (0 until 30).joinToString(prefix = "[\n", postfix = "\n]\n") { index ->
            "  {\"message\":\"event-$index ${"x".repeat(100)}\",\"nested\":[1,{\"ok\":true}]}"
        }
        val directory = createTempDirectory("openlog-profile-json-split").toFile()
        val outputs = planSplitOutputs("events.json", directory, "part", 3)

        splitProfileRecordsToFiles(
            ByteArrayInputStream(source.toByteArray()), outputs, source.toByteArray().size.toLong(), profile,
        )

        assertTrue(outputs.all { file ->
            val text = file.readText()
            text.trimStart().startsWith("[") && text.trimEnd().endsWith("]")
        })
        val entries = outputs.flatMap { file -> parseProfileLines(file.readLines(), profile).entries }
        assertEquals((0 until 30).map { "event-$it ${"x".repeat(100)}" }, entries.map { it.msg })
    }

    @Test
    fun jsonSplitPreservesRecordsAcrossRootArrayBoundariesOnTheSameLine() {
        val profile = LogFormatProfile(
            id = "split-json-mixed",
            name = "Split mixed JSON",
            engine = LogFormatEngine.JSON_LINES,
            fieldMappings = mapOf(LogFieldRole.MESSAGE to "message"),
        )
        val source = """
            {
              "message": "before"
            } [{"message":"array-one"}] [{"message":"array-two"}] {
              "message": "after"
            } unexpected text
        """.trimIndent()
        val directory = createTempDirectory("openlog-profile-json-mixed-split").toFile()
        try {
            val bytes = source.toByteArray()
            val outputs = planSplitOutputs("events.json", directory, "part", 2)

            splitProfileRecordsToFiles(ByteArrayInputStream(bytes), outputs, bytes.size.toLong(), profile)

            val entries = outputs.flatMap { file -> parseProfileLines(file.readLines(), profile).entries }
            assertEquals(listOf("before", "array-one", "array-two", "after", " unexpected text"), entries.map { it.msg })
            assertEquals(parseProfileLines(source.lines(), profile).entries.map { it.rawRecord }, entries.map { it.rawRecord })
        } finally {
            directory.deleteRecursively()
        }
    }

    @Test
    fun splitJsonKeepsUnicodeHeavyUtf16PartsReadableIndependently() {
        val profile = LogFormatProfile(id = "utf16-json", name = "UTF-16 JSON", engine = LogFormatEngine.JSON_LINES)
        val record = """{"message":"${"漢".repeat(5000)}"}"""
        val bytes = "\uFEFF$record\r\n$record\r\n".toByteArray(Charsets.UTF_16LE)
        val directory = createTempDirectory("openlog-profile-json-encoding").toFile()
        try {
            val outputs = planSplitOutputs("events.json", directory, "part", 2)

            splitProfileRecordsToFiles(ByteArrayInputStream(bytes), outputs, bytes.size.toLong(), profile)

            val entries = outputs.flatMap { file ->
                file.inputStream().use { parseLogContent(it, profile = profile).entries }
            }
            assertEquals(listOf(record, record), entries.map { it.rawRecord })
        } finally {
            directory.deleteRecursively()
        }
    }

    @Test
    fun multilineRegexProfileSplitCutsOnlyAtConfiguredRecordStarts() {
        val profile = LogFormatProfile(
            id = "split-regex",
            name = "Split multiline regex",
            engine = LogFormatEngine.REGEX,
            regexPattern = "^(\\d+): (.*)$",
            multilineStartPattern = "^\\d+: ",
            fieldMappings = mapOf(LogFieldRole.MESSAGE to "2"),
        )
        val source = "1: first\n  continuation one\n2: second\n  continuation two\n3: third\n"
        val directory = createTempDirectory("openlog-profile-regex-split").toFile()
        val outputs = planSplitOutputs("sample.log", directory, "part", 2)

        splitProfileRecordsToFiles(ByteArrayInputStream(source.toByteArray()), outputs, source.toByteArray().size.toLong(), profile)

        val entries = outputs.flatMap { file -> parseProfileLines(file.readLines(), profile).entries }
        assertEquals(3, entries.size)
        assertEquals(
            listOf("1: first\n  continuation one", "2: second\n  continuation two", "3: third"),
            entries.map { it.rawRecord },
        )
    }

    @Test
    fun splitUtf16PartsDecodeEvenWhenOnlyTheFirstPartHasTheBom() {
        val text = buildString {
            repeat(12) { index ->
                val message = if (index == 5) "line $index — π" else "line $index"
                append("06-29 12:34:${(index % 60).toString().padStart(2, '0')}.789 1 1 I App: $message\n")
            }
        }
        val dir = createTempDirectory("openlog-split-utf16").toFile()

        for ((name, charset, bom) in listOf(
            Triple("be", Charsets.UTF_16BE, byteArrayOf(0xFE.toByte(), 0xFF.toByte())),
            Triple("le", Charsets.UTF_16LE, byteArrayOf(0xFF.toByte(), 0xFE.toByte())),
        )) {
            val sourceBytes = bom + text.toByteArray(charset)
            val outputs = planSplitOutputs("$name.log", dir, "part", 3)
            splitStreamToFiles(ByteArrayInputStream(sourceBytes), outputs, sourceBytes.size.toLong())

            assertEquals(sourceBytes.toList(), outputs.flatMap { it.readBytes().toList() }, name)
            val entries = outputs.flatMap { parseLogcat(it) }
            assertEquals(12, entries.size, name)
            assertTrue(entries.all { it.tag == "App" }, name)
            assertEquals("line 5 — π", entries[5].msg, name)
        }
    }
}
