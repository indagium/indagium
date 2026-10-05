package com.indagium.debug

import com.indagium.edition.Edition
import com.indagium.testing.limits.FREE_EDITION_LIMIT_HINT
import com.indagium.testing.limits.activeCaseIds
import com.indagium.testing.limits.activeSuiteIds
import com.indagium.testing.limits.isCaseLocked
import com.indagium.testing.model.HookItem
import com.indagium.testing.model.SharedStep
import com.indagium.testing.model.StepCheck
import com.indagium.testing.model.TestCase
import com.indagium.testing.model.TestLibrary
import com.indagium.testing.model.TestScript
import com.indagium.testing.model.TestStep
import com.indagium.testing.model.TestSuite
import com.indagium.testing.script.ScriptRunOutcome
import com.indagium.testing.script.scriptArgsFromToolValues
import com.indagium.testing.script.toToolResult
import com.indagium.testing.store.StoreResult
import com.indagium.testing.store.TEST_SUITE_FILE_FORMAT
import com.indagium.testing.store.caseToJson
import com.indagium.testing.store.scriptToJson
import com.indagium.testing.store.sharedStepToJson
import com.indagium.testing.store.stepToJson
import com.indagium.testing.store.suiteToJson
import com.indagium.ui.AppState
import com.indagium.ui.tryTestScript
import kotlinx.coroutines.CancellationException
import java.io.File

// Handlers of the AI test-suite authoring tools (catalogue: TestSuiteToolCatalog.kt), merged into
// IndagiumToolOperations.operationHandlers. Every handler returns a plain Map, and every expected
// failure is DATA: { "error": message } for a missing id, an invalid value or a bad argument, and
// { "error", "limit": { kind, max, edition, hint } } when the edition limits refuse the call. The
// store's own StoreResult is mapped in one place ([toResult]).
//
// Entities are written with the same codec the suite files use (testing/store/TestLibraryCodec.kt), so
// the JSON an MCP client reads is the file JSON, plus `locked` flags that exist only here.

internal class TestSuiteToolOperations(private val appState: AppState) {
    /** One handler per tool in [TEST_SUITE_MCP_TOOLS]; the gateway's parity check fails on any drift. */
    val handlers: Map<String, (Map<String, Any?>) -> Any?> =
        suiteHandlers() + caseHandlers() + stepHandlers() + scriptHandlers() + sharedStepHandlers() + editionHandlers()

    /**
     * Handlers that wait (a script runs for up to its timeout). They are registered as suspending handlers so the
     * transport never blocks a request thread on them; today that is `try_test_script` only.
     */
    val suspendHandlers: Map<String, suspend (Map<String, Any?>) -> Any?> = mapOf(
        "try_test_script" to suspendTool { a -> tryScript(a) },
    )

    private val library: TestLibrary get() = appState.testLibrary

    private fun tool(body: (ToolArgs) -> Any?): (Map<String, Any?>) -> Any? = { raw ->
        try {
            body(ToolArgs(raw))
        } catch (e: ToolArgException) {
            errorMap(e.message ?: "Invalid arguments.")
        }
    }

