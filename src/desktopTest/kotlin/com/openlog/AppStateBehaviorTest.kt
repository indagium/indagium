package com.openlog

import androidx.compose.ui.graphics.Color
import com.openlog.debug.ControlServer
import com.openlog.model.AiProviderKind
import com.openlog.model.AiProviderProfile
import com.openlog.model.AnnBlock
import com.openlog.model.AnnotationLogBlockStyle
import com.openlog.model.Annotations
import com.openlog.model.AppSettings
import com.openlog.model.CopyMaskRule
import com.openlog.model.CrashCategory
import com.openlog.model.CtrlFTarget
import com.openlog.model.CtxMenuState
import com.openlog.model.DEFAULT_KEYWORD_HIGHLIGHT_COLOR
import com.openlog.model.Filter
import com.openlog.model.FilterMode
import com.openlog.model.Highlighter
import com.openlog.model.LogAnalysis
import com.openlog.model.LogEntry
import com.openlog.model.LogItem
import com.openlog.model.LogLevel
import com.openlog.model.ManualCollapseBlock
import com.openlog.model.ManualCollapseDirection
import com.openlog.model.SequenceDef
import com.openlog.model.SourceFolderInfo
import com.openlog.model.SourceLogConfiguration
import com.openlog.model.SourceWrapperRule
import com.openlog.model.ThemePreset
import com.openlog.ui.AppState
import com.openlog.ui.DesktopStorage
import com.openlog.ui.FilterSearchRequest
import com.openlog.ui.HL_COLORS
import com.openlog.ui.ImportFilterAction
import com.openlog.ui.ManualCollapseAvailability
import com.openlog.ui.SEQ_COLORS
import com.openlog.ui.SettingsSection
import com.openlog.ui.SplitMode
import com.openlog.ui.annotationsToken
import com.openlog.ui.blockOrderDuringDrag
import com.openlog.ui.consumeFilterSearchRequest
import com.openlog.ui.cumulativeBlockOffsets
import com.openlog.ui.emptyWorkspaceTab
import com.openlog.ui.filterSearchTargetForTab
import com.openlog.ui.manualCollapseAvailability
import com.openlog.ui.maskWordForCopy
import com.openlog.ui.mkTab
import com.openlog.ui.persistedSnapshot
import com.openlog.ui.recentFilesForMenu
import com.openlog.ui.sequenceOrderDuringDrag
import com.openlog.ui.sequenceRenderY
import com.openlog.ui.sequenceRowBaseBackground
import com.openlog.ui.shouldSyncSequenceVisualOrder
import com.openlog.ui.summarizeItems
import com.openlog.ui.tabDisplayLabel
import com.openlog.ui.themeColors
import com.openlog.utils.SPLIT_PROMPT_BYTES
import com.openlog.utils.buildMd
import com.openlog.utils.computeItems
import com.openlog.utils.listArchiveLogCandidates
import kotlinx.coroutines.delay
import java.io.File
import java.io.RandomAccessFile
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlin.io.path.createTempDirectory
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotEquals
import kotlin.test.assertSame
import kotlin.test.assertTrue

class AppStateBehaviorTest {
    @Test
    fun startsWithNoOpenTabs() {
        val state = AppState()

        assertTrue(state.tabs.isEmpty())
        assertEquals(null, state.activeTab())
        assertEquals(ThemePreset.LIGHT, state.settings.theme)
        assertFalse(state.settings.openUnfilteredOnCtrlF)
    }

    @Test
    fun canCloseTheLastTab() {
        val state = AppState()
        state.tabs = listOf(mkTab("log", "test.log", listOf(LogEntry(1, "10:00:00.000", LogLevel.E, "App", "Boom"))))
        state.activeTabId = "log"
        state.compareTabId = "log"

        state.closeTab("log")

        assertTrue(state.tabs.isEmpty())
        assertEquals(null, state.activeTab())
        assertEquals("", state.activeTabId)
        assertEquals("", state.compareTabId)
    }

    @Test
    fun compareModeRequiresAtLeastTwoOpenTabs() {
        val state = AppState()
        state.tabs = listOf(mkTab("one", "one.log", emptyList()))
        state.activeTabId = "one"

        state.updateCompareMode(true)

        assertFalse(state.canCompare)
        assertFalse(state.compareMode)

        state.tabs += mkTab("two", "two.log", emptyList())
        state.updateCompareMode(true)

        assertTrue(state.canCompare)
        assertTrue(state.compareMode)
    }

    @Test
    fun closingToOneTabExitsCompareMode() {
        val state = AppState()
        state.tabs = listOf(mkTab("one", "one.log", emptyList()), mkTab("two", "two.log", emptyList()))
        state.activeTabId = "one"
        state.updateCompareMode(true)

        state.closeTab("two")

        assertFalse(state.canCompare)
        assertFalse(state.compareMode)
    }

    @Test
    fun closeTabScopeActionsKeepExpectedTabsAndActiveTab() {
        val state = AppState()
        state.tabs = (1..5).map { idx -> mkTab("t$idx", "tab-$idx.log", emptyList()) }
        state.activeTabId = "t3"
        state.compareTabId = "t4"

        state.closeTabsToRight("t2")

        assertEquals(listOf("t1", "t2"), state.tabs.map { it.id })
        assertEquals("t2", state.activeTabId)
        assertEquals("t1", state.compareTabId)

        state.tabs = (1..5).map { idx -> mkTab("t$idx", "tab-$idx.log", emptyList()) }
        state.activeTabId = "t3"
        state.compareTabId = "t2"

        state.closeTabsToLeft("t4")

        assertEquals(listOf("t4", "t5"), state.tabs.map { it.id })
        assertEquals("t4", state.activeTabId)
        assertEquals("t4", state.compareTabId)

        state.tabs = (1..4).map { idx -> mkTab("t$idx", "tab-$idx.log", emptyList()) }
        state.activeTabId = "t2"

        state.closeOtherTabs("t3")

        assertEquals(listOf("t3"), state.tabs.map { it.id })
        assertEquals("t3", state.activeTabId)

        state.closeAllTabs()

        assertTrue(state.tabs.isEmpty())
        assertEquals("", state.activeTabId)
    }

    @Test
    fun activatingVisibleTabDoesNotReorderTabs() {
        val state = AppState()
        state.tabs = (1..5).map { idx ->
            mkTab(
                "t$idx",
                "tab-$idx.log",
                listOf(LogEntry(1, "10:00:00.000", LogLevel.I, "Tag$idx", "message $idx")),
            )
        }
        state.activeTabId = "t4"

        state.activateTab("t5")

        assertEquals("t5", state.activeTabId)
        assertEquals((1..5).map { "t$it" }, state.tabs.map { it.id })
        assertEquals("Tag5", state.activeTab()?.logData?.single()?.tag)
    }

    @Test
    fun activatingOverflowTabPromotesExistingTabToEnd() {
        val state = AppState()
        val hidden = mkTab(
            "t1",
            "tab-1.log",
            listOf(LogEntry(1, "10:00:00.000", LogLevel.I, "Hidden", "keep state")),
        ).copy(selected = setOf(1))
        state.tabs = listOf(hidden) + (2..5).map { idx ->
            mkTab(
                "t$idx",
                "tab-$idx.log",
                listOf(LogEntry(1, "10:00:00.000", LogLevel.I, "Tag$idx", "message $idx")),
            )
        }
        state.activeTabId = "t5"

        state.activateOverflowTab("t1")

        assertEquals("t1", state.activeTabId)
        assertEquals(listOf("t2", "t3", "t4", "t5", "t1"), state.tabs.map { it.id })
        assertSame(hidden, state.tabs.last())
        assertEquals(setOf(1), state.activeTab()?.selected)
    }

    @Test
    fun expandAllUsesFilteredVisibleSequenceGroupIds() {
        val logs = listOf(
            LogEntry(1, "10:00:00.000", LogLevel.I, "Drop", "drop start"),
            LogEntry(2, "10:00:00.100", LogLevel.I, "Drop", "drop child"),
            LogEntry(3, "10:00:00.200", LogLevel.I, "Keep", "keep start"),
            LogEntry(4, "10:00:00.300", LogLevel.I, "Keep", "keep child"),
        )
        val seqs = listOf(
            SequenceDef("drop", "drop start", priority = 1, color = Color.Red, tag = "Drop"),
            SequenceDef("keep", "keep start", priority = 2, color = Color.Blue, tag = "Keep"),
        )
        val state = AppState()
        state.tabs = listOf(
            mkTab("log", "test.log", logs).copy(
                filter = Filter(sequences = seqs, mode = FilterMode.TAGS, activeTags = setOf("Keep")),
            ),
        )

        state.expandAll("log")

        val header = computeItems(state.tabs.single(), applyFilter = true)
            .filterIsInstance<LogItem.SeqHeader>()
            .single()
        assertEquals("sg_keep_3", header.gid)
        assertTrue(header.expanded)
    }

    @Test
    fun expandAllAlsoExpandsOriginalPanelSequencesWhenUnfilteredIsShown() {
        val logs = listOf(
            LogEntry(1, "10:00:00.000", LogLevel.I, "Drop", "drop start"),
            LogEntry(2, "10:00:00.100", LogLevel.I, "Drop", "drop child"),
            LogEntry(3, "10:00:00.200", LogLevel.I, "Keep", "keep start"),
            LogEntry(4, "10:00:00.300", LogLevel.I, "Keep", "keep child"),
        )
        val seqs = listOf(
            SequenceDef("drop", "drop start", priority = 1, color = Color.Red, tag = "Drop"),
            SequenceDef("keep", "keep start", priority = 2, color = Color.Blue, tag = "Keep"),
        )
        val state = AppState()
        state.tabs = listOf(
            mkTab("log", "test.log", logs).copy(
                filter = Filter(sequences = seqs, mode = FilterMode.TAGS, activeTags = setOf("Keep")),
                showUnfiltered = true,
            ),
        )

        state.expandAll("log")

        val originalHeaders = computeItems(state.tabs.single(), applyFilter = false)
            .filterIsInstance<LogItem.SeqHeader>()
        assertEquals(listOf("sg_drop_1", "sg_keep_3"), originalHeaders.map { it.gid })
        assertTrue(originalHeaders.all { it.expanded })
    }

    @Test
    fun expandAllExpandsSequencesAfterManualCollapseToStart() {
        val logs = listOf(
            LogEntry(1, "10:00:00.000", LogLevel.I, "App", "intro"),
            LogEntry(2, "10:00:00.100", LogLevel.I, "App", "manual anchor"),
            LogEntry(3, "10:00:00.200", LogLevel.I, "Seq", "flow start"),
            LogEntry(4, "10:00:00.300", LogLevel.I, "Seq", "flow child"),
        )
        val block = com.openlog.model.ManualCollapseBlock(
            id = "m1",
            anchorId = 2,
            direction = com.openlog.model.ManualCollapseDirection.TO_START,
        )
        val seq = SequenceDef("flow", "flow start", priority = 1, color = Color.Blue, tag = "Seq")
        val state = AppState()
        state.tabs = listOf(
            mkTab("log", "test.log", logs).copy(
                filter = Filter(sequences = listOf(seq)),
                manualBlocks = listOf(block),
            ),
        )

        state.expandAll("log")

        val headers = computeItems(state.tabs.single(), applyFilter = true)
            .filterIsInstance<LogItem.SeqHeader>()
        assertEquals(listOf("sg_flow_3"), headers.map { it.gid })
        assertTrue(headers.single().expanded)
    }

    @Test
    fun expandAllExpandsSequenceInsideManualCollapseToStart() {
        val logs = listOf(
            LogEntry(1, "10:00:00.000", LogLevel.I, "Seq", "flow start"),
            LogEntry(2, "10:00:00.100", LogLevel.I, "Seq", "flow child"),
            LogEntry(3, "10:00:00.200", LogLevel.I, "App", "manual anchor"),
        )
        val block = com.openlog.model.ManualCollapseBlock(
            id = "m1",
            anchorId = 3,
            direction = com.openlog.model.ManualCollapseDirection.TO_START,
        )
        val seq = SequenceDef("flow", "flow start", priority = 1, color = Color.Blue, tag = "Seq")
        val state = AppState()
        state.tabs = listOf(
            mkTab("log", "test.log", logs).copy(
                filter = Filter(sequences = listOf(seq)),
                manualBlocks = listOf(block),
            ),
        )

        state.expandAll("log")

        val items = computeItems(state.tabs.single(), applyFilter = true)
        val headers = items.filterIsInstance<LogItem.SeqHeader>()
        assertEquals(listOf("sg_flow_1"), headers.map { it.gid })
        assertTrue(headers.single().expanded)
        assertEquals(listOf(2), items.filterIsInstance<LogItem.Row>().map { it.entry.id })
    }

    @Test
    fun expandAllExpandsSequenceWhenManualCollapseAnchorStartsSequence() {
        val logs = listOf(
            LogEntry(1, "10:00:00.000", LogLevel.I, "App", "intro"),
            LogEntry(2, "10:00:00.100", LogLevel.I, "Seq", "flow start"),
            LogEntry(3, "10:00:00.200", LogLevel.I, "Seq", "flow child"),
        )
        val block = com.openlog.model.ManualCollapseBlock(
            id = "m1",
            anchorId = 2,
            direction = com.openlog.model.ManualCollapseDirection.TO_START,
        )
        val seq = SequenceDef("flow", "flow start", priority = 1, color = Color.Blue, tag = "Seq")
        val state = AppState()
        state.tabs = listOf(
            mkTab("log", "test.log", logs).copy(
                filter = Filter(sequences = listOf(seq)),
                manualBlocks = listOf(block),
            ),
        )

        state.expandAll("log")

        val headers = computeItems(state.tabs.single(), applyFilter = true)
            .filterIsInstance<LogItem.SeqHeader>()
        assertEquals(listOf("sg_flow_2"), headers.map { it.gid })
        assertTrue(headers.single().expanded)
    }

    @Test
    fun expandAllDoesNotPreExpandSequencesWhenSequenceGroupingIsOff() {
        val logs = listOf(
            LogEntry(1, "10:00:00.000", LogLevel.I, "Keep", "keep start"),
            LogEntry(2, "10:00:00.100", LogLevel.I, "Keep", "keep child"),
        )
        val seq = SequenceDef("keep", "keep start", priority = 1, color = Color.Blue, tag = "Keep")
        val state = AppState()
        state.tabs = listOf(
            mkTab("log", "test.log", logs).copy(filter = Filter(sequences = listOf(seq), seqOn = false)),
        )

        state.expandAll("log")
        state.toggleSeq("log")

        val header = computeItems(state.tabs.single(), applyFilter = true)
            .filterIsInstance<LogItem.SeqHeader>()
            .single()
        assertEquals("sg_keep_1", header.gid)
        assertTrue(!header.expanded)
    }

    @Test
    fun sequenceExpansionSurvivesFilteringOutEarlierRows() {
        val logs = listOf(
            LogEntry(1, "10:00:00.000", LogLevel.I, "Drop", "flow start"),
            LogEntry(2, "10:00:00.100", LogLevel.I, "Drop", "flow child"),
            LogEntry(3, "10:00:00.200", LogLevel.I, "Keep", "flow start"),
            LogEntry(4, "10:00:00.300", LogLevel.I, "Keep", "flow child"),
        )
        val seq = SequenceDef("flow", "flow start", priority = 1, color = Color.Blue)
        val state = AppState()
        state.tabs = listOf(mkTab("log", "test.log", logs).copy(filter = Filter(sequences = listOf(seq))))
        val keepHeaderBeforeFilter = computeItems(state.tabs.single(), applyFilter = true)
            .filterIsInstance<LogItem.SeqHeader>()
            .first { it.entry.id == 3 }
        state.toggleGroup("log", keepHeaderBeforeFilter.gid)

        state.toggleExcludeTag("log", "Drop")

        val keepHeaderAfterFilter = computeItems(state.tabs.single(), applyFilter = true)
            .filterIsInstance<LogItem.SeqHeader>()
            .single { it.entry.id == 3 }
        assertTrue(keepHeaderAfterFilter.expanded)
    }

    @Test
    fun addAnnotationStoresStructuredLogReference() {
        val state = AppState()
        state.tabs = listOf(mkTab("log", "test.log", listOf(LogEntry(1, "10:00:00.000", LogLevel.E, "App", "Boom"))))

        state.confirmAddAnn("log", "log", listOf(1), "Investigate", null)

        val block = assertIs<AnnBlock.LogRef>(state.tabs.single().annotations.blocks.single())
        assertEquals(listOf(1), block.logIds)
        assertEquals("Investigate", block.caption)
    }

    @Test
    fun consecutiveLogRefAnnotationsGetDistinctIdsAndDoNotCrossContaminateReferencedLines() {
        val state = AppState()
        state.tabs = listOf(
            mkTab(
                "log", "test.log",
                listOf(
                    LogEntry(1, "10:00:00.000", LogLevel.E, "App", "first"),
                    LogEntry(2, "10:00:00.001", LogLevel.E, "App", "second"),
                ),
            ),
        )

        // Regression: the LogRef id used to be seeded from System.currentTimeMillis(), so two
        // annotations confirmed within the same millisecond got identical ids and clobbered each
        // other's data (only one note's lines/caption would show, or lines leaked between notes).
        state.confirmAddAnn("log", "log", listOf(1), "first note", null)
        state.confirmAddAnn("log", "log", listOf(2), "second note", null)

        val blocks = state.tabs.single().annotations.blocks.filterIsInstance<AnnBlock.LogRef>()
        assertEquals(2, blocks.size)
        assertTrue(blocks[0].id != blocks[1].id)
        assertEquals(listOf(1), blocks[0].logIds)
        assertEquals("first note", blocks[0].caption)
        assertEquals(listOf(2), blocks[1].logIds)
        assertEquals("second note", blocks[1].caption)
    }

    @Test
    fun newLogTabsUseFromFilenamePrefix() {
        val tab = mkTab("log", "LOGCAT_example.log", emptyList())

        assertEquals("From LOGCAT_example.log", tab.annotations.prefix)
    }

    @Test
    fun markdownCanWrapLogsInJiraJavaCodeBlock() {
        val tab = mkTab(
            "log",
            "LOGCAT_example.log",
            listOf(
                LogEntry(1, "10:00:00.000", LogLevel.I, "App", "line 1"),
                LogEntry(2, "10:00:00.001", LogLevel.E, "App", "line 2"),
            ),
        ).copy(
            annotations = com.openlog.model.Annotations(
                prefix = "From LOGCAT_example.log",
                suffix = "Check later",
                blocks = listOf(AnnBlock.LogRef("r1", listOf(1, 2), "Investigate")),
            ),
        )

        val md = buildMd(tab, AppSettings(annotationLogBlockStyle = AnnotationLogBlockStyle.JIRA_JAVA))

        assertTrue(md.contains("From LOGCAT_example.log"))
        assertTrue(md.contains("Investigate"))
        assertTrue(md.contains("{code:java}\n10:00:00.000  I/App  line 1\n10:00:00.001  E/App  line 2\n{code}"))
        assertTrue(md.contains("Check later"))
    }

    @Test
    fun markdownUsesConfiguredSourcePrefixLabel() {
        val tab = mkTab(
            "log",
            "main.log",
            listOf(LogEntry(1, "10:00:00.000", LogLevel.I, "App", "line 1")),
        ).copy(
            annotations = com.openlog.model.Annotations(
                prefix = "Evidence main.log",
                blocks = listOf(
                    AnnBlock.LogRef(
                        id = "r1",
                        logIds = listOf(1),
                        caption = "Cross file",
                        sourceFilename = "LOGCAT_example.log",
                    ),
                ),
            ),
        )

        val md = buildMd(tab, AppSettings(annotationPrefixLabel = "Evidence"))

        assertTrue(md.contains("Evidence main.log"))
        assertTrue(md.contains("Evidence LOGCAT_example.log"))
    }

    @Test
    fun markdownCanNumberAnnotationBlocksButNotPrefixOrNextSteps() {
        val tab = mkTab(
            "log",
            "LOGCAT_example.log",
            listOf(LogEntry(1, "10:00:00.000", LogLevel.I, "App", "line 1")),
        ).copy(
            annotations = com.openlog.model.Annotations(
                prefix = "From LOGCAT_example.log",
                suffix = "Next action",
                blocks = listOf(
                    AnnBlock.Note("n1", "Context"),
                    AnnBlock.LogRef("r1", listOf(1), "Evidence"),
                ),
            ),
        )

        val md = buildMd(tab, AppSettings(numberAnnotationBlocks = true))

        assertTrue(md.startsWith("From LOGCAT_example.log\n\n1. Context"))
        assertTrue(md.contains("\n\n2. Evidence"))
        assertTrue(md.endsWith("Next action"))
    }

    @Test
    fun savedFiltersRoundTripAllFilterFields() {
        val state = AppState()
        state.addTab()
        val tabId = state.tabs.single().id

        state.setFilterMode(tabId, FilterMode.TAGS)
        state.setKwInTag(tabId, "timeout")
        state.toggleKwInTagRx(tabId)
        state.addPkgPrefix(tabId, "com.example")
        state.addExcludePkgPrefix(tabId, "com.example.noisy")
        state.setPidTidFilter(tabId, "1234,5678")
        state.saveFilter(tabId, "network")
        state.clearFilter(tabId)

        state.loadFilter(tabId, state.savedFilters.single())

        val filter = state.tabs.single().filter
        assertEquals("timeout", filter.kwInTag)
        assertTrue(filter.kwInTagRegex)
        assertEquals(setOf("com.example"), filter.pkgPrefixes)
        assertEquals(setOf("com.example.noisy"), filter.excludePkgPrefixes)
        assertEquals("1234,5678", filter.pidTidFilter)
    }

    @Test
    fun savedFiltersRoundTripSequences() {
        val state = AppState()
        state.addTab()
        val tabId = state.tabs.single().id
        state.addSequence("start", false, Color.Red, "StartTag", "end", false, "EndTag")
        state.toggleSequence(state.sequences.single().id)

        state.saveFilter(tabId, "with sequences")
        state.clearFilter(tabId)
        state.loadFilter(tabId, state.savedFilters.single())

        val sequence = state.sequences.single()
        assertEquals("start", sequence.matchText)
        assertEquals("StartTag", sequence.tag)
        assertEquals("end", sequence.endMatchText)
        assertEquals("EndTag", sequence.endTag)
        assertEquals(false, sequence.enabled)
    }

    @Test
    fun savedFilterLibraryMovesFavoritesAndReordersWithinFolder() {
        val state = AppState()
        state.addTab()
        val tabId = state.tabs.single().id
        state.createSavedFilterFolder("QA")
        val folderId = state.savedFilterFolders.single().id

        state.saveFilter(tabId, "first", folderId)
        state.saveFilter(tabId, "second", folderId)
        val first = state.savedFilters.first { it.name == "first" }
        val second = state.savedFilters.first { it.name == "second" }

        state.toggleSavedFilterFavorite(second.id)
        state.reorderSavedFilterWithinFolder(second.id, 0)

        assertEquals(listOf(second.id, first.id), state.savedFilters.filter { it.folderId == folderId }.map { it.id })
        assertTrue(state.savedFilters.first { it.id == second.id }.favorite)

        state.requestDeleteSavedFilterFolder(folderId)
        state.confirmDeleteSavedFilterFolder()

        assertTrue(state.savedFilterFolders.isEmpty())
        assertTrue(state.savedFilters.all { it.folderId == null })
    }

    @Test
    fun exportedSavedFilterLibraryPreservesFolderAndFavorite() {
        val source = AppState()
        source.addTab()
        source.createSavedFilterFolder("Release")
        val folderId = source.savedFilterFolders.single().id
        source.saveFilter(source.tabs.single().id, "production", folderId)
        source.toggleSavedFilterFavorite(source.savedFilters.single().id)

        val target = AppState()
        target.importFilters(source.exportFilters())

        assertEquals(listOf("Release"), target.savedFilterFolders.map { it.name })
        assertEquals("Release", target.savedFilterFolders.single { it.id == target.savedFilters.single().folderId }.name)
        assertTrue(target.savedFilters.single().favorite)
    }

    @Test
    fun exportedFiltersRoundTripSequences() {
        val source = AppState()
        source.addTab()
        source.addSequence("start", true, Color.Red, "StartTag", "end", false, "EndTag")
        source.saveFilter(source.tabs.single().id, "with sequences")

        val target = AppState()
        target.importFilters(source.exportFilters())
        target.addTab()
        target.loadFilter(target.tabs.single().id, target.savedFilters.single())

        val sequence = target.sequences.single()
        assertEquals("start", sequence.matchText)
        assertTrue(sequence.isRegex)
        assertEquals("StartTag", sequence.tag)
        assertEquals("end", sequence.endMatchText)
        assertEquals("EndTag", sequence.endTag)
    }

    @Test
    fun exportedFiltersRoundTripSpecialCharacters() {
        val source = AppState()
        source.addTab()
        val tabId = source.tabs.single().id
        source.setKw(tabId, "hello, \"quoted\"\nnext")
        source.addHl(tabId, "warn,\"quoted\"", false, Color.Yellow)
        source.addPkgPrefix(tabId, "com.example,odd")
        source.addExcludePkgPrefix(tabId, "com.example,odd.noisy")
        source.addMessageRule(
            tabId,
            include = true,
            pattern = "value=(\"a,b\")\\s+next",
            regex = true,
            tag = "Tag,With,Comma",
            packagePrefix = "",
        )
        source.saveFilter(tabId, "special, \"filter\"\nname")

        val target = AppState()
        target.importFilters(source.exportFilters())
        target.addTab()
        target.loadFilter(target.tabs.single().id, target.savedFilters.single())

        val saved = target.savedFilters.single()
        val filter = target.tabs.single().filter
        assertEquals("special, \"filter\"\nname", saved.name)
        assertEquals("hello, \"quoted\"\nnext", filter.kwText)
        assertEquals(setOf("com.example,odd"), filter.pkgPrefixes)
        assertEquals(setOf("com.example,odd.noisy"), filter.excludePkgPrefixes)
        assertEquals("warn,\"quoted\"", filter.highlighters.single().pattern)
        val rule = filter.messageRules.single()
        assertEquals("value=(\"a,b\")\\s+next", rule.pattern)
        assertEquals("Tag,With,Comma", rule.tag)
        assertTrue(rule.regex)
    }

    @Test
    fun savedFiltersRoundTripMessageRules() {
        val state = AppState()
        state.addTab()
        val tabId = state.tabs.single().id
        state.addMessageRule(
            tabId,
            include = false,
            pattern = "heartbeat",
            regex = false,
            tag = "com.app.Network",
            packagePrefix = ""
        )

        state.saveFilter(tabId, "message rules")
        state.clearFilter(tabId)
        state.loadFilter(tabId, state.savedFilters.single())

        val rule = state.tabs.single().filter.messageRules.single()
        assertEquals(false, rule.include)
        assertEquals("heartbeat", rule.pattern)
        assertEquals("com.app.Network", rule.tag)
    }

    @Test
    fun changingSavedFilterWithDirtyCurrentPresetRequestsConfirmation() {
        val state = AppState()
        state.addTab()
        val tabId = state.tabs.single().id

        state.addPkgPrefix(tabId, "com.one")
        state.saveFilter(tabId, "one")
        state.clearFilter(tabId)
        state.addPkgPrefix(tabId, "com.two")
        state.saveFilter(tabId, "two")
        val one = state.savedFilters.first { it.name == "one" }
        val two = state.savedFilters.first { it.name == "two" }
        state.loadFilter(tabId, one)
        state.addPkgPrefix(tabId, "com.extra")

        state.requestLoadFilter(tabId, two)

        assertEquals(two.id, state.pendingFilterLoad?.targetFilterId)
        assertEquals(setOf("com.one", "com.extra"), state.tabs.single().filter.pkgPrefixes)
    }

    @Test
    fun editedPresetDraftDoesNotUpdateOriginalPresetBeforeSwitching() {
        val state = AppState()
        state.addTab()
        val tabId = state.tabs.single().id

        state.addPkgPrefix(tabId, "com.one")
        state.saveFilter(tabId, "one")
        state.clearFilter(tabId)
        state.addPkgPrefix(tabId, "com.two")
        state.saveFilter(tabId, "two")
        val one = state.savedFilters.first { it.name == "one" }
        val two = state.savedFilters.first { it.name == "two" }
        state.loadFilter(tabId, one)
        state.addPkgPrefix(tabId, "com.extra")
        state.requestLoadFilter(tabId, two)

        assertEquals(null, state.pendingFilterLoad?.currentFilterId)
        state.discardPendingFilterChangesAndLoad()

        assertEquals(null, state.pendingFilterLoad)
        assertEquals(setOf("com.one"), state.savedFilters.first { it.id == one.id }.pkgPrefixes)
        assertEquals(setOf("com.two"), state.tabs.single().filter.pkgPrefixes)
    }

    @Test
    fun pendingFilterLoadCanSaveAsNewBeforeSwitching() {
        val state = AppState()
        state.addTab()
        val tabId = state.tabs.single().id

        state.addPkgPrefix(tabId, "com.one")
        state.saveFilter(tabId, "one")
        state.clearFilter(tabId)
        state.addPkgPrefix(tabId, "com.two")
        state.saveFilter(tabId, "two")
        val one = state.savedFilters.first { it.name == "one" }
        val two = state.savedFilters.first { it.name == "two" }
        state.loadFilter(tabId, one)
        state.addPkgPrefix(tabId, "com.extra")
        state.requestLoadFilter(tabId, two)

        state.saveFilter(tabId, "one modified")

        assertEquals(null, state.pendingFilterLoad)
        assertEquals(setOf("com.one", "com.extra"), state.savedFilters.first { it.name == "one modified" }.pkgPrefixes)
        assertEquals(setOf("com.two"), state.tabs.single().filter.pkgPrefixes)
    }

