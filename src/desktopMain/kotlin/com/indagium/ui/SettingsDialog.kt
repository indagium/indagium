@file:OptIn(
    androidx.compose.foundation.ExperimentalFoundationApi::class,
    androidx.compose.foundation.layout.ExperimentalLayoutApi::class,
    androidx.compose.ui.ExperimentalComposeUiApi::class,
)
@file:Suppress("MaxLineLength")

package com.indagium.ui

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.*
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Bolt
import androidx.compose.material.icons.outlined.Code
import androidx.compose.material.icons.outlined.Dashboard
import androidx.compose.material.icons.outlined.Description
import androidx.compose.material.icons.outlined.Mic
import androidx.compose.material.icons.outlined.Palette
import androidx.compose.material.icons.outlined.PhoneAndroid
import androidx.compose.material.icons.outlined.Psychology
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material.icons.outlined.Terminal
import androidx.compose.material.icons.outlined.Tune
import androidx.compose.material3.Icon
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.isShiftPressed
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.pointer.*
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.layout.layout
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.PopupProperties
import com.indagium.ai.CustomAiCommand
import com.indagium.ai.ModelDiscoveryResult
import com.indagium.ai.OpenAiCompatibleProvider
import com.indagium.ai.normalizeAiProviderProfiles
import com.indagium.diagram3.DiagramExportMode
import com.indagium.generated.BuildInfo
import com.indagium.model.*
import com.indagium.update.PROJECT_REPO_URL
import com.indagium.voice.VoiceLanguageCatalog
import com.indagium.voice.VoiceModelCatalog
import com.indagium.voice.VoiceModelInstallResult
import com.indagium.voice.VoiceModelInstaller
import com.indagium.voice.VoiceRecognitionEngines
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.util.UUID
import kotlin.math.roundToInt

// ── Settings dialog ───────────────────────────────────────────────────
// Left-hand nav lists every section; only the selected section's content renders on the
// right, so growing any one section (e.g. AI providers) no longer pushes every other
// section down a shared scroll. There's no standalone "About" entry — its former content
// (keyboard shortcuts, version, author) now lives in Editor behavior and the footer.

/** Published by [AiProviderSettingsSection] each recomposition so [SettingsDialog] can gate
 *  section switches and closing the dialog behind an unsaved-changes prompt without hoisting the
 *  section's entire edit-draft state up to the dialog. */
private const val ANCHOR_WAIT_FRAMES = 30

/** Which name dialog Settings › General has open. */
private sealed interface ProfileNameRequest {
    data object New : ProfileNameRequest

    data class Rename(val profile: ResolvedProfile) : ProfileNameRequest
}

// Width of the "Temporary data · size" / "App data · size" texts, so their buttons line up right after them.
private val STORAGE_TEXT_WIDTH = 210.dp
private val ANCHOR_SCROLL_MARGIN = 16.dp
private const val FLASH_MS = 1200

/** Keeps the content composed (so its state survives) but neither placed, drawn, hit-testable nor
 *  exposed to accessibility. */
private fun Modifier.composedButHidden(): Modifier = this
    .clearAndSetSemantics {}
    .layout { measurable, constraints ->
        measurable.measure(constraints)
        layout(0, 0) {}
    }

private class AiProviderGuard(val isDirty: Boolean, val profileName: String, val save: () -> String?)

internal enum class SettingsSection(val title: String, val icon: ImageVector) {
    General("General", Icons.Outlined.Dashboard),
    Appearance("Appearance", Icons.Outlined.Palette),
    EditorBehavior("Editor behavior", Icons.Outlined.Tune),
    ExportAnnotations("Export & annotations", Icons.Outlined.Description),
    Capture("Capture", Icons.Outlined.PhoneAndroid),
    Automation("Automation", Icons.Outlined.Bolt),
    AiProviders("AI providers", Icons.Outlined.Psychology),
    VoiceInput("Voice input", Icons.Outlined.Mic),
    CustomAiCommands("AI commands", Icons.Outlined.Terminal),
    SourceCode("Source code", Icons.Outlined.Code),
    Issues("Issues", Icons.Outlined.Bolt),
}

