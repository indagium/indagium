@file:OptIn(androidx.compose.ui.ExperimentalComposeUiApi::class)

package com.indagium.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Check
import androidx.compose.material3.Icon
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
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.onPointerEvent
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupProperties
import com.indagium.model.ProfileSpec

// Workspace-profile picker (Settings → General; the first-run setup assistant reuses the same
// card). The card itself is preview-agnostic so a caller can pass its own wireframe, e.g. a
// "Keep my current setup" variant.

private val WIREFRAME_HEIGHT = 68.dp

/** One selectable card: a [preview] area, the [title] and a one-line-ish [description]. */
@Composable
internal fun WorkspaceProfileCard(
    title: String,
    description: String,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    // A "⋯" menu at the preview's top-left (custom profiles: rename, update, export, delete).
    menuActions: List<ProfileMenuAction> = emptyList(),
    preview: @Composable BoxScope.() -> Unit,
) {
    val tc = tc()
    val shape = RoundedCornerShape(8.dp)
    var hovered by remember { mutableStateOf(false) }
    Column(
        modifier
            .clip(shape)
            .background(if (hovered && !selected) tc.hv else tc.p2)
            .border(if (selected) 2.dp else 1.dp, if (selected) tc.ac else tc.br, shape)
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                onClick = onClick,
            )
            .onPointerEvent(PointerEventType.Enter) { hovered = true }
            .onPointerEvent(PointerEventType.Exit) { hovered = false }
            .padding(8.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Box(Modifier.fillMaxWidth().height(WIREFRAME_HEIGHT)) {
            preview()
            if (menuActions.isNotEmpty()) {
                ProfileCardMenu(menuActions, Modifier.align(Alignment.TopStart).padding(3.dp))
            }
            if (selected) {
                Box(
                    Modifier.align(Alignment.TopEnd).padding(3.dp).size(14.dp).background(tc.ac, CircleShape),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(Icons.Outlined.Check, contentDescription = "Selected", tint = tc.bg, modifier = Modifier.size(10.dp))
                }
            }
        }
        AppText(
            title, color = tc.tx, fontSize = 11.sp, fontWeight = FontWeight.SemiBold,
            maxLines = 1, overflow = TextOverflow.Ellipsis,
        )
        AppText(description, color = tc.td, fontSize = 10.sp, maxLines = 3, overflow = TextOverflow.Ellipsis)
    }
}

/** One entry of a card's "⋯" menu. */
internal data class ProfileMenuAction(val label: String, val danger: Boolean = false, val onClick: () -> Unit)

@Composable
private fun ProfileCardMenu(actions: List<ProfileMenuAction>, modifier: Modifier = Modifier) {
    val tc = tc()
    var open by remember { mutableStateOf(false) }
    val density = LocalDensity.current
    Box(modifier) {
        Box(
            Modifier.size(18.dp).background(tc.p.copy(alpha = .85f), CircleShape).border(0.5.dp, tc.br, CircleShape)
                .clip(CircleShape).clickable { open = !open }
                .testTag("profile-card-menu"),
            contentAlignment = Alignment.Center,
        ) {
            AppText("⋯", color = tc.ts, fontSize = 12.sp, fontWeight = FontWeight.Bold)
        }
        if (open) {
            Popup(
                alignment = Alignment.TopStart,
                offset = IntOffset(0, with(density) { 22.dp.roundToPx() }),
                onDismissRequest = { open = false },
                properties = PopupProperties(focusable = true),
            ) {
                Column(
                    Modifier.width(190.dp).shadow(8.dp, RoundedCornerShape(8.dp))
                        .background(tc.p, RoundedCornerShape(8.dp)).border(1.dp, tc.br, RoundedCornerShape(8.dp)).padding(4.dp),
                    verticalArrangement = Arrangement.spacedBy(2.dp),
                ) {
                    actions.forEach { action ->
                        HoverBox(
                            modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(5.dp)),
                            onClick = {
                                open = false
                                action.onClick()
                            },
                        ) {
                            AppText(
                                action.label,
                                color = if (action.danger) DANGER_RED else tc.tx,
                                fontSize = 11.sp,
                                modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
                            )
                        }
                    }
                }
            }
        }
    }
}

/** Cards in a grid of [columns] equal columns; every row has equal-height cards and short rows keep the column width. */
@Composable
internal fun WorkspaceCardGrid(
    cards: List<@Composable (Modifier) -> Unit>,
    columns: Int,
    spacing: Dp,
    modifier: Modifier = Modifier,
) {
    Column(modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(spacing)) {
        cards.chunked(columns).forEach { rowCards ->
            Row(Modifier.fillMaxWidth().height(IntrinsicSize.Max), horizontalArrangement = Arrangement.spacedBy(spacing)) {
                rowCards.forEach { card -> card(Modifier.weight(1f).fillMaxHeight()) }
                repeat(columns - rowCards.size) { Spacer(Modifier.weight(1f)) }
            }
        }
    }
}

