# Task 06 — Context actions and evidence

## Goal

Make log investigation fast from the current UI and present evidence based on real tool outputs.

## Dependencies

Tasks 04 and 05.

## Implementation contract

- Add `Ask AI about this line` to the log context menu.
- Add quick actions for selected error, root cause, filtered result, timeline, and mapped source code.
- Seed a run with explicit active-tab and selected-line context; never silently carry context from another tab.
- Turn gateway results into structured evidence cards for log rows, source matches, and created/changed notes. Cards navigate using actual ids and paths returned by tools.
- Treat final model Markdown only as prose; do not infer clickable targets from model-invented line references.

## Tests and acceptance

- Context-menu and quick-action tests prove the right tab/line context is passed.
- Evidence tests prove card navigation targets the actual returned line/source/note.
- Verify automatic filters and annotations work, while confirmation actions remain blocked until accepted.

## Rollback

Remove only entry points and evidence UI. The agent panel and shared tool execution remain intact.
