package com.openlog.utils

import com.openlog.model.*

// Flat model: every enabled SequenceDef can start a group. When multiple defs match the
// same entry, the one with the lowest priority number wins. Groups are non-nested.
fun computeSeqGroups(logData: List<LogEntry>, defs: List<SequenceDef>): List<SeqGroup> {
    val enabled = defs.filter { it.enabled }.sortedBy { it.priority }
    if (enabled.isEmpty()) return emptyList()

    fun matches(entry: LogEntry, def: SequenceDef): Boolean {
        val haystack = "${entry.tag} ${entry.msg}"
        return if (def.isRegex) runCatching { Regex(def.matchText, RegexOption.IGNORE_CASE).containsMatchIn(haystack) }.getOrElse { false }
        else haystack.contains(def.matchText, ignoreCase = true)
    }

    // Collect all boundary hits; first-priority def wins per entry
    data class Hit(val idx: Int, val def: SequenceDef)
    val hits: List<Hit> = logData.indices.mapNotNull { i ->
        val def = enabled.firstOrNull { matches(logData[i], it) } ?: return@mapNotNull null
        Hit(i, def)
    }
    if (hits.isEmpty()) return emptyList()

    return hits.mapIndexed { k, hit ->
        val endIdx = if (k + 1 < hits.size) hits[k + 1].idx else logData.size
        val plain  = (hit.idx + 1 until endIdx).map { logData[it].id }
        SeqGroup("sg_${hit.def.id}_${hit.idx}", logData[hit.idx].id, plain, emptyList(), hit.def.id)
    }
}
