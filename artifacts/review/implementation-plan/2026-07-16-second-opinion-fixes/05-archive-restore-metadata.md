# Task 05 — Refresh archive metadata during background restore

## Goal

Preserve cheap synchronous session construction while ensuring a restored
archive tab publishes current candidate metadata and the correct
`largeFileMode` before Compose computes its rows.

## Evidence

- Persisted `ZipLogCandidate.sizeBytes` determines restored
  `largeFileMode`.
- Current metadata is refreshed only when direct extraction returns empty.
- An archive regenerated at the same path/entry can contain much larger data
  while retaining stale small-file classification.
- `LogViewer` computes non-large tabs synchronously.

## Severity and implementation risk

- Finding severity: **Medium**
- Implementation risk: **Medium**, because restore metadata and parsed content
  must publish atomically.

## Files likely affected

- `src/desktopMain/kotlin/com/openlog/ui/AutosaveCodec.kt`
  - `RestoredTabSource`
  - `RestoredTabSource.ArchiveSource`
  - `tabShellFromToken`
- `src/desktopMain/kotlin/com/openlog/ui/AppState.kt`
  - `scheduleRestoredTabLoad`
- Archive helpers if a targeted current-candidate lookup is extracted
- `src/desktopTest/kotlin/com/openlog/AppStateBehaviorTest.kt`
- `src/desktopTest/kotlin/com/openlog/BugReportZipTest.kt`

## Dependencies

None.

## Implementation approach

1. Keep `tabShellFromToken()` metadata-only and cheap. Do not re-list archives
   during `AppState` construction.
2. Introduce a small restore result containing:
   - parsed entries;
   - effective current `largeFileMode`;
   - refreshed archive candidate when available;
   - enough status to distinguish missing/stale from valid empty content.
3. In the existing IO restore job, resolve the current entry by `entryPath`
   before extraction. A targeted lookup is preferred; full candidate listing
   is acceptable because it remains off the UI thread and preserves existing
   eligibility checks.
4. Use refreshed metadata for extraction and size classification.
5. Publish `logData`, `rmap`, pending analysis, `archiveCandidate`, and
   `largeFileMode` in one `upTab` update.
6. Define explicit behavior for:
   - unchanged entry;
   - replaced entry at the same path;
   - missing/renamed entry;
   - genuinely empty valid entry;
   - legacy token without candidate metadata.
7. Persist the refreshed candidate on the next autosave without changing the
   token version or removing legacy compatibility.

## Non-goals

- No synchronous archive scan during startup.
- No archive-format, picker, open, split, or extraction-budget redesign.
- No removal of legacy tokens.
- No 64 MB in-memory test String.

## Required tests

- Autosave a small archive tab, replace the archive at the same path with the
  same entry path and changed metadata/content, restore, and assert current
  content/candidate are published.
- Cross the large-file threshold through an injectable threshold or direct
  load-result seam; assert restored `largeFileMode` uses current metadata.
- Missing entry fails according to the defined restore policy without opening
  a misleading empty tab.
- Genuinely empty valid entry is distinguishable from missing/stale.
- Unchanged archive still restores.
- Legacy token without candidate metadata still restores through the
  background path.
- Constructor/startup test proves no archive listing occurs synchronously.

## Acceptance criteria

- Replacing an archive cannot publish large content with a stale small-file
  flag.
- Compose never observes refreshed log data before refreshed size metadata.
- Session construction remains free of synchronous archive scanning for new
  tokens.
- Legacy caches remain readable.

## Terra reviewer checklist

- Verify refresh occurs on IO, not during construction/composition.
- Verify metadata and data publish in one state update.
- Verify empty and missing cases are not conflated.
- Verify tests avoid huge fixtures and still cross the threshold
  deterministically.
- Check autosave format compatibility.

## Focused and full gates

```bash
./gradlew desktopTest --tests "com.openlog.AppStateBehaviorTest.*Archive*"
./gradlew desktopTest --tests "com.openlog.BugReportZipTest"
./gradlew desktopTest
git diff --check
```

## Rollback notes

No token migration is required. Reverting restores persisted-candidate restore
behavior and its stale-metadata edge.

## Suggested commit

`fix: refresh archive metadata during restore`
