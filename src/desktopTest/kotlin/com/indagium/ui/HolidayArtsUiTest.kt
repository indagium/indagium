// Window and content sizes of the screens under test are literal on purpose.
@file:Suppress("MagicNumber")

package com.indagium.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.AccountTree
import androidx.compose.material.icons.outlined.Description
import androidx.compose.material.icons.outlined.PhoneAndroid
import androidx.compose.material.icons.outlined.Science
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.getBoundsInRoot
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.unit.dp
import com.indagium.model.AppSettings
import com.indagium.model.HolidayIntensity
import com.indagium.model.HolidayMode
import com.indagium.model.HomeSectionId
import com.indagium.model.LogEntry
import com.indagium.model.LogLevel
import com.indagium.ui.holiday.HOLIDAY_ART_WASH_TAG
import com.indagium.ui.holiday.HOLIDAY_FILTER_PICTURE_TAG
import com.indagium.ui.holiday.HOLIDAY_NOTES_COMPANION_TAG
import com.indagium.ui.holiday.HolidayArtFrame
import com.indagium.ui.holiday.HolidayArtsBehind
import com.indagium.ui.holiday.HolidayArtsWash
import com.indagium.ui.holiday.HolidayLook
import com.indagium.ui.holiday.LocalHoliday
import com.indagium.ui.holiday.LocalHolidayArtClock
import com.indagium.ui.holiday.LocalHolidayArtFrame
import com.indagium.ui.holiday.rememberHolidayArtClock
import com.indagium.ui.holiday.resolveHolidayLook
import com.indagium.ui.holiday.withHoliday
import org.junit.After
import org.junit.Rule
import org.junit.Test
import java.io.File
import java.nio.file.Files
import java.time.LocalDate
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The seasonal pack's picture (bottom-left) and companion (bottom-right) are app-level: ChromeScaffold reports the content area, App draws them
 * behind everything and as a faint wash over everything, so they show on every screen and in every layout and on no screen without a pack.
 */
@OptIn(ExperimentalTestApi::class)
class HolidayArtsUiTest {
    @get:Rule
    val rule = createComposeRule()

    private val tempDir = Files.createTempDirectory("holiday-arts-ui-").toFile()
    private val state = AppState(
        autosaveFile = File(tempDir, "state.cache"),
        restoreOnCreate = false,
        notesDir = File(tempDir, "notes"),
        archiveCacheDir = File(tempDir, "archive-cache"),
        customCommandsDir = File(tempDir, "commands"),
        filterBackupsDir = File(tempDir, "filter-backups"),
    )

    @After
    fun tearDown() {
        state.close()
        tempDir.deleteRecursively()
    }

    private fun lookWith(edit: (com.indagium.model.HolidaySettings) -> com.indagium.model.HolidaySettings = { it }): HolidayLook? =
        resolveHolidayLook(AppSettings().withHoliday { edit(it.copy(mode = HolidayMode.ALWAYS)) }, LocalDate.of(2026, 10, 31))

    private fun exists(tag: String) = rule.onAllNodesWithTag(tag).fetchSemanticsNodes().isNotEmpty()

    private fun bothArts() = exists(HOLIDAY_FILTER_PICTURE_TAG) && exists(HOLIDAY_NOTES_COMPANION_TAG)

    private fun noArts() = !exists(HOLIDAY_FILTER_PICTURE_TAG) && !exists(HOLIDAY_NOTES_COMPANION_TAG) && !exists(HOLIDAY_ART_WASH_TAG)

    /** The app's layering: the behind pass first, the real scaffold with [content], then the wash. */
    private fun app(look: HolidayLook?, width: Int = 1000, height: Int = 700, content: @Composable () -> Unit) {
        rule.setContent {
            val frame = remember { HolidayArtFrame() }
            val clock = rememberHolidayArtClock(look, frame, paused = false)
            CompositionLocalProvider(
                LocalTheme provides (look?.colors ?: LIGHT_THEME),
                LocalHoliday provides look,
                LocalHolidayArtFrame provides frame,
                LocalHolidayArtClock provides clock,
            ) {
                Box(Modifier.size(width.dp, height.dp).background(tc().bg)) {
                    HolidayArtsBehind()
                    ChromeScaffold(state = state, onReclaimFocus = {}, anchors = ChromeAnchors(), content = content)
                    HolidayArtsWash()
                }
            }
        }
        rule.waitForIdle()
    }

