// Pixel sizes, dates and colours of the scene under test are literal on purpose.
@file:Suppress("MagicNumber")

package com.indagium.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.height
import androidx.compose.ui.unit.width
import com.indagium.model.AppSettings
import com.indagium.model.HolidayLightness
import com.indagium.model.HolidayMode
import com.indagium.model.HolidayPack
import com.indagium.model.HolidaySettings
import com.indagium.model.LogTab
import com.indagium.model.TabSizeMode
import com.indagium.model.TabStripPosition
import com.indagium.ui.holiday.*
import org.junit.After
import org.junit.Rule
import org.junit.Test
import java.awt.image.BufferedImage
import java.io.File
import java.time.LocalDate
import javax.imageio.ImageIO
import kotlin.io.path.createTempDirectory
import kotlin.math.abs
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The seasonal scene in the real [ChromeScaffold]: it is the background of the tab bar's own row, behind the tabs, so the chrome has no extra
 * row and the tabs and the toolbar keep their whole width; it is clipped to the tab strip's part of the bar and never reaches the toolbar.
 */
@OptIn(ExperimentalTestApi::class)
class HolidayTabSceneUiTest {
    @get:Rule
    val rule = createComposeRule()

    private lateinit var directory: File
    private lateinit var state: AppState

    @After
    fun tearDown() {
        if (::state.isInitialized) state.close()
        if (::directory.isInitialized) directory.deleteRecursively()
    }

    private fun tab(id: String): LogTab = mkTab(id, "$id.log", emptyList()).copy(sourcePath = "/logs/$id/$id.log")

    private fun look(pack: HolidayPack = HolidayPack.PUMPKIN_PATCH, edit: (HolidaySettings) -> HolidaySettings = { it }): HolidayLook =
        checkNotNull(resolveHolidayLook(AppSettings().withHoliday { edit(it.copy(mode = HolidayMode.ALWAYS, pack = pack)) }, LocalDate.of(2026, 10, 31)))

    private fun setup(look: HolidayLook?, widthDp: Int = HOST_WIDTH, configure: (AppState) -> Unit = {}) {
        directory = createTempDirectory("holiday-tab-scene").toFile()
        state = AppState(
            autosaveFile = File(directory, "state.cache"),
            notesDir = File(directory, "notes"),
            archiveCacheDir = File(directory, "archives"),
            customCommandsDir = File(directory, "commands"),
            autoExportNotes = false,
        )
        state.tabs = listOf(tab("a"), tab("b"), tab("c"))
        state.activeTabId = "b"
        configure(state)
        rule.setContent {
            CompositionLocalProvider(LocalTheme provides (look?.colors ?: LIGHT_THEME), LocalHoliday provides look) {
                Box(Modifier.size(widthDp.dp, HOST_HEIGHT.dp).testTag("band-root")) { ChromeScaffold(state) { Box(Modifier.fillMaxSize()) } }
            }
        }
        rule.waitForIdle()
    }

    private fun stripBounds() = rule.onNodeWithTag("tab-strip").getUnclippedBoundsInRoot()

    private fun fit(it: AppState) = it.updateSettings { s -> s.copy(tabSizeMode = TabSizeMode.FIT) }

    private fun dump(name: String) {
        val dir = System.getenv("INDAGIUM_CAPTURE_BAND") ?: return
        val image = rule.onNodeWithTag("band-root").captureToImage()
        val pixels = image.toPixelMap()
        val out = BufferedImage(image.width, image.height, BufferedImage.TYPE_INT_ARGB)
        for (y in 0 until image.height) for (x in 0 until image.width) out.setRGB(x, y, pixels[x, y].toArgb())
        ImageIO.write(out, "png", File(dir, "$name.png"))
    }

    /** The pixel of the root image at ([xDp], [yDp]). */
    private fun pixelAt(xDp: Float, yDp: Float): androidx.compose.ui.graphics.Color {
        val pixels = rule.onNodeWithTag("band-root").captureToImage().toPixelMap()
        val density = rule.density.density
        return pixels[(xDp * density).toInt(), (yDp * density).toInt()]
    }

    @Test
    fun thereIsNoExtraRowAboveTheTabsUnderAPack() {
        setup(null)
        val plain = stripBounds()
        tearDown()
        setup(look())
        val withPack = stripBounds()
        assertEquals(0f, withPack.top.value, EPS, "the tab bar is still the top row of the window")
        assertEquals(plain.height.value, withPack.height.value, EPS, "the pack adds no height")
        assertEquals(plain.width.value, withPack.width.value, EPS)
    }

