@file:OptIn(androidx.compose.ui.ExperimentalComposeUiApi::class)

package com.openlog.ui

import androidx.compose.foundation.*
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CheckboxDefaults
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.input.pointer.*
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.openlog.model.LogLevel
import java.awt.KeyboardFocusManager
import java.awt.Cursor as AwtCursor

@Composable fun tc() = LocalTheme.current

@Composable fun monoFont() = if (LocalUseMono.current) FontFamily.Monospace else FontFamily.Default

@Composable fun baseSp() = LocalFontBase.current.sp

// ── Hover ────────────────────────────────────────────────────────────
@Composable
fun HoverBox(
    modifier: Modifier = Modifier,
    baseBg: Color = Color.Transparent,
    hoverBg: Color = LocalTheme.current.hv,
    onClick: (() -> Unit)? = null,
    content: @Composable BoxScope.() -> Unit,
) {
    var hovered by remember { mutableStateOf(false) }
    Box(
        modifier = modifier
            .background(if (hovered) hoverBg else baseBg)
            .onPointerEvent(PointerEventType.Enter) { hovered = true }
            .onPointerEvent(PointerEventType.Exit) { hovered = false }
            .then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier),
        content = content,
    )
}

// ── Resizable dividers ───────────────────────────────────────────────
// Compose pointer events are in layout pixels; panel widths are stored in dp.
// Dividing by density converts px → dp so the divider tracks the cursor exactly.
private fun activeWindow() = KeyboardFocusManager.getCurrentKeyboardFocusManager().activeWindow

// Non-null while any HDivider or VDivider is being dragged; App.kt reads this to show a full-window cursor overlay.
internal val dragCursorOverride = mutableStateOf<AwtCursor?>(null)

@Composable
fun HDivider(onDelta: (Float) -> Unit) {
    val tc = tc()
    val density = LocalDensity.current.density
    val cursor  = remember { AwtCursor.getPredefinedCursor(AwtCursor.E_RESIZE_CURSOR) }
    var hovered  by remember { mutableStateOf(false) }
    var dragging by remember { mutableStateOf(false) }
    // 10dp hit area keeps the pointer inside during normal drags.
    // For fast drags the AWT window cursor is locked for the entire drag so no
    // flicker occurs when the pointer briefly exits the visual stripe.
    Box(
        Modifier
            .width(10.dp).fillMaxHeight()
            .onPointerEvent(PointerEventType.Enter) { hovered = true }
            .onPointerEvent(PointerEventType.Exit)  { hovered = false }
            .pointerInput(density) {
                detectDragGestures(
                    onDragStart  = { dragging = true;  dragCursorOverride.value = cursor; activeWindow()?.cursor = cursor },
                    onDragEnd    = { dragging = false; dragCursorOverride.value = null;   activeWindow()?.cursor = AwtCursor.getDefaultCursor() },
                    onDragCancel = { dragging = false; dragCursorOverride.value = null;   activeWindow()?.cursor = AwtCursor.getDefaultCursor() },
                    onDrag = { change, dragAmount -> change.consume(); onDelta(dragAmount.x / density) },
                )
            }
            .pointerHoverIcon(PointerIcon(cursor)),
        contentAlignment = Alignment.Center,
    ) {
        Box(Modifier.width(4.dp).fillMaxHeight().background(if (hovered || dragging) tc.ac.copy(.5f) else tc.br))
    }
}

@Composable
fun VDivider(onDelta: (Float) -> Unit) {
    val tc = tc()
    val density = LocalDensity.current.density
    val cursor  = remember { AwtCursor.getPredefinedCursor(AwtCursor.S_RESIZE_CURSOR) }
    var hovered  by remember { mutableStateOf(false) }
    var dragging by remember { mutableStateOf(false) }
    Box(
        Modifier
            .height(10.dp).fillMaxWidth()
            .onPointerEvent(PointerEventType.Enter) { hovered = true }
            .onPointerEvent(PointerEventType.Exit)  { hovered = false }
            .pointerInput(density) {
                detectDragGestures(
                    onDragStart  = { dragging = true;  dragCursorOverride.value = cursor; activeWindow()?.cursor = cursor },
                    onDragEnd    = { dragging = false; dragCursorOverride.value = null;   activeWindow()?.cursor = AwtCursor.getDefaultCursor() },
                    onDragCancel = { dragging = false; dragCursorOverride.value = null;   activeWindow()?.cursor = AwtCursor.getDefaultCursor() },
                    onDrag = { change, dragAmount -> change.consume(); onDelta(dragAmount.y / density) },
                )
            }
            .pointerHoverIcon(PointerIcon(cursor)),
        contentAlignment = Alignment.Center,
    ) {
        Box(Modifier.fillMaxWidth().height(4.dp).background(if (hovered || dragging) tc.ac.copy(.5f) else tc.br))
    }
}

