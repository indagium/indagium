@file:OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)

package com.indagium.ui

import androidx.compose.foundation.TooltipArea
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.ChevronRight
import androidx.compose.material.icons.outlined.Code
import androidx.compose.material.icons.outlined.ContentCopy
import androidx.compose.material.icons.outlined.FavoriteBorder
import androidx.compose.material.icons.outlined.Gavel
import androidx.compose.material.icons.outlined.OpenInNew
import androidx.compose.material.icons.outlined.Visibility
import androidx.compose.material.icons.outlined.VisibilityOff
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.indagium.edition.Edition
import com.indagium.generated.BuildInfo
import kotlinx.coroutines.delay
import java.io.File

// Settings › Storage & files, Integrations and About, plus the save-folder row the Test runs page shares with Storage. Storage holds the
// save folders and what Indagium keeps on disk, Integrations the local MCP control server and diagnostic logging, About the version,
// edition and project links.

// The row descriptions the search index shares (SettingsSearchIndex.kt), so a result reads exactly like the row it points at.
internal const val SAVE_ROOT_HINT = "Parent folder every other save location defaults under. Defaults to your Documents folder."
internal const val SAVE_ANALYSIS_HINT = "Where analysis notes, filtered exports and split logs are saved."
internal const val SAVE_SESSIONS_HINT = "Where new device captures are recorded; applies from the next Start."
internal const val SAVE_SNAPSHOTS_HINT = "Destination for “Save snapshot” while a capture is recording."
internal const val SAVE_ZIP_HINT = "Where “Save ZIP” writes a stopped or retained capture."
internal const val SAVE_TEST_SUITES_HINT = "Where your AI test suites are kept: library, suites and golden screenshots."
internal const val SAVE_TEST_RUNS_HINT = "Where each AI test run keeps its evidence: run files, recordings, screenshots and transcripts."
internal const val SAVE_TEST_ISSUES_HINT = "Where the issues made from failed test steps are kept, with their evidence."
internal const val STORAGE_AUTO_SAVE_HINT = "Saves note Markdown and its .ann sidecar after note changes."
internal const val STORAGE_FILTER_BACKUPS_HINT = "Writes timestamped saved-filter backups after saved-filter changes."
internal const val STORAGE_TEMP_HINT = "Downloaded archive cache and app-managed notes."
internal const val STORAGE_APP_DATA_HINT = "Everything Indagium stores: settings, session, saved filters, indexes and diagnostics."
internal const val STORAGE_RESET_HINT = "Deletes everything in app data, after you confirm. Indagium then exits and starts again with its defaults."
internal const val MCP_SERVER_HINT = "Local server that lets AI clients drive Indagium over MCP. It listens on this computer only and is off by default."
internal const val MCP_PORT_HINT = "Loopback port the server listens on. Changing it restarts a running server."
internal const val MCP_CORS_HINT = "Allows CORS, so a browser-based MCP inspector can call the local server. Leave it off unless you use one."
internal const val MCP_CONNECTION_HINT =
    "Point any MCP client (LM Studio, Claude Code, Codex) at the URL, or copy a ready-made config. The token authorises every request."
internal const val DEBUG_LOGGING_HINT = "Writes Indagium's own diagnostic log to a file. Attach it when you report a bug."
internal const val DEBUG_LOG_FILE_HINT = "Where the diagnostic log is written."

private const val NOT_SET = "Not set"
private const val RUNNING_POLL_MS = 250L
private const val RUNNING_POLL_TRIES = 20
private const val TOKEN_MASK = "••••••••••••••••••••••••"
private val ABOUT_CARD_SHAPE = RoundedCornerShape(8.dp)

internal const val TEST_RUN_LOCK_HINT = "Unavailable while a test run is in progress. Change it after the run has finished."

/** The three AI test folders: Settings › Test runs shows them, Storage & files shows every other save folder. */
internal val TEST_FOLDER_KINDS = listOf(SaveFolderKind.TEST_SUITES, SaveFolderKind.TEST_RUNS, SaveFolderKind.TEST_ISSUES)