@Composable
internal fun SettingsDialog(state: AppState, onDismiss: () -> Unit, onRequestCloseChanged: (() -> Unit) -> Unit = {}) {
    val tc = tc()
    val shape = RoundedCornerShape(8.dp)
    var selectedSection by remember {
        mutableStateOf(state.requestedSettingsSection ?: SettingsSection.General)
    }
    val voiceInputSupported = true
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

    // Returns false when the switch was deferred behind the unsaved-changes prompt.
    fun requestSectionSwitch(target: SettingsSection): Boolean {
        if (selectedSection == SettingsSection.AiProviders && aiProviderGuard?.isDirty == true) {
            guardSaveError = null
            pendingSectionSwitch = target
            return false
        }
        selectedSection = target
        return true
    }

    // ── Settings search (ui/SettingsSearchIndex.kt, ui/SettingsSearchUi.kt) ──────────────────
    val visibleSections = remember { SettingsSection.entries.filter { it != SettingsSection.VoiceInput || voiceInputSupported }.toSet() }
    var query by remember { mutableStateOf("") }
    val searching = query.isNotBlank()
    val results = remember(query) { searchSettings(query, visibleSections) }
    val anchors = remember { SettingsAnchors() }
    var pendingAnchor by remember { mutableStateOf<String?>(null) }
    val searchFocus = remember { FocusRequester() }
    // Keeps the dialog's own key handling (Cmd/Ctrl+F, Esc) alive: a clicked nav item, result row
    // or dismissed popup would otherwise take keyboard focus with it and leave nothing focused.
    val rootFocus = remember { FocusRequester() }

    fun reclaimFocus() { runCatching { rootFocus.requestFocus() } }
    LaunchedEffect(Unit) { reclaimFocus() }

    fun openResult(entry: SettingsSearchEntry) {
        val reached = entry.section == selectedSection || requestSectionSwitch(entry.section)
        // A switch deferred behind the AI-provider prompt keeps the results on screen instead, so
        // the user can simply pick again once they've answered it.
        if (!reached) return
        query = ""
        pendingAnchor = entry.anchor
        reclaimFocus()
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

    val contentScroll = rememberScrollState()
    val density = LocalDensity.current
    // After a result click the section is composed again (and, for another section, for the first
    // time), so give it two frames to lay out before reading the anchor, then scroll and flash.
    LaunchedEffect(pendingAnchor) {
        val key = pendingAnchor ?: return@LaunchedEffect
        repeat(2) { withFrameNanos { } }
        var y: Float? = null
        var waited = 0
        while (y == null && waited < ANCHOR_WAIT_FRAMES) {
            y = anchors.yInContent(key)
            if (y == null) {
                withFrameNanos { }
                waited++
            }
        }
        // Cleared only at the end: the key of this very effect, so clearing it earlier would
        // cancel the scroll below. A newer result click changes the key and restarts it instead.
        val target = y
        if (target != null) {
            contentScroll.animateScrollTo((target - with(density) { ANCHOR_SCROLL_MARGIN.toPx() }).toInt().coerceAtLeast(0))
            anchors.flashKey = key
            anchors.flash.snapTo(1f)
            anchors.flash.animateTo(0f, tween(FLASH_MS, easing = LinearEasing))
        }
        pendingAnchor = null
    }

    Box(
        // 200 (sidebar) + 1 (divider) + 693 (content). 693 less the 24dp padding either side and
        // the 8dp scrollbar gutter leaves 637dp, tuned tight against ThemeGallery's FlowRow math
        // (118dp cards, 8dp gaps: 5 cards = 622dp) so five fit per row with the scrollbar close
        // against the 5th card, and the five workspace-profile cards split the same width evenly.
        Modifier.width(894.dp).height(620.dp)
            .clip(shape)
            .background(tc.p)
            .border(1.dp, tc.br, shape)
            .onPreviewKeyEvent { ev ->
                when {
                    ev.type != KeyEventType.KeyDown -> false
                    ev.isActionKey && !ev.isShiftPressed && ev.key == Key.F -> {
                        runCatching { searchFocus.requestFocus() }
                        true
                    }
                    // Esc first clears an active search; with no query it falls through to the
                    // dialog's own dismiss handling (and its unsaved-changes guard) unchanged.
                    ev.key == Key.Escape && query.isNotEmpty() -> {
                        query = ""
                        true
                    }
                    else -> false
                }
            }
            .focusRequester(rootFocus)
            .focusable(),
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
                    Modifier.width(200.dp).fillMaxHeight().padding(vertical = 12.dp, horizontal = 10.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    SettingsSearchField(
                        query = query,
                        onQueryChange = { query = it },
                        focusRequester = searchFocus,
                        onSubmit = { results.firstOrNull()?.let(::openResult) },
                    )
                    // Scrolls only if the section list ever outgrows the space between the search
                    // field and the shortcuts block below.
                    Column(
                        Modifier.weight(1f).fillMaxWidth().verticalScroll(rememberScrollState()),
                        verticalArrangement = Arrangement.spacedBy(2.dp),
                    ) {
                        if (searching) {
                            SettingsMenuItem(
                                title = "All results",
                                icon = Icons.Outlined.Search,
                                selected = true,
                                onClick = { runCatching { searchFocus.requestFocus() } },
                                badge = results.size,
                            )
                        }
                        val counts = results.groupingBy { it.section }.eachCount()
                        SettingsSection.entries.filter { it in visibleSections }.forEach { section ->
                            SettingsMenuItem(
                                title = section.title,
                                icon = section.icon,
                                selected = !searching && section == selectedSection,
                                onClick = {
                                    // Leaving the results for a section is a plain section switch.
                                    query = ""
                                    if (section != selectedSection) requestSectionSwitch(section)
                                    reclaimFocus()
                                },
                                badge = if (searching) counts[section] else null,
                                dimmed = searching && section !in counts,
                            )
                        }
                    }
                    Column(
                        Modifier.fillMaxWidth().padding(horizontal = 6.dp, vertical = 2.dp),
                        verticalArrangement = Arrangement.spacedBy(4.dp),
                    ) {
                        AppText(
                            "Keyboard shortcuts",
                            color = tc.td,
                            fontSize = 10.sp,
                            fontFamily = UI,
                            fontWeight = FontWeight.SemiBold,
                        )
                        // Deliberately doesn't close Settings first — stacks on top instead, so closing
                        // this popup returns you to Settings rather than to the main window.
                        AppButton(
                            "Show shortcuts…",
                            onClick = { state.shortcutsOpen = true },
                            variant = ButtonVariant.Secondary,
                            modifier = Modifier.fillMaxWidth(),
                        )
                        // Closes Settings first (unlike the shortcuts popup): the assistant is a dialog of
                        // its own. Unsaved AI-provider edits get the usual close prompt instead.
                        AppButton(
                            "Run setup assistant…",
                            onClick = {
                                if (selectedSection == SettingsSection.AiProviders && aiProviderGuard?.isDirty == true) {
                                    requestClose()
                                } else {
                                    state.rerunSetupAssistant()
                                }
                            },
                            variant = ButtonVariant.Secondary,
                            modifier = Modifier.fillMaxWidth(),
                        )
                    }
                }
                Box(Modifier.width(1.dp).fillMaxHeight().background(tc.br))
                Box(Modifier.weight(1f).fillMaxHeight()) {
                    if (searching) SettingsSearchResults(query, results, ::openResult, Modifier.fillMaxSize())
                    CompositionLocalProvider(LocalSettingsAnchors provides anchors) {
                        Column(
                            Modifier.fillMaxSize()
                                // While results are showing, the selected section stays composed but is
                                // neither placed nor drawn: leaving it out of the tree would drop its
                                // unsaved AI-provider edits, which the section-switch guard relies on.
                                .then(if (searching) Modifier.composedButHidden() else Modifier)
                                .verticalScroll(contentScroll)
                                .onGloballyPositioned { anchors.content = it }
                                .padding(24.dp).padding(end = 8.dp),
                            verticalArrangement = Arrangement.spacedBy(14.dp),
                        ) {
                            when (selectedSection) {
                                SettingsSection.General -> GeneralSettingsSection(state, ::reclaimFocus)
                                SettingsSection.Appearance -> AppearanceSettingsSection(state)
                                SettingsSection.Capture -> CaptureSettingsSection(state)
                                SettingsSection.EditorBehavior -> EditorBehaviorSettingsSection(state)
                                SettingsSection.Issues -> IssuesSettingsSection(state)
                                SettingsSection.ExportAnnotations -> ExportAnnotationsSettingsSection(state)
                                SettingsSection.Automation -> AutomationSettingsSection(state)
                                SettingsSection.AiProviders -> AiProviderSettingsSection(state) { aiProviderGuard = it }
                                SettingsSection.VoiceInput -> VoiceInputSettingsSection(state)
                                SettingsSection.CustomAiCommands -> CustomAiCommandsSettingsSection(state)
                                SettingsSection.SourceCode -> SourceCodeSettingsSection(state)
                            }
                        }
                    }
                    if (!searching) {
                        VerticalScrollbar(
                            adapter = rememberScrollbarAdapter(contentScroll),
                            modifier = Modifier.align(Alignment.CenterEnd).fillMaxHeight().padding(vertical = 4.dp),
                            style = appScrollbarStyle(tc),
                        )
                    }
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
                        AppText("  |  ", color = tc.td, fontSize = 10.sp, fontFamily = UI)
                        HoverBox(
                            modifier = Modifier.clip(RoundedCornerShape(4.dp)),
                            onClick = ::openProjectRepository,
                        ) {
                            Box(Modifier.padding(horizontal = 4.dp, vertical = 2.dp)) {
                                AppText("GitHub repository ↗", color = tc.ac, fontSize = 10.sp, fontFamily = UI)
                            }
                        }
                        AppText("  |  ", color = tc.td, fontSize = 10.sp, fontFamily = UI)
                        HoverBox(
                            modifier = Modifier.clip(RoundedCornerShape(4.dp)),
                            onClick = { state.openSupportDialog() },
                        ) {
                            Box(Modifier.padding(horizontal = 4.dp, vertical = 2.dp)) {
                                AppText("Support project ♥", color = tc.ac, fontSize = 10.sp, fontFamily = UI)
                            }
                        }
                    }
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Box(Modifier.width(52.dp)) {
                            AppText("Author", color = tc.td, fontSize = 10.sp, fontFamily = UI, fontWeight = FontWeight.SemiBold)
                        }
                        AppText(BuildInfo.APP_AUTHOR, color = tc.ts, fontSize = 10.sp, fontFamily = MONO)
                    }
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                    AppButton("License…", onClick = { state.licenseAgreementOpen = true }, variant = ButtonVariant.Secondary)
                    AppButton("Done", onClick = ::requestClose, variant = ButtonVariant.Primary)
                }
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

private fun openProjectRepository() {
    openExternalUrl(PROJECT_REPO_URL)
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
private fun SettingsMenuItem(
    title: String,
    icon: ImageVector,
    selected: Boolean,
    onClick: () -> Unit,
    // Search-result count for this nav row (shown as a small pill), and dimming for sections with none.
    badge: Int? = null,
    dimmed: Boolean = false,
) {
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
            .padding(horizontal = 10.dp, vertical = 6.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            icon,
            contentDescription = null,
            modifier = Modifier.size(16.dp),
            tint = if (selected) tc.ac else tc.td,
        )
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
            Box(
                Modifier.background(tc.ac.copy(alpha = .16f), RoundedCornerShape(50)).padding(horizontal = 6.dp, vertical = 1.dp),
            ) {
                AppText(badge.toString(), color = tc.ac, fontSize = 10.sp, fontWeight = FontWeight.SemiBold)
            }
        }
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

/** A text link in the accent colour with the hover highlight the footer's "GitHub repository" link uses. */
@Composable
internal fun AccentLink(label: String, onClick: () -> Unit, modifier: Modifier = Modifier) {
    val tc = tc()
    HoverBox(modifier = modifier.clip(RoundedCornerShape(4.dp)), onClick = onClick) {
        Box(Modifier.padding(horizontal = 6.dp, vertical = 4.dp)) {
            AppText(label, color = tc.ac, fontSize = 11.sp, fontFamily = UI)
        }
    }
}

/**
 * The font, size, scale and toolbar-label controls of Settings → Appearance; the setup assistant's
 * Look step shows this same row so the two can't drift. Controls take their natural width (the
 * font-family labels were truncated when four equal columns squeezed them) and wrap onto a second
 * line only when the window is too narrow for all four.
 */
@Composable
internal fun AppearanceBasicsRow(state: AppState) {
    SettingsControlRow {
        CompactSetting("Font family") {
            SegmentedControl(
                options = listOf("Monospace", "Proportional"),
                selectedIndices = setOf(if (state.settings.fontMono) 0 else 1),
                onToggle = { idx -> state.updateSettings { it.copy(fontMono = idx == 0) } },
            )
        }
        CompactSetting("Log font size", horizontalAlignment = Alignment.Start) {
            ListStepper(
                options = (10..24).toList(),
                value = state.settings.fontSize,
                onChange = { v -> state.updateSettings { it.copy(fontSize = v) } },
            )
        }
        CompactSetting("Interface scale", horizontalAlignment = Alignment.Start) {
            ListStepper(
                options = (MIN_INTERFACE_SCALE_PERCENT..MAX_INTERFACE_SCALE_PERCENT step 10).toList(),
                value = state.settings.interfaceScalePercent,
                onChange = { v -> state.updateSettings { it.copy(interfaceScalePercent = v) } },
            )
        }
        CompactSettingWithTooltip(
            label = "Toolbar labels",
            tooltip = "Hides text on the main toolbar buttons, leaving only their icons.",
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
private fun AppearanceSettingsSection(state: AppState) {
    val tc = tc()
    Column(Modifier.settingsAnchor("Theme"), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        AppText("Theme", color = tc.td, fontSize = 10.sp, fontFamily = UI, fontWeight = FontWeight.SemiBold)
        ThemeGallery(
            settings = state.settings,
            selected = state.settings.theme,
            onSelect = { preset -> preset?.let { state.updateSettings { s -> s.copy(theme = it) } } },
            height = null,
        )
    }
    AppearanceBasicsRow(state)
    if (isLinuxOs) {
        CompactSettingWithTooltip(
            label = "File picker",
            tooltip = "Automatic uses Compatibility X11 on Debian, Arch, Manjaro, and Fedora; " +
                "it uses Native GTK on Ubuntu and unrecognized distributions.",
        ) {
            val modes = LinuxFilePickerMode.entries
            SegmentedControl(
                options = modes.map(LinuxFilePickerMode::label),
                selectedIndices = setOf(modes.indexOf(state.settings.linuxFilePickerMode)),
                onToggle = { index -> state.updateSettings { it.copy(linuxFilePickerMode = modes[index]) } },
                modifier = Modifier.fillMaxWidth(),
                fillWidth = true,
            )
        }
        AppText(
            "Restart Indagium to apply file picker changes.",
            color = tc.td,
            fontSize = 10.sp,
            fontFamily = UI,
        )
    }
}

@Composable
private fun GeneralSettingsSection(state: AppState, reclaimFocus: () -> Unit) {
    val tc = tc()
    val selectedProfile = state.selectedWorkspaceProfile
    var nameRequest by remember { mutableStateOf<ProfileNameRequest?>(null) }
    var deleteRequest by remember { mutableStateOf<ResolvedProfile?>(null) }
    val profiles = WorkspaceProfile.entries.map { it.resolved() } + state.settings.customWorkspaceProfiles.map { it.resolved() }
    Column(Modifier.settingsAnchor("Workspace profile"), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        AppText("Workspace profile", color = tc.td, fontSize = 10.sp, fontFamily = UI, fontWeight = FontWeight.SemiBold)
        AppText(
            "Sets panels, filter placement, theme and font size in one step. Every setting stays editable afterwards.",
            color = tc.ts,
            fontSize = 11.sp,
            fontFamily = UI,
        )
        Spacer(Modifier.height(2.dp))
        WorkspaceProfileCards(
            profiles = profiles,
            selectedId = selectedProfile?.id,
            onSelect = state::applyResolvedProfile,
            onMenuClosed = reclaimFocus,
            menuFor = { profile ->
                listOf(
                    ProfileMenuAction("Rename…") { nameRequest = ProfileNameRequest.Rename(profile) },
                    ProfileMenuAction("Update from current setup") { state.updateWorkspaceProfileFromCurrent(profile.id) },
                    ProfileMenuAction("Export…") { state.exportWorkspaceProfile(profile) },
                    ProfileMenuAction("Delete…", danger = true) { deleteRequest = profile },
                )
            },
        )
        // Read straight from state on every recomposition: theme/font/layout edits made elsewhere
        // (Appearance, the panel toggles) all show up here without any extra plumbing.
        val differences = state.workspaceProfileDifferences()
        val customized = selectedProfile != null && differences.isNotEmpty()
        if (selectedProfile != null && customized) {
            val shape = RoundedCornerShape(6.dp)
            Column(
                Modifier.fillMaxWidth().background(tc.warnBg, shape).border(0.5.dp, tc.br, shape)
                    .padding(horizontal = 10.dp, vertical = 8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                AppText(
                    "Customized — ${differences.size} ${if (differences.size == 1) "setting differs" else "settings differ"} " +
                        "from ${selectedProfile.title}: " +
                        differences.joinToString(", "),
                    color = tc.tx,
                    fontSize = 11.sp,
                    fontFamily = UI,
                    maxLines = 3,
                )
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                    AppButton("Reset to profile", onClick = { state.applyResolvedProfile(selectedProfile) })
                    AppButton(
                        "Save as new profile…",
                        onClick = { nameRequest = ProfileNameRequest.New },
                        variant = ButtonVariant.Secondary,
                    )
                    if (selectedProfile.custom) {
                        AppButton(
                            "Update “${selectedProfile.title}”",
                            onClick = { state.updateWorkspaceProfileFromCurrent(selectedProfile.id) },
                            variant = ButtonVariant.Secondary,
                        )
                    }
                }
            }
        }
        Row(
            Modifier.settingsAnchor("Save as new profile"),
            horizontalArrangement = Arrangement.spacedBy(4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (!customized) {
                AccentLink("Save current setup as profile…", onClick = { nameRequest = ProfileNameRequest.New })
            }
            AccentLink(
                "Import profile…",
                onClick = {
                    state.importWorkspaceProfile()
                    reclaimFocus()
                },
                modifier = Modifier.settingsAnchor("Import profile"),
            )
            AccentLink(
                "Export current setup as .json",
                onClick = {
                    state.exportWorkspaceProfile(null)
                    reclaimFocus()
                },
                modifier = Modifier.settingsAnchor("Export profile"),
            )
        }
    }
    nameRequest?.let { request ->
        val taken = when (request) {
            ProfileNameRequest.New -> state.workspaceProfileNames()
            is ProfileNameRequest.Rename -> state.workspaceProfileNames() - request.profile.title
        }
        WorkspaceProfileNameDialog(
            title = if (request is ProfileNameRequest.Rename) "Rename profile" else "Save current setup as a profile",
            confirmLabel = if (request is ProfileNameRequest.Rename) "Rename" else "Save",
            initialName = (request as? ProfileNameRequest.Rename)?.profile?.title
                ?: uniqueProfileName("My profile", state.workspaceProfileNames()),
            takenNames = taken,
            onConfirm = { name ->
                when (request) {
                    ProfileNameRequest.New -> state.saveCurrentAsWorkspaceProfile(name)
                    is ProfileNameRequest.Rename -> state.renameWorkspaceProfile(request.profile.id, name)
                }
                nameRequest = null
                reclaimFocus()
            },
            onDismiss = {
                nameRequest = null
                reclaimFocus()
            },
        )
    }
    deleteRequest?.let { profile ->
        SettingsConfirmDialog(
            title = "Delete profile?",
            message = "Delete “${profile.title}”? The theme and panels you have now stay as they are. This can't be undone.",
            onDismissRequest = {
                deleteRequest = null
                reclaimFocus()
            },
        ) {
            DialogActionButton("Delete", active = true, danger = true) {
                state.deleteWorkspaceProfile(profile.id)
                deleteRequest = null
                reclaimFocus()
            }
            DialogActionButton("Cancel", active = false) {
                deleteRequest = null
                reclaimFocus()
            }
        }
    }
    Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
        SaveFolderKind.entries.forEach { kind -> SaveFolderSetting(state, kind) }
    }
    Column(Modifier.settingsAnchor("Storage"), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        AppText(
            "Storage",
            color = tc.td,
            fontSize = 10.sp,
            fontFamily = UI,
            fontWeight = FontWeight.SemiBold,
        )
        val appDataPath = state.appCachePath
        TooltipArea(
            tooltip = {
                Box(
                    Modifier
                        .background(tc.p2, RoundedCornerShape(4.dp))
                        .border(0.5.dp, tc.br, RoundedCornerShape(4.dp))
                        .padding(horizontal = 8.dp, vertical = 4.dp),
                ) {
                    AppText(appDataPath, color = tc.tx, fontSize = 11.sp, fontFamily = MONO)
                }
            },
        ) {
            AppText(
                truncatePathForDisplay(appDataPath),
                color = tc.ts,
                fontSize = 11.sp,
                fontFamily = MONO,
                overflow = TextOverflow.Ellipsis,
            )
        }
        Row(
            Modifier.settingsAnchor("Temporary data"),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            TooltipArea(
                tooltip = {
                    StorageInfoTooltip(
                        "Downloaded archive cache and app-managed notes. Clear temporary data removes these items.",
                    )
                },
                modifier = Modifier.width(STORAGE_TEXT_WIDTH),
            ) {
                AppText(
                    "Temporary data · ${formatByteSize(state.temporaryDataSizeBytes)}  ⓘ",
                    color = tc.ts,
                    fontSize = 11.sp,
                    fontFamily = MONO,
                )
            }
            AppButton("Clear temporary data", onClick = { state.requestClearCache() }, variant = ButtonVariant.Secondary)
        }
        Row(
            Modifier.settingsAnchor("App data"),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            TooltipArea(
                tooltip = {
                    StorageInfoTooltip(
                        "Everything Indagium stores in this folder: temporary data, settings, current session/autosave, " +
                            "saved filters, source and case indexes, diagnostics, and integration data.",
                    )
                },
                modifier = Modifier.width(STORAGE_TEXT_WIDTH),
            ) {
                AppText(
                    "App data · ${formatByteSize(state.appDataSizeBytes)}  ⓘ",
                    color = tc.ts,
                    fontSize = 11.sp,
                    fontFamily = MONO,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            AppButton("Reset app data…", onClick = { state.requestResetAppData() }, variant = ButtonVariant.Secondary, isDanger = true)
        }
    }
    state.autosaveError?.let { message ->
        AppText(message, color = DANGER_RED, fontSize = 11.sp, maxLines = 2)
    }
    val cardShape = RoundedCornerShape(6.dp)
    Row(
        Modifier.fillMaxWidth().settingsAnchor("Setup assistant")
            .background(tc.p2, cardShape).border(1.dp, tc.br, cardShape).padding(horizontal = 12.dp, vertical = 10.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            AppText("Setup assistant", color = tc.tx, fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
            AppText("Walk through profile, theme, folders and capture again.", color = tc.td, fontSize = 11.sp, maxLines = 2)
        }
        AppButton("Run setup again", onClick = { state.rerunSetupAssistant() }, variant = ButtonVariant.Secondary)
    }
}

/** The row for one of the save folders, wired exactly as Settings → General shows it; the setup
 *  assistant reuses it for the folders it offers. */
@Composable
internal fun SaveFolderSetting(state: AppState, kind: SaveFolderKind) {
    when (kind) {
        SaveFolderKind.ROOT -> SaveFolderRow(
            label = "Default save folder",
            tooltip = "Parent folder every other save location below defaults under when it isn't " +
                "set on its own. Defaults to your Documents folder.",
            explicitValue = state.settings.saveRootDir,
            effectivePath = state.effectiveSaveRootDir.absolutePath,
            onBrowse = { state.pickSaveFolder(SaveFolderKind.ROOT) },
            onReset = { state.resetSaveFolder(SaveFolderKind.ROOT) },
        )
        SaveFolderKind.ANALYSIS -> SaveFolderRow(
            label = "Analysis artifacts folder",
            tooltip = "Where analysis notes, filtered exports and split logs are saved. Auto-saved " +
                "notes are written here, created on first save.",
            explicitValue = state.settings.defaultSaveDir,
            effectivePath = state.effectiveAnalysisDirForDisplay().absolutePath,
            onBrowse = { state.pickSaveFolder(SaveFolderKind.ANALYSIS) },
            onReset = { state.resetSaveFolder(SaveFolderKind.ANALYSIS) },
        )
        SaveFolderKind.SESSIONS -> SaveFolderRow(
            label = "Capture sessions folder",
            tooltip = "Where new device captures are recorded. A change applies from the next " +
                "Start; sessions already recorded stay exactly where they are.",
            explicitValue = state.settings.captureSessionsDir,
            effectivePath = state.effectiveCaptureSessionsDir().absolutePath,
            onBrowse = { state.pickSaveFolder(SaveFolderKind.SESSIONS) },
            onReset = { state.resetSaveFolder(SaveFolderKind.SESSIONS) },
        )
        SaveFolderKind.SNAPSHOTS -> SaveFolderRow(
            label = "Snapshots folder",
            tooltip = "Destination for \"Save snapshot\" while a capture is recording.",
            explicitValue = state.settings.captureSnapshotsDir,
            effectivePath = state.effectiveCaptureSnapshotsDir().absolutePath,
            onBrowse = { state.pickSaveFolder(SaveFolderKind.SNAPSHOTS) },
            onReset = { state.resetSaveFolder(SaveFolderKind.SNAPSHOTS) },
        )
        SaveFolderKind.ZIP -> SaveFolderRow(
            label = "Saved captures folder (Save ZIP)",
            tooltip = "Destination for \"Save ZIP\" on a stopped or retained capture.",
            explicitValue = state.settings.captureZipDir,
            effectivePath = state.effectiveCaptureZipDirForDisplay().absolutePath,
            onBrowse = { state.pickSaveFolder(SaveFolderKind.ZIP) },
            onReset = { state.resetSaveFolder(SaveFolderKind.ZIP) },
        )
    }
}

/**
 * One row of the five-folder Save folders group (GeneralSettingsSection above): a labeled
 * tooltip, the effective path (dimmer with a "(default)" suffix when nothing is explicitly set —
 * [explicitValue] is null), a Browse button, and a Reset button that only shows once the folder
 * has actually been set to something other than its computed default.
 */
@Composable
internal fun SaveFolderRow(
    label: String,
    tooltip: String,
    explicitValue: String?,
    effectivePath: String,
    onBrowse: () -> Unit,
    onReset: () -> Unit,
) {
    val tc = tc()
    Column(Modifier.settingsAnchor(label), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        TooltipArea(
            tooltip = {
                Box(
                    Modifier
                        .background(tc.p2, RoundedCornerShape(4.dp))
                        .border(0.5.dp, tc.br, RoundedCornerShape(4.dp))
                        .padding(horizontal = 8.dp, vertical = 4.dp),
                ) {
                    AppText(tooltip, color = tc.tx, fontSize = 11.sp, maxLines = 3)
                }
            },
        ) {
            AppText(label, color = tc.td, fontSize = 10.sp, fontFamily = UI, fontWeight = FontWeight.SemiBold)
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            val displayText = truncatePathForDisplay(effectivePath).let {
                if (explicitValue != null) it else "$it  (default)"
            }
            val pathText: @Composable () -> Unit = {
                AppText(
                    displayText,
                    color = if (explicitValue != null) tc.ts else tc.td,
                    fontSize = 11.sp,
                    fontFamily = MONO,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            TooltipArea(
                tooltip = {
                    Box(
                        Modifier
                            .background(tc.p2, RoundedCornerShape(4.dp))
                            .border(0.5.dp, tc.br, RoundedCornerShape(4.dp))
                            .padding(horizontal = 8.dp, vertical = 4.dp),
                    ) {
                        AppText(effectivePath, color = tc.tx, fontSize = 11.sp, fontFamily = MONO)
                    }
                },
                modifier = Modifier.weight(1f),
            ) { pathText() }
            AppButton("Browse", onClick = onBrowse)
            if (explicitValue != null) AppButton("Reset", onClick = onReset)
        }
    }
}

@Composable
private fun StorageInfoTooltip(text: String) {
    val tc = tc()
    Box(
        Modifier
            .width(330.dp)
            .background(tc.p2, RoundedCornerShape(4.dp))
            .border(0.5.dp, tc.br, RoundedCornerShape(4.dp))
            .padding(horizontal = 8.dp, vertical = 5.dp),
    ) {
        AppText(text, color = tc.tx, fontSize = 11.sp, maxLines = 4)
    }
}

@Composable
private fun EditorBehaviorSettingsSection(state: AppState) {
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(16.dp)) {
        // Four rows of four on one grid (SettingsGrid): columns are content-sized with equal gaps,
        // so cells line up as columns and the last column ends on the right content edge.
        // Reserve an identical two-line label area in every cell of the last row so its controls
        // align even though one label wraps.
        val finalRowLabelAreaHeight = 28.dp

        SettingsGrid(columns = 4, rowSpacing = 16.dp) {
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
            CompactSettingWithTooltip(
                label = "Minimap",
                tooltip = "Replaces the scrollbar with a Sublime-style text minimap — a miniature of each line " +
                    "colored by level. Click or drag on it to jump to that part of the file.",
            ) {
                SegmentedControl(
                    options = listOf("On", "Off"),
                    selectedIndices = setOf(if (state.settings.showMinimap) 0 else 1),
                    onToggle = { idx -> state.updateSettings { it.copy(showMinimap = idx == 0) } },
                )
            }
            CompactSettingWithTooltip(
                label = "Follow live logs",
                tooltip = "Keeps the view pinned to the newest line while a tab is live-watching " +
                    "(Start Live Watching). Scrolling up to read earlier lines pauses following; " +
                    "scrolling back down to the last line resumes it.",
            ) {
                SegmentedControl(
                    options = listOf("On", "Off"),
                    selectedIndices = setOf(if (state.settings.autoScrollWhileTailing) 0 else 1),
                    onToggle = { idx -> state.updateSettings { it.copy(autoScrollWhileTailing = idx == 0) } },
                )
            }
            CompactSettingWithTooltip(
                label = "Regex summary",
                tooltip = "Shows retained Tags-mode selectors above the Regex field. This summary is informational and does not change filtering.",
            ) {
                SegmentedControl(
                    options = listOf("Off", "On"),
                    selectedIndices = setOf(if (state.settings.showRegexFilterSummary) 1 else 0),
                    onToggle = { index -> state.updateSettings { it.copy(showRegexFilterSummary = index == 1) } },
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
                label = "Process names in new tabs",
                tooltip = "Whether a newly opened tab starts with process names shown in place of numeric " +
                    "pids — resolved from the log's own \"Start proc\" lines. This is only the starting " +
                    "point: showing or hiding names afterwards applies to one tab at a time, from the log " +
                    "toolbar's options popup or a row's right-click menu, since two tabs are usually two " +
                    "different logs with two different sets of processes. Per-tab picks reset every session " +
                    "(pids are reused across runs, so a saved pick could silently point at the wrong process).",
            ) {
                SegmentedControl(
                    options = listOf("On", "Off"),
                    selectedIndices = setOf(if (state.settings.showProcessNamesInNewTabs) 0 else 1),
                    onToggle = { idx -> state.updateSettings { it.copy(showProcessNamesInNewTabs = idx == 0) } },
                )
            }
            CompactSettingWithTooltip(
                label = "Ctrl+F opens",
                tooltip = "Find bar highlights regex matches in place and jumps between them without hiding " +
                    "any rows. Tags/Regex instead focuses the corresponding filter field and hides " +
                    "non-matching rows. \"Ctrl+F opens Original\" below applies to all three.",
                modifier = Modifier.width(112.dp),
                labelMaxLines = 1,
                labelAreaHeight = finalRowLabelAreaHeight,
            ) {
                // Rules (CtrlFTarget.MESSAGE_RULE) dropped from the selector, not the enum —
                // an old token can still hold it. It remains valid for the shortcut, while the
                // dropdown falls back to its current Find bar label until the user selects one
                // of the three supported destinations.
                val tc = tc()
                val density = LocalDensity.current
                val targets = listOf(CtrlFTarget.FIND_BAR, CtrlFTarget.TAGS, CtrlFTarget.KEYWORD_REGEX)
                val labels = listOf("Find bar", "Tags", "Regex")
                var open by remember { mutableStateOf(false) }
                var suppressToggleUntilMs by remember { mutableStateOf(0L) }
                val selectedIndex = targets.indexOf(state.settings.ctrlFTarget)
                Box(Modifier.fillMaxWidth()) {
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
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            AppText(
                                labels.getOrElse(selectedIndex) { labels.first() },
                                color = tc.tx,
                                fontSize = 11.sp,
                                fontWeight = FontWeight.Medium,
                            )
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
                                Modifier.width(104.dp)
                                    .shadow(8.dp, RoundedCornerShape(8.dp))
                                    .background(tc.p, RoundedCornerShape(8.dp))
                                    .border(1.dp, tc.br, RoundedCornerShape(8.dp))
                                    .padding(4.dp),
                                verticalArrangement = Arrangement.spacedBy(2.dp),
                            ) {
                                targets.forEachIndexed { index, target ->
                                    val active = index == selectedIndex
                                    HoverBox(
                                        modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(5.dp)),
                                        baseBg = if (active) tc.abg else Color.Transparent,
                                        onClick = {
                                            open = false
                                            state.updateSettings { it.copy(ctrlFTarget = target) }
                                        },
                                    ) {
                                        AppText(
                                            labels[index],
                                            color = if (active) tc.ac else tc.tx,
                                            fontSize = 11.sp,
                                            fontWeight = if (active) FontWeight.SemiBold else FontWeight.Normal,
                                            modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
            }
            CompactSettingWithTooltip(
                label = "Ctrl+F opens Original",
                tooltip = "Reveals the active file's unfiltered Original panel whenever Ctrl+F opens, whichever " +
                    "of Find bar / Tags / Regex it opens with above. Single-tab view only — compare mode has no " +
                    "Original/Filtered split to reveal.",
                labelMaxLines = 2,
                labelAreaHeight = finalRowLabelAreaHeight,
            ) {
                SegmentedControl(
                    options = listOf("On", "Off"),
                    selectedIndices = setOf(if (state.settings.openUnfilteredOnCtrlF) 0 else 1),
                    onToggle = { idx -> state.updateSettings { it.copy(openUnfilteredOnCtrlF = idx == 0) } },
                )
            }
            CompactSettingWithTooltip(
                label = "Video follow readout",
                tooltip = "Shows the \"video → log → holding at ...\" diagnostic line under the video transport " +
                    "bar, explaining exactly what Follow is doing at the current playhead position.",
                labelMaxLines = 2,
                labelAreaHeight = finalRowLabelAreaHeight,
            ) {
                SegmentedControl(
                    options = listOf("On", "Off"),
                    selectedIndices = setOf(if (state.settings.showVideoFollowReadout) 0 else 1),
                    onToggle = { idx -> state.updateSettings { it.copy(showVideoFollowReadout = idx == 0) } },
                )
            }
            CompactSettingWithTooltip(
                label = "New video links:\ndouble-click seeks",
                tooltip = "The default for a newly linked log/video anchor. Each video link can then be toggled " +
                    "independently from its header; this does not affect existing links or Follow.",
                labelMaxLines = 2,
                labelAreaHeight = finalRowLabelAreaHeight,
            ) {
                SegmentedControl(
                    options = listOf("On", "Off"),
                    selectedIndices = setOf(if (state.settings.enableDoubleClickVideoSeekOnLink) 0 else 1),
                    onToggle = { idx -> state.updateSettings { it.copy(enableDoubleClickVideoSeekOnLink = idx == 0) } },
                )
            }
        }
    }
}

/**
 * A row of compact settings spread across the full content width: cells keep their natural width,
 * the first starts on the left edge, the last ends on the right edge, the space between is even,
 * and the row wraps onto a second line if the cells cannot fit. For several rows that should line
 * up as columns use [SettingsGrid] instead.
 */
@Composable
internal fun SettingsControlRow(modifier: Modifier = Modifier, content: @Composable () -> Unit) {
    FlowRow(
        modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) { content() }
}

/**
 * Compact settings on a grid with [columns] columns; the children fill it row by row. Each column is
 * exactly as wide as its widest cell (label or control) and the width left over is split into equal
 * gaps between columns, so the first column starts on the left edge, the last column's widest cell
 * ends on the right edge, and every column reads as one clean left edge. Cells are top-aligned
 * and start-aligned in their column.
 */
@Composable
internal fun SettingsGrid(
    columns: Int,
    modifier: Modifier = Modifier,
    rowSpacing: Dp = 10.dp,
    content: @Composable () -> Unit,
) {
    Layout(content, modifier.fillMaxWidth()) { measurables, constraints ->
        val width = constraints.maxWidth
        val placeables = measurables.map { it.measure(Constraints(maxWidth = width)) }
        val rows = placeables.chunked(columns)
        val columnWidths = List(columns) { column -> rows.maxOfOrNull { it.getOrNull(column)?.width ?: 0 } ?: 0 }
        val gap = if (columns > 1) ((width - columnWidths.sum()) / (columns - 1).toFloat()).coerceAtLeast(0f) else 0f
        val spacing = rowSpacing.roundToPx()
        val rowHeights = rows.map { row -> row.maxOfOrNull { it.height } ?: 0 }
        val height = rowHeights.sum() + spacing * (rows.size - 1).coerceAtLeast(0)
        layout(width, height) {
            var y = 0
            rows.forEachIndexed { rowIndex, row ->
                var x = 0f
                row.forEachIndexed { column, placeable ->
                    placeable.placeRelative(x.roundToInt(), y)
                    x += columnWidths[column] + gap
                }
                y += rowHeights[rowIndex] + spacing
            }
        }
    }
}

private fun customIssueRulesValidation(rules: List<CustomIssueRule>): String? {
    val builtInNames = CrashCategory.entries.map { it.name.lowercase() }.toSet() +
        setOf("all", "crashes", "anrs", "fatal exceptions", "exceptions", "others")
    rules.forEachIndexed { index, rule ->
        if (rule.name.isBlank()) return "Category ${index + 1} needs a name."
        if (rule.name.trim().lowercase() in builtInNames) return "Category ${index + 1} conflicts with a built-in Issues category."
        if (rule.regex.isBlank()) return "Category ${index + 1} needs a regex."
        runCatching { Regex(rule.regex) }.exceptionOrNull()?.let { return "Category ${index + 1}: ${it.message ?: "invalid regex"}" }
    }
    if (rules.groupingBy { it.name.trim().lowercase() }.eachCount().any { it.value > 1 }) {
        return "Category names must be unique."
    }
    return null
}

@Composable
private fun IssuesSettingsSection(state: AppState) {
    var drafts by remember { mutableStateOf(state.settings.customIssueRules) }
    val validation = customIssueRulesValidation(drafts)
    val dirty = drafts != state.settings.customIssueRules
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Column(Modifier.settingsAnchor("Custom issue categories"), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            AppText("Custom issue categories", color = tc().tx, fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
            AppText(
                "Each enabled regex is matched against a log tag or message and adds a clickable anchor to Issues. " +
                    "It does not change stack-trace folding or built-in crash detection.",
                color = tc().td, fontSize = 11.sp, maxLines = 3,
            )
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            AppText("Category", color = tc().td, fontSize = 10.sp, fontFamily = UI, modifier = Modifier.weight(0.8f))
            AppText("Tag or message regex", color = tc().td, fontSize = 10.sp, fontFamily = UI, modifier = Modifier.weight(1.2f))
            Spacer(Modifier.width(132.dp))
        }
        SettingsScrollableRows {
            if (drafts.isEmpty()) {
                AppText("No custom issue categories yet.", color = tc().td, fontSize = 11.sp)
            }
            drafts.forEachIndexed { index, rule ->
                Row(
                    Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    InlineField(
                        rule.name,
                        { value -> drafts = drafts.mapIndexed { i, current -> if (i == index) current.copy(name = value) else current } },
                        "Network timeouts",
                        Modifier.weight(0.8f),
                        fontSize = 12.sp,
                    )
                    InlineField(
                        rule.regex,
                        { value -> drafts = drafts.mapIndexed { i, current -> if (i == index) current.copy(regex = value) else current } },
                        "timeout\\\\s+after\\\\s+\\\\d+ms",
                        Modifier.weight(1.2f),
                        fontSize = 12.sp,
                    )
                    SegmentedControl(
                        options = listOf("On", "Off"),
                        selectedIndices = setOf(if (rule.enabled) 0 else 1),
                        onToggle = { selected ->
                            drafts = drafts.mapIndexed { i, current -> if (i == index) current.copy(enabled = selected == 0) else current }
                        },
                    )
                    AppButton(
                        "Remove",
                        onClick = { drafts = drafts.filterIndexed { i, _ -> i != index } },
                        variant = ButtonVariant.Secondary,
                        isDanger = true,
                    )
                }
            }
        }
        validation?.let { AppText(it, color = DANGER_RED, fontSize = 11.sp, maxLines = 2) }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            AppButton(
                "Add category",
                onClick = { drafts = drafts + CustomIssueRule(UUID.randomUUID().toString(), "", "") },
                variant = ButtonVariant.Secondary,
            )
            AppButton(
                "Save changes",
                onClick = { state.updateSettings { it.copy(customIssueRules = drafts.map { rule -> rule.copy(name = rule.name.trim()) }) } },
                enabled = dirty && validation == null,
            )
            if (dirty) {
                AppButton("Discard", onClick = { drafts = state.settings.customIssueRules }, variant = ButtonVariant.Secondary)
            }
        }
    }
}

/**
 * The twelve note and copy settings on one four-column grid (content-sized columns, equal gaps —
 * SettingsGrid). Rows group them by purpose: note behaviour, what copying includes, and the
 * formats. The three wide format controls use tighter segments so all four columns fit with
 * comfortable gaps.
 */
@Composable
private fun ExportSettingsGrid(state: AppState) {
    SettingsGrid(columns = 4, rowSpacing = 10.dp) {
        AutoSaveSetting(state)
        FilterBackupsSetting(state)
        InlineMarkdownSetting(state)
        NumberBlocksSetting(state)
        PidTidCopySetting(state)
        PidCopyAsNameSetting(state)
        RowNumberCopySetting(state)
        TimeDeltaCopySetting(state)
        LogBlocksSetting(state)
        CopyDefaultSetting(state)
        DiagramNoteActionSetting(state)
        DiagramExportSetting(state)
    }
}

@Composable
private fun AutoSaveSetting(state: AppState) {
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
}

@Composable
private fun FilterBackupsSetting(state: AppState) {
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
}

@Composable
private fun NumberBlocksSetting(state: AppState) {
    CompactSetting("Number blocks") {
        SegmentedControl(
            options = listOf("On", "Off"),
            selectedIndices = setOf(if (state.settings.numberAnnotationBlocks) 0 else 1),
            onToggle = { idx -> state.updateSettings { it.copy(numberAnnotationBlocks = idx == 0) } },
        )
    }
}

@Composable
private fun LogBlocksSetting(state: AppState) {
    CompactSetting("Log blocks") {
        val styles = AnnotationLogBlockStyle.entries
        SegmentedControl(
            segmentHorizontalPadding = 6.dp,
            options = listOf("Indented", "Wiki", "Cloud"),
            selectedIndices = setOf(styles.indexOf(state.settings.annotationLogBlockStyle)),
            onToggle = { idx -> state.updateSettings { it.copy(annotationLogBlockStyle = styles[idx]) } },
        )
    }
}

@Composable
private fun InlineMarkdownSetting(state: AppState) {
    CompactSettingWithTooltip(
        label = "Inline Markdown",
        tooltip = "Shows non-empty note and caption fields as rendered Markdown in the Notes panel; click them to edit.",
    ) {
        SegmentedControl(
            options = listOf("On", "Off"),
            selectedIndices = setOf(if (state.settings.renderAnnotationMarkdownInline) 0 else 1),
            onToggle = { idx -> state.updateSettings { it.copy(renderAnnotationMarkdownInline = idx == 0) } },
        )
    }
}

@Composable
private fun PidTidCopySetting(state: AppState) {
    CompactSettingWithTooltip(
        label = "Pid/Tid copy",
        tooltip = "Includes PID and TID for log rows that contain them when copying lines, annotations, or filtered exports.",
    ) {
        SegmentedControl(
            options = listOf("On", "Off"),
            selectedIndices = setOf(if (state.settings.copyPidTid) 0 else 1),
            onToggle = { index -> state.updateSettings { it.copy(copyPidTid = index == 0) } },
        )
    }
}

@Composable
private fun PidCopyAsNameSetting(state: AppState) {
    CompactSettingWithTooltip(
        label = "Pid copy as name",
        tooltip = "Uses a process name learned from the log instead of the numeric PID. Available only while PID/TID copying is on.",
    ) {
        SegmentedControl(
            options = listOf("On", "Off"),
            selectedIndices = setOf(if (state.settings.copyPidAsName) 0 else 1),
            onToggle = { index -> state.updateSettings { it.copy(copyPidAsName = index == 0) } },
            enabled = state.settings.copyPidTid,
        )
    }
}

@Composable
private fun RowNumberCopySetting(state: AppState) {
    CompactSettingWithTooltip(
        label = "Row number copy",
        tooltip = "Includes the original log row number when the row-number gutter is visible in the log view.",
    ) {
        SegmentedControl(
            options = listOf("On", "Off"),
            selectedIndices = setOf(if (state.settings.copyRowNumber) 0 else 1),
            onToggle = { index -> state.updateSettings { it.copy(copyRowNumber = index == 0) } },
        )
    }
}

@Composable
private fun TimeDeltaCopySetting(state: AppState) {
    CompactSettingWithTooltip(
        label = "Time delta copy",
        tooltip = "Includes Δt only when the active tab's Δt column is visible and the log view can calculate it.",
    ) {
        SegmentedControl(
            options = listOf("On", "Off"),
            selectedIndices = setOf(if (state.settings.copyTimeDelta) 0 else 1),
            onToggle = { index -> state.updateSettings { it.copy(copyTimeDelta = index == 0) } },
        )
    }
}

@Composable
private fun CopyDefaultSetting(state: AppState) {
    CompactSettingWithTooltip(
        label = "Copy default",
        tooltip = "Chooses the format used by the main Copy button. A one-time choice from the Copy menu does not change this default.",
    ) {
        val formats = AnnotationCopyFormat.entries
        SegmentedControl(
            segmentHorizontalPadding = 6.dp,
            options = listOf("Cloud", "Wiki", "Markdown", "HTML"),
            selectedIndices = setOf(formats.indexOf(state.settings.annotationCopyFormat)),
            onToggle = { index -> state.updateSettings { it.copy(annotationCopyFormat = formats[index]) } },
        )
    }
}

@Composable
private fun DiagramNoteActionSetting(state: AppState) {
    CompactSettingWithTooltip(
        label = "Diagram note action",
        tooltip = "The sequence-diagram workspace always offers snapshot and linked notes. " +
            "This chooses which half of the split action is primary.",
    ) {
        SegmentedControl(
            segmentHorizontalPadding = 6.dp,
            options = listOf("Snapshot", "Link"),
            selectedIndices = setOf(if (state.settings.diagramLinkedNotePrimary) 1 else 0),
            onToggle = { idx -> state.updateSettings { it.copy(diagramLinkedNotePrimary = idx == 1) } },
        )
    }
}

@Composable
private fun DiagramExportSetting(state: AppState) {
    CompactSettingWithTooltip(
        label = "Diagram export",
        tooltip = "Sets the representation for newly added sequence-diagram notes. " +
            "Image works in Markdown and Jira without Mermaid or PlantUML support; " +
            "Src keeps the editable diagram text. Existing notes keep their own choice.",
    ) {
        SegmentedControl(
            options = listOf("Img", "Src"),
            selectedIndices = setOf(if (state.settings.diagramDefaultExportMode == DiagramExportMode.IMAGE) 0 else 1),
            onToggle = { index ->
                state.updateSettings {
                    it.copy(
                        diagramDefaultExportMode = if (index == 0) {
                            DiagramExportMode.IMAGE
                        } else {
                            DiagramExportMode.SOURCE
                        },
                    )
                }
            },
        )
    }
}

@Composable
private fun ExportAnnotationsSettingsSection(state: AppState) {
    val tc = tc()
    ExportSettingsGrid(state)
    Column(Modifier.settingsAnchor("Annotation file prefix"), verticalArrangement = Arrangement.spacedBy(6.dp)) {
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
        CompactSetting("Mask word on copy") {
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
    SettingsGrid(columns = 3, rowSpacing = 16.dp) {
        CompactSetting("MCP control server") {
            SegmentedControl(
                options = listOf("On", "Off"),
                selectedIndices = setOf(if (state.settings.mcpControlEnabled) 0 else 1),
                onToggle = { idx -> state.setMcpControlEnabled(idx == 0, state.settings.mcpControlPort) },
            )
        }
        // (SEC-1) Off by default: CORS lets any origin a browser has open issue cross-origin requests
        // to this loopback server. Bearer-token auth still gates every request either way — this only
        // controls whether a browser is additionally allowed to do that at all. Opt-in for the
        // uncommon case of a browser-based MCP inspector.
        CompactSetting("Allow browser-based MCP clients (CORS)") {
            SegmentedControl(
                options = listOf("On", "Off"),
                selectedIndices = setOf(if (state.settings.mcpAllowBrowserClients) 0 else 1),
                onToggle = { idx -> state.setMcpAllowBrowserClients(idx == 0) },
            )
        }
        CompactSetting("Port") {
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
        CompactSetting("Connection info") {
            // Deliberately doesn't close Settings first — stacks on top instead, so closing
            // this popup returns you to Settings rather than to the main window.
            AppButton("Connection info…", onClick = { state.mcpInfoOpen = true }, variant = ButtonVariant.Secondary)
        }
        CompactSetting("Debug logging") {
            SegmentedControl(
                options = listOf("On", "Off"),
                selectedIndices = setOf(if (state.settings.debugLoggingEnabled) 0 else 1),
                onToggle = { idx -> state.setDebugLoggingEnabled(idx == 0) },
            )
        }
        CompactSetting("Check for updates automatically") {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                SegmentedControl(
                    options = listOf("On", "Off"),
                    selectedIndices = setOf(if (state.settings.autoCheckUpdates) 0 else 1),
                    onToggle = { idx -> state.updateSettings { it.copy(autoCheckUpdates = idx == 0) } },
                )
                AppButton("Check now", onClick = { state.checkForUpdates(manual = true) }, variant = ButtonVariant.Secondary)
            }
        }
    }
    state.mcpControlError?.let { message ->
        AppText(message, color = DANGER_RED, fontSize = 11.sp, maxLines = 2)
    }
    Row(
        Modifier.settingsAnchor("Debug log file"),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        val fullPath = state.settings.debugLogFilePath
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
                modifier = Modifier.weight(1f, fill = false),
            ) { pathText() }
        } else {
            Box(Modifier.weight(1f, fill = false)) { pathText() }
        }
        AppButton("Browse", onClick = { state.pickDebugLogFile() })
        AppButton(
            "Open current log",
            onClick = { state.openCurrentDebugLog() },
            variant = ButtonVariant.Secondary,
            enabled = fullPath?.let { File(it).isFile } == true,
        )
    }
    state.debugLoggingError?.let { message ->
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            AppText("Diagnostic logging unavailable: $message", color = DANGER_RED, fontSize = 11.sp, maxLines = 2)
            AppButton("Retry", onClick = { state.retryDebugLoggingConfiguration() }, variant = ButtonVariant.Secondary)
        }
    }
    // availableUpdate is checked first: once a release is known, that fact takes priority over
    // whatever the last raw check status happened to be (e.g. a stale UpToDate from a previous run).
    val updateStatusText = when {
        state.availableUpdate != null -> "Update available"
        else -> when (val s = state.updateCheckStatus) {
            UpdateStatus.Idle -> null
            UpdateStatus.Checking -> "Checking…"
            is UpdateStatus.UpToDate -> "Up to date (v${s.version})"
            is UpdateStatus.Failed -> "Couldn't reach GitHub"
        }
    }
    updateStatusText?.let { text ->
        val isError = state.availableUpdate == null && state.updateCheckStatus is UpdateStatus.Failed
        AppText(text, color = if (isError) DANGER_RED else tc.td, fontSize = 11.sp)
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
                    "Point Indagium at your project's source folder(s), then right-click a log line → " +
                        "\"Show in code\" to see the code that logged it.",
                    color = tc.tx,
                    fontSize = 11.sp,
                    maxLines = 2,
                )
            }
        },
    ) {
        AppButton(
            "Register source code",
            onClick = { state.pickSourceFolder() },
            modifier = Modifier.settingsAnchor("Register source code"),
        )
    }
    // The "N files changed — reindex recommended" hint stats every indexed file, so it is computed
    // off the UI thread (see AppState.refreshChangedFileCounts) and only re-checked when this
    // section is actually shown — never per recomposition, which on a network-mounted source
    // folder froze the dialog.
    LaunchedEffect(state.settings.sourceFolders) { state.refreshChangedFileCounts() }
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
            modifier = Modifier.settingsAnchor("Discover simple custom log wrappers"),
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
            Modifier.fillMaxWidth().settingsAnchor("Logging configurations"),
            horizontalArrangement = Arrangement.spacedBy(16.dp),
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
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(16.dp), verticalAlignment = Alignment.CenterVertically) {
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
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(16.dp), verticalAlignment = Alignment.CenterVertically) {
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
                        "Owner/type, e.g. com.example.Telemetry or Telemetry",
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
            if (File(path).absolutePath in state.indexingFolders) {
                AppButton(
                    "Cancel",
                    onClick = { state.cancelReindexSources(path) },
                    variant = ButtonVariant.Secondary,
                    enabled = File(path).absolutePath !in state.cancelledIndexingFolders,
                )
            }
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
private fun VoiceInputSettingsSection(state: AppState) {
    val tc = tc()
    val scope = rememberCoroutineScope()
    val voiceSettings = state.settings.voiceInput
    val selectedModel = VoiceModelCatalog.byId(voiceSettings.modelId)
    val installer = remember(selectedModel.id) { VoiceModelInstaller(DesktopStorage.voiceModelsDir(), selectedModel) }
    var installed by remember { mutableStateOf(false) }
    var checkingModel by remember { mutableStateOf(true) }
    var installing by remember { mutableStateOf(false) }
    var downloadedBytes by remember { mutableStateOf(0L) }
    var statusMessage by remember { mutableStateOf<String?>(null) }
    var languageToAdd by remember { mutableStateOf("") }

    LaunchedEffect(installer) {
        installed = withContext(Dispatchers.IO) { installer.isInstalled() }
        checkingModel = false
    }

    fun installModel() {
        installing = true
        statusMessage = null
        scope.launch {
            when (val result = withContext(Dispatchers.IO) {
                installer.install { downloadedBytes = it }
            }) {
                is VoiceModelInstallResult.Installed,
                is VoiceModelInstallResult.AlreadyInstalled -> {
                    installed = true
                    installing = false
                    statusMessage = "Local voice model is ready."
                }
                is VoiceModelInstallResult.Failure -> {
                    installing = false
                    statusMessage = result.message
                }
            }
        }
    }

    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        AppText("Voice input", color = tc.td, fontSize = 10.sp, fontFamily = UI)
        AppText(
            "Dictate into the AI composer using the default microphone. Audio stays local; AI providers receive only the text you choose to send.",
            color = tc.tx,
            fontSize = 12.sp,
            maxLines = 4,
        )
        AppText(
            "Recognition engine", color = tc.td, fontSize = 10.sp, fontFamily = UI,
            modifier = Modifier.settingsAnchor("Recognition engine"),
        )
        val engineChoices = VoiceRecognitionEngines.availableChoices()
        SegmentedControl(
            options = engineChoices.map { it.label },
            selectedIndices = setOf(engineChoices.indexOf(voiceSettings.recognitionEngine).coerceAtLeast(0)),
            onToggle = { index ->
                state.updateSettings { settings ->
                    settings.copy(voiceInput = settings.voiceInput.copy(recognitionEngine = engineChoices[index]))
                }
            },
            modifier = Modifier.fillMaxWidth(),
            fillWidth = engineChoices.size <= 2,
        )
        AppText(VoiceRecognitionEngines.description(voiceSettings.recognitionEngine), color = tc.td, fontSize = 10.sp, maxLines = 3)
        if (VoiceRecognitionEngines.supportsTranslation(voiceSettings.recognitionEngine)) {
            CheckRow(
                modifier = Modifier.settingsAnchor("Translate dictated speech to English"),
                checked = voiceSettings.translateToEnglish,
                onToggle = {
                    state.updateSettings { settings ->
                        settings.copy(voiceInput = settings.voiceInput.copy(translateToEnglish = !settings.voiceInput.translateToEnglish))
                    }
                },
            ) {
                AppText("Translate dictated speech to English", color = tc.tx, fontSize = 12.sp)
            }
            AppText(
                if (voiceSettings.translateToEnglish) {
                    "Output mode: English translation after " +
                        "${VoiceLanguageCatalog.label(voiceSettings.selectedRecognitionLanguageCode)} recognition. " +
                        "You can edit the result before sending."
                } else {
                    "Output mode: ${VoiceLanguageCatalog.label(voiceSettings.selectedRecognitionLanguageCode)} " +
                        "transcript. You can edit the result before sending."
                },
                color = tc.td,
                fontSize = 10.sp,
                maxLines = 3,
            )
        } else {
            AppText(
                "This OS engine returns the recognized spoken language only; English translation " +
                    "remains available with Whisper.",
                color = tc.td,
                fontSize = 10.sp,
                maxLines = 3,
            )
        }
        Divider()
        AppText(
            "Recognition language", color = tc.td, fontSize = 10.sp, fontFamily = UI,
            modifier = Modifier.settingsAnchor("Recognition language"),
        )
        AppText(
            if (voiceSettings.recognitionEngine == VoiceRecognitionEngine.WINDOWS_SPEECH) {
                "Windows Speech needs a matching installed legacy recognizer. For Ukrainian or any unavailable language, choose Local Whisper."
            } else {
                "Automatic detects the spoken language. Choose Ukrainian for short Ukrainian phrases; it avoids an English-recognition bias."
            },
            color = tc.tx,
            fontSize = 12.sp,
            maxLines = 3,
        )
        val selectedLanguageIndex = voiceSettings.recognitionLanguageCodes
            .indexOf(voiceSettings.selectedRecognitionLanguageCode).coerceAtLeast(0)
        SegmentedControl(
            options = voiceSettings.recognitionLanguageCodes.map(VoiceLanguageCatalog::label),
            selectedIndices = setOf(selectedLanguageIndex),
            onToggle = { index ->
                val code = voiceSettings.recognitionLanguageCodes[index]
                state.updateSettings { settings ->
                    settings.copy(voiceInput = settings.voiceInput.copy(selectedRecognitionLanguageCode = code))
                }
            },
            modifier = Modifier.fillMaxWidth(),
            fillWidth = voiceSettings.recognitionLanguageCodes.size <= 3,
        )
        val addableLanguages = VoiceLanguageCatalog.additional.filter { it.code !in voiceSettings.recognitionLanguageCodes }
        if (addableLanguages.isNotEmpty()) {
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
                InlineField(
                    value = languageToAdd,
                    onValue = { languageToAdd = it.lowercase().filter(Char::isLetter).take(3) },
                    placeholder = "Language code, e.g. ru",
                    modifier = Modifier.weight(1f),
                    fontSize = 11.sp,
                    onSubmit = {
                        val code = languageToAdd
                        if (addableLanguages.any { it.code == code }) {
                            state.updateSettings { settings ->
                                settings.copy(voiceInput = settings.voiceInput.copy(
                                    recognitionLanguageCodes = settings.voiceInput.recognitionLanguageCodes + code,
                                ))
                            }
                            languageToAdd = ""
                        }
                    },
                )
                AppButton(
                    "Add language",
                    onClick = {
                        val code = languageToAdd
                        if (addableLanguages.any { it.code == code }) {
                            state.updateSettings { settings ->
                                settings.copy(voiceInput = settings.voiceInput.copy(
                                    recognitionLanguageCodes = settings.voiceInput.recognitionLanguageCodes + code,
                                ))
                            }
                            languageToAdd = ""
                        }
                    },
                    enabled = addableLanguages.any { it.code == languageToAdd },
                )
            }
            AppText(
                "Additional supported codes: ${addableLanguages.joinToString { "${it.label} (${it.code})" }}",
                color = tc.td,
                fontSize = 10.sp,
                maxLines = 3,
            )
        }
        val removableLanguages = voiceSettings.recognitionLanguageCodes.filter { code ->
            VoiceLanguageCatalog.defaults.none { it.code == code }
        }
        if (removableLanguages.isNotEmpty()) {
            FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                removableLanguages.forEach { code ->
                    AppButton(
                        "Remove ${VoiceLanguageCatalog.label(code)}",
                        onClick = {
                            state.updateSettings { settings ->
                                val languages = settings.voiceInput.recognitionLanguageCodes - code
                                settings.copy(voiceInput = settings.voiceInput.copy(
                                    recognitionLanguageCodes = languages,
                                    selectedRecognitionLanguageCode = settings.voiceInput.selectedRecognitionLanguageCode
                                        .takeIf { it in languages } ?: "auto",
                                ))
                            }
                        },
                        variant = ButtonVariant.Ghost,
                        horizontalPadding = 7.dp,
                    )
                }
            }
        }
        Divider()
        if (voiceSettings.recognitionEngine == VoiceRecognitionEngine.WHISPER) {
            AppText(
                "Local model", color = tc.td, fontSize = 10.sp, fontFamily = UI,
                modifier = Modifier.settingsAnchor("Local model"),
            )
            SegmentedControl(
                options = VoiceModelCatalog.all.map { model ->
                    if (model.id == VoiceModelCatalog.base.id) "Base (${formatByteSize(model.sizeBytes)})"
                    else "Small (${formatByteSize(model.sizeBytes)})"
                },
                selectedIndices = setOf(VoiceModelCatalog.all.indexOfFirst { it.id == selectedModel.id }),
                onToggle = { index ->
                    val model = VoiceModelCatalog.all[index]
                    state.updateSettings { settings ->
                        settings.copy(voiceInput = settings.voiceInput.copy(modelId = model.id))
                    }
                },
                modifier = Modifier.fillMaxWidth(),
                fillWidth = true,
            )
            AppText(
                (
                    if (selectedModel.id == VoiceModelCatalog.small.id) {
                        "Whisper small (multilingual): more accurate for Ukrainian/Russian and short phrases, but uses more disk, memory, and CPU."
                    } else {
                        "Whisper base (multilingual): faster and smaller, best for English or longer, clear speech."
                    }
                ) + " One explicit HTTPS download; no cloud speech-recognition service.",
                color = tc.tx,
                fontSize = 12.sp,
                maxLines = 3,
            )
            AppText(
                when {
                    checkingModel -> "Checking local model…"
                    installed -> "Status: installed at Indagium application data/voice-models."
                    else -> "Status: not installed."
                },
                color = if (installed) tc.ac else tc.td,
                fontSize = 11.sp,
            )
            if (installing) AppText("Downloading ${formatByteSize(downloadedBytes)}…", color = tc.ac, fontSize = 11.sp)
            statusMessage?.let { message ->
                AppText(message, color = if (installed) tc.ac else DANGER_RED, fontSize = 10.sp, maxLines = 3)
            }
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                if (installed) {
                    AppButton(
                        "Remove model",
                        onClick = {
                            if (installer.remove()) {
                                installed = false
                                statusMessage = "Local voice model removed."
                            } else {
                                statusMessage = "Could not remove the local voice model."
                            }
                        },
                        variant = ButtonVariant.Secondary,
                        isDanger = true,
                        enabled = !installing,
                    )
                    AppButton(
                        "Reinstall",
                        onClick = {
                            installer.remove()
                            installed = false
                            installModel()
                        },
                        variant = ButtonVariant.Secondary,
                        enabled = !installing,
                    )
                } else {
                    AppButton(
                        if (installing) "Downloading…" else "Download local model",
                        onClick = ::installModel,
                        variant = ButtonVariant.Primary,
                        enabled = !installing && !checkingModel,
                    )
                }
            }
            AppText("Model license: ${selectedModel.licenseUrl}", color = tc.td, fontSize = 10.sp, maxLines = 2)
        } else {
            AppText("Native engine", color = tc.td, fontSize = 10.sp, fontFamily = UI)
            AppText(
                if (voiceSettings.recognitionEngine == VoiceRecognitionEngine.APPLE_SPEECH) {
                    "At first use macOS asks for Speech Recognition permission. If the selected " +
                        "language has no on-device Apple model, Indagium refuses to send audio and " +
                        "you can switch back to Whisper."
                } else {
                    "Windows Speech uses a matching installed legacy recognizer. Language availability is narrower than Whisper; " +
                        "if there is no recognizer for the selected language, Indagium shows the reason and you can switch back to Whisper."
                },
                color = tc.tx,
                fontSize = 12.sp,
                maxLines = 4,
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
    var accountCliCheck by remember(profile.id, kind) { mutableStateOf<com.indagium.ai.AccountCliCheck?>(null) }
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
        AppText("Providers", color = tc.td, fontSize = 10.sp, fontFamily = UI, modifier = Modifier.settingsAnchor("Providers"))
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
            AppText(
                "Provider type", color = tc.td, fontSize = 10.sp, fontFamily = UI,
                modifier = Modifier.settingsAnchor("Provider type"),
            )
            SegmentedControl(
                options = com.indagium.model.AiProviderKind.entries.map { it.label },
                selectedIndices = setOf(com.indagium.model.AiProviderKind.entries.indexOf(kind)),
                onToggle = { index ->
                    val selectedKind = com.indagium.model.AiProviderKind.entries[index]
                    if (selectedKind != kind) {
                        val preset = com.indagium.model.defaultAiProviderProfile(selectedKind)
                        kind = selectedKind
                        name = preset.displayName
                        endpoint = preset.baseUrl
                        model = preset.model
                        executablePath = preset.executablePath
                        reasoningEffort = preset.reasoningEffort
                        ackState = endpoint.trim() to false
                    }
                },
                modifier = Modifier.fillMaxWidth(),
                fillWidth = true,
                weightByLabel = true,
            )
            AppText(
                "Profile name", color = tc.td, fontSize = 10.sp, fontFamily = UI,
                modifier = Modifier.settingsAnchor("Profile name"),
            )
            InlineField(name, { name = it }, "LM Studio (local)", Modifier.fillMaxWidth(), fontSize = 12.sp)
            if (kind.usesHttpEndpoint) {
                AppText(
                    "Endpoint", color = tc.td, fontSize = 10.sp, fontFamily = UI,
                    modifier = Modifier.settingsAnchor("Endpoint"),
                )
                InlineField(endpoint, { endpoint = it }, "https://api.example.com/v1", Modifier.fillMaxWidth(), fontSize = 12.sp)
            } else {
                AppText(
                    "Uses a local CLI account already signed in on this computer.",
                    color = tc.td,
                    fontSize = 10.sp,
                )
                AppText(
                    "CLI executable (optional)", color = tc.td, fontSize = 10.sp, fontFamily = UI,
                    modifier = Modifier.settingsAnchor("CLI executable (optional)"),
                )
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
                            val detected = com.indagium.ai.LocalAccountCli.detectExecutable(kind)
                            if (detected != null) {
                                executablePath = detected
                                accountCliCheck = com.indagium.ai.AccountCliCheck(true, "Found local CLI: $detected")
                            } else {
                                val appPath = com.indagium.ai.LocalAccountCli.detectedDesktopApp(kind)
                                accountCliCheck = com.indagium.ai.AccountCliCheck(
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
                    modifier = Modifier.weight(1f).settingsAnchor("Model"),
                )
                if (showReasoningDropdown) {
                    AppText(
                        "Reasoning effort", color = tc.td, fontSize = 10.sp, fontFamily = UI,
                        modifier = Modifier.weight(1f).settingsAnchor("Reasoning effort"),
                    )
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
                AppText(
                    "API key — this session only; it is never saved", color = tc.td, fontSize = 10.sp, fontFamily = UI,
                    modifier = Modifier.settingsAnchor("API key"),
                )
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
        if (kind.usesHttpEndpoint && endpoint.isNotBlank() && !com.indagium.ai.isLoopbackHost(endpointHost)) {
            CheckRow(acknowledged, { ackState = endpoint.trim() to !acknowledged }) {
                // Screen images from device capture tools are sent to this provider when used.
                AppText(
                    "I understand logs, source code, paths, device screen images, and tool results may leave this " +
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
                            accountCliCheck = com.indagium.ai.checkAccountCli(testProfile)
                        } else {
                            val provider = when (testProfile.kind) {
                                com.indagium.model.AiProviderKind.ANTHROPIC_API ->
                                    com.indagium.ai.AnthropicMessagesProvider(testProfile.baseUrl, testKey)
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
        CompactSetting("Max MCP tool calls per request") {
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
                "with \"MCP tool-call budget\" before it finishes. Notes reads and writes are unlimited; " +
                "only analysis and operational calls consume this budget.",
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
            modifier = Modifier.settingsAnchor("Add command"),
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
// Indagium serves MCP natively over Streamable HTTP at /mcp — any MCP client (LM Studio, Claude
// Code, Codex) connects with just this URL, no Node bridge / npm / repo checkout to install. The
// snippet is the standard mcpServers-with-url form those clients accept. `token` is required on
// every request (see debug/ControlServer.kt's start()); it rides along as a `headers` block the
// same way any bearer-token MCP server config does, so "paste this JSON" keeps working end to end.
internal fun mcpConfigSnippet(port: Int, token: String): String =
    """
    {
      "mcpServers": {
        "indagium-control": {
          "url": "${mcpUrl(port)}",
          "headers": {
            "Authorization": "Bearer $token"
          }
        }
      }
    }
    """.trimIndent()

internal fun codexMcpConfigSnippet(port: Int, token: String): String =
    """
    [mcp_servers.indagium]
    url = "${mcpUrl(port)}"
    http_headers = { Authorization = "Bearer $token" }

    [mcp_servers.indagium.tools.list_tabs]
    approval_mode = "approve"
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

/**
 * WP8: generalised so both the Settings "Theme" control and the per-document diagram picker in
 * `Seq3Workspace.kt` share one card gallery instead of Settings having the only visual chooser and
 * everything else falling back to a text dropdown. [settings] is only
 * needed to resolve the *colors* of the leading "Follow app theme" tile (via
 * [resolveSeq3ThemeColors]) when [followAppTheme] is true — selection itself is driven entirely by
 * [selected]/[onSelect] so this has no idea whether it's picking the app theme, a per-document
 * theme, or a default-for-new-documents.
 */
@Composable
internal fun ThemeGallery(
    settings: AppSettings,
    selected: ThemePreset?,
    onSelect: (ThemePreset?) -> Unit,
    // Null shows every theme at natural height with no inner scroll (Settings, setup assistant);
    // a fixed height keeps the compact scrolling gallery of the diagram theme picker.
    height: Dp? = 148.dp,
    followAppTheme: Boolean = false,
) {
    val tc = tc()
    val themeScroll = rememberScrollState()
    Box(Modifier.fillMaxWidth().then(if (height != null) Modifier.height(height) else Modifier)) {
        FlowRow(
            Modifier.fillMaxWidth().then(if (height != null) Modifier.verticalScroll(themeScroll).padding(end = 12.dp) else Modifier),
            // Natural height: no scrollbar to leave room for, and the cards spread edge to edge so
            // the controls below line up with the gallery's right edge.
            horizontalArrangement = if (height != null) Arrangement.spacedBy(8.dp) else Arrangement.SpaceBetween,
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            if (followAppTheme) {
                ThemeWindowCard(
                    label = "Follow app theme",
                    colors = resolveSeq3ThemeColors(null, settings),
                    selected = selected == null,
                    onClick = { onSelect(null) },
                )
            }
            ThemePreset.entries.forEach { preset ->
                ThemeWindowCard(
                    label = preset.label,
                    colors = themeColors(preset),
                    selected = preset == selected,
                    onClick = { onSelect(preset) },
                )
            }
        }
        if (height != null) {
            VerticalScrollbar(
                adapter = rememberScrollbarAdapter(themeScroll),
                modifier = Modifier.align(Alignment.CenterEnd).fillMaxHeight().width(6.dp),
                style = appScrollbarStyle(tc),
            )
        }
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
internal fun CompactSettingWithTooltip(
    label: String,
    tooltip: String,
    modifier: Modifier = Modifier,
    horizontalAlignment: Alignment.Horizontal = Alignment.Start,
    labelMaxLines: Int = 1,
    labelAreaHeight: Dp? = null,
    content: @Composable () -> Unit,
) {
    val tc = tc()
    Column(
        modifier.settingsAnchor(label),
        horizontalAlignment = horizontalAlignment,
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        val labelContent: @Composable () -> Unit = {
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
                AppText(
                    label,
                    color = tc.td,
                    fontSize = 10.sp,
                    fontFamily = UI,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = labelMaxLines,
                )
            }
        }
        if (labelAreaHeight == null) {
            labelContent()
        } else {
            Box(Modifier.height(labelAreaHeight)) { labelContent() }
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
    Column(
        modifier.settingsAnchor(label),
        verticalArrangement = Arrangement.spacedBy(6.dp),
        horizontalAlignment = horizontalAlignment,
    ) {
        AppText(label, color = tc.td, fontSize = 10.sp, fontFamily = UI, fontWeight = FontWeight.SemiBold)
        content()
    }
}
