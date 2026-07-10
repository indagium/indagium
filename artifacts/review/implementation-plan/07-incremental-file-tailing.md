# Task 07 — Make file tailing incremental and bounded

## Goal

Close finding P-04 by reading appended bytes in bounded chunks with a carry buffer for partial lines, then updating tab data and analysis incrementally or on a controlled throttle instead of rebuilding the entire log for every burst.

## Files likely affected

- `src/desktopMain/kotlin/com/openlog/utils/FileTailer.kt`
- `src/desktopMain/kotlin/com/openlog/ui/AppState.kt`
- `src/desktopMain/kotlin/com/openlog/model/Model.kt`
- `src/desktopMain/kotlin/com/openlog/utils/LogParser.kt`
- `src/desktopMain/kotlin/com/openlog/utils/EntryIdMap.kt`
- `src/desktopTest/kotlin/com/openlog/FileTailerTest.kt`
- `src/desktopTest/kotlin/com/openlog/AppStateBehaviorTest.kt`
- `src/desktopTest/kotlin/com/openlog/LargeFilePerfHarness.kt`

## Risk level

- Finding severity: **High**
- Implementation risk: **High**, because byte boundaries, truncation/rotation, IDs, and incremental derived state are correctness-sensitive.

## Expected behavior change

- Large append bursts are read in bounded chunks rather than one allocation sized to the unread delta.
- Partial UTF-8 characters and partial lines are carried safely to the next read.
- Truncation, rotation, file deletion, and tail cancellation have deterministic behavior.
- Derived maps and analysis are updated incrementally when valid, or recomputed at a bounded cadence rather than every polling batch.

## Tests/checks to run

- Add byte-boundary tests for UTF-8, CRLF/LF, no trailing newline, very long lines, rapid multi-write bursts, truncation, replacement/rotation, and cancellation.
- Compare incremental results against a full reparse oracle for randomized append sequences.
- Run a fixed-duration tail soak with a fixed append rate and fixture. Record allocation rate, GC pause time, CPU, peak heap, event-to-visible latency, and retained entry count before and after.
- Acceptance target: bounded read allocation, no data loss/duplication, stable memory after the soak plateau, and append cost that does not grow proportionally with all prior rows.
- Run `./gradlew desktopTest --tests "com.openlog.FileTailerTest"`.
- Run `./gradlew desktopTest --tests "com.openlog.AppStateBehaviorTest"`.
- Run the full verification suite documented in the plan index.

## Rollback notes

Land the chunked reader and incremental state update in separable commits within the PR so the state optimization can be reverted while keeping safe bounded reads. If data-integrity parity fails, disable incremental derived-state updates and use a throttled full recompute until corrected.