/** Test tags of the Storage & files, Integrations and About controls. */
internal object SystemPageTags {
    const val AUTO_SAVE = "storage-auto-save"
    const val FILTER_BACKUPS = "storage-filter-backups"
    const val CLEAR_TEMP = "storage-clear-temp"
    const val RESET_APP_DATA = "storage-reset-app-data"
    const val TEMP_SIZE = "storage-temp-size"
    const val APP_SIZE = "storage-app-size"

    const val MCP_SWITCH = "integrations-mcp-switch"
    const val MCP_RUNNING = "integrations-mcp-running"
    const val MCP_PORT = "integrations-mcp-port"
    const val MCP_CORS = "integrations-mcp-cors"
    const val MCP_COPY_URL = "integrations-mcp-copy-url"
    const val MCP_TOKEN_TOGGLE = "integrations-mcp-token-toggle"
    const val MCP_COPY_TOKEN = "integrations-mcp-copy-token"
    const val MCP_COPY_CLAUDE = "integrations-mcp-copy-claude"
    const val MCP_COPY_CODEX = "integrations-mcp-copy-codex"
    const val MCP_INFO = "integrations-mcp-info"
    const val DEBUG_SWITCH = "integrations-debug-switch"
    const val DEBUG_BROWSE = "integrations-debug-browse"
    const val DEBUG_OPEN = "integrations-debug-open"

    const val ABOUT_CHECK_UPDATES = "about-check-updates"
    const val ABOUT_UPDATE_STATUS = "about-update-status"
    const val ABOUT_GITHUB = "about-github"
    const val ABOUT_SUPPORT = "about-support"
    const val ABOUT_LICENSE = "about-license"
    const val EDITION_SWITCH = "about-edition-switch"
}

// ── Save folders ──────────────────────────────────────────────────────

/**
 * The row for one of the save folders, wired exactly as Settings has always shown it; the setup assistant reuses it for the folders it
 * offers. [asRow] draws it as a Settings page row (label, hint and path, Browse and Reset) instead of the assistant's stacked form.
 */
