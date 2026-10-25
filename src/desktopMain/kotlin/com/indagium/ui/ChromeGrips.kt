@file:OptIn(androidx.compose.ui.ExperimentalComposeUiApi::class)

package com.indagium.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.wrapContentSize
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.DragIndicator
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.PointerIcon
import androidx.compose.ui.input.pointer.onPointerEvent
import androidx.compose.ui.input.pointer.pointerHoverIcon
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.indagium.model.ChromeEdge
import java.awt.Cursor as AwtCursor

// The pointer-driven parts of the window chrome (ChromeScaffold.kt places them): the grips that move the tab strip and the toolbar, the
// drop strips that show where a dragged part can go, and the splitter that resizes a vertical tab bar. All manual pointer drags
// (detectMoveDragGestures / detectDragGestures), NOT Compose/AWT drag-and-drop: App.kt's file-drop target must never see them, and a
// test can drive them.

private const val DOCK_FILL_ALPHA = 0.12f
private const val DOCK_BORDER_ALPHA = 0.55f
private const val DOCK_DASH_DP = 5f
private const val DOCK_VERTICAL_TEXT_DEGREES = -90f
private const val DOCK_SIDEWAYS_MAX_WIDTH_DP = 48f

internal class ChromeGripCallbacks(
    val onStart: (ChromeSlot) -> Unit,
    val onMove: (Offset) -> Unit,
    val onEnd: (ChromeSlot) -> Unit,
    val onCancel: () -> Unit,
    val isDragging: (ChromeSlot) -> Boolean,
)

// The move handle of the tab strip or the toolbar. A vertical part has a header for it that is always drawn; a horizontal part's grip
// overlays the part's first few dp, takes no room (so a horizontal bar looks exactly as it always did) and shows only under the pointer.
@Composable
internal fun ChromeGrip(slot: ChromeSlot, partVertical: Boolean, description: String, tag: String, callbacks: ChromeGripCallbacks) {
    val tc = tc()
    val current by rememberUpdatedState(callbacks)
    var gripRoot by remember { mutableStateOf(Offset.Zero) }
    var hovered by remember { mutableStateOf(false) }
    // A drag whose grip leaves the composition (the chrome got locked mid-drag) never reports an end: drop its state here.
    DisposableEffect(slot) {
        onDispose { if (current.isDragging(slot)) current.onCancel() }
    }
    val dragging = callbacks.isDragging(slot)
    val visible = partVertical || hovered || dragging
    Box(
        Modifier
            .fillMaxSize()
            .moveCursor()
            .onGloballyPositioned { gripRoot = it.positionInRoot() }
            .onPointerEvent(PointerEventType.Enter) { hovered = true }
            .onPointerEvent(PointerEventType.Exit) { hovered = false }
            .testTag(tag)
            .semantics { contentDescription = description }
            .pointerInput(slot) {
                detectMoveDragGestures(
                    onDragStart = { down ->
                        current.onStart(slot)
                        current.onMove(gripRoot + down)
                    },
                    onDragEnd = { current.onEnd(slot) },
                    onDragCancel = { current.onCancel() },
                    onDrag = { change, _ ->
                        change.consume()
                        current.onMove(gripRoot + change.position)
                    },
                )
            }
            .then(if (hovered || dragging) Modifier.background(tc.hv) else Modifier),
        contentAlignment = Alignment.Center,
    ) {
        if (visible) {
            Icon(
                Icons.Outlined.DragIndicator,
                contentDescription = null,
                tint = if (hovered || dragging) tc.ts else tc.td,
                modifier = Modifier.size(12.dp).then(if (partVertical) Modifier.rotate(90f) else Modifier),
            )
        }
    }
}

/**
 * The handle on the content-side edge of a vertical tab bar. [widthNow] is the bar's current width (dp, including a draft); [onDraft]
 * is told the new width while dragging (px divided by density, the sign following which side the content is on) and [onCommit] once on
 * release; a double-click calls [onReset]. Manual pointer drag, like the grips.
 */
