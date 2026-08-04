package com.indagium.debug

import com.indagium.model.LogEntry
import com.indagium.model.LogLevel
import com.indagium.model.MessageCompositionState
import com.indagium.model.MessageTemplate
import com.indagium.model.MessageTemplateHistogram
import com.indagium.model.TemplateGranularity
import com.indagium.ui.AppState
import com.indagium.ui.mkTab
import com.indagium.utils.viewDefiningKey
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Stage 3: the `get_log_composition` automation tool exposes utils/MessageTemplates.kt's
 * histogram — see IndagiumToolOperations.getLogComposition's own doc for why this scans
 * synchronously on the calling thread instead of polling AppState.requestMessageComposition's
 * async result (the awaitLoad/SAAD-R4 anti-pattern this deliberately avoids).
 */
class GetLogCompositionToolTest {
    private fun entry(id: Int, tag: String, msg: String) = LogEntry(id, "10:00:00.${"%03d".format(id)}", LogLevel.I, tag, msg)

    // Four distinct message shapes at STRICT granularity, with deliberately distinct counts so
    // frequent/rare ordering and pagination are all unambiguous: UI/render=7, Net/connect=5,
    // Net/disconnect=3, UI/click=1.
    private fun openMixedShapesTab(state: AppState): String {
        val entries = buildList {
            repeat(7) { add(entry(size + 1, "UI", "render frame ${it + 1}")) }
            repeat(5) { add(entry(size + 1, "Net", "connect to 10.0.0.${it + 1}")) }
            repeat(3) { add(entry(size + 1, "Net", "disconnect")) }
            add(entry(size + 1, "UI", "click button ok"))
        }
        state.tabs = listOf(mkTab("t1", "mixed.log", entries))
        return state.tabs.single().id
    }

    private fun operationsFor(state: AppState) = IndagiumToolOperations(state)

    // ── Never mistaken for "no repeated messages" ───────────────────────────────────────────

    @Test
    fun aFreshTabWithNoCompositionComputedYetStillReturnsRealTemplatesNotAnEmptyPlaceholder() {
        val state = AppState()
        val tabId = openMixedShapesTab(state)
        assertEquals(MessageCompositionState.NotComputed, state.tabs.single().messageComposition, "arrange: nothing computed yet")

        val result = operationsFor(state).toolGateway.execute("get_log_composition", mapOf("tabId" to tabId)) as Map<*, *>

        assertEquals(false, result["cacheHit"], "first call must do the scan itself, not report a cached miss")
        val templates = result["templates"] as List<*>
        assertTrue(templates.isNotEmpty(), "an un-computed view must be scanned on demand, never reported as if it had no repeats")
        assertEquals(4, result["shapeCount"])
        assertEquals(16, result["totalEntries"])
    }

    @Test
    fun theInlineScanIsCachedIntoAppStateSoASecondCallForTheSameFilterIsAHit() {
        val state = AppState()
        val tabId = openMixedShapesTab(state)
        val operations = operationsFor(state)

        operations.toolGateway.execute("get_log_composition", mapOf("tabId" to tabId))
        val cached = state.tabs.single().messageComposition
        assertTrue(cached is MessageCompositionState.Computed, "the fresh scan must be written back so the panel and a later call both see it")

        val second = operations.toolGateway.execute("get_log_composition", mapOf("tabId" to tabId)) as Map<*, *>
        assertEquals(true, second["cacheHit"])
    }

    // ── Ranking and pagination ───────────────────────────────────────────────────────────────

    @Test
    fun frequentOrderRanksHighestCountFirstByDefault() {
        val state = AppState()
        val tabId = openMixedShapesTab(state)

        val result = operationsFor(state).toolGateway.execute("get_log_composition", mapOf("tabId" to tabId)) as Map<*, *>

        val counts = (result["templates"] as List<*>).map { (it as Map<*, *>)["count"] }
        assertEquals(listOf(7, 5, 3, 1), counts, "default order is frequent: highest count first")
        assertEquals("frequent", result["order"])
    }

