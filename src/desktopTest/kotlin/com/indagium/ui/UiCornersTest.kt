package com.indagium.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.getBoundsInRoot
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.height
import androidx.compose.ui.unit.width
import com.indagium.model.AppSettings
import com.indagium.model.HolidayMode
import com.indagium.model.HolidayPack
import com.indagium.model.ShapeStyle
import com.indagium.model.TabStripPosition
import com.indagium.model.effectiveToolbarShapeStyle
import com.indagium.ui.holiday.HOLIDAY_THEMES
import com.indagium.ui.holiday.HolidayLook
import com.indagium.ui.holiday.LocalHoliday
import com.indagium.ui.holiday.resolveHolidayLook
import com.indagium.ui.holiday.withHoliday
import org.junit.After
import org.junit.Rule
import org.junit.Test
import java.io.File
import java.time.LocalDate
import kotlin.io.path.createTempDirectory
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** The one corner resolver: Theme default is exactly today's values, each fixed style is its table row. */
class UiCornersTest {
    private fun lookFor(pack: HolidayPack): HolidayLook {
        val settings = AppSettings().withHoliday { it.copy(mode = HolidayMode.ALWAYS, pack = pack) }
        return checkNotNull(resolveHolidayLook(settings, LocalDate.parse("2026-10-31"))) { "no look for $pack" }
    }

    private val theme = ShapeStyle.THEME

    @Test
    fun themeAndThemeWithoutAPackIsTodaysValues() {
        val c = resolveUiCorners(theme, theme, null)
        assertEquals(UiCorners(8, 7, 8), c)
        assertEquals(8, LOG_VIEW_CHROME_SPEC.cornerDp, "the regular tile corner is the log view's spec")
    }

    @Test
    fun themeAndThemeWithAPackIsThatPacksCornersAndTheWorkspaceTilesStayAtEight() {
        assertTrue(HOLIDAY_THEMES.isNotEmpty())
        HOLIDAY_THEMES.keys.forEach { pack ->
            val look = lookFor(pack)
            val own = look.theme.corners
            assertEquals(UiCorners(own.tileDp, own.toolbarDp, 8), resolveUiCorners(theme, theme, look), "$pack: packs never changed the workspace tiles")
        }
    }

    @Test
    fun theShapeStyleChangesOnlyTheTiles() {
        val looks = listOf<HolidayLook?>(null) + HOLIDAY_THEMES.keys.map(::lookFor)
        looks.forEach { look ->
            val base = resolveUiCorners(theme, theme, look)
            ShapeStyle.entries.forEach { style ->
                assertEquals(base.toolbarDp, resolveUiCorners(style, theme, look).toolbarDp, "$style leaves the toolbar alone")
            }
            listOf(ShapeStyle.SQUARE to 0, ShapeStyle.SOFT to 8, ShapeStyle.ROUNDED to 12, ShapeStyle.ROUND to 16).forEach { (style, tile) ->
                assertEquals(UiCorners(tile, base.toolbarDp, tile), resolveUiCorners(style, theme, look), "$style")
            }
        }
    }

    @Test
    fun theToolbarShapeChangesOnlyTheToolbar() {
        val looks = listOf<HolidayLook?>(null) + HOLIDAY_THEMES.keys.map(::lookFor)
        looks.forEach { look ->
            val base = resolveUiCorners(theme, theme, look)
            ShapeStyle.entries.forEach { toolbar ->
                val c = resolveUiCorners(theme, toolbar, look)
                assertEquals(base.tileDp, c.tileDp, "$toolbar leaves the tiles alone")
                assertEquals(base.workspaceTileDp, c.workspaceTileDp)
            }
            assertEquals(0, resolveUiCorners(theme, ShapeStyle.SQUARE, look).toolbarDp)
            assertEquals(7, resolveUiCorners(theme, ShapeStyle.SOFT, look).toolbarDp)
            assertEquals(10, resolveUiCorners(theme, ShapeStyle.ROUNDED, look).toolbarDp)
            assertEquals(UI_CORNER_CAPSULE, resolveUiCorners(theme, ShapeStyle.ROUND, look).toolbarDp)
        }
    }

