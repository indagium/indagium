package com.indagium.debug

import com.indagium.testing.ISSUE_LANE_ID
import com.indagium.testing.IssueRunFixture
import com.indagium.testing.installRun
import com.indagium.testing.issueRunFixture
import com.indagium.testing.model.OnFailure
import com.indagium.testing.store.decodeRunFile
import com.indagium.ui.AppState
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import java.io.File
import kotlin.io.path.createTempDirectory
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

private const val AWAIT_MS = 15_000L
private const val POLL_MS = 20L

/** create_issue_from_step, list_issues, get_issue, update_issue and delete_issue over the gateway. */
class IssueToolsGatewayTest {
    private lateinit var dir: File
    private lateinit var state: AppState
    private lateinit var operations: IndagiumToolOperations
    private lateinit var fixture: IssueRunFixture

    @BeforeTest
    fun setUp() {
        dir = createTempDirectory("issue-tools").toFile()
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
        fixture = issueRunFixture(File(dir, "fixture"), onFailure = OnFailure.STOP_CASE)
        installRun(File(dir, "testing"), fixture)
    }

    @AfterTest
    fun tearDown() {
        state.close()
        dir.deleteRecursively()
    }

    @Suppress("UNCHECKED_CAST")
    private fun callWith(tool: String, args: Map<String, Any?>): Map<String, Any?> = runBlocking {
        val wire = Json.decode(Json.encode(args)) as Map<String, Any?>
        Json.decode(Json.encode(operations.toolGateway.executeSuspending(tool, wire))) as Map<String, Any?>
    }

    private fun call(tool: String, vararg args: Pair<String, Any?>): Map<String, Any?> = callWith(tool, mapOf(*args))

    private fun create(destination: String? = null, vararg extra: Pair<String, Any?>): Map<String, Any?> {
        val base = mapOf<String, Any?>(
            "runId" to fixture.run.id, "laneId" to ISSUE_LANE_ID, "caseId" to fixture.caseId, "stepId" to fixture.failingStep.stepId,
        )
        val withDestination = if (destination == null) base else base + ("destination" to destination)
        return callWith("create_issue_from_step", withDestination + extra)
    }

    private fun storedRun() = decodeRunFile(File(dir, "testing/runs/${fixture.run.id}/run.json").readText()).getOrThrow()

    @Suppress("UNCHECKED_CAST")
    private fun issueOf(answer: Map<String, Any?>): Map<String, Any?> = answer["issue"] as Map<String, Any?>

    // ── create_issue_from_step ──────────────────────────────────────

    @Test
    fun aLocalIssueIsBuiltFromTheStepStoredWithItsEvidenceAndNotedOnTheStepsResult() {
        val created = create()

        assertEquals(null, created["error"], created.toString())
        val issueId = created["issueId"] as String
        assertEquals("SAVED", created["status"])
        val got = call("get_issue", "issueId" to issueId)
        val issue = issueOf(got)
        assertEquals(issueId, issue["id"])
        assertEquals("SAVED", issue["status"])
        @Suppress("UNCHECKED_CAST")
        val draft = issue["draft"] as Map<String, Any?>
        assertTrue((draft["title"] as String).startsWith("Settings · step 2: Tap Settings in the toolbar"), draft.toString())
        assertEquals("CRITICAL", draft["severity"], "app defect with a crash in the step's log")
        @Suppress("UNCHECKED_CAST")
        val attachments = draft["attachments"] as List<Map<String, Any?>>
        assertTrue(attachments.any { it["kind"] == "SCREENSHOT" && (it["storedPath"] as String).startsWith("attachments/") }, attachments.toString())
        val folder = File(issue["folder"] as String)
        assertTrue(File(folder, "issue.json").isFile)
        assertEquals(issueId, storedRun().lanes.single().cases.single().steps[1].issueId, "the report finds the issue again")
        assertTrue(File(folder, "attachments").list()!!.isNotEmpty(), "the evidence was copied next to issue.json")
    }

