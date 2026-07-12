# In-app AI assistant implementation backlog

This is the approved, task-by-task implementation plan for the per-log-tab AI sidebar. Tasks are intentionally small and must be implemented, reviewed, and accepted in order.

## Guardrails

- Preserve the external MCP and REST contracts throughout; their current tests are compatibility gates.
- Persist provider profiles but never API keys, run transcripts, or tool payloads.
- Loopback providers are trusted. Remote profiles require HTTPS plus an explicit one-time disclosure acknowledgement.
- The in-app assistant calls shared Kotlin operations directly; it must not connect to openLog's own MCP HTTP endpoint.
- Run focused tests first, then `./gradlew desktopTest`, `./gradlew detekt ktlintCheck`, `./gradlew build`, and `git diff --check` before accepting the last task.

## Order

1. [Provider profiles and ephemeral credentials](01-provider-profiles-and-credentials.md)
2. [OpenAI-compatible streaming provider](02-openai-compatible-streaming-provider.md)
3. [Shared openLog tool gateway](03-shared-openlog-tool-gateway.md)
4. [Agent runner and action policy](04-agent-runner-and-action-policy.md)
5. [AI sidebar and run trace](05-ai-sidebar-and-run-trace.md)
6. [Context actions and evidence](06-context-actions-and-evidence.md)
7. [Acceptance, documentation, and release check](07-acceptance-documentation-and-release-check.md)

No task authorizes a commit or push by itself. Each task requires independent review and explicit acceptance before the next begins.
