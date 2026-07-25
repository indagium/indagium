package com.openlog

import com.openlog.model.AnnBlock
import com.openlog.model.Annotations
import com.openlog.model.AnnotationLogBlockStyle
import com.openlog.model.AppSettings
import com.openlog.model.LogEntry
import com.openlog.model.LogLevel
import com.openlog.ui.AppState
import com.openlog.ui.mkTab
import com.openlog.utils.annotationImageFileName
import com.openlog.utils.buildMd
import java.io.File
import kotlin.io.path.createTempDirectory
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * C1/C2/C3 — annotationImageFileName, buildMd's JIRA_JAVA `!frame-0N.jpg!` wiki anchors, and
 * AppState.exportAnnotationFrames writing files that match those anchors exactly (the divergence
 * guard both sides route through the shared helper to prevent).
 */
class AnnotationExportTest {
    private fun imageBlock(id: String, caption: String = "", bytes: ByteArray = byteArrayOf(1, 2, 3)): AnnBlock.Image =
        AnnBlock.Image(id = id, caption = caption, provenance = "from repro.mp4", format = "jpeg", bytes = bytes)

    // ── annotationImageFileName (C1) ─────────────────────────────────────

    @Test
    fun annotationImageFileNameZeroPadsToWidthTwoAndMapsJpegToJpgExtension() {
        assertEquals("frame-01.jpg", annotationImageFileName(1, "jpeg"))
        assertEquals("frame-02.jpg", annotationImageFileName(2, "jpeg"))
        assertEquals("frame-10.jpg", annotationImageFileName(10, "jpeg"))
        assertEquals("frame-99.jpg", annotationImageFileName(99, "jpeg"))
    }

    @Test
    fun annotationImageFileNamePassesThroughAnyOtherFormatUnchanged() {
        assertEquals("frame-01.png", annotationImageFileName(1, "png"))
    }

    // ── buildMd Jira anchors (C2) ────────────────────────────────────────

    @Test
    fun buildMdEmitsSequentialJiraAnchorsAcrossImagesWithALogRefSandwichedBetweenThem() {
        val tab = mkTab("t1", "app.log", listOf(LogEntry(1, "10:00:00.000", LogLevel.I, "App", "line 1"))).copy(
            annotations = Annotations(
                blocks = listOf(
                    imageBlock("i1", "First"),
                    AnnBlock.LogRef("r1", listOf(1), "Evidence"),
                    imageBlock("i2", "Second"),
                ),
            ),
        )

        val md = buildMd(tab, AppSettings(annotationLogBlockStyle = AnnotationLogBlockStyle.JIRA_JAVA))

        // The image ordinal tracks image position only — the LogRef block in between (and its own
        // number/{code:java} content) must not shift the second image's anchor past frame-02.
        assertTrue(md.contains("!frame-01.jpg!"))
        assertTrue(md.contains("!frame-02.jpg!"))
        assertTrue(md.indexOf("!frame-01.jpg!") < md.indexOf("!frame-02.jpg!"))
    }

    @Test
    fun buildMdJiraAnchorOrdinalIsTheSameWhetherOrNotBlockNumberingIsOn() {
        val tab = mkTab("t1", "app.log", emptyList()).copy(
            annotations = Annotations(blocks = listOf(AnnBlock.Note("n1", "Context"), imageBlock("i1"), imageBlock("i2"))),
        )

        val numbered = buildMd(
            tab,
            AppSettings(annotationLogBlockStyle = AnnotationLogBlockStyle.JIRA_JAVA, numberAnnotationBlocks = true),
        )
        val unnumbered = buildMd(
            tab,
            AppSettings(annotationLogBlockStyle = AnnotationLogBlockStyle.JIRA_JAVA, numberAnnotationBlocks = false),
        )

        assertTrue(numbered.contains("!frame-01.jpg!"))
        assertTrue(numbered.contains("!frame-02.jpg!"))
        assertTrue(unnumbered.contains("!frame-01.jpg!"))
        assertTrue(unnumbered.contains("!frame-02.jpg!"))
    }

    @Test
    fun buildMdIndentedStyleKeepsThePlainScreenshotMarkerInsteadOfAJiraAnchor() {
        val tab = mkTab("t1", "app.log", emptyList()).copy(
            annotations = Annotations(blocks = listOf(imageBlock("i1", "Crash"))),
        )

        val md = buildMd(tab, AppSettings(annotationLogBlockStyle = AnnotationLogBlockStyle.INDENTED))

        // Bare marker: the source of a pasted/dropped image is never named in an export — only a
        // video frame carries a "From <video> @ <time>" line (AnnBlock.Image.displayProvenance).
        assertTrue(md.contains("[screenshot]"))
        assertFalse(md.contains("from repro.mp4"))
        assertFalse(md.contains("!frame-01.jpg!"))
    }

