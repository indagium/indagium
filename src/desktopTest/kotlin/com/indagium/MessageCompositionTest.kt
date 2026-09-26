package com.indagium

import com.indagium.model.Filter
import com.indagium.model.LogFormat
import com.indagium.model.MessageCompositionState
import com.indagium.model.MessageTemplateHistogram
import com.indagium.model.TemplateGranularity
import com.indagium.ui.AppState
import com.indagium.ui.mkTab
import com.indagium.ui.persistedSnapshot
import com.indagium.utils.ParsedLog
import com.indagium.utils.parseLogcat
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.io.path.createTempDirectory
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

/**
 * Stage 2a: AppState.requestMessageComposition and LogTab.messageComposition — the on-demand
 * histogram moved off LogAnalysis (see model/Model.kt's MessageCompositionState doc) so that
 * TailCoordinator's debounced LogAnalysis rebuild can never silently discard it, and so the ~4s
 * scan on a large file is paid only when the "Log composition" filter-panel section is actually
 * expanded rather than on every load.
 */
class MessageCompositionTest {
    private fun waitUntil(timeoutMs: Long = 10_000, condition: () -> Boolean) {
        val deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(timeoutMs)
        while (System.nanoTime() < deadline) {
            if (condition()) return
            Thread.sleep(10)
        }
        assertTrue(condition())
    }

    // ── The four states are distinct, at the type level, independent of any timing ─────────────

    @Test
    fun theFourCompositionStatesAreAllDistinctFromEachOtherIncludingComputedAndEmptyVsNotComputed() {
        val emptyHistogram = MessageTemplateHistogram(emptyList(), TemplateGranularity.STRICT, 0, 0, false)
        val states: List<MessageCompositionState> = listOf(
            MessageCompositionState.NotComputed,
            MessageCompositionState.Computing(Filter()),
            MessageCompositionState.Computed(emptyHistogram, Filter()),
            MessageCompositionState.Failed("boom"),
        )
        for (i in states.indices) {
            for (j in states.indices) {
                if (i == j) assertEquals(states[i], states[j]) else assertNotEquals(states[i], states[j])
            }
        }
    }

    // ── A freshly opened tab starts NotComputed: buildLogAnalysis no longer computes this eagerly ─

    @Test
    fun aFreshlyOpenedTabStartsNotComputedProvingTheEagerComputationIsGone() {
        val dir = createTempDirectory("openlog-composition-eager").toFile()
        val file = File(dir, "a.log").apply { writeText("06-26 10:00:00.000  100  100 I App: hello\n") }
        val state = AppState(autosaveFile = File(dir, "state.cache"))
        state.openFile(file)
        waitUntil { state.tabs.size == 1 && !state.isLoading }

        assertEquals(MessageCompositionState.NotComputed, state.tabs.single().messageComposition)
    }

    // ── Single-flight: triggering computation twice does not start a second job ────────────────

    @Test
    fun triggeringComputationTwiceInARowOnlyStartsOneJob() {
        val dir = createTempDirectory("openlog-composition-guard").toFile()
        val file = File(dir, "a.log").apply { writeText("06-26 10:00:00.000  100  100 I App: hello\n") }
        val state = AppState(autosaveFile = File(dir, "state.cache"))
        state.openFile(file)
        waitUntil { state.tabs.size == 1 && !state.isLoading }
        val tabId = state.tabs.single().id

        val first = state.requestMessageComposition(tabId)
        val second = state.requestMessageComposition(tabId)

        assertTrue(first, "the first call must actually start the scan")
        assertFalse(second, "a call while already Computing/Computed must be a no-op")

        waitUntil { state.tab(tabId)!!.messageComposition is MessageCompositionState.Computed }
        // Re-requesting once genuinely Computed is also a no-op — expanding the section twice
        // must never re-scan a file that's already been scanned.
        assertFalse(state.requestMessageComposition(tabId))
    }

    // ── Computed-and-empty is a real, reachable, distinct result ────────────────────────────────

