package com.indagium.ui

import com.indagium.capture.CaptureCommandResult
import com.indagium.capture.CaptureDevice
import com.indagium.capture.CaptureExecutable
import com.indagium.capture.CaptureProcessRunner
import com.indagium.capture.CaptureProcessSpec
import com.indagium.capture.CaptureTools
import com.indagium.capture.NativeMediaSupport
import com.indagium.capture.RunningCaptureProcess
import java.io.File
import java.io.IOException
import java.time.Duration
import java.util.Collections
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.io.path.createTempDirectory
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class CaptureStartShutdownTest {
    @Test
    fun shutdownDuringDeviceLoggingReadDoesNotStartOrPublishCapture() {
        val root = createTempDirectory("capture-start-shutdown").toFile()
        val runner = BlockingConfigurationRunner()
        val app = AppState(
            autosaveFile = File(root, "state.cache"),
            autoExportNotes = false,
            notesDir = File(root, "notes"),
            archiveCacheDir = File(root, "archive"),
            customCommandsDir = File(root, "commands"),
            controlTokenFile = File(root, "token"),
            sourceIndexFile = File(root, "source-index"),
            testingDir = File(root, "testing"),
        )

        try {
            app.captureToolsProvider = { CaptureTools(CaptureExecutable("adb"), null, runner) }
            app.captureMediaSupportProvider = { NativeMediaSupport(available = true) }
            app.updateSettings {
                it.copy(captureSettings = it.captureSettings.copy(recordVideo = false, mirror = false, freeSpaceReserveBytes = 0))
            }

            val tabId = assertNotNull(
                app.startCaptureTab(CaptureDevice("shutdown-test-serial", "device", "Pixel")),
            )
            assertTrue(runner.configurationProbeStarted.await(5, TimeUnit.SECONDS), "capture should enter its device logging read")

            app.close()
            runner.releaseConfigurationProbe.countDown()
            awaitUntil { !app.captureStartInProgress }

            assertTrue(runner.startSpecs.isEmpty(), "shutdown must prevent logcat and media process launch")
            assertTrue(app.tabs.none { it.id == tabId }, "a capture tab must not publish after shutdown")
            assertNull(app.captureControllerFor(tabId), "shutdown must release the pending controller")
            assertFalse(app.captureStartInProgress)
        } finally {
            runner.releaseConfigurationProbe.countDown()
            app.close()
            root.deleteRecursively()
        }
    }

    private class BlockingConfigurationRunner : CaptureProcessRunner {
        val configurationProbeStarted = CountDownLatch(1)
        val releaseConfigurationProbe = CountDownLatch(1)
        val startSpecs = Collections.synchronizedList(mutableListOf<CaptureProcessSpec>())
        private val blocked = java.util.concurrent.atomic.AtomicBoolean(false)

        override fun run(
            spec: CaptureProcessSpec,
            timeout: Duration,
            outputLimitBytes: Int,
        ): CaptureCommandResult {
            if (spec.command.takeLast(4) == listOf("logcat", "-b", "all", "-g") && blocked.compareAndSet(false, true)) {
                configurationProbeStarted.countDown()
                check(releaseConfigurationProbe.await(5, TimeUnit.SECONDS)) { "test did not release the configuration probe" }
            }
            return CaptureCommandResult(exitCode = 0, stdout = byteArrayOf(), stderr = byteArrayOf())
        }

        override fun start(spec: CaptureProcessSpec): RunningCaptureProcess {
            startSpecs += spec
            throw IOException("Unexpected process launch during the shutdown test")
        }
    }

    private fun awaitUntil(timeoutMs: Long = 5_000, condition: () -> Boolean) {
        val deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(timeoutMs)
        while (!condition()) {
            check(System.nanoTime() < deadline) { "condition was not met within ${timeoutMs}ms" }
            Thread.sleep(10)
        }
    }
}
