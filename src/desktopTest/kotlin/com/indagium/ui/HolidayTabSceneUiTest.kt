// Pixel sizes, dates and colours of the scene under test are literal on purpose.
@file:Suppress("MagicNumber")

package com.indagium.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertTextEquals
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
    fun readyPromptHasAnActiveCursorInBothPalettes() {
        for (lightness in listOf(HolidayLightness.DARK, HolidayLightness.LIGHT)) {
            if (::state.isInitialized) state.close()
            setup(look(HolidayPack.READY_PROMPT) { it.copy(lightness = lightness) }) { fit(it) }
            assertTrue(rule.onNodeWithTag("tab-cursor-b", useUnmergedTree = true).fetchSemanticsNode().boundsInRoot.width > 0f)
            dump("ready-${lightness.name.lowercase()}")
        }
    }

    @Test
    fun tulipsChromeRendersInBothPalettes() {
        for (lightness in listOf(HolidayLightness.DARK, HolidayLightness.LIGHT)) {
            if (::state.isInitialized) state.close()
            setup(look(HolidayPack.TULIPS) { it.copy(lightness = lightness) }) { fit(it) }
            rule.onNodeWithTag("tab-b").assertExists()
            dump("tulips-${lightness.name.lowercase()}")
        }
    }

    @Test
    fun muertosChromeRendersInBothPalettes() {
        for (lightness in listOf(HolidayLightness.DARK, HolidayLightness.LIGHT)) {
            if (::state.isInitialized) state.close()
            setup(look(HolidayPack.DIA_DE_MUERTOS) { it.copy(lightness = lightness) }) { fit(it) }
            rule.onNodeWithTag("tab-b").assertExists()
            dump("muertos-${lightness.name.lowercase()}")
        }
    }

    @Test
    fun thanksgivingChromeRendersInBothPalettes() {
        for (lightness in listOf(HolidayLightness.DARK, HolidayLightness.LIGHT)) {
            if (::state.isInitialized) state.close()
            setup(look(HolidayPack.THANKSGIVING) { it.copy(lightness = lightness) }) { fit(it) }
            rule.onNodeWithTag("tab-b").assertExists()
            dump("thanksgiving-${lightness.name.lowercase()}")
        }
    }

    @Test
    fun coffeeBearChromeRendersInBothPalettes() {
        for (lightness in listOf(HolidayLightness.DARK, HolidayLightness.LIGHT)) {
            if (::state.isInitialized) state.close()
            setup(look(HolidayPack.COFFEE_BEAR) { it.copy(lightness = lightness) }) { fit(it) }
            rule.onNodeWithTag("tab-b").assertExists()
            dump("bear-${lightness.name.lowercase()}")
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

    private fun winterLook(pack: HolidayPack, lightness: HolidayLightness) = look(pack) { it.copy(lightness = lightness) }

    // Nothing moves: the twinkling star and the blinking cursor are at their still pose (full strength, on), whatever the test clock does.
    private fun stillLook(pack: HolidayPack) = look(pack) { it.copy(lightness = HolidayLightness.DARK, animatePicture = false) }

    @Test
    fun theWinterPacksRenderTheirChromeInBothPalettes() {
        for (pack in listOf(HolidayPack.FIRST_SNOW, HolidayPack.COZY_CHRISTMAS, HolidayPack.CLASSIC_CHRISTMAS)) {
            for (lightness in listOf(HolidayLightness.DARK, HolidayLightness.LIGHT)) {
                if (::state.isInitialized) state.close()
                setup(winterLook(pack, lightness)) { fit(it) }
                rule.onNodeWithTag("tab-b").assertExists()
                rule.onNodeWithTag("tab-stem-b").assertExists()
                dump("${pack.name.lowercase()}-${lightness.name.lowercase()}")
            }
        }
    }

    @Test
    fun theSnowCapSitsOverTheActiveTabAndNotOverAnIdleOne() {
        val look = winterLook(HolidayPack.FIRST_SNOW, HolidayLightness.DARK)
        setup(look)
        val snow = androidx.compose.ui.graphics.Color(0xFFeef5fc)
        val active = rule.onNodeWithTag("tab-b").getUnclippedBoundsInRoot()
        val idle = rule.onNodeWithTag("tab-a").getUnclippedBoundsInRoot()
        val centre = { b: androidx.compose.ui.unit.DpRect -> (b.left.value + b.right.value) / 2f }
        assertTrue(sameColour(pixelAt(centre(active), active.top.value - 1f), snow), "snow sits on top of the active tab")
        assertTrue(!sameColour(pixelAt(centre(idle), idle.top.value - 1f), snow), "an idle tab has no cap")
    }

    @Test
    fun classicTabsHaveAPineLineAndTheActiveOneARedLineWithAStarAndABowAbove() {
        val look = stillLook(HolidayPack.CLASSIC_CHRISTMAS)
        setup(look)
        val active = rule.onNodeWithTag("tab-b").getUnclippedBoundsInRoot()
        val idle = rule.onNodeWithTag("tab-a").getUnclippedBoundsInRoot()
        val theme = look.theme
        // 2.5 dp under the tab's top edge: inside the 3 dp line, clear of the 1 to 1.5 dp outline and the label.
        val activeLine = pixelAt(active.left.value + active.width.value * .3f, active.top.value + 2.8f)
        val idleLine = pixelAt(idle.left.value + idle.width.value * .3f, idle.top.value + 2.5f)
        assertTrue(sameColour(activeLine, checkNotNull(theme.tabEdgeLine(true, true))), "red line on the active tab: $activeLine")
        assertTrue(sameColour(idleLine, checkNotNull(theme.tabEdgeLine(false, true))), "pine line on an idle tab: $idleLine")
        val below = pixelAt(idle.left.value + idle.width.value * .3f, idle.top.value + 12f)
        assertTrue(!sameColour(below, idleLine), "the line is only 3 dp thick")
        // The bow is above the active tab's top edge, in the middle; the twinkling star at its corner (still pose here: full gold).
        val bow = pixelAt((active.left.value + active.right.value) / 2f - 4f, active.top.value - 3f)
        assertTrue(bow.green > bow.red && bow.green > .4f, "a green bow loop over the tab: $bow")
        val star = pixelAt(active.right.value - 7.5f, active.top.value - 2.5f)
        assertTrue(star.red > .7f && star.green > .6f && star.blue < .5f, "a gold star at the corner: $star")
        assertTrue(abs(star.red - GOLD.red) < STAR_TOLERANCE && abs(star.green - GOLD.green) < STAR_TOLERANCE, "the still star is the design's gold: $star")
    }

    @Test
    fun cozyTabsAreGiftTagsWithAHolePunchedThroughAndTheLabelClearOfIt() {
        val look = winterLook(HolidayPack.COZY_CHRISTMAS, HolidayLightness.DARK).let { it.copy(effects = it.effects.copy(activeEffects = emptyList())) }
        setup(look) { fit(it) }
        val tab = rule.onNodeWithTag("tab-a").getUnclippedBoundsInRoot()
        val midY = (tab.top.value + tab.bottom.value) / 2f
        val barColour = look.chrome.tabBar
        // The hole shows the tab bar through the tab, the tag around it is the tab fill.
        assertTrue(sameColour(pixelAt(tab.left.value + 14.5f, midY), barColour), "the hole is open to the bar: ${pixelAt(tab.left.value + 14.5f, midY)}")
        assertTrue(sameColour(pixelAt(tab.left.value + 24f, tab.top.value + 3f), look.chrome.tabBg), "the tag is solid beside it")
        // The point: the top-left corner is cut away, so the bar shows there too.
        assertTrue(sameColour(pixelAt(tab.left.value + 2f, tab.top.value + 2f), barColour), "the corner is cut by the point")
        val label = rule.onNodeWithTag("tab-label-a", useUnmergedTree = true).fetchSemanticsNode().boundsInRoot
        val density = rule.density.density
        val labelStartDp = label.left / density - tab.left.value
        assertTrue(labelStartDp >= 25.5f, "the label starts past the tag's hole, as the design's 26 dp: $labelStartDp dp")
        dump("cozy-hole")
    }

    @Test
    fun theThreePartyPacksRenderTheirChromeInBothPalettes() {
        for (pack in listOf(HolidayPack.NEW_YEARS_EVE, HolidayPack.PROGRAMMERS_DAY, HolidayPack.RETRO)) {
            for (lightness in listOf(HolidayLightness.DARK, HolidayLightness.LIGHT)) {
                if (::state.isInitialized) state.close()
                setup(winterLook(pack, lightness)) { fit(it) }
                rule.onNodeWithTag("tab-b").assertExists()
                dump("${pack.name.lowercase()}-${lightness.name.lowercase()}")
            }
        }
    }

    @Test
    fun programmersDayTabsHaveTheTildePrefixASquareCornerAndABlinkingCursorOnTheActiveOne() {
        val look = stillLook(HolidayPack.PROGRAMMERS_DAY)
        setup(look)
        rule.onNodeWithTag("tab-prefix-a", useUnmergedTree = true).assertTextEquals("~/ ")
        rule.onNodeWithTag("tab-prefix-b", useUnmergedTree = true).assertTextEquals("~/ ")
        assertTrue(rule.onNodeWithTag("tab-cursor-b", useUnmergedTree = true).fetchSemanticsNode().boundsInRoot.width > 0f, "the active tab has the cursor")
        rule.onNodeWithTag("tab-cursor-a", useUnmergedTree = true).assertDoesNotExist()
        val tab = rule.onNodeWithTag("tab-a").getUnclippedBoundsInRoot()
        // Square: the very corner of the tab is the tab's own border or fill, not the bar showing through a rounded corner.
        assertTrue(!sameColour(pixelAt(tab.left.value + .6f, tab.top.value + .6f), look.chrome.tabBar), "square corner")
        // The cursor blinks in the accent colour (the clock is not running here, so it is on).
        val cursor = rule.onNodeWithTag("tab-cursor-b", useUnmergedTree = true).fetchSemanticsNode().boundsInRoot
        val density = rule.density.density
        val inCursor = pixelAt((cursor.left + cursor.right) / 2f / density, (cursor.top + cursor.bottom) / 2f / density)
        assertTrue(sameColour(inCursor, look.colors.ac), "accent cursor")
    }

    @Test
    fun theDashedAccentRuleIsDrawnAsDashesWithOpenGaps() {
        val dashes = checkNotNull(winterLook(HolidayPack.PROGRAMMERS_DAY, HolidayLightness.DARK).theme.tileDividerDashes(true))
        rule.setContent { Box(Modifier.width(60.dp).testTag("rule")) { HolidayDashedDivider(dashes) } }
        val pixels = rule.onNodeWithTag("rule").captureToImage().toPixelMap()
        val density = rule.density.density

        fun at(xDp: Float) = pixels[(xDp * density).toInt(), 0]
        assertEquals(dashes.dash.toArgb(), at(1.5f).toArgb(), "a dash")
        assertEquals(0f, at(4.5f).alpha, "an open gap")
        assertEquals(dashes.dash.toArgb(), at(7.5f).toArgb(), "the next dash")
        assertEquals(pixels.height, density.toInt(), "one dp tall")
    }

    @Test
    fun newYearsEveTabsArePillsWithATwinklingStarOnTheActiveOne() {
        val look = stillLook(HolidayPack.NEW_YEARS_EVE)
        setup(look)
        val idle = rule.onNodeWithTag("tab-a").getUnclippedBoundsInRoot()
        val active = rule.onNodeWithTag("tab-b").getUnclippedBoundsInRoot()
        assertTrue(sameColour(pixelAt(idle.left.value + 1.2f, idle.top.value + 1.2f), look.chrome.tabBar), "the pill's corner is cut away")
        val middle = pixelAt(idle.left.value + idle.width.value / 2f, idle.top.value + idle.height.value / 2f)
        assertTrue(sameColour(middle, look.chrome.tabBg), "and its middle is the tab's fill")
        val star = pixelAt(active.right.value - 5.5f, active.top.value - .5f)
        val gold = abs(star.red - ACCENT_GOLD.red) < STAR_TOLERANCE && abs(star.green - ACCENT_GOLD.green) < STAR_TOLERANCE
        assertTrue(gold, "an accent-gold star on the corner: $star")
        val idleCorner = pixelAt(idle.right.value - 7.5f, idle.top.value - 2.5f)
        assertTrue(idleCorner.green < ACCENT_GOLD.green - STAR_TOLERANCE, "no star on an idle tab: $idleCorner")
    }

    @Test
    fun retroTabsGlowInNeonWithACyanInnerBarOnTheActiveOne() {
        val look = winterLook(HolidayPack.RETRO, HolidayLightness.DARK)
        setup(look)
        val active = rule.onNodeWithTag("tab-b").getUnclippedBoundsInRoot()
        val idle = rule.onNodeWithTag("tab-a").getUnclippedBoundsInRoot()
        val centre = { b: androidx.compose.ui.unit.DpRect -> (b.left.value + b.right.value) / 2f }
        val neon = androidx.compose.ui.graphics.Color(0xFFff4fd8)
        val cyan = androidx.compose.ui.graphics.Color(0xFF00f0ff)
        assertTrue(sameColour(pixelAt(centre(active), active.top.value - .5f), neon), "the neon ring above the active tab")
        assertTrue(sameColour(pixelAt(centre(active), active.bottom.value - 2.5f), cyan), "the cyan bar along its inner edge")
        assertTrue(!sameColour(pixelAt(centre(idle), idle.top.value - .5f), neon), "an idle tab has no ring")
        assertTrue(!sameColour(pixelAt(centre(idle), idle.bottom.value - 2.5f), cyan), "and no bar")
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
        val GOLD = androidx.compose.ui.graphics.Color(0xFFe8b84a)
        val ACCENT_GOLD = androidx.compose.ui.graphics.Color(0xFFf0c75e)
        const val STAR_TOLERANCE = .1f
    }
}
