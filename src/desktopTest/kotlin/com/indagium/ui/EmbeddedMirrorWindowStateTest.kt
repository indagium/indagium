package com.indagium.ui

import kotlin.test.Test
import kotlin.test.assertEquals

class EmbeddedMirrorWindowStateTest {
    @Test
    fun detachedWindowSelectionRetainsOnlyLiveCaptureTabsAcrossNavigation() {
        val live = mkTab("live", "Capture — Pixel", emptyList()).copy(captureSessionId = "session")
        val stopped = mkTab("stopped", "Capture — Old", emptyList())
        val ordinary = mkTab("log", "log.txt", emptyList())

        assertEquals(listOf(live), activeDetachedEmbeddedMirrorTabs(listOf(live, stopped, ordinary), setOf("live", "stopped", "log")))
    }
}
