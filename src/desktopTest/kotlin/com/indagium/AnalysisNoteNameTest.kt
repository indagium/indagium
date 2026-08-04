package com.indagium

import com.indagium.ui.analysisNoteMarkdownName
import com.indagium.ui.nextFreeNoteTargetName
import kotlin.test.Test
import kotlin.test.assertEquals

class AnalysisNoteNameTest {
    @Test
    fun plainFileUsesBareFilenameBase() {
        assertEquals("logcat_analysis.md", analysisNoteMarkdownName("logcat.log"))
        assertEquals("logcat_analysis.md", analysisNoteMarkdownName("logcat.log", "/Users/me/logs/logcat.log"))
    }

    @Test
    fun archiveEntryFoldsArchiveNameIntoBase() {
        val src = "/Users/me/Downloads/20260717-081418_ADDU-222797_WithAPK.zip!" +
            "20260717-081418_ADDU-222797_WithAPK/logcat.log"
        assertEquals(
            "20260717-081418_ADDU-222797_WithAPK_logcat_analysis.md",
            analysisNoteMarkdownName("logcat.log", src),
        )
    }

    @Test
    fun differentArchivesWithSameEntryNameGetDistinctNames() {
        val a = analysisNoteMarkdownName("logcat.log", "/logs/ADDU-100.zip!bugreport/logcat.log")
        val b = analysisNoteMarkdownName("logcat.log", "/logs/ADDU-200.zip!bugreport/logcat.log")
        assertEquals("ADDU-100_logcat_analysis.md", a)
        assertEquals("ADDU-200_logcat_analysis.md", b)
    }

    @Test
    fun blankBaseFallsBackToAnalysis() {
        assertEquals("analysis_analysis.md", analysisNoteMarkdownName(".", null))
    }

    // AppState.saveNotesToNewNoteFile's "Save to a new file" choice on the note-overwrite prompt —
    // pure, filesystem-free (the caller supplies `taken` instead of this function ever touching disk).
    @Test
    fun nextFreeNoteTargetNameReturnsTheBaseNameWhenItIsFree() {
        assertEquals("sample_analysis.md", nextFreeNoteTargetName("sample_analysis.md", 1000) { false })
    }

    @Test
    fun nextFreeNoteTargetNameSkipsToTheFirstFreeNumberedSuffix() {
        val taken = setOf("sample_analysis.md", "sample_analysis_2.md")
        assertEquals(
            "sample_analysis_3.md",
            nextFreeNoteTargetName("sample_analysis.md", 1000) { it in taken },
        )
    }

    @Test
    fun nextFreeNoteTargetNameGivesUpAtMaxSuffixAndReturnsTheLastCandidateTried() {
        // Every name (including every numbered suffix) is reported taken — the walk must still
        // terminate at maxSuffix rather than looping forever, returning whatever it last tried
        // (suffixes 2, 3, 4 are attempted before the loop condition stops at suffix 5).
        assertEquals(
            "sample_analysis_4.md",
            nextFreeNoteTargetName("sample_analysis.md", maxSuffix = 5) { true },
        )
    }
}
