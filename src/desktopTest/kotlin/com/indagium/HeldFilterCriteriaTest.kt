package com.indagium

import com.indagium.model.BUILTIN_LOG_FORMAT_PROFILES
import com.indagium.model.Filter
import com.indagium.model.FilterMode
import com.indagium.model.LogEntry
import com.indagium.model.LogFormatAttribute
import com.indagium.model.LogFormatEngine
import com.indagium.model.LogFormatProfile
import com.indagium.model.LogLevel
import com.indagium.model.LogProfileValueType
import com.indagium.model.SavedFilter
import com.indagium.model.TabContentState
import com.indagium.model.UNKNOWN_SEVERITY_METADATA
import com.indagium.model.deriveLogCapabilities
import com.indagium.ui.AppState
import com.indagium.ui.filterForPublishedRows
import com.indagium.ui.mkTab
import com.indagium.ui.tabShellFromToken
import com.indagium.ui.tabToken
import com.indagium.ui.toFilter
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import java.io.File
import kotlin.io.path.createTempDirectory
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** A profile tab keeps the filter criteria its rows cannot filter by, instead of losing them to a load that lacks the fields. */
class HeldFilterCriteriaTest {
    private val plainText: LogFormatProfile = BUILTIN_LOG_FORMAT_PROFILES.first { it.engine == LogFormatEngine.PLAIN_TEXT }

    private fun row(id: Int, severity: Boolean, tag: String = "") = LogEntry(
        id, "", LogLevel.E, tag, "message $id",
        recordMetadata = if (severity) null else UNKNOWN_SEVERITY_METADATA,
    )

    private fun publish(filter: Filter, held: Filter?, rows: List<LogEntry>) =
        filterForPublishedRows(filter, held, rows.isEmpty(), deriveLogCapabilities(plainText, rows), plainText)

    @Test
    fun emptyRowsKeepTheFilterAndHeldCriteriaAndShowNoNotice() {
        val filter = Filter(activeTags = setOf("worker"), pidTidFilter = "77", levels = setOf(LogLevel.E))
        val held = Filter(activeTags = setOf("worker"), pidTidFilter = "77", levels = setOf(LogLevel.E), kwText = "kept")

        val state = publish(filter, held, emptyList())

        assertEquals(filter, state.filter)
        assertEquals(held, state.heldFilterCriteria)
        assertNull(state.notice)
    }

    @Test
    fun rowsWithoutSeverityTrimTheLevelFilterAndHoldItUntilRowsThatHaveItArrive() {
        val levelFilter = Filter(levels = setOf(LogLevel.E), kwText = "needle")

        val trimmed = publish(levelFilter, null, listOf(row(1, severity = false)))
        assertEquals(LogLevel.entries.toSet(), trimmed.filter.levels)
        assertEquals("needle", trimmed.filter.kwText)
        assertEquals(levelFilter, trimmed.heldFilterCriteria)
        assertNotNull(trimmed.notice)

        val restored = publish(trimmed.filter, trimmed.heldFilterCriteria, listOf(row(1, severity = true)))
        assertEquals(levelFilter, restored.filter)
        assertNull(restored.heldFilterCriteria)
        assertNull(restored.notice)
    }

    @Test
    fun criteriaStayHeldAcrossSeveralLoadsThatLackTheField() {
        val levelFilter = Filter(levels = setOf(LogLevel.W))
        val first = publish(levelFilter, null, listOf(row(1, severity = false)))
        val second = publish(first.filter, first.heldFilterCriteria, listOf(row(2, severity = false)))

        assertEquals(first.filter, second.filter)
        assertEquals(levelFilter, second.heldFilterCriteria)
        assertNotNull(second.notice)
    }

    @Test
    fun aFilterEditedWhileCriteriaAreHeldNeverGetsThemBack() {
        val levelFilter = Filter(levels = setOf(LogLevel.E))
        val trimmed = publish(levelFilter, null, listOf(row(1, severity = false)))
        val edited = trimmed.filter.copy(kwText = "foo")

        val reloaded = publish(edited, trimmed.heldFilterCriteria, listOf(row(1, severity = true)))

        assertEquals(edited, reloaded.filter)
        assertEquals(LogLevel.entries.toSet(), reloaded.filter.levels)
        assertNull(reloaded.heldFilterCriteria)
    }

    @Test
    fun aPresetLoadedWhileCriteriaAreHeldReplacesThem() {
        val trimmed = publish(Filter(levels = setOf(LogLevel.E)), null, listOf(row(1, severity = false)))
        val preset = Filter(levels = setOf(LogLevel.D), activeTags = setOf("net"))

        val reloaded = publish(preset, trimmed.heldFilterCriteria, listOf(row(1, severity = true, tag = "net")))

        assertEquals(preset, reloaded.filter)
        assertNull(reloaded.heldFilterCriteria)
    }