    @Test
    fun onlyTheToolbarsRoundIsACapsuleAndTheShapeStyleNeverIs() {
        ShapeStyle.entries.forEach {
            assertEquals(it == ShapeStyle.ROUND, isCapsuleCorner(resolveUiCorners(theme, it, null).toolbarDp), "toolbar $it")
            assertFalse(isCapsuleCorner(resolveUiCorners(it, theme, null).toolbarDp), "shape $it")
            assertFalse(isCapsuleCorner(resolveUiCorners(it, theme, null).tileDp), "shape $it")
        }
    }

    @Test
    fun aCapsuleToolbarGroupEndsAtHalfTheBarThicknessAndAFixedOneAtItsDp() {
        val capsule = ToolbarShapes(vertical = false, cornerDp = UI_CORNER_CAPSULE)
        assertEquals(RoundedCornerShape(topStartPercent = 50, bottomStartPercent = 50), capsule.lead)
        assertEquals(RoundedCornerShape(topEndPercent = 50, bottomEndPercent = 50), capsule.tail)
        assertEquals(RoundedCornerShape(50), capsule.solo)
        assertEquals(RoundedCornerShape(0.dp), capsule.middle)
        val column = ToolbarShapes(vertical = true, cornerDp = UI_CORNER_CAPSULE)
        assertEquals(RoundedCornerShape(topStartPercent = 50, topEndPercent = 50), column.lead)
        assertEquals(RoundedCornerShape(bottomStartPercent = 50, bottomEndPercent = 50), column.tail)

        val fixed = ToolbarShapes(vertical = false, cornerDp = 10)
        assertEquals(RoundedCornerShape(topStart = 10.dp, bottomStart = 10.dp), fixed.lead)
        assertEquals(RoundedCornerShape(10.dp), fixed.solo)
        assertEquals(RoundedCornerShape(0.dp), ToolbarShapes(vertical = false, cornerDp = 0).solo)
    }
}

/** The shape style reaches the real tiles and toolbar: SQUARE is 0 dp and square, ROUND a capsule toolbar group. */
@OptIn(ExperimentalTestApi::class)
class ShapeStyleUiTest {
    @get:Rule
    val rule = createComposeRule()

    private lateinit var directory: File
    private lateinit var state: AppState
    private var tileDp = -1
    private var toolbarDp = -1

    @After
    fun tearDown() {
        if (::state.isInitialized) state.close()
        if (::directory.isInitialized) directory.deleteRecursively()
    }

    @Composable
    private fun Probe() {
        tileDp = logViewChromeSpec().cornerDp
        toolbarDp = toolbarCornerDp()
    }

    private fun show(style: ShapeStyle?, toolbar: ShapeStyle = ShapeStyle.THEME) {
        directory = createTempDirectory("shape-style-ui").toFile()
        state = AppState(
            autosaveFile = File(directory, "state.cache"),
            notesDir = File(directory, "notes"),
            archiveCacheDir = File(directory, "archives"),
            customCommandsDir = File(directory, "commands"),
            autoExportNotes = false,
        )
        val tab = mkTab("shape", "shape.log", emptyList())
        state.tabs = listOf(tab)
        state.activeTabId = tab.id
        // A left strip is a card bar, which follows the log view's tile corner.
        state.updateSettings { it.copy(tabStripPosition = TabStripPosition.LEFT) }
        rule.setContent {
            CompositionLocalProvider(
                LocalTheme provides LIGHT_THEME,
                LocalHoliday provides null,
                LocalUiCorners provides style?.let { resolveUiCorners(it, toolbar, null) },
            ) {
                Probe()
                Box(Modifier.size(800.dp, 500.dp)) {
                    ChromeScaffold(state) { Box(Modifier.fillMaxSize().testTag("content")) }
                }
            }
        }
        rule.waitForIdle()
    }

    // A rounded corner shows as the corner pixel differing from the one at the middle of the top edge.
    private fun cornerIsRounded(tag: String): Boolean {
        val px = rule.onNodeWithTag(tag).captureToImage().toPixelMap()
        return px[px.width / 2, 0] != px[0, 0]
    }

