@file:Suppress("MagicNumber") // Fixture coordinates, timestamps and sizes, not tunable constants.

package com.indagium.testing

import com.indagium.capture.CaptureCommandResult
import com.indagium.testing.authoring.RecordedPoint
import com.indagium.testing.authoring.RecordingAdb
import com.indagium.testing.authoring.RecordingScreenProbe
import com.indagium.testing.authoring.RecordingScreenState
import com.indagium.testing.authoring.RecordingScreenshotEvidence
import com.indagium.testing.authoring.TappedElement
import com.indagium.testing.authoring.parseTopActivity
import com.indagium.testing.authoring.resolveTappedElement
import com.indagium.testing.device.UI_DUMP_COMMAND
import com.indagium.testing.device.UiNode
import com.indagium.testing.device.parseUiAutomatorDump
import java.awt.image.BufferedImage
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import javax.imageio.ImageIO
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Synthetic uiautomator XML and dumpsys text only; no device, no real log. */
class RecordingScreenProbeTest {
    private fun xmlNode(
        bounds: String,
        pkg: String = "com.example.shop",
        text: String = "",
        desc: String = "",
        id: String = "",
        cls: String = "android.widget.TextView",
        clickable: Boolean = false,
        password: Boolean = false,
        focused: Boolean = false,
        close: Boolean = true,
    ) = """<node index="0" text="$text" resource-id="$id" class="$cls" package="$pkg" content-desc="$desc" checkable="false" checked="false" """ +
        """clickable="$clickable" enabled="true" focusable="true" focused="$focused" scrollable="false" long-clickable="false" """ +
        """password="$password" selected="false" bounds="$bounds"${if (close) " />" else ">"}"""

    private val dump = """
        <?xml version='1.0' encoding='UTF-8' standalone='yes' ?>
        <hierarchy rotation="0">
        ${xmlNode("[0,0][1000,2000]", cls = "android.widget.FrameLayout", close = false)}
        ${xmlNode("[100,100][900,300]", id = "com.example.shop:id/search_button", cls = "android.widget.FrameLayout", clickable = true, close = false)}
        ${xmlNode("[200,150][400,250]", text = "Search")}
        </node>
        ${xmlNode("[100,400][900,500]", id = "com.example.shop:id/password", cls = "android.widget.EditText", clickable = true, password = true,
        focused = true)}
        </node>
        </hierarchy>
        UI hierarchy dumped to: /dev/tty
    """.trimIndent()

    private val dumpsysApi31 = """
        ACTIVITY MANAGER ACTIVITIES (dumpsys activity activities)
          Display #0 (activities from top to bottom):
            topResumedActivity=ActivityRecord{1f2e3d4 u0 com.example.shop/.checkout.CheckoutActivity t42}
            ResumedActivity: ActivityRecord{9a8b7c6 u0 com.example.other/.Old t7}
    """.trimIndent()

    private fun result(text: String, exit: Int = 0, timedOut: Boolean = false) =
        CaptureCommandResult(exit, text.toByteArray(), ByteArray(0), timedOut)

    private fun png(): ByteArray = ByteArrayOutputStream().also { ImageIO.write(BufferedImage(20, 30, BufferedImage.TYPE_INT_RGB), "png", it) }.toByteArray()

    private fun fakeAdb(dumpResult: CaptureCommandResult?, activityResult: CaptureCommandResult?, calls: MutableList<List<String>> = mutableListOf()) =
        RecordingAdb { arguments, _, _ ->
            calls += arguments
            val answer = if (arguments == UI_DUMP_COMMAND) dumpResult else activityResult
            answer ?: error("adb offline")
        }

    // ── Activity parsing ──

    @Test
    fun theTopResumedActivityIsPreferredOverTheOlderResumedLine() {
        assertEquals("com.example.shop" to ".checkout.CheckoutActivity", parseTopActivity(dumpsysApi31))
    }

    @Test
    fun olderAndNullResumedLinesAreHandled() {
        val older = "  mResumedActivity: ActivityRecord{abc123 u0 com.example.shop/com.example.shop.Main t3}"
        assertEquals("com.example.shop" to "com.example.shop.Main", parseTopActivity(older))
        assertNull(parseTopActivity("  mResumedActivity: null\n  mFocusedApp=null"))
        assertNull(parseTopActivity(""))
    }

