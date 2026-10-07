package com.indagium.debug

import com.indagium.edition.DEV_PROPERTY
import com.indagium.edition.Edition
import com.indagium.edition.EditionService
import com.indagium.edition.PACKAGED_APP_PROPERTY
import com.indagium.testing.limits.FREE_EDITION_LIMIT_HINT
import com.indagium.ui.AppState
import io.modelcontextprotocol.kotlin.sdk.types.ToolSchema
import java.io.File
import kotlin.io.path.createTempDirectory
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

private const val FREE_CASE_LIMIT = 5
private const val FREE_SUITE_LIMIT = 1
private const val OVER_LIMIT_CASES = 7
private const val LOG_TIMEOUT_MS = 7_000
private const val LOG_ABSENT_MS = 3_000
private const val STEP_TIMEOUT_MS = 45_000
private const val STEP_RETRIES = 2

/** The test-suite authoring tools, driven through the same gateway the MCP server and the in-app AI use. */
class TestSuiteToolsGatewayTest {
    private lateinit var dir: File
    private lateinit var state: AppState
    private lateinit var operations: IndagiumToolOperations

    @BeforeTest
    fun setUp() {
        dir = createTempDirectory("test-suite-tools").toFile()
        open(EditionService())
    }

    @AfterTest
    fun tearDown() {
        state.close()
        dir.deleteRecursively()
    }

    private fun open(edition: EditionService, root: File = dir) {
        if (::state.isInitialized) state.close()
        state = AppState(
            autosaveFile = File(root, "state.cache"),
            autoExportNotes = false,
            notesDir = File(root, "notes"),
            archiveCacheDir = File(root, "archive"),
            customCommandsDir = File(root, "commands"),
            controlTokenFile = File(root, "token"),
            sourceIndexFile = File(root, "source-index"),
            testingDir = File(root, "testing"),
            editionService = edition,
        )
        operations = IndagiumToolOperations(state)
    }

    // Arguments and results take the REST detour (Json.encode -> Json.decode) so the tests see exactly what a
    // client sees and prove every result is JSON-encodable.
    @Suppress("UNCHECKED_CAST")
    private fun call(tool: String, vararg args: Pair<String, Any?>): Map<String, Any?> {
        val wire = Json.decode(Json.encode(mapOf(*args))) as Map<String, Any?>
        val result = operations.toolGateway.execute(tool, wire)
        return Json.decode(Json.encode(result)) as Map<String, Any?>
    }

    private fun Map<String, Any?>.id(): String = this["id"] as String

    @Suppress("UNCHECKED_CAST")
    private fun Map<String, Any?>.list(key: String): List<Map<String, Any?>> = this[key] as List<Map<String, Any?>>

    @Suppress("UNCHECKED_CAST")
    private fun Map<String, Any?>.obj(key: String): Map<String, Any?> = this[key] as Map<String, Any?>

    private fun assertOk(result: Map<String, Any?>): Map<String, Any?> {
        assertNull(result["error"], "unexpected error: $result")
        return result
    }

    private fun assertError(result: Map<String, Any?>, fragment: String): Map<String, Any?> {
        val message = result["error"] as? String
        assertNotNull(message, "expected an error but got $result")
        assertTrue(message.contains(fragment, ignoreCase = true), "'$message' should mention '$fragment'")
        return result
    }

    private fun suiteNamed(name: String, vararg extra: Pair<String, Any?>) = assertOk(call("create_test_suite", "name" to name, *extra))

    private fun caseIn(suiteId: String, name: String, vararg extra: Pair<String, Any?>) =
        assertOk(call("create_test_case", "suiteId" to suiteId, "name" to name, *extra))

    private fun stepIn(caseId: String, action: String, vararg extra: Pair<String, Any?>) =
        assertOk(call("create_test_step", "caseId" to caseId, "action" to action, *extra))

    private fun getSuite(suiteId: String) = assertOk(call("get_test_suite", "suiteId" to suiteId))

    private fun caseNames(suiteId: String) = getSuite(suiteId).list("cases").map { it["name"] }

    private fun stepActions(suiteId: String, caseIndex: Int = 0) =
        getSuite(suiteId).list("cases")[caseIndex].list("steps").map { it["action"] }

    private fun allChecks(script: String): List<Map<String, Any?>> = listOf(
        mapOf("type" to "logAppears", "tag" to "ActivityManager", "regex" to "Displayed .*Settings", "withinMs" to LOG_TIMEOUT_MS),
        mapOf("type" to "logAbsent", "regex" to "FATAL EXCEPTION", "forMs" to LOG_ABSENT_MS),
        mapOf("type" to "screenJudge", "text" to "Settings title is visible", "exampleRef" to "ex-golden"),
        mapOf("type" to "scriptResult", "scriptId" to script, "args" to mapOf("package_name" to "com.example.app"), "stdoutContains" to "ok"),
        mapOf("type" to "scriptResult", "scriptId" to script, "exitCode" to null),
        mapOf("type" to "askJudge", "text" to "Does it look right?"),
    )

    private val allExamples: List<Map<String, Any?>> = listOf(
        mapOf("id" to "ex-golden", "type" to "goldenScreenshot", "assetPath" to "golden/home.png", "caption" to "Home"),
        mapOf("type" to "referenceLog", "text" to "I/App: ready", "caption" to "Ready"),
    )

    // ── CRUD ─────────────────────────────────────────────────────────