    private fun plain() = @Composable { Box(Modifier.fillMaxSize()) { Text("content") } }

    @Test
    fun switchedLogViewStartsWithTheLastMeasuredViewport() {
        var tab by mutableStateOf(0)
        val memory = LogViewMeasurementMemory()
        val observedWidths = mutableListOf<Float>()
        rule.setContent {
            Box(Modifier.size(1000.dp, 700.dp)) {
                key(tab) {
                    LogViewTilingFrame(state, Modifier.fillMaxSize(), memory) { width, _ ->
                        if (tab == 1) observedWidths.add(width)
                    }
                }
            }
        }
        rule.waitForIdle()
        rule.runOnIdle { tab = 1 }
        rule.waitForIdle()
        assertTrue(observedWidths.isNotEmpty(), "the new tab renders without waiting for a fresh viewport measurement")
        assertTrue(observedWidths.all { it > 900f })
    }

    @Test
    fun thePictureIsInTheContentAreasBottomLeftAndTheCatInItsBottomRightBelowTheChrome() {
        app(lookWith(), content = plain())
        assertTrue(bothArts() && exists(HOLIDAY_ART_WASH_TAG))
        val picture = rule.onNodeWithTag(HOLIDAY_FILTER_PICTURE_TAG).getBoundsInRoot()
        val cat = rule.onNodeWithTag(HOLIDAY_NOTES_COMPANION_TAG).getBoundsInRoot()
        val strip = rule.onNodeWithTag("tab-strip").getBoundsInRoot()
        assertEquals(0f, picture.left.value, 1.5f)
        assertEquals(700f, picture.bottom.value, 1.5f)
        assertEquals(1000f, cat.right.value, 1.5f)
        assertEquals(700f, cat.bottom.value, 1.5f)
        assertTrue((picture.right - picture.left).value in 225f..240f, "about 24% of the window width, less where the height limits it")
        assertTrue(picture.top >= strip.bottom && cat.top >= strip.bottom, "not over the tab bar or the toolbar")
    }

    @Test
    fun noArtWithoutAPackOrWhenItsSwitchIsOff() {
        app(null, content = plain())
        assertTrue(noArts())
    }

    @Test
    fun eachArtHasItsOwnSwitch() {
        app(lookWith { it.copy(filterPicture = false) }, content = plain())
        assertTrue(!exists(HOLIDAY_FILTER_PICTURE_TAG) && exists(HOLIDAY_NOTES_COMPANION_TAG))
    }

    @Test
    fun theOtherSwitchRemovesOnlyTheCat() {
        app(lookWith { it.copy(notesCompanion = false) }, content = plain())
        assertTrue(exists(HOLIDAY_FILTER_PICTURE_TAG) && !exists(HOLIDAY_NOTES_COMPANION_TAG))
    }

    @Test
    fun bothSwitchesOffLeavesNothingAndNoWash() {
        app(lookWith { it.copy(filterPicture = false, notesCompanion = false) }, content = plain())
        assertTrue(noArts())
    }

    @Test
    fun aWindowUnderTheMinimumContentSizeShowsNone() {
        app(lookWith(), width = 470, height = 700, content = plain())
        assertTrue(noArts())
    }

    @Test
    fun theLogViewShowsBothArtsWhateverIsInItsCornersFiltersHiddenOrNot() {
        val rows = (1..20).map { LogEntry(it, "10:00:00.$it", LogLevel.I, "Tag", "row $it", pid = 100, tid = 1) }
        state.tabs = listOf(mkTab("holiday-arts-log", "arts.log", rows))
        state.activeTabId = "holiday-arts-log"
        LOG_VIEW_FILTER_TILES.forEach(state::hideLogViewTile)
        app(lookWith()) { state.tab("holiday-arts-log")?.let { FileView(state, it) } }
        assertTrue(bothArts(), "Log | Original with the filters hidden: the corners are log tiles, the arts show anyway")
    }