    @Test
    fun heldCriteriaRoundTripThroughTheTabTokenAndOldTokensDecodeWithNone() {
        val dir = createTempDirectory("held-filter-token").toFile()
        try {
            val source = File(dir, "app.log").apply { writeText("x\n") }
            val held = Filter(levels = setOf(LogLevel.E, LogLevel.W), activeTags = setOf("worker"), pidTidFilter = "12", kwText = "boom")
            val tab = mkTab("held", "app.log", emptyList()).copy(sourcePath = source.absolutePath, heldFilterCriteria = held)

            val token = tab.tabToken()
            assertEquals(held, token.tabShellFromToken()?.tab?.heldFilterCriteria)

            val withoutHeld = mkTab("plain", "app.log", emptyList()).copy(sourcePath = source.absolutePath).tabToken()
            assertNull(withoutHeld.tabShellFromToken()?.tab?.heldFilterCriteria)
            // The field after the held criteria (the note's closed sources) is the token's last; a token from before both has neither.
            val oldToken = token.substringBeforeLast('|').substringBeforeLast('|')
            assertNull(oldToken.tabShellFromToken()?.tab?.heldFilterCriteria, "a token from before the field decodes with none")
            assertEquals(tab.filter, oldToken.tabShellFromToken()?.tab?.filter)
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test
    fun anEditThatEndsOnTheTrimmedValueStillDropsTheHeldCriteria() {
        val directory = createTempDirectory("held-filter-edit").toFile()
        try {
            val app = newState(directory)
            val held = Filter(levels = setOf(LogLevel.E))
            val trimmed = Filter()
            app.tabs = listOf(mkTab("t", "app.log", listOf(row(1, severity = false))).copy(filter = trimmed, heldFilterCriteria = held))

            // A re-push of the same filter (a debounced field settling) is not an edit: the held criteria stay.
            app.upFlt("t") { it }
            assertEquals(held, app.tab("t")?.heldFilterCriteria)

            // The user picks a level, then goes back to all levels: the filter ends where the trim left it, yet it is the user's now.
            app.upFlt("t") { it.copy(levels = setOf(LogLevel.W)) }
            app.upFlt("t") { it.copy(levels = LogLevel.entries.toSet()) }
            val tab = requireNotNull(app.tab("t"))
            assertNull(tab.heldFilterCriteria)

            val reloaded = publish(tab.filter, tab.heldFilterCriteria, listOf(row(1, severity = true)))
            assertEquals(LogLevel.entries.toSet(), reloaded.filter.levels)
            app.close()
        } finally {
            directory.deleteRecursively()
        }
    }

    @Test
    fun aPresetLoadedIntoRowsThatLackAFieldHoldsTheTrimmedCriteria() {
        val directory = createTempDirectory("held-filter-preset").toFile()
        try {
            val app = newState(directory)
            app.tabs = listOf(mkTab("t", "notes.txt", listOf(row(1, severity = false)), profile = plainText))
            val preset = SavedFilter(
                id = "errors", name = "Errors only", levels = setOf(LogLevel.E), activeTags = emptySet(), kwText = "boom",
                kwRegex = false, mode = FilterMode.KEYWORD, excludeTags = emptySet(), excludeKw = "", excludeKwRegex = false,
                highlighters = emptyList(), seqOn = true,
            )

            app.loadFilter("t", preset)

            val tab = requireNotNull(app.tab("t"))
            // The rows have no levels, so the level criterion is trimmed from the filter but set aside with the rest of the preset.
            assertEquals(LogLevel.entries.toSet(), tab.filter.levels)
            assertEquals("boom", tab.filter.kwText)
            assertNotNull(tab.filterNormalizationNotice)
            assertEquals(preset.toFilter(), tab.heldFilterCriteria)

            // A later load whose rows have levels brings the preset's level criterion back.
            val reloaded = publish(tab.filter, tab.heldFilterCriteria, listOf(row(1, severity = true)))
            assertEquals(setOf(LogLevel.E), reloaded.filter.levels)
            assertNull(reloaded.heldFilterCriteria)

            // A preset that fits the rows holds nothing.
            app.loadFilter("t", preset.copy(id = "all", levels = LogLevel.entries.toSet()))
            assertNull(app.tab("t")?.heldFilterCriteria)
            app.close()
        } finally {
            directory.deleteRecursively()
        }
    }

    private fun newState(directory: File, restore: Boolean = false): AppState = AppState(
        autosaveFile = File(directory, "state.cache"),
        restoreOnCreate = restore,
        autoExportNotes = false,
        notesDir = File(directory, "notes"),
        archiveCacheDir = File(directory, "archive-cache"),
    )

    @Test
    fun aRestoredProfileTabWhoseFileIsNowEmptyKeepsItsTagFilter() = runBlocking {
        val directory = createTempDirectory("held-filter-empty-file").toFile()
        val source = File(directory, "service.jsonl").apply {
            writeText("""{"timestamp":"2026-05-04T12:30:00Z","level":"INFO","message":"first","trace":"a"}""" + "\n")
        }
        val profile = LogFormatProfile(
            id = "held-filter-json",
            name = "Held filter JSON",
            engine = LogFormatEngine.JSON_LINES,
            attributes = listOf(LogFormatAttribute("traceId", "Trace ID", "trace", LogProfileValueType.TEXT)),
        )
        val filter = Filter(activeTags = setOf("worker"), pidTidFilter = "77", levels = setOf(LogLevel.E))
        val app = newState(directory)
        try {
            assertTrue(app.saveLogFormatProfile(profile).isSuccess)
            val tabId = assertNotNull(app.openFileWithLogFormatProfile(source, profile.id))
            awaitLoaded(app, tabId)
            app.upTab(tabId) { it.copy(filter = filter) }
            app.autosaveNow()
        } finally {
            app.close()
        }
        source.writeText("")

        val restored = newState(directory, restore = true)
        try {
            restored.startPendingRestoredTabLoads()
            val tab = awaitLoaded(restored, restored.tabs.single().id)
            assertEquals(TabContentState.LOADED, tab.contentState)
            assertTrue(tab.logData.isEmpty())
            assertEquals(filter, tab.filter)
            assertNull(tab.filterNormalizationNotice)
        } finally {
            restored.close()
            directory.deleteRecursively()
        }
    }

    @Test
    fun heldCriteriaFollowTheirSectionAcrossSectionSwitches() = runBlocking {
        val directory = createTempDirectory("held-filter-sections").toFile()
        val file = File(directory, "capture.json").apply { writeText(sectionedDocument()) }
        val app = newState(directory)
        try {
            val tabId = assertNotNull(app.openFile(file))
            withTimeout(TIMEOUT_MS) {
                while (app.tab(tabId)?.let { it.contentState == TabContentState.LOADED && it.selectedLogSectionId == HCI } != true) delay(POLL_MS)
            }
            val levelFilter = Filter(levels = setOf(LogLevel.E))
            app.upTab(tabId) { it.copy(filter = levelFilter) }

            assertTrue(app.selectLogSectionAndAwait(tabId, L2CAP))
            assertEquals(Filter(), app.tab(tabId)!!.filter, "a section never opened has a fresh filter")
            assertNull(app.tab(tabId)!!.heldFilterCriteria)

            assertTrue(app.selectLogSectionAndAwait(tabId, HCI))
            val back = app.tab(tabId)!!
            assertEquals(LogLevel.entries.toSet(), back.filter.levels, "this section's rows have no severity to filter by")
            assertEquals(levelFilter, back.heldFilterCriteria)
            assertNotNull(back.filterNormalizationNotice)

            assertTrue(app.selectLogSectionAndAwait(tabId, L2CAP))
            assertNull(app.tab(tabId)!!.heldFilterCriteria, "held criteria stay with the section that trimmed them")
            assertTrue(app.selectLogSectionAndAwait(tabId, HCI))
            assertEquals(levelFilter, app.tab(tabId)!!.heldFilterCriteria)
        } finally {
            app.close()
            directory.deleteRecursively()
        }
    }

    /** Records of both sections interleave in time, in the order of [PACKET_ORDER]. */
    private fun packet(identity: String): String {
        val offset = PACKET_ORDER.indexOf(identity) * PACKET_STEP_NS
        return """{"metadata":{"identity":"$identity","source":"Demo","system_time":${T0 + offset}},"Summary":{"Note":"$identity"}}"""
    }

    private fun sectionedDocument(): String =
        """{"$ROOT":{"HCI":[${packet("HCI.A")},${packet("HCI.B")}],""" +
            """"L2CAP":[${packet("L2.A")},${packet("L2.B")}]}}"""

    private suspend fun awaitLoaded(state: AppState, tabId: String) = withTimeout(TIMEOUT_MS) {
        while (state.tab(tabId) == null && state.isLoadInFlight(tabId)) delay(POLL_MS)
        state.awaitTabLoaded(tabId)
            ?: error("Tab $tabId failed to load: ${state.tab(tabId)?.contentLoadError ?: "it was not published"}")
    }

    private companion object {
        const val TIMEOUT_MS = 20_000L
        const val POLL_MS = 10L
        const val ROOT = "Tethered Bluetooth"
        const val HCI = "$ROOT / HCI"
        const val L2CAP = "$ROOT / L2CAP"
        const val T0 = 1_790_000_000_000_000_000L
        const val PACKET_STEP_NS = 10_000_000L
        val PACKET_ORDER = listOf("HCI.A", "L2.A", "HCI.B", "L2.B")
    }
}
