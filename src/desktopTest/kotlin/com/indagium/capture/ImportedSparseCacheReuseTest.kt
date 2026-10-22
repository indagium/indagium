package com.indagium.capture

import com.indagium.utils.parseLogcat
import java.io.File
import java.time.Instant
import kotlin.io.path.createTempDirectory
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

@Suppress("MagicNumber")
class ImportedSparseCacheReuseTest {
    @Test
    fun aSecondOpenOfTheSameLegacyArchiveReusesItsCacheWithoutRewritingIt() = withFixture { root ->
        val imported = finalizedLegacyArchive(root)
        val first = open(root, imported)
        val index = first.session.blockIndexFile
        val sync = File(index.parentFile.parentFile, "timeline/sync.json")
        val oldStamp = System.currentTimeMillis() - 86_400_000L
        assertTrue(index.setLastModified(oldStamp) && sync.setLastModified(oldStamp))
        val indexBytes = index.readBytes()

        val second = open(root, imported)

        assertEquals(oldStamp / 1000, index.lastModified() / 1000, "the block index must not be rewritten")
        assertEquals(oldStamp / 1000, sync.lastModified() / 1000, "the sync manifest must not be rewritten")
        assertTrue(indexBytes.contentEquals(index.readBytes()))
        assertEquals(first.session.syncMetadataOverride, second.session.syncMetadataOverride)
        assertEquals(first.session.frozenBlockIndexBytes, second.session.frozenBlockIndexBytes)
    }

    @Test
    fun aDamagedOrMismatchedCacheIsRebuiltInsteadOfReused() = withFixture { root ->
        val imported = finalizedLegacyArchive(root)
        val first = open(root, imported)
        val sync = File(first.session.blockIndexFile.parentFile.parentFile, "timeline/sync.json")
        val oldStamp = System.currentTimeMillis() - 86_400_000L
        sync.writeText(sync.readText().take(30)) // truncated JSON no longer parses
        sync.setLastModified(oldStamp)

        val rebuilt = open(root, imported)

        assertNotEquals(oldStamp / 1000, sync.lastModified() / 1000, "a damaged manifest must be rewritten")
        assertEquals(first.session.syncMetadataOverride, rebuilt.session.syncMetadataOverride)
    }

    @Test
    fun legacyFlatIndexFilesAreSweptButPerIdentityDirectoriesSurvive() = withFixture { root ->
        val indexes = File(root, "imported-snapshot-index").apply { mkdirs() }
        File(indexes, "abc123.jsonl").writeText("{}")
        File(indexes, "def456.jsonl").writeText("{}")
        val identityDirectory = File(indexes, "0123456789abcdef").apply { mkdirs() }
        File(identityDirectory, "mapping").mkdirs()
        File(identityDirectory, "mapping/blocks.jsonl").writeText("{}")

        assertEquals(2, sweepLegacyImportedIndexFiles(indexes))

        assertEquals(listOf("0123456789abcdef"), indexes.list().orEmpty().toList())
        assertTrue(File(identityDirectory, "mapping/blocks.jsonl").isFile)
        assertEquals(0, sweepLegacyImportedIndexFiles(File(root, "missing")))
    }

    private fun open(root: File, imported: ImportedCapture): ImportedCaptureSnapshot = importedCaptureSnapshot(
        imported,
        File(imported.source, CAPTURE_DESCRIPTOR_NAME),
        parseLogcat(imported.logFile),
        File(root, "cache"),
        File(root, "cursors"),
    )

    private fun finalizedLegacyArchive(root: File): ImportedCapture {
        val elapsed = listOf(0, 60_000, 120_000)
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
                    val raw = "01-01 10:00:0$ordinal.000  100  101 I Tag: row-$ordinal\n".toByteArray()
                    log.write(raw)
                    index.appendLine("{\"byteOffset\":$offset,\"byteLength\":${raw.size},\"elapsedMs\":$time,\"rowOrdinal\":${ordinal + 1}}")
                    offset += raw.size
                }
            }
        }
        val old = CaptureArchiveExporter().finalizeSessionInPlace(session)
        assertEquals(4, old.descriptor.formatVersion)
        return old
    }

    private fun withFixture(test: (File) -> Unit) {
        val root = createTempDirectory("imported-sparse-cache-reuse").toFile()
        try {
            test(root)
        } finally {
            root.deleteRecursively()
        }
    }
}
