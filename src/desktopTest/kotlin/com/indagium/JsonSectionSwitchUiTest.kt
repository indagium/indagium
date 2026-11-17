package com.indagium

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import com.indagium.model.AnnBlock
import com.indagium.model.LogEntry
import com.indagium.model.TabContentState
import com.indagium.model.resolveRows
import com.indagium.ui.AppState
import com.indagium.ui.DARK_GITHUB
import com.indagium.ui.FileView
import com.indagium.ui.LocalTheme
import com.indagium.ui.staleRowsTag
import com.indagium.utils.metadataJsonProfile
import com.indagium.utils.parseLogFileResultWithProfiles
import org.junit.Rule
import org.junit.Test
import java.io.File
import java.nio.file.Files
import java.util.concurrent.CopyOnWriteArraySet
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

private const val SWITCH_TIMEOUT_MS = 15000L
private const val SETTLE_MS = 700L
private const val ROOT = "Tethered Bluetooth"
private const val HCI = "$ROOT / HCI"
private const val L2CAP = "$ROOT / L2CAP"
private const val SDP = "$ROOT / SDP"
private const val T0 = 1_790_000_000_000_000_000L

/** Switching JSON sections keeps the shown rows until the picked section has loaded, and reads only that section when it can. */
@Suppress("MagicNumber") // Synthetic wire timestamps.
@OptIn(ExperimentalTestApi::class)
class JsonSectionSwitchUiTest {
    @get:Rule
    val rule = createComposeRule()

    private fun packet(identity: String, title: String, offset: Long): String =
        """{"metadata":{"identity":"$identity","source":"Demo","system_time":${T0 + offset}},"Summary":{"Note":"$title"}}"""

    /** Interleaved times, so a section's global row ids are not its own 1..n. */
    private fun document(): String =
        """{"$ROOT":{"HCI":[${packet("HCI.A", "hci one", 10)},${packet("HCI.B", "hci two", 50)}],""" +
            """"L2CAP":[${packet("L2.A", "l2cap one", 20)},${packet("L2.B", "l2cap two", 60)}],""" +
            """"SDP":[${packet("SDP.A", "sdp one", 30)},${packet("SDP.B", "sdp two", 70)}]}}"""

    private fun withApp(block: (AppState, File, String) -> Unit) {
        val directory = Files.createTempDirectory("json-section-switch-").toFile()
        val file = File(directory, "capture.json").apply { writeText(document()) }
        val app = AppState(autosaveFile = File(directory, "state.cache"), autoExportNotes = false)
        try {
            val tabId = assertNotNull(app.openFile(file))
            waitForSection(app, tabId, HCI)
            block(app, file, tabId)
        } finally {
            app.onLogSectionLoadStart = null
            app.close()
            directory.deleteRecursively()
        }
    }

    private fun waitForSection(app: AppState, tabId: String, sectionId: String) {
        rule.waitUntil(SWITCH_TIMEOUT_MS) {
            val tab = app.tab(tabId)
            tab?.contentState == TabContentState.LOADED && tab.selectedLogSectionId == sectionId && tabId !in app.pendingLogSection
        }
        rule.waitForIdle()
    }

    private fun messages(app: AppState, tabId: String): List<String> = app.tab(tabId)!!.logData.map { it.msg }

    private fun fullParseRows(file: File, sectionId: String): List<LogEntry> =
        parseLogFileResultWithProfiles(file, emptyList(), metadataJsonProfile()).sections.single { it.id == sectionId }.entries

