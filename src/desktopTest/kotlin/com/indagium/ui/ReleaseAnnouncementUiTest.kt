package com.indagium.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Description
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertIsNotSelected
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.width
import com.indagium.model.HolidayLightness
import com.indagium.model.HolidayMode
import com.indagium.model.HolidayPack
import com.indagium.model.TileLeaf
import com.indagium.ui.holiday.HOLIDAY_THEMES
import com.indagium.ui.holiday.LocalHoliday
import com.indagium.ui.holiday.resolveHolidayLook
import com.indagium.ui.holiday.seasonalPackModeTag
import org.junit.After
import org.junit.Rule
import org.junit.Test
import java.io.File
import java.time.LocalDateTime
import kotlin.io.path.createTempDirectory
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

@OptIn(ExperimentalTestApi::class)
class ReleaseAnnouncementUiTest {
    @get:Rule val rule = createComposeRule()
    private val directory = createTempDirectory("release-announcement-ui").toFile()
    private val state = AppState(
        autosaveFile = File(directory, "cache"), notesDir = File(directory, "notes"), archiveCacheDir = File(directory, "archive"),
        customCommandsDir = File(directory, "commands"), autoExportNotes = false,
    )
    private val release = ReleaseAnnouncement(
        "1.9.1", listOf(WhatsNewPage("First page", "First change"), WhatsNewPage("Second page", "Second change")),
        listOf(HolidayPack.HAUNTED_MANOR, HolidayPack.TRICK_OR_TREAT),
    )

    @After fun close() { state.close(); directory.deleteRecursively() }

    private fun show() {
        state.maybeShowReleaseAnnouncementOnStartup(release)
        rule.setContent {
            val look = resolveHolidayLook(state.settingsForReleaseThemePreview(), PREVIEW_TIME)
            CompositionLocalProvider(LocalTheme provides (look?.colors ?: LIGHT_THEME), LocalHoliday provides look) {
                AppText("Current preview: ${look?.theme?.label ?: "none"}")
                ReleaseAnnouncementDialog(state)
            }
        }
        rule.waitForIdle()
    }

    private fun assertButtonsCentered(dialogTag: String, first: String, last: String) {
        val dialog = rule.onNodeWithTag(dialogTag).getUnclippedBoundsInRoot()
        val firstButton = rule.onNodeWithText(first).getUnclippedBoundsInRoot()
        val lastButton = rule.onNodeWithText(last).getUnclippedBoundsInRoot()
        val dialogCenter = (dialog.left.value + dialog.right.value) / 2f
        val buttonsCenter = (firstButton.left.value + firstButton.right.value + lastButton.left.value + lastButton.right.value) / 4f
        assertTrue(kotlin.math.abs(dialogCenter - buttonsCenter) < 2f, "buttons should be centered in the dialog")
    }

    @Test fun nextPreviousSkipAndThemeSelection() {
        show()
        rule.onNodeWithTag(WHATS_NEW_TAG).assertExists()
        assertButtonsCentered(WHATS_NEW_TAG, "Skip", "Next")
        rule.onNodeWithText("What's New in this version").assertExists()
        rule.onNodeWithText("First page").assertExists()
        rule.onNodeWithText("Next").performClick()
        rule.onNodeWithText("Second page").assertExists()
        rule.onNodeWithText("Previous").performClick()
        rule.onNodeWithText("First page").assertExists()
        rule.onNodeWithText("Skip").performClick()
        rule.onNodeWithTag(HOLIDAY_ANNOUNCEMENT_TAG).assertExists()
        assertButtonsCentered(HOLIDAY_ANNOUNCEMENT_TAG, "Skip", "Use theme")
        rule.onNodeWithText("Use theme").assertIsNotEnabled()
        rule.onNodeWithTag(seasonalPackModeTag(HolidayPack.TRICK_OR_TREAT, dark = false)).performClick()
        rule.onNodeWithText("Use theme").performClick()
        rule.waitForIdle()
        assertEquals(HolidayPack.TRICK_OR_TREAT, state.settings.holiday.pack)
        assertEquals(HolidayMode.ALWAYS, state.settings.holiday.mode)
        assertEquals(HolidayLightness.LIGHT, state.settings.holiday.lightness)
        assertNull(state.releaseAnnouncementStep)
    }

    private fun showThemes() {
        show()
        rule.onNodeWithText("Skip").performClick()
        rule.onNodeWithTag(HOLIDAY_ANNOUNCEMENT_TAG).assertExists()
    }

