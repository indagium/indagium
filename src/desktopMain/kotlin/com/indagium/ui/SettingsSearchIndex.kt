package com.indagium.ui
import com.indagium.edition.Edition
import com.indagium.model.VoiceInputSettings
import com.indagium.model.VoiceRecognitionEngine
import com.indagium.ui.holiday.*
import com.indagium.voice.VoiceModelCatalog
import com.indagium.voice.VoiceRecognitionEngines

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
        PROFILE_INTRO,
        listOf("profile", "layout", "classic", "focused", "compare", "minimal", "logcat query", "workspace"),
    ),
    entry(
        SettingsPage.General, "Inactive tab memory",
        INACTIVE_TAB_MEMORY_HINT,
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
        SAVE_ROOT_HINT,
        listOf("documents", "root"),
    ),
    entry(
        SettingsPage.Storage, "Analysis artifacts folder",
        SAVE_ANALYSIS_HINT,
        listOf("notes", "exports", "split logs", "save folder"),
    ),
    entry(
        SettingsPage.Storage, "Capture sessions folder",
        SAVE_SESSIONS_HINT,
        listOf("recordings", "device", "save folder"),
    ),
    entry(
        SettingsPage.Storage, "Snapshots folder",
        SAVE_SNAPSHOTS_HINT,
        listOf("screenshots", "save folder"),
    ),
    entry(
        SettingsPage.Storage, "Saved captures folder",
        SAVE_ZIP_HINT,
        listOf("zip", "archive", "save folder"),
    ),
    entry(
        SettingsPage.TestRuns, "Test suites folder",
        SAVE_TEST_SUITES_HINT,
        listOf("tests", "ai tests", "library", "assets", "save folder"),
    ),
    entry(
        SettingsPage.TestRuns, "Test runs folder",
        SAVE_TEST_RUNS_HINT,
        listOf("tests", "ai tests", "evidence", "recordings", "save folder"),
    ),
    entry(
        SettingsPage.TestRuns, "Issues folder",
        SAVE_TEST_ISSUES_HINT,
        listOf("tests", "ai tests", "bugs", "tracker", "save folder"),
    ),
    entry(
        SettingsPage.Storage, "Storage",
        "Where Indagium keeps its data, and the temporary data and app data it holds.",
        listOf("app data", "cache", "disk", "space", "appdata"),
    ),
    entry(
        SettingsPage.Storage, "Temporary data",
        STORAGE_TEMP_HINT,
        listOf("clear", "cache", "delete", "temp"),
    ),
    entry(
        SettingsPage.Storage, "App data",
        STORAGE_APP_DATA_HINT,
        listOf("reset", "wipe", "delete", "clear", "factory", "return to defaults", "reset app data"),
    ),
)

// Settings > Appearance > Shape style and Toolbar shape (ui/UiCorners.kt).
private fun appearanceShapes() = listOf(
    entry(
        SettingsPage.Appearance, "Shape style",
        SHAPE_STYLE_HINT,
        listOf("shape", "corners", "rounded", "square", "round", "radius", "soft", "tiles"),
    ),
    entry(
        SettingsPage.Appearance, "Toolbar shape",
        TOOLBAR_SHAPE_HINT,
        listOf("toolbar", "buttons", "menu buttons", "corners", "rounded", "square", "round", "capsule", "pill", "same as tiles"),
    ),
)

