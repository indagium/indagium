package com.indagium.testing.authoring

import com.indagium.debug.toPlainMap
import com.indagium.testing.model.HookItem
import com.indagium.testing.model.MAX_SCRIPT_TOOL_NAME_CHARS
import com.indagium.testing.model.ScriptParamType
import com.indagium.testing.model.ScriptPermission
import com.indagium.testing.model.ScriptTarget
import com.indagium.testing.model.StepCheck
import com.indagium.testing.model.TestLibrary
import com.indagium.testing.model.TestScript
import com.indagium.testing.model.TestSuite
import com.indagium.testing.model.newScriptId
import com.indagium.testing.model.scriptToolNameError
import com.indagium.testing.run.scriptToolDescriptor
import com.indagium.testing.store.IdAllocator
import com.indagium.testing.store.MAX_SCRIPT_OUTPUT_CAP_BYTES
import com.indagium.testing.store.MAX_SCRIPT_TIMEOUT_MS
import com.indagium.testing.store.decodeScript
import com.indagium.testing.store.scriptToJson
import com.indagium.testing.store.validateScript
import io.modelcontextprotocol.kotlin.sdk.types.ToolSchema
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put
import java.io.File
import java.nio.charset.StandardCharsets

const val TEST_SCRIPT_FILE_FORMAT = "indagium-test-script"
const val TEST_SCRIPT_FILE_VERSION = 1
const val MAX_TEST_SCRIPT_FILE_BYTES = 2 * 1024 * 1024

fun readTestScriptEnvelope(file: File): String {
    require(file.isFile) { "${file.name} is not a file." }
    val bytes = file.inputStream().use { it.readNBytes(MAX_TEST_SCRIPT_FILE_BYTES + 1) }
    require(bytes.size <= MAX_TEST_SCRIPT_FILE_BYTES) { "Script JSON is limited to 2 MiB." }
    return bytes.toString(StandardCharsets.UTF_8)
}

fun writeTestScriptEnvelope(file: File, text: String, overwrite: Boolean): Long {
    val bytes = text.toByteArray(StandardCharsets.UTF_8)
    require(bytes.size <= MAX_TEST_SCRIPT_FILE_BYTES) { "Script JSON export is limited to 2 MiB." }
    require(!file.exists() || overwrite) { "${file.path} already exists; confirm overwrite before replacing it." }
    file.absoluteFile.parentFile?.let { require(it.isDirectory || it.mkdirs()) { "Could not create ${it.path}." } }
    file.outputStream().use { it.write(bytes) }
    return bytes.size.toLong()
}

data class ScriptUsageReference(
    val kind: String,
    val suiteId: String? = null,
    val suiteName: String? = null,
    val caseId: String? = null,
    val caseName: String? = null,
    val sharedStepId: String? = null,
    val sharedStepName: String? = null,
    val stepId: String? = null,
    val checkId: String? = null,
    val label: String,
)

/** Explicit references to [scriptId] in the current library, used by both the UI and MCP. */
fun testScriptUsageReferences(library: TestLibrary, scriptId: String): List<ScriptUsageReference> {
    val script = library.script(scriptId) ?: return emptyList()
    val uses = mutableListOf<ScriptUsageReference>()

    fun hooks(suite: TestSuite, list: List<HookItem>, caseId: String? = null, caseName: String? = null, section: String) {
        list.filterIsInstance<HookItem.Script>().filter { it.scriptId == scriptId }.forEach {
            uses += ScriptUsageReference(
                kind = "hook",
                suiteId = suite.id,
                suiteName = suite.name,
                caseId = caseId,
                caseName = caseName,
                label = "$section · ${script.toolName}",
            )
        }
    }
    library.suites.forEach { suite ->
        hooks(suite, suite.setup, section = "Suite setup")
        hooks(suite, suite.teardown, section = "Suite teardown")
        suite.cases.forEach { case ->
            hooks(suite, case.setup, case.id, case.name, "Case setup")
            hooks(suite, case.teardown, case.id, case.name, "Case teardown")
            if (case.allowedTools?.contains(script.toolName) == true) {
                uses += ScriptUsageReference("allowedTool", suite.id, suite.name, case.id, case.name, label = "Allowed lane tool · ${script.toolName}")
            }
            case.steps.forEach { step ->
                step.checks.filterIsInstance<StepCheck.ScriptResult>().filter { it.scriptId == scriptId }.forEach { check ->
                    uses += ScriptUsageReference(
                        "scriptResult",
                        suite.id,
                        suite.name,
                        case.id,
                        case.name,
                        stepId = step.id,
                        checkId = check.id,
                        label = "Script-result check · ${step.action}",
                    )
                }
            }
        }
    }
    library.sharedSteps.forEach { shared ->
        shared.steps.forEach { step ->
            step.checks.filterIsInstance<StepCheck.ScriptResult>().filter { it.scriptId == scriptId }.forEach { check ->
                uses += ScriptUsageReference(
                    "sharedScriptResult",
                    sharedStepId = shared.id,
                    sharedStepName = shared.name,
                    stepId = step.id,
                    checkId = check.id,
                    label = "Shared-step check · ${step.action}",
                )
            }
        }
    }
    return uses
}

