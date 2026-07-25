package com.openlog

import com.openlog.utils.ZipLogCandidate
import com.openlog.utils.ZipLogCandidateKind
import com.openlog.utils.archiveVideoCacheFileName
import com.openlog.utils.enforceArchiveVideoCacheBudget
import com.openlog.utils.extractArchiveVideoToCache
import com.openlog.utils.pruneUnreferencedArchiveVideos
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlin.io.path.createTempDirectory
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

class BugReportVideoCacheTest {
    private fun zip(dir: File, name: String, entries: Map<String, ByteArray>): File = File(dir, name).also { archive ->
        ZipOutputStream(archive.outputStream()).use { output ->
            entries.forEach { (path, content) ->
                output.putNextEntry(ZipEntry(path))
                output.write(content)
                output.closeEntry()
            }
        }
    }

    // ── archiveVideoCacheFileName (A1) ──────────────────────────────────

    @Test
    fun archiveVideoCacheFileNameIsDeterministicAcrossCalls() {
        val dir = createTempDirectory("openlog-video-key").toFile()
        val archive = zip(dir, "bugreport.zip", mapOf("screen/repro.mp4" to byteArrayOf(1, 2, 3)))
        val candidate = ZipLogCandidate("screen/repro.mp4", "repro.mp4", sizeBytes = -1L, kind = ZipLogCandidateKind.VIDEO)

        val first = archiveVideoCacheFileName(archive, candidate)
        val second = archiveVideoCacheFileName(archive, candidate)

        assertEquals(first, second)
        assertTrue(first.endsWith(".mp4"))
    }

    @Test
    fun archiveVideoCacheFileNameDiffersWhenEntryPathDiffers() {
        val dir = createTempDirectory("openlog-video-key").toFile()
        val archive = zip(
            dir, "bugreport.zip",
            mapOf("screen/first.mp4" to byteArrayOf(1), "screen/second.mp4" to byteArrayOf(1)),
        )
        val first = ZipLogCandidate("screen/first.mp4", "first.mp4", sizeBytes = -1L, kind = ZipLogCandidateKind.VIDEO)
        val second = ZipLogCandidate("screen/second.mp4", "second.mp4", sizeBytes = -1L, kind = ZipLogCandidateKind.VIDEO)

        assertNotEquals(archiveVideoCacheFileName(archive, first), archiveVideoCacheFileName(archive, second))
    }

    // Locks the critical invariant: resolveVideoPlaybackPath (AppState.kt) always reconstructs its
    // ZipLogCandidate with sizeBytes = -1L before extracting/looking up the cache file. Any prune
    // logic building the referenced-file set must key off that exact same -1L candidate, or it will
    // delete a file playback still needs. This test proves extraction with a -1L candidate writes a
    // file named exactly what archiveVideoCacheFileName computes for that same -1L candidate.
    @Test
    fun sizeBytesMinusOneKeyMatchesWhatExtractionActuallyWrites() {
        val dir = createTempDirectory("openlog-video-key").toFile()
        val archive = zip(dir, "bugreport.zip", mapOf("screen/repro.mp4" to byteArrayOf(9, 9, 9, 9)))
        val cacheDir = File(dir, "managed-cache")
        val playbackCandidate = ZipLogCandidate(
            entryPath = "screen/repro.mp4",
            displayName = "repro.mp4",
            sizeBytes = -1L,
            kind = ZipLogCandidateKind.VIDEO,
        )

        val expectedFileName = archiveVideoCacheFileName(archive, playbackCandidate)
        val extracted = extractArchiveVideoToCache(archive, playbackCandidate, cacheDir)

        assertEquals(expectedFileName, extracted?.name)
    }

    // ── pruneUnreferencedArchiveVideos (A2) ─────────────────────────────

    @Test
    fun pruneKeepsOnlyReferencedAndPartialFiles() {
        val dir = createTempDirectory("openlog-video-prune").toFile()
        val videosDir = File(dir, "videos").apply { mkdirs() }
        val referenced = File(videosDir, "keep.mp4").apply { writeText("keep") }
        val unreferenced = File(videosDir, "stale.mp4").apply { writeText("stale") }
        val inFlight = File(videosDir, ".extracting.mp4.partial").apply { writeText("partial") }

        pruneUnreferencedArchiveVideos(dir, referencedFileNames = setOf("keep.mp4"))

        assertTrue(referenced.exists())
        assertTrue(inFlight.exists())
        assertTrue(!unreferenced.exists())
    }

    @Test
    fun pruneIsNoOpWhenVideosDirDoesNotExist() {
        val dir = createTempDirectory("openlog-video-prune-missing").toFile()

        // listFiles() on a non-existent directory returns null — must not throw.
        pruneUnreferencedArchiveVideos(dir, referencedFileNames = emptySet())
    }

    // ── enforceArchiveVideoCacheBudget (A2) ─────────────────────────────

    @Test
    fun budgetEvictsLeastRecentlyModifiedFirstUntilUnderBudget() {
        val dir = createTempDirectory("openlog-video-budget").toFile()
        val videosDir = File(dir, "videos").apply { mkdirs() }
        val oldest = File(videosDir, "oldest.mp4").apply { writeBytes(ByteArray(10)) }
        val middle = File(videosDir, "middle.mp4").apply { writeBytes(ByteArray(10)) }
        val newest = File(videosDir, "newest.mp4").apply { writeBytes(ByteArray(10)) }
        oldest.setLastModified(1_000L)
        middle.setLastModified(2_000L)
        newest.setLastModified(3_000L)

        // 30 bytes total, budget 15 -> must evict oldest first, then middle, stopping once <= budget.
        enforceArchiveVideoCacheBudget(dir, budgetBytes = 15L, protectedFileNames = emptySet())

        assertTrue(!oldest.exists())
        assertTrue(!middle.exists())
        assertTrue(newest.exists())
    }

    @Test
    fun budgetNeverEvictsProtectedFilesEvenWhenStillOverBudget() {
        val dir = createTempDirectory("openlog-video-budget-protected").toFile()
        val videosDir = File(dir, "videos").apply { mkdirs() }
        val protectedOld = File(videosDir, "protected.mp4").apply { writeBytes(ByteArray(20)) }
        val evictable = File(videosDir, "evictable.mp4").apply { writeBytes(ByteArray(20)) }
        protectedOld.setLastModified(1_000L)
        evictable.setLastModified(2_000L)

        // Budget is smaller than the protected file alone; only the evictable file can go.
        enforceArchiveVideoCacheBudget(dir, budgetBytes = 5L, protectedFileNames = setOf("protected.mp4"))

        assertTrue(protectedOld.exists())
        assertTrue(!evictable.exists())
    }

    @Test
    fun budgetSkipsPartialFilesWhenComputingTotalSizeAndEviction() {
        val dir = createTempDirectory("openlog-video-budget-partial").toFile()
        val videosDir = File(dir, "videos").apply { mkdirs() }
        val partial = File(videosDir, ".inflight.mp4.partial").apply { writeBytes(ByteArray(1_000)) }

        // The huge partial file must not count toward the budget nor be evicted.
        enforceArchiveVideoCacheBudget(dir, budgetBytes = 1L, protectedFileNames = emptySet())

        assertTrue(partial.exists())
    }
}
