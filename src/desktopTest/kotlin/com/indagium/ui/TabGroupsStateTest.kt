package com.indagium.ui

import com.indagium.model.LogFormat
import com.indagium.utils.ParsedLog
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
            assertEquals("Group 1", state.tabGroups.single().name)
            assertEquals(TabGroupColor.BLUE, state.tabGroups.single().color)
            assertEquals(TabGroupTextSize.MEDIUM, state.tabGroups.single().textSize)
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
            assertEquals(TabGroupColor.PURPLE, keep.color)
            assertTrue(keep.collapsed)
            assertEquals(TabGroupTextSize.MEDIUM, keep.textSize)
            assertEquals(TabGroupIcon.NONE, keep.icon)
            assertEquals(listOf(TabRef.Log("log")), keep.members)
            val malformed = state.tabGroups.single { it.id == "malformed" }
            assertEquals(TabGroupColor.BLUE, malformed.color)
            assertFalse(malformed.collapsed)
            assertEquals(TabGroupTextSize.MEDIUM, malformed.textSize)
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
            state.setTabGroupColor(groupId, TabGroupColor.ORANGE)
            state.setTabGroupTextSize(groupId, TabGroupTextSize.LARGE)
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
                assertEquals(TabGroupColor.ORANGE, group.color)
                assertEquals(TabGroupTextSize.LARGE, group.textSize)
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
}
