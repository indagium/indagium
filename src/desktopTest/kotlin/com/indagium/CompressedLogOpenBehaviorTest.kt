package com.indagium

import com.indagium.ui.AppState
import org.apache.commons.compress.compressors.gzip.GzipCompressorOutputStream
import java.io.File
import kotlin.io.path.createTempDirectory
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

// AppState-level behavior for a bare compressed log (foo.log.gz) — CompressedLogFileTest covers
// the same feature at the utils level (parseLogFile/parseCompressedLog in isolation).
class CompressedLogOpenBehaviorTest {
    private fun waitUntil(timeoutMs: Long = 10_000, condition: () -> Boolean) {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            if (condition()) return
            Thread.sleep(10)
        }
        assertTrue(condition(), "condition not met within ${timeoutMs}ms")
    }

    private fun buildGzipLog(dir: File, name: String, text: String): File {
        val file = File(dir, name)
        GzipCompressorOutputStream(file.outputStream()).use { it.write(text.toByteArray()) }
        return file
    }

    private val sampleLog = "06-26 10:00:00.000  100  200 I App: hello\n06-26 10:00:00.500  100  200 E App: boom\n"

    @Test
    fun opensAsOneTabWithNoPickerAndAPlainSourcePath() {
        val dir = createTempDirectory("compressed-log-open").toFile()
        val gz = buildGzipLog(dir, "logcat.txt.gz", sampleLog)
        val state = AppState(File(dir, "state.cache"))

        state.openPath(gz)
        waitUntil { state.tabs.size == 1 && !state.isLoading }

        val tab = state.tabs.single()
        assertEquals(gz.absolutePath, tab.sourcePath)
        assertFalse('!' in (tab.sourcePath ?: ""))
        assertNull(tab.archiveCandidate)
        assertNull(state.pendingZipPicker)
        assertEquals(2, tab.logData.size)
        assertEquals("boom", tab.logData[1].msg)
    }

    @Test
    fun reopeningTheSameCompressedLogDedupsToTheExistingTabInsteadOfOpeningASecondOne() {
        val dir = createTempDirectory("compressed-log-open").toFile()
        val gz = buildGzipLog(dir, "logcat.txt.gz", sampleLog)
        val state = AppState(File(dir, "state.cache"))

        state.openPath(gz)
        waitUntil { state.tabs.size == 1 && !state.isLoading }
        val firstTabId = state.tabs.single().id

        state.openPath(gz)

        assertEquals(1, state.tabs.size)
        assertEquals(firstTabId, state.activeTabId)
    }

    @Test
    fun compressedLogNeverTriggersTheSplitPromptEvenAboveTheThreshold() {
        val dir = createTempDirectory("compressed-log-open").toFile()
        val gz = buildGzipLog(dir, "big.log.gz", "A".repeat(5_000))
        val state = AppState(File(dir, "state.cache"))

        // Threshold set to the compressed file's own on-disk length, so a length()-based gate (the
        // pre-fix behavior) would trigger the prompt here too — ArchiveFormat.None is what gates
        // it now (see openPaths' comment), and a CompressedFile never qualifies.
        state.openPaths(listOf(gz), splitPromptThresholdBytes = gz.length())

        assertNull(state.pendingSplitPrompt)
        waitUntil { state.tabs.size == 1 && !state.isLoading }
    }

    @Test
    fun autosaveRestoreRoundTripsTheSameDecompressedLogData() {
        val dir = createTempDirectory("compressed-log-restore").toFile()
        val gz = buildGzipLog(dir, "logcat.txt.gz", sampleLog)
        val cacheFile = File(dir, "state.cache")
        val state = AppState(cacheFile)

        state.openPath(gz)
        waitUntil { state.tabs.size == 1 && !state.isLoading }
        val original = state.tabs.single()
        state.autosaveNow()

        val restored = AppState(cacheFile, restoreOnCreate = true)
        restored.startPendingRestoredTabLoads()
        waitUntil { restored.tabs.size == 1 && !restored.isLoading }

        // Fails without the AppState `parser` seam defaulting to ::parseLogFile — loadRestoredTab
        // would otherwise re-parse the raw gzip bytes as garbage RAW logcat lines on every
        // relaunch of a restored .log.gz tab instead of decompressing first.
        assertEquals(original.logData, restored.tabs.single().logData)
        assertEquals(gz.absolutePath, restored.tabs.single().sourcePath)
    }

    @Test
    fun startTailingOnACompressedLogLeavesItNotTailing() {
        val dir = createTempDirectory("compressed-log-open").toFile()
        val gz = buildGzipLog(dir, "logcat.txt.gz", sampleLog)
        val state = AppState(File(dir, "state.cache"))

        state.openPath(gz)
        waitUntil { state.tabs.size == 1 && !state.isLoading }
        val tabId = state.tabs.single().id

        state.startTailing(tabId)

        assertFalse(state.tab(tabId)!!.tailing)
    }
}
