package com.indagium

import com.indagium.model.AppSettings
import com.indagium.model.ThemePreset
import com.indagium.ui.settingsFromJson
import com.indagium.ui.settingsJson
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/** WP1's `AppSettings.diagramDefaultTheme` — persisted in keyed JSON only (never the frozen
 *  positional `settingsFromToken` decoder), null meaning "follow the app theme" rather than
 *  pinning every new diagram to one preset. */
class Seq3DefaultDiagramThemeSettingsTest {
    @Test
    fun defaultIsNullAndFollowsTheAppTheme() {
        assertNull(AppSettings().diagramDefaultTheme)
    }

    @Test
    fun aChosenPresetRoundTripsThroughSettingsJson() {
        val restored = settingsFromJson(AppSettings(diagramDefaultTheme = ThemePreset.DRACULA).settingsJson())
        assertEquals(ThemePreset.DRACULA, restored?.diagramDefaultTheme)
    }

    @Test
    fun clearingBackToNullRoundTrips() {
        val withTheme = AppSettings(diagramDefaultTheme = ThemePreset.DRACULA).settingsJson()
        val cleared = settingsFromJson(withTheme)?.copy(diagramDefaultTheme = null)?.settingsJson()
        assertNull(cleared?.let(::settingsFromJson)?.diagramDefaultTheme)
    }

    @Test
    fun missingOrARenamedPresetDegradesToNullRatherThanThrowing() {
        assertNull(settingsFromJson("{}")?.diagramDefaultTheme)
        assertNull(settingsFromJson("{\"diagramDefaultTheme\":\"NOT_A_REAL_PRESET\"}")?.diagramDefaultTheme)
    }
}
