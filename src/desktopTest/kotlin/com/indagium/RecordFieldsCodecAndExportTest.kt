package com.indagium

import com.indagium.debug.IndagiumToolOperations
import com.indagium.model.AnnBlock
import com.indagium.model.AnnotationLogBlockStyle
import com.indagium.model.Annotations
import com.indagium.model.AppSettings
import com.indagium.model.LogDisplayField
import com.indagium.model.LogEntry
import com.indagium.model.LogLevel
import com.indagium.model.LogRecordMetadata
import com.indagium.ui.AppState
import com.indagium.ui.annotationsFromToken
import com.indagium.ui.annotationsToken
import com.indagium.ui.b64
import com.indagium.ui.mkTab
import com.indagium.ui.recordFieldsToken
import com.indagium.ui.requestAddRecordFields
import com.indagium.ui.resolveLogRefRowIds
import com.indagium.ui.tokenFields
import com.indagium.ui.unb64
import com.indagium.utils.RECORD_EXPORT_MAX_LINES
import com.indagium.utils.RECORD_FIELDS_MAX_JSON_CHARS
import com.indagium.utils.RecordFieldsResult
import com.indagium.utils.buildAnnotationsHtml
import com.indagium.utils.buildMd
import com.indagium.utils.joinRecordPath
import com.indagium.utils.prettyJsonLines
import com.indagium.utils.recordFieldRefs
import com.indagium.utils.withSelectedRecordSnapshot
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import java.io.File
import kotlin.io.path.createTempDirectory
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Chosen fields of a JSON record (AnnBlock.LogRef.recordFields): freezing them, the "R" token that autosave and the .ann sidecar share,
 * the Markdown / HTML export of a whole record and of chosen fields, and what the AI tools report.
 */
class RecordFieldsCodecAndExportTest {
    private val record = """{"timestamp":"2026-11-16T10:00:00Z","metadata":{"bluetooth":{"accessory_address":"AA:BB:CC","rssi":-61},""" +
        """"tags":["a","b"]},"id":7}"""

    private fun jsonRow(id: Int, json: String = record) = LogEntry(
        id, "10:00:%02d.000".format(id), LogLevel.I, "HCI", "message $id",
        recordMetadata = LogRecordMetadata(rawRecord = json, usesProfilePresentation = true),
    )

    private fun ref(
        id: String = "r1",
        fields: List<AnnBlock.RecordFieldRef>?,
        section: String? = null,
        source: String? = null,
    ) = AnnBlock.LogRef(
        id = id,
        logIds = listOf(1),
        caption = "cap | with bar",
        sourceTabId = source,
        sourceFilename = source?.let { "Untitled.json" },
        sourceEntries = listOf(jsonRow(1)),
        sourceSectionId = section,
        recordFields = fields,
    )

    private fun fields() = (recordFieldRefs(record, listOf(listOf("metadata", "bluetooth", "accessory_address"), listOf("id"))) as RecordFieldsResult.Ok).fields

    private fun notes(vararg blocks: AnnBlock) = Annotations(prefix = "p", suffix = "s", blocks = blocks.toList())

    // ── Freezing ─────────────────────────────────────────────────────────────────────────────────

    @Test
    fun theChosenSubtreesAreFrozenAsCompactJsonInTheOrderGiven() {
        val result = recordFieldRefs(
            record,
            listOf(listOf("metadata", "bluetooth"), listOf("id"), listOf("metadata", "tags", "[1]"), listOf("id"), listOf("metadata", "bluetooth", "rssi")),
        )
        val chosen = assertIs<RecordFieldsResult.Ok>(result).fields

        // "id" twice counts once, and "rssi" lies inside the chosen "bluetooth" subtree, so it is not a field of its own.
        assertEquals(
            listOf(
                listOf("metadata", "bluetooth") to """{"accessory_address":"AA:BB:CC","rssi":-61}""",
                listOf("id") to "7",
                listOf("metadata", "tags", "[1]") to "\"b\"",
            ),
            chosen.map { it.path to it.json },
        )
        assertEquals("metadata.tags[1]", joinRecordPath(listOf("metadata", "tags", "[1]")))
    }

