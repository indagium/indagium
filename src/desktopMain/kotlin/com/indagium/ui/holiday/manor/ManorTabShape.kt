@file:Suppress("MagicNumber")

package com.indagium.ui.holiday.manor

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.unit.dp
import com.indagium.model.TabStripPosition
import com.indagium.ui.holiday.HolidayTabShape

/** The outer edge of each tab curves like a stone crown; the edge against the content stays nearly square. */
internal object ManorTabShape : HolidayTabShape {
    override fun shape(position: TabStripPosition): Shape = when (position) {
        TabStripPosition.TOP -> RoundedCornerShape(topStart = 16.dp, topEnd = 16.dp, bottomStart = 2.dp, bottomEnd = 2.dp)
        TabStripPosition.BOTTOM -> RoundedCornerShape(topStart = 2.dp, topEnd = 2.dp, bottomStart = 16.dp, bottomEnd = 16.dp)
        TabStripPosition.LEFT -> RoundedCornerShape(topStart = 16.dp, bottomStart = 16.dp, topEnd = 2.dp, bottomEnd = 2.dp)
        TabStripPosition.RIGHT -> RoundedCornerShape(topStart = 2.dp, bottomStart = 2.dp, topEnd = 16.dp, bottomEnd = 16.dp)
    }
}
