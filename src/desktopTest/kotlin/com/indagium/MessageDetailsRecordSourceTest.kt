package com.indagium

import com.indagium.model.LogDisplayField
import com.indagium.model.LogEntry
import com.indagium.model.LogLevel
import com.indagium.model.LogRecordMetadata
import com.indagium.ui.distinctRecordFields
import com.indagium.ui.messageDetailsCopyLabel
import com.indagium.ui.messageDetailsShowsParsedFields
import com.indagium.ui.messageDetailsTreeSource
import com.indagium.ui.profileRecordsAddDetail
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** What Message details shows and copies for a record, and when a tab has anything for it to add. */
class MessageDetailsRecordSourceTest {
    private fun row(
        id: Int,
        raw: String,
        fields: Map<String, String>,
        columns: List<Pair<String, String>>,
    ) = LogEntry(
        id = id, ts = "12:00:00", level = LogLevel.I, tag = "", msg = columns.lastOrNull()?.second.orEmpty(),
        recordMetadata = LogRecordMetadata(
            profileFields = fields,
            displayColumns = columns.map { (columnId, value) -> LogDisplayField(columnId, columnId, value) },
            rawRecord = raw,
            usesProfilePresentation = true,
        ),
    )

    private val syslog = row(
        1,
        "<134>Nov 16 12:00:02 demo-node Worker[4100]: Request accepted",
        mapOf("TIMESTAMP" to "Nov 16 12:00:02", "HOST" to "demo-node", "COMPONENT" to "Worker", "PROCESS_ID" to "4100", "MESSAGE" to "Request accepted"),
        listOf("timestamp" to "Nov 16 12:00:02", "component" to "Worker", "message" to "Request accepted"),
    )

    private val dmesg = row(
        2,
        "[    1.500000] demo_driver: Request accepted",
        mapOf("TIMESTAMP" to "1.500000", "MESSAGE" to "demo_driver: Request accepted"),
        listOf("timestamp" to "1.500000", "message" to "demo_driver: Request accepted"),
    )

    @Test
    fun aTextRecordShowsItsParsedFieldsAsATreeWithTheSourceLineUnderIt() {
        assertEquals(
            """{"timestamp":"Nov 16 12:00:02","host":"demo-node","component":"Worker","process_id":"4100","message":"Request accepted"}""",
            messageDetailsTreeSource(syslog),
        )
        assertTrue(messageDetailsShowsParsedFields(syslog))
    }

    @Test
    fun aJsonRecordIsShownAsRead() {
        val json = row(3, """{"msg":"ok","level":30}""", mapOf("MESSAGE" to "ok"), listOf("message" to "ok"))
        assertEquals("""{"msg":"ok","level":30}""", messageDetailsTreeSource(json))
        assertFalse(messageDetailsShowsParsedFields(json))
    }

    @Test
    fun theCopyActionIsNamedForWhatItCopies() {
        assertEquals("Copy JSON", messageDetailsCopyLabel(row(3, """{"msg":"ok"}""", emptyMap(), emptyList())))
        assertEquals("Copy line", messageDetailsCopyLabel(syslog))
        assertEquals("Copy record", messageDetailsCopyLabel(row(4, "ERROR boom\n  at a.b(C.kt:1)", emptyMap(), emptyList())))
    }

    @Test
    fun aTabGetsTheTileOnlyWhenItsRecordsHoldMoreThanTheRowShows() {
        // The syslog row hides its host and process id.
        assertTrue(profileRecordsAddDetail("syslog", listOf(syslog)))
        // A dmesg row is its whole record.
        assertFalse(profileRecordsAddDetail("dmesg", listOf(dmesg)))
        // A record of several lines always has more to show.
        val multiline = row(5, "ERROR boom\n  at a.b(C.kt:1)", mapOf("MESSAGE" to "ERROR boom"), listOf("message" to "ERROR boom"))
        assertTrue(profileRecordsAddDetail("regex", listOf(multiline)))
        // A Logcat row has no profile record.
        assertFalse(profileRecordsAddDetail("logcat", listOf(LogEntry(6, "12:00:00.000", LogLevel.I, "App", "hi"))))
    }

    @Test
    fun eachValueShowsOnceUnderItsSourceName() {
        val logfmt = mapOf(
            "time" to "2026-11-16T12:00:00Z", "level" to "INFO", "msg" to "ok", "pid" to "4100",
            "TIMESTAMP" to "2026-11-16T12:00:00Z", "SEVERITY" to "INFO", "MESSAGE" to "ok", "PROCESS_ID" to "4100",
        )
        assertEquals(listOf("time", "level", "msg", "pid"), distinctRecordFields(logfmt).keys.toList())
        // A role with no source copy (a syslog host parsed by the engine itself) stays.
        val syslog = mapOf("HOST" to "demo-node", "sd.a@1.k" to "v", "structured_data" to "[a@1 k=\"v\"]")
        assertEquals(listOf("HOST", "sd.a@1.k"), distinctRecordFields(syslog).keys.toList())
    }
}
