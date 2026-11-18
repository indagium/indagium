package com.indagium

import com.indagium.diagram3.Seq3Dialect
import com.indagium.diagram3.Seq3Document
import com.indagium.diagram3.encodeSeq3Note
import com.indagium.model.AddAnnRequest
import com.indagium.model.AnnBlock
import com.indagium.model.LogEntry
import com.indagium.model.LogFileSection
import com.indagium.model.LogLevel
import com.indagium.ui.AppState
import com.indagium.ui.createSharedNote
import com.indagium.ui.deleteSharedNote
import com.indagium.ui.logRefSourceLabel
import com.indagium.ui.mkTab
import com.indagium.ui.notesTargetFor
import com.indagium.ui.setNotesView
import com.indagium.ui.shownNoteOf
import com.indagium.utils.buildAnnotationsHtml
import com.indagium.utils.buildMd
import java.io.File
import kotlin.io.path.createTempDirectory
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

/**
 * Phase 3 of shared notes, part 1: every entry point that adds or edits note content on behalf of "the tab the user is in"
 * goes through [notesTargetFor], so a tab whose Notes tile shows a shared note writes into it. Row references from a member
 * become cross-file references to their tab (and section) with frozen rows; a tab that shows its own note is unchanged.
 */
class SharedNoteRoutingTest {
    private fun entry(id: Int, msg: String = "line $id", tag: String = "App") =
        LogEntry(id, "10:00:%02d.000".format(id), LogLevel.I, tag, msg)

    private val sections = listOf(
        LogFileSection("Tethered Bluetooth / HCI", "HCI", "Tethered Bluetooth", 3),
        LogFileSection("Tethered Bluetooth / L2CAP", "L2CAP", "Tethered Bluetooth", 3),
    )

    private fun state(dir: File): AppState =
        AppState(File(dir, "state.cache"), notesDir = File(dir, "notes")).also { s ->
            s.updateSettings { it.copy(autoExportNotes = false) }
            s.tabs = listOf(
                // "a": a logcat-like tab, "j": a metadata-JSON-like tab showing its HCI section, "c": a tab that stays on its own note.
                mkTab("a", "a.log", (1..4).map { entry(it, "a says $it") }).copy(sourcePath = File(dir, "a.log").absolutePath),
                mkTab("j", "Untitled.json", (1..3).map { entry(it, "hci says $it", "HCI") }).copy(
                    sourcePath = File(dir, "Untitled.json").absolutePath,
                    logSections = sections,
                    selectedLogSectionId = "Tethered Bluetooth / HCI",
                ),
                mkTab("c", "c.log", (1..2).map { entry(it, "c says $it") }),
            )
            s.activeTabId = "a"
        }

    private fun AppState.addRows(source: String, vararg ids: Int, caption: String = "rows") {
        requestAddAnn(source, ids.toList())
        val request = assertNotNull(addAnnRequest)
        confirmAddAnn(request, caption, request.capture?.presentation?.options ?: AnnBlock.LogRefPresentationOptions())
    }

    private fun AppState.refs(noteId: String) =
        assertNotNull(noteTab(noteId)).annotations.blocks.filterIsInstance<AnnBlock.LogRef>()

    // ── The resolver ─────────────────────────────────────────────────────────────────────────────

    @Test
    fun theResolverNamesTheShownSharedNoteElseTheTabItself() {
        val dir = createTempDirectory("shared-routing-resolver").toFile()
        val app = state(dir)
        try {
            assertEquals("a", app.notesTargetFor("a"))
            assertEquals("unknown", app.notesTargetFor("unknown"))
            val host = assertNotNull(app.createSharedNote("S", listOf("a", "j")))

            assertEquals(host, app.notesTargetFor("a"))
            assertEquals(host, app.notesTargetFor("j"))
            assertEquals("c", app.notesTargetFor("c"))
            assertSame(app.sharedNoteHost(host), app.shownNoteOf(app.tab("a")!!))
            assertSame(app.tab("c"), app.shownNoteOf(app.tab("c")!!), "a tab with its own note is returned as it is")

            assertTrue(app.setNotesView("a", null))
            assertEquals("a", app.notesTargetFor("a"))
            // A tab whose note vanished from under it falls back to its own, even before notesView is cleared.
            app.setNotesView("a", host)
            app.upTab("a") { it.copy(notesView = "shared-gone") }
            assertEquals("a", app.notesTargetFor("a"))
            app.upTab("a") { it.copy(notesView = host) }
            app.deleteSharedNote(host)
            assertEquals("a", app.notesTargetFor("a"))
        } finally {
            app.close()
            dir.deleteRecursively()
        }
    }

