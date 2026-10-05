package com.indagium.testing.tracker

import com.indagium.model.AiProviderKind
import com.indagium.model.AiProviderProfile
import com.indagium.security.SecretKey
import com.indagium.security.SecretResult
import com.indagium.security.SecretStore
import com.indagium.testing.model.IssueDestination
import com.indagium.testing.model.IssueDraft
import com.indagium.testing.model.IssueRecord
import com.indagium.testing.model.IssueSeverity
import com.indagium.testing.model.IssueSource
import com.indagium.testing.model.IssueStatus
import com.indagium.testing.model.TrackerSettings
import com.indagium.testing.run.EngineTuning
import com.indagium.testing.run.LaneAgentFactory
import com.indagium.testing.run.ProviderLaneAgent
import com.indagium.testing.store.StoreResult
import com.indagium.ui.AppState
import com.indagium.ui.IssueActionResult
import com.indagium.ui.TestRunOverrides
import com.indagium.ui.TrackerTestResult
import com.indagium.ui.deliverIssue
import com.indagium.ui.removeTrackerToken
import com.indagium.ui.saveTrackerToken
import com.indagium.ui.settingsJson
import com.indagium.ui.testTrackerConnection
import com.indagium.ui.tokenLine
import kotlinx.coroutines.runBlocking
import java.io.File
import java.util.concurrent.atomic.AtomicInteger
import kotlin.io.path.createTempDirectory
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

private const val TOKEN = "app-level-token-NEVER-PERSIST-5521"
private const val ISSUE_URL = "https://tracker.example.com/browse/ABC-17"
private val agentProfile = AiProviderProfile(
    id = "profile-tracker", displayName = "Tracker agent", baseUrl = "http://127.0.0.1:1234", model = "test-model", kind = AiProviderKind.OPENAI_COMPATIBLE,
)

/** A secret store in a map that counts how often it is asked, so a test can prove nothing touched it. */
private class CountingSecretStore(override val persistent: Boolean = true) : SecretStore {
    val values = HashMap<SecretKey, String>()
    val reads = AtomicInteger()
    val writes = AtomicInteger()
    override val backendName = "Test keychain"
    override val degradedReason: String? get() = if (persistent) null else "the keyring is locked"

    override suspend fun read(key: SecretKey): SecretResult<String?> {
        reads.incrementAndGet()
        return SecretResult.Ok(values[key])
    }

    override suspend fun write(key: SecretKey, value: String): SecretResult<Unit> {
        writes.incrementAndGet()
        values[key] = value
        return SecretResult.Ok(Unit)
    }

    override suspend fun delete(key: SecretKey): SecretResult<Unit> {
        values.remove(key)
        return SecretResult.Ok(Unit)
    }
}

/** Sending a stored issue to the tracker through AppState: the real wiring with a fake keychain, tracker client and model. */
class TrackerSendTest {
    private lateinit var dir: File
    private lateinit var secrets: CountingSecretStore
    private lateinit var state: AppState
    private lateinit var client: FakeTrackerClient
    private var model = ScriptedTrackerModel(emptyList())
    private val connections = java.util.concurrent.CopyOnWriteArrayList<TrackerConnection>()

    @BeforeTest
    fun setUp() {
        dir = createTempDirectory("tracker-send").toFile()
        secrets = CountingSecretStore()
        client = FakeTrackerClient()
        state = AppState(
            autosaveFile = File(dir, "state.cache"),
            autoExportNotes = false,
            notesDir = File(dir, "notes"),
            archiveCacheDir = File(dir, "archive"),
            customCommandsDir = File(dir, "commands"),
            controlTokenFile = File(dir, "token"),
            sourceIndexFile = File(dir, "source-index"),
            testingDir = File(dir, "testing"),
            secretStore = secrets,
        )
        install()
    }

    @AfterTest
    fun tearDown() {
        state.close()
        dir.deleteRecursively()
    }

    private fun install() {
        state.testRunOverrides = TestRunOverrides(
            agentFactory = LaneAgentFactory { chosen, _ -> ProviderLaneAgent(model, chosen) },
            tuning = EngineTuning(agentCancelWaitMs = 2_000L),
            trackerClients = TrackerMcpClientFactory { connection ->
                connections += connection
                client
            },
        )
    }

    private fun configure(tracker: TrackerSettings =
        TrackerSettings(enabled = true, name = "Jira", mcpUrl = "https://tracker.example.com/mcp", prompt = "Bug in ABC", agentProfileId = agentProfile.id)) {
        state.updateSettings { it.copy(aiProviderProfiles = listOf(agentProfile), tracker = tracker) }
    }

    private fun reportingModel() = ScriptedTrackerModel(
        listOf(
            "tracker_create_issue" to args("summary" to "Crash"),
            "report_issue_created" to args("url" to ISSUE_URL, "key" to "ABC-17"),
        ),
    )

