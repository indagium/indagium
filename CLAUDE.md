# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Git rules

- **Never `git push` without asking the user first.**
- Exception: after a branch has been merged into main, you may ask the user if they want to push main.

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
./gradlew desktopTest --tests "com.openlog.LogParserTest"

# Run a single test method
./gradlew desktopTest --tests "com.openlog.AppStateBehaviorTest.startsWithNoOpenTabs"

# Build (compile + test)
./gradlew build

# Package distributable
./gradlew packageDmg        # macOS .dmg (local)
./gradlew packageDeb        # Linux .deb (run on Linux)
./gradlew packageMsi        # Windows .msi (run on Windows)

# Release (triggers GitHub Actions → builds Linux + Windows + macOS → creates GitHub Release)
git tag v1.5.3 && git push --tags
```

Source sets are `desktopMain` and `desktopTest` (Kotlin Multiplatform with a single `jvm("desktop")` target).

## Architecture

openLog is a Compose Multiplatform Desktop log viewer for Android logcat files. All code lives under `src/desktopMain/kotlin/com/openlog/`.

### Data flow

```
File → LogParser.parseLogcat() → List<LogEntry>
                                       ↓
                               LogTab (in AppState.tabs)
                                       ↓
                    Filter.computeItems(tab, sequences) → List<LogItem>
                                       ↓
                               LogViewer (LazyColumn)
```

### Key files

| File | Role |
|------|------|
| `Main.kt` | Entry point: `application { Window { App(appState) } }` |
| `model/Model.kt` | All data types: `LogEntry`, `LogTab`, `Filter`, `LogItem`, `SequenceDef`, `AnnBlock`, etc. |
| `ui/AppState.kt` | Central mutable state — tabs, filters, sequences, annotations, autosave. All `mutableStateOf` fields. |
| `ui/App.kt` | Root composable — drag-and-drop, context menu, dialogs, routes between FileView and CompareView |
| `ui/LogViewer.kt` | Log display with `LazyColumn`, horizontal scroll, row selection, drag-select |
| `ui/FilterPanel.kt` | Left sidebar — log levels, tag filters, sequences, highlighters, message rules |
| `ui/AnnotationPanel.kt` | Right panel — block-based annotations exported as Markdown |
| `ui/Components.kt` | Shared widgets: `HDivider`/`VDivider` (resizable), `ColHeader`, `AppText` |
| `ui/Theme.kt` | `ThemeColors`, `themeColors()`, `HL_COLORS`, `SEQ_COLORS` palettes |
| `utils/LogParser.kt` | Parses 4 logcat formats: threadtime, time, brief, bare. Unrecognised lines become tag=`RAW`. |
| `utils/Filter.kt` | `passesFilter()`, `computeItems()` (builds `List<LogItem>` with sequence/manual headers), `buildMd()` |
| `utils/SeqComputer.kt` | `computeSeqGroups()` — nesting algorithm for sequence detection |
| `source/SourceIndexer.kt` | Scans registered source folders (`.kt`/`.java`) for `Log.*`/Timber call sites — extracts message template, resolves `TAG`, finds enclosing method by brace-matching. Pure, no parser dep. |
| `source/LogSourceResolver.kt` | Maps a log line's `(tag, msg)` back to source call site(s): tag-bucketed regex match, generic-literal suppression, confidence ranking. |
| `source/SourceIndexStore.kt` | Persists the index to `appDataDir()/source-index` (`openLog2-source-index-v1` token format), separate from autosave. |
| `source/SourceModel.kt` | Index data types: `LogCallSite`, `SourceIndex`, `SourceMatch`, `SourceIndexStatus`, `SourceCodeView`. |
| `ui/SourceCodeDialog.kt` | "Show in code" popup — the resolved method's source with V+H scrollbars, path, line range, Open-in-default-app. |
| `debug/ControlServer.kt` | Localhost MCP + REST control server (41 tools incl. `resolve_log_source`, `get_project_info`, `set_highlighters`, `reindex_sources`, `add_manual_collapse`, `add_sequence`, `save_filter_preset`). Off by default; enabled via Settings. |

### AppState

`AppState` is a plain class (not a ViewModel). All fields are `mutableStateOf`. Pattern for mutation:

```kotlin
fun upTab(tabId: String, fn: (LogTab) -> LogTab) { tabs = tabs.map { if (it.id == tabId) fn(it) else it } }
fun upFlt(tabId: String, fn: (Filter) -> Filter) = upTab(tabId) { it.copy(filter = fn(it.filter)) }
```

File loading uses `ioScope` (`Dispatchers.IO`). Compose `mutableStateOf` is snapshot-safe to write from any thread — **no `withContext(Dispatchers.Main)` needed or used**.

Autosave triggers via `LaunchedEffect` with a 400ms debounce on tab/filter/settings changes, writing to `~/.openlog2/autosave.cache` in a line-oriented token format (`openLog2-cache-v1`).

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

## IDEA MCP

The JetBrains IDEA MCP is available for this project (`mcp__idea__*` tools). Use it for:
- Building the project: `mcp__idea__build_project`
- Running the app or tests via run configurations: `mcp__idea__execute_run_configuration`
- Finding files and symbols: `mcp__idea__find_files_by_glob`, `mcp__idea__search_symbol`
- Checking compilation errors: `mcp__idea__get_file_problems`
