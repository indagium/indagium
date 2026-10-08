package com.indagium.testing.store

import com.indagium.model.AiUsageStats
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put

internal fun aiUsageToJson(usage: AiUsageStats): JsonObject = buildJsonObject {
    usage.toolCalls?.let { put("toolCalls", it) }
    usage.inputTokens?.let { put("inputTokens", it) }
    usage.outputTokens?.let { put("outputTokens", it) }
    usage.totalTokens?.let { put("totalTokens", it) }
    usage.cacheCreationTokens?.let { put("cacheCreationTokens", it) }
    usage.cachedInputTokens?.let { put("cachedInputTokens", it) }
    usage.cachedInputIncludedInInput?.let { put("cachedInputIncludedInInput", it) }
    usage.reasoningOutputTokens?.let { put("reasoningOutputTokens", it) }
    put("partial", usage.partial)
}

internal fun decodeAiUsage(element: JsonElement?): AiUsageStats? {
    val value = element as? JsonObject ?: return null

    fun count(key: String): Long? = (value[key] as? JsonPrimitive)?.longOrNull?.takeIf { it >= 0L }
    return AiUsageStats(
        toolCalls = count("toolCalls"),
        inputTokens = count("inputTokens"),
        outputTokens = count("outputTokens"),
        totalTokens = count("totalTokens"),
        cacheCreationTokens = count("cacheCreationTokens"),
        cachedInputTokens = count("cachedInputTokens"),
        cachedInputIncludedInInput = (value["cachedInputIncludedInInput"] as? JsonPrimitive)?.booleanOrNull,
        reasoningOutputTokens = count("reasoningOutputTokens"),
        partial = (value["partial"] as? JsonPrimitive)?.booleanOrNull ?: false,
    )
}
