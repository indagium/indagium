@file:OptIn(androidx.compose.ui.ExperimentalComposeUiApi::class)

package com.indagium.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ManageSearch
import androidx.compose.material.icons.automirrored.outlined.StickyNote2
import androidx.compose.material.icons.automirrored.outlined.ViewList
import androidx.compose.material.icons.outlined.AutoFixHigh
import androidx.compose.material.icons.outlined.BugReport
import androidx.compose.material.icons.outlined.Build
import androidx.compose.material.icons.outlined.Category
import androidx.compose.material.icons.outlined.Code
import androidx.compose.material.icons.outlined.ContentCopy
import androidx.compose.material.icons.outlined.Flag
import androidx.compose.material.icons.outlined.Folder
import androidx.compose.material.icons.outlined.Hub
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material.icons.outlined.Keyboard
import androidx.compose.material.icons.outlined.Mic
import androidx.compose.material.icons.outlined.Palette
import androidx.compose.material.icons.outlined.PhoneAndroid
import androidx.compose.material.icons.outlined.Psychology
import androidx.compose.material.icons.outlined.Science
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material.icons.outlined.SmartDisplay
import androidx.compose.material.icons.outlined.Storage
import androidx.compose.material.icons.outlined.Tab
import androidx.compose.material.icons.outlined.Terminal
import androidx.compose.material.icons.outlined.Tune
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.onPointerEvent
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.indagium.generated.BuildInfo
import com.indagium.update.PROJECT_REPO_URL

// The Settings dialog's navigation model and the pieces of its shell that are not the page bodies: the grouped nav list, the footer, the
// interim About page and the dialog's size. SettingsDialog.kt owns the state (selected page, search, unsaved-changes guard).

/** The nav's section headings; a page belongs to exactly one. */
internal enum class SettingsGroupKind(val label: String) {
    Workspace("Workspace"),
    ReadingLogs("Reading logs"),
    Notes("Notes"),
    Devices("Devices"),
    Ai("AI"),
    Testing("Testing"),
    System("System"),
}

/**
 * One Settings page. A page with a [parent] is a drill-in sub-page: it has no nav item of its own, the nav highlights its parent and the
 * page shows a back link to it. [description] is the one-line text under the title; blank when the page body carries its own.
 */
internal enum class SettingsPage(
    val title: String,
    val icon: ImageVector,
    val group: SettingsGroupKind,
    val description: String = "",
    val parent: SettingsPage? = null,
) {
    General(
        "General", Icons.Outlined.Tune, SettingsGroupKind.Workspace,
        "Your workspace profile, memory for inactive tabs and update checks.",
    ),
    Appearance(
        "Appearance", Icons.Outlined.Palette, SettingsGroupKind.Workspace,
        "Theme, fonts, interface scale and the shape of corners.",
    ),
    TabsToolbar(
        "Tabs & toolbar", Icons.Outlined.Tab, SettingsGroupKind.Workspace,
        "How the tab strip and the main toolbar look, and where they sit.",
    ),
    LogView(
        "Log view", Icons.AutoMirrored.Outlined.ViewList, SettingsGroupKind.ReadingLogs,
        "How rows look and behave in every log tab. A tab's own toolbar can still change most of these for that tab.",
    ),
    SearchFilters(
        "Search & filters", Icons.AutoMirrored.Outlined.ManageSearch, SettingsGroupKind.ReadingLogs,
        "What Ctrl/Cmd+F does, and how the filter panel and keyboard navigation behave.",
    ),
    IssueCategories(
        "Issue categories", Icons.Outlined.Category, SettingsGroupKind.ReadingLogs,
        "Your own regex categories for the Issues list.",
    ),
    LogFormats(
        "Log formats", Icons.Outlined.Tune, SettingsGroupKind.ReadingLogs,
        "Create, preview, import and export the parsers used for log tabs.",
    ),
    SourceCode(
        "Source code", Icons.Outlined.Code, SettingsGroupKind.ReadingLogs,
        "Register your project's source folders to enable Show in code.",
    ),
    NotesWriting(
        "Notes & writing", Icons.AutoMirrored.Outlined.StickyNote2, SettingsGroupKind.Notes,
        "How note blocks are written, and English proofreading for notes and captions.",
    ),
    CopyExport(
        "Copy & export", Icons.Outlined.ContentCopy, SettingsGroupKind.Notes,
        "What copying includes, the words it masks, and how notes and diagrams are exported.",
    ),
    CaptureVideo(
        "Capture & video", Icons.Outlined.PhoneAndroid, SettingsGroupKind.Devices,
        "Defaults for the New tab's Before start panel. Each capture can still change them before it starts.",
    ),
    CaptureTools(
        "Tools", Icons.Outlined.Build, SettingsGroupKind.Devices,
        "The adb and scrcpy programs used for device capture, and how the in-app mirror decodes video.",
        parent = CaptureVideo,
    ),
    CaptureLimits(
        "Limits & snapshots", Icons.Outlined.Storage, SettingsGroupKind.Devices,
        "Size, frame rate and bitrate of the recording, how big a session may grow, and how saved snapshots are named.",
        parent = CaptureVideo,
    ),
    CaptureMarkIssue(
        "Mark issue", Icons.Outlined.Flag, SettingsGroupKind.Devices,
        "The Mark issue button on the capture strip drops a note with the log window around the press and, if enabled, a screenshot.",
        parent = CaptureVideo,
    ),
    CaptureVideoPlayback(
        "Video playback", Icons.Outlined.SmartDisplay, SettingsGroupKind.Devices,
        "How playback follows the log, and the volume of the live mirror's sound.",
        parent = CaptureVideo,
    ),
    AiProviders(
        "Providers", Icons.Outlined.Psychology, SettingsGroupKind.Ai,
        "The AI provider profiles the AI panel uses.",
    ),
    AiCommands(
        "Commands", Icons.Outlined.Terminal, SettingsGroupKind.Ai,
        "Reusable prompts you can run from Actions or by typing /name in the chat box.",
    ),
    VoiceInput(
        "Voice input", Icons.Outlined.Mic, SettingsGroupKind.Ai,
        "Dictate into the AI composer using the default microphone. Audio stays local; AI providers receive only the text you choose to send.",
    ),
    TestRuns(
        "Test runs", Icons.Outlined.Science, SettingsGroupKind.Testing,
        "Defaults for new AI test runs, and where suites, runs and issues are kept.",
    ),
    IssueTracker(
        "Issue tracker", Icons.Outlined.BugReport, SettingsGroupKind.Testing,
        "Send issues from failed test steps to your own tracker, an MCP server. An AI agent files each issue by calling its tools; " +
            "the issue text and the evidence it uploads are visible to that AI profile and sent to the tracker.",
    ),
    Storage(
        "Storage & files", Icons.Outlined.Folder, SettingsGroupKind.System,
        "Where Indagium saves your files, and what it keeps on disk. The test suite, run and issue folders are on Test runs.",
    ),
    Integrations(
        "Integrations", Icons.Outlined.Hub, SettingsGroupKind.System,
        "The local MCP control server for AI clients, and diagnostic logging.",
    ),
    About(
        "About", Icons.Outlined.Info, SettingsGroupKind.System,
        "Version, edition and project links.",
    ),
    ;

    /** The page whose nav item stands for this one: itself, or its parent for a sub-page. */
    val navPage: SettingsPage get() = parent ?: this

    /** `Group › Page`, or `Group › Parent › Sub-page`, as shown next to a search result. */
    val breadcrumb: String get() = listOfNotNull(group.label, parent?.title, title).joinToString(" › ")
}