    @Test
    fun withoutAnyProvidedCornersTheLookIsTodays() {
        show(null)
        assertEquals(8, tileDp)
        assertEquals(7, toolbarDp)
    }

    @Test
    fun shapeStyleSquareGivesZeroDpTilesAndASquareCardBarButLeavesTheToolbarAtSeven() {
        show(ShapeStyle.SQUARE)
        assertEquals(0, tileDp)
        assertEquals(7, toolbarDp)
        assertFalse(cornerIsRounded("tab-strip"), "a square card bar")
        assertTrue(cornerIsRounded("layout-menu-button"), "the toolbar button keeps its 7 dp corner")
        assertEquals(RoundedCornerShape(7.dp), ToolbarShapes(vertical = false, cornerDp = toolbarDp).solo)
    }

    @Test
    fun shapeStyleRoundGivesSixteenDpTilesAndARoundedCardBarButNoCapsuleToolbar() {
        show(ShapeStyle.ROUND)
        assertEquals(16, tileDp)
        assertEquals(7, toolbarDp)
        assertTrue(cornerIsRounded("tab-strip"), "a rounded card bar")
        assertEquals(RoundedCornerShape(7.dp), ToolbarShapes(vertical = false, cornerDp = toolbarDp).solo)
    }

    @Test
    fun toolbarShapeSquareSquaresTheToolbarButtonAndLeavesTheTilesAtEight() {
        show(ShapeStyle.THEME, toolbar = ShapeStyle.SQUARE)
        assertEquals(8, tileDp)
        assertEquals(0, toolbarDp)
        assertFalse(cornerIsRounded("layout-menu-button"), "a square toolbar button")
        assertEquals(RoundedCornerShape(0.dp), ToolbarShapes(vertical = false, cornerDp = toolbarDp).solo)
    }

    @Test
    fun toolbarShapeRoundGivesACapsuleToolbarGroupAndLeavesTheTilesAtEight() {
        show(ShapeStyle.THEME, toolbar = ShapeStyle.ROUND)
        assertEquals(8, tileDp)
        assertEquals(UI_CORNER_CAPSULE, toolbarDp)
        assertTrue(cornerIsRounded("layout-menu-button"), "a capsule toolbar button")
        assertEquals(RoundedCornerShape(50), ToolbarShapes(vertical = false, cornerDp = toolbarDp).solo)
    }
}

/** Settings > Appearance > Corners: the Tiles and Toolbar dropdowns (the Toolbar one with "Same as tiles") each write their own fields. */
@OptIn(ExperimentalTestApi::class)
class ShapeStyleSettingUiTest {
    @get:Rule
    val rule = createComposeRule()

    private lateinit var directory: File
    private lateinit var state: AppState

    @After
    fun tearDown() {
        if (::state.isInitialized) state.close()
        if (::directory.isInitialized) directory.deleteRecursively()
    }

    private fun newState() = AppState(
        autosaveFile = File(directory, "state.cache"),
        notesDir = File(directory, "notes"),
        archiveCacheDir = File(directory, "archives"),
        customCommandsDir = File(directory, "commands"),
        autoExportNotes = false,
    )

    private fun chooseTile(style: ShapeStyle) {
        rule.onNodeWithTag(SHAPE_STYLE_CONTROL_TAG).performClick()
        rule.waitForIdle()
        rule.onNodeWithTag("$SHAPE_STYLE_CONTROL_TAG-${style.name}").performClick()
        rule.waitForIdle()
        rule.onNodeWithTag(SHAPE_STYLE_CONTROL_TAG).assertTextContains(shapeStyleLabel(style))
    }

    private fun assertSample(ownerTag: String, style: ShapeStyle?) =
        rule.onNode(hasTestTag(shapeSampleTag(style)) and hasAnyAncestor(hasTestTag(ownerTag)), useUnmergedTree = true).assertExists()

    private fun chooseToolbar(style: ShapeStyle?) {
        rule.onNodeWithTag(TOOLBAR_SHAPE_CONTROL_TAG).performClick()
        rule.waitForIdle()
        rule.onNodeWithTag("$TOOLBAR_SHAPE_CONTROL_TAG-option-${style?.name ?: "SAME_AS_TILES"}").performClick()
        rule.waitForIdle()
    }

