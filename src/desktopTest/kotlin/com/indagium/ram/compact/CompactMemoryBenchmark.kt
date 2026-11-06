// Diagnostic-only retained-heap and allocation harness. Each run is launched in a fresh JVM.
@file:Suppress("ExplicitGarbageCollectionCall", "MagicNumber", "LongParameterList", "CyclomaticComplexMethod", "ComplexCondition")

package com.indagium.ram.compact

import com.indagium.model.LogEntry
import com.indagium.model.LogLevel
import com.indagium.model.NO_DATE
import java.lang.management.ManagementFactory

/**
 * Isolated generated-data screening harness for the original row and the two compact candidates.
 * It deliberately keeps one representation in the retained list at a time. This does not parse
 * files or exercise application views, so it is not an acceptance benchmark for production.
 *
 * Arguments: `<mode> <scenario> <rows> <timestamp-repeats>`.
 */
object CompactMemoryBenchmark {
    private enum class Mode { ORIGINAL, METADATA, TIMESTAMP }

    private enum class Scenario { PLAIN, MERGED, DLT, MIXED }

    private data class Population(
        val metadataRows: Int,
        val sourceTagRows: Int,
        val dltRows: Int,
        val canonicalTimestampRows: Int,
        val fallbackTimestampRows: Int,
    )

    private data class BuiltRows(
        val rows: ArrayList<Any>,
        val expectedChecksum: Long,
        val population: Population,
        val buildNanos: Long,
    )

    @Volatile private var identitySink: Long = 0

    @JvmStatic
    fun main(args: Array<String>) {
        require(args.size == 4) {
            "usage: CompactMemoryBenchmark <original|metadata|timestamp> " +
                "<plain|merged|dlt|mixed> <rows> <timestamp-repeats>"
        }
        val mode = Mode.valueOf(args[0].uppercase())
        val scenario = Scenario.valueOf(args[1].uppercase())
        val rowCount = args[2].toInt().also { require(it in 1..(Int.MAX_VALUE - 8)) }
        val timestampRepeats = args[3].toInt().also { require(it in 1..100) }
        val runtime = Runtime.getRuntime()
        val gcBeans = ManagementFactory.getGarbageCollectorMXBeans()
        val threadBean = (ManagementFactory.getThreadMXBean() as? com.sun.management.ThreadMXBean)
            ?.takeIf { it.isThreadAllocatedMemorySupported }
        threadBean?.let { if (!it.isThreadAllocatedMemoryEnabled) it.isThreadAllocatedMemoryEnabled = true }
        val threadId = Thread.currentThread().id

        val built = buildRows(mode, scenario, rowCount)
        val verificationStart = System.nanoTime()
        val actualChecksum = checksumRows(mode, built.rows)
        check(actualChecksum == built.expectedChecksum) {
            "representation checksum mismatch: expected=${built.expectedChecksum} actual=$actualChecksum"
        }
        val verificationNanos = System.nanoTime() - verificationStart

        // The source LogEntry used to construct a candidate is created and discarded one row at a
        // time in buildRows(). That helper has returned, so none of its locals can keep source rows
        // alive while the candidate retained heap is sampled.
        System.gc()
        Thread.sleep(200)
        val usedHeap = runtime.totalMemory() - runtime.freeMemory()
        val committedHeap = runtime.totalMemory()
        val gcCount = gcBeans.sumOf { it.collectionCount.coerceAtLeast(0) }
        val gcMillis = gcBeans.sumOf { it.collectionTime.coerceAtLeast(0) }
        println(
            "COMPACT_MEMORY phase=retained mode=${mode.name.lowercase()} scenario=${scenario.name.lowercase()} " +
                "rows=${built.rows.size} metadata_rows=${built.population.metadataRows} " +
                "source_tag_rows=${built.population.sourceTagRows} dlt_rows=${built.population.dltRows} " +
                "canonical_timestamps=${built.population.canonicalTimestampRows} " +
                "fallback_timestamps=${built.population.fallbackTimestampRows} checksum=$actualChecksum " +
                "build_ms=${built.buildNanos / 1_000_000} verify_ms=${verificationNanos / 1_000_000} " +
                "heap_used_bytes=$usedHeap heap_committed_bytes=$committedHeap gc_count=$gcCount gc_ms=$gcMillis"
        )
        println(
            "COMPACT_MEMORY phase=runtime jdk=${System.getProperty("java.version")} " +
                "vm=${System.getProperty("java.vm.name").orEmpty().replace(' ', '_')} " +
                "input_args=${ManagementFactory.getRuntimeMXBean().inputArguments.joinToString(",").ifEmpty { "none" }}"
        )
        println(
            "COMPACT_MEMORY phase=vm_layout compressed_oops=${vmOption("UseCompressedOops")} " +
                "compressed_class_pointers=${vmOption("UseCompressedClassPointers")} " +
                "object_alignment_bytes=${vmOption("ObjectAlignmentInBytes")} " +
                "compact_strings=${vmOption("CompactStrings")}"
        )

        val timestampResult = measureTimestampAccess(
            mode = mode,
            rows = built.rows,
            repeats = timestampRepeats,
            threadBean = threadBean,
            threadId = threadId,
        )
        check(timestampResult.firstPassChecksum == timestampChecksum(mode, built.rows)) {
            "timestamp checksum changed during repeated access"
        }
        identitySink = timestampResult.identityChecksum
        val accessCount = built.rows.size.toLong() * timestampRepeats
        val bytesPerAccess = timestampResult.allocatedBytes.toDouble() / accessCount
        println(
            "COMPACT_MEMORY phase=timestamp_access mode=${mode.name.lowercase()} " +
                "scenario=${scenario.name.lowercase()} rows=${built.rows.size} repeats=$timestampRepeats " +
                "accesses=$accessCount elapsed_ms=${timestampResult.elapsedNanos / 1_000_000} " +
                "allocated_bytes=${timestampResult.allocatedBytes} " +
                "allocated_bytes_per_access=$bytesPerAccess " +
                "checksum=${timestampResult.firstPassChecksum} identity_checksum=$identitySink"
        )
    }

