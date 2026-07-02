package com.openlog

import com.openlog.model.Filter
import com.openlog.model.FilterMode
import com.openlog.model.SequenceDef
import com.openlog.ui.mkTab
import com.openlog.utils.computeItems
import com.openlog.utils.parseLogcat
import java.io.File
import kotlin.test.Test

private const val BYTES_PER_MB = 1024L * 1024L
private const val GC_PASSES = 3
private const val NANOS_PER_MILLI = 1_000_000L

// Manual performance harness — skipped unless -Dopenlog.perf.file=<path> points at a fixture
// (see docs/perf-large-files.md for how the ~1.5GB fixture is generated). Deliberately not part
// of the normal suite: it needs a multi-GB heap and minutes of wall time.
class LargeFilePerfHarness {
    private fun seqDef(matchText: String) = SequenceDef(
        id = "s1",
        matchText = matchText,
        priority = 1,
        color = androidx.compose.ui.graphics.Color.Red,
    )

    private fun heapUsedMb(): Long {
        repeat(GC_PASSES) {
            System.gc()
            Thread.sleep(100)
        }
        val rt = Runtime.getRuntime()
        return (rt.totalMemory() - rt.freeMemory()) / BYTES_PER_MB
    }

    private fun <T> timed(label: String, block: () -> T): T {
        val start = System.nanoTime()
        val result = block()
        val elapsedMs = (System.nanoTime() - start) / NANOS_PER_MILLI
        println("PERF $label: ${elapsedMs}ms")
        return result
    }

    @Test
    fun largeFileBenchmark() {
        val path = System.getProperty("openlog.perf.file").orEmpty()
        if (path.isBlank()) return
        val file = File(path)
        check(file.isFile) { "fixture not found: $path" }
        println("PERF fixture: ${file.length() / BYTES_PER_MB}MB")
        val baselineHeap = heapUsedMb()

        val data = timed("parseLogcat") { parseLogcat(file) }
        println("PERF entries: ${data.size}")
        println("PERF heapAfterParse: ${heapUsedMb() - baselineHeap}MB")

        val tab = timed("mkTab(rmap+analysis)") { mkTab("t1", "big.log", data) }
        println("PERF heapAfterTab: ${heapUsedMb() - baselineHeap}MB")
        println("PERF stackGroups: ${tab.analysis.stackTraceGroups.size} crashSites: ${tab.analysis.crashSites.size}")

        // Warm-up + the three interaction-critical passes: unfiltered render, keyword filter
        // change (what every debounced keystroke pays), and expanded-group recompute.
        timed("computeItems warmup") { computeItems(tab, applyFilter = true) }
        timed("computeItems noFilter") { computeItems(tab, applyFilter = true) }
        val kwTab = tab.copy(filter = Filter(mode = FilterMode.KEYWORD, kwText = "denied"))
        timed("computeItems keyword'denied'") { computeItems(kwTab, applyFilter = true) }
        val firstGid = tab.analysis.stackTraceGroups.first().gid
        timed("computeItems expandOneGroup") { computeItems(tab.copy(expanded = setOf(firstGid)), applyFilter = true) }
        val rareSeqTab = tab.copy(filter = tab.filter.copy(sequences = listOf(seqDef("ANR in"))))
        timed("computeItems rareSequenceDef") { computeItems(rareSeqTab, applyFilter = true) }
        // A sequence pattern matching ~5% of lines: quadratic in candidate count before the
        // SeqComputer fix, so only run when explicitly asked for (-Dopenlog.perf.dense=1).
        if (System.getProperty("openlog.perf.dense").orEmpty() == "1") {
            val denseSeqTab = tab.copy(filter = tab.filter.copy(sequences = listOf(seqDef("Skipped frames"))))
            timed("computeItems denseSequenceDef") { computeItems(denseSeqTab, applyFilter = true) }
        }
        println("PERF heapEnd: ${heapUsedMb() - baselineHeap}MB")
    }
}
