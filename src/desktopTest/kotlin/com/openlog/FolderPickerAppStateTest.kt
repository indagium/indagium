package com.openlog

import com.openlog.ui.AppState
import com.openlog.ui.UpdateDownloadState
import com.openlog.update.ReleaseAsset
import com.openlog.update.ReleaseInfo
import com.openlog.update.UpdateChecker
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import java.io.File
import kotlin.io.path.createTempDirectory
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

class FolderPickerAppStateTest {
    @Test
    fun saveAndSourceFolderPickersPersistTheDirectoryReturnedByThePicker() {
        val root = createTempDirectory("openlog-folder-picker").toFile()
        val selected = File(root, "selected").apply { mkdir() }
        val picker = FakeDirectoryPicker(selected)
        val state = AppState(autosaveFile = File(root, "state.cache"), directoryPicker = picker::pick)

        try {
            state.pickSaveFolder()
            state.pickSourceFolder()

            assertEquals(selected.absolutePath, state.settings.defaultSaveDir)
            assertEquals(listOf(selected.absolutePath), state.settings.sourceFolders)
            assertEquals(listOf("Choose Save Folder", "Choose Source Folder"), picker.titles)
            assertNull(picker.initialDirectories.first())
            assertNull(picker.initialDirectories[1])
        } finally {
            state.close()
        }
    }

    @Test
    fun cancellingFolderPickersLeavesSettingsUnchanged() {
        val root = createTempDirectory("openlog-folder-picker-cancel").toFile()
        val originalSave = File(root, "original-save").apply { mkdir() }
        val originalSource = File(root, "original-source").apply { mkdir() }
        val picker = FakeDirectoryPicker(null)
        val state = AppState(autosaveFile = File(root, "state.cache"), directoryPicker = picker::pick)

        try {
            state.updateSettings { it.copy(defaultSaveDir = originalSave.absolutePath, sourceFolders = listOf(originalSource.absolutePath)) }
            state.pickSaveFolder()
            state.pickSourceFolder()

            assertEquals(originalSave.absolutePath, state.settings.defaultSaveDir)
            assertEquals(listOf(originalSource.absolutePath), state.settings.sourceFolders)
            assertEquals(listOf("Choose Save Folder", "Choose Source Folder"), picker.titles)
            assertEquals(originalSave, picker.initialDirectories.first())
        } finally {
            state.close()
        }
    }

    @Test
    fun updateDownloadUsesAndPersistsTheDirectoryReturnedByThePicker() {
        val root = createTempDirectory("openlog-update-picker").toFile()
        val selected = File(root, "downloads").apply { mkdir() }
        val legacyFile = File(root, "first-item").apply { writeText("legacy incorrect selection") }
        val picker = FakeDirectoryPicker(selected)
        val client = HttpClient(MockEngine {
            respond("package", HttpStatusCode.OK, headersOf("Content-Length", "7"))
        }) { expectSuccess = false }
        val state = AppState(
            autosaveFile = File(root, "state.cache"),
            directoryPicker = picker::pick,
            updateChecker = UpdateChecker(client),
        )

        try {
            state.updateSettings { it.copy(updateDownloadDir = legacyFile.absolutePath) }
            state.availableUpdate = ReleaseInfo(
                version = "1.0.1",
                tag = "v1.0.1",
                htmlUrl = "https://example.test/release",
                body = "",
                assets = listOf(
                    ReleaseAsset("openLog.dmg", "https://example.test/openLog.dmg", 7L),
                    ReleaseAsset("openLog.deb", "https://example.test/openLog.deb", 7L),
                    ReleaseAsset("openLog.msi", "https://example.test/openLog.msi", 7L),
                ),
            )

            state.downloadUpdate()
            waitUntil { state.updateDownload is UpdateDownloadState.Done }

            assertEquals(selected.absolutePath, state.settings.updateDownloadDir)
            assertEquals(listOf("Choose Download Folder"), picker.titles)
            assertEquals(legacyFile, picker.initialDirectories.single())
            assertIs<UpdateDownloadState.Done>(state.updateDownload)
        } finally {
            state.close()
            client.close()
        }
    }

    private class FakeDirectoryPicker(private val selection: File?) {
        val titles = mutableListOf<String>()
        val initialDirectories = mutableListOf<File?>()

        fun pick(title: String, initialDirectory: File?): File? {
            titles += title
            initialDirectories += initialDirectory
            return selection
        }
    }

    private fun waitUntil(timeoutMs: Long = 2_000, condition: () -> Boolean) {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (!condition() && System.currentTimeMillis() < deadline) Thread.sleep(10)
        assertTrue(condition(), "Condition was not met within ${timeoutMs}ms")
    }
}
