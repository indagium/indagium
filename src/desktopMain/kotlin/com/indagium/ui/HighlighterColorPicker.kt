package com.indagium.ui

import androidx.compose.foundation.*
import androidx.compose.foundation.focusable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.LocalMinimumInteractiveComponentSize
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.isSecondaryPressed
import androidx.compose.ui.input.pointer.onPointerEvent
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupProperties
import kotlin.math.max
import kotlin.math.min

internal const val MAX_CUSTOM_HIGHLIGHT_COLORS = 256
private const val COLOR_COMPONENT_MAX = 255f
private const val COLOR_ROUNDING_BIAS = 0.5f
private const val PAGED_PALETTE_COLUMNS = 5
private const val PAGED_PALETTE_PAGE_SIZE = 25
private const val RECTANGLE_PALETTE_COLUMNS = 10
private const val RECTANGLE_PALETTE_PAGE_SIZE = 100

/** Parses the import/editor spelling used throughout Indagium: #RRGGBB or #AARRGGBB. */
internal fun parseHighlightHex(raw: String): Color? {
    val digits = raw.trim().removePrefix("#")
    if (digits.length != 6 && digits.length != 8) return null
    if (digits.any { it.digitToIntOrNull(16) == null }) return null
    val argb = if (digits.length == 6) "FF$digits" else digits
    return runCatching { Color(argb.toLong(16).toInt()) }.getOrNull()
}

internal fun highlightColorHex(color: Color): String {
    fun channel(value: Float) = (value.coerceIn(0f, 1f) * COLOR_COMPONENT_MAX + COLOR_ROUNDING_BIAS).toInt()
    val argb = (channel(color.alpha) shl 24) or (channel(color.red) shl 16) or
        (channel(color.green) shl 8) or channel(color.blue)
    return "#%08X".format(argb)
}

internal fun customHighlightColors(raw: List<String>): List<Color> =
    raw.mapNotNull(::parseHighlightHex).distinct().take(MAX_CUSTOM_HIGHLIGHT_COLORS)

/** Color swatch and a bounded popup editor shared by background and foreground colors. */
@Suppress("UnusedParameter") // Kept for existing callers; section pickers are now always 10 columns.
@Composable
internal fun HighlighterColorPicker(
    color: Color,
    onColorChange: (Color) -> Unit,
    customColors: List<Color>,
    onSaveCustomColor: (Color) -> Unit,
    onDeleteCustomColor: (Color) -> Unit,
    paletteColumns: Int,
    onPaletteColumnsChange: (Int) -> Unit,
    pickerOpen: Boolean,
    onPickerOpenChange: (Boolean) -> Unit,
    testTagPrefix: String = "highlighter-color",
    customColorEditorExpanded: Boolean? = null,
    onCustomColorEditorExpandedChange: ((Boolean) -> Unit)? = null,
) {
    val density = androidx.compose.ui.platform.LocalDensity.current
    val windowSize = LocalWindowInfo.current.containerSize
    var localEditorExpanded by remember(testTagPrefix) { mutableStateOf(true) }
    val editorExpanded = customColorEditorExpanded ?: localEditorExpanded
    val setEditorExpanded: (Boolean) -> Unit = { expanded ->
        localEditorExpanded = expanded
        onCustomColorEditorExpandedChange?.invoke(expanded)
    }
    val widthLimit = with(density) { (windowSize.width.toDp() - 24.dp).coerceAtLeast(120.dp) }
    val heightLimit = with(density) { (windowSize.height.toDp() - 32.dp).coerceAtLeast(160.dp) }
    val preferredWidth = if (editorExpanded) 320.dp else 208.dp
    val popupWidth = minOf(preferredWidth, widthLimit)
    val popupHeight = minOf(560.dp, heightLimit)
    Box(Modifier.testTag("$testTagPrefix-trigger")) {
        ColorPickerSwatch(color, pickerOpen, { onPickerOpenChange(!pickerOpen) }, size = 16.dp)
        if (pickerOpen) {
            Popup(
                alignment = Alignment.TopStart,
                offset = IntOffset(0, with(density) { 22.dp.roundToPx() }),
                onDismissRequest = { onPickerOpenChange(false) },
                properties = PopupProperties(focusable = true),
            ) {
                HighlighterColorPickerContent(
                    color = color,
                    onColorChange = onColorChange,
                    customColors = customColors,
                    onSaveCustomColor = onSaveCustomColor,
                    onDeleteCustomColor = onDeleteCustomColor,
                    testTagPrefix = testTagPrefix,
                    customColorEditorExpanded = editorExpanded,
                    onCustomColorEditorExpandedChange = setEditorExpanded,
                    onDismiss = { onPickerOpenChange(false) },
                    modifier = Modifier.width(popupWidth)
                        .heightIn(max = popupHeight).verticalScroll(rememberScrollState()),
                )
            }
        }
    }
}

