@file:Suppress("MagicNumber")

package com.indagium.ui

import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import com.indagium.model.AnnBlock
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** A row reference in a shared note names its source file (and section) in a badge in its header line and has a Go to rows link. */
@OptIn(ExperimentalTestApi::class)
class SharedNoteReferenceUiTest : SharedNoteUiTestBase() {
    private lateinit var host: String

    private fun ref(
        id: String = "r1",
        source: String? = "a",
        ids: List<Int> = listOf(1, 2),
        filename: String? = source?.let { "$it.log" },
        section: String? = null,
        frozen: Boolean = true,
    ) = AnnBlock.LogRef(
        id = id,
        logIds = ids,
        caption = "Bond state changed",
        sourceTabId = source,
        sourceFilename = filename,
        sourceEntries = if (frozen) ids.map { entry(it, "$it from ${source ?: "a"}") } else null,
        sourceSectionId = section,
    )

    private fun assertBadgeLabel(blockId: String, label: String) {
        val texts = rule.onAllNodes(hasAnyAncestor(hasTestTag(logRefSourceBadgeTag(blockId))) and hasText(label), useUnmergedTree = true)
        assertEquals(1, texts.fetchSemanticsNodes().size, "the badge of $blockId reads \"$label\"")
    }

    /** Tab b is the active one and views the shared note (a and b are members). */
    private fun setupShared(vararg blocks: AnnBlock) {
        setup()
        state.activeTabId = "b"
        host = assertNotNull(state.createSharedNote("Pairing", listOf("a", "b")))
        putBlocks(host, *blocks)
        rule.waitForIdle()
    }

    @Test
    fun aCrossFileReferenceShowsItsFileAsABadgeWithAGoToRowsLink() {
        setupShared(ref())

        assertBadgeLabel("r1", "a.log")
        assertTrue(exists(logRefGoToRowsTag("r1")))
        assertFalse(textExists("from a.log"), "the old accent text is gone")
    }

    @Test
    fun aSectionReferenceNamesTheFileAndTheSection() {
        setupShared(ref(id = "r2", source = "a", filename = "Untitled.json", section = "Tethered Bluetooth / HCI"))

        assertBadgeLabel("r2", "Untitled.json · HCI")
    }

    @Test
    fun aReferenceToTheTabsOwnRowsHasNoBadgeButKeepsItsChip() {
        setup()
        putBlocks("a", ref(id = "own", source = null, ids = listOf(1)))

        assertFalse(exists(logRefSourceBadgeTag("own")))
        assertTrue(exists(logRefGoToRowsTag("own")), "the log ↗ chip is the reference's link to its rows")
        assertTrue(textExists("log ↗"))
    }

    @Test
    fun theChipIsTheOnlyLinkToTheRows() {
        setupShared(ref())

        assertFalse(textExists("Go to rows"), "no separate Go to rows link next to the chip")
        assertTrue(textExists("log ↗"))
    }

    @Test
    fun goToRowsShowsTheSourceTabWithTheRowsSelected() {
        setupShared(ref())
        assertEquals("b", state.activeTabId)

        click(logRefGoToRowsTag("r1"))

        waitUntil("the source tab is active with its rows selected") {
            state.activeTabId == "a" && state.tab("a")?.selected == setOf(1, 2)
        }
    }

    @Test
    fun aClosedSourceOffersToReopenAndThenGoesToTheRows() {
        setupShared(ref())
        state.closeTab("a")
        rule.waitForIdle()
        assertNull(state.tab("a"))

        click(logRefGoToRowsTag("r1"))

        waitUntil("the reopen prompt appears") { exists(logRefReopenTag("r1")) }
        assertTrue(textExists("a.log is closed."))
        assertNull(state.tab("a"), "nothing is opened until the user says so")

        click(logRefReopenTag("r1"))

        waitUntil("the source reopens and its rows are selected") {
            state.tabs.any { it.sourcePath?.endsWith("a.log") == true && it.selected == setOf(1, 2) && state.activeTabId == it.id }
        }
        assertFalse(exists(logRefReopenTag("r1")), "the prompt is gone")
    }

    @Test
    fun rowsThatAreGoneShowTheNotice() {
        setupShared(ref(ids = listOf(99), frozen = false))
        assertNull(state.pendingDiagramNotice)

        click(logRefGoToRowsTag("r1"))

        waitUntil("the notice appears") { state.pendingDiagramNotice != null }
        assertEquals("These rows are no longer in a.log.", state.pendingDiagramNotice!!.body)
    }

    @Test
    fun aSourceThatCannotBeFoundDoesNothingVisible() {
        setupShared(ref(source = "zz", filename = "zz.log"))

        click(logRefGoToRowsTag("r1"))
        Thread.sleep(300)
        rule.waitForIdle()

        assertNull(state.pendingDiagramNotice)
        assertFalse(exists(logRefReopenTag("r1")))
        assertEquals("b", state.activeTabId)
    }

    @Test
    fun clickingTheShowInLogChipOfAPrivateReferenceStillSelectsItsRows() {
        setup()
        putBlocks("a", ref(id = "own", source = null, ids = listOf(2, 3)))
        state.activeTabId = "b"
        rule.waitForIdle()
        state.activeTabId = "a"
        rule.waitForIdle()

        rule.onNodeWithText("log ↗").performClick()

        waitUntil("the rows are selected through go-to-rows") { state.tab("a")?.selected == setOf(2, 3) }
    }
}
