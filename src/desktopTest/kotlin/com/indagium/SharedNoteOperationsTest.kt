package com.indagium

import com.indagium.model.AnnBlock
import com.indagium.model.Annotations
import com.indagium.model.LogEntry
import com.indagium.model.LogLevel
import com.indagium.model.LogTab
import com.indagium.model.SharedNoteClosedSource
import com.indagium.model.SharedNoteInfo
import com.indagium.ui.AppState
import com.indagium.ui.PendingNoteOverwrite
import com.indagium.ui.createSharedNote
import com.indagium.ui.deleteSharedNote
import com.indagium.ui.mkTab
import com.indagium.ui.newPrivateNote
import com.indagium.ui.nextDefaultSharedNoteName
import com.indagium.ui.noteOverwriteMessage
import com.indagium.ui.otherSharedNotes
import com.indagium.ui.renameSharedNote
import com.indagium.ui.reopenSharedNoteMember
import com.indagium.ui.setNotesView
import com.indagium.ui.setSharedNoteMembers
import com.indagium.ui.shareTabNote
import com.indagium.ui.sharedNotesFor
import com.indagium.ui.stopSharing
import com.indagium.ui.tokenFieldAt
import java.io.File
import java.util.concurrent.TimeUnit
import kotlin.io.path.createTempDirectory
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Phase 2 of shared notes: the operations on [AppState.sharedNoteHosts] (create, share a tab's note, members, rename, stop
 * sharing, delete), the member lifecycle (close keeps a closed member, reopening its file re-attaches it) and the queries
 * behind Open ▾. Every test also pins the Phase 1 rule that a host is never part of [AppState.tabs].
 */
class SharedNoteOperationsTest {
    private fun entry(id: Int, msg: String = "line $id") = LogEntry(id, "10:00:00.%03d".format(id), LogLevel.I, "App", msg)

    private fun waitUntil(timeoutMs: Long = 10_000, condition: () -> Boolean) {
        val deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(timeoutMs)
        while (System.nanoTime() < deadline) {
            if (condition()) return
            Thread.sleep(10)
        }
        assertTrue(condition())
    }

    private fun logFile(dir: File, name: String) = File(dir, name).apply {
        writeText("06-26 10:00:00.000  123  456 I App: hello from $name\n")
    }

    private fun state(dir: File, autoExport: Boolean = false) =
        AppState(File(dir, "state.cache"), notesDir = File(dir, "notes")).also { s ->
            s.updateSettings { it.copy(autoExportNotes = autoExport) }
        }

    /** Tabs a, b and c, each with a real source path under [dir] (the files need not exist unless a test opens them). */
    private fun withTabs(dir: File, autoExport: Boolean = false): AppState = state(dir, autoExport).also { s ->
        s.tabs = listOf("a", "b", "c").map { id ->
            mkTab(id, "$id.log", listOf(entry(1, "boom in $id"))).copy(sourcePath = File(dir, "$id.log").absolutePath)
        }
        s.activeTabId = "a"
    }

    private fun AppState.host(id: String): LogTab = assertNotNull(sharedNoteHost(id))

    private fun AppState.assertNoHostInTabs() {
        val hostIds = sharedNoteHosts.map { it.id }.toSet()
        assertTrue(tabs.none { it.id in hostIds || it.sharedNote != null }, "a host leaked into tabs: ${tabs.map { it.id }}")
        assertTrue(sharedNoteHosts.none { host -> tabs.any { it.id == host.id } })
    }

    private fun AppState.putBlocks(id: String, vararg blocks: AnnBlock) =
        upAnn(id) { it.copy(annotations = it.annotations.copy(blocks = it.annotations.blocks + blocks)) }

    private fun noteBlock(id: String = "n1", text: String = "a finding") = AnnBlock.Note(id, text)

    private fun logRef(id: String, sourceTabId: String?, vararg ids: Int) = AnnBlock.LogRef(
        id = id,
        logIds = ids.toList(),
        caption = "rows",
        sourceTabId = sourceTabId,
        sourceFilename = sourceTabId?.let { "$it.log" },
    )

    // ── createSharedNote ─────────────────────────────────────────────────────────────────────────

    @Test
    fun createSharedNoteMakesAnEmptyHostAndPointsEveryMemberAtIt() {
        val dir = createTempDirectory("shared-ops-create").toFile()
        val state = withTabs(dir)

        val id = assertNotNull(state.createSharedNote("  Pairing bug  ", listOf("a", "b")))

        assertTrue(id.startsWith("shared-"))
        val host = state.host(id)
        assertEquals("Pairing bug", host.filename)
        assertEquals(SharedNoteInfo(id, "Pairing bug", listOf("a", "b"), emptyList(), host.sharedNote!!.createdAtMs), host.sharedNote)
        assertTrue(host.sharedNote.createdAtMs > 0)
        assertEquals(Annotations(), host.annotations)
        assertEquals(id, state.tab("a")?.notesView)
        assertEquals(id, state.tab("b")?.notesView)
        assertNull(state.tab("c")?.notesView)
        assertEquals(listOf("a", "b", "c"), state.tabs.map { it.id })
        state.assertNoHostInTabs()
    }

    @Test
    fun createSharedNoteFallsBackToTheDefaultNameAndDropsInvalidMembers() {
        val dir = createTempDirectory("shared-ops-create-invalid").toFile()
        val state = withTabs(dir)
        state.openHomeTab()
        val launcherId = state.tabs.single { it.isCaptureLauncher }.id
        val first = assertNotNull(state.createSharedNote("   ", listOf("a")))

        val second = assertNotNull(state.createSharedNote("Two", listOf("b", "b", "nope", launcherId, first)))

        assertEquals("Shared note", state.host(first).filename)
        assertEquals(listOf("b"), state.host(second).sharedNote?.memberTabIds, "duplicates, unknown ids, the launcher and hosts are ignored")
        assertEquals(listOf(first, second), state.sharedNoteHosts.map { it.id }, "creation order")
        assertNull(state.tab(launcherId)?.notesView)
        state.assertNoHostInTabs()
    }

    @Test
    fun createSharedNoteWithoutAValidMemberChangesNothing() {
        val dir = createTempDirectory("shared-ops-create-none").toFile()
        val state = withTabs(dir)
        val tabsBefore = state.tabs

        assertNull(state.createSharedNote("Nothing", emptyList()))
        assertNull(state.createSharedNote("Nothing", listOf("nope")))

        assertTrue(state.sharedNoteHosts.isEmpty())
        assertTrue(state.tabs === tabsBefore)
    }

