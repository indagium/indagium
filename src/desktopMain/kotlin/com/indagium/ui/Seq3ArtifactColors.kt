@file:OptIn(
    androidx.compose.ui.ExperimentalComposeUiApi::class,
    androidx.compose.foundation.ExperimentalFoundationApi::class,
)

package com.indagium.ui

import androidx.compose.foundation.TooltipArea
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.DisableSelection
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.onPointerEvent
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntRect
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupPositionProvider
import androidx.compose.ui.window.PopupProperties
import com.indagium.diagram3.Seq3ArtifactStyle

/** Where the style popover opens: under its trigger, left edge aligned, nudged back inside the window
 *  (flipped above the trigger when there is no room below). Pure so the placement is unit-tested. */
internal fun seq3PopoverPosition(
    anchor: IntRect,
    popup: IntSize,
    window: IntSize,
    gapPx: Int,
    marginPx: Int = POPOVER_WINDOW_MARGIN_PX,
): IntOffset {
    val maxX = (window.width - popup.width - marginPx).coerceAtLeast(marginPx)
    val x = anchor.left.coerceIn(marginPx, maxX)
    val below = anchor.bottom + gapPx
    val y = if (below + popup.height + marginPx <= window.height) {
        below
    } else {
        (anchor.top - gapPx - popup.height).coerceAtLeast(marginPx)
    }
    return IntOffset(x, y)
}

private const val POPOVER_WINDOW_MARGIN_PX = 4
private val POPOVER_WIDTH = 260.dp
private val POPOVER_SHAPE = RoundedCornerShape(7.dp)
private val POPOVER_LABEL_WIDTH = 52.dp
private val POPOVER_ROW_HEIGHT = 28.dp

private class PopoverPositionProvider(private val gapPx: Int) : PopupPositionProvider {
    override fun calculatePosition(
        anchorBounds: IntRect,
        windowSize: IntSize,
        layoutDirection: LayoutDirection,
        popupContentSize: IntSize,
    ): IntOffset = seq3PopoverPosition(anchorBounds, popupContentSize, windowSize, gapPx)
}

/** The hex readout next to a colour row: "#RRGGBB" when opaque, "#AARRGGBB" when translucent. */
internal fun seq3ArtifactHexLabel(color: Color): String {
    val hex = highlightColorHex(color)
    return if (color.alpha >= OPAQUE_ALPHA) "#" + hex.substring(HEX_ALPHA_DIGITS + 1) else hex
}

private const val OPAQUE_ALPHA = 0.999f
private const val HEX_ALPHA_DIGITS = 2

/** A small accent-coloured text action ("Default", "Rename only this arrow"). [enabled] false greys
 *  it out and removes the click. */
@Composable
internal fun Seq3TextLink(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    fontSize: TextUnit = 11.sp,
) {
    val tc = tc()
    var hovered by remember { mutableStateOf(false) }
    Box(
        modifier
            .clip(CORNER_SM)
            .clickable(enabled = enabled, onClick = onClick)
            .onPointerEvent(PointerEventType.Enter) { hovered = true }
            .onPointerEvent(PointerEventType.Exit) { hovered = false }
            .padding(horizontal = 3.dp, vertical = 2.dp),
    ) {
        DisableSelection {
            AppText(
                text,
                color = if (enabled) tc.ac else tc.td.copy(alpha = 0.5f),
                fontSize = fontSize,
                textDecoration = if (hovered && enabled) TextDecoration.Underline else null,
            )
        }
    }
}

/** Compact action-row affordance for the artifact style popover: a swatch showing the current
 *  border colour (tooltip "Style"). Fragments also pass [showKindWord] to get the "Show kind word
 *  on canvas" checkbox; null hides it. */
