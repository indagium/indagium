package com.indagium.ui.holiday

import com.indagium.model.HolidayPack
import com.indagium.ui.holiday.bear.CoffeeBearTheme
import com.indagium.ui.holiday.beer.BeerGreenTheme
import com.indagium.ui.holiday.beer.BeerYellowTheme
import com.indagium.ui.holiday.candy.TrickOrTreatTheme
import com.indagium.ui.holiday.cozy.CozyTheme
import com.indagium.ui.holiday.devday.DevDayTheme
import com.indagium.ui.holiday.manor.HauntedManorTheme
import com.indagium.ui.holiday.muertos.MuertosTheme
import com.indagium.ui.holiday.newyear.NewYearTheme
import com.indagium.ui.holiday.pumpkin.PumpkinPatchTheme
import com.indagium.ui.holiday.ready.ReadyTheme
import com.indagium.ui.holiday.retro.RetroTheme
import com.indagium.ui.holiday.snow.FirstSnowTheme
import com.indagium.ui.holiday.thanksgiving.ThanksTheme
import com.indagium.ui.holiday.tulips.TulipsTheme
import com.indagium.ui.holiday.xmas.ClassicChristmasTheme

/** The registered themes in registry order (the tie-break when several packs' dates overlap). A new pack is added here and nowhere else. */
internal val HOLIDAY_THEMES: Map<HolidayPack, HolidayTheme> = holidayThemeRegistry(
    PumpkinPatchTheme, HauntedManorTheme, TrickOrTreatTheme, BeerYellowTheme, BeerGreenTheme, ReadyTheme, TulipsTheme, CoffeeBearTheme, MuertosTheme,
    ThanksTheme, DevDayTheme, ClassicChristmasTheme, CozyTheme, NewYearTheme, FirstSnowTheme, RetroTheme,
)

/** [themes] keyed by pack, in the order given. */
internal fun holidayThemeRegistry(vararg themes: HolidayTheme): Map<HolidayPack, HolidayTheme> = linkedMapOf<HolidayPack, HolidayTheme>().apply {
    themes.forEach { put(it.pack, it) }
}
