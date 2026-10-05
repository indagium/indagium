package com.indagium.testing.store

import com.indagium.testing.model.CASE_ID_PREFIX
import com.indagium.testing.model.CHECK_ID_PREFIX
import com.indagium.testing.model.DEFAULT_LOG_ABSENT_FOR_MS
import com.indagium.testing.model.DEFAULT_LOG_WITHIN_MS
import com.indagium.testing.model.DEFAULT_SCRIPT_EXIT_CODE
import com.indagium.testing.model.DEFAULT_SCRIPT_OUTPUT_CAP_BYTES
import com.indagium.testing.model.DEFAULT_SCRIPT_TIMEOUT_MS
import com.indagium.testing.model.DEFAULT_STEP_RETRIES
import com.indagium.testing.model.DEFAULT_STEP_TIMEOUT_MS
import com.indagium.testing.model.EXAMPLE_ID_PREFIX
import com.indagium.testing.model.HOOK_ID_PREFIX
import com.indagium.testing.model.HookItem
import com.indagium.testing.model.OnFailure
import com.indagium.testing.model.SCRIPT_ID_PREFIX
import com.indagium.testing.model.SHARED_STEP_ID_PREFIX
import com.indagium.testing.model.STEP_ID_PREFIX
import com.indagium.testing.model.SUITE_ID_PREFIX
import com.indagium.testing.model.ScriptParam
import com.indagium.testing.model.ScriptParamType
import com.indagium.testing.model.ScriptPermission
import com.indagium.testing.model.ScriptTarget
import com.indagium.testing.model.SharedStep
import com.indagium.testing.model.StepCheck
import com.indagium.testing.model.StepExample
import com.indagium.testing.model.TestCase
import com.indagium.testing.model.TestScript
import com.indagium.testing.model.TestStep
import com.indagium.testing.model.TestSuite
import com.indagium.testing.model.TestVariable
import com.indagium.testing.model.VARIABLE_ID_PREFIX
import com.indagium.testing.model.isSafeId
import com.indagium.testing.model.newPrefixedId
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonObjectBuilder
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put

// JSON codec for the test library files, on the kotlinx.serialization RUNTIME API only (buildJsonObject /
// parseToJsonElement) — the project does not apply the serialization compiler plugin.
//
//   suites/<id>.json  {"format":"indagium-test-suite","version":1,"suite":{...}}
//   library.json      {"format":"indagium-test-library","version":1,"suiteOrder":[...],"scripts":[...],"sharedSteps":[...]}
//
// Reads are tolerant: unknown keys are ignored, a missing or mistyped field takes its default, an
// entry of an unknown kind is skipped, and a missing/duplicate/unsafe id is replaced by a fresh one.
// Only the envelope is strict. A file with a NEWER version still decodes but is flagged readOnly so
// the store never rewrites it (which would drop fields this build does not know).

const val TEST_SUITE_FILE_FORMAT = "indagium-test-suite"
const val TEST_LIBRARY_FILE_FORMAT = "indagium-test-library"
const val TEST_SUITE_FILE_VERSION = 1
const val TEST_LIBRARY_FILE_VERSION = 1

private val prettyJson = Json { prettyPrint = true }

data class DecodedSuiteFile(val suite: TestSuite, val readOnly: Boolean)

data class DecodedLibraryFile(
    val suiteOrder: List<String>,
    val scripts: List<TestScript>,
    val sharedSteps: List<SharedStep>,
    val readOnly: Boolean,
)

// ── Tolerant readers ─────────────────────────────────────────────────

private fun JsonObject.str(key: String, default: String = ""): String = (this[key] as? JsonPrimitive)?.contentOrNull ?: default

private fun JsonObject.optStr(key: String): String? = (this[key] as? JsonPrimitive)?.contentOrNull

private fun JsonObject.long(key: String, default: Long): Long = (this[key] as? JsonPrimitive)?.longOrNull ?: default