    private fun buildRows(mode: Mode, scenario: Scenario, count: Int): BuiltRows {
        val rows = ArrayList<Any>(count)
        var expectedChecksum = 0L
        var metadataRows = 0
        var sourceTagRows = 0
        var dltRows = 0
        var canonicalRows = 0
        var fallbackRows = 0
        val start = System.nanoTime()
        for (index in 0 until count) {
            val entry = generatedEntry(index, scenario)
            expectedChecksum = expectedChecksum * 31 + checksum(entry)
            if (entry.sourceTag != null || entry.dltEcuId != null || entry.dltAppId != null ||
                entry.dltContextId != null || entry.dltMessageType != null || entry.dltTimestamp != null ||
                entry.dltTimestampSource != null
            ) metadataRows++
            if (entry.sourceTag != null) sourceTagRows++
            if (entry.dltEcuId != null || entry.dltAppId != null || entry.dltContextId != null ||
                entry.dltMessageType != null || entry.dltTimestamp != null || entry.dltTimestampSource != null
            ) dltRows++
            if (encodeCanonicalTimestamp(entry.ts) >= 0) canonicalRows++ else fallbackRows++

            when (mode) {
                Mode.ORIGINAL -> rows.add(entry)
                Mode.METADATA -> rows.add(MetadataCandidate(entry))
                Mode.TIMESTAMP -> rows.add(TimestampCandidate(entry))
            }
        }
        return BuiltRows(
            rows = rows,
            expectedChecksum = expectedChecksum,
            population = Population(metadataRows, sourceTagRows, dltRows, canonicalRows, fallbackRows),
            buildNanos = System.nanoTime() - start,
        )
    }

