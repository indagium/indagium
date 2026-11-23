package com.indagium.ui

import com.indagium.model.AnnBlock
import com.indagium.model.Annotations
import com.indagium.model.LogEntry
import com.indagium.model.LogTab
import com.indagium.model.SharedNoteClosedSource
import com.indagium.model.SharedNoteInfo
import com.indagium.model.TabContentState
import com.indagium.model.resolveRows
import com.indagium.utils.logFormatProfileFromJson
import com.indagium.utils.recordFingerprint
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.asContextElement
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.io.File
import java.util.UUID
import kotlin.math.abs

// Operations on shared notes (see LogTab.sharedNote / AppState.sharedNoteHosts). A shared note is a note-only host LogTab
// kept OUTSIDE AppState.tabs; the tabs that write into it are its members, and LogTab.notesView says which note a tab's
// Notes tile shows. Every mutation here is one synchronized(stateLock) block that rewrites `tabs` and/or `sharedNoteHosts`
// directly (like the structural edits next to closeTabsById), so a background tail/load landing at the same instant
// cannot lose either write. Nothing here saves explicitly: App.kt's debounced autosave effect is keyed on both lists'
// persisted snapshots (sharedNote and notesView included), so each operation is picked up like any other edit.

internal const val DEFAULT_SHARED_NOTE_NAME = "Shared note"

internal const val SHARED_NOTE_ID_PREFIX = "shared-"

private val sharedNoteWriteAuthor = ThreadLocal<String>()

internal suspend fun <T> withSharedNoteWriteAuthor(tabId: String, write: suspend () -> T): T =
    withContext(sharedNoteWriteAuthor.asContextElement(tabId)) { write() }

internal class SharedNoteWriteDenied(hostId: String) :
    IllegalStateException("Shared note $hostId is unavailable to the tab for this run.")

/** Check the live membership while holding stateLock, immediately before changing the note. */
internal fun LogTab.requireSharedNoteWriteAccess() {
    val author = sharedNoteWriteAuthor.get() ?: return
    val note = sharedNote ?: return
    if (author !in note.memberTabIds) throw SharedNoteWriteDenied(id)
}

// ── Helpers ──────────────────────────────────────────────────────────────────────────────────

/**
 * The name a shared note gets for [typed]: what the user typed, trimmed; a blank one becomes "Shared note", then "Shared note 2",
 * "Shared note 3"... among the other notes ([exceptHostId] is a note being renamed, which does not count against itself).
 */
internal fun AppState.sharedNoteNameFor(typed: String, exceptHostId: String? = null): String {
    val name = typed.trim()
    if (name.isNotEmpty()) return name
    val taken = sharedNoteHosts.filter { it.id != exceptHostId }.mapNotNull { it.sharedNote?.name }.toSet()
    if (DEFAULT_SHARED_NOTE_NAME !in taken) return DEFAULT_SHARED_NOTE_NAME
    var number = 2
    while ("$DEFAULT_SHARED_NOTE_NAME $number" in taken) number++
    return "$DEFAULT_SHARED_NOTE_NAME $number"
}

/** What a blank name would become right now: the share dialog shows it as the name field's placeholder. */
internal fun AppState.nextDefaultSharedNoteName(): String = sharedNoteNameFor("")

/** The text of the "Existing notes found" prompt: a shared note has no log file to have "never opened", so it only says another session wrote it. */
internal fun noteOverwriteMessage(targetName: String, sharedNote: Boolean): String =
    if (sharedNote) {
        "\"$targetName\" already has saved notes from another session. Nothing is being written to disk while this is open — " +
            "choose how to proceed."
    } else {
        "\"$targetName\" already has saved notes for this log, from a session that never opened this file. " +
            "Nothing is being written to disk while this is open — choose how to proceed."
    }

/** The path of a tab opened from one plain file, normalized for comparison; null for no source or an archive entry ("zip!entry"). */
private fun plainSourcePathKey(sourcePath: String?): String? {
    if (sourcePath.isNullOrBlank() || '!' in sourcePath) return null
    return File(sourcePath).absoluteFile.normalize().path
}

/** Older sessions have no profile snapshot, so their closed sources match by path alone. */
private fun SharedNoteClosedSource.matches(tab: LogTab): Boolean {
    if (tab.isCaptureLauncher) return false
    sourceInstanceId?.let { return it == tab.sourceInstanceId }
    val key = plainSourcePathKey(sourcePath) ?: return false
    return plainSourcePathKey(tab.sourcePath) == key &&
        (logFormatProfileSnapshot == null || logFormatProfileSnapshot == tab.logFormatProfileSnapshot)
}

private fun SharedNoteInfo.closedSource(tabId: String): SharedNoteClosedSource? =
    closedMembers.firstOrNull { it.tabId == tabId } ?: closedReferenceSources.firstOrNull { it.tabId == tabId }

/** The ids of the tabs whose rows [blocks] cite as another file's (a shared note's references, or a private note that stopped being shared). */
private fun List<AnnBlock>.citedSourceTabIds(): Set<String> =
    asSequence().filterIsInstance<AnnBlock.LogRef>().mapNotNullTo(HashSet()) { it.sourceTabId }

