@file:Suppress("MagicNumber")

package com.indagium.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.key
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.SemanticsNodeInteraction
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.unit.dp
import com.indagium.model.AnnBlock
import com.indagium.model.LogEntry
import com.indagium.model.LogLevel
import com.indagium.model.LogTab
import org.junit.After
import org.junit.Rule
import java.awt.image.BufferedImage
import java.io.File
import java.util.concurrent.TimeUnit
import javax.imageio.ImageIO
import kotlin.io.path.createTempDirectory
import kotlin.test.assertTrue

/**
 * The real log view (FileView, so the real Notes tile) over an [AppState] with open tabs `a.log`, `b.log` and `c.log`, plus the shared-note
 * dialog host App.kt draws, for the shared-note UI tests: the New ▾ and Open ▾ menus, the Shared badge and its popover, the dialogs and the
 * row-reference badge. Tab files exist on disk so closed members can be reopened.
 */
@OptIn(ExperimentalTestApi::class)
abstract class SharedNoteUiTestBase {
    @get:Rule
    val rule = createComposeRule()

    protected lateinit var dir: File
    protected lateinit var state: AppState

    @After
    fun tearDownSharedNoteUi() {
        if (::state.isInitialized) state.close()
        if (::dir.isInitialized) dir.deleteRecursively()
    }

    protected fun entry(id: Int, msg: String = "line $id", ts: String = "10:00:%02d.000".format(id)) =
        LogEntry(id, ts, LogLevel.I, "App", msg)

    /** The three rows of tab [id]: the same text the file [logFile] writes, so a reopened file's rows match the ones a note froze. */
    protected fun rowsOf(id: String): List<LogEntry> = (1..3).map { entry(it, "$it from $id") }

    protected fun logFile(name: String): File = File(dir, name).apply {
        val id = name.removeSuffix(".log")
        writeText((1..3).joinToString("\n", postfix = "\n") { "06-26 10:00:%02d.000  123  456 I App: $it from $id".format(it) })
    }

    protected fun tabOf(id: String): LogTab = mkTab(id, "$id.log", rowsOf(id)).copy(sourcePath = logFile("$id.log").absolutePath)

    /** Composes the log view of the active tab at [width] x [height] dp; [tabs] are the open tabs, the first is active. */
    protected fun setup(
        width: Int = 1100,
        height: Int = 700,
        tabs: List<String> = listOf("a", "b", "c"),
        configure: (AppState) -> Unit = {},
    ) {
        dir = createTempDirectory("shared-note-ui").toFile()
        state = AppState(File(dir, "state.cache"), notesDir = File(dir, "notes"), autoExportNotes = false)
        state.tabs = tabs.map { tabOf(it) }
        state.activeTabId = tabs.first()
        configure(state)
        rule.setContent {
            CompositionLocalProvider(LocalTheme provides LIGHT_THEME) {
                Box(Modifier.size(width.dp, height.dp)) {
                    val active = state.activeTabId?.let { state.tab(it) }
                    if (active != null) key(active.id) { FileView(state, active) }
                    SharedNoteDialogHost(state)
                }
            }
        }
        rule.waitForIdle()
    }

    /** Composes only the Notes panel of the active tab, [width] dp wide (its own bar of buttons, no tile header). */
    protected fun setupPanel(
        width: Int,
        height: Int = 500,
        tabs: List<String> = listOf("a", "b", "c"),
        focusRequester: FocusRequester? = null,
        configure: (AppState) -> Unit = {},
    ) {
        dir = createTempDirectory("shared-note-ui").toFile()
        state = AppState(File(dir, "state.cache"), notesDir = File(dir, "notes"), autoExportNotes = false)
        state.tabs = tabs.map { tabOf(it) }
        state.activeTabId = tabs.first()
        configure(state)
        rule.setContent {
            CompositionLocalProvider(LocalTheme provides LIGHT_THEME) {
                Box(Modifier.size(width.dp, height.dp)) {
                    val active = state.activeTabId?.let { state.tab(it) }
                    if (active != null) {
                        key(active.id) {
                            BoundAnnotationPanel(
                                state = state, tab = active, width = width.toFloat(),
                                focusRequester = focusRequester, onPanelFocusChanged = {},
                            )
                        }
                    }
                    SharedNoteDialogHost(state)
                }
            }
        }
        rule.waitForIdle()
    }

    protected fun exists(tag: String): Boolean = rule.onAllNodesWithTag(tag).fetchSemanticsNodes().isNotEmpty()

    protected fun textExists(text: String): Boolean = rule.onAllNodesWithText(text, useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty()

    protected fun tag(tag: String): SemanticsNodeInteraction = rule.onNodeWithTag(tag)

    protected fun click(tag: String) {
        rule.onNodeWithTag(tag).performClick()
        rule.waitForIdle()
    }

    protected fun waitUntil(message: String, timeoutMs: Long = 20_000, condition: () -> Boolean) {
        val deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(timeoutMs)
        while (!condition() && System.nanoTime() < deadline) {
            rule.waitForIdle()
            Thread.sleep(25)
        }
        assertTrue(condition(), message)
    }

    protected fun putBlocks(noteId: String, vararg blocks: AnnBlock) =
        state.upAnn(noteId) { it.copy(annotations = it.annotations.copy(blocks = it.annotations.blocks + blocks)) }

    /** Maximizes the Notes tile so its card is wide enough for the buttons to sit in the header row. */
    protected fun maximizeNotes() {
        click("tile-maximize-notes")
    }

    protected fun saveScreenshot(node: SemanticsNodeInteraction, name: String) {
        val image = node.captureToImage()
        val pixels = image.toPixelMap()
        val rendered = BufferedImage(image.width, image.height, BufferedImage.TYPE_INT_ARGB)
        for (y in 0 until image.height) for (x in 0 until image.width) rendered.setRGB(x, y, pixels[x, y].toArgb())
        val directory = File("build/shared-notes-qa").apply { mkdirs() }
        assertTrue(ImageIO.write(rendered, "png", File(directory, "$name.png")))
    }
}
