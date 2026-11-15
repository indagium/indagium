// The pack thumbnail is a small literal sketch (dp sizes of a 150 x 46 card), not tunables.
@file:Suppress("MagicNumber")

package com.indagium.ui.holiday

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.indagium.model.HolidayIntensity
import com.indagium.model.HolidayLightness
import com.indagium.model.HolidayMode
import com.indagium.model.HolidayPack
import com.indagium.model.HolidaySettings
import com.indagium.ui.*
import java.time.MonthDay
import java.time.format.DateTimeFormatter
import java.util.Locale

// Settings > Appearance > Theme: ONE block with two tabs, Themes (the regular gallery) and Special themes (a card per registered theme, when it
// applies, light or dark, how much it moves, the fun toolbar icons). Picking a regular theme turns the seasonal one off; picking a seasonal card
// turns it on. The user's own theme and tab style are never touched by a special theme; every change goes through AppState.updateSettings. The
// rows after the cards stay editable while no special theme is on, so a choice can be made before switching it on.

internal const val SEASONAL_MODE_TAG = "seasonal-mode"
internal const val SEASONAL_LIGHTNESS_TAG = "seasonal-lightness"
internal const val SEASONAL_INTENSITY_TAG = "seasonal-intensity"
internal const val SEASONAL_FUN_ICONS_TAG = "seasonal-fun-icons"
internal const val SEASONAL_PAUSE_TAG = "seasonal-pause"
internal const val THEME_TABS_TAG = "theme-tabs"
internal const val SEASONAL_TABS_TAG = "seasonal-tabs"
internal const val SEASONAL_STATUS_TAG = "seasonal-status"

/** The settings anchors of the rows on the Special themes tab: a search or jump to one of them opens that tab first. */
internal const val SEASONAL_ANCHOR_WHEN = "When it applies"
internal const val SEASONAL_ANCHOR_PACK = "Theme pack"
internal const val SEASONAL_ANCHOR_LIGHTNESS = "Light or dark"
internal const val SEASONAL_ANCHOR_EFFECTS = "Effects"
internal const val SEASONAL_ANCHOR_FUN_ICONS = "Fun icons"
internal const val SEASONAL_ANCHOR_PAUSE = "Pause effects while capturing or playing video"
internal const val SEASONAL_ANCHOR_TABS = "Tabs"
internal val SEASONAL_ANCHORS = setOf(
    SEASONAL_ANCHOR_WHEN, SEASONAL_ANCHOR_PACK, SEASONAL_ANCHOR_LIGHTNESS, SEASONAL_ANCHOR_EFFECTS, SEASONAL_ANCHOR_FUN_ICONS, SEASONAL_ANCHOR_PAUSE,
    SEASONAL_ANCHOR_TABS,
)

private const val TAB_THEMES = 0
private const val TAB_SEASONAL = 1
private val PACK_CARD_WIDTH = 306.dp

/** Below this width the pack cards stack in one column instead of two. */
private val TWO_PACK_COLUMNS_MIN_WIDTH = 520.dp

internal fun seasonalPackTag(pack: HolidayPack): String = "seasonal-pack-${pack.name.lowercase()}"

private const val GENERIC_EFFECTS_HINT = "Calm: fewer, slower and none over the log. Off: decorations stay, nothing moves."

/** The descriptions of the Special themes rows, shared with their search entries. */
internal const val SEASONAL_PACK_HINT = "A pack brings its own palette, tab shapes, scene behind the tabs and pictures."
internal const val SEASONAL_LIGHTNESS_HINT = "Whether the pack uses its light or dark colours, or follows your theme preset."
internal const val FUN_ICONS_HINT = "Toolbar buttons become pictures. Hover one to see its real name."
internal const val SEASONAL_PAUSE_HINT = "Stops the effects and animated pictures while a capture runs or a video plays."
internal const val SEASONAL_THEME_KIND_HINT = "Regular themes, or special ones that can switch on by date."
internal const val OWN_TABS_HINT = "My tab style keeps your Tab style (Settings › Tabs & toolbar) in the pack's colours."
private const val FOOTNOTE = "Log levels, sequences and highlighters keep their colours in every pack."

private val DATE_FORMAT = DateTimeFormatter.ofPattern("MMM d", Locale.ENGLISH)

private fun lightnessLabel(lightness: HolidayLightness): String = when (lightness) {
    HolidayLightness.FOLLOW_PRESET -> "Follow preset"
    HolidayLightness.LIGHT -> "Light"
    HolidayLightness.DARK -> "Dark"
}

internal fun intensityLabel(intensity: HolidayIntensity): String = when (intensity) {
    HolidayIntensity.FULL -> "Full"
    HolidayIntensity.CALM -> "Calm"
    HolidayIntensity.OFF -> "Off"
}

