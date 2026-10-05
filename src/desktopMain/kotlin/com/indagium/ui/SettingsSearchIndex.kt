package com.indagium.ui

// Search index behind the Settings dialog's "Search settings" field: one entry per labelled setting
// row. Labels are copied from the literals the rows render (so a result reads exactly like the row
// it points at) and each entry's [anchor] is the key the row registers with Modifier.settingsAnchor
// (ui/SettingsSearchUi.kt) so a clicked result can scroll to it. Add an entry here whenever a
// labelled row is added to a section.

internal data class SettingsSearchEntry(
    val id: String,
    val section: SettingsSection,
    val label: String,
    val hint: String,
    val keywords: List<String> = emptyList(),
    /** The key the row passes to Modifier.settingsAnchor; differs from [label] only when the UI
     *  label has a hard line break or is dynamic. */
    val anchor: String = label,
)

private fun entry(
    section: SettingsSection,
    label: String,
    hint: String,
    keywords: List<String> = emptyList(),
    anchor: String = label,
): SettingsSearchEntry {
    val slug = anchor.lowercase().replace(Regex("[^a-z0-9]+"), "-").trim('-')
    return SettingsSearchEntry("${section.name}.$slug", section, label, hint, keywords, anchor)
}

private fun general() = listOf(
    entry(
        SettingsSection.General, "Workspace profile",
        "Sets panels, filter placement, theme and font size in one step.",
        listOf("profile", "layout", "classic", "focused", "compare", "minimal", "logcat query", "workspace"),
    ),
    entry(
        SettingsSection.General, "Save as new profile",
        "Save the current theme, font size and panel layout as your own workspace profile.",
        listOf("workspace", "preset", "layout", "custom", "save profile", "my profile"),
    ),
    entry(
        SettingsSection.General, "Import profile",
        "Load a workspace profile from a .json file someone shared.",
        listOf("workspace", "preset", "layout", "share", "json", "open"),
    ),
    entry(
        SettingsSection.General, "Export profile",
        "Save your current setup, or one of your profiles, as a shareable .json file.",
        listOf("workspace", "preset", "layout", "share", "json", "save"),
    ),
    entry(
        SettingsSection.General, "Default save folder",
        "Parent folder every other save location defaults under. Defaults to your Documents folder.",
        listOf("documents", "root"),
    ),
    entry(
        SettingsSection.General, "Analysis artifacts folder",
        "Where analysis notes, filtered exports and split logs are saved.",
        listOf("notes", "exports", "split logs", "save folder"),
    ),
    entry(
        SettingsSection.General, "Capture sessions folder",
        "Where new device captures are recorded; applies from the next Start.",
        listOf("recordings", "device", "save folder"),
    ),
    entry(
        SettingsSection.General, "Snapshots folder",
        "Destination for \"Save snapshot\" while a capture is recording.",
        listOf("screenshots", "save folder"),
    ),
    entry(
        SettingsSection.General, "Saved captures folder (Save ZIP)",
        "Destination for \"Save ZIP\" on a stopped or retained capture.",
        listOf("zip", "archive", "save folder"),
    ),
    entry(
        SettingsSection.General, "Storage",
        "Where Indagium keeps its data, and the temporary data and app data it holds.",
        listOf("app data", "cache", "disk", "space", "appdata"),
    ),
    entry(
        SettingsSection.General, "Temporary data",
        "Downloaded archive cache and app-managed notes. Clear temporary data removes these items.",
        listOf("clear", "cache", "delete", "temp"),
    ),
    entry(
        SettingsSection.General, "App data",
        "Everything Indagium stores: settings, session, saved filters, indexes and diagnostics. Reset app data wipes it.",
        listOf("reset", "wipe", "delete", "clear", "factory"),
    ),
    entry(
        SettingsSection.General, "Setup assistant",
        "Walk through profile, theme, folders and capture again (Run setup again).",
        listOf("setup", "wizard", "onboarding", "first run", "assistant", "welcome", "run setup"),
    ),
)

