package com.indagium.debug

/** MCP and in-app AI tools for inspecting, importing, exporting, and applying data-only formats. */
internal val LOG_FORMAT_MCP_TOOLS: List<IndagiumToolDescriptor> = listOf(
    IndagiumToolDescriptor(
        "list_log_format_profiles",
        "List built-in and custom log format profiles. Each result includes its stable id, revision, engine, " +
            "field mappings, custom attributes, detection hints, and whether it is read-only.",
        schema(),
    ),
    IndagiumToolDescriptor(
        "get_log_format_profile",
        "Return one complete, data-only log format profile definition by stable id.",
        schema("profileId" to "string", required = listOf("profileId")),
    ),
    IndagiumToolDescriptor(
        "preview_log_format_profile",
        "Parse a bounded sample with a profile without changing any tab. Returns parsed rows, diagnostics, " +
            "available fields and derived capability status.",
        schema(
            "profileId" to "string", "sample" to "string", "limit" to "integer",
            required = listOf("profileId", "sample"),
            descriptions = mapOf(
                "sample" to "Plain-text sample to parse. Preview is bounded to 2,000 input lines and the parser's per-record limits.",
                "limit" to "Maximum preview records to return (default 50, maximum 200).",
            ),
        ),
    ),
    IndagiumToolDescriptor(
        "import_log_format_profiles",
        "Validate and import one profile definition or a profile bundle in JSON. Conflict policy is explicit: " +
            "RENAME preserves existing profiles and gives colliding imports new IDs/names; UPDATE replaces matching " +
            "custom IDs and advances their revision. Built-ins are never overwritten.",
        schema(
            "json" to "string", "conflictPolicy" to "string",
            required = listOf("json", "conflictPolicy"),
            enums = mapOf("conflictPolicy" to listOf("RENAME", "UPDATE")),
            descriptions = mapOf("json" to "One profile JSON object, a JSON array, or an object with a profiles array."),
        ),
    ),
    IndagiumToolDescriptor(
        "export_log_format_profiles",
        "Export selected built-in or custom log format profiles as a validated JSON bundle. If path is supplied, " +
            "also write the same JSON atomically to that path. Profile IDs are preserved.",
        schema(
            "profileIds" to "array", "path" to "string",
            required = listOf("profileIds"),
            descriptions = mapOf("profileIds" to "Stable profile IDs to export; pass one ID for a single-profile object."),
        ),
    ),
    IndagiumToolDescriptor(
        "reparse_tab_with_log_format_profile",
        "Reparse the source of an open tab with a selected profile into a new independent tab. The original tab and its " +
            "pinned profile remain unchanged. Returns the new tab ID after parsing completes.",
        schema("tabId" to "string", "profileId" to "string", required = listOf("tabId", "profileId")),
    ),
)