private fun MonthDay.label(): String = DATE_FORMAT.format(this.atYear(2001))

/** "Oct 24 – Nov 1": the days a theme applies in Automatic mode. */
internal fun holidayDatesLabel(theme: HolidayTheme): String = theme.automaticLabel ?: "${theme.dates.start.label()} – ${theme.dates.end.label()}"

/** "Full: bats, embers and lightning. Calm: ..." for [theme]'s effects (no list when it has none). */
internal fun holidayEffectsHint(theme: HolidayTheme): String {
    val nouns = theme.effects.map { it.noun }
    val full = when {
        nouns.isEmpty() -> "Full: pictures move"
        nouns.size == 1 -> "Full: ${nouns.single()}"
        else -> "Full: ${nouns.dropLast(1).joinToString(", ")} and ${nouns.last()}"
    }
    return "$full. Calm: fewer, slower and none over the log. Off: decorations stay, nothing moves."
}

/**
 * The "Theme" block of Settings > Appearance: a Themes | Special themes switcher over the regular gallery or the seasonal rows. It opens on the
 * tab that is active (Seasonal while a special theme is on); switching tabs changes nothing by itself, and the choice is not persisted. A search
 * result that targets a seasonal row opens the Seasonal tab (see [SEASONAL_ANCHORS]). The rows go straight into a host's [SettingsGroup]
 * ([showHeading] false, Settings › Appearance) or into a group of their own called Theme.
 */
@Composable
internal fun ThemeSettingsBlock(state: AppState, themes: Map<HolidayPack, HolidayTheme> = HOLIDAY_THEMES, showHeading: Boolean = true) {
    if (showHeading) {
        SettingsGroup("Theme") { ThemeSettingsRows(state, themes) }
    } else {
        ThemeSettingsRows(state, themes)
    }
}

@Composable
private fun ThemeSettingsRows(state: AppState, themes: Map<HolidayPack, HolidayTheme>) {
    val holiday = state.settings.holiday
    var tab by remember { mutableStateOf(if (holiday.mode != HolidayMode.OFF) TAB_SEASONAL else TAB_THEMES) }
    val pending = LocalSettingsAnchors.current?.pendingKey
    LaunchedEffect(pending) { if (pending != null && pending in SEASONAL_ANCHORS) tab = TAB_SEASONAL }
    SettingsRow("Choose from", SEASONAL_THEME_KIND_HINT, anchor = "Theme") {
        SettingsSegmented(
            options = listOf("Themes", "Special themes"),
            selectedIndices = setOf(tab),
            onToggle = { tab = it },
            modifier = Modifier.testTag(THEME_TABS_TAG),
        )
    }
    if (tab == TAB_THEMES) {
        Box(Modifier.fillMaxWidth().padding(12.dp)) {
            ThemeGallery(
                settings = state.settings,
                // No regular theme shows as selected while a seasonal one is on.
                selected = state.settings.theme.takeIf { holiday.mode == HolidayMode.OFF },
                onSelect = { preset -> preset?.let { state.updateSettings { s -> s.withRegularTheme(it) } } },
                height = null,
            )
        }
    } else {
        SeasonalThemeRows(state, themes)
    }
}

