# Task 11 — Establish a single state-mutation boundary

## Goal

Close finding A-01 by routing tab state and resource lifecycle mutations from Compose/AWT, single-instance callbacks, Ktor handlers, and I/O jobs through one explicit owner. Remove unsynchronized global ID allocation and plain mutable resource-map races.

## Files likely affected

- `src/desktopMain/kotlin/com/openlog/ui/AppState.kt`
- `src/desktopMain/kotlin/Main.kt`
- `src/desktopMain/kotlin/com/openlog/debug/ControlServer.kt`
- `src/desktopMain/kotlin/com/openlog/singleinstance/SingleInstance.kt`
- `src/desktopMain/kotlin/com/openlog/model/Model.kt`
- `src/desktopTest/kotlin/com/openlog/AppStateBehaviorTest.kt`
- `src/desktopTest/kotlin/com/openlog/ControlServerTest.kt`
- `src/desktopTest/kotlin/com/openlog/SingleInstanceTest.kt`

## Risk level

- Finding severity: **Medium**
- Implementation risk: **High**, because this changes ordering across most app entry points.

## Expected behavior change

- State mutations are serialized through a documented dispatcher/actor/store boundary.
- Tab IDs are allocated by the state owner and remain unique under concurrent opens.
- Active loads, tailers, close operations, and server-triggered actions transition atomically and dispose exactly once.
- Read-only snapshots used by Compose remain observable without exposing mutable resource maps.

## Tests/checks to run

- Add concurrent stress tests for many file-open requests from server, single-instance, and UI entry points; assert unique IDs and deterministic tab/resource counts.
- Race open/close, tail/close, reload/close, and shutdown with delayed fake I/O; assert no stale publish and exactly-once cancellation/close.
- Run existing restore, split-view, tab, control-server, and single-instance regression suites.
- Run a coroutine-debug or thread-confinement test that fails when a protected mutation occurs outside the owner.
- Run `./gradlew desktopTest --tests "com.openlog.AppStateBehaviorTest"`.
- Run `./gradlew desktopTest --tests "com.openlog.ControlServerTest"`.
- Run `./gradlew desktopTest --tests "com.openlog.SingleInstanceTest"`.
- Run the full verification suite documented in the plan index.

## Rollback notes

Introduce the boundary behind existing `AppState` methods rather than rewriting callers and domain layout simultaneously. If ordering regressions arise, revert the dispatch boundary as one unit. Do not leave half the resource maps actor-owned and half directly mutable.
