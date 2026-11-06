package com.indagium.utils

import com.indagium.model.LogEntry
import com.indagium.model.LogLevel
import com.indagium.ui.mkTab
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlin.test.Test
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

class ComputedCacheInvalidationTest {
    @Test
    fun anInvalidatedComputationCannotReplaceANewerCache() {
        val count = CANCELLATION_CHECK_INTERVAL * 4
        val rows = listOf(
            LogEntry(1, "10:00:00.000", LogLevel.E, "AndroidRuntime", "FATAL EXCEPTION: main", pid = 1),
            LogEntry(2, "10:00:00.001", LogLevel.E, "AndroidRuntime", "    at com.app.Main.onCreate(Main.java:1)", pid = 1),
        ) + (3..count).map { LogEntry(it, "10:00:00.000", LogLevel.I, "Synthetic", "row $it") }
        val old = mkTab("invalidate-before-new", "old.log", rows)
        val fresh = mkTab(old.id, "new.log", rows.toList())
        check(old.analysis.stackTraceGroups.isNotEmpty())
        val checkpoint = CountDownLatch(1)
        val resume = CountDownLatch(1)
        val executor = Executors.newSingleThreadExecutor()
        invalidateComputeCache(old.id)
        try {
            val result = executor.submit<List<com.indagium.model.LogItem>> {
                computeItems(old, true, cancellationCheck = {
                    checkpoint.countDown()
                    check(resume.await(10, TimeUnit.SECONDS))
                })
            }
            assertTrue(checkpoint.await(10, TimeUnit.SECONDS))
            invalidateComputeCache(old.id)
            computeItems(fresh, true)
            val freshVisible = assertNotNull(cachedVisibleEntriesFor(fresh, true))
            resume.countDown()
            result.get(10, TimeUnit.SECONDS)
            assertSame(freshVisible, cachedVisibleEntriesFor(fresh, true), "old computation must not replace the new cache")
        } finally {
            resume.countDown()
            executor.shutdownNow()
            invalidateComputeCache(old.id)
        }
    }

    @Test
    fun invalidationPreventsAnEarlierComputationFromRepopulatingTheCache() {
        val count = CANCELLATION_CHECK_INTERVAL * 4
        val rows = listOf(
            LogEntry(1, "10:00:00.000", LogLevel.E, "AndroidRuntime", "FATAL EXCEPTION: main", pid = 1),
            LogEntry(2, "10:00:00.001", LogLevel.E, "AndroidRuntime", "    at com.app.Main.onCreate(Main.java:1)", pid = 1),
        ) + (3..count).map { LogEntry(it, "10:00:00.000", LogLevel.I, "Synthetic", "row $it") }
        val tab = mkTab("invalidate-in-flight", "synthetic.log", rows)
        check(tab.analysis.stackTraceGroups.isNotEmpty())
        val checkpoint = CountDownLatch(1)
        val resume = CountDownLatch(1)
        val executor = Executors.newSingleThreadExecutor()
        invalidateComputeCache(tab.id)
        try {
            val result = executor.submit<List<com.indagium.model.LogItem>> {
                computeItems(tab, applyFilter = true, cancellationCheck = {
                    checkpoint.countDown()
                    check(resume.await(10, TimeUnit.SECONDS)) { "test did not resume computation" }
                })
            }
            assertTrue(checkpoint.await(10, TimeUnit.SECONDS), "computation must reach a checkpoint")
            invalidateComputeCache(tab.id)
            resume.countDown()
            result.get(10, TimeUnit.SECONDS)
            assertNull(cachedVisibleEntriesFor(tab, true), "an invalidated computation must not publish its cache")
        } finally {
            resume.countDown()
            executor.shutdownNow()
            invalidateComputeCache(tab.id)
        }
    }
}
