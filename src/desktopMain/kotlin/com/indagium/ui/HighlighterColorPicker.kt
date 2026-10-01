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
    val preferredWidth = when {
        editorExpanded && paletteColumns >= 10 -> 360.dp
        editorExpanded -> 286.dp
        paletteColumns >= 10 -> 208.dp
        else -> 136.dp
    }
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
                    paletteColumns = paletteColumns,
                    onPaletteColumnsChange = onPaletteColumnsChange,
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
    paletteColumns: Int,
    onPaletteColumnsChange: (Int) -> Unit,
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
    val palette = remember(customColors) { (HL_COLORS + customColors).distinct() }
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
            palette = palette,
            selectedColor = color,
            paletteColumns = paletteColumns,
            onPaletteColumnsChange = onPaletteColumnsChange,
            testTagPrefix = testTagPrefix,
            onColorChange = onColorChange,
            title = "",
        )
        if (customColors.isNotEmpty()) {
            SavedHighlightColors(customColors, color, testTagPrefix, onColorChange, onDeleteCustomColor)
        }
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
                val candidateHex = parsedHex?.let(::highlightColorHex)
                val alreadySaved = candidateHex != null && customColors.any {
                    highlightColorHex(it).equals(candidateHex, ignoreCase = true)
                }
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
                    enabled = parsedHex != null && !alreadySaved,
                    modifier = Modifier.testTag("$testTagPrefix-save"),
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
    palette: List<Color>,
    selectedColor: Color,
    paletteColumns: Int,
    onPaletteColumnsChange: (Int) -> Unit,
    testTagPrefix: String,
    onColorChange: (Color) -> Unit,
    modifier: Modifier = Modifier,
    title: String = "Colors",
    expandedHeight: androidx.compose.ui.unit.Dp = 300.dp,
) {
    var page by remember(testTagPrefix) { mutableIntStateOf(0) }
    val pageCount = ((palette.size + 24) / 25).coerceAtLeast(1)
    LaunchedEffect(pageCount, paletteColumns) {
        page = page.coerceIn(0, (pageCount - 1).coerceAtLeast(0))
    }
    Column(modifier, verticalArrangement = Arrangement.spacedBy(3.dp)) {
        if (title.isNotBlank()) {
            AppText(title, color = tc().ts, fontSize = 10.sp, fontWeight = FontWeight.SemiBold)
        }
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
                page = 0
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
                page = 0
            }
        }
        if (paletteColumns >= 10) {
            Column(
                Modifier.fillMaxWidth().heightIn(max = expandedHeight).verticalScroll(rememberScrollState())
                    .testTag("$testTagPrefix-palette"),
                verticalArrangement = Arrangement.spacedBy(3.dp),
            ) {
                palette.chunked(10).forEachIndexed { rowIndex, row ->
                    Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(3.dp)) {
                        row.forEachIndexed { columnIndex, swatch ->
                            val index = rowIndex * 10 + columnIndex
                            PaletteSwatch(swatch, index, swatch == selectedColor, 14.dp, testTagPrefix) {
                                onColorChange(swatch)
                            }
                        }
                    }
                }
            }
        } else {
            val pageColors = palette.drop(page * 25).take(25)
            Column(
                Modifier.fillMaxWidth().testTag("$testTagPrefix-palette"),
                verticalArrangement = Arrangement.spacedBy(3.dp),
            ) {
                pageColors.chunked(5).forEachIndexed { rowIndex, row ->
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(3.dp, Alignment.CenterHorizontally)) {
                        row.forEachIndexed { columnIndex, swatch ->
                            val index = page * 25 + rowIndex * 5 + columnIndex
                            PaletteSwatch(swatch, index, swatch == selectedColor, 14.dp, testTagPrefix) {
                                onColorChange(swatch)
                            }
                        }
                    }
                }
            }
            val tc = tc()
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.Center) {
                SquareIconButton(
                    "←",
                    fontSize = 11.sp,
                    onClick = { page = (page - 1).coerceAtLeast(0) },
                    enabled = page > 0,
                    modifier = Modifier.semantics { contentDescription = "Previous page" }.testTag("$testTagPrefix-prev-page"),
                    size = 18.dp,
                )
                AppText("Page ${page + 1} of $pageCount", color = tc.td, fontSize = 9.sp, modifier = Modifier.padding(horizontal = 4.dp))
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
}

@Composable
private fun SavedHighlightColors(
    colors: List<Color>,
    selectedColor: Color,
    prefix: String,
    onColorChange: (Color) -> Unit,
    onDelete: (Color) -> Unit,
) {
    val tc = tc()
    Column(
        Modifier.fillMaxWidth().heightIn(max = 52.dp).verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        AppText("Saved colors", color = tc.td, fontSize = 9.sp)
        FlowRow(horizontalArrangement = Arrangement.spacedBy(3.dp), verticalArrangement = Arrangement.spacedBy(3.dp)) {
            colors.forEachIndexed { index, saved ->
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(1.dp)) {
                    PaletteSwatch(saved, -1, saved == selectedColor, 14.dp, "$prefix-custom-$index") {
                        onColorChange(saved)
                    }
                    AppText(
                        "×",
                        color = tc.td,
                        fontSize = 10.sp,
                        modifier = Modifier.clickable { onDelete(saved) }
                            .testTag("$prefix-delete-custom"),
                    )
                }
            }
        }
    }
}

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

@Composable
private fun PaletteSwatch(color: Color, index: Int, selected: Boolean, size: androidx.compose.ui.unit.Dp, prefix: String, onClick: () -> Unit) {
    ColorSwatch(
        color,
        selected,
        modifier = Modifier.clip(CORNER_SM).checkerboard()
            .semantics {
                contentDescription = "Palette color ${highlightColorHex(color)}"
                role = Role.Button
            }
            .testTag(if (index >= 0) "$prefix-swatch-$index" else "$prefix-swatch"),
        size = size,
        onClick = onClick,
    )
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
