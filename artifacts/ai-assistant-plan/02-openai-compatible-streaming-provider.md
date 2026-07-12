# Task 02 — OpenAI-compatible streaming provider

## Goal

Implement a cancellable OpenAI-compatible Chat Completions provider with LM Studio support.

## Dependencies

Task 01.

## Implementation contract

- Add provider-neutral `LlmProvider`, capability, request/message, tool-call, and streaming-event types under a dedicated AI package.
- Add `OpenAiCompatibleProvider` using Ktor Client at the existing Ktor version. It accepts each profile's base URL and runtime-only key.
- Support model discovery where `/models` is available; preserve manual model entry when it is not.
- Parse SSE text deltas and assemble fragmented tool-call ids, names, and argument JSON by index.
- Cancelling the collection cancels the HTTP request and emits no synthetic successful completion.

## Tests and acceptance

- Deterministic SSE fixtures cover text streaming, multiple fragmented tool calls, malformed events, unavailable endpoint, and coroutine cancellation.
- Test model-list success and graceful unsupported/failure handling.
- Dependency locks are updated only for added client artifacts.

## Rollback

Remove the provider package and client dependencies together; profile data remains harmless inert configuration.
