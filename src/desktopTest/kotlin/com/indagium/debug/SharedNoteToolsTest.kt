package com.indagium.debug

import com.indagium.ai.AiRun
import com.indagium.ai.AiRunEvent
import com.indagium.ai.AiToolCallBudget
import com.indagium.ai.AiToolExecutionCoordinator
import com.indagium.model.AnnBlock
import com.indagium.model.LogEntry
import com.indagium.model.LogLevel
import com.indagium.ui.AppState
import com.indagium.ui.mkTab
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import java.io.File
import kotlin.io.path.createTempDirectory
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** The shared-note MCP tools (list/create/set members) and the existing Notes tools addressed at a shared note's host id. */
private const val CONFIRMATION_WAIT_NANOS = 10_000_000_000L
private const val CONFIRMATION_POLL_MS = 10L

class SharedNoteToolsTest {
    private lateinit var dir: File
    private lateinit var state: AppState
    private lateinit var gateway: IndagiumToolGateway

    private fun entry(id: Int) = LogEntry(id, "10:00:00.%03d".format(id), LogLevel.I, "App", "line $id")

    @BeforeTest
    fun setUp() {
        dir = createTempDirectory("shared-note-tools").toFile()
        state = AppState(File(dir, "state.cache"), notesDir = File(dir, "notes"))
        state.updateSettings { it.copy(autoExportNotes = false) }
        state.tabs = listOf(
            mkTab("a", "a.log", listOf(entry(1), entry(2), entry(3))).copy(sourcePath = File(dir, "a.log").absolutePath),
            mkTab("b", "b.log", listOf(entry(1), entry(2))).copy(sourcePath = File(dir, "b.log").absolutePath),
            mkTab("c", "c.log", listOf(entry(1))),
        )
        state.activeTabId = "a"
        gateway = IndagiumToolOperations(state).toolGateway
    }

    @AfterTest
    fun tearDown() {
        state.close()
    }

    private fun call(tool: String, vararg args: Pair<String, Any?>): Map<*, *> = gateway.execute(tool, mapOf(*args)) as Map<*, *>

    private fun memberIds(result: Map<*, *>, key: String = "members"): List<String> =
        (result[key] as List<*>).map { (it as Map<*, *>)["tabId"] as String }

    private fun listed(vararg args: Pair<String, Any?>) = (call("list_shared_notes", *args)["sharedNotes"] as List<*>).map { it as Map<*, *> }

    @Test
    fun theThreeToolsAreInTheCatalogueAndClassifiedLikeTheOtherNotesTools() {
        val names = listOf("list_shared_notes", "create_shared_note", "set_shared_note_members")
        names.forEach { name ->
            assertTrue(gateway.tools.any { it.name == name }, "$name missing from the catalogue")
            // Reading is automatic; sharing a note or changing its members asks the user first in the in-app panel.
            val expected = if (name == "list_shared_notes") IndagiumToolActionPolicy.AUTOMATIC else IndagiumToolActionPolicy.CONFIRMATION_REQUIRED
            assertEquals(expected, gateway.actionPolicy(name), name)
            assertTrue(name !in PER_CALL_APPROVAL_MCP_TOOLS, name)
        }
        // Notes reads and writes never spend an AI run's analysis budget (the budget here is one call, already spent below).
        val budget = AiToolCallBudget(1)
        assertTrue(budget.tryConsume("get_visible_lines").allowed)
        names.forEach { assertTrue(budget.tryConsume(it).allowed, "$it must not spend the analysis budget") }
        assertEquals(1, budget.snapshot().evidenceUsed)
    }

