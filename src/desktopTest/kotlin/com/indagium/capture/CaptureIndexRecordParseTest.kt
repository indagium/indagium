package com.indagium.capture

import java.lang.management.ManagementFactory
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

private const val ALLOCATION_LINE_COUNT = 100_000
private const val ALLOCATION_WARMUP_LINES = 20_000
private const val ALLOCATION_RATIO_BOUND = 4
private const val RANDOM_RECORD_COUNT = 2_000
private const val RANDOM_SEED = 42
private const val SAMPLE_OFFSET = 28L
private const val SAMPLE_LENGTH = 94
private const val SAMPLE_ELAPSED = 117L
private const val MAX_TEST_BYTE_LENGTH = 1_000_000
private const val MAX_TEST_ORDINAL = 5_000_000

/** Fast path of [parseIndexRecord] must behave exactly like the JSON implementation it shortcuts. */
class CaptureIndexRecordParseTest {
    private fun outcome(block: () -> CaptureLogIndexRecord): Result<CaptureLogIndexRecord> = runCatching(block)

    private fun assertSameOutcome(line: String) {
        val fast = outcome { parseIndexRecord(line) }
        val json = outcome { parseIndexRecordJson(line) }
        assertEquals(json.getOrNull(), fast.getOrNull(), "value for: $line")
        assertEquals(json.exceptionOrNull()?.javaClass, fast.exceptionOrNull()?.javaClass, "exception type for: $line")
        assertEquals(json.exceptionOrNull()?.message, fast.exceptionOrNull()?.message, "exception message for: $line")
    }

    private fun randomRecords(): List<CaptureLogIndexRecord> {
        val random = Random(RANDOM_SEED)
        val edge = listOf(
            CaptureLogIndexRecord(0, 0, 0, null),
            CaptureLogIndexRecord(0, 0, 0, 0),
            CaptureLogIndexRecord(Long.MAX_VALUE, Int.MAX_VALUE, Long.MAX_VALUE, Int.MAX_VALUE),
            CaptureLogIndexRecord(SAMPLE_OFFSET, SAMPLE_LENGTH, SAMPLE_ELAPSED, 1),
            CaptureLogIndexRecord(0, SAMPLE_LENGTH, SAMPLE_ELAPSED, null),
        )
        val generated = List(RANDOM_RECORD_COUNT) {
            CaptureLogIndexRecord(
                byteOffset = if (random.nextBoolean()) random.nextLong(0, Long.MAX_VALUE) else random.nextLong(0, MAX_TEST_BYTE_LENGTH.toLong()),
                byteLength = random.nextInt(0, MAX_TEST_BYTE_LENGTH),
                elapsedMs = random.nextLong(0, Long.MAX_VALUE),
                rowOrdinal = if (random.nextBoolean()) random.nextInt(0, MAX_TEST_ORDINAL) else null,
            )
        }
        return edge + generated
    }

    @Test
    fun writerOutputRoundTrips() {
        for (record in randomRecords()) {
            assertEquals(record, parseIndexRecord(indexRecordJson(record)))
        }
    }

    @Test
    fun writerShapeIsWhatTheDocumentedExamplesSay() {
        assertEquals("""{"byteOffset":0,"byteLength":28,"elapsedMs":115}""", indexRecordJson(CaptureLogIndexRecord(0, 28, 115, null)))
        assertEquals(
            """{"byteOffset":28,"byteLength":94,"elapsedMs":117,"rowOrdinal":1}""",
            indexRecordJson(CaptureLogIndexRecord(28, 94, 117, 1)),
        )
    }

    @Test
    fun fastAndJsonPathsAgreeOnCanonicalLines() {
        for (record in randomRecords()) {
            val line = indexRecordJson(record)
            assertEquals(parseIndexRecordJson(line), parseIndexRecord(line), line)
        }
    }

    @Test
    fun negativeNumbersMatchTheJsonPath() {
        for (
        line in listOf(
            """{"byteOffset":-5,"byteLength":1,"elapsedMs":-9,"rowOrdinal":-3}""",
            """{"byteOffset":-9223372036854775808,"byteLength":-2147483648,"elapsedMs":1}""",
            """{"byteOffset":-0,"byteLength":1,"elapsedMs":1}""",
        )
        ) {
            assertSameOutcome(line)
        }
    }