// The rows of the Special themes tab: the pack, when it applies (Off turns it off), light or dark, effects, tabs, the two switches, a note.
// With no special theme on the rows after the pack stay editable, so a choice can be made before switching it on.
@Composable
private fun SeasonalThemeRows(state: AppState, themes: Map<HolidayPack, HolidayTheme>) {
    val holiday = state.settings.holiday
    val on = holiday.mode != HolidayMode.OFF
    val selected = themes[holiday.pack] ?: themes.values.firstOrNull()
    val update: (HolidaySettings.() -> HolidaySettings) -> Unit = { edit ->
        state.updateSettings { s -> s.withHoliday { it.edit() } }
    }
    SettingsStackedRow(SEASONAL_ANCHOR_PACK, SEASONAL_PACK_HINT) {
        PackCards(themes, holiday.pack.takeIf { on }) { pack -> update { withPackOn(pack) } }
    }
    if (selected != null) WhenRow(selected, holiday.mode, state.settings.theme.label) { mode -> update { copy(mode = mode, pack = selected.pack) } }
    SettingsRow(SEASONAL_ANCHOR_LIGHTNESS, SEASONAL_LIGHTNESS_HINT) {
        val all = HolidayLightness.entries
        SettingsSegmented(
            options = all.map(::lightnessLabel),
            selectedIndices = setOf(all.indexOf(holiday.lightness)),
            onToggle = { update { copy(lightness = all[it]) } },
            modifier = Modifier.testTag(SEASONAL_LIGHTNESS_TAG),
        )
    }
    SettingsRow(SEASONAL_ANCHOR_EFFECTS, selected?.let(::holidayEffectsHint) ?: GENERIC_EFFECTS_HINT) {
        val all = HolidayIntensity.entries
        SettingsSegmented(
            options = all.map(::intensityLabel),
            selectedIndices = setOf(all.indexOf(holiday.intensity)),
            onToggle = { update { copy(intensity = all[it]) } },
            modifier = Modifier.testTag(SEASONAL_INTENSITY_TAG),
        )
    }
    if (selected != null) {
        SettingsRow(SEASONAL_ANCHOR_TABS, OWN_TABS_HINT) {
            val own = selected.pack in holiday.ownTabStylePacks
            SettingsSegmented(
                options = listOf("Theme's tabs", "My tab style"),
                selectedIndices = setOf(if (own) 1 else 0),
                onToggle = { update { withOwnTabStyle(selected.pack, it == 1) } },
                modifier = Modifier.testTag(SEASONAL_TABS_TAG),
            )
        }
    }
    if (selected?.icons != null) {
        SeasonalSwitchRow(SEASONAL_ANCHOR_FUN_ICONS, FUN_ICONS_HINT, holiday.funIcons, SEASONAL_FUN_ICONS_TAG) { update { copy(funIcons = !funIcons) } }
    }
    SeasonalSwitchRow(SEASONAL_ANCHOR_PAUSE, SEASONAL_PAUSE_HINT, holiday.pauseWhileBusy, SEASONAL_PAUSE_TAG) {
        update { copy(pauseWhileBusy = !pauseWhileBusy) }
    }
    SettingsNoteRow(FOOTNOTE)
}

// Off | Always | Only during its dates (Automatic for a theme with its own schedule). Picking Always or the dates turns the selected theme on;
// Off keeps the pack and your other choices and switches the special theme off. Under Automatic outside the dates the description says when
// it turns on and what shows until then.
@Composable
private fun WhenRow(theme: HolidayTheme, mode: HolidayMode, themeLabel: String, onSelect: (HolidayMode) -> Unit) {
    val now by rememberHolidayNow()
    val modes = buildList {
        add(HolidayMode.OFF)
        add(HolidayMode.ALWAYS)
        if (theme.supportsAutomatic) add(HolidayMode.AUTOMATIC)
    }
    val waiting = theme.supportsAutomatic && mode == HolidayMode.AUTOMATIC && !theme.automaticAt(now)
    val description = when {
        waiting -> "${theme.label} turns on ${holidayDatesLabel(theme).substringBefore(" – ")}; until then your theme is $themeLabel."
        theme.supportsAutomatic -> "${theme.label}: ${holidayDatesLabel(theme)}."
        else -> "${theme.label} is on all year while chosen."
    }
    SettingsRow(SEASONAL_ANCHOR_WHEN, description, descriptionTag = if (waiting) SEASONAL_STATUS_TAG else null) {
        SettingsSegmented(
            options = modes.map { m ->
                when (m) {
                    HolidayMode.OFF -> "Off"
                    HolidayMode.ALWAYS -> "Always"
                    HolidayMode.AUTOMATIC -> if (theme.automaticLabel == null) "Only during its dates" else "Automatic"
                }
            },
            selectedIndices = setOfNotNull(modes.indexOf(mode).takeIf { it >= 0 }),
            onToggle = { onSelect(modes[it]) },
            modifier = Modifier.testTag(SEASONAL_MODE_TAG),
        )
    }
}

@Composable
private fun SeasonalSwitchRow(label: String, hint: String, checked: Boolean, tag: String, onToggle: () -> Unit) {
    SettingsRow(label, hint) {
        SettingsSwitch(checked = checked, onCheckedChange = { onToggle() }, label = label, testTag = tag)
    }
}

// One selectable card per registered theme: a small thumbnail of its tab-strip scene with some of its icons, its name and dates. Two equal
// columns that fill the row, one column in a narrow window.
@Composable
private fun PackCards(themes: Map<HolidayPack, HolidayTheme>, selected: HolidayPack?, onSelect: (HolidayPack) -> Unit) {
    BoxWithConstraints(Modifier.fillMaxWidth()) {
        WorkspaceCardGrid(
            cards = themes.values.map { theme ->
                { cardModifier: Modifier -> PackCard(theme, theme.pack == selected, modifier = cardModifier, onClick = { onSelect(theme.pack) }) }
            },
            columns = if (maxWidth >= TWO_PACK_COLUMNS_MIN_WIDTH) 2 else 1,
            spacing = 8.dp,
        )
    }
}

