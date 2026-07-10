package com.openlog

import com.openlog.debug.ControlServer
import com.openlog.ui.AppState
import java.io.File
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.io.path.createTempDirectory
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

private const val AWAIT_SECONDS = 10L
private const val OPEN_THREAD_COUNT = 12
private const val MERGE_THREAD_COUNT = 8

// A-01: AppState's tabs list is mutated from at least four independent thread contexts in
// production (the Compose/AWT UI thread, the single-instance accept thread, ControlServer's Ktor
// request threads, and AppState's own ioScope). These tests drive real concurrent java.lang.Thread
// callers — not just interleaved coroutines on one thread, which wouldn't exercise the actual
// cross-thread races — against a single AppState instance and assert the properties the A-01
// hardening (atomic tabCounter, ConcurrentHashMap resource maps, atomic closeTabsById, atomic
// control-server generation counter) is meant to guarantee.
class ConcurrentStateMutationTest {
    private fun newState(dir: File) = AppState(autosaveFile = File(dir, "state.cache"))

    // Every thread blocks on the same CountDownLatch until released together, maximizing
    // contention on tabCounter.getAndIncrement() at the moment they all proceed — a loose,
    // unsynchronized start would let threads trickle in one at a time and never actually race.
    private fun runConcurrently(count: Int, action: (Int) -> Unit) {
        val ready = CountDownLatch(count)
        val go = CountDownLatch(1)
        val done = CountDownLatch(count)
        val threads = (0 until count).map { i ->
            Thread {
                ready.countDown()
                go.await(AWAIT_SECONDS, TimeUnit.SECONDS)
                try {
                    action(i)
                } finally {
                    done.countDown()
                }
            }.apply { isDaemon = true; start() }
        }
        assertTrue(ready.await(AWAIT_SECONDS, TimeUnit.SECONDS), "threads failed to reach the start gate")
        go.countDown()
        assertTrue(done.await(AWAIT_SECONDS, TimeUnit.SECONDS), "threads failed to finish")
        threads.forEach { it.join(TimeUnit.SECONDS.toMillis(AWAIT_SECONDS)) }
    }

    private fun waitUntil(timeoutMs: Long = TimeUnit.SECONDS.toMillis(AWAIT_SECONDS), condition: () -> Boolean) {
        val deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(timeoutMs)
        while (!condition()) {
            check(System.nanoTime() < deadline) { "condition not met within ${timeoutMs}ms" }
            Thread.sleep(10)
        }
    }

    @Test
    fun concurrentOpenFileCallsFromMultipleThreadsNeverProduceDuplicateTabIds() {
        val dir = createTempDirectory("openlog-concurrent-open").toFile()
        val state = newState(dir)
        val files = (0 until OPEN_THREAD_COUNT).map { i ->
            File(dir, "file$i.log").apply { writeText("line one\nline two\n") }
        }
        val returnedIds = ConcurrentLinkedQueue<String>()

        runConcurrently(OPEN_THREAD_COUNT) { i ->
            state.openFile(files[i])?.let(returnedIds::add)
        }
        waitUntil { !state.isLoading }

        assertEquals(OPEN_THREAD_COUNT, returnedIds.size, "every concurrent open must allocate a tab id")
        assertEquals(returnedIds.size, returnedIds.distinct().size, "no two concurrent opens may share a tab id")
        assertEquals(OPEN_THREAD_COUNT, state.tabs.size)
        assertEquals(state.tabs.size, state.tabs.map { it.id }.distinct().size, "tabs list must never contain a duplicate id")
    }

