package com.indagium

import com.indagium.model.Filter
import com.indagium.model.FilterMode
import com.indagium.model.SequenceDef
import com.indagium.model.TemplateGranularity
import com.indagium.ui.mkTab
import com.indagium.utils.appendLogEntries
import com.indagium.utils.computeCrashSites
import com.indagium.utils.computeItems
import com.indagium.utils.computeMessageTemplates
import com.indagium.utils.computeStackTraceGroups
import com.indagium.utils.extractCandidate
import com.indagium.utils.foldTemplates
import com.indagium.utils.invalidateComputeCache
import com.indagium.utils.listArchiveLogCandidates
import com.indagium.utils.parseLogcat
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import java.io.File
import kotlin.test.Test

private const val BYTES_PER_MB = 1024L * 1024L
private const val GC_PASSES = 3
private const val NANOS_PER_MILLI = 1_000_000L
private const val NANOS_PER_MICRO = 1_000L

// How long the cancellation-response scenario lets computeItems run before cancelling it — long
// enough that it's genuinely mid-computation on a multi-GB fixture, short relative to the full
// uncancelled run measured just above it.
private const val CANCEL_AFTER_MS = 5L

private const val DEFAULT_APPEND_ROWS = 1_000_000
private const val DEFAULT_APPEND_BATCHES = 100
private const val DEFAULT_APPEND_BATCH_SIZE = 200
private const val FULL_SAMPLE_EVERY = 10

