# 05 File System, Resources, and Desktop OS Behavior

Reviewed:
- `Main.kt`
- `AppState.kt`
- `App.kt`
- `AnnotationPanel.kt`
- `LogParser.kt`
- resource/icon folders

Runtime resources for the window icon exist under `src/desktopMain/resources/icons/openlog.png`. Native distribution icons exist under `icons/` for macOS, Windows, and Linux.

## Findings

### OL2-005

Severity: medium

Area: File parsing and data preservation

File path: `src/desktopMain/kotlin/com/openlog/utils/LogParser.kt`

Symbol/function/class if known: `parseLogcat`

Problem: `parseLogcat` skips any line longer than 8192 characters before trying to preserve it as a `RAW` entry.

Why it matters: Android logs often contain long JSON payloads, stack traces, or encoded values. Silently dropping those lines can hide exactly the evidence a log-analysis tool is meant to surface.

Suggested fix: Preserve long unmatched lines as `RAW` entries, possibly truncated with a clear marker and original length, instead of dropping them.

Suggested test: Add a parser test with a non-empty line longer than 8192 characters and assert a `RAW` entry remains visible.

Confidence: high

### OL2-006

Severity: medium

Area: Desktop file locations

File path: `src/desktopMain/kotlin/com/openlog/ui/AppState.kt`

Symbol/function/class if known: `defaultAutosaveFile`, `notesDir`

Problem: App-private autosave and note files are stored in hardcoded `~/.openlog2` locations.

Why it matters: This ignores Windows/macOS/Linux app-data conventions and can surprise users who expect app state in OS-managed app data/cache folders.

Suggested fix: Use an app-directories provider for OS-specific config/cache/data locations.

Suggested test: Unit-test path selection separately from `AppState`.

Confidence: high

### OL2-011

Severity: low

Area: Cross-platform path handling

File path: `src/desktopMain/kotlin/com/openlog/ui/AppState.kt`

Symbol/function/class if known: `saveAnalysis`, `exportFiltersToFile`, `importFiltersFromFile`

Problem: Some FileDialog results are combined with `File(dir + path)` instead of `File(dir, path)`.

Why it matters: `FileDialog.directory` often includes a trailing separator, but relying on string concatenation is fragile across platforms and future API changes. Other parts of the app already use `File(fd.directory, it)`.

Suggested fix: Use `File(dir, path)` consistently.

Suggested test: Add a small pure helper test for joining FileDialog directory/file values without assuming trailing separators.

Confidence: medium

## No Serious Issue Found

- Window icon resource loading uses classpath resources, which should work after packaging.
- Native distribution icon files exist for configured package targets.
