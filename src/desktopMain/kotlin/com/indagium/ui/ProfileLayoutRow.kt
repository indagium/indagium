package com.indagium.ui

import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.indagium.model.CustomLogViewLayout
import com.indagium.model.LogViewScope
import com.indagium.model.ProfileLayoutRef

// Settings › General's "Layout" row under the profile cards: the layout the selected profile points at, as a small thumbnail and its
// name. A custom profile's layout is changed from a dropdown (presets, saved layouts, "Keep current layout"); a built-in profile's is
// fixed, so its name is its read-only value beside the thumbnail.

private const val LAYOUT_THUMB_WIDTH_DP = 28
private const val LAYOUT_THUMB_HEIGHT_DP = 16

/** The row's description for a profile whose layout can be changed. */
internal const val PROFILE_LAYOUT_DESCRIPTION = "The panel layout this profile applies."

/** The row's description for a built-in profile, whose layout is fixed: its name is the row's value. */
internal const val BUILT_IN_PROFILE_LAYOUT_DESCRIPTION = "The panel layout this built-in profile applies. It cannot be changed."

/** One entry of the layout dropdown. */
private data class ProfileLayoutOption(val key: String, val label: String, val ref: ProfileLayoutRef?)

private fun profileLayoutOptions(saved: List<CustomLogViewLayout>): List<ProfileLayoutOption> =
    LogViewLayoutPreset.forScope(LogViewScope.SINGLE).map { ProfileLayoutOption("preset-${it.name}", it.label, ProfileLayoutRef.Preset(it.id)) } +
        saved.map { ProfileLayoutOption("saved-${it.id}", it.name, ProfileLayoutRef.Saved(it.id)) } +
        ProfileLayoutOption("keep", KEEP_CURRENT_LAYOUT_LABEL, null)

/** [profile]'s layout row. [onInteraction] hands keyboard focus back to the host after a click or a dismissed popup. */
@Composable
internal fun ProfileLayoutRow(state: AppState, profile: ResolvedProfile, onInteraction: () -> Unit) {
    val tc = tc()
    val saved = state.settings.customLogViewLayouts
    val ref = profile.spec.layout?.takeUnless { it is ProfileLayoutRef.Saved && saved.none { s -> s.id == it.id } }
    val tree = remember(ref, saved) {
        when (ref) {
            null -> null
            is ProfileLayoutRef.Preset -> presetThumbTree(LogViewLayoutPreset.of(ref.id))
            is ProfileLayoutRef.Saved -> saved.firstOrNull { it.id == ref.id }?.let(::savedLayoutThumbTree)
        }
    }
    val filterBar = when (ref) {
        is ProfileLayoutRef.Preset -> LogViewLayoutPreset.of(ref.id).showsFilterBar
        is ProfileLayoutRef.Saved -> saved.firstOrNull { it.id == ref.id }?.filterBarVisible == true
        null -> false
    }
    val thumbnail = @Composable {
        Box(
            Modifier.size(LAYOUT_THUMB_WIDTH_DP.dp, LAYOUT_THUMB_HEIGHT_DP.dp).clip(CORNER_SM)
                .border(1.dp, tc.br, CORNER_SM).padding(1.5.dp).testTag("profile-layout-thumb"),
        ) { LayoutThumbCanvas(tree, filterBar, tc.td, Modifier.fillMaxSize()) }
    }
    val rowModifier = Modifier.testTag("profile-layout-row")
    if (profile.custom) {
        val options = profileLayoutOptions(saved)
        SettingsRow("Layout", PROFILE_LAYOUT_DESCRIPTION, modifier = rowModifier) {
            SettingsDropdown(
                options = options,
                selected = options.firstOrNull { it.ref == ref } ?: options.last(),
                label = { it.label },
                onSelect = { state.setWorkspaceProfileLayout(profile.id, it.ref) },
                testTag = "profile-layout-select",
                optionTag = { "profile-layout-option-${it.key}" },
                leading = thumbnail,
                onInteraction = onInteraction,
            )
        }
    } else {
        // Read-only text, not a field: the thumbnail sits beside the layout name so it cannot be taken for an icon button.
        SettingsRow("Layout", BUILT_IN_PROFILE_LAYOUT_DESCRIPTION, modifier = rowModifier) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                thumbnail()
                AppText(
                    profileLayoutName(ref, saved),
                    color = tc.tx,
                    fontSize = 12.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.testTag("profile-layout-readonly"),
                )
            }
        }
    }
}
