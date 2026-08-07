package com.indagium

import com.indagium.model.AppSettings
import com.indagium.ui.settingsFromJson
import com.indagium.ui.settingsFromToken
import com.indagium.ui.settingsJson
import java.util.Base64
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class TailFollowSettingCodecTest {
    @Test
    fun autoScrollWhileTailingRoundTripsBothValuesThroughKeyedSettingsJson() {
        val off = settingsFromJson(AppSettings(autoScrollWhileTailing = false).settingsJson())!!
        val on = settingsFromJson(AppSettings(autoScrollWhileTailing = true).settingsJson())!!

        // Both directions, not just the off case: a field that only ever encodes its non-default
        // value would still pass a one-sided assertion while silently dropping the other.
        assertFalse(off.autoScrollWhileTailing)
        assertTrue(on.autoScrollWhileTailing)
    }

    // Unlike every other recently added boolean, this one defaults ON — so an autosave written
    // before the field existed must come back following the tail, not with it silently disabled.
    // Both decoders are checked: the keyed JSON form new settings live in, and the frozen legacy
    // positional token, which can never carry this field at all.
    @Test
    fun absentJsonAndLegacySettingsDefaultAutoScrollWhileTailingOn() {
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
            assertTrue(settings.autoScrollWhileTailing)
        }
    }
}
