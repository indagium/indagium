package com.openlog

import com.openlog.model.Filter
import com.openlog.model.FilterMode
import com.openlog.model.SequenceDef
import com.openlog.ui.mkTab
import com.openlog.utils.computeCrashSites
import com.openlog.utils.computeItems
import com.openlog.utils.computeStackTraceGroups
import com.openlog.utils.extractCandidate
import com.openlog.utils.listArchiveLogCandidates
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

        // Per-phase analysis timings (same work mkTab does, itemized).
        val stackGroupsOnly = timed("analysis.stackTraceGroups") { computeStackTraceGroups(data) }
        timed("analysis.crashSites") { computeCrashSites(data, stackGroupsOnly) }
        timed("analysis.tagCounts") { data.groupingBy { it.tag }.eachCount() }

        val tab = timed("mkTab(rmap+analysis)") { mkTab("t1", "big.log", data) }
        println("PERF heapAfterTab: ${heapUsedMb() - baselineHeap}MB")
        println("PERF stackGroups: ${tab.analysis.stackTraceGroups.size} crashSites: ${tab.analysis.crashSites.size}")

        // Warm-up + the three interaction-critical passes: unfiltered render, keyword filter
        // change (what every debounced keystroke pays), and expanded-group recompute.
        // Ordering matters: the per-tab compute memo holds one slot per (tab, applyFilter), so an
        // "expand after X" measurement must run immediately after its filter-establishing call —
        // exactly the succession a user's expand click produces.
        timed("computeItems warmup") { computeItems(tab, applyFilter = true) }
        timed("computeItems noFilter") { computeItems(tab, applyFilter = true) }
        val firstGid = tab.analysis.stackTraceGroups.first().gid
        timed("computeItems expandOneGroup") { computeItems(tab.copy(expanded = setOf(firstGid)), applyFilter = true) }
        val kwTab = tab.copy(filter = Filter(mode = FilterMode.KEYWORD, kwText = "denied"))
        timed("computeItems keyword'denied'") { computeItems(kwTab, applyFilter = true) }
        timed("computeItems keywordThenExpand") {
            computeItems(kwTab.copy(expanded = setOf(firstGid)), applyFilter = true)
        }
        val rareSeqTab = tab.copy(filter = tab.filter.copy(sequences = listOf(seqDef("ANR in"))))
        timed("computeItems rareSequenceDef") { computeItems(rareSeqTab, applyFilter = true) }
        val firstSeqGid = "sg_s1_" // expanding after a sequence pass must reuse the memoized scan
        timed("computeItems seqThenExpand") {
            computeItems(rareSeqTab.copy(expanded = setOf(firstSeqGid)), applyFilter = true)
        }
        // A sequence pattern matching ~5% of lines: quadratic in candidate count before the
        // SeqComputer fix, so only run when explicitly asked for (-Dopenlog.perf.dense=1).
        if (System.getProperty("openlog.perf.dense").orEmpty() == "1") {
            val denseSeqTab = tab.copy(filter = tab.filter.copy(sequences = listOf(seqDef("Skipped frames"))))
            timed("computeItems denseSequenceDef") { computeItems(denseSeqTab, applyFilter = true) }
        }
        println("PERF heapEnd: ${heapUsedMb() - baselineHeap}MB")

        // Archive path: -Dopenlog.perf.archive=<path to zip containing a large log>.
        val archivePath = System.getProperty("openlog.perf.archive").orEmpty()
        if (archivePath.isNotBlank()) {
            val archive = File(archivePath)
            check(archive.isFile) { "archive fixture not found: $archivePath" }
            val candidate = listArchiveLogCandidates(archive).maxByOrNull { it.sizeBytes }
            checkNotNull(candidate) { "no log candidates in $archivePath" }
            val entries = timed("archive extract+parse") { extractCandidate(archive, candidate) }
            println("PERF archiveEntries: ${entries.size}")
        }
    }
}
