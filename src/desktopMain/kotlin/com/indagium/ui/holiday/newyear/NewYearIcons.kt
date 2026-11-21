// Generated from the design canvas's icon kit (circles, ellipses, rectangles and group transforms baked into paths).
@file:Suppress("MagicNumber")

package com.indagium.ui.holiday.newyear

import androidx.compose.ui.graphics.Color
import com.indagium.ui.holiday.*

/** The New Year's Eve kit: champagne, the party hat and the clock. */
internal object NewYearIcons : HolidayIconSet {
    override fun icons(outline: Color): Map<ToolbarIconId, HolidayIcon> = holidayIconMap(outline, ::newYearIconsSpec)

    override fun findIcon(outline: Color): HolidayIcon = icons(outline).getValue(ToolbarIconId.CASES).copy(name = "Find")
}

// A table of vector art: each colour is one path, so the function is long by its data, not its logic.
@Suppress("LongMethod")
private fun newYearIconsSpec(id: ToolbarIconId): IconSpec = when (id) {
    ToolbarIconId.NEW -> IconSpec(
        "New tab",
        "pop a new one",
        1.4f,
        listOf(
            part(
                "M11 3.5h10l-1 9a4 4 0 0 1-8 0z",
                fill = 0xFFf2d27aL, stroke = OUTLINE, width = 1.4f,
            ),
            part(
                "M16 16.5v9M11 28h10",
                fill = NONE, stroke = OUTLINE, width = 1.8f,
            ),
            part(
                "M13.7 8A0.9 0.9 0 1 0 15.5 8A0.9 0.9 0 1 0 13.7 8Z",
                fill = 0xFFffffffL, stroke = NONE, width = 1.4f,
            ),
            part(
                "M16.7 6A0.7 0.7 0 1 0 18.1 6A0.7 0.7 0 1 0 16.7 6Z",
                fill = 0xFFffffffL, stroke = NONE, width = 1.4f,
            ),
            part(
                "M25 3.5l1 2.2 2.2 1-2.2 1L25 10l-1-2.3-2.2-1 2.2-1z",
                fill = 0xFFf2b33dL, stroke = NONE, width = 1.4f,
            ),
        ),
    )
    ToolbarIconId.FILTERS -> IconSpec(
        "Filters",
        "a funnel with a pompom",
        1.4f,
        listOf(
            part(
                "M16 5L7 28h18z",
                fill = 0xFF7c5cffL, stroke = OUTLINE, width = 1.4f,
            ),
            part(
                "M12.4 15.5h7.2M10.4 21.5h11.2",
                fill = NONE, stroke = 0xFFffd166L, width = 2.2f,
            ),
            part(
                "M13.5 4.5A2.5 2.5 0 1 0 18.5 4.5A2.5 2.5 0 1 0 13.5 4.5Z",
                fill = 0xFFff6faeL, stroke = OUTLINE, width = 1.4f,
            ),
        ),
    )
    ToolbarIconId.NOTES -> IconSpec(
        "Notes",
        "resolutions",
        1.4f,
        listOf(
            part(
                "M9 3.5H23A2 2 0 0 1 25 5.5V26.5A2 2 0 0 1 23 28.5H9A2 2 0 0 1 7 26.5V5.5A2 2 0 0 1 9 3.5Z",
                fill = 0xFFfbf3ddL, stroke = OUTLINE, width = 1.4f,
            ),
            part(
                "M11 9.5h10M11 13.5h10M11 17.5h6",
                fill = NONE, stroke = 0xFFb3261eL, width = 1.6f,
            ),
            part(
                "M11 22.5l1.6 1.6 3-3.4",
                fill = NONE, stroke = 0xFF2f8f4eL, width = 1.8f,
            ),
        ),
    )
    ToolbarIconId.AI -> IconSpec(
        "AI",
        "a bright idea",
        1.4f,
        listOf(
            part(
                "M16 3.5v6M16 22.5v6M3.5 16h6M22.5 16h6",
                fill = NONE, stroke = 0xFFf2b33dL, width = 1.4f,
            ),
            part(
                "M7.2 7.2l4 4M20.8 20.8l4 4M24.8 7.2l-4 4M11.2 20.8l-4 4",
                fill = NONE, stroke = 0xFFff6faeL, width = 1.4f,
            ),
            part(
                "M12.8 16A3.2 3.2 0 1 0 19.2 16A3.2 3.2 0 1 0 12.8 16Z",
                fill = 0xFF5ad1c8L, stroke = OUTLINE, width = 1.4f,
            ),
        ),
    )
    ToolbarIconId.VIDEO -> IconSpec(
        "Video",
        "lights and motion",
        1.4f,
        listOf(
            part(
                "M16 2v5",
                fill = NONE, stroke = OUTLINE, width = 1.4f,
            ),
            part(
                "M6 17A10 10 0 1 0 26 17A10 10 0 1 0 6 17Z",
                fill = 0xFFc9ced6L, stroke = OUTLINE, width = 1.4f,
            ),
            part(
                "M6.5 17h19M16 7.5v19M8 12h16M8 22h16M11 8.8v16.4M21 8.8v16.4",
                fill = NONE, stroke = 0xFF8a929eL, width = 0.8f,
            ),
            part(
                "M17.5 12.5H20.5V15.5H17.5Z",
                fill = 0xFFffffffL, stroke = NONE, width = 1.4f,
            ),
            part(
                "M11.5 18H14.5V21H11.5Z",
                fill = 0xFF7c5cffL, stroke = NONE, width = 1.4f,
            ),
        ),
    )
    ToolbarIconId.COMPARE -> IconSpec(
        "Compare",
        "side by side",
        1.4f,
        listOf(
            part(
                "M6 5h8l-.8 8a3.2 3.2 0 0 1-6.4 0z",
                fill = 0xFFf2d27aL, stroke = OUTLINE, width = 1.4f,
            ),
            part(
                "M10 16v9M7 27h6",
                fill = NONE, stroke = OUTLINE, width = 1.6f,
            ),
            part(
                "M18 5h8l-.8 8a3.2 3.2 0 0 1-6.4 0z",
                fill = 0xFFf2d27aL, stroke = OUTLINE, width = 1.4f,
            ),
            part(
                "M22 16v9M19 27h6",
                fill = NONE, stroke = OUTLINE, width = 1.6f,
            ),
            part(
                "M16 1.5v3M13.4 2.5l1 2M18.6 2.5l-1 2",
                fill = NONE, stroke = 0xFFf2b33dL, width = 1.4f,
            ),
        ),
    )
    ToolbarIconId.CASES -> IconSpec(
        "Cases",
        "find the shine",
        1.4f,
        listOf(
            part(
                "M4.5 13A8.5 8.5 0 1 0 21.5 13A8.5 8.5 0 1 0 4.5 13Z",
                fill = 0xFFfff8e1L, stroke = OUTLINE, width = 1.4f,
            ),
            part(
                "M19.5 19.5l8 8",
                fill = NONE, stroke = 0xFFe0a526L, width = 4.4f,
            ),
            part(
                "M13 8l1.2 3.8L18 13l-3.8 1.2L13 18l-1.2-3.8L8 13l3.8-1.2z",
                fill = 0xFFe0a526L, stroke = NONE, width = 1.4f,
            ),
        ),
    )
    ToolbarIconId.OPEN -> IconSpec(
        "Open file",
        "pop it open",
        1.4f,
        listOf(
            part(
                "M4.5 27.5l5-14 9 9z",
                fill = 0xFFff6faeL, stroke = OUTLINE, width = 1.4f,
            ),
            part(
                "M7.2 21l4 4M8.6 16.6l6.8 6.8",
                fill = NONE, stroke = 0xFFffd166L, width = 1.5f,
            ),
            part(
                "M17.5 6.5l1 3M24 8l-2 2M27 14h-3M21 3v2",
                fill = NONE, stroke = 0xFF7c5cffL, width = 1.8f,
            ),
            part(
                "M22.2 4A1.3 1.3 0 1 0 24.8 4A1.3 1.3 0 1 0 22.2 4Z",
                fill = 0xFF3fb950L, stroke = NONE, width = 1.4f,
            ),
            part(
                "M26.2 10A1.3 1.3 0 1 0 28.8 10A1.3 1.3 0 1 0 26.2 10Z",
                fill = 0xFFf2b33dL, stroke = NONE, width = 1.4f,
            ),
        ),
    )
    ToolbarIconId.LAYOUT -> IconSpec(
        "Layout",
        "a grid of days",
        1.4f,
        listOf(
            part(
                "M6 6H26A2 2 0 0 1 28 8V26A2 2 0 0 1 26 28H6A2 2 0 0 1 4 26V8A2 2 0 0 1 6 6Z",
                fill = 0xFFffffffL, stroke = OUTLINE, width = 1.4f,
            ),
            part(
                "M4 12V8a2 2 0 0 1 2-2h20a2 2 0 0 1 2 2v4z",
                fill = 0xFFd94040L, stroke = OUTLINE, width = 1.4f,
            ),
            part(
                "M10 3.5v5M22 3.5v5",
                fill = NONE, stroke = OUTLINE, width = 1.8f,
            ),
            part(
                "M8 15h4v4H8zM14 15h4v4h-4zM20 15h4v4h-4zM8 21h4v4H8zM14 21h4v4h-4z",
                fill = 0xFFc9ced6L, stroke = NONE, width = 1.4f,
            ),
            part(
                "M20 21H24V25H20Z",
                fill = 0xFFe8c15aL, stroke = NONE, width = 1.4f,
            ),
        ),
    )
    ToolbarIconId.SETTINGS -> IconSpec(
        "Settings",
        "set it right",
        1.4f,
        listOf(
            part(
                "M4.5 16A11.5 11.5 0 1 0 27.5 16A11.5 11.5 0 1 0 4.5 16Z",
                fill = 0xFFfff8e1L, stroke = OUTLINE, width = 1.4f,
            ),
            part(
                "M16 6.5v2M16 23.5v2M6.5 16h2M23.5 16h2",
                fill = NONE, stroke = 0xFFe0a526L, width = 1.4f,
            ),
            part(
                "M16 16V8.5",
                fill = NONE, stroke = OUTLINE, width = 1.4f,
            ),
            part(
                "M16 16v-5",
                fill = NONE, stroke = OUTLINE, width = 2.6f,
            ),
            part(
                "M14.6 16A1.4 1.4 0 1 0 17.4 16A1.4 1.4 0 1 0 14.6 16Z",
                fill = OUTLINE, stroke = NONE, width = 1.4f,
            ),
        ),
    )
}
