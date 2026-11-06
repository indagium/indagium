package com.indagium.storage

import com.indagium.model.Filter
import com.indagium.utils.RegexEvaluationContext
import com.indagium.utils.passesFilter

/** Uses the existing filter implementation and one regex budget for an entire query scan. */
internal suspend fun StorageQuery.scanFilter(
    filter: Filter,
    processNames: Map<Int, String> = emptyMap(),
    tagPids: Map<String, Set<Int>> = emptyMap(),
    regexContext: RegexEvaluationContext = RegexEvaluationContext(),
): Long {
    return scan { entry -> passesFilter(entry, filter, processNames, regexContext, tagPids) }
}