    @Test
    fun createListAndFilterByTab() {
        assertTrue(listed().isEmpty())

        val created = call("create_shared_note", "name" to "  Pairing bug ", "memberTabIds" to listOf("a", "b"))
        assertNull(created["error"], created.toString())
        val hostId = created["hostId"] as String
        assertEquals("Pairing bug", created["name"])
        assertEquals(listOf("a", "b"), memberIds(created))
        assertEquals(hostId, state.sharedNoteHosts.single().id)
        assertEquals(hostId, state.tab("a")?.notesView, "the member's Notes tile is pointed at the note")

        val second = call("create_shared_note", "memberTabIds" to listOf("c"))
        assertEquals("Shared note", second["name"], "a missing name takes the UI's default")
        assertEquals("Shared note 2", call("create_shared_note", "name" to "", "memberTabIds" to listOf("c"))["name"])

        val all = listed()
        assertEquals(3, all.size)
        val pairing = all.single { it["hostId"] == hostId }
        assertEquals("Pairing bug", pairing["name"])
        assertEquals(listOf("a", "b"), memberIds(pairing))
        assertEquals(
            listOf("a.log", "b.log"),
            (pairing["members"] as List<*>).map { (it as Map<*, *>)["filename"] },
        )
        assertEquals(0, pairing["blockCount"])
        assertEquals(emptyList<Any?>(), pairing["closedMembers"])

        assertEquals(listOf(hostId), listed("tabId" to "a").map { it["hostId"] })
        assertEquals(2, listed("tabId" to "c").size)
        assertEquals("no such tab: nope", call("list_shared_notes", "tabId" to "nope")["error"])
    }

    @Test
    fun aClosedMemberIsListedWithItsFileAndPath() {
        val hostId = call("create_shared_note", "name" to "N", "memberTabIds" to listOf("a", "b"))["hostId"] as String

        state.closeTab("b")

        val note = listed().single { it["hostId"] == hostId }
        assertEquals(listOf("a"), memberIds(note))
        val closed = (note["closedMembers"] as List<*>).single() as Map<*, *>
        assertEquals("b", closed["tabId"])
        assertEquals("b.log", closed["filename"])
        assertEquals(File(dir, "b.log").absolutePath, closed["sourcePath"])
    }

    @Test
    fun createRefusesBadArgumentsAndChangesNothing() {
        assertEquals("memberTabIds must name at least one open tab", call("create_shared_note", "name" to "x")["error"])
        assertEquals(
            "memberTabIds must name at least one open tab",
            call("create_shared_note", "memberTabIds" to emptyList<String>())["error"],
        )
        assertEquals("no such tab: nope, zzz", call("create_shared_note", "memberTabIds" to listOf("a", "nope", "zzz"))["error"])
        assertEquals("no such tab: nope", call("create_shared_note", "memberTabIds" to listOf("a"), "fromTabId" to "nope")["error"])
        // A shared note is not a tab you can be a member of.
        val hostId = call("create_shared_note", "memberTabIds" to listOf("a"))["hostId"] as String
        assertEquals("no such tab: $hostId", call("create_shared_note", "memberTabIds" to listOf(hostId))["error"])
        assertEquals(1, state.sharedNoteHosts.size)
        assertNull(state.tab("b")?.notesView)
    }

    @Test
    fun fromTabIdSharesThatTabsExistingNote() {
        assertNull(call("add_text_note", "tabId" to "a", "text" to "private finding")["error"])

        val created = call("create_shared_note", "name" to "From a", "memberTabIds" to listOf("b"), "fromTabId" to "a")

        assertNull(created["error"], created.toString())
        assertEquals(listOf("a", "b"), memberIds(created).sorted(), "the source tab is always a member")
        val hostId = created["hostId"] as String
        assertEquals(listOf("private finding"), state.sharedNoteHost(hostId)!!.annotations.blocks.filterIsInstance<AnnBlock.Note>().map { it.text })
        assertTrue(state.tab("a")!!.annotations.blocks.isEmpty(), "the tab gets a fresh private note")
        assertEquals(1, listed().single()["blockCount"])
    }

    @Test
    fun inAppNoteCallsCanUseAnExplicitHostOnlyFromAMemberTab() = runBlocking {
        val hostId = call("create_shared_note", "memberTabIds" to listOf("a", "b"))["hostId"] as String
        val coordinator = AiToolExecutionCoordinator(gateway)
        val member = AiRun(tabId = "a", maxToolCalls = 10)
        val outsider = AiRun(tabId = "c", maxToolCalls = 10)

        val written = coordinator.executeManaged(member, "add_text_note", mapOf("tabId" to hostId, "text" to "shared finding"))
        val read = coordinator.executeManaged(member, "get_annotation_blocks", mapOf("tabId" to hostId))
        val rejected = coordinator.executeManaged(outsider, "add_text_note", mapOf("tabId" to hostId, "text" to "wrong owner"))

        assertTrue(written.content.contains("ok=true"), written.content)
        assertTrue(read.content.contains("shared finding"), read.content)
        assertTrue(rejected.content.contains("unavailable"), rejected.content)
        assertEquals(listOf("shared finding"), state.sharedNoteHost(hostId)!!.annotations.blocks.filterIsInstance<AnnBlock.Note>().map { it.text })
        assertTrue(state.tab("a")!!.annotations.blocks.isEmpty())
        assertTrue(state.tab("c")!!.annotations.blocks.isEmpty())
    }

