@file:OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class, androidx.compose.ui.ExperimentalComposeUiApi::class)

package com.openlog.ui

import androidx.compose.foundation.*
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Bolt
import androidx.compose.material.icons.outlined.Code
import androidx.compose.material.icons.outlined.Description
import androidx.compose.material.icons.outlined.Palette
import androidx.compose.material.icons.outlined.Psychology
import androidx.compose.material.icons.outlined.Terminal
import androidx.compose.material.icons.outlined.Tune
import androidx.compose.material3.Icon
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.*
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupProperties
import com.openlog.ai.CustomAiCommand
import com.openlog.ai.ModelDiscoveryResult
import com.openlog.ai.OpenAiCompatibleProvider
import com.openlog.ai.normalizeAiProviderProfiles
import com.openlog.generated.BuildInfo
import com.openlog.model.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import java.io.File
import java.util.UUID

// ── Settings dialog ───────────────────────────────────────────────────
// Left-hand nav lists every section; only the selected section's content renders on the
// right, so growing any one section (e.g. AI providers) no longer pushes every other
// section down a shared scroll. There's no standalone "About" entry — its former content
// (keyboard shortcuts, version, author) now lives in Editor behavior and the footer.

/** Published by [AiProviderSettingsSection] each recomposition so [SettingsDialog] can gate
 *  section switches and closing the dialog behind an unsaved-changes prompt without hoisting the
 *  section's entire edit-draft state up to the dialog. */
private class AiProviderGuard(val isDirty: Boolean, val profileName: String, val save: () -> String?)

internal enum class SettingsSection(val title: String, val icon: ImageVector) {
    Appearance("Appearance", Icons.Outlined.Palette),
    EditorBehavior("Editor behavior", Icons.Outlined.Tune),
    ExportAnnotations("Export & annotations", Icons.Outlined.Description),
    Automation("Automation", Icons.Outlined.Bolt),
    AiProviders("AI providers", Icons.Outlined.Psychology),
    CustomAiCommands("AI commands", Icons.Outlined.Terminal),
    SourceCode("Source code", Icons.Outlined.Code),
}

@Composable
internal fun SettingsDialog(state: AppState, onDismiss: () -> Unit, onRequestCloseChanged: (() -> Unit) -> Unit = {}) {
    val tc = tc()
    val shape = RoundedCornerShape(8.dp)
    var selectedSection by remember {
        mutableStateOf(state.requestedSettingsSection ?: SettingsSection.Appearance)
    }
    LaunchedEffect(Unit) {
        state.requestedSettingsSection?.let {
            selectedSection = it
            state.requestedSettingsSection = null
        }
        state.refreshArchiveCacheInfo()
    }
    // Guards leaving the AI providers section (switching to another section, or closing the
    // dialog entirely) with unsaved profile edits. Only consulted while that section is actually
    // selected, so a stale guard value left over from an earlier visit is harmless.
    var aiProviderGuard by remember { mutableStateOf<AiProviderGuard?>(null) }
    var pendingSectionSwitch by remember { mutableStateOf<SettingsSection?>(null) }
    var pendingClose by remember { mutableStateOf(false) }
    var guardSaveError by remember { mutableStateOf<String?>(null) }

    fun requestSectionSwitch(target: SettingsSection) {
        if (selectedSection == SettingsSection.AiProviders && aiProviderGuard?.isDirty == true) {
            guardSaveError = null
            pendingSectionSwitch = target
        } else {
            selectedSection = target
        }
    }

    fun requestClose() {
        if (selectedSection == SettingsSection.AiProviders && aiProviderGuard?.isDirty == true) {
            guardSaveError = null
            pendingClose = true
        } else {
            onDismiss()
        }
    }
    // Escape still reaches the outer Dialog's onDismissRequest (only click-outside is disabled
    // there), so it must go through the same unsaved-changes guard rather than closing unconditionally.
    SideEffect { onRequestCloseChanged(::requestClose) }

    Box(
        // 190 (sidebar) + 1 (divider) + 572 (content). 572 is tuned tight against ThemeGallery's
        // FlowRow math (118dp cards, 8dp gaps: 4 cards = 496dp) plus just enough slack (~8dp) that
        // the scrollbar sits close against the 4th card instead of floating in leftover width.
        Modifier.width(763.dp).height(560.dp)
            .clip(shape)
            .background(tc.p)
            .border(1.dp, tc.br, shape),
    ) {
        Column(Modifier.fillMaxSize()) {
            Row(
                Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 16.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                AppText("Settings", color = tc.tx, fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
                CloseButton(onClick = ::requestClose)
            }
            Divider()
            Row(Modifier.weight(1f).fillMaxWidth()) {
                Column(
                    Modifier.width(190.dp).fillMaxHeight().padding(vertical = 12.dp, horizontal = 10.dp),
                    verticalArrangement = Arrangement.spacedBy(2.dp),
                ) {
                    SettingsSection.entries.forEach { section ->
                        SettingsMenuItem(
                            section = section,
                            selected = section == selectedSection,
                            onClick = { requestSectionSwitch(section) },
                        )
                    }
                }
                Box(Modifier.width(1.dp).fillMaxHeight().background(tc.br))
                val contentScroll = rememberScrollState()
                Box(Modifier.weight(1f).fillMaxHeight()) {
                    Column(
                        Modifier.fillMaxSize().verticalScroll(contentScroll).padding(24.dp).padding(end = 8.dp),
                        verticalArrangement = Arrangement.spacedBy(14.dp),
                    ) {
                        when (selectedSection) {
                            SettingsSection.Appearance -> AppearanceSettingsSection(state)
                            SettingsSection.EditorBehavior -> EditorBehaviorSettingsSection(state)
                            SettingsSection.ExportAnnotations -> ExportAnnotationsSettingsSection(state)
                            SettingsSection.Automation -> AutomationSettingsSection(state)
                            SettingsSection.AiProviders -> AiProviderSettingsSection(state) { aiProviderGuard = it }
                            SettingsSection.CustomAiCommands -> CustomAiCommandsSettingsSection(state)
                            SettingsSection.SourceCode -> SourceCodeSettingsSection(state)
                        }
                    }
                    VerticalScrollbar(
                        adapter = rememberScrollbarAdapter(contentScroll),
                        modifier = Modifier.align(Alignment.CenterEnd).fillMaxHeight().padding(vertical = 4.dp),
                        style = appScrollbarStyle(tc),
                    )
                }
            }
            // Sits just above the existing footer divider (bottom-up: Done → footer divider →
            // Show shortcuts). The caption is stacked directly on top of the button, right-aligned.
            Row(
                Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 10.dp),
                horizontalArrangement = Arrangement.End,
            ) {
                Column(horizontalAlignment = Alignment.Start, verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    AppText("Keyboard shortcuts", color = tc.td, fontSize = 10.sp, fontFamily = UI, fontWeight = FontWeight.SemiBold)
                    // Deliberately doesn't close Settings first — stacks on top instead, so closing
                    // this popup returns you to Settings rather than to the main window.
                    AppButton("Show shortcuts…", onClick = { state.shortcutsOpen = true }, variant = ButtonVariant.Secondary)
                }
            }
            Divider()
            Row(
                Modifier.fillMaxWidth().padding(16.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                // Fixed-width label column (not spacedBy) so "Version"/"Author" — different
                // lengths in a proportional font — leave their values starting at the same x.
                Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Box(Modifier.width(52.dp)) {
                            AppText("Version", color = tc.td, fontSize = 10.sp, fontFamily = UI, fontWeight = FontWeight.SemiBold)
                        }
                        AppText(BuildInfo.APP_VERSION, color = tc.ts, fontSize = 10.sp, fontFamily = MONO)
                    }
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Box(Modifier.width(52.dp)) {
                            AppText("Author", color = tc.td, fontSize = 10.sp, fontFamily = UI, fontWeight = FontWeight.SemiBold)
                        }
                        AppText(BuildInfo.APP_AUTHOR, color = tc.ts, fontSize = 10.sp, fontFamily = MONO)
                    }
                }
                AppButton("Done", onClick = ::requestClose, variant = ButtonVariant.Primary)
            }
        }
    }

    pendingSectionSwitch?.let { target ->
        SettingsConfirmDialog(
            title = "Save changes to ${aiProviderGuard?.profileName.orEmpty()}?",
            message = "You changed this provider's settings without saving. Save them before switching sections?",
            error = guardSaveError,
            onDismissRequest = { pendingSectionSwitch = null; guardSaveError = null },
        ) {
            DialogActionButton("Save", active = true) {
                val err = aiProviderGuard?.save?.invoke()
                if (err == null) {
                    pendingSectionSwitch = null
                    guardSaveError = null
                    selectedSection = target
                } else {
                    guardSaveError = err
                }
            }
            DialogActionButton("Discard", active = false, danger = true) {
                pendingSectionSwitch = null
                guardSaveError = null
                selectedSection = target
            }
            DialogActionButton("Cancel", active = false) { pendingSectionSwitch = null; guardSaveError = null }
        }
    }

    if (pendingClose) {
        SettingsConfirmDialog(
            title = "Save changes to ${aiProviderGuard?.profileName.orEmpty()}?",
            message = "You changed this provider's settings without saving. Save them before closing Settings?",
            error = guardSaveError,
            onDismissRequest = { pendingClose = false; guardSaveError = null },
        ) {
            DialogActionButton("Save", active = true) {
                val err = aiProviderGuard?.save?.invoke()
                if (err == null) {
                    pendingClose = false
                    guardSaveError = null
                    onDismiss()
                } else {
                    guardSaveError = err
                }
            }
            DialogActionButton("Discard", active = false, danger = true) {
                pendingClose = false
                guardSaveError = null
                onDismiss()
            }
            DialogActionButton("Cancel", active = false) { pendingClose = false; guardSaveError = null }
        }
    }
}