    @Test
    fun rareOrderSurfacesTheLeastFrequentShapeFirst() {
        val state = AppState()
        val tabId = openMixedShapesTab(state)

        val result = operationsFor(state).toolGateway.execute(
            "get_log_composition", mapOf("tabId" to tabId, "order" to "rare"),
        ) as Map<*, *>

        val counts = (result["templates"] as List<*>).map { (it as Map<*, *>)["count"] }
        assertEquals(listOf(1, 3, 5, 7), counts)
    }

    @Test
    fun paginationClampsOffsetAndLimitAndEchoesTheEffectiveValuesAndTotal() {
        val state = AppState()
        val tabId = openMixedShapesTab(state)
        val operations = operationsFor(state)

        val page = operations.toolGateway.execute(
            "get_log_composition", mapOf("tabId" to tabId, "limit" to 2),
        ) as Map<*, *>
        assertEquals(0, page["offset"])
        assertEquals(2, page["limit"])
        assertEquals(4, page["totalCount"])
        assertEquals(2, (page["templates"] as List<*>).size)

        val offBeyondEnd = operations.toolGateway.execute(
            "get_log_composition", mapOf("tabId" to tabId, "offset" to 999),
        ) as Map<*, *>
        assertEquals(4, offBeyondEnd["offset"], "offset clamps to the total rather than erroring")
        assertTrue((offBeyondEnd["templates"] as List<*>).isEmpty())

        val hugeLimit = operations.toolGateway.execute(
            "get_log_composition", mapOf("tabId" to tabId, "limit" to 100_000),
        ) as Map<*, *>
        assertEquals(500, hugeLimit["limit"], "limit is hard-capped at 500")

        val zeroLimit = operations.toolGateway.execute(
            "get_log_composition", mapOf("tabId" to tabId, "limit" to 0),
        ) as Map<*, *>
        assertEquals(1, zeroLimit["limit"], "limit clamps up to at least 1 rather than returning nothing")
    }

    // ── Row shape is compact ─────────────────────────────────────────────────────────────────

    @Test
    fun eachRowExposesOnlyTagTemplateCountAndFirstLineId() {
        val state = AppState()
        val tabId = openMixedShapesTab(state)

        val result = operationsFor(state).toolGateway.execute(
            "get_log_composition", mapOf("tabId" to tabId, "limit" to 1),
        ) as Map<*, *>

        val row = (result["templates"] as List<*>).single() as Map<*, *>
        assertEquals(setOf("tag", "template", "count", "firstLineId"), row.keys)
        assertEquals("UI", row["tag"])
        assertNotNull(row["firstLineId"])
    }

    // ── tag / search narrowing ───────────────────────────────────────────────────────────────

    @Test
    fun tagRestrictsToExactlyOneTag() {
        val state = AppState()
        val tabId = openMixedShapesTab(state)

        val result = operationsFor(state).toolGateway.execute(
            "get_log_composition", mapOf("tabId" to tabId, "tag" to "Net"),
        ) as Map<*, *>

        val tags = (result["templates"] as List<*>).map { (it as Map<*, *>)["tag"] }.toSet()
        assertEquals(setOf("Net"), tags)
        assertEquals(2, result["totalCount"])
    }

    @Test
    fun searchMatchesCaseInsensitivelyOverTagAndTemplate() {
        val state = AppState()
        val tabId = openMixedShapesTab(state)

        val result = operationsFor(state).toolGateway.execute(
            "get_log_composition", mapOf("tabId" to tabId, "search" to "CONNECT"),
        ) as Map<*, *>

        val templates = (result["templates"] as List<*>).map { (it as Map<*, *>)["template"] as String }
        assertEquals(2, templates.size, "matches both 'connect to ...' and 'disconnect'")
        assertTrue(templates.all { it.contains("connect", ignoreCase = true) })
    }

    // ── Granularity folding ──────────────────────────────────────────────────────────────────

