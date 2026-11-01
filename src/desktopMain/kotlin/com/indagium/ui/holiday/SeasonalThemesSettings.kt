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
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
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

// Settings > Appearance > Seasonal theme: when a pack applies (never, on its dates, always), which pack, light or dark, how much it moves, and
// the fun toolbar icons. The user's own theme and tab style are never touched; every change goes through AppState.updateSettings. The
// rows after the mode only look dimmed while the mode is Off: they stay editable so a choice can be made before switching the pack on.

internal const val SEASONAL_MODE_TAG = "seasonal-mode"
internal const val SEASONAL_LIGHTNESS_TAG = "seasonal-lightness"
internal const val SEASONAL_INTENSITY_TAG = "seasonal-intensity"
internal const val SEASONAL_FUN_ICONS_TAG = "seasonal-fun-icons"
internal const val SEASONAL_PAUSE_TAG = "seasonal-pause"

internal fun seasonalPackTag(pack: HolidayPack): String = "seasonal-pack-${pack.name.lowercase()}"

private const val DIMMED_ALPHA = 0.5f

private const val MODE_HINT = "Automatic turns a pack on for its dates, then goes back to your preset."
private const val GENERIC_EFFECTS_HINT = "Calm: fewer, slower and none over the log. Off: decorations stay, nothing moves."
private const val FUN_ICONS_HINT = "Toolbar buttons become pictures. Hover one to see its real name."
private const val FOOTNOTE = "Log levels, sequences and highlighters keep their colours in every pack."

private val DATE_FORMAT = DateTimeFormatter.ofPattern("MMM d", Locale.ENGLISH)

private fun modeLabel(mode: HolidayMode): String = when (mode) {
    HolidayMode.OFF -> "Off"
    HolidayMode.AUTOMATIC -> "Automatic"
    HolidayMode.ALWAYS -> "Always"
}

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
internal fun holidayDatesLabel(theme: HolidayTheme): String = "${theme.dates.start.label()} – ${theme.dates.end.label()}"

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

@Composable
internal fun SeasonalThemesSettings(state: AppState, themes: Map<HolidayPack, HolidayTheme> = HOLIDAY_THEMES) {
    val tc = tc()
    val holiday = state.settings.holiday
    val selected = themes[holiday.pack] ?: themes.values.firstOrNull()
    val update: (HolidaySettings.() -> HolidaySettings) -> Unit = { edit ->
        state.updateSettings { s -> s.withHoliday { it.edit() } }
    }
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        AppText("Seasonal theme", color = tc.td, fontSize = 10.sp, fontFamily = UI, fontWeight = FontWeight.SemiBold)
        ModeRow(holiday.mode) { mode -> update { copy(mode = mode) } }
        Column(Modifier.alpha(if (holiday.mode == HolidayMode.OFF) DIMMED_ALPHA else 1f), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            PackCards(themes, holiday.pack) { pack -> update { copy(pack = pack) } }
            SettingsControlRow {
                LightnessRow(holiday.lightness) { lightness -> update { copy(lightness = lightness) } }
                val hint = selected?.let(::holidayEffectsHint) ?: GENERIC_EFFECTS_HINT
                EffectsRow(holiday.intensity, hint) { intensity -> update { copy(intensity = intensity) } }
            }
            if (selected?.icons != null) {
                SeasonalCheck("Fun icons", FUN_ICONS_HINT, holiday.funIcons, SEASONAL_FUN_ICONS_TAG) { update { copy(funIcons = !funIcons) } }
            }
            SeasonalCheck("Pause effects while capturing or playing video", null, holiday.pauseWhileBusy, SEASONAL_PAUSE_TAG) {
                update { copy(pauseWhileBusy = !pauseWhileBusy) }
            }
            AppText(FOOTNOTE, color = tc.td, fontSize = 10.sp)
        }
    }
}

@Composable
private fun ModeRow(mode: HolidayMode, onSelect: (HolidayMode) -> Unit) {
    val tc = tc()
    CompactSetting("Seasonal theme") {
        val modes = HolidayMode.entries
        SegmentedControl(
            options = modes.map(::modeLabel),
            selectedIndices = setOf(modes.indexOf(mode)),
            onToggle = { onSelect(modes[it]) },
            modifier = Modifier.testTag(SEASONAL_MODE_TAG),
        )
        AppText(MODE_HINT, color = tc.td, fontSize = 10.sp)
    }
}

@Composable
private fun LightnessRow(lightness: HolidayLightness, onSelect: (HolidayLightness) -> Unit) {
    CompactSetting("Light or dark") {
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
    CompactSetting("Effects") {
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
private fun PackCards(themes: Map<HolidayPack, HolidayTheme>, selected: HolidayPack, onSelect: (HolidayPack) -> Unit) {
    CompactSetting("Theme pack") {
        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
            themes.values.forEach { theme -> PackCard(theme, theme.pack == selected) { onSelect(theme.pack) } }
        }
    }
}

@Composable
private fun PackCard(theme: HolidayTheme, selected: Boolean, onClick: () -> Unit) {
    val tc = tc()
    val shape = RoundedCornerShape(8.dp)
    Row(
        Modifier
            .fillMaxWidth()
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