    @Test
    fun pendingFilterLoadCanDiscardChangesBeforeSwitching() {
        val state = AppState()
        state.addTab()
        val tabId = state.tabs.single().id

        state.addPkgPrefix(tabId, "com.one")
        state.saveFilter(tabId, "one")
        state.clearFilter(tabId)
        state.addPkgPrefix(tabId, "com.two")
        state.saveFilter(tabId, "two")
        val one = state.savedFilters.first { it.name == "one" }
        val two = state.savedFilters.first { it.name == "two" }
        state.loadFilter(tabId, one)
        state.addPkgPrefix(tabId, "com.extra")

        state.requestLoadFilter(tabId, two)
        state.discardPendingFilterChangesAndLoad()

        assertEquals(null, state.pendingFilterLoad)
        assertEquals(setOf("com.one"), state.savedFilters.first { it.id == one.id }.pkgPrefixes)
        assertEquals(setOf("com.two"), state.tabs.single().filter.pkgPrefixes)
    }

    @Test
    fun updateExistingPickerOverwritesChosenPresetRegardlessOfWhichWasActive() {
        val state = AppState()
        state.addTab()
        val tabId = state.tabs.single().id

        state.addPkgPrefix(tabId, "com.one")
        state.saveFilter(tabId, "one")
        state.clearFilter(tabId)
        state.addPkgPrefix(tabId, "com.two")
        state.saveFilter(tabId, "two")
        val one = state.savedFilters.first { it.name == "one" }
        val two = state.savedFilters.first { it.name == "two" }
        state.loadFilter(tabId, one)
        state.addPkgPrefix(tabId, "com.extra")

        state.requestLoadFilter(tabId, two)
        // Editing "one" already demoted the tab to a draft, so there is no unambiguous "current"
        // preset — the picker must still let the user pick "two" (not just "one") to overwrite.
        assertEquals(null, state.pendingFilterLoad?.currentFilterId)

        state.beginUpdateExistingPick()
        assertTrue(state.updateExistingPickerOpen)

        state.confirmUpdateExisting(two.id)

        assertEquals(false, state.updateExistingPickerOpen)
        assertEquals(null, state.pendingFilterLoad)
        assertEquals(setOf("com.one", "com.extra"), state.savedFilters.first { it.id == two.id }.pkgPrefixes)
        assertEquals(setOf("com.one", "com.extra"), state.tabs.single().filter.pkgPrefixes)
        assertEquals(two.id, state.activeSavedFilterId(tabId))
        assertEquals(null, state.filterDraftForTab(tabId))
    }

    @Test
    fun changingSavedFilterWithDirtyUnsavedFilterRequestsConfirmation() {
        val source = AppState()
        source.addTab()
        source.addPkgPrefix(source.tabs.single().id, "com.target")
        source.saveFilter(source.tabs.single().id, "target")

        val state = AppState()
        state.addTab()
        val tabId = state.tabs.single().id
        state.importFilters(source.exportFilters())
        val target = state.savedFilters.single()
        state.addPkgPrefix(tabId, "com.unsaved")

        state.requestLoadFilter(tabId, target)

        assertEquals(target.id, state.pendingFilterLoad?.targetFilterId)
        assertEquals(null, state.pendingFilterLoad?.currentFilterId)
        assertEquals(setOf("com.unsaved"), state.tabs.single().filter.pkgPrefixes)
    }

    @Test
    fun loadingFilterOnEmptyNewTabDoesNotRequestConfirmationBecauseGlobalSequencesExist() {
        val source = AppState()
        source.addTab()
        source.addPkgPrefix(source.tabs.single().id, "com.target")
        source.saveFilter(source.tabs.single().id, "target")

        val state = AppState()
        state.importFilters(source.exportFilters())
        state.addTab()
        val target = state.savedFilters.single()

        state.requestLoadFilter(state.tabs.single().id, target)

        assertEquals(null, state.pendingFilterLoad)
        assertEquals(setOf("com.target"), state.tabs.single().filter.pkgPrefixes)
    }

    @Test
    fun savingFilterWithDuplicateNameRequestsReplaceChoice() {
        val state = AppState()
        state.addTab()
        val tabId = state.tabs.single().id
        state.addPkgPrefix(tabId, "com.first")
        state.saveFilter(tabId, "network")
        state.clearFilter(tabId)
        state.addPkgPrefix(tabId, "com.second")

        state.saveFilter(tabId, " network ")

        assertEquals("network", state.pendingDuplicateFilterSave?.existingName)
        assertEquals(setOf("com.first"), state.savedFilters.single().pkgPrefixes)
    }

    @Test
    fun replacingDuplicateSavedFilterKeepsSinglePresetAndUsesNewSnapshot() {
        val state = AppState()
        state.addTab()
        val tabId = state.tabs.single().id
        state.addPkgPrefix(tabId, "com.first")
        state.saveFilter(tabId, "network")
        state.clearFilter(tabId)
        state.addPkgPrefix(tabId, "com.second")
        state.saveFilter(tabId, "network")

        state.confirmReplaceDuplicateFilter()

        assertEquals(null, state.pendingDuplicateFilterSave)
        assertEquals(1, state.savedFilters.size)
        assertEquals(setOf("com.second"), state.savedFilters.single().pkgPrefixes)
    }

    @Test
    fun replacingSharedSavedFilterLeavesOtherOpenTabsUntouchedUntilTheyReloadIt() {
        val state = AppState()
        state.tabs = listOf(
            mkTab("one", "one.log", emptyList()),
            mkTab("two", "two.log", emptyList()),
        )
        state.activeTabId = "one"
        state.addPkgPrefix("one", "com.base")
        state.saveFilter("one", "network")
        val saved = state.savedFilters.single()
        state.loadFilter("two", saved)

        state.startRegexSearch("one")
        state.setKw("one", "network.*")
        state.setFilterMode("one", FilterMode.TAGS)
        state.addPkgPrefix("one", "com.changed")
        state.saveFilter("one", "network")
        state.confirmReplaceDuplicateFilter()

        assertEquals(FilterMode.TAGS, state.tab("one")!!.filter.mode)
        assertEquals(FilterMode.TAGS, state.tab("two")!!.filter.mode)
        assertEquals(setOf("com.base"), state.tab("two")!!.filter.pkgPrefixes)
        assertEquals(setOf("com.base", "com.changed"), state.savedFilters.single().pkgPrefixes)

        state.loadFilter("two", state.savedFilters.single())
        assertEquals(setOf("com.base", "com.changed"), state.tab("two")!!.filter.pkgPrefixes)
    }

    @Test
    fun deletingSavedFilterRequiresConfirmation() {
        val state = AppState()
        state.addTab()
        val tabId = state.tabs.single().id
        state.addPkgPrefix(tabId, "com.app")
        state.saveFilter(tabId, "app")
        val savedId = state.savedFilters.single().id

        state.requestDeleteSF(savedId)

        assertEquals(savedId, state.pendingDeleteFilterId)
        assertEquals(1, state.savedFilters.size)

        state.confirmDeleteSF()

        assertEquals(null, state.pendingDeleteFilterId)
        assertTrue(state.savedFilters.isEmpty())
        assertEquals(null, state.activeSavedFilterId(tabId))
    }

    @Test
    fun importFiltersFromDroppedFileAddsSavedFilters() {
        val dir = createTempDirectory("openlog-filters").toFile()
        val source = AppState(File(dir, "source.cache"))
        source.addTab()
        source.addPkgPrefix(source.tabs.single().id, "com.drop")
        source.saveFilter(source.tabs.single().id, "dropped")
        val file = File(dir, "filters.json").apply { writeText(source.exportFilters()) }

        val target = AppState(File(dir, "target.cache"))
        target.importFiltersFromFile(file)

        assertEquals("filters.json", target.pendingImportReview?.sourceName)
        target.confirmImportFilters()
        assertEquals("dropped", target.savedFilters.single().name)
    }

    @Test
    fun confirmedClearFilterClearsActiveSavedFilter() {
        val state = AppState()
        state.addTab()
        val tabId = state.tabs.single().id
        state.addPkgPrefix(tabId, "com.app")
        state.saveFilter(tabId, "app")
        val savedId = state.savedFilters.single().id
        assertEquals(savedId, state.activeSavedFilterId(tabId))

        state.requestClearFilter(tabId)
        state.confirmClearFilter()

        assertEquals(Filter(), state.tabs.single().filter)
        assertEquals(null, state.activeSavedFilterId(tabId))
        assertEquals(null, state.pendingClearFilterTabId)
    }

    @Test
    fun reapplyingAnUnchangedKeywordDoesNotDemoteTheActivePresetToADraft() {
        val state = AppState()
        state.addTab()
        val tabId = state.tabs.single().id
        state.setFilterMode(tabId, FilterMode.KEYWORD)
        state.setKw(tabId, "boot")
        state.saveFilter(tabId, "boot filter")
        val saved = state.savedFilters.single()
        assertEquals(saved.id, state.activeSavedFilterId(tabId))

        // FilterPanel's debounced kwDisplay field re-pushes its current value through setKw once
        // its LaunchedEffect settles after every recomposition, including one triggered by nothing
        // more than switching tabs away and back — not just on genuine user edits.
        state.setKw(tabId, "boot")

        assertEquals(saved.id, state.activeSavedFilterId(tabId))
        assertEquals(null, state.filterDraftForTab(tabId))
    }

    @Test
    fun startingRegexSearchDoesNotCreateDraftButCanStillBeSavedExplicitly() {
        val state = AppState()
        state.addTab()
        val tabId = state.tabs.single().id
        state.addPkgPrefix(tabId, "com.example")
        state.saveFilter(tabId, "example logs")

        state.startRegexSearch(tabId)
        state.setKw(tabId, """receive.*message""")

        assertEquals(FilterMode.KEYWORD, state.tab(tabId)!!.filter.mode)
        assertTrue(state.tab(tabId)!!.filter.kwRegex)
        assertEquals(state.savedFilters.single().id, state.activeSavedFilterId(tabId))
        assertEquals(null, state.filterDraftForTab(tabId))
        assertEquals(state.savedFilters.single().id, state.activeFilterItemId(tabId))
        assertEquals(1, state.savedFilters.size)

        state.saveFilter(tabId, "receive message")

        assertEquals(2, state.savedFilters.size)
        assertEquals("receive message", state.savedFilters.last().name)
        assertEquals(state.savedFilters.last().id, state.activeSavedFilterId(tabId))
    }

    @Test
    fun returningFromTransientRegexSearchLoadsSavedFilterWithoutSaveChangesPrompt() {
        val state = AppState()
        state.addTab()
        val tabId = state.tabs.single().id
        state.addPkgPrefix(tabId, "com.example")
        state.saveFilter(tabId, "example logs")
        val saved = state.savedFilters.single()

        state.startRegexSearch(tabId)
        state.setKw(tabId, """receive.*message""")
        state.setFilterMode(tabId, FilterMode.TAGS)
        state.requestLoadFilter(tabId, saved)

        assertEquals(null, state.pendingFilterLoad)
        assertEquals(saved.id, state.activeSavedFilterId(tabId))
        assertEquals(FilterMode.TAGS, state.tab(tabId)!!.filter.mode)
        assertEquals(setOf("com.example"), state.tab(tabId)!!.filter.pkgPrefixes)
        assertEquals("", state.tab(tabId)!!.filter.kwText)
    }

    @Test
    fun autosaveRestoresTransientRegexSearchWithoutBlockingSavedFilterReload() {
        val dir = createTempDirectory("openlog-transient-regex-restore").toFile()
        val logFile = File(dir, "restore.log").apply {
            writeText("06-26 10:00:00.000  123  456 I App: receive message\n")
        }
        val cacheFile = File(dir, "state.cache")
        val state = AppState(cacheFile)
        state.tabs = listOf(
            mkTab("log", "restore.log", listOf(LogEntry(1, "10:00:00.000", LogLevel.I, "App", "receive message")))
                .copy(sourcePath = logFile.absolutePath),
        )
        state.activeTabId = "log"
        state.addPkgPrefix("log", "com.example")
        state.saveFilter("log", "example logs")
        state.startRegexSearch("log")
        state.setKw("log", """receive.*message""")
        state.setFilterMode("log", FilterMode.TAGS)
        state.autosaveNow()

        val restored = AppState(cacheFile, restoreOnCreate = true)
        val restoredTabId = restored.tabs.single().id
        val saved = restored.savedFilters.single()
        restored.requestLoadFilter(restoredTabId, saved)

        assertEquals(null, restored.pendingFilterLoad)
        assertEquals(saved.id, restored.activeSavedFilterId(restoredTabId))
    }

    @Test
    fun editingActiveSavedFilterCreatesTabLocalDraftOnly() {
        val state = AppState()
        state.tabs = listOf(
            mkTab("one", "one.log", emptyList()),
            mkTab("two", "two.log", emptyList()),
        )
        state.activeTabId = "one"
        state.addPkgPrefix("one", "com.base")
        state.saveFilter("one", "base")
        val saved = state.savedFilters.single()
        state.loadFilter("two", saved)

        state.addPkgPrefix("one", "1234")

        assertEquals(null, state.activeSavedFilterId("one"))
        assertEquals(saved.id, state.activeSavedFilterId("two"))
        assertEquals(setOf("com.base"), state.tab("two")!!.filter.pkgPrefixes)
        assertEquals(listOf("unsaved_one.log", "base"), state.savedFiltersForTab("one").map { it.name })
        assertEquals(listOf("base"), state.savedFiltersForTab("two").map { it.name })
    }

    @Test
    fun deletingDraftKeepsCurrentTabFilterValues() {
        val state = AppState()
        state.tabs = listOf(mkTab("one", "one.log", emptyList()))
        state.activeTabId = "one"
        state.addPkgPrefix("one", "com.base")
        state.saveFilter("one", "base")
        state.addPkgPrefix("one", "1234")
        val draft = state.filterDraftForTab("one")!!

        state.requestDeleteSF(draft.id)
        state.confirmDeleteSF()

        assertEquals(null, state.filterDraftForTab("one"))
        assertEquals(setOf("com.base", "1234"), state.tab("one")!!.filter.pkgPrefixes)
    }

    @Test
    fun renamingDraftPromotesItToGlobalSavedFilter() {
        val state = AppState()
        state.tabs = listOf(mkTab("one", "one.log", emptyList()))
        state.activeTabId = "one"
        state.addPkgPrefix("one", "com.base")
        state.saveFilter("one", "base")
        state.addPkgPrefix("one", "1234")
        val draft = state.filterDraftForTab("one")!!

        state.beginRenameFilter(draft.id)
        state.confirmRenameFilter("pid 1234")

        val promoted = state.savedFilters.single { it.name == "pid 1234" }
        assertEquals(null, state.filterDraftForTab("one"))
        assertEquals(promoted.id, state.activeSavedFilterId("one"))
        assertEquals(setOf("com.base", "1234"), promoted.pkgPrefixes)
    }

    @Test
    fun beginRenameFilterStartsBlankForADraftSoConfirmingDoesNotLeakItsPlaceholderName() {
        val state = AppState()
        state.tabs = listOf(mkTab("one", "one.log", emptyList()))
        state.activeTabId = "one"
        state.addPkgPrefix("one", "com.base")
        state.saveFilter("one", "base")
        state.addPkgPrefix("one", "1234")
        val draft = state.filterDraftForTab("one")!!
        assertTrue(draft.name.startsWith("unsaved_"))

        state.beginRenameFilter(draft.id)

        assertEquals("", state.filterRenameName)
    }

    @Test
    fun renamingSavedFilterKeepsIdAndRejectsDuplicateNames() {
        val state = AppState()
        state.addTab()
        val tabId = state.tabs.single().id
        state.addPkgPrefix(tabId, "com.first")
        state.saveFilter(tabId, "network")
        state.clearFilter(tabId)
        state.addPkgPrefix(tabId, "com.second")
        state.saveFilter(tabId, "errors")
        val networkId = state.savedFilters.first { it.name == "network" }.id

        state.beginRenameFilter(networkId)
        state.confirmRenameFilter("Network renamed")

        assertEquals(networkId, state.savedFilters.first { it.name == "Network renamed" }.id)
        state.beginRenameFilter(networkId)
        state.confirmRenameFilter(" errors ")
        assertEquals("A saved filter named \"errors\" already exists.", state.filterRenameError)
        assertEquals(null, state.savedFilters.find { it.name == "errors" }?.let { if (it.id == networkId) it else null })
    }

    @Test
    fun selectedFilterExportIncludesOnlyChosenFilters() {
        val state = AppState()
        state.addTab()
        val tabId = state.tabs.single().id
        state.addPkgPrefix(tabId, "com.one")
        state.saveFilter(tabId, "one")
        state.clearFilter(tabId)
        state.addPkgPrefix(tabId, "com.two")
        state.saveFilter(tabId, "two")
        val one = state.savedFilters.first { it.name == "one" }

        val exported = state.exportFilters(setOf(one.id))

        assertTrue(exported.contains("\"name\": \"one\""))
        assertFalse(exported.contains("\"name\": \"two\""))
    }

    @Test
    fun importSkipsIdenticalConflictsAndRenamesDifferentOnesWithFreshIds() {
        val target = AppState()
        target.addTab()
        val tabId = target.tabs.single().id
        target.addPkgPrefix(tabId, "com.base")
        target.saveFilter(tabId, "network")
        val existingId = target.savedFilters.single().id

        val identical = AppState().apply {
            addTab()
            addPkgPrefix(tabs.single().id, "com.base")
            saveFilter(tabs.single().id, "network")
        }
        target.beginImportFilters(identical.exportFilters())
        target.confirmImportFilters()
        assertEquals(1, target.savedFilters.size)

        val different = AppState().apply {
            addTab()
            addPkgPrefix(tabs.single().id, "com.other")
            saveFilter(tabs.single().id, "network")
        }
        target.beginImportFilters(different.exportFilters())
        assertEquals(ImportFilterAction.RENAME, target.pendingImportReview!!.rows.single().action)
        target.confirmImportFilters()

        assertEquals(listOf("network", "network (imported)"), target.savedFilters.map { it.name })
        assertEquals(2, target.savedFilters.map { it.id }.toSet().size)
        assertEquals(existingId, target.savedFilters.first { it.name == "network" }.id)
    }

    @Test
    fun importReplaceKeepsExistingFilterId() {
        val target = AppState()
        target.addTab()
        val tabId = target.tabs.single().id
        target.addPkgPrefix(tabId, "com.base")
        target.saveFilter(tabId, "network")
        val existingId = target.savedFilters.single().id

        val source = AppState().apply {
            addTab()
            addPkgPrefix(tabs.single().id, "com.replacement")
            saveFilter(tabs.single().id, "network")
        }
        target.beginImportFilters(source.exportFilters())
        val rowId = target.pendingImportReview!!.rows.single().rowId
        target.setImportFilterAction(rowId, ImportFilterAction.REPLACE)
        target.confirmImportFilters()

        assertEquals(existingId, target.savedFilters.single().id)
        assertEquals(setOf("com.replacement"), target.savedFilters.single().pkgPrefixes)
    }

    @Test
    fun uncheckingNewFilterExcludesItFromImport() {
        val target = AppState()
        val source = AppState().apply {
            addTab()
            saveFilter(tabs.single().id, "brand new")
        }
        target.beginImportFilters(source.exportFilters())
        val rowId = target.pendingImportReview!!.rows.single().rowId

        target.setImportRowsChecked(setOf(rowId), false)
        assertEquals(ImportFilterAction.SKIP, target.pendingImportReview!!.rows.single().action)

        target.confirmImportFilters()
        assertTrue(target.savedFilters.isEmpty())
    }

    @Test
    fun recheckingSkippedNewFilterRestoresAddAction() {
        val target = AppState()
        val source = AppState().apply {
            addTab()
            saveFilter(tabs.single().id, "brand new")
        }
        target.beginImportFilters(source.exportFilters())
        val rowId = target.pendingImportReview!!.rows.single().rowId

        target.setImportRowsChecked(setOf(rowId), false)
        target.setImportRowsChecked(setOf(rowId), true)
        assertEquals(ImportFilterAction.ADD, target.pendingImportReview!!.rows.single().action)

        target.confirmImportFilters()
        assertEquals(listOf("brand new"), target.savedFilters.map { it.name })
    }

    @Test
    fun folderNotAddedWhenAllItsFiltersAreSkipped() {
        val target = AppState()
        val source = AppState().apply {
            addTab()
            createSavedFilterFolder("Release")
            saveFilter(tabs.single().id, "production", savedFilterFolders.single().id)
        }
        target.beginImportFilters(source.exportFilters())
        assertEquals(listOf("Release"), target.pendingImportReview!!.stagedFolders.map { it.name })
        val rowId = target.pendingImportReview!!.rows.single().rowId

        target.setImportRowsChecked(setOf(rowId), false)
        target.confirmImportFilters()

        assertTrue(target.savedFilterFolders.isEmpty())
        assertTrue(target.savedFilters.isEmpty())
    }

    @Test
    fun folderAddedWhenAtLeastOneOfItsFiltersSurvives() {
        val target = AppState()
        val source = AppState().apply {
            addTab()
            createSavedFilterFolder("Release")
            val folderId = savedFilterFolders.single().id
            saveFilter(tabs.single().id, "production", folderId)
            saveFilter(tabs.single().id, "staging", folderId)
        }
        target.beginImportFilters(source.exportFilters())
        target.confirmImportFilters()

        assertEquals(listOf("Release"), target.savedFilterFolders.map { it.name })
        val folderId = target.savedFilterFolders.single().id
        assertEquals(setOf("production", "staging"), target.savedFilters.map { it.name }.toSet())
        assertTrue(target.savedFilters.all { it.folderId == folderId })
    }

    @Test
    fun selectAllAndSelectNoneToggleAllToggleableRows() {
        val target = AppState()
        val source = AppState().apply {
            addTab()
            saveFilter(tabs.single().id, "one")
            saveFilter(tabs.single().id, "two")
        }
        target.beginImportFilters(source.exportFilters())
        val ids = target.pendingImportReview!!.rows.map { it.rowId }.toSet()

        target.setImportRowsChecked(ids, false)
        assertTrue(target.pendingImportReview!!.rows.all { it.action == ImportFilterAction.SKIP })

        target.setImportRowsChecked(ids, true)
        assertTrue(target.pendingImportReview!!.rows.all { it.action == ImportFilterAction.ADD })

        target.confirmImportFilters()
        assertEquals(setOf("one", "two"), target.savedFilters.map { it.name }.toSet())
    }

    @Test
    fun filterBackupsDefaultOnAndKeepLatestTwenty() {
        val dir = createTempDirectory("openlog-filter-backups").toFile()
        val state = AppState(File(dir, "state.cache"), filterBackupsDir = File(dir, "filter-backups"))
        state.addTab()
        val tabId = state.tabs.single().id

        assertTrue(state.settings.autoSaveFilters)
        repeat(25) { idx ->
            state.clearFilter(tabId)
            state.addPkgPrefix(tabId, "com.$idx")
            state.saveFilter(tabId, "filter-$idx")
        }

        val backups = File(dir, "filter-backups").listFiles { file -> file.extension == "json" }.orEmpty()
        assertEquals(20, backups.size)
        assertTrue(backups.maxBy { it.name }.readText().contains("\"name\": \"filter-24\""))
    }

    @Test
    fun addingSequenceUsesNextUnusedColorAfterRestore() {
        val state = AppState()
        state.addTab()
        state.upFlt(state.tabs.single().id) { f ->
            f.copy(sequences = listOf(SequenceDef(id = "outer", matchText = "start", priority = 1, color = SEQ_COLORS[0])))
        }
        state.newSeqColor = SEQ_COLORS[0]

        state.addSequence("inner", false, state.newSeqColor)

        assertEquals(SEQ_COLORS[1], state.sequences.last().color)
        assertEquals(SEQ_COLORS[2], state.newSeqColor)
    }

    @Test
    fun canChangeHighlighterColorAfterCreation() {
        val state = AppState()
        state.addTab()
        val tabId = state.tabs.single().id
        state.addHl(tabId, "Network", false, Color.Yellow)
        val highlighterId = state.tabs.single().filter.highlighters.single().id

        state.setHighlighterColor(tabId, highlighterId, Color.Cyan)

        assertEquals(Color.Cyan, state.tabs.single().filter.highlighters.single().color)
    }

    @Test
    fun regexHighlightSettingsRoundTripViaFilterSaveAndLoad() {
        val state = AppState()
        state.addTab()
        val tabId = state.tabs.single().id
        state.setFilterMode(tabId, FilterMode.KEYWORD)
        state.setKw(tabId, "timeout")
        state.toggleKwRx(tabId)
        state.setKwHighlightEnabled(tabId, false)
        state.setKwHighlightColor(tabId, Color.Cyan)

        state.saveFilter(tabId, "regex-highlight")
        state.clearFilter(tabId)
        state.loadFilter(tabId, state.savedFilters.single())

        val filter = state.tabs.single().filter
        assertEquals(false, filter.kwHighlightEnabled)
        assertEquals(Color.Cyan, filter.kwHighlightColor)
    }

    @Test
    fun importedOlderFilterDefaultsRegexHighlightSettings() {
        val state = AppState()

        state.importFilters(
            """
            [
              {
                "id": "old",
                "name": "old regex",
                "levels": ["I"],
                "mode": "KEYWORD",
                "kwText": "timeout",
                "kwRegex": true
              }
            ]
            """.trimIndent(),
        )

        val saved = state.savedFilters.single()
        assertTrue(saved.kwHighlightEnabled)
        assertEquals(DEFAULT_KEYWORD_HIGHLIGHT_COLOR, saved.kwHighlightColor)
    }

    @Test
    fun autosaveRestoresOpenTabNotesAndFilters() {
        val dir = createTempDirectory("openlog-cache").toFile()
        val logFile = File(dir, "test.log").apply {
            writeText("06-26 10:00:00.000  123  456 I App: hello\n")
        }
        val cacheFile = File(dir, "state.cache")
        val state = AppState(cacheFile)
        val tab = mkTab("log", "test.log", listOf(LogEntry(1, "10:00:00.000", LogLevel.I, "App", "hello")))
            .copy(sourcePath = logFile.absolutePath)
        state.tabs = listOf(tab)
        state.activeTabId = "log"
        state.settings = state.settings.copy(visibleTabLimit = 6, annotationPrefixLabel = "Evidence")
        state.addPkgPrefix("log", "App")
        state.confirmAddAnn("log", "log", listOf(1), "remember this", null)
        state.saveFilter("log", "app only")

        state.autosaveNow()

        val restored = AppState(cacheFile, restoreOnCreate = true)
        restored.startPendingRestoredTabLoads()
        // Restore parses the tab's file on a background job; wait for it to settle so assertions
        // observe a fully-materialized tab rather than racing the in-flight load.
        waitUntil(timeoutMs = 5_000) { !restored.isLoading && restored.tabs.isNotEmpty() }
        assertEquals("test.log", restored.tabs.single().filename)
        assertEquals(6, restored.settings.visibleTabLimit)
        assertEquals("Evidence", restored.settings.annotationPrefixLabel)
        assertEquals(setOf("App"), restored.tabs.single().filter.pkgPrefixes)
        val block = assertIs<AnnBlock.LogRef>(restored.tabs.single().annotations.blocks.single())
        assertEquals("remember this", block.caption)
        assertEquals("app only", restored.savedFilters.single().name)
        assertEquals(restored.savedFilters.single().id, restored.activeSavedFilterId(restored.tabs.single().id))
    }

    @Test
    fun autosaveRestoresIssueDescription() {
        val dir = createTempDirectory("openlog-cache").toFile()
        val logFile = File(dir, "test.log").apply {
            writeText("06-26 10:00:00.000  123  456 I App: hello\n")
        }
        val cacheFile = File(dir, "state.cache")
        val state = AppState(cacheFile)
        val tab = mkTab("log", "test.log", listOf(LogEntry(1, "10:00:00.000", LogLevel.I, "App", "hello")))
            .copy(sourcePath = logFile.absolutePath)
        state.tabs = listOf(tab)
        state.activeTabId = "log"
        state.setIssueDescription("log", "Repro steps:\n1. Open app\n2. Crash")

        state.autosaveNow()

        val restored = AppState(cacheFile, restoreOnCreate = true)
        restored.startPendingRestoredTabLoads()
        waitUntil(timeoutMs = 5_000) { !restored.isLoading && restored.tabs.isNotEmpty() }
        assertEquals("Repro steps:\n1. Open app\n2. Crash", restored.tabs.single().annotations.issueDescription)
    }

    @Test
    fun issueDescriptionRoundTripsThroughAnnSidecarFile() {
        val dir = createTempDirectory("openlog-ann-issue-desc").toFile()
        val state = AppState(File(dir, "state.cache"))
        state.tabs = listOf(mkTab("log", "test.log", emptyList()))
        state.activeTabId = "log"
        state.setIssueDescription("log", "multi\nline description")
        val ann = File(dir, "test_analysis.ann")
        state.saveAnnotationsTo("log", ann)

        val restored = AppState(File(dir, "restored.cache"))
        restored.tabs = listOf(mkTab("log", "test.log", emptyList()))
        assertTrue(restored.loadAnnotationsFrom("log", ann))

        assertEquals("multi\nline description", restored.tab("log")!!.annotations.issueDescription)
    }

