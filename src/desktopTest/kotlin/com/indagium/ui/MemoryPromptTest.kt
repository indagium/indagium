@file:Suppress("MagicNumber")

package com.indagium.ui

import com.indagium.utils.MemoryShortfall
import com.indagium.utils.SPLIT_PROMPT_BYTES
import com.indagium.utils.ZipLogCandidateKind
import java.io.File
import java.io.RandomAccessFile
import java.nio.file.Files
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class MemoryPromptTest {
    private val mb = 1024L * 1024L
    private val gb = 1024L * mb

    private fun newApp(dir: File, free: Long) =
        AppState(autosaveFile = File(dir, "state.cache"), autoExportNotes = false).also {
            it.heapFreeBytesProvider = { free }
        }

    private fun newDir() = Files.createTempDirectory("memory-prompt").toFile()

    private fun sparse(dir: File, name: String, bytes: Long) =
        File(dir, name).also { f -> RandomAccessFile(f, "rw").use { it.setLength(bytes) } }

    @Test
    fun smallFileWithLowFreeHeapPromptsWithAShortfall() {
        val dir = newDir()
        val file = sparse(dir, "small.log", 100 * mb) // needs 350 MB
        val app = newApp(dir, free = 200 * mb)
        try {
            app.openFile(file)

            val pending = assertNotNull(app.pendingSplitPrompt)
            assertEquals(MemoryShortfall(350 * mb, 200 * mb), pending.memoryShortfall)
            assertEquals(listOf("small.log"), pending.sources.map { it.displayName })
            assertTrue(app.tabs.isEmpty())
            // Below the size threshold the size-based suggestion is 1; the memory ratio gives a real split.
            assertEquals(2, app.defaultSplitPartCount(pending.sources.single(), pending.memoryShortfall))
        } finally {
            app.close()
        }
    }

    @Test
    fun openPathsWithLowFreeHeapPromptsWithAShortfall() {
        val dir = newDir()
        val file = sparse(dir, "small.log", 100 * mb)
        val app = newApp(dir, free = 200 * mb)
        try {
            app.openPaths(listOf(file))

            assertEquals(MemoryShortfall(350 * mb, 200 * mb), app.pendingSplitPrompt?.memoryShortfall)
            assertTrue(app.tabs.isEmpty())
        } finally {
            app.close()
        }
    }

    @Test
    fun batchThatFitsIndividuallyButNotTogetherPromptsOnceForTheBatch() {
        val dir = newDir()
        val a = sparse(dir, "a.log", 50 * mb)
        val b = sparse(dir, "b.log", 50 * mb)
        val app = newApp(dir, free = 200 * mb) // each needs 175 MB, together 350 MB
        try {
            app.openPaths(listOf(a, b))

            val pending = assertNotNull(app.pendingSplitPrompt)
            assertEquals(listOf("a.log", "b.log"), pending.sources.map { it.displayName })
            assertEquals(MemoryShortfall(350 * mb, 200 * mb), pending.memoryShortfall)
        } finally {
            app.close()
        }
    }

    @Test
    fun bigFileWithPlentyOfFreeHeapKeepsTheOldPromptWithoutAShortfall() {
        val dir = newDir()
        val file = sparse(dir, "large.log", SPLIT_PROMPT_BYTES)
        val app = newApp(dir, free = Long.MAX_VALUE)
        try {
            app.openFile(file)

            val pending = assertNotNull(app.pendingSplitPrompt)
            assertNull(pending.memoryShortfall)
            assertEquals(listOf("large.log"), pending.sources.map { it.displayName })
        } finally {
            app.close()
        }
    }

    @Test
    fun bigFileThatAlsoDoesNotFitCarriesTheShortfall() {
        val dir = newDir()
        val file = sparse(dir, "large.log", SPLIT_PROMPT_BYTES)
        val app = newApp(dir, free = 1 * gb)
        try {
            app.openFile(file)

            assertEquals(MemoryShortfall(SPLIT_PROMPT_BYTES * 7 / 2, 1 * gb), app.pendingSplitPrompt?.memoryShortfall)
            assertTrue(app.defaultSplitPartCount(app.pendingSplitPrompt!!.sources.single(), app.pendingSplitPrompt!!.memoryShortfall) >= 2)
        } finally {
            app.close()
        }
    }

    @Test
    fun smallFileWithPlentyOfFreeHeapOpensWithoutAPrompt() {
        val dir = newDir()
        val file = File(dir, "small.log").apply { writeText("01-01 00:00:00.000  100  200 I Tag: hello\n") }
        val app = newApp(dir, free = Long.MAX_VALUE)
        try {
            app.openFile(file)

            assertNull(app.pendingSplitPrompt)
            val deadline = System.currentTimeMillis() + 10_000
            while ((app.tabs.isEmpty() || app.isLoading) && System.currentTimeMillis() < deadline) Thread.sleep(20)
            assertEquals(1, app.tabs.size)
        } finally {
            app.close()
        }
    }

    @Test
    fun openAsIsBypassesTheMemoryPrompt() {
        val dir = newDir()
        val file = File(dir, "small.log").apply { writeText("01-01 00:00:00.000  100  200 I Tag: hello\n") }
        val app = newApp(dir, free = 1L)
        try {
            app.openFileAsIs(file)

            assertNull(app.pendingSplitPrompt)
        } finally {
            app.close()
        }
    }

    @Test
    fun shortfallLineUsesGigabytesWithOneDecimal() {
        assertEquals(
            "Needs about 3.1 GB of memory; about 2.0 GB is free. " +
                "Split it (only the parts that fit will open), close other tabs, or open anyway.",
            memoryShortfallLine(MemoryShortfall((3.1 * gb).toLong(), 2 * gb)),
        )
        assertEquals(
            "Needs about 350.0 MB of memory; about 200.0 MB is free. " +
                "Split it (only the parts that fit will open), close other tabs, or open anyway.",
            memoryShortfallLine(MemoryShortfall(350 * mb, 200 * mb)),
        )
    }

    private fun logLines(count: Int): String =
        (1..count).joinToString("") { "01-02 03:04:05.%03d  100  101 I Tag: message number %d\n".format(it % 1000, it) }

    private fun waitUntil(timeoutMs: Long = 15_000, condition: () -> Boolean) {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (!condition() && System.currentTimeMillis() < deadline) Thread.sleep(10)
        assertTrue(condition(), "condition was not met within ${timeoutMs}ms")
    }

    private fun zip(dir: File, name: String, entries: Map<String, String>): File = File(dir, name).also { archive ->
        ZipOutputStream(archive.outputStream()).use { output ->
            entries.forEach { (path, content) ->
                output.putNextEntry(ZipEntry(path))
                output.write(content.toByteArray())
                output.closeEntry()
            }
        }
    }

    // The memory prompt recommends Split, but opening every part again put the whole file back on the heap.
    @Test
    fun splitOnAMemoryPromptWritesEveryPartButOpensOnlyThoseThatFit() {
        val dir = newDir()
        val file = File(dir, "app.log").apply { writeText(logLines(2_000)) }
        val needed = (file.length() * 3.5).toLong()
        // Room for 60% of the whole: two of four equal parts fit, a third does not.
        val app = newApp(dir, free = needed * 6 / 10)
        val parts = File(dir, "parts")
        try {
            app.openFile(file)
            val pending = assertNotNull(app.pendingSplitPrompt)
            assertNotNull(pending.memoryShortfall)
            val source = pending.sources.single()

            app.confirmSplitPrompt(
                modes = mapOf(source.id to SplitMode.SPLIT),
                destinationDir = parts,
                postfix = "part",
                partCounts = mapOf(source.id to 4),
            )
            waitUntil { app.openError != null && !app.isLoading }

            assertEquals(4, parts.listFiles()!!.size, "every part is written to disk")
            assertEquals(2, app.tabs.size, "only the parts that fit are opened")
            val notice = assertNotNull(app.openError)
            assertEquals(parts.absolutePath, notice.path, "the notice says where the remaining parts are")
            assertTrue(notice.message.contains("2 parts were left on disk"), notice.message)
            assertNull(app.pendingSplitPrompt)
        } finally {
            app.close()
        }
    }

    @Test
    fun splitOnAMemoryPromptAlwaysOpensAtLeastTheFirstPart() {
        val dir = newDir()
        val file = File(dir, "app.log").apply { writeText(logLines(400)) }
        val app = newApp(dir, free = 1L) // nothing "fits"
        val parts = File(dir, "parts")
        try {
            app.openFile(file)
            val source = assertNotNull(app.pendingSplitPrompt).sources.single()

            app.confirmSplitPrompt(
                mapOf(source.id to SplitMode.SPLIT), parts, "part", mapOf(source.id to 3),
            )
            waitUntil { app.openError != null && !app.isLoading }

            assertEquals(3, parts.listFiles()!!.size)
            assertEquals(1, app.tabs.size)
            assertEquals("app_part_1.log", app.tabs.single().filename)
        } finally {
            app.close()
        }
    }

    // A pure size prompt (no memory shortfall) keeps opening every part.
    @Test
    fun splitOnAPureSizePromptStillOpensEveryPart() {
        val dir = newDir()
        val file = File(dir, "app.log").apply { writeText(logLines(400)) }
        val app = newApp(dir, free = Long.MAX_VALUE)
        val parts = File(dir, "parts")
        try {
            app.openPaths(listOf(file), splitPromptThresholdBytes = 1_000L)
            val pending = assertNotNull(app.pendingSplitPrompt)
            assertNull(pending.memoryShortfall)
            val source = pending.sources.single()

            app.confirmSplitPrompt(mapOf(source.id to SplitMode.SPLIT), parts, "part", mapOf(source.id to 3))
            waitUntil { app.tabs.size == 3 && !app.isLoading }

            assertNull(app.openError, "no memory notice for a size-only split")
        } finally {
            app.close()
        }
    }

    // Deferred files were already weighed in the batch decision; opening them used to re-run the per-file
    // memory check, which raised new prompts that overwrote one another so some files never opened.
    @Test
    fun deferredFilesOpenWithoutRaisingNewPromptsAndNoneIsDropped() {
        val dir = newDir()
        val big = File(dir, "big.log").apply { writeText(logLines(300)) }
        val smallA = File(dir, "small_a.log").apply { writeText(logLines(5)) }
        val smallB = File(dir, "small_b.log").apply { writeText(logLines(6)) }
        // Free heap too small even for the small files, so each of them would prompt on its own.
        val app = newApp(dir, free = 100L)
        try {
            app.openPaths(listOf(big, smallA, smallB), splitPromptThresholdBytes = big.length())
            val pending = assertNotNull(app.pendingSplitPrompt)
            assertEquals(listOf("big.log"), pending.sources.map { it.displayName })
            assertEquals(listOf("small_a.log", "small_b.log"), pending.deferredFiles.map { it.name })
            val source = pending.sources.single()

            app.confirmSplitPrompt(mapOf(source.id to SplitMode.OPEN_AS_IS), File(dir, "parts"), "part", emptyMap())
            waitUntil { app.tabs.size == 3 && !app.isLoading }

            assertEquals(setOf("big.log", "small_a.log", "small_b.log"), app.tabs.map { it.filename }.toSet())
            assertNull(app.pendingSplitPrompt, "deferred files must not raise a prompt of their own")
        } finally {
            app.close()
        }
    }

    @Test
    fun aSecondPromptMergesIntoThePendingOneInsteadOfReplacingIt() {
        val dir = newDir()
        val a = sparse(dir, "a.log", 100 * mb)
        val b = sparse(dir, "b.log", 100 * mb)
        val app = newApp(dir, free = 200 * mb)
        try {
            app.openFile(a)
            app.openFile(b)

            val pending = assertNotNull(app.pendingSplitPrompt)
            assertEquals(listOf("a.log", "b.log"), pending.sources.map { it.displayName }, "a.log must not be dropped")
        } finally {
            app.close()
        }
    }

    // The archive picker's video choice used to be dropped when a memory (or size) prompt intercepted the open.
    @Test
    fun zipWithAVideoUnderAMemoryPromptStillAttachesTheVideoAfterOpenAsIs() {
        val dir = newDir()
        val archive = zip(
            dir, "bugreport.zip",
            mapOf("logs/main.log" to logLines(5), "screen/recording.mp4" to "not decoded by this test"),
        )
        val app = newApp(dir, free = 1L)
        try {
            app.openZipFile(archive)
            val picker = assertNotNull(app.pendingZipPicker)
            val video = picker.videoCandidates.single()
            assertEquals(ZipLogCandidateKind.VIDEO, video.kind)

            val tabIds = app.openZipEntries(archive, picker.candidates, video)

            assertTrue(tabIds.isEmpty())
            val pending = assertNotNull(app.pendingSplitPrompt)
            assertEquals(PendingPromptVideo.FromArchive(archive, video), pending.video)
            val source = pending.sources.single()

            app.confirmSplitPrompt(mapOf(source.id to SplitMode.OPEN_AS_IS), File(dir, "parts"), "part", emptyMap())

            waitUntil { app.tabs.size == 1 && app.tabs.single().attachedVideo != null }
            assertEquals("main.log", app.tabs.single().filename)
        } finally {
            app.close()
        }
    }

    @Test
    fun zipWithAVideoUnderAMemoryPromptAttachesTheVideoToTheOpenedSplitPart() {
        val dir = newDir()
        val archive = zip(
            dir, "bugreport.zip",
            mapOf("logs/main.log" to logLines(200), "screen/recording.mp4" to "not decoded by this test"),
        )
        val app = newApp(dir, free = 1L)
        try {
            app.openZipFile(archive)
            val picker = assertNotNull(app.pendingZipPicker)
            app.openZipEntries(archive, picker.candidates, picker.videoCandidates.single())
            val source = assertNotNull(app.pendingSplitPrompt).sources.single()

            app.confirmSplitPrompt(mapOf(source.id to SplitMode.SPLIT), File(dir, "parts"), "part", mapOf(source.id to 2))

            waitUntil { app.tabs.size == 1 && app.tabs.single().attachedVideo != null && app.openError != null }
            assertFalse(app.tabs.single().attachedVideo == null)
        } finally {
            app.close()
        }
    }

    @Test
    fun logAndVideoDropUnderAMemoryPromptStillAttachesTheVideoAfterTheUserResolvesIt() {
        val dir = newDir()
        val log = File(dir, "new.log").apply { writeText(logLines(5)) }
        val video = File(dir, "new.mp4").apply { writeText("not decoded by this test") }
        val app = newApp(dir, free = 1L)
        try {
            app.openDroppedFiles(listOf(log, video))

            val pending = assertNotNull(app.pendingSplitPrompt)
            assertEquals(PendingPromptVideo.FromFile(video), pending.video)
            assertTrue(app.tabs.isEmpty())
            val source = pending.sources.single()

            app.confirmSplitPrompt(mapOf(source.id to SplitMode.OPEN_AS_IS), File(dir, "parts"), "part", emptyMap())

            waitUntil { app.tabs.size == 1 && app.tabs.single().attachedVideo != null }
            assertEquals(video.absolutePath, app.tabs.single().attachedVideo?.path)
        } finally {
            app.close()
        }
    }
}
