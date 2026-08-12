package com.indagium.debug

import androidx.compose.ui.graphics.Color
import com.indagium.diagram.MAX_SOURCE_INTERACTIONS_PER_ENTRY
import com.indagium.model.AnnBlock
import com.indagium.model.FilterMode
import com.indagium.model.LogEntry
import com.indagium.model.LogLevel
import com.indagium.model.SequenceDef
import com.indagium.model.VideoAttachment
import com.indagium.source.LogCallSite
import com.indagium.source.SourceDirectCall
import com.indagium.source.SourceIndex
import com.indagium.ui.AppState
import com.indagium.ui.mkTab
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

class IndagiumToolGatewayTest {
    private lateinit var state: AppState
    private lateinit var server: ControlServer
    private lateinit var operations: IndagiumToolOperations

    @BeforeTest
    fun setUp() {
        // notesDir isolated from AppState()'s default (shared across this whole gradle test run —
        // see build.gradle.kts' testHomeDir sandboxing comment): upAnn's overwrite gate now defers
        // a mutation instead of committing it whenever its export target already exists on disk
        // (AppState.upAnn/PendingNoteOverwrite). Every test in this class shares this one "t1"/
        // "sample.log" tab via setUp(), so if it used the default notes dir, another test ELSEWHERE
        // in the suite that already exported the same default "sample_analysis.md" there would make
        // add_text_note/add_log_note/add_image_note etc. non-deterministically defer instead of
        // commit, purely from suite run order — nothing this class actually tests.
        state = AppState(
            autosaveFile = File.createTempFile("openlog-gateway-test", ".cache"),
            notesDir = kotlin.io.path.createTempDirectory("openlog-gateway-notes").toFile(),
        )
        state.tabs = listOf(mkTab("t1", "sample.log", listOf(LogEntry(1, "10:00:00.000", LogLevel.I, "App", "hello"))))
        operations = IndagiumToolOperations(state)
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
            "get_filter", "get_sequence_summary", "set_filter", "get_visible_lines", "get_line_context", "select_lines", "get_selection",
            "toggle_group", "expand_all", "collapse_all", "get_tags", "get_packages", "get_log_composition", "get_crash_sites",
            "get_issue_description", "get_annotation_sections", "get_annotation_blocks", "append_annotation_section", "set_annotation_section",
            "add_text_note", "add_log_note", "add_image_note", "update_note_block", "move_note_block",
            "delete_note_block", "clear_all_notes", "export_analysis", "export_filtered_log", "save_annotations", "load_annotations",
            "list_filter_presets", "apply_filter_preset", "merge_tabs", "start_tailing", "stop_tailing", "resolve_log_source",
            "get_source_file", "list_source_declarations", "get_source_declarations",
            "get_project_info", "set_highlighters", "register_source_folder", "reindex_sources", "add_manual_collapse", "add_sequence",
            "save_filter_preset", "search_similar_cases", "get_case",
            "build_sequence_diagram", "set_case_metadata", "reindex_cases",
            "get_video_frame", "get_follow_diagnostics",
        )
        assertEquals(expected, operations.toolGateway.tools.map { it.name }.toSet())
        assertEquals(expected.size, operations.toolGateway.tools.size)
        assertEquals(operations.toolGateway.tools, server.toolGateway.tools)
    }

    @Test
    fun availableMethodsDocumentEveryCatalogTool() {
        val documentation = File("docs/mcp/AVAILABLE_METHODS.md").readText()
        operations.toolGateway.tools.forEach { tool ->
            assertTrue(documentation.contains("`${tool.name}`"), "AVAILABLE_METHODS.md is missing ${tool.name}")
        }
    }

    @Test
    fun sequenceSummaryUsesRawLogAndCachesPaginatedOccurrenceDetails() {
        state.tabs = listOf(
            mkTab(
                "t1", "sequences.log", listOf(
                    LogEntry(1, "10:00:00.000", LogLevel.I, "App", "BEGIN"),
                    LogEntry(2, "10:00:01.000", LogLevel.I, "App", "inside"),
                    LogEntry(3, "10:00:02.000", LogLevel.I, "App", "END"),
                    LogEntry(4, "10:00:03.000", LogLevel.I, "App", "BEGIN"),
                ),
            ),
        )
        state.upFlt("t1") {
            it.copy(
                seqOn = false,
                sequences = listOf(
                    SequenceDef(
                        id = "request", matchText = "BEGIN", priority = 1, color = Color.Cyan,
                        endMatchText = "END",
                    ),
                ),
            )
        }

        val summary = operations.toolGateway.execute("get_sequence_summary", mapOf("tabId" to "t1")) as Map<*, *>
        val definitions = summary["sequences"] as List<*>
        assertEquals(2, (definitions.single() as Map<*, *>)["occurrenceCount"])
        assertEquals(false, summary["cacheHit"])

        val details = operations.toolGateway.execute(
            "get_sequence_summary", mapOf("tabId" to "t1", "sequenceId" to "request", "limit" to 1),
        ) as Map<*, *>
        val occurrence = (details["occurrences"] as List<*>).single() as Map<*, *>
        assertEquals(true, details["cacheHit"])
        assertEquals(1, occurrence["startLineId"])
        assertEquals(3, occurrence["endLineId"])
        assertEquals(3, occurrence["lineCount"])
        assertEquals(0, occurrence["nestingDepth"])
        assertEquals("end_match", occurrence["endReason"])
    }

    @Test
    fun sequenceSummaryReportsNestedFallbackRowsPaginationAndUnknownIds() {
        state.tabs = listOf(
            mkTab(
                "t1", "nested-sequences.log", listOf(
                    LogEntry(1, "10:00:00.000", LogLevel.I, "Outer", "outer begin"),
                    LogEntry(2, "10:00:01.000", LogLevel.I, "Inner", "inner begin"),
                    LogEntry(3, "10:00:02.000", LogLevel.I, "Inner", "inner end"),
                    LogEntry(4, "10:00:03.000", LogLevel.I, "Outer", "outer end"),
                    LogEntry(5, "10:00:04.000", LogLevel.I, "Inner", "inner begin"),
                    LogEntry(6, "10:00:05.000", LogLevel.I, "Outer", "outer begin"),
                    LogEntry(7, "10:00:06.000", LogLevel.I, "App", "after"),
                ),
            ),
        )
        state.upFlt("t1") {
            it.copy(
                sequences = listOf(
                    SequenceDef("outer", "outer begin", priority = 1, color = Color.Red, tag = "Outer", endMatchText = "outer end", endTag = "Outer"),
                    SequenceDef("inner", "inner begin", priority = 2, color = Color.Blue, tag = "Inner", endMatchText = "inner end", endTag = "Inner"),
                ),
            )
        }

        val firstPage = operations.toolGateway.execute(
            "get_sequence_summary", mapOf("tabId" to "t1", "sequenceId" to "inner", "offset" to 0, "limit" to 1),
        ) as Map<*, *>
        val nested = (firstPage["occurrences"] as List<*>).single() as Map<*, *>
        assertEquals(2, firstPage["totalCount"])
        assertEquals("sg_inner_2", nested["gid"])
        assertEquals(2, nested["startRowNumber"])
        assertEquals(3, nested["endRowNumber"])
        assertEquals(1, nested["nestingDepth"])

        val secondPage = operations.toolGateway.execute(
            "get_sequence_summary", mapOf("tabId" to "t1", "sequenceId" to "inner", "offset" to 1, "limit" to 1),
        ) as Map<*, *>
        val fallback = (secondPage["occurrences"] as List<*>).single() as Map<*, *>
        assertEquals(5, fallback["startRowNumber"])
        assertEquals(5, fallback["endRowNumber"])
        assertEquals("next_sequence_start", fallback["endReason"])

        val unknown = operations.toolGateway.execute(
            "get_sequence_summary", mapOf("tabId" to "t1", "sequenceId" to "missing"),
        ) as Map<*, *>
        assertTrue((unknown["error"] as String).contains("no enabled sequence"))
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
        assertEquals(IndagiumToolActionPolicy.AUTOMATIC, operations.toolGateway.actionPolicy("set_filter"))
    }

    @Test
    fun sourceNavigationReadsRegisteredFilesAndExactClassMembers() {
        val dir = kotlin.io.path.createTempDirectory("openlog-source-navigation").toFile()
        val source = File(dir, "Feature.kt").apply {
            writeText(
                """
                package demo

                class Feature {
                    val state = 1
                    fun run(id: String) {
                        Log.d("Feature", "run ${'$'}id")
                    }
                }
                """.trimIndent(),
            )
        }
        state.updateSettings { it.copy(sourceFolders = listOf(dir.absolutePath)) }

        val page = operations.toolGateway.execute(
            "get_source_file", mapOf("filePath" to source.absolutePath, "lineCount" to 2),
        ) as Map<*, *>
        assertEquals(1, page["startLine"])
        assertEquals(2, page["endLine"])
        assertEquals(3, page["nextStartLine"])

        val top = operations.toolGateway.execute(
            "list_source_declarations", mapOf("filePath" to source.absolutePath),
        ) as Map<*, *>
        val feature = (top["declarations"] as List<*>).single() as Map<*, *>
        assertEquals("class", feature["kind"])
        val memberList = operations.toolGateway.execute(
            "list_source_declarations", mapOf("filePath" to source.absolutePath, "parentId" to feature["id"]),
        ) as Map<*, *>
        val members = memberList["declarations"] as List<*>
        assertEquals(setOf("state", "run"), members.map { (it as Map<*, *>)["name"] }.toSet())
        val run = members.map { it as Map<*, *> }.single { it["name"] == "run" }
        val exact = operations.toolGateway.execute(
            "get_source_declarations",
            mapOf("filePath" to source.absolutePath, "declarationIds" to listOf(run["id"]), "revision" to top["revision"]),
        ) as Map<*, *>
        assertTrue((((exact["declarations"] as List<*>).single() as Map<*, *>)["code"] as String).contains("Log.d"))
    }

    @Test
    fun sourceNavigationRejectsUnregisteredAndStaleRequests() {
        val dir = kotlin.io.path.createTempDirectory("openlog-source-navigation-security").toFile()
        val source = File(dir, "Feature.kt").apply { writeText("class Feature { fun run() = Unit }") }
        val outside = File(dir.parentFile, "outside.kt").apply { writeText("class Outside") }
        state.updateSettings { it.copy(sourceFolders = listOf(dir.absolutePath)) }

        val denied = operations.toolGateway.execute("get_source_file", mapOf("filePath" to outside.absolutePath)) as Map<*, *>
        assertTrue((denied["error"] as String).contains("outside registered"))
        val symlink = File(dir, "linked-outside.kt")
        java.nio.file.Files.createSymbolicLink(symlink.toPath(), outside.toPath())
        val symlinkDenied = operations.toolGateway.execute("get_source_file", mapOf("filePath" to symlink.absolutePath)) as Map<*, *>
        assertTrue((symlinkDenied["error"] as String).contains("outside registered"))

        val unknownParent = operations.toolGateway.execute(
            "list_source_declarations", mapOf("filePath" to source.absolutePath, "parentId" to "unknown"),
        ) as Map<*, *>
        assertTrue((unknownParent["error"] as String).contains("unknown declaration id"))

        val outline = operations.toolGateway.execute(
            "list_source_declarations", mapOf("filePath" to source.absolutePath),
        ) as Map<*, *>
        val id = ((outline["declarations"] as List<*>).single() as Map<*, *>)["id"]
        source.writeText("class Feature { fun changed() = Unit }")
        val stale = operations.toolGateway.execute(
            "get_source_declarations",
            mapOf("filePath" to source.absolutePath, "declarationIds" to listOf(id), "revision" to outline["revision"]),
        ) as Map<*, *>
        assertTrue((stale["error"] as String).contains("source file changed"))
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
        assertEquals(IndagiumToolActionPolicy.AUTOMATIC, operations.toolGateway.actionPolicy("add_sequence"))
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
        assertEquals(IndagiumToolActionPolicy.AUTOMATIC, operations.toolGateway.actionPolicy("append_annotation_section"))
    }

    @Test
    fun annotationBlocksListSafeIdentifiersAndClearAllPreservesNonNotesMetadata() {
        state.setPrefix("t1", "Reporter context")
        state.setSuffix("t1", "- Verify release")
        val textId = (operations.toolGateway.execute(
            "add_text_note", mapOf("tabId" to "t1", "text" to "Root cause summary"),
        ) as Map<*, *>)["blockId"] as String
        operations.toolGateway.execute("add_log_note", mapOf("tabId" to "t1", "lineIds" to listOf(1), "caption" to "Crash"))
        state.upAnn("t1") { tab ->
            tab.copy(annotations = tab.annotations.copy(
                issueDescription = "The app crashes after login",
                appVersion = "7.2.1",
                decisiveTags = listOf("Auth"),
                blocks = tab.annotations.blocks + AnnBlock.Image("image-1", "Screenshot", "pasted", "png", byteArrayOf(1, 2, 3)),
            ))
        }

        val listed = operations.toolGateway.execute("get_annotation_blocks", mapOf("tabId" to "t1")) as Map<*, *>
        val blocks = listed["blocks"] as List<*>
        assertEquals(3, blocks.size)
        val text = blocks.single { (it as Map<*, *>)["id"] == textId } as Map<*, *>
        assertEquals("text", text["type"])
        assertEquals("Root cause summary", text["text"])
        val image = blocks.single { (it as Map<*, *>)["id"] == "image-1" } as Map<*, *>
        assertEquals("image", image["type"])
        assertEquals(3, image["byteCount"])
        assertTrue(!image.containsKey("bytes"))

        assertEquals(IndagiumToolActionPolicy.CONFIRMATION_REQUIRED, operations.toolGateway.actionPolicy("clear_all_notes"))
        assertEquals(IndagiumToolActionPolicy.AUTOMATIC, operations.toolGateway.actionPolicy("delete_note_block"))
        val cleared = operations.toolGateway.execute("clear_all_notes", mapOf("tabId" to "t1")) as Map<*, *>
        assertEquals(true, cleared["ok"])
        assertEquals(3, cleared["removedBlockCount"])
        val annotations = state.tab("t1")!!.annotations
        assertEquals("", annotations.prefix)
        assertEquals("", annotations.suffix)
        assertTrue(annotations.blocks.isEmpty())
        assertEquals("The app crashes after login", annotations.issueDescription)
        assertEquals("7.2.1", annotations.appVersion)
        assertEquals(listOf("Auth"), annotations.decisiveTags)
    }

    @Test
    fun setAnnotationSectionReplacesAndClearsNotesWithoutRejectingBlankText() {
        state.setPrefix("t1", "Existing context")
        state.setSuffix("t1", "- Reproduce")

        val replaced = operations.toolGateway.execute(
            "set_annotation_section", mapOf("tabId" to "t1", "section" to "prefix", "text" to "Replaced heading"),
        ) as Map<*, *>
        assertEquals(true, replaced["ok"])
        assertEquals("Replaced heading", replaced["content"])
        assertEquals("Replaced heading", state.tab("t1")!!.annotations.prefix)

        val clearedByOmission = operations.toolGateway.execute(
            "set_annotation_section", mapOf("tabId" to "t1", "section" to "prefix"),
        ) as Map<*, *>
        assertEquals(true, clearedByOmission["ok"])
        assertEquals("", clearedByOmission["content"])
        assertEquals("", state.tab("t1")!!.annotations.prefix)

        val clearedByBlank = operations.toolGateway.execute(
            "set_annotation_section", mapOf("tabId" to "t1", "section" to "suffix", "text" to "   "),
        ) as Map<*, *>
        assertEquals(true, clearedByBlank["ok"])
        assertEquals("", clearedByBlank["content"])
        assertEquals("", state.tab("t1")!!.annotations.suffix)

        val invalidSection = operations.toolGateway.execute(
            "set_annotation_section", mapOf("tabId" to "t1", "section" to "body", "text" to "Ignored"),
        ) as Map<*, *>
        val missingTab = operations.toolGateway.execute(
            "set_annotation_section", mapOf("tabId" to "missing", "section" to "prefix", "text" to "Ignored"),
        ) as Map<*, *>
        assertTrue((invalidSection["error"] as String).contains("valid: prefix,suffix"))
        assertTrue((missingTab["error"] as String).contains("no such tab: missing"))
        assertEquals("", state.tab("t1")!!.annotations.prefix)

        assertEquals(IndagiumToolActionPolicy.AUTOMATIC, operations.toolGateway.actionPolicy("set_annotation_section"))
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
        assertEquals(IndagiumToolActionPolicy.CONFIRMATION_REQUIRED, operations.toolGateway.actionPolicy("close_tab"))
        assertEquals(IndagiumToolActionPolicy.CONFIRMATION_REQUIRED, operations.toolGateway.actionPolicy("export_analysis"))
    }

    @Test
    fun getProjectInfoOmitsFoldersWithNeitherDescriptionNorReadme() {
        state.updateSettings {
            it.copy(
                sourceFolders = listOf("/a", "/b"),
                sourceFolderInfo = mapOf("/a" to com.indagium.model.SourceFolderInfo(description = "")),
            )
        }

        val result = operations.toolGateway.execute("get_project_info", emptyMap()) as Map<*, *>

        assertEquals(emptyList<Any?>(), result["folders"])
    }

    @Test
    fun getProjectInfoReturnsDescriptionOnlyFolder() {
        state.updateSettings {
            it.copy(sourceFolderInfo = mapOf("/a" to com.indagium.model.SourceFolderInfo(description = "The main app.")))
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
            it.copy(sourceFolderInfo = mapOf("/a" to com.indagium.model.SourceFolderInfo(readmePath = readme.absolutePath)))
        }

        val folders = operations.toolGateway.execute("get_project_info", emptyMap()) as Map<*, *>
        val folder = (folders["folders"] as List<*>).single() as Map<*, *>

        assertEquals("# Hello project", folder["readmeContent"])
        assertTrue(!folder.containsKey("readmeError"))
    }

    @Test
    fun getProjectInfoCapsDescriptionBeforeReadmeAndReportsTruncation() {
        val readme = File.createTempFile("openlog-readme", ".md").apply { writeText("README-CONTENT") }
        try {
            state.updateSettings {
                it.copy(
                    sourceFolderInfo = mapOf(
                        "/z" to com.indagium.model.SourceFolderInfo(description = "Z-desc", readmePath = readme.absolutePath),
                        "/a" to com.indagium.model.SourceFolderInfo(description = "A-description", readmePath = readme.absolutePath),
                    ),
                )
            }

            val result = operations.toolGateway.execute("get_project_info", mapOf("maxContentChars" to 15)) as Map<*, *>
            val folders = result["folders"] as List<*>
            val first = folders[0] as Map<*, *>
            val second = folders[1] as Map<*, *>

            assertEquals("/a", first["path"], "Capped allocation must use deterministic path order.")
            assertEquals("A-description", first["description"])
            assertEquals("RE", first["readmeContent"], "Description receives the available content first.")
            assertEquals(true, first["readmeTruncated"])
            assertEquals("/z", second["path"])
            assertEquals("", second["description"])
            assertEquals(true, second["descriptionTruncated"])
            assertEquals(15, result["returnedContentChars"])
            assertEquals(true, result["contentTruncated"])
        } finally {
            readme.delete()
        }
    }

    @Test
    fun getProjectInfoWithoutCapKeepsLegacyFullContent() {
        val readme = File.createTempFile("openlog-readme", ".md").apply { writeText("README-CONTENT") }
        try {
            state.updateSettings {
                it.copy(sourceFolderInfo = mapOf("/a" to com.indagium.model.SourceFolderInfo(description = "Description", readmePath = readme.absolutePath)))
            }

            val result = operations.toolGateway.execute("get_project_info", emptyMap()) as Map<*, *>
            val folder = (result["folders"] as List<*>).single() as Map<*, *>

            assertEquals("Description", folder["description"])
            assertEquals("README-CONTENT", folder["readmeContent"])
            assertTrue(!folder.containsKey("descriptionTruncated"))
            assertTrue(!result.containsKey("maxContentChars"))
        } finally {
            readme.delete()
        }
    }

    @Test
    fun getProjectInfoReportsReadmeErrorForMissingFile() {
        state.updateSettings {
            it.copy(
                sourceFolderInfo = mapOf(
                    "/a" to com.indagium.model.SourceFolderInfo(readmePath = "/does/not/exist/README.md"),
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
        assertEquals(IndagiumToolActionPolicy.AUTOMATIC, operations.toolGateway.actionPolicy("get_video_frame"))
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

    @Test
    fun sequenceDiagramCreatesManualDraftWithCoverage() {
        state.tabs = listOf(
            mkTab("t1", "components.log", listOf(
                LogEntry(1, "10:00:00.000", LogLevel.I, "Ui", "tap"),
                LogEntry(2, "10:00:00.010", LogLevel.I, "Service", "request"),
                LogEntry(3, "10:00:00.020", LogLevel.I, "Noise", "ignored"),
            )),
        )
        val result = operations.toolGateway.execute(
            "build_sequence_diagram",
            mapOf(
                "tabId" to "t1",
                "components" to listOf(
                    mapOf("id" to "ui", "displayName" to "UI", "tagIds" to listOf("Ui")),
                    mapOf("id" to "svc", "displayName" to "Service", "tagIds" to listOf("Service")),
                ),
                "unmappedTagPolicy" to "hide",
                "activationPolicy" to "evidenceBacked",
            ),
        ) as Map<*, *>

        assertEquals(2, (result["coverage"] as Map<*, *>)["shownEntries"])
        assertEquals(1, (result["coverage"] as Map<*, *>)["hiddenEntries"])
        assertTrue((result["messages"] as List<*>).all { (it as Map<*, *>)["evidence"] == "manual_override" })
        assertEquals(2, ((result["manualDocument"] as Map<*, *>)["interactions"] as List<*>).size)
        assertNotNull(result["activationSpans"])
    }

    @Test
    fun sequenceDiagramKeepsLegacyTagsAndEntryExitActorsCompatible() {
        state.tabs = listOf(
            mkTab("t1", "legacy.log", listOf(
                LogEntry(1, "10:00:00.000", LogLevel.I, "A", "begin"),
                LogEntry(2, "10:00:00.010", LogLevel.I, "B", "end"),
            )),
        )
        val result = operations.toolGateway.execute(
            "build_sequence_diagram",
            mapOf("tabId" to "t1", "tags" to listOf("A", "B"), "actors" to listOf("User", "Server"),
                "entryActor" to "User", "exitActor" to "Server"),
        ) as Map<*, *>

        val participantIds = (result["participants"] as List<*>).map { (it as Map<*, *>)["id"] }
        assertTrue(participantIds.containsAll(listOf("A", "B", "User", "Server")))
    }

    @Test
    fun sequenceDiagramMcpReturnsManualDocumentAndAcceptsLegacyFields() {
        val result = operations.toolGateway.execute("build_sequence_diagram", mapOf(
            "tabId" to "t1",
            "tags" to listOf("App", "Peer"),
            "authoringMode" to "manual",
            "lifelineOrder" to listOf("App", "Peer"),
            "rules" to listOf(mapOf(
                "id" to "route", "pattern" to "to (?<peer>\\w+)",
                "fromEndpoint" to mapOf("kind" to "existing", "participantId" to "App"),
                "toEndpoint" to mapOf("kind" to "captured", "captureName" to "peer", "bindings" to listOf(
                    mapOf("capturedValue" to "Peer", "participantId" to "Peer"),
                )),
            )),
            "messageOverrides" to listOf(mapOf(
                "origin" to mapOf("entryId" to 1, "ruleId" to "route", "sourceOperationId" to "op-1"),
                "label" to "manual hello", "kind" to "async",
            )),
            "manualDocument" to mapOf(
                "interactions" to listOf(mapOf(
                    "id" to "m-1", "sourceEntryIds" to listOf(1), "fromParticipantId" to "App", "toParticipantId" to "Peer",
                    "operation" to "hello", "kind" to "async",
                )),
                "groups" to listOf(mapOf("id" to "g-1", "label" to "manual", "interactionIds" to listOf("m-1"))),
                "notes" to listOf(mapOf("id" to "n-1", "participantId" to "Peer", "afterInteractionId" to "m-1", "text" to "received")),
                "activations" to listOf(mapOf("id" to "a-1", "participantId" to "Peer", "startInteractionId" to "m-1", "endInteractionId" to "m-1")),
            ),
        )) as Map<*, *>

        assertNull(result["error"])
        assertEquals(listOf("App", "Peer"), result["lifelineOrder"])
        assertNull(result["authoringMode"])
        assertNull(result["messageOverrides"])
        assertEquals("m-1", (((result["manualDocument"] as Map<*, *>)["interactions"] as List<*>).single() as Map<*, *>)["id"])
    }

    @Test
    fun sequenceDiagramRejectsOversizedParticipantCollections() {
        fun call(extra: Map<String, Any?>): Map<*, *> = operations.toolGateway.execute(
            "build_sequence_diagram", mapOf("tabId" to "t1") + extra,
        ) as Map<*, *>

        val components = (0..64).map { mapOf("id" to "c$it", "displayName" to "C$it", "tagIds" to emptyList<String>()) }
        val actors = (0..64).map { "actor-$it" }
        val merged = (0..64).associate { "merged-$it" to listOf("tag-$it") }
        val tags = (0..128).map { "tag-$it" }

        assertTrue((call(mapOf("components" to components))["error"] as String).contains("at most 64"))
        assertTrue((call(mapOf("actors" to actors))["error"] as String).contains("at most 64"))
        assertTrue((call(mapOf("mergedTags" to merged))["error"] as String).contains("at most 64"))
        assertTrue((call(mapOf("tags" to tags))["error"] as String).contains("at most 128"))
    }

    @Test
    fun sequenceDiagramRejectsDuplicateAndCollidingParticipantIds() {
        fun error(extra: Map<String, Any?>): String = (operations.toolGateway.execute(
            "build_sequence_diagram", mapOf("tabId" to "t1") + extra,
        ) as Map<*, *>)["error"] as String

        assertTrue(error(mapOf("components" to listOf(
            mapOf("id" to "same", "tagIds" to listOf("A")),
            mapOf("id" to "same", "tagIds" to listOf("B")),
        ))).contains("duplicate component id"))
        assertTrue(error(mapOf("actors" to listOf("User", mapOf("id" to "User", "label" to "Person"))))
            .contains("duplicate actor id"))
        assertTrue(error(mapOf(
            "components" to listOf(mapOf("id" to "shared", "tagIds" to listOf("A"))),
            "actors" to listOf(mapOf("id" to "shared", "label" to "Shared")),
        )).contains("both a component and actor"))
    }

    @Test
    fun sequenceDiagramRejectsOversizedStringsAndTagMembership() {
        fun error(extra: Map<String, Any?>): String = (operations.toolGateway.execute(
            "build_sequence_diagram", mapOf("tabId" to "t1") + extra,
        ) as Map<*, *>)["error"] as String

        assertTrue(error(mapOf("title" to "x".repeat(513))).contains("title must be at most 512"))
        assertTrue(error(mapOf("components" to listOf(mapOf(
            "id" to "c", "tagIds" to (0..128).map { "tag-$it" },
        )))).contains("tagIds may contain at most 128"))
        assertTrue(error(mapOf("actors" to listOf(mapOf(
            "id" to "x".repeat(257), "label" to "Actor",
        )))).contains("id must be at most 256"))
    }

    @Test
    fun sequenceDiagramStillClampsMaxMessagesAtTheHardCap() {
        state.tabs = listOf(mkTab("t1", "many.log", (1..450).map { id ->
            LogEntry(id, "10:00:00.000", LogLevel.I, if (id % 2 == 0) "A" else "B", "message-$id")
        }))
        val result = operations.toolGateway.execute(
            "build_sequence_diagram",
            mapOf("tabId" to "t1", "tags" to listOf("A", "B"), "collapseRepeats" to false, "maxMessages" to 50_000),
        ) as Map<*, *>

        assertEquals(450, result["messageCount"], "the hard cap limits optional structure, not selected log rows")
        assertEquals(false, result["truncated"])
        assertEquals(450, (result["messages"] as List<*>).size)
    }

    @Test
    fun sequenceDiagramSourceEnrichmentDoesNotEmitEveryAmbiguousIndexedCall() {
        val directCalls = (0 until MAX_SOURCE_INTERACTIONS_PER_ENTRY * 2).map { index ->
            SourceDirectCall(
                targetFilePath = "/fixture/NetworkService.kt",
                targetOwnerType = "demo.NetworkService",
                targetMethodName = "fetch$index",
                targetMethodSignature = "fun fetch$index(): ApiResult",
                targetDeclaredReturnType = "ApiResult",
                // Keep the synthetic calls on the log statement's source line so this
                // test continues to exercise the per-entry interaction cap. Calls on
                // unrelated lines are intentionally rejected by source-site matching.
                callLine = 10,
            )
        }
        val sourceIndex = SourceIndex(
            version = 10,
            roots = listOf("/fixture"),
            sites = listOf(LogCallSite(
                filePath = "/fixture/Screen.kt",
                tag = "Screen",
                methodName = "refresh",
                methodStartLine = 1,
                methodEndLine = 20,
                callLine = 10,
                matcher = Regex.escape("refresh operation completed"),
                literalLen = 27,
                owningType = "demo.Screen",
                methodSignature = "fun refresh()",
                directCalls = directCalls,
            )),
            fileMeta = emptyMap(),
            builtAt = 1L,
        )
        operations = IndagiumToolOperations(state) { sourceIndex }
        state.tabs = listOf(mkTab("t1", "source.log", listOf(
            LogEntry(1, "10:00:00.000", LogLevel.I, "Screen", "refresh operation completed"),
        )))

        val result = operations.toolGateway.execute("build_sequence_diagram", mapOf(
            "tabId" to "t1",
            "components" to listOf(
                mapOf("id" to "screen", "displayName" to "Screen", "tagIds" to listOf("Screen")),
                mapOf("id" to "network", "displayName" to "NetworkService", "tagIds" to listOf("Network")),
            ),
            "sourceEnrichment" to true,
        )) as Map<*, *>

        val messages = result["messages"] as List<*>
        assertEquals(1, messages.size)
        assertEquals("manual_override", (messages.single() as Map<*, *>)["evidence"])
        assertNull(result["sourceEnrichment"])
    }

    @Test
    fun sequenceDiagramSourceEnrichmentReportsUnavailableIndex() {
        operations = IndagiumToolOperations(state) { null }
        state.tabs = listOf(mkTab("t1", "source.log", listOf(
            LogEntry(1, "10:00:00.000", LogLevel.I, "A", "event"),
        )))
        val result = operations.toolGateway.execute("build_sequence_diagram", mapOf(
            "tabId" to "t1",
            "components" to listOf(mapOf("id" to "a", "displayName" to "A", "tagIds" to listOf("A"))),
            "sourceEnrichment" to true,
        )) as Map<*, *>

        assertTrue((result["warnings"] as List<*>).any { it.toString().contains("no source index") })
        assertNull(result["sourceEnrichment"])
    }

    @Test
    fun sequenceDiagramSourceCacheHashesMessagesAndEvictsOldUniqueEntries() {
        val sensitiveTag = "PrivateCustomerTag"
        val sensitiveMessage = "account 123456789 unique diagnostic payload"
        val key = diagramSourceCacheKey(sensitiveTag, sensitiveMessage)

        assertEquals(key, diagramSourceCacheKey(sensitiveTag, sensitiveMessage))
        assertNotEquals(key, diagramSourceCacheKey(sensitiveTag, "$sensitiveMessage changed"))
        assertNotEquals(diagramSourceCacheKey("a", "b\u0000c"), diagramSourceCacheKey("a\u0000b", "c"))
        assertTrue(!key.contains(sensitiveTag) && !key.contains(sensitiveMessage))
        assertEquals(DIAGRAM_SOURCE_CACHE_KEY_CHARS, key.length, "SHA-256 base64url keys remain fixed-size")

        val cache = DiagramSourceLruCache<Int>(MAX_MCP_DIAGRAM_SOURCE_CACHE_ENTRIES)
        repeat(MAX_MCP_DIAGRAM_SOURCE_CACHE_ENTRIES * 3) { index -> cache["key-$index"] = index }

        assertEquals(MAX_MCP_DIAGRAM_SOURCE_CACHE_ENTRIES, cache.size)
        assertNull(cache["key-0"])
        assertEquals(
            MAX_MCP_DIAGRAM_SOURCE_CACHE_ENTRIES * 3 - 1,
            cache["key-${MAX_MCP_DIAGRAM_SOURCE_CACHE_ENTRIES * 3 - 1}"],
        )
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