    @Test
    fun oldThreeFieldAnnFilesStillParseWithBlankIssueDescription() {
        val dir = createTempDirectory("openlog-ann-legacy").toFile()
        val state = AppState(File(dir, "state.cache"))
        state.tabs = listOf(mkTab("log", "test.log", emptyList()))
        state.activeTabId = "log"
        state.setPrefix("log", "Heading")
        val legacyAnn = File(dir, "legacy.ann")
        state.saveAnnotationsTo("log", legacyAnn)
        // Simulate a .ann file written before issueDescription existed: drop the trailing
        // (base64-or-"~"-encoded, so "|"-free) 4th token, leaving exactly the old 3-field format.
        legacyAnn.writeText(legacyAnn.readText().substringBeforeLast("|"))

        val restored = AppState(File(dir, "restored.cache"))
        restored.tabs = listOf(mkTab("log", "test.log", emptyList()))
        assertTrue(restored.loadAnnotationsFrom("log", legacyAnn))

        assertEquals("Heading", restored.tab("log")!!.annotations.prefix)
        assertEquals("", restored.tab("log")!!.annotations.issueDescription)
    }

    @Test
    fun buildMdNeverIncludesIssueDescription() {
        val state = AppState()
        state.addTab()
        val tabId = state.tabs.single().id
        state.setPrefix(tabId, "Heading")
        state.addNoteBlock(tabId, "Body", null)
        state.setSuffix(tabId, "Follow up")
        state.setIssueDescription(tabId, "SECRET_MARKER_should_not_leak")

        val md = buildMd(state.tab(tabId)!!)

        assertTrue(!md.contains("SECRET_MARKER_should_not_leak"))
    }

    @Test
    fun autosaveRestoresFileOpenedThroughOpenFile() {
        val dir = createTempDirectory("openlog-openfile-restore").toFile()
        val logFile = File(dir, "restart.log").apply {
            writeText("06-26 10:00:00.000  123  456 I App: hello after restart\n")
        }
        val cacheFile = File(dir, "state.cache")
        val state = AppState(cacheFile)

        state.openFile(logFile)
        waitUntil { state.tabs.size == 1 && !state.isLoading }
        state.autosaveNow()

        val restored = AppState(cacheFile, restoreOnCreate = true)
        assertEquals(1, restored.tabs.size)
        assertFalse(restored.isLoading)
        assertTrue(restored.tabs.single().logData.isEmpty())
        restored.startPendingRestoredTabLoads()
        waitUntil { restored.tabs.size == 1 && !restored.isLoading }

        val restoredTab = restored.tabs.single()
        assertEquals("restart.log", restoredTab.filename)
        assertEquals(logFile.absolutePath, restoredTab.sourcePath)
        assertEquals("hello after restart", restoredTab.logData.single().msg)
    }

    @Test
    fun newlyOpenedFileUsesUnfilteredDefaultSetting() {
        val dir = createTempDirectory("openlog-open-unfiltered-default").toFile()
        val logFile = File(dir, "default-original.log").apply {
            writeText("06-26 10:00:00.000  123  456 I App: hello\n")
        }
        val state = AppState()
        state.settings = state.settings.copy(openNewFilesWithUnfiltered = true)

        state.openFile(logFile)
        waitUntil { state.tabs.size == 1 && !state.isLoading }

        assertTrue(state.tabs.single().showUnfiltered)
    }

    @Test
    fun newlyOpenedFileKeepsOriginalPanelHiddenByDefault() {
        val dir = createTempDirectory("openlog-open-unfiltered-off-default").toFile()
        val logFile = File(dir, "default-filtered.log").apply {
            writeText("06-26 10:00:00.000  123  456 I App: hello\n")
        }
        val state = AppState()

        state.openFile(logFile)
        waitUntil { state.tabs.size == 1 && !state.isLoading }

        assertFalse(state.tabs.single().showUnfiltered)
    }

    @Test
    fun autosaveRestoredTabKeepsSavedUnfilteredStateDespiteCurrentDefault() {
        val dir = createTempDirectory("openlog-restore-unfiltered-default").toFile()
        val logFile = File(dir, "restore-original.log").apply {
            writeText("06-26 10:00:00.000  123  456 I App: hello\n")
        }
        val cacheFile = File(dir, "state.cache")
        val state = AppState(cacheFile)
        state.settings = state.settings.copy(openNewFilesWithUnfiltered = true)
        state.tabs = listOf(
            mkTab("log", "restore-original.log", listOf(LogEntry(1, "10:00:00.000", LogLevel.I, "App", "hello")))
                .copy(sourcePath = logFile.absolutePath, showUnfiltered = false),
        )
        state.activeTabId = "log"

        state.autosaveNow()

        val restored = AppState(cacheFile, restoreOnCreate = true)
        assertEquals(true, restored.settings.openNewFilesWithUnfiltered)
        assertEquals(false, restored.tabs.single().showUnfiltered)
    }

    @Test
    fun autosaveRestoresActiveTagsKeywordAndLevelFilters() {
        val dir = createTempDirectory("openlog-cache").toFile()
        val logFile = File(dir, "test.log").apply {
            writeText("06-26 10:00:00.000  123  456 I App: hello\n")
        }
        val cacheFile = File(dir, "state.cache")
        val state = AppState(cacheFile)
        val tab = mkTab("log", "test.log", listOf(LogEntry(1, "10:00:00.000", LogLevel.I, "App", "hello")))
            .copy(sourcePath = logFile.absolutePath)
        state.tabs = listOf(tab)
        state.activeTabId = "log"
        state.upFlt("log") { f ->
            f.copy(
                activeTags = setOf("App", "Network"),
                kwText = "boom",
                kwRegex = true,
                mode = FilterMode.KEYWORD,
                levels = setOf(LogLevel.W, LogLevel.E),
            )
        }

        state.autosaveNow()

        val restored = AppState(cacheFile, restoreOnCreate = true)
        val restoredFilter = restored.tabs.single().filter
        assertEquals(setOf("App", "Network"), restoredFilter.activeTags)
        assertEquals("boom", restoredFilter.kwText)
        assertTrue(restoredFilter.kwRegex)
        assertEquals(FilterMode.KEYWORD, restoredFilter.mode)
        assertEquals(setOf(LogLevel.W, LogLevel.E), restoredFilter.levels)
    }

    @Test
    fun autosaveRestoresAnUnsavedFilterDraftAsStillActiveAndEditable() {
        val dir = createTempDirectory("openlog-cache").toFile()
        val logFile = File(dir, "test.log").apply {
            writeText("06-26 10:00:00.000  123  456 I App: hello\n")
        }
        val cacheFile = File(dir, "state.cache")
        val state = AppState(cacheFile)
        val tab = mkTab("log", "test.log", listOf(LogEntry(1, "10:00:00.000", LogLevel.I, "App", "hello")))
            .copy(sourcePath = logFile.absolutePath)
        state.tabs = listOf(tab)
        state.activeTabId = "log"
        state.addPkgPrefix("log", "com.base")
        state.saveFilter("log", "base")
        state.addPkgPrefix("log", "1234")
        val draftBeforeSave = state.filterDraftForTab("log")
        assertEquals(setOf("com.base", "1234"), draftBeforeSave?.pkgPrefixes)

        state.autosaveNow()

        val restored = AppState(cacheFile, restoreOnCreate = true)
        val restoredDraft = restored.filterDraftForTab("log")
        assertEquals(setOf("com.base", "1234"), restoredDraft?.pkgPrefixes)
        assertEquals(restoredDraft?.id, restored.activeFilterItemId("log"))
        assertEquals(setOf("com.base", "1234"), restored.tab("log")!!.filter.pkgPrefixes)

        // A further edit after restore must still update the draft, not silently no-op.
        restored.addPkgPrefix("log", "5678")
        assertEquals(setOf("com.base", "1234", "5678"), restored.filterDraftForTab("log")?.pkgPrefixes)
    }

    @Test
    fun autosaveSkipsTabsWhoseSourceFileNoLongerExists() {
        val dir = createTempDirectory("openlog-cache").toFile()
        val logFile = File(dir, "test.log").apply {
            writeText("06-26 10:00:00.000  123  456 I App: hello\n")
        }
        val cacheFile = File(dir, "state.cache")
        val state = AppState(cacheFile)
        state.tabs = listOf(
            mkTab("log", "test.log", listOf(LogEntry(1, "10:00:00.000", LogLevel.I, "App", "hello")))
                .copy(sourcePath = logFile.absolutePath),
        )
        state.activeTabId = "log"
        state.autosaveNow()
        logFile.delete()

        val restored = AppState(cacheFile, restoreOnCreate = true)

        assertTrue(restored.tabs.isEmpty())
        assertEquals("", restored.activeTabId)
    }

    @Test
    fun autosaveRestoresOpenZipEntryFromOriginalArchive() {
        val dir = createTempDirectory("openlog-zip-restore").toFile()
        val zip = buildZipFixture(
            dir,
            "bugreport.zip",
            mapOf("FS/data/anr/main_log.txt" to "06-26 10:00:00.000  123  456 I App: hello\n"),
        )
        val cacheFile = File(dir, "state.cache")
        val state = AppState(cacheFile)

        state.openZipFile(zip)
        waitUntil { state.tabs.size == 1 && !state.isLoading }
        state.autosaveNow()

        val restored = AppState(cacheFile, restoreOnCreate = true)
        restored.startPendingRestoredTabLoads()
        waitUntil { restored.tabs.size == 1 && !restored.isLoading }

        val restoredTab = restored.tabs.single()
        assertEquals("main_log.txt", restoredTab.filename)
        assertEquals("${zip.absolutePath}!FS/data/anr/main_log.txt", restoredTab.sourcePath)
        assertEquals("hello", restoredTab.logData.single().msg)
    }

    // PERF-3b: the tab token carries the archive candidate as a trailing field so restore can
    // rebuild the ArchiveSource without opening and scanning the archive during init. Assert the
    // field round-trips (present in the serialized token) and that a tab carrying it restores.
    @Test
    fun autosavePersistsArchiveCandidateSoRestoreSkipsRelisting() {
        val dir = createTempDirectory("openlog-zip-candidate").toFile()
        val zip = buildZipFixture(
            dir,
            "bugreport.zip",
            mapOf("FS/data/anr/main_log.txt" to "06-26 10:00:00.000  123  456 I App: hello\n"),
        )
        val cacheFile = File(dir, "state.cache")
        val state = AppState(cacheFile)

        state.openZipFile(zip)
        waitUntil { state.tabs.size == 1 && !state.isLoading }
        // The opened tab carries the exact candidate it was opened with.
        assertEquals("FS/data/anr/main_log.txt", state.tabs.single().archiveCandidate?.entryPath)
        state.autosaveNow()

        // The serialized cache mentions the entry path inside the tab's own (base64) token line,
        // evidence the candidate field was written rather than dropped.
        assertTrue(cacheFile.readText().isNotBlank())

        val restored = AppState(cacheFile, restoreOnCreate = true)
        restored.startPendingRestoredTabLoads()
        waitUntil { restored.tabs.size == 1 && !restored.isLoading }
        val restoredTab = restored.tabs.single()
        assertEquals("FS/data/anr/main_log.txt", restoredTab.archiveCandidate?.entryPath)
        assertEquals("hello", restoredTab.logData.single().msg)
    }

    @Test
    fun autosaveRestoreRefreshesReplacedArchiveContentMetadataAndLargeFileMode() {
        val dir = createTempDirectory("openlog-zip-refresh").toFile()
        val entryPath = "FS/data/anr/main_log.txt"
        val zip = buildZipFixture(
            dir,
            "bugreport.zip",
            mapOf(entryPath to "06-26 10:00:00.000  123  456 I App: old\n"),
        )
        val cacheFile = File(dir, "state.cache")
        val original = AppState(cacheFile)
        original.openZipFile(zip)
        waitUntil { original.tabs.size == 1 && !original.isLoading }
        val persistedSize = original.tabs.single().archiveCandidate!!.sizeBytes
        original.autosaveNow()

        val newMessage = "replacement-${"x".repeat(160)}"
        buildZipFixture(
            dir,
            "bugreport.zip",
            mapOf(entryPath to "06-26 10:00:00.000  123  456 I App: $newMessage\n"),
        )
        val resolverCalls = AtomicInteger()
        val restored = AppState(
            autosaveFile = cacheFile,
            restoreOnCreate = true,
            restoredArchiveLargeFileModeBytes = 100L,
            restoredArchiveCandidateResolver = { archiveFile, path ->
                resolverCalls.incrementAndGet()
                listArchiveLogCandidates(archiveFile).firstOrNull { it.entryPath == path }
            },
        )

        // Construction is metadata-only: the persisted candidate is visible, but current archive
        // metadata is not resolved until the queued IO load starts.
        assertEquals(0, resolverCalls.get())
        assertEquals(persistedSize, restored.tabs.single().archiveCandidate?.sizeBytes)
        assertFalse(restored.tabs.single().largeFileMode)

        restored.startPendingRestoredTabLoads()
        waitUntil { restored.tabs.size == 1 && !restored.isLoading }

        val tab = restored.tabs.single()
        val refreshedCandidate = requireNotNull(tab.archiveCandidate)
        assertEquals(1, resolverCalls.get())
        assertEquals(newMessage, tab.logData.single().msg)
        assertTrue(refreshedCandidate.sizeBytes > persistedSize)
        assertTrue(refreshedCandidate.sizeBytes >= 100L)
        assertTrue(tab.largeFileMode)

        restored.autosaveNow()
        val persistedAgain = AppState(cacheFile, restoreOnCreate = true)
        assertEquals(refreshedCandidate, persistedAgain.tabs.single().archiveCandidate)
    }

    @Test
    fun autosaveRestoreKeepsGenuinelyEmptyArchiveEntry() {
        val dir = createTempDirectory("openlog-zip-empty-restore").toFile()
        val entryPath = "FS/data/anr/main_log.txt"
        val zip = buildZipFixture(dir, "bugreport.zip", mapOf(entryPath to ""))
        val cacheFile = File(dir, "state.cache")
        val original = AppState(cacheFile)
        original.openZipFile(zip)
        waitUntil { original.tabs.size == 1 && !original.isLoading }
        original.autosaveNow()

        val restored = AppState(cacheFile, restoreOnCreate = true)
        restored.startPendingRestoredTabLoads()
        waitUntil { restored.tabs.size == 1 && !restored.isLoading }

        assertTrue(restored.tabs.single().logData.isEmpty())
        assertEquals(entryPath, restored.tabs.single().archiveCandidate?.entryPath)
        assertEquals(null, restored.openError)
    }

    @Test
    fun autosaveRestoreDropsArchiveTabWhenSavedEntryIsMissing() {
        val dir = createTempDirectory("openlog-zip-missing-restore").toFile()
        val entryPath = "FS/data/anr/main_log.txt"
        val zip = buildZipFixture(
            dir,
            "bugreport.zip",
            mapOf(entryPath to "06-26 10:00:00.000  123  456 I App: old\n"),
        )
        val cacheFile = File(dir, "state.cache")
        val original = AppState(cacheFile)
        original.openZipFile(zip)
        waitUntil { original.tabs.size == 1 && !original.isLoading }
        original.autosaveNow()

        buildZipFixture(
            dir,
            "bugreport.zip",
            mapOf("FS/data/anr/renamed_log.txt" to "06-26 10:00:00.000  123  456 I App: new\n"),
        )
        val restored = AppState(cacheFile, restoreOnCreate = true)
        assertEquals(1, restored.tabs.size)

        restored.startPendingRestoredTabLoads()
        waitUntil { restored.tabs.isEmpty() && !restored.isLoading }

        assertEquals("Restored archive entry is unavailable", restored.openError?.title)
        assertTrue(restored.openError?.path.orEmpty().endsWith("!$entryPath"))
    }

    // Backward compatibility: an archive tab token written before PERF-3b (no trailing candidate
    // field) must still restore without bringing archive listing back onto construction.
    @Test
    fun autosaveRestoresArchiveTabFromLegacyTokenWithoutCandidateField() {
        val dir = createTempDirectory("openlog-zip-legacy").toFile()
        val zip = buildZipFixture(
            dir,
            "bugreport.zip",
            mapOf("FS/data/anr/main_log.txt" to "06-26 10:00:00.000  123  456 I App: hello\n"),
        )
        val cacheFile = File(dir, "state.cache")
        val state = AppState(cacheFile)
        state.openZipFile(zip)
        waitUntil { state.tabs.size == 1 && !state.isLoading }
        state.autosaveNow()

        // Simulate a pre-PERF-3b cache: strip the trailing candidate field from every tab line by
        // dropping the last '|'-separated token. Tab lines are the ones after the "tabs" marker.
        val lines = cacheFile.readLines()
        val tabsIdx = lines.indexOf("tabs")
        val rewritten = lines.mapIndexed { i, line ->
            if (i > tabsIdx && line.startsWith("tab\t")) line.substringBeforeLast('|') else line
        }
        cacheFile.writeText(rewritten.joinToString("\n"))

        val resolverCalls = AtomicInteger()
        val restored = AppState(
            autosaveFile = cacheFile,
            restoreOnCreate = true,
            restoredArchiveCandidateResolver = { archiveFile, path ->
                resolverCalls.incrementAndGet()
                listArchiveLogCandidates(archiveFile).firstOrNull { it.entryPath == path }
            },
        )
        assertEquals(0, resolverCalls.get())
        assertEquals(null, restored.tabs.single().archiveCandidate)
        restored.startPendingRestoredTabLoads()
        waitUntil { restored.tabs.size == 1 && !restored.isLoading }
        assertEquals(1, resolverCalls.get())
        assertEquals("hello", restored.tabs.single().logData.single().msg)
        assertEquals("FS/data/anr/main_log.txt", restored.tabs.single().archiveCandidate?.entryPath)
    }

    // PERF-4: a selection-only change must not alter what autosave persists, so the debounced
    // effect (keyed on persistedSnapshot()) never re-arms for a row click. Compare the serialized
    // cache before and after selecting a row.
    @Test
    fun changingOnlySelectionDoesNotChangeThePersistedAutosave() {
        val dir = createTempDirectory("openlog-sel-nosave").toFile()
        val cacheFile = File(dir, "state.cache")
        val entries = (1..4).map { LogEntry(it, "10:00:00.00$it", LogLevel.I, "App", "msg $it") }
        val state = AppState(cacheFile)
        state.tabs = listOf(mkTab("t1", "test.log", entries))
        state.autosaveNow()
        val before = cacheFile.readText()

        state.selRow("t1", 2, multi = false, range = false)
        state.autosaveNow()
        val after = cacheFile.readText()

        assertEquals(setOf(2), state.tabs.single().selected)
        assertEquals(before, after)
        // Sanity: a persisted change (expanded is in the token) DOES alter the cache.
        state.tabs = listOf(state.tabs.single().copy(expanded = setOf("g1")))
        state.autosaveNow()
        assertTrue(cacheFile.readText() != before)
    }

    // Directly guards the LaunchedEffect key (persistedSnapshot()) the effect is actually keyed on
    // — the serialized-bytes test above only proves tabToken() excludes `selected`, which would
    // still pass if a future edit re-added `selected` to persistedSnapshot() and reintroduced the
    // PERF-4 re-arm-on-every-click regression. A persisted field (expanded) must change the key.
    @Test
    fun persistedSnapshotIgnoresSelectionButReflectsPersistedFields() {
        val entries = (1..4).map { LogEntry(it, "10:00:00.00$it", LogLevel.I, "App", "msg $it") }
        val tab = mkTab("t1", "test.log", entries)

        assertEquals(tab.persistedSnapshot(), tab.copy(selected = setOf(2)).persistedSnapshot())
        assertEquals(tab.persistedSnapshot(), tab.copy(analysis = LogAnalysis(pending = false)).persistedSnapshot())
        assertTrue(tab.persistedSnapshot() != tab.copy(expanded = setOf("g1")).persistedSnapshot())
    }

    @Test
    fun openingAZipEntryGivesTheTabAnArchiveQualifiedAnnotationPrefix() {
        val dir = createTempDirectory("openlog-zip-prefix").toFile()
        val zip = buildZipFixture(
            dir,
            "bugreport.zip",
            mapOf("FS/data/anr/main_log.txt" to "06-26 10:00:00.000  123  456 I App: hello\n"),
        )
        val state = AppState(File(dir, "state.cache"))

        state.openZipFile(zip)
        waitUntil { state.tabs.size == 1 && !state.isLoading }

        assertEquals("From bugreport.zip/FS/data/anr/main_log.txt", state.tabs.single().annotations.prefix)
    }

    @Test
    fun openingAnArchiveEntryOverTheExtractionBudgetShowsAClearErrorInsteadOfAnEmptyTab() {
        // S-03: extractCandidate used to be swallowed into an empty tab on any failure, including a
        // budget breach — indistinguishable from "this entry legitimately has nothing in it." Using
        // the archiveEntryByteBudget test seam (tiny, instead of a real multi-hundred-MB fixture) to
        // force the breach deterministically.
        val dir = createTempDirectory("openlog-zip-budget-ui").toFile()
        val zip = buildZipFixture(
            dir,
            "bugreport.zip",
            mapOf("FS/data/anr/main_log.txt" to "06-26 10:00:00.000  123  456 I App: ".repeat(20)),
        )
        val state = AppState(File(dir, "state.cache"), archiveEntryByteBudget = 10)

        state.openZipFile(zip)
        waitUntil { state.openError != null || (state.tabs.isNotEmpty() && !state.isLoading) }

        assertEquals(emptyList(), state.tabs, "an over-budget entry must not open as a (misleadingly empty) tab")
        assertTrue(state.openError?.message?.contains("extraction limit") == true, "expected a clear budget error:\n${state.openError}")
    }

    @Test
    fun openMissingFileShowsErrorAndRemovesItFromRecentFiles() {
        val dir = createTempDirectory("openlog-open-error-missing").toFile()
        val missing = File(dir, "missing.log")
        val state = AppState(File(dir, "state.cache"))
        state.recentFiles = listOf(missing.absolutePath)

        val tabId = state.openFile(missing)

        assertEquals(null, tabId)
        assertTrue(state.tabs.isEmpty())
        assertTrue(state.recentFiles.isEmpty())
        assertEquals("Could not open file", state.openError?.title)
        assertEquals(missing.absolutePath, state.openError?.path)
    }

    @Test
    fun openPathOrShowErrorAcceptsExtensionlessTextFileLikeDragAndDrop() {
        // Regression: the Open toolbar button used to gate on FileDialog.setFilenameFilter,
        // which is unreliable on macOS and greyed out exactly this kind of file even though
        // drag-and-drop (openPaths -> isOpenableAsLog) has always accepted it by content.
        val dir = createTempDirectory("openlog-open-noext").toFile()
        val noExt = File(dir, "bugreport").apply { writeText("10-01 10:00:00.000 1 1 I App: hi\n") }
        val state = AppState(File(dir, "state.cache"))

        state.openPathOrShowError(noExt)
        waitUntil { !state.isLoading }

        assertEquals(null, state.openError)
        assertEquals(1, state.tabs.size)
    }

    @Test
    fun openPathOrShowErrorRejectsUnsupportedBinaryWithClearMessage() {
        val dir = createTempDirectory("openlog-open-binary").toFile()
        val binary = File(dir, "image.dat").apply { writeBytes(byteArrayOf(0, 1, 2, 3, 0, 4)) }
        val state = AppState(File(dir, "state.cache"))

        state.openPathOrShowError(binary)

        assertTrue(state.tabs.isEmpty())
        assertEquals("Could not open file", state.openError?.title)
        assertEquals(binary.absolutePath, state.openError?.path)
    }

    @Test
    fun parserFailureShowsErrorInsteadOfOpeningEmptyTab() {
        val dir = createTempDirectory("openlog-open-error-parser").toFile()
        val logFile = File(dir, "broken.log").apply { writeText("broken") }
        val state = AppState(
            autosaveFile = File(dir, "state.cache"),
            parser = { error("parser exploded") },
        )

        state.openFile(logFile)
        waitUntil { !state.isLoading }

        assertTrue(state.tabs.isEmpty())
        assertEquals("Could not open file", state.openError?.title)
        assertTrue(state.openError?.message.orEmpty().contains("parser exploded"))
    }

    @Test
    fun archiveWithoutLogCandidatesShowsError() {
        val dir = createTempDirectory("openlog-open-error-archive").toFile()
        val zip = buildZipFixture(
            dir,
            "no-logs.zip",
            mapOf("images/screenshot.png" to "not a log"),
        )
        val state = AppState(File(dir, "state.cache"))

        state.openZipFile(zip)

        assertTrue(state.tabs.isEmpty())
        assertEquals(null, state.pendingZipPicker)
        assertEquals("No log files found", state.openError?.title)
        assertEquals(zip.absolutePath, state.openError?.path)
    }

    @Test
    fun openArchiveAddsArchivePathToRecentFiles() {
        val dir = createTempDirectory("openlog-archive-recent").toFile()
        val zip = buildZipFixture(
            dir,
            "bugreport.zip",
            mapOf("FS/data/anr/main_log.txt" to "06-26 10:00:00.000  123  456 I App: hello\n"),
        )
        val state = AppState(File(dir, "state.cache"))

        state.openZipFile(zip)

        assertEquals(listOf(zip.absolutePath), state.recentFiles)
    }

    @Test
    fun appCacheInfoRefreshesOnDemandAndClearDeletesAppManagedCacheOnlyAfterConfirmation() {
        val dir = createTempDirectory("openlog-archive-cache").toFile()
        val archiveCacheDir = File(dir, "archive-cache").apply { mkdirs() }
        val notesDir = File(dir, "notes").apply { mkdirs() }
        val userNotesDir = createTempDirectory("openlog-user-notes").toFile()
        File(archiveCacheDir, "a.tmp").writeText("12345")
        File(archiveCacheDir, "nested").apply { mkdirs() }.resolve("b.tmp").writeText("123")
        File(notesDir, "cached_analysis.md").writeText("note")
        File(dir, "root-data.bin").writeText("root")
        File(userNotesDir, "saved_analysis.md").writeText("keep")

        val state = AppState(
            autosaveFile = File(dir, "state.cache"),
            notesDir = notesDir,
            archiveCacheDir = archiveCacheDir,
        )
        state.settings = state.settings.copy(defaultSaveDir = userNotesDir.absolutePath)
        state.recentNotes = listOf(
            File(notesDir, "cached_analysis.md").absolutePath,
            File(userNotesDir, "saved_analysis.md").absolutePath,
        )

        assertEquals(archiveCacheDir.absolutePath, state.archiveCachePath)
        assertEquals(dir.absolutePath, state.appCachePath)
        // PERF-3a: the init-time refresh now runs on ioScope instead of blocking construction, so
        // the initial value needs a wait instead of being readable synchronously right after `new`.
        waitUntil { state.appDataSizeBytes == 16L }
        assertEquals(16L, state.archiveCacheSizeBytes)
        File(archiveCacheDir, "later.tmp").writeText("xx")
        assertEquals(16L, state.appDataSizeBytes)

        state.refreshAppDataSizeInfo()
        assertEquals(18L, state.appDataSizeBytes)

        state.requestClearCache()
        assertTrue(state.cacheClearConfirmOpen)
        assertTrue(File(notesDir, "cached_analysis.md").exists())
        assertTrue(File(userNotesDir, "saved_analysis.md").exists())

        state.confirmClearCache()
        assertFalse(state.cacheClearConfirmOpen)
        // Clear cache intentionally leaves other app data (including the autosave it refreshes).
        val recursiveAppDataSize = dir.walkTopDown().filter { it.isFile }.sumOf { it.length() }
        assertEquals(recursiveAppDataSize, state.appDataSizeBytes)
        assertTrue(state.appDataSizeBytes > 0L)
        assertTrue(archiveCacheDir.listFiles().orEmpty().isEmpty())
        assertTrue(notesDir.listFiles().orEmpty().isEmpty())
        assertTrue(File(userNotesDir, "saved_analysis.md").exists())
        assertEquals(listOf(File(userNotesDir, "saved_analysis.md").absolutePath), state.recentNotes)
    }

    @Test
    fun openingRecentNotesPrunesDeletedNoteFiles() {
        val dir = createTempDirectory("openlog-recent-notes-prune").toFile()
        val cacheFile = File(dir, "state.cache")
        val existingNote = File(dir, "existing_analysis.md").apply { writeText("keep") }
        val deletedNote = File(dir, "deleted_analysis.md").apply { writeText("gone") }
        val state = AppState(cacheFile)
        state.recentNotes = listOf(deletedNote.absolutePath, existingNote.absolutePath)
        state.autosaveNow()
        deletedNote.delete()

        state.toggleRecentNotesMenu()

        assertTrue(state.recentNotesMenuOpen)
        assertEquals(listOf(existingNote.absolutePath), state.recentNotes)

        val restored = AppState(cacheFile, restoreOnCreate = true)
        assertEquals(listOf(existingNote.absolutePath), restored.recentNotes)
    }

