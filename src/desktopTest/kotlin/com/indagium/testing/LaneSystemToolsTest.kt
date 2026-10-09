package com.indagium.testing

import com.indagium.capture.CompletedFakeProcess
import com.indagium.testing.device.SystemPanel
import com.indagium.testing.device.TestDeviceSession
import com.indagium.testing.run.LaneStepBrief
import com.indagium.testing.run.LaneToolContext
import com.indagium.testing.run.LaneTools
import com.indagium.testing.run.buildLaneTools
import com.indagium.testing.script.ScriptRunContext
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import java.io.File
import kotlin.io.path.createTempDirectory
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

private val GET_ACCELEROMETER = listOf("settings", "get", "system", "accelerometer_rotation")
private val GET_USER_ROTATION = listOf("settings", "get", "system", "user_rotation")
private val PUT_ACCELEROMETER_OFF = listOf("settings", "put", "system", "accelerometer_rotation", "0")

class LaneSystemToolsTest {
    private val runner = ScriptedAdbRunner()
    private val laneDir: File = createTempDirectory("indagium-system-tools").toFile()
    private var session: TestDeviceSession? = null

    @AfterTest
    fun tearDown() {
        session?.closeBlocking()
        laneDir.deleteRecursively()
    }

    private fun open(): TestDeviceSession = runBlocking { openFixtureSession(runner, laneDir) }.also { session = it }

    private fun answerRotationSettings(accelerometer: String = "1\n", user: String = "null\n", exitCode: Int = 0) {
        runner.shellResponder = { arguments ->
            when (arguments.take(GET_ACCELEROMETER.size)) {
                GET_ACCELEROMETER -> CompletedFakeProcess(accelerometer, code = exitCode)
                GET_USER_ROTATION -> CompletedFakeProcess(user, code = exitCode)
                else -> null
            }
        }
    }

    private fun laneTools(lane: TestDeviceSession): LaneTools = buildLaneTools(
        LaneToolContext(
            session = lane,
            currentStep = { LaneStepBrief("step-1", "Case", 1, 1, "Turn the phone", "It is landscape") },
            stepLogOffset = { 0L },
            scripts = emptyList(),
            scriptContext = { ScriptRunContext(packageName = "com.example", runDir = laneDir, caseId = "case-1", stepId = "step-1") },
        ),
    )

    private fun LaneTools.call(name: String, vararg args: Pair<String, Any?>): Map<*, *> =
        runBlocking { gateway.executeSuspending(name, mapOf(*args)) } as Map<*, *>

    @Test
    fun pressKeyReachesPowerVolumeMuteRecentsAndPlayPause() {
        val lane = laneTools(open())
        val expected = mapOf(
            "POWER" to "KEYCODE_POWER",
            "VOLUME_UP" to "KEYCODE_VOLUME_UP",
            "VOLUME_DOWN" to "KEYCODE_VOLUME_DOWN",
            "VOLUME_MUTE" to "KEYCODE_VOLUME_MUTE",
            "APP_SWITCH" to "KEYCODE_APP_SWITCH",
            "MEDIA_PLAY_PAUSE" to "KEYCODE_MEDIA_PLAY_PAUSE",
        )
        for ((name, code) in expected) {
            assertEquals(true, lane.call("press_key", "key" to name)["ok"], name)
            assertEquals(listOf("input", "keyevent", code), runner.shellCommands.last())
        }
        assertTrue(lane.call("press_key", "key" to "SLEEP")["error"].toString().contains("Supported keys"))
    }

    @Test
    fun openSystemPanelRunsOneFixedStatusBarCommandPerPanel() {
        val lane = laneTools(open())
        assertEquals(true, lane.call("open_system_panel", "panel" to "notifications")["ok"])
        assertEquals(listOf("cmd", "statusbar", "expand-notifications"), runner.shellCommands.last())
        lane.call("open_system_panel", "panel" to "quick_settings")
        assertEquals(listOf("cmd", "statusbar", "expand-settings"), runner.shellCommands.last())
        lane.call("open_system_panel", "panel" to "close")
        assertEquals(listOf("cmd", "statusbar", "collapse"), runner.shellCommands.last())

        val before = runner.shellCommands.size
        assertTrue(lane.call("open_system_panel", "panel" to "notifications; reboot")["error"].toString().contains("panel must be one of"))
        assertTrue(lane.call("open_system_panel")["error"].toString().isNotEmpty())
        assertEquals(before, runner.shellCommands.size, "a refused panel never reaches adb")
        assertEquals(setOf("notifications", "quick_settings", "close"), SystemPanel.entries.map { it.toolValue }.toSet())
    }

