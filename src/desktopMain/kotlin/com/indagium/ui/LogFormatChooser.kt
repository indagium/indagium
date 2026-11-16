package com.indagium.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.indagium.model.LogTab

/**
 * One compact strip shown when format detection tied between several profiles. The tab stays open as
 * plain text until the user picks one of them or keeps plain text.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun LogFormatChooserStrip(
    state: AppState,
    tab: LogTab,
    onReturnFocus: () -> Unit,
) {
    if (tab.logFormatCandidateIds.isEmpty()) return
    val tc = tc()
    FlowRow(
        Modifier.fillMaxWidth().background(tc.p2).padding(horizontal = 10.dp, vertical = 4.dp).testTag("tab-log-format-chooser"),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
        itemVerticalAlignment = Alignment.CenterVertically,
    ) {
        AppText("This file matches several formats. Choose one:", color = tc.ts, fontSize = 10.sp, maxLines = 1)
        tab.logFormatCandidateIds.forEach { id ->
            AppButton(
                state.logFormatProfile(id)?.name ?: id,
                onClick = {
                    state.resolveLogFormatCandidate(tab.id, id)
                    onReturnFocus()
                },
                variant = ButtonVariant.Ghost,
                modifier = Modifier.testTag("tab-log-format-candidate-$id"),
                verticalPadding = 3.dp,
            )
        }
        AppButton(
            "Keep plain text",
            onClick = {
                state.resolveLogFormatCandidate(tab.id, null)
                onReturnFocus()
            },
            variant = ButtonVariant.Ghost,
            modifier = Modifier.testTag("tab-log-format-candidate-keep"),
            verticalPadding = 3.dp,
        )
    }
}