    @Test
    fun theShownRowsStayUntilThePickedSectionArrivesWithItsOwnView() = withApp { app, _, tabId ->
        val hciRows = app.tab(tabId)!!.logData
        assertEquals(listOf("hci one", "hci two"), hciRows.map { it.msg })
        app.setKw(tabId, "hci")
        app.selRow(tabId, hciRows.last().id, multi = false, range = false)
        rule.setContent {
            CompositionLocalProvider(LocalTheme provides DARK_GITHUB) {
                Box(Modifier.fillMaxSize()) { app.tab(tabId)?.let { FileView(app, it) } }
            }
        }
        rule.onNodeWithTag(staleRowsTag(tabId)).assertDoesNotExist()

        val states = CopyOnWriteArraySet<TabContentState>()
        val gate = CountDownLatch(1)
        app.onLogSectionLoadStart = { _, _ -> gate.await(SWITCH_TIMEOUT_MS, TimeUnit.MILLISECONDS) }
        rule.onNodeWithTag("json-section-$L2CAP").performClick()
        rule.waitUntil(SWITCH_TIMEOUT_MS) { app.pendingLogSection[tabId] == L2CAP }
        rule.waitForIdle()
        app.tab(tabId)?.contentState?.let(states::add)

        val during = app.tab(tabId)!!
        assertEquals(TabContentState.LOADED, during.contentState)
        assertTrue(during.logData === hciRows, "the old rows stay published, not blanked")
        assertEquals(HCI, during.selectedLogSectionId)
        assertEquals("hci", during.filter.kwText, "the old rows keep their own filter until the new rows replace them")
        assertEquals(setOf(hciRows.last().id), during.selected)
        rule.onNodeWithTag("json-section-$L2CAP").assertIsSelected()
        rule.onNodeWithTag(staleRowsTag(tabId)).assertExists()

        gate.countDown()
        waitForSection(app, tabId, L2CAP)
        app.tab(tabId)?.contentState?.let(states::add)

        assertEquals(setOf(TabContentState.LOADED), states.toSet(), "a switch never shows the tab as unloaded")
        val arrived = app.tab(tabId)!!
        assertEquals(listOf("l2cap one", "l2cap two"), arrived.logData.map { it.msg })
        assertEquals("", arrived.filter.kwText, "a section never opened has a fresh view")
        assertTrue(arrived.selected.isEmpty())
        assertNull(arrived.inspectedRecordId)
        rule.onNodeWithTag(staleRowsTag(tabId)).assertDoesNotExist()

        app.onLogSectionLoadStart = null
        app.selectLogSection(tabId, HCI)
        waitForSection(app, tabId, HCI)
        assertEquals("hci", app.tab(tabId)!!.filter.kwText, "the earlier section's filter comes back with it")
        assertEquals(setOf(hciRows.last().id), app.tab(tabId)!!.selected)
    }

    @Test
    fun aNewerPickSupersedesALoadStillRunning() = withApp { app, _, tabId ->
        val gate = CountDownLatch(1)
        app.onLogSectionLoadStart = { _, section -> if (section == L2CAP) gate.await(SWITCH_TIMEOUT_MS, TimeUnit.MILLISECONDS) }
        app.selectLogSection(tabId, L2CAP)
        rule.waitUntil(SWITCH_TIMEOUT_MS) { app.pendingLogSection[tabId] == L2CAP }
        app.selectLogSection(tabId, SDP)
        assertEquals(SDP, app.pendingLogSection[tabId])
        waitForSection(app, tabId, SDP)
        gate.countDown()
        Thread.sleep(SETTLE_MS)
        rule.waitForIdle()
        val settled = app.tab(tabId)!!
        assertEquals(SDP, settled.selectedLogSectionId, "the superseded load's rows are discarded")
        assertEquals(listOf("sdp one", "sdp two"), settled.logData.map { it.msg })
        assertTrue(app.pendingLogSection.isEmpty())
    }

