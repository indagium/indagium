package com.indagium.ui

import com.indagium.diagram3.Seq3NoteSeed
import com.indagium.diagram3.parseSeq3Note
import com.indagium.model.AnnBlock
import com.indagium.model.LogTab

// ── Notes panel → "From notes" diagram seed ─────────────────────────────────────────────────
//
// Turns a tab's already-curated Notes document into a v3 diagram's inputs: which log lines to
// draw (exactly the noted ones, not the span between them — see [Seq3NotesSelection.entryIds]'s
// own doc) and which prose becomes a canvas [com.indagium.diagram3.Seq3Note] once
// [com.indagium.diagram3.applySeq3NoteSeeds] anchors it to a generated message. Deliberately plain
// Kotlin over [LogTab] — no Compose — so [seq3NotesSelection] is directly unit-testable and reused
// as-is by both `Seq3Session.beginFromNotes` (which needs [Seq3NotesSelection.entryIds]/[seeds])
// and the panel's popup summary line (which needs the counts alone); see `Seq3NotesSelectionTest`.

/**
 * Result of walking [LogTab.annotations] for a "From notes" diagram: [entryIds] is the exact set
 * of log lines to draw (a [com.indagium.diagram3.Seq3Range.Ids] with `selectedIds = entryIds`
 * preserves "exactly the noted lines", not the span between them — a curated set can legitimately
 * skip long stretches the user never annotated); [seeds] is the prose to carry onto the canvas as
 * notes, one per non-blank curated block; [usedBlockCount] is how many `AnnBlock.LogRef` blocks
 * actually contributed a line (for the popup's "N lines from M note blocks" subtitle);
 * [skippedCrossFileCount] is how many were skipped for being a compare-mode cross-file reference
 * (for the popup's "· K cross-file skipped" suffix — this is the ONLY place that count is
 * surfaced to the user, so it must be computed even when [entryIds] ends up non-empty).
 */
data class Seq3NotesSelection(
    val entryIds: Set<Int>,
    val seeds: List<Seq3NoteSeed>,
    val usedBlockCount: Int,
    val skippedCrossFileCount: Int,
)

/**
 * Walks [tab].annotations.blocks in document order. Only `AnnBlock.LogRef` blocks contribute:
 * a cross-file one (`sourceTabId != null`, compare mode) counts into [Seq3NotesSelection.
 * skippedCrossFileCount] and contributes no lines — this tab's own `rmap`/`logData` couldn't
 * resolve those ids to real evidence even if we tried. A same-file block contributes whichever of
 * its `logIds` are still present in [LogTab.rmap] (same guard as [AnnotationManager.addLogRefBlock]
 * — a line can be annotated and later vanish from the log, e.g. after a re-parse);
 * a block left with none after that filter contributes nothing at all, not even an empty seed.
 * `AnnBlock.Image` and a standalone `AnnBlock.Note` (one with no `LogRef` after it) never
 * contribute on their own — a note's prose only reaches the diagram by way of the `LogRef` block
 * it introduces.
 */
fun seq3NotesSelection(tab: LogTab): Seq3NotesSelection {
    val blocks = tab.annotations.blocks
    val entryIds = mutableSetOf<Int>()
    val seeds = mutableListOf<Seq3NoteSeed>()
    var usedBlockCount = 0
    var skippedCrossFileCount = 0

    blocks.forEachIndexed { index, block ->
        if (block !is AnnBlock.LogRef) return@forEachIndexed
        if (block.sourceTabId != null) {
            skippedCrossFileCount++
            return@forEachIndexed
        }
        // logIds are minted sorted (AnnotationManager.addLogRefBlock) and this filter preserves
        // that order, so `.min()` below and "the smallest surviving id" are the same value; `.min()`
        // is used anyway rather than `.first()` so this stays correct even if that upstream
        // ordering guarantee ever slips.
        val survivingIds = block.logIds.filter { it in tab.rmap }
        if (survivingIds.isEmpty()) return@forEachIndexed
        entryIds += survivingIds
        usedBlockCount++

        // The immediately preceding block's prose, but only when it's a plain Note — not a diagram
        // note (`parseSeq3Note(text) == null`, the same predicate `seq3DiagramNotes` uses to tell
        // the two apart) and not some other AnnBlock kind (Image/LogRef at i-1 contribute nothing
        // here; their own prose, if any, belongs to their own preceding-Note check when THEY are
        // walked as the "current" block).
        val precedingText = (blocks.getOrNull(index - 1) as? AnnBlock.Note)
            ?.takeIf { note -> parseSeq3Note(note.text) == null }
            ?.text?.trim()
            .orEmpty()
        val caption = block.caption.trim()
        val text = listOfNotNull(precedingText.ifBlank { null }, caption.ifBlank { null }).joinToString("\n\n")
        // A blank combined text still lets its lines into entryIds/usedBlockCount above — only the
        // note-worthy prose is optional, the curated range never is.
        if (text.isNotBlank()) {
            seeds += Seq3NoteSeed(id = "note-ann-${block.id}", text = text, anchorEntryId = survivingIds.min())
        }
    }

    return Seq3NotesSelection(entryIds, seeds, usedBlockCount, skippedCrossFileCount)
}
