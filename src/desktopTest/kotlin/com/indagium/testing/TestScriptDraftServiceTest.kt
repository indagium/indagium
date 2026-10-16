package com.indagium.testing

import com.indagium.model.AiUsageStats
import com.indagium.testing.authoring.TestScriptDraftService
import com.indagium.testing.model.ScriptParamType
import com.indagium.testing.model.ScriptPermission
import com.indagium.testing.model.ScriptTarget
import com.indagium.testing.model.TestScript
import com.indagium.testing.store.StoreResult
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

class TestScriptDraftServiceTest {
    private val json = """
        {"toolName":"clear_app_data","description":"Clears the app data.",
         "params":[{"name":"package_name","type":"STRING","description":"App package","required":true,"defaultValue":null}],
         "commandTemplate":"pm clear \"${'$'}package_name\"","target":"ADB_SHELL","timeoutMs":15000}
    """.trimIndent()

    private fun service(
        os: String = "Mac OS X",
        answer: (String) -> String = { json },
        prompts: MutableList<String> = mutableListOf(),
    ) = TestScriptDraftService(
        generate = { _, prompt, _, _, onUsage ->
            prompts += prompt
            onUsage(AiUsageStats())
            answer(prompt)
        },
        hostOs = { os },
    )

    private fun ok(result: StoreResult<TestScript>): TestScript = assertIs<StoreResult.Ok<TestScript>>(result).value

    private fun invalid(result: StoreResult<TestScript>): String = assertIs<StoreResult.Invalid>(result).reason

    @Test
    fun parsesUnfencedJson() {
        val script = ok(service().parse(json, emptyList()))
        assertEquals("clear_app_data", script.toolName)
        assertEquals(ScriptTarget.ADB_SHELL, script.target)
        assertEquals(15_000L, script.timeoutMs)
        assertEquals("package_name", script.params.single().name)
        assertEquals(ScriptParamType.STRING, script.params.single().type)
        assertTrue(script.id.isNotBlank())
    }

    @Test
    fun parsesFencedJsonAndLeadingProse() {
        val fenced = "```json\n$json\n```"
        assertEquals("clear_app_data", ok(service().parse(fenced, emptyList())).toolName)
        val prose = "Sure! Here is the script you asked for:\n$fenced\nLet me know if it needs changes."
        assertEquals("clear_app_data", ok(service().parse(prose, emptyList())).toolName)
    }

    @Test
    fun bracesInsideStringsDoNotBreakExtraction() {
        val tricky = """{"toolName":"curly","commandTemplate":"echo '}' && echo {a}"} trailing {"""
        val script = ok(service().parse("text $tricky", emptyList()))
        assertEquals("echo '}' && echo {a}", script.commandTemplate)
        assertEquals(ScriptTarget.HOST_SHELL, script.target)
        assertEquals(30_000L, script.timeoutMs)
    }

    @Test
    fun invalidToolNameIsRejected() {
        val message = invalid(service().parse(json.replace("clear_app_data", "Clear App"), emptyList()))
        assertTrue(message.contains("Tool name"), message)
    }

    @Test
    fun noJsonIsRejected() {
        assertTrue(invalid(service().parse("I cannot do that.", emptyList())).contains("JSON"))
    }

    @Test
    fun takenToolNameIsMadeUnique() {
        val existing = listOf(TestScript(id = "script-a", toolName = "clear_app_data", commandTemplate = "true"))
        assertEquals("clear_app_data_2", ok(service().parse(json, existing)).toolName)
    }

    @Test
    fun reservedEnvironmentParamNamesAreRejectedInAnyCase() {
        listOf("device", "DEVICE", "run_dir", "RUN_DIR", "package", "Case_Id", "step_id").forEach { name ->
            val message = invalid(service().parse(json.replace("package_name", name), emptyList()))
            assertTrue(message.contains(name.uppercase()) || message.contains("reserved"), "$name -> $message")
        }
    }

    @Test
    fun otherReservedParamNamesAreRejectedByTheLibraryRules() {
        assertTrue(invalid(service().parse(json.replace("package_name", "path"), emptyList())).contains("reserved"))
    }

    @Test
    fun permissionIsForcedToAskWhateverTheModelReturned() {
        val auto = json.replace("\"timeoutMs\":15000", "\"timeoutMs\":15000,\"permission\":\"AUTO\"")
        assertEquals(ScriptPermission.ASK, ok(service().parse(auto, emptyList())).permission)
    }

    @Test
    fun targetHintOverridesTheModelsChoice() {
        assertEquals(ScriptTarget.HOST_SHELL, ok(service().parse(json, emptyList(), ScriptTarget.HOST_SHELL)).target)
    }

    @Test
    fun promptNamesEnvironmentVariablesExistingToolsAndTheHostShell() {
        val service = service()
        val prompt = service.promptFor("clear data", setOf("reset_app", "tap_home"), null, "Mac OS X")
        listOf("DEVICE", "PACKAGE", "RUN_DIR", "CASE_ID", "STEP_ID", "reset_app", "tap_home", "adb shell", "toybox", "zsh", "Request: clear data")
            .forEach { assertTrue(prompt.contains(it), "missing $it") }
        assertTrue(prompt.contains("ONLY"))
        assertTrue(prompt.contains("environment variables"))
        assertTrue(service.promptFor("x", emptySet(), ScriptTarget.ADB_SHELL, "Linux").contains("/bin/sh"))
        assertTrue(service.promptFor("x", emptySet(), ScriptTarget.HOST_SHELL, "Windows 11").contains("PowerShell"))
    }

    @Test
    fun generateSendsPromptAndReturnsDraft() = runBlocking {
        val prompts = mutableListOf<String>()
        val taken = listOf(TestScript(id = "a", toolName = "reset_app", commandTemplate = "true"))
        val result = service(prompts = prompts).generate("p1", "clear the app data", taken)
        assertEquals("clear_app_data", ok(result).toolName)
        assertTrue(prompts.single().contains("reset_app"))
    }

    @Test
    fun generateRejectsABlankRequestAndSurfacesProviderFailures() = runBlocking {
        assertTrue(invalid(service().generate("p1", "  ", emptyList())).contains("Describe"))
        val failing = TestScriptDraftService(generate = { _, _, _, _, _ -> error("provider down") })
        assertEquals("provider down", invalid(failing.generate("p1", "do it", emptyList())))
    }

    @Test
    fun regenerationKeepsIdAndOwnNameAndNeverGrantsAutoPermission() = runBlocking {
        val prompts = mutableListOf<String>()
        val current = TestScript(
            id = "script-1",
            toolName = "clear_app_data",
            commandTemplate = "pm clear x",
            workingDir = "/tmp/work",
            outputCapBytes = 1234,
            permission = ScriptPermission.AUTO,
        )
        val other = TestScript(id = "script-2", toolName = "other", commandTemplate = "true")
        val result = ok(service(prompts = prompts).generate("p1", "improve it", listOf(current, other), current = current))
        assertEquals("script-1", result.id)
        assertEquals("clear_app_data", result.toolName)
        assertEquals("/tmp/work", result.workingDir)
        assertEquals(1234, result.outputCapBytes)
        assertEquals(ScriptPermission.ASK, result.permission)
        assertTrue(prompts.single().contains("pm clear x"))
        assertTrue(prompts.single().contains("improved version"))
    }
}
