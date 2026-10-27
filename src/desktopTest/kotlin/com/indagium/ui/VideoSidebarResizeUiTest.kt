package com.indagium.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Description
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.getBoundsInRoot
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.height
import com.indagium.model.LogViewSectionId
import org.junit.Rule
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

@OptIn(ExperimentalTestApi::class)
class VideoSidebarResizeUiTest {
    @get:Rule
    val rule = createComposeRule()

    @Test
    fun realSidebarSplitterResizesVideoWithOtherSidebarTilesCollapsed() {
        val visibleTiles = listOf(LogViewSectionId.LOG, LogViewSectionId.NOTES, LogViewSectionId.DIAGRAMS, LogViewSectionId.AI, LogViewSectionId.VIDEO)
        val collapsed = setOf(LogViewSectionId.DIAGRAMS, LogViewSectionId.NOTES, LogViewSectionId.AI)
        val layout = mutableStateOf(classicLogViewLayout(LogViewLegacySizes()))
        val callbacks = TilingCallbacks<LogViewSectionId>(
            onCommitLayout = { layout.value = it },
            onHide = {},
            onToggleCollapse = {},
            onMaximize = {},
            onReclaimFocus = {},
            dropLabel = { "Move tile" },
        )

        rule.setContent {
            CompositionLocalProvider(LocalTheme provides LIGHT_THEME) {
                Box(Modifier.size(900.dp, 700.dp)) {
                    LogViewSurface(
                        layout = layout.value,
                        isVisible = { it in visibleTiles },
                        collapsed = collapsed,
                        parked = emptySet(),
                        locked = false,
                        maximized = null,
                        widthDp = 900f,
                        heightDp = 700f,
                        callbacks = callbacks,
                    ) {
                        visibleTiles.forEach { id ->
                            Tile(
                                id,
                                TileSlot(
                                    icon = Icons.Outlined.Description,
                                    title = logViewTileTitle(id),
                                    collapsible = true,
                                    sizing = TileBodySizing.FILL,
                                    body = { Box(Modifier.fillMaxSize().testTag("video-resize-body-${id.key}")) },
                                ),
                            )
                        }
                    }
                }
            }
        }
        rule.waitForIdle()

        val videoBefore = rule.onNodeWithTag("tile-card-video").getBoundsInRoot()
        val logBefore = rule.onNodeWithTag("tile-card-log").getBoundsInRoot()
        val bodyBefore = rule.onNodeWithTag("video-resize-body-video").getBoundsInRoot()
        assertTrue(logBefore.right < videoBefore.left, "the main log remains beside the Video sidebar")
        assertTrue(bodyBefore.height < videoBefore.height, "Video body receives the bounded card area below its header")
        assertTrue(bodyBefore.height > 400.dp, "the lone expanded Video initially fills the available sidebar height")
        val splitter = rule.onNodeWithTag("tile-splitter-2")
        val splitterBefore = splitter.getBoundsInRoot()
        assertEquals(videoBefore.bottom.value, splitterBefore.top.value, 1f, "the splitter immediately below Video is interactive")

        val density = rule.density.density
        splitter.performTouchInput {
            down(center)
            moveBy(Offset(0f, -80f * density))
            up()
        }
        rule.waitForIdle()

        val videoAfter = rule.onNodeWithTag("tile-card-video").getBoundsInRoot()
        assertTrue(videoAfter.height < videoBefore.height - 40.dp, "dragging the actual Video splitter changes Video height")
        assertEquals(videoAfter.height.value, layout.value.videoSlot()!!.second!!, 1f, "the resized Video height is stored")
    }
}