    @Test
    fun suiteCaseStepRoundTripWithEveryCheckAndExampleKind() {
        val script = assertOk(call("create_test_script", "toolName" to "reset_app", "commandTemplate" to "pm clear \"\$package_name\""))
        val shared = assertOk(call("create_shared_step", "name" to "Log in"))
        val suite = suiteNamed(
            "Smoke", "description" to "Quick", "instructions" to "Use the test account",
            "setup" to listOf(
                mapOf("type" to "script", "scriptId" to script.id(), "args" to mapOf("package_name" to "com.example.app")),
                mapOf("type" to "shared", "sharedStepId" to shared.id()),
            ),
            "teardown" to listOf(mapOf("type" to "script", "scriptId" to script.id())),
            "variables" to listOf(mapOf("name" to "user", "value" to "tester", "description" to "Account")),
        )
        assertEquals(false, suite["locked"])
        assertEquals(2, suite.list("setup").size)
        assertEquals("shared", suite.list("setup")[1]["type"])
        assertEquals("user", suite.list("variables").single()["name"])

        val case = caseIn(suite.id(), "Settings", "instructions" to "Be careful", "allowedTools" to listOf("tap", "take_screenshot"))
        assertEquals(suite.id(), case["suiteId"])
        assertEquals(0, case["index"])
        assertEquals(false, case["locked"])
        assertEquals(listOf("tap", "take_screenshot"), case["allowedTools"])

        val step = stepIn(
            case.id(), "Open settings",
            "expected" to "The settings screen is shown", "timeoutMs" to STEP_TIMEOUT_MS, "retries" to STEP_RETRIES,
            "onFailure" to "create_issue_and_continue",
            "checks" to allChecks(script.id()), "examples" to allExamples,
        )
        assertNull(step["warnings"])
        assertEquals("CREATE_ISSUE_AND_CONTINUE", step["onFailure"])
        assertEquals(case.id(), step["caseId"])

        val stored = getSuite(suite.id()).list("cases").single().list("steps").single()
        val checks = stored.list("checks")
        assertEquals(listOf("logAppears", "logAbsent", "screenJudge", "scriptResult", "scriptResult", "askJudge"), checks.map { it["type"] })
        assertEquals(LOG_TIMEOUT_MS, checks[0]["withinMs"])
        assertEquals("ActivityManager", checks[0]["tag"])
        assertEquals("ex-golden", checks[2]["exampleRef"])
        assertEquals(0, checks[3]["exitCode"])
        assertEquals(mapOf("package_name" to "com.example.app"), checks[3]["args"])
        assertTrue(checks[4].containsKey("exitCode") && checks[4]["exitCode"] == null, "explicit null exit code means any")
        assertTrue(checks.all { (it["id"] as String).startsWith("chk-") })
        val examples = stored.list("examples")
        assertEquals(listOf("goldenScreenshot", "referenceLog"), examples.map { it["type"] })
        assertEquals("ex-golden", examples[0]["id"])
        assertEquals("golden/home.png", examples[0]["assetPath"])
        assertEquals(STEP_TIMEOUT_MS, stored["timeoutMs"])
        assertEquals(STEP_RETRIES, stored["retries"])
    }

    @Test
    fun updateAndDuplicateAndDeleteAcrossTheHierarchy() {
        val suite = suiteNamed("Alpha")
        val case = caseIn(suite.id(), "Login")
        val step = stepIn(case.id(), "Tap login", "checks" to listOf(mapOf("type" to "askJudge", "text" to "ok")))

        val renamed = assertOk(call("update_test_suite", "suiteId" to suite.id(), "name" to "Alpha 2", "instructions" to "Go"))
        assertEquals("Alpha 2", renamed["name"])
        assertEquals("Go", renamed["instructions"])
        assertEquals(1, renamed.list("cases").size, "update must not touch the cases")

        val updatedCase = assertOk(call("update_test_case", "caseId" to case.id(), "description" to "Logs in", "allowedTools" to listOf<String>()))
        assertEquals("Logs in", updatedCase["description"])
        assertEquals(emptyList<Any?>(), updatedCase["allowedTools"])
        assertNull(assertOk(call("update_test_case", "caseId" to case.id(), "allowAllTools" to true))["allowedTools"])

        val updatedStep = assertOk(call("update_test_step", "stepId" to step.id(), "expected" to "Home", "retries" to 0))
        assertEquals("Home", updatedStep["expected"])
        assertEquals(0, updatedStep["retries"])
        assertEquals("Tap login", updatedStep["action"])
        assertEquals(1, updatedStep.list("checks").size, "untouched checks are kept")

        val stepCopy = assertOk(call("duplicate_test_step", "stepId" to step.id()))
        assertNotEquals(step.id(), stepCopy["id"])
        assertEquals(1, stepCopy["index"])
        assertNotEquals(step.list("checks")[0]["id"], stepCopy.list("checks")[0]["id"])

        val caseCopy = assertOk(call("duplicate_test_case", "caseId" to case.id()))
        assertEquals("Login (copy)", caseCopy["name"])
        assertEquals(1, caseCopy["index"])
        assertEquals(2, caseCopy.list("steps").size)

        val suiteCopy = assertOk(call("duplicate_test_suite", "suiteId" to suite.id()))
        assertEquals("Alpha 2 (copy)", suiteCopy["name"])
        assertEquals(2, suiteCopy.list("cases").size)
        assertEquals(listOf(suite.id(), suiteCopy.id()), call("list_test_suites").list("suites").map { it["id"] })

        assertEquals(true, assertOk(call("delete_test_step", "stepId" to stepCopy.id()))["deleted"])
        assertEquals(1, getSuite(suite.id()).list("cases")[0].list("steps").size)
        assertOk(call("delete_test_case", "caseId" to caseCopy.id()))
        assertEquals(listOf("Login"), caseNames(suite.id()))
        assertOk(call("delete_test_suite", "suiteId" to suiteCopy.id()))
        assertEquals(listOf(suite.id()), call("list_test_suites").list("suites").map { it["id"] })
        assertError(call("get_test_suite", "suiteId" to suiteCopy.id()), "not found")
    }