@Composable
internal fun SaveFolderSetting(state: AppState, kind: SaveFolderKind, asRow: Boolean = false) {
    val locked = kind.affectsTestStorage && state.testRunInProgress
    when (kind) {
        SaveFolderKind.ROOT -> SaveFolderRow(
            label = "Default save folder",
            tooltip = SAVE_ROOT_HINT,
            detail = "Parent folder every other save location defaults under when it isn't set on its own. Defaults to your Documents folder.",
            explicitValue = state.settings.saveRootDir,
            effectivePath = state.effectiveSaveRootDir.absolutePath,
            onBrowse = { state.pickSaveFolder(SaveFolderKind.ROOT) },
            onReset = { state.resetSaveFolder(SaveFolderKind.ROOT) },
            enabled = !locked,
            disabledHint = TEST_RUN_LOCK_HINT,
            asRow = asRow,
        )
        SaveFolderKind.ANALYSIS -> SaveFolderRow(
            label = "Analysis artifacts folder",
            tooltip = SAVE_ANALYSIS_HINT,
            detail = "$SAVE_ANALYSIS_HINT Auto-saved notes are written here, created on first save.",
            explicitValue = state.settings.defaultSaveDir,
            effectivePath = state.effectiveAnalysisDirForDisplay().absolutePath,
            onBrowse = { state.pickSaveFolder(SaveFolderKind.ANALYSIS) },
            onReset = { state.resetSaveFolder(SaveFolderKind.ANALYSIS) },
            asRow = asRow,
        )
        SaveFolderKind.SESSIONS -> SaveFolderRow(
            label = "Capture sessions folder",
            tooltip = SAVE_SESSIONS_HINT,
            detail = "Where new device captures are recorded. A change applies from the next Start; sessions already recorded stay exactly " +
                "where they are.",
            explicitValue = state.settings.captureSessionsDir,
            effectivePath = state.effectiveCaptureSessionsDir().absolutePath,
            onBrowse = { state.pickSaveFolder(SaveFolderKind.SESSIONS) },
            onReset = { state.resetSaveFolder(SaveFolderKind.SESSIONS) },
            asRow = asRow,
        )
        SaveFolderKind.SNAPSHOTS -> SaveFolderRow(
            label = "Snapshots folder",
            tooltip = SAVE_SNAPSHOTS_HINT,
            explicitValue = state.settings.captureSnapshotsDir,
            effectivePath = state.effectiveCaptureSnapshotsDir().absolutePath,
            onBrowse = { state.pickSaveFolder(SaveFolderKind.SNAPSHOTS) },
            onReset = { state.resetSaveFolder(SaveFolderKind.SNAPSHOTS) },
            asRow = asRow,
        )
        SaveFolderKind.ZIP -> SaveFolderRow(
            label = "Saved captures folder",
            tooltip = SAVE_ZIP_HINT,
            explicitValue = state.settings.captureZipDir,
            effectivePath = state.effectiveCaptureZipDirForDisplay().absolutePath,
            onBrowse = { state.pickSaveFolder(SaveFolderKind.ZIP) },
            onReset = { state.resetSaveFolder(SaveFolderKind.ZIP) },
            asRow = asRow,
        )
        SaveFolderKind.TEST_SUITES -> SaveFolderRow(
            label = "Test suites folder",
            tooltip = SAVE_TEST_SUITES_HINT,
            detail = "Where your AI test suites are kept (library.json, suites/ and golden-screenshot assets/). " +
                "Changing it loads the suites found there; the old folder is left as it is.",
            explicitValue = state.settings.testSuitesDir,
            effectivePath = state.effectiveTestSuitesDirForDisplay().absolutePath,
            onBrowse = { state.pickSaveFolder(SaveFolderKind.TEST_SUITES) },
            onReset = { state.resetSaveFolder(SaveFolderKind.TEST_SUITES) },
            enabled = !locked,
            disabledHint = TEST_RUN_LOCK_HINT,
            asRow = asRow,
        )
        SaveFolderKind.TEST_RUNS -> SaveFolderRow(
            label = "Test runs folder",
            tooltip = SAVE_TEST_RUNS_HINT,
            detail = "Where each AI test run keeps its evidence (run.json, lane recordings, screenshots, transcripts). " +
                "Changing it lists the runs found there; the old folder is left as it is.",
            explicitValue = state.settings.testRunsDir,
            effectivePath = state.effectiveTestRunsDirForDisplay().absolutePath,
            onBrowse = { state.pickSaveFolder(SaveFolderKind.TEST_RUNS) },
            onReset = { state.resetSaveFolder(SaveFolderKind.TEST_RUNS) },
            enabled = !locked,
            disabledHint = TEST_RUN_LOCK_HINT,
            asRow = asRow,
        )
        SaveFolderKind.TEST_ISSUES -> SaveFolderRow(
            label = "Issues folder",
            tooltip = SAVE_TEST_ISSUES_HINT,
            detail = "Where the issues made from failed test steps are kept, one folder each with its evidence. " +
                "Changing it lists the issues found there; the old folder is left as it is.",
            explicitValue = state.settings.testIssuesDir,
            effectivePath = state.effectiveTestIssuesDirForDisplay().absolutePath,
            onBrowse = { state.pickSaveFolder(SaveFolderKind.TEST_ISSUES) },
            onReset = { state.resetSaveFolder(SaveFolderKind.TEST_ISSUES) },
            enabled = !locked,
            disabledHint = TEST_RUN_LOCK_HINT,
            asRow = asRow,
        )
    }
}

/** The small bordered box a hover tooltip of these pages is drawn in. */
@Composable
private fun SettingsTooltipBox(content: @Composable () -> Unit) {
    val tc = tc()
    Box(
        Modifier
            .background(tc.p2, RoundedCornerShape(4.dp))
            .border(0.5.dp, tc.br, RoundedCornerShape(4.dp))
            .padding(horizontal = 8.dp, vertical = 4.dp),
    ) { content() }
}

