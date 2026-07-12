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
            "get_issue_description", "add_text_note", "add_log_note", "update_note_block", "move_note_block",
            "delete_note_block", "export_analysis", "export_filtered_log", "save_annotations", "load_annotations",
            "list_filter_presets", "apply_filter_preset", "merge_tabs", "start_tailing", "stop_tailing", "resolve_log_source",
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
    fun confirmationClassifiedOperationIsNotAutomatic() {
        assertEquals(OpenLogToolActionPolicy.CONFIRMATION_REQUIRED, operations.toolGateway.actionPolicy("close_tab"))
        assertEquals(OpenLogToolActionPolicy.CONFIRMATION_REQUIRED, operations.toolGateway.actionPolicy("export_analysis"))
    }
}
