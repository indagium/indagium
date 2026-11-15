package com.indagium.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.indagium.edition.Edition
import com.indagium.model.AiProviderProfile
import com.indagium.testing.authoring.MAX_RECORDING_EVIDENCE_BYTES
import com.indagium.testing.authoring.megabytesText
import com.indagium.testing.model.EvidenceFlags
import com.indagium.testing.model.JudgeMode
import com.indagium.testing.model.MAX_CONFIRMATION_TIMEOUT_MINUTES
import com.indagium.testing.model.MAX_TRACKER_PROMPT_CHARS
import com.indagium.testing.model.MIN_CONFIRMATION_TIMEOUT_MINUTES
import com.indagium.testing.model.TestingSettings
import com.indagium.testing.model.TrackerSettings
import com.indagium.testing.store.RecordingEvidenceUsage
import com.indagium.testing.store.StoreResult
import com.indagium.testing.tracker.checkAuthHeaderName
import com.indagium.testing.tracker.checkTrackerUrl
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

// Settings > Test runs (the defaults a new test run starts from, the recorded-case evidence and the three AI test folders) and
// Settings > Issue tracker (the MCP server issues are filed in, how to authenticate, which AI profile files them and the prompt that says
// how). The tracker's access token is typed into a masked field, saved to the SecretStore (OS keychain, or memory for the session when
// that fails) and never shown again or written anywhere else. The keychain and the network are only ever touched off the UI thread
// (AppState.saveTrackerToken and friends, Test connection).

private val FIELD_WIDTH = 300.dp
private val SHORT_FIELD_WIDTH = 240.dp
private val JUDGE_FIELD_WIDTH = 168.dp
private val PROMPT_MIN_HEIGHT = 96.dp
private val PROMPT_MAX_HEIGHT = 240.dp
private const val BYTES_PER_MB = 1024L * 1024L
private const val DEFAULT_PROMPT_HINT = "Project key ABC, issue type Bug, label found-by-indagium; put the steps to reproduce in the description."

/** Test tags of the Test runs and Issue tracker controls. */
internal object TestingPageTags {
    const val JUDGE = "testing-judge"
    const val JUDGE_PROFILE = "testing-judge-profile"
    const val EVIDENCE_SCREENSHOTS = "testing-evidence-screenshots"
    const val EVIDENCE_TRANSCRIPT = "testing-evidence-transcript"
    const val RECORD_SCREEN = "testing-record-screen"
    const val CONFIRM_TIMEOUT = "testing-confirm-timeout"
    const val KEEP_RECORDING_EVIDENCE = "testing-keep-recording-evidence"
    const val DELETE_RECORDING_EVIDENCE = "testing-delete-recording-evidence"

    const val TRACKER_ENABLE = "tracker-enable"
    const val TRACKER_NAME = "tracker-name"
    const val TRACKER_URL = "tracker-url"
    const val TRACKER_HEADER = "tracker-auth-header"
    const val TRACKER_BEARER = "tracker-bearer"
    const val TRACKER_TOKEN = "tracker-token"
    const val TRACKER_SAVE_TOKEN = "tracker-save-token"
    const val TRACKER_REMOVE_TOKEN = "tracker-remove-token"
    const val TRACKER_PROFILE = "tracker-agent-profile"
    const val TRACKER_PROMPT = "tracker-prompt"
    const val TRACKER_TEST = "tracker-test-connection"
}

/** "Free (1 suite, 5 cases per suite)" or "Unlimited": what an edition allows, for Settings > About. */
internal fun editionSummary(edition: Edition): String {
    val limits = edition.limits
    val suites = limits.maxSuites
    val cases = limits.maxCasesPerSuite
    return if (suites == null && cases == null) {
        "${edition.label} (no limits)"
    } else {
        "${edition.label} (${suites ?: "unlimited"} suite${if (suites == 1) "" else "s"}, ${cases ?: "unlimited"} cases per suite)"
    }
}

private fun AiProviderProfile.choiceLabel(): String = "${displayName.ifBlank { kind.label }} · ${kind.label}"

private const val NO_PROFILE_TEXT = "No AI profile configured (Settings > AI > Providers)"

/**
 * The AI-profile picker both pages use: the selected profile (or [placeholder] while none is chosen) in a [SettingsDropdown] of every
 * profile; [onSelect] gets the picked one.
 */
