package com.indagium.ui

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.indagium.model.AddAnnRequest
import com.indagium.model.AnnBlock
import com.indagium.model.VideoFrameReference
import com.indagium.model.VideoSource
import com.indagium.utils.downscaleAndEncodeJpeg
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter

// Extracted from AppState (Task 12 slice 5, mechanical — no behavior change): the annotation
// block-model mutations (add/update/remove/move/reorder a note or log-ref block, prefix/suffix/
// issue-description edits) and the "add annotation" dialog request they're launched from.
// Auto-export-on-change (autoExportAnnotations) and the broader note-file/fingerprinting
// machinery it depends on stay on AppState — upAnn (bumped to internal) is the shared choke
// point both this class and AppState.loadAnnotationsFrom route tabs-list writes through, so
// auto-export keeps firing on every annotation edit regardless of which class made it.
// Local time, not UTC: this only needs to be unique-enough per analysis (see addImageBlock's own
// doc on Annotations.frameStamp), and a local timestamp is what a human skimming a Jira attachment
// list would expect to line up with when they were actually looking at the recording.
private val FRAME_STAMP_FORMAT: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss")

internal class AnnotationManager(private val appState: AppState) {
    // App.kt binds this directly two-way (Dialog dismiss/confirm write it, not just read it),
    // so it stays a plain mutableStateOf var rather than a callback-driven method.
    var addAnnRequest by mutableStateOf<AddAnnRequest?>(null)

    fun requestAddAnn(sourceTabId: String, logIds: List<Int>) {
        val targetTabId = if (appState.compareMode && sourceTabId != appState.activeTabId) appState.activeTabId else sourceTabId
        val crossFile = targetTabId != sourceTabId
        val sourceTab = appState.tab(sourceTabId)
        addAnnRequest = AddAnnRequest(
            targetTabId = targetTabId,
            sourceTabId = sourceTabId,
            logIds = logIds,
            sourceFilename = if (crossFile && sourceTab != null) {
                appState.displaySourceLabel(sourceTab.sourcePath, sourceTab.filename, appState.tab(targetTabId)?.filename)
            } else {
                null
            },
        )
        appState.ctx = null
    }

    fun confirmAddAnn(
        targetTabId: String,
        sourceTabId: String,
        logIds: List<Int>,
        caption: String,
        sourceFilename: String?
    ) {
        val crossFile = sourceTabId != targetTabId
        val sourceEntries = if (crossFile) {
            val rmap = appState.tab(sourceTabId)?.rmap ?: emptyMap()
            logIds.sorted().mapNotNull { rmap[it] }
        } else {
            null
        }
        appState.upAnn(targetTabId) { t ->
            val block = AnnBlock.LogRef(
                id = "r${System.nanoTime()}",
                logIds = logIds.sorted(),
                caption = caption,
                sourceTabId = if (crossFile) sourceTabId else null,
                sourceFilename = if (crossFile) sourceFilename else null,
                sourceEntries = sourceEntries,
            )
            t.copy(annotations = t.annotations.copy(blocks = t.annotations.blocks + block))
        }
        addAnnRequest = null
    }

    fun addNoteBlock(tabId: String, afterId: String? = null) {
        addNoteBlock(tabId, "", afterId)
    }

    fun addNoteBlock(tabId: String, text: String, afterId: String? = null): String? {
        val id = "n${System.nanoTime()}"
        appState.upAnn(tabId) { t ->
            val note = AnnBlock.Note(id, text)
            val blocks = t.annotations.blocks.toMutableList()
            val idx =
                if (afterId != null) (blocks.indexOfFirst { it.id == afterId } + 1).coerceAtLeast(0) else blocks.size
            blocks.add(idx, note)
            t.copy(annotations = t.annotations.copy(blocks = blocks))
        }
        return id.takeIf { appState.tab(tabId)?.annotations?.blocks?.any { block -> block.id == id } == true }
    }

