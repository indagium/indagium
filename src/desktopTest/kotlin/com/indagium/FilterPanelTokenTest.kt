package com.indagium

import com.indagium.ui.FilterPanelUiState
import com.indagium.ui.b64
import com.indagium.ui.filterPanelToken
import com.indagium.ui.restoreFilterPanelToken
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse

// Mirrors the private String.fieldToken() encoding inside ui/AutosaveCodec.kt (tokenFields'
// per-field helper): b64() for a non-empty value, the "~" sentinel for an empty one. Reimplemented
// here (rather than exposing the private builder) purely to construct a legacy-shaped token string
// for the test below.
private fun String.legacyFieldToken(): String = if (isEmpty()) "~" else b64()

private fun legacyTokenFields(vararg values: String): String = values.joinToString("|") { it.legacyFieldToken() }

/**
 * Covers `FilterPanelUiState.filterPanelToken()` / `String.restoreFilterPanelToken()`
 * (ui/AutosaveCodec.kt) after Stage 2a appended `logCompositionExpanded` (field index 11) — the
 * "Log composition" section's expand state. Appended after the existing 11 fields (never
 * inserted/reordered), so a legacy token written before that section existed must still restore
 * every prior field correctly and default the new one to false (collapsed).
 */
class FilterPanelTokenTest {
    @Test
    fun logCompositionExpandedRoundTripsThroughTheToken() {
        val original = FilterPanelUiState().apply {
            logCompositionExpanded = true
        }

        val token = original.filterPanelToken()
        val restored = FilterPanelUiState()
        restored.restoreFilterPanelToken(token)

        assertEquals(true, restored.logCompositionExpanded)
    }

    @Test
    fun aLegacyTokenWithoutTheNewFieldRestoresLogCompositionExpandedAtTheDefault() {
        // A pre-Stage-2a token: exactly the 11 fields filterPanelToken() used to write, with no
        // trailing logCompositionExpanded field.
        val legacy = FilterPanelUiState().apply {
            logCompositionExpanded = true // must NOT influence the legacy token below
        }
        val legacyToken = legacyTokenFields(
            legacy.hlListExpanded.toString(),
            legacy.lvlExpanded.toString(),
            legacy.seqExpanded.toString(),
            legacy.sfExpanded.toString(),
            legacy.incPillsExpanded.toString(),
            legacy.incMsgPillsExpanded.toString(),
            legacy.excMsgPillsExpanded.toString(),
            legacy.crashExpanded.toString(),
            "ALL",
            "",
            legacy.sfFavoritesExpanded.toString(),
        )

        val restored = FilterPanelUiState()
        // Default is false (collapsed) — restoreFilterPanelToken must not touch it on a legacy
        // token, so it stays at whatever the fresh FilterPanelUiState() default already is.
        assertFalse(restored.logCompositionExpanded)
        restored.restoreFilterPanelToken(legacyToken)

        assertFalse(restored.logCompositionExpanded, "a legacy token with no field 11 must restore at the collapsed default")
    }
}