    @Test
    fun askingAgainForTheSameStepReusesTheIssueInsteadOfDuplicatingIt() {
        val first = create()
        val second = create(extra = arrayOf("overrides" to mapOf("title" to "Better title")))

        assertEquals(first["issueId"], second["issueId"])
        val issues = call("list_issues")["issues"] as List<*>
        assertEquals(1, issues.size)
        assertEquals("Better title", (issueOf(call("get_issue", "issueId" to second["issueId"]))["draft"] as Map<*, *>)["title"])
    }

    @Test
    fun overridesReplaceTheDraftFieldsBeforeItIsStored() {
        val created = create(
            "markdown",
            "overrides" to mapOf(
                "title" to "Settings title missing", "severity" to "low", "labels" to listOf("ui", "ui", "regression"),
                "stepsToReproduce" to listOf("Open Settings"), "expected" to "A title", "actual" to "No title", "judgeNotes" to "Looks wrong",
                "linkToCase" to true,
            ),
        )

        assertEquals(null, created["error"], created.toString())
        val markdown = created["markdown"] as String
        assertTrue(markdown.startsWith("# Settings title missing\n"), markdown)
        assertTrue(markdown.contains("**Severity:** Low · **Labels:** ui, regression"), markdown)
        assertTrue(markdown.contains("1. Open Settings"), markdown)
        assertTrue(markdown.contains("## Judge notes\n\nLooks wrong"), markdown)
        assertEquals("SENT", created["status"])
        val record = issueOf(call("get_issue", "issueId" to created["issueId"]))
        assertEquals(true, record["linkToCase"])
        val screenshotPath = markdown.lines().first { it.startsWith("- Screenshot:") }
        val listed = File(screenshotPath.substringAfter('`').substringBefore('`'))
        assertTrue(listed.isFile, "the evidence is listed by an absolute path that exists: $screenshotPath")
    }

    @Test
    fun theTrackerDestinationIsRefusedAndCreatesNothing() {
        val refused = create("tracker")

        assertTrue(refused["error"].toString().contains("issue tracker"), refused.toString())
        assertEquals(emptyList<Any?>(), call("list_issues")["issues"])
    }

    @Test
    fun theNotesDestinationAsksForALogTabThenOpensTheLaneLogOnRequest() {
        val offer = create("notes")

        assertEquals(true, offer["needsLogTab"], offer.toString())
        assertTrue((offer["logFile"] as String).endsWith("logs/logcat.log"))
        assertTrue(state.tabs.isEmpty())
        val issueId = offer["issueId"] as String
        assertEquals("DRAFT", (call("list_issues")["issues"] as List<*>).map { (it as Map<*, *>)["status"] }.single())

        val done = create("notes", "openLaneLog" to true)

        val tabId = assertNotNull(done["tabId"] as? String, done.toString())
        runBlocking { withTimeout(AWAIT_MS) { while (state.isLoadInFlight(tabId)) delay(POLL_MS) } }
        assertEquals(issueId, done["issueId"], "the same issue is used")
        assertEquals("SENT", done["status"])
        assertEquals(2, checkNotNull(state.tab(tabId)).annotations.blocks.size, "a note and the screenshot")
    }

    @Test
    fun badInputIsReportedAsDataAndANonStepCreatesNothing() {
        assertTrue(create("pigeon")["error"].toString().contains("destination must be one of"))
        assertTrue(create(extra = arrayOf("overrides" to mapOf("severity" to "SEVERE")))["error"].toString().contains("severity must be one of"))
        val unknownRun = call("create_issue_from_step", "runId" to "run-nope", "laneId" to "l", "caseId" to "c", "stepId" to "s")
        assertTrue(unknownRun["error"].toString().contains("not found"))
        assertTrue(
            call("create_issue_from_step", "runId" to fixture.run.id, "laneId" to ISSUE_LANE_ID, "caseId" to fixture.caseId, "stepId" to "step-x")["error"]
                .toString().contains("has no result"),
        )
        assertTrue(call("create_issue_from_step", "runId" to fixture.run.id)["error"].toString().contains("laneId is required"))
        assertEquals(emptyList<Any?>(), call("list_issues")["issues"])
    }

