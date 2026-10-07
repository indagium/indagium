package com.indagium.testing

import com.indagium.testing.model.LaneToolCall
import com.indagium.testing.model.LaneToolCallStatus
import com.indagium.testing.store.LaneToolActivityWriter
import java.io.File
import kotlin.io.path.createTempDirectory
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class LaneToolActivityWriterTest {
    @Test
    fun fullActivityStreamSurvivesBeyondTheLiveRunCacheSizeAndMarksStorageTruncation() {
        val root = createTempDirectory("tool-activity").toFile()
        try {
            val fullFile = File(root, "full.jsonl")
            val full = LaneToolActivityWriter(fullFile)
            repeat(305) { index -> full.append("finished", call("call-$index")) }
            assertEquals(305, fullFile.readLines().size)

            val cappedFile = File(root, "capped.jsonl")
            val capped = LaneToolActivityWriter(cappedFile, maxBytes = 500)
            repeat(20) { index -> capped.append("finished", call("call-$index")) }
            assertTrue(cappedFile.length() <= 500)
            assertTrue(cappedFile.readText().contains("truncated"), cappedFile.readText())
        } finally {
            root.deleteRecursively()
        }
    }

    private fun call(id: String) = LaneToolCall(
        id = id, caseId = "case-1", stepId = "step-1", iteration = 1, attempt = 1,
        toolName = "tap", argumentsPreview = "{}", resultPreview = "ok",
        status = LaneToolCallStatus.SUCCEEDED, startedAt = 1L, durationMs = 2L,
    )
}