    private fun issue(): IssueRecord = (
        state.issueStore.create(
            IssueDraft("Settings crash", IssueSeverity.HIGH, listOf("smoke"), listOf("Open the app"), "Opens", "Crashes"),
            IssueSource("run-1", "lane-1", "suite-1", "case-1", "step-1", caseName = "Login", stepNumber = 2),
        ) as StoreResult.Ok
    ).value

    private fun send(record: IssueRecord, resend: Boolean = false) =
        runBlocking { state.deliverIssue(record.id, IssueDestination.TRACKER, copyMarkdown = false, resendToTracker = resend) }

    // ── Not ready ────────────────────────────────────────────────────

    @Test
    fun withoutATrackerNothingIsSentAndTheSecretStoreIsNeverAsked() {
        val record = issue()

        val failed = assertIs<IssueActionResult.Failed>(send(record))

        assertTrue(failed.message.contains("issue tracker"), failed.message)
        assertEquals(0, secrets.reads.get(), "no keychain read until a tracker is configured")
        assertTrue(state.issueStore.load(record.id)!!.destinationResults.isEmpty())
        assertTrue(connections.isEmpty())
    }

    @Test
    fun aConfiguredTrackerWithoutATokenAsksForOne() {
        configure()
        val record = issue()

        val failed = assertIs<IssueActionResult.Failed>(send(record))

        assertTrue(failed.message.contains("access token"), failed.message)
        assertTrue(connections.isEmpty(), "nothing connected")
    }

    @Test
    fun theProfileMustExist() {
        configure(TrackerSettings(enabled = true, mcpUrl = "https://tracker.example.com/mcp", agentProfileId = "profile-gone"))
        runBlocking { state.saveTrackerToken(TOKEN) }

        val failed = assertIs<IssueActionResult.Failed>(send(issue()))

        assertTrue(failed.message.contains("AI profile"), failed.message)
    }

    // ── Sending ──────────────────────────────────────────────────────

    @Test
    fun theIssueIsFiledReportedAndRecordedAsSentWithTheTrackersUrl() {
        configure()
        assertNull(runBlocking { state.saveTrackerToken(TOKEN) })
        model = reportingModel()
        val record = issue()

        val done = assertIs<IssueActionResult.Done>(send(record))

        assertEquals(ISSUE_URL, done.trackerUrl)
        assertEquals("ABC-17", done.trackerKey)
        assertEquals("Created ABC-17 in Jira", done.message)
        val stored = assertNotNull(state.issueStore.load(record.id))
        assertEquals(IssueStatus.SENT, stored.status)
        val entry = stored.destinationResults.single()
        assertEquals(IssueDestination.TRACKER, entry.destination)
        assertTrue(entry.ok)
        assertEquals(ISSUE_URL, entry.reference)
        assertEquals(listOf("create_issue" to mapOf("summary" to "Crash")), client.calls.toList())
        assertTrue(client.closed)
    }

    @Test
    fun theTokenReachesOnlyTheConnectionAndIsInNoFileSettingOrPrompt() {
        configure()
        runBlocking { state.saveTrackerToken(TOKEN) }
        model = reportingModel()
        val record = issue()

        assertIs<IssueActionResult.Done>(send(record))

        val connection = connections.single()
        assertEquals("Authorization", connection.headerName)
        assertEquals("Bearer $TOKEN", connection.headerValue())
        assertFalse(connection.toString().contains(TOKEN))
        assertFalse(model.everythingSeen().contains(TOKEN), "the model never sees the token")
        assertFalse(state.settings.settingsJson().contains(TOKEN), "AppSettings never holds the token")
        val everyFile = dir.walkTopDown().filter { it.isFile }.toList()
        assertTrue(everyFile.isNotEmpty())
        everyFile.forEach { file -> assertFalse(file.readText(Charsets.ISO_8859_1).contains(TOKEN), "the token is in ${file.path}") }
        assertEquals(TOKEN, secrets.values[SecretKey("tracker.default")], "the secret store is its only home")
    }

    @Test
    fun aTrackerThatNeedsNoAuthenticationSendsWithoutAToken() {
        configure(TrackerSettings(enabled = true, mcpUrl = "http://127.0.0.1:9/mcp", authHeaderName = "", agentProfileId = agentProfile.id))
        model = reportingModel()

        assertIs<IssueActionResult.Done>(send(issue()))

        assertFalse(connections.single().hasAuth)
        assertEquals(0, secrets.reads.get(), "a tracker without authentication never asks the secret store")
    }

    @Test
    fun anIssueAlreadyCreatedIsNotCreatedAgainUnlessAskedTo() {
        configure()
        runBlocking { state.saveTrackerToken(TOKEN) }
        model = reportingModel()
        val record = issue()
        assertIs<IssueActionResult.Done>(send(record))

        val refused = assertIs<IssueActionResult.Failed>(send(record))
        assertTrue(refused.message.contains("already created") && refused.message.contains("ABC-17"), refused.message)
        assertEquals(1, client.calls.size)

        model = reportingModel()
        val again = assertIs<IssueActionResult.Done>(send(record, resend = true))
        assertEquals("ABC-17", again.trackerKey)
        assertEquals(2, state.issueStore.load(record.id)!!.destinationResults.count { it.destination == IssueDestination.TRACKER })
    }

