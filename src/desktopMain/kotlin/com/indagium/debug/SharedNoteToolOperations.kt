package com.indagium.debug

import com.indagium.model.SharedNoteInfo
import com.indagium.ui.AppState
import com.indagium.ui.createSharedNote
import com.indagium.ui.setSharedNoteMembers
import com.indagium.ui.shareTabNoteLocked

// Handlers of the shared-note tools (catalogue: SharedNoteToolCatalog.kt), merged into IndagiumToolOperations. Every expected
// failure is DATA, `{ "error": message }`, never an exception. The rules (who may be a member, the default name, what moves
// where) live in ui/SharedNotes.kt; these handlers only validate ids up front so a caller hears about an unknown tab instead of
// having it ignored, then call the same operations the UI does.

internal class SharedNoteToolOperations(private val appState: AppState) {
    val handlers: Map<String, (Map<String, Any?>) -> Any?> = mapOf(
        "list_shared_notes" to { a -> list(a.str("tabId")) },
        "create_shared_note" to { a -> create(a) },
        "set_shared_note_members" to { a -> setMembers(a.str("hostId") ?: "", a) },
    )

    /** Membership must still hold when the write commits. The tab ID comes from the run, outside tool arguments. */
    fun executeWriteForTab(name: String, arguments: Map<String, Any?>, tabId: String): Map<String, Any?> =
        when (name) {
            "create_shared_note" -> create(arguments, inAppTabId = tabId)
            "set_shared_note_members" -> synchronized(appState.stateLock) {
                setMembers(arguments.str("hostId") ?: "", arguments, inAppTabId = tabId)
            }
            else -> error("unknown shared-note write: $name")
        }

    private fun list(tabId: String?): Map<String, Any?> {
        if (!tabId.isNullOrBlank() && openTab(tabId) == null) return error("no such tab: $tabId")
        val notes = appState.sharedNoteHosts.filter { host ->
            tabId.isNullOrBlank() || tabId in host.sharedNote?.memberTabIds.orEmpty()
        }
        return mapOf("sharedNotes" to notes.mapNotNull { host -> host.sharedNote?.let { describe(host.id, it, host.annotations.blocks.size) } })
    }

    private fun create(a: Map<String, Any?>, inAppTabId: String? = null): Map<String, Any?> {
        val members = a.strList("memberTabIds").orEmpty().map { it.trim() }.filter { it.isNotEmpty() }.distinct()
        val fromTabId = a.str("fromTabId")?.trim()?.takeIf { it.isNotEmpty() }
        val name = a.str("name").orEmpty()
        val (result, attachmentsChanged) = synchronized(appState.stateLock) {
            val badEntry = badMemberEntry(a)
            val problem = when {
                badEntry != null -> badEntry
                members.isEmpty() -> error("memberTabIds must name at least one open tab")
                inAppTabId != null && fromTabId != null && fromTabId != inAppTabId ->
                    error("An in-app run can only share the private note of its own tab.")
                inAppTabId != null && fromTabId == null && inAppTabId !in members ->
                    error("A shared note created by an in-app run must include its tab.")
                else -> unknownTabs((members + listOfNotNull(fromTabId)).distinct())
            }
            if (problem != null) return problem
            val move = fromTabId?.let {
                appState.shareTabNoteLocked(it, name, members) ?: return error(
                    "the note of tab $fromTabId could not be shared: an \"Existing notes found\" prompt is open for it; " +
                        "a person needs to resolve it first",
                )
            }
            val hostId = move?.hostId ?: appState.createSharedNote(name, members) ?: return error("the shared note was not created")
            val info = appState.sharedNoteHost(hostId)?.sharedNote ?: return error("the shared note was not created")
            mapOf("ok" to true, "hostId" to hostId, "name" to info.name, "members" to members(info)) to (move?.attachmentsChanged == true)
        }
        if (attachmentsChanged) appState.seq3Sessions.persistAttachmentChanges()
        return result
    }

    private fun setMembers(hostId: String, a: Map<String, Any?>, inAppTabId: String? = null): Map<String, Any?> {
        val members = a.strList("memberTabIds").orEmpty().map { it.trim() }.filter { it.isNotEmpty() }.distinct()
        val host = appState.sharedNoteHost(hostId)?.sharedNote
        val badEntry = badMemberEntry(a)
        val problem = when {
            hostId.isBlank() -> error("missing hostId")
            host == null -> error("no such shared note: $hostId")
            inAppTabId != null && inAppTabId !in host.memberTabIds -> error("Shared note $hostId is unavailable to the tab for this run.")
            a["memberTabIds"] !is List<*> -> error("missing memberTabIds (an array of open tab ids; empty removes every open member)")
            badEntry != null -> badEntry
            else -> unknownTabs(members)
        }
        if (problem != null) return problem
        val changed = appState.setSharedNoteMembers(hostId, members)
        val info = appState.sharedNoteHost(hostId)?.sharedNote
        return if (!changed || info == null) {
            error("no such shared note: $hostId")
        } else {
            mapOf("ok" to true, "hostId" to hostId, "name" to info.name, "members" to members(info), "closedMembers" to closedMembers(info))
        }
    }

    /**
     * An error for the first `memberTabIds` entry that is not a non-blank string, else null. Checked before the entries are trimmed
     * and filtered: a blank id dropped silently would turn `[" "]` into the empty list, which means "remove every open member".
     */
    private fun badMemberEntry(a: Map<String, Any?>): Map<String, Any?>? {
        val entries = a["memberTabIds"] as? List<*> ?: return null
        entries.forEachIndexed { index, entry ->
            if (entry !is String) return error("memberTabIds[$index] must be a string tab id")
            if (entry.isBlank()) return error("memberTabIds[$index] is blank")
        }
        return null
    }

    private fun openTab(id: String) = appState.tab(id)?.takeIf { !it.isCaptureLauncher }

    /** An error naming every id that is not an open ordinary tab (a shared note or the New-tab launcher is not one), else null. */
    private fun unknownTabs(ids: List<String>): Map<String, Any?>? {
        val unknown = ids.filter { openTab(it) == null }
        return if (unknown.isEmpty()) null else error("no such tab: ${unknown.joinToString(", ")}")
    }

    private fun members(info: SharedNoteInfo): List<Map<String, Any?>> =
        info.memberTabIds.map { id -> mapOf("tabId" to id, "filename" to appState.tab(id)?.filename) }

    private fun closedMembers(info: SharedNoteInfo): List<Map<String, Any?>> =
        info.closedMembers.map { mapOf("tabId" to it.tabId, "filename" to it.filename, "sourcePath" to it.sourcePath) }

    private fun describe(hostId: String, info: SharedNoteInfo, blockCount: Int): Map<String, Any?> = mapOf(
        "hostId" to hostId,
        "name" to info.name,
        "members" to members(info),
        "closedMembers" to closedMembers(info),
        "blockCount" to blockCount,
    )

    private fun error(message: String): Map<String, Any?> = mapOf("error" to message)
}
