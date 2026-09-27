package com.indagium

import com.indagium.model.AppSettings
import com.indagium.ui.settingsFromJson
import com.indagium.ui.settingsJson
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

// The New tab's "Device logging" section starts expanded; a collapse is remembered via keyed JSON.
class DeviceLoggingPanelSettingCodecTest {
    @Test
    fun expandedByDefaultAndACollapseRoundTrips() {
        assertTrue(AppSettings().deviceLoggingPanelExpanded)

        val restored = settingsFromJson(AppSettings(deviceLoggingPanelExpanded = false).settingsJson())!!

        assertFalse(restored.deviceLoggingPanelExpanded)
    }

    @Test
    fun settingsSavedBeforeTheFieldExistedOpenExpanded() {
        assertTrue(settingsFromJson("""{"showRegexFilterSummary":false}""")!!.deviceLoggingPanelExpanded)
    }
}
