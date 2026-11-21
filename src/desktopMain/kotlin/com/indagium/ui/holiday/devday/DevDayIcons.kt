// Generated from the design canvas's icon kit (circles, ellipses, rectangles and group transforms baked into paths).
@file:Suppress("MagicNumber")

package com.indagium.ui.holiday.devday

import androidx.compose.ui.graphics.Color
import com.indagium.ui.holiday.*

/** The Programmers' Day kit: the terminal, grep and the regex. */
internal object DevDayIcons : HolidayIconSet {
    override fun icons(outline: Color): Map<ToolbarIconId, HolidayIcon> = holidayIconMap(outline, ::devDayIconsSpec)

    override fun findIcon(outline: Color): HolidayIcon = icons(outline).getValue(ToolbarIconId.CASES).copy(name = "Find")
}

// A table of vector art: each colour is one path, so the function is long by its data, not its logic.
@Suppress("LongMethod")
private fun devDayIconsSpec(id: ToolbarIconId): IconSpec = when (id) {
    ToolbarIconId.NEW -> IconSpec(
        "New tab",
        "a fresh prompt",
        1.4f,
        listOf(
            part(
                "M6 6H26A2 2 0 0 1 28 8V24A2 2 0 0 1 26 26H6A2 2 0 0 1 4 24V8A2 2 0 0 1 6 6Z",
                fill = 0xFF0a120cL, stroke = OUTLINE, width = 1.4f,
            ),
            part(
                "M9 13l4 3-4 3",
                fill = NONE, stroke = 0xFF39ff6aL, width = 2f,
            ),
            part(
                "M15 20h7",
                fill = NONE, stroke = 0xFF39ff6aL, width = 2f,
            ),
        ),
    )
    ToolbarIconId.FILTERS -> IconSpec(
        "Filters",
        "| grep",
        1.4f,
        listOf(
            part(
                "M4 6h24l-9 11v8l-6 3V17z",
                fill = 0xFF1d3b22L, stroke = OUTLINE, width = 1.4f,
            ),
            part(
                "M16 9v5",
                fill = NONE, stroke = 0xFF39ff6aL, width = 2.2f,
            ),
        ),
    )
    ToolbarIconId.NOTES -> IconSpec(
        "Notes",
        "// a comment",
        1.4f,
        listOf(
            part(
                "M5 6h22v15H14l-6 5v-5H5z",
                fill = 0xFF0a120cL, stroke = OUTLINE, width = 1.4f,
            ),
            part(
                "M12 17l3-7M17 17l3-7",
                fill = NONE, stroke = 0xFF39ff6aL, width = 2f,
            ),
        ),
    )
    ToolbarIconId.AI -> IconSpec(
        "AI",
        "ask the bot",
        1.4f,
        listOf(
            part(
                "M16 9V5",
                fill = NONE, stroke = OUTLINE, width = 1.4f,
            ),
            part(
                "M14.2 4A1.8 1.8 0 1 0 17.8 4A1.8 1.8 0 1 0 14.2 4Z",
                fill = 0xFFe53935L, stroke = OUTLINE, width = 1.4f,
            ),
            part(
                "M5 14v6M27 14v6",
                fill = NONE, stroke = OUTLINE, width = 2.4f,
            ),
            part(
                "M10 9H22A3 3 0 0 1 25 12V22A3 3 0 0 1 22 25H10A3 3 0 0 1 7 22V12A3 3 0 0 1 10 9Z",
                fill = 0xFFcfd8dcL, stroke = OUTLINE, width = 1.4f,
            ),
            part(
                "M10.3 16A2.2 2.2 0 1 0 14.7 16A2.2 2.2 0 1 0 10.3 16Z",
                fill = 0xFF1f8a3cL, stroke = NONE, width = 1.4f,
            ),
            part(
                "M17.3 16A2.2 2.2 0 1 0 21.7 16A2.2 2.2 0 1 0 17.3 16Z",
                fill = 0xFF1f8a3cL, stroke = NONE, width = 1.4f,
            ),
            part(
                "M12.5 21h7",
                fill = NONE, stroke = OUTLINE, width = 1.4f,
            ),
        ),
    )
    ToolbarIconId.VIDEO -> IconSpec(
        "Video",
        "screen to screen",
        1.4f,
        listOf(
            part(
                "M2 7H15V17H2Z",
                fill = 0xFF008080L, stroke = OUTLINE, width = 1.4f,
            ),
            part(
                "M17 7H30V17H17Z",
                fill = 0xFF008080L, stroke = OUTLINE, width = 1.4f,
            ),
            part(
                "M8.5 17v4M23.5 17v4M5 22h7M20 22h7",
                fill = NONE, stroke = OUTLINE, width = 1.6f,
            ),
        ),
    )
    ToolbarIconId.COMPARE -> IconSpec(
        "Compare",
        "the diff",
        1.4f,
        listOf(
            part(
                "M7 5H25A2 2 0 0 1 27 7V25A2 2 0 0 1 25 27H7A2 2 0 0 1 5 25V7A2 2 0 0 1 7 5Z",
                fill = 0xFF0a120cL, stroke = OUTLINE, width = 1.4f,
            ),
            part(
                "M9 11h6M12 8v6",
                fill = NONE, stroke = 0xFF39ff6aL, width = 2f,
            ),
            part(
                "M9 16.5h14",
                fill = NONE, stroke = 0xFF3f8a52L, width = 1f,
            ),
            part(
                "M17 21.5h6",
                fill = NONE, stroke = 0xFFff5c5cL, width = 2f,
            ),
        ),
    )
    ToolbarIconId.CASES -> IconSpec(
        "Cases",
        "the regex",
        1.4f,
        listOf(
            part(
                "M7 6H25A3 3 0 0 1 28 9V23A3 3 0 0 1 25 26H7A3 3 0 0 1 4 23V9A3 3 0 0 1 7 6Z",
                fill = 0xFF0a120cL, stroke = OUTLINE, width = 1.4f,
            ),
            part(
                "M9 20A2 2 0 1 0 13 20A2 2 0 1 0 9 20Z",
                fill = 0xFF39ff6aL, stroke = NONE, width = 1.4f,
            ),
            part(
                "M20 10v8M16.5 12l7 4M23.5 12l-7 4",
                fill = NONE, stroke = 0xFF39ff6aL, width = 2f,
            ),
        ),
    )
    ToolbarIconId.OPEN -> IconSpec(
        "Open file",
        "load from disk",
        1.4f,
        listOf(
            part(
                "M5 5h18l4 4v18H5z",
                fill = 0xFF3a5a8cL, stroke = OUTLINE, width = 1.4f,
            ),
            part(
                "M10 5H21V13H10Z",
                fill = 0xFFcfd8dcL, stroke = OUTLINE, width = 1.4f,
            ),
            part(
                "M17 6.2H19.6V11.2H17Z",
                fill = 0xFF3a5a8cL, stroke = NONE, width = 1.4f,
            ),
            part(
                "M9 17H23V27H9Z",
                fill = 0xFFf4f4f4L, stroke = OUTLINE, width = 1.4f,
            ),
        ),
    )
    ToolbarIconId.LAYOUT -> IconSpec(
        "Layout",
        "a tiling window manager",
        1.4f,
        listOf(
            part(
                "M4 5H15V27H4Z",
                fill = 0xFF0a120cL, stroke = OUTLINE, width = 1.4f,
            ),
            part(
                "M17 5H28V15H17Z",
                fill = 0xFF0a120cL, stroke = OUTLINE, width = 1.4f,
            ),
            part(
                "M17 17H28V27H17Z",
                fill = 0xFF0a120cL, stroke = OUTLINE, width = 1.4f,
            ),
            part(
                "M6.5 8.5h4M19.5 8.5h4M19.5 20.5h4",
                fill = NONE, stroke = 0xFF39ff6aL, width = 1.6f,
            ),
        ),
    )
    ToolbarIconId.SETTINGS -> IconSpec(
        "Settings",
        "tune the hardware",
        1.4f,
        listOf(
            part(
                "M12 9V5M16 9V5M20 9V5M12 23v4M16 23v4M20 23v4M9 12H5M9 16H5M9 20H5M23 12h4M23 16h4M23 20h4",
                fill = NONE, stroke = OUTLINE, width = 1.4f,
            ),
            part(
                "M10.5 9H21.5A1.5 1.5 0 0 1 23 10.5V21.5A1.5 1.5 0 0 1 21.5 23H10.5A1.5 1.5 0 0 1 9 21.5V10.5A1.5 1.5 0 0 1 10.5 9Z",
                fill = 0xFF1d3b22L, stroke = OUTLINE, width = 1.4f,
            ),
            part(
                "M13 13H19V19H13Z",
                fill = 0xFF39ff6aL, stroke = NONE, width = 1.4f,
            ),
        ),
    )
}
