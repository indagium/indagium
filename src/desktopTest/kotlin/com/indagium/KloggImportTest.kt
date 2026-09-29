package com.indagium

import androidx.compose.ui.graphics.Color
import com.indagium.ui.ImportFilterAction
import com.indagium.ui.buildImportRows
import com.indagium.ui.decodeFilterImport
import com.indagium.ui.exportFiltersList
import com.indagium.utils.importKloggHighlighters
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

// Synthetic klogg exports only; never a real klogg config.
internal object KloggFixtures {
    const val SET_1_ID = "{11111111-1111-1111-1111-111111111111}"

    val V2: String = """
        [General]
        geometry=@ByteArray(abc)

        [HighlighterSetCollection]
        version=2
        active_sets=$SET_1_ID
        quick\size=1
        quick\1\fore_colour=#ff000000
        quick\1\back_colour=#ffff0000
        sets\size=3
        sets\1\HighlighterSet\id=$SET_1_ID
        sets\1\HighlighterSet\name=Errors
        sets\1\HighlighterSet\version=1
        sets\1\HighlighterSet\highlighters\size=2
        sets\1\HighlighterSet\highlighters\1\regexp=ERROR (\\d+)
        sets\1\HighlighterSet\highlighters\1\use_regex=true
        sets\1\HighlighterSet\highlighters\1\ignore_case=true
        sets\1\HighlighterSet\highlighters\1\match_only=false
        sets\1\HighlighterSet\highlighters\1\fore_colour=#ffffffff
        sets\1\HighlighterSet\highlighters\1\back_colour=#ffcc0000
        sets\1\HighlighterSet\highlighters\1\variate_colors=true
        sets\1\HighlighterSet\highlighters\1\color_variance=30
        sets\1\HighlighterSet\highlighters\2\regexp=timeout
        sets\1\HighlighterSet\highlighters\2\use_regex=false
        sets\1\HighlighterSet\highlighters\2\match_only=true
        sets\1\HighlighterSet\highlighters\2\fore_colour=yellow
        sets\1\HighlighterSet\highlighters\2\back_colour=#00f
        sets\1\HighlighterSet\highlighters\2\variate_colors=true
        sets\1\HighlighterSet\highlighters\2\color_variance=20
        sets\2\HighlighterSet\id={22222222-2222-2222-2222-222222222222}
        sets\2\HighlighterSet\name=Broken
        sets\2\HighlighterSet\highlighters\size=1
        sets\2\HighlighterSet\highlighters\1\regexp=(?P<n>x)
        sets\3\HighlighterSet\name=Defaults
        sets\3\HighlighterSet\highlighters\size=1
        sets\3\HighlighterSet\highlighters\1\regexp=foo
    """.trimIndent()

    val LEGACY: String = """
        [FilterSet]
        filters\size=2
        filters\1\regexp=warn
        filters\1\fore_colour=#ff000000
        filters\1\back_colour=#ffffff00
        filters\2\regexp=fatal
    """.trimIndent()
}

class KloggImportTest {
    private fun import(text: String, file: String = "klogg.conf") = assertNotNull(importKloggHighlighters(text, file))

    @Test
    fun aV2FileBecomesOneFilterPerSet() {
        val result = import(KloggFixtures.V2)
        assertEquals(listOf("Errors", "Broken", "Defaults"), result.sets.map { it.filter.name })
        assertEquals(listOf(2, 0, 1), result.sets.map { it.filter.highlighters.size })
        // everything but the highlighters is the filter default
        val errors = result.sets[0].filter
        assertTrue(errors.activeTags.isEmpty() && errors.kwText.isEmpty() && errors.seqOn)
        // the geometry blob in [General] is not decoded and is mentioned once, import-wide
        assertEquals(1, result.notes.size)
    }

    @Test
    fun everyKloggFieldMapsOntoTheHighlighter() {
        val (first, second) = import(KloggFixtures.V2).sets[0].filter.highlighters
        assertEquals("ERROR (\\d+)", first.pattern)
        assertTrue(first.regex)
        assertFalse(first.caseSensitive) // ignore_case=true
        assertTrue(first.wholeLine) // match_only=false
        assertEquals(Color.White, first.textColor)
        assertEquals(Color(0xFFCC0000.toInt()), first.color)
        assertTrue(first.captureGroupsOnly)
        assertEquals(0, first.colorVariance) // variate_colors only applies with match_only
        assertTrue(first.on)

        assertEquals("timeout", second.pattern)
        assertFalse(second.regex) // use_regex=false
        assertTrue(second.caseSensitive) // ignore_case missing -> false
        assertFalse(second.wholeLine) // match_only=true
        assertEquals(Color(255, 255, 0), second.textColor)
        assertEquals(Color(0, 0, 255), second.color)
        assertEquals(20, second.colorVariance)
    }

