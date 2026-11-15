package com.indagium.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.BoxWithConstraints
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
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInWindow
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntRect
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.PopupPositionProvider
import androidx.compose.ui.window.PopupProperties
import com.indagium.model.ProfileSpec
import kotlin.math.roundToInt

// Workspace-profile picker (Settings → General; the first-run setup assistant reuses the same
// card). The card itself is preview-agnostic so a caller can pass its own wireframe, e.g. a
// "Keep my current setup" variant.

private val WIREFRAME_HEIGHT = 68.dp

/** The "⋯" menu on a custom profile's card: always drawn (never hover-only), on the caption line, an 18dp target. */
private val CARD_MENU_TARGET = 18.dp

/** Below this width the profile cards go four to a row instead of five, so a name keeps room for two lines. */
private val FIVE_CARD_COLUMNS_MIN_WIDTH = 600.dp

/**
 * One selectable card: a [preview] area, the [title] and a short [description]. A picture picker, so it follows the one selected-state
 * rule ([settingsPickerSurface]): accent border, wash and caption while chosen, the hover tint only otherwise.
 */
@Composable
internal fun WorkspaceProfileCard(
    title: String,
    description: String,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    // A "⋯" menu on the caption line (custom profiles: rename, update, export, delete).
    menuActions: List<ProfileMenuAction> = emptyList(),
    onMenuClosed: () -> Unit = {},
    preview: @Composable BoxScope.() -> Unit,
) {
    val tc = tc()
    val shape = RoundedCornerShape(8.dp)
    val interaction = remember { MutableInteractionSource() }
    val hovered by interaction.collectIsHoveredAsState()
    var cardLeftPx by remember { mutableStateOf(0) }
    Column(
        modifier
            .onGloballyPositioned { cardLeftPx = it.positionInWindow().x.roundToInt() }
            .settingsPickerSurface(selected, hovered, shape, tc)
            .hoverable(interaction)
            .semantics { this.selected = selected }
            .clickable(interactionSource = interaction, indication = null, onClick = onClick)
            .padding(8.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Box(Modifier.fillMaxWidth().height(WIREFRAME_HEIGHT)) { preview() }
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            AppText(
                title, color = settingsPickerCaptionColor(selected, tc), fontSize = 11.sp, fontWeight = FontWeight.SemiBold,
                maxLines = 2, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f),
            )
            if (menuActions.isNotEmpty()) ProfileCardMenu(menuActions, onMenuClosed, cardLeftPx = { cardLeftPx })
        }
        AppText(description, color = tc.ts, fontSize = 10.sp, maxLines = 3, overflow = TextOverflow.Ellipsis)
    }
}

private val MENU_PAGE_MARGIN = 12.dp

/**
 * Where a card's "⋯" menu opens: under the button, its start edge on the card's start edge, and kept inside the page (the Settings content
 * column) and the window, so a menu wider than a narrow card never reaches into the gutter.
 */
private class CardMenuPosition(
    private val cardLeftPx: () -> Int,
    private val page: IntRect?,
    private val marginPx: Int,
    private val gapPx: Int,
) : PopupPositionProvider {
    override fun calculatePosition(anchorBounds: IntRect, windowSize: IntSize, layoutDirection: LayoutDirection, popupContentSize: IntSize): IntOffset {
        val minX = (page?.left ?: 0) + marginPx
        val maxX = ((page?.right ?: windowSize.width).coerceAtMost(windowSize.width) - marginPx - popupContentSize.width).coerceAtLeast(minX)
        val x = cardLeftPx().coerceIn(minX, maxX)
        val below = anchorBounds.bottom + gapPx
        val y = if (below + popupContentSize.height <= windowSize.height) below else (anchorBounds.top - gapPx - popupContentSize.height).coerceAtLeast(0)
        return IntOffset(x, y)
    }
}

/** One entry of a card's "⋯" menu. */
internal data class ProfileMenuAction(val label: String, val danger: Boolean = false, val onClick: () -> Unit)

