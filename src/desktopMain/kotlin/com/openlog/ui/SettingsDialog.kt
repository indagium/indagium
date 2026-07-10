@file:OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class, androidx.compose.ui.ExperimentalComposeUiApi::class)

package com.openlog.ui

import androidx.compose.foundation.*
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.input.pointer.*
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.openlog.generated.BuildInfo
import com.openlog.model.*

// ── Settings dialog ───────────────────────────────────────────────────
@Composable
internal fun SettingsDialog(state: AppState, onDismiss: () -> Unit) {
    val tc = tc()
    val scroll = rememberScrollState()
    val shape = RoundedCornerShape(8.dp)
    LaunchedEffect(Unit) {
        state.refreshArchiveCacheInfo()
    }
    Box(
        Modifier.width(580.dp).heightIn(max = 860.dp)
            .clip(shape)
            .background(tc.p)
            .border(1.dp, tc.br, shape),
    ) {
        Column(
            Modifier.verticalScroll(scroll).padding(24.dp).padding(end = 8.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                AppText("Settings", color = tc.tx, fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
                CloseButton(onClick = onDismiss)
            }

            SettingsSectionHeader("Appearance")
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
                    "App cache",
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
                            "${truncatePathForDisplay(cachePath)} · ${formatByteSize(state.archiveCacheSizeBytes)}",
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
                CompactSetting("Font size", Modifier.weight(1f), horizontalAlignment = Alignment.End) {
                    ListStepper(
                        options = (10..16).toList(),
                        value = state.settings.fontSize,
                        onChange = { v -> state.updateSettings { it.copy(fontSize = v) } },
                    )
                }
            }

            SettingsSectionHeader("Editor behavior")
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

            SettingsSectionHeader("Export & annotations")
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
                        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            AppText("Word", color = tc.td, fontSize = 10.sp, fontFamily = UI)
                            InlineField(
                                state.settings.maskWordTarget,
                                { value -> state.updateSettings { it.copy(maskWordTarget = value) } },
                                "java",
                                Modifier.fillMaxWidth(),
                                fontSize = 12.sp,
                            )
                        }
                        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            AppText("Replacement", color = tc.td, fontSize = 10.sp, fontFamily = UI)
                            InlineField(
                                state.settings.maskWordReplacement,
                                { value -> state.updateSettings { it.copy(maskWordReplacement = value) } },
                                "j*ava",
                                Modifier.fillMaxWidth(),
                                fontSize = 12.sp,
                            )
                        }
                    }
                    AppText(
                        "Replaces the whole word \"${state.settings.maskWordTarget}\" when copying a note — " +
                            "{code:java} block markers are never touched.",
                        color = tc.td, fontSize = 10.sp, maxLines = 2,
                    )
                }
            }

            SettingsSectionHeader("Automation")
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

            SettingsSectionHeader("About")
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                AppText("Keyboard shortcuts", color = tc.td, fontSize = 10.sp, fontFamily = UI, fontWeight = FontWeight.SemiBold)
                // Deliberately doesn't close Settings first — stacks on top instead, so closing
                // this popup returns you to Settings rather than to the main window.
                AppButton("Show shortcuts…", onClick = { state.shortcutsOpen = true }, variant = ButtonVariant.Secondary)
            }
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                AppText("Version", color = tc.td, fontSize = 10.sp, fontFamily = UI, fontWeight = FontWeight.SemiBold)
                AppText(BuildInfo.APP_VERSION, color = tc.ts, fontSize = 11.sp, fontFamily = MONO)
            }
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                AppText("Author", color = tc.td, fontSize = 10.sp, fontFamily = UI, fontWeight = FontWeight.SemiBold)
                AppText(BuildInfo.APP_AUTHOR, color = tc.ts, fontSize = 11.sp, fontFamily = MONO)
            }
            Row(horizontalArrangement = Arrangement.End, modifier = Modifier.fillMaxWidth()) {
                AppButton("Done", onClick = onDismiss, variant = ButtonVariant.Primary)
            }
        }
        VerticalScrollbar(
            adapter = rememberScrollbarAdapter(scroll),
            modifier = Modifier.align(Alignment.CenterEnd).fillMaxHeight().padding(vertical = 4.dp),
            style = appScrollbarStyle(tc),
        )
    }
}

@Composable
internal fun SettingsSectionHeader(title: String) {
    val tc = tc()
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        AppText(title, color = tc.ts, fontSize = 11.sp, fontFamily = UI, fontWeight = FontWeight.SemiBold)
        Divider()
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