/**
 * One row of a save-folders group: the label with its hint ([tooltip], at most two lines; [detail] is the longer text for the hover hint),
 * the effective path (one line, cut in the middle, with a "· default" while nothing is explicitly set, [explicitValue] null), a Browse
 * button, and a Reset button that only shows once the folder has been set to something other than its computed default. [enabled] = false
 * greys Browse and Reset out; [disabledHint] is the tooltip that says why. With [asRow] the label and hint sit on top of the path and its
 * buttons in a Settings page row, otherwise in the setup assistant's stacked form.
 */
@Composable
internal fun SaveFolderRow(
    label: String,
    tooltip: String,
    explicitValue: String?,
    effectivePath: String,
    onBrowse: () -> Unit,
    onReset: () -> Unit,
    enabled: Boolean = true,
    disabledHint: String? = null,
    asRow: Boolean = false,
    detail: String? = null,
) {
    val tc = tc()
    val buttons: @Composable () -> Unit = {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            SettingsButton("Browse", onClick = onBrowse, enabled = enabled)
            if (explicitValue != null) SettingsButton("Reset", onClick = onReset, enabled = enabled)
        }
    }
    val buttonsWithHint: @Composable () -> Unit = {
        if (!enabled && disabledHint != null) {
            TooltipArea(tooltip = { SettingsTooltipBox { AppText(disabledHint, color = tc.tx, fontSize = 11.sp, maxLines = 3) } }) { buttons() }
        } else {
            buttons()
        }
    }
    val pathText: @Composable (Modifier) -> Unit = { modifier ->
        TooltipArea(
            tooltip = { SettingsTooltipBox { AppText(effectivePath, color = tc.tx, fontSize = 11.sp, fontFamily = MONO) } },
            modifier = modifier,
        ) {
            SettingsPathText(effectivePath, isDefault = explicitValue == null)
        }
    }
    if (asRow) {
        // The path needs the width, so the label and hint are on top and the path with its buttons under them.
        SettingsStackedRow(label, tooltip, detail = detail) {
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
                pathText(Modifier.weight(1f))
                buttonsWithHint()
            }
        }
        return
    }
    Column(Modifier.settingsAnchor(label), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        TooltipArea(tooltip = { SettingsTooltipBox { AppText(detail ?: tooltip, color = tc.tx, fontSize = 11.sp, maxLines = 3) } }) {
            AppText(label, color = tc.td, fontSize = 10.sp, fontFamily = UI, fontWeight = FontWeight.SemiBold)
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            pathText(Modifier.weight(1f))
            buttonsWithHint()
        }
    }
}

// ── Storage & files ───────────────────────────────────────────────────

@Composable
internal fun StorageSettingsSection(state: AppState, reclaimFocus: () -> Unit) {
    SettingsGroup("Folders") {
        SaveFolderKind.entries.filter { it !in TEST_FOLDER_KINDS }.forEach { kind -> SaveFolderSetting(state, kind, asRow = true) }
        state.testStorageStatus?.let { message -> SettingsNoteRow(message, error = true) }
    }
    SettingsGroup("Saving") {
        SettingsRow("Auto-save", STORAGE_AUTO_SAVE_HINT) {
            SettingsToggle(
                checked = state.settings.autoExportNotes,
                label = "Auto-save",
                onChange = { next -> state.updateSettings { it.copy(autoExportNotes = next) } },
                reclaimFocus = reclaimFocus,
                testTag = SystemPageTags.AUTO_SAVE,
            )
        }
        SettingsRow("Filter backups", STORAGE_FILTER_BACKUPS_HINT) {
            SettingsToggle(
                checked = state.settings.autoSaveFilters,
                label = "Filter backups",
                onChange = { next -> state.updateSettings { it.copy(autoSaveFilters = next) } },
                reclaimFocus = reclaimFocus,
                testTag = SystemPageTags.FILTER_BACKUPS,
            )
        }
    }
    DiskUseGroup(state)
    state.autosaveError?.let { message -> SettingsGroup("") { SettingsNoteRow(message, error = true) } }
}