    @Test
    fun inAppMembershipWritesRequireAnExistingMembershipOrTheRunsOwnPrivateNote() = runBlocking {
        val hostId = call("create_shared_note", "memberTabIds" to listOf("a", "b"))["hostId"] as String
        call("add_text_note", "tabId" to "c", "text" to "private finding")
        val coordinator = AiToolExecutionCoordinator(gateway)
        val member = AiRun(tabId = "a", maxToolCalls = 10)
        val outsider = AiRun(tabId = "c", maxToolCalls = 10)

        val change = mapOf("hostId" to hostId, "memberTabIds" to listOf("a", "c"))
        val rejected = confirmedCall(coordinator, outsider, "set_shared_note_members", change)
        assertTrue(rejected.content.contains("unavailable"), rejected.content)
        assertEquals(listOf("a", "b"), state.sharedNoteHost(hostId)?.sharedNote?.memberTabIds)
        assertTrue(coordinator.executeManaged(outsider, "get_annotation_blocks", mapOf("tabId" to hostId)).content.contains("unavailable"))

        val rejectedMove = confirmedCall(
            coordinator, member, "create_shared_note", mapOf("fromTabId" to " c ", "memberTabIds" to listOf("a")),
        )
        assertTrue(rejectedMove.content.contains("own tab"), rejectedMove.content)
        val rejectedCreate = confirmedCall(coordinator, member, "create_shared_note", mapOf("memberTabIds" to listOf("b", "c")))
        assertTrue(rejectedCreate.content.contains("must include its tab"), rejectedCreate.content)
        assertEquals(1, state.sharedNoteHosts.size)
        assertEquals("private finding", (state.tab("c")!!.annotations.blocks.single() as AnnBlock.Note).text)

        val allowed = confirmedCall(coordinator, member, "set_shared_note_members", change)
        assertTrue(allowed.content.contains("ok=true"), allowed.content)
        assertEquals(listOf("a", "c"), state.sharedNoteHost(hostId)?.sharedNote?.memberTabIds)
        val moved = confirmedCall(
            coordinator, outsider, "create_shared_note", mapOf("fromTabId" to "c", "memberTabIds" to listOf("a")),
        )
        assertTrue(moved.content.contains("ok=true"), moved.content)
        assertTrue(state.tab("c")!!.annotations.blocks.isEmpty())
    }

    /** Runs an in-app membership call, which asks first, and accepts its confirmation as the user would. */
    private suspend fun confirmedCall(
        coordinator: AiToolExecutionCoordinator,
        run: AiRun,
        name: String,
        arguments: Map<String, Any?>,
    ) = answeredCall(coordinator, run, name, arguments, accept = true)

    private suspend fun answeredCall(
        coordinator: AiToolExecutionCoordinator,
        run: AiRun,
        name: String,
        arguments: Map<String, Any?>,
        accept: Boolean,
    ) = coroutineScope {
        val result = async(Dispatchers.Default) { coordinator.executeManaged(run, name, arguments) }
        val deadline = System.nanoTime() + CONFIRMATION_WAIT_NANOS
        while (run.confirmations.isEmpty() && !result.isCompleted) {
            check(System.nanoTime() < deadline) { "$name never asked for confirmation" }
            delay(CONFIRMATION_POLL_MS)
        }
        run.confirmations.values.firstOrNull()?.complete(accept)
        result.await()
    }

