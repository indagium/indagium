# Safe implementation plan

This backlog converts the validated read-only review findings into small, independently mergeable tasks. It does not authorize implementation by itself. Production code, tests, dependencies, and build configuration remain unchanged until an individual task is started.

## Guardrails

- Preserve the current default: the control server remains disabled unless the user explicitly enables it.
- Add characterization tests before changing externally visible behavior or lifecycle ownership.
- Keep each PR limited to one task below. Do not combine security fixes with broad class extraction or style cleanup.
- For memory and latency work, record a before/after result using the same fixture, JVM options, warm-up, and measurement method.
- Prefer bounded failure with a clear user-facing error over unbounded allocation, silent failure, or partial output.
- Run focused tests first, then `./gradlew desktopTest`, `./gradlew detekt ktlintCheck`, and `./gradlew build` before merge.

## Priority and dependency order

| Order | Task | Change type | Review finding | Depends on |
|---:|---|---|---|---|
| 1 | [Secure the local control plane](01-secure-control-plane.md) | Behavioral / security | S-01 | None |
| 2 | [Linearize control-server lifecycle](02-control-server-lifecycle.md) | Behavioral / security | S-02 | Task 01 authentication contract |
| 3 | [Bound archive extraction](03-archive-resource-budgets.md) | Behavioral / crash prevention | S-03 | None |
| 4 | [Make analysis completion explicit](04-analysis-state-correctness.md) | Behavioral / crash and CPU prevention | P-02 | None |
| 5 | [Conflate and cancel filter computation](05-cancellable-filter-computation.md) | Behavioral / performance | P-01 | Task 04 state model |
| 6 | [Stream filtered exports](06-streaming-filtered-export.md) | Behavioral / crash prevention | P-03 | None |
| 7 | [Make file tailing incremental and bounded](07-incremental-file-tailing.md) | Behavioral / performance | P-04 | Task 04 analysis state recommended |
| 8 | [Remove interactive full-list scans](08-ui-hot-path-indexes.md) | Behavioral / performance | P-05, P-07 | Tasks 04-05 recommended |
| 9 | [Serialize persistence and improve diagnostics](09-persistence-and-logging.md) | Behavioral / reliability | P-06 | None |
| 10 | [Gate release and dependency integrity](10-release-dependency-gates.md) | Build/release behavior | A-02 | Security tasks should land first |
| 11 | [Establish a single state-mutation boundary](11-single-writer-state-boundary.md) | Behavioral / architecture | A-01 | Tasks 02, 04, 05, 07, 09 |
| 12 | [Mechanically decompose large UI/state files](12-mechanical-decomposition.md) | Mechanical refactor / style cleanup | Refactoring opportunities | Tasks 01-11 |

## Change-type separation

Tasks 01-11 intentionally change security, lifecycle, failure, performance, or release behavior and require focused acceptance tests. Task 12 is the only mechanical cleanup task: it must preserve behavior, avoid dependency changes, and be split further if review becomes difficult.

## Merge discipline

For every PR, include the finding ID, the before/after contract, focused test output, and any profiling result requested by the task. If a performance task does not show a repeatable improvement or a resource bound under its prescribed check, do not merge it on intuition alone.
