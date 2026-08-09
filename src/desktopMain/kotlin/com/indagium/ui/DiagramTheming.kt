package com.indagium.ui

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.toComposeImageBitmap
import com.indagium.diagram.DiagramTheme
import com.indagium.diagram.RenderedDiagram
import com.indagium.diagram.SeqDiagram
import com.indagium.diagram.renderSequenceDiagram
import com.indagium.diagram.toPngBytes
import kotlin.math.roundToInt

// ── Compose theme -> renderer theme, and a cache in front of the renderer ────────────────────
//
// com.indagium.diagram is deliberately Compose-free (it has to run from the export path and the
// MCP tool handlers, neither of which has a composition), so the mapping from this app's 20
// ThemeColors presets onto the renderer's plain java.awt palette lives here, on the UI side of
// that boundary.

private const val COLOR_CHANNEL_MAX_F = 255f
private const val COLOR_CHANNEL_MIN = 0
private const val COLOR_CHANNEL_MAX = 255

// roundToInt(), not toInt(): Compose stores channels as Floats, and a truncating conversion lands
// a channel one short of the literal the preset was declared with — the same precision trap
// SeqDiagramBuilder.colorArgb() and IndagiumToolOperations.colorToHex already document.
private fun Color.toAwt(): java.awt.Color = java.awt.Color(
    (red * COLOR_CHANNEL_MAX_F).roundToInt().coerceIn(COLOR_CHANNEL_MIN, COLOR_CHANNEL_MAX),
    (green * COLOR_CHANNEL_MAX_F).roundToInt().coerceIn(COLOR_CHANNEL_MIN, COLOR_CHANNEL_MAX),
    (blue * COLOR_CHANNEL_MAX_F).roundToInt().coerceIn(COLOR_CHANNEL_MIN, COLOR_CHANNEL_MAX),
    (alpha * COLOR_CHANNEL_MAX_F).roundToInt().coerceIn(COLOR_CHANNEL_MIN, COLOR_CHANNEL_MAX),
)

/**
 * Maps the active [ThemeColors] onto the renderer's palette, so a diagram matches whichever of the
 * app's presets the user is on — including the PNG written at export time, which is rendered from
 * this same theme rather than a fixed light one.
 *
 * `td` (dim text) rather than `br` (border) backs the lifelines: a lifeline is a faint guide, and
 * on several presets `br` is nearly invisible against `bg`. `seq1` backs frames because a frame IS
 * a sequence group, so it inherits the palette's own sequence colour; DANGER_RED backs error notes
 * for the same reason crash rows use it in LogViewer.
 */
fun ThemeColors.toDiagramTheme(): DiagramTheme = DiagramTheme(
    background = bg.toAwt(),
    lifeline = td.toAwt(),
    participantFill = p2.toAwt(),
    participantBorder = br.toAwt(),
    participantText = tx.toAwt(),
    arrow = ts.toAwt(),
    label = tx.toAwt(),
    frame = seq1.toAwt(),
    note = ts.toAwt(),
    errorAccent = DANGER_RED.toAwt(),
)

/**
 * Memoizes rendered diagrams.
 *
 * Two callers need this and neither can afford to re-rasterize on demand:
 * - the Notes panel, which would otherwise re-render on every recomposition;
 * - [AppState]'s annotation export, which runs on EVERY debounced note edit (see
 *   autoExportAnnotations) and writes a PNG per diagram note. Without a cache, typing in an
 *   unrelated text block would re-rasterize every diagram in the document on a 400 ms cadence.
 *
 * Keyed by the diagram's own identity plus the theme and scale, so switching theme or DPI
 * correctly produces a fresh raster instead of a stale one. Bounded and FIFO-evicted: a diagram
 * raster is a few hundred KB, and an analysis realistically holds a handful.
 */
object DiagramRenderCache {
    private const val MAX_ENTRIES = 24

    private data class Key(val diagram: SeqDiagram, val theme: DiagramTheme, val scale: Float)