    @Test
    fun aSelectionOverTheSizeCapIsRefusedAndAMissingFieldIsUnavailable() {
        val big = "x".repeat(RECORD_FIELDS_MAX_JSON_CHARS)
        val bigRecord = """{"a":"$big","b":"y"}"""

        assertIs<RecordFieldsResult.TooLarge>(recordFieldRefs(bigRecord, listOf(listOf("a"))))
        assertIs<RecordFieldsResult.Ok>(recordFieldRefs(bigRecord, listOf(listOf("b"))))
        assertIs<RecordFieldsResult.Unavailable>(recordFieldRefs(record, listOf(listOf("nope"))))
        assertIs<RecordFieldsResult.Unavailable>(recordFieldRefs("not json", listOf(listOf("a"))))
        assertIs<RecordFieldsResult.Unavailable>(recordFieldRefs(null, listOf(listOf("a"))))
    }

    // ── The token ────────────────────────────────────────────────────────────────────────────────

    @Test
    fun chosenFieldsSurviveTheAnnSidecarTokenWithAndWithoutASection() {
        val original = notes(
            ref("r1", fields(), section = "Tethered Bluetooth / HCI", source = "j"),
            ref("r2", fields(), section = null, source = null),
            ref("r3", null, section = "G / S"),
            ref("r4", fields() + AnnBlock.RecordFieldRef(listOf(""), "7") + AnnBlock.RecordFieldRef(listOf("", "a,b;c:d|e"), "\"v | ; : ,\"")),
        )

        val token = original.annotationsToken()
        val sanitized = original.copy(blocks = original.blocks.filterIsInstance<AnnBlock.LogRef>().map { it.withSelectedRecordSnapshot() })
        val decoded = assertNotNull(token.annotationsFromToken())

        assertEquals(sanitized.annotationsToken(), token)
        assertEquals(sanitized.blocks, decoded.blocks)
        val refs = decoded.blocks.filterIsInstance<AnnBlock.LogRef>()
        assertEquals(listOf("Tethered Bluetooth / HCI", null, "G / S", null), refs.map { it.sourceSectionId })
        assertEquals(listOf(true, true, false, true), refs.map { it.recordFields != null })
        assertEquals(listOf(""), refs[3].recordFields!![2].path)
        assertEquals(listOf("", "a,b;c:d|e"), refs[3].recordFields!!.last().path)
    }

    @Test
    fun aLegacyFieldsReferenceUsesItsFullSnapshotToCreateTheLocatorBeforeDiscardingIt() {
        val whole = ref(fields = null, section = "section")
        val wholeNote = notes(whole)
        val rowToken = wholeNote.annotationsToken().tokenFields()[2].unb64()
        val legacyRefToken = "$rowToken|${fields().recordFieldsToken().b64()}"

        val decoded = assertNotNull(tokenFields(wholeNote.prefix, wholeNote.suffix, legacyRefToken.b64()).annotationsFromToken())
        val block = assertIs<AnnBlock.LogRef>(decoded.blocks.single())
        val row = jsonRow(1)
        assertEquals(whole.copy(recordFields = fields()).withSelectedRecordSnapshot(), block)
        assertEquals(listOf(1), resolveLogRefRowIds(block, listOf(row), mapOf(1 to row)))
    }