private fun appearance() = buildList {
    add(
        entry(
            SettingsSection.Appearance, "Theme",
            "Color theme of the whole window.",
            listOf("dark", "light", "palette", "colors", "look"),
        ),
    )
    add(
        entry(
            SettingsSection.Appearance,
            "Fallback log family",
            "Default monospace or proportional log text when no system family is selected.",
            listOf("typeface", "mono", "font family"),
        ),
    )
    add(
        entry(
            SettingsSection.Appearance,
            "Interface font",
            "Search and preview an installed system font for controls and interface text.",
            listOf("typeface", "ui", "font family"),
        ),
    )
    add(
        entry(
            SettingsSection.Appearance,
            "Log font",
            "Search and preview an installed system font for log rows and highlighter previews.",
            listOf("typeface", "font family", "monospace"),
        ),
    )
    add(
        entry(
            SettingsSection.Appearance, "Log font size",
            "Text size of the log rows.",
            listOf("text size", "zoom", "bigger", "smaller"),
        ),
    )
    add(
        entry(
            SettingsSection.Appearance, "Interface scale",
            "Scales the whole interface, not just the log.",
            listOf("zoom", "dpi", "size", "hidpi"),
        ),
    )
    add(
        entry(
            SettingsSection.Appearance, "Toolbar labels",
            "Hides text on the main toolbar buttons, leaving only their icons.",
            listOf("icons only", "buttons"),
        ),
    )
    if (isLinuxOs) {
        add(
            entry(
                SettingsSection.Appearance, "File picker",
                "Native GTK or Compatibility X11 file dialogs; restart to apply.",
                listOf("dialog", "linux", "gtk", "x11", "open"),
            ),
        )
    }
}

private fun editorBehavior() = listOf(
    entry(SettingsSection.EditorBehavior, "Visible tabs", "How many tabs show before the rest collapse into a menu.", listOf("tab bar", "overflow")),
    entry(
        SettingsSection.EditorBehavior, "Keyboard scroll margin",
        "Rows kept in view around the selection when moving with the keyboard.",
        listOf("arrow keys", "context", "navigation"),
    ),
    entry(SettingsSection.EditorBehavior, "Most-used tags", "How many frequent tags are offered as quick picks.", listOf("tag list", "filter")),
    entry(SettingsSection.EditorBehavior, "Filter list rows", "Visible rows of the filter panel's lists.", listOf("sidebar", "height")),
    entry(
        SettingsSection.EditorBehavior, "Follow live logs",
        "Keeps the view pinned to the newest line while a tab is live-watching.",
        listOf("tail", "auto scroll", "autoscroll", "live watching"),
    ),
    entry(
        SettingsSection.EditorBehavior, "Regex summary",
        "Shows retained Tags-mode selectors above the Regex field; informational only.",
        listOf("regex", "tags", "filter"),
    ),
    entry(
        SettingsSection.EditorBehavior, "Row wrapping",
        "Auto wraps long lines to the panel width, or set a fixed wrap column and scroll horizontally.",
        listOf("wrap", "long lines", "horizontal scroll"),
    ),
    entry(
        SettingsSection.EditorBehavior, "Crash rows",
        "Colors every row in an expanded crash/stack-trace group, not just the header.",
        listOf("stack trace", "exception", "highlight", "anr"),
    ),
    entry(
        SettingsSection.EditorBehavior, "Original panel",
        "Whether newly opened files start with the unfiltered Original panel visible.",
        listOf("unfiltered", "split", "new files"),
    ),
    entry(
        SettingsSection.EditorBehavior, "Row number",
        "Shows a left gutter with each row's original row number.",
        listOf("line number", "gutter"),
    ),
    entry(
        SettingsSection.EditorBehavior, "Minimap",
        "Replaces the scrollbar with a text minimap colored by level.",
        listOf("scrollbar", "overview"),
    ),
    entry(
        SettingsSection.EditorBehavior, "Ctrl+F opens",
        "Whether Ctrl/Cmd+F opens the Find bar, or focuses the Tags or Regex filter field.",
        listOf("cmd+f", "find", "search", "shortcut", "keyboard", "find bar"),
    ),
    entry(
        SettingsSection.EditorBehavior, "Ctrl+F opens Original",
        "Reveals the Original panel whenever Ctrl/Cmd+F opens.",
        listOf("cmd+f", "find", "shortcut", "keyboard", "unfiltered"),
    ),
    entry(
        SettingsSection.EditorBehavior, "Process names in new tabs",
        "Whether new tabs show process names in place of numeric pids.",
        listOf("pid", "proc", "process"),
    ),
    entry(
        SettingsSection.EditorBehavior, "Video follow readout",
        "Shows the diagnostic line under the video transport bar explaining what Follow is doing.",
        listOf("video", "sync", "playhead"),
    ),
    entry(
        SettingsSection.EditorBehavior, "New video links: double-click seeks",
        "Default for newly linked log/video anchors; each link can still be toggled from its header.",
        listOf("video", "anchor", "double click", "seek"),
        anchor = "New video links:\ndouble-click seeks",
    ),
)