    @Test
    fun pickingTheShownSectionAgainCancelsTheLoad() = withApp { app, _, tabId ->
        val gate = CountDownLatch(1)
        app.onLogSectionLoadStart = { _, _ -> gate.await(SWITCH_TIMEOUT_MS, TimeUnit.MILLISECONDS) }
        app.selectLogSection(tabId, L2CAP)
        rule.waitUntil(SWITCH_TIMEOUT_MS) { app.pendingLogSection[tabId] == L2CAP }
        app.selectLogSection(tabId, HCI)
        assertTrue(app.pendingLogSection.isEmpty())
        gate.countDown()
        Thread.sleep(SETTLE_MS)
        assertEquals(HCI, app.tab(tabId)!!.selectedLogSectionId)
        assertEquals(listOf("hci one", "hci two"), messages(app, tabId))
    }

    @Test
    fun rowsReadThroughTheIndexKeepTheirGlobalIdsSoNotesStillResolve() = withApp { app, file, tabId ->
        val expected = fullParseRows(file, L2CAP)
        val reference = expected.first()
        app.upAnn(tabId) { it.copy(annotations = it.annotations.copy(blocks = it.annotations.blocks + AnnBlock.LogRef("ref", listOf(reference.id), "l2"))) }
        val ref = app.tab(tabId)!!.annotations.blocks.filterIsInstance<AnnBlock.LogRef>().single()
        assertTrue(ref.resolveRows(app.tab(tabId)!!).isEmpty(), "the note's row is not in the shown section")

        // Blank the HCI records without changing the file's length or time: only a switch that reads just the L2CAP bytes still sees the
        // original ids (a full parse would renumber L2CAP's rows from the damaged document).
        val modified = file.lastModified()
        file.writeText(file.readText().replace("hci one", "xxxxxxx").replace("hci two", "yyyyyyy"))
        assertTrue(file.setLastModified(modified))
        app.selectLogSection(tabId, L2CAP)
        waitForSection(app, tabId, L2CAP)
        assertEquals(expected.map { it.id }, app.tab(tabId)!!.logData.map { it.id })
        assertEquals(listOf(reference.msg), ref.resolveRows(app.tab(tabId)!!).map { it.msg })
        assertEquals(expected, app.tab(tabId)!!.logData.toList())
    }

    @Test
    fun aChangedFileIsReadInFullAndTheSwitchStillCompletes() = withApp { app, file, tabId ->
        file.appendText("\n")
        app.selectLogSection(tabId, SDP)
        waitForSection(app, tabId, SDP)
        assertEquals(listOf("sdp one", "sdp two"), messages(app, tabId))
        app.selectLogSection(tabId, L2CAP)
        waitForSection(app, tabId, L2CAP)
        assertEquals(listOf("l2cap one", "l2cap two"), messages(app, tabId))
    }

    @Test
    fun aFailedLoadLeavesTheOldRowsAndReportsTheError() = withApp { app, file, tabId ->
        assertTrue(file.delete())
        app.selectLogSection(tabId, L2CAP)
        rule.waitUntil(SWITCH_TIMEOUT_MS) { app.openError != null }
        rule.waitUntil(SWITCH_TIMEOUT_MS) { app.pendingLogSection.isEmpty() }
        val tab = app.tab(tabId)!!
        assertEquals(TabContentState.LOADED, tab.contentState)
        assertEquals(HCI, tab.selectedLogSectionId)
        assertEquals(listOf("hci one", "hci two"), tab.logData.map { it.msg })
        assertTrue(app.openError!!.message.contains("missing or unreadable"))
    }

    @Test
    fun closingTheTabCancelsAPendingSwitch() = withApp { app, _, tabId ->
        val gate = CountDownLatch(1)
        app.onLogSectionLoadStart = { _, _ -> gate.await(SWITCH_TIMEOUT_MS, TimeUnit.MILLISECONDS) }
        app.selectLogSection(tabId, L2CAP)
        rule.waitUntil(SWITCH_TIMEOUT_MS) { app.pendingLogSection[tabId] == L2CAP }
        app.closeTab(tabId)
        assertTrue(app.pendingLogSection.isEmpty())
        gate.countDown()
        Thread.sleep(SETTLE_MS)
        assertNull(app.tab(tabId))
    }
}
