# openlog-mcp-server

MCP server that drives a running openLog instance via its localhost debug control server
(`../src/desktopMain/kotlin/com/openlog/debug/ControlServer.kt`). This server is a pure network
client — it never touches the Kotlin/JVM process or its source, only `fetch()`s the control
server's JSON endpoints. That also means any MCP client, or `curl`, works identically against the
same base URL.

## Prerequisite: launch openLog with the control server enabled

The control server is off by default (never starts in packaged `.deb`/`.dmg`/`.msi` builds).
Enable it for a dev run with either:

```bash
OPENLOG_DEBUG_CONTROL=8991 ./gradlew desktopRun
# or
./gradlew desktopRun -Dopenlog.debugControl=8991
```

If you use a port other than 8991, also set `OPENLOG_CONTROL_URL` (e.g.
`http://127.0.0.1:9000`) when running this MCP server.

## Run standalone

```bash
npm install
npm start
```

## Tools

| Tool | Purpose |
|---|---|
| `list_tabs` | List every open tab |
| `open_log_file` | Open a file (or bug-report .zip) by absolute path, blocks until parsed |
| `close_tab` | Close a tab |
| `get_filter` / `set_filter` | Read/partially-update a tab's filter |
| `get_visible_lines` | Read the currently rendered log items (post-filter, post-fold) |
| `toggle_group` | Expand/collapse a sequence or manual-collapse group |
| `get_tags` | List every distinct tag in a tab's full file |
| `get_crash_sites` | List detected exceptions and ANRs in a tab's full file, with jump-to log ids |
| `merge_tabs` | Merge 2+ already-open tabs into one new tab, interleaved by time-of-day |

Registered for Claude Code via `.mcp.json` at the repo root.
