# Task 12 — Mechanically decompose large UI/state files

## Goal

After behavioral fixes are stable, reduce the size and coupling of `AppState` and `App` through behavior-preserving extraction only. Separate server management, loading/tailing coordination, persistence, dialog/UI adapters, and focused Compose sections without changing public behavior or storage formats.

## Files likely affected

- `src/desktopMain/kotlin/com/openlog/ui/AppState.kt`
- `src/desktopMain/kotlin/com/openlog/ui/App.kt`
- `src/desktopMain/kotlin/com/openlog/ui/FilterPanel.kt`
- `src/desktopMain/kotlin/com/openlog/ui/LogViewer.kt`
- New focused files under `src/desktopMain/kotlin/com/openlog/ui/` and, where ownership is non-UI, an appropriate existing package
- Existing tests under `src/desktopTest/kotlin/com/openlog/`; new characterization tests only where extraction reveals an uncovered contract

## Risk level

- Review severity: **Low maintainability/style cleanup**
- Implementation risk: **Medium** due to the volume of moved code, even though behavior must not change.

## Expected behavior change

- **None.** UI, shortcuts, persistence schema, control API, file formats, filtering results, timing contracts, and resource ownership established by Tasks 01-11 remain unchanged.
- Dependencies become explicit and components can be unit-tested without constructing the entire application state.
- Callback groupings may become typed action interfaces, but their externally visible effects remain identical.

## Tests/checks to run

- Before moving code, capture focused characterization tests for every extracted boundary.
- Keep moves mechanical: use symbol-aware move/refactor operations where possible and inspect the diff for logic changes.
- Run IDEA inspections on every extracted/new file.
- Run `git diff --check` and verify no unrelated formatting churn.
- Run `./gradlew desktopTest`, `./gradlew detekt ktlintCheck`, and `./gradlew build` after each extraction slice.
- Compare existing large-file performance harness results before/after to ensure the refactor alone does not regress latency or allocation; no improvement claim is required for this mechanical task.

## Rollback notes

Split the work into independently revertible extraction commits: server manager, load/tail coordinator, persistence service, dialog adapter, then Compose sections. Revert only the problematic extraction. Do not mix cleanup with model/schema/dependency changes, so rollback requires no migration.
