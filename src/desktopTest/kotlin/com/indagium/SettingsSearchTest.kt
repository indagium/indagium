package com.indagium

import com.indagium.ui.SETTINGS_SEARCH_INDEX
import com.indagium.ui.SettingsGroupKind
import com.indagium.ui.SettingsPage
import com.indagium.ui.holiday.*
import com.indagium.ui.matchRanges
import com.indagium.ui.searchHighlightTerms
import com.indagium.ui.searchMatchedKeywords
import com.indagium.ui.searchSettings
import com.indagium.ui.searchTokens
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class SettingsSearchTest {
    private val all = SettingsPage.entries.toSet()

    private fun labels(query: String, pages: Set<SettingsPage> = all) =
        searchSettings(query, pages).map { it.label }

    @Test
    fun entryIdsAreUnique() {
        val ids = SETTINGS_SEARCH_INDEX.map { it.id }
        assertEquals(ids.size, ids.toSet().size, "duplicate ids: ${ids.groupBy { it }.filterValues { it.size > 1 }.keys}")
    }

    @Test
    fun everyPageHasAtLeastOneEntry() {
        for (page in SettingsPage.entries) {
            assertTrue(SETTINGS_SEARCH_INDEX.any { it.page == page }, "no entries for ${page.title}")
        }
    }

    @Test
    fun theEighteenPagesSitInSevenGroupsInTheNavOrder() {
        val top = SettingsPage.entries.filter { it.parent == null }
        assertEquals(
            listOf(
                "General", "Appearance", "Tabs & toolbar", "Log view", "Search & filters", "Issue categories", "Source code",
                "Notes & writing", "Copy & export", "Capture & video", "Providers", "Commands", "Voice input",
                "Test runs", "Issue tracker", "Storage & files", "Integrations", "About",
            ),
            top.map { it.title },
        )
        assertEquals(
            listOf("Workspace", "Reading logs", "Notes", "Devices", "AI", "Testing", "System"),
            SettingsGroupKind.entries.map { it.label },
        )
        assertEquals(top.map { it.group }, top.map { it.group }.sortedBy { it.ordinal }, "pages are listed group by group")
        assertTrue(SettingsPage.entries.all { it.navPage.parent == null })
    }

    @Test
    fun aSubPageEntryIsFoundThroughItsParentTitleAndItsBreadcrumbNamesTheParent() {
        assertEquals("Devices › Capture & video › Mark issue", SettingsPage.CaptureMarkIssue.breadcrumb)
        assertEquals("Devices › Capture & video", SettingsPage.CaptureVideo.breadcrumb)
        val found = searchSettings("capture video bitrate", all)
        assertTrue(found.any { it.page == SettingsPage.CaptureLimits && it.label == "Bitrate Mbps" }, found.toString())
        assertEquals(SettingsPage.CaptureVideoPlayback, SETTINGS_SEARCH_INDEX.single { it.label == "Video follow readout" }.page)
        assertEquals(SettingsPage.CaptureTools, SETTINGS_SEARCH_INDEX.single { it.label == "ADB path" }.page)
        assertEquals(SettingsPage.CaptureVideo, SETTINGS_SEARCH_INDEX.single { it.label == "Device display" }.page)
    }

    @Test
    fun theGroupLabelAndPageTitleAreSearchable() {
        assertTrue("Profile" in labels("providers"), labels("providers").toString())
        val reading = searchSettings("reading logs", all)
        assertTrue(reading.isNotEmpty() && reading.all { it.page.group == SettingsGroupKind.ReadingLogs }, reading.toString())
        assertEquals("Reading logs › Log view", SettingsPage.LogView.breadcrumb)
    }

    @Test
    fun everyPageIdIsPrefixedWithItsPageName() {
        assertTrue(SETTINGS_SEARCH_INDEX.all { it.id.startsWith("${it.page.name}.") })
    }

    @Test
    fun everyEntryHasALabelAndAHint() {
        for (entry in SETTINGS_SEARCH_INDEX) {
            assertTrue(entry.label.isNotBlank() && entry.hint.isNotBlank(), entry.id)
        }
    }

    @Test
    fun folderFindsTheStorageAndTestRunFolderRows() {
        val found = searchSettings("folder", all)
            .filter { it.page == SettingsPage.Storage || it.page == SettingsPage.TestRuns }.map { it.label }
        assertTrue(
            found.containsAll(
                listOf(
                    "Default save folder", "Analysis artifacts folder", "Capture sessions folder",
                    "Snapshots folder", "Saved captures folder",
                    "Test suites folder", "Test runs folder", "Issues folder",
                ),
            ),
            found.toString(),
        )
    }

    @Test
    fun directoryFindsTheFolderRowsThroughTheSynonym() {
        val found = labels("directory")
        assertTrue("Default save folder" in found, found.toString())
        assertTrue("Analysis artifacts folder" in found, found.toString())
    }

    @Test
    fun matchingIsCaseInsensitive() {
        assertEquals(labels("folder"), labels("FOLDER"))
        assertEquals(labels("folder"), labels("  Folder  "))
    }

    @Test
    fun everyTokenMustMatch() {
        val found = labels("log font")
        assertEquals("Log font", found.first(), found.toString())
        assertTrue("Log font size" in found, found.toString())
        assertFalse("Theme" in found, found.toString())
        assertTrue(labels("font zzzznotathing").isEmpty())
    }

    @Test
    fun blankQueryReturnsNothing() {
        assertTrue(searchSettings("", all).isEmpty())
        assertTrue(searchSettings("   ", all).isEmpty())
    }

    @Test
    fun hiddenPagesAreExcluded() {
        val withVoice = searchSettings("recognition", all)
        assertTrue(withVoice.any { it.page == SettingsPage.VoiceInput })

        val withoutVoice = searchSettings("recognition", all - SettingsPage.VoiceInput)
        assertTrue(withoutVoice.none { it.page == SettingsPage.VoiceInput })
    }

    @Test
    fun labelPrefixMatchesComeFirst() {
        val found = labels("font")
        // "Font family" starts with the query; "Log font size" only contains it.
        assertTrue(found.indexOf("Font family") < found.indexOf("Log font size"), found.toString())
    }

    @Test
    fun synonymsBridgeThemeAndColor() {
        assertTrue("Theme" in labels("colour"))
        assertTrue("Ctrl/Cmd+F opens" in labels("shortcut"))
    }

    @Test
    fun theTestRunsAndIssueTrackerPagesAreSearchable() {
        assertTrue(SettingsPage.TestRuns in all && SettingsPage.IssueTracker in all)
        assertEquals("Issue tracker", SettingsPage.IssueTracker.title)
        assertEquals("Test runs", SettingsPage.TestRuns.title)
        assertEquals(SettingsGroupKind.Testing, SettingsPage.TestRuns.group)
        assertTrue("Access token" in labels("keychain"), labels("keychain").toString())
        assertTrue("Tracker MCP URL" in labels("mcp url"), labels("mcp url").toString())
        assertTrue("Issue instructions" in labels("project key"), labels("project key").toString())
        assertTrue("Test connection" in labels("connect"), labels("connect").toString())
        assertTrue("Default judge" in labels("judge"), labels("judge").toString())
        assertTrue("Edition" in labels("suites"), labels("suites").toString())
        assertTrue("Keep recording evidence with recorded cases" in labels("recording evidence"), labels("recording evidence").toString())
        assertTrue("Stored recording evidence" in labels("delete disk"), labels("delete disk").toString())
        assertTrue(searchSettings("token", all - SettingsPage.IssueTracker).none { it.page == SettingsPage.IssueTracker })
        // The custom issue categories keep their own entry, on the Issue categories page.
        assertTrue(SETTINGS_SEARCH_INDEX.any { it.page == SettingsPage.IssueCategories && it.label == "Custom issue categories" })
    }

    @Test
    fun errorHighlightingAndPersonalVocabularyAreSearchable() {
        assertTrue("Error rows" in labels("error highlight"))
        assertTrue("Error rows" in labels("error level"))
        assertTrue("Error rows" in labels("crash"))
        assertTrue("Personal vocabulary" in labels("dictionary words"))
        assertTrue("Personal vocabulary" in labels("personal"))
        // The index titles match the on-screen anchors exactly.
        assertTrue(SETTINGS_SEARCH_INDEX.none { it.label == "Error level rows" || it.label == "Personal proofreading vocabulary" })
    }

    @Test
    fun matchRangesMergeAndIgnoreCase() {
        assertEquals(listOf(0..2), matchRanges("Log font", listOf("log")))
        assertEquals(listOf(0..2, 4..7), matchRanges("Log font", listOf("log", "font")))
        assertEquals(listOf(0..3), matchRanges("abcd", listOf("ab", "bc", "cd")))
        assertTrue(matchRanges("abc", emptyList()).isEmpty())
    }

    @Test
    fun halloweenAndPumpkinFindTheSeasonalThemeRows() {
        for (query in listOf("halloween", "pumpkin", "seasonal", "spooky")) {
            val found = labels(query)
            assertTrue("Special theme" in found, "$query: $found")
            assertTrue("Special theme pack" in found, "$query: $found")
        }
        for (query in listOf("haunted", "manor", "ghost", "cobweb")) {
            val found = labels(query)
            assertTrue("Special theme" in found, "$query: $found")
            assertTrue("Special theme pack" in found, "$query: $found")
        }
    }

    @Test
    fun shapeStyleIsFoundByItsWords() {
        for (query in listOf("corners", "shape", "rounded", "square", "radius", "soft")) {
            assertTrue("Shape style" in labels(query), "$query: ${labels(query)}")
        }
        val entry = searchSettings("corners", all).first { it.label == "Shape style" }
        assertEquals("Shape style", entry.anchor)
    }

    @Test
    fun toolbarShapeIsFoundByItsWordsAndShapeStyleNoLongerClaimsTheToolbar() {
        assertTrue("Toolbar shape" in labels("toolbar"), labels("toolbar").toString())
        for (query in listOf("menu buttons", "capsule", "pill", "toolbar corners", "buttons")) {
            assertTrue("Toolbar shape" in labels(query), "$query: ${labels(query)}")
        }
        val entry = searchSettings("toolbar shape", all).first { it.label == "Toolbar shape" }
        assertEquals("Toolbar shape", entry.anchor)
        val shape = searchSettings("corners", all).first { it.label == "Shape style" }
        assertTrue(shape.hint.startsWith("How round tiles are"), shape.hint)
    }

    @Test
    fun theSeasonalTabsRowIsFoundAndOpensTheSpecialThemesTab() {
        for (query in listOf("seasonal tabs", "my tab style", "special tab")) {
            assertTrue("Special theme tabs" in labels(query), "$query: ${labels(query)}")
        }
        val entry = searchSettings("seasonal tabs", all).first { it.label == "Special theme tabs" }
        assertEquals(SEASONAL_ANCHOR_TABS, entry.anchor)
        assertTrue(entry.anchor in SEASONAL_ANCHORS, "a jump to it opens the Special themes tab")
    }

    @Test
    fun theTabStyleHintNamesEveryStyle() {
        val hint = searchSettings("tab style", all).first { it.label == "Tab style" }.hint
        assertTrue("outlined" in hint && "pill" in hint, hint)
    }

    @Test
    fun theSeasonalEffectRowsAreFoundByWhatTheyDo() {
        assertTrue("Special effects" in labels("bats"))
        assertTrue("Special effects" in labels("lightning"))
        assertTrue("Fun icons" in labels("icons"))
        assertTrue("Pause effects while capturing or playing video" in labels("pause"))
        assertTrue("Special light or dark" in labels("follow preset"))
        assertTrue(searchSettings("halloween", all).all { it.page == SettingsPage.Appearance })
    }

    @Test
    fun everyResultShowsWhyItMatched() {
        // "font" reaches Error rows only through a keyword: the result says which one instead of showing no mark at all.
        val errorRows = searchSettings("font", all).first { it.label == "Error rows" }
        assertEquals(listOf("font"), searchMatchedKeywords(errorRows, searchTokens("font")))
        // A word that is in the label or description is marked there, so it needs no note.
        val interfaceFont = searchSettings("font", all).first { it.label == "Interface font" }
        assertTrue(searchMatchedKeywords(interfaceFont, searchTokens("font")).isEmpty())
        for (query in listOf("font", "colour", "folder", "capture", "shortcut")) {
            for (entry in searchSettings(query, all)) {
                val tokens = searchTokens(query)
                val visible = (entry.label + " " + entry.hint).lowercase()
                val shownInText = searchHighlightTerms(tokens).any { it in visible }
                assertTrue(
                    shownInText || searchMatchedKeywords(entry, tokens).isNotEmpty(),
                    "\"${entry.label}\" matched \"$query\" with no visible reason",
                )
            }
        }
    }
}
