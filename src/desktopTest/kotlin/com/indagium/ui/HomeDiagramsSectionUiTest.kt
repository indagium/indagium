package com.indagium.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.graphics.toPixelMap
import java.awt.image.BufferedImage
import javax.imageio.ImageIO
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextClearance
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.unit.dp
import com.indagium.diagram3.Seq3Document
import com.indagium.diagram3.Seq3Lifeline
import com.indagium.diagram3.Seq3Range
import com.indagium.model.AnnBlock
import com.indagium.model.HomeDiagramSegment
import com.indagium.model.HomeDiagramsView
import com.indagium.model.LogEntry
import com.indagium.model.LogLevel
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.io.path.createTempDirectory
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** The New tab's Diagrams section as a user drives it: segments, search, "+ New diagram", opening each kind of row, the row menu
 *  (Rename, Delete with its confirmation), the empty states and the Grid | List view with its previews. */
class HomeDiagramsSectionUiTest {
    @get:Rule
    val rule = createComposeRule()

    private val root = createTempDirectory("home-diagrams-ui").toFile()
    private val store = DiagramLibraryStore(File(root, "library.cache"))
    private val state = AppState(
        File(root, "state.cache"),
        notesDir = File(root, "notes"),
        diagramLibraryStore = store,
        restoreOnCreate = false,
    )
    private var reclaimed = 0
    private val loginSource = DiagramSourceIdentity.NONE
    private val ticketSource = DiagramSourceIdentity(File(root, "checkout.log").absolutePath, "2:feedface")

    @Before
    fun resetThumbnails() {
        HomeDiagramThumbnails.clearForTest()
    }

    @After
    fun tearDown() {
        HomeDiagramThumbnails.clearForTest()
        state.seq3Sessions.sessions.toList().forEach { state.seq3Sessions.close(it.id) }
        state.close()
        root.deleteRecursively()
    }

    private fun addDiagram(title: String, source: DiagramSourceIdentity, vararg participants: String): DiagramLibraryItem {
        val document = Seq3Document(
            title = title,
            range = Seq3Range.VisibleView,
            lifelines = participants.mapIndexed { index, name -> Seq3Lifeline("l$index", name, emptySet(), index) },
        )
        return store.create(title, "", source, DiagramLibrarySnapshot.create(document))
    }

    private fun show(launcherTabId: String? = null) {
        rule.setContent {
            CompositionLocalProvider(LocalTheme provides LIGHT_THEME) {
                Box(Modifier.size(520.dp, 640.dp)) {
                    HomeDiagramsSection(state, launcherTabId, onReclaimFocus = { reclaimed++ }, modifier = Modifier.fillMaxSize())
                }
            }
        }
        rule.waitForIdle()
    }

    private fun rowTag(id: String) = "home-diagrams-open-$id"

    private fun clickSegment(label: String) {
        rule.onNode(hasText(label) and hasAnyAncestor(hasTestTag("home-diagrams-segments"))).performClick()
        rule.waitForIdle()
    }

    private fun entries() = listOf(
        LogEntry(1, "10:00:00.000", LogLevel.I, "Checkout", "start", 7, 11),
        LogEntry(2, "10:00:00.050", LogLevel.I, "Gateway", "charge", 7, 11),
    )

    // ---- segments -------------------------------------------------------------------------------------------

    @Test
    fun theDefaultSegmentIsNoTicketAndListsOnlyDiagramsWithoutALog() {
        val login = addDiagram("Login flow", loginSource, "Client", "Auth server")
        val payment = addDiagram("Payment retry", ticketSource, "Checkout")
        show()

        assertEquals(HomeDiagramSegment.NO_TICKET, state.settings.homeDiagramsSegment)
        rule.onNodeWithTag(rowTag(login.id)).assertIsDisplayed()
        rule.onAllNodesWithTag(rowTag(payment.id)).assertCountEquals(0)
        rule.onNodeWithText("Client, Auth server", substring = true).assertExists()
        rule.onAllNodesWithTag("home-diagrams-ticket-${login.id}", useUnmergedTree = true).assertCountEquals(0)
    }

