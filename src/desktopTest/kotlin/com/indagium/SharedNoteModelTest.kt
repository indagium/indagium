package com.indagium

import com.indagium.debug.IndagiumToolOperations
import com.indagium.model.AnnBlock
import com.indagium.model.Annotations
import com.indagium.model.BUILTIN_LOG_FORMAT_PROFILES
import com.indagium.model.LogEntry
import com.indagium.model.LogLevel
import com.indagium.model.SharedNoteClosedSource
import com.indagium.model.SharedNoteInfo
import com.indagium.model.TabContentState
import com.indagium.ui.AUTOSAVE_MAGIC_CURRENT
import com.indagium.ui.TAB_TOKEN_SHARED_NOTE_INDEX
import com.indagium.ui.AppState
import com.indagium.ui.annotationsToken
import com.indagium.ui.mkTab
import com.indagium.ui.sharedNoteHostTab
import com.indagium.utils.logFormatProfileJson
import kotlinx.coroutines.runBlocking
import java.io.File
import java.util.concurrent.TimeUnit
import kotlin.io.path.createTempDirectory
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Phase 1 of shared notes: a shared note is a note-only host [com.indagium.model.LogTab] kept in
 * [AppState.sharedNoteHosts], never in [AppState.tabs]. These tests pin that the notes pipeline
 * (tab/upTab/upFlt/upAnn, auto-export, the overwrite prompt, open note) reaches a host, that no
 * log feature ever sees one, and that hosts and the two new tab fields survive an autosave round trip.
 */
class SharedNoteModelTest {
    private fun entry(id: Int, msg: String = "line $id") = LogEntry(id, "10:00:00.%03d".format(id), LogLevel.I, "App", msg)

    private fun info(id: String = "shared-1", name: String = "Pairing bug", members: List<String> = listOf("a", "b")) =
        SharedNoteInfo(
            id = id,
            name = name,
            memberTabIds = members,
            closedMembers = listOf(
                SharedNoteClosedSource(
                    "c", "old|file.json", "/tmp/old|file.json", logFormatProfileJson(BUILTIN_LOG_FORMAT_PROFILES.first { it.id == "json-lines" }),
                ),
                SharedNoteClosedSource("d", "gone.log", null),
            ),
            createdAtMs = 1_700_000_000_123L,
            closedReferenceSources = listOf(
                SharedNoteClosedSource(
                    "t7", "compare.jsonl", "/tmp/compare.jsonl",
                    logFormatProfileJson(BUILTIN_LOG_FORMAT_PROFILES.first { it.id == "json-lines" }),
                ),
            ),
        )

    private fun state(dir: File, notes: File = File(dir, "notes")) = AppState(File(dir, "state.cache"), notesDir = notes)

    private fun waitUntil(timeoutMs: Long = 10_000, condition: () -> Boolean) {
        val deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(timeoutMs)
        while (System.nanoTime() < deadline) {
            if (condition()) return
            Thread.sleep(10)
        }
        assertTrue(condition())
    }

    private fun stateWithTabsAndHost(dir: File): AppState {
        val state = state(dir)
        state.tabs = listOf(
            mkTab("a", "a.log", listOf(entry(1))).copy(sourcePath = File(dir, "a.log").absolutePath),
            mkTab("b", "b.log", listOf(entry(1))).copy(sourcePath = File(dir, "b.log").absolutePath),
        )
        state.activeTabId = "a"
        state.addSharedNoteHost(sharedNoteHostTab(info()))
        return state
    }

    // ── Storage: notes code resolves a host through noteTab/upTab/upFlt/upAnn, never through tab or tabs ──

    @Test
    fun aHostIsFoundByNoteTabButNeverByTabOrInTabs() {
        val dir = createTempDirectory("shared-note-model").toFile()
        val state = stateWithTabsAndHost(dir)

        val host = assertNotNull(state.noteTab("shared-1"))
        assertEquals("Pairing bug", host.filename)
        assertEquals(TabContentState.LOADED, host.contentState)
        assertTrue(host.logData.isEmpty() && host.rmap.isEmpty() && host.sourcePath == null)
        assertSame(host, state.sharedNoteHost("shared-1"))
        assertNull(state.tab("shared-1"), "tab() is for log features and must not see a host")
        assertEquals(listOf("a", "b"), state.tabs.map { it.id })
        assertEquals(listOf("shared-1"), state.sharedNoteHosts.map { it.id })
        assertNull(state.sharedNoteHost("a"))
        assertEquals("a", state.tab("a")?.id)
        assertEquals("a", state.noteTab("a")?.id, "noteTab finds ordinary tabs first")
        assertNull(state.noteTab("nope"))
    }

