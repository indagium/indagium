package com.indagium.utils

import com.indagium.model.AnnBlock
import com.indagium.model.LogFileSection

/** Separates the file from its section in a source label: "Untitled.json · HCI". */
internal const val LOG_REF_SOURCE_SEPARATOR = " · "

/** A section's short title: the title the source tab lists for it in [sections], else the last " / " segment of its id. */
internal fun logRefSectionTitle(sectionId: String, sections: List<LogFileSection> = emptyList()): String =
    sections.firstOrNull { it.id == sectionId }?.title?.takeIf { it.isNotBlank() }
        ?: sectionId.substringAfterLast(" / ").ifBlank { sectionId }

/**
 * What a row reference says about where its rows come from: the source file, plus " · " and the section's short title when the
 * rows were captured from a section. Null when the reference has no source file (rows of the note's own tab), as before.
 * [sections] are the source tab's log sections when the caller has them; without them the title is the id's last segment.
 */
internal fun logRefSourceLabel(block: AnnBlock.LogRef, sections: List<LogFileSection> = emptyList()): String? {
    val file = block.sourceFilename ?: return null
    val section = block.sourceSectionId ?: return file
    return file + LOG_REF_SOURCE_SEPARATOR + logRefSectionTitle(section, sections)
}
