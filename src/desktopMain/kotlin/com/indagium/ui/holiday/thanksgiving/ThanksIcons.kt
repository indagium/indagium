// Generated from the Thanksgiving design canvas's icon kit (circles, ellipses, rectangles and group transforms baked into paths).
@file:Suppress("MagicNumber")

package com.indagium.ui.holiday.thanksgiving

import androidx.compose.ui.graphics.Color
import com.indagium.ui.holiday.*

/** Harvest, pie and the wishbone. */
internal object ThanksIcons : HolidayIconSet {
    override fun icons(outline: Color): Map<ToolbarIconId, HolidayIcon> = holidayIconMap(outline, ::thanksIcon)

    override fun findIcon(outline: Color): HolidayIcon = icons(outline).getValue(ToolbarIconId.CASES).copy(name = "Find")
}

// A table of vector art: each colour is one path, so the function is long by its data, not its logic.
@Suppress("LongMethod")
private fun thanksIcon(id: ToolbarIconId): IconSpec = when (id) {
    ToolbarIconId.NEW -> IconSpec(
        "New tab",
        "from a small acorn",
        1.4f,
        listOf(
            part(
                "M16 3.5v4",
                fill = NONE, stroke = 0xFF6b4220L, width = 2f,
            ),
            part(
                "M9 14c0 7 3 12.5 7 13.5 4-1 7-6.5 7-13.5z",
                fill = 0xFFd9953fL, stroke = OUTLINE, width = 1.4f,
            ),
            part(
                "M12.4 16c.2 3.6 1.4 6.6 3 8.4",
                fill = NONE, stroke = 0xFFf3c983L, width = 1.4f,
            ),
            part(
                "M6.5 14c0-4.6 4-7 9.5-7s9.5 2.4 9.5 7z",
                fill = 0xFF8a5a2bL, stroke = OUTLINE, width = 1.4f,
            ),
            part(
                "M10 10.6l2.4 3M14 8.6l2.6 4.8M18.4 8.6l2.4 4.8M22.2 10.4l1.6 3",
                fill = NONE, stroke = 0xFF5e3a1aL, width = 1f,
            ),
        ),
    )
    ToolbarIconId.FILTERS -> IconSpec(
        "Filters",
        "the horn of plenty",
        1.4f,
        listOf(
            part(
                "M5 25c-2-1.6-1.4-4.8.6-5.2 1.4-.3 2.3.6 2.4 1.6C11.6 15.6 17.6 11 24 9.6l3.6 9c-5.6.4-10.8 2.6-14.6 6.6C10.2 28 6.8 26.4 5 25z",
                fill = 0xFFc98a3fL, stroke = OUTLINE, width = 1.4f,
            ),
            part(
                "M12.2 16.6l3 6.6M16.6 13.2l3 7M21 11l2.6 7.2",
                fill = NONE, stroke = 0xFF8a5a2bL, width = 0.9f,
            ),
            part(
                "M23.37 14.9A2.4 4.8 -22 1 0 27.83 13.1A2.4 4.8 -22 1 0 23.37 14.9Z",
                fill = 0xFF6b4220L, stroke = NONE, width = 1.4f,
            ),
            part(
                "M21.8 8.6A2.8 2.8 0 1 0 27.4 8.6A2.8 2.8 0 1 0 21.8 8.6Z",
                fill = 0xFFc2321fL, stroke = OUTLINE, width = 1.4f,
            ),
            part(
                "M26.9 14.8A1.5 1.5 0 1 0 29.9 14.8A1.5 1.5 0 1 0 26.9 14.8Z",
                fill = 0xFF6a3a8cL, stroke = NONE, width = 1.4f,
            ),
            part(
                "M25.9 17.2A1.5 1.5 0 1 0 28.9 17.2A1.5 1.5 0 1 0 25.9 17.2Z",
                fill = 0xFF6a3a8cL, stroke = NONE, width = 1.4f,
            ),
            part(
                "M28.1 17.4A1.5 1.5 0 1 0 31.1 17.4A1.5 1.5 0 1 0 28.1 17.4Z",
                fill = 0xFF6a3a8cL, stroke = NONE, width = 1.4f,
            ),
            part(
                "M26.9 19.6A1.5 1.5 0 1 0 29.9 19.6A1.5 1.5 0 1 0 26.9 19.6Z",
                fill = 0xFF6a3a8cL, stroke = NONE, width = 1.4f,
            ),
            part(
                "M28.4 10.6c1.6-1.4 2.6-1.4 3.2-.4-1 1-2 1.2-3.2.4z",
                fill = 0xFF7a8a3aL, stroke = NONE, width = 1.4f,
            ),
        ),
    )
    ToolbarIconId.NOTES -> IconSpec(
        "Notes",
        "the family recipe",
        1.4f,
        listOf(
            part(
                "M6.1 7H25.9A1.6 1.6 0 0 1 27.5 8.6V23.4A1.6 1.6 0 0 1 25.9 25H6.1A1.6 1.6 0 0 1 4.5 23.4V8.6A1.6 1.6 0 0 1 6.1 7Z",
                fill = 0xFFfffaf0L, stroke = OUTLINE, width = 1.4f,
            ),
            part(
                "M4.5 11.5h23",
                fill = NONE, stroke = 0xFFc2321fL, width = 1.2f,
            ),
            part(
                "M9 15.5h14M9 19h10M9 22.2h12",
                fill = NONE, stroke = 0xFFa88f74L, width = 1.1f,
            ),
            part(
                "M24.5 19.9L25.26 21.56L26.66 20.76L26.3 22.56L28.1 22.42L27.09 23.79L27.88 24.8L25.72 24.58L25.29 25.88L24.75 25.59L24.75 27.1" +
                    "L24.25 27.1L24.25 25.59L23.71 25.88L23.28 24.58L21.12 24.8L21.91 23.79L20.9 22.42L22.7 22.56L22.34 20.76L23.74 21.56Z",
                fill = 0xFFe8a33aL, stroke = OUTLINE, width = 1.19f,
            ),
            part(
                "M24.5 26.74V21.34",
                fill = NONE, stroke = 0x47000000L, width = 1.19f,
            ),
        ),
    )
    ToolbarIconId.AI -> IconSpec(
        "AI",
        "make a wish",
        1.4f,
        listOf(
            part(
                "M16 27V17C14 13 11 9.2 7.6 5.4M16 17c2-3.8 5-7.6 8.4-11.6",
                fill = NONE, stroke = OUTLINE, width = 5.4f,
            ),
            part(
                "M16 27V17C14 13 11 9.2 7.6 5.4M16 17c2-3.8 5-7.6 8.4-11.6",
                fill = NONE, stroke = 0xFFf3e2c2L, width = 3f,
            ),
            part(
                "M5 5.2A2.4 2.4 0 1 0 9.8 5.2A2.4 2.4 0 1 0 5 5.2Z",
                fill = 0xFFf3e2c2L, stroke = OUTLINE, width = 1.4f,
            ),
            part(
                "M22.2 5.2A2.4 2.4 0 1 0 27 5.2A2.4 2.4 0 1 0 22.2 5.2Z",
                fill = 0xFFf3e2c2L, stroke = OUTLINE, width = 1.4f,
            ),
            part(
                "M26 20.5v4.6M23.7 22.8h4.6",
                fill = NONE, stroke = 0xFFe8a33aL, width = 1.6f,
            ),
            part(
                "M6.4 18.6v3M4.9 20.1h3",
                fill = NONE, stroke = 0xFFe8a33aL, width = 1.2f,
            ),
        ),
    )
    ToolbarIconId.VIDEO -> IconSpec(
        "Video",
        "geese flying past, frame by frame",
        1.4f,
        listOf(
            part(
                "M3 11Q6 7 9 11Q12 7 15 11",
                fill = NONE, stroke = OUTLINE, width = 2f,
            ),
            part(
                "M11.9 16Q14.45 12.6 17 16Q19.55 12.6 22.1 16",
                fill = NONE, stroke = OUTLINE, width = 1.7f,
            ),
            part(
                "M20.8 21Q22.9 18.2 25 21Q27.1 18.2 29.2 21",
                fill = NONE, stroke = OUTLINE, width = 1.4f,
            ),
            part(
                "M4.8 22Q6.9 19.2 9 22Q11.1 19.2 13.2 22",
                fill = NONE, stroke = OUTLINE, width = 1.4f,
            ),
            part(
                "M22 8.5A3 3 0 1 0 28 8.5A3 3 0 1 0 22 8.5Z",
                fill = 0xFFf5b041L, stroke = NONE, width = 1f,
            ),
        ),
    )
    ToolbarIconId.COMPARE -> IconSpec(
        "Compare",
        "maple and oak",
        1.4f,
        listOf(
            part(
                "M10 7.2L11.64 10.79L14.68 9.07L13.9 12.97L17.8 12.66L15.62 15.62L17.33 17.81L12.65 17.34L11.72 20.15L10.55 19.52L10.55 22.8" +
                    "L9.45 22.8L9.45 19.52L8.28 20.15L7.35 17.34L2.67 17.81L4.38 15.62L2.2 12.66L6.1 12.97L5.32 9.07L8.36 10.79Z",
                fill = 0xFFb5451bL, stroke = OUTLINE, width = 1.17f,
            ),
            part(
                "M10 22.02V10.32",
                fill = NONE, stroke = 0x47000000L, width = 1.17f,
            ),
            part(
                "M22 9.6C24.22 9.6 23.78 11.82 25.11 12.26C27.18 12.41 25.85 14.48 26.74 15.22C28.66 16.11 26.74 18.18 25.85 19.07" +
                    "C27.18 20.7 24.52 21.44 23.48 21.29L22.52 24.4L21.48 24.4L20.52 21.29C19.48 21.44 16.82 20.7 18.15 19.07" +
                    "C17.26 18.18 15.34 16.11 17.26 15.22C18.15 14.48 16.82 12.41 18.89 12.26C20.22 11.82 19.78 9.6 22 9.6Z",
                fill = 0xFFe8a33aL, stroke = OUTLINE, width = 1.18f,
            ),
            part(
                "M22 23.66V12.56",
                fill = NONE, stroke = 0x47000000L, width = 1.18f,
            ),
        ),
    )
    ToolbarIconId.CASES -> IconSpec(
        "Cases",
        "look closer at the leaves",
        1.4f,
        listOf(
            part(
                "M19 19l8.5 8.5",
                fill = NONE, stroke = OUTLINE, width = 3.4f,
            ),
            part(
                "M4.5 13A8.5 8.5 0 1 0 21.5 13A8.5 8.5 0 1 0 4.5 13Z",
                fill = 0xFFfff6e6L, stroke = OUTLINE, width = 1.4f,
            ),
            part(
                "M13 7.8L14.09 10.19L16.12 9.05L15.6 11.65L18.2 11.44L16.74 13.42L17.89 14.87L14.77 14.56L14.14 16.43L13.36 16.02L13.36 18.2" +
                    "L12.64 18.2L12.64 16.02L11.86 16.43L11.23 14.56L8.11 14.87L9.26 13.42L7.8 11.44L10.4 11.65L9.88 9.05L11.91 10.19Z",
                fill = 0xFFb5451bL, stroke = NONE, width = 0.73f,
            ),
            part(
                "M13 17.68V9.88",
                fill = NONE, stroke = 0x47000000L, width = 1.2f,
            ),
        ),
    )
    ToolbarIconId.OPEN -> IconSpec(
        "Open file",
        "open the harvest basket",
        1.4f,
        listOf(
            part(
                "M8.5 14.5a7.5 7.5 0 0 1 15 0",
                fill = NONE, stroke = 0xFF8a5a2bL, width = 2.2f,
            ),
            part(
                "M8.8 13.6A3.2 3.2 0 1 0 15.2 13.6A3.2 3.2 0 1 0 8.8 13.6Z",
                fill = 0xFFc2321fL, stroke = OUTLINE, width = 1.4f,
            ),
            part(
                "M14.2 12.4A3.2 3.2 0 1 0 20.6 12.4A3.2 3.2 0 1 0 14.2 12.4Z",
                fill = 0xFFe8a33aL, stroke = OUTLINE, width = 1.4f,
            ),
            part(
                "M18.6 14A2.8 2.8 0 1 0 24.2 14A2.8 2.8 0 1 0 18.6 14Z",
                fill = 0xFF7a8a3aL, stroke = OUTLINE, width = 1.4f,
            ),
            part(
                "M4.5 15h23l-2.6 12h-17.8z",
                fill = 0xFFc98a3fL, stroke = OUTLINE, width = 1.4f,
            ),
            part(
                "M9 15l1.4 12M13.6 15l.6 12M18.4 15l-.6 12M23 15l-1.4 12M5.4 19h21.2M6.3 23h19.4",
                fill = NONE, stroke = 0xFF8a5a2bL, width = 0.9f,
            ),
        ),
    )
    ToolbarIconId.LAYOUT -> IconSpec(
        "Layout",
        "patchwork, square by square",
        1.4f,
        listOf(
            part(
                "M4 4H12V12H4Z",
                fill = 0xFFb5451bL, stroke = NONE, width = 1.4f,
            ),
            part(
                "M12 4H20V12H12Z",
                fill = 0xFFf3d9a6L, stroke = NONE, width = 1.4f,
            ),
            part(
                "M20 4H28V12H20Z",
                fill = 0xFFe8a33aL, stroke = NONE, width = 1.4f,
            ),
            part(
                "M4 12H12V20H4Z",
                fill = 0xFF7a8a3aL, stroke = NONE, width = 1.4f,
            ),
            part(
                "M12 12H20V20H12Z",
                fill = 0xFFc2321fL, stroke = NONE, width = 1.4f,
            ),
            part(
                "M20 12H28V20H20Z",
                fill = 0xFFf3d9a6L, stroke = NONE, width = 1.4f,
            ),
            part(
                "M4 20H12V28H4Z",
                fill = 0xFFe8a33aL, stroke = NONE, width = 1.4f,
            ),
            part(
                "M12 20H20V28H12Z",
                fill = 0xFFb5451bL, stroke = NONE, width = 1.4f,
            ),
            part(
                "M20 20H28V28H20Z",
                fill = 0xFF7a8a3aL, stroke = NONE, width = 1.4f,
            ),
            part(
                "M5.4 4H26.6A1.4 1.4 0 0 1 28 5.4V26.6A1.4 1.4 0 0 1 26.6 28H5.4A1.4 1.4 0 0 1 4 26.6V5.4A1.4 1.4 0 0 1 5.4 4Z",
                fill = NONE, stroke = OUTLINE, width = 1.4f,
            ),
            part(
                "M12 4v24M20 4v24M4 12h24M4 20h24",
                fill = NONE, stroke = OUTLINE, width = 1f,
            ),
            part(
                "M6 8h4M14 16h4M22 24h4M8 22v4M24 6v4",
                fill = NONE, stroke = 0xB2ffffffL, width = 0.8f,
            ),
        ),
    )
    ToolbarIconId.SETTINGS -> IconSpec(
        "Settings",
        "crimped like a gear",
        1.4f,
        listOf(
            part(
                "M26.4 16Q30 19.2 25.4 20.5Q27.2 25 22.5 24.1Q22.2 29 18.3 26.2Q16 30.4 13.7 26.2Q9.8 29 9.5 24.1Q4.8 25 6.6 20.5Q2 19.2 5.6 16" +
                    "Q2 12.8 6.6 11.5Q4.8 7 9.5 7.9Q9.8 3 13.7 5.8Q16 1.6 18.3 5.8Q22.2 3 22.5 7.9Q27.2 7 25.4 11.5Q30 12.8 26.4 16Z",
                fill = 0xFFd9953fL, stroke = OUTLINE, width = 1.1f,
            ),
            part(
                "M7.4 16A8.6 8.6 0 1 0 24.6 16A8.6 8.6 0 1 0 7.4 16Z",
                fill = 0xFFc2621fL, stroke = NONE, width = 1.4f,
            ),
            part(
                "M10.2 12.4l11.6 7.2M12.2 9l9.6 6M10.2 16l7.6 5.4M21.8 12.4l-11.6 7.2M19.8 9l-9.6 6M21.8 16l-7.6 5.4",
                fill = NONE, stroke = 0xFFf0c27aL, width = 1.6f,
            ),
        ),
    )
}
