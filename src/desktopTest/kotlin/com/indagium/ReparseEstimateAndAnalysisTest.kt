@file:Suppress("MagicNumber")

package com.indagium

import com.indagium.model.LogAnalysis
import com.indagium.model.LogFormat
import com.indagium.model.TabContentState
import com.indagium.ui.AppState
import com.indagium.ui.buildLogAnalysis
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.io.path.createTempDirectory
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** A Reparse budgets the parser that will really read the source, and its analysis never lands on rows it was not computed from. */
class ReparseEstimateAndAnalysisTest {
    private fun newState(directory: File) = AppState(
        autosaveFile = File(directory, "state.cache"),
        autoExportNotes = false,
        notesDir = File(directory, "notes"),
        archiveCacheDir = File(directory, "archive-cache"),
    )

    private suspend fun awaitLoaded(state: AppState, tabId: String) = withTimeout(TIMEOUT_MS) {
        while (state.tab(tabId) == null && state.isLoadInFlight(tabId)) delay(POLL_MS)
        state.awaitTabLoaded(tabId) ?: error("Tab $tabId failed to load: ${state.tab(tabId)?.contentLoadError}")
    }

    private fun jsonRows() = (1..20).joinToString("\n", postfix = "\n") {
        """{"timestamp":"2026-05-04T12:30:00Z","level":"INFO","message":"row $it"}"""
    }

    // ---- 4: the estimate follows the parser that will really read the source ----

    /** A free-heap value between what the Logcat/DLT factor and the generic-profile factor estimate for [bytes]. */
    private fun AppState.midpointFor(bytes: Long): Long {
        val cheap = estimatedHeapBytesForProfile(logFormatProfile("automotive-dlt")!!, bytes)
        val dear = estimatedHeapBytesForProfile(logFormatProfile("json-lines")!!, bytes)
        assertTrue(cheap < dear)
        return (cheap + dear) / 2
    }

    @Test
    fun aBinaryDltTabReparsedWithAJsonProfileIsBudgetedAsDlt() = runBlocking {
        val directory = createTempDirectory("reparse-estimate-dlt").toFile()
        val source = File(directory, "capture.dlt").apply { writeBytes(dltStorageHeader() + dltTestFrame("one") + dltStorageHeader() + dltTestFrame("two")) }
        val app = newState(directory)
        try {
            app.heapFreeBytesProvider = { Long.MAX_VALUE }
            val sourceId = assertNotNull(app.openFile(source))
            assertEquals(LogFormat.DLT, awaitLoaded(app, sourceId).logFormat)

            app.heapFreeBytesProvider = { app.midpointFor(source.length()) }
            val reparsedId = assertNotNull(app.reparseTabWithLogFormatProfileAwait(sourceId, "json-lines"), app.logFormatProfileError)
            assertEquals(LogFormat.DLT, app.tab(reparsedId)?.logFormat)
            assertEquals(listOf("one", "two"), app.tab(reparsedId)?.logData?.map { it.msg })

            // The callback form goes through the same estimate.
            val results = java.util.concurrent.LinkedBlockingQueue<Result<String>>()
            app.reparseTabWithLogFormatProfile(sourceId, "json-lines") { results.add(it) }
            val outcome = withContext(Dispatchers.IO) { results.poll(TIMEOUT_MS, TimeUnit.MILLISECONDS) }
            assertTrue(outcome?.isSuccess == true, outcome?.exceptionOrNull()?.message)
        } finally {
            app.close()
            directory.deleteRecursively()
        }
    }

    @Test
    fun aTextTabWithTheSameNumbersIsStillRefusedForAJsonProfile() = runBlocking {
        val directory = createTempDirectory("reparse-estimate-text").toFile()
        val source = File(directory, "events.log").apply { writeText(jsonRows()) }
        val app = newState(directory)
        try {
            app.heapFreeBytesProvider = { Long.MAX_VALUE }
            val sourceId = assertNotNull(app.openFileWithLogFormatProfile(source, "plain-text"))
            awaitLoaded(app, sourceId)

            app.heapFreeBytesProvider = { app.midpointFor(source.length()) }
            assertNull(app.reparseTabWithLogFormatProfileAwait(sourceId, "json-lines"))
            assertTrue(app.logFormatProfileError.orEmpty().contains("available"), app.logFormatProfileError)
        } finally {
            app.close()
            directory.deleteRecursively()
        }
    }