/** [added] joined to this list, replacing an entry of the same tab. */
private fun List<SharedNoteClosedSource>.withClosedSources(added: List<SharedNoteClosedSource>): List<SharedNoteClosedSource> =
    if (added.isEmpty()) this else filterNot { old -> added.any { it.tabId == old.tabId } } + added

/**
 * The closed source that [ownerId] recorded for the citations of [tabId]: [ownerId] is a shared note's host id, or the id of a
 * tab whose private note cites it ([LogTab.noteClosedSources]).
 */
private fun AppState.closedSourceOf(ownerId: String, tabId: String): SharedNoteClosedSource? =
    sharedNoteHost(ownerId)?.sharedNote?.closedSource(tabId)
        ?: tabs.firstOrNull { it.id == ownerId }?.noteClosedSources?.firstOrNull { it.tabId == tabId }

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

/** Rebinds row references and restores membership when a closed member returns. */
private fun LogTab.withReopenedSource(tab: LogTab, addMember: Boolean = false): LogTab {
    val info = sharedNote ?: return this
    val members = info.closedMembers.filter { it.matches(tab) }
    val sources = info.closedReferenceSources.filter { it.matches(tab) }
    if (!addMember && members.isEmpty() && sources.isEmpty()) return this
    val retargeted = (members + sources).fold(annotations) { notes, closed -> notes.retargetLogRefSource(closed.tabId, tab.id) }
    return copy(
        annotations = retargeted,
        sharedNote = info.copy(
            memberTabIds = if (addMember || members.isNotEmpty()) (info.memberTabIds + tab.id).distinct() else info.memberTabIds,
            closedMembers = info.closedMembers - members.toSet(),
            closedReferenceSources = info.closedReferenceSources - sources.toSet(),
        ),
    )
}

private fun LogTab.withMember(tab: LogTab): LogTab = withReopenedSource(tab, addMember = true)

/** What a note must remember to reopen this tab's file later; null when it has no plain file to open (an archive entry, a capture). */
private fun LogTab.asClosedSource(): SharedNoteClosedSource? =
    sourcePath?.takeIf { plainSourcePathKey(it) != null }?.let { SharedNoteClosedSource(id, filename, it, logFormatProfileSnapshot) }

/** Source identities for the note sidecar, including cited tabs that are still open at the time of export. */
internal fun AppState.noteSourcesForExport(owner: LogTab): List<SharedNoteClosedSource> {
    val cited = owner.annotations.blocks.citedSourceTabIds()
    if (cited.isEmpty()) return emptyList()
    val known = owner.sharedNote?.let { it.closedMembers + it.closedReferenceSources } ?: owner.noteClosedSources
    val stored = known.filter { it.tabId in cited }.distinctBy { it.tabId }
    val storedById = stored.associateBy { it.tabId }
    val open = tabs.mapNotNull { tab ->
        if (tab.isCaptureLauncher || tab.id !in cited || storedById[tab.id]?.matches(tab) == false) return@mapNotNull null
        tab.asClosedSource() ?: SharedNoteClosedSource(tab.id, tab.filename, null, sourceInstanceId = tab.sourceInstanceId)
    }
    return stored.withClosedSources(open)
}

private const val PENDING_CITATION_SOURCE_PREFIX = "citation-"

/** Restores a note's cross-file citations and rebinds any source already open. Caller holds stateLock. */
internal fun AppState.restoreNoteSourcesLocked(owner: LogTab, sources: List<SharedNoteClosedSource>): LogTab {
    val cited = owner.annotations.blocks.citedSourceTabIds()
    val known = sources.distinctBy { it.tabId }.associateBy { it.tabId }
    val destinations = HashMap<String, String>()
    val pending = ArrayList<SharedNoteClosedSource>()
    for (id in cited) {
        val source = known[id]
        val opened = source?.let {
            tabs.firstOrNull { tab -> tab.id == id && source.matches(tab) } ?: tabs.filter(source::matches).singleOrNull()
        }
        // An older sidecar may have no identity for this citation. Preserve the frozen rows but detach the unverified numeric ID.
        val destination = opened?.id ?: id.takeIf { it.startsWith(PENDING_CITATION_SOURCE_PREFIX) && tab(it) == null }
            ?: "$PENDING_CITATION_SOURCE_PREFIX${UUID.randomUUID()}"
        destinations[id] = destination
        if (opened == null && source != null) pending += source.copy(tabId = destination)
    }
    val annotations = owner.annotations.mapLogRefs { ref ->
        val destination = destinations[ref.sourceTabId]
        if (destination == null || destination == ref.sourceTabId) ref else ref.copy(sourceTabId = destination)
    }
    val info = owner.sharedNote
    return owner.copy(
        annotations = annotations,
        noteClosedSources = if (info == null) pending else emptyList(),
        sharedNote = info?.copy(closedReferenceSources = pending.filterNot { source -> info.closedMembers.any { it.tabId == source.tabId } }),
    )
}