    @Test fun everyAnnouncedThemeHasALightCardLeftAndADarkCardRight() {
        showThemes()
        val dialog = rule.onNodeWithTag(HOLIDAY_ANNOUNCEMENT_TAG).getUnclippedBoundsInRoot()
        assertTrue(dialog.width.value >= 700f - 1f, "the dialog is wide enough for two columns")
        val lightHeader = rule.onNodeWithTag("release-themes-header-light").getUnclippedBoundsInRoot()
        val darkHeader = rule.onNodeWithTag("release-themes-header-dark").getUnclippedBoundsInRoot()
        assertTrue(lightHeader.left < darkHeader.left)
        rule.onNodeWithText("Light").assertExists()
        rule.onNodeWithText("Dark").assertExists()
        release.themes.forEach { pack ->
            val light = rule.onNodeWithTag(seasonalPackModeTag(pack, dark = false)).getUnclippedBoundsInRoot()
            val dark = rule.onNodeWithTag(seasonalPackModeTag(pack, dark = true)).getUnclippedBoundsInRoot()
            assertTrue(light.right <= dark.left, "$pack: the light card is left of the dark one")
            assertTrue(kotlin.math.abs(light.top.value - dark.top.value) < 1f, "$pack: both cards share one row")
            assertTrue(light.left >= dialog.left && dark.right <= dialog.right, "$pack: both cards fit the dialog")
            assertTrue(light.left < lightHeader.right && dark.left >= lightHeader.right - 1.dp, "$pack: cards sit under their column headers")
            val label = HOLIDAY_THEMES.getValue(pack).label
            rule.onNodeWithContentDescription("$label, light").assertExists()
            rule.onNodeWithContentDescription("$label, dark").assertExists()
        }
    }

    @Test fun eachCardIsDrawnInItsOwnPaletteWhateverTheAppShows() {
        // The app currently shows the light fallback theme; the dark column must still use the theme's dark palette, and the light one its light.
        showThemes()
        HolidayPack.entries.filter { it in release.themes }.forEach { pack ->
            val theme = HOLIDAY_THEMES.getValue(pack)
            listOf(false, true).forEach { dark ->
                val bounds = rule.onNodeWithTag(seasonalPackModeTag(pack, dark)).getUnclippedBoundsInRoot()
                val pixel = rule.onNodeWithTag(seasonalPackModeTag(pack, dark)).captureToImage().toPixelMap()
                val expected = theme.palette(dark).p2
                val corner = pixel[(bounds.width.value * rule.density.density / 2).toInt(), (rule.density.density * 3).toInt()]
                assertTrue(
                    kotlin.math.abs(corner.red - expected.red) < .06f && kotlin.math.abs(corner.green - expected.green) < .06f &&
                        kotlin.math.abs(corner.blue - expected.blue) < .06f,
                    "$pack dark=$dark background $corner vs $expected",
                )
            }
        }
    }

    @Test fun pickingADarkCardPreviewsItInDarkAndUseThemeStoresTheLightness() {
        showThemes()
        val original = state.settings.holiday
        rule.onNodeWithTag(seasonalPackModeTag(HolidayPack.HAUNTED_MANOR, dark = true)).performClick()
        rule.onNodeWithText("Current preview: Haunted manor").assertExists()
        assertEquals(HolidayLightness.DARK, state.settingsForReleaseThemePreview().holiday.lightness)
        assertEquals(original, state.settings.holiday, "previewing leaves the settings untouched")
        rule.onNodeWithTag(seasonalPackModeTag(HolidayPack.HAUNTED_MANOR, dark = true)).assertIsSelected()
        rule.onNodeWithTag(seasonalPackModeTag(HolidayPack.HAUNTED_MANOR, dark = false)).assertIsNotSelected()
        rule.onNodeWithTag(seasonalPackModeTag(HolidayPack.TRICK_OR_TREAT, dark = true)).assertIsNotSelected()
        // The light card of the same theme previews it in light, and is then the selected one.
        rule.onNodeWithTag(seasonalPackModeTag(HolidayPack.HAUNTED_MANOR, dark = false)).performClick()
        assertEquals(HolidayLightness.LIGHT, state.settingsForReleaseThemePreview().holiday.lightness)
        rule.onNodeWithTag(seasonalPackModeTag(HolidayPack.HAUNTED_MANOR, dark = false)).assertIsSelected()
        rule.onNodeWithTag(seasonalPackModeTag(HolidayPack.HAUNTED_MANOR, dark = true)).assertIsNotSelected()
        rule.onNodeWithTag(seasonalPackModeTag(HolidayPack.TRICK_OR_TREAT, dark = true)).performClick()
        rule.onNodeWithText("Use theme").performClick()
        rule.waitForIdle()
        assertEquals(HolidayPack.TRICK_OR_TREAT, state.settings.holiday.pack)
        assertEquals(HolidayMode.ALWAYS, state.settings.holiday.mode)
        assertEquals(HolidayLightness.DARK, state.settings.holiday.lightness)
        assertNull(state.releaseAnnouncementStep)
    }

