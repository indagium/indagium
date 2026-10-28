package com.indagium.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.Outline
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.click
import androidx.compose.ui.test.getBoundsInRoot
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performMouseInput
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.DpRect
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.height
import androidx.compose.ui.unit.sp
import androidx.compose.ui.unit.width
import com.indagium.model.LogTab
import com.indagium.model.TabCloseButtonPosition
import com.indagium.model.TabLabelMode
import com.indagium.model.TabSizeMode
import com.indagium.model.TabStripPosition
import com.indagium.model.TabStripStyle
import org.junit.After
import org.junit.Rule
import org.junit.Test
import java.awt.image.BufferedImage
import java.io.File
import javax.imageio.ImageIO
import kotlin.io.path.createTempDirectory
import kotlin.math.abs
import kotlin.math.roundToInt
import kotlin.math.sqrt
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

/**
 * The real [TabBar] in every strip style: tabs, close buttons, tailing dot, strip height, FIT widths and drag reorder, and a pixel
 * check that the default shell draws what it always did.
 */
@OptIn(ExperimentalTestApi::class)
class TabStripStyleUiTest {
    private companion object {
        const val TAB_ACCENT_PIXEL_TOLERANCE = 32
        const val TAB_CAPSULE_SOURCE_PIXEL_TOLERANCE = 12
        const val RAISED_CORNER_RADIUS_DP = 8f
        const val STANDARD_ROUNDED_CORNER_RADIUS_DP = 6f
        const val ACTIVE_CONTOUR_DIAGONAL_FACTOR = 0.7f
        const val HALF_PIXEL_OFFSET = 0.5f
        const val SOURCE_BEND_DEPTH_DP = 5f
        const val ARGB_RED_SHIFT = 16
        const val ARGB_GREEN_SHIFT = 8
        const val ARGB_CHANNEL_MASK = 0xff
        const val LOG_SOURCE_BLUE_MIN = 210
        const val LOG_BLUE_GREEN_GAP_MIN = 20
        const val LOG_GREEN_RED_GAP_MIN = 20
    }

    @get:Rule
    val rule = createComposeRule()

    private lateinit var directory: File
    private lateinit var state: AppState

    @After
    fun tearDown() {
        if (::state.isInitialized) state.close()
        if (::directory.isInitialized) directory.deleteRecursively()
    }

    private fun newState(): AppState {
        directory = createTempDirectory("tab-strip-style-ui").toFile()
        return AppState(
            autosaveFile = File(directory, "state.cache"),
            notesDir = File(directory, "notes"),
            archiveCacheDir = File(directory, "archives"),
            customCommandsDir = File(directory, "commands"),
            autoExportNotes = false,
        )
    }

    private fun tab(id: String, name: String, path: String? = "/logs/$id/$name"): LogTab = mkTab(id, name, emptyList()).copy(sourcePath = path)

    private val defaultTabs = listOf(
        tab("short", "a.log"),
        tab("long", "a-much-longer-file-name-for-fit.log"),
        tab("mid", "middle.log").copy(tailing = true),
    )

    private fun setup(width: Int = 1200, tabs: List<LogTab> = defaultTabs, configure: (AppState) -> Unit = {}) {
        state = newState()
        state.tabs = tabs
        state.activeTabId = tabs.first().id
        configure(state)
        rule.setContent {
            CompositionLocalProvider(LocalTheme provides LIGHT_THEME) {
                Box(Modifier.width(width.dp)) { TabBar(state) }
            }
        }
        rule.waitForIdle()
    }

    private fun bounds(tag: String, useUnmergedTree: Boolean = false): DpRect =
        rule.onNodeWithTag(tag, useUnmergedTree = useUnmergedTree).getBoundsInRoot()

