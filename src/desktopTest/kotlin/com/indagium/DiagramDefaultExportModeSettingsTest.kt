package com.indagium

import com.indagium.diagram.DiagramExportMode
import com.indagium.model.AppSettings
import com.indagium.ui.settingsFromJson
import com.indagium.ui.settingsJson
import kotlin.test.Test
import kotlin.test.assertEquals

class DiagramDefaultExportModeSettingsTest {
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
