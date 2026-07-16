package com.openlog

import androidx.compose.ui.graphics.Color
import com.openlog.model.AnnotationLogBlockStyle
import com.openlog.model.CrashCategory
import com.openlog.model.CtrlFTarget
import com.openlog.model.ThemePreset
import com.openlog.ui.AppState
import kotlin.io.path.createTempDirectory
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * (QUAL-1) Freezes the CURRENT "openLog2-cache-v1" autosave/settings format as a checked-in
 * fixture (`src/desktopTest/resources/goldens/autosave-v1.cache`) so a future format migration
 * (Batch 5, ARCH-2/4/5) has something concrete to prove itself against: if v1 support is kept or
 * bridged, this test must keep passing unmodified against the *same* committed bytes.
 *
 * The fixture was produced once by building a real [AppState] with every [com.openlog.model.AppSettings]
 * field pushed away from its default, two tabs with non-trivial filters/annotations/manual blocks,
 * two saved filters, and non-empty recent-files/notes/active-filter state, then calling
 * `autosaveNow()` and copying the resulting file verbatim into the resource above (see the (now
 * deleted) throwaway `GoldenGenerator` this file's history was generated from — regenerating means
 * rebuilding that same object graph and re-copying its output, never hand-editing the resource).
 *
 * Deliberate limitation: both tabs' `sourcePath` point at `/nonexistent/openlog-golden-fixture/...`
 * — a path guaranteed absent on every machine this test runs on. `tabShellFromToken()` drops any
 * tab whose backing file doesn't exist (`restoredTabSource()` returns null) *before* logData is
 * even scheduled to load, so a frozen golden — whose paths can never point at a file that exists at
 * assertion time, since it must be byte-identical across every checkout and CI runner — can never
 * assert a fully-restored tab. What it CAN and does pin at the field level: every AppSettings
 * value, both saved filters in full, and every session-metadata field that restores independently
 * of any tab's backing file (recent files/notes, active-filter map, compare/layout state, filter
 * panel UI state). The "tabs" section's raw token lines are still asserted structurally below, so
 * the tab TOKEN FORMAT itself stays pinned even though full tab restoration isn't exercised here.
 */