    private fun assertSame(expected: Any?, actual: Any?) = assertTrue(expected === actual)

    @Test
    fun aHostIdCannotCollideWithAnOrdinaryTab() {
        val dir = createTempDirectory("shared-note-collision").toFile()
        val state = stateWithTabsAndHost(dir)

        val failure = runCatching { state.addSharedNoteHost(sharedNoteHostTab(info(id = "a"))) }.exceptionOrNull()

        assertTrue(failure is IllegalArgumentException)
        assertEquals(listOf("shared-1"), state.sharedNoteHosts.map { it.id })
    }

    @Test
    fun upTabUpFltAndUpAnnWriteBackToTheHostAndLeaveTabsUntouched() {
        val dir = createTempDirectory("shared-note-writers").toFile()
        val state = stateWithTabsAndHost(dir)
        state.updateSettings { it.copy(autoExportNotes = false) }
        val tabsBefore = state.tabs

        state.upTab("shared-1") { it.copy(showAnnMd = true) }
        state.upFlt("shared-1") { it.copy(kwText = "needle") }
        state.addNoteBlock("shared-1", "first shared note")

        val host = assertNotNull(state.sharedNoteHost("shared-1"))
        assertTrue(host.showAnnMd)
        assertEquals("needle", host.filter.kwText)
        assertEquals(listOf("first shared note"), host.annotations.blocks.filterIsInstance<AnnBlock.Note>().map { it.text })
        assertEquals("Pairing bug", host.sharedNote?.name, "the descriptor survives every write")
        assertTrue(state.tabs === tabsBefore, "writing a host must not rewrite tabs")
        assertEquals(listOf("shared-1"), state.sharedNoteHosts.map { it.id })
    }

    @Test
    fun upAnnOnAnUnknownIdIsStillANoOp() {
        val dir = createTempDirectory("shared-note-unknown").toFile()
        val state = stateWithTabsAndHost(dir)
        state.updateSettings { it.copy(autoExportNotes = false) }

        state.addNoteBlock("nope", "lost")

        assertTrue(state.sharedNoteHosts.single().annotations.blocks.isEmpty())
        assertTrue(state.tabs.all { it.annotations.blocks.isEmpty() })
    }

    // ── The notes pipeline: auto-export, overwrite prompt, open note ─────────────────────────────

    @Test
    fun autoExportWritesTheHostsMarkdownAndSidecarAndPinsItsTarget() {
        val dir = createTempDirectory("shared-note-export").toFile()
        val notes = File(dir, "notes")
        val state = state(dir, notes)
        state.addSharedNoteHost(sharedNoteHostTab(info(name = "Pairing bug")))

        state.addNoteBlock("shared-1", "cross-file finding")

        val md = File(notes, "Pairing_bug_analysis.md")
        waitUntil { md.exists() && md.readText().contains("cross-file finding") }
        waitUntil { File(notes, "Pairing_bug_analysis.ann").exists() }
        assertEquals("Pairing_bug_analysis.md", state.sharedNoteHost("shared-1")?.noteTargetName)
        assertNull(state.pendingNoteOverwrite)
    }

    @Test
    fun aCrossFileLogRefInAHostIsExportedFromItsFrozenRows() {
        val dir = createTempDirectory("shared-note-logref").toFile()
        val notes = File(dir, "notes")
        val state = state(dir, notes)
        state.tabs = listOf(mkTab("a", "a.log", listOf(entry(1, "boom in a"))))
        state.addSharedNoteHost(sharedNoteHostTab(info()))

        // The same call Compare mode uses for a note that lives in another tab than the rows' own.
        state.confirmAddAnn("shared-1", "a", listOf(1), "see a.log", "a.log")

        val block = state.sharedNoteHost("shared-1")!!.annotations.blocks.single() as AnnBlock.LogRef
        assertEquals("a", block.sourceTabId)
        assertEquals("a.log", block.sourceFilename)
        assertEquals("boom in a", block.sourceEntries?.single()?.msg)
        val md = File(notes, "Pairing_bug_analysis.md")
        waitUntil { md.exists() && md.readText().contains("boom in a") }
    }

