package com.indagium.ui

import com.indagium.diagram3.Seq3AwtTextMetrics
import com.indagium.diagram3.Seq3Document
import com.indagium.diagram3.Seq3LayoutOptions
import com.indagium.diagram3.Seq3RasterTheme
import com.indagium.diagram3.layoutSeq3
import com.indagium.diagram3.renderSeq3
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.awt.image.BufferedImage
import java.util.concurrent.atomic.AtomicInteger

// The preview images of the New tab's Diagrams section in Grid view. A thumbnail is the diagram's saved snapshot laid out by the same
// Seq3Layout the canvas and the PNG export use, painted by Seq3Raster straight at the thumbnail's size (the raster scales the Graphics2D
// transform, so no full-size image is ever made), on a background thread, and kept in a small LRU keyed by the diagram's id and its
// updatedAt. The cache is pure and generic so it is testable without images; the composable side is in HomeDiagramsSection.kt.

/** The most pixels wide / tall a thumbnail gets; a card shows it scaled to fit, so a diagram never costs more than ~250 KB. */
internal const val HOME_DIAGRAM_THUMB_MAX_W_PX = 320
internal const val HOME_DIAGRAM_THUMB_MAX_H_PX = 200

/** How many thumbnails stay in memory at once (the library holds at most 128 diagrams; a tile rarely shows more than a screenful). */
internal const val HOME_DIAGRAM_THUMB_CACHE_ENTRIES = 64

/**
 * What identifies one thumbnail: the diagram, the revision of it that was drawn and [variant], the one thing besides the diagram that
 * changes the picture (the app theme). A rename or any edit moves [updatedAt], so the stale picture is never found again.
 */
internal data class HomeDiagramThumbKey(val id: String, val updatedAt: Long, val variant: Int = 0)

// LinkedHashMap's own default load factor, named only because detekt flags an inline literal.
private const val LRU_LOAD_FACTOR = 0.75f

/**
 * A bounded, thread-safe LRU of thumbnails. Putting a key drops every other entry of the same diagram, so an edited diagram's old
 * picture goes at once instead of waiting to be evicted. [maxEntries] below 1 is treated as 1.
 */
internal class HomeDiagramThumbnailCache<V : Any>(maxEntries: Int = HOME_DIAGRAM_THUMB_CACHE_ENTRIES) {
    private val limit = maxEntries.coerceAtLeast(1)
    private val entries = object : LinkedHashMap<HomeDiagramThumbKey, V>(limit, LRU_LOAD_FACTOR, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<HomeDiagramThumbKey, V>?): Boolean = size > limit
    }

    /** The cached picture, which also makes it the most recently used; null when there is none. */
    fun get(key: HomeDiagramThumbKey): V? = synchronized(entries) { entries[key] }

    fun put(key: HomeDiagramThumbKey, value: V) {
        synchronized(entries) {
            entries.keys.removeAll { it.id == key.id && it != key }
            entries[key] = value
        }
    }

    /** Drops every picture of the diagram [id] (it was deleted). */
    fun invalidate(id: String) {
        synchronized(entries) { entries.keys.removeAll { it.id == id } }
    }

    fun clear() {
        synchronized(entries) { entries.clear() }
    }

    val size: Int get() = synchronized(entries) { entries.size }

    /** The keys, least recently used first. */
    fun keys(): List<HomeDiagramThumbKey> = synchronized(entries) { entries.keys.toList() }
}

/** The scale that fits a [layoutWidth] x [layoutHeight] diagram into [maxWidthPx] x [maxHeightPx]; never above 1, so a small diagram stays crisp. */
internal fun homeDiagramThumbnailScale(layoutWidth: Double, layoutHeight: Double, maxWidthPx: Int, maxHeightPx: Int): Float {
    if (!(layoutWidth > 0.0) || !(layoutHeight > 0.0)) return 1f
    return minOf(1.0, maxWidthPx / layoutWidth, maxHeightPx / layoutHeight).toFloat()
}

private val thumbnailTextMetrics by lazy { Seq3AwtTextMetrics() }

/**
 * Paints [document] at thumbnail size, or null when there is nothing to show (no lifelines) or drawing failed. Headless and safe off the
 * UI thread: it only lays out and rasterizes. Never throws, except to pass a cancellation on.
 */
internal fun renderHomeDiagramThumbnail(
    document: Seq3Document,
    theme: Seq3RasterTheme,
    maxWidthPx: Int = HOME_DIAGRAM_THUMB_MAX_W_PX,
    maxHeightPx: Int = HOME_DIAGRAM_THUMB_MAX_H_PX,
): BufferedImage? = try {
    val layout = layoutSeq3(document, Seq3LayoutOptions(thumbnailTextMetrics))
    if (layout.lifelines.isEmpty()) {
        null
    } else {
        renderSeq3(layout, theme, homeDiagramThumbnailScale(layout.width, layout.height, maxWidthPx, maxHeightPx)).image
    }
} catch (cancelled: CancellationException) {
    throw cancelled
} catch (_: Exception) {
    null
}

/** The process-wide thumbnail cache and the loader that fills it. */
internal object HomeDiagramThumbnails {
    val cache = HomeDiagramThumbnailCache<BufferedImage>()
    private val renders = AtomicInteger()

    /** Test hook: runs on the background thread right before a thumbnail is drawn (lets a test hold the render to see the placeholder). */
    @Volatile
    internal var beforeRenderForTest: (() -> Unit)? = null

    /** How many thumbnails were drawn (cache misses), for tests. */
    internal val renderCount: Int get() = renders.get()

    /**
     * The thumbnail of [key]: from the cache, else drawn on a background thread from the diagram [id]'s saved snapshot ([documentOf] hands
     * it over) and cached. Null when the diagram is gone, empty or could not be drawn.
     */
    suspend fun load(
        key: HomeDiagramThumbKey,
        theme: (Seq3Document) -> Seq3RasterTheme,
        documentOf: (String) -> Seq3Document?,
    ): BufferedImage? {
        cache.get(key)?.let { return it }
        return withContext(Dispatchers.Default) {
            cache.get(key)?.let { return@withContext it }
            beforeRenderForTest?.invoke()
            val document = documentOf(key.id) ?: return@withContext null
            renders.incrementAndGet()
            renderHomeDiagramThumbnail(document, theme(document))?.also { cache.put(key, it) }
        }
    }

    internal fun clearForTest() {
        cache.clear()
        renders.set(0)
        beforeRenderForTest = null
    }
}