private fun JsonObject.int(key: String, default: Int): Int = (this[key] as? JsonPrimitive)?.intOrNull ?: default

private fun JsonObject.bool(key: String, default: Boolean): Boolean = (this[key] as? JsonPrimitive)?.booleanOrNull ?: default

private fun JsonObject.objects(key: String): List<JsonObject> = (this[key] as? JsonArray)?.mapNotNull { it as? JsonObject }.orEmpty()

private fun JsonObject.strings(key: String): List<String> =
    (this[key] as? JsonArray)?.mapNotNull { (it as? JsonPrimitive)?.takeIf { p -> p.isString }?.content }.orEmpty()

private fun JsonObject.stringMap(key: String): Map<String, String> {
    val obj = this[key] as? JsonObject ?: return emptyMap()
    val out = LinkedHashMap<String, String>()
    obj.forEach { (k, v) -> (v as? JsonPrimitive)?.contentOrNull?.let { out[k] = it } }
    return out
}

/** Hands out ids for one file: a stored id is kept when it is safe and not yet used, otherwise a fresh one is made. */
private class IdAllocator {
    private val seen = HashSet<String>()

    fun next(raw: JsonElement?, prefix: String): String {
        val stored = (raw as? JsonPrimitive)?.contentOrNull
        if (stored != null && isSafeId(stored) && seen.add(stored)) return stored
        while (true) {
            val fresh = newPrefixedId(prefix)
            if (seen.add(fresh)) return fresh
        }
    }
}

private inline fun <reified E : Enum<E>> JsonObject.enumOr(key: String, default: E): E =
    optStr(key)?.let { name -> enumValues<E>().firstOrNull { it.name == name } } ?: default

// ── Writers ──────────────────────────────────────────────────────────

private fun JsonObjectBuilder.putStringMap(key: String, map: Map<String, String>) {
    put(key, buildJsonObject { map.forEach { (k, v) -> put(k, v) } })
}

private fun checkToJson(check: StepCheck): JsonObject = buildJsonObject {
    put("id", check.id)
    when (check) {
        is StepCheck.LogAppears -> {
            put("type", "logAppears")
            check.tag?.let { put("tag", it) }
            put("regex", check.regex)
            put("withinMs", check.withinMs)
        }
        is StepCheck.LogAbsent -> {
            put("type", "logAbsent")
            check.tag?.let { put("tag", it) }
            put("regex", check.regex)
            put("forMs", check.forMs)
        }
        is StepCheck.ScreenJudge -> {
            put("type", "screenJudge")
            put("text", check.text)
            check.exampleRef?.let { put("exampleRef", it) }
        }
        is StepCheck.ScriptResult -> {
            put("type", "scriptResult")
            put("scriptId", check.scriptId)
            putStringMap("args", check.args)
            put("exitCode", check.exitCode)
            check.stdoutContains?.let { put("stdoutContains", it) }
        }
        is StepCheck.AskJudge -> {
            put("type", "askJudge")
            put("text", check.text)
        }
    }
}

private fun exampleToJson(example: StepExample): JsonObject = buildJsonObject {
    put("id", example.id)
    put("caption", example.caption)
    when (example) {
        is StepExample.GoldenScreenshot -> {
            put("type", "goldenScreenshot")
            put("assetPath", example.assetPath)
        }
        is StepExample.ReferenceLog -> {
            put("type", "referenceLog")
            put("text", example.text)
        }
    }
}

private fun stepToJson(step: TestStep): JsonObject = buildJsonObject {
    put("id", step.id)
    put("action", step.action)
    put("expected", step.expected)
    put("timeoutMs", step.timeoutMs)
    put("retries", step.retries)
    put("onFailure", step.onFailure.name)
    put("checks", buildJsonArray { step.checks.forEach { add(checkToJson(it)) } })
    put("examples", buildJsonArray { step.examples.forEach { add(exampleToJson(it)) } })
}