    private fun suspendTool(body: suspend (ToolArgs) -> Any?): suspend (Map<String, Any?>) -> Any? = { raw ->
        try {
            body(ToolArgs(raw))
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (e: ToolArgException) {
            errorMap(e.message ?: "Invalid arguments.")
        }
    }

    // ── Suites ───────────────────────────────────────────────────────

    private fun suiteHandlers(): Map<String, (Map<String, Any?>) -> Any?> = mapOf(
        "list_test_suites" to tool { listSuites() },
        "get_test_suite" to tool { a -> getSuite(a.requiredString("suiteId")) },
        "create_test_suite" to tool { a -> createSuite(a) },
        "update_test_suite" to tool { a -> updateSuite(a) },
        "delete_test_suite" to tool { a ->
            val id = a.requiredString("suiteId")
            appState.deleteTestSuite(id).toResult { mapOf("deleted" to true, "suiteId" to id) }
        },
        "duplicate_test_suite" to tool { a -> appState.duplicateTestSuite(a.requiredString("suiteId")).toResult { suiteResult(it) } },
        "move_test_suite" to tool { a ->
            val id = a.requiredString("suiteId")
            appState.moveTestSuite(id, a.requiredInt("toIndex")).toResult {
                mapOf("suiteId" to id, "index" to library.suites.indexOfFirst { it.id == id }, "suiteIds" to library.suites.map { it.id })
            }
        },
        "import_test_suite" to tool { a -> importSuite(a) },
        "export_test_suite" to tool { a -> exportSuite(a) },
    )

    private fun listSuites(): Map<String, Any?> {
        val lib = library
        val limits = appState.editionService.limits()
        val activeSuites = activeSuiteIds(lib, limits)
        val activeCases = activeCaseIds(lib, limits)
        val summaries = lib.suites.map { suite ->
            mapOf(
                "id" to suite.id,
                "name" to suite.name,
                "description" to suite.description,
                "targetPackage" to suite.targetPackage,
                "tags" to suite.tags,
                "caseCount" to suite.cases.size,
                "stepCount" to suite.cases.sumOf { it.steps.size },
                "locked" to (suite.id !in activeSuites),
                "readOnly" to suite.readOnly,
                "createdAt" to suite.createdAt,
                "updatedAt" to suite.updatedAt,
                "cases" to suite.cases.map { case ->
                    mapOf("id" to case.id, "name" to case.name, "stepCount" to case.steps.size, "locked" to (case.id !in activeCases))
                },
            )
        }
        return buildMap {
            put("edition", editionMap())
            put("suites", summaries)
            put("scriptCount", lib.scripts.size)
            put("sharedStepCount", lib.sharedSteps.size)
            if (lib.readOnly) put("readOnly", true)
            appState.testLibraryLoadIssues.takeIf { it.isNotEmpty() }?.let { put("loadIssues", it) }
        }
    }

    private fun getSuite(suiteId: String): Map<String, Any?> {
        val lib = library
        val suite = lib.suite(suiteId) ?: return notFoundMap("suite", suiteId)
        return suiteMap(lib, suite)
    }

    private fun createSuite(a: ToolArgs): Map<String, Any?> {
        val parsed = applySuiteFields(a, TestSuite("", a.requiredString("name")))
        val created = appState.createTestSuite(parsed.name, parsed.description, parsed.instructions)
        if (created !is StoreResult.Ok) return created.toResult { emptyMap() }
        val suiteId = created.value.id
        val hasContent = parsed.setup.isNotEmpty() || parsed.teardown.isNotEmpty() || parsed.variables.isNotEmpty() ||
            parsed.targetPackage.isNotBlank() || parsed.deviceProfileHint.isNotBlank() || parsed.tags.isNotEmpty()
        if (!hasContent) return created.toResult { suiteResult(it) }
        val updated = appState.updateTestSuite(suiteId) {
            it.copy(
                targetPackage = parsed.targetPackage, deviceProfileHint = parsed.deviceProfileHint, tags = parsed.tags,
                setup = parsed.setup, teardown = parsed.teardown, variables = parsed.variables,
            )
        }
        if (updated !is StoreResult.Ok) {
            appState.deleteTestSuite(suiteId)
            return updated.toResult { emptyMap() }
        }
        return updated.toResult(referenceWarnings(library, parsed.setup + parsed.teardown, emptyList())) { suiteResult(it) }
    }

    private fun updateSuite(a: ToolArgs): Map<String, Any?> {
        val id = a.requiredString("suiteId")
        val old = library.suite(id) ?: return notFoundMap("suite", id)
        val updated = applySuiteFields(a, old)
        val warnings = referenceWarnings(library, updated.setup + updated.teardown, emptyList())
        return appState.updateTestSuite(id) { updated }.toResult(warnings) { suiteResult(it) }
    }

    private fun importSuite(a: ToolArgs): Map<String, Any?> {
        val path = a.string("path")
        val text = a.string("text")
        if ((path == null) == (text == null)) toolArgError("Send exactly one of path or text.")
        val result = if (path != null) appState.importTestSuiteFromFile(absoluteFile(path)) else appState.importTestSuite(text.orEmpty())
        return result.toResult { suiteResult(it) }
    }

    private fun exportSuite(a: ToolArgs): Map<String, Any?> {
        val id = a.requiredString("suiteId")
        val path = a.string("path")
        if (path == null) {
            return appState.exportTestSuite(id).toResult { mapOf("suiteId" to id, "format" to TEST_SUITE_FILE_FORMAT, "text" to it) }
        }
        if (library.suite(id) == null) return notFoundMap("suite", id)
        val destination = absoluteFile(path)
        if (destination.exists() && a.bool("overwrite") != true) {
            return errorMap("$path already exists; send overwrite=true to replace it.")
        }
        return appState.exportTestSuiteToFile(id, destination)
            .toResult { mapOf("suiteId" to id, "path" to it.absolutePath, "bytes" to it.length()) }
    }

    // ── Cases ────────────────────────────────────────────────────────

    private fun caseHandlers(): Map<String, (Map<String, Any?>) -> Any?> = mapOf(
        "create_test_case" to tool { a -> createCase(a) },
        "update_test_case" to tool { a -> updateCase(a) },
        "delete_test_case" to tool { a ->
            val id = a.requiredString("caseId")
            appState.deleteTestCase(id).toResult { mapOf("deleted" to true, "caseId" to id) }
        },
        "duplicate_test_case" to tool { a -> appState.duplicateTestCase(a.requiredString("caseId")).toResult { caseResult(it) } },
        "move_test_case" to tool { a ->
            appState.moveTestCase(a.requiredString("caseId"), a.requiredInt("toIndex"), a.string("toSuiteId")).toResult { caseResult(it) }
        },
    )

    private fun createCase(a: ToolArgs): Map<String, Any?> {
        val suiteId = a.requiredString("suiteId")
        val steps = parseNewSteps(a, "steps").orEmpty()
        val case = applyCaseFields(a, TestCase("", a.requiredString("name"))).copy(steps = steps)
        val warnings = referenceWarnings(library, case.setup + case.teardown, steps.flatMap { it.checks })
        return appState.createTestCase(suiteId, case, a.int("index")).toResult(warnings) { caseResult(it) }
    }

    private fun updateCase(a: ToolArgs): Map<String, Any?> {
        val id = a.requiredString("caseId")
        if (a.has("steps")) toolArgError("Steps are edited with create_test_step, update_test_step, move_test_step and delete_test_step.")
        val old = library.findCase(id)?.case ?: return notFoundMap("case", id)
        val updated = applyCaseFields(a, old)
        val warnings = referenceWarnings(library, updated.setup + updated.teardown, emptyList())
        return appState.updateTestCase(id) { updated }.toResult(warnings) { caseResult(it) }
    }

    // ── Steps ────────────────────────────────────────────────────────

    private fun stepHandlers(): Map<String, (Map<String, Any?>) -> Any?> = mapOf(
        "create_test_step" to tool { a -> createStep(a) },
        "update_test_step" to tool { a -> updateStep(a) },
        "delete_test_step" to tool { a ->
            val id = a.requiredString("stepId")
            appState.deleteTestStep(id).toResult { mapOf("deleted" to true, "stepId" to id) }
        },
        "duplicate_test_step" to tool { a -> appState.duplicateTestStep(a.requiredString("stepId")).toResult { stepResult(it) } },
        "move_test_step" to tool { a ->
            val id = a.requiredString("stepId")
            appState.moveTestStep(id, a.requiredInt("toIndex")).toResult { stepOrderResult(id) }
        },
    )

    private fun createStep(a: ToolArgs): Map<String, Any?> {
        val caseId = a.requiredString("caseId")
        a.requiredString("action")
        val step = applyStepFields(a, TestStep("", action = ""))
        val warnings = referenceWarnings(library, emptyList(), step.checks)
        return appState.createTestStep(caseId, step, a.int("index")).toResult(warnings) { stepResult(it) }
    }

    private fun updateStep(a: ToolArgs): Map<String, Any?> {
        val id = a.requiredString("stepId")
        val old = library.findStep(id)?.step ?: return notFoundMap("step", id)
        val updated = applyStepFields(a, old)
        val warnings = referenceWarnings(library, emptyList(), updated.checks)
        return appState.updateTestStep(id) { updated }.toResult(warnings) { stepResult(it) }
    }

    // ── Scripts ──────────────────────────────────────────────────────

    private fun scriptHandlers(): Map<String, (Map<String, Any?>) -> Any?> = mapOf(
        "list_test_scripts" to tool { mapOf("scripts" to library.scripts.map { scriptMap(it) }) },
        "create_test_script" to tool { a ->
            val script = applyScriptFields(a, TestScript("", a.requiredString("toolName")))
            appState.createTestScript(script).toResult { scriptMap(it) }
        },
        "update_test_script" to tool { a ->
            val id = a.requiredString("scriptId")
            val old = library.script(id) ?: return@tool notFoundMap("script", id)
            val updated = applyScriptFields(a, old)
            appState.updateTestScript(id) { updated }.toResult { scriptMap(it) }
        },
        "delete_test_script" to tool { a ->
            val id = a.requiredString("scriptId")
            appState.deleteTestScript(id).toResult { mapOf("deleted" to true, "scriptId" to id) }
        },
        "move_test_script" to tool { a ->
            val id = a.requiredString("scriptId")
            appState.moveTestScript(id, a.requiredInt("toIndex")).toResult {
                mapOf("scriptId" to id, "index" to library.scripts.indexOfFirst { it.id == id }, "scriptIds" to library.scripts.map { it.id })
            }
        },
    )

    private suspend fun tryScript(a: ToolArgs): Map<String, Any?> {
        val id = a.requiredString("scriptId")
        val script = library.script(id) ?: return notFoundMap("script", id)
        val rawArgs: Map<String, Any?> = a.map["args"]?.asObject("args") ?: emptyMap()
        val arguments = try {
            scriptArgsFromToolValues(rawArgs)
        } catch (invalid: IllegalArgumentException) {
            return errorMap(invalid.message ?: "Invalid arguments.")
        }
        val serial = a.string("deviceSerial")
        return when (val outcome = appState.tryTestScript(script, arguments, serial)) {
            is ScriptRunOutcome.Finished -> mapOf(
                "scriptId" to script.id,
                "toolName" to script.toolName,
                "target" to script.target.name,
                "deviceSerial" to serial?.trim()?.takeIf(String::isNotEmpty),
            ) + outcome.result.toToolResult()
            is ScriptRunOutcome.Rejected -> errorMap(outcome.message)
        }
    }

    // ── Shared steps ─────────────────────────────────────────────────

    private fun sharedStepHandlers(): Map<String, (Map<String, Any?>) -> Any?> = mapOf(
        "list_shared_steps" to tool { mapOf("sharedSteps" to library.sharedSteps.map { sharedMap(it) }) },
        "create_shared_step" to tool { a ->
            val shared = applySharedStepFields(a, SharedStep("", a.requiredString("name")))
            appState.createSharedStep(shared).toResult(referenceWarnings(library, emptyList(), shared.steps.flatMap { it.checks })) {
                sharedMap(it)
            }
        },
        "update_shared_step" to tool { a ->
            val id = a.requiredString("sharedStepId")
            val old = library.sharedStep(id) ?: return@tool notFoundMap("shared step", id)
            val updated = applySharedStepFields(a, old)
            appState.updateSharedStep(id) { updated }.toResult(referenceWarnings(library, emptyList(), updated.steps.flatMap { it.checks })) {
                sharedMap(it)
            }
        },
        "delete_shared_step" to tool { a ->
            val id = a.requiredString("sharedStepId")
            appState.deleteSharedStep(id).toResult { mapOf("deleted" to true, "sharedStepId" to id) }
        },
        "move_shared_step" to tool { a ->
            val id = a.requiredString("sharedStepId")
            appState.moveSharedStep(id, a.requiredInt("toIndex")).toResult {
                mapOf(
                    "sharedStepId" to id,
                    "index" to library.sharedSteps.indexOfFirst { it.id == id },
                    "sharedStepIds" to library.sharedSteps.map { it.id },
                )
            }
        },
    )

    // ── Edition ──────────────────────────────────────────────────────

    private fun editionHandlers(): Map<String, (Map<String, Any?>) -> Any?> = mapOf(
        "get_edition" to tool { editionMap() },
        "set_edition" to tool { a ->
            val raw = a.requiredString("edition")
            val edition = Edition.parse(raw)
                ?: return@tool errorMap("edition must be one of ${Edition.entries.joinToString(", ") { it.name }}.")
            if (appState.editionService.setForDev(edition)) {
                editionMap()
            } else {
                errorMap("Switching the edition is only allowed in an unpackaged build or with -Dindagium.dev=true.") + ("devSwitchAllowed" to false)
            }
        },
    )

    private fun editionMap(): Map<String, Any?> {
        val service = appState.editionService
        val edition = service.current.value
        val limits = edition.limits
        val limited = limits.maxSuites != null || limits.maxCasesPerSuite != null
        return mapOf(
            "edition" to edition.name,
            "label" to edition.label,
            "limits" to mapOf("maxSuites" to limits.maxSuites, "maxCasesPerSuite" to limits.maxCasesPerSuite),
            "devSwitchAllowed" to service.devSwitchAllowed(),
            "hint" to (if (limited) FREE_EDITION_LIMIT_HINT else null),
        )
    }

    // ── Result mapping ───────────────────────────────────────────────

    private fun errorMap(message: String): Map<String, Any?> = mapOf("error" to message)

    private fun notFoundMap(kind: String, id: String): Map<String, Any?> =
        errorMap("${kind.replaceFirstChar { it.uppercase() }} '$id' not found.")

    /** The one place a [StoreResult] becomes a tool result; [extraWarnings] are added to the store's own. */
    private fun <T> StoreResult<T>.toResult(
        extraWarnings: List<String> = emptyList(),
        onOk: (T) -> Map<String, Any?>,
    ): Map<String, Any?> = when (this) {
        is StoreResult.Ok -> {
            val all = warnings + extraWarnings
            if (all.isEmpty()) onOk(value) else onOk(value) + ("warnings" to all)
        }
        is StoreResult.NotFound -> notFoundMap(kind, id)
        is StoreResult.Invalid -> errorMap(reason)
        is StoreResult.LimitReached -> mapOf(
            "error" to decision.message,
            "limit" to mapOf(
                "kind" to decision.kind.name,
                "max" to decision.limit,
                "edition" to appState.editionService.current.value.name,
                "hint" to decision.hint,
            ),
        )
    }

    // ── Entity maps ──────────────────────────────────────────────────

    /** The whole suite as file JSON, plus `locked` (and `readOnly`) on the suite and `locked` on each case. */
    private fun suiteMap(lib: TestLibrary, suite: TestSuite): Map<String, Any?> {
        val limits = appState.editionService.limits()
        val activeCases = activeCaseIds(lib, limits)
        val map = suiteToJson(suite).toPlainMap()
        map["cases"] = suite.cases.map { caseMap(it, locked = it.id !in activeCases) }
        map["locked"] = suite.id !in activeSuiteIds(lib, limits)
        map["readOnly"] = suite.readOnly
        return map
    }

    private fun suiteResult(suite: TestSuite): Map<String, Any?> {
        val lib = library
        return suiteMap(lib, lib.suite(suite.id) ?: suite)
    }

    private fun caseMap(case: TestCase, locked: Boolean): MutableMap<String, Any?> {
        val map = caseToJson(case).toPlainMap()
        if (!map.containsKey("allowedTools")) map["allowedTools"] = null
        map["locked"] = locked
        return map
    }

    /** A case with its position: `suiteId` and `index` say where it lives now. */
    private fun caseResult(case: TestCase): Map<String, Any?> {
        val lib = library
        val location = lib.findCase(case.id) ?: return caseMap(case, locked = false)
        val map = caseMap(location.case, isCaseLocked(lib, case.id, appState.editionService.limits()))
        map["suiteId"] = location.suite.id
        map["index"] = location.index
        return map
    }

    private fun stepResult(step: TestStep): Map<String, Any?> {
        val location = library.findStep(step.id)
        val map = stepToJson(location?.step ?: step).toPlainMap()
        location?.let {
            map["caseId"] = it.case.id
            map["suiteId"] = it.suite.id
            map["index"] = it.index
        }
        return map
    }

    private fun stepOrderResult(stepId: String): Map<String, Any?> {
        val location = library.findStep(stepId)
        return mapOf(
            "stepId" to stepId,
            "caseId" to location?.case?.id,
            "index" to location?.index,
            "stepIds" to location?.case?.steps?.map { it.id }.orEmpty(),
        )
    }

    private fun scriptMap(script: TestScript): Map<String, Any?> = scriptToJson(script).toPlainMap()

    private fun sharedMap(shared: SharedStep): Map<String, Any?> = sharedStepToJson(shared).toPlainMap()

    // ── Helpers ──────────────────────────────────────────────────────

    private fun absoluteFile(path: String): File {
        val file = File(path)
        if (path.isBlank() || !file.isAbsolute) toolArgError("path must be an absolute file path.")
        return file
    }

    /** Hooks and checks may point at scripts or shared steps that do not exist (yet); the save still succeeds. */
    private fun referenceWarnings(lib: TestLibrary, hooks: List<HookItem>, checks: List<StepCheck>): List<String> {
        val scriptIds = hooks.filterIsInstance<HookItem.Script>().map { it.scriptId } +
            checks.filterIsInstance<StepCheck.ScriptResult>().map { it.scriptId }
        val missingScripts = scriptIds.filter { lib.script(it) == null }.distinct()
        val missingShared = hooks.filterIsInstance<HookItem.Shared>().map { it.sharedStepId }.filter { lib.sharedStep(it) == null }.distinct()
        return missingScripts.map { "References unknown script '$it'." } + missingShared.map { "References unknown shared step '$it'." }
    }
}