class AutosaveGoldenV1Test {
    @Test
    fun v1GoldenDecodesSettingsSavedFiltersAndSessionMetadataLosslessly() {
        val golden = javaClass.getResourceAsStream("/goldens/autosave-v1.cache")
            ?.bufferedReader()
            ?.readText()
            ?: error("Missing golden fixture: src/desktopTest/resources/goldens/autosave-v1.cache")

        assertEquals("openLog2-cache-v1", golden.lineSequence().first(), "golden must be pinned to the v1 format tag")

        val dir = createTempDirectory("openlog-golden-restore").toFile()
        val cacheFile = dir.resolve("state.cache").apply { writeText(golden) }
        val restored = AppState(autosaveFile = cacheFile, restoreOnCreate = true)

        // ── Settings: every AppSettings field, matching the values GoldenGenerator wrote ──
        val s = restored.settings
        assertEquals(ThemePreset.DRACULA, s.theme)
        assertEquals(16, s.fontSize)
        assertEquals(false, s.fontMono)
        assertEquals("/nonexistent/openlog-golden-fixture/saves", s.defaultSaveDir)
        assertEquals(7, s.mostUsedTagLimit)
        assertEquals(9, s.filterListRows)
        assertEquals(6, s.visibleTabLimit)
        assertEquals(false, s.autoExportNotes)
        assertEquals(false, s.autoSaveFilters)
        assertEquals(AnnotationLogBlockStyle.INDENTED, s.annotationLogBlockStyle)
        assertEquals(true, s.numberAnnotationBlocks)
        assertEquals("Ref", s.annotationPrefixLabel)
        assertEquals(8, s.navScrollMargin)
        assertEquals(600, s.logRowWrapLimitChars)
        assertEquals(false, s.autoLogRowWrap)
        assertEquals(true, s.mcpControlEnabled)
        assertEquals(9100, s.mcpControlPort)
        assertEquals(true, s.maskWordOnCopy)
        assertEquals("kotlin", s.maskWordTarget)
        assertEquals("k*otlin", s.maskWordReplacement)
        assertEquals(true, s.highlightEntireCrashGroup)
        assertEquals(CtrlFTarget.TAGS, s.ctrlFTarget)
        assertEquals(true, s.openNewFilesWithUnfiltered)
        assertEquals(
            listOf("/nonexistent/openlog-golden-fixture/src-a", "/nonexistent/openlog-golden-fixture/src-b"),
            s.sourceFolders,
        )
        assertEquals("Module A", s.sourceFolderInfo["/nonexistent/openlog-golden-fixture/src-a"]?.description)
        assertEquals(
            "/nonexistent/openlog-golden-fixture/src-a/README.md",
            s.sourceFolderInfo["/nonexistent/openlog-golden-fixture/src-a"]?.readmePath,
        )
        assertEquals(1, s.sourceLogConfigurations.size)
        assertEquals("cfg-golden-1", s.sourceLogConfigurations.single().id)
        assertEquals("Custom Logger", s.sourceLogConfigurations.single().name)
        assertEquals(1, s.sourceLogConfigurations.single().wrapperRules.size)
        assertEquals("com.example.Log", s.sourceLogConfigurations.single().wrapperRules.single().ownerType)
        assertEquals("d", s.sourceLogConfigurations.single().wrapperRules.single().methodName)
        assertEquals(
            mapOf("/nonexistent/openlog-golden-fixture/src-a" to listOf("cfg-golden-1")),
            s.sourceFolderConfigurationIds,
        )
        assertEquals(false, s.sourceAutoDiscoveryEnabled)
        assertEquals("code -g {file}:{line}", s.editorCommand)
        assertEquals(2, s.aiProviderProfiles.size)
        val anthropicProfile = s.aiProviderProfiles.single { it.id == "anthropic-golden" }
        assertEquals("Anthropic Golden", anthropicProfile.displayName)
        assertEquals("claude-sonnet-4-5", anthropicProfile.model)
        assertEquals(true, anthropicProfile.selected)
        assertEquals(true, anthropicProfile.remoteDisclosureAcknowledged)
        assertEquals(false, s.aiProviderProfiles.single { it.id == "lmstudio-golden" }.selected)
        assertEquals(250, s.aiMaxToolRounds)
        assertEquals(
            listOf(com.openlog.model.CopyMaskRule("java", "j*ava"), com.openlog.model.CopyMaskRule("secret", "s3cret")),
            s.copyMaskRules,
        )
        assertEquals(true, s.openUnfilteredOnCtrlF)
        assertEquals(true, s.mcpAllowBrowserClients)

        // ── Saved filters, in full ──
        assertEquals(2, restored.savedFilters.size)
        val crashTriage = restored.savedFilters.single { it.id == "sf-golden-1" }
        assertEquals("Crash triage", crashTriage.name)
        assertEquals(setOf(com.openlog.model.LogLevel.I, com.openlog.model.LogLevel.W, com.openlog.model.LogLevel.E), crashTriage.levels)
        assertEquals(setOf("App"), crashTriage.activeTags)
        assertEquals("fatal", crashTriage.kwText)
        assertEquals(setOf("com.example"), crashTriage.pkgPrefixes)
        assertEquals(Color(0xFFfacc15), crashTriage.kwHighlightColor)

        val networkOnly = restored.savedFilters.single { it.id == "sf-golden-2" }
        assertEquals("Network only", networkOnly.name)
        assertEquals(com.openlog.model.LogLevel.entries.toSet(), networkOnly.levels)
        assertEquals(setOf("Network"), networkOnly.activeTags)
        assertEquals(false, networkOnly.seqOn)

        // ── Session metadata that restores independently of any tab's backing file ──
        assertEquals(
            listOf("/nonexistent/openlog-golden-fixture/recent-1.log", "/nonexistent/openlog-golden-fixture/recent-2.log"),
            restored.recentFiles,
        )
        // recentNotes (unlike recentFiles) is actively pruned on restore — restoreAutosave() calls
        // pruneMissingRecentNotes() unconditionally, dropping any entry whose file no longer
        // exists. The golden's path is deliberately nonexistent (see the class doc comment), so
        // this is expected to come back empty; it still pins that the "recentNotes" token itself
        // decodes without error (a parse failure would surface as a thrown exception here, not a
        // silently-wrong list).
        assertEquals(emptyList(), restored.recentNotes)
        assertEquals(mapOf("app-a" to "sf-golden-1"), restored.activeSavedFilterIds)

        // ── Compare/layout state ──
        // compareMode/compareTabId are also downstream of the dropped tabs (see the class doc
        // comment): restoreTabsFromAutosave forces compareMode off when canCompare is false
        // (fewer than 2 real tabs) and reassigns compareTabId away from an id that no longer
        // resolves to a tab — both fire here since neither golden tab restores. The persisted
        // "compare" token value (true / "svc-b") still decodes correctly; it's the *tabs* restore
        // step immediately after that overrides it, which is exactly the real (non-golden)
        // behavior for a session whose tab files vanished between launches.
        assertEquals(false, restored.compareMode)
        assertEquals("", restored.compareTabId)
        assertEquals(false, restored.compareFilterRight)
        assertEquals(false, restored.filterVisible)
        assertEquals(true, restored.annotationVisible)
        assertEquals(true, restored.aiPanelVisible)
        assertEquals(0.35f, restored.rightSidebarSplit)
        assertEquals(260f, restored.filterPanelWidth)
        assertEquals(430f, restored.annotationPanelWidth)
        assertEquals(0.42f, restored.compareSplit)

        // ── Filter panel UI state ──
        assertEquals(true, restored.fpState.hlListExpanded)
        assertEquals(false, restored.fpState.lvlExpanded)
        assertEquals(true, restored.fpState.seqExpanded)
        assertEquals(false, restored.fpState.sfExpanded)
        assertEquals(true, restored.fpState.incPillsExpanded)
        assertEquals(false, restored.fpState.incMsgPillsExpanded)
        assertEquals(true, restored.fpState.excMsgPillsExpanded)
        assertEquals(true, restored.fpState.crashExpanded)
        assertEquals(CrashCategory.CRASHES, restored.fpState.crashCategory)

        // ── Tabs: see the class doc comment above for why a frozen golden can't exercise full
        // tab restoration — both sourcePaths are deliberately absent on every machine, so
        // tabShellFromToken() drops both shells. Assert that documented behavior explicitly
        // rather than leaving it as an unexplained gap.
        assertEquals(emptyList(), restored.tabs)

        // Structural check that the tab TOKEN FORMAT itself is still pinned in the committed
        // bytes, even though restoration can't be exercised: a "tabs" section marker followed by
        // exactly two "tab\t..." lines, one per tab GoldenGenerator wrote.
        val lines = golden.lines()
        assertTrue(lines.contains("tabs"), "golden must contain the \"tabs\" section marker")
        assertEquals(2, lines.count { it.startsWith("tab\t") }, "golden must contain exactly the 2 tab tokens it was generated with")
    }
}