/** Shared color editor, including persistent custom colors. */
@OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
@Composable
private fun HighlighterColorPickerContent(
    color: Color,
    onColorChange: (Color) -> Unit,
    customColors: List<Color>,
    onSaveCustomColor: (Color) -> Unit,
    onDeleteCustomColor: (Color) -> Unit,
    testTagPrefix: String,
    customColorEditorExpanded: Boolean,
    onCustomColorEditorExpandedChange: (Boolean) -> Unit,
    onDismiss: () -> Unit,
    modifier: Modifier,
) {
    val tc = tc()
    val popupFocusRequester = remember(testTagPrefix) { FocusRequester() }
    LaunchedEffect(popupFocusRequester) { runCatching { popupFocusRequester.requestFocus() } }
    var hexDraft by remember(color, testTagPrefix) { mutableStateOf(highlightColorHex(color)) }
    val parsedHex = parseHighlightHex(hexDraft)
    var hsv by remember(testTagPrefix) { mutableStateOf(rgbToHsv(color).toList()) }
    var observedColor by remember(testTagPrefix) { mutableStateOf(color) }
    var preserveHsvForNextColor by remember(testTagPrefix) { mutableStateOf(false) }
    LaunchedEffect(color) {
        if (color != observedColor) {
            if (!preserveHsvForNextColor) hsv = rgbToHsv(color).toList()
            preserveHsvForNextColor = false
            observedColor = color
        }
    }

    fun updateHsv(index: Int, value: Float) {
        val next = hsv.toMutableList().also { it[index] = value }
        val nextColor = Color.hsv(next[0], next[1], next[2], color.alpha)
        hsv = next
        preserveHsvForNextColor = nextColor != color
        onColorChange(nextColor)
    }

    Column(
        modifier.focusRequester(popupFocusRequester).focusable().onPreviewKeyEvent { event ->
            if (event.type == KeyEventType.KeyDown && event.key == Key.Escape) {
                onDismiss()
                true
            } else {
                false
            }
        }.fillMaxWidth().padding(7.dp)
            .shadow(8.dp, RoundedCornerShape(7.dp))
            .background(tc.p, RoundedCornerShape(7.dp))
            .border(1.dp, tc.br, RoundedCornerShape(7.dp))
            .padding(9.dp)
            .testTag("$testTagPrefix-picker"),
        verticalArrangement = Arrangement.spacedBy(5.dp),
    ) {
        HighlightPaletteGrid(
            presetColors = HL_COLORS,
            customColors = customColors,
            selectedColor = color,
            paletteColumns = RECTANGLE_PALETTE_COLUMNS,
            onPaletteColumnsChange = {},
            testTagPrefix = testTagPrefix,
            onColorChange = onColorChange,
            onDeleteCustomColor = onDeleteCustomColor,
            onDeleteMenuOpenChange = { isOpen ->
                if (!isOpen) runCatching { popupFocusRequester.requestFocus() }
            },
            showModeSelector = false,
            adaptiveSwatches = true,
            title = "",
        )
        val customControlShape = RoundedCornerShape(5.dp)
        HoverBox(
            modifier = Modifier.fillMaxWidth().clip(customControlShape)
                .border(1.dp, if (customColorEditorExpanded) tc.ac else tc.br, customControlShape)
                .testTag("$testTagPrefix-custom-toggle"),
            baseBg = tc.p2,
            hoverBg = tc.hv,
            forceHover = customColorEditorExpanded,
            onClick = { onCustomColorEditorExpandedChange(!customColorEditorExpanded) },
        ) {
            Row(
                Modifier.fillMaxWidth().padding(horizontal = 7.dp, vertical = 3.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                AppText("Custom color", color = tc.ts, fontSize = 10.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
                AppText(if (customColorEditorExpanded) "▾" else "▸", color = tc.ts, fontSize = 10.sp)
            }
        }
        if (customColorEditorExpanded) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(1.dp)) {
                    ColorSlider("Hue", hsv[0], 0f..360f, testTagPrefix, "hue") { value -> updateHsv(0, value) }
                    ColorSlider("Saturation", hsv[1], 0f..1f, testTagPrefix, "saturation") { value -> updateHsv(1, value) }
                    ColorSlider("Value", hsv[2], 0f..1f, testTagPrefix, "value") { value -> updateHsv(2, value) }
                    ColorSlider("Alpha", color.alpha, 0f..1f, testTagPrefix, "alpha") { value ->
                        val nextColor = color.copy(alpha = value)
                        preserveHsvForNextColor = nextColor != color
                        onColorChange(nextColor)
                    }
                }
                Box(
                    Modifier.size(58.dp, 42.dp).clip(RoundedCornerShape(5.dp))
                        .checkerboard()
                        .border(1.dp, tc.br, RoundedCornerShape(5.dp)).testTag("$testTagPrefix-preview"),
                    contentAlignment = Alignment.Center,
                ) {
                    Box(Modifier.fillMaxSize().background(color))
                    AppText("Aa", color = if (color.luminance() > .45f) Color.Black else Color.White, fontSize = 17.sp, fontWeight = FontWeight.Bold)
                }
            }
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(5.dp)) {
                val hexHint = "#RRGGBB or #AARRGGBB"
                InlineField(
                    hexDraft,
                    { value ->
                        hexDraft = value
                    },
                    hexHint,
                    Modifier.weight(1f).testTag("$testTagPrefix-hex"),
                    onSubmit = {
                        parsedHex?.let {
                            hsv = rgbToHsv(it).toList()
                            preserveHsvForNextColor = false
                            onColorChange(it)
                            hexDraft = highlightColorHex(it)
                        }
                    },
                    onCancel = onDismiss,
                )
                val alreadySaved = parsedHex != null && ((HL_COLORS + customColors).any { it == parsedHex })
                AppButton(
                    "Apply",
                    onClick = {
                        parsedHex?.let {
                            hsv = rgbToHsv(it).toList()
                            preserveHsvForNextColor = false
                            onColorChange(it)
                            hexDraft = highlightColorHex(it)
                        }
                    },
                    variant = ButtonVariant.Ghost,
                    enabled = parsedHex != null && parsedHex != color,
                    modifier = Modifier.testTag("$testTagPrefix-apply"),
                )
                AppButton(
                    "Save color",
                    onClick = { parsedHex?.let(onSaveCustomColor) },
                    variant = ButtonVariant.Ghost,
                    enabled = parsedHex != null && !alreadySaved && customColors.size < MAX_CUSTOM_HIGHLIGHT_COLORS,
                    modifier = Modifier.testTag("$testTagPrefix-save"),
                )
            }
            if (customColors.size >= MAX_CUSTOM_HIGHLIGHT_COLORS) {
                AppText(
                    "$MAX_CUSTOM_HIGHLIGHT_COLORS custom colors maximum",
                    color = tc.td,
                    fontSize = 9.sp,
                    modifier = Modifier.testTag("$testTagPrefix-custom-color-limit"),
                )
            }
            if (parsedHex == null) {
                AppText(
                    "Enter #RRGGBB or #AARRGGBB",
                    color = DANGER_RED,
                    fontSize = 9.sp,
                    modifier = Modifier.testTag("$testTagPrefix-hex-error"),
                )
            }
        }
    }
}