    @Test
    fun listTestSuitesSummarisesSuitesCasesAndEdition() {
        val suite = suiteNamed("Alpha")
        val case = caseIn(suite.id(), "One")
        stepIn(case.id(), "A")
        stepIn(case.id(), "B")
        val listing = assertOk(call("list_test_suites"))
        val summary = listing.list("suites").single()
        assertEquals(1, summary["caseCount"])
        assertEquals(2, summary["stepCount"])
        assertEquals(false, summary["locked"])
        assertEquals(false, summary["readOnly"])
        assertEquals(mapOf("id" to case.id(), "name" to "One", "stepCount" to 2, "locked" to false), summary.list("cases").single())
        assertEquals("UNLIMITED", listing.obj("edition")["edition"])
        assertEquals(0, listing["scriptCount"])
    }

    // ── Reordering ───────────────────────────────────────────────────

    @Test
    fun moveOperationsAreReflectedInGetTestSuiteOrder() {
        val a = suiteNamed("A")
        val b = suiteNamed("B")
        val c = suiteNamed("C")
        assertEquals(listOf(c.id(), a.id(), b.id()), assertOk(call("move_test_suite", "suiteId" to c.id(), "toIndex" to 0))["suiteIds"])
        assertEquals(listOf("C", "A", "B"), call("list_test_suites").list("suites").map { it["name"] })
        assertEquals(listOf(a.id(), b.id(), c.id()), assertOk(call("move_test_suite", "suiteId" to c.id(), "toIndex" to 99))["suiteIds"])

        val c1 = caseIn(a.id(), "c1")
        caseIn(a.id(), "c2")
        caseIn(a.id(), "c3")
        val moved = assertOk(call("move_test_case", "caseId" to c1.id(), "toIndex" to 2))
        assertEquals(2, moved["index"])
        assertEquals(listOf("c2", "c3", "c1"), caseNames(a.id()))

        val case = getSuite(a.id()).list("cases")[0]
        val s1 = stepIn(case.id(), "s1")
        stepIn(case.id(), "s2")
        stepIn(case.id(), "s3")
        val order = assertOk(call("move_test_step", "stepId" to s1.id(), "toIndex" to 1))
        assertEquals(1, order["index"])
        assertEquals(3, (order["stepIds"] as List<*>).size)
        assertEquals(listOf("s2", "s1", "s3"), stepActions(a.id()))
    }

    @Test
    fun aCaseCanMoveToAnotherSuiteAtAnIndex() {
        val a = suiteNamed("A")
        val b = suiteNamed("B")
        val keep = caseIn(a.id(), "keep")
        val travel = caseIn(a.id(), "travel")
        caseIn(b.id(), "b1")
        caseIn(b.id(), "b2")
        val result = assertOk(call("move_test_case", "caseId" to travel.id(), "toIndex" to 1, "toSuiteId" to b.id()))
        assertEquals(b.id(), result["suiteId"])
        assertEquals(1, result["index"])
        assertEquals(listOf("keep"), caseNames(a.id()))
        assertEquals(listOf("b1", "travel", "b2"), caseNames(b.id()))
        assertEquals(keep.id(), getSuite(a.id()).list("cases").single().id())
        assertError(call("move_test_case", "caseId" to keep.id(), "toIndex" to 0, "toSuiteId" to "suite-missing"), "not found")
    }

    @Test
    fun updateTestStepReplacesChecksAndExamplesAsWholeOrderedLists() {
        val case = caseIn(suiteNamed("S").id(), "C")
        val step = stepIn(
            case.id(), "Act",
            "checks" to listOf(
                mapOf("id" to "chk-first", "type" to "logAppears", "regex" to "one"),
                mapOf("id" to "chk-second", "type" to "logAbsent", "regex" to "two"),
                mapOf("id" to "chk-third", "type" to "askJudge", "text" to "three"),
            ),
            "examples" to listOf(
                mapOf("id" to "ex-a", "type" to "referenceLog", "text" to "A"),
                mapOf("id" to "ex-b", "type" to "referenceLog", "text" to "B"),
            ),
        )
        val reordered = assertOk(
            call(
                "update_test_step", "stepId" to step.id(),
                "checks" to listOf(
                    mapOf("id" to "chk-third", "type" to "askJudge", "text" to "three"),
                    mapOf("id" to "chk-first", "type" to "logAppears", "regex" to "one"),
                    mapOf("type" to "screenJudge", "text" to "fresh", "exampleRef" to "ex-b"),
                ),
            ),
        )
        val checks = reordered.list("checks")
        assertEquals("chk-third", checks[0]["id"])
        assertEquals("chk-first", checks[1]["id"])
        assertEquals("screenJudge", checks[2]["type"])
        assertTrue((checks[2]["id"] as String).startsWith("chk-"))
        assertEquals(listOf("ex-a", "ex-b"), reordered.list("examples").map { it["id"] }, "examples were not sent, so they are kept")

        val keptExample = mapOf("id" to "ex-b", "type" to "referenceLog", "text" to "B")
        val replacedExamples = assertOk(
            call("update_test_step", "stepId" to step.id(), "checks" to listOf<Any>(), "examples" to listOf(keptExample)),
        )
        assertEquals(emptyList<Any?>(), replacedExamples["checks"])
        assertEquals(listOf("ex-b"), replacedExamples.list("examples").map { it["id"] })
    }

