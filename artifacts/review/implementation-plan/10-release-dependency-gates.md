# Task 10 — Gate release and dependency integrity

## Goal

Close finding A-02 by making code quality and dependency integrity enforceable in CI and release packaging: tests/checks must run, static-analysis findings must fail at an agreed baseline, wrapper/dependency artifacts must be verified, and the resolved runtime graph must be auditable.

## Files likely affected

- `.github/workflows/build.yml`
- `build.gradle.kts`
- `settings.gradle.kts`
- `gradle/wrapper/gradle-wrapper.properties`
- `gradle.properties`
- New Gradle verification metadata, lockfiles, or baseline files if the chosen mechanism requires them
- `README.md` and `CLAUDE.md` only if developer/release commands change

## Risk level

- Finding severity: **Medium**
- Implementation risk: **Medium**, because stricter gates can initially block releases until existing debt is baselined.

## Expected behavior change

- CI runs compile, tests, formatting, static analysis, and packaging checks instead of packaging alone.
- New Detekt violations fail CI; existing accepted debt is explicit and reviewable rather than hidden by `ignoreFailures`.
- Gradle wrapper and resolved dependencies are verified/locked according to the selected Gradle-native mechanism.
- CI produces an SBOM or resolved dependency report and checks supported dependency advisories.
- Third-party actions are pinned to immutable revisions.

## Tests/checks to run

- Run the complete CI workflow on a branch for all supported runners.
- Prove the gate fails on a temporary formatting/static-analysis violation, checksum mismatch, and unauthorized dependency change; remove those temporary changes before merge.
- Run `./gradlew desktopTest`, `./gradlew detekt ktlintCheck`, `./gradlew build`, and the dependency verification/report tasks selected by the PR.
- Confirm packaging still succeeds on macOS, Linux, and Windows CI and emitted reports identify the resolved Compose, lifecycle, coroutine, Ktor, MCP SDK, and archive-library versions.

## Rollback notes

Keep each gate in a distinct workflow step so a broken scanner/service can be temporarily disabled without removing tests or artifact verification. Never roll back by silently accepting all Detekt failures again; use a reviewed baseline with an expiry/follow-up task.