    @Test
    fun switchingSegmentsListsTheOthersAndTheChoiceIsRemembered() {
        val login = addDiagram("Login flow", loginSource, "Client")
        val payment = addDiagram("Payment retry", ticketSource, "Checkout")
        show()

        clickSegment("Ticket")
        assertEquals(HomeDiagramSegment.TICKET, state.settings.homeDiagramsSegment)
        rule.onNodeWithTag(rowTag(payment.id)).assertIsDisplayed()
        rule.onAllNodesWithTag(rowTag(login.id)).assertCountEquals(0)
        rule.onNodeWithTag("home-diagrams-ticket-${payment.id}", useUnmergedTree = true).assertIsDisplayed()
        rule.onNodeWithText("checkout.log", useUnmergedTree = true).assertExists()
        rule.onAllNodesWithTag("home-diagrams-no-ticket", useUnmergedTree = true).assertCountEquals(0)

        clickSegment("All")
        assertEquals(HomeDiagramSegment.ALL, state.settings.homeDiagramsSegment)
        rule.onNodeWithTag(rowTag(login.id)).assertIsDisplayed()
        rule.onNodeWithTag(rowTag(payment.id)).assertIsDisplayed()
        rule.onAllNodesWithTag("home-diagrams-no-ticket", useUnmergedTree = true).assertCountEquals(1) // only the log-free row is marked

        clickSegment("No ticket")
        assertEquals(HomeDiagramSegment.NO_TICKET, state.settings.homeDiagramsSegment)
        rule.onAllNodesWithTag(rowTag(payment.id)).assertCountEquals(0)
        assertTrue(reclaimed >= 3, "every segment click gave root focus back")
    }

    @Test
    fun aRememberedSegmentIsShownOnOpen() {
        val payment = addDiagram("Payment retry", ticketSource, "Checkout")
        state.updateSettings { it.copy(homeDiagramsSegment = HomeDiagramSegment.TICKET) }
        show()

        rule.onNodeWithTag(rowTag(payment.id)).assertIsDisplayed()
    }

    // ---- search ---------------------------------------------------------------------------------------------

    @Test
    fun searchNarrowsTheListAndANoMatchSaysSo() {
        val login = addDiagram("Login flow", loginSource, "Client", "Auth server")
        val sketch = addDiagram("Sketch", loginSource, "Cache")
        show()

        rule.onNodeWithTag("home-diagrams-search").performTextInput("auth")
        rule.waitForIdle()
        rule.onNodeWithTag(rowTag(login.id)).assertIsDisplayed()
        rule.onAllNodesWithTag(rowTag(sketch.id)).assertCountEquals(0)

        rule.onNodeWithTag("home-diagrams-search").performTextClearance()
        rule.onNodeWithTag("home-diagrams-search").performTextInput("zzz")
        rule.waitForIdle()
        rule.onNodeWithTag("home-diagrams-empty").assertIsDisplayed()
        rule.onNodeWithText("Nothing matches \"zzz\"").assertExists()
        rule.onAllNodesWithTag("home-diagrams-empty-new").assertCountEquals(0)

        rule.onNodeWithTag("home-diagrams-search").performTextClearance()
        rule.waitForIdle()
        rule.onNodeWithTag(rowTag(login.id)).assertIsDisplayed()
        rule.onNodeWithTag(rowTag(sketch.id)).assertIsDisplayed()
    }

    @Test
    fun searchMatchesTheTicketLabelOnlyWhereTheSegmentShowsTickets() {
        val payment = addDiagram("Payment retry", ticketSource, "Bank")
        state.updateSettings { it.copy(homeDiagramsSegment = HomeDiagramSegment.TICKET) }
        show()

        rule.onNodeWithTag("home-diagrams-search").performTextInput("checkout.log")
        rule.waitForIdle()
        rule.onNodeWithTag(rowTag(payment.id)).assertIsDisplayed()
    }

    // ---- empty states ---------------------------------------------------------------------------------------

