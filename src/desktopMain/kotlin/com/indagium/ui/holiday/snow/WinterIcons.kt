// Generated from the design canvas's icon kit (circles, ellipses, rectangles and group transforms baked into paths).
@file:Suppress("MagicNumber")

package com.indagium.ui.holiday.snow

import androidx.compose.ui.graphics.Color
import com.indagium.ui.holiday.*

/** The winter kit: presents, the tree and the snowflake (First snow, Cozy and Classic Christmas). */
internal object WinterIcons : HolidayIconSet {
    override fun icons(outline: Color): Map<ToolbarIconId, HolidayIcon> = holidayIconMap(outline, ::winterIconsSpec)

    override fun findIcon(outline: Color): HolidayIcon = icons(outline).getValue(ToolbarIconId.CASES).copy(name = "Find")
}

// A table of vector art: each colour is one path, so the function is long by its data, not its logic.
@Suppress("LongMethod")
private fun winterIconsSpec(id: ToolbarIconId): IconSpec = when (id) {
    ToolbarIconId.NEW -> IconSpec(
        "New tab",
        "unwrap a new one",
        1.4f,
        listOf(
            part(
                "M7 13H25A1.5 1.5 0 0 1 26.5 14.5V26.5A1.5 1.5 0 0 1 25 28H7A1.5 1.5 0 0 1 5.5 26.5V14.5A1.5 1.5 0 0 1 7 13Z",
                fill = 0xFFd94040L, stroke = OUTLINE, width = 1.4f,
            ),
            part(
                "M5 9.5H27A1 1 0 0 1 28 10.5V13.5A1 1 0 0 1 27 14.5H5A1 1 0 0 1 4 13.5V10.5A1 1 0 0 1 5 9.5Z",
                fill = 0xFFe85a5aL, stroke = OUTLINE, width = 1.4f,
            ),
            part(
                "M16 9.5V28",
                fill = NONE, stroke = 0xFFffd166L, width = 3f,
            ),
            part(
                "M16 9.5c-3-5-8.5-4-6.2-1 1 1.2 4 1 6.2 1zm0 0c3-5 8.5-4 6.2-1-1 1.2-4 1-6.2 1z",
                fill = 0xFFffd166L, stroke = OUTLINE, width = 1f,
            ),
        ),
    )
    ToolbarIconId.FILTERS -> IconSpec(
        "Filters",
        "a funnel, upside down",
        1.4f,
        listOf(
            part(
                "M16 3l7 9h-3.5l5 7h-4l5 7h-19l5-7h-4l5-7H9z",
                fill = 0xFF2f8f4eL, stroke = OUTLINE, width = 1.4f,
            ),
            part(
                "M14 26H18V30H14Z",
                fill = 0xFF7a4a2aL, stroke = OUTLINE, width = 1f,
            ),
            part(
                "M12.1 14A1.4 1.4 0 1 0 14.9 14A1.4 1.4 0 1 0 12.1 14Z",
                fill = 0xFFffd166L, stroke = NONE, width = 1.4f,
            ),
            part(
                "M17.6 19A1.4 1.4 0 1 0 20.4 19A1.4 1.4 0 1 0 17.6 19Z",
                fill = 0xFFe85a5aL, stroke = NONE, width = 1.4f,
            ),
            part(
                "M10.6 22.5A1.4 1.4 0 1 0 13.4 22.5A1.4 1.4 0 1 0 10.6 22.5Z",
                fill = 0xFF8fd3ffL, stroke = NONE, width = 1.4f,
            ),
            part(
                "M19.1 23A1.4 1.4 0 1 0 21.9 23A1.4 1.4 0 1 0 19.1 23Z",
                fill = 0xFFffd166L, stroke = NONE, width = 1.4f,
            ),
        ),
    )
    ToolbarIconId.NOTES -> IconSpec(
        "Notes",
        "the list",
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
        "follow the star",
        1.4f,
        listOf(
            part(
                "M15 5l3.4 7.6 8.1.8-6.1 5.4 1.8 8L15 22.6l-7.2 4.2 1.8-8-6.1-5.4 8.1-.8z",
                fill = 0xFFffd166L, stroke = OUTLINE, width = 1.4f,
            ),
            part(
                "M26.5 3.5v5M24 6h5",
                fill = NONE, stroke = 0xFFf2b33dL, width = 1.8f,
            ),
        ),
    )
    ToolbarIconId.VIDEO -> IconSpec(
        "Video",
        "shake and watch",
        1.4f,
        listOf(
            part(
                "M6 14A10 10 0 1 0 26 14A10 10 0 1 0 6 14Z",
                fill = 0xFFd6eeffL, stroke = OUTLINE, width = 1.4f,
            ),
            part(
                "M16 8l3.6 6h-2l3 5h-9.2l3-5h-2z",
                fill = 0xFF2f8f4eL, stroke = OUTLINE, width = 1f,
            ),
            part(
                "M9.6 10A0.9 0.9 0 1 0 11.4 10A0.9 0.9 0 1 0 9.6 10Z",
                fill = 0xFFffffffL, stroke = NONE, width = 1.4f,
            ),
            part(
                "M20.6 9A0.9 0.9 0 1 0 22.4 9A0.9 0.9 0 1 0 20.6 9Z",
                fill = 0xFFffffffL, stroke = NONE, width = 1.4f,
            ),
            part(
                "M11.2 17A0.8 0.8 0 1 0 12.8 17A0.8 0.8 0 1 0 11.2 17Z",
                fill = 0xFFffffffL, stroke = NONE, width = 1.4f,
            ),
            part(
                "M8 24h16l-1.5 5h-13z",
                fill = 0xFF7a4a2aL, stroke = OUTLINE, width = 1.4f,
            ),
        ),
    )
    ToolbarIconId.COMPARE -> IconSpec(
        "Compare",
        "a pair",
        1.4f,
        listOf(
            part(
                "M5 25V15.5c0-4 2-7 5-7s5 3 5 7V25z",
                fill = 0xFFd94040L, stroke = OUTLINE, width = 1.4f,
            ),
            part(
                "M5 18.5c-2.5-1.5-4 .5-3 2.5L5 23.5",
                fill = 0xFFd94040L, stroke = OUTLINE, width = 1.4f,
            ),
            part(
                "M5.5 24.5H14.5A1 1 0 0 1 15.5 25.5V27.5A1 1 0 0 1 14.5 28.5H5.5A1 1 0 0 1 4.5 27.5V25.5A1 1 0 0 1 5.5 24.5Z",
                fill = 0xFFffffffL, stroke = OUTLINE, width = 1.4f,
            ),
            part(
                "M17 25V15.5c0-4 2-7 5-7s5 3 5 7V25z",
                fill = 0xFF2f8f4eL, stroke = OUTLINE, width = 1.4f,
            ),
            part(
                "M27 18.5c2.5-1.5 4 .5 3 2.5L27 23.5",
                fill = 0xFF2f8f4eL, stroke = OUTLINE, width = 1.4f,
            ),
            part(
                "M17.5 24.5H26.5A1 1 0 0 1 27.5 25.5V27.5A1 1 0 0 1 26.5 28.5H17.5A1 1 0 0 1 16.5 27.5V25.5A1 1 0 0 1 17.5 24.5Z",
                fill = 0xFFffffffL, stroke = OUTLINE, width = 1.4f,
            ),
        ),
    )
    ToolbarIconId.CASES -> IconSpec(
        "Cases",
        "look closer",
        1.4f,
        listOf(
            part(
                "M4.5 13A8.5 8.5 0 1 0 21.5 13A8.5 8.5 0 1 0 4.5 13Z",
                fill = 0xFFe8f4ffL, stroke = OUTLINE, width = 1.4f,
            ),
            part(
                "M19.5 19.5l8 8",
                fill = NONE, stroke = 0xFFffffffL, width = 4.6f,
            ),
            part(
                "M19.5 19.5l8 8",
                fill = NONE, stroke = 0xFFd94040L, width = 4.6f,
            ),
        ),
    )
    ToolbarIconId.OPEN -> IconSpec(
        "Open file",
        "reach inside",
        1.4f,
        listOf(
            part(
                "M10 7h10v9.5l5 5c2 2 1 6-2 7-2 .6-3.5 0-5-1.5L11 20.5c-1-1-1-2-1-3z",
                fill = 0xFFd94040L, stroke = OUTLINE, width = 1.4f,
            ),
            part(
                "M10 4H20A1 1 0 0 1 21 5V8A1 1 0 0 1 20 9H10A1 1 0 0 1 9 8V5A1 1 0 0 1 10 4Z",
                fill = 0xFFffffffL, stroke = OUTLINE, width = 1.4f,
            ),
            part(
                "M18 17.5l3 3",
                fill = NONE, stroke = 0xFFffffffL, width = 1.6f,
            ),
        ),
    )
    ToolbarIconId.LAYOUT -> IconSpec(
        "Layout",
        "rooms and windows",
        1.4f,
        listOf(
            part(
                "M6 14l10-8 10 8v14H6z",
                fill = 0xFFb5713aL, stroke = OUTLINE, width = 1.4f,
            ),
            part(
                "M4 15.5L16 5.5l12 10",
                fill = NONE, stroke = 0xFFfffaf0L, width = 2.4f,
            ),
            part(
                "M16 20H16A2.5 2.5 0 0 1 18.5 22.5V25.5A2.5 2.5 0 0 1 16 28H16A2.5 2.5 0 0 1 13.5 25.5V22.5A2.5 2.5 0 0 1 16 20Z",
                fill = 0xFF7a3f1dL, stroke = OUTLINE, width = 1f,
            ),
            part(
                "M8.5 15.5H12.5V19.5H8.5Z",
                fill = 0xFFffd166L, stroke = OUTLINE, width = 1f,
            ),
            part(
                "M19.5 15.5H23.5V19.5H19.5Z",
                fill = 0xFFffd166L, stroke = OUTLINE, width = 1f,
            ),
        ),
    )
    ToolbarIconId.SETTINGS -> IconSpec(
        "Settings",
        "six arms, like a gear",
        1.4f,
        listOf(
            part(
                "M16 3v26M4.7 9.5l22.6 13M4.7 22.5l22.6-13",
                fill = NONE, stroke = OUTLINE, width = 1.4f,
            ),
            part(
                "M13 5.5l3 3 3-3M13 26.5l3-3 3 3M5.2 13.4l4.1-.6-1.6-3.8M26.8 18.6l-4.1.6 1.6 3.8M5.2 18.6l4.1.6-1.6 3.8M26.8 13.4l-4.1-.6 1.6-3.8",
                fill = NONE, stroke = OUTLINE, width = 1.4f,
            ),
            part(
                "M13.4 16A2.6 2.6 0 1 0 18.6 16A2.6 2.6 0 1 0 13.4 16Z",
                fill = 0xFFffffffL, stroke = OUTLINE, width = 1.4f,
            ),
        ),
    )
}
