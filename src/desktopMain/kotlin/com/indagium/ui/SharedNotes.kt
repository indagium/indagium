package com.indagium.ui

import com.indagium.model.AnnBlock
import com.indagium.model.Annotations
import com.indagium.model.LogTab
import com.indagium.model.SharedNoteClosedMember
import com.indagium.model.SharedNoteInfo
import com.indagium.model.resolveRows
import java.io.File
import java.util.UUID

// Operations on shared notes (see LogTab.sharedNote / AppState.sharedNoteHosts). A shared note is a note-only host LogTab
// kept OUTSIDE AppState.tabs; the tabs that write into it are its members, and LogTab.notesView says which note a tab's
// Notes tile shows. Every mutation here is one synchronized(stateLock) block that rewrites `tabs` and/or `sharedNoteHosts`
// directly (like the structural edits next to closeTabsById), so a background tail/load landing at the same instant
// cannot lose either write. Nothing here saves explicitly: App.kt's debounced autosave effect is keyed on both lists'
// persisted snapshots (sharedNote and notesView included), so each operation is picked up like any other edit.

internal const val DEFAULT_SHARED_NOTE_NAME = "Shared note"

private const val SHARED_NOTE_ID_PREFIX = "shared-"

// ── Helpers ──────────────────────────────────────────────────────────────────────────────────

private fun sharedNoteName(name: String): String = name.trim().ifBlank { DEFAULT_SHARED_NOTE_NAME }

/** The path of a tab opened from one plain file, normalized for comparison; null for no source or an archive entry ("zip!entry"). */
private fun plainSourcePathKey(sourcePath: String?): String? {
    if (sourcePath.isNullOrBlank() || '!' in sourcePath) return null
    return File(sourcePath).absoluteFile.normalize().path
}

/** Open ordinary tabs only: never a shared-note host (not in `tabs`) and never the New-tab launcher. Caller holds stateLock. */
private fun AppState.eligibleMemberIds(ids: List<String>): List<String> =
    ids.distinct().filter { id -> tabs.any { it.id == id && !it.isCaptureLauncher } }

private fun AppState.updateTabsLocked(ids: Set<String>, fn: (LogTab) -> LogTab) {
    if (ids.isEmpty()) return
    tabs = tabs.map { if (it.id in ids) fn(it) else it }
}

private fun AppState.replaceHostLocked(host: LogTab) {
    sharedNoteHosts = sharedNoteHosts.map { if (it.id == host.id) host else it }
}

/** The prefix a tab's private note starts with (what opening the file or "New note" seeds). */
private fun AppState.defaultNotePrefix(tab: LogTab): String =
    "${settings.annotationPrefixLabel.trim().ifBlank { "From" }} ${tab.filename}"

private fun Annotations.mapLogRefs(fn: (AnnBlock.LogRef) -> AnnBlock.LogRef): Annotations =
    copy(blocks = blocks.map { if (it is AnnBlock.LogRef) fn(it) else it })

private fun Annotations.retargetLogRefSource(from: String, to: String): Annotations =
    mapLogRefs { if (it.sourceTabId == from) it.copy(sourceTabId = to) else it }

/**
 * A private note's blocks as they must read inside a shared note: a row reference that points at the tab's own rows
 * (sourceTabId null) now names that tab as its source, with the rows frozen so the reference still renders if the tab is
 * unloaded or closed.
 */
private fun Annotations.ownedBy(tab: LogTab): Annotations = mapLogRefs { ref ->
    if (ref.sourceTabId != null) {
        ref
    } else {
        ref.copy(
            sourceTabId = tab.id,
            sourceFilename = tab.filename,
            sourceEntries = ref.sourceEntries ?: ref.resolveRows(tab).takeIf { it.isNotEmpty() },
        )
    }
}

/** The inverse of [ownedBy] for one tab: references to that tab's rows are its own again (frozen rows stay). */
private fun Annotations.returnedTo(tabId: String): Annotations = mapLogRefs { ref ->
    if (ref.sourceTabId == tabId) ref.copy(sourceTabId = null, sourceFilename = null) else ref
}

/** A host with [tab] added as an open member; a closed member for the same file is dropped and its row references follow the new tab id. */
private fun LogTab.withMember(tab: LogTab): LogTab {
    val info = sharedNote ?: return this
    val key = plainSourcePathKey(tab.sourcePath)
    val matched = key?.let { k -> info.closedMembers.filter { plainSourcePathKey(it.sourcePath) == k } }.orEmpty()
    val retargeted = matched.fold(annotations) { notes, closed -> notes.retargetLogRefSource(closed.tabId, tab.id) }
    return copy(
        annotations = retargeted,
        sharedNote = info.copy(
            memberTabIds = (info.memberTabIds + tab.id).distinct(),
            closedMembers = info.closedMembers - matched.toSet(),
        ),
    )
}