    @Test
    fun everySegmentHasItsOwnEmptyStateAndNoTicketOffersANewDiagram() {
        show()

        rule.onNodeWithText("No diagrams without a ticket yet").assertIsDisplayed()
        rule.onNodeWithTag("home-diagrams-empty-new").assertIsDisplayed()

        clickSegment("Ticket")
        rule.onNodeWithTag("home-diagrams-empty").assertIsDisplayed()
        rule.onNodeWithText("No diagrams with a ticket yet", substring = true).assertExists()

        clickSegment("All")
        rule.onNodeWithText("No diagrams yet").assertIsDisplayed()
        rule.onNodeWithTag("home-diagrams-empty-new").assertIsDisplayed()
    }

    @Test
    fun theEmptyStateNewDiagramButtonCreatesOne() {
        show()

        rule.onNodeWithTag("home-diagrams-empty-new").performClick()
        rule.waitForIdle()

        val session = state.seq3Sessions.sessions.single()
        assertTrue(session.logFree)
    }

    // ---- + New diagram ----------------------------------------------------------------------------------------

    @Test
    fun newDiagramOpensALogFreeDiagramTabAndConsumesTheNewTab() {
        state.openHomeTab()
        val launcher = state.tabs.single { it.isCaptureLauncher }.id
        show(launcher)

        rule.onNodeWithTag("home-diagrams-new").performClick()
        rule.waitForIdle()

        val session = state.seq3Sessions.sessions.single()
        assertNull(session.sourceTabId)
        assertTrue(session.logFree)
        assertEquals(ActiveSurface.Diagram3(session.id), state.activeSurface)
        assertTrue(state.tabs.none { it.id == launcher }, "the New tab closed once the diagram took the screen")
        assertEquals(DiagramSourceIdentity.NONE, store.get(assertNotNull(session.libraryItemId))?.source)
        rule.onNodeWithTag(rowTag(assertNotNull(session.libraryItemId))).assertIsDisplayed()
        assertTrue(reclaimed >= 1)
    }

    // ---- opening rows -----------------------------------------------------------------------------------------

    @Test
    fun aLogFreeRowOpensItsOwnDiagramTab() {
        val login = addDiagram("Login flow", loginSource, "Client")
        show()

        rule.onNodeWithTag(rowTag(login.id)).performClick()
        rule.waitForIdle()

        val session = state.seq3Sessions.sessions.single()
        assertEquals(login.id, session.libraryItemId)
        assertNull(session.sourceTabId)
        assertEquals(ActiveSurface.Diagram3(session.id), state.activeSurface)
        assertNull(state.pendingDiagramNotice)
    }

    @Test
    fun aTicketRowWithItsLogOpenOpensAgainstThatTab() {
        val logFile = File(root, "checkout.log").apply { writeText("fixture") }
        val tab = mkTab("log", "checkout.log", entries()).copy(sourcePath = logFile.absolutePath)
        state.tabs = listOf(tab)
        state.activateTab(tab.id)
        val sessionId = assertNotNull(state.seq3Sessions.beginEmpty(tab.id))
        val itemId = assertNotNull(state.seq3Sessions.sessions.single().libraryItemId)
        state.seq3Sessions.close(sessionId)
        state.updateSettings { it.copy(homeDiagramsSegment = HomeDiagramSegment.TICKET) }
        show()

        rule.onNodeWithTag(rowTag(itemId)).performClick()
        rule.waitUntil(10_000) { state.seq3Sessions.sessions.isNotEmpty() }
        rule.waitForIdle()

        val session = state.seq3Sessions.sessions.single()
        assertEquals(itemId, session.libraryItemId)
        assertEquals(tab.id, session.sourceTabId)
        assertEquals(ActiveSurface.Diagram3(session.id), state.activeSurface)
        assertNull(state.pendingDiagramNotice)
    }

