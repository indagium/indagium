package com.indagium.capture

import java.io.File
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class DeviceLoggingConfigurationTest {
    @Test
    fun mergingUnknownFieldsPreservesThemWhileKnownEmptyFieldsReplaceThem() {
        val original = DeviceLoggingConfiguration(
            bufferSizesBytes = mapOf("main" to 1024L),
            globalLogTag = "D",
            perTagOverrides = mapOf("NoisyTag" to "S"),
        )

        assertEquals(original, original.mergeKnown(DeviceLoggingConfiguration()))
        assertEquals(
            DeviceLoggingConfiguration(
                bufferSizesBytes = emptyMap(),
                globalLogTag = "",
                perTagOverrides = emptyMap(),
            ),
            original.mergeKnown(
                DeviceLoggingConfiguration(
                    bufferSizesBytes = emptyMap(),
                    globalLogTag = "",
                    perTagOverrides = emptyMap(),
                ),
            ),
        )
        assertEquals(
            original.copy(globalLogTag = ""),
            original.mergeKnown(DeviceLoggingConfiguration(globalLogTag = "")),
        )
    }

    @Test
    fun completeBufferListingRequiresEveryLineToBeValidUniqueAndBounded() {
        val listing = """
            main: ring buffer is 1MB (128KB consumed)
            system: ring buffer is 256KB
        """.trimIndent()
        assertEquals(
            mapOf("main" to 1024L * 1024, "system" to 256L * 1024),
            parseCompleteLogBufferConfiguration(listing),
        )

        assertNull(parseCompleteLogBufferConfiguration(""))
        assertNull(parseCompleteLogBufferConfiguration("adb: device offline"))
        assertNull(parseCompleteLogBufferConfiguration("$listing\nnot a buffer record"))
        assertNull(parseCompleteLogBufferConfiguration("main: ring buffer is 1MB trailing-junk"))
        assertNull(parseCompleteLogBufferConfiguration("main: ring buffer is 1MB\nmain: ring buffer is 2MB"))
        assertNull(parseCompleteLogBufferConfiguration("bad/name: ring buffer is 1MB"))
        assertNull(parseCompleteLogBufferConfiguration("main: ring buffer is 0B"))
        assertNull(parseCompleteLogBufferConfiguration(listing, outputWasTruncated = true))

        val overLimit = (1..257).joinToString("\n") { "buffer_$it: ring buffer is 1MB" }
        assertNull(parseCompleteLogBufferConfiguration(overLimit))
    }

    @Test
    fun completeBufferSnapshotAcceptsCurrentPlatformToolsReadableMetrics() {
        // Captured from `adb -s emulator-5554 logcat -b all -g` on 2026-10-16. This exact
        // output used to parse as zero buffers because every record had a readable suffix.
        val listing = """
            main: ring buffer is 2 MiB (1 MiB consumed, 7 MiB readable), max entry is 5120 B, max payload is 4068 B
            radio: ring buffer is 256 KiB (168 KiB consumed, 673 KiB readable), max entry is 5120 B, max payload is 4068 B
            events: ring buffer is 256 KiB (79 KiB consumed, 126 KiB readable), max entry is 5120 B, max payload is 4068 B
            system: ring buffer is 2 MiB (682 KiB consumed, 1 MiB readable), max entry is 5120 B, max payload is 4068 B
            crash: ring buffer is 2 MiB (516 KiB consumed, 173 KiB readable), max entry is 5120 B, max payload is 4068 B
            stats: ring buffer is 256 KiB (0 B consumed, 0 B readable), max entry is 5120 B, max payload is 4068 B
            security: ring buffer is 256 KiB (0 B consumed, 0 B readable), max entry is 5120 B, max payload is 4068 B
            kernel: ring buffer is 2 MiB (0 B consumed, 0 B readable), max entry is 5120 B, max payload is 4068 B
        """.trimIndent()

        assertEquals(
            mapOf(
                "main" to 2L * 1024 * 1024,
                "radio" to 256L * 1024,
                "events" to 256L * 1024,
                "system" to 2L * 1024 * 1024,
                "crash" to 2L * 1024 * 1024,
                "stats" to 256L * 1024,
                "security" to 256L * 1024,
                "kernel" to 2L * 1024 * 1024,
            ),
            parseCompleteLogBufferConfiguration(listing),
        )
    }

    @Test
    fun globalLogTagKeepsSuccessfulEmptyDistinctFromTruncatedOrOversizedReads() {
        assertEquals("", knownGlobalLogTag(""))
        assertEquals("V", knownGlobalLogTag("V\n"))
        assertNull(knownGlobalLogTag("", outputWasTruncated = true))
        assertNull(knownGlobalLogTag("x".repeat(513)))
        assertNull(knownGlobalLogTag("x".repeat(513), outputWasTruncated = true))
    }

    @Test
    fun perTagListingAcceptsCompletePropertiesAndPreservesKnownEmptyResults() {
        val listing = """
            [ro.build.version.release]: [14]
            [log.tag.MyTag]: [D]
            [log.tag.NoisyService]: [S]
            [log.tag.Cleared]: []
        """.trimIndent()
        assertEquals(
            mapOf("MyTag" to "D", "NoisyService" to "S"),
            parseCompletePerTagLogOverrides(listing),
        )
        assertEquals(
            emptyMap(),
            parseCompletePerTagLogOverrides("[ro.build.version.release]: [14]\n[log.tag.Cleared]: []"),
        )

        assertNull(parseCompletePerTagLogOverrides(""))
        assertNull(parseCompletePerTagLogOverrides("[log.tag.Foo]: [D]\nmalformed property output"))
        assertNull(parseCompletePerTagLogOverrides("[log.tag.Foo]: [D]\n[log.tag.Foo]: [W]"))
        assertNull(parseCompletePerTagLogOverrides("[log.tag.Bad/Tag]: [D]"))
        assertNull(parseCompletePerTagLogOverrides("[log.tag.TooLong]: [${"x".repeat(513)}]"))
        assertNull(parseCompletePerTagLogOverrides(listing, outputWasTruncated = true))

        val ordinaryProperties = (1..300).map { "[ro.test.prop$it]: [value]" }
        val completeListing = (ordinaryProperties + listOf("[log.tag.MyTag]: [D]", "[log.tag.OtherTag]: [W]"))
            .joinToString("\n")
        assertEquals(
            mapOf("MyTag" to "D", "OtherTag" to "W"),
            parseCompletePerTagLogOverrides(completeListing),
        )

        val tooManyOverrides = (1..257).joinToString("\n") { "[log.tag.Tag$it]: [D]" }
        assertNull(parseCompletePerTagLogOverrides(tooManyOverrides))

        val overTotalLineLimit = (1..8_193).joinToString("\n") { "[ro.test.prop$it]: [value]" }
        assertNull(parseCompletePerTagLogOverrides(overTotalLineLimit))
    }

    @Test
    fun perTagSnapshotAcceptsCompleteMultilineValuesOnUnrelatedProperties() {
        // The emulator's complete `getprop` listing includes a multiline
        // persist.sys.boot.reason.history value. Keep its contents redacted here; the brackets and
        // continuation-line shape are what the complete snapshot parser needs to validate.
        val listing = """
            [ro.build.version.release]: [14]
            [persist.sys.boot.reason.history]: [<redacted>
            <redacted>
            <redacted>]
            [log.tag.MyTag]: [D]
            [log.tag.Cleared]: []
        """.trimIndent()

        assertEquals(
            mapOf("MyTag" to "D"),
            parseCompletePerTagLogOverrides(listing),
        )
        assertNull(parseCompletePerTagLogOverrides("[prop.multiline]: [partial\n[log.tag.Foo]: [D]"))
    }

    @Test
    fun sessionMetadataRoundTripsKnownEmptyConfigurationAndLeavesLegacyAbsenceUnknown() {
        val directory = Files.createTempDirectory("device-log-config-session").toFile()
        try {
            val configuration = DeviceLoggingConfiguration(
                bufferSizesBytes = mapOf("main" to 1024L),
                globalLogTag = "",
                perTagOverrides = emptyMap(),
            )
            val captureSession = session(directory, configuration)
            File(directory, "session.json").writeText(sessionJson(captureSession))

            assertEquals(configuration, readCaptureSessionDirectory(directory)?.deviceLoggingConfiguration)

            val legacyDirectory = Files.createTempDirectory("legacy-device-log-config-session").toFile()
            try {
                File(legacyDirectory, "session.json").writeText(sessionJson(session(legacyDirectory, null)))
                assertNull(readCaptureSessionDirectory(legacyDirectory)?.deviceLoggingConfiguration)
            } finally {
                legacyDirectory.deleteRecursively()
            }
        } finally {
            directory.deleteRecursively()
        }
    }

    private fun session(directory: File, configuration: DeviceLoggingConfiguration?) = CaptureSession(
        id = "device-log-config-test",
        directory = directory,
        device = CaptureDevice(serial = "SERIAL", state = "device", model = "Pixel"),
        settings = CaptureSettings(),
        startedEpochMs = 1_000,
        status = CaptureStatus.STOPPED,
        deviceLoggingConfiguration = configuration,
    )
}
