package com.indagium.ui

import androidx.compose.ui.graphics.toArgb
import com.indagium.model.LogFormat
import com.indagium.utils.ParsedLog
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.io.path.createTempDirectory
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class TabGroupsStateTest {
    private companion object {
        // Not in any palette: only a colorArgb can carry it.
        const val CUSTOM_GROUP_COLOR = 0xFF123456.toInt()
        const val NEAR_LEGACY_BLUE = 0xFF3A80F0.toInt()
    }

    private fun stateFor(
        dir: File,
        restore: Boolean = false,
        libraryFile: File = File(dir, "diagram-library.cache"),
        parser: (File) -> ParsedLog = { ParsedLog(LogFormat.LOGCAT, emptyList()) },
    ) = AppState(
        autosaveFile = File(dir, "state.cache"),
        restoreOnCreate = restore,
        parser = parser,
        notesDir = File(dir, "notes"),
        archiveCacheDir = File(dir, "archive-cache"),
        customCommandsDir = File(dir, "commands"),
        autoExportNotes = false,
        controlTokenFile = File(dir, "control.token"),
        sourceIndexFile = File(dir, "source-index"),
        diagramLibraryStore = DiagramLibraryStore(libraryFile),
    )

    private fun await(timeoutMs: Long = 5_000, condition: () -> Boolean) {
        val deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(timeoutMs)
        while (!condition() && System.nanoTime() < deadline) Thread.sleep(10)
        assertTrue(condition(), "condition did not become true within ${timeoutMs}ms")
    }

    private fun workspace(id: String) = emptyWorkspaceTab().copy(id = id, filename = id)

    @Test
    fun groupsUsePaletteDefaultsAndKeepTheirMembersContiguousAfterManualUngroup() {
        val dir = createTempDirectory("tab-groups-order").toFile()
        val state = stateFor(dir)
        try {
            state.tabs = listOf(workspace("a"), workspace("b"), workspace("c"))
            state.commitTabOrder(listOf(TabRef.Log("a"), TabRef.Log("b"), TabRef.Log("c")))

            val id = state.createTabGroup(TabRef.Log("a"))!!
            assertEquals("a", state.tabGroups.single().name, "a new group is named after its tab")
            assertEquals(HL_COLORS.first().toArgb(), state.tabGroups.single().colorArgb)
            assertEquals(TabGroupIcon.NONE, state.tabGroups.single().icon)
            state.assignTabToGroup(TabRef.Log("c"), id, before = TabRef.Log("a"))
            state.assignTabToGroup(TabRef.Log("b"), id)

            assertEquals(
                listOf(TabRef.Log("c"), TabRef.Log("a"), TabRef.Log("b")),
                state.reconciledTabOrder(),
            )
            assertEquals(listOf(TabRef.Log("c"), TabRef.Log("a"), TabRef.Log("b")), state.tabGroups.single().members)

            // Removing the middle member compresses the remaining group run together and leaves
            // the now-ungrouped tab adjacent to it.
            state.removeTabFromGroup(TabRef.Log("a"))
            assertEquals(listOf(TabRef.Log("c"), TabRef.Log("b")), state.tabGroups.single().members)
            assertEquals(listOf(TabRef.Log("c"), TabRef.Log("b"), TabRef.Log("a")), state.reconciledTabOrder())
        } finally {
            state.close()
        }
    }

    @Test
    fun groupCreationMovesAnAlreadyGroupedMemberAndExcludesLauncherAndTests() {
        val dir = createTempDirectory("tab-groups-reassign").toFile()
        val state = stateFor(dir)
        try {
            val launcherId = "capture-launcher-home"
            state.tabs = listOf(workspace("log"), workspace(launcherId).copy(isCaptureLauncher = true))
            state.testsTabOpen = true
            state.commitTabOrder(listOf(TabRef.Log("log"), TabRef.Log(launcherId), TabRef.Tests))

            assertNull(state.createTabGroup(TabRef.Log(launcherId)))
            assertNull(state.createTabGroup(TabRef.Tests))
            val first = state.createTabGroup(TabRef.Log("log"))!!
            val second = state.createTabGroup(TabRef.Log("log"))!!

            assertFalse(first == second)
            assertEquals(1, state.tabGroups.size)
            assertEquals(second, state.tabGroups.single().id)
            assertEquals(listOf(TabRef.Log("log")), state.tabGroups.single().members)
            assertTrue(TabRef.Tests in state.reconciledTabOrder())
        } finally {
            state.close()
        }
    }

    @Test
    fun jsonRestoreDropsMissingDuplicateAndNonGroupableMemberships() {
        val dir = createTempDirectory("tab-groups-prune").toFile()
        val state = stateFor(dir)
        try {
            val launcherId = "capture-launcher-home"
            state.tabs = listOf(workspace("log"), workspace("other"), workspace(launcherId).copy(isCaptureLauncher = true))
            state.testsTabOpen = true
            state.commitTabOrder(listOf(TabRef.Log("log"), TabRef.Log("other"), TabRef.Log(launcherId), TabRef.Tests))

            state.restoreTabGroups(
                """
                {"groups":[
                  {"id":"keep","name":"Keep","color":"PURPLE","collapsed":true,"members":[
                    {"kind":"L","id":"log"},{"kind":"L","id":"log"},
                    {"kind":"L","id":"gone"},{"kind":"L","id":"capture-launcher-home"},
                    {"kind":"T","id":"tests"},{"kind":"D","id":"missing-library-item"}]},
                  {"id":"keep","name":"Duplicate group","color":"RED","collapsed":false,"members":[{"kind":"L","id":"log"}]},
                  {"id":"empty","name":"Empty","color":"GREEN","collapsed":false,"members":[{"kind":"D","id":"gone"}]},
                  {"id":"malformed","name":"Malformed","color":5,"collapsed":"true",
                   "textSize":"EXTRA","icon":"UNKNOWN","members":[{"kind":"L","id":"other"}]}
                ]}
                """.trimIndent(),
            )

            assertEquals(2, state.tabGroups.size)
            val keep = state.tabGroups.single { it.id == "keep" }
            assertEquals("Keep", keep.name)
            assertEquals(LegacyTabGroupColor.PURPLE.argb, keep.colorArgb, "an old file's palette name maps to its old colour")
            assertTrue(keep.collapsed)
            assertEquals(TabGroupIcon.NONE, keep.icon)
            assertEquals(listOf(TabRef.Log("log")), keep.members)
            val malformed = state.tabGroups.single { it.id == "malformed" }
            assertEquals(LegacyTabGroupColor.BLUE.argb, malformed.colorArgb)
            assertFalse(malformed.collapsed)
            assertEquals(TabGroupIcon.NONE, malformed.icon)
            assertEquals(listOf(TabRef.Log("other")), malformed.members)
        } finally {
            state.close()
        }
    }

    @Test
    fun assigningToMissingGroupIsANoOpAndOpeningAnExistingTabKeepsItsMembership() {
        val dir = createTempDirectory("tab-groups-existing").toFile()
        val existingFile = File(dir, "existing.log").apply { writeText("placeholder") }
        val state = stateFor(dir)
        try {
            state.tabs = listOf(
                workspace("origin"),
                workspace("existing").copy(sourcePath = existingFile.absolutePath),
            )
            state.commitTabOrder(listOf(TabRef.Log("origin"), TabRef.Log("existing")))
            val originGroup = state.createTabGroup(TabRef.Log("origin"))!!
            val existingGroup = state.createTabGroup(TabRef.Log("existing"))!!
            state.toggleTabGroupCollapsed(existingGroup)
            val before = state.tabGroups

            state.assignTabToGroup(TabRef.Log("origin"), id = "missing-group")
            assertEquals(before, state.tabGroups)

            assertEquals("existing", state.openFile(existingFile, TabRef.Log("origin")))
            assertEquals(originGroup, state.tabGroupOf(TabRef.Log("origin"))?.id)
            assertEquals(existingGroup, state.tabGroupOf(TabRef.Log("existing"))?.id)
            assertFalse(state.tabGroups.single { it.id == existingGroup }.collapsed)
        } finally {
            state.close()
        }
    }

    @Test
    fun autosaveRestoresMixedLogDiagramGroupAndKeepsCollapsedActiveGroupCollapsed() {
        val dir = createTempDirectory("tab-groups-autosave").toFile()
        val logFile = File(dir, "app.log").apply { writeText("placeholder") }
        val libraryFile = File(dir, "diagram-library.cache")
        val state = stateFor(dir, libraryFile = libraryFile)
        try {
            state.tabs = listOf(workspace("log").copy(sourcePath = logFile.absolutePath))
            state.commitTabOrder(listOf(TabRef.Log("log")))
            state.activateTab("log")
            val groupId = state.createTabGroup(TabRef.Log("log"))!!
            val diagramSessionId = state.seq3Sessions.beginEmpty("log")!!
            val savedLibraryId = state.seq3Sessions.sessions.single { it.id == diagramSessionId }.libraryItemId!!
            state.setTabGroupColor(groupId, CUSTOM_GROUP_COLOR)
            state.setTabGroupIcon(groupId, TabGroupIcon.DEVICE)
            state.renameTabGroup(groupId, "Investigation")
            state.toggleTabGroupCollapsed(groupId)
            assertTrue((state.activeSurface as? ActiveSurface.Diagram3)?.sessionId == diagramSessionId)
            state.autosaveNow()

            val restored = stateFor(dir, restore = true, libraryFile = libraryFile)
            try {
                val restoredSession = restored.seq3Sessions.sessions.single { it.libraryItemId == savedLibraryId }
                val group = restored.tabGroups.single()
                assertEquals(groupId, group.id)
                assertEquals("Investigation", group.name)
                assertEquals(CUSTOM_GROUP_COLOR, group.colorArgb)
                assertEquals(TabGroupIcon.DEVICE, group.icon)
                assertTrue(group.collapsed)
                assertEquals(
                    listOf(TabRef.Log("log"), TabRef.Diagram(restoredSession.id)),
                    group.members,
                )
                assertEquals(
                    listOf(TabRef.Log("log"), TabRef.Diagram(restoredSession.id)),
                    restored.reconciledTabOrder(),
                )
                assertEquals(ActiveSurface.Diagram3(restoredSession.id), restored.activeSurface)

                restored.activateStripTab(TabRef.Diagram(restoredSession.id))
                assertFalse(restored.tabGroups.single().collapsed)
            } finally {
                restored.close()
            }
        } finally {
            state.close()
        }
    }

    @Test
    fun asyncOpenUsesTheGroupCapturedAtStartAndDoesNotResurrectADeletedGroup() {
        val dir = createTempDirectory("tab-groups-origin").toFile()
        val waitingFiles = setOf("inherit.log", "deleted.log")
        val entered = waitingFiles.associateWith { CountDownLatch(1) }
        val releases = waitingFiles.associateWith { CountDownLatch(1) }
        val state = stateFor(
            dir,
            parser = { file ->
                if (file.name in waitingFiles) {
                    entered.getValue(file.name).countDown()
                    check(releases.getValue(file.name).await(5, TimeUnit.SECONDS))
                }
                ParsedLog(LogFormat.LOGCAT, emptyList())
            },
        )
        try {
            state.tabs = listOf(workspace("origin"), workspace("other"))
            state.commitTabOrder(listOf(TabRef.Log("origin"), TabRef.Log("other")))
            state.activateTab("origin")
            val groupId = state.createTabGroup(TabRef.Log("origin"))!!

            val inheritedFile = File(dir, "inherit.log").apply { writeText("x") }
            val inheritedId = state.openFile(inheritedFile, TabRef.Log("origin"))!!
            assertTrue(entered.getValue("inherit.log").await(3, TimeUnit.SECONDS))
            state.activateTab("other")
            releases.getValue("inherit.log").countDown()
            await { state.tab(inheritedId) != null }
            assertEquals(groupId, state.tabGroupOf(TabRef.Log(inheritedId))?.id)

            state.activateTab("origin")
            val unrelatedFile = File(dir, "unrelated.log").apply { writeText("x") }
            val unrelatedId = state.openFile(unrelatedFile)!!
            await { state.tab(unrelatedId) != null }
            assertNull(state.tabGroupOf(TabRef.Log(unrelatedId)))

            val deletedFile = File(dir, "deleted.log").apply { writeText("x") }
            val deletedId = state.openFile(deletedFile, TabRef.Log("origin"))!!
            assertTrue(entered.getValue("deleted.log").await(3, TimeUnit.SECONDS))
            state.ungroupTabs(groupId)
            releases.getValue("deleted.log").countDown()
            await { state.tab(deletedId) != null }
            assertTrue(state.tabGroups.isEmpty())
            assertNull(state.tabGroupOf(TabRef.Log(deletedId)))
        } finally {
            releases.values.forEach(CountDownLatch::countDown)
            state.close()
        }
    }

    @Test
    fun closingAGroupUsesTheLogAndDiagramStoresNormalRemovalPaths() {
        val dir = createTempDirectory("tab-groups-close").toFile()
        val state = stateFor(dir)
        try {
            state.tabs = listOf(workspace("log"))
            state.commitTabOrder(listOf(TabRef.Log("log")))
            val groupId = state.createTabGroup(TabRef.Log("log"))!!
            val diagramId = state.seq3Sessions.beginEmpty("log")!!
            assertEquals(2, state.tabGroups.single().members.size)

            state.closeTabGroup(groupId)

            assertTrue(state.tabs.isEmpty())
            assertTrue(state.seq3Sessions.sessions.isEmpty())
            assertTrue(state.tabGroups.isEmpty())
            assertFalse(TabRef.Log("log") in state.reconciledTabOrder())
            assertFalse(TabRef.Diagram(diagramId) in state.reconciledTabOrder())
        } finally {
            state.close()
        }
    }

    @Test
    fun newGroupsTakeTheFirstUnusedPaletteColourAndTheirTabsNameDeduplicated() {
        val dir = createTempDirectory("tab-groups-defaults").toFile()
        val state = stateFor(dir)
        try {
            state.tabs = listOf(workspace("a"), workspace("b"), workspace("c"))
            state.commitTabOrder(listOf(TabRef.Log("a"), TabRef.Log("b"), TabRef.Log("c")))
            state.tabs = state.tabs.map { it.copy(filename = "logcat.log") }

            val first = state.createTabGroup(TabRef.Log("a"))!!
            val second = state.createTabGroup(TabRef.Log("b"))!!
            val third = state.createTabGroup(TabRef.Log("c"))!!

            val byId = state.tabGroups.associateBy { it.id }
            assertEquals(listOf("logcat.log", "logcat.log 2", "logcat.log 3"), listOf(first, second, third).map { byId.getValue(it).name })
            assertEquals(
                HL_COLORS.take(3).map { it.toArgb() },
                listOf(first, second, third).map { byId.getValue(it).colorArgb },
                "each group takes the first palette colour no other group uses",
            )

            // A colour freed by removing a group is the next one handed out again.
            state.ungroupTabs(second)
            state.assignTabToGroup(TabRef.Log("b"), third)
            val reused = state.createTabGroup(TabRef.Log("b"))!!
            assertEquals(HL_COLORS[1].toArgb(), state.tabGroups.single { it.id == reused }.colorArgb)
        } finally {
            state.close()
        }
    }

    @Test
    fun regroupingTheOnlyMemberOfAGroupReusesItsNameAndColour() {
        val dir = createTempDirectory("tab-groups-regroup").toFile()
        val state = stateFor(dir)
        try {
            state.tabs = listOf(workspace("solo"))
            state.commitTabOrder(listOf(TabRef.Log("solo")))
            state.createTabGroup(TabRef.Log("solo"))!!
            val again = state.createTabGroup(TabRef.Log("solo"))!!
            val group = state.tabGroups.single()
            assertEquals(again, group.id)
            assertEquals("solo", group.name, "the emptied group no longer counts as taken")
            assertEquals(HL_COLORS.first().toArgb(), group.colorArgb)
        } finally {
            state.close()
        }
    }

    @Test
    fun colorArgbRoundTripsAndOlderBuildsStillGetTheNearestPaletteName() {
        val dir = createTempDirectory("tab-groups-codec").toFile()
        val state = stateFor(dir)
        try {
            state.tabs = listOf(workspace("log"))
            state.commitTabOrder(listOf(TabRef.Log("log")))
            val groupId = state.createTabGroup(TabRef.Log("log"))!!
            state.setTabGroupColor(groupId, NEAR_LEGACY_BLUE)

            val written = Json.parseToJsonElement(state.tabGroupsJson()).jsonObject.getValue("groups").jsonArray.single().jsonObject
            assertEquals(NEAR_LEGACY_BLUE, written.getValue("colorArgb").jsonPrimitive.int)
            assertEquals("BLUE", written.getValue("color").jsonPrimitive.contentOrNull, "older builds get the closest palette name")
            assertFalse("textSize" in written, "the retired text size is no longer written")

            state.restoreTabGroups(state.tabGroupsJson())
            assertEquals(NEAR_LEGACY_BLUE, state.tabGroups.single().colorArgb)
            assertEquals(LegacyTabGroupColor.GREEN, LegacyTabGroupColor.nearest(LegacyTabGroupColor.GREEN.argb))
            assertEquals(LegacyTabGroupColor.RED, LegacyTabGroupColor.nearest(0xFFFF0000.toInt()))
        } finally {
            state.close()
        }
    }

    @Test
    fun colorArgbWinsOverTheLegacyNameAndAnOldTextSizeIsIgnored() {
        val dir = createTempDirectory("tab-groups-legacy").toFile()
        val state = stateFor(dir)
        try {
            state.tabs = listOf(workspace("a"), workspace("b"), workspace("c"))
            state.commitTabOrder(listOf(TabRef.Log("a"), TabRef.Log("b"), TabRef.Log("c")))
            state.restoreTabGroups(
                """
                {"groups":[
                  {"id":"new","name":"New","color":"GREEN","colorArgb":$CUSTOM_GROUP_COLOR,"collapsed":false,
                   "members":[{"kind":"L","id":"a"}]},
                  {"id":"old","name":"Old","color":"ORANGE","textSize":"LARGE","icon":"BUG","collapsed":false,
                   "members":[{"kind":"L","id":"b"}]},
                  {"id":"junk","name":"Junk","colorArgb":"red","collapsed":false,"members":[{"kind":"L","id":"c"}]}
                ]}
                """.trimIndent(),
            )
            val byId = state.tabGroups.associateBy { it.id }
            assertEquals(CUSTOM_GROUP_COLOR, byId.getValue("new").colorArgb)
            assertEquals(LegacyTabGroupColor.ORANGE.argb, byId.getValue("old").colorArgb)
            assertEquals(TabGroupIcon.BUG, byId.getValue("old").icon)
            assertEquals(LegacyTabGroupColor.BLUE.argb, byId.getValue("junk").colorArgb, "a malformed colorArgb falls back to the default")
        } finally {
            state.close()
        }
    }

    @Test
    fun aCollapsedGroupKeepsItsActiveMemberAndFoldsFullyOnceAnotherTabIsActive() {
        val order = listOf(TabRef.Log("a"), TabRef.Log("b"), TabRef.Log("c"), TabRef.Log("d"))
        val collapsed = TabGroup("g", "G", 0xFF336699.toInt(), collapsed = true, members = listOf(TabRef.Log("a"), TabRef.Log("b"), TabRef.Log("c")))
        val expanded = collapsed.copy(collapsed = false)

        fun refs(items: List<TabStripItem>) = items.map { item ->
            when (item) {
                is TabStripItem.GroupHeader -> "header"
                is TabStripItem.Tab -> (item.ref as TabRef.Log).tabId
            }
        }

        assertEquals(listOf("header", "b", "d"), refs(projectTabStripItems(order, listOf(collapsed), active = TabRef.Log("b"))))
        assertEquals(listOf("header", "d"), refs(projectTabStripItems(order, listOf(collapsed), active = TabRef.Log("d"))))
        assertEquals(listOf("header", "d"), refs(projectTabStripItems(order, listOf(collapsed), active = null)))
        assertEquals(listOf("header", "a", "b", "c", "d"), refs(projectTabStripItems(order, listOf(expanded), active = TabRef.Log("d"))))

        // The kept member still carries the group: an overflowed header is re-added beside it.
        val items = projectTabStripItems(order, listOf(collapsed), active = TabRef.Log("b"))
        val withoutHeader = items.filterNot { it is TabStripItem.GroupHeader }
        assertEquals(listOf("header", "b", "d"), refs(associateGroupHeaders(withoutHeader, listOf(collapsed))))
        assertEquals(setOf(TabRef.Log("b")), tabGroupRunEnds(items, listOf(collapsed)), "the lone visible member closes the run")
        assertEquals(setOf(TabRef.Log("c")), tabGroupRunEnds(projectTabStripItems(order, listOf(expanded)), listOf(expanded)))
    }
}