    @Test
    fun scriptsCrudAndMove() {
        val first = assertOk(
            call(
                "create_test_script", "toolName" to "reset_app", "description" to "Clears the app", "commandTemplate" to "pm clear \"\$package_name\"",
                "target" to "adb_shell", "permission" to "AUTO", "timeoutMs" to 12_000, "outputCapBytes" to 2048, "workingDir" to "/tmp/work",
                "params" to listOf(
                    mapOf("name" to "package_name", "description" to "Package"),
                    mapOf("name" to "count", "type" to "int", "required" to false, "defaultValue" to 3),
                ),
            ),
        )
        assertEquals("ADB_SHELL", first["target"])
        assertEquals("AUTO", first["permission"])
        assertEquals("/tmp/work", first["workingDir"])
        assertEquals(listOf("STRING", "INT"), first.list("params").map { it["type"] })
        assertEquals("3", first.list("params")[1]["defaultValue"])
        assertEquals(false, first.list("params")[1]["required"])
        val second = assertOk(call("create_test_script", "toolName" to "list_files", "commandTemplate" to "ls"))
        assertEquals("ASK", second["permission"])
        assertEquals("HOST_SHELL", second["target"])

        assertEquals(listOf("reset_app", "list_files"), call("list_test_scripts").list("scripts").map { it["toolName"] })
        val moved = assertOk(call("move_test_script", "scriptId" to second.id(), "toIndex" to 0))
        assertEquals(listOf(second.id(), first.id()), moved["scriptIds"])
        assertEquals(0, moved["index"])

        val updated = assertOk(
            call("update_test_script", "scriptId" to first.id(), "permission" to "SETUP_TEARDOWN_ONLY", "workingDir" to "", "params" to listOf<Any>()),
        )
        assertEquals("SETUP_TEARDOWN_ONLY", updated["permission"])
        assertNull(updated["workingDir"])
        assertEquals(emptyList<Any?>(), updated["params"])
        assertEquals("reset_app", updated["toolName"])
        assertEquals("pm clear \"\$package_name\"", updated["commandTemplate"])

        assertError(call("create_test_script", "toolName" to "reset_app", "commandTemplate" to "x"), "already exists")
        assertError(call("create_test_script", "toolName" to "tap", "commandTemplate" to "x"), "built-in")
        assertError(call("create_test_script", "toolName" to "ok_name", "commandTemplate" to "x", "params" to listOf(mapOf("name" to "home"))), "reserved")
        val floatParam = listOf(mapOf("name" to "a", "type" to "FLOAT"))
        assertError(call("create_test_script", "toolName" to "ok_name", "commandTemplate" to "x", "params" to floatParam), "type")
        assertError(call("create_test_script", "toolName" to "ok_name", "target" to "SSH", "commandTemplate" to "x"), "target")

        assertOk(call("delete_test_script", "scriptId" to second.id()))
        assertEquals(listOf(first.id()), call("list_test_scripts").list("scripts").map { it["id"] })
    }

    @Test
    fun sharedStepsCrudAndMove() {
        val login = assertOk(
            call(
                "create_shared_step", "name" to "Log in", "description" to "Signs in",
                "steps" to listOf(
                    mapOf("action" to "Open login", "checks" to listOf(mapOf("type" to "askJudge", "text" to "form visible"))),
                    mapOf("action" to "Submit", "onFailure" to "PAUSE_FOR_USER"),
                ),
            ),
        )
        assertEquals(listOf("Open login", "Submit"), login.list("steps").map { it["action"] })
        assertEquals("PAUSE_FOR_USER", login.list("steps")[1]["onFailure"])
        assertTrue(login.list("steps").all { (it["id"] as String).startsWith("step-") })
        val logout = assertOk(call("create_shared_step", "name" to "Log out"))
        assertEquals(listOf("Log in", "Log out"), call("list_shared_steps").list("sharedSteps").map { it["name"] })

        val moved = assertOk(call("move_shared_step", "sharedStepId" to logout.id(), "toIndex" to 0))
        assertEquals(listOf(logout.id(), login.id()), moved["sharedStepIds"])

        val updated = assertOk(call("update_shared_step", "sharedStepId" to login.id(), "name" to "Sign in", "steps" to listOf(mapOf("action" to "Only step"))))
        assertEquals("Sign in", updated["name"])
        assertEquals(listOf("Only step"), updated.list("steps").map { it["action"] })
        assertEquals("Signs in", updated["description"])

        assertOk(call("delete_shared_step", "sharedStepId" to logout.id()))
        assertEquals(listOf(login.id()), call("list_shared_steps").list("sharedSteps").map { it["id"] })
        assertError(call("create_shared_step", "name" to "Bad", "steps" to listOf(mapOf("expected" to "no action"))), "action")
    }

    // ── Target, tags, case goal/hooks, tool-call budget ──────────────