    @Test
    fun theTilesDropdownListsTheFiveStylesWithTheirDescriptionAndWritesEachChoice() {
        directory = createTempDirectory("shape-style-setting").toFile()
        state = newState()
        rule.setContent {
            CompositionLocalProvider(LocalTheme provides LIGHT_THEME) {
                Box(Modifier.size(700.dp, 320.dp)) {
                    Column {
                        ShapeStyleSetting(state)
                        ToolbarShapeSetting(state)
                    }
                }
            }
        }
        rule.waitForIdle()
        rule.onNodeWithText("How round tiles are. Theme default keeps each theme's own shapes.").assertExists()
        rule.onNodeWithText("Corners of the toolbar's button groups and the Layout menu button.").assertExists()
        rule.onNodeWithTag(SHAPE_STYLE_CONTROL_TAG).assertTextContains("Theme default")
        rule.onNodeWithTag(SHAPE_STYLE_CONTROL_TAG).performClick()
        rule.waitForIdle()
        ShapeStyle.entries.forEach { style ->
            rule.onNodeWithTag("$SHAPE_STYLE_CONTROL_TAG-${style.name}").assertExists()
        }
        rule.onNodeWithTag("$SHAPE_STYLE_CONTROL_TAG-${ShapeStyle.THEME.name}").performClick()
        rule.waitForIdle()
        assertEquals(ShapeStyle.THEME, state.settings.shapeStyle)
        val choices = listOf(ShapeStyle.SQUARE, ShapeStyle.SOFT, ShapeStyle.ROUNDED, ShapeStyle.ROUND, ShapeStyle.THEME)
        choices.forEach { style ->
            chooseTile(style)
            assertEquals(style, state.settings.shapeStyle, style.name)
            assertEquals(ShapeStyle.THEME, state.settings.toolbarShapeStyle, "the toolbar's own style is untouched")
            assertTrue(state.settings.toolbarShapeFollowsTiles, "a Tiles choice never detaches the toolbar")
            assertEquals(style, state.settings.effectiveToolbarShapeStyle, "the toolbar follows the tiles")
        }
    }

    @Test
    fun tilesAndToolbarAreTheSameDropdownControlWithACornerSampleBeforeEveryOption() {
        directory = createTempDirectory("shape-style-same-control").toFile()
        state = newState()
        rule.setContent {
            CompositionLocalProvider(LocalTheme provides LIGHT_THEME) {
                Box(Modifier.size(700.dp, 400.dp)) {
                    Column {
                        ShapeStyleSetting(state)
                        ToolbarShapeSetting(state)
                    }
                }
            }
        }
        rule.waitForIdle()
        // Closed, both are one dropdown field (not a row of toggle buttons): the same size, right edge and height.
        rule.onNodeWithText("Square").assertDoesNotExist()
        val tiles = rule.onNodeWithTag(SHAPE_STYLE_CONTROL_TAG).getBoundsInRoot()
        val toolbar = rule.onNodeWithTag(TOOLBAR_SHAPE_CONTROL_TAG).getBoundsInRoot()
        assertEquals(tiles.width, toolbar.width, "the same width")
        assertEquals(tiles.height, toolbar.height, "the same height")
        assertEquals(tiles.right, toolbar.right, "the same right edge")
        // Each field shows a sample before its name: Tiles its style's corner, Toolbar the link glyph of "Same as tiles".
        assertSample(SHAPE_STYLE_CONTROL_TAG, ShapeStyle.THEME)
        assertSample(TOOLBAR_SHAPE_CONTROL_TAG, null)

        rule.onNodeWithTag(SHAPE_STYLE_CONTROL_TAG).performClick()
        rule.waitForIdle()
        ShapeStyle.entries.forEach { style -> assertSample("$SHAPE_STYLE_CONTROL_TAG-${style.name}", style) }
        rule.onNodeWithTag("$SHAPE_STYLE_CONTROL_TAG-${ShapeStyle.SOFT.name}").performClick()
        rule.waitForIdle()
        assertSample(SHAPE_STYLE_CONTROL_TAG, ShapeStyle.SOFT)

        rule.onNodeWithTag(TOOLBAR_SHAPE_CONTROL_TAG).performClick()
        rule.waitForIdle()
        assertSample("$TOOLBAR_SHAPE_CONTROL_TAG-option-SAME_AS_TILES", null)
        ShapeStyle.entries.forEach { style -> assertSample("$TOOLBAR_SHAPE_CONTROL_TAG-option-${style.name}", style) }
        rule.onNodeWithTag("$TOOLBAR_SHAPE_CONTROL_TAG-option-${ShapeStyle.ROUND.name}").performClick()
        rule.waitForIdle()
        assertSample(TOOLBAR_SHAPE_CONTROL_TAG, ShapeStyle.ROUND)
    }

