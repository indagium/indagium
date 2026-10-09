package com.indagium.testing.authoring

import java.awt.Color
import java.awt.Font
import java.awt.RenderingHints
import java.awt.image.BufferedImage

// The one contact-sheet layout of the recording rewrite: the video storyboard (RecordedVideoTimeline) and the sheets composed from
// the stored screen-state JPEGs (RewriteEvidence) both draw their cells here, so a sheet looks the same wherever it came from.

private const val SHEET_COLUMNS = 3
private const val SHEET_CELL_WIDTH = 480
private const val SHEET_IMAGE_HEIGHT = 800
private const val SHEET_LABEL_HEIGHT = 42
private const val SHEET_LABEL_FONT_SIZE = 15
private const val SHEET_LABEL_BASELINE = 27
private const val SHEET_LABEL_INSET = 10
private val SHEET_BACKGROUND = Color(17, 21, 29)
private val SHEET_AFTER = Color(127, 205, 160)
private val SHEET_BEFORE = Color(235, 202, 111)
private val SHEET_OTHER = Color(185, 190, 205)

/** One picture of a sheet: [relation] ("after", "before" or anything else) picks the label colour, [label] is the text under it. */
internal class ContactSheetCell(val image: BufferedImage, val relation: String, val label: String)

/** The cells in reading order, [SHEET_COLUMNS] to a row, each scaled to fit its box and captioned. */
internal fun composeContactSheet(cells: List<ContactSheetCell>): BufferedImage {
    val rows = (cells.size + SHEET_COLUMNS - 1) / SHEET_COLUMNS
    val sheet = BufferedImage(SHEET_COLUMNS * SHEET_CELL_WIDTH, rows * (SHEET_IMAGE_HEIGHT + SHEET_LABEL_HEIGHT), BufferedImage.TYPE_INT_RGB)
    val graphics = sheet.createGraphics()
    graphics.color = SHEET_BACKGROUND
    graphics.fillRect(0, 0, sheet.width, sheet.height)
    graphics.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BICUBIC)
    graphics.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
    cells.forEachIndexed { index, cell ->
        val x = index % SHEET_COLUMNS * SHEET_CELL_WIDTH
        val y = index / SHEET_COLUMNS * (SHEET_IMAGE_HEIGHT + SHEET_LABEL_HEIGHT)
        val source = cell.image
        val scale = minOf(SHEET_CELL_WIDTH.toDouble() / source.width, SHEET_IMAGE_HEIGHT.toDouble() / source.height)
        val width = (source.width * scale).toInt()
        val height = (source.height * scale).toInt()
        graphics.drawImage(source, x + (SHEET_CELL_WIDTH - width) / 2, y + (SHEET_IMAGE_HEIGHT - height) / 2, width, height, null)
        graphics.color = when (cell.relation) {
            "after" -> SHEET_AFTER
            "before" -> SHEET_BEFORE
            else -> SHEET_OTHER
        }
        graphics.font = Font(Font.SANS_SERIF, Font.BOLD, SHEET_LABEL_FONT_SIZE)
        graphics.drawString(cell.label, x + SHEET_LABEL_INSET, y + SHEET_IMAGE_HEIGHT + SHEET_LABEL_BASELINE)
    }
    graphics.dispose()
    return sheet
}
