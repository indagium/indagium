package com.indagium.capture

import com.indagium.model.AnnBlock
import com.indagium.model.Annotations
import com.indagium.model.VideoFrameReference
import com.indagium.model.VideoSource
import com.indagium.utils.parseLogcat
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipFile
import java.util.zip.ZipOutputStream
import kotlin.io.path.createTempDirectory
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Spacing between the fixture's synthetic rows on the capture clock — one row per second. */
private const val FIXTURE_ROW_INTERVAL_MS = 1_000

/**
 * Phase 4 (snapshot archive and import): CaptureArchiveExporter.export's `notes` parameter and the
 * CaptureArchiveReader.open -> reanchorImportedCaptureNotes round trip. CaptureArchiveTest.kt covers
 * the pre-existing log/video/mapping export behavior this doesn't touch.
 *
 * Fixture: six log rows, ordinals 1..6, one second apart. Every export below selects the ORDINAL
 * range [2, 5] (CaptureRange.SELECTION) — original ordinals 1 and 6 fall outside it — so the
 * exported log's own local numbering (1..4) is deliberately offset from the original session-wide
 * ordinals the notes were written with. That offset is exactly the bug Phase 4 exists to fix; an
 * ALL export wouldn't exercise it (original ordinal == local ordinal there by construction).
 *
 * Three markers exercise the three outcomes range-filtering can produce:
 *  - "nA" (original rows 3-4): fully inside the export -> reanchored, not clipped.
 *  - "nB" (original rows 4-6): partially inside -> reanchored AND clipped to what was kept (4-5).
 *  - "nC" (original row 1 only): entirely outside -> its LogRef is dropped, note/heading kept.
 * Plus one plain note with no header and no LogRef at all, which range-filtering must never touch.
 */
class CaptureArchiveNotesTest {
    private val markerA = CaptureMarker(
        id = "m1", elapsedMs = 3_000L, firstOrdinal = 3, lastOrdinal = 4, videoMs = null,
        label = "Marker A", preMs = 0L, postMs = 0L, screenshotPath = null,
    )
    private val markerB = CaptureMarker(
        id = "m2", elapsedMs = 4_000L, firstOrdinal = 4, lastOrdinal = 6, videoMs = null,
        label = "Marker B", preMs = 0L, postMs = 0L, screenshotPath = null,
    )
    private val markerC = CaptureMarker(
        id = "m3", elapsedMs = 1_000L, firstOrdinal = 1, lastOrdinal = 1, videoMs = null,
        label = "Marker C", preMs = 0L, postMs = 0L, screenshotPath = null,
    )

    private fun sourceNotes(): Annotations = Annotations(
        blocks = listOf(
            AnnBlock.Note("nA", markerHeader(markerA) + "\n" + markerHeadingLine(1, markerA.label) + "\n"),
            AnnBlock.LogRef("rA", logIds = listOf(3, 4), caption = ""),
            AnnBlock.Note("nB", markerHeader(markerB) + "\n" + markerHeadingLine(2, markerB.label) + "\n"),
            AnnBlock.LogRef("rB", logIds = listOf(4, 5, 6), caption = ""),
            AnnBlock.Note("nC", markerHeader(markerC) + "\n" + markerHeadingLine(3, markerC.label) + "\n"),
            AnnBlock.LogRef("rC", logIds = listOf(1), caption = ""),
            AnnBlock.Note("nPlain", "Manual analysis note, no log reference"),
        ),
    )

    private fun exportWithNotes(
        root: File,
        destinationName: String,
        notes: Annotations?,
        settings: CaptureSettings = CaptureSettings(freeSpaceReserveBytes = 0),
    ): File {
        val session = CaptureSession(
            id = "session-1",
            directory = File(root, "session"),
            device = CaptureDevice("serial", "device", "Pixel"),
            settings = settings,
            startedEpochMs = 1_700_000_000_000,
            elapsedMs = 6_000,
        )
        session.logFile.parentFile.mkdirs()
        session.indexFile.parentFile.mkdirs()
        var offset = 0L
        session.logFile.outputStream().use { log ->
            session.indexFile.bufferedWriter().use { index ->
                for (ordinal in 1..6) {
                    val text = "01-01 10:00:0$ordinal.000  1  1 I Tag: row-$ordinal\n"
                    val bytes = text.toByteArray()
                    log.write(bytes)
                    index.append(
                        "{\"byteOffset\":$offset,\"byteLength\":${bytes.size}," +
                            "\"elapsedMs\":${ordinal * FIXTURE_ROW_INTERVAL_MS},\"rowOrdinal\":$ordinal}\n",
                    )
                    offset += bytes.size
                }
            }
        }
        val destination = File(root, destinationName)
        CaptureArchiveExporter(CaptureVideoExporter { _, _, _, _ -> error("unused") }).export(
            session,
            CaptureExportRequest(
                destination = destination,
                range = CaptureRange.SELECTION,
                cutoffElapsedMs = 6_000,
                selectedFirstRowOrdinal = 2,
                selectedLastRowOrdinal = 5,
            ),
            notes = notes,
        )
        return destination
    }

