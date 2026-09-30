package com.indagium.ui

import com.indagium.utils.MemoryShortfall
import com.indagium.utils.SPLIT_PROMPT_BYTES
import java.io.File
import java.io.RandomAccessFile
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
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
            "Needs about 3.1 GB of memory; about 2.0 GB is free. Split it, close other tabs, or open anyway.",
            memoryShortfallLine(MemoryShortfall((3.1 * gb).toLong(), 2 * gb)),
        )
        assertEquals(
            "Needs about 350.0 MB of memory; about 200.0 MB is free. Split it, close other tabs, or open anyway.",
            memoryShortfallLine(MemoryShortfall(350 * mb, 200 * mb)),
        )
    }
}