    @Test
    fun concurrentOpenAndMergeCallsAcrossDifferentCallSitesNeverProduceDuplicateTabIds() {
        val dir = createTempDirectory("openlog-concurrent-mixed").toFile()
        val state = newState(dir)
        // Pre-open two source tabs synchronously, outside the timed race, so mergeTabs has
        // something real to merge once the race starts.
        val source1 = File(dir, "source1.log").apply { writeText("a\n") }
        val source2 = File(dir, "source2.log").apply { writeText("b\n") }
        val id1 = state.openFile(source1)
        val id2 = state.openFile(source2)
        waitUntil { !state.isLoading }
        checkNotNull(id1)
        checkNotNull(id2)

        val openFiles = (0 until OPEN_THREAD_COUNT).map { i ->
            File(dir, "concurrent$i.log").apply { writeText("x\n") }
        }

        // Threads 0..<OPEN_THREAD_COUNT call openFile (openFileInternal's tabCounter site);
        // threads OPEN_THREAD_COUNT..< call mergeTabs (mergeTabs' own separate tabCounter site) —
        // proving the fix holds when different call sites race each other, not just one.
        runConcurrently(OPEN_THREAD_COUNT + MERGE_THREAD_COUNT) { i ->
            if (i < OPEN_THREAD_COUNT) {
                state.openFile(openFiles[i])
            } else {
                state.mergeTabs(listOf(id1, id2), "merged-$i")
            }
        }
        waitUntil { !state.isLoading }

        val expectedCount = 2 + OPEN_THREAD_COUNT + MERGE_THREAD_COUNT
        waitUntil { state.tabs.size == expectedCount }
        assertEquals(expectedCount, state.tabs.map { it.id }.distinct().size, "tabs list must never contain a duplicate id")
    }

    @Test
    fun closingATabWhileItIsActivelyTailingNeverThrowsAndLeavesTheTabFullyGone() {
        val dir = createTempDirectory("openlog-concurrent-tail").toFile()
        val state = newState(dir)
        val file = File(dir, "growing.log").apply { writeText("start\n") }
        val tabId = checkNotNull(state.openFile(file))
        waitUntil { !state.isLoading }

        state.startTailing(tabId)

        val appender = Thread {
            repeat(20) { n ->
                runCatching { file.appendText("appended line $n\n") }
                Thread.sleep(20)
            }
        }.apply { isDaemon = true; start() }

        // No fixed delay is "correct" here — the point is to close while the appender/tailer are
        // still actively racing, not to hit one exact interleaving. Repeated close/open cycles
        // below (regressionAfterRace) prove global state (tabCounter, etc.) survives regardless of
        // exactly which interleaving this run happened to hit.
        Thread.sleep(150)
        state.closeTab(tabId)
        appender.join(TimeUnit.SECONDS.toMillis(AWAIT_SECONDS))

        assertNull(state.tab(tabId), "closed tab must be fully gone from tabs")
        assertTrue(state.tabs.none { it.id == tabId })

        // stopTailing on an already-closed tab must be a safe no-op (idempotent double-close), and
        // the tabCounter/stateLock machinery must still be healthy for a subsequent open.
        state.stopTailing(tabId)
        val next = File(dir, "after-race.log").apply { writeText("ok\n") }
        val nextId = state.openFile(next)
        waitUntil { !state.isLoading }
        assertTrue(nextId != null && nextId != tabId)
        assertTrue(state.tabs.any { it.id == nextId })
    }

    @Test
    fun concurrentControlServerToggleFromMultipleThreadsEndsWithNoServerLeftBoundAfterFinalDisable() {
        val dir = createTempDirectory("openlog-concurrent-mcp").toFile()
        // Slow, real factory (same seam Task 02's S-02 tests use) — artificially widens the bind
        // window so concurrent enable/disable calls actually overlap an in-flight start instead of
        // completing one at a time.
        val state = AppState(
            autosaveFile = File(dir, "state.cache"),
            controlServerFactory = { s, p -> kotlinx.coroutines.delay(50); ControlServer(s, p).also { it.start() } },
        )

        // Each "enable" gets its own freshly-obtained free port so real OS-level bind collisions
        // (a different, pre-existing concern outside A-01) don't mask what's actually under test
        // here: whether the generation-counter/@Volatile publish stay consistent when the shared
        // controlServer/controlServerStartGeneration fields are toggled from many threads at once.
        runConcurrently(24) { i ->
            if (i % 2 == 0) {
                val port = java.net.ServerSocket(0).use { it.localPort }
                state.setMcpControlEnabled(true, port)
            } else {
                state.setMcpControlEnabled(false, state.settings.mcpControlPort)
            }
        }

        // Whatever nondeterministic state the race above left things in, a final explicit disable
        // must deterministically win — if the AtomicInteger generation counter or @Volatile
        // publish were still broken, a stale start from the race above could resurrect a server
        // after this call returns.
        state.setMcpControlEnabled(false, state.settings.mcpControlPort)
        Thread.sleep(400)
        assertNull(state.controlServerToken(), "a final disable must leave no server bound regardless of prior concurrent toggling")
    }
}
