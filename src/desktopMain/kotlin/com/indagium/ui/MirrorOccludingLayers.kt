package com.indagium.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntRect
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.window.DialogProperties
import androidx.compose.ui.window.PopupPositionProvider
import androidx.compose.ui.window.PopupProperties
import java.util.concurrent.atomic.AtomicLong
import androidx.compose.ui.window.Dialog as ComposeDialog
import androidx.compose.ui.window.Popup as ComposePopup

/** AppState for the window whose Compose overlays can cover a native mirror surface. */
internal val LocalMirrorOverlayAppState = staticCompositionLocalOf<AppState?> { null }

/** Non-null only for a detached mirror window; its overlays must mask only that tab's surface. */
internal val LocalMirrorOverlayTabId = staticCompositionLocalOf<String?> { null }

private val nextMirrorOverlaySourceId = AtomicLong()

@Composable
private fun RegisterMirrorOcclusionForCurrentWindow() {
    val state = LocalMirrorOverlayAppState.current ?: return
    val tabId = LocalMirrorOverlayTabId.current
    val source = remember(state) { "compose-layer-${nextMirrorOverlaySourceId.incrementAndGet()}" }
    DisposableEffect(state, tabId, source) {
        if (tabId == null) {
            state.setEmbeddedMirrorGlobalOverlayOccluded(source, true)
        } else {
            state.setEmbeddedMirrorOverlayOcclusionSource(tabId, source, true)
        }
        onDispose {
            if (tabId == null) {
                state.setEmbeddedMirrorGlobalOverlayOccluded(source, false)
            } else {
                state.setEmbeddedMirrorOverlayOcclusionSource(tabId, source, false)
            }
        }
    }
}

/** Compose's same-window Dialog is a scene layer, so keep the elevated JAWT mirror masked under it. */
@Composable
internal fun Dialog(
    onDismissRequest: () -> Unit,
    properties: DialogProperties = DialogProperties(),
    content: @Composable () -> Unit,
) {
    RegisterMirrorOcclusionForCurrentWindow()
    ComposeDialog(onDismissRequest = onDismissRequest, properties = properties, content = content)
}

/**
 * Anchors a popup to its parent the way Compose's own `Popup(alignment, offset)` does (Compose keeps its provider internal).
 * Used with [FileDropPopup], which places the popup body itself inside a window-sized layer.
 */
private class AlignmentOffsetProvider(private val alignment: Alignment, private val offset: IntOffset) : PopupPositionProvider {
    override fun calculatePosition(
        anchorBounds: IntRect,
        windowSize: IntSize,
        layoutDirection: LayoutDirection,
        popupContentSize: IntSize,
    ): IntOffset {
        val anchorAlignmentPoint = alignment.align(IntSize.Zero, anchorBounds.size, layoutDirection)
        val popupAlignmentPoint = -alignment.align(IntSize.Zero, popupContentSize, layoutDirection)
        val resolvedOffset = IntOffset(offset.x * (if (layoutDirection == LayoutDirection.Ltr) 1 else -1), offset.y)
        return anchorBounds.topLeft + anchorAlignmentPoint + popupAlignmentPoint + resolvedOffset
    }
}

/**
 * Covers both public Popup placement overloads while retaining their normal Compose behavior. A focusable popup inside the
 * app additionally keeps external file drops working (see [LocalFileDropTarget] / [FileDropPopup]).
 */
@Composable
internal fun Popup(
    alignment: Alignment = Alignment.TopStart,
    offset: IntOffset = IntOffset.Zero,
    onDismissRequest: (() -> Unit)? = null,
    properties: PopupProperties = PopupProperties(),
    content: @Composable () -> Unit,
) {
    val dropTarget = LocalFileDropTarget.current
    RegisterMirrorOcclusionForCurrentWindow()
    if (properties.focusable && dropTarget != null) {
        val provider = remember(alignment, offset) { AlignmentOffsetProvider(alignment, offset) }
        FileDropPopup(provider, dropTarget, onDismissRequest, properties, content)
        return
    }
    ComposePopup(
        alignment = alignment,
        offset = offset,
        onDismissRequest = onDismissRequest,
        properties = properties,
        content = content,
    )
}

@Composable
internal fun Popup(
    popupPositionProvider: PopupPositionProvider,
    onDismissRequest: (() -> Unit)? = null,
    properties: PopupProperties = PopupProperties(),
    content: @Composable () -> Unit,
) {
    val dropTarget = LocalFileDropTarget.current
    RegisterMirrorOcclusionForCurrentWindow()
    if (properties.focusable && dropTarget != null) {
        FileDropPopup(popupPositionProvider, dropTarget, onDismissRequest, properties, content)
        return
    }
    ComposePopup(
        popupPositionProvider = popupPositionProvider,
        onDismissRequest = onDismissRequest,
        properties = properties,
        content = content,
    )
}
