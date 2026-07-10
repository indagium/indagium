# Task 01 — Secure the local control plane

## Goal

Close finding S-01 by requiring an unguessable per-install or per-session credential for every MCP/REST request, validating browser-facing request origin/host, and applying an explicit filesystem access policy to open/read/export operations. Keep the server opt-in and bind only to loopback.

## Files likely affected

- `src/desktopMain/kotlin/com/openlog/debug/ControlServer.kt`
- `src/desktopMain/kotlin/com/openlog/ui/AppState.kt`
- `src/desktopMain/kotlin/com/openlog/ui/DesktopStorage.kt`
- `src/desktopMain/kotlin/com/openlog/ui/App.kt`
- `src/desktopTest/kotlin/com/openlog/ControlServerTest.kt`
- `src/desktopTest/kotlin/com/openlog/ControlServerMcpTest.kt`
- `src/desktopTest/kotlin/com/openlog/McpConfigSnippetTest.kt`

## Risk level

- Finding severity: **High**
- Implementation risk: **High**, because clients and saved MCP configuration may need a controlled credential migration.

## Expected behavior change

- Requests without valid authentication receive a non-revealing unauthorized response and cannot list tabs, expose paths, open files, or write exports.
- Requests with an untrusted `Origin` or invalid `Host` are rejected before route handling.
- Client identity is derived from authenticated state rather than an optional caller-supplied identifier.
- Open/read/export commands are limited to an explicit policy, such as user-approved paths and app-owned output locations.
- Existing authenticated local clients continue to work after updating their generated configuration.

## Tests/checks to run

- Add route tests for missing, malformed, stale, and valid credentials.
- Add browser-shaped request tests for allowed/no `Origin`, rejected cross-site `Origin`, and invalid `Host`.
- Add tests proving an unauthenticated caller cannot enumerate source paths or perform filesystem operations.
- Add path-policy tests for traversal, symlinks, paths outside approved roots, and an approved export destination.
- Run `./gradlew desktopTest --tests "com.openlog.ControlServerTest"`.
- Run `./gradlew desktopTest --tests "com.openlog.ControlServerMcpTest"`.
- Run `./gradlew desktopTest --tests "com.openlog.McpConfigSnippetTest"`.
- Run the full verification suite documented in the plan index.

## Rollback notes

Revert the PR as one unit if authenticated configuration cannot be generated reliably. Do not roll back by accepting unauthenticated requests. If compatibility is needed, temporarily disable the server and provide an explicit regeneration path for client configuration rather than adding a permissive fallback.