/** Shared modal shape for the confirm/discard prompts in this file - title, message, an optional
 *  inline error (surfaced when a Save attempt from within the dialog fails validation), and a
 *  caller-supplied row of [DialogActionButton]s. */
@Composable
private fun SettingsConfirmDialog(
    title: String,
    message: String,
    onDismissRequest: () -> Unit,
    error: String? = null,
    buttons: @Composable RowScope.() -> Unit,
) {
    val tc = tc()
    Dialog(onDismissRequest = onDismissRequest) {
        Column(
            Modifier.width(460.dp).background(tc.p, RoundedCornerShape(8.dp))
                .border(1.dp, tc.br, RoundedCornerShape(8.dp)).padding(20.dp),
        ) {
            AppText(title, color = tc.tx, fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
            Spacer(Modifier.height(6.dp))
            AppText(message, color = tc.td, fontSize = 11.sp, maxLines = 3)
            error?.let {
                Spacer(Modifier.height(6.dp))
                AppText(it, color = DANGER_RED, fontSize = 11.sp, maxLines = 3)
            }
            Spacer(Modifier.height(14.dp))
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterHorizontally),
                verticalAlignment = Alignment.CenterVertically,
                content = buttons,
            )
        }
    }
}

@Composable
private fun SettingsMenuItem(section: SettingsSection, selected: Boolean, onClick: () -> Unit) {
    val tc = tc()
    var hovered by remember { mutableStateOf(false) }
    val shape = RoundedCornerShape(8.dp)
    Row(
        Modifier.fillMaxWidth()
            .clip(shape)
            .background(
                when {
                    selected -> tc.ac.copy(alpha = .16f)
                    hovered -> tc.br.copy(alpha = .5f)
                    else -> Color.Transparent
                },
            )
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                onClick = onClick,
            )
            .onPointerEvent(PointerEventType.Enter) { hovered = true }
            .onPointerEvent(PointerEventType.Exit) { hovered = false }
            .padding(horizontal = 12.dp, vertical = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            section.icon,
            contentDescription = null,
            modifier = Modifier.size(16.dp),
            tint = if (selected) tc.ac else tc.td,
        )
        AppText(
            section.title,
            color = if (selected) tc.tx else tc.ts,
            fontSize = 12.sp,
            fontWeight = if (selected) FontWeight.Medium else FontWeight.Normal,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

/** A bounded, scrollable collection used for Settings sections that can grow without bound.
 *
 * Keeping the add/register action outside this viewport makes it available even when a long list
 * is scrolled to the bottom, matching the Theme gallery's compact scrollbar treatment. */
@Composable
private fun SettingsScrollableRows(
    modifier: Modifier = Modifier,
    content: @Composable ColumnScope.() -> Unit,
) {
    val tc = tc()
    val scrollState = rememberScrollState()
    val shape = RoundedCornerShape(6.dp)
    Box(
        modifier.fillMaxWidth().height(148.dp)
            .clip(shape)
            .background(tc.p2)
            .border(1.dp, tc.br, shape)
            .padding(8.dp),
    ) {
        Column(
            Modifier.fillMaxSize().verticalScroll(scrollState).padding(end = 12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
            content = content,
        )
        VerticalScrollbar(
            adapter = rememberScrollbarAdapter(scrollState),
            modifier = Modifier.align(Alignment.CenterEnd).fillMaxHeight().width(6.dp),
            style = appScrollbarStyle(tc),
        )
    }
}

@Composable
private fun AppearanceSettingsSection(state: AppState) {
    val tc = tc()
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        AppText("Theme", color = tc.td, fontSize = 10.sp, fontFamily = UI, fontWeight = FontWeight.SemiBold)
        ThemeGallery(state)
    }
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        TooltipArea(
            tooltip = {
                Box(
                    Modifier
                        .background(tc.p2, RoundedCornerShape(4.dp))
                        .border(0.5.dp, tc.br, RoundedCornerShape(4.dp))
                        .padding(horizontal = 8.dp, vertical = 4.dp),
                ) {
                    AppText(
                        "Auto-saved notes are written here when this folder exists. Clear cache keeps this folder.",
                        color = tc.tx,
                        fontSize = 11.sp,
                        maxLines = 2,
                    )
                }
            },
        ) {
            AppText(
                "Default save folder",
                color = tc.td,
                fontSize = 10.sp,
                fontFamily = UI,
                fontWeight = FontWeight.SemiBold,
            )
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            val fullPath = state.settings.defaultSaveDir
            val pathText: @Composable () -> Unit = {
                AppText(
                    fullPath?.let { truncatePathForDisplay(it) } ?: "(not set)",
                    color = tc.ts, fontSize = 11.sp, fontFamily = MONO, overflow = TextOverflow.Ellipsis,
                )
            }
            if (fullPath != null) {
                TooltipArea(
                    tooltip = {
                        Box(
                            Modifier
                                .background(tc.p2, RoundedCornerShape(4.dp))
                                .border(0.5.dp, tc.br, RoundedCornerShape(4.dp))
                                .padding(horizontal = 8.dp, vertical = 4.dp),
                        ) {
                            AppText(fullPath, color = tc.tx, fontSize = 11.sp, fontFamily = MONO)
                        }
                    },
                    modifier = Modifier.weight(1f),
                ) { pathText() }
            } else {
                Box(Modifier.weight(1f)) { pathText() }
            }
            AppButton("Browse", onClick = { state.pickSaveFolder() })
            if (fullPath != null) AppButton(
                "Clear",
                onClick = { state.updateSettings { it.copy(defaultSaveDir = null) } })
        }
    }
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        AppText(
            "App data",
            color = tc.td,
            fontSize = 10.sp,
            fontFamily = UI,
            fontWeight = FontWeight.SemiBold,
        )
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            val cachePath = state.appCachePath
            TooltipArea(
                tooltip = {
                    Box(
                        Modifier
                            .background(tc.p2, RoundedCornerShape(4.dp))
                            .border(0.5.dp, tc.br, RoundedCornerShape(4.dp))
                            .padding(horizontal = 8.dp, vertical = 4.dp),
                    ) {
                        AppText(cachePath, color = tc.tx, fontSize = 11.sp, fontFamily = MONO)
                    }
                },
                modifier = Modifier.weight(1f),
            ) {
                AppText(
                    "${truncatePathForDisplay(cachePath)} · ${formatByteSize(state.appDataSizeBytes)}",
                    color = tc.ts,
                    fontSize = 11.sp,
                    fontFamily = MONO,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            AppButton("Clear cache", onClick = { state.requestClearCache() }, variant = ButtonVariant.Secondary)
        }
    }
    state.autosaveError?.let { message ->
        AppText(message, color = DANGER_RED, fontSize = 11.sp, maxLines = 2)
    }
    Row(
        Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(10.dp),
        verticalAlignment = Alignment.Top,
    ) {
        CompactSetting("Font family", Modifier.weight(1f)) {
            SegmentedControl(
                options = listOf("Monospace", "Proportional"),
                selectedIndices = setOf(if (state.settings.fontMono) 0 else 1),
                onToggle = { idx -> state.updateSettings { it.copy(fontMono = idx == 0) } },
            )
        }
        CompactSetting("Font size", Modifier.weight(1f), horizontalAlignment = Alignment.Start) {
            ListStepper(
                options = (10..16).toList(),
                value = state.settings.fontSize,
                onChange = { v -> state.updateSettings { it.copy(fontSize = v) } },
            )
        }
        CompactSettingWithTooltip(
            label = "Toolbar labels",
            tooltip = "Hides text on the main toolbar buttons, leaving only their icons.",
            modifier = Modifier.weight(1f),
            horizontalAlignment = Alignment.Start,
        ) {
            SegmentedControl(
                options = listOf("Show", "Icons only"),
                selectedIndices = setOf(if (state.settings.toolbarIconOnlyButtons) 1 else 0),
                onToggle = { idx -> state.updateSettings { it.copy(toolbarIconOnlyButtons = idx == 1) } },
            )
        }
    }
}

@Composable
private fun EditorBehaviorSettingsSection(state: AppState) {
    val tc = tc()
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
        CompactSetting("Visible tabs") {
            val tabLimits = listOf(4, 6, 8, 10, 12, 16)
            ListStepper(
                options = tabLimits,
                value = state.settings.visibleTabLimit,
                onChange = { v -> state.updateSettings { it.copy(visibleTabLimit = v) } },
            )
        }
        CompactSetting("Keyboard scroll margin") {
            val scrollMargins = listOf(0, 2, 3, 5, 8, 12)
            ListStepper(
                options = scrollMargins,
                value = state.settings.navScrollMargin,
                onChange = { v -> state.updateSettings { it.copy(navScrollMargin = v) } },
            )
        }
        CompactSetting("Most-used tags") {
            val tagLimits = listOf(0, 3, 5, 10, 20)
            ListStepper(
                options = tagLimits,
                value = state.settings.mostUsedTagLimit,
                onChange = { v -> state.updateSettings { it.copy(mostUsedTagLimit = v) } },
            )
        }
        CompactSetting("Filter list rows") {
            val rowLimits = listOf(3, 5, 8, 10, 15)
            ListStepper(
                options = rowLimits,
                value = state.settings.filterListRows,
                onChange = { v -> state.updateSettings { it.copy(filterListRows = v) } },
            )
        }
    }
    // Plain SpaceBetween, no weight()/forced alignment: with weighted equal-width columns
    // and the last item End-aligned, that item's own leading slack (columnWidth minus its
    // content width) piled onto the third gap on top of the third column's own trailing
    // slack, visibly doubling it. Left tightly wrapped (each item's width = its own
    // content, per CompactSettingWithTooltip's plain Column), SpaceBetween's
    // (available − Σwidths)/(n−1) split gives a numerically equal gap between every pair
    // of adjacent items regardless of how their label/control widths differ, and pins the
    // last item flush to the row's right edge for free — no explicit End alignment needed.
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
        CompactSettingWithTooltip(
            label = "Row wrapping",
            // AWT has no horizontal mouse-wheel axis at all (confirmed via
            // java.awt.event.MouseWheelEvent — there's no getWheelRotationX() or
            // equivalent), so Compose Desktop only ever produces a horizontal scroll
            // delta when Shift is held down (its AWT bridge maps the wheel rotation into
            // Offset.x specifically for that case). A genuine two-finger trackpad
            // horizontal swipe never reaches Compose as a horizontal delta at all on
            // Linux; see ui/LinuxHorizontalScroll.kt for the X11-button bridge that
            // targets that gap directly. Shift+wheel works everywhere regardless, hence
            // the tooltip below.
            tooltip = "Auto wraps long lines to fit the panel width; toggle off to set a fixed " +
                "wrap column and scroll horizontally instead. Tip: hold Shift while scrolling if " +
                "two-finger trackpad swipe doesn't scroll horizontally.",
        ) {
            RowWrapControl(
                auto = state.settings.autoLogRowWrap,
                wrapChars = state.settings.logRowWrapLimitChars,
                onToggleAuto = { state.updateSettings { it.copy(autoLogRowWrap = !it.autoLogRowWrap) } },
                onWrapCharsChange = { limit -> state.updateSettings { it.copy(logRowWrapLimitChars = limit) } },
            )
        }
        CompactSettingWithTooltip(
            label = "Crash rows",
            tooltip = "Colors every row in an expanded crash/stack-trace group, not just the header.",
        ) {
            SegmentedControl(
                options = listOf("On", "Off"),
                selectedIndices = setOf(if (state.settings.highlightEntireCrashGroup) 0 else 1),
                onToggle = { idx -> state.updateSettings { it.copy(highlightEntireCrashGroup = idx == 0) } },
            )
        }
        CompactSettingWithTooltip(
            label = "Original panel",
            tooltip = "Controls whether newly opened files start with the unfiltered Original panel visible.",
        ) {
            SegmentedControl(
                options = listOf("On", "Off"),
                selectedIndices = setOf(if (state.settings.openNewFilesWithUnfiltered) 0 else 1),
                onToggle = { idx -> state.updateSettings { it.copy(openNewFilesWithUnfiltered = idx == 0) } },
            )
        }
        CompactSettingWithTooltip(
            label = "Ctrl+F focuses",
            tooltip = "Which filter input Ctrl/Cmd+F jumps to.",
        ) {
            // Rules (CtrlFTarget.MESSAGE_RULE) dropped from the selector, not the enum —
            // a settings token saved before this change can still hold it, so indexOf
            // falling through to -1 (nothing highlighted, existing behavior unaffected)
            // is the correct degrade rather than a crash.
            val targets = listOf(CtrlFTarget.TAGS, CtrlFTarget.KEYWORD_REGEX)
            SegmentedControl(
                options = listOf("Tags", "Regex"),
                selectedIndices = setOf(targets.indexOf(state.settings.ctrlFTarget)),
                onToggle = { idx -> state.updateSettings { it.copy(ctrlFTarget = targets[idx]) } },
            )
        }
    }
    CompactSettingWithTooltip(
        label = "Ctrl+F opens Original",
        tooltip = "When enabled, Ctrl/Cmd+F reveals the active file's unfiltered Original panel before focusing the configured search field.",
    ) {
        SegmentedControl(
            options = listOf("On", "Off"),
            selectedIndices = setOf(if (state.settings.openUnfilteredOnCtrlF) 0 else 1),
            onToggle = { idx -> state.updateSettings { it.copy(openUnfilteredOnCtrlF = idx == 0) } },
        )
    }
    CompactSettingWithTooltip(
        label = "Row number",
        tooltip = "Shows a left gutter with each row's original row number. The number stays fixed when " +
            "you filter or fold rows, so it always points back to the same spot in the full log.",
    ) {
        SegmentedControl(
            options = listOf("On", "Off"),
            selectedIndices = setOf(if (state.settings.showRowNumbers) 0 else 1),
            onToggle = { idx -> state.updateSettings { it.copy(showRowNumbers = idx == 0) } },
        )
    }
}

