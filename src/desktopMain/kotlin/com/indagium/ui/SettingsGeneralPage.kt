package com.indagium.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Tune
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.indagium.model.DEFAULT_INACTIVE_TAB_UNLOAD_MINUTES

// Settings › General: the workspace profile cards (the group's rare actions in one "⋯" menu), the memory switch for inactive tabs and the
// update check. Keyboard shortcuts and the setup assistant live in the dialog's footer.

/** Which name dialog Settings › General has open. */
private sealed interface ProfileNameRequest {
    data object New : ProfileNameRequest

    data class Rename(val profile: ResolvedProfile) : ProfileNameRequest
}

/** Test tag of the "⋯" menu of the Workspace profile group, and the prefix of its entries (`-save`, `-import`, `-export`). */
internal const val PROFILE_GROUP_MENU_TAG = "profile-group-menu"

private const val INACTIVE_MINUTES_DIGITS = 5
private const val DIMMED_ALPHA = 0.5f

@Composable
internal fun GeneralSettingsSection(state: AppState, reclaimFocus: () -> Unit) {
    WorkspaceProfileGroup(state, reclaimFocus)
    SettingsGroup("Memory and updates") {
        InactiveTabMemoryRow(state)
        UpdateCheckRow(state)
    }
}

@Composable
private fun WorkspaceProfileGroup(state: AppState, reclaimFocus: () -> Unit) {
    val tc = tc()
    val selectedProfile = state.selectedWorkspaceProfile
    var nameRequest by remember { mutableStateOf<ProfileNameRequest?>(null) }
    var deleteRequest by remember { mutableStateOf<ResolvedProfile?>(null) }
    val savedLayouts = state.settings.customLogViewLayouts
    val profiles = WorkspaceProfile.entries.map { it.resolved(savedLayouts) } + state.settings.customWorkspaceProfiles.map { it.resolved(savedLayouts) }
    val profileActions: (ResolvedProfile) -> List<ProfileMenuAction> = { profile ->
        listOf(
            ProfileMenuAction("Rename…") { nameRequest = ProfileNameRequest.Rename(profile) },
            ProfileMenuAction("Update from current setup") { state.updateWorkspaceProfileFromCurrent(profile.id) },
            ProfileMenuAction("Export…") { state.exportWorkspaceProfile(profile) },
            ProfileMenuAction("Delete…", danger = true) { deleteRequest = profile },
        )
    }
    SettingsGroup(
        "Workspace profile",
        trailing = {
            ProfileGroupMenu(
                onSave = { nameRequest = ProfileNameRequest.New },
                onImport = { state.importWorkspaceProfile() },
                onExport = { state.exportWorkspaceProfile(null) },
                reclaimFocus = reclaimFocus,
            )
        },
    ) {
        Column(
            Modifier.fillMaxWidth().settingsAnchor("Workspace profile").padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            AppText(
                "Sets the theme, font size and defaults, and applies a layout, in one step. Profiles you save also keep your tab style, " +
                    "window layout and editor behavior. Every setting stays editable afterwards.",
                color = tc.ts,
                fontSize = 11.sp,
                fontFamily = UI,
                maxLines = 3,
            )
            WorkspaceProfileCards(
                profiles = profiles,
                selectedId = selectedProfile?.id,
                onSelect = state::applyResolvedProfile,
                panelsFor = { state.profilePanelsFor(it.spec) },
                onMenuClosed = reclaimFocus,
                menuFor = profileActions,
            )
            if (selectedProfile != null) ProfileLayoutRow(state, selectedProfile, reclaimFocus)
            // The same actions as the card's "⋯", always in view for the selected custom profile.
            if (selectedProfile != null && selectedProfile.custom) {
                ProfileActionStrip(
                    selectedProfile.title,
                    profileActions(selectedProfile).map { action ->
                        action.copy(
                            onClick = {
                                reclaimFocus()
                                action.onClick()
                            },
                        )
                    },
                )
            }
            // Read straight from state on every recomposition: theme/font edits made elsewhere
            // (Appearance) show up here without any extra plumbing. Panels belong to the layout, not the profile.
            val differences = state.workspaceProfileDifferences()
            if (selectedProfile != null && differences.isNotEmpty()) {
                CustomizedBanner(
                    selectedProfile,
                    differences,
                    onReset = { state.applyResolvedProfile(selectedProfile) },
                    onSaveNew = { nameRequest = ProfileNameRequest.New },
                    onUpdate = { state.updateWorkspaceProfileFromCurrent(selectedProfile.id) },
                )
            }
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
}

/**
 * The group's "⋯": save the current setup as a profile, import one, export the current setup. The three search entries that used to be
 * links in a row (Save as new profile, Import profile, Export profile) all anchor on this button, one nested anchor each.
 */
@Composable
private fun ProfileGroupMenu(onSave: () -> Unit, onImport: () -> Unit, onExport: () -> Unit, reclaimFocus: () -> Unit) {
    Box(Modifier.settingsAnchor("Save as new profile")) {
        Box(Modifier.settingsAnchor("Import profile")) {
            Box(Modifier.settingsAnchor("Export profile")) {
                SettingsOverflowMenu(
                    items = listOf(
                        SettingsMenuItem("Save current setup as profile…", "$PROFILE_GROUP_MENU_TAG-save", onClick = onSave),
                        SettingsMenuItem(
                            "Import profile…",
                            "$PROFILE_GROUP_MENU_TAG-import",
                            onClick = {
                                onImport()
                                reclaimFocus()
                            },
                        ),
                        SettingsMenuItem(
                            "Export current setup as .json",
                            "$PROFILE_GROUP_MENU_TAG-export",
                            onClick = {
                                onExport()
                                reclaimFocus()
                            },
                        ),
                    ),
                    contentDescription = "More profile actions",
                    testTag = PROFILE_GROUP_MENU_TAG,
                    onInteraction = reclaimFocus,
                )
            }
        }
    }
}

/** What differs between the live settings and the selected profile, with the ways out: reset, save as a new profile, update the custom one. */
@Composable
private fun CustomizedBanner(
    profile: ResolvedProfile,
    differences: List<String>,
    onReset: () -> Unit,
    onSaveNew: () -> Unit,
    onUpdate: () -> Unit,
) {
    val tc = tc()
    val shape = RoundedCornerShape(6.dp)
    Column(
        Modifier.fillMaxWidth().background(tc.warnBg, shape).border(0.5.dp, tc.br, shape)
            .padding(horizontal = 10.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.Top) {
            Icon(Icons.Outlined.Tune, contentDescription = null, tint = tc.ts, modifier = Modifier.size(16.dp))
            AppText(
                "Customized — ${differences.size} ${if (differences.size == 1) "setting differs" else "settings differ"} " +
                    "from ${profile.title}: " +
                    differences.joinToString(", "),
                color = tc.tx,
                fontSize = 11.sp,
                fontFamily = UI,
                maxLines = 3,
                modifier = Modifier.weight(1f),
            )
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            AppButton("Reset to profile", onClick = onReset)
            AppButton("Save as new profile…", onClick = onSaveNew, variant = ButtonVariant.Secondary)
            if (profile.custom) {
                AppButton("Update “${profile.title}”", onClick = onUpdate, variant = ButtonVariant.Secondary)
            }
        }
    }
}

/**
 * Inactive tab memory as a switch plus a minutes field. Off stores 0; turning it on restores the last positive value this session saw
 * ([AppState.lastInactiveTabMinutes]) or the default. While it is off the field shows that value dimmed and takes no input.
 */
@Composable
private fun InactiveTabMemoryRow(state: AppState) {
    val tc = tc()
    val minutes = state.settings.inactiveTabUnloadMinutes
    val on = minutes > 0
    SideEffect { if (minutes > 0) state.lastInactiveTabMinutes = minutes }
    val restored = state.lastInactiveTabMinutes.takeIf { it > 0 } ?: DEFAULT_INACTIVE_TAB_UNLOAD_MINUTES
    var timeoutText by remember(minutes) { mutableStateOf((minutes.takeIf { it > 0 } ?: restored).toString()) }
    SettingsRow(
        "Inactive tab memory",
        "Release log rows from reloadable tabs after they have been out of view. The displayed tab and compare panes stay loaded.",
    ) {
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
            AppText("after", color = tc.ts, fontSize = 11.sp, modifier = Modifier.alpha(if (on) 1f else DIMMED_ALPHA))
            SettingsNumberField(
                value = timeoutText,
                onValueChange = { digits ->
                    timeoutText = digits
                    digits.toIntOrNull()?.takeIf { it > 0 }?.let { next ->
                        state.updateSettings { it.copy(inactiveTabUnloadMinutes = next) }
                    }
                },
                unit = "min",
                maxDigits = INACTIVE_MINUTES_DIGITS,
                enabled = on,
                testTag = "inactive-tab-minutes",
            )
            Spacer(Modifier.width(2.dp))
            SettingsSwitch(
                checked = on,
                onCheckedChange = { next ->
                    if (next) {
                        val value = timeoutText.toIntOrNull()?.takeIf { it > 0 } ?: restored
                        timeoutText = value.toString()
                        state.updateSettings { it.copy(inactiveTabUnloadMinutes = value) }
                    } else {
                        if (minutes > 0) state.lastInactiveTabMinutes = minutes
                        state.updateSettings { it.copy(inactiveTabUnloadMinutes = 0) }
                    }
                },
                label = "Inactive tab memory",
                testTag = "inactive-tab-memory-switch",
            )
        }
    }
}

/**
 * The status line of the update check and whether it is an error: "Update available" once a release is known (that fact takes priority over
 * whatever the last raw check status happened to be, e.g. a stale UpToDate from a previous run), otherwise the last check's outcome; null
 * while there is nothing to say.
 */
internal fun updateStatusLine(state: AppState): Pair<String, Boolean>? = when {
    state.availableUpdate != null -> "Update available" to false
    else -> when (val s = state.updateCheckStatus) {
        UpdateStatus.Idle -> null
        UpdateStatus.Checking -> "Checking…" to false
        is UpdateStatus.UpToDate -> "Up to date (v${s.version})" to false
        is UpdateStatus.Failed -> "Couldn't reach GitHub" to true
    }
}

/** Check for updates automatically, with Check now and the status of the last check. */
@Composable
private fun UpdateCheckRow(state: AppState) {
    val tc = tc()
    val status = updateStatusLine(state)
    val updateStatusText = status?.first
    val isError = status?.second == true
    SettingsRow("Check for updates automatically", "Looks for a newer release on GitHub when Indagium starts.") {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            updateStatusText?.let { text ->
                AppText(text, color = if (isError) DANGER_RED else tc.ts, fontSize = 11.sp, modifier = Modifier.testTag("update-check-status"))
            }
            AppButton(
                "Check now",
                onClick = { state.checkForUpdates(manual = true) },
                variant = ButtonVariant.Secondary,
                modifier = Modifier.testTag("update-check-now"),
            )
            SettingsSwitch(
                checked = state.settings.autoCheckUpdates,
                onCheckedChange = { next -> state.updateSettings { it.copy(autoCheckUpdates = next) } },
                label = "Check for updates automatically",
                testTag = "update-check-switch",
            )
        }
    }
}