    @Test
    fun openNoteFileRestoresAnnotationsFromAnnSidecarEvenWhenMdIsMissing() {
        val dir = createTempDirectory("openlog-ann-recovery").toFile()
        val state = AppState(File(dir, "state.cache"))
        state.tabs = listOf(mkTab("log", "test.log", emptyList()))
        state.activeTabId = "log"
        state.setPrefix("log", "Heading")
        state.addNoteBlock("log", "Body text", null)
        state.setSuffix("log", "Next steps")
        val md = File(dir, "test_analysis.md")
        val ann = File(dir, "test_analysis.ann")
        state.saveAnnotationsTo("log", ann)
        // The paired .md was never written (or was since deleted) — only the .ann sidecar exists.
        assertTrue(!md.exists())
        assertTrue(ann.exists())

        val restored = AppState(File(dir, "restored.cache"))
        restored.tabs = listOf(mkTab("log", "test.log", emptyList()))
        restored.openNoteFile("log", ann)

        val restoredAnn = restored.tab("log")!!.annotations
        assertEquals("Heading", restoredAnn.prefix)
        assertEquals("Next steps", restoredAnn.suffix)
        assertEquals(listOf("Body text"), restoredAnn.blocks.filterIsInstance<AnnBlock.Note>().map { it.text })
    }

    @Test
    fun recentNotesForTabOnlyReturnsNotesMatchingThatTabName() {
        val dir = createTempDirectory("openlog-recent-notes-tab").toFile()
        val sampleNote = File(dir, "sample_analysis.md").apply { writeText("sample") }
        val otherNote = File(dir, "other_analysis.md").apply { writeText("other") }
        val state = AppState(File(dir, "state.cache"))
        state.recentNotes = listOf(otherNote.absolutePath, sampleNote.absolutePath)
        val tab = mkTab("log", "sample.log", emptyList())

        assertEquals(
            listOf(sampleNote.absolutePath),
            state.recentNotesForTab(tab),
        )
    }

    @Test
    fun layoutPaneStatePersistsImmediatelyAfterUiChanges() {
        val dir = createTempDirectory("openlog-layout").toFile()
        val cacheFile = File(dir, "state.cache")
        val state = AppState(cacheFile)

        state.updateFilterVisible(false)
        state.updateAnnotationVisible(false)
        state.updateCompareMode(true)
        state.updateCompareFilterRight(false)
        // The pane-size updates below now debounce through autosaveInBackground() (P-06 — this
        // used to be synchronous file I/O on every drag pointer-move event); wait past the
        // debounce before reading back what was persisted.
        state.updateFilterPanelWidth(333f)
        state.updateAnnotationPanelWidth(444f)
        state.updateCompareSplit(0.72f)
        // Autosave content is base64-encoded per field, so poll by restoring rather than matching
        // raw file text.
        waitUntil { cacheFile.exists() && AppState(cacheFile, restoreOnCreate = true).filterPanelWidth == 333f }

        val restored = AppState(cacheFile, restoreOnCreate = true)

        assertEquals(false, restored.filterVisible)
        assertEquals(false, restored.annotationVisible)
        assertEquals(false, restored.compareMode)
        assertEquals(false, restored.compareFilterRight)
        assertEquals(333f, restored.filterPanelWidth)
        assertEquals(444f, restored.annotationPanelWidth)
        assertEquals(0.72f, restored.compareSplit)
    }

    // ── Autosave hardening (P-06) ────────────────────────────────────────

    @Test
    fun autosaveNowSetsAutosaveErrorOnWriteFailureWithoutThrowing() {
        // autosaveFile's parent is itself a plain file, not a directory — mkdirs() can't create
        // a directory there and the subsequent write must fail. autosaveNow() must not throw;
        // it must surface the failure via autosaveError instead.
        val dir = createTempDirectory("openlog-autosave-fail").toFile()
        val blocker = File(dir, "blocker").apply { writeText("not a directory") }
        val state = AppState(File(blocker, "nested/state.cache"))

        assertEquals(null, state.autosaveError)
        state.autosaveNow()

        assertTrue(state.autosaveError != null, "expected autosaveError to be set after a failed write")
    }

    @Test
    fun autosaveNowClearsAutosaveErrorOnNextSuccessfulWrite() {
        val dir = createTempDirectory("openlog-autosave-recover").toFile()
        val blocker = File(dir, "blocker").apply { writeText("not a directory") }
        val state = AppState(File(blocker, "nested/state.cache"))
        state.autosaveNow()
        assertTrue(state.autosaveError != null)

        // Point at a real, writable location for the next save — mirrors what would happen if
        // the underlying disk-full/permissions condition resolved itself.
        val recovered = AppState(File(dir, "state.cache"))
        recovered.autosaveNow()

        assertEquals(null, recovered.autosaveError)
    }

    @Test
    fun autosaveInBackgroundDoesNotWriteSynchronously() {
        // The whole point of the background path (used by the drag-driven pane-size mutators) is
        // to get file I/O off the calling thread — a restore immediately after the call must not
        // yet reflect the change (autosave content is base64-encoded, so this reads back via a
        // restored AppState rather than matching raw file text).
        val dir = createTempDirectory("openlog-autosave-bg-timing").toFile()
        val cacheFile = File(dir, "state.cache")
        val state = AppState(cacheFile)

        state.updateFilterPanelWidth(333f)

        val immediately = if (cacheFile.exists()) AppState(cacheFile, restoreOnCreate = true).filterPanelWidth else null
        assertTrue(immediately != 333f, "background write must not be synchronous")
        waitUntil { cacheFile.exists() && AppState(cacheFile, restoreOnCreate = true).filterPanelWidth == 333f }
    }

    @Test
    fun autosaveInBackgroundCoalescesRapidCallsIntoOneFinalWrite() {
        // Each call cancels the previous debounce job and restarts it — only the last one should
        // ever complete. autosaveNow() always serializes the full current (in-memory) state
        // regardless of which specific update triggered it, so once any surviving job fires it
        // captures every update already applied above it.
        val dir = createTempDirectory("openlog-autosave-coalesce").toFile()
        val cacheFile = File(dir, "state.cache")
        val state = AppState(cacheFile)

        state.updateFilterPanelWidth(100f)
        state.updateFilterPanelWidth(200f)
        state.updateFilterPanelWidth(333f)
        state.updateAnnotationPanelWidth(444f)
        state.updateCompareSplit(0.72f)

        waitUntil { cacheFile.exists() && AppState(cacheFile, restoreOnCreate = true).filterPanelWidth == 333f }
        val restored = AppState(cacheFile, restoreOnCreate = true)
        assertEquals(333f, restored.filterPanelWidth, "only the final, coalesced value must land on disk")
        assertEquals(444f, restored.annotationPanelWidth)
        assertEquals(0.72f, restored.compareSplit)
    }

    @Test
    fun filterPanelSectionCollapseStatePersistsImmediatelyAfterUiChanges() {
        val dir = createTempDirectory("openlog-filter-panel").toFile()
        val cacheFile = File(dir, "state.cache")
        val state = AppState(cacheFile)

        state.updateFilterPanelUiState {
            hlListExpanded = false
            lvlExpanded = false
            seqExpanded = false
            sfExpanded = false
            incPillsExpanded = false
            incMsgPillsExpanded = false
            excMsgPillsExpanded = true
            crashCategory = CrashCategory.FATAL_EXCEPTIONS
        }

        val restored = AppState(cacheFile, restoreOnCreate = true)

        assertEquals(false, restored.fpState.hlListExpanded)
        assertEquals(false, restored.fpState.lvlExpanded)
        assertEquals(false, restored.fpState.seqExpanded)
        assertEquals(false, restored.fpState.sfExpanded)
        assertEquals(false, restored.fpState.incPillsExpanded)
        assertEquals(false, restored.fpState.incMsgPillsExpanded)
        assertEquals(true, restored.fpState.excMsgPillsExpanded)
        assertEquals(CrashCategory.FATAL_EXCEPTIONS, restored.fpState.crashCategory)
    }

    @Test
    fun openFilePersistsRecentFilesAndExistingAutoExportedNote() {
        val originalHome = System.getProperty("user.home")
        val home = createTempDirectory("openlog-home").toFile()
        try {
            System.setProperty("user.home", home.absolutePath)
            val notesDir = File(home, ".openlog2/notes").apply { mkdirs() }
            val logFile = File(home, "sample.log").apply {
                writeText("06-26 10:00:00.000  123  456 I App: hello\n")
            }
            val noteFile = File(notesDir, "sample_analysis.md").apply {
                writeText("## sample.log\n\nremember this")
            }
            val cacheFile = File(home, "state.cache")
            val state = AppState(cacheFile)

            state.openFile(logFile)

            assertEquals(listOf(logFile.absolutePath), state.recentFiles)
            assertEquals(listOf(noteFile.absolutePath), state.recentNotes)
            val restored = AppState(cacheFile, restoreOnCreate = true)
            assertEquals(listOf(logFile.absolutePath), restored.recentFiles)
            assertEquals(listOf(noteFile.absolutePath), restored.recentNotes)
        } finally {
            System.setProperty("user.home", originalHome)
        }
    }

    @Test
    fun autoExportUsesSameDotlessAnalysisBasenameAsManualSaveDefault() {
        val dir = createTempDirectory("openlog-auto-export-name").toFile()
        val notesDir = File(dir, "notes")
        val state = AppState(File(dir, "state.cache"), notesDir = notesDir)
        state.tabs =
            listOf(mkTab("log", "sample.log", listOf(LogEntry(1, "10:00:00.000", LogLevel.I, "App", "hello"))))

        state.confirmAddAnn("log", "log", listOf(1), "save this", null)
        waitUntil {
            File(notesDir, "sample_analysis.md").exists() &&
                File(notesDir, "sample_analysis.ann").exists()
        }

        assertTrue(File(notesDir, "sample_analysis.ann").exists())
        assertTrue(!File(notesDir, "sample.log_notes.md").exists())
        assertEquals(listOf(File(notesDir, "sample_analysis.md").absolutePath), state.recentNotes)
    }

    @Test
    fun autoExportNoLongerWritesStandaloneSrcSidecar() {
        val dir = createTempDirectory("openlog-no-src-sidecar").toFile()
        val notesDir = File(dir, "notes")
        val state = AppState(File(dir, "state.cache"), notesDir = notesDir)
        state.tabs = listOf(
            mkTab("log", "sample.log", listOf(LogEntry(1, "10:00:00.000", LogLevel.I, "App", "hello")))
                .copy(sourcePath = File(dir, "sample.log").absolutePath),
        )

        state.confirmAddAnn("log", "log", listOf(1), "save this", null)
        waitUntil { File(notesDir, "sample_analysis.md").exists() && File(notesDir, "sample_analysis.ann").exists() }

        // The sourcePath fingerprint now lives in the .ann file's 5th token field instead of a
        // separate sidecar — only two files should exist per analysis.
        assertTrue(!File(notesDir, "sample_analysis.md.src").exists())
    }

    @Test
    fun autoExportDisambiguatesWhenADifferentFileSharesTheSameName() {
        val dir = createTempDirectory("openlog-collision-notes").toFile()
        val notesDir = File(dir, "notes")
        val stateA = AppState(File(dir, "state-a.cache"), notesDir = notesDir)
        stateA.tabs = listOf(
            mkTab("log", "sample.log", listOf(LogEntry(1, "10:00:00.000", LogLevel.I, "App", "hello")))
                .copy(sourcePath = File(dir, "folder_a/sample.log").absolutePath),
        )
        stateA.confirmAddAnn("log", "log", listOf(1), "notes for file A", null)
        waitUntil { File(notesDir, "sample_analysis.md").exists() && File(notesDir, "sample_analysis.ann").exists() }
        val originalContent = File(notesDir, "sample_analysis.md").readText()

        // A different file (different sourcePath) that happens to share the same display name.
        val stateB = AppState(File(dir, "state-b.cache"), notesDir = notesDir)
        stateB.tabs = listOf(
            mkTab("log", "sample.log", listOf(LogEntry(1, "10:00:00.000", LogLevel.I, "App", "goodbye")))
                .copy(sourcePath = File(dir, "folder_b/sample.log").absolutePath),
        )
        stateB.confirmAddAnn("log", "log", listOf(1), "notes for file B", null)
        waitUntil { File(notesDir, "sample_analysis_2.md").exists() && File(notesDir, "sample_analysis_2.ann").exists() }

        // File A's saved note must survive untouched — this is the silent-overwrite bug being fixed.
        assertEquals(originalContent, File(notesDir, "sample_analysis.md").readText())
        assertEquals(listOf(File(notesDir, "sample_analysis_2.md").absolutePath), stateB.recentNotes)
    }

    @Test
    fun autoExportReusesPlainNameOnRepeatedSaveOfTheSameFile() {
        val dir = createTempDirectory("openlog-same-file-notes").toFile()
        val notesDir = File(dir, "notes")
        val state = AppState(File(dir, "state.cache"), notesDir = notesDir)
        val sourcePath = File(dir, "sample.log").absolutePath
        state.tabs = listOf(
            mkTab("log", "sample.log", listOf(LogEntry(1, "10:00:00.000", LogLevel.I, "App", "hello")))
                .copy(sourcePath = sourcePath),
        )

        state.confirmAddAnn("log", "log", listOf(1), "first note", null)
        waitUntil { File(notesDir, "sample_analysis.md").exists() }
        state.addNoteBlock("log", "second note")
        waitUntil { File(notesDir, "sample_analysis.md").readText().contains("second note") }

        // Same sourcePath re-saving must keep reusing the plain name, never disambiguate itself.
        assertTrue(!File(notesDir, "sample_analysis_2.md").exists())
    }

    @Test
    fun recentNotesForTabExcludesNoteKnownToBelongToADifferentFile() {
        val dir = createTempDirectory("openlog-recent-notes-collision").toFile()
        val notesDir = File(dir, "notes").apply { mkdirs() }
        val state = AppState(File(dir, "state.cache"), notesDir = notesDir)
        val noteFile = File(notesDir, "sample_analysis.md").apply { writeText("belongs to folder_a") }
        File(notesDir, "sample_analysis.md.src").writeText(File(dir, "folder_a/sample.log").absolutePath)
        state.recentNotes = listOf(noteFile.absolutePath)

        val unrelatedTab = mkTab("log", "sample.log", emptyList())
            .copy(sourcePath = File(dir, "folder_b/sample.log").absolutePath)
        val sameTab = mkTab("log", "sample.log", emptyList())
            .copy(sourcePath = File(dir, "folder_a/sample.log").absolutePath)
        val unknownTab = mkTab("log", "sample.log", emptyList())

        assertTrue(state.recentNotesForTab(unrelatedTab).isEmpty())
        assertEquals(listOf(noteFile.absolutePath), state.recentNotesForTab(sameTab))
        // No sourcePath to compare against (e.g. a merged tab) — can't prove a mismatch, so it
        // still matches by name, same as before fingerprinting existed.
        assertEquals(listOf(noteFile.absolutePath), state.recentNotesForTab(unknownTab))
    }

    @Test
    fun recentNotesUseLegacyFingerprintWhenEmbeddedNoteFingerprintIsMalformed() {
        val dir = createTempDirectory("openlog-malformed-note-fingerprint-legacy").toFile()
        val notesDir = File(dir, "notes").apply { mkdirs() }
        val noteFile = File(notesDir, "sample_analysis.md").apply { writeText("legacy owner") }
        val legacySourcePath = File(dir, "folder_a/sample.log").absolutePath
        File(notesDir, "sample_analysis.ann").writeText(
            Annotations().annotationsToken(legacySourcePath).withMalformedAnnotationField(4),
        )
        File(notesDir, "sample_analysis.md.src").writeText(legacySourcePath)
        val state = AppState(File(dir, "state.cache"), notesDir = notesDir).apply {
            recentNotes = listOf(noteFile.absolutePath)
        }

        val legacyOwner = mkTab("same", "sample.log", emptyList()).copy(sourcePath = legacySourcePath)
        val differentOwner = mkTab("other", "sample.log", emptyList())
            .copy(sourcePath = File(dir, "folder_b/sample.log").absolutePath)

        assertEquals(listOf(noteFile.absolutePath), state.recentNotesForTab(legacyOwner))
        assertTrue(state.recentNotesForTab(differentOwner).isEmpty())
    }

    @Test
    fun malformedAnnotationFieldsDoNotBreakRecentNotePositiveEvidenceFiltering() {
        val dir = createTempDirectory("openlog-malformed-note-fields").toFile()
        val notesDir = File(dir, "notes").apply { mkdirs() }
        val noteFile = File(notesDir, "sample_analysis.md").apply { writeText("unknown owner") }
        val annFile = File(notesDir, "sample_analysis.ann")
        val sourcePath = File(dir, "folder_a/sample.log").absolutePath
        val state = AppState(File(dir, "state.cache"), notesDir = notesDir).apply {
            recentNotes = listOf(noteFile.absolutePath)
        }
        val tab = mkTab("log", "sample.log", emptyList()).copy(sourcePath = sourcePath)
        val validToken = Annotations().annotationsToken(File(dir, "different/sample.log").absolutePath)

        // tokenFields decodes every field eagerly. Corrupting any one of them must be contained,
        // including fields before the fingerprint and the fingerprint field itself.
        repeat(5) { fieldIndex ->
            annFile.writeText(validToken.withMalformedAnnotationField(fieldIndex))
            assertEquals(
                listOf(noteFile.absolutePath),
                state.recentNotesForTab(tab),
                "malformed field $fieldIndex must behave as an absent fingerprint",
            )
        }
    }

    @Test
    fun validEmbeddedNoteFingerprintTakesPrecedenceOverLegacyFingerprint() {
        val dir = createTempDirectory("openlog-embedded-note-fingerprint-precedence").toFile()
        val notesDir = File(dir, "notes").apply { mkdirs() }
        val noteFile = File(notesDir, "sample_analysis.md").apply { writeText("embedded owner") }
        val embeddedSourcePath = File(dir, "folder_a/sample.log").absolutePath
        val legacySourcePath = File(dir, "folder_b/sample.log").absolutePath
        File(notesDir, "sample_analysis.ann").writeText(Annotations().annotationsToken(embeddedSourcePath))
        File(notesDir, "sample_analysis.md.src").writeText(legacySourcePath)
        val state = AppState(File(dir, "state.cache"), notesDir = notesDir).apply {
            recentNotes = listOf(noteFile.absolutePath)
        }

        val embeddedOwner = mkTab("embedded", "sample.log", emptyList()).copy(sourcePath = embeddedSourcePath)
        val legacyOwner = mkTab("legacy", "sample.log", emptyList()).copy(sourcePath = legacySourcePath)

        assertEquals(listOf(noteFile.absolutePath), state.recentNotesForTab(embeddedOwner))
        assertTrue(state.recentNotesForTab(legacyOwner).isEmpty())
    }

    @Test
    fun autoExportNoteCollisionFallsBackAfterMalformedEmbeddedFingerprint() {
        val dir = createTempDirectory("openlog-malformed-note-auto-export").toFile()
        val notesDir = File(dir, "notes").apply { mkdirs() }
        val originalNote = File(notesDir, "sample_analysis.md").apply { writeText("keep original") }
        val originalSourcePath = File(dir, "folder_a/sample.log").absolutePath
        val malformedToken = Annotations().annotationsToken(originalSourcePath).withMalformedAnnotationField(0)
        val malformedAnn = File(notesDir, "sample_analysis.ann").apply { writeText(malformedToken) }
        val legacyFingerprint = File(notesDir, "sample_analysis.md.src").apply { writeText(originalSourcePath) }
        val state = AppState(File(dir, "state.cache"), notesDir = notesDir)
        state.tabs = listOf(
            mkTab("log", "sample.log", listOf(LogEntry(1, "10:00:00.000", LogLevel.I, "App", "hello")))
                .copy(sourcePath = File(dir, "folder_b/sample.log").absolutePath),
        )

        state.confirmAddAnn("log", "log", listOf(1), "new owner", null)
        waitUntil {
            File(notesDir, "sample_analysis_2.md").exists() &&
                File(notesDir, "sample_analysis_2.ann").exists()
        }

        assertEquals("keep original", originalNote.readText())
        assertEquals(malformedToken, malformedAnn.readText())
        assertEquals(originalSourcePath, legacyFingerprint.readText())
        assertEquals(listOf(File(notesDir, "sample_analysis_2.md").absolutePath), state.recentNotes)
    }

    @Test
    fun autoExportUsesDefaultSaveFolderWhenItExists() {
        val dir = createTempDirectory("openlog-default-notes").toFile()
        val notesDir = File(dir, "notes")
        val defaultSaveDir = File(dir, "saved-notes").apply { mkdirs() }
        val state = AppState(File(dir, "state.cache"), notesDir = notesDir)
        state.settings = state.settings.copy(defaultSaveDir = defaultSaveDir.absolutePath)
        state.tabs =
            listOf(mkTab("log", "sample.log", listOf(LogEntry(1, "10:00:00.000", LogLevel.I, "App", "hello"))))

        state.confirmAddAnn("log", "log", listOf(1), "save this", null)
        // recentNotes updates one statement after the .ann write on the export thread — wait for
        // it too, or the assertion below races that window.
        waitUntil {
            File(defaultSaveDir, "sample_analysis.md").exists() &&
                File(defaultSaveDir, "sample_analysis.ann").exists() &&
                state.recentNotes.isNotEmpty()
        }

        assertTrue(File(defaultSaveDir, "sample_analysis.ann").exists())
        assertTrue(!notesDir.exists() || notesDir.listFiles().orEmpty().isEmpty())
        assertEquals(listOf(File(defaultSaveDir, "sample_analysis.md").absolutePath), state.recentNotes)
    }

    @Test
    fun autoExportFallsBackToAppNotesWhenDefaultSaveFolderIsMissing() {
        val dir = createTempDirectory("openlog-missing-default-notes").toFile()
        val notesDir = File(dir, "notes")
        val missingDefaultSaveDir = File(dir, "missing-saved-notes")
        val state = AppState(File(dir, "state.cache"), notesDir = notesDir)
        state.settings = state.settings.copy(defaultSaveDir = missingDefaultSaveDir.absolutePath)
        state.tabs =
            listOf(mkTab("log", "sample.log", listOf(LogEntry(1, "10:00:00.000", LogLevel.I, "App", "hello"))))

        state.confirmAddAnn("log", "log", listOf(1), "save this", null)
        waitUntil {
            File(notesDir, "sample_analysis.md").exists() &&
                File(notesDir, "sample_analysis.ann").exists()
        }

        assertTrue(File(notesDir, "sample_analysis.ann").exists())
        assertTrue(!missingDefaultSaveDir.exists())
        assertEquals(listOf(File(notesDir, "sample_analysis.md").absolutePath), state.recentNotes)
    }

    @Test
    fun autoExportCanBeDisabledForPrivateLogs() {
        val dir = createTempDirectory("openlog-private").toFile()
        val notesDir = File(dir, "notes")
        val state = AppState(File(dir, "state.cache"), notesDir = notesDir)
        state.settings = state.settings.copy(autoExportNotes = false)
        state.tabs =
            listOf(mkTab("log", "private.log", listOf(LogEntry(1, "10:00:00.000", LogLevel.I, "App", "secret"))))

        state.confirmAddAnn("log", "log", listOf(1), "keep local only", null)
        Thread.sleep(100)

        assertTrue(!notesDir.exists() || notesDir.listFiles().orEmpty().isEmpty())
    }

    @Test
    fun desktopStorageUsesOsSpecificAppDataLocations() {
        val mac = DesktopStorage.appDataDir("Mac OS X", "/Users/me") { null }
        val windows = DesktopStorage.appDataDir("Windows 11", "C:/Users/me") { key ->
            if (key == "APPDATA") "C:/Users/me/AppData/Roaming" else null
        }
        val linux = DesktopStorage.appDataDir("Linux", "/home/me") { key ->
            if (key == "XDG_STATE_HOME") "/home/me/.local/state" else null
        }

        assertEquals(File("/Users/me/Library/Application Support/openLog2"), mac)
        assertEquals(File("C:/Users/me/AppData/Roaming", "openLog2"), windows)
        assertEquals(File("/home/me/.local/state", "openLog2"), linux)
    }

    @Test
    fun concurrentOpenFileKeepsEveryLoadedTabUntilAllLoadsFinish() {
        val dir = createTempDirectory("openlog-concurrent").toFile()
        val first = File(dir, "first.log").apply { writeText("first") }
        val second = File(dir, "second.log").apply { writeText("second") }
        val release = CountDownLatch(1)
        val started = CountDownLatch(2)
        val state = AppState(
            autosaveFile = File(dir, "state.cache"),
            parser = { file ->
                started.countDown()
                release.await(2, TimeUnit.SECONDS)
                listOf(LogEntry(1, "", LogLevel.I, file.nameWithoutExtension, file.name))
            },
        )

        state.openFile(first)
        state.openFile(second)
        assertTrue(started.await(2, TimeUnit.SECONDS))
        assertTrue(state.isLoading)
        release.countDown()

        waitUntil { state.tabs.size == 2 && !state.isLoading }
        assertEquals(setOf("first.log", "second.log"), state.tabs.map { it.filename }.toSet())
    }

    @Test
    fun isLoadInFlightScopesToOneTabInsteadOfTheGlobalLoadingFlag() {
        // ARCH-3: OpenLogToolOperations.awaitLoad scopes to isLoadInFlight(tabId) rather than the
        // global isLoading flag precisely so a concurrent unrelated load can't make an
        // open_log_file call wait on, or misreport completion for, a load it didn't trigger. This
        // exercises the AppState-level primitive that guarantee rests on directly.
        val dir = createTempDirectory("openlog-load-in-flight").toFile()
        val slow = File(dir, "slow.log").apply { writeText("slow") }
        val fast = File(dir, "fast.log").apply { writeText("fast") }
        val started = CountDownLatch(1)
        val release = CountDownLatch(1)
        val state = AppState(
            autosaveFile = File(dir, "state.cache"),
            parser = { file ->
                if (file.name == "slow.log") {
                    started.countDown()
                    release.await(2, TimeUnit.SECONDS)
                }
                listOf(LogEntry(1, "", LogLevel.I, file.nameWithoutExtension, file.name))
            },
        )

        val slowTabId = requireNotNull(state.openFile(slow))
        assertTrue(started.await(2, TimeUnit.SECONDS))
        assertTrue(state.isLoadInFlight(slowTabId))

        val fastTabId = requireNotNull(state.openFile(fast))
        waitUntil { !state.isLoadInFlight(fastTabId) }
        // The unrelated fast load already finished, but the still-blocked slow load must still
        // report in-flight for its own tabId — a caller scoped to fastTabId must not be told to
        // keep waiting, and a caller scoped to slowTabId must not be told it's already done.
        assertTrue(state.isLoadInFlight(slowTabId))
        assertTrue(state.isLoading, "global flag stays true while the slow load is still pending")

        release.countDown()
        waitUntil { !state.isLoadInFlight(slowTabId) }
        assertFalse(state.isLoading)
    }

    @Test
    fun closeCancelsPendingFileLoadBeforeItMutatesTabs() {
        val dir = createTempDirectory("openlog-cancel").toFile()
        val file = File(dir, "slow.log").apply { writeText("slow") }
        val started = CountDownLatch(1)
        val release = CountDownLatch(1)
        val state = AppState(
            autosaveFile = File(dir, "state.cache"),
            parser = {
                started.countDown()
                release.await(2, TimeUnit.SECONDS)
                listOf(LogEntry(1, "", LogLevel.I, "Slow", "done"))
            },
        )

        state.openFile(file)
        assertTrue(started.await(2, TimeUnit.SECONDS))
        state.close()
        release.countDown()

        waitUntil { !state.isLoading }
        assertTrue(state.tabs.isEmpty())
    }

    @Test
    fun fileLoadingStatusStaysGeneric() {
        val dir = createTempDirectory("openlog-loading-label").toFile()
        val file = File(dir, "slow.log").apply { writeText("slow") }
        val started = CountDownLatch(1)
        val release = CountDownLatch(1)
        val state = AppState(
            autosaveFile = File(dir, "state.cache"),
            parser = {
                started.countDown()
                release.await(2, TimeUnit.SECONDS)
                listOf(LogEntry(1, "", LogLevel.I, "Slow", "done"))
            },
        )

        state.openFile(file)
        assertTrue(started.await(2, TimeUnit.SECONDS))
        assertEquals("Loading file...", state.loadingStatus)
        assertFalse(state.loadingStatus.orEmpty().contains("large", ignoreCase = true))
        release.countDown()
        waitUntil { !state.isLoading }
    }

    @Test
    fun openingOversizedFileCreatesPendingSplitPromptInsteadOfParsingImmediately() {
        val dir = createTempDirectory("openlog-split-open").toFile()
        val file = File(dir, "large.log")
        RandomAccessFile(file, "rw").use { it.setLength(SPLIT_PROMPT_BYTES) }
        var parseCalls = 0
        val state = AppState(
            autosaveFile = File(dir, "state.cache"),
            parser = {
                parseCalls += 1
                emptyList()
            },
        )

        state.openFile(file)

        assertEquals(0, parseCalls)
        assertTrue(state.tabs.isEmpty())
        assertEquals(listOf("large.log"), state.pendingSplitPrompt?.sources?.map { it.displayName })
        assertEquals(dir.absolutePath, state.defaultSplitDestination(state.pendingSplitPrompt!!.sources.single()).absolutePath)
    }

