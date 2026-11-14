package com.indagium.ui
import com.indagium.ui.holiday.*

// Search index behind the Settings dialog's "Search settings" field: one entry per labelled setting
// row, keyed to the page that shows it. Labels are copied from the literals the rows render (so a result reads exactly like the row
// it points at) and each entry's [anchor] is the key the row registers with Modifier.settingsAnchor
// (ui/SettingsSearchUi.kt) so a clicked result can scroll to it. Add an entry here whenever a
// labelled row is added to a page.

internal data class SettingsSearchEntry(
    val id: String,
    val page: SettingsPage,
    val label: String,
    val hint: String,
    val keywords: List<String> = emptyList(),
    /** The key the row passes to Modifier.settingsAnchor; differs from [label] only when the UI
     *  label has a hard line break or is dynamic. */
    val anchor: String = label,
)

private fun entry(
    page: SettingsPage,
    label: String,
    hint: String,
    keywords: List<String> = emptyList(),
    anchor: String = label,
): SettingsSearchEntry {
    val slug = anchor.lowercase().replace(Regex("[^a-z0-9]+"), "-").trim('-')
    return SettingsSearchEntry("${page.name}.$slug", page, label, hint, keywords, anchor)
}

private fun general() = listOf(
    entry(
        SettingsPage.General, "Workspace profile",
        "Sets panels, filter placement, theme and font size in one step.",
        listOf("profile", "layout", "classic", "focused", "compare", "minimal", "logcat query", "workspace"),
    ),
    entry(
        SettingsPage.General, "Inactive tab memory",
        "Release rows from inactive reloadable log tabs after a configurable number of minutes; switch it off to disable it.",
        listOf("memory", "ram", "unload", "idle", "inactive tabs", "large logs", "timeout", "off"),
    ),
    entry(
        SettingsPage.General, "Save as new profile",
        "Save the current theme, font size and panel layout as your own workspace profile.",
        listOf("workspace", "preset", "layout", "custom", "save profile", "my profile"),
    ),
    entry(
        SettingsPage.General, "Import profile",
        "Load a workspace profile from a .json file someone shared.",
        listOf("workspace", "preset", "layout", "share", "json", "open"),
    ),
    entry(
        SettingsPage.General, "Export profile",
        "Save your current setup, or one of your profiles, as a shareable .json file.",
        listOf("workspace", "preset", "layout", "share", "json", "save"),
    ),
    entry(
        SettingsPage.Storage, "Default save folder",
        "Parent folder every other save location defaults under. Defaults to your Documents folder.",
        listOf("documents", "root"),
    ),
    entry(
        SettingsPage.Storage, "Analysis artifacts folder",
        "Where analysis notes, filtered exports and split logs are saved.",
        listOf("notes", "exports", "split logs", "save folder"),
    ),
    entry(
        SettingsPage.Storage, "Capture sessions folder",
        "Where new device captures are recorded; applies from the next Start.",
        listOf("recordings", "device", "save folder"),
    ),
    entry(
        SettingsPage.Storage, "Snapshots folder",
        "Destination for \"Save snapshot\" while a capture is recording.",
        listOf("screenshots", "save folder"),
    ),
    entry(
        SettingsPage.Storage, "Saved captures folder (Save ZIP)",
        "Destination for \"Save ZIP\" on a stopped or retained capture.",
        listOf("zip", "archive", "save folder"),
    ),
    entry(
        SettingsPage.TestRuns, "Test suites folder",
        "Where your AI test suites are kept: library, suites and golden screenshots.",
        listOf("tests", "ai tests", "library", "assets", "save folder"),
    ),
    entry(
        SettingsPage.TestRuns, "Test runs folder",
        "Where each AI test run keeps its evidence: run files, recordings, screenshots and transcripts.",
        listOf("tests", "ai tests", "evidence", "recordings", "save folder"),
    ),
    entry(
        SettingsPage.TestRuns, "Issues folder",
        "Where the issues made from failed test steps are kept, with their evidence.",
        listOf("tests", "ai tests", "bugs", "tracker", "save folder"),
    ),
    entry(
        SettingsPage.Storage, "Storage",
        "Where Indagium keeps its data, and the temporary data and app data it holds.",
        listOf("app data", "cache", "disk", "space", "appdata"),
    ),
    entry(
        SettingsPage.Storage, "Temporary data",
        "Downloaded archive cache and app-managed notes. Clear temporary data removes these items.",
        listOf("clear", "cache", "delete", "temp"),
    ),
    entry(
        SettingsPage.Storage, "App data",
        "Everything Indagium stores: settings, session, saved filters, indexes and diagnostics. Reset app data wipes it.",
        listOf("reset", "wipe", "delete", "clear", "factory"),
    ),
)

