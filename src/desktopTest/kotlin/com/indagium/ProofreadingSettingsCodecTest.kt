package com.indagium

import com.indagium.model.AppSettings
import com.indagium.model.ProofreadingSettings
import com.indagium.ui.settingsFromJson
import com.indagium.ui.settingsJson
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ProofreadingSettingsCodecTest {
    @Test
    fun settingsDefaultOnAndRoundTripChosenEnglishVariant() {
        assertEquals(ProofreadingSettings(), AppSettings().proofreading)

        val settings = AppSettings(proofreading = ProofreadingSettings(enabled = false, language = "en-GB"))
        val restored = settingsFromJson(settings.settingsJson())!!

        assertEquals(settings.proofreading, restored.proofreading)
    }

    @Test
    fun oldAndInvalidSettingsUseSafeEnglishDefaults() {
        assertEquals(ProofreadingSettings(), settingsFromJson("{}")!!.proofreading)
        assertEquals(
            ProofreadingSettings(enabled = false, language = "en-US"),
            settingsFromJson("""{"proofreading":{"enabled":false,"language":"fr-FR"}}""")!!.proofreading,
        )
        assertTrue(settingsFromJson("{}")!!.settingsJson().contains("\"proofreading\""))
    }
}