    private fun generatedEntry(index: Int, scenario: Scenario): LogEntry {
        val hasSourceTag: Boolean
        val hasDlt: Boolean
        when (scenario) {
            Scenario.PLAIN -> {
                hasSourceTag = false
                hasDlt = false
            }
            Scenario.MERGED -> {
                hasSourceTag = true
                hasDlt = false
            }
            Scenario.DLT -> {
                hasSourceTag = false
                hasDlt = true
            }
            Scenario.MIXED -> {
                // Ten-row cycle: six plain, two merged, one DLT and one merged-DLT row.
                val slot = index % 10
                hasSourceTag = slot in 6..7 || slot == 9
                hasDlt = slot == 8 || slot == 9
            }
        }
        val sourceTag = if (hasSourceTag) SOURCE_TAGS[(index / 7) % SOURCE_TAGS.size] else null
        val dltEcu = if (hasDlt) DLT_ECUS[index % DLT_ECUS.size] else null
        val dltApp = if (hasDlt) DLT_APPS[(index / 3) % DLT_APPS.size] else null
        val dltContext = if (hasDlt) DLT_CONTEXTS[(index / 11) % DLT_CONTEXTS.size] else null
        val dltType = if (hasDlt) DLT_TYPES[index % DLT_TYPES.size] else null
        val dltTimestamp = if (hasDlt) 1_730_000_000_000L + index * 1_000L else null
        val dltTimestampSource = if (hasDlt) "ecu" else null
        val message = when (index % 4) {
            0 -> "request completed id=$index duration=${index % 997}ms"
            1 -> "permission denied for /data/user/0/app/cache/segment-$index.bin"
            2 -> "frame=${index % 240} rendered; μs=${index % 1000}; 完成"
            else -> "connection retry=${index % 9} peer=node-${index % 32} payload=🙂"
        }
        return LogEntry(
            id = index,
            ts = timestampFor(index),
            level = LEVELS[index % LEVELS.size],
            tag = TAGS[(index / 13) % TAGS.size],
            msg = message,
            pid = 1000 + index % 79,
            tid = 2000 + index % 31,
            sourceTag = sourceTag,
            dltEcuId = dltEcu,
            dltAppId = dltApp,
            dltContextId = dltContext,
            dltMessageType = dltType,
            dltTimestamp = dltTimestamp,
            dltTimestampSource = dltTimestampSource,
            dayOfYear = if (index % 30 == 0) (index / 30 % 366).toShort() else NO_DATE,
        )
    }

    private fun timestampFor(index: Int): String = when (index % 997) {
        0 -> "12:34:56"
        1 -> "2026-11-04 12:34:56.123456"
        else -> decodeCanonicalTimestamp(((index.toLong() * 7_919L) % 86_400_000L).toInt())
    }

    private fun checksumRows(mode: Mode, rows: List<Any>): Long {
        var value = 0L
        for (row in rows) {
            value = value * 31 + when (mode) {
                Mode.ORIGINAL -> checksum(row as LogEntry)
                Mode.METADATA -> checksum(row as MetadataCandidate)
                Mode.TIMESTAMP -> checksum(row as TimestampCandidate)
            }
        }
        return value
    }

    private fun checksum(row: LogEntry): Long = fingerprint(
        row.id, row.ts, row.level, row.tag, row.msg, row.pid, row.tid, row.sourceTag,
        row.dltEcuId, row.dltAppId, row.dltContextId, row.dltMessageType,
        row.dltTimestamp, row.dltTimestampSource, row.dayOfYear,
    )

    private fun checksum(row: MetadataCandidate): Long = fingerprint(
        row.id, row.ts, row.level, row.tag, row.msg, row.pid, row.tid, row.metadata?.sourceTag,
        row.metadata?.ecu, row.metadata?.app, row.metadata?.context, row.metadata?.type,
        row.metadata?.timestamp, row.metadata?.timestampSource, row.dayOfYear,
    )

    private fun checksum(row: TimestampCandidate): Long = fingerprint(
        row.id, row.text(), row.level, row.tag, row.msg, row.pid, row.tid, row.sourceTag,
        row.ecu, row.app, row.context, row.type, row.dltTimestamp, row.timestampSource, row.dayOfYear,
    )