// Settings > Appearance > Shape style and Toolbar shape (ui/UiCorners.kt).
private fun appearanceShapes() = listOf(
    entry(
        SettingsPage.Appearance, "Shape style",
        "Corners of the tiles: theme default, square, soft, rounded or round. Tabs and the toolbar have their own settings.",
        listOf("shape", "corners", "rounded", "square", "round", "radius", "soft", "tiles"),
    ),
    entry(
        SettingsPage.Appearance, "Toolbar shape",
        "Corners of the toolbar's button groups and the Layout menu button: same as tiles, theme default, square, soft, rounded or round (a capsule).",
        listOf("toolbar", "buttons", "menu buttons", "corners", "rounded", "square", "round", "capsule", "pill", "same as tiles"),
    ),
)

private fun appearance() = buildList {
    add(
        entry(
            SettingsPage.Appearance, "Theme",
            "Color theme of the whole window.",
            listOf("dark", "light", "palette", "colors", "look"),
        ),
    )
    add(
        entry(
            SettingsPage.Appearance,
            "Interface font",
            "Search and preview an installed system font for controls and interface text.",
            listOf("typeface", "ui", "font family"),
        ),
    )
    add(
        entry(
            SettingsPage.Appearance,
            "Log font",
            "Monospace or proportional default, or an installed system font, for log rows and highlighter previews.",
            listOf("typeface", "font family", "monospace", "proportional", "mono", "fallback log family", "default font"),
        ),
    )
    add(
        entry(
            SettingsPage.Appearance, "Log font size",
            "Text size of the log rows.",
            listOf("text size", "zoom", "bigger", "smaller"),
        ),
    )
    add(
        entry(
            SettingsPage.Appearance, "Interface scale",
            "Scales the whole interface, not just the log.",
            listOf("zoom", "dpi", "size", "hidpi"),
        ),
    )
    add(
        entry(
            SettingsPage.TabsToolbar, "Toolbar labels",
            "Hides text on the main toolbar buttons, leaving only their icons.",
            listOf("icons only", "buttons"),
        ),
    )
    add(
        entry(
            SettingsPage.TabsToolbar, "Tab style",
            "How the tab strip looks: current, raised, segmented, typed, compact, source colour, two-line, quiet, outlined or pill.",
            listOf("tabs", "tab bar", "appearance", "look", "raised", "compact", "segmented"),
        ),
    )
    add(
        entry(
            SettingsPage.TabsToolbar, "Tab size",
            "Fixed gives every tab an equal share of the strip; Fit label sizes each tab to its name.",
            listOf("tabs", "width", "tab bar", "fixed", "fit"),
        ),
    )
    add(
        entry(
            SettingsPage.TabsToolbar, "Tab label",
            "What a tab shows: the file name (plus its folder when two open tabs share a name), the folder only, " +
                "the file name only, or always the file and its folder.",
            listOf("tabs", "title", "name", "file", "session", "folder", "same name", "duplicate"),
        ),
    )
    add(
        entry(
            SettingsPage.TabsToolbar, "File-type badge",
            "Adds a LOG, ZIP or IND badge before the tab name.",
            listOf("tabs", "type", "log", "zip", "ind", "capture"),
        ),
    )
    add(
        entry(
            SettingsPage.TabsToolbar, "Source colour stripe",
            "Adds a coloured stripe on top of a tab by where it came from: file, live capture, bug report or capture.",
            listOf("tabs", "color", "colour", "source", "stripe", "live"),
        ),
    )
    add(
        entry(
            SettingsPage.TabsToolbar, "Close button on every tab",
            "Off shows a tab's close button only on the active tab and under the pointer.",
            listOf("tabs", "x", "close", "hover"),
        ),
    )
    addAll(chromePlacement())
    addAll(seasonalThemes())
    if (isLinuxOs) {
        add(
            entry(
                SettingsPage.Appearance, "File picker",
                "Native GTK or Compatibility X11 file dialogs; restart to apply.",
                listOf("dialog", "linux", "gtk", "x11", "open"),
            ),
        )
    }
}