private val SETTINGS_DIALOG_WIDTH = 960.dp
private val SETTINGS_DIALOG_HEIGHT = 812.dp
private val SETTINGS_DIALOG_MARGIN = 24.dp

/** The dialog's design size, shrunk to leave [SETTINGS_DIALOG_MARGIN] free on every side of a window of [windowWidth] x [windowHeight]. */
internal fun settingsDialogSize(windowWidth: Dp, windowHeight: Dp): DpSize {
    val width = if (windowWidth > 0.dp) (windowWidth - SETTINGS_DIALOG_MARGIN * 2).coerceIn(0.dp, SETTINGS_DIALOG_WIDTH) else SETTINGS_DIALOG_WIDTH
    val height = if (windowHeight > 0.dp) (windowHeight - SETTINGS_DIALOG_MARGIN * 2).coerceIn(0.dp, SETTINGS_DIALOG_HEIGHT) else SETTINGS_DIALOG_HEIGHT
    return DpSize(width, height)
}

internal fun settingsNavTag(page: SettingsPage): String = "settings-nav-${page.name}"

// Tight enough that the 18 pages, their 7 group labels and the "All results" item of a search all fit in the dialog's 812dp without the
// list scrolling (and About staying out of view).
private val NAV_GROUP_GAP = 3.dp
private val NAV_GROUP_LABEL_HEIGHT = 18.dp

/**
 * The nav's page list: an 18dp group label over its 26dp items. While [searching] a leading "All results" item stands for the results, each
 * page shows its match count from [resultCounts] and pages with none are dimmed. [onPick] gets the page that was clicked.
 */
