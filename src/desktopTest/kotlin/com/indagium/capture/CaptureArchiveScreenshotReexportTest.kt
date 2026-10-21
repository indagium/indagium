package com.indagium.capture

import com.indagium.utils.parseLogcat
import java.io.File
import java.util.zip.ZipFile
import kotlin.io.path.createTempDirectory
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals

/** Re-exporting an opened capture archive must carry its screenshots into the child archive. */
@Suppress("MagicNumber") // Fixture timings and sizes, not tunable constants.
class CaptureArchiveScreenshotReexportTest {
    private val shotBytes = byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47, 1, 2, 3, 4)

    @Test
    fun childExportOfAnImportedCaptureKeepsItsScreenshot() {
        val root = createTempDirectory("capture-shot-reexport").toFile()
        try {
            val a = exportParent(root)
            assertEquals(listOf("screenshots/1-screenshot-3500.png"), screenshotEntries(a))

            val snapshot = importSnapshot(root, a, "a")
            assertEquals(1_500L, snapshot.session.screenshotsOverride?.single()?.elapsedMs)
            val b = File(root, "b.zip")
            exportChild(snapshot, b, CaptureRange.ALL)

            assertEquals(listOf("screenshots/1-screenshot-3500.png"), screenshotEntries(b))
            assertContentEquals(shotBytes, entryBytes(b, "screenshots/1-screenshot-3500.png"))

            // Re-exporting the child again keeps the name stable instead of growing prefixes.
            val secondImport = importSnapshot(root, b, "b")
            assertEquals(1_500L, secondImport.session.screenshotsOverride?.single()?.elapsedMs)
            val c = File(root, "c.zip")
            exportChild(secondImport, c, CaptureRange.ALL)
            assertEquals(listOf("screenshots/1-screenshot-3500.png"), screenshotEntries(c))
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun childExportHonoursTheRangeOnTheImportedSessionTimeBase() {
        val root = createTempDirectory("capture-shot-reexport-range").toFile()
        try {
            val snapshot = importSnapshot(root, exportParent(root), "a")
            // Local time base: the screenshot sits at 1500 ms (3500 on the parent session).
            val before = File(root, "before.zip")
            exportChild(snapshot, before, CaptureRange.EXPLICIT, start = 0, end = 1000)
            assertEquals(emptyList(), screenshotEntries(before))

            val around = File(root, "around.zip")
            exportChild(snapshot, around, CaptureRange.EXPLICIT, start = 1000, end = 2000)
            assertEquals(listOf("screenshots/1-screenshot-3500.png"), screenshotEntries(around))
            assertContentEquals(shotBytes, entryBytes(around, "screenshots/1-screenshot-3500.png"))
        } finally {
            root.deleteRecursively()
        }
    }

    /** Live session with rows at 1000/3000/4000 ms and screenshots at 500 (outside) and 3500 ms. */
    private fun exportParent(root: File): File {
        val session = CaptureSession(
            id = "session-1",
            directory = File(root, "session"),
            device = CaptureDevice("serial", "device", "Pixel"),
            settings = CaptureSettings(recordVideo = false, freeSpaceReserveBytes = 0),
            startedEpochMs = 1_700_000_000_000,
            elapsedMs = 6_000,
        )
        val rows = listOf(
            "01-01 10:00:01.000  1  1 I Tag: one\n" to 1_000L,
            "01-01 10:00:03.000  1  1 I Tag: two\n" to 3_000L,
            "01-01 10:00:04.000  1  1 I Tag: three\n" to 4_000L,
        )
        session.logFile.parentFile.mkdirs()
        session.indexFile.parentFile.mkdirs()
        var offset = 0L
        session.logFile.outputStream().use { log ->
            session.indexFile.bufferedWriter().use { index ->
                rows.forEachIndexed { i, (text, elapsed) ->
                    val bytes = text.toByteArray()
                    log.write(bytes)
                    index.append("{\"byteOffset\":$offset,\"byteLength\":${bytes.size},\"elapsedMs\":$elapsed,\"rowOrdinal\":${i + 1}}\n")
                    offset += bytes.size
                }
            }
        }
        File(session.directory, "screenshots").mkdirs()
        File(session.directory, "screenshots/screenshot-500.png").writeBytes(byteArrayOf(9, 9))
        File(session.directory, "screenshots/screenshot-3500.png").writeBytes(shotBytes)
        val archive = File(root, "a.zip")
        CaptureArchiveExporter().export(
            session,
            CaptureExportRequest(
                destination = archive,
                range = CaptureRange.EXPLICIT,
                includeVideo = false,
                cutoffElapsedMs = 6_000,
                explicitStartElapsedMs = 2_000,
                explicitEndElapsedMs = 5_000,
            ),
        )
        return archive
    }

    private fun importSnapshot(root: File, archive: File, tag: String): ImportedCaptureSnapshot {
        val opened = CaptureArchiveReader.open(archive, File(root, "cache-$tag"))
        return importedCaptureSnapshot(
            opened, archive, parseLogcat(opened.logFile), File(root, "indexes-$tag"), File(root, "cursors-$tag"),
        )
    }

    private fun exportChild(
        snapshot: ImportedCaptureSnapshot,
        destination: File,
        range: CaptureRange,
        start: Long? = null,
        end: Long? = null,
    ) {
        CaptureArchiveExporter().export(
            snapshot.session,
            CaptureExportRequest(
                destination = destination,
                range = range,
                includeVideo = false,
                cutoffElapsedMs = snapshot.session.elapsedMs,
                explicitStartElapsedMs = start,
                explicitEndElapsedMs = end,
            ),
        )
    }

    private fun screenshotEntries(archive: File): List<String> = ZipFile(archive).use { zip ->
        zip.entries().asSequence().map { it.name }.filter { it.startsWith("screenshots/") }.sorted().toList()
    }

    private fun entryBytes(archive: File, name: String): ByteArray = ZipFile(archive).use { zip ->
        zip.getInputStream(zip.getEntry(name)).use { it.readBytes() }
    }
}
