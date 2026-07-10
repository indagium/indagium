package com.openlog.ui

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.openlog.utils.writeFileAtomically
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.io.File

// Debounce for saveInBackground() — long enough to collapse a rapid splitter drag (many
// pointer-move events per second) into one write, short enough that the save still feels
// immediate, matching the feel of the existing keyword-input debounce (150/350ms).
private const val BACKGROUND_AUTOSAVE_DEBOUNCE_MS = 150L

// Extracted from AppState (Task 12 slice 3, mechanical — no behavior change): owns *when* the
// autosave file gets written and how bursts of writes get throttled. What actually gets encoded
// (serializeAutosave) and how a saved file gets decoded back (restoreAutosave and friends) stay on
// AppState — that logic touches nearly every field AppState owns (settings, tabs, filters,
// annotations, layout, ...), and forwarding all of it through another class would trade a small
// amount of AppState line-count for a much larger, harder-to-review diff with no real decoupling
// benefit — the same scoping call slice 2 made for load-tracking vs. tailing.
internal class AutosaveScheduler(
    private val autosaveFile: File,
    private val scope: CoroutineScope,
    private val serialize: () -> String,
) {
    // Job backing saveInBackground()'s debounce — cancelling and relaunching it on every call is
    // what collapses a rapid burst (e.g. dragging a splitter) into a single write.
    private var backgroundJob: Job? = null

    // Set when a write fails (disk full, permissions, etc.); shown as a small inline hint rather
    // than a blocking dialog, since a failed autosave shouldn't interrupt the user's work. Cleared
    // on the next successful write.
    var autosaveError by mutableStateOf<String?>(null)
        private set

    // Synchronous by design: ~35 existing tests call this then immediately construct a second
    // AppState(restoreOnCreate = true) to assert round-trip content, and Main.kt's shutdown path
    // needs the write to complete before exitApplication() runs. writeFileAtomically replaces
    // the destination only once the write fully succeeds, so a failure here can never corrupt an
    // existing autosave file — it only fails to update it, which autosaveError then surfaces.
    fun saveNow() {
        runCatching {
            writeFileAtomically(autosaveFile) { writer -> writer.write(serialize()) }
        }.fold(
            onSuccess = { autosaveError = null },
            onFailure = { error ->
                autosaveError = "Could not save your session: " +
                    (error.message ?: error::class.simpleName.orEmpty().ifBlank { "unknown error" })
            },
        )
    }

    // Used only by the drag-driven pane-size mutators — unlike saveNow(), nothing depends on this
    // completing synchronously, so it can debounce (cancel-and-relaunch) and run entirely off the
    // UI thread instead of doing file I/O on every pointer-move event of a drag.
    fun saveInBackground() {
        backgroundJob?.cancel()
        backgroundJob = scope.launch {
            delay(BACKGROUND_AUTOSAVE_DEBOUNCE_MS)
            saveNow()
        }
    }
}