/** A host whose open member [tab] was closed: it moves to the closed members when its file can be found again, else it is just dropped. */
private fun LogTab.withMemberClosed(tab: LogTab): LogTab {
    val info = sharedNote ?: return this
    if (tab.id !in info.memberTabIds) return this
    val path = tab.sourcePath?.takeIf { plainSourcePathKey(it) != null }
    val closed = path?.let { SharedNoteClosedMember(tab.id, tab.filename, it) }
    return copy(
        sharedNote = info.copy(
            memberTabIds = info.memberTabIds - tab.id,
            closedMembers = info.closedMembers.filterNot { it.tabId == tab.id } + listOfNotNull(closed),
        ),
    )
}

// ── Queries ──────────────────────────────────────────────────────────────────────────────────

/** The shared notes this tab is an open member of, in creation order (Open ▾ "Shared notes"). */
internal fun AppState.sharedNotesFor(tabId: String): List<SharedNoteInfo> =
    sharedNoteHosts.mapNotNull { it.sharedNote }.filter { tabId in it.memberTabIds }

/** Every other shared note (Open ▾ "Other shared notes"): opening one adds the tab as a member. */
internal fun AppState.otherSharedNotes(tabId: String): List<SharedNoteInfo> =
    sharedNoteHosts.mapNotNull { it.sharedNote }.filter { tabId !in it.memberTabIds }

// ── Create / share ───────────────────────────────────────────────────────────────────────────

private fun newSharedNoteHost(name: String, members: List<String>): LogTab =
    sharedNoteHostTab(
        SharedNoteInfo(
            id = SHARED_NOTE_ID_PREFIX + UUID.randomUUID(),
            name = sharedNoteName(name),
            memberTabIds = members,
            createdAtMs = System.currentTimeMillis(),
        ),
    )

/**
 * Creates an empty shared note with [memberTabIds] (open ordinary tabs; others are ignored) and points every member's Notes
 * tile at it. Null when no member is valid. Returns the host id.
 */
internal fun AppState.createSharedNote(name: String, memberTabIds: List<String>): String? = synchronized(stateLock) {
    val members = eligibleMemberIds(memberTabIds)
    if (members.isEmpty()) return null
    val host = newSharedNoteHost(name, members)
    addSharedNoteHost(host)
    updateTabsLocked(members.toSet()) { it.copy(notesView = host.id) }
    host.id
}

/**
 * Turns [tabId]'s private note into a shared note: the host takes over its annotations and its pinned note file, and the
 * tab gets a fresh private note that exports under a new name (never over the shared file). [tabId] is always a member.
 * Null (nothing changed) when the tab is not an open ordinary tab or an overwrite prompt is pending for it.
 */
internal fun AppState.shareTabNote(tabId: String, name: String, memberTabIds: List<String>): String? = synchronized(stateLock) {
    val tab = tabs.firstOrNull { it.id == tabId && !it.isCaptureLauncher } ?: return null
    // An in-flight edit lives only on pendingNoteOverwrite.pendingTab: moving the note now would race its resolution.
    if (pendingNoteOverwrite?.tabId == tabId) return null
    val members = eligibleMemberIds(listOf(tabId) + memberTabIds)
    val host = newSharedNoteHost(name, members).let {
        it.copy(
            annotations = tab.annotations.ownedBy(tab),
            noteTargetName = tab.noteTargetName,
            recoveredNoteRows = tab.recoveredNoteRows,
        )
    }
    addSharedNoteHost(host)
    val notesDir = activeNotesDir()
    // Only a tab that had a pin has a shared file to stay away from; "taken" also covers a file that is not on disk yet.
    val freshPin = tab.noteTargetName?.let {
        nextFreeNoteTargetName(analysisNoteMarkdownName(tab.filename, tab.sourcePath), MAX_NOTE_TARGET_SUFFIX) { candidate ->
            candidate == host.noteTargetName || File(notesDir, candidate).exists() ||
                File(notesDir, "${candidate.removeSuffix(".md")}.ann").exists()
        }
    }
    updateTabsLocked(members.toSet()) { member ->
        if (member.id == tabId) {
            member.copy(
                annotations = Annotations(prefix = defaultNotePrefix(member)),
                noteTargetName = freshPin,
                recoveredNoteRows = emptyMap(),
                notesView = host.id,
            )
        } else {
            member.copy(notesView = host.id)
        }
    }
    host.id
}

