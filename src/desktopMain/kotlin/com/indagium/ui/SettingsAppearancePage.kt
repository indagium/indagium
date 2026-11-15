package com.indagium.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import com.indagium.model.LinuxFilePickerMode
import com.indagium.model.MAX_INTERFACE_SCALE_PERCENT
import com.indagium.model.MIN_INTERFACE_SCALE_PERCENT
import com.indagium.model.ShapeStyle
import com.indagium.ui.holiday.ThemeSettingsBlock

// Settings › Appearance: the theme, the fonts and scales, and the corners. The tab strip and toolbar moved to Tabs & toolbar. The font
// and corner pieces are separate composables (AppearanceSystemFontSelectors, ShapeStyleSetting, ToolbarShapeSetting) because tests and the
// setup assistant render them on their own; each emits rows for a SettingsGroup, or stands alone as a plain row.

/** Test tag of the Tiles corner picker. */
internal const val SHAPE_STYLE_CONTROL_TAG = "shape-style"

/** Test tag of the Toolbar corner dropdown. */
internal const val TOOLBAR_SHAPE_CONTROL_TAG = "toolbar-shape"

private const val LOG_FONT_MONO_KEY = "monospace"
private const val LOG_FONT_PROPORTIONAL_KEY = "proportional"
private val FONT_DROPDOWN_WIDTH = SETTINGS_FIELD_SHORT
private val SHAPE_SAMPLE_WIDTH = 14.dp
private val SHAPE_SAMPLE_HEIGHT = 9.dp
private const val SHAPE_DASH_DP = 2f
private const val SAMPLE_RADIUS_SOFT = 1.5f
private const val SAMPLE_RADIUS_ROUNDED = 3f
private const val SAMPLE_RADIUS_ROUND = 4.5f

/** The descriptions of the Appearance rows, shared with their search entries. */
internal const val INTERFACE_FONT_HINT = "Controls and interface text."
internal const val LOG_FONT_HINT = "Log rows and highlighter previews. The monospace and proportional defaults sit at the top of the list."
internal const val LOG_FONT_SIZE_HINT = "Text size of the log rows, 10 to 24 px."
internal const val INTERFACE_SCALE_HINT = "Scales the whole interface, not just the log."
internal const val SHAPE_STYLE_HINT = "How round tiles are. Theme default keeps each theme's own shapes."
internal const val TOOLBAR_SHAPE_HINT = "Corners of the toolbar's button groups and the Layout menu button."

internal fun shapeStyleLabel(style: ShapeStyle): String = when (style) {
    ShapeStyle.THEME -> "Theme default"
    ShapeStyle.SQUARE -> "Square"
    ShapeStyle.SOFT -> "Soft"
    ShapeStyle.ROUNDED -> "Rounded"
    ShapeStyle.ROUND -> "Round"
}

/** The short label of a segment of the Tiles picker (the full [shapeStyleLabel] is the segment's accessibility label). */
private fun shapeStyleTileLabel(style: ShapeStyle): String = if (style == ShapeStyle.THEME) "Theme" else shapeStyleLabel(style)

/** The radius, in dp, of the 14x9 sample drawn on a segment of the picker: a picture of the corner, not the real tile radius. */
private fun shapeSampleRadiusDp(style: ShapeStyle): Float = when (style) {
    ShapeStyle.THEME -> SAMPLE_RADIUS_ROUNDED
    ShapeStyle.SQUARE -> 0f
    ShapeStyle.SOFT -> SAMPLE_RADIUS_SOFT
    ShapeStyle.ROUNDED -> SAMPLE_RADIUS_ROUNDED
    ShapeStyle.ROUND -> SAMPLE_RADIUS_ROUND
}

/**
 * The Log font choice: the monospace and proportional defaults on top, then the installed families. Picking a default clears the
 * family and sets which kind of default log text is used; picking a family leaves that kind alone, as the fallback if the family is
 * ever missing. Shown by Settings › Appearance and by the setup assistant's Look step.
 */
