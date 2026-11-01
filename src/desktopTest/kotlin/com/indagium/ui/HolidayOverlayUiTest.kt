// The test window's size is literal on purpose.
@file:Suppress("MagicNumber")

package com.indagium.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.unit.dp
import com.indagium.model.AppSettings
import com.indagium.model.HolidayIntensity
import com.indagium.model.HolidayLightness
import com.indagium.model.HolidayMode
import com.indagium.model.HolidayPack
import com.indagium.model.HolidaySettings
import com.indagium.ui.holiday.*
import org.junit.Rule
import org.junit.Test
import java.time.LocalDate
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** The effects overlay is drawn only while there is something to animate, and never takes a click. */
@OptIn(ExperimentalTestApi::class)
class HolidayOverlayUiTest {
    @get:Rule
    val rule = createComposeRule()

    private fun lookWith(edit: (HolidaySettings) -> HolidaySettings): HolidayLook? =
        resolveHolidayLook(AppSettings().withHoliday { edit(it.copy(mode = HolidayMode.ALWAYS)) }, LocalDate.of(2026, 10, 31))

    private fun setup(look: HolidayLook?, paused: Boolean = false, onClick: () -> Unit = {}) {
        rule.setContent {
            CompositionLocalProvider(LocalHoliday provides look, LocalHolidayPaused provides paused) {
                Box(Modifier.size(400.dp, 300.dp)) {
                    Box(Modifier.fillMaxSize().testTag("under").clickable(onClick = onClick))
                    HolidayEffectsOverlay(Modifier.fillMaxSize())
                }
            }
        }
        rule.waitForIdle()
    }

    private fun overlayExists() = rule.onAllNodesWithTag(HOLIDAY_EFFECTS_OVERLAY_TAG).fetchSemanticsNodes().isNotEmpty()

    @Test
    fun theOverlayIsThereUnderAPackAndLetsClicksThrough() {
        var clicks = 0
        setup(lookWith { it }, onClick = { clicks++ })
        assertTrue(overlayExists(), "the overlay is drawn")
        rule.onNodeWithTag("under").performClick()
        rule.waitForIdle()
        assertEquals(1, clicks, "the click reached the node under the overlay")
    }

    @Test
    fun noOverlayWithoutAPack() {
        setup(null)
        assertTrue(!overlayExists())
    }

    @Test
    fun noOverlayWhenIntensityIsOffOrNothingIsSwitchedOn() {
        setup(lookWith { it.copy(intensity = HolidayIntensity.OFF) })
        assertTrue(!overlayExists(), "intensity Off stops all motion")
    }

    @Test
    fun noOverlayWhenAllThreeEffectsAreOff() {
        setup(lookWith { it.copy(effectToggles = mapOf("pumpkin.embers" to false, "pumpkin.bats" to false, "pumpkin.lightning" to false)) })
        assertTrue(!overlayExists())
    }

    @Test
    fun noOverlayWhilePaused() {
        setup(lookWith { it }, paused = true)
        assertTrue(!overlayExists(), "capturing or playing video pauses the effects")
    }

    @Test
    fun manorGhostIsVisibleAsAFaintWashOverOpaqueContent() {
        val look = lookWith {
            it.copy(pack = HolidayPack.HAUNTED_MANOR, lightness = HolidayLightness.DARK,
                effectToggles = mapOf("manor.fog" to false, "manor.lightning" to false))
        }
        val background = Color(0xFF141c19)
        rule.setContent {
            CompositionLocalProvider(LocalHoliday provides look, LocalHolidayPaused provides false) {
                Box(Modifier.size(400.dp, 300.dp).testTag("scene")) {
                    HolidayEffectsOverlay(Modifier.fillMaxSize(), behindContent = true)
                    Box(Modifier.fillMaxSize().background(background).testTag("content"))
                    HolidayEffectsOverlay(Modifier.fillMaxSize())
                }
            }
        }
        rule.waitForIdle()
        assertTrue(rule.onAllNodesWithTag(HOLIDAY_EFFECTS_BEHIND_TAG).fetchSemanticsNodes().isNotEmpty())
        assertTrue(rule.onAllNodesWithTag(HOLIDAY_EFFECTS_OVERLAY_TAG).fetchSemanticsNodes().isNotEmpty())
        val pixels = rule.onNodeWithTag("scene").captureToImage().toPixelMap()
        val density = rule.density.density
        val ghostPixel = pixels[(340f * density).toInt(), (260f * density).toInt()]
        assertTrue(ghostPixel.red > background.red + 0.02f, "the ghost remains visible through opaque content")
        assertTrue(ghostPixel.red < 0.3f, "the wash stays faint enough for text")
    }
}
