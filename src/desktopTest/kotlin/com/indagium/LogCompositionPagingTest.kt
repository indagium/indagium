package com.indagium

import com.indagium.model.MessageTemplate
import com.indagium.ui.LogCompositionPageToken
import com.indagium.ui.logCompositionClampPage
import com.indagium.ui.logCompositionPageCount
import com.indagium.ui.logCompositionPageItems
import com.indagium.ui.logCompositionPageWindow
import com.indagium.ui.logCompositionSearchMatches
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Stage 2c: the Log composition panel section's paging and search, extracted as plain functions
 * (ui/FilterPanel.kt) precisely so this arithmetic is testable without a Compose harness — this
 * project has none. The 8,620-distinct-shapes real-log report that motivated this feature is
 * modeled directly: a page size of 10 puts that at 863 pages, which is what the page-window test
 * below checks stays a fixed-width control rather than one button per page.
 */
class LogCompositionPagingTest {
    private fun template(tag: String, template: String, count: Int = 1, id: Int = 1) =
        MessageTemplate(tag, template, count, id, id, template.length, template.length)

    // ── Page boundaries ─────────────────────────────────────────────────────────────────────

    @Test
    fun pageItemsSliceExactlyPageSizeItemsPerPageAndTheLastPageIsPartial() {
        val items = (1..25).map { template("Tag", "line $it", id = it) }

        val page0 = logCompositionPageItems(items, page = 0, pageSize = 10)
        val page1 = logCompositionPageItems(items, page = 1, pageSize = 10)
        val page2 = logCompositionPageItems(items, page = 2, pageSize = 10)

        assertEquals((1..10).toList(), page0.map { it.firstEntryId })
        assertEquals((11..20).toList(), page1.map { it.firstEntryId })
        assertEquals((21..25).toList(), page2.map { it.firstEntryId }, "the last page is a partial page of 5, not padded to 10")
        assertEquals(3, logCompositionPageCount(items.size, pageSize = 10))
    }

    @Test
    fun pageCountIsZeroForAnEmptyListRatherThanOne() {
        assertEquals(0, logCompositionPageCount(0, pageSize = 10))
        assertTrue(logCompositionPageItems(emptyList<MessageTemplate>(), page = 0, pageSize = 10).isEmpty())
    }

    @Test
    fun aPageIndexPastTheEndAfterTheListShrinksClampsToTheLastRealPageInsteadOfReturningNothing() {
        // Models: user was on page 5 of a large histogram, then a filter change (or a search) cut
        // the list down to 3 items — a page index into a list that no longer exists must not just
        // silently render empty.
        val shrunk = (1..3).map { template("Tag", "line $it", id = it) }

        val items = logCompositionPageItems(shrunk, page = 5, pageSize = 10)

        assertEquals(shrunk.map { it.firstEntryId }, items.map { it.firstEntryId })
        assertEquals(0, logCompositionClampPage(page = 5, pageCount = logCompositionPageCount(shrunk.size, pageSize = 10)))
    }

    // ── Search resets to page 1 ────────────────────────────────────────────────────────────
    // The reset itself is a UI-state write (ui/FilterPanel.kt sets fpState.logCompositionPage = 0
    // in the search field's onValue and in a LaunchedEffect keyed on tab/composition — no branching
    // worth a pure function of its own). What IS worth testing here is the arithmetic it depends
    // on: after a search narrows the ranked list, page 0 of THAT narrowed list is what must render.

    @Test
    fun searchingThenRenderingAtPageOneShowsTheFirstPageOfTheNarrowedResultsNotTheOriginalList() {
        val items = (1..25).map { template("Tag", "needle $it", id = it) } +
            (26..30).map { template("Other", "unrelated $it", id = it) }

        val matched = logCompositionSearchMatches(items, "needle")
        val firstPage = logCompositionPageItems(matched, page = 0, pageSize = 10)

        assertEquals(25, matched.size)
        assertEquals((1..10).toList(), firstPage.map { it.firstEntryId })
    }

    // ── Search matching ─────────────────────────────────────────────────────────────────────

    @Test
    fun searchMatchesAgainstBothTagAndTemplateCaseInsensitively() {
        val items = listOf(
            template("NetworkManager", "Connection lost", id = 1),
            template("Gc", "GC freed 4823 objects", id = 2),
            template("Audio", "Volume changed", id = 3),
        )

        val byTag = logCompositionSearchMatches(items, "network")
        val byTemplate = logCompositionSearchMatches(items, "FREED")
        val noMatch = logCompositionSearchMatches(items, "bluetooth")

        assertEquals(listOf(1), byTag.map { it.firstEntryId })
        assertEquals(listOf(2), byTemplate.map { it.firstEntryId })
        assertTrue(noMatch.isEmpty())
    }

    @Test
    fun aBlankSearchReturnsEveryTemplateUnfiltered() {
        val items = listOf(template("A", "one"), template("B", "two"))
        assertEquals(items, logCompositionSearchMatches(items, "   "))
    }

    // ── Page window stays a fixed width at 863 pages ────────────────────────────────────────

    @Test
    fun thePageWindowAt863PagesStaysBoundedAndAlwaysIncludesTheCurrentPage() {
        val pageCount = 863
        for (current in listOf(0, 1, 429, 861, 862)) {
            val window = logCompositionPageWindow(current, pageCount)
            val pageIndices = window.filterIsInstance<LogCompositionPageToken.Page>().map { it.index }

            assertTrue(current in pageIndices, "window for current=$current must include the current page")
            // First and last page, up to 5 window pages (radius 2), up to 2 ellipses: 9 tokens max,
            // regardless of pageCount being 9 or 863 — never one control per page.
            assertTrue(window.size <= 9, "expected a bounded-width window, got ${window.size} tokens for current=$current")
        }
    }

    @Test
    fun thePageWindowAlwaysIncludesTheFirstAndLastPage() {
        val window = logCompositionPageWindow(currentPage = 430, pageCount = 863)
        val pageIndices = window.filterIsInstance<LogCompositionPageToken.Page>().map { it.index }

        assertEquals(0, pageIndices.first())
        assertEquals(862, pageIndices.last())
    }

    @Test
    fun aSmallPageCountRendersEveryPageWithNoEllipsis() {
        val window = logCompositionPageWindow(currentPage = 2, pageCount = 5)

        assertEquals((0..4).toList(), window.filterIsInstance<LogCompositionPageToken.Page>().map { it.index })
        assertTrue(window.none { it is LogCompositionPageToken.Ellipsis })
    }
}