    @Test
    fun confirmingOpenAsIsForOversizedFileLoadsOriginalTab() {
        val dir = createTempDirectory("openlog-split-as-is").toFile()
        val file = File(dir, "large.log")
        RandomAccessFile(file, "rw").use { it.setLength(SPLIT_PROMPT_BYTES) }
        val state = AppState(
            autosaveFile = File(dir, "state.cache"),
            parser = { listOf(LogEntry(1, "10:00:00.000", LogLevel.I, "App", "loaded")) },
        )
        state.settings = state.settings.copy(openNewFilesWithUnfiltered = true)
        state.openFile(file)
        val sourceId = state.pendingSplitPrompt!!.sources.single().id

        state.confirmSplitPrompt(
            modes = mapOf(sourceId to SplitMode.OPEN_AS_IS),
            destinationDir = dir,
            postfix = "part",
            partCounts = mapOf(sourceId to 2),
        )

        waitUntil { state.tabs.size == 1 && !state.isLoading }
        val tab = state.tabs.single()
        assertEquals("large.log", tab.filename)
        assertEquals(file.absolutePath, tab.sourcePath)
        assertTrue(tab.largeFileMode)
        assertTrue(tab.showUnfiltered)
    }

    @Test
    fun confirmingSplitWritesPartsAndOpensThemInsteadOfOriginal() {
        val dir = createTempDirectory("openlog-split-confirm").toFile()
        val source = File(dir, "large.log").apply {
            writeText("one\nvery long line two\nthree\nfour\n")
        }
        val outputDir = File(dir, "out")
        val state = AppState(autosaveFile = File(dir, "state.cache"))
        state.requestSplitForFile(source)
        val sourceId = state.pendingSplitPrompt!!.sources.single().id

        state.confirmSplitPrompt(
            modes = mapOf(sourceId to SplitMode.SPLIT),
            destinationDir = outputDir,
            postfix = "part",
            partCounts = mapOf(sourceId to 2),
        )

        waitUntil { state.tabs.size == 2 && !state.isLoading }
        assertEquals(listOf("large_part_1.log", "large_part_2.log"), state.tabs.map { it.filename })
        assertEquals(
            source.readText(),
            File(outputDir, "large_part_1.log").readText() + File(outputDir, "large_part_2.log").readText(),
        )
        assertTrue(state.tabs.none { it.sourcePath == source.absolutePath })
    }

    @Test
    fun openingMultipleOversizedFilesCreatesOneBatchSplitPrompt() {
        val dir = createTempDirectory("openlog-split-batch-open").toFile()
        val first = File(dir, "first.log")
        val second = File(dir, "second.log")
        RandomAccessFile(first, "rw").use { it.setLength(SPLIT_PROMPT_BYTES) }
        RandomAccessFile(second, "rw").use { it.setLength(SPLIT_PROMPT_BYTES) }
        val state = AppState(autosaveFile = File(dir, "state.cache"))

        state.openPaths(listOf(first, second))

        assertEquals(listOf("first.log", "second.log"), state.pendingSplitPrompt?.sources?.map { it.displayName })
        assertTrue(state.tabs.isEmpty())
    }

    @Test
    fun confirmingSelectedBatchSplitOpensSelectedPartsAndDeferredNormalFiles() {
        val dir = createTempDirectory("openlog-split-batch-confirm").toFile()
        val first = File(dir, "first.log").apply { writeText("one\ntwo\n") }
        val second = File(dir, "second.log").apply { writeText("three\nfour\n") }
        val normal = File(dir, "normal.log").apply { writeText("") }
        val out = File(dir, "out")
        val state = AppState(autosaveFile = File(dir, "state.cache"))
        state.openPaths(listOf(first, second, normal), splitPromptThresholdBytes = 5L)
        val firstId = state.pendingSplitPrompt!!.sources.single { it.displayName == "first.log" }.id
        val secondId = state.pendingSplitPrompt!!.sources.single { it.displayName == "second.log" }.id

        state.confirmSplitPrompt(
            modes = mapOf(firstId to SplitMode.SPLIT, secondId to SplitMode.OPEN_AS_IS),
            destinationDir = out,
            postfix = "part",
            partCounts = mapOf(firstId to 2, secondId to 2),
            postfixes = mapOf(firstId to "chunk", secondId to "ignored"),
        )

        waitUntil { state.tabs.size == 4 && !state.isLoading }
        assertEquals(
            listOf("first_chunk_1.log", "first_chunk_2.log", "normal.log", "second.log"),
            state.tabs.map { it.filename }.sorted(),
        )
    }

    @Test
    fun selectedArchiveEntriesCreateBatchSplitPromptForOversizedCandidates() {
        val dir = createTempDirectory("openlog-archive-split").toFile()
        val archive = buildZipFixture(
            dir,
            "bugreport.zip",
            mapOf(
                "small_log.txt" to "06-26 10:00:00.000  100  100 I App: small\n",
                "large_log.txt" to "06-26 10:00:01.000  100  100 I App: large\n",
            ),
        )
        val candidates = com.openlog.utils.listArchiveLogCandidates(archive)
        val large = candidates.single { it.entryPath == "large_log.txt" }.copy(sizeBytes = SPLIT_PROMPT_BYTES)
        val small = candidates.single { it.entryPath == "small_log.txt" }
        val state = AppState(autosaveFile = File(dir, "state.cache"))

        state.openZipEntries(archive, listOf(small, large))

        assertEquals(listOf("large_log.txt"), state.pendingSplitPrompt?.sources?.map { it.displayName })
        assertTrue(state.tabs.isEmpty())
    }

    @Test
    fun newlyOpenedArchiveEntryUsesUnfilteredDefaultSetting() {
        val dir = createTempDirectory("openlog-archive-unfiltered-default").toFile()
        val archive = buildZipFixture(
            dir,
            "bugreport.zip",
            mapOf("logcat.txt" to "06-26 10:00:00.000  100  100 I App: hello\n"),
        )
        val state = AppState(autosaveFile = File(dir, "state.cache"))
        state.settings = state.settings.copy(openNewFilesWithUnfiltered = true)

        state.openZipEntries(archive, com.openlog.utils.listArchiveLogCandidates(archive))

        waitUntil { state.tabs.size == 1 && !state.isLoading }
        assertTrue(state.tabs.single().showUnfiltered)
    }

    @Test
    fun mergeTabsCreatesANewTabInterleavedByTimeAndTaggedBySource() {
        val dir = createTempDirectory("openlog-merge").toFile()
        val state = AppState(autosaveFile = File(dir, "state.cache"))
        state.tabs = listOf(
            mkTab(
                "t1", "main.log",
                listOf(
                    LogEntry(1, "10:00:00.000", LogLevel.I, "App", "main first"),
                    LogEntry(2, "10:00:02.000", LogLevel.I, "App", "main second"),
                ),
            ),
            mkTab("t2", "system.log", listOf(LogEntry(1, "10:00:01.000", LogLevel.I, "Sys", "system first"))),
        )

        state.mergeTabs(listOf("t1", "t2"), "Merged Session")

        waitUntil { state.tabs.size == 3 }
        val merged = state.tabs.last()
        assertEquals("Merged Session", merged.filename)
        assertEquals(listOf("main first", "system first", "main second"), merged.logData.map { it.msg })
        assertEquals(listOf(1, 2, 3), merged.logData.map { it.id })
        assertEquals(listOf("main.log", "system.log", "main.log"), merged.logData.map { it.sourceTag })
        assertEquals(state.activeTabId, merged.id)
    }

    @Test
    fun mergeTabsWithFewerThanTwoValidTabIdsIsANoOp() {
        val dir = createTempDirectory("openlog-merge").toFile()
        val state = AppState(autosaveFile = File(dir, "state.cache"))
        state.tabs = listOf(mkTab("t1", "main.log", listOf(LogEntry(1, "10:00:00.000", LogLevel.I, "App", "hi"))))

        state.mergeTabs(listOf("t1"), "Merged")

        assertEquals(1, state.tabs.size)
    }

    @Test
    fun startTailingAppendsNewLinesContinuingIdNumberingWithoutDuplicates() {
        val dir = createTempDirectory("openlog-tailing").toFile()
        val file = File(dir, "tail.log").apply { writeText("06-26 10:00:00.000  100  100 I App: first\n") }
        val state = AppState(autosaveFile = File(dir, "state.cache"))
        state.openFile(file)
        waitUntil { state.tabs.size == 1 && !state.isLoading }
        val tabId = state.tabs.single().id
        assertEquals(1, state.tab(tabId)!!.logData.size)

        state.startTailing(tabId)
        assertTrue(state.tab(tabId)!!.tailing)

        file.appendText("06-26 10:00:01.000  100  100 I App: second\n")
        waitUntil { state.tab(tabId)!!.logData.size == 2 }
        file.appendText("06-26 10:00:02.000  100  100 I App: third\n")
        waitUntil { state.tab(tabId)!!.logData.size == 3 }

        val entries = state.tab(tabId)!!.logData
        assertEquals(listOf(1, 2, 3), entries.map { it.id })
        assertEquals(listOf("first", "second", "third"), entries.map { it.msg })
        assertEquals(3, entries.map { it.id }.toSet().size)

        state.stopTailing(tabId)
        assertFalse(state.tab(tabId)!!.tailing)
    }

    @Test
    fun startTailingAccumulatesTagCountsAcrossBatchesInsteadOfOverwriting() {
        // PERF-6: tagCounts used to be recomputed by regrouping the tab's entire (ever-growing)
        // logData on every ~500ms tail batch. It's now built incrementally from the previous
        // batch's counts via merge(tag, 1, Int::plus) — pin that the running totals actually sum
        // across several separate batches, including a tag ("App") that appears in more than one
        // batch, rather than the map `+` operator silently overwriting its count each time.
        val dir = createTempDirectory("openlog-tailing-tagcounts").toFile()
        val file = File(dir, "tail.log").apply { writeText("06-26 10:00:00.000  100  100 I App: first\n") }
        val state = AppState(autosaveFile = File(dir, "state.cache"))
        state.openFile(file)
        waitUntil { state.tabs.size == 1 && !state.isLoading }
        val tabId = state.tabs.single().id
        assertEquals(mapOf("App" to 1), state.tab(tabId)!!.analysis.tagCounts)

        state.startTailing(tabId)

        file.appendText("06-26 10:00:01.000  100  100 I App: second\n")
        waitUntil { state.tab(tabId)!!.logData.size == 2 }
        assertEquals(mapOf("App" to 2), state.tab(tabId)!!.analysis.tagCounts)

        file.appendText(
            "06-26 10:00:02.000  100  100 I Binder: third\n" +
                "06-26 10:00:02.001  100  100 I Binder: fourth\n",
        )
        waitUntil { state.tab(tabId)!!.logData.size == 4 }
        assertEquals(mapOf("App" to 2, "Binder" to 2), state.tab(tabId)!!.analysis.tagCounts)

        state.stopTailing(tabId)
    }

    @Test
    fun tailedCrashDataStaysPendingDuringABurstThenResolvesOnceItSettles() {
        // P-04: appendTailedLines used to call buildLogAnalysis() — the full crash/stack-trace
        // scan — on every single tail batch. It's now debounced instead, reusing the same
        // pending=true "still analyzing" state Task 04 already wired FilterPanel/Filter.kt to
        // render correctly, so a batch landing must not immediately show a stale/empty crash-site
        // list that looks indistinguishable from "analyzed, found nothing."
        val dir = createTempDirectory("openlog-tailing-analysis").toFile()
        val file = File(dir, "tail.log").apply { writeText("06-26 10:00:00.000  100  100 I App: first\n") }
        val state = AppState(autosaveFile = File(dir, "state.cache"))
        state.openFile(file)
        waitUntil { state.tabs.size == 1 && !state.isLoading }
        val tabId = state.tabs.single().id
        assertFalse(state.tab(tabId)!!.analysis.pending)

        state.startTailing(tabId)
        file.appendText(
            "06-26 10:00:01.000  100  100 E AndroidRuntime: FATAL EXCEPTION: main\n" +
                "06-26 10:00:01.001  100  100 E AndroidRuntime:     at com.app.Main.onCreate(Main.java:10)\n",
        )
        waitUntil { state.tab(tabId)!!.logData.size == 3 }

        assertTrue(state.tab(tabId)!!.analysis.pending, "analysis must stay pending until the debounced refresh fires")

        waitUntil(timeoutMs = 5_000) { !state.tab(tabId)!!.analysis.pending }
        assertTrue(state.tab(tabId)!!.analysis.crashSites.isNotEmpty(), "the debounced refresh must eventually pick up the crash")

        state.stopTailing(tabId)
    }

    @Test
    fun startTailingIsANoOpForATabWithNoBackingFile() {
        val dir = createTempDirectory("openlog-tailing").toFile()
        val state = AppState(autosaveFile = File(dir, "state.cache"))
        state.tabs = listOf(mkTab("t1", "merged", listOf(LogEntry(1, "10:00:00.000", LogLevel.I, "App", "hi"))))

        state.startTailing("t1")

        assertFalse(state.tab("t1")!!.tailing)
    }

    @Test
    fun contextMenuCanAddHideAndShowOnlyMessageRules() {
        val state = AppState()
        state.tabs = listOf(
            mkTab(
                "log",
                "test.log",
                listOf(LogEntry(1, "10:00:00.000", LogLevel.I, "com.app.Network", "heartbeat"))
            )
        )
        state.ctx = CtxMenuState("log", 1, 0f, 0f, "")

        state.hideMessagesLikeCtx()
        state.ctx = CtxMenuState("log", 1, 0f, 0f, "")
        state.showOnlyMessagesLikeCtx()

        val rules = state.tabs.single().filter.messageRules
        assertEquals(1, rules.size)
        assertTrue(rules.single().include)
        assertEquals("heartbeat", rules.single().pattern)
        assertEquals("com.app.Network", rules.single().tag)
        assertEquals(null, rules.single().packagePrefix)
    }

    @Test
    fun contextMenuMessageRulesUseSelectedTextWithinClickedTag() {
        val state = AppState()
        state.tabs = listOf(
            mkTab(
                "log",
                "test.log",
                listOf(LogEntry(1, "10:00:00.000", LogLevel.I, "com.app.Network", "request timeout after 5s"))
            )
        )

        state.ctx = CtxMenuState("log", 1, 0f, 0f, "timeout")
        state.hideMessagesLikeCtx()
        state.ctx = CtxMenuState("log", 1, 0f, 0f, "timeout")
        state.showOnlyMessagesLikeCtx()

        val rules = state.tabs.single().filter.messageRules
        assertEquals(listOf("timeout"), rules.map { it.pattern })
        assertEquals(listOf(true), rules.map { it.include })
        assertEquals(listOf("com.app.Network"), rules.map { it.tag })
        assertEquals(listOf(null), rules.map { it.packagePrefix })
    }

    // ── requestScrollAnchor (via filter-changing ctx actions) ────────────────
    // Filter/tag/keyword ctx actions can change which rows exist above the current scroll
    // position; each one must re-request the jump-to-log-line navigation for the row it just
    // acted on, so the viewport re-centers there instead of leaving a stale scroll index pointing
    // at whatever row now happens to occupy that slot.

    @Test
    fun hideMessagesLikeCtxDoesNotRequestScrollAnchor() {
        // Hiding excludes the acted-on row from the filtered view by definition — anchoring on it
        // would just burn cycles (expansionAndIndexForEntry can never find it) for no visible
        // benefit, which is exactly what made this action feel like a hang on large files.
        val state = AppState()
        state.tabs = listOf(mkTab("log", "test.log", listOf(LogEntry(1, "10:00:00.000", LogLevel.I, "App", "boom"))))
        state.ctx = CtxMenuState("log", 1, 0f, 0f, "")

        state.hideMessagesLikeCtx()

        assertEquals(null, state.pendingAnnotationNavigation)
    }

    @Test
    fun showOnlyMessagesLikeCtxRequestsScrollAnchorForActedOnRow() {
        val state = AppState()
        state.tabs = listOf(mkTab("log", "test.log", listOf(LogEntry(1, "10:00:00.000", LogLevel.I, "App", "boom"))))
        state.ctx = CtxMenuState("log", 1, 0f, 0f, "")

        state.showOnlyMessagesLikeCtx()

        assertEquals(listOf(1), state.pendingAnnotationNavigation?.logIds)
    }

    @Test
    fun addTagFilterFromCtxRequestsScrollAnchorForActedOnRow() {
        val state = AppState()
        state.tabs = listOf(mkTab("log", "test.log", listOf(LogEntry(1, "10:00:00.000", LogLevel.I, "App", "boom"))))
        state.ctx = CtxMenuState("log", 1, 0f, 0f, "")

        state.addTagFilterFromCtx()

        assertEquals(listOf(1), state.pendingAnnotationNavigation?.logIds)
    }

    @Test
    fun addExcludeTagFromCtxDoesNotRequestScrollAnchor() {
        val state = AppState()
        state.tabs = listOf(mkTab("log", "test.log", listOf(LogEntry(1, "10:00:00.000", LogLevel.I, "App", "boom"))))
        state.ctx = CtxMenuState("log", 1, 0f, 0f, "")

        state.addExcludeTagFromCtx()

        assertEquals(null, state.pendingAnnotationNavigation)
    }

    // ── messageRuleVariantsFromCtx / hideMessagesLikeVariant / showOnlyMessagesLikeVariant ────

    @Test
    fun messageRuleVariantsWithoutSelectionOffersFourSeparatorTruncatedChoices() {
        val state = AppState()
        val entry = LogEntry(1, "10:00:00.000", LogLevel.I, "Net", "conn/reset-retrying,attempt 3")
        state.tabs = listOf(mkTab("log", "test.log", listOf(entry)))
        state.ctx = CtxMenuState("log", 1, 0f, 0f, "")

        val variants = state.messageRuleVariantsFromCtx()

        assertEquals(
            listOf(
                Triple("Net: conn", "conn", "Net"),
                Triple("Net: conn/reset", "conn/reset", "Net"),
                Triple("conn", "conn", null),
                Triple("conn/reset", "conn/reset", null),
            ),
            variants.map { Triple(it.label, it.pattern, it.tag) },
        )
    }

    @Test
    fun messageRuleVariantsHandleColonAndEqualsSeparators() {
        // Exact reported example: "Card stack expanded: stackId=stack_home" — ':' is the 1st
        // separator, '=' is the 2nd.
        val state = AppState()
        val entry = LogEntry(
            1, "20:15:00.457", LogLevel.D, "com.my.app.ui.PetsScreen",
            "Card stack expanded: stackId=stack_home",
        )
        state.tabs = listOf(mkTab("log", "test.log", listOf(entry)))
        state.ctx = CtxMenuState("log", 1, 0f, 0f, "")

        val variants = state.messageRuleVariantsFromCtx()

        assertEquals(
            listOf(
                Triple("com.my.app.ui.PetsScreen: Card stack expanded", "Card stack expanded", "com.my.app.ui.PetsScreen"),
                Triple(
                    "com.my.app.ui.PetsScreen: Card stack expanded: stackId",
                    "Card stack expanded: stackId",
                    "com.my.app.ui.PetsScreen",
                ),
                Triple("Card stack expanded", "Card stack expanded", null),
                Triple("Card stack expanded: stackId", "Card stack expanded: stackId", null),
            ),
            variants.map { Triple(it.label, it.pattern, it.tag) },
        )
    }

    @Test
    fun messageRuleVariantsWithSelectionMatchingExactReportedExample() {
        val state = AppState()
        val entry = LogEntry(
            1, "20:15:00.457", LogLevel.D, "com.my.app.ui.PetsScreen",
            "Card stack expanded: stackId=stack_home",
        )
        state.tabs = listOf(mkTab("log", "test.log", listOf(entry)))
        state.ctx = CtxMenuState("log", 1, 0f, 0f, "Card stack expanded: stackId")

        val variants = state.messageRuleVariantsFromCtx()

        assertEquals(
            listOf(
                Triple(
                    "com.my.app.ui.PetsScreen: Card stack expanded: stackId",
                    "Card stack expanded: stackId",
                    "com.my.app.ui.PetsScreen",
                ),
                Triple("Card stack expanded: stackId", "Card stack expanded: stackId", null),
            ),
            variants.map { Triple(it.label, it.pattern, it.tag) },
        )
    }

    @Test
    fun messageRuleVariantsCollapseToTwoWhenMessageHasNoSeparators() {
        val state = AppState()
        val entry = LogEntry(1, "10:00:00.000", LogLevel.I, "Net", "nofillerhere")
        state.tabs = listOf(mkTab("log", "test.log", listOf(entry)))
        state.ctx = CtxMenuState("log", 1, 0f, 0f, "")

        val variants = state.messageRuleVariantsFromCtx()

        assertEquals(
            listOf(
                Triple("Net: nofillerhere", "nofillerhere", "Net"),
                Triple("nofillerhere", "nofillerhere", null),
            ),
            variants.map { Triple(it.label, it.pattern, it.tag) },
        )
    }

    @Test
    fun messageRuleVariantsWithSelectionOffersTagScopedAndUnscopedChoices() {
        val state = AppState()
        val entry = LogEntry(1, "10:00:00.000", LogLevel.I, "Net", "conn/reset-retrying,attempt 3")
        state.tabs = listOf(mkTab("log", "test.log", listOf(entry)))
        state.ctx = CtxMenuState("log", 1, 0f, 0f, "attempt 3")

        val variants = state.messageRuleVariantsFromCtx()

        assertEquals(
            listOf(
                Triple("Net: attempt 3", "attempt 3", "Net"),
                Triple("attempt 3", "attempt 3", null),
            ),
            variants.map { Triple(it.label, it.pattern, it.tag) },
        )
    }

    @Test
    fun hideMessagesLikeVariantAppliesTheChosenScopeAndPattern() {
        val state = AppState()
        val entry = LogEntry(1, "10:00:00.000", LogLevel.I, "Net", "conn/reset-retrying")
        state.tabs = listOf(mkTab("log", "test.log", listOf(entry)))
        state.ctx = CtxMenuState("log", 1, 0f, 0f, "")
        val unscoped = state.messageRuleVariantsFromCtx().first { it.tag == null }

        state.hideMessagesLikeVariant(unscoped)

        val rule = state.tabs.single().filter.messageRules.single()
        assertEquals(false, rule.include)
        assertEquals(unscoped.pattern, rule.pattern)
        assertEquals(null, rule.tag)
        assertEquals(null, state.ctx)
        assertEquals(null, state.pendingAnnotationNavigation)
    }

    @Test
    fun showOnlyMessagesLikeVariantAppliesTheChosenScopeAndPattern() {
        val state = AppState()
        val entry = LogEntry(1, "10:00:00.000", LogLevel.I, "Net", "conn/reset-retrying")
        state.tabs = listOf(mkTab("log", "test.log", listOf(entry)))
        state.ctx = CtxMenuState("log", 1, 0f, 0f, "")
        val tagScoped = state.messageRuleVariantsFromCtx().first { it.tag == "Net" }

        state.showOnlyMessagesLikeVariant(tagScoped)

        val rule = state.tabs.single().filter.messageRules.single()
        assertEquals(true, rule.include)
        assertEquals(tagScoped.pattern, rule.pattern)
        assertEquals("Net", rule.tag)
    }

    @Test
    fun collapseSelectedLinesFromCtxCoversMinToMaxOfTheSelection() {
        val state = AppState()
        val entries = (1..5).map { LogEntry(it, "10:00:00.00$it", LogLevel.I, "App", "msg $it") }
        state.tabs = listOf(mkTab("log", "test.log", entries))
        state.ctx = CtxMenuState("log", 3, 0f, 0f, "")

        state.collapseSelectedLinesFromCtx("log", setOf(4, 2))

        val block = state.tabs.single().manualBlocks.single()
        assertEquals(ManualCollapseDirection.RANGE, block.direction)
        assertEquals(2, block.anchorId)
        assertEquals(4, block.endId)
        assertEquals(null, state.ctx)
    }

    @Test
    fun collapseSelectedLinesFromCtxIsNoOpForASingleLine() {
        val state = AppState()
        state.tabs = listOf(mkTab("log", "test.log", listOf(LogEntry(1, "10:00:00.000", LogLevel.I, "App", "hi"))))
        state.ctx = CtxMenuState("log", 1, 0f, 0f, "")

        state.collapseSelectedLinesFromCtx("log", setOf(1))

        assertTrue(state.tabs.single().manualBlocks.isEmpty())
    }

    @Test
    fun manualCollapseAvailabilityRejectsFileEdgeNoOps() {
        val entries = (1..3).map { LogEntry(it, "10:00:00.00$it", LogLevel.I, "App", "msg $it") }
        val tab = mkTab("log", "test.log", entries)

        assertEquals(
            ManualCollapseAvailability.NOOP_RANGE,
            manualCollapseAvailability(tab, anchorId = 1, direction = ManualCollapseDirection.TO_START),
        )
        assertEquals(
            ManualCollapseAvailability.NOOP_RANGE,
            manualCollapseAvailability(tab, anchorId = 3, direction = ManualCollapseDirection.TO_END),
        )
        assertEquals(
            ManualCollapseAvailability.NOOP_RANGE,
            manualCollapseAvailability(tab, anchorId = 2, direction = ManualCollapseDirection.RANGE, endId = 2),
        )
        assertEquals(
            ManualCollapseAvailability.MISSING_ROW,
            manualCollapseAvailability(tab, anchorId = 99, direction = ManualCollapseDirection.TO_END),
        )
    }

    @Test
    fun manualCollapseAvailabilityAllowsUnlimitedAdjacentBlocksButRejectsOverlapsEvenWhenDisabled() {
        val entries = (1..6).map { LogEntry(it, "10:00:00.00$it", LogLevel.I, "App", "msg $it") }
        val tab = mkTab("log", "test.log", entries).copy(
            manualBlocks = listOf(
                ManualCollapseBlock("start", 1, ManualCollapseDirection.RANGE, endId = 2),
                ManualCollapseBlock("end", 5, ManualCollapseDirection.RANGE, enabled = false, endId = 6),
            ),
        )

        assertEquals(
            ManualCollapseAvailability.AVAILABLE,
            manualCollapseAvailability(tab, anchorId = 3, direction = ManualCollapseDirection.RANGE, endId = 4),
        )
        assertEquals(
            ManualCollapseAvailability.OVERLAPS_EXISTING,
            manualCollapseAvailability(tab, anchorId = 2, direction = ManualCollapseDirection.RANGE, endId = 4),
        )
    }

    @Test
    fun manualCollapseAvailabilityAllowsRangeInsideSequence() {
        val entries = (1..5).map { LogEntry(it, "10:00:00.00$it", LogLevel.I, "App", "msg $it") }
        val sequence = SequenceDef(
            id = "seq",
            matchText = "msg 2",
            priority = 1,
            color = SEQ_COLORS[0],
            endMatchText = "msg 5",
        )
        val tab = mkTab("log", "test.log", entries).copy(
            filter = Filter(sequences = listOf(sequence)),
            expanded = setOf("seq"),
        )

        assertEquals(
            ManualCollapseAvailability.AVAILABLE,
            manualCollapseAvailability(tab, anchorId = 3, direction = ManualCollapseDirection.RANGE, endId = 4),
        )
        assertEquals(
            ManualCollapseAvailability.AVAILABLE,
            manualCollapseAvailability(tab, anchorId = 3, direction = ManualCollapseDirection.TO_START),
        )
        assertEquals(
            ManualCollapseAvailability.AVAILABLE,
            manualCollapseAvailability(tab, anchorId = 3, direction = ManualCollapseDirection.TO_END),
        )
    }

    @Test
    fun collapseToFileEdgesFromInsideSequenceUsesSourceRangesAndStillSupportsExpandAll() {
        val entries = (1..5).map { LogEntry(it, "10:00:00.00$it", LogLevel.I, "App", "msg $it") }
        val sequence = SequenceDef(
            id = "seq",
            matchText = "msg 2",
            priority = 1,
            color = SEQ_COLORS[0],
            endMatchText = "msg 5",
        )

        fun stateInsideSequence() = AppState().also { state ->
            state.tabs = listOf(mkTab("log", "test.log", entries).copy(filter = Filter(sequences = listOf(sequence))))
            state.activeTabId = "log"
        }

        val startState = stateInsideSequence()
        startState.ctx = CtxMenuState("log", 3, 0f, 0f, "")
        startState.collapseToStartFromCtx()
        assertEquals(ManualCollapseDirection.TO_START, startState.tabs.single().manualBlocks.single().direction)
        startState.expandAll("log")
        assertTrue(startState.tabs.single().expanded.isNotEmpty())
        startState.collapseAll("log")
        assertTrue(startState.tabs.single().expanded.isEmpty())

        val endState = stateInsideSequence()
        endState.ctx = CtxMenuState("log", 3, 0f, 0f, "")
        endState.collapseToEndFromCtx()
        assertEquals(ManualCollapseDirection.TO_END, endState.tabs.single().manualBlocks.single().direction)
    }

    @Test
    fun collapseActionsDoNotCreateOverlappingManualBlocks() {
        val state = AppState()
        val entries = (1..5).map { LogEntry(it, "10:00:00.00$it", LogLevel.I, "App", "msg $it") }
        state.tabs = listOf(
            mkTab("log", "test.log", entries).copy(
                manualBlocks = listOf(ManualCollapseBlock("existing", 2, ManualCollapseDirection.RANGE, endId = 4)),
            ),
        )
        state.ctx = CtxMenuState("log", 3, 0f, 0f, "")

        state.collapseToEndFromCtx()

        assertEquals(listOf("existing"), state.tabs.single().manualBlocks.map { it.id })
    }

