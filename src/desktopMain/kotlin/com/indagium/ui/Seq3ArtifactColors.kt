package com.indagium.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
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
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupProperties
import com.indagium.diagram3.Seq3ArtifactStyle

/** Compact action-row affordance for opening the shared artifact color controls. */
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
) {
    val focusRequester = LocalSeq3FocusRequester.current
    val tc = tc()
    val popupFocusRequester = remember { FocusRequester() }
    var expanded by remember { mutableStateOf(false) }
    LaunchedEffect(expanded) {
        if (expanded) runCatching { popupFocusRequester.requestFocus() }
    }
    Box(modifier.testTag("seq3-artifact-color-action")) {
        SquareIconButton(
            text = "◉",
            fontSize = 10.sp,
            onClick = { expanded = true },
            modifier = Modifier.testTag("seq3-artifact-color-action-button"),
            size = 16.dp,
            enabled = enabled,
            tooltip = "Artifact colors",
        )
        if (expanded) {
            Popup(
                alignment = Alignment.TopEnd,
                offset = IntOffset(0, with(LocalDensity.current) { 19.dp.roundToPx() }),
                onDismissRequest = {
                    expanded = false
                    reclaimSeq3Focus(focusRequester)
                },
                properties = PopupProperties(focusable = true),
            ) {
                Column(
                    Modifier.width(220.dp).shadow(6.dp, RoundedCornerShape(6.dp))
                        .background(tc.p, RoundedCornerShape(6.dp))
                        .border(1.dp, tc.br, RoundedCornerShape(6.dp))
                        .padding(6.dp)
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
                    verticalArrangement = Arrangement.spacedBy(1.dp),
                ) {
                    CompositionLocalProvider(LocalSeq3FocusRequester provides popupFocusRequester) {
                        Seq3ArtifactColorControls(
                            state = state,
                            style = style,
                            onStyleChange = onStyleChange,
                            fillDefault = fillDefault,
                            borderDefault = borderDefault,
                            textDefault = textDefault,
                        )
                    }
                }
            }
        }
    }
}

private val ARTIFACT_COLOR_FIELDS = listOf(
    ArtifactColorField("Fill", "fill"),
    ArtifactColorField("Border", "border"),
    ArtifactColorField("Text", "text"),
)

private data class ArtifactColorField(val label: String, val key: String)

/**
 * Single-row color controls shared by the artifact panel and canvas popovers. Each field shows
 * its role, current swatch, and a compact reset icon; the color palette and custom editor open
 * only when that field's swatch is clicked. Null style fields inherit the passed document-theme
 * defaults.
 *
 * Applying a picked color emits one style update when its picker closes. Saving/deleting a custom
 * color remains an explicit action inside the existing highlighter picker; applying an artifact
 * color never adds it to that palette.
 */
@Composable
internal fun Seq3ArtifactColorControls(
    state: AppState,
    style: Seq3ArtifactStyle?,
    onStyleChange: (Seq3ArtifactStyle?) -> Unit,
    fillDefault: Color,
    borderDefault: Color,
    textDefault: Color,
    modifier: Modifier = Modifier,
) {
    val latestStyle by rememberUpdatedState(style)
    val focusRequester = LocalSeq3FocusRequester.current
    val customColors = remember(state.settings.highlighterCustomColors) {
        customHighlightColors(state.settings.highlighterCustomColors)
    }
    val paletteColumns = state.settings.highlighterPaletteColumns

    Row(
        modifier = modifier.testTag("seq3-artifact-colors"),
        horizontalArrangement = Arrangement.spacedBy(2.dp, Alignment.Start),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        ARTIFACT_COLOR_FIELDS.forEach { field ->
            val default = when (field.key) {
                "fill" -> fillDefault
                "border" -> borderDefault
                else -> textDefault
            }
            val override = when (field.key) {
                "fill" -> style?.fillArgb
                "border" -> style?.borderArgb
                else -> style?.textArgb
            }
            val selected = override?.let(::Color) ?: default
            var pickerOpen by remember(field.key) { mutableStateOf(false) }
            var stagedColor by remember(field.key) { mutableStateOf(selected) }
            var stagedDirty by remember(field.key) { mutableStateOf(false) }

            fun styleWithColor(color: Color): Seq3ArtifactStyle {
                val current = latestStyle ?: Seq3ArtifactStyle()
                val argb = color.toArgb()
                return when (field.key) {
                    "fill" -> current.copy(fillArgb = argb)
                    "border" -> current.copy(borderArgb = argb)
                    else -> current.copy(textArgb = argb)
                }
            }

            fun resetFieldToDefault() {
                val current = latestStyle
                val updated = when (field.key) {
                    "fill" -> current?.copy(fillArgb = null)
                    "border" -> current?.copy(borderArgb = null)
                    else -> current?.copy(textArgb = null)
                }
                onStyleChange(updated?.takeUnless { it == Seq3ArtifactStyle() })
                reclaimSeq3Focus(focusRequester)
            }

            Row(verticalAlignment = Alignment.CenterVertically) {
                AppText(
                    text = field.label,
                    color = tc().td,
                    fontSize = 9.sp,
                    maxLines = 1,
                    modifier = Modifier.testTag("seq3-artifact-${field.key}-label"),
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
                    paletteColumns = paletteColumns,
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
                                val next = styleWithColor(stagedColor)
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
                )
                val hasFieldOverride = override != null
                val resetTooltip = "Use diagram default color"
                SquareIconButton(
                    text = "↺",
                    fontSize = 10.sp,
                    onClick = ::resetFieldToDefault,
                    modifier = Modifier
                        .testTag("seq3-artifact-${field.key}-reset")
                        .semantics { contentDescription = resetTooltip },
                    size = 14.dp,
                    enabled = hasFieldOverride,
                    tooltip = resetTooltip,
                )
            }
        }

        Spacer(Modifier.width(1.dp))
        SquareIconButton(
            text = "↺",
            fontSize = 10.sp,
            onClick = {
                onStyleChange(null)
                reclaimSeq3Focus(focusRequester)
            },
            modifier = Modifier
                .testTag("seq3-artifact-colors-reset")
                .semantics { contentDescription = "Use diagram default colors" },
            size = 14.dp,
            enabled = style != null,
            tooltip = "Use diagram default colors",
        )
    }
}

private fun reclaimSeq3Focus(requester: FocusRequester?) {
    if (requester != null) runCatching { requester.requestFocus() }
}