    @Test
    fun inAppMembershipChangesAskFirstAndADeclineChangesNothing() = runBlocking {
        val hostId = call("create_shared_note", "memberTabIds" to listOf("a", "b"))["hostId"] as String
        val coordinator = AiToolExecutionCoordinator(gateway)
        val member = AiRun(tabId = "a", maxToolCalls = 10)
        assertEquals(IndagiumToolActionPolicy.CONFIRMATION_REQUIRED, gateway.actionPolicy("set_shared_note_members"))
        assertEquals(IndagiumToolActionPolicy.CONFIRMATION_REQUIRED, gateway.actionPolicy("create_shared_note"))

        val declined = answeredCall(
            coordinator, member, "set_shared_note_members", mapOf("hostId" to hostId, "memberTabIds" to listOf("a")), accept = false,
        )
        assertTrue(declined.content.contains("declined"), declined.content)
        assertTrue(member.history.any { it is AiRunEvent.ConfirmationRequired })
        assertEquals(listOf("a", "b"), state.sharedNoteHost(hostId)?.sharedNote?.memberTabIds)

        val declinedCreate = answeredCall(coordinator, member, "create_shared_note", mapOf("memberTabIds" to listOf("a", "c")), accept = false)
        assertTrue(declinedCreate.content.contains("declined"), declinedCreate.content)
        assertEquals(1, state.sharedNoteHosts.size)
    }

    @Test
    fun loadAnnotationsAddressedAtASharedNoteHostReplacesTheHostsNote() {
        val hostId = call("create_shared_note", "memberTabIds" to listOf("a", "b"))["hostId"] as String
        call("add_text_note", "tabId" to hostId, "text" to "original")
        call("add_text_note", "tabId" to "c", "text" to "from the sidecar")
        val sidecar = File(dir, "from-c.ann")
        assertTrue(state.saveAnnotationsTo("c", sidecar))

        val result = call("load_annotations", "tabId" to hostId, "path" to sidecar.absolutePath)

        assertEquals(true, result["ok"], result.toString())
        assertEquals("from the sidecar", (state.sharedNoteHost(hostId)!!.annotations.blocks.single() as AnnBlock.Note).text)
        assertTrue(state.tabs.none { it.id == hostId }, "the host never enters the tab list")
        assertEquals("annotations were not loaded", call("load_annotations", "tabId" to "nope", "path" to sidecar.absolutePath)["error"])
    }

    @Test
    fun revokingMembershipBeforeAContentWriteCommitsPreventsTheWrite() = runBlocking {
        val hostId = call("create_shared_note", "memberTabIds" to listOf("a", "b"))["hostId"] as String
        call("add_text_note", "tabId" to hostId, "text" to "original")
        call("add_text_note", "tabId" to "c", "text" to "replacement")
        val sidecar = File(dir, "replacement.ann")
        assertTrue(state.saveAnnotationsTo("c", sidecar))

        val started = CompletableDeferred<Unit>()
        val resume = CompletableDeferred<Unit>()
        val descriptor = gateway.tools.single { it.name == "load_annotations" }
        val pausedGateway = IndagiumToolGateway(
            catalog = listOf(descriptor),
            handlers = emptyMap(),
            suspendHandlers = mapOf(descriptor.name to {
                started.complete(Unit)
                resume.await()
                withContext(Dispatchers.Default) { state.loadAnnotationsFrom(hostId, sidecar) }
            }),
        )
        val args = mapOf("tabId" to hostId, "path" to sidecar.absolutePath)
        val loading = async { pausedGateway.executeSuspending(descriptor.name, args, inAppTabId = "a") }
        started.await()
        call("set_shared_note_members", "hostId" to hostId, "memberTabIds" to listOf("b"))
        resume.complete(Unit)

        assertTrue((loading.await() as Map<*, *>)["error"].toString().contains("unavailable"))
        assertEquals("original", (state.sharedNoteHost(hostId)!!.annotations.blocks.single() as AnnBlock.Note).text)
        val deniedEdit = gateway.executeSuspending("add_text_note", mapOf("tabId" to hostId, "text" to "denied"), inAppTabId = "a")
        assertTrue((deniedEdit as Map<*, *>)["error"].toString().contains("unavailable"))
        val allowedLoad = gateway.executeSuspending(descriptor.name, args, inAppTabId = "b")
        assertEquals(true, (allowedLoad as Map<*, *>)["ok"])
        assertEquals("replacement", (state.sharedNoteHost(hostId)!!.annotations.blocks.single() as AnnBlock.Note).text)
    }

