package com.openlog

import com.openlog.model.AnnBlock
import com.openlog.model.Filter
import com.openlog.model.FilterMode
import com.openlog.model.LogEntry
import com.openlog.model.LogLevel
import com.openlog.model.SequenceDef
import com.openlog.model.ThemePreset
import com.openlog.ui.AppState
import com.openlog.model.CtxMenuState
import androidx.compose.ui.graphics.Color
import com.openlog.ui.SEQ_COLORS
import com.openlog.ui.mkTab
import java.io.File
import kotlin.io.path.createTempDirectory
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
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
