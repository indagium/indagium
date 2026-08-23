@file:OptIn(androidx.compose.ui.ExperimentalComposeUiApi::class, androidx.compose.foundation.ExperimentalFoundationApi::class)

package com.indagium.ui

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.*
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.selection.DisableSelection
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.Label
import androidx.compose.material.icons.automirrored.outlined.LabelOff
import androidx.compose.material.icons.automirrored.outlined.OpenInNew
import androidx.compose.material.icons.outlined.AccountTree
import androidx.compose.material.icons.outlined.ArrowDownward
import androidx.compose.material.icons.outlined.ArrowUpward
import androidx.compose.material.icons.outlined.Badge
import androidx.compose.material.icons.outlined.ContentCopy
import androidx.compose.material.icons.outlined.FindInPage
import androidx.compose.material.icons.outlined.Flag
import androidx.compose.material.icons.outlined.Layers
import androidx.compose.material.icons.outlined.Link
import androidx.compose.material.icons.outlined.Movie
import androidx.compose.material.icons.outlined.PlayArrow
import androidx.compose.material.icons.outlined.VisibilityOff
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CheckboxDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.TriStateCheckbox
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.pointer.*
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.state.ToggleableState
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupProperties
import com.indagium.model.LogLevel
import kotlinx.coroutines.delay
import java.awt.KeyboardFocusManager
import kotlin.math.roundToInt
import java.awt.Cursor as AwtCursor

@Composable fun tc() = LocalTheme.current

@Composable fun monoFont() = if (LocalUseMono.current) FontFamily.Monospace else FontFamily.Default

@Composable fun baseSp() = LocalFontBase.current.sp

@Composable
fun IndeterminateLoadingLine(
    modifier: Modifier = Modifier,
    segmentWidth: Dp = 44.dp,
    durationMillis: Int = 900,
) {
    val tc = tc()
    val density = LocalDensity.current
    val transition = rememberInfiniteTransition(label = "loading-line")
    val progress by transition.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = durationMillis, easing = LinearEasing),
            repeatMode = RepeatMode.Reverse,
        ),
        label = "loading-line-progress",
    )
    BoxWithConstraints(
        modifier
            .height(3.dp)
            .clip(RoundedCornerShape(2.dp))
            .background(tc.br.copy(alpha = 0.35f)),
    ) {
        val travel = (maxWidth - segmentWidth).coerceAtLeast(0.dp)
        Box(
            Modifier
                .width(segmentWidth)
                .fillMaxHeight()
                .graphicsLayer {
                    translationX = with(density) { (travel * progress).toPx() }
                }
                .clip(RoundedCornerShape(2.dp))
                .background(tc.ac.copy(alpha = 0.42f)),
        )
    }
}