    // LinkedHashMap in access order with removeEldestEntry == a bounded LRU; synchronized because
    // the export path runs on ioScope while the panel reads from the composition thread.
    private val cache = object : LinkedHashMap<Key, RenderedDiagram>(MAX_ENTRIES, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<Key, RenderedDiagram>?): Boolean = size > MAX_ENTRIES
    }

    // A Compose bitmap is a display artifact, not an export artifact.  Keeping it separately
    // means an expanded Notes card never goes through BufferedImage -> PNG -> Skia -> ImageBitmap
    // (and never repeats that expensive encode/decode on recomposition).  Callers intentionally
    // create these entries from a background dispatcher; see AnnotationPanel.
    private val displayCache = object : LinkedHashMap<Key, DiagramDisplay>(MAX_ENTRIES, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<Key, DiagramDisplay>?): Boolean = size > MAX_ENTRIES
    }

    // PNG is only needed for clipboard/export.  Annotation auto-export can ask for the same
    // diagram after every unrelated note edit, so retaining the encoded bytes prevents a fresh
    // ImageIO pass unless the model, theme, or scale actually changed.
    private val pngCache = object : LinkedHashMap<Key, ByteArray>(MAX_ENTRIES, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<Key, ByteArray>?): Boolean = size > MAX_ENTRIES
    }
    private val renderMissCount = java.util.concurrent.atomic.AtomicInteger()

    fun render(diagram: SeqDiagram, theme: DiagramTheme, scale: Float = 2f): RenderedDiagram {
        val key = Key(diagram, theme, scale)
        synchronized(cache) { cache[key] }?.let { return it }
        // Rasterize OUTSIDE the lock: a large diagram takes tens of milliseconds, and holding the
        // lock across it would stall the composition thread behind an export-triggered render.
        renderMissCount.incrementAndGet()
        val rendered = renderSequenceDiagram(diagram, theme, scale)
        synchronized(cache) { cache[key] = rendered }
        return rendered
    }

    fun display(diagram: SeqDiagram, theme: DiagramTheme, scale: Float = 2f): DiagramDisplay {
        val key = Key(diagram, theme, scale)
        synchronized(displayCache) { displayCache[key] }?.let { return it }
        val rendered = render(diagram, theme, scale)
        // BufferedImage has a native Compose conversion on Desktop.  Unlike the old PNG bridge it
        // preserves the renderer's pixels without allocating a temporary encoded image.
        val display = DiagramDisplay(rendered, rendered.image.toComposeImageBitmap())
        synchronized(displayCache) { displayCache[key] = display }
        return display
    }

    fun pngBytes(diagram: SeqDiagram, theme: DiagramTheme, scale: Float = 2f): ByteArray {
        val key = Key(diagram, theme, scale)
        synchronized(pngCache) { pngCache[key] }?.let { return it }
        val png = render(diagram, theme, scale).toPngBytes()
        synchronized(pngCache) { pngCache[key] = png }
        return png
    }

    /** Drops everything. Only needed if a future change makes DiagramTheme's equality unreliable;
     *  kept so that escape hatch exists rather than being discovered under a bug report. */
    fun clear() {
        synchronized(cache) { cache.clear() }
        synchronized(displayCache) { displayCache.clear() }
        synchronized(pngCache) { pngCache.clear() }
    }

    internal fun renderMissCountForTest(): Int = renderMissCount.get()

    internal fun clearForTest() {
        clear()
        renderMissCount.set(0)
    }
}

/** Immutable display pair.  The [bitmap] is deliberately cached beside renderer hit targets so
 * a Notes card can draw and click the exact same raster without PNG encode/decode churn. */
data class DiagramDisplay(val rendered: RenderedDiagram, val bitmap: ImageBitmap)

/** Compatibility helper for older UI call sites. New code should use [DiagramRenderCache.display]
 * so conversion is cached. */
fun RenderedDiagram.toComposeBitmap(): ImageBitmap = image.toComposeImageBitmap()
