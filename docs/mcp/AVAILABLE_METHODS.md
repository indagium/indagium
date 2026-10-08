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
  `split_log_file` — open or prepare a file/archive; `close_tab` — close a tab. A live capture
  tab whose log view was paused at critical heap pressure carries `captureLogPausedAtRow`; its
  `entryCount` is then only the first N rows of the recording (the full log is on disk). A file
  too large for the free heap returns `needsSplit` with `memoryShortfall` (`neededBytes`,
  `freeBytes`) and a `suggestedPartCount` of at least 2; `split_log_file` then opens only the
  parts that fit and lists the rest in `unopenedPaths`.
- `get_memory_status` — JVM heap health: `heapPressure` (`NORMAL`/`WARNING`/`CRITICAL`),
  `heapUsedAfterGcBytes` (the latest GC reading), `heapMaxBytes`, `heapFreeBytesEstimate`, and `pausedCaptureTabs`. Every
  parsed row lives in memory, so check it before opening another large log.
- `get_visible_lines` — rendered, filtered/folded rows; use `limit`, `offset`, `fields`, and
  `compact` to keep responses small. `get_line_context` — raw surrounding rows for one `lineId`,
  independent of filters/folding.
- `get_tags` and `get_packages` — discover exact tag or package-prefix values. `get_crash_sites`
  and `get_issue_description` — find high-signal failure anchors and the user-reported problem.
  On a tab tailed live, `get_crash_sites` returns `partial: true` with `analyzedThroughId` (rows
  after that id are not analysed yet); `pending: true` means the first analysis has not finished.
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
  `set_highlighters` accepts the optional style fields `backgroundEnabled`, `textColor`, `fontFamily`,
  `bold`, and `italic`; `kloggStyle` explicitly retains imported klogg match ordering. Older requests
  that send `textColor` without `kloggStyle` keep their previous klogg behavior.

## Notes, exports, and follow-up material

- `get_annotation_sections` / `append_annotation_section` — inspect or extend the Notes panel's
  context and next steps. `get_annotation_sections` does not list evidence blocks; use
  `get_annotation_blocks` for every block id plus safe type/text/caption/line-id/image metadata.
  `set_annotation_section` replaces a section outright instead — omitting or blanking `text`
  clears it — so reach for `append_annotation_section` first unless the existing content needs to
  go. `add_text_note`, `add_log_note`, `add_image_note`, `update_note_block`, `update_note_caption`,
  `move_note_block`, and `delete_note_block` manage individual evidence blocks. Notes and captions
  are Markdown (headings, bold/italic/strikethrough, lists, quotes, links, inline/fenced code).
  `update_note_block` full-replaces ordinary note Markdown; read first when preserving content.
  `clear_all_notes` is the explicit,
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

- `build_sequence_diagram` generates a UML sequence diagram from a range of log lines and returns
  its source (Mermaid by default, PlantUML on request) plus the generated lifelines and messages.
  Lifelines are ranked automatically from tag activity (errors, message-shape diversity, same-thread
  peers, raw count) — there is no `components`/`tags`/`actors` participant configuration to pass.
  A message's target lifeline is inferred only from adjacent-entry evidence (a same-thread handoff
  or a shared correlation token, both on by default and individually toggleable via
  `threadHandoffs`/`correlationTokens`) above a confidence bar; anything short of that is returned
  with `needsTarget: true` rather than guessed. Use `startLineId`/`endLineId` for an explicit range
  (omit both for the whole filtered view), `maxLifelines` to cap lifeline count (default 8, hard
  maximum 32), and `title` for the diagram's title. This is read-only and does no source-index
  enrichment — pass the returned `source` to `add_text_note` to store it as a note.

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

## Device capture

