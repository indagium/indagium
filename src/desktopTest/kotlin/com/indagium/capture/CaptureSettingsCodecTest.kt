package com.indagium.capture

import java.io.File
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull

/**
 * Phase 1 (buffer modes) regression coverage. `CaptureSettingsCodec` is shared by three writers —
 * app settings (`ui/AutosaveCodec.kt`), `session.json` (`CaptureRecorder.sessionJson`), and the
 * portable archive descriptor (`CaptureArchive.kt`) — all reading through the same `optional`
 * helper, so a key that is optional-with-a-default here is safe for all three.
 */
class CaptureSettingsCodecTest {
    @Test
    fun bufferModeDefaultsWhenAbsent() {
        val decoded = assertNotNull(captureSettingsFromJson("""{"formatVersion":1}"""))
        assertEquals(CaptureBufferMode.DEFAULT, decoded.bufferMode)
    }

    @Test
    fun earlierDeviceLogsAreOffWhenAbsentButAnExplicitTrueIsKept() {
        assertEquals(false, CaptureSettings().includeBufferedLogs)
        assertEquals(false, assertNotNull(captureSettingsFromJson("""{"formatVersion":1}""")).includeBufferedLogs)
        assertEquals(
            true,
            assertNotNull(captureSettingsFromJson("""{"formatVersion":1,"includeBufferedLogs":true}""")).includeBufferedLogs,
        )
    }

    @Test
    fun microphoneSelectionDefaultsOffAndRoundTrips() {
        val defaultSettings = assertNotNull(captureSettingsFromJson("""{"formatVersion":1}"""))
        assertEquals(MICROPHONE_OFF_ID, defaultSettings.microphoneDeviceId)
        val selected = "dshow\u001faudio=USB Microphone"
        val decoded = assertNotNull(captureSettingsFromJson(captureSettingsToJson(CaptureSettings(microphoneDeviceId = selected))))
        assertEquals(selected, decoded.microphoneDeviceId)
        val defaultRoundTrip = CaptureSettings(microphoneDeviceId = MICROPHONE_DEFAULT_ID)
        val decodedDefault = assertNotNull(captureSettingsFromJson(captureSettingsToJson(defaultRoundTrip)))
        assertEquals(MICROPHONE_DEFAULT_ID, decodedDefault.microphoneDeviceId)
    }

    @Test
    fun legacyMirrorBooleanMigratesToTheEquivalentDisplayMode() {
        assertEquals(
            CaptureMirrorMode.EMBEDDED,
            assertNotNull(captureSettingsFromJson("""{"formatVersion":1,"mirror":true}""")).effectiveMirrorMode,
        )
        assertEquals(
            CaptureMirrorMode.DISABLED,
            assertNotNull(captureSettingsFromJson("""{"formatVersion":1,"mirror":false}""")).effectiveMirrorMode,
        )
    }

    @Test
    fun externalMirrorChoiceRoundTripsAndKeepsLegacyEnablementTrue() {
        val decoded = assertNotNull(
            captureSettingsFromJson(captureSettingsToJson(CaptureSettings().withMirrorMode(CaptureMirrorMode.EXTERNAL))),
        )
        assertEquals(CaptureMirrorMode.EXTERNAL, decoded.effectiveMirrorMode)
        assertEquals(true, decoded.mirror)
    }

    @Test
    fun captureStartSelectsExactlyOneMirrorRoute() {
        assertEquals(CaptureMirrorStartRoute.EMBEDDED, CaptureSettings().mirrorStartRoute())
        assertEquals(
            CaptureMirrorStartRoute.EXTERNAL,
            CaptureSettings().withMirrorMode(CaptureMirrorMode.EXTERNAL).mirrorStartRoute(),
        )
        assertEquals(
            CaptureMirrorStartRoute.NONE,
            CaptureSettings().withMirrorMode(CaptureMirrorMode.DISABLED).mirrorStartRoute(),
        )
        assertEquals(CaptureMirrorStartRoute.NONE, CaptureSettings(mirror = false).mirrorStartRoute())
    }

    @Test
    fun unknownMirrorModeFallsBackToTheLegacyBooleanInsteadOfDiscardingOtherSettings() {
        val decoded = assertNotNull(
            captureSettingsFromJson("""{"formatVersion":1,"mirror":false,"mirrorMode":"FUTURE","maxFps":45}"""),
        )
        assertEquals(CaptureMirrorMode.DISABLED, decoded.effectiveMirrorMode)
        assertEquals(45, decoded.maxFps)
    }

    @Test
    fun bufferModeRoundTrips() {
        CaptureBufferMode.entries.forEach { mode ->
            val settings = CaptureSettings(bufferMode = mode)
            val decoded = assertNotNull(captureSettingsFromJson(captureSettingsToJson(settings)))
            assertEquals(mode, decoded.bufferMode)
        }
    }

    // The regression test for the "present but unknown enum name" decision: `optional()`'s usual
    // contract is to fail the WHOLE decode on a malformed known key (see its doc), which would
    // reset every other capture setting to defaults just because one build is older than the file
    // it is reading. bufferMode deliberately does not use that helper's failure path.
    @Test
    fun unknownBufferModeFallsBackToDefaultWithoutDiscardingOtherSettings() {
        val json = """{"formatVersion":1,"bufferMode":"SOMETHING_NEW","maxFps":45}"""
        val decoded = assertNotNull(captureSettingsFromJson(json))
        assertEquals(CaptureBufferMode.DEFAULT, decoded.bufferMode)
        assertEquals(45, decoded.maxFps)
    }

