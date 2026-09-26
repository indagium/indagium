package com.indagium.ai

import com.indagium.debug.IndagiumToolDescriptor
import com.indagium.debug.IndagiumToolGateway
import io.modelcontextprotocol.kotlin.sdk.types.ToolSchema
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.buildJsonObject
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

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
        run.deviceControlApproved = true
        run.deviceControlApprovedSerial = "SERIAL-1"

        coordinator.executeManaged(run, "stop_device_capture", emptyMap())
        val started = coordinator.executeManaged(run, "start_device_capture", emptyMap())
        coordinator.executeManaged(run, "get_filter", emptyMap())

        assertEquals("capture-new", (started.raw as Map<*, *>)["tabId"])
        assertEquals(listOf("capture-old" to "capture-new"), movedTabs)
        assertEquals("capture-new", run.tabId)
        assertEquals("capture-new", run.deviceCaptureTabId)
        assertTrue(run.context.isDeviceCapture)
        assertEquals(listOf("capture-new"), receivedFilterTabs)
    }

    @Test
    fun asksOncePerDevicePinsLiveRunAndMovesItWithANewCapture() = runBlocking {
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

        val initialCount = run.history.size
        val screen = async { coordinator.executeManaged(run, "get_device_screen", emptyMap()) }
        approveNext(run, initialCount)
        assertEquals("c2NyZWVu", screen.await().images.single().base64)
        assertEquals("SERIAL-1", run.deviceControlApprovedSerial)

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
        assertEquals(1, run.history.filterIsInstance<AiRunEvent.ConfirmationRequired>().size)

        val beforeDeviceChange = run.history.size
        val replacement = async {
            coordinator.executeManaged(
                run,
                "start_device_capture",
                mapOf("deviceSerial" to "SERIAL-2", "newCapture" to true),
            )
        }
        approveNext(run, beforeDeviceChange)
        replacement.await()
        assertEquals("SERIAL-2", run.deviceControlApprovedSerial)
        assertEquals("capture-SERIAL-2", run.tabId)
        assertEquals("capture-SERIAL-1" to "capture-SERIAL-2", movedTabs.last())
        assertEquals(2, run.history.filterIsInstance<AiRunEvent.ConfirmationRequired>().size)

        val beforeDisconnect = run.history.size
        val disconnected = coordinator.executeManaged(run, "device_tap", emptyMap())
        assertTrue(disconnected.content.contains("disconnected"))
        assertFalse(run.deviceControlApproved)
        val beforeNextApproval = run.history.size
        val nextControl = async { coordinator.executeManaged(run, "device_tap", emptyMap()) }
        approveNext(run, beforeNextApproval)
        nextControl.await()
        assertEquals(3, run.history.filterIsInstance<AiRunEvent.ConfirmationRequired>().size)
    }

    private suspend fun approveNext(run: AiRun, afterEventIndex: Int) {
        val confirmation = withTimeout(1_000) {
            while (true) {
                val next = run.history.drop(afterEventIndex)
                    .filterIsInstance<AiRunEvent.ConfirmationRequired>()
                    .firstOrNull()
                if (next != null) return@withTimeout next.confirmation
                delay(5)
            }
            error("confirmation timeout")
        }
        assertNotNull(confirmation)
        assertTrue(run.confirmations[confirmation.id]?.complete(true) == true)
    }
}
