# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Git rules

- **Never `git push` without asking the user first.**
- Exception: after a branch has been merged into main, you may ask the user if they want to push main.

## Planning files

When the user asks to create a plan and save it as Markdown without specifying an exact folder,
save it in `temp_visuals_not_for_git/`. This directory is intentionally ignored by Git. Use a
user-specified location instead when one is provided.

## Versioning

The single source of truth for the app version is `app.version` in `gradle.properties`. Whenever that value changes, update in the same commit:
- The version badge at the top of `README.md`.
- The example `git tag vX.Y.Z` command in both `README.md` (Releasing section) and this file's Commands section — bump it to the next version.

Skipping this leaves the README showing a stale version after a release ships, which is confusing to anyone landing on the repo page.

## Commands

```bash
# Run the app
./gradlew desktopRun

# Run all tests
./gradlew desktopTest

# Run a single test class
./gradlew desktopTest --tests "com.indagium.LogParserTest"

# Run a single test method
./gradlew desktopTest --tests "com.indagium.AppStateBehaviorTest.startsWithNoOpenTabs"

# Build (compile + test)
./gradlew build

# Package distributable
./gradlew packageDmg        # macOS .dmg (local)
./gradlew packageDeb        # Linux .deb (run on Linux)
./gradlew packageMsi        # Windows .msi (run on Windows)

# Release (triggers GitHub Actions → builds Linux x86-64 + Linux arm64 + Windows + macOS → creates GitHub Release)
git tag v1.8.3 && git push --tags
```

Source sets are `desktopMain` and `desktopTest` (Kotlin Multiplatform with a single `jvm("desktop")` target).

## Architecture

Indagium is a Compose Multiplatform Desktop log viewer for Android logcat files. All code lives under `src/desktopMain/kotlin/com/indagium/` — ~50k lines across 12 packages.

> **`docs/SAAD.md` is the authoritative architecture document.** It covers module boundaries, the
> threading model, persistence formats, security posture, and known risks, with file:line citations.
> This section is a short index; when the two disagree, SAAD is newer.

### Data flow

```
File → LogParser.parseLogcat()  ──→  List<LogEntry>          (sequential by design; fast path + regex chain)
                                          ↓
                                   LogTab (in AppState.tabs)  ── analysis filled in a second phase
                                          ↓
              Filter.computeItems(tab, applyFilter, cancellationCheck)
                    ├─ memo cache: ConcurrentHashMap keyed "$tabId#$applyFilter"
                    ├─ spliceStackToggle fast path for a single group expand/collapse
                    └─ computeSeqGroups → manual ranges → renderRange (BitSet id sets)
                                          ↓
                                   List<LogItem>
                                          ↓
                             LogViewer (LazyColumn)
```

### Packages