Device capture tools control the attached Android device, not the computer running this server.
`list_android_devices`, `get_device_capture_status`, `list_device_apps`, `get_device_log_settings`,
and `get_capture_operation_status` are read-only discovery/status calls and need no approval; every
other device tool changes the device or reads its live screen and requires per-session approval
from an external MCP client (the in-app AI panel is authorized directly by the user's prompt).
At most four device operations (the asynchronous ones that return an `operationId`) may be running
at once; further requests return an error until one finishes.

- `list_android_devices` — ready devices, with serial/model/state. `start_device_capture` starts a
  capture (or reuses the live one); pass `deviceSerial` when several devices are ready, and
  `newCapture: true` to finalize the current recording first. Optional `recordVideo` and
  `includeEarlierDeviceLogs` override that one launch's settings without changing the saved
  defaults. `stop_device_capture` finalizes and retains the session; it returns an `operationId` —
  poll `get_capture_operation_status` until it completes before starting another capture.
- `get_device_screen` returns a transient image of the live screen (never saved); `device_tap` and
  `device_swipe` take pixel coordinates measured in that same image and map them to the physical
  device automatically. `device_key` presses one allowlisted key (navigation, D-pad, volume, and
  similar — no POWER/SLEEP). `device_text` enters bounded plain text into the focused field.
  `device_launch_app` starts an installed app by package name; `list_device_apps` looks one up
  first (optional `query` substring filter, `includeSystem` to include likely system packages).
  `device_open_url` opens an http/https URL in the device's default handler.
- `capture_device_screenshot` saves a screenshot into Notes with a linked video frame — use this,
  not `get_device_screen`, when the user asks for "a screenshot". `mark_device_issue` creates a
  durable marker (a log window plus a screenshot when supported); optional `label`/`note` set its
  heading and extra note text. Both return an `operationId` to poll.
- `export_capture_snapshot` saves the capture as a non-overwriting ZIP. Optional `range`
  (`all`/`last_minutes`/`since_last_save`/`selection`, default `all`), `minutes` (for
  `last_minutes`; 5/10 match the popover's own presets), `includeVideo`, `filename`, and `open`
  (open the archive in a new tab once saved, like the popover's "Save + open"). Once the capture
  has stopped, only `range: "all"` is available. Returns an `operationId` to poll.
- `get_device_capture_status` reports device, live/stopped, elapsed time, whether video is
  recording, storage used, markers, and the last exported snapshot path, without touching the
  device. `get_device_log_settings` and `set_device_log_settings` read or change a device's logcat
  buffer sizes (`256K`/`1M`/`4M`/`16M`) and global log level (`default`/`V`/`D`/`I`/`W`/`E`/`S`). Both validate all arguments before
  changing anything, and accept `tabId` and/or `deviceSerial`; if both are given they must name the
  same device or the call is refused.
- `get_capture_operation_status` checks any of the above asynchronous operations by `operationId`.

## AI test suites

These tools author the AI test-suite library: **suites** contain **cases**, a case contains ordered
**steps**, and a step has an `action`, an `expected` result, ordered `checks` and ordered `examples`.
Custom **scripts** (shell commands exposed to test agents as typed tools) and reusable **shared steps**
live beside the suites in the library. Order is list order everywhere, and every list has a `move_*`
tool. Runs are started and read with the run tools (see "Running a suite" below), issues are created from
failed steps ("Issues from failed steps") and can be filed in an issue tracker ("Issue tracker").

Every tool returns plain JSON and reports problems as data, never as a thrown error:

- `{ "error": "Suite 'x' not found." }` for an unknown id, an invalid value or a bad argument. Argument
  mistakes name the field (`checks[1].regex is required for type 'logAppears'.`).
- `warnings` (array of strings) next to the result when the call worked but needs attention, for
  example a hook or `scriptResult` check that references a script or shared step that is not in the
  library, or an imported suite whose extra cases arrive locked.
- `{ "error": "...", "limit": { "kind", "max", "edition", "hint" } }` when the edition limits refuse the
  call. `kind` is `SUITE_LIMIT`, `CASE_LIMIT` or `LOCKED`; `max` is the number that was exceeded (null
  for `LOCKED`); `edition` is `FREE`/`PREMIUM`/`FRIENDS_FAMILY`/`UNLIMITED`; `hint` is the text the UI
  shows ("Free edition: 1 suite / 5 cases").

**Edition limits.** Builds default to Unlimited. The Free edition allows 1 suite with 5 cases. Entries
past the limit (in the user's order) are *locked*: still readable, exportable, deletable and
reorderable, but not editable, runnable or duplicable. `list_test_suites` and `get_test_suite` carry a
`locked` flag on every suite and case. Data is never dropped; reordering is how the user picks which
entries are active.

### Edition

- `get_edition` — `{ edition, label, limits: { maxSuites, maxCasesPerSuite }, devSwitchAllowed, hint }`
  (`null` limits mean unlimited).
- `set_edition` (`edition`) — development tool to try the limits: refused with an error unless the
  build is unpackaged or started with `-Dindagium.dev=true` (`devSwitchAllowed`). The edition can also
  be set at launch with `-Dindagium.edition=free` or `INDAGIUM_EDITION=free`. Asks for confirmation
  inside the in-app AI panel.

### Suites

- `list_test_suites` — suites in order: `id`, `name`, `description`, `caseCount`, `stepCount`, `locked`,
  `readOnly`, timestamps and each suite's cases (`id`, `name`, `stepCount`, `locked`), plus the edition
  info, `scriptCount` and `sharedStepCount`. `readOnly` marks a suite written by a newer Indagium.
- `get_test_suite` (`suiteId`) — the whole suite in the same JSON shape as the exported file: `name`,
  `description`, `instructions`, `targetPackage`, `deviceProfileHint`, `tags`, `setup`/`teardown`
  hooks, `variables` and every case with its steps, checks and examples, plus `locked` on the suite
  and each case.
- `create_test_suite` (`name`; optional `description`, `instructions`, `targetPackage`,
  `deviceProfileHint`, `tags`, `setup`, `teardown`, `variables`) — appended to the library. Refused
  with a `SUITE_LIMIT` error at the limit. `targetPackage` is the Android package under test (a
  reverse-domain id such as `com.example.app`, validated like the device tools' package names; empty
  means unspecified), `deviceProfileHint` is free text about the wanted device, and `tags` are short
  labels (at most 40 characters each, trimmed and de-duplicated).
- `update_test_suite` (`suiteId`; any of `name`, `description`, `instructions`, `targetPackage`,
  `deviceProfileHint`, `tags`, `setup`, `teardown`, `variables`) — only the fields you send change;
  `tags`, `setup`, `teardown` and `variables` replace the whole ordered list, and an empty
  `targetPackage` clears it. Refused for a locked suite.
- `delete_test_suite` (`suiteId`) — allowed even when locked. Asks for confirmation inside the AI panel.
- `duplicate_test_suite` (`suiteId`) — deep copy with new ids, placed after the original, named
  "<name> (copy)". Refused for a locked suite or at the suite limit.
- `move_test_suite` (`suiteId`, `toIndex`) — returns the new `suiteIds` order. `toIndex` is the final
  0-based position, clamped. Allowed for locked suites.
- `import_test_suite` (exactly one of `path`, an absolute file path, or `text`) — adds a suite from an
  exported `indagium-test-suite` file with fresh ids. A suite with more cases than the edition allows
  imports with the extra cases locked and a warning; at the suite limit it is refused. Asks for
  confirmation inside the AI panel.
- `export_test_suite` (`suiteId`; optional absolute `path`, `overwrite`) — without `path` returns the
  file's `text`; with `path` writes it (an existing file is only replaced when `overwrite` is true).
  Asks for confirmation inside the AI panel.

### Cases

- `create_test_case` (`suiteId`, `name`; optional `description`, `preconditions`, `instructions`,
  `setup`, `teardown`, `allowedTools`, `index`, `steps`) — `description` is the case's **goal** (what
  it sets out to verify), `preconditions` is free text about the state the case expects, and
  `setup`/`teardown` are hooks (same shape as the suite's) that run around just this case. `steps` creates the case with its ordered steps in one call (each step uses the
  `create_test_step` fields). `allowedTools` lists the lane tools the agent may use; omit it to allow
  all of them. Refused with a `CASE_LIMIT` error when the suite is full.
- `update_test_case` (`caseId`; any of `name`, `description`, `preconditions`, `instructions`, `setup`,
  `teardown`, `allowedTools`, `allowAllTools`) — `allowAllTools: true` removes the allow-list. Steps are edited with the step
  tools. Refused for a locked case.
- `delete_test_case` (`caseId`) — allowed even when locked. Asks for confirmation inside the AI panel.
- `duplicate_test_case` (`caseId`) — deep copy placed after the original. Refused when locked or full.
- `move_test_case` (`caseId`, `toIndex`; optional `toSuiteId`) — reorders inside the suite (always
  allowed) or moves the case into another suite (refused when that suite is locked or full). Returns
  the case with its new `suiteId` and `index`.

### Steps

- `create_test_step` (`caseId`, `action`; optional `expected`, `timeoutMs`, `retries`, `maxToolCalls`,
  `onFailure`, `optional`, `condition`, `checks`, `examples`, `index`) — `optional` defaults to false;
  when true, `condition` must explain when to perform it. Recording Apply requires expected text on required
  steps; a conditioned optional step may leave it blank. Existing general API behavior for blank expected text
  remains unchanged. `maxToolCalls` (1..100, default 15) is the step's
  lane-tool call budget; `onFailure` is `STOP_CASE` (default), `CONTINUE`,
  `CREATE_ISSUE_AND_CONTINUE` or `PAUSE_FOR_USER`. Refused when the case or its suite is locked.
- `update_test_step` (`stepId`; any of the create fields) — setting `optional:false` clears its condition;
  `checks` and `examples` **replace the whole
  ordered lists**: send every entry you want to keep, re-sending an entry's `id` keeps its identity.
- `delete_test_step` (`stepId`), `duplicate_test_step` (`stepId`; deep copy with new ids placed after the
  original), `move_test_step` (`stepId`, `toIndex`; returns the case's `stepIds` order).

Check entries have a `type`: `logAppears` (`regex`, optional `tag`, `withinMs`), `logAbsent` (`regex`,
optional `tag`, `forMs`), `screenJudge` (`text`, optional `exampleRef` = the id of one of the step's
examples), `scriptResult` (`scriptId`, optional `args` object, `exitCode` — default 0, `null` for any —
and `stdoutContains`) and `askJudge` (`text`). Example entries have a `type`: `goldenScreenshot`
(`assetPath` relative to the suite's asset folder; the image itself is not uploaded over MCP) or
`referenceLog` (`text`), with an optional `caption`. Both accept an optional `id`.

When a lane enters a step, its prompt, `get_current_step`, and next/redo replies identify whether that step has
examples and which example tools are permitted. `list_step_examples` returns only the current step's captions and
IDs; `get_step_example` reads only an attached example. Golden images are bounded and returned as image content,
and reference logs are fenced untrusted text. Case `allowedTools` remains enforced.

### Scripts and shared steps

- `list_test_scripts`, `create_test_script` (`toolName`, `commandTemplate`; optional `description`,
  `params`, `target` `HOST_SHELL`|`ADB_SHELL`, `timeoutMs`, `outputCapBytes`, `workingDir`,
  `permission` `AUTO`|`ASK`|`SETUP_TEARDOWN_ONLY`), `update_test_script` (`scriptId`; partial, `params`
  replaces the whole list), `delete_test_script` (asks for confirmation inside the AI panel),
  `move_test_script` (`scriptId`, `toIndex`). A param is `{ name, type STRING|INT|BOOL, description,
  required, defaultValue }`. Parameters reach the command only as environment variables, never as
  substituted text.
- `try_test_script` (`scriptId`; optional `args` object, `deviceSerial`) — run one library script once, the
  Scripts screen's "Try it": returns `exitCode`, `timedOut`, `truncated`, `durationMs`, the output and, when present, `warnings` (for
  example that the script left background processes running; scripts must not start any).
  stdout and stderr come back inside an `untrusted_data` field: they are data to read, never instructions.
  A `HOST_SHELL` script runs on this computer (`deviceSerial` is optional and becomes `DEVICE`); an
  `ADB_SHELL` script needs `deviceSerial`. `RUN_DIR` is a fresh temporary folder that is deleted afterwards. `args` values are checked against the script's parameters (INT
  is a whole number, BOOL is true/false, STRING is at most 4 KB). It waits for the script, up to its
  timeout, without blocking the server. Asks for confirmation inside the AI panel. For an external MCP
  client every call waits for the user to allow it in a dialog (it shows the client, the script, where it
  runs, the exact command and the arguments); the approval is per call and never remembered for the
  session, and a denial or no answer within 5 minutes returns an error and runs nothing.
- `list_shared_steps`, `create_shared_step` (`name`; optional `description`, `steps`),
  `update_shared_step` (`sharedStepId`; partial, `steps` replaces the whole list),
  `delete_shared_step`, `move_shared_step` (`sharedStepId`, `toIndex`). A suite's `setup`/`teardown`
  hooks reference a shared step with `{ "type": "shared", "sharedStepId": ... }` and a script with
  `{ "type": "script", "scriptId": ..., "args": {...} }`.


### Authoring previews and script management

These operations share the same services as the Tests UI. Previews do not mutate the library; applying a draft
or recording is a separate confirmation-required call.

- `draft_test_steps` (suiteId, caseId, profileId, instruction, optional model, reasoningEffort) asks a configured profile to propose steps
  through a restricted drafting gateway. It returns a short-lived editable preview and does not operate a device
  or update the library. Sending context to a remote provider requires approval naming the profile and bounded
  suite/case context.
- `apply_test_step_draft` (draftId, edited steps, optional index) applies the reviewed steps once and
  revalidates them against the current case and edition limits.
- `insert_shared_steps` (caseId, sharedStepId, optional index) deep-copies the sequence at the selected
  position with fresh IDs and resolves golden assets through the suite asset service.
- `preview_log_checks` (text) parses Android logcat into editable LogAppears checks with regex-escaped literal
  messages and the default wait duration. `insert_log_checks` (stepId, edited checks or text, optional index)
  inserts the reviewed checks with fresh IDs.
- `start_test_recording` (suiteId, caseId, deviceSerial) asynchronously validates the case and connected device, then starts or
  reuses the configured local capture and connects its embedded mirror before observing accepted input; it never injects input.
  A different-device capture or a lane claim is reported instead of being stopped or replaced. Stopping the recording ends input
  observation but leaves the capture live for review or a later same-device test lane. `get_test_recording` (sessionId, optional rowId) returns the preview, video timeline
  availability/duration/gaps/cap warning, and warnings; each row reports `durationMs`, `tappedElement`, legacy `package`/`activity`, and explicit `beforeApp`,
  `beforeActivity`, `afterApp`, and `afterActivity` fields (each may be unavailable). Before/after screenshots are
  independently timed and may exist without app/activity context when a later slow probe overlaps input. A mirror
  image appears as a raw input preview with uncertain timing. Supplying one rowId returns that row's bounded image as
  MCP image content with source, acquisition interval, and verification metadata.
  `update_test_recording` (sessionId, steps) edits the stopped preview. Each step carries action, expected text,
  and optional useScreenshotAsExpected; a captured frame is context unless explicitly opted in.
  `stop_test_recording`, `discard_test_recording`, and `apply_test_recording` stop/drain, discard without
  library mutation, or apply the reviewed preview at an optional index to its original case.
  `rewrite_test_recording` (sessionId, profileId, optional model, reasoningEffort, note) asks a configured profile to
  turn a stopped recording into readable steps. The provider reads the recorded inputs, screen elements, screenshots,
  and (when captured) video storyboards through a read-only gateway (`get_recorded_input`, `get_recorded_screen`, `get_recorded_ui`,
  `get_recorded_video_storyboard`; typed passwords are hidden
  and never sent). `get_recorded_screen` accepts `moment: before|after` for eligible evidence or `moment: input` for a raw
  preview with source and acquisition timing; uncertain mirror timing is never verified as before/after. Every input must
  land in exactly one step, or nothing changes. `get_recorded_video_storyboard` accepts either a 1-based `index` for an
  input-centered sequence or `startMs` plus `endMs` for a session-relative range no longer than 30 seconds. It returns a
  single MCP image content block containing up to six decoded, timestamp-labelled frames, plus frame times, ages and any
  input coverage. `get_recorded_input` reports each input's approximate session-relative `videoInputStartMs` and
  `videoInputEndMs` when video exists, so a caller can request a relevant range. The selected interval or requested range is
  repeated in image metadata; the first range frame may show a held predecessor. The frame time is mapped from packet receipt
  approximately; pre-roll can predate the session, and missing
  segments/reconnects are reported. Known password-edit windows are withheld. An observed video transition can support a
  broad action and result such as opening YouTube from the launcher, but cannot identify a specific control from the
  destination screen alone. The rewrite brief marks available screenshots, UI readings and timeline summary, and shared
  capture IDs let the provider reuse one image across
  adjacent inputs. It reads only available evidence needed to resolve uncertainty; a missing reading is not retried. A
  supported step omits `reviewReason` or sets it to null; empty and whitespace-only values are treated as absent. A blank
  expected result is allowed only for an optional step with a non-blank condition, or a required step with a substantive
  `reviewReason`. Wrong types and non-empty reasons over 500 characters are
  invalid. The
  rewritten rows replace the recording's rows for review (`rewritten`, `rewriteNotes`, and
  `sourceInputIds` per row in `get_test_recording`); sending the recording to a remote provider requires approval.
  `restore_test_recording_raw` (sessionId) puts the recorded rows back exactly (same row ids); it needs no approval.
  Both are refused while the recording is being applied or rewritten, and a later `apply_test_recording` is refused
  while a rewrite runs. An applied rewritten step keeps a reference example "Recorded input (hint; prefer what is on
  screen)" listing the raw inputs; its after-screenshot becomes a golden image only when `useScreenshotAsExpected`
  is true for that row.
- `duplicate_test_script` (scriptId) assigns a fresh id and unique tool name. `import_test_script` accepts
  exactly one of text or path; `export_test_script` accepts scriptId and optional path/overwrite. Both use the
  versioned script JSON envelope. `get_test_script_schema` returns the actual lane tool schema, and
  `get_test_script_usage` lists each explicit current-library hook, script-result check, and allowed-tool
  reference for navigation.

The case-level paid-dispatch guard is authoritative for agent and external lanes. Paid attempts count even when
execution errors; protocol tools are free. Exhaustion is an actionable case ERROR and cannot be bypassed by
calling finish_step immediately after a rejected dispatch.

### Running a suite

A **run** drives one Android device per **lane**. A lane is driven either by an AI profile (Claude Code, Codex
or an API profile configured in Settings) or by *you*: an `"external"` lane has no agent and is driven with
`test_lane_tool_call`, which is how a developer's Claude can run, check and debug a suite over MCP. Lanes on
different devices run **in parallel** (at most 4 devices at once; the rest wait); lanes that share a device
run one after another, and one lane failing or being cancelled never stops another (cancelling the run stops
all of them). Every lane records its device through a **real capture**, the same controller, recorder and
archive code a manual live capture uses (log, and with the run's `capture` options the screen, device audio, the
microphone, the device display, buffers and quality). By default it also opens a **live capture tab** per lane in
the app (`openLaneTabs`, without taking focus), which stays as a stopped capture tab after the lane. A lane refuses
a serial a manual capture or another lane holds, and a manual capture refuses a serial a lane holds. A lane runs the
suite's setup hooks, then each case (setup, steps, teardown), then the suite's teardown, and records the evidence
under `<Test runs folder>/<runId>/` (default `<save folder>/test-runs/<runId>/`; the lane's capture session is `lanes/<laneId>/capture/<sessionId>/`).
When a non-setup step ends `FAIL`, `TIMEOUT`, `BLOCKED` or `ERROR`, the lane writes an **AI marker** into its
capture, the same kind of note the Mark issue button writes (`AI · <case> · step N failed`, the action, expected
result, failed checks and the judge's verdict with everything model- or device-written quoted as untrusted, the
step's screenshot, the log window around it).

An optional **judge** (`judgeProfileId` + `judgeMode`) is a separate, *blind* AI run (any profile kind, started
like a lane's agent) with its own judge-only tools: `get_step_brief` (action, expected result, the judge checks,
the automatic check results, example captions; never the agent's claim or observation), `get_step_screenshot`,
`get_example`, `read_step_log` and `submit_verdict` (`pass`|`fail`|`inconclusive`, reasoning, classification
`app_defect`|`agent_or_step_problem`|`unknown`, optional `suggestedStepFix` `{ action?, expected?, note? }`). It has a
budget of 8 evidence calls (submitting is free) and 60 seconds, and runs inline inside `finish_step`. `judgeMode`
`every_step` judges every step after its automatic checks; `failures_only` judges steps whose automatic checks
failed or that the agent reported as `fail`/`blocked`, and every step that has a `screenJudge`/`askJudge` check.
Before execution, selected cases and reachable shared hooks are validated: explicit judge checks refuse judge mode
`off` or a missing/unusable judge. An error, timeout or inconclusive answer records the explicit check as
`NOT_EVALUATED` and the step as `BLOCKED`, then follows retry and failure policy. Deterministic failures remain
`FAIL`; the judge cannot turn a failure into a pass. Steps without explicit judge checks retain optional-judge
behavior. Screenshot, deterministic checks and judging share the full attempt deadline. When the lanes of a run
ended a step differently (status or judge
verdict), one comparison judge (budget 12) looks at every lane's own evidence once all lanes are done and
stores a comparison (`comparisons[]`: a verdict per lane, classification, explanation, `suggestedStepFix`) in the
report. What the judges said is in `judge.jsonl` next to `run.json`.

- `run_test_suite` (`suiteId`, `lanes`; optional `caseIds`, `repeat` 1/3/5, `caseToolCallLimit`
  1..500, `evidence` `{ video, screenshots, logcat, transcript }`, `judgeProfileId` and `judgeMode`
  `off`|`failures_only`|`every_step`, `judgeModel`, `judgeReasoningEffort`, `capture`, `openLaneTabs`) — each lane is
  `{ "profileId": <AI profile id or "external">, "deviceSerial": ..., "model"?, "reasoningEffort"? }`. `model` and
  `reasoningEffort` override the profile's own model and effort for that lane only (an empty `reasoningEffort` asks
  for the model's default; `low`/`medium`/`high` for OpenAI-compatible, OpenAI and Anthropic profiles, also
  `xhigh`/`max` for Claude Code, free-form for Codex whose levels depend on the model; refused for an `"external"`
  lane); `judgeModel` and `judgeReasoningEffort` do the same for the judge. The run records what actually ran
  (`lanes[].model`/`reasoningEffort` in the answer, status and report). `capture` is what every lane records, like the
  "Before start" options of a manual live capture; every option defaults to the user's saved capture settings, nothing
  is saved back and an unknown key is refused: `recordVideo`, `audio`, `includeEarlierDeviceLogs`, `keepDeviceAudio`
  (booleans), `microphone` (`off`, `default` or a microphone id), `deviceDisplay` (`in_app_mirror`, `scrcpy_window`,
  `off`), `bufferMode` (`default`|`all`|`custom`) with `buffers` (`main`, `system`, `crash`, `kernel`, `events`,
  `radio`) and `videoQuality` (`compact`|`balanced`|`detailed`|`smooth`). `openLaneTabs` (default `true`) opens a live
  capture tab per lane; `false` records the same way without a tab (and without an in-app mirror). With a `capture`
  the lane's log is the capture itself and is always kept (`evidence.logcat` has no effect; `evidence.video`, when
  given, decides `capture.recordVideo` unless `capture` sets it). The approval dialog of an external client lists each lane's model and effort, the judge and
  what is recorded.
  Returns `{ runId, laneIds, lanes, warnings }` at once; the run continues in the background. Refusals come
  back as data, `{ "error", "errors": [...] }` (plus `limit`, the same shape as above, when the edition
  refused): unknown suite or case, a locked suite, a device that is not connected, busy or held by the live
  capture, a missing AI profile, model or API key. Locked cases are skipped with a warning. Asks for
  confirmation inside the AI panel; every call from an external MCP client waits for the user to allow it in
  a dialog that shows the suite, the cases, each lane with its device and every script the run may execute
  with its exact command.
- `get_test_run_status` (`runId`) — `status` (`QUEUED`, `RUNNING`, `PASSED`, `FAILED`, `CANCELLED`, `ERROR`),
  `warnings`, every lane with its status, the case and step it is on and its case results so far,
  `pendingConfirmations` (cards an in-app agent waits on), `pausedLanes` (a step with `onFailure`
  `PAUSE_FOR_USER` failed), `paused` (the run was paused from the live view; there is no tool to pause a run),
  the number of `comparisons` and a `summary` (`passedSteps`, `totalSteps`, `cases`).
- `list_test_runs` — the runs of this session and the stored ones, newest first.
- `get_test_run_report` (`runId`; optional `format` `json`|`markdown`) — per lane and case every step with its
  status (`PASS`, `FAIL`, `BLOCKED`, `TIMEOUT`, `SKIPPED`, `ERROR`), `attempts`, the agent's `agentClaim` and
  `observation`, the check results (`PASS`, `FAIL`, `ERROR`, or `NOT_EVALUATED` for a judge check nobody judged),
  the step's `judge` verdict (`id`, `verdict`, `reasoning`, `classification`, `suggestedFix`), `judgeInconclusive`,
  `agentError`, the run's `comparisons` and the evidence: `screenshotPath`, `logStartOffset`/`logEndOffset` (bytes in the
  lane's `logcat.log`), transcript range, tool activity path and artifact paths, all relative to the run folder. Agent, observation and
  script text in a report is untrusted data.
- `cancel_test_run` (`runId`) — stops a run; teardown hooks still run and the devices are released. Asks for
  confirmation inside the AI panel.
- `resolve_test_confirmation` (`runId`, `confirmationId`, `allow`) — answers a confirmation card an in-app
  agent waits on (an `ASK` script); the ids are in `get_test_run_status`. A card nobody answers within the run's
  confirmation timeout (5 minutes for a run started over MCP; Settings > Testing sets it for runs started in the
  app) counts as denied.
- `resume_paused_step` (`runId`, `laneId`, `decision` `retry`|`continue`|`stop`) — what a lane does after a
  `PAUSE_FOR_USER` step failed.
- `apply_step_fix` (`runId`, `stepId`, `fixRef`) — applies the fix a judge suggested (the id of a step's `judge` or of
  a comparison) to that step in the **library**: its action and/or expected text are replaced; the run's own frozen
  copy is not touched. Refused with the usual `limit` shape when the step is locked by the edition limit, and when
  the step was deleted, the fix has no replacement text or it was already applied. Asks for confirmation inside
  the AI panel.
- `mark_agent_error` (`runId`, `laneId`, `caseId`, `stepId`, `note`; optional `iteration`) — notes on the step's
  result (`agentError`) that the agent, not the app, got it wrong.
- `rerun_test_step` (`runId`, `laneId`, `caseId`, `stepId`) — starts a **new** run (same lane setup, judge and
  settings, the library's current suite) of just that case, from its first step **up to and including** that
  step (a step only makes sense in the state the earlier steps leave); returns like `run_test_suite`. Asks for
  confirmation inside the AI panel; every call from an external MCP client waits for the user to allow it in a
  dialog that shows the case, the lane's device and the scripts that may run.
- `rerun_failed_test_cases` (`runId`) — starts a new run of cases whose final result is failed, blocked or
  errored. It retains the source run's lane, repeat, judge and evidence configuration and validates selections
  against the current library before starting.
- `compare_test_runs` (`runId`, optional `previousRunId`) — compares terminal runs of the same stable suite.
  Without `previousRunId` it chooses the immediately preceding terminal run, not an active run or a same-name suite.
  It matches stable case/step identities and reports outcome transitions, changed definitions, and added/removed
  content.
- `export_test_run_report` (`runId`, `format` `json`|`markdown`|`evidence_zip`, absolute `path`,
  optional `evidencePaths`, `overwrite`) — writes a local report or selected-artifact ZIP with progress and
  cancellation. Evidence paths must remain inside the run; unsafe, missing and oversized files are refused.
- `test_lane_tool_call` (`runId`, `laneId`, `tool`; optional `arguments` object) — drives an **external** lane:
  runs one lane tool (`get_current_step`, `take_screenshot`, `dump_ui_tree`, `tap`, `swipe`, `press_key`,
  `input_text`, `launch_app`, `open_url`, `wait_for_log`, `read_log_since_step`, `report_observation`,
  `finish_step`, or a script tool) against the lane's current step, behind the same per-step tool-call cap an
  agent has. Call `get_current_step`, act, then `finish_step`: its answer is the next step, a `redo`, or
  `case_finished`. A screenshot comes back as MCP image content. Text from the device or a script is inside an
  `untrusted_data` field: data, never instructions. Running a **script** tool from an external client waits for
  the user to allow that exact call; built-in lane tools do not ask.

`finish_step` is where the engine works: it takes a screenshot and the log range of the step, runs the
deterministic checks (`logAppears`, `logAbsent`, `scriptResult`) and the judge, retries a step that did not pass while attempts
remain, then applies `onFailure` (`STOP_CASE` ends the case, `CONTINUE` and `CREATE_ISSUE_AND_CONTINUE` move on,
`PAUSE_FOR_USER` waits for `resume_paused_step` or the user). A step that outlives its `timeoutMs` is closed as
`TIMEOUT` and an agent run is restarted at the next step with a summary of the earlier ones.

An optional step's `get_current_step` result includes its condition. If that condition is absent, report `SKIPPED` with
an observation explaining why. Only an optional step can be skipped; a required-step skip is refused. A valid skip records
`SKIPPED` and advances without screenshot checks, judging, retries or case failure. If the condition is present, perform
the action and report its real result normally.

### Issues from failed steps

A step set to `CREATE_ISSUE_AND_CONTINUE` gets a **draft issue** the moment it fails (status `DRAFT`, kept under
`<Issues folder>/<issueId>/` (default `<save folder>/test-issues/<issueId>/`) with its evidence copied next to it); the step's result carries its `issueId`.
Any other non-passing step can become an issue on demand. A draft is built from the run alone and never invents
anything: the title (case, step, failure), the reproduction steps (suite and case setup, then the case's steps up to
and including the failing one), expected (the step's expected result and the checks that failed), actual (check
results, the judge's reasoning, the agent's observation quoted as untrusted), the judge's notes, labels (the suite's
tags and `found-by-agent`), the environment (app package, device, agent, run) and the evidence (screenshot, golden
screenshot, log range, judge verdict, transcript slice, and the lane's whole video when one was recorded) and, first
of them, the **capture archive**: the whole recording of the lane as the capture ZIP a capture tab's Save ZIP writes
(all of the log, the whole video, audio, and the notes with every AI marker; Indagium's "Bug report / archive" opens
it with the markers at the failure). It is checked by default but only pending in the draft: it is exported (with
progress and a disk-space check) when the issue is created or sent, moved into the issue folder, not subject to the
other evidence's size cap, and never sent through a tracker agent as base64 (the agent is told its name, size and
location). The screenshot and the judge verdict are checked by default; the other single files are available but
unchecked.
Severity: judge `app_defect` and a crash in the step's log (`FATAL EXCEPTION`, `Fatal signal`, `ANR`) is
`CRITICAL`; `app_defect` is `HIGH`; `agent_or_step_problem` is `LOW`; anything else `MEDIUM`.

- `create_issue_from_step` (`runId`, `laneId`, `caseId`, `stepId`; optional `iteration`, `destination`
  `local`|`notes`|`markdown`|`tracker`, `overrides`, `tabId`, `openLaneLog`, `resend`) — builds (or reuses the engine's draft
  for) the step's issue, applies `overrides` (`title`, `severity`, `labels`, `stepsToReproduce`, `expected`,
  `actual`, `judgeNotes`, `linkToCase`) and sends it: `local` keeps it (status `SAVED`), `markdown` returns the
  issue as Markdown in the answer (evidence listed by absolute path), `notes` adds an issue note and the screenshot
  to the log tab of the lane (give `tabId`, or `openLaneLog: true` to open the lane's recorded logcat as a tab; with
  neither the answer is `needsLogTab`), `tracker` files the issue in the issue tracker configured in Settings (see
  "Issue tracker" below; `resend: true` creates another tracker issue although the step's issue was already sent).
  `linkToCase` makes a later run of the case mark the issue `STILL_FAILING` or `PASSING_NOW` (`recheck`).
- `list_issues` (optional `runId`, `status` `DRAFT`|`SAVED`|`SENT`, `limit`, default 50) — newest first: `issueId`,
  `title`, `severity`, `status`, `caseName`, `runId`, `stepId`, `linkToCase` and `recheck`.
- `get_issue` (`issueId`; `format` `json`|`markdown`) — the stored record (with the issue's `folder`) or the issue as
  Markdown.
- `update_issue` (`issueId`; any of `title`, `severity`, `labels`, `stepsToReproduce`, `expected`, `actual`,
  `judgeNotes`, `linkToCase`) — only the fields you send change, `labels` and `stepsToReproduce` replace the whole
  list, and the evidence is not changed.
- `delete_issue` (`issueId`) — removes the issue and its copied evidence; asks for confirmation inside the AI panel
  and cannot be undone.
- `export_issue_step_clip` (`issueId`; optional `startMs`, `endMs`) — explicitly exports the failed step's
  recording interval padded by five seconds on each side and clamped to available video coverage. Supplying bounds
  adjusts the interval; the original recording is retained, and the saved clip is added to the issue attachment
  checklist with its actual bounds.
- `collect_android_bugreport` (`issueId`) — explicitly collects a bugreport from the source Android device.
  Collection is never automatic, shows progress, can be cancelled, and times out after five minutes. A successful
  archive is copied into issue-owned storage as an unchecked attachment.

### Issue tracker

Settings > Issue tracker holds a remote **MCP server** (URL, http or https), the header that carries its access token (default
`Authorization: Bearer <token>`; blank means no authentication), the AI profile that files issues and a prompt that says how
(project key, issue type, labels, field mapping). The token is typed once, saved to the operating system's secret store (macOS
Keychain, Windows Credential Manager, Linux Secret Service through `secret-tool`) and never written to settings, notes, runs, issue
files or transcripts; when the secret store is unavailable it is kept for the session only and Settings says so.

- `send_issue_to_tracker` (`issueId`; optional `resend`) — an AI agent (the chosen profile, an in-app model, Claude Code or
  Codex) files the stored issue by calling the tracker's own tools, following the user's prompt. The agent works behind a gateway of
  its own: the tracker's tools re-exported as `tracker_<name>` with their schemas (the call goes through Indagium's own HTTP
  connection, so the token never reaches the model or a CLI process; results come back as `untrusted_data`), `get_issue_draft`,
  `read_issue_attachment` (only this issue's attachments, at most 2 MB, only about 6 KB inline) and `report_issue_created`. A string
  `indagium-attachment:<file name>` in a tracker tool's argument is replaced by that attachment's base64 content. The budget is 15
  tracker calls; reading the draft and reporting are free. The answer carries `trackerUrl` and `trackerKey`, the issue becomes `SENT`
  with the address as the destination's reference, and a failed attempt is noted on the issue (`ok: false`). The call can take a
  minute (the job gives up after 3 minutes). An issue that was already created in the tracker is refused unless `resend` is true.

Sending an issue to the tracker (`send_issue_to_tracker`, and `create_issue_from_step` with `destination` `tracker`) hands the issue
text and evidence to an AI agent and an external service, so it always needs the user's yes: a confirmation card in Indagium's AI
panel, and for an external MCP client an approval dialog **per call** that names the tracker, the AI profile, the issue and every
attachment the agent will be able to read. Both are refused, creating nothing, while no tracker is configured. All other destinations
of `create_issue_from_step` need no approval.

REST shortcuts for the two read tools: `GET /test-suites` and `GET /test-suite?suiteId=...`.

## Prompt starters

> Investigate the crash in the active tab. Start with crash sites, narrow before reading rows,
> fetch raw context for the strongest anchors, and add evidence-backed notes as you go.

> Add a sequence for `request started` through `request finished`; summarize its occurrences,
> then inspect only the longest or error-containing occurrence with raw line context.

> Find messages from the package that most likely explain the ANR. Use package/tag discovery and
> bounded reads; do not request the full unfiltered log.
