package com.indagium.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupProperties
import com.indagium.edition.Edition
import com.indagium.model.AiProviderProfile
import com.indagium.testing.model.EvidenceFlags
import com.indagium.testing.model.JudgeMode
import com.indagium.testing.model.MAX_CONFIRMATION_TIMEOUT_MINUTES
import com.indagium.testing.model.MAX_TRACKER_PROMPT_CHARS
import com.indagium.testing.model.MIN_CONFIRMATION_TIMEOUT_MINUTES
import com.indagium.testing.model.TestingSettings
import com.indagium.testing.model.TrackerSettings
import com.indagium.testing.tracker.checkAuthHeaderName
import com.indagium.testing.tracker.checkTrackerUrl
import kotlinx.coroutines.launch

// Settings > Testing (the defaults a new test run starts from, the edition) and Settings > Issue tracker (the MCP server issues
// are filed in, how to authenticate, which AI profile files them and the prompt that says how). The tracker's access token is
// typed into a masked field, saved to the SecretStore (OS keychain, or memory for the session when that fails) and never shown
// again or written anywhere else. The keychain and the network are only ever touched off the UI thread (AppState.saveTrackerToken
// and friends, Test connection).

private val FIELD_WIDTH = 360.dp
private val SHORT_FIELD_WIDTH = 240.dp
private val NUMBER_FIELD_WIDTH = 72.dp
private val MENU_WIDTH = 320.dp
private val PROMPT_MIN_HEIGHT = 96.dp
private val PROMPT_MAX_HEIGHT = 240.dp
private const val DEFAULT_PROMPT_HINT = "Project key ABC, issue type Bug, label found-by-indagium; put the steps to reproduce in the description."

/** "Free (1 suite, 5 cases per suite)" or "Unlimited": what an edition allows, for Settings > Testing. */
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

@Composable
private fun SectionHeading(title: String, text: String) {
    val tc = tc()
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        AppText(title, color = tc.tx, fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
        AppText(text, color = tc.td, fontSize = 11.sp, maxLines = 4)
    }
}

