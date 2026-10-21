package com.indagium.capture

import com.indagium.utils.parseLogcat
import java.io.File
import java.time.Instant
import kotlin.io.path.createTempDirectory
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertTrue

@Suppress("MagicNumber")
class CaptureSparseLegacyConversionTest {
    @Test
    fun nonrepresentableLegacyTimingBecomesUncertainInBothConversionPaths() = withFixture { root ->
        val source = legacySession(root, listOf(0, 60_000, 120_000), trailingNewline = true)
        val old = CaptureArchiveExporter().finalizeSessionInPlace(source)
        assertEquals(4, old.descriptor.formatVersion)
        assertEquals(60_000L, old.timeline?.rows?.get(1)?.elapsedMs, "opening an old capture must preserve its old timing")
        val imported = importedCaptureSnapshot(
            old, File(source.directory, CAPTURE_DESCRIPTOR_NAME), parseLogcat(old.logFile),
            File(root, "cache"), File(root, "cursors"),
        )
        assertTrue(requireNotNull(imported.session.syncMetadataOverride).segments.all { it.reliability == CaptureSyncReliability.UNCERTAIN })
        for ((index, session) in listOf(source, imported.session).withIndex()) {
            val result = CaptureArchiveExporter().export(
                session,
                CaptureExportRequest(File(root, "converted-$index.zip"), includeVideo = false, cutoffElapsedMs = 120_000),
            )
            val opened = CaptureArchiveReader.open(result.file, File(root, "opened-$index"))
            assertEquals(3, parseLogcat(opened.logFile).size)
            assertTrue(requireNotNull(opened.sparseSyncMetadata).uncertainBoundary)
            assertTrue(result.message.contains("uncertain", ignoreCase = true), result.message)
            assertTrue(requireNotNull(opened.sparseSyncMetadata).segments.all { it.reliability == CaptureSyncReliability.UNCERTAIN })
        }
    }

    @Test
    fun completeLegacyEofRowWithoutNewlineSurvivesTwoExports() = withFixture { root ->
        val source = legacySession(root, listOf(0, 1_000, 2_000), trailingNewline = false)
        val originalBytes = source.logFile.readBytes()
        val first = CaptureArchiveExporter().export(
            source,
            CaptureExportRequest(File(root, "first.zip"), includeVideo = false, cutoffElapsedMs = 2_000),
        )
        val opened = CaptureArchiveReader.open(first.file, File(root, "opened"))
        assertContentEquals(originalBytes, opened.logFile.readBytes())
        val snapshot = importedCaptureSnapshot(
            opened, first.file, parseLogcat(opened.logFile), File(root, "cache"), File(root, "cursors"),
        )
        val second = CaptureArchiveExporter().export(
            snapshot.session,
            CaptureExportRequest(File(root, "second.zip"), includeVideo = false, cutoffElapsedMs = 2_000),
        )
        val child = CaptureArchiveReader.open(second.file, File(root, "child"))
        assertEquals(3, parseLogcat(child.logFile).size)
        assertContentEquals(originalBytes, child.logFile.readBytes())
        assertContentEquals(originalBytes, source.logFile.readBytes())
    }

    private fun legacySession(root: File, elapsed: List<Int>, trailingNewline: Boolean): CaptureSession {
        val session = CaptureSession(
            id = "legacy",
            directory = File(root, "session"),
            device = CaptureDevice("test-device", "device"),
            settings = CaptureSettings(recordVideo = false, freeSpaceReserveBytes = 0),
            startedEpochMs = Instant.parse("2024-01-01T10:00:00Z").toEpochMilli(),
            elapsedMs = elapsed.last().toLong(),
            status = CaptureStatus.STOPPED,
        )
        session.logFile.parentFile.mkdirs()
        session.indexFile.parentFile.mkdirs()
        var offset = 0L
        session.logFile.outputStream().use { log ->
            session.indexFile.bufferedWriter().use { index ->
                elapsed.forEachIndexed { ordinal, time ->
                    val suffix = if (ordinal < elapsed.lastIndex || trailingNewline) "\n" else ""
                    val raw = "01-01 10:00:0$ordinal.000  100  101 I Tag: row-$ordinal$suffix".toByteArray()
                    log.write(raw)
                    index.appendLine("{\"byteOffset\":$offset,\"byteLength\":${raw.size},\"elapsedMs\":$time,\"rowOrdinal\":${ordinal + 1}}")
                    offset += raw.size
                }
            }
        }
        return session
    }

    private fun withFixture(test: (File) -> Unit) {
        val root = createTempDirectory("capture-sparse-legacy").toFile()
        try {
            test(root)
        } finally {
            root.deleteRecursively()
        }
    }
}
