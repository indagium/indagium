package com.indagium.model

/** Token and tool usage known for one authoring or run scope. Null fields mean the source did not report that metric. */
data class AiUsageStats(
    val toolCalls: Long? = null,
    val inputTokens: Long? = null,
    val outputTokens: Long? = null,
    val totalTokens: Long? = null,
    /** Cache creation tokens are separate billable input in providers that report them. */
    val cacheCreationTokens: Long? = null,
    /** Cached input may be a subset of [inputTokens] or reported separately; see [cachedInputIncludedInInput]. */
    val cachedInputTokens: Long? = null,
    val cachedInputIncludedInInput: Boolean? = true,
    val reasoningOutputTokens: Long? = null,
    /** True when cancellation, failure, or a missing source scope may have left this total incomplete. */
    val partial: Boolean = false,
)

/** Compact user-facing line; null metrics stay explicitly unavailable instead of looking like zero. */
fun AiUsageStats.summaryLabel(): String {
    val tokens = totalTokens?.let { "Tokens $it" } ?: when {
        inputTokens != null && outputTokens != null -> "Input $inputTokens · output $outputTokens"
        inputTokens != null -> "Input $inputTokens · output unavailable"
        outputTokens != null -> "Input unavailable · output $outputTokens"
        else -> "Tokens unavailable"
    }
    return listOf(toolCalls?.let { "Tools $it" } ?: "Tools unavailable", tokens, "Partial".takeIf { partial })
        .filterNotNull().joinToString(" · ")
}

/** Adds known values while preserving missing components and marking incomplete scopes. */
fun sumAiUsage(scopes: Collection<AiUsageStats?>): AiUsageStats? {
    val present = scopes.filterNotNull()
    if (present.isEmpty()) return null
    var overflowed = false

    fun sum(selector: (AiUsageStats) -> Long?): Long? {
        val values = present.mapNotNull(selector).filter { it >= 0L }
        return values.takeIf { it.isNotEmpty() }?.fold(0L) { total, value ->
            if (value > 0L && total > Long.MAX_VALUE - value) {
                overflowed = true
                Long.MAX_VALUE
            } else {
                total + value
            }
        }
    }
    val cacheSemantics = present.mapNotNull { it.cachedInputIncludedInInput }.distinct().singleOrNull()
    val hasInvalidCounts = present.any { usage ->
        listOf(
            usage.toolCalls,
            usage.inputTokens,
            usage.outputTokens,
            usage.totalTokens,
            usage.cacheCreationTokens,
            usage.cachedInputTokens,
            usage.reasoningOutputTokens,
        ).any { it != null && it < 0L }
    }
    return AiUsageStats(
        toolCalls = sum { it.toolCalls },
        inputTokens = sum { it.inputTokens },
        outputTokens = sum { it.outputTokens },
        totalTokens = sum { it.totalTokens },
        cacheCreationTokens = sum { it.cacheCreationTokens },
        cachedInputTokens = sum { it.cachedInputTokens },
        cachedInputIncludedInInput = cacheSemantics,
        reasoningOutputTokens = sum { it.reasoningOutputTokens },
        partial = present.any { it.partial } || hasInvalidCounts || overflowed ||
            listOf<(AiUsageStats) -> Long?>({ it.totalTokens }, { it.inputTokens }, { it.outputTokens })
                .any { metric -> present.any { metric(it) != null } && present.any { metric(it) == null } },
    )
}