    @Test
    fun aTicketRowWhoseLogIsGoneOpensTheSavedDiagramAndSaysTheLogWasNotFound() {
        val orphan = addDiagram("Payment retry", ticketSource, "Checkout")
        state.updateSettings { it.copy(homeDiagramsSegment = HomeDiagramSegment.ALL) }
        show()

        rule.onNodeWithTag(rowTag(orphan.id)).performClick()
        rule.waitUntil(10_000) { state.seq3Sessions.sessions.isNotEmpty() && state.pendingDiagramNotice != null }
        rule.waitForIdle()

        val session = state.seq3Sessions.sessions.single()
        assertEquals(orphan.id, session.libraryItemId)
        assertNull(session.sourceTabId)
        assertEquals("Source log not found", assertNotNull(state.pendingDiagramNotice).title)
        assertEquals(ticketSource, store.get(orphan.id)?.source, "the saved diagram keeps its ticket")
    }

    // ---- row menu: rename ---------------------------------------------------------------------------------------

    private fun openMenu(id: String) {
        rule.onNodeWithTag("home-diagrams-menu-$id").performClick()
        rule.waitForIdle()
    }

    @Test
    fun renameThroughTheRowMenuChangesTheTitleAndKeepsEverythingElse() {
        val login = addDiagram("Login flow", loginSource, "Client")
        show()

        openMenu(login.id)
        rule.onNodeWithTag("home-diagrams-rename-${login.id}").performClick()
        rule.waitForIdle()
        val afterMenu = reclaimed
        rule.onNodeWithTag("profile-name-field").performTextClearance()
        rule.onNodeWithTag("profile-name-field").performTextInput("Sign-in flow")
        rule.onNodeWithText("Rename").performClick()
        rule.waitForIdle()

        val renamed = assertNotNull(store.get(login.id))
        assertEquals("Sign-in flow", renamed.title)
        assertEquals("Sign-in flow", renamed.parsed?.document?.title)
        assertEquals(listOf("Client"), renamed.parsed?.document?.lifelines?.map { it.name })
        rule.onNodeWithText("Sign-in flow").assertIsDisplayed()
        rule.onAllNodesWithText("Login flow").assertCountEquals(0)
        assertTrue(reclaimed > afterMenu, "root focus came back once the dialog closed")
        assertNull(state.pendingDiagramNotice)
    }

    @Test
    fun cancellingTheRenameChangesNothingAndGivesFocusBack() {
        val login = addDiagram("Login flow", loginSource, "Client")
        show()

        openMenu(login.id)
        val beforeMenuAction = reclaimed
        rule.onNodeWithTag("home-diagrams-rename-${login.id}").performClick()
        rule.waitForIdle()
        assertTrue(reclaimed > beforeMenuAction, "the menu choice gave focus back")
        val beforeCancel = reclaimed
        rule.onNodeWithTag("profile-name-field").performTextClearance()
        rule.onNodeWithTag("profile-name-field").performTextInput("Something else")
        rule.onNodeWithText("Cancel").performClick()
        rule.waitForIdle()

        assertEquals("Login flow", store.get(login.id)?.title)
        assertTrue(reclaimed > beforeCancel, "root focus came back once the dialog closed")
        rule.onAllNodesWithTag("profile-name-field").assertCountEquals(0)
    }

    @Test
    fun aNameAnotherDiagramOfTheSameTicketHasIsNotAccepted() {
        val login = addDiagram("Login flow", loginSource, "Client")
        addDiagram("Sketch", loginSource, "Cache")
        show()

        openMenu(login.id)
        rule.onNodeWithTag("home-diagrams-rename-${login.id}").performClick()
        rule.waitForIdle()
        rule.onNodeWithTag("profile-name-field").performTextClearance()
        rule.onNodeWithTag("profile-name-field").performTextInput("Sketch")
        rule.waitForIdle()

        rule.onNodeWithText("A diagram with this name already exists.").assertExists()
        rule.onNodeWithText("Rename").performClick()
        rule.waitForIdle()
        assertEquals("Login flow", store.get(login.id)?.title)
    }

    // ---- row menu: delete ---------------------------------------------------------------------------------------

