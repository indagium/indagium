package com.indagium

import com.indagium.model.LogEntry
import com.indagium.utils.MetadataJsonSectionIndex
import com.indagium.utils.ParsedLog
import com.indagium.utils.loadMetadataJsonSection
import com.indagium.utils.metadataJsonProfile
import com.indagium.utils.parseLogContent
import com.indagium.utils.parseLogFileResultWithProfiles
import java.io.File
import java.nio.file.Files
import java.util.zip.GZIPOutputStream
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

private const val T0 = 1_790_000_000_000_000_000L

@Suppress("MagicNumber") // Synthetic wire timestamps and record counts.
class MetadataJsonSectionIndexTest {
    private fun record(
        identity: String,
        title: String,
        time: Long?,
        newline: String,
        timestamp: Long? = null,
    ): String {
        val metadata = buildList {
            add("\"identity\": \"$identity\"")
            add("\"source\": \"Démo 🚀\"")
            if (time != null) add("\"system_time\": $time")
            if (timestamp != null) add("\"timestamp\": $timestamp")
            if (time == null && timestamp == null) add("\"title\": \"$title\"")
        }
        return "{$newline  \"metadata\": {${metadata.joinToString(", ")}},$newline" +
            "  \"Summary\": {\"Note\": \"$title é\"},$newline  \"Raw Bytes\": \"0001ff\"$newline}"
    }

    /** Several sections with interleaved times, ties, untimed records, a summary object and decode-notice / unavailable sections. */
    private fun document(newline: String = "\n"): String {
        fun r(identity: String, title: String, time: Long?, timestamp: Long? = null) = record(identity, title, time, newline, timestamp)
        val hci = listOf(
            r("HCI.A", "hci-late", T0 + 900), r("HCI.B", "hci-tie", T0 + 500), r("HCI.C", "hci-early", T0 + 100),
            r("HCI.D", "hci-untimed", null), r("HCI.E", "hci-relative", null, timestamp = 7_000_000),
        )
        val l2cap = listOf(r("L2.A", "l2-tie", T0 + 500), r("L2.B", "l2-mid", T0 + 300), r("L2.C", "l2-tie-again", T0 + 500))
        val events = listOf(r("EV.A", "event-first", T0 + 50), r("EV.B", "event-last", T0 + 2000), r("EV.C", "event-untimed", null))
        return "{$newline\"Tethered Bluetooth\": {$newline\"HCI\": [${hci.joinToString(",$newline")}],$newline" +
            "\"L2CAP\": [${l2cap.joinToString(",$newline")}],$newline\"RFCOMM\": []},$newline" +
            "\"Other\": {$newline\"Events\": [${events.joinToString(",$newline")}],$newline" +
            "\"Counters\": {\"packets\": 3, \"note\": \"résumé\"}},$newline" +
            "\"indagium_section_status\": {\"Tethered Bluetooth / Decoded\": {\"decode_notice\": \"Partial\", \"decode_source\": \"tool\"}," +
            "\"Tethered Bluetooth / Missing\": {\"unavailable_reason\": \"Not captured\"}}}$newline"
    }

    private fun write(directory: File, name: String, text: String, bom: Boolean = false): File = File(directory, name).apply {
        writeBytes((if (bom) byteArrayOf(0xEF.toByte(), 0xBB.toByte(), 0xBF.toByte()) else byteArrayOf()) + text.toByteArray())
    }

    private fun withDirectory(block: (File) -> Unit) {
        val directory = Files.createTempDirectory("metadata-json-index-").toFile()
        try {
            block(directory)
        } finally {
            directory.deleteRecursively()
        }
    }

    private fun full(file: File): ParsedLog =
        parseLogFileResultWithProfiles(file, emptyList(), metadataJsonProfile())

    /** The parse every section switch used before: text reader, no index. */
    private fun reference(file: File): ParsedLog =
        file.inputStream().use {
            parseLogContent(it, fileName = file.name, profile = metadataJsonProfile(), sourceClockId = file.absolutePath)
        }