private fun exportAnnotations() = listOf(
    entry(
        SettingsSection.ExportAnnotations, "Auto-save",
        "Saves note Markdown and its .ann sidecar after note changes.",
        listOf("autosave", "notes", "save", "markdown"),
    ),
    entry(
        SettingsSection.ExportAnnotations, "Filter backups",
        "Writes timestamped saved-filter backups after saved-filter changes.",
        listOf("backup", "saved filters", "save"),
    ),
    entry(SettingsSection.ExportAnnotations, "Number blocks", "Numbers the blocks of a note.", listOf("numbering", "annotation")),
    entry(
        SettingsSection.ExportAnnotations, "Log blocks",
        "Format of log excerpts in exported notes: Indented, Wiki or Cloud.",
        listOf("jira", "wiki", "code block", "annotation"),
    ),
    entry(
        SettingsSection.ExportAnnotations, "Inline Markdown",
        "Shows note and caption fields as rendered Markdown in the Notes panel; click them to edit.",
        listOf("render", "notes", "markdown"),
    ),
    entry(
        SettingsSection.ExportAnnotations, "Pid/Tid copy",
        "Includes PID and TID for log rows when copying lines, annotations or filtered exports.",
        listOf("copy", "clipboard", "pid", "tid", "thread"),
    ),
    entry(
        SettingsSection.ExportAnnotations, "Pid copy as name",
        "Uses a process name learned from the log instead of the numeric PID when copying.",
        listOf("copy", "clipboard", "process name"),
    ),
    entry(
        SettingsSection.ExportAnnotations, "Row number copy",
        "Includes the original log row number when copying and the row-number gutter is visible.",
        listOf("copy", "clipboard", "line number"),
    ),
    entry(
        SettingsSection.ExportAnnotations, "Time delta copy",
        "Includes Δt when copying and the Δt column is visible.",
        listOf("copy", "clipboard", "delta", "elapsed"),
    ),
    entry(
        SettingsSection.ExportAnnotations, "Copy default",
        "Format used by the main Copy button: Cloud, Wiki, Markdown or HTML.",
        listOf("copy", "clipboard", "jira", "html", "format"),
    ),
    entry(
        SettingsSection.ExportAnnotations, "Diagram note action",
        "Which half of the sequence-diagram note action is primary: snapshot or linked note.",
        listOf("sequence diagram", "uml", "snapshot", "link"),
    ),
    entry(
        SettingsSection.ExportAnnotations, "Diagram export",
        "Representation of newly added sequence-diagram notes: image or source.",
        listOf("sequence diagram", "uml", "mermaid", "plantuml", "image"),
    ),
    entry(
        SettingsSection.ExportAnnotations, "Annotation file prefix",
        "Prefix used in front of the log name in note labels, e.g. \"From app.log\".",
        listOf("note", "label", "name", "from"),
    ),
    entry(
        SettingsSection.ExportAnnotations, "Mask word on copy",
        "Replaces listed words with a masked form when copying a note.",
        listOf("redact", "privacy", "censor", "hide", "sensitive", "copy"),
    ),
)

