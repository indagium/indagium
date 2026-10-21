package com.indagium.capture

import com.indagium.model.LogEntry
import com.indagium.utils.parseLogcat
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.io.File
import java.time.Instant
import java.util.zip.ZipFile
import kotlin.system.measureNanoTime
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** Opt-in, reproducible metadata benchmark; its large fixture and outputs live under ignored build/. */
@Suppress("MagicNumber", "LongMethod")
class SparseMetadataBenchmarkTest {
    @Test
    fun millionRowsUseSparseMetadataWithoutChangingLogBytes() {
        if (System.getenv("INDAGIUM_SPARSE_METADATA_BENCHMARK") != "1") return
        val root = File("build/verification/sparse-metadata").absoluteFile
        val baseline = File(root, "baseline")
        val output = File(root, "measured").apply { mkdirs() }
        val sourceLog = File(baseline, "logcat.log")
        require(sourceLog.isFile) { "Generate the documented million-row fixture first" }
        val rowCount = 1_000_000
        val durationMs = 1_000_000L
        val startedEpochMs = Instant.parse("2026-10-21T12:00:00Z").toEpochMilli()
        val session = CaptureSession(
            id = "sparse-benchmark",
            directory = File(output, "session").apply {
                check(deleteRecursively()) { "Cannot reset benchmark session metadata" }
                mkdirs()
            },
            device = CaptureDevice("benchmark", "device", "Synthetic fixture"),
            settings = CaptureSettings(recordVideo = true, freeSpaceReserveBytes = 0),
            startedEpochMs = startedEpochMs,
            elapsedMs = durationMs,
            status = CaptureStatus.STOPPED,
            videoStartElapsedMs = 0L,
            logFileOverride = sourceLog,
            videoFileOverride = File(baseline, "screen.mkv"),
            storageVersion = CaptureStorageVersion.BLOCKS_V2,
        )
        session.blockIndexFile.parentFile.mkdirs()
        var blockCount = 0
        val indexingNs = measureNanoTime {
            CaptureBlockWriter(session.blockIndexFile).use { writer ->
                var offset = 0L
                var ordinal = 0
                sourceLog.bufferedReader().useLines { lines ->
                    lines.forEach { line ->
                        ordinal++
                        val bytes = "$line\n".toByteArray(Charsets.UTF_8)
                        writer.append(offset, bytes, ordinal, ordinal - 1L, 0)
                        offset += bytes.size
                    }
                }
                assertEquals(rowCount, ordinal)
                assertEquals(sourceLog.length(), offset)
                blockCount = writer.freeze().blocks.size
            }
        }
        val metadata = CaptureSyncMetadata(
            logBytes = sourceLog.length(),
            rowCount = rowCount,
            timelineBaseElapsedMs = 0L,
            timelineDurationMs = durationMs,
            segments = listOf(
                CaptureSyncSegment(
                    id = 0,
                    firstRow = 1,
                    lastRowExclusive = rowCount + 1,
                    anchorLogTimeMs = startedEpochMs,
                    anchorCaptureElapsedMs = 0L,
                    reliability = CaptureSyncReliability.RELIABLE,
                    clockReference = requireNotNull(parseCaptureClockReference("10-21 12:00:00.000", startedEpochMs)),
                ),
            ),
        )
        session.syncFile.parentFile.mkdirs()
        session.syncFile.writeText(metadata.toJson())
        val frozen = session.copy(syncMetadataOverride = metadata)
        val oldMetadataBytes = File(baseline, "capture-index.jsonl").length() + File(baseline, "rows.jsonl").length()
        val newMetadataBytes = session.blockIndexFile.length() + session.syncFile.length()
        assertTrue(newMetadataBytes < sourceLog.length() / 100)
        assertTrue(newMetadataBytes <= oldMetadataBytes * 0.05)

        val destination = File(output, "million-rows-v5.zip")
        // Copy the same tiny video to isolate metadata work from transcoding performance.
        val video = CaptureVideoExporter { source, target, start, end ->
            source.copyTo(target, overwrite = true)
            CaptureVideoClip(start, end, end - start)
        }
        val exportNs = measureNanoTime {
            CaptureArchiveExporter(video).export(
                frozen,
                CaptureExportRequest(destination, cutoffElapsedMs = durationMs, overwriteExisting = true),
            )
        }
        val selectedDestination = File(output, "hundred-rows-v5.zip")
        val selectedExportNs = measureNanoTime {
            CaptureArchiveExporter(video).export(
                frozen,
                CaptureExportRequest(
                    selectedDestination,
                    range = CaptureRange.SELECTION,
                    includeVideo = false,
                    cutoffElapsedMs = durationMs,
                    selectedFirstRowOrdinal = 500_000,
                    selectedLastRowOrdinal = 500_099,
                    overwriteExisting = true,
                ),
            )
        }
        lateinit var opened: ImportedCapture
        lateinit var legacyOpened: ImportedCapture
        val cacheRoot = File(output, "cache-${System.nanoTime()}")
        val openNs = measureNanoTime {
            opened = CaptureArchiveReader.open(destination, File(cacheRoot, "v5"))
        }
        val legacyOpenNs = measureNanoTime {
            legacyOpened = CaptureArchiveReader.open(File(baseline, "legacy-v4.zip"), File(cacheRoot, "v4"))
        }
        val selected = CaptureArchiveReader.open(selectedDestination, File(cacheRoot, "selection"))
        val selectedLines = selected.logFile.readLines()
        assertEquals(100, selectedLines.size)
        assertTrue(selectedLines.first().contains("row=0500000"))
        assertTrue(selectedLines.last().contains("row=0500099"))
        assertEquals(sourceLog.length(), opened.logFile.length())
        lateinit var resolver: CaptureTimingResolver
        lateinit var navigation: CaptureTimelineIndex
        lateinit var parsedEntries: List<LogEntry>
        val parseAndPrepareNs = measureNanoTime {
            val entries = parseLogcat(opened.logFile)
            parsedEntries = entries
            assertEquals(rowCount, entries.size)
            val timeline = requireNotNull(opened.timeline)
            resolver = CaptureTimingResolver.forTimeline(entries, timeline)
            navigation = CaptureTimelineIndex(timeline, entries.size, resolver)
            assertEquals(0L, resolver.videoMsForOrdinal(1))
            assertEquals(999_999L, resolver.videoMsForOrdinal(rowCount))
        }
        var checksum = 0L
        val lookupCount = 100_000
        val navigationNs = measureNanoTime {
            repeat(lookupCount) { index ->
                val ordinal = ((index.toLong() * 7_919) % rowCount).toInt() + 1
                checksum += requireNotNull(navigation.videoMsForOrdinal(ordinal))
                checksum += requireNotNull(navigation.nearest(ordinal - 1L).point).ordinal
            }
        }
        assertTrue(checksum > 0)
        lateinit var legacySnapshot: ImportedCaptureSnapshot
        val legacyPrepareNs = measureNanoTime {
            legacySnapshot = importedCaptureSnapshot(
                imported = legacyOpened,
                source = File(baseline, "legacy-v4.zip"),
                parsedLog = parsedEntries,
                indexDirectory = File(cacheRoot, "legacy-adapter"),
                checkpointDirectory = File(cacheRoot, "legacy-cursors"),
            )
        }
        val legacyReexport = File(output, "million-rows-upgraded-v5.zip")
        val legacyReexportNs = measureNanoTime {
            CaptureArchiveExporter(video).export(
                legacySnapshot.session,
                CaptureExportRequest(legacyReexport, cutoffElapsedMs = durationMs, overwriteExisting = true),
            )
        }
        val upgraded = CaptureArchiveReader.open(legacyReexport, File(cacheRoot, "upgraded"))
        val upgradedTimeline = requireNotNull(upgraded.timeline)
        assertTrue(upgradedTimeline.syncSegments.any { it.reliability == CaptureSyncReliability.RELIABLE })
        assertEquals(499_999L, CaptureTimingResolver.forTimeline(parsedEntries, upgradedTimeline).videoMsForOrdinal(500_000))
        assertTrue(legacySnapshot.session.blockIndexFile.length() < sourceLog.length() / 100)
        ZipFile(destination).use { archive ->
            assertEquals(null, archive.getEntry("timeline/rows.jsonl"))
        }
        val archived = archiveStats(destination, setOf("timeline/sync.json", "mapping/blocks.jsonl"))
        val legacyArchived = archiveStats(File(baseline, "legacy-v4.zip"), setOf("timeline/rows.jsonl"))
        val metrics = buildJsonObject {
            put("rows", rowCount)
            put("blocks", blockCount)
            put("raw_log_bytes", sourceLog.length())
            put("legacy_combined_metadata_bytes", oldMetadataBytes)
            put("new_retained_metadata_bytes", newMetadataBytes)
            put("new_archive_unpacked_metadata_bytes", archived.metadataBytes)
            put("new_archive_compressed_metadata_bytes", archived.compressedMetadataBytes)
            put("legacy_archive_unpacked_metadata_bytes", legacyArchived.metadataBytes)
            put("legacy_archive_compressed_metadata_bytes", legacyArchived.compressedMetadataBytes)
            put("legacy_archive_unpacked_total_bytes", legacyArchived.unpackedBytes)
            put("new_archive_unpacked_total_bytes", archived.unpackedBytes)
            put("metadata_percent_of_log", 100.0 * newMetadataBytes / sourceLog.length())
            put("metadata_savings_percent", 100.0 * (1.0 - newMetadataBytes.toDouble() / oldMetadataBytes))
            put("legacy_v4_archive_bytes", File(baseline, "legacy-v4.zip").length())
            put("new_v5_archive_bytes", destination.length())
            put("block_index_build_ms", indexingNs / 1_000_000.0)
            put("v5_export_ms", exportNs / 1_000_000.0)
            put("hundred_selected_rows_export_ms", selectedExportNs / 1_000_000.0)
            put("hundred_selected_rows_archive_bytes", selectedDestination.length())
            put("v4_reader_open_ms", legacyOpenNs / 1_000_000.0)
            put("v4_snapshot_adapter_prepare_ms", legacyPrepareNs / 1_000_000.0)
            put("v4_to_v5_export_ms", legacyReexportNs / 1_000_000.0)
            put("v4_to_v5_archive_bytes", legacyReexport.length())
            put("v5_reader_open_ms", openNs / 1_000_000.0)
            put("v5_parse_and_timing_prepare_ms", parseAndPrepareNs / 1_000_000.0)
            put("navigation_queries", lookupCount * 2)
            put("navigation_total_ms", navigationNs / 1_000_000.0)
            put("navigation_average_us", navigationNs / 1_000.0 / (lookupCount * 2))
            put(
                "method",
                "Synthetic stable-clock ASCII logs, 1ms per row. Identical tiny H264 video copied by a test exporter; " +
                    "encoding and real-device UI latency are outside this metadata benchmark. " +
                    "Reader times include extraction/checksums but exclude log parsing; navigation uses warmed row/video index lookups.",
            )
        }
        File(output, "metrics.json").writeText(metrics.toString())
        println("SPARSE_METADATA_BENCHMARK $metrics")
    }

    private data class ArchiveStats(val unpackedBytes: Long, val metadataBytes: Long, val compressedMetadataBytes: Long)

    private fun archiveStats(file: File, metadataPaths: Set<String>): ArchiveStats = ZipFile(file).use { archive ->
        val metadata = metadataPaths.map { requireNotNull(archive.getEntry(it)) }
        ArchiveStats(
            unpackedBytes = archive.entries().asSequence().sumOf { it.size },
            metadataBytes = metadata.sumOf { it.size },
            compressedMetadataBytes = metadata.sumOf { it.compressedSize },
        )
    }
}