    fun addLogRefBlock(tabId: String, logIds: List<Int>, caption: String = ""): String? {
        val t = appState.tab(tabId) ?: return null
        val cleanIds = logIds.distinct().sorted().filter { it in t.rmap }
        if (cleanIds.isEmpty()) return null
        val id = "r${System.nanoTime()}"
        appState.upAnn(tabId) { tab ->
            val block = AnnBlock.LogRef(id = id, logIds = cleanIds, caption = caption)
            tab.copy(annotations = tab.annotations.copy(blocks = tab.annotations.blocks + block))
        }
        // Same membership check as addNoteBlock, and for the same reason: upAnn's overwrite-conflict
        // gate (AppState.upAnn/PendingNoteOverwrite) can stash this mutation on pendingNoteOverwrite
        // instead of committing it to `tabs`, so the block this call just minted may not actually be
        // observable anywhere yet. Without this check, MCP's add_log_note would report `ok: true`
        // with an id nothing can find — "a returned block id means the block is observable" is the
        // invariant every add-a-block entry point here must hold.
        return id.takeIf { appState.tab(tabId)?.annotations?.blocks?.any { block -> block.id == id } == true }
    }

    fun updateBlock(tabId: String, blockId: String, newText: String) = appState.upAnn(tabId) { t ->
        t.copy(
            annotations = t.annotations.copy(
                blocks = t.annotations.blocks.map { b ->
                    when {
                        b.id != blockId -> b
                        b is AnnBlock.Note -> b.copy(text = newText)
                        b is AnnBlock.LogRef -> b.copy(caption = newText)
                        b is AnnBlock.Image -> b.copy(caption = newText)
                        else -> b
                    }
                },
            ),
        )
    }

    // Downscale+re-encode happens here (not at the call site) so every entry point — video panel
    // "Add frame to notes" today, MCP/other producers later — gets the same storage guard: a raw
    // capture would bloat autosave, which re-serializes the whole Annotations tree on every
    // debounced edit (see AutosaveCodec.kt persistedSnapshot()). Returns null (adding nothing) if
    // sourceBytes doesn't decode as an image at all.
    //
    // This is also the sole producer of AnnBlock.Image, which makes it the one place that can set
    // Annotations.frameStamp: the "yyyyMMdd-HHmmss" moment stamped into every exported frame's
    // filename (utils/annotationImageFileName) so two people's analyses of two different logs don't
    // both produce a colliding "frame-01.jpg" when attached to the same Jira issue. Generated only
    // the FIRST time a tab gains an image (frameStamp is still null) and left untouched on every
    // later image add — a fresh stamp per image, or per export, would rename files out from under
    // already-shared Markdown anchors instead of merely creating new ones.
    fun addImageBlock(
        tabId: String,
        sourceBytes: ByteArray,
        provenance: String,
        afterId: String? = null,
        videoFrame: VideoFrameReference? = null,
        // Set at insertion rather than through a follow-up updateBlock: every upAnn triggers an
        // autosave re-serialization plus a note auto-export, so a caption-carrying producer (the
        // MCP add_image_note tool) would otherwise write the whole Annotations tree twice.
        caption: String = "",
    ): String? {
        val encoded = downscaleAndEncodeJpeg(sourceBytes) ?: return null
        val id = "i${System.nanoTime()}"
        appState.upAnn(tabId) { t ->
            val block = AnnBlock.Image(
                id = id,
                caption = caption,
                provenance = provenance,
                format = "jpeg",
                bytes = encoded,
                videoFrame = videoFrame,
            )
            val blocks = t.annotations.blocks.toMutableList()
            // A stale focus id (for example, the user removed that block while a drag was in
            // flight) is equivalent to no focused block: append rather than unexpectedly putting
            // newly pasted evidence at the top of Notes.
            val idx = afterId?.let { anchorId ->
                blocks.indexOfFirst { it.id == anchorId }.takeIf { it >= 0 }?.plus(1)
            } ?: blocks.size
            blocks.add(idx, block)
            // Gated on "this analysis had NO image at all until now", not merely on a null stamp.
            // An analysis created before frameStamp existed has a null stamp AND existing images:
            // stamping it here would rename every one of its frames on the next export, orphaning
            // the files already sitting in its _frames folder and breaking the `!frame-0N.jpg!`
            // anchors in Markdown that may already be pasted into a Jira issue. Those analyses keep
            // the legacy unstamped naming for good; only analyses that get their first image from
            // this version onwards are stamped.
            val isFirstImage = t.annotations.blocks.none { it is AnnBlock.Image }
            t.copy(
                annotations = t.annotations.copy(
                    blocks = blocks,
                    frameStamp = if (isFirstImage) FRAME_STAMP_FORMAT.format(LocalDateTime.now()) else t.annotations.frameStamp,
                ),
            )
        }
        return id
    }

