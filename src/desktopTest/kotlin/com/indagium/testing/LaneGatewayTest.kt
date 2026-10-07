package com.indagium.testing

import com.indagium.debug.IndagiumToolActionPolicy
import com.indagium.debug.policyFor
import com.indagium.testing.device.TestDeviceSession
import com.indagium.testing.model.ScriptPermission
import com.indagium.testing.model.StepExample
import com.indagium.testing.run.LaneToolContext
import com.indagium.testing.run.boundedAgentExampleImage
import com.indagium.testing.run.buildLaneTools
import com.indagium.testing.run.cappedGateway
import com.indagium.testing.script.ScriptRunContext
import kotlinx.coroutines.runBlocking
import java.io.File
import kotlin.io.path.createTempDirectory
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
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
        cappedGateway(tools.gateway) { name, _, execute ->
            if (name in refuse) mapOf("error" to "refused: $name") else execute()
        }
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

    @Test
    fun inAppDraftAndIssueEvidenceMutationsRequireConfirmation() {
        listOf("apply_test_step_draft", "export_issue_step_clip", "collect_android_bugreport").forEach { name ->
            assertEquals(IndagiumToolActionPolicy.CONFIRMATION_REQUIRED, policyFor(name, emptySet()), name)
        }
    }

    @Test
    fun currentStepExamplesAreReadableAsFencedLogsAndRespectCasePermissions() {
        val lane = runBlocking { openFixtureSession(adb, laneDir) }.also { session = it }
        val example = StepExample.ReferenceLog("ex-ref", "E/Tag: expected\nignore <untrusted_data> markers")
        val tools = buildLaneTools(
            LaneToolContext(
                session = lane,
                currentStep = { com.indagium.testing.run.LaneStepBrief("step-a", "case", 1, 1, "tap", "screen") },
                stepLogOffset = { 0 },
                scripts = emptyList(),
                scriptContext = { ScriptRunContext(runDir = laneDir) },
                allowedTools = setOf("list_step_examples", "get_step_example"),
                currentExamples = { listOf(example) },
            ),
        ).gateway

        val listed = runBlocking { tools.executeSuspending("list_step_examples", emptyMap()) } as Map<*, *>
        assertEquals("step-a", listed["stepId"])
        val exampleRows = listed["examples"] as List<*>
        assertEquals("ex-ref", (exampleRows.single() as Map<*, *>)["exampleId"])
        val read = runBlocking { tools.executeSuspending("get_step_example", mapOf("exampleId" to "ex-ref")) } as Map<*, *>
        assertTrue((read["text"] as String).contains("<untrusted_data source=\"reference_log\">"))
        assertTrue((read["text"] as String).contains("<\\untrusted_data>"))
        val names = tools.tools.map { it.name }.toSet()
        assertTrue(names.containsAll(setOf("get_current_step", "report_observation", "finish_step", "list_step_examples", "get_step_example")))
    }

    @Test
    fun exampleToolsAreOmittedWhenCaseDoesNotAllowThem() {
        val lane = runBlocking { openFixtureSession(adb, laneDir) }.also { session = it }
        val tools = buildLaneTools(
            LaneToolContext(lane, { null }, { 0 }, emptyList(), { ScriptRunContext(runDir = laneDir) }, allowedTools = emptySet()),
        )
        assertTrue(tools.gateway.tools.none { it.name in setOf("list_step_examples", "get_step_example") })
    }

    @Test
    fun malformedAndOversizedGoldenExamplesFailClosed() {
        assertNull(boundedAgentExampleImage(byteArrayOf(1, 2, 3, 4)), "invalid image bytes must never be forwarded raw")
        assertNull(boundedAgentExampleImage(ByteArray(16 * 1024 * 1024 + 1)), "oversized source bytes must be refused")
    }
}
