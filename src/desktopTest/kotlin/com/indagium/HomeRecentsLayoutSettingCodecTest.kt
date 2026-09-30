package com.indagium

import com.indagium.model.AppSettings
import com.indagium.model.HomeRecentsLayout
import com.indagium.ui.settingsFromJson
import com.indagium.ui.settingsFromToken
import com.indagium.ui.settingsJson
import java.util.Base64
import kotlin.test.Test
import kotlin.test.assertEquals

// Shaped after TailFollowSettingCodecTest: homeRecentsLayout is JSON-only (see AppSettings' own
// doc on it and CLAUDE.md's append-last/frozen-legacy-decoder rule) — settingsFromToken must
// never gain this field, so a legacy positional token simply defaults it, same as `{}`.
class HomeRecentsLayoutSettingCodecTest {
    @Test
    fun homeRecentsLayoutRoundTripsBothValuesThroughKeyedSettingsJson() {
        val grid = settingsFromJson(AppSettings(homeRecentsLayout = HomeRecentsLayout.GRID).settingsJson())!!
        val list = settingsFromJson(AppSettings(homeRecentsLayout = HomeRecentsLayout.LIST).settingsJson())!!

        assertEquals(HomeRecentsLayout.GRID, grid.homeRecentsLayout)
        assertEquals(HomeRecentsLayout.LIST, list.homeRecentsLayout)
    }

    @Test
    fun absentJsonAndLegacyTokenDefaultHomeRecentsLayoutToGrid() {
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
            assertEquals(HomeRecentsLayout.GRID, settings.homeRecentsLayout)
        }
    }
}
