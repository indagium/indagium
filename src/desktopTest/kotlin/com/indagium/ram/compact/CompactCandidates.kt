@file:Suppress("MagicNumber", "ComplexCondition") // Fixed timestamp positions and optional field coverage.

package com.indagium.ram.compact

import com.indagium.model.LogEntry
import com.indagium.model.LogLevel
import com.indagium.utils.TS_UNKNOWN
import com.indagium.utils.parseMillisOfDay

// Test-only candidates: conversion makes the compatibility boundary explicit. These are not
// substitutes for LogEntry's constructor, copy, destructuring or persistence API.
internal data class OptionalMetadata(
    val sourceTag: String?, val ecu: String?, val app: String?, val context: String?,
    val type: String?, val timestamp: Long?, val timestampSource: String?,
) {
    companion object {
        fun from(e: LogEntry): OptionalMetadata? = if (
            e.sourceTag == null && e.dltEcuId == null && e.dltAppId == null &&
            e.dltContextId == null && e.dltMessageType == null && e.dltTimestamp == null &&
            e.dltTimestampSource == null
        ) null else OptionalMetadata(e.sourceTag, e.dltEcuId, e.dltAppId, e.dltContextId,
            e.dltMessageType, e.dltTimestamp, e.dltTimestampSource)
    }
}

internal data class MetadataCandidate(
    val id: Int, val ts: String, val level: LogLevel, val tag: String, val msg: String,
    val pid: Int, val tid: Int, val dayOfYear: Short, val metadata: OptionalMetadata?,
) {
    constructor(e: LogEntry) : this(e.id, e.ts, e.level, e.tag, e.msg, e.pid, e.tid,
        e.dayOfYear, OptionalMetadata.from(e))

    fun toEntry() = LogEntry(id, ts, level, tag, msg, pid, tid, metadata?.sourceTag,
        metadata?.ecu, metadata?.app, metadata?.context, metadata?.type, metadata?.timestamp,
        metadata?.timestampSource, dayOfYear)
}

// A single primitive plus nullable fallback is embedded directly in the row, not boxed per row.
// -1 denotes fallback; range-checked canonical text round-trips with exactly three digits.
internal fun encodeCanonicalTimestamp(text: String): Int {
    if (text.length != 12 || text[8] != '.' || text.substring(9).any { it !in '0'..'9' }) return -1
    val millis = parseMillisOfDay(text)
    return if (millis == TS_UNKNOWN) -1 else millis.toInt()
}

internal fun decodeCanonicalTimestamp(millis: Int): String {
    require(millis in 0 until 86_400_000)
    val hour = millis / 3_600_000
    val minute = millis / 60_000 % 60
    val second = millis / 1_000 % 60
    val fraction = millis % 1_000
    val chars = charArrayOf('0', '0', ':', '0', '0', ':', '0', '0', '.', '0', '0', '0')
    chars[0] += hour / 10; chars[1] += hour % 10
    chars[3] += minute / 10; chars[4] += minute % 10
    chars[6] += second / 10; chars[7] += second % 10
    chars[9] += fraction / 100; chars[10] += fraction / 10 % 10; chars[11] += fraction % 10
    return String(chars)
}

internal data class TimestampCandidate(
    val id: Int, val timestampMillis: Int, val fallback: String?, val level: LogLevel,
    val tag: String, val msg: String, val pid: Int, val tid: Int, val sourceTag: String?,
    val ecu: String?, val app: String?, val context: String?, val type: String?,
    val dltTimestamp: Long?, val timestampSource: String?, val dayOfYear: Short,
) {
    constructor(e: LogEntry) : this(e.id, encodeCanonicalTimestamp(e.ts),
        e.ts.takeIf { encodeCanonicalTimestamp(it) < 0 }, e.level, e.tag, e.msg, e.pid, e.tid,
        e.sourceTag, e.dltEcuId, e.dltAppId, e.dltContextId, e.dltMessageType,
        e.dltTimestamp, e.dltTimestampSource, e.dayOfYear)

    fun text(): String = fallback ?: decodeCanonicalTimestamp(timestampMillis)

    fun toEntry() = LogEntry(id, text(), level, tag, msg, pid, tid, sourceTag, ecu, app,
        context, type, dltTimestamp, timestampSource, dayOfYear)
}
