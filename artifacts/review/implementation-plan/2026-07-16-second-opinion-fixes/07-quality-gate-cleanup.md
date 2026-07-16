# Task 07 — Restore the zero-new-quality-debt contract

## Goal

Remove branch-introduced or branch-moved detekt, ktlint, and whitespace
failures without expanding this repair cycle into unrelated repository-wide
AI/style debt.

## Evidence

Current checks report:

- detekt: 22 weighted issues, including a changed
  `AutosaveCodec.kt` legacy-offset magic number;
- ktlint: a changed `ControlServer.kt` declaration-spacing violation among
  pre-existing findings;
- `git diff --check`: trailing whitespace in
  `src/desktopTest/resources/goldens/autosave-v1.cache`.

The historical summary's `zero new lint findings` claim is therefore not true
for the live checkout.

## Severity and implementation risk

- Finding severity: **Low**
- Implementation risk: **Low**, provided formatting remains scoped.

## Files likely affected

- `src/desktopMain/kotlin/com/openlog/ui/AutosaveCodec.kt`
- `src/desktopMain/kotlin/com/openlog/debug/ControlServer.kt`
  - only if Task 02 did not already fix the touched declaration spacing
- `src/desktopTest/resources/goldens/autosave-v1.cache`
- `config/detekt-baseline.xml`
  - only to remove/update genuinely stale moved entries, never to hide a new
    finding
- Exact files changed by Tasks 01-06 if those tasks introduce new findings

## Dependencies

Tasks 01-06. This is the final mechanical repair commit.

## Implementation approach

1. Replace legacy settings offsets such as `17`/`18` with explicitly named
   constants describing the frozen positional format.
2. Fix touched-file declaration spacing; do not run a repo-wide formatter.
3. Remove trailing whitespace from the golden fixture while preserving its
   semantic empty `transientRegex` field and golden decode behavior.
4. Review detekt baseline changes:
   - remove obsolete old-file entries when code moved;
   - add no suppression/baseline entry merely to hide repair debt;
   - prefer named constants or code cleanup.
5. Capture and compare exact detekt/ktlint output against `master`.
6. Clean every finding introduced by `master...HEAD` or by the new repair
   series.
7. Keep unrelated pre-existing AI/settings debt out of this task. If the user
   requires a globally green `build`, create a separate explicitly authorized
   lint-debt cleanup plan rather than mixing it into these fixes.

## Non-goals

- No repository-wide reformat.
- No unrelated AI refactor.
- No mass baseline regeneration.
- No dependency/plugin upgrade.
- No claim that a failing static task passed.

## Required tests and checks

```bash
GRADLE_USER_HOME=/private/tmp/openlog2-gradle ./gradlew desktopTest
GRADLE_USER_HOME=/private/tmp/openlog2-gradle ./gradlew detekt --console=plain
GRADLE_USER_HOME=/private/tmp/openlog2-gradle ./gradlew ktlintCheck --continue --console=plain
git diff --check master...HEAD
git status --short --branch
```

Also:

- run `AutosaveGoldenV1Test`;
- compare static-check outputs with the same commands on `master` in an
  isolated worktree or recorded baseline;
- verify no unexpected file was changed by formatting.

Run `./gradlew build` after all tasks. If it remains red solely on findings
proven identical to `master`, report that boundary and require explicit root
acceptance; do not describe it as green.

## Acceptance criteria

- `git diff --check master...HEAD` is clean.
- No detekt or ktlint finding is newly attributable to the branch/repair
  series.
- No new suppression or unjustified baseline entry was added.
- Golden decode and all desktop tests pass.
- Static-check status is documented accurately relative to `master`.

## Terra reviewer checklist

- Verify changed-file-only scope.
- Verify the golden line remains semantically equivalent.
- Verify named constants describe the legacy layout.
- Verify baseline edits remove stale entries rather than mask debt.
- Compare actual output, not only issue counts.

## Rollback notes

Pure mechanical cleanup. Reverting is safe, but would restore known branch
quality-gate failures.

## Suggested commit

`chore: clear branch quality regressions`
