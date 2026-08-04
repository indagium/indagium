# Indagium

A desktop log viewer for Android logcat files, built with Kotlin and Compose Multiplatform.

![Version](https://img.shields.io/badge/version-1.8.0-blue)
![Platform](https://img.shields.io/badge/platform-macOS%20%7C%20Linux%20%7C%20Windows-lightgrey)

![Folding a repeating region into a collapsible sequence](docs/images/gif-05-sequences.gif)

[Full user guide](docs/USER_GUIDE.md)

## Features

**Filtering and folding**

- **Multi-tab** — open multiple log files side by side
- **Log level filtering** — toggle V / D / I / W / E / A individually
- **Tag filters** — include or exclude tags with exact match or regex; package-level grouping
- **Message rules** — filter lines by message content or PID/TID (substring or regex), optionally scoped to a tag or package
- **Sequences** — collapse recurring regions into colored, nestable groups using start/end patterns
- **Manual collapse** — fold to start, to end, or an explicit range when there's no pattern to match
- **Highlighters** — color-code lines by message pattern without filtering anything out
- **Find bar** — in-view search across the fully expanded log
- **Filter presets** — save, organize into folders, favorite, export and import filter configurations

**Reading**

- **Crash, ANR and native-crash detection** — stack traces fold automatically; custom issue rules add your own categories
- **Minimap** — compressed overview of the whole log in place of the scrollbar
- **Thread map** — color every thread in a process and see interleaved activity as a branch gutter
- **Time deltas** — per-row gaps, or offsets from a selected anchor row, to find stalls
- **Compare view** — two tabs side by side, each with its own filter or mirroring the other's
- **Live tailing** — watch a file as it's written, with your filter already applied
- **Large-file support** — multi-gigabyte logs, with an optional split-on-open for the biggest
- **Bug reports** — open `.zip` and `.7z` Android bug reports directly and pick what to load

**Producing output**

- **Annotations** — annotate log selections with notes, images, and video frames; export as Markdown or Jira markup
- **Export filtered log** — write the current filtered set to TXT or CSV
- **Merge and split** — interleave several logs by timestamp, or split one huge file into parts
- **Show in code** — register your project's source folder(s) in Settings, then right-click a log line to view the exact method that emitted it (Kotlin/Java, `Log.*` + Timber, plus custom wrappers); also exposed to AI assistants via the `resolve_log_source` MCP tool

**Extras**

- **In-app AI assistant** — use LM Studio, OpenAI, Anthropic, Codex, Claude Code, or another compatible provider to investigate the active log tab with the same log, filter, source, and notes tools exposed through MCP; on-device voice dictation inserts editable text into the composer
- **Video sync** — attach a screen recording, anchor it to a log line, and scrub either from the other
- **MCP control server** — drive Indagium from any MCP client over a local URL (off by default)
- **Themes** — 20 built-in themes (light, dark, and paper variants)
- **Autosave** — session is fully restored on next launch
- **Update checker** — optional in-app check against GitHub Releases

### Supported logcat formats

`threadtime`, `time`, `brief`, `bare` — unrecognised lines are shown with tag `RAW`.

## Documentation

| Document | What it covers |
|---|---|
| [User guide](docs/USER_GUIDE.md) | How to use every feature, keyboard shortcuts, and worked recipes |
| [Architecture (SAAD)](docs/SAAD.md) | System design, module boundaries, data flow, threading, persistence, security |
| [MCP guide](docs/mcp/README.md) | Connecting an external MCP client |
| [MCP methods](docs/mcp/AVAILABLE_METHODS.md) | The automation tool reference |
| [Analysis playbook](docs/mcp/ANALYSIS_PLAYBOOK.md) | Prompt patterns for log analysis |

## Installation

Download the latest release for your platform from the [Releases](../../releases) page:

| Platform | File |
|---|---|
| Linux (x86-64) | `indagium_x.y.z-1_amd64.deb` |
| Linux (arm64) | `indagium_x.y.z-1_arm64.deb` |
| Windows | `Indagium-x.y.z.msi` |
| macOS (Apple Silicon) | `Indagium-x.y.z.dmg` |

### macOS: "could not verify... free of malware"

The macOS build isn't signed with an Apple Developer ID or notarized, so Gatekeeper blocks it
once the `.dmg` has been downloaded through a browser (locally built copies aren't affected —
they never get the quarantine flag a browser download adds). To open it anyway, either:

- Terminal: `xattr -cr /Applications/Indagium.app`, or
- System Settings → Privacy & Security → scroll to the "Indagium was blocked" notice → **Open Anyway**

### Linux

Install with `sudo dpkg -i indagium_x.y.z-1_amd64.deb` (substitute `arm64` for `amd64` if that's
the package you downloaded, or use your package manager's equivalent). The
package registers Indagium as a candidate handler for `.log`/`.txt`/`.logcat`/`.trace`/`.out`
files, but does **not** make itself the system default — a package has no business silently
rewriting another user's `mimeapps.list`. To open `.log`/`.txt` files with Indagium by default,
opt in yourself:

```bash
xdg-mime default indagium-Indagium.desktop text/plain text/x-log
```

or right-click a file in your file manager → **Open With** → **Indagium** → set as default.

## In-app AI assistant

**Notes** and **AI** are independent toggles on the main toolbar (next to **Filter**), each showing
or hiding its own panel in the same resizable sidebar slot. With only one on, it fills the slot;
with both on, it splits into Notes above AI at a draggable divider. The AI assistant is optional
and runs only when you send a request. Its first provider is an OpenAI-compatible Chat Completions
endpoint, so the default profile works with a local LM Studio server:

1. In LM Studio, load a tool-capable model and start its local API server.
2. Open a log tab in Indagium, turn on **AI** in the toolbar, and choose the loaded model from the
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
Indagium tools and confirmation rules as the LM Studio panel path, pins requests to the active log
tab, and is revoked when the run ends. The account agents do not receive source-folder or app
workspace access; log and source evidence is available only through Indagium tools.

### Local voice dictation

The microphone button beside **Send** records the default microphone in memory, transcribes it
with a local Whisper model, and appends the result to the editable composer. The default language
choices are **Automatic**, **Ukrainian**, and **English**; Settings can add other Whisper languages.
The `EN` control beside the microphone switches local English translation on or off. Nothing is
sent and a request is never started until you edit or accept the text and press **Send** yourself.

On first use, Indagium asks before downloading the selected multilingual Whisper model: Base
(~142 MiB, faster) or Small (~465 MiB, more accurate for short non-English dictation). After that
one-time model download, recording and transcription work offline and independently of the
selected chat provider on macOS, Windows, and Linux. **Settings → Voice input** also offers an
opt-in native engine where available: strictly on-device Apple Speech on macOS or an installed
offline Windows Speech language pack on Windows. Native engines never fall back to cloud speech;
they currently transcribe in the spoken language, while Whisper also supports local English
translation. The downloaded Whisper model lives in the app-data `voice-models` folder; audio
recordings and transcripts are never written there or kept after the dictation attempt.

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
shows that tab's separate session; relaunching Indagium clears all AI sessions. Use **Stop** (or
Escape while a run is active) to cancel the active request. **Retry** resends the last request in
that tab after a provider error or cancellation.

A single request is capped at a configurable number of MCP analysis/operational calls (Settings →
AI providers → **Max MCP tool calls per request**, default 100) so an investigation can't run
forever. The budget is shared by every provider, including managed Codex and Claude Code sessions.
Notes and annotation reads/writes are unlimited and do not consume it, so an agent can always
inspect, revise, and save durable Notes output. The trace shows analysis/operational usage, Notes
writes as unlimited, returned character count, and result truncation count; the model receives
automatic budget guidance and a footer only when 5, 3, or 1 analysis/operational calls remain.

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
- **"MCP ... budget exhausted"** — the analysis/operational allowance is spent. Notes and
  annotation operations remain available; raise **Max MCP tool calls per request** in Settings →
  AI providers if more log/source investigation is needed.
- **An investigation waits** — inspect the tool trace and choose **Allow** or **Deny** on its
  confirmation card. Use **Stop** if the action is no longer wanted.

The AI integration is separate from the built-in MCP server. MCP remains useful for external
clients such as LM Studio, Codex, and Claude Code; see [the MCP guide](docs/mcp/README.md) and
the [available methods and prompt guide](docs/mcp/AVAILABLE_METHODS.md).

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

Push a version tag to trigger the GitHub Actions build, which produces Linux (x86-64 and arm64), Windows, and macOS packages and creates a GitHub Release automatically:

```bash
git tag v1.8.0 && git push --tags
```

The macOS build is unsigned (no Apple Developer certificate in CI) — see the Installation section above for the Gatekeeper workaround.

### GitLab mirror

The repo is also mirrored to GitLab (`gitlab.com/rarnaut-dev-group/indagium`), which runs [.gitlab-ci.yml](.gitlab-ci.yml) on GitLab's shared runners — a separate compute/storage pool from GitHub Actions, useful if GitHub's Actions quota is exhausted. The mirror is manual, not automatic: after pushing to `origin`, run:

```bash
./scripts/push-gitlab-mirror.sh
```

Pushing a `v*.*.*` tag there builds the Linux `.deb` automatically; Windows `.msi` and macOS `.dmg` are optional manual jobs in the resulting pipeline (triggered with the "play" button), since GitLab's Windows/macOS SaaS runners are pricier and only worth running by request.

## License

Indagium is source-available under the [PolyForm Perimeter License 1.0.0](LICENSE). It is free to use for commercial and non-commercial purposes.

You may not provide a product that competes with Indagium, including a free or paid substitute distributed under different branding. See the [license](LICENSE) for the complete terms.

The Indagium name, logo, icon, and branding are reserved; see [NOTICE](NOTICE). Third-party component notices are listed in [THIRD_PARTY_NOTICES.md](THIRD_PARTY_NOTICES.md).
