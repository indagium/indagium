package com.indagium.ai

import com.indagium.debug.IndagiumToolDescriptor
import com.indagium.debug.IndagiumToolGateway
import io.modelcontextprotocol.kotlin.sdk.types.ToolSchema
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.buildJsonObject
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * In-app device tool calls (both direct-API runs via [AiAgentRunner] and managed account-agent
 * runs via [ManagedMcpRunRegistry] — both go through [AiToolExecutionCoordinator]) never show a
 * device-approval card: the user's own prompt that started the run is the authorization. This
 * class instead covers what's left of the device-tool bookkeeping — tab pinning, the bound-serial
 * auto-fill across calls, device-switch handling, and disconnect handling. External MCP clients
 * keep their own per-session approval; see ControlServer.executeExternalDeviceTool and
 * AppState.executeExternalDeviceAiAction, which never route through this coordinator.
 */
class AiDeviceToolApprovalTest {
    @Test
    fun startAfterStopRebindsSessionRunAndLaterTabToolsEvenWithDefaultNewCaptureFlag() = runBlocking {
        val movedTabs = mutableListOf<Pair<String, String>>()
        val receivedFilterTabs = mutableListOf<String>()
        val gateway = IndagiumToolGateway(
            catalog = listOf("stop_device_capture", "start_device_capture", "get_filter").map { name ->
                IndagiumToolDescriptor(name, "", ToolSchema(properties = buildJsonObject { }))
            },
            handlers = mapOf(
                "stop_device_capture" to { args -> mapOf("tabId" to args["tabId"], "status" to "stopped") },
                "start_device_capture" to { args ->
                    assertEquals("SERIAL-1", args["deviceSerial"])
                    mapOf("tabId" to "capture-new", "deviceSerial" to "SERIAL-1")
                },
                "get_filter" to { args ->
                    receivedFilterTabs += args["tabId"] as String
                    mapOf("ok" to true)
                },
            ),
        )
        val coordinator = AiToolExecutionCoordinator(gateway, onCaptureTabChanged = { from, to -> movedTabs += from to to })
        val run = AiRun(tabId = "capture-old", context = AiInvestigationContext("capture-old", isDeviceCapture = true))
        run.deviceBoundSerial = "SERIAL-1"

        coordinator.executeManaged(run, "stop_device_capture", emptyMap())
        val started = coordinator.executeManaged(run, "start_device_capture", emptyMap())
        coordinator.executeManaged(run, "get_filter", emptyMap())

        assertEquals("capture-new", (started.raw as Map<*, *>)["tabId"])
        assertEquals(listOf("capture-old" to "capture-new"), movedTabs)
        assertEquals("capture-new", run.tabId)
        assertEquals("capture-new", run.deviceCaptureTabId)
        assertTrue(run.context.isDeviceCapture)
        assertEquals(listOf("capture-new"), receivedFilterTabs)
        assertTrue(run.history.filterIsInstance<AiRunEvent.ConfirmationRequired>().isEmpty())
    }

    @Test
    fun deviceToolsRunWithoutApprovalAndTrackTheBoundSerialAcrossSwitchesAndDisconnects() = runBlocking {
        val receivedTabIds = mutableListOf<String>()
        val movedTabs = mutableListOf<Pair<String, String>>()
        val gateway = IndagiumToolGateway(
            catalog = listOf("start_device_capture", "get_device_screen", "device_tap").map { name ->
                IndagiumToolDescriptor(name, "", ToolSchema(properties = buildJsonObject { }))
            },
            handlers = mapOf(
                "start_device_capture" to { args ->
                    val serial = args["deviceSerial"] as String
                    mapOf("tabId" to "capture-$serial", "deviceSerial" to serial)
                },
                "get_device_screen" to { args ->
                    receivedTabIds += args["tabId"] as String
                    mapOf("deviceSerial" to "SERIAL-1", "imageBase64" to "c2NyZWVu", "mimeType" to "image/jpeg")
                },
                "device_tap" to { _: Map<String, Any?> -> mapOf("error" to "Android device SERIAL-2 is disconnected") },
            ),
        )
        val coordinator = AiToolExecutionCoordinator(gateway, onCaptureTabChanged = { from, to -> movedTabs += from to to })
        val run = AiRun(tabId = "capture-old")

        // No card and no pending confirmation for a device tool call from the in-app panel.
        val screen = coordinator.executeManaged(run, "get_device_screen", emptyMap())
        assertEquals(0, run.pendingConfirmationCount)
        assertEquals("c2NyZWVu", screen.images.single().base64)
        assertEquals("SERIAL-1", run.deviceBoundSerial)

        val replacementResult = coordinator.executeManaged(
            run,
            "start_device_capture",
            mapOf("deviceSerial" to "SERIAL-1", "newCapture" to true),
        )
        assertEquals("capture-SERIAL-1", replacementResult.raw.let { (it as Map<*, *>)["tabId"] })
        assertEquals("capture-SERIAL-1", run.tabId)
        assertEquals(listOf("capture-old" to "capture-SERIAL-1"), movedTabs)
        coordinator.executeManaged(run, "get_device_screen", emptyMap())
        assertEquals(listOf("capture-old", "capture-SERIAL-1"), receivedTabIds)

        // Requesting a different device drops the previously bound serial before the call runs,
        // so the result below re-binds it to the device that actually started.
        val replacement = coordinator.executeManaged(
            run,
            "start_device_capture",
            mapOf("deviceSerial" to "SERIAL-2", "newCapture" to true),
        )
        assertEquals("capture-SERIAL-2", (replacement.raw as Map<*, *>)["tabId"])
        assertEquals("SERIAL-2", run.deviceBoundSerial)
        assertEquals("capture-SERIAL-2", run.tabId)
        assertEquals("capture-SERIAL-1" to "capture-SERIAL-2", movedTabs.last())

        // A disconnect result clears the bound serial so a later call won't auto-fill a stale one.
        val disconnected = coordinator.executeManaged(run, "device_tap", emptyMap())
        assertTrue(disconnected.content.contains("disconnected"))
        assertNull(run.deviceBoundSerial)

        // Still no confirmation anywhere in this run's whole history.
        assertTrue(run.history.filterIsInstance<AiRunEvent.ConfirmationRequired>().isEmpty())
    }
}
