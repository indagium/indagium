@file:Suppress("MagicNumber")

package com.indagium.storage

import androidx.compose.ui.graphics.Color
import com.indagium.model.Filter
import com.indagium.model.FilterMode
import com.indagium.model.LogEntry
import com.indagium.model.LogFormat
import com.indagium.model.LogLevel
import com.indagium.model.NestedSeqGroup
import com.indagium.model.SeqGroup
import com.indagium.model.SequenceDef
import com.indagium.utils.CancellationCheck
import com.indagium.utils.RegexEvaluationContext
import com.indagium.utils.computeSeqGroups
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

class StorageSequenceGroupsTest {
    private fun row(id: Int, msg: String, tid: Int = 1) = LogEntry(id, "", LogLevel.I, "Tag", msg, tid = tid)

    private fun def(id: String, start: String, end: String?, priority: Int, tid: Int? = null) =
        SequenceDef(id, start, priority = priority, color = Color.Red, endMatchText = end, scopeTid = tid)

    private fun StorageSequenceResult.legacy(): List<SeqGroup> = groups.map { group ->
        SeqGroup(
            group.gid, group.rootId.value, group.plain.toIntArray().toList(),
            group.nested.map { nested ->
                NestedSeqGroup(nested.gid, nested.rootId.value, nested.members.toIntArray().toList(), nested.defId, nested.endExclusive)
            }, group.defId, group.endExclusive,
        )
    }

    @Test
    fun scopedSequenceAcrossPagesUsesFilteredOrdinalsAndIndependentExpectedIds() = runBlocking {
        val rows = listOf(
            row(5, "ignored"), row(10, "start", 1), row(35, "foreign", 2),
            row(72, "middle", 1), row(90, "end", 1),
        )
        val source = LegacyInMemorySource(SourceId("sequence"), LogFormat.LOGCAT, rows, maxPageRows = 2)
        val query = StorageQuery(source.snapshot(), 2, 2)
        try {
            query.scan { it.id != 5 }
            val context = RegexEvaluationContext()
            val result = computeStorageSequenceGroups(query, listOf(def("run", "start", "end", 1, 1)), 2, context)
            assertEquals(query.version, result.version)
            assertEquals(query.queryId, result.queryId)
            assertFalse(result.regexTimedOut)
            val group = result.groups.single()
            assertEquals("sg_run_10", group.gid)
            assertEquals(10, group.rootId.value)
            assertEquals(4, group.endExclusive)
            assertContentEquals(intArrayOf(72, 90), group.plain.toIntArray())
            val copy = group.plain.toIntArray()
            copy[0] = -999
            assertEquals(72, group.plain[0])
            val filtered = rows.filter { it.id != 5 }
            assertEquals(computeSeqGroups(filtered, listOf(def("run", "start", "end", 1, 1))), result.legacy())
        } finally {
            query.close()
            source.close()
        }
    }

    @Test
    fun nestedGroupsAndCrossingCandidatesMatchLegacyAndExpectedOutput() = runBlocking {
        val rows = listOf(row(3, "A start"), row(9, "B start"), row(23, "B end"), row(47, "A end"), row(80, "tail"))
        val defs = listOf(def("a", "A start", "A end", 1), def("b", "B start", "B end", 2))
        val source = LegacyInMemorySource(SourceId("nested"), LogFormat.LOGCAT, rows, maxPageRows = 1)
        val query = StorageQuery(source.snapshot(), 1, 1)
        try {
            query.scan { true }
            val result = computeStorageSequenceGroups(query, defs, 1, RegexEvaluationContext())
            val group = result.groups.single()
            assertEquals(3, group.rootId.value)
            assertContentEquals(intArrayOf(47), group.plain.toIntArray())
            assertEquals(9, group.nested.single().rootId.value)
            assertContentEquals(intArrayOf(23), group.nested.single().members.toIntArray())
            assertEquals(computeSeqGroups(rows, defs), result.legacy())

            val crossing = listOf(def("a", "A start", "B end", 1), def("b", "B start", "A end", 2))
            val crossingResult = computeStorageSequenceGroups(query, crossing, 1, RegexEvaluationContext())
            assertEquals(listOf(3, 9), crossingResult.groups.map { it.rootId.value })
            assertEquals(computeSeqGroups(rows, crossing), crossingResult.legacy())
        } finally {
            query.close()
            source.close()
        }
    }

    @Test
    fun duplicateDefinitionIdsPriorityAndMissingEndMatchLegacy() = runBlocking {
        val rows = listOf(row(1, "first"), row(7, "end"), row(19, "second"), row(22, "end"), row(35, "first"))
        val defs = listOf(def("same", "first", "end", 2), def("same", "second", "end", 1))
        val source = LegacyInMemorySource(SourceId("duplicates"), LogFormat.LOGCAT, rows, maxPageRows = 2)
        val query = StorageQuery(source.snapshot(), 2, 2)
        try {
            query.scan { true }
            val result = computeStorageSequenceGroups(query, defs, 2, RegexEvaluationContext())
            assertEquals(listOf(1, 19, 35), result.groups.map { it.rootId.value })
            assertEquals(listOf(2, 4, 5), result.groups.map { it.endExclusive })
            assertEquals(computeSeqGroups(rows, defs), result.legacy())
        } finally {
            query.close()
            source.close()
        }
    }

