package com.indagium.capture

import com.indagium.utils.logDaySlot
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.io.File
import java.time.Instant
import kotlin.io.path.createTempDirectory
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * A RECORDING session whose [CaptureRecordingLease] is held is live (another recorder or app instance owns it):
 * recovery must leave it byte-for-byte alone. POSIX locks are per process, so "another owner" is simulated through
 * the lease's own in-process registry, the same path a second recorder in this JVM takes.
 */
@Suppress("MagicNumber")
class CaptureRecordingLeaseTest {
    @Test
    fun recoveryLeavesASessionWhoseLeaseIsHeldUntouchedByteForByte() = withFixture { root ->
        val session = fixture(root, rows = 2)
        // What recovery would otherwise rewrite: an unindexed complete tail, a torn index record, a partial line.
        session.logFile.appendText(line(2) + "\n" + line(3))
        session.blockIndexFile.appendText("{\"v\":1")
        val owner = requireNotNull(CaptureRecordingLease.create(session.directory))
        val before = snapshotOf(session)

        val listed = CaptureRecorder(root).use { it.recoverSessions() }.single()

        assertEquals(CaptureStatus.RECORDING, listed.status)
        assertEquals(before, snapshotOf(session))
        // Once the owner is gone the very same session is an ordinary orphan and is repaired.
        owner.close()
        val recovered = CaptureRecorder(root).use { it.recoverSessions() }.single()
        assertEquals(CaptureStatus.INTERRUPTED, recovered.status)
        assertEquals(CaptureBlockIndex.read(recovered.blockIndexFile, recovered.logFile).indexedRows, 3)
    }

    @Test
    fun aCrashedSessionWithAnUnheldLeaseFileIsStillRecovered() = withFixture { root ->
        val session = fixture(root, rows = 2)
        File(session.directory, CaptureRecordingLease.LEASE_NAME).writeText("")
        session.logFile.appendText(line(2) + "\n")

        val recovered = CaptureRecorder(root).use { it.recoverSessions() }.single()

        assertEquals(CaptureStatus.INTERRUPTED, recovered.status)
        assertEquals(3, requireNotNull(recovered.syncMetadataOverride).rowCount)
        assertFalse(CaptureRecordingLease.isHeldByThisProcess(session.directory), "recovery must release the lease it took")
    }

    @Test
    fun aSessionRecordedByAnOlderBuildWithoutALeaseFileKeepsTodaysBehaviour() = withFixture { root ->
        val session = fixture(root, rows = 2)
        assertFalse(File(session.directory, CaptureRecordingLease.LEASE_NAME).exists())
        session.logFile.appendText(line(2) + "\n")

        val recovered = CaptureRecorder(root).use { it.recoverSessions() }.single()

        assertEquals(CaptureStatus.INTERRUPTED, recovered.status)
        assertEquals(3, requireNotNull(recovered.syncMetadataOverride).rowCount)
    }

    @Test
    fun aLiveSessionOfThisProcessIsNeverRecovered() = withFixture { root ->
        val session = fixture(root, rows = 2)
        session.logFile.appendText(line(2) + "\n")
        val before = snapshotOf(session)
        LiveCaptureSessions.add(session.directory)
        try {
            val listed = CaptureRecorder(root).use { it.recoverSessions() }.single()
            assertEquals(CaptureStatus.RECORDING, listed.status)
            assertEquals(before, snapshotOf(session))
        } finally {
            LiveCaptureSessions.remove(session.directory)
        }
    }

    @Test
    fun aSecondClaimOnTheSameLeaseIsRefusedAndNeverDropsTheFirst() = withFixture { root ->
        val directory = File(root, "session").apply { mkdirs() }
        val first = requireNotNull(CaptureRecordingLease.create(directory))

        assertNull(CaptureRecordingLease.create(directory))
        assertEquals(CaptureRecordingLease.Attempt.Held, CaptureRecordingLease.tryTakeOver(directory))
        assertTrue(CaptureRecordingLease.isHeldByThisProcess(directory), "a refused claim must not release the holder")

        first.close()
        assertFalse(CaptureRecordingLease.isHeldByThisProcess(directory))
        val again = CaptureRecordingLease.tryTakeOver(directory)
        assertNotNull((again as? CaptureRecordingLease.Attempt.Acquired)?.also { it.lease.close() })
    }

    @Test
    fun aMissingLeaseFileIsAbsentNotAnError() = withFixture { root ->
        val directory = File(root, "session").apply { mkdirs() }

        assertEquals(CaptureRecordingLease.Attempt.Absent, CaptureRecordingLease.tryTakeOver(directory))
        assertFalse(CaptureRecordingLease.isHeldByThisProcess(directory))
    }

    private fun snapshotOf(session: CaptureSession): List<List<Byte>> = listOf(
        session.logFile,
        session.blockIndexFile,
        session.syncFile,
        File(session.directory, "session.json"),
    ).map { it.readBytes().toList() }

    private fun fixture(root: File, rows: Int): CaptureSession {
        val directory = File(root, "session").apply { mkdirs() }
        val session = CaptureSession(
            id = "session",
            directory = directory,
            device = CaptureDevice("test-device", "device"),
            settings = CaptureSettings(recordVideo = false, mirror = false, freeSpaceReserveBytes = 0),
            startedEpochMs = Instant.parse("2026-10-21T10:00:00Z").toEpochMilli(),
            elapsedMs = 10_000,
            status = CaptureStatus.RECORDING,
            storageVersion = CaptureStorageVersion.BLOCKS_V2,
        )
        session.logFile.parentFile.mkdirs()
        var offset = 0L
        CaptureBlockWriter(session.blockIndexFile).use { writer ->
            session.logFile.outputStream().use { output ->
                repeat(rows) { index ->
                    val raw = (line(index) + "\n").toByteArray()
                    output.write(raw)
                    writer.append(offset, raw, index + 1, index * 1_000L, 0)
                    offset += raw.size
                }
            }
        }
        val segment = CaptureSyncSegment(
            0, 1, rows + 1,
            Instant.parse("2026-10-21T10:00:00Z").toEpochMilli(),
            0L,
            CaptureSyncReliability.RELIABLE,
            CaptureClockReference(36_000_000L, logDaySlot(10, 21).toInt(), 2026),
        )
        session.syncFile.parentFile.mkdirs()
        session.syncFile.writeText(CaptureSyncMetadata(offset, rows, 0, 10_000, listOf(segment)).toJson())
        File(directory, "session.json").writeText(buildJsonObject {
            put("formatVersion", 1)
            put("storageVersion", 2)
            put("id", session.id)
            put("device", buildJsonObject { put("serial", session.device.serial) })
            put("settings", Json.parseToJsonElement(captureSettingsToJson(session.settings)))
            put("startedEpochMs", session.startedEpochMs)
            put("elapsedMs", session.elapsedMs)
            put("status", CaptureStatus.RECORDING.name)
        }.toString())
        return session
    }

    private fun line(second: Int) = "10-21 10:00:0$second.000  100  101 I Tag: row-$second"

    private fun withFixture(test: (File) -> Unit) {
        val root = createTempDirectory("capture-recording-lease").toFile()
        try {
            test(root)
        } finally {
            root.deleteRecursively()
        }
    }
}
