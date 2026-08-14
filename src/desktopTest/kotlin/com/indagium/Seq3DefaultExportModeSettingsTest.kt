package com.indagium

import com.indagium.diagram3.DiagramExportMode
import com.indagium.model.AppSettings
import com.indagium.ui.settingsFromJson
import com.indagium.ui.settingsJson
import kotlin.test.Test
import kotlin.test.assertEquals

/** v3 port of the deleted `DiagramDefaultExportModeSettingsTest` — [DiagramExportMode] moved from
 *  `com.indagium.diagram` into `com.indagium.diagram3` in the v3 cutover, but its serialised
 *  strings (`IMAGE`/`SOURCE`) must not change, or an existing autosave's saved export-mode
 *  preference breaks on load (see AutosaveCodec.kt's own settingsFromJson decoder). */
class Seq3DefaultExportModeSettingsTest {
    @Test
    fun defaultIsImageAndSourceRoundTripsThroughSettingsJson() {
        assertEquals(DiagramExportMode.IMAGE, AppSettings().diagramDefaultExportMode)

        val restored = settingsFromJson(
            AppSettings(diagramDefaultExportMode = DiagramExportMode.SOURCE).settingsJson(),
        )

        assertEquals(DiagramExportMode.SOURCE, restored?.diagramDefaultExportMode)
    }

    @Test
    fun missingOrMalformedModeKeepsImageMigrationDefault() {
        assertEquals(DiagramExportMode.IMAGE, settingsFromJson("{}")?.diagramDefaultExportMode)
        assertEquals(
            DiagramExportMode.IMAGE,
            settingsFromJson("{\"diagramDefaultExportMode\":\"not-a-mode\"}")?.diagramDefaultExportMode,
        )
    }
}
