package com.indagium.capture

import com.indagium.utils.parseLogcat
import java.io.File
import java.security.MessageDigest
import kotlin.io.path.createTempDirectory
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

@Suppress("MagicNumber") // Fixture timings, counts and sizes, not tunable constants.
class CaptureArchiveV3ReexportTest {
    @Test
    fun legacyAnchorTimingSurvivesAChildExportWithoutRewritingTheSource() {
        verifyLegacyCut(withVideoAnchor = true)
    }

    @Test
    fun legacyLogOnlyCaptureUsesCoverageTimingForAChildExport() {
        verifyLegacyCut(withVideoAnchor = false)
    }

    private fun verifyLegacyCut(withVideoAnchor: Boolean) {
        val root = createTempDirectory("capture-v3-reexport").toFile()
        try {
            val source = File(root, "legacy").apply { mkdirs() }
            val log = File(source, "logcat.log").apply {
                writeText(
                    "01-02 03:04:10.000  100  101 I Tag: first\n" +
                        "01-02 03:04:12.000  100  101 I Tag: middle\n" +
                        "01-02 03:04:14.000  100  101 I Tag: last\n",
                )
            }
            val video = File(source, "screen.mkv").apply { writeText("legacy video asset") }
            val descriptor = File(source, CAPTURE_DESCRIPTOR_NAME).apply {
                val videoJson = if (withVideoAnchor) """{"file":"screen.mkv","startMs":1000,"endMs":6000}""" else "null"
                val syncJson = if (withVideoAnchor) """{"row":2,"videoMs":2000}""" else "null"
                writeText(
                    """{
                      "format":"indagium-capture","version":3,"sessionId":"legacy-session",
                      "device":{"serial":"legacy-device","model":"Legacy"},
                      "sessionStartedEpochMs":1000,"exportedEpochMs":2000,"range":"ALL",
                      "log":{"file":"logcat.log","startMs":1000,"endMs":5000},
                      "video":$videoJson,"sync":$syncJson,
                      "sha256":{"logcat.log":"${digest(log)}","screen.mkv":"${digest(video)}"}
                    }""".trimIndent(),
                )
            }
            val originalDescriptor = descriptor.readBytes()
            val originalLog = log.readBytes()
            val opened = CaptureArchiveReader.open(descriptor, File(root, "cache"))
            assertEquals(3, opened.descriptor.formatVersion)
            assertNull(opened.timeline)
            val snapshot = importedCaptureSnapshot(
                opened, descriptor, parseLogcat(opened.logFile),
                File(root, "indexes"), File(root, "cursors"),
            )
            val archive = File(root, "child.zip")
            CaptureArchiveExporter().export(
                snapshot.session,
                CaptureExportRequest(
                    destination = archive,
                    range = CaptureRange.EXPLICIT,
                    includeVideo = false,
                    cutoffElapsedMs = snapshot.session.elapsedMs,
                    explicitStartElapsedMs = 2000,
                    explicitEndElapsedMs = 4500,
                ),
            )
            val child = CaptureArchiveReader.open(archive, File(root, "child-cache"))
            assertEquals(5, child.descriptor.formatVersion)
            val childEntries = parseLogcat(child.logFile)
            assertEquals(if (withVideoAnchor) listOf("middle") else listOf("first", "middle", "last"), childEntries.map { it.msg })
            assertEquals(emptyList(), child.timeline!!.rows)
            val resolver = CaptureTimingResolver.forTimeline(parseLogcat(child.logFile), child.timeline!!)
            assertEquals(if (withVideoAnchor) 1_000L else null, resolver.elapsedMsForOrdinal(if (withVideoAnchor) 1 else 2))
            assertEquals(!withVideoAnchor, requireNotNull(child.sparseSyncMetadata).uncertainBoundary)
            assertEquals(2500L, child.descriptor.timelineDurationMs)
            assertEquals(originalDescriptor.toList(), descriptor.readBytes().toList())
            assertEquals(originalLog.toList(), log.readBytes().toList())
        } finally {
            root.deleteRecursively()
        }
    }

    private fun digest(file: File): String = MessageDigest.getInstance("SHA-256")
        .digest(file.readBytes()).joinToString("") { "%02x".format(it.toInt() and 0xff) }
}
