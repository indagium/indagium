// The pack thumbnail is a small literal sketch (dp sizes of a 150 x 46 card), not tunables.
@file:Suppress("MagicNumber")

package com.indagium.ui.holiday

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
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
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
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
// rows after the cards only look dimmed while no special theme is on: they stay editable so a choice can be made before switching it on.

internal const val SEASONAL_MODE_TAG = "seasonal-mode"
internal const val SEASONAL_LIGHTNESS_TAG = "seasonal-lightness"
internal const val SEASONAL_INTENSITY_TAG = "seasonal-intensity"
internal const val SEASONAL_FUN_ICONS_TAG = "seasonal-fun-icons"
internal const val SEASONAL_PAUSE_TAG = "seasonal-pause"
internal const val THEME_TABS_TAG = "theme-tabs"
internal const val SEASONAL_OFF_TAG = "seasonal-turn-off"
internal const val SEASONAL_STATUS_TAG = "seasonal-status"

/** The settings anchors of the rows on the Special themes tab: a search or jump to one of them opens that tab first. */
internal const val SEASONAL_ANCHOR_WHEN = "When it applies"
internal const val SEASONAL_ANCHOR_PACK = "Theme pack"
internal const val SEASONAL_ANCHOR_LIGHTNESS = "Light or dark"
internal const val SEASONAL_ANCHOR_EFFECTS = "Effects"
internal const val SEASONAL_ANCHOR_FUN_ICONS = "Fun icons"
internal const val SEASONAL_ANCHOR_PAUSE = "Pause effects while capturing or playing video"
internal val SEASONAL_ANCHORS = setOf(
    SEASONAL_ANCHOR_WHEN, SEASONAL_ANCHOR_PACK, SEASONAL_ANCHOR_LIGHTNESS, SEASONAL_ANCHOR_EFFECTS, SEASONAL_ANCHOR_FUN_ICONS, SEASONAL_ANCHOR_PAUSE,
)

private const val TAB_THEMES = 0
private const val TAB_SEASONAL = 1
private val PACK_CARD_WIDTH = 306.dp

internal fun seasonalPackTag(pack: HolidayPack): String = "seasonal-pack-${pack.name.lowercase()}"

private const val DIMMED_ALPHA = 0.5f

private const val GENERIC_EFFECTS_HINT = "Calm: fewer, slower and none over the log. Off: decorations stay, nothing moves."
private const val FUN_ICONS_HINT = "Toolbar buttons become pictures. Hover one to see its real name."
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
 * The "Theme" block of Settings > Appearance: a Themes | Special themes switcher over the regular gallery or the seasonal cards. It opens on the
 * tab that is active (Seasonal while a special theme is on); switching tabs changes nothing by itself, and the choice is not persisted. A search
 * result that targets a seasonal row opens the Seasonal tab (see [SEASONAL_ANCHORS]).
 */
@Composable
internal fun ThemeSettingsBlock(state: AppState, themes: Map<HolidayPack, HolidayTheme> = HOLIDAY_THEMES) {
    val tc = tc()
    val holiday = state.settings.holiday
    var tab by remember { mutableStateOf(if (holiday.mode != HolidayMode.OFF) TAB_SEASONAL else TAB_THEMES) }
    val pending = LocalSettingsAnchors.current?.pendingKey
    LaunchedEffect(pending) { if (pending != null && pending in SEASONAL_ANCHORS) tab = TAB_SEASONAL }
    Column(Modifier.settingsAnchor("Theme"), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        AppText("Theme", color = tc.td, fontSize = 10.sp, fontFamily = UI, fontWeight = FontWeight.SemiBold)
        SegmentedControl(
            options = listOf("Themes", "Special themes"),
            selectedIndices = setOf(tab),
            onToggle = { tab = it },
            modifier = Modifier.testTag(THEME_TABS_TAG),
        )
        if (tab == TAB_THEMES) {
            ThemeGallery(
                settings = state.settings,
                // No regular theme shows as selected while a seasonal one is on.
                selected = state.settings.theme.takeIf { holiday.mode == HolidayMode.OFF },
                onSelect = { preset -> preset?.let { state.updateSettings { s -> s.withRegularTheme(it) } } },
                height = null,
            )
        } else {
            SeasonalThemesTab(state, themes)
        }
    }
}

