package com.indagium

import com.indagium.model.AppSettings
import com.indagium.model.DEFAULT_HOME_RECENT_GRID_COLUMNS
import com.indagium.ui.settingsFromJson
import com.indagium.ui.settingsFromToken
import com.indagium.ui.settingsJson
import java.util.Base64
import kotlin.test.Test
import kotlin.test.assertEquals

// Shaped after HomeRecentsLayoutSettingCodecTest: homeRecentGridColumns is JSON-only, appended last
// per CLAUDE.md's rule — settingsFromToken must never gain this field, so a legacy positional token
// simply defaults it, same as `{}`.
class HomeRecentGridColumnsSettingCodecTest {
    @Test
    fun homeRecentGridColumnsRoundTripsThroughKeyedSettingsJson() {
        for (columns in 3..8) {
            val decoded = settingsFromJson(AppSettings(homeRecentGridColumns = columns).settingsJson())!!
            assertEquals(columns, decoded.homeRecentGridColumns)
        }
    }

    @Test
    fun absentJsonAndLegacyTokenDefaultHomeRecentGridColumnsToFour() {
        val legacyToken = listOf("LIGHT", "12", "true", "", "5").joinToString("|") { value ->
            if (value.isEmpty()) {
                "~"
            } else {
                Base64.getUrlEncoder().withoutPadding().encodeToString(value.toByteArray())
            }
        }

        val jsonDefaults = settingsFromJson("{}")!!
        val legacyDefaults = settingsFromToken(legacyToken)!!

        listOf(jsonDefaults, legacyDefaults).forEach { settings ->
            assertEquals(DEFAULT_HOME_RECENT_GRID_COLUMNS, settings.homeRecentGridColumns)
        }
    }

    @Test
    fun outOfRangeStoredValueClampsOnDecode() {
        val tooLow = settingsFromJson("""{"homeRecentGridColumns":1}""")!!
        val tooHigh = settingsFromJson("""{"homeRecentGridColumns":99}""")!!

        assertEquals(3, tooLow.homeRecentGridColumns)
        assertEquals(8, tooHigh.homeRecentGridColumns)
    }
}
