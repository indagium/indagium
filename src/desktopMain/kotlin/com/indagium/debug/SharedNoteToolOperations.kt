package com.indagium.debug

import com.indagium.model.SharedNoteInfo
import com.indagium.ui.AppState
import com.indagium.ui.createSharedNote
import com.indagium.ui.setSharedNoteMembers
import com.indagium.ui.shareTabNote

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

    private fun list(tabId: String?): Map<String, Any?> {
        if (!tabId.isNullOrBlank() && openTab(tabId) == null) return error("no such tab: $tabId")
        val notes = appState.sharedNoteHosts.filter { host ->
            tabId.isNullOrBlank() || tabId in host.sharedNote?.memberTabIds.orEmpty()
        }
        return mapOf("sharedNotes" to notes.mapNotNull { host -> host.sharedNote?.let { describe(host.id, it, host.annotations.blocks.size) } })
    }

    private fun create(a: Map<String, Any?>): Map<String, Any?> {
        val members = a.strList("memberTabIds").orEmpty().map { it.trim() }.filter { it.isNotEmpty() }.distinct()
        if (members.isEmpty()) return error("memberTabIds must name at least one open tab")
        val fromTabId = a.str("fromTabId")?.trim()?.takeIf { it.isNotEmpty() }
        unknownTabs((members + listOfNotNull(fromTabId)).distinct())?.let { return it }
        val name = a.str("name").orEmpty()
        val hostId = if (fromTabId != null) {
            appState.shareTabNote(fromTabId, name, members) ?: return error(
                "the note of tab $fromTabId could not be shared: an \"Existing notes found\" prompt is open for it; " +
                    "a person needs to resolve it first",
            )
        } else {
            appState.createSharedNote(name, members) ?: return error("the shared note was not created")
        }
        val info = appState.sharedNoteHost(hostId)?.sharedNote ?: return error("the shared note was not created")
        return mapOf("ok" to true, "hostId" to hostId, "name" to info.name, "members" to members(info))
    }

    private fun setMembers(hostId: String, a: Map<String, Any?>): Map<String, Any?> {
        val members = a.strList("memberTabIds").orEmpty().map { it.trim() }.filter { it.isNotEmpty() }.distinct()
        val problem = when {
            hostId.isBlank() -> error("missing hostId")
            appState.sharedNoteHost(hostId) == null -> error("no such shared note: $hostId")
            a["memberTabIds"] !is List<*> -> error("missing memberTabIds (an array of open tab ids; empty removes every open member)")
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
