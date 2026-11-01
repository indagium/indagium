package com.indagium.ui.holiday

import com.indagium.model.HolidayPack
import com.indagium.ui.holiday.manor.HauntedManorTheme
import com.indagium.ui.holiday.pumpkin.PumpkinPatchTheme

/** The registered themes in registry order (the tie-break when several packs' dates overlap). A new pack is added here and nowhere else. */
internal val HOLIDAY_THEMES: Map<HolidayPack, HolidayTheme> = holidayThemeRegistry(PumpkinPatchTheme, HauntedManorTheme)

/** [themes] keyed by pack, in the order given. */
internal fun holidayThemeRegistry(vararg themes: HolidayTheme): Map<HolidayPack, HolidayTheme> = linkedMapOf<HolidayPack, HolidayTheme>().apply {
    themes.forEach { put(it.pack, it) }
}
