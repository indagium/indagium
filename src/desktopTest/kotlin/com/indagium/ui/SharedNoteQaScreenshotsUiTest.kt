@file:Suppress("MagicNumber")

package com.indagium.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.isRoot
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.unit.dp
import com.indagium.model.AnnBlock
import com.indagium.model.TabStripStyle
import org.junit.After
import org.junit.Rule
import org.junit.Test
import java.awt.image.BufferedImage
import java.io.File
import javax.imageio.ImageIO
import kotlin.io.path.createTempDirectory
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/** Writes the shared-note surfaces to build/shared-notes-qa (one PNG each) so they can be compared with the design boards by eye. */
@OptIn(ExperimentalTestApi::class)
class SharedNoteQaScreenshotsUiTest : SharedNoteUiTestBase() {
    private fun shareWatchNote(): String {
        val host = assertNotNull(state.createSharedNote("Watch drops after pairing", listOf("logcat", "Untitled")))
        rule.waitForIdle()
        return host
    }

    private fun qaSetup(width: Int = 760, height: Int = 520) =
        setup(width = width, height = height, tabs = listOf("logcat", "Untitled", "242543_1"))

    private fun shot(name: String) {
        val roots = rule.onAllNodes(isRoot())
        val count = roots.fetchSemanticsNodes().size
        // The window is the first root; each open popup or dialog adds one more, which captureToImage cannot name through onRoot().
        for (i in 0 until count) saveScreenshot(roots[i], if (i == 0) name else "$name-layer$i")
    }

    @Test
    fun newMenuOpen() {
        qaSetup()
        state.upAnn("logcat") { it.copy(annotations = it.annotations.copy(prefix = "Watch drops after pairing")) }
        putBlocks("logcat", AnnBlock.Note("n1", "Bonding completes at 22:27:51, the first ATT write fails two seconds later."))
        maximizeNotes()
        click(NOTES_NEW_MENU_TAG)
        shot("1-new-menu-open")
    }

    @Test
    fun shareDialog() {
        qaSetup(width = 700, height = 560)
        state.upAnn("logcat") { it.copy(annotations = it.annotations.copy(prefix = "Watch drops after pairing")) }
        state.sharedNoteDialog = SharedNoteDialogRequest.Share("logcat", shareExistingNote = true)
        rule.waitForIdle()
        shot("2-share-dialog")
    }

    @Test
    fun badgePopover() {
        qaSetup()
        shareWatchNote()
        state.setSharedNoteMembers(state.sharedNoteHosts.single().id, listOf("logcat", "Untitled", "242543_1"))
        state.closeTab("242543_1")
        rule.waitForIdle()
        maximizeNotes()
        click(SHARED_BADGE_TAG)
        shot("3-badge-popover")
    }

    @Test
    fun openMenu() {
        qaSetup()
        val host = shareWatchNote()
        assertNotNull(state.createSharedNote("Firmware 26.218 regressions", listOf("Untitled", "242543_1")))
        putBlocks(host, AnnBlock.Note("n1", "Bonding finishes on the phone, then the watch rejects the first write."))
        maximizeNotes()
        click(NOTES_RECENT_NOTES_TAG)
        shot("4-open-menu")
    }

    @Test
    fun sharedNoteWithReferences() {
        qaSetup(width = 900, height = 600)
        val host = shareWatchNote()
        state.activeTabId = "Untitled"
        rule.waitForIdle()
        putBlocks(
            host,
            AnnBlock.Note("t1", "Bonding finishes on the phone, then the watch rejects the first write:"),
            AnnBlock.LogRef(
                "r1", listOf(1, 2), "Bond state changed", "logcat", "logcat.log",
                listOf(entry(1, "1 from logcat"), entry(2, "2 from logcat")),
            ),
            AnnBlock.Note("t2", "Two seconds later the watch answers the encrypted write with an authentication error, then drops:"),
            AnnBlock.LogRef(
                "r2", listOf(2, 3), "ATT error", "Untitled", "Untitled.json",
                listOf(entry(2, "2 from Untitled"), entry(3, "3 from Untitled")), sourceSectionId = "Tethered Bluetooth / HCI",
            ),
        )
        maximizeNotes()
        shot("5-shared-note-references")
    }

    @Test
    fun sharedNoteInTheDefaultRightColumn() {
        qaSetup(width = 1100, height = 640)
        val host = shareWatchNote()
        putBlocks(
            host,
            AnnBlock.LogRef("r1", listOf(1, 2), "Bond state changed", "logcat", "logcat.log", listOf(entry(1, "1 from logcat"), entry(2, "2 from logcat"))),
        )
        shot("7-shared-note-default-layout")
    }
}

/** The tab strip drawn alone, in the three styles that differ most. */
@OptIn(ExperimentalTestApi::class)
class SharedNoteQaTabStripUiTest {
    @get:Rule
    val rule = createComposeRule()

    private lateinit var directory: File
    private lateinit var state: AppState

    @After
    fun tearDown() {
        if (::state.isInitialized) state.close()
        if (::directory.isInitialized) directory.deleteRecursively()
    }

    @Test
    fun theStripInThreeStyles() {
        directory = createTempDirectory("shared-note-qa-strip").toFile()
        state = AppState(File(directory, "state.cache"), notesDir = File(directory, "notes"), autoExportNotes = false)
        state.tabs = listOf("logcat", "Untitled", "242543_1").map { id -> mkTab(id, if (id == "Untitled") "Untitled.json" else "$id.log", emptyList()) }
        state.activeTabId = "Untitled"
        assertNotNull(state.createSharedNote("Watch drops after pairing", listOf("logcat", "Untitled")))
        listOf(TabStripStyle.CURRENT, TabStripStyle.RAISED, TabStripStyle.PILL).forEachIndexed { i, style ->
            state.updateSettings { it.copy(tabStripStyle = style) }
            rule.setContent {
                CompositionLocalProvider(LocalTheme provides LIGHT_THEME) {
                    Box(Modifier.size(640.dp, 40.dp)) { TabStrip(state, ChromeOrientation.HORIZONTAL) }
                }
            }
            rule.waitForIdle()
            val image = rule.onRoot().captureToImage()
            val pixels = image.toPixelMap()
            val out = BufferedImage(image.width, image.height, BufferedImage.TYPE_INT_ARGB)
            for (y in 0 until image.height) for (x in 0 until image.width) out.setRGB(x, y, pixels[x, y].toArgb())
            val directoryOut = File("build/shared-notes-qa").apply { mkdirs() }
            assertTrue(ImageIO.write(out, "png", File(directoryOut, "6-tab-strip-${i + 1}-${style.name.lowercase()}.png")))
        }
    }
}
