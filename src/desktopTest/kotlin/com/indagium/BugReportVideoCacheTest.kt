package com.indagium

import com.indagium.utils.ZipLogCandidate
import com.indagium.utils.ZipLogCandidateKind
import com.indagium.utils.archiveVideoCacheFileName
import com.indagium.utils.enforceArchiveVideoCacheBudget
import com.indagium.utils.extractArchiveVideoToCache
import com.indagium.utils.pruneUnreferencedArchiveVideos
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlin.io.path.createTempDirectory
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
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

    @Test
    fun extractedFileIsByteIdenticalToTheArchivedEntry() {
        val dir = createTempDirectory("openlog-video-content").toFile()
        val content = byteArrayOf(1, 2, 3, 4, 5, 6, 7, 8, 9)
        val archive = zip(dir, "bugreport.zip", mapOf("screen/repro.mp4" to content))
        val candidate = ZipLogCandidate("screen/repro.mp4", "repro.mp4", sizeBytes = -1L, kind = ZipLogCandidateKind.VIDEO)

        val extracted = extractArchiveVideoToCache(archive, candidate, File(dir, "cache"))

        assertContentEquals(content, extracted?.readBytes())
    }

    // Regression guard for a real reported bug: video playing from an archive lost its duration
    // and never recovered it — while the identical bytes opened directly worked fine. Traced to
    // this write path's old fallback, only reachable when the primary `partial.renameTo(destination)`
    // (atomic — either fully succeeds or leaves `destination` untouched) fails: it copied straight
    // into `destination`'s final name a second time, non-atomically. If the process died mid-copy,
    // `destination` was left truncated but non-empty — and the cache-hit check above
    // (`destination.isFile && destination.length() > 0`) has no integrity check, so every future
    // open of that archived video would silently reuse the truncated copy forever. This test can't
    // portably force `File.renameTo` to fail via a Windows-style "destination already exists" case
    // (POSIX rename atomically replaces, so that path only reliably fails on Windows), so it uses a
    // renameTo failure every platform agrees on instead: `destination` pre-existing as a non-empty
    // directory. What matters here is the outcome once rename fails at all: extraction must return
    // null (a clean, visible failure — surfaced as "Couldn't play this video", see
    // FailedVideoPlayerController) rather than ever falling back to a copy that could be interrupted
    // mid-write and cached as if it were complete.
    @Test
    fun extractionFailsCleanlyRatherThanRiskingATruncatedCacheEntryWhenRenameCannotSucceed() {
        val dir = createTempDirectory("openlog-video-rename-failure").toFile()
        val archive = zip(dir, "bugreport.zip", mapOf("screen/repro.mp4" to byteArrayOf(1, 2, 3)))
        val candidate = ZipLogCandidate("screen/repro.mp4", "repro.mp4", sizeBytes = -1L, kind = ZipLogCandidateKind.VIDEO)
        val cacheDir = File(dir, "cache")
        val destination = File(File(cacheDir, "videos"), archiveVideoCacheFileName(archive, candidate))
        // A non-empty directory at the destination path: renameTo fails on every platform (can't
        // replace a directory with a file), and so does a plain delete() (directory isn't empty) —
        // deterministically exercising the "rename truly cannot succeed" branch without depending
        // on the platform-specific Windows "destination file already exists" rename failure.
        destination.mkdirs()
        File(destination, "not-empty").writeText("x")

        val extracted = extractArchiveVideoToCache(archive, candidate, cacheDir)

        assertNull(extracted)
        assertTrue(destination.isDirectory, "must not have replaced or corrupted the pre-existing path")
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