    // ── Hierarchy parsing ──

    @Test
    fun theDumpKeepsPackagePasswordAndFocusFlags() {
        val parsed = assertNotNull(parseUiAutomatorDump(dump))
        assertEquals("com.example.shop", parsed.packageName)
        assertEquals(1000, parsed.screenWidth)
        val password = parsed.nodes.single { it.password }
        assertEquals("", password.text)
        assertTrue(password.focused)
        assertEquals("com.example.shop", password.packageName)
        assertFalse(parsed.nodes.single { it.text == "Search" }.password)
    }

    @Test
    fun aPasswordNodeStaysEvenWithoutAnyOtherReasonToKeepIt() {
        val xml = """<hierarchy>${xmlNode("[0,0][10,10]", cls = "android.widget.FrameLayout")}${xmlNode("[0,0][5,5]", password = true)}</hierarchy>"""
        assertTrue(assertNotNull(parseUiAutomatorDump(xml)).nodes.single().password)
    }

    // ── The probe ──

    @Test
    fun aProbeReadsHierarchyActivityAndImageWithinItsClockWindow() {
        val times = ArrayDeque(listOf(1_000L, 1_010L, 1_020L, 1_450L))
        val calls = mutableListOf<List<String>>()
        val order = mutableListOf<String>()
        val adb = RecordingAdb { arguments, _, _ ->
            order += if (arguments == UI_DUMP_COMMAND) "hierarchy" else "activity"
            calls += arguments
            if (arguments == UI_DUMP_COMMAND) result(dump) else result(dumpsysApi31)
        }
        val probe = RecordingScreenProbe(adb, screencap = { order += "screenshot"; png() }, clock = { times.removeFirst() })
        var earlyEvidence: RecordingScreenshotEvidence? = null
        val state = assertNotNull(probe.read { evidence -> earlyEvidence = evidence; order += "delivered" })
        assertEquals(1_020L, state.startedAt)
        assertEquals(1_450L, state.finishedAt)
        assertEquals(1_000L, state.screenshotStartedAt)
        assertEquals(1_010L, state.screenshotFinishedAt)
        assertEquals(1_000L, earlyEvidence?.acquiredAtMs)
        assertEquals(1_010L, earlyEvidence?.acquisitionFinishedAtMs)
        assertEquals("com.example.shop", state.packageName)
        assertEquals(".checkout.CheckoutActivity", state.activity)
        assertEquals(1000 to 2000, state.screenWidth to state.screenHeight)
        assertTrue(state.nodes.any { it.text == "Search" })
        val image = ImageIO.read(ByteArrayInputStream(assertNotNull(state.screenshotJpeg)))
        assertEquals(20, image.width)
        assertEquals(listOf("screenshot", "delivered", "hierarchy", "activity"), order)
        assertEquals(listOf(UI_DUMP_COMMAND, listOf("shell", "dumpsys", "activity", "activities")), calls)
    }

    @Test
    fun theHierarchyPackageIsUsedWhenTheActivityIsUnknown() {
        val state = assertNotNull(RecordingScreenProbe(fakeAdb(result(dump), result("nothing here"))).read())
        assertEquals("com.example.shop", state.packageName)
        assertNull(state.activity)
    }

    @Test
    fun aFailingDumpStillKeepsTheActivityAndImage() {
        val probe = RecordingScreenProbe(fakeAdb(result("", timedOut = true), result(dumpsysApi31)), screencap = ::png)
        val state = assertNotNull(probe.read())
        assertTrue(state.nodes.isEmpty())
        assertEquals(0, state.screenWidth)
        assertEquals("com.example.shop", state.packageName)
        assertNotNull(state.screenshotJpeg)
    }

    @Test
    fun aProbeWhereEverythingFailsIsNullAndNeverThrows() {
        val broken = RecordingScreenProbe(fakeAdb(null, null), screencap = { error("no screencap") })
        assertNull(broken.read())
        assertNull(RecordingScreenProbe(fakeAdb(result("ERROR: null root node"), result("", exit = 1))).read())
    }

    // ── Password handling ──

    private fun state(vararg nodes: UiNode) = RecordingScreenState(1, 0, 0, "com.example.shop", null, nodes.toList(), 1000, 2000)

