// Generated from the design canvas's icon kit (circles, ellipses, rectangles and group transforms baked into paths).
@file:Suppress("MagicNumber")

package com.indagium.ui.holiday.retro

import androidx.compose.ui.graphics.Color
import com.indagium.ui.holiday.*

/** The synthwave kit: tapes, the prism and the grid sun. */
internal object RetroIcons : HolidayIconSet {
    override fun icons(outline: Color): Map<ToolbarIconId, HolidayIcon> = holidayIconMap(outline, ::retroIconsSpec)

    override fun findIcon(outline: Color): HolidayIcon = icons(outline).getValue(ToolbarIconId.CASES).copy(name = "Find")
}

// A table of vector art: each colour is one path, so the function is long by its data, not its logic.
@Suppress("LongMethod")
private fun retroIconsSpec(id: ToolbarIconId): IconSpec = when (id) {
    ToolbarIconId.NEW -> IconSpec(
        "New tab",
        "a fresh tape",
        1.4f,
        listOf(
            part(
                "M5 8H27A2 2 0 0 1 29 10V23A2 2 0 0 1 27 25H5A2 2 0 0 1 3 23V10A2 2 0 0 1 5 8Z",
                fill = 0xFF2a2a3aL, stroke = OUTLINE, width = 1.4f,
            ),
            part(
                "M6 10H26V15H6Z",
                fill = 0xFFff4fd8L, stroke = NONE, width = 1.4f,
            ),
            part(
                "M6 12.5H26V15H6Z",
                fill = 0xFF00d0e8L, stroke = NONE, width = 1.4f,
            ),
            part(
                "M8.4 19.5A2.6 2.6 0 1 0 13.6 19.5A2.6 2.6 0 1 0 8.4 19.5Z",
                fill = 0xFFffffffL, stroke = OUTLINE, width = 1.4f,
            ),
            part(
                "M18.4 19.5A2.6 2.6 0 1 0 23.6 19.5A2.6 2.6 0 1 0 18.4 19.5Z",
                fill = 0xFFffffffL, stroke = OUTLINE, width = 1.4f,
            ),
            part(
                "M8 25l2-3h12l2 3",
                fill = NONE, stroke = OUTLINE, width = 1.4f,
            ),
        ),
    )
    ToolbarIconId.FILTERS -> IconSpec(
        "Filters",
        "split the light, keep one colour",
        1.4f,
        listOf(
            part(
                "M2 14l10 4",
                fill = NONE, stroke = OUTLINE, width = 1.8f,
            ),
            part(
                "M16 5L27 25H5z",
                fill = 0x8Cc9f6ffL, stroke = OUTLINE, width = 1.4f,
            ),
            part(
                "M21 16l9-3",
                fill = NONE, stroke = 0xFFff4fd8L, width = 1.8f,
            ),
            part(
                "M21.5 18.5h9",
                fill = NONE, stroke = 0xFFffe14dL, width = 1.8f,
            ),
            part(
                "M22 21l9 3",
                fill = NONE, stroke = 0xFF00d0e8L, width = 1.8f,
            ),
        ),
    )
    ToolbarIconId.NOTES -> IconSpec(
        "Notes",
        "leave a message",
        1.4f,
        listOf(
            part(
                "M8 9H24A3 3 0 0 1 27 12V21A3 3 0 0 1 24 24H8A3 3 0 0 1 5 21V12A3 3 0 0 1 8 9Z",
                fill = 0xFF2a2a3aL, stroke = OUTLINE, width = 1.4f,
            ),
            part(
                "M8 12H24V18H8Z",
                fill = 0xFF7dff9aL, stroke = NONE, width = 1.4f,
            ),
            part(
                "M10 15h8",
                fill = NONE, stroke = 0xFF1a4a2aL, width = 1.4f,
            ),
            part(
                "M21.8 21A1.2 1.2 0 1 0 24.2 21A1.2 1.2 0 1 0 21.8 21Z",
                fill = 0xFFff4fd8L, stroke = NONE, width = 1.4f,
            ),
        ),
    )
    ToolbarIconId.AI -> IconSpec(
        "AI",
        "player two, press start",
        1.4f,
        listOf(
            part(
                "M16 7v12",
                fill = NONE, stroke = OUTLINE, width = 2.6f,
            ),
            part(
                "M7 18H25A2 2 0 0 1 27 20V25A2 2 0 0 1 25 27H7A2 2 0 0 1 5 25V20A2 2 0 0 1 7 18Z",
                fill = 0xFF2a2a3aL, stroke = OUTLINE, width = 1.4f,
            ),
            part(
                "M12 7A4 4 0 1 0 20 7A4 4 0 1 0 12 7Z",
                fill = 0xFFff2f6dL, stroke = OUTLINE, width = 1.4f,
            ),
            part(
                "M21 22.5A2 2 0 1 0 25 22.5A2 2 0 1 0 21 22.5Z",
                fill = 0xFFffe14dL, stroke = NONE, width = 1.4f,
            ),
        ),
    )
    ToolbarIconId.VIDEO -> IconSpec(
        "Video",
        "press play",
        1.4f,
        listOf(
            part(
                "M4.5 9H27.5A1.5 1.5 0 0 1 29 10.5V22.5A1.5 1.5 0 0 1 27.5 24H4.5A1.5 1.5 0 0 1 3 22.5V10.5A1.5 1.5 0 0 1 4.5 9Z",
                fill = 0xFF1d1d26L, stroke = OUTLINE, width = 1.4f,
            ),
            part(
                "M10 12H22A1 1 0 0 1 23 13V17A1 1 0 0 1 22 18H10A1 1 0 0 1 9 17V13A1 1 0 0 1 10 12Z",
                fill = 0xFF6b6b80L, stroke = NONE, width = 1.4f,
            ),
            part(
                "M10.4 15A1.6 1.6 0 1 0 13.6 15A1.6 1.6 0 1 0 10.4 15Z",
                fill = 0xFFffffffL, stroke = NONE, width = 1.4f,
            ),
            part(
                "M18.4 15A1.6 1.6 0 1 0 21.6 15A1.6 1.6 0 1 0 18.4 15Z",
                fill = 0xFFffffffL, stroke = NONE, width = 1.4f,
            ),
            part(
                "M5 21h22",
                fill = NONE, stroke = 0xFF00d0e8L, width = 1.6f,
            ),
        ),
    )
    ToolbarIconId.COMPARE -> IconSpec(
        "Compare",
        "two palms",
        1.4f,
        listOf(
            part(
                "M10 29c0-8 1-14 2-19M22 29c0-8-1-14-2-19",
                fill = NONE, stroke = 0xFF8a4a2aL, width = 2.2f,
            ),
            part(
                "M12 10c-3-3-7-3-9-1M12 10c1-4 5-5 8-4M12 10c-3 1-6 4-7 7M20 10c3-3 7-3 9-1M20 10c-1-4-5-5-8-4M20 10c3 1 6 4 7 7",
                fill = NONE, stroke = 0xFFff4fd8L, width = 2f,
            ),
        ),
    )
    ToolbarIconId.CASES -> IconSpec(
        "Cases",
        "look sharp",
        1.4f,
        listOf(
            part(
                "M3 12h26",
                fill = NONE, stroke = OUTLINE, width = 1.8f,
            ),
            part(
                "M4 12h10l-1 6a3 3 0 0 1-3 2H7a3 3 0 0 1-3-2z",
                fill = 0xFFff4fd8L, stroke = OUTLINE, width = 1.4f,
            ),
            part(
                "M18 12h10l-.6 6a3 3 0 0 1-3 2h-3a3 3 0 0 1-3-2z",
                fill = 0xFF00d0e8L, stroke = OUTLINE, width = 1.4f,
            ),
        ),
    )
    ToolbarIconId.OPEN -> IconSpec(
        "Open file",
        "insert the tape",
        1.4f,
        listOf(
            part(
                "M4.5 9H27.5A1.5 1.5 0 0 1 29 10.5V22.5A1.5 1.5 0 0 1 27.5 24H4.5A1.5 1.5 0 0 1 3 22.5V10.5A1.5 1.5 0 0 1 4.5 9Z",
                fill = 0xFF1d1d26L, stroke = OUTLINE, width = 1.4f,
            ),
            part(
                "M10 12H22A1 1 0 0 1 23 13V17A1 1 0 0 1 22 18H10A1 1 0 0 1 9 17V13A1 1 0 0 1 10 12Z",
                fill = 0xFF6b6b80L, stroke = NONE, width = 1.4f,
            ),
            part(
                "M10.4 15A1.6 1.6 0 1 0 13.6 15A1.6 1.6 0 1 0 10.4 15Z",
                fill = 0xFFffffffL, stroke = NONE, width = 1.4f,
            ),
            part(
                "M18.4 15A1.6 1.6 0 1 0 21.6 15A1.6 1.6 0 1 0 18.4 15Z",
                fill = 0xFFffffffL, stroke = NONE, width = 1.4f,
            ),
            part(
                "M5 21h22",
                fill = NONE, stroke = 0xFF00d0e8L, width = 1.6f,
            ),
        ),
    )
    ToolbarIconId.LAYOUT -> IconSpec(
        "Layout",
        "the grid",
        1.4f,
        listOf(
            part(
                "M8 16a8 8 0 0 1 16 0z",
                fill = 0xFFff8a3dL, stroke = OUTLINE, width = 1.4f,
            ),
            part(
                "M3 16h26M5 20h22M8 25h16M16 16v12M11 16l-5 12M21 16l5 12",
                fill = NONE, stroke = 0xFFff4fd8L, width = 1.2f,
            ),
        ),
    )
    ToolbarIconId.SETTINGS -> IconSpec(
        "Settings",
        "turn the record",
        1.4f,
        listOf(
            part(
                "M4 16A12 12 0 1 0 28 16A12 12 0 1 0 4 16Z",
                fill = 0xFF1d1d26L, stroke = OUTLINE, width = 1.4f,
            ),
            part(
                "M7.5 16A8.5 8.5 0 1 0 24.5 16A8.5 8.5 0 1 0 7.5 16Z",
                fill = NONE, stroke = 0xFF4a4a5cL, width = 1f,
            ),
            part(
                "M12 16A4 4 0 1 0 20 16A4 4 0 1 0 12 16Z",
                fill = 0xFFff4fd8L, stroke = NONE, width = 1.4f,
            ),
            part(
                "M15 16A1 1 0 1 0 17 16A1 1 0 1 0 15 16Z",
                fill = 0xFFffffffL, stroke = NONE, width = 1.4f,
            ),
        ),
    )
}