    @Test
    fun rotateDeviceLocksTheRotationRemembersTheOriginalsOnceAndLaneEndPutsThemBack() {
        answerRotationSettings(accelerometer = "1\n", user = "null\n")
        val lane = open()
        val tools = laneTools(lane)

        assertEquals(true, tools.call("rotate_device", "rotation" to 1)["ok"])
        assertEquals(true, tools.call("rotate_device", "rotation" to 2)["ok"])
        assertEquals(
            listOf(
                GET_ACCELEROMETER, GET_USER_ROTATION, PUT_ACCELEROMETER_OFF, listOf("settings", "put", "system", "user_rotation", "1"),
                PUT_ACCELEROMETER_OFF, listOf("settings", "put", "system", "user_rotation", "2"),
            ),
            runner.shellCommands.toList(),
            "the original settings are read only before the first rotation",
        )

        lane.closeBlocking()
        assertEquals(
            listOf(listOf("settings", "delete", "system", "user_rotation"), listOf("settings", "put", "system", "accelerometer_rotation", "1")),
            runner.shellCommands.toList().takeLast(2),
            "an unset user rotation is deleted again, auto-rotate comes back",
        )
        val afterRestore = runner.shellCommands.size
        lane.closeBlocking()
        assertEquals(afterRestore, runner.shellCommands.size, "the restore happens once")
    }

    @Test
    fun aSetUserRotationIsPutBackToItsValue() {
        answerRotationSettings(accelerometer = "0\n", user = "3\n")
        val lane = open()
        runBlocking { lane.rotateDevice(0) }
        lane.closeBlocking()
        assertEquals(
            listOf(listOf("settings", "put", "system", "user_rotation", "3"), listOf("settings", "put", "system", "accelerometer_rotation", "0")),
            runner.shellCommands.toList().takeLast(2),
        )
    }

    @Test
    fun rotationIsRefusedWhenItCouldNotBeUndoneOrWhenOutOfRange() {
        answerRotationSettings(exitCode = 1)
        val lane = open()
        val tools = laneTools(lane)
        assertTrue(tools.call("rotate_device", "rotation" to 1)["error"].toString().contains("could not be undone"))
        assertTrue(runner.shellCommands.none { it.getOrNull(1) == "put" }, "nothing was changed")
        assertTrue(tools.call("rotate_device", "rotation" to 4)["error"].toString().contains("rotation must be"))
        assertFailsWith<IllegalArgumentException> { runBlocking { lane.rotateDevice(-1) } }
        lane.closeBlocking()
        assertTrue(runner.shellCommands.none { it.getOrNull(1) == "delete" }, "nothing to restore, so nothing is written at the end")
    }

    @Test
    fun aCancelledLaneStillRestoresTheRotationBecauseItsCleanupIsNonCancellable() = runBlocking {
        answerRotationSettings(accelerometer = "1\n", user = "0\n")
        val lane = open()
        val rotated = CompletableDeferred<Unit>()
        val job = launch(Dispatchers.Default) {
            lane.rotateDevice(3)
            rotated.complete(Unit)
            awaitCancellation()
        }
        rotated.await()
        job.cancelAndJoin()
        withContext(NonCancellable) { lane.close() }
        assertEquals(
            listOf(listOf("settings", "put", "system", "user_rotation", "0"), listOf("settings", "put", "system", "accelerometer_rotation", "1")),
            runner.shellCommands.toList().takeLast(2),
        )
    }

    @Test
    fun afterARotationTheNextTapTakesANewScreenshotForItsCoordinateSpace() {
        answerRotationSettings()
        val lane = open()
        runBlocking { lane.screenshot() }
        val before = runner.specs.count { "screencap" in it.command }
        runBlocking { lane.rotateDevice(1) }
        runBlocking { lane.tap(1, 1) }
        assertEquals(before + 1, runner.specs.count { "screencap" in it.command })
    }
}
