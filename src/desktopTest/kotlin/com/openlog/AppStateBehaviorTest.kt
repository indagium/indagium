package com.openlog

import com.openlog.model.AnnBlock
import com.openlog.model.Filter
import com.openlog.model.FilterMode
import com.openlog.model.LogEntry
import com.openlog.model.LogItem
import com.openlog.model.LogLevel
import com.openlog.model.SequenceDef
import com.openlog.model.ThemePreset
import com.openlog.ui.AppState
import com.openlog.model.CtxMenuState
import androidx.compose.ui.graphics.Color
import com.openlog.ui.SEQ_COLORS
import com.openlog.ui.mkTab
import com.openlog.utils.computeItems
import java.io.File
import kotlin.io.path.createTempDirectory
import kotlin.test.Test
import kotlin.test.assertEquals
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
        val state = AppState()
        state.sequences = listOf(
            SequenceDef("drop", "drop start", priority = 1, color = Color.Red, tag = "Drop"),
            SequenceDef("keep", "keep start", priority = 2, color = Color.Blue, tag = "Keep"),
        )
        state.tabs = listOf(
            mkTab("log", "test.log", logs).copy(
                filter = Filter(mode = FilterMode.TAGS, activeTags = setOf("Keep")),
            ),
        )

        state.expandAll("log")

        val tab = state.tabs.single()
        val header = computeItems(tab, state.sequences, applyFilter = true)
            .filterIsInstance<LogItem.SeqHeader>()
            .single()
        assertEquals("sg_keep_0", header.gid)
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
        val state = AppState()
        state.sequences = listOf(
            SequenceDef("drop", "drop start", priority = 1, color = Color.Red, tag = "Drop"),
            SequenceDef("keep", "keep start", priority = 2, color = Color.Blue, tag = "Keep"),
        )
        state.tabs = listOf(
            mkTab("log", "test.log", logs).copy(
                filter = Filter(mode = FilterMode.TAGS, activeTags = setOf("Keep")),
                showUnfiltered = true,
            ),
        )

        state.expandAll("log")

        val originalHeaders = computeItems(state.tabs.single(), state.sequences, applyFilter = false)
            .filterIsInstance<LogItem.SeqHeader>()
        assertEquals(listOf("sg_drop_0", "sg_keep_2"), originalHeaders.map { it.gid })
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
        val state = AppState()
        state.sequences = listOf(SequenceDef("flow", "flow start", priority = 1, color = Color.Blue, tag = "Seq"))
        state.tabs = listOf(
            mkTab("log", "test.log", logs).copy(manualBlocks = listOf(block)),
        )

        state.expandAll("log")

        val headers = computeItems(state.tabs.single(), state.sequences, applyFilter = true)
            .filterIsInstance<LogItem.SeqHeader>()
        assertEquals(listOf("sg_flow_0"), headers.map { it.gid })
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
        val state = AppState()
        state.sequences = listOf(SequenceDef("flow", "flow start", priority = 1, color = Color.Blue, tag = "Seq"))
        state.tabs = listOf(
            mkTab("log", "test.log", logs).copy(manualBlocks = listOf(block)),
        )

        state.expandAll("log")

        val items = computeItems(state.tabs.single(), state.sequences, applyFilter = true)
        val headers = items.filterIsInstance<LogItem.SeqHeader>()
        assertEquals(listOf("sg_flow_0"), headers.map { it.gid })
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
        val state = AppState()
        state.sequences = listOf(SequenceDef("flow", "flow start", priority = 1, color = Color.Blue, tag = "Seq"))
        state.tabs = listOf(
            mkTab("log", "test.log", logs).copy(manualBlocks = listOf(block)),
        )

        state.expandAll("log")

        val headers = computeItems(state.tabs.single(), state.sequences, applyFilter = true)
            .filterIsInstance<LogItem.SeqHeader>()
        assertEquals(listOf("sg_flow_1"), headers.map { it.gid })
        assertTrue(headers.single().expanded)
    }

    @Test
    fun expandAllDoesNotPreExpandSequencesWhenSequenceGroupingIsOff() {
        val logs = listOf(
            LogEntry(1, "10:00:00.000", LogLevel.I, "Keep", "keep start"),
            LogEntry(2, "10:00:00.100", LogLevel.I, "Keep", "keep child"),
        )
        val state = AppState()
        state.sequences = listOf(SequenceDef("keep", "keep start", priority = 1, color = Color.Blue, tag = "Keep"))
        state.tabs = listOf(
            mkTab("log", "test.log", logs).copy(filter = Filter(seqOn = false)),
        )

        state.expandAll("log")
        state.toggleSeq("log")

        val header = computeItems(state.tabs.single(), state.sequences, applyFilter = true)
            .filterIsInstance<LogItem.SeqHeader>()
            .single()
        assertEquals("sg_keep_0", header.gid)
        assertTrue(!header.expanded)
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
    fun savedFiltersRoundTripAllFilterFields() {
        val state = AppState()
        state.addTab()
        val tabId = state.tabs.single().id

        state.setFilterMode(tabId, FilterMode.TAGS)
        state.setKwInTag(tabId, "timeout")
        state.toggleKwInTagRx(tabId)
        state.addPkgPrefix(tabId, "com.example")
        state.setPidTidFilter(tabId, "1234,5678")
        state.saveFilter(tabId, "network")
        state.clearFilter(tabId)
        state.sequences = emptyList()

        state.loadFilter(tabId, state.savedFilters.single())

        val filter = state.tabs.single().filter
        assertEquals("timeout", filter.kwInTag)
        assertTrue(filter.kwInTagRegex)
        assertEquals(setOf("com.example"), filter.pkgPrefixes)
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
        state.sequences = emptyList()
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
    fun savedFiltersRoundTripMessageRules() {
        val state = AppState()
        state.addTab()
        val tabId = state.tabs.single().id
        state.addMessageRule(tabId, include = false, pattern = "heartbeat", regex = false, tag = "com.app.Network", packagePrefix = "")

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
    fun pendingFilterLoadCanUpdateCurrentPresetBeforeSwitching() {
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

        state.updateCurrentPresetAndLoadPending()

        assertEquals(null, state.pendingFilterLoad)
        assertEquals(setOf("com.one", "com.extra"), state.savedFilters.first { it.id == one.id }.pkgPrefixes)
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
    fun importFiltersFromDroppedFileAddsSavedFilters() {
        val dir = createTempDirectory("openlog-filters").toFile()
        val source = AppState(File(dir, "source.cache"))
        source.addTab()
        source.addPkgPrefix(source.tabs.single().id, "com.drop")
        source.saveFilter(source.tabs.single().id, "dropped")
        val file = File(dir, "filters.json").apply { writeText(source.exportFilters()) }

        val target = AppState(File(dir, "target.cache"))
        target.importFiltersFromFile(file)

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
    fun addingSequenceUsesNextUnusedColorAfterRestore() {
        val state = AppState()
        state.sequences = listOf(
            SequenceDef(
                id = "outer",
                matchText = "start",
                priority = 1,
                color = SEQ_COLORS[0],
            ),
        )
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
        state.settings = state.settings.copy(visibleTabLimit = 6)
        state.addPkgPrefix("log", "App")
        state.confirmAddAnn("log", "log", listOf(1), "remember this", null)
        state.saveFilter("log", "app only")

        state.autosaveNow()

        val restored = AppState(cacheFile, restoreOnCreate = true)
        assertEquals("test.log", restored.tabs.single().filename)
        assertEquals(6, restored.settings.visibleTabLimit)
        assertEquals(setOf("App"), restored.tabs.single().filter.pkgPrefixes)
        val block = assertIs<AnnBlock.LogRef>(restored.tabs.single().annotations.blocks.single())
        assertEquals("remember this", block.caption)
        assertEquals("app only", restored.savedFilters.single().name)
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
    fun openFilePersistsRecentFilesAndExistingAutoExportedNote() {
        val originalHome = System.getProperty("user.home")
        val home = createTempDirectory("openlog-home").toFile()
        try {
            System.setProperty("user.home", home.absolutePath)
            val notesDir = File(home, ".openlog2/notes").apply { mkdirs() }
            val logFile = File(home, "sample.log").apply {
                writeText("06-26 10:00:00.000  123  456 I App: hello\n")
            }
            val noteFile = File(notesDir, "sample.log_notes.md").apply {
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
    fun contextMenuCanAddHideAndShowOnlyMessageRules() {
        val state = AppState()
        state.tabs = listOf(mkTab("log", "test.log", listOf(LogEntry(1, "10:00:00.000", LogLevel.I, "com.app.Network", "heartbeat"))))
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
}
