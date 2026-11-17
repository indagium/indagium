package com.indagium.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * First line of the Tags tile when the tab hides the TAG column the filter still matches (a metadata JSON tab, whose visible SOURCE
 * column is another field): says so and offers the per-tab "Show TAG" action. The tile's header has no room for it at its usual width.
 */
@Composable
internal fun TagColumnHiddenNote(onShow: () -> Unit) {
    val colors = tc()
    Row(
        Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp).testTag("tags-tag-column-note"),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        AppText("TAG column hidden", color = colors.td, fontSize = 11.sp, modifier = Modifier.weight(1f, fill = false))
        AppButton(
            "Show",
            onClick = onShow,
            horizontalPadding = 8.dp,
            tooltip = "Show the TAG column, which this filter matches",
            modifier = Modifier.testTag("tags-tag-column-show"),
        )
    }
}