    @Test
    fun aHostGoesThroughTheOverwritePromptLikeATab() {
        val dir = createTempDirectory("shared-note-overwrite").toFile()
        val notes = File(dir, "notes").apply { mkdirs() }
        val existing = File(notes, "Pairing_bug_analysis.md").apply { writeText("## someone else's note") }
        val state = state(dir, notes)
        state.addSharedNoteHost(sharedNoteHostTab(info(name = "Pairing bug")))

        state.addNoteBlock("shared-1", "brand new")

        val pending = assertNotNull(state.pendingNoteOverwrite)
        assertEquals("shared-1", pending.tabId)
        assertEquals(existing.absolutePath, pending.targetPath)
        assertTrue(state.sharedNoteHost("shared-1")!!.annotations.blocks.isEmpty(), "the edit is held back until the prompt is answered")

        state.confirmNoteOverwrite()

        assertNull(state.pendingNoteOverwrite)
        assertEquals(1, state.sharedNoteHost("shared-1")!!.annotations.blocks.size)
        assertEquals("Pairing_bug_analysis.md", state.sharedNoteHost("shared-1")?.noteTargetName)
        waitUntil { existing.readText().contains("brand new") }
    }

    @Test
    fun saveToANewFileOnAHostPicksTheNextFreeName() {
        val dir = createTempDirectory("shared-note-newfile").toFile()
        val notes = File(dir, "notes").apply { mkdirs() }
        File(notes, "Pairing_bug_analysis.md").writeText("taken")
        val state = state(dir, notes)
        state.addSharedNoteHost(sharedNoteHostTab(info(name = "Pairing bug")))
        state.addNoteBlock("shared-1", "brand new")
        assertNotNull(state.pendingNoteOverwrite)

        state.saveNotesToNewNoteFile()

        assertEquals("Pairing_bug_analysis_2.md", state.sharedNoteHost("shared-1")?.noteTargetName)
        waitUntil { File(notes, "Pairing_bug_analysis_2.md").exists() }
        assertEquals("taken", File(notes, "Pairing_bug_analysis.md").readText())
    }

    @Test
    fun openNoteFileLoadsAnAnnSidecarIntoAHost() {
        val dir = createTempDirectory("shared-note-open").toFile()
        val notes = File(dir, "notes").apply { mkdirs() }
        File(notes, "earlier.ann").writeText(Annotations(blocks = listOf(AnnBlock.Note("n1", "from disk"))).annotationsToken())
        val earlier = File(notes, "earlier.md").apply { writeText("# earlier") }
        val state = state(dir, notes)
        state.addSharedNoteHost(sharedNoteHostTab(info()))

        state.openNoteFile("shared-1", earlier)

        val host = assertNotNull(state.sharedNoteHost("shared-1"))
        assertEquals("from disk", (host.annotations.blocks.single() as AnnBlock.Note).text)
        assertEquals("earlier.md", host.noteTargetName)
        assertEquals("Pairing bug", host.sharedNote?.name)
        assertTrue(state.tabs.isEmpty())
    }

    // ── Guards: nothing that works on log rows, files or tabs treats a host as a tab ─────────────

    @Test
    fun mcpListTabsExcludesHostsAndLogToolsRejectAHostId() {
        val dir = createTempDirectory("shared-note-mcp").toFile()
        val state = stateWithTabsAndHost(dir)
        state.updateSettings { it.copy(autoExportNotes = false) }
        val gateway = IndagiumToolOperations(state).toolGateway

        val listed = (gateway.execute("list_tabs", emptyMap()) as List<*>).map { (it as Map<*, *>)["id"] }
        assertEquals(listOf("a", "b"), listed)

        for (tool in listOf("get_visible_lines", "get_filter", "start_tailing", "stop_tailing", "get_selection", "expand_all", "get_tags")) {
            val result = gateway.execute(tool, mapOf("tabId" to "shared-1")) as Map<*, *>
            assertEquals("no such tab: shared-1", result["error"], "$tool must treat a host as unknown")
        }
    }

