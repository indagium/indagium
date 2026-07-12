# openLog

A desktop log viewer for Android logcat files, built with Kotlin and Compose Multiplatform.

![Version](https://img.shields.io/badge/version-1.2.0-blue)
![Platform](https://img.shields.io/badge/platform-macOS%20%7C%20Linux%20%7C%20Windows-lightgrey)

## Features

- **Multi-tab** — open multiple log files side by side
- **Log level filtering** — toggle V / D / I / W / E / F / S individually
- **Tag filters** — include or exclude tags with exact match or regex; package-level grouping
- **Message rules** — filter lines by message content (substring or regex)
- **Sequences** — auto-detect and collapse recurring tag patterns into collapsible groups
- **Highlighters** — color-code lines by message pattern
- **Annotations** — annotate log selections with notes, exported as Markdown
- **Show in code** — register your project's source folder(s) in Settings, then right-click a log line to view the exact method that emitted it (Kotlin/Java, `Log.*` + Timber); also exposed to AI assistants via the `resolve_log_source` MCP tool
- **In-app AI assistant** — use a local LM Studio model or another OpenAI-compatible provider to investigate the active log tab with the same log, filter, source, and notes tools exposed through MCP
- **Compare view** — diff two open tabs line by line
- **Themes** — 20 built-in themes (light, dark, and paper variants)
- **Autosave** — session is fully restored on next launch
- **Filter presets** — save and load filter configurations

### Supported logcat formats

`threadtime`, `time`, `brief`, `bare` — unrecognised lines are shown with tag `RAW`.

## Installation

Download the latest release for your platform from the [Releases](../../releases) page:

| Platform | File |
|---|---|
| Linux | `openLog_x.y.z_amd64.deb` |
| Windows | `openLog-x.y.z.msi` |
| macOS | `openLog-x.y.z.dmg` |

### macOS: "could not verify... free of malware"

The macOS build isn't signed with an Apple Developer ID or notarized, so Gatekeeper blocks it
once the `.dmg` has been downloaded through a browser (locally built copies aren't affected —
they never get the quarantine flag a browser download adds). To open it anyway, either:

- Terminal: `xattr -cr /Applications/openLog.app`, or
- System Settings → Privacy & Security → scroll to the "openLog was blocked" notice → **Open Anyway**

### Linux

Install with `sudo dpkg -i openLog_x.y.z_amd64.deb` (or your package manager's equivalent). The
package registers openLog as a candidate handler for `.log`/`.txt`/`.logcat`/`.trace`/`.out`
files, but does **not** make itself the system default — a package has no business silently
rewriting another user's `mimeapps.list`. To open `.log`/`.txt` files with openLog by default,
opt in yourself:

```bash
xdg-mime default openlog-openLog.desktop text/plain text/x-log
```

or right-click a file in your file manager → **Open With** → **openLog** → set as default.

## In-app AI assistant

The right sidebar has **Notes** and **AI** tabs. The AI assistant is optional and runs only when
you send a request. Its first provider is an OpenAI-compatible Chat Completions endpoint, so the
default profile works with a local LM Studio server:

1. In LM Studio, load a tool-capable model and start its local API server.
2. Open a log tab in openLog, select **AI** in the right sidebar, and enter or choose the loaded
   model id. **Find** asks the provider for available models; if that endpoint is unavailable,
   entering the model id manually still works.
3. Ask a question or use a quick action such as **Selected error**, **Root cause**, **Timeline**,
   **Filtered result**, or **Mapped source**. You can also right-click a log line and choose
   **Ask AI**.

The built-in `LM Studio (local)` profile uses `http://127.0.0.1:1234/v1`. A key is normally not
needed for LM Studio. Add or edit compatible-provider profiles in **Settings → AI providers**;
profiles retain only their name, endpoint, selected model, and remote-disclosure acknowledgement.
The API-key field is memory-only for the current app launch. It is not written to autosave,
settings, notes, or exports, and must be entered again after restart.

### Data and action safety

Loopback endpoints (`127.0.0.1`, `localhost`, and equivalent local addresses) are treated as
local. A non-local provider must use HTTPS and is blocked until you acknowledge in Settings that
the request can disclose log text, selected context, source-code paths and snippets, and tool
results to that provider.

The assistant can automatically read log/source context and apply filter, selection, folding, and
annotation changes. Actions that affect files or tab lifecycle always pause for an **Allow** or
**Deny** card: opening, splitting, closing, or merging tabs; starting/stopping tailing; exports;
and saving/loading annotation files. The tool trace shows what it requested and returned.
Clickable evidence cards are created only from actual tool results, never from line numbers or
paths invented in the model's prose.

Each conversation is scoped to one log tab and exists only for the current launch. Switching tabs
shows that tab's separate session; relaunching openLog clears all AI sessions. Use **Stop** (or
Escape while a run is active) to cancel the active request. **Retry** resends the last request in
that tab after a provider error or cancellation.

### Troubleshooting

- **"Choose or enter a model"** — select a model returned by **Find** or type the exact model id
  exposed by your provider.
- **Connection or stream error** — confirm the endpoint is reachable, that LM Studio's server is
  running, and that the endpoint includes its required API version path (the default ends in
  `/v1`).
- **No models returned** — model discovery is optional; enter the model id manually.
- **Remote provider blocked** — use HTTPS, then save the profile after acknowledging the remote
  data disclosure in Settings.
- **An investigation waits** — inspect the tool trace and choose **Allow** or **Deny** on its
  confirmation card. Use **Stop** if the action is no longer wanted.

The AI integration is separate from the built-in MCP server. MCP remains useful for external
clients such as LM Studio, Codex, and Claude Code; see [the MCP guide](docs/mcp/README.md).

## Building from source

**Requirements:** JDK 17+

```bash
# Run
./gradlew desktopRun

# Test
./gradlew desktopTest

# Package (run on the target OS)
./gradlew packageDmg   # macOS
./gradlew packageDeb   # Linux
./gradlew packageMsi   # Windows
```

## Releasing

Push a version tag to trigger the GitHub Actions build, which produces Linux, Windows, and macOS packages and creates a GitHub Release automatically:

```bash
git tag v1.2.0 && git push --tags
```

The macOS build is unsigned (no Apple Developer certificate in CI) — see the Installation section above for the Gatekeeper workaround.

## License

Copyright © 2026 Roman Arnaut
