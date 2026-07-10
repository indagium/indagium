package com.openlog

import androidx.compose.ui.graphics.Color
import com.openlog.model.LogEntry
import com.openlog.model.LogLevel
import com.openlog.model.SequenceDef
import com.openlog.ui.mkTab
import com.openlog.utils.CANCELLATION_CHECK_INTERVAL
import com.openlog.utils.computeItems
import com.openlog.utils.computeSeqGroups
import kotlinx.coroutines.CancellationException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

private const val MILLIS_PER_SECOND = 1000

// P-01: computeItems/computeSeqGroups must actually stop when a caller-supplied
// cancellationCheck throws, not run to completion regardless. Each row count below is chosen to
// be many times CANCELLATION_CHECK_INTERVAL, so a checkpoint firing this early is only possible
// if the hot loop was genuinely interrupted partway — a computation that ran to completion first
// would never reach a low checkpoint count on a dataset this large in the first place, since the
// only place these lambdas are invoked from is inside the loop itself.
class ComputeItemsCancellationTest {
    private fun plainEntries(count: Int): List<LogEntry> =
        (1..count).map { LogEntry(it, "10:00:00.${it % MILLIS_PER_SECOND}", LogLevel.I, "App", "line $it") }

    @Test
    fun computeSeqGroupsStopsAtTheFirstCancellationCheckpointInsteadOfScanningTheWholeFile() {
        val rowCount = CANCELLATION_CHECK_INTERVAL * 10
        val logData = plainEntries(rowCount)
        val defs = listOf(SequenceDef(id = "s1", matchText = "never matches anything", priority = 1, color = Color.Red))
        var calls = 0

        assertFailsWith<CancellationException> {
            computeSeqGroups(logData, defs, cancellationCheck = { calls++; throw CancellationException("test cancel") })
        }

        assertEquals(1, calls, "the scan must stop at the very first checkpoint, not keep scanning past it")
    }

    @Test
    fun computeItemsRenderRangeStopsAtTheSecondCancellationCheckpointInsteadOfRenderingTheWholeFile() {
        // No sequences configured (default Filter()), so computeSeqGroups is never invoked here —
        // isolates the checkpoint inside renderRange specifically. A crash embedded in the data
        // gives computeItems a non-empty stackTraceGroups, which is what forces it past the
        // "nothing to fold, return a flat row list" fast path and into the actual renderRange walk.
        val rowCount = CANCELLATION_CHECK_INTERVAL * 10
        val crashLines = listOf(
            LogEntry(1, "10:00:00.000", LogLevel.E, "AndroidRuntime", "FATAL EXCEPTION: main", pid = 1),
            LogEntry(2, "10:00:00.001", LogLevel.E, "AndroidRuntime", "    at com.app.Main.onCreate(Main.java:1)", pid = 1),
        )
        val logData = crashLines + plainEntries(rowCount).drop(2)
        val tab = mkTab("t1", "big.log", logData)
        check(tab.analysis.stackTraceGroups.isNotEmpty()) { "fixture must actually produce a stack trace group" }
        var calls = 0

        assertFailsWith<CancellationException> {
            computeItems(
                tab,
                applyFilter = true,
                cancellationCheck = {
                    calls++
                    if (calls == 2) throw CancellationException("test cancel")
                },
            )
        }

        assertEquals(2, calls, "renderRange must stop at the second checkpoint, not keep rendering past it")
    }
}