    @Test
    fun missingKeysTakeKloggDefaults() {
        val hl = import(KloggFixtures.V2).sets[2].filter.highlighters.single()
        assertTrue(hl.regex)
        assertTrue(hl.caseSensitive)
        assertTrue(hl.wholeLine)
        assertEquals(Color.Black, hl.textColor)
        assertEquals(Color.White, hl.color)
        assertEquals(0, hl.colorVariance)
    }

    @Test
    fun anInvalidRegexIsSkippedWithANoteAndTheSetIsSkippedWhenNothingIsLeft() {
        val broken = import(KloggFixtures.V2).sets[1]
        assertEquals("no valid highlighters", broken.skippedReason)
        assertEquals(1, broken.notes.size)
        assertTrue(broken.notes.single().contains("not a valid Java regex"))
        assertNull(import(KloggFixtures.V2).sets[0].skippedReason)
    }

    @Test
    fun activeSetsGetANote() {
        val sets = import(KloggFixtures.V2).sets
        assertTrue("active in klogg" in sets[0].notes)
        assertFalse("active in klogg" in sets[2].notes)
    }

    @Test
    fun layoutSensitivePatternsGetANote() {
        fun noteFor(pattern: String, useRegex: Boolean = true): List<String> = import(
            """
            [HighlighterSetCollection]
            sets\size=1
            sets\1\HighlighterSet\name=S
            sets\1\HighlighterSet\highlighters\size=1
            sets\1\HighlighterSet\highlighters\1\regexp=$pattern
            sets\1\HighlighterSet\highlighters\1\use_regex=$useRegex
            """.trimIndent(),
        ).sets.single().notes

        assertTrue(noteFor("^E/Activity").single().contains("anchored with ^"))
        assertTrue(noteFor("(^Fatal)").single().contains("anchored with ^"))
        assertTrue(noteFor("12-31 \\\\d+").single().contains("date"))
        assertTrue(noteFor("\\\\d{4}-\\\\d{2}-\\\\d{2}").single().contains("date"))
        assertTrue(noteFor("E/ActivityManager", useRegex = false).single().contains("L/Tag"))
        assertTrue(noteFor("plain words").isEmpty())
        assertTrue(noteFor("a^b").isEmpty())
    }

    @Test
    fun aLegacyFilterSetIsNamedAfterTheFile() {
        val sets = import(KloggFixtures.LEGACY, "old_glogg.conf").sets
        val set = sets.single()
        assertEquals("old_glogg", set.filter.name)
        assertEquals(listOf("warn", "fatal"), set.filter.highlighters.map { it.pattern })
        assertEquals(Color(0xFFFFFF00.toInt()), set.filter.highlighters[0].color)
        assertTrue(set.filter.highlighters.all { it.textColor != null && it.wholeLine })
        assertNull(set.skippedReason)
    }

    @Test
    fun aFileWithoutHighlighterSectionsIsNotKlogg() {
        assertNull(importKloggHighlighters("[General]\nx=1\n", "a.ini"))
    }

    @Test
    fun idsAreDeterministicSoReimportShowsAsIdentical() {
        val a = import(KloggFixtures.V2).sets.map { it.filter }
        val b = import(KloggFixtures.V2).sets.map { it.filter }
        assertEquals(a, b)

        val library = decodeFilterImport("klogg.conf", KloggFixtures.V2).getOrThrow()
        val saved = library.filters.map { it.copy(id = "saved-${it.name}") }
        val rows = buildImportRows(saved, library.filters, library.rowInfo)
        assertEquals("identical", rows[0].skippedReason)
        assertEquals("no valid highlighters", rows[1].skippedReason)
        assertEquals("identical", rows[2].skippedReason)
        assertTrue(rows.all { it.action == ImportFilterAction.SKIP })
    }

    @Test
    fun rowsCarryTheImporterNotes() {
        val library = decodeFilterImport("klogg.conf", KloggFixtures.V2).getOrThrow()
        val rows = buildImportRows(emptyList(), library.filters, library.rowInfo)
        assertEquals(ImportFilterAction.ADD, rows[0].action)
        assertTrue("active in klogg" in rows[0].notes)
        assertEquals("no valid highlighters", rows[1].skippedReason)
        assertTrue(rows[1].notes.isNotEmpty())
    }

    @Test
    fun theRouterDecidesByContent() {
        val json = exportFiltersList(emptyList())
        assertTrue(decodeFilterImport("filters.json", json).isSuccess)
        assertTrue(decodeFilterImport("anything.txt", KloggFixtures.V2).isSuccess)
        assertTrue(decodeFilterImport("x.json", KloggFixtures.LEGACY).isSuccess)

        val garbage = decodeFilterImport("notes.txt", "hello world")
        assertTrue(garbage.exceptionOrNull()!!.message!!.contains("Unrecognised"))
        assertEquals("Could not read filter file.", decodeFilterImport("bad.json", "{not json").exceptionOrNull()!!.message)
        val empty = decodeFilterImport("k.conf", "[HighlighterSetCollection]\nversion=2\nsets\\size=0\n")
        assertTrue(empty.exceptionOrNull()!!.message!!.contains("No highlighter sets"))
    }
}