    @Test
    fun exportComputesClippedLocalOrdinalsAndReanchoringRestoresTheRightLogEntries() {
        val root = createTempDirectory("capture-notes-reanchor").toFile()
        val destination = exportWithNotes(root, "notes.zip", sourceNotes())
        val imported = CaptureArchiveReader.open(destination, File(root, "cache"))

        // Export side: nA (fully inside [2,5]) and nB (partially inside) both get a clipped,
        // EXPORT-LOCAL entry; nC (entirely outside) gets none.
        val markers = imported.descriptor.markers.associateBy { it.noteBlockId }
        assertEquals(setOf("nA", "nB"), markers.keys)
        assertEquals(2, markers.getValue("nA").firstOrdinal)
        assertEquals(3, markers.getValue("nA").lastOrdinal)
        assertEquals(3, markers.getValue("nB").firstOrdinal)
        assertEquals(4, markers.getValue("nB").lastOrdinal)

        val logData = parseLogcat(imported.logFile)
        assertEquals(4, logData.size)
        // Local ordinal 1..4 == original rows 2..5, so entry id 2 is original "row-3" and id 3 is
        // original "row-4" — exactly what markerA's ORIGINAL header (rows 3-4) pointed at.
        assertTrue(logData.first { it.id == 2 }.msg.contains("row-3"))
        assertTrue(logData.first { it.id == 3 }.msg.contains("row-4"))

        val reanchored = reanchorImportedCaptureNotes(requireNotNull(imported.notes), imported.descriptor.markers, logData)

        val byId = reanchored.blocks.associateBy { it.id }
        assertEquals(2, parseMarkerHeader((byId.getValue("nA") as AnnBlock.Note).text)?.firstOrdinal)
        assertEquals(3, parseMarkerHeader((byId.getValue("nA") as AnnBlock.Note).text)?.lastOrdinal)
        assertEquals(listOf(2, 3), (byId.getValue("rA") as AnnBlock.LogRef).logIds)
        assertNull((byId.getValue("rA") as AnnBlock.LogRef).sourceEntries)

        assertEquals(listOf(3, 4), (byId.getValue("rB") as AnnBlock.LogRef).logIds)

        assertEquals("Manual analysis note, no log reference", (byId.getValue("nPlain") as AnnBlock.Note).text)
    }

    @Test
    fun outOfRangeMarkerLosesOnlyItsLogRefWhilePlainNoteSurvivesUntouched() {
        val root = createTempDirectory("capture-notes-outofrange").toFile()
        val destination = exportWithNotes(root, "notes.zip", sourceNotes())
        val imported = CaptureArchiveReader.open(destination, File(root, "cache"))
        val logData = parseLogcat(imported.logFile)
        val reanchored = reanchorImportedCaptureNotes(requireNotNull(imported.notes), imported.descriptor.markers, logData)

        val byId = reanchored.blocks.associateBy { it.id }
        // nC's note survives with its jump cleared (rows=null,null); its LogRef ("rC") is gone.
        assertTrue("nC" in byId)
        val clearedHeader = parseMarkerHeader((byId.getValue("nC") as AnnBlock.Note).text)
        assertNull(clearedHeader?.firstOrdinal)
        assertNull(clearedHeader?.lastOrdinal)
        assertTrue("rC" !in byId)
        assertEquals(6, reanchored.blocks.size) // 7 source blocks minus the dropped rC LogRef
    }

