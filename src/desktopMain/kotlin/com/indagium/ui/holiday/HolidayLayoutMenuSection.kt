package com.indagium.ui.holiday

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.indagium.model.HolidayIntensity
import com.indagium.model.HolidaySettings
import com.indagium.ui.*

// The "Special effects" part of the log view's Layout menu, shown only while a special theme is active: how much it moves and which of its
// decorations are on, generated from the active theme's declared effects and capabilities. The pack itself is chosen in Settings. Every
// click goes through updateSettings and then hands keyboard focus back (a clicked row in a popup otherwise keeps it, CLAUDE.md).

/** A switch row of the section: its [key] (the test tag suffix), label, and how it reads and flips the setting. */
internal class HolidayToggle(val key: String, val label: String, val read: (HolidaySettings) -> Boolean, val flip: (HolidaySettings) -> HolidaySettings)

/**
 * The rows of the section for [theme]: one per effect it declares (in its order), then the generic switches for the capabilities it has (no
 * scene row without a tab scene, no picture or animate row without a picture or companion, and so on), then the always-present pause row.
 */
internal fun holidayToggles(theme: HolidayTheme): List<HolidayToggle> = buildList {
    theme.effects.forEach { spec ->
        add(HolidayToggle(spec.id, spec.label, { it.isEffectOn(spec) }, { it.copy(effectToggles = it.effectToggles + (spec.id to !it.isEffectOn(spec))) }))
    }
    if (theme.tabScene != null) add(HolidayToggle("scene", "Scene in the tab bar", { it.tabScene }, { it.copy(tabScene = !it.tabScene) }))
    if (theme.picture != null) add(HolidayToggle("picture", "Picture under the filters", { it.filterPicture }, { it.copy(filterPicture = !it.filterPicture) }))
    if (theme.picture != null || theme.companion != null) {
        add(HolidayToggle("animate", "Animate the picture", { it.animatePicture }, { it.copy(animatePicture = !it.animatePicture) }))
    }
    if (theme.icons != null) add(HolidayToggle("icons", "Picture icons in the toolbar", { it.funIcons }, { it.copy(funIcons = !it.funIcons) }))
    theme.companion?.let { c -> add(HolidayToggle("companion", c.label, { it.notesCompanion }, { it.copy(notesCompanion = !it.notesCompanion) })) }
    add(HolidayToggle("pause", "Pause while capturing or playing video", { it.pauseWhileBusy }, { it.copy(pauseWhileBusy = !it.pauseWhileBusy) }))
}

/** Test tag of the section, of the intensity control and (suffixed by key) of each switch row. */
internal const val LAYOUT_MENU_HOLIDAY_TAG = "layout-menu-holiday"
internal const val LAYOUT_MENU_HOLIDAY_INTENSITY_TAG = "layout-menu-holiday-intensity"

internal fun layoutMenuHolidayToggleTag(key: String): String = "layout-menu-holiday-$key"

@Composable
internal fun HolidayEffectsMenuSection(state: AppState, onReclaimFocus: () -> Unit) {
    val look = LocalHoliday.current ?: return
    val tc = tc()
    val holiday = state.settings.holiday
    Divider()
    Box(Modifier.testTag(LAYOUT_MENU_HOLIDAY_TAG)) { MenuLabel("Special effects") }
    val intensities = HolidayIntensity.entries
    SegmentedControl(
        options = intensities.map(::intensityLabel),
        selectedIndices = setOf(intensities.indexOf(holiday.intensity)),
        onToggle = { index ->
            state.updateSettings { s -> s.withHoliday { it.copy(intensity = intensities[index]) } }
            onReclaimFocus()
        },
        modifier = Modifier.padding(horizontal = 12.dp, vertical = 2.dp).testTag(LAYOUT_MENU_HOLIDAY_INTENSITY_TAG),
    )
    holidayToggles(look.theme).forEach { toggle ->
        Box(Modifier.testTag(layoutMenuHolidayToggleTag(toggle.key))) {
            CheckRow(toggle.read(holiday), {
                state.updateSettings { s -> s.withHoliday(toggle.flip) }
                onReclaimFocus()
            }) { AppText(toggle.label, color = tc.tx, fontSize = 12.sp) }
        }
    }
}
