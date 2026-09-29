@file:OptIn(androidx.compose.ui.ExperimentalComposeUiApi::class)

package com.indagium.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Check
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.onPointerEvent
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.indagium.model.ProfileSpec

// First-run setup assistant: four short steps that apply the same choices Settings offers, through
// the same AppState functions (applyWorkspaceProfile, updateSettings, pickSaveFolder, ...), so there
// is no draft to keep in sync. Skipping (button, Esc, closing) and finishing both mark it done —
// AppState.finishSetupAssistant/skipSetupAssistant. Existing users see it once too, with a "Keep my
// current setup" choice preselected so simply clicking through changes nothing. It is as wide as
// Settings (884dp) so the theme gallery fits five cards per row.

private enum class SetupStep(val title: String) {
    Workspace("Workspace"),
    Look("Look"),
    Folders("Folders"),
    Capture("Capture"),
}

/** What step 1 has selected: the untouched current setup, or one of the built-in profiles. */
private sealed interface WorkspaceChoice {
    data object KeepCurrent : WorkspaceChoice

    data class Profile(val profile: ResolvedProfile) : WorkspaceChoice
}

private const val PROFILE_GRID_COLUMNS = 3

@Composable
internal fun SetupAssistantDialog(state: AppState) {
    val tc = tc()
    val shape = RoundedCornerShape(8.dp)
    val steps = SetupStep.entries
    var step by remember { mutableStateOf(0) }
    var visited by remember { mutableStateOf(setOf(0)) }
    // Someone who already has a setup to keep: an upgrade from an earlier version, or a re-run
    // after finishing this assistant once.
    val offerKeep = state.startedWithExistingData || state.settings.setupAssistantDone
    var choice by remember {
        mutableStateOf<WorkspaceChoice>(if (offerKeep) WorkspaceChoice.KeepCurrent else WorkspaceChoice.Profile(WorkspaceProfile.CLASSIC.resolved()))
    }
    var applied by remember { mutableStateOf<String?>(null) } // id of the profile last applied
    val rootFocus = remember { FocusRequester() }

    fun reclaimFocus() {
        runCatching { rootFocus.requestFocus() }
    }

    // Applied when leaving step 1, so the Look step opens with the profile's theme already chosen.
    fun applyChoiceIfNeeded() {
        val picked = (choice as? WorkspaceChoice.Profile)?.profile ?: return
        if (picked.id != applied) {
            state.applyResolvedProfile(picked)
            applied = picked.id
        }
    }

    fun goTo(target: Int) {
        if (step == 0 && target != 0) applyChoiceIfNeeded()
        step = target.coerceIn(0, steps.lastIndex)
        visited = visited + step
        reclaimFocus()
    }

    fun finish() {
        applyChoiceIfNeeded()
        state.finishSetupAssistant()
    }

    fun next() {
        if (step == steps.lastIndex) finish() else goTo(step + 1)
    }

    LaunchedEffect(Unit) { reclaimFocus() }

    Box(
        Modifier.width(884.dp).height(600.dp)
            .clip(shape).background(tc.p).border(1.dp, tc.br, shape)
            .onPreviewKeyEvent { ev ->
                if (ev.type == KeyEventType.KeyDown && ev.key == Key.Escape) {
                    state.skipSetupAssistant()
                    true
                } else {
                    false
                }
            }
            // Bubbling (not preview) so a focused button still gets its own Enter first.
            .onKeyEvent { ev ->
                if (ev.type == KeyEventType.KeyDown && ev.key == Key.Enter) {
                    next()
                    true
                } else {
                    false
                }
            }
            .focusRequester(rootFocus)
            .focusable(),
    ) {
        Column(Modifier.fillMaxSize()) {
            Row(
                Modifier.fillMaxWidth().padding(horizontal = 24.dp, vertical = 16.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    AppText("Set up Indagium", color = tc.tx, fontSize = 17.sp, fontWeight = FontWeight.SemiBold)
                    AppText(
                        "Four quick choices. You can change any of them later in Settings › General.",
                        color = tc.td, fontSize = 11.sp, maxLines = 1,
                    )
                }
                AppButton("Skip for now", onClick = { state.skipSetupAssistant() }, variant = ButtonVariant.Ghost)
            }
            Divider()
            Row(Modifier.weight(1f).fillMaxWidth()) {
                Column(
                    Modifier.width(180.dp).fillMaxHeight().background(tc.p2).padding(vertical = 16.dp, horizontal = 10.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    steps.forEachIndexed { index, s ->
                        SetupRailItem(
                            number = index + 1,
                            title = s.title,
                            current = index == step,
                            done = index != step && index in visited,
                            onClick = { goTo(index) },
                        )
                    }
                }
                Box(Modifier.width(1.dp).fillMaxHeight().background(tc.br))
                Column(
                    Modifier.weight(1f).fillMaxHeight().verticalScroll(rememberScrollState()).padding(24.dp),
                    verticalArrangement = Arrangement.spacedBy(14.dp),
                ) {
                    when (steps[step]) {
                        SetupStep.Workspace -> WorkspaceStep(
                            state, offerKeep, choice,
                            onChoice = { choice = it },
                            // An imported profile is already added and applied; just select it.
                            onImported = { imported ->
                                choice = WorkspaceChoice.Profile(imported)
                                applied = imported.id
                            },
                        )
                        SetupStep.Look -> LookStep(state)
                        SetupStep.Folders -> FoldersStep(state)
                        SetupStep.Capture -> CaptureStep(state, ::reclaimFocus)
                    }
                }
            }
            Divider()
            Row(
                Modifier.fillMaxWidth().padding(16.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                AppText("Step ${step + 1} of ${steps.size}", color = tc.td, fontSize = 11.sp)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                    if (step > 0) AppButton("Back", onClick = { goTo(step - 1) }, variant = ButtonVariant.Secondary)
                    AppButton(if (step == steps.lastIndex) "Finish" else "Continue", onClick = ::next, variant = ButtonVariant.Primary)
                }
            }
        }
    }
}

@Composable
private fun SetupRailItem(number: Int, title: String, current: Boolean, done: Boolean, onClick: () -> Unit) {
    val tc = tc()
    var hovered by remember { mutableStateOf(false) }
    val shape = RoundedCornerShape(8.dp)
    Row(
        Modifier.fillMaxWidth().clip(shape)
            .background(if (hovered && !current) tc.br.copy(alpha = .5f) else tc.p2.copy(alpha = 0f))
            .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null, onClick = onClick)
            .onPointerEvent(PointerEventType.Enter) { hovered = true }
            .onPointerEvent(PointerEventType.Exit) { hovered = false }
            .padding(horizontal = 8.dp, vertical = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            Modifier.size(22.dp)
                .background(
                    when {
                        current -> tc.ac
                        done -> tc.ac.copy(alpha = .16f)
                        else -> tc.p2.copy(alpha = 0f)
                    },
                    CircleShape,
                )
                .border(1.dp, if (current || done) tc.ac else tc.br, CircleShape),
            contentAlignment = Alignment.Center,
        ) {
            if (done) {
                Icon(Icons.Outlined.Check, contentDescription = "Visited", tint = tc.ac, modifier = Modifier.size(13.dp))
            } else {
                AppText(number.toString(), color = if (current) tc.bg else tc.td, fontSize = 11.sp, fontWeight = FontWeight.SemiBold)
            }
        }
        AppText(
            title,
            color = if (current) tc.tx else tc.ts,
            fontSize = 12.sp,
            fontWeight = if (current) FontWeight.Medium else FontWeight.Normal,
            maxLines = 1, overflow = TextOverflow.Ellipsis,
        )
    }
}

