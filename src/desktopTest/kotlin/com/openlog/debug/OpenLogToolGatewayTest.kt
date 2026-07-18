package com.openlog.debug

import com.openlog.model.FilterMode
import com.openlog.model.LogEntry
import com.openlog.model.LogLevel
import com.openlog.ui.AppState
import com.openlog.ui.mkTab
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import java.io.File
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
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
            "add_text_note", "add_log_note", "update_note_block", "move_note_block",
            "delete_note_block", "export_analysis", "export_filtered_log", "save_annotations", "load_annotations",
            "list_filter_presets", "apply_filter_preset", "merge_tabs", "start_tailing", "stop_tailing", "resolve_log_source",
            "get_project_info", "set_highlighters", "reindex_sources", "add_manual_collapse", "add_sequence",
            "save_filter_preset",
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
}
