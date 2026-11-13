package com.indagium

import androidx.compose.ui.graphics.Color
import com.indagium.model.AppSettings
import com.indagium.model.ErrorHighlightStyle
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

        val settings = AppSettings(
            proofreading = ProofreadingSettings(enabled = false, language = "en-GB", vocabulary = listOf("Indagium", "iOS")),
            errorHighlightStyle = ErrorHighlightStyle(
                color = Color(0xFFE53935), backgroundEnabled = false, textColor = Color.White,
                fontFamily = "Dialog", bold = true, italic = false,
            ),
        )
        val restored = settingsFromJson(settings.settingsJson())!!

        assertEquals(settings.proofreading, restored.proofreading)
        assertEquals(settings.errorHighlightStyle, restored.errorHighlightStyle)
    }

    @Test
    fun oldAndInvalidSettingsUseSafeEnglishDefaults() {
        assertEquals(ProofreadingSettings(), settingsFromJson("{}")!!.proofreading)
        assertEquals(ErrorHighlightStyle(), settingsFromJson("{}")!!.errorHighlightStyle)
        assertEquals(
            ProofreadingSettings(enabled = false, language = "en-US"),
            settingsFromJson("""{"proofreading":{"enabled":false,"language":"fr-FR"}}""")!!.proofreading,
        )
        assertEquals(
            listOf("Indagium", "Café"),
            settingsFromJson("""{"proofreading":{"vocabulary":[" Indagium ","indagium","two words","Café"]}}""")!!.proofreading.vocabulary,
        )
        assertTrue(settingsFromJson("{}")!!.settingsJson().contains("\"proofreading\""))
    }
}