    @Test
    fun theSceneIsDrawnInsideTheTabBarBehindTheTabs() {
        // Few FIT tabs leave the strip's right part uncovered: the pumpkin hill shows there, in the bar's own row.
        setup(look()) { fit(it) }
        val strip = stripBounds()
        val tabBar = look().chrome.tabBar
        val toolbarLeft = rule.onNodeWithTag("main-toolbar").getUnclippedBoundsInRoot().left.value
        val sample = pixelAt(toolbarLeft - SAMPLE_INSET_DP, strip.top.value + strip.height.value - SAMPLE_INSET_DP)
        assertTrue(!sameColour(sample, tabBar), "the hill shows in the free part of the tab row: $sample vs $tabBar")
        // A tab covers the scene: its own fill, not the hill, is what is seen under the first tab.
        val tabBounds = rule.onNodeWithTag("tab-a").getUnclippedBoundsInRoot()
        val under = pixelAt(tabBounds.left.value + SAMPLE_INSET_DP * 2, tabBounds.bottom.value - SAMPLE_INSET_DP)
        assertTrue(sameColour(under, look().chrome.tabBg), "an idle tab is opaque over the scene: $under")
    }

    @Test
    fun theSceneStopsWhereTheToolbarOfAJoinedRowStarts() {
        val span = HolidaySceneSpan()
        val painter = checkNotNull(look().theme.tabScene)
        val l = look()
        rule.setContent {
            val density = androidx.compose.ui.platform.LocalDensity.current.density
            span.set(0f..(SPAN_DP * density))
            Box(Modifier.size(BAR_WIDTH_DP.dp, BAR_HEIGHT_DP.dp).testTag("bar").holidayTabBarScene(l, painter, null, span))
        }
        rule.waitForIdle()
        val pixels = rule.onNodeWithTag("bar").captureToImage().toPixelMap()
        val density = rule.density.density

        fun at(xDp: Float) = pixels[(xDp * density).toInt(), ((BAR_HEIGHT_DP - 3f) * density).toInt()]
        assertTrue(!sameColour(at(SPAN_DP - 20f), l.chrome.tabBar), "the hill is drawn inside the span")
        assertTrue(sameColour(at(SPAN_DP + 20f), l.chrome.tabBar), "nothing is drawn past the span, under the toolbar")
        assertTrue(sameColour(at(BAR_WIDTH_DP - 5f), l.chrome.tabBar))
    }

    private fun sameColour(a: androidx.compose.ui.graphics.Color, b: androidx.compose.ui.graphics.Color) =
        abs(a.red - b.red) < COLOUR_TOLERANCE && abs(a.green - b.green) < COLOUR_TOLERANCE && abs(a.blue - b.blue) < COLOUR_TOLERANCE

    @Test
    fun theSceneFollowsTheSceneSwitchAndTheTabPosition() {
        assertTrue(holidaySceneShown(sceneOn = true, tabPosition = TabStripPosition.TOP))
        assertTrue(!holidaySceneShown(sceneOn = false, tabPosition = TabStripPosition.TOP))
        listOf(TabStripPosition.BOTTOM, TabStripPosition.LEFT, TabStripPosition.RIGHT).forEach {
            assertTrue(!holidaySceneShown(sceneOn = true, tabPosition = it), "$it")
        }
        // Switched off, the bar is the plain one: the hill's pixel is the bar colour of the plain look.
        setup(look { it.copy(tabScene = false) }) { fit(it) }
        val strip = stripBounds()
        val toolbarLeft = rule.onNodeWithTag("main-toolbar").getUnclippedBoundsInRoot().left.value
        val sample = pixelAt(toolbarLeft - SAMPLE_INSET_DP, strip.top.value + strip.height.value - SAMPLE_INSET_DP)
        val top = pixelAt(toolbarLeft - SAMPLE_INSET_DP, strip.top.value + SAMPLE_INSET_DP)
        assertTrue(sameColour(sample, top), "the bar is one plain colour without the scene: $sample vs $top")
    }

    @Test
    fun everyPackWithASceneDrawsInTheBar() {
        HolidayPack.entries.forEach { pack ->
            if (::state.isInitialized) state.close()
            setup(look(pack)) { fit(it) }
            dump("scene-${pack.name.lowercase()}")
        }
    }

    @Test
    fun beerLooksLikeTheDesign() {
        listOf(HolidayPack.BEER_TIME_YELLOW to HolidayLightness.DARK, HolidayPack.BEER_TIME_GREEN to HolidayLightness.LIGHT).forEach { (pack, lightness) ->
            if (::state.isInitialized) state.close()
            setup(look(pack) { it.copy(lightness = lightness) }) { fit(it) }
            dump("beer-${pack.name.lowercase()}-${lightness.name.lowercase()}")
        }
    }

    private fun isFoam(c: androidx.compose.ui.graphics.Color) =
        abs(c.red - FOAM.red) < COLOUR_TOLERANCE && abs(c.green - FOAM.green) < COLOUR_TOLERANCE && abs(c.blue - FOAM.blue) < COLOUR_TOLERANCE

