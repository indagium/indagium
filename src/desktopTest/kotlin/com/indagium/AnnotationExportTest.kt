package com.indagium

import com.indagium.model.AnnBlock
import com.indagium.model.AnnotationLogBlockStyle
import com.indagium.model.Annotations
import com.indagium.model.AppSettings
import com.indagium.model.LogEntry
import com.indagium.model.LogLevel
import com.indagium.model.SharedNoteInfo
import com.indagium.ui.AppState
import com.indagium.ui.mkTab
import com.indagium.ui.sharedNoteHostTab
import com.indagium.utils.annotationFramesDirIn
import com.indagium.utils.annotationFramesFolderName
import com.indagium.utils.annotationImageFileName
import com.indagium.utils.buildMd
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

    @Test
    fun buildMdPreservesRichMarkdownInNotesAndLogAnnotations() {
        val tab = mkTab("t1", "app.log", listOf(LogEntry(1, "10:00:00.000", LogLevel.E, "App", "Boom"))).copy(
            annotations = Annotations(
                blocks = listOf(
                    AnnBlock.Note("n1", "# Investigation\n\n**Root cause:** a race."),
                    AnnBlock.LogRef("r1", listOf(1), "> Reproduces on cold start\n\n`Startup.kt:42`"),
                ),
            ),
        )

        val markdown = buildMd(tab, AppSettings())

        assertTrue(markdown.contains("# Investigation"))
        assertTrue(markdown.contains("**Root cause:** a race."))
        assertTrue(markdown.contains("> Reproduces on cold start"))
        assertTrue(markdown.contains("`Startup.kt:42`"))
    }

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

    // ── frameStamp naming (Jira attachment collision fix) ────────────────

    @Test
    fun annotationImageFileNameWithNullStampReproducesTheLegacyUnstampedName() {
        // No explicit third argument, and an explicit null, must both be indistinguishable from
        // the pre-existing (pre-stamp) behavior above — old analyses must keep their old names.
        assertEquals("frame-01.jpg", annotationImageFileName(1, "jpeg", null))
        assertEquals("frame-01.jpg", annotationImageFileName(1, "jpeg"))
    }

    @Test
    fun annotationImageFileNameWithAStampInsertsItBetweenFrameAndTheOrdinal() {
        assertEquals("frame-20260725-143012-01.jpg", annotationImageFileName(1, "jpeg", "20260725-143012"))
        assertEquals("frame-20260725-143012-02.jpg", annotationImageFileName(2, "jpeg", "20260725-143012"))
        assertEquals("frame-20260725-143012-01.png", annotationImageFileName(1, "png", "20260725-143012"))
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

    @Test
    fun exportedFrameFilenamesMatchBuildMdsJiraAnchorsWhenAFrameStampIsSet() {
        // Same regression fence as the unstamped test above, but with Annotations.frameStamp set —
        // proves buildMd's anchors and exportAnnotationFrames' written files agree on the STAMPED
        // name too, not just the legacy unstamped one.
        val dir = createTempDirectory("openlog-frame-export-stamped").toFile()
        val tab = mkTab("t1", "app.log", listOf(LogEntry(1, "10:00:00.000", LogLevel.I, "App", "line 1"))).copy(
            annotations = Annotations(
                blocks = listOf(
                    imageBlock("i1", "First", bytes = byteArrayOf(11)),
                    AnnBlock.LogRef("r1", listOf(1), "Evidence"),
                    imageBlock("i2", "Second", bytes = byteArrayOf(22)),
                ),
                frameStamp = "20260725-143012",
            ),
        )
        val state = AppState(directoryPicker = { _, _ -> dir })
        state.tabs = listOf(tab)
        state.settings = state.settings.copy(annotationLogBlockStyle = AnnotationLogBlockStyle.JIRA_JAVA)
        val md = buildMd(tab, state.settings)

        state.exportAnnotationFrames(tab.id)

        val framesDir = File(dir, "app_frames")
        waitUntil { File(framesDir, "frame-20260725-143012-02.jpg").isFile }
        assertTrue(md.contains("!frame-20260725-143012-01.jpg!"))
        assertTrue(md.contains("!frame-20260725-143012-02.jpg!"))
        assertEquals(11, File(framesDir, "frame-20260725-143012-01.jpg").readBytes().single())
        assertEquals(22, File(framesDir, "frame-20260725-143012-02.jpg").readBytes().single())
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

    // ── frames folder name (a shared note's name is typed by the user) ───

    @Test
    fun framesFolderNameKeepsTheUsualLogNameAndNeverEscapes() {
        assertEquals("app_frames", annotationFramesFolderName("app.log"))
        assertEquals("a.b_frames", annotationFramesFolderName("a.b.log"))
        assertEquals("Pairing bug_frames", annotationFramesFolderName("Pairing bug"))
        val hostile = listOf(
            "../escape", "a/b", "..\\win", "   ", "", "/abs/path", "C:\\Windows\\x", "..", ".", "a\u0000b\nc", "x<>:\"|?*y", "trail. .", "NUL",
            "../../../etc/passwd",
        )
        for (name in hostile) {
            val folder = annotationFramesFolderName(name)
            assertTrue(folder.endsWith("_frames"), name)
            assertFalse(folder.any { it in "/\\:*?\"<>|" || it.isISOControl() }, "$name -> $folder")
            assertFalse(folder.contains(".."), "$name -> $folder")
            assertTrue(folder.removeSuffix("_frames").isNotBlank(), "$name -> $folder")
        }
        assertEquals("notes_frames", annotationFramesFolderName("   "))
        assertEquals("notes_frames", annotationFramesFolderName(".."))
        assertEquals("NUL__frames", annotationFramesFolderName("NUL"))
    }

    @Test
    fun framesDirIsRefusedWhenItsNameIsASymbolicLinkOutOfTheChosenFolder() {
        val dir = createTempDirectory("openlog-frames-link").toFile()
        val outside = createTempDirectory("openlog-frames-outside").toFile()
        java.nio.file.Files.createSymbolicLink(File(dir, "app_frames").toPath(), outside.toPath())

        assertEquals(null, annotationFramesDirIn(dir, "app.log"))
        assertEquals(File(dir, "other_frames"), annotationFramesDirIn(dir, "other.log"))
    }

    @Test
    fun exportingASharedNoteWithAnEscapingNameWritesOnlyOneSafeChildFolder() {
        for (name in listOf("../escape", "a/b", "..\\win", "   ", "/abs")) {
            val root = createTempDirectory("openlog-frame-export-hostile").toFile()
            val dir = File(root, "chosen").apply { mkdirs() }
            val state = AppState(directoryPicker = { _, _ -> dir })
            val host = sharedNoteHostTab(SharedNoteInfo(id = "shared-1", name = name)).copy(
                annotations = Annotations(blocks = listOf(imageBlock("i1", "First", bytes = byteArrayOf(5)))),
            )
            state.addSharedNoteHost(host)

            state.exportAnnotationFrames(host.id)

            waitUntil { dir.listFiles().orEmpty().singleOrNull()?.listFiles().orEmpty().any { it.name == "frame-01.jpg" } }
            val children = dir.listFiles().orEmpty()
            assertEquals(1, children.size, "$name -> ${children.map { it.name }}")
            assertTrue(children.single().isDirectory && children.single().name == annotationFramesFolderName(name), name)
            // Nothing landed beside the chosen folder, in the temp root, or under any other path.
            assertEquals(listOf("chosen"), root.listFiles().orEmpty().map { it.name }, name)
            assertEquals(listOf("frame-01.jpg"), children.single().listFiles().orEmpty().map { it.name })
        }
    }

    private fun waitUntil(timeoutMs: Long = 2_000, condition: () -> Boolean) {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (!condition() && System.currentTimeMillis() < deadline) Thread.sleep(10)
        assertTrue(condition(), "condition was not met within ${timeoutMs}ms")
    }
}