private fun caseToJson(case: TestCase): JsonObject = buildJsonObject {
    put("id", case.id)
    put("name", case.name)
    put("description", case.description)
    put("instructions", case.instructions)
    case.allowedTools?.let { tools -> put("allowedTools", buildJsonArray { tools.forEach { add(JsonPrimitive(it)) } }) }
    put("steps", buildJsonArray { case.steps.forEach { add(stepToJson(it)) } })
}

private fun hookToJson(hook: HookItem): JsonObject = buildJsonObject {
    put("id", hook.id)
    when (hook) {
        is HookItem.Script -> {
            put("type", "script")
            put("scriptId", hook.scriptId)
            putStringMap("args", hook.args)
        }
        is HookItem.Shared -> {
            put("type", "shared")
            put("sharedStepId", hook.sharedStepId)
        }
    }
}

private fun variableToJson(variable: TestVariable): JsonObject = buildJsonObject {
    put("id", variable.id)
    put("name", variable.name)
    put("value", variable.value)
    put("description", variable.description)
}

private fun suiteToJson(suite: TestSuite): JsonObject = buildJsonObject {
    put("id", suite.id)
    put("name", suite.name)
    put("description", suite.description)
    put("instructions", suite.instructions)
    put("createdAt", suite.createdAt)
    put("updatedAt", suite.updatedAt)
    put("setup", buildJsonArray { suite.setup.forEach { add(hookToJson(it)) } })
    put("teardown", buildJsonArray { suite.teardown.forEach { add(hookToJson(it)) } })
    put("variables", buildJsonArray { suite.variables.forEach { add(variableToJson(it)) } })
    put("cases", buildJsonArray { suite.cases.forEach { add(caseToJson(it)) } })
}

private fun paramToJson(param: ScriptParam): JsonObject = buildJsonObject {
    put("name", param.name)
    put("type", param.type.name)
    put("description", param.description)
    put("required", param.required)
    param.defaultValue?.let { put("defaultValue", it) }
}

private fun scriptToJson(script: TestScript): JsonObject = buildJsonObject {
    put("id", script.id)
    put("toolName", script.toolName)
    put("description", script.description)
    put("commandTemplate", script.commandTemplate)
    put("target", script.target.name)
    put("timeoutMs", script.timeoutMs)
    put("outputCapBytes", script.outputCapBytes)
    script.workingDir?.let { put("workingDir", it) }
    put("permission", script.permission.name)
    put("params", buildJsonArray { script.params.forEach { add(paramToJson(it)) } })
}

private fun sharedStepToJson(shared: SharedStep): JsonObject = buildJsonObject {
    put("id", shared.id)
    put("name", shared.name)
    put("description", shared.description)
    put("steps", buildJsonArray { shared.steps.forEach { add(stepToJson(it)) } })
}

fun encodeSuiteFile(suite: TestSuite): String = prettyJson.encodeToString(
    JsonObject.serializer(),
    buildJsonObject {
        put("format", TEST_SUITE_FILE_FORMAT)
        put("version", TEST_SUITE_FILE_VERSION)
        put("suite", suiteToJson(suite))
    },
)

fun encodeLibraryFile(suiteOrder: List<String>, scripts: List<TestScript>, sharedSteps: List<SharedStep>): String =
    prettyJson.encodeToString(
        JsonObject.serializer(),
        buildJsonObject {
            put("format", TEST_LIBRARY_FILE_FORMAT)
            put("version", TEST_LIBRARY_FILE_VERSION)
            put("suiteOrder", buildJsonArray { suiteOrder.forEach { add(JsonPrimitive(it)) } })
            put("scripts", buildJsonArray { scripts.forEach { add(scriptToJson(it)) } })
            put("sharedSteps", buildJsonArray { sharedSteps.forEach { add(sharedStepToJson(it)) } })
        },
    )

// ── Readers ──────────────────────────────────────────────────────────

