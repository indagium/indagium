@file:OptIn(
    androidx.compose.ui.ExperimentalComposeUiApi::class,
    androidx.compose.foundation.ExperimentalFoundationApi::class,
)

package com.indagium.ui

import androidx.compose.foundation.ContextMenuItem
import androidx.compose.foundation.ContextMenuRepresentation
import androidx.compose.foundation.ContextMenuState
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.TextContextMenu
import androidx.compose.foundation.text.TextContextMenuArea
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.ContentCopy
import androidx.compose.material.icons.outlined.ContentCut
import androidx.compose.material.icons.outlined.ContentPaste
import androidx.compose.material.icons.outlined.SelectAll
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.onPointerEvent
import androidx.compose.ui.platform.LocalLocalization
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupProperties
import androidx.compose.ui.window.rememberPopupPositionProviderAtPosition

private val TEXT_CONTEXT_MENU_WIDTH = 200.dp
private val TEXT_CONTEXT_MENU_SHAPE = RoundedCornerShape(7.dp)
private val TEXT_CONTEXT_MENU_ITEM_SHAPE = RoundedCornerShape(3.dp)

/** The app-styled replacement for Compose Desktop's generic text-selection menu. */
internal object IndagiumTextContextMenu : TextContextMenu {
    @Composable
    override fun Area(
        textManager: TextContextMenu.TextManager,
        state: ContextMenuState,
        content: @Composable () -> Unit,
    ) {
        val localization = LocalLocalization.current
        TextContextMenuArea(
            textManager = textManager,
            items = {
                buildList {
                    textManager.cut?.let {
                        add(IndagiumContextMenuItem(localization.cut, it.enabled, it.execute, Icons.Outlined.ContentCut))
                    }
                    textManager.copy?.let {
                        add(IndagiumContextMenuItem(localization.copy, it.enabled, it.execute, Icons.Outlined.ContentCopy))
                    }
                    textManager.paste?.let {
                        add(IndagiumContextMenuItem(localization.paste, it.enabled, it.execute, Icons.Outlined.ContentPaste))
                    }
                    textManager.selectAll?.let {
                        add(IndagiumContextMenuItem(localization.selectAll, it.enabled, it.execute, Icons.Outlined.SelectAll))
                    }
                }
            },
            state = state,
            content = content,
        )
    }
}

private class IndagiumContextMenuItem(
    label: String,
    enabled: Boolean,
    onClick: () -> Unit,
    val icon: ImageVector,
) : ContextMenuItem(label, enabled, onClick)

/** Shared popup chrome for text selections and other legacy context-menu items. */
internal object IndagiumContextMenuRepresentation : ContextMenuRepresentation {
    @Composable
    override fun Representation(state: ContextMenuState, items: () -> List<ContextMenuItem>) {
        val status = state.status
        if (status !is ContextMenuState.Status.Open) return

        // Keep the item list snapshot-aware: clipboard changes can enable/disable Paste while the
        // menu is open, and the standard text manager is responsible for reporting that state.
        val menuItems by remember { derivedStateOf(items) }
        if (menuItems.isEmpty()) {
            SideEffect { state.status = ContextMenuState.Status.Closed }
            return
        }

        val tc = tc()
        Popup(
            popupPositionProvider = rememberPopupPositionProviderAtPosition(status.rect.center),
            onDismissRequest = { state.status = ContextMenuState.Status.Closed },
            properties = PopupProperties(focusable = true),
        ) {
            Column(
                Modifier.width(TEXT_CONTEXT_MENU_WIDTH)
                    .shadow(8.dp, TEXT_CONTEXT_MENU_SHAPE)
                    .background(tc.p, TEXT_CONTEXT_MENU_SHAPE)
                    .border(1.dp, tc.br, TEXT_CONTEXT_MENU_SHAPE)
                    .padding(vertical = 4.dp, horizontal = 4.dp),
            ) {
                menuItems.forEach { item ->
                    IndagiumTextContextMenuItemRow(
                        item = item,
                        onClick = {
                            state.status = ContextMenuState.Status.Closed
                            item.onClick()
                        },
                    )
                }
            }
        }
    }
}

@Composable
private fun IndagiumTextContextMenuItemRow(item: ContextMenuItem, onClick: () -> Unit) {
    val tc = tc()
    val enabled = item.enabled
    val icon = (item as? IndagiumContextMenuItem)?.icon
    var hovered by remember { mutableStateOf(false) }
    Row(
        Modifier.fillMaxWidth()
            .height(32.dp)
            .background(if (hovered && enabled) tc.hv else Color.Transparent, TEXT_CONTEXT_MENU_ITEM_SHAPE)
            .then(if (enabled) Modifier.clickable(onClick = onClick) else Modifier)
            .onPointerEvent(PointerEventType.Enter) { hovered = true }
            .onPointerEvent(PointerEventType.Exit) { hovered = false }
            .padding(horizontal = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.size(24.dp), contentAlignment = Alignment.Center) {
            icon?.let {
                Icon(
                    imageVector = it,
                    contentDescription = null,
                    modifier = Modifier.size(16.dp),
                    tint = if (enabled) tc.td.copy(alpha = 0.65f) else tc.td.copy(alpha = 0.3f),
                )
            }
        }
        Spacer(Modifier.width(8.dp))
        AppText(item.label, color = if (enabled) tc.tx else tc.td, fontSize = 12.sp)
    }
}