    // ── Row references ───────────────────────────────────────────────────────────────────────────

    @Test
    fun linesAddedFromTwoMemberTabsLandInOneHostAsCrossFileReferences() {
        val dir = createTempDirectory("shared-routing-two-members").toFile()
        val app = state(dir)
        try {
            val host = assertNotNull(app.createSharedNote("S", listOf("a", "j")))
            val ownBefore = app.tab("a")!!.annotations

            app.requestAddAnn("a", listOf(2, 3))
            val request = assertNotNull(app.addAnnRequest)
            assertEquals(host, request.targetTabId)
            assertEquals("a", request.sourceTabId)
            assertEquals("a.log", request.sourceFilename)
            assertNull(request.sourceSectionId, "a log without sections has no section")
            app.confirmAddAnn(request, "from logcat", AnnBlock.LogRefPresentationOptions())
            assertNull(app.addAnnRequest)

            app.addRows("j", 1, 2, caption = "from json")

            val refs = app.refs(host)
            assertEquals(2, refs.size)
            val (fromA, fromJ) = refs
            assertEquals("a", fromA.sourceTabId)
            assertEquals("a.log", fromA.sourceFilename)
            assertNull(fromA.sourceSectionId)
            assertEquals(listOf(2, 3), fromA.logIds)
            assertEquals(listOf("a says 2", "a says 3"), fromA.sourceEntries?.map { it.msg }, "the rows are frozen into the reference")
            assertEquals("j", fromJ.sourceTabId)
            assertEquals("Untitled.json", fromJ.sourceFilename)
            assertEquals("Tethered Bluetooth / HCI", fromJ.sourceSectionId)
            assertEquals(listOf("hci says 1", "hci says 2"), fromJ.sourceEntries?.map { it.msg })
            assertEquals(ownBefore, app.tab("a")!!.annotations, "the member's own note is untouched")
            assertTrue(app.tab("j")!!.annotations.blocks.isEmpty())
            assertTrue(app.tabs.none { it.id == host })
        } finally {
            app.close()
            dir.deleteRecursively()
        }
    }

    @Test
    fun aTabThatShowsItsOwnNoteStillWritesItsOwnNote() {
        val dir = createTempDirectory("shared-routing-own").toFile()
        val app = state(dir)
        try {
            val host = assertNotNull(app.createSharedNote("S", listOf("a")))

            app.requestAddAnn("c", listOf(1))
            val request = assertNotNull(app.addAnnRequest)
            assertEquals("c", request.targetTabId)
            assertNull(request.sourceFilename)
            app.confirmAddAnn(request, "mine", AnnBlock.LogRefPresentationOptions())

            val own = app.refs("c").single()
            assertNull(own.sourceTabId, "not a cross-file reference")
            assertNull(own.sourceFilename)
            assertTrue(app.refs(host).isEmpty())
        } finally {
            app.close()
            dir.deleteRecursively()
        }
    }

    @Test
    fun anOwnNoteReferenceFromASectionedTabRemembersTheSection() {
        val dir = createTempDirectory("shared-routing-own-section").toFile()
        val app = state(dir)
        try {
            app.addRows("j", 1)

            val ref = app.refs("j").single()
            assertNull(ref.sourceTabId)
            assertEquals("Tethered Bluetooth / HCI", ref.sourceSectionId)
            assertNull(app.logRefSourceLabel(ref), "rows of the note's own tab have no source badge, as before")
        } finally {
            app.close()
            dir.deleteRecursively()
        }
    }