/** Versioned, single-script JSON envelope used by the file picker and MCP. */
fun encodeTestScriptEnvelope(script: TestScript): String = Json { prettyPrint = true }.encodeToString(
    JsonObject.serializer(),
    buildJsonObject {
        put("format", TEST_SCRIPT_FILE_FORMAT)
        put("version", TEST_SCRIPT_FILE_VERSION)
        put("script", scriptToJson(script))
    },
)

/** Decode with a fresh entity id and an available unique tool name; callers still use store validation before publish. */
@Suppress("ThrowsCount") // Strict import reports the precise invalid envelope field without changing tolerant persisted-library decoding.
fun decodeTestScriptEnvelope(text: String, existing: List<TestScript>): TestScript {
    require(text.toByteArray(Charsets.UTF_8).size <= MAX_TEST_SCRIPT_FILE_BYTES) { "Script JSON is limited to 2 MiB." }
    val root = runCatching { Json.parseToJsonElement(text).jsonObject }
        .getOrElse { throw IllegalArgumentException("This is not a valid script JSON file.") }
    val formatPrimitive = root["format"] as? JsonPrimitive
    val format = formatPrimitive?.takeIf { it.isString }?.content
    require(format == TEST_SCRIPT_FILE_FORMAT) { "Expected a $TEST_SCRIPT_FILE_FORMAT file." }
    val versionPrimitive = root["version"] as? JsonPrimitive
    val version = versionPrimitive?.takeUnless { it.isString }?.intOrNull
    require(version == TEST_SCRIPT_FILE_VERSION) { "Unsupported script JSON version '${version ?: "unknown"}'." }
    val raw = root["script"] as? JsonObject ?: throw IllegalArgumentException("The script JSON file has no script object.")
    validateScriptEnvelopePayload(raw)
    val decoded = decodeScript(raw, IdAllocator()) ?: throw IllegalArgumentException("The script JSON file has no valid toolName.")
    val imported = decoded.copy(id = newScriptId(), toolName = uniqueScriptToolName(decoded.toolName, existing.map { it.toolName }))
    validateScript(imported, existing)?.let { throw IllegalArgumentException(it) }
    return imported
}

/** Versioned imports are strict: persisted-library recovery may be tolerant, but user-selected files may not be repaired silently. */
private fun validateScriptEnvelopePayload(raw: JsonObject) {
    require(envelopeString(raw, "toolName").isNotBlank()) { "The script JSON file needs a toolName." }
    envelopeString(raw, "description", required = false)
    require(envelopeString(raw, "commandTemplate").isNotBlank()) { "The script JSON file needs a commandTemplate." }
    require(envelopeString(raw, "target") in ScriptTarget.entries.map { it.name }) {
        "Unknown script target '${envelopeString(raw, "target")}'."
    }
    require(envelopeString(raw, "permission") in ScriptPermission.entries.map { it.name }) {
        "Unknown script permission '${envelopeString(raw, "permission")}'."
    }
    val timeout = envelopeLong(raw, "timeoutMs")
    val outputCap = envelopeInt(raw, "outputCapBytes")
    require(timeout in 1..MAX_SCRIPT_TIMEOUT_MS) {
        "Script timeout must be between 1 and $MAX_SCRIPT_TIMEOUT_MS milliseconds."
    }
    require(outputCap in 1..MAX_SCRIPT_OUTPUT_CAP_BYTES) { "Script output cap is out of range." }
    validateWorkingDirectory(raw)
    val params = raw["params"] as? JsonArray
    require(params != null) { "Script field 'params' must be an array." }
    params.forEachIndexed { index, item -> validateScriptParam(item, index) }
}

