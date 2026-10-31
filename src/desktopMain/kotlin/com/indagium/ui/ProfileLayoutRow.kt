package com.indagium.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.PopupProperties
import com.indagium.model.CustomLogViewLayout
import com.indagium.model.LogViewScope
import com.indagium.model.ProfileLayoutRef

// Settings › General's "Layout" row under the profile cards: the layout the selected profile points at, as a small thumbnail and its
// name. A custom profile's layout is changed from a dropdown (presets, saved layouts, "Keep current layout"); a built-in profile's is
// fixed, so it reads as text.

private const val LAYOUT_ROW_THUMB_WIDTH_DP = 44
private const val LAYOUT_ROW_THUMB_HEIGHT_DP = 26
private const val LAYOUT_ROW_SELECT_WIDTH_DP = 200

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
    Row(
        Modifier.testTag("profile-layout-row"),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        AppText("Layout", color = tc.td, fontSize = 10.sp, fontFamily = UI, fontWeight = FontWeight.SemiBold)
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
        Box(
            Modifier.size(LAYOUT_ROW_THUMB_WIDTH_DP.dp, LAYOUT_ROW_THUMB_HEIGHT_DP.dp).clip(CORNER_SM)
                .border(1.dp, tc.br, CORNER_SM).padding(2.dp).testTag("profile-layout-thumb"),
        ) { LayoutThumbCanvas(tree, filterBar, tc.td, Modifier.fillMaxSize()) }
        if (profile.custom) {
            ProfileLayoutSelect(profileLayoutName(ref, saved), ref, saved, onInteraction) { state.setWorkspaceProfileLayout(profile.id, it) }
        } else {
            AppText(
                profileLayoutName(ref, saved), color = tc.tx, fontSize = 11.sp, fontFamily = UI,
                modifier = Modifier.testTag("profile-layout-readonly"),
            )
        }
    }
}

@Composable
private fun ProfileLayoutSelect(
    currentLabel: String,
    current: ProfileLayoutRef?,
    saved: List<CustomLogViewLayout>,
    onInteraction: () -> Unit,
    onSelected: (ProfileLayoutRef?) -> Unit,
) {
    val tc = tc()
    val density = LocalDensity.current
    var open by remember { mutableStateOf(false) }
    var suppressToggleUntilMs by remember { mutableStateOf(0L) }
    Box {
        HoverBox(
            modifier = Modifier.width(LAYOUT_ROW_SELECT_WIDTH_DP.dp).height(26.dp).testTag("profile-layout-select")
                .clip(CORNER_SM).background(tc.p2, CORNER_SM).border(1.dp, tc.br, CORNER_SM),
            onClick = {
                if (System.currentTimeMillis() >= suppressToggleUntilMs) {
                    open = !open
                    onInteraction()
                }
            },
        ) {
            Row(
                Modifier.fillMaxSize().padding(horizontal = 9.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                AppText(currentLabel, color = tc.tx, fontSize = 11.sp, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
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
                    onInteraction()
                },
                properties = PopupProperties(focusable = false),
            ) {
                Column(
                    Modifier.widthIn(min = LAYOUT_ROW_SELECT_WIDTH_DP.dp, max = 260.dp)
                        .shadow(8.dp, RoundedCornerShape(8.dp))
                        .background(tc.p, RoundedCornerShape(8.dp))
                        .border(1.dp, tc.br, RoundedCornerShape(8.dp))
                        .padding(4.dp),
                    verticalArrangement = Arrangement.spacedBy(2.dp),
                ) {
                    profileLayoutOptions(saved).forEach { option ->
                        val active = option.ref == current
                        HoverBox(
                            modifier = Modifier.fillMaxWidth().testTag("profile-layout-option-${option.key}").clip(RoundedCornerShape(5.dp)),
                            baseBg = if (active) tc.abg else Color.Transparent,
                            onClick = {
                                open = false
                                onSelected(option.ref)
                                onInteraction()
                            },
                        ) {
                            AppText(
                                option.label,
                                color = if (active) tc.ac else tc.tx,
                                fontSize = 11.sp,
                                fontWeight = if (active) FontWeight.SemiBold else FontWeight.Normal,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
                            )
                        }
                    }
                }
            }
        }
    }
}
