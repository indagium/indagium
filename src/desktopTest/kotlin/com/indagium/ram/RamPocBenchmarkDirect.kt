// Diagnostic-only explicit GC and fixed measurement parameters are intentional.
@file:Suppress("ExplicitGarbageCollectionCall", "MagicNumber")

package com.indagium.ram

import java.lang.management.ManagementFactory
import java.nio.file.Files
import java.nio.file.Path

/** Benchmark-only reader experiment; not an alternate application implementation. */
object RamPocBenchmarkDirect {
    @JvmStatic fun main(args: Array<String>) {
        require(args.size == 1 && args[0] in setOf("100k", "1m", "10m"))
        val root = Path.of("build/ram-poc/fixtures").toAbsolutePath().normalize()
        val fixture = root.resolve("logcat-${args[0]}.log")
        var p = root.root
        for (part in fixture) { p = p.resolve(part); require(!Files.isSymbolicLink(p)) }
        require(Files.isRegularFile(fixture) && fixture.toRealPath().parent == root.toRealPath())
        val forceGc = System.getProperty("indagium.ram.forceGc") == "true"

        fun sample(phase: String, elapsed: Long = 0) {
            if (forceGc) { System.gc(); Thread.sleep(200) }
            val rt = Runtime.getRuntime()
            val gc = ManagementFactory.getGarbageCollectorMXBeans()
            val thread = ManagementFactory.getThreadMXBean() as? com.sun.management.ThreadMXBean
            val allocated = thread?.takeIf { it.isThreadAllocatedMemorySupported }?.let {
                if (!it.isThreadAllocatedMemoryEnabled) it.isThreadAllocatedMemoryEnabled = true
                it.getThreadAllocatedBytes(Thread.currentThread().id)
            } ?: -1L
            println("RAM_POC phase=$phase elapsed_ms=${elapsed / 1_000_000} heap_used=${rt.totalMemory() - rt.freeMemory()} " +
                "heap_committed=${rt.totalMemory()} thread_allocated=$allocated " +
                "gc_count=${gc.sumOf { it.collectionCount.coerceAtLeast(0) }} gc_ms=${gc.sumOf { it.collectionTime.coerceAtLeast(0) }} diagnostic=$forceGc")
        }

        fun <T> measure(phase: String, block: () -> T): T {
            val start = System.nanoTime(); val result = block(); sample(phase, System.nanoTime() - start); return result
        }
        sample("start")

        fun workload() {
            measure("index") { DirectFileLogStore.open(root, fixture) }.use { store ->
                check(store.rowCount == mapOf("100k" to 100_000, "1m" to 1_000_000, "10m" to 10_000_000).getValue(args[0]))

                fun viewport(): Long {
                    var checksum = 0L
                    val random = java.util.Random(0L)
                    repeat(100) {
                        val start = random.nextInt(store.rowCount - 50)
                        repeat(50) { checksum += store[start + it].id }
                    }
                    return checksum
                }
                val first = measure("viewport_cold") { viewport() }
                val second = measure("viewport_repeat") { viewport() }
                check(first == second)
                val count = measure("sequential_keyword") { store.entries().count { it.msg.contains("denied") } }
                check(count > 0)
                val batchedCount = measure("sequential_keyword_batched_validation") {
                    store.batchedEntries().count { it.msg.contains("denied") }
                }
                check(batchedCount == count)
                println("RAM_POC_DIRECT cache_bytes=${store.cacheBytes} cache_budget_bytes=${store.cacheBudgetBytes} matches=$count")
            }
        }
        workload()
        sample("after_close")
    }
}
