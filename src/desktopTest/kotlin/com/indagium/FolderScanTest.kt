package com.indagium

import com.indagium.utils.MAX_FOLDER_CANDIDATES
import com.indagium.utils.ZipLogCandidateKind
import com.indagium.utils.scanFolderForLogs
import java.io.File
import java.nio.file.Files
import kotlin.io.path.createTempDirectory
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class FolderScanTest {
    @Test
    fun candidatesComeBackAsRelativeSortedPaths() {
        val root = createTempDirectory("openlog-folder-scan-sorted").toFile()
        File(root, "b.log").writeText("06-26 10:00:00.000  1  1 I Tag: b\n")
        File(root, "sub").apply { mkdir() }.also { File(it, "a.log").writeText("06-26 10:00:00.000  1  1 I Tag: a\n") }

        val scan = scanFolderForLogs(root)

        assertEquals(listOf("b.log", "sub/a.log"), scan.logCandidates.map { it.entryPath })
        assertFalse(scan.truncated)
    }

    @Test
    fun binaryFileWithLogLikeNameIsExcludedByContentSniff() {
        val root = createTempDirectory("openlog-folder-scan-binary").toFile()
        File(root, "readable.log").writeText("06-26 10:00:00.000  1  1 I Tag: ok\n")
        File(root, "binary.log").writeBytes(byteArrayOf(0, 1, 2, 3, 0, 5))

        val scan = scanFolderForLogs(root)

        assertEquals(listOf("readable.log"), scan.logCandidates.map { it.entryPath })
    }

    @Test
    fun videosAreSeparatedFromLogCandidates() {
        val root = createTempDirectory("openlog-folder-scan-video").toFile()
        File(root, "main.log").writeText("06-26 10:00:00.000  1  1 I Tag: main\n")
        File(root, "recording.mp4").writeText("not decoded by this test")

        val scan = scanFolderForLogs(root)

        assertEquals(listOf("main.log"), scan.logCandidates.map { it.entryPath })
        assertEquals(listOf("recording.mp4"), scan.videoCandidates.map { it.entryPath })
        assertEquals(ZipLogCandidateKind.VIDEO, scan.videoCandidates.single().kind)
    }

    @Test
    fun exceedingMaxCandidatesTruncatesAndStopsAcceptingMore() {
        val root = createTempDirectory("openlog-folder-scan-candidate-cap").toFile()
        // One more candidate than the cap allows, so the scan must stop partway through and
        // report truncated rather than silently accepting all of them.
        repeat(MAX_FOLDER_CANDIDATES + 5) { i ->
            File(root, "file-%04d.log".format(i)).writeText("06-26 10:00:00.000  1  1 I Tag: $i\n")
        }

        val scan = scanFolderForLogs(root, maxCandidates = 10)

        assertEquals(10, scan.logCandidates.size)
        assertTrue(scan.truncated)
    }

    @Test
    fun exceedingMaxDepthStopsDescendingFurtherAndSkipsWhatsBelow() {
        val root = createTempDirectory("openlog-folder-scan-depth-cap").toFile()
        var dir = root
        // Nest well past a shallow cap so the deepest file is unreachable at maxDepth = 2.
        repeat(4) { i -> dir = File(dir, "level$i").apply { mkdir() } }
        File(dir, "too-deep.log").writeText("06-26 10:00:00.000  1  1 I Tag: deep\n")
        File(root, "shallow.log").writeText("06-26 10:00:00.000  1  1 I Tag: shallow\n")

        val scan = scanFolderForLogs(root, maxDepth = 2)

        assertEquals(listOf("shallow.log"), scan.logCandidates.map { it.entryPath })
    }

    @Test
    fun exceedingMaxEntriesScannedTruncates() {
        val root = createTempDirectory("openlog-folder-scan-entries-cap").toFile()
        repeat(30) { i -> File(root, "file-%02d.log".format(i)).writeText("06-26 10:00:00.000  1  1 I Tag: $i\n") }

        val scan = scanFolderForLogs(root, maxEntries = 5)

        assertTrue(scan.truncated)
        assertTrue(scan.logCandidates.size <= 5)
    }

    @Test
    fun hiddenFilesAndDirectoriesAreExcludedByDefault() {
        val root = createTempDirectory("openlog-folder-scan-hidden").toFile()
        File(root, "visible.log").writeText("06-26 10:00:00.000  1  1 I Tag: visible\n")
        File(root, ".hidden.log").writeText("06-26 10:00:00.000  1  1 I Tag: hidden\n")
        val hiddenDir = File(root, ".hiddenDir").apply { mkdir() }
        File(hiddenDir, "inside.log").writeText("06-26 10:00:00.000  1  1 I Tag: inside\n")

        val scan = scanFolderForLogs(root)

        assertEquals(listOf("visible.log"), scan.logCandidates.map { it.entryPath })
    }

    @Test
    fun includeHiddenTrueSurfacesHiddenFilesAndDirectories() {
        val root = createTempDirectory("openlog-folder-scan-hidden-included").toFile()
        File(root, "visible.log").writeText("06-26 10:00:00.000  1  1 I Tag: visible\n")
        File(root, ".hidden.log").writeText("06-26 10:00:00.000  1  1 I Tag: hidden\n")

        val scan = scanFolderForLogs(root, includeHidden = true)

        assertEquals(listOf(".hidden.log", "visible.log"), scan.logCandidates.map { it.entryPath })
    }

    // A symlinked directory pointing back at an ancestor would be an infinite loop for a walk
    // that didn't guard against it — the canonicalPath HashSet must catch this and terminate with
    // every real candidate reported exactly once (not zero, and not looping forever).
    @Test
    fun symlinkCycleTerminatesWithEachCandidateExactlyOnce() {
        if (System.getProperty("os.name").contains("Windows", ignoreCase = true)) return
        val root = createTempDirectory("openlog-folder-scan-symlink-cycle").toFile()
        val sub = File(root, "sub").apply { mkdir() }
        File(sub, "main.log").writeText("06-26 10:00:00.000  1  1 I Tag: main\n")
        // Points back at sub's own parent — descending it again would revisit "sub" forever.
        runCatching { Files.createSymbolicLink(File(sub, "loop").toPath(), root.toPath()) }
            .onFailure { return } // symlink creation unsupported/unprivileged on this host — skip

        val scan = scanFolderForLogs(root)

        assertEquals(listOf("sub/main.log"), scan.logCandidates.map { it.entryPath })
    }

    @Test
    fun emptyFolderReturnsEmptyLists() {
        val root = createTempDirectory("openlog-folder-scan-empty").toFile()

        val scan = scanFolderForLogs(root)

        assertTrue(scan.logCandidates.isEmpty())
        assertTrue(scan.videoCandidates.isEmpty())
        assertFalse(scan.truncated)
    }
}