@Composable
private fun ExportAnnotationsSettingsSection(state: AppState) {
    val tc = tc()
    AnnotationSettingsRow(state)
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        AppText(
            "Annotation file prefix",
            color = tc.td,
            fontSize = 10.sp,
            fontFamily = UI,
            fontWeight = FontWeight.SemiBold
        )
        InlineField(
            state.settings.annotationPrefixLabel,
            { value -> state.updateSettings { it.copy(annotationPrefixLabel = value) } },
            "From",
            Modifier.fillMaxWidth(),
            fontSize = 12.sp,
        )
        val previewLabel = state.settings.annotationPrefixLabel.trim().ifBlank { "From" }
        AppText("Preview: $previewLabel app.log", color = tc.td, fontSize = 10.sp, fontFamily = MONO)
    }
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Row(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            AppText("Mask word on copy", color = tc.td, fontSize = 10.sp, fontFamily = UI, fontWeight = FontWeight.SemiBold)
            SegmentedControl(
                options = listOf("On", "Off"),
                selectedIndices = setOf(if (state.settings.maskWordOnCopy) 0 else 1),
                onToggle = { idx -> state.updateSettings { it.copy(maskWordOnCopy = idx == 0) } },
            )
        }
        if (state.settings.maskWordOnCopy) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                AppText("Word", color = tc.td, fontSize = 10.sp, fontFamily = UI, modifier = Modifier.weight(1f))
                AppText("Replacement", color = tc.td, fontSize = 10.sp, fontFamily = UI, modifier = Modifier.weight(1f))
                Spacer(Modifier.width(68.dp))
            }
            SettingsScrollableRows {
                if (state.settings.copyMaskRules.isEmpty()) {
                    AppText("(no pairs yet — add one to mask text when copying a note)", color = tc.td, fontSize = 11.sp)
                }
                state.settings.copyMaskRules.forEachIndexed { index, rule ->
                    Row(
                        Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        InlineField(
                            rule.target,
                            { value ->
                                state.updateSettings { settings ->
                                    settings.copy(copyMaskRules = settings.copyMaskRules.mapIndexed { ruleIndex, current ->
                                        if (ruleIndex == index) current.copy(target = value) else current
                                    })
                                }
                            },
                            "java",
                            Modifier.weight(1f),
                            fontSize = 12.sp,
                        )
                        InlineField(
                            rule.replacement,
                            { value ->
                                state.updateSettings { settings ->
                                    settings.copy(copyMaskRules = settings.copyMaskRules.mapIndexed { ruleIndex, current ->
                                        if (ruleIndex == index) current.copy(replacement = value) else current
                                    })
                                }
                            },
                            "j*ava",
                            Modifier.weight(1f),
                            fontSize = 12.sp,
                        )
                        AppButton(
                            "Remove",
                            onClick = {
                                state.updateSettings { settings ->
                                    settings.copy(copyMaskRules = settings.copyMaskRules.filterIndexed { ruleIndex, _ -> ruleIndex != index })
                                }
                            },
                            variant = ButtonVariant.Secondary,
                            isDanger = true,
                        )
                    }
                }
            }
            AppText(
                "Rules replace case-sensitive whole words in the listed order when copying a note — " +
                    "{code:java} block markers are never touched.",
                color = tc.td, fontSize = 10.sp, maxLines = 2,
            )
            AppButton(
                "Add mask",
                onClick = { state.updateSettings { it.copy(copyMaskRules = it.copyMaskRules + CopyMaskRule()) } },
                variant = ButtonVariant.Secondary,
            )
        }
    }
}

@Composable
private fun AutomationSettingsSection(state: AppState) {
    val tc = tc()
    Row(
        Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        CompactSetting("MCP control server") {
            SegmentedControl(
                options = listOf("On", "Off"),
                selectedIndices = setOf(if (state.settings.mcpControlEnabled) 0 else 1),
                onToggle = { idx -> state.setMcpControlEnabled(idx == 0, state.settings.mcpControlPort) },
            )
        }
        CompactSetting("Port", horizontalAlignment = Alignment.End) {
            var portText by remember(state.settings.mcpControlPort) {
                mutableStateOf(state.settings.mcpControlPort.toString())
            }
            InlineField(
                portText,
                { v ->
                    val digits = v.filter { it.isDigit() }.take(5)
                    portText = digits
                    digits.toIntOrNull()?.coerceIn(MIN_PORT, MAX_PORT)?.let { p ->
                        if (state.settings.mcpControlEnabled) state.setMcpControlEnabled(true, p)
                        else state.updateSettings { it.copy(mcpControlPort = p) }
                    }
                },
                "8991",
                Modifier.width(72.dp),
                fontSize = 12.sp,
            )
        }
    }
    state.mcpControlError?.let { message ->
        AppText(message, color = DANGER_RED, fontSize = 11.sp, maxLines = 2)
    }
    // (SEC-1) Off by default: CORS lets any origin a browser has open issue cross-origin requests
    // to this loopback server. Bearer-token auth still gates every request either way — this only
    // controls whether a browser is additionally allowed to do that at all. Opt-in for the
    // uncommon case of a browser-based MCP inspector.
    Row(
        Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        CompactSetting("Allow browser-based MCP clients (CORS)") {
            SegmentedControl(
                options = listOf("On", "Off"),
                selectedIndices = setOf(if (state.settings.mcpAllowBrowserClients) 0 else 1),
                onToggle = { idx -> state.setMcpAllowBrowserClients(idx == 0) },
            )
        }
    }
    Row(
        Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        AppText("Connection info", color = tc.td, fontSize = 10.sp, fontFamily = UI, fontWeight = FontWeight.SemiBold)
        // Deliberately doesn't close Settings first — stacks on top instead, so closing
        // this popup returns you to Settings rather than to the main window.
        AppButton("Connection info…", onClick = { state.mcpInfoOpen = true }, variant = ButtonVariant.Secondary)
    }
}

