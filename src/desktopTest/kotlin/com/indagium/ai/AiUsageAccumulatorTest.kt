package com.indagium.ai

import com.indagium.model.AiUsageStats
import com.indagium.model.sumAiUsage
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class AiUsageAccumulatorTest {
    @Test
    fun requestSnapshotsReplaceWithinARequestAndSumAcrossRequests() {
        val firstCall = AiRunEvent.UsageRequestStarted("request-1")
        val firstSnapshot = usage("request-1", input = 100, output = 20, total = 120)
        val repeatedSnapshot = usage("request-1", input = 90, output = null, total = null)
        val secondSnapshot = usage("request-2", input = 5, output = 1, total = 6)
        val events = listOf(
            firstCall,
            firstSnapshot,
            repeatedSnapshot,
            AiRunEvent.UsageRequestStarted("request-2"),
            secondSnapshot,
        )

        val stats = aiUsageFromHistory(events)

        assertEquals(105L, stats.inputTokens)
        assertEquals(21L, stats.outputTokens)
        assertEquals(126L, stats.totalTokens)
        assertFalse(stats.partial)
    }

    @Test
    fun aStartedRequestWithoutUsageMakesKnownTotalsPartial() {
        val stats = aiUsageFromHistory(
            listOf(
                AiRunEvent.UsageRequestStarted("request-1"),
                usage("request-1", input = 100, output = 30, total = 130),
                AiRunEvent.UsageRequestStarted("request-2"),
            ),
        )

        assertEquals(100L, stats.inputTokens)
        assertEquals(130L, stats.totalTokens)
        assertTrue(stats.partial)
    }

    @Test
    fun sparseAndOutOfOrderCumulativeUpdatesNeverEraseKnownCounts() {
        val stats = aiUsageFromHistory(
            listOf(
                usage(null, input = 200, output = 80, total = 280, aggregation = AiUsageAggregation.RUN_CUMULATIVE),
                usage(null, input = null, output = 70, total = null, aggregation = AiUsageAggregation.RUN_CUMULATIVE),
                usage(null, input = 150, output = 60, total = 210, aggregation = AiUsageAggregation.RUN_CUMULATIVE),
            ),
        )

        assertEquals(200L, stats.inputTokens)
        assertEquals(80L, stats.outputTokens)
        assertEquals(280L, stats.totalTokens)
        assertFalse(stats.partial)
    }

    @Test
    fun cacheSubsetsRemainSeparateAndMissingTokenReportsStayUnavailable() {
        val stats = aiUsageFromHistory(
            listOf(
                AiRunEvent.UsageRequestStarted("request-1"),
                AiRunEvent.Usage(
                    inputTokens = 100,
                    outputTokens = 10,
                    totalTokens = 110,
                    cachedInputTokens = 30,
                    cachedInputIncludedInInput = true,
                    requestId = "request-1",
                ),
                AiRunEvent.UsageRequestStarted("request-2"),
            ),
        )

        assertEquals(100L, stats.inputTokens, "cached input is a subset, never added to input twice")
        assertEquals(30L, stats.cachedInputTokens)
        assertTrue(stats.partial)
        assertNull(aiUsageFromHistory(listOf(AiRunEvent.UsageRequestStarted("no-report"))).totalTokens)
    }

    @Test
    fun toolCountsAreIndependentOfCappedHistoryAndProviderIdsMayRepeat() {
        val starts = (0 until 350).map { index ->
            AiRunEvent.ToolExecutionStarted(LlmToolCall("tool-$index", "read", "{}"))
        }
        val reusedId = LlmToolCall("call_0", "read", "{}")
        val sameProviderId = List(2) { AiRunEvent.ToolExecutionStarted(reusedId) }
        val events = starts + sameProviderId + List(350) { AiRunEvent.ToolRequested(LlmToolCall("request-$it", "read", "{}")) }

        assertEquals(352L, aiUsageFromHistory(events).toolCalls)
    }

    @Test
    fun negativeCountsAreUnavailableAndArithmeticOverflowSaturatesWithPartialMarker() {
        val negative = aiUsageFromHistory(listOf(usage("request-1", input = -1, output = 3, total = -2)))
        assertNull(negative.inputTokens)
        assertNull(negative.totalTokens)
        assertTrue(negative.partial)

        val overflow = sumAiUsage(
            listOf(AiUsageStats(inputTokens = Long.MAX_VALUE), AiUsageStats(inputTokens = 1)),
        )
        assertEquals(Long.MAX_VALUE, overflow?.inputTokens)
        assertTrue(overflow?.partial == true)
    }

    private fun usage(
        requestId: String?,
        input: Long?,
        output: Long?,
        total: Long?,
        aggregation: AiUsageAggregation = AiUsageAggregation.REQUEST_SNAPSHOT,
    ) = AiRunEvent.Usage(
        inputTokens = input,
        outputTokens = output,
        totalTokens = total,
        requestId = requestId,
        aggregation = aggregation,
    )
}
