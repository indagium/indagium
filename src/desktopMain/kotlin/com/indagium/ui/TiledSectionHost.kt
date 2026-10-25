package com.indagium.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.Rule
import androidx.compose.material.icons.outlined.Analytics
import androidx.compose.material.icons.outlined.BarChart
import androidx.compose.material.icons.outlined.Bookmark
import androidx.compose.material.icons.outlined.BugReport
import androidx.compose.material.icons.outlined.Highlight
import androidx.compose.material.icons.outlined.Sell
import androidx.compose.material.icons.outlined.Timeline
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.indagium.model.LogViewSectionId

// The log view's filter panel as tiles: a SectionHost (SectionHost.kt) that turns every section the FilterPanel emits into its own tile
// of the enclosing tiling surface. FilterPanel stays ONE composable (so its shared state, keyboard navigation and requesters stay in one
// place); this adapter only maps a section's chrome onto a TileSlot: title / icon / summary, the Tags | Regex switch as the Tags
// tile's header action, the section's pills (its "trailing") next to it, and a scrolling body that carries the panel's input chain.

/** The header icon of a filter tile. */
internal fun filterTileIcon(id: LogViewSectionId): ImageVector = when (id) {
    LogViewSectionId.TAGS -> Icons.Outlined.Sell
    LogViewSectionId.MESSAGE_RULES -> Icons.AutoMirrored.Outlined.Rule
    LogViewSectionId.LOG_COMPOSITION -> Icons.Outlined.Analytics
    LogViewSectionId.HIGHLIGHTERS -> Icons.Outlined.Highlight
    LogViewSectionId.LOG_LEVEL -> Icons.Outlined.BarChart
    LogViewSectionId.SEQUENCES -> Icons.Outlined.Timeline
    LogViewSectionId.ISSUES -> Icons.Outlined.BugReport
    else -> Icons.Outlined.Bookmark
}

/**
 * Emits each section of the filter panel as a tile of [scope]. [visibleSections] are the filter tiles that currently take part in the
 * layout; [collapsibleTiles] those that sit in a COLUMN split (only there does a collapsed tile shrink to its header). [toggles]
 * collects each section's own expand/collapse handler (it also clears editors and pickers), which the surface's collapse button calls
 * so a collapse from the tile header behaves exactly like the legacy section header's chevron.
 */
internal class TiledSectionHost(
    private val scope: TileScope<LogViewSectionId>,
    override val visibleSections: Set<LogViewSectionId>,
    private val collapsibleTiles: Set<LogViewSectionId>,
    private val toggles: MutableMap<LogViewSectionId, () -> Unit>,
) : SectionHost {
    override val hostsInColumn: Boolean = false

    // The first visible filter tile in panel order carries the panel's FocusRequester.
    override val focusOwner: LogViewSectionId? = LOG_VIEW_FILTER_TILES.firstOrNull { it in visibleSections }

    @Composable
    override fun Section(id: LogViewSectionId, chrome: SectionChrome, content: @Composable (TileBodyInfo?) -> Unit) {
        val onToggle = chrome.onToggle
        SideEffect {
            if (onToggle != null) toggles[id] = onToggle else toggles.remove(id)
        }
        // The section's own pills (counts, list toggles) are controls, so they stay; a summary next to them would repeat them.
        val summary = chrome.summary.takeIf { chrome.trailing == null }
        scope.Tile(
            id,
            TileSlot(
                icon = chrome.icon ?: filterTileIcon(id),
                title = chrome.title,
                summary = summary,
                headerAction = headerAction(chrome),
                collapsible = id in collapsibleTiles,
                body = { info -> SectionTileBody(chrome, info, content) },
            ),
        )
    }

    private fun headerAction(chrome: SectionChrome): (@Composable RowScope.() -> Unit)? {
        val trailing = chrome.trailing
        val tabs = chrome.modeTabs
        if (trailing == null && tabs == null) return null
        return {
            trailing?.invoke(this)
            if (tabs != null) {
                SegmentedControl(
                    options = tabs.labels,
                    selectedIndices = setOf(tabs.selectedIndex),
                    onToggle = tabs.onSelect,
                    segmentHeight = 22.dp,
                    segmentFontSize = 11.sp,
                    segmentHorizontalPadding = 8.dp,
                    modifier = Modifier.testTag("tile-mode-tabs"),
                )
            }
        }
    }
}

// A tile body: the section's content in a scrolling column, under the panel's input chain (keys, drops, focus group, focus border).
@Composable
private fun SectionTileBody(chrome: SectionChrome, info: TileBodyInfo, content: @Composable (TileBodyInfo?) -> Unit) {
    Box(Modifier.fillMaxSize().then(chrome.bodyModifier)) {
        ScrollColumnWithBar(Modifier.fillMaxSize(), endGutter = 0.dp) { content(info) }
    }
}
