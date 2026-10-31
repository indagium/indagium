package com.indagium.ui

import androidx.compose.foundation.background
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.zIndex
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

/** Scale of a dragged row in the Lifelines and the Sequence list. */
internal const val SEQ3_DRAG_LIFT_SCALE = 1.02f

/** How a dragged row of the Lifelines and the Sequence list is lifted: above its neighbours, slightly
 *  larger, over an opaque [panel] fill so the rows it passes never show through. The one place both
 *  lists get that look from, so they cannot drift apart. */
internal fun Modifier.seq3DragLift(isDragging: Boolean, panel: Color): Modifier =
    zIndex(if (isDragging) 1f else 0f)
        .graphicsLayer {
            if (isDragging) {
                scaleX = SEQ3_DRAG_LIFT_SCALE
                scaleY = SEQ3_DRAG_LIFT_SCALE
            }
        }
        .background(if (isDragging) panel else Color.Transparent)
