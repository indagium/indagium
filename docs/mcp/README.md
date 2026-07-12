# Driving openLog with an MCP client

openLog has a Model Context Protocol server **built into the app** — it speaks MCP over
Streamable HTTP, so any MCP client (LM Studio, Claude Code, Codex, or your own tooling) connects
with just a URL. There is nothing to install: no Node.js, no `npm`, no repo checkout.

## Enable it

The server is off by default and never runs in a packaged build unless you turn it on.

- **Installed app:** Settings → Automation → *MCP control server* → **On**. The default port is
  8991 (configurable). Click *Connection info…* for the exact URL and a copyable client config.
- **Dev run:** `OPENLOG_DEBUG_CONTROL=8991 ./gradlew desktopRun` (or `-Dopenlog.debugControl=8991`).

## Connect a client

The endpoint is `http://127.0.0.1:8991/mcp` (swap the port if you changed it). Every request
also requires the bearer token shown by **Connection info…** in openLog Settings.

**LM Studio / Cursor-style `mcp.json`** (e.g. `~/.lmstudio/mcp.json`):

```json
{
  "mcpServers": {
    "openlog-control": {
      "url": "http://127.0.0.1:8991/mcp",
      "headers": {
        "Authorization": "Bearer PASTE_THE_TOKEN_FROM_CONNECTION_INFO"
      }
    }
  }
}
```

**Claude Code:**

```bash
claude mcp add --transport http openlog http://127.0.0.1:8991/mcp
```

**Codex** (`~/.codex/config.toml`): add a streamable-HTTP MCP server pointed at the same URL.

The repo root `.mcp.json` already registers this for tools that auto-discover it.

## Tools

The server exposes 32 tools covering the whole analysis workflow — open files/archives, split
oversized logs, read/set filters (including message rules and sequences), read the rendered
(post-fold) lines, read unfiltered context around a line, list crash sites, tags and packages,
manage selection and collapsible groups, write annotations, export, merge tabs, and live-tail.
Call `tools/list` for the authoritative set and their schemas.

A few worth calling out:
- `get_filter` returns the **full** filter, including `messageRules` and `sequences` — both can
  hide or fold rows even when tags/levels look empty. `set_filter` accepts them too, plus
  `clearMessageRules` / `clearSequences` to remove a stale one.
- `get_line_context` reads raw lines around a line id **ignoring the active filter and folding** —
  the reliable way to see what surrounds a filtered line without touching the filter.
- `get_visible_lines` / `get_line_context` accept `fields` (column whitelist) and `compact` to
  shrink the payload when you don't need every column.
- `get_packages` lists dotted tag-prefixes (with counts) to discover values for `pkgPrefixes`.
- `get_project_info` returns the description/README set per registered source folder in
  Settings → Source code — useful project context before a code-level investigation.

See [ANALYSIS_PLAYBOOK.md](ANALYSIS_PLAYBOOK.md) for a system-prompt skeleton that teaches an
agent how to actually investigate a log with these tools.

## Direct HTTP (no MCP)

The same server also serves a plain JSON/REST surface for quick scripting or `curl`, e.g.
`curl -H "Authorization: Bearer PASTE_THE_TOKEN_FROM_CONNECTION_INFO" http://127.0.0.1:8991/tabs`.
This is the escape hatch the MCP tools are built on; the MCP endpoint at `/mcp` is what agent
clients should use.