/** The test tag of [pack]'s card drawn in a fixed mode: [seasonalPackTag] plus "-light" or "-dark". */
internal fun seasonalPackModeTag(pack: HolidayPack, dark: Boolean): String = seasonalPackTag(pack) + if (dark) "-dark" else "-light"

/**
 * A theme card. [dark] null follows the current theme (the Settings tab); true / false draws the card in the theme's OWN dark / light palette
 * (`theme.palette(dark)` for background, border and text, `theme.chrome(dark)` for the thumbnail), whatever the app shows now (the release
 * announcement's two columns). A fixed-mode card carries [seasonalPackModeTag] and a "Pumpkin patch, light" content description. A picture
 * picker, so it follows the one selected-state rule. [modifier] sets the size; the default is a fixed 306dp card.
 */
@Composable
internal fun PackCard(
    theme: HolidayTheme,
    selected: Boolean,
    dark: Boolean? = null,
    modifier: Modifier = Modifier.width(PACK_CARD_WIDTH),
    onClick: () -> Unit,
) {
    val current = tc()
    val tc = if (dark == null) current else remember(theme, dark) { theme.palette(dark) }
    val tag = if (dark == null) seasonalPackTag(theme.pack) else seasonalPackModeTag(theme.pack, dark)
    val description = if (dark == null) null else "${theme.label}, ${if (dark) "dark" else "light"}"
    val shape = RoundedCornerShape(8.dp)
    val interaction = remember { MutableInteractionSource() }
    val hovered by interaction.collectIsHoveredAsState()
    Row(
        modifier
            .settingsPickerSurface(selected, hovered, shape, tc)
            .hoverable(interaction)
            .semantics {
                role = Role.Button
                this.selected = selected
                if (description != null) contentDescription = description
            }
            .clickable(interactionSource = interaction, indication = null, role = Role.Button, onClick = onClick)
            .padding(8.dp)
            .testTag(tag),
        horizontalArrangement = Arrangement.spacedBy(10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        PackPreview(theme, tc, dark ?: !tc.isLight())
        Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
            AppText(theme.label, color = if (selected) tc.ac else tc.tx, fontSize = 12.sp, fontWeight = FontWeight.Medium)
            // An any-time pack has no dates of its own to show.
            val dates = if (theme.supportsAutomatic) " · ${holidayDatesLabel(theme)}" else ""
            AppText("${theme.occasion}$dates", color = tc.ts, fontSize = 10.sp, maxLines = 2)
        }
    }
}

// The theme's scene band (when it has one) with three of its toolbar pictures (when it has them) on top, in the palette of the family it will use.
@Composable
private fun PackPreview(theme: HolidayTheme, tc: ThemeColors, dark: Boolean) {
    val chrome = remember(theme, dark) { theme.chrome(dark) }
    val icons = remember(theme, tc.tx) { theme.icons?.icons(tc.tx) }
    Box(PREVIEW_MODIFIER) {
        Canvas(Modifier.size(PREVIEW_WIDTH, PREVIEW_HEIGHT)) {
            drawRect(chrome.tabBar)
            val painter = theme.tabScene
            if (painter != null) clipRect { drawSceneThumbnail(painter, dark) }
        }
        if (icons != null) {
            Row(Modifier.padding(start = 6.dp, top = 4.dp), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                listOf(ToolbarIconId.NEW, ToolbarIconId.FILTERS, ToolbarIconId.NOTES).forEach { id ->
                    Image(icons.getValue(id).vector, contentDescription = null, modifier = Modifier.size(14.dp))
                }
            }
        }
    }
}

private val PREVIEW_WIDTH = 120.dp
private val PREVIEW_HEIGHT = 36.dp
private val PREVIEW_MODIFIER = Modifier.size(PREVIEW_WIDTH, PREVIEW_HEIGHT).clip(RoundedCornerShape(6.dp))

// The scene band is a 1600 dp wide title bar; the thumbnail shows its right-hand part, where the compositions are, at THUMBNAIL_SCALE along the
// bottom of the card (the band's bar colour is the card's).
private const val THUMBNAIL_SCALE = 0.3f

private fun DrawScope.drawSceneThumbnail(painter: HolidayScenePainter, dark: Boolean) {
    val band = Rect(0f, 0f, HOLIDAY_SCENE_REFERENCE_WIDTH * density, HOLIDAY_SCENE_REFERENCE_HEIGHT_DP * density)
    val layout = painter.layout(band)
    withTransform({
        translate(size.width - band.width * THUMBNAIL_SCALE, size.height - band.height * THUMBNAIL_SCALE)
        scale(THUMBNAIL_SCALE, THUMBNAIL_SCALE, Offset.Zero)
    }) { painter.draw(this, layout, dark) }
}
