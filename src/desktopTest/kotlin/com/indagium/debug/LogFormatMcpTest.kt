package com.indagium.debug

import com.indagium.model.CapabilityAvailability
import com.indagium.model.LogCapability
import com.indagium.model.LogCapabilityStatus
import com.indagium.model.LogEntry
import com.indagium.model.LogFieldRole
import com.indagium.model.LogFormatAttribute
import com.indagium.model.LogFormatDetection
import com.indagium.model.LogFormatEngine
import com.indagium.model.LogFormatProfile
import com.indagium.model.LogLevel
import com.indagium.model.LogProfileValueType
import com.indagium.model.LogRecordMetadata
import com.indagium.ui.AppState
import com.indagium.ui.mkTab
import com.indagium.utils.decodeLogFormatProfiles
import com.indagium.utils.logFormatProfileJson
import kotlinx.coroutines.runBlocking
import java.io.File
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class LogFormatMcpTest {
    private val state = AppState(
        autosaveFile = File.createTempFile("log-format-mcp", ".cache"),
        notesDir = kotlin.io.path.createTempDirectory("log-format-mcp-notes").toFile(),
    )
    private val operations = IndagiumToolOperations(state)

    @AfterTest
    fun tearDown() = state.close()

    @Test
    fun catalogExposesProfileToolsAndSchemas() {
        val names = operations.toolGateway.tools.map { it.name }
        assertTrue(names.containsAll(listOf(
            "list_log_format_profiles", "get_log_format_profile", "preview_log_format_profile",
            "import_log_format_profiles", "export_log_format_profiles", "reparse_tab_with_log_format_profile",
        )))
        assertEquals(MCP_TOOLS, operations.toolGateway.tools)
        assertEquals(names, operations.openAiFunctionDefinitions().map { it.name })
        val import = operations.toolGateway.tools.single { it.name == "import_log_format_profiles" }
        val properties = assertNotNull(import.schema.properties)
        assertTrue(properties.containsKey("json"))
        assertTrue(properties.containsKey("conflictPolicy"))
    }

    @Test
    fun profileToolMutationPoliciesKeepExistingApprovalRules() {
        assertEquals(IndagiumToolActionPolicy.AUTOMATIC, operations.toolGateway.actionPolicy("list_log_format_profiles"))
        assertEquals(IndagiumToolActionPolicy.AUTOMATIC, operations.toolGateway.actionPolicy("preview_log_format_profile"))
        assertEquals(IndagiumToolActionPolicy.CONFIRMATION_REQUIRED, operations.toolGateway.actionPolicy("import_log_format_profiles"))
        assertEquals(IndagiumToolActionPolicy.CONFIRMATION_REQUIRED, operations.toolGateway.actionPolicy("export_log_format_profiles"))
        assertEquals(IndagiumToolActionPolicy.CONFIRMATION_REQUIRED, operations.toolGateway.actionPolicy("reparse_tab_with_log_format_profile"))
        assertEquals(IndagiumToolActionPolicy.CONFIRMATION_REQUIRED, operations.toolGateway.actionPolicy("open_log_file"))
        assertEquals(IndagiumToolActionPolicy.AUTOMATIC, operations.toolGateway.actionPolicy("set_filter"))
    }

    @Test
    fun profileImportConflictPolicyPreviewAndExportAreValidated() {
        val original = LogFormatProfile(
            id = "mcp-json-profile",
            name = "MCP JSON",
            description = "Profile imported by MCP test",
            engine = LogFormatEngine.JSON_LINES,
            attributes = listOf(LogFormatAttribute("traceId", "Trace ID", "trace", LogProfileValueType.TEXT)),
        )
        val imported = executeMap("import_log_format_profiles", mapOf(
            "json" to logFormatProfileJson(original),
            "conflictPolicy" to "RENAME",
        ))
        assertEquals(true, imported["ok"])
        assertEquals(1, imported["importedCount"])

        val renamed = executeMap("import_log_format_profiles", mapOf(
            "json" to logFormatProfileJson(original.copy(name = "MCP JSON renamed")),
            "conflictPolicy" to "RENAME",
        ))

        @Suppress("UNCHECKED_CAST")
        val renamedProfiles = renamed["profiles"] as List<Map<String, Any?>>
        assertTrue(renamedProfiles.single().getValue("id") != original.id)

        val updated = executeMap("import_log_format_profiles", mapOf(
            "json" to logFormatProfileJson(original.copy(name = "MCP JSON updated")),
            "conflictPolicy" to "UPDATE",
        ))

        @Suppress("UNCHECKED_CAST")
        val updatedProfile = (updated["profiles"] as List<Map<String, Any?>>).single()
        assertEquals(original.id, updatedProfile["id"])
        assertEquals(2, updatedProfile["revision"])

        val preview = executeMap("preview_log_format_profile", mapOf(
            "profileId" to original.id,
            "sample" to """{"timestamp":"2026-05-04T12:30:00Z","level":"MYSTERY","message":"hello","trace":"abc"}""",
            "limit" to 1,
        ))

        @Suppress("UNCHECKED_CAST")
        val previewRow = (preview["rows"] as List<Map<String, Any?>>).single()
        assertEquals("MYSTERY", previewRow["level"])
        assertEquals("MYSTERY", previewRow["originalSeverity"])
        assertEquals(false, previewRow["severityKnown"])

        @Suppress("UNCHECKED_CAST")
        val attributes = previewRow["customAttributes"] as Map<String, String>
        assertEquals("abc", attributes["traceId"])
        assertNotNull(previewRow["canonicalTimestamp"])

        val output = File.createTempFile("log-format-mcp-export", ".json")
        try {
            val exported = executeMap(
                "export_log_format_profiles",
                mapOf("profileIds" to listOf(original.id), "path" to output.absolutePath),
            )
            val responseJson = exported["json"] as String
            assertEquals("mcp-json-profile", decodeLogFormatProfiles(responseJson).single().id)
            assertEquals(responseJson, output.readText())
        } finally {
            output.delete()
        }
    }

    @Test
    fun rowProjectionIncludesProfileMetadataWithoutChangingLegacyRows() = runBlocking {
        val profile = LogFormatProfile(
            id = "mcp-row-profile",
            name = "MCP row profile",
            description = "Row projection fixture",
            engine = LogFormatEngine.PLAIN_TEXT,
            attributes = listOf(LogFormatAttribute("traceId", "Trace ID", "trace", LogProfileValueType.TEXT)),
        )
        assertTrue(state.saveLogFormatProfile(profile).isSuccess)
        val rows = listOf(
            LogEntry(1, "", LogLevel.V, "", "first"),
            LogEntry(
                2,
                "12:00:00",
                LogLevel.V,
                "service",
                "second",
                recordMetadata = LogRecordMetadata(
                    profileFields = mapOf("traceId" to "trace-2"),
                    originalSeverity = "MYSTERY",
                    severityKnown = false,
                    rawRecord = "source record",
                    sourceStartLine = 7,
                    sourceEndLine = 8,
                    processIdentity = "worker-1",
                    threadIdentity = "thread-A",
                ),
            ),
            LogEntry(3, "", LogLevel.I, "", "third"),
        )
        val unavailable = LogCapabilityStatus(CapabilityAvailability.UNAVAILABLE, "No timestamps were parsed.")
        state.tabs = listOf(
            mkTab("profile-tab", "sample.log", rows).copy(
                logFormatProfileId = profile.id,
                logFormatProfileSnapshot = logFormatProfileJson(profile),
                logFormatDiagnostics = listOf("fixture diagnostic"),
                logCapabilities = mapOf(LogCapability.TIMELINE to unavailable),
                profileFieldNames = setOf("traceId"),
            ),
            mkTab("legacy-tab", "legacy.log", listOf(LogEntry(1, "10:00:00.000", LogLevel.I, "App", "legacy"))),
        )

        @Suppress("UNCHECKED_CAST")
        val tabs = operations.toolGateway.execute("list_tabs", emptyMap()) as List<Map<String, Any?>>
        val profileTab = tabs.single { it["id"] == "profile-tab" }
        assertEquals(profile.id, profileTab["logFormatProfileId"])
        assertEquals(1, profileTab["logFormatProfileRevision"])
        assertTrue(profileTab.containsKey("logFormatProfile"))
        assertEquals(listOf("fixture diagnostic"), profileTab["logFormatDiagnostics"])

        @Suppress("UNCHECKED_CAST")
        val fields = profileTab["availableFields"] as List<Map<String, Any?>>
        assertTrue(fields.any { it["name"] == "traceId" })

        @Suppress("UNCHECKED_CAST")
        val caps = profileTab["capabilities"] as Map<String, Map<String, String>>
        assertEquals("UNAVAILABLE", caps.getValue("TIMELINE").getValue("availability"))
        assertEquals("No timestamps were parsed.", caps.getValue("TIMELINE").getValue("reason"))

        val projectedFields = listOf(
            "level", "originalSeverity", "severityKnown", "customAttributes", "rawRecord",
            "sourceStartLine", "sourceEndLine", "processIdentity", "threadIdentity",
        )

        @Suppress("UNCHECKED_CAST")
        val visible = operations.toolGateway.executeSuspending(
            "get_visible_lines",
            mapOf("tabId" to "profile-tab", "offset" to 1, "limit" to 1, "fields" to projectedFields),
        ) as Map<String, Any?>

        @Suppress("UNCHECKED_CAST")
        val items = visible.getValue("items") as List<Map<String, Any?>>
        val item = items.single()
        assertEquals(2, item["id"])
        assertEquals("MYSTERY", item["level"])
        assertEquals("MYSTERY", item["originalSeverity"])
        assertEquals(false, item["severityKnown"])
        assertEquals("source record", item["rawRecord"])
        assertEquals(7L, item["sourceStartLine"])
        assertEquals(8L, item["sourceEndLine"])
        assertEquals("worker-1", item["processIdentity"])
        assertEquals("thread-A", item["threadIdentity"])

        @Suppress("UNCHECKED_CAST")
        val custom = item["customAttributes"] as Map<String, String>
        assertEquals("trace-2", custom["traceId"])
        assertFalse("msg" in item)
        assertEquals(3, visible["totalCount"])

        @Suppress("UNCHECKED_CAST")
        val compact = operations.toolGateway.executeSuspending(
            "get_visible_lines", mapOf("tabId" to "profile-tab", "limit" to 1, "compact" to true),
        ) as Map<String, Any?>

        @Suppress("UNCHECKED_CAST")
        val compactItem = (compact.getValue("items") as List<Map<String, Any?>>).single()
        assertFalse("pid" in compactItem)
        assertFalse("tid" in compactItem)
        assertFalse("indent" in compactItem)

        @Suppress("UNCHECKED_CAST")
        val legacy = operations.toolGateway.executeSuspending(
            "get_visible_lines", mapOf("tabId" to "legacy-tab", "limit" to 1),
        ) as Map<*, *>

        @Suppress("UNCHECKED_CAST")
        val legacyItem = (legacy["items"] as List<Map<String, Any?>>).single()
        assertEquals("I", legacyItem["level"])
        assertNull(legacyItem["originalSeverity"])
        assertFalse(legacy.containsKey("logFormatProfile"))
    }

    @Test
    fun explicitProfileOpenAndReparseReturnIndependentTabIds() = runBlocking {
        val profile = LogFormatProfile(
            id = "mcp-open-profile",
            name = "MCP open profile",
            description = "Explicit profile open fixture",
            engine = LogFormatEngine.PLAIN_TEXT,
        )
        assertTrue(state.saveLogFormatProfile(profile).isSuccess)
        val source = File.createTempFile("log-format-mcp-open", ".log")
        val splitDirectory = kotlin.io.path.createTempDirectory("log-format-mcp-parts").toFile()
        try {
            source.writeText("first plain record\nsecond plain record\n")
            val opened = executeMap("open_log_file", mapOf("path" to source.absolutePath, "profileId" to profile.id))
            val originalId = opened["tabId"] as String
            val original = assertNotNull(state.tab(originalId))
            assertEquals(profile.id, original.logFormatProfileId)
            assertEquals(2, original.logData.size)

            @Suppress("UNCHECKED_CAST")
            val reparsed = operations.toolGateway.executeSuspending(
                "reparse_tab_with_log_format_profile",
                mapOf("tabId" to originalId, "profileId" to profile.id),
            ) as Map<String, Any?>
            val newId = reparsed["tabId"] as String
            assertTrue(newId != originalId)
            assertNotNull(state.tab(originalId))
            assertEquals(profile.id, state.tab(newId)?.logFormatProfileId)
            assertEquals(2, state.tab(newId)?.logData?.size)

            @Suppress("UNCHECKED_CAST")
            val split = executeMap("split_log_file", mapOf(
                "path" to source.absolutePath,
                "destinationDir" to splitDirectory.absolutePath,
                "partCount" to 2,
                "profileId" to profile.id,
            ))

            @Suppress("UNCHECKED_CAST")
            val splitTabs = split["tabs"] as List<Map<String, Any?>>
            assertEquals(2, splitTabs.size)
            assertTrue(splitTabs.all { it["logFormatProfileId"] == profile.id })
        } finally {
            source.delete()
            splitDirectory.listFiles()?.forEach { it.delete() }
            splitDirectory.delete()
        }
    }

    @Test
    fun splitUsesTheOriginalProfileForCsvAndMultilineRegex() {
        val csv = LogFormatProfile(
            id = "mcp-split-csv",
            name = "MCP split CSV",
            engine = LogFormatEngine.CSV,
            detection = LogFormatDetection(extensions = listOf("csv"), alwaysUseForExtensions = true),
            fieldMappings = mapOf(LogFieldRole.MESSAGE to "message"),
        )
        val regex = LogFormatProfile(
            id = "mcp-split-regex",
            name = "MCP split multiline regex",
            engine = LogFormatEngine.REGEX,
            regexPattern = "^(\\d+): (.*)$",
            multilineStartPattern = "^\\d+: ",
            fieldMappings = mapOf(LogFieldRole.MESSAGE to "2"),
        )
        assertTrue(state.saveLogFormatProfile(csv).isSuccess)
        assertTrue(state.saveLogFormatProfile(regex).isSuccess)
        val directory = kotlin.io.path.createTempDirectory("log-format-mcp-record-split").toFile()
        try {
            val csvFile = File(directory, "records.csv").apply { writeText("time,message\n1,\"first\n  continuation\"\n2,second\n") }
            val regexFile = File(directory, "records.log").apply { writeText("1: first\n  continuation\n2: second\n") }
            for ((file, profile) in listOf(csvFile to csv, regexFile to regex)) {
                val args = mapOf("path" to file.absolutePath, "destinationDir" to directory.absolutePath, "partCount" to 2)
                val requests = if (profile == csv) listOf(args, args + ("profileId" to profile.id)) else listOf(args + ("profileId" to profile.id))
                for (request in requests) {
                    val result = executeMap("split_log_file", request)
                    assertEquals(true, result["ok"], result.toString())
                    @Suppress("UNCHECKED_CAST")
                    val tabs = result["tabs"] as List<Map<String, Any?>>
                    assertEquals(2, tabs.size)
                    assertTrue(tabs.all { it["logFormatProfileId"] == profile.id })
                    val records = tabs.flatMap { assertNotNull(state.tab(it["tabId"] as String)).logData }
                    assertEquals(2, records.size)
                    assertTrue(records.first().rawRecord.orEmpty().contains("\n  continuation"))
                }
            }
        } finally {
            directory.deleteRecursively()
        }
    }

    @Test
    fun automaticMetadataJsonSplitIsRefusedBeforeWritingParts() {
        val directory = kotlin.io.path.createTempDirectory("log-format-mcp-json-split").toFile()
        try {
            val source = File(directory, "capture.json").apply {
                writeText(
                    """
                    {"Group":{
                      "A":[{"metadata":{"identity":"one","system_time":1000000}}],
                      "B":[{"metadata":{"identity":"two","system_time":2000000}}]
                    }}
                    """.trimIndent(),
                )
            }
            val destination = File(directory, "parts")
            val opened = executeMap("open_log_file", mapOf("path" to source.absolutePath))
            assertEquals("metadata-json", opened["logFormatProfileId"])
            val result = executeMap(
                "split_log_file",
                mapOf("path" to source.absolutePath, "destinationDir" to destination.absolutePath, "partCount" to 2),
            )
            assertEquals(false, result["ok"])
            assertEquals(LogCapability.RECORD_SPLIT.name, result["capability"])
            assertTrue(result["error"].toString().contains("preserve its JSON sections"))
            assertFalse(destination.exists())
        } finally {
            directory.deleteRecursively()
        }
    }

    @Test
    fun explicitOpenDoesNotReuseTabPinnedToOlderRevisionOfSameProfileId() = runBlocking {
        val profile = LogFormatProfile(
            id = "mcp-revision-profile",
            name = "MCP revision profile",
            description = "Revised profile should get its own parsed tab.",
            engine = LogFormatEngine.JSON_LINES,
            attributes = listOf(LogFormatAttribute("request", "Request", "trace", LogProfileValueType.TEXT)),
        )
        assertTrue(state.saveLogFormatProfile(profile).isSuccess)
        val source = File.createTempFile("log-format-mcp-revision", ".jsonl")
        try {
            source.writeText("""{"timestamp":"2026-05-04T12:30:00Z","level":"INFO","message":"hello","trace":"trace-a","request":"req-a"}""")
            val first = executeMap("open_log_file", mapOf("path" to source.absolutePath, "profileId" to profile.id))
            val firstId = first.getValue("tabId") as String
            assertEquals("trace-a", state.tab(firstId)?.logData?.single()?.profileFields?.get("request"))

            val revised = state.saveLogFormatProfile(
                profile.copy(name = "MCP revision profile v2", attributes = profile.attributes.map { it.copy(source = "request") }),
            ).getOrThrow()
            assertTrue(revised.revision > profile.revision)
            val second = executeMap("open_log_file", mapOf("path" to source.absolutePath, "profileId" to profile.id))
            val secondId = second.getValue("tabId") as String

            assertTrue(secondId != firstId)
            assertEquals(revised.revision, second["logFormatProfileRevision"])
            assertEquals("req-a", state.tab(secondId)?.logData?.single()?.profileFields?.get("request"))
            assertEquals("trace-a", state.tab(firstId)?.logData?.single()?.profileFields?.get("request"))
        } finally {
            source.delete()
        }
    }

    @Suppress("UNCHECKED_CAST")
    private fun executeMap(name: String, arguments: Map<String, Any?>): Map<String, Any?> =
        operations.toolGateway.execute(name, arguments) as Map<String, Any?>
}