@Composable
private fun SeasonalThemesTab(state: AppState, themes: Map<HolidayPack, HolidayTheme>) {
    val tc = tc()
    val holiday = state.settings.holiday
    val on = holiday.mode != HolidayMode.OFF
    val selected = themes[holiday.pack] ?: themes.values.firstOrNull()
    val update: (HolidaySettings.() -> HolidaySettings) -> Unit = { edit ->
        state.updateSettings { s -> s.withHoliday { it.edit() } }
    }
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        PackCards(themes, holiday.pack.takeIf { on }) { pack -> update { withPackOn(pack) } }
        if (selected != null) {
            WhenRow(selected, holiday.mode) { mode -> update { copy(mode = mode, pack = selected.pack) } }
            AutomaticStatus(state, selected, holiday)
        }
        if (on) AccentLink("Turn off special theme", onClick = { update { copy(mode = HolidayMode.OFF) } }, modifier = Modifier.testTag(SEASONAL_OFF_TAG))
        Column(Modifier.alpha(if (on) 1f else DIMMED_ALPHA), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            SettingsControlRow {
                LightnessRow(holiday.lightness) { lightness -> update { copy(lightness = lightness) } }
                val hint = selected?.let(::holidayEffectsHint) ?: GENERIC_EFFECTS_HINT
                EffectsRow(holiday.intensity, hint) { intensity -> update { copy(intensity = intensity) } }
            }
            if (selected?.icons != null) {
                SeasonalCheck(SEASONAL_ANCHOR_FUN_ICONS, FUN_ICONS_HINT, holiday.funIcons, SEASONAL_FUN_ICONS_TAG) { update { copy(funIcons = !funIcons) } }
            }
            SeasonalCheck(SEASONAL_ANCHOR_PAUSE, null, holiday.pauseWhileBusy, SEASONAL_PAUSE_TAG) {
                update { copy(pauseWhileBusy = !pauseWhileBusy) }
            }
            AppText(FOOTNOTE, color = tc.td, fontSize = 10.sp)
        }
    }
}

// Always | Only during its dates. With no special theme on neither is selected; picking one turns the selected theme on.
@Composable
private fun WhenRow(theme: HolidayTheme, mode: HolidayMode, onSelect: (HolidayMode) -> Unit) {
    val tc = tc()
    CompactSetting(SEASONAL_ANCHOR_WHEN) {
        val modes = listOf(HolidayMode.ALWAYS, HolidayMode.AUTOMATIC)
        SegmentedControl(
            options = listOf("Always", if (theme.automaticLabel == null) "Only during its dates" else "Automatic"),
            selectedIndices = setOfNotNull(modes.indexOf(mode).takeIf { it >= 0 }),
            onToggle = { onSelect(modes[it]) },
            modifier = Modifier.testTag(SEASONAL_MODE_TAG),
        )
        AppText("${theme.label}: ${holidayDatesLabel(theme)}", color = tc.td, fontSize = 10.sp)
    }
}

// Under Automatic outside the dates the theme is waiting: say when it turns on and what shows until then.
@Composable
private fun AutomaticStatus(state: AppState, theme: HolidayTheme, holiday: HolidaySettings) {
    val now by rememberHolidayNow()
    if (holiday.mode != HolidayMode.AUTOMATIC || theme.automaticAt(now)) return
    val tc = tc()
    AppText(
        "${theme.label} turns on ${holidayDatesLabel(theme).substringBefore(" – ")}; until then your theme is ${state.settings.theme.label}",
        color = tc.td,
        fontSize = 10.sp,
        modifier = Modifier.testTag(SEASONAL_STATUS_TAG),
    )
}

