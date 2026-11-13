@file:Suppress("MagicNumber", "MaxLineLength")

package com.indagium.ui.holiday.bear
import androidx.compose.ui.graphics.Color
import com.indagium.ui.holiday.*

internal object CoffeeBearIcons : HolidayIconSet {
    override fun icons(outline: Color): Map<ToolbarIconId, HolidayIcon> = holidayIconMap(outline, ::bearIcon)

    override fun findIcon(outline: Color): HolidayIcon = icons(outline).getValue(ToolbarIconId.CASES).copy(name = "Find")
}

private fun bearIcon(id: ToolbarIconId): IconSpec = when (id) {
    ToolbarIconId.NEW -> IconSpec("New tab", "a fresh cup", 1.4f, listOf(
        part("M11 2.5c-1.6 2 1.6 3 0 5.2M16.5 1.8c-1.6 2 1.6 3 0 5.2", fill = NONE, stroke = 0xFFbfe3f2L, width = 1.6f),
        part("M8 10h15l-2.2 18H10.2z", fill = 0xFFe0453bL, stroke = OUTLINE),
        part(ellipsePath(15.5f, 10f, 7.5f, 1.8f), fill = 0xFF3a2418L, stroke = OUTLINE),
        part("M15.5 15.5v7M12 19h7", fill = NONE, stroke = 0xFFffffffL, width = 1.8f),
    ))
    ToolbarIconId.FILTERS -> IconSpec("Filters", "a pour-over filter", 1.4f, listOf(
        part("M5.5 7h21l-6.5 11h-8z", fill = 0xFFf4f1e8L, stroke = OUTLINE),
        part("M11 9.5l3 6.5M21 9.5l-3 6.5M16 9.5v6.5", fill = NONE, stroke = 0xFFc9b98aL, width = 1f),
        part("M9 19.5h14", fill = NONE, stroke = OUTLINE, width = 1.8f),
        part(circlePath(16f, 21.6f, .9f), fill = 0xFF5a3a26L, stroke = NONE),
        part("M11 23h10l-1.2 6h-7.6z", fill = 0xFFe0453bL, stroke = OUTLINE),
    ))
    ToolbarIconId.NOTES -> IconSpec("Notes", "a note with a paw print", 1.4f, listOf(
        part(roundRectPath(7.0f, 3.5f, 18.0f, 25.0f, 2.0f), fill = 0xFFfffdf6L, stroke = OUTLINE),
        part("M11 9h10M11 13h7", fill = NONE, stroke = 0xFF8ea3b8L, width = 1.4f),
        part(ellipsePath(18.5f, 23f, 3.2f, 2.6f), fill = 0xFF2b2b2bL, stroke = NONE),
        part(circlePath(15.4f, 19.4f, 1.3f), fill = 0xFF2b2b2bL, stroke = NONE),
        part(circlePath(18.5f, 18.1f, 1.3f), fill = 0xFF2b2b2bL, stroke = NONE),
        part(circlePath(21.6f, 19.4f, 1.3f), fill = 0xFF2b2b2bL, stroke = NONE),
    ))
    ToolbarIconId.AI -> IconSpec("AI", "ask the bear", 1.4f, listOf(
        part(circlePath(8.5f, 9f, 4f), fill = 0xFFf1e6b8L, stroke = OUTLINE),
        part(circlePath(23.5f, 9f, 4f), fill = 0xFFf1e6b8L, stroke = OUTLINE),
        part(circlePath(8.5f, 9f, 1.8f), fill = 0xFFe2d29aL, stroke = NONE),
        part(circlePath(23.5f, 9f, 1.8f), fill = 0xFFe2d29aL, stroke = NONE),
        part(circlePath(16f, 17.5f, 10f), fill = 0xFFf1e6b8L, stroke = OUTLINE),
        part(circlePath(12.5f, 15f, 1.1f), fill = 0xFF1b1b1bL, stroke = NONE),
        part(circlePath(19.5f, 15f, 1.1f), fill = 0xFF1b1b1bL, stroke = NONE),
        part(ellipsePath(16f, 19.2f, 2.1f, 1.7f), fill = 0xFF1b1b1bL, stroke = NONE),
        part("M13.8 22.6q2.2 1.3 4.4 0", fill = NONE, stroke = 0xFF1b1b1bL, width = 1.3f),
    ))
    ToolbarIconId.OPEN -> IconSpec("Open file", "open the honey jar", 1.4f, listOf(
        part("M7.325 4.240L22.542 -0.704L23.593 2.530L8.376 7.474Z", fill = 0xFF8b5a2bL, stroke = OUTLINE),
        part(roundRectPath(9.0f, 10.0f, 14.0f, 3.0f, 1.0f), fill = 0xFFd99a1cL, stroke = OUTLINE),
        part("M8 13h16v12.5a3 3 0 0 1-3 3H11a3 3 0 0 1-3-3z", fill = 0xFFf2b33dL, stroke = OUTLINE),
        part(roundRectPath(11.0f, 17.0f, 10.0f, 6.5f, 1.2f), fill = 0xFFfff4dcL, stroke = NONE),
        part("M20 13v3.2a1.5 1.5 0 0 0 3 0V13", fill = 0xFFf2b33dL, stroke = NONE),
    ))
    ToolbarIconId.COMPARE -> IconSpec("Compare", "two cups side by side", 1.4f, listOf(
        part("M7 3c-1.3 1.6 1.3 2.6 0 4.4", fill = NONE, stroke = 0xFFbfe3f2L, width = 1.5f),
        part("M3 10h10l-1.6 16H4.6z", fill = 0xFFe0453bL, stroke = OUTLINE),
        part(ellipsePath(8f, 10f, 5f, 1.3f), fill = 0xFF3a2418L, stroke = OUTLINE),
        part("M19 10h10l-1.6 16h-6.8z", fill = 0xFFbfe3f2L, stroke = OUTLINE),
        part(ellipsePath(24f, 10f, 5f, 1.3f), fill = 0xFF3a2418L, stroke = OUTLINE),
    ))
    ToolbarIconId.LAYOUT -> IconSpec("Layout", "built block by block", 1.4f, listOf(
        part("M4 25.5a12 12 0 0 1 24 0z", fill = 0xFFeef7fbL, stroke = OUTLINE),
        part("M6.6 19.5h18.8M5.2 22.5h21.6M10 14h12M16 13.6v5.9M10.5 19.5v3M21.5 19.5v3M13 22.5v3M19 22.5v3", fill = NONE, stroke = 0xFF8eb9cfL, width = 1f),
        part("M13 25.5v-3.4a3 3 0 0 1 6 0v3.4z", fill = 0xFF2b4466L, stroke = NONE),
        part("M3 25.5h26", fill = NONE, stroke = OUTLINE, width = 1.6f),
    ))
    ToolbarIconId.SETTINGS -> IconSpec("Settings", "grind it just right", 1.4f, listOf(
        part("M16 10V5.5h7", fill = NONE, stroke = OUTLINE, width = 1.8f),
        part(circlePath(24f, 5.5f, 1.9f), fill = 0xFFe0453bL, stroke = OUTLINE),
        part("M9 14l2-4h10l2 4z", fill = 0xFFc9cdd2L, stroke = OUTLINE),
        part(roundRectPath(8.0f, 14.0f, 16.0f, 14.0f, 1.6f), fill = 0xFF8b5a2bL, stroke = OUTLINE),
        part(roundRectPath(11.0f, 20.0f, 10.0f, 5.0f, 1.0f), fill = 0xFF6b4220L, stroke = NONE),
        part(circlePath(16f, 22.5f, 1.2f), fill = 0xFFf2b33dL, stroke = NONE),
    ))
    ToolbarIconId.CASES -> IconSpec("Cases", "follow the tracks", 1.4f, listOf(
        part(ellipsePath(10.5f, 24.5f, 3.2f, 2.6f), fill = OUTLINE, stroke = NONE),
        part(circlePath(7.4f, 20.9f, 1.3f), fill = OUTLINE, stroke = NONE),
        part(circlePath(10.5f, 19.6f, 1.3f), fill = OUTLINE, stroke = NONE),
        part(circlePath(13.6f, 20.9f, 1.3f), fill = OUTLINE, stroke = NONE),
        part(ellipsePath(21.5f, 12.5f, 3.2f, 2.6f), fill = OUTLINE, stroke = NONE),
        part(circlePath(18.4f, 8.9f, 1.3f), fill = OUTLINE, stroke = NONE),
        part(circlePath(21.5f, 7.6f, 1.3f), fill = OUTLINE, stroke = NONE),
        part(circlePath(24.6f, 8.9f, 1.3f), fill = OUTLINE, stroke = NONE),
    ))
    ToolbarIconId.VIDEO -> IconSpec("Video", "ask the bear", 1.4f, listOf(
        part(circlePath(8.5f, 9f, 4f), fill = 0xFFf1e6b8L, stroke = OUTLINE),
        part(circlePath(23.5f, 9f, 4f), fill = 0xFFf1e6b8L, stroke = OUTLINE),
        part(circlePath(8.5f, 9f, 1.8f), fill = 0xFFe2d29aL, stroke = NONE),
        part(circlePath(23.5f, 9f, 1.8f), fill = 0xFFe2d29aL, stroke = NONE),
        part(circlePath(16f, 17.5f, 10f), fill = 0xFFf1e6b8L, stroke = OUTLINE),
        part(circlePath(12.5f, 15f, 1.1f), fill = 0xFF1b1b1bL, stroke = NONE),
        part(circlePath(19.5f, 15f, 1.1f), fill = 0xFF1b1b1bL, stroke = NONE),
        part(ellipsePath(16f, 19.2f, 2.1f, 1.7f), fill = 0xFF1b1b1bL, stroke = NONE),
        part("M13.8 22.6q2.2 1.3 4.4 0", fill = NONE, stroke = 0xFF1b1b1bL, width = 1.3f),
    ))
}