// Settings > Appearance > Special theme (ui/holiday/SeasonalThemesSettings.kt). What names a theme (label, occasion, its own keywords) comes from
// the registry, so a new theme is found by its words without touching this index.
private val SEASONAL_THEMES = HOLIDAY_THEMES.values
private val SEASONAL_KEYWORDS = listOf("special", "seasonal", "holiday", "theme pack") +
    SEASONAL_THEMES.flatMap { listOf(it.label.lowercase(), it.occasion.lowercase()) + it.keywords }.distinct()
private val SEASONAL_NAMES = SEASONAL_THEMES.joinToString(", ") { it.label }
private val SEASONAL_DATES = SEASONAL_THEMES.joinToString("; ") { "${it.label} runs ${holidayDatesLabel(it)}" }

private fun seasonalThemes() = listOf(
    entry(
        SettingsPage.Appearance, "Special theme",
        "Theme > Special themes: turn a special theme on always or only during its dates. $SEASONAL_DATES and goes back to your theme after.",
        SEASONAL_KEYWORDS + listOf("automatic", "always", "off", "dates", "colours"),
        anchor = SEASONAL_ANCHOR_WHEN,
    ),
    entry(
        SettingsPage.Appearance, "Special theme pack",
        "$SEASONAL_NAMES, each with its own palette, tab shapes, scene behind the tabs and pictures.",
        SEASONAL_KEYWORDS + listOf("tabs", "scene", "cat"),
        anchor = SEASONAL_ANCHOR_PACK,
    ),
    entry(
        SettingsPage.Appearance, "Special light or dark",
        "Whether the special theme uses its light or dark colours, or follows your theme preset.",
        SEASONAL_KEYWORDS + listOf("follow preset", "light", "dark", "mode"),
        anchor = SEASONAL_ANCHOR_LIGHTNESS,
    ),
    entry(
        SettingsPage.Appearance, "Special effects",
        "Full: ghosts, fog, bats, embers and lightning. Calm: fewer, slower and none over the log. Off: decorations stay, nothing moves.",
        SEASONAL_KEYWORDS + listOf("effects", "animation", "motion", "ghosts", "fog", "bats", "embers", "lightning", "calm", "full", "reduce motion"),
        anchor = SEASONAL_ANCHOR_EFFECTS,
    ),
    entry(
        SettingsPage.Appearance, "Special theme tabs",
        "Tabs: the special theme's own tab shapes, or My tab style, which keeps your Tab style in the pack's colours.",
        SEASONAL_KEYWORDS + listOf("tab style", "seasonal tabs", "my tab style", "tabs", "shape"),
        anchor = SEASONAL_ANCHOR_TABS,
    ),
    entry(
        SettingsPage.Appearance, "Fun icons",
        "Toolbar buttons become pictures under a special theme. Hover one to see its real name.",
        SEASONAL_KEYWORDS + listOf("icons", "toolbar", "pictures", "tooltip", "cat"),
    ),
    entry(
        SettingsPage.Appearance, "Pause effects while capturing or playing video",
        "Stops special theme effects and animated pictures while a capture is running or a video plays.",
        SEASONAL_KEYWORDS + listOf("pause", "capture", "video", "effects", "animation", "motion"),
    ),
)

// Settings › Tabs & toolbar, the placement half (ui/ChromePlacementSettings.kt).
private fun chromePlacement() = listOf(
    entry(
        SettingsPage.TabsToolbar, "Tab position",
        "Which window edge the tab strip sits on: top, left, right or bottom. Left and right stack the tabs vertically.",
        listOf("tabs", "tab bar", "vertical", "side", "edge", "dock", "move"),
    ),
    entry(
        SettingsPage.TabsToolbar, "Toolbar placement",
        "Where the main toolbar sits: at the start or end of the tab strip, beside a vertical tab strip, on an edge of its own, or as a tile in the log view.",
        listOf("toolbar", "buttons", "dock", "tabs", "beside", "own edge", "vertical", "tile", "layout", "log view"),
    ),
    entry(
        SettingsPage.TabsToolbar, "Toolbar alignment",
        "Where the buttons sit along a toolbar that has a bar of its own: at the start, the centre or the end, " +
            "and before or after the tab bar on the tab strip's edge.",
        listOf("toolbar", "align", "start", "end", "center", "before tabs", "after tabs", "right", "left"),
    ),
    entry(
        SettingsPage.TabsToolbar, "Toolbar edge",
        "The window edge the toolbar is docked to when it has an edge of its own.",
        listOf("toolbar", "dock", "top", "bottom", "left", "right"),
    ),
    entry(
        SettingsPage.TabsToolbar, "Lock tabs and toolbar",
        "Hides the grips that drag the tab strip and the toolbar to another edge, so they stay where they are.",
        listOf("tabs", "toolbar", "grip", "lock", "dock", "move", "drag"),
    ),
)