/** A card for [profile] (built-in or custom) in the shared style. */
@Composable
internal fun ResolvedProfileCard(
    profile: ResolvedProfile,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    menuActions: List<ProfileMenuAction> = emptyList(),
) {
    WorkspaceProfileCard(
        title = profile.title,
        description = profile.description,
        selected = selected,
        onClick = onClick,
        modifier = modifier,
        menuActions = menuActions,
    ) { ProfileWireframe(profile.spec) }
}

/** The built-in profiles followed by the custom ones, five per row. [menuFor] gives a custom card its "⋯" menu. */
@Composable
internal fun WorkspaceProfileCards(
    profiles: List<ResolvedProfile>,
    selectedId: String?,
    onSelect: (ResolvedProfile) -> Unit,
    menuFor: (ResolvedProfile) -> List<ProfileMenuAction>,
    modifier: Modifier = Modifier,
) {
    WorkspaceCardGrid(
        cards = profiles.map { profile ->
            { cardModifier: Modifier ->
                ResolvedProfileCard(
                    profile = profile,
                    selected = profile.id == selectedId,
                    onClick = { onSelect(profile) },
                    modifier = cardModifier,
                    menuActions = if (profile.custom) menuFor(profile) else emptyList(),
                )
            }
        },
        columns = 5,
        spacing = 8.dp,
        modifier = modifier,
    )
}

/** A miniature window drawn in the profile's own theme: sidebar, inline filter bar, split or single
 *  log pane, notes/video column and minimap strip appear exactly when the spec turns them on. */
@Composable
internal fun ProfileWireframe(spec: ProfileSpec, modifier: Modifier = Modifier) {
    val c = remember(spec.theme) { themeColors(spec.theme) }
    val shape = RoundedCornerShape(5.dp)
    Row(
        modifier.fillMaxWidth().height(WIREFRAME_HEIGHT)
            .clip(shape).background(c.bg).border(0.5.dp, c.br, shape).padding(4.dp),
        horizontalArrangement = Arrangement.spacedBy(3.dp),
    ) {
        if (spec.filterVisible) {
            WireBlock(c.p, c, Modifier.width(20.dp).fillMaxHeight()) { WireLines(c, 4) }
        }
        Column(Modifier.weight(1f).fillMaxHeight(), verticalArrangement = Arrangement.spacedBy(3.dp)) {
            if (spec.filterBarVisible) {
                WireBlock(c.p, c, Modifier.fillMaxWidth().height(8.dp)) {
                    Box(Modifier.padding(start = 3.dp).align(Alignment.CenterStart).size(3.dp).background(c.ac, CircleShape))
                }
            }
            if (spec.openNewFilesWithUnfiltered) {
                Column(Modifier.weight(1f).fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    WireBlock(c.p2, c, Modifier.weight(1f).fillMaxWidth()) { WireLines(c, 3) }
                    WireBlock(c.p2, c, Modifier.weight(1f).fillMaxWidth()) { WireLines(c, 3) }
                }
            } else {
                WireBlock(c.p2, c, Modifier.weight(1f).fillMaxWidth()) { WireLines(c, 7) }
            }
        }
        if (spec.annotationVisible || spec.videoPanelVisible) {
            Column(Modifier.width(22.dp).fillMaxHeight(), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                if (spec.videoPanelVisible) {
                    val videoModifier = if (spec.annotationVisible) Modifier.height(18.dp) else Modifier.weight(1f)
                    WireBlock(c.seq1.copy(alpha = .35f), c, videoModifier.fillMaxWidth()) {}
                }
                if (spec.annotationVisible) {
                    WireBlock(c.p, c, Modifier.weight(1f).fillMaxWidth()) { WireLines(c, 4) }
                }
            }
        }
        if (spec.showMinimap) {
            Box(Modifier.width(3.dp).fillMaxHeight().background(c.td.copy(alpha = .3f), RoundedCornerShape(1.dp)))
        }
    }
}

@Composable
private fun WireBlock(fill: Color, c: ThemeColors, modifier: Modifier, content: @Composable BoxScope.() -> Unit) {
    val shape = RoundedCornerShape(2.dp)
    Box(modifier.clip(shape).background(fill).border(0.5.dp, c.br, shape), content = content)
}

@Composable
private fun WireLines(c: ThemeColors, count: Int) {
    Column(Modifier.padding(2.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
        repeat(count) { i ->
            Box(
                Modifier.fillMaxWidth(if (i % 3 == 1) .6f else .9f).height(2.dp)
                    .background(c.td.copy(alpha = .45f), RoundedCornerShape(1.dp)),
            )
        }
    }
}
