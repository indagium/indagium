package com.indagium.debug

import com.indagium.ai.AiRun
import com.indagium.ai.AiRunEvent
import com.indagium.ai.AiToolExecutionCoordinator
import com.indagium.model.AiProviderKind
import com.indagium.model.AiProviderProfile
import com.indagium.security.SecretKey
import com.indagium.security.SecretResult
import com.indagium.security.SecretStore
import com.indagium.testing.ISSUE_LANE_ID
import com.indagium.testing.IssueRunFixture
import com.indagium.testing.installRun
import com.indagium.testing.issueRunFixture
import com.indagium.testing.model.IssueAttachment
import com.indagium.testing.model.IssueAttachmentKind
import com.indagium.testing.model.IssueDraft
import com.indagium.testing.model.IssueSeverity
import com.indagium.testing.model.IssueSource
import com.indagium.testing.model.OnFailure
import com.indagium.testing.model.TrackerSettings
import com.indagium.testing.run.EngineTuning
import com.indagium.testing.run.LaneAgentFactory
import com.indagium.testing.run.ProviderLaneAgent
import com.indagium.testing.store.StoreResult
import com.indagium.testing.tracker.FakeTrackerClient
import com.indagium.testing.tracker.ScriptedTrackerModel
import com.indagium.testing.tracker.TrackerMcpClientFactory
import com.indagium.testing.tracker.args
import com.indagium.ui.AppState
import com.indagium.ui.TestRunOverrides
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import java.io.File
import java.util.concurrent.ConcurrentHashMap
import kotlin.io.path.createTempDirectory
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

private const val TOKEN = "approval-test-token-8842"
private const val ISSUE_URL = "https://tracker.example.com/browse/ABC-17"
private const val WAIT_MS = 5_000L
private val agentProfile = AiProviderProfile(
    id = "profile-tracker", displayName = "Tracker agent", baseUrl = "http://127.0.0.1:1234", model = "test-model", kind = AiProviderKind.OPENAI_COMPATIBLE,
)

private class MapSecrets : SecretStore {
    val values = ConcurrentHashMap<SecretKey, String>()
    override val backendName = "Test keychain"
    override val persistent = true
    override val degradedReason: String? = null

    override suspend fun read(key: SecretKey): SecretResult<String?> = SecretResult.Ok(values[key])

    override suspend fun write(key: SecretKey, value: String): SecretResult<Unit> = SecretResult.Ok(Unit).also { values[key] = value }

    override suspend fun delete(key: SecretKey): SecretResult<Unit> = SecretResult.Ok(Unit).also { values.remove(key) }
}

/**
 * Sending an issue to the tracker over MCP: send_issue_to_tracker and create_issue_from_step with destination tracker. An external
 * client needs the user's approval for every such call (the dialog names the tracker, the agent, the issue and its evidence); the
 * AI panel shows a confirmation card; every other destination needs neither.
 */
class TrackerToolsTest {
    private lateinit var dir: File
    private lateinit var state: AppState
    private lateinit var operations: IndagiumToolOperations
    private lateinit var fixture: IssueRunFixture
    private var client = FakeTrackerClient()
    private var model = ScriptedTrackerModel(emptyList())