@Composable
private fun StepHeading(title: String, subtitle: String) {
    val tc = tc()
    Column(verticalArrangement = Arrangement.spacedBy(3.dp)) {
        AppText(title, color = tc.tx, fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
        AppText(subtitle, color = tc.ts, fontSize = 11.sp, maxLines = 2)
    }
}

@Composable
private fun WorkspaceStep(
    state: AppState,
    offerKeep: Boolean,
    choice: WorkspaceChoice,
    onChoice: (WorkspaceChoice) -> Unit,
    onImported: (ResolvedProfile) -> Unit,
) {
    StepHeading(
        "How do you like to read logs?",
        "Pick a starting layout. It sets panels, filter placement, theme and font size.",
    )
    val layout = state.layoutSnapshot()
    val settings = state.settings
    val cards = buildList<@Composable (Modifier) -> Unit> {
        if (offerKeep) {
            add { modifier ->
                WorkspaceProfileCard(
                    title = "Keep my current setup",
                    description = "Your panels and theme stay as they are",
                    selected = choice == WorkspaceChoice.KeepCurrent,
                    onClick = { onChoice(WorkspaceChoice.KeepCurrent) },
                    modifier = modifier,
                ) {
                    ProfileWireframe(
                        ProfileSpec(
                            theme = settings.theme,
                            fontSize = settings.fontSize,
                            showMinimap = settings.showMinimap,
                            toolbarIconOnlyButtons = settings.toolbarIconOnlyButtons,
                            openNewFilesWithUnfiltered = settings.openNewFilesWithUnfiltered,
                            filterVisible = layout.filterVisible,
                            filterBarVisible = layout.filterBarVisible,
                            annotationVisible = layout.annotationVisible,
                            videoPanelVisible = layout.videoPanelVisible,
                            aiPanelVisible = layout.aiPanelVisible,
                        ),
                    )
                }
            }
        }
        (WorkspaceProfile.entries.map { it.resolved() } + settings.customWorkspaceProfiles.map { it.resolved() }).forEach { profile ->
            add { modifier ->
                ResolvedProfileCard(
                    profile = profile,
                    selected = (choice as? WorkspaceChoice.Profile)?.profile?.id == profile.id,
                    onClick = { onChoice(WorkspaceChoice.Profile(profile)) },
                    modifier = modifier,
                )
            }
        }
        add { modifier ->
            val tc = tc()
            WorkspaceProfileCard(
                title = "Import a profile",
                description = "Load a .json profile someone shared with you",
                selected = false,
                onClick = { state.importWorkspaceProfile(onImported = { onImported(it.resolved()) }) },
                modifier = modifier,
            ) {
                val shape = RoundedCornerShape(5.dp)
                Box(
                    Modifier.fillMaxSize().background(tc.p, shape).border(1.dp, tc.br, shape),
                    contentAlignment = Alignment.Center,
                ) { AppText("+", color = tc.td, fontSize = 22.sp) }
            }
        }
    }
    WorkspaceCardGrid(cards, PROFILE_GRID_COLUMNS, 10.dp)
}

@Composable
private fun LookStep(state: AppState) {
    StepHeading("Pick a look", "The theme applies right away, so you can see it behind this window.")
    ThemeGallery(
        settings = state.settings,
        selected = state.settings.theme,
        onSelect = { preset -> preset?.let { state.updateSettings { s -> s.copy(theme = it) } } },
        height = null,
    )
    AppearanceBasicsRow(state)
}

@Composable
private fun FoldersStep(state: AppState) {
    val tc = tc()
    StepHeading("Where should files go?", "Starting folders for saves and captures. Save dialogs can still go anywhere.")
    Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
        listOf(SaveFolderKind.ROOT, SaveFolderKind.ANALYSIS, SaveFolderKind.SESSIONS).forEach { kind ->
            SaveFolderSetting(state, kind)
        }
    }
    AppText("More folders in Settings › General", color = tc.td, fontSize = 10.sp)
}

