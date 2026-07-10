# Task 05 — Conflate and cancel filter computation

## Goal

Close finding P-01 by giving each tab at most one active item-computation pipeline. New filter input should supersede older work, and long CPU loops must cooperate with coroutine cancellation.

## Files likely affected

- `src/desktopMain/kotlin/com/openlog/ui/LogViewer.kt`
- `src/desktopMain/kotlin/com/openlog/ui/FilterPanel.kt`
- `src/desktopMain/kotlin/com/openlog/ui/AppState.kt`
- `src/desktopMain/kotlin/com/openlog/utils/Filter.kt`
- `src/desktopMain/kotlin/com/openlog/utils/SeqComputer.kt`
- `src/desktopTest/kotlin/com/openlog/FilterBehaviorTest.kt`
- `src/desktopTest/kotlin/com/openlog/ComputeItemsSpliceTest.kt`
- `src/desktopTest/kotlin/com/openlog/LargeFilePerfHarness.kt`

## Risk level

- Finding severity: **High**
- Implementation risk: **High**, because stale-result suppression and cancellation ordering affect visible filter results.

## Expected behavior change

- Rapid edits are conflated to the newest filter revision.
- Cancelled computations stop promptly and cannot publish stale items.
- Only the latest successful revision updates the visible list and related counts.
- Small logs remain responsive without unnecessary dispatcher churn.

## Tests/checks to run

- Add deterministic tests with a pausable compute function: submit A, then B; assert A is cancelled and only B publishes.
- Test tab close/switch while computing, error recovery, sequence expansion state, and identical input deduplication.
- Add cancellation checkpoints to hot loops and assert cancellation completes within a fixed test timeout.
- Use `LargeFilePerfHarness` with a fixed large fixture and scripted rapid typing. Record before/after peak concurrent compute jobs, total CPU time, allocation rate, and time until the final result is visible.
- Acceptance target: one active compute per tab, no stale publication, and repeatable reduction in CPU/allocation under the rapid-input scenario without regressing single-filter latency materially.
- Run `./gradlew desktopTest --tests "com.openlog.FilterBehaviorTest"`.
- Run `./gradlew desktopTest --tests "com.openlog.ComputeItemsSpliceTest"`.
- Run the full verification suite documented in the plan index.

## Rollback notes

Keep the new computation coordinator behind the current `computeItems` contract. If ordering regressions appear, revert the coordinator and cancellation changes together; do not retain cancellation checkpoints without a clear owner for stale-result suppression.
