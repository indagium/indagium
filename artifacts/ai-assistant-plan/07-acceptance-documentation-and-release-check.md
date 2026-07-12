# Task 07 — Acceptance, documentation, and release check

## Goal

Document the feature, correct MCP connection documentation, and complete end-to-end verification.

## Dependencies

Tasks 01–06.

## Implementation contract

- Document LM Studio setup, compatible-provider profiles, remote-data disclosure, ephemeral keys, tool confirmations, cancellation, and troubleshooting.
- Correct the MCP README configuration sample to include the required Authorization header.
- Add an end-to-end fake-provider scenario that streams an answer, invokes a tool, pauses for confirmation, and creates evidence.

## Tests and acceptance

- Run focused AI and control-server tests, then `./gradlew desktopTest`, `./gradlew detekt ktlintCheck`, `./gradlew build`, and `git diff --check`.
- Manually validate LM Studio with a tool-capable local model: selected-line analysis, automatic filter and note action, destructive confirmation, cancellation, and relaunch with no chat/key persistence.
- Record any unavailable manual dependency, such as a missing local LM Studio server/model, separately from automated results.

## Rollback

Documentation can be reverted independently. Do not claim LM Studio compatibility without the automated provider contract tests remaining green.