| Package | Role |
|---------|------|
| `model` | All domain types (`Model.kt`, one file): `LogEntry`, `LogTab`, `Filter`, `Annotations`/`AnnBlock`, `LogAnalysis`, `LogItem`, `AppSettings`, `ThemePreset`. No behaviour. |
| `utils` | The log engine, UI-free: `LogParser`, `Filter` (`passesFilter`/`computeItems`/`buildMd`), `SeqComputer`, `StackTraceComputer`, `TextMatch` (regex cache + backtracking budget), `EntryIdMap`, `LogTime`, `LogMerge`, `LogSplitter`, `FileTailer`, `BugReportZip`, `AtomicFileWrite`, export helpers. |
| `ui` | Compose UI **and** `AppState` + coordinators + persistence codecs. The biggest package by far. |
| `source` | Source indexing and bounded source-first log→execution-trace resolution. Pure text/regex/brace-matching, no compiler dep. Own store at `appDataDir()/source-index` (`indagium-source-index-v1`, schema v18; also accepts the legacy `openLog2-source-index-v1` magic). |
| `cases` | Similarity index over previously written analysis notes; backs `search_similar_cases`. Store at `appDataDir()/case-index` (`indagium-case-index-v1`; also accepts the legacy `openLog2-case-index-v1` magic). |
| `diagram3` | UML sequence-diagram generation (v3), UI-free: `Seq3Generator` (log range -> `Seq3Document`: ranks tag activity into lifelines, infers a message's target only from adjacent-entry thread-handoff/correlation-token evidence — no source-index enrichment), `Seq3Layout` (the one geometry source shared by the Compose canvas and the raster), `Seq3Raster` (Graphics2D -> `BufferedImage`, headless PNG export), `Seq3Emitters` (Mermaid/PlantUML text), `Seq3Codec` (the `<!-- indagium:diagram3 v1 ... -->` note header). Stored as an ordinary `AnnBlock.Note` — no `.ann` format change. |
| `debug` | `ControlServer` (Ktor CIO, loopback-only, MCP over Streamable HTTP + REST, off by default), the **57-tool** catalogue + handlers joined by `IndagiumToolGateway`, hand-rolled `Json`, `AppLogger`. |
| `ai` | `LlmProvider` (Anthropic + OpenAI-compatible over HTTP) and the subprocess account agents (Codex stdio JSON-RPC, Claude Code stream-json), `AiAgentRunner` loop, `AiToolExecutionCoordinator` (the single policy point: budget, tab pinning, confirmation gate). |
| `video` | JavaCV/FFmpeg playback on a dedicated decode thread; per-tab controllers owned by `AppState`. |
| `voice` | Dictation: Whisper JNI, Apple Speech (build-time-compiled JNI bridge), Windows Speech helper. |
| `update` | GitHub Releases check and asset download. |
| `singleinstance` | File lock + loopback socket; forwards file args to a running instance. Skipped on macOS. |

### Files you'll touch most

| File | Role |
|------|------|
| `ui/AppState.kt` | 6k lines. All mutable state + most behaviour. Mutate via `upTab`/`upFlt`/`upAnn` — see below |
| `ui/App.kt` | Root composable: layout routing, every dialog, drag-and-drop, global keys, the 400 ms autosave debounce |
| `ui/LogViewer.kt` | The row list; the only production caller that passes a `cancellationCheck` |
| `ui/FilterPanel.kt` | Left sidebar; bound to `AppState` by `BoundFilterPanel` in `ui/FileView.kt` |
| `ui/AnnotationPanel.kt` + `ui/AnnotationManager.kt` | Notes UI and its block-model mutations |
| `ui/AutosaveCodec.kt` | The on-disk format. **Append new token fields last** — see the versioning note below |
| `debug/ControlServer.kt` + `debug/IndagiumToolOperations.kt` | Tool catalogue and handlers. Names must match or `IndagiumToolGateway`'s `init` throws |
| `ui/Components.kt` / `ui/Theme.kt` | Shared widgets; `ThemeColors`, `themeColors()`, `HL_COLORS`, `SEQ_COLORS` |

### Two invariants worth knowing before editing

- **`stateLock`.** Every read-modify-write of `AppState.tabs` goes through `synchronized(stateLock)`. `upTab`/`upFlt`/`upAnn` already do this; new mutators must too. `AutosaveScheduler`'s locks must never be held together with it.
- **Append-last token versioning.** Autosave token records are positional, `|`-joined, decoded with `getOrNull`. A new field goes at the **end** of the token; inserting one in the middle breaks every existing autosave. The legacy positional *settings* decoder is frozen by `AutosaveGoldenV1Test` — new settings go into the JSON form only.

### AppState

`AppState` is a plain class (not a ViewModel). All fields are `mutableStateOf`. Pattern for mutation:

```kotlin
fun upTab(tabId: String, fn: (LogTab) -> LogTab) { tabs = tabs.map { if (it.id == tabId) fn(it) else it } }
fun upFlt(tabId: String, fn: (Filter) -> Filter) = upTab(tabId) { it.copy(filter = fn(it.filter)) }
```

File loading uses `ioScope` (`Dispatchers.IO`). Compose `mutableStateOf` is snapshot-safe to write from any thread — **no `withContext(Dispatchers.Main)` needed or used**.

Autosave triggers via `LaunchedEffect` with a 400ms debounce on tab/filter/settings changes, writing to `<appDataDir>/autosave.cache` in a line-oriented token format (`indagium-cache-v1`; a load also accepts the legacy `openLog2-cache-v1` magic). `appDataDir()` is platform-specific — see `ui/DesktopStorage.kt` — e.g. `~/Library/Application Support/Indagium` on macOS, `%APPDATA%\Indagium` on Windows, `$XDG_STATE_HOME/Indagium` (fallback `~/.local/state/Indagium`) on Linux.

### LogItem sealed class

`computeItems()` maps filtered `List<LogEntry>` → `List<LogItem>`:
- `LogItem.Row` — plain log line (with optional indent and group color)
- `LogItem.SeqHeader` — collapsible sequence group header
- `LogItem.ManualHeader` — manually-created collapse block header

### Compose Desktop gotchas in this codebase

- **`DialogActionButton` enabled vs active**: `active` controls highlight style; `enabled` controls interactivity. They differ for secondary buttons — "Cancel" uses `active=false` for grey styling but stays enabled; "Update existing" uses `enabled=current!=null` to truly block clicks when no preset is loaded.
- **Drag-and-drop**: use `Modifier.dragAndDropTarget` (not AWT `DropTarget`, which conflicts with Compose's DnD on macOS).
- **Retina/HiDPI**: pointer deltas from `pointerInput` are in pixels; divide by `LocalDensity.current.density` to get dp before updating layout state.
- **HDivider/VDivider**: track `dragging` with a separate `MutableState<Boolean>` to suppress the hover-highlight flicker that occurs when the cursor leaves the hit target during a drag.
- **ID collisions across tabs**: `LogParser` starts IDs from 1 per file. `pointerInput` keys and `rowBoundsAbs` maps must include `tab.id` to avoid cross-tab collisions.
- **LazyColumn horizontal scroll**: wrap `LazyColumn` in a `Box` with `horizontalScroll`, and give items `widthIn(min = 2000.dp)` so all columns stay aligned.
- **`Dialog(onDismissRequest = ...)` width clamp**: defaults to `usePlatformDefaultWidth = true`, which silently caps content to a ported-from-Android "preferred dialog width" (580dp on a window this size — see `RootMeasurePolicy.skiko.kt`'s `preferredDialogWidth`) no matter how wide the composable inside requests. Any `Dialog` meant to be wider than ~580dp needs `properties = DialogProperties(usePlatformDefaultWidth = false)` (see `SettingsDialog`'s call site in `App.kt`) — otherwise every width tweak on the inner content is silently a no-op.
- **A dismissed `Popup`/clicked `Modifier.clickable` steals keyboard focus and never gives it back**: `Modifier.clickable` (checkboxes, dropdown pills, action-bar buttons) is focusable by default, so clicking one moves keyboard focus onto it; dismissing a `Popup` (e.g. a dropdown built on `Modifier.clickable` + `Popup`) doesn't return focus to whatever `Modifier.focusRequester(...)` owned it before. A screen with its own root-level key handler (`onPreviewKeyEvent`/`onKeyEvent`) then silently stops receiving keys — Esc included — after the very first click on any clickable child. Fix: after handling the click (or the `Popup`'s `onDismissRequest`), call that root's `FocusRequester.requestFocus()` again (wrap in `runCatching` — it throws if the requester isn't attached yet). See `ui/Seq3Workspace.kt`'s `LocalSeq3FocusRequester`/`Seq3ViewState.focusRequester` for the pattern: the requester lives on the shared view-state object so every composable that already receives it (row click handlers, a shared dropdown button) can reclaim focus without a new parameter threaded through every call site.

## IDEA MCP

The JetBrains IDEA MCP is available for this project (`mcp__idea__*` tools). Use it for:
- Building the project: `mcp__idea__build_project`
- Running the app or tests via run configurations: `mcp__idea__execute_run_configuration`
- Finding files and symbols: `mcp__idea__find_files_by_glob`, `mcp__idea__search_symbol`
- Checking compilation errors: `mcp__idea__get_file_problems`
