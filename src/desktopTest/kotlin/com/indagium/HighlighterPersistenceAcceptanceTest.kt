package com.indagium

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import com.indagium.model.AppSettings
import com.indagium.model.FilterMode
import com.indagium.model.Highlighter
import com.indagium.model.LogLevel
import com.indagium.model.SavedFilter
import com.indagium.ui.WorkspaceProfile
import com.indagium.ui.decodeFilterImport
import com.indagium.ui.decodeWorkspaceProfileFile
import com.indagium.ui.encodeWorkspaceProfileFile
import com.indagium.ui.exportFiltersList
import com.indagium.ui.newHighlightersFor
import com.indagium.ui.resolvedInterfaceFontFamily
import com.indagium.ui.resolvedLogFontFamily
import com.indagium.ui.settingsFromJson
import com.indagium.ui.settingsJson
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

class HighlighterPersistenceAcceptanceTest {
    private val styled = Highlighter(
        id = "styled", pattern = "green", regex = false, color = Color(0x40112233), on = true,
        wholeLine = true, textColor = Color(0x8044CC66), backgroundEnabled = false,
        fontFamily = "Missing font retained in storage", bold = false, italic = true,
    )

    @Test
    fun savedFilterJsonExportAndImportPreserveAllStylesAndArgbColors() {
        val imported = styled.copy(id = "imported", kloggStyle = true, captureGroupsOnly = true, colorVariance = 35)
        val filter = SavedFilter(
            id = "filter", name = "Styled rules", levels = LogLevel.entries.toSet(), activeTags = emptySet(),
            kwText = "", kwRegex = false, mode = FilterMode.KEYWORD, excludeTags = emptySet(),
            excludeKw = "", excludeKwRegex = false, highlighters = listOf(styled, imported), seqOn = false,
        )
        val restored = decodeFilterImport("styles.json", exportFiltersList(listOf(filter))).getOrThrow().filters.single()
        assertEquals(filter, restored)
    }

    @Test
    fun importDeduplicationDistinguishesEachStyleAndCompatibilityFlag() {
        val variants = listOf(
            styled.copy(kloggStyle = true),
            styled.copy(backgroundEnabled = true),
            styled.copy(fontFamily = "Another font"),
            styled.copy(bold = true),
            styled.copy(italic = false),
            styled.copy(textColor = Color.Green),
        )
        val result = newHighlightersFor(listOf(styled), listOf(styled.copy(id = "duplicate")) + variants + variants)
        assertEquals(variants, result.mapIndexed { index, highlighter -> highlighter.copy(id = variants[index].id) })
    }

    @Test
    fun settingsRoundTripCustomPaletteAndSeparateFontsWithMissingFontFallback() {
        val settings = AppSettings(
            fontMono = true, fontSize = 15, interfaceScalePercent = 125,
            interfaceFontFamily = "Missing interface font", logFontFamily = "Missing log font",
            highlighterCustomColors = listOf("#8044CC66", "#FF112233"), highlighterPaletteColumns = 10,
        )
        val restored = assertNotNull(settingsFromJson(settings.settingsJson()))
        assertEquals(settings, restored)
        assertEquals(FontFamily.Default, restored.resolvedInterfaceFontFamily())
        assertEquals(FontFamily.Monospace, restored.resolvedLogFontFamily())
    }

    @Test
    fun olderJsonRetainsSizeScaleAndProportionalLogDefault() {
        val restored = assertNotNull(settingsFromJson("""{"fontMono":false,"fontSize":17,"interfaceScalePercent":130}"""))
        assertEquals(17, restored.fontSize)
        assertEquals(130, restored.interfaceScalePercent)
        assertNull(restored.interfaceFontFamily)
        assertNull(restored.logFontFamily)
        assertEquals(FontFamily.Default, restored.resolvedInterfaceFontFamily())
        assertEquals(FontFamily.Default, restored.resolvedLogFontFamily())
        assertEquals(emptyList(), restored.highlighterCustomColors)
        assertEquals(5, restored.highlighterPaletteColumns)
    }

    @Test
    fun customProfileExportKeepsSeparateFontNamesAndFallbackChoice() {
        val spec = WorkspaceProfile.CLASSIC.spec.copy(
            fontMono = false, interfaceFontFamily = "Missing interface font", logFontFamily = "Missing log font",
        )
        assertEquals(spec, decodeWorkspaceProfileFile(encodeWorkspaceProfileFile("Typography", spec)).getOrThrow().spec)
    }
}
