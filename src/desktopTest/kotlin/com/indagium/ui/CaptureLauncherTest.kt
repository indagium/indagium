package com.indagium.ui

import androidx.compose.runtime.snapshots.Snapshot
import com.indagium.capture.CaptureDevice
import com.indagium.capture.CaptureEncoderAcceleration
import com.indagium.capture.CaptureMirrorMode
import com.indagium.capture.CaptureVideoCodec
import com.indagium.capture.CaptureVideoEncoder
import com.indagium.capture.CaptureVideoEncoderCapabilities
import com.indagium.capture.CaptureVideoEncoderProbeState
import com.indagium.capture.effectiveMirrorMode
import com.indagium.capture.withMirrorMode
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class CaptureLauncherTest {
    @Test
    fun advancedCodecSupportTextNamesThePortableDecoderOnlyWhenTheCodecIsInEffect() {
        fun ready(codec: CaptureVideoCodec, acceleration: CaptureEncoderAcceleration) = CaptureVideoEncoderProbeState.Ready(
            CaptureVideoEncoderCapabilities(listOf(CaptureVideoEncoder(codec, "enc.${codec.scrcpyName}", acceleration))),
        )
        val h265 = captureVideoCodecSupportText(CaptureVideoCodec.H265, ready(CaptureVideoCodec.H265, CaptureEncoderAcceleration.HARDWARE), "PHONE")
        val av1 = captureVideoCodecSupportText(CaptureVideoCodec.AV1, ready(CaptureVideoCodec.AV1, CaptureEncoderAcceleration.HARDWARE), "PHONE")
        assertTrue("portable decoder" in h265 && "software-only" !in h265, h265)
        assertTrue("portable decoder" in av1 && "AV1 decoding is software-only" in av1, av1)

        val fallback = captureVideoCodecSupportText(CaptureVideoCodec.AV1, ready(CaptureVideoCodec.AV1, CaptureEncoderAcceleration.SOFTWARE), "PHONE")
        assertFalse("portable decoder" in fallback, "an H.264 fallback keeps the native mirror: $fallback")
        assertFalse("portable decoder" in captureVideoCodecSupportText(CaptureVideoCodec.H264, null, "PHONE"))
    }

    @Test
    fun selectingADeviceProbesEncodersOnlyForAnAdvancedCodec() {
        val online = CaptureDevice("PHONE", "device")
        val offline = CaptureDevice("PHONE", "offline")
        assertFalse(shouldAutoProbeVideoEncoders(CaptureVideoCodec.H264, online), "H.264 never touches the device")
        assertTrue(shouldAutoProbeVideoEncoders(CaptureVideoCodec.H265, online))
        assertTrue(shouldAutoProbeVideoEncoders(CaptureVideoCodec.AV1, online))
        assertFalse(shouldAutoProbeVideoEncoders(CaptureVideoCodec.AV1, offline), "an unavailable device is not probed")
    }

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

    // The New tab's "Before start" choices are the saved capture settings: what a user picked for
    // the last capture is what the next New tab, and the next app start, begin with.
    @Test
    fun beforeStartChoicesCarryOverToTheNextNewTabAndAcrossRestart() {
        val autosave = Files.createTempFile("capture-launcher-persist", ".json").toFile()
        val app = AppState(autosaveFile = autosave, autoExportNotes = false)
        try {
            app.openHomeTab()
            val first = requireNotNull(app.activeTab()).id
            app.updateCaptureLaunchSettings(first) {
                it.copy(recordVideo = false, audio = true, hardwareMirror = true).withMirrorMode(CaptureMirrorMode.EXTERNAL)
            }
            app.closeTab(first)

            app.openHomeTab()
            val second = requireNotNull(app.activeTab()).id
            val carried = app.captureLaunchSettings(second)
            assertFalse(carried.recordVideo)
            assertTrue(carried.audio)
            assertTrue(carried.hardwareMirror)
            assertEquals(CaptureMirrorMode.EXTERNAL, carried.effectiveMirrorMode)
            app.autosaveNow()
        } finally {
            app.close()
        }

        val restored = AppState(autosaveFile = autosave, restoreOnCreate = true, autoExportNotes = false)
        try {
            val saved = restored.settings.captureSettings
            assertFalse(saved.recordVideo)
            assertTrue(saved.audio)
            assertTrue(saved.hardwareMirror)
            assertEquals(CaptureMirrorMode.EXTERNAL, saved.effectiveMirrorMode)
        } finally {
            restored.close()
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

    // adbShortStatusLabel (item 2 of the "make it compact" pass): the Devices panel header's short
    // "adb 1.0.41" line, extracted from the (possibly multi-line, verbose) raw `adb version` output
    // on CaptureService.toolStatus rather than showing that whole blob inline.

    @Test
    fun adbShortStatusLabelExtractsTheVersionNumberFromTheFirstLine() {
        assertEquals("adb 1.0.41", adbShortStatusLabel("Android Debug Bridge version 1.0.41\nscrcpy is ready"))
    }

    @Test
    fun adbShortStatusLabelHandlesAMultiLineVersionBlob() {
        val toolStatus = "Android Debug Bridge version 1.0.41\nVersion 34.0.4-10411341\nInstalled as /opt/adb\nscrcpy is ready"
        assertEquals("adb 1.0.41", adbShortStatusLabel(toolStatus))
    }

    @Test
    fun adbShortStatusLabelFallsBackToTheRawFirstLineWhenNoVersionNumberIsFound() {
        assertEquals("adb is ready", adbShortStatusLabel("adb is ready\nscrcpy is ready"))
    }

    @Test
    fun adbShortStatusLabelIsNullForBlankOrMissingStatus() {
        assertNull(adbShortStatusLabel(null))
        assertNull(adbShortStatusLabel(""))
        assertNull(adbShortStatusLabel("\n"))
    }
}
