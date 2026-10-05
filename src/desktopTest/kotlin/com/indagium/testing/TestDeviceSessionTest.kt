package com.indagium.testing

import com.indagium.debug.mapDisplayedScreenCoordinatesToDevice
import com.indagium.testing.device.LogAbsentResult
import com.indagium.testing.device.LogWaitResult
import com.indagium.testing.device.TestDeviceSession
import com.indagium.testing.device.parseUiAutomatorDump
import kotlinx.coroutines.runBlocking
import java.io.File
import kotlin.concurrent.thread
import kotlin.io.path.createTempDirectory
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

private const val SETTLE_MS = 5_000L
private const val NANOS_PER_MS = 1_000_000L

class TestDeviceSessionTest {
    private val runner = ScriptedAdbRunner()
    private val laneDir: File = createTempDirectory("indagium-lane-test").toFile()
    private var session: TestDeviceSession? = null

    @AfterTest
    fun tearDown() {
        session?.closeBlocking()
        laneDir.deleteRecursively()
    }

    private fun open(isLive: (String) -> Boolean = { false }): TestDeviceSession =
        runBlocking { openFixtureSession(runner, laneDir, isLive) }.also { session = it }

    private var emittedBytes = 0L

    private fun emit(text: String) {
        emittedBytes += text.toByteArray().size
        runner.logcat.emit(text)
    }

    /** Waits until the recorder has written everything emitted so far to the file. */
    private fun settle(session: TestDeviceSession) {
        val deadline = System.nanoTime() + SETTLE_MS * NANOS_PER_MS
        while (runBlocking { session.logMarker() } < emittedBytes) {
            check(System.nanoTime() < deadline) { "log never reached $emittedBytes bytes" }
            Thread.sleep(10)
        }
    }

    // ── Opening ────────────────────────────────────────────────────

    @Test
    fun opensALogcatCaptureUnderTheLaneFolder() {
        val lane = open()
        assertTrue(lane.isRecording)
        assertTrue(lane.logFile.path.startsWith(File(laneDir, "capture").path), lane.logFile.path)
        assertTrue(lane.logFile.isFile)
        val logcat = runner.specs.first { "logcat" in it.command }.command
        assertEquals(listOf("adb", "-s", FIXTURE_SERIAL, "logcat"), logcat.take(4))
    }

    @Test
    fun aSerialHeldByTheLiveCaptureIsRefused() {
        val failure = assertFailsWith<IllegalArgumentException> { open { it == FIXTURE_SERIAL } }
        assertTrue(failure.message.orEmpty().contains("live capture"), failure.message)
        assertTrue(runner.specs.isEmpty(), "no adb process was started")
    }

    @Test
    fun closeStopsTheRecorder() {
        val lane = open()
        runBlocking { lane.close() }
        assertFalse(lane.isRecording)
        runBlocking { lane.close() }
    }

    // ── Screen, coordinates, input ─────────────────────────────────

    @Test
    fun aScreenshotIsBoundedAndDefinesTheCoordinateSpace() {
        val lane = open()
        val shot = runBlocking { lane.screenshot() }
        assertEquals(FIXTURE_DEVICE_WIDTH, shot.space.deviceWidth)
        assertEquals(FIXTURE_DEVICE_HEIGHT, shot.space.deviceHeight)
        assertTrue(shot.image.width < FIXTURE_DEVICE_WIDTH, "downscaled for the agent")
        assertTrue(shot.png.isNotEmpty())
    }

    @Test
    fun tapCoordinatesAreMappedFromScreenshotPixelsToDevicePixels() {
        val lane = open()
        val space = runBlocking { lane.screenshot() }.space
        runBlocking { lane.tap(0, 0) }
        runBlocking { lane.tap(space.imageWidth - 1, space.imageHeight - 1) }
        val (midX, midY) = space.imageWidth / 2 to space.imageHeight / 2
        val mid = runBlocking { lane.tap(midX, midY) }
        assertEquals(mapDisplayedScreenCoordinatesToDevice(midX, midY, space).toList(), mid.deviceCoordinates)
        assertEquals(listOf("input", "tap", "0", "0"), runner.shellCommands[0])
        assertEquals(listOf("input", "tap", (FIXTURE_DEVICE_WIDTH - 1).toString(), (FIXTURE_DEVICE_HEIGHT - 1).toString()), runner.shellCommands[1])
    }