    @Test
    fun everyMcpNoteRouteAcceptsAHostId() {
        val dir = createTempDirectory("shared-note-mcp-notes").toFile()
        val state = stateWithTabsAndHost(dir)
        state.updateSettings { it.copy(autoExportNotes = false) }
        val gateway = IndagiumToolOperations(state).toolGateway
        val host = "shared-1"

        fun call(tool: String, vararg args: Pair<String, Any?>) = gateway.execute(tool, mapOf("tabId" to host) + args) as Map<*, *>

        fun blocks() = state.sharedNoteHost(host)!!.annotations.blocks

        assertNull(call("add_text_note", "text" to "via mcp")["error"])
        assertNull(call("add_text_note", "text" to "second")["error"])
        val first = blocks().first().id
        val second = blocks()[1].id
        assertEquals(listOf("via mcp", "second"), blocks().filterIsInstance<AnnBlock.Note>().map { it.text })

        assertNull(call("update_note_block", "blockId" to first, "text" to "edited")["error"])
        assertEquals("edited", (blocks().first() as AnnBlock.Note).text)
        assertNull(call("move_note_block", "blockId" to second, "delta" to -1)["error"])
        assertEquals(second, blocks().first().id)
        assertNull(call("delete_note_block", "blockId" to second)["error"])
        assertEquals(listOf(first), blocks().map { it.id })

        val png = java.io.ByteArrayOutputStream().also {
            javax.imageio.ImageIO.write(java.awt.image.BufferedImage(4, 4, java.awt.image.BufferedImage.TYPE_INT_RGB), "png", it)
        }.toByteArray()
        val image = call("add_image_note", "imageBase64" to java.util.Base64.getEncoder().encodeToString(png), "caption" to "pic")
        assertNull(image["error"], image.toString())
        val imageId = blocks().last().id
        assertNull(call("update_note_caption", "blockId" to imageId, "caption" to "pic 2")["error"])
        assertEquals("pic 2", (blocks().last() as AnnBlock.Image).caption)

        assertNull(call("set_annotation_section", "section" to "prefix", "text" to "From shared")["error"])
        assertNull(call("append_annotation_section", "section" to "suffix", "text" to "tail")["error"])
        val sections = call("get_annotation_sections")
        assertEquals("From shared", sections["prefix"])
        assertEquals("tail", sections["suffix"])
        assertEquals(2, (call("get_annotation_blocks")["blocks"] as List<*>).size)
        assertNull(call("set_case_metadata", "appVersion" to "9.9")["error"])
        assertEquals("9.9", state.sharedNoteHost(host)!!.annotations.appVersion)
        assertEquals("", call("get_issue_description")["issueDescription"])

        // add_log_note copies rows out of a log tab (it runs behind withLoadedRows), so a host id is unknown to it; Phase 3 adds notes from a member tab.
        val logNote = gateway.execute("add_log_note", mapOf("tabId" to host, "lineIds" to listOf(1))) as Map<*, *>
        assertEquals("no such tab: $host", logNote["error"])

        assertNull(call("clear_all_notes")["error"])
        assertTrue(blocks().isEmpty())
        assertEquals(listOf("a", "b"), state.tabs.map { it.id })
    }

    @Test
    fun closeActivateAndResidencyIgnoreAHost() {
        val dir = createTempDirectory("shared-note-lifecycle").toFile()
        val state = stateWithTabsAndHost(dir)

        state.activateTab("shared-1")
        assertEquals("a", state.activeTabId, "a host is never the active tab")
        state.closeTab("shared-1")
        assertEquals(listOf("a", "b"), state.tabs.map { it.id })
        assertNull(state.ensureTabLoaded("shared-1"))
        assertNull(runBlocking { state.awaitPinnedLoadedTab("shared-1") }, "no rows to pin and no endless retry")
        assertNull(state.pinLoadedTabSnapshot("shared-1"))

        state.closeAllTabs()
        assertTrue(state.tabs.isEmpty())
        assertEquals(listOf("shared-1"), state.sharedNoteHosts.map { it.id }, "closing every tab keeps the shared notes")
        assertEquals(TabContentState.LOADED, state.sharedNoteHost("shared-1")?.contentState)
    }

