package com.indagium.ui

import com.mikepenz.markdown.model.State

/**
 * Small LRU of already-parsed note Markdown, keyed by the exact render source.
 *
 * Only the active tab is composed, so every tab visit composes the notes panel from scratch and the
 * renderer would otherwise parse each block's text again (synchronously, to avoid the layout glide
 * described at [AnnotationMarkdownText]). A cached [State.Success] is rendered through the
 * renderer's `Markdown(state = ...)` overload, which is synchronous too, so the first frame keeps
 * its real height. Entries are immutable parse results (an AST plus the reference-link table of
 * that same text), so sharing one between composables is safe; the cache is process-wide and
 * bounded by entry count, and sources above [maxSourceChars] are never kept.
 */
internal class ParsedMarkdownCache(
    private val maxEntries: Int = ANNOTATION_MARKDOWN_CACHE_ENTRIES,
    private val maxSourceChars: Int = ANNOTATION_MARKDOWN_CACHE_SOURCE_CHARS,
) {
    private val lock = Any()
    private val entries = object : LinkedHashMap<String, State.Success>(INITIAL_CAPACITY, LOAD_FACTOR, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, State.Success>?): Boolean = size > maxEntries
    }

    var hits: Int = 0
        private set
    var misses: Int = 0
        private set

    val size: Int get() = synchronized(lock) { entries.size }

    fun get(renderSource: String): State.Success? = synchronized(lock) {
        entries[renderSource].also { if (it != null) hits++ else misses++ }
    }

    /** Remembers a successful parse; failures and oversized sources are ignored (they re-parse as before). */
    fun put(renderSource: String, state: State) {
        if (state !is State.Success || renderSource.length > maxSourceChars) return
        synchronized(lock) { entries[renderSource] = state }
    }

    private companion object {
        const val INITIAL_CAPACITY = 16
        const val LOAD_FACTOR = 0.75f
    }
}

private const val ANNOTATION_MARKDOWN_CACHE_ENTRIES = 256
private const val ANNOTATION_MARKDOWN_CACHE_SOURCE_CHARS = 16_384

/** The notes panel's, editor preview's and dialogs' shared parse cache. */
internal val ANNOTATION_MARKDOWN_CACHE = ParsedMarkdownCache()