    @Test
    fun nonCanonicalButValidJsonStillParses() {
        val expected = CaptureLogIndexRecord(12, 34, 56, 7)
        val variants = listOf(
            """{ "byteOffset": 12, "byteLength": 34, "elapsedMs": 56, "rowOrdinal": 7 }""",
            """{"elapsedMs":56,"rowOrdinal":7,"byteLength":34,"byteOffset":12}""",
            """{"byteOffset":12,"byteLength":34,"elapsedMs":56,"rowOrdinal":7,"extra":true}""",
            """{"byteOffset":012,"byteLength":34,"elapsedMs":56,"rowOrdinal":7}""",
            """{"byteOffset":12,"byteLength":34,"elapsedMs":56,"rowOrdinal":7} """,
        )
        for (line in variants) {
            assertEquals(expected, parseIndexRecord(line), line)
            assertSameOutcome(line)
        }
        assertEquals(
            CaptureLogIndexRecord(1, 2, 3, null),
            parseIndexRecord("""{"byteOffset":1,"byteLength":2,"elapsedMs":3,"rowOrdinal":null}"""),
        )
    }

    @Test
    fun invalidLinesFailExactlyLikeTheJsonPath() {
        val lines = listOf(
            """{"byteLength":2,"elapsedMs":3}""",
            """{"byteOffset":1,"elapsedMs":3}""",
            """{"byteOffset":1,"byteLength":2}""",
            """{"byteOffset":"x","byteLength":2,"elapsedMs":3}""",
            """{"byteOffset":1,"byteLength":2,"elapsedMs":3,"rowOrdinal":"abc"}""",
            """{"byteOffset":1,"byteLength":3000000000,"elapsedMs":3}""",
            """{"byteOffset":1,"byteLength":2,"elapsedMs":3,"rowOrdinal":3000000000}""",
            """{"byteOffset":99999999999999999999,"byteLength":2,"elapsedMs":3}""",
            """{"byteOffset":1,"byteLength":2,"elapsedMs":3""",
            """{"byteOffset":1,"byteLength":2,"elapsedMs":3,"rowOrdinal":}""",
            """{"byteOffset":,"byteLength":2,"elapsedMs":3}""",
            """{"byteOffset":1.5,"byteLength":2,"elapsedMs":3}""",
            "not json",
            "[]",
            "",
        )
        for (line in lines) {
            assertSameOutcome(line)
            assertTrue(outcome { parseIndexRecordJson(line) }.isFailure || parseIndexRecord(line) == parseIndexRecordJson(line), line)
        }
        assertTrue(outcome { parseIndexRecord("""{"byteOffset":1,"byteLength":3000000000,"elapsedMs":3}""") }.isFailure)
    }

    @Test
    fun fastPathAllocatesFarLessThanTheJsonPath() {
        val bean = ManagementFactory.getThreadMXBean() as? com.sun.management.ThreadMXBean ?: return
        if (!bean.isThreadAllocatedMemorySupported) return
        val random = Random(RANDOM_SEED)
        val lines = List(ALLOCATION_LINE_COUNT) {
            val record = CaptureLogIndexRecord(
                byteOffset = random.nextLong(0, Long.MAX_VALUE),
                byteLength = random.nextInt(0, MAX_TEST_BYTE_LENGTH),
                elapsedMs = random.nextLong(0, Int.MAX_VALUE.toLong()),
                rowOrdinal = it,
            )
            indexRecordJson(record)
        }

        fun allocated(parse: (String) -> CaptureLogIndexRecord): Long {
            var sink = 0L
            for (i in 0 until ALLOCATION_WARMUP_LINES) sink += parse(lines[i]).byteOffset
            val before = bean.currentThreadAllocatedBytes
            for (line in lines) sink += parse(line).byteLength
            val after = bean.currentThreadAllocatedBytes
            assertTrue(sink != 0L)
            return after - before
        }

        val fast = allocated(::parseIndexRecord)
        val json = allocated(::parseIndexRecordJson)
        println("capture-index parse allocation over $ALLOCATION_LINE_COUNT lines: fast=$fast bytes, json=$json bytes")
        assertTrue(fast * ALLOCATION_RATIO_BOUND < json, "fast=$fast json=$json")
    }
}
