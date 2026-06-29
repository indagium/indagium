# 03 Compose Desktop UI and State

Reviewed:
- `App.kt`
- `LogViewer.kt`
- `FilterPanel.kt`
- `AnnotationPanel.kt`
- `Components.kt`

The UI is feature-rich and has regression tests for a number of helper functions. The main Compose Desktop concerns are expensive work during recomposition, heavy UI callback wiring, and UI callbacks doing synchronous IO.

## Findings

### OL2-004

Severity: medium

Area: Compose Desktop performance and state

File path: `src/desktopMain/kotlin/com/openlog/ui/LogViewer.kt`

Symbol/function/class if known: `LogViewer`

Problem: `LogViewer` computes filtered items synchronously in composition using `remember(...) { computeItems(...) }`. `computeItems` can filter all rows, run regex matching, compute sequence groups, and build item lists. `FilterPanel` also computes message/PID suggestions over `tab.logData` in composition.

Why it matters: For large logcat files, changing a filter, typing into a search field, toggling sequences, or opening split view can block the UI thread and cause jank or apparent freezes.

Suggested fix: Move expensive filtering/sequence/suggestion work into a debounced background pipeline, cache compiled regexes, and expose computed UI lists through state. At minimum, precompile regexes per filter/rule/sequence before iterating rows.

Suggested test: Add a performance-oriented regression test around filtering/sequence computation with a large synthetic log and regex-heavy filters; keep UI thread work bounded.

Confidence: high

### OL2-009

Severity: medium

Area: Compose Desktop UI responsiveness

File path: `src/desktopMain/kotlin/com/openlog/ui/AppState.kt`

Symbol/function/class if known: `saveAnalysis`, `openNoteFile`, `exportFiltersToFile`, `importFiltersFromFile`

Problem: File reads/writes for saving analysis, loading notes, exporting filters, and importing filters run synchronously from UI callbacks.

Why it matters: File dialogs are expected to block, but file IO after selection can still freeze the Compose event thread if the note/filter file is large or the target is a slow network/cloud folder.

Suggested fix: Move post-dialog read/write work to an IO coroutine and publish completion/errors back through state. Keep the dialog interaction itself on the UI thread.

Suggested test: Inject a fake file store that suspends, then verify the UI state can show loading/error without blocking the caller.

Confidence: medium

## No Serious Issue Found

- `remember` keys in `LogViewer` include tab id and state relevant to visible items.
- Scroll state is intentionally scoped per tab/panel and has tests.
- Divider drag deltas account for density, matching known Compose Desktop HiDPI behavior.