/** A "label ▾" field that opens a list of [options]; modeled on EditorChoiceDropdown, with no dependency on the Tests workspace. */
@Composable
private fun <T> SettingsChoiceDropdown(
    selectedLabel: String,
    options: List<T>,
    optionLabel: (T) -> String,
    isSelected: (T) -> Boolean,
    onSelect: (T) -> Unit,
    emptyText: String,
    modifier: Modifier = Modifier,
) {
    val tc = tc()
    val density = LocalDensity.current
    var open by remember { mutableStateOf(false) }
    var fieldWidth by remember { mutableStateOf(MENU_WIDTH) }
    // See EditorChoiceDropdown: dismissing the popup and the field's own click can both fire for one press.
    var suppressToggleUntilMs by remember { mutableStateOf(0L) }
    Box(modifier.onGloballyPositioned { fieldWidth = with(density) { it.size.width.toDp() } }) {
        HoverBox(
            modifier = Modifier.fillMaxWidth().height(26.dp).clip(CORNER_SM).background(tc.p2, CORNER_SM).border(1.dp, tc.br, CORNER_SM),
            onClick = { if (System.currentTimeMillis() >= suppressToggleUntilMs) open = !open },
        ) {
            Row(
                Modifier.fillMaxWidth().padding(horizontal = 10.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                AppText(selectedLabel, color = tc.tx, fontSize = 11.sp, fontWeight = FontWeight.Medium, modifier = Modifier.weight(1f, fill = false))
                AppText(if (open) "▲" else "▼", color = tc.td, fontSize = 9.sp)
            }
        }
        if (open) {
            Popup(
                alignment = Alignment.TopStart,
                offset = IntOffset(0, with(density) { 30.dp.roundToPx() }),
                onDismissRequest = {
                    open = false
                    suppressToggleUntilMs = System.currentTimeMillis() + 200
                },
                properties = PopupProperties(focusable = false),
            ) {
                Column(
                    Modifier.width(fieldWidth).heightIn(max = 320.dp).verticalScroll(rememberScrollState())
                        .shadow(8.dp, RoundedCornerShape(8.dp)).background(tc.p, RoundedCornerShape(8.dp))
                        .border(1.dp, tc.br, RoundedCornerShape(8.dp)).padding(4.dp),
                    verticalArrangement = Arrangement.spacedBy(2.dp),
                ) {
                    if (options.isEmpty()) AppText(emptyText, color = tc.td, fontSize = 11.sp, modifier = Modifier.padding(8.dp), maxLines = 3)
                    options.forEach { option ->
                        val active = isSelected(option)
                        HoverBox(
                            modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(5.dp)),
                            baseBg = if (active) tc.abg else Color.Transparent,
                            onClick = {
                                open = false
                                onSelect(option)
                            },
                        ) {
                            Row(Modifier.fillMaxWidth().padding(horizontal = 10.dp, vertical = 6.dp)) {
                                AppText(
                                    optionLabel(option), color = if (active) tc.ac else tc.tx, fontSize = 11.sp,
                                    fontWeight = if (active) FontWeight.SemiBold else FontWeight.Normal,
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

private fun AiProviderProfile.choiceLabel(): String = "${displayName.ifBlank { kind.label }} · ${kind.label}"

@Composable
private fun OnOff(enabled: Boolean, onChange: (Boolean) -> Unit) {
    SegmentedControl(options = listOf("On", "Off"), selectedIndices = setOf(if (enabled) 0 else 1), onToggle = { onChange(it == 0) })
}

// ── Testing ──────────────────────────────────────────────────────────

private fun updateTesting(state: AppState, change: (TestingSettings) -> TestingSettings) =
    state.updateSettings { it.copy(testing = change(it.testing)) }

private fun updateEvidence(state: AppState, change: (EvidenceFlags) -> EvidenceFlags) =
    updateTesting(state) { it.copy(evidence = change(it.evidence)) }

@Composable
internal fun TestingSettingsSection(state: AppState) {
    val tc = tc()
    val testing = state.settings.testing
    val edition by state.editionService.current.collectAsState()
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        SectionHeading("Testing", "What a new AI test run starts from. You can still change every part in the run dialog.")
        CompactSetting("Default judge") { DefaultJudge(state, testing) }
        CompactSetting("Default evidence to keep") {
            Column {
                val evidence = testing.evidence
                EvidenceToggle("Screenshots", evidence.screenshots) { on -> updateEvidence(state) { it.copy(screenshots = on) } }
                EvidenceToggle("Agent transcript", evidence.transcript) { on -> updateEvidence(state) { it.copy(transcript = on) } }
                EvidenceToggle("Record the screen by default", evidence.video) { on -> updateEvidence(state) { it.copy(video = on) } }
            }
        }
        CompactSetting("Confirmation timeout (minutes)") { ConfirmationTimeout(state, testing) }
        CompactSetting("Edition") {
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                AppText("Current edition: ${editionSummary(edition)}", color = tc.tx, fontSize = 12.sp)
                if (state.editionService.devSwitchAllowed()) {
                    AppText("Development switch (this run only):", color = tc.td, fontSize = 10.sp)
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        Edition.entries.forEach { candidate ->
                            AppButton(
                                candidate.label,
                                onClick = { state.editionService.setForDev(candidate) },
                                variant = if (candidate == edition) ButtonVariant.Primary else ButtonVariant.Secondary,
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun DefaultJudge(state: AppState, testing: TestingSettings) {
    val profiles = state.settings.aiProviderProfiles
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        SegmentedControl(
            options = JudgeMode.entries.map { it.label },
            selectedIndices = setOf(JudgeMode.entries.indexOf(testing.judgeMode)),
            onToggle = { index -> updateTesting(state) { it.copy(defaultJudgeMode = JudgeMode.entries[index].wire) } },
        )
        if (testing.judgeMode != JudgeMode.OFF) {
            SettingsChoiceDropdown(
                selectedLabel = profiles.firstOrNull { it.id == testing.defaultJudgeProfileId }?.choiceLabel() ?: "Choose the judge's AI profile",
                options = profiles,
                optionLabel = { it.choiceLabel() },
                isSelected = { it.id == testing.defaultJudgeProfileId },
                onSelect = { profile -> updateTesting(state) { it.copy(defaultJudgeProfileId = profile.id) } },
                emptyText = "No AI profile configured (Settings > AI providers)",
                modifier = Modifier.width(FIELD_WIDTH),
            )
        }
        AppText(
            "A blind AI judge compares each step's expected result with the evidence. It never sees what the agent claimed.",
            color = tc().td, fontSize = 10.sp, maxLines = 2,
        )
    }
}

@Composable
private fun ConfirmationTimeout(state: AppState, testing: TestingSettings) {
    var text by remember(testing.confirmationTimeoutMinutes) { mutableStateOf(testing.confirmationTimeoutMinutes.toString()) }
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
        InlineField(
            text,
            { value ->
                val digits = value.filter { it.isDigit() }.take(3)
                text = digits
                digits.toIntOrNull()?.takeIf { it in MIN_CONFIRMATION_TIMEOUT_MINUTES..MAX_CONFIRMATION_TIMEOUT_MINUTES }
                    ?.let { minutes -> updateTesting(state) { it.copy(confirmationTimeoutMinutes = minutes) } }
            },
            "5", Modifier.width(NUMBER_FIELD_WIDTH), fontSize = 12.sp,
        )
        AppText(
            "An action the agent asks to confirm is denied when nobody answers in this time " +
                "($MIN_CONFIRMATION_TIMEOUT_MINUTES to $MAX_CONFIRMATION_TIMEOUT_MINUTES).",
            color = tc().td, fontSize = 10.sp, maxLines = 2, modifier = Modifier.weight(1f),
        )
    }
}

@Composable
private fun EvidenceToggle(label: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    CheckRow(checked, { onChange(!checked) }) { AppText(label, color = tc().tx, fontSize = 12.sp) }
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
internal fun IssueTrackerSettingsSection(state: AppState) {
    val tracker = state.settings.tracker
    val profiles = state.settings.aiProviderProfiles
    val urlCheck = checkTrackerUrl(tracker.mcpUrl)
    // The token being typed: Save stores it, Test connection may use it before it is saved. It lives only in this composable.
    var tokenText by remember { mutableStateOf("") }
    LaunchedEffect(Unit) { state.refreshTrackerStatus() }
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        SectionHeading(
            "Issue tracker",
            "Send issues from failed test steps to your own tracker. A tracker is an MCP server; an AI agent files each issue by " +
                "calling its tools as your prompt describes. The issue text and the evidence the agent uploads are visible to that " +
                "AI profile and are sent to the tracker.",
        )
        CompactSetting("Use an issue tracker") { OnOff(tracker.enabled) { on -> updateTracker(state) { it.copy(enabled = on) } } }
        CompactSetting("Tracker name") {
            InlineField(
                tracker.name, { value -> updateTracker(state) { it.copy(name = value.take(MAX_NAME_CHARS)) } },
                "Jira", Modifier.width(SHORT_FIELD_WIDTH), fontSize = 12.sp,
            )
        }
        CompactSetting("Tracker MCP URL") {
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                InlineField(
                    tracker.mcpUrl, { value -> updateTracker(state) { it.copy(mcpUrl = value.trim()) } },
                    "https://tracker.example.com/mcp", Modifier.width(FIELD_WIDTH), fontSize = 12.sp,
                )
                if (tracker.mcpUrl.isNotBlank()) (urlCheck.problem ?: urlCheck.warning)?.let { AppText(it, color = DANGER_RED, fontSize = 10.sp, maxLines = 3) }
            }
        }
        CompactSetting("Authentication header") { AuthenticationHeader(state, tracker) }
        CompactSetting("Access token") { AccessToken(state, tokenText) { tokenText = it } }
        CompactSetting("Issue agent profile") {
            SettingsChoiceDropdown(
                selectedLabel = profiles.firstOrNull { it.id == tracker.agentProfileId }?.choiceLabel() ?: "Choose the AI profile that files the issue",
                options = profiles,
                optionLabel = { it.choiceLabel() },
                isSelected = { it.id == tracker.agentProfileId },
                onSelect = { profile -> updateTracker(state) { it.copy(agentProfileId = profile.id) } },
                emptyText = "No AI profile configured (Settings > AI providers)",
                modifier = Modifier.width(FIELD_WIDTH),
            )
        }
        CompactSetting("Issue instructions (prompt)") { IssuePrompt(state, tracker) }
        CompactSetting("Test connection") { TestConnection(state, tracker, tokenText) }
    }
}

@Composable
private fun AuthenticationHeader(state: AppState, tracker: TrackerSettings) {
    val tc = tc()
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        InlineField(
            tracker.authHeaderName, { value -> updateTracker(state) { it.copy(authHeaderName = value.trim()) } },
            "Authorization", Modifier.width(SHORT_FIELD_WIDTH), fontSize = 12.sp,
        )
        CheckRow(tracker.bearerPrefix, { updateTracker(state) { it.copy(bearerPrefix = !it.bearerPrefix) } }) {
            AppText("Send the token as “Bearer <token>”", color = tc.tx, fontSize = 12.sp)
        }
        checkAuthHeaderName(tracker.authHeaderName)?.let { AppText(it, color = DANGER_RED, fontSize = 10.sp) }
        AppText("Leave the header name empty for a server that needs no authentication.", color = tc.td, fontSize = 10.sp)
    }
}

@Composable
private fun AccessToken(state: AppState, tokenText: String, onTokenText: (String) -> Unit) {
    val tc = tc()
    val scope = rememberCoroutineScope()
    var tokenMessage by remember { mutableStateOf<String?>(null) }
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            InlineField(
                tokenText, onTokenText, "Paste the token", Modifier.width(SHORT_FIELD_WIDTH), fontSize = 12.sp,
                visualTransformation = PasswordVisualTransformation(),
            )
            AppButton(
                "Save token", enabled = tokenText.isNotBlank(),
                onClick = {
                    val typed = tokenText
                    scope.launch {
                        tokenMessage = state.saveTrackerToken(typed)
                        if (tokenMessage == null) onTokenText("")
                    }
                },
            )
            AppButton(
                "Remove token", enabled = state.trackerStatus.tokenPresent == true, variant = ButtonVariant.Secondary,
                onClick = { scope.launch { tokenMessage = state.removeTrackerToken() } },
            )
        }
        AppText(state.trackerStatus.tokenLine(), color = tc.td, fontSize = 10.sp, maxLines = 3)
        tokenMessage?.let { AppText(it, color = DANGER_RED, fontSize = 10.sp, maxLines = 3) }
        AppText(
            "The token is kept only in your system's secret store (never in Indagium's settings, notes, runs or issue files).",
            color = tc.td, fontSize = 10.sp, maxLines = 2,
        )
    }
}

@Composable
private fun IssuePrompt(state: AppState, tracker: TrackerSettings) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        ScrollableTextArea(
            tracker.prompt, { value -> updateTracker(state) { it.copy(prompt = value.take(MAX_TRACKER_PROMPT_CHARS)) } },
            placeholder = DEFAULT_PROMPT_HINT, modifier = Modifier.width(FIELD_WIDTH * 1.5f), fontSize = 12.sp,
            minHeight = PROMPT_MIN_HEIGHT, maxHeight = PROMPT_MAX_HEIGHT,
        )
        AppText(
            "Tell the agent how to create an issue: the project key, issue type, labels and how the issue's fields map to the tracker's.",
            color = tc().td, fontSize = 10.sp, maxLines = 2,
        )
    }
}

@Composable
private fun TestConnection(state: AppState, tracker: TrackerSettings, typedToken: String) {
    val scope = rememberCoroutineScope()
    var test by remember { mutableStateOf<TestUi>(TestUi.Idle) }
    val ready = checkTrackerUrl(tracker.mcpUrl).valid && checkAuthHeaderName(tracker.authHeaderName) == null
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        AppButton(
            if (test == TestUi.Running) "Connecting…" else "Test connection",
            enabled = test != TestUi.Running && ready,
            onClick = {
                test = TestUi.Running
                scope.launch { test = state.testTrackerConnection(state.settings.tracker, typedToken).toUi() }
            },
        )
        (test as? TestUi.Done)?.let { AppText(it.message, color = if (it.ok) tc().ts else DANGER_RED, fontSize = 11.sp, maxLines = 6) }
    }
}