@Composable
private fun CaptureStep(state: AppState, reclaimFocus: () -> Unit) {
    val tc = tc()
    StepHeading("Capture from a phone", "Optional. Skip it if you only open log files.")
    // Resolution runs on the capture service's own scope (never the UI thread); reuse an earlier
    // check from the New tab when there already is one.
    LaunchedEffect(Unit) { if (!state.captureToolsChecked) state.recheckCaptureTools() }
    val shape = RoundedCornerShape(6.dp)
    Row(
        Modifier.fillMaxWidth().background(tc.p2, shape).border(1.dp, tc.br, shape).padding(horizontal = 12.dp, vertical = 10.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
            when {
                !state.captureToolsChecked ->
                    AppText("Checking for adb…", color = tc.td, fontSize = 12.sp)
                state.captureAdbAvailable -> {
                    AppText("✓ adb found", color = tc.ok, fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
                    state.captureToolResolution?.adbPath?.let {
                        AppText(truncatePathForDisplay(it), color = tc.ts, fontSize = 10.sp, fontFamily = MONO, overflow = TextOverflow.Ellipsis)
                    }
                }
                else -> {
                    AppText("adb not found", color = tc.warn, fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
                    AppText(
                        "Install Android SDK Platform-Tools, or point Indagium at an adb you already have.",
                        color = tc.td, fontSize = 10.sp, maxLines = 2,
                    )
                }
            }
        }
        AppButton(
            "Use another…",
            onClick = {
                state.browseCaptureAdb()
                reclaimFocus()
            },
            variant = ButtonVariant.Secondary,
        )
    }
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        RecordVideoToFileCheck(
            settings = state.settings.captureSettings,
            edit = { transform -> state.updateSettings { it.copy(captureSettings = transform(it.captureSettings)) } },
            nativeMediaSupport = state.captureNativeMediaSupport,
        )
        AppText(
            "Records the device screen with every capture.",
            color = tc.td, fontSize = 10.sp, modifier = Modifier.padding(start = 12.dp),
        )
    }
    AppText("Pair phones over Wi-Fi from the capture launcher on the New tab.", color = tc.td, fontSize = 11.sp, maxLines = 2)
}
