# 02 Kotlin Multiplatform Correctness

Reviewed:
- `build.gradle.kts`
- `settings.gradle.kts`
- `src/desktopMain`
- `src/desktopTest`

The project uses Kotlin Multiplatform with a single `jvm("desktop")` target. There is no `commonMain` directory in the repository. That means most classic KMP concerns, such as `expect`/`actual` correctness and JVM APIs leaking into shared code, do not currently apply.

## Findings

No blocker/high KMP correctness findings.

### OL2-006

Severity: medium

Area: Desktop portability and app data locations

File path: `src/desktopMain/kotlin/com/openlog/ui/AppState.kt`

Symbol/function/class if known: `defaultAutosaveFile`, `notesDir`

Problem: Autosave and note storage use hardcoded `File(System.getProperty("user.home"), ".openlog2/...")` paths.

Why it matters: This is technically JVM-desktop-only code, so it does not violate `commonMain`, but it is not idiomatic across the target desktop OSes. Windows users expect app data under `%APPDATA%` or `%LOCALAPPDATA%`, macOS under `~/Library/Application Support` or cache locations, and Linux under XDG directories. Hardcoding a dot directory in home also complicates cleanup and enterprise profiles.

Suggested fix: Introduce a desktop app-directories abstraction that selects OS-appropriate config/cache/data folders. Inject it into `AppState`.

Suggested test: Add tests for path selection with fake `os.name`, env/home inputs, or a pure function that maps platform info to app directories.

Confidence: high

## No Serious Issue Found

- JVM/Desktop APIs (`java.awt`, `java.io.File`) are isolated to `desktopMain`.
- No `expect`/`actual` declarations are present, so there are no mismatches to review.
- Dependency placement is coherent for a single desktop target: Compose dependencies live in `desktopMain`, tests depend on `kotlin("test")`.