private fun editorBehavior() = listOf(
    entry(SettingsPage.TabsToolbar, "Visible tabs", "How many tabs show before the rest collapse into a menu.", listOf("tab bar", "overflow")),
    entry(
        SettingsPage.SearchFilters, "Keyboard scroll margin",
        "Rows kept in view around the selection when moving with the keyboard.",
        listOf("arrow keys", "context", "navigation"),
    ),
    entry(SettingsPage.SearchFilters, "Most-used tags", "How many frequent tags are offered as quick picks.", listOf("tag list", "filter")),
    entry(SettingsPage.SearchFilters, "Filter list rows", "Visible rows of the filter panel's lists.", listOf("sidebar", "height")),
    entry(
        SettingsPage.LogView, "Follow live logs",
        "Keeps the view pinned to the newest line while a tab is live-watching.",
        listOf("tail", "auto scroll", "autoscroll", "live watching"),
    ),
    entry(
        SettingsPage.SearchFilters, "Regex summary",
        "Shows retained Tags-mode selectors above the Regex field; informational only.",
        listOf("regex", "tags", "filter"),
    ),
    entry(
        SettingsPage.LogView, "Row wrapping",
        "Auto wraps long lines to the panel width, or set a fixed wrap column and scroll horizontally.",
        listOf("wrap", "long lines", "horizontal scroll"),
    ),
    entry(
        SettingsPage.LogView, "Crash rows",
        "Colors every row in an expanded crash/stack-trace group, not just the header.",
        listOf("stack trace", "exception", "highlight", "anr"),
    ),
    entry(
        SettingsPage.LogView, "Original panel",
        "Whether newly opened files start with the unfiltered Original panel visible.",
        listOf("unfiltered", "split", "new files"),
    ),
    entry(
        SettingsPage.LogView, "Row number",
        "Shows a left gutter with each row's original row number.",
        listOf("line number", "gutter"),
    ),
    entry(
        SettingsPage.LogView, "Minimap",
        "Replaces the scrollbar with a text minimap colored by level.",
        listOf("scrollbar", "overview"),
    ),
    entry(
        SettingsPage.SearchFilters, "Ctrl+F opens",
        "Whether Ctrl/Cmd+F opens the Find bar, or focuses the Tags or Regex filter field.",
        listOf("cmd+f", "find", "search", "shortcut", "keyboard", "find bar"),
    ),
    entry(
        SettingsPage.SearchFilters, "Ctrl+F also shows Original",
        "Reveals the Original panel whenever Ctrl/Cmd+F opens; Search scope behavior still picks the Find field.",
        listOf("cmd+f", "find", "shortcut", "keyboard", "unfiltered", "opens original"),
    ),
    entry(
        SettingsPage.SearchFilters, "Search scope behavior",
        "Choose Filtered, Unfiltered, Two searches, Last search mode, or Auto-switch by open view for normal Find opens.",
        listOf("find", "search", "two searches", "dual", "scope", "cmd+f", "ctrl+f", "filtered", "unfiltered", "last used", "auto"),
    ),
    entry(
        SettingsPage.SearchFilters, "Link split selection and Find",
        "In any Filtered and Original split view, mirror row selection and Find navigation, or keep each panel independent.",
        listOf("find", "search", "original", "filtered", "two searches", "dual", "selection", "scroll", "sync"),
    ),
    entry(
        SettingsPage.LogView, "Process names in new tabs",
        "Whether new tabs show process names in place of numeric pids.",
        listOf("pid", "proc", "process"),
    ),
    entry(
        SettingsPage.CaptureVideoPlayback, "Video follow readout",
        "Shows the diagnostic line under the video transport bar explaining what Follow is doing.",
        listOf("video", "sync", "playhead"),
    ),
    entry(
        SettingsPage.CaptureVideoPlayback, "New video links: double-click seeks",
        "Default for newly linked log/video anchors; each link can still be toggled from its header.",
        listOf("video", "anchor", "double click", "seek"),
        anchor = "New video links:\ndouble-click seeks",
    ),
    entry(
        SettingsPage.NotesWriting, "English proofreading",
        "Local, optional English spelling and grammar suggestions in editable prose.",
        listOf("proofread", "grammar", "spelling", "language tool", "writing", "suggestions", "english"),
    ),
    entry(
        SettingsPage.NotesWriting, "English variant",
        "Choose American or British English rules for local proofreading suggestions.",
        listOf("proofread", "grammar", "spelling", "en-us", "en-gb", "american", "british"),
    ),
    entry(
        SettingsPage.LogView, "Error rows",
        "Highlight parsed Error rows by default and choose their background, text color, font, bold and italic style.",
        listOf("error", "error level", "crash", "highlight", "red", "log row", "bold", "italic", "font"),
    ),
    entry(
        SettingsPage.NotesWriting, "Personal vocabulary",
        "Search, add, or remove single words that spelling suggestions should accept in every editor.",
        listOf("personal", "proofread", "vocabulary", "dictionary", "spelling", "words", "ignore"),
    ),
)

