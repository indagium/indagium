package com.indagium

import com.indagium.model.CapabilityAvailability
import com.indagium.model.LogCapability
import com.indagium.model.LogFieldRole
import com.indagium.model.LogLevel
import com.indagium.utils.isMetadataJsonSample
import com.indagium.utils.metadataJsonProfile
import com.indagium.utils.parseLogContent
import com.indagium.utils.parseMetadataJson
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import java.io.StringReader
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class MetadataJsonCaptureTest {
    @Suppress("MagicNumber") // Synthetic wire timestamps and byte payloads.
    private fun packet(time: Long, identity: String = "Bluetooth.HCI.Note", message: String = "Capture started"): String = buildJsonObject {
        put("metadata", buildJsonObject {
            put("identity", identity)
            put("source", "Demo accessory")
            put("system_time", time)
            put("system_time_formatted", "12:00:00.001")
            put("timestamp", 1_000_000L)
            put("timestamp_formatted", "0.001")
            put("title", "00 01 02")
        })
        put("Raw Bytes", "000102")
        put("Summary", buildJsonObject { put("Note", message) })
        put("Extra details", buildJsonObject { put("future field", "preserved") })
    }.toString()

    @Test
    fun tagIsHiddenByDefaultWithoutRemovingItsValueOrFilteringCapability() {
        val parsed = parseLogContent("[${packet(1790000000000000000L)}]".byteInputStream(), fileName = "records.json")
        assertFalse(LogFieldRole.COMPONENT in parsed.profile!!.visibleColumns)
        assertFalse(parsed.entries.single().recordMetadata!!.displayColumns.any { it.id == "component" })
        assertEquals("Bluetooth.HCI.Note", parsed.entries.single().tag)
        assertEquals(CapabilityAvailability.AVAILABLE, parsed.capabilities.getValue(LogCapability.COMPONENT_FILTER).availability)
        val ordinary = parseLogContent("""{"tag":"Service","message":"ordinary"}""".byteInputStream(), fileName = "ordinary.json")
        assertTrue(LogFieldRole.COMPONENT in ordinary.profile!!.visibleColumns)
        assertTrue(ordinary.entries.single().recordMetadata!!.displayColumns.any { it.id == "component" })
    }

    @Test
    fun oldBuiltInSnapshotsGetTheNewDefaultWhileExplicitAndCustomChoicesSurvive() {
        val columns = listOf(LogFieldRole.TIMESTAMP, LogFieldRole.SOURCE, LogFieldRole.COMPONENT, LogFieldRole.MESSAGE)
        val document = """{"Overview":{"Details":{"name":"demo"}},"Services":{"Events":[${packet(1790000000000000000L)}]}}"""
        val old = metadataJsonProfile().copy(revision = 1, visibleColumns = columns)
        val migrated = parseMetadataJson(StringReader(document), old)
        assertEquals(2, migrated.profile!!.revision)
        assertTrue(migrated.sections.flatMap { it.entries }.none { row -> row.recordMetadata!!.displayColumns.any { it.id == "component" } })
        val explicit = parseMetadataJson(StringReader(document), old.copy(revision = 2))
        assertTrue(explicit.sections.flatMap { it.entries }.all { row -> row.recordMetadata!!.displayColumns.any { it.id == "component" } })
        val custom = parseMetadataJson(StringReader(document), old.copy(id = "custom-metadata"))
        assertTrue(LogFieldRole.COMPONENT in custom.profile!!.visibleColumns)
    }

    @Test
    fun detectionRecognizesMetadataBeforeALargeOrIncompleteFirstPayload() {
        val prefix = """{"Wi-Fi":{"Network":[{"metadata":{"identity":"Network.Frame","source":"demo","system_time":123},"Raw Bytes":""""
        assertTrue(isMetadataJsonSample(prefix + "aa".repeat(5000)))
        assertFalse(isMetadataJsonSample("""{"message":"metadata identity system_time", "details":{"identity":"unrelated"}}"""))
    }

    @Test
    fun flatPrettyPrintedExportUsesMetadataAndDecodedSummary() {
        val json = Json { prettyPrint = true }.encodeToString(
            kotlinx.serialization.json.JsonElement.serializer(), Json.parseToJsonElement("[${packet(1790000000000500000L)}]"),
        )
        val parsed = parseLogContent(json.byteInputStream(), fileName = "HCI.json", startId = 12)
        val entry = parsed.entries.single()
        assertEquals("metadata-json", parsed.profile?.id)
        assertEquals(12, entry.id)
        assertEquals("Capture started", entry.msg)
        assertEquals(entry.msg, entry.recordMetadata?.displayColumns?.single { it.id == "message" }?.value)
        assertEquals("Bluetooth.HCI.Note", entry.tag)
        assertEquals("Demo accessory", entry.profileFields["source"])
        assertEquals("HCI", entry.profileFields["section"])
        assertEquals(1790000000001L, entry.parsedTime?.epochMillis)
        assertEquals("0.001", entry.profileFields["relative_time"])
        assertFalse(entry.severityKnown)
        assertEquals("preserved", entry.profileFields["Extra details.future field"])
        assertEquals(Json.parseToJsonElement(packet(1790000000000500000L)), Json.parseToJsonElement(assertNotNull(entry.rawRecord)))
        assertTrue(assertNotNull(entry.sourceStartLine) > 1)
        assertTrue(assertNotNull(entry.sourceEndLine) > entry.sourceStartLine!!)
        assertEquals(CapabilityAvailability.UNAVAILABLE, parsed.capabilities.getValue(LogCapability.INCREMENTAL_TAIL).availability)
        assertEquals(CapabilityAvailability.UNAVAILABLE, parsed.capabilities.getValue(LogCapability.RECORD_SPLIT).availability)
    }

    @Test
    fun nestedSectionsRemainSeparateAndRetainTransportDetails() {
        val later = packet(1790000000000000002L, "Bluetooth.HCI.Event", "later")
        val earlier = packet(1790000000000000001L, "Bluetooth.L2CAP", "earlier")
        val sameTime = packet(1790000000000000002L, "USB.iAP2.Message", "same time")
        val document = """{"Summary":{"Accessories":[{"name":"Demo"}]},"Tethered Bluetooth":{"HCI":[$later],"L2CAP":[$earlier]},""" +
            """"USB":{"iAP2":[$sameTime],"Empty":[]}}"""
        val parsed = parseLogContent(document.byteInputStream(), fileName = "capture.json")
        assertEquals("metadata-json", parsed.profile?.id)
        assertEquals(listOf("later"), parsed.entries.map { it.msg }, "HCI is the initial section, with no L2CAP or USB rows mixed in")
        val sections = parsed.sections.associateBy { it.id }
        assertEquals(listOf("earlier"), sections.getValue("Tethered Bluetooth / L2CAP").entries.map { it.msg })
        assertEquals(listOf("same time"), sections.getValue("USB / iAP2").entries.map { it.msg })
        assertTrue(sections.getValue("USB / Empty").entries.isEmpty())
        val all = parsed.sections.flatMap { it.entries }
        assertEquals(setOf(1, 2, 3, 4), all.map { it.id }.toSet(), "packet IDs remain distinct across sections")
        assertTrue(all.filter { it.tag != "Metadata" }.all { it.rawRecord?.contains("future field") == true })
    }

    @Test
    fun exportedPacketsReopenIncludingFailedCommandSeverity() {
        val value = Json.parseToJsonElement(packet(1790000000000000000L, "Bluetooth.HCI.Event")).jsonObject
        val failed = JsonObject(value + ("Summary" to buildJsonObject {
            put("Code", "Command Status")
            put("Command", "LE Connection Update")
            put("Status", "Command Disallowed")
        }))
        val parsed = parseLogContent("[$failed]".byteInputStream(), fileName = "HCI.json")
        val entry = parsed.entries.single()
        assertEquals("Command Status — LE Connection Update — Command Disallowed", entry.msg)
        assertTrue(entry.severityKnown)
        assertEquals(LogLevel.E, entry.level)
        val reopened = parseLogContent(("[" + parsed.entries.joinToString(",") { it.rawRecord!! } + "]").byteInputStream(), fileName = "exported.json")
        assertEquals("metadata-json", reopened.profile?.id)
        assertEquals(entry.rawRecord, reopened.entries.single().rawRecord)
    }

    @Test
    fun damagedTailKeepsCompletePacketsAndReportsDamage() {
        val json = """{"Bluetooth":{"HCI":[${packet(1790000000000000000L)},{"metadata": """
        val parsed = parseLogContent(json.byteInputStream(), fileName = "broken.json")
        assertEquals("metadata-json", parsed.profile?.id)
        assertEquals(1, parsed.entries.size)
        assertTrue(parsed.diagnostics.any { "damaged or truncated" in it })
    }

    @Test
    fun arraysAndNestedRecordObjectsInsideAMessageDoNotBecomeSectionsOrExtraMessages() {
        val child = packet(1790000000000000001L, "Service.Child", "nested")
        val original = Json.parseToJsonElement(packet(1790000000000000000L)).jsonObject
        val parent = JsonObject(original + ("Details" to Json.parseToJsonElement(
            """{"values":[1,2,3],"linked":[ $child ],"other":{"entries":[{"name":"x"}]}}""",
        )))
        val flat = parseLogContent("[$parent]".byteInputStream(), fileName = "single.json")
        assertEquals(1, flat.entries.size)
        assertEquals(listOf("single"), flat.sections.map { it.title })
        assertEquals(Json.parseToJsonElement(parent.toString()), Json.parseToJsonElement(flat.entries.single().rawRecord!!))
        val combined = parseLogContent("""{"Services":{"Events":[$parent],"Empty":[]}}""".byteInputStream(), fileName = "combined.json")
        assertEquals(1, combined.entries.size)
        assertEquals(setOf("Services / Events", "Services / Empty"), combined.sections.map { it.id }.toSet())
    }

    @Test
    fun metadataObjectsInAnEnvelopeAreNotLimitedToSpecificNames() {
        val document = """{"Overview":{"Environment":{"name":"demo","properties":[1,2]}},"Services":{"Events":[${packet(1790000000000000000L)}]}}"""
        val parsed = parseLogContent(document.byteInputStream(), fileName = "combined.json")
        val overview = parsed.sections.single { it.id == "Overview / Environment" }.entries.single()
        assertEquals("Environment details", overview.msg)
        assertEquals("", overview.ts)
        assertEquals(null, overview.parsedTime)
        assertEquals(listOf("Services / Events", "Overview / Environment"), parsed.sections.map { it.id })
        assertEquals("Services / Events", parsed.selectedSectionId)
    }

    @Test
    fun ordinaryJsonIsNotMistakenForMetadataRecords() {
        val json = """{"message":"metadata system_time identity source","metadata":{"identity":"business","source":"app"}}"""
        val parsed = parseLogContent(json.byteInputStream(), fileName = "ordinary.json")
        assertEquals("json-lines", parsed.profile?.id)
        assertEquals(1, parsed.entries.size)
    }

    @Test
    fun summaryMetadataIsInspectableWithoutInventingPacketTimestamps() {
        val summary = """{"Identification":{"Accessory Info":{"Name":"Demo accessory"}}}"""
        val document = """{"Summary":{"iAP2":$summary},"Tethered Bluetooth":{"HCI":[${packet(1790000000000000000L)}]}}"""
        val parsed = parseLogContent(document.byteInputStream(), fileName = "summary.json")
        val entry = parsed.sections.single { it.id == "Summary / iAP2" }.entries.single()
        assertEquals("iAP2 details", entry.msg)
        assertEquals("", entry.ts)
        assertEquals(null, entry.parsedTime)
        assertEquals(Json.parseToJsonElement(summary), Json.parseToJsonElement(entry.rawRecord!!))
        val standalone = parseLogContent(summary.byteInputStream(), fileName = "details.json")
        assertEquals(1, standalone.entries.size)
        assertEquals(Json.parseToJsonElement(summary), Json.parseToJsonElement(standalone.entries.single().rawRecord!!))
    }

    @Test
    fun recordsWithNullSourcesOrNoPacketTimeAreRetained() {
        val document = """{"Wi-Fi":{"Bonjour Services":[""" +
            """{"metadata":{"identity":"WiFi.Bonjour","source":null,"system_time":1790000000000000000,"title":"Timed service"}},""" +
            """{"metadata":{"identity":"WiFi.Bonjour","source":null,"system_time":null,"timestamp":null,"title":"Untimed service"}}]}}"""
        val parsed = parseLogContent(document.byteInputStream(), fileName = "services.json")
        assertEquals("metadata-json", parsed.profile?.id)
        assertEquals(listOf("Timed service", "Untimed service"), parsed.entries.map { it.msg })
        assertEquals("", parsed.entries.last().ts)
        assertEquals(null, parsed.entries.last().parsedTime?.epochMillis)
        assertEquals("", parsed.entries.first().profileFields["source"])
    }
}