    @Test
    fun savingSelectedFieldsKeepsTheHeaderAndFindsTheOriginalRecordAfterItMoves() {
        val dir = createTempDirectory("record-fields-sidecar").toFile()
        val app = AppState(File(dir, "state.cache"), autoExportNotes = false)
        try {
            val packet = jsonRow(1, """{"id":7,"detail":"unselected"}""").let { row ->
                row.copy(recordMetadata = row.recordMetadata!!.copy(displayColumns = listOf(LogDisplayField("detail", "Detail", "unselected"))))
            }
            app.tabs = listOf(mkTab("a", "a.json", listOf(packet)))
            app.activeTabId = "a"
            app.requestAddRecordFields("a", packet.id, listOf(listOf("id")))
            app.confirmAddAnn(assertNotNull(app.addAnnRequest), "", AnnBlock.LogRefPresentationOptions())

            val sidecar = File(dir, "a.ann")
            assertTrue(app.saveAnnotationsTo("a", sidecar))
            val saved = assertIs<AnnBlock.LogRef>(assertNotNull(sidecar.readText().annotationsFromToken()).blocks.single())
            assertEquals(listOf(AnnBlock.RecordFieldRef(listOf("id"), "7")), saved.recordFields)
            assertEquals(listOf(LogEntry(1, packet.ts, packet.level, packet.tag, "")), saved.sourceEntries)
            assertTrue(saved.presentation?.rows.orEmpty().isEmpty())

            val rows = listOf(packet.copy(recordMetadata = LogRecordMetadata(rawRecord = """{"id":7,"detail":"other"}""")), packet.copy(id = 2))
            assertEquals(listOf(2), resolveLogRefRowIds(saved, rows, rows.associateBy { it.id }))
            assertEquals(emptyList(), resolveLogRefRowIds(saved, rows.take(1), mapOf(1 to rows.first())))
        } finally {
            app.close()
            dir.deleteRecursively()
        }
    }

    @Test
    fun aReferenceWithoutFieldsEncodesExactlyAsBeforeAndLegacyTokensDecodeWithNone() {
        val plain = notes(ref("r1", null, section = "G / S"))
        val withFields = notes(ref("r1", fields(), section = "G / S"))

        assertEquals(false, plain.annotationsToken() == withFields.annotationsToken())
        assertNull(assertNotNull(plain.annotationsToken().annotationsFromToken()).blocks.filterIsInstance<AnnBlock.LogRef>().single().recordFields)
        // An empty list is not a fields reference: it writes nothing, so it reads back as none.
        val empty = assertNotNull(notes(ref("r1", emptyList())).annotationsToken().annotationsFromToken())
        assertNull(empty.blocks.filterIsInstance<AnnBlock.LogRef>().single().recordFields)
    }

    @Test
    fun aTokenHoldingMoreJsonThanTheCapReadsBackWithoutTheFields() {
        val tooMuch = listOf(AnnBlock.RecordFieldRef(listOf("a"), "\"" + "x".repeat(RECORD_FIELDS_MAX_JSON_CHARS) + "\""))

        val decoded = assertNotNull(notes(ref("r1", tooMuch)).annotationsToken().annotationsFromToken())

        assertNull(decoded.blocks.filterIsInstance<AnnBlock.LogRef>().single().recordFields)
    }

    @Test
    fun chosenFieldsSurviveAnAutosaveRoundTrip() {
        val dir = createTempDirectory("record-fields-autosave").toFile()
        val app = AppState(File(dir, "state.cache"), autoExportNotes = false)
        try {
            val source = File(dir, "j.log").apply { writeText("06-26 10:00:00.000  1  1 I HCI: one\n") }
            app.tabs = listOf(mkTab("j", "j.log", listOf(jsonRow(1), jsonRow(2))).copy(sourcePath = source.absolutePath))
            app.activeTabId = "j"
            app.upAnn("j") { it.copy(annotations = notes(ref("r1", fields(), section = null), ref("r2", fields(), section = "S"))) }
            app.autosaveNow()

            val restored = AppState(File(dir, "state.cache"), restoreOnCreate = true, autoExportNotes = false)
            try {
                val refs = restored.tab("j")!!.annotations.blocks.filterIsInstance<AnnBlock.LogRef>()
                assertEquals(listOf(null, "S"), refs.map { it.sourceSectionId })
                assertEquals(fields(), refs[0].recordFields)
                assertEquals(fields(), refs[1].recordFields)
            } finally {
                restored.close()
            }
        } finally {
            app.close()
            dir.deleteRecursively()
        }
    }

