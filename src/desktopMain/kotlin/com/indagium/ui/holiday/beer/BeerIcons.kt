@file:Suppress("MagicNumber")

package com.indagium.ui.holiday.beer

import androidx.compose.ui.graphics.Color
import com.indagium.ui.holiday.*

internal object BeerIcons : HolidayIconSet {
    override fun icons(outline: Color): Map<ToolbarIconId, HolidayIcon> = holidayIconMap(outline, ::beerIcon)

    override fun findIcon(outline: Color): HolidayIcon = icons(outline).getValue(ToolbarIconId.CASES).copy(name = "Find")
}

private fun beerIcon(id: ToolbarIconId): IconSpec = when (id) {
    ToolbarIconId.NEW -> IconSpec("New tab", "a fresh one", 1.4f, listOf(
        part("M20 13h3a3 3 0 0 1 3 3v5a3 3 0 0 1-3 3h-3", fill = NONE, stroke = OUTLINE, width = 2.0f),
        part(roundRectPath(6.0f, 11.0f, 14.0f, 17.0f, 2.0f), fill = 0xFFf2b33d, stroke = OUTLINE),
        part("M5 12c-1-3 2-5 4.5-4 1-2.6 5-3 6.5-.6 1.6-1.8 4.6-1 5 1.4 1 .6 1 2.2 0 3.2z", fill = 0xFFfff4dc, stroke = OUTLINE),
        part("M13 16v7.5M9.25 19.75h7.5", fill = NONE, stroke = 0xFF7a3c10, width = 2.2f),
    ))
    ToolbarIconId.FILTERS -> IconSpec("Filters", "a pilsner glass is a funnel", 1.4f, listOf(
        part("M8 6.5h16l-4.6 21h-6.8z", fill = 0xFFf2b33d, stroke = OUTLINE),
        part("M8.9 6.8h14.2l-.8 3.6H9.7z", fill = 0xFFfff4dc, stroke = NONE),
        part("M7 7c0-2.2 2.4-3 3.8-1.8 1-1.7 3.8-1.7 4.8 0 1.2-1.5 4-1.3 4.6.4 1.4-.9 4 0 4 1.4z", fill = 0xFFfff4dc, stroke = OUTLINE),
        part(circlePath(15.0f, 16.0f, 1.1f), fill = 0xFFfff4dc, stroke = NONE),
        part(circlePath(17.6f, 20.5f, 0.9f), fill = 0xFFfff4dc, stroke = NONE),
        part(circlePath(15.4f, 24.0f, 0.7f), fill = 0xFFfff4dc, stroke = NONE),
    ))
    ToolbarIconId.NOTES -> IconSpec("Notes", "write it on the board", 1.4f, listOf(
        part("M10 25l-3 4.5M22 25l3 4.5", fill = NONE, stroke = 0xFF8a5a2c, width = 2.2f),
        part(roundRectPath(4.0f, 5.0f, 24.0f, 20.0f, 2.0f), fill = 0xFF2b2b2b, stroke = 0xFF8a5a2c, width = 2.4f),
        part("M8.5 11h9.5M8.5 15h13M8.5 19h7.5", fill = NONE, stroke = 0xFFf6ecd6, width = 1.6f),
    ))
    ToolbarIconId.AI -> IconSpec("AI", "ring for the bartender", 1.4f, listOf(
        part("M16 5.5v2.2M9.5 7.5l1.4 1.6M22.5 7.5l-1.4 1.6", fill = NONE, stroke = 0xFFf2b33d, width = 1.6f),
        part(roundRectPath(14.0f, 11.0f, 4.0f, 2.6f, 1.0f), fill = 0xFFc9cdd2, stroke = OUTLINE),
        part("M6.5 23a9.5 9.5 0 0 1 19 0z", fill = 0xFFd4a017, stroke = OUTLINE),
        part("M11 20.5a5.6 5.6 0 0 1 3.4-3.6", fill = NONE, stroke = 0xFFfff4dc, width = 1.6f),
        part(roundRectPath(4.0f, 23.0f, 24.0f, 4.0f, 1.5f), fill = 0xFF8a5a2c, stroke = OUTLINE),
    ))
    ToolbarIconId.OPEN -> IconSpec("Open file", "pop it open", 1.4f, listOf(
        part(
            "M9 26.5V16c0-2.4 2.2-3.6 2.8-6.2.3-1.2.3-2.4.3-3.6V5.4h3v.8c0 1.2 0 2.4.3 3.6.6 " +
                "2.6 2.8 3.8 2.8 6.2v10.5a1.5 1.5 0 0 1-1.5 1.5h-6A1.5 1.5 0 0 1 9 26.5z",
            fill = 0xFF2a8a38,
            stroke = OUTLINE,
        ),
        part(ellipsePath(13.5f, 20.0f, 3.3f, 2.7f), fill = 0xFFf4f6f3, stroke = NONE),
        part(circlePath(13.5f, 9.8f, 1.0f), fill = 0xFFe1252e, stroke = NONE),
        part("M11.6 5c-1.8-1-1.6-3.4.3-3.6.6-1.4 2.7-1.3 3.1.2 1.6.4 1.6 2.7 0 3.4z", fill = 0xFFfff4dc, stroke = OUTLINE),
        part("M21.5 5h5v2.4h-5z", fill = 0xFFc9cdd2, stroke = OUTLINE),
        part("M19 9.5l2.6-1.2M19.5 12.5l2.6-.2", fill = NONE, stroke = 0xFFf2b33d, width = 1.4f),
    ))
    ToolbarIconId.COMPARE -> IconSpec("Compare", "two side by side", 1.4f, listOf(
        part(
            "M4 26.5V16c0-2.4 2.2-3.6 2.8-6.2.3-1.2.3-2.4.3-3.6V5.4h3v.8c0 1.2 0 2.4.3 3.6.6 " +
                "2.6 2.8 3.8 2.8 6.2v10.5a1.5 1.5 0 0 1-1.5 1.5h-6A1.5 1.5 0 0 1 4 26.5z",
            fill = 0xFF2a8a38,
            stroke = OUTLINE,
        ),
        part(ellipsePath(8.5f, 20.0f, 3.3f, 2.7f), fill = 0xFFf4f6f3, stroke = NONE),
        part(circlePath(8.5f, 9.8f, 1.0f), fill = 0xFFe1252e, stroke = NONE),
        part("M6.6 5.4h3.8V3.8h-3.8z", fill = 0xFFc9cdd2, stroke = OUTLINE),
        part(
            "M19 26.5V16c0-2.4 2.2-3.6 2.8-6.2.3-1.2.3-2.4.3-3.6V5.4h3v.8c0 1.2 0 2.4.3 3.6.6 " +
                "2.6 2.8 3.8 2.8 6.2v10.5a1.5 1.5 0 0 1-1.5 1.5h-6A1.5 1.5 0 0 1 19 26.5z",
            fill = 0xFF1d6e2c,
            stroke = OUTLINE,
        ),
        part(ellipsePath(23.5f, 20.0f, 3.3f, 2.7f), fill = 0xFFf4f6f3, stroke = NONE),
        part(circlePath(23.5f, 9.8f, 1.0f), fill = 0xFFe1252e, stroke = NONE),
        part("M21.6 5.4h3.8V3.8h-3.8z", fill = 0xFFc9cdd2, stroke = OUTLINE),
    ))
    ToolbarIconId.LAYOUT -> IconSpec("Layout", "bottles in a grid", 1.4f, listOf(
        part(roundRectPath(4.0f, 4.0f, 24.0f, 24.0f, 2.5f), fill = 0xFFb8642a, stroke = OUTLINE),
        part("M4 16h24M16 4v24", fill = NONE, stroke = 0xFF7a3c10, width = 1.4f),
        part(circlePath(10.0f, 10.0f, 3.6f), fill = 0xFFd4a017, stroke = 0xFF8a6410, width = 1.6f),
        part(circlePath(22.0f, 10.0f, 3.6f), fill = 0xFFd4a017, stroke = 0xFF8a6410, width = 1.6f),
        part(circlePath(10.0f, 22.0f, 3.6f), fill = 0xFFd4a017, stroke = 0xFF8a6410, width = 1.6f),
        part(circlePath(22.0f, 22.0f, 3.6f), fill = 0xFFd4a017, stroke = 0xFF8a6410, width = 1.6f),
    ))
    ToolbarIconId.SETTINGS -> IconSpec("Settings", "teeth like a gear", 1.4f, listOf(
        part(circlePath(16.0f, 16.0f, 11.5f), fill = 0xFFd4a017, stroke = 0xFF8a6410, width = 3.6f),
        part(circlePath(16.0f, 16.0f, 7.5f), fill = 0xFFe8b84a, stroke = 0xFF8a6410, width = 1.4f),
        part(circlePath(16.0f, 16.0f, 2.6f), fill = 0xFF8a6410, stroke = NONE),
    ))
    ToolbarIconId.CASES -> IconSpec("Cases", "take a closer look", 1.4f, listOf(
        part("M19 19l8.5 8.5", fill = NONE, stroke = OUTLINE, width = 3.6f),
        part(circlePath(13.0f, 13.0f, 8.0f), fill = 0xFFf2b33d, stroke = NONE),
        part("M5.25 11A8 8 0 0 1 20.75 11z", fill = 0xFFfff4dc, stroke = NONE),
        part(circlePath(11.0f, 16.0f, 1.2f), fill = 0xFFfff4dc, stroke = NONE),
        part(circlePath(15.0f, 14.5f, 0.9f), fill = 0xFFfff4dc, stroke = NONE),
        part(circlePath(14.0f, 18.5f, 0.8f), fill = 0xFFfff4dc, stroke = NONE),
        part(circlePath(13.0f, 13.0f, 8.0f), fill = NONE, stroke = OUTLINE, width = 2.2f),
    ))
    ToolbarIconId.VIDEO -> IconSpec("Video", "ring the bell", 1.4f, listOf(
        part("M16 5.5v2.2M9.5 7.5l1.4 1.6M22.5 7.5l-1.4 1.6", fill = NONE, stroke = 0xFFf2b33d, width = 1.6f),
        part(roundRectPath(14.0f, 11.0f, 4.0f, 2.6f, 1.0f), fill = 0xFFc9cdd2, stroke = OUTLINE),
        part("M6.5 23a9.5 9.5 0 0 1 19 0z", fill = 0xFFd4a017, stroke = OUTLINE),
        part("M11 20.5a5.6 5.6 0 0 1 3.4-3.6", fill = NONE, stroke = 0xFFfff4dc, width = 1.6f),
        part(roundRectPath(4.0f, 23.0f, 24.0f, 4.0f, 1.5f), fill = 0xFF8a5a2c, stroke = OUTLINE),
    ))
}
