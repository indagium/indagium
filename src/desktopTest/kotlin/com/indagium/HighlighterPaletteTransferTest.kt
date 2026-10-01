package com.indagium

import androidx.compose.ui.graphics.Color
import com.indagium.ui.AppState
import com.indagium.ui.ImportFilterAction
import com.indagium.ui.ImportReviewMode
import com.indagium.ui.decodeFilterLibrary
import java.io.File
import kotlin.io.path.createTempDirectory
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class HighlighterPaletteTransferTest {
    private val states = mutableListOf<AppState>()
    private val temporaryDirectories = mutableListOf<File>()

    @AfterTest
    fun tearDown() {
        states.forEach { runCatching { it.close() } }
        temporaryDirectories.forEach { it.deleteRecursively() }
    }

    @Test
    fun exportStagesPaletteWithoutMutationUntilAFilterIsConfirmed() {
        val source = state("palette-source")
        source.addTab()
        val sourceTab = source.tabs.single().id
        source.saveFilter(sourceTab, "release")
        source.updateSettings {
            it.copy(highlighterCustomColors = listOf("#112233", "#80112233", "#AABBCCDD"))
        }

        val target = state("palette-target")
        target.updateSettings {
            it.copy(
                highlighterCustomColors = listOf("#102030", "#ff112233"),
                highlighterPaletteColumns = 10,
            )
        }
        val existingColors = target.settings.highlighterCustomColors
        val exported = source.exportFilters()
        assertTrue(exported.contains("\"highlighterCustomColors\""))

        target.beginImportFilters(exported, "release.json")
        val pending = assertNotNull(target.pendingImportReview)
        assertEquals(listOf("#FF112233", "#80112233", "#AABBCCDD"), pending.customColors)
        assertEquals(existingColors, target.settings.highlighterCustomColors, "staging must not mutate settings")

        target.cancelImportFilters()
        assertEquals(existingColors, target.settings.highlighterCustomColors, "cancel must discard staged colors")

        target.beginImportFilters(exported, "release.json")
        val row = assertNotNull(target.pendingImportReview).rows.single()
        target.setImportFilterAction(row.rowId, ImportFilterAction.SKIP)
        target.confirmImportFilters()
        assertEquals(existingColors, target.settings.highlighterCustomColors, "a confirmation with no selected rows must not import colors")

        target.beginImportFilters(exported, "release.json")
        target.confirmImportFilters()
        assertEquals(
            listOf("#FF102030", "#FF112233", "#80112233", "#AABBCCDD"),
            target.settings.highlighterCustomColors,
        )
        assertEquals(10, target.settings.highlighterPaletteColumns, "import must preserve the local palette presentation")
        assertEquals("release", target.savedFilters.single().name)
    }

    @Test
    fun multiFileAddToCurrentImportsColorsOnlyAfterASelection() {
        val first = sourceWithHighlighter("first", "#112233")
        val second = sourceWithHighlighter("second", "#445566")
        val dir = createTempDirectory("highlighter-palette-transfer").toFile()
        temporaryDirectories += dir
        val firstFile = File(dir, "first.json").apply { writeText(first.exportFilters()) }
        val secondFile = File(dir, "second.json").apply { writeText(second.exportFilters()) }

        val target = state("palette-multi-target")
        target.addTab()
        target.importFiltersFromFiles(listOf(firstFile, secondFile))
        target.setImportReviewMode(ImportReviewMode.ADD_TO_CURRENT)
        val review = assertNotNull(target.pendingImportReview)
        assertEquals(2, review.rows.size)
        assertEquals(listOf("#FF112233", "#FF445566"), review.customColors)
        target.setImportRowsChecked(review.highlightRowIds, checked = false)
        target.confirmImportFilters()

        assertTrue(target.settings.highlighterCustomColors.isEmpty())
        assertTrue(target.tabs.single().filter.highlighters.isEmpty())

        target.importFiltersFromFiles(listOf(firstFile, secondFile))
        target.setImportReviewMode(ImportReviewMode.ADD_TO_CURRENT)
        target.confirmImportFilters()

        assertEquals(listOf("#FF112233", "#FF445566"), target.settings.highlighterCustomColors)
        assertEquals(setOf("first", "second"), target.tabs.single().filter.highlighters.map { it.pattern }.toSet())
    }

    @Test
    fun directImportMergesPaletteAndOlderLibrariesHaveNoPaletteMetadata() {
        val source = sourceWithHighlighter("direct", "#C0112233")
        val target = state("palette-direct-target")
        target.importFilters(source.exportFilters())
        assertEquals(listOf("#C0112233"), target.settings.highlighterCustomColors)

        val oldObject = """{"format":"indagium-saved-filter-library","version":2,"folders":[],"filters":[]}"""
        val oldArray = "[]"
        assertTrue(decodeFilterLibrary(oldObject).getOrThrow().customColors.isEmpty())
        assertTrue(decodeFilterLibrary(oldArray).getOrThrow().customColors.isEmpty())

        val mixed = """{"filters":[],"highlighterCustomColors":["#112233","#FF112233","not-a-color",42,{"x":1}]}"""
        assertEquals(listOf("#FF112233"), decodeFilterLibrary(mixed).getOrThrow().customColors)
    }

    private fun sourceWithHighlighter(name: String, customColor: String): AppState = state("source-$name").also { source ->
        source.addTab()
        val tabId = source.tabs.single().id
        source.addHl(tabId, name, rx = false, color = Color.Red)
        source.saveFilter(tabId, name)
        source.updateSettings { it.copy(highlighterCustomColors = listOf(customColor)) }
    }

    private fun state(name: String): AppState {
        val directory = createTempDirectory(name).toFile()
        temporaryDirectories += directory
        return AppState(File(directory, "state.cache")).also(states::add)
    }
}