// ── Hover ────────────────────────────────────────────────────────────
@Composable
fun HoverBox(
    modifier: Modifier = Modifier,
    baseBg: Color = Color.Transparent,
    hoverBg: Color = LocalTheme.current.hv,
    forceHover: Boolean = false,
    hoverEnabled: Boolean = true,
    onClick: (() -> Unit)? = null,
    content: @Composable BoxScope.() -> Unit,
) {
    var hovered by remember { mutableStateOf(false) }
    Box(
        modifier = modifier
            .background(if ((hovered && hoverEnabled) || forceHover) hoverBg else baseBg)
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
    HoverBox(
        modifier = Modifier.fillMaxWidth().clip(CORNER_SM),
        onClick = onToggle,
    ) {
        DisableSelection {
            Row(
                // Keep every collapsible header on the same 32dp rhythm. In particular, a trailing
                // action must not make the Messages header taller than Inspector/Evidence headers.
                Modifier.fillMaxWidth().height(32.dp).padding(horizontal = 12.dp),
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
    // Appended last so every existing (near-exclusively named-argument) call site is unaffected;
    // null preserves the platform default (no decoration) exactly like before this param existed.
    textDecoration: TextDecoration? = null,
    onTextLayout: ((TextLayoutResult) -> Unit)? = null,
) {
    androidx.compose.material3.Text(
        text, color = color, fontSize = fontSize, fontFamily = fontFamily,
        fontWeight = fontWeight, modifier = modifier, maxLines = maxLines, overflow = overflow,
        textDecoration = textDecoration,
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
internal fun TagPill(
    tag: String, color: Color,
    // "×" for removable, or a count for a toggle pill
    trailing: String = "×",
    // false → tc.td.copy(.10f) fill, tc.br border, tc.ts text
    active: Boolean = true,
    // full dotted tag when `tag` is a shortened label
    tooltip: String = tag,
    onClick: () -> Unit,
) {
    val tc = tc()
    val fill = if (active) color.copy(.13f) else tc.td.copy(.10f)
    val border = if (active) color.copy(.27f) else tc.br
    val labelColor = if (active) color else tc.ts
    BoxWithConstraints {
        // Cap the text to the pill's actual available width (from the enclosing FlowRow), not a
        // guessed constant — the filter panel can be resized down to 140dp (FILTER_PANEL_MIN_WIDTH),
        // narrower than a fixed 260dp cap, which let the trailing × render past the panel's own
        // edge and get clipped instead of the text truncating to make room for it.
        val textCap = (maxWidth - 32.dp).coerceAtLeast(40.dp)
        Box(
            Modifier.background(fill, CORNER_SM)
                .border(1.dp, border, CORNER_SM)
                .clip(CORNER_SM)
                .clickable(onClick = onClick)
                .padding(start = 7.dp, end = 4.dp, top = 1.dp, bottom = 1.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(3.dp)) {
                // A label that differs from its tooltip has already been shortened
                // (displayTagForPrefix strips the package), so hovering must reveal the full
                // dotted tag even though the shortened form fits — hence alwaysHint, not
                // forceShow, which would instead pin the popup open with no pointer.
                FullTextHint(tooltip, alwaysHint = tooltip != tag) { onTextLayout ->
                    AppText(
                        tag, color = labelColor, fontSize = 11.sp, fontFamily = MONO,
                        maxLines = 1, overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.widthIn(max = textCap),
                        onTextLayout = onTextLayout,
                    )
                }
                AppText(
                    trailing,
                    color = if (trailing == "×") labelColor.copy(.7f) else labelColor,
                    fontSize = if (trailing == "×") 14.sp else 10.sp,
                )
            }
        }
    }
}

// Bounded scrollable list that works inside a verticalScroll parent.
// heightIn(max=X) breaks inside an unbounded parent; height(X) is reliable.
@Composable
internal fun ScrollableItems(
    itemCount: Int,
    rowDp: Int = 28,
    maxDp: Int = 150,
    scrollToIndex: Int = -1,
    modifier: Modifier = Modifier,
    content: @Composable ColumnScope.() -> Unit,
) {
    if (itemCount == 0) return
    val h = (itemCount * rowDp).coerceAtMost(maxDp).dp
    val scrollState = rememberScrollState()
    val density = LocalDensity.current.density
    LaunchedEffect(scrollToIndex) {
        if (scrollToIndex >= 0) {
            val rowPx = (rowDp * density).roundToInt()
            val itemTop = scrollToIndex * rowPx
            val itemBot = itemTop + rowPx
            val viewTop = scrollState.value
            val viewBot = viewTop + (maxDp * density).roundToInt()
            when {
                itemTop < viewTop -> scrollState.animateScrollTo(itemTop)
                itemBot > viewBot -> scrollState.animateScrollTo(itemBot - (maxDp * density).roundToInt())
            }
        }
    }
    Box(modifier.fillMaxWidth().height(h)) {
        Column(Modifier.fillMaxSize().verticalScroll(scrollState), content = content)
        VerticalScrollbar(
            adapter = rememberScrollbarAdapter(scrollState),
            modifier = Modifier.align(Alignment.CenterEnd).fillMaxHeight().width(6.dp),
            style = appScrollbarStyle(tc()),
        )
    }
}

@Composable
internal fun BoundedScrollBox(
    rowLimit: Int,
    rowDp: Int = 28,
    modifier: Modifier = Modifier,
    content: @Composable ColumnScope.() -> Unit,
) = BoundedScrollBoxDp(maxHeightDp = rowLimit * rowDp, modifier = modifier, content = content)

// Same box as BoundedScrollBox, parameterized directly by the cap in dp rather than a row
// count/row-height pair — for sections like Issues where rows aren't uniform height (a collapsed
// group vs. an expanded one) and the caller has already reduced that down to a single dp figure
// (see issuesBoxHeightDp).
@Composable
internal fun BoundedScrollBoxDp(
    maxHeightDp: Int,
    modifier: Modifier = Modifier,
    // Stage 5 task 4: a caller that needs to programmatically scroll this box (e.g. scrolling a
    // manual-message row into view on canvas click) can hoist and pass its own ScrollState;
    // every other call site keeps getting a private one via this default, exactly as before.
    scrollState: ScrollState = rememberScrollState(),
    content: @Composable ColumnScope.() -> Unit,
) {
    // heightIn(max) rather than height(): maxHeightDp is only ever a guessed/derived content
    // height, and a fixed height clips real content that's taller than the guess (e.g. a wrapping
    // message line). heightIn(max) makes it a cap for many rows while letting fewer/shorter rows
    // size to their own content instead of being stretched-then-clipped to it.
    val h = maxHeightDp.dp
    Box(modifier.fillMaxWidth().heightIn(max = h)) {
        // fillMaxWidth, not fillMaxSize: fillMaxSize would claim the full `h` cap regardless of
        // actual content height, forcing the Box back to a fixed size and defeating heightIn above.
        Column(Modifier.fillMaxWidth().verticalScroll(scrollState), content = content)
        // The scrollbar sits inside a matchParentSize() Box (same pattern as UpdateDialog) so its
        // fillMaxHeight() doesn't participate in sizing the outer Box — a plain fillMaxHeight child
        // measures at the full `h` cap and would pin the Box to it, defeating heightIn above.
        Box(Modifier.matchParentSize()) {
            VerticalScrollbar(
                adapter = rememberScrollbarAdapter(scrollState),
                modifier = Modifier.align(Alignment.CenterEnd).fillMaxHeight().width(6.dp),
                style = appScrollbarStyle(tc()),
            )
        }
    }
}

@Composable
@OptIn(androidx.compose.ui.ExperimentalComposeUiApi::class)
internal fun FullTextHint(
    text: String,
    modifier: Modifier = Modifier,
    forceShow: Boolean = false,
    // Two independent axes, easy to conflate: forceShow drops the *hover* requirement (a
    // keyboard-selected row reveals its own truncated text without the pointer), while
    // alwaysHint drops the *overflow* requirement — for a label that was deliberately
    // abbreviated before it got here (displayTagForPrefix's package-stripped tag), where the
    // rendered text fits perfectly and still isn't what the user needs to read.
    alwaysHint: Boolean = false,
    content: @Composable BoxScope.((TextLayoutResult) -> Unit) -> Unit,
) {
    val tc = tc()
    val density = LocalDensity.current
    var hovered by remember { mutableStateOf(false) }
    var isOverflowing by remember(text) { mutableStateOf(false) }
    var anchorHeightPx by remember { mutableStateOf(0) }
    Box(
        modifier
            .onSizeChanged { anchorHeightPx = it.height }
            .onPointerEvent(PointerEventType.Enter) { hovered = true }
            .onPointerEvent(PointerEventType.Exit) { hovered = false },
    ) {
        content { result -> isOverflowing = result.hasVisualOverflow }
        if ((hovered || forceShow) && (isOverflowing || alwaysHint)) {
            // Popup(alignment, offset) aligns matching corners of anchor and popup — TopStart
            // means "popup's top-left = anchor's top-left", NOT "popup below anchor". Placing it
            // below requires shifting by the anchor's own *measured* height (device px, matching
            // offset's unit) plus a gap; a guessed constant (the previous approach, and an even
            // more wrong alignment=BottomStart before that) either overlaps the anchor on some
            // densities/text sizes or — with BottomStart — aligns the popup's own bottom-left to
            // the anchor's bottom-left, making the popup extend upward and fully cover the anchor.
            // Either overlap makes the popup the topmost hit-test target at the cursor, which
            // fires Exit on the anchor, hides the popup, then Enter fires again — rapid flicker.
            val gapPx = with(density) { 4.dp.roundToPx() }
            Popup(
                alignment = Alignment.TopStart,
                offset = IntOffset(0, anchorHeightPx + gapPx),
                properties = PopupProperties(focusable = false),
            ) {
                Box(
                    Modifier.widthIn(max = 520.dp)
                        .background(tc.p, RoundedCornerShape(5.dp))
                        .border(1.dp, tc.br, RoundedCornerShape(5.dp))
                        .padding(horizontal = 8.dp, vertical = 5.dp),
                ) {
                    AppText(text, color = tc.tx, fontSize = 11.sp, fontFamily = MONO, maxLines = 3, overflow = TextOverflow.Clip)
                }
            }
        }
    }
}

// Header font size for ColHeader's column labels — kept in sync with the "#" cell's own width
// formula (rowNumberColumnWidth) so it lines up reasonably with the row gutter below it.
private const val COL_HEADER_FONT_SP = 9f

@Composable
fun ColHeader(
    hasPidTid: Boolean = false,
    showRowNumbers: Boolean = false,
    rowNumDigits: Int = 1,
    showTimeDelta: Boolean = false,
    // Fixed to the same Δt character budget as the row gutter below. This keeps the header and
    // rows aligned while selecting an anchor.
    timeDeltaChars: Int = 1,
    // Mirrors LogRow's own leading hasTidMap spacer (ui/LogViewer.kt) — same TID_MAP_HIT_WIDTH,
    // same leading position, so the header's row-number/Δt column labels below line up with the
    // body rows' actual gutters regardless of whether a tid map is active.
    hasTidMap: Boolean = false,
    // Change 3 (process-names rework): the uniform pid-FIELD character width every row's pid cell
    // pads to (LogViewer's pidFieldCharWidth) — 5 (the original width) whenever the feature is off
    // or this tab has no name over 5 chars. Only widens the "PID" box below the pre-feature fixed
    // 40.dp when this is actually > 5, so the OFF case's Compose tree is untouched.
    pidFieldChars: Int = 5,
    // The row's own content font size (AppSettings.fontSize) — the pid field renders inline in the
    // row's single BasicTextField at THIS font size, not at COL_HEADER_FONT_SP (see
    // Theme.kt's pidFieldColumnWidth doc), so the header box below must be sized from it too or it
    // drifts out of alignment with the row (and every header after it shifts left) whenever the
    // field is actually widened. Defaults to AppSettings' own default (12) purely so existing call
    // sites/tests that don't pass it keep compiling; every real call site threads settings.fontSize.
    contentFontSizeSp: Int = 12,
) {
    val tc = tc()
    Row(
        Modifier.fillMaxWidth().background(tc.p2).border(BorderStroke(1.dp, tc.br))
            .padding(horizontal = 12.dp, vertical = 5.dp),
        horizontalArrangement = Arrangement.spacedBy(10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (hasTidMap) {
            Spacer(Modifier.width(TID_MAP_HIT_WIDTH))
        }
        if (showRowNumbers) {
            val numColWidth = rowNumberColumnWidth(COL_HEADER_FONT_SP, rowNumDigits)
            Box(Modifier.width(numColWidth)) {
                AppText(
                    "#", color = tc.td, fontSize = COL_HEADER_FONT_SP.sp, fontFamily = UI, fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.align(Alignment.CenterStart),
                )
            }
        }
        if (showTimeDelta) {
            val deltaColWidth = timeDeltaColumnWidth(COL_HEADER_FONT_SP, timeDeltaChars)
            Box(Modifier.width(deltaColWidth)) {
                // Left-aligned (CenterStart), matching the row's own Δt value below it (LogRow) —
                // that cell is left-aligned too, so its left edge lands exactly where row content
                // starts when the column is hidden. The "#" cell above stays right-aligned since
                // row numbers themselves stay right-aligned.
                AppText(
                    "Δt", color = tc.td, fontSize = COL_HEADER_FONT_SP.sp, fontFamily = UI, fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.align(Alignment.CenterStart),
                )
            }
        }
        AppText("TIMESTAMP", color = tc.td, fontSize = 9.sp, fontFamily = UI, fontWeight = FontWeight.SemiBold, modifier = Modifier.width(90.dp))
        if (hasPidTid) {
            // pidFieldChars > 5 only when the process-name feature is on AND this tab has a known
            // name wider than 5 chars (pidFieldCharWidth) — every other case keeps the exact
            // pre-feature 40.dp box, which is what makes mode OFF byte-identical to before. Sized
            // from contentFontSizeSp (the row's own font), not COL_HEADER_FONT_SP — see
            // headerPidColumnWidth/pidFieldColumnWidth's own docs for why.
            val pidColWidth = headerPidColumnWidth(pidFieldChars, contentFontSizeSp.toFloat())
            AppText("PID", color = tc.td, fontSize = 9.sp, fontFamily = UI, fontWeight = FontWeight.SemiBold, modifier = Modifier.width(pidColWidth))
            // TID is the same class of drift as PID above: entry.tid.toString().padStart(5) is a
            // genuinely fixed 5-char field in the row (unlike TIMESTAMP/TAG below, which are
            // unpadded free text with no fixed width to derive from), so it's sized the same way —
            // safe across the whole font-size range (Settings caps it at 10..24sp; even at the
            // floor, 5 content-font chars comfortably fit the 3-char "TID" label).
            val tidColWidth = pidFieldColumnWidth(contentFontSizeSp.toFloat(), 5)
            AppText("TID", color = tc.td, fontSize = 9.sp, fontFamily = UI, fontWeight = FontWeight.SemiBold, modifier = Modifier.width(tidColWidth))
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
            .clip(CORNER_MD)
            .clickable(onClick = onClick)
            .onPointerEvent(PointerEventType.Enter) { hovered = true }
            .onPointerEvent(PointerEventType.Exit) { hovered = false },
    ) {
        DisableSelection {
            AppText(label, color = if (active) tc.ac else tc.ts, fontSize = 10.sp, modifier = Modifier.padding(horizontal = 7.dp, vertical = 2.dp))
        }
    }
}

@Composable
fun ToolbarBtn(
    label: String,
    icon: ImageVector? = null,
    showLabel: Boolean = true,
    tooltip: String? = null,
    active: Boolean = false,
    enabled: Boolean = true,
    modifier: Modifier = Modifier,
    shape: RoundedCornerShape = CORNER_MD,
    contentPadding: PaddingValues = PaddingValues(horizontal = 10.dp, vertical = 5.dp),
    baseBg: Color = Color.Transparent,
    onClick: () -> Unit,
) {
    val tc = tc()
    var hovered by remember { mutableStateOf(false) }
    val contentColor = if (!enabled) tc.td.copy(.5f) else if (active) Color.White else tc.ts
    // Solid fill (matching AppButton's Primary variant) rather than a translucent accent tint, so
    // an active toggle in this row reads the same way the AI/Notes panel toggle does.
    val button: @Composable () -> Unit = {
        Box(
            modifier
                .border(1.dp, if (active && enabled) tc.ac else tc.br, shape)
                .background(
                    when {
                        active && enabled -> tc.ac
                        hovered && enabled -> tc.hv
                        else -> baseBg
                    },
                    shape,
                )
                .clip(shape)
                // Put the gesture before content padding so the complete visual button surface,
                // including its breathing room, is interactive.
                .then(if (enabled) Modifier.clickable(onClick = onClick) else Modifier)
                .onPointerEvent(PointerEventType.Enter) { hovered = true }
                .onPointerEvent(PointerEventType.Exit) { hovered = false }
                .padding(contentPadding),
            contentAlignment = Alignment.Center,
        ) {
            DisableSelection {
                Row(
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    if (icon != null) {
                        Icon(
                            imageVector = icon,
                            contentDescription = if (showLabel) null else tooltip ?: label,
                            tint = contentColor,
                            modifier = Modifier.size(15.dp),
                        )
                    }
                    if (showLabel || icon == null) {
                        AppText(
                            label,
                            color = contentColor,
                            fontSize = 12.sp,
                            fontWeight = if (active && enabled) FontWeight.Medium else FontWeight.Normal,
                        )
                    }
                }
            }
        }
    }
    if (tooltip != null) {
        TooltipArea(tooltip = { ToolbarTooltip(tooltip) }) { button() }
    } else {
        button()
    }
}

@Composable
internal fun ToolbarTooltip(text: String) {
    val tc = tc()
    Box(
        Modifier.background(tc.p2, RoundedCornerShape(4.dp))
            .border(0.5.dp, tc.br, RoundedCornerShape(4.dp))
            .padding(horizontal = 8.dp, vertical = 4.dp),
    ) {
        AppText(text, color = tc.tx, fontSize = 11.sp, maxLines = 2)
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
    onSubmit: (() -> Unit)? = null,
    // WP7 item 6 (round-2 corrections plan): the one Esc hook every inline editor in the workspace
    // needed and didn't have — previously each of the four canvas editors (and the panel's own
    // rename rows) would have needed its own Modifier.onPreviewKeyEvent at the call site to cancel
    // on Escape; centralizing it here removes that repeated gap instead of patching one call site.
    // Null (the default) keeps every other InlineField call site — the vast majority, which have no
    // "cancel" concept at all — completely unaffected: the key handler below only ever consumes
    // Escape when a caller actually passed a cancel action, i.e. only while a cancelable editor is
    // genuinely open.
    onCancel: (() -> Unit)? = null,
    singleLine: Boolean = true,
    centerTextVertically: Boolean = false,
    visualTransformation: VisualTransformation = VisualTransformation.None,
) {
    val tc = tc()
    BasicTextField(
        value = value, onValueChange = onValue,
        visualTransformation = visualTransformation,
        textStyle = TextStyle(color = tc.tx, fontSize = fontSize, fontFamily = FontFamily.Default),
        cursorBrush = SolidColor(tc.ac),
        singleLine = singleLine,
        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
        keyboardActions = KeyboardActions(onDone = { onSubmit?.invoke() }),
        modifier = modifier
            .onPreviewKeyEvent { event ->
                if (onCancel != null && event.type == KeyEventType.KeyDown && event.key == Key.Escape) {
                    onCancel()
                    true
                } else {
                    false
                }
            }
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
                        SquareIconButton(
                            "×", fontSize = 12.sp, onClick = onClear,
                            modifier = Modifier.padding(start = 4.dp), size = 16.dp,
                        )
                    }
                }
            } else {
                if (centerTextVertically) {
                    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.CenterStart) {
                        if (value.isEmpty()) AppText(placeholder, color = tc.td, fontSize = fontSize)
                        inner()
                    }
                } else {
                    if (value.isEmpty()) AppText(placeholder, color = tc.td, fontSize = fontSize)
                    inner()
                }
            }
        },
    )
}

// Grows with content up to `maxHeight`, then scrolls internally — shared by the Notes panel's
// From/Next-steps fields and the Project-info Description field, all of which used to either grow
// without limit or clip silently past a fixed height with no way to see the rest. Lifted from
// `AiPromptComposer` (AiSidebar.kt), which solves the same viewport/caret-follow/clear-button
// problem for the AI prompt box.
//
// Callers tracking focus through `modifier` must read `hasFocus`, not `isFocused`: the
// verticalScroll below groups focus, so the caller's onFocusChanged no longer sits directly above
// the text field and `isFocused` stays false the whole time it is being typed into. A panel that
// gates its own Enter/arrow shortcuts on that flag would otherwise eat the field's keystrokes.
@Composable
fun ScrollableTextArea(
    value: String,
    onValue: (String) -> Unit,
    placeholder: String = "",
    // applied to the BasicTextField itself
    modifier: Modifier = Modifier,
    fontSize: TextUnit = 12.sp,
    lineHeight: TextUnit = TextUnit.Unspecified,
    minHeight: Dp = 0.dp,
    maxHeight: Dp,
    // re-seeds the caret when identity changes (tab id / folder path)
    resetKey: Any? = null,
    shape: Shape = CORNER_SM,
    // null → tc.br
    borderColor: Color? = null,
    contentPadding: PaddingValues = PaddingValues(horizontal = 7.dp, vertical = 4.dp),
    onClear: (() -> Unit)? = null,
) {
    val tc = tc()
    val density = LocalDensity.current
    val scroll = rememberScrollState()
    var fieldHeightPx by remember { mutableStateOf(0) }
    var viewportHeightPx by remember { mutableStateOf(0) }
    var layout by remember { mutableStateOf<TextLayoutResult?>(null) }
    var fieldValue by remember(resetKey) { mutableStateOf(TextFieldValue(value, TextRange(value.length))) }

    // The caller's String is the source of truth (MCP tool calls, loading a different tab/folder
    // all write it directly); TextFieldValue only adds the caret position needed to reveal the
    // line being edited. Reseat it whenever an external write changes the text under us.
    LaunchedEffect(value) {
        if (value != fieldValue.text) fieldValue = TextFieldValue(value, TextRange(value.length))
    }
    LaunchedEffect(fieldValue, layout, viewportHeightPx) {
        val l = layout ?: return@LaunchedEffect
        if (viewportHeightPx == 0) return@LaunchedEffect
        val caretBottom = l.getCursorRect(fieldValue.selection.end).bottom
        val target = (caretBottom - viewportHeightPx + with(density) { 12.dp.toPx() })
            .roundToInt()
            .coerceIn(0, scroll.maxValue)
        scroll.animateScrollTo(target)
    }

    Box(Modifier.fillMaxWidth()) {
        BasicTextField(
            value = fieldValue,
            onValueChange = {
                fieldValue = it
                onValue(it.text)
            },
            textStyle = TextStyle(color = tc.tx, fontSize = fontSize, lineHeight = lineHeight, fontFamily = FontFamily.Default),
            cursorBrush = SolidColor(tc.ac),
            onTextLayout = { layout = it },
            // heightIn must come before verticalScroll: verticalScroll measures its child unbounded
            // in the scroll axis, so the outer heightIn is what actually clips the viewport, and
            // short text still lands at its own natural height. A fillMaxSize here (as
            // AiPromptComposer uses inside its fixed-height editor) would pin it at maxHeight.
            modifier = modifier
                .heightIn(min = minHeight, max = maxHeight)
                .onSizeChanged { fieldHeightPx = it.height }
                .background(tc.bg, shape)
                .border(1.dp, borderColor ?: tc.br, shape)
                .padding(contentPadding)
                .padding(end = 21.dp)
                // Measured *inside* the padding: caret positions come from the text layout, which
                // shares that inner coordinate space. Measuring the padded outer height instead
                // leaves the caret short of the bottom edge by the padding on every reveal.
                .onSizeChanged { viewportHeightPx = it.height }
                .verticalScroll(scroll),
            decorationBox = { inner ->
                if (value.isEmpty()) AppText(placeholder, color = tc.td, fontSize = fontSize)
                inner()
            },
        )
        val clearAction = onClear?.takeIf { value.isNotBlank() }
        if (fieldHeightPx > 0) {
            // Can't fillMaxHeight() here: in AnnotationPanel this component sits inside the panel's
            // own verticalScroll Column — an infinite max-height constraint, where fillMaxHeight
            // misbehaves. The field's own measured (already-capped) height stands in for it.
            // The top inset yields the corner to the × when it is showing: both sit at the trailing
            // edge, so without it the thumb draws under the glyph and the button swallows clicks
            // aimed at the top of the track (same reason AiPromptComposer insets its own scrollbar).
            VerticalScrollbar(
                adapter = rememberScrollbarAdapter(scroll),
                modifier = Modifier.align(Alignment.CenterEnd)
                    .height(with(density) { fieldHeightPx.toDp() })
                    .padding(top = if (clearAction != null) 24.dp else 2.dp, bottom = 2.dp),
                style = appScrollbarStyle(tc),
            )
        }
        if (clearAction != null) {
            SquareIconButton(
                "×", fontSize = 12.sp, onClick = clearAction,
                modifier = Modifier.align(Alignment.TopEnd).padding(4.dp), size = 16.dp,
            )
        }
    }
}

@Composable
fun CheckRow(
    checked: Boolean, onToggle: () -> Unit,
    accentColor: Color = LocalTheme.current.ac,
    modifier: Modifier = Modifier,
    content: @Composable RowScope.() -> Unit,
) {
    val tc = tc()
    // This is one control: keep its label out of any ambient text selection and put the click
    // handler after padding so the complete visual row toggles, not only the checkbox/text bounds.
    DisableSelection {
        Row(
            modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 3.dp).clickable(onClick = onToggle),
            verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Checkbox(checked = checked, onCheckedChange = { onToggle() },
                colors = CheckboxDefaults.colors(checkedColor = accentColor, uncheckedColor = tc.td, checkmarkColor = tc.bg),
                modifier = Modifier.size(16.dp))
            content()
        }
    }
}

/** A checkbox for dense inspector rows. Unlike [CheckRow], this control owns only its compact
 * footprint, so it can safely sit beside labels and row actions. [indeterminate] is used for
 * grouped manual interactions whose occurrences are only partially enabled. */
@Composable
fun CompactCheckBox(
    checked: Boolean,
    onToggle: () -> Unit,
    modifier: Modifier = Modifier,
    indeterminate: Boolean = false,
    accentColor: Color = LocalTheme.current.ac,
) {
    val tc = tc()
    val state = when {
        indeterminate -> ToggleableState.Indeterminate
        checked -> ToggleableState.On
        else -> ToggleableState.Off
    }
    TriStateCheckbox(
        state = state,
        onClick = onToggle,
        colors = CheckboxDefaults.colors(
            checkedColor = accentColor,
            uncheckedColor = tc.td,
            checkmarkColor = tc.bg,
        ),
        modifier = modifier.size(20.dp),
    )
}

@Composable
fun ColorSwatch(color: Color, selected: Boolean, onClick: () -> Unit) {
    val tc = tc()
    Box(
        Modifier.size(14.dp)
            .background(color, CORNER_SM)
            .border(2.dp, if (selected) tc.tx else Color.Transparent, CORNER_SM)
            .clip(CORNER_SM)
            .clickable(onClick = onClick),
    )
}

// Shared small square icon-button — single-glyph edit/remove/reorder buttons (✎, ×, ↑, ↓) across
// Notes/Highlighters/Sequences/Saved-filters used to be bare AppText + .clickable() with no
// shape, size, or hover highlight, each drifting independently. This gives them one consistent
// footprint, following CloseButton's own hover-highlight convention (tc.hv on pointer-enter).
// 18dp matches the height of the adjacent type badge (BlockControls' Note/LogRef pill) they sit
// next to in the same row.
@Composable
fun SquareIconButton(text: String, fontSize: TextUnit, onClick: () -> Unit, modifier: Modifier = Modifier, size: Dp = 18.dp) {
    val tc = tc()
    var hovered by remember { mutableStateOf(false) }
    Box(
        modifier
            .size(size)
            .background(if (hovered) tc.hv else Color.Transparent, CORNER_MD)
            .clip(CORNER_MD)
            .clickable(onClick = onClick)
            .onPointerEvent(PointerEventType.Enter) { hovered = true }
            .onPointerEvent(PointerEventType.Exit) { hovered = false },
        contentAlignment = Alignment.Center,
    ) {
        DisableSelection { AppText(text, color = tc.td, fontSize = fontSize) }
    }
}

// Same height/shape/hover convention as SquareIconButton, for multi-character labels (e.g.
// "+ note") that can't fit a fixed square — auto-width via horizontal padding instead.
@Composable
fun LabelIconButton(text: String, fontSize: TextUnit, onClick: () -> Unit, modifier: Modifier = Modifier) {
    val tc = tc()
    var hovered by remember { mutableStateOf(false) }
    Box(
        modifier
            .height(18.dp)
            .background(if (hovered) tc.hv else Color.Transparent, CORNER_MD)
            .clip(CORNER_MD)
            .clickable(onClick = onClick)
            .onPointerEvent(PointerEventType.Enter) { hovered = true }
            .onPointerEvent(PointerEventType.Exit) { hovered = false },
        contentAlignment = Alignment.Center,
    ) {
        DisableSelection {
            AppText(text, color = tc.td, fontSize = fontSize, modifier = Modifier.padding(horizontal = 6.dp))
        }
    }
}

// Shared round enabled/active indicator — replaces the bare "●"/"○" glyph trick (highlighters,
// sequences, saved filters) with a real CircleShape so its hover highlight is a round halo behind
// the dot, not a square highlight box behind a round glyph.
@Composable
fun RoundIndicator(
    active: Boolean,
    color: Color,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    size: Dp = 10.dp,
    indeterminate: Boolean = false,
) {
    val tc = tc()
    var hovered by remember { mutableStateOf(false) }
    Box(
        modifier
            .size(size + 8.dp)
            .background(if (hovered) tc.hv else Color.Transparent, CircleShape)
            .clip(CircleShape)
            .clickable(onClick = onClick)
            .onPointerEvent(PointerEventType.Enter) { hovered = true }
            .onPointerEvent(PointerEventType.Exit) { hovered = false },
        contentAlignment = Alignment.Center,
    ) {
        Box(
            Modifier.size(size)
                .background(if (active) color else Color.Transparent, CircleShape)
                .border(1.dp, color, CircleShape),
        ) {
            if (indeterminate && !active) {
                Box(Modifier.size(size / 2).background(color, CircleShape).align(Alignment.Center))
            }
        }
    }
}

// Rounded-pill hover highlight for compact clickable summary rows (e.g. the "N active ▾" /
// "N excluded ▾" toggles in Tags/Message rules/Highlighters section headers) — these used to be
// a bare Row + .clickable() with no visual cue that they're clickable at all.
@Composable
fun Modifier.hoverPill(): Modifier {
    val tc = tc()
    var hovered by remember { mutableStateOf(false) }
    return this
        .background(if (hovered) tc.hv else Color.Transparent, RoundedCornerShape(percent = 50))
        .clip(RoundedCornerShape(percent = 50))
        .onPointerEvent(PointerEventType.Enter) { hovered = true }
        .onPointerEvent(PointerEventType.Exit) { hovered = false }
}

// Shared square color swatch that opens a color picker (highlighters/sequences). A plain colored
// square gave no visual cue that it's clickable — especially when its own fill color happens to
// blend with the hover tint — so the affordance lives OUTSIDE the color itself: a hover highlight
// and, when the picker is open, a colored ring, both drawn in the surrounding box.
@Composable
fun ColorPickerSwatch(color: Color, pickerOpen: Boolean, onClick: () -> Unit, size: Dp = 12.dp) {
    val tc = tc()
    var hovered by remember { mutableStateOf(false) }
    Box(
        Modifier
            .size(size + 8.dp)
            .background(if (hovered || pickerOpen) tc.hv else Color.Transparent, CORNER_SM)
            .border(1.dp, if (pickerOpen) tc.ac else Color.Transparent, CORNER_SM)
            .clip(CORNER_SM)
            .clickable(onClick = onClick)
            .onPointerEvent(PointerEventType.Enter) { hovered = true }
            .onPointerEvent(PointerEventType.Exit) { hovered = false },
        contentAlignment = Alignment.Center,
    ) {
        Box(Modifier.size(size).background(color, CORNER_SM))
    }
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
    enabled: Boolean = true,
    segmentHeight: Dp = 28.dp,
    segmentFontSize: TextUnit = 12.sp,
    segmentHorizontalPadding: Dp = 10.dp,
) {
    val tc = tc()
    val controlShape = RoundedCornerShape(6.dp)
    Row(
        modifier = modifier
            .border(0.5.dp, tc.br, controlShape)
            .clip(controlShape),
    ) {
        options.forEachIndexed { index, label ->
            val selected = index in selectedIndices
            val selColor = selectedColors?.getOrNull(index) ?: tc.ac
            // The selected fill is part of the segment, not an unshaped overlay. Keeping the
            // outer segment corners rounded makes the highlight follow the control's silhouette;
            // middle segments remain square where they meet their neighbors.
            val segmentShape = when {
                options.size == 1 -> controlShape
                index == 0 -> RoundedCornerShape(topStart = 6.dp, bottomStart = 6.dp)
                index == options.lastIndex -> RoundedCornerShape(topEnd = 6.dp, bottomEnd = 6.dp)
                else -> RoundedCornerShape(0.dp)
            }
            Box(
                contentAlignment = Alignment.Center,
                modifier = (if (fillWidth) Modifier.weight(1f) else Modifier.defaultMinSize(minWidth = 36.dp))
                    .height(segmentHeight)
                    .clip(segmentShape)
                    .background(if (selected && enabled) selColor.copy(.2f) else Color.Transparent, segmentShape)
                    .clickable(enabled = enabled) { onToggle(index) },
            ) {
                DisableSelection {
                    AppText(
                        text = label,
                        color = if (!enabled) tc.td.copy(.5f) else if (selected) selColor else tc.ts,
                        fontSize = segmentFontSize,
                        fontWeight = if (selected) FontWeight.Medium else FontWeight.Normal,
                        modifier = Modifier.padding(horizontal = segmentHorizontalPadding),
                    )
                }
            }
            if (index < options.lastIndex) {
                Box(Modifier.width(0.5.dp).height(segmentHeight).background(tc.br))
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
    leadingIcon: ImageVector? = null,
    horizontalPadding: Dp = 10.dp,
    // Lets a caller join two AppButtons into one segmented control (see the Notes header's
    // Open+▾ split button) the same way ToolbarBtn does — CORNER_MD default means every
    // pre-existing call site (none of which passes this) renders identically to before.
    shape: Shape = CORNER_MD,
    textColor: Color? = null,
) {
    val tc = tc()
    var hovered by remember { mutableStateOf(false) }
    val accentColor = if (isDanger) DANGER_RED else tc.ac
    val resolvedTextColor = textColor ?: when {
        !enabled -> tc.td.copy(.5f)
        variant == ButtonVariant.Primary -> Color.White
        variant == ButtonVariant.Secondary && isDanger -> DANGER_RED
        variant == ButtonVariant.Ghost -> tc.td
        else -> tc.tx
    }
    Box(
        modifier = modifier
            .then(if (variant == ButtonVariant.Secondary)
                Modifier.border(0.5.dp, if (isDanger) DANGER_RED.copy(.5f) else tc.br, shape)
            else Modifier)
            .background(
                when {
                    variant == ButtonVariant.Primary && enabled -> accentColor
                    hovered && enabled -> tc.hv
                    else -> Color.Transparent
                },
                shape,
            )
            .clip(shape)
            // Keep the entire padded visual surface clickable; placing this after padding would
            // make only the label hit-testable and lets parent row handlers win in the padding.
            .then(if (enabled) Modifier.clickable(onClick = onClick) else Modifier)
            .onPointerEvent(PointerEventType.Enter) { hovered = true }
            .onPointerEvent(PointerEventType.Exit) { hovered = false }
            .padding(horizontal = horizontalPadding, vertical = 5.dp),
        contentAlignment = Alignment.Center,
    ) {
        // A button label must never behave like selectable text - without this, a button placed
        // inside an ambient SelectionContainer (e.g. the AI sidebar's response area) shows an
        // I-beam cursor on hover instead of looking clickable.
        DisableSelection {
            Row(
                horizontalArrangement = if (leadingIcon != null && label.isEmpty()) {
                    Arrangement.Center
                } else {
                    Arrangement.spacedBy(4.dp)
                },
                verticalAlignment = Alignment.CenterVertically,
            ) {
                leadingIcon?.let {
                    Icon(it, contentDescription = null, modifier = Modifier.size(14.dp), tint = resolvedTextColor)
                }
                if (label.isNotEmpty()) {
                    AppText(
                        label,
                        color = resolvedTextColor,
                        fontSize = 12.sp,
                        fontWeight = if (variant == ButtonVariant.Primary) FontWeight.Medium else FontWeight.Normal,
                    )
                }
            }
        }
    }
}

internal fun truncatePathForDisplay(path: String, maxChars: Int = 42): String {
    if (path.length <= maxChars) return path
    val segments = path.trimEnd('/').split('/').filter { it.isNotEmpty() }
    var best = ""
    for (i in segments.indices.reversed()) {
        val candidate = segments.subList(i, segments.size).joinToString("/", prefix = "…/")
        if (candidate.length > maxChars) break
        best = candidate
    }
    return best.ifEmpty { "…" + path.takeLast(maxChars - 1) }
}

internal fun zipEntryPathForDisplay(path: String, maxChars: Int = 40): String {
    if (path.length <= maxChars) return path
    if (maxChars <= 3) return path.takeLast(maxChars.coerceAtLeast(0))
    return "..." + path.takeLast(maxChars - 3)
}

internal fun formatByteSize(bytes: Long): String {
    val units = listOf("B", "KB", "MB", "GB", "TB")
    var value = bytes.coerceAtLeast(0).toDouble()
    var unitIndex = 0
    while (value >= BYTES_PER_SIZE_UNIT && unitIndex < units.lastIndex) {
        value /= BYTES_PER_SIZE_UNIT
        unitIndex++
    }
    return if (unitIndex == 0) {
        "${value.toLong()} ${units[unitIndex]}"
    } else {
        "${java.lang.String.format(java.util.Locale.US, "%.1f", value)} ${units[unitIndex]}"
    }
}

@Composable
internal fun ListStepper(options: List<Int>, value: Int, onChange: (Int) -> Unit, modifier: Modifier = Modifier) {
    val tc = tc()
    val index = options.indexOf(value).coerceAtLeast(0)
    Row(
        modifier
            .border(0.5.dp, tc.br, RoundedCornerShape(6.dp))
            .clip(RoundedCornerShape(6.dp)),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        StepperButton("−", enabled = index > 0, onClick = { onChange(options[(index - 1).coerceAtLeast(0)]) })
        Box(Modifier.width(0.5.dp).height(28.dp).background(tc.br))
        Box(Modifier.width(44.dp).height(28.dp), contentAlignment = Alignment.Center) {
            AppText("$value", color = tc.tx, fontSize = 12.sp, fontFamily = MONO, fontWeight = FontWeight.Medium)
        }
        Box(Modifier.width(0.5.dp).height(28.dp).background(tc.br))
        StepperButton("+", enabled = index < options.lastIndex, onClick = { onChange(options[(index + 1).coerceAtMost(options.lastIndex)]) })
    }
}

@Composable
internal fun StepperButton(symbol: String, enabled: Boolean, onClick: () -> Unit) {
    val tc = tc()
    var hovered by remember { mutableStateOf(false) }
    Box(
        Modifier
            .width(28.dp).height(28.dp)
            .background(if (hovered && enabled) tc.hv else Color.Transparent)
            .then(if (enabled) Modifier.clickable(onClick = onClick) else Modifier)
            .onPointerEvent(PointerEventType.Enter) { hovered = true }
            .onPointerEvent(PointerEventType.Exit) { hovered = false },
        contentAlignment = Alignment.Center,
    ) {
        AppText(symbol, color = if (enabled) tc.tx else tc.td.copy(alpha = .4f), fontSize = 14.sp, fontWeight = FontWeight.Medium)
    }
}

// Merges the Auto/Manual toggle and the wrap-column number into one bordered pill (matching
// ListStepper's single-border, divider-between-segments look) instead of two separate controls —
// "Auto" is a plain on/off chip, not a two-way select, so the field beside it is reachable by
// toggling it off rather than by picking "Manual" from a second option.
@Composable
internal fun CtxItem(icon: ImageVector, label: String, highlighted: Boolean = false, enabled: Boolean = true, onClick: () -> Unit) {
    val tc = tc()
    HoverBox(
        modifier = Modifier.fillMaxWidth(),
        hoverBg = if (enabled) tc.hv else Color.Transparent,
        forceHover = highlighted && enabled,
        onClick = if (enabled) onClick else null,
    ) {
        Row(
            Modifier.fillMaxWidth().height(32.dp).padding(horizontal = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(Modifier.size(24.dp), contentAlignment = Alignment.Center) {
                Icon(
                    icon,
                    contentDescription = null,
                    modifier = Modifier.size(16.dp),
                    tint = if (enabled) tc.td.copy(alpha = 0.65f) else tc.td.copy(alpha = 0.3f),
                )
            }
            Spacer(Modifier.width(8.dp))
            AppText(label, color = if (enabled) tc.tx else tc.td, fontSize = 12.sp, modifier = Modifier.weight(1f))
        }
    }
}

@Composable
internal fun CtxTagActions(
    highlighted: Boolean = false,
    onInclude: () -> Unit,
    onExclude: () -> Unit,
    onHighlight: () -> Unit,
    onHighlightColor: (Color) -> Unit,
    highlightAutoColor: Color,
    preferPickerLeft: Boolean,
) {
    val tc = tc()
    HoverBox(
        modifier = Modifier.fillMaxWidth(),
        hoverBg = tc.hv,
        forceHover = highlighted,
    ) {
        Column(
            Modifier.fillMaxWidth().padding(horizontal = 6.dp, vertical = 3.dp),
            verticalArrangement = Arrangement.spacedBy(2.dp),
        ) {
            AppText("Tag", color = tc.tx, fontSize = 12.sp, modifier = Modifier.padding(start = 10.dp))
            Row(
                Modifier.fillMaxWidth().padding(start = 10.dp, end = 2.dp),
                horizontalArrangement = Arrangement.spacedBy(2.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                CtxActionSlot(CTX_ACTION_BUTTON_WIDTH) {
                    AppButton(
                        "Include", onClick = onInclude, variant = ButtonVariant.Ghost,
                        modifier = Modifier.fillMaxWidth().height(26.dp),
                        leadingIcon = Icons.AutoMirrored.Outlined.Label, horizontalPadding = 4.dp,
                    )
                }
                CtxActionDivider(tc)
                CtxActionSlot(CTX_ACTION_BUTTON_WIDTH) {
                    AppButton(
                        "Exclude", onClick = onExclude, variant = ButtonVariant.Ghost,
                        modifier = Modifier.fillMaxWidth().height(26.dp),
                        leadingIcon = Icons.AutoMirrored.Outlined.LabelOff, horizontalPadding = 4.dp,
                    )
                }
                CtxActionDivider(tc)
                CtxActionSlot(CTX_HIGHLIGHT_ACTION_WIDTH) {
                    CtxHighlightAction(
                        onHighlight = onHighlight,
                        onHighlightColor = onHighlightColor,
                        autoColor = highlightAutoColor,
                        preferLeft = preferPickerLeft,
                    )
                }
            }
        }
    }
}

@Composable
internal fun CtxCollapseActions(
    highlighted: Boolean = false,
    onToStart: (() -> Unit)? = null,
    onToEnd: (() -> Unit)? = null,
    onSelected: (() -> Unit)? = null,
) {
    val tc = tc()
    HoverBox(
        modifier = Modifier.fillMaxWidth(),
        hoverBg = tc.hv,
        forceHover = highlighted,
    ) {
        Column(
            Modifier.fillMaxWidth().padding(horizontal = 6.dp, vertical = 3.dp),
            verticalArrangement = Arrangement.spacedBy(2.dp),
        ) {
            AppText("Collapse", color = tc.tx, fontSize = 12.sp, modifier = Modifier.padding(start = 10.dp))
            Row(
                Modifier.fillMaxWidth().padding(horizontal = 10.dp),
                horizontalArrangement = Arrangement.spacedBy(2.dp, Alignment.CenterHorizontally),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                onToStart?.let {
                    CtxActionSlot(CTX_ACTION_BUTTON_WIDTH) {
                        AppButton(
                            "To start", onClick = it, variant = ButtonVariant.Ghost,
                            modifier = Modifier.fillMaxWidth().height(26.dp),
                            leadingIcon = Icons.Outlined.ArrowUpward, horizontalPadding = 4.dp,
                        )
                    }
                }
                if (onToStart != null && (onToEnd != null || onSelected != null)) {
                    CtxActionDivider(tc)
                }
                onToEnd?.let {
                    CtxActionSlot(CTX_ACTION_BUTTON_WIDTH) {
                        AppButton(
                            "To End", onClick = it, variant = ButtonVariant.Ghost,
                            modifier = Modifier.fillMaxWidth().height(26.dp),
                            leadingIcon = Icons.Outlined.ArrowDownward, horizontalPadding = 4.dp,
                        )
                    }
                }
                if (onToEnd != null && onSelected != null) {
                    CtxActionDivider(tc)
                }
                onSelected?.let {
                    CtxActionSlot(CTX_ACTION_BUTTON_WIDTH) {
                        AppButton(
                            "Selected", onClick = it, variant = ButtonVariant.Ghost,
                            modifier = Modifier.fillMaxWidth().height(26.dp),
                            leadingIcon = Icons.Outlined.Layers, horizontalPadding = 4.dp,
                        )
                    }
                }
            }
        }
    }
}

// Mirrors CtxCollapseActions's own shape (header + one row of Ghost buttons) — replaces what used
// to be a single "Set sequence ▶" flyout (Start / Async start / End behind a submenu) with the same
// always-visible inline row the "Tag" and "Collapse" blocks above already use, per the user's own
// request to match that established convention rather than hide these three behind a flyout.
// "End" follows CtxCollapseActions' nullable-lambda convention exactly: the call site passes null
// when no sequence start is pending, and this renders that as a visibly-disabled (not omitted)
// button — same "shown but disabled beats silently doing nothing" rule CollapseActions itself
// documents, and the same rule the pre-inline flyout's own CtxSubmenuOption("End", enabled = ...)
// followed.
@Composable
internal fun CtxSequenceActions(
    highlighted: Boolean = false,
    onStart: () -> Unit,
    onAsyncStart: () -> Unit,
    onEnd: (() -> Unit)? = null,
) {
    val tc = tc()
    HoverBox(
        modifier = Modifier.fillMaxWidth(),
        hoverBg = tc.hv,
        forceHover = highlighted,
    ) {
        Column(
            Modifier.fillMaxWidth().padding(horizontal = 6.dp, vertical = 3.dp),
            verticalArrangement = Arrangement.spacedBy(2.dp),
        ) {
            AppText("Sequence", color = tc.tx, fontSize = 12.sp, modifier = Modifier.padding(start = 10.dp))
            Row(
                Modifier.fillMaxWidth().padding(start = 10.dp, end = 2.dp),
                horizontalArrangement = Arrangement.spacedBy(2.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                CtxActionSlot(CTX_SEQUENCE_BUTTON_WIDTH) {
                    AppButton(
                        "Start", onClick = onStart, variant = ButtonVariant.Ghost,
                        modifier = Modifier.fillMaxWidth().height(26.dp),
                        leadingIcon = Icons.Outlined.PlayArrow, horizontalPadding = 4.dp,
                    )
                }
                CtxActionDivider(tc)
                CtxActionSlot(CTX_SEQUENCE_ASYNC_BUTTON_WIDTH) {
                    AppButton(
                        "Async start", onClick = onAsyncStart, variant = ButtonVariant.Ghost,
                        modifier = Modifier.fillMaxWidth().height(26.dp),
                        leadingIcon = Icons.Outlined.PlayArrow, horizontalPadding = 4.dp,
                    )
                }
                CtxActionDivider(tc)
                CtxActionSlot(CTX_SEQUENCE_BUTTON_WIDTH) {
                    AppButton(
                        "End", onClick = onEnd ?: {}, variant = ButtonVariant.Ghost, enabled = onEnd != null,
                        modifier = Modifier.fillMaxWidth().height(26.dp),
                        leadingIcon = Icons.Outlined.Flag, horizontalPadding = 4.dp,
                    )
                }
            }
        }
    }
}

// Mirrors CtxCollapseActions's own shape (header + one or two rows of Ghost buttons) — merges what
// used to be two adjacent blocks for the SAME process ("Threads" with its Show/Hide map pair, and a
// separate "Process name" with its own Show/Hide name pair) into one "Process" section, since a user
// right-clicking one row has no reason to read two headers naming the same process back to back.
// onShowMap/onHideMap can legitimately both be non-null at once (see App.kt's call site — hiding an
// unrelated already-open map while also offering to show this row's own); onShowName/onHideName are
// mutually exclusive by construction (App.kt's currentlyShown boolean sets exactly one). So this
// block renders 1 to 3 buttons total (2 map + at most 1 name).
//
// "Show map"/"Hide map"/"Show name"/"Hide name" are full, unambiguous labels — never truncated to a
// bare verb, since a bare "Hide" can't tell the user which of the map or the name it would hide. A
// prior version crammed all 3 into one Row via equal-weight slots, which squeezed these two-word
// labels down to truncated stubs at this menu's fixed width. Buttons are laid out at most two per
// row (fixed-width slots, like every sibling block below — CtxActionSlot centers each button's
// content the same way CtxTagActions/CtxCollapseActions/CtxSelectionActions do), wrapping the third
// button — only reachable when both map actions AND a name action are available at once — onto its
// own second row. So: 1 or 2 buttons → a single row; 3 → two rows (2 + 1).
@Composable
internal fun CtxProcessActions(
    highlighted: Boolean = false,
    onShowMap: (() -> Unit)? = null,
    onHideMap: (() -> Unit)? = null,
    onShowName: (() -> Unit)? = null,
    onHideName: (() -> Unit)? = null,
    // The resolved process this whole block is about — this row's own name when known, falling
    // back (at the call site) to the pid the ACTIVE map targets when this row's own pid has no
    // name of its own. Appended to the header as "Process — <processLabel>"; never null in
    // practice since the call site only ever adds this entry when at least one action is available,
    // and at least one of the four actions being available always implies a resolvable pid.
    processLabel: String? = null,
) {
    val tc = tc()
    HoverBox(
        modifier = Modifier.fillMaxWidth(),
        hoverBg = tc.hv,
        forceHover = highlighted,
    ) {
        Column(
            Modifier.fillMaxWidth().padding(horizontal = 6.dp, vertical = 3.dp),
            verticalArrangement = Arrangement.spacedBy(2.dp),
        ) {
            val headerText = if (processLabel != null) "Process — $processLabel" else "Process"
            AppText(headerText, color = tc.tx, fontSize = 12.sp, modifier = Modifier.padding(start = 10.dp))
            val buttons = buildList {
                onShowMap?.let { add(CtxProcessButtonSpec("Show map", Icons.Outlined.AccountTree, it)) }
                onHideMap?.let { add(CtxProcessButtonSpec("Hide map", Icons.Outlined.VisibilityOff, it)) }
                onShowName?.let { add(CtxProcessButtonSpec("Show name", Icons.Outlined.Badge, it)) }
                onHideName?.let { add(CtxProcessButtonSpec("Hide name", Icons.Outlined.VisibilityOff, it)) }
            }
            buttons.chunked(2).forEach { rowButtons ->
                Row(
                    Modifier.fillMaxWidth().padding(horizontal = 10.dp),
                    horizontalArrangement = Arrangement.spacedBy(2.dp, Alignment.CenterHorizontally),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    rowButtons.forEachIndexed { index, spec ->
                        CtxActionSlot(CTX_THREADS_BUTTON_WIDTH) {
                            AppButton(
                                spec.label, onClick = spec.onClick, variant = ButtonVariant.Ghost,
                                modifier = Modifier.fillMaxWidth().height(26.dp),
                                leadingIcon = spec.icon, horizontalPadding = 4.dp,
                            )
                        }
                        if (index != rowButtons.lastIndex) {
                            CtxActionDivider(tc)
                        }
                    }
                }
            }
        }
    }
}

private data class CtxProcessButtonSpec(val label: String, val icon: ImageVector, val onClick: () -> Unit)

// Mirrors CtxProcessActions' own per-row shape exactly (header + a row of Ghost buttons) — the
// "Link to current video position"/"Show in video" pair used to render as two independent (and a
// third, now-dropped "Link to video start (0:00)") CtxMenuEntry.Action rows; this groups the
// surviving two under a "Video" header the same way Process groups its own show/hide-map actions.
@Composable
internal fun CtxVideoActions(
    highlighted: Boolean = false,
    linkLabel: String,
    onLink: () -> Unit,
    showEnabled: Boolean,
    onShow: () -> Unit,
) {
    val tc = tc()
    HoverBox(
        modifier = Modifier.fillMaxWidth(),
        hoverBg = tc.hv,
        forceHover = highlighted,
    ) {
        Column(
            Modifier.fillMaxWidth().padding(horizontal = 6.dp, vertical = 3.dp),
            verticalArrangement = Arrangement.spacedBy(2.dp),
        ) {
            AppText("Video", color = tc.tx, fontSize = 12.sp, modifier = Modifier.padding(start = 10.dp))
            Row(
                Modifier.fillMaxWidth().padding(horizontal = 10.dp),
                horizontalArrangement = Arrangement.spacedBy(2.dp, Alignment.CenterHorizontally),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                CtxActionSlot(CTX_VIDEO_BUTTON_WIDTH) {
                    AppButton(
                        linkLabel, onClick = onLink, variant = ButtonVariant.Ghost,
                        modifier = Modifier.fillMaxWidth().height(26.dp),
                        leadingIcon = Icons.Outlined.Link, horizontalPadding = 4.dp,
                    )
                }
                CtxActionDivider(tc)
                CtxActionSlot(CTX_VIDEO_BUTTON_WIDTH) {
                    AppButton(
                        "Show", onClick = onShow, variant = ButtonVariant.Ghost, enabled = showEnabled,
                        modifier = Modifier.fillMaxWidth().height(26.dp),
                        leadingIcon = Icons.Outlined.Movie, horizontalPadding = 4.dp,
                    )
                }
            }
        }
    }
}

@Composable
internal fun CtxSelectionActions(
    highlighted: Boolean = false,
    onAskAi: () -> Unit,
    onCopy: () -> Unit,
    onHighlight: () -> Unit,
    onHighlightColor: (Color) -> Unit,
    highlightAutoColor: Color,
    preferPickerLeft: Boolean,
) {
    val tc = tc()
    HoverBox(
        modifier = Modifier.fillMaxWidth(),
        hoverBg = tc.hv,
        forceHover = highlighted,
    ) {
        Column(
            Modifier.fillMaxWidth().padding(horizontal = 6.dp, vertical = 3.dp),
            verticalArrangement = Arrangement.spacedBy(2.dp),
        ) {
            AppText("Selection", color = tc.tx, fontSize = 12.sp, modifier = Modifier.padding(start = 10.dp))
            Row(
                Modifier.fillMaxWidth().padding(start = 10.dp, end = 2.dp),
                horizontalArrangement = Arrangement.spacedBy(2.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                CtxActionSlot(CTX_ACTION_BUTTON_WIDTH) {
                    AppButton(
                        "Ask AI", onClick = onAskAi, variant = ButtonVariant.Ghost,
                        modifier = Modifier.fillMaxWidth().height(26.dp),
                        leadingIcon = Icons.Outlined.FindInPage, horizontalPadding = 4.dp,
                    )
                }
                CtxActionDivider(tc)
                CtxActionSlot(CTX_ACTION_BUTTON_WIDTH) {
                    AppButton(
                        "Copy", onClick = onCopy, variant = ButtonVariant.Ghost,
                        modifier = Modifier.fillMaxWidth().height(26.dp),
                        leadingIcon = Icons.Outlined.ContentCopy, horizontalPadding = 4.dp,
                    )
                }
                CtxActionDivider(tc)
                CtxActionSlot(CTX_HIGHLIGHT_ACTION_WIDTH) {
                    CtxHighlightAction(
                        onHighlight = onHighlight,
                        onHighlightColor = onHighlightColor,
                        autoColor = highlightAutoColor,
                        preferLeft = preferPickerLeft,
                    )
                }
            }
        }
    }
}

@Composable
internal fun CtxSourceActions(
    highlighted: Boolean = false,
    enabled: Boolean = true,
    onShowCode: () -> Unit,
    onOpenFile: () -> Unit,
) {
    val tc = tc()
    HoverBox(
        modifier = Modifier.fillMaxWidth(),
        hoverBg = tc.hv,
        forceHover = highlighted,
    ) {
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 10.dp, vertical = 3.dp),
            horizontalArrangement = Arrangement.spacedBy(2.dp, Alignment.CenterHorizontally),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            CtxActionSlot(90.dp) {
                AppButton(
                    "Show code",
                    onClick = onShowCode,
                    variant = ButtonVariant.Ghost,
                    enabled = enabled,
                    modifier = Modifier.height(26.dp),
                    leadingIcon = Icons.Outlined.FindInPage,
                    horizontalPadding = 4.dp,
                )
            }
            CtxActionDivider(tc)
            CtxActionSlot(90.dp) {
                AppButton(
                    "Open file",
                    onClick = onOpenFile,
                    variant = ButtonVariant.Ghost,
                    enabled = enabled,
                    modifier = Modifier.height(26.dp),
                    leadingIcon = Icons.AutoMirrored.Outlined.OpenInNew,
                    horizontalPadding = 4.dp,
                )
            }
        }
    }
}

@Composable
private fun CtxActionSlot(
    width: Dp,
    alignment: Alignment = Alignment.Center,
    content: @Composable () -> Unit,
) {
    Box(Modifier.width(width), contentAlignment = alignment) { content() }
}

// All grouped actions deliberately use one shared width so the three columns line up
// across Selection, Tag, and Collapse rows, regardless of label length.
private val CTX_ACTION_BUTTON_WIDTH = 78.dp

// CtxProcessActions' own width: "Show map"/"Hide map"/"Show name"/"Hide name" are two-word labels
// built almost entirely from wide glyphs, measurably wider than the single-word labels
// CTX_ACTION_BUTTON_WIDTH was sized for — 78dp truncated them. Sized for two per row (that block
// wraps a third onto its own row rather than trying to fit 3 across).
private val CTX_THREADS_BUTTON_WIDTH = 100.dp

// CtxSequenceActions' own widths. Three slots (not Tag/Collapse's two-plus-a-wide-highlight-slot)
// have to share the SAME 252dp row budget those blocks already fill exactly (menuWidth 276dp minus
// the Column's 6dp+6dp padding minus the Row's 10dp+2dp padding) — reusing CTX_ACTION_BUTTON_WIDTH
// (78dp, sized for Tag/Collapse's own single- and two-word labels) for all three would overrun that
// budget by 14dp the moment the middle slot is widened for "Async start" (see below), pushing the
// row past the menu's own right edge. "Start"/"End" are short enough (5 and 3 letters) to stay
// fully legible well below 78dp, so they're the ones that give up the shared width here — NOT
// "Async start", which is the label that actually needs the room. Sized with a few dp of slack
// over what "Start"/"End" ask for at this menu's 12sp Ghost-button font, not tuned to the exact
// truncation edge, since font-metrics estimation from a comment is not a substitute for opening the
// menu and looking.
private val CTX_SEQUENCE_BUTTON_WIDTH = 64.dp

// "Async start" is a two-word label that overruns CTX_ACTION_BUTTON_WIDTH's 78dp (mirrors
// CTX_THREADS_BUTTON_WIDTH's own reasoning for "Show map"/"Hide map") — 108dp is CTX_SEQUENCE_
// BUTTON_WIDTH's freed-up budget (242dp for three slots, minus 64dp+64dp for Start/End) rounded
// down to a comfortable value, not the tightest width that happens to fit.
private val CTX_SEQUENCE_ASYNC_BUTTON_WIDTH = 108.dp

// Video (CtxVideoActions) has exactly 2 slots, and "Link to 12:34" runs measurably longer than
// most other Ghost-button labels in this menu (a fixed "Link to " prefix plus a variable mm:ss
// timestamp) — 78dp clipped its trailing digits on longer recordings, so this gets its own, wider
// width rather than reusing CTX_ACTION_BUTTON_WIDTH.
private val CTX_VIDEO_BUTTON_WIDTH = 118.dp

// Extends into the row's existing right padding so the picker target can align with the
// Show/Hide chevron without overlapping the Highlight label or leaving its hit area.
private val CTX_HIGHLIGHT_ACTION_WIDTH = 86.dp

@Composable
private fun CtxActionDivider(colors: ThemeColors) {
    Box(Modifier.width(1.dp).height(18.dp).background(colors.br))
}

// 82dp inner width fits five 14dp swatches with 3dp gaps, but not a sixth. Matching the
// 9dp vertical padding makes every edge around the grid equally spaced.
private val CTX_HIGHLIGHT_PICKER_WIDTH = 100.dp

// The primary button preserves the existing grouped Highlight action. Its wider final slot puts
// the entire picker target in the same position as the Show/Hide messages submenu chevron.
@Composable
private fun CtxHighlightAction(
    onHighlight: () -> Unit,
    onHighlightColor: (Color) -> Unit,
    autoColor: Color,
    preferLeft: Boolean,
) {
    val tc = tc()
    val density = LocalDensity.current
    var hoveringTrigger by remember { mutableStateOf(false) }
    var hoveringPopup by remember { mutableStateOf(false) }
    var pickerOpen by remember { mutableStateOf(false) }
    var anchorWidthPx by remember { mutableStateOf(0) }
    LaunchedEffect(hoveringTrigger, hoveringPopup) {
        if (hoveringTrigger || hoveringPopup) {
            pickerOpen = true
        } else if (pickerOpen) {
            delay(CTX_SUBMENU_CLOSE_DELAY_MS)
            pickerOpen = false
        }
    }
    Box(Modifier.fillMaxWidth().height(26.dp).onGloballyPositioned { anchorWidthPx = it.size.width }) {
        AppButton(
            "Highlight", onClick = onHighlight, variant = ButtonVariant.Ghost,
            modifier = Modifier.fillMaxSize().padding(end = 24.dp),
            horizontalPadding = 4.dp,
        )
        HoverBox(
            modifier = Modifier.align(Alignment.CenterEnd).size(24.dp).clip(RoundedCornerShape(6.dp))
                .onPointerEvent(PointerEventType.Enter) { hoveringTrigger = true }
                .onPointerEvent(PointerEventType.Exit) { hoveringTrigger = false },
            onClick = { pickerOpen = true },
        ) {
            Box(
                Modifier.align(Alignment.Center).size(10.dp)
                    .background(autoColor, CORNER_SM)
                    .border(1.dp, tc.br, CORNER_SM),
            )
        }
        if (pickerOpen) {
            Popup(
                alignment = Alignment.TopStart,
                offset = IntOffset(
                    if (preferLeft) -with(density) { CTX_HIGHLIGHT_PICKER_WIDTH.roundToPx() } else anchorWidthPx,
                    0,
                ),
                onDismissRequest = { pickerOpen = false },
                properties = PopupProperties(focusable = false),
            ) {
                FlowRow(
                    Modifier.width(CTX_HIGHLIGHT_PICKER_WIDTH)
                        .shadow(8.dp, RoundedCornerShape(7.dp))
                        .background(tc.p, RoundedCornerShape(7.dp))
                        .border(1.dp, tc.br, RoundedCornerShape(7.dp))
                        .padding(9.dp)
                        .onPointerEvent(PointerEventType.Enter) { hoveringPopup = true }
                        .onPointerEvent(PointerEventType.Exit) { hoveringPopup = false },
                    horizontalArrangement = Arrangement.spacedBy(3.dp),
                    verticalArrangement = Arrangement.spacedBy(3.dp),
                ) {
                    HL_COLORS.forEach { color ->
                        ColorSwatch(color, color == autoColor) {
                            pickerOpen = false
                            onHighlightColor(color)
                        }
                    }
                }
            }
        }
    }
}

// Same row as CtxItem plus a trailing ▶ hit target: hovering/pressing that arrow specifically
// opens a flyout of narrower match-scope choices without triggering the row's own default
// onClick, which still fires from anywhere else on the row (matching today's one-click behavior).
internal val CTX_SUBMENU_WIDTH = 240.dp

// One row inside a CtxItemWithSubmenu flyout. [enabled] defaults true — every pre-existing caller
// (match-scope variants, fragment-kind grouping) built its options from data that's always
// actionable, so they keep compiling/rendering unchanged. false is for an option that's always
// SHOWN (never omitted from the list — omitting it would silently look like it doesn't exist)
// but currently not actionable, e.g. "End" in the "Set sequence ▶" flyout (App.kt) while no
// sequence start is pending — rendered greyed-out and non-clickable, mirroring CtxItem's own
// enabled-vs-disabled styling rather than inventing a second convention for the same idea.
internal data class CtxSubmenuOption(val label: String, val enabled: Boolean = true, val onClick: () -> Unit)

// Grace period between the pointer leaving both the ▶ trigger and the popup, and the popup
// actually closing — without it, crossing the (small) visual gap between trigger and popup would
// close the menu before the pointer ever reaches it.
internal const val CTX_SUBMENU_CLOSE_DELAY_MS = 200L

@Composable
internal fun CtxItemWithSubmenu(
    icon: ImageVector,
    label: String,
    submenu: List<CtxSubmenuOption>,
    highlighted: Boolean = false,
    preferLeft: Boolean = false,
    onClick: () -> Unit,
) {
    val tc = tc()
    val density = LocalDensity.current
    var hoveringTrigger by remember { mutableStateOf(false) }
    var hoveringPopup by remember { mutableStateOf(false) }
    var submenuOpen by remember { mutableStateOf(false) }
    var rowWidthPx by remember { mutableStateOf(0) }
    // Opens instantly on hover/press; closes only after a grace period with the pointer outside
    // BOTH the trigger and the popup, so moving the mouse out doesn't leave it stuck open forever
    // (the old behavior) nor close it while crossing the gap to reach the popup.
    LaunchedEffect(hoveringTrigger, hoveringPopup) {
        if (hoveringTrigger || hoveringPopup) {
            submenuOpen = true
        } else if (submenuOpen) {
            delay(CTX_SUBMENU_CLOSE_DELAY_MS)
            submenuOpen = false
        }
    }
    Box(Modifier.onGloballyPositioned { coords -> rowWidthPx = coords.size.width }) {
        HoverBox(modifier = Modifier.fillMaxWidth(), forceHover = highlighted, onClick = onClick) {
            Row(
                Modifier.fillMaxWidth().height(32.dp).padding(horizontal = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Box(Modifier.size(24.dp), contentAlignment = Alignment.Center) {
                    Icon(icon, contentDescription = null, modifier = Modifier.size(16.dp), tint = tc.td.copy(alpha = 0.65f))
                }
                Spacer(Modifier.width(8.dp))
                AppText(label, color = tc.tx, fontSize = 12.sp, modifier = Modifier.weight(1f))
                // Same rounded-hover affordance as the log row collapse chevron (CollapseChevron
                // in LogViewer.kt) — a plain unstyled glyph gave no feedback that this was a
                // separate, more-specific hit target than the rest of the row.
                HoverBox(
                    modifier = Modifier.size(24.dp).clip(RoundedCornerShape(6.dp))
                        .onPointerEvent(PointerEventType.Enter) { hoveringTrigger = true }
                        .onPointerEvent(PointerEventType.Exit) { hoveringTrigger = false },
                    onClick = { submenuOpen = true },
                ) {
                    AppText(
                        "▶", color = tc.td.copy(alpha = 0.65f), fontSize = 10.sp,
                        modifier = Modifier.align(Alignment.Center),
                    )
                }
            }
        }
        if (submenuOpen) {
            Popup(
                // Popup's alignment describes where the popup's OWN alignment point lands within
                // the anchor's bounds — TopStart with zero offset overlaps the anchor exactly
                // (popup's top-left = anchor's top-left). To sit the popup beside the anchor with
                // no overlap, keep alignment fixed at TopStart and shift by a real measured
                // distance instead: +rowWidth pushes it past the anchor's right edge (submenu
                // appears to the right); -popupWidth pushes it past the anchor's own left edge
                // (submenu appears to the left) when there isn't enough window space on the right.
                alignment = Alignment.TopStart,
                offset = IntOffset(
                    if (preferLeft) -with(density) { CTX_SUBMENU_WIDTH.roundToPx() } else rowWidthPx,
                    0,
                ),
                onDismissRequest = { submenuOpen = false },
                properties = PopupProperties(focusable = false),
            ) {
                Column(
                    Modifier.width(CTX_SUBMENU_WIDTH)
                        .shadow(8.dp, RoundedCornerShape(7.dp))
                        .background(tc.p, RoundedCornerShape(7.dp))
                        .border(1.dp, tc.br, RoundedCornerShape(7.dp))
                        .padding(vertical = 4.dp)
                        .onPointerEvent(PointerEventType.Enter) { hoveringPopup = true }
                        .onPointerEvent(PointerEventType.Exit) { hoveringPopup = false },
                ) {
                    submenu.forEach { option ->
                        // Same enabled-vs-disabled styling CtxItem uses (muted tc.td text, no
                        // hover feedback, no click) — a disabled option (e.g. "End" with no
                        // pending sequence start) stays visible so its unavailability is legible
                        // rather than silently vanishing from the list.
                        HoverBox(
                            modifier = Modifier.fillMaxWidth(),
                            hoverBg = if (option.enabled) tc.hv else Color.Transparent,
                            onClick = if (option.enabled) ({ submenuOpen = false; option.onClick() }) else null,
                        ) {
                            // Only long variant labels (a long tag/package prefix, or an
                            // untruncated message) actually get ellipsized in this fixed-width
                            // popup — onTextLayout reports whether *this* label did, so short
                            // labels that already fit in full don't get a redundant tooltip.
                            var isTruncated by remember(option.label) { mutableStateOf(false) }
                            val optionTextColor = if (option.enabled) tc.tx else tc.td
                            val labelText: @Composable () -> Unit = {
                                Box(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp)) {
                                    AppText(
                                        option.label, color = optionTextColor, fontSize = 12.sp, maxLines = 1,
                                        overflow = TextOverflow.Ellipsis,
                                        onTextLayout = { result -> isTruncated = result.hasVisualOverflow },
                                    )
                                }
                            }
                            if (isTruncated) {
                                TooltipArea(
                                    tooltip = {
                                        Box(
                                            Modifier.background(tc.p2, RoundedCornerShape(4.dp))
                                                .border(0.5.dp, tc.br, RoundedCornerShape(4.dp))
                                                .padding(horizontal = 8.dp, vertical = 4.dp)
                                                .widthIn(max = 320.dp),
                                        ) {
                                            AppText(option.label, color = tc.tx, fontSize = 11.sp, maxLines = 4)
                                        }
                                    },
                                ) {
                                    labelText()
                                }
                            } else {
                                labelText()
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
internal fun CtxDivider() {
    val tc = tc()
    Spacer(Modifier.height(4.dp))
    Box(Modifier.fillMaxWidth().height(0.5.dp).background(tc.br))
    Spacer(Modifier.height(4.dp))
}

// Data-driven context menu entries so keyboard nav (arrow keys) can walk the selectable ones
// without duplicating the conditional logic that decides which items render.
internal sealed class CtxMenuEntry {
    data class ActionHeader(val label: String, val onClick: () -> Unit) : CtxMenuEntry()

    data class Action(val icon: ImageVector, val label: String, val enabled: Boolean = true, val onClick: () -> Unit) : CtxMenuEntry()

    data class TagActions(
        val onInclude: () -> Unit,
        val onExclude: () -> Unit,
        val onHighlight: () -> Unit,
        val onHighlightColor: (Color) -> Unit,
        val highlightAutoColor: Color,
        val preferPickerLeft: Boolean,
    ) : CtxMenuEntry()

    data class CollapseActions(
        val onToStart: (() -> Unit)? = null,
        val onToEnd: (() -> Unit)? = null,
        val onSelected: (() -> Unit)? = null,
    ) : CtxMenuEntry()

    // Start / Async start are always available; onEnd is null (rendered disabled, not omitted —
    // see CtxSequenceActions' own doc) whenever no sequence start is currently pending.
    data class SequenceActions(
        val onStart: () -> Unit,
        val onAsyncStart: () -> Unit,
        val onEnd: (() -> Unit)? = null,
    ) : CtxMenuEntry()

    // The merged tid-map + process-name section for one row's process (see CtxProcessActions —
    // used to be two separate entries, ThreadsActions and ProcessNameActions, for the same
    // process). onShowMap/onHideMap resolve via utils/TidMap.kt's tidMapProcessLabel the same way
    // they always did; onShowName/onHideName are mutually exclusive by construction (App.kt sets
    // exactly one, based on whether this pid is currently shown). processLabel is null only in the
    // (untested-in-practice) case where none of the four actions are set, which the call site never
    // actually adds as a menu entry — see the "an empty header with no buttons under it must not
    // render" rule both predecessor blocks followed.
    data class ProcessActions(
        val onShowMap: (() -> Unit)? = null,
        val onHideMap: (() -> Unit)? = null,
        val onShowName: (() -> Unit)? = null,
        val onHideName: (() -> Unit)? = null,
        val processLabel: String? = null,
    ) : CtxMenuEntry()

    // "Link" always overwrites whatever anchor already existed (one anchor per tab — see
    // VideoAttachment.anchor's own doc); its label is precomputed by the call site (App.kt) rather
    // than formatted in here, since only that site has the live VideoPlayerController position.
    // "Show" mirrors SourceActions' enabled-not-hidden convention: it stays visible but disabled
    // until an anchor exists AND the row actually maps to a video position.
    data class VideoActions(
        val linkLabel: String,
        val onLink: () -> Unit,
        val showEnabled: Boolean,
        val onShow: () -> Unit,
    ) : CtxMenuEntry()

    data class SelectionActions(
        val onAskAi: () -> Unit,
        val onCopy: () -> Unit,
        val onHighlight: () -> Unit,
        val onHighlightColor: (Color) -> Unit,
        val highlightAutoColor: Color,
        val preferPickerLeft: Boolean,
    ) : CtxMenuEntry()

    data class SourceActions(
        val enabled: Boolean,
        val onShowCode: () -> Unit,
        val onOpenFile: () -> Unit,
    ) : CtxMenuEntry()

    // The main row keeps today's default onClick; hovering/pressing the trailing ▶ opens a
    // flyout with up to 4 narrower match-scope choices instead (see messageRuleVariantsFromCtx).
    data class ActionWithSubmenu(
        val icon: ImageVector,
        val label: String,
        val onClick: () -> Unit,
        val submenu: List<CtxSubmenuOption>,
    ) : CtxMenuEntry()

    object Divider : CtxMenuEntry()

    object Preview : CtxMenuEntry()
}