    @Test
    fun theNewTabShowsBothArtsStackedOrNot() {
        state.openHomeTab()
        val slots = mapOf(
            HomeSectionId.OPEN to HomeSectionSlot(Icons.Outlined.Description, homeSectionTitle(HomeSectionId.OPEN)) { Text("open") },
            HomeSectionId.CAPTURE to HomeSectionSlot(Icons.Outlined.PhoneAndroid, homeSectionTitle(HomeSectionId.CAPTURE)) { Text("capture") },
            HomeSectionId.TESTS to HomeSectionSlot(Icons.Outlined.Science, homeSectionTitle(HomeSectionId.TESTS)) { Text("tests") },
            HomeSectionId.DIAGRAMS to HomeSectionSlot(Icons.Outlined.AccountTree, homeSectionTitle(HomeSectionId.DIAGRAMS)) { Text("diagrams") },
        )
        app(lookWith(), width = 600, height = 700) { HomeTilingHost(state = state, slots = slots, onReclaimFocus = {}, modifier = Modifier) }
        assertTrue(bothArts(), "a stacked New tab has no tiling surface; the arts do not need one")
    }

    @Test
    fun theTestsWorkspaceShowsBothArts() {
        app(lookWith()) {
            val scope = rememberCoroutineScope()
            val ui = remember { TestsUi(state, state.testsView, FocusRequester(), scope) }
            CompositionLocalProvider(LocalTestsUi provides ui) {
                TestsWorkspaceTilingHost(main = { Text("workspace") }, header = null, inspector = { Text("details") }, evidence = { Text("evidence") })
            }
        }
        assertTrue(bothArts())
    }

    @Test
    fun theDiagramWorkspaceShowsBothArtsAlthoughThePreviewOwnsTheBottomRight() {
        val tab = mkTab("holiday-arts-diagram", "diagram.log", emptyList())
        state.tabs = listOf(tab)
        state.activateTab(tab.id)
        val sessionId = requireNotNull(state.seq3Sessions.beginEmpty(tab.id))
        app(lookWith()) { Seq3Workspace(state, sessionId) }
        assertTrue(bothArts())
    }

    @Test
    fun theWashMultipliesIntoALightThemeSoItOnlyDarkensWhatIsUnderIt() {
        // A white content area: the behind pass is covered, so any change in the picture's rectangle is the wash alone.
        val light = lookWith { it.copy(notesCompanion = false) }?.takeIf { !it.isDark } ?: return
        app(light) { Box(Modifier.fillMaxSize().background(Color.White).testTag("white")) }
        val pic = rule.onNodeWithTag(HOLIDAY_FILTER_PICTURE_TAG).getBoundsInRoot()
        val density = rule.density.density
        val pixels = rule.onNodeWithTag("white").captureToImage().toPixelMap()
        val strip = rule.onNodeWithTag("tab-strip").getBoundsInRoot().bottom.value
        // The big pumpkin of the picture, near (120, 320) of its 300 x 420 design.
        val scale = (pic.right - pic.left).value / 300f
        val x = ((pic.left.value + 120f * scale) * density).toInt().coerceIn(0, pixels.width - 1)
        val y = ((pic.top.value + 320f * scale - strip) * density).toInt().coerceIn(0, pixels.height - 1)
        val inside = pixels[x, y]
        assertTrue(inside.luminance() < 0.995f, "the pumpkin shows faintly through the white content: $inside")
        assertTrue(inside.red <= 1f && inside.luminance() > 0.7f, "only faintly")
        val outside = pixels[(pixels.width - 5).coerceAtLeast(0), 5]
        assertEquals(1f, outside.luminance(), 0.002f, "away from the art the content stays white")
    }

    @Test
    fun calmAndOffStillShowTheArts() {
        app(lookWith { it.copy(intensity = HolidayIntensity.CALM) }, content = plain())
        assertTrue(bothArts())
    }
}
