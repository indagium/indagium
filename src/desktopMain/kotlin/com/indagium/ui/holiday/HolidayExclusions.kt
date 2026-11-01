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
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.onGloballyPositioned
import com.indagium.ui.*

/**
 * The window areas the effects overlay keeps clear (in root pixels): under Calm intensity the log and Original panes, so nothing moves over
 * the text being read. Tiles report their rect; the overlay cuts [path] out of what it draws. Writes happen only when a rect really changed.
 */
internal class HolidayExclusions {
    private val rects = LinkedHashMap<Any, Rect>()
    private var cached: Path? = null

    /** Bumped on every real change; a draw lambda that reads it is redrawn when the areas change. */
    var version by mutableIntStateOf(0)
        private set

    fun set(key: Any, rect: Rect) {
        if (rect.isEmpty) {
            remove(key)
        } else if (rects.put(key, rect) != rect) {
            changed()
        }
    }

    fun remove(key: Any) {
        if (rects.remove(key) != null) changed()
    }

    /** The union of the reported areas as one path, rebuilt only after a change; null when there are none. */
    fun path(): Path? {
        if (rects.isEmpty()) return null
        return cached ?: Path().also { path -> rects.values.forEach(path::addRect) }.also { cached = it }
    }

    private fun changed() {
        cached = null
        version += 1
    }
}

/** Provided by App.kt; null where there is no overlay (previews, tests of a single view). */
internal val LocalHolidayExclusions = staticCompositionLocalOf<HolidayExclusions?> { null }

/**
 * Reports this node's bounds to the overlay as an area to keep clear while [excluded] and the active pack asks for nothing over the log
 * ([HolidayEffects.overLog] false). Otherwise (and on leaving the composition) it reports nothing.
 */
@Composable
internal fun Modifier.holidayExcluded(excluded: Boolean): Modifier {
    val holder = LocalHolidayExclusions.current
    val keepClear = excluded && LocalHoliday.current?.effects?.overLog == false
    if (holder == null || !keepClear) return this
    val key = remember { Any() }
    DisposableEffect(holder, key) { onDispose { holder.remove(key) } }
    return this.onGloballyPositioned { holder.set(key, it.boundsInRoot()) }
}
