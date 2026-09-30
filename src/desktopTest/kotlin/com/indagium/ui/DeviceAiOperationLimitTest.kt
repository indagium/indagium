@file:Suppress("MagicNumber")

package com.indagium.ui

import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class DeviceAiOperationLimitTest {
    private lateinit var state: AppState

    @BeforeTest
    fun setUp() {
        state = AppState(autosaveFile = File.createTempFile("openlog-device-ai-limit", ".cache"))
    }

    @AfterTest
    fun tearDown() {
        state.close()
    }

    @Test
    fun refusesToLaunchMoreThanTheRunningLimitUntilOneFinishes() {
        val release = CountDownLatch(1)
        try {
            val running = (1..MAX_RUNNING_DEVICE_AI_OPERATIONS).map { index ->
                state.launchDeviceAiOperation("blocked operation $index", action = {
                    release.await(20, TimeUnit.SECONDS)
                    index
                })
            }
            running.forEach { assertNotNull(it["operationId"]) }

            val refused = state.launchDeviceAiOperation("one too many", action = { "never runs" })
            assertNull(refused["operationId"])
            assertEquals("Too many device operations are running; wait for one to finish", refused["error"])

            release.countDown()
            val ids = running.map { it["operationId"] as String }
            awaitUntil { ids.all { state.deviceAiOperationStatus(it)["status"] == "completed" } }

            val accepted = state.launchDeviceAiOperation("after the others finished", action = { "ok" })
            assertNotNull(accepted["operationId"])
            assertNull(accepted["error"])
        } finally {
            release.countDown()
        }
    }

    private fun awaitUntil(timeoutMs: Long = 10_000, condition: () -> Boolean) {
        val deadline = System.nanoTime() + timeoutMs * 1_000_000L
        while (!condition()) {
            assertTrue(System.nanoTime() < deadline, "condition not met within ${timeoutMs}ms")
            Thread.sleep(10)
        }
    }
}
