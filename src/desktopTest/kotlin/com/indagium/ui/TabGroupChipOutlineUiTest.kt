package com.indagium.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.unit.dp
import com.indagium.model.TabStripPosition
import com.indagium.model.TabStripStyle
import org.junit.Rule
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals

/**
 * A group chip always carries a 1 dp border in the group's colour, drawn in the style's own shape on the edge facing away from the
 * content (the side a raised card does not open), and its fill stays the style's normal cell fill.
 */
@OptIn(ExperimentalTestApi::class)
class TabGroupChipOutlineUiTest {
    @get:Rule
    val rule = createComposeRule()

    private companion object {
        const val CAPSULE_END_TOLERANCE = 40
    }

    private val groupColor = Color(0xFFEC4899)

    private fun group(id: String, tintMembers: Boolean) = TabGroup(
        id = id,
        name = "Logs",
        colorArgb = groupColor.toArgb(),
        collapsed = false,
        members = listOf(TabRef.Log("x")),
        tintMembers = tintMembers,
    )

    private fun setChips(position: TabStripPosition, tintMembers: Boolean) {
        rule.setContent {
            CompositionLocalProvider(LocalTheme provides LIGHT_THEME) {
                Column(Modifier.width(420.dp)) {
                    TabStripStyle.entries.forEach { style ->
                        Row(Modifier.background(tabStripBackground(style, LIGHT_THEME))) {
                            TabGroupHeader(
                                group = group("g-${style.name}", tintMembers),
                                vertical = false,
                                width = 160.dp,
                                height = tabStripHeightDp(style).dp,
                                appearance = TabAppearance(style = style, position = position),
                                onClick = {},
                                onContextMenu = {},
                            )
                        }
                    }
                }
            }
        }
        rule.waitForIdle()
    }

    private fun chipPixels(style: TabStripStyle) =
        rule.onNodeWithTag("tab-group-chip-g-${style.name}", useUnmergedTree = true).captureToImage().toPixelMap()

    @Test
    fun theChipOutlineIsTheGroupColourOnTheOuterEdgeOfEveryStyleOnEveryEdge() {
        TabStripPosition.entries.forEach { position ->
            setChips(position, tintMembers = true)
            TabStripStyle.entries.forEach { style ->
                val image = chipPixels(style)
                // The middle of the edge facing away from the content: a 1 dp band, whatever the style's corners do.
                val (x, y) = when (position) {
                    TabStripPosition.TOP -> image.width / 2 to 0
                    TabStripPosition.BOTTOM -> image.width / 2 to image.height - 1
                    TabStripPosition.LEFT -> 0 to image.height / 2
                    TabStripPosition.RIGHT -> image.width - 1 to image.height / 2
                }
                // A pill's end is a half circle: its outermost pixel at the middle is only anti-aliased onto the border.
                val capsuleEnd = style == TabStripStyle.PILL && (position == TabStripPosition.LEFT || position == TabStripPosition.RIGHT)
                assertArgbNear(
                    groupColor.toArgb(), image[x, y].toArgb(), "$style on $position: the chip's outline is the group colour",
                    tolerance = if (capsuleEnd) CAPSULE_END_TOLERANCE else 2,
                )
            }
        }
    }

    @Test
    fun theChipOutlineIsDrawnEvenWhenTheGroupDoesNotTintItsMembersAndTheFillStaysTheStylesOwn() {
        setChips(TabStripPosition.TOP, tintMembers = false)
        TabStripStyle.entries.forEach { style ->
            val image = chipPixels(style)
            assertArgbNear(groupColor.toArgb(), image[image.width / 2, 0].toArgb(), "$style: the outline does not depend on the tint option")
            // Inside the cell, clear of the dot, the label and the outline: the style's own idle fill (never the group colour).
            val inside = image[image.width - 6, image.height / 2].toArgb()
            assertNotEquals(groupColor.toArgb(), inside, "$style: the chip is not filled with the group colour")
            // Only the pill fills an idle cell with the panel tone; every other style leaves the strip's own colour.
            assertEquals(
                if (style == TabStripStyle.PILL) LIGHT_THEME.p.toArgb() else tabStripBackground(style, LIGHT_THEME).toArgb(),
                inside,
                "$style: the chip keeps the style's normal cell fill",
            )
        }
    }
}
