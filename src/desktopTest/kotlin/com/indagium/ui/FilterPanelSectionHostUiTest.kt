package com.indagium.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.getBoundsInRoot
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.height
import com.indagium.model.Filter
import com.indagium.model.FilterMode
import com.indagium.model.LogAnalysis
import com.indagium.model.LogEntry
import com.indagium.model.LogLevel
import com.indagium.model.LogTab
import com.indagium.model.LogViewSectionId
import com.indagium.model.SavedFilter
import com.indagium.model.SavedFilterFolder
import org.junit.Rule
import org.junit.Test
import java.io.File
import kotlin.io.path.createTempDirectory
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/** Delegates drawing to [delegate] (the legacy column by default) and remembers which sections were emitted, in order. */
private class RecordingSectionHost(private val delegate: SectionHost = ColumnSectionHost) : SectionHost {
    val ids = mutableListOf<LogViewSectionId>()
    val chromes = mutableMapOf<LogViewSectionId, SectionChrome>()

    @Composable
    override fun Section(id: LogViewSectionId, chrome: SectionChrome, content: @Composable (TileBodyInfo?) -> Unit) {
        SideEffect {
            ids += id
            chromes[id] = chrome
        }
        delegate.Section(id, chrome, content)
    }

    /** Emission order of one pass: first occurrences only, as recomposition repeats the calls. */
    fun emitted(): List<LogViewSectionId> = ids.distinct()
}

private const val PANEL_TEST_WIDTH_DP = 360
private const val PANEL_TEST_HEIGHT_DP = 1400
private const val PANEL_WIDTH = 320f

private val ALL_SECTIONS = listOf(
    LogViewSectionId.TAGS,
    LogViewSectionId.MESSAGE_RULES,
    LogViewSectionId.LOG_COMPOSITION,
    LogViewSectionId.HIGHLIGHTERS,
    LogViewSectionId.LOG_LEVEL,
    LogViewSectionId.SEQUENCES,
    LogViewSectionId.ISSUES,
    LogViewSectionId.SAVED_FILTERS,
)

@OptIn(ExperimentalTestApi::class)
class FilterPanelSectionHostUiTest {
    @get:Rule
    val rule = createComposeRule()

    private fun tabFor(mode: FilterMode, activeTags: Set<String> = emptySet()): LogTab {
        val log = listOf(
            LogEntry(1, "00:00:00", LogLevel.I, "Alpha", "alpha", pid = 100, tid = 1),
            LogEntry(2, "00:00:01", LogLevel.D, "Beta", "beta", pid = 100, tid = 1),
        )
        return LogTab(
            id = "filter-panel-section-host-ui",
            filename = "section-host.log",
            logData = log,
            rmap = log.associateBy(LogEntry::id),
            filter = Filter(mode = mode, activeTags = activeTags),
            analysis = LogAnalysis(tagCounts = log.groupingBy(LogEntry::tag).eachCount(), pending = false),
        )
    }

    private fun withPanel(
        mode: FilterMode = FilterMode.TAGS,
        filterBarVisible: Boolean = false,
        initialHost: SectionHost? = null,
        activeTags: Set<String> = emptySet(),
        configure: (AppState) -> Unit = {},
        test: (hostOf: () -> SectionHost?, setHost: (SectionHost?) -> Unit) -> Unit,
    ) {
        val directory = createTempDirectory("filter-panel-section-host-ui").toFile()
        val tab = tabFor(mode, activeTags)
        val state = AppState(
            autosaveFile = File(directory, "state.cache"),
            notesDir = File(directory, "notes"),
            archiveCacheDir = File(directory, "archives"),
            customCommandsDir = File(directory, "commands"),
            autoExportNotes = false,
        ).also { it.tabs = listOf(tab); it.filterPanelWidth = PANEL_WIDTH; configure(it) }
        var host by mutableStateOf(initialHost)
        try {
            rule.setContent {
                CompositionLocalProvider(LocalTheme provides LIGHT_THEME) {
                    Column(Modifier.size(PANEL_TEST_WIDTH_DP.dp, PANEL_TEST_HEIGHT_DP.dp)) {
                        BoundFilterPanel(
                            state = state,
                            tab = tab,
                            width = PANEL_WIDTH,
                            liveMaxWidth = { PANEL_WIDTH },
                            filterBarVisible = filterBarVisible,
                            host = host,
                        )
                    }
                }
            }
            rule.waitForIdle()
            test({ host }, { host = it })
        } finally {
            state.close()
            directory.deleteRecursively()
        }
    }

    /** Text, test tag and bounds of every node, in tree order: what a user would see, position included. */
    private fun renderedTree(): List<String> = rule.onAllNodes(SemanticsMatcher("any") { true }, useUnmergedTree = true)
        .fetchSemanticsNodes()
        .map { node ->
            val text = node.config.getOrNull(SemanticsProperties.Text)?.joinToString("|") { it.text }
            val tag = node.config.getOrNull(SemanticsProperties.TestTag)
            "$tag/$text@${node.boundsInRoot}"
        }

    @Test
    fun emitsTheEightSectionsInPanelOrder() {
        val recording = RecordingSectionHost()
        withPanel(initialHost = recording) { _, _ ->
            assertEquals(ALL_SECTIONS, recording.emitted())
        }
    }