private fun decodeCheck(o: JsonObject, ids: IdAllocator): StepCheck? {
    val id by lazy { ids.next(o["id"], CHECK_ID_PREFIX) }
    return when (o.str("type")) {
        "logAppears" -> StepCheck.LogAppears(id, o.optStr("tag"), o.str("regex"), o.long("withinMs", DEFAULT_LOG_WITHIN_MS))
        "logAbsent" -> StepCheck.LogAbsent(id, o.optStr("tag"), o.str("regex"), o.long("forMs", DEFAULT_LOG_ABSENT_FOR_MS))
        "screenJudge" -> StepCheck.ScreenJudge(id, o.str("text"), o.optStr("exampleRef"))
        "scriptResult" -> StepCheck.ScriptResult(
            id = id,
            scriptId = o.str("scriptId"),
            args = o.stringMap("args"),
            // An absent key means the default; an explicit null means "any exit code".
            exitCode = if (o.containsKey("exitCode")) (o["exitCode"] as? JsonPrimitive)?.intOrNull else DEFAULT_SCRIPT_EXIT_CODE,
            stdoutContains = o.optStr("stdoutContains"),
        )
        "askJudge" -> StepCheck.AskJudge(id, o.str("text"))
        else -> null
    }
}

private fun decodeExample(o: JsonObject, ids: IdAllocator): StepExample? {
    val id by lazy { ids.next(o["id"], EXAMPLE_ID_PREFIX) }
    return when (o.str("type")) {
        "goldenScreenshot" -> StepExample.GoldenScreenshot(id, o.str("assetPath"), o.str("caption"))
        "referenceLog" -> StepExample.ReferenceLog(id, o.str("text"), o.str("caption"))
        else -> null
    }
}

private fun decodeStep(o: JsonObject, ids: IdAllocator): TestStep = TestStep(
    id = ids.next(o["id"], STEP_ID_PREFIX),
    action = o.str("action"),
    expected = o.str("expected"),
    checks = o.objects("checks").mapNotNull { decodeCheck(it, ids) },
    examples = o.objects("examples").mapNotNull { decodeExample(it, ids) },
    timeoutMs = o.long("timeoutMs", DEFAULT_STEP_TIMEOUT_MS),
    retries = o.int("retries", DEFAULT_STEP_RETRIES),
    onFailure = o.enumOr("onFailure", OnFailure.STOP_CASE),
)

private fun decodeCase(o: JsonObject, ids: IdAllocator): TestCase = TestCase(
    id = ids.next(o["id"], CASE_ID_PREFIX),
    name = o.str("name"),
    description = o.str("description"),
    instructions = o.str("instructions"),
    steps = o.objects("steps").map { decodeStep(it, ids) },
    allowedTools = (o["allowedTools"] as? JsonArray)?.let { o.strings("allowedTools").toCollection(LinkedHashSet()) },
)

private fun decodeHook(o: JsonObject, ids: IdAllocator): HookItem? {
    val id by lazy { ids.next(o["id"], HOOK_ID_PREFIX) }
    return when (o.str("type")) {
        "script" -> HookItem.Script(id, o.str("scriptId"), o.stringMap("args"))
        "shared" -> HookItem.Shared(id, o.str("sharedStepId"))
        else -> null
    }
}

private fun decodeVariable(o: JsonObject, ids: IdAllocator): TestVariable =
    TestVariable(ids.next(o["id"], VARIABLE_ID_PREFIX), o.str("name"), o.str("value"), o.str("description"))

private fun decodeSuite(o: JsonObject, readOnly: Boolean): TestSuite? {
    val ids = IdAllocator()
    // The suite id names its file, so unlike nested ids a bad one is not repaired: the file is not usable.
    val suiteId = (o["id"] as? JsonPrimitive)?.contentOrNull?.takeIf { isSafeId(it) } ?: return null
    ids.next(JsonPrimitive(suiteId), SUITE_ID_PREFIX)
    return TestSuite(
        id = suiteId,
        name = o.str("name"),
        description = o.str("description"),
        instructions = o.str("instructions"),
        setup = o.objects("setup").mapNotNull { decodeHook(it, ids) },
        teardown = o.objects("teardown").mapNotNull { decodeHook(it, ids) },
        variables = o.objects("variables").map { decodeVariable(it, ids) },
        cases = o.objects("cases").map { decodeCase(it, ids) },
        createdAt = o.long("createdAt", 0L),
        updatedAt = o.long("updatedAt", 0L),
        readOnly = readOnly,
    )
}

