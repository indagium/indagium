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
            assertFalse(app.captureLaunchSettings(launcherId).recordVideo)

            var writes = 0
            val observer = Snapshot.registerGlobalWriteObserver { writes++ }
            try {
                app.updateCaptureLaunchSettings(launcherId) { it.copy(recordVideo = true) }
                Snapshot.sendApplyNotifications()
            } finally {
                observer.dispose()
            }

            assertTrue(writes > 0, "launcher drafts must invalidate CaptureLauncher after a toggle")
            assertTrue(app.captureLaunchSettings(launcherId).recordVideo)
        } finally {
            app.close()
        }
    }
}
