package com.indagium.ui.holiday

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.onGloballyPositioned

/** Bounds in root pixels of visible tabs and tiles on which a seasonal decoration may land. */
internal class HolidayLandingAreas {
    private val rectangles = LinkedHashMap<Any, Rect>()
    var version by mutableIntStateOf(0)
        private set

    fun set(key: Any, rect: Rect) {
        if (rect.isEmpty) remove(key) else if (rectangles.put(key, rect) != rect) version++
    }

    fun remove(key: Any) { if (rectangles.remove(key) != null) version++ }

    fun bounds(): List<Rect> = rectangles.values.toList()
}

internal val LocalHolidayLandingAreas = staticCompositionLocalOf<HolidayLandingAreas?> { null }

@Composable
internal fun Modifier.holidayLandingAnchor(): Modifier {
    val holder = LocalHolidayLandingAreas.current ?: return this
    val active = LocalHoliday.current?.effects?.activeEffects?.any { it is HolidayLandingEffect } == true
    if (!active) return this
    val key = remember { Any() }
    DisposableEffect(holder, key) { onDispose { holder.remove(key) } }
    return onGloballyPositioned { holder.set(key, it.boundsInRoot()) }
}