/** Keeps reopen information for a closing member or a file cited by this note. */
private fun LogTab.withSourceClosed(tab: LogTab): LogTab {
    val info = sharedNote ?: return this
    val wasMember = tab.id in info.memberTabIds
    if (!wasMember && annotations.blocks.none { it is AnnBlock.LogRef && it.sourceTabId == tab.id }) return this
    val closed = tab.asClosedSource()
    return copy(
        sharedNote = if (wasMember) info.copy(
            memberTabIds = info.memberTabIds - tab.id,
            closedMembers = info.closedMembers.filterNot { it.tabId == tab.id } + listOfNotNull(closed),
        ) else info.copy(
            closedReferenceSources = info.closedReferenceSources.filterNot { it.tabId == tab.id } + listOfNotNull(closed),
        ),
    )
}

// ── Which note an action on a tab edits ─────────────────────────────────────────────────────

/**
 * The note an action on behalf of "the tab the user is in" reads and writes: the shared-note host that tab's Notes tile shows
 * ([LogTab.notesView]) when that note still exists, else the tab itself (its own private note). Every UI entry point that adds
 * or edits note content (row references, text, image and video-frame blocks, issue notes, prefix/suffix, copy/save/export of the
 * tab's notes, the case library's seed) asks this and hands the result to the note functions in place of the tab id. MCP tools
 * address a note explicitly and do not use it.
 */
internal fun AppState.notesTargetFor(tabId: String): String {
    val view = tab(tabId)?.notesView ?: return tabId
    return if (sharedNoteHosts.any { it.id == view }) view else tabId
}

/** The note [tab]'s Notes tile shows: the tab itself unless it views a shared note, then that note's host. */
internal fun AppState.shownNoteOf(tab: LogTab): LogTab {
    val target = notesTargetFor(tab.id)
    return if (target == tab.id) tab else sharedNoteHost(target) ?: tab
}

/**
 * What a row reference says about where its rows come from (the source file, plus " · " and the section's short title when it was
 * captured from a section), or null for rows of the note's own tab. The section's title comes from the source tab while it is open.
 */
internal fun AppState.logRefSourceLabel(block: AnnBlock.LogRef): String? =
    com.indagium.utils.logRefSourceLabel(block, block.sourceTabId?.let { tab(it) }?.logSections.orEmpty())

// ── Queries ──────────────────────────────────────────────────────────────────────────────────

/** The shared notes this tab is an open member of, in creation order (Open ▾ "Shared notes"). */
internal fun AppState.sharedNotesFor(tabId: String): List<SharedNoteInfo> =
    sharedNoteHosts.mapNotNull { it.sharedNote }.filter { tabId in it.memberTabIds }

/** Every other shared note (Open ▾ "Other shared notes"): opening one adds the tab as a member. */
internal fun AppState.otherSharedNotes(tabId: String): List<SharedNoteInfo> =
    sharedNoteHosts.mapNotNull { it.sharedNote }.filter { tabId !in it.memberTabIds }

// ── Create / share ───────────────────────────────────────────────────────────────────────────

/**
 * A new, empty host. It pins its export file right away to a name nothing else owns (a shared note has no source file to tell its
 * note file from another's, so the unpinned default would be the same "<name>_analysis.md" for two notes of one name). Caller holds stateLock.
 */
private fun AppState.newSharedNoteHost(name: String, members: List<String>): LogTab {
    val host = sharedNoteHostTab(
        SharedNoteInfo(
            id = SHARED_NOTE_ID_PREFIX + UUID.randomUUID(),
            name = sharedNoteNameFor(name),
            memberTabIds = members,
            createdAtMs = System.currentTimeMillis(),
        ),
    )
    return host.copy(noteTargetName = freeNoteTargetName(analysisNoteMarkdownName(host.filename.replace(".", "_"))))
}

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
internal fun AppState.shareTabNote(tabId: String, name: String, memberTabIds: List<String>): String? {
    val moved = shareTabNoteLocked(tabId, name, memberTabIds) ?: return null
    // The library's attachments follow the blocks to the host, after the lock: the library writes to disk.
    seq3Sessions.retargetLibraryAttachments(tabId, moved.noteTabId, moved.blockIds)
    return moved.noteTabId
}

/** The tab that holds the note after a move and the ids of the blocks that moved into it. */
private class MovedNote(
    val noteTabId: String,
    val blockIds: List<String>,
    /** Blocks of the destination's own note that the move replaced wholesale (Stop sharing with replaceExisting). */
    val replacedBlockIds: List<String> = emptyList(),
)