// ── Basic ────────────────────────────────────────────────────────────
@Composable
fun Divider() {
    val tc = tc()
    Box(Modifier.fillMaxWidth().height(1.dp).background(tc.br))
}

@Composable
fun SectionHeader(
    title: String,
    trailing: (@Composable RowScope.() -> Unit)? = null,
    expanded: Boolean? = null,
    onToggle: (() -> Unit)? = null,
) {
    val tc = tc()
    Row(
        Modifier.fillMaxWidth()
            .then(if (onToggle != null) Modifier.clickable(onClick = onToggle) else Modifier)
            .padding(horizontal = 12.dp, vertical = 7.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        AppText(title, color = tc.td, fontSize = 10.sp, fontFamily = UI, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
        trailing?.invoke(this)
        if (expanded != null) {
            Spacer(Modifier.width(6.dp))
            Box(
                Modifier.size(18.dp).background(tc.br.copy(.5f), CORNER_SM),
                contentAlignment = Alignment.Center,
            ) { AppText(if (expanded) "▾" else "▸", color = tc.ts, fontSize = 14.sp) }
        }
    }
}

@Composable
fun AppText(
    text: String,
    color: Color = LocalTheme.current.tx,
    fontSize: TextUnit = LocalFontBase.current.sp,
    fontFamily: FontFamily = FontFamily.Default,
    fontWeight: FontWeight = FontWeight.Normal,
    modifier: Modifier = Modifier,
    maxLines: Int = 1,
    overflow: TextOverflow = TextOverflow.Clip,
    onTextLayout: ((TextLayoutResult) -> Unit)? = null,
) {
    androidx.compose.material3.Text(
        text, color = color, fontSize = fontSize, fontFamily = fontFamily,
        fontWeight = fontWeight, modifier = modifier, maxLines = maxLines, overflow = overflow,
        onTextLayout = onTextLayout ?: {},
    )
}

@Composable
fun LevelBadge(level: LogLevel) {
    val color = level.defaultColor
    Box(
        Modifier.background(color.copy(.13f), CORNER_SM)
            .border(1.dp, color.copy(.27f), CORNER_SM)
            .padding(horizontal = 4.dp, vertical = 1.dp),
    ) { AppText(level.key.toString(), color = color, fontSize = 10.sp, fontFamily = MONO, fontWeight = FontWeight.SemiBold) }
}

@Composable
fun ColHeader(hasPidTid: Boolean = false) {
    val tc = tc()
    Row(
        Modifier.fillMaxWidth().background(tc.p2).border(BorderStroke(1.dp, tc.br))
            .padding(horizontal = 12.dp, vertical = 5.dp),
        horizontalArrangement = Arrangement.spacedBy(10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        AppText("TIMESTAMP", color = tc.td, fontSize = 9.sp, fontFamily = UI, fontWeight = FontWeight.SemiBold, modifier = Modifier.width(90.dp))
        if (hasPidTid) {
            AppText("PID", color = tc.td, fontSize = 9.sp, fontFamily = UI, fontWeight = FontWeight.SemiBold, modifier = Modifier.width(40.dp))
            AppText("TID", color = tc.td, fontSize = 9.sp, fontFamily = UI, fontWeight = FontWeight.SemiBold, modifier = Modifier.width(40.dp))
        }
        AppText("LVL", color = tc.td, fontSize = 9.sp, fontFamily = UI, fontWeight = FontWeight.SemiBold, modifier = Modifier.width(28.dp))
        AppText("TAG", color = tc.td, fontSize = 9.sp, fontFamily = UI, fontWeight = FontWeight.SemiBold, modifier = Modifier.width(100.dp))
        AppText("MESSAGE", color = tc.td, fontSize = 9.sp, fontFamily = UI, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
    }
}

@Composable
fun PillBtn(label: String, active: Boolean, onClick: () -> Unit) {
    val tc = tc()
    var hovered by remember { mutableStateOf(false) }
    Box(
        Modifier
            .border(1.dp, if (active) tc.ac else tc.br, CORNER_MD)
            .background(if (active) tc.ac.copy(.15f) else if (hovered) tc.hv else Color.Transparent, CORNER_MD)
            .clickable(onClick = onClick)
            .onPointerEvent(PointerEventType.Enter) { hovered = true }
            .onPointerEvent(PointerEventType.Exit) { hovered = false }
            .padding(horizontal = 7.dp, vertical = 2.dp),
    ) { AppText(label, color = if (active) tc.ac else tc.ts, fontSize = 10.sp) }
}

@Composable
fun ToolbarBtn(
    label: String,
    active: Boolean = false,
    enabled: Boolean = true,
    modifier: Modifier = Modifier,
    shape: RoundedCornerShape = CORNER_MD,
    contentPadding: PaddingValues = PaddingValues(horizontal = 10.dp, vertical = 5.dp),
    onClick: () -> Unit,
) {
    val tc = tc()
    var hovered by remember { mutableStateOf(false) }
    Box(
        modifier
            .border(1.dp, if (active && enabled) tc.ac else tc.br, shape)
            .background(if (active && enabled) tc.ac.copy(.2f) else if (hovered && enabled) tc.hv else Color.Transparent, shape)
            .clip(shape)
            .then(if (enabled) Modifier.clickable(onClick = onClick) else Modifier)
            .onPointerEvent(PointerEventType.Enter) { hovered = true }
            .onPointerEvent(PointerEventType.Exit) { hovered = false }
            .padding(contentPadding),
        contentAlignment = Alignment.Center,
    ) {
        AppText(
            label,
            color = if (!enabled) tc.td.copy(.5f) else if (active) tc.ac else tc.ts,
            fontSize = 12.sp,
            fontWeight = if (active && enabled) FontWeight.Medium else FontWeight.Normal,
        )
    }
}

@Composable
fun CloseButton(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val tc = tc()
    var hovered by remember { mutableStateOf(false) }
    Box(
        modifier
            .size(24.dp)
            .background(if (hovered) tc.hv else Color.Transparent, CORNER_MD)
            .clip(CORNER_MD)
            .clickable(onClick = onClick)
            .onPointerEvent(PointerEventType.Enter) { hovered = true }
            .onPointerEvent(PointerEventType.Exit) { hovered = false },
        contentAlignment = Alignment.Center,
    ) {
        AppText("×", color = tc.td, fontSize = 16.sp)
    }
}

@Composable
fun InlineField(
    value: String, onValue: (String) -> Unit,
    placeholder: String = "", modifier: Modifier = Modifier,
    fontSize: TextUnit = LocalFontBase.current.sp,
    onClear: (() -> Unit)? = null,
) {
    val tc = tc()
    BasicTextField(
        value = value, onValueChange = onValue,
        textStyle = TextStyle(color = tc.tx, fontSize = fontSize, fontFamily = FontFamily.Default),
        cursorBrush = SolidColor(tc.ac),
        modifier = modifier
            .background(tc.bg, CORNER_SM)
            .border(1.dp, tc.br, CORNER_SM)
            .padding(horizontal = 7.dp, vertical = 4.dp),
        decorationBox = { inner ->
            if (onClear != null) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(Modifier.weight(1f)) {
                        if (value.isEmpty()) AppText(placeholder, color = tc.td, fontSize = fontSize)
                        inner()
                    }
                    if (value.isNotEmpty()) {
                        AppText("×", color = tc.td, fontSize = 14.sp,
                            modifier = Modifier.clickable(onClick = onClear).padding(start = 4.dp))
                    }
                }
            } else {
                if (value.isEmpty()) AppText(placeholder, color = tc.td, fontSize = fontSize)
                inner()
            }
        },
    )
}

@Composable
fun CheckRow(
    checked: Boolean, onToggle: () -> Unit,
    accentColor: Color = LocalTheme.current.ac,
    content: @Composable RowScope.() -> Unit,
) {
    val tc = tc()
    Row(
        Modifier.fillMaxWidth().clickable(onClick = onToggle).padding(horizontal = 12.dp, vertical = 3.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Checkbox(checked = checked, onCheckedChange = { onToggle() },
            colors = CheckboxDefaults.colors(checkedColor = accentColor, uncheckedColor = tc.td, checkmarkColor = tc.bg),
            modifier = Modifier.size(16.dp))
        content()
    }
}

@Composable
fun ColorSwatch(color: Color, selected: Boolean, onClick: () -> Unit) {
    val tc = tc()
    Box(
        Modifier.size(14.dp)
            .background(color, CORNER_SM)
            .border(2.dp, if (selected) tc.tx else Color.Transparent, CORNER_SM)
            .clickable(onClick = onClick),
    )
}

// ── Segmented control ────────────────────────────────────────────────
@Composable
fun SegmentedControl(
    options: List<String>,
    selectedIndices: Set<Int>,
    onToggle: (Int) -> Unit,
    modifier: Modifier = Modifier,
    selectedColors: List<Color>? = null,
    fillWidth: Boolean = false,
) {
    val tc = tc()
    Row(
        modifier = modifier
            .border(0.5.dp, tc.br, RoundedCornerShape(6.dp))
            .clip(RoundedCornerShape(6.dp)),
    ) {
        options.forEachIndexed { index, label ->
            val selected = index in selectedIndices
            val selColor = selectedColors?.getOrNull(index) ?: tc.ac
            Box(
                contentAlignment = Alignment.Center,
                modifier = (if (fillWidth) Modifier.weight(1f) else Modifier.defaultMinSize(minWidth = 36.dp))
                    .height(28.dp)
                    .background(if (selected) selColor.copy(.2f) else Color.Transparent)
                    .clickable { onToggle(index) }
                    .padding(horizontal = 10.dp),
            ) {
                AppText(
                    text = label,
                    color = if (selected) selColor else tc.ts,
                    fontSize = 12.sp,
                    fontWeight = if (selected) FontWeight.Medium else FontWeight.Normal,
                )
            }
            if (index < options.lastIndex) {
                Box(Modifier.width(0.5.dp).height(28.dp).background(tc.br))
            }
        }
    }
}

// ── App button ────────────────────────────────────────────────────────
enum class ButtonVariant { Primary, Secondary, Ghost }

@Composable
fun AppButton(
    label: String,
    onClick: () -> Unit,
    variant: ButtonVariant = ButtonVariant.Secondary,
    isDanger: Boolean = false,
    enabled: Boolean = true,
    modifier: Modifier = Modifier,
) {
    val tc = tc()
    var hovered by remember { mutableStateOf(false) }
    val accentColor = if (isDanger) DANGER_RED else tc.ac
    val textColor = when {
        !enabled -> tc.td.copy(.5f)
        variant == ButtonVariant.Primary -> Color.White
        variant == ButtonVariant.Secondary && isDanger -> DANGER_RED
        variant == ButtonVariant.Ghost -> tc.td
        else -> tc.tx
    }
    Box(
        modifier = modifier
            .then(if (variant == ButtonVariant.Secondary)
                Modifier.border(0.5.dp, if (isDanger) DANGER_RED.copy(.5f) else tc.br, CORNER_MD)
            else Modifier)
            .background(
                when {
                    variant == ButtonVariant.Primary && enabled -> accentColor
                    hovered && enabled -> tc.hv
                    else -> Color.Transparent
                },
                CORNER_MD,
            )
            .clip(CORNER_MD)
            .then(if (enabled) Modifier.clickable(onClick = onClick) else Modifier)
            .onPointerEvent(PointerEventType.Enter) { hovered = true }
            .onPointerEvent(PointerEventType.Exit) { hovered = false }
            .padding(horizontal = 10.dp, vertical = 5.dp),
        contentAlignment = Alignment.Center,
    ) {
        AppText(
            label,
            color = textColor,
            fontSize = 12.sp,
            fontWeight = if (variant == ButtonVariant.Primary) FontWeight.Medium else FontWeight.Normal,
        )
    }
}