    private fun exists(tag: String): Boolean = rule.onAllNodesWithTag(tag, useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty()

    private fun saveTabContourQaImage(image: ImageBitmap) {
        val directory = File("build/tab-style-qa").apply { mkdirs() }
        val pixels = image.toPixelMap()
        val rendered = BufferedImage(image.width, image.height, BufferedImage.TYPE_INT_ARGB)
        for (y in 0 until image.height) for (x in 0 until image.width) rendered.setRGB(x, y, pixels[x, y].toArgb())
        check(ImageIO.write(rendered, "png", File(directory, "rounded-contours.png")))
    }

    private data class PixelPoint(val x: Int, val y: Int)

    private fun roundedContourRadius(style: TabStripStyle, image: ImageBitmap, density: Float): Float = when (style) {
        TabStripStyle.RAISED -> RAISED_CORNER_RADIUS_DP * density
        TabStripStyle.SEGMENTED, TabStripStyle.OUTLINED -> STANDARD_ROUNDED_CORNER_RADIUS_DP * density
        TabStripStyle.PILL -> minOf(image.width, image.height) / 2f
        else -> error("not a rounded style: $style")
    }

    private fun activeContourPoint(radius: Float, thickness: Float): PixelPoint {
        val innerRadius = radius - thickness / 2f
        val x = radius - innerRadius * ACTIVE_CONTOUR_DIAGONAL_FACTOR
        val y = radius - innerRadius * ACTIVE_CONTOUR_DIAGONAL_FACTOR
        return PixelPoint((x - HALF_PIXEL_OFFSET).roundToInt(), (y - HALF_PIXEL_OFFSET).roundToInt())
    }

    private fun sourceBendPoint(
        position: TabStripPosition,
        width: Int,
        height: Int,
        radius: Float,
        thickness: Float,
        density: Float,
    ): PixelPoint {
        val depth = SOURCE_BEND_DEPTH_DP * density
        val innerRadius = radius - thickness / 2f
        val along = radius - sqrt(innerRadius * innerRadius - (radius - depth) * (radius - depth))
        val depthIndex = (depth - HALF_PIXEL_OFFSET).roundToInt()
        val alongIndex = (along - HALF_PIXEL_OFFSET).roundToInt()
        return when (position) {
            TabStripPosition.TOP -> PixelPoint(alongIndex, depthIndex)
            TabStripPosition.BOTTOM -> PixelPoint(alongIndex, height - depthIndex - 1)
            TabStripPosition.LEFT -> PixelPoint(depthIndex, alongIndex)
            TabStripPosition.RIGHT -> PixelPoint(width - depthIndex - 1, alongIndex)
        }
    }

    private fun activeContourPoint(position: TabStripPosition, width: Int, height: Int, curve: PixelPoint): PixelPoint = when (position) {
        TabStripPosition.TOP -> PixelPoint(curve.x, height - curve.y - 1)
        TabStripPosition.BOTTOM -> curve
        TabStripPosition.LEFT -> PixelPoint(width - curve.y - 1, curve.x)
        TabStripPosition.RIGHT -> PixelPoint(curve.y, curve.x)
    }

    private fun hasLogSourceBlueAround(image: ImageBitmap, point: PixelPoint): Boolean {
        val pixels = image.toPixelMap()
        for (y in point.y - 1..point.y + 1) {
            for (x in point.x - 1..point.x + 1) {
                if (x in 0 until image.width && y in 0 until image.height && hasLogSourceBlue(pixels[x, y].toArgb())) return true
            }
        }
        return false
    }

    private fun hasColorNear(image: ImageBitmap, point: PixelPoint, target: Int, tolerance: Int): Boolean {
        val pixels = image.toPixelMap()
        val targetRed = target shr ARGB_RED_SHIFT and ARGB_CHANNEL_MASK
        val targetGreen = target shr ARGB_GREEN_SHIFT and ARGB_CHANNEL_MASK
        val targetBlue = target and ARGB_CHANNEL_MASK
        for (y in point.y - 1..point.y + 1) {
            for (x in point.x - 1..point.x + 1) {
                if (x !in 0 until image.width || y !in 0 until image.height) continue
                val color = pixels[x, y].toArgb()
                val red = color shr ARGB_RED_SHIFT and ARGB_CHANNEL_MASK
                val green = color shr ARGB_GREEN_SHIFT and ARGB_CHANNEL_MASK
                val blue = color and ARGB_CHANNEL_MASK
                if (abs(red - targetRed) <= tolerance &&
                    abs(green - targetGreen) <= tolerance &&
                    abs(blue - targetBlue) <= tolerance
                ) return true
            }
        }
        return false
    }

    private fun straightContourPoint(position: TabStripPosition, width: Int, height: Int): PixelPoint = when (position) {
        TabStripPosition.TOP -> PixelPoint(width / 2, 1)
        TabStripPosition.BOTTOM -> PixelPoint(width / 2, height - 2)
        TabStripPosition.LEFT -> PixelPoint(1, height / 2)
        TabStripPosition.RIGHT -> PixelPoint(width - 2, height / 2)
    }

    private fun hasLogSourceBlue(argb: Int): Boolean {
        val red = argb shr ARGB_RED_SHIFT and ARGB_CHANNEL_MASK
        val green = argb shr ARGB_GREEN_SHIFT and ARGB_CHANNEL_MASK
        val blue = argb and ARGB_CHANNEL_MASK
        return blue >= LOG_SOURCE_BLUE_MIN &&
            blue - green >= LOG_BLUE_GREEN_GAP_MIN &&
            green - red >= LOG_GREEN_RED_GAP_MIN
    }

    private fun raisedRoundedCorner(position: TabStripPosition, width: Int, height: Int): PixelPoint = when (position) {
        TabStripPosition.TOP, TabStripPosition.LEFT -> PixelPoint(0, 0)
        TabStripPosition.BOTTOM -> PixelPoint(0, height - 1)
        TabStripPosition.RIGHT -> PixelPoint(width - 1, 0)
    }

    private fun assertRoundedContourPixels(
        style: TabStripStyle,
        position: TabStripPosition,
        image: ImageBitmap,
        density: Float,
        blue: Int,
        accent: Int,
    ) {
        val pixels = image.toPixelMap()
        val radius = roundedContourRadius(style, image, density)
        val sourceBend = sourceBendPoint(position, image.width, image.height, radius, 3f * density, density)
        val activeCurve = activeContourPoint(radius, 2f * density)
        val active = activeContourPoint(position, image.width, image.height, activeCurve)
        val behind = tabStripBackground(style, LIGHT_THEME).toArgb()
        val straight = straightContourPoint(position, image.width, image.height)
        val sourcePixel = pixels[straight.x, straight.y].toArgb()
        val capsuleSide = style == TabStripStyle.PILL && (position == TabStripPosition.LEFT || position == TabStripPosition.RIGHT)
        assertTrue(
            sourcePixel == blue || (capsuleSide && hasColorNear(image, straight, blue, TAB_CAPSULE_SOURCE_PIXEL_TOLERANCE)),
            "$style on $position: source color is present inside the outer edge band",
        )
        assertTrue(
            hasLogSourceBlueAround(image, sourceBend),
            "$style on $position: source blue reaches 5 dp into the rounded corner, beyond a straight 3 dp band",
        )
        if (style == TabStripStyle.OUTLINED || style == TabStripStyle.PILL) {
            val activePixel = pixels[active.x, active.y].toArgb()
            assertTrue(
                activePixel == accent || hasColorNear(image, active, accent, TAB_ACCENT_PIXEL_TOLERANCE),
                "$style on $position: active accent follows the content curve, allowing only an antialiased edge blend",
            )
        }
        val corner = if (style == TabStripStyle.RAISED) raisedRoundedCorner(position, image.width, image.height) else PixelPoint(0, 0)
        assertEquals(behind, pixels[corner.x, corner.y].toArgb(), "$style on $position: a genuinely rounded outer corner remains outside the stripe")
    }

    @Composable
    private fun roundedContourPreview(styles: List<TabStripStyle>, withGroupIndicator: Boolean = false) {
        CompositionLocalProvider(LocalTheme provides LIGHT_THEME) {
            Column(Modifier.width(720.dp).testTag("tab-rounded-contour-preview")) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    AppText("Style", color = LIGHT_THEME.ts, fontSize = 9.sp, modifier = Modifier.width(54.dp))
                    TabStripPosition.entries.forEach { position ->
                        AppText(position.name, color = LIGHT_THEME.ts, fontSize = 9.sp, modifier = Modifier.width(160.dp))
                    }
                }
                styles.forEach { style ->
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        AppText(style.name, color = LIGHT_THEME.ts, fontSize = 9.sp, modifier = Modifier.width(54.dp))
                        TabStripPosition.entries.forEach { position ->
                            val key = "${style.name}-${position.name}"
                            val group = TabGroup(
                                id = "contour-group",
                                name = "Group",
                                color = TabGroupColor.GREEN,
                                collapsed = false,
                                members = listOf(TabRef.Log("contour")),
                            )
                            Box(Modifier.width(160.dp).background(tabStripBackground(style, LIGHT_THEME))) {
                                TabShell(
                                    pointerKey = key,
                                    label = "contour.log",
                                    tooltip = "contour.log",
                                    isActive = true,
                                    showClose = false,
                                    onClick = {},
                                    onClose = {},
                                    groupIndicator = if (withGroupIndicator) {
                                        { TabGroupHeaderIcon(group, testTagValue = "group-indicator-$key") }
                                    } else {
                                        null
                                    },
                                    appearance = TabAppearance(
                                        style = style,
                                        showStripe = true,
                                        position = position,
                                        groupIndicatorWidthDp = if (withGroupIndicator) tabGroupIndicatorWidthDp(group) else 0f,
                                    ),
                                )
                            }
                        }
                    }
                }
            }
        }
    }

    private fun assertRoundedContours(styles: List<TabStripStyle>, density: Float, blue: Int, accent: Int) {
        styles.forEach { style ->
            TabStripPosition.entries.forEach { position ->
                assertRoundedContourPixels(
                    style = style,
                    position = position,
                    image = rule.onNodeWithTag("tab-${style.name}-${position.name}").captureToImage(),
                    density = density,
                    blue = blue,
                    accent = accent,
                )
            }
        }
    }

    private fun style(style: TabStripStyle) {
        state.updateSettings { it.copy(tabStripStyle = style) }
        rule.waitForIdle()
    }

    @Test
    fun everyStyleRendersTheTabsTheirCloseButtonsAndTheTailingDot() {
        setup()
        TabStripStyle.entries.forEach { s ->
            style(s)
            defaultTabs.forEach { t ->
                assertTrue(exists("tab-${t.id}"), "$s: tab ${t.id}")
                assertTrue(exists("tab-close-${t.id}"), "$s: close button of ${t.id}")
            }
            assertTrue(exists("tab-tailing-mid"), "$s: tailing dot")
            assertFalse(exists("tab-tailing-short"), "$s: only the tailing tab has the dot")
        }
    }

    @Test
    fun closeButtonPositionWrapsLeadingContentAndBadgeAcrossAllStylesAndStripEdges() {
        val cases = TabStripStyle.entries.flatMap { style -> TabStripPosition.entries.map { style to it } }
        rule.setContent {
            CompositionLocalProvider(LocalTheme provides LIGHT_THEME) {
                Column {
                    TabStripStyle.entries.forEach { style ->
                        Row {
                            TabStripPosition.entries.forEach { position ->
                                Column(Modifier.width(220.dp)) {
                                    TabCloseButtonPosition.entries.forEach { closePosition ->
                                        val key = "${style.name}-${position.name}-${closePosition.name}"
                                        Box(Modifier.width(220.dp).background(tabStripBackground(style, LIGHT_THEME))) {
                                            TabShell(
                                                pointerKey = key,
                                                label = "session.log",
                                                tooltip = "session.log",
                                                isActive = false,
                                                showClose = true,
                                                leading = { Box(Modifier.size(8.dp).testTag("tab-leading-$key")) },
                                                onClick = {},
                                                onClose = {},
                                                appearance = TabAppearance(
                                                    style = style,
                                                    showBadge = true,
                                                    closeAlways = true,
                                                    position = position,
                                                    closeButtonPosition = closePosition,
                                                ),
                                            )
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
        rule.waitForIdle()

        cases.forEach { (style, position) ->
            TabCloseButtonPosition.entries.forEach { closePosition ->
                val key = "${style.name}-${position.name}-${closePosition.name}"
                val close = bounds("tab-close-$key", useUnmergedTree = true)
                val label = bounds("tab-label-$key", useUnmergedTree = true)
                val leading = bounds("tab-leading-$key", useUnmergedTree = true)
                val badge = bounds("tab-badge-$key", useUnmergedTree = true)
                if (closePosition == TabCloseButtonPosition.LEFT) {
                    assertTrue(close.right <= leading.left, "$style on $position places LEFT close before leading content")
                    assertTrue(close.right <= badge.left, "$style on $position places LEFT close before the badge")
                    assertTrue(close.right <= label.left, "$style on $position places LEFT close before the label")
                } else {
                    assertTrue(label.right <= close.left, "$style on $position places RIGHT close after the label")
                    assertTrue(badge.right <= close.left, "$style on $position places RIGHT close after the badge")
                }
            }
        }
    }

    @Test
    fun theStripHeightFollowsTheStyle() {
        setup()
        assertEquals(36.dp, bounds("tab-strip").height)
        style(TabStripStyle.COMPACT)
        assertEquals(28.dp, bounds("tab-strip").height)
        style(TabStripStyle.TWO_LINE)
        assertEquals(44.dp, bounds("tab-strip").height)
        style(TabStripStyle.RAISED)
        assertEquals(36.dp, bounds("tab-strip").height)
    }

    @Test
    fun clickingATabInAnyStyleActivatesItAndTheCloseButtonClosesIt() {
        setup()
        TabStripStyle.entries.forEach { s ->
            style(s)
            rule.onNodeWithTag("tab-long").performClick()
            rule.waitForIdle()
            assertEquals("long", state.activeTabId, "$s: click activates")
            state.activateTab("short")
            rule.waitForIdle()
        }
        style(TabStripStyle.SEGMENTED)
        rule.onNodeWithTag("tab-close-long").performClick()
        rule.waitForIdle()
        assertEquals(listOf("short", "mid"), state.tabs.map { it.id })
    }

    @Test
    fun fixedTabsShareTheStripEquallyAndFitTabsFollowTheirLabel() {
        setup()
        val fixed = defaultTabs.map { bounds("tab-${it.id}").width }
        assertEquals(fixed[0], fixed[1])
        assertEquals(fixed[1], fixed[2])
        state.updateSettings { it.copy(tabSizeMode = TabSizeMode.FIT) }
        rule.waitForIdle()
        val fit = defaultTabs.associate { it.id to bounds("tab-${it.id}").width }
        assertTrue(fit.getValue("long") > fit.getValue("mid"), "the long name is wider than the mid one: $fit")
        assertTrue(fit.getValue("mid") > fit.getValue("short"), "the mid name (with the dot) is wider than the short one: $fit")
        // Laid out one after another from the strip's start.
        assertEquals(bounds("tab-short").right, bounds("tab-long").left)
        assertEquals(bounds("tab-long").right, bounds("tab-mid").left)
        assertEquals(0.dp, bounds("tab-short").left)
    }

    // Press, drag by [dx] px, let the strip recompose (a real drag has frames between moves), then release.
    private fun dragShort(dx: Float) {
        rule.onNodeWithTag("tab-short").performTouchInput {
            down(center)
            moveBy(Offset(20f, 0f))
            moveBy(Offset(dx - 20f, 0f))
        }
        rule.waitForIdle()
        rule.onNodeWithTag("tab-short").performTouchInput { up() }
        rule.waitForIdle()
    }

    @Test
    fun aFitDragReorderCommitsTheRightOrder() {
        setup(configure = { it.updateSettings { s -> s.copy(tabSizeMode = TabSizeMode.FIT) } })
        val d = rule.density.density
        assertEquals(listOf("short", "long", "mid"), state.tabs.map { it.id })
        // Dragging the narrow first tab only a little leaves the order alone.
        dragShort(40f * d)
        assertEquals(listOf("short", "long", "mid"), state.tabs.map { it.id })
        // Far enough to pass the wide tab's centre but not the third's: it lands second.
        val widthShort = bounds("tab-short").width.value
        val widthLong = bounds("tab-long").width.value
        val pastLongCentre = (widthShort / 2f + widthShort * 0.25f) // start centre + bias
        val dx = (widthShort + widthLong / 2f - pastLongCentre + 10f) * d
        dragShort(dx)
        assertEquals(listOf("long", "short", "mid"), state.tabs.map { it.id })
        // The dragged tab now sits second, its neighbours in the new order.
        assertTrue(bounds("tab-long").right <= bounds("tab-short").left + 1.dp)
        assertTrue(bounds("tab-short").right <= bounds("tab-mid").left + 1.dp)
    }

    @Test
    fun theDetailCheckboxesAddABadgeAndAStripeToAnyStyleAndTheNamedStylesImplyThem() {
        setup()
        assertFalse(exists("tab-badge-short"))
        assertFalse(exists("tab-stripe-short"))
        state.updateSettings { it.copy(tabShowTypeBadge = true, tabShowSourceStripe = true) }
        rule.waitForIdle()
        defaultTabs.forEach {
            assertTrue(exists("tab-badge-${it.id}"))
            assertTrue(exists("tab-stripe-${it.id}"))
        }
        state.updateSettings { it.copy(tabShowTypeBadge = false, tabShowSourceStripe = false, tabStripStyle = TabStripStyle.TYPED) }
        rule.waitForIdle()
        assertTrue(exists("tab-badge-short"))
        assertFalse(exists("tab-stripe-short"))
        style(TabStripStyle.SOURCE_STRIPE)
        assertFalse(exists("tab-badge-short"))
        assertTrue(exists("tab-stripe-short"))
    }

    @Test
    fun closeOnEveryTabOffKeepsTheButtonOnTheActiveTabOnly() {
        setup()
        state.updateSettings { it.copy(tabCloseOnEvery = false) }
        rule.waitForIdle()
        assertTrue(exists("tab-close-short"), "the active tab keeps its close button")
        assertFalse(exists("tab-close-long"))
        assertFalse(exists("tab-close-mid"))
    }

    @Test
    fun hoverRevealsTheReservedCloseSlotWithoutMovingTheLabelOnEitherSide() {
        val cases = TabCloseButtonPosition.entries
        rule.setContent {
            CompositionLocalProvider(LocalTheme provides LIGHT_THEME) {
                Column {
                    cases.forEach { position ->
                        val key = "hover-${position.name}"
                        Box(Modifier.width(280.dp)) {
                            TabShell(
                                pointerKey = key,
                                label = "inactive.log",
                                tooltip = "inactive.log",
                                isActive = false,
                                showClose = true,
                                onClick = {},
                                onClose = {},
                                appearance = TabAppearance(
                                    closeAlways = false,
                                    closeButtonPosition = position,
                                ),
                            )
                        }
                    }
                }
            }
        }
        rule.waitForIdle()

        cases.forEach { position ->
            val key = "hover-${position.name}"
            assertFalse(exists("tab-close-$key"), "$position: inactive close starts hidden")
            assertTrue(exists("tab-close-slot-$key"), "$position: its hitbox space stays reserved")
            val reservedSlot = bounds("tab-close-slot-$key", useUnmergedTree = true)
            assertEquals(24.dp, reservedSlot.width, "$position: the hidden slot reserves a full hitbox")
            val before = bounds("tab-label-$key", useUnmergedTree = true)
            rule.onNodeWithTag("tab-$key").performMouseInput { moveTo(center) }
            rule.waitForIdle()
            assertTrue(exists("tab-close-$key"), "$position: hover reveals the close button")
            val close = bounds("tab-close-$key", useUnmergedTree = true)
            assertEquals(reservedSlot.left, close.left, "$position: the button appears in its reserved slot")
            assertEquals(reservedSlot.right, close.right, "$position: the button keeps the reserved slot's width")
            val after = bounds("tab-label-$key", useUnmergedTree = true)
            assertEquals(before.left, after.left, "$position: label keeps its left edge")
            assertEquals(before.right, after.right, "$position: label keeps its right edge")
            assertEquals(before.width, after.width, "$position: label keeps its width")
        }
    }

    private fun hasLabel(text: String): Boolean =
        rule.onAllNodesWithText(text, useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty()

    @Test
    fun labelModesChangeTheTabText() {
        setup(tabs = listOf(tab("one", "same.log", "/logs/first/same.log"), tab("two", "same.log", "/logs/second/same.log")))
        assertTrue(hasLabel("same.log — first") && hasLabel("same.log — second"))
        state.updateSettings { it.copy(tabLabelMode = TabLabelMode.SESSION_ONLY) }
        rule.waitForIdle()
        assertTrue(hasLabel("first") && hasLabel("second"))
        assertFalse(hasLabel("same.log — first"))
        state.updateSettings { it.copy(tabLabelMode = TabLabelMode.FILE_ONLY) }
        rule.waitForIdle()
        assertEquals(2, rule.onAllNodesWithText("same.log", useUnmergedTree = true).fetchSemanticsNodes().size)
    }

    @Test
    fun theDefaultStyleKeepsTodaysGeometry() {
        setup()
        val area = defaultTabs.map { bounds("tab-${it.id}") }
        // Three equal tabs filling the area left of the toolbar, each as tall as the strip's content (36 - 2 dp padding).
        assertEquals(area[0].width, area[1].width)
        assertEquals(area[1].width, area[2].width)
        assertEquals(34.dp, area[0].height)
        assertEquals(area[0].right, area[1].left)
        assertEquals(area[1].right, area[2].left)
        assertEquals(36.dp, bounds("tab-strip").height)
    }

    // The shell as it was before styles existed, copied verbatim (minus test tags), to compare pixels with.
    @Composable
    private fun LegacyTabShell(label: String, isActive: Boolean, leading: (@Composable () -> Unit)?) {
        val tc = tc()
        val accent = tc.ac
        Row(
            Modifier.fillMaxWidth().height(36.dp)
                .background(if (isActive) tc.bg else tc.p2)
                .border(BorderStroke(1.dp, tc.br.copy(alpha = 0.95f)))
                .drawBehind {
                    if (isActive) {
                        val stroke = 2.dp.toPx()
                        drawRect(
                            color = accent,
                            topLeft = Offset(0f, size.height - stroke),
                            size = androidx.compose.ui.geometry.Size(size.width, stroke),
                        )
                    }
                }
                .clickable {}.padding(start = 12.dp, end = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(7.dp),
        ) {
            leading?.invoke()
            Box(Modifier.weight(1f)) {
                AppText(label, color = if (isActive) tc.tx else tc.ts, fontSize = 12.sp, fontFamily = MONO, overflow = TextOverflow.Ellipsis, maxLines = 1)
            }
            CloseButton(onClick = {})
        }
    }

    @Test
    fun theDefaultShellPaintsTheSamePixelsAsTheShellBeforeStyles() {
        directory = createTempDirectory("tab-strip-style-pixels").toFile()
        rule.setContent {
            CompositionLocalProvider(LocalTheme provides LIGHT_THEME) {
                Column(Modifier.width(300.dp).padding(8.dp)) {
                    listOf(true, false).forEach { active ->
                        Box(Modifier.width(240.dp).testTag("new-$active")) {
                            TabShell(
                                pointerKey = "k$active", label = "pixel-check.log", tooltip = "t", isActive = active,
                                showClose = true, onClick = {}, onClose = {},
                            )
                        }
                        Box(Modifier.width(240.dp).testTag("old-$active")) { LegacyTabShell("pixel-check.log", active, null) }
                    }
                }
            }
        }
        rule.waitForIdle()
        listOf(true, false).forEach { active ->
            val new = rule.onNodeWithTag("new-$active").captureToImage().toPixelMap()
            val old = rule.onNodeWithTag("old-$active").captureToImage().toPixelMap()
            assertEquals(old.width, new.width)
            assertEquals(old.height, new.height)
            var differing = 0
            for (x in 0 until old.width) for (y in 0 until old.height) if (old[x, y] != new[x, y]) differing++
            assertEquals(0, differing, "active=$active: the default shell differs from the old one in $differing pixels")
        }
    }

    @Test
    fun aNonDefaultStyleDoesPaintDifferently() {
        directory = createTempDirectory("tab-strip-style-pixels2").toFile()
        rule.setContent {
            CompositionLocalProvider(LocalTheme provides LIGHT_THEME) {
                Column(Modifier.width(300.dp)) {
                    Box(Modifier.width(240.dp).testTag("a")) {
                        TabShell(pointerKey = "a", label = "x.log", tooltip = "t", isActive = true, showClose = true, onClick = {}, onClose = {})
                    }
                    Box(Modifier.width(240.dp).testTag("b")) {
                        TabShell(
                            pointerKey = "b", label = "x.log", tooltip = "t", isActive = true, showClose = true, onClick = {}, onClose = {},
                            appearance = TabAppearance(style = TabStripStyle.RAISED),
                        )
                    }
                }
            }
        }
        rule.waitForIdle()
        val a = rule.onNodeWithTag("a").captureToImage().toPixelMap()
        val b = rule.onNodeWithTag("b").captureToImage().toPixelMap()
        var differing = 0
        for (x in 0 until minOf(a.width, b.width)) for (y in 0 until minOf(a.height, b.height)) if (a[x, y] != b[x, y]) differing++
        assertNotEquals(0, differing)
    }

    @Test
    fun theSettingsSubsectionPicksTheStyleAndLocksTheDetailTheStyleImplies() {
        state = newState()
        rule.setContent {
            CompositionLocalProvider(LocalTheme provides LIGHT_THEME) {
                Column(Modifier.width(700.dp)) { TabsAppearanceSettings(state) }
            }
        }
        rule.waitForIdle()
        TabStripStyle.entries.forEach { assertTrue(exists("tab-style-${it.name}"), "thumbnail of $it") }
        rule.onNodeWithTag("tab-style-RAISED").performClick()
        rule.waitForIdle()
        assertEquals(TabStripStyle.RAISED, state.settings.tabStripStyle)
        rule.onNodeWithTag("tab-detail-file-type-badge", useUnmergedTree = true).performClick()
        rule.waitForIdle()
        assertTrue(state.settings.tabShowTypeBadge)
        rule.onNodeWithTag("tab-style-TYPED").performClick()
        rule.waitForIdle()
        // TYPED always shows the badge: the checkbox is locked, so a click leaves the stored value alone.
        rule.onNodeWithTag("tab-detail-file-type-badge", useUnmergedTree = true).performClick()
        rule.waitForIdle()
        assertTrue(state.settings.tabShowTypeBadge)
        rule.onNodeWithText("Fit label").performClick()
        rule.waitForIdle()
        assertEquals(TabSizeMode.FIT, state.settings.tabSizeMode)
        rule.onNodeWithText("Folder").performClick()
        rule.waitForIdle()
        assertEquals(TabLabelMode.SESSION_ONLY, state.settings.tabLabelMode)
        rule.onNodeWithTag("tab-detail-close-button-on-every-tab", useUnmergedTree = true).performClick()
        rule.waitForIdle()
        assertFalse(state.settings.tabCloseOnEvery)
        val closePositionControl = rule.onNodeWithTag(TAB_CLOSE_BUTTON_POSITION_CONTROL_TAG)
        closePositionControl.performTouchInput { click(Offset(5f, center.y)) }
        rule.waitForIdle()
        assertEquals(TabCloseButtonPosition.LEFT, state.settings.tabCloseButtonPosition)
        val controlWidthPx = with(rule.density) { closePositionControl.getBoundsInRoot().width.toPx() }
        closePositionControl.performTouchInput { click(Offset(controlWidthPx - 5f, center.y)) }
        rule.waitForIdle()
        assertEquals(TabCloseButtonPosition.RIGHT, state.settings.tabCloseButtonPosition)
    }

    @Test
    fun theTenStylePickerUsesFiveColumnsWheneverTheAvailableWidthAllowsIt() {
        state = newState()
        listOf(700, 480, 1100).forEach { width ->
            rule.setContent {
                CompositionLocalProvider(LocalTheme provides LIGHT_THEME) {
                    Column(Modifier.width(width.dp)) { TabsAppearanceSettings(state) }
                }
            }
            rule.waitForIdle()
            val styles = TabStripStyle.entries
            assertEquals(10, styles.size)
            val firstRow = styles.take(5).map { bounds("tab-style-${it.name}") }
            val secondRow = styles.drop(5).map { bounds("tab-style-${it.name}") }
            val firstRowLabels = styles.take(5).map { bounds("tab-style-label-${it.name}") }
            val grid = bounds(TAB_STYLE_GRID_TAG)
            assertTrue(firstRow.all { it.top == firstRow.first().top }, "first five share a row at $width dp: $firstRow")
            assertTrue(secondRow.all { it.top == secondRow.first().top }, "last five share a row at $width dp: $secondRow")
            assertTrue(secondRow.first().top > firstRow.first().top, "the tenth style starts the second row at $width dp")
            assertTrue(firstRow.zipWithNext().all { (left, right) -> left.left < right.left })
            assertEquals(grid.left, firstRow.first().left, "first preview reaches the left grid edge at $width dp")
            assertEquals(grid.right, firstRow.last().right, "fifth preview reaches the right grid edge at $width dp")
            firstRow.zip(firstRowLabels).forEach { (preview, label) ->
                assertEquals(preview.width, label.width, "preview and caption use equal full-width columns at $width dp")
            }
        }

        rule.setContent {
            CompositionLocalProvider(LocalTheme provides LIGHT_THEME) {
                Column(Modifier.width(420.dp)) { TabsAppearanceSettings(state) }
            }
        }
        rule.waitForIdle()
        val fitsFour = TabStripStyle.entries.take(4).map { bounds("tab-style-${it.name}") }
        val narrowGrid = bounds(TAB_STYLE_GRID_TAG)
        assertTrue(fitsFour.all { it.top == fitsFour.first().top })
        assertEquals(narrowGrid.left, fitsFour.first().left)
        assertEquals(narrowGrid.right, fitsFour.last().right)
        assertTrue(bounds("tab-style-${TabStripStyle.COMPACT.name}").top > fitsFour.first().top)
        val firstCellWidth = fitsFour.first().width
        TabStripStyle.entries.forEach { style ->
            assertEquals(firstCellWidth, bounds("tab-style-${style.name}").width, "$style keeps the same narrow-grid column width")
            assertEquals(
                firstCellWidth,
                bounds("tab-style-label-${style.name}").width,
                "$style caption spans the same narrow-grid column",
            )
        }
    }

    @Test
    fun closeButtonHoverBackgroundFollowsRoundedTabStyleOnEveryStripEdge() {
        val roundedStyles = listOf(TabStripStyle.RAISED, TabStripStyle.SEGMENTED, TabStripStyle.OUTLINED, TabStripStyle.PILL)
        val cases = roundedStyles.flatMap { style -> TabStripPosition.entries.map { position -> style to position } } +
            (TabStripStyle.CURRENT to TabStripPosition.TOP)
        rule.setContent {
            CompositionLocalProvider(LocalTheme provides LIGHT_THEME) {
                Column(Modifier.width(720.dp)) {
                    cases.forEach { (style, position) ->
                        Box(Modifier.width(180.dp).background(tabStripBackground(style, LIGHT_THEME))) {
                            TabShell(
                                pointerKey = "close-hover-${style.name}-${position.name}",
                                label = "close.log",
                                tooltip = "close.log",
                                isActive = true,
                                showClose = true,
                                onClick = {},
                                onClose = {},
                                appearance = TabAppearance(style = style, position = position),
                            )
                        }
                    }
                }
            }
        }
        rule.waitForIdle()
        val density = rule.density.density
        cases.forEach { (style, position) ->
            val tabNode = rule.onNodeWithTag("tab-close-hover-${style.name}-${position.name}")
            val node = rule.onNodeWithTag("tab-close-close-hover-${style.name}-${position.name}")
            val bounds = node.getBoundsInRoot()
            assertEquals(24.dp, bounds.width, "$style on $position keeps the close hitbox width")
            assertEquals(24.dp, bounds.height, "$style on $position keeps the close hitbox height")

            tabNode.performMouseInput { moveTo(Offset(center.x / 2f, center.y)) }
            rule.waitForIdle()
            val before = node.captureToImage().toPixelMap()
            node.performMouseInput { moveTo(center) }
            rule.waitForIdle()
            val hovered = node.captureToImage().toPixelMap()
            val centerProbe = PixelPoint(hovered.width / 2, (4f * density).roundToInt())
            assertNotEquals(
                before[centerProbe.x, centerProbe.y],
                hovered[centerProbe.x, centerProbe.y],
                "$style on $position paints the hover background inside the close button",
            )

            val corner = closeButtonHoverProbe(style, position, hovered.width, hovered.height)
            val beforeCorner = before[corner.x, corner.y]
            val hoveredCorner = hovered[corner.x, corner.y]
            if (style == TabStripStyle.CURRENT) {
                assertNotEquals(beforeCorner, hoveredCorner, "the classic style keeps its existing close-button corner fill")
            } else {
                assertEquals(beforeCorner, hoveredCorner, "$style on $position keeps the rounded hover corner clear")
            }
        }
    }

    private fun closeButtonHoverProbe(
        style: TabStripStyle,
        position: TabStripPosition,
        width: Int,
        height: Int,
    ): PixelPoint {
        if (style == TabStripStyle.CURRENT) return PixelPoint(width / 2, 0)
        if (style != TabStripStyle.RAISED) return PixelPoint(0, 0)
        return when (position) {
            TabStripPosition.TOP, TabStripPosition.LEFT -> PixelPoint(0, 0)
            TabStripPosition.BOTTOM -> PixelPoint(0, height - 1)
            TabStripPosition.RIGHT -> PixelPoint(width - 1, 0)
        }
    }

    // The Tab label control: the four options, the one-line hint under it and every option fully inside the control at the widths of the
    // settings dialog's content column (about 650 dp) and well below it.
    @Test
    fun theTabLabelControlNamesTheFolderOptionsExplainsTheDefaultAndDoesNotClip() {
        listOf(700, 480, 420).forEach { width ->
            state = newState()
            rule.setContent {
                CompositionLocalProvider(LocalTheme provides LIGHT_THEME) {
                    Column(Modifier.width(width.dp)) { TabsAppearanceSettings(state) }
                }
            }
            rule.waitForIdle()
            val labels = listOf("File (+ folder if same name)", "Folder", "File only", "File — folder")
            assertEquals(labels, TabLabelMode.entries.map(::tabLabelModeLabel))
            val control = rule.onNodeWithTag(TAB_LABEL_CONTROL_TAG).getBoundsInRoot()
            labels.forEach { label ->
                val text = rule.onNodeWithText(label).getUnclippedBoundsInRoot()
                assertTrue(text.left >= control.left && text.right <= control.right, "$label is inside the control at $width dp: $text in $control")
                assertTrue(text.right <= width.dp, "$label is inside the column at $width dp")
            }
            assertTrue(control.right <= width.dp, "the control fits a $width dp column: $control")
            val hint = rule.onNodeWithTag(TAB_LABEL_HINT_TAG).getBoundsInRoot()
            assertTrue(hint.top >= control.bottom, "the hint sits under the control")
            rule.onNodeWithTag(TAB_LABEL_HINT_TAG).assertTextEquals("The folder is added when two open tabs have the same file name.")
            // Choosing the new option stores it, and the highlight follows.
            rule.onNodeWithText("File — folder").performClick()
            rule.waitForIdle()
            assertEquals(TabLabelMode.FILE_FOLDER_ALWAYS, state.settings.tabLabelMode)
            rule.onNodeWithText("File (+ folder if same name)").performClick()
            rule.waitForIdle()
            assertEquals(TabLabelMode.FILE_SESSION, state.settings.tabLabelMode)
        }
    }

    // ---- the selection, hover and focus highlights follow the tab's own shape -------------------------------------------------------------------

    private fun roundedCorners(shape: Shape, width: Float, height: Float): Set<String> {
        val outline = shape.createOutline(Size(width, height), LayoutDirection.Ltr, Density(1f))
        val r = (outline as? Outline.Rounded)?.roundRect ?: return emptySet()
        return buildSet {
            if (r.topLeftCornerRadius.x > 0f) add("tl")
            if (r.topRightCornerRadius.x > 0f) add("tr")
            if (r.bottomLeftCornerRadius.x > 0f) add("bl")
            if (r.bottomRightCornerRadius.x > 0f) add("br")
        }
    }

    @Test
    fun theTabShapesAreTheCardsOpenSidedShapeThePillAndARectangleForTheRest() {
        assertEquals(RectangleShape, tabShape(TabStripStyle.CURRENT, TabStripPosition.TOP))
        val raised = mapOf(
            TabStripPosition.TOP to RoundedCornerShape(topStart = 8.dp, topEnd = 8.dp),
            TabStripPosition.BOTTOM to RoundedCornerShape(bottomStart = 8.dp, bottomEnd = 8.dp),
            TabStripPosition.LEFT to RoundedCornerShape(topStart = 8.dp, bottomStart = 8.dp),
            TabStripPosition.RIGHT to RoundedCornerShape(topEnd = 8.dp, bottomEnd = 8.dp),
        )
        raised.forEach { (position, shape) -> assertEquals(shape, tabShape(TabStripStyle.RAISED, position), "raised on $position") }
        TabStripPosition.entries.forEach { assertEquals(RoundedCornerShape(6.dp), tabShape(TabStripStyle.SEGMENTED, it)) }
        TabStripPosition.entries.forEach { assertEquals(RoundedCornerShape(6.dp), tabShape(TabStripStyle.OUTLINED, it)) }
        TabStripPosition.entries.forEach { assertEquals(RoundedCornerShape(50), tabShape(TabStripStyle.PILL, it)) }
        listOf(TabStripStyle.TYPED, TabStripStyle.COMPACT, TabStripStyle.SOURCE_STRIPE, TabStripStyle.TWO_LINE, TabStripStyle.QUIET).forEach { style ->
            TabStripPosition.entries.forEach { assertEquals(RectangleShape, tabShape(style, it), "$style") }
        }
    }

    @Test
    fun sourceAndActiveAccentsFollowRoundedContoursOnEveryEdge() {
        directory = createTempDirectory("tab-strip-contour-pixels").toFile()
        val roundedStyles = listOf(TabStripStyle.RAISED, TabStripStyle.SEGMENTED, TabStripStyle.OUTLINED, TabStripStyle.PILL)
        rule.setContent { roundedContourPreview(roundedStyles) }
        rule.waitForIdle()
        saveTabContourQaImage(rule.onNodeWithTag("tab-rounded-contour-preview").captureToImage())

        val density = rule.density.density
        val blue = tabSourceColor(TabKind.LOG, LIGHT_THEME).toArgb()
        val accent = LIGHT_THEME.ac.toArgb()
        assertRoundedContours(roundedStyles, density, blue, accent)
    }

    @Test
    fun groupIndicatorStaysInsideRoundedTabShapeAndLeavesExistingAccentsVisible() {
        directory = createTempDirectory("tab-strip-group-indicator-shapes").toFile()
        val roundedStyles = listOf(TabStripStyle.RAISED, TabStripStyle.SEGMENTED, TabStripStyle.OUTLINED, TabStripStyle.PILL)
        rule.setContent { roundedContourPreview(roundedStyles, withGroupIndicator = true) }
        rule.waitForIdle()

        val density = rule.density.density
        val blue = tabSourceColor(TabKind.LOG, LIGHT_THEME).toArgb()
        val accent = LIGHT_THEME.ac.toArgb()
        assertRoundedContours(roundedStyles, density, blue, accent)
        roundedStyles.forEach { style ->
            TabStripPosition.entries.forEach { position ->
                val key = "${style.name}-${position.name}"
                val tab = rule.onNodeWithTag("tab-$key").getBoundsInRoot()
                val marker = rule.onNodeWithTag("group-indicator-$key", useUnmergedTree = true).getBoundsInRoot()
                assertTrue(marker.left >= tab.left && marker.right <= tab.right, "$style on $position keeps the group marker inside the tab width")
                assertTrue(marker.top >= tab.top && marker.bottom <= tab.bottom, "$style on $position keeps the group marker inside the tab height")
            }
        }
    }

    // A tab under the mouse (the hover highlight of its click), selected or not, in every style on every edge: the rounded corners of its shape
    // stay the strip's own colour, so nothing lights up as a rectangle behind the card or the pill.
    @Test
    fun hoveringATabNeverTintsTheCornersItsShapeCutsOffInAnyStyleOrPosition() {
        directory = createTempDirectory("tab-strip-style-shape").toFile()
        TabStripPosition.entries.forEach { position ->
            listOf(true, false).forEach { active ->
                rule.setContent {
                    CompositionLocalProvider(LocalTheme provides LIGHT_THEME) {
                        Column(Modifier.width(300.dp)) {
                            TabStripStyle.entries.forEach { style ->
                                Box(Modifier.width(240.dp).background(tabStripBackground(style, LIGHT_THEME))) {
                                    TabShell(
                                        pointerKey = style.name, label = "shape.log", tooltip = "t", isActive = active, showClose = true,
                                        onClick = {}, onClose = {}, appearance = TabAppearance(style = style, position = position),
                                    )
                                }
                            }
                        }
                    }
                }
                rule.waitForIdle()
                TabStripStyle.entries.forEach { style ->
                    val node = rule.onNodeWithTag("tab-${style.name}")
                    node.performMouseInput { moveTo(center) }
                    rule.waitForIdle()
                    val image = node.captureToImage().toPixelMap()
                    val behind = tabStripBackground(style, LIGHT_THEME).toArgb()
                    val corners = mapOf(
                        "tl" to (0 to 0), "tr" to (image.width - 1 to 0),
                        "bl" to (0 to image.height - 1), "br" to (image.width - 1 to image.height - 1),
                    )
                    val rounded = roundedCorners(tabShape(style, position), image.width.toFloat(), image.height.toFloat())
                    corners.filterKeys { it in rounded }.forEach { (name, at) ->
                        assertEquals(
                            behind, image[at.first, at.second].toArgb(),
                            "$style on $position (selected=$active): the $name corner outside the tab's shape is the strip's colour",
                        )
                    }
                    // The shapes that are not plain rectangles really do cut corners, and the open side of a raised card does not.
                    if (style == TabStripStyle.SEGMENTED) assertEquals(setOf("tl", "tr", "bl", "br"), rounded)
                    if (style == TabStripStyle.RAISED) assertEquals(2, rounded.size, "raised on $position: the open side stays square")
                }
            }
        }
    }
}