    // ── names and files of new shared notes ──────────────────────────────────────────────────────

    @Test
    fun blankNamedSharedNotesCountUpAndEachPinsItsOwnFile() {
        val dir = createTempDirectory("shared-ops-defaults").toFile()
        val state = withTabs(dir, autoExport = true)

        val first = assertNotNull(state.createSharedNote("", listOf("a")))
        val second = assertNotNull(state.createSharedNote("  ", listOf("b")))
        val third = assertNotNull(state.createSharedNote("", listOf("c")))

        assertEquals(listOf("Shared note", "Shared note 2", "Shared note 3"), listOf(first, second, third).map { state.host(it).sharedNote?.name })
        assertEquals(
            listOf("Shared_note_analysis.md", "Shared_note_2_analysis.md", "Shared_note_3_analysis.md"),
            listOf(first, second, third).map { state.host(it).noteTargetName },
        )
        assertEquals("Shared note 4", state.nextDefaultSharedNoteName())

        // Adding to the second never meets the first's file: no "Existing notes found" prompt, and each writes its own file.
        state.putBlocks(first, noteBlock("n1", "first"))
        waitUntil { File(dir, "notes/Shared_note_analysis.md").exists() }
        state.putBlocks(second, noteBlock("n2", "second"))
        waitUntil { File(dir, "notes/Shared_note_2_analysis.md").exists() }
        assertNull(state.pendingNoteOverwrite)
        assertTrue(File(dir, "notes/Shared_note_analysis.md").readText().contains("first"))
        assertTrue(File(dir, "notes/Shared_note_2_analysis.md").readText().contains("second"))
        state.close()
    }

    @Test
    fun aTypedNameIsKeptAndADeletedDefaultNameIsFreeAgain() {
        val dir = createTempDirectory("shared-ops-typed").toFile()
        val state = withTabs(dir)
        val named = assertNotNull(state.createSharedNote("Pairing", listOf("a")))
        val blank = assertNotNull(state.createSharedNote("", listOf("b")))
        assertEquals("Pairing", state.host(named).sharedNote?.name)
        assertEquals("Shared note", state.host(blank).sharedNote?.name)
        assertEquals("Pairing_analysis.md", state.host(named).noteTargetName)
        // A note's name is not a file name: a dot in it is kept (as "_"), not cut off as an extension.
        val versioned = assertNotNull(state.createSharedNote("Pairing v1.2", listOf("c")))
        assertEquals("Pairing_v1_2_analysis.md", state.host(versioned).noteTargetName)

        state.deleteSharedNote(blank)

        assertEquals("Shared note", state.nextDefaultSharedNoteName())
    }

    @Test
    fun aHostPinSkipsFilesOnDiskAndNamesPinnedByTabsOrOtherNotes() {
        val dir = createTempDirectory("shared-ops-pin-free").toFile()
        val notes = File(dir, "notes").apply { mkdirs() }
        File(notes, "Pairing_analysis.md").writeText("# old")
        File(notes, "Pairing_analysis_2.ann").writeText("old")
        val state = withTabs(dir)
        state.upTab("c") { it.copy(noteTargetName = "Pairing_analysis_3.md") }

        val id = assertNotNull(state.createSharedNote("Pairing", listOf("a")))
        val other = assertNotNull(state.createSharedNote("Pairing", listOf("b")))

        assertEquals("Pairing_analysis_4.md", state.host(id).noteTargetName)
        assertEquals("Pairing_analysis_5.md", state.host(other).noteTargetName, "another note's pin counts even before its file exists")
    }

    @Test
    fun renamingAndSharingKeepTheRightPins() {
        val dir = createTempDirectory("shared-ops-pin-rename").toFile()
        val state = withTabs(dir)
        val first = assertNotNull(state.createSharedNote("", listOf("a")))
        val second = assertNotNull(state.createSharedNote("", listOf("b")))

        assertTrue(state.renameSharedNote(second, ""))
        assertEquals("Shared note 2", state.host(second).sharedNote?.name, "a blank rename keeps counting past the other notes only")
        assertTrue(state.renameSharedNote(first, "Renamed"))
        assertEquals("Shared_note_analysis.md", state.host(first).noteTargetName, "a rename leaves the pin")

        state.putBlocks("c", noteBlock())
        val shared = assertNotNull(state.shareTabNote("c", "", listOf("c")))
        assertEquals("Shared note", state.host(shared).sharedNote?.name, "the name freed by the rename is used again")
        assertEquals("Shared_note_analysis_2.md", state.host(shared).noteTargetName)
    }

    @Test
    fun theOverwritePromptSaysAnotherSessionForASharedNoteAndForThisLogForATab() {
        val shared = noteOverwriteMessage("Shared_note_analysis.md", sharedNote = true)
        val tab = noteOverwriteMessage("a_analysis.md", sharedNote = false)

        assertTrue(shared.startsWith("\"Shared_note_analysis.md\" already has saved notes from another session."), shared)
        assertFalse("this log" in shared || "never opened" in shared, shared)
        assertTrue(tab.startsWith("\"a_analysis.md\" already has saved notes for this log, from a session that never opened this file."), tab)
    }

    // ── shareTabNote ─────────────────────────────────────────────────────────────────────────────