// Manual performance harness — skipped unless -Dindagium.perf.file=<path> (or the legacy
// -Dopenlog.perf.file spelling) points at a fixture
// of roughly 1.5 GB. Deliberately not part of the normal suite: it needs a multi-GB heap and
// minutes of wall time.
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
        val path = (System.getProperty("indagium.perf.file") ?: System.getProperty("openlog.perf.file")).orEmpty()
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

        // The log-composition scan. This measurement is what gated the rest of that feature
        // (Stage 2a): the ~4s cost measured here is too much to pay on every load for a panel most
        // sessions never open, which is why AppState.requestMessageComposition now runs this only
        // on demand and stores the result on LogTab.messageComposition, not on every tab's
        // LogAnalysis. Heap is reported too, unlike the transient timings above.
        val heapBeforeTemplates = heapUsedMb()
        val templates = timed("analysis.messageTemplates") { computeMessageTemplates(data, stackGroupsOnly) }
        println("PERF messageTemplates heap: ${heapUsedMb() - heapBeforeTemplates}MB")
        println(
            "PERF messageTemplates: distinct=${templates.templates.size} " +
                "counted=${templates.countedEntries}/${templates.totalEntries} overflowed=${templates.overflowed}",
        )
        // Level switching must be a fold over the distinct templates, never a rescan — the whole
        // reason masking is level-independent. Timed separately so a regression that quietly
        // reintroduced a rescan would show up here as a second full-file cost.
        timed("messageTemplates fold->NORMAL") { foldTemplates(templates, TemplateGranularity.NORMAL) }
        timed("messageTemplates fold->LOOSE") { foldTemplates(templates, TemplateGranularity.LOOSE) }

        val tab = timed("mkTab(rmap+analysis)") { mkTab("t1", "big.log", data) }
        println("PERF heapAfterTab: ${heapUsedMb() - baselineHeap}MB")
        println("PERF stackGroups: ${tab.analysis.stackTraceGroups.size} crashSites: ${tab.analysis.crashSites.size}")

        // Warm-up + the three interaction-critical passes: unfiltered render, keyword filter
        // change (what every debounced keystroke pays), and expanded-group recompute.
        // Ordering matters: the per-tab compute memo holds one slot per (tab, applyFilter), so an
        // "expand after X" measurement must run immediately after its filter-establishing call —
        // exactly the succession a user's expand click produces.
        timed("computeItems warmup") { computeItems(tab, applyFilter = true) }
        val baseItems = timed("computeItems noFilter") { computeItems(tab, applyFilter = true) }
        val baseSummary = timed("summarizeItems full") { com.indagium.ui.summarizeItems(baseItems) }

        // P-01 evidence: a cancelled computeItems call must stop promptly instead of running the
        // full computation to completion on its Dispatchers.Default thread. invalidateComputeCache
        // forces a genuine fresh full computation — reusing the warmed-up tab/filter combo above
        // would just hit the per-tab memoization cache and prove nothing about the hot loop.
        // Compare this printed value against "computeItems noFilter" above: it must be small and
        // bounded, not comparable to the full uncancelled run.
        invalidateComputeCache(tab.id)
        runBlocking {
            val job = launch(Dispatchers.Default) {
                computeItems(tab, applyFilter = true, cancellationCheck = { ensureActive() })
            }
            delay(CANCEL_AFTER_MS)
            val cancelStart = System.nanoTime()
            job.cancelAndJoin()
            val stopMs = (System.nanoTime() - cancelStart) / NANOS_PER_MILLI
            println("PERF computeItems cancelResponseTime: ${stopMs}ms")
        }
        invalidateComputeCache(tab.id) // leave the cache clean for the measurements that follow
        val firstGid = tab.analysis.stackTraceGroups.first().gid
        val expandedItems =
            timed("computeItems expandOneGroup") { computeItems(tab.copy(expanded = setOf(firstGid)), applyFilter = true) }
        timed("spliceSummarize") { com.indagium.ui.spliceSummarize(baseItems, baseSummary, expandedItems) }
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
        // SeqComputer fix, so only run when explicitly asked for (-Dindagium.perf.dense=1).
        if ((System.getProperty("indagium.perf.dense") ?: System.getProperty("openlog.perf.dense")).orEmpty() == "1") {
            val denseSeqTab = tab.copy(filter = tab.filter.copy(sequences = listOf(seqDef("Skipped frames"))))
            timed("computeItems denseSequenceDef") { computeItems(denseSeqTab, applyFilter = true) }
        }
        println("PERF heapEnd: ${heapUsedMb() - baselineHeap}MB")

        // File splitting throughput (pure streaming copy — should be I/O-bound, a couple of
        // seconds for 1.5GB, not minutes).
        val splitDir = kotlin.io.path.createTempDirectory("openlog-split-bench").toFile()
        val splitOutputs = com.indagium.utils.planSplitOutputs(file.name, splitDir, "part", 3)
        timed("split into 3 parts") { com.indagium.utils.splitFileToFiles(file, splitOutputs) }
        check(splitOutputs.sumOf { it.length() } == file.length()) { "split parts must sum to source size" }
        println("PERF splitPartSizes: ${splitOutputs.map { it.length() / BYTES_PER_MB }}MB")
        splitOutputs.forEach { it.delete() }

        // Archive path: -Dindagium.perf.archive=<path to zip containing a large log>.
        val archivePath = (System.getProperty("indagium.perf.archive") ?: System.getProperty("openlog.perf.archive")).orEmpty()
        if (archivePath.isNotBlank()) {
            val archive = File(archivePath)
            check(archive.isFile) { "archive fixture not found: $archivePath" }
            val candidate = listArchiveLogCandidates(archive).maxByOrNull { it.sizeBytes }
            checkNotNull(candidate) { "no log candidates in $archivePath" }
            val entries = timed("archive extract+parse") { extractCandidate(archive, candidate) }
            println("PERF archiveEntries: ${entries.size}")
        }
    }

    // Opt-in (-Dindagium.perf.append=1), synthetic, no fixture: how much a live-capture batch costs
    // when computeItems extends the previous result (append fast path) versus rebuilding it from
    // scratch. "full" is a cold-cache compute of the identical tab under another id; "fast" chains
    // on the main tab's own cache exactly like successive tail batches do.
    @Test
    fun appendFastPathBenchmark() {
        if (System.getProperty("indagium.perf.append").isNullOrBlank()) return
        val rows = System.getProperty("indagium.perf.append.rows")?.toIntOrNull() ?: DEFAULT_APPEND_ROWS
        val batches = System.getProperty("indagium.perf.append.batches")?.toIntOrNull() ?: DEFAULT_APPEND_BATCHES
        val batchSize = System.getProperty("indagium.perf.append.batchSize")?.toIntOrNull() ?: DEFAULT_APPEND_BATCH_SIZE
        val tags = listOf("ActivityManager", "WifiService", "NetworkMonitor", "Vold", "SurfaceFlinger", "denied.Perm")

        fun row(id: Int) = com.indagium.model.LogEntry(
            id,
            "10:00:%02d.%03d".format((id / 1000) % 60, id % 1000),
            com.indagium.model.LogLevel.I,
            tags[id % tags.size],
            if (id % 10 == 0) "request denied id=$id" else "frame $id rendered ok",
            pid = 1000 + id % 7,
        )
        // A realistic highlighter set so each resolved minimap bar pays line-text + regex cost.
        val minimapHighlighters = listOf(
            com.indagium.model.Highlighter("h1", "denied|error", true, androidx.compose.ui.graphics.Color.Red, true),
            com.indagium.model.Highlighter("h2", "frame \\d+5 ", true, androidx.compose.ui.graphics.Color.Blue, true),
        )
        println("PERF append: rows=$rows batches=$batches batchSize=$batchSize")
        val scenarios = listOf(
            "noFilter" to Filter(),
            "keyword10pct" to Filter(mode = FilterMode.KEYWORD, kwText = "denied"),
        )
        for ((name, filter) in scenarios) {
            var data: List<com.indagium.model.LogEntry> = appendLogEntries(emptyList(), (1..rows).map(::row))
            var tab = mkTab("perf-append-$name", "synthetic.log", data).copy(filter = filter)
            invalidateComputeCache(tab.id)
            timed("append[$name] initial full compute (rows=$rows)") { computeItems(tab, applyFilter = true) }
            var summary = com.indagium.ui.summarizeItems(computeItems(tab, applyFilter = true))
            var prevItems = computeItems(tab, applyFilter = true)
            val fastMs = ArrayList<Long>()
            val summaryMs = ArrayList<Long>()
            val minimapMs = ArrayList<Long>()
            val fullMs = ArrayList<Long>()
            val fullSummaryMs = ArrayList<Long>()
            var nextId = rows + 1
            repeat(batches) { b ->
                val batch = (nextId until nextId + batchSize).map(::row)
                nextId += batchSize
                data = appendLogEntries(data, batch)
                tab = tab.copy(logData = data, rmap = com.indagium.ui.mkRmap(data))
                val t0 = System.nanoTime()
                val fast = computeItems(tab, applyFilter = true)
                fastMs += (System.nanoTime() - t0) / NANOS_PER_MICRO
                val t1 = System.nanoTime()
                summary = com.indagium.ui.spliceSummarize(prevItems, summary, fast) ?: com.indagium.ui.summarizeItems(fast)
                summaryMs += (System.nanoTime() - t1) / NANOS_PER_MICRO
                val t4 = System.nanoTime()
                com.indagium.ui.computeMinimapBars(
                    fast,
                    java.util.BitSet(),
                    fast.size.coerceAtMost(com.indagium.ui.MINIMAP_MAX_BUCKETS),
                    minimapHighlighters,
                    androidx.compose.ui.graphics.Color.Gray,
                )
                minimapMs += (System.nanoTime() - t4) / NANOS_PER_MICRO
                prevItems = fast
                if (b % FULL_SAMPLE_EVERY == 0) {
                    val coldTab = tab.copy(id = tab.id + "~full")
                    invalidateComputeCache(coldTab.id)
                    val t2 = System.nanoTime()
                    val full = computeItems(coldTab, applyFilter = true)
                    fullMs += (System.nanoTime() - t2) / NANOS_PER_MILLI
                    val t3 = System.nanoTime()
                    com.indagium.ui.summarizeItems(full)
                    fullSummaryMs += (System.nanoTime() - t3) / NANOS_PER_MILLI
                    check(full == fast) { "fast path result differs from a full recompute at batch $b" }
                    invalidateComputeCache(coldTab.id)
                }
            }

            fun stats(label: String, xs: List<Long>, unit: String = "ms") {
                val sorted = xs.sorted()
                println(
                    "PERF append[$name] $label: n=${xs.size} median=${sorted[sorted.size / 2]}$unit " +
                        "mean=${xs.average().toLong()}$unit max=${sorted.last()}$unit",
                )
            }
            stats("fast computeItems per batch", fastMs, "us")
            stats("spliceSummarize per batch", summaryMs, "us")
            stats("minimap bars per batch", minimapMs, "us")
            stats("full computeItems per batch", fullMs)
            stats("full summarizeItems per batch (what a full rebuild forces)", fullSummaryMs)
            invalidateComputeCache(tab.id)
        }
    }
}
