package com.indagium.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.selection.selectable
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.indagium.model.LogFileSection
import com.indagium.model.LogTab
import com.indagium.model.TabContentState

private val SECTION_GROUP_HEIGHT = 24.dp
private val SECTION_ROW_HEIGHT = 28.dp
private val SECTION_INSET = 12.dp

/** The Sections tile's header button: how many empty sections it would show, or "Hide empty". */
internal fun showEmptySectionsLabel(showEmpty: Boolean, emptyCount: Int): String =
    if (showEmpty) "Hide empty" else "Show $emptyCount empty"

/** The Sections tile's header action: reveals or hides the sections that hold no records. Nothing when no section is empty. */
@Composable
internal fun RowScope.LogSectionsHeaderAction(tab: LogTab, showEmpty: Boolean, onToggle: () -> Unit) {
    val emptyCount = tab.logSections.count { it.isEmptySection }
    if (emptyCount == 0) return
    AppButton(
        showEmptySectionsLabel(showEmpty, emptyCount),
        onClick = onToggle,
        horizontalPadding = 8.dp,
        modifier = Modifier.testTag("json-sections-show-empty-${tab.id}"),
    )
}

/**
 * JSON sections are separate logs; selecting one loads only that section into the viewer. Sections that hold no records stay out of the
 * list unless [showEmpty] (the tile's header button) says otherwise; the selected section is always listed.
 */
@Composable
internal fun LogSections(state: AppState, tab: LogTab, showEmpty: Boolean, modifier: Modifier = Modifier) {
    val colors = tc()
    val scroll = rememberLazyListState()
    val visibleSections = tab.logSections.filter { showEmpty || !it.isEmptySection || it.id == tab.selectedLogSectionId }
    Column(modifier.fillMaxWidth().fillMaxHeight().testTag("log-sections-${tab.id}")) {
        Box(Modifier.weight(1f).fillMaxWidth()) {
            LazyColumn(
                Modifier.fillMaxWidth().padding(end = 8.dp).testTag("json-section-list-${tab.id}"),
                state = scroll,
            ) {
                visibleSections.groupBy { it.group }.forEach { (group, sections) ->
                    item(key = "group:$group") {
                        Box(Modifier.fillMaxWidth().heightIn(min = SECTION_GROUP_HEIGHT), contentAlignment = Alignment.CenterStart) {
                            AppText(
                                group, modifier = Modifier.padding(start = SECTION_INSET), color = colors.ts, fontSize = 10.sp,
                                fontWeight = FontWeight.SemiBold, maxLines = 2,
                            )
                        }
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
    HoverBox(
        modifier = Modifier.fillMaxWidth()
            .selectable(
                selected = selected, enabled = tab.contentState == TabContentState.LOADED, role = Role.Tab,
                onClick = { state.selectLogSection(tab.id, section.id) },
            ).testTag("json-section-${section.id}"),
        baseBg = if (selected) colors.sl else Color.Transparent,
        hoverBg = if (selected) colors.sl else colors.hv,
    ) {
        Row(
            Modifier.fillMaxWidth().heightIn(min = SECTION_ROW_HEIGHT).padding(horizontal = SECTION_INSET, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Column(Modifier.weight(1f)) {
                AppText(
                    section.title, color = colors.tx, fontSize = 12.sp,
                    fontWeight = if (selected) FontWeight.Medium else FontWeight.Normal,
                    maxLines = 2, overflow = TextOverflow.Ellipsis,
                )
                val status = when {
                    section.unavailableReason != null -> "Unavailable"
                    section.decodeNotice != null -> "Partial records"
                    else -> null
                }
                if (status != null) AppText(status, color = colors.ts, fontSize = 11.sp)
            }
            if (section.unavailableReason == null) {
                val count = when {
                    section.decodeNotice == null -> section.recordCount.toString()
                    section.recordCount == 0 -> "Unknown"
                    else -> "${section.recordCount}+"
                }
                AppText(count, color = colors.ts, fontSize = 11.sp, fontFamily = FontFamily.Monospace)
            }
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