    @Test
    fun compareModeSendsTheOtherPanesLinesToTheActiveTabsNote() {
        val dir = createTempDirectory("shared-routing-compare").toFile()
        val app = state(dir)
        try {
            val host = assertNotNull(app.createSharedNote("S", listOf("a")))
            app.activeTabId = "a"
            app.compareTabId = "c"
            app.compareMode = true

            // The other pane (c) is the source; the active tab (a) shows the shared note, so they go into it.
            app.requestAddAnn("c", listOf(1))
            assertEquals(host, assertNotNull(app.addAnnRequest).targetTabId)
            app.confirmAddAnn(assertNotNull(app.addAnnRequest), "to shared", AnnBlock.LogRefPresentationOptions())
            val ref = app.refs(host).single()
            assertEquals("c", ref.sourceTabId)
            assertEquals("c.log", ref.sourceFilename)

            // The same pane as the source: the active tab's own view still decides.
            app.requestAddAnn("a", listOf(1))
            assertEquals(host, assertNotNull(app.addAnnRequest).targetTabId)
            app.addAnnRequest = null

            // With the active tab on its own note, the old compare rule stands: the active tab's own note.
            app.setNotesView("a", null)
            app.requestAddAnn("c", listOf(2))
            val request: AddAnnRequest = assertNotNull(app.addAnnRequest)
            assertEquals("a", request.targetTabId)
            assertEquals("c.log", request.sourceFilename)
            app.confirmAddAnn(request, "to a", AnnBlock.LogRefPresentationOptions())
            assertEquals("c", app.refs("a").single().sourceTabId)
            assertEquals(1, app.refs(host).size)
        } finally {
            app.close()
            dir.deleteRecursively()
        }
    }

    @Test
    fun theSourceLabelAddsTheSectionTitleAndExportsPrintIt() {
        val dir = createTempDirectory("shared-routing-label").toFile()
        val app = state(dir)
        try {
            val host = assertNotNull(app.createSharedNote("S", listOf("a", "j")))
            app.addRows("a", 1)
            app.addRows("j", 1)
            val (plain, sectioned) = app.refs(host)

            assertEquals("a.log", app.logRefSourceLabel(plain))
            assertEquals("Untitled.json · HCI", app.logRefSourceLabel(sectioned), "the title comes from the open source tab")
            // Once the source tab is gone the id's last " / " segment stands in.
            app.closeTab("j")
            assertEquals("Untitled.json · HCI", app.logRefSourceLabel(app.refs(host).last()))
            val orphan = sectioned.copy(sourceFilename = "x.json", sourceSectionId = "Group / Sub / Deep")
            assertEquals("x.json · Deep", app.logRefSourceLabel(orphan))

            val markdown = buildMd(assertNotNull(app.noteTab(host)), app.settings)
            assertTrue(markdown.contains("From a.log\n"), markdown)
            assertTrue(markdown.contains("From Untitled.json · HCI\n"), markdown)
            assertFalse(markdown.contains("From Untitled.json\n"), markdown)
            val html = buildAnnotationsHtml(assertNotNull(app.noteTab(host)), app.settings)
            assertTrue(html.contains("From Untitled.json · HCI"), html)
        } finally {
            app.close()
            dir.deleteRecursively()
        }
    }

    @Test
    fun theMarkdownExportOfANoteWithASourceTabUsesTheNotesOwnSectionTitles() {
        val dir = createTempDirectory("shared-routing-md-own").toFile()
        val app = state(dir)
        try {
            // A reference that names a source file (a compare source) in a tab that has the section list itself.
            val ref = AnnBlock.LogRef(
                id = "r1", logIds = listOf(1), caption = "rows", sourceFilename = "Untitled.json",
                sourceEntries = listOf(entry(1)), sourceSectionId = "Tethered Bluetooth / L2CAP",
            )
            app.upAnn("j") { it.copy(annotations = it.annotations.copy(blocks = listOf(ref))) }

            val markdown = buildMd(assertNotNull(app.tab("j")), app.settings)
            assertTrue(markdown.contains("From Untitled.json · L2CAP\n"), markdown)
        } finally {
            app.close()
            dir.deleteRecursively()
        }
    }

