# Task 03 — Bound archive extraction

## Goal

Close finding S-03 by enforcing actual decompressed-byte, entry-count, line-count, compression-ratio, and time/cancellation budgets while listing and parsing archives. Reject oversized or suspicious entries before they exhaust heap or disk resources.

## Files likely affected

- `src/desktopMain/kotlin/com/openlog/utils/BugReportZip.kt`
- `src/desktopMain/kotlin/com/openlog/utils/LogParser.kt`
- `src/desktopMain/kotlin/com/openlog/ui/AppState.kt`
- `src/desktopMain/kotlin/com/openlog/ui/App.kt`
- `src/desktopTest/kotlin/com/openlog/BugReportZipTest.kt`
- `src/desktopTest/kotlin/com/openlog/LogParserTest.kt`
- `src/desktopTest/kotlin/com/openlog/ZipPickerDisplayTest.kt`

## Risk level

- Finding severity: **High**
- Implementation risk: **Medium**, because legitimate very large bug reports may now fail with an explicit limit instead of attempting an unsafe load.

## Expected behavior change

- Archive metadata is treated as a hint, not as the resource limit.
- The reader counts bytes actually decompressed and stops at configured budgets.
- Excessive entry count, nesting/ratio, output size, or parsed line count produces a clear bounded error in the UI.
- Valid archives below the limits retain current entry selection and parsing behavior.

## Tests/checks to run

- Add synthetic archives for unknown entry size, misleading size metadata, high compression ratio, too many entries, and a valid entry just below each boundary.
- Run hostile fixtures in a forked JVM with a small heap and a timeout; assert a controlled error rather than OOM or hang.
- Verify cancellation closes all archive/input streams.
- Run `./gradlew desktopTest --tests "com.openlog.BugReportZipTest"`.
- Run `./gradlew desktopTest --tests "com.openlog.LogParserTest"`.
- Run the full verification suite documented in the plan index.

## Rollback notes

Keep limits centralized and configurable so a too-conservative threshold can be adjusted without removing enforcement. If the PR must be reverted, disable archive loading for untrusted inputs until a corrected bounded implementation lands; do not restore unbounded decompression as the long-term state.