    @Test
    fun suiteTargetPackageDeviceHintAndTagsRoundTripAndAreValidated() {
        val suite = suiteNamed(
            "Targeted", "targetPackage" to "com.example.app", "deviceProfileHint" to "Pixel 8", "tags" to listOf(" smoke ", "Smoke", "ui", ""),
        )
        assertEquals("com.example.app", suite["targetPackage"])
        assertEquals("Pixel 8", suite["deviceProfileHint"])
        assertEquals(listOf("smoke", "ui"), suite["tags"])
        val summary = call("list_test_suites").list("suites").single()
        assertEquals("com.example.app", summary["targetPackage"])
        assertEquals(listOf("smoke", "ui"), summary["tags"])

        assertError(call("update_test_suite", "suiteId" to suite.id(), "targetPackage" to "not a package"), "package")
        assertError(call("update_test_suite", "suiteId" to suite.id(), "tags" to listOf("x".repeat(41))), "too long")
        assertError(call("update_test_suite", "suiteId" to suite.id(), "tags" to "ui"), "tags must be an array")
        assertEquals("com.example.app", getSuite(suite.id())["targetPackage"], "refused updates change nothing")

        val cleared = assertOk(call("update_test_suite", "suiteId" to suite.id(), "targetPackage" to "", "tags" to listOf<String>()))
        assertEquals("", cleared["targetPackage"])
        assertEquals(emptyList<Any?>(), cleared["tags"])
        assertEquals("Pixel 8", cleared["deviceProfileHint"], "unsent fields are kept")

        assertError(call("create_test_suite", "name" to "Bad", "targetPackage" to "nope"), "package")
        assertEquals(1, call("list_test_suites").list("suites").size, "a refused create leaves nothing behind")
    }

    @Test
    fun caseGoalPreconditionsAndHooksRoundTripWithWarningsAndFreshIdsOnDuplicate() {
        val script = assertOk(call("create_test_script", "toolName" to "reset_app", "commandTemplate" to "x"))
        val suite = suiteNamed("S")
        val case = caseIn(
            suite.id(), "Login", "description" to "Verify login works", "preconditions" to "Signed out",
            "setup" to listOf(mapOf("type" to "script", "scriptId" to script.id(), "args" to mapOf("package_name" to "com.example.app"))),
            "teardown" to listOf(mapOf("type" to "script", "scriptId" to "script-gone")),
        )
        assertEquals("Verify login works", case["description"])
        assertEquals("Signed out", case["preconditions"])
        assertEquals(1, case.list("setup").size)
        assertTrue((case["warnings"] as List<*>).single().toString().contains("script-gone"))

        val sharedHook = listOf(mapOf("type" to "shared", "sharedStepId" to "shared-x"))
        val updated = assertOk(call("update_test_case", "caseId" to case.id(), "setup" to sharedHook, "preconditions" to "Signed in"))
        assertEquals("shared", updated.list("setup").single()["type"])
        assertEquals("Signed in", updated["preconditions"])
        assertEquals(1, updated.list("teardown").size, "hooks that were not sent are kept")
        assertError(call("update_test_case", "caseId" to case.id(), "setup" to listOf(mapOf("type" to "launch"))), "setup[0].type must be one of")

        val copy = assertOk(call("duplicate_test_case", "caseId" to case.id()))
        val originalIds = (updated.list("setup") + updated.list("teardown")).map { it["id"] }
        assertTrue((copy.list("setup") + copy.list("teardown")).map { it["id"] }.none { it in originalIds })
        val suiteCopy = assertOk(call("duplicate_test_suite", "suiteId" to suite.id()))
        val copiedCaseHooks = suiteCopy.list("cases").flatMap { it.list("setup") + it.list("teardown") }.map { it["id"] }
        assertTrue(copiedCaseHooks.isNotEmpty() && copiedCaseHooks.none { it in originalIds })
    }

    @Test
    fun stepMaxToolCallsDefaultsAndIsValidated() {
        val case = caseIn(suiteNamed("S").id(), "C")
        val defaulted = stepIn(case.id(), "A")
        assertEquals(15, defaulted["maxToolCalls"])
        assertEquals(40, stepIn(case.id(), "B", "maxToolCalls" to 40)["maxToolCalls"])
        assertError(call("create_test_step", "caseId" to case.id(), "action" to "x", "maxToolCalls" to 0), "max tool calls")
        assertError(call("create_test_step", "caseId" to case.id(), "action" to "x", "maxToolCalls" to 101), "max tool calls")
        val updated = assertOk(call("update_test_step", "stepId" to defaulted.id(), "maxToolCalls" to 100))
        assertEquals(100, updated["maxToolCalls"])
        assertError(call("update_test_step", "stepId" to defaulted.id(), "maxToolCalls" to 101), "max tool calls")
        val shared = assertOk(call("create_shared_step", "name" to "N", "steps" to listOf(mapOf("action" to "a", "maxToolCalls" to 7))))
        assertEquals(7, shared.list("steps").single()["maxToolCalls"])
    }

    // ── Edition limits ───────────────────────────────────────────────

    @Test
    fun freeEditionRefusesTheSecondSuiteAndTheSixthCaseWithTheLimitShape() {
        assertEquals("FREE", assertOk(call("set_edition", "edition" to "free"))["edition"])
        val suite = suiteNamed("Only")
        val second = call("create_test_suite", "name" to "Second")
        assertEquals(
            mapOf(
                "error" to "Suite limit reached: this edition allows $FREE_SUITE_LIMIT suite(s).",
                "limit" to mapOf("kind" to "SUITE_LIMIT", "max" to FREE_SUITE_LIMIT, "edition" to "FREE", "hint" to FREE_EDITION_LIMIT_HINT),
            ),
            second,
        )
        assertEquals(listOf(suite.id()), call("list_test_suites").list("suites").map { it["id"] })

        repeat(FREE_CASE_LIMIT) { caseIn(suite.id(), "case ${it + 1}") }
        val sixth = call("create_test_case", "suiteId" to suite.id(), "name" to "case 6")
        assertEquals(
            mapOf(
                "error" to "Case limit reached: this edition allows $FREE_CASE_LIMIT case(s) per suite.",
                "limit" to mapOf("kind" to "CASE_LIMIT", "max" to FREE_CASE_LIMIT, "edition" to "FREE", "hint" to FREE_EDITION_LIMIT_HINT),
            ),
            sixth,
        )
        assertEquals(FREE_CASE_LIMIT, caseNames(suite.id()).size)
        assertError(call("duplicate_test_suite", "suiteId" to suite.id()), "limit")
        assertTrue(call("duplicate_test_suite", "suiteId" to suite.id()).containsKey("limit"))
        assertTrue(call("duplicate_test_case", "caseId" to getSuite(suite.id()).list("cases")[0].id()).containsKey("limit"))
    }

