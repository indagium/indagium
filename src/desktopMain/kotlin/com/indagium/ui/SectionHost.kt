package com.indagium.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.testTag
import com.indagium.model.LogViewSectionId

// What the filter panel (and the highlighter section) hand to whoever lays their sections out. Every region of the panel is
// emitted through SectionHost.Section(id, chrome) { body }: the legacy ColumnSectionHost draws today's single scrolling column
// (a SectionHeader, the body, a Divider), while a tiled host (log-view tiling, a later phase) can emit each Section as its own
// tile. The panel itself stays ONE composable, so its shared state, keyboard navigation and focus requesters stay in one place.

/** The Tags | Regex switch of the Tags section: a full-width underline strip in the column host, a compact header action in a tile. */
internal class SectionModeTabs(
    val labels: List<String>,
    val selectedIndex: Int,
    val onSelect: (Int) -> Unit,
)

/**
 * What only the column host needs to reproduce the legacy panel exactly: [headerShown] false skips the SectionHeader row (Regex
 * mode has none) while a tile still has its header; [headerTestTag] wraps the header in a Box with that test tag; [wrapper] goes
 * on a Column that wraps header and body (the scroll-into-view requesters of Highlighters and Log level); [dividerAfter] draws
 * the 1dp divider between column sections (all but the last).
 */
internal class ColumnSectionOptions(
    val headerShown: Boolean = true,
    val headerTestTag: String? = null,
    val wrapper: Modifier = Modifier,
    val dividerAfter: Boolean = true,
)

/**
 * The header of a section, as data. [title], [icon], [summary], [expanded], [onToggle] and [trailing] are what a tile header
 * shows ([trailing] is also the column header's pill/count slot); [expanded] null means the section cannot be collapsed (no
 * chevron). [headerless] means no header row at all.
 *
 * [column] is for the column host only. [bodyModifier] is the panel's keyboard/focus/drop input chain, which the column host
 * leaves alone (the panel applies it once on its outer Column) and a tiled host must apply to every tile body.
 */
internal class SectionChrome(
    val title: String,
    val icon: ImageVector? = null,
    val summary: String? = null,
    val expanded: Boolean? = null,
    val onToggle: (() -> Unit)? = null,
    val trailing: (@Composable RowScope.() -> Unit)? = null,
    val headerless: Boolean = false,
    val modeTabs: SectionModeTabs? = null,
    val column: ColumnSectionOptions = ColumnSectionOptions(),
    val bodyModifier: Modifier = Modifier,
)

internal interface SectionHost {
    /** True when the host stacks its sections in one column, so the panel wraps them in its own scrolling Column. */
    val hostsInColumn: Boolean get() = true

    /** The sections whose tiles are currently visible, or null when every section is (the legacy column). */
    val visibleSections: Set<LogViewSectionId>? get() = null

    /**
     * Emits one section. [content] gets the size of the tile body, or null in the column host. A section's [content] decides
     * for itself whether it is expanded (it draws nothing when collapsed): a host composes it either way, which is what lets a
     * collapsed tile keep its body composed.
     */
    @Composable
    fun Section(id: LogViewSectionId, chrome: SectionChrome, content: @Composable (TileBodyInfo?) -> Unit)
}

/** Today's panel: the mode strip, the section header, the body and a divider, as direct children of the caller's Column. */
internal object ColumnSectionHost : SectionHost {
    @Composable
    override fun Section(id: LogViewSectionId, chrome: SectionChrome, content: @Composable (TileBodyInfo?) -> Unit) {
        if (chrome.column.wrapper == Modifier) {
            ColumnSectionRows(chrome, content)
        } else {
            Column(Modifier.fillMaxWidth().then(chrome.column.wrapper)) { ColumnSectionRows(chrome, content) }
        }
        if (chrome.column.dividerAfter) Divider()
    }
}

@Composable
private fun ColumnSectionRows(chrome: SectionChrome, content: @Composable (TileBodyInfo?) -> Unit) {
    chrome.modeTabs?.let { tabs -> UnderlineTabs(tabs.labels, tabs.selectedIndex, tabs.onSelect) }
    if (!chrome.headerless && chrome.column.headerShown) {
        val tag = chrome.column.headerTestTag
        if (tag == null) {
            SectionHeader(chrome.title, chrome.trailing, chrome.expanded, chrome.onToggle)
        } else {
            Box(Modifier.testTag(tag)) { SectionHeader(chrome.title, chrome.trailing, chrome.expanded, chrome.onToggle) }
        }
    }
    content(null)
}
