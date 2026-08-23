package com.indagium.diagram3

// ── Notes-panel-derived UML notes ───────────────────────────────────────────────────────────
//
// "From notes" (AnnotationPanel's Diagram library → + diagram menu) seeds a freshly generated
// document with one Seq3Note per curated notes block, anchored to the message that owns that
// block's first log line. The selection/text-assembly half of that feature lives in
// `ui.Seq3NotesSelection` (it needs `model.AnnBlock`/`model.LogTab`, so it cannot live here — see
// this package's own header on why it stays model-free); this file is the pure back half, dealing
// only in `Int` entry ids and `String` text so it can run from `Seq3Session.publishGenerated`
// exactly like every other diagram3 transform, headless and UI-free.

/** One notes-derived UML note waiting to be anchored to whatever message ends up carrying
 *  [anchorEntryId] after a generate pass. [id] is deterministic (`ui.seq3NotesSelection` mints it
 *  as `"note-ann-<annBlockId>"`), which is what makes [applySeq3NoteSeeds] idempotent across a
 *  regenerate instead of duplicating the note on every generate pass. */
data class Seq3NoteSeed(val id: String, val text: String, val anchorEntryId: Int)

// Mirrors Seq3Codec.kt's own (file-private) MAX_SEQ3_NOTES bound. Duplicated rather than exported
// — that constant is the codec's own decode/truncation contract, not something this package's
// generation half should reach into — but kept in sync so a From-notes seed list never produces a
// document the codec would silently truncate on its very next encode.
private const val MAX_SEQ3_NOTE_SEEDS = 400

/**
 * Folds [seeds] into [document], returning a document whose [Seq3Document.notes] carries one
 * [Seq3Note] per seed, IN ADDITION to whatever notes [document] already had that no seed claims
 * (see "Idempotent" below — this is never a wholesale replace). Two production call sites reach
 * this, both through `Seq3Session`: the initial generate a `beginFromNotes` session kicks off, and
 * a later range-change regenerate (`updateRangeAndRegenerate`) on that same session. The
 * workspace's own "Regenerate" button (`Seq3RegenerateSheet`'s review sheet,
 * `requestRegenReview`/`applyRegenReview`) never calls this function at all — that path diffs
 * against and writes back through `Seq3Command.ApplyRegeneration`, so these notes simply ride
 * along on the document itself, untouched, the same way any other hand-added note or fragment
 * survives a reviewed regeneration today.
 *
 * Anchoring: the message whose [Seq3Message.occurrences] carries [Seq3NoteSeed.anchorEntryId]
 * ([Seq3Occurrence.entryId]) — the exact, already-load-bearing log-to-message link every other v3
 * feature (arrow-click-to-log, source-trace) already relies on. When that entry produced no
 * message at all (its tag didn't rank into a lifeline, or it fell outside the generated range),
 * fall back to the first message whose SMALLEST occurrence id is `>= anchorEntryId` — the next
 * message forward in log order — and if none qualifies (the anchor is past every message), the
 * LAST message. [document] having no messages at all is the one case with nothing sensible to
 * anchor to; every seed is dropped rather than invented onto a document with nothing on it. This
 * "never drop the user's prose silently" contract is deliberate: notes carry hand-written
 * investigation prose the user curated into their analysis, not a value cheap to regenerate.
 *
 * Idempotent: a seed whose [Seq3NoteSeed.id] already names a note in [document].notes keeps that
 * note's [Seq3Note.text]/[Seq3Note.x]/[y]/[width]/[height]/[visibility] untouched — user edits on
 * the canvas win — and only its [Seq3Note.messageIds] is re-pointed to the freshly resolved
 * anchor. A note in [document].notes that no seed claims passes through completely unchanged
 * (this is what makes the function safe to call with a document that already carries OTHER notes
 * on it, e.g. from a previous seed application — see `Seq3Session.publishGenerated`'s own call
 * site for how it assembles that document). For this idempotency to actually engage, the caller
 * must hand in a [document] whose [Seq3Document.notes] already contains the previous pass's
 * seeded notes — `Seq3Generator.generateSeq3` itself never produces any, so a literally-fresh
 * generate result on its own has nothing to preserve.
 *
 * The [MAX_SEQ3_NOTE_SEEDS] bound applies to the TOTAL result (passed-through + updated + newly
 * added), not just the incoming [seeds] list — emitting more than the codec will ever decode back
 * is worse than trimming here, where it is at least a deliberate, documented choice rather than
 * losing whichever notes the codec's own position-based cutoff happens to keep. Existing notes
 * (passed-through or seed-updated) are kept in their original order and take priority over brand
 * new ones when the bound is reached, so a document already at the cap never loses a note it
 * already had just because a new seed showed up.
 */
fun applySeq3NoteSeeds(document: Seq3Document, seeds: List<Seq3NoteSeed>): Seq3Document {
    if (seeds.isEmpty() || document.messages.isEmpty()) return document

    val seedsById = seeds.associateBy { it.id }
    // One entry per (entryId -> owning message id), first message wins on a duplicate — mirrors
    // every other "which message does this log line belong to" lookup in this package.
    val messageIdByEntryId = HashMap<Int, String>()
    document.messages.forEach { message ->
        message.occurrences.forEach { occurrence -> messageIdByEntryId.putIfAbsent(occurrence.entryId, message.id) }
    }
    // Sorted by each message's own smallest occurrence id, so "the next message forward" is a
    // simple forward scan rather than re-deriving order from timestamps a second time.
    val messagesByMinEntryId = document.messages
        .map { message -> (message.occurrences.minOfOrNull { it.entryId } ?: Int.MAX_VALUE) to message.id }
        .sortedBy { it.first }

    fun resolveAnchor(anchorEntryId: Int): String =
        messageIdByEntryId[anchorEntryId]
            ?: messagesByMinEntryId.firstOrNull { (minEntryId, _) -> minEntryId >= anchorEntryId }?.second
            ?: messagesByMinEntryId.last().second

    // Every existing note passes through UNTOUCHED unless a seed claims its id, in original
    // document order — this is the "never a wholesale replace" half of the contract, and it's
    // what lets a hand-added canvas note (or a seed from an earlier pass this call didn't get
    // seeds for) simply ride along.
    val updatedExisting = document.notes.map { note ->
        val seed = seedsById[note.id] ?: return@map note
        note.copy(messageIds = listOf(resolveAnchor(seed.anchorEntryId)))
    }
    val existingIds = document.notes.mapTo(HashSet()) { it.id }
    val newlyAdded = seeds.filter { it.id !in existingIds }.map { seed ->
        Seq3Note(id = seed.id, text = seed.text, messageIds = listOf(resolveAnchor(seed.anchorEntryId)))
    }
    return document.copy(notes = (updatedExisting + newlyAdded).take(MAX_SEQ3_NOTE_SEEDS))
}