    @Test
    fun shareTabNoteMovesTheNoteAndThePinnedFileToTheHost() {
        val dir = createTempDirectory("shared-ops-share").toFile()
        val state = withTabs(dir)
        state.putBlocks("a", noteBlock(), logRef("r1", null, 1), logRef("r2", "b", 1))
        state.upAnn("a") { it.copy(annotations = it.annotations.copy(prefix = "From a.log", suffix = "end", issueDescription = "why")) }
        state.upTab("a") { it.copy(noteTargetName = "a_analysis.md", recoveredNoteRows = mapOf("r1" to listOf(entry(1)))) }
        val before = state.tab("a")!!

        val id = assertNotNull(state.shareTabNote("a", "Pairing bug", listOf("b")))

        val host = state.host(id)
        assertEquals(listOf("a", "b"), host.sharedNote?.memberTabIds, "the tab is always a member, first")
        assertEquals("a_analysis.md", host.noteTargetName)
        assertEquals(before.recoveredNoteRows, host.recoveredNoteRows)
        assertEquals("From a.log", host.annotations.prefix)
        assertEquals("end", host.annotations.suffix)
        assertEquals("why", host.annotations.issueDescription)
        assertEquals(listOf("n1", "r1", "r2"), host.annotations.blocks.map { it.id })
        val own = host.annotations.blocks[1] as AnnBlock.LogRef
        assertEquals("a", own.sourceTabId, "a reference to the tab's own rows now names the tab")
        assertEquals("a.log", own.sourceFilename)
        assertEquals("boom in a", own.sourceEntries?.single()?.msg, "and carries its frozen rows")
        assertEquals("b", (host.annotations.blocks[2] as AnnBlock.LogRef).sourceTabId)

        val tab = state.tab("a")!!
        assertTrue(tab.annotations.blocks.isEmpty())
        assertEquals("", tab.annotations.suffix)
        assertEquals("", tab.annotations.issueDescription)
        assertEquals("From a.log", tab.annotations.prefix, "a fresh private note starts with its usual prefix")
        assertTrue(tab.recoveredNoteRows.isEmpty())
        assertEquals("a_analysis_2.md", tab.noteTargetName)
        assertEquals(id, tab.notesView)
        assertEquals(id, state.tab("b")?.notesView)
        assertNull(state.tab("c")?.notesView)
        state.assertNoHostInTabs()
    }

    @Test
    fun shareTabNoteOfAnUnpinnedNoteLeavesTheTabUnpinned() {
        val dir = createTempDirectory("shared-ops-share-unpinned").toFile()
        val state = withTabs(dir)
        state.putBlocks("a", noteBlock())

        val id = assertNotNull(state.shareTabNote("a", "", listOf("a")))

        assertEquals("Shared note", state.host(id).filename)
        assertEquals("Shared_note_analysis.md", state.host(id).noteTargetName, "a host is pinned to a free file as it is made")
        assertNull(state.tab("a")?.noteTargetName)
        assertEquals(listOf("a"), state.host(id).sharedNote?.memberTabIds)
        assertEquals(1, state.host(id).annotations.blocks.size)
    }

    @Test
    fun theTabsNextPrivateNoteNeverExportsOverTheSharedFile() {
        val dir = createTempDirectory("shared-ops-share-export").toFile()
        val notes = File(dir, "notes")
        val state = withTabs(dir, autoExport = true)
        state.addNoteBlock("a", "first finding")
        val shared = File(notes, "a_analysis.md")
        waitUntil { shared.exists() && shared.readText().contains("first finding") }
        assertEquals("a_analysis.md", state.tab("a")?.noteTargetName)

        val id = assertNotNull(state.shareTabNote("a", "Pairing bug", listOf("a", "b")))
        assertEquals("a_analysis.md", state.host(id).noteTargetName)
        state.addNoteBlock("a", "private afterwards")

        val own = File(notes, "a_analysis_2.md")
        waitUntil { own.exists() && own.readText().contains("private afterwards") }
        assertFalse(shared.readText().contains("private afterwards"), "the shared file stays the shared note's")
        assertNull(state.pendingNoteOverwrite, "a new name, so no overwrite prompt either")
        assertEquals("a_analysis_2.md", state.tab("a")?.noteTargetName)

        // Edits made through the host keep going to the file it took over.
        state.addNoteBlock(id, "shared afterwards")
        waitUntil { shared.readText().contains("shared afterwards") }
        assertFalse(own.readText().contains("shared afterwards"))
    }

    @Test
    fun reopeningTheLogSkipsItsFormerPrivateExportAfterSharing() {
        val dir = createTempDirectory("shared-ops-reopen-export").toFile()
        val file = logFile(dir, "a.log")
        val state = withTabs(dir, autoExport = true)
        val notes = state.activeNotesDir()
        try {
            state.addNoteBlock("a", "now shared")
            val shared = File(notes, "a_analysis.md")
            val sharedSidecar = File(notes, "a_analysis.ann")
            waitUntil { runCatching { sharedSidecar.readText().tokenFieldAt(4) }.getOrNull() == file.absolutePath }
            val hostId = assertNotNull(state.shareTabNote("a", "Pairing bug", listOf("a")))
            state.closeTab("a")

            val firstReopen = assertNotNull(state.openFile(file))
            waitUntil { state.tab(firstReopen)?.analysis?.pending == false }

            val reopened = assertNotNull(state.tab(firstReopen))
            assertEquals(hostId, reopened.notesView)
            assertTrue(reopened.annotations.blocks.isEmpty())
            assertNull(reopened.noteTargetName)

            state.addNoteBlock(firstReopen, "still private")
            val privateExport = File(notes, "a_analysis_2.md")
            val privateSidecar = File(notes, "a_analysis_2.ann")
            waitUntil { runCatching { privateSidecar.readText().tokenFieldAt(4) }.getOrNull() == file.absolutePath }
            assertTrue(shared.setLastModified(privateExport.lastModified() + 5_000))
            state.closeTab(firstReopen)

            val secondReopen = assertNotNull(state.openFile(file))
            waitUntil { state.tab(secondReopen)?.analysis?.pending == false }

            val reloaded = assertNotNull(state.tab(secondReopen))
            assertEquals(privateExport.name, reloaded.noteTargetName)
            assertEquals("still private", (reloaded.annotations.blocks.single() as AnnBlock.Note).text)
            assertEquals("now shared", (state.host(hostId).annotations.blocks.single() as AnnBlock.Note).text)
        } finally {
            state.close()
            dir.deleteRecursively()
        }
    }

    @Test
    fun newPrivateNoteSkipsAnUnwrittenSharedExportTarget() {
        val dir = createTempDirectory("shared-ops-new-private").toFile()
        val state = withTabs(dir, autoExport = true)
        try {
            val id = assertNotNull(state.createSharedNote("a", listOf("a")))
            val shared = File(dir, "notes/a_analysis.md")
            val own = File(dir, "notes/a_analysis_2.md")
            assertEquals(shared.name, state.host(id).noteTargetName)
            assertFalse(shared.exists())

            state.newPrivateNote("a")
            assertNull(state.tab("a")?.notesView)
            assertEquals(own.name, state.tab("a")?.noteTargetName)
            state.addNoteBlock("a", "private finding")
            waitUntil { own.exists() && own.readText().contains("private finding") }
            state.addNoteBlock(id, "shared finding")
            waitUntil { shared.exists() && shared.readText().contains("shared finding") }

            assertFalse(own.readText().contains("shared finding"))
            assertFalse(shared.readText().contains("private finding"))
            assertNull(state.pendingNoteOverwrite)
        } finally {
            state.close()
            dir.deleteRecursively()
        }
    }