    @Test
    fun lockedFlagsAreVisibleAndLockedEntriesRefuseEditsButAllowReorderAndDelete() {
        val first = suiteNamed("First")
        repeat(OVER_LIMIT_CASES) { caseIn(first.id(), "case ${it + 1}") }
        val second = suiteNamed("Second")
        val secondCase = caseIn(second.id(), "belongs to a locked suite")
        assertOk(call("set_edition", "edition" to "FREE"))

        val listing = call("list_test_suites")
        assertEquals(listOf(false, true), listing.list("suites").map { it["locked"] })
        val firstCases = getSuite(first.id()).list("cases")
        assertEquals(listOf(false, false, false, false, false, true, true), firstCases.map { it["locked"] })
        assertEquals(true, getSuite(second.id())["locked"])
        assertEquals(true, getSuite(second.id()).list("cases").single()["locked"])
        assertEquals(listOf(true, true), listing.list("suites")[0].list("cases").takeLast(2).map { it["locked"] })

        val lockedCase = firstCases[5]
        val refused = call("update_test_case", "caseId" to lockedCase.id(), "name" to "edited")
        assertEquals("LOCKED", refused.obj("limit")["kind"])
        assertNull(refused.obj("limit")["max"])
        assertEquals("FREE", refused.obj("limit")["edition"])
        assertTrue(call("update_test_suite", "suiteId" to second.id(), "name" to "edited").containsKey("limit"))
        assertTrue(call("create_test_step", "caseId" to secondCase.id(), "action" to "x").containsKey("limit"))
        assertTrue(call("update_test_step", "stepId" to "step-missing", "action" to "x")["error"] != null)

        // Reordering a locked case to the front makes it active; deleting is always allowed.
        assertOk(call("move_test_case", "caseId" to lockedCase.id(), "toIndex" to 0))
        assertEquals(false, getSuite(first.id()).list("cases")[0]["locked"])
        assertOk(call("delete_test_case", "caseId" to firstCases[6].id()))
        assertOk(call("move_test_suite", "suiteId" to second.id(), "toIndex" to 0))
        assertEquals(listOf(false, true), call("list_test_suites").list("suites").map { it["locked"] })
        assertOk(call("delete_test_suite", "suiteId" to first.id()))
    }

    @Test
    fun importingAnOverLimitSuiteUnderFreeEditionWarnsAndLocksTheExtraCases() {
        val source = suiteNamed("Big")
        repeat(OVER_LIMIT_CASES) { caseIn(source.id(), "case ${it + 1}") }
        val text = assertOk(call("export_test_suite", "suiteId" to source.id()))["text"] as String
        assertOk(call("delete_test_suite", "suiteId" to source.id()))
        assertOk(call("set_edition", "edition" to "FREE"))
        val imported = assertOk(call("import_test_suite", "text" to text))
        assertNotEquals(source.id(), imported.id())
        assertEquals(OVER_LIMIT_CASES, imported.list("cases").size)
        assertEquals(2, imported.list("cases").count { it["locked"] == true })
        assertTrue((imported["warnings"] as List<*>).single().toString().contains("locked"))
        assertTrue(call("import_test_suite", "text" to text).containsKey("limit"), "the Free edition has room for one suite only")
    }

    @Test
    fun getEditionReportsLimitsAndTheDevSwitch() {
        val unlimited = assertOk(call("get_edition"))
        assertEquals("UNLIMITED", unlimited["edition"])
        assertEquals(mapOf("maxSuites" to null, "maxCasesPerSuite" to null), unlimited.obj("limits"))
        assertNull(unlimited["hint"])
        assertEquals(true, unlimited["devSwitchAllowed"])
        val free = assertOk(call("set_edition", "edition" to "free"))
        assertEquals(mapOf("maxSuites" to FREE_SUITE_LIMIT, "maxCasesPerSuite" to FREE_CASE_LIMIT), free.obj("limits"))
        assertEquals(FREE_EDITION_LIMIT_HINT, free["hint"])
        assertEquals("FRIENDS_FAMILY", assertOk(call("set_edition", "edition" to "friends-family"))["edition"])
        assertError(call("set_edition", "edition" to "gold"), "one of")
        assertError(call("set_edition"), "required")
    }

    @Test
    fun setEditionIsRefusedWhenTheDevSwitchIsNotAllowed() {
        val packaged = EditionService(Edition.FREE, props = { key -> if (key == PACKAGED_APP_PROPERTY) "/Applications/Indagium" else null })
        open(packaged)
        val refused = call("set_edition", "edition" to "UNLIMITED")
        assertError(refused, "unpackaged")
        assertEquals(false, refused["devSwitchAllowed"])
        assertEquals("FREE", call("get_edition")["edition"])
        assertEquals(false, call("get_edition")["devSwitchAllowed"])

        val devOverride = EditionService(
            Edition.FREE,
            props = { key ->
                when (key) {
                    PACKAGED_APP_PROPERTY -> "/Applications/Indagium"
                    DEV_PROPERTY -> "true"
                    else -> null
                }
            },
        )
        open(devOverride)
        assertEquals("UNLIMITED", assertOk(call("set_edition", "edition" to "UNLIMITED"))["edition"])
    }

