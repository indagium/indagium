package com.indagium.capture

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
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

    // deviceLogStatusText (item 3 of the New-tab flicker fix): a background refresh must never blank
    // a status line the panel already has good cached data for, and only an actual applied change
    // gets the bare "Applying…" treatment — see the function's own doc.
    private val loadedSizes = listOf(LogBufferSize("main", 4L * 1024 * 1024))

    @Test
    fun deviceLogStatusTextShowsNotReadYetBeforeTheFirstReadStarts() {
        assertEquals("Not read yet", deviceLogStatusText(null))
        assertEquals(
            "Not read yet",
            deviceLogStatusText(DeviceLogState(serial = "s1", activity = DeviceLogActivity.IDLE, loaded = false)),
        )
    }

    @Test
    fun deviceLogStatusTextShowsAReadingPlaceholderForTheFirstEverRead() {
        assertEquals(
            "Reading device settings…",
            deviceLogStatusText(DeviceLogState(serial = "s1", activity = DeviceLogActivity.REFRESHING, loaded = false)),
        )
    }

    @Test
    fun deviceLogStatusTextKeepsTheCachedLineDuringABackgroundRefresh() {
        val state = DeviceLogState(
            serial = "s1",
            bufferSizes = loadedSizes,
            globalLevel = LogTagLevel.VERBOSE,
            activity = DeviceLogActivity.REFRESHING,
            loaded = true,
        )
        assertEquals(
            "On device: main 4 MB · log.tag = V (Verbose) · Refreshing…",
            deviceLogStatusText(state),
        )
    }

    @Test
    fun deviceLogStatusTextShowsThePlainCachedLineWhenIdle() {
        val state = DeviceLogState(
            serial = "s1",
            bufferSizes = loadedSizes,
            globalLevel = null,
            activity = DeviceLogActivity.IDLE,
            loaded = true,
        )
        assertEquals("On device: main 4 MB · log.tag not set (device default)", deviceLogStatusText(state))
    }

    @Test
    fun deviceLogStatusTextShowsApplyingEvenWithCachedDataBehindIt() {
        val state = DeviceLogState(
            serial = "s1",
            bufferSizes = loadedSizes,
            globalLevel = LogTagLevel.VERBOSE,
            activity = DeviceLogActivity.APPLYING,
            loaded = true,
        )
        assertEquals("Applying…", deviceLogStatusText(state))
    }

    @Test
    fun deviceLogStateBusyIsTrueForAnyNonIdleActivity() {
        assertFalse(DeviceLogState(serial = "s1", activity = DeviceLogActivity.IDLE).busy)
        assertTrue(DeviceLogState(serial = "s1", activity = DeviceLogActivity.REFRESHING).busy)
        assertTrue(DeviceLogState(serial = "s1", activity = DeviceLogActivity.APPLYING).busy)
        assertTrue(DeviceLogState(serial = "s1", activity = DeviceLogActivity.ROOTING).busy)
    }

    @Test
    fun deviceLogStatusTextShowsARootingMessageWhileRestartingAdb() {
        val state = DeviceLogState(
            serial = "s1",
            bufferSizes = loadedSizes,
            globalLevel = LogTagLevel.VERBOSE,
            activity = DeviceLogActivity.ROOTING,
            loaded = true,
        )
        // Same bare-message treatment as APPLYING: the cached line is about to be stale anyway once
        // adbd finishes restarting, so it isn't worth showing behind this one.
        assertEquals("Restarting adb as root…", deviceLogStatusText(state))
    }

    // isPermissionFailure (the "Restart adb as root" button's trigger): loose, case-insensitive
    // keyword matching against real-world adb/Android wording for a permission problem — see the
    // function's own doc for why it deliberately over-triggers rather than under-triggers.

    @Test
    fun isPermissionFailureMatchesEveryDocumentedKeywordCaseInsensitively() {
        val messages = listOf(
            "Permission denied",
            "permission",
            "Operation not permitted",
            "Failed to set property \"log.tag\" to \"V\"",
            "avc: denied { set } for property=log.tag",
            "SELinux policy prevents this",
            "setprop: failed to set property",
            "Unable to set property 'log.tag' to 'V'",
            "insufficient permissions for device",
            "open failed: EACCES",
        )
        messages.forEach { message ->
            assertTrue(isPermissionFailure(message), "expected a permission match for: $message")
            assertTrue(isPermissionFailure(message.uppercase()), "expected a case-insensitive match for: $message")
        }
    }

    @Test
    fun isPermissionFailureIsFalseForUnrelatedText() {
        assertFalse(isPermissionFailure("device offline"))
        assertFalse(isPermissionFailure(""))
        assertFalse(isPermissionFailure("main: ring buffer is 256KB (0KB consumed)"))
    }

    @Test
    fun isPermissionFailureOnACaptureCommandResultRequiresANonZeroExit() {
        val denied = CaptureCommandResult(exitCode = 1, stdout = ByteArray(0), stderr = "Permission denied".toByteArray())
        assertTrue(isPermissionFailure(denied))

        // A zero exit is never a permission failure, even if the text happens to mention the word
        // in passing (e.g. a getprop dump that legitimately contains it).
        val succeededButMentionsIt = CaptureCommandResult(exitCode = 0, stdout = "permission".toByteArray(), stderr = ByteArray(0))
        assertFalse(isPermissionFailure(succeededButMentionsIt))

        val unrelatedFailure = CaptureCommandResult(exitCode = 1, stdout = ByteArray(0), stderr = "device offline".toByteArray())
        assertFalse(isPermissionFailure(unrelatedFailure))
    }

    // classifyAdbRootOutput (the root-output parsing this feature's task doc calls for): restarting
    // / already root / production refusal / unknown, matched on adb's own real wording.

    @Test
    fun classifyAdbRootOutputRecognizesARestart() {
        assertEquals(AdbRootOutcome.RESTARTING, classifyAdbRootOutput("restarting adbd as root\n"))
        assertEquals(AdbRootOutcome.RESTARTING, classifyAdbRootOutput("* daemon not running; starting now *\nrestarting adbd as root"))
    }

    @Test
    fun classifyAdbRootOutputRecognizesAlreadyRoot() {
        assertEquals(AdbRootOutcome.ALREADY_ROOT, classifyAdbRootOutput("adbd is already running as root\n"))
    }

    @Test
    fun classifyAdbRootOutputRecognizesAProductionBuildRefusal() {
        assertEquals(AdbRootOutcome.PRODUCTION_REFUSAL, classifyAdbRootOutput("adbd cannot run as root in production builds\n"))
    }

    @Test
    fun classifyAdbRootOutputFallsBackToUnknownForAnythingElse() {
        assertEquals(AdbRootOutcome.UNKNOWN, classifyAdbRootOutput(""))
        assertEquals(AdbRootOutcome.UNKNOWN, classifyAdbRootOutput("error: closed"))
        assertEquals(AdbRootOutcome.UNKNOWN, classifyAdbRootOutput("error: no devices/emulators found"))
    }
}