@Composable
private fun SourceCodeSettingsSection(state: AppState) {
    val tc = tc()
    TooltipArea(
        tooltip = {
            Box(
                Modifier
                    .background(tc.p2, RoundedCornerShape(4.dp))
                    .border(0.5.dp, tc.br, RoundedCornerShape(4.dp))
                    .padding(horizontal = 8.dp, vertical = 4.dp),
            ) {
                AppText(
                    "Point openLog at your project's source folder(s), then right-click a log line → " +
                        "\"Show in code\" to see the code that logged it.",
                    color = tc.tx,
                    fontSize = 11.sp,
                    maxLines = 2,
                )
            }
        },
    ) {
        AppButton("Register source code", onClick = { state.pickSourceFolder() })
    }
    SettingsScrollableRows {
        if (state.settings.sourceFolders.isEmpty()) {
            AppText(
                "(no folders — register one to enable Show in code)",
                color = tc.td,
                fontSize = 11.sp,
            )
        } else {
            state.settings.sourceFolders.forEach { path ->
                SourceFolderRow(state, path)
            }
        }
    }
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        // Scans lazily, the first time this section is composed (not eagerly at AppState
        // construction) — a subprocess probe per catalog candidate is wasted work for a session
        // that never opens Settings. Re-entering the section after a scan is already cached
        // (detectedEditors != null) is then a no-op; the Rescan button below is the only way to
        // force a fresh probe, e.g. after installing an editor mid-session.
        LaunchedEffect(Unit) {
            if (state.detectedEditors == null) state.rescanEditors()
        }
        CompactSettingWithTooltip(
            label = "Open command",
            tooltip = "Editor used by \"Show in code\" to open a file at the logged line. Automatic " +
                "uses the first installed app found (VS Code, IntelliJ IDEA, Android Studio, Cursor, " +
                "Sublime Text, or Zed); pick one by name, or choose Custom command… to type a raw " +
                "command with {file} and {line} placeholders, e.g. idea --line {line} {file} or " +
                "code -g {file}:{line}. There is no fallback to the system's default-app opener: " +
                "that can open the file but not jump to the line.",
        ) {
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Box(Modifier.weight(1f)) { EditorChoiceDropdown(state) }
                AppButton("Rescan", onClick = { state.rescanEditors() }, variant = ButtonVariant.Secondary)
            }
        }
        EditorChoiceDetail(state)
    }
    TooltipArea(
        tooltip = {
            Box(
                Modifier
                    .background(tc.p2, RoundedCornerShape(4.dp))
                    .border(0.5.dp, tc.br, RoundedCornerShape(4.dp))
                    .padding(horizontal = 8.dp, vertical = 5.dp),
            ) {
                AppText(
                    "During reindexing, finds simple wrapper methods that directly delegate to Log.* or Timber " +
                        "and follows one interface/implementation hop. It runs for every registered source folder; " +
                        "folders without wrappers simply discover none. Ambiguous or multi-level wrappers are skipped.",
                    color = tc.tx,
                    fontSize = 11.sp,
                    maxLines = 5,
                )
            }
        },
    ) {
        CheckRow(
            checked = state.settings.sourceAutoDiscoveryEnabled,
            onToggle = {
                state.updateSettings {
                    it.copy(sourceAutoDiscoveryEnabled = !it.sourceAutoDiscoveryEnabled)
                }
            },
        ) {
            AppText("Discover simple custom log wrappers", color = tc.tx, fontSize = 11.sp)
            AppText("ⓘ", color = tc.td, fontSize = 11.sp)
        }
    }
    SourceLoggingConfigurations(state)
}

private fun editorChoiceLabel(choice: String): String = when (choice) {
    "", "auto" -> "Automatic (first installed)"
    "custom" -> "Custom command…"
    else -> EDITOR_CATALOG.find { it.id == choice }?.displayName ?: "Automatic (first installed)"
}

// Hand-rolled dropdown for settings.editorChoice, modeled on FilterPanel.kt's
// CrashCategoryDropdown: a clickable field showing the current choice that opens a themed option
// list on click, with the popup width measured from the field itself so it lines up exactly.
@Composable
private fun EditorChoiceDropdown(state: AppState) {
    val tc = tc()
    val density = LocalDensity.current
    var open by remember { mutableStateOf(false) }
    var fieldWidth by remember { mutableStateOf(0.dp) }
    // See CrashCategoryDropdown's identical guard: the Popup's own dismissOnClickOutside also fires
    // for a click back on the field itself, so without suppressing the toggle briefly after a
    // dismiss, that dismiss and the field's own onClick can both fire for the same press and net out
    // to "stayed open" instead of closing.
    var suppressToggleUntilMs by remember { mutableStateOf(0L) }
    val choice = state.settings.editorChoice
    val detected = state.detectedEditors
    Box(
        Modifier.fillMaxWidth().onGloballyPositioned { coords ->
            fieldWidth = with(density) { coords.size.width.toDp() }
        },
    ) {
        HoverBox(
            modifier = Modifier.fillMaxWidth().height(26.dp)
                .clip(CORNER_SM)
                .background(tc.p2, CORNER_SM)
                .border(1.dp, tc.br, CORNER_SM),
            onClick = {
                if (System.currentTimeMillis() >= suppressToggleUntilMs) open = !open
            },
        ) {
            Row(
                Modifier.fillMaxSize().padding(horizontal = 10.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                AppText(editorChoiceLabel(choice), color = tc.tx, fontSize = 11.sp, fontWeight = FontWeight.Medium)
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
                    Modifier.width(fieldWidth)
                        .shadow(8.dp, RoundedCornerShape(8.dp))
                        .background(tc.p, RoundedCornerShape(8.dp))
                        .border(1.dp, tc.br, RoundedCornerShape(8.dp))
                        .padding(4.dp),
                    verticalArrangement = Arrangement.spacedBy(2.dp),
                ) {
                    EditorChoiceOptionRow(
                        label = "Automatic (first installed)",
                        active = choice == "" || choice == "auto",
                        onClick = { open = false; state.updateSettings { it.copy(editorChoice = "auto") } },
                    )
                    detected?.forEach { (preset, _) ->
                        EditorChoiceOptionRow(
                            label = preset.displayName,
                            active = choice == preset.id,
                            onClick = { open = false; state.updateSettings { it.copy(editorChoice = preset.id) } },
                        )
                    }
                    EditorChoiceOptionRow(
                        label = "Custom command…",
                        active = choice == "custom",
                        onClick = { open = false; state.updateSettings { it.copy(editorChoice = "custom") } },
                    )
                }
            }
        }
    }
}

@Composable
private fun EditorChoiceOptionRow(label: String, active: Boolean, onClick: () -> Unit) {
    val tc = tc()
    HoverBox(
        modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(5.dp)),
        baseBg = if (active) tc.abg else Color.Transparent,
        onClick = onClick,
    ) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 10.dp, vertical = 6.dp)) {
            AppText(
                label,
                color = if (active) tc.ac else tc.tx,
                fontSize = 11.sp,
                fontWeight = if (active) FontWeight.SemiBold else FontWeight.Normal,
            )
        }
    }
}

// Below the dropdown: the custom-command field when "Custom command…" is chosen, or a read-only
// hint showing what Automatic/a named app resolves to right now (per detectedEditors' latest scan).
@Composable
private fun EditorChoiceDetail(state: AppState) {
    val tc = tc()
    val choice = state.settings.editorChoice
    val detected = state.detectedEditors
    when (choice) {
        "custom" -> InlineField(
            state.settings.editorCommand,
            { value -> state.updateSettings { it.copy(editorCommand = value) } },
            "idea --line {line} {file}",
            Modifier.fillMaxWidth(),
            fontSize = 12.sp,
        )
        "", "auto" -> when {
            detected == null -> AppText("Detecting installed editors…", color = tc.td, fontSize = 10.sp)
            detected.isEmpty() -> AppText(
                "No known editor detected on this machine — install one above, or pick Custom " +
                    "command… to type your own.",
                color = tc.td,
                fontSize = 10.sp,
                maxLines = 2,
            )
            else -> {
                val (preset, template) = detected.first()
                AppText(
                    "Will use ${preset.displayName}: $template",
                    color = tc.td,
                    fontSize = 10.sp,
                    fontFamily = MONO,
                )
            }
        }
        else -> when {
            detected == null -> AppText("Detecting installed editors…", color = tc.td, fontSize = 10.sp)
            else -> {
                val resolved = detected.find { it.first.id == choice }
                if (resolved != null) {
                    AppText(resolved.second, color = tc.td, fontSize = 11.sp, fontFamily = MONO)
                } else {
                    val name = EDITOR_CATALOG.find { it.id == choice }?.displayName ?: "This app"
                    AppText(
                        "$name is no longer detected — pick another option above, or Custom command… " +
                            "to type your own.",
                        color = tc.td,
                        fontSize = 10.sp,
                        maxLines = 2,
                    )
                }
            }
        }
    }
}

