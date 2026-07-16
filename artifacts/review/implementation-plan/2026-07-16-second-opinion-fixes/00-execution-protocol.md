# Execution protocol — Luna implementation, Terra review and coordination

## Goal

Execute each repair as one bounded, independently revertible change with
separate implementation, review, arbitration, verification, and final-review
ownership.

## Role boundaries

### GPT-5.6 Luna implementer — high reasoning

- Implements exactly one task brief at a time, including regression tests.
- Inspects adjacent code only to preserve invariants; does not broaden scope.
- Runs focused tests and reports changed files, rationale, results, and known
  limitations.
- Does not stage, commit, push, merge, rewrite history, edit the historical
  summary, or touch real `~/.openlog2` data.

### GPT-5.6 Terra reviewer — high reasoning

- Starts only after Luna has stopped editing.
- Reviews the live diff read-only against the finding, task acceptance
  criteria, `master`, and the repair-series starting point.
- Returns exactly one status: `Accepted`, `Changes required`, or `Blocked`.
- For every issue, gives severity, location, evidence, impact, smallest safe
  fix, and missing verification.
- Does not edit, stage, or commit.

### GPT-5.6 Terra coordinator — high reasoning

- Maintains the task ledger and records `PRE_TASK_SHA`, branch, expected files,
  and allowed scope before each task.
- Sends Luna the bounded brief and ensures no other agent edits concurrently.
- Arbitrates Terra review findings using code and test evidence.
- Sends implementation corrections back to Luna; does not implement
  production/test code itself.
- Runs independent focused and full gates after review acceptance.
- Stages only allowlisted files and creates one task-focused local commit.
- Never pushes, merges, amends Claude's commits, or stages
  `docs/review-fixes-session-summary.md` without explicit authorization.

### Root final reviewer

- Independently reviews every repair commit and the final `master...HEAD` diff.
- Rechecks all seven findings rather than trusting per-task approvals.
- If a defect is found, reopens the Luna → Terra reviewer → Terra coordinator
  loop instead of silently patching it.
- Gives the final merge recommendation. Merge and push remain separate,
  explicitly authorized actions.

## Per-task loop

1. Terra coordinator verifies:
   - branch is `feat/updates_16_jul_part_2`;
   - tracked state is clean;
   - only the known untracked summary is present;
   - no Gradle or editing agent is already active.
2. Terra records `PRE_TASK_SHA` and sends Luna:
   - goal and evidence;
   - exact likely files/symbols;
   - dependencies and non-goals;
   - required tests and acceptance criteria.
3. Luna adds characterization/regression tests first where practical,
   implements the smallest safe fix, runs focused tests, then stops editing.
4. Terra reviewer examines `PRE_TASK_SHA..working-tree`.
5. Terra coordinator arbitrates:
   - accepts supported review findings and sends them back to Luna;
   - overrules only with recorded code/test evidence;
   - escalates unresolved design disagreement to root.
6. Repeat implementation and review until Terra returns `Accepted`.
7. Terra coordinator independently runs:
   - focused task tests;
   - full `desktopTest`;
   - detekt and ktlint delta checks;
   - `git diff --check`;
   - IDEA MCP changed-file problem checks when available.
8. Terra stages exact allowlisted files and creates one local task commit.
9. Record commit SHA, test evidence, static-check result, limitations, and any
   manual QA still outstanding before starting the next task.

## Commit sequence

Suggested local commits:

1. `fix: serialize autosave writes and shutdown`
2. `fix: allow authenticated browser MCP preflight`
3. `fix: restart MCP after runtime security changes`
4. `fix: bound regex filtering operations`
5. `fix: refresh archive metadata during restore`
6. `fix: tolerate malformed annotation sidecars`
7. `chore: clear branch quality regressions`

Add commits on top of `09d479b9`. Do not amend or reorder the existing branch.

## Rework and failure policy

- A failed focused test returns to Luna immediately.
- A later task breaking an earlier invariant reopens the responsible task and
  receives a dedicated follow-up commit; do not hide it in unrelated work.
- A full-suite-only failure is called flaky only after the exact test passes in
  isolation and repeated evidence shows order/reporting sensitivity.
- If a small safe fix is not possible, stop and escalate rather than widening
  into an architectural rewrite.
- No task commits with an unresolved High/Medium review issue, unexpected file,
  new branch-local lint debt, or access to real application data.

## Manual QA staging

Run the app with an isolated temporary `user.home` and copied fixtures.

1. Rapidly edit filters, annotations, and pane sizes; close immediately;
   relaunch and verify the newest state.
2. Confirm process shutdown leaves no autosave, tail, MCP, or AI work capable
   of changing files or retaining listeners.
3. Connect a real browser MCP inspector; exercise initialize, normal requests,
   and session close.
4. Toggle browser access and rotate the token while MCP startup is delayed;
   verify only the final configuration/token is reachable.
5. Apply a pathological regex to a realistically large fixture; immediately
   change/cancel it and verify bounded recovery.
6. Replace an archive between launches with a larger same-path entry and
   verify current large-file behavior.
7. Open Recent Notes with valid, legacy, missing, and malformed `.ann` files.

## Root final-review checklist

- Review `master...HEAD` for the whole branch.
- Review `6deaff1a..09d479b9` against Claude's summary.
- Review `09d479b9..HEAD` as the repair series.
- Review each repair commit for scope isolation, behavior preservation, and
  test strength.
- Re-run the final automated gates.
- Verify the historical summary remains unmodified/uncommitted unless the user
  explicitly chose to publish it.
- Report the real static-check and manual-QA boundary without blanket success.
