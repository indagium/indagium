# Task 09 — Serialize persistence and improve diagnostics

## Goal

Close finding P-06 by funneling autosave/settings writes through one debounced writer, replacing destination files atomically, surfacing export/persistence failures, and adding redacted, size-limited production diagnostics.

## Files likely affected

- `src/desktopMain/kotlin/com/openlog/ui/AppState.kt`
- `src/desktopMain/kotlin/com/openlog/ui/DesktopStorage.kt`
- `src/desktopMain/kotlin/com/openlog/ui/App.kt`
- `src/desktopMain/kotlin/Main.kt`
- `build.gradle.kts`
- `src/desktopTest/kotlin/com/openlog/AppStateBehaviorTest.kt`
- New focused storage tests under `src/desktopTest/kotlin/com/openlog/`

## Risk level

- Finding severity: **Medium**
- Implementation risk: **Medium**, because persistence timing and shutdown flush behavior change.

## Expected behavior change

- Resize and rapid state changes coalesce instead of synchronously writing on each delta.
- Writes are serialized and committed via temporary file plus atomic move where supported.
- Shutdown performs a bounded final flush.
- Persistence/export failures are visible to the user and logged without raw log contents, credentials, or unnecessary full paths.
- A single explicit logging backend captures actionable production failures with rotation/size limits.

## Tests/checks to run

- Use a fake storage sink to test debounce, write ordering, concurrent updates, final shutdown flush, injected write/move failure, and recovery from a leftover temporary file.
- Verify redaction for source paths, control credentials, and log contents.
- Add a compatibility test that current persisted data still restores after the writer change.
- Script a sustained resize/state-update burst and record UI-thread blocked time, writes per second, bytes written, and allocation rate before and after.
- Acceptance target: writes coalesce to the documented rate, no synchronous filesystem work occurs on the UI path, and final persisted state matches the latest revision.
- Run `./gradlew desktopTest --tests "com.openlog.AppStateBehaviorTest"`.
- Run the full verification suite documented in the plan index.

## Rollback notes

Avoid changing the persistence schema in this PR. The serialized writer can then be reverted without migration. If the new logging backend causes packaging issues, revert the backend dependency separately but keep surfaced user errors and atomic persistence behavior.
