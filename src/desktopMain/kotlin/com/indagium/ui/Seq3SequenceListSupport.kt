package com.indagium.ui

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.compositeOver
import com.indagium.diagram3.Seq3Document
import com.indagium.diagram3.Seq3Filter
import com.indagium.diagram3.Seq3FilterCounts
import com.indagium.diagram3.seq3QueueRows

/** The pill counts of the sequence list: filtering is by parent message, so each pill counts the
 *  drawn rows whose message passes it (the Templates list counts messages). */
internal fun seq3SequenceFilterCounts(document: Seq3Document, rows: List<Seq3SequenceRow>): Seq3FilterCounts {
    fun matching(filter: Seq3Filter): Int {
        val ids = seq3QueueRows(document, filter).mapTo(HashSet()) { it.id }
        return rows.count { it.message.id in ids }
    }
    return Seq3FilterCounts(
        all = rows.size,
        needsTarget = matching(Seq3Filter.NEEDS_TARGET),
        edited = matching(Seq3Filter.EDITED),
        hidden = matching(Seq3Filter.HIDDEN),
    )
}

/** The lifted row's fill: the accent tint laid over the opaque panel colour, so the rows it floats
 *  over never show through the ghost. */
internal fun seq3SequenceGhostBackground(theme: ThemeColors): Color = theme.abg.compositeOver(theme.p)