@Composable
internal fun LogFontDropdown(state: AppState, modifier: Modifier = Modifier, onInteraction: () -> Unit = {}) {
    val mono = state.settings.fontMono
    SearchableFontDropdown(
        label = null,
        selectedFamily = state.settings.logFontFamily,
        defaultLabel = if (mono) "Monospace · default" else "Proportional · default",
        fallbackFamily = if (mono) FontFamily.Monospace else FontFamily.Default,
        onSelect = { family -> state.updateSettings { it.copy(logFontFamily = family) } },
        modifier = modifier,
        testTagPrefix = "settings-log-font",
        showPreview = false,
        pinnedDefaults = listOf(
            PinnedFontDefault(LOG_FONT_MONO_KEY, "Monospace · default", FontFamily.Monospace),
            PinnedFontDefault(LOG_FONT_PROPORTIONAL_KEY, "Proportional · default", FontFamily.Default),
        ),
        selectedPinnedKey = if (mono) LOG_FONT_MONO_KEY else LOG_FONT_PROPORTIONAL_KEY,
        onSelectPinned = { pinned ->
            state.updateSettings { it.copy(logFontFamily = null, fontMono = pinned.key == LOG_FONT_MONO_KEY) }
        },
        triggerInFont = true,
        onInteraction = onInteraction,
    )
}

/**
 * The font, size and scale controls of the setup assistant's Look step, in one wrapping row; Settings › Appearance has the same
 * settings as rows. Controls take their natural width and wrap onto a second line only when the window is too narrow for all of them.
 */
@Composable
internal fun AppearanceBasicsRow(state: AppState, onInteraction: () -> Unit = {}) {
    SettingsControlRow {
        CompactSetting("Log font") { LogFontDropdown(state, Modifier.width(FONT_DROPDOWN_WIDTH), onInteraction) }
        CompactSetting("Log font size", horizontalAlignment = Alignment.Start) { LogFontSizeStepper(state) }
        CompactSetting("Interface scale", horizontalAlignment = Alignment.Start) { InterfaceScaleStepper(state) }
        ToolbarLabelsSetting(state)
    }
}

@Composable
private fun LogFontSizeStepper(state: AppState) {
    SettingsStepper(
        options = (10..24).toList(),
        value = state.settings.fontSize,
        onChange = { v -> state.updateSettings { it.copy(fontSize = v) } },
        format = { "$it px" },
    )
}

@Composable
private fun InterfaceScaleStepper(state: AppState) {
    SettingsStepper(
        options = (MIN_INTERFACE_SCALE_PERCENT..MAX_INTERFACE_SCALE_PERCENT step 10).toList(),
        value = state.settings.interfaceScalePercent,
        onChange = { v -> state.updateSettings { it.copy(interfaceScalePercent = v) } },
        format = { "$it%" },
    )
}

/** The main toolbar's text-or-icons-only choice as a compact cell; the setup assistant shows it inside [AppearanceBasicsRow]. */
@Composable
internal fun ToolbarLabelsSetting(state: AppState) {
    CompactSettingWithTooltip(
        label = "Toolbar labels",
        tooltip = TOOLBAR_LABELS_HINT,
        horizontalAlignment = Alignment.Start,
    ) {
        SettingsSegmented(
            options = TOOLBAR_LABEL_OPTIONS,
            selectedIndices = setOf(if (state.settings.toolbarIconOnlyButtons) 1 else 0),
            onToggle = { idx -> state.updateSettings { it.copy(toolbarIconOnlyButtons = idx == 1) } },
        )
    }
}

/** The Interface font and Log font rows stacked in one column, for a host that is not a [SettingsGroup] (tests, the assistant). */
@Composable
internal fun AppearanceSystemFontSelectors(state: AppState, onInteraction: () -> Unit = {}) {
    Column {
        InterfaceFontRow(state, onInteraction)
        LogFontRow(state, onInteraction)
    }
}

@Composable
private fun InterfaceFontRow(state: AppState, onInteraction: () -> Unit) {
    SettingsRow("Interface font", INTERFACE_FONT_HINT) {
        SearchableFontDropdown(
            label = null,
            selectedFamily = state.settings.interfaceFontFamily,
            defaultLabel = "System default",
            fallbackFamily = FontFamily.Default,
            onSelect = { family -> state.updateSettings { it.copy(interfaceFontFamily = family) } },
            modifier = Modifier.width(FONT_DROPDOWN_WIDTH),
            testTagPrefix = "settings-interface-font",
            showPreview = false,
            triggerInFont = true,
            onInteraction = onInteraction,
        )
    }
}

@Composable
private fun LogFontRow(state: AppState, onInteraction: () -> Unit) {
    SettingsRow("Log font", LOG_FONT_HINT) {
        LogFontDropdown(state, Modifier.width(FONT_DROPDOWN_WIDTH), onInteraction)
    }
}

