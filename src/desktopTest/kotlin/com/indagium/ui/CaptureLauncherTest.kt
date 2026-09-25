package com.indagium.ui

import androidx.compose.runtime.snapshots.Snapshot
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class CaptureLauncherTest {
    @Test
    fun launcherMarkerIsSessionOnlyAndNotSerialized() {
        val launcher = mkTab("launcher", "New capture", emptyList()).copy(isCaptureLauncher = true)
        val ordinary = launcher.copy(isCaptureLauncher = false)
        assertTrue(launcher.isCaptureLauncher)
        assertFalse(ordinary.isCaptureLauncher)
        assertEquals(ordinary.persistedSnapshot(), launcher.persistedSnapshot())
        assertEquals(ordinary.tabToken(), launcher.tabToken())
    }

    @Test
    fun changingTheRecordVideoDraftPublishesComposeStateBeforeCaptureStarts() {
        val app = AppState(
            autosaveFile = Files.createTempFile("capture-launcher-draft", ".json").toFile(),
            autoExportNotes = false,
        )
        try {
            app.openHomeTab()
            val launcherId = requireNotNull(app.activeTab()).id
            assertTrue(app.captureLaunchSettings(launcherId).recordVideo) // on by default

            var writes = 0
            val observer = Snapshot.registerGlobalWriteObserver { writes++ }
            try {
                app.updateCaptureLaunchSettings(launcherId) { it.copy(recordVideo = false) }
                Snapshot.sendApplyNotifications()
            } finally {
                observer.dispose()
            }

            assertTrue(writes > 0, "launcher drafts must invalidate CaptureLauncher after a toggle")
            assertFalse(app.captureLaunchSettings(launcherId).recordVideo)
        } finally {
            app.close()
        }
    }

    @Test
    fun toggleCaptureBufferAddsUncheckedAndRemovesChecked() {
        val defaults = listOf("main", "system", "crash")

        // Add: an unticked buffer is appended once, not removed (item 5's bug).
        assertEquals(listOf("main", "system", "crash", "kernel"), toggleCaptureBuffer(defaults, "kernel"))
        // Remove: a ticked buffer is removed, not re-added.
        assertEquals(listOf("main", "crash"), toggleCaptureBuffer(defaults, "system"))
        // No duplicates: toggling twice returns to the original list.
        val added = toggleCaptureBuffer(defaults, "radio")
        assertEquals(defaults, toggleCaptureBuffer(added, "radio"))
        // Order preserved for both directions.
        assertEquals(listOf("system", "crash"), toggleCaptureBuffer(defaults, "main"))
    }

    @Test
    fun requiresTypedDeleteConfirmationOnlyWhenAllOfAtLeastTwoAreSelected() {
        assertFalse(requiresTypedDeleteConfirmation(selectedCount = 1, totalCount = 1))
        assertFalse(requiresTypedDeleteConfirmation(selectedCount = 1, totalCount = 3))
        assertFalse(requiresTypedDeleteConfirmation(selectedCount = 2, totalCount = 3))
        assertTrue(requiresTypedDeleteConfirmation(selectedCount = 3, totalCount = 3))
        assertFalse(requiresTypedDeleteConfirmation(selectedCount = 0, totalCount = 0))
    }
}
