# Task 06 — Make annotation fingerprint parsing failure-safe

## Goal

Ensure a malformed or hand-edited `.ann` sidecar cannot throw from Recent
Notes filtering or abort automatic note-target selection. Preserve valid
legacy `.src` fallback.

## Evidence

- `readSourceFingerprint()` wraps `readText()` but invokes `tokenFields()`
  outside the failure boundary.
- Invalid Base64 can therefore escape during composition-facing
  `recentNotesForTab()` or auto-export collision resolution.
- The preceding `.src` implementation treated unreadable fingerprint data as
  absent.

## Severity and implementation risk

- Finding severity: **Medium**
- Implementation risk: **Low**, intentionally one small recovery change.

## Files likely affected

- `src/desktopMain/kotlin/com/openlog/ui/AppState.kt`
  - `readSourceFingerprint`
  - callers only if a test seam is required
- `src/desktopTest/kotlin/com/openlog/AppStateBehaviorTest.kt`

## Dependencies

None.

## Implementation approach

1. Wrap the complete `.ann` operation in one `runCatching`:
   - file read;
   - token splitting;
   - Base64 decoding;
   - fifth-field extraction.
2. Do not call `tokenFields()` outside the protected block.
3. Treat any malformed `.ann` data as no embedded fingerprint.
4. Continue to the legacy `<note>.md.src` fallback when `.ann` decoding fails
   or contains no valid source path.
5. If both sources are invalid/absent, return `null` and preserve the existing
   positive-evidence-only matching policy.
6. Do not repair, rewrite, or delete the user's malformed sidecar.

## Non-goals

- No annotation token-format change.
- No migration/removal of legacy `.src`.
- No user-facing repair dialog.
- No rejection of otherwise readable Markdown notes.

## Required tests

- Malformed Base64 `.ann` plus valid legacy `.src` uses the legacy fingerprint.
- Malformed `.ann` without legacy fallback does not throw and behaves as no
  fingerprint.
- Malformed data in any token field is contained, not only the fifth field.
- `recentNotesForTab()` remains usable and preserves positive-evidence
  filtering.
- Auto-export/collision resolution chooses a safe target rather than aborting.
- Valid embedded `.ann` fingerprint continues to take precedence over legacy
  `.src`.

## Acceptance criteria

- No malformed `.ann` can crash Recent Notes or auto-export target selection.
- Valid embedded and legacy fingerprints retain current behavior.
- User files are not rewritten as a side effect of reading.

## Terra reviewer checklist

- Verify the entire decode chain is inside `runCatching`.
- Verify fallback still executes after parse failure.
- Verify valid `.ann` precedence remains intact.
- Verify no broad note-storage cleanup entered the diff.

## Focused and full gates

```bash
./gradlew desktopTest --tests "com.openlog.AppStateBehaviorTest.*Note*"
./gradlew desktopTest
git diff --check
```

## Rollback notes

One-function behavior change with no data migration. Revert is safe.

## Suggested commit

`fix: tolerate malformed annotation sidecars`
