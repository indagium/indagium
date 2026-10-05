package com.indagium.testing

import com.indagium.debug.IndagiumToolActionPolicy
import com.indagium.testing.device.TestDeviceSession
import com.indagium.testing.model.ScriptPermission
import com.indagium.testing.run.LaneToolContext
import com.indagium.testing.run.buildLaneTools
import com.indagium.testing.run.cappedGateway
import com.indagium.testing.script.ScriptRunContext
import kotlinx.coroutines.runBlocking
import java.io.File
import kotlin.io.path.createTempDirectory
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** The guard around a lane's gateway: refusals never reach the tool, everything else (and the ASK-script policy) is unchanged. */
class LaneGatewayTest {
    private val adb = ScriptedAdbRunner()
    private val laneDir: File = createTempDirectory("indagium-lane-gateway").toFile()
    private var session: TestDeviceSession? = null

    @AfterTest
    fun tearDown() {
        session?.closeBlocking()
        laneDir.deleteRecursively()
    }

    private fun guarded(refuse: Set<String>) = runBlocking { openFixtureSession(adb, laneDir) }.also { session = it }.let { lane ->
        val tools = buildLaneTools(
            LaneToolContext(
                session = lane,
                currentStep = { null },
                stepLogOffset = { 0L },
                scripts = listOf(hostScript("ask_tool", "printf hi", ScriptPermission.ASK), hostScript("auto_tool", "printf hi", ScriptPermission.AUTO)),
                scriptContext = { ScriptRunContext(runDir = laneDir) },
            ),
        )
        cappedGateway(tools.gateway) { name -> if (name in refuse) "refused: $name" else null }
    }

    @Test
    fun aRefusedToolReturnsTheRefusalAndNeverRuns() {
        val gateway = guarded(setOf("tap"))
        val result = runBlocking { gateway.executeSuspending("tap", mapOf("x" to 1, "y" to 1)) }
        assertEquals(mapOf("error" to "refused: tap"), result)
        assertTrue(adb.shellCommands.isEmpty(), "no adb input was sent")
    }

    @Test
    fun otherToolsPassThroughUnchanged() {
        val gateway = guarded(setOf("tap"))
        val step = runBlocking { gateway.executeSuspending("get_current_step", emptyMap()) } as Map<*, *>
        assertEquals("No step is active.", step["error"])
        val expected = setOf("ask_tool", "auto_tool", "tap", "finish_step")
        assertEquals(expected, gateway.tools.map { it.name }.toSet().intersect(expected))
    }

    @Test
    fun theConfirmationPolicyOfAskScriptsSurvivesTheWrapper() {
        val gateway = guarded(emptySet())
        assertEquals(IndagiumToolActionPolicy.CONFIRMATION_REQUIRED, gateway.actionPolicy("ask_tool"))
        assertEquals(IndagiumToolActionPolicy.AUTOMATIC, gateway.actionPolicy("auto_tool"))
        assertEquals(IndagiumToolActionPolicy.AUTOMATIC, gateway.actionPolicy("tap"))
        assertEquals("Run script 'ask_tool' on this computer", gateway.confirmationDescription("ask_tool"))
    }
}
