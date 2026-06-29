# 07 Tests and Maintainability

Reviewed:
- `src/desktopTest/kotlin/com/openlog/*`
- Gradle verification tasks
- IDEA MCP inspections on key files

The test suite is meaningful and currently includes parser, sequence grouping, filter behavior, tag suggestions, app-state behavior, split view/tab helper regressions, and palette checks. The suite is also currently red.

## Findings

### OL2-001

Severity: blocker

Area: Tests and filter behavior

File path: `src/desktopMain/kotlin/com/openlog/utils/Filter.kt`

Symbol/function/class if known: `passesFilter`

Problem: `./gradlew build`, `./gradlew check`, and `./gradlew desktopTest` fail because `FilterBehaviorTest.scopedIncludeMessageRuleNarrowsOnlyMatchingTag` expects an unrelated tag to remain visible when an include message rule is scoped to `com.app.Network`. The implementation treats any positive rule as a global allowlist; if the current entry does not match a positive rule, it returns false.

Why it matters: This blocks build/check. It also means a scoped "show only messages like this" rule can hide unrelated tags, which is likely surprising in tag-focused analysis.

Suggested fix: Decide and codify the intended rule semantics. Based on the existing test, positive scoped rules should narrow entries within their scope but should not exclude entries outside their scope. Implement that behavior in `passesFilter` and keep the current regression test.

Suggested test: The existing failing test is the regression test. Add a package-prefix scoped equivalent and a case with both include and exclude rules.

Confidence: high

### OL2-014

Severity: low

Area: Test coverage gaps

File path: `src/desktopTest/kotlin/com/openlog`

Symbol/function/class if known: test suite

Problem: Important risk areas lack tests: concurrent file opens, IO-scope cancellation, large-file parser behavior, special-character saved-filter round trips, OS-specific app-data path selection, and privacy/clear-history behavior.

Why it matters: The existing suite catches logic regressions well, but the riskiest desktop behaviors are file/lifecycle/persistence boundaries, and those are exactly where regressions can become user-visible data loss or stale local data.

Suggested fix: Add fake service boundaries first, then write deterministic tests around these areas.

Suggested test: See each finding's suggested test. Prioritize OL2-001, OL2-002, OL2-008, and OL2-007.

Confidence: high

## No Serious Issue Found

- Tests are not merely smoke tests; they cover real behavior in parser/filter/sequence/state helpers.
- `desktopTest` is the correct verification task for this KMP desktop project; root `test` is not available.