@Composable
private fun SourceLoggingConfigurations(state: AppState) {
    val tc = tc()
    var editingId by remember { mutableStateOf<String?>(null) }
    var editingIsNew by remember { mutableStateOf(false) }
    val configurations = state.settings.sourceLogConfigurations
    val editing = configurations.firstOrNull { it.id == editingId }

    Column(verticalArrangement = Arrangement.spacedBy(7.dp)) {
        Row(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                AppText("Logging configurations", color = tc.tx, fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
                AppText(
                    "Assign module-specific wrapper rules to registered source folders.",
                    color = tc.td,
                    fontSize = 10.sp,
                )
            }
            AppButton(
                "Add configuration",
                onClick = {
                    val id = UUID.randomUUID().toString()
                    state.saveSourceLogConfiguration(SourceLogConfiguration(id = id, name = "Logging configuration"))
                    editingId = id
                    editingIsNew = true
                },
                variant = ButtonVariant.Secondary,
            )
        }
        if (editing != null) {
            SourceLoggingConfigurationEditor(
                state = state,
                configuration = editing,
                onClose = {
                    if (editingIsNew) editingId?.let(state::deleteSourceLogConfiguration)
                    editingId = null
                    editingIsNew = false
                },
                onSaved = { editingIsNew = false },
            )
        } else if (configurations.isEmpty()) {
            AppText("No custom logging configurations.", color = tc.td, fontSize = 10.sp)
        } else {
            configurations.forEach { configuration ->
                Row(
                    Modifier.fillMaxWidth().background(tc.p2, CORNER_SM).padding(horizontal = 8.dp, vertical = 6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                        AppText(configuration.name, color = tc.tx, fontSize = 11.sp, fontWeight = FontWeight.Medium)
                        AppText(
                            "${configuration.wrapperRules.size} wrapper rules",
                            color = tc.td,
                            fontSize = 10.sp,
                        )
                    }
                    AppButton("Edit", onClick = { editingId = configuration.id; editingIsNew = false })
                    AppButton(
                        "Duplicate",
                        onClick = {
                            val id = UUID.randomUUID().toString()
                            state.saveSourceLogConfiguration(configuration.copy(id = id, name = "${configuration.name} copy"))
                            editingId = id
                            editingIsNew = true
                        },
                    )
                    AppButton(
                        "Delete",
                        onClick = {
                            state.deleteSourceLogConfiguration(configuration.id)
                            if (editingId == configuration.id) {
                                editingId = null
                                editingIsNew = false
                            }
                        },
                        isDanger = true,
                    )
                }
            }
        }
    }
}

@Composable
private fun SourceLoggingConfigurationEditor(
    state: AppState,
    configuration: SourceLogConfiguration,
    onClose: () -> Unit,
    onSaved: () -> Unit,
) {
    val tc = tc()
    var draft by remember(configuration.id) { mutableStateOf(configuration) }
    val assignedFolders = state.settings.sourceFolderConfigurationIds
    Column(
        Modifier.fillMaxWidth().background(tc.p2, CORNER_SM).padding(8.dp),
        verticalArrangement = Arrangement.spacedBy(7.dp),
    ) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
            AppText("Edit logging configuration", color = tc.tx, fontSize = 11.sp, fontWeight = FontWeight.SemiBold)
            AppButton("Cancel", onClick = onClose)
        }
        InlineField(
            draft.name,
            { draft = draft.copy(name = it) },
            "Configuration name",
            Modifier.fillMaxWidth(),
            fontSize = 12.sp,
        )
        AppText("Source folders", color = tc.td, fontSize = 10.sp, fontWeight = FontWeight.SemiBold)
        if (state.settings.sourceFolders.isEmpty()) {
            AppText("Register source folders above to assign this configuration.", color = tc.td, fontSize = 10.sp)
        } else {
            state.settings.sourceFolders.forEach { folder ->
                val path = File(folder).absolutePath
                CheckRow(
                    checked = configuration.id in assignedFolders[path].orEmpty(),
                    onToggle = {
                        val current = assignedFolders[path].orEmpty().toSet()
                        val next = if (configuration.id in current) current - configuration.id else current + configuration.id
                        state.assignSourceLogConfigurations(path, next.toList())
                    },
                ) {
                    AppText(truncatePathForDisplay(folder), color = tc.ts, fontSize = 10.sp, fontFamily = MONO, overflow = TextOverflow.Ellipsis)
                }
            }
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
            AppText("Wrapper rules", color = tc.td, fontSize = 10.sp, fontWeight = FontWeight.SemiBold)
            AppButton(
                "Add rule",
                onClick = { draft = draft.copy(wrapperRules = draft.wrapperRules + SourceWrapperRule("", "")) },
            )
        }
        draft.wrapperRules.forEachIndexed { index, rule ->
            Column(Modifier.fillMaxWidth().border(0.5.dp, tc.br, CORNER_SM).padding(6.dp), verticalArrangement = Arrangement.spacedBy(5.dp)) {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
                    InlineField(
                        rule.ownerType,
                        { value -> draft = draft.replaceWrapperRule(index, rule.copy(ownerType = value)) },
                        "Owner/type, e.g. com.example.Telemetry",
                        Modifier.weight(1f),
                        fontSize = 11.sp,
                    )
                    InlineField(
                        rule.methodName,
                        { value -> draft = draft.replaceWrapperRule(index, rule.copy(methodName = value)) },
                        "Method",
                        Modifier.width(100.dp),
                        fontSize = 11.sp,
                    )
                    AppButton(
                        "Remove",
                        onClick = { draft = draft.copy(wrapperRules = draft.wrapperRules.filterIndexed { ruleIndex, _ -> ruleIndex != index }) },
                        isDanger = true,
                    )
                }
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    InlineField(
                        rule.tagArgumentIndex.toString(),
                        { value -> value.toIntOrNull()?.let { draft = draft.replaceWrapperRule(index, rule.copy(tagArgumentIndex = it)) } },
                        "Tag argument",
                        Modifier.weight(1f),
                        fontSize = 11.sp,
                    )
                    InlineField(
                        rule.messageArgumentIndex.toString(),
                        { value -> value.toIntOrNull()?.let { draft = draft.replaceWrapperRule(index, rule.copy(messageArgumentIndex = it)) } },
                        "Message argument",
                        Modifier.weight(1f),
                        fontSize = 11.sp,
                    )
                    InlineField(
                        rule.throwableArgumentIndex?.toString().orEmpty(),
                        { value -> draft = draft.replaceWrapperRule(index, rule.copy(throwableArgumentIndex = value.toIntOrNull())) },
                        "Throwable (optional)",
                        Modifier.weight(1f),
                        fontSize = 11.sp,
                    )
                }
            }
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
            AppButton(
                "Save configuration",
                onClick = { state.saveSourceLogConfiguration(draft); onSaved() },
                variant = ButtonVariant.Primary,
            )
        }
    }
}

private fun SourceLogConfiguration.replaceWrapperRule(index: Int, rule: SourceWrapperRule): SourceLogConfiguration =
    copy(wrapperRules = wrapperRules.mapIndexed { ruleIndex, current -> if (ruleIndex == index) rule else current })