private fun envelopeString(raw: JsonObject, key: String, required: Boolean = true): String {
    val value = raw[key]
    if (value == null || value is JsonNull) {
        require(!required) { "The script JSON file is missing '$key'." }
        return ""
    }
    val primitive = value as? JsonPrimitive
    require(primitive != null && primitive.isString) { "Script field '$key' must be a string." }
    return primitive.content
}

private fun envelopeLong(raw: JsonObject, key: String): Long {
    val primitive = raw[key] as? JsonPrimitive
    return primitive?.takeUnless { it.isString }?.longOrNull
        ?: throw IllegalArgumentException("Script field '$key' must be an integer.")
}

private fun envelopeInt(raw: JsonObject, key: String): Int {
    val primitive = raw[key] as? JsonPrimitive
    return primitive?.takeUnless { it.isString }?.intOrNull
        ?: throw IllegalArgumentException("Script field '$key' must be an integer.")
}

private fun validateWorkingDirectory(raw: JsonObject) {
    val value = raw["workingDir"]
    require(value == null || value is JsonNull || value is JsonPrimitive && value.isString) {
        "Script field 'workingDir' must be a string or null."
    }
}

private fun validateScriptParam(item: kotlinx.serialization.json.JsonElement, index: Int) {
    val param = item as? JsonObject
    require(param != null) { "params[$index] must be an object." }
    require(paramString(param, "name", index).isNotBlank()) { "params[$index].name must not be blank." }
    val type = paramString(param, "type", index)
    require(type in ScriptParamType.entries.map { it.name }) { "params[$index] has unknown type '$type'." }
    paramString(param, "description", index, required = false)
    val required = param["required"] as? JsonPrimitive
    require(required?.isString == false && required.booleanOrNull != null) {
        "params[$index].required must be a boolean."
    }
    paramString(param, "defaultValue", index, required = false)
}

private fun paramString(param: JsonObject, key: String, index: Int, required: Boolean = true): String {
    val value = param[key]
    if (value == null || value is JsonNull) {
        require(!required) { "params[$index] is missing '$key'." }
        return ""
    }
    val primitive = value as? JsonPrimitive
    require(primitive != null && primitive.isString) { "params[$index].$key must be a string." }
    return primitive.content
}

/** Duplicates a script with a fresh id and a valid unique tool name. */
fun duplicateTestScript(source: TestScript, existing: List<TestScript>): TestScript {
    val duplicate = source.copy(id = newScriptId(), toolName = uniqueScriptToolName(source.toolName, existing.map { it.toolName }))
    scriptToolNameError(duplicate.toolName)?.let { throw IllegalArgumentException(it) }
    return duplicate
}

/** The actual runtime descriptor serialized for a schema preview; it is built by the same function as lane dispatch. */
fun testScriptSchemaPreview(script: TestScript): Map<String, Any?> {
    val descriptor = scriptToolDescriptor(script)
    val schema = Json.encodeToJsonElement(ToolSchema.serializer(), descriptor.schema)
    return mapOf("name" to descriptor.name, "description" to descriptor.description, "inputSchema" to schema.jsonObject.toPlainMap())
}

private fun uniqueScriptToolName(base: String, existing: List<String>): String {
    scriptToolNameError(base)?.let { throw IllegalArgumentException(it) }
    val taken = existing.toSet()
    if (base !in taken) return base
    var suffix = 2
    while (true) {
        val tail = "_$suffix"
        val prefix = base.take((MAX_SCRIPT_TOOL_NAME_CHARS - tail.length).coerceAtLeast(1))
        val candidate = prefix + tail
        if (candidate !in taken && scriptToolNameError(candidate) == null) return candidate
        suffix++
    }
}
