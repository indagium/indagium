package com.indagium

import com.indagium.ui.SETTINGS_SEARCH_INDEX
import com.indagium.ui.SettingsSection
import com.indagium.ui.matchRanges
import com.indagium.ui.searchSettings
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class SettingsSearchTest {
    private val all = SettingsSection.entries.toSet()

    private fun labels(query: String, sections: Set<SettingsSection> = all) =
        searchSettings(query, sections).map { it.label }

    @Test
    fun entryIdsAreUnique() {
        val ids = SETTINGS_SEARCH_INDEX.map { it.id }
        assertEquals(ids.size, ids.toSet().size, "duplicate ids: ${ids.groupBy { it }.filterValues { it.size > 1 }.keys}")
    }

    @Test
    fun everySectionHasAtLeastOneEntry() {
        for (section in SettingsSection.entries) {
            assertTrue(SETTINGS_SEARCH_INDEX.any { it.section == section }, "no entries for ${section.title}")
        }
    }

    @Test
    fun everyEntryHasALabelAndAHint() {
        for (entry in SETTINGS_SEARCH_INDEX) {
            assertTrue(entry.label.isNotBlank() && entry.hint.isNotBlank(), entry.id)
        }
    }

    @Test
    fun folderFindsTheGeneralFolderRows() {
        val found = searchSettings("folder", all).filter { it.section == SettingsSection.General }.map { it.label }
        assertTrue(
            found.containsAll(
                listOf(
                    "Default save folder", "Analysis artifacts folder", "Capture sessions folder",
                    "Snapshots folder", "Saved captures folder (Save ZIP)",
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
    fun hiddenSectionsAreExcluded() {
        val withVoice = searchSettings("recognition", all)
        assertTrue(withVoice.any { it.section == SettingsSection.VoiceInput })

        val withoutVoice = searchSettings("recognition", all - SettingsSection.VoiceInput)
        assertTrue(withoutVoice.none { it.section == SettingsSection.VoiceInput })
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
        assertTrue("Ctrl+F opens" in labels("shortcut"))
    }

    @Test
    fun matchRangesMergeAndIgnoreCase() {
        assertEquals(listOf(0..2), matchRanges("Log font", listOf("log")))
        assertEquals(listOf(0..2, 4..7), matchRanges("Log font", listOf("log", "font")))
        assertEquals(listOf(0..3), matchRanges("abcd", listOf("ab", "bc", "cd")))
        assertTrue(matchRanges("abc", emptyList()).isEmpty())
    }
}