@Composable
private fun DiskUseGroup(state: AppState) {
    val tc = tc()
    val appDataPath = state.appCachePath
    // The group anchor is the "Storage" search entry (where Indagium keeps its data); the rows keep their own.
    SettingsGroup("Disk use", modifier = Modifier.settingsAnchor("Storage")) {
        SettingsRow("Temporary data", STORAGE_TEMP_HINT) {
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
                AppText(
                    formatByteSize(state.temporaryDataSizeBytes),
                    color = tc.ts,
                    fontSize = 11.sp,
                    fontFamily = MONO,
                    modifier = Modifier.testTag(SystemPageTags.TEMP_SIZE),
                )
                SettingsButton(
                    "Clear temporary data…",
                    onClick = { state.requestClearCache() },
                    variant = ButtonVariant.Secondary,
                    modifier = Modifier.testTag(SystemPageTags.CLEAR_TEMP),
                )
            }
        }
        TooltipArea(tooltip = { SettingsTooltipBox { AppText(appDataPath, color = tc.tx, fontSize = 11.sp, fontFamily = MONO) } }) {
            SettingsRow(
                "App data",
                STORAGE_APP_DATA_HINT,
                detail = "Everything Indagium stores in $appDataPath: temporary data, settings, current session/autosave, saved filters, " +
                    "source and case indexes, diagnostics, and integration data.",
            ) {
                AppText(
                    formatByteSize(state.appDataSizeBytes),
                    color = tc.ts,
                    fontSize = 11.sp,
                    fontFamily = MONO,
                    modifier = Modifier.testTag(SystemPageTags.APP_SIZE),
                )
            }
        }
        SettingsRow("Return to defaults", STORAGE_RESET_HINT) {
            SettingsButton(
                "Reset app data…",
                onClick = { state.requestResetAppData() },
                variant = ButtonVariant.Secondary,
                isDanger = true,
                modifier = Modifier.testTag(SystemPageTags.RESET_APP_DATA),
            )
        }
    }
}

// ── Integrations ──────────────────────────────────────────────────────

private enum class McpCopied { Url, Token, Claude, Codex }

@Composable
internal fun IntegrationsSettingsSection(state: AppState, reclaimFocus: () -> Unit) {
    McpServerGroup(state, reclaimFocus)
    DiagnosticsGroup(state, reclaimFocus)
}

@Composable
private fun McpServerGroup(state: AppState, reclaimFocus: () -> Unit) {
    val enabled = state.settings.mcpControlEnabled
    val port = state.settings.mcpControlPort
    val error = state.mcpControlError
    // The server starts and stops on a background thread and its handle is not Compose state, so after a switch (or a port change) the
    // badge asks for a little while until it agrees with the switch, and gives up when the start failed.
    var running by remember { mutableStateOf(state.controlServerToken() != null) }
    LaunchedEffect(enabled, port, error) {
        repeat(RUNNING_POLL_TRIES) {
            running = state.controlServerToken() != null
            if (running == enabled || error != null) return@LaunchedEffect
            delay(RUNNING_POLL_MS)
        }
        running = state.controlServerToken() != null
    }
    SettingsGroup("Control server") {
        SettingsRow("MCP control server", MCP_SERVER_HINT) {
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
                if (running) SettingsBadge("Running", dot = true, modifier = Modifier.testTag(SystemPageTags.MCP_RUNNING))
                SettingsToggle(
                    checked = enabled,
                    label = "MCP control server",
                    onChange = { next -> state.setMcpControlEnabled(next, port) },
                    reclaimFocus = reclaimFocus,
                    testTag = SystemPageTags.MCP_SWITCH,
                )
            }
        }
        error?.let { message -> SettingsNoteRow(message, error = true) }
        // Not indented, dimmed or locked: the port, the connection details and CORS stay editable while the server is off, for when it is turned on.
        SettingsRow("Port", MCP_PORT_HINT) {
            var portText by remember(port) { mutableStateOf(port.toString()) }
            SettingsNumberField(
                value = portText,
                onValueChange = { digits ->
                    portText = digits
                    digits.toIntOrNull()?.coerceIn(MIN_PORT, MAX_PORT)?.let { p ->
                        if (state.settings.mcpControlEnabled) state.setMcpControlEnabled(true, p)
                        else state.updateSettings { it.copy(mcpControlPort = p) }
                    }
                },
                maxDigits = PORT_DIGITS,
                placeholder = "8991",
                testTag = SystemPageTags.MCP_PORT,
            )
        }
        McpConnectionRow(state, port)
        // (SEC-1) Off by default: CORS lets any origin a browser has open issue cross-origin requests to this loopback server.
        // Bearer-token auth still gates every request either way; this only controls whether a browser is additionally allowed to
        // do that at all. Opt-in for the uncommon case of a browser-based MCP inspector.
        SettingsRow("Allow browser-based MCP clients", MCP_CORS_HINT) {
            SettingsToggle(
                checked = state.settings.mcpAllowBrowserClients,
                label = "Allow browser-based MCP clients",
                onChange = { next -> state.setMcpAllowBrowserClients(next) },
                reclaimFocus = reclaimFocus,
                testTag = SystemPageTags.MCP_CORS,
            )
        }
    }
}

