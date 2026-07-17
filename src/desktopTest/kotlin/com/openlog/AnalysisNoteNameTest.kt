package com.openlog

import com.openlog.ui.analysisNoteMarkdownName
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
}