// Indexing is per folder (AppState.reindexSources/sourceIndexStatusForFolder) — each registered
// folder gets its own status line and its own Reindex button, rather than one aggregate action
// that rescans every folder together.
@Composable
private fun SourceFolderRow(state: AppState, path: String) {
    val tc = tc()
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Row(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            TooltipArea(
                tooltip = {
                    Box(
                        Modifier
                            .background(tc.p2, RoundedCornerShape(4.dp))
                            .border(0.5.dp, tc.br, RoundedCornerShape(4.dp))
                            .padding(horizontal = 8.dp, vertical = 4.dp),
                    ) {
                        AppText(path, color = tc.tx, fontSize = 11.sp, fontFamily = MONO)
                    }
                },
                modifier = Modifier.weight(1f),
            ) {
                AppText(
                    truncatePathForDisplay(path),
                    color = tc.ts,
                    fontSize = 11.sp,
                    fontFamily = MONO,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            AppButton(
                "Reindex",
                onClick = { state.reindexSources(path) },
                variant = ButtonVariant.Secondary,
                enabled = File(path).absolutePath !in state.indexingFolders,
            )
            AppButton(
                "Info",
                onClick = { state.sourceFolderInfoEditorTarget = path },
                variant = ButtonVariant.Secondary,
            )
            AppButton("Remove", onClick = { state.removeSourceFolder(path) }, variant = ButtonVariant.Secondary)
        }
        val folderStatus = state.sourceIndexStatusForFolder(path)
        AppText(
            if (folderStatus.builtAt == 0L) {
                "Not indexed yet"
            } else if (folderStatus.configurationChanged) {
                "Configuration changed — reindex required"
            } else {
                "${folderStatus.fileCount} files · ${folderStatus.siteCount} call sites · " +
                    "indexed ${sourceIndexAgeLabel(folderStatus.builtAt)}"
            },
            color = tc.td,
            fontSize = 10.sp,
            fontFamily = UI,
        )
        val configurationNames = state.sourceConfigurationsForFolder(path).map { it.name }
        if (configurationNames.isNotEmpty()) {
            AppText(
                "Configurations: ${configurationNames.joinToString()}",
                color = tc.td,
                fontSize = 10.sp,
                fontFamily = UI,
            )
        } else if (state.settings.sourceLogConfigurations.isNotEmpty()) {
            AppText(
                "No logging configuration assigned — assign one below, then reindex",
                color = tc.ac,
                fontSize = 10.sp,
                fontFamily = UI,
            )
        }
        if (folderStatus.changedFileCount > 0) {
            AppText(
                "${folderStatus.changedFileCount} files changed — reindex recommended",
                color = tc.ac,
                fontSize = 10.sp,
                fontFamily = UI,
            )
        }
    }
}

@Composable
private fun AiProviderSettingsSection(state: AppState, onGuardChange: (AiProviderGuard) -> Unit = {}) {
    val tc = tc()
    // Settings migration normally guarantees this invariant, but keeping the editor safe during
    // a transient empty state avoids a compose-time crash while a profile list is being replaced.
    val profiles = normalizeAiProviderProfiles(state.settings.aiProviderProfiles)
    var editingProfileId by remember { mutableStateOf(profiles.firstOrNull { it.selected }?.id.orEmpty()) }
    val profile = profiles.firstOrNull { it.id == editingProfileId }
        ?: profiles.firstOrNull { it.selected }
        ?: profiles.first()
    var name by remember(profile.id, profile.displayName) { mutableStateOf(profile.displayName) }
    var endpoint by remember(profile.id, profile.baseUrl) { mutableStateOf(profile.baseUrl) }
    var model by remember(profile.id, profile.model) { mutableStateOf(profile.model) }
    var kind by remember(profile.id, profile.kind) { mutableStateOf(profile.kind) }
    var executablePath by remember(profile.id, profile.executablePath) { mutableStateOf(profile.executablePath) }
    var reasoningEffort by remember(profile.id, profile.reasoningEffort) { mutableStateOf(profile.reasoningEffort) }
    // Tracks (endpoint text, acknowledged) as a pair rather than a bare boolean so the checkbox
    // reflects the *live* endpoint field: editing the endpoint away from what was last acknowledged
    // shows unchecked again, without a Save round-trip. `acknowledged` below is a plain derived val
    // (never written to directly), so there's no write-triggers-recompute-triggers-write loop risk.
    var ackState by remember(profile.id, profile.remoteDisclosureAcknowledged) {
        mutableStateOf(profile.baseUrl.trim() to profile.remoteDisclosureAcknowledged)
    }
    var apiKey by remember(profile.id) { mutableStateOf(state.aiProviderApiKey(profile.id)) }
    var validationError by remember(profile.id) { mutableStateOf<String?>(null) }
    var connectionTest by remember(profile.id, kind) { mutableStateOf<ModelDiscoveryResult?>(null) }
    var accountCliCheck by remember(profile.id, kind) { mutableStateOf<com.openlog.ai.AccountCliCheck?>(null) }
    var modelDiscovery by remember(profile.id, kind) { mutableStateOf<ModelDiscoveryResult?>(null) }
    var testingConnection by remember(profile.id, kind) { mutableStateOf(false) }
    val coroutineScope = rememberCoroutineScope()
    val acknowledged = if (endpoint.trim() == ackState.first) ackState.second else false
    val draft = profile.copy(
        displayName = name.trim().ifBlank { "OpenAI-compatible" },
        baseUrl = endpoint.trim(),
        model = model.trim(),
        remoteDisclosureAcknowledged = acknowledged,
        kind = kind,
        executablePath = executablePath.trim(),
        reasoningEffort = reasoningEffort,
    )
    // Discovery used to only run when the user opened the model dropdown, so switching back to a
    // profile that already had a model+reasoning effort saved showed the model but silently hid
    // the reasoning dropdown (modelDiscovery resets to null on every profile/kind switch) until
    // the model dropdown was clicked again. Running it eagerly keeps the two in sync.
    LaunchedEffect(profile.id, kind) {
        modelDiscovery = state.aiSidebarRuntime.discoverModels(draft, apiKey)
    }
    val isDirty = draft != profile
    SideEffect { onGuardChange(AiProviderGuard(isDirty, profile.displayName) { state.updateAiProviderProfile(draft) }) }

    // Any navigation away from a dirty draft (switching provider, adding a new one, or removing a
    // *different* profile) goes through this instead of acting immediately, so edits are never
    // silently lost. Switching settings section or closing the dialog is guarded the same way, one
    // level up in SettingsDialog, via the published AiProviderGuard above.
    var pendingNavigation by remember { mutableStateOf<(() -> Unit)?>(null) }
    var navigationSaveError by remember { mutableStateOf<String?>(null) }
    var pendingDeleteProfileId by remember { mutableStateOf<String?>(null) }

    fun navigateOrConfirm(action: () -> Unit) {
        if (isDirty) {
            navigationSaveError = null
            pendingNavigation = action
        } else {
            action()
        }
    }

    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        AppText("Providers", color = tc.td, fontSize = 10.sp, fontFamily = UI)
        // Same dropdown-plus-"+" treatment as the AI panel's own provider switcher
        // (AiProviderControls in AiSidebar.kt), for a consistent look and so both places share one
        // battle-tested selection path instead of a second, subtly different one.
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            AiProviderDropdown(
                profiles = profiles,
                selected = profile,
                onSelect = { id ->
                    navigateOrConfirm {
                        state.selectAiProviderProfile(id)
                        editingProfileId = id
                    }
                },
                modifier = Modifier.weight(1f),
            )
            HoverBox(
                modifier = Modifier.size(28.dp).clip(RoundedCornerShape(6.dp)).border(1.dp, tc.br, RoundedCornerShape(6.dp)),
                onClick = { navigateOrConfirm { editingProfileId = state.addAiProviderProfile().id } },
            ) {
                AppText("+", color = tc.td, fontSize = 16.sp, modifier = Modifier.align(Alignment.Center))
            }
        }
        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            AppText("Provider type", color = tc.td, fontSize = 10.sp, fontFamily = UI)
            SegmentedControl(
                options = com.openlog.model.AiProviderKind.entries.map { it.label },
                selectedIndices = setOf(com.openlog.model.AiProviderKind.entries.indexOf(kind)),
                onToggle = { index ->
                    val selectedKind = com.openlog.model.AiProviderKind.entries[index]
                    if (selectedKind != kind) {
                        val preset = com.openlog.model.defaultAiProviderProfile(selectedKind)
                        kind = selectedKind
                        name = preset.displayName
                        endpoint = preset.baseUrl
                        model = preset.model
                        executablePath = preset.executablePath
                        reasoningEffort = preset.reasoningEffort
                        ackState = endpoint.trim() to false
                    }
                },
            )
            AppText("Profile name", color = tc.td, fontSize = 10.sp, fontFamily = UI)
            InlineField(name, { name = it }, "LM Studio (local)", Modifier.fillMaxWidth(), fontSize = 12.sp)
            if (kind.usesHttpEndpoint) {
                AppText("Endpoint", color = tc.td, fontSize = 10.sp, fontFamily = UI)
                InlineField(endpoint, { endpoint = it }, "https://api.example.com/v1", Modifier.fillMaxWidth(), fontSize = 12.sp)
            } else {
                AppText(
                    "Uses a local CLI account already signed in on this computer.",
                    color = tc.td,
                    fontSize = 10.sp,
                )
                AppText("CLI executable (optional)", color = tc.td, fontSize = 10.sp, fontFamily = UI)
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
                    InlineField(
                        executablePath,
                        { executablePath = it },
                        if (kind == AiProviderKind.CODEX_ACCOUNT) "codex" else "claude",
                        Modifier.weight(1f),
                        fontSize = 12.sp,
                    )
                    AppButton(
                        "Detect",
                        onClick = {
                            val detected = com.openlog.ai.LocalAccountCli.detectExecutable(kind)
                            if (detected != null) {
                                executablePath = detected
                                accountCliCheck = com.openlog.ai.AccountCliCheck(true, "Found local CLI: $detected")
                            } else {
                                val appPath = com.openlog.ai.LocalAccountCli.detectedDesktopApp(kind)
                                accountCliCheck = com.openlog.ai.AccountCliCheck(
                                    false,
                                    if (kind == AiProviderKind.CLAUDE_CODE_ACCOUNT && appPath != null) {
                                        "Claude desktop app was found at $appPath, but Claude Code CLI is not installed."
                                    } else {
                                        "No ${if (kind == AiProviderKind.CODEX_ACCOUNT) "Codex" else "Claude Code"} CLI was detected."
                                    },
                                )
                            }
                        },
                        variant = ButtonVariant.Secondary,
                    )
                    AppButton(
                        "Browse",
                        onClick = { state.pickAccountCliExecutable()?.let { executablePath = it } },
                        variant = ButtonVariant.Secondary,
                    )
                }
                AppText(
                    if (kind == AiProviderKind.CODEX_ACCOUNT) {
                        "Choose a CLI executable, not an app bundle. On macOS Detect can use ChatGPT's bundled Codex CLI."
                    } else {
                        "Choose the Claude Code CLI executable. Claude desktop app bundles cannot run managed panel requests."
                    },
                    color = tc.td,
                    fontSize = 10.sp,
                    maxLines = 2,
                )
            }
            // Not gated by provider kind: any provider whose discovered model reports reasoning
            // efforts gets the dropdown, so Codex, Anthropic, and OpenAI-compatible endpoints
            // (including reasoning-capable local models served via LM Studio) all work the same way.
            val discoveredModel = (modelDiscovery as? ModelDiscoveryResult.Available)
                ?.models
                ?.firstOrNull { it.id == model }
            val reasoningEfforts = discoveredModel?.reasoningEfforts.orEmpty()
            val showReasoningDropdown = discoveredModel != null && reasoningEfforts.isNotEmpty()
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                AppText(
                    if (kind.usesHttpEndpoint) "Model (optional)" else "Model (optional override)",
                    color = tc.td, fontSize = 10.sp, fontFamily = UI,
                    modifier = Modifier.weight(1f),
                )
                if (showReasoningDropdown) {
                    AppText("Reasoning effort", color = tc.td, fontSize = 10.sp, fontFamily = UI, modifier = Modifier.weight(1f))
                }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                AiModelDropdown(
                    model = model,
                    discovery = modelDiscovery,
                    onDiscoverModels = {
                        coroutineScope.launch(Dispatchers.IO) {
                            modelDiscovery = state.aiSidebarRuntime.discoverModels(draft, apiKey)
                        }
                    },
                    onPickModel = { selectedModel ->
                        model = selectedModel
                        val supportedEfforts = (modelDiscovery as? ModelDiscoveryResult.Available)
                            ?.models
                            ?.firstOrNull { it.id == selectedModel }
                            ?.reasoningEfforts
                            .orEmpty()
                        if (reasoningEffort !in supportedEfforts) reasoningEffort = ""
                    },
                    modifier = Modifier.weight(1f),
                )
                if (showReasoningDropdown) {
                    AiReasoningEffortDropdown(
                        efforts = reasoningEfforts,
                        selected = reasoningEffort,
                        onPick = { reasoningEffort = it },
                        modifier = Modifier.weight(1f),
                    )
                }
            }
            if (discoveredModel != null && reasoningEfforts.isEmpty()) {
                AppText(
                    "This model does not expose configurable reasoning effort.",
                    color = tc.td,
                    fontSize = 10.sp,
                    maxLines = 2,
                )
            }
            if (kind == AiProviderKind.CLAUDE_CODE_ACCOUNT) {
                AppText(
                    "Claude Code cannot list models enabled for this account. Sonnet, Opus, and Haiku are " +
                        "documented aliases; blank uses your account default, and you can enter a full model id.",
                    color = tc.td,
                    fontSize = 10.sp,
                    maxLines = 3,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
            if (kind.usesApiKey) {
                AppText("API key — this session only; it is never saved", color = tc.td, fontSize = 10.sp, fontFamily = UI)
                InlineField(
                    apiKey,
                    { value -> apiKey = value; state.setAiProviderApiKey(profile.id, value) },
                    "Required for cloud API providers",
                    Modifier.fillMaxWidth(),
                    fontSize = 12.sp,
                    visualTransformation = PasswordVisualTransformation(),
                )
            }
        }
        val endpointHost = runCatching { java.net.URI(endpoint.trim()).host.orEmpty() }.getOrDefault("")
        if (kind.usesHttpEndpoint && endpoint.isNotBlank() && !com.openlog.ai.isLoopbackHost(endpointHost)) {
            CheckRow(acknowledged, { ackState = endpoint.trim() to !acknowledged }) {
                // (SEC-4) Plain-HTTP-only clause added: the original text covered log/source
                // content leaving the device but not that a non-HTTPS endpoint also puts the API
                // key itself on the wire unencrypted — a distinct, credential-level risk.
                AppText(
                    "I understand logs, source code, paths, and tool results may leave this " +
                        "device, and that a plain HTTP (non-HTTPS) endpoint also sends my API key unencrypted.",
                    color = tc.td,
                    fontSize = 10.sp,
                    maxLines = 3,
                )
            }
        }
        validationError?.let { AppText(it, color = DANGER_RED, fontSize = 10.sp) }
        connectionTest?.let { result ->
            when (result) {
                is ModelDiscoveryResult.Available -> AppText(
                    "Reachable — ${result.models.size} model(s) found.",
                    color = tc.ac,
                    fontSize = 10.sp,
                )
                is ModelDiscoveryResult.Unavailable -> AppText(result.message, color = DANGER_RED, fontSize = 10.sp)
            }
        }
        accountCliCheck?.let { result ->
            AppText(
                result.message,
                color = if (result.isReady) tc.ac else DANGER_RED,
                fontSize = 10.sp,
                maxLines = 3,
                modifier = Modifier.fillMaxWidth(),
            )
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            // Secondary, not Primary — elsewhere in Settings, Primary is reserved for the
            // dialog's own "Done" action, not per-section actions like this one.
            AppButton(
                "Save profile",
                onClick = { validationError = state.updateAiProviderProfile(draft) },
            )
            // Reachability is a pure network probe against the current form fields: it never
            // saves, validates, or otherwise touches the stored profile, so it works whether or
            // not the endpoint would currently pass Save (e.g. an unacknowledged remote endpoint).
            AppButton(
                if (testingConnection) "Checking…" else if (kind.usesHttpEndpoint) "Test connection" else "Check local CLI",
                onClick = {
                    val testProfile = draft
                    val testKey = apiKey
                    testingConnection = true
                    connectionTest = null
                    accountCliCheck = null
                    coroutineScope.launch(Dispatchers.IO) {
                        if (!testProfile.kind.usesHttpEndpoint) {
                            accountCliCheck = com.openlog.ai.checkAccountCli(testProfile)
                        } else {
                            val provider = when (testProfile.kind) {
                                com.openlog.model.AiProviderKind.ANTHROPIC_API ->
                                    com.openlog.ai.AnthropicMessagesProvider(testProfile.baseUrl, testKey)
                                else -> OpenAiCompatibleProvider(testProfile, testKey)
                            }
                            connectionTest = try {
                                provider.listModels()
                            } finally {
                                provider.close()
                            }
                        }
                        testingConnection = false
                    }
                },
                variant = ButtonVariant.Secondary,
                enabled = !testingConnection && (!kind.usesHttpEndpoint || endpoint.isNotBlank()),
            )
            if (profiles.size > 1) {
                AppButton(
                    "Remove",
                    onClick = { pendingDeleteProfileId = profile.id },
                    variant = ButtonVariant.Secondary,
                    isDanger = true,
                )
            }
        }
        CompactSetting("Max tool rounds per request") {
            val roundLimits = listOf(12, 25, 50, 100, 200, 500)
            ListStepper(
                options = roundLimits,
                value = state.settings.aiMaxToolRounds,
                onChange = { v -> state.updateSettings { it.copy(aiMaxToolRounds = v) } },
            )
        }
        AppText(
            "A multi-step investigation (filtering, reading lines, then writing a note) can take " +
                "many tool calls, especially with a smaller local model. Raise this if a request stops " +
                "with \"tool rounds\" before it finishes.",
            color = tc.td,
            fontSize = 10.sp,
            maxLines = 3,
        )
    }

    pendingDeleteProfileId?.let { id ->
        val targetName = profiles.firstOrNull { it.id == id }?.displayName ?: "this provider"
        SettingsConfirmDialog(
            title = "Remove provider profile?",
            message = "Delete \"$targetName\" from AI providers. This can't be undone.",
            onDismissRequest = { pendingDeleteProfileId = null },
        ) {
            DialogActionButton("Delete", active = true, danger = true) {
                state.removeAiProviderProfile(id)
                editingProfileId = state.settings.aiProviderProfiles.first { it.selected }.id
                pendingDeleteProfileId = null
            }
            DialogActionButton("Cancel", active = false) { pendingDeleteProfileId = null }
        }
    }

    pendingNavigation?.let { action ->
        SettingsConfirmDialog(
            title = "Save changes to ${profile.displayName}?",
            message = "You changed this provider's settings without saving. Save them before continuing?",
            error = navigationSaveError,
            onDismissRequest = { pendingNavigation = null; navigationSaveError = null },
        ) {
            DialogActionButton("Save", active = true) {
                val err = state.updateAiProviderProfile(draft)
                if (err == null) {
                    pendingNavigation = null
                    navigationSaveError = null
                    action()
                } else {
                    navigationSaveError = err
                }
            }
            DialogActionButton("Discard", active = false, danger = true) {
                pendingNavigation = null
                navigationSaveError = null
                action()
            }
            DialogActionButton("Cancel", active = false) { pendingNavigation = null; navigationSaveError = null }
        }
    }
}