// ── Members, rename, stop sharing, delete ────────────────────────────────────────────────────

/**
 * Replaces the open member list (open ordinary tabs; others are ignored). A removed member that was showing this note goes
 * back to its own; an added member keeps what it shows (it reaches the note through Open ▾); closed members are untouched.
 * False when the note does not exist.
 */
internal fun AppState.setSharedNoteMembers(hostId: String, memberTabIds: List<String>): Boolean = synchronized(stateLock) {
    val host = sharedNoteHost(hostId) ?: return false
    val info = host.sharedNote ?: return false
    val members = eligibleMemberIds(memberTabIds)
    val removed = info.memberTabIds.filter { it !in members }.toSet()
    var updated = host.copy(sharedNote = info.copy(memberTabIds = info.memberTabIds.filter { it in members }))
    members.filter { it !in info.memberTabIds }.forEach { id ->
        tabs.firstOrNull { it.id == id }?.let { updated = updated.withMember(it) }
    }
    replaceHostLocked(updated)
    updateTabsLocked(removed) { if (it.notesView == hostId) it.copy(notesView = null) else it }
    true
}

/** Renames the note only: the pinned export file keeps its name. False when the note does not exist. */
internal fun AppState.renameSharedNote(hostId: String, name: String): Boolean = synchronized(stateLock) {
    val host = sharedNoteHost(hostId) ?: return false
    val info = host.sharedNote ?: return false
    val newName = sharedNoteName(name)
    replaceHostLocked(host.copy(filename = newName, sharedNote = info.copy(name = newName)))
    true
}

/**
 * Ends sharing: the note becomes [intoTabId]'s private note (and its pinned note file) and the shared note is deleted
 * (see [deleteSharedNote]). [intoTabId] must be an open member. Returns false and changes nothing when it is not, when
 * its own private note holds anything and [replaceExisting] is false (the UI confirms, then asks again with true), or when
 * an overwrite prompt is pending for the note or the tab.
 */
internal fun AppState.stopSharing(hostId: String, intoTabId: String, replaceExisting: Boolean = false): Boolean =
    synchronized(stateLock) {
        val host = sharedNoteHost(hostId) ?: return false
        val tab = tabs.firstOrNull { it.id == intoTabId } ?: return false
        if (intoTabId !in host.sharedNote?.memberTabIds.orEmpty()) return false
        val pending = pendingNoteOverwrite
        if (pending != null && (pending.tabId == hostId || pending.tabId == intoTabId)) return false
        if (!replaceExisting && privateNoteHasContent(tab)) return false
        updateTabsLocked(setOf(intoTabId)) {
            it.copy(
                annotations = host.annotations.returnedTo(intoTabId),
                noteTargetName = host.noteTargetName ?: it.noteTargetName,
                recoveredNoteRows = host.recoveredNoteRows.ifEmpty { it.recoveredNoteRows },
            )
        }
        deleteSharedNote(hostId)
        true
    }

/** True when the tab's private note holds something worth confirming over: blocks, text, or a prefix other than the seeded one. */
private fun AppState.privateNoteHasContent(tab: LogTab): Boolean {
    val note = tab.annotations
    val seededPrefix = note.prefix.isBlank() || note.prefix == defaultNotePrefix(tab)
    return note.blocks.isNotEmpty() || !seededPrefix || note.suffix.isNotBlank() || note.issueDescription.isNotBlank()
}

/**
 * Deletes the shared note: every tab showing it goes back to its own note and an overwrite prompt aimed at it is dropped.
 * The exported .md/.ann files on disk are left alone. False when the note does not exist.
 */
internal fun AppState.deleteSharedNote(hostId: String): Boolean = synchronized(stateLock) {
    if (sharedNoteHost(hostId) == null) return false
    sharedNoteHosts = sharedNoteHosts.filterNot { it.id == hostId }
    tabs = tabs.map { if (it.notesView == hostId) it.copy(notesView = null) else it }
    if (pendingNoteOverwrite?.tabId == hostId) pendingNoteOverwrite = null
    true
}

/**
 * Switches which note [tabId]'s Notes tile shows: null for the tab's own, else a shared note. Showing a note the tab is not
 * a member of adds it as a member. False when the tab is not an open ordinary tab or the note does not exist.
 */
internal fun AppState.setNotesView(tabId: String, hostId: String?): Boolean = synchronized(stateLock) {
    val tab = tabs.firstOrNull { it.id == tabId && !it.isCaptureLauncher } ?: return false
    if (hostId != null) {
        val host = sharedNoteHost(hostId) ?: return false
        if (tabId !in host.sharedNote?.memberTabIds.orEmpty()) replaceHostLocked(host.withMember(tab))
    }
    updateTabsLocked(setOf(tabId)) { it.copy(notesView = hostId) }
    true
}