private fun capture() = buildList {
    val c = SettingsSection.Capture
    add(entry(c, "Tools", "adb and scrcpy executables used for device capture.", listOf("adb", "scrcpy", "android", "device", "path")))
    add(entry(c, "ADB path", "Path to adb; leave blank to auto-detect.", listOf("adb", "android", "device", "phone", "executable")))
    add(
        entry(
            c, "scrcpy path (optional — native mirror window only)",
            "Only used when Device display is set to a scrcpy window.",
            listOf("scrcpy", "mirror", "android", "device", "executable"),
        ),
    )
    add(entry(c, "Before start", "Defaults for the New tab's Before start panel.", listOf("capture", "device", "phone", "launcher")))
    add(
        entry(
            c, "Record video to file", "Records the device screen into the retained session.",
            listOf("video", "screen", "recording", "android", "device", "phone"),
        ),
    )
    add(entry(c, "Capture audio", "Captures device audio into the recording.", listOf("sound", "record", "android", "device", "phone")))
    add(
        entry(
            c, "Include earlier device logs",
            "Also copies what the device already holds from before Start.",
            listOf("buffer", "history", "logcat", "android", "device"),
        ),
    )
    add(
        entry(
            c, "Keep sound on the device (Android 13+)",
            "Keeps the phone's speaker on while audio is captured.",
            listOf("audio", "mute", "speaker", "android", "device"),
        ),
    )
    if (!isMacOs) {
        add(
            entry(
                c, "Hardware-accelerated mirror (experimental)",
                "Uses hardware video decoding for the in-app mirror.",
                listOf("gpu", "decode", "mirror", "android", "device", "experimental"),
            ),
        )
    }
    add(
        entry(
            c, "Microphone", "Records audio from this computer's microphone with the screen capture.",
            listOf("audio", "voice", "record", "device"),
        ),
    )
    add(
        entry(
            c, "Device display", "In-app mirror, scrcpy window or off while capturing.",
            listOf("mirror", "scrcpy", "screen", "android", "device", "phone"),
        ),
    )
    add(
        entry(
            c, "Video quality", "Recording preset (Compact, Balanced, Detailed, Smooth) with an approximate size per minute.",
            listOf("quality", "preset", "bitrate", "size per minute", "compact", "balanced", "detailed", "smooth", "video", "resolution"),
        ),
    )
    add(entry(c, "Buffer mode", "Which logcat buffers are captured: default, all or custom.", listOf("logcat", "buffers", "android", "device")))
    add(
        entry(
            c, "Buffers to capture", "Choose the logcat buffers used by Custom buffer mode.",
            listOf("main", "system", "crash", "kernel", "events", "radio", "logcat"),
        ),
    )
    add(entry(c, "Screen recording", "Size, frame rate, bitrate and format of the screen recording.", listOf("video", "android", "device", "phone")))
    add(entry(c, "Max size", "Longest side in pixels of the recording; 0 keeps full resolution.", listOf("resolution", "video", "scale", "screen recording")))
    add(entry(c, "Max FPS", "Frame-rate cap for the screen recording.", listOf("frame rate", "video", "screen recording")))
    add(entry(c, "Bitrate Mbps", "Video bitrate of the screen recording in Mbps.", listOf("quality", "video", "screen recording")))
    add(entry(c, "Saved video format", "Container used by Save snapshot / Save ZIP: MP4 or MKV.", listOf("mp4", "mkv", "video", "container", "export")))
    add(entry(c, "Live audio volume %", "Volume of the embedded mirror's speaker toggle.", listOf("sound", "speaker", "mirror", "audio")))
    add(entry(c, "Session limit GiB", "Largest size a single capture session may grow to.", listOf("storage", "disk", "size", "limit", "gigabytes")))
    add(entry(c, "Reserve GiB", "Free disk space left untouched while capturing.", listOf("storage", "disk", "free space", "gigabytes")))
    add(entry(c, "Snapshot files", "Filename template and label for saved snapshots.", listOf("naming", "export")))
    add(
        entry(
            c, "Filename template", "Placeholders: {device}, {start}, {range}, {counter}.",
            listOf("snapshot", "name", "naming", "export", "save"),
        ),
    )
    add(entry(c, "Label", "Label stored in the capture archive.", listOf("snapshot", "archive", "name")))
    add(entry(c, "Mark issue", "The Mark issue button drops a note with the log window around the press.", listOf("marker", "bug", "note", "screenshot")))
    add(entry(c, "Before, seconds", "Log window kept before pressing Mark issue.", listOf("marker", "pre", "window", "mark issue")))
    add(entry(c, "After, seconds", "Log window kept after pressing Mark issue.", listOf("marker", "post", "window", "mark issue")))
    add(entry(c, "Take a screenshot on Mark issue", "Adds a device screenshot to the marker note.", listOf("marker", "image", "mark issue")))
    add(entry(c, "Include markers in saved snapshots", "Snapshot export does not use this setting yet.", listOf("marker", "notes", "mark issue")))
}