    @Test
    fun navigatingToARowOfAHostOwnedRefNeverActivatesTheHost() {
        val dir = createTempDirectory("shared-note-nav").toFile()
        val state = stateWithTabsAndHost(dir)
        val ref = AnnBlock.LogRef("r1", listOf(1), "x", sourceTabId = null)

        state.requestAnnotationNavigation("shared-1", ref)

        assertEquals("a", state.activeTabId)
        assertNull(state.pendingAnnotationNavigation)
    }

    @Test
    fun logOnlyFeaturesAndMergeSkipAHost() {
        val dir = createTempDirectory("shared-note-merge").toFile()
        val state = stateWithTabsAndHost(dir)

        state.mergeTabs(listOf("a", "shared-1"), "Merged")
        state.toggleLevel("shared-1", LogLevel.E)
        state.toggleUnfiltered("shared-1")

        assertEquals(listOf("a", "b"), state.tabs.map { it.id }, "merging with only one real tab is a no-op")
        assertEquals(setOf("shared-1"), state.sharedNoteHosts.map { it.id }.toSet())
        assertFalse(state.sharedNoteHost("shared-1")!!.showUnfiltered)
    }

    // ── Autosave ─────────────────────────────────────────────────────────────────────────────────

    private fun richHostNotes() = Annotations(
        prefix = "From the pairing bug",
        suffix = "end",
        issueDescription = "pairing fails",
        blocks = listOf(
            AnnBlock.Note("n1", "text note | with a pipe\nand a newline"),
            AnnBlock.LogRef(
                id = "r1",
                logIds = listOf(1, 2),
                caption = "cross-file rows",
                sourceTabId = "b",
                sourceFilename = "b.log",
                sourceEntries = listOf(entry(1, "from b one"), entry(2, "from b two")),
            ),
        ),
    )

    @Test
    fun autosaveRoundTripKeepsHostsTheirNotesAndEveryTabsNotesView() {
        val dir = createTempDirectory("shared-note-roundtrip").toFile()
        val state = stateWithTabsAndHost(dir)
        state.updateSettings { it.copy(autoExportNotes = false) }
        state.upAnn("shared-1") { it.copy(annotations = richHostNotes()) }
        state.upTab("a") { it.copy(notesView = "shared-1") }
        state.autosaveNow()

        val restored = AppState(File(dir, "state.cache"), restoreOnCreate = true)

        assertEquals(listOf("a", "b"), restored.tabs.map { it.id }, "a host is never restored into tabs")
        val host = assertNotNull(restored.sharedNoteHost("shared-1"))
        assertEquals(state.sharedNoteHost("shared-1")!!.sharedNote, host.sharedNote)
        assertEquals(info(), host.sharedNote)
        assertEquals("Pairing bug", host.filename)
        assertEquals(richHostNotes(), host.annotations)
        assertEquals(TabContentState.LOADED, host.contentState)
        assertTrue(host.logData.isEmpty() && host.sourcePath == null)
        assertEquals("shared-1", restored.tab("a")?.notesView)
        assertNull(restored.tab("b")?.notesView)
        assertEquals(restored.sharedNoteHosts.map { it.id }, listOf("shared-1"))

        val nextFile = File(dir, "new.log").apply { writeText("new log\n") }
        val nextId = assertNotNull(restored.openFileWithLogFormatProfile(nextFile, "plain-text"))
        assertTrue(nextId.removePrefix("t").toInt() > 7, "new tabs cannot reuse a saved source ID")
        waitUntil { restored.tab(nextId)?.contentState == TabContentState.LOADED }
        restored.close()
        state.close()
        dir.deleteRecursively()
    }