@Composable
private fun ProfileCardMenu(actions: List<ProfileMenuAction>, onClosed: () -> Unit, cardLeftPx: () -> Int, modifier: Modifier = Modifier) {
    val tc = tc()
    var open by remember { mutableStateOf(false) }
    val density = LocalDensity.current
    val interaction = remember { MutableInteractionSource() }
    val hovered by interaction.collectIsHoveredAsState()
    Box(modifier) {
        Box(
            Modifier.size(CARD_MENU_TARGET)
                .clip(CORNER_SM)
                .background(if (hovered || open) tc.hv else Color.Transparent, CORNER_SM)
                .hoverable(interaction)
                .clickable(interactionSource = interaction, indication = null) { open = !open }
                .semantics { contentDescription = "More profile actions" }
                .testTag("profile-card-menu"),
            contentAlignment = Alignment.Center,
        ) {
            AppText("⋯", color = tc.ts, fontSize = 12.sp, fontWeight = FontWeight.Bold)
        }
        if (open) {
            val contentBounds = LocalSettingsAnchors.current?.content?.takeIf { it.isAttached }?.boundsInWindow()
            val margin = with(density) { MENU_PAGE_MARGIN.roundToPx() }
            Popup(
                popupPositionProvider = remember(contentBounds, margin) {
                    CardMenuPosition(
                        cardLeftPx = cardLeftPx,
                        page = contentBounds?.let { IntRect(it.left.roundToInt(), it.top.roundToInt(), it.right.roundToInt(), it.bottom.roundToInt()) },
                        marginPx = margin,
                        gapPx = with(density) { 4.dp.roundToPx() },
                    )
                },
                onDismissRequest = {
                    open = false
                    onClosed()
                },
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
                                // A dismissed popup takes keyboard focus with it, so hand it back first.
                                onClosed()
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
    panels: ProfilePanels,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    menuActions: List<ProfileMenuAction> = emptyList(),
    onMenuClosed: () -> Unit = {},
) {
    WorkspaceProfileCard(
        title = profile.title,
        description = profile.description,
        selected = selected,
        onClick = onClick,
        modifier = modifier,
        menuActions = menuActions,
        onMenuClosed = onMenuClosed,
    ) { ProfileWireframe(profile.spec, panels) }
}

/** The built-in profiles followed by the custom ones, five per row (four in a narrow window). [menuFor] gives a custom card its "⋯" menu. */
@Composable
internal fun WorkspaceProfileCards(
    profiles: List<ResolvedProfile>,
    selectedId: String?,
    onSelect: (ResolvedProfile) -> Unit,
    panelsFor: (ResolvedProfile) -> ProfilePanels,
    menuFor: (ResolvedProfile) -> List<ProfileMenuAction>,
    modifier: Modifier = Modifier,
    onMenuClosed: () -> Unit = {},
) {
    BoxWithConstraints(modifier.fillMaxWidth()) {
        WorkspaceCardGrid(
            cards = profiles.map { profile ->
                { cardModifier: Modifier ->
                    ResolvedProfileCard(
                        profile = profile,
                        panels = panelsFor(profile),
                        selected = profile.id == selectedId,
                        onClick = { onSelect(profile) },
                        modifier = cardModifier,
                        menuActions = if (profile.custom) menuFor(profile) else emptyList(),
                        onMenuClosed = onMenuClosed,
                    )
                }
            },
            columns = if (maxWidth < FIVE_CARD_COLUMNS_MIN_WIDTH) 4 else 5,
            spacing = 8.dp,
        )
    }
}

/** A miniature window drawn in the profile's own theme: sidebar, inline filter bar, notes/video column (from [panels], i.e. from the
 *  layout the profile points at), split or single log pane and minimap strip (from the spec). */
@Composable
internal fun ProfileWireframe(spec: ProfileSpec, panels: ProfilePanels, modifier: Modifier = Modifier) {
    val c = remember(spec.theme) { themeColors(spec.theme) }
    val shape = RoundedCornerShape(5.dp)
    Row(
        modifier.fillMaxWidth().height(WIREFRAME_HEIGHT)
            .clip(shape).background(c.bg).border(0.5.dp, c.br, shape).padding(4.dp),
        horizontalArrangement = Arrangement.spacedBy(3.dp),
    ) {
        if (panels.filter) {
            WireBlock(c.p, c, Modifier.width(20.dp).fillMaxHeight()) { WireLines(c, 4) }
        }
        Column(Modifier.weight(1f).fillMaxHeight(), verticalArrangement = Arrangement.spacedBy(3.dp)) {
            if (panels.filterBar) {
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
        if (panels.notes || panels.video) {
            Column(Modifier.width(22.dp).fillMaxHeight(), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                if (panels.video) {
                    val videoModifier = if (panels.notes) Modifier.height(18.dp) else Modifier.weight(1f)
                    WireBlock(c.seq1.copy(alpha = .35f), c, videoModifier.fillMaxWidth()) {}
                }
                if (panels.notes) {
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
