// Generated from the Dia de Muertos design canvas's icon kit (circles, rectangles and group transforms baked into paths).
@file:Suppress("MagicNumber")

package com.indagium.ui.holiday.muertos

import androidx.compose.ui.graphics.Color
import com.indagium.ui.holiday.*

/** Marigolds, calaveras and the ofrenda. */
internal object MuertosIcons : HolidayIconSet {
    override fun icons(outline: Color): Map<ToolbarIconId, HolidayIcon> = holidayIconMap(outline, ::muertosIcon)

    override fun findIcon(outline: Color): HolidayIcon = icons(outline).getValue(ToolbarIconId.CASES).copy(name = "Find")
}

// A table of vector art: each colour is one path, so the function is long by its data, not its logic.
@Suppress("LongMethod")
private fun muertosIcon(id: ToolbarIconId): IconSpec = when (id) {
    ToolbarIconId.NEW -> IconSpec(
        "New tab",
        "a fresh sheet of papel picado",
        1.4f,
        listOf(
            part(
                "M2.5 5H29.5",
                fill = NONE, stroke = OUTLINE, width = 1.4f,
            ),
            part(
                "M6 5H26V22q-2.5 3.6-5 0q-2.5 3.6-5 0q-2.5 3.6-5 0q-2.5 3.6-5 0Z",
                fill = 0xFFe6007eL, stroke = OUTLINE, width = 1.4f,
            ),
            part(
                "M16 9v9.4M11.3 13.7h9.4",
                fill = NONE, stroke = 0xFFffffffL, width = 2.6f,
            ),
        ),
    )
    ToolbarIconId.FILTERS -> IconSpec(
        "Filters",
        "the cempasúchil",
        1.4f,
        listOf(
            part(
                "M26.1 16Q31.1 20 24.7 21Q27 27 21 24.7Q20 31.1 16 26.1Q12 31.1 11 24.7Q5 27 7.3 21Q0.9 20 5.9 16Q0.9 12 7.3 11Q5 5 11 7.3" +
                    "Q12 0.9 16 5.9Q20 0.9 21 7.3Q27 5 24.7 11Q31.1 12 26.1 16Z",
                fill = 0xFFe8650cL, stroke = OUTLINE, width = 1.1f,
            ),
            part(
                "M23.2 17.5Q25.9 21.6 21 21.4Q20.7 26.4 16.8 23.3Q13.7 27.2 12.4 22.4Q7.6 23.7 9.3 19.1Q4.7 17.3 8.8 14.5Q6.1 10.4 11 10.6" +
                    "Q11.3 5.6 15.2 8.7Q18.3 4.8 19.6 9.6Q24.4 8.3 22.7 12.9Q27.3 14.7 23.2 17.5Z",
                fill = 0xFFff9a1fL, stroke = NONE, width = 1.4f,
            ),
            part(
                "M20.6 16.5Q22.3 19.4 18.9 19.6Q18.1 22.9 15.5 20.6Q12.6 22.3 12.4 18.9Q9.1 18.1 11.4 15.5Q9.7 12.6 13.1 12.4Q13.9 9.1 16.5 11.4" +
                    "Q19.4 9.7 19.6 13.1Q22.9 13.9 20.6 16.5Z",
                fill = 0xFFffc02eL, stroke = NONE, width = 1.4f,
            ),
            part(
                "M14.3 16A1.7 1.7 0 1 0 17.7 16A1.7 1.7 0 1 0 14.3 16Z",
                fill = 0xFFc9560cL, stroke = NONE, width = 1.4f,
            ),
        ),
    )
    ToolbarIconId.NOTES -> IconSpec(
        "Notes",
        "kept on the ofrenda",
        1.4f,
        listOf(
            part(
                "M6.6 3.5H23.4A1.6 1.6 0 0 1 25 5.1V26.9A1.6 1.6 0 0 1 23.4 28.5H6.6A1.6 1.6 0 0 1 5 26.9V5.1A1.6 1.6 0 0 1 6.6 3.5Z",
                fill = 0xFFf2b544L, stroke = OUTLINE, width = 1.4f,
            ),
            part(
                "M8.5 7H21.5V25H8.5Z",
                fill = 0xFFf8e7c6L, stroke = NONE, width = 1.4f,
            ),
            part(
                "M11.7 13A3.3 3.3 0 1 0 18.3 13A3.3 3.3 0 1 0 11.7 13Z",
                fill = 0xFF7a5233L, stroke = NONE, width = 1.4f,
            ),
            part(
                "M9.6 25c0-4.8 2.4-7.4 5.4-7.4s5.4 2.6 5.4 7.4z",
                fill = 0xFF7a5233L, stroke = NONE, width = 1.4f,
            ),
            part(
                "M28.5 24Q30.5 26.4 27.4 26.9Q27.5 30 24.8 28.4Q22.8 30.8 21.8 27.9Q18.7 28.5 19.8 25.5Q17.1 24 19.8 22.5Q18.7 19.5 21.8 20.1" +
                    "Q22.8 17.2 24.8 19.6Q27.5 18 27.4 21.1Q30.5 21.6 28.5 24Z",
                fill = 0xFFe8650cL, stroke = OUTLINE, width = 1f,
            ),
            part(
                "M26.7 24.5Q27.4 26.5 25.2 26.4Q24.1 28.2 22.9 26.5Q20.8 26.7 21.4 24.7Q19.9 23.2 21.8 22.4Q22.1 20.2 23.9 21.3Q25.7 20.2 26.1 22.3" +
                    "Q28.1 23 26.7 24.5Z",
                fill = 0xFFffc02eL, stroke = NONE, width = 1.4f,
            ),
        ),
    )
    ToolbarIconId.AI -> IconSpec(
        "AI",
        "the sugar skull knows",
        1.4f,
        listOf(
            part(
                "M16 3c6.6 0 10.5 4.6 10.5 10 0 3.5-1.6 5.8-3.7 7v2.6c0 2.6-2.6 6-6.8 6s-6.8-3.4-6.8-6V20c-2.1-1.2-3.7-3.5-3.7-7C5.5 7.6 9.4 3 16 3" +
                    "z",
                fill = 0xFFfbf3e3L, stroke = OUTLINE, width = 1.4f,
            ),
            part(
                "M8.2 13.4A3.3 3.3 0 1 0 14.8 13.4A3.3 3.3 0 1 0 8.2 13.4Z",
                fill = 0xFF2b1238L, stroke = 0xFFe6007eL, width = 1.5f,
            ),
            part(
                "M17.2 13.4A3.3 3.3 0 1 0 23.8 13.4A3.3 3.3 0 1 0 17.2 13.4Z",
                fill = 0xFF2b1238L, stroke = 0xFFe6007eL, width = 1.5f,
            ),
            part(
                "M16 16.6l-1.7 2.8h3.4z",
                fill = 0xFFe6007eL, stroke = 0xFFe6007eL, width = 0.7f,
            ),
            part(
                "M13 22.6v3M16 23v3.6M19 22.6v3",
                fill = NONE, stroke = 0xFF2b1238L, width = 1f,
            ),
            part(
                "M14 7.4A2 2 0 1 0 18 7.4A2 2 0 1 0 14 7.4Z",
                fill = 0xFFff9a1fL, stroke = NONE, width = 1.4f,
            ),
        ),
    )
    ToolbarIconId.VIDEO -> IconSpec(
        "Video",
        "flutters frame to frame",
        1.4f,
        listOf(
            part(
                "M16 14.1C17.8 9.6 24.1 6 28.6 7.8C29.95 10.5 27.7 15.9 21.4 17.25C18.7 17.7 16.9 16.35 16 14.1Z",
                fill = 0xFFff8a1fL, stroke = 0xFF1d1016L, width = 1.08f,
            ),
            part(
                "M16 15.45C18.7 16.35 24.1 17.25 25 20.85C24.55 24.45 20.05 24.9 17.8 21.75C16.9 19.95 16.45 17.7 16 15.45Z",
                fill = 0xFFffa23aL, stroke = 0xFF1d1016L, width = 1.08f,
            ),
            part(
                "M16.9 13.65L26.8 8.7M17.8 15L27.25 11.85M18.25 16.35L22.75 15.9M16.9 16.8L23.2 20.4M17.35 18.15L20.05 22.65",
                fill = NONE, stroke = 0xFF1d1016L, width = 0.5f,
            ),
            part(
                "M27.7 8.7h0.05M28.15 10.95h0.05M26.8 13.2h0.05M24.55 21.75h0.05M22.75 23.1h0.05M20.5 23.55h0.05",
                fill = NONE, stroke = 0xFFffffffL, width = 0.9f,
            ),
            part(
                "M16 14.1C17.8 9.6 24.1 6 28.6 7.8C29.95 10.5 27.7 15.9 21.4 17.25C18.7 17.7 16.9 16.35 16 14.1Z",
                fill = 0xFFff8a1fL, stroke = 0xFF1d1016L, width = 1.08f,
            ),
            part(
                "M16 15.45C18.7 16.35 24.1 17.25 25 20.85C24.55 24.45 20.05 24.9 17.8 21.75C16.9 19.95 16.45 17.7 16 15.45Z",
                fill = 0xFFffa23aL, stroke = 0xFF1d1016L, width = 1.08f,
            ),
            part(
                "M16.9 13.65L26.8 8.7M17.8 15L27.25 11.85M18.25 16.35L22.75 15.9M16.9 16.8L23.2 20.4M17.35 18.15L20.05 22.65",
                fill = NONE, stroke = 0xFF1d1016L, width = 0.5f,
            ),
            part(
                "M27.7 8.7h0.05M28.15 10.95h0.05M26.8 13.2h0.05M24.55 21.75h0.05M22.75 23.1h0.05M20.5 23.55h0.05",
                fill = NONE, stroke = 0xFFffffffL, width = 0.9f,
            ),
            part(
                "M16 14.1C14.2 9.6 7.9 6 3.4 7.8C2.05 10.5 4.3 15.9 10.6 17.25C13.3 17.7 15.1 16.35 16 14.1Z",
                fill = 0xFFff8a1fL, stroke = 0xFF1d1016L, width = 1.08f,
            ),
            part(
                "M16 15.45C13.3 16.35 7.9 17.25 7 20.85C7.45 24.45 11.95 24.9 14.2 21.75C15.1 19.95 15.55 17.7 16 15.45Z",
                fill = 0xFFffa23aL, stroke = 0xFF1d1016L, width = 1.08f,
            ),
            part(
                "M15.1 13.65L5.2 8.7M14.2 15L4.75 11.85M13.75 16.35L9.25 15.9M15.1 16.8L8.8 20.4M14.65 18.15L11.95 22.65",
                fill = NONE, stroke = 0xFF1d1016L, width = 0.5f,
            ),
            part(
                "M4.3 8.7h-0.05M3.85 10.95h-0.05M5.2 13.2h-0.05M7.45 21.75h-0.05M9.25 23.1h-0.05M11.5 23.55h-0.05",
                fill = NONE, stroke = 0xFFffffffL, width = 0.9f,
            ),
            part(
                "M16 14.1C14.2 9.6 7.9 6 3.4 7.8C2.05 10.5 4.3 15.9 10.6 17.25C13.3 17.7 15.1 16.35 16 14.1Z",
                fill = 0xFFff8a1fL, stroke = 0xFF1d1016L, width = 1.08f,
            ),
            part(
                "M16 15.45C13.3 16.35 7.9 17.25 7 20.85C7.45 24.45 11.95 24.9 14.2 21.75C15.1 19.95 15.55 17.7 16 15.45Z",
                fill = 0xFFffa23aL, stroke = 0xFF1d1016L, width = 1.08f,
            ),
            part(
                "M15.1 13.65L5.2 8.7M14.2 15L4.75 11.85M13.75 16.35L9.25 15.9M15.1 16.8L8.8 20.4M14.65 18.15L11.95 22.65",
                fill = NONE, stroke = 0xFF1d1016L, width = 0.5f,
            ),
            part(
                "M4.3 8.7h-0.05M3.85 10.95h-0.05M5.2 13.2h-0.05M7.45 21.75h-0.05M9.25 23.1h-0.05M11.5 23.55h-0.05",
                fill = NONE, stroke = 0xFFffffffL, width = 0.9f,
            ),
            part(
                "M16 10.95V22.2",
                fill = NONE, stroke = 0xFF1d1016L, width = 1.44f,
            ),
            part(
                "M16 10.95C15.55 9.15 14.2 8.25 13.3 7.8M16 10.95C16.45 9.15 17.8 8.25 18.7 7.8",
                fill = NONE, stroke = 0xFF1d1016L, width = 0.45f,
            ),
        ),
    )
    ToolbarIconId.COMPARE -> IconSpec(
        "Compare",
        "two calaveras side by side",
        1.4f,
        listOf(
            part(
                "M8 8.4c3.96 0 6.3 2.76 6.3 6c0 2.1 -0.96 3.48 -2.22 4.2v1.56c0 1.56 -1.56 3.6 -4.08 3.6s-4.08 -2.04 -4.08 -3.6V18.6" +
                    "c-1.26 -0.72 -2.22 -2.1 -2.22 -4.2C1.7 11.16 4.04 8.4 8 8.4Z",
                fill = 0xFFfbf3e3L, stroke = OUTLINE, width = 1.32f,
            ),
            part(
                "M3.32 14.64A1.98 1.98 0 1 0 7.28 14.64A1.98 1.98 0 1 0 3.32 14.64Z",
                fill = 0xFF2b1238L, stroke = 0xFFe6007eL, width = 0.9f,
            ),
            part(
                "M8.72 14.64A1.98 1.98 0 1 0 12.68 14.64A1.98 1.98 0 1 0 8.72 14.64Z",
                fill = 0xFF2b1238L, stroke = 0xFFe6007eL, width = 0.9f,
            ),
            part(
                "M8 16.56l-1.02 1.68h2.04Z",
                fill = 0xFFe6007eL, stroke = 0xFFe6007eL, width = 0.42f,
            ),
            part(
                "M6.2 20.16v1.8M8 20.4v2.16M9.8 20.16v1.8",
                fill = NONE, stroke = 0xFF2b1238L, width = 0.6f,
            ),
            part(
                "M6.8 11.04A1.2 1.2 0 1 0 9.2 11.04A1.2 1.2 0 1 0 6.8 11.04Z",
                fill = 0xFFff9a1fL, stroke = NONE, width = 1.32f,
            ),
            part(
                "M23.6 8.4c3.96 0 6.3 2.76 6.3 6c0 2.1 -0.96 3.48 -2.22 4.2v1.56c0 1.56 -1.56 3.6 -4.08 3.6s-4.08 -2.04 -4.08 -3.6V18.6" +
                    "c-1.26 -0.72 -2.22 -2.1 -2.22 -4.2C17.3 11.16 19.64 8.4 23.6 8.4Z",
                fill = 0xFFfbf3e3L, stroke = OUTLINE, width = 1.32f,
            ),
            part(
                "M18.92 14.64A1.98 1.98 0 1 0 22.88 14.64A1.98 1.98 0 1 0 18.92 14.64Z",
                fill = 0xFF2b1238L, stroke = 0xFF12b5b0L, width = 0.9f,
            ),
            part(
                "M24.32 14.64A1.98 1.98 0 1 0 28.28 14.64A1.98 1.98 0 1 0 24.32 14.64Z",
                fill = 0xFF2b1238L, stroke = 0xFF12b5b0L, width = 0.9f,
            ),
            part(
                "M23.6 16.56l-1.02 1.68h2.04Z",
                fill = 0xFF12b5b0L, stroke = 0xFF12b5b0L, width = 0.42f,
            ),
            part(
                "M21.8 20.16v1.8M23.6 20.4v2.16M25.4 20.16v1.8",
                fill = NONE, stroke = 0xFF2b1238L, width = 0.6f,
            ),
            part(
                "M22.4 11.04A1.2 1.2 0 1 0 24.8 11.04A1.2 1.2 0 1 0 22.4 11.04Z",
                fill = 0xFFff9a1fL, stroke = NONE, width = 1.32f,
            ),
        ),
    )
    ToolbarIconId.CASES -> IconSpec(
        "Cases",
        "a candle lights the way",
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
                "M11.8 12.6H14.2A0.6 0.6 0 0 1 14.8 13.2V18.2A0.6 0.6 0 0 1 14.2 18.8H11.8A0.6 0.6 0 0 1 11.2 18.2V13.2A0.6 0.6 0 0 1 11.8 12.6Z",
                fill = 0xFFe6007eL, stroke = NONE, width = 1.4f,
            ),
            part(
                "M13 5.8C15.6 8.4 15.8 10.6 13 12C10.2 10.6 10.4 8.4 13 5.8Z",
                fill = 0xFFff9a1fL, stroke = NONE, width = 1.4f,
            ),
            part(
                "M13 8.6C14 9.8 14 10.8 13 11.5C12 10.8 12 9.8 13 8.6Z",
                fill = 0xFFffe07aL, stroke = NONE, width = 1.4f,
            ),
        ),
    )
    ToolbarIconId.OPEN -> IconSpec(
        "Open file",
        "through the marigold arch",
        1.4f,
        listOf(
            part(
                "M9.4 29v-10a6.6 6.6 0 0 1 13.2 0v10z",
                fill = 0xFFffd36bL, stroke = NONE, width = 1.4f,
            ),
            part(
                "M7.8 16.4Q8.9 18.3 6.7 18.6Q6 20.6 4.4 19.1Q2.3 19.8 2.5 17.6Q0.7 16.4 2.5 15.2Q2.3 13 4.4 13.7Q6 12.2 6.7 14.2Q8.9 14.5 7.8 16.4Z",
                fill = 0xFFff9a1fL, stroke = NONE, width = 1.4f,
            ),
            part(
                "M9.1 11.5Q9.7 13.7 7.5 13.3Q6.1 15 5.1 13.1Q2.9 13.1 3.7 11.1Q2.3 9.4 4.4 8.8Q4.9 6.7 6.7 7.9Q8.6 6.9 8.8 9.1Q10.8 10.1 9.1 11.5Z",
                fill = 0xFFe8650cL, stroke = NONE, width = 1.4f,
            ),
            part(
                "M12.8 8.1Q12.7 10.3 10.7 9.3Q8.9 10.6 8.4 8.4Q6.4 7.8 7.7 6.1Q6.9 4.1 9.1 4.1Q10.2 2.2 11.5 3.9Q13.7 3.6 13.2 5.7" +
                    "Q14.8 7.2 12.8 8.1Z",
                fill = 0xFFff9a1fL, stroke = NONE, width = 1.4f,
            ),
            part(
                "M17.7 7.2Q17 9.2 15.4 7.7Q13.3 8.4 13.5 6.2Q11.7 5 13.5 3.8Q13.3 1.6 15.4 2.3Q17 0.8 17.8 2.8Q19.9 3.1 18.8 5Q19.9 6.9 17.7 7.2Z",
                fill = 0xFFe8650cL, stroke = NONE, width = 1.4f,
            ),
            part(
                "M22.5 9.1Q21.2 10.9 20.1 8.9Q17.9 9 18.7 6.9Q17.4 5.2 19.5 4.6Q19.9 2.5 21.7 3.7Q23.7 2.8 23.8 5Q25.8 5.9 24.2 7.4" +
                    "Q24.7 9.5 22.5 9.1Z",
                fill = 0xFFff9a1fL, stroke = NONE, width = 1.4f,
            ),
            part(
                "M25.7 13.5Q23.9 14.7 23.5 12.6Q21.4 12 22.8 10.3Q22 8.2 24.1 8.3Q25.2 6.4 26.6 8.1Q28.7 7.8 28.2 9.9Q29.8 11.4 27.8 12.3" +
                    "Q27.7 14.5 25.7 13.5Z",
                fill = 0xFFe8650cL, stroke = NONE, width = 1.4f,
            ),
            part(
                "M26.4 19.1Q24.3 19.8 24.5 17.6Q22.7 16.4 24.5 15.2Q24.3 13 26.4 13.7Q28 12.2 28.8 14.2Q30.9 14.5 29.8 16.4Q30.9 18.3 28.7 18.6" +
                    "Q27.9 20.6 26.4 19.1Z",
                fill = 0xFFff9a1fL, stroke = NONE, width = 1.4f,
            ),
            part(
                "M7.5 23.2Q8 25.2 6 24.9Q4.7 26.5 3.7 24.7Q1.6 24.7 2.4 22.8Q1.1 21.2 3.1 20.6Q3.5 18.6 5.2 19.8Q7 18.9 7.2 20.9Q9 21.8 7.5 23.2Z",
                fill = 0xFFff9a1fL, stroke = NONE, width = 1.4f,
            ),
            part(
                "M7.5 28.8Q8 30.8 6 30.5Q4.7 32.1 3.7 30.3Q1.6 30.3 2.4 28.4Q1.1 26.8 3.1 26.2Q3.5 24.2 5.2 25.4Q7 24.5 7.2 26.5Q9 27.4 7.5 28.8Z",
                fill = 0xFFff9a1fL, stroke = NONE, width = 1.4f,
            ),
            part(
                "M29.5 23.2Q30 25.2 28 24.9Q26.7 26.5 25.7 24.7Q23.6 24.7 24.4 22.8Q23.1 21.2 25.1 20.6Q25.5 18.6 27.2 19.8Q29 18.9 29.2 20.9" +
                    "Q31 21.8 29.5 23.2Z",
                fill = 0xFFff9a1fL, stroke = NONE, width = 1.4f,
            ),
            part(
                "M29.5 28.8Q30 30.8 28 30.5Q26.7 32.1 25.7 30.3Q23.6 30.3 24.4 28.4Q23.1 26.8 25.1 26.2Q25.5 24.2 27.2 25.4Q29 24.5 29.2 26.5" +
                    "Q31 27.4 29.5 28.8Z",
                fill = 0xFFff9a1fL, stroke = NONE, width = 1.4f,
            ),
        ),
    )
    ToolbarIconId.LAYOUT -> IconSpec(
        "Layout",
        "the tiers of the altar",
        1.4f,
        listOf(
            part(
                "M11 9.5H21V15.5H11Z",
                fill = 0xFFe6007eL, stroke = OUTLINE, width = 1.4f,
            ),
            part(
                "M7 15.5H25V21.5H7Z",
                fill = 0xFF12b5b0L, stroke = OUTLINE, width = 1.4f,
            ),
            part(
                "M3 21.5H29V27.5H3Z",
                fill = 0xFF7a35c4L, stroke = OUTLINE, width = 1.4f,
            ),
            part(
                "M18.7 5.8Q19.9 7.4 17.9 7.7Q17.6 9.7 16 8.5Q14.4 9.7 14.1 7.7Q12.1 7.4 13.3 5.8Q12.1 4.2 14.1 3.9Q14.4 1.9 16 3.1" +
                    "Q17.6 1.9 17.9 3.9Q19.9 4.2 18.7 5.8Z",
                fill = 0xFFff9a1fL, stroke = NONE, width = 1.4f,
            ),
            part(
                "M6 21.5v-3M26 21.5v-3",
                fill = NONE, stroke = 0xFFffc02eL, width = 1.8f,
            ),
        ),
    )
    ToolbarIconId.SETTINGS -> IconSpec(
        "Settings",
        "pan de muerto, like a gear",
        1.4f,
        listOf(
            part(
                "M16 2.6v4M16 25.4v4M2.6 16h4M25.4 16h4M6.5 6.5l2.8 2.8M22.7 22.7l2.8 2.8M25.5 6.5l-2.8 2.8M9.3 22.7l-2.8 2.8",
                fill = NONE, stroke = 0xFFa7641fL, width = 3.6f,
            ),
            part(
                "M5 16A11 11 0 1 0 27 16A11 11 0 1 0 5 16Z",
                fill = 0xFFd9933fL, stroke = OUTLINE, width = 1.4f,
            ),
            part(
                "M16 5.6v20.8M5.6 16h20.8",
                fill = NONE, stroke = 0xFFa7641fL, width = 3f,
            ),
            part(
                "M12.4 16A3.6 3.6 0 1 0 19.6 16A3.6 3.6 0 1 0 12.4 16Z",
                fill = 0xFFf0c283L, stroke = 0xFFa7641fL, width = 1f,
            ),
            part(
                "M10.4 10.6h.1M21.6 10.6h.1M10.4 21.4h.1M21.6 21.4h.1",
                fill = NONE, stroke = 0xFFffffffL, width = 1.8f,
            ),
        ),
    )
}
