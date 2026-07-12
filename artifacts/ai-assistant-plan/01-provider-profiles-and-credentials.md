# Task 01 — Provider profiles and ephemeral credentials

## Goal

Add persisted non-secret OpenAI-compatible provider profiles, including a default LM Studio localhost profile, while keeping API keys memory-only.

## Non-goals

- Do not make network requests or add the chat client yet.
- Do not persist transcripts, credentials, or tool results.

## Implementation contract

- Add `AiProviderProfile` with stable id, display name, base URL, model, selected state, and remote-disclosure acknowledgement.
- Extend `AppSettings` and its backwards-compatible token serializer with only non-secret profile state. Old settings must continue to load with the LM Studio profile as the default.
- Add an in-memory credential store owned by `AppState`; it is cleared on app close and omitted from every persistence path.
- Treat `localhost`, `127.0.0.1`, and IPv6 loopback as local. Reject non-loopback HTTP URLs; remote HTTPS profiles require acknowledgement before use.
- Add profile editing controls in Settings; API-key input is session-only and clearly labelled.

## Tests and acceptance

- Settings round trip preserves profiles and selected profile.
- Older settings tokens load safely.
- Assertions prove plaintext API keys and AI sessions do not occur in autosave serialization.
- URL validation covers loopback, malformed URL, remote HTTP rejection, remote HTTPS acknowledgement, and IPv6 loopback.

## Rollback

Revert this task as one unit. It only appends defaulted persisted fields, so rollback does not strand existing user settings.