@Composable
private fun CustomAiCommandsSettingsSection(state: AppState) {
    val tc = tc()
    var pendingDeleteCommand by remember { mutableStateOf<CustomAiCommand?>(null) }
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        AppButton(
            "Add command",
            onClick = { state.customCommandEditorTarget = CustomAiCommand("", "") },
            variant = ButtonVariant.Secondary,
        )
        SettingsScrollableRows {
            if (state.customAiCommands.isEmpty()) {
                AppText(
                    "(none yet — add one to invoke it as a button in Actions or by typing /name in the chat box)",
                    color = tc.td,
                    fontSize = 11.sp,
                )
            } else {
                state.customAiCommands.forEach { command ->
                    Row(
                        Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Column(Modifier.weight(1f)) {
                            AppText("/${command.name}", color = tc.tx, fontSize = 11.sp, fontFamily = MONO)
                            val preview = command.promptTemplate.lineSequence().firstOrNull().orEmpty()
                            if (preview.isNotBlank()) {
                                AppText(preview, color = tc.td, fontSize = 10.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            }
                        }
                        AppButton("Edit", onClick = { state.customCommandEditorTarget = command }, variant = ButtonVariant.Secondary)
                        AppButton(
                            "Delete",
                            onClick = { pendingDeleteCommand = command },
                            variant = ButtonVariant.Secondary,
                            isDanger = true,
                        )
                    }
                }
            }
        }
    }
    pendingDeleteCommand?.let { command ->
        SettingsConfirmDialog(
            title = "Delete AI command?",
            message = "Delete /${command.name} from AI commands. This can't be undone.",
            onDismissRequest = { pendingDeleteCommand = null },
        ) {
            DialogActionButton("Delete", active = true, danger = true) {
                state.deleteCustomAiCommand(command.name)
                pendingDeleteCommand = null
            }
            DialogActionButton("Cancel", active = false) { pendingDeleteCommand = null }
        }
    }
}

// Reuses MS_PER_MINUTE/MS_PER_SECOND from McpInfoDialog.kt (same package); only the hour/day/
// absolute-date tiers below are new, since agoLabel() there only ever needs seconds/minutes for
// "last seen" client freshness.
internal const val MS_PER_HOUR = 60 * MS_PER_MINUTE
internal const val MS_PER_DAY = 24 * MS_PER_HOUR

internal fun sourceIndexAgeLabel(builtAt: Long): String {
    val delta = System.currentTimeMillis() - builtAt
    return when {
        delta < AGO_JUST_NOW_MS -> "just now"
        delta < MS_PER_MINUTE -> "${delta / MS_PER_SECOND}s ago"
        delta < MS_PER_HOUR -> "${delta / MS_PER_MINUTE} min ago"
        delta < MS_PER_DAY -> "${delta / MS_PER_HOUR} h ago"
        else -> java.text.SimpleDateFormat("MMM d, yyyy", java.util.Locale.US).format(java.util.Date(builtAt))
    }
}

