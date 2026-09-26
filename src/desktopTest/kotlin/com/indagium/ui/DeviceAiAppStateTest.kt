package com.indagium.ui

import com.indagium.model.AppSettings
import com.indagium.model.defaultCodexAccountProfile
import kotlinx.coroutines.async
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import java.io.File
import kotlin.io.path.createTempDirectory
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class DeviceAiAppStateTest {
    @Test
    fun restoreMigratesOnlyTheKnownObsoleteBundledCodexPath() {
        val root = createTempDirectory("indagium-codex-path-migration").toFile()
        val cache = File(root, "autosave.cache")
        val stalePath = "/Applications/ChatGPT.app/Contents/Resources/codex"
        val profile = defaultCodexAccountProfile().copy(executablePath = stalePath, selected = true)
        val custom = defaultCodexAccountProfile().copy(
            id = "custom-codex",
            executablePath = "/opt/tools/codex",
            selected = false,
        )
        val settingsJson = AppSettings(aiProviderProfiles = listOf(profile, custom)).settingsJson()
        cache.writeText("$AUTOSAVE_MAGIC_CURRENT\nsettings\t${settingsJson.b64()}\n")
        val currentPath = "/Applications/ChatGPT.app/Contents/Resources/codex-cli/CodexCLI.app/Contents/MacOS/codex"
        val state = AppState(
            autosaveFile = cache,
            restoreOnCreate = true,
            autoExportNotes = false,
            notesDir = File(root, "notes"),
            archiveCacheDir = File(root, "archives"),
            customCommandsDir = File(root, "commands"),
            controlTokenFile = File(root, "control-token"),
            sourceIndexFile = File(root, "source-index"),
            bundledCodexExecutableProvider = { currentPath },
        )
        try {
            assertEquals(currentPath, state.settings.aiProviderProfiles.first { it.id == profile.id }.executablePath)
            assertEquals("/opt/tools/codex", state.settings.aiProviderProfiles.first { it.id == custom.id }.executablePath)
        } finally {
            state.close()
        }
    }

    @Test
    fun externalMcpDeviceApprovalIsOncePerSessionAndReopensForDeviceChangeOrRevocation() = runBlocking {
        val state = appState()
        try {
            val first = async { state.awaitExternalDeviceAiApproval("session-1", "Claude Code", "Pixel (SERIAL-1)") }
            awaitApproval(state, "Pixel (SERIAL-1)")
            state.resolveExternalDeviceAiApproval(state.externalDeviceAiApprovals.single().requestId, accepted = true)
            assertTrue(first.await())
            assertTrue(state.awaitExternalDeviceAiApproval("session-1", "Claude Code", "Pixel (SERIAL-1)"))

            val changedDevice = async { state.awaitExternalDeviceAiApproval("session-1", "Claude Code", "Pixel (SERIAL-2)") }
            awaitApproval(state, "Pixel (SERIAL-2)")
            state.resolveExternalDeviceAiApproval(state.externalDeviceAiApprovals.single().requestId, accepted = true)
            assertTrue(changedDevice.await())

            state.revokeExternalDeviceAiApproval("session-1")
            val afterDisconnect = async { state.awaitExternalDeviceAiApproval("session-1", "Claude Code", "Pixel (SERIAL-2)") }
            awaitApproval(state, "Pixel (SERIAL-2)")
            state.resolveExternalDeviceAiApproval(state.externalDeviceAiApprovals.single().requestId, accepted = false)
            assertFalse(afterDisconnect.await())
        } finally {
            state.close()
        }
    }

    @Test
    fun asynchronousDeviceOperationFailureIsVisibleThroughItsStatusId() = runBlocking {
        val state = appState()
        try {
            val started = state.launchDeviceAiOperation("fixture operation", action = { error("fixture failure") })
            val operationId = started["operationId"] as String
            val status = withTimeout(2_000) {
                while (state.deviceAiOperationStatus(operationId)["status"] == "running") delay(10)
                state.deviceAiOperationStatus(operationId)
            }
            assertEquals("failed", status["status"])
            assertEquals("fixture failure", status["error"])
            assertTrue((state.deviceAiOperationStatus("missing")["error"] as String).contains("Unknown"))
        } finally {
            state.close()
        }
    }

    @Test
    fun newCaptureWaitsForSeparateStopOperationToFinalize() = runBlocking {
        val state = appState()
        val stopFinalization = CompletableDeferred<Unit>()
        try {
            val stop = state.launchDeviceAiOperation("fixture stop", action = { stopFinalization.await() })
            state.trackDeviceAiStopOperation(stop["operationId"] as String, "capture-1")

            assertFailsWith<IllegalStateException> { state.ensureNoDeviceAiStopIsFinalizing() }
            stopFinalization.complete(Unit)
            withTimeout(2_000) {
                while (state.deviceAiOperationStatus(stop["operationId"] as String)["status"] == "running") delay(5)
            }
            state.ensureNoDeviceAiStopIsFinalizing()
        } finally {
            stopFinalization.complete(Unit)
            state.close()
        }
    }

    private suspend fun awaitApproval(state: AppState, deviceLabel: String) {
        withTimeout(1_000) {
            while (state.externalDeviceAiApprovals.none { it.deviceLabel == deviceLabel }) delay(5)
        }
    }

    private fun appState(): AppState {
        val root = createTempDirectory("indagium-device-ai-state").toFile()
        return AppState(
            autosaveFile = File(root, "autosave.cache"),
            autoExportNotes = false,
            notesDir = File(root, "notes"),
            archiveCacheDir = File(root, "archives"),
            customCommandsDir = File(root, "commands"),
            controlTokenFile = File(root, "control-token"),
            sourceIndexFile = File(root, "source-index"),
        )
    }
}