    @Test
    fun granularityFoldsSeparateStrictShapesIntoOneAtNormalAndUnknownValueIsADataErrorNotAThrow() {
        val state = AppState()
        val entries = listOf(
            entry(1, "UI", "Card stack expanded: stackId=stack_home"),
            entry(2, "UI", "Card stack expanded: stackId=stack_home"),
            entry(3, "UI", "Card stack expanded: stackId=stack_work"),
            entry(4, "UI", "Card stack expanded: stackId=stack_work"),
        )
        state.tabs = listOf(mkTab("t1", "granularity.log", entries))
        val tabId = state.tabs.single().id
        val operations = operationsFor(state)

        val strict = operations.toolGateway.execute(
            "get_log_composition", mapOf("tabId" to tabId, "granularity" to "strict"),
        ) as Map<*, *>
        assertEquals(2, strict["shapeCount"], "strict keeps stack_home and stack_work distinct")

        val normal = operations.toolGateway.execute(
            "get_log_composition", mapOf("tabId" to tabId, "granularity" to "normal"),
        ) as Map<*, *>
        assertEquals(1, normal["shapeCount"], "normal truncates at the 2nd separator, merging both shapes")
        assertEquals(4, (normal["templates"] as List<*>).single().let { (it as Map<*, *>)["count"] })

        val bogus = operations.toolGateway.execute(
            "get_log_composition", mapOf("tabId" to tabId, "granularity" to "extreme"),
        ) as Map<*, *>
        assertNotNull(bogus["error"], "an unknown granularity must be a data error, not a thrown exception")
    }

    // ── The composition reflects the tab's current filter ───────────────────────────────────

    @Test
    fun theCompositionReflectsTheActiveFilterAndDropsShapesTheFilterExcludes() {
        val state = AppState()
        val tabId = openMixedShapesTab(state)
        val operations = operationsFor(state)

        val unfiltered = operations.toolGateway.execute("get_log_composition", mapOf("tabId" to tabId)) as Map<*, *>
        assertEquals(4, unfiltered["shapeCount"])

        state.upFlt(tabId) { it.copy(activeTags = setOf("Net")) }
        val filtered = operations.toolGateway.execute("get_log_composition", mapOf("tabId" to tabId)) as Map<*, *>

        assertEquals(false, filtered["cacheHit"], "a filter change must invalidate the cached view, not reuse the unfiltered scan")
        val tags = (filtered["templates"] as List<*>).map { (it as Map<*, *>)["tag"] }.toSet()
        assertEquals(setOf("Net"), tags, "the UI-tagged shapes are excluded by the active filter, not just hidden client-side")
        assertEquals(2, filtered["shapeCount"])
    }

    // ── overflowed is surfaced, not silently dropped ────────────────────────────────────────

    @Test
    fun overflowedIsSurfacedFromTheUnderlyingHistogram() {
        val state = AppState()
        val tabId = openMixedShapesTab(state)
        val tab = state.tabs.single()
        val overflowedHistogram = MessageTemplateHistogram(
            templates = listOf(MessageTemplate("UI", "render frame <n>", 7, 1, 7, 17, 17)),
            granularity = TemplateGranularity.STRICT,
            totalEntries = 16,
            countedEntries = 16,
            overflowed = true,
        )
        state.upTab(tabId) {
            it.copy(messageComposition = MessageCompositionState.Computed(overflowedHistogram, tab.filter.viewDefiningKey()))
        }

        val result = IndagiumToolOperations(state).toolGateway.execute("get_log_composition", mapOf("tabId" to tabId)) as Map<*, *>

        assertEquals(true, result["cacheHit"], "arrange: the seeded histogram must be reused, not rescanned")
        assertTrue(result["overflowed"] as Boolean, "overflowed must reach the caller so the rare lens is not read as complete")
    }

    // ── Unknown tab ──────────────────────────────────────────────────────────────────────────

    @Test
    fun unknownTabIsADataErrorNotAnException() {
        val state = AppState()

        val result = operationsFor(state).toolGateway.execute("get_log_composition", mapOf("tabId" to "missing")) as Map<*, *>

        assertFalse(result["error"].toString().isBlank())
    }
}
