package com.indagium.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.selection.selectable
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.indagium.model.LogFileSection
import com.indagium.model.LogTab
import com.indagium.model.TabContentState

/** JSON sections are separate logs; selecting one loads only that section into the viewer. */
@Composable
internal fun LogSections(state: AppState, tab: LogTab, modifier: Modifier = Modifier) {
    val colors = tc()
    val scroll = rememberLazyListState()
    var showEmpty by remember(tab.id) { mutableStateOf(false) }
    val emptyCount = tab.logSections.count { it.isEmptySection }
    val visibleSections = tab.logSections.filter { showEmpty || !it.isEmptySection || it.id == tab.selectedLogSectionId }
    Column(modifier.fillMaxWidth().fillMaxHeight().background(colors.p2).testTag("log-sections-${tab.id}")) {
        if (emptyCount > 0) CheckRow(
            showEmpty, { showEmpty = !showEmpty }, modifier = Modifier.testTag("json-sections-show-empty-${tab.id}"),
        ) {
            AppText("Show empty ($emptyCount)", color = colors.ts, fontSize = 10.sp)
        }
        Box(Modifier.weight(1f).fillMaxWidth()) {
            LazyColumn(
                Modifier.fillMaxWidth().padding(end = 8.dp).testTag("json-section-list-${tab.id}"),
                state = scroll, verticalArrangement = Arrangement.spacedBy(2.dp),
            ) {
                visibleSections.groupBy { it.group }.forEach { (group, sections) ->
                    item(key = "group:$group") {
                        AppText(group, modifier = Modifier.padding(10.dp, 6.dp), color = colors.ts, fontSize = 10.sp, maxLines = 2)
                    }
                    items(sections, key = { it.id }) { section -> LogSectionRow(state, tab, section) }
                }
            }
            ThinVerticalScrollbar(scroll, Modifier.align(Alignment.CenterEnd).fillMaxHeight())
        }
    }
}

@Composable
private fun LogSectionRow(state: AppState, tab: LogTab, section: LogFileSection) {
    val colors = tc()
    val selected = section.id == tab.selectedLogSectionId
    Row(
        Modifier.fillMaxWidth().background(if (selected) colors.ac.copy(alpha = .16f) else colors.p2)
            .selectable(
                selected = selected, enabled = tab.contentState == TabContentState.LOADED, role = Role.Tab,
                onClick = { state.selectLogSection(tab.id, section.id) },
            ).padding(horizontal = 12.dp, vertical = 8.dp).testTag("json-section-${section.id}"),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Column(Modifier.weight(1f)) {
            AppText(
                section.title, color = if (selected) colors.ac else colors.tx, fontSize = 11.sp,
                maxLines = 2, overflow = TextOverflow.Ellipsis,
            )
            val status = when {
                section.unavailableReason != null -> "Unavailable"
                section.decodeNotice != null -> "Partial records"
                else -> null
            }
            if (status != null) AppText(status, color = colors.ts, fontSize = 10.sp)
        }
        if (section.unavailableReason == null) {
            val count = when {
                section.decodeNotice == null -> section.recordCount.toString()
                section.recordCount == 0 -> "Unknown"
                else -> "${section.recordCount}+"
            }
            AppText(count, color = colors.ts, fontSize = 10.sp)
        }
    }
}

/** An unknown count is not an empty section. */
internal val LogFileSection.isEmptySection: Boolean
    get() = recordCount == 0 && unavailableReason == null && decodeNotice == null

internal fun LogTab.hasSectionNavigation(): Boolean {
    val meaningful = logSections.count { !it.isEmptySection }
    val selectedEmpty = logSections.any { it.id == selectedLogSectionId && it.isEmptySection }
    return meaningful > 1 || (meaningful > 0 && selectedEmpty && logSections.size > 1)
}