    @Test
    fun aFailedAttemptIsNotedOnTheIssueWithoutChangingItsStatus() {
        configure()
        runBlocking { state.saveTrackerToken(TOKEN) }
        model = ScriptedTrackerModel(listOf("tracker_create_issue" to args("summary" to "x")))
        val record = issue()

        val failed = assertIs<IssueActionResult.Failed>(send(record))

        assertTrue(failed.message.contains("without reporting"), failed.message)
        val stored = state.issueStore.load(record.id)!!
        assertEquals(IssueStatus.DRAFT, stored.status)
        val entry = stored.destinationResults.single()
        assertFalse(entry.ok)
        assertTrue(entry.message.contains("without reporting"))
    }

    @Test
    fun anUnreachableTrackerIsAFailureThatNamesTheProblem() {
        configure()
        runBlocking { state.saveTrackerToken(TOKEN) }
        client = FakeTrackerClient(connectFailure = "Connection refused")
        install()

        val failed = assertIs<IssueActionResult.Failed>(send(issue()))

        assertTrue(failed.message.contains("Connection refused"), failed.message)
    }

    // ── The token store ──────────────────────────────────────────────

    @Test
    fun theTokenIsSavedToTheSecretStoreAndRemovedAgain() = runBlocking<Unit> {
        configure()
        assertNull(state.saveTrackerToken("  $TOKEN  "), "surrounding spaces are trimmed")

        assertEquals(true, state.trackerStatus.tokenPresent)
        assertEquals("Stored in Test keychain", state.trackerStatus.tokenLine())
        assertEquals(TOKEN, secrets.values[SecretKey("tracker.default")])

        assertNull(state.removeTrackerToken())
        assertEquals(false, state.trackerStatus.tokenPresent)
        assertEquals("No token saved.", state.trackerStatus.tokenLine())
        assertTrue(secrets.values.isEmpty())
    }

    @Test
    fun aTokenThatCannotBeStoredIsRefusedWithTheReason() = runBlocking<Unit> {
        val reason = state.saveTrackerToken("bad\ntoken")

        assertTrue(reason!!.contains("printable ASCII"), reason)
        assertEquals(0, secrets.writes.get())
    }

    @Test
    fun aSessionOnlyStoreIsReportedAsSuch() = runBlocking<Unit> {
        secrets = CountingSecretStore(persistent = false)
        state.close()
        state = AppState(
            autosaveFile = File(dir, "state2.cache"), autoExportNotes = false, notesDir = File(dir, "notes2"), archiveCacheDir = File(dir, "archive2"),
            customCommandsDir = File(dir, "commands2"), controlTokenFile = File(dir, "token2"), sourceIndexFile = File(dir, "source-index2"),
            testingDir = File(dir, "testing2"), secretStore = secrets,
        )

        state.saveTrackerToken(TOKEN)

        assertEquals("Kept for this session only: the keyring is locked", state.trackerStatus.tokenLine())
    }

    // ── Test connection ──────────────────────────────────────────────

    @Test
    fun testConnectionListsTheToolsUsingTheTypedTokenOrTheStoredOne() = runBlocking<Unit> {
        client = FakeTrackerClient(tools = listOf(createIssueTool("create_issue"), createIssueTool("search")))
        install()
        val tracker = TrackerSettings(enabled = true, mcpUrl = "https://tracker.example.com/mcp")

        val missing = assertIs<TrackerTestResult.Failed>(state.testTrackerConnection(tracker, ""))
        assertTrue(missing.message.contains("token"), missing.message)

        val typed = assertIs<TrackerTestResult.Connected>(state.testTrackerConnection(tracker, TOKEN))
        assertEquals(listOf("create_issue", "search"), typed.toolNames)
        assertEquals("Bearer $TOKEN", connections.last().headerValue())
        assertEquals(0, secrets.writes.get(), "testing does not save the typed token")

        state.saveTrackerToken("stored-token")
        assertIs<TrackerTestResult.Connected>(state.testTrackerConnection(tracker, ""))
        assertEquals("Bearer stored-token", connections.last().headerValue())
        assertTrue(client.closed)
    }

    @Test
    fun testConnectionRejectsABadUrlWithoutConnecting() = runBlocking<Unit> {
        val failed = assertIs<TrackerTestResult.Failed>(state.testTrackerConnection(TrackerSettings(mcpUrl = "ftp://x/mcp"), TOKEN))

        assertTrue(failed.message.contains("http"), failed.message)
        assertTrue(connections.isEmpty())
    }

    @Test
    fun testConnectionReportsAFailureWithoutTheToken() = runBlocking<Unit> {
        client = FakeTrackerClient(connectFailure = "401 Unauthorized")
        install()

        val failed = assertIs<TrackerTestResult.Failed>(state.testTrackerConnection(TrackerSettings(mcpUrl = "https://tracker.example.com/mcp"), TOKEN))

        assertEquals("401 Unauthorized", failed.message)
    }
}
