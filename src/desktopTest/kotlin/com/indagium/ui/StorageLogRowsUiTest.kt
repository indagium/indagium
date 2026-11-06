package com.indagium.ui

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.test.click
import androidx.compose.ui.test.doubleClick
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.performMouseInput
import androidx.compose.ui.test.performScrollToIndex
import androidx.compose.ui.test.pressKey
import com.indagium.model.Filter
import com.indagium.model.FilterMode
import com.indagium.model.LogFormat
import com.indagium.model.LogTab
import com.indagium.model.SearchScope
import com.indagium.model.searchFor
import com.indagium.storage.ProjectionViewportController
import com.indagium.storage.SourceId
import com.indagium.storage.StackFileSession
import kotlinx.coroutines.runBlocking
import org.junit.Rule
import org.junit.Test
import java.nio.file.Files
import java.util.concurrent.atomic.AtomicReference

class StorageLogRowsUiTest {
    @get:Rule
    val rule = createComposeRule()

    @Suppress("TooGenericExceptionCaught") // The temporary fixture must be removed if opening fails.
    @Test
    fun generatedFileRendersAndScrollsThroughBoundedPages() {
        val file = Files.createTempFile("storage-ui-", ".log")
        val controller = try {
            Files.newBufferedWriter(file).use { writer ->
                repeat(1000) { writer.appendLine("01-01 12:00:00.000 1 1 I App: generated-row-$it") }
            }
            val session = runBlocking { StackFileSession.open(SourceId("ui"), file, Filter(), LogFormat.LOGCAT, false, pageRows = 64) }
            ProjectionViewportController(session, 64)
        } catch (error: Throwable) {
            Files.deleteIfExists(file)
            throw error
        }
        try {
            val tab = LogTab("storage-ui", "generated.log", emptyList(), emptyMap())
            rule.setContent {
                LogViewer(
                    tab = tab, modifier = Modifier.fillMaxSize(), onSelRow = { _, _, _ -> },
                    onCtxMenu = { _, _, _, _, _, _ -> }, onToggleGroup = {}, onClearFilter = {},
                    onExpandAll = {}, onCollapseAll = {}, onToggleUnfiltered = {},
                    onExportTxt = {}, onExportCsv = {}, storageViewport = controller,
                )
            }
            rule.waitUntil(15_000) { rule.onAllNodesWithText("generated-row-0", substring = true).fetchSemanticsNodes().isNotEmpty() }
            rule.onNodeWithTag("storage-log-rows").performScrollToIndex(700)
            rule.waitUntil(15_000) { rule.onAllNodesWithText("generated-row-700", substring = true).fetchSemanticsNodes().isNotEmpty() }
        } finally {
            rule.setContent { }
            controller.close()
            runBlocking { controller.awaitClosed() }
            Files.deleteIfExists(file)
        }
    }

    @Test
    fun filterChangeReplacesTheFileBackedViewerGeneration() {
        val file = Files.createTempFile("storage-ui-filter-", ".log")
        try {
            Files.writeString(file, """
                01-01 12:00:00.000 1 1 I App: old-row
                01-01 12:00:00.001 1 1 I App: wanted-row
            """.trimIndent())
            val tab = mutableStateOf(LogTab("filter-ui", "generated.log", emptyList(), emptyMap()))
            rule.setContent { StorageFileLogViewer(tab.value, file, Modifier.fillMaxSize(), pageRows = 16) }
            rule.waitUntil(15_000) { rule.onAllNodesWithText("old-row", substring = true).fetchSemanticsNodes().isNotEmpty() }
            rule.runOnIdle { tab.value = tab.value.copy(filter = Filter(mode = FilterMode.KEYWORD, kwText = "wanted")) }
            rule.waitUntil(15_000) {
                rule.onAllNodesWithText("1 displayed items", substring = true).fetchSemanticsNodes().isNotEmpty() &&
                    rule.onAllNodesWithText("wanted-row", substring = true).fetchSemanticsNodes().isNotEmpty()
            }
            rule.waitUntil(15_000) { rule.onAllNodesWithText("old-row", substring = true).fetchSemanticsNodes().isEmpty() }
        } finally {
            rule.setContent { }
            Files.deleteIfExists(file)
        }
    }

    @Test
    fun stackToggleRebuildsThePagedView() {
        val file = Files.createTempFile("storage-ui-stack-", ".log")
        try {
            Files.writeString(file, """
                01-01 12:00:00.000 1 1 E App: FATAL EXCEPTION: main
                01-01 12:00:00.001 1 1 E App:     at app.Example.go(A.kt:1)
                01-01 12:00:00.002 1 1 I App: done
            """.trimIndent())
            val tab = mutableStateOf(LogTab("stack-ui", "generated.log", emptyList(), emptyMap()))
            rule.setContent {
                StorageFileLogViewer(tab.value, file, Modifier.fillMaxSize(), pageRows = 16, onToggleGroup = { gid ->
                    tab.value = tab.value.copy(expanded = tab.value.expanded + gid)
                })
            }
            rule.waitUntil(15_000) { rule.onAllNodesWithText("FATAL EXCEPTION", substring = true).fetchSemanticsNodes().isNotEmpty() }
            rule.onNodeWithTag("storage-row-0").performMouseInput { doubleClick() }
            rule.waitUntil(15_000) { rule.onAllNodesWithText("app.Example.go", substring = true).fetchSemanticsNodes().isNotEmpty() }
        } finally {
            rule.setContent { }
            Files.deleteIfExists(file)
        }
    }

