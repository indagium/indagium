# Available MCP methods

The running app's `tools/list` response is authoritative for exact schemas. This guide is the
version-controlled map of what an AI can do and how to prompt it. Most read methods require
`tabId`; start with `list_tabs` and use the returned id, never a filename.

## Read logs and narrow evidence

- `list_tabs` — open tabs and ids; `open_log_file`, `preview_split_log_file`, and
  `split_log_file` — open or prepare a file/archive; `close_tab` — close a tab.
- `get_visible_lines` — rendered, filtered/folded rows; use `limit`, `offset`, `fields`, and
  `compact` to keep responses small. `get_line_context` — raw surrounding rows for one `lineId`,
  independent of filters/folding.
- `get_tags` and `get_packages` — discover exact tag or package-prefix values. `get_crash_sites`
  and `get_issue_description` — find high-signal failure anchors and the user-reported problem.

## Filters, sequences, and navigation

- `get_filter` / `set_filter` — inspect or update levels, tags, packages, keyword filters,
  message rules, and sequence definitions. `set_filter` changes only supplied properties.
- `add_sequence` — append one sequence without replacing existing definitions. Then call
  `get_sequence_summary` with only `tabId` for per-definition counts; call it again with the
  returned `sequenceId`, `offset`, and `limit` for occurrences. Each result gives `gid`, 1-based
  `startRowNumber`/`endRowNumber`, line ids for `get_line_context`, timestamps, line count,
  nesting depth, and `endReason`.
- `set_highlighters`, `select_lines`, `get_selection`, `toggle_group`, `expand_all`,
  `collapse_all`, and `add_manual_collapse` — mark, select, or reveal the evidence needed next.

## Notes, exports, and follow-up material

- `get_annotation_sections` / `append_annotation_section` — inspect or extend the Notes panel's
  context and next steps. `set_annotation_section` replaces a section outright instead — omitting
  or blanking `text` clears it — so reach for `append_annotation_section` first unless the
  existing content needs to go. `add_text_note`, `add_log_note`, `add_image_note`,
  `update_note_block`, `move_note_block`, and `delete_note_block` manage evidence blocks.
- `export_analysis`, `export_filtered_log`, `save_annotations`, and `load_annotations` write or
  restore user-requested artifacts; confirm paths and destructive choices first.
- `list_filter_presets`, `apply_filter_preset`, and `save_filter_preset` manage reusable filters.
  `merge_tabs`, `start_tailing`, and `stop_tailing` manage active log sources.

## Source, cases, and video

- `resolve_log_source`, `get_source_file`, `list_source_declarations`, `get_source_declarations`,
  `get_project_info`, and `reindex_sources` connect log calls to registered Kotlin/Java source.
  Start with a resolved source path, list its declarations, then request only the class or method
  body needed. `get_source_file` is line-paginated (default 400, maximum 2,000 lines); use its
  `nextStartLine` to read broader context without flooding the conversation. Source navigation is
  limited to `.kt`/`.java` files under Settings → Source code folders and does not require an
  index. `search_similar_cases`, `get_case`, `set_case_metadata`, and
  `reindex_cases` retrieve comparable investigations.
- `get_video_frame` and `get_follow_diagnostics` relate a selected log line to attached video.

## Prompt starters

> Investigate the crash in the active tab. Start with crash sites, narrow before reading rows,
> fetch raw context for the strongest anchors, and add evidence-backed notes as you go.

> Add a sequence for `request started` through `request finished`; summarize its occurrences,
> then inspect only the longest or error-containing occurrence with raw line context.

> Find messages from the package that most likely explain the ANR. Use package/tag discovery and
> bounded reads; do not request the full unfiltered log.