    // ── Export ───────────────────────────────────────────────────────────────────────────────────

    private fun tabWith(vararg blocks: AnnBlock) =
        mkTab("a", "a.log", listOf(jsonRow(1))).copy(annotations = notes(*blocks))

    @Test
    fun aWholeRecordIsFollowedByItsPrettyPrintedJson() {
        val settings = AppSettings(annotationLogBlockStyle = AnnotationLogBlockStyle.JIRA_CLOUD)
        val tab = tabWith(AnnBlock.LogRef("r1", listOf(1), "Pairing", sourceEntries = listOf(jsonRow(1))))

        val markdown = buildMd(tab, settings)

        assertContains(markdown, "```json\n{\n  \"timestamp\": \"2026-11-16T10:00:00Z\",\n  \"metadata\": {\n    \"bluetooth\": {\n")
        assertContains(
            markdown,
            "      \"accessory_address\": \"AA:BB:CC\",\n      \"rssi\": -61\n    },\n" +
                "    \"tags\": [\n      \"a\",\n      \"b\"\n    ]\n  },\n  \"id\": 7\n}\n```",
        )
        // The row line comes first, the record after it.
        assertTrue(markdown.indexOf("```java") < markdown.indexOf("```json"))
    }

    @Test
    fun theJiraWikiStyleUsesACodeMacroForTheJson() {
        val tab = tabWith(AnnBlock.LogRef("r1", listOf(1), "", sourceEntries = listOf(jsonRow(1))))

        val markdown = buildMd(tab, AppSettings(annotationLogBlockStyle = AnnotationLogBlockStyle.JIRA_JAVA))

        assertContains(markdown, "{code:json}\n{\n  \"timestamp\"")
        assertFalse("```json" in markdown)
    }

    @Test
    fun aLongRecordIsCappedWithACountOfWhatWasLeftOut() {
        val long = "[" + (1..400).joinToString(",") { it.toString() } + "]"
        val tab = tabWith(AnnBlock.LogRef("r1", listOf(1), "", sourceEntries = listOf(jsonRow(1, long))))

        val markdown = buildMd(tab, AppSettings(annotationLogBlockStyle = AnnotationLogBlockStyle.JIRA_CLOUD))

        val json = markdown.substringAfter("```json\n").substringBefore("\n```")
        val lines = json.lines()
        assertEquals(RECORD_EXPORT_MAX_LINES + 1, lines.size)
        assertEquals("… ${402 - RECORD_EXPORT_MAX_LINES} more lines", lines.last())
    }

    @Test
    fun deeplyNestedOmittedJsonIsCountedWithoutRecursivelyFormattingIt() {
        val depth = RECORD_EXPORT_MAX_LINES * RECORD_EXPORT_MAX_LINES
        var node: JsonElement = JsonPrimitive("leaf")
        repeat(depth) { node = JsonArray(listOf(node)) }

        val lines = prettyJsonLines(node)

        assertEquals(RECORD_EXPORT_MAX_LINES + 1, lines.size)
        assertEquals("[", lines.first())
        assertEquals("… ${depth * 2 + 1 - RECORD_EXPORT_MAX_LINES} more lines", lines.last())
    }

    @Test
    fun chosenFieldsAreOneJsonObjectOfPathsAfterTheSourceLabel() {
        val settings = AppSettings(annotationLogBlockStyle = AnnotationLogBlockStyle.JIRA_CLOUD)
        val tab = tabWith(ref("r1", fields(), section = "Tethered Bluetooth / HCI", source = "j"))

        val markdown = buildMd(tab, settings)

        val expectedBlock = "From Untitled.json · HCI\n```json\n{\n  \"metadata.bluetooth.accessory_address\": \"AA:BB:CC\",\n  \"id\": 7\n}\n```"
        assertContains(markdown, expectedBlock)
        // The record's own row line is not repeated: the fields stand for it.
        assertFalse("message 1" in markdown)
    }

