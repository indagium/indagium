package com.indagium

import com.indagium.model.AnnBlock
import com.indagium.model.AnnotationLogBlockStyle
import com.indagium.model.Annotations
import com.indagium.model.AppSettings
import com.indagium.model.LogEntry
import com.indagium.model.LogLevel
import com.indagium.ui.mkTab
import com.indagium.utils.buildMd
import com.indagium.utils.recoverLogRefRows
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Covers relink-log Change 2: a note opened without its log (or with a different log) shows no
 * lines for a LogRef block saved before Annotations.materializeLogRefs existed, because
 * sourceEntries is null and the log-less tab's rmap can't resolve logIds either. recoverLogRefRows
 * (utils/NoteMarkdownRecovery.kt) is the last-resort fallback: it re-parses the paired .md, which
 * DOES still carry the row text as plain text (that's why the Case Library preview, which renders
 * the .md, kept showing the lines the panel could not).
 *
 * These tests build the .md through the real buildMd/appendLogRefBlock (Filter.kt) wherever
 * possible, rather than hand-writing fixture text, so a future format change to that function is
 * caught here instead of silently invalidating the recovery parser.
 */
class NoteMarkdownRecoveryTest {
    private val sampleEntries = listOf(
        LogEntry(1, "10:00:00.000", LogLevel.I, "App", "first evidence line"),
        LogEntry(2, "10:00:00.100", LogLevel.W, "App", "second evidence line"),
    )

    private fun tabWithLogRefBlocks(entries: List<LogEntry>, blocks: List<AnnBlock.LogRef>) =
        mkTab("t1", "app.log", entries).copy(annotations = Annotations(blocks = blocks))

    @Test
    fun indentedStyleRecoversRowsMatchingLogIds() {
        val block = AnnBlock.LogRef("r1", listOf(1, 2), "evidence")
        val tab = tabWithLogRefBlocks(sampleEntries, listOf(block))
        val md = buildMd(tab, AppSettings(annotationLogBlockStyle = AnnotationLogBlockStyle.INDENTED))

        val recovered = recoverLogRefRows(md, listOf(block))

        assertEquals(sampleEntries, recovered["r1"])
    }

    @Test
    fun jiraJavaStyleRecoversRowsMatchingLogIds() {
        val block = AnnBlock.LogRef("r1", listOf(1, 2), "evidence")
        val tab = tabWithLogRefBlocks(sampleEntries, listOf(block))
        val md = buildMd(tab, AppSettings(annotationLogBlockStyle = AnnotationLogBlockStyle.JIRA_JAVA))

        val recovered = recoverLogRefRows(md, listOf(block))

        assertEquals(sampleEntries, recovered["r1"])
    }

    @Test
    fun bothStylesAreAcceptedRegardlessOfTheCallersCurrentSetting() {
        // The note may have been written under whichever style was active THEN, not whichever is
        // active NOW — recoverLogRefRows must not assume they match.
        val block = AnnBlock.LogRef("r1", listOf(1, 2), "evidence")
        val tab = tabWithLogRefBlocks(sampleEntries, listOf(block))
        val jiraMd = buildMd(tab, AppSettings(annotationLogBlockStyle = AnnotationLogBlockStyle.JIRA_JAVA))
        val indentedMd = buildMd(tab, AppSettings(annotationLogBlockStyle = AnnotationLogBlockStyle.INDENTED))

        assertEquals(sampleEntries, recoverLogRefRows(jiraMd, listOf(block))["r1"])
        assertEquals(sampleEntries, recoverLogRefRows(indentedMd, listOf(block))["r1"])
    }