    @BeforeTest
    fun setUp() {
        dir = createTempDirectory("tracker-tools").toFile()
        state = AppState(
            autosaveFile = File(dir, "state.cache"), autoExportNotes = false, notesDir = File(dir, "notes"), archiveCacheDir = File(dir, "archive"),
            customCommandsDir = File(dir, "commands"), controlTokenFile = File(dir, "token"), sourceIndexFile = File(dir, "source-index"),
            testingDir = File(dir, "testing"), secretStore = MapSecrets(),
        )
        state.testRunOverrides = TestRunOverrides(
            agentFactory = LaneAgentFactory { chosen, _ -> ProviderLaneAgent(model, chosen) },
            tuning = EngineTuning(agentCancelWaitMs = 2_000L),
            trackerClients = TrackerMcpClientFactory { client },
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

    private fun configureTracker() {
        state.updateSettings {
            it.copy(
                aiProviderProfiles = listOf(agentProfile),
                tracker = TrackerSettings(
                    enabled = true, name = "Jira", mcpUrl = "https://tracker.example.com/mcp", prompt = "Bug in ABC",
                    agentProfileId = agentProfile.id,
                ),
            )
        }
        runBlocking { state.secretStore.write(SecretKey("tracker.default"), TOKEN) }
    }

    private fun reportingModel() = ScriptedTrackerModel(
        listOf("tracker_create_issue" to args("summary" to "x"), "report_issue_created" to args("url" to ISSUE_URL, "key" to "ABC-17")),
    )

    @Suppress("UNCHECKED_CAST")
    private fun call(tool: String, args: Map<String, Any?>): Map<String, Any?> = runBlocking {
        val wire = Json.decode(Json.encode(args)) as Map<String, Any?>
        Json.decode(Json.encode(operations.toolGateway.executeSuspending(tool, wire))) as Map<String, Any?>
    }

    private fun stepArgs(destination: String? = null): Map<String, Any?> {
        val base = mapOf<String, Any?>("runId" to fixture.run.id, "laneId" to ISSUE_LANE_ID, "caseId" to fixture.caseId, "stepId" to fixture.failingStep.stepId)
        return if (destination == null) base else base + ("destination" to destination)
    }

    private fun storedIssueId(): String = (
        state.issueStore.create(
            IssueDraft("Settings crash", IssueSeverity.HIGH, listOf("smoke"), listOf("Open"), "Opens", "Crashes"),
            IssueSource("run-1", "lane-1", "suite-1", "case-1", "step-1", caseName = "Login", stepNumber = 2),
        ) as StoreResult.Ok
    ).value.id

    private fun describe(tool: String, args: Map<String, Any?>) = runBlocking { describePerCallApproval(state, tool, args, "Test client") }

    // ── The tools ────────────────────────────────────────────────────

    @Test
    fun sendIssueToTrackerFilesTheIssueAndAnswersWithItsAddress() {
        configureTracker()
        model = reportingModel()
        val issueId = storedIssueId()

        val answer = call("send_issue_to_tracker", mapOf("issueId" to issueId))

        assertNull(answer["error"], answer.toString())
        assertEquals(ISSUE_URL, answer["trackerUrl"])
        assertEquals("ABC-17", answer["trackerKey"])
        assertEquals("SENT", answer["status"])
        val again = call("send_issue_to_tracker", mapOf("issueId" to issueId))
        assertTrue(again["error"].toString().contains("already created"), again.toString())
        model = reportingModel()
        assertEquals("ABC-17", call("send_issue_to_tracker", mapOf("issueId" to issueId, "resend" to true))["trackerKey"])
    }

    @Test
    fun createIssueFromStepWithTheTrackerDestinationSendsTheStepsIssue() {
        configureTracker()
        model = reportingModel()

        val answer = call("create_issue_from_step", stepArgs("tracker"))

        assertNull(answer["error"], answer.toString())
        assertEquals(ISSUE_URL, answer["trackerUrl"])
        assertEquals("SENT", answer["status"])
        assertEquals(1, (call("list_issues", emptyMap())["issues"] as List<*>).size)
    }

    @Test
    fun withoutATrackerBothToolsRefuseAndCreateNothing() {
        val issueId = storedIssueId()

        assertTrue(call("send_issue_to_tracker", mapOf("issueId" to issueId))["error"].toString().contains("issue tracker"))
        assertTrue(call("create_issue_from_step", stepArgs("tracker"))["error"].toString().contains("issue tracker"))
        assertEquals(1, (call("list_issues", emptyMap())["issues"] as List<*>).size, "only the issue stored by the test")
    }

    @Test
    fun anUnknownIssueIsReportedAsNotFound() {
        configureTracker()

        assertTrue(call("send_issue_to_tracker", mapOf("issueId" to "issue-nope"))["error"].toString().contains("not found"))
    }

    // ── Approval of an external client ───────────────────────────────

    @Test
    fun bothToolsNeedPerCallApprovalFromAnExternalClient() {
        assertTrue("send_issue_to_tracker" in PER_CALL_APPROVAL_MCP_TOOLS)
        assertTrue("create_issue_from_step" in PER_CALL_APPROVAL_MCP_TOOLS)
    }

    @Test
    fun theApprovalDialogNamesTheTrackerTheAgentTheIssueAndItsEvidence() {
        configureTracker()
        val issueId = storedIssueId()
        state.issueStore.update(issueId) { record ->
            val file = File(dir, "evidence.log").apply { writeText("log lines") }
            record.copy(
                draft = record.draft.copy(
                    attachments = listOf(
                        IssueAttachment(IssueAttachmentKind.LOG_RANGE, "Log of the step", "log.txt", 9, sourcePath = file.absolutePath),
                    ),
                ),
            )
        }

        val details = assertNotNull(describe("send_issue_to_tracker", mapOf("issueId" to issueId)))

        assertEquals("Send an issue to the issue tracker?", details.title)
        assertTrue(details.summary.startsWith("Test client wants to send an issue to the issue tracker"), details.summary)
        val fields = details.fields.toMap()
        assertEquals("Jira (https://tracker.example.com/mcp)", fields["Tracker"])
        assertEquals("Tracker agent · OpenAI-compatible", fields["AI profile that files it"])
        assertEquals("Settings crash (severity HIGH)", fields["Issue"])
        assertTrue(fields["Evidence the agent can read"]!!.contains("Log of the step"), fields.toString())
        assertEquals("Send to tracker", details.allowLabel)
        assertTrue(details.declinedMessage.contains("nothing was sent"))
        assertFalse(details.fields.joinToString { it.second }.contains(TOKEN))
    }

    @Test
    fun theDialogForAStepShowsTheDraftThatWouldBeSent() {
        configureTracker()

        val details = assertNotNull(describe("create_issue_from_step", stepArgs("tracker") + mapOf("overrides" to mapOf("title" to "Custom title"))))

        val fields = details.fields.toMap()
        assertTrue(fields["Issue"]!!.startsWith("Custom title (severity"), fields.toString())
        assertTrue(fields["Evidence the agent can read"]!!.contains("Screenshot"), fields.toString())
        assertEquals(0, (call("list_issues", emptyMap())["issues"] as List<*>).size, "describing creates nothing")
    }

    @Test
    fun noApprovalIsAskedForOtherDestinationsOrForACallTheToolWouldRefuse() {
        // Another destination: no approval, even with a tracker configured.
        configureTracker()
        assertNull(describe("create_issue_from_step", stepArgs("markdown")))
        assertNull(describe("create_issue_from_step", stepArgs()))
        assertNull(describe("create_issue_from_step", stepArgs("TRACKER") + ("runId" to "run-nope")), "an unknown run is refused by the tool itself")
        assertNull(describe("send_issue_to_tracker", mapOf("issueId" to "issue-nope")))
        assertNull(describe("send_issue_to_tracker", emptyMap()))
        assertNotNull(describe("create_issue_from_step", stepArgs("TRACKER")), "the destination is read case-insensitively, like the tool does")

        // No tracker set up: the tool refuses and nothing leaves, so the user is not asked.
        state.updateSettings { it.copy(tracker = TrackerSettings()) }
        assertNull(describe("send_issue_to_tracker", mapOf("issueId" to storedIssueId())))
        assertNull(describe("create_issue_from_step", stepArgs("tracker")))
    }

    // ── Confirmation inside the AI panel ─────────────────────────────

    @Test
    fun theGatewayRaisesTheTrackerCallsToConfirmationRequiredButNotTheOtherDestinations() {
        val gateway = operations.toolGateway

        assertEquals(IndagiumToolActionPolicy.CONFIRMATION_REQUIRED, gateway.actionPolicy("send_issue_to_tracker"))
        assertEquals(IndagiumToolActionPolicy.AUTOMATIC, gateway.actionPolicy("create_issue_from_step"))
        assertEquals(IndagiumToolActionPolicy.CONFIRMATION_REQUIRED, gateway.actionPolicy("create_issue_from_step", mapOf("destination" to "tracker")))
        assertEquals(IndagiumToolActionPolicy.CONFIRMATION_REQUIRED, gateway.actionPolicy("create_issue_from_step", mapOf("destination" to " Tracker ")))
        assertEquals(IndagiumToolActionPolicy.AUTOMATIC, gateway.actionPolicy("create_issue_from_step", mapOf("destination" to "local")))
        assertEquals(IndagiumToolActionPolicy.AUTOMATIC, gateway.actionPolicy("list_issues", mapOf("destination" to "tracker")))
    }

    @Test
    fun inTheAiPanelATrackerSendWaitsForTheUsersAnswerAndOtherDestinationsDoNot() = runBlocking<Unit> {
        configureTracker()
        model = reportingModel()
        val issueId = storedIssueId()
        val coordinator = AiToolExecutionCoordinator(operations.toolGateway)

        val local = coordinator.executeManaged(AiRun(tabId = "tab"), "create_issue_from_step", stepArgs("local"))
        assertTrue(local.content.contains("issueId"), "a local issue needs no card: ${local.content}")

        val run = AiRun(tabId = "tab")
        val declined = async { coordinator.executeManaged(run, "send_issue_to_tracker", mapOf("issueId" to issueId)) }
        withTimeout(WAIT_MS) { while (run.confirmations.isEmpty()) delay(5) }
        val card = (run.history.first { it is AiRunEvent.ConfirmationRequired } as AiRunEvent.ConfirmationRequired).confirmation
        assertTrue(card.description.contains("issue tracker"), card.description)
        run.confirmations.values.single().complete(false)
        assertTrue(declined.await().content.contains("declined"))
        assertTrue(client.calls.isEmpty(), "declined: nothing was sent")

        val allowed = AiRun(tabId = "tab")
        val sent = async { coordinator.executeManaged(allowed, "create_issue_from_step", stepArgs("tracker")) }
        withTimeout(WAIT_MS) { while (allowed.confirmations.isEmpty()) delay(5) }
        allowed.confirmations.values.single().complete(true)
        assertTrue(sent.await().content.contains(ISSUE_URL))
    }
}