    private fun assertSameRows(expected: List<LogEntry>, actual: List<LogEntry>, label: String) {
        assertEquals(expected.size, actual.size, "$label row count")
        expected.zip(actual).forEachIndexed { row, (a, b) ->
            assertEquals(a.id, b.id, "$label id of row $row")
            assertEquals(a.ts, b.ts, "$label ts of row $row")
            assertEquals(a.msg, b.msg, "$label msg of row $row")
            assertEquals(a.recordMetadata?.profileFields, b.recordMetadata?.profileFields, "$label profileFields of row $row")
            assertEquals(a.recordMetadata?.rawRecord, b.recordMetadata?.rawRecord, "$label rawRecord of row $row")
            assertEquals(a.recordMetadata?.sourceStartLine, b.recordMetadata?.sourceStartLine, "$label start line of row $row")
            assertEquals(a.recordMetadata?.sourceEndLine, b.recordMetadata?.sourceEndLine, "$label end line of row $row")
            assertEquals(a.recordMetadata?.displayColumns, b.recordMetadata?.displayColumns, "$label displayColumns of row $row")
            assertEquals(a, b, "$label whole entry of row $row")
        }
    }

    private fun assertEveryIndexedSectionMatches(file: File) {
        val reference = reference(file)
        val parsed = full(file)
        assertEquals(reference.entries, parsed.entries, "the byte-stream parse must give the reader parse's rows")
        assertEquals(reference.sections.map { it.description() }, parsed.sections.map { it.description() })
        assertEquals(reference.diagnostics, parsed.diagnostics)
        assertEquals(reference.sourceLineCount, parsed.sourceLineCount)
        assertEquals(reference.selectedSectionId, parsed.selectedSectionId)
        val index = assertNotNull(parsed.metadataSectionIndex, "a plain valid file is indexed")
        assertTrue(reference.sections.size >= 5)
        assertEquals(1, reference.sections.single { it.id == "Other / Counters" }.entries.size, "the summary object is a section row")
        assertEquals("Not captured", reference.sections.single { it.id == "Tethered Bluetooth / Missing" }.unavailableReason)
        assertTrue(reference.sections.single { it.id == "Tethered Bluetooth / RFCOMM" }.entries.isEmpty())
        val hciIds = reference.sections.single { it.id == "Tethered Bluetooth / HCI" }.entries.map { it.id }
        assertTrue(hciIds.zipWithNext().any { (a, b) -> b - a > 1 }, "HCI ids come from the global time order, so they are not consecutive")
        reference.sections.forEach { section ->
            val loaded = assertNotNull(loadMetadataJsonSection(file, index, section.id, metadataJsonProfile()), "section ${section.id} loads from the index")
            assertSameRows(section.entries, loaded, section.id)
        }
    }

    @Test
    fun everySectionLoadedFromTheIndexEqualsTheFullParse() = withDirectory { directory ->
        assertEveryIndexedSectionMatches(write(directory, "export.json", document()))
    }

    @Test
    fun windowsLineEndingsAndAByteOrderMarkKeepIdsAndLines() = withDirectory { directory ->
        assertEveryIndexedSectionMatches(write(directory, "crlf.json", document("\r\n"), bom = true))
    }

    @Test
    fun aTopLevelArrayOfRecordsIsIndexedAsOneSection() = withDirectory { directory ->
        val rows = (0 until 6).joinToString(",\n") { record("Top.$it", "top-$it", T0 + (6 - it) * 10L, "\n") }
        val file = write(directory, "records.json", "[\n$rows\n]\n")
        val reference = reference(file)
        val index = assertNotNull(full(file).metadataSectionIndex)
        val section = reference.sections.single()
        assertSameRows(section.entries, assertNotNull(loadMetadataJsonSection(file, index, section.id, metadataJsonProfile())), "top")
    }

    @Test
    fun aModifiedFileIsNotUsedThroughTheIndex() = withDirectory { directory ->
        val file = write(directory, "export.json", document())
        val index = assertNotNull(full(file).metadataSectionIndex)
        assertNotNull(loadMetadataJsonSection(file, index, "Tethered Bluetooth / HCI", metadataJsonProfile()))
        file.appendText(" ")
        assertNull(loadMetadataJsonSection(file, index, "Tethered Bluetooth / HCI", metadataJsonProfile()))
        val sameLength = write(directory, "same.json", document())
        val sameLengthIndex = assertNotNull(full(sameLength).metadataSectionIndex)
        assertTrue(sameLength.setLastModified(sameLength.lastModified() + 5_000))
        assertNull(loadMetadataJsonSection(sameLength, sameLengthIndex, "Tethered Bluetooth / HCI", metadataJsonProfile()))
        assertNull(loadMetadataJsonSection(file, index, "No such section", metadataJsonProfile()))
    }

