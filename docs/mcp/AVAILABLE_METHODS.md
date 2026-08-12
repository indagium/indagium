# Available MCP methods

The running app's `tools/list` response is authoritative for exact schemas. This guide is the
version-controlled map of what an AI can do and how to prompt it. Most read methods require
`tabId`; start with `list_tabs` and use the returned id, never a filename.

For automated end-to-end verification, launch a dedicated process with both
`INDAGIUM_DEBUG_CONTROL=<port>` and
`INDAGIUM_DEBUG_APP_DATA_DIR=<canonical-empty-temp-directory>`. The app-data directory must be an
empty, non-symlink child beneath the JVM temporary directory (or macOS `/private/tmp` /
`/private/var/folders`). This prevents the test process from restoring the user's autosave or
Recent state; file-open calls must still use explicit approved fixture paths.

## Read logs and narrow evidence

- `list_tabs` — open tabs and ids; `open_log_file`, `preview_split_log_file`, and
  `split_log_file` — open or prepare a file/archive; `close_tab` — close a tab.
- `get_visible_lines` — rendered, filtered/folded rows; use `limit`, `offset`, `fields`, and
  `compact` to keep responses small. `get_line_context` — raw surrounding rows for one `lineId`,
  independent of filters/folding.
- `get_tags` and `get_packages` — discover exact tag or package-prefix values. `get_crash_sites`
  and `get_issue_description` — find high-signal failure anchors and the user-reported problem.
- `get_log_composition` — rank the distinct masked message shapes in the tab's CURRENT FILTERED
  VIEW (not the whole file), most frequent first by default. Narrow with `set_filter`, then call
  this instead of scrolling for repeats. `order:rare` flips to least-frequent-first — the lens
  that tends to find one-off defects rather than routine noise. A result cached for the tab's
  exact current filter returns instantly; otherwise this scans synchronously and can take several
  seconds on a large unfiltered tab, so filter first. Each row is compact (tag/template/count/
  firstLineId) — follow up with `get_line_context` on `firstLineId` for real text. Check
  `overflowed`: when true the rare lens is incomplete, not exhaustive.

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
  context and next steps. `get_annotation_sections` does not list evidence blocks; use
  `get_annotation_blocks` for every block id plus safe type/text/caption/line-id/image metadata.
  `set_annotation_section` replaces a section outright instead — omitting or blanking `text`
  clears it — so reach for `append_annotation_section` first unless the existing content needs to
  go. `add_text_note`, `add_log_note`, `add_image_note`, `update_note_block`, `move_note_block`,
  and `delete_note_block` manage individual evidence blocks. `clear_all_notes` is the explicit,
  confirmation-required bulk clear for both sections and every block; it preserves the private
  issue description and case metadata.
- `export_analysis`, `export_filtered_log`, `save_annotations`, and `load_annotations` write or
  restore user-requested artifacts; confirm paths and destructive choices first.
- `list_filter_presets`, `apply_filter_preset`, and `save_filter_preset` manage reusable filters.
  `merge_tabs`, `start_tailing`, and `stop_tailing` manage active log sources.

## Source, cases, and video

- `register_source_folder`, `resolve_log_source`, `get_source_file`, `list_source_declarations`,
  `get_source_declarations`, `get_project_info`, and `reindex_sources` connect log calls to
  registered Kotlin/Java source. `register_source_folder` accepts one canonical source directory
  and is useful for isolated automation runs where opening Settings is not practical.
  Start with a resolved source path, list its declarations, then request only the class or method
  body needed. `get_source_file` is line-paginated (default 400, maximum 2,000 lines); use its
  `nextStartLine` to read broader context without flooding the conversation. Source navigation is
  limited to `.kt`/`.java` files under Settings → Source code folders and does not require an
  index. `search_similar_cases`, `get_case`, `set_case_metadata`, and
  `reindex_cases` retrieve comparable investigations.
- `get_video_frame` and `get_follow_diagnostics` relate a selected log line to attached video.

## Diagrams

- `build_sequence_diagram` turns a range of log lines into an editable manual sequence-diagram
  draft and UML source (Mermaid by default, PlantUML on request). By default it uses verified
  source tracing to seed structural calls/returns alongside every selected log event; use
  `seed: { sourceTrace, threadHandoffs }` to control that starting draft. Ambiguous or unavailable
  source evidence remains diagnostics and falls back to log events rather than guessed arrows.
  Prefer `components`: each object is `{ id, displayName, tagIds, enabled }`, so a component can
  deliberately represent several raw tags. Components are not capped at eight participants; use a
  tight range and the returned coverage/warnings to judge readability. `tags` remains available for
  legacy callers, and `mergedTags` is a shorthand map from component name/id to tag arrays. Use
  `lifelineOrder` for an explicit participant order. Provide `manualDocument` to render a completed
  source-index-independent draft with interactions, groups, notes, and activations. The MCP
  boundary accepts at most 64 components, 64 actors, 128 legacy tags, 128 tags per component, and
  1,024 component/merged tag references; IDs/tags are at most 256 characters and labels/titles at
  most 512. Component and actor IDs must be unique and cannot collide.

  Tags outside enabled components are hidden by default. Set `unmappedTagPolicy` to `groupAsOther`
  only when grouping every remaining in-range tag into the single `Other` component is meaningful.
  Interaction rules accept typed `fromEndpoint`/`toEndpoint` references. Captured values must have an
  explicit value-to-participant binding; only an explicit `actor` endpoint may create a lifeline.
  `sourceEnrichment: true` reconstructs a bounded, verified-only source execution trace;
  structural calls and returns carry source operation IDs, while ambiguous or stale anchors remain
  diagnostics. If no current source index is loaded, the response reports explicit fallback mode
  instead of silently returning an unenriched result. `activationPolicy`
  defaults to `evidenceBacked` and emits activation spans only when correlated log/rule/source
  evidence supports them (`none` disables spans). The result includes per-message evidence (`log`,
  `rule`, `sourceInferred`, or `actorMirror`) and range coverage as well as `truncated` and up to 100
  bounded warnings. `maxMessages` defaults to 60 and is hard-capped at 400.

  The tool is read-only — pass returned `source` to `add_text_note` to put it in the analysis.

## Prompt starters

> Investigate the crash in the active tab. Start with crash sites, narrow before reading rows,
> fetch raw context for the strongest anchors, and add evidence-backed notes as you go.

> Add a sequence for `request started` through `request finished`; summarize its occurrences,
> then inspect only the longest or error-containing occurrence with raw line context.

> Find messages from the package that most likely explain the ANR. Use package/tag discovery and
> bounded reads; do not request the full unfiltered log.