    @Test
    fun theHtmlCarriesTheSameJson() {
        val tab = tabWith(
            AnnBlock.LogRef("r1", listOf(1), "", sourceEntries = listOf(jsonRow(1))),
            ref("r2", fields(), source = "j"),
        )

        val html = buildAnnotationsHtml(tab, AppSettings())

        assertContains(html, "&quot;accessory_address&quot;: &quot;AA:BB:CC&quot;,")
        assertContains(html, "<pre><code>{\n  &quot;metadata.bluetooth.accessory_address&quot;: &quot;AA:BB:CC&quot;,\n  &quot;id&quot;: 7\n}\n</code></pre>")
    }

    @Test
    fun exportsKeepLiteralPathSyntaxSeparateFromNestedFields() {
        val json = """{"x.y":1,"x":{"y":2},"items[0]":3,"items":[4],"":5}"""
        val paths = listOf(listOf("x.y"), listOf("x", "y"), listOf("items[0]"), listOf("items", "[0]"), listOf(""))
        val fields = assertIs<RecordFieldsResult.Ok>(recordFieldRefs(json, paths)).fields
        val tab = tabWith(ref(fields = fields))
        val settings = AppSettings(annotationLogBlockStyle = AnnotationLogBlockStyle.JIRA_CLOUD)

        val markdown = buildMd(tab, settings).substringAfter("```json\n").substringBefore("\n```")
        val html = buildAnnotationsHtml(tab, settings).substringAfter("<pre><code>").substringBefore("\n</code></pre>")
            .replace("&quot;", "\"")
        val expected = mapOf("[\"x.y\"]" to "1", "x.y" to "2", "[\"items[0]\"]" to "3", "items[0]" to "4", "[\"\"]" to "5")
        for (export in listOf(markdown, html)) {
            assertEquals(expected, Json.parseToJsonElement(export).jsonObject.mapValues { it.value.toString() })
        }
    }

    @Test
    fun rowsThatAreNotJsonAreExportedAsBefore() {
        val plain = LogEntry(1, "10:00:01.000", LogLevel.I, "App", "plain line")
        val tab = mkTab("a", "a.log", listOf(plain)).copy(
            annotations = notes(AnnBlock.LogRef("r1", listOf(1), "cap", sourceEntries = listOf(plain))),
        )

        val markdown = buildMd(tab, AppSettings(annotationLogBlockStyle = AnnotationLogBlockStyle.JIRA_CLOUD))

        assertFalse("json" in markdown)
        assertContains(markdown, "```java\n")
    }

    // ── AI tools ─────────────────────────────────────────────────────────────────────────────────

    @Test
    fun getAnnotationBlocksReportsTheChosenFieldsOfAReferenceOnly() {
        val dir = createTempDirectory("record-fields-tools").toFile()
        val app = AppState(File(dir, "state.cache"), autoExportNotes = false)
        try {
            app.tabs = listOf(mkTab("t1", "t1.log", listOf(jsonRow(1))))
            app.activeTabId = "t1"
            app.upAnn("t1") { it.copy(annotations = notes(ref("r1", fields()), AnnBlock.LogRef("r2", listOf(1), "whole row"))) }

            val listed = IndagiumToolOperations(app).toolGateway.execute("get_annotation_blocks", mapOf("tabId" to "t1")) as Map<*, *>

            val blocks = (listed["blocks"] as List<*>).map { it as Map<*, *> }
            val chosen = blocks.single { it["id"] == "r1" }["recordFields"] as List<*>
            assertEquals(
                listOf(
                    mapOf("path" to listOf("metadata", "bluetooth", "accessory_address"), "json" to "\"AA:BB:CC\""),
                    mapOf("path" to listOf("id"), "json" to "7"),
                ),
                chosen,
            )
            assertFalse(blocks.single { it["id"] == "r2" }.containsKey("recordFields"))
        } finally {
            app.close()
            dir.deleteRecursively()
        }
    }
}