    @Test
    fun deleteAsksFirstAndCancellingKeepsTheDiagram() {
        val login = addDiagram("Login flow", loginSource, "Client")
        show()

        openMenu(login.id)
        rule.onNodeWithTag("home-diagrams-delete-${login.id}").performClick()
        rule.waitForIdle()
        rule.onNodeWithText("Delete saved diagram?").assertIsDisplayed()
        assertNotNull(store.get(login.id), "nothing is deleted before the confirmation")
        val beforeCancel = reclaimed
        rule.onNodeWithText("Cancel").performClick()
        rule.waitForIdle()

        assertNotNull(store.get(login.id))
        rule.onNodeWithTag(rowTag(login.id)).assertIsDisplayed()
        rule.onAllNodesWithText("Delete saved diagram?").assertCountEquals(0)
        assertTrue(reclaimed > beforeCancel, "root focus came back once the dialog closed")
    }

    @Test
    fun confirmingDeleteRemovesOnlyTheLibraryRecordAndNeverALogNote() {
        val logFile = File(root, "checkout.log").apply { writeText("fixture") }
        val note = AnnBlock.Note("n1", "Seen on the checkout path")
        val tab = mkTab("log", "checkout.log", entries()).let {
            it.copy(sourcePath = logFile.absolutePath, annotations = it.annotations.copy(blocks = listOf(note)))
        }
        state.tabs = listOf(tab)
        state.activateTab(tab.id)
        val keep = addDiagram("Sketch", loginSource, "Cache")
        val doomed = addDiagram("Login flow", loginSource, "Client")
        val notesBefore = state.tab(tab.id)?.annotations
        show()

        openMenu(doomed.id)
        rule.onNodeWithTag("home-diagrams-delete-${doomed.id}").performClick()
        rule.waitForIdle()
        rule.onNodeWithText("Delete").performClick()
        rule.waitForIdle()

        assertNull(store.get(doomed.id))
        assertNotNull(store.get(keep.id))
        rule.onAllNodesWithTag(rowTag(doomed.id)).assertCountEquals(0)
        rule.onNodeWithTag(rowTag(keep.id)).assertIsDisplayed()
        assertEquals(notesBefore, state.tab(tab.id)?.annotations, "log notes are not touched")
        assertEquals(listOf(note), state.tab(tab.id)?.annotations?.blocks)
    }

    @Test
    fun deletingADiagramThatIsOpenClosesItsTabAndSaysSoInTheDialog() {
        val sessionId = assertNotNull(state.seq3Sessions.beginStandalone())
        val itemId = assertNotNull(state.seq3Sessions.sessions.single().libraryItemId)
        show()

        openMenu(itemId)
        rule.onNodeWithTag("home-diagrams-delete-$itemId").performClick()
        rule.waitForIdle()
        rule.onNodeWithText("Its open Diagram tab will be closed.", substring = true).assertExists()
        rule.onNodeWithText("Delete").performClick()
        rule.waitForIdle()

        assertNull(store.get(itemId))
        assertTrue(state.seq3Sessions.sessions.none { it.id == sessionId }, "the workspace cannot outlive its record")
    }

    @Test
    fun theAllSegmentRendersEvidence() {
        addDiagram("Login flow", loginSource, "Client", "Auth server", "Cache", "Audit log")
        addDiagram("Payment retry with a very long title that has to be cut off at the edge of the card", ticketSource, "Checkout", "Gateway")
        addDiagram("Crash trace", DiagramSourceIdentity(File(root, "BUG-42.zip").absolutePath + "!FS/logs/logcat.log", "1:a"), "App")
        state.updateSettings { it.copy(homeDiagramsSegment = HomeDiagramSegment.ALL) }
        show()
        val image = rule.onRoot().captureToImage()
        val pixels = image.toPixelMap()
        val rendered = BufferedImage(image.width, image.height, BufferedImage.TYPE_INT_ARGB)
        for (y in 0 until image.height) for (x in 0 until image.width) rendered.setRGB(x, y, pixels[x, y].toArgb())
        val directory = File("build/home-layout-qa").apply { mkdirs() }
        assertTrue(ImageIO.write(rendered, "png", File(directory, "home-diagrams-all.png")))
    }

