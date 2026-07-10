# Task 06 — Stream filtered exports

## Goal

Close finding P-03 by writing filtered rows incrementally to a buffered temporary file and atomically moving it into place. Avoid materializing both a filtered list and a second full-size output string.

## Files likely affected

- `src/desktopMain/kotlin/com/openlog/utils/ExportFilteredLog.kt`
- `src/desktopMain/kotlin/com/openlog/utils/Filter.kt`
- `src/desktopMain/kotlin/com/openlog/ui/AppState.kt`
- `src/desktopMain/kotlin/com/openlog/ui/App.kt`
- `src/desktopTest/kotlin/com/openlog/ExportFilteredLogTest.kt`
- `src/desktopTest/kotlin/com/openlog/FilterBehaviorTest.kt`

## Risk level

- Finding severity: **High**
- Implementation risk: **Medium**, because output bytes, newline handling, cancellation, and replacement semantics must remain compatible.

## Expected behavior change

- Export memory usage is bounded by buffers and filtering state rather than total output size.
- Successful output preserves the current format and row order.
- Cancellation or I/O failure leaves the existing destination intact and removes the temporary file.
- Errors are surfaced to the user rather than swallowed.

## Tests/checks to run

- Golden-file tests for empty, small, Unicode, multiline/raw, filtered, sequence, and final-newline cases.
- Failure-injection tests for write failure, move failure, cancellation, destination already present, and temporary-file cleanup.
- Use a fixed large generated fixture in a forked JVM with a constrained heap. Record peak heap/RSS, elapsed time, output checksum, and output size before and after.
- Acceptance target: output checksum parity and peak managed-memory growth that does not scale linearly with the complete output string size.
- Run `./gradlew desktopTest --tests "com.openlog.ExportFilteredLogTest"`.
- Run the full verification suite documented in the plan index.

## Rollback notes

Retain the old formatter as a test oracle during development, not as an automatic production fallback for large exports. If streaming output differs, revert the writer and atomic-move path together so partially changed replacement semantics are not left behind.