private fun decodeParam(o: JsonObject): ScriptParam? {
    val name = o.str("name").takeIf { it.isNotBlank() } ?: return null
    return ScriptParam(
        name = name,
        type = o.enumOr("type", ScriptParamType.STRING),
        description = o.str("description"),
        required = o.bool("required", true),
        defaultValue = o.optStr("defaultValue"),
    )
}

private fun decodeScript(o: JsonObject, ids: IdAllocator): TestScript? {
    val toolName = o.str("toolName").takeIf { it.isNotBlank() } ?: return null
    return TestScript(
        id = ids.next(o["id"], SCRIPT_ID_PREFIX),
        toolName = toolName,
        description = o.str("description"),
        params = o.objects("params").mapNotNull { decodeParam(it) },
        commandTemplate = o.str("commandTemplate"),
        target = o.enumOr("target", ScriptTarget.HOST_SHELL),
        timeoutMs = o.long("timeoutMs", DEFAULT_SCRIPT_TIMEOUT_MS),
        outputCapBytes = o.int("outputCapBytes", DEFAULT_SCRIPT_OUTPUT_CAP_BYTES),
        workingDir = o.optStr("workingDir")?.takeIf { it.isNotBlank() },
        permission = o.enumOr("permission", ScriptPermission.ASK),
    )
}

private fun decodeSharedStep(o: JsonObject, ids: IdAllocator): SharedStep = SharedStep(
    id = ids.next(o["id"], SHARED_STEP_ID_PREFIX),
    name = o.str("name"),
    description = o.str("description"),
    steps = o.objects("steps").map { decodeStep(it, ids) },
)

/** Validates the envelope shared by both file kinds; the failure message is safe to show as-is. */
private fun readEnvelope(text: String, expectedFormat: String, what: String): Pair<JsonObject, Boolean> {
    val root = runCatching { Json.parseToJsonElement(text).jsonObject }
        .getOrElse { error("This file is not a $what (it is not valid JSON).") }
    if (root.str("format") != expectedFormat) error("This file is not an Indagium $what.")
    val version = (root["version"] as? JsonPrimitive)?.intOrNull ?: 1
    val supported = if (expectedFormat == TEST_SUITE_FILE_FORMAT) TEST_SUITE_FILE_VERSION else TEST_LIBRARY_FILE_VERSION
    return root to (version > supported)
}

/** Never throws; failure carries a message that is safe to show as-is. */
fun decodeSuiteFile(text: String): Result<DecodedSuiteFile> = runCatching {
    val (root, newer) = readEnvelope(text, TEST_SUITE_FILE_FORMAT, "test suite")
    val suiteObject = root["suite"] as? JsonObject ?: error("This test suite file has no suite in it.")
    DecodedSuiteFile(decodeSuite(suiteObject, readOnly = newer) ?: error("This test suite file has no usable suite id."), newer)
}

/** Never throws; failure carries a message that is safe to show as-is. */
fun decodeLibraryFile(text: String): Result<DecodedLibraryFile> = runCatching {
    val (root, newer) = readEnvelope(text, TEST_LIBRARY_FILE_FORMAT, "test library")
    val ids = IdAllocator()
    DecodedLibraryFile(
        suiteOrder = root.strings("suiteOrder").distinct(),
        scripts = root.objects("scripts").mapNotNull { decodeScript(it, ids) },
        sharedSteps = root.objects("sharedSteps").map { decodeSharedStep(it, ids) },
        readOnly = newer,
    )
}
