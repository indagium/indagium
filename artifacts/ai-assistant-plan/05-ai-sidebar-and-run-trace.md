# Task 05 — AI sidebar and run trace

## Goal

Expose the agent through an AI tab in the existing right sidebar without changing Notes behavior.

## Dependencies

Tasks 01, 02, and 04.

## Implementation contract

- Add an AI/Notes selector in the right sidebar. AI state follows the active log tab for the current app session only.
- Add provider/model selection, current-launch key entry, remote-disclosure UI, prompt input, send, stop, and retry.
- Render assistant Markdown with a Compose Markdown renderer and throttle rendering while streaming.
- Render chronological run trace rows and confirmation cards; confirmation resumes the paused run and rejection reports a tool denial.
- Make all new controls keyboard reachable and localize visible strings in the touched UI.

## Tests and acceptance

- State tests cover tab switching, send/stop/retry, provider/key validation, confirmation state, and failed-provider recovery.
- Manual desktop verification covers resizing, scroll behavior, focus traversal, and switching back to Notes.

## Rollback

Remove the AI selector and panel while retaining the isolated non-UI layers. Notes must remain usable throughout.