@Composable
internal fun SettingsNavList(
    selected: SettingsPage,
    visiblePages: Set<SettingsPage>,
    searching: Boolean,
    resultCounts: Map<SettingsPage, Int>,
    totalResults: Int,
    onAllResults: () -> Unit,
    onPick: (SettingsPage) -> Unit,
    modifier: Modifier = Modifier,
) {
    val tc = tc()
    ScrollColumnWithBar(modifier, verticalArrangement = Arrangement.spacedBy(NAV_GROUP_GAP), showBarOnlyWhenScrollable = true) {
        if (searching) {
            SettingsNavItem(
                title = "All results",
                icon = Icons.Outlined.Search,
                selected = true,
                onClick = onAllResults,
                tag = "settings-nav-results",
                badge = totalResults,
            )
        }
        SettingsGroupKind.entries.forEach { group ->
            val pages = SettingsPage.entries.filter { it.group == group && it.parent == null && it in visiblePages }
            if (pages.isEmpty()) return@forEach
            Column(Modifier.fillMaxWidth()) {
                Box(Modifier.fillMaxWidth().height(NAV_GROUP_LABEL_HEIGHT).padding(horizontal = 10.dp), contentAlignment = Alignment.CenterStart) {
                    AppText(group.label, color = tc.ts, fontSize = 10.sp, fontWeight = FontWeight.SemiBold, maxLines = 1)
                }
                pages.forEach { page ->
                    SettingsNavItem(
                        title = page.title,
                        icon = page.icon,
                        selected = !searching && page == selected.navPage,
                        onClick = { onPick(page) },
                        tag = settingsNavTag(page),
                        badge = if (searching) resultCounts[page] else null,
                        dimmed = searching && page !in resultCounts,
                    )
                }
            }
        }
    }
}

@Composable
private fun SettingsNavItem(
    title: String,
    icon: ImageVector,
    selected: Boolean,
    onClick: () -> Unit,
    tag: String,
    // Search-result count for this nav row (shown as a small pill), and dimming for pages with none.
    badge: Int? = null,
    dimmed: Boolean = false,
) {
    val tc = tc()
    var hovered by remember { mutableStateOf(false) }
    val shape = RoundedCornerShape(6.dp)
    Row(
        Modifier.fillMaxWidth().height(26.dp)
            .testTag(tag)
            .clip(shape)
            .background(
                when {
                    selected -> tc.abg
                    hovered -> tc.hv
                    else -> Color.Transparent
                },
            )
            .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null, role = Role.Tab, onClick = onClick)
            .semantics { this.selected = selected }
            .onPointerEvent(PointerEventType.Enter) { hovered = true }
            .onPointerEvent(PointerEventType.Exit) { hovered = false }
            .padding(horizontal = 10.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(icon, contentDescription = null, modifier = Modifier.size(16.dp), tint = if (selected) tc.ac else tc.ts)
        AppText(
            title,
            color = when {
                selected -> tc.tx
                dimmed -> tc.td
                else -> tc.ts
            },
            fontSize = 12.sp,
            fontWeight = if (selected) FontWeight.Medium else FontWeight.Normal,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )
        if (badge != null) {
            Box(Modifier.background(tc.ac.copy(alpha = .16f), RoundedCornerShape(50)).padding(horizontal = 6.dp, vertical = 1.dp)) {
                AppText(badge.toString(), color = tc.ac, fontSize = 10.sp, fontWeight = FontWeight.SemiBold)
            }
        }
    }
}

/**
 * The 52dp strip under the pages: ghost Keyboard shortcuts and Setup assistant buttons on the left; on the right a "Version · author"
 * link ([onVersion], to About) and the primary Done button.
 */
@Composable
internal fun SettingsFooter(
    onShortcuts: () -> Unit,
    onSetupAssistant: () -> Unit,
    onVersion: () -> Unit,
    onDone: () -> Unit,
    saveProfile: (() -> Unit)? = null,
) {
    val tc = tc()
    Column(Modifier.fillMaxWidth().background(tc.p)) {
        Divider()
        Row(
            Modifier.fillMaxWidth().height(52.dp).padding(start = 12.dp, end = 16.dp),
            horizontalArrangement = Arrangement.spacedBy(4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            // Deliberately doesn't close Settings first - stacks on top instead, so closing the shortcuts popup returns you to Settings
            // rather than to the main window.
            AppButton(
                "Keyboard shortcuts",
                onClick = onShortcuts,
                variant = ButtonVariant.Ghost,
                leadingIcon = Icons.Outlined.Keyboard,
                modifier = Modifier.testTag("settings-footer-shortcuts"),
            )
            AppButton(
                "Setup assistant…",
                onClick = onSetupAssistant,
                variant = ButtonVariant.Ghost,
                leadingIcon = Icons.Outlined.AutoFixHigh,
                modifier = Modifier.testTag("settings-footer-setup-assistant"),
            )
            Box(Modifier.weight(1f))
            HoverBox(
                modifier = Modifier.clip(RoundedCornerShape(4.dp)).testTag("settings-footer-version"),
                onClick = onVersion,
            ) {
                Row(Modifier.padding(horizontal = 6.dp, vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                    AppText("Version ${BuildInfo.APP_VERSION} · ${BuildInfo.APP_AUTHOR}", color = tc.ts, fontSize = 11.sp)
                }
            }
            Box(Modifier.size(8.dp))
            if (saveProfile != null) {
                AppButton("Save profile", onClick = saveProfile, modifier = Modifier.testTag("settings-log-format-save-footer"))
            }
            AppButton("Done", onClick = onDone, variant = ButtonVariant.Primary, modifier = Modifier.testTag("settings-done"))
        }
    }
}

internal fun openProjectRepository() {
    openExternalUrl(PROJECT_REPO_URL)
}