    @Test
    fun canDisableAndRemoveManualCollapseBlocks() {
        val state = AppState()
        state.tabs = listOf(
            mkTab(
                "log",
                "test.log",
                listOf(
                    LogEntry(1, "10:00:00.000", LogLevel.I, "App", "Boom"),
                    LogEntry(2, "10:00:00.001", LogLevel.I, "App", "After"),
                ),
            ),
        )
        state.ctx = CtxMenuState("log", 1, 0f, 0f, "")
        state.collapseToEndFromCtx()
        val blockId = state.tabs.single().manualBlocks.single().id

        state.toggleManualCollapse("log", blockId)
        assertEquals(false, state.tabs.single().manualBlocks.single().enabled)

        state.removeManualCollapse("log", blockId)
        assertTrue(state.tabs.single().manualBlocks.isEmpty())
    }

    @Test
    fun contextMenuStartAndEndCreatesStartEndSequence() {
        val state = AppState()
        state.tabs = listOf(
            mkTab(
                "log",
                "test.log",
                listOf(
                    LogEntry(1, "10:00:00.000", LogLevel.I, "com.app.Start", "flow begin"),
                    LogEntry(2, "10:00:00.100", LogLevel.I, "com.app.End", "flow done"),
                ),
            ),
        )
        state.activeTabId = "log"

        state.ctx = CtxMenuState("log", 1, 0f, 0f, "")
        state.setSequenceStartFromCtx()
        state.ctx = CtxMenuState("log", 2, 0f, 0f, "")
        state.completeSequenceEndFromCtx()

        val sequence = state.sequences.single()
        assertEquals("flow begin", sequence.matchText)
        assertEquals("com.app.Start", sequence.tag)
        assertEquals("flow done", sequence.endMatchText)
        assertEquals("com.app.End", sequence.endTag)
    }

    @Test
    fun addSequenceVariantAppliesTheChosenScopeAndPattern() {
        val state = AppState()
        val entry = LogEntry(1, "10:00:00.000", LogLevel.I, "Net", "conn/reset-retrying")
        state.tabs = listOf(mkTab("log", "test.log", listOf(entry)))
        state.activeTabId = "log"
        state.ctx = CtxMenuState("log", 1, 0f, 0f, "")
        val unscoped = state.messageRuleVariantsFromCtx().first { it.tag == null }

        state.addSequenceVariant(unscoped)

        val sequence = state.sequences.single()
        assertEquals(unscoped.pattern, sequence.matchText)
        assertEquals(null, sequence.tag)
        assertEquals(null, state.ctx)
    }

    @Test
    fun canEditSequenceAndAddEnd() {
        val state = AppState()
        state.addTab()
        state.addSequence("flow begin", false, Color.Red, "com.app.Start")
        val id = state.sequences.single().id

        state.updateSequence(
            id = id,
            matchText = "flow begin",
            isRegex = false,
            tag = "com.app.Start",
            endMatchText = "flow done",
            endIsRegex = false,
            endTag = "com.app.End",
        )

        val sequence = state.sequences.single()
        assertEquals("flow done", sequence.endMatchText)
        assertEquals("com.app.End", sequence.endTag)
    }

    // ── Token serialization round-trips ───────────────────────────────────────

    @Test
    fun highlighterRoundTripViaFilterSaveAndLoad() {
        val state = AppState()
        state.addTab()
        val tabId = state.tabs.single().id
        state.addHl(tabId, "error", false, Color.Red)

        state.saveFilter(tabId, "hl-filter")
        state.clearFilter(tabId)
        state.loadFilter(tabId, state.savedFilters.single())

        val hl = state.tabs.single().filter.highlighters.single()
        assertEquals("error", hl.pattern)
        assertEquals(false, hl.regex)
        assertEquals(Color.Red, hl.color)
        assertTrue(hl.on)
    }

    @Test
    fun highlighterPatternWithPipeCharSurvivesRoundTrip() {
        val state = AppState()
        state.addTab()
        val tabId = state.tabs.single().id
        // | is the field delimiter; b64 encoding must protect it
        state.addHl(tabId, "error|crash", false, Color.Yellow)

        state.saveFilter(tabId, "special-hl")
        state.clearFilter(tabId)
        state.loadFilter(tabId, state.savedFilters.single())

        assertEquals("error|crash", state.tabs.single().filter.highlighters.single().pattern)
    }

    @Test
    fun messageRuleRoundTripViaFilterSaveAndLoad() {
        val state = AppState()
        state.addTab()
        val tabId = state.tabs.single().id
        state.setFilterMode(tabId, FilterMode.KEYWORD)
        state.addMessageRule(tabId, include = true, pattern = "timeout", regex = true, tag = "NetTag", packagePrefix = null)

        state.saveFilter(tabId, "rule-filter")
        state.clearFilter(tabId)
        state.loadFilter(tabId, state.savedFilters.single())

        val rule = state.tabs.single().filter.messageRules.single()
        assertEquals("timeout", rule.pattern)
        assertEquals(true, rule.include)
        assertEquals(true, rule.regex)
        assertEquals("NetTag", rule.tag)
        // The rule was created while in KEYWORD mode — the save/load token round-trip must
        // preserve that, not silently default it back to TAGS.
        assertEquals(FilterMode.KEYWORD, rule.mode)
    }

    @Test
    fun settingsNonDefaultFieldsRoundTripViaAutosave() {
        val dir = createTempDirectory("openlog-settings").toFile()
        val cacheFile = File(dir, "state.cache")
        val state = AppState(cacheFile)
        state.updateSettings {
            it.copy(
                fontSize = 16,
                fontMono = false,
                mostUsedTagLimit = 10,
                autoSaveFilters = false,
                annotationLogBlockStyle = AnnotationLogBlockStyle.JIRA_JAVA,
                numberAnnotationBlocks = true,
                logRowWrapLimitChars = 1200,
                autoLogRowWrap = false,
                maskWordOnCopy = true,
                maskWordTarget = "kotlin",
                maskWordReplacement = "k*otlin",
                copyMaskRules = listOf(
                    CopyMaskRule("kotlin", ""),
                    CopyMaskRule("timeout", "delayed"),
                ),
                highlightEntireCrashGroup = true,
                openNewFilesWithUnfiltered = true,
                openUnfilteredOnCtrlF = true,
                mcpAllowBrowserClients = true,
            )
        }

        state.autosaveNow()

        val restored = AppState(cacheFile, restoreOnCreate = true)
        assertEquals(16, restored.settings.fontSize)
        assertEquals(false, restored.settings.fontMono)
        assertEquals(10, restored.settings.mostUsedTagLimit)
        assertEquals(false, restored.settings.autoSaveFilters)
        assertEquals(AnnotationLogBlockStyle.JIRA_JAVA, restored.settings.annotationLogBlockStyle)
        assertEquals(true, restored.settings.numberAnnotationBlocks)
        assertEquals(1200, restored.settings.logRowWrapLimitChars)
        assertEquals(false, restored.settings.autoLogRowWrap)
        assertEquals(true, restored.settings.maskWordOnCopy)
        assertEquals("kotlin", restored.settings.maskWordTarget)
        assertEquals("k*otlin", restored.settings.maskWordReplacement)
        assertEquals(
            listOf(CopyMaskRule("kotlin", ""), CopyMaskRule("timeout", "delayed")),
            restored.settings.copyMaskRules,
        )
        assertEquals(true, restored.settings.highlightEntireCrashGroup)
        assertEquals(true, restored.settings.openNewFilesWithUnfiltered)
        assertEquals(true, restored.settings.openUnfilteredOnCtrlF)
        assertEquals(true, restored.settings.mcpAllowBrowserClients)
    }

    // (ARCH-2/Batch 5) The write path now emits the "settings" line as keyed JSON (settingsJson()
    // in AppState.kt), so this test can no longer derive a legacy blob by stripping fields off a
    // live autosaveNow() write — that write is JSON now, not pipe-delimited. Instead this hand-
    // builds a v1 POSITIONAL settings token from scratch, mirroring the retired settingsToken()'s
    // field order up to (and including) sourceAutoDiscoveryEnabled and deliberately omitting the
    // copyMaskRules/openUnfilteredOnCtrlF/mcpAllowBrowserClients fields that came after it — the
    // same shape a real pre-copyMaskRules cache would have had. This still exercises exactly what
    // the test name says: settingsFromToken()'s legacy-read fallback (restoreAutosaveKey dispatches
    // to it whenever the decoded "settings" value doesn't start with '{') migrating the single
    // legacy maskWordTarget/maskWordReplacement pair into one CopyMaskRule.
    @Test
    fun legacySingleCopyMaskPairMigratesToOneOrderedRule() {
        val dir = createTempDirectory("openlog-copy-mask-legacy").toFile()
        val cacheFile = File(dir, "state.cache")

        fun legacyField(v: String): String =
            if (v.isEmpty()) "~" else java.util.Base64.getUrlEncoder().withoutPadding().encodeToString(v.toByteArray(Charsets.UTF_8))

        val legacyRawToken = listOf(
            "LIGHT", "12", "true", "", "5", "5", "8", "true", "true", "JIRA_JAVA", "false", "From",
            "5", "480", "true", "false", "8991", "true", "kotlin", "k*otlin", "false", "KEYWORD_REGEX", "false",
            "", "", "", "100", "", "", "", "true",
        ).joinToString("|") { legacyField(it) }
        val legacyEncoded = java.util.Base64.getUrlEncoder().withoutPadding()
            .encodeToString(legacyRawToken.toByteArray(Charsets.UTF_8))
        cacheFile.writeText("openLog2-cache-v1\nsettings\t$legacyEncoded\n")

        val restored = AppState(cacheFile, restoreOnCreate = true)

        assertEquals(listOf(CopyMaskRule("kotlin", "k*otlin")), restored.settings.copyMaskRules)
        assertFalse(restored.settings.openUnfilteredOnCtrlF)
    }

    // Every AppSettings field pushed off its default, for settingsJsonRoundTripsEveryFieldAndWritesKeyedJson
    // below — split out so that test stays under detekt's LongMethod threshold. AppSettings (and
    // every type it nests: AiProviderProfile/SourceFolderInfo/SourceLogConfiguration/
    // SourceWrapperRule/CopyMaskRule) is a data class, so a single structural assertEquals against
    // this value proves every field round-tripped, not just the ones a hand-picked list happens
    // to check.
    private fun offDefaultSettingsForJsonRoundTripTest() = AppSettings(
        theme = ThemePreset.DRACULA,
        fontSize = 18,
        fontMono = false,
        defaultSaveDir = "/tmp/openlog-rt-saves",
        mostUsedTagLimit = 11,
        filterListRows = 12,
        visibleTabLimit = 9,
        autoExportNotes = false,
        autoSaveFilters = false,
        annotationLogBlockStyle = AnnotationLogBlockStyle.INDENTED,
        numberAnnotationBlocks = true,
        annotationPrefixLabel = "Cite",
        navScrollMargin = 10,
        logRowWrapLimitChars = 777,
        autoLogRowWrap = false,
        mcpControlEnabled = true,
        mcpControlPort = 9200,
        maskWordOnCopy = true,
        maskWordTarget = "kotlin",
        maskWordReplacement = "k*otlin",
        highlightEntireCrashGroup = true,
        ctrlFTarget = CtrlFTarget.MESSAGE_RULE,
        openNewFilesWithUnfiltered = true,
        openUnfilteredOnCtrlF = true,
        sourceFolders = listOf("/tmp/openlog-rt-src-a", "/tmp/openlog-rt-src-b"),
        editorCommand = "code -g {file}:{line}",
        editorChoice = "custom",
        aiProviderProfiles = listOf(
            AiProviderProfile(
                id = "anthropic-rt",
                displayName = "Anthropic RT",
                baseUrl = "https://api.anthropic.com",
                model = "claude-sonnet-4-5",
                selected = true,
                remoteDisclosureAcknowledged = true,
                kind = AiProviderKind.ANTHROPIC_API,
                executablePath = "",
                reasoningEffort = "high",
            ),
            AiProviderProfile(
                id = "codex-rt",
                displayName = "Codex RT",
                baseUrl = "",
                model = "",
                selected = false,
                remoteDisclosureAcknowledged = false,
                kind = AiProviderKind.CODEX_ACCOUNT,
                executablePath = "/usr/local/bin/codex",
                reasoningEffort = "medium",
            ),
        ),
        aiMaxToolRounds = 222,
        sourceFolderInfo = mapOf(
            "/tmp/openlog-rt-src-a" to
                SourceFolderInfo(description = "Module A", readmePath = "/tmp/openlog-rt-src-a/README.md"),
        ),
        sourceLogConfigurations = listOf(
            SourceLogConfiguration(
                id = "cfg-rt-1",
                name = "Custom Logger",
                wrapperRules = listOf(
                    SourceWrapperRule(
                        ownerType = "com.example.Log",
                        methodName = "d",
                        tagArgumentIndex = 0,
                        messageArgumentIndex = 1,
                        throwableArgumentIndex = 2,
                    ),
                ),
            ),
        ),
        sourceFolderConfigurationIds = mapOf("/tmp/openlog-rt-src-a" to listOf("cfg-rt-1")),
        sourceAutoDiscoveryEnabled = false,
        copyMaskRules = listOf(CopyMaskRule("java", "j*ava"), CopyMaskRule("secret", "s3cret")),
        mcpAllowBrowserClients = true,
    )

    // (ARCH-2/Batch 5) Proves the migration end to end: every AppSettings field pushed off its
    // default round-trips through a real autosaveNow() write, AND the written "settings" line is
    // actually the new keyed-JSON format (not a pipe token that merely happens to decode).
    @Test
    fun settingsJsonRoundTripsEveryFieldAndWritesKeyedJson() {
        val dir = createTempDirectory("openlog-settings-json").toFile()
        val cacheFile = File(dir, "state.cache")
        val state = AppState(cacheFile)
        val expected = offDefaultSettingsForJsonRoundTripTest()
        state.updateSettings { expected }
        state.autosaveNow()

        val lines = cacheFile.readLines()
        val settingsLine = lines.single { it.startsWith("settings\t") }
        val decoded = String(
            java.util.Base64.getUrlDecoder().decode(settingsLine.removePrefix("settings\t")),
            Charsets.UTF_8,
        )
        assertTrue(
            decoded.trimStart().startsWith("{"),
            "settings blob must be written as keyed JSON now, not the legacy pipe token: $decoded",
        )

        val restored = AppState(cacheFile, restoreOnCreate = true)
        assertEquals(expected, restored.settings)
    }

    // Migration default (AutosaveCodec.settingsFromJson): a JSON settings blob written before
    // editorChoice existed had a typed editorCommand but no editorChoice key at all. That must read
    // back as "custom" so the user's existing command keeps being used unchanged (shown as "Custom
    // command…" in Settings) rather than silently switching them to auto-detect.
    @Test
    fun legacyJsonSettingsWithTypedEditorCommandMigratesToCustomEditorChoice() {
        val dir = createTempDirectory("openlog-editor-choice-migrate-custom").toFile()
        val cacheFile = File(dir, "state.cache")
        val legacyJson = """{"editorCommand":"idea --line {line} {file}"}"""
        val encoded = java.util.Base64.getUrlEncoder().withoutPadding()
            .encodeToString(legacyJson.toByteArray(Charsets.UTF_8))
        cacheFile.writeText("openLog2-cache-v1\nsettings\t$encoded\n")

        val restored = AppState(cacheFile, restoreOnCreate = true)

        assertEquals("custom", restored.settings.editorChoice)
        assertEquals("idea --line {line} {file}", restored.settings.editorCommand)
    }

    // Same migration, the other branch: a legacy JSON blob with no editorCommand typed (blank, the
    // pre-existing default) and no editorChoice key must read back as "auto" — the same behavior a
    // blank Open command always had.
    @Test
    fun legacyJsonSettingsWithBlankEditorCommandMigratesToAutoEditorChoice() {
        val dir = createTempDirectory("openlog-editor-choice-migrate-auto").toFile()
        val cacheFile = File(dir, "state.cache")
        val legacyJson = """{"theme":"DRACULA"}"""
        val encoded = java.util.Base64.getUrlEncoder().withoutPadding()
            .encodeToString(legacyJson.toByteArray(Charsets.UTF_8))
        cacheFile.writeText("openLog2-cache-v1\nsettings\t$encoded\n")

        val restored = AppState(cacheFile, restoreOnCreate = true)

        assertEquals("auto", restored.settings.editorChoice)
        assertEquals("", restored.settings.editorCommand)
    }

    // Once editorChoice is written explicitly (current format), it round-trips as-is rather than
    // being recomputed from editorCommand — proves the migration default only kicks in when the key
    // is absent, not whenever editorCommand happens to be non-blank.
    @Test
    fun editorChoiceSurvivesAutosaveRoundTrip() {
        val dir = createTempDirectory("openlog-editor-choice-roundtrip").toFile()
        val cacheFile = File(dir, "state.cache")
        val state = AppState(cacheFile)
        state.updateSettings { it.copy(editorChoice = "vscode", editorCommand = "should be ignored") }
        state.autosaveNow()

        val restored = AppState(cacheFile, restoreOnCreate = true)

        assertEquals("vscode", restored.settings.editorChoice)
    }

    @Test
    fun noteBlockAutosaveSurvivesRoundTrip() {
        val dir = createTempDirectory("openlog-note").toFile()
        val logFile = File(dir, "test.log").apply { writeText("06-26 10:00:00.000  1  1 I App: hello\n") }
        val cacheFile = File(dir, "state.cache")
        val state = AppState(cacheFile, autoExportNotes = false)
        state.tabs = listOf(mkTab("log", "test.log", emptyList()).copy(sourcePath = logFile.absolutePath))
        state.activeTabId = "log"
        state.addNoteBlock("log")
        val noteId = state.tabs.single().annotations.blocks.single().id
        state.updateBlock("log", noteId, "My analysis note")

        state.autosaveNow()

        val restored = AppState(cacheFile, restoreOnCreate = true)
        val block = assertIs<AnnBlock.Note>(restored.tabs.single().annotations.blocks.single())
        assertEquals("My analysis note", block.text)
    }

    @Test
    fun manualCollapseBlockAutosaveSurvivesRoundTrip() {
        val dir = createTempDirectory("openlog-manual").toFile()
        val logFile = File(dir, "test.log").apply { writeText("06-26 10:00:00.000  1  1 I App: hello\n") }
        val cacheFile = File(dir, "state.cache")
        val state = AppState(cacheFile)
        val block = ManualCollapseBlock("m1", 1, ManualCollapseDirection.TO_END, Color.Red, enabled = true)
        state.tabs = listOf(
            mkTab("log", "test.log", emptyList()).copy(
                sourcePath = logFile.absolutePath,
                manualBlocks = listOf(block),
            ),
        )
        state.activeTabId = "log"

        state.autosaveNow()

        val restored = AppState(cacheFile, restoreOnCreate = true)
        val restoredBlock = restored.tabs.single().manualBlocks.single()
        assertEquals("m1", restoredBlock.id)
        assertEquals(1, restoredBlock.anchorId)
        assertEquals(ManualCollapseDirection.TO_END, restoredBlock.direction)
        assertEquals(Color.Red, restoredBlock.color)
        assertEquals(null, restoredBlock.endId)
    }

    @Test
    fun manualCollapseRangeBlockEndIdSurvivesAutosaveRoundTrip() {
        val dir = createTempDirectory("openlog-manual-range").toFile()
        val logFile = File(dir, "test.log").apply { writeText("06-26 10:00:00.000  1  1 I App: hello\n") }
        val cacheFile = File(dir, "state.cache")
        val state = AppState(cacheFile)
        val block = ManualCollapseBlock("m1", 2, ManualCollapseDirection.RANGE, endId = 5)
        state.tabs = listOf(
            mkTab("log", "test.log", emptyList()).copy(
                sourcePath = logFile.absolutePath,
                manualBlocks = listOf(block),
            ),
        )
        state.activeTabId = "log"

        state.autosaveNow()

        val restored = AppState(cacheFile, restoreOnCreate = true)
        val restoredBlock = restored.tabs.single().manualBlocks.single()
        assertEquals(ManualCollapseDirection.RANGE, restoredBlock.direction)
        assertEquals(2, restoredBlock.anchorId)
        assertEquals(5, restoredBlock.endId)
    }

    // ── Filter mutations ──────────────────────────────────────────────────────

    @Test
    fun toggleLevelRemovesItFromFilter() {
        val state = AppState()
        state.addTab()
        val tabId = state.tabs.single().id
        assertTrue(LogLevel.D in state.tabs.single().filter.levels)

        state.toggleLevel(tabId, LogLevel.D)

        assertFalse(LogLevel.D in state.tabs.single().filter.levels)
    }

    @Test
    fun toggleLevelAddsItBackAfterRemoval() {
        val state = AppState()
        state.addTab()
        val tabId = state.tabs.single().id
        state.toggleLevel(tabId, LogLevel.D)
        assertFalse(LogLevel.D in state.tabs.single().filter.levels)

        state.toggleLevel(tabId, LogLevel.D)

        assertTrue(LogLevel.D in state.tabs.single().filter.levels)
    }

    @Test
    fun setPidTidFilterStoresValue() {
        val state = AppState()
        state.addTab()
        val tabId = state.tabs.single().id

        state.setPidTidFilter(tabId, "1234,5678")

        assertEquals("1234,5678", state.tabs.single().filter.pidTidFilter)
    }

    @Test
    fun addMessageRuleAssignsUniqueIds() {
        val state = AppState()
        state.addTab()
        val tabId = state.tabs.single().id

        state.addMessageRule(tabId, include = true, pattern = "error", regex = false, tag = null, packagePrefix = null)
        state.addMessageRule(tabId, include = false, pattern = "debug", regex = false, tag = null, packagePrefix = null)

        val rules = state.tabs.single().filter.messageRules
        assertEquals(2, rules.size)
        assertFalse(rules[0].id == rules[1].id)
    }

    @Test
    fun addingOppositeTagAndMessageRuleReplacesTheExistingPolarity() {
        val state = AppState()
        state.addTab()
        val tabId = state.tabs.single().id

        state.toggleExcludeTag(tabId, "Network")
        state.toggleTag(tabId, "Network")
        assertEquals(setOf("Network"), state.tabs.single().filter.activeTags)
        assertTrue(state.tabs.single().filter.excludeTags.isEmpty())

        state.addMessageRule(tabId, include = false, pattern = "timeout", regex = true, tag = "Network", packagePrefix = null)
        state.addMessageRule(tabId, include = true, pattern = "timeout", regex = true, tag = "Network", packagePrefix = null)
        state.addMessageRule(tabId, include = true, pattern = "timeout", regex = true, tag = "Network", packagePrefix = null)
        val rules = state.tabs.single().filter.messageRules
        assertEquals(1, rules.size)
        assertTrue(rules.single().include)
    }

    @Test
    fun duplicateTabFilenamesUseProgressiveSourceSuffixesAndRecentMenuKeepsAllEntries() {
        val first = mkTab("one", "same.log", emptyList()).copy(sourcePath = "/logs/first/same.log")
        val second = mkTab("two", "same.log", emptyList()).copy(sourcePath = "/logs/second/same.log")
        assertEquals("same.log — first", tabDisplayLabel(first, listOf(first, second)))
        assertEquals("same.log — second", tabDisplayLabel(second, listOf(first, second)))
        assertEquals("same.log", tabDisplayLabel(first, listOf(first)))

        val archiveA = mkTab("archive-a", "same.log", emptyList())
            .copy(sourcePath = "/logs/reports/archive-a.zip!FS/a/same.log")
        val archiveB = mkTab("archive-b", "same.log", emptyList())
            .copy(sourcePath = "/logs/reports/archive-b.zip!FS/b/same.log")
        assertEquals("same.log — archive-a.zip", tabDisplayLabel(archiveA, listOf(archiveA, archiveB)))
        assertEquals("same.log — archive-b.zip", tabDisplayLabel(archiveB, listOf(archiveA, archiveB)))

        val archiveFolderA = mkTab("archive-folder-a", "same.log", emptyList())
            .copy(sourcePath = "/logs/archive.zip!FS/a/same.log")
        val archiveFolderB = mkTab("archive-folder-b", "same.log", emptyList())
            .copy(sourcePath = "/logs/archive.zip!FS/b/same.log")
        assertEquals(
            "same.log — archive.zip/FS/a/same.log",
            tabDisplayLabel(archiveFolderA, listOf(archiveFolderA, archiveFolderB)),
        )
        assertEquals(
            "same.log — archive.zip/FS/b/same.log",
            tabDisplayLabel(archiveFolderB, listOf(archiveFolderA, archiveFolderB)),
        )

        val sameNamedArchiveA = mkTab("archive-path-a", "same.log", emptyList())
            .copy(sourcePath = "/logs/first/archive.zip!FS/a/same.log")
        val sameNamedArchiveB = mkTab("archive-path-b", "same.log", emptyList())
            .copy(sourcePath = "/logs/second/archive.zip!FS/b/same.log")
        assertEquals(
            "same.log — archive.zip/FS/a/same.log",
            tabDisplayLabel(sameNamedArchiveA, listOf(sameNamedArchiveA, sameNamedArchiveB)),
        )
        assertEquals(
            "same.log — archive.zip/FS/b/same.log",
            tabDisplayLabel(sameNamedArchiveB, listOf(sameNamedArchiveA, sameNamedArchiveB)),
        )

        val sameFolderA = mkTab("real-a", "same.log", emptyList()).copy(sourcePath = "/logs/shared/same.log")
        val sameFolderB = mkTab("real-b", "same.log", emptyList()).copy(sourcePath = "/logs/shared/same.log")
        assertEquals("same.log — shared — real-a", tabDisplayLabel(sameFolderA, listOf(sameFolderA, sameFolderB)))
        assertEquals("same.log — shared — real-b", tabDisplayLabel(sameFolderB, listOf(sameFolderA, sameFolderB)))
        assertEquals(30, recentFilesForMenu((1..30).map { "/logs/$it.log" }).size)
    }

    @Test
    fun filterSearchRequestTargetsOnlyItsOriginTabAndIsConsumedOnce() {
        val request = FilterSearchRequest(1, "one", CtrlFTarget.KEYWORD_REGEX)

        assertEquals(CtrlFTarget.KEYWORD_REGEX, filterSearchTargetForTab(request, "one"))
        assertEquals(null, filterSearchTargetForTab(request, "two"))

        val consumed = consumeFilterSearchRequest(request, request)
        assertEquals(null, consumed)
        assertEquals(null, filterSearchTargetForTab(consumed, "one"))
    }

    @Test
    fun filterSearchRequestPreservesTagsAndMessageRuleTargetsForItsOriginTab() {
        val tags = FilterSearchRequest(2, "one", CtrlFTarget.TAGS)
        val messageRules = FilterSearchRequest(3, "two", CtrlFTarget.MESSAGE_RULE)

        assertEquals(CtrlFTarget.TAGS, filterSearchTargetForTab(tags, "one"))
        assertEquals(null, filterSearchTargetForTab(tags, "two"))
        assertEquals(CtrlFTarget.MESSAGE_RULE, filterSearchTargetForTab(messageRules, "two"))
        assertEquals(null, filterSearchTargetForTab(messageRules, "one"))
        assertEquals(messageRules, consumeFilterSearchRequest(messageRules, tags))
    }

    @Test
    fun providerPickerOpensSettingsAtAiProviders() {
        val state = AppState(restoreOnCreate = false)
        try {
            state.openAiProviderSettings()

            assertTrue(state.settingsOpen)
            assertEquals(SettingsSection.AiProviders, state.requestedSettingsSection)
        } finally {
            state.close()
        }
    }

    @Test
    fun addMessageRuleStampsRuleWithTheTabsCurrentFilterMode() {
        val state = AppState()
        state.addTab()
        val tabId = state.tabs.single().id

        state.addMessageRule(tabId, include = true, pattern = "error", regex = false, tag = null, packagePrefix = null)
        assertEquals(FilterMode.TAGS, state.tabs.single().filter.messageRules.single().mode)

        state.setFilterMode(tabId, FilterMode.KEYWORD)
        state.addMessageRule(tabId, include = true, pattern = "warn", regex = false, tag = null, packagePrefix = null)
        val rules = state.tabs.single().filter.messageRules
        assertEquals(FilterMode.TAGS, rules.first { it.pattern == "error" }.mode)
        assertEquals(FilterMode.KEYWORD, rules.first { it.pattern == "warn" }.mode)
    }

    @Test
    fun unscopedManualMessageRuleStoresAllScope() {
        val state = AppState()
        state.addTab()
        val tabId = state.tabs.single().id

        state.addMessageRule(tabId, include = true, pattern = "error", regex = false, tag = null, packagePrefix = null)

        val rule = state.tabs.single().filter.messageRules.single()
        assertEquals(null, rule.tag)
        assertEquals(null, rule.packagePrefix)
    }

    @Test
    fun removeMessageRuleDeletesOnlyTargetRule() {
        val state = AppState()
        state.addTab()
        val tabId = state.tabs.single().id
        state.addMessageRule(tabId, include = true, pattern = "error", regex = false, tag = null, packagePrefix = null)
        state.addMessageRule(tabId, include = false, pattern = "debug", regex = false, tag = null, packagePrefix = null)
        val removeId = state.tabs.single().filter.messageRules.first().id

        state.removeMessageRule(tabId, removeId)

        val remaining = state.tabs.single().filter.messageRules
        assertEquals(1, remaining.size)
        assertEquals("debug", remaining.single().pattern)
    }

