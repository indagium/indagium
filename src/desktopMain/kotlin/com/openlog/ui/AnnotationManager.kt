package com.openlog.ui

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.openlog.model.AddAnnRequest
import com.openlog.model.AnnBlock

// Extracted from AppState (Task 12 slice 5, mechanical — no behavior change): the annotation
// block-model mutations (add/update/remove/move/reorder a note or log-ref block, prefix/suffix/
// issue-description edits) and the "add annotation" dialog request they're launched from.
// Auto-export-on-change (autoExportAnnotations) and the broader note-file/fingerprinting
// machinery it depends on stay on AppState — upAnn (bumped to internal) is the shared choke
// point both this class and AppState.loadAnnotationsFrom route tabs-list writes through, so
// auto-export keeps firing on every annotation edit regardless of which class made it.
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
        return id
    }

    fun updateBlock(tabId: String, blockId: String, newText: String) = appState.upAnn(tabId) { t ->
        t.copy(
            annotations = t.annotations.copy(
                blocks = t.annotations.blocks.map { b ->
                    when {
                        b.id != blockId -> b
                        b is AnnBlock.Note -> b.copy(text = newText)
                        b is AnnBlock.LogRef -> b.copy(caption = newText)
                        else -> b
                    }
                },
            ),
        )
    }

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

    fun setIssueDescription(tabId: String, v: String) =
        appState.upAnn(tabId) { t -> t.copy(annotations = t.annotations.copy(issueDescription = v)) }
}
