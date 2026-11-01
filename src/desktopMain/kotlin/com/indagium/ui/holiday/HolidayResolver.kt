package com.indagium.ui.holiday

import androidx.compose.runtime.Composable
import androidx.compose.runtime.State
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.produceState
import com.indagium.model.AppSettings
import com.indagium.model.HolidayIntensity
import com.indagium.model.HolidayLightness
import com.indagium.model.HolidayMode
import com.indagium.model.HolidayPack
import com.indagium.model.HolidaySettings
import com.indagium.ui.*
import com.indagium.ui.ThemeColors
import com.indagium.ui.isLight
import com.indagium.ui.themeColors
import kotlinx.coroutines.delay
import java.time.Duration
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.MonthDay

// Seasonal packs: which pack is active today, which palette it uses and how much it moves. Pure and UI-free except for the
// composition locals at the bottom. The user's own preset and tab style are never changed; the pack only overrides what the
// UI reads (App.kt provides [LocalHoliday] and the pack's [ThemeColors] as LocalTheme).

private const val CALM_COUNT_SCALE = 0.4f
private const val CALM_SPEED_SCALE = 0.6f
private const val MAX_CLOCK_REFRESH_MS = 60L * 60L * 1000L
private const val MIN_CLOCK_REFRESH_MS = 1_000L

/** A yearly date range, both ends inclusive. [end] before [start] means the range wraps past New Year. */
data class HolidayDates(val start: MonthDay, val end: MonthDay) {
    fun contains(d: LocalDate): Boolean {
        val day = MonthDay.of(d.month, d.dayOfMonth)
        return if (start <= end) day >= start && day <= end else day >= start || day <= end
    }
}

/**
 * The theme to apply on [today], or null when the feature is off, or automatic and no registered theme's dates contain today. Always uses
 * the selected pack. Automatic takes the theme whose window contains today; when windows overlap the selected pack wins if it is among them,
 * else the first in registry order.
 */
internal fun activeHolidayTheme(h: HolidaySettings, today: LocalDate, themes: Map<HolidayPack, HolidayTheme> = HOLIDAY_THEMES): HolidayTheme? =
    when (h.mode) {
        HolidayMode.OFF -> null
        HolidayMode.ALWAYS -> themes[h.pack]
        HolidayMode.AUTOMATIC -> {
            val inWindow = themes.values.filter { it.dates.contains(today) }
            inWindow.firstOrNull { it.pack == h.pack } ?: inWindow.firstOrNull()
        }
    }

/** The pack to apply on [today] (see [activeHolidayTheme]). */
internal fun activeHolidayPack(h: HolidaySettings, today: LocalDate, themes: Map<HolidayPack, HolidayTheme> = HOLIDAY_THEMES): HolidayPack? =
    activeHolidayTheme(h, today, themes)?.pack

/** True when the pack renders its dark palette: an explicit choice, or the family of the user's own [preset]. */
fun resolveHolidayDark(lightness: HolidayLightness, preset: ThemeColors): Boolean = when (lightness) {
    HolidayLightness.DARK -> true
    HolidayLightness.LIGHT -> false
    HolidayLightness.FOLLOW_PRESET -> !preset.isLight()
}

/**
 * What the theme's decorations may do, after the intensity and the theme's own capabilities are applied to the user's toggles. A
 * decoration the theme does not provide is off here whatever the setting says. [overLog] says whether motion may cross the log and
 * Original panes; [countScale] and [speedScale] scale particle counts and speeds; [activeEffects] are the theme's effects the user has on
 * (none under Off intensity); [flashMinGapSec] is the least time between flashes.
 */
internal data class HolidayEffects(
    val activeEffects: List<HolidayEffectSpec>,
    val tabScene: Boolean,
    val filterPicture: Boolean,
    val animatePicture: Boolean,
    val notesCompanion: Boolean,
    val funIcons: Boolean,
    val overLog: Boolean,
    val countScale: Float,
    val speedScale: Float,
    val flashMinGapSec: Float,
) {
    /** Whether anything animates at all (no frame loop is needed when false). */
    val moving: Boolean get() = activeEffects.any { it.moves } || animatePicture
}