private fun AppState.shareTabNoteLocked(tabId: String, name: String, memberTabIds: List<String>): MovedNote? = synchronized(stateLock) {
    val tab = tabs.firstOrNull { it.id == tabId && !it.isCaptureLauncher } ?: return null
    // An in-flight edit lives only on pendingNoteOverwrite.pendingTab: moving the note now would race its resolution.
    if (pendingNoteOverwrite?.tabId == tabId) return null
    val members = eligibleMemberIds(listOf(tabId) + memberTabIds)
    val annotations = tab.annotations.ownedBy(tab)
    val sharedBlocks = seq3Sessions.stampSharedDiagramSources(tab, annotations.blocks)
    val host = newSharedNoteHost(name, members).let {
        it.copy(
            annotations = annotations.copy(blocks = sharedBlocks),
            // The tab's pinned file goes with its note; a tab that had none leaves the host the free pin it was made with.
            noteTargetName = tab.noteTargetName ?: it.noteTargetName,
            recoveredNoteRows = tab.recoveredNoteRows,
            // The closed logs the note cites go with it, so its citations can still reopen them (stopSharing hands them back).
            sharedNote = it.sharedNote?.let { info ->
                info.copy(closedReferenceSources = info.closedReferenceSources.withClosedSources(tab.noteClosedSources))
            },
        )
    }
    addSharedNoteHost(host)
    // Only a tab that had a pin has a shared file to stay away from; "taken" also covers a file that is not on disk yet.
    val freshPin = tab.noteTargetName?.let { freeNoteTargetName(analysisNoteMarkdownName(tab.filename, tab.sourcePath)) }
    updateTabsLocked(members.toSet()) { member ->
        if (member.id == tabId) {
            member.copy(
                annotations = Annotations(prefix = defaultNotePrefix(member)),
                noteTargetName = freshPin,
                recoveredNoteRows = emptyMap(),
                noteClosedSources = emptyList(),
                notesView = host.id,
            )
        } else {
            member.copy(notesView = host.id)
        }
    }
    seq3Sessions.moveNoteOwner(tabId, host.id, host.annotations.blocks)
    MovedNote(host.id, host.annotations.blocks.map { it.id })
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
    val newName = sharedNoteNameFor(name, exceptHostId = hostId)
    replaceHostLocked(host.copy(filename = newName, sharedNote = info.copy(name = newName)))
    true
}

/**
 * Ends sharing: the note becomes [intoTabId]'s private note (and its pinned note file) and the shared note is deleted
 * (see [deleteSharedNote]). [intoTabId] must be an open member. Returns false and changes nothing when it is not, when
 * its own private note holds anything and [replaceExisting] is false (the UI confirms, then asks again with true), or when
 * an overwrite prompt is pending for the note or the tab.
 */
internal fun AppState.stopSharing(hostId: String, intoTabId: String, replaceExisting: Boolean = false): Boolean {
    val moved = stopSharingLocked(hostId, intoTabId, replaceExisting) ?: return false
    // The library's attachments follow the blocks to the recipient, after the lock: the library writes to disk.
    seq3Sessions.retargetLibraryAttachments(hostId, intoTabId, moved.blockIds)
    // The recipient's own blocks that this replaced are gone: their attachments must not keep the library items "Attached".
    seq3Sessions.releaseLibraryAttachments(intoTabId, moved.replacedBlockIds)
    return true
}

private fun AppState.stopSharingLocked(hostId: String, intoTabId: String, replaceExisting: Boolean): MovedNote? =
    synchronized(stateLock) {
        val host = sharedNoteHost(hostId) ?: return null
        val tab = tabs.firstOrNull { it.id == intoTabId } ?: return null
        if (intoTabId !in host.sharedNote?.memberTabIds.orEmpty()) return null
        val pending = pendingNoteOverwrite
        if (pending != null && (pending.tabId == hostId || pending.tabId == intoTabId)) return null
        if (!replaceExisting && privateNoteHasContent(tab)) return null
        // The host's record of closed logs goes with the citations: without it the transferred note could no longer reopen them.
        val cited = host.annotations.blocks.citedSourceTabIds()
        val carriedSources = host.sharedNote?.let { (it.closedMembers + it.closedReferenceSources) }.orEmpty()
            .filter { it.tabId in cited }
            .distinctBy { it.tabId }
        // The diagrams record their log before the shared note (their evidence) is gone, in the same write as the move.
        val returnedBlocks = seq3Sessions.stampStoppedDiagramSources(host, tab, host.annotations.blocks)
        val recipient = tab.copy(
            annotations = host.annotations.copy(blocks = returnedBlocks).returnedTo(intoTabId),
            noteTargetName = stoppedSharedNotePin(host, tab),
            recoveredNoteRows = host.recoveredNoteRows.ifEmpty { tab.recoveredNoteRows },
            noteClosedSources = carriedSources,
        )
        updateTabsLocked(setOf(intoTabId)) { recipient }
        seq3Sessions.moveNoteOwner(hostId, intoTabId, recipient.annotations.blocks)
        // Not deleteSharedNote: the host's blocks moved (their attachments are retargeted), so none is released here.
        deleteSharedNoteLocked(hostId)
        persistStoppedSharedNote(host, recipient)
        val movedIds = recipient.annotations.blocks.mapTo(HashSet()) { it.id }
        MovedNote(intoTabId, movedIds.toList(), tab.annotations.blocks.map { it.id }.filter { it !in movedIds })
    }

