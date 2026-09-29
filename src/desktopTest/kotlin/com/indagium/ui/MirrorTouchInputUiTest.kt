package com.indagium.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.click
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import com.indagium.capture.mirror.DevicePoint
import com.indagium.capture.mirror.MirrorTouchAction
import org.junit.Rule
import org.junit.Test
import kotlin.test.assertEquals

// Regression test for taps landing in the wrong place on the software (Compose) mirror, the default
// on Windows/Linux: the touch mapper was built once from a size captured when the gesture handler
// started, so after the surface resized (a landscape frame arriving, a window/sidebar resize) taps
// were converted against the old dimensions.
class MirrorTouchInputUiTest {
    @get:Rule
    val rule = createComposeRule()

    @Test
    fun tapsMapAgainstTheSurfaceSizeAfterItResizes() {
        val touches = mutableListOf<Pair<MirrorTouchAction, DevicePoint?>>()
        var surfaceWidth by mutableStateOf(FRAME_WIDTH)
        rule.setContent {
            // 1 dp == 1 px, so the offsets below are exact surface pixels.
            CompositionLocalProvider(LocalDensity provides Density(1f)) {
                Box(
                    Modifier.size(surfaceWidth.dp, FRAME_HEIGHT.dp).testTag(SURFACE_TAG)
                        .mirrorTouchInput(Unit, FRAME_WIDTH, FRAME_HEIGHT) { mapper, action, _, x, y ->
                            touches += action to mapper.map(x, y)
                        },
                )
            }
        }

        // The handler starts while the surface exactly matches the frame.
        rule.onNodeWithTag(SURFACE_TAG).performTouchInput { click(Offset(50f, 50f)) }
        rule.waitForIdle()
        assertEquals(DevicePoint(50, 50), touches.first { it.first == MirrorTouchAction.DOWN }.second)

        // Twice as wide: the 200x100 frame is now letterboxed with 100 px bars on each side, so
        // surface x = 250 is frame x = 150. The stale 200 px viewport treated x = 250 as outside
        // the picture and sent nothing.
        touches.clear()
        surfaceWidth = FRAME_WIDTH * 2
        rule.waitForIdle()
        rule.onNodeWithTag(SURFACE_TAG).performTouchInput { click(Offset(250f, 50f)) }
        rule.waitForIdle()
        assertEquals(DevicePoint(150, 50), touches.first { it.first == MirrorTouchAction.DOWN }.second)
    }

    private companion object {
        const val FRAME_WIDTH = 200
        const val FRAME_HEIGHT = 100
        const val SURFACE_TAG = "mirror-surface"
    }
}