    @Test
    fun sessionJsonWithoutBufferModeStillLoads() {
        // Hand-write a pre-Phase-1 session.json: a "settings" object with no "bufferMode" key at
        // all, the shape every capture recorded before this change persisted to disk.
        val root = Files.createTempDirectory("capture-codec-session-fixture").toFile()
        try {
            val sessionDir = File(root, "session-fixture")
            sessionDir.mkdirs()
            val legacySettingsJson = """
                {"formatVersion":1,"adbPath":"","scrcpyPath":"","buffers":["main","system","crash"],
                "includeBufferedLogs":false,"recordVideo":false,"mirror":true,"audio":false,
                "maxSize":1080,"maxFps":30,"bitrateMbps":8,"sessionLimitBytes":10737418240,
                "freeSpaceReserveBytes":1073741824,"filenameTemplate":"{device}_{start}_{range}_{counter}.zip",
                "label":""}
            """.trimIndent()
            File(sessionDir, "session.json").writeText(
                """
                {"formatVersion":1,"id":"session-fixture","device":{"serial":"SERIAL","state":"device","model":"Pixel","emulator":false},
                "settings":$legacySettingsJson,"startedEpochMs":1000,"elapsedMs":0,"status":"STOPPED",
                "logCheckpointMs":-1,"videoCheckpointMs":-1,"exportCounter":0,"interruptions":[],"manualOffsetMs":0}
                """.trimIndent(),
            )
            val recorder = CaptureRecorder(root)
            try {
                val loaded = assertNotNull(recorder.listSessions().singleOrNull())
                assertEquals(CaptureBufferMode.DEFAULT, loaded.settings.bufferMode)
                assertEquals(listOf("main", "system", "crash"), loaded.settings.buffers)
            } finally {
                recorder.close()
            }
        } finally {
            root.deleteRecursively()
        }
    }

    // Archive v3 / MP4 export: videoContainer round-trips like bufferMode above, and an unrecognized
    // value (a settings file written by a newer build) falls back to the default instead of failing
    // the whole decode — same "absent or unknown both mean default" contract as bufferMode/mirrorMode.
    @Test
    fun videoContainerRoundTrips() {
        CaptureVideoContainer.entries.forEach { container ->
            val settings = CaptureSettings(videoContainer = container)
            val decoded = assertNotNull(captureSettingsFromJson(captureSettingsToJson(settings)))
            assertEquals(container, decoded.videoContainer)
        }
    }

    @Test
    fun unknownVideoContainerFallsBackToTheDefaultWithoutDiscardingOtherSettings() {
        val json = """{"formatVersion":1,"videoContainer":"SOMETHING_NEW","maxFps":45}"""
        val decoded = assertNotNull(captureSettingsFromJson(json))
        assertEquals(CaptureVideoContainer.MP4, decoded.videoContainer)
        assertEquals(45, decoded.maxFps)
    }

    // "Keep sound on the device" (CaptureSettings.keepDeviceAudio): off by default, and absent on
    // any settings JSON written before this feature existed decodes to that same default — same
    // "appended last, defaults on absence" contract as markerPreMs/videoContainer above.

    @Test
    fun keepDeviceAudioDefaultsToFalseWhenAbsent() {
        val decoded = assertNotNull(captureSettingsFromJson("""{"formatVersion":1}"""))
        assertEquals(false, decoded.keepDeviceAudio)
    }

    @Test
    fun keepDeviceAudioRoundTrips() {
        val decoded = assertNotNull(captureSettingsFromJson(captureSettingsToJson(CaptureSettings(keepDeviceAudio = true))))
        assertEquals(true, decoded.keepDeviceAudio)
    }

    // Windows/Linux hardware mirror opt-in (CaptureSettings.hardwareMirror): off by default, and
    // absent on any settings JSON written before this feature existed decodes to that same default —
    // same "appended last, defaults on absence" contract as keepDeviceAudio above.
    @Test
    fun hardwareMirrorDefaultsToFalseWhenAbsent() {
        val decoded = assertNotNull(captureSettingsFromJson("""{"formatVersion":1}"""))
        assertEquals(false, decoded.hardwareMirror)
    }

    @Test
    fun hardwareMirrorRoundTrips() {
        val decoded = assertNotNull(captureSettingsFromJson(captureSettingsToJson(CaptureSettings(hardwareMirror = true))))
        assertEquals(true, decoded.hardwareMirror)
    }

    @Test
    fun logcatBufferArgsForEachMode() {
        assertEquals(emptyList(), CaptureSettings(bufferMode = CaptureBufferMode.DEFAULT).logcatBufferArgs())
        assertEquals(listOf("-b", "all"), CaptureSettings(bufferMode = CaptureBufferMode.ALL).logcatBufferArgs())
        assertEquals(
            listOf("-b", "main", "-b", "crash"),
            CaptureSettings(bufferMode = CaptureBufferMode.CUSTOM, buffers = listOf("main", "crash")).logcatBufferArgs(),
        )
        assertEquals(
            listOf("-b", "main", "-b", "crash"),
            CaptureSettings(
                bufferMode = CaptureBufferMode.CUSTOM,
                buffers = listOf("main", "crash", "main"),
            ).logcatBufferArgs(),
        )
    }
}