    @Test
    fun aTapOutsideTheScreenshotIsRefusedBeforeAnyAdbCall() {
        val lane = open()
        val space = runBlocking { lane.screenshot() }.space
        assertFailsWith<IllegalArgumentException> { runBlocking { lane.tap(space.imageWidth, 0) } }
        assertFailsWith<IllegalArgumentException> { runBlocking { lane.tap(-1, 5) } }
        assertTrue(runner.shellCommands.isEmpty())
    }

    @Test
    fun aTapBeforeAnyScreenshotTakesOneToLearnTheScreenSize() {
        val lane = open()
        val result = runBlocking { lane.tap(10, 20) }
        assertEquals(2, result.deviceCoordinates.size)
        assertTrue(runner.specs.any { "screencap" in it.command })
    }

    @Test
    fun swipeKeyTextLaunchAndUrlUseTheSharedGuards() {
        val lane = open()
        runBlocking { lane.screenshot() }
        runBlocking { lane.swipe(10, 100, 10, 400, 300) }
        assertEquals("swipe", runner.shellCommands.last()[1])
        assertFailsWith<IllegalArgumentException> { runBlocking { lane.swipe(10, 100, 10, 400, 10) } }

        assertEquals("BACK", runBlocking { lane.pressKey("back") })
        assertEquals(listOf("input", "keyevent", "KEYCODE_BACK"), runner.shellCommands.last())
        assertFailsWith<IllegalArgumentException> { runBlocking { lane.pressKey("POWER") } }

        runBlocking { lane.inputText("hello world") }
        assertEquals(listOf("input", "text", "hello%sworld"), runner.shellCommands.last())
        assertFailsWith<IllegalArgumentException> { runBlocking { lane.inputText("rm -rf /; \$(x)") } }

        runBlocking { lane.launchApp("com.example.app") }
        assertEquals(listOf("monkey", "-p", "com.example.app", "-c", "android.intent.category.LAUNCHER", "1"), runner.shellCommands.last())
        assertFailsWith<IllegalArgumentException> { runBlocking { lane.launchApp("not a package; x") } }

        runBlocking { lane.openUrl("https://example.com/a?b=1") }
        assertEquals(listOf("am start -a android.intent.action.VIEW -d 'https://example.com/a?b=1'"), runner.shellCommands.last())
        assertFailsWith<IllegalArgumentException> { runBlocking { lane.openUrl("javascript:alert(1)") } }
        assertFailsWith<IllegalArgumentException> { runBlocking { lane.openUrl("https://x.com/a'b") } }
    }

    @Test
    fun aFailedInputCommandIsReported() {
        val lane = open()
        runBlocking { lane.screenshot() }
        runner.shellExitCode = 1
        val failure = assertFailsWith<IllegalStateException> { runBlocking { lane.pressKey("HOME") } }
        assertTrue(failure.message.orEmpty().contains("input failed"))
        runner.shellStdout = "** No activities found to run"
        runner.shellExitCode = 0
        assertFailsWith<IllegalStateException> { runBlocking { lane.launchApp("com.missing.app") } }
    }

    // ── UI tree ────────────────────────────────────────────────────

    @Test
    fun theUiTreeIsParsedCompactAndConvertedToScreenshotPixels() {
        val lane = open()
        val tree = runBlocking { lane.dumpUiTree() }
        assertEquals(2, tree.nodes.size, "the silent container is dropped, the button and the field stay")
        val button = tree.nodes.first { it.node.resourceId == "com.example:id/ok" }
        assertEquals("Sign & go", button.node.text)
        assertEquals("Button", button.node.className)
        assertTrue(button.node.clickable)
        val space = runBlocking { lane.screenshot() }.space
        // The element's centre (200, 300) on the device, expressed in screenshot pixels, taps the same device pixel back.
        val (deviceX, deviceY) = mapDisplayedScreenCoordinatesToDevice(button.tapX, button.tapY, space)
        assertTrue(kotlin.math.abs(deviceX - 200) <= 2 && kotlin.math.abs(deviceY - 300) <= 3, "($deviceX, $deviceY)")
        assertEquals(space.imageWidth, tree.imageWidth)
        val password = tree.nodes.first { it.node.resourceId == "com.example:id/pw" }
        assertEquals("", password.node.text, "password text never leaves the device")
        assertFalse(password.node.enabled)
    }