    @Test
    fun anInAppSharedLogNoteTakesItsRowsFromTheRunsOwnTabWhateverSourceTabIdTheModelSent() = runBlocking {
        val hostId = call("create_shared_note", "memberTabIds" to listOf("a", "b"))["hostId"] as String
        val coordinator = AiToolExecutionCoordinator(gateway)
        val run = AiRun(tabId = "a", maxToolCalls = 10)

        // Leaving sourceTabId out works (it would be an error for an external client) ...
        val omitted = coordinator.executeManaged(run, "add_log_note", mapOf("tabId" to hostId, "lineIds" to listOf(1), "caption" to "omitted"))
        // ... and another member's tab is replaced by the run's own.
        val other = coordinator.executeManaged(
            run, "add_log_note",
            mapOf("tabId" to hostId, "sourceTabId" to "b", "lineIds" to listOf(1), "caption" to "from the other member"),
        )

        assertTrue(omitted.content.contains("ok=true"), omitted.content)
        assertTrue(other.content.contains("ok=true"), other.content)
        val refs = state.sharedNoteHost(hostId)!!.annotations.blocks.filterIsInstance<AnnBlock.LogRef>().associateBy { it.caption }
        assertEquals(setOf("omitted", "from the other member"), refs.keys)
        assertTrue(refs.values.all { it.sourceTabId == "a" }, refs.values.map { it.sourceTabId }.toString())
        // An external client keeps its explicit, required sourceTabId.
        val external = gateway.executeSuspending("add_log_note", mapOf("tabId" to hostId, "lineIds" to listOf(1), "caption" to "external")) as Map<*, *>
        assertTrue(external["error"].toString().contains("sourceTabId"), external.toString())
    }

    @Test
    fun concurrentSharedLogNotesReturnTheirOwnIdsAndLeaveTheOpenDialogAlone() = runBlocking {
        val hostId = call("create_shared_note", "memberTabIds" to listOf("a", "b"))["hostId"] as String
        state.requestAddAnn("a", listOf(1))
        val pending = assertNotNull(state.addAnnRequest)

        val results = (1..12).map { index ->
            async(Dispatchers.Default) {
                val caption = "reference $index"
                val result = gateway.executeSuspending(
                    "add_log_note",
                    mapOf("tabId" to hostId, "sourceTabId" to "a", "lineIds" to listOf(1), "caption" to caption),
                ) as Map<*, *>
                caption to assertNotNull(result["blockId"], result.toString())
            }
        }.awaitAll()

        val actual = state.sharedNoteHost(hostId)!!.annotations.blocks.filterIsInstance<AnnBlock.LogRef>().associate { it.caption to it.id }
        assertEquals(results.toMap(), actual)
        assertEquals(pending, state.addAnnRequest)
    }

    @Test
    fun setMembersReplacesTheOpenMembers() {
        val hostId = call("create_shared_note", "memberTabIds" to listOf("a", "b"))["hostId"] as String

        val result = call("set_shared_note_members", "hostId" to hostId, "memberTabIds" to listOf("b", "c"))

        assertNull(result["error"], result.toString())
        assertEquals(listOf("b", "c"), memberIds(result))
        assertEquals(emptyList<Any?>(), result["closedMembers"])
        assertEquals(listOf("b", "c"), state.sharedNoteHost(hostId)!!.sharedNote!!.memberTabIds)
        assertNull(state.tab("a")?.notesView, "a removed member goes back to its own note")
        assertEquals(hostId, state.tab("b")?.notesView, "a kept member still shows the note")
        assertNull(state.tab("c")?.notesView, "an added member keeps what it shows")

        val none = call("set_shared_note_members", "hostId" to hostId, "memberTabIds" to emptyList<String>())
        assertNull(none["error"], none.toString())
        assertTrue(memberIds(none).isEmpty())
        assertEquals(listOf(hostId), state.sharedNoteHosts.map { it.id }, "a note without members stays")
    }