/** A flat icon button of 24dp, for the copy and show buttons of a code line. */
@Composable
private fun SettingsIconButton(icon: ImageVector, description: String, tag: String, onClick: () -> Unit) {
    val tc = tc()
    HoverBox(
        modifier = Modifier.size(ICON_BUTTON).clip(CORNER_SM).testTag(tag).semantics { contentDescription = description },
        onClick = onClick,
    ) {
        Icon(icon, contentDescription = null, tint = tc.ts, modifier = Modifier.size(16.dp).align(Alignment.Center))
    }
}

private val ICON_BUTTON = 22.dp

/** One labelled line of the connection block: a [name], the mono [value] and its buttons, as a read-only 26dp field like every Settings field. */
@Composable
private fun ConnectionLine(name: String, value: String, buttons: @Composable () -> Unit) {
    val tc = tc()
    Row(
        Modifier.fillMaxWidth()
            .height(SETTINGS_CONTROL_HEIGHT)
            .background(tc.bg, CORNER_SM)
            .border(SETTINGS_CONTROL_BORDER, tc.br, CORNER_SM)
            .padding(start = 8.dp, end = 2.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        AppText(name, color = tc.ts, fontSize = 11.sp, modifier = Modifier.width(CONNECTION_NAME_WIDTH))
        AppText(value, color = tc.tx, fontSize = 11.sp, fontFamily = MONO, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
        buttons()
    }
}

private val CONNECTION_NAME_WIDTH = 44.dp

/**
 * How to connect an MCP client: the URL and token (each with a copy button, the token masked until shown), the two ready-made configs
 * and the Connection info dialog (connected clients, the curl command, a new token). The token is only read from disk on a click or
 * when shown, never merely by opening the page.
 */
@Composable
private fun McpConnectionRow(state: AppState, port: Int) {
    val tc = tc()
    var copied by remember { mutableStateOf<McpCopied?>(null) }
    var tokenShown by remember { mutableStateOf(false) }
    LaunchedEffect(copied) {
        if (copied != null) {
            delay(COPIED_FEEDBACK_MS)
            copied = null
        }
    }
    val copiedMark: @Composable (McpCopied) -> Unit = { which ->
        if (copied == which) AppText("Copied", color = tc.ac, fontSize = 11.sp, fontWeight = FontWeight.Medium)
    }
    SettingsStackedRow("Connect a client", MCP_CONNECTION_HINT, anchor = "Connection info") {
        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
            ConnectionLine("URL", mcpUrl(port)) {
                copiedMark(McpCopied.Url)
                SettingsIconButton(Icons.Outlined.ContentCopy, "Copy URL", SystemPageTags.MCP_COPY_URL) {
                    state.copyToClipboard(mcpUrl(port))
                    copied = McpCopied.Url
                }
            }
            ConnectionLine("Token", if (tokenShown) state.connectionInfoToken() else TOKEN_MASK) {
                copiedMark(McpCopied.Token)
                SettingsIconButton(
                    if (tokenShown) Icons.Outlined.VisibilityOff else Icons.Outlined.Visibility,
                    if (tokenShown) "Hide token" else "Show token",
                    SystemPageTags.MCP_TOKEN_TOGGLE,
                ) { tokenShown = !tokenShown }
                SettingsIconButton(Icons.Outlined.ContentCopy, "Copy token", SystemPageTags.MCP_COPY_TOKEN) {
                    state.copyToClipboard(state.connectionInfoToken())
                    copied = McpCopied.Token
                }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                SettingsButton(
                    "Copy config for Claude Code",
                    onClick = {
                        state.copyToClipboard(mcpConfigSnippet(port, state.connectionInfoToken()))
                        copied = McpCopied.Claude
                    },
                    modifier = Modifier.testTag(SystemPageTags.MCP_COPY_CLAUDE),
                )
                copiedMark(McpCopied.Claude)
                SettingsButton(
                    "Copy config for Codex",
                    onClick = {
                        state.copyToClipboard(codexMcpConfigSnippet(port, state.connectionInfoToken()))
                        copied = McpCopied.Codex
                    },
                    modifier = Modifier.testTag(SystemPageTags.MCP_COPY_CODEX),
                )
                copiedMark(McpCopied.Codex)
                // Deliberately doesn't close Settings first: stacks on top instead, so closing the dialog returns you to Settings
                // rather than to the main window.
                SettingsButton(
                    "Connection info…",
                    onClick = { state.mcpInfoOpen = true },
                    variant = ButtonVariant.Secondary,
                    modifier = Modifier.testTag(SystemPageTags.MCP_INFO),
                )
            }
        }
    }
}

private const val PORT_DIGITS = 5

@Composable
private fun DiagnosticsGroup(state: AppState, reclaimFocus: () -> Unit) {
    val fullPath = state.settings.debugLogFilePath
    SettingsGroup("Diagnostics") {
        SettingsRow("Debug logging", DEBUG_LOGGING_HINT) {
            SettingsToggle(
                checked = state.settings.debugLoggingEnabled,
                label = "Debug logging",
                onChange = { next -> state.setDebugLoggingEnabled(next) },
                reclaimFocus = reclaimFocus,
                testTag = SystemPageTags.DEBUG_SWITCH,
            )
        }
        val logFileRow: @Composable () -> Unit = {
            SettingsRow("Debug log file", fullPath?.let { truncatePathForDisplay(it) } ?: NOT_SET, detail = fullPath?.let { "$DEBUG_LOG_FILE_HINT\n$it" }) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                    SettingsButton("Browse", onClick = { state.pickDebugLogFile() }, modifier = Modifier.testTag(SystemPageTags.DEBUG_BROWSE))
                    SettingsButton(
                        "Open current log",
                        onClick = { state.openCurrentDebugLog() },
                        variant = ButtonVariant.Secondary,
                        enabled = fullPath?.let { File(it).isFile } == true,
                        modifier = Modifier.testTag(SystemPageTags.DEBUG_OPEN),
                    )
                }
            }
        }
        logFileRow()
        state.debugLoggingError?.let { message ->
            Row(
                Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 9.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                AppText("Diagnostic logging unavailable: $message", color = DANGER_RED, fontSize = 11.sp, maxLines = 2, modifier = Modifier.weight(1f))
                SettingsButton("Retry", onClick = { state.retryDebugLoggingConfiguration() }, variant = ButtonVariant.Secondary)
            }
        }
    }
}

