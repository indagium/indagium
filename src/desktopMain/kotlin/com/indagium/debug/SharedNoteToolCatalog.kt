package com.indagium.debug

// MCP catalogue of the SHARED NOTE tools: a shared note is one set of Notes (From, blocks, Next steps) that several open log tabs
// write into, kept apart from any one tab. Handlers: SharedNoteToolOperations.kt (the gateway's init throws if the two drift).
// Appended to ControlServer.kt's MCP_TOOLS like the other catalogues. Reading and editing a shared note's content needs no new tool:
// every existing Notes tool (get_annotation_blocks, add_text_note, ...) takes the note's `hostId` as its `tabId`.
// Classification matches the other Notes tools: list_shared_notes only reads; create/set are ordinary AUTOMATIC Notes writes
// (no confirmation card, no per-call approval) that never touch a log file, and they do not spend an AI run's analysis budget.

internal val SHARED_NOTE_MCP_TOOLS: List<IndagiumToolDescriptor> = listOf(
    IndagiumToolDescriptor(
        "list_shared_notes",
        "List the shared notes: Notes that several open tabs write into together. Each entry has `hostId` (use it as `tabId` " +
            "with get_annotation_blocks, add_text_note, update_note_block and the other Notes tools), `name`, `members` (open tabs: " +
            "tabId and filename), `closedMembers` (tabs closed since: tabId, filename and the file's path, if known, so it can be opened " +
            "again with open_log_file) and `blockCount`. Pass `tabId` to list only the shared notes that tab is a member of.",
        schema(
            "tabId" to "string",
            descriptions = mapOf("tabId" to "Only the shared notes this open tab is a member of (optional)."),
        ),
    ),
    IndagiumToolDescriptor(
        "create_shared_note",
        "Create a shared note with the given open tabs as members and point each member's Notes at it. Returns `hostId` (the id " +
            "to use as `tabId` with the Notes tools) and the resolved `name`. The note starts empty unless `fromTabId` is given: " +
            "then that tab's existing private Notes become the shared note (its row references keep naming that tab as their source) " +
            "and the tab gets a fresh private note. Fails, changing nothing, when a tab id is unknown or `memberTabIds` is empty.",
        schema(
            "name" to "string", "memberTabIds" to "array", "fromTabId" to "string",
            required = listOf("memberTabIds"),
            descriptions = mapOf(
                "name" to "The note's name (optional). A blank or missing name becomes \"Shared note\" (then \"Shared note 2\", ...).",
                "memberTabIds" to "Ids of the open tabs (from list_tabs) that share the note; at least one.",
                "fromTabId" to "An open tab whose existing private note becomes this shared note (optional). It is always a member.",
            ),
        ),
    ),
    IndagiumToolDescriptor(
        "set_shared_note_members",
        "Replace the open members of a shared note. A tab that is removed and was showing this note goes back to its own private " +
            "note; a tab that is added keeps what its Notes tile shows. The note's content and its closed members are not touched. " +
            "Returns the resulting `members` and `closedMembers`. Fails, changing nothing, when `hostId` or a tab id is unknown.",
        schema(
            "hostId" to "string", "memberTabIds" to "array",
            required = listOf("hostId", "memberTabIds"),
            descriptions = mapOf(
                "hostId" to "A shared note's hostId (from list_shared_notes or create_shared_note).",
                "memberTabIds" to "Ids of the open tabs (from list_tabs) that are members afterwards; an empty list removes every open member.",
            ),
        ),
    ),
)
