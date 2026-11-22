package com.indagium

import com.indagium.model.BUILTIN_LOG_FORMAT_PROFILES
import com.indagium.model.Filter
import com.indagium.model.FilterMode
import com.indagium.model.LogEntry
import com.indagium.model.LogFormatEngine
import com.indagium.model.LogLevel
import com.indagium.model.SavedFilter
import com.indagium.model.UNKNOWN_SEVERITY_METADATA
import com.indagium.model.deriveLogCapabilities
import com.indagium.ui.AppState
import com.indagium.ui.filterForPublishedRows
import com.indagium.ui.mkTab
import java.io.File
import kotlin.io.path.createTempDirectory
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

/**
 * Clear filters is the user's explicit reset: when the visible filter was already trimmed to what Clear sets (rows without severity
 * trimmed an Errors-only preset), nothing about it changes, yet the criteria a load set aside must not survive to come back when rows
 * with severities arrive.
 */
class ClearFilterHeldCriteriaTest {
    private val plainText = BUILTIN_LOG_FORMAT_PROFILES.first { it.engine == LogFormatEngine.PLAIN_TEXT }

    private fun row(id: Int, severity: Boolean) = LogEntry(
        id, "", LogLevel.E, "", "message $id",
        recordMetadata = if (severity) null else UNKNOWN_SEVERITY_METADATA,
    )

    private fun newState(directory: File): AppState = AppState(
        autosaveFile = File(directory, "state.cache"),
        restoreOnCreate = false,
        autoExportNotes = false,
        notesDir = File(directory, "notes"),
        archiveCacheDir = File(directory, "archive-cache"),
    )

    private val errorsOnly = Filter(levels = setOf(LogLevel.E))

    @Test
    fun clearingAFilterThatWasAlreadyTrimmedDropsTheHeldCriteriaAndTheNotice() {
        val directory = createTempDirectory("clear-filter-held").toFile()
        try {
            val app = newState(directory)
            app.tabs = listOf(
                mkTab("t", "app.log", listOf(row(1, severity = false)), profile = plainText)
                    .copy(filter = Filter(), heldFilterCriteria = errorsOnly, filterNormalizationNotice = "Level filter set aside"),
            )

            app.clearFilter("t")

            val tab = assertNotNull(app.tab("t"))
            assertEquals(Filter(), tab.filter)
            assertNull(tab.heldFilterCriteria)
            assertNull(tab.filterNormalizationNotice)

            // Rows with severities arrive later: nothing comes back.
            val published = filterForPublishedRows(
                tab.filter,
                tab.heldFilterCriteria,
                false,
                deriveLogCapabilities(plainText, listOf(row(1, severity = true))),
                plainText,
            )
            assertEquals(Filter(), published.filter)
            assertNull(published.heldFilterCriteria)
            assertNull(published.notice)
            app.close()
        } finally {
            directory.deleteRecursively()
        }
    }

    @Test
    fun confirmingTheClearDialogDoesTheSame() {
        val directory = createTempDirectory("clear-filter-held-confirm").toFile()
        try {
            val app = newState(directory)
            app.tabs = listOf(
                mkTab("t", "app.log", listOf(row(1, severity = false)), profile = plainText)
                    .copy(filter = Filter(), heldFilterCriteria = errorsOnly, filterNormalizationNotice = "Level filter set aside"),
            )

            app.requestClearFilter("t")
            app.confirmClearFilter()

            assertNull(app.tab("t")?.heldFilterCriteria)
            assertNull(app.tab("t")?.filterNormalizationNotice)
            app.close()
        } finally {
            directory.deleteRecursively()
        }
    }

    @Test
    fun reloadingAnUnchangedDraftTakesTheHeldCriteriaAway() {
        val directory = createTempDirectory("clear-filter-held-draft").toFile()
        try {
            val app = newState(directory)
            app.tabs = listOf(mkTab("t", "app.log", listOf(row(1, severity = true)), profile = plainText))
            val preset = SavedFilter(
                id = "kw", name = "Keyword", levels = LogLevel.entries.toSet(), activeTags = emptySet(), kwText = "boom",
                kwRegex = false, mode = FilterMode.KEYWORD, excludeTags = emptySet(), excludeKw = "", excludeKwRegex = false,
                highlighters = emptyList(), seqOn = true,
            )
            app.loadFilter("t", preset)
            app.upFlt("t") { it.copy(kwText = "boomer") }
            val draft = assertNotNull(app.filterDraftForTab("t"), "fixture: editing a loaded preset leaves a draft")
            app.upTab("t") { it.copy(heldFilterCriteria = errorsOnly, filterNormalizationNotice = "Level filter set aside") }

            app.requestLoadFilter("t", draft)

            assertNull(app.tab("t")?.heldFilterCriteria)
            assertNull(app.tab("t")?.filterNormalizationNotice)
            app.close()
        } finally {
            directory.deleteRecursively()
        }
    }
}