    // ── Errors are data ──────────────────────────────────────────────

    @Test
    fun invalidIdsAndMissingArgumentsAreErrorMapsNeverExceptions() {
        val bogus = "no-such-id"
        val byId = mapOf(
            "get_test_suite" to "suiteId", "update_test_suite" to "suiteId", "delete_test_suite" to "suiteId",
            "duplicate_test_suite" to "suiteId", "export_test_suite" to "suiteId",
            "update_test_case" to "caseId", "delete_test_case" to "caseId", "duplicate_test_case" to "caseId",
            "update_test_step" to "stepId", "delete_test_step" to "stepId", "duplicate_test_step" to "stepId",
            "update_test_script" to "scriptId", "delete_test_script" to "scriptId",
            "update_shared_step" to "sharedStepId", "delete_shared_step" to "sharedStepId",
        )
        byId.forEach { (tool, key) -> assertError(call(tool, key to bogus), "not found") }
        mapOf(
            "move_test_suite" to "suiteId", "move_test_case" to "caseId", "move_test_step" to "stepId",
            "move_test_script" to "scriptId", "move_shared_step" to "sharedStepId",
        ).forEach { (tool, key) -> assertError(call(tool, key to bogus, "toIndex" to 0), "not found") }
        assertError(call("create_test_case", "suiteId" to bogus, "name" to "x"), "not found")
        assertError(call("create_test_step", "caseId" to bogus, "action" to "x"), "not found")

        // Missing or mistyped arguments.
        assertError(call("get_test_suite"), "suiteId is required")
        assertError(call("create_test_suite"), "name is required")
        assertError(call("move_test_suite", "suiteId" to "x"), "toIndex is required")
        assertError(call("move_test_suite", "suiteId" to "x", "toIndex" to "front"), "whole number")
        assertError(call("create_test_suite", "name" to 5), "name must be a string")
        assertError(call("create_test_suite", "name" to ""), "required")
        assertError(call("create_test_suite", "name" to "x".repeat(250)), "too long")
        assertEquals(emptyList<Any?>(), call("list_test_suites").list("suites"), "a refused create leaves nothing behind")
        assertError(call("import_test_suite"), "exactly one")
        assertError(call("import_test_suite", "path" to "p", "text" to "t"), "exactly one")
        assertError(call("import_test_suite", "text" to "not json"), "not a test suite")
        assertError(call("import_test_suite", "path" to "relative/file.json"), "absolute")
        assertError(call("export_test_suite", "suiteId" to "x", "path" to "relative.json"), "not found")
    }

    @Test
    fun invalidStepContentIsReportedWithTheOffendingField() {
        val case = caseIn(suiteNamed("S").id(), "C")
        val cid = case.id()

        fun rejectsStep(fragment: String, vararg extra: Pair<String, Any?>) =
            assertError(call("create_test_step", "caseId" to cid, "action" to "a", *extra), fragment)

        fun check(vararg fields: Pair<String, Any?>) = "checks" to listOf(mapOf(*fields))

        fun example(vararg fields: Pair<String, Any?>) = "examples" to listOf(mapOf(*fields))
        assertError(call("create_test_step", "caseId" to cid), "action is required")
        rejectsStep("onFailure must be one of", "onFailure" to "EXPLODE")
        rejectsStep("timeout", "timeoutMs" to 0)
        rejectsStep("retries", "retries" to 99)
        rejectsStep("checks must be an array", "checks" to "oops")
        rejectsStep("checks[0] must be an object", "checks" to listOf("oops"))
        rejectsStep("checks[0].type must be one of", check("type" to "magic"))
        rejectsStep("checks[0].regex is required", check("type" to "logAppears"))
        rejectsStep("regular expression", check("type" to "logAppears", "regex" to "("))
        rejectsStep("withinMs", check("type" to "logAppears", "regex" to "x", "withinMs" to "soon"))
        rejectsStep("text is required", check("type" to "askJudge"))
        rejectsStep("exampleRef", check("type" to "screenJudge", "text" to "t", "exampleRef" to "ex-none"))
        rejectsStep("relative", example("type" to "goldenScreenshot", "assetPath" to "../x.png"))
        rejectsStep("relative", example("type" to "goldenScreenshot", "assetPath" to "/abs.png"))
        rejectsStep("text is required", example("type" to "referenceLog"))
        assertError(call("update_test_case", "caseId" to cid, "steps" to listOf<Any>()), "create_test_step")
        assertError(call("create_test_suite", "name" to "H", "setup" to listOf(mapOf("type" to "script"))), "scriptId is required")
        assertError(call("create_test_suite", "name" to "H", "setup" to listOf(mapOf("type" to "launch"))), "setup[0].type must be one of")
        assertError(call("create_test_suite", "name" to "H", "variables" to listOf(mapOf("name" to "a"), mapOf("name" to "a"))), "unique")
        assertEquals(1, call("list_test_suites").list("suites").size, "failed suite creates leave nothing behind")
        assertEquals(emptyList<Any?>(), getSuite(call("list_test_suites").list("suites")[0].id()).list("cases")[0]["steps"])
    }