private fun automation() = listOf(
    entry(
        SettingsSection.Automation, "MCP control server",
        "Local server that lets AI clients drive Indagium over MCP.",
        listOf("mcp", "api", "server", "claude", "codex", "automation", "rest"),
    ),
    entry(SettingsSection.Automation, "Port", "Loopback port of the MCP control server.", listOf("mcp", "server", "8991", "network")),
    entry(
        SettingsSection.Automation, "Allow browser-based MCP clients (CORS)",
        "Lets a browser-based MCP inspector call the local server; off by default.",
        listOf("cors", "browser", "mcp", "security", "origin"),
    ),
    entry(
        SettingsSection.Automation, "Connection info",
        "URL, token and config snippets for connecting an MCP client.",
        listOf("mcp", "token", "url", "config", "claude", "codex", "snippet"),
    ),
    entry(
        SettingsSection.Automation, "Debug logging",
        "Writes Indagium's own diagnostic log to a file.",
        listOf("diagnostics", "troubleshooting", "log file", "verbose"),
    ),
    entry(
        SettingsSection.Automation, "Debug log file",
        "Where the diagnostic log is written; open the current one from here.",
        listOf("path", "diagnostics", "browse", "open current log"),
    ),
    entry(
        SettingsSection.Automation, "Check for updates automatically",
        "Checks GitHub Releases for a newer version at startup.",
        listOf("update", "upgrade", "version", "release", "github", "check now"),
    ),
)

private fun aiProviders() = listOf(
    entry(
        SettingsSection.AiProviders, "Providers",
        "Provider profiles used by the AI panel; add, switch or remove profiles here.",
        listOf("profile", "openai", "anthropic", "claude", "codex", "lm studio", "llm", "chatgpt", "gpt"),
    ),
    entry(
        SettingsSection.AiProviders, "Provider type",
        "Anthropic, OpenAI-compatible, or a local Codex / Claude Code account.",
        listOf("openai", "anthropic", "claude code", "codex", "account", "cli", "llm"),
    ),
    entry(SettingsSection.AiProviders, "Profile name", "Display name of this provider profile.", listOf("provider", "rename")),
    entry(
        SettingsSection.AiProviders, "Endpoint",
        "Base URL of an HTTP AI provider.",
        listOf("url", "api", "server", "lm studio", "local", "host"),
    ),
    entry(
        SettingsSection.AiProviders, "CLI executable (optional)",
        "Path to the Codex or Claude Code CLI used for account providers.",
        listOf("codex", "claude code", "path", "binary", "account"),
    ),
    entry(
        SettingsSection.AiProviders, "Model (optional)",
        "Model id to use; detect the models a provider offers.",
        listOf("llm", "gpt", "sonnet", "opus", "haiku", "override", "discover"),
        anchor = "Model",
    ),
    entry(
        SettingsSection.AiProviders, "Reasoning effort",
        "Reasoning level for models that support it.",
        listOf("thinking", "llm", "model", "effort"),
    ),
    entry(
        SettingsSection.AiProviders, "API key — this session only; it is never saved",
        "Key for cloud API providers; kept in memory for this session only.",
        listOf("api key", "token", "secret", "password", "credentials", "openai", "anthropic"),
        anchor = "API key",
    ),
    entry(
        SettingsSection.AiProviders, "Max MCP tool calls per request",
        "Tool-call budget for one AI request before it stops.",
        listOf("rounds", "limit", "budget", "tool calls", "llm", "mcp"),
    ),
)

private fun voiceInput() = listOf(
    entry(
        SettingsSection.VoiceInput, "Recognition engine",
        "Local Whisper or the operating system's speech recognizer.",
        listOf("dictation", "microphone", "speech", "whisper", "apple", "windows", "voice"),
    ),
    entry(
        SettingsSection.VoiceInput, "Translate dictated speech to English",
        "Outputs an English translation instead of the spoken-language transcript.",
        listOf("whisper", "dictation", "translation", "voice"),
    ),
    entry(
        SettingsSection.VoiceInput, "Recognition language",
        "Language dictation listens for; Automatic detects it.",
        listOf("dictation", "speech", "ukrainian", "english", "whisper", "voice"),
    ),
    entry(
        SettingsSection.VoiceInput, "Local model",
        "Size of the local Whisper model and its download.",
        listOf("whisper", "download", "install", "base", "small", "dictation", "voice"),
    ),
)

