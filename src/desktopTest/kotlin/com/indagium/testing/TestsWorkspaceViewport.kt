package com.indagium.testing

import androidx.compose.ui.InternalComposeUiApi
import androidx.compose.ui.test.InternalTestApi
import androidx.compose.ui.test.junit4.ComposeContentTestRule
import androidx.compose.ui.test.junit4.DesktopComposeTestRule
import androidx.compose.ui.unit.IntSize

/** Resize layout constraints. Full-size raster/input checks use runDesktopComposeUiTest(width, height) instead. */
@OptIn(InternalComposeUiApi::class, InternalTestApi::class)
internal fun ComposeContentTestRule.setTestsViewport(width: Int, height: Int) {
    runOnUiThread { (this as DesktopComposeTestRule).scene.size = IntSize(width, height) }
}
