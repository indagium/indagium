# Task 02 — Complete authenticated browser MCP CORS support

## Goal

Make the opt-in browser-MCP setting functional for authenticated Streamable
HTTP clients while preserving default-off CORS, loopback Host validation, and
bearer authentication on every real request.

## Evidence

- The server requires `Authorization: Bearer ...`.
- The CORS block allows MCP and content headers but omits `Authorization`.
- Existing tests send a direct GET with `Origin`; they do not exercise browser
  OPTIONS preflight.
- Streamable HTTP uses non-simple requests and session lifecycle methods that
  must be included in the CORS contract.

## Severity and implementation risk

- Finding severity: **Medium**
- Implementation risk: **Low to Medium**, limited to opt-in browser transport.

## Files likely affected

- `src/desktopMain/kotlin/com/openlog/debug/ControlServer.kt`
  - CORS installation in `ControlServer.start()`
- `src/desktopTest/kotlin/com/openlog/ControlServerTest.kt`
- `src/desktopTest/kotlin/com/openlog/ControlServerMcpTest.kt`

## Dependencies

None. Land before Task 03 so lifecycle tests target the completed protocol.

## Implementation approach

1. Use `HttpHeaders` constants where available.
2. Allow these request headers when browser clients are enabled:
   - `Authorization`;
   - `Content-Type`;
   - `Mcp-Session-Id`;
   - `Mcp-Protocol-Version`.
3. Audit the actual `/mcp` route methods and explicitly allow required
   non-default methods, including session-closing `DELETE` if registered by
   the SDK. Keep `POST` support explicit in tests.
4. Remove the comment stating authenticated browser calls remain unsupported.
5. Preserve:
   - CORS disabled by default;
   - loopback Host allowlist;
   - bearer-token intercept for the actual request;
   - managed-run tokens;
   - native non-browser MCP behavior.
6. Fix the touched-file declaration-spacing ktlint violation rather than
   carrying it to the final cleanup task.

## Non-goals

- No CORS default change.
- No authentication weakening or token-in-query fallback.
- No new origin-management UI.
- No MCP SDK or Ktor upgrade.
- No redesign of native client configuration.

## Required tests

Add a real OPTIONS preflight against `/mcp` containing:

- `Origin`;
- `Access-Control-Request-Method: POST`;
- `Access-Control-Request-Headers` with authorization, content type, and both
  MCP headers.

Assert:

- preflight succeeds without a bearer token;
- `Access-Control-Allow-Origin` is present;
- requested headers are allowed using case-insensitive comparison;
- the subsequent authenticated request succeeds;
- the same actual request without a token remains `401`;
- default-off construction emits no CORS grant.

If the route supports `DELETE`, add a separate DELETE preflight/session-close
test so browser clients can terminate sessions.

## Acceptance criteria

- An opted-in browser client can complete preflight and send an authenticated
  MCP request.
- Browser session closing works for every registered MCP method.
- Preflight itself does not require auth, but real requests still do.
- CORS remains absent when the setting is off.
- Host and token security checks are unchanged.

## Terra reviewer checklist

- Verify the test is a real OPTIONS preflight, not a normal JVM request with
  only an `Origin` header.
- Verify all requested headers appear in the allow response.
- Verify no permissive auth bypass was added.
- Verify method coverage matches the SDK route.
- Check native MCP tests remain unchanged in behavior.

## Focused and full gates

```bash
./gradlew desktopTest --tests "com.openlog.ControlServerTest"
./gradlew desktopTest --tests "com.openlog.ControlServerMcpTest"
./gradlew desktopTest
git diff --check
```

## Rollback notes

Reverting restores default-off but partially functional browser CORS. Saved
settings and native clients require no migration.

## Suggested commit

`fix: allow authenticated browser MCP preflight`
