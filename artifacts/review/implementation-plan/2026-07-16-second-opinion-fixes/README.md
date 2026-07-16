# Second-opinion fix plan

This planning-only backlog converts the independent review of
`feat/updates_16_jul_part_2` into small, independently reviewable repair tasks.
It does not authorize production-code changes by itself.

## Baselines

- Branch base: `master` at `c1f36d85`.
- Claude review-fix session baseline: `6deaff1a`.
- Repair-series starting point: `09d479b9`.
- Preserve the untracked `docs/review-fixes-session-summary.md`; do not edit,
  stage, delete, or silently fold it into a repair commit.

Use all three comparisons during execution:

- `master...HEAD` — the complete branch, including the earlier `.ann` sidecar
  change.
- `6deaff1a..09d479b9` — the six commits described by Claude's summary.
- `09d479b9..HEAD` — only the new repair series.

## Requested execution roles

- **Implementation:** GPT-5.6 Luna, high reasoning.
- **Independent review:** GPT-5.6 Terra, high reasoning.
- **Coordination/arbitration:** GPT-5.6 Terra, high reasoning.
- **Final review and merge recommendation:** root agent.

The runtime must confirm model availability when implementation starts. Role
boundaries and high-reasoning briefs remain mandatory even if the agent
interface cannot technically pin a named model variant.

See [00-execution-protocol.md](00-execution-protocol.md) for the full handoff,
review, commit, and rework loop.

## Priority order

| Order | Task | Severity | Change type | Depends on |
|---:|---|---|---|---|
| 1 | [Serialize autosave and shutdown](01-autosave-single-writer.md) | High | Reliability / lifecycle | None |
| 2 | [Complete browser MCP CORS](02-browser-mcp-cors.md) | Medium | Security / protocol | None |
| 3 | [Linearize MCP restart-sensitive changes](03-control-server-restart-races.md) | Medium | Concurrency / lifecycle | Task 02 |
| 4 | [Bound regex work per bulk operation](04-regex-operation-budget.md) | Medium | Security / performance | None |
| 5 | [Refresh archive metadata during restore](05-archive-restore-metadata.md) | Medium | Performance / restore correctness | None |
| 6 | [Harden annotation-sidecar parsing](06-annotation-sidecar-hardening.md) | Medium | Recovery / crash prevention | None |
| 7 | [Restore the zero-new-quality-debt contract](07-quality-gate-cleanup.md) | Low | Mechanical / build hygiene | Tasks 01-06 |

Task 01 is first because it protects the newest user session state. Tasks
02-04 close local-control-plane and resource-exhaustion gaps before restore
performance and recovery work. Mechanical cleanup remains last.

## Global guardrails

- Only one implementation task and one editing agent may be active at a time;
  all agents share the same worktree.
- Luna implements the smallest safe change and corresponding regression tests.
- Terra review is read-only and starts only after Luna stops editing.
- Terra coordination arbitrates findings and runs independent gates.
- No broad `AppState` decomposition, autosave-format migration, Compose `Color`
  decoupling, dependency upgrade, version bump, or unrelated UI cleanup.
- Do not run IDEA and Gradle builds concurrently. Gradle wrapper results are
  authoritative; IDEA MCP is for navigation and changed-file problem checks.
- Never touch real app state or logs under the user's home directory. Use the
  test sandbox or an isolated `user.home` under `/private/tmp`.
- Luna and the reviewer do not stage, commit, merge, or push.
- Terra coordination may create one local commit per accepted task. Never
  amend Claude's commits. Merge and push require a separate user request.

## Required automated gates

Run focused tests first. Terra coordination then reruns them independently and
executes, without parallel Gradle processes:

```bash
GRADLE_USER_HOME=/private/tmp/openlog2-gradle ./gradlew --no-parallel desktopTest
GRADLE_USER_HOME=/private/tmp/openlog2-gradle ./gradlew --no-parallel detekt
GRADLE_USER_HOME=/private/tmp/openlog2-gradle ./gradlew --no-parallel ktlintCheck --continue
git diff --check
git status --short --branch
```

After all repair tasks:

```bash
GRADLE_USER_HOME=/private/tmp/openlog2-gradle ./gradlew --no-parallel build
```

If repository-wide static checks remain red because of debt already present on
`master`, compare the exact findings with `master`. Do not call a failing gate
green, do not hide new findings in a baseline, and do not expand this plan into
a repo-wide lint cleanup unless the user authorizes that separately.

## Final merge criteria

Root recommends merge only when:

- all seven tasks have passed the Luna → Terra review → Terra coordination
  loop;
- no unresolved High or Medium findings remain;
- autosave newest-write ordering is deterministic;
- browser preflight works without weakening real-request authentication;
- CORS changes and token rotation cannot publish stale in-flight servers;
- catastrophic regex cost is bounded across an entire bulk operation;
- restored archive tabs publish current metadata before Compose uses them;
- malformed `.ann` files fail safely with legacy fallback;
- `desktopTest` and `git diff --check` pass;
- no branch-introduced detekt or ktlint finding remains;
- remaining baseline failures, if any, are proven identical to `master` and
  explicitly accepted rather than mislabeled;
- manual QA from the execution protocol is completed, otherwise the verdict is
  `Needs deeper manual QA`;
- no unrelated files, version bump, merge, or push were introduced.
