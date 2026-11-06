package com.indagium.storage

import androidx.compose.ui.graphics.Color
import com.indagium.model.AppSettings
import com.indagium.model.Filter
import com.indagium.model.FilterMode
import com.indagium.model.LogFormat
import com.indagium.model.LogTab
import com.indagium.model.SequenceDef
import com.indagium.storage.projection.DecodedDisplayEvent
import com.indagium.ui.mkTab
import com.indagium.utils.LogLinePresentationContext
import com.indagium.utils.RegexEvaluationContext
import com.indagium.utils.computeProcessNames
import com.indagium.utils.computeItems
import com.indagium.utils.computeSearchMatches
import com.indagium.utils.computeTagPids
import com.indagium.utils.parseLogcat
import com.indagium.utils.presentLogLine
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class StackFileSessionTest {
    @Test
    fun searchMatchesFullyExpandedEagerViewForFilteredAndOriginalScopes(): Unit = runBlocking {
        val path = Files.createTempFile("stack-session-search-", ".log")
        try {
            Files.writeString(path, """
                01-01 12:00:00.000 1 1 E App: FATAL EXCEPTION: main
                01-01 12:00:00.001 1 1 E App:     at app.Example.go(A.kt:1)
                01-01 12:00:00.002 1 1 I App: done
            """.trimIndent())
            val filter = Filter(mode = FilterMode.KEYWORD, kwText = "done")
            val legacy = mkTab("search-eager", "generated.log", parseLogcat(path.toFile())).copy(filter = filter)
            val expanded = legacy.analysis.stackTraceGroups.mapTo(HashSet()) { it.gid }
            val session = StackFileSession.open(SourceId("search"), path, filter, LogFormat.LOGCAT, false, pageRows = 2)
            try {
                for (applyFilter in listOf(false, true)) {
                    val expected = computeSearchMatches(
                        computeItems(legacy.copy(expanded = expanded), applyFilter), "Example|done", false, RegexEvaluationContext(),
                    )
                    assertEquals(expected, session.search("Example|done", false, applyFilter))
                }
                assertTrue(session.search("[", false, true).invalidPattern)
                assertEquals(0, session.search("", false, true).matchIds.size)
            } finally {
                session.close()
                session.awaitClosed()
            }
        } finally {
            Files.deleteIfExists(path)
        }
    }

    @Test
    fun fileAnalysisSuppliesProcessNamesAndTagPidsForFiltering(): Unit = runBlocking {
        val path = Files.createTempFile("stack-session-analysis-", ".log")
        try {
            Files.writeString(path, """
                01-01 12:00:00.000 1234 1234 I Target: before start
                01-01 12:00:00.001 50 50 I ActivityManager: Start proc 1234:com.example.app/u0a1 for activity
                01-01 12:00:00.002 1234 1234 I Other: after start
                01-01 12:00:00.003 99 99 I Other: unrelated
            """.trimIndent())
            val legacy = parseLogcat(path.toFile())
            val session = StackFileSession.open(
                SourceId("analysis"), path, Filter(pidTidFilter = "com.example.app"), LogFormat.LOGCAT, false, pageRows = 2,
            )
            try {
                assertEquals(computeProcessNames(legacy), session.analysis.processNames)
                assertEquals(computeTagPids(legacy), session.analysis.tagPids)
                assertEquals(legacy.groupingBy { it.tag }.eachCount(), session.analysis.tagCounts)
                assertEquals(2L, session.displayCount)
            } finally {
                session.close()
                session.awaitClosed()
            }
        } finally {
            Files.deleteIfExists(path)
        }
    }

    @Test
    fun opensGeneratedFileAndClosesItsView(): Unit = runBlocking {
        val path = Files.createTempFile("stack-session-", ".log")
        try {
            Files.writeString(path, """
                01-01 12:00:00.000 1 1 E App: FATAL EXCEPTION: main
                01-01 12:00:00.001 1 1 E App:     at app.Example.go(A.kt:1)
                01-01 12:00:00.002 1 1 I App: done
            """.trimIndent())
            val session = StackFileSession.open(SourceId("generated"), path, Filter(), LogFormat.LOGCAT, false, expanded = setOf("st_1"), pageRows = 2)
            assertEquals(3L, session.displayCount)
            val first = session.page(0, 2)
            assertEquals(session.generation, first.generation)
            assertTrue(first.items[0] is DecodedDisplayEvent.Header)
            assertEquals(2, (first.items[1] as DecodedDisplayEvent.Row).row.entry.id)
            assertEquals(3, (session.page(2, 2).items.single() as DecodedDisplayEvent.Row).row.entry.id)
            val generation = session.generation
            val selectionGeneration = SelectionGeneration(
                SourceId("generated"), generation.sourceVersion, generation.queryId, generation.projectionId, 1, 1,
            )
            val selection = StorageSelection(selectionGeneration, SelectionBase.AllDisplayedRows)
            assertContentEquals(intArrayOf(2, 3), session.selectedIdsForCopy(selection, selectionGeneration))
            val settings = AppSettings()
            val expected = parseLogcat(path.toFile()).filter { it.id in setOf(2, 3) }
                .joinToString("\n") { presentLogLine(LogTab("copy", "", emptyList(), emptyMap()), it, settings) }
            assertEquals(expected, session.selectedTextForCopy(selection, selectionGeneration, settings, false, 10_000))
            val entries = parseLogcat(path.toFile())
            val deltaSettings = settings.copy(copyTimeDelta = true)
            val deltaTab = LogTab("copy", "", entries, entries.associateBy { it.id }, selected = setOf(2, 3), showTimeDelta = true)
            val deltaContext = LogLinePresentationContext(deltaTab, deltaSettings, emptyList())
            val expectedDelta = entries.filter { it.id in setOf(2, 3) }.joinToString("\n") {
                presentLogLine(deltaTab, it, deltaSettings, deltaContext)
            }
            assertEquals(expectedDelta, session.selectedTextForCopy(selection, selectionGeneration, deltaSettings, true, 10_000))
            assertFailsWith<StorageFailure.InvalidRequest> {
                session.selectedTextForCopy(selection, selectionGeneration, settings, false, 1)
            }
            val output = Files.createTempFile("stack-session-export-", ".txt")
            try {
                session.export(output.toFile(), StorageExportSpec(LogFormat.LOGCAT, AppSettings(), false, null, emptyMap(), false))
                val text = Files.readString(output)
                assertTrue(text.contains("FATAL EXCEPTION"))
                assertTrue(text.contains("app.Example.go"))
                assertTrue(text.contains("done"))
            } finally {
                Files.deleteIfExists(output)
            }
            Files.writeString(path, "replacement\n")
            assertFailsWith<IndexedFileFailure.Changed> { session.page(0, 1) }
            session.close()
            session.close()
            session.awaitClosed()
            assertFailsWith<StorageFailure.Closed> { session.page(0, 1) }
        } finally {
            Files.deleteIfExists(path)
        }
    }

    @Test
    fun unsupportedGroupsAndCancelledOpenAreExplicit(): Unit = runBlocking {
        val path = Files.createTempFile("stack-session-", ".log")
        try {
            Files.writeString(path, "generated\n")
            assertFailsWith<StorageFailure.InvalidRequest> {
                StackFileSession.open(SourceId("generated"), path, Filter(), LogFormat.LOGCAT, true)
            }
            assertFailsWith<StorageFailure.InvalidRequest> {
                StackFileSession.open(SourceId("generated"), path, Filter(), LogFormat.DLT, false)
            }
            val sequence = SequenceDef("sequence", "start", priority = 0, color = Color.Red)
            assertFailsWith<StorageFailure.InvalidRequest> {
                StackFileSession.open(SourceId("generated"), path, Filter(sequences = listOf(sequence)), LogFormat.LOGCAT, false)
            }
            val cancelled = Job().apply { cancel() }
            assertFailsWith<CancellationException> {
                withContext(cancelled) { StackFileSession.open(SourceId("generated"), path, Filter(), LogFormat.LOGCAT, false) }
            }
        } finally {
            Files.deleteIfExists(path)
        }
    }

    @Test
    fun timedOutFilterDoesNotProduceACompletedSession(): Unit = runBlocking {
        val path = Files.createTempFile("stack-session-timeout-", ".log")
        try {
            Files.writeString(path, "a".repeat(10_000) + "!\n")
            val context = RegexEvaluationContext(matchBudgetNanos = 1)
            val filter = Filter(mode = FilterMode.KEYWORD, kwText = "(a+)+$", kwRegex = true)
            assertFailsWith<StorageFailure.InvalidRequest> {
                StackFileSession.open(SourceId("generated"), path, filter, LogFormat.LOGCAT, false, regexContext = context)
            }
            assertTrue(context.hasTimedOut)
        } finally {
            Files.deleteIfExists(path)
        }
    }

    @Test
    fun oneStackInALargeGeneratedFileRemainsPageable(): Unit = runBlocking {
        val path = Files.createTempFile("stack-session-large-", ".log")
        try {
            Files.newBufferedWriter(path).use { writer ->
                repeat(50_000) { writer.appendLine("01-01 12:00:00.000 2 2 I App: ordinary") }
                writer.appendLine("01-01 12:00:00.001 1 1 E App: FATAL EXCEPTION: main")
                writer.appendLine("01-01 12:00:00.002 1 1 E App:     at app.Example.go(A.kt:1)")
                repeat(49_998) { writer.appendLine("01-01 12:00:00.003 2 2 I App: ordinary") }
            }
            val sourceId = SourceId("large-generated")
            val session = StackFileSession.open(sourceId, path, Filter(), LogFormat.LOGCAT, false,
                expanded = setOf("st_50001"), pageRows = 256)
            try {
                assertEquals(100_000L, session.displayCount)
                val page = session.page(50_000, 2)
                assertTrue(page.items[0] is DecodedDisplayEvent.Header)
                assertEquals(50_002, (page.items[1] as DecodedDisplayEvent.Row).row.entry.id)
                val generation = session.generation
                val selectionGeneration = SelectionGeneration(
                    sourceId, generation.sourceVersion, generation.queryId, generation.projectionId, 1, 1,
                )
                val ids = session.selectedIdsForCopy(StorageSelection(selectionGeneration, SelectionBase.AllDisplayedRows),
                    selectionGeneration, maxSelectedIds = 100_000)
                assertEquals(99_999, ids.size)
                assertEquals(1, ids.first())
                assertEquals(100_000, ids.last())
            } finally {
                session.close()
                session.awaitClosed()
            }
        } finally {
            Files.deleteIfExists(path)
        }
    }
}