    @Test
    fun firstPrivateEditSkipsAnUnwrittenSharedExportTarget() {
        val dir = createTempDirectory("shared-ops-first-private-edit").toFile()
        val state = withTabs(dir, autoExport = true)
        try {
            val id = assertNotNull(state.createSharedNote("a", listOf("a")))
            assertEquals("a_analysis.md", state.host(id).noteTargetName)
            assertFalse(File(dir, "notes/a_analysis.md").exists())
            assertNull(state.tab("a")?.noteTargetName)

            state.setNotesView("a", null)
            state.addNoteBlock("a", "private finding")

            assertEquals("a_analysis_2.md", state.tab("a")?.noteTargetName)
            val own = File(dir, "notes/a_analysis_2.md")
            waitUntil { own.exists() && own.readText().contains("private finding") }
        } finally {
            state.close()
            dir.deleteRecursively()
        }
    }

    @Test
    fun editingAnOlderDuplicatePinKeepsTheExistingExportIntact() {
        val dir = createTempDirectory("shared-ops-duplicate-pin").toFile()
        val state = withTabs(dir, autoExport = true)
        try {
            val id = assertNotNull(state.createSharedNote("a", listOf("a")))
            val shared = File(dir, "notes/a_analysis.md")
            state.addNoteBlock(id, "original shared export")
            waitUntil { shared.exists() && shared.readText().contains("original shared export") }
            state.upTab("a") { it.copy(noteTargetName = shared.name) }

            state.addNoteBlock("a", "private finding")

            val own = File(dir, "notes/a_analysis_2.md")
            assertEquals(own.name, state.tab("a")?.noteTargetName)
            waitUntil { own.exists() && own.readText().contains("private finding") }
            assertEquals(shared.name, state.host(id).noteTargetName)
            assertTrue(shared.readText().contains("original shared export"))
            assertFalse(shared.readText().contains("private finding"))
        } finally {
            state.close()
            dir.deleteRecursively()
        }
    }

    @Test
    fun shareTabNoteRefusesWhileAnOverwritePromptIsPendingForTheTab() {
        val dir = createTempDirectory("shared-ops-share-pending").toFile()
        val state = withTabs(dir)
        state.putBlocks("a", noteBlock())
        val pending = PendingNoteOverwrite("a", File(dir, "x.md").absolutePath, "x.md", pendingTab = state.tab("a")!!)
        state.pendingNoteOverwrite = pending

        assertNull(state.shareTabNote("a", "Nope", listOf("a")))

        assertTrue(state.sharedNoteHosts.isEmpty())
        assertEquals(1, state.tab("a")!!.annotations.blocks.size)
        assertNull(state.tab("a")!!.notesView)
        assertEquals(pending, state.pendingNoteOverwrite, "the in-flight prompt is untouched")
        // A prompt for another tab does not block it.
        state.pendingNoteOverwrite = pending.copy(tabId = "b")
        assertNotNull(state.shareTabNote("a", "Fine", listOf("a")))
    }

    @Test
    fun shareTabNoteRefusesAnUnknownTabAndTheLauncher() {
        val dir = createTempDirectory("shared-ops-share-unknown").toFile()
        val state = withTabs(dir)
        state.openHomeTab()
        val launcherId = state.tabs.single { it.isCaptureLauncher }.id

        assertNull(state.shareTabNote("nope", "x", listOf("a")))
        assertNull(state.shareTabNote(launcherId, "x", listOf("a")))
        assertTrue(state.sharedNoteHosts.isEmpty())
    }

    // ── setSharedNoteMembers ─────────────────────────────────────────────────────────────────────

    @Test
    fun setSharedNoteMembersSendsRemovedTabsBackAndLeavesAddedTabsAlone() {
        val dir = createTempDirectory("shared-ops-members").toFile()
        val state = withTabs(dir)
        val other = assertNotNull(state.createSharedNote("Other", listOf("c")))
        val id = assertNotNull(state.createSharedNote("Main", listOf("a", "b")))
        val closed = SharedNoteClosedSource("z", "z.log", "/tmp/z.log")
        state.addSharedNoteHost(state.host(id).let { it.copy(sharedNote = it.sharedNote!!.copy(closedMembers = listOf(closed))) })

        assertTrue(state.setSharedNoteMembers(id, listOf("b", "c", "nope")))

        val info = state.host(id).sharedNote!!
        assertEquals(listOf("b", "c"), info.memberTabIds)
        assertEquals(listOf(closed), info.closedMembers, "closed members are untouched")
        assertNull(state.tab("a")?.notesView, "a removed member goes back to its own note")
        assertEquals(id, state.tab("b")?.notesView)
        assertEquals(other, state.tab("c")?.notesView, "an added member keeps what it shows")
        state.assertNoHostInTabs()
    }

    @Test
    fun aRemovedMemberThatShowsAnotherNoteKeepsShowingIt() {
        val dir = createTempDirectory("shared-ops-members-other").toFile()
        val state = withTabs(dir)
        val id = assertNotNull(state.createSharedNote("Main", listOf("a", "b")))
        val other = assertNotNull(state.createSharedNote("Other", listOf("a")))
        assertEquals(other, state.tab("a")?.notesView)

        state.setSharedNoteMembers(id, listOf("b"))

        assertEquals(other, state.tab("a")?.notesView)
    }

    @Test
    fun aSharedNoteStaysWhenItsLastMemberLeaves() {
        val dir = createTempDirectory("shared-ops-members-empty").toFile()
        val state = withTabs(dir)
        val id = assertNotNull(state.createSharedNote("Main", listOf("a")))

        assertTrue(state.setSharedNoteMembers(id, emptyList()))

        assertEquals(emptyList(), state.host(id).sharedNote?.memberTabIds)
        assertNull(state.tab("a")?.notesView)
        assertFalse(state.setSharedNoteMembers("nope", listOf("a")))
    }

