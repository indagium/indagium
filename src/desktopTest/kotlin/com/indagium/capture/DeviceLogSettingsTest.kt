package com.indagium.capture

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class DeviceLogSettingsTest {
    // Sample outputs below are hand-written to match real `adb logcat -g` wording seen across
    // Android/platform-tools releases: lowercase "Kb"/"Mb" units on older devices, uppercase "KB"/
    // "MB" on newer ones, and "KiB" on some vendor builds — see parseLogcatBufferSizes' own doc.

    @Test
    fun parsesLowercaseUnitFormatFromOlderPlatformTools() {
        val output = """
            main: ring buffer is 256Kb (10Kb consumed), max entry is 5120b, max payload is 4068b
            system: ring buffer is 256Kb (5Kb consumed), max entry is 5120b, max payload is 4068b
            crash: ring buffer is 256Kb (1Kb consumed), max entry is 5120b, max payload is 4068b
        """.trimIndent()

        val sizes = parseLogcatBufferSizes(output)

        assertEquals(3, sizes.size)
        assertEquals(LogBufferSize("main", 256L * 1024, 10L * 1024, 5120), sizes[0])
        assertEquals(LogBufferSize("system", 256L * 1024, 5L * 1024, 5120), sizes[1])
        assertEquals(LogBufferSize("crash", 256L * 1024, 1L * 1024, 5120), sizes[2])
    }

    @Test
    fun parsesUppercaseUnitFormatWithMegabytes() {
        val output = "main: ring buffer is 1MB (512KB consumed), max entry is 5120B, max payload is 4068B"

        val sizes = parseLogcatBufferSizes(output)

        assertEquals(1, sizes.size)
        assertEquals("main", sizes[0].buffer)
        assertEquals(1024L * 1024L, sizes[0].sizeBytes)
        assertEquals(512L * 1024L, sizes[0].consumedBytes)
        assertEquals(5120L, sizes[0].maxEntryBytes)
    }

    @Test
    fun parsesKibibyteUnitAndFractionalMegabytes() {
        val kib = parseLogcatBufferSizes("main: ring buffer is 256KiB (0KiB consumed), max entry is 5120B, max payload is 4068B")
        assertEquals(256L * 1024L, kib.single().sizeBytes)

        val fractional = parseLogcatBufferSizes("main: ring buffer is 1.50MB (0.00MB consumed)")
        assertEquals((1.5 * 1024 * 1024).toLong(), fractional.single().sizeBytes)
    }

    @Test
    fun toleratesUnknownOrMalformedLinesWithoutFailingTheWholeRead() {
        val output = """
            main: ring buffer is 256KB (10KB consumed), max entry is 5120B, max payload is 4068B
            some unexpected diagnostic line from a custom ROM
            ---- end of logcat ----

            system: ring buffer is 256KB (5KB consumed), max entry is 5120B, max payload is 4068B
        """.trimIndent()

        val sizes = parseLogcatBufferSizes(output)

        assertEquals(2, sizes.size)
        assertEquals(setOf("main", "system"), sizes.map { it.buffer }.toSet())
    }

    @Test
    fun emptyOrCompletelyUnrecognizedOutputYieldsNoBuffersInsteadOfThrowing() {
        assertTrue(parseLogcatBufferSizes("").isEmpty())
        assertTrue(parseLogcatBufferSizes("adb: no devices/emulators found").isEmpty())
    }

    @Test
    fun matchingBufferSizeChoiceRequiresEveryBufferToAgreeOnAKnownSize() {
        val allOneMib = listOf(
            LogBufferSize("main", LogBufferSizeChoice.SIZE_1M.bytes),
            LogBufferSize("system", LogBufferSizeChoice.SIZE_1M.bytes),
        )
        assertEquals(LogBufferSizeChoice.SIZE_1M, matchingBufferSizeChoice(allOneMib))

        val mismatched = listOf(
            LogBufferSize("main", LogBufferSizeChoice.SIZE_1M.bytes),
            LogBufferSize("system", LogBufferSizeChoice.SIZE_4M.bytes),
        )
        assertNull(matchingBufferSizeChoice(mismatched))

        val offChoice = listOf(LogBufferSize("main", 123_456L))
        assertNull(matchingBufferSizeChoice(offChoice))

        assertNull(matchingBufferSizeChoice(emptyList()))
    }

    @Test
    fun mainBufferIsSmallOnlyFlagsAMainBufferUnderOneMebibyte() {
        assertTrue(mainBufferIsSmall(listOf(LogBufferSize("main", 256L * 1024))))
        assertTrue(!mainBufferIsSmall(listOf(LogBufferSize("main", 1024L * 1024))))
        assertTrue(!mainBufferIsSmall(listOf(LogBufferSize("system", 256L * 1024))))
        assertTrue(!mainBufferIsSmall(emptyList()))
    }

    @Test
    fun formatBufferSizesCompactJoinsEveryBufferWithItsShortSize() {
        val sizes = listOf(
            LogBufferSize("main", 256L * 1024),
            LogBufferSize("system", 256L * 1024),
            LogBufferSize("crash", 64L * 1024),
        )
        assertEquals("main 256 KB · system 256 KB · crash 64 KB", formatBufferSizesCompact(sizes))
    }

    @Test
    fun formatBufferSizeShortPicksTheLargestSensibleUnit() {
        assertEquals("?", formatBufferSizeShort(null))
        assertEquals("512 B", formatBufferSizeShort(512))
        assertEquals("256 KB", formatBufferSizeShort(256L * 1024))
        assertEquals("1 MB", formatBufferSizeShort(1024L * 1024))
        assertEquals("1.5 MB", formatBufferSizeShort((1.5 * 1024 * 1024).toLong()))
    }

    @Test
    fun logTagLevelParsesTheSixSettableLettersCaseInsensitively() {
        assertEquals(LogTagLevel.VERBOSE, LogTagLevel.fromPropValue("V"))
        assertEquals(LogTagLevel.DEBUG, LogTagLevel.fromPropValue("d"))
        assertEquals(LogTagLevel.INFO, LogTagLevel.fromPropValue("I"))
        assertEquals(LogTagLevel.WARN, LogTagLevel.fromPropValue("W"))
        assertEquals(LogTagLevel.ERROR, LogTagLevel.fromPropValue("e"))
        assertEquals(LogTagLevel.SILENT, LogTagLevel.fromPropValue("S"))
    }

    @Test
    fun logTagLevelParsesAssertUnderBothOfItsLetters() {
        assertEquals(LogTagLevel.ASSERT, LogTagLevel.fromPropValue("A"))
        assertEquals(LogTagLevel.ASSERT, LogTagLevel.fromPropValue("F"))
    }

    @Test
    fun logTagLevelTreatsBlankOrUnknownAsDeviceDefault() {
        assertNull(LogTagLevel.fromPropValue(""))
        assertNull(LogTagLevel.fromPropValue("   "))
        assertNull(LogTagLevel.fromPropValue("nonsense"))
    }

    @Test
    fun selectableLevelsExcludeAssertAndSilentIsLast() {
        assertEquals(
            listOf(LogTagLevel.VERBOSE, LogTagLevel.DEBUG, LogTagLevel.INFO, LogTagLevel.WARN, LogTagLevel.ERROR, LogTagLevel.SILENT),
            LogTagLevel.selectable,
        )
    }

    @Test
    fun parseGetpropListingReadsBracketedKeyValueLines() {
        val output = """
            [ro.build.version.release]: [14]
            [log.tag.MyTag]: [D]
            [persist.log.tag]: []
        """.trimIndent()

        val props = parseGetpropListing(output)

        assertEquals("14", props["ro.build.version.release"])
        assertEquals("D", props["log.tag.MyTag"])
        assertEquals("", props["persist.log.tag"])
    }

    @Test
    fun perTagLogLevelOverridesFiltersToLogTagKeysAndStripsThePrefix() {
        val output = """
            [ro.build.version.release]: [14]
            [log.tag.MyTag]: [D]
            [log.tag.NoisyService]: [S]
            [log.tag]: [V]
            [log.tag.Cleared]: []
        """.trimIndent()

        val overrides = perTagLogLevelOverrides(output)

        assertEquals(mapOf("MyTag" to "D", "NoisyService" to "S"), overrides)
    }

    @Test
    fun perTagLogLevelOverridesIsEmptyWhenNoneArePresent() {
        assertTrue(perTagLogLevelOverrides("[ro.build.version.release]: [14]").isEmpty())
        assertTrue(perTagLogLevelOverrides("").isEmpty())
    }

    @Test
    fun bufferSizeButtonLabelShowsTheMatchingChoiceLabel() {
        val allFourMib = listOf(LogBufferSize("main", LogBufferSizeChoice.SIZE_4M.bytes))
        assertEquals("4 MB", bufferSizeButtonLabel(allFourMib))
    }

    @Test
    fun bufferSizeButtonLabelShowsTheActualSizeWhenOffMenu() {
        val twoMib = listOf(
            LogBufferSize("main", 2L * 1024 * 1024),
            LogBufferSize("system", 2L * 1024 * 1024),
        )
        assertEquals("2 MB", bufferSizeButtonLabel(twoMib))
    }

    @Test
    fun bufferSizeButtonLabelShowsMixedWhenBuffersDisagree() {
        val mismatched = listOf(
            LogBufferSize("main", LogBufferSizeChoice.SIZE_1M.bytes),
            LogBufferSize("system", LogBufferSizeChoice.SIZE_4M.bytes),
        )
        assertEquals("Mixed", bufferSizeButtonLabel(mismatched))
    }

    @Test
    fun bufferSizeButtonLabelShowsUnknownBeforeAnyRead() {
        assertEquals("Unknown", bufferSizeButtonLabel(emptyList()))
    }

    @Test
    fun logLevelButtonLabelShowsDeviceDefaultForNull() {
        assertEquals("Default (device)", logLevelButtonLabel(null))
        assertEquals("Verbose", logLevelButtonLabel(LogTagLevel.VERBOSE))
    }

    @Test
    fun formatDeviceLogStatusLineJoinsBuffersAndTagLevel() {
        val sizes = listOf(
            LogBufferSize("main", 4L * 1024 * 1024),
            LogBufferSize("system", 4L * 1024 * 1024),
        )
        assertEquals(
            "On device: main 4 MB · system 4 MB · log.tag = V (Verbose)",
            formatDeviceLogStatusLine(sizes, LogTagLevel.VERBOSE),
        )
    }

    @Test
    fun formatDeviceLogStatusLineReportsDeviceDefaultWhenLogTagIsUnset() {
        val sizes = listOf(LogBufferSize("main", 4L * 1024 * 1024))
        assertEquals("On device: main 4 MB · log.tag not set (device default)", formatDeviceLogStatusLine(sizes, null))
    }

    @Test
    fun formatDeviceLogStatusLineReportsUnavailableBuffersBeforeAnyRead() {
        assertEquals(
            "On device: buffer info unavailable · log.tag not set (device default)",
            formatDeviceLogStatusLine(emptyList(), null),
        )
    }
}