private fun appearance() = buildList {
    add(
        entry(
            SettingsPage.Appearance, "Theme",
            SEASONAL_THEME_KIND_HINT,
            listOf("dark", "light", "palette", "colors", "look"),
        ),
    )
    add(
        entry(
            SettingsPage.Appearance,
            "Interface font",
            INTERFACE_FONT_HINT,
            listOf("typeface", "ui", "font family"),
        ),
    )
    add(
        entry(
            SettingsPage.Appearance,
            "Log font",
            LOG_FONT_HINT,
            listOf("typeface", "font family", "monospace", "proportional", "mono", "fallback log family", "default font"),
        ),
    )
    add(
        entry(
            SettingsPage.Appearance, "Log font size",
            LOG_FONT_SIZE_HINT,
            listOf("text size", "zoom", "bigger", "smaller"),
        ),
    )
    add(
        entry(
            SettingsPage.Appearance, "Interface scale",
            INTERFACE_SCALE_HINT,
            listOf("zoom", "dpi", "size", "hidpi"),
        ),
    )
    add(
        entry(
            SettingsPage.TabsToolbar, "Toolbar labels",
            TOOLBAR_LABELS_HINT,
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
            TAB_SIZE_HINT,
            listOf("tabs", "width", "tab bar", "fixed", "fit"),
        ),
    )
    add(
        entry(
            SettingsPage.TabsToolbar, "Tab label",
            TAB_LABEL_HINT,
            listOf("tabs", "title", "name", "file", "session", "folder", "same name", "duplicate"),
        ),
    )
    add(
        entry(
            SettingsPage.TabsToolbar, "File-type badge",
            TAB_BADGE_HINT,
            listOf("tabs", "type", "log", "zip", "ind", "capture"),
        ),
    )
    add(
        entry(
            SettingsPage.TabsToolbar, "Source colour stripe",
            TAB_STRIPE_HINT,
            listOf("tabs", "color", "colour", "source", "stripe", "live"),
        ),
    )
    add(
        entry(
            SettingsPage.TabsToolbar, "Close button on every tab",
            TAB_CLOSE_EVERY_HINT,
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
private val SEASONAL_DATES = SEASONAL_THEMES.joinToString("; ") { "${it.label} runs ${holidayDatesLabel(it)}" }

private fun seasonalThemes() = listOf(
    entry(
        SettingsPage.Appearance, "Special theme",
        "Off, always, or only during the pack's dates, going back to your theme after: $SEASONAL_DATES.",
        SEASONAL_KEYWORDS + listOf("automatic", "always", "off", "dates", "colours"),
        anchor = SEASONAL_ANCHOR_WHEN,
    ),
    entry(
        SettingsPage.Appearance, "Special theme pack",
        SEASONAL_PACK_HINT,
        SEASONAL_KEYWORDS + listOf("tabs", "scene", "cat"),
        anchor = SEASONAL_ANCHOR_PACK,
    ),
    entry(
        SettingsPage.Appearance, "Special light or dark",
        SEASONAL_LIGHTNESS_HINT,
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
        OWN_TABS_HINT,
        SEASONAL_KEYWORDS + listOf("tab style", "seasonal tabs", "my tab style", "tabs", "shape"),
        anchor = SEASONAL_ANCHOR_TABS,
    ),
    entry(
        SettingsPage.Appearance, "Fun icons",
        FUN_ICONS_HINT,
        SEASONAL_KEYWORDS + listOf("icons", "toolbar", "pictures", "tooltip", "cat"),
    ),
    entry(
        SettingsPage.Appearance, "Pause effects while capturing or playing video",
        SEASONAL_PAUSE_HINT,
        SEASONAL_KEYWORDS + listOf("pause", "capture", "video", "effects", "animation", "motion"),
    ),
)

// Settings › Tabs & toolbar, the placement half (ui/ChromePlacementSettings.kt).
private fun chromePlacement() = listOf(
    entry(
        SettingsPage.TabsToolbar, "Tab position",
        TAB_POSITION_HINT,
        listOf("tabs", "tab bar", "vertical", "side", "edge", "dock", "move"),
    ),
    entry(
        SettingsPage.TabsToolbar, "Toolbar placement",
        TOOLBAR_PLACEMENT_HINT,
        listOf("toolbar", "buttons", "dock", "tabs", "beside", "own edge", "vertical", "tile", "layout", "log view"),
    ),
    entry(
        SettingsPage.TabsToolbar, "Toolbar alignment",
        TOOLBAR_ALIGN_HINT,
        listOf("toolbar", "align", "start", "end", "center", "before tabs", "after tabs", "right", "left"),
    ),
    entry(
        SettingsPage.TabsToolbar, "Toolbar edge",
        TOOLBAR_EDGE_HINT,
        listOf("toolbar", "dock", "top", "bottom", "left", "right"),
    ),
    entry(
        SettingsPage.TabsToolbar, "Lock tabs and toolbar",
        TOOLBAR_LOCK_HINT,
        listOf("tabs", "toolbar", "grip", "lock", "dock", "move", "drag"),
    ),
)

private fun editorBehavior() = listOf(
    entry(SettingsPage.TabsToolbar, "Visible tabs", VISIBLE_TABS_HINT, listOf("tab bar", "overflow")),
    entry(
        SettingsPage.SearchFilters, "Keyboard scroll margin",
        SEARCH_SCROLL_MARGIN_HINT,
        listOf("arrow keys", "context", "navigation"),
    ),
    entry(SettingsPage.SearchFilters, "Most-used tags", SEARCH_MOST_USED_TAGS_HINT, listOf("tag list", "filter")),
    entry(SettingsPage.SearchFilters, "Filter list rows", SEARCH_LIST_HEIGHT_HINT, listOf("sidebar", "height")),
    entry(
        SettingsPage.LogView, "Follow live logs",
        LOG_FOLLOW_LIVE_HINT,
        listOf("tail", "auto scroll", "autoscroll", "live watching"),
    ),
    entry(
        SettingsPage.SearchFilters, "Regex summary",
        SEARCH_REGEX_SUMMARY_HINT,
        listOf("regex", "tags", "filter"),
    ),
    entry(
        SettingsPage.LogView, "Row wrapping",
        LOG_ROW_WRAPPING_HINT,
        listOf("wrap", "long lines", "horizontal scroll", "auto", "fixed"),
    ),
    entry(
        SettingsPage.LogView, "Wrap at",
        LOG_WRAP_AT_HINT,
        listOf("wrap", "columns", "fixed", "long lines", "row wrapping"),
    ),
    entry(
        SettingsPage.LogView, "Crash rows",
        LOG_CRASH_ROWS_HINT,
        listOf("stack trace", "exception", "highlight", "anr"),
    ),
    entry(
        SettingsPage.LogView, "Original panel",
        LOG_OPEN_WITH_ORIGINAL_HINT,
        listOf("unfiltered", "split", "new files"),
    ),
    entry(
        SettingsPage.LogView, "Row numbers",
        LOG_ROW_NUMBERS_HINT,
        listOf("line number", "gutter", "row number"),
    ),
    entry(
        SettingsPage.LogView, "Minimap",
        LOG_MINIMAP_HINT,
        listOf("scrollbar", "overview"),
    ),
    entry(
        SettingsPage.SearchFilters, "Ctrl/Cmd+F opens",
        SEARCH_OPENS_HINT,
        listOf("cmd+f", "find", "search", "shortcut", "keyboard", "find bar"),
    ),
    entry(
        SettingsPage.SearchFilters, "Ctrl/Cmd+F also shows Original",
        SEARCH_ALSO_ORIGINAL_HINT,
        listOf("cmd+f", "find", "shortcut", "keyboard", "unfiltered", "opens original"),
    ),
    entry(
        SettingsPage.SearchFilters, "Search scope behaviour",
        SEARCH_SCOPE_HINT,
        listOf("find", "search", "two searches", "dual", "scope", "cmd+f", "ctrl+f", "filtered", "unfiltered", "last used", "auto"),
    ),
    entry(
        SettingsPage.SearchFilters, "Link split selection and Find",
        SEARCH_LINK_SPLIT_HINT,
        listOf("find", "search", "original", "filtered", "two searches", "dual", "selection", "scroll", "sync"),
    ),
    entry(
        SettingsPage.LogView, "Process names in new tabs",
        LOG_PROCESS_NAMES_HINT,
        listOf("pid", "proc", "process"),
    ),
    entry(
        SettingsPage.CaptureVideoPlayback, "Video follow readout",
        CAPTURE_FOLLOW_READOUT_HINT,
        listOf("video", "sync", "playhead"),
    ),
    entry(
        SettingsPage.CaptureVideoPlayback, "New video links: double-click seeks",
        CAPTURE_DOUBLE_CLICK_HINT,
        listOf("video", "anchor", "double click", "seek"),
        anchor = "New video links:\ndouble-click seeks",
    ),
    entry(
        SettingsPage.NotesWriting, "English proofreading",
        NOTES_PROOFREADING_HINT,
        listOf("proofread", "grammar", "spelling", "language tool", "writing", "suggestions", "english"),
    ),
    entry(
        SettingsPage.NotesWriting, "English variant",
        NOTES_SPELLING_HINT,
        listOf("proofread", "grammar", "spelling", "en-us", "en-gb", "american", "british"),
    ),
    entry(
        SettingsPage.LogView, "Error rows",
        LOG_ERROR_ROWS_HINT,
        listOf("error", "error level", "crash", "highlight", "red", "log row", "bold", "italic", "font"),
    ),
    entry(
        SettingsPage.NotesWriting, "Personal vocabulary",
        NOTES_VOCABULARY_HINT,
        listOf("personal", "proofread", "vocabulary", "dictionary", "spelling", "words", "ignore"),
    ),
)

private fun exportAnnotations() = listOf(
    entry(
        SettingsPage.Storage, "Auto-save",
        STORAGE_AUTO_SAVE_HINT,
        listOf("autosave", "notes", "save", "markdown"),
    ),
    entry(
        SettingsPage.Storage, "Filter backups",
        STORAGE_FILTER_BACKUPS_HINT,
        listOf("backup", "saved filters", "save"),
    ),
    entry(SettingsPage.NotesWriting, "Number blocks", NOTES_NUMBER_BLOCKS_HINT, listOf("numbering", "annotation")),
    entry(
        SettingsPage.NotesWriting, "Log blocks",
        NOTES_LOG_EXCERPTS_HINT,
        listOf("jira", "wiki", "code block", "annotation"),
    ),
    entry(
        SettingsPage.NotesWriting, "Inline Markdown",
        NOTES_INLINE_MARKDOWN_HINT,
        listOf("render", "notes", "markdown"),
    ),
    entry(
        SettingsPage.CopyExport, "Pid/Tid copy",
        COPY_PID_TID_HINT,
        listOf("copy", "clipboard", "pid", "tid", "thread"),
    ),
    entry(
        SettingsPage.CopyExport, "Pid copy as name",
        COPY_PID_AS_NAME_HINT,
        listOf("copy", "clipboard", "process name"),
    ),
    entry(
        SettingsPage.CopyExport, "Row number copy",
        COPY_ROW_NUMBER_HINT,
        listOf("copy", "clipboard", "line number"),
    ),
    entry(
        SettingsPage.CopyExport, "Time delta copy",
        COPY_TIME_DELTA_HINT,
        listOf("copy", "clipboard", "delta", "elapsed"),
    ),
    entry(
        SettingsPage.CopyExport, "Copy default",
        COPY_NOTE_FORMAT_HINT,
        listOf("copy", "clipboard", "jira", "html", "format"),
    ),
    entry(
        SettingsPage.NotesWriting, "Diagram note action",
        NOTES_DIAGRAM_ACTION_HINT,
        listOf("sequence diagram", "uml", "snapshot", "link"),
    ),
    entry(
        SettingsPage.CopyExport, "Diagram export",
        COPY_DIAGRAM_EXPORT_HINT,
        listOf("sequence diagram", "uml", "mermaid", "plantuml", "image"),
    ),
    entry(
        SettingsPage.CopyExport, "Annotation file prefix",
        COPY_SOURCE_LINE_LABEL_HINT,
        listOf("note", "label", "name", "from"),
    ),
    entry(
        SettingsPage.CopyExport, "Mask word on copy",
        COPY_MASK_HINT,
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
    add(entry(tools, "ADB path", CAPTURE_ADB_HINT, listOf("adb", "android", "device", "phone", "executable")))
    add(
        entry(
            tools, "scrcpy path",
            CAPTURE_SCRCPY_HINT,
            listOf("scrcpy", "mirror", "android", "device", "executable"),
        ),
    )
    add(entry(c, "Before start", "Defaults for the New tab's Before start panel.", listOf("capture", "device", "phone", "launcher")))
    add(
        entry(
            c, "Record video to file", CAPTURE_RECORD_SCREEN_HINT,
            listOf("video", "screen", "recording", "android", "device", "phone"),
        ),
    )
    add(entry(c, "Capture audio", CAPTURE_AUDIO_HINT, listOf("sound", "record", "android", "device", "phone")))
    add(
        entry(
            c, "Include earlier device logs",
            EARLIER_DEVICE_LOGS_TEXT,
            listOf("buffer", "history", "logcat", "android", "device"),
        ),
    )
    add(
        entry(
            c, "Keep sound on the device",
            CAPTURE_KEEP_DEVICE_AUDIO_HINT,
            listOf("audio", "mute", "speaker", "android", "device"),
        ),
    )
    if (!isMacOs) {
        add(
            entry(
                tools, "Hardware-accelerated mirror",
                CAPTURE_HARDWARE_MIRROR_HINT,
                listOf("gpu", "decode", "mirror", "android", "device", "experimental"),
            ),
        )
    }
    add(
        entry(
            c, "Microphone", CAPTURE_MICROPHONE_HINT,
            listOf("audio", "voice", "record", "device"),
        ),
    )
    add(
        entry(
            c, "Device display", CAPTURE_DEVICE_DISPLAY_HINT,
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
            c, "Buffers to capture", CAPTURE_CUSTOM_BUFFERS_HINT,
            listOf("main", "system", "crash", "kernel", "events", "radio", "logcat"),
        ),
    )
    add(entry(limits, "Screen recording", "Size, frame rate, bitrate and format of the screen recording.", listOf("video", "android", "device", "phone")))
    add(
        entry(
            limits, "Max size", CAPTURE_MAX_SIZE_HINT,
            listOf("resolution", "video", "scale", "screen recording"),
        ),
    )
    add(entry(limits, "Max FPS", CAPTURE_MAX_FPS_HINT, listOf("frame rate", "video", "screen recording")))
    add(entry(limits, "Bitrate Mbps", CAPTURE_BITRATE_HINT, listOf("quality", "video", "screen recording")))
    add(entry(limits, "Saved video format", CAPTURE_SAVED_FORMAT_HINT, listOf("mp4", "mkv", "video", "container", "export")))
    add(entry(playback, "Live audio volume %", CAPTURE_LIVE_VOLUME_HINT, listOf("sound", "speaker", "mirror", "audio")))
    add(entry(limits, "Session limit GiB", CAPTURE_SESSION_LIMIT_HINT, listOf("storage", "disk", "size", "limit", "gigabytes")))
    add(entry(limits, "Reserve GiB", CAPTURE_RESERVE_HINT, listOf("storage", "disk", "free space", "gigabytes")))
    add(entry(limits, "Snapshot files", "Filename template and label for saved snapshots.", listOf("naming", "export")))
    add(
        entry(
            limits, "Filename template", CAPTURE_FILENAME_TEMPLATE_HINT,
            listOf("snapshot", "name", "naming", "export", "save"),
        ),
    )
    add(entry(limits, "Label", CAPTURE_LABEL_HINT, listOf("snapshot", "archive", "name")))
    add(
        entry(
            markIssue, "Mark issue", "The Mark issue button drops a note with the log window around the press.",
            listOf("marker", "bug", "note", "screenshot"),
        ),
    )
    add(entry(markIssue, "Before, seconds", CAPTURE_MARKER_BEFORE_HINT, listOf("marker", "pre", "window", "mark issue")))
    add(entry(markIssue, "After, seconds", CAPTURE_MARKER_AFTER_HINT, listOf("marker", "post", "window", "mark issue")))
    add(entry(markIssue, "Take a screenshot on Mark issue", CAPTURE_MARKER_SCREENSHOT_HINT, listOf("marker", "image", "mark issue")))
}

private fun automation() = listOf(
    entry(
        SettingsPage.Integrations, "MCP control server",
        MCP_SERVER_HINT,
        listOf("mcp", "api", "server", "claude", "codex", "automation", "rest"),
    ),
    entry(SettingsPage.Integrations, "Port", MCP_PORT_HINT, listOf("mcp", "server", "8991", "network")),
    entry(
        SettingsPage.Integrations, "Allow browser-based MCP clients",
        MCP_CORS_HINT,
        listOf("cors", "browser", "mcp", "security", "origin"),
    ),
    entry(
        SettingsPage.Integrations, "Connection info",
        MCP_CONNECTION_HINT,
        listOf("mcp", "token", "url", "config", "claude", "codex", "snippet"),
    ),
    entry(
        SettingsPage.Integrations, "Debug logging",
        DEBUG_LOGGING_HINT,
        listOf("diagnostics", "troubleshooting", "log file", "verbose"),
    ),
    entry(
        SettingsPage.Integrations, "Debug log file",
        DEBUG_LOG_FILE_HINT,
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
        SettingsPage.AiProviders, "Profile",
        AI_PROFILE_HINT,
        listOf("providers", "openai", "anthropic", "claude", "codex", "lm studio", "llm", "chatgpt", "gpt", "add profile", "remove profile"),
        anchor = "Providers",
    ),
    entry(
        SettingsPage.AiProviders, "Provider type",
        AI_PROVIDER_TYPE_HINT,
        listOf("openai", "anthropic", "claude code", "codex", "account", "cli", "llm"),
    ),
    entry(SettingsPage.AiProviders, "Profile name", AI_PROFILE_NAME_HINT, listOf("provider", "rename")),
    entry(
        SettingsPage.AiProviders, "Endpoint",
        AI_ENDPOINT_HINT,
        listOf("url", "api", "server", "lm studio", "local", "host"),
    ),
    entry(
        SettingsPage.AiProviders, "CLI executable",
        AI_CLI_EXECUTABLE_HINT,
        listOf("codex", "claude code", "path", "binary", "account"),
    ),
    entry(
        SettingsPage.AiProviders, "Model",
        AI_MODEL_HINT,
        listOf("llm", "gpt", "sonnet", "opus", "haiku", "override", "discover"),
    ),
    entry(
        SettingsPage.AiProviders, "Reasoning effort",
        AI_REASONING_EFFORT_HINT,
        listOf("thinking", "llm", "model", "effort"),
    ),
    entry(
        SettingsPage.AiProviders, "API key",
        AI_API_KEY_HINT,
        listOf("api key", "token", "secret", "password", "credentials", "openai", "anthropic", "session"),
    ),
    entry(
        SettingsPage.AiProviders, "Max MCP tool calls per request",
        AI_MAX_TOOL_CALLS_HINT,
        listOf("rounds", "limit", "budget", "tool calls", "llm", "mcp"),
    ),
)

private fun voiceInput() = listOf(
    entry(
        SettingsPage.VoiceInput, "Recognition engine",
        VoiceRecognitionEngines.description(VoiceRecognitionEngine.WHISPER),
        listOf("dictation", "microphone", "speech", "whisper", "apple", "windows", "voice"),
    ),
    entry(
        SettingsPage.VoiceInput, "Translate dictated speech to English",
        VOICE_TRANSLATE_HINT,
        listOf("whisper", "dictation", "translation", "voice"),
    ),
    entry(
        SettingsPage.VoiceInput, "Recognition language",
        VOICE_LANGUAGE_HINT,
        listOf("dictation", "speech", "ukrainian", "english", "whisper", "voice", "add language", "language code"),
    ),
    entry(
        SettingsPage.VoiceInput, "Recognition model",
        VoiceModelCatalog.byId(VoiceInputSettings().modelId).description,
        listOf("local model", "whisper", "download", "install", "base", "small", "dictation", "voice", "license"),
    ),
)

private fun customAiCommands() = listOf(
    entry(
        SettingsPage.AiCommands, "Add command",
        SettingsPage.AiCommands.description,
        listOf("slash", "prompt", "template", "macro", "custom", "llm", "shortcut"),
    ),
)

private fun sourceCode() = listOf(
    entry(
        SettingsPage.SourceCode, "Register source code",
        SOURCE_REGISTER_HINT,
        listOf("project", "index", "repository", "folder", "directory", "show in code", "add"),
    ),
    entry(
        SettingsPage.SourceCode, "Open command",
        SOURCE_OPEN_COMMAND_HINT,
        listOf("editor", "ide", "vs code", "intellij", "android studio", "cursor", "sublime", "zed", "external"),
    ),
    entry(
        SettingsPage.SourceCode, "Discover simple custom log wrappers",
        SOURCE_DISCOVER_WRAPPERS_HINT,
        listOf("timber", "log", "wrapper", "index", "reindex", "auto discovery"),
    ),
    entry(
        SettingsPage.SourceCode, "Logging configurations",
        SOURCE_LOG_CONFIGURATIONS_HINT,
        listOf("wrapper", "rules", "module", "source", "add configuration"),
    ),
)

private fun issues() = listOf(
    entry(
        SettingsPage.IssueCategories, "Custom issue categories",
        ISSUE_CATEGORIES_HINT,
        listOf("regex", "rules", "anchor", "category", "crash", "anr", "problem"),
    ),
)

private fun testing() = listOf(
    entry(
        SettingsPage.TestRuns, "Default judge",
        TESTING_JUDGE_HINT,
        listOf("test run", "judge", "verdict", "ai", "failed steps only", "every step"),
    ),
    entry(
        SettingsPage.TestRuns, "Default evidence to keep",
        TESTING_EVIDENCE_HINT,
        listOf("test run", "screenshots", "logcat", "transcript", "video", "scrcpy"),
    ),
    entry(
        SettingsPage.TestRuns, "Confirmation timeout",
        TESTING_CONFIRM_TIMEOUT_HINT,
        listOf("test run", "allow", "deny", "confirm", "wait", "timeout"),
    ),
    entry(
        SettingsPage.TestRuns, "Keep recording evidence with recorded cases",
        TESTING_KEEP_RECORDING_HINT,
        listOf("record", "recording", "rewrite", "reopen", "video", "password", "privacy", "disk", "storage", "evidence"),
    ),
    entry(
        SettingsPage.TestRuns, "Stored recording evidence",
        TESTING_STORED_EVIDENCE_HINT,
        listOf("record", "recording", "delete", "disk", "space", "size", "clean", "evidence", "storage"),
    ),
    entry(
        SettingsPage.About, "Edition",
        editionSummary(Edition.UNLIMITED),
        listOf("free", "premium", "unlimited", "limits", "suites", "cases", "friends"),
    ),
)

private fun issueTracker() = listOf(
    entry(
        SettingsPage.IssueTracker, "Use an issue tracker",
        TRACKER_USE_HINT,
        listOf("jira", "mcp", "issues", "tracker", "ticket", "bug", "send"),
    ),
    entry(
        SettingsPage.IssueTracker, "Tracker name",
        TRACKER_NAME_HINT,
        listOf("jira", "linear", "github", "name"),
    ),
    entry(
        SettingsPage.IssueTracker, "Tracker MCP URL",
        TRACKER_URL_HINT,
        listOf("mcp", "url", "server", "endpoint", "https", "address"),
    ),
    entry(
        SettingsPage.IssueTracker, "Authentication header",
        TRACKER_HEADER_HINT,
        listOf("authorization", "bearer", "header", "auth", "token", "api key"),
    ),
    entry(
        SettingsPage.IssueTracker, "Access token",
        TRACKER_TOKEN_HINT,
        listOf("token", "secret", "keychain", "password", "credential", "save token", "remove token", "api key"),
    ),
    entry(
        SettingsPage.IssueTracker, "Issue agent profile",
        TRACKER_PROFILE_HINT,
        listOf("ai", "profile", "agent", "claude", "codex", "model"),
    ),
    entry(
        SettingsPage.IssueTracker, "Issue instructions",
        TRACKER_PROMPT_HINT,
        listOf("prompt", "project key", "issue type", "labels", "fields", "instructions"),
    ),
    entry(
        SettingsPage.IssueTracker, "Test connection",
        TRACKER_CONNECTION_HINT,
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
    setOf("theme", "color", "colour", "colors", "colours", "dark", "light", "appearance", "palette"),
    setOf("center", "centre"),
    setOf("behavior", "behaviour"),
    setOf("recognizer", "recogniser"),
    setOf("authorization", "authorisation"),
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

/** Every word a result highlights for [tokens]: the words themselves and their synonyms ("colour" also marks "color"). */
internal fun searchHighlightTerms(tokens: List<String>): List<String> = tokens.flatMap { synonymsOf(it) }.distinct()

/**
 * Why [entry] matched when a word of the query is not in its visible label or description: the keyword (or, failing that, the page or group
 * name) that carried it, one per such word. Empty when every word is visible, which the row then highlights itself.
 */
internal fun searchMatchedKeywords(entry: SettingsSearchEntry, tokens: List<String>): List<String> {
    val visible = (entry.label.replace('\n', ' ') + " " + entry.hint).lowercase()
    val found = LinkedHashSet<String>()
    for (token in tokens) {
        val variants = synonymsOf(token)
        if (variants.any { it in visible }) continue
        val keyword = entry.keywords.firstOrNull { keyword -> variants.any { it in keyword.lowercase() } }
        val pageNames = listOfNotNull(entry.page.parent?.title, entry.page.title, entry.page.group.label)
        val name = pageNames.firstOrNull { pageName -> variants.any { it in pageName.lowercase() } }
        (keyword ?: name)?.let { found += it }
    }
    return found.toList()
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