// ── About ─────────────────────────────────────────────────────────────

private const val APP_TAGLINE = "Log viewer for Android logcat, bug reports and captures."

@Composable
internal fun AboutSettingsSection(state: AppState, reclaimFocus: () -> Unit = {}) {
    val tc = tc()
    val status = updateStatusLine(state)
    Row(
        Modifier.fillMaxWidth().background(tc.p, ABOUT_CARD_SHAPE).border(1.dp, tc.br, ABOUT_CARD_SHAPE).padding(12.dp),
        horizontalArrangement = Arrangement.spacedBy(16.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            AppText("Indagium", color = tc.tx, fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
            AppText(APP_TAGLINE, color = tc.ts, fontSize = 11.sp, maxLines = 2)
            Row(verticalAlignment = Alignment.CenterVertically) {
                AppText("Version ", color = tc.ts, fontSize = 11.sp)
                AppText(BuildInfo.APP_VERSION, color = tc.tx, fontSize = 11.sp)
                AppText(" · by ", color = tc.ts, fontSize = 11.sp)
                AppText(BuildInfo.APP_AUTHOR, color = tc.tx, fontSize = 11.sp)
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
            status?.let { (text, isError) ->
                AppText(text, color = if (isError) DANGER_RED else tc.ts, fontSize = 11.sp, modifier = Modifier.testTag(SystemPageTags.ABOUT_UPDATE_STATUS))
            }
            SettingsButton(
                "Check for updates",
                onClick = { state.checkForUpdates(manual = true) },
                variant = ButtonVariant.Secondary,
                modifier = Modifier.testTag(SystemPageTags.ABOUT_CHECK_UPDATES),
            )
        }
    }
    EditionGroup(state, reclaimFocus)
    SettingsGroup("Project") {
        LinkRow(
            Icons.Outlined.Code,
            "GitHub repository",
            "The source code, release notes and issue tracker on GitHub.",
            Icons.Outlined.OpenInNew,
            SystemPageTags.ABOUT_GITHUB,
            ::openProjectRepository,
        )
        LinkRow(
            Icons.Outlined.FavoriteBorder,
            "Support the project",
            "Ways to help keep Indagium going.",
            Icons.Outlined.ChevronRight,
            SystemPageTags.ABOUT_SUPPORT,
        ) { state.openSupportDialog() }
        LinkRow(
            Icons.Outlined.Gavel,
            "License agreement",
            "The terms Indagium is licensed under.",
            Icons.Outlined.ChevronRight,
            SystemPageTags.ABOUT_LICENSE,
        ) { state.licenseAgreementOpen = true }
    }
}

/** The edition and its limits, with the development-only switch while a build allows it. */
@Composable
private fun EditionGroup(state: AppState, reclaimFocus: () -> Unit) {
    val edition by state.editionService.current.collectAsState()
    SettingsGroup("Edition") {
        SettingsRow("Edition", editionSummary(edition)) { SettingsBadge(edition.label) }
        if (state.editionService.devSwitchAllowed()) {
            SettingsRow("Development switch", "Changes the edition for this run only.") {
                val editions = Edition.entries
                SettingsSegmented(
                    options = editions.map(Edition::label),
                    selectedIndices = setOf(editions.indexOf(edition)),
                    onToggle = { index ->
                        state.editionService.setForDev(editions[index])
                        reclaimFocus()
                    },
                    modifier = Modifier.testTag(SystemPageTags.EDITION_SWITCH),
                    segmentModifier = { index -> Modifier.testTag("${SystemPageTags.EDITION_SWITCH}-${editions[index].name}") },
                )
            }
        }
    }
}

/** A row of the Project group that opens something: a leading [icon], the [label] with its [description], and a [trailing] icon. */
@Composable
private fun LinkRow(
    icon: ImageVector,
    label: String,
    description: String,
    trailing: ImageVector,
    tag: String,
    onClick: () -> Unit,
) {
    val tc = tc()
    HoverBox(Modifier.fillMaxWidth().testTag(tag).settingsAnchor(label), onClick = onClick) {
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 9.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(icon, contentDescription = null, tint = tc.ts, modifier = Modifier.size(16.dp))
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                AppText(label, color = tc.tx, fontSize = 12.sp, fontWeight = FontWeight.Medium)
                AppText(description, color = tc.ts, fontSize = 11.sp, maxLines = 2, overflow = TextOverflow.Ellipsis)
            }
            Icon(trailing, contentDescription = null, tint = tc.ts, modifier = Modifier.size(16.dp))
        }
    }
}