/** True when the tab's private note holds something worth confirming over: blocks, text, or a prefix other than the seeded one. */
internal fun AppState.privateNoteHasContent(tab: LogTab): Boolean {
    val note = tab.annotations
    val seededPrefix = note.prefix.isBlank() || note.prefix == defaultNotePrefix(tab)
    return note.blocks.isNotEmpty() || !seededPrefix || note.suffix.isNotBlank() || note.issueDescription.isNotBlank()
}

/**
 * Deletes the shared note: every tab showing it goes back to its own note and an overwrite prompt aimed at it is dropped.
 * The exported .md/.ann files on disk are left alone. False when the note does not exist.
 */
internal fun AppState.deleteSharedNote(hostId: String): Boolean {
    val blockIds = deleteSharedNoteLocked(hostId) ?: return false
    // The deleted blocks' library attachments go with them, after the lock: the library writes to disk.
    seq3Sessions.releaseLibraryAttachments(hostId, blockIds)
    return true
}

/** The deletion itself, for a caller that already holds [AppState.stateLock]; the ids of the deleted blocks, or null when there is no such note. */
private fun AppState.deleteSharedNoteLocked(hostId: String): List<String>? = synchronized(stateLock) {
    val host = sharedNoteHost(hostId) ?: return null
    sharedNoteHosts = sharedNoteHosts.filterNot { it.id == hostId }
    tabs = tabs.map { if (it.notesView == hostId) it.copy(notesView = null) else it }
    if (pendingNoteOverwrite?.tabId == hostId) pendingNoteOverwrite = null
    seq3Sessions.noteDeleted(hostId)
    host.annotations.blocks.map { it.id }
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
 * Records closing members and cited files. When an equivalent source is already open, the
 * references follow it immediately. Caller holds stateLock.
 */
internal fun AppState.detachSharedNoteMembersLocked(closing: List<LogTab>) {
    if (closing.isEmpty()) return
    val closingIds = closing.mapTo(HashSet()) { it.id }
    // A closing source can hand off to another tab showing the same file with the same profile.
    val successors = closing.associate { tab ->
        val key = plainSourcePathKey(tab.sourcePath)
        tab.id to key?.let { k ->
            tabs.firstOrNull {
                it.id !in closingIds && plainSourcePathKey(it.sourcePath) == k &&
                    (tab.logFormatProfileSnapshot == null || it.logFormatProfileSnapshot == tab.logFormatProfileSnapshot)
            }
        }
    }
    recordPrivateNoteClosedSourcesLocked(closing, closingIds, successors)
    if (sharedNoteHosts.isEmpty()) return
    val viewHandOver = HashMap<String, String>()
    val updated = sharedNoteHosts.map { host ->
        closing.fold(host) { acc, tab ->
            val wasMember = acc.sharedNote?.memberTabIds?.contains(tab.id) == true
            val closed = acc.withSourceClosed(tab)
            val successor = successors[tab.id]
            if (closed === acc || successor == null) {
                closed
            } else {
                if (wasMember && tab.notesView == host.id && successor.notesView == null) viewHandOver[successor.id] = host.id
                closed.withReopenedSource(successor, addMember = wasMember)
            }
        }
    }
    if (updated.zip(sharedNoteHosts).any { (after, before) -> after !== before }) sharedNoteHosts = updated
    if (viewHandOver.isNotEmpty()) updateTabsLocked(viewHandOver.keys) { it.copy(notesView = viewHandOver[it.id]) }
}

/**
 * A private note that cites a closing tab (a note that stopped being shared keeps its cross-file references) either follows an
 * equivalent open tab or records how to reopen the file ([LogTab.noteClosedSources]). Caller holds stateLock.
 */
private fun AppState.recordPrivateNoteClosedSourcesLocked(
    closing: List<LogTab>,
    closingIds: Set<String>,
    successors: Map<String, LogTab?>,
) {
    val closingById = closing.associateBy { it.id }
    var changed = false
    val updated = tabs.map { owner ->
        val cited = if (owner.id in closingIds) emptyList() else owner.annotations.blocks.citedSourceTabIds().filter { it in closingById }
        if (cited.isEmpty()) return@map owner
        changed = true
        cited.fold(owner) { acc, id ->
            val successor = successors[id]
            val closed = closingById.getValue(id).asClosedSource()
            when {
                successor != null -> acc.copy(annotations = acc.annotations.retargetLogRefSource(id, successor.id))
                closed != null -> acc.copy(noteClosedSources = acc.noteClosedSources.withClosedSources(listOf(closed)))
                else -> acc
            }
        }
    }
    if (changed) tabs = updated
}

/**
 * Rebinds closed references when their source opens. A returning member also rejoins its notes
 * and shows the first one. Returns the tab to publish. Caller holds stateLock.
 */
internal fun AppState.attachReopenedTabToSharedNotesLocked(opened: LogTab): LogTab {
    if (plainSourcePathKey(opened.sourcePath) == null) return opened
    rebindPrivateNotesToReopenedTabLocked(opened)
    if (sharedNoteHosts.isEmpty()) return opened
    var firstHostId: String? = null
    val updated = sharedNoteHosts.map { host ->
        if (firstHostId == null && host.sharedNote?.closedMembers?.any { it.matches(opened) } == true) firstHostId = host.id
        host.withReopenedSource(opened)
    }
    if (updated.zip(sharedNoteHosts).any { (after, before) -> after !== before }) sharedNoteHosts = updated
    return if (opened.notesView == null && firstHostId != null) opened.copy(notesView = firstHostId) else opened
}

/**
 * Every open tab whose private note cites a closed file that [opened] is again ([LogTab.noteClosedSources]) points those
 * citations at [opened] and forgets the entry. Caller holds stateLock.
 */
private fun AppState.rebindPrivateNotesToReopenedTabLocked(opened: LogTab) {
    if (tabs.none { owner -> owner.noteClosedSources.any { it.matches(opened) } }) return
    tabs = tabs.map { owner ->
        val reopened = owner.noteClosedSources.filter { it.matches(opened) }
        if (reopened.isEmpty()) {
            owner
        } else {
            owner.copy(
                annotations = reopened.fold(owner.annotations) { notes, closed -> notes.retargetLogRefSource(closed.tabId, opened.id) },
                noteClosedSources = owner.noteClosedSources - reopened.toSet(),
            )
        }
    }
}

/**
 * Opens a closed member's file again through the normal open path (no dialog); the open hook above re-attaches it. A file
 * that is already open is attached and shown instead. A missing or unknown file is reported through the open-error notice.
 * Returns whether the file was opened (or was already open).
 */
internal fun AppState.reopenSharedNoteMember(hostId: String, closedTabId: String): Boolean {
    val closed = sharedNoteHost(hostId)?.sharedNote?.closedMembers?.firstOrNull { it.tabId == closedTabId } ?: return false
    return reopenClosedSource(closed) != null
}

private fun AppState.reopenClosedSource(closed: SharedNoteClosedSource): String? {
    val path = closed.sourcePath?.takeIf { plainSourcePathKey(it) != null }
    if (path == null) {
        showOpenError("Could not reopen ${closed.filename}", null, "The shared note has no file recorded for this tab.")
        return null
    }
    val file = File(path)
    if (!file.isFile) {
        showOpenError("Could not open file", path, "The file does not exist or is not readable.")
        return null
    }
    val alreadyOpen = synchronized(stateLock) {
        val existing = tabs.firstOrNull { closed.matches(it) } ?: return@synchronized null
        val attached = attachReopenedTabToSharedNotesLocked(existing)
        if (attached != existing) updateTabsLocked(setOf(existing.id)) { attached }
        existing.id
    }
    if (alreadyOpen != null) {
        activateTab(alreadyOpen)
        return alreadyOpen
    }
    val snapshot = closed.logFormatProfileSnapshot ?: return openPath(file)
    val profile = logFormatProfileFromJson(snapshot)
    return if (profile == null) {
        showOpenError("Could not reopen ${closed.filename}", path, "The saved log format profile is invalid.")
        null
    } else {
        openFileWithLogFormatProfile(file, profile)
    }
}

// ── Go to rows ───────────────────────────────────────────────────────────────────────────────

/** How a "Go to rows" request ended (see [goToLogRefRows]). */
internal sealed interface GoToRowsResult {
    /** The source tab is shown with [rowIds] selected and revealed; [missing] frozen rows could not be found in it any more. */
    data class Navigated(val tabId: String, val rowIds: List<Int>, val missing: Int = 0) : GoToRowsResult

    /**
     * The source was saved by [ownerId]'s note (a shared note's host id, or the id of the tab whose private note cites it): ask the
     * user, then [goToLogRefRowsAfterReopen].
     */
    data class NeedsReopen(val ownerId: String, val closedTabId: String) : GoToRowsResult

    /** The source tab is open but none of the rows (or the section) could be found in it; nothing was selected. */
    data object RowsNotFound : GoToRowsResult

    /** The reference names no source tab that is open or can be reopened. */
    data object NoSource : GoToRowsResult
}

private const val REOPEN_WAIT_MS = 60_000L
private const val REOPEN_POLL_MS = 25L

// Older frozen rows can omit the process and thread fields, which the sidecar decoder represents as zero.
private fun LogEntry.sameRowContent(saved: LogEntry): Boolean =
    ts == saved.ts && msg == saved.msg && tag == saved.tag && level == saved.level &&
        (saved.pid == 0 || pid == saved.pid) && (saved.tid == 0 || tid == saved.tid) &&
        (saved.rawRecord == null || rawRecord == saved.rawRecord)

private fun resolveSelectedRecordId(block: AnnBlock.LogRef, rows: List<LogEntry>, rmap: Map<Int, LogEntry>, fingerprint: String): Int? {
    val id = block.logIds.singleOrNull() ?: return null
    val saved = block.sourceEntries?.singleOrNull()

    fun matches(row: LogEntry): Boolean =
        (saved == null || (row.ts == saved.ts && row.tag == saved.tag)) && row.rawRecord?.recordFingerprint() == fingerprint

    if (rmap[id]?.let(::matches) == true) return id
    return rows.asSequence().filter(::matches).minByOrNull { abs(it.id - id) }?.id
}

/** Frozen rows of one content that no longer sit under their saved id, and the ids of the rows that now carry that content. */
private class LostRowGroup(val saved: LogEntry, val targetIds: List<Int>) {
    val candidates = ArrayList<Int>()
}

/**
 * The ids in [rows] that stand for [block]'s rows. A saved id is kept when the row now under it is the frozen one (same time,
 * message, tag, severity, and process, thread and raw record when available); a changed id is looked up by content. Rows that are
 * already matched at their saved id are reserved, and the changed ids take distinct rows (see [assignLostRows]): identical
 * citations never collapse onto one row. A reference without frozen rows keeps the ids that still exist. One pass over [rows]
 * serves every changed id.
 */
internal fun resolveLogRefRowIds(block: AnnBlock.LogRef, rows: List<LogEntry>, rmap: Map<Int, LogEntry>): List<Int> {
    block.sourceRecordFingerprint?.let { return listOfNotNull(resolveSelectedRecordId(block, rows, rmap, it)) }
    val frozen = block.sourceEntries?.associateBy { it.id }.orEmpty()
    val found = LinkedHashSet<Int>()
    val lost = LinkedHashMap<Int, LogEntry>()
    for (id in block.logIds) {
        val saved = frozen[id]
        when {
            saved == null -> if (id in rmap) found += id
            rmap[id]?.sameRowContent(saved) == true -> found += id
            else -> lost[id] = saved
        }
    }
    if (lost.isNotEmpty()) {
        val groups = lost.values
            .groupBy { listOf(it.ts, it.msg, it.tag, it.level, it.pid, it.tid, it.rawRecord) }
            .values.map { same -> LostRowGroup(same.first(), same.map(LogEntry::id).sorted()) }
        val byMessage = groups.groupBy { it.saved.msg }
        for (row in rows) {
            if (row.id in found) continue
            byMessage[row.msg]?.forEach { group -> if (row.sameRowContent(group.saved)) group.candidates += row.id }
        }
        assignLostRows(groups, found)
    }
    return found.sorted()
}

/**
 * Gives every lost saved id its own row. Groups of different content never compete for a row. Inside a group the ids, in ascending
 * order, take the closest unused candidate that keeps the order (a row after the one the previous id took) while leaving enough
 * rows after it for the ids still to come; when no ordered choice exists they take the closest unused row at all. [taken] holds the
 * reserved rows and receives the chosen ones.
 */
private fun assignLostRows(groups: List<LostRowGroup>, taken: MutableSet<Int>) {
    for (group in groups.sortedBy { it.targetIds.first() }) {
        val rows = group.candidates.sorted()
        var previous = -1
        group.targetIds.forEachIndexed { index, target ->
            val stillToCome = group.targetIds.size - index - 1
            val chosen = closestUnusedRow(rows, target, previous + 1, rows.size - 1 - stillToCome, taken)
                ?: closestUnusedRow(rows, target, 0, rows.lastIndex, taken)
            if (chosen != null) {
                taken += rows[chosen]
                previous = chosen
            }
        }
    }
}

/** The index in [from]..[to] of [rows] (ascending ids) whose id is closest to [target] and not in [taken]; the earlier row wins a tie. */
private fun closestUnusedRow(rows: List<Int>, target: Int, from: Int, to: Int, taken: Set<Int>): Int? {
    if (from > to) return null
    var low = from
    var high = to + 1
    while (low < high) {
        val middle = (low + high) ushr 1
        if (rows[middle] < target) low = middle + 1 else high = middle
    }
    var right = low
    var left = low - 1
    while (right <= to && rows[right] in taken) right++
    while (left >= from && rows[left] in taken) left--
    val hasRight = right <= to
    val hasLeft = left >= from
    return when {
        !hasRight && !hasLeft -> null
        !hasLeft -> right
        !hasRight -> left
        rows[right] - target < target - rows[left] -> right
        else -> left
    }
}

/**
 * Offers to reopen a source recorded by a note: the note being viewed first (the shared note shown, else the viewing tab's own
 * private note), then any other shared note.
 */
private fun AppState.reopenOfferFor(sourceTabId: String, viewingTabId: String, viewedHostId: String?): GoToRowsResult {
    val owners = listOf(viewedHostId ?: viewingTabId) + sharedNoteHosts.map { it.id }
    val owner = owners.firstOrNull { plainSourcePathKey(closedSourceOf(it, sourceTabId)?.sourcePath) != null }
    return owner?.let { GoToRowsResult.NeedsReopen(it, sourceTabId) } ?: GoToRowsResult.NoSource
}

/**
 * The loaded source tab showing [sectionId] (when it has that section; a tab without it is taken as it is, its rows are looked up
 * by content). Loads the tab first when its rows are not resident and, for another section, waits for the switch to publish.
 * Null when the tab cannot be loaded or the switch did not complete.
 */
private suspend fun AppState.loadedSourceShowing(sourceTabId: String, sectionId: String?): LogTab? {
    val loaded = awaitTabLoaded(sourceTabId)?.takeIf { tab(it.id) != null } ?: return null
    if (sectionId == null || loaded.selectedLogSectionId == sectionId || loaded.logSections.none { it.id == sectionId }) return loaded
    return if (selectLogSectionAndAwait(sourceTabId, sectionId)) tab(sourceTabId) else null
}

/**
 * Shows [block]'s rows in their source tab: activates the tab (loading it first when its rows are not resident), switches to the
 * section the rows were captured from and waits for that switch to publish, checks every saved id against the frozen rows (looking
 * a row up by content when the id changed), then selects and reveals what was found. Suspends; Phase 4 launches it with
 * [launchGoToLogRefRows].
 *
 * The source tab is the block's [AnnBlock.LogRef.sourceTabId], or, for a private note, the tab [viewingTabId] itself (which owns
 * the note); a shared note's row reference with no source tab has [GoToRowsResult.NoSource]. A source tab that is not open but
 * has a saved file in a note (a shared note, or the viewing tab's own private note) gives [GoToRowsResult.NeedsReopen].
 */
internal suspend fun AppState.goToLogRefRows(
    block: AnnBlock.LogRef,
    viewingTabId: String,
    // A test seam: runs between resolving the ids and applying them, where an unload or a section switch can land.
    afterResolve: () -> Unit = {},
): GoToRowsResult {
    val viewedHostId = notesTargetFor(viewingTabId).takeIf { it != viewingTabId }
    val sourceTabId = block.sourceTabId ?: viewingTabId.takeIf { viewedHostId == null } ?: return GoToRowsResult.NoSource
    val savedSource = closedSourceOf(viewedHostId ?: viewingTabId, sourceTabId)
    val liveSource = tab(sourceTabId)
    if (liveSource == null || savedSource?.matches(liveSource) == false) return reopenOfferFor(sourceTabId, viewingTabId, viewedHostId)
    // The ids are resolved off the lock against one snapshot of the rows. They are applied only to those same rows: when the tab was
    // unloaded or switched section meanwhile, the resolve is redone once, and a source that still moves gets no success.
    repeat(GO_TO_ROWS_ATTEMPTS) {
        val shown = loadedSourceShowing(sourceTabId, block.sourceSectionId) ?: return GoToRowsResult.RowsNotFound
        val pinned = pinLoadedTabSnapshot(sourceTabId, shown.logData) ?: return@repeat
        pinned.use { snapshot ->
            val source = snapshot.tab
            if (source.selectedLogSectionId != shown.selectedLogSectionId) return@repeat
            val ids = withContext(Dispatchers.Default) { resolveLogRefRowIds(block, source.logData, source.rmap) }
            afterResolve()
            // A live tail appends new rows while preserving earlier objects. A reload or section switch can replace them.
            val stillShown = { live: LogTab ->
                live.contentState == TabContentState.LOADED && live.selectedLogSectionId == source.selectedLogSectionId &&
                    ids.all { id -> live.rmap[id].let { it != null && it === source.rmap[id] } }
            }
            if (ids.isEmpty()) {
                if (tab(sourceTabId)?.let(stillShown) == true) return GoToRowsResult.RowsNotFound
            } else if (requestLogNavigationIf(sourceTabId, ids, stillShown)) {
                block.recordFields?.let { fields -> openRecordFields(sourceTabId, ids.first(), fields.map { it.path }.toSet()) }
                return GoToRowsResult.Navigated(sourceTabId, ids, missing = block.logIds.size - ids.size)
            }
        }
    }
    return GoToRowsResult.RowsNotFound
}

private const val GO_TO_ROWS_ATTEMPTS = 2

/**
 * Reopens the saved source the user agreed to reopen, waits for the tab to be published, and
 * goes to the rows. Returns [GoToRowsResult.NoSource] if the source cannot be reopened.
 */
internal suspend fun AppState.goToLogRefRowsAfterReopen(
    ownerId: String,
    closedTabId: String,
    block: AnnBlock.LogRef,
    viewingTabId: String,
): GoToRowsResult {
    val closed = closedSourceOf(ownerId, closedTabId) ?: return GoToRowsResult.NoSource
    val reopenedId = reopenClosedSource(closed) ?: return GoToRowsResult.NoSource
    val published = withTimeoutOrNull(REOPEN_WAIT_MS) {
        while (isLoadInFlight(reopenedId)) delay(REOPEN_POLL_MS)
        tab(reopenedId)?.let(closed::matches) == true
    }
    if (published != true) return GoToRowsResult.NoSource
    val currentNote = noteTab(notesTargetFor(viewingTabId))
    val fresh = currentNote?.annotations?.blocks?.firstOrNull { it.id == block.id } as? AnnBlock.LogRef ?: block
    return goToLogRefRows(fresh.copy(sourceTabId = reopenedId), viewingTabId)
}

/** Runs [goToLogRefRows] in the background for a click handler; [onResult] gets the outcome there. */
internal fun AppState.launchGoToLogRefRows(
    block: AnnBlock.LogRef,
    viewingTabId: String,
    onResult: (GoToRowsResult) -> Unit = {},
) = launchInBackground { onResult(goToLogRefRows(block, viewingTabId)) }
