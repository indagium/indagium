package com.openlog.debug

import com.openlog.model.AnnBlock
import com.openlog.model.FilterMode
import com.openlog.model.LogEntry
import com.openlog.model.LogLevel
import com.openlog.model.VideoAttachment
import com.openlog.ui.AppState
import com.openlog.ui.mkTab
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import java.awt.image.BufferedImage
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.Base64
import javax.imageio.ImageIO
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class OpenLogToolGatewayTest {
    private lateinit var state: AppState
    private lateinit var server: ControlServer
    private lateinit var operations: OpenLogToolOperations

    @BeforeTest
    fun setUp() {
        state = AppState(autosaveFile = File.createTempFile("openlog-gateway-test", ".cache"))
        state.tabs = listOf(mkTab("t1", "sample.log", listOf(LogEntry(1, "10:00:00.000", LogLevel.I, "App", "hello"))))
        operations = OpenLogToolOperations(state)
        server = ControlServer(state, 0)
    }

    @AfterTest
    fun tearDown() {
        server.stop()
        state.close()
    }

    @Test
    fun catalogHasEveryCurrentToolExactlyOnce() {
        val expected = setOf(
            "list_tabs", "open_log_file", "preview_split_log_file", "split_log_file", "close_tab",
            "get_filter", "set_filter", "get_visible_lines", "get_line_context", "select_lines", "get_selection",
            "toggle_group", "expand_all", "collapse_all", "get_tags", "get_packages", "get_crash_sites",
            "get_issue_description", "get_annotation_sections", "append_annotation_section",
            "add_text_note", "add_log_note", "add_image_note", "update_note_block", "move_note_block",
            "delete_note_block", "export_analysis", "export_filtered_log", "save_annotations", "load_annotations",
            "list_filter_presets", "apply_filter_preset", "merge_tabs", "start_tailing", "stop_tailing", "resolve_log_source",
            "get_project_info", "set_highlighters", "reindex_sources", "add_manual_collapse", "add_sequence",
            "save_filter_preset", "search_similar_cases", "get_case", "set_case_metadata", "reindex_cases",
            "get_video_frame", "get_follow_diagnostics",
        )
        assertEquals(expected, operations.toolGateway.tools.map { it.name }.toSet())
        assertEquals(expected.size, operations.toolGateway.tools.size)
        assertEquals(operations.toolGateway.tools, server.toolGateway.tools)
    }

    @Test
    fun openAiDefinitionsPreserveMcpSchemas() {
        val functions = operations.openAiFunctionDefinitions().associateBy { it.name }
        operations.toolGateway.tools.forEach { tool ->
            val function = assertNotNull(functions[tool.name])
            val mcpSchema = Json.encodeToJsonElement(io.modelcontextprotocol.kotlin.sdk.types.ToolSchema.serializer(), tool.schema).jsonObject
            assertEquals(mcpSchema, function.parameters, tool.name)
            assertEquals("object", function.parameters["type"]?.toString()?.trim('"'), tool.name)
        }
    }

    @Test
    fun directGatewayReadsAndAppliesAutomaticMutation() {
        val tabs = operations.toolGateway.execute("list_tabs", emptyMap()) as List<*>
        assertTrue(tabs.single().toString().contains("sample.log"))

        val result = operations.toolGateway.execute("set_filter", mapOf("tabId" to "t1", "kwText" to "hello")) as Map<*, *>
        assertEquals(true, result["ok"])
        assertEquals(FilterMode.KEYWORD, state.tab("t1")!!.filter.mode)
        assertEquals(OpenLogToolActionPolicy.AUTOMATIC, operations.toolGateway.actionPolicy("set_filter"))
    }

    @Test
    fun addSequenceAppendsToExistingSequencesWithoutDroppingThem() {
        // set_filter's sequences field REPLACES the whole list — add_sequence is the gap that left,
        // an append that leaves an existing sequence (from a prior set_filter call) untouched.
        operations.toolGateway.execute(
            "set_filter",
            mapOf("tabId" to "t1", "sequences" to listOf(mapOf("matchText" to "boot"))),
        )
        assertEquals(1, state.tab("t1")!!.filter.sequences.size)

        val result = operations.toolGateway.execute(
            "add_sequence",
            mapOf("tabId" to "t1", "matchText" to "shutdown"),
        ) as Map<*, *>

        assertEquals(true, result["ok"])
        assertEquals(2, result["sequenceCount"])
        val sequences = state.tab("t1")!!.filter.sequences
        assertEquals(2, sequences.size)
        assertEquals("boot", sequences[0].matchText)
        assertEquals("shutdown", sequences[1].matchText)
        assertEquals(OpenLogToolActionPolicy.AUTOMATIC, operations.toolGateway.actionPolicy("add_sequence"))
    }

    @Test
    fun addSequenceRejectsBlankOrMissingMatchTextWithoutChangingSequences() {
        val blank = operations.toolGateway.execute("add_sequence", mapOf("tabId" to "t1", "matchText" to "  ")) as Map<*, *>
        val missing = operations.toolGateway.execute("add_sequence", mapOf("tabId" to "t1")) as Map<*, *>

        assertEquals(false, blank["ok"])
        assertEquals(false, missing["ok"])
        assertTrue(state.tab("t1")!!.filter.sequences.isEmpty())
    }

    @Test
    fun addSequenceRejectsUnknownTab() {
        val result = operations.toolGateway.execute(
            "add_sequence", mapOf("tabId" to "missing", "matchText" to "boot"),
        ) as Map<*, *>

        assertEquals(false, result["ok"])
        assertTrue((result["error"] as String).contains("no such tab: missing"))
    }

    @Test
    fun successiveAddSequenceCallsProduceDistinctIdsAndColorsWithIncreasingCount() {
        val first = operations.toolGateway.execute("add_sequence", mapOf("tabId" to "t1", "matchText" to "boot")) as Map<*, *>
        val second = operations.toolGateway.execute("add_sequence", mapOf("tabId" to "t1", "matchText" to "shutdown")) as Map<*, *>

        assertEquals(1, first["sequenceCount"])
        assertEquals(2, second["sequenceCount"])

        val firstSeq = first["sequence"] as Map<*, *>
        val secondSeq = second["sequence"] as Map<*, *>
        assertNotEquals(firstSeq["id"], secondSeq["id"])

        val sequences = state.tab("t1")!!.filter.sequences
        assertEquals(2, sequences.size)
        assertNotEquals(sequences[0].id, sequences[1].id)
        assertNotEquals(sequences[0].color, sequences[1].color)
    }

    @Test
    fun annotationSectionToolsReadAndAppendWithoutReplacingExistingNotes() {
        state.setPrefix("t1", "Existing context")
        state.setSuffix("t1", "- Reproduce")

        val before = operations.toolGateway.execute("get_annotation_sections", mapOf("tabId" to "t1")) as Map<*, *>
        assertEquals("Existing context", before["prefix"])
        assertEquals("- Reproduce", before["suffix"])

        val prefix = operations.toolGateway.execute(
            "append_annotation_section", mapOf("tabId" to "t1", "section" to "prefix", "text" to "Captured on Android 16"),
        ) as Map<*, *>
        val suffix = operations.toolGateway.execute(
            "append_annotation_section", mapOf("tabId" to "t1", "section" to "suffix", "text" to "- Verify the fix"),
        ) as Map<*, *>

        assertEquals(true, prefix["ok"])
        assertEquals("Existing context\n\nCaptured on Android 16", prefix["content"])
        assertEquals(true, suffix["ok"])
        assertEquals("- Reproduce\n\n- Verify the fix", suffix["content"])
        assertEquals(OpenLogToolActionPolicy.AUTOMATIC, operations.toolGateway.actionPolicy("append_annotation_section"))
    }

    @Test
    fun annotationSectionAppendRejectsInvalidInputWithoutChangingNotes() {
        state.setPrefix("t1", "Existing context")

        val invalidSection = operations.toolGateway.execute(
            "append_annotation_section", mapOf("tabId" to "t1", "section" to "body", "text" to "Ignored"),
        ) as Map<*, *>
        val blankText = operations.toolGateway.execute(
            "append_annotation_section", mapOf("tabId" to "t1", "section" to "prefix", "text" to "  "),
        ) as Map<*, *>
        val missingTab = operations.toolGateway.execute(
            "append_annotation_section", mapOf("tabId" to "missing", "section" to "suffix", "text" to "- Ignored"),
        ) as Map<*, *>

        assertTrue((invalidSection["error"] as String).contains("valid: prefix,suffix"))
        assertTrue((blankText["error"] as String).contains("blank annotation text"))
        assertTrue((missingTab["error"] as String).contains("no such tab: missing"))
        assertEquals("Existing context", state.tab("t1")!!.annotations.prefix)
    }

    @Test
    fun confirmationClassifiedOperationIsNotAutomatic() {
        assertEquals(OpenLogToolActionPolicy.CONFIRMATION_REQUIRED, operations.toolGateway.actionPolicy("close_tab"))
        assertEquals(OpenLogToolActionPolicy.CONFIRMATION_REQUIRED, operations.toolGateway.actionPolicy("export_analysis"))
    }

    @Test
    fun getProjectInfoOmitsFoldersWithNeitherDescriptionNorReadme() {
        state.updateSettings {
            it.copy(
                sourceFolders = listOf("/a", "/b"),
                sourceFolderInfo = mapOf("/a" to com.openlog.model.SourceFolderInfo(description = "")),
            )
        }

        val result = operations.toolGateway.execute("get_project_info", emptyMap()) as Map<*, *>

        assertEquals(emptyList<Any?>(), result["folders"])
    }

    @Test
    fun getProjectInfoReturnsDescriptionOnlyFolder() {
        state.updateSettings {
            it.copy(sourceFolderInfo = mapOf("/a" to com.openlog.model.SourceFolderInfo(description = "The main app.")))
        }

        val folders = operations.toolGateway.execute("get_project_info", emptyMap()) as Map<*, *>
        val folder = (folders["folders"] as List<*>).single() as Map<*, *>

        assertEquals("/a", folder["path"])
        assertEquals("The main app.", folder["description"])
        assertEquals(null, folder["readmePath"])
        assertTrue(!folder.containsKey("readmeContent"))
        assertTrue(!folder.containsKey("readmeError"))
    }

    @Test
    fun getProjectInfoReadsReadmeContentLiveFromDisk() {
        val readme = File.createTempFile("openlog-readme", ".md").apply { writeText("# Hello project") }

        state.updateSettings {
            it.copy(sourceFolderInfo = mapOf("/a" to com.openlog.model.SourceFolderInfo(readmePath = readme.absolutePath)))
        }

        val folders = operations.toolGateway.execute("get_project_info", emptyMap()) as Map<*, *>
        val folder = (folders["folders"] as List<*>).single() as Map<*, *>

        assertEquals("# Hello project", folder["readmeContent"])
        assertTrue(!folder.containsKey("readmeError"))
    }

    @Test
    fun getProjectInfoReportsReadmeErrorForMissingFile() {
        state.updateSettings {
            it.copy(
                sourceFolderInfo = mapOf(
                    "/a" to com.openlog.model.SourceFolderInfo(readmePath = "/does/not/exist/README.md"),
                ),
            )
        }

        val folders = operations.toolGateway.execute("get_project_info", emptyMap()) as Map<*, *>
        val folder = (folders["folders"] as List<*>).single() as Map<*, *>

        assertNotNull(folder["readmeError"])
        assertTrue(!folder.containsKey("readmeContent"))
    }

    // get_video_frame's happy path needs a real decoded frame (real FFmpeg natives on a real video
    // file) so it's integration-only and out of scope here — these cover the error contract, which
    // is reachable with only plain AppState/LogTab state (see plan doc Task E's test note).
    @Test
    fun getVideoFrameRejectsUnknownTab() {
        val result = operations.toolGateway.execute("get_video_frame", mapOf("tabId" to "missing", "videoMs" to 1_000)) as Map<*, *>
        assertTrue((result["error"] as String).contains("no such tab: missing"))
    }

    @Test
    fun getVideoFrameRequiresAnAttachedVideo() {
        val result = operations.toolGateway.execute("get_video_frame", mapOf("tabId" to "t1", "videoMs" to 1_000)) as Map<*, *>
        assertTrue((result["error"] as String).contains("no video attached to tab: t1"))
    }

    @Test
    fun getVideoFrameRequiresLineIdOrVideoMs() {
        attachVideoToT1()
        val result = operations.toolGateway.execute("get_video_frame", mapOf("tabId" to "t1")) as Map<*, *>
        assertTrue((result["error"] as String).contains("provide lineId or videoMs"))
    }

    @Test
    fun getVideoFrameErrorsWhenLineIdHasNoAnchorSet() {
        attachVideoToT1()
        val result = operations.toolGateway.execute("get_video_frame", mapOf("tabId" to "t1", "lineId" to 1)) as Map<*, *>
        assertTrue((result["error"] as String).contains("no anchor set"))
    }

    @Test
    fun getVideoFrameIsClassifiedAutomatic() {
        assertEquals(OpenLogToolActionPolicy.AUTOMATIC, operations.toolGateway.actionPolicy("get_video_frame"))
    }

    // ── add_image_note ───────────────────────────────────────────────────

    @Test
    fun addImageNoteFromBase64AppendsAnImageBlockWithNoSourceLine() {
        val result = operations.toolGateway.execute(
            "add_image_note",
            mapOf("tabId" to "t1", "imageBase64" to Base64.getEncoder().encodeToString(pngBytes()), "caption" to "Crash dialog"),
        ) as Map<*, *>

        assertEquals(true, result["ok"])
        val block = state.tab("t1")!!.annotations.blocks.single() as AnnBlock.Image
        assertEquals(result["blockId"], block.id)
        assertEquals("Crash dialog", block.caption)
        // Not a video frame ⇒ nothing to seek to ⇒ no "From …" line anywhere it is rendered.
        assertNull(block.videoFrame)
        assertNull(block.displayProvenance)
    }

    @Test
    fun addImageNoteFromImagePathReadsTheFileFromDisk() {
        val file = File.createTempFile("openlog-image-note", ".png").apply { writeBytes(pngBytes()) }

        val result = operations.toolGateway.execute(
            "add_image_note",
            mapOf("tabId" to "t1", "imagePath" to file.absolutePath),
        ) as Map<*, *>

        assertEquals(true, result["ok"])
        assertTrue(state.tab("t1")!!.annotations.blocks.single() is AnnBlock.Image)
    }

    @Test
    fun addImageNoteRequiresExactlyOneSource() {
        val none = operations.toolGateway.execute("add_image_note", mapOf("tabId" to "t1")) as Map<*, *>
        val both = operations.toolGateway.execute(
            "add_image_note",
            mapOf("tabId" to "t1", "imageBase64" to Base64.getEncoder().encodeToString(pngBytes()), "videoMs" to 1_000),
        ) as Map<*, *>

        // Silently preferring one source over another would leave a caller that passed two
        // believing the wrong image had been used.
        assertTrue((none["error"] as String).contains("exactly one"))
        assertTrue((both["error"] as String).contains("exactly one"))
        assertTrue(state.tab("t1")!!.annotations.blocks.isEmpty())
    }

    @Test
    fun addImageNoteRejectsBytesThatAreNotAnImage() {
        val result = operations.toolGateway.execute(
            "add_image_note",
            mapOf("tabId" to "t1", "imageBase64" to Base64.getEncoder().encodeToString(byteArrayOf(1, 2, 3))),
        ) as Map<*, *>

        assertNotNull(result["error"])
        assertTrue(state.tab("t1")!!.annotations.blocks.isEmpty())
    }

    @Test
    fun addImageNoteWithVideoMsNeedsAnAttachedVideo() {
        val result = operations.toolGateway.execute(
            "add_image_note",
            mapOf("tabId" to "t1", "videoMs" to 1_000),
        ) as Map<*, *>

        assertTrue((result["error"] as String).contains("no video attached"))
    }

    // Smallest thing ImageIO will both write and read back, so AnnotationManager's
    // downscaleAndEncodeJpeg accepts it rather than rejecting the block as undecodable.
    private fun pngBytes(): ByteArray = ByteArrayOutputStream().use { out ->
        ImageIO.write(BufferedImage(4, 4, BufferedImage.TYPE_INT_RGB), "png", out)
        out.toByteArray()
    }

    private fun attachVideoToT1() {
        state.tabs = state.tabs.map { t ->
            if (t.id == "t1") t.copy(attachedVideo = VideoAttachment(path = "/tmp/repro.mp4", sourceLabel = "/tmp/repro.mp4")) else t
        }
    }
}