    /** Adds clickable video evidence with identity and time kept as structured metadata. */
    fun addVideoFrameNote(
        tabId: String,
        sourceBytes: ByteArray,
        source: VideoSource,
        sourceLabel: String,
        positionMs: Long,
        afterId: String? = null,
        caption: String = "",
    ): String? = addImageBlock(
        tabId = tabId,
        sourceBytes = sourceBytes,
        provenance = "From $sourceLabel",
        afterId = afterId,
        videoFrame = VideoFrameReference(
            source = source,
            sourceLabel = sourceLabel,
            positionMs = positionMs.coerceAtLeast(0L),
        ),
        caption = caption,
    )

    fun removeBlock(tabId: String, blockId: String) = appState.upAnn(tabId) { t ->
        t.copy(annotations = t.annotations.copy(blocks = t.annotations.blocks.filter { it.id != blockId }))
    }

    fun moveBlock(tabId: String, blockId: String, delta: Int) = appState.upAnn(tabId) { t ->
        val list = t.annotations.blocks.toMutableList()
        val idx = list.indexOfFirst { it.id == blockId }.takeIf { it >= 0 } ?: return@upAnn t
        val to = (idx + delta).coerceIn(0, list.lastIndex)
        val item = list.removeAt(idx)
        list.add(to, item)
        t.copy(annotations = t.annotations.copy(blocks = list))
    }

    // Drag-and-drop counterpart to moveBlock's ±1 buttons — moves a block to an arbitrary index,
    // mirroring reorderSequence.
    fun reorderBlock(tabId: String, blockId: String, toIdx: Int) = appState.upAnn(tabId) { t ->
        val list = t.annotations.blocks.toMutableList()
        val fromIdx = list.indexOfFirst { it.id == blockId }.takeIf { it >= 0 } ?: return@upAnn t
        val item = list.removeAt(fromIdx)
        list.add(toIdx.coerceIn(0, list.size), item)
        t.copy(annotations = t.annotations.copy(blocks = list))
    }

    fun setPrefix(tabId: String, v: String) = appState.upAnn(tabId) { t -> t.copy(annotations = t.annotations.copy(prefix = v)) }

    fun setSuffix(tabId: String, v: String) = appState.upAnn(tabId) { t -> t.copy(annotations = t.annotations.copy(suffix = v)) }

    // Keep the existing Notes text intact when an MCP/AI caller contributes a follow-up. Separate
    // populated entries by one blank line without reformatting either side.
    fun appendPrefix(tabId: String, text: String) = appState.upAnn(tabId) { t ->
        t.copy(annotations = t.annotations.copy(prefix = appendSectionText(t.annotations.prefix, text)))
    }

    fun appendSuffix(tabId: String, text: String) = appState.upAnn(tabId) { t ->
        t.copy(annotations = t.annotations.copy(suffix = appendSectionText(t.annotations.suffix, text)))
    }

    fun setIssueDescription(tabId: String, v: String) =
        appState.upAnn(tabId) { t -> t.copy(annotations = t.annotations.copy(issueDescription = v)) }

    private fun appendSectionText(existing: String, addition: String): String = when {
        existing.isEmpty() -> addition
        existing.endsWith("\n\n") -> existing + addition
        existing.endsWith('\n') -> "$existing\n$addition"
        else -> "$existing\n\n$addition"
    }
}