    @Test fun skipThemeDoesNotChangeAppearance() {
        show()
        val original = state.settings.holiday
        rule.onNodeWithText("Skip").performClick()
        rule.onNodeWithTag(HOLIDAY_ANNOUNCEMENT_TAG).assertExists()
        rule.onNodeWithTag(seasonalPackModeTag(HolidayPack.HAUNTED_MANOR, dark = true)).performClick()
        rule.onNodeWithText("Current preview: Haunted manor").assertExists()
        assertEquals(HolidayLightness.DARK, state.settingsForReleaseThemePreview().holiday.lightness)
        assertEquals(original, state.settings.holiday)
        rule.onNodeWithText("Skip").performClick()
        rule.waitForIdle()
        assertEquals(original, state.settings.holiday)
        rule.onNodeWithText("Current preview: none").assertExists()
        assertNull(state.releaseAnnouncementStep)
    }

    @Test fun previewShowsExamplePagesThenTheThemeSelectorWithoutAcknowledgingARelease() {
        state.previewReleaseAnnouncement()
        rule.setContent { CompositionLocalProvider(LocalTheme provides LIGHT_THEME) { ReleaseAnnouncementDialog(state) } }
        rule.onNodeWithText("Preview").assertExists()
        rule.onNodeWithText("Next").performClick()
        rule.onNodeWithText("Browsing changes").assertExists()
        rule.onNodeWithText("Done").performClick()
        rule.onNodeWithTag(HOLIDAY_ANNOUNCEMENT_TAG).assertExists()
        rule.onNodeWithText("Skip").performClick()
        assertEquals("", state.settings.lastSeenWhatsNewVersion)
        assertEquals("", state.settings.lastSeenHolidayAnnouncementVersion)
    }

    @Test fun applyingEitherBeerThemeKeepsExpandedTilesVisible() {
        val spec = TileChromeSpec<String>(
            headerDp = 32f, titleSp = 12f, gapRowDp = 8f, gapColumnDp = 8f,
            cornerDp = 6, minTileDp = 60f, minPairShare = .1f, tagPrefix = "beer-test", keyOf = { it },
        )
        state.maybeShowReleaseAnnouncementOnStartup(ReleaseAnnouncement("1.9.1", emptyList(), listOf(HolidayPack.BEER_TIME_YELLOW)))
        rule.setContent {
            val look = resolveHolidayLook(state.settingsForReleaseThemePreview(), LocalDateTime.of(2026, 11, 1, 12, 0))
            CompositionLocalProvider(LocalTheme provides (look?.colors ?: LIGHT_THEME), LocalHoliday provides look) {
                Box(Modifier.size(600.dp, 400.dp)) {
                    TilingSurface(
                        fullLayout = TileLeaf("filter", 1f), isVisible = { true }, collapsed = emptySet(), parked = emptySet(),
                        maximized = null, locked = false, widthDp = 600f, heightDp = 400f, spec = spec,
                        callbacks = TilingCallbacks(onCommitLayout = {}, onHide = {}, onToggleCollapse = {}, onMaximize = {},
                            onReclaimFocus = {}, dropLabel = { "drop" }),
                    ) {
                        Tile("filter", TileSlot(icon = Icons.Outlined.Description, title = "Filters") { AppText("Filter content") })
                    }
                }
                ReleaseAnnouncementDialog(state)
            }
        }
        listOf(HolidayPack.BEER_TIME_YELLOW, HolidayPack.BEER_TIME_GREEN).forEachIndexed { index, pack ->
            if (index > 0) rule.runOnIdle {
                state.maybeShowReleaseAnnouncementOnStartup(ReleaseAnnouncement("1.9.2", emptyList(), listOf(pack)))
            }
            rule.onNodeWithTag(seasonalPackModeTag(pack, dark = true)).performClick()
            rule.onNodeWithText("Use theme").performClick()
            rule.waitForIdle()
            assertEquals(pack, state.settings.holiday.pack)
            assertEquals(HolidayLightness.DARK, state.settings.holiday.lightness)
            rule.onNodeWithTag("beer-test-card-filter").assertExists()
            rule.onNodeWithText("Filter content").assertExists()
        }
    }

    private companion object {
        val PREVIEW_TIME: LocalDateTime = LocalDateTime.of(2026, 11, 1, 12, 0)
    }
}
