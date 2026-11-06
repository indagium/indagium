@file:Suppress("ExplicitGarbageCollectionCall", "MagicNumber")

package com.indagium.ram

import com.indagium.model.Filter
import com.indagium.model.FilterMode
import com.indagium.storage.IndexedFileSource
import com.indagium.storage.SourceId
import com.indagium.storage.StorageQuery
import com.indagium.storage.scanFilter
import com.indagium.utils.RegexEvaluationContext
import com.indagium.utils.parseLogcat
import com.indagium.utils.passesFilter
import kotlinx.coroutines.runBlocking
import java.lang.management.ManagementFactory
import java.nio.file.Files
import java.nio.file.Path

/** Screening comparison of the experimental file index/query and the legacy eager model path. */
object IndexedStoreBenchmark {
    private const val FILTER_TEXT = "denied"
    private const val SCAN_PAGE_ROWS = 2_048
    private const val RESULT_PAGE_ROWS = 512
    private val fixtureRows = mapOf(
        "logcat-100k.log" to 100_000,
        "logcat-1m.log" to 1_000_000,
        "logcat-10m.log" to 10_000_000,
    )

    @JvmStatic
    fun main(args: Array<String>) {
        require(args.size == 2) { "usage: IndexedStoreBenchmark <eager|indexed> <generated-fixture-name>" }
        val mode = args[0]
        require(mode == "eager" || mode == "indexed") { "unsupported mode" }
        val expectedRows = fixtureRows[args[1]] ?: error("unsupported generated fixture")
        val fixture = generatedFixture(args[1])
        val fixtureHash = System.getProperty("indagium.ram.fixtureSha256", "unspecified")
        val sourceHash = System.getProperty("indagium.ram.sourceSha256", "unspecified")
        val forceGc = System.getProperty("indagium.ram.forceGc") == "true"
        val filter = Filter(mode = FilterMode.KEYWORD, kwText = FILTER_TEXT)

        println("RAM_INDEXED_BENCH scope=model-only viewer_connected=false promotion=none")
        println("RAM_INDEXED_BENCH mode=$mode fixture=${fixture.fileName} fixture_sha256=$fixtureHash " +
            "source_sha256=$sourceHash filter=KEYWORD:$FILTER_TEXT force_gc_diagnostic=$forceGc")
        sample("start", mode, fixtureHash, sourceHash, forceGc)

        val matchCount = runBlocking {
            when (mode) {
                "eager" -> eagerPath(fixture, expectedRows, filter, fixtureHash, sourceHash, forceGc)
                else -> indexedPath(fixture, expectedRows, filter, fixtureHash, sourceHash, forceGc)
            }
        }
        check(matchCount in 1 until expectedRows.toLong()) { "unexpected match count: $matchCount" }
        sample("after_scope", mode, fixtureHash, sourceHash, forceGc)
        println("RAM_INDEXED_BENCH phase=complete mode=$mode rows=$expectedRows match_count=$matchCount " +
            "filter=KEYWORD:$FILTER_TEXT fixture_sha256=$fixtureHash source_sha256=$sourceHash " +
            "force_gc_diagnostic=$forceGc")
    }

    private suspend fun eagerPath(
        fixture: Path,
        expectedRows: Int,
        filter: Filter,
        fixtureHash: String,
        sourceHash: String,
        forceGc: Boolean,
    ): Long {
        suspend fun workload(): Long {
            val entries = measure("parse_complete", "eager", fixtureHash, sourceHash, forceGc) {
                parseLogcat(fixture.toFile())
            }
            check(entries.size == expectedRows) { "expected $expectedRows parsed rows, got ${entries.size}" }
            return measure("filter_complete", "eager", fixtureHash, sourceHash, forceGc) {
                val regexContext = RegexEvaluationContext()
                entries.count { entry -> passesFilter(entry, filter, regexContext) }.toLong()
            }
        }

        val matches = workload()
        sample("eager_scope_closed", "eager", fixtureHash, sourceHash, forceGc)
        return matches
    }

    private suspend fun indexedPath(
        fixture: Path,
        expectedRows: Int,
        filter: Filter,
        fixtureHash: String,
        sourceHash: String,
        forceGc: Boolean,
    ): Long {
        val source = measure("index_complete", "indexed", fixtureHash, sourceHash, forceGc) {
            IndexedFileSource.open(SourceId("ram-poc-generated"), fixture, maxPageRows = SCAN_PAGE_ROWS)
        }
        val matches = try {
            val snapshot = source.snapshot()
            check(snapshot.rowCount == expectedRows.toLong()) {
                "expected $expectedRows indexed rows, got ${snapshot.rowCount}"
            }
            val query = StorageQuery(snapshot, SCAN_PAGE_ROWS, RESULT_PAGE_ROWS)
            try {
                measure("filter_complete", "indexed", fixtureHash, sourceHash, forceGc) {
                    query.scanFilter(filter)
                }
            } finally {
                query.close()
            }
        } finally {
            source.close()
        }
        sample("indexed_scope_closed", "indexed", fixtureHash, sourceHash, forceGc)
        return matches
    }

    private suspend fun <T> measure(
        phase: String,
        mode: String,
        fixtureHash: String,
        sourceHash: String,
        forceGc: Boolean,
        operation: suspend () -> T,
    ): T {
        val started = System.nanoTime()
        val result = operation()
        sample(phase, mode, fixtureHash, sourceHash, forceGc, System.nanoTime() - started)
        return result
    }

    private fun sample(
        phase: String,
        mode: String,
        fixtureHash: String,
        sourceHash: String,
        forceGc: Boolean,
        elapsedNs: Long = 0,
    ) {
        if (forceGc) {
            System.gc()
            Thread.sleep(200)
        }
        val runtime = Runtime.getRuntime()
        val collectors = ManagementFactory.getGarbageCollectorMXBeans()
        val threadBean = ManagementFactory.getThreadMXBean() as? com.sun.management.ThreadMXBean
        val allocated = threadBean?.takeIf { it.isThreadAllocatedMemorySupported }?.let {
            if (!it.isThreadAllocatedMemoryEnabled) it.isThreadAllocatedMemoryEnabled = true
            it.getThreadAllocatedBytes(Thread.currentThread().id)
        } ?: -1L
        println("RAM_INDEXED_BENCH phase=$phase mode=$mode elapsed_ms=${elapsedNs / 1_000_000} " +
            "heap_used_bytes=${runtime.totalMemory() - runtime.freeMemory()} " +
            "heap_committed_bytes=${runtime.totalMemory()} thread_allocated_bytes=$allocated " +
            "gc_count=${collectors.sumOf { it.collectionCount.coerceAtLeast(0) }} " +
            "gc_ms=${collectors.sumOf { it.collectionTime.coerceAtLeast(0) }} " +
            "fixture_sha256=$fixtureHash source_sha256=$sourceHash force_gc_diagnostic=$forceGc")
    }

    private fun generatedFixture(name: String): Path {
        val root = Path.of("build", "ram-poc", "fixtures").toAbsolutePath().normalize()
        var component = root.root
        for (part in root) {
            component = component.resolve(part)
            require(!Files.isSymbolicLink(component)) { "symlink in fixture path: $component" }
        }
        val fixture = root.resolve(name)
        require(!Files.isSymbolicLink(fixture) && Files.isRegularFile(fixture)) { "missing or unsafe fixture" }
        require(fixture.toRealPath().parent == root.toRealPath()) { "fixture escaped generated-data directory" }
        return fixture
    }
}
