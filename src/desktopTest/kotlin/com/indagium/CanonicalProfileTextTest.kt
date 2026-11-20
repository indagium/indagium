package com.indagium

import com.indagium.model.LogDisplayField
import com.indagium.model.LogEntry
import com.indagium.model.LogLevel
import com.indagium.model.LogRecordMetadata
import com.indagium.utils.canonicalProfileText
import com.indagium.utils.profileDisplayText
import com.indagium.utils.profileDisplayTextProjection
import com.indagium.utils.visibleLogLineText
import kotlin.test.Test
import kotlin.test.assertEquals

/** The text-only builder Filter and Find use must equal the text of the full display projection. */
class CanonicalProfileTextTest {
    private fun field(id: String, value: String, label: String = id) = LogDisplayField(id, label, value)

    private val columnLists: List<List<LogDisplayField>> = listOf(
        emptyList(),
        listOf(field("message", "only a message")),
        listOf(field("timestamp", "10:00:00.000"), field("severity", "ERROR"), field("component", "net"), field("message", "boom")),
        // Blank values are skipped without leaving a separator behind.
        listOf(field("timestamp", ""), field("severity", "  "), field("component", "net"), field("message", "")),
        listOf(field("timestamp", "t"), field("severity", ""), field("message", "m")),
        listOf(field("timestamp", ""), field("message", "")),
        // Non-standard columns carry their label, standard ones do not.
        listOf(field("message", "m"), field("trace_id", "abc", "Trace ID"), field("http_status", "404", "Status")),
        listOf(field("trace_id", "abc", "Trace ID"), field("message", "m")),
        // A value that ends with the truncation mark is written as it is.
        listOf(field("component", "svc…"), field("message", "cut…"), field("stream", "…", "Stream")),
        // Process and thread columns are standard ones.
        listOf(field("timestamp", "t"), field("process_id", "123"), field("thread_id", "456"), field("pid", "7"), field("tid", "8"), field("message", "m")),
        listOf(field("source", "main.log"), field("section", "boot"), field("message", "line with  two  spaces")),
        listOf(field("message", "multi\nline\tvalue"), field("extra", "a b", "Two words")),
    )

    @Test
    fun theTextOnlyBuilderEqualsTheProjectionText() {
        columnLists.forEachIndexed { index, columns ->
            val expected = profileDisplayTextProjection(columns).text
            assertEquals(expected, canonicalProfileText(columns), "column list #$index")
            assertEquals(expected, profileDisplayText(columns), "default profileDisplayText of list #$index")
        }
    }

    @Test
    fun aRowsVisibleTextIsTheCanonicalTextOfItsColumns() {
        columnLists.forEachIndexed { index, columns ->
            val entry = LogEntry(
                id = index + 1, ts = "", level = LogLevel.I, tag = "", msg = "",
                recordMetadata = LogRecordMetadata(displayColumns = columns, usesProfilePresentation = true),
            )
            assertEquals(profileDisplayTextProjection(columns).text, visibleLogLineText(entry), "row #$index")
        }
    }
}
