# 01 Architecture and Module Boundaries

Reviewed:
- `src/desktopMain/kotlin/Main.kt`
- `src/desktopMain/kotlin/com/openlog/model/Model.kt`
- `src/desktopMain/kotlin/com/openlog/ui/AppState.kt`
- `src/desktopMain/kotlin/com/openlog/ui/App.kt`
- `src/desktopMain/kotlin/com/openlog/utils/*`

The app is a single KMP desktop target with clear package-level intent: `model` holds data types, `utils` holds parsing/filtering/sequence logic, and `ui` holds Compose UI. The main ownership concern is that `AppState` is effectively a UI store, service layer, persistence layer, file-dialog adapter, clipboard adapter, autosave serializer, and import/export implementation at the same time.

## Findings

### OL2-003

Severity: medium

Area: Architecture and state ownership

File path: `src/desktopMain/kotlin/com/openlog/ui/AppState.kt`

Symbol/function/class if known: `AppState`

Problem: `AppState` owns mutable UI flags, tab state, parser orchestration, file dialogs, clipboard writes, note auto-export, autosave serialization/deserialization, saved-filter JSON import/export, and filesystem paths in one class.

Why it matters: This makes ownership unclear and increases the chance that UI changes accidentally break persistence or file behavior. It also makes important behaviors hard to test with fake services, especially file dialogs, clipboard, autosave, and asynchronous file loading.

Suggested fix: Split behind small interfaces or collaborators: `LogFileLoader`, `AutosaveStore`, `SavedFilterStore`, `NoteStore`, and `DesktopDialogs/Clipboard`. Keep `AppState` focused on state transitions and inject the services.

Suggested test: Add state tests using fake stores/loaders that verify state transitions without touching real `FileDialog`, clipboard, or user-home paths.

Confidence: high

### OL2-013

Severity: low

Area: Architecture and duplicated wiring

File path: `src/desktopMain/kotlin/com/openlog/ui/App.kt`

Symbol/function/class if known: `FileView`, `CompareView`, `filterPanelFor`

Problem: `FilterPanel` and `LogViewer` callback wiring is duplicated between the normal file view and compare view.

Why it matters: This is not just style. Any future filter action or state parameter has to be updated in multiple places; IDEA also reports a long duplicated fragment. That increases the chance of compare mode drifting from file mode.

Suggested fix: Extract small adapter composables/functions that bind `AppState` to `FilterPanel` and `LogViewer` once per tab.

Suggested test: Add a regression test or composable-level harness around a shared action binding, such as importing filters or clearing filters from compare mode.

Confidence: medium

## No Serious Issue Found

- The basic dependency direction is acceptable for a compact desktop app: UI depends on model/utils, utils depend on model, and model is mostly data.
- No circular Gradle modules exist because this is currently a single-module project.