    @Test
    fun theToolbarDropdownStartsAtSameAsTilesAndAnOwnStyleDetachesItUntilSameAsTilesIsPickedAgain() {
        directory = createTempDirectory("toolbar-shape-setting").toFile()
        state = newState()
        rule.setContent {
            CompositionLocalProvider(LocalTheme provides LIGHT_THEME) {
                Box(Modifier.size(700.dp, 320.dp)) { Column { ToolbarShapeSetting(state) } }
            }
        }
        rule.waitForIdle()
        rule.onNodeWithTag(TOOLBAR_SHAPE_CONTROL_TAG).assertTextContains("Same as tiles")
        rule.onNodeWithTag(TOOLBAR_SHAPE_CONTROL_TAG).performClick()
        rule.waitForIdle()
        // "Same as tiles" first, then the five styles.
        rule.onNodeWithTag("$TOOLBAR_SHAPE_CONTROL_TAG-option-SAME_AS_TILES").assertExists()
        ShapeStyle.entries.forEach { rule.onNodeWithTag("$TOOLBAR_SHAPE_CONTROL_TAG-option-${it.name}").assertExists() }
        rule.onNodeWithTag("$TOOLBAR_SHAPE_CONTROL_TAG-option-SAME_AS_TILES").performClick()
        rule.waitForIdle()

        state.updateSettings { it.copy(shapeStyle = ShapeStyle.SOFT) }
        ShapeStyle.entries.forEach { style ->
            chooseToolbar(style)
            assertEquals(style, state.settings.toolbarShapeStyle, style.name)
            assertFalse(state.settings.toolbarShapeFollowsTiles, "an own style detaches the toolbar")
            assertEquals(style, state.settings.effectiveToolbarShapeStyle)
            assertEquals(ShapeStyle.SOFT, state.settings.shapeStyle, "the tiles' style is untouched")
            rule.onNodeWithTag(TOOLBAR_SHAPE_CONTROL_TAG).assertTextContains(shapeStyleLabel(style))
        }
        chooseToolbar(null)
        assertTrue(state.settings.toolbarShapeFollowsTiles)
        assertEquals(ShapeStyle.SOFT, state.settings.effectiveToolbarShapeStyle, "it follows the tiles again")
        assertEquals(ShapeStyle.ROUND, state.settings.toolbarShapeStyle, "the remembered own style is kept for the next time")
        rule.onNodeWithTag(TOOLBAR_SHAPE_CONTROL_TAG).assertTextContains("Same as tiles")
    }

    @Test
    fun effectiveToolbarShapeStyleIsTheTilesStyleWhileFollowingAndTheOwnStyleOtherwise() {
        val following = AppSettings(shapeStyle = ShapeStyle.ROUNDED, toolbarShapeStyle = ShapeStyle.SQUARE)
        assertEquals(ShapeStyle.ROUNDED, following.effectiveToolbarShapeStyle)
        val own = following.copy(toolbarShapeFollowsTiles = false)
        assertEquals(ShapeStyle.SQUARE, own.effectiveToolbarShapeStyle)
        assertEquals(ShapeStyle.THEME, AppSettings().effectiveToolbarShapeStyle, "a fresh install is theme default throughout")
    }
}
