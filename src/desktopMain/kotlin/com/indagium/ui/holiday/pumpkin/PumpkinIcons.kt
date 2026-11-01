// Literal drawing geometry and colours (SVG path data and hex colours from the approved holiday canvas), not tunables.
@file:Suppress("MagicNumber")

package com.indagium.ui.holiday.pumpkin

import androidx.compose.ui.graphics.Color
import com.indagium.ui.*
import com.indagium.ui.holiday.HolidayIcon
import com.indagium.ui.holiday.HolidayIconSet
import com.indagium.ui.holiday.IconPart
import com.indagium.ui.holiday.IconSpec
import com.indagium.ui.holiday.NONE
import com.indagium.ui.holiday.OUTLINE
import com.indagium.ui.holiday.ToolbarIconId
import com.indagium.ui.holiday.circlePath
import com.indagium.ui.holiday.ellipsePath
import com.indagium.ui.holiday.holidayIconMap
import com.indagium.ui.holiday.part
import com.indagium.ui.holiday.roundRectPath

/** The pumpkin patch's ten toolbar pictures: a tombstone for New tab, a pumpkin for Filters, a spellbook for Notes, ... */
internal object PumpkinIcons : HolidayIconSet {
    override fun icons(outline: Color): Map<ToolbarIconId, HolidayIcon> = holidayIconMap(outline, ::pumpkinSpec)
}

private fun pumpkinSpec(id: ToolbarIconId): IconSpec = when (id) {
    ToolbarIconId.NEW -> IconSpec("New tab", "the tombstone", 1.4f, tombstoneParts())
    ToolbarIconId.FILTERS -> IconSpec("Filters", "the pumpkin", 1.4f, pumpkinParts())
    ToolbarIconId.NOTES -> IconSpec("Notes", "the spellbook", 1.4f, spellbookParts())
    ToolbarIconId.AI -> IconSpec("AI", "the crystal ball", 1.4f, crystalParts())
    ToolbarIconId.VIDEO -> IconSpec("Video", "the bat", 1.2f, batParts())
    ToolbarIconId.COMPARE -> IconSpec("Compare", "the ghosts", 1.4f, ghostsParts())
    ToolbarIconId.CASES -> IconSpec("Cases", "the eyeball", 1.4f, eyeballParts())
    ToolbarIconId.OPEN -> IconSpec("Open file", "the coffin", 1.4f, coffinParts())
    ToolbarIconId.LAYOUT -> IconSpec("Layout", "the web", 1.2f, webParts())
    ToolbarIconId.SETTINGS -> IconSpec("Settings", "the cauldron", 1.4f, cauldronParts())
}

private fun tombstoneParts(): List<IconPart> = listOf(
    part("M8 28V13a8 8 0 0 1 16 0v15z", fill = 0xFFaeb3b9),
    part("M16 12v9M11.5 16.5h9", stroke = 0xFF3b4046, width = 2.4f),
    part("M4 28.5h24", stroke = 0xFF5f8f3a, width = 2.2f),
)

private fun pumpkinParts(): List<IconPart> = listOf(
    part("M16 10c-.6-3 .6-5.2 3.4-6.4", stroke = 0xFF3f8f3a, width = 2.2f),
    part(ellipsePath(10.5f, 18.5f, 7f, 9f), fill = 0xFFe86f12),
    part(ellipsePath(21.5f, 18.5f, 7f, 9f), fill = 0xFFe86f12),
    part(ellipsePath(16f, 18.5f, 6f, 9.5f), fill = 0xFFff9a3c),
    part("M11 16l2.4-3 2 3zM17 16l2.4-3 2 3z", fill = 0xFF3b1a04, stroke = NONE),
    part("M10.5 21.5q5.5 4 11 0l-2 .8-1.6-1-1.9 1.2-1.9-1.2-1.6 1z", fill = 0xFF3b1a04, stroke = NONE),
)

private fun spellbookParts(): List<IconPart> = listOf(
    part(roundRectPath(6f, 4.5f, 20f, 23.5f, 2f), fill = 0xFF6b3fa0),
    part("M10 4.5v23.5", stroke = 0xFF3d2066, width = 1.6f),
    part("M18 9.5l1.5 3 3.3.5-2.4 2.3.6 3.3-3-1.6-3 1.6.6-3.3-2.4-2.3 3.3-.5z", fill = 0xFFffd166, stroke = NONE),
    part("M14 23h8", stroke = 0xFFc9a6ff),
)