@Composable
private fun ProfileDropdown(
    profiles: List<AiProviderProfile>,
    selectedId: String?,
    placeholder: String,
    onSelect: (AiProviderProfile) -> Unit,
    testTag: String,
    reclaimFocus: () -> Unit,
) {
    SettingsDropdown<AiProviderProfile?>(
        options = profiles,
        selected = profiles.firstOrNull { it.id == selectedId },
        label = { it?.choiceLabel() ?: placeholder },
        onSelect = { profile -> if (profile != null) onSelect(profile) },
        testTag = testTag,
        modifier = Modifier.width(FIELD_WIDTH),
        onInteraction = reclaimFocus,
    )
}

// ── Test runs ────────────────────────────────────────────────────────

private fun updateTesting(state: AppState, change: (TestingSettings) -> TestingSettings) =
    state.updateSettings { it.copy(testing = change(it.testing)) }

private fun updateEvidence(state: AppState, change: (EvidenceFlags) -> EvidenceFlags) =
    updateTesting(state) { it.copy(evidence = change(it.evidence)) }

/** Test runs: what a new run starts from, what a recorded case keeps, then the three AI test folders (kept with the lock hint while a run is active). */
@Composable
internal fun TestRunsSettingsSection(state: AppState, reclaimFocus: () -> Unit) {
    val testing = state.settings.testing
    val profiles = state.settings.aiProviderProfiles
    SettingsGroup("New runs") {
        SettingsRow(
            "Judge",
            "A blind AI judge compares each step's expected result with the evidence. It never sees what the agent claimed.",
            anchor = "Default judge",
        ) {
            SettingsDropdown(
                options = JudgeMode.entries,
                selected = testing.judgeMode,
                label = { it.label },
                onSelect = { mode -> updateTesting(state) { it.copy(defaultJudgeMode = mode.wire) } },
                testTag = TestingPageTags.JUDGE,
                minWidth = JUDGE_FIELD_WIDTH,
                onInteraction = reclaimFocus,
            )
        }
        if (testing.judgeMode != JudgeMode.OFF) {
            SettingsRow(
                "Judge AI profile",
                if (profiles.isEmpty()) NO_PROFILE_TEXT else "The AI profile that judges the steps.",
                indent = true,
            ) {
                ProfileDropdown(
                    profiles = profiles,
                    selectedId = testing.defaultJudgeProfileId,
                    placeholder = "Choose the judge's AI profile",
                    onSelect = { profile -> updateTesting(state) { it.copy(defaultJudgeProfileId = profile.id) } },
                    testTag = TestingPageTags.JUDGE_PROFILE,
                    reclaimFocus = reclaimFocus,
                )
            }
        }
        SettingsStackedRow(
            "Evidence to keep",
            "What a new test run keeps. You can still change this in the run dialog.",
            anchor = "Default evidence to keep",
        ) {
            val evidence = testing.evidence
            Row(horizontalArrangement = Arrangement.spacedBy(20.dp), verticalAlignment = Alignment.CenterVertically) {
                SettingsCheckbox(
                    checked = evidence.screenshots,
                    label = "Screenshots",
                    onChange = { on ->
                        updateEvidence(state) { it.copy(screenshots = on) }
                        reclaimFocus()
                    },
                    testTag = TestingPageTags.EVIDENCE_SCREENSHOTS,
                )
                SettingsCheckbox(
                    checked = evidence.transcript,
                    label = "Agent transcript",
                    onChange = { on ->
                        updateEvidence(state) { it.copy(transcript = on) }
                        reclaimFocus()
                    },
                    testTag = TestingPageTags.EVIDENCE_TRANSCRIPT,
                )
            }
        }
        SettingsRow("Record the screen by default", "A new test run records the device screen as video evidence.") {
            SettingsToggle(
                checked = testing.evidence.video,
                label = "Record the screen by default",
                onChange = { on -> updateEvidence(state) { it.copy(video = on) } },
                reclaimFocus = reclaimFocus,
                testTag = TestingPageTags.RECORD_SCREEN,
            )
        }
        ConfirmationTimeoutRow(state, testing)
    }
    SettingsGroup("Recorded cases") {
        SettingsRow(
            "Keep recording evidence",
            "When on, applying a recording also saves its screenshots, screen readings and video beside the case, so Rewrite with AI can " +
                "be run again on the saved case. A recording in which a password was typed keeps no video and no screens from that time. " +
                "One recording takes at most ${MAX_RECORDING_EVIDENCE_BYTES / BYTES_PER_MB} MB (the video is left out first). " +
                "Suite exports never include it.",
            anchor = "Keep recording evidence with recorded cases",
        ) {
            SettingsToggle(
                checked = testing.keepRecordingEvidence,
                label = "Keep recording evidence",
                onChange = { on -> updateTesting(state) { it.copy(keepRecordingEvidence = on) } },
                reclaimFocus = reclaimFocus,
                testTag = TestingPageTags.KEEP_RECORDING_EVIDENCE,
            )
        }
        StoredRecordingEvidenceRow(state)
    }
    SettingsGroup("Folders") {
        TEST_FOLDER_KINDS.forEach { kind -> SaveFolderSetting(state, kind, asRow = true) }
        state.testStorageStatus?.let { message -> SettingsNoteRow(message, error = true) }
    }
}