    @Test
    fun changeFormatUsesTheEntireStandalonePathContainingAnExclamationMark() = runBlocking {
        val directory = createTempDirectory("reparse-literal-bang").toFile()
        val source = File(directory, "events!2.log").apply { writeText(jsonRows()) }
        val app = newState(directory)
        try {
            app.heapFreeBytesProvider = { Long.MAX_VALUE }
            val sourceId = assertNotNull(app.openFileWithLogFormatProfile(source, "plain-text"))
            awaitLoaded(app, sourceId)

            val profile = assertNotNull(app.logFormatProfile("json-lines"))
            val preview = kotlinx.coroutines.CompletableDeferred<Result<com.indagium.utils.ParsedLog>>()
            app.previewCurrentLogFormatProfile(sourceId, profile) { preview.complete(it) }
            assertEquals("row 1", withTimeout(TIMEOUT_MS) { preview.await().getOrThrow() }.entries.first().msg)

            app.heapFreeBytesProvider = { app.midpointFor(source.length()) }
            assertNull(app.reparseTabWithLogFormatProfileAwait(sourceId, profile.id))
            assertTrue(app.logFormatProfileError.orEmpty().contains("available"))

            app.heapFreeBytesProvider = { Long.MAX_VALUE }
            val result = assertNotNull(app.reparseTabWithLogFormatProfileAwait(sourceId, profile.id), app.logFormatProfileError)
            assertEquals(source.absolutePath, app.tab(result)?.sourcePath)
            assertEquals("row 1", app.tab(result)?.logData?.first()?.msg)
        } finally {
            app.close()
            directory.deleteRecursively()
        }
    }

    @Test
    fun aDltViewerTextExportIsStillBudgetedForTheTextProfileItWillBeReadWith() = runBlocking {
        val directory = createTempDirectory("reparse-estimate-viewer").toFile()
        val csv = "Time,ECU,AppId,ContextId,Type,Payload\n" + (1..30).joinToString("\n") { "2026-01-02 03:04:05,ECU1,APP1,CTX1,ERROR,message $it" } + "\n"
        val source = File(directory, "viewer!export.csv").apply { writeText(csv) }
        val app = newState(directory)
        try {
            app.heapFreeBytesProvider = { Long.MAX_VALUE }
            val sourceId = assertNotNull(app.openFile(source))
            // It opens as DLT, but its bytes are text: a JSON profile reads it as plain text rows.
            assertEquals(LogFormat.DLT, awaitLoaded(app, sourceId).logFormat)

            app.heapFreeBytesProvider = { app.midpointFor(source.length()) }
            assertNull(app.reparseTabWithLogFormatProfileAwait(sourceId, "json-lines"))
            assertTrue(app.logFormatProfileError.orEmpty().contains("available"), app.logFormatProfileError)
        } finally {
            app.close()
            directory.deleteRecursively()
        }
    }

    // ---- 5: a stale analysis never overwrites the rows a section switch published ----

    private val root = "Tethered Bluetooth"
    private val hci = "$root / HCI"
    private val l2cap = "$root / L2CAP"

    private fun packet(identity: String, title: String, offset: Long) =
        """{"metadata":{"identity":"$identity","source":"Demo","system_time":${BASE_TIME + offset}},"Summary":{"Note":"$title"}}"""

    private fun sectionedDocument() =
        """{"$root":{"HCI":[${packet("HCI.A", "hci one", 10)},${packet("HCI.B", "hci two", 50)}],""" +
            """"L2CAP":[${packet("L2.A", "l2cap one", 20)},${packet("L2.B", "l2cap two", 60)},${packet("L2.C", "l2cap three", 70)}]}}"""

