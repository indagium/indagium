// Diagnostic-only explicit GC and fixed measurement parameters are intentional.
@file:Suppress("ExplicitGarbageCollectionCall", "MagicNumber")

package com.indagium.ram

import com.indagium.model.Filter
import com.indagium.model.FilterMode
import com.indagium.ui.mkTab
import com.indagium.utils.computeItems
import com.indagium.utils.invalidateComputeCache
import com.indagium.utils.parseLogcat
import java.lang.management.ManagementFactory
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test

/** Opt-in model benchmark. This does not claim to measure UI frames or scroll rendering. */
class RamPocBenchmark {
    @Test
    fun modelWorkload() {
        val name = System.getProperty("indagium.ram.fixture") ?: return
        require(name in setOf("100k", "1m", "10m"))
        val root = Path.of("build", "ram-poc", "fixtures").toAbsolutePath().normalize()
        // Never follow a redirected fixture directory or fixture.
        var component = root.root
        for (part in root) {
            component = component.resolve(part)
            require(!Files.isSymbolicLink(component)) { "symlink in fixture path: $component" }
        }
        val file = root.resolve("logcat-$name.log")
        require(!Files.isSymbolicLink(file) && Files.isRegularFile(file))
        require(file.toRealPath().parent == root.toRealPath())
        val diagnostic = System.getProperty("indagium.ram.forceGc") == "true"
        val runtime = Runtime.getRuntime()
        val gc = ManagementFactory.getGarbageCollectorMXBeans()
        val thread = ManagementFactory.getThreadMXBean() as? com.sun.management.ThreadMXBean

        fun sample(label: String, elapsedNs: Long = 0) {
            if (diagnostic) {
                System.gc()
                Thread.sleep(200)
            }
            val allocated = thread?.takeIf { it.isThreadAllocatedMemorySupported }?.let {
                if (!it.isThreadAllocatedMemoryEnabled) it.isThreadAllocatedMemoryEnabled = true
                it.getThreadAllocatedBytes(Thread.currentThread().id)
            } ?: -1L
            println("RAM_POC phase=$label elapsed_ms=${elapsedNs / 1_000_000} " +
                "heap_used=${runtime.totalMemory() - runtime.freeMemory()} heap_committed=${runtime.totalMemory()} " +
                "thread_allocated=$allocated gc_count=${gc.sumOf { it.collectionCount.coerceAtLeast(0) }} " +
                "gc_ms=${gc.sumOf { it.collectionTime.coerceAtLeast(0) }} diagnostic=$diagnostic")
        }

        fun <T> measure(label: String, block: () -> T): T {
            val start = System.nanoTime()
            val result = block()
            sample(label, System.nanoTime() - start)
            return result
        }
        sample("start")

        fun workload() {
            val entries = measure("parse") { parseLogcat(file.toFile()) }
            val expected = mapOf("100k" to 100_000, "1m" to 1_000_000, "10m" to 10_000_000).getValue(name)
            check(entries.size == expected) { "expected $expected rows, got ${entries.size}" }
            var tab = measure("tab") { mkTab("ram-poc", file.fileName.toString(), entries) }
            // Exclude stack folding to isolate the flat row projection; keep the result alive.
            tab = tab.copy(analysis = tab.analysis.copy(stackTraceGroups = emptyList()))
            sample("before_flat_projection")
            val projection = measure("flat_projection") { computeItems(tab, applyFilter = true) }
            check(projection.isNotEmpty())
            tab = tab.copy(filter = Filter(mode = FilterMode.KEYWORD, kwText = "denied"))
            val filtered = measure("filter") { computeItems(tab, applyFilter = true) }
            check(filtered.isNotEmpty())
            invalidateComputeCache(tab.id)
            check(projection.isNotEmpty())
        }
        workload()
        sample("after_scope_closed")
    }
}

/** Separate-process entry point for the opt-in model workload. */
object RamPocBenchmarkModel {
    @JvmStatic fun main(args: Array<String>) {
        require(args.size == 1 && args[0] in setOf("100k", "1m", "10m"))
        System.setProperty("indagium.ram.fixture", args[0])
        RamPocBenchmark().modelWorkload()
    }
}
