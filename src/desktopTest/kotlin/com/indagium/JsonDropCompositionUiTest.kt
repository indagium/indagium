package com.indagium

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.snapshots.Snapshot
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.unit.dp
import com.indagium.model.LogFormatEngine
import com.indagium.model.TabContentState
import com.indagium.ui.AppState
import com.indagium.ui.FileView
import com.indagium.ui.logCapabilitiesForTab
import org.junit.After
import org.junit.Rule
import org.junit.Test
import java.io.File
import java.nio.file.Files
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

private const val DROP_LOAD_TIMEOUT_MS = 15_000L

@OptIn(ExperimentalTestApi::class)
class JsonDropCompositionUiTest {
    @get:Rule
    val rule = createComposeRule()

    private var tempDir: File? = null
    private var appState: AppState? = null

    @After
    fun tearDown() {
        appState?.close()
        tempDir?.deleteRecursively()
    }

    @Test
    fun aDroppedJsonFileDoesNotPublishCapabilityCacheWritesDuringComposition() {
        val directory = Files.createTempDirectory("json-drop-compose-").toFile().also { tempDir = it }
        val state = AppState(
            autosaveFile = File(directory, "state.cache"),
            restoreOnCreate = false,
            notesDir = File(directory, "notes"),
            archiveCacheDir = File(directory, "archive-cache"),
            customCommandsDir = File(directory, "commands"),
            filterBackupsDir = File(directory, "filter-backups"),
            autoExportNotes = false,
            controlTokenFile = File(directory, "control-token"),
            sourceIndexFile = File(directory, "source-index"),
            testingDir = File(directory, "testing"),
        ).also { appState = it }
        val source = File(directory, "dropped.json").apply {
            writeText(
                """
                {"timestamp":"2026-01-01T00:00:00Z","level":"INFO","logger":"drop","message":"first"}
                {"timestamp":"2026-01-01T00:00:01Z","level":"WARN","logger":"drop","message":"second"}
                """.trimIndent() + "\n",
            )
        }

        state.openDroppedFiles(listOf(source))
        rule.waitUntil(DROP_LOAD_TIMEOUT_MS) {
            state.tabs.singleOrNull { it.sourcePath == source.absolutePath }?.contentState == TabContentState.LOADED
        }
        val tabId = assertNotNull(state.tabs.singleOrNull { it.sourcePath == source.absolutePath }).id
        val loadedTab = assertNotNull(state.tab(tabId))
        assertEquals(LogFormatEngine.JSON_LINES, state.pinnedLogFormatProfileResult(tabId).getOrNull()?.engine)
        assertEquals(2, loadedTab.logData.size)

        // Exercise the legacy/in-flight summary fallback deterministically. The drop and parse above
        // still go through the real JSON file pipeline; only the cache is cleared before its first UI read.
        state.upTab(tabId) { it.copy(logCapabilities = emptyMap()) }
        state.annotationVisible = true
        val capabilityWrites = AtomicInteger()

        rule.setContent {
            Box(Modifier.size(1180.dp, 760.dp)) {
                state.tab(tabId)?.let { tab ->
                    Snapshot.observe(
                        readObserver = null,
                        writeObserver = { capabilityWrites.incrementAndGet() },
                    ) {
                        state.logCapabilitiesForTab(tab)
                    }
                    BasicText("JSON capability summary")
                    FileView(state, tab)
                }
            }
        }
        rule.waitForIdle()

        assertEquals(0, capabilityWrites.get(), "capability reads in composition must not write AppState state")
        assertTrue(state.tab(tabId)!!.logCapabilities.isEmpty(), "the UI read leaves the deliberately-cleared cache untouched")
    }
}