    @Test
    fun addPkgPrefixStoresValueAndIsRemovable() {
        val state = AppState()
        state.addTab()
        val tabId = state.tabs.single().id

        state.addPkgPrefix(tabId, "com.example")
        assertEquals(setOf("com.example"), state.tabs.single().filter.pkgPrefixes)

        state.removePkgPrefix(tabId, "com.example")
        assertTrue(state.tabs.single().filter.pkgPrefixes.isEmpty())
    }

    // ── Sequence mutations ────────────────────────────────────────────────────

    @Test
    fun sequenceColorWrapsAroundWhenPaletteExhausted() {
        val state = AppState()
        state.addTab()
        SEQ_COLORS.forEachIndexed { i, color -> state.addSequence("seq$i", false, color) }
        val countBefore = state.sequences.size

        state.addSequence("overflow", false, SEQ_COLORS[0])

        assertEquals(countBefore + 1, state.sequences.size)
        assertTrue(state.sequences.last().color in SEQ_COLORS)
    }

    @Test
    fun moveSequenceUpSwapsWithPreviousEntry() {
        val state = AppState()
        state.addTab()
        state.addSequence("first", false, SEQ_COLORS[0])
        state.addSequence("second", false, SEQ_COLORS[1])
        val firstId = state.sequences[0].id
        val secondId = state.sequences[1].id

        state.moveSequenceUp(secondId)

        assertEquals(secondId, state.sequences[0].id)
        assertEquals(firstId, state.sequences[1].id)
    }

    @Test
    fun moveSequenceDownAtLastIndexIsNoOp() {
        val state = AppState()
        state.addTab()
        state.addSequence("first", false, SEQ_COLORS[0])
        state.addSequence("second", false, SEQ_COLORS[1])
        val secondId = state.sequences[1].id

        state.moveSequenceDown(secondId)

        assertEquals(secondId, state.sequences[1].id)
        assertEquals(2, state.sequences.size)
    }

    @Test
    fun sequenceOrderDuringDragMovesSequenceAsItsCenterCrossesNeighbors() {
        val order = sequenceOrderDuringDrag(
            visibleIds = listOf("first", "second", "third", "fourth"),
            draggedId = "first",
            dragStartIndex = 0,
            dragOffsetY = 108f,
            rowHeight = 32f,
        )

        assertEquals(listOf("second", "third", "fourth", "first"), order)
    }

    @Test
    fun sequenceRenderYMatchesTabDragAnimationRules() {
        assertEquals(
            46f,
            sequenceRenderY(
                isDragging = true,
                isJustReleased = false,
                pointerY = 46f,
                targetY = 80f,
                animatedY = 20f,
            ),
        )
        assertEquals(
            20f,
            sequenceRenderY(
                isDragging = false,
                isJustReleased = false,
                pointerY = 46f,
                targetY = 80f,
                animatedY = 20f,
            ),
        )
        assertEquals(
            80f,
            sequenceRenderY(
                isDragging = false,
                isJustReleased = true,
                pointerY = 46f,
                targetY = 80f,
                animatedY = 20f,
            ),
        )
    }

    @Test
    fun draggedSequenceRowUsesOpaqueBaseBackground() {
        val tc = themeColors(ThemePreset.LIGHT)

        val background = sequenceRowBaseBackground(isDragging = true, enabled = true, theme = tc)

        assertEquals(1f, background.alpha)
        assertEquals(tc.p, background)
    }

    @Test
    fun sequenceVisualOrderDoesNotSyncBackWhileReleaseAnimationIsSettling() {
        assertFalse(shouldSyncSequenceVisualOrder(dragId = null, justReleasedSequenceId = "seq-1"))
        assertTrue(shouldSyncSequenceVisualOrder(dragId = null, justReleasedSequenceId = null))
    }

    @Test
    fun cumulativeBlockOffsetsComputesRunningTotalOfVariableHeights() {
        val heights = mapOf("a" to 20f, "b" to 100f, "c" to 20f)

        val offsets = cumulativeBlockOffsets(listOf("a", "b", "c")) { heights.getValue(it) }

        assertEquals(mapOf("a" to 0f, "b" to 20f, "c" to 120f), offsets)
    }

    @Test
    fun blockOrderDuringDragAccountsForVariableBlockHeights() {
        // "a" (20) starts above a much taller "b" (100) and a short "c" (20). Dragging "a" down
        // by 90 moves its center (10 -> 100, plus a snap bias) past "b"'s center (70) but not
        // "c"'s (130) — unlike sequences' uniform rowHeight, this only resolves correctly if the
        // reorder math uses each block's own measured height rather than a fixed row height.
        val heights = mapOf("a" to 20f, "b" to 100f, "c" to 20f)

        val order = blockOrderDuringDrag(
            visibleIds = listOf("a", "b", "c"),
            draggedId = "a",
            dragOffsetY = 90f,
        ) { heights.getValue(it) }

        assertEquals(listOf("b", "a", "c"), order)
    }

    @Test
    fun blockOrderDuringDragIgnoresUnknownDraggedId() {
        val order = blockOrderDuringDrag(
            visibleIds = listOf("a", "b"),
            draggedId = "not-in-list",
            dragOffsetY = 500f,
        ) { 20f }

        assertEquals(listOf("a", "b"), order)
    }

    @Test
    fun reorderBlockMovesBlockToArbitraryIndex() {
        val state = AppState()
        state.addTab()
        val tabId = state.tabs.single().id
        val firstId = state.addNoteBlock(tabId, "first", null)!!
        val secondId = state.addNoteBlock(tabId, "second", firstId)!!
        val thirdId = state.addNoteBlock(tabId, "third", secondId)!!

        state.reorderBlock(tabId, thirdId, 0)

        val order = state.tab(tabId)!!.annotations.blocks.map { it.id }
        assertEquals(listOf(thirdId, firstId, secondId), order)
    }

    @Test
    fun toggleSequenceFlipsEnabledFlag() {
        val state = AppState()
        state.addTab()
        state.addSequence("flow", false, SEQ_COLORS[0])
        val id = state.sequences.single().id
        assertTrue(state.sequences.single().enabled)

        state.toggleSequence(id)
        assertFalse(state.sequences.single().enabled)

        state.toggleSequence(id)
        assertTrue(state.sequences.single().enabled)
    }

    // ── Annotation block manipulation ─────────────────────────────────────────

    @Test
    fun moveBlockUpShiftsPositionInList() {
        val state = AppState()
        state.updateSettings { it.copy(autoExportNotes = false) }
        state.tabs = listOf(
            mkTab("t1", "test.log", emptyList()).copy(
                annotations = Annotations(blocks = listOf(AnnBlock.Note("a", "first"), AnnBlock.Note("b", "second"))),
            ),
        )

        state.moveBlock("t1", "b", -1)

        val blocks = state.tabs.single().annotations.blocks
        assertEquals("b", blocks[0].id)
        assertEquals("a", blocks[1].id)
    }

    @Test
    fun moveBlockDownShiftsPositionInList() {
        val state = AppState()
        state.updateSettings { it.copy(autoExportNotes = false) }
        state.tabs = listOf(
            mkTab("t1", "test.log", emptyList()).copy(
                annotations = Annotations(blocks = listOf(AnnBlock.Note("a", "first"), AnnBlock.Note("b", "second"))),
            ),
        )

        state.moveBlock("t1", "a", 1)

        val blocks = state.tabs.single().annotations.blocks
        assertEquals("b", blocks[0].id)
        assertEquals("a", blocks[1].id)
    }

    @Test
    fun removeBlockDeletesOnlyTarget() {
        val state = AppState()
        state.updateSettings { it.copy(autoExportNotes = false) }
        state.tabs = listOf(
            mkTab("t1", "test.log", emptyList()).copy(
                annotations = Annotations(blocks = listOf(AnnBlock.Note("a", "keep"), AnnBlock.Note("b", "remove"))),
            ),
        )

        state.removeBlock("t1", "b")

        val blocks = state.tabs.single().annotations.blocks
        assertEquals(1, blocks.size)
        assertEquals("a", blocks.single().id)
    }

    @Test
    fun updateBlockReplacesNoteContent() {
        val state = AppState()
        state.updateSettings { it.copy(autoExportNotes = false) }
        state.tabs = listOf(
            mkTab("t1", "test.log", emptyList()).copy(
                annotations = Annotations(blocks = listOf(AnnBlock.Note("n1", "old text"))),
            ),
        )

        state.updateBlock("t1", "n1", "new content")

        val block = assertIs<AnnBlock.Note>(state.tabs.single().annotations.blocks.single())
        assertEquals("new content", block.text)
    }

    @Test
    fun setPrefixSetsAnnotationPrefix() {
        val state = AppState()
        state.tabs = listOf(mkTab("t1", "test.log", emptyList()))

        state.setPrefix("t1", "## Analysis")

        assertEquals("## Analysis", state.tabs.single().annotations.prefix)
    }

    // ── Selection ─────────────────────────────────────────────────────────────

    @Test
    fun selRowRangeSelectsAllIdsInRange() {
        val state = AppState()
        val entries = (1..4).map { LogEntry(it, "10:00:00.00$it", LogLevel.I, "App", "msg $it") }
        state.tabs = listOf(mkTab("t1", "test.log", entries))

        state.selRowRange("t1", 1, 3)

        assertEquals(setOf(1, 2, 3), state.tabs.single().selected)
    }

    // PERF-5: selRowRange used to always re-filter the full logData; it now prefers the
    // ItemsSummary LogViewer already noted (same fallback rule selRow's range branch uses) and
    // must produce the same selection either way.
    @Test
    fun selRowRangeMatchesWithAndWithoutANotedSummary() {
        val entries = (1..6).map { LogEntry(it, "10:00:00.00$it", LogLevel.I, "App", "msg $it") }

        val stateWithoutSummary = AppState()
        stateWithoutSummary.tabs = listOf(mkTab("t1", "test.log", entries))
        stateWithoutSummary.selRowRange("t1", 2, 5)

        val stateWithSummary = AppState()
        stateWithSummary.tabs = listOf(mkTab("t1", "test.log", entries))
        val tab = stateWithSummary.tabs.single()
        stateWithSummary.noteVisibleItems("t1", summarizeItems(computeItems(tab, true)))
        stateWithSummary.selRowRange("t1", 2, 5)

        assertEquals(setOf(2, 3, 4, 5), stateWithoutSummary.tabs.single().selected)
        assertEquals(stateWithoutSummary.tabs.single().selected, stateWithSummary.tabs.single().selected)
    }

    // The case where the summary path genuinely diverges from a raw filter pass: a collapsed
    // manual block hides its interior rows, and a range spanning the fold must select only what
    // is actually visible (header included) rather than expanding through the collapsed block —
    // the same rule selRow's range branch documents for shift-click.
    @Test
    fun selRowRangeWithSummaryDoesNotExpandThroughCollapsedBlock() {
        val entries = (1..8).map { LogEntry(it, "10:00:00.00$it", LogLevel.I, "App", "msg $it") }
        val state = AppState()
        val block = ManualCollapseBlock("mc1", anchorId = 3, direction = ManualCollapseDirection.RANGE, endId = 6)
        state.tabs = listOf(mkTab("t1", "test.log", entries).copy(manualBlocks = listOf(block)))
        val tab = state.tabs.single()
        state.noteVisibleItems("t1", summarizeItems(computeItems(tab, true)))

        state.selRowRange("t1", 2, 7)

        // Ids 4..6 sit inside the collapsed block (3 is its always-visible header row).
        assertEquals(setOf(2, 3, 7), state.tabs.single().selected)
    }

    @Test
    fun selRowSingleClickReplacesPreviousSelection() {
        val state = AppState()
        val entries = (1..4).map { LogEntry(it, "10:00:00.00$it", LogLevel.I, "App", "msg $it") }
        state.tabs = listOf(mkTab("t1", "test.log", entries).copy(selected = setOf(1, 2, 3)))

        state.selRow("t1", 4, multi = false, range = false)

        assertEquals(setOf(4), state.tabs.single().selected)
    }

    @Test
    fun clearSelectionEmptiesSet() {
        val state = AppState()
        val entries = (1..3).map { LogEntry(it, "10:00:00.00$it", LogLevel.I, "App", "msg $it") }
        state.tabs = listOf(mkTab("t1", "test.log", entries))
        state.selRowRange("t1", 1, 3)

        state.clearSelection("t1")

        assertTrue(state.tabs.single().selected.isEmpty())
    }

    // ── extractMsgText (via hideMessagesLikeCtx) ──────────────────────────────
    // Message-only ctx actions (sequence/message-rule filters) route the selection through
    // extractMsgText since they match against entry.msg specifically. addHlFromCtx is
    // deliberately NOT one of these vehicles — see its own tests below — because highlighter
    // matching runs against the full rendered line, not just entry.msg.

    @Test
    fun extractMsgTextStripsTagColonPrefix() {
        // Branch: sel starts with "tag: " but does not end with full entry.msg
        val state = AppState()
        val entry = LogEntry(1, "10:00:00.000", LogLevel.I, "MyTag", "full message")
        state.tabs = listOf(mkTab("t1", "test.log", listOf(entry)))
        state.ctx = CtxMenuState("t1", 1, 0f, 0f, "MyTag: partial text")

        state.hideMessagesLikeCtx()

        assertEquals("partial text", state.tabs.single().filter.messageRules.single().pattern)
    }

    @Test
    fun extractMsgTextNoisyPrefixStrippedWhenSelEndsWithMsg() {
        // Branch: sel ends with entry.msg and is longer → return entry.msg
        val state = AppState()
        val entry = LogEntry(1, "10:00:00.000", LogLevel.I, "MyTag", "real message")
        state.tabs = listOf(mkTab("t1", "test.log", listOf(entry)))
        state.ctx = CtxMenuState("t1", 1, 0f, 0f, "10:00:00.000  I MyTag real message")

        state.hideMessagesLikeCtx()

        assertEquals("real message", state.tabs.single().filter.messageRules.single().pattern)
    }

    // ── addHlFromCtx ──────────────────────────────────────────────────────────

    @Test
    fun addHlFromCtxUsesRawSelectionAcrossTagAndMessageBoundary() {
        // A selection spanning part of the tag plus part of the message must be highlighted
        // verbatim — extractMsgText's "tag: " stripping is for message-only actions and would
        // otherwise silently drop the tag portion the user actually selected.
        val state = AppState()
        val entry = LogEntry(1, "10:00:00.000", LogLevel.I, "MyTag", "full message")
        state.tabs = listOf(mkTab("t1", "test.log", listOf(entry)))
        state.ctx = CtxMenuState("t1", 1, 0f, 0f, "yTag: full mess")

        state.addHlFromCtx()

        assertEquals("yTag: full mess", state.tabs.single().filter.highlighters.single().pattern)
    }

    @Test
    fun addHlFromCtxFallsBackToFullMessageWhenNoSelection() {
        val state = AppState()
        val entry = LogEntry(1, "10:00:00.000", LogLevel.I, "MyTag", "full message")
        state.tabs = listOf(mkTab("t1", "test.log", listOf(entry)))
        state.ctx = CtxMenuState("t1", 1, 0f, 0f, "")

        state.addHlFromCtx()

        assertEquals("full message", state.tabs.single().filter.highlighters.single().pattern)
    }

    @Test
    fun addHlFromCtxUsesNextUnusedColorFromTheDraftCursor() {
        val state = AppState()
        val entry = LogEntry(1, "10:00:00.000", LogLevel.I, "MyTag", "full message")
        state.tabs = listOf(
            mkTab("t1", "test.log", listOf(entry)).copy(
                filter = Filter(highlighters = listOf(
                    Highlighter("one", "first", false, HL_COLORS[0], true),
                    Highlighter("two", "second", false, HL_COLORS[1], true),
                )),
            ),
        )
        state.newHlColor = HL_COLORS[0]
        state.ctx = CtxMenuState("t1", 1, 0f, 0f, "selected")

        state.addHlFromCtx()

        assertEquals(HL_COLORS[2], state.tabs.single().filter.highlighters.last().color)
        assertEquals(HL_COLORS[3], state.newHlColor)
    }

    @Test
    fun addHlFromCtxWrapsToDraftColorWhenEveryPaletteColorIsInUse() {
        val state = AppState()
        val entry = LogEntry(1, "10:00:00.000", LogLevel.I, "MyTag", "full message")
        state.tabs = listOf(
            mkTab("t1", "test.log", listOf(entry)).copy(
                filter = Filter(highlighters = HL_COLORS.mapIndexed { index, color ->
                    Highlighter("h$index", "existing $index", false, color, true)
                }),
            ),
        )
        state.newHlColor = HL_COLORS.last()
        state.ctx = CtxMenuState("t1", 1, 0f, 0f, "selected")

        state.addHlFromCtx()

        assertEquals(HL_COLORS.last(), state.tabs.single().filter.highlighters.last().color)
    }

    @Test
    fun addHlFromCtxUsesExplicitPickerColor() {
        val state = AppState()
        val entry = LogEntry(1, "10:00:00.000", LogLevel.I, "MyTag", "full message")
        state.tabs = listOf(mkTab("t1", "test.log", listOf(entry)))
        state.ctx = CtxMenuState("t1", 1, 0f, 0f, "selected")

        state.addHlFromCtx(HL_COLORS[7])

        assertEquals(HL_COLORS[7], state.tabs.single().filter.highlighters.single().color)
    }

    @Test
    fun addHlTagFromCtxUsesExplicitPickerColor() {
        val state = AppState()
        val entry = LogEntry(1, "10:00:00.000", LogLevel.I, "MyTag", "full message")
        state.tabs = listOf(mkTab("t1", "test.log", listOf(entry)))
        state.ctx = CtxMenuState("t1", 1, 0f, 0f, "")

        state.addHlTagFromCtx(HL_COLORS[11])

        val highlighter = state.tabs.single().filter.highlighters.single()
        assertEquals("MyTag", highlighter.pattern)
        assertEquals(HL_COLORS[11], highlighter.color)
    }

    // ── requestAddAnn source label (displaySourceLabel) ──────────────────────

    @Test
    fun requestAddAnnUsesArchiveQualifiedLabelForZipSourcedTab() {
        val state = AppState()
        val sourceTab = mkTab("src", "logcat.log", listOf(LogEntry(1, "10:00:00.000", LogLevel.I, "App", "hello")))
            .copy(sourcePath = "/abs/path/archive.zip!inner/dir/logcat.log")
        val targetTab = mkTab("tgt", "other.log", emptyList())
        state.tabs = listOf(sourceTab, targetTab)
        state.compareMode = true
        state.activeTabId = "tgt"

        state.requestAddAnn("src", listOf(1))

        assertEquals("archive.zip/inner/dir/logcat.log", state.addAnnRequest?.sourceFilename)
    }

    @Test
    fun requestAddAnnUsesFolderQualifiedLabelWhenNamesCollide() {
        val state = AppState()
        val sourceTab = mkTab("src", "logcat.log", listOf(LogEntry(1, "10:00:00.000", LogLevel.I, "App", "hello")))
            .copy(sourcePath = "/tmp/folder_before/logcat.log")
        val targetTab = mkTab("tgt", "logcat.log", emptyList())
        state.tabs = listOf(sourceTab, targetTab)
        state.compareMode = true
        state.activeTabId = "tgt"

        state.requestAddAnn("src", listOf(1))

        assertEquals("folder_before/logcat.log", state.addAnnRequest?.sourceFilename)
    }

    @Test
    fun requestAddAnnUsesBareFilenameWhenNoCollisionOrArchive() {
        val state = AppState()
        val sourceTab = mkTab("src", "logcat.log", listOf(LogEntry(1, "10:00:00.000", LogLevel.I, "App", "hello")))
            .copy(sourcePath = "/tmp/folder_before/logcat.log")
        val targetTab = mkTab("tgt", "other.log", emptyList())
        state.tabs = listOf(sourceTab, targetTab)
        state.compareMode = true
        state.activeTabId = "tgt"

        state.requestAddAnn("src", listOf(1))

        assertEquals("logcat.log", state.addAnnRequest?.sourceFilename)
    }

    @Test
    fun requestAddAnnLeavesSourceFilenameNullWhenNotCrossFile() {
        val state = AppState()
        val tab = mkTab("t1", "logcat.log", listOf(LogEntry(1, "10:00:00.000", LogLevel.I, "App", "hello")))
        state.tabs = listOf(tab)

        state.requestAddAnn("t1", listOf(1))

        assertEquals(null, state.addAnnRequest?.sourceFilename)
    }

    // ── matchingHighlighterId / removeHlFromCtx ──────────────────────────────

    @Test
    fun matchingHighlighterIdFindsExactCaseInsensitivePatternMatch() {
        val state = AppState()
        val entry = LogEntry(1, "10:00:00.000", LogLevel.I, "MyTag", "full message")
        state.tabs = listOf(mkTab("t1", "test.log", listOf(entry)))
        state.addHl("t1", "full message", false, Color.Yellow)

        val id = state.matchingHighlighterId("t1", "FULL MESSAGE")

        assertEquals(state.tabs.single().filter.highlighters.single().id, id)
    }

    @Test
    fun matchingHighlighterIdReturnsNullForPartialOverlap() {
        val state = AppState()
        val entry = LogEntry(1, "10:00:00.000", LogLevel.I, "MyTag", "full message")
        state.tabs = listOf(mkTab("t1", "test.log", listOf(entry)))
        state.addHl("t1", "full message", false, Color.Yellow)

        // Selecting only part of the highlighted text is not "the fully highlighted part".
        assertEquals(null, state.matchingHighlighterId("t1", "full"))
    }

    @Test
    fun removeHlFromCtxRemovesTheMatchingHighlighter() {
        val state = AppState()
        val entry = LogEntry(1, "10:00:00.000", LogLevel.I, "MyTag", "full message")
        state.tabs = listOf(mkTab("t1", "test.log", listOf(entry)))
        state.addHl("t1", "full message", false, Color.Yellow)
        state.ctx = CtxMenuState("t1", 1, 0f, 0f, "full message")

        state.removeHlFromCtx()

        assertTrue(state.tabs.single().filter.highlighters.isEmpty())
    }

    // ── maskWordForCopy ───────────────────────────────────────────────────────

    @Test
    fun maskWordForCopyReplacesWholeWordCaseSensitively() {
        val settings = AppSettings(maskWordOnCopy = true, copyMaskRules = listOf(CopyMaskRule("java", "j*ava")))

        val result = maskWordForCopy("Crash seen in java, not Java or javascript.", settings)

        assertEquals("Crash seen in j*ava, not Java or javascript.", result)
    }

    @Test
    fun maskWordForCopyPreservesCodeJavaFenceMarkers() {
        val settings = AppSettings(maskWordOnCopy = true)
        val text = "See below:\n{code:java}\nthrow new RuntimeException(\"java issue\");\n{code}\n"

        val result = maskWordForCopy(text, settings)

        assertTrue(result.contains("{code:java}"))
        assertTrue(result.contains("{code}"))
        assertTrue(result.contains("j*ava issue"))
    }

    @Test
    fun maskWordForCopyNoOpWhenSettingDisabled() {
        val settings = AppSettings(maskWordOnCopy = false)

        val result = maskWordForCopy("plain java text", settings)

        assertEquals("plain java text", result)
    }

    @Test
    fun maskWordForCopyAppliesOrderedRulesAndAllowsAnEmptyReplacement() {
        val settings = AppSettings(
            maskWordOnCopy = true,
            copyMaskRules = listOf(
                CopyMaskRule("java", "kotlin"),
                CopyMaskRule("kotlin", ""),
                CopyMaskRule("", "ignored"),
            ),
        )

        val result = maskWordForCopy("java Kotlin javaScript", settings)

        assertEquals(" Kotlin javaScript", result)
    }

    @Test
    fun ensureActiveTabUnfilteredOnlyOpensTheActiveOriginalPanel() {
        val state = AppState()
        state.tabs = listOf(
            mkTab("active", "active.log", emptyList()),
            mkTab("other", "other.log", emptyList()),
        )
        state.activeTabId = "active"

        state.ensureActiveTabUnfiltered()
        state.ensureActiveTabUnfiltered()

        assertTrue(state.tab("active")!!.showUnfiltered)
        assertFalse(state.tab("other")!!.showUnfiltered)
    }

    @Test
    fun buildMdIndentedStyleUsesFourSpaceLinePrefix() {
        val entry = LogEntry(1, "10:00:00.000", LogLevel.I, "App", "hello")
        val tab = mkTab("t1", "test.log", listOf(entry)).copy(
            annotations = Annotations(
                blocks = listOf(AnnBlock.LogRef("r1", listOf(1), "Evidence")),
            ),
        )

        val md = buildMd(tab, AppSettings(annotationLogBlockStyle = AnnotationLogBlockStyle.INDENTED))

        assertTrue(md.contains("    10:00:00.000  I/App  hello"))
        assertFalse(md.contains("{code"))
    }

    @Test
    fun buildMdEmptyAnnotationsProducesEmptyString() {
        val tab = mkTab("t1", "test.log", emptyList()).copy(annotations = Annotations())

        val md = buildMd(tab, AppSettings())

        assertEquals("", md)
    }

    @Test
    fun buildMdSpecialCharsInCaptionAreNotHtmlEscaped() {
        val tab = mkTab("t1", "test.log", emptyList()).copy(
            annotations = Annotations(
                blocks = listOf(AnnBlock.LogRef("r1", emptyList(), "Fix <a> & <b>")),
            ),
        )

        val md = buildMd(tab, AppSettings(annotationLogBlockStyle = AnnotationLogBlockStyle.INDENTED))

        assertTrue(md.contains("Fix <a> & <b>"))
    }

    // ── Autosave corruption / fallback ────────────────────────────────────────

    @Test
    fun autosaveWithWrongVersionHeaderIsIgnored() {
        val dir = createTempDirectory("openlog-badver").toFile()
        val cacheFile = File(dir, "state.cache").apply { writeText("openLog2-cache-v99\nsome data\n") }

        val state = AppState(cacheFile, restoreOnCreate = true)

        assertTrue(state.tabs.isEmpty())
    }

    @Test
    fun autosaveWithEmptyFileIsIgnored() {
        val dir = createTempDirectory("openlog-empty2").toFile()
        val cacheFile = File(dir, "state.cache").apply { writeText("") }

        val state = AppState(cacheFile, restoreOnCreate = true)

        assertTrue(state.tabs.isEmpty())
    }

    @Test
    fun autosaveWithTruncatedTabLineProducesNoTab() {
        val dir = createTempDirectory("openlog-trunc").toFile()
        val cacheFile = File(dir, "state.cache").apply {
            writeText("openLog2-cache-v1\ntabs\ntab\tmalformed-not-enough-fields\n")
        }

        val state = AppState(cacheFile, restoreOnCreate = true)

        assertTrue(state.tabs.isEmpty())
    }

    @Test
    fun fileLoadErrorShowsErrorWithoutOpeningTab() {
        val dir = createTempDirectory("openlog-err").toFile()
        val logFile = File(dir, "test.log").apply { writeText("content") }
        val state = AppState(
            autosaveFile = File(dir, "state.cache"),
            parser = { throw RuntimeException("disk error") },
        )

        state.openFile(logFile)
        waitUntil { !state.isLoading }

        assertTrue(state.tabs.isEmpty())
        assertEquals("Could not open file", state.openError?.title)
        assertTrue(state.openError?.message.orEmpty().contains("disk error"))
    }

    @Test
    fun closingTabDuringOpenCancelsPendingLoad() {
        val dir = createTempDirectory("openlog-cancel-load").toFile()
        val logFile = File(dir, "slow.log").apply { writeText("06-26 10:00:00.000  1  1 I App: hello\n") }
        val parserStarted = CountDownLatch(1)
        val releaseParser = CountDownLatch(1)
        val state = AppState(
            autosaveFile = File(dir, "state.cache"),
            parser = {
                parserStarted.countDown()
                releaseParser.await(2, TimeUnit.SECONDS)
                listOf(LogEntry(1, "10:00:00.000", LogLevel.I, "App", "hello"))
            },
        )

        val pendingTabId = state.openFile(logFile)!!
        assertTrue(parserStarted.await(2, TimeUnit.SECONDS))
        state.closeTab(pendingTabId)
        assertFalse(state.isLoading)
        releaseParser.countDown()

        assertTrue(state.tabs.isEmpty())
    }

    @Test
    fun closeAllTabsCancelsOrphanedPendingLoad() {
        val dir = createTempDirectory("openlog-cancel-orphan-load").toFile()
        val logFile = File(dir, "slow.log").apply { writeText("06-26 10:00:00.000  1  1 I App: hello\n") }
        val parserStarted = CountDownLatch(1)
        val releaseParser = CountDownLatch(1)
        val state = AppState(
            autosaveFile = File(dir, "state.cache"),
            parser = {
                parserStarted.countDown()
                releaseParser.await(2, TimeUnit.SECONDS)
                listOf(LogEntry(1, "10:00:00.000", LogLevel.I, "App", "hello"))
            },
        )

        state.openFile(logFile)
        assertTrue(parserStarted.await(2, TimeUnit.SECONDS))
        state.tabs = emptyList()
        state.closeAllTabs()

        assertFalse(state.isLoading)
        releaseParser.countDown()
        assertTrue(state.tabs.isEmpty())
    }

    @Test
    fun closeAllTabsClearsOrphanedLoadingState() {
        val state = AppState()
        state.isLoading = true
        state.loadingStatus = "Loading file..."

        state.closeAllTabs()

        assertFalse(state.isLoading)
        assertEquals(null, state.loadingStatus)
    }