    @Test
    fun beerFoamCapsAreVisibleOverTheActiveTabAndOverEachToolbarGroup() {
        setup(look(HolidayPack.BEER_TIME_YELLOW) { it.copy(lightness = HolidayLightness.DARK) })
        val pixels = rule.onNodeWithTag("band-root").captureToImage().toPixelMap()
        val density = rule.density.density
        val stripTop = stripBounds().top.value
        val active = rule.onNodeWithTag("tab-b").getUnclippedBoundsInRoot()
        // A row 5 dp under the bar's top edge: inside the cap, above the tab labels and icons.
        val y = ((stripTop + CAP_SAMPLE_DP) * density).toInt()

        fun foamAt(xDp: Float) = isFoam(pixels[(xDp * density).toInt(), y])
        val capped = (active.left.value.toInt()..active.right.value.toInt()).count { foamAt(it.toFloat()) }
        assertTrue(capped > active.width.value / 2, "a foam cap over the active tab")
        val idle = rule.onNodeWithTag("tab-a").getUnclippedBoundsInRoot()
        assertTrue((idle.left.value.toInt()..idle.right.value.toInt()).none { foamAt(it.toFloat()) }, "no cap over an idle tab")
        // Over the toolbar: one run of foam per group (New, the joined toggles, Layout, Settings), not one per button.
        val from = rule.onNodeWithTag("tab-c").getUnclippedBoundsInRoot().right.value.toInt() + 2
        var runs = 0
        var inRun = false
        for (x in from until HOST_WIDTH) {
            val foam = foamAt(x.toFloat())
            if (foam && !inRun) runs++
            inRun = foam
        }
        assertEquals(EXPECTED_TOOLBAR_GROUPS, runs, "one foam cap per toolbar group")
    }

    @Test
    fun theLabelsStayClearOfTheFoam() {
        setup(look(HolidayPack.BEER_TIME_YELLOW) { it.copy(lightness = HolidayLightness.DARK) })
        val label = rule.onNodeWithTag("tab-b").getUnclippedBoundsInRoot()
        val strip = stripBounds()
        // The cap is 11 dp tall from the bar's top edge; the tab (and so its label) starts under the cap's outer part and is centred in a 30+ dp tab.
        assertTrue(label.top.value - strip.top.value >= FOAM_TOP_ROOM_DP - EPS, "the active tab starts at least ${FOAM_TOP_ROOM_DP}dp below the bar's top edge")
    }

    private fun tabWidthDp(id: String): Float = rule.onNodeWithTag("tab-$id").getUnclippedBoundsInRoot().let { it.width.value }

    private fun areaWidthDp(): Float = rule.onNodeWithTag("tab-strip-drag-idle").getUnclippedBoundsInRoot().width.value

    @Test
    fun fixedTabsKeepTheirFullWidthUnderAPack() {
        setup(look(), widthDp = WIDE_DP)
        val area = rule.onNodeWithTag("tab-strip-drag-idle").getUnclippedBoundsInRoot()
        val last = rule.onNodeWithTag("tab-c").getUnclippedBoundsInRoot()
        assertTrue(abs(tabWidthDp("a") * 3 - areaWidthDp()) < TOLERANCE_DP, "the tabs share the whole tab area, no slot is kept")
        assertTrue((area.right - last.right).value < TOLERANCE_DP, "the last tab ends at the end of the area")
    }

    @Test
    fun fitTabsAreNotReservedAnythingEither() {
        setup(look(), widthDp = WIDE_DP) { it.updateSettings { s -> s.copy(tabSizeMode = TabSizeMode.FIT) } }
        val withPack = tabWidthDp("a")
        if (::state.isInitialized) state.close()
        setup(null, widthDp = WIDE_DP) { it.updateSettings { s -> s.copy(tabSizeMode = TabSizeMode.FIT) } }
        assertEquals(withPack, tabWidthDp("a"), TOLERANCE_DP, "a FIT tab is as wide as without the pack")
    }

    @Test
    fun verticalTabsAreNotShortenedByAPack() {
        setup(look()) { it.updateSettings { s -> s.copy(tabStripPosition = TabStripPosition.LEFT) } }
        val bar = stripBounds()
        assertEquals(6f, bar.top.value, EPS, "the vertical tab bar card keeps its usual 6 dp margin from the top of the window")
        // A vertical bar has no scene: it is the plain card colour all the way down.
        val low = pixelAt(bar.left.value + SAMPLE_INSET_DP * 4, bar.bottom.value - SAMPLE_INSET_DP * 4)
        val high = pixelAt(bar.left.value + SAMPLE_INSET_DP * 4, bar.top.value + SAMPLE_INSET_DP * 4)
        assertTrue(sameColour(low, high), "no hill in a vertical tab bar")
    }

    private companion object {
        const val HOST_WIDTH = 1000
        const val HOST_HEIGHT = 640
        const val WIDE_DP = 1600
        const val EPS = 1.5f
        const val TOLERANCE_DP = 8f
        const val CAP_SAMPLE_DP = 5f
        const val SAMPLE_INSET_DP = 3f
        const val SPAN_DP = 200f
        const val BAR_WIDTH_DP = 400f
        const val BAR_HEIGHT_DP = 36f
        const val COLOUR_TOLERANCE = .05f
        const val FOAM_TOP_ROOM_DP = 6f
        const val EXPECTED_TOOLBAR_GROUPS = 4
        val FOAM = androidx.compose.ui.graphics.Color(0xFFfff4dc)
    }
}
