package com.indagium.capture

import com.indagium.utils.logDaySlot
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.io.File
import java.nio.channels.FileChannel
import java.nio.file.Files
import java.nio.file.StandardOpenOption
import java.time.Instant
import java.util.concurrent.TimeUnit
import kotlin.io.path.createTempDirectory
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

private const val LOCK_PROBE_TIMEOUT_SECONDS = 60L

/**
 * Child JVM for [CaptureRecordingLeaseTest]: tries to take the lock of the file named by `args[0]` and prints
 * `HELD` when another process (the test JVM) owns it, `FREE` when it could take it.
 */
internal object CaptureLockProbe {
    @JvmStatic
    fun main(args: Array<String>) {
        FileChannel.open(File(args[0]).toPath(), StandardOpenOption.READ, StandardOpenOption.WRITE).use { channel ->
            val lock = channel.tryLock()
            println(if (lock == null) "HELD" else "FREE")
            lock?.release()
        }
    }
}

/** True when a different JVM cannot lock [file] because the test JVM holds a lock on it. */
internal fun lockIsHeldByAnotherProcess(file: File): Boolean {
    val java = File(System.getProperty("java.home"), "bin/java").path
    val process = ProcessBuilder(
        java, "-Djava.awt.headless=true", "-cp", System.getProperty("java.class.path"),
        CaptureLockProbe::class.java.name, file.absolutePath,
    ).redirectErrorStream(true).start()
    val output = process.inputStream.bufferedReader().readText()
    check(process.waitFor(LOCK_PROBE_TIMEOUT_SECONDS, TimeUnit.SECONDS)) { "lock probe did not exit" }
    return when (output.trim().lines().last()) {
        "HELD" -> true
        "FREE" -> false
        else -> error("unexpected lock probe output: $output")
    }
}

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
    fun aSymlinkAliasProbeNeverReleasesTheRecordersOsLock() = withFixture { root ->
        val real = File(root, "real").apply { mkdirs() }
        val alias = File(root, "alias")
        try {
            Files.createSymbolicLink(alias.toPath(), real.toPath())
        } catch (_: Exception) {
            return@withFixture // no symlinks here (Windows without privilege): nothing to alias
        }
        val owner = requireNotNull(CaptureRecordingLease.create(real))
        try {
            assertTrue(lockIsHeldByAnotherProcess(File(real, CaptureRecordingLease.LEASE_NAME)), "the owner holds the OS lock")

            assertEquals(CaptureRecordingLease.Attempt.Held, CaptureRecordingLease.tryTakeOver(alias))
            assertNull(CaptureRecordingLease.create(alias))

            assertTrue(
                lockIsHeldByAnotherProcess(File(real, CaptureRecordingLease.LEASE_NAME)),
                "probing through an alias must not close a channel that drops the recorder's lock",
            )
            assertTrue(CaptureRecordingLease.isHeldByThisProcess(alias), "the alias names the same lease")
        } finally {
            owner.close()
        }
        assertFalse(CaptureRecordingLease.isHeldByThisProcess(alias))
        assertFalse(CaptureRecordingLease.isHeldByThisProcess(real))
    }

    @Test
    fun aLiveSessionIsRecognisedThroughAPathAlias() = withFixture { root ->
        val real = File(root, "real").apply { mkdirs() }
        val alias = File(root, "alias")
        try {
            Files.createSymbolicLink(alias.toPath(), real.toPath())
        } catch (_: Exception) {
            return@withFixture
        }
        LiveCaptureSessions.add(real)
        try {
            assertTrue(LiveCaptureSessions.isLive(alias))
        } finally {
            LiveCaptureSessions.remove(alias)
        }
        assertFalse(LiveCaptureSessions.isLive(real), "removal through the alias removes the one entry")
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
