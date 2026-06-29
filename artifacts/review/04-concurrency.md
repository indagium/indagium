# 04 Coroutines, Lifecycle, and Concurrency

Reviewed:
- `AppState.kt`
- `App.kt`
- `LogViewer.kt`

The app uses an internal `CoroutineScope(SupervisorJob() + Dispatchers.IO)` in `AppState` for file loading and annotation auto-export. The most serious concurrency issue is overlapping file loads updating shared snapshot state without a single serialized owner.

## Findings

### OL2-002

Severity: high

Area: Coroutines and shared mutable state

File path: `src/desktopMain/kotlin/com/openlog/ui/AppState.kt`

Symbol/function/class if known: `openFile`

Problem: `openFile` launches a new IO coroutine for each file and then mutates `tabs`, `activeTabId`, and `isLoading` from those coroutines. Concurrent loads do `tabs = tabs + t`, which is a read-modify-write on shared state. `isLoading` is a single boolean, so the first completed load sets it false even if other loads are still running.

Why it matters: Dropping/opening multiple files can lose tabs, set the active tab nondeterministically, or hide the loading overlay early. This is a likely user-visible race because drag/drop loops call `openFile` once per dropped file.

Suggested fix: Serialize file-open completion on a single app/main state owner, or protect the tab update with a mutex/reducer. Track loading as a count or set of pending file ids. Consider injecting a file loader and returning results to the state reducer.

Suggested test: Add a fake parser/loader that completes files in controlled order, open multiple files concurrently, and assert that all expected tabs are present and loading remains true until the final completion.

Confidence: high

### OL2-010

Severity: medium

Area: Coroutine lifecycle

File path: `src/desktopMain/kotlin/com/openlog/ui/AppState.kt`

Symbol/function/class if known: `ioScope`

Problem: `AppState` creates an internal `CoroutineScope(SupervisorJob() + Dispatchers.IO)` but exposes no close/cancel path, and `Main.kt` remembers `AppState` without disposing that scope.

Why it matters: In normal process exit this may not leak for long, but tests, preview-like reuse, future multi-window support, or window recreation can leave background work running after the UI state is no longer visible.

Suggested fix: Make `AppState` closeable or accept an external scope owned by the application lifecycle. In Compose, cancel it from `DisposableEffect` when the state leaves composition.

Suggested test: Inject a test scope, dispose/close state, and assert pending file-load/export jobs are cancelled.

Confidence: medium

## No Serious Issue Found

- No `GlobalScope` usage was found.
- `rememberCoroutineScope` in `LogViewer` is used for UI scroll animation and is lifecycle-bound to composition.