@Composable
internal fun Seq3ArtifactColorAction(
    state: AppState,
    style: Seq3ArtifactStyle?,
    onStyleChange: (Seq3ArtifactStyle?) -> Unit,
    fillDefault: Color,
    borderDefault: Color,
    textDefault: Color,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    showKindWord: Boolean? = null,
    onShowKindWordChange: (Boolean) -> Unit = {},
) {
    val focusRequester = LocalSeq3FocusRequester.current
    val tc = tc()
    val popupFocusRequester = remember { FocusRequester() }
    var expanded by remember { mutableStateOf(false) }
    val gapPx = with(LocalDensity.current) { 4.dp.roundToPx() }
    val positionProvider = remember(gapPx) { PopoverPositionProvider(gapPx) }
    LaunchedEffect(expanded) {
        if (expanded) runCatching { popupFocusRequester.requestFocus() }
    }
    Box(modifier.testTag("seq3-artifact-color-action")) {
        TooltipArea(tooltip = { ToolbarTooltip("Style") }) {
            Box(Modifier.testTag("seq3-artifact-color-action-button")) {
                ColorPickerSwatch(
                    color = style?.borderArgb?.let(::Color) ?: borderDefault,
                    pickerOpen = expanded,
                    onClick = { if (enabled) expanded = true },
                    size = 12.dp,
                    backdrop = tc.p,
                    outline = tc.br,
                )
            }
        }
        if (expanded) {
            Popup(
                popupPositionProvider = positionProvider,
                onDismissRequest = {
                    expanded = false
                    reclaimSeq3Focus(focusRequester)
                },
                properties = PopupProperties(focusable = true),
            ) {
                Column(
                    Modifier.width(POPOVER_WIDTH).shadow(8.dp, POPOVER_SHAPE)
                        .background(tc.p, POPOVER_SHAPE)
                        .border(1.dp, tc.br, POPOVER_SHAPE)
                        .padding(vertical = 8.dp)
                        .focusRequester(popupFocusRequester)
                        .focusable()
                        .onPreviewKeyEvent { event ->
                            if (event.type == KeyEventType.KeyDown && event.key == Key.Escape) {
                                expanded = false
                                reclaimSeq3Focus(focusRequester)
                                true
                            } else {
                                false
                            }
                        }
                        .testTag("seq3-artifact-color-popover"),
                ) {
                    CompositionLocalProvider(LocalSeq3FocusRequester provides popupFocusRequester) {
                        Seq3ArtifactStylePopover(
                            state = state,
                            style = style,
                            onStyleChange = onStyleChange,
                            fillDefault = fillDefault,
                            borderDefault = borderDefault,
                            textDefault = textDefault,
                            showKindWord = showKindWord,
                            onShowKindWordChange = onShowKindWordChange,
                        )
                    }
                }
            }
        }
    }
}

private class ArtifactColorField(
    val label: String,
    val key: String,
    val read: (Seq3ArtifactStyle?) -> Int?,
    val write: (Seq3ArtifactStyle, Int?) -> Seq3ArtifactStyle,
)

private val ARTIFACT_COLOR_FIELDS = listOf(
    ArtifactColorField("Fill", "fill", { it?.fillArgb }, { current, argb -> current.copy(fillArgb = argb) }),
    ArtifactColorField("Border", "border", { it?.borderArgb }, { current, argb -> current.copy(borderArgb = argb) }),
    ArtifactColorField("Text", "text", { it?.textArgb }, { current, argb -> current.copy(textArgb = argb) }),
)

/**
 * The content of the artifact style popover, shared by the Artifacts tile and the canvas: one row
 * per colour (label, swatch, hex, and a "Default" link while that colour is overridden), a
 * "Reset all to diagram colours" footer link, and, for fragments, the kind-word checkbox. Null
 * style fields inherit the passed document-theme defaults.
 *
 * Applying a picked colour emits one style update when its picker closes. Saving/deleting a custom
 * colour remains an explicit action inside the existing highlighter picker; applying an artifact
 * colour never adds it to that palette.
 */
@Composable
internal fun Seq3ArtifactStylePopover(
    state: AppState,
    style: Seq3ArtifactStyle?,
    onStyleChange: (Seq3ArtifactStyle?) -> Unit,
    fillDefault: Color,
    borderDefault: Color,
    textDefault: Color,
    modifier: Modifier = Modifier,
    showKindWord: Boolean? = null,
    onShowKindWordChange: (Boolean) -> Unit = {},
) {
    val tc = tc()
    val focusRequester = LocalSeq3FocusRequester.current
    Column(modifier.fillMaxWidth().testTag("seq3-artifact-colors")) {
        ARTIFACT_COLOR_FIELDS.forEach { field ->
            val default = when (field.key) {
                "fill" -> fillDefault
                "border" -> borderDefault
                else -> textDefault
            }
            ArtifactColorRow(field, state, style, onStyleChange, default)
        }
        if (showKindWord != null) {
            Box(Modifier.fillMaxWidth().height(1.dp).background(tc.br))
            CheckRow(
                checked = showKindWord,
                onToggle = {
                    onShowKindWordChange(!showKindWord)
                    reclaimSeq3Focus(focusRequester)
                },
                modifier = Modifier.padding(horizontal = 0.dp).testTag("seq3-artifact-kind-word"),
            ) {
                AppText("Show kind word on canvas", color = tc.tx, fontSize = 11.sp)
            }
        }
        Box(Modifier.fillMaxWidth().height(1.dp).background(tc.br))
        Seq3TextLink(
            text = "Reset all to diagram colours",
            onClick = {
                onStyleChange(null)
                reclaimSeq3Focus(focusRequester)
            },
            enabled = style != null,
            modifier = Modifier.padding(start = 7.dp, top = 4.dp).testTag("seq3-artifact-colors-reset"),
        )
    }
}