@Composable
private fun ConfirmationTimeoutRow(state: AppState, testing: TestingSettings) {
    var text by remember(testing.confirmationTimeoutMinutes) { mutableStateOf(testing.confirmationTimeoutMinutes.toString()) }
    SettingsRow(
        "Confirmation timeout",
        "An action the agent asks to confirm is denied when nobody answers in this time " +
            "($MIN_CONFIRMATION_TIMEOUT_MINUTES to $MAX_CONFIRMATION_TIMEOUT_MINUTES minutes).",
        anchor = "Confirmation timeout (minutes)",
    ) {
        SettingsNumberField(
            value = text,
            onValueChange = { digits ->
                text = digits
                digits.toIntOrNull()?.takeIf { it in MIN_CONFIRMATION_TIMEOUT_MINUTES..MAX_CONFIRMATION_TIMEOUT_MINUTES }
                    ?.let { minutes -> updateTesting(state) { it.copy(confirmationTimeoutMinutes = minutes) } }
            },
            unit = "min",
            maxDigits = 3,
            placeholder = "5",
            testTag = TestingPageTags.CONFIRM_TIMEOUT,
        )
    }
}

/** How much disk the stored recordings of the Test suites folder use, and the button that deletes them (after a confirmation). */
@Composable
private fun StoredRecordingEvidenceRow(state: AppState) {
    val scope = rememberCoroutineScope()
    val folder = state.activeTestFolders().first
    var usage by remember { mutableStateOf<RecordingEvidenceUsage?>(null) }
    var refresh by remember { mutableStateOf(0) }
    var confirming by remember { mutableStateOf(false) }
    var status by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(folder.path, refresh, state.testLibrary) { usage = withContext(Dispatchers.IO) { state.storedRecordingUsage() } }
    val current = usage
    SettingsRow(
        "Stored evidence",
        buildString {
            append(
                when {
                    current == null -> "Measuring…"
                    current.recordings == 0 -> "No recording evidence is stored in ${folder.path}."
                    else -> "${current.recordings} stored recording(s), ${megabytesText(current.bytes)} in ${folder.path}."
                },
            )
            status?.let { append("\n$it") }
        },
        anchor = "Stored recording evidence",
    ) {
        SettingsButton(
            "Delete stored recording evidence",
            onClick = { confirming = true },
            enabled = (current?.recordings ?: 0) > 0,
            variant = ButtonVariant.Secondary,
            modifier = Modifier.testTag(TestingPageTags.DELETE_RECORDING_EVIDENCE),
        )
    }
    if (confirming) {
        SettingsConfirmDialog(
            title = "Delete stored recording evidence?",
            message = "This removes ${current?.recordings ?: 0} stored recording(s) (${megabytesText(current?.bytes ?: 0L)}) from the Test suites " +
                "folder. The recorded steps stay in their cases, but Rewrite with AI can no longer be run from the saved recording. " +
                "This can't be undone.",
            onDismissRequest = { confirming = false },
        ) {
            DialogActionButton("Delete", active = true, danger = true) {
                confirming = false
                scope.launch {
                    val result = withContext(Dispatchers.IO) { state.deleteStoredRecordingEvidence() }
                    status = when (result) {
                        is StoreResult.Ok -> "Deleted ${result.value.recordings} stored recording(s) (${megabytesText(result.value.bytes)})."
                        else -> result.userMessage()
                    }
                    refresh++
                }
            }
            DialogActionButton("Cancel", active = false) { confirming = false }
        }
    }
}

// ── Issue tracker ────────────────────────────────────────────────────

private sealed interface TestUi {
    data object Idle : TestUi

    data object Running : TestUi

    class Done(val message: String, val ok: Boolean) : TestUi
}