    @Test
    fun limitsFailExplicitlyWithoutPublishingPartialGroups(): Unit = runBlocking {
        val rows = listOf(row(1, "start"), row(2, "middle"), row(3, "end"))
        val defs = listOf(def("run", "start", "end", 1))
        val source = LegacyInMemorySource(SourceId("limits"), LogFormat.LOGCAT, rows, maxPageRows = 2)
        val query = StorageQuery(source.snapshot(), 2, 2)
        try {
            query.scan { true }
            for ((limits, expected) in listOf(
                StorageSequenceLimits(maxRows = 2) to "rows",
                StorageSequenceLimits(maxDefinitions = 0) to "definitions",
                StorageSequenceLimits(maxCandidates = 0) to "candidates",
                StorageSequenceLimits(maxEndOccurrences = 0) to "end occurrences",
                StorageSequenceLimits(maxMembership = 0) to "memberships",
            )) {
                val failure = assertFailsWith<StorageSequenceLimitExceeded> {
                    computeStorageSequenceGroups(query, defs, 2, RegexEvaluationContext(), limits)
                }
                assertEquals(expected, failure.resource)
            }
            assertEquals(1, computeStorageSequenceGroups(query, defs, 2, RegexEvaluationContext()).groups.size)
        } finally {
            query.close()
            source.close()
        }
    }

    @Test
    fun changedOrClosedSourceFailsFinalFenceAndCancellationPropagates(): Unit = runBlocking {
        val source = LegacyInMemorySource(SourceId("fence"), LogFormat.LOGCAT, listOf(row(1, "start")), maxPageRows = 1)
        val delegate = source.snapshot()
        var validations = 0
        val snapshot = object : ReadSnapshot by delegate {
            override suspend fun resolve(id: EntryId): RowOrdinal? {
                validations++
                if (validations == 2) throw IndexedFileFailure.Changed()
                return delegate.resolve(id)
            }
        }
        val query = StorageQuery(snapshot, 1, 1)
        try {
            query.scan { true }
            assertFailsWith<IndexedFileFailure.Changed> {
                computeStorageSequenceGroups(query, listOf(def("run", "start", null, 1)), 1, RegexEvaluationContext())
            }
            val cancelled = Job().apply { cancel() }
            assertFailsWith<CancellationException> {
                withContext(cancelled) {
                    computeStorageSequenceGroups(query, listOf(def("run", "start", null, 1)), 1, RegexEvaluationContext())
                }
            }
        } finally {
            query.close()
            source.close()
        }
    }

    @Test
    fun queryIdentityAndSharedRegexContextAreExplicit() = runBlocking {
        val rows = listOf(row(1, "start"), row(2, "finish"))
        val source = LegacyInMemorySource(SourceId("identity"), LogFormat.LOGCAT, rows, maxPageRows = 2)
        val first = StorageQuery(source.snapshot(), 2, 2)
        val second = StorageQuery(source.snapshot(), 2, 2)
        try {
            assertEquals(first.version, second.version)
            assertNotEquals(first.queryId, second.queryId)
            val context = RegexEvaluationContext()
            first.scanFilter(Filter(mode = FilterMode.KEYWORD, kwText = ""), regexContext = context)
            val invalid = SequenceDef("bad", "[", isRegex = true, priority = 1, color = Color.Red)
            val result = computeStorageSequenceGroups(first, listOf(invalid), 2, context)
            assertTrue(result.groups.isEmpty())
            assertEquals(computeSeqGroups(rows, listOf(invalid), CancellationCheck {}, RegexEvaluationContext()), result.legacy())
        } finally {
            first.close()
            second.close()
            source.close()
        }
    }

    @Test
    fun regexTimeoutStateIsSharedAcrossFilteringAndGrouping() = runBlocking {
        val rows = (1..10).map { row(it, "a".repeat(40) + "!") }
        val source = LegacyInMemorySource(SourceId("timeout"), LogFormat.LOGCAT, rows, maxPageRows = 2)
        val query = StorageQuery(source.snapshot(), 2, 2)
        try {
            val context = RegexEvaluationContext(matchBudgetNanos = 1L)
            val filter = Filter(mode = FilterMode.KEYWORD, kwText = "(a+)+$", kwRegex = true)
            assertEquals(0L, query.scanFilter(filter, regexContext = context))
            assertTrue(context.hasTimedOut)
            val def = SequenceDef("timeout", "(a+)+$", isRegex = true, priority = 1, color = Color.Red)
            val result = computeStorageSequenceGroups(query, listOf(def), 2, context)
            assertTrue(result.regexTimedOut)
            assertTrue(result.groups.isEmpty())
        } finally {
            query.close()
            source.close()
        }
    }
}
