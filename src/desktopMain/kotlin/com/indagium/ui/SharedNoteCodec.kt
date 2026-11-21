package com.indagium.ui

import com.indagium.model.Annotations
import com.indagium.model.Filter
import com.indagium.model.LogTab
import com.indagium.model.SharedNoteClosedSource
import com.indagium.model.SharedNoteInfo

/** Clear the export's owning log while preserving all other annotation token fields. */
internal fun String.withoutNoteSourcePath(): String {
    val sourcePathIndex = 4
    val fields = split('|').toMutableList()
    val emptyField = tokenFields("")
    if (fields.size <= sourcePathIndex || fields[sourcePathIndex] == emptyField) return this
    fields[sourcePathIndex] = emptyField
    return fields.joinToString("|")
}

// Autosave encoding of shared notes (see LogTab.sharedNote / AppState.sharedNoteHosts): the descriptor is field 22 of a
// tab token (AutosaveCodec.tabToken); the hosts themselves are one `sharedNotes` key line, each host a full tab token.

internal fun SharedNoteInfo.sharedNoteToken(): String = tokenFields(
    id,
    name,
    memberTabIds.joinToString(",") { it.b64() },
    closedMembers.closedSourcesToken(),
    createdAtMs.toString(),
    closedReferenceSources.closedSourcesToken(),
)

private fun List<SharedNoteClosedSource>.closedSourcesToken(): String = joinToString(",") {
    tokenFields(it.tabId, it.filename, it.sourcePath.orEmpty(), it.logFormatProfileSnapshot.orEmpty()).b64()
}

private fun String?.closedSourcesFromToken(): List<SharedNoteClosedSource> = orEmpty().encodedList().mapNotNull { encoded ->
    runCatching {
        val fields = encoded.tokenFields()
        SharedNoteClosedSource(
            fields[0], fields[1], fields.getOrNull(2)?.takeIf { it.isNotBlank() }, fields.getOrNull(3)?.takeIf { it.isNotBlank() },
        )
    }.getOrNull()
}

private fun String.sharedNoteFromToken(): SharedNoteInfo? = runCatching {
    val p = tokenFields()
    SharedNoteInfo(
        id = p[0],
        name = p[1],
        memberTabIds = p.getOrNull(2).orEmpty().encodedList(),
        closedMembers = p.getOrNull(3).closedSourcesFromToken(),
        createdAtMs = p.getOrNull(4)?.toLongOrNull() ?: 0L,
        closedReferenceSources = p.getOrNull(5).closedSourcesFromToken(),
    )
}.getOrNull()

/** The autosave value of the `sharedNotes` key line: every host as a full tab token (annotations included), each base64'd. */
internal fun List<LogTab>.sharedNoteHostsToken(): String = joinToString(",") { it.tabToken().b64() }

/**
 * Rebuilds the shared-note hosts written by [sharedNoteHostsToken]. A host has no source file to load, so it comes back
 * LOADED and empty (like [emptyWorkspaceTab]); an entry that is unreadable or carries no [LogTab.sharedNote] is dropped.
 */
internal fun String.sharedNoteHostsFromToken(): List<LogTab> =
    encodedList().mapNotNull { it.sharedNoteHostFromToken() }.distinctBy { it.id }

private fun String.sharedNoteHostFromToken(): LogTab? = runCatching {
    val p = tokenFields()
    val info = p.getOrNull(TAB_TOKEN_SHARED_NOTE_INDEX)?.takeIf { it.isNotBlank() }?.sharedNoteFromToken()
        ?: return@runCatching null
    sharedNoteHostTab(info).copy(
        id = p[0],
        filename = p[1],
        filter = p[3].savedFilterFromToken()?.toFilter() ?: Filter(),
        annotations = p[4].annotationsFromToken() ?: Annotations(),
        showAnnMd = p[5].toBoolean(),
        noteTargetName = p.getOrNull(12)?.takeIf { it.isNotBlank() },
    )
}.getOrNull()
