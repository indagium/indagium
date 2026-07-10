# Task 02 — Linearize control-server lifecycle

## Goal

Close finding S-02 by ensuring enable, disable, restart, and application shutdown transitions are serialized. A stale asynchronous start must never publish a server after the user has disabled it.

## Files likely affected

- `src/desktopMain/kotlin/com/openlog/ui/AppState.kt`
- `src/desktopMain/kotlin/com/openlog/debug/ControlServer.kt`
- `src/desktopMain/kotlin/Main.kt`
- `src/desktopTest/kotlin/com/openlog/AppStateBehaviorTest.kt`
- `src/desktopTest/kotlin/com/openlog/ControlServerTest.kt`

## Risk level

- Finding severity: **Medium**
- Implementation risk: **Medium**, because startup and shutdown ordering changes.

## Expected behavior change

- At most one start/stop transition is active at a time.
- Disabling during a slow start cancels or invalidates that start; no listener remains bound afterward.
- Repeated enable requests reuse or replace the current instance deterministically rather than racing multiple engines.
- App shutdown awaits or cancels the lifecycle job and closes the bound server exactly once.

## Tests/checks to run

- Introduce a controllable fake server factory with delayed start and observable close calls.
- Test enable → disable before start completes, enable → enable, disable → disable, failed start → retry, and application disposal during start.
- Assert no stale handle is published and no more than one server is live in each transition.
- Run `./gradlew desktopTest --tests "com.openlog.AppStateBehaviorTest"`.
- Run `./gradlew desktopTest --tests "com.openlog.ControlServerTest"`.
- Run the full verification suite documented in the plan index.

## Rollback notes

Keep the lifecycle change isolated behind the existing server-manager entry points. If rollback is required, revert the lifecycle coordinator and its tests together. Leave Task 01 authentication intact; disabling the server is the safe temporary mitigation if lifecycle reliability regresses.
