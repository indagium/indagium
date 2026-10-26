package com.indagium.ui

import androidx.compose.runtime.snapshots.Snapshot
import com.indagium.capture.CaptureBufferMode
import com.indagium.capture.CaptureDevice
import com.indagium.capture.CaptureEncoderAcceleration
import com.indagium.capture.CaptureMirrorMode
import com.indagium.capture.CaptureSettings
import com.indagium.capture.CaptureVideoCodec
import com.indagium.capture.CaptureVideoEncoder
import com.indagium.capture.CaptureVideoEncoderCapabilities
import com.indagium.capture.CaptureVideoEncoderProbeState
import com.indagium.capture.CaptureVideoPreset
import com.indagium.capture.MICROPHONE_DEFAULT_ID
import com.indagium.capture.effectiveMirrorMode
import com.indagium.capture.withMirrorMode
import com.indagium.capture.withVideoPreset
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
        val av1 = captureVideoCodecSupportText(CaptureVideoCodec.AV1, ready(CaptureVideoCodec.AV1, CaptureEncoderAcceleration.HARDWARE), "PHONE")
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

    // captureSettingsSummary: the Capture card's one-line recap of the (now dialog-hosted) recording options.

    @Test
    fun summaryNamesPresetMirrorCodecAndDeviceAudio() {
        val settings = CaptureSettings(audio = true).withVideoPreset(CaptureVideoPreset.SMOOTH)
        assertEquals("Smooth · In-app mirror · H.264 · device audio", captureSettingsSummary(settings))
    }

    @Test
    fun summaryDefaultsReadAsBalancedWithNoAudioAndNoBufferNoise() {
        assertEquals("Balanced · In-app mirror · H.264 · no audio", captureSettingsSummary(CaptureSettings()))
    }

    @Test
    fun summaryShowsCustomWhenTheNumbersMatchNoPreset() {
        assertTrue(captureSettingsSummary(CaptureSettings(maxSize = 999)).startsWith("Custom · "))
    }

    @Test
    fun summaryDropsCodecAndSaysNoVideoWhenRecordingIsOff() {
        val summary = captureSettingsSummary(
            CaptureSettings(recordVideo = false, videoCodec = CaptureVideoCodec.AV1).withMirrorMode(CaptureMirrorMode.EXTERNAL),
        )
        assertEquals("No video · scrcpy window · no audio", summary)
    }

    @Test
    fun summaryListsAudioPiecesKeptOnDeviceAndMic() {
        val kept = CaptureSettings(audio = true, keepDeviceAudio = true, microphoneDeviceId = MICROPHONE_DEFAULT_ID)
        assertEquals(
            "Balanced · In-app mirror · H.264 · device audio + kept on device · mic",
            captureSettingsSummary(kept),
        )
        assertEquals(
            "Balanced · In-app mirror · H.264 · mic",
            captureSettingsSummary(CaptureSettings(microphoneDeviceId = MICROPHONE_DEFAULT_ID)),
        )
    }

    @Test
    fun summaryMentionsMirrorOffEarlierLogsAndNonDefaultBuffers() {
        val settings = CaptureSettings(
            includeBufferedLogs = true,
            bufferMode = CaptureBufferMode.CUSTOM,
            buffers = listOf("main", "events"),
            videoCodec = CaptureVideoCodec.AV1,
        ).withMirrorMode(CaptureMirrorMode.DISABLED)
        assertEquals(
            "Balanced · Mirror off · AV1 · no audio · earlier logs · buffers: main, events",
            captureSettingsSummary(settings),
        )
        assertTrue(captureSettingsSummary(CaptureSettings(bufferMode = CaptureBufferMode.ALL)).endsWith("· all buffers"))
    }

    // captureStartBlockedHint: why Start capture is disabled; null exactly when the old enable rule allows it.

    @Test
    fun startHintIsNullOnlyForAReadyDeviceWithNothingRunningOrStarting() {
        assertNull(captureStartBlockedHint(CaptureDevice("PHONE", "device"), null, false))
    }

    @Test
    fun startHintExplainsEachBlockedState() {
        val ready = CaptureDevice("PHONE", "device")
        assertEquals("Connect a device to start", captureStartBlockedHint(null, null, false))
        assertEquals(
            "Device is unauthorized — allow USB debugging on the phone",
            captureStartBlockedHint(CaptureDevice("PHONE", "unauthorized"), null, false),
        )
        assertEquals("Device is offline — reconnect it", captureStartBlockedHint(CaptureDevice("PHONE", "offline"), null, false))
        assertEquals("Device is recovery — not ready for capture", captureStartBlockedHint(CaptureDevice("PHONE", "recovery"), null, false))
        assertEquals("A capture is already running", captureStartBlockedHint(ready, "tab-1", false))
        assertEquals("Starting…", captureStartBlockedHint(ready, null, true))
    }

    @Test
    fun startHintMatchesTheOldEnableRuleForEveryCombination() {
        val devices = listOf(null, CaptureDevice("A", "device"), CaptureDevice("A", "offline"))
        for (device in devices) for (live in listOf<String?>(null, "tab")) for (starting in listOf(false, true)) {
            val oldRule = device?.available == true && live == null && !starting
            assertEquals(oldRule, captureStartBlockedHint(device, live, starting) == null, "$device $live $starting")
        }
    }

    // resolveSelectedCaptureDevice: the selection logic that used to sit inline in the launcher.

    @Test
    fun resolveSelectionPrefersTheLiveSelectedDeviceThenTheFirst() {
        val a = CaptureDevice("A", "device")
        val b = CaptureDevice("B", "device")
        assertEquals(b, resolveSelectedCaptureDevice(listOf(a, b), "B", null, false))
        assertEquals(a, resolveSelectedCaptureDevice(listOf(a, b), null, null, false))
        assertEquals(a, resolveSelectedCaptureDevice(listOf(a, b), "GONE", null, false))
        assertNull(resolveSelectedCaptureDevice(emptyList(), "B", null, false))
    }

    @Test
    fun resolveSelectionKeepsTheLastKnownDeviceOnlyWhileItIsBeingRooted() {
        val a = CaptureDevice("A", "device")
        val b = CaptureDevice("B", "device")
        assertEquals(b, resolveSelectedCaptureDevice(listOf(a), "B", b, isSelectedDeviceRooting = true))
        assertEquals(a, resolveSelectedCaptureDevice(listOf(a), "B", b, isSelectedDeviceRooting = false))
        assertEquals(a, resolveSelectedCaptureDevice(listOf(a), "B", a, isSelectedDeviceRooting = true), "last-known must match the selected serial")
    }
}
