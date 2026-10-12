package com.indagium.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import com.indagium.testing.model.RunSummary
import kotlinx.coroutines.launch

// Deleting stored test runs from the Runs screen, a run's report and a suite's Runs tab. The disk work is
// AppState.deleteTestRun(s) -> TestRunCoordinator.deleteRun -> TestRunStore.delete; this file is the confirmation and the sizes
// the confirmation and the Runs header quote.

/** Which runs the confirmation is about, and what to do once they are gone (for example leave the deleted run's report). */
internal class RunDeleteRequest(val runs: List<RunSummary>, val onDeleted: () -> Unit = {})

/** The size in bytes of every stored run folder by run id; null until the disk walk (off the UI thread) finished. Reloads when runs change. */
@Composable
internal fun rememberRunSizes(): State<Map<String, Long>?> {
    val ui = LocalTestsUi.current
    val liveRevision = ui.state.testRuns.map { "${it.id}:${it.status}" }
    return produceState<Map<String, Long>?>(null, liveRevision, ui.state.testRunsFolderEpoch) { value = ui.state.testRunSizes() }
}

/** "3 runs · 1.2 GB" (or just "3 runs" while the sizes are still being read). */
internal fun runsUsageLine(count: Int, sizes: Map<String, Long>?, ids: Collection<String>): String {
    val noun = if (count == 1) "1 run" else "$count runs"
    return if (sizes == null) noun else "$noun · ${formatBytes(ids.sumOf { sizes[it] ?: 0L })}"
}

/** "Delete run?" / "Delete 12 runs?": the confirmation. States the count and the disk space once known; runs go off the UI thread. */
@Composable
internal fun RunDeleteDialog(request: RunDeleteRequest, onDismiss: () -> Unit) {
    val ui = LocalTestsUi.current
    val sizes by rememberRunSizes()
    val count = request.runs.size
    val ids = request.runs.map { it.id }
    val size = sizes?.let { " (${formatBytes(ids.sumOf { id -> it[id] ?: 0L })})" }.orEmpty()
    val what = if (count == 1) "This run" else "These $count runs"
    TestsConfirmDialog(
        title = if (count == 1) "Delete run?" else "Delete $count runs?",
        message = "$what$size will be deleted from disk with their reports, logs and screenshots. " +
            "Issues created from them stay. This cannot be undone.",
        confirmLabel = if (count == 1) "Delete run" else "Delete $count runs",
        onConfirm = {
            ui.scope.launch {
                val outcome = ui.state.deleteTestRuns(ids)
                if (outcome.failed == 0) {
                    ui.info(if (outcome.deleted == 1) "Deleted 1 run." else "Deleted ${outcome.deleted} runs.")
                } else {
                    ui.banner = TestsBanner(
                        "Deleted ${outcome.deleted} of $count runs. ${outcome.firstError.orEmpty()}".trim(),
                        isError = true,
                    )
                }
                if (outcome.deleted > 0) request.onDeleted()
            }
        },
        onDismiss = onDismiss,
    )
}