    @Test
    fun danglingScriptAndSharedStepReferencesAreWarningsNotErrors() {
        val suite = assertOk(
            call(
                "create_test_suite", "name" to "W",
                "setup" to listOf(mapOf("type" to "script", "scriptId" to "script-gone"), mapOf("type" to "shared", "sharedStepId" to "shared-gone")),
            ),
        )
        val warnings = (suite["warnings"] as List<*>).map { it.toString() }
        assertEquals(2, warnings.size)
        assertTrue(warnings.any { it.contains("script-gone") } && warnings.any { it.contains("shared-gone") })
        val case = caseIn(suite.id(), "C")
        val step = stepIn(case.id(), "A", "checks" to listOf(mapOf("type" to "scriptResult", "scriptId" to "script-gone")))
        assertTrue((step["warnings"] as List<*>).single().toString().contains("script-gone"))
        assertEquals(1, getSuite(suite.id()).list("cases").single().list("steps").size)
    }

    // ── Import / export ──────────────────────────────────────────────

    @Test
    fun exportReturnsTextOrWritesAFileAndImportReadsBothBack() {
        val suite = suiteNamed("Portable", "description" to "Round trip")
        val case = caseIn(suite.id(), "One")
        stepIn(case.id(), "Tap", "checks" to listOf(mapOf("type" to "logAppears", "regex" to "ready")))

        val exported = assertOk(call("export_test_suite", "suiteId" to suite.id()))
        assertEquals("indagium-test-suite", exported["format"])
        assertTrue((exported["text"] as String).contains("\"Portable\""))

        val target = File(dir, "out/portable.json").also { it.parentFile.mkdirs() }
        val written = assertOk(call("export_test_suite", "suiteId" to suite.id(), "path" to target.absolutePath))
        assertEquals(target.absolutePath, written["path"])
        assertTrue(target.isFile && (written["bytes"] as Number).toLong() == target.length())
        assertError(call("export_test_suite", "suiteId" to suite.id(), "path" to target.absolutePath), "overwrite")
        assertOk(call("export_test_suite", "suiteId" to suite.id(), "path" to target.absolutePath, "overwrite" to true))

        val fromFile = assertOk(call("import_test_suite", "path" to target.absolutePath))
        val fromText = assertOk(call("import_test_suite", "text" to exported["text"]))
        listOf(fromFile, fromText).forEach { copy ->
            assertNotEquals(suite.id(), copy.id())
            assertEquals("Portable", copy["name"])
            assertEquals("ready", copy.list("cases").single().list("steps").single().list("checks").single()["regex"])
        }
        assertNotEquals(fromFile.id(), fromText.id())
        assertEquals(3, call("list_test_suites").list("suites").size)
    }

    // ── Catalogue and policy ─────────────────────────────────────────

    @Test
    fun destructiveAndFileToolsNeedConfirmationInsideTheAiPanelAndCreatesDoNot() {
        listOf(
            "delete_test_suite", "delete_test_case", "delete_test_script", "import_test_suite", "export_test_suite", "set_edition",
        ).forEach { assertEquals(IndagiumToolActionPolicy.CONFIRMATION_REQUIRED, operations.toolGateway.actionPolicy(it), it) }
        listOf(
            "list_test_suites", "get_test_suite", "create_test_suite", "update_test_suite", "move_test_suite", "create_test_case",
            "create_test_step", "update_test_step", "delete_test_step", "move_test_step", "create_test_script", "get_edition",
        ).forEach { assertEquals(IndagiumToolActionPolicy.AUTOMATIC, operations.toolGateway.actionPolicy(it), it) }
    }

    @Test
    fun theSchemasDescribeNestedChecksAndEnumsPrecisely() {
        fun schemaText(tool: String) =
            kotlinx.serialization.json.Json.encodeToString(ToolSchema.serializer(), operations.toolGateway.tools.single { it.name == tool }.schema)
        val step = schemaText("create_test_step")
        listOf("logAppears", "screenJudge", "scriptResult", "askJudge", "goldenScreenshot", "referenceLog", "STOP_CASE", "PAUSE_FOR_USER").forEach {
            assertTrue(step.contains("\"$it\""), "create_test_step schema should list $it")
        }
        assertTrue(step.contains("\"required\":[\"caseId\",\"action\"]"))
        val script = schemaText("create_test_script")
        listOf("HOST_SHELL", "ADB_SHELL", "SETUP_TEARDOWN_ONLY", "STRING", "INT", "BOOL").forEach {
            assertTrue(script.contains("\"$it\""), "create_test_script schema should list $it")
        }
        assertTrue(schemaText("create_test_case").contains("\"checks\""), "the nested steps item exposes checks")
        assertTrue(schemaText("create_test_suite").contains("sharedStepId"))
        listOf("targetPackage", "deviceProfileHint", "tags").forEach { assertTrue(schemaText("create_test_suite").contains("\"$it\"")) }
        listOf("preconditions", "setup", "teardown").forEach { assertTrue(schemaText("create_test_case").contains("\"$it\"")) }
        assertTrue(schemaText("create_test_case").contains("sharedStepId"), "case hooks use the hook item schema")
        assertTrue(schemaText("update_test_step").contains("\"maxToolCalls\""))
        assertEquals(48, TEST_SUITE_MCP_TOOLS.size)
        assertFalse(TEST_SUITE_MCP_TOOLS.any { it.name.startsWith("run_") }, "run tools belong to a later phase")
        val names = TEST_SUITE_MCP_TOOLS.map { it.name }.toSet()
        assertEquals(names.size, operations.openAiFunctionDefinitions().count { it.name in names })
    }

    @Test
    fun theStateSurvivesAReopenFromDisk() {
        val suite = suiteNamed("Persisted")
        caseIn(suite.id(), "Kept")
        open(EditionService())
        assertEquals(listOf("Kept"), caseNames(suite.id()))
        assertEquals(1, call("list_test_suites").list("suites").size)
    }
}