@Composable
internal fun HighlightPaletteGrid(
    presetColors: List<Color>,
    customColors: List<Color>,
    selectedColor: Color,
    paletteColumns: Int,
    onPaletteColumnsChange: (Int) -> Unit,
    testTagPrefix: String,
    onColorChange: (Color) -> Unit,
    onDeleteCustomColor: (Color) -> Unit = {},
    onDeleteMenuOpenChange: (Boolean) -> Unit = {},
    showModeSelector: Boolean = true,
    adaptiveSwatches: Boolean = false,
    modifier: Modifier = Modifier,
    title: String = "Colors",
    expandedHeight: androidx.compose.ui.unit.Dp = 300.dp,
) {
    val colors = remember(presetColors, customColors) {
        val customSet = customColors.toSet()
        presetColors.map { PaletteColor(it, it in customSet) } +
            customColors.filterNot { it in presetColors }.map { PaletteColor(it, true) }
    }
    val columns = if (paletteColumns >= 10) RECTANGLE_PALETTE_COLUMNS else PAGED_PALETTE_COLUMNS
    val pageSize = if (columns == RECTANGLE_PALETTE_COLUMNS) RECTANGLE_PALETTE_PAGE_SIZE else PAGED_PALETTE_PAGE_SIZE
    val pageCount = ((colors.size + pageSize - 1) / pageSize).coerceAtLeast(1)

    fun selectedPage(): Int = colors.indexOfFirst { it.color == selectedColor }
        .takeIf { it >= 0 }?.div(pageSize) ?: 0
    var page by remember(testTagPrefix, columns) { mutableIntStateOf(selectedPage()) }
    LaunchedEffect(pageCount, pageSize) { page = page.coerceIn(0, pageCount - 1) }
    Column(modifier, verticalArrangement = Arrangement.spacedBy(3.dp)) {
        if (title.isNotBlank()) {
            AppText(title, color = tc().ts, fontSize = 10.sp, fontWeight = FontWeight.SemiBold)
        }
        if (showModeSelector) {
            Row(
                Modifier.fillMaxWidth().border(0.5.dp, tc().br, RoundedCornerShape(5.dp)).clip(RoundedCornerShape(5.dp)),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                PaletteModeOption(
                    label = "5 × 5",
                    selected = paletteColumns < 10,
                    testTag = "$testTagPrefix-mode-pages",
                    modifier = Modifier.weight(1f),
                ) {
                    onPaletteColumnsChange(5)
                    page = selectedPage()
                }
                Box(Modifier.width(0.5.dp).height(18.dp).background(tc().br))
                PaletteModeOption(
                    label = "10 cols",
                    selected = paletteColumns >= 10,
                    testTag = "$testTagPrefix-mode-rectangle",
                    modifier = Modifier.weight(1f),
                    contentDescription = "10 columns",
                ) {
                    onPaletteColumnsChange(10)
                    page = selectedPage()
                }
            }
        }
        BoxWithConstraints(Modifier.fillMaxWidth().heightIn(max = expandedHeight).verticalScroll(rememberScrollState())) {
            val gap = 3.dp
            val swatchSize = if (adaptiveSwatches) {
                minOf(
                    ((maxWidth - gap * (columns - 1)) / columns).coerceIn(14.dp, 28.dp),
                    (expandedHeight - gap * ((pageSize / columns) - 1)) / (pageSize / columns),
                )
            } else {
                14.dp
            }
            val pageColors = colors.drop(page * pageSize).take(pageSize)
            Column(
                Modifier.fillMaxWidth().testTag("$testTagPrefix-palette"),
                verticalArrangement = Arrangement.spacedBy(gap),
            ) {
                repeat(pageSize / columns) { rowIndex ->
                    Row(
                        Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(gap, Alignment.CenterHorizontally),
                    ) {
                        repeat(columns) { columnIndex ->
                            val index = rowIndex * columns + columnIndex
                            val entry = pageColors.getOrNull(index)
                            if (entry == null) {
                                Spacer(Modifier.size(swatchSize).testTag("$testTagPrefix-empty-slot"))
                            } else {
                                PaletteSwatch(
                                    entry.color,
                                    page * pageSize + index,
                                    selected = entry.color == selectedColor,
                                    isCustom = entry.isCustom,
                                    size = swatchSize,
                                    prefix = testTagPrefix,
                                    onDelete = onDeleteCustomColor,
                                    onDeleteMenuOpenChange = onDeleteMenuOpenChange,
                                    onClick = { onColorChange(entry.color) },
                                )
                            }
                        }
                    }
                }
            }
        }
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.Center) {
            SquareIconButton(
                "←",
                fontSize = 11.sp,
                onClick = { page = (page - 1).coerceAtLeast(0) },
                enabled = page > 0,
                modifier = Modifier.semantics { contentDescription = "Previous page" }.testTag("$testTagPrefix-prev-page"),
                size = 18.dp,
            )
            AppText(
                "Page ${page + 1} of $pageCount",
                color = tc().td,
                fontSize = 9.sp,
                modifier = Modifier.padding(horizontal = 4.dp).testTag("$testTagPrefix-page-label"),
            )
            SquareIconButton(
                "→",
                fontSize = 11.sp,
                onClick = { page = (page + 1).coerceAtMost(pageCount - 1) },
                enabled = page + 1 < pageCount,
                modifier = Modifier.semantics { contentDescription = "Next page" }.testTag("$testTagPrefix-next-page"),
                size = 18.dp,
            )
        }
    }
}

