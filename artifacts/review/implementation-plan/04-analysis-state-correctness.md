# Task 04 — Make analysis completion explicit

## Goal

Close finding P-02 by representing analysis as an explicit pending/completed/failed state. A valid completed result with zero crash sites must not be interpreted as missing work or recomputed during composition or each control-server request.

## Files likely affected

- `src/desktopMain/kotlin/com/openlog/model/Model.kt`
- `src/desktopMain/kotlin/com/openlog/ui/AppState.kt`
- `src/desktopMain/kotlin/com/openlog/ui/FilterPanel.kt`
- `src/desktopMain/kotlin/com/openlog/debug/ControlServer.kt`
- `src/desktopMain/kotlin/com/openlog/utils/StackTraceComputer.kt`
- `src/desktopTest/kotlin/com/openlog/AppStateBehaviorTest.kt`
- `src/desktopTest/kotlin/com/openlog/CrashPanelDetectionTest.kt`
- `src/desktopTest/kotlin/com/openlog/ControlServerTest.kt`

## Risk level

- Finding severity: **High**
- Implementation risk: **Medium**, because analysis state is consumed by both Compose UI and the control API.

## Expected behavior change

- Pending, completed-empty, completed-with-results, and failed states are distinguishable.
- Composition only renders current state; it does not perform full-log crash/stack analysis.
- Repeated API reads return the cached completed result for the same log revision.
- A log revision change invalidates the result once and schedules one new analysis.

## Tests/checks to run

- Add a call-counting fake analyzer and prove a crash-free log is analyzed once across recompositions and repeated API requests.
- Test invalidation after append, reload, filter-independent tab updates, cancellation, and analysis failure.
- Add a Compose recomposition trace or counter check showing zero analyzer invocations from composition after state is complete.
- Measure CPU time for 100 repeated crash-site API reads on a fixed large fixture before and after; after the first completion, reads should not scale with entry count.
- Run `./gradlew desktopTest --tests "com.openlog.CrashPanelDetectionTest"`.
- Run `./gradlew desktopTest --tests "com.openlog.ControlServerTest"`.
- Run the full verification suite documented in the plan index.

## Rollback notes

Introduce the state type and adapter at one boundary so the PR can be reverted without changing persisted data. If failure-state UI proves problematic, retain the explicit completed-empty state and temporarily map failures to the existing visible error path rather than restoring recomputation in composition.