    @Test
    fun aSectionSwitchBeforeTheReparseAnalysisFinishesKeepsTheNewSectionsAnalysis() = runBlocking {
        val directory = createTempDirectory("reparse-stale-analysis").toFile()
        val app = newState(directory)
        val gate = CountDownLatch(1)
        val entered = CountDownLatch(1)
        val built = CountDownLatch(1)
        try {
            app.logAnalysisBuilder = { rows, rules, format, profile ->
                entered.countDown()
                check(gate.await(TIMEOUT_MS, TimeUnit.MILLISECONDS)) { "the test never released the analysis" }
                buildLogAnalysis(rows, rules, format, profile = profile).also { built.countDown() }
            }
            val file = File(directory, "capture.json").apply { writeText(sectionedDocument()) }
            app.heapFreeBytesProvider = { Long.MAX_VALUE }
            val plainId = assertNotNull(app.openFileWithLogFormatProfile(file, "plain-text"))
            awaitLoaded(app, plainId)

            val reparsedId = assertNotNull(app.reparseTabWithLogFormatProfileAwait(plainId, "metadata-json"))
            assertEquals(hci, app.tab(reparsedId)?.selectedLogSectionId)
            withContext(Dispatchers.IO) { assertTrue(entered.await(TIMEOUT_MS, TimeUnit.MILLISECONDS)) }

            assertTrue(app.selectLogSectionAndAwait(reparsedId, l2cap))
            val shown = assertNotNull(app.tab(reparsedId))
            assertEquals(listOf("l2cap one", "l2cap two", "l2cap three"), shown.logData.map { it.msg })
            val expectedTags = mapOf("L2.A" to 1, "L2.B" to 1, "L2.C" to 1)
            withTimeout(TIMEOUT_MS) { while (app.tab(reparsedId)?.analysis?.pending != false) delay(POLL_MS) }
            assertEquals(expectedTags, app.tab(reparsedId)?.analysis?.tagCounts)

            gate.countDown()
            withContext(Dispatchers.IO) { assertTrue(built.await(TIMEOUT_MS, TimeUnit.MILLISECONDS)) }
            delay(SETTLE_MS) // the stale result's write-back runs right after the build returns
            val after = assertNotNull(app.tab(reparsedId))
            assertEquals(TabContentState.LOADED, after.contentState)
            assertEquals(l2cap, after.selectedLogSectionId)
            assertEquals(expectedTags, after.analysis.tagCounts, "the first section's tag counts must not replace the shown section's")
        } finally {
            gate.countDown()
            app.close()
            directory.deleteRecursively()
        }
    }

    @Test
    fun aReparseAnalysisThatFinishesWithoutASectionSwitchIsApplied() = runBlocking {
        val directory = createTempDirectory("reparse-analysis-applied").toFile()
        val app = newState(directory)
        try {
            val file = File(directory, "capture.json").apply { writeText(sectionedDocument()) }
            app.heapFreeBytesProvider = { Long.MAX_VALUE }
            val plainId = assertNotNull(app.openFileWithLogFormatProfile(file, "plain-text"))
            awaitLoaded(app, plainId)
            val reparsedId = assertNotNull(app.reparseTabWithLogFormatProfileAwait(plainId, "metadata-json"))
            withTimeout(TIMEOUT_MS) { while (app.tab(reparsedId)?.analysis?.pending != false) delay(POLL_MS) }
            assertEquals(mapOf("HCI.A" to 1, "HCI.B" to 1), app.tab(reparsedId)?.analysis?.tagCounts)
        } finally {
            app.close()
            directory.deleteRecursively()
        }
    }

    @Test
    fun aCaptureOpenedTabKeepsTheAnalysisOfRowsThatReplacedTheOnesItWasComputedFor() = runBlocking {
        val directory = createTempDirectory("capture-stale-analysis").toFile()
        val app = AppState(autosaveFile = File(directory, "state.cache"), autoExportNotes = false, archiveCacheDir = File(directory, "cache"))
        val gate = CountDownLatch(1)
        val entered = CountDownLatch(1)
        val built = CountDownLatch(1)
        try {
            app.logAnalysisBuilder = { rows, rules, format, profile ->
                entered.countDown()
                check(gate.await(TIMEOUT_MS, TimeUnit.MILLISECONDS)) { "the test never released the analysis" }
                buildLogAnalysis(rows, rules, format, profile = profile).also { built.countDown() }
            }
            val archive = CaptureArchiveFixtures.exportWithVideo(directory)
            val tabId = app.openCaptureFile(archive)
            withContext(Dispatchers.IO) { assertTrue(entered.await(TIMEOUT_MS, TimeUnit.MILLISECONDS)) }
            val shown = assertNotNull(app.tab(tabId))
            assertTrue(shown.logData.isNotEmpty())

            // The rows are replaced while the analysis is computed; the replacement carries its own analysis.
            val own = LogAnalysis(tagCounts = mapOf("replacement" to 1), pending = false)
            app.upTab(tabId) { it.copy(logData = emptyList(), rmap = emptyMap(), analysis = own) }

            gate.countDown()
            withContext(Dispatchers.IO) { assertTrue(built.await(TIMEOUT_MS, TimeUnit.MILLISECONDS)) }
            delay(SETTLE_MS)
            assertEquals(own, app.tab(tabId)?.analysis, "an analysis of the old rows must not replace the new rows' one")
        } finally {
            gate.countDown()
            app.close()
            directory.deleteRecursively()
        }
    }

    private companion object {
        const val BASE_TIME = 1_790_000_000_000_000_000L
        const val TIMEOUT_MS = 20_000L
        const val POLL_MS = 25L
        const val SETTLE_MS = 500L
    }
}