private data class PaletteColor(val color: Color, val isCustom: Boolean)

@Composable
@OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
private fun ColorSlider(label: String, value: Float, range: ClosedFloatingPointRange<Float>, prefix: String, suffix: String, onChange: (Float) -> Unit) {
    val tc = tc()
    val colors = SliderDefaults.colors(thumbColor = tc.ac, activeTrackColor = tc.ac, inactiveTrackColor = tc.br)
    val interactionSource = remember(prefix, suffix) { MutableInteractionSource() }
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(5.dp)) {
        AppText(label, color = tc.td, fontSize = 9.sp, modifier = Modifier.width(56.dp))
        CompositionLocalProvider(LocalMinimumInteractiveComponentSize provides 24.dp) {
            Slider(
                value = value.coerceIn(range.start, range.endInclusive),
                onValueChange = onChange,
                valueRange = range,
                modifier = Modifier.weight(1f).height(24.dp).testTag("$prefix-$suffix"),
                colors = colors,
                interactionSource = interactionSource,
                thumb = {
                    SliderDefaults.Thumb(
                        interactionSource = interactionSource,
                        colors = colors,
                        enabled = true,
                        thumbSize = DpSize(12.dp, 12.dp),
                    )
                },
                track = { sliderState ->
                    SliderDefaults.Track(
                        sliderState = sliderState,
                        modifier = Modifier.height(4.dp),
                        enabled = true,
                        colors = colors,
                        drawStopIndicator = null,
                        thumbTrackGapSize = 0.dp,
                        trackInsideCornerSize = 2.dp,
                    )
                },
            )
        }
    }
}

