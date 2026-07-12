# Task 04 — Agent runner and action policy

## Goal

Implement the per-tab AI run loop that streams model output, executes shared tools, and stops for confirmations.

## Dependencies

Tasks 02 and 03.

## Implementation contract

- Add ephemeral `AiSession`/`AiRun` state per log tab. It must never be serialized into autosave or note exports.
- Implement model → tool → model execution with a hard 12-round limit and bounded tool-result injection. When data is truncated, the model and UI receive an explicit truncation notice.
- Emit typed events for status, text delta, requested tool, completed tool, required confirmation, error, cancelled, and done.
- Execute reads, filters, selection, group controls, and annotation mutations automatically. Require an explicit confirmation event for open/split/merge/close, tailing, export, and annotation file save/load.
- Cancellation stops provider collection and invalidates pending confirmation.

## Tests and acceptance

- Scripted provider tests cover multi-tool runs, tool failure, malformed arguments, loop limit, cancellation, accepted/rejected confirmation, and no session persistence.
- Verify a confirmation-classified tool cannot touch app state before acceptance.

## Rollback

Remove only the runner package; it leaves provider and gateway layers independently usable and does not alter saved data.