    @Test
    fun addingAMemberWhoseFileIsAClosedMemberReattachesIt() {
        val dir = createTempDirectory("shared-ops-members-reattach").toFile()
        val state = withTabs(dir)
        val id = assertNotNull(state.createSharedNote("Main", listOf("a", "b")))
        state.putBlocks(id, logRef("r1", "a", 1))
        state.closeTab("a")
        assertEquals(listOf("a"), state.host(id).sharedNote!!.closedMembers.map { it.tabId })
        state.tabs += mkTab("a2", "a.log", listOf(entry(1))).copy(sourcePath = File(dir, "a.log").absolutePath)

        state.setSharedNoteMembers(id, listOf("b", "a2"))

        val info = state.host(id).sharedNote!!
        assertEquals(listOf("b", "a2"), info.memberTabIds)
        assertTrue(info.closedMembers.isEmpty())
        assertEquals("a2", (state.host(id).annotations.blocks.single() as AnnBlock.LogRef).sourceTabId)
        assertNull(state.tab("a2")?.notesView, "adding a member does not change what it shows")
    }

    @Test
    fun closingAMemberWhoseFileIsStillOpenInAnotherTabHandsItsMembershipOver() {
        // A reparse opens the same file as a new tab before it closes the original.
        val dir = createTempDirectory("shared-ops-handover").toFile()
        val state = withTabs(dir)
        val id = assertNotNull(state.createSharedNote("Main", listOf("a", "b")))
        state.putBlocks(id, logRef("r1", "a", 1))
        state.tabs += mkTab("a2", "a.log", listOf(entry(1))).copy(sourcePath = File(dir, "a.log").absolutePath)

        state.closeTab("a")

        val info = state.host(id).sharedNote!!
        assertEquals(listOf("b", "a2"), info.memberTabIds)
        assertTrue(info.closedMembers.isEmpty(), "the file is still open, so nothing is closed")
        assertEquals("a2", (state.host(id).annotations.blocks.single() as AnnBlock.LogRef).sourceTabId)
        assertEquals(id, state.tab("a2")?.notesView, "the new tab shows the note the closed one showed")
        state.assertNoHostInTabs()
    }

    // ── renameSharedNote ─────────────────────────────────────────────────────────────────────────

    @Test
    fun renameChangesTheNameButNotThePinnedFile() {
        val dir = createTempDirectory("shared-ops-rename").toFile()
        val state = withTabs(dir)
        state.putBlocks("a", noteBlock())
        state.upTab("a") { it.copy(noteTargetName = "a_analysis.md") }
        val id = assertNotNull(state.shareTabNote("a", "Old name", listOf("a")))

        assertTrue(state.renameSharedNote(id, "  New name "))

        assertEquals("New name", state.host(id).sharedNote?.name)
        assertEquals("New name", state.host(id).filename)
        assertEquals("a_analysis.md", state.host(id).noteTargetName)
        assertTrue(state.renameSharedNote(id, " "))
        assertEquals("Shared note", state.host(id).sharedNote?.name)
        assertFalse(state.renameSharedNote("nope", "x"))
    }

    // ── stopSharing ──────────────────────────────────────────────────────────────────────────────

    @Test
    fun stopSharingCopiesTheNoteIntoTheTabAndMovesThePinnedFileBack() {
        val dir = createTempDirectory("shared-ops-stop").toFile()
        val state = withTabs(dir)
        val id = assertNotNull(state.createSharedNote("Main", listOf("a", "b")))
        state.putBlocks(id, noteBlock(), logRef("own", "a", 1), logRef("theirs", "b", 1))
        state.upTab(id) { it.copy(noteTargetName = "main_analysis.md", annotations = it.annotations.copy(suffix = "end")) }
        state.upTab("a") { it.copy(annotations = Annotations(prefix = "From a.log"), noteTargetName = "a_analysis_2.md") }

        assertTrue(state.stopSharing(id, "a"), "a seeded prefix alone is not a note worth confirming over")

        val tab = state.tab("a")!!
        assertEquals(listOf("n1", "own", "theirs"), tab.annotations.blocks.map { it.id })
        assertEquals("end", tab.annotations.suffix)
        assertNull((tab.annotations.blocks[1] as AnnBlock.LogRef).sourceTabId, "its own rows are its own again")
        assertEquals("b", (tab.annotations.blocks[2] as AnnBlock.LogRef).sourceTabId)
        assertEquals("main_analysis.md", tab.noteTargetName)
        assertNull(tab.notesView)
        assertNull(state.tab("b")?.notesView, "the other members go back to their own notes")
        assertTrue(state.sharedNoteHosts.isEmpty())
        state.assertNoHostInTabs()
    }

    @Test
    fun stopSharingIntoANonEmptyPrivateNoteNeedsReplaceExisting() {
        val dir = createTempDirectory("shared-ops-stop-replace").toFile()
        val state = withTabs(dir)
        val id = assertNotNull(state.createSharedNote("Main", listOf("a", "b")))
        state.putBlocks(id, noteBlock("shared", "shared text"))
        val cases = listOf<(LogTab) -> LogTab>(
            { it.copy(annotations = Annotations(blocks = listOf(noteBlock("own", "mine")))) },
            { it.copy(annotations = Annotations(prefix = "Custom intro")) },
            { it.copy(annotations = Annotations(suffix = "tail")) },
            { it.copy(annotations = Annotations(issueDescription = "context")) },
        )
        for (makeNonEmpty in cases) {
            state.upTab("a", makeNonEmpty)
            val before = state.tab("a")!!
            val hostBefore = state.host(id)

            assertFalse(state.stopSharing(id, "a"))

            assertEquals(before, state.tab("a"), "a refusal changes nothing")
            assertEquals(hostBefore, state.host(id))
            assertEquals(id, state.tab("b")?.notesView)
        }

        assertTrue(state.stopSharing(id, "a", replaceExisting = true))

        assertEquals(listOf("shared"), state.tab("a")!!.annotations.blocks.map { it.id })
        assertTrue(state.sharedNoteHosts.isEmpty())
    }

    @Test
    fun stopSharingRefusesANonMemberAnUnknownNoteAndAPendingPrompt() {
        val dir = createTempDirectory("shared-ops-stop-refuse").toFile()
        val state = withTabs(dir)
        val id = assertNotNull(state.createSharedNote("Main", listOf("a")))

        assertFalse(state.stopSharing(id, "b"), "b is not a member")
        assertFalse(state.stopSharing(id, "nope"))
        assertFalse(state.stopSharing("nope", "a"))
        state.pendingNoteOverwrite = PendingNoteOverwrite(id, "/x.md", "x.md", pendingTab = state.host(id))
        assertFalse(state.stopSharing(id, "a"))
        state.pendingNoteOverwrite = PendingNoteOverwrite("a", "/x.md", "x.md", pendingTab = state.tab("a")!!)
        assertFalse(state.stopSharing(id, "a"))

        assertEquals(listOf(id), state.sharedNoteHosts.map { it.id })
        assertEquals(id, state.tab("a")?.notesView)
    }