    @Test
    fun aComputedEmptyHistogramIsDistinctFromNotComputed() {
        val dir = createTempDirectory("openlog-composition-empty").toFile()
        val state = AppState(autosaveFile = File(dir, "state.cache"))
        val tab = mkTab("t1", "empty.log", emptyList())
        state.tabs = listOf(tab)

        assertEquals(MessageCompositionState.NotComputed, state.tab(tab.id)!!.messageComposition)

        assertTrue(state.requestMessageComposition(tab.id))
        waitUntil { state.tab(tab.id)!!.messageComposition is MessageCompositionState.Computed }

        val composed: MessageCompositionState = state.tab(tab.id)!!.messageComposition
        assertTrue(composed is MessageCompositionState.Computed)
        assertTrue(composed.histogram.templates.isEmpty())
        assertTrue(composed != MessageCompositionState.NotComputed)
    }

    // ── Session-only: absent from persistedSnapshot() and does not survive an autosave round trip ─

    @Test
    fun theHistogramIsAbsentFromPersistedSnapshotAndDoesNotSurviveAnAutosaveRoundTrip() {
        val dir = createTempDirectory("openlog-composition-persist").toFile()
        val cacheFile = File(dir, "state.cache")
        val file = File(dir, "a.log").apply { writeText("06-26 10:00:00.000  100  100 I App: hello\n") }
        val state = AppState(autosaveFile = cacheFile)
        state.openFile(file)
        waitUntil { state.tabs.size == 1 && !state.isLoading }
        val tabId = state.tabs.single().id

        state.requestMessageComposition(tabId)
        waitUntil { state.tab(tabId)!!.messageComposition is MessageCompositionState.Computed }

        val computed = state.tab(tabId)!!
        // persistedSnapshot() must be identical whether or not the histogram has been computed —
        // proving it plays no part in the debounced-autosave key (AutosaveCodec.kt).
        assertEquals(
            computed.persistedSnapshot(),
            computed.copy(messageComposition = MessageCompositionState.NotComputed).persistedSnapshot(),
        )

        state.autosaveNow()
        waitUntil { cacheFile.exists() }

        val restored = AppState(autosaveFile = cacheFile, restoreOnCreate = true)
        restored.startPendingRestoredTabLoads()
        waitUntil { restored.tabs.size == 1 && !restored.isLoading }

        assertEquals(MessageCompositionState.NotComputed, restored.tabs.single().messageComposition)
    }

    @Test
    fun restoredShellRescansCompositionAfterDelayedRowsAndStackTraceAnalysisArrive() {
        val dir = createTempDirectory("openlog-composition-restored-shell").toFile()
        val cacheFile = File(dir, "state.cache")
        val logFile = File(dir, "restored.log").apply {
            writeText(
                listOf(
                    "06-26 10:00:00.000 100 100 I App: repeated ready",
                    "06-26 10:00:00.100 100 100 E AndroidRuntime: FATAL EXCEPTION: main",
                    "06-26 10:00:00.200 100 100 E AndroidRuntime: java.lang.NullPointerException: boom",
                    "06-26 10:00:00.300 100 100 E AndroidRuntime:     at com.app.Main.onCreate(Main.java:10)",
                    "06-26 10:00:00.400 100 100 E AndroidRuntime:     at android.app.Activity.performCreate(Activity.java:1)",
                    "06-26 10:00:00.500 100 100 I App: repeated ready",
                ).joinToString("\n"),
            )
        }
        val saved = AppState(autosaveFile = cacheFile)
        val shell = mkTab("t1", logFile.name, emptyList()).copy(sourcePath = logFile.absolutePath)
        saved.tabs = listOf(shell)
        saved.activeTabId = shell.id
        saved.autosaveNow()
        waitUntil { cacheFile.exists() }

        val parserEntered = CountDownLatch(1)
        val allowParserToFinish = CountDownLatch(1)
        val restored = AppState(
            autosaveFile = cacheFile,
            restoreOnCreate = true,
            parser = { file ->
                parserEntered.countDown()
                check(allowParserToFinish.await(10, TimeUnit.SECONDS))
                ParsedLog(LogFormat.LOGCAT, parseLogcat(file))
            },
        )
        val tabId = restored.tabs.single().id
        assertTrue(restored.requestMessageComposition(tabId))
        waitUntil {
            val state = restored.tab(tabId)?.messageComposition
            state is MessageCompositionState.Computed && state.histogram.totalEntries == 0
        }
        val emptyRevision = restored.tab(tabId)!!.messageCompositionRevision

        restored.startPendingRestoredTabLoads()
        assertTrue(parserEntered.await(5, TimeUnit.SECONDS), "restored parser should start after the initial empty-shell scan")
        assertEquals(0, restored.tab(tabId)!!.logData.size, "file parsing remains deliberately blocked")
        allowParserToFinish.countDown()

        waitUntil(timeoutMs = 15_000) {
            val tab = restored.tab(tabId) ?: return@waitUntil false
            !restored.isLoading && !tab.analysis.pending && tab.analysis.stackTraceGroups.isNotEmpty()
        }
        val loaded = restored.tab(tabId)!!
        assertEquals(6, loaded.logData.size)
        assertTrue(loaded.messageCompositionRevision >= emptyRevision + 2, "rows and full stack analysis each advance freshness")
        assertEquals(MessageCompositionState.NotComputed, loaded.messageComposition)

        assertTrue(restored.requestMessageComposition(tabId))
        waitUntil(timeoutMs = 15_000) {
            val tab = restored.tab(tabId) ?: return@waitUntil false
            val composition = tab.messageComposition as? MessageCompositionState.Computed
            composition?.forRevision == tab.messageCompositionRevision
        }
        val current = restored.tab(tabId)!!
        val composition = current.messageComposition as MessageCompositionState.Computed
        assertEquals(current.logData.size, composition.histogram.totalEntries)
        assertTrue(composition.histogram.countedEntries < composition.histogram.totalEntries, "full stack groups exclude their member rows")
    }