    @Test
    fun v1ArchiveWithoutNotesKeyStillOpens() {
        val root = createTempDirectory("capture-notes-v1").toFile()
        val destination = exportWithNotes(root, "v2.zip", notes = null)
        val downgraded = File(root, "v1.zip")
        rewriteDescriptorEntry(destination, downgraded) { json ->
            json.replace("\"formatVersion\":$CAPTURE_ARCHIVE_VERSION", "\"formatVersion\":1")
        }

        val imported = CaptureArchiveReader.open(downgraded, File(root, "cache"))
        assertNull(imported.notes)
        assertNull(imported.descriptor.notes)
        assertTrue(imported.descriptor.markers.isEmpty())
    }

    @Test
    fun markerNotesInSnapshotFalseProducesNoNotesEntry() {
        val root = createTempDirectory("capture-notes-disabled").toFile()
        val destination = exportWithNotes(
            root,
            "disabled.zip",
            notes = sourceNotes(),
            settings = CaptureSettings(freeSpaceReserveBytes = 0, markerNotesInSnapshot = false),
        )

        ZipFile(destination).use { zip ->
            assertTrue(zip.entries().asSequence().none { it.name == "notes.ann" })
        }
        val imported = CaptureArchiveReader.open(destination, File(root, "cache"))
        assertNull(imported.notes)
        assertNull(imported.descriptor.notes)
        assertTrue(imported.descriptor.markers.isEmpty())
    }

    // Bug fix regression (marker screenshot doesn't seek to the right video position): the plan's
    // own worked example — a marker screenshot at elapsed 40s, video starting at 2s (so its LIVE,
    // source-video-relative position is 38s), exported as a "last 5 min"-style clip whose video
    // actually starts at SOURCE 10s — must reopen seeking to 28s (38 - 10), not 38s and not the
    // session's raw video file.
    @Test
    fun exportRewritesLiveVideoFrameToPortableArchiveSourceAndImportRepointsItToTheActualVideo() {
        val root = createTempDirectory("capture-notes-video-frame").toFile()
        val session = CaptureSession(
            id = "session-frame",
            directory = File(root, "session"),
            device = CaptureDevice("serial", "device", "Pixel"),
            settings = CaptureSettings(recordVideo = true, freeSpaceReserveBytes = 0),
            startedEpochMs = 1_700_000_000_000,
            elapsedMs = 60_000,
            videoStartElapsedMs = 2_000L,
        )
        session.logFile.parentFile.mkdirs()
        session.indexFile.parentFile.mkdirs()
        session.videoFile.parentFile.mkdirs()
        session.videoFile.writeBytes(byteArrayOf(1, 2, 3))
        val logBytes = "01-01 10:00:40.000  1  1 I Tag: row\n".toByteArray()
        session.logFile.writeBytes(logBytes)
        session.indexFile.writeText(
            "{\"byteOffset\":0,\"byteLength\":${logBytes.size},\"elapsedMs\":40000,\"rowOrdinal\":1}\n",
        )

        val liveFrame = VideoFrameReference(
            source = VideoSource.LocalFile(session.videoFile.absolutePath),
            sourceLabel = "capture.indagium.json/${session.videoFile.name}",
            // elapsedMs(40s) - videoStartElapsedMs(2s) + manualOffsetMs(0) = 38s of SOURCE video.
            positionMs = 38_000L,
        )
        val notes = Annotations(
            blocks = listOf(
                AnnBlock.Image(
                    "img1", caption = "", provenance = liveFrame.provenanceLabel,
                    format = "png", bytes = byteArrayOf(9), videoFrame = liveFrame,
                ),
            ),
        )
        val destination = File(root, "frame.zip")
        val exporter = CaptureArchiveExporter(
            CaptureVideoExporter { _, target, _, _ ->
                target.writeText("clip")
                CaptureVideoClip(actualStartMs = 10_000L, coveredEndMs = 50_000L, durationMs = 40_000L)
            },
        )
        exporter.export(
            session,
            CaptureExportRequest(destination = destination, range = CaptureRange.ALL, cutoffElapsedMs = 60_000L),
            notes = notes,
        )

        val imported = CaptureArchiveReader.open(destination, File(root, "cache"))
        val exportedImage = (requireNotNull(imported.notes).blocks.single()) as AnnBlock.Image
        val exportedFrame = requireNotNull(exportedImage.videoFrame)
        assertEquals(28_000L, exportedFrame.positionMs, "38s of source video minus the clip's own 10s start")
        val portableSource = exportedFrame.source as VideoSource.ArchiveEntry
        assertEquals("", portableSource.archivePath, "export-time source is a portable, archive-relative marker")
        assertEquals(imported.descriptor.video!!.path, portableSource.entryPath)

        // Import side: AppState re-points the portable marker at whatever this reopen's own
        // attached video actually resolves to — here, a durable ArchiveEntry for the reopened zip.
        val actualSource = VideoSource.ArchiveEntry(destination.absolutePath, imported.descriptor.video!!.path, "screen")
        val repointed = repointPortableVideoFrames(requireNotNull(imported.notes), actualSource)
        val repointedFrame = requireNotNull((repointed.blocks.single() as AnnBlock.Image).videoFrame)
        assertEquals(actualSource, repointedFrame.source)
        assertEquals(28_000L, repointedFrame.positionMs)
    }