    private fun field(password: Boolean, focused: Boolean) =
        UiNode(0, 0, 10, 10, "", "", "id", "EditText", clickable = true, enabled = true, scrollable = false, password = password, focused = focused)

    @Test
    fun aFocusedPasswordFieldOrAnAmbiguousScreenCountsAsEditingAPassword() {
        assertTrue(state(field(password = true, focused = true)).passwordFieldLikelyEdited())
        assertTrue(state(field(password = true, focused = false)).passwordFieldLikelyEdited(), "no focus information: hide rather than guess")
        assertFalse(state(field(password = true, focused = false), field(password = false, focused = true)).passwordFieldLikelyEdited())
        assertFalse(state(field(password = false, focused = true)).passwordFieldLikelyEdited())
        assertFalse(state().passwordFieldLikelyEdited())
    }

    // ── Element under the tap ──

    private fun node(
        l: Int,
        t: Int,
        r: Int,
        b: Int,
        text: String = "",
        desc: String = "",
        id: String = "",
        cls: String = "TextView",
        clickable: Boolean = false,
    ) = UiNode(l, t, r, b, text, desc, id, cls, clickable, enabled = true, scrollable = false)

    private fun screen(vararg nodes: UiNode, w: Int = 1000, h: Int = 2000) =
        RecordingScreenState(1, 0, 0, "com.example.shop", null, nodes.toList(), w, h)

    private fun tapAt(x: Int, y: Int, w: Int = 100, h: Int = 200) = RecordedPoint(x, y, w, h)

    @Test
    fun theSmallestLabelledNodeUnderTheTapWins() {
        val search = node(200, 150, 400, 250, text = "Search")
        val state = screen(node(100, 100, 900, 300, id = "app:id/bar", clickable = true), search)
        assertEquals(TappedElement("Search", "", "", "TextView"), resolveTappedElement(state, tapAt(30, 20)))
    }

    @Test
    fun mirrorCoordinatesAreScaledToTheDumpSize() {
        val state = screen(node(0, 0, 500, 500, text = "Top left"), node(500, 1000, 1000, 2000, desc = "Bottom right"), w = 1000, h = 2000)
        assertEquals("Bottom right", resolveTappedElement(state, tapAt(75, 150))?.contentDesc)
        assertEquals("Top left", resolveTappedElement(state, tapAt(10, 20))?.text)
        // A different mirror size maps the same relative position.
        assertEquals("Bottom right", resolveTappedElement(state, RecordedPoint(750, 1500, 1000, 2000))?.contentDesc)
    }

    @Test
    fun anUnlabelledNodeFallsBackToItsLabelledClickableAncestor() {
        val button = node(100, 100, 900, 300, id = "app:id/buy", cls = "Button", clickable = true)
        val icon = node(200, 150, 300, 250, cls = "ImageView", clickable = true)
        val label = node(400, 150, 800, 250, text = "far away")
        val element = resolveTappedElement(screen(button, icon, label), tapAt(25, 20))
        assertEquals("app:id/buy", element?.resourceId)
        assertEquals("Button", element?.className)
    }

    @Test
    fun withoutALabelledAncestorTheSmallestClickableNodeIsUsed() {
        val outer = node(0, 0, 1000, 1000, cls = "Outer", clickable = true)
        val inner = node(100, 100, 300, 300, cls = "Inner", clickable = true)
        assertEquals("Inner", resolveTappedElement(screen(outer, inner), tapAt(15, 15))?.className)
        assertNull(resolveTappedElement(screen(node(0, 0, 1000, 1000, cls = "Plain")), tapAt(15, 15)), "no label and nothing clickable")
    }

    @Test
    fun aTapOutsideAnyNodeOrOnARotatedDumpResolvesToNothing() {
        val state = screen(node(0, 0, 100, 100, text = "Corner"))
        assertNull(resolveTappedElement(state, tapAt(90, 190)))
        assertNull(resolveTappedElement(screen(w = 1000, h = 2000), tapAt(1, 1)))
        assertNull(resolveTappedElement(state, RecordedPoint(10, 10, 200, 100)), "landscape mirror against a portrait dump")
        assertNull(resolveTappedElement(state, RecordedPoint(10, 10, 0, 0)))
    }
}
