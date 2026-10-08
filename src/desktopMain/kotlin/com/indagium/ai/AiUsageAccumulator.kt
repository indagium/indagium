package com.indagium.ai

import com.indagium.model.AiUsageStats
import com.indagium.model.sumAiUsage

/** How a provider reports repeated usage updates. */
internal enum class AiUsageAggregation { REQUEST_SNAPSHOT, RUN_CUMULATIVE }

/** Accumulates exact provider reports without treating cache subsets or cumulative snapshots as new spend. */
internal class AiUsageAccumulator(toolCallsKnown: Boolean = false) {
    private val requestSnapshots = LinkedHashMap<String, AiRunEvent.Usage>()
    private val startedRequestIds = LinkedHashSet<String>()
    private var cumulativeSnapshot: AiRunEvent.Usage? = null
    private var unkeyed = 0L
    private var toolCalls = 0L
    private var explicitPartial = false
    private val knowsToolCalls = toolCallsKnown

    @Synchronized
    fun accept(event: AiRunEvent) {
        when (event) {
            is AiRunEvent.ToolExecutionStarted -> toolCalls = incrementSaturated(toolCalls)
            is AiRunEvent.UsageRequestStarted -> startedRequestIds += event.requestId
            is AiRunEvent.Usage -> {
                if (event.hasInvalidCounts()) explicitPartial = true
                when (event.aggregation) {
                    AiUsageAggregation.REQUEST_SNAPSHOT -> {
                        val key = event.requestId ?: "unkeyed-${++unkeyed}"
                        startedRequestIds += key
                        requestSnapshots[key] = mergeSnapshots(requestSnapshots[key], event)
                    }
                    AiUsageAggregation.RUN_CUMULATIVE -> cumulativeSnapshot = mergeSnapshots(cumulativeSnapshot, event)
                }
            }
            is AiRunEvent.Error, AiRunEvent.Cancelled -> explicitPartial = true
            else -> Unit
        }
    }

    @Synchronized
    fun markPartial() {
        explicitPartial = true
    }

    @Synchronized
    fun snapshot(partial: Boolean = false): AiUsageStats {
        val requestReports = startedRequestIds.map { id -> requestSnapshots[id]?.toStats() ?: AiUsageStats(partial = true) }
        val reports = requestReports + listOfNotNull(cumulativeSnapshot?.toStats())
        val summed = sumAiUsage(reports)
        return (summed ?: AiUsageStats()).copy(
            toolCalls = if (knowsToolCalls) toolCalls else null,
            partial = explicitPartial || partial || summed?.partial == true,
        )
    }

    private fun AiRunEvent.Usage.toStats(): AiUsageStats {
        val input = inputTokens.nonNegative()
        val output = outputTokens.nonNegative()
        val total = totalTokens.nonNegative()
        return AiUsageStats(
            inputTokens = input,
            outputTokens = output,
            totalTokens = total,
            cacheCreationTokens = cacheCreationInputTokens.nonNegative(),
            cachedInputTokens = cachedInputTokens.nonNegative(),
            cachedInputIncludedInInput = cachedInputIncludedInInput,
            reasoningOutputTokens = reasoningOutputTokens.nonNegative(),
            partial = input == null && output == null && total == null,
        )
    }

    private fun mergeSnapshots(previous: AiRunEvent.Usage?, update: AiRunEvent.Usage): AiRunEvent.Usage {
        if (previous == null) return update

        fun maxKnown(left: Long?, right: Long?): Long? = when {
            left == null -> right
            right == null -> left
            else -> maxOf(left, right)
        }
        return update.copy(
            inputTokens = maxKnown(previous.inputTokens, update.inputTokens),
            outputTokens = maxKnown(previous.outputTokens, update.outputTokens),
            totalTokens = maxKnown(previous.totalTokens, update.totalTokens),
            cacheCreationInputTokens = maxKnown(previous.cacheCreationInputTokens, update.cacheCreationInputTokens),
            cachedInputTokens = maxKnown(previous.cachedInputTokens, update.cachedInputTokens),
            reasoningOutputTokens = maxKnown(previous.reasoningOutputTokens, update.reasoningOutputTokens),
            cachedInputIncludedInInput = when {
                previous.cachedInputIncludedInInput == null -> update.cachedInputIncludedInInput
                update.cachedInputIncludedInInput == null -> previous.cachedInputIncludedInInput
                previous.cachedInputIncludedInInput == update.cachedInputIncludedInInput -> previous.cachedInputIncludedInInput
                else -> null
            },
        )
    }

    private fun incrementSaturated(value: Long): Long = if (value == Long.MAX_VALUE) value else value + 1L

    private fun Long?.nonNegative(): Long? = this?.takeIf { it >= 0L }

    private fun AiRunEvent.Usage.hasInvalidCounts(): Boolean = listOf(
        inputTokens,
        outputTokens,
        totalTokens,
        cacheCreationInputTokens,
        cachedInputTokens,
        reasoningOutputTokens,
    ).any { it != null && it < 0L }
}

/** Reads a run's full immutable history, independent of the bounded SharedFlow replay buffer. */
internal fun aiUsageFromHistory(
    events: List<AiRunEvent>,
    toolCallsKnown: Boolean = true,
    partial: Boolean = false,
): AiUsageStats = AiUsageAccumulator(toolCallsKnown).let { accumulator ->
    events.forEach(accumulator::accept)
    // Providers that omit accounting cannot establish zero consumption. Once an agent run has emitted
    // activity, absent usage stays explicitly unavailable and partial even when the run ended normally.
    accumulator.snapshot(partial || (events.isNotEmpty() && events.none { it is AiRunEvent.Usage }))
}
