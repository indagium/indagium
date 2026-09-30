package com.indagium.ui

import kotlinx.coroutines.runBlocking
import java.io.File
import kotlin.io.path.createTempDirectory
import kotlin.test.Test
import kotlin.test.assertEquals

class RecentFilesPruneTest {
    @Test
    fun offUiThreadPruneDropsMissingFilesAndKeepsExistingOnesInOrder() {
        val dir = createTempDirectory("openlog-recent-prune").toFile()
        val first = File(dir, "first.log").apply { writeText("a\n") }
        val second = File(dir, "second.log").apply { writeText("b\n") }
        val missing = File(dir, "gone.log")
        val state = AppState(File(dir, "state.cache"))
        state.recentFiles = listOf(first.absolutePath, missing.absolutePath, second.absolutePath)

        runBlocking { state.pruneMissingRecentFilesOffUiThread() }

        assertEquals(listOf(first.absolutePath, second.absolutePath), state.recentFiles)
    }

    @Test
    fun offUiThreadPruneLeavesAnUnchangedListAlone() {
        val dir = createTempDirectory("openlog-recent-prune-noop").toFile()
        val only = File(dir, "only.log").apply { writeText("a\n") }
        val state = AppState(File(dir, "state.cache"))
        state.recentFiles = listOf(only.absolutePath)

        runBlocking { state.pruneMissingRecentFilesOffUiThread() }

        assertEquals(listOf(only.absolutePath), state.recentFiles)
    }
}