@Composable
private fun PaletteModeOption(
    label: String,
    selected: Boolean,
    testTag: String,
    modifier: Modifier = Modifier,
    contentDescription: String = label,
    onClick: () -> Unit,
) {
    val tc = tc()
    Box(
        modifier.height(20.dp).clip(RoundedCornerShape(5.dp))
            .background(if (selected) tc.abg else Color.Transparent)
            .clickable(onClick = onClick)
            .semantics { this.contentDescription = contentDescription }
            .testTag(testTag),
        contentAlignment = Alignment.Center,
    ) {
        AppText(label, color = if (selected) tc.ac else tc.ts, fontSize = 9.sp, modifier = Modifier.padding(horizontal = 3.dp))
    }
}

@OptIn(ExperimentalFoundationApi::class, androidx.compose.ui.ExperimentalComposeUiApi::class)
@Composable
private fun PaletteSwatch(
    color: Color,
    index: Int,
    selected: Boolean,
    isCustom: Boolean,
    size: androidx.compose.ui.unit.Dp,
    prefix: String,
    onDelete: (Color) -> Unit,
    onDeleteMenuOpenChange: (Boolean) -> Unit,
    onClick: () -> Unit,
) {
    val tc = tc()
    val density = androidx.compose.ui.platform.LocalDensity.current
    var deleteMenuOpen by remember(prefix, color) { mutableStateOf(false) }
    DisposableEffect(deleteMenuOpen) {
        onDispose {
            if (deleteMenuOpen) onDeleteMenuOpenChange(false)
        }
    }
    val description = when {
        isCustom && color in HL_COLORS -> "Preset color with saved custom copy ${highlightColorHex(color)}; right-click to remove saved copy"
        isCustom -> "Custom color ${highlightColorHex(color)}; right-click to delete"
        else -> "Palette color ${highlightColorHex(color)}"
    }
    Box {
        val swatch = @Composable {
            ColorSwatch(
                color,
                selected,
                modifier = Modifier.clip(CORNER_SM).checkerboard()
                    .onPointerEvent(PointerEventType.Press) { event ->
                        if (isCustom && event.buttons.isSecondaryPressed) {
                            deleteMenuOpen = true
                            onDeleteMenuOpenChange(true)
                        }
                    }
                    .semantics {
                        contentDescription = description
                        role = Role.Button
                    }
                    .testTag("$prefix-swatch-$index"),
                size = size,
                onClick = onClick,
            )
        }
        if (isCustom) {
            TooltipArea(tooltip = { ToolbarTooltip("Custom color · right-click to delete") }) { swatch() }
        } else {
            swatch()
        }
        if (isCustom && deleteMenuOpen) {
            Popup(
                alignment = Alignment.TopEnd,
                offset = IntOffset(0, with(density) { 16.dp.roundToPx() }),
                onDismissRequest = {
                    deleteMenuOpen = false
                    onDeleteMenuOpenChange(false)
                },
                properties = PopupProperties(focusable = true),
            ) {
                HoverBox(
                    modifier = Modifier.width(132.dp).clip(RoundedCornerShape(6.dp))
                        .shadow(8.dp, RoundedCornerShape(6.dp))
                        .border(1.dp, tc.br, RoundedCornerShape(6.dp))
                        .testTag("$prefix-delete-menu"),
                    baseBg = tc.p,
                    hoverBg = tc.hv,
                    onClick = {
                        deleteMenuOpen = false
                        onDeleteMenuOpenChange(false)
                        onDelete(color)
                    },
                ) {
                    AppText("Delete color", color = tc.tx, fontSize = 11.sp, modifier = Modifier.padding(horizontal = 10.dp, vertical = 7.dp))
                }
            }
        }
    }
}