private const val MAX_NAME_CHARS = 60

private fun updateTracker(state: AppState, change: (TrackerSettings) -> TrackerSettings) =
    state.updateSettings { it.copy(tracker = change(it.tracker)) }

private fun TrackerTestResult.toUi(): TestUi.Done = when (this) {
    is TrackerTestResult.Connected -> TestUi.Done(
        if (toolNames.isEmpty()) "Connected, but the tracker lists no tools." else "Connected. Tools: ${toolNames.joinToString(", ")}",
        ok = toolNames.isNotEmpty(),
    )
    is TrackerTestResult.Failed -> TestUi.Done(message, ok = false)
}

@Composable
internal fun IssueTrackerSettingsSection(state: AppState, reclaimFocus: () -> Unit) {
    val tracker = state.settings.tracker
    val profiles = state.settings.aiProviderProfiles
    val urlCheck = checkTrackerUrl(tracker.mcpUrl)
    // The token being typed: Save stores it, Test connection may use it before it is saved. It lives only in this composable.
    var tokenText by remember { mutableStateOf("") }
    LaunchedEffect(Unit) { state.refreshTrackerStatus() }
    SettingsGroup("Tracker") {
        SettingsRow("Use an issue tracker", "Turn on sending issues from failed test steps to your own tracker.") {
            SettingsToggle(
                checked = tracker.enabled,
                label = "Use an issue tracker",
                onChange = { on -> updateTracker(state) { it.copy(enabled = on) } },
                reclaimFocus = reclaimFocus,
                testTag = TestingPageTags.TRACKER_ENABLE,
            )
        }
        SettingsRow("Tracker name", "How the tracker is called in the issue dialog.", indent = true) {
            SettingsTextField(
                tracker.name, { value -> updateTracker(state) { it.copy(name = value.take(MAX_NAME_CHARS)) } },
                Modifier.width(SHORT_FIELD_WIDTH), placeholder = "Jira", testTag = TestingPageTags.TRACKER_NAME,
            )
        }
        SettingsRow("Tracker MCP URL", "The address of the tracker's MCP server (http or https).", indent = true) {
            Column(verticalArrangement = Arrangement.spacedBy(4.dp), horizontalAlignment = Alignment.End) {
                SettingsTextField(
                    tracker.mcpUrl, { value -> updateTracker(state) { it.copy(mcpUrl = value.trim()) } },
                    Modifier.width(FIELD_WIDTH), placeholder = "https://tracker.example.com/mcp", testTag = TestingPageTags.TRACKER_URL,
                )
                if (tracker.mcpUrl.isNotBlank()) {
                    (urlCheck.problem ?: urlCheck.warning)?.let {
                        AppText(it, color = DANGER_RED, fontSize = 10.sp, maxLines = 3, modifier = Modifier.width(FIELD_WIDTH))
                    }
                }
            }
        }
    }
    SettingsGroup("Authentication") {
        SettingsRow(
            "Authentication header",
            "The header that carries the access token. Leave the name empty for a server that needs no authentication.",
        ) {
            Column(verticalArrangement = Arrangement.spacedBy(4.dp), horizontalAlignment = Alignment.End) {
                SettingsTextField(
                    tracker.authHeaderName, { value -> updateTracker(state) { it.copy(authHeaderName = value.trim()) } },
                    Modifier.width(SHORT_FIELD_WIDTH), placeholder = "Authorization", testTag = TestingPageTags.TRACKER_HEADER,
                )
                checkAuthHeaderName(tracker.authHeaderName)?.let {
                    AppText(it, color = DANGER_RED, fontSize = 10.sp, modifier = Modifier.width(SHORT_FIELD_WIDTH))
                }
            }
        }
        SettingsRow("Send the token as “Bearer <token>”", "Adds the Bearer prefix in front of the token in that header.", indent = true) {
            SettingsToggle(
                checked = tracker.bearerPrefix,
                label = "Send the token as Bearer",
                onChange = { on -> updateTracker(state) { it.copy(bearerPrefix = on) } },
                reclaimFocus = reclaimFocus,
                testTag = TestingPageTags.TRACKER_BEARER,
            )
        }
        AccessTokenRow(state, tokenText) { tokenText = it }
    }
    SettingsGroup("Filing issues") {
        SettingsRow(
            "Issue agent profile",
            if (profiles.isEmpty()) NO_PROFILE_TEXT else "The AI profile that files the issue by calling the tracker's tools.",
        ) {
            ProfileDropdown(
                profiles = profiles,
                selectedId = tracker.agentProfileId,
                placeholder = "Choose the AI profile that files the issue",
                onSelect = { profile -> updateTracker(state) { it.copy(agentProfileId = profile.id) } },
                testTag = TestingPageTags.TRACKER_PROFILE,
                reclaimFocus = reclaimFocus,
            )
        }
        SettingsStackedRow(
            "Issue instructions",
            "The agent's prompt: tell it how to create an issue, with the project key, issue type, labels and how the issue's fields map " +
                "to the tracker's.",
        ) {
            DictatedTextArea(
                tracker.prompt, { value -> updateTracker(state) { it.copy(prompt = value.take(MAX_TRACKER_PROMPT_CHARS)) } },
                proseTargetId = "settings:issue-tracker:prompt",
                placeholder = DEFAULT_PROMPT_HINT, modifier = Modifier.fillMaxWidth().testTag(TestingPageTags.TRACKER_PROMPT), fontSize = 12.sp,
                minHeight = PROMPT_MIN_HEIGHT, maxHeight = PROMPT_MAX_HEIGHT,
            )
        }
    }
    SettingsGroup("Connection") { TestConnectionRow(state, tracker, tokenText) }
}