internal fun resolveHolidayEffects(h: HolidaySettings, theme: HolidayTheme): HolidayEffects {
    val chosen = theme.effects.filter(h::isEffectOn)
    val tabScene = h.tabScene && theme.tabScene != null
    val picture = h.filterPicture && theme.picture != null
    val companion = h.notesCompanion && theme.companion != null
    val icons = h.funIcons && theme.icons != null
    return when (h.intensity) {
        HolidayIntensity.FULL -> HolidayEffects(
            chosen, tabScene, picture, h.animatePicture, companion, icons,
            overLog = true, countScale = 1f, speedScale = 1f, flashMinGapSec = HOLIDAY_FLASH_GAP_FULL_SEC,
        )
        HolidayIntensity.CALM -> HolidayEffects(
            chosen, tabScene, picture, h.animatePicture, companion, icons,
            overLog = false, countScale = CALM_COUNT_SCALE, speedScale = CALM_SPEED_SCALE, flashMinGapSec = HOLIDAY_FLASH_GAP_CALM_SEC,
        )
        // Static decorations stay as set; nothing moves.
        HolidayIntensity.OFF -> HolidayEffects(
            emptyList(), tabScene, picture, false, companion, icons,
            overLog = false, countScale = 0f, speedScale = 0f, flashMinGapSec = HOLIDAY_FLASH_GAP_FULL_SEC,
        )
    }
}

/** Everything the UI needs while a theme is active: the theme, its palette variant, the framework's chrome colours and what its decorations may do. */
internal data class HolidayLook(
    val theme: HolidayTheme,
    val isDark: Boolean,
    val colors: ThemeColors,
    val chrome: HolidayChromeColors,
    val effects: HolidayEffects,
) {
    val pack: HolidayPack get() = theme.pack
}

/** The look for [today], or null when no theme applies and the user's preset is used as it is. [themes] is injectable for tests. */
internal fun resolveHolidayLook(settings: AppSettings, today: LocalDate, themes: Map<HolidayPack, HolidayTheme> = HOLIDAY_THEMES): HolidayLook? {
    val theme = activeHolidayTheme(settings.holiday, today, themes) ?: return null
    val dark = resolveHolidayDark(settings.holiday.lightness, themeColors(settings.theme))
    return HolidayLook(
        theme = theme,
        isDark = dark,
        colors = theme.palette(dark),
        chrome = theme.chrome(dark),
        effects = resolveHolidayEffects(settings.holiday, theme),
    )
}

/** The colours the UI draws with: the active pack's palette, else the user's preset. Non-composable twin of App.kt's choice. */
internal fun effectiveThemeColors(settings: AppSettings, today: LocalDate = LocalDate.now()): ThemeColors =
    resolveHolidayLook(settings, today)?.colors ?: themeColors(settings.theme)

/** [this] with its holiday settings transformed (the one write path for the Settings dialog and the Layout menu). */
fun AppSettings.withHoliday(fn: (HolidaySettings) -> HolidaySettings): AppSettings = copy(holiday = fn(holiday))

/** The active pack's look, or null (no pack). Provided by App.kt next to LocalTheme. */
internal val LocalHoliday = compositionLocalOf<HolidayLook?> { null }

/** True while animation should stand still (capturing, playing video); separate so play/pause never recomposes the chrome. */
val LocalHolidayPaused = compositionLocalOf { false }

/** Today's date, re-read at local midnight and at least hourly, so Automatic mode flips without a restart. */
@Composable
fun rememberHolidayToday(): State<LocalDate> = produceState(LocalDate.now()) {
    while (true) {
        val now = LocalDateTime.now()
        val untilMidnight = Duration.between(now, now.toLocalDate().plusDays(1).atStartOfDay()).toMillis()
        delay(untilMidnight.coerceIn(MIN_CLOCK_REFRESH_MS, MAX_CLOCK_REFRESH_MS))
        value = LocalDate.now()
    }
}