private fun exportAnnotations() = listOf(
    entry(
        SettingsPage.Storage, "Auto-save",
        "Saves note Markdown and its .ann sidecar after note changes.",
        listOf("autosave", "notes", "save", "markdown"),
    ),
    entry(
        SettingsPage.Storage, "Filter backups",
        "Writes timestamped saved-filter backups after saved-filter changes.",
        listOf("backup", "saved filters", "save"),
    ),
    entry(SettingsPage.NotesWriting, "Number blocks", "Numbers the blocks of a note.", listOf("numbering", "annotation")),
    entry(
        SettingsPage.NotesWriting, "Log blocks",
        "Format of log excerpts in exported notes: Indented, Wiki or Cloud.",
        listOf("jira", "wiki", "code block", "annotation"),
    ),
    entry(
        SettingsPage.NotesWriting, "Inline Markdown",
        "Shows note and caption fields as rendered Markdown in the Notes panel; click them to edit.",
        listOf("render", "notes", "markdown"),
    ),
    entry(
        SettingsPage.CopyExport, "Pid/Tid copy",
        "Includes PID and TID for log rows when copying lines, annotations or filtered exports.",
        listOf("copy", "clipboard", "pid", "tid", "thread"),
    ),
    entry(
        SettingsPage.CopyExport, "Pid copy as name",
        "Uses a process name learned from the log instead of the numeric PID when copying.",
        listOf("copy", "clipboard", "process name"),
    ),
    entry(
        SettingsPage.CopyExport, "Row number copy",
        "Includes the original log row number when copying and the row-number gutter is visible.",
        listOf("copy", "clipboard", "line number"),
    ),
    entry(
        SettingsPage.CopyExport, "Time delta copy",
        "Includes Δt when copying and the Δt column is visible.",
        listOf("copy", "clipboard", "delta", "elapsed"),
    ),
    entry(
        SettingsPage.CopyExport, "Copy default",
        "Format used by the main Copy button: Cloud, Wiki, Markdown or HTML.",
        listOf("copy", "clipboard", "jira", "html", "format"),
    ),
    entry(
        SettingsPage.NotesWriting, "Diagram note action",
        "Which half of the sequence-diagram note action is primary: snapshot or linked note.",
        listOf("sequence diagram", "uml", "snapshot", "link"),
    ),
    entry(
        SettingsPage.CopyExport, "Diagram export",
        "Representation of newly added sequence-diagram notes: image or source.",
        listOf("sequence diagram", "uml", "mermaid", "plantuml", "image"),
    ),
    entry(
        SettingsPage.CopyExport, "Annotation file prefix",
        "Prefix used in front of the log name in note labels, e.g. \"From app.log\".",
        listOf("note", "label", "name", "from"),
    ),
    entry(
        SettingsPage.CopyExport, "Mask word on copy",
        "Replaces listed words with a masked form when copying a note.",
        listOf("redact", "privacy", "censor", "hide", "sensitive", "copy"),
    ),
)

