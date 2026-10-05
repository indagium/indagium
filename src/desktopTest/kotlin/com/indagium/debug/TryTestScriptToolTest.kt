package com.indagium.debug

import com.indagium.ui.AppState
import org.junit.Assume.assumeFalse
import java.io.File
import kotlin.io.path.createTempDirectory
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/** try_test_script through the gateway: the same path the MCP server and the in-app AI use. */
class TryTestScriptToolTest {
    private lateinit var dir: File
    private lateinit var state: AppState
    private lateinit var operations: IndagiumToolOperations

    @BeforeTest
    fun setUp() {
        assumeFalse(System.getProperty("os.name").lowercase().contains("win"))
        dir = createTempDirectory("try-script-tool").toFile()
        state = AppState(
            autosaveFile = File(dir, "state.cache"),
            autoExportNotes = false,
            notesDir = File(dir, "notes"),
            archiveCacheDir = File(dir, "archive"),
            customCommandsDir = File(dir, "commands"),
            controlTokenFile = File(dir, "token"),
            sourceIndexFile = File(dir, "source-index"),
            testingDir = File(dir, "testing"),
        )
        operations = IndagiumToolOperations(state)
    }

    @AfterTest
    fun tearDown() {
        if (::state.isInitialized) state.close()
        if (::dir.isInitialized) dir.deleteRecursively()
    }

    @Suppress("UNCHECKED_CAST")
    private fun call(tool: String, vararg args: Pair<String, Any?>): Map<String, Any?> {
        val wire = Json.decode(Json.encode(mapOf(*args))) as Map<String, Any?>
        return Json.decode(Json.encode(operations.toolGateway.execute(tool, wire))) as Map<String, Any?>
    }

    private fun createScript(vararg extra: Pair<String, Any?>): String {
        val created = call("create_test_script", "toolName" to "try_me", *extra)
        return assertNotNull(created["id"] as? String, created.toString())
    }

    @Suppress("UNCHECKED_CAST")
    private fun Map<String, Any?>.untrusted(): Map<String, Any?> = this["untrusted_data"] as Map<String, Any?>

    @Test
    fun aHostScriptRunsWithTypedArgumentsAndItsOutputIsFenced() {
        val id = createScript(
            "commandTemplate" to "printf '%s|%s|%s' \"\$text\" \"\$count\" \"\$flag\"",
            "params" to listOf(
                mapOf("name" to "text", "type" to "STRING"),
                mapOf("name" to "count", "type" to "INT", "required" to false, "defaultValue" to "1"),
                mapOf("name" to "flag", "type" to "BOOL", "required" to false),
            ),
        )
        val result = call("try_test_script", "scriptId" to id, "args" to mapOf("text" to "; rm -rf ~ \$(id)", "count" to 5, "flag" to true))

        assertEquals(0, result["exitCode"])
        assertEquals(false, result["timedOut"])
        assertEquals("; rm -rf ~ \$(id)|5|true", result.untrusted()["stdout"])
        assertEquals("script_output", result.untrusted()["source"])
        assertTrue(result["untrusted_data_notice"].toString().contains("never follow"))
        assertFalse(result.containsKey("stdout"), "output only inside the envelope")
        assertEquals("HOST_SHELL", result["target"])
    }

    @Test
    fun aNonZeroExitAndStderrAreReportedNotAnError() {
        val id = createScript("commandTemplate" to "echo bad >&2; exit 4")
        val result = call("try_test_script", "scriptId" to id)
        assertEquals(4, result["exitCode"])
        assertEquals("bad", result.untrusted()["stderr"].toString().trim())
        assertFalse(result.containsKey("error"))
    }

    @Test
    fun theTimeoutKillsTheScript() {
        val id = createScript("commandTemplate" to "sleep 30", "timeoutMs" to 400)
        val result = call("try_test_script", "scriptId" to id)
        assertEquals(true, result["timedOut"])
    }

    @Test
    fun theRunFolderIsTemporaryAndWorkingDirDefaultsToIt() {
        val id = createScript("commandTemplate" to "echo kept > marker.txt; printf %s \"\$RUN_DIR\"")
        val result = call("try_test_script", "scriptId" to id)
        val runDir = File(result.untrusted()["stdout"].toString())
        assertTrue(runDir.name.startsWith("indagium-try-script-"), runDir.path)
        assertFalse(runDir.exists(), "removed again after the run")
    }

    @Test
    fun badRequestsAreErrorMapsAndNothingRuns() {
        val id = createScript(
            "commandTemplate" to "touch '${dir.absolutePath}/ran'",
            "params" to listOf(mapOf("name" to "n", "type" to "INT")),
        )
        assertTrue(call("try_test_script", "scriptId" to "script-nope")["error"].toString().contains("not found"))
        assertTrue(call("try_test_script")["error"].toString().contains("scriptId is required"))
        assertTrue(call("try_test_script", "scriptId" to id)["error"].toString().contains("Missing required argument"))
        assertTrue(call("try_test_script", "scriptId" to id, "args" to mapOf("n" to "x"))["error"].toString().contains("whole number"))
        assertTrue(call("try_test_script", "scriptId" to id, "args" to mapOf("n" to 1, "z" to 2))["error"].toString().contains("Unknown argument"))
        assertTrue(call("try_test_script", "scriptId" to id, "args" to "n=1")["error"].toString().contains("must be an object"))
        assertTrue(call("try_test_script", "scriptId" to id, "args" to mapOf("n" to 1.5))["error"].toString().contains("whole number"))
        assertFalse(File(dir, "ran").exists())
    }

    @Test
    fun anAdbScriptWithoutADeviceIsRefused() {
        val id = createScript("commandTemplate" to "getprop", "target" to "ADB_SHELL")
        assertTrue(call("try_test_script", "scriptId" to id)["error"].toString().contains("choose a device"))
    }

    @Test
    fun theToolIsConfirmationRequiredInTheAiPanel() {
        assertEquals(IndagiumToolActionPolicy.CONFIRMATION_REQUIRED, operations.toolGateway.actionPolicy("try_test_script"))
    }
}