    @Test
    fun mkTabCachesAnalysisNeededByLargeFileUi() {
        val tab = mkTab(
            "t1",
            "large.log",
            listOf(
                LogEntry(1, "10:00:00.000", LogLevel.W, "Binder", "Caught a RuntimeException from the binder stub implementation.", pid = 7),
                LogEntry(2, "10:00:00.001", LogLevel.W, "Binder", "java.lang.ArrayIndexOutOfBoundsException:", pid = 7),
                LogEntry(3, "10:00:00.002", LogLevel.W, "Binder", "    at android.os.Binder.execTransact(Binder.java:1)", pid = 7),
                LogEntry(4, "10:00:00.003", LogLevel.I, "ActivityManager", "ANR in com.example", pid = 8),
            ),
        )

        assertEquals(mapOf("Binder" to 3, "ActivityManager" to 1), tab.analysis.tagCounts)
        assertEquals(listOf(1), tab.analysis.stackTraceGroups.map { it.rid })
        assertEquals(listOf(1, 4), tab.analysis.crashSites.map { it.entry.id })
    }

    @Test
    fun openFilePublishesTabBeforeAnalysisThenFillsItIn() {
        val dir = createTempDirectory("openlog-deferred").toFile()
        val logFile = File(dir, "crash.log").apply {
            writeText(
                """
                06-26 10:00:00.000  7  7 I App: hello
                06-26 10:00:00.001  7  7 E AndroidRuntime: FATAL EXCEPTION: main
                06-26 10:00:00.002  7  7 E AndroidRuntime: java.lang.IllegalStateException: boom
                06-26 10:00:00.003  7  7 E AndroidRuntime:     at com.example.Foo.bar(Foo.kt:1)
                """.trimIndent() + "\n",
            )
        }
        val state = AppState(File(dir, "state.cache"))

        state.openFile(logFile)
        // isLoading clears as soon as the tab is published (parse done); tag counts must already
        // be there for the filter panel even if the stack/crash analysis is still pending.
        waitUntil { !state.isLoading && state.tabs.isNotEmpty() }
        assertEquals(4, state.tabs.single().logData.size)
        assertTrue(state.tabs.single().analysis.tagCounts.isNotEmpty())

        // The deferred analysis lands afterwards (same background job).
        waitUntil { !state.tabs.single().analysis.pending }
        assertEquals(listOf(2), state.tabs.single().analysis.stackTraceGroups.map { it.rid })
        assertTrue(state.tabs.single().analysis.crashSites.isNotEmpty())
    }

    // ── Analysis completion is explicit, not inferred from emptiness (P-02) ─────

    @Test
    fun bareLogAnalysisDefaultsToPendingNotToSilentlyCompleteAndEmpty() {
        // Root cause of P-02: LogAnalysis's default used to be pending=false, so any LogTab built
        // without an explicit analysis (a bug, a new construction site, a test fixture) silently
        // claimed "analyzed, nothing found" instead of "never analyzed." Flipping the default is
        // the actual fix; every real completion path (buildLogAnalysis, via mkTab) must still land
        // on pending=false explicitly.
        assertTrue(LogAnalysis().pending, "a bare LogAnalysis() must read as not-yet-analyzed")
        assertFalse(mkTab("t1", "a.log", emptyList()).analysis.pending, "mkTab computes real analysis and must mark it complete")
    }

    @Test
    fun emptyWorkspaceTabIsExplicitlyCompleteNotPending() {
        // logData is always empty here, so a real analysis would also be empty — this is
        // vacuously complete, not "not yet analyzed," and must not show an "Analyzing…" hint
        // that would never resolve (there is nothing that will ever complete it).
        assertFalse(emptyWorkspaceTab().analysis.pending)
    }

    @Test
    fun crashFreeCompletedAnalysisIsTrustedWithoutRecomputing() {
        // A tab whose real analysis genuinely found zero crash sites/stack traces must render as
        // "done, nothing found" — computeItems must not fall back to recomputing from raw logData
        // just because the cached result happens to be empty (the exact P-02 bug).
        val tab = mkTab("t1", "clean.log", listOf(LogEntry(1, "10:00:00.000", LogLevel.I, "App", "all quiet")))
        assertFalse(tab.analysis.pending)
        assertTrue(tab.analysis.crashSites.isEmpty())
        assertTrue(tab.analysis.stackTraceGroups.isEmpty())

        val items = computeItems(tab, applyFilter = true)
        assertTrue(items.none { it is LogItem.StackTraceHeader }, "no stack trace groups exist; none should be rendered")
    }

    @Test
    fun activeTabIdFallsBackToFirstTabAfterRestore() {
        val dir = createTempDirectory("openlog-active2").toFile()
        val logFile = File(dir, "test.log").apply { writeText("06-26 10:00:00.000  1  1 I App: hello\n") }
        val cacheFile = File(dir, "state.cache")
        val state = AppState(cacheFile)
        state.tabs = listOf(mkTab("t1", "test.log", emptyList()).copy(sourcePath = logFile.absolutePath))
        state.activeTabId = "ghost-tab-id"
        state.autosaveNow()

        val restored = AppState(cacheFile, restoreOnCreate = true)

        assertEquals("t1", restored.activeTabId)
    }

    @Test
    fun compareTabIdFallsBackToFirstTabAfterRestore() {
        val dir = createTempDirectory("openlog-compare2").toFile()
        val logFile = File(dir, "test.log").apply { writeText("06-26 10:00:00.000  1  1 I App: hello\n") }
        val cacheFile = File(dir, "state.cache")
        val state = AppState(cacheFile)
        state.tabs = listOf(mkTab("t1", "test.log", emptyList()).copy(sourcePath = logFile.absolutePath))
        state.compareTabId = "ghost-compare-id"
        state.autosaveNow()

        val restored = AppState(cacheFile, restoreOnCreate = true)

        assertEquals("t1", restored.compareTabId)
    }

    @Test
    fun tabCounterStartsAboveHighestRestoredId() {
        val dir = createTempDirectory("openlog-counter").toFile()
        val logFile = File(dir, "test.log").apply { writeText("06-26 10:00:00.000  1  1 I App: hello\n") }
        val cacheFile = File(dir, "state.cache")
        val state = AppState(cacheFile)
        state.tabs = listOf(
            mkTab("t3", "a.log", emptyList()).copy(sourcePath = logFile.absolutePath),
            mkTab("t7", "b.log", emptyList()).copy(sourcePath = logFile.absolutePath),
        )
        state.autosaveNow()

        val restored = AppState(cacheFile, restoreOnCreate = true)
        restored.addTab()

        assertTrue(restored.tabs.any { it.id == "t8" })
    }

    @Test
    fun selectAllSelectsAllVisibleRows() {
        val entries = (1..5).map { i -> LogEntry(i, "10:00:0$i.000", LogLevel.I, "Tag", "msg$i") }
        val state = AppState()
        state.tabs = listOf(mkTab("t1", "test.log", entries))
        state.activeTabId = "t1"
        state.selectAll("t1")
        assertEquals(5, state.activeTab()!!.selected.size)
    }

    @Test
    fun selectAllRespectsActiveFilter() {
        val entries = listOf(
            LogEntry(1, "10:00:01.000", LogLevel.D, "Tag", "msg1"),
            LogEntry(2, "10:00:02.000", LogLevel.E, "Tag", "msg2"),
            LogEntry(3, "10:00:03.000", LogLevel.D, "Tag", "msg3"),
        )
        val state = AppState()
        val tab = mkTab("t1", "test.log", entries)
        state.tabs = listOf(tab.copy(filter = tab.filter.copy(levels = setOf(LogLevel.E))))
        state.activeTabId = "t1"
        state.selectAll("t1")
        assertEquals(setOf(2), state.activeTab()!!.selected)
    }

    @Test
    fun mcpControlEnableReturnsImmediatelyWithoutBlockingCaller() {
        // The actual bind (ControlServer.start()) now runs on ioScope specifically so a slow or
        // hung bind (macOS's first-time incoming-connection firewall prompt, VPN/security
        // software intercepting the socket, etc.) can't freeze the Compose UI thread — a past
        // version ran it synchronously on whichever thread called this, which is exactly what
        // that thread is here (the test's own thread, standing in for the UI thread). This can't
        // prove non-blocking for every possible slow bind, but it does pin the regression a
        // revert-to-synchronous would reintroduce: the call must return long before any bind on
        // a real port could plausibly complete network-stack setup.
        val state = AppState(controlTokenFile = File(createTempDirectory("openlog-mcp-token").toFile(), "control-token"))
        val elapsedMs = kotlin.system.measureTimeMillis {
            state.setMcpControlEnabled(true, 0)
        }
        assertTrue(elapsedMs < 500, "setMcpControlEnabled took ${elapsedMs}ms — looks synchronous again")
        waitUntil { state.settings.mcpControlEnabled }
        state.setMcpControlEnabled(false, state.settings.mcpControlPort)
    }

    @Test
    fun mcpControlEnableSurvivesPortAlreadyInUse() {
        // Occupy a real port first (0 → OS picks a free one) so the app's own bind attempt on
        // that same port genuinely fails, the same way it would if another instance — or a
        // stray leftover process — already held the configured port.
        val blocker = ControlServer(AppState(), 0)
        blocker.start()
        try {
            val state = AppState(controlTokenFile = File(createTempDirectory("openlog-mcp-token").toFile(), "control-token"))
            state.settings = state.settings.copy(mcpControlPort = blocker.boundPort)

            // Must not throw: a past version let ControlServer.start()'s BindException escape
            // uncaught, crashing the whole app on this exact toggle.
            state.setMcpControlEnabled(true, blocker.boundPort)

            waitUntil { state.mcpControlError != null }
            assertTrue(state.mcpControlError!!.contains(blocker.boundPort.toString()))
            // The failed attempt must not leave the toggle persisted as "on" — otherwise Main.kt
            // retries the identical failing bind on every future launch before the window ever
            // appears, with no way back into Settings to turn it off.
            assertFalse(state.settings.mcpControlEnabled)
        } finally {
            blocker.stop()
        }
    }

    @Test
    fun mcpControlEnableSucceedsOnFreePortAfterEarlierFailure() {
        val blocker = ControlServer(AppState(), 0)
        blocker.start()
        val state = AppState(controlTokenFile = File(createTempDirectory("openlog-mcp-token").toFile(), "control-token"))
        state.setMcpControlEnabled(true, blocker.boundPort)
        waitUntil { state.mcpControlError != null }
        blocker.stop()

        // A free port right after must succeed and clear the earlier error — the failure isn't
        // "sticky" once the underlying conflict is gone. java.net.ServerSocket(0)'s bind-then-
        // release-immediately is the same "let the OS hand back a free port" idiom ControlServer
        // itself uses for tests (port 0); the tiny reuse race is standard for this pattern.
        val freePort = java.net.ServerSocket(0).use { it.localPort }
        state.setMcpControlEnabled(true, freePort)

        waitUntil { state.settings.mcpControlEnabled }
        assertEquals(null, state.mcpControlError)
        state.setMcpControlEnabled(false, state.settings.mcpControlPort)
    }

    @Test
    fun controlServerTokenSurvivesARelaunchSoTheMcpClientConfigDoesNotNeedRecopying() {
        // Users otherwise have to re-copy the MCP client config after every app launch, since a
        // fresh random token used to be generated per ControlServer instance with nothing persisted.
        // loadOrCreateControlToken() now reuses whatever's already on disk at controlTokenFile.
        // setMcpControlEnabled clamps its port argument to at least MIN_PORT (1), so — unlike
        // ControlServer's own constructor — passing 0 here would try to bind the privileged port 1
        // instead of asking the OS for a free one; grab a real free port explicitly instead, same
        // as mcpControlEnableSucceedsOnFreePortAfterEarlierFailure above.
        val tokenFile = File(createTempDirectory("openlog-mcp-token-persist").toFile(), "control-token")

        val first = AppState(controlTokenFile = tokenFile)
        first.setMcpControlEnabled(true, java.net.ServerSocket(0).use { it.localPort })
        waitUntil { first.controlServerToken() != null }
        val firstToken = first.controlServerToken()
        first.setMcpControlEnabled(false, first.settings.mcpControlPort)

        // A second AppState with the same token file stands in for the next app launch.
        val second = AppState(controlTokenFile = tokenFile)
        second.setMcpControlEnabled(true, java.net.ServerSocket(0).use { it.localPort })
        waitUntil { second.controlServerToken() != null }

        assertEquals(firstToken, second.controlServerToken(), "the persisted token must survive a relaunch")
        second.setMcpControlEnabled(false, second.settings.mcpControlPort)
    }

    @Test
    fun connectionInfoTokenIsAvailableWhileTheControlServerIsOff() {
        val tokenFile = File(createTempDirectory("openlog-mcp-token-info").toFile(), "control-token")
        val state = AppState(controlTokenFile = tokenFile)

        assertFalse(state.settings.mcpControlEnabled)
        assertEquals(null, state.controlServerToken())
        val token = state.connectionInfoToken()

        assertEquals(32, token.length)
        assertEquals(token, tokenFile.readText().trim())
        assertEquals(token, state.connectionInfoToken())
    }

    @Test
    fun rotateControlTokenChangesTheLiveTokenWhileServerIsRunning() {
        val tokenFile = File(createTempDirectory("openlog-mcp-token-rotate").toFile(), "control-token")
        val state = AppState(controlTokenFile = tokenFile)
        state.setMcpControlEnabled(true, java.net.ServerSocket(0).use { it.localPort })
        waitUntil { state.controlServerToken() != null }
        val original = state.controlServerToken()

        state.rotateControlToken()

        // rotateControlToken forces a disable+re-enable of the live server, so the token briefly
        // goes through null before the restarted server publishes the new one.
        waitUntil { state.controlServerToken() != null && state.controlServerToken() != original }
        val rotated = state.controlServerToken()
        assertNotEquals(original, rotated)
        assertEquals(rotated, tokenFile.readText().trim(), "the rotated token must also be the one persisted to disk")

        state.setMcpControlEnabled(false, state.settings.mcpControlPort)
    }

    @Test
    fun rotateControlTokenWithNoServerRunningJustPersistsANewTokenWithoutCrashing() {
        val tokenFile = File(createTempDirectory("openlog-mcp-token-rotate-idle").toFile(), "control-token")
        val state = AppState(controlTokenFile = tokenFile)

        state.rotateControlToken()

        assertTrue(tokenFile.isFile)
        assertEquals(null, state.controlServerToken(), "no server was running, so none should have been started")
    }

    // ── Control-server lifecycle race (S-02) ────────────────────────────
    // applyControlServerState's async enable path used to publish `controlServer = it` from an
    // unawaited coroutine with no generation/ordering guard: a disable that landed while a slow
    // enable was still binding saw an already-null field (its own stop() was a no-op), then the
    // stale bind's onSuccess overwrote it later — a server the caller believed was off. These use
    // the controlServerFactory test seam to make the bind artificially slow and observable, without
    // faking ControlServer itself (start()/stop() are the real Ktor calls throughout).

    @Test
    fun disableBeforeSlowStartCompletesLeavesNoServerPublished() {
        val state = AppState(controlServerFactory = { s, p -> delay(300); ControlServer(s, p).also { it.start() } })
        state.setMcpControlEnabled(true, 0)
        // The 300ms bind above is still in flight here — disable must invalidate it, not just no-op
        // against a still-null controlServer field.
        state.setMcpControlEnabled(false, state.settings.mcpControlPort)
        // Give the delayed start's completion handler a chance to run; if the race regressed, this
        // is exactly the window where a stale server would get published.
        Thread.sleep(600)
        assertEquals(null, state.controlServerToken(), "a disabled-before-bind-completed start must never publish")
    }

    @Test
    fun rapidReenableOnlyPublishesTheLatestStart() {
        val state = AppState(
            controlServerFactory = { s, p -> delay(200); ControlServer(s, p).also { it.start() } },
        )
        state.setMcpControlEnabled(true, 0) // first start, still binding
        val secondPort = java.net.ServerSocket(0).use { it.localPort }
        state.setMcpControlEnabled(true, secondPort) // supersedes the first before it finishes

        waitUntil(timeoutMs = 2_000) { state.controlServerToken() != null }
        // Only the second (latest) generation's server may ever become visible; the first must have
        // been stopped by its own stale completion handler rather than left listening unreferenced.
        assertTrue(state.settings.mcpControlPort == secondPort || state.mcpControlError != null)
        state.setMcpControlEnabled(false, state.settings.mcpControlPort)
    }

    @Test
    fun disableDuringSlowStartIsIdempotentAndSafe() {
        val state = AppState(controlServerFactory = { s, p -> delay(200); ControlServer(s, p).also { it.start() } })
        state.setMcpControlEnabled(true, 0)
        // Repeated disable while a start is in flight must not throw and must not crash-loop.
        state.setMcpControlEnabled(false, state.settings.mcpControlPort)
        state.setMcpControlEnabled(false, state.settings.mcpControlPort)
        Thread.sleep(400)
        assertEquals(null, state.controlServerToken())
    }

    @Test
    fun shutdownDuringStartLeavesNoServerListening() {
        // Main.kt's onDispose calls stopControlServerForShutdown() unconditionally on window close
        // — it must invalidate a still-binding start exactly like an explicit disable does.
        val state = AppState(controlServerFactory = { s, p -> delay(300); ControlServer(s, p).also { it.start() } })
        state.startControlServerForThisSessionOnly(0)
        state.stopControlServerForShutdown()
        Thread.sleep(600)
        assertEquals(null, state.controlServerToken(), "shutdown must invalidate an in-flight start")
    }

    @Test
    fun mcpControlServerCorsToggleSupersedesAnInFlightStartThatCapturedTheOldSetting() {
        val dir = createTempDirectory("openlog-mcp-cors-race").toFile()
        val tokenFile = File(dir, "control-token")
        val firstCaptured = CountDownLatch(1)
        val releaseFirst = CountDownLatch(1)
        val firstCompleted = CountDownLatch(1)
        val captures = CopyOnWriteArrayList<Boolean>()
        val calls = AtomicInteger()
        val port = java.net.ServerSocket(0).use { it.localPort }
        val state = AppState(
            autosaveFile = File(dir, "state.cache"),
            controlTokenFile = tokenFile,
            controlServerFactory = { s, p ->
                val call = calls.getAndIncrement()
                val allowBrowserClients = s.settings.mcpAllowBrowserClients
                captures += allowBrowserClients
                if (call == 0) {
                    firstCaptured.countDown()
                    check(releaseFirst.await(2, TimeUnit.SECONDS))
                }
                try {
                    ControlServer(
                        s,
                        p,
                        token = s.connectionInfoToken(),
                        allowBrowserClients = allowBrowserClients,
                    ).also { it.start() }
                } finally {
                    if (call == 0) firstCompleted.countDown()
                }
            },
        )

        try {
            state.setMcpControlEnabled(true, port)
            assertTrue(firstCaptured.await(2, TimeUnit.SECONDS))
            assertEquals(listOf(false), captures.toList())

            state.setMcpAllowBrowserClients(true)

            waitUntil { state.controlServerToken() != null }
            assertEquals(listOf(false, true), captures.toList())
            assertTrue(state.settings.mcpAllowBrowserClients)

            releaseFirst.countDown()
            assertTrue(firstCompleted.await(2, TimeUnit.SECONDS))
            assertTrue(state.controlServerToken() != null, "the stale start must not clear the replacement")
        } finally {
            releaseFirst.countDown()
            state.stopControlServerForShutdown()
        }
    }

    @Test
    fun mcpControlServerDisableDuringAnInFlightReplacementWinsOverBothStarts() {
        val dir = createTempDirectory("openlog-mcp-disable-replacement").toFile()
        val captured = List(2) { CountDownLatch(1) }
        val release = List(2) { CountDownLatch(1) }
        val completed = CountDownLatch(2)
        val calls = AtomicInteger()
        val port = java.net.ServerSocket(0).use { it.localPort }
        val state = AppState(
            autosaveFile = File(dir, "state.cache"),
            controlTokenFile = File(dir, "control-token"),
            controlServerFactory = { s, p ->
                val call = calls.getAndIncrement()
                captured[call].countDown()
                check(release[call].await(2, TimeUnit.SECONDS))
                try {
                    ControlServer(
                        s,
                        p,
                        token = "replacement-$call",
                        allowBrowserClients = s.settings.mcpAllowBrowserClients,
                    ).also { it.start() }
                } finally {
                    completed.countDown()
                }
            },
        )

        try {
            state.setMcpControlEnabled(true, port)
            assertTrue(captured[0].await(2, TimeUnit.SECONDS))
            state.setMcpAllowBrowserClients(true)
            assertTrue(captured[1].await(2, TimeUnit.SECONDS))

            state.setMcpControlEnabled(false, port)
            release.forEach { it.countDown() }

            assertTrue(completed.await(2, TimeUnit.SECONDS))
            assertEquals(null, state.controlServerToken())
            assertFalse(state.settings.mcpControlEnabled)
        } finally {
            release.forEach { it.countDown() }
            state.stopControlServerForShutdown()
        }
    }

    @Test
    fun mcpControlServerSessionOnlyRestartRetainsItsRuntimePortWithoutPersistingEnablement() {
        val dir = createTempDirectory("openlog-mcp-session-restart").toFile()
        val firstCaptured = CountDownLatch(1)
        val releaseFirst = CountDownLatch(1)
        val firstCompleted = CountDownLatch(1)
        val capturedPorts = CopyOnWriteArrayList<Int>()
        val calls = AtomicInteger()
        val runtimePort = java.net.ServerSocket(0).use { it.localPort }
        val state = AppState(
            autosaveFile = File(dir, "state.cache"),
            controlTokenFile = File(dir, "control-token"),
            controlServerFactory = { s, p ->
                val call = calls.getAndIncrement()
                capturedPorts += p
                if (call == 0) {
                    firstCaptured.countDown()
                    check(releaseFirst.await(2, TimeUnit.SECONDS))
                }
                try {
                    ControlServer(
                        s,
                        p,
                        token = s.connectionInfoToken(),
                        allowBrowserClients = s.settings.mcpAllowBrowserClients,
                    ).also { it.start() }
                } finally {
                    if (call == 0) firstCompleted.countDown()
                }
            },
        )
        val persistedPort = state.settings.mcpControlPort

        try {
            state.startControlServerForThisSessionOnly(runtimePort)
            assertTrue(firstCaptured.await(2, TimeUnit.SECONDS))
            assertFalse(state.settings.mcpControlEnabled)
            assertEquals(persistedPort, state.settings.mcpControlPort)

            state.setMcpAllowBrowserClients(true)

            waitUntil { state.controlServerToken() != null }
            assertEquals(listOf(runtimePort, runtimePort), capturedPorts.toList())
            assertFalse(state.settings.mcpControlEnabled)
            assertEquals(persistedPort, state.settings.mcpControlPort)

            releaseFirst.countDown()
            assertTrue(firstCompleted.await(2, TimeUnit.SECONDS))
        } finally {
            releaseFirst.countDown()
            state.stopControlServerForShutdown()
        }
    }

    @Test
    fun mcpControlServerTokenRotationSupersedesAnInFlightStartThatCapturedTheOldToken() {
        val dir = createTempDirectory("openlog-mcp-token-race").toFile()
        val tokenFile = File(dir, "control-token")
        val firstCaptured = CountDownLatch(1)
        val releaseFirst = CountDownLatch(1)
        val firstCompleted = CountDownLatch(1)
        val capturedTokens = CopyOnWriteArrayList<String>()
        val calls = AtomicInteger()
        val port = java.net.ServerSocket(0).use { it.localPort }
        val state = AppState(
            autosaveFile = File(dir, "state.cache"),
            controlTokenFile = tokenFile,
            controlServerFactory = { s, p ->
                val call = calls.getAndIncrement()
                val token = s.connectionInfoToken()
                capturedTokens += token
                if (call == 0) {
                    firstCaptured.countDown()
                    check(releaseFirst.await(2, TimeUnit.SECONDS))
                }
                try {
                    ControlServer(s, p, token = token).also { it.start() }
                } finally {
                    if (call == 0) firstCompleted.countDown()
                }
            },
        )

        try {
            state.setMcpControlEnabled(true, port)
            assertTrue(firstCaptured.await(2, TimeUnit.SECONDS))
            val oldToken = capturedTokens.single()

            state.rotateControlToken()

            waitUntil { capturedTokens.size == 2 && state.controlServerToken() == capturedTokens[1] }
            val newToken = capturedTokens[1]
            assertNotEquals(oldToken, newToken)
            assertEquals(401, controlServerRequestStatus(port, oldToken))
            assertEquals(200, controlServerRequestStatus(port, newToken))

            releaseFirst.countDown()
            assertTrue(firstCompleted.await(2, TimeUnit.SECONDS))
            assertEquals(newToken, state.controlServerToken())
        } finally {
            releaseFirst.countDown()
            state.stopControlServerForShutdown()
        }
    }

    @Test
    fun mcpControlServerRapidCorsTogglesPublishOnlyTheLatestGeneration() {
        val dir = createTempDirectory("openlog-mcp-rapid-cors").toFile()
        val captured = List(2) { CountDownLatch(1) }
        val release = List(2) { CountDownLatch(1) }
        val completed = CountDownLatch(2)
        val capturedCors = CopyOnWriteArrayList<Boolean>()
        val calls = AtomicInteger()
        val port = java.net.ServerSocket(0).use { it.localPort }
        val state = AppState(
            autosaveFile = File(dir, "state.cache"),
            controlTokenFile = File(dir, "control-token"),
            controlServerFactory = { s, p ->
                val call = calls.getAndIncrement()
                val allowBrowserClients = s.settings.mcpAllowBrowserClients
                capturedCors += allowBrowserClients
                if (call < 2) {
                    captured[call].countDown()
                    check(release[call].await(2, TimeUnit.SECONDS))
                }
                try {
                    ControlServer(
                        s,
                        p,
                        token = "generation-$call",
                        allowBrowserClients = allowBrowserClients,
                    ).also { it.start() }
                } finally {
                    if (call < 2) completed.countDown()
                }
            },
        )

        try {
            state.setMcpControlEnabled(true, port)
            assertTrue(captured[0].await(2, TimeUnit.SECONDS))
            state.setMcpAllowBrowserClients(true)
            assertTrue(captured[1].await(2, TimeUnit.SECONDS))
            state.setMcpAllowBrowserClients(false)

            waitUntil { state.controlServerToken() == "generation-2" }
            assertEquals(listOf(false, true, false), capturedCors.toList())

            release.forEach { it.countDown() }
            assertTrue(completed.await(2, TimeUnit.SECONDS))
            assertEquals("generation-2", state.controlServerToken())
        } finally {
            release.forEach { it.countDown() }
            state.stopControlServerForShutdown()
        }
    }

    @Test
    fun mcpControlServerCurrentSessionFailureClearsStartBookkeepingWithoutChangingPersistedEnablement() {
        val dir = createTempDirectory("openlog-mcp-session-failure").toFile()
        val firstCaptured = CountDownLatch(1)
        val releaseFailure = CountDownLatch(1)
        val calls = AtomicInteger()
        val port = java.net.ServerSocket(0).use { it.localPort }
        val state = AppState(
            autosaveFile = File(dir, "state.cache"),
            controlTokenFile = File(dir, "control-token"),
            controlServerFactory = { s, p ->
                if (calls.getAndIncrement() == 0) {
                    firstCaptured.countDown()
                    check(releaseFailure.await(2, TimeUnit.SECONDS))
                    error("controlled start failure")
                }
                ControlServer(s, p, token = "recovered").also { it.start() }
            },
        )
        state.settings = state.settings.copy(mcpControlEnabled = true)
        val persistedPort = state.settings.mcpControlPort

        try {
            state.startControlServerForThisSessionOnly(port)
            assertTrue(firstCaptured.await(2, TimeUnit.SECONDS))
            releaseFailure.countDown()
            waitUntil { state.mcpControlError?.contains("controlled start failure") == true }

            assertTrue(state.settings.mcpControlEnabled)
            assertEquals(persistedPort, state.settings.mcpControlPort)

            state.startControlServerForThisSessionOnly(port)
            waitUntil { state.controlServerToken() == "recovered" }
            assertEquals(null, state.mcpControlError)
            assertTrue(state.settings.mcpControlEnabled)
        } finally {
            releaseFailure.countDown()
            state.stopControlServerForShutdown()
        }
    }

    private fun controlServerRequestStatus(port: Int, token: String): Int {
        val request = HttpRequest.newBuilder(URI.create("http://127.0.0.1:$port/tabs"))
            .header("Authorization", "Bearer $token")
            .GET()
            .build()
        return HttpClient.newHttpClient().send(request, HttpResponse.BodyHandlers.ofString()).statusCode()
    }

    private fun String.withMalformedAnnotationField(index: Int): String =
        split("|").toMutableList().also { fields -> fields[index] = "%%%" }.joinToString("|")

    private fun waitUntil(timeoutMs: Long = 2_000, condition: () -> Boolean) {
        val deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(timeoutMs)
        while (System.nanoTime() < deadline) {
            if (condition()) return
            Thread.sleep(10)
        }
        assertTrue(condition())
    }

    private fun buildZipFixture(dir: File, name: String, entries: Map<String, String>): File {
        val file = File(dir, name)
        java.util.zip.ZipOutputStream(file.outputStream()).use { zos ->
            entries.forEach { (path, content) ->
                zos.putNextEntry(java.util.zip.ZipEntry(path))
                zos.write(content.toByteArray())
                zos.closeEntry()
            }
        }
        return file
    }
}
