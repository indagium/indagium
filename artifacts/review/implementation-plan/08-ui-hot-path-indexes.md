# Task 08 — Remove interactive full-list scans

## Goal

Close findings P-05 and P-07 by moving full-list work off input handlers/composition, maintaining revision-aware lookup indexes, and invalidating PID/TID availability when tailed data changes.

## Files likely affected

- `src/desktopMain/kotlin/com/openlog/ui/LogViewer.kt`
- `src/desktopMain/kotlin/com/openlog/ui/FilterPanel.kt`
- `src/desktopMain/kotlin/com/openlog/ui/TagSuggestions.kt`
- `src/desktopMain/kotlin/com/openlog/ui/AppState.kt`
- `src/desktopMain/kotlin/com/openlog/model/Model.kt`
- `src/desktopMain/kotlin/com/openlog/utils/EntryIdMap.kt`
- `src/desktopTest/kotlin/com/openlog/TagSuggestionTest.kt`
- `src/desktopTest/kotlin/com/openlog/KeyboardNavigationTest.kt`
- `src/desktopTest/kotlin/com/openlog/LargeFilePerfHarness.kt`

## Risk level

- Finding severity: **Medium** and **Low**
- Implementation risk: **Medium**, because selection, navigation, and suggestion ordering must stay identical.

## Expected behavior change

- Arrow-key navigation and drag selection use stable ID/index maps instead of repeated list scans.
- Expensive item/suggestion computation is scheduled by measured cost and cannot block the UI thread for large inputs.
- PID/TID controls appear when appended data introduces those fields without requiring a tab reopen.
- Visible ordering, selection anchors, keyboard behavior, and suggestion semantics remain unchanged.

## Tests/checks to run

- Characterization tests for keyboard navigation, drag-selection boundaries, missing IDs, filtered/unfiltered transitions, and suggestion ordering.
- Add a tailing test where initial entries lack PID/TID and later entries include them.
- Use Compose tracing or a scripted input harness on fixed 100k/500k/1M-row fixtures. Record key-event-to-selection latency, suggestion latency, UI-thread blocked time, recomposition counts, and allocation rate.
- Acceptance target: lookup-driven key/drag operations do not scale linearly with total row count, no full scan appears on the UI-thread trace for those actions, and output parity tests pass.
- Run `./gradlew desktopTest --tests "com.openlog.KeyboardNavigationTest"`.
- Run `./gradlew desktopTest --tests "com.openlog.TagSuggestionTest"`.
- Run the full verification suite documented in the plan index.

## Rollback notes

Keep indexes derived and revision-keyed so they can be removed without changing persisted models. If parity regressions occur, revert one interaction path at a time while retaining measurements; do not keep stale indexes with fallback scans that can silently disagree.
