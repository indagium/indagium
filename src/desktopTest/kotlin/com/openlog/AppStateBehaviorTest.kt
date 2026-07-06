package com.openlog

import androidx.compose.ui.graphics.Color
import com.openlog.debug.ControlServer
import com.openlog.model.AnnBlock
import com.openlog.model.AnnotationLogBlockStyle
import com.openlog.model.Annotations
import com.openlog.model.AppSettings
import com.openlog.model.CtxMenuState
import com.openlog.model.Filter
import com.openlog.model.FilterMode
import com.openlog.model.LogEntry
import com.openlog.model.LogItem
import com.openlog.model.LogLevel
import com.openlog.model.ManualCollapseBlock
import com.openlog.model.ManualCollapseDirection
import com.openlog.model.SequenceDef
import com.openlog.model.ThemePreset
import com.openlog.ui.AppState
import com.openlog.ui.DesktopStorage
import com.openlog.ui.ImportFilterAction
import com.openlog.ui.SEQ_COLORS
import com.openlog.ui.SplitMode
import com.openlog.ui.blockOrderDuringDrag
import com.openlog.ui.cumulativeBlockOffsets
import com.openlog.ui.mkTab
import com.openlog.ui.sequenceOrderDuringDrag
import com.openlog.ui.sequenceRenderY
import com.openlog.ui.sequenceRowBaseBackground
import com.openlog.ui.shouldSyncSequenceVisualOrder
import com.openlog.ui.themeColors
import com.openlog.utils.SPLIT_PROMPT_BYTES
import com.openlog.utils.buildMd
import com.openlog.utils.computeItems
import java.io.File
import java.io.RandomAccessFile
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.io.path.createTempDirectory
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertSame
import kotlin.test.assertTrue

class AppStateBehaviorTest {
    @Test
    fun startsWithNoOpenTabs() {
        val state = AppState()

        assertTrue(state.tabs.isEmpty())
        assertEquals(null, state.activeTab())
        assertEquals(ThemePreset.LIGHT, state.settings.theme)
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
        val userNotesDir = File(dir, "user-notes").apply { mkdirs() }
        File(archiveCacheDir, "a.tmp").writeText("12345")
        File(archiveCacheDir, "nested").apply { mkdirs() }.resolve("b.tmp").writeText("123")
        File(notesDir, "cached_analysis.md").writeText("note")
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
        assertEquals(12L, state.archiveCacheSizeBytes)
        File(archiveCacheDir, "later.tmp").writeText("xx")
        assertEquals(12L, state.archiveCacheSizeBytes)

        state.refreshArchiveCacheInfo()
        assertEquals(14L, state.archiveCacheSizeBytes)

        state.requestClearCache()
        assertTrue(state.cacheClearConfirmOpen)
        assertTrue(File(notesDir, "cached_analysis.md").exists())
        assertTrue(File(userNotesDir, "saved_analysis.md").exists())

        state.confirmClearCache()
        assertFalse(state.cacheClearConfirmOpen)
        assertEquals(0L, state.archiveCacheSizeBytes)
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
        state.updateFilterPanelWidth(333f)
        state.updateAnnotationPanelWidth(444f)
        state.updateCompareSplit(0.72f)

        val restored = AppState(cacheFile, restoreOnCreate = true)

        assertEquals(false, restored.filterVisible)
        assertEquals(false, restored.annotationVisible)
        assertEquals(true, restored.compareMode)
        assertEquals(false, restored.compareFilterRight)
        assertEquals(333f, restored.filterPanelWidth)
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
        }

        val restored = AppState(cacheFile, restoreOnCreate = true)

        assertEquals(false, restored.fpState.hlListExpanded)
        assertEquals(false, restored.fpState.lvlExpanded)
        assertEquals(false, restored.fpState.seqExpanded)
        assertEquals(false, restored.fpState.sfExpanded)
        assertEquals(false, restored.fpState.incPillsExpanded)
        assertEquals(false, restored.fpState.incMsgPillsExpanded)
        assertEquals(true, restored.fpState.excMsgPillsExpanded)
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
        assertEquals(false, rules[0].include)
        assertEquals(true, rules[1].include)
        assertEquals("heartbeat", rules[0].pattern)
        assertEquals("com.app.Network", rules[0].tag)
    }

    @Test
    fun canDisableAndRemoveManualCollapseBlocks() {
        val state = AppState()
        state.tabs = listOf(mkTab("log", "test.log", listOf(LogEntry(1, "10:00:00.000", LogLevel.I, "App", "Boom"))))
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
        state.addMessageRule(tabId, include = true, pattern = "timeout", regex = true, tag = "NetTag", packagePrefix = null)

        state.saveFilter(tabId, "rule-filter")
        state.clearFilter(tabId)
        state.loadFilter(tabId, state.savedFilters.single())

        val rule = state.tabs.single().filter.messageRules.single()
        assertEquals("timeout", rule.pattern)
        assertEquals(true, rule.include)
        assertEquals(true, rule.regex)
        assertEquals("NetTag", rule.tag)
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

    // ── extractMsgText (via addHlFromCtx) ────────────────────────────────────

    @Test
    fun extractMsgTextStripsTagColonPrefix() {
        // Branch: sel starts with "tag: " but does not end with full entry.msg
        val state = AppState()
        val entry = LogEntry(1, "10:00:00.000", LogLevel.I, "MyTag", "full message")
        state.tabs = listOf(mkTab("t1", "test.log", listOf(entry)))
        state.ctx = CtxMenuState("t1", 1, 0f, 0f, "MyTag: partial text")

        state.addHlFromCtx()

        assertEquals("partial text", state.tabs.single().filter.highlighters.single().pattern)
    }

    @Test
    fun extractMsgTextNoisyPrefixStrippedWhenSelEndsWithMsg() {
        // Branch: sel ends with entry.msg and is longer → return entry.msg
        val state = AppState()
        val entry = LogEntry(1, "10:00:00.000", LogLevel.I, "MyTag", "real message")
        state.tabs = listOf(mkTab("t1", "test.log", listOf(entry)))
        state.ctx = CtxMenuState("t1", 1, 0f, 0f, "10:00:00.000  I MyTag real message")

        state.addHlFromCtx()

        assertEquals("real message", state.tabs.single().filter.highlighters.single().pattern)
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
        val state = AppState()
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
            val state = AppState()
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
        val state = AppState()
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