@Composable
internal fun ChromeSplitter(
    edge: ChromeEdge,
    widthNow: () -> Float,
    onDraft: (Float) -> Unit,
    onCommit: () -> Unit,
    onReset: () -> Unit,
) {
    val density = LocalDensity.current.density
    val cursor = remember { AwtCursor.getPredefinedCursor(AwtCursor.E_RESIZE_CURSOR) }
    val currentWidth by rememberUpdatedState(widthNow)
    val currentDraft by rememberUpdatedState(onDraft)
    val currentCommit by rememberUpdatedState(onCommit)
    val currentReset by rememberUpdatedState(onReset)
    // Dragging towards the content grows the bar: right for a left bar, left for a right bar.
    val sign = if (edge == ChromeEdge.LEFT) 1f else -1f
    // Invisible at rest, hovered and dragged alike: only the resize cursor and the hit area tell it is there.
    Box(
        Modifier
            .fillMaxSize()
            .testTag("chrome-splitter-tabs")
            .semantics { contentDescription = "Resize tab bar" }
            .pointerHoverIcon(PointerIcon(cursor))
            .pointerInput(Unit) { detectTapGestures(onDoubleTap = { currentReset() }) }
            .pointerInput(density, sign) {
                detectDragGestures(
                    onDragStart = { dragCursorOverride.value = cursor },
                    onDragEnd = {
                        dragCursorOverride.value = null
                        currentCommit()
                    },
                    onDragCancel = {
                        dragCursorOverride.value = null
                        currentCommit()
                    },
                    onDrag = { change, amount ->
                        change.consume()
                        currentDraft(currentWidth() + sign * amount.x / density)
                    },
                )
            },
    )
}

// ---------------------------------------------------------------------------------------------------------------
// Drop strips
// ---------------------------------------------------------------------------------------------------------------

@Composable
internal fun ChromeDockOverlay(slot: ChromeSlot, zones: List<ChromeDockZone>, target: ChromeDockTarget?) {
    Box(Modifier.fillMaxSize()) {
        zones.forEach { zone ->
            key(zone.target.key()) { ChromeDockStrip(slot, zone, active = zone.target == target) }
        }
    }
}

@Composable
private fun ChromeDockStrip(slot: ChromeSlot, zone: ChromeDockZone, active: Boolean) {
    val tc = tc()
    val rect = zone.rect
    // A narrow, tall strip (along a left or right edge) reads its caption sideways.
    val sideways = rect.w < DOCK_SIDEWAYS_MAX_WIDTH_DP && rect.h > rect.w
    Box(
        Modifier
            .offset(rect.x.dp, rect.y.dp)
            .size(rect.w.dp, rect.h.dp)
            .testTag("chrome-dock-${zone.target.key()}")
            .drawBehind {
                val dash = DOCK_DASH_DP.dp.toPx()
                if (active) drawRect(tc.ac.copy(alpha = DOCK_FILL_ALPHA))
                drawRoundRect(
                    color = tc.ac.copy(alpha = if (active) 1f else DOCK_BORDER_ALPHA),
                    cornerRadius = CornerRadius(4.dp.toPx()),
                    style = Stroke(width = 1.dp.toPx(), pathEffect = PathEffect.dashPathEffect(floatArrayOf(dash, dash))),
                )
            },
        contentAlignment = Alignment.Center,
    ) {
        AppText(
            chromeDockLabel(slot, zone.target),
            color = tc.ac,
            fontSize = 10.sp,
            fontWeight = if (active) FontWeight.Medium else FontWeight.Normal,
            maxLines = 1,
            modifier = Modifier.wrapContentSize(unbounded = true).then(if (sideways) Modifier.rotate(DOCK_VERTICAL_TEXT_DEGREES) else Modifier),
        )
    }
}
