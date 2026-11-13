package com.indagium.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.unit.dp
import com.indagium.model.AppSettings
import com.indagium.model.HolidayMode
import com.indagium.model.HolidayPack
import com.indagium.model.ShapeStyle
import com.indagium.model.TabStripPosition
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
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** The one corner resolver: Theme default is exactly today's values, each fixed style is its table row. */
class UiCornersTest {
    private fun lookFor(pack: HolidayPack): HolidayLook {
        val settings = AppSettings().withHoliday { it.copy(mode = HolidayMode.ALWAYS, pack = pack) }
        return checkNotNull(resolveHolidayLook(settings, LocalDate.parse("2026-10-31"))) { "no look for $pack" }
    }

    @Test
    fun themeWithoutAPackIsTodaysValuesAndTheTabStyleDrawsItsOwnCorners() {
        val c = resolveUiCorners(ShapeStyle.THEME, null)
        assertEquals(8, c.tileDp)
        assertEquals(7, c.toolbarDp)
        assertNull(c.tabDp)
        assertEquals(8, c.workspaceTileDp)
        assertEquals(8, LOG_VIEW_CHROME_SPEC.cornerDp, "the regular tile corner is the log view's spec")
    }

    @Test
    fun themeWithAPackIsThatPacksCornersAndTheWorkspaceTilesStayAtEight() {
        assertTrue(HOLIDAY_THEMES.isNotEmpty())
        HOLIDAY_THEMES.keys.forEach { pack ->
            val look = lookFor(pack)
            val own = look.theme.corners
            val c = resolveUiCorners(ShapeStyle.THEME, look)
            assertEquals(own.tileDp, c.tileDp, "$pack tiles")
            assertEquals(own.toolbarDp, c.toolbarDp, "$pack toolbar")
            assertEquals(own.tabDp, c.tabDp, "$pack tabs")
            assertEquals(8, c.workspaceTileDp, "$pack: packs never changed the workspace tiles")
        }
    }

    @Test
    fun eachFixedStyleGivesItsRowWithOrWithoutAPackAndTheWorkspaceTilesFollow() {
        val looks = listOf<HolidayLook?>(null) + HOLIDAY_THEMES.keys.map(::lookFor)
        looks.forEach { look ->
            assertEquals(UiCorners(0, 0, 0f, 0, 0f), resolveUiCorners(ShapeStyle.SQUARE, look))
            assertEquals(UiCorners(8, 7, 6f, 8, 6f), resolveUiCorners(ShapeStyle.SOFT, look))
            assertEquals(UiCorners(12, 10, 10f, 12, 10f), resolveUiCorners(ShapeStyle.ROUNDED, look))
            assertEquals(UiCorners(16, UI_CORNER_CAPSULE, 14f, 16, 14f), resolveUiCorners(ShapeStyle.ROUND, look))
        }
    }

    @Test
    fun onlyRoundIsACapsule() {
        ShapeStyle.entries.forEach {
            assertEquals(it == ShapeStyle.ROUND, isCapsuleCorner(resolveUiCorners(it, null).toolbarDp), "$it")
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

    private fun show(style: ShapeStyle?) {
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
                LocalUiCorners provides style?.let { resolveUiCorners(it, null) },
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
    fun squareGivesZeroDpTilesASquareCardBarAndASquareToolbarGroup() {
        show(ShapeStyle.SQUARE)
        assertEquals(0, tileDp)
        assertEquals(0, toolbarDp)
        assertFalse(cornerIsRounded("tab-strip"), "a square card bar")
        assertFalse(cornerIsRounded("layout-menu-button"), "a square toolbar button")
        assertEquals(RoundedCornerShape(0.dp), ToolbarShapes(vertical = false, cornerDp = toolbarDp).solo)
    }

    @Test
    fun roundGivesSixteenDpTilesARoundedCardBarAndACapsuleToolbarGroup() {
        show(ShapeStyle.ROUND)
        assertEquals(16, tileDp)
        assertEquals(UI_CORNER_CAPSULE, toolbarDp)
        assertTrue(cornerIsRounded("tab-strip"), "a rounded card bar")
        assertTrue(cornerIsRounded("layout-menu-button"), "a capsule toolbar button")
        assertEquals(RoundedCornerShape(50), ToolbarShapes(vertical = false, cornerDp = toolbarDp).solo)
    }
}

/** Settings > Appearance > Shape style: the control writes [com.indagium.model.AppSettings.shapeStyle]. */
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

    private fun choose(label: String) {
        rule.onNode(hasText(label) and hasAnyAncestor(hasTestTag(SHAPE_STYLE_CONTROL_TAG))).performClick()
        rule.waitForIdle()
    }

    @Test
    fun theControlListsTheFiveStylesShowsTheHintAndWritesEachChoice() {
        directory = createTempDirectory("shape-style-setting").toFile()
        state = AppState(
            autosaveFile = File(directory, "state.cache"),
            notesDir = File(directory, "notes"),
            archiveCacheDir = File(directory, "archives"),
            customCommandsDir = File(directory, "commands"),
            autoExportNotes = false,
        )
        rule.setContent {
            CompositionLocalProvider(LocalTheme provides LIGHT_THEME) { Box(Modifier.size(700.dp, 200.dp)) { ShapeStyleSetting(state) } }
        }
        rule.waitForIdle()
        rule.onNodeWithText("Corners of tiles, the toolbar and tabs. Theme default keeps each theme's own shapes.").assertExists()
        assertEquals(ShapeStyle.THEME, state.settings.shapeStyle)
        val choices = listOf(
            "Square" to ShapeStyle.SQUARE,
            "Soft" to ShapeStyle.SOFT,
            "Rounded" to ShapeStyle.ROUNDED,
            "Round" to ShapeStyle.ROUND,
            "Theme default" to ShapeStyle.THEME,
        )
        choices
            .forEach { (label, style) ->
                choose(label)
                assertEquals(style, state.settings.shapeStyle, label)
            }
    }
}