    @Test
    fun setMembersRefusesBadArgumentsAndChangesNothing() {
        val hostId = call("create_shared_note", "memberTabIds" to listOf("a"))["hostId"] as String

        assertEquals("no such shared note: nope", call("set_shared_note_members", "hostId" to "nope", "memberTabIds" to listOf("a"))["error"])
        assertEquals("missing hostId", call("set_shared_note_members", "memberTabIds" to listOf("a"))["error"])
        assertTrue((call("set_shared_note_members", "hostId" to hostId)["error"] as String).startsWith("missing memberTabIds"))
        assertEquals("no such tab: zzz", call("set_shared_note_members", "hostId" to hostId, "memberTabIds" to listOf("b", "zzz"))["error"])
        assertEquals("no such shared note: a", call("set_shared_note_members", "hostId" to "a", "memberTabIds" to listOf("b"))["error"])

        assertEquals(listOf("a"), state.sharedNoteHost(hostId)!!.sharedNote!!.memberTabIds)
    }

    @Test
    fun setMembersRefusesABlankOrNonStringEntryInsteadOfClearingTheMembers() {
        val hostId = call("create_shared_note", "memberTabIds" to listOf("a", "b"))["hostId"] as String

        // A blank id once vanished in the filter, leaving [] - the request to remove every open member.
        assertEquals("memberTabIds[0] is blank", call("set_shared_note_members", "hostId" to hostId, "memberTabIds" to listOf(" "))["error"])
        assertEquals("memberTabIds[1] is blank", call("set_shared_note_members", "hostId" to hostId, "memberTabIds" to listOf("a", ""))["error"])
        assertEquals(
            "memberTabIds[0] must be a string tab id",
            call("set_shared_note_members", "hostId" to hostId, "memberTabIds" to listOf(1))["error"],
        )
        assertEquals(
            "memberTabIds[1] must be a string tab id",
            call("set_shared_note_members", "hostId" to hostId, "memberTabIds" to listOf("a", null))["error"],
        )

        assertEquals(listOf("a", "b"), state.sharedNoteHost(hostId)!!.sharedNote!!.memberTabIds, "nothing changed")
        assertEquals(hostId, state.tab("a")?.notesView)
        assertTrue(memberIds(call("set_shared_note_members", "hostId" to hostId, "memberTabIds" to emptyList<String>())).isEmpty())
    }

    @Test
    fun createRefusesABlankOrNonStringMemberEntry() {
        assertEquals("memberTabIds[0] is blank", call("create_shared_note", "memberTabIds" to listOf("  "))["error"])
        assertEquals("memberTabIds[1] is blank", call("create_shared_note", "memberTabIds" to listOf("a", ""))["error"])
        assertEquals("memberTabIds[0] must be a string tab id", call("create_shared_note", "memberTabIds" to listOf(7))["error"])
        assertTrue(state.sharedNoteHosts.isEmpty())
    }

    @Test
    fun theNotesToolsTakeAHostIdAsTabId() {
        val hostId = call("create_shared_note", "name" to "N", "memberTabIds" to listOf("a", "b"))["hostId"] as String

        val text = call("add_text_note", "tabId" to hostId, "text" to "shared finding")
        assertNull(text["error"], text.toString())
        assertEquals(hostId, text["tabId"])

        val blocks = call("get_annotation_blocks", "tabId" to hostId)
        assertNull(blocks["error"], blocks.toString())
        assertEquals(hostId, blocks["tabId"])
        val first = (blocks["blocks"] as List<*>).single() as Map<*, *>
        assertEquals("text", first["type"])
        assertEquals("shared finding", first["text"])
        assertTrue(call("get_annotation_blocks", "tabId" to "a").let { (it["blocks"] as List<*>).isEmpty() }, "the tab's private note is separate")
        assertEquals(1, listed().single()["blockCount"])
    }

    @Test
    fun addLogNoteToASharedNoteCitesRowsOfTheSourceTab() {
        val hostId = call("create_shared_note", "name" to "N", "memberTabIds" to listOf("a", "b"))["hostId"] as String
        state.upTab("a") { it.copy(selectedLogSectionId = "sec-1") }

        val added = call("add_log_note", "tabId" to hostId, "sourceTabId" to "a", "lineIds" to listOf(2, 3, 99), "caption" to "the crash")

        assertNull(added["error"], added.toString())
        assertEquals(hostId, added["tabId"])
        assertEquals("a", added["sourceTabId"])
        val blocks = (call("get_annotation_blocks", "tabId" to hostId)["blocks"] as List<*>).map { it as Map<*, *> }
        val log = blocks.single()
        assertEquals(added["blockId"], log["id"])
        assertEquals("log", log["type"])
        assertEquals(listOf(2, 3), log["lineIds"], "ids that are not rows of the source tab are dropped")
        assertEquals("the crash", log["caption"])
        assertEquals("a", log["sourceTabId"])
        assertEquals("a.log", log["sourceFilename"])
        assertEquals("sec-1", log["sourceSectionId"])
        assertTrue(state.tab("a")!!.annotations.blocks.isEmpty(), "nothing lands in the source tab's private note")
        val stored = state.sharedNoteHost(hostId)!!.annotations.blocks.single() as AnnBlock.LogRef
        assertEquals(listOf(2, 3), stored.sourceEntries?.map { it.id }, "the rows are frozen into the block")
    }

