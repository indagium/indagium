# openLog

A desktop log viewer for Android logcat files, built with Kotlin and Compose Multiplatform.

![Version](https://img.shields.io/badge/version-1.5.1-blue)
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
- **In-app AI assistant** — use LM Studio, OpenAI, Anthropic, Codex, Claude Code, or another compatible provider to investigate the active log tab with the same log, filter, source, and notes tools exposed through MCP
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

**Notes** and **AI** are independent toggles on the main toolbar (next to **Filter**), each showing
or hiding its own panel in the same resizable sidebar slot. With only one on, it fills the slot;
with both on, it splits into Notes above AI at a draggable divider. The AI assistant is optional
and runs only when you send a request. Its first provider is an OpenAI-compatible Chat Completions
endpoint, so the default profile works with a local LM Studio server:

1. In LM Studio, load a tool-capable model and start its local API server.
2. Open a log tab in openLog, turn on **AI** in the toolbar, and choose the loaded model from the
   dropdown (click it to browse discovered models, or type an id manually at the bottom of the
   list). If discovery is unavailable, manual entry still works.
3. Ask a question or use a quick action such as **Check error**, **Find root cause**, **Build
   timeline**, or **Investigate issue**. You can also right-click a log line and choose **Ask AI**.

Each reply shows when the request was sent, how long the first response and the full answer took,
and the reported token usage if the provider includes it. Tool-call activity for a request appears
in a collapsible, independently scrollable **Investigation** section between your message and the
final answer — expanded while it's running, collapsed once it finishes (click to reopen). Chat text
is selectable/copyable, and **Reset** clears the current tab's conversation to start over.

The built-in `LM Studio (local)` profile uses `http://127.0.0.1:1234/v1`. **Settings → AI
providers** also offers explicit **OpenAI API** and **Anthropic API** profiles, alongside the
existing generic OpenAI-compatible option. API keys are memory-only for the current app launch;
they are not written to autosave, settings, notes, or exports and must be entered again after
restart.

**Codex account** and **Claude Code account** profiles use a locally installed CLI executable and
its existing signed-in account instead of an API key. Settings can detect a common installation
or let you browse to the executable. On macOS, Codex can use the `codex` binary bundled inside
ChatGPT; a desktop app bundle itself is not enough. Claude requires the Claude Code CLI. Each request gets a fresh empty temporary
workspace plus a private, short-lived managed MCP endpoint. That endpoint exposes the same
openLog tools and confirmation rules as the LM Studio panel path, pins requests to the active log
tab, and is revoked when the run ends. The account agents do not receive source-folder or app
workspace access; log and source evidence is available only through openLog tools.

### Data and action safety

Loopback endpoints (`127.0.0.1`, `localhost`, and equivalent local addresses) are treated as
local. A non-local provider (HTTP or HTTPS — some local-network LM Studio setups only ever serve
HTTP) is blocked until you acknowledge in Settings that the request can disclose log text,
selected context, source-code paths and snippets, and tool results to that provider. Use **Test
connection** in Settings → AI providers to check whether an endpoint is reachable before or after
saving; it only probes the endpoint and never saves or otherwise affects the profile.

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

A single request is capped at a configurable number of tool round trips (Settings → AI providers
→ **Max tool rounds per request**, default 100) so an investigation can't run forever. As that
budget runs low, the assistant is told to stop gathering evidence and conclude with what it has —
including writing a note if that was part of the request — rather than being cut off mid-task.

### Troubleshooting

- **"Choose or enter a model"** — select a model returned by **Find** or type the exact model id
  exposed by your provider.
- **Connection or stream error** — confirm the endpoint is reachable, that LM Studio's server is
  running, and that the endpoint includes its required API version path (the default ends in
  `/v1`). Use **Test connection** in Settings → AI providers to check reachability directly.
- **No models returned** — model discovery is optional; enter the model id manually.
- **Codex or Claude Code cannot start** — use **Detect** or **Browse** in the account profile to
  select its CLI executable, then sign in to that CLI. The account profile does not accept or
  store API keys.
- **Remote provider blocked** — save the profile after acknowledging the remote data disclosure
  in Settings (HTTP and HTTPS are both allowed once acknowledged).
- **"Stopped after N tool rounds"** — a multi-step investigation (filtering, reading lines, then
  writing a note) can need many rounds, especially with a smaller local model. Raise **Max tool
  rounds per request** in Settings → AI providers.
- **An investigation waits** — inspect the tool trace and choose **Allow** or **Deny** on its
  confirmation card. Use **Stop** if the action is no longer wanted.

The AI integration is separate from the built-in MCP server. MCP remains useful for external
clients such as LM Studio, Codex, and Claude Code; see [the MCP guide](docs/mcp/README.md).

## Building from source

**Requirements:** JDK 21+

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
git tag v1.5.1 && git push --tags
```

The macOS build is unsigned (no Apple Developer certificate in CI) — see the Installation section above for the Gatekeeper workaround.

### GitLab mirror

The repo is also mirrored to GitLab (`gitlab.com/rarnaut-dev-group/openlog2`), which runs [.gitlab-ci.yml](.gitlab-ci.yml) on GitLab's shared runners — a separate compute/storage pool from GitHub Actions, useful if GitHub's Actions quota is exhausted. The mirror is manual, not automatic: after pushing to `origin`, run:

```bash
./scripts/push-gitlab-mirror.sh
```

Pushing a `v*.*.*` tag there builds the Linux `.deb` automatically; Windows `.msi` and macOS `.dmg` are optional manual jobs in the resulting pipeline (triggered with the "play" button), since GitLab's Windows/macOS SaaS runners are pricier and only worth running by request.

## License

openLog is source-available under the [PolyForm Noncommercial License 1.0.0](LICENSE). It is free for permitted personal and other non-commercial use.

Any business or work-related use, including use by a company, employee, contractor, consultant, freelancer, or sole proprietor, requires a separate written commercial license. Start a commercial-license request in [GitHub Discussions](https://github.com/rarnaut-dev/openLog2/discussions).

The openLog name, logo, icon, and branding are reserved; see [NOTICE](NOTICE). Third-party component notices are listed in [THIRD_PARTY_NOTICES.md](THIRD_PARTY_NOTICES.md).