    @Test
    fun aRowMenuHasRenameAndDeleteAndClosesWithoutActing() {
        val login = addDiagram("Login flow", loginSource, "Client")
        show()

        openMenu(login.id)
        rule.onNodeWithTag("home-diagrams-menu").assertIsDisplayed()
        rule.onNodeWithText("Rename…").assertExists()
        rule.onNodeWithText("Delete…").assertExists()
        // Opening the menu again toggles it shut; nothing was changed.
        rule.onNodeWithTag("home-diagrams-menu-${login.id}").performClick()
        rule.waitForIdle()
        assertEquals("Login flow", store.get(login.id)?.title)
        assertEquals(1, store.all().size)
    }

    // ---- Grid | List ------------------------------------------------------------------------------------------

    private fun clickView(label: String) {
        rule.onNode(hasText(label) and hasAnyAncestor(hasTestTag("home-diagrams-view"))).performClick()
        rule.waitForIdle()
    }

    private fun waitForThumb(id: String) {
        rule.waitUntil(10_000) { rule.onAllNodesWithTag("home-diagrams-thumb-$id", useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty() }
    }

    @Test
    fun listIsTheDefaultViewAndNeverDrawsAPreview() {
        val login = addDiagram("Login flow", loginSource, "Client", "Auth server")
        show()

        assertEquals(HomeDiagramsView.LIST, state.settings.homeDiagramsView)
        rule.onNodeWithTag("home-diagrams-view").assertIsDisplayed()
        rule.onNodeWithTag("home-diagrams-list").assertIsDisplayed()
        rule.onAllNodesWithTag("home-diagrams-grid").assertCountEquals(0)
        rule.onAllNodesWithTag("home-diagrams-thumb-${login.id}", useUnmergedTree = true).assertCountEquals(0)
        rule.onAllNodesWithTag("home-diagrams-thumb-placeholder-${login.id}", useUnmergedTree = true).assertCountEquals(0)
        Thread.sleep(200)
        assertEquals(0, HomeDiagramThumbnails.renderCount, "List mode draws no previews")
    }

    @Test
    fun theGridListSwitchIsRememberedAndSwapsTheLayout() {
        val login = addDiagram("Login flow", loginSource, "Client", "Auth server")
        show()

        clickView("Grid")
        assertEquals(HomeDiagramsView.GRID, state.settings.homeDiagramsView)
        rule.onNodeWithTag("home-diagrams-grid").assertIsDisplayed()
        rule.onAllNodesWithTag("home-diagrams-list").assertCountEquals(0)
        rule.onNodeWithTag("home-diagrams-row-${login.id}").assertIsDisplayed()

        clickView("List")
        assertEquals(HomeDiagramsView.LIST, state.settings.homeDiagramsView)
        rule.onNodeWithTag("home-diagrams-list").assertIsDisplayed()
        rule.onAllNodesWithTag("home-diagrams-grid").assertCountEquals(0)
        assertTrue(reclaimed >= 2, "every switch click gave root focus back")
    }

    @Test
    fun aRememberedGridViewIsShownOnOpen() {
        val login = addDiagram("Login flow", loginSource, "Client")
        state.updateSettings { it.copy(homeDiagramsView = HomeDiagramsView.GRID) }
        show()

        rule.onNodeWithTag("home-diagrams-grid").assertIsDisplayed()
        rule.onNodeWithTag("home-diagrams-open-${login.id}").assertIsDisplayed()
    }

    @Test
    fun aGridCardSaysTitleParticipantsUpdatedTimeAndTicket() {
        val login = addDiagram("Login flow", loginSource, "Client", "Auth server")
        val payment = addDiagram("Payment retry", ticketSource, "Checkout")
        state.updateSettings { it.copy(homeDiagramsView = HomeDiagramsView.GRID, homeDiagramsSegment = HomeDiagramSegment.ALL) }
        show()

        rule.onNodeWithText("Login flow").assertIsDisplayed()
        rule.onNodeWithText("Client, Auth server").assertExists()
        rule.onAllNodesWithText("Updated", substring = true).assertCountEquals(2)
        rule.onAllNodesWithTag("home-diagrams-no-ticket", useUnmergedTree = true).assertCountEquals(1)
        rule.onNodeWithTag("home-diagrams-ticket-${payment.id}", useUnmergedTree = true).assertIsDisplayed()
        rule.onNodeWithText("checkout.log", useUnmergedTree = true).assertExists()
        rule.onAllNodesWithTag("home-diagrams-ticket-${login.id}", useUnmergedTree = true).assertCountEquals(0)
    }

    @Test
    fun aGridCardShowsAPlaceholderThenTheDrawnPreview() {
        val login = addDiagram("Login flow", loginSource, "Client", "Auth server")
        val release = CountDownLatch(1)
        HomeDiagramThumbnails.beforeRenderForTest = { release.await(10, TimeUnit.SECONDS) }
        state.updateSettings { it.copy(homeDiagramsView = HomeDiagramsView.GRID) }
        show()

        rule.onNodeWithTag("home-diagrams-thumb-placeholder-${login.id}", useUnmergedTree = true).assertIsDisplayed()
        rule.onAllNodesWithTag("home-diagrams-thumb-${login.id}", useUnmergedTree = true).assertCountEquals(0)
        assertEquals(0, HomeDiagramThumbnails.renderCount)

        release.countDown()
        waitForThumb(login.id)
        rule.onNodeWithTag("home-diagrams-thumb-${login.id}", useUnmergedTree = true).assertIsDisplayed()
        rule.onAllNodesWithTag("home-diagrams-thumb-placeholder-${login.id}", useUnmergedTree = true).assertCountEquals(0)
        assertEquals(1, HomeDiagramThumbnails.renderCount)
    }

    @Test
    fun aDiagramThatCannotBeDrawnKeepsThePlaceholder() {
        val sketch = addDiagram("Empty sketch", loginSource)
        state.updateSettings { it.copy(homeDiagramsView = HomeDiagramsView.GRID) }
        show()
        Thread.sleep(300)
        rule.waitForIdle()

        rule.onNodeWithTag("home-diagrams-thumb-placeholder-${sketch.id}", useUnmergedTree = true).assertIsDisplayed()
        rule.onAllNodesWithTag("home-diagrams-thumb-${sketch.id}", useUnmergedTree = true).assertCountEquals(0)
    }

    @Test
    fun aDrawnPreviewIsReusedWhenTheViewIsToggledBack() {
        val login = addDiagram("Login flow", loginSource, "Client", "Auth server")
        show()

        clickView("Grid")
        waitForThumb(login.id)
        val drawn = HomeDiagramThumbnails.renderCount
        clickView("List")
        clickView("Grid")
        rule.onNodeWithTag("home-diagrams-thumb-${login.id}", useUnmergedTree = true).assertIsDisplayed()
        assertEquals(drawn, HomeDiagramThumbnails.renderCount, "the cached picture shows at once, nothing is drawn again")
    }

    @Test
    fun switchingBackToListStopsDrawing() {
        addDiagram("Login flow", loginSource, "Client")
        addDiagram("Sketch", loginSource, "Cache")
        state.updateSettings { it.copy(homeDiagramsView = HomeDiagramsView.GRID) }
        show()
        rule.waitUntil(10_000) { HomeDiagramThumbnails.renderCount == 2 }

        clickView("List")
        addDiagram("Third", loginSource, "Bus")
        rule.waitForIdle()
        Thread.sleep(200)
        assertEquals(2, HomeDiagramThumbnails.renderCount, "a list row never triggers a draw")
    }

    @Test
    fun clickingAGridCardOpensItLikeARow() {
        val login = addDiagram("Login flow", loginSource, "Client")
        state.updateSettings { it.copy(homeDiagramsView = HomeDiagramsView.GRID) }
        show()

        rule.onNodeWithTag(rowTag(login.id)).performClick()
        rule.waitForIdle()

        val session = state.seq3Sessions.sessions.single()
        assertEquals(login.id, session.libraryItemId)
        assertNull(session.sourceTabId)
        assertEquals(ActiveSurface.Diagram3(session.id), state.activeSurface)
        assertTrue(reclaimed >= 1)
    }

    @Test
    fun aGridCardMenuRenamesAndDeletes() {
        val login = addDiagram("Login flow", loginSource, "Client")
        val keep = addDiagram("Sketch", loginSource, "Cache")
        state.updateSettings { it.copy(homeDiagramsView = HomeDiagramsView.GRID) }
        show()

        openMenu(login.id)
        rule.onNodeWithTag("home-diagrams-menu").assertIsDisplayed()
        rule.onNodeWithTag("home-diagrams-rename-${login.id}").performClick()
        rule.waitForIdle()
        rule.onNodeWithTag("profile-name-field").performTextClearance()
        rule.onNodeWithTag("profile-name-field").performTextInput("Sign-in flow")
        rule.onNodeWithText("Rename").performClick()
        rule.waitForIdle()
        assertEquals("Sign-in flow", store.get(login.id)?.title)
        rule.onNodeWithText("Sign-in flow").assertIsDisplayed()

        openMenu(login.id)
        rule.onNodeWithTag("home-diagrams-delete-${login.id}").performClick()
        rule.waitForIdle()
        rule.onNodeWithText("Delete saved diagram?").assertIsDisplayed()
        rule.onNodeWithText("Delete").performClick()
        rule.waitForIdle()

        assertNull(store.get(login.id))
        assertNotNull(store.get(keep.id))
        rule.onAllNodesWithTag(rowTag(login.id)).assertCountEquals(0)
        rule.onNodeWithTag(rowTag(keep.id)).assertIsDisplayed()
        assertNull(HomeDiagramThumbnails.cache.keys().firstOrNull { it.id == login.id }, "a deleted diagram's preview is dropped")
    }

    @Test
    fun searchAndEmptyStatesWorkInTheGridToo() {
        val login = addDiagram("Login flow", loginSource, "Client", "Auth server")
        val sketch = addDiagram("Sketch", loginSource, "Cache")
        state.updateSettings { it.copy(homeDiagramsView = HomeDiagramsView.GRID) }
        show()

        rule.onNodeWithTag("home-diagrams-search").performTextInput("auth")
        rule.waitForIdle()
        rule.onNodeWithTag(rowTag(login.id)).assertIsDisplayed()
        rule.onAllNodesWithTag(rowTag(sketch.id)).assertCountEquals(0)

        rule.onNodeWithTag("home-diagrams-search").performTextClearance()
        rule.onNodeWithTag("home-diagrams-search").performTextInput("zzz")
        rule.waitForIdle()
        rule.onNodeWithText("Nothing matches \"zzz\"").assertExists()
        rule.onAllNodesWithTag("home-diagrams-grid").assertCountEquals(0)

        rule.onNodeWithTag("home-diagrams-search").performTextClearance()
        clickSegment("Ticket")
        rule.onNodeWithText("No diagrams with a ticket yet", substring = true).assertExists()
        clickSegment("All")
        rule.onNodeWithTag(rowTag(login.id)).assertIsDisplayed()
        assertEquals(HomeDiagramsView.GRID, state.settings.homeDiagramsView)
    }

    @Test
    fun theGridRendersEvidence() {
        val ids = mutableListOf(addDiagram("Login flow", loginSource, "Client", "Auth server", "Cache").id)
        ids += addDiagram("Payment retry with a very long title that has to be cut off at the edge of the card", ticketSource, "Checkout", "Gateway").id
        ids += addDiagram("Crash trace", DiagramSourceIdentity(File(root, "BUG-42.zip").absolutePath + "!FS/logs/logcat.log", "1:a"), "App").id
        state.updateSettings { it.copy(homeDiagramsSegment = HomeDiagramSegment.ALL, homeDiagramsView = HomeDiagramsView.GRID) }
        show()
        ids.forEach(::waitForThumb)
        rule.waitForIdle()
        val image = rule.onRoot().captureToImage()
        val pixels = image.toPixelMap()
        val rendered = BufferedImage(image.width, image.height, BufferedImage.TYPE_INT_ARGB)
        for (y in 0 until image.height) for (x in 0 until image.width) rendered.setRGB(x, y, pixels[x, y].toArgb())
        val directory = File("build/home-layout-qa").apply { mkdirs() }
        assertTrue(ImageIO.write(rendered, "png", File(directory, "home-diagrams-grid.png")))
    }
}