@Suppress("MagicNumber") // Cell size and checker colors are visual texture data.
private fun Modifier.checkerboard(): Modifier = drawBehind {
    val cell = 5.dp.toPx()
    var y = 0f
    var row = 0
    while (y < size.height) {
        var x = 0f
        var column = 0
        while (x < size.width) {
            if ((row + column) % 2 == 0) drawRect(Color(0xFFD8D8D8), androidx.compose.ui.geometry.Offset(x, y), androidx.compose.ui.geometry.Size(cell, cell))
            else drawRect(Color(0xFFF6F6F6), androidx.compose.ui.geometry.Offset(x, y), androidx.compose.ui.geometry.Size(cell, cell))
            x += cell
            column++
        }
        y += cell
        row++
    }
}

@Suppress("MagicNumber") // HSV sector constants are part of the standard color conversion formula.
private fun rgbToHsv(color: Color): FloatArray {
    val r = color.red
    val g = color.green
    val b = color.blue
    val max = max(r, max(g, b))
    val min = min(r, min(g, b))
    val delta = max - min
    val hue = when {
        delta == 0f -> 0f
        max == r -> 60f * (((g - b) / delta) % 6f)
        max == g -> 60f * (((b - r) / delta) + 2f)
        else -> 60f * (((r - g) / delta) + 4f)
    }.let { if (it < 0f) it + 360f else it }
    val saturation = if (max == 0f) 0f else delta / max
    return floatArrayOf(hue, saturation, max)
}
