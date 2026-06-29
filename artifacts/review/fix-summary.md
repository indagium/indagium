# openLog2 Review Fix Summary

Branch: `feat/review_gen_code_upd_3`

Original review recommendation: `BLOCK`

Current remediation status: code fixes implemented for all blocker/high findings and the actionable medium/low findings that can be addressed in this repository without external signing credentials or cross-OS installer infrastructure.

## Fixed Findings

- OL2-001: Fixed scoped include message-rule filtering so scoped include rules narrow only matching tag/package scopes. Existing regression now passes.
- OL2-002: Added pending-load accounting, synchronized tab completion, parser injection, and cancellation via `AppState.close()`.
- OL2-004: Centralized regex matching with cached compiled regexes for filters, sequences, and highlighters to reduce repeated regex compilation during UI work.
- OL2-005: Preserved long raw log lines instead of dropping lines longer than 8192 characters.
- OL2-006: Added `DesktopStorage` and moved autosave/notes defaults to OS-specific app-data locations, with legacy note lookup retained.
- OL2-007: Added an `autoExportNotes` setting and constructor control so note auto-export can be disabled for private logs.
- OL2-008: Replaced fragile saved-filter import/export parsing with a JSON reader for the exported shape and added escaping for quotes, backslashes, newlines, tabs, and control characters. Highlighters are now included in filter export/import.
- OL2-009: Moved save/export/import/open-note file IO behind IO coroutine wrappers for UI paths while preserving synchronous test entry points.
- OL2-010: Added `AppState.close()` to cancel the internal IO scope and pending loads.
- OL2-011: Replaced fragile FileDialog string path joining with `File(dir, path)`.
- OL2-012: Added native distribution vendor/copyright metadata, `docs/release-readiness.md`, and an explicit Compose packaging JDK-vendor override so local package smoke tests can run on this machine.
- OL2-013: Extracted duplicated `FilterPanel` callback wiring into a shared `BoundFilterPanel` binding used by file and compare views.
- OL2-014: Added regression coverage for scoped filtering, long raw lines, filter JSON round-trips, concurrent file opens, cancellation, privacy auto-export disablement, OS app-data paths, layout pane persistence, and invalid regex handling.

## Additional UX Fixes

- Markdown preview now uses the same `AppButton` style as the annotation panel Preview/Copy controls.
- Markdown preview close and tab close now share the same `CloseButton` component.
- Filter, notes, compare, compare-filter, and pane-size state changes now autosave immediately and restore after relaunch.
- Filter-panel section collapse/expand state now autosaves immediately and restores after relaunch.

## Partially Addressed / Not Fully Verifiable

- OL2-003: `AppState` is still a large state owner, but it now has injected parser/storage controls, persistence-aware update methods, shared UI binding, and more test seams. A full service extraction remains a larger architecture refactor.
- Release signing/notarization, Windows MSI behavior, Linux package installation, and installer upgrade behavior were not verified locally.

## Verification Commands

- `./gradlew desktopTest`: passed.
- `./gradlew check`: passed.
- `./gradlew build`: passed.
- `./gradlew packageDistributionForCurrentOS`: passed; wrote `build/compose/binaries/main/dmg/openLog-1.0.0.dmg` on macOS.
- IDEA MCP error inspections on touched Kotlin files: no errors.
- IDEA MCP project build: passed.