// ── Member lifecycle ─────────────────────────────────────────────────────────────────────────

/**
 * Called by closeTabsById (the one place tabs leave `tabs`, plus a failed capture start) with the tabs about to go: each
 * one that is an open member becomes a closed member of its notes. Caller holds stateLock.
 */
internal fun AppState.detachSharedNoteMembersLocked(closing: List<LogTab>) {
    if (closing.isEmpty() || sharedNoteHosts.isEmpty()) return
    val closingIds = closing.mapTo(HashSet()) { it.id }
    // A closing member whose file is still open in another tab (a reparse opens the new tab before closing the original)
    // hands its membership to that tab instead of becoming a closed member.
    val successors = closing.associate { tab ->
        val key = plainSourcePathKey(tab.sourcePath)
        tab.id to key?.let { k -> tabs.firstOrNull { it.id !in closingIds && plainSourcePathKey(it.sourcePath) == k } }
    }
    val viewHandOver = HashMap<String, String>()
    val updated = sharedNoteHosts.map { host ->
        closing.fold(host) { acc, tab ->
            val wasMember = acc.sharedNote?.memberTabIds?.contains(tab.id) == true
            val closed = acc.withMemberClosed(tab)
            val successor = successors[tab.id]
            if (!wasMember || successor == null || closed.sharedNote?.memberTabIds?.contains(successor.id) == true) {
                closed
            } else {
                if (tab.notesView == host.id && successor.notesView == null) viewHandOver[successor.id] = host.id
                closed.withMember(successor)
            }
        }
    }
    if (updated.zip(sharedNoteHosts).any { (after, before) -> after !== before }) sharedNoteHosts = updated
    if (viewHandOver.isNotEmpty()) updateTabsLocked(viewHandOver.keys) { it.copy(notesView = viewHandOver[it.id]) }
}

/**
 * Called by publishOpenedTab (every newly opened tab passes through it) before the tab is added: when its file is a closed
 * member of one or more shared notes it joins them all, the closed entries go, row references that named the old tab id
 * follow the new one, and it shows the first of those notes. Returns the tab to publish. Session restore keeps tab ids, so
 * restored tabs are already members and never come through here. Caller holds stateLock.
 */
internal fun AppState.attachReopenedTabToSharedNotesLocked(opened: LogTab): LogTab {
    val key = plainSourcePathKey(opened.sourcePath)
    if (key == null || sharedNoteHosts.isEmpty()) return opened
    var firstHostId: String? = null
    val updated = sharedNoteHosts.map { host ->
        val info = host.sharedNote
        val reattaches = info != null && opened.id !in info.memberTabIds &&
            info.closedMembers.any { plainSourcePathKey(it.sourcePath) == key }
        if (reattaches) {
            if (firstHostId == null) firstHostId = host.id
            host.withMember(opened)
        } else {
            host
        }
    }
    if (firstHostId == null) return opened
    sharedNoteHosts = updated
    return if (opened.notesView == null) opened.copy(notesView = firstHostId) else opened
}

/**
 * Opens a closed member's file again through the normal open path (no dialog); the open hook above re-attaches it. A file
 * that is already open is attached and shown instead. A missing or unknown file is reported through the open-error notice.
 * Returns whether the file was opened (or was already open).
 */
internal fun AppState.reopenSharedNoteMember(hostId: String, closedTabId: String): Boolean {
    val closed = sharedNoteHost(hostId)?.sharedNote?.closedMembers?.firstOrNull { it.tabId == closedTabId } ?: return false
    val path = closed.sourcePath?.takeIf { plainSourcePathKey(it) != null }
    if (path == null) {
        showOpenError("Could not reopen ${closed.filename}", null, "The shared note has no file recorded for this tab.")
        return false
    }
    val file = File(path)
    if (!file.isFile) {
        showOpenError("Could not open file", path, "The file does not exist or is not readable.")
        return false
    }
    val key = plainSourcePathKey(path)
    val alreadyOpen = synchronized(stateLock) {
        val existing = tabs.firstOrNull { plainSourcePathKey(it.sourcePath) == key } ?: return@synchronized null
        val attached = attachReopenedTabToSharedNotesLocked(existing)
        if (attached != existing) updateTabsLocked(setOf(existing.id)) { attached }
        existing.id
    }
    if (alreadyOpen != null) {
        activateTab(alreadyOpen)
        return true
    }
    return openPath(file) != null
}
