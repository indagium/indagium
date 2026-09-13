package com.indagium

import com.indagium.model.LogFormat
import com.indagium.utils.LogContentKind
import com.indagium.utils.classifyLogContent
import com.indagium.utils.parseLogContent
import com.indagium.utils.splitDltStreamToFiles
import com.indagium.utils.splitStreamToFiles
import java.io.ByteArrayInputStream
import java.io.File
import kotlin.io.path.createTempDirectory
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

private const val FRAME_COUNT = 50
private const val PART_COUNT = 3

class DltSplitterTest {
    private fun outputs(dir: File, ext: String, count: Int = PART_COUNT): List<File> =
        (1..count).map { File(dir, "part_$it$ext") }

    @Test
    fun storageStreamSplitsIntoStandaloneDltPartsThatReproduceTheSourceBytes() {
        val dir = createTempDirectory("dlt-split-storage").toFile()
        val frames = (1..FRAME_COUNT).map { i -> dltStorageHeader(ecu = "ECU1") + dltTestFrame("frame $i") }
        val source = frames.reduce { acc, f -> acc + f }
        val outs = outputs(dir, ".dlt")

        val written = splitDltStreamToFiles(ByteArrayInputStream(source), outs, source.size.toLong(), LogContentKind.DLT_STORAGE)

        assertEquals(source.toList(), written.flatMap { it.readBytes().toList() })
        var totalRows = 0
        written.forEach { part ->
            val parsed = part.inputStream().use { parseLogContent(it, fileName = part.name) }
            assertEquals(LogFormat.DLT, parsed.format)
            assertTrue(parsed.entries.none { it.tag == "RAW" }, "unexpected marker row in ${part.name}: ${parsed.entries}")
            totalRows += parsed.entries.size
        }
        assertEquals(FRAME_COUNT, totalRows)
    }

    @Test
    fun rawFrameStreamSplitsIntoStandaloneDltPartsThatReproduceTheSourceBytes() {
        val dir = createTempDirectory("dlt-split-raw").toFile()
        val frames = (1..FRAME_COUNT).map { i -> dltTestFrame("frame $i") }
        val source = frames.reduce { acc, f -> acc + f }
        val outs = outputs(dir, ".dlt")

        val written = splitDltStreamToFiles(ByteArrayInputStream(source), outs, source.size.toLong(), LogContentKind.DLT_RAW)

        assertEquals(source.toList(), written.flatMap { it.readBytes().toList() })
        var totalRows = 0
        written.forEach { part ->
            val parsed = part.inputStream().use { parseLogContent(it, fileName = part.name) }
            assertEquals(LogFormat.DLT, parsed.format)
            assertTrue(parsed.entries.none { it.tag == "RAW" }, "unexpected marker row in ${part.name}: ${parsed.entries}")
            totalRows += parsed.entries.size
        }
        assertEquals(FRAME_COUNT, totalRows)
    }

    @Test
    fun trailingGarbageAfterTheLastValidFrameIsPreservedByteExactly() {
        val dir = createTempDirectory("dlt-split-garbage").toFile()
        val frames = (1..10).map { i -> dltStorageHeader(ecu = "ECU1") + dltTestFrame("frame $i") }
        val garbage = ByteArray(37) { (0xDE + it).toByte() } // never matches "DLT" nor a valid HTYP
        val source = frames.reduce { acc, f -> acc + f } + garbage
        val outs = outputs(dir, ".dlt", count = 2)

        val written = splitDltStreamToFiles(ByteArrayInputStream(source), outs, source.size.toLong(), LogContentKind.DLT_STORAGE)

        assertEquals(source.toList(), written.flatMap { it.readBytes().toList() })
        assertTrue(written.last().readBytes().let { tail -> String(tail).contains(String(garbage)) })
    }

    @Test
    fun csvHeaderIsRepeatedAtTheTopOfEveryPartAfterTheFirst() {
        val dir = createTempDirectory("dlt-split-csv").toFile()
        val header = "Time,ECU,AppId,ContextId,Type,Payload"
        val rowCount = 24
        val rows = (1..rowCount).map { i -> "2026-01-0${1 + i % 9} 03:04:0${i % 10},ECU1,APP1,CTX1,ERROR,payload $i" }
        val text = (listOf(header) + rows).joinToString("\n", postfix = "\n")
        val source = text.toByteArray(Charsets.UTF_8)
        val outs = outputs(dir, ".csv")
        val headerBytes = (header + "\n").toByteArray(Charsets.UTF_8)

        val written = splitStreamToFiles(ByteArrayInputStream(source), outs, source.size.toLong(), headerBytes)

        var totalDataRows = 0
        written.forEachIndexed { index, part ->
            val bytes = part.readBytes()
            val sample = bytes.copyOf(minOf(bytes.size, 8 * 1024))
            val kind = classifyLogContent(sample, atEof = bytes.size <= sample.size, fileName = part.name)
            assertEquals(LogContentKind.DLT_VIEWER_CSV, kind, "part $index did not classify as CSV")
            assertTrue(String(bytes, Charsets.UTF_8).startsWith(header), "part $index missing repeated header")

            val parsed = part.inputStream().use { parseLogContent(it, fileName = part.name) }
            assertEquals(LogFormat.DLT, parsed.format)
            totalDataRows += parsed.entries.size
        }
        assertEquals(rowCount, totalDataRows)
    }

    @Test
    fun protocolV2IsRefused() {
        val dir = createTempDirectory("dlt-split-v2").toFile()
        val outs = outputs(dir, ".dlt", count = 1)

        assertFailsWith<IllegalArgumentException> {
            splitDltStreamToFiles(ByteArrayInputStream(ByteArray(0)), outs, 0L, LogContentKind.DLT_UNSUPPORTED_V2)
        }
    }
}