    @Test
    fun tagsAndMessageRulesAreAbsentWhileTheHorizontalFilterBarIsVisible() {
        val recording = RecordingSectionHost()
        withPanel(filterBarVisible = true, initialHost = recording) { _, _ ->
            assertEquals(ALL_SECTIONS - LogViewSectionId.TAGS - LogViewSectionId.MESSAGE_RULES, recording.emitted())
        }
    }

    @Test
    fun messageRulesAreAbsentInRegexMode() {
        val recording = RecordingSectionHost()
        withPanel(mode = FilterMode.KEYWORD, initialHost = recording) { _, _ ->
            assertEquals(ALL_SECTIONS - LogViewSectionId.MESSAGE_RULES, recording.emitted())
            val tags = assertNotNull(recording.chromes[LogViewSectionId.TAGS])
            assertEquals(false, tags.column.headerShown, "Regex mode has no section header, only the mode strip")
            assertEquals(1, assertNotNull(tags.modeTabs).selectedIndex)
        }
    }

    @Test
    fun chromeCarriesTitlesAndTheModeSwitch() {
        val recording = RecordingSectionHost()
        withPanel(initialHost = recording) { _, _ ->
            assertEquals(
                listOf("Tags", "Message rules", "Log composition", "Highlighters", "Log level", "Sequences", "Issues", "Saved filters"),
                ALL_SECTIONS.map { assertNotNull(recording.chromes[it]).title },
            )
            val tags = assertNotNull(recording.chromes[LogViewSectionId.TAGS])
            assertEquals(listOf("Tags", "Regex"), assertNotNull(tags.modeTabs).labels)
            assertEquals(0, tags.modeTabs.selectedIndex)
            assertEquals(false, assertNotNull(recording.chromes[LogViewSectionId.SAVED_FILTERS]).column.dividerAfter)
            assertTrue(ALL_SECTIONS.all { assertNotNull(recording.chromes[it]).bodyModifier != Modifier })
        }
    }

    @Test
    fun theColumnHostRendersExactlyWhatNoHostRenders() {
        withPanel(initialHost = null) { _, setHost ->
            val unhosted = renderedTree()
            setHost(ColumnSectionHost)
            rule.waitForIdle()
            val hosted = renderedTree()
            val titles = listOf("Tags", "Message rules", "Log composition", "Highlighters", "Log level", "Sequences", "Issues", "Saved filters")
            titles.forEach { title ->
                assertTrue(unhosted.any { it.contains("/$title@") }, "section header \"$title\" renders without a host")
            }
            assertEquals(unhosted, hosted)
        }
    }

    @Test
    fun aFlexListOutsideAContentSizedTileIsTheBoundedBoxItAlwaysWas() {
        // In the single scrolling column (no tile is sized by its content) a section's list is capped at the row setting (5 rows of 28 dp
        // by default) and scrolls inside, exactly as BoundedScrollBox did; the section is not stretched to anything.
        val tags = (0 until 40).map { "Tag$it" }.toSet()
        withPanel(initialHost = ColumnSectionHost, activeTags = tags) { _, _ ->
            val list = rule.onNodeWithTag("filter-tags-list").getBoundsInRoot()
            assertEquals(140f, list.height.value, 0.7f)
            // 40 chips are much more than that: the list scrolls (its content is taller than the box).
            assertTrue(rule.onNodeWithTag("filter-panel-tags-input").getBoundsInRoot().top >= list.bottom)
        }
    }

    private fun savedFilter(id: String, folder: String?) = SavedFilter(
        id, "Filter $id", emptySet(), emptySet(), "", false, FilterMode.TAGS, emptySet(), "", false, emptyList(), false, folderId = folder,
    )

    private fun scrollablesBelow(node: SemanticsNode): Int =
        (if (node.config.contains(SemanticsProperties.VerticalScrollAxisRange)) 1 else 0) + node.children.sumOf(::scrollablesBelow)

    @Test
    fun theLegacyColumnKeepsEachSavedFilterGroupsOwnCappedList() {
        // Outside a tile (no flex region) the saved filters are what they were on main: every group of rows caps at the rows setting and scrolls
        // inside, in the panel's one column. Only a tile makes the tree a single scroller (see LogViewTilingUiTest).
        withPanel(
            initialHost = ColumnSectionHost,
            configure = { s ->
                s.savedFilterFolders = listOf(SavedFilterFolder("folder-a", "Work"))
                s.savedFilters = (1..12).map { savedFilter("s$it", if (it <= 6) "folder-a" else null) }
            },
        ) { _, _ ->
            val tree = rule.onNodeWithTag("filter-saved-filters-tree", useUnmergedTree = true).fetchSemanticsNode()
            assertEquals(2, scrollablesBelow(tree), "one list for the folder and one for Ungrouped")
        }
    }

    @Test
    fun onlyLogLevelIsContentAndTheOtherFilterSectionsAreFlex() {
        assertEquals(TileBodySizing.CONTENT, filterTileSizing(LogViewSectionId.LOG_LEVEL))
        ALL_SECTIONS.filter { it != LogViewSectionId.LOG_LEVEL }.forEach { assertEquals(TileBodySizing.FLEX, filterTileSizing(it), "$it") }
    }
}
