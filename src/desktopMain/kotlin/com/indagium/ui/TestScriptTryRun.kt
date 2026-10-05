package com.indagium.ui

import com.indagium.testing.model.ScriptTarget
import com.indagium.testing.model.TestScript
import com.indagium.testing.script.AdbScriptTarget
import com.indagium.testing.script.ScriptRunContext
import com.indagium.testing.script.ScriptRunOutcome
import com.indagium.testing.script.TestScriptRunner
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import java.io.File
import java.nio.file.Files

// "Try it" for a library script: runs it once with the arguments the author typed (the Scripts screen) or sent
// (the try_test_script MCP tool), outside any test run. RUN_DIR is a fresh temporary folder that is removed again
// when the script ends, so a script that writes files does not leave them behind.

private const val TRY_RUN_DIR_PREFIX = "indagium-try-script-"

/**
 * Runs [script] once. An ADB_SHELL script needs [deviceSerial]; for a HOST_SHELL script it is optional and only
 * becomes the DEVICE variable. Suspends without blocking its caller; cancelling the caller kills the process.
 */
@Suppress("TooGenericExceptionCaught") // adb resolution can fail in several ways; every one is reported as a rejection.
internal suspend fun AppState.tryTestScript(script: TestScript, args: Map<String, String>, deviceSerial: String?): ScriptRunOutcome {
    val serial = deviceSerial?.trim()?.takeIf(String::isNotEmpty)
    if (script.target == ScriptTarget.ADB_SHELL && serial == null) {
        return ScriptRunOutcome.Rejected("This script runs on a device; choose a device to run it on.")
    }
    val adb = if (script.target == ScriptTarget.ADB_SHELL) {
        val tools = try {
            withContext(Dispatchers.IO) { captureService.toolsForStart(settings.captureSettings) }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: Exception) {
            return ScriptRunOutcome.Rejected("adb is not available: ${failure.message ?: failure::class.simpleName}")
        }
        AdbScriptTarget(tools, requireNotNull(serial))
    } else {
        null
    }
    val runDir = withContext(Dispatchers.IO) { Files.createTempDirectory(TRY_RUN_DIR_PREFIX).toFile() }
    try {
        return TestScriptRunner().run(script, args, ScriptRunContext(deviceSerial = serial.orEmpty(), runDir = runDir), adb)
    } finally {
        withContext(Dispatchers.IO + NonCancellable) { deleteQuietly(runDir) }
    }
}

private fun deleteQuietly(directory: File) {
    runCatching { directory.deleteRecursively() }
}