@Composable
private fun AccessTokenRow(state: AppState, tokenText: String, onTokenText: (String) -> Unit) {
    val tc = tc()
    val scope = rememberCoroutineScope()
    var tokenMessage by remember { mutableStateOf<String?>(null) }
    SettingsStackedRow(
        "Access token",
        "The token is kept only in your system's secret store (never in Indagium's settings, notes, runs or issue files).",
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                SettingsTextField(
                    tokenText, onTokenText, Modifier.width(SHORT_FIELD_WIDTH), placeholder = "Paste the token",
                    visualTransformation = PasswordVisualTransformation(), testTag = TestingPageTags.TRACKER_TOKEN,
                )
                SettingsButton(
                    "Save token", enabled = tokenText.isNotBlank(),
                    onClick = {
                        val typed = tokenText
                        scope.launch {
                            tokenMessage = state.saveTrackerToken(typed)
                            if (tokenMessage == null) onTokenText("")
                        }
                    },
                    modifier = Modifier.testTag(TestingPageTags.TRACKER_SAVE_TOKEN),
                )
                SettingsButton(
                    "Remove token", enabled = state.trackerStatus.tokenPresent == true, variant = ButtonVariant.Secondary,
                    onClick = { scope.launch { tokenMessage = state.removeTrackerToken() } },
                    modifier = Modifier.testTag(TestingPageTags.TRACKER_REMOVE_TOKEN),
                )
            }
            if (state.trackerStatus.tokenPresent == false) {
                SettingsEmptyState(state.trackerStatus.tokenLine())
            } else {
                AppText(state.trackerStatus.tokenLine(), color = tc.ts, fontSize = 11.sp, maxLines = 3)
            }
            tokenMessage?.let { AppText(it, color = DANGER_RED, fontSize = 11.sp, maxLines = 3) }
        }
    }
}

@Composable
private fun TestConnectionRow(state: AppState, tracker: TrackerSettings, typedToken: String) {
    val scope = rememberCoroutineScope()
    var test by remember { mutableStateOf<TestUi>(TestUi.Idle) }
    val ready = checkTrackerUrl(tracker.mcpUrl).valid && checkAuthHeaderName(tracker.authHeaderName) == null
    // The button sits on the right like every action row; the outcome of the last test stays under it.
    Column(Modifier.fillMaxWidth()) {
        SettingsRow("Test connection", "Connect to the tracker and list the tools it offers.") {
            SettingsButton(
                if (test == TestUi.Running) "Connecting…" else "Test connection",
                enabled = test != TestUi.Running && ready,
                onClick = {
                    test = TestUi.Running
                    scope.launch { test = state.testTrackerConnection(state.settings.tracker, typedToken).toUi() }
                },
                modifier = Modifier.testTag(TestingPageTags.TRACKER_TEST),
            )
        }
        (test as? TestUi.Done)?.let {
            AppText(
                it.message,
                color = if (it.ok) tc().ts else DANGER_RED,
                fontSize = 11.sp,
                maxLines = 6,
                modifier = Modifier.fillMaxWidth().padding(start = 12.dp, end = 12.dp, bottom = 9.dp),
            )
        }
    }
}
