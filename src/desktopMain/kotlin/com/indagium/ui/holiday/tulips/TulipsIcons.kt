@file:Suppress("MagicNumber", "MaxLineLength")
package com.indagium.ui.holiday.tulips

import androidx.compose.ui.graphics.Color
import com.indagium.ui.holiday.*

/** The reference palette, tulips, and artist's tools. */
internal object TulipsIcons : HolidayIconSet {
    override fun icons(outline: Color): Map<ToolbarIconId, HolidayIcon> = holidayIconMap(outline, ::tulipIcon)
    override fun findIcon(outline: Color): HolidayIcon = icons(outline).getValue(ToolbarIconId.CASES).copy(name = "Find")
}

private fun tulipIcon(id: ToolbarIconId): IconSpec = when (id) {
    ToolbarIconId.NEW -> IconSpec("New tab", "a fresh palette", 1.4f, listOf(
        part("M16 4C8.5 4 3.5 9.5 3.5 16S9 28 15 28c2.6 0 3.2-1.6 2.4-3.2-.8-1.8.4-3.3 2.4-3.3H24c3 0 4.5-2.3 4.5-5.2C28.5 9.5 23 4 16 4z", fill = 0xFFe9dcc0L, stroke = OUTLINE),
        part(circlePath(10f, 11f, 2.2f), fill = 0xFF7b2fbfL, stroke = NONE),
        part(circlePath(16f, 8.5f, 2.2f), fill = 0xFFd4252eL, stroke = NONE),
        part(circlePath(22f, 11f, 2.2f), fill = 0xFFf2d21cL, stroke = NONE),
        part(circlePath(9.5f, 17.5f, 2.2f), fill = 0xFF6fb33fL, stroke = NONE),
        part(circlePath(23.5f, 17f, 2.2f), fill = 0xFFe46a9aL, stroke = NONE),
    ))
    ToolbarIconId.FILTERS -> IconSpec("Filters", "the tulip cup", 1.4f, listOf(
        part("M16 18v11M16 25c-4-2-7-6-7-10M16 24c3-1 6-4 6-8", fill = NONE, stroke = 0xFF3f8f2cL, width = 2f),
        part("M9 5l3.5 4 3.5-6 3.5 6L23 5v7a7 7 0 0 1-14 0z", fill = 0xFFec4899L, stroke = OUTLINE),
    ))
    ToolbarIconId.NOTES -> IconSpec("Notes", "sketch it down", 1.4f, listOf(
        part(roundRectPath(6.0f, 5.0f, 20.0f, 23.0f, 2.0f), fill = 0xFFfbfcf8L, stroke = OUTLINE),
        part("M10 3.5v3M14 3.5v3M18 3.5v3M22 3.5v3", fill = NONE, stroke = OUTLINE, width = 1.6f),
        part("M13 23v-6M13 17c-3 0-4-4-3-6 1 1 2 1 3 0 1 1 2 1 3 0 1 2 0 6-3 6z", fill = NONE, stroke = 0xFF7b2fbfL, width = 1.2f),
        part("M19 26l7-7 2 2-7 7h-2z", fill = 0xFFf2d21cL, stroke = OUTLINE),
    ))
    ToolbarIconId.AI -> IconSpec("AI", "a stroke of genius", 1.4f, listOf(
        part("M6 7v4M4 9h4", fill = NONE, stroke = 0xFFf2d21cL, width = 1.6f),
        part("M24 4l4 4-11 11-4-4z", fill = 0xFF8b5a2bL, stroke = OUTLINE),
        part("M13 15l4 4-2 2-4-4z", fill = 0xFFc9cdd2L, stroke = OUTLINE),
        part("M11 17l4 4c-1 3-4 6-9 6 0-5 3-8 5-10z", fill = 0xFF7b2fbfL, stroke = OUTLINE),
    ))
    ToolbarIconId.OPEN -> IconSpec("Open file", "put it on the easel", 1.4f, listOf(
        part("M9 29l5-21M23 29l-5-21M16 5v24", fill = NONE, stroke = 0xFF8b5a2bL, width = 1.8f),
        part(roundRectPath(7.0f, 7.0f, 18.0f, 13.0f, 1.0f), fill = 0xFFfbfcf8L, stroke = OUTLINE),
        part("M13 14.5v4M19 15.5v3", fill = NONE, stroke = 0xFF6fb33fL, width = 1.2f),
        part(circlePath(13f, 12.5f, 2.1f), fill = 0xFFd4252eL, stroke = NONE),
        part(circlePath(19f, 13.5f, 2.1f), fill = 0xFFf2d21cL, stroke = NONE),
        part("M6 20.5h20", fill = NONE, stroke = OUTLINE, width = 1.8f),
    ))
    ToolbarIconId.COMPARE -> IconSpec("Compare", "two tulips side by side", 1.4f, listOf(
        part("M10 16V28", fill = NONE, stroke = 0xFF6fb33fL, width = 1.6f),
        part("M5 7c0 6 2 9 5 9s5-3 5-9l-2.5 2.5L10 6l-2.5 3.5z", fill = 0xFF7b2fbfL, stroke = OUTLINE),
        part("M22 18V28", fill = NONE, stroke = 0xFF6fb33fL, width = 1.6f),
        part("M17 9c0 6 2 9 5 9s5-3 5-9l-2.5 2.5L22 8l-2.5 3.5z", fill = 0xFFd4252eL, stroke = OUTLINE),
    ))
    ToolbarIconId.LAYOUT -> IconSpec("Layout", "frames on the wall", 1.4f, listOf(
        part(roundRectPath(4.0f, 4.0f, 11.0f, 14.0f, 0.0f), fill = 0xFF7b2fbfL, stroke = 0xFF8b5a2bL, width = 1.8f),
        part(roundRectPath(17.0f, 4.0f, 11.0f, 9.0f, 0.0f), fill = 0xFFf2d21cL, stroke = 0xFF8b5a2bL, width = 1.8f),
        part(roundRectPath(17.0f, 15.0f, 11.0f, 13.0f, 0.0f), fill = 0xFFd4252eL, stroke = 0xFF8b5a2bL, width = 1.8f),
        part(roundRectPath(4.0f, 20.0f, 11.0f, 8.0f, 0.0f), fill = 0xFF6fb33fL, stroke = 0xFF8b5a2bL, width = 1.8f),
    ))
    ToolbarIconId.SETTINGS -> IconSpec("Settings", "petals like a gear", 1.4f, listOf(
        part("M12.4000 8.6000A3.6 5.4 0.0 1 0 19.6000 8.6000A3.6 5.4 0.0 1 0 12.4000 8.6000Z", fill = 0xFFe46a9aL, stroke = OUTLINE),
        part("M20.6086 9.1823A3.6 5.4 60.0 1 0 24.2086 15.4177A3.6 5.4 60.0 1 0 20.6086 9.1823Z", fill = 0xFFe46a9aL, stroke = OUTLINE),
        part("M24.2086 16.5823A3.6 5.4 120.0 1 0 20.6086 22.8177A3.6 5.4 120.0 1 0 24.2086 16.5823Z", fill = 0xFFe46a9aL, stroke = OUTLINE),
        part("M19.6000 23.4000A3.6 5.4 180.0 1 0 12.4000 23.4000A3.6 5.4 180.0 1 0 19.6000 23.4000Z", fill = 0xFFe46a9aL, stroke = OUTLINE),
        part("M11.3914 22.8177A3.6 5.4 240.0 1 0 7.7914 16.5823A3.6 5.4 240.0 1 0 11.3914 22.8177Z", fill = 0xFFe46a9aL, stroke = OUTLINE),
        part("M7.7914 15.4177A3.6 5.4 300.0 1 0 11.3914 9.1823A3.6 5.4 300.0 1 0 7.7914 15.4177Z", fill = 0xFFe46a9aL, stroke = OUTLINE),
        part(circlePath(16f, 16f, 4.2f), fill = 0xFFf2d21cL, stroke = OUTLINE),
    ))
    ToolbarIconId.CASES -> IconSpec("Cases", "look closer", 1.4f, listOf(
        part("M19 19l8.5 8.5", fill = NONE, stroke = OUTLINE, width = 3.4f),
        part(circlePath(13f, 13f, 8.5f), fill = 0xFFfbfcf8L, stroke = OUTLINE),
        part("M13 15v4", fill = NONE, stroke = 0xFF6fb33fL, width = 1.4f),
        part("M9.5 9.5c0 4 1.4 6 3.5 6s3.5-2 3.5-6l-1.7 1.7L13 8.8l-1.8 2.4z", fill = 0xFFd4252eL, stroke = NONE),
        part(circlePath(13f, 13f, 8.5f), fill = NONE, stroke = OUTLINE, width = 2f),
    ))
    ToolbarIconId.VIDEO -> IconSpec("Video", "a stroke of genius", 1.4f, listOf(
        part("M6 7v4M4 9h4", fill = NONE, stroke = 0xFFf2d21cL, width = 1.6f),
        part("M24 4l4 4-11 11-4-4z", fill = 0xFF8b5a2bL, stroke = OUTLINE),
        part("M13 15l4 4-2 2-4-4z", fill = 0xFFc9cdd2L, stroke = OUTLINE),
        part("M11 17l4 4c-1 3-4 6-9 6 0-5 3-8 5-10z", fill = 0xFF7b2fbfL, stroke = OUTLINE),
    ))
}