@Composable
private fun ArtifactColorRow(
    field: ArtifactColorField,
    state: AppState,
    style: Seq3ArtifactStyle?,
    onStyleChange: (Seq3ArtifactStyle?) -> Unit,
    default: Color,
) {
    val tc = tc()
    val latestStyle by rememberUpdatedState(style)
    val focusRequester = LocalSeq3FocusRequester.current
    val customColors = remember(state.settings.highlighterCustomColors) {
        customHighlightColors(state.settings.highlighterCustomColors)
    }
    val override = field.read(style)
    val selected = override?.let(::Color) ?: default
    var pickerOpen by remember(field.key) { mutableStateOf(false) }
    var stagedColor by remember(field.key) { mutableStateOf(selected) }
    var stagedDirty by remember(field.key) { mutableStateOf(false) }

    Row(
        Modifier.fillMaxWidth().height(POPOVER_ROW_HEIGHT).padding(horizontal = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        AppText(
            text = field.label,
            color = tc.ts,
            fontSize = 11.sp,
            maxLines = 1,
            modifier = Modifier.width(POPOVER_LABEL_WIDTH).testTag("seq3-artifact-${field.key}-label"),
        )
        HighlighterColorPicker(
            color = if (pickerOpen) stagedColor else selected,
            onColorChange = { chosen ->
                stagedColor = chosen
                stagedDirty = chosen != selected
            },
            customColors = customColors,
            onSaveCustomColor = { color ->
                val hex = highlightColorHex(color)
                state.updateSettings { settings ->
                    val colors = customHighlightColors(settings.highlighterCustomColors)
                    if (color !in colors && colors.size < MAX_CUSTOM_HIGHLIGHT_COLORS) {
                        settings.copy(
                            highlighterCustomColors =
                                (colors.map(::highlightColorHex) + hex).take(MAX_CUSTOM_HIGHLIGHT_COLORS),
                        )
                    } else {
                        settings
                    }
                }
            },
            onDeleteCustomColor = { color ->
                state.updateSettings { settings ->
                    settings.copy(
                        highlighterCustomColors = settings.highlighterCustomColors.filterNot {
                            parseHighlightHex(it) == color
                        },
                    )
                }
            },
            paletteColumns = state.settings.highlighterPaletteColumns,
            onPaletteColumnsChange = { columns ->
                state.updateSettings { settings ->
                    settings.copy(highlighterPaletteColumns = if (columns >= 10) 10 else 5)
                }
            },
            pickerOpen = pickerOpen,
            onPickerOpenChange = { open ->
                if (open) {
                    stagedColor = selected
                    stagedDirty = false
                    pickerOpen = true
                } else {
                    val shouldCommit = pickerOpen && stagedDirty
                    pickerOpen = false
                    if (shouldCommit) {
                        val next = field.write(latestStyle ?: Seq3ArtifactStyle(), stagedColor.toArgb())
                        if (next != latestStyle) onStyleChange(next)
                    }
                    reclaimSeq3Focus(focusRequester)
                }
            },
            testTagPrefix = "seq3-artifact-${field.key}",
            customColorEditorExpanded = state.settings.highlighterCustomColorEditorExpanded,
            onCustomColorEditorExpandedChange = { expanded ->
                state.updateSettings { it.copy(highlighterCustomColorEditorExpanded = expanded) }
            },
            swatchBackdrop = tc.p,
            swatchOutline = tc.br,
        )
        AppText(
            text = seq3ArtifactHexLabel(selected),
            color = tc.td,
            fontSize = 10.sp,
            fontFamily = MONO,
            maxLines = 1,
            modifier = Modifier.weight(1f).padding(start = 6.dp).testTag("seq3-artifact-${field.key}-hex"),
        )
        if (override != null) {
            Seq3TextLink(
                text = "Default",
                onClick = {
                    val reset = latestStyle?.let { field.write(it, null) }
                    onStyleChange(reset?.takeUnless { it == Seq3ArtifactStyle() })
                    reclaimSeq3Focus(focusRequester)
                },
                modifier = Modifier.testTag("seq3-artifact-${field.key}-reset"),
            )
        }
    }
}

private fun reclaimSeq3Focus(requester: FocusRequester?) {
    if (requester != null) runCatching { requester.requestFocus() }
}
