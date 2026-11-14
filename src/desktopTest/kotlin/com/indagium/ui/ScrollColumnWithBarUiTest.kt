package com.indagium.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.unit.dp
import org.junit.Rule
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** [ScrollColumnWithBar]'s `showBarOnlyWhenScrollable`: a list that fits draws no thumb, an overflowing one does. */
class ScrollColumnWithBarUiTest {
    @get:Rule
    val rule = createComposeRule()

    /** How many pixels in the right-hand strip of the box differ from the plain background. */
    private fun thumbPixels(contentHeight: Int, onlyWhenScrollable: Boolean): Int {
        rule.setContent {
            CompositionLocalProvider(LocalTheme provides LIGHT_THEME) {
                Box(Modifier.size(120.dp, 200.dp).background(Color.White).testTag("host")) {
                    ScrollColumnWithBar(Modifier.size(120.dp, 200.dp), showBarOnlyWhenScrollable = onlyWhenScrollable) {
                        Spacer(Modifier.height(contentHeight.dp))
                    }
                }
            }
        }
        rule.waitForIdle()
        val pixels = rule.onNodeWithTag("host").captureToImage().toPixelMap()
        var differing = 0
        for (x in pixels.width - 8 until pixels.width) for (y in 0 until pixels.height) {
            if (pixels[x, y] != Color.White) differing++
        }
        return differing
    }

    @Test
    fun aListThatFitsShowsNoThumbWhenAskedToHideIt() {
        assertEquals(0, thumbPixels(contentHeight = 60, onlyWhenScrollable = true))
    }

    @Test
    fun anOverflowingListShowsTheThumbEvenWhenAskedToHideItWhenItFits() {
        assertTrue(thumbPixels(contentHeight = 600, onlyWhenScrollable = true) > 0)
    }
}