    @Test
    fun aRotationBetweenDumpsRefreshesTheCoordinateSpace() {
        val lane = open()
        runBlocking { lane.screenshot() }
        runner.png = fixturePng(FIXTURE_DEVICE_HEIGHT, FIXTURE_DEVICE_WIDTH)
        runner.uiDump = fixtureUiDump(FIXTURE_DEVICE_HEIGHT, FIXTURE_DEVICE_WIDTH)
        val tree = runBlocking { lane.dumpUiTree() }
        assertTrue(tree.imageWidth > tree.imageHeight, "now landscape: ${tree.imageWidth}x${tree.imageHeight}")
    }

    @Test
    fun anEmptyOrBrokenUiDumpIsAnError() {
        val lane = open()
        runner.uiDump = "ERROR: null root node returned by UiTestAutomationBridge."
        val failure = assertFailsWith<IllegalStateException> { runBlocking { lane.dumpUiTree() } }
        assertTrue(failure.message.orEmpty().contains("no UI hierarchy"))
    }

    @Test
    fun theUiTreeIsCappedInSize() {
        val nodes = (0 until 400).joinToString("\n") {
            """<node text="item $it" class="android.widget.TextView" clickable="true" bounds="[0,${it + 1}][10,${it + 5}]" />"""
        }
        val xml = "<hierarchy>\n<node text=\"\" class=\"X\" bounds=\"[0,0][100,2000]\">\n$nodes\n</node></hierarchy>"
        val parsed = assertNotNull(parseUiAutomatorDump(xml))
        assertEquals(250, parsed.nodes.size)
        assertTrue(parsed.truncated)
        assertEquals(401, parsed.totalNodes)
    }

    // ── Log reader ─────────────────────────────────────────────────

    @Test
    fun logMarkersSeparateTheRowsWrittenBeforeFromAfter() {
        val lane = open()
        emit(logRow("before one") + logRow("before two"))
        settle(lane)
        val marker = runBlocking { lane.logMarker() }
        assertTrue(marker > 0)
        emit(logRow("after one", tag = "Net") + "--------- beginning of crash\n" + logRow("after two", level = 'E'))
        settle(lane)
        val read = runBlocking { lane.readLogSince(marker) }
        assertEquals(listOf("after one", "after two"), read.rows.map { it.msg })
        assertEquals(listOf("Net", "App"), read.rows.map { it.tag })
        assertFalse(read.more)
        assertEquals(runBlocking { lane.logMarker() }, read.nextOffset)
        val all = runBlocking { lane.readLogSince(0) }
        assertEquals(4, all.rows.size)
    }

    @Test
    fun readingPagesWithLimitAndFiltersByTagAndRegex() {
        val lane = open()
        emit((1..6).joinToString("") { logRow("row $it", tag = if (it % 2 == 0) "Even" else "Odd") })
        settle(lane)
        val first = runBlocking { lane.readLogSince(0, limit = 2) }
        assertEquals(listOf("row 1", "row 2"), first.rows.map { it.msg })
        assertTrue(first.more)
        val second = runBlocking { lane.readLogSince(first.nextOffset, limit = 2) }
        assertEquals(listOf("row 3", "row 4"), second.rows.map { it.msg })
        assertEquals(listOf("row 2", "row 4", "row 6"), runBlocking { lane.readLogSince(0, tag = "even") }.rows.map { it.msg })
        assertEquals(listOf("row 5"), runBlocking { lane.readLogSince(0, regex = "row [5]") }.rows.map { it.msg })
        assertFailsWith<IllegalArgumentException> { runBlocking { lane.readLogSince(0, regex = "([") } }
    }

    // ── waitForLog / ensureAbsent ──────────────────────────────────

    @Test
    fun waitForLogFindsARowThatArrivesWhileWaiting() {
        val lane = open()
        val marker = runBlocking { lane.logMarker() }
        val writer = thread { Thread.sleep(300); emit(logRow("noise") + logRow("Login OK for user 42", tag = "Auth")) }
        val result = runBlocking { lane.waitForLog("Login OK for user \\d+", "Auth", marker, 4_000) }
        writer.join()
        val matched = assertIs<LogWaitResult.Matched>(result)
        assertEquals("Auth", matched.entry.tag)
        assertTrue(matched.waitedMs in 200..3_500, "waited ${matched.waitedMs} ms")
    }