private fun capture() = buildList {
    val c = SettingsPage.CaptureVideo
    val tools = SettingsPage.CaptureTools
    val limits = SettingsPage.CaptureLimits
    val markIssue = SettingsPage.CaptureMarkIssue
    val playback = SettingsPage.CaptureVideoPlayback
    add(entry(tools, "Tools", "adb and scrcpy executables used for device capture.", listOf("adb", "scrcpy", "android", "device", "path")))
    add(entry(tools, "ADB path", "Path to adb; leave blank to auto-detect.", listOf("adb", "android", "device", "phone", "executable")))
    add(
        entry(
            tools, "scrcpy path (optional — native mirror window only)",
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
                tools, "Hardware-accelerated mirror (experimental)",
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
    add(entry(limits, "Screen recording", "Size, frame rate, bitrate and format of the screen recording.", listOf("video", "android", "device", "phone")))
    add(
        entry(
            limits, "Max size", "Longest side in pixels of the recording; 0 keeps full resolution.",
            listOf("resolution", "video", "scale", "screen recording"),
        ),
    )
    add(entry(limits, "Max FPS", "Frame-rate cap for the screen recording.", listOf("frame rate", "video", "screen recording")))
    add(entry(limits, "Bitrate Mbps", "Video bitrate of the screen recording in Mbps.", listOf("quality", "video", "screen recording")))
    add(entry(limits, "Saved video format", "Container used by Save snapshot / Save ZIP: MP4 or MKV.", listOf("mp4", "mkv", "video", "container", "export")))
    add(entry(playback, "Live audio volume %", "Volume of the embedded mirror's speaker toggle.", listOf("sound", "speaker", "mirror", "audio")))
    add(entry(limits, "Session limit GiB", "Largest size a single capture session may grow to.", listOf("storage", "disk", "size", "limit", "gigabytes")))
    add(entry(limits, "Reserve GiB", "Free disk space left untouched while capturing.", listOf("storage", "disk", "free space", "gigabytes")))
    add(entry(limits, "Snapshot files", "Filename template and label for saved snapshots.", listOf("naming", "export")))
    add(
        entry(
            limits, "Filename template", "Placeholders: {device}, {start}, {range}, {counter}.",
            listOf("snapshot", "name", "naming", "export", "save"),
        ),
    )
    add(entry(limits, "Label", "Label stored in the capture archive.", listOf("snapshot", "archive", "name")))
    add(
        entry(
            markIssue, "Mark issue", "The Mark issue button drops a note with the log window around the press.",
            listOf("marker", "bug", "note", "screenshot"),
        ),
    )
    add(entry(markIssue, "Before, seconds", "Log window kept before pressing Mark issue.", listOf("marker", "pre", "window", "mark issue")))
    add(entry(markIssue, "After, seconds", "Log window kept after pressing Mark issue.", listOf("marker", "post", "window", "mark issue")))
    add(entry(markIssue, "Take a screenshot on Mark issue", "Adds a device screenshot to the marker note.", listOf("marker", "image", "mark issue")))
    add(entry(markIssue, "Include markers in saved snapshots", "Snapshot export does not use this setting yet.", listOf("marker", "notes", "mark issue")))
}

private fun automation() = listOf(
    entry(
        SettingsPage.Integrations, "MCP control server",
        "Local server that lets AI clients drive Indagium over MCP.",
        listOf("mcp", "api", "server", "claude", "codex", "automation", "rest"),
    ),
    entry(SettingsPage.Integrations, "Port", "Loopback port of the MCP control server.", listOf("mcp", "server", "8991", "network")),
    entry(
        SettingsPage.Integrations, "Allow browser-based MCP clients (CORS)",
        "Lets a browser-based MCP inspector call the local server; off by default.",
        listOf("cors", "browser", "mcp", "security", "origin"),
    ),
    entry(
        SettingsPage.Integrations, "Connection info",
        "URL, token and config snippets for connecting an MCP client.",
        listOf("mcp", "token", "url", "config", "claude", "codex", "snippet"),
    ),
    entry(
        SettingsPage.Integrations, "Debug logging",
        "Writes Indagium's own diagnostic log to a file.",
        listOf("diagnostics", "troubleshooting", "log file", "verbose"),
    ),
    entry(
        SettingsPage.Integrations, "Debug log file",
        "Where the diagnostic log is written; open the current one from here.",
        listOf("path", "diagnostics", "browse", "open current log"),
    ),
    entry(
        SettingsPage.General, "Check for updates automatically",
        "Checks GitHub Releases for a newer version at startup.",
        listOf("update", "upgrade", "version", "release", "github", "check now"),
    ),
)

private fun aiProviders() = listOf(
    entry(
        SettingsPage.AiProviders, "Providers",
        "Provider profiles used by the AI panel; add, switch or remove profiles here.",
        listOf("profile", "openai", "anthropic", "claude", "codex", "lm studio", "llm", "chatgpt", "gpt"),
    ),
    entry(
        SettingsPage.AiProviders, "Provider type",
        "Anthropic, OpenAI-compatible, or a local Codex / Claude Code account.",
        listOf("openai", "anthropic", "claude code", "codex", "account", "cli", "llm"),
    ),
    entry(SettingsPage.AiProviders, "Profile name", "Display name of this provider profile.", listOf("provider", "rename")),
    entry(
        SettingsPage.AiProviders, "Endpoint",
        "Base URL of an HTTP AI provider.",
        listOf("url", "api", "server", "lm studio", "local", "host"),
    ),
    entry(
        SettingsPage.AiProviders, "CLI executable (optional)",
        "Path to the Codex or Claude Code CLI used for account providers.",
        listOf("codex", "claude code", "path", "binary", "account"),
    ),
    entry(
        SettingsPage.AiProviders, "Model (optional)",
        "Model id to use; detect the models a provider offers.",
        listOf("llm", "gpt", "sonnet", "opus", "haiku", "override", "discover"),
        anchor = "Model",
    ),
    entry(
        SettingsPage.AiProviders, "Reasoning effort",
        "Reasoning level for models that support it.",
        listOf("thinking", "llm", "model", "effort"),
    ),
    entry(
        SettingsPage.AiProviders, "API key — this session only; it is never saved",
        "Key for cloud API providers; kept in memory for this session only.",
        listOf("api key", "token", "secret", "password", "credentials", "openai", "anthropic"),
        anchor = "API key",
    ),
    entry(
        SettingsPage.AiProviders, "Max MCP tool calls per request",
        "Tool-call budget for one AI request before it stops.",
        listOf("rounds", "limit", "budget", "tool calls", "llm", "mcp"),
    ),
)

private fun voiceInput() = listOf(
    entry(
        SettingsPage.VoiceInput, "Recognition engine",
        "Local Whisper or the operating system's speech recognizer.",
        listOf("dictation", "microphone", "speech", "whisper", "apple", "windows", "voice"),
    ),
    entry(
        SettingsPage.VoiceInput, "Translate dictated speech to English",
        "Outputs an English translation instead of the spoken-language transcript.",
        listOf("whisper", "dictation", "translation", "voice"),
    ),
    entry(
        SettingsPage.VoiceInput, "Recognition language",
        "Language dictation listens for; Automatic detects it.",
        listOf("dictation", "speech", "ukrainian", "english", "whisper", "voice"),
    ),
    entry(
        SettingsPage.VoiceInput, "Local model",
        "Size of the local Whisper model and its download.",
        listOf("whisper", "download", "install", "base", "small", "dictation", "voice"),
    ),
)

private fun customAiCommands() = listOf(
    entry(
        SettingsPage.AiCommands, "Add command",
        "Create a reusable prompt invoked as a button in Actions or by typing /name in the chat box.",
        listOf("slash", "prompt", "template", "macro", "custom", "llm", "shortcut"),
    ),
)

private fun sourceCode() = listOf(
    entry(
        SettingsPage.SourceCode, "Register source code",
        "Point Indagium at your project's source folder(s) to enable \"Show in code\".",
        listOf("project", "index", "repository", "folder", "directory", "show in code", "add"),
    ),
    entry(
        SettingsPage.SourceCode, "Open command",
        "Editor used by \"Show in code\" to open a file at the logged line.",
        listOf("editor", "ide", "vs code", "intellij", "android studio", "cursor", "sublime", "zed", "external"),
    ),
    entry(
        SettingsPage.SourceCode, "Discover simple custom log wrappers",
        "During reindexing, follows simple wrapper methods that delegate to Log.* or Timber.",
        listOf("timber", "log", "wrapper", "index", "reindex", "auto discovery"),
    ),
    entry(
        SettingsPage.SourceCode, "Logging configurations",
        "Assign module-specific wrapper rules to registered source folders.",
        listOf("wrapper", "rules", "module", "source", "add configuration"),
    ),
)

private fun issues() = listOf(
    entry(
        SettingsPage.IssueCategories, "Custom issue categories",
        "Regexes matched against a log tag or message that add clickable anchors to Issues.",
        listOf("regex", "rules", "anchor", "category", "crash", "anr", "problem"),
    ),
)

private fun testing() = listOf(
    entry(
        SettingsPage.TestRuns, "Default judge",
        "Whether a blind AI judge checks the steps of a new test run, and with which AI profile.",
        listOf("test run", "judge", "verdict", "ai", "failed steps only", "every step"),
    ),
    entry(
        SettingsPage.TestRuns, "Default evidence to keep",
        "Screenshots, logcat, agent transcript and video a new test run keeps.",
        listOf("test run", "screenshots", "logcat", "transcript", "video", "scrcpy"),
    ),
    entry(
        SettingsPage.TestRuns, "Confirmation timeout (minutes)",
        "How long a test agent waits for you to allow an action before it is denied.",
        listOf("test run", "allow", "deny", "confirm", "wait", "timeout"),
    ),
    entry(
        SettingsPage.TestRuns, "Keep recording evidence with recorded cases",
        "Save the screenshots, screen readings and video of an applied recording beside the case so Rewrite with AI can run on it again.",
        listOf("record", "recording", "rewrite", "reopen", "video", "password", "privacy", "disk", "storage", "evidence"),
    ),
    entry(
        SettingsPage.TestRuns, "Stored recording evidence",
        "How much disk the stored recordings of the Test suites folder use, and a button that deletes them.",
        listOf("record", "recording", "delete", "disk", "space", "size", "clean", "evidence", "storage"),
    ),
    entry(
        SettingsPage.About, "Edition",
        "The edition and its limits on test suites and cases; development builds can switch it.",
        listOf("free", "premium", "unlimited", "limits", "suites", "cases", "friends"),
    ),
)

private fun issueTracker() = listOf(
    entry(
        SettingsPage.IssueTracker, "Use an issue tracker",
        "Turn on sending issues from failed test steps to your own tracker.",
        listOf("jira", "mcp", "issues", "tracker", "ticket", "bug", "send"),
    ),
    entry(
        SettingsPage.IssueTracker, "Tracker name",
        "How the tracker is called in the issue dialog.",
        listOf("jira", "linear", "github", "name"),
    ),
    entry(
        SettingsPage.IssueTracker, "Tracker MCP URL",
        "The address of the tracker's MCP server (http or https).",
        listOf("mcp", "url", "server", "endpoint", "https", "address"),
    ),
    entry(
        SettingsPage.IssueTracker, "Authentication header",
        "The header that carries the access token, and whether it is sent as a Bearer token.",
        listOf("authorization", "bearer", "header", "auth", "token", "api key"),
    ),
    entry(
        SettingsPage.IssueTracker, "Access token",
        "The tracker's access token, kept in your system's secret store (keychain), never in settings.",
        listOf("token", "secret", "keychain", "password", "credential", "save token", "remove token", "api key"),
    ),
    entry(
        SettingsPage.IssueTracker, "Issue agent profile",
        "The AI profile that files the issue by calling the tracker's tools.",
        listOf("ai", "profile", "agent", "claude", "codex", "model"),
    ),
    entry(
        SettingsPage.IssueTracker, "Issue instructions (prompt)",
        "Tell the agent how to create an issue: project key, issue type, labels and field mapping.",
        listOf("prompt", "project key", "issue type", "labels", "fields", "instructions"),
    ),
    entry(
        SettingsPage.IssueTracker, "Test connection",
        "Connect to the tracker and list the tools it offers.",
        listOf("check", "connect", "tools", "verify", "mcp"),
    ),
)

internal val SETTINGS_SEARCH_INDEX: List<SettingsSearchEntry> =
    general() + appearance() + appearanceShapes() + editorBehavior() + exportAnnotations() + capture() +
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
 * Entries of [visiblePages] matching every whitespace-separated token of [query] (case-
 * insensitive substring of the label, hint, keywords, page title or page group label, or of any
 * synonym of the token). Label-prefix matches come first, then label-contains, then the rest, each
 * keeping the index's own order.
 */
internal fun searchSettings(query: String, visiblePages: Set<SettingsPage>): List<SettingsSearchEntry> {
    val tokens = searchTokens(query)
    if (tokens.isEmpty()) return emptyList()
    val normalizedQuery = tokens.joinToString(" ")
    val synonyms = tokens.map(::synonymsOf)
    return SETTINGS_SEARCH_INDEX
        .filter { it.page in visiblePages }
        .mapNotNull { e ->
            val label = e.label.lowercase().replace('\n', ' ')
            val haystack = buildString {
                append(label).append('\n').append(e.hint.lowercase()).append('\n')
                e.keywords.forEach { append(it.lowercase()).append('\n') }
                append(e.page.title.lowercase()).append('\n').append(e.page.group.label.lowercase())
                e.page.parent?.let { append('\n').append(it.title.lowercase()) }
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