    // ── Tailing: never triggers the initial scan, never discards an existing one ────────────────

    @Test
    fun aTailFlushNeverTriggersTheInitialCompositionScan() {
        val dir = createTempDirectory("openlog-composition-tail-init").toFile()
        val file = File(dir, "tail.log").apply { writeText("06-26 10:00:00.000  100  100 I App: first\n") }
        val state = AppState(autosaveFile = File(dir, "state.cache"))
        state.openFile(file)
        waitUntil { state.tabs.size == 1 && !state.isLoading }
        val tabId = state.tabs.single().id
        assertEquals(MessageCompositionState.NotComputed, state.tab(tabId)!!.messageComposition)

        state.startTailing(tabId)
        file.appendText("06-26 10:00:01.000  100  100 I App: second\n")
        waitUntil { state.tab(tabId)!!.logData.size == 2 }

        assertEquals(MessageCompositionState.NotComputed, state.tab(tabId)!!.messageComposition)
        state.stopTailing(tabId)
    }

    @Test
    fun aTailFlushMergesIntoAnAlreadyComputedHistogramWithoutDiscardingIt() {
        val dir = createTempDirectory("openlog-composition-tail-merge").toFile()
        val file = File(dir, "tail.log").apply { writeText("06-26 10:00:00.000  100  100 I App: first\n") }
        val state = AppState(autosaveFile = File(dir, "state.cache"))
        state.openFile(file)
        waitUntil { state.tabs.size == 1 && !state.isLoading }
        val tabId = state.tabs.single().id

        state.requestMessageComposition(tabId)
        waitUntil { state.tab(tabId)!!.messageComposition is MessageCompositionState.Computed }
        val before = (state.tab(tabId)!!.messageComposition as MessageCompositionState.Computed).histogram
        assertEquals(1, before.totalEntries)

        state.startTailing(tabId)
        file.appendText("06-26 10:00:01.000  100  100 I App: second\n")
        waitUntil { state.tab(tabId)!!.logData.size == 2 }
        waitUntil {
            val composition = state.tab(tabId)!!.messageComposition
            composition is MessageCompositionState.Computed && composition.histogram.totalEntries == 2
        }

        val after = (state.tab(tabId)!!.messageComposition as MessageCompositionState.Computed).histogram
        assertEquals(2, after.totalEntries)
        assertEquals(2, after.countedEntries)

        state.stopTailing(tabId)
    }
}
