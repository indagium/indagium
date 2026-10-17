@file:OptIn(androidx.compose.ui.ExperimentalComposeUiApi::class, androidx.compose.foundation.ExperimentalFoundationApi::class)

package com.indagium.ui

import androidx.compose.foundation.draganddrop.dragAndDropTarget
import androidx.compose.foundation.layout.Box
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.draganddrop.DragAndDropEvent
import androidx.compose.ui.draganddrop.DragAndDropTarget
import androidx.compose.ui.draganddrop.dragData
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.layout.onPlaced
import androidx.compose.ui.layout.positionInWindow
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntRect
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.round
import androidx.compose.ui.window.PopupPositionProvider
import androidx.compose.ui.window.PopupProperties
import androidx.compose.ui.window.Popup as ComposePopup

/**
 * The app's external-file drop handler (the one `App` installs on its root with `dragAndDropTarget`), made available to
 * popups. Null when no such handler exists (tests, detached windows), which leaves every popup exactly as Compose draws it.
 *
 * Why popups need it: a *focusable* Compose popup is a scene layer, and the scene hands every drag-and-drop event to the
 * focused layer only (`CanvasLayersComposeScene.rootDragAndDropNode` -> `focusedOwner.dragAndDropOwner`). While a dropdown such as
 * the log toolbar's Export menu is open, a file dragged in from Finder therefore never reaches the app's root target and the
 * drop does nothing. [FileDropPopup] fixes that by giving the focused layer a window-sized drop target that forwards to this handler.
 */
internal val LocalFileDropTarget = staticCompositionLocalOf<DragAndDropTarget?> { null }

/** The placement a popup with [PopupPositionProvider] semantics gets inside the window-sized layer of [FileDropPopup]. */
private object ZeroPositionProvider : PopupPositionProvider {
    override fun calculatePosition(
        anchorBounds: IntRect,
        windowSize: IntSize,
        layoutDirection: LayoutDirection,
        popupContentSize: IntSize,
    ): IntOffset = IntOffset.Zero
}

/** Compose's own clamp for `clippingEnabled` popups: keep a popup smaller than the window fully inside it. */
internal fun clipPopupPosition(position: IntOffset, contentSize: IntSize, windowSize: IntSize): IntOffset = IntOffset(
    x = if (contentSize.width < windowSize.width) position.x.coerceIn(0, windowSize.width - contentSize.width) else 0,
    y = if (contentSize.height < windowSize.height) position.y.coerceIn(0, windowSize.height - contentSize.height) else 0,
)

/**
 * A focusable popup that still lets the user drop files from the OS file manager onto the app.
 *
 * It is a normal Compose popup whose layer spans the whole window: a transparent scrim carries the drop target (and the
 * click-outside dismissal Compose would otherwise do for a layer that is not window-sized), and [content] is placed where
 * [positionProvider] would have put it, clamped into the window when [PopupProperties.clippingEnabled]. A drop is forwarded
 * to [dropTarget]; when it was accepted the popup is dismissed, since the user has moved on to the dropped file.
 */
@Composable
internal fun FileDropPopup(
    positionProvider: PopupPositionProvider,
    dropTarget: DragAndDropTarget,
    onDismissRequest: (() -> Unit)?,
    properties: PopupProperties,
    content: @Composable () -> Unit,
) {
    // The anchor is the parent of this call, exactly as Compose's own Popup measures it.
    var anchorBounds by remember { mutableStateOf(IntRect.Zero) }
    Layout(
        content = {},
        modifier = Modifier.onPlaced { placed ->
            placed.parentCoordinates?.let { anchorBounds = IntRect(it.positionInWindow().round(), it.size) }
        },
    ) { _, _ -> layout(0, 0) {} }

    val currentOnDismiss by rememberUpdatedState(onDismissRequest)
    val currentDropTarget by rememberUpdatedState(dropTarget)
    val scrimTarget = remember {
        object : DragAndDropTarget {
            override fun onStarted(event: DragAndDropEvent) = currentDropTarget.onStarted(event)

            override fun onDrop(event: DragAndDropEvent): Boolean {
                val accepted = currentDropTarget.onDrop(event)
                if (accepted) currentOnDismiss?.invoke()
                return accepted
            }
        }
    }
    val dismissOnOutsideClick = properties.dismissOnClickOutside && onDismissRequest != null
    val layoutDirection = LocalLayoutDirection.current

    ComposePopup(
        popupPositionProvider = ZeroPositionProvider,
        onDismissRequest = onDismissRequest,
        properties = properties,
    ) {
        val scrimModifier = Modifier
            .dragAndDropTarget(
                shouldStartDragAndDrop = { event -> runCatching { isFileDropData(event.dragData()) }.getOrDefault(false) },
                target = scrimTarget,
            )
            .then(
                if (dismissOnOutsideClick) {
                    Modifier.pointerInput(Unit) {
                        awaitPointerEventScope {
                            while (true) {
                                if (awaitPointerEvent().type == PointerEventType.Press) currentOnDismiss?.invoke()
                            }
                        }
                    }
                } else {
                    Modifier
                },
            )
        Layout(
            content = {
                Box(scrimModifier)
                // Present in hit testing so a press on a blank part of the popup body never reaches the scrim below it.
                Box(Modifier.pointerInput(Unit) { awaitPointerEventScope { while (true) awaitPointerEvent() } }) { content() }
            },
        ) { measurables, constraints ->
            val windowWidth = constraints.maxWidth.takeIf { it != Constraints.Infinity }
            val windowHeight = constraints.maxHeight.takeIf { it != Constraints.Infinity }
            val body = measurables[1].measure(constraints.copy(minWidth = 0, minHeight = 0))
            val width = windowWidth ?: body.width
            val height = windowHeight ?: body.height
            val scrim = measurables[0].measure(Constraints.fixed(width, height))
            layout(width, height) {
                scrim.place(0, 0)
                val windowSize = IntSize(width, height)
                val position = positionProvider.calculatePosition(
                    anchorBounds = anchorBounds,
                    windowSize = windowSize,
                    layoutDirection = layoutDirection,
                    popupContentSize = IntSize(body.width, body.height),
                )
                val placed = if (properties.clippingEnabled) {
                    clipPopupPosition(position, IntSize(body.width, body.height), windowSize)
                } else {
                    position
                }
                body.place(placed)
            }
        }
    }
}
