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
import androidx.compose.material.icons.outlined.BugReport
import androidx.compose.material.icons.outlined.Category
import androidx.compose.material.icons.outlined.Code
import androidx.compose.material.icons.outlined.ContentCopy
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
    IssueCategories("Issue categories", Icons.Outlined.Category, SettingsGroupKind.ReadingLogs),
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
    CaptureVideo("Capture & video", Icons.Outlined.PhoneAndroid, SettingsGroupKind.Devices),
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
        "Dictation engine, language and local model.",
    ),
    TestRuns("Test runs", Icons.Outlined.Science, SettingsGroupKind.Testing),
    IssueTracker("Issue tracker", Icons.Outlined.BugReport, SettingsGroupKind.Testing),
    Storage(
        "Storage & files", Icons.Outlined.Folder, SettingsGroupKind.System,
        "Where Indagium saves your files, and what it keeps on disk.",
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

    /** `Group › Page`, as shown next to a search result. */
    val breadcrumb: String get() = "${group.label} › $title"
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

/**
 * The nav's page list: a 20dp group label over its 26dp items. While [searching] a leading "All results" item stands for the results, each
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
    ScrollColumnWithBar(modifier, verticalArrangement = Arrangement.spacedBy(6.dp)) {
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
                Box(Modifier.fillMaxWidth().height(20.dp).padding(horizontal = 10.dp), contentAlignment = Alignment.CenterStart) {
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
                modifier = Modifier.testTag("settings-footer-setup-assistant"),
            )
            Box(Modifier.weight(1f))
            HoverBox(
                modifier = Modifier.clip(RoundedCornerShape(4.dp)).testTag("settings-footer-version"),
                onClick = onVersion,
            ) {
                Row(Modifier.padding(horizontal = 6.dp, vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                    AppText("Version ", color = tc.ts, fontSize = 11.sp)
                    AppText(BuildInfo.APP_VERSION, color = tc.ts, fontSize = 11.sp, fontFamily = MONO)
                    AppText(" · ${BuildInfo.APP_AUTHOR}", color = tc.ts, fontSize = 11.sp)
                }
            }
            Box(Modifier.size(8.dp))
            AppButton("Done", onClick = onDone, variant = ButtonVariant.Primary, modifier = Modifier.testTag("settings-done"))
        }
    }
}

internal fun openProjectRepository() {
    openExternalUrl(PROJECT_REPO_URL)
}

/** Interim About page: version and author text and the project links that used to live in the dialog's footer, then the edition. */
@Composable
internal fun AboutSettingsSection(state: AppState) {
    val tc = tc()
    Row(horizontalArrangement = Arrangement.spacedBy(40.dp)) {
        CompactSetting("Version") { AppText(BuildInfo.APP_VERSION, color = tc.ts, fontSize = 12.sp, fontFamily = MONO) }
        CompactSetting("Author") { AppText(BuildInfo.APP_AUTHOR, color = tc.ts, fontSize = 12.sp, fontFamily = MONO) }
    }
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
        AccentLink("GitHub repository ↗", onClick = ::openProjectRepository)
        AccentLink("Support project ♥", onClick = { state.openSupportDialog() })
        AppButton("License…", onClick = { state.licenseAgreementOpen = true }, variant = ButtonVariant.Secondary)
    }
    EditionSettingsBlock(state)
}