    // ── Text, image and other blocks ─────────────────────────────────────────────────────────────

    @Test
    fun textImageAndVideoFrameEntryPointsWriteIntoTheShownNote() {
        val dir = createTempDirectory("shared-routing-blocks").toFile()
        val app = state(dir)
        try {
            val host = assertNotNull(app.createSharedNote("S", listOf("a")))
            val png = pngBytes()

            // These are the calls the Notes tile, the global paste and the video panel make: through the resolver.
            val noteId = assertNotNull(app.addNoteBlock(app.notesTargetFor("a"), "a finding"))
            assertNotNull(app.addImageBlock(app.notesTargetFor("a"), png, "pasted from clipboard", noteId))
            app.setPrefix(app.notesTargetFor("a"), "prefix of the shared note")
            app.setIssueDescription(app.notesTargetFor("a"), "the issue")
            app.setSuffix(app.notesTargetFor("a"), "next steps")

            val shared = assertNotNull(app.noteTab(host)).annotations
            assertEquals(listOf("a finding"), shared.blocks.filterIsInstance<AnnBlock.Note>().map { it.text })
            assertEquals(1, shared.blocks.filterIsInstance<AnnBlock.Image>().size)
            assertEquals("prefix of the shared note", shared.prefix)
            assertEquals("the issue", shared.issueDescription)
            assertEquals("next steps", shared.suffix)
            val own = app.tab("a")!!.annotations
            assertTrue(own.blocks.isEmpty())
            assertNotEquals("prefix of the shared note", own.prefix)

            app.setNotesView("a", null)
            assertEquals("a", app.notesTargetFor("a"))
            app.addNoteBlock(app.notesTargetFor("a"), "private again")
            assertEquals(listOf("private again"), app.tab("a")!!.annotations.blocks.filterIsInstance<AnnBlock.Note>().map { it.text })
        } finally {
            app.close()
            dir.deleteRecursively()
        }
    }

    @Test
    fun theCaseLibraryIsSeededFromTheShownNotesIssueDescription() {
        val dir = createTempDirectory("shared-routing-case").toFile()
        val app = state(dir)
        try {
            val host = assertNotNull(app.createSharedNote("S", listOf("a")))
            app.setIssueDescription(host, "pairing drops after sleep")
            app.setIssueDescription("a", "my private issue")

            app.openCaseLibrary("a")
            assertEquals("pairing drops after sleep", app.caseLibraryQuery)
            assertEquals("a", app.caseLibraryTabId, "the tab stays the dialog's origin")
            app.closeCaseLibrary()

            app.openCaseLibrary("c")
            assertEquals("", app.caseLibraryQuery)
            app.closeCaseLibrary()
        } finally {
            app.close()
            dir.deleteRecursively()
        }
    }

    @Test
    fun aDiagramNoteInASharedNoteCanBeOpenedForEditingAgainstTheNote() {
        val dir = createTempDirectory("shared-routing-diagram").toFile()
        val app = state(dir)
        try {
            val host = assertNotNull(app.createSharedNote("S", listOf("a")))
            val text = encodeSeq3Note(Seq3Document(title = "Pairing flow"), Seq3Dialect.PLANTUML)
            app.addNoteBlock(host, text)
            val blockId = assertNotNull(app.noteTab(host)).annotations.blocks.single().id

            // The Notes tile's Edit goes to the note that holds the block; the member's own note has none.
            assertNull(app.seq3Sessions.beginEdit("a", blockId))
            val session = assertNotNull(app.seq3Sessions.beginEdit(host, blockId))

            assertEquals("Pairing flow", app.seq3Sessions.sessions.single { it.id == session }.document.title)
            app.seq3Sessions.close(session)
        } finally {
            app.close()
            dir.deleteRecursively()
        }
    }

    private fun pngBytes(): ByteArray {
        val image = java.awt.image.BufferedImage(4, 4, java.awt.image.BufferedImage.TYPE_INT_RGB)
        return java.io.ByteArrayOutputStream().also { javax.imageio.ImageIO.write(image, "png", it) }.toByteArray()
    }
}
