package com.indagium

import com.indagium.capture.CaptureDevice
import com.indagium.model.AppSettings
import com.indagium.model.CaptureMirrorLayout
import com.indagium.model.DEFAULT_CAPTURE_MIRROR_SPLIT
import com.indagium.model.MAX_CAPTURE_MIRROR_LAYOUTS
import com.indagium.ui.AppState
import com.indagium.ui.captureMirrorDeviceKey
import com.indagium.ui.captureMirrorLayoutFor
import com.indagium.ui.mkTab
import com.indagium.ui.settingsFromJson
import com.indagium.ui.settingsJson
import com.indagium.ui.withCaptureMirrorLayout
import java.io.File
import kotlin.io.path.createTempDirectory
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class CaptureMirrorLayoutTest {
    @Test
    fun wirelessDevicesAreKeyedByModelAndWiredOnesBySerial() {
        assertEquals("R5CT123", captureMirrorDeviceKey(CaptureDevice("R5CT123", "device", model = "Pixel 8")))
        assertEquals("Pixel 8", captureMirrorDeviceKey(CaptureDevice("192.168.1.20:41233", "device", model = "Pixel 8")))
        assertEquals("emulator-5554", captureMirrorDeviceKey(CaptureDevice("emulator-5554", "device")))
        // A wireless device with no known model falls back to its serial rather than an empty key.
        assertEquals("192.168.1.20:41233", captureMirrorDeviceKey(CaptureDevice("192.168.1.20:41233", "device", model = "")))
    }

    @Test
    fun layoutFallsBackFromDeviceEntryToLastToDefault() {
        val empty = AppSettings()
        assertEquals(CaptureMirrorLayout(), empty.captureMirrorLayoutFor("A"))
        assertEquals(DEFAULT_CAPTURE_MIRROR_SPLIT, empty.captureMirrorLayoutFor(null).videoSplit)

        val withA = empty.withCaptureMirrorLayout("A") { it.copy(videoSplit = 0.6f) }
        assertEquals(0.6f, withA.captureMirrorLayoutFor("A").videoSplit)
        // An unseen device inherits the last layout used anywhere.
        assertEquals(0.6f, withA.captureMirrorLayoutFor("B").videoSplit)

        val withB = withA.withCaptureMirrorLayout("B") { it.copy(videoSplit = 0.3f) }
        assertEquals(0.6f, withB.captureMirrorLayoutFor("A").videoSplit)
        assertEquals(0.3f, withB.captureMirrorLayoutFor("B").videoSplit)
        assertEquals(0.3f, withB.captureMirrorLayoutFor("C").videoSplit)
    }

    @Test
    fun updatesClampTheSplitAndKeepTheMapBounded() {
        val clamped = AppSettings().withCaptureMirrorLayout("A") { it.copy(videoSplit = 5f) }
        assertEquals(0.82f, clamped.captureMirrorLayoutFor("A").videoSplit)

        var settings = AppSettings()
        repeat(MAX_CAPTURE_MIRROR_LAYOUTS + 5) { i -> settings = settings.withCaptureMirrorLayout("dev$i") { it.copy(videoSplit = 0.5f) } }
        assertEquals(MAX_CAPTURE_MIRROR_LAYOUTS, settings.captureMirrorLayouts.size)
        assertNull(settings.captureMirrorLayouts["dev0"], "the least recently used device is dropped first")
        assertNotNull(settings.captureMirrorLayouts["dev${MAX_CAPTURE_MIRROR_LAYOUTS + 4}"])
    }

    @Test
    fun layoutsRoundTripThroughTheSettingsJson() {
        val settings = AppSettings()
            .withCaptureMirrorLayout("A") { it.copy(videoSplit = 0.55f, detachedWidth = 700f, detachedHeight = 800f) }
            .withCaptureMirrorLayout("Pixel 8") { it.copy(videoSplit = 0.3f) }

        val restored = assertNotNull(settingsFromJson(settings.settingsJson()))

        assertEquals(settings.captureMirrorLayouts, restored.captureMirrorLayouts)
        assertEquals(settings.lastCaptureMirrorLayout, restored.lastCaptureMirrorLayout)
        assertEquals(CaptureMirrorLayout(0.55f, 700f, 800f), restored.captureMirrorLayouts["A"])
    }

    @Test
    fun missingKeysDecodeToEmptyAndBadValuesAreClampedOrDropped() {
        val old = assertNotNull(settingsFromJson("""{"annotationLogBlockStyle":"JIRA_JAVA"}"""))
        assertTrue(old.captureMirrorLayouts.isEmpty())
        assertNull(old.lastCaptureMirrorLayout)

        val bad = assertNotNull(
            settingsFromJson(
                """{"captureMirrorLayouts":{"A":{"videoSplit":9.0,"detachedWidth":5.0,"detachedHeight":900.0},"B":"junk"},""" +
                    """"lastCaptureMirrorLayout":{"videoSplit":-1.0}}""",
            ),
        )
        assertEquals(setOf("A"), bad.captureMirrorLayouts.keys)
        assertEquals(CaptureMirrorLayout(0.82f, null, 900f), bad.captureMirrorLayouts["A"])
        assertEquals(0.18f, bad.lastCaptureMirrorLayout?.videoSplit)
    }

    @Test
    fun appStateRemembersTheLayoutEvenBeforeTheTabsDeviceIsKnown() {
        val root = createTempDirectory("capture-mirror-layout").toFile()
        val app = AppState(autosaveFile = File(root, "autosave"), autoExportNotes = false)
        try {
            app.tabs = listOf(mkTab("t", "Capture — x", emptyList()).copy(captureSourceSessionId = "unknown-session"))
            assertEquals(DEFAULT_CAPTURE_MIRROR_SPLIT, app.captureMirrorLayoutFor("t").videoSplit)

            app.rememberCaptureMirrorLayout("t") { it.copy(videoSplit = 0.7f) }

            // No session resolves, so only the "last" layout is written, and it seeds the next capture.
            assertTrue(app.settings.captureMirrorLayouts.isEmpty())
            assertEquals(0.7f, app.settings.lastCaptureMirrorLayout?.videoSplit)
            assertEquals(0.7f, app.captureMirrorLayoutFor("t").videoSplit)
        } finally {
            app.close()
            root.deleteRecursively()
        }
    }
}
