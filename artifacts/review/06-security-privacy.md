# 06 Security, Privacy, and Logging

Reviewed:
- Source/config searches for secrets, tokens, URLs, external process execution, logging, and filesystem access.
- `AppState` autosave/note import/export paths.
- Drag/drop and recent-file handling.

No hardcoded secrets, tokens, external process execution, update/download logic, or network calls were found in production source/config.

## Findings

### OL2-007

Severity: medium

Area: Privacy and local data retention

File path: `src/desktopMain/kotlin/com/openlog/ui/AppState.kt`

Symbol/function/class if known: `serializeAutosave`, `autoExportAnnotations`, `tabToken`

Problem: Autosave persists recent file paths, active tab source paths, filters, and annotations. Annotation auto-export writes Markdown and sidecar files under `~/.openlog2/notes`. Logcat files can contain access tokens, PII, customer identifiers, crash details, or internal hostnames.

Why it matters: This is local persistence, not remote exfiltration, but users may not expect sensitive log excerpts and absolute source paths to be retained after closing the app. It also increases exposure in backups, shared machines, and support bundles.

Suggested fix: Add explicit privacy controls: disable autosave/auto-export, clear recent files/notes, clear autosave cache, and choose app-data location. Consider documenting what is stored locally and avoid auto-exporting note content unless enabled.

Suggested test: Add tests that verify disabling autosave/auto-export prevents writes and that clear-history removes recent paths from serialized state.

Confidence: high

### OL2-008

Severity: medium

Area: Unsafe ad hoc serialization

File path: `src/desktopMain/kotlin/com/openlog/ui/AppState.kt`

Symbol/function/class if known: `exportFilters`, `importFilters`, `jsonStr`, `jsonField`, `jsonArrayStr`

Problem: Saved-filter import/export hand-builds JSON and parses it with regular expressions and comma splitting. The escaping only handles backslash and quote; control characters and newlines are not escaped, and arrays break when values contain commas or escaped quotes.

Why it matters: A saved filter with a comma, quote, newline, or backslash-heavy regex can be corrupted or lost on export/import. Because filters can encode important analysis state, this is a user-visible data integrity issue.

Suggested fix: Use `kotlinx.serialization` or another JSON library for `SavedFilter` import/export, or reuse the existing token format consistently instead of pretending it is general JSON.

Suggested test: Round-trip saved filters containing commas, quotes, backslashes, newlines, regex metacharacters, and multiple message rules/sequences.

Confidence: high

## No Serious Issue Found

- No `Runtime.exec`, `ProcessBuilder`, HTTP clients, or update/download paths were found.
- Drag/drop file handling only accepts local file URIs that resolve to existing files and filters extensions for logs/filter imports.
