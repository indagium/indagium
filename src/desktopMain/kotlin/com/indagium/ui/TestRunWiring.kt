package com.indagium.ui

import com.indagium.ai.ManagedMcpServerLease
import com.indagium.ai.defaultAiProviderFactory
import com.indagium.ai.normalizeAiProviderProfiles
import com.indagium.testing.device.TestDeviceSession
import com.indagium.testing.run.CoordinatorDeps
import com.indagium.testing.run.EngineTuning
import com.indagium.testing.run.LaneAgentFactory
import com.indagium.testing.run.LaneDeviceOpener
import com.indagium.testing.run.TestRunCoordinator
import com.indagium.testing.run.defaultLaneAgentFactory
import com.indagium.testing.run.laneAccountRunnerFactory
import com.indagium.testing.script.TestScriptRunner
import com.indagium.testing.store.TestRunStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

// Connects the AI test-run coordinator (testing/run, which knows nothing of AppState) to the app: where runs are stored,
// which AI profiles and keys exist, how a lane opens its device and how it starts an agent. Production uses the
// defaults; tests replace pieces through AppState.testRunOverrides BEFORE the first use of the coordinator, so no
// real adb, CLI or model is needed.

/** Test seams for the run coordinator. A null piece means "the production behaviour". */
internal class TestRunOverrides(
    val openDevice: LaneDeviceOpener? = null,
    val agentFactory: LaneAgentFactory? = null,
    val deviceProblem: (suspend (serial: String) -> String?)? = null,
    val scriptRunner: TestScriptRunner? = null,
    val tuning: EngineTuning = EngineTuning(),
)

private const val LIVE_CAPTURE_PROBLEM = "is held by the live capture in the main window; stop that capture or choose another device."

/** Builds the app's coordinator. [baseDir] is where run folders go; [onChanged] is called after every change of a run. */
internal fun AppState.createTestRunCoordinator(overrides: TestRunOverrides, baseDir: () -> File, onChanged: () -> Unit): TestRunCoordinator {
    val openDevice = overrides.openDevice ?: LaneDeviceOpener { serial, laneDir, recordVideo ->
        val tools = withContext(Dispatchers.IO) { captureService.toolsForStart(settings.captureSettings) }
        TestDeviceSession.open(serial, laneDir, tools, recordVideo = recordVideo, isLiveCaptureSerial = { it == liveCaptureSerial() })
    }
    val agentFactory = overrides.agentFactory ?: defaultLaneAgentFactory(
        defaultAiProviderFactory,
        laneAccountRunnerFactory { run, gateway -> ManagedMcpServerLease.start(this, run, gateway) },
    )
    val deviceProblem = overrides.deviceProblem ?: { serial -> productionDeviceProblem(serial) }
    return TestRunCoordinator(
        CoordinatorDeps(
            library = { testLibrary },
            limits = { editionService.limits() },
            store = TestRunStore(baseDir),
            profiles = { normalizeAiProviderProfiles(settings.aiProviderProfiles) },
            apiKey = ::aiProviderApiKey,
            deviceProblem = deviceProblem,
            openDevice = openDevice,
            agentFactory = agentFactory,
            scriptRunner = overrides.scriptRunner ?: TestScriptRunner(),
            tuning = overrides.tuning,
            goldenImage = { suiteId, assetPath -> testGoldenImageFile(suiteId, assetPath)?.takeIf { it.isFile }?.readBytes() },
        ),
        onChanged = onChanged,
    )
}

@Suppress("TooGenericExceptionCaught") // Listing devices runs adb; any failure becomes the message the user sees.
private suspend fun AppState.productionDeviceProblem(serial: String): String? = withContext(Dispatchers.IO) {
    try {
        val devices = aiCaptureDevices()
        val device = devices.firstOrNull { it.serial == serial }
        when {
            device == null -> "Device $serial is not connected."
            !device.available -> "Device $serial is not ready (${device.state})."
            serial == liveCaptureSerial() -> "Device $serial $LIVE_CAPTURE_PROBLEM"
            else -> null
        }
    } catch (failure: Exception) {
        "Could not list the connected devices: ${failure.message ?: failure::class.simpleName}"
    }
}