    @Test
    fun aSameLengthRewriteAfterTheOpeningStampCheckDiscardsIndexedRows() = withDirectory { directory ->
        val original = document()
        val file = write(directory, "export.json", original)
        val index = assertNotNull(full(file).metadataSectionIndex)
        val originalLength = file.length()
        val changingFile = object : File(file.absolutePath) {
            private var rewritePending = true

            override fun lastModified(): Long {
                val modified = super.lastModified()
                if (rewritePending) {
                    rewritePending = false
                    file.writeText(original.replace("hci-late", "hci-move"))
                    assertEquals(originalLength, file.length())
                    assertTrue(file.setLastModified(modified + 5_000))
                }
                return modified
            }
        }

        assertNull(loadMetadataJsonSection(changingFile, index, "Tethered Bluetooth / HCI", metadataJsonProfile()))
        assertTrue(file.readText().contains("hci-move"))
    }

    @Test
    fun aCompressedFileIsParsedTheOrdinaryWayWithoutAnIndex() = withDirectory { directory ->
        val file = File(directory, "export.json.gz")
        GZIPOutputStream(file.outputStream()).use { it.write(document().toByteArray()) }
        val parsed = full(file)
        assertNull(parsed.metadataSectionIndex)
        assertTrue(parsed.entries.isNotEmpty())
    }

    @Test
    fun aDamagedOrNonUtf8FileFallsBackToTheOrdinaryParse() = withDirectory { directory ->
        val truncated = write(directory, "truncated.json", document().dropLast(40))
        assertNull(full(truncated).metadataSectionIndex)
        assertEquals(reference(truncated).entries, full(truncated).entries)
        val invalid = File(directory, "invalid.json").apply {
            writeBytes(document().toByteArray() + byteArrayOf(0xC3.toByte()))
        }
        assertNull(full(invalid).metadataSectionIndex)
        val utf16 = File(directory, "utf16.json").apply { writeBytes(document().toByteArray(Charsets.UTF_16LE)) }
        assertNull(full(utf16).metadataSectionIndex)
    }

    private fun runCount(index: MetadataJsonSectionIndex, section: String): Int = assertNotNull(index.records[section]).runCount

    private fun assertSectionsMatch(file: File): MetadataJsonSectionIndex {
        val reference = reference(file)
        val index = assertNotNull(full(file).metadataSectionIndex, "a plain valid file is indexed")
        assertTrue(reference.sections.isNotEmpty())
        reference.sections.forEach { section ->
            val loaded = assertNotNull(loadMetadataJsonSection(file, index, section.id, metadataJsonProfile()), "section ${section.id} loads")
            assertSameRows(section.entries, loaded, section.id)
        }
        return index
    }

    @Test
    fun aNormalSectionIsOneRunWhateverItsSize() = withDirectory { directory ->
        val index = assertEveryIndexedSectionMatchesAndReturn(write(directory, "export.json", document()))
        assertEquals(1, runCount(index, "Tethered Bluetooth / HCI"))
        assertEquals(1, runCount(index, "Tethered Bluetooth / L2CAP"))
        assertEquals(1, runCount(index, "Other / Events"))
    }

    private fun assertEveryIndexedSectionMatchesAndReturn(file: File): MetadataJsonSectionIndex {
        assertEveryIndexedSectionMatches(file)
        return assertNotNull(full(file).metadataSectionIndex)
    }

    @Test
    fun recordsSeparatedBySomethingElseInOneArrayFormSeveralRuns() = withDirectory { directory ->
        listOf("\n", "\r\n").forEach { newline ->
            fun r(id: String, time: Long) = record(id, id, time, newline)
            val items = listOf(
                r("A.1", T0 + 30), r("A.2", T0 + 10), "{\"note\": \"not a record\"}", r("A.3", T0 + 20), "7", r("A.4", T0 + 5),
                r("A.5", T0 + 40), "[1, 2]", "\"text\"", r("A.6", T0 + 25),
            )
            val other = listOf(r("B.1", T0 + 15), r("B.2", T0 + 35))
            val text = "{$newline\"S\": {$newline\"A\": [${items.joinToString(",$newline")}],$newline" +
                "\"B\": [${other.joinToString(",$newline")}]$newline}}$newline"
            val index = assertSectionsMatch(write(directory, "runs-${newline.length}.json", text))
            assertEquals(4, runCount(index, "S / A"), "A.1 A.2 | A.3 | A.4 A.5 | A.6: the note, 7 and [1, 2] + text each break the run")
            assertEquals(1, runCount(index, "S / B"))
        }
    }