/** The Tiles row: how round the tiles are, for every theme (ui/UiCorners.kt), as a segmented control whose segments draw their corner. */
@Composable
internal fun ShapeStyleSetting(state: AppState) {
    val styles = ShapeStyle.entries
    SettingsRow("Tiles", SHAPE_STYLE_HINT, anchor = "Shape style") {
        SettingsSegmented(
            options = styles.map(::shapeStyleTileLabel),
            selectedIndices = setOf(styles.indexOf(state.settings.shapeStyle)),
            onToggle = { index -> state.updateSettings { it.copy(shapeStyle = styles[index]) } },
            modifier = Modifier.testTag(SHAPE_STYLE_CONTROL_TAG),
            segmentModifier = { index ->
                Modifier.testTag("$SHAPE_STYLE_CONTROL_TAG-${styles[index].name}").semantics { contentDescription = shapeStyleLabel(styles[index]) }
            },
            segmentLeading = { index, color -> ShapeSample(styles[index], color) },
        )
    }
}

/**
 * The Toolbar row: how round the toolbar's button groups and the Layout menu button are (ui/UiCorners.kt). "Same as tiles" follows the
 * Tiles row; any style picked here stands on its own.
 */
@Composable
internal fun ToolbarShapeSetting(state: AppState, onInteraction: () -> Unit = {}) {
    val options: List<ShapeStyle?> = listOf(null) + ShapeStyle.entries
    val settings = state.settings
    SettingsRow("Toolbar", TOOLBAR_SHAPE_HINT, anchor = "Toolbar shape") {
        SettingsDropdown(
            options = options,
            selected = if (settings.toolbarShapeFollowsTiles) null else settings.toolbarShapeStyle,
            label = { style -> style?.let(::shapeStyleLabel) ?: "Same as tiles" },
            onSelect = { style ->
                state.updateSettings {
                    if (style == null) it.copy(toolbarShapeFollowsTiles = true) else it.copy(toolbarShapeStyle = style, toolbarShapeFollowsTiles = false)
                }
            },
            testTag = TOOLBAR_SHAPE_CONTROL_TAG,
            optionTag = { style -> "$TOOLBAR_SHAPE_CONTROL_TAG-option-${style?.name ?: "SAME_AS_TILES"}" },
            onInteraction = onInteraction,
        )
    }
}

// A 14x9dp rectangle with that style's corner, drawn in the segment's label colour (dashed for "Theme default", which has no corner of its own).
@Composable
private fun ShapeSample(style: ShapeStyle, color: Color) {
    Canvas(Modifier.size(SHAPE_SAMPLE_WIDTH, SHAPE_SAMPLE_HEIGHT)) {
        val width = 1.25.dp.toPx()
        val inset = width / 2f
        val sampleSize = Size(size.width - width, size.height - width)
        val radius = CornerRadius(shapeSampleRadiusDp(style).dp.toPx())
        val dash = if (style == ShapeStyle.THEME) PathEffect.dashPathEffect(floatArrayOf(SHAPE_DASH_DP.dp.toPx(), SHAPE_DASH_DP.dp.toPx())) else null
        drawRoundRect(color, Offset(inset, inset), sampleSize, radius, style = Stroke(width, pathEffect = dash))
    }
}

@Composable
internal fun AppearanceSettingsSection(state: AppState, reclaimFocus: () -> Unit) {
    SettingsGroup("Theme") {
        // The block keeps its own Themes | Special themes switcher and anchors; the group already carries the title.
        ThemeSettingsBlock(state, showHeading = false)
    }
    SettingsGroup("Text and scale") {
        InterfaceFontRow(state, reclaimFocus)
        LogFontRow(state, reclaimFocus)
        SettingsRow("Log font size", LOG_FONT_SIZE_HINT) { LogFontSizeStepper(state) }
        SettingsRow("Interface scale", INTERFACE_SCALE_HINT) { InterfaceScaleStepper(state) }
        if (isLinuxOs) LinuxFilePickerRow(state)
    }
    SettingsGroup("Corners") {
        ShapeStyleSetting(state)
        ToolbarShapeSetting(state, reclaimFocus)
    }
}

@Composable
private fun LinuxFilePickerRow(state: AppState) {
    SettingsRow(
        "File picker",
        "Automatic uses Compatibility X11 on Debian, Arch, Manjaro and Fedora, and Native GTK on Ubuntu and unrecognised " +
            "distributions. Restart Indagium to apply a change.",
    ) {
        val modes = LinuxFilePickerMode.entries
        SettingsSegmented(
            options = modes.map(LinuxFilePickerMode::label),
            selectedIndices = setOf(modes.indexOf(state.settings.linuxFilePickerMode)),
            onToggle = { index -> state.updateSettings { it.copy(linuxFilePickerMode = modes[index]) } },
        )
    }
}