    @Test
    fun blockCountMismatchReturnsAnEmptyMapRatherThanGuessAnAlignment() {
        val blockA = AnnBlock.LogRef("rA", listOf(1), "first")
        val blockB = AnnBlock.LogRef("rB", listOf(2), "second")
        val tab = tabWithLogRefBlocks(sampleEntries, listOf(blockA, blockB))
        val md = buildMd(tab, AppSettings(annotationLogBlockStyle = AnnotationLogBlockStyle.INDENTED))

        // The .md actually contains two log blocks, but the caller only supplies one — e.g. the
        // in-memory annotations were hand-edited (a block removed) after the .md was last written.
        val recovered = recoverLogRefRows(md, listOf(blockA))

        assertTrue(recovered.isEmpty())
    }

    @Test
    fun rowCountMismatchSkipsOnlyThatBlockNotTheWholeMap() {
        // Hand-written rather than built through buildMd: this simulates a block whose row run was
        // truncated on disk (see the multi-line-msg test below for how that happens in practice)
        // while a well-formed block sits right after it in the same document. blockA claims 2 ids
        // but the document only has 1 indented row line for it; blockB claims 2 ids and has 2.
        val blockA = AnnBlock.LogRef("rA", listOf(1, 2), "short block")
        val blockB = AnnBlock.LogRef("rB", listOf(3, 4), "full block")
        val md = """
            short block

                10:00:00.000  I/App  only row present for block A

            full block

                10:00:01.000  I/App  row one for block B
                10:00:01.100  W/App  row two for block B

        """.trimIndent()

        val recovered = recoverLogRefRows(md, listOf(blockA, blockB))

        assertTrue("rA" !in recovered, "the short block must be skipped, not padded or misaligned")
        assertEquals(
            listOf(
                LogEntry(3, "10:00:01.000", LogLevel.I, "App", "row one for block B"),
                LogEntry(4, "10:00:01.100", LogLevel.W, "App", "row two for block B"),
            ),
            recovered["rB"],
        )
    }

    @Test
    fun multiLineMessageFailsSafeInsteadOfProducingMisalignedRows() {
        // A msg containing an embedded newline (Filter.kt's appendLogRefBlock writes it via a bare
        // appendLine(msg), which does not re-indent continuation lines) breaks the contiguous
        // four-space run early: the continuation line lands unindented, splitting what should be
        // ONE row block into two candidate row blocks on disk. With only one actual LogRef block in
        // `blocks`, that extra candidate makes the document-wide block count disagree with
        // `blocks.size` — recovery bails out for the whole document rather than guessing which half
        // of the split belongs where.
        val entries = listOf(
            LogEntry(1, "10:00:00.000", LogLevel.I, "App", "first line of a multi-line message\nsecond line"),
            LogEntry(2, "10:00:00.100", LogLevel.W, "App", "single-line message"),
        )
        val block = AnnBlock.LogRef("r1", listOf(1, 2), "evidence")
        val tab = tabWithLogRefBlocks(entries, listOf(block))
        val md = buildMd(tab, AppSettings(annotationLogBlockStyle = AnnotationLogBlockStyle.INDENTED))

        val recovered = recoverLogRefRows(md, listOf(block))

        assertTrue(recovered.isEmpty(), "a multi-line msg must fail the whole document safe, not silently truncate row 1")
    }

    @Test
    fun pidAndTidAreLeftAtDefaultsRatherThanInvented() {
        val entries = listOf(LogEntry(1, "10:00:00.000", LogLevel.I, "App", "line", pid = 123, tid = 456))
        val block = AnnBlock.LogRef("r1", listOf(1), "evidence")
        val tab = tabWithLogRefBlocks(entries, listOf(block))
        val md = buildMd(tab, AppSettings(annotationLogBlockStyle = AnnotationLogBlockStyle.INDENTED))

        val recovered = recoverLogRefRows(md, listOf(block))

        val recoveredEntry = requireNotNull(recovered["r1"]?.single())
        assertEquals(0, recoveredEntry.pid, "the .md never recorded pid — recovery must not invent one")
        assertEquals(0, recoveredEntry.tid, "the .md never recorded tid — recovery must not invent one")
        assertEquals("line", recoveredEntry.msg)
    }
}