private fun customAiCommands() = listOf(
    entry(
        SettingsSection.CustomAiCommands, "Add command",
        "Create a reusable prompt invoked as a button in Actions or by typing /name in the chat box.",
        listOf("slash", "prompt", "template", "macro", "custom", "llm", "shortcut"),
    ),
)

private fun sourceCode() = listOf(
    entry(
        SettingsSection.SourceCode, "Register source code",
        "Point Indagium at your project's source folder(s) to enable \"Show in code\".",
        listOf("project", "index", "repository", "folder", "directory", "show in code", "add"),
    ),
    entry(
        SettingsSection.SourceCode, "Open command",
        "Editor used by \"Show in code\" to open a file at the logged line.",
        listOf("editor", "ide", "vs code", "intellij", "android studio", "cursor", "sublime", "zed", "external"),
    ),
    entry(
        SettingsSection.SourceCode, "Discover simple custom log wrappers",
        "During reindexing, follows simple wrapper methods that delegate to Log.* or Timber.",
        listOf("timber", "log", "wrapper", "index", "reindex", "auto discovery"),
    ),
    entry(
        SettingsSection.SourceCode, "Logging configurations",
        "Assign module-specific wrapper rules to registered source folders.",
        listOf("wrapper", "rules", "module", "source", "add configuration"),
    ),
)

private fun issues() = listOf(
    entry(
        SettingsSection.Issues, "Custom issue categories",
        "Regexes matched against a log tag or message that add clickable anchors to Issues.",
        listOf("regex", "rules", "anchor", "category", "crash", "anr", "problem"),
    ),
)

private fun testing() = listOf(
    entry(
        SettingsSection.Testing, "Default judge",
        "Whether a blind AI judge checks the steps of a new test run, and with which AI profile.",
        listOf("test run", "judge", "verdict", "ai", "failed steps only", "every step"),
    ),
    entry(
        SettingsSection.Testing, "Default evidence to keep",
        "Screenshots, logcat, agent transcript and video a new test run keeps.",
        listOf("test run", "screenshots", "logcat", "transcript", "video", "scrcpy"),
    ),
    entry(
        SettingsSection.Testing, "Confirmation timeout (minutes)",
        "How long a test agent waits for you to allow an action before it is denied.",
        listOf("test run", "allow", "deny", "confirm", "wait", "timeout"),
    ),
    entry(
        SettingsSection.Testing, "Edition",
        "The edition and its limits on test suites and cases; development builds can switch it.",
        listOf("free", "premium", "unlimited", "limits", "suites", "cases", "friends"),
    ),
)

private fun issueTracker() = listOf(
    entry(
        SettingsSection.IssueTracker, "Use an issue tracker",
        "Turn on sending issues from failed test steps to your own tracker.",
        listOf("jira", "mcp", "issues", "tracker", "ticket", "bug", "send"),
    ),
    entry(
        SettingsSection.IssueTracker, "Tracker name",
        "How the tracker is called in the issue dialog.",
        listOf("jira", "linear", "github", "name"),
    ),
    entry(
        SettingsSection.IssueTracker, "Tracker MCP URL",
        "The address of the tracker's MCP server (http or https).",
        listOf("mcp", "url", "server", "endpoint", "https", "address"),
    ),
    entry(
        SettingsSection.IssueTracker, "Authentication header",
        "The header that carries the access token, and whether it is sent as a Bearer token.",
        listOf("authorization", "bearer", "header", "auth", "token", "api key"),
    ),
    entry(
        SettingsSection.IssueTracker, "Access token",
        "The tracker's access token, kept in your system's secret store (keychain), never in settings.",
        listOf("token", "secret", "keychain", "password", "credential", "save token", "remove token", "api key"),
    ),
    entry(
        SettingsSection.IssueTracker, "Issue agent profile",
        "The AI profile that files the issue by calling the tracker's tools.",
        listOf("ai", "profile", "agent", "claude", "codex", "model"),
    ),
    entry(
        SettingsSection.IssueTracker, "Issue instructions (prompt)",
        "Tell the agent how to create an issue: project key, issue type, labels and field mapping.",
        listOf("prompt", "project key", "issue type", "labels", "fields", "instructions"),
    ),
    entry(
        SettingsSection.IssueTracker, "Test connection",
        "Connect to the tracker and list the tools it offers.",
        listOf("check", "connect", "tools", "verify", "mcp"),
    ),
)