    @Test
    fun waitForLogCountsRowsWrittenBeforeTheCallWhenTheOffsetIsEarlier() {
        val lane = open()
        emit(logRow("already there"))
        settle(lane)
        val started = System.nanoTime()
        val result = runBlocking { lane.waitForLog("already there", null, 0, 5_000) }
        assertIs<LogWaitResult.Matched>(result)
        assertTrue((System.nanoTime() - started) / 1_000_000 < 2_000, "found at once")
    }

    @Test
    fun waitForLogIgnoresRowsBeforeItsOffsetAndOtherTagsAndTimesOut() {
        val lane = open()
        emit(logRow("target", tag = "Old"))
        settle(lane)
        val marker = runBlocking { lane.logMarker() }
        emit(logRow("target", tag = "Other"))
        val started = System.nanoTime()
        val result = runBlocking { lane.waitForLog("target", "New", marker, 600) }
        val timedOut = assertIs<LogWaitResult.TimedOut>(result)
        assertTrue(timedOut.waitedMs >= 550, "waited ${timedOut.waitedMs} ms")
        assertTrue((System.nanoTime() - started) / 1_000_000 < 3_000)
        assertTrue(timedOut.recording)
    }

    @Test
    fun waitForLogCountsARowThatIsStillInTheRecordersBufferAtTheDeadline() {
        val lane = open()
        val marker = runBlocking { lane.logMarker() }
        emit(logRow("last second"))
        // timeout 0: no polling at all, only the final flush-and-read decides.
        assertIs<LogWaitResult.Matched>(runBlocking { lane.waitForLog("last second", null, marker, 0) }.let { first ->
            if (first is LogWaitResult.TimedOut) runBlocking { lane.waitForLog("last second", null, marker, 2_000) } else first
        })
    }

    @Test
    fun waitForLogRejectsABadRegexAndAnExcessiveTimeout() {
        val lane = open()
        assertFailsWith<IllegalArgumentException> { runBlocking { lane.waitForLog("([", null, 0, 100) } }
        assertFailsWith<IllegalArgumentException> { runBlocking { lane.waitForLog("x", null, 0, 11 * 60 * 1000L) } }
        assertFailsWith<IllegalArgumentException> { runBlocking { lane.waitForLog("x", null, 0, -1) } }
    }

    @Test
    fun ensureAbsentPassesWhenNothingMatchesForTheWholeWindow() {
        val lane = open()
        val marker = runBlocking { lane.logMarker() }
        emit(logRow("all fine"))
        val started = System.nanoTime()
        val result = runBlocking { lane.ensureAbsent("FATAL|crash", null, marker, 700) }
        val absent = assertIs<LogAbsentResult.Absent>(result)
        assertTrue(absent.waitedMs >= 650, "watched ${absent.waitedMs} ms")
        assertTrue((System.nanoTime() - started) / 1_000_000 >= 650)
    }

    @Test
    fun ensureAbsentFailsAsSoonAsAMatchingRowAppears() {
        val lane = open()
        val marker = runBlocking { lane.logMarker() }
        val writer = thread { Thread.sleep(250); emit(logRow("FATAL EXCEPTION: main", tag = "AndroidRuntime", level = 'E')) }
        val result = runBlocking { lane.ensureAbsent("FATAL EXCEPTION", "AndroidRuntime", marker, 5_000) }
        writer.join()
        val found = assertIs<LogAbsentResult.Found>(result)
        assertEquals("AndroidRuntime", found.entry.tag)
        assertTrue(found.waitedMs < 4_000, "returned early after ${found.waitedMs} ms")
    }

    @Test
    fun ensureAbsentOnlyLooksAtTheRequestedTag() {
        val lane = open()
        val marker = runBlocking { lane.logMarker() }
        emit(logRow("FATAL somewhere else", tag = "Other"))
        assertIs<LogAbsentResult.Absent>(runBlocking { lane.ensureAbsent("FATAL", "AndroidRuntime", marker, 300) })
    }
}