    // ── exportAnnotationFrames (C3) ──────────────────────────────────────

    @Test
    fun exportedFrameFilenamesMatchBuildMdsJiraAnchorsForTheSameBlockOrder() {
        val dir = createTempDirectory("openlog-frame-export").toFile()
        val tab = mkTab("t1", "app.log", listOf(LogEntry(1, "10:00:00.000", LogLevel.I, "App", "line 1"))).copy(
            annotations = Annotations(
                blocks = listOf(
                    imageBlock("i1", "First", bytes = byteArrayOf(11)),
                    AnnBlock.LogRef("r1", listOf(1), "Evidence"),
                    imageBlock("i2", "Second", bytes = byteArrayOf(22)),
                ),
            ),
        )
        val state = AppState(directoryPicker = { _, _ -> dir })
        state.tabs = listOf(tab)
        state.settings = state.settings.copy(annotationLogBlockStyle = AnnotationLogBlockStyle.JIRA_JAVA)
        val md = buildMd(tab, state.settings)

        state.exportAnnotationFrames(tab.id)

        val framesDir = File(dir, "app_frames")
        waitUntil { File(framesDir, "frame-02.jpg").isFile }
        // Same names buildMd referenced, written with the right (per-block) bytes — proves the
        // exporter and buildMd cannot silently drift onto different ordinals for the same blocks.
        assertTrue(md.contains("!frame-01.jpg!"))
        assertTrue(md.contains("!frame-02.jpg!"))
        assertEquals(11, File(framesDir, "frame-01.jpg").readBytes().single())
        assertEquals(22, File(framesDir, "frame-02.jpg").readBytes().single())
        // Only the images are written — no .md/.ann note file lands in the picked folder.
        assertTrue(dir.listFiles().orEmpty().none { it.isFile })
    }

    // ── exportAnalysisTo also writes the referenced frame images (Fix 2) ──

    @Test
    fun exportAnalysisToWritesFrameImagesMatchingBuildMdsJiraAnchorsNextToTheMd() {
        val dir = createTempDirectory("openlog-export-analysis").toFile()
        val tab = mkTab("t1", "app.log", listOf(LogEntry(1, "10:00:00.000", LogLevel.I, "App", "line 1"))).copy(
            annotations = Annotations(
                blocks = listOf(
                    imageBlock("i1", "First", bytes = byteArrayOf(11)),
                    AnnBlock.LogRef("r1", listOf(1), "Evidence"),
                    imageBlock("i2", "Second", bytes = byteArrayOf(22)),
                ),
            ),
        )
        val state = AppState()
        state.tabs = listOf(tab)
        state.settings = state.settings.copy(annotationLogBlockStyle = AnnotationLogBlockStyle.JIRA_JAVA)
        val mdFile = File(dir, "app_analysis.md")

        val ok = state.exportAnalysisTo(tab.id, mdFile)

        assertTrue(ok)
        assertTrue(mdFile.readText().contains("!frame-01.jpg!"))
        assertTrue(mdFile.readText().contains("!frame-02.jpg!"))
        val framesDir = File(dir, "app_analysis_frames")
        assertEquals(11, File(framesDir, "frame-01.jpg").readBytes().single())
        assertEquals(22, File(framesDir, "frame-02.jpg").readBytes().single())
    }

    @Test
    fun exportAnalysisToDoesNotCreateAFramesFolderWhenTheTabHasNoImageBlocks() {
        val dir = createTempDirectory("openlog-export-analysis-empty").toFile()
        val tab = mkTab("t1", "app.log", listOf(LogEntry(1, "10:00:00.000", LogLevel.I, "App", "line 1")))
        val state = AppState()
        state.tabs = listOf(tab)
        val mdFile = File(dir, "app_analysis.md")

        val ok = state.exportAnalysisTo(tab.id, mdFile)

        assertTrue(ok)
        assertFalse(File(dir, "app_analysis_frames").exists())
    }

    @Test
    fun exportAnnotationFramesIsANoOpWhenTheTabHasNoImageBlocks() {
        val dir = createTempDirectory("openlog-frame-export-empty").toFile()
        var pickerCalled = false
        val tab = mkTab("t1", "app.log", emptyList())
        val state = AppState(directoryPicker = { _, _ -> pickerCalled = true; dir })
        state.tabs = listOf(tab)

        state.exportAnnotationFrames(tab.id)

        assertFalse(pickerCalled, "an empty (no-image) tab must not even prompt for a folder")
        assertTrue(dir.listFiles().orEmpty().isEmpty())
    }

    private fun waitUntil(timeoutMs: Long = 2_000, condition: () -> Boolean) {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (!condition() && System.currentTimeMillis() < deadline) Thread.sleep(10)
        assertTrue(condition(), "condition was not met within ${timeoutMs}ms")
    }
}
