package com.indagium.ui

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Text
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performScrollToIndex
import com.indagium.model.LogEntry
import com.indagium.model.LogLevel
import com.indagium.model.TabContentState
import org.junit.Rule
import org.junit.Test
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.io.path.createTempDirectory
import kotlin.test.assertEquals
import kotlin.test.assertNotSame
import kotlin.test.assertTrue

@OptIn(ExperimentalTestApi::class)
class InactiveTabScrollResidencyUiTest {
    @get:Rule
    val rule = createComposeRule()

    @Test
    fun unloadingDropsLazyListStateAndRestoresItsPrimitiveVisiblePosition() {
        val store = LogViewerScrollStateStore()
        val panelKey = "t1:main"
        val oldState = store.lazyState(panelKey)
        rule.setContent {
            LazyColumn(state = oldState, modifier = Modifier.fillMaxSize().testTag("rows")) {
                items((0 until 250).toList()) { index -> Text("row $index") }
            }
        }

        rule.onNodeWithTag("rows").performScrollToIndex(123)
        rule.waitForIdle()
        val visibleIndex = rule.runOnIdle { oldState.firstVisibleItemIndex }
        val visibleOffset = rule.runOnIdle { oldState.firstVisibleItemScrollOffset }

        rule.runOnIdle { store.snapshotAndDropLazyStates("t1") }
        val restored = store.lazyState(panelKey)

        assertNotSame(oldState, restored, "the released LazyListState is not held in the shell store")
        assertEquals(visibleIndex, restored.firstVisibleItemIndex)
        assertEquals(visibleOffset, restored.firstVisibleItemScrollOffset)
    }

    @Test
    fun fileViewKeepsScrollAcrossDisplayedUnloadAndBlockedReloadGap() {
        val dir = createTempDirectory("inactive-tab-scroll-gap").toFile()
        val source = File(dir, "scroll.log")
        source.bufferedWriter().use { writer ->
            repeat(200) { index -> writer.append("10:00:00.000 I/Tag: row $index\n") }
        }
        var now = 0L
        val parserEntered = CountDownLatch(1)
        val releaseParser = CountDownLatch(1)
        val app = AppState(
            autosaveFile = File(dir, "state.cache"),
            autoExportNotes = false,
            parser = { file ->
                parserEntered.countDown()
                releaseParser.await(10, TimeUnit.SECONDS)
                com.indagium.utils.parseLogFileResult(file)
            },
            monotonicNowNanos = { now },
        ).also { it.closedTabRowsReleaseDelayMs = 10L }
        try {
            app.settings = app.settings.copy(inactiveTabUnloadMinutes = 30)
            val rows = (1..200).map { id -> LogEntry(id, "10:00:00.000", LogLevel.I, "Tag", "row ${id - 1}") }
            app.tabs = listOf(
                mkTab("scroll", source.name, rows).copy(sourcePath = source.absolutePath),
                mkTab("visible", "visible.log", emptyList()),
            )
            app.activateTab("scroll")
            rule.setContent {
                CompositionLocalProvider(LocalTheme provides LIGHT_THEME) {
                    app.tab(app.activeTabId)?.let { FileView(app, it) }
                }
            }
            rule.waitForIdle()

            val initialScroll = app.logViewerScrollStateStore.lazyState("scroll:main")
            rule.runOnIdle { initialScroll.requestScrollToItem(150) }
            rule.waitForIdle()
            val initialIndex = rule.runOnIdle { initialScroll.firstVisibleItemIndex }
            assertTrue(initialIndex >= 100, "the actual log viewer is scrolled well past its first page")

            app.activateTab("visible")
            rule.waitForIdle() // FileView disposes and releases its active viewer pin.
            now = TimeUnit.MINUTES.toNanos(30)
            waitUntil("the hidden displayed tab should unload") {
                app.sweepInactiveTabs()
                app.tab("scroll")?.contentState == TabContentState.UNLOADED
            }

            app.activateTab("scroll")
            assertTrue(parserEntered.await(10, TimeUnit.SECONDS), "reactivation begins a reload")
            rule.waitForIdle()
            assertEquals(TabContentState.LOADING, app.tab("scroll")?.contentState)
            assertTrue(
                rule.onAllNodesWithTag("log-row-scroll:main-150").fetchSemanticsNodes().isEmpty(),
                "the row host stays unmounted during loading",
            )

            releaseParser.countDown()
            waitUntil("the selected log should finish loading") {
                app.tab("scroll")?.contentState == TabContentState.LOADED
            }
            rule.waitForIdle()
            val restoredScroll = app.logViewerScrollStateStore.lazyState("scroll:main")
            assertTrue(restoredScroll.firstVisibleItemIndex >= 100, "loaded LogViewer restores the previous list position")
        } finally {
            releaseParser.countDown()
            app.close()
            dir.deleteRecursively()
        }
    }

    private fun waitUntil(message: String, condition: () -> Boolean) {
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(20)
        while (!condition() && System.nanoTime() < deadline) Thread.sleep(10)
        assertTrue(condition(), message)
    }
}