    @Test
    fun sharingATabsNoteAndStoppingGivesItBackUnchanged() {
        val dir = createTempDirectory("shared-ops-roundtrip").toFile()
        val state = withTabs(dir)
        state.putBlocks("a", noteBlock(), logRef("r1", null, 1))
        state.upTab("a") { it.copy(noteTargetName = "a_analysis.md") }
        val original = state.tab("a")!!.annotations
        val id = assertNotNull(state.shareTabNote("a", "Main", listOf("a", "b")))

        assertTrue(state.stopSharing(id, "a"))

        val tab = state.tab("a")!!
        assertEquals("a_analysis.md", tab.noteTargetName)
        assertEquals(original.blocks.map { it.id }, tab.annotations.blocks.map { it.id })
        val ref = tab.annotations.blocks[1] as AnnBlock.LogRef
        assertNull(ref.sourceTabId)
        assertEquals(listOf(1), ref.logIds)
    }

    // ── deleteSharedNote ─────────────────────────────────────────────────────────────────────────

    @Test
    fun deleteRemovesTheHostAndSendsEveryViewerBackToItsOwnNote() {
        val dir = createTempDirectory("shared-ops-delete").toFile()
        val notes = File(dir, "notes").apply { mkdirs() }
        val exported = File(notes, "main_analysis.md").apply { writeText("# exported") }
        val state = withTabs(dir)
        val id = assertNotNull(state.createSharedNote("Main", listOf("a", "b")))
        val keep = assertNotNull(state.createSharedNote("Keep", listOf("c")))
        state.setNotesView("a", keep)

        assertTrue(state.deleteSharedNote(id))

        assertEquals(listOf(keep), state.sharedNoteHosts.map { it.id })
        assertNull(state.tab("b")?.notesView)
        assertEquals(keep, state.tab("a")?.notesView, "a tab showing another note is not touched")
        assertEquals("# exported", exported.readText(), "files on disk are left alone")
        assertFalse(state.deleteSharedNote(id))
        state.assertNoHostInTabs()
    }

    @Test
    fun deleteCancelsAnOverwritePromptAimedAtTheHostButNotOthers() {
        val dir = createTempDirectory("shared-ops-delete-pending").toFile()
        val state = withTabs(dir)
        val id = assertNotNull(state.createSharedNote("Main", listOf("a")))
        state.pendingNoteOverwrite = PendingNoteOverwrite(id, "/x.md", "x.md", pendingTab = state.host(id))

        state.deleteSharedNote(id)

        assertNull(state.pendingNoteOverwrite)
        val other = assertNotNull(state.createSharedNote("Other", listOf("b")))
        val tabPending = PendingNoteOverwrite("c", "/y.md", "y.md", pendingTab = state.tab("c")!!)
        state.pendingNoteOverwrite = tabPending
        state.deleteSharedNote(other)
        assertEquals(tabPending, state.pendingNoteOverwrite)
    }

    // ── setNotesView and the queries ─────────────────────────────────────────────────────────────

    @Test
    fun setNotesViewSwitchesTheNoteAndAddsANonMemberAsAMember() {
        val dir = createTempDirectory("shared-ops-view").toFile()
        val state = withTabs(dir)
        val id = assertNotNull(state.createSharedNote("Main", listOf("a")))

        assertTrue(state.setNotesView("b", id))

        assertEquals(id, state.tab("b")?.notesView)
        assertEquals(listOf("a", "b"), state.host(id).sharedNote?.memberTabIds)
        assertTrue(state.setNotesView("b", null))
        assertNull(state.tab("b")?.notesView)
        assertEquals(listOf("a", "b"), state.host(id).sharedNote?.memberTabIds, "going back to its own note keeps the membership")
        assertFalse(state.setNotesView("b", "nope"))
        assertFalse(state.setNotesView("nope", id))
        assertFalse(state.setNotesView(id, id), "a host is not a tab")
        assertNull(state.tab("b")?.notesView)
        state.assertNoHostInTabs()
    }

    @Test
    fun sharedNotesForAndOtherSharedNotesListInCreationOrder() {
        val dir = createTempDirectory("shared-ops-queries").toFile()
        val state = withTabs(dir)
        val first = assertNotNull(state.createSharedNote("First", listOf("a")))
        val second = assertNotNull(state.createSharedNote("Second", listOf("b")))
        val third = assertNotNull(state.createSharedNote("Third", listOf("a", "b")))

        assertEquals(listOf(first, third), state.sharedNotesFor("a").map { it.id })
        assertEquals(listOf(second), state.otherSharedNotes("a").map { it.id })
        assertEquals(listOf(second, third), state.sharedNotesFor("b").map { it.id })
        assertEquals(listOf(first, second, third), state.otherSharedNotes("c").map { it.id })
        assertTrue(state.sharedNotesFor("c").isEmpty())
    }

    // ── Member lifecycle: closing ────────────────────────────────────────────────────────────────

    @Test
    fun closingAMemberKeepsItsFileAsAClosedMemberOnEveryClosePath() {
        val dir = createTempDirectory("shared-ops-close").toFile()
        val state = withTabs(dir)
        state.tabs += mkTab("d", "d.log", listOf(entry(1))).copy(sourcePath = "${File(dir, "z.zip").absolutePath}!inner/d.log")
        state.tabs += mkTab("e", "e.log", listOf(entry(1))) // no source at all
        val id = assertNotNull(state.createSharedNote("Main", listOf("a", "b", "c", "d", "e")))

        state.closeTab("a")
        assertEquals(listOf("b", "c", "d", "e"), state.host(id).sharedNote!!.memberTabIds)
        assertEquals(
            listOf(SharedNoteClosedSource("a", "a.log", File(dir, "a.log").absolutePath)),
            state.host(id).sharedNote!!.closedMembers,
        )

        state.closeOtherTabs("b")
        val info = state.host(id).sharedNote!!
        assertEquals(listOf("b"), info.memberTabIds)
        assertEquals(listOf("a", "c"), info.closedMembers.map { it.tabId }, "an archive entry or a tab without a file is just dropped")

        state.closeAllTabs()
        assertTrue(state.host(id).sharedNote!!.memberTabIds.isEmpty())
        assertEquals(listOf("a", "c", "b"), state.host(id).sharedNote!!.closedMembers.map { it.tabId })
        assertEquals(listOf(id), state.sharedNoteHosts.map { it.id }, "the note stays with no members at all")
        assertNull(state.tab(id))
        state.assertNoHostInTabs()
    }