private fun crystalParts(): List<IconPart> = listOf(
    part(circlePath(16f, 14f, 9.5f), fill = 0xFF9fd8ff),
    part(circlePath(18.5f, 16f, 3f), fill = 0xFFc084fc, stroke = NONE),
    part("M11.5 10.5a5.5 5.5 0 0 1 5-3", stroke = 0xFFffffff, width = 1.8f),
    part("M8.5 28l2.2-5.5h10.6L23.5 28z", fill = 0xFF7a4a2a),
)

private fun batParts(): List<IconPart> = listOf(
    part(
        "M16 12.5c1.4 0 2.3.8 2.3.8l.9-2 .6 2.6c2-1.4 5-2.4 9.2-.4-2 1-3 3-3 5.2-2-1-4-1-5.6 1.1-1.2-1-2.9-1.6-4.4-1.6s-3.2.6-4.4 1.6" +
            "C10 17.7 8 17.7 6 18.7c0-2.2-1-4.2-3-5.2 4.2-2 7.2-1 9.2.4l.6-2.6.9 2s.9-.8 2.3-.8z",
        fill = 0xFF3a2d4f,
    ),
    part(circlePath(14.6f, 14.4f, 0.9f), fill = 0xFFffcc33, stroke = NONE),
    part(circlePath(17.4f, 14.4f, 0.9f), fill = 0xFFffcc33, stroke = NONE),
)

private fun ghostsParts(): List<IconPart> = listOf(
    part("M3 27V13a6 6 0 0 1 12 0v14l-2-2-2 2-2-2-2 2-2-2z", fill = 0xFFf4f1ea),
    part("M17 27V15a5.5 5.5 0 0 1 11 0v12l-1.8-1.8-1.9 1.8-1.8-1.8-1.9 1.8-1.8-1.8z", fill = 0xFFe4e0f5),
    part(circlePath(7f, 14f, 1.1f), fill = 0xFF222222, stroke = NONE),
    part(circlePath(11f, 14f, 1.1f), fill = 0xFF222222, stroke = NONE),
    part(circlePath(20.5f, 16f, 1f), fill = 0xFF222222, stroke = NONE),
    part(circlePath(24.5f, 16f, 1f), fill = 0xFF222222, stroke = NONE),
)

private fun eyeballParts(): List<IconPart> = listOf(
    part(circlePath(16f, 16f, 11f), fill = 0xFFfbf7ef),
    part("M6.5 11.5l3 1.8M8 21.5l3-1.4M25.5 10.5l-3 2.4M24.5 22l-3-1.8", stroke = 0xFFd64545, width = 1f),
    part(circlePath(16f, 16f, 5.5f), fill = 0xFFe0812a),
    part(circlePath(16f, 16f, 2.6f), fill = 0xFF1b1b1b, stroke = NONE),
    part(circlePath(17.5f, 14.5f, 0.9f), fill = 0xFFffffff, stroke = NONE),
)

private fun coffinParts(): List<IconPart> = listOf(
    part("M12 3h8l5 7-3 19H10L7 10z", fill = 0xFF7b4b2a),
    part("M16 9v10M12.5 12.5h7", stroke = 0xFFeadbb8, width = 2f),
)

private fun webParts(): List<IconPart> = listOf(
    part("M16 3v26M3 16h26M6.8 6.8l18.4 18.4M25.2 6.8L6.8 25.2"),
    part("M16 8l5.7 2.3L24 16l-2.3 5.7L16 24l-5.7-2.3L8 16l2.3-5.7z"),
    part("M16 12l2.8 1.2L20 16l-1.2 2.8L16 20l-2.8-1.2L12 16l1.2-2.8z"),
    part("M22.5 3.5v6"),
    part(circlePath(22.5f, 11f, 2.4f), fill = OUTLINE, stroke = NONE),
)

private fun cauldronParts(): List<IconPart> = listOf(
    part(circlePath(12f, 9f, 2.4f), fill = 0xFF7ee08a),
    part(circlePath(18f, 6.5f, 3f), fill = 0xFF7ee08a),
    part(circlePath(22.5f, 10f, 1.7f), fill = 0xFF7ee08a),
    part("M6.5 13c0 8 3.5 13 9.5 13s9.5-5 9.5-13z", fill = 0xFF33383d),
    part("M5 13h22", width = 2.4f),
    part("M10 25.5l-2 3.5M22 25.5l2 3.5", width = 1.8f),
)