    @Test
    fun objectsSpanningManyLinesWithBlankAndCarriageReturnOnlyBreaksKeepTheirLines() = withDirectory { directory ->
        val gaps = listOf(",\r\r\r", " ,\n\n\n  \n", "\r\n,\r\n\r\n", ",\r\n\r  \n", "\n,\r", ",\n\r\n\r")
        listOf("\n", "\r", "\r\n").forEach { newline ->
            val rows = (0 until 12).map { record("Gap.$it", "gap-$it", T0 + (12 - it) * 7L, newline) }
            val body = buildString {
                rows.forEachIndexed { i, row ->
                    append(row)
                    if (i < rows.lastIndex) append(gaps[i % gaps.size])
                }
            }
            val text = "$newline$newline{$newline\"S\"$newline:$newline{\"A\"\r:\r[\n\r\n$body$newline]}}$newline"
            val index = assertSectionsMatch(write(directory, "gaps-${newline.length}-${newline.hashCode()}.json", text, bom = newline == "\r\n"))
            assertEquals(1, runCount(index, "S / A"))
        }
    }

    @Test
    fun aSectionArrayHoldingNestedSectionsKeepsItsRecordsAndLines() = withDirectory { directory ->
        fun r(id: String, time: Long) = record(id, id, time, "\n")
        // Records that themselves contain a section array: the inner records are part of the outer record, not rows of their own.
        val outer = (0 until 4).map { i ->
            val inner = (0 until 2).joinToString(",\n") { r("In.$i.$it", T0 + 1000L * i + it) }
            val base = r("Out.$i", T0 + 100L * i)
            base.dropLast(2) + ",\n\"Inner\": [$inner]\n}"
        }
        // Non-record elements, each holding a section array of its own: one nested section, one run per element.
        val wrappers = (0 until 3).map { i ->
            "{\"label\": \"w$i\", \"Items\": [${(0 until 2 + i).joinToString(",\n") { r("W.$i.$it", T0 + 5000L + 10L * i + it) }}]}"
        }
        val text = "{\n\"S\": {\n\"Records\": [${outer.joinToString(",\n")}],\n\"Wrapped\": [${wrappers.joinToString(",\n")}]}}\n"
        val file = write(directory, "nested.json", text)
        val reference = reference(file)
        assertTrue(reference.sections.any { it.entries.isNotEmpty() })
        val index = assertSectionsMatch(file)
        assertEquals(1, runCount(index, "S / Records"))
        val wrapped = reference.sections.filter { "Items" in it.id }
        assertTrue(wrapped.isNotEmpty() && wrapped.all { it.entries.size == 9 }, "the wrapped records are one nested section")
        wrapped.forEach { assertEquals(3, runCount(index, it.id)) }
    }

    @Test
    fun anIndexStoresAboutFourBytesPerRecord() = withDirectory { directory ->
        val count = 10_000
        val rows = (0 until count).joinToString(",\n") { record("Big.$it", "big-$it", T0 + (it * 37L) % 9_973, "\n") }
        val file = write(directory, "big.json", "{\n\"S\": {\"A\": [$rows]}}\n")
        val index = assertNotNull(full(file).metadataSectionIndex)
        assertEquals(1, runCount(index, "S / A"))
        assertEquals(count * 4L + 24L, index.estimatedBytes, "ids only per object plus one 24-byte run")
        assertTrue(index.estimatedBytes < count * 6L, "under six bytes per object")
        val section = reference(file).sections.single()
        assertSameRows(section.entries, assertNotNull(loadMetadataJsonSection(file, index, section.id, metadataJsonProfile())), "big")
    }

    @Test
    fun anIndexCostsFourBytesPerObjectPlusOneRunPerStretch() = withDirectory { directory ->
        val index = assertNotNull(full(write(directory, "export.json", document())).metadataSectionIndex)
        val objects = 5 + 3 + 3 + 1
        val runs = 4 // HCI, L2CAP, Events and the Counters summary; RFCOMM is empty
        assertEquals(objects * 4L + runs * 24L, index.estimatedBytes)
    }
}