    @Test
    fun closingATabThatIsNotAMemberTouchesNoHost() {
        val dir = createTempDirectory("shared-ops-close-nonmember").toFile()
        val state = withTabs(dir)
        val id = assertNotNull(state.createSharedNote("Main", listOf("a")))
        val before = state.host(id)

        state.closeTab("b")

        assertTrue(state.host(id) === before)
    }

    // ── Member lifecycle: reopening ──────────────────────────────────────────────────────────────

    @Test
    fun openingAClosedMembersFileAgainReattachesItAndFollowsItsRowReferences() {
        val dir = createTempDirectory("shared-ops-reopen").toFile()
        val state = withTabs(dir)
        val file = logFile(dir, "a.log")
        try {
            val id = assertNotNull(state.createSharedNote("Main", listOf("a", "b")))
            state.putBlocks(id, logRef("r1", "a", 1), logRef("r2", "b", 1))
            state.closeTab("a")
            assertEquals(listOf("a"), state.host(id).sharedNote!!.closedMembers.map { it.tabId })

            state.openFile(file)
            waitUntil { state.tabs.any { it.sourcePath == file.absolutePath } }

            val reopened = state.tabs.single { it.sourcePath == file.absolutePath }
            assertNotEquals("a", reopened.id)
            val info = state.host(id).sharedNote!!
            assertEquals(listOf("b", reopened.id), info.memberTabIds)
            assertTrue(info.closedMembers.isEmpty())
            assertEquals(id, reopened.notesView)
            val refs = state.host(id).annotations.blocks.filterIsInstance<AnnBlock.LogRef>()
            assertEquals(reopened.id, refs.first { it.id == "r1" }.sourceTabId)
            assertEquals("b", refs.first { it.id == "r2" }.sourceTabId)
            state.assertNoHostInTabs()
        } finally {
            state.close()
        }
    }

    @Test
    fun aFileThatIsAClosedMemberOfSeveralNotesReattachesToAll() {
        val dir = createTempDirectory("shared-ops-reopen-many").toFile()
        val state = withTabs(dir)
        val file = logFile(dir, "a.log")
        try {
            val first = assertNotNull(state.createSharedNote("First", listOf("a")))
            val second = assertNotNull(state.createSharedNote("Second", listOf("a", "b")))
            state.putBlocks(second, logRef("r1", "a", 1))
            state.closeTab("a")

            state.openFile(file)
            waitUntil { state.tabs.any { it.sourcePath == file.absolutePath } }

            val reopened = state.tabs.single { it.sourcePath == file.absolutePath }
            assertTrue(reopened.id in state.host(first).sharedNote!!.memberTabIds)
            assertTrue(reopened.id in state.host(second).sharedNote!!.memberTabIds)
            assertEquals(first, reopened.notesView, "it shows the first of them")
            assertEquals(reopened.id, (state.host(second).annotations.blocks.single() as AnnBlock.LogRef).sourceTabId)
        } finally {
            state.close()
        }
    }

    @Test
    fun aFileThatIsNotAClosedMemberJoinsNothing() {
        val dir = createTempDirectory("shared-ops-open-other").toFile()
        val state = withTabs(dir)
        val other = logFile(dir, "other.log")
        try {
            val id = assertNotNull(state.createSharedNote("Main", listOf("a")))
            state.closeTab("a")

            state.openFile(other)
            waitUntil { state.tabs.any { it.sourcePath == other.absolutePath } }

            assertTrue(state.host(id).sharedNote!!.memberTabIds.isEmpty())
            assertEquals(1, state.host(id).sharedNote!!.closedMembers.size)
            assertNull(state.tabs.single { it.sourcePath == other.absolutePath }.notesView)
        } finally {
            state.close()
        }
    }

    @Test
    fun reopenSharedNoteMemberOpensTheFileAndReattaches() {
        val dir = createTempDirectory("shared-ops-reopen-member").toFile()
        val state = withTabs(dir)
        val file = logFile(dir, "a.log")
        try {
            val id = assertNotNull(state.createSharedNote("Main", listOf("a", "b")))
            state.closeTab("a")

            assertTrue(state.reopenSharedNoteMember(id, "a"))
            waitUntil { state.tabs.any { it.sourcePath == file.absolutePath } }

            val reopened = state.tabs.single { it.sourcePath == file.absolutePath }
            assertEquals(listOf("b", reopened.id), state.host(id).sharedNote!!.memberTabIds)
            assertEquals(id, reopened.notesView)
            assertNull(state.openError)
            assertFalse(state.reopenSharedNoteMember(id, "nope"), "an unknown closed member")
            assertFalse(state.reopenSharedNoteMember("nope", "a"))
        } finally {
            state.close()
        }
    }

    @Test
    fun reopenSharedNoteMemberUsesThePinnedProfileWithAnotherParseAlreadyOpen() {
        val dir = createTempDirectory("shared-ops-reopen-profile").toFile()
        val state = state(dir)
        try {
            val file = File(dir, "events.json").apply { writeText("""{"message":"hello"}""" + "\n") }
            val pinned = assertNotNull(state.logFormatProfile("plain-text"))
                .copy(id = "text-events", name = "Text events", readOnly = false)
            state.updateSettings { it.copy(customLogFormatProfiles = listOf(pinned)) }
            val originalId = assertNotNull(state.openFileWithLogFormatProfile(file, pinned.id))
            waitUntil { state.tab(originalId) != null }
            val snapshot = assertNotNull(state.tab(originalId)?.logFormatProfileSnapshot)
            val hostId = assertNotNull(state.createSharedNote("Main", listOf(originalId)))
            state.putBlocks(hostId, logRef("r1", originalId, 1))
            state.closeTab(originalId)

            state.updateSettings { it.copy(customLogFormatProfiles = emptyList()) }
            val otherId = assertNotNull(state.openFileWithLogFormatProfile(file, "json-lines"))
            waitUntil { state.tab(otherId) != null }
            assertEquals(originalId, state.host(hostId).sharedNote!!.closedMembers.single().tabId)
            assertNull(state.tab(otherId)?.notesView)

            assertTrue(state.reopenSharedNoteMember(hostId, originalId))
            waitUntil { state.host(hostId).sharedNote!!.memberTabIds.singleOrNull()?.let { state.tab(it) != null } == true }

            val reopened = assertNotNull(state.tab(state.host(hostId).sharedNote!!.memberTabIds.single()))
            assertNotEquals(otherId, reopened.id)
            assertEquals(snapshot, reopened.logFormatProfileSnapshot)
            assertEquals(reopened.id, (state.host(hostId).annotations.blocks.single() as AnnBlock.LogRef).sourceTabId)
            assertTrue(state.host(hostId).sharedNote!!.closedMembers.isEmpty())
        } finally {
            state.close()
            dir.deleteRecursively()
        }
    }