@Composable
private fun LightnessRow(lightness: HolidayLightness, onSelect: (HolidayLightness) -> Unit) {
    CompactSetting(SEASONAL_ANCHOR_LIGHTNESS) {
        val all = HolidayLightness.entries
        SegmentedControl(
            options = all.map(::lightnessLabel),
            selectedIndices = setOf(all.indexOf(lightness)),
            onToggle = { onSelect(all[it]) },
            modifier = Modifier.testTag(SEASONAL_LIGHTNESS_TAG),
        )
    }
}

@Composable
private fun EffectsRow(intensity: HolidayIntensity, hint: String, onSelect: (HolidayIntensity) -> Unit) {
    val tc = tc()
    CompactSetting(SEASONAL_ANCHOR_EFFECTS) {
        val all = HolidayIntensity.entries
        SegmentedControl(
            options = all.map(::intensityLabel),
            selectedIndices = setOf(all.indexOf(intensity)),
            onToggle = { onSelect(all[it]) },
            modifier = Modifier.testTag(SEASONAL_INTENSITY_TAG),
        )
        AppText(hint, color = tc.td, fontSize = 10.sp, modifier = Modifier.width(260.dp))
    }
}

@Composable
private fun SeasonalCheck(label: String, hint: String?, checked: Boolean, tag: String, onToggle: () -> Unit) {
    val tc = tc()
    CheckRow(modifier = Modifier.settingsAnchor(label).testTag(tag), checked = checked, onToggle = onToggle) {
        Column {
            AppText(label, color = tc.tx, fontSize = 11.sp)
            if (hint != null) AppText(hint, color = tc.td, fontSize = 10.sp)
        }
    }
}

// One selectable card per registered theme: a small thumbnail of its tab-strip scene with some of its icons, its name and dates.
@Composable
private fun PackCards(themes: Map<HolidayPack, HolidayTheme>, selected: HolidayPack?, onSelect: (HolidayPack) -> Unit) {
    CompactSetting(SEASONAL_ANCHOR_PACK) {
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            themes.values.forEach { theme -> PackCard(theme, theme.pack == selected) { onSelect(theme.pack) } }
        }
    }
}

@Composable
internal fun PackCard(theme: HolidayTheme, selected: Boolean, onClick: () -> Unit) {
    val tc = tc()
    val shape = RoundedCornerShape(8.dp)
    Row(
        Modifier
            .width(PACK_CARD_WIDTH)
            .clip(shape)
            .border(if (selected) 1.5.dp else 1.dp, if (selected) tc.ac else tc.br, shape)
            .background(if (selected) tc.abg else tc.p2)
            .semantics { role = Role.Button; this.selected = selected }
            .clickable(role = Role.Button, onClick = onClick)
            .padding(8.dp)
            .testTag(seasonalPackTag(theme.pack)),
        horizontalArrangement = Arrangement.spacedBy(10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        PackPreview(theme)
        Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
            AppText(theme.label, color = tc.tx, fontSize = 12.sp, fontWeight = FontWeight.Medium)
            AppText("${theme.occasion} · ${holidayDatesLabel(theme)}", color = tc.td, fontSize = 10.sp)
        }
    }
}

// The theme's tab-strip scene (when it has one) with three of its toolbar pictures (when it has them) on top, in the palette of the family it will use.
@Composable
private fun PackPreview(theme: HolidayTheme) {
    val tc = tc()
    val dark = !tc.isLight()
    val chrome = remember(theme, dark) { theme.chrome(dark) }
    val icons = remember(theme, tc.tx) { theme.icons?.icons(tc.tx) }
    Box(PREVIEW_MODIFIER) {
        Canvas(Modifier.size(PREVIEW_WIDTH, PREVIEW_HEIGHT)) {
            drawRect(chrome.tabBar)
            val painter = theme.tabScene
            if (painter != null) clipRect { painter.draw(this, painter.layout(Rect(Offset.Zero, size), vertical = false), dark) }
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
