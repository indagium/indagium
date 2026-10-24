package com.indagium

import com.indagium.ui.LOG_TILE_NARROW_DP
import com.indagium.ui.pidTidColumnsVisible
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class LogViewerTilesTest {
    @Test
    fun narrowPanesDropThePidTidColumns() {
        assertFalse(pidTidColumnsVisible(hasPidTid = true, paneWidthDp = LOG_TILE_NARROW_DP - 0.5f))
        assertFalse(pidTidColumnsVisible(hasPidTid = true, paneWidthDp = 0f))
    }

    @Test
    fun aPaneAtOrAboveTheThresholdKeepsThem() {
        assertTrue(pidTidColumnsVisible(hasPidTid = true, paneWidthDp = LOG_TILE_NARROW_DP))
        assertTrue(pidTidColumnsVisible(hasPidTid = true, paneWidthDp = 1400f))
    }

    @Test
    fun aLogWithoutPidsNeverShowsThem() {
        assertFalse(pidTidColumnsVisible(hasPidTid = false, paneWidthDp = 1400f))
        assertFalse(pidTidColumnsVisible(hasPidTid = false, paneWidthDp = 100f))
    }

    @Test
    fun theThresholdIsTheLogViewsNarrowTileWidth() {
        assertEquals(520f, LOG_TILE_NARROW_DP)
    }
}