    // ── list / get / update / delete ────────────────────────────────

    @Test
    fun listIssuesFiltersByRunAndStatusAndLimits() {
        val saved = create()["issueId"] as String
        val sent = create("markdown")["issueId"] as String
        assertEquals(saved, sent, "the same step has one issue")

        val all = call("list_issues")["issues"] as List<*>
        assertEquals(1, all.size)
        val row = all.single() as Map<*, *>
        assertEquals(saved, row["issueId"])
        assertEquals("SENT", row["status"])
        assertEquals("Settings", row["caseName"])
        assertNull(row["recheck"])
        assertEquals(1, (call("list_issues", "runId" to fixture.run.id)["issues"] as List<*>).size)
        assertEquals(0, (call("list_issues", "runId" to "run-other")["issues"] as List<*>).size)
        assertEquals(1, (call("list_issues", "status" to "SENT")["issues"] as List<*>).size)
        assertEquals(0, (call("list_issues", "status" to "DRAFT")["issues"] as List<*>).size)
        assertTrue(call("list_issues", "status" to "NOPE")["error"].toString().contains("status must be one of"))
    }

    @Test
    fun getIssueAnswersJsonOrMarkdownAndUnknownIdsAreErrors() {
        val issueId = create()["issueId"] as String

        val markdown = call("get_issue", "issueId" to issueId, "format" to "markdown")
        assertEquals("markdown", markdown["format"])
        assertTrue((markdown["markdown"] as String).contains("## Steps to reproduce"))
        assertTrue(call("get_issue", "issueId" to issueId, "format" to "xml")["error"].toString().contains("format must be"))
        assertTrue(call("get_issue", "issueId" to "issue-nope")["error"].toString().contains("not found"))
    }

    @Test
    fun updateIssueChangesOnlyTheNamedFields() {
        val issueId = create()["issueId"] as String
        val before = issueOf(call("get_issue", "issueId" to issueId))["draft"] as Map<*, *>

        val updated = call("update_issue", "issueId" to issueId, "title" to "Renamed", "severity" to "MEDIUM", "labels" to listOf("a"), "linkToCase" to true)

        assertEquals(null, updated["error"], updated.toString())
        val draft = issueOf(updated)["draft"] as Map<*, *>
        assertEquals("Renamed", draft["title"])
        assertEquals("MEDIUM", draft["severity"])
        assertEquals(listOf("a"), draft["labels"])
        assertEquals(before["expected"], draft["expected"], "an unnamed field stays")
        assertEquals(before["stepsToReproduce"], draft["stepsToReproduce"])
        assertEquals(true, issueOf(updated)["linkToCase"])
        assertTrue(call("update_issue", "issueId" to "issue-nope", "title" to "x")["error"].toString().contains("not found"))
        assertTrue(call("update_issue", "issueId" to issueId, "severity" to "SEVERE")["error"].toString().contains("severity must be one of"))
    }

    @Test
    fun deleteIssueRemovesItsFolderIsConfirmationGatedAndIsAnErrorTheSecondTime() {
        val issueId = create()["issueId"] as String
        val folder = state.issueStore.issueDir(issueId)
        assertTrue(folder.isDirectory)
        assertEquals(IndagiumToolActionPolicy.CONFIRMATION_REQUIRED, operations.toolGateway.actionPolicy("delete_issue"))
        assertEquals(IndagiumToolActionPolicy.AUTOMATIC, operations.toolGateway.actionPolicy("create_issue_from_step"))
        assertEquals(IndagiumToolActionPolicy.AUTOMATIC, operations.toolGateway.actionPolicy("list_issues"))

        val deleted = call("delete_issue", "issueId" to issueId)

        assertEquals(true, deleted["deleted"], deleted.toString())
        assertFalse(folder.exists())
        assertEquals(emptyList<Any?>(), call("list_issues")["issues"])
        assertTrue(call("delete_issue", "issueId" to issueId)["error"].toString().contains("not found"))
    }
}