internal val SETTINGS_SEARCH_INDEX: List<SettingsSearchEntry> =
    general() + appearance() + editorBehavior() + exportAnnotations() + capture() +
        automation() + aiProviders() + voiceInput() + customAiCommands() + sourceCode() + issues() + testing() + issueTracker()

// Bidirectional groups of interchangeable words. A query token that equals (or, from 3 letters on,
// is a prefix of) a member also matches every other member of its group.
private val SETTINGS_SYNONYM_GROUPS: List<Set<String>> = listOf(
    setOf("folder", "directory", "path", "location", "dir", "folders"),
    setOf("theme", "color", "colour", "colors", "dark", "light", "appearance", "palette"),
    setOf("font", "typeface", "fonts"),
    setOf("shortcut", "shortcuts", "key", "keys", "keyboard", "hotkey", "hotkeys"),
    setOf("ai", "llm", "model", "provider", "gpt"),
    setOf("adb", "device", "phone", "android"),
    setOf("export", "save"),
    setOf("note", "notes", "annotation", "annotations"),
    setOf("copy", "clipboard"),
    setOf("mcp", "automation", "api"),
)

private fun synonymsOf(token: String): Set<String> {
    val out = mutableSetOf(token)
    for (group in SETTINGS_SYNONYM_GROUPS) {
        if (group.any { it == token || (token.length >= 3 && it.startsWith(token)) }) out += group
    }
    return out
}

internal fun searchTokens(query: String): List<String> =
    query.trim().lowercase().split(Regex("\\s+")).filter { it.isNotEmpty() }

/**
 * Entries of [visibleSections] matching every whitespace-separated token of [query] (case-
 * insensitive substring of the label, hint, keywords or section title, or of any synonym of the
 * token). Label-prefix matches come first, then label-contains, then the rest, each keeping the
 * index's own (section, row) order.
 */
internal fun searchSettings(query: String, visibleSections: Set<SettingsSection>): List<SettingsSearchEntry> {
    val tokens = searchTokens(query)
    if (tokens.isEmpty()) return emptyList()
    val normalizedQuery = tokens.joinToString(" ")
    val synonyms = tokens.map(::synonymsOf)
    return SETTINGS_SEARCH_INDEX
        .filter { it.section in visibleSections }
        .mapNotNull { e ->
            val label = e.label.lowercase().replace('\n', ' ')
            val haystack = buildString {
                append(label).append('\n').append(e.hint.lowercase()).append('\n')
                e.keywords.forEach { append(it.lowercase()).append('\n') }
                append(e.section.title.lowercase())
            }
            if (synonyms.any { variants -> variants.none { it in haystack } }) return@mapNotNull null
            val rank = when {
                label.startsWith(normalizedQuery) || label.startsWith(tokens.first()) -> 0
                tokens.all { token -> token in label } -> 1
                else -> 2
            }
            rank to e
        }
        .sortedBy { it.first } // stable, so ties keep the index order
        .map { it.second }
}

/** Half-open ranges of [text] covered by any of [tokens] (case-insensitive), merged. */
internal fun matchRanges(text: String, tokens: List<String>): List<IntRange> {
    if (tokens.isEmpty()) return emptyList()
    val lower = text.lowercase()
    val found = mutableListOf<IntRange>()
    for (token in tokens) {
        var from = 0
        while (true) {
            val at = lower.indexOf(token, from)
            if (at < 0) break
            found += at until at + token.length
            from = at + token.length
        }
    }
    val merged = mutableListOf<IntRange>()
    for (r in found.sortedBy { it.first }) {
        val last = merged.lastOrNull()
        if (last != null && r.first <= last.last + 1) merged[merged.lastIndex] = last.first..maxOf(last.last, r.last) else merged += r
    }
    return merged
}