    @Test
    fun experimentalAppTabOpensFiltersAndClosesGeneratedFile() {
        val home = Files.createTempDirectory("storage-app-ui-")
        val file = home.resolve("generated.log")
        Files.writeString(file, """
            01-01 12:00:00.000 1 1 I App: app-old-row
            01-01 12:00:00.001 1 1 I App: app-wanted-row
            01-01 12:00:00.002 1 1 E App: FATAL EXCEPTION: main
            01-01 12:00:00.003 1 1 E App:     at app.Example.go(A.kt:1)
            01-01 12:00:00.004 1 1 I App: after-stack
        """.trimIndent())
        val state = AppState(
            autosaveFile = home.resolve("autosave").toFile(), notesDir = home.resolve("notes").toFile(),
            archiveCacheDir = home.resolve("archives").toFile(), customCommandsDir = home.resolve("commands").toFile(),
            controlTokenFile = home.resolve("token").toFile(), sourceIndexFile = home.resolve("index").toFile(),
            diagramLibraryStore = DiagramLibraryStore(home.resolve("diagrams").toFile()), autoExportNotes = false,
        )
        try {
            val copied = AtomicReference<String>()
            state.experimentalStorageCopyPublisher = { copied.set(it) }
            val tabId = state.openStorageFileForTesting(file.toFile())
            rule.setContent { state.tab(tabId)?.let { FileView(state, it) } }
            rule.waitUntil(15_000) { rule.onAllNodesWithText("app-old-row", substring = true).fetchSemanticsNodes().isNotEmpty() }
            rule.waitUntil(15_000) { state.tab(tabId)?.analysis?.tagCounts?.get("App") == 5 }
            rule.runOnIdle {
                state.setFilterMode(tabId, FilterMode.KEYWORD)
                state.setKw(tabId, "wanted")
            }
            rule.waitUntil(15_000) {
                rule.onAllNodesWithText("1 displayed items", substring = true).fetchSemanticsNodes().isNotEmpty() &&
                    rule.onAllNodesWithText("app-wanted-row", substring = true).fetchSemanticsNodes().isNotEmpty()
            }
            rule.onNodeWithTag("storage-row-0").performMouseInput { click() }
            rule.waitUntil(15_000) { state.tab(tabId)?.selected == setOf(2) }
            rule.onNodeWithTag("storage-row-0").performKeyInput {
                val action = if (isMacOs) Key.MetaLeft else Key.CtrlLeft
                keyDown(action)
                pressKey(Key.C)
                keyUp(action)
            }
            rule.waitUntil(15_000) { copied.get()?.contains("app-wanted-row") == true }
            rule.runOnIdle { state.setKw(tabId, ""); copied.set(null) }
            rule.waitUntil(15_000) { rule.onAllNodesWithText("4 displayed items", substring = true).fetchSemanticsNodes().isNotEmpty() }
            rule.onNodeWithTag("storage-row-0").performMouseInput { click() }
            rule.onNodeWithTag("storage-row-0").performKeyInput {
                val action = if (isMacOs) Key.MetaLeft else Key.CtrlLeft
                keyDown(action)
                pressKey(Key.A)
                pressKey(Key.C)
                keyUp(action)
            }
            rule.waitUntil(15_000) { copied.get()?.let { "app-old-row" in it && "app-wanted-row" in it } == true }
            rule.runOnIdle {
                state.openSearch(tabId, SearchScope.FILTERED)
                state.setSearchQuery(tabId, "Example", SearchScope.FILTERED)
            }
            rule.waitUntil(15_000) {
                state.tab(tabId)?.searchFor(SearchScope.FILTERED)?.let { !it.isSearching && it.matchIds.contentEquals(intArrayOf(4)) } == true
            }
            rule.runOnIdle { state.searchNext(tabId, SearchScope.FILTERED) }
            rule.waitUntil(15_000) {
                state.searchNavigationFor(tabId, SearchScope.FILTERED) == null &&
                    rule.onAllNodesWithText("app.Example.go", substring = true).fetchSemanticsNodes().isNotEmpty()
            }
            rule.runOnIdle { state.closeTab(tabId) }
            rule.waitUntil(15_000) { state.tab(tabId) == null }
        } finally {
            rule.setContent { }
            state.close()
            home.toFile().deleteRecursively()
        }
    }
}
