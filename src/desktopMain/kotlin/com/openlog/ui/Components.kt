@file:OptIn(androidx.compose.ui.ExperimentalComposeUiApi::class)

package com.openlog.ui

import androidx.compose.foundation.*
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CheckboxDefaults
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.input.pointer.*
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.openlog.model.LogLevel

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
@Composable
fun HDivider(onDelta: (Float) -> Unit) {
    val tc = tc()
    var hovered by remember { mutableStateOf(false) }
    Box(
        Modifier
            .width(4.dp).fillMaxHeight()
            .background(if (hovered) tc.ac.copy(.5f) else tc.br)
            .onPointerEvent(PointerEventType.Enter) { hovered = true }
            .onPointerEvent(PointerEventType.Exit) { hovered = false }
            .pointerInput(Unit) {
                awaitEachGesture {
                    awaitFirstDown(requireUnconsumed = false).also { it.consume() }
                    var active = true
                    while (active) {
                        val event = awaitPointerEvent()
                        event.changes.forEach { ch ->
                            if (ch.pressed) onDelta(ch.positionChange().x)
                            ch.consume()
                        }
                        if (event.changes.none { it.pressed }) active = false
                    }
                }
            }
            .pointerHoverIcon(PointerIcon.Hand)
    )
}

@Composable
fun VDivider(onDelta: (Float) -> Unit) {
    val tc = tc()
    var hovered by remember { mutableStateOf(false) }
    Box(
        Modifier
            .height(4.dp).fillMaxWidth()
            .background(if (hovered) tc.ac.copy(.5f) else tc.br)
            .onPointerEvent(PointerEventType.Enter) { hovered = true }
            .onPointerEvent(PointerEventType.Exit) { hovered = false }
            .pointerInput(Unit) {
                awaitEachGesture {
                    awaitFirstDown(requireUnconsumed = false).also { it.consume() }
                    var active = true
                    while (active) {
                        val event = awaitPointerEvent()
                        event.changes.forEach { ch ->
                            if (ch.pressed) onDelta(ch.positionChange().y)
                            ch.consume()
                        }
                        if (event.changes.none { it.pressed }) active = false
                    }
                }
            }
            .pointerHoverIcon(PointerIcon.Hand)
    )
}

// ── Basic ────────────────────────────────────────────────────────────
@Composable
fun Divider() {
    val tc = tc()
    Box(Modifier.fillMaxWidth().height(1.dp).background(tc.br))
}

@Composable
fun SectionHeader(title: String, trailing: (@Composable RowScope.() -> Unit)? = null) {
    val tc = tc()
    Row(
        Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 5.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        AppText(title, color = tc.td, fontSize = 10.sp, fontFamily = UI, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
        trailing?.invoke(this)
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
) {
    androidx.compose.material3.Text(
        text, color = color, fontSize = fontSize, fontFamily = fontFamily,
        fontWeight = fontWeight, modifier = modifier, maxLines = maxLines, overflow = overflow,
    )
}

@Composable
fun LevelBadge(level: LogLevel) {
    val color = level.defaultColor
    Box(
        Modifier.background(color.copy(.13f), RoundedCornerShape(3.dp))
            .border(1.dp, color.copy(.27f), RoundedCornerShape(3.dp))
            .padding(horizontal = 4.dp, vertical = 1.dp),
    ) { AppText(level.key.toString(), color = color, fontSize = 10.sp, fontFamily = MONO, fontWeight = FontWeight.SemiBold) }
}

@Composable
fun ColHeader() {
    val tc = tc()
    Row(
        Modifier.fillMaxWidth().background(tc.p2).border(BorderStroke(1.dp, tc.br))
            .padding(horizontal = 12.dp, vertical = 5.dp),
        horizontalArrangement = Arrangement.spacedBy(10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        AppText("TIMESTAMP", color = tc.td, fontSize = 9.sp, fontFamily = UI, fontWeight = FontWeight.SemiBold, modifier = Modifier.width(90.dp))
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
            .border(1.dp, if (active) tc.ac else tc.br, RoundedCornerShape(4.dp))
            .background(if (active) tc.ac.copy(.15f) else if (hovered) tc.hv else Color.Transparent, RoundedCornerShape(4.dp))
            .clickable(onClick = onClick)
            .onPointerEvent(PointerEventType.Enter) { hovered = true }
            .onPointerEvent(PointerEventType.Exit) { hovered = false }
            .padding(horizontal = 7.dp, vertical = 2.dp),
    ) { AppText(label, color = if (active) tc.ac else tc.ts, fontSize = 10.sp) }
}

@Composable
fun ToolbarBtn(label: String, active: Boolean = false, onClick: () -> Unit) {
    val tc = tc()
    var hovered by remember { mutableStateOf(false) }
    Box(
        Modifier
            .border(1.dp, if (active) tc.ac else tc.br, RoundedCornerShape(4.dp))
            .background(if (active) tc.ac.copy(.15f) else if (hovered) tc.hv else Color.Transparent, RoundedCornerShape(4.dp))
            .clickable(onClick = onClick)
            .onPointerEvent(PointerEventType.Enter) { hovered = true }
            .onPointerEvent(PointerEventType.Exit) { hovered = false }
            .padding(horizontal = 10.dp, vertical = 5.dp),
    ) { AppText(label, color = if (active) tc.ac else tc.ts, fontSize = 12.sp) }
}

@Composable
fun InlineField(
    value: String, onValue: (String) -> Unit,
    placeholder: String = "", modifier: Modifier = Modifier,
    fontSize: TextUnit = LocalFontBase.current.sp,
) {
    val tc = tc()
    BasicTextField(
        value = value, onValueChange = onValue,
        textStyle = TextStyle(color = tc.tx, fontSize = fontSize, fontFamily = FontFamily.Default),
        cursorBrush = SolidColor(tc.ac),
        modifier = modifier
            .background(tc.bg, RoundedCornerShape(3.dp))
            .border(1.dp, tc.br, RoundedCornerShape(3.dp))
            .padding(horizontal = 7.dp, vertical = 4.dp),
        decorationBox = { inner ->
            if (value.isEmpty()) AppText(placeholder, color = tc.td, fontSize = fontSize)
            inner()
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
            .background(color, RoundedCornerShape(3.dp))
            .border(2.dp, if (selected) tc.tx else Color.Transparent, RoundedCornerShape(3.dp))
            .clickable(onClick = onClick),
    )
}