    @Test
    fun theHostsLiveInAKeyLineBeforeTheTabsMarkerSoAnOlderReaderSkipsThem() {
        val dir = createTempDirectory("shared-note-layout").toFile()
        val state = stateWithTabsAndHost(dir)
        state.autosaveNow()

        val lines = File(dir, "state.cache").readLines()

        assertEquals(AUTOSAVE_MAGIC_CURRENT, lines.first())
        val hostLine = lines.indexOfFirst { it.startsWith("sharedNotes\t") }
        val tabsMarker = lines.indexOf("tabs")
        assertTrue(hostLine in 1 until tabsMarker, "sharedNotes must be a key line, not a tab record")
        assertTrue(lines.drop(tabsMarker + 1).all { it.startsWith("tab\t") }, "after the marker there are only tab records")
        assertEquals(2, lines.drop(tabsMarker + 1).size)
    }

    @Test
    fun aCacheWithoutSharedNotesWritesNoSharedNotesLineAndLoadsWithNoHosts() {
        val dir = createTempDirectory("shared-note-legacy").toFile()
        val state = state(dir)
        state.tabs = listOf(mkTab("a", "a.log", listOf(entry(1))).copy(sourcePath = File(dir, "a.log").absolutePath))
        state.activeTabId = "a"
        state.autosaveNow()
        val lines = File(dir, "state.cache").readLines()
        assertTrue(lines.none { it.startsWith("sharedNotes") })

        val restored = AppState(File(dir, "state.cache"), restoreOnCreate = true)

        assertEquals(listOf("a"), restored.tabs.map { it.id })
        assertTrue(restored.sharedNoteHosts.isEmpty())
        assertNull(restored.tab("a")?.notesView)
    }

    @Test
    fun aTabTokenWrittenBeforeTheNewFieldsStillLoads() {
        val dir = createTempDirectory("shared-note-oldtoken").toFile()
        val state = state(dir)
        state.tabs = listOf(mkTab("a", "a.log", listOf(entry(1))).copy(sourcePath = File(dir, "a.log").absolutePath))
        state.activeTabId = "a"
        state.autosaveNow()
        val cache = File(dir, "state.cache")
        // Keep only the fields written before shared notes were introduced.
        cache.writeText(
            cache.readLines().joinToString("\n") { line ->
                if (line.startsWith("tab\t")) {
                    val fields = line.removePrefix("tab\t").split("|")
                    assertTrue(fields.size > TAB_TOKEN_SHARED_NOTE_INDEX)
                    "tab\t" + fields.take(TAB_TOKEN_SHARED_NOTE_INDEX).joinToString("|")
                } else {
                    line
                }
            } + "\n",
        )

        val restored = AppState(cache, restoreOnCreate = true)

        assertEquals(listOf("a"), restored.tabs.map { it.id })
        assertNull(restored.tab("a")?.notesView)
        assertTrue(restored.sharedNoteHosts.isEmpty())
    }

    @Test
    fun aNotesViewThatPointsAtAMissingHostFallsBackToTheTabsOwnNote() {
        val dir = createTempDirectory("shared-note-dangling").toFile()
        val state = stateWithTabsAndHost(dir)
        state.upTab("a") { it.copy(notesView = "shared-1") }
        state.upTab("b") { it.copy(notesView = "shared-gone") }
        state.autosaveNow()

        val restored = AppState(File(dir, "state.cache"), restoreOnCreate = true)

        assertEquals("shared-1", restored.tab("a")?.notesView)
        assertNull(restored.tab("b")?.notesView, "the host named by b no longer exists")
    }

    @Test
    fun aHostRecordWithoutADescriptorIsDropped() {
        val dir = createTempDirectory("shared-note-badhost").toFile()
        val state = stateWithTabsAndHost(dir)
        state.autosaveNow()
        val cache = File(dir, "state.cache")
        cache.writeText(cache.readLines().joinToString("\n") { if (it.startsWith("sharedNotes\t")) "sharedNotes\t!!not-base64!!" else it } + "\n")

        val restored = AppState(cache, restoreOnCreate = true)

        assertEquals(listOf("a", "b"), restored.tabs.map { it.id })
        assertTrue(restored.sharedNoteHosts.isEmpty())
    }
}
