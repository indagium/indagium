# Task 01 — Serialize autosave writes and make shutdown final

## Goal

Prevent an older background autosave from racing with and replacing a newer
synchronous/shutdown save. Ensure normal window shutdown flushes the latest
state and then cancels the `AppState`-owned background scope.

## Evidence

- `AutosaveScheduler.saveInBackground()` launches a delayed job that calls the
  same unsynchronized `saveNow()` path.
- `backgroundJob` replacement/cancellation is not thread-safe.
- `Main.kt` flushes synchronously but does not cancel or wait for state-owned
  background work before exit.
- Atomic file replacement prevents partial data, but not stale last-writer
  ordering.

## Severity and implementation risk

- Finding severity: **High**
- Implementation risk: **Medium**, because persistence and shutdown ordering
  are user-data-sensitive.

## Files likely affected

- `src/desktopMain/kotlin/com/openlog/ui/AutosaveScheduler.kt`
  - `AutosaveScheduler`
  - `saveNow()`
  - `saveInBackground()`
- `src/desktopMain/kotlin/com/openlog/ui/AppState.kt`
  - `autosaveNow()`
  - `close()`
- `src/desktopMain/kotlin/Main.kt`
  - `Window.onCloseRequest`
- `src/desktopMain/kotlin/com/openlog/ui/App.kt`
  - autosave effect comments/contract if needed
- New focused `AutosaveSchedulerTest.kt`
- `src/desktopTest/kotlin/com/openlog/AppStateBehaviorTest.kt`

## Dependencies

None. Land this first.

## Implementation approach

1. Add a single-writer boundary covering both `serialize()` and
   `writeFileAtomically()`.
2. Add thread-safe scheduling ownership around `backgroundJob`.
3. Split the write operation so:
   - `saveNow()` cancels pending debounce work, waits behind any write already
     in progress, then serializes current state and writes last;
   - the background job delays and invokes the private write operation
     directly instead of calling `saveNow()` and cancelling itself.
4. Clear the stored job only if the completing job is still the current job.
5. Do not pre-capture a serialized string before entering the ordering
   boundary. If a Compose read snapshot is needed for coherent multi-field
   serialization, take it inside the ordered write path without holding
   `AppState.stateLock` across disk I/O.
6. Preserve non-throwing `autosaveError` behavior and ensure its final value
   reflects the final ordered write.
7. Change normal close ordering to:
   - flush synchronously;
   - call idempotent `appState.close()`;
   - exit the application.
8. Add a characterization/invariant test ensuring the autosave effect's
   `persistedSnapshot()` stays aligned with fields serialized by `tabToken()`.

Avoid a `runBlocking` plus coroutine `Mutex` design that can deadlock when
`saveNow()` is called from the same `ioScope`.

## Non-goals

- No autosave-format migration.
- No debounce-duration change.
- No broad `AppState` decomposition.
- No conversion of every synchronous autosave call to background work.
- No `stateLock` held during filesystem I/O.

## Required tests

- `newerSynchronousSaveWinsOverOlderInFlightBackgroundSave`
  - pause the first background serialization/write with latches;
  - mutate the saved state;
  - invoke a synchronous shutdown save;
  - release the old write;
  - restore and assert only the newer state wins.
- `saveNowCancelsPendingDebouncedSave`
  - schedule background work;
  - immediately save synchronously;
  - wait beyond the debounce;
  - prove no later write replaces the synchronous result.
- Concurrent failure followed by success leaves `autosaveError == null`;
  success followed by final failure exposes the final error.
- Rapid background calls still coalesce.
- Background scheduling remains non-blocking to the caller.
- `autosaveNow(); close()` preserves the final AppState mutation and leaves no
  delayed job capable of changing the file.
- Existing golden and settings/tab round-trip tests remain green.

## Acceptance criteria

- At most one autosave serialization/write is active at a time.
- A synchronous save completes after every older write and writes current state
  last.
- No pending or in-progress background save can replace the shutdown result.
- The final write controls `autosaveError`.
- Normal window shutdown closes the `AppState` scope after the final flush.
- Persisted autosave bytes and restore behavior remain backward compatible.

## Terra reviewer checklist

- Look for deadlock risk between scheduling and writer locks.
- Verify the background job cannot cancel itself through `saveNow()`.
- Verify close order is flush-before-cancel.
- Verify tests control ordering deterministically with latches, not timing
  luck.
- Check that no unrelated autosave-format or AppState refactor entered the
  diff.

## Focused and full gates

```bash
./gradlew desktopTest --tests "com.openlog.AutosaveSchedulerTest"
./gradlew desktopTest --tests "com.openlog.AppStateBehaviorTest"
./gradlew desktopTest
git diff --check
```

## Rollback notes

This task changes scheduling only, not persisted bytes. Reverting the commit
restores the old scheduler without migration or cleanup.

## Suggested commit

`fix: serialize autosave writes and shutdown`
