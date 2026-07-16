# Task 03 — Linearize restart-sensitive control-server changes

## Goal

Ensure CORS changes and token rotation cannot publish a server constructed with
stale configuration when start/bind is still in flight. Preserve the actual
runtime port and session-only enablement semantics.

## Evidence

- `setMcpAllowBrowserClients()` restarts only when `controlServer != null`.
- During a slow bind, `controlServer` is null while a start job is active.
- `rotateControlToken()` has the same published-server-only condition.
- Restarting a session/environment-started server currently risks using
  persisted `settings.mcpControlPort` instead of the actual requested port.
- Existing generation tests already provide a delayed factory seam.

## Severity and implementation risk

- Finding severity: **Medium**
- Implementation risk: **Medium**, because persisted and session-only server
  lifecycles share the manager.

## Files likely affected

- `src/desktopMain/kotlin/com/openlog/ui/ControlServerManager.kt`
  - `applyControlServerState`
  - `setMcpAllowBrowserClients`
  - `rotateControlToken`
  - start generation/job bookkeeping
- `src/desktopMain/kotlin/com/openlog/ui/AppState.kt`
  - factory seam or forwarding methods if needed
- `src/desktopTest/kotlin/com/openlog/AppStateBehaviorTest.kt`
- `src/desktopTest/kotlin/com/openlog/ControlServerTest.kt`

## Dependencies

Task 02, so the restarted server's target CORS contract is already correct.

## Implementation approach

1. Track desired runtime server state independently of the published
   `controlServer` reference:
   - desired enabled/running state;
   - actual requested port;
   - current generation/start job;
   - whether enablement is persisted or session-only if needed.
2. Treat an in-flight start as active restart state.
3. Centralize restart-sensitive changes:
   - persist/update the underlying setting or token first;
   - capture the actual desired runtime port;
   - invalidate/cancel the old generation;
   - start a new generation that reads the new configuration/token.
4. Apply the helper to:
   - browser-CORS changes;
   - token rotation;
   - any adjacent restart path already using disable/re-enable.
5. Clear current-generation job/desired-state bookkeeping on success, failure,
   disable, and shutdown without allowing stale completion to erase newer
   state.
6. Preserve:
   - session-only server port selected by environment/system property;
   - session-only enablement not being persisted;
   - synchronous stop;
   - existing failure rollback for persisted enablement;
   - no duplicate live server/listener.

## Non-goals

- No full lifecycle rewrite or actor conversion.
- No change to token format.
- No asynchronous stop.
- No persistence of an environment/session-only enable request.
- No new server UI.

## Required tests

- Delayed factory captures `mcpAllowBrowserClients=false`; toggle to true before
  start completes; assert the old generation never publishes and the final
  server was constructed with true.
- Disable during the replacement; assert no server publishes.
- Start session-only on a non-settings port, toggle CORS, and assert restart
  retains the actual runtime port without persisting enablement.
- Rotate token after the factory captured the old token but before bind
  completes; assert the final server accepts only the new token.
- Rapid repeated CORS toggles publish only the latest generation.
- Current-generation failure clears bookkeeping and leaves the documented
  persisted/ephemeral state.
- Existing disable/reenable, port-conflict, rotation, and shutdown race tests
  remain green.

## Acceptance criteria

- In-flight and published starts follow the same restart semantics.
- No stale CORS configuration or old token can become reachable.
- Only one final server remains listening.
- Runtime port/source semantics are preserved for both persisted and
  session-only starts.
- Disable and shutdown always win over an older start.

## Terra reviewer checklist

- Verify desired state is separate from published state.
- Verify stale completion cannot clear or replace current-generation fields.
- Verify token rotation is covered, not only CORS.
- Verify the actual runtime port is used during restart.
- Look for leaked server instances after cancellation or failure.
- Reject changes that persist session-only enablement.

## Focused and full gates

```bash
./gradlew desktopTest --tests "com.openlog.AppStateBehaviorTest.*Control*"
./gradlew desktopTest --tests "com.openlog.ControlServerTest"
./gradlew desktopTest
git diff --check
```

## Rollback notes

No data migration is introduced. Reverting restores the current generation
guard and its known restart-during-start gaps.

## Suggested commit

`fix: restart MCP after runtime security changes`