    @Test
    fun privateLogNoteCapturesTheSectionAlongWithTheRows() {
        state.upTab("a") { it.copy(selectedLogSectionId = "first") }
        val added = call("add_log_note", "tabId" to "a", "lineIds" to listOf(2, 99))
        assertNull(added["error"], added.toString())
        state.upTab("a") { it.copy(selectedLogSectionId = "second") }

        val stored = state.tab("a")!!.annotations.blocks.single() as AnnBlock.LogRef
        assertEquals("first", stored.sourceSectionId)
        assertNull(stored.sourceTabId)
        assertEquals(listOf(2), stored.sourceEntries?.map { it.id })
    }

    @Test
    fun addLogNoteToASharedNoteExplainsWhatIsMissing() {
        val hostId = call("create_shared_note", "memberTabIds" to listOf("a"))["hostId"] as String

        assertTrue((call("add_log_note", "tabId" to hostId, "lineIds" to listOf(1))["error"] as String).contains("sourceTabId"))
        assertEquals("no such tab: nope", call("add_log_note", "tabId" to hostId, "sourceTabId" to "nope", "lineIds" to listOf(1))["error"])
        assertEquals(
            "none of lineIds exist in tab a",
            call("add_log_note", "tabId" to hostId, "sourceTabId" to "a", "lineIds" to listOf(77))["error"],
        )
        // As in the app, only a member tab writes into the note.
        assertTrue((call("add_log_note", "tabId" to hostId, "sourceTabId" to "b", "lineIds" to listOf(1))["error"] as String).contains("not a member"))
        assertEquals("no such tab: nope", call("add_log_note", "tabId" to "nope", "lineIds" to listOf(1))["error"])
        assertTrue(state.sharedNoteHost(hostId)!!.annotations.blocks.isEmpty())
    }

    @Test
    fun anOrdinaryTabsAddLogNoteIsUnchanged() {
        val added = call("add_log_note", "tabId" to "a", "lineIds" to listOf(1, 2), "caption" to "plain")

        assertNull(added["error"], added.toString())
        val block = state.tab("a")!!.annotations.blocks.single() as AnnBlock.LogRef
        assertEquals(listOf(1, 2), block.logIds)
        assertNull(block.sourceTabId)
    }

    @Test
    fun getAnnotationBlocksReportsTheFieldsAReferenceCites() {
        val hostId = call("create_shared_note", "memberTabIds" to listOf("a"))["hostId"] as String
        val fields = listOf(AnnBlock.RecordFieldRef(listOf("user", "id"), "42"), AnnBlock.RecordFieldRef(listOf("state"), "\"open\""))
        state.upAnn(hostId) { host ->
            val ref = AnnBlock.LogRef(
                id = "r1", logIds = listOf(1), caption = "", sourceTabId = "a", sourceFilename = "a.log",
                sourceSectionId = "sec-9", recordFields = fields,
            )
            host.copy(annotations = host.annotations.copy(blocks = host.annotations.blocks + ref))
        }

        val block = (call("get_annotation_blocks", "tabId" to hostId)["blocks"] as List<*>).single() as Map<*, *>

        assertEquals("a", block["sourceTabId"])
        assertEquals("a.log", block["sourceFilename"])
        assertEquals("sec-9", block["sourceSectionId"])
        val reported = (block["recordFields"] as List<*>).map { it as Map<*, *> }
        assertEquals(listOf(listOf("user", "id"), listOf("state")), reported.map { it["path"] })
        assertNotNull(reported.first()["json"])
    }
}
