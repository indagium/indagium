package com.indagium

import com.indagium.model.AppSettings
import com.indagium.ui.settingsFromJson
import com.indagium.ui.settingsFromToken
import com.indagium.ui.settingsJson
import java.util.Base64
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class CopyMetadataSettingsCodecTest {
    @Test
    fun copyMetadataOptionsRoundTripThroughKeyedSettingsJson() {
        val json = AppSettings(
            copyPidTid = true,
            copyPidAsName = true,
            copyRowNumber = true,
            copyTimeDelta = true,
        ).settingsJson()

        val restored = settingsFromJson(json)!!

        assertTrue(restored.copyPidTid)
        assertTrue(restored.copyPidAsName)
        assertTrue(restored.copyRowNumber)
        assertTrue(restored.copyTimeDelta)
    }

    @Test
    fun absentJsonAndLegacySettingsDefaultCopyMetadataOptionsOff() {
        val legacyToken = listOf("LIGHT", "12", "true", "", "5").joinToString("|") { value ->
            if (value.isEmpty()) "~"
            else Base64.getUrlEncoder().withoutPadding().encodeToString(value.toByteArray())
        }

        val jsonDefaults = settingsFromJson("{}")!!
        val legacyDefaults = settingsFromToken(legacyToken)!!

        listOf(jsonDefaults, legacyDefaults).forEach { settings ->
            assertFalse(settings.copyPidTid)
            assertFalse(settings.copyPidAsName)
            assertFalse(settings.copyRowNumber)
            assertFalse(settings.copyTimeDelta)
        }
    }
}
