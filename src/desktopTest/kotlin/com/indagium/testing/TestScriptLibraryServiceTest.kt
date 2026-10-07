package com.indagium.testing

import com.indagium.testing.authoring.decodeTestScriptEnvelope
import com.indagium.testing.authoring.duplicateTestScript
import com.indagium.testing.authoring.encodeTestScriptEnvelope
import com.indagium.testing.authoring.testScriptSchemaPreview
import com.indagium.testing.authoring.testScriptUsageReferences
import com.indagium.testing.model.HookItem
import com.indagium.testing.model.ScriptParam
import com.indagium.testing.model.ScriptParamType
import com.indagium.testing.model.SharedStep
import com.indagium.testing.model.StepCheck
import com.indagium.testing.model.TestCase
import com.indagium.testing.model.TestLibrary
import com.indagium.testing.model.TestScript
import com.indagium.testing.model.TestStep
import com.indagium.testing.model.TestSuite
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue
import com.indagium.debug.Json as ToolJson
import kotlinx.serialization.json.Json as SerializationJson

class TestScriptLibraryServiceTest {
    private fun script(name: String = "restart_app") = TestScript(
        id = "script-fixture", toolName = name, description = "Restart the app",
        params = listOf(ScriptParam("package_name", ScriptParamType.STRING, required = true)),
        commandTemplate = "adb shell am force-stop \"\$package_name\"",
    )

    @Test
    fun envelopeImportGetsFreshIdAndUniqueNameAndSchemaMatchesRuntimeTypes() {
        val original = script()
        val encoded = encodeTestScriptEnvelope(original)
        val imported = decodeTestScriptEnvelope(encoded, listOf(original))
        assertNotEquals(original.id, imported.id)
        assertEquals("restart_app_2", imported.toolName)

        val schema = testScriptSchemaPreview(original)["inputSchema"] as Map<*, *>
        assertEquals("object", schema["type"])
        val properties = schema["properties"] as Map<*, *>
        assertEquals("string", (properties["package_name"] as Map<*, *>)["type"])
        assertEquals(listOf("package_name"), schema["required"])
        val serialized = ToolJson.encode(mapOf("inputSchema" to schema))
        assertTrue("\"type\":\"object\"" in serialized)
        assertTrue("\"required\":[\"package_name\"]" in serialized)
    }

    @Test
    fun strictEnvelopeRejectsUnknownTargetAndMalformedParameterRows() {
        val envelope = SerializationJson.parseToJsonElement(encodeTestScriptEnvelope(script())).jsonObject
        val original = envelope["script"]!!.jsonObject
        val badTarget = SerializationJson.encodeToString(envelope.toMutableMap().apply {
            this["script"] = kotlinx.serialization.json.buildJsonObject {
                original.forEach { (key, value) -> if (key == "target") put(key, kotlinx.serialization.json.JsonPrimitive("ADB_SHEL")) else put(key, value) }
            }
        })
        assertTrue(
            assertFailsWith<IllegalArgumentException> { decodeTestScriptEnvelope(badTarget, emptyList()) }
                .message.orEmpty().contains("Unknown script target"),
        )
        val malformedParams = SerializationJson.encodeToString(envelope.toMutableMap().apply {
            this["script"] = kotlinx.serialization.json.buildJsonObject {
                original.forEach { (key, value) ->
                    if (key == "params") put(key, kotlinx.serialization.json.JsonPrimitive("not-an-array")) else put(key, value)
                }
            }
        })
        assertTrue(assertFailsWith<IllegalArgumentException> { decodeTestScriptEnvelope(malformedParams, emptyList()) }.message.orEmpty().contains("params"))

        val stringRequired = SerializationJson.encodeToString(envelope.toMutableMap().apply {
            this["script"] = kotlinx.serialization.json.buildJsonObject {
                original.forEach { (key, value) ->
                    if (key == "params") put(key, kotlinx.serialization.json.buildJsonArray {
                        add(kotlinx.serialization.json.buildJsonObject {
                            original["params"]!!.jsonArray.single().jsonObject.forEach { (paramKey, paramValue) ->
                                if (paramKey == "required") put(paramKey, kotlinx.serialization.json.JsonPrimitive("true")) else put(paramKey, paramValue)
                            }
                        })
                    }) else put(key, value)
                }
            }
        })
        assertTrue(
            assertFailsWith<IllegalArgumentException> { decodeTestScriptEnvelope(stringRequired, emptyList()) }
                .message.orEmpty().contains("must be a boolean"),
        )

        val stringVersion = SerializationJson.encodeToString(envelope.toMutableMap().apply { this["version"] = kotlinx.serialization.json.JsonPrimitive("1") })
        assertTrue(assertFailsWith<IllegalArgumentException> { decodeTestScriptEnvelope(stringVersion, emptyList()) }.message.orEmpty().contains("version"))
    }

    @Test
    fun invalidNamesAndReservedBuiltinsFailPromptlyInsteadOfSearchingForever() {
        listOf("Restart_App", "tracker_send_issue", "bad-name", "get_step_example").forEach { name ->
            assertFailsWith<IllegalArgumentException> { duplicateTestScript(script(name), emptyList()) }
            val envelope = encodeTestScriptEnvelope(script(name))
            assertFailsWith<IllegalArgumentException> { decodeTestScriptEnvelope(envelope, emptyList()) }
        }
    }

    @Test
    fun inlineEnvelopeHasTheSameTwoMiBLimitAsFileImport() {
        val oversized = " ".repeat(2 * 1024 * 1024 + 1)
        val failure = assertFailsWith<IllegalArgumentException> { decodeTestScriptEnvelope(oversized, emptyList()) }
        assertTrue(failure.message.orEmpty().contains("2 MiB"))
    }

    @Test
    fun usageCountsEveryExplicitScriptCheckAndEachAllowedToolAndHook() {
        val target = script()
        val checks = listOf(
            StepCheck.ScriptResult("check-1", target.id),
            StepCheck.ScriptResult("check-2", target.id),
        )
        val step = TestStep("step-1", "Run check", checks = checks)
        val suite = TestSuite(
            id = "suite-1", name = "Suite", setup = listOf(HookItem.Script("hook-1", target.id)),
            cases = listOf(TestCase("case-1", "Case", allowedTools = setOf(target.toolName), steps = listOf(step))),
        )
        val shared = SharedStep("shared-1", "Shared", steps = listOf(TestStep("step-2", "Shared command", checks = checks)))
        val references = testScriptUsageReferences(TestLibrary(suites = listOf(suite), scripts = listOf(target), sharedSteps = listOf(shared)), target.id)
        assertEquals(6, references.size)
        assertEquals(2, references.count { it.kind == "scriptResult" })
        assertEquals(2, references.count { it.kind == "sharedScriptResult" })
        assertEquals(1, references.count { it.kind == "hook" })
        assertEquals(1, references.count { it.kind == "allowedTool" })
        assertEquals(setOf("check-1", "check-2"), references.mapNotNull { it.checkId }.toSet())
    }
}
