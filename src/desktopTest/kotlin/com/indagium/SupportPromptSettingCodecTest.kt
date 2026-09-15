package com.indagium

import com.indagium.model.AppSettings
import com.indagium.ui.settingsFromJson
import com.indagium.ui.settingsJson
import kotlin.test.Test
import kotlin.test.assertEquals

class SupportPromptSettingCodecTest {
    @Test
    fun lastSupportPromptAtRoundTripsThroughKeyedSettingsJson() {
        val epochMs = 1_726_000_000_000L
        val decoded = settingsFromJson(AppSettings(lastSupportPromptAt = epochMs).settingsJson())!!

        assertEquals(epochMs, decoded.lastSupportPromptAt)
    }

    // JSON form only (see AppSettings.lastSupportPromptAt's own doc) — a blob predating this field,
    // or any other key missing from it, must default to 0 (never prompted), same as a fresh
    // AppSettings().
    @Test
    fun aMissingKeyDefaultsToZero() {
        assertEquals(0L, settingsFromJson("{}")!!.lastSupportPromptAt)
        assertEquals(0L, AppSettings().lastSupportPromptAt)
    }
}
