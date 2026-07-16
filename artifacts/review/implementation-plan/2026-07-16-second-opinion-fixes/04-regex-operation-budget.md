# Task 04 — Bound regex cost across a complete bulk operation

## Goal

Prevent a catastrophic regex from consuming the per-match timeout once for
every log entry or every distinct malicious rule during one filtering,
sequence, export, suggestion, highlight, or MCP bulk computation.

## Evidence

- `DeadlineCharSequence` uses `System.currentTimeMillis()`.
- Each top-level match call creates a fresh 100 ms deadline.
- Full filtering calls matching repeatedly across fields and entries.
- One million failing entries can therefore multiply the timeout into hours of
  work even though no individual matcher runs forever.

## Severity and implementation risk

- Finding severity: **Medium**
- Implementation risk: **Medium to High**, because matching helpers are shared
  by UI, filtering, sequences, export, and MCP paths.

## Files likely affected

- `src/desktopMain/kotlin/com/openlog/utils/TextMatch.kt`
  - `DeadlineCharSequence`
  - `containsPattern`
  - `tagMsgContainsPattern`
  - `firstRegexMatch`
  - `regexRanges`
- `src/desktopMain/kotlin/com/openlog/utils/Filter.kt`
  - `passesFilter`
  - `visibleEntries`
  - `computeItems`
- `src/desktopMain/kotlin/com/openlog/utils/SeqComputer.kt`
  - start/end matching and scan operations
- Bulk callers in:
  - `AppState.kt`
  - `FilterPanel.kt`
  - filtered export helpers
  - MCP/OpenLog tool operations
  - LogViewer highlight computation where multiple rows share one calculation
- `TextMatchTest.kt`
- `FilterBehaviorTest.kt`
- `SequenceGroupingTest.kt`
- relevant export/MCP tests

## Dependencies

None, but land before final performance/manual QA.

## Implementation approach

1. Replace wall-clock deadlines with monotonic `System.nanoTime()`.
2. Add a computation-local `RegexEvaluationContext` or equivalent:
   - tracks patterns that timed out during this operation;
   - skips a timed-out pattern for the remainder of the same operation;
   - retains the per-match ceiling, capped by remaining allowed work;
   - optionally caps the number of distinct timeout events so several
     malicious rules are still globally bounded.
3. Do not poison the process-wide regex cache and do not permanently disable a
   pattern. A later independent computation must retry it.
4. Thread one context through each logical bulk operation:
   - filter/visible-entry computation;
   - message include/exclude rules;
   - sequence start/end scans;
   - filter-panel candidate/suggestion scans;
   - exports over filtered data;
   - MCP filtered-line/tool operations;
   - batch highlight/range work where the same dangerous pattern would
     otherwise repay the timeout per row.
5. Keep existing single-call APIs source-compatible with an optional/default
   context.
6. Preserve the current result contract: a timed-out match behaves as no
   match. Surface a warning only where an existing result/error channel allows
   it without a UI redesign.
7. Do not use executor/future cancellation around `java.util.regex`; matcher
   work is not reliably interruptible and can leak stuck workers.
8. Do not impose a short global deadline that truncates legitimate fast
   scanning of a very large log. Bound timeout events/patterns, not normal
   successful iterations.

## Non-goals

- No regex-engine replacement or new dependency.
- No global permanent pattern blacklist.
- No filter-panel redesign.
- No cache-capacity change.
- No behavior change for valid fast patterns.

## Required tests

- Hundreds/thousands of identical catastrophic lines cost approximately one
  expensive timeout for one operation, not one per line.
- Several distinct catastrophic patterns remain bounded by the operation
  timeout-event cap.
- A second independent operation retries the previously timed-out pattern.
- A different normal regex remains usable after one pattern is quarantined.
- Normal regexes still scan the full dataset and preserve match/range results.
- Sequence start/end matching shares the operation containment.
- Filtered export and MCP filtering use the bounded path.
- Invalid-regex and LRU-cache tests remain green.

Use generous CI thresholds that clearly fail the old multiplied behavior;
avoid fragile sub-frame timing assertions.

## Acceptance criteria

- One dangerous pattern cannot consume 100 ms per entry in one bulk operation.
- Several dangerous rules cannot multiply cost without an explicit small cap.
- The budget is local to one computation and safe across concurrent
  computations.
- Normal matching correctness and cache behavior are unchanged.
- UI and MCP paths share the same containment rules.

## Terra reviewer checklist

- Verify `nanoTime()` is used consistently.
- Verify a new context is created per logical operation, not per entry.
- Verify no mutable context is shared across concurrent computations.
- Verify normal large-log scans are not prematurely truncated.
- Verify sequence/export/MCP paths are not accidentally left unbounded.
- Reject global cache poisoning or unbounded executor creation.

## Focused and full gates

```bash
./gradlew desktopTest --tests "com.openlog.TextMatchTest"
./gradlew desktopTest --tests "com.openlog.FilterBehaviorTest"
./gradlew desktopTest --tests "com.openlog.SequenceGroupingTest"
./gradlew desktopTest --tests "com.openlog.ControlServerTest"
./gradlew desktopTest
git diff --check
```

## Rollback notes

No persisted data changes. Reverting restores the per-call timeout behavior.

## Suggested commit

`fix: bound regex filtering operations`
