package com.indagium

import com.indagium.model.BUILTIN_LOG_FORMAT_PROFILES
import com.indagium.model.LogFieldRole
import com.indagium.model.LogFormatEngine
import com.indagium.model.LogFormatProfile
import com.indagium.utils.DEFAULT_SPLIT_POSTFIX
import com.indagium.utils.JsonRecordMachine
import com.indagium.utils.MAX_SPLIT_RECORD_CHARS
import com.indagium.utils.ProfileRecord
import com.indagium.utils.SPLIT_PROMPT_BYTES
import com.indagium.utils.parseLogContent
import com.indagium.utils.parseLogcat
import com.indagium.utils.parseProfileLines
import com.indagium.utils.planSplitOutputs
import com.indagium.utils.requiresSplitPrompt
import com.indagium.utils.splitProfileRecordsToFiles
import com.indagium.utils.splitStreamToFiles
import com.indagium.utils.suggestedSplitPartCount
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import java.io.ByteArrayInputStream
import java.io.File
import kotlin.io.path.createTempDirectory
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

private const val OVERSIZED_EVENT_CHARS = 300_000

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
        val source = "\ntime,message\n1,\"first line\nsecond line\"\n2,plain\n3,last\n"
        val directory = createTempDirectory("openlog-profile-csv-split").toFile()
        val outputs = planSplitOutputs("sample.csv", directory, "part", 3)

        splitProfileRecordsToFiles(ByteArrayInputStream(source.toByteArray()), outputs, source.toByteArray().size.toLong(), profile)

        assertTrue(outputs.drop(1).all { it.readLines().first() == "time,message" })
        val entries = outputs.flatMap { file -> parseProfileLines(file.readLines(), profile).entries }
        assertEquals(listOf("first line\nsecond line", "plain", "last"), entries.map { it.msg })
    }

    @Test
    fun largeMinifiedJsonArraySplitsAtCompleteEvents() {
        val profile = LogFormatProfile(id = "minified-json", name = "Minified JSON", engine = LogFormatEngine.JSON_LINES)
        val records = List(1100) { """{"message":"event-$it ${"x".repeat(1024)}","nested":[1,{"ok":true}]}""" }
        val bytes = records.joinToString(",", "[", "]").toByteArray()
        val directory = createTempDirectory("openlog-minified-split").toFile()
        try {
            val outputs = planSplitOutputs("events.json", directory, "part", 3)
            splitProfileRecordsToFiles(bytes.inputStream(), outputs, bytes.size.toLong(), profile)

            val actual = outputs.map { file -> file.inputStream().use { parseLogContent(it, profile = profile).entries } }
            assertTrue(actual.all { it.isNotEmpty() })
            assertEquals(records, actual.flatten().map { it.rawRecord })
        } finally {
            directory.deleteRecursively()
        }
    }

    @Test
    fun continuationProfilesSplitWithoutOrphaningTheirStackTraces() {
        val syslog = BUILTIN_LOG_FORMAT_PROFILES.single { it.id == "syslog-rfc3164" }
        val regex = LogFormatProfile(
            id = "regex-continuation",
            name = "Regex with continuations",
            engine = LogFormatEngine.REGEX,
            regexPattern = "^(\\d+): (.*)$",
            fieldMappings = mapOf(LogFieldRole.MESSAGE to "2"),
            joinUnmatchedLines = true,
        )
        val directory = createTempDirectory("openlog-continuation-split").toFile()
        try {
            for ((profile, header) in listOf(syslog to "<34>Oct 11 22:14:15 host app: ", regex to "1: ")) {
                val source = (1..10).joinToString("") { "$header$it\n  stack trace line $it\n" }
                val bytes = source.toByteArray()
                val outputs = planSplitOutputs("${profile.id}.log", directory, "part", 2)
                splitProfileRecordsToFiles(bytes.inputStream(), outputs, bytes.size.toLong(), profile)

                assertTrue(outputs.all { it.length() > 0 })
                assertEquals(source, outputs.joinToString("") { it.readText() })
                val actual = outputs.flatMap { parseProfileLines(it.readLines(), profile).entries }
                assertEquals(parseProfileLines(source.lines(), profile).entries.map { it.rawRecord }, actual.map { it.rawRecord })
                assertEquals(10, actual.size)
            }
        } finally {
            directory.deleteRecursively()
        }
    }

    @Test
    fun laterHeaderlessCsvAndRegexPartsRetainUtf16Encoding() {
        val csv = LogFormatProfile(id = "utf16-csv", name = "UTF-16 CSV", engine = LogFormatEngine.CSV, hasHeader = false)
        val regex = LogFormatProfile(
            id = "utf16-regex",
            name = "UTF-16 Regex",
            engine = LogFormatEngine.REGEX,
            regexPattern = "^漢(.*)$",
            multilineStartPattern = "^漢",
        )
        val record = "漢".repeat(5000)
        val directory = createTempDirectory("openlog-profile-utf16-split").toFile()
        try {
            for (profile in listOf(csv, regex)) {
                val bytes = "\uFEFF$record\n$record\n".toByteArray(Charsets.UTF_16LE)
                val outputs = planSplitOutputs("${profile.id}.log", directory, "part", 2)
                splitProfileRecordsToFiles(bytes.inputStream(), outputs, bytes.size.toLong(), profile)

                val actual = outputs.map { file ->
                    file.inputStream().use { parseLogContent(it, profile = profile).entries.map { entry -> entry.rawRecord } }
                }
                assertEquals(listOf(listOf(record), listOf(record)), actual, profile.name)
            }
        } finally {
            directory.deleteRecursively()
        }
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
    fun splitUtf16JsonIgnoresNewlineBytesAcrossCodeUnits() {
        val profile = LogFormatProfile(id = "utf16-json", name = "UTF-16 JSON", engine = LogFormatEngine.JSON_LINES)
        val directory = createTempDirectory("openlog-profile-json-code-unit-split").toFile()
        try {
            for ((charset, text) in listOf(Charsets.UTF_16BE to "\u0100\u0A01", Charsets.UTF_16LE to "\u0A01\u0100")) {
                val records = listOf("""{"message":"before $text after"}""", """{"message":"next line"}""")
                val bytes = records.joinToString("\n", prefix = "\uFEFF", postfix = "\n").toByteArray(charset)
                val outputs = planSplitOutputs("${charset.name()}.json", directory, "part", 2)

                splitProfileRecordsToFiles(ByteArrayInputStream(bytes), outputs, bytes.size.toLong(), profile)

                val actual = outputs.map { file ->
                    file.inputStream().use { parseLogContent(it, profile = profile).entries.map { entry -> entry.rawRecord } }
                }
                assertEquals(records.map { listOf(it) }, actual, charset.name())
            }
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

    private val bigJsonProfile = LogFormatProfile(id = "big-json", name = "Big JSON", engine = LogFormatEngine.JSON_LINES)

    /** Events around two over-256K ones: one on a single line, one pretty-printed over several lines. */
    private fun bigJsonEvents(): List<String> {
        val big = "y".repeat(OVERSIZED_EVENT_CHARS)
        return listOf(
            """{"message":"small-1"}""",
            """{"message":"$big","n":1}""",
            """{"message":"small-2"}""",
            "{\n  \"message\": \"$big\",\n  \"nested\": {\n    \"ok\": true\n  }\n}",
            """{"message":"small-3"}""",
        )
    }

    /** The events a split part holds, read the way the splitter reads (the normal parser would cut the oversized ones). */
    private fun eventsOf(text: String): List<String> {
        val machine = JsonRecordMachine(bigJsonProfile, mutableListOf(), 0, splitMode = true)
        val records = ArrayList<ProfileRecord>()
        text.lines().forEach { machine.accept(it, records) }
        machine.finish(records)
        return records.map { it.raw }
    }

    @Test
    fun anOversizedEventInAJsonLinesFileIsCarriedWholeIntoItsPart() {
        val events = bigJsonEvents()
        val source = events.joinToString("\n", postfix = "\n").toByteArray()
        val directory = createTempDirectory("openlog-big-json-lines").toFile()
        try {
            val outputs = planSplitOutputs("events.jsonl", directory, "part", 3)
            splitProfileRecordsToFiles(source.inputStream(), outputs, source.size.toLong(), bigJsonProfile)

            // Not one byte lost or repeated: the parts are the source, cut between events.
            assertEquals(source.toList(), outputs.flatMap { it.readBytes().toList() })
            assertEquals(events, outputs.flatMap { eventsOf(it.readText()) })
            // The normal parser's 256K policy is its own: every part still opens, and the small events read as before.
            val opened = outputs.flatMap { file -> file.inputStream().use { parseLogContent(it, profile = bigJsonProfile).entries } }
            assertTrue(opened.map { it.rawRecord }.containsAll(listOf("""{"message":"small-1"}""", """{"message":"small-3"}""")))
        } finally {
            directory.deleteRecursively()
        }
    }

    @Test
    fun anOversizedEventInARootArrayIsCarriedWholeIntoAnArrayPart() {
        val events = bigJsonEvents()
        val source = events.joinToString(",\n", "[", "]\n").toByteArray()
        val directory = createTempDirectory("openlog-big-json-array").toFile()
        try {
            val outputs = planSplitOutputs("events.json", directory, "part", 3)
            splitProfileRecordsToFiles(source.inputStream(), outputs, source.size.toLong(), bigJsonProfile)

            val parts = outputs.map { it.readText() }
            assertTrue(parts.all { it.trimStart().startsWith("[") && it.trimEnd().endsWith("]") }, "every part is an array")
            assertEquals(events.size, parts.sumOf { Json.parseToJsonElement(it).jsonArray.size }, "every event is in exactly one part")
            assertEquals(events, parts.flatMap { eventsOf(it) })
        } finally {
            directory.deleteRecursively()
        }
    }

    @Test
    fun anEventOverTheSplitLimitFailsTheSplitNamingTheLimitAndLeavesNoParts() {
        val source = ("{\"message\":\"" + "z".repeat(MAX_SPLIT_RECORD_CHARS + 10) + "\"}\n").toByteArray()
        val directory = createTempDirectory("openlog-huge-json").toFile()
        try {
            val outputs = planSplitOutputs("events.jsonl", directory, "part", 2)
            val failure = assertFailsWith<IllegalArgumentException> {
                splitProfileRecordsToFiles(source.inputStream(), outputs, source.size.toLong(), bigJsonProfile)
            }
            assertTrue(failure.message.orEmpty().contains(MAX_SPLIT_RECORD_CHARS.toString()), failure.message)
            assertTrue(outputs.none { it.exists() })
        } finally {
            directory.deleteRecursively()
        }
    }

    private val numberedRecords = LogFormatProfile(
        id = "split-rare-starts",
        name = "Split rare starts",
        engine = LogFormatEngine.REGEX,
        regexPattern = "^(\\d+): (.*)$",
        multilineStartPattern = "^\\d+: ",
        fieldMappings = mapOf(LogFieldRole.MESSAGE to "2"),
    )

    @Test
    fun aFileWhoseOnlyRecordStartIsItsFirstLineStillSplitsIntoBoundedParts() {
        val line = "  continuation ${"c".repeat(984)}\n"
        val source = ("1: only header\n" + line.repeat(10_000)).toByteArray()
        val directory = createTempDirectory("openlog-split-rare-starts").toFile()
        try {
            val parts = 5
            val outputs = planSplitOutputs("sample.log", directory, "part", parts)
            splitProfileRecordsToFiles(source.inputStream(), outputs, source.size.toLong(), numberedRecords)

            val target = (source.size + parts - 1) / parts
            val sizes = outputs.map { it.length() }
            assertEquals(source.toList(), outputs.flatMap { it.readBytes().toList() }, "no byte lost or repeated")
            // Cut a megabyte past the target at the latest, so the file is spread over several parts, not left in the first.
            assertTrue(sizes.dropLast(1).all { it <= target + (1 shl 20) + line.length }, sizes.toString())
            assertTrue(sizes.count { it > 0 } >= 3, "the parts: $sizes")
            assertTrue(outputs.drop(1).filter { it.length() > 0 }.all { it.readText().startsWith("  continuation") }, "cut at line boundaries")
        } finally {
            directory.deleteRecursively()
        }
    }

    @Test
    fun recordsThatStartOftenStillCutOnlyBeforeAHeader() {
        val source = (1..400).joinToString("") { "$it: record $it\n  detail a\n  detail b\n  detail c\n" }.toByteArray()
        val directory = createTempDirectory("openlog-split-frequent-starts").toFile()
        try {
            val outputs = planSplitOutputs("sample.log", directory, "part", 4)
            splitProfileRecordsToFiles(source.inputStream(), outputs, source.size.toLong(), numberedRecords)

            assertEquals(source.toList(), outputs.flatMap { it.readBytes().toList() })
            assertTrue(outputs.all { it.length() > 0 })
            assertTrue(outputs.all { it.readText().matches(Regex("(?s)^\\d+: record.*")) }, "every part starts at a record header")
            assertEquals(400, outputs.sumOf { file -> parseProfileLines(file.readLines(), numberedRecords).entries.size })
        } finally {
            directory.deleteRecursively()
        }
    }
}