    private fun fingerprint(
        id: Int,
        ts: String,
        level: LogLevel,
        tag: String,
        msg: String,
        pid: Int,
        tid: Int,
        sourceTag: String?,
        ecu: String?,
        app: String?,
        context: String?,
        type: String?,
        dltTimestamp: Long?,
        timestampSource: String?,
        dayOfYear: Short,
    ): Long {
        var value = id.toLong()
        value = value * 31 + ts.hashCode()
        value = value * 31 + level.ordinal
        value = value * 31 + tag.hashCode()
        value = value * 31 + msg.hashCode()
        value = value * 31 + pid
        value = value * 31 + tid
        value = value * 31 + (sourceTag?.hashCode() ?: 0)
        value = value * 31 + (ecu?.hashCode() ?: 0)
        value = value * 31 + (app?.hashCode() ?: 0)
        value = value * 31 + (context?.hashCode() ?: 0)
        value = value * 31 + (type?.hashCode() ?: 0)
        value = value * 31 + (dltTimestamp ?: 0L)
        value = value * 31 + (timestampSource?.hashCode() ?: 0)
        value = value * 31 + dayOfYear
        return value
    }

    private data class TimestampResult(
        val elapsedNanos: Long,
        val allocatedBytes: Long,
        val firstPassChecksum: Long,
        val identityChecksum: Long,
    )

    private fun measureTimestampAccess(
        mode: Mode,
        rows: List<Any>,
        repeats: Int,
        threadBean: com.sun.management.ThreadMXBean?,
        threadId: Long,
    ): TimestampResult {
        // Warm the exact access path and discard its temporary candidate strings before measuring.
        val warmCount = minOf(rows.size, 10_000)
        var warm = 0
        repeat(2) {
            for (index in 0 until warmCount) warm = warm * 31 + timestampText(mode, rows[index]).hashCode()
        }
        if (warm == Int.MIN_VALUE) identitySink = warm.toLong()
        System.gc()
        Thread.sleep(100)

        val beforeAllocated = allocatedBytes(threadBean, threadId)
        val start = System.nanoTime()
        var firstPassChecksum = 0L
        var identityChecksum = 0L
        repeat(repeats) { pass ->
            var passChecksum = 0L
            for (row in rows) {
                val text = timestampText(mode, row)
                passChecksum = passChecksum * 31 + text.hashCode()
                // Reading identity prevents the newly formatted candidate string from being
                // treated as an unobservable temporary by an optimizing JVM.
                identityChecksum = identityChecksum * 31 + System.identityHashCode(text)
            }
            if (pass == 0) firstPassChecksum = passChecksum
            else check(passChecksum == firstPassChecksum) { "timestamp text changed between passes" }
        }
        val elapsed = System.nanoTime() - start
        val afterAllocated = allocatedBytes(threadBean, threadId)
        val allocated = if (beforeAllocated < 0 || afterAllocated < 0) {
            -1
        } else {
            afterAllocated - beforeAllocated
        }
        return TimestampResult(elapsed, allocated, firstPassChecksum, identityChecksum)
    }

    private fun timestampChecksum(mode: Mode, rows: List<Any>): Long {
        var value = 0L
        for (row in rows) value = value * 31 + timestampText(mode, row).hashCode()
        return value
    }

    private fun timestampText(mode: Mode, row: Any): String = when (mode) {
        Mode.ORIGINAL -> (row as LogEntry).ts
        Mode.METADATA -> (row as MetadataCandidate).ts
        Mode.TIMESTAMP -> (row as TimestampCandidate).text()
    }

    private fun allocatedBytes(bean: com.sun.management.ThreadMXBean?, threadId: Long): Long =
        bean?.getThreadAllocatedBytes(threadId) ?: -1L

    private fun vmOption(name: String): String = try {
        ManagementFactory.getPlatformMXBean(com.sun.management.HotSpotDiagnosticMXBean::class.java)
            ?.getVMOption(name)?.value ?: "unavailable"
    } catch (_: Exception) {
        "unavailable"
    }

    private val LEVELS = LogLevel.entries.toTypedArray()
    private val TAGS = arrayOf("ActivityTaskManager", "InputDispatcher", "SystemServer", "AudioFlinger", "生成器")
    private val SOURCE_TAGS = arrayOf("merged-source-a.log", "merged-source-b.log")
    private val DLT_ECUS = arrayOf("HU1", "BCM", "ECU-2")
    private val DLT_APPS = arrayOf("APP", "NAV", "CLIMATE")
    private val DLT_CONTEXTS = arrayOf("MAIN", "DIAG", "NET")
    private val DLT_TYPES = arrayOf("log", "warn", "error")
}
