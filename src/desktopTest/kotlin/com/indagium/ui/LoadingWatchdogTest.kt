package com.indagium.ui

import com.indagium.debug.AppLogger
import com.indagium.model.LogEntry
import com.indagium.model.LogFormat
import com.indagium.model.LogLevel
import com.indagium.utils.ParsedLog
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.currentTime
import kotlinx.coroutines.test.runTest
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.io.path.createTempDirectory
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

@OptIn(ExperimentalCoroutinesApi::class)
class LoadingWatchdogTest {
    @AfterTest
    fun tearDown() {
        AppLogger.configure(enabled = false, path = null)
    }

    private val stuck = LoadingSnapshot(
        pendingLoads = 1,
        status = "Restoring session...",
        restoreLoadsQueued = 0,
        loads = listOf(
            LoadingLoadInfo("t7", "capture.ind", "restore", "capture", "active", countsAsLoading = true),
        ),
    )

    @Test
    fun aLoadThatStaysPendingWarnsExactlyOnceWithStateAndThreadDump() = runTest {
        val warnings = mutableListOf<String>()
        val infos = mutableListOf<String>()
        val watchdog = LoadingWatchdog(
            scope = backgroundScope,
            config = LoadingWatchdogConfig(warnAfterMs = 10_000, followUpsMs = emptyList()),
            snapshot = { stuck },
            warn = { warnings += it },
            info = { infos += it },
            threadDump = { listOf("${LoadingWatchdog.THREAD_DUMP_MARKER} 1 threads") },
            clockMs = { currentTime },
        )

        watchdog.episodeStarted()
        advanceTimeBy(9_999)
        assertTrue(warnings.isEmpty(), "nothing is logged before the threshold")
        advanceTimeBy(2)
        assertEquals(1, warnings.size)
        val report = warnings.single()
        assertTrue("Restoring session..." in report)
        assertTrue("t7" in report && "capture.ind" in report && "job=active" in report)
        assertTrue("pendingLoads=1" in report)
        assertTrue(LoadingWatchdog.THREAD_DUMP_MARKER in report)
        advanceTimeBy(120_000)
        assertEquals(1, warnings.size, "no follow-ups were configured, and the warning never repeats")

        advanceTimeBy(1_000)
        watchdog.episodeEnded()
        assertEquals(1, infos.size)
        assertTrue("finished after" in infos.single())
    }

    @Test
    fun aQuickLoadLogsNothing() = runTest {
        val warnings = mutableListOf<String>()
        val infos = mutableListOf<String>()
        val watchdog = LoadingWatchdog(
            scope = backgroundScope,
            config = LoadingWatchdogConfig(warnAfterMs = 10_000),
            snapshot = { stuck },
            warn = { warnings += it },
            info = { infos += it },
            clockMs = { currentTime },
        )

        watchdog.episodeStarted()
        advanceTimeBy(2_000)
        watchdog.episodeEnded()
        advanceTimeBy(100_000)

        assertTrue(warnings.isEmpty())
        assertTrue(infos.isEmpty())
    }

    @Test
    fun followUpsAtThirtyAndSixtySecondsAndASecondDumpAtThirty() = runTest {
        val warnings = mutableListOf<String>()
        var dumps = 0
        val watchdog = LoadingWatchdog(
            scope = backgroundScope,
            config = LoadingWatchdogConfig(),
            snapshot = { stuck },
            warn = { warnings += it },
            info = {},
            threadDump = { listOf("${LoadingWatchdog.THREAD_DUMP_MARKER} dump#${++dumps}") },
            clockMs = { currentTime },
        )

        watchdog.episodeStarted()
        advanceTimeBy(10_001)
        assertEquals(1, warnings.size)
        advanceTimeBy(20_000)
        assertEquals(2, warnings.size)
        assertTrue("dump#2" in warnings[1], "the 30 s follow-up repeats the thread dump")
        assertTrue("STILL active" in warnings[1])
        advanceTimeBy(30_000)
        assertEquals(3, warnings.size)
        assertFalse(LoadingWatchdog.THREAD_DUMP_MARKER in warnings[2], "the 60 s follow-up is short")
        assertEquals(2, dumps)
    }

    @Test
    fun aNewEpisodeAfterTheFirstEndedIsWatchedAgain() = runTest {
        val warnings = mutableListOf<String>()
        val watchdog = LoadingWatchdog(
            scope = backgroundScope,
            config = LoadingWatchdogConfig(warnAfterMs = 1_000, followUpsMs = emptyList()),
            snapshot = { stuck },
            warn = { warnings += it },
            info = {},
            threadDump = { emptyList() },
            clockMs = { currentTime },
        )

        repeat(2) {
            watchdog.episodeStarted()
            advanceTimeBy(1_500)
            watchdog.episodeEnded()
        }

        assertEquals(2, warnings.size)
    }

    @Test
    fun theRealThreadDumpNamesThreadsStatesAndStacks() {
        val lines = captureThreadDumpLines()
        assertTrue(lines.all { it.startsWith(LoadingWatchdog.THREAD_DUMP_MARKER) })
        val own = lines.single { it.contains("\"${Thread.currentThread().name}\"") }
        assertTrue("state=RUNNABLE" in own)
        assertTrue("captureThreadDumpLines" in own)
    }

    @Test
    fun appStateLogsOneWarningWithTheStuckTabAndTheEndOfTheEpisode() {
        val dir = createTempDirectory("loading-watchdog-appstate").toFile()
        val logFile = File(dir, "debug.log")
        val slow = File(dir, "slow.log").apply { writeText("x") }
        val started = CountDownLatch(1)
        val release = CountDownLatch(1)
        val state = AppState(
            autosaveFile = File(dir, "state.cache"),
            parser = { file ->
                started.countDown()
                release.await(10, TimeUnit.SECONDS)
                ParsedLog(LogFormat.LOGCAT, listOf(LogEntry(1, "", LogLevel.I, file.nameWithoutExtension, file.name)))
            },
            autoExportNotes = false,
            loadingWatchdogConfig = LoadingWatchdogConfig(warnAfterMs = 150, followUpsMs = emptyList()),
        )
        try {
            state.updateSettings { it.copy(debugLogFilePath = logFile.absolutePath) }
            state.setDebugLoggingEnabled(true)
            state.openFile(slow)
            assertTrue(started.await(5, TimeUnit.SECONDS))
            assertTrue(state.isLoading)
            waitFor { logFile.exists() && logFile.readText().contains("Global loading still active") }
            release.countDown()
            waitFor { !state.isLoading && logFile.readText().contains("Global loading finished after") }

            val text = logFile.readText()
            assertEquals(1, Regex("Global loading still active").findAll(text).count())
            assertTrue("pendingLoads=1" in text)
            assertTrue("status=Loading file..." in text)
            assertTrue("load tab=" in text)
            assertTrue(LoadingWatchdog.THREAD_DUMP_MARKER in text)
            assertTrue("job=active" in text)
        } finally {
            release.countDown()
            state.close()
        }
    }

    private fun waitFor(timeoutMs: Long = 10_000, condition: () -> Boolean) {
        val deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(timeoutMs)
        while (System.nanoTime() < deadline) {
            if (condition()) return
            Thread.sleep(10)
        }
        assertTrue(condition())
    }
}