// The repo's own .mcp.json can get away with a relative "mcp-server/src/index.ts" because tools
// that auto-discover it (Claude Code) spawn the server with cwd already at the project root.
// This copy-for-other-tools snippet can't assume that — a client like LM Studio spawns MCP
// servers from ITS OWN working directory, so a relative path there resolves to nothing and the
// server fails to start.
//
// user.dir is only a reliable stand-in for "the project root" during an unpackaged dev run
// (./gradlew desktopRun sets the JVM's working directory there). The control server can also be
// turned on from Settings in a normal installed .dmg/.deb/.msi — that's the common case, not a
// dev-only path — and there user.dir is whatever the OS handed the launched app (often "/" for a
// openLog serves MCP natively over Streamable HTTP at /mcp — any MCP client (LM Studio, Claude
// Code, Codex) connects with just this URL, no Node bridge / npm / repo checkout to install. The
// snippet is the standard mcpServers-with-url form those clients accept. `token` is required on
// every request (see debug/ControlServer.kt's start()); it rides along as a `headers` block the
// same way any bearer-token MCP server config does, so "paste this JSON" keeps working end to end.
internal fun mcpConfigSnippet(port: Int, token: String): String =
    """
    {
      "mcpServers": {
        "openlog-control": {
          "url": "${mcpUrl(port)}",
          "headers": {
            "Authorization": "Bearer $token"
          }
        }
      }
    }
    """.trimIndent()

internal fun mcpUrl(port: Int): String = "http://127.0.0.1:$port/mcp"

@Composable
internal fun RowWrapControl(auto: Boolean, wrapChars: Int, onToggleAuto: () -> Unit, onWrapCharsChange: (Int) -> Unit) {
    val tc = tc()
    var wrapLimitText by remember(wrapChars) { mutableStateOf(wrapChars.toString()) }
    Row(
        Modifier
            .border(0.5.dp, tc.br, RoundedCornerShape(6.dp))
            .clip(RoundedCornerShape(6.dp)),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            contentAlignment = Alignment.Center,
            modifier = Modifier
                .height(28.dp)
                .background(if (auto) tc.ac.copy(alpha = .2f) else Color.Transparent)
                .clickable(onClick = onToggleAuto)
                .padding(horizontal = 10.dp),
        ) {
            AppText(
                "Auto",
                color = if (auto) tc.ac else tc.ts,
                fontSize = 12.sp,
                fontWeight = if (auto) FontWeight.Medium else FontWeight.Normal,
            )
        }
        Box(Modifier.width(0.5.dp).height(28.dp).background(tc.br))
        if (auto) {
            Box(Modifier.width(60.dp).height(28.dp).padding(horizontal = 8.dp), contentAlignment = Alignment.CenterStart) {
                AppText(wrapLimitText, color = tc.td.copy(alpha = 0.5f), fontSize = 12.sp, fontFamily = MONO)
            }
        } else {
            BasicTextField(
                value = wrapLimitText,
                onValueChange = { value ->
                    val digits = value.filter { it.isDigit() }.take(5)
                    wrapLimitText = digits
                    digits.toIntOrNull()?.let { onWrapCharsChange(it.coerceIn(80, 20_000)) }
                },
                textStyle = TextStyle(color = tc.tx, fontSize = 12.sp, fontFamily = MONO),
                cursorBrush = SolidColor(tc.ac),
                singleLine = true,
                modifier = Modifier.width(60.dp).height(28.dp).padding(horizontal = 8.dp),
                decorationBox = { inner -> Box(contentAlignment = Alignment.CenterStart) { inner() } },
            )
        }
    }
}

@Composable
internal fun ThemeGallery(state: AppState) {
    val tc = tc()
    val themeScroll = rememberScrollState()
    Box(Modifier.fillMaxWidth().height(148.dp)) {
        FlowRow(
            Modifier.fillMaxWidth().verticalScroll(themeScroll).padding(end = 12.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            ThemePreset.entries.forEach { preset ->
                ThemeWindowCard(
                    label = preset.label,
                    colors = themeColors(preset),
                    selected = preset == state.settings.theme,
                    onClick = { state.updateSettings { it.copy(theme = preset) } },
                )
            }
        }
        VerticalScrollbar(
            adapter = rememberScrollbarAdapter(themeScroll),
            modifier = Modifier.align(Alignment.CenterEnd).fillMaxHeight().width(6.dp),
            style = appScrollbarStyle(tc),
        )
    }
}

@Composable
internal fun ThemeWindowCard(label: String, colors: ThemeColors, selected: Boolean, onClick: () -> Unit) {
    val tc = tc()
    val shape = RoundedCornerShape(8.dp)
    var hovered by remember { mutableStateOf(false) }
    Column(
        Modifier.width(118.dp).height(66.dp)
            .clip(shape)
            .background(if (hovered && !selected) colors.ac.copy(.08f) else colors.bg)
            .border(1.dp, if (selected || hovered) colors.ac else tc.br, shape)
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                onClick = onClick,
            )
            .onPointerEvent(PointerEventType.Enter) { hovered = true }
            .onPointerEvent(PointerEventType.Exit) { hovered = false }
            .padding(5.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Row(
            Modifier.fillMaxWidth().height(14.dp).background(colors.p, RoundedCornerShape(5.dp)).padding(horizontal = 5.dp),
            horizontalArrangement = Arrangement.spacedBy(3.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            listOf(colors.ac, colors.seq1, colors.seq2).forEach { color ->
                Box(Modifier.size(4.dp).background(color, RoundedCornerShape(50)))
            }
        }
        Box(Modifier.fillMaxWidth().weight(1f).background(colors.p2, RoundedCornerShape(4.dp))) {
            Box(Modifier.align(Alignment.CenterStart).fillMaxHeight().width(4.dp).background(colors.ac, CORNER_SM))
            Row(
                Modifier.align(Alignment.BottomEnd).padding(4.dp),
                horizontalArrangement = Arrangement.spacedBy(3.dp),
            ) {
                Box(Modifier.size(8.dp).background(colors.seq1, CORNER_SM))
                Box(Modifier.size(8.dp).background(colors.seq2, CORNER_SM))
            }
        }
        AppText(
            text = label,
            color = if (selected) colors.ac else tc.ts,
            fontSize = 9.sp,
            fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
            overflow = TextOverflow.Ellipsis,
            maxLines = 1,
        )
    }
}

@Composable
internal fun AnnotationSettingsRow(state: AppState) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
        CompactSettingWithTooltip(
            label = "Auto-save",
            tooltip = "Saves note Markdown and its .ann sidecar after note changes.",
        ) {
            SegmentedControl(
                options = listOf("On", "Off"),
                selectedIndices = setOf(if (state.settings.autoExportNotes) 0 else 1),
                onToggle = { idx -> state.updateSettings { it.copy(autoExportNotes = idx == 0) } },
            )
        }
        CompactSettingWithTooltip(
            label = "Filter backups",
            tooltip = "Writes timestamped saved-filter backups after saved-filter changes.",
        ) {
            SegmentedControl(
                options = listOf("On", "Off"),
                selectedIndices = setOf(if (state.settings.autoSaveFilters) 0 else 1),
                onToggle = { idx -> state.updateSettings { it.copy(autoSaveFilters = idx == 0) } },
            )
        }
        CompactSetting("Number blocks") {
            SegmentedControl(
                options = listOf("On", "Off"),
                selectedIndices = setOf(if (state.settings.numberAnnotationBlocks) 0 else 1),
                onToggle = { idx -> state.updateSettings { it.copy(numberAnnotationBlocks = idx == 0) } },
            )
        }
        CompactSetting("Log blocks") {
            val styles = AnnotationLogBlockStyle.entries
            SegmentedControl(
                options = listOf("Indented", "{code:java}"),
                selectedIndices = setOf(styles.indexOf(state.settings.annotationLogBlockStyle)),
                onToggle = { idx -> state.updateSettings { it.copy(annotationLogBlockStyle = styles[idx]) } },
            )
        }
    }
}

@Composable
internal fun CompactSettingWithTooltip(
    label: String,
    tooltip: String,
    modifier: Modifier = Modifier,
    horizontalAlignment: Alignment.Horizontal = Alignment.Start,
    content: @Composable () -> Unit,
) {
    val tc = tc()
    Column(modifier, horizontalAlignment = horizontalAlignment, verticalArrangement = Arrangement.spacedBy(6.dp)) {
        TooltipArea(
            tooltip = {
                Box(
                    Modifier
                        .background(tc.p2, RoundedCornerShape(4.dp))
                        .border(0.5.dp, tc.br, RoundedCornerShape(4.dp))
                        .padding(horizontal = 8.dp, vertical = 4.dp),
                ) {
                    AppText(tooltip, color = tc.tx, fontSize = 11.sp, maxLines = 2)
                }
            },
        ) {
            AppText(label, color = tc.td, fontSize = 10.sp, fontFamily = UI, fontWeight = FontWeight.SemiBold)
        }
        content()
    }
}

@Composable
internal fun CompactSetting(
    label: String,
    modifier: Modifier = Modifier,
    horizontalAlignment: Alignment.Horizontal = Alignment.Start,
    content: @Composable () -> Unit,
) {
    val tc = tc()
    Column(modifier, verticalArrangement = Arrangement.spacedBy(6.dp), horizontalAlignment = horizontalAlignment) {
        AppText(label, color = tc.td, fontSize = 10.sp, fontFamily = UI, fontWeight = FontWeight.SemiBold)
        content()
    }
}