    @Test
    fun reopenSharedNoteMemberWithAMissingFileReportsAnOpenError() {
        val dir = createTempDirectory("shared-ops-reopen-missing").toFile()
        val state = withTabs(dir)
        val id = assertNotNull(state.createSharedNote("Main", listOf("a", "b")))
        state.closeTab("a") // a.log was never written

        assertFalse(state.reopenSharedNoteMember(id, "a"))

        val error = assertNotNull(state.openError)
        assertEquals(File(dir, "a.log").absolutePath, error.path)
        assertTrue(error.message.isNotBlank())
        assertEquals(listOf("a"), state.host(id).sharedNote!!.closedMembers.map { it.tabId }, "it stays a closed member")
        assertEquals(2, state.tabs.size)
    }

    @Test
    fun reopenSharedNoteMemberWithoutARecordedFileReportsAnOpenError() {
        val dir = createTempDirectory("shared-ops-reopen-nofile").toFile()
        val state = withTabs(dir)
        val id = assertNotNull(state.createSharedNote("Main", listOf("a")))
        state.addSharedNoteHost(
            state.host(id).let { it.copy(sharedNote = it.sharedNote!!.copy(closedMembers = listOf(SharedNoteClosedSource("x", "x.log", null)))) },
        )

        assertFalse(state.reopenSharedNoteMember(id, "x"))

        assertNotNull(state.openError)
    }

    @Test
    fun reopenSharedNoteMemberAttachesAnAlreadyOpenTabForThatFile() {
        val dir = createTempDirectory("shared-ops-reopen-open").toFile()
        val state = withTabs(dir)
        logFile(dir, "b.log")
        val id = assertNotNull(state.createSharedNote("Main", listOf("a")))
        state.putBlocks(id, logRef("r1", "gone", 1))
        val closed = SharedNoteClosedSource("gone", "b.log", File(dir, "b.log").absolutePath)
        state.addSharedNoteHost(state.host(id).let { it.copy(sharedNote = it.sharedNote!!.copy(closedMembers = listOf(closed))) })

        assertTrue(state.reopenSharedNoteMember(id, "gone"))

        val info = state.host(id).sharedNote!!
        assertEquals(listOf("a", "b"), info.memberTabIds)
        assertTrue(info.closedMembers.isEmpty())
        assertEquals("b", (state.host(id).annotations.blocks.single() as AnnBlock.LogRef).sourceTabId)
        assertEquals(id, state.tab("b")?.notesView)
        assertEquals("b", state.activeTabId)
        assertEquals(3, state.tabs.size, "no duplicate tab was opened")
    }

    @Test
    fun aRestoredSessionKeepsItsMembersWithoutDoubleAttaching() {
        val dir = createTempDirectory("shared-ops-restore").toFile()
        val state = withTabs(dir)
        val id = assertNotNull(state.createSharedNote("Main", listOf("a", "b", "c")))
        state.closeTab("c")
        state.autosaveNow()

        val restored = AppState(File(dir, "state.cache"), restoreOnCreate = true)

        assertEquals(listOf("a", "b"), restored.host(id).sharedNote!!.memberTabIds)
        assertEquals(listOf("c"), restored.host(id).sharedNote!!.closedMembers.map { it.tabId })
        assertEquals(id, restored.tab("a")?.notesView)
        restored.assertNoHostInTabs()
    }

    // ── Autosave ─────────────────────────────────────────────────────────────────────────────────

    @Test
    fun autosaveRoundTripKeepsTheResultOfSeveralOperations() {
        val dir = createTempDirectory("shared-ops-autosave").toFile()
        val state = withTabs(dir)
        state.putBlocks("a", noteBlock(), logRef("r1", null, 1))
        state.upTab("a") { it.copy(noteTargetName = "a_analysis.md") }
        val shared = assertNotNull(state.shareTabNote("a", "Pairing bug", listOf("b")))
        val second = assertNotNull(state.createSharedNote("Second", listOf("c")))
        state.renameSharedNote(second, "Renamed")
        state.setSharedNoteMembers(shared, listOf("a"))
        state.closeTab("c")
        state.autosaveNow()

        val restored = AppState(File(dir, "state.cache"), restoreOnCreate = true)

        assertEquals(state.sharedNoteHosts.map { it.sharedNote }, restored.sharedNoteHosts.map { it.sharedNote })
        assertEquals(shared, restored.sharedNoteHosts.first().id)
        assertEquals(state.host(shared).annotations, restored.host(shared).annotations)
        assertEquals("a_analysis.md", restored.host(shared).noteTargetName)
        assertEquals("Renamed", restored.host(second).sharedNote?.name)
        assertEquals(listOf("c"), restored.host(second).sharedNote!!.closedMembers.map { it.tabId })
        assertEquals(shared, restored.tab("a")?.notesView)
        assertNull(restored.tab("b")?.notesView, "b was removed from the members")
        assertEquals(state.tab("a")?.noteTargetName, restored.tab("a")?.noteTargetName)
        restored.assertNoHostInTabs()
    }

    @Test
    fun noOperationEverPutsAHostIntoTabs() {
        val dir = createTempDirectory("shared-ops-no-leak").toFile()
        val state = withTabs(dir)
        state.putBlocks("a", noteBlock())
        val one = assertNotNull(state.createSharedNote("One", listOf("a", "b")))
        state.assertNoHostInTabs()
        val two = assertNotNull(state.shareTabNote("a", "Two", listOf("c")))
        state.assertNoHostInTabs()
        state.setSharedNoteMembers(one, listOf("b", "c"))
        state.renameSharedNote(one, "Uno")
        state.setNotesView("a", one)
        state.closeTab("b")
        state.assertNoHostInTabs()
        state.stopSharing(two, "a", replaceExisting = true)
        state.deleteSharedNote(one)
        state.assertNoHostInTabs()
        assertEquals(listOf("a", "c"), state.tabs.map { it.id })
        assertTrue(state.sharedNoteHosts.isEmpty())
    }
}