    // A frame whose live position falls outside what this export actually covered (or no video was
    // exported at all) must be DETACHED, not shipped with a stale/wrong position — see
    // CaptureArchive.kt's rewriteExportedVideoFrames doc.
    @Test
    fun exportDropsAVideoFrameThatFallsOutsideTheExportedClip() {
        val root = createTempDirectory("capture-notes-video-frame-outside").toFile()
        val session = CaptureSession(
            id = "session-frame-outside",
            directory = File(root, "session"),
            device = CaptureDevice("serial", "device", "Pixel"),
            settings = CaptureSettings(recordVideo = true, freeSpaceReserveBytes = 0),
            startedEpochMs = 1_700_000_000_000,
            elapsedMs = 60_000,
            videoStartElapsedMs = 2_000L,
        )
        session.logFile.parentFile.mkdirs()
        session.indexFile.parentFile.mkdirs()
        session.videoFile.parentFile.mkdirs()
        session.videoFile.writeBytes(byteArrayOf(1, 2, 3))
        val logBytes = "01-01 10:00:05.000  1  1 I Tag: row\n".toByteArray()
        session.logFile.writeBytes(logBytes)
        session.indexFile.writeText(
            "{\"byteOffset\":0,\"byteLength\":${logBytes.size},\"elapsedMs\":5000,\"rowOrdinal\":1}\n",
        )
        // Live position 3s of source video — before the clip's own 10s start, so entirely outside it.
        val liveFrame = VideoFrameReference(
            source = VideoSource.LocalFile(session.videoFile.absolutePath),
            sourceLabel = "capture.indagium.json/${session.videoFile.name}",
            positionMs = 3_000L,
        )
        val notes = Annotations(
            blocks = listOf(
                AnnBlock.Image(
                    "img1", caption = "", provenance = liveFrame.provenanceLabel,
                    format = "png", bytes = byteArrayOf(9), videoFrame = liveFrame,
                ),
            ),
        )
        val destination = File(root, "outside.zip")
        val exporter = CaptureArchiveExporter(
            CaptureVideoExporter { _, target, _, _ ->
                target.writeText("clip")
                CaptureVideoClip(actualStartMs = 10_000L, coveredEndMs = 50_000L, durationMs = 40_000L)
            },
        )
        exporter.export(
            session,
            CaptureExportRequest(destination = destination, range = CaptureRange.ALL, cutoffElapsedMs = 60_000L),
            notes = notes,
        )

        val imported = CaptureArchiveReader.open(destination, File(root, "cache"))
        val exportedImage = (requireNotNull(imported.notes).blocks.single()) as AnnBlock.Image
        assertNull(exportedImage.videoFrame, "a frame outside the exported clip must be detached, not left pointing at the wrong moment")
    }

    /** Rewrites only [CAPTURE_DESCRIPTOR_NAME]'s bytes, copying every other entry unchanged — same
     *  pattern CaptureArchiveTest.kt's own `rewriteZip` uses, kept local to avoid cross-file coupling
     *  between the two capture-archive test suites. */
    private fun rewriteDescriptorEntry(source: File, destination: File, transform: (String) -> String) {
        ZipFile(source).use { input ->
            ZipOutputStream(destination.outputStream()).use { output ->
                input.entries().asSequence().forEach { entry ->
                    val bytes = input.getInputStream(entry).use { it.readBytes() }
                    output.putNextEntry(ZipEntry(entry.name))
                    output.write(
                        if (entry.name == CAPTURE_DESCRIPTOR_NAME) {
                            transform(bytes.toString(Charsets.UTF_8)).toByteArray()
                        } else {
                            bytes
                        },
                    )
                    output.closeEntry()
                }
            }
        }
    }
}
