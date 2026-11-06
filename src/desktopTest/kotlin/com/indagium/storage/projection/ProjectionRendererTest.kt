package com.indagium.storage.projection

import androidx.compose.ui.graphics.Color
import com.indagium.model.Filter
import com.indagium.model.LogAnalysis
import com.indagium.model.LogEntry
import com.indagium.model.LogItem
import com.indagium.model.LogLevel
import com.indagium.model.LogTab
import com.indagium.model.ManualCollapseBlock
import com.indagium.model.ManualCollapseDirection
import com.indagium.model.SequenceDef
import com.indagium.model.StackTraceGroup
import com.indagium.storage.SourceVersion
import com.indagium.storage.StorageFailure
import com.indagium.ui.DANGER_RED
import com.indagium.utils.computeDisplayProjection
import com.indagium.utils.computeItems
import com.indagium.utils.computeSeqGroups
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ProjectionRendererTest {
    private val generation = ProjectionGeneration(SourceVersion("test", 0), "query", "projection", 1)

    private fun entry(id: Int, message: String, tid: Int = 1) =
        LogEntry(id, "12:00:00.000", LogLevel.I, "tag", message, tid = tid)

    private fun tab(rows: List<LogEntry>) = LogTab("t", "test", rows, rows.associateBy { it.id })

    private suspend fun assertParity(tab: LogTab): List<DisplayEvent> {
        val projection = computeDisplayProjection(tab, false, generation, 2, AutoCloseable {})
        val actual = mutableListOf<DisplayEvent>()
        var start = 0L
        var endReached: Boolean
        do {
            val page = projection.page(start, 2)
            actual.addAll(page.items)
            start += page.items.size
            endReached = page.endReached
        } while (!endReached)
        val legacy = computeItems(tab, false)
        val compact = computeItems(tab.copy(id = "${tab.id}-compact", sourcePath = "/generated/test.log"), false)
        assertEquals(legacy, compact)
        assertEquals(legacy.size, actual.size)
        legacy.zip(actual).forEach { (expected, event) ->
            when (expected) {
                is LogItem.Row -> {
                    event as DisplayEvent.Row
                    assertEquals(expected.entry.id, event.id.value)
                    assertEquals(tab.logData.indexOf(expected.entry).toLong(), event.ordinal)
                    assertEquals(RowStyle(expected.indent, expected.groupColor, expected.scopedSeqColor), event.style)
                }
                is LogItem.SeqHeader -> {
                    val header = (event as DisplayEvent.Header).descriptor
                    assertEquals("sequence", header.kind)
                    assertEquals(expected.gid, header.groupId)
                    assertEquals(expected.entry.id, header.anchorId?.value)
                    assertEquals(expected.indent, header.depth)
                    assertEquals(expected.expanded, header.expanded)
                    assertEquals(expected.count, header.count)
                    assertEquals(expected.color, header.color)
                    assertEquals(expected.scopeTid, header.scopeTid)
                }
                is LogItem.ManualHeader -> {
                    val header = (event as DisplayEvent.Header).descriptor
                    assertEquals("manual", header.kind)
                    assertEquals(expected.gid, header.groupId)
                    assertEquals(expected.entry.id, header.anchorId?.value)
                    assertEquals(expected.direction, header.direction)
                    assertEquals(expected.expanded, header.expanded)
                    assertEquals(expected.count, header.count)
                    assertEquals(expected.color, header.color)
                }
                is LogItem.StackTraceHeader -> {
                    val header = (event as DisplayEvent.Header).descriptor
                    assertEquals("stack", header.kind)
                    assertEquals(expected.gid, header.groupId)
                    assertEquals(expected.entry.id, header.anchorId?.value)
                    assertEquals(expected.indent, header.depth)
                    assertEquals(expected.expanded, header.expanded)
                    assertEquals(expected.count, header.count)
                }
            }
        }
        return actual
    }

    @Test
    fun expandedManualSuppressesAnchorEvenWhenStackEmitsIt(): Unit = runBlocking {
        val rows = listOf(entry(2, "before"), entry(8, "crash"), entry(21, "anchor"), entry(30, "after"))
        val block = ManualCollapseBlock("m", 21, ManualCollapseDirection.RANGE, Color.Blue, endId = 8)
        val stack = StackTraceGroup("s", 8, listOf(21, 30))
        val tab = tab(rows).copy(
            manualBlocks = listOf(block), expanded = setOf("m", "s"),
            analysis = LogAnalysis(pending = false, stackTraceGroups = listOf(stack)),
        )
        val events = assertParity(tab)
        assertEquals(listOf("row:2", "manual:21", "stack:8", "row:30"), events.map {
            when (it) {
                is DisplayEvent.Row -> "row:${it.id.value}"
                is DisplayEvent.Header -> "${it.descriptor.kind}:${it.descriptor.anchorId?.value}"
            }
        })
        assertEquals(RowStyle(2, DANGER_RED), (events.last() as DisplayEvent.Row).style)
    }

    @Test
    fun manualAndStackHeadersCanShareAnAnchor(): Unit = runBlocking {
        val rows = listOf(entry(2, "crash"), entry(8, "member"), entry(21, "tail"))
        val block = ManualCollapseBlock("m", 2, ManualCollapseDirection.TO_END, Color.Blue)
        val tab = tab(rows).copy(manualBlocks = listOf(block), expanded = setOf("m", "s"),
            analysis = LogAnalysis(pending = false, stackTraceGroups = listOf(StackTraceGroup("s", 2, listOf(8)))))
        val events = assertParity(tab)
        assertEquals(listOf("manual:2", "stack:2", "row:8", "row:21"), events.map {
            when (it) {
                is DisplayEvent.Row -> "row:${it.id.value}"
                is DisplayEvent.Header -> "${it.descriptor.kind}:${it.descriptor.anchorId?.value}"
            }
        })
    }

    @Test
    fun stackMemberMayAlsoAppearAsSequenceHeader(): Unit = runBlocking {
        val rows = listOf(entry(2, "crash"), entry(8, "start"), entry(21, "tail"))
        val sequence = SequenceDef("seq", "start", priority = 1, color = Color.Red)
        val gid = computeSeqGroups(rows, listOf(sequence)).single().gid
        val tab = tab(rows).copy(filter = Filter(sequences = listOf(sequence)), expanded = setOf("s", gid),
            analysis = LogAnalysis(pending = false, stackTraceGroups = listOf(StackTraceGroup("s", 2, listOf(8)))))
        val events = assertParity(tab)
        assertEquals(listOf("stack:2", "row:8", "sequence:8", "row:21"), events.map {
            when (it) {
                is DisplayEvent.Row -> "row:${it.id.value}"
                is DisplayEvent.Header -> "${it.descriptor.kind}:${it.descriptor.anchorId?.value}"
            }
        })
        assertEquals(RowStyle(1, DANGER_RED), (events[1] as DisplayEvent.Row).style)
        assertEquals(RowStyle(1, Color.Red), (events[3] as DisplayEvent.Row).style)
    }

    @Test
    fun sourceMissingAStackMemberFailsExplicitly(): Unit = runBlocking {
        val rows = listOf(entry(2, "crash"))
        val missing = entry(8, "member")
        val tab = tab(rows).copy(rmap = mapOf(2 to rows[0], 8 to missing), expanded = setOf("s"),
            analysis = LogAnalysis(pending = false, stackTraceGroups = listOf(StackTraceGroup("s", 2, listOf(8)))))
        assertFailsWith<StorageFailure.InvalidRequest> { computeDisplayProjection(tab, false, generation, 2, AutoCloseable {}) }
    }

    @Test
    fun scopedSequenceAndStackPreserveForeignRowsAndStyles(): Unit = runBlocking {
        val rows = listOf(entry(2, "start"), entry(8, "foreign", 2), entry(21, "crash"), entry(30, "member"), entry(45, "end"))
        val sequence = SequenceDef("seq", "start", priority = 1, color = Color.Red, endMatchText = "end", scopeTid = 1)
        val gid = computeSeqGroups(rows, listOf(sequence)).single().gid
        val tab = tab(rows).copy(filter = Filter(sequences = listOf(sequence)), expanded = setOf(gid, "stack"),
            analysis = LogAnalysis(pending = false, stackTraceGroups = listOf(StackTraceGroup("stack", 21, listOf(30)))))
        val events = assertParity(tab)
        assertEquals(listOf("sequence:2", "row:8", "stack:21", "row:30", "row:45"), events.map {
            when (it) {
                is DisplayEvent.Row -> "row:${it.id.value}"
                is DisplayEvent.Header -> "${it.descriptor.kind}:${it.descriptor.anchorId?.value}"
            }
        })
        assertEquals(RowStyle(), (events[1] as DisplayEvent.Row).style)
        assertEquals(RowStyle(2, DANGER_RED), (events[3] as DisplayEvent.Row).style)
        assertEquals(RowStyle(1, Color.Red, Color.Red), (events[4] as DisplayEvent.Row).style)
    }

    @Test
    fun unsupportedIdOrderIsRejectedBeforeProjection(): Unit = runBlocking {
        for (rows in listOf(
            listOf(entry(9, "first"), entry(3, "second")),
            listOf(entry(3, "first"), entry(3, "duplicate")),
            listOf(entry(-1, "negative")),
        )) {
            assertFailsWith<StorageFailure.InvalidRequest> { computeDisplayProjection(tab(rows), false, generation, 2, AutoCloseable {}) }
        }
    }

    @Test
    fun leaseKeepsRowsAliveUntilProjectionFinishes(): Unit = runBlocking {
        val entered = CountDownLatch(1)
        val resume = CountDownLatch(1)
        val released = AtomicBoolean(false)
        val ownerClosed = AtomicBoolean(false)
        val pinned = AtomicBoolean(true)
        val closeCount = AtomicInteger()
        val original = listOf(entry(2, "row"))
        val rows = object : AbstractList<LogEntry>() {
            override val size: Int get() = original.size

            override fun get(index: Int): LogEntry {
                entered.countDown()
                check(resume.await(5, TimeUnit.SECONDS))
                check(!released.get()) { "Rows released while projection was reading them" }
                return original[index]
            }
        }
        val tab = LogTab("lease", "test", rows, mapOf(2 to original[0]))
        val result = async(start = CoroutineStart.UNDISPATCHED) {
            computeDisplayProjection(tab, false, generation, 2, AutoCloseable {
                closeCount.incrementAndGet()
                pinned.set(false)
                if (ownerClosed.get()) released.set(true)
            })
        }
        try {
            assertTrue(entered.await(5, TimeUnit.SECONDS))
            // Simulate tab close: its storage is released only when the pin is gone.
            ownerClosed.set(true)
            if (!pinned.get()) released.set(true)
            assertFalse(released.get())
        } finally {
            resume.countDown()
        }
        result.await()
        assertTrue(released.get())
        assertEquals(1, closeCount.get())
    }

    @Test
    fun leaseClosesOnFailureAndCancellationBeforeDispatch(): Unit = runBlocking {
        val closeCount = AtomicInteger()
        assertFailsWith<StorageFailure.InvalidRequest> {
            computeDisplayProjection(tab(listOf(entry(-1, "invalid"))), false, generation, 2,
                AutoCloseable { closeCount.incrementAndGet() })
        }
        assertEquals(1, closeCount.get())

        val cancelled = async(start = CoroutineStart.UNDISPATCHED) {
            cancel()
            computeDisplayProjection(tab(emptyList()), false, generation, 2,
                AutoCloseable { closeCount.incrementAndGet() })
        }
        assertFailsWith<CancellationException> { cancelled.await() }
        assertEquals(2, closeCount.get())
    }
}
