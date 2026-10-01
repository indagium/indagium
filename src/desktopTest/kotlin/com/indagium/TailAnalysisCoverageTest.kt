@file:Suppress("MagicNumber")

package com.indagium

import com.indagium.model.LogAnalysis
import com.indagium.model.LogEntry
import com.indagium.model.LogItem
import com.indagium.model.LogLevel
import com.indagium.ui.AppState
import com.indagium.ui.buildLogAnalysis
import com.indagium.ui.mkTab
import com.indagium.utils.computeItems
import java.io.File
import java.util.concurrent.TimeUnit
import kotlin.io.path.createTempDirectory
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * During a live capture the full analysis always lags the rows by at least one batch. It used to be
 * hidden (`pending = true`) whenever it was not exactly current, so the ~30 s refresh was wasted work.
 * Now it records how far it reaches (`analyzedThroughId`): the analysed prefix's results stay visible,
 * only the rows beyond are unanalysed.
 */
class TailAnalysisCoverageTest {
    private fun entry(id: Int, msg: String, tag: String = "App", level: LogLevel = LogLevel.I) =
        LogEntry(id, "10:00:00.%03d".format(id), level, tag, msg, pid = 100, tid = 100)

    // ids 1..4: a fatal exception trace; 5..6 plain; 7..10 a second trace that arrives later.
    private fun traceRows(from: Int) = listOf(
        entry(from, "FATAL EXCEPTION: main", "AndroidRuntime", LogLevel.E),
        entry(from + 1, "java.lang.IllegalStateException: boom", "AndroidRuntime", LogLevel.E),
        entry(from + 2, "\tat com.app.Main.run(Main.java:10)", "AndroidRuntime", LogLevel.E),
        entry(from + 3, "\tat com.app.Main.main(Main.java:5)", "AndroidRuntime", LogLevel.E),
    )

    private val prefix = traceRows(1) + entry(5, "plain a") + entry(6, "plain b")
    private val all = prefix + traceRows(7)

    private fun waitUntil(timeoutMs: Long = TimeUnit.SECONDS.toMillis(15), condition: () -> Boolean) {
        val deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(timeoutMs)
        while (!condition()) {
            check(System.nanoTime() < deadline) { "condition not met within ${timeoutMs}ms" }
            Thread.sleep(10)
        }
    }

    private fun visibleIds(items: List<LogItem>): List<Int> = items.map {
        when (it) {
            is LogItem.Row -> it.entry.id
            is LogItem.SeqHeader -> it.entry.id
            is LogItem.ManualHeader -> it.entry.id
            is LogItem.StackTraceHeader -> it.entry.id
        }
    }

    @Test
    fun theAnalysedPrefixFoldsWhileRowsBeyondItStayUnfolded() {
        val analysis = buildLogAnalysis(prefix).copy(pending = false, analyzedThroughId = 6)
        assertEquals(1, analysis.stackTraceGroups.size, "fixture: only the first trace is analysed")
        val tab = mkTab("t", "f", all, analysis = analysis)

        val ids = visibleIds(computeItems(tab, applyFilter = false))

        // The analysed trace collapses to its header (ids 2..4 hidden); the unanalysed one shows every row.
        assertEquals(listOf(1, 5, 6, 7, 8, 9, 10), ids)
        assertFalse(analysis.isStaleFor(6))
        assertTrue(analysis.isStaleFor(10), "the appended rows are reported as not analysed yet")
    }

    @Test
    fun aPendingInitialAnalysisStillRendersUnfolded() {
        val tab = mkTab("t", "f", all, analysis = LogAnalysis()) // pending = true: the initial load

        assertEquals((1..10).toList(), visibleIds(computeItems(tab, applyFilter = false)))
        assertTrue(tab.analysis.isStaleFor(10))
    }

    @Test
    fun tailedBatchesKeepTheCrashResultsVisibleAndTheRefreshCompletesTheAnalysis() {
        val dir = createTempDirectory("openlog-tail-coverage").toFile()
        val file = File(dir, "app.log").apply {
            writeText(
                "01-02 03:04:05.001  100  100 E AndroidRuntime: FATAL EXCEPTION: main\n" +
                    "01-02 03:04:05.002  100  100 E AndroidRuntime: java.lang.IllegalStateException: boom\n" +
                    "01-02 03:04:05.003  100  100 E AndroidRuntime: \tat com.app.Main.run(Main.java:10)\n" +
                    "01-02 03:04:05.004  100  100 I App: plain\n",
            )
        }
        val state = AppState(autosaveFile = File(dir, "state.cache"), autoExportNotes = false)
        try {
            val tabId = checkNotNull(state.openFile(file))
            waitUntil { !state.isLoading && state.tab(tabId)?.analysis?.pending == false }
            assertEquals(1, state.tab(tabId)!!.analysis.crashSites.size)

            state.startTailing(tabId)
            file.appendText("01-02 03:04:06.001  100  100 I App: more one\n01-02 03:04:06.002  100  100 I App: more two\n")
            waitUntil { state.tab(tabId)!!.logData.size == 6 }

            // The batch landed, the 1.5 s refresh has not run: the analysed prefix must still be visible.
            val duringTail = state.tab(tabId)!!.analysis
            assertFalse(duringTail.pending, "a batch must not hide the analysed prefix's results")
            assertEquals(1, duringTail.crashSites.size)

            // Stop's final drain: the tab ends with a complete, non-pending analysis.
            file.appendText("01-02 03:04:07.001  100  100 I App: late\n")
            state.drainAndStopTailing(tabId)
            waitUntil {
                val tab = state.tab(tabId)!!
                tab.logData.size == 7 && !tab.analysis.pending && tab.analysis.analyzedThroughId == null
            }
            assertEquals(1, state.tab(tabId)!!.analysis.crashSites.size)
            assertFalse(state.tab(tabId)!!.analysis.isStaleFor(state.tab(tabId)!!.logData.last().id))
        } finally {
            state.close()
        }
    }

    @Test
    fun aTabWhoseInitialAnalysisIsStillPendingStaysPendingWhileRowsAreAppended() {
        val dir = createTempDirectory("openlog-tail-coverage-pending").toFile()
        val file = File(dir, "app.log").apply { writeText("01-02 03:04:05.001  100  100 I App: first\n") }
        val state = AppState(autosaveFile = File(dir, "state.cache"), autoExportNotes = false)
        try {
            val tabId = checkNotNull(state.openFile(file))
            waitUntil { !state.isLoading && state.tab(tabId)?.analysis?.pending == false }
            // Pretend the initial analysis has not landed yet.
            state.upTab(tabId) { it.copy(analysis = it.analysis.copy(pending = true)) }

            state.startTailing(tabId)
            file.appendText("01-02 03:04:06.001  100  100 I App: second\n")
            waitUntil { state.tab(tabId)!!.logData.size == 2 }

            val analysis = state.tab(tabId)!!.analysis
            assertTrue(analysis.pending, "nothing is analysed yet, so the initial 'analyzing' state stays")
            assertNull(analysis.analyzedThroughId)
            state.stopTailing(tabId)
        } finally {
            state.close()
        }
    }
}
