# Task 03 — Shared openLog tool gateway

## Goal

Move the existing MCP operation catalog and dispatch behind a transport-neutral internal gateway so MCP and in-app AI share exactly one implementation.

## Dependencies

None; it may be developed after Task 02 but does not depend on its code.

## Implementation contract

- Extract tool descriptors, JSON-schema metadata, dispatch, and operation results from `ControlServer` into an internal gateway.
- Keep existing MCP tool names, schemas, argument validation, REST paths, and response shapes byte-for-byte compatible where tests already characterize them.
- Adapt the existing ControlServer to delegate to the gateway.
- Generate OpenAI function descriptors from the same catalog; do not hand-maintain a second tool list.
- Preserve the current 31-tool external contract.

## Tests and acceptance

- Existing `ControlServerTest`, `ControlServerMcpTest`, and configuration-snippet tests remain green.
- Add catalog parity tests for tool-name uniqueness, all current names, and MCP/OpenAI schema correspondence.
- Direct gateway tests cover a representative read, automatic mutation, and confirmation-classified mutation.

## Rollback

Revert the gateway extraction as one mechanical unit; do not leave parallel dispatch implementations.
