package com.indagium.ui

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.toComposeImageBitmap
import com.indagium.diagram3.RenderedSeq3
import com.indagium.diagram3.Seq3AwtTextMetrics
import com.indagium.diagram3.Seq3Document
import com.indagium.diagram3.Seq3Layout
import com.indagium.diagram3.Seq3LayoutOptions
import com.indagium.diagram3.Seq3RasterTheme
import com.indagium.diagram3.layoutSeq3
import com.indagium.diagram3.renderSeq3
import com.indagium.diagram3.toPngBytes
import kotlin.math.roundToInt

// ── Compose theme -> diagram3 raster theme, and a cache in front of layout + render ────────────
//
// Mirrors the deleted ui/DiagramTheming.kt's split for v1/v2: com.indagium.diagram3 is deliberately
// Compose-free (Seq3Model.kt's own header — it must run headless from the export path and the MCP
// build_sequence_diagram handler, neither of which has a composition), so mapping this app's ~20
// ThemeColors presets onto the package's plain-ARGB Seq3RasterTheme lives here, on the UI side of
// that boundary.
//
// DO NOT modify diagram3/Seq3Raster.kt for this file's sake: Seq3RasterTheme.DEFAULT_LIGHT is its
// own doc's words a standalone fallback "so a test/tool can render without one" — not a target this
// mapping needs to reproduce pixel-for-pixel.

private const val CHANNEL_MAX_F = 255f
private const val CHANNEL_MAX = 255
private const val CHANNEL_MIN = 0

// The raster paints these two as translucent washes, not solid fills — see Seq3Raster.kt's
// paintFragment/paintMessageNoteRow/paintNoteBox. Chosen to land in the same visual register as
// that file's own DEFAULT_LIGHT constants (0x12/0xFF and 0x33/0xFF respectively) without copying
// them verbatim — DEFAULT_LIGHT only ever had to look right on one preset, this has to work on ~20.
private const val ALPHA_FRAGMENT_WASH = 0.07f
private const val ALPHA_NOTE_FILL = 0.20f

/**
 * roundToInt(), not toInt(): a truncating float -> int conversion lands a channel one short of the
 * literal a preset was declared with — the exact trap `ui/DiagramTheming.kt`'s `toAwt()` documents
 * (and, one package further out, `SeqDiagramBuilder.colorArgb()`/`IndagiumToolOperations.
 * colorToHex()`). [alpha] defaults to the colour's own alpha channel so most call sites below don't
 * have to repeat `this.alpha`.
 */
private fun Color.toSeq3Argb(alpha: Float = this.alpha): Int {
    fun channel(v: Float) = (v * CHANNEL_MAX_F).roundToInt().coerceIn(CHANNEL_MIN, CHANNEL_MAX)
    return (channel(alpha) shl 24) or (channel(red) shl 16) or (channel(green) shl 8) or channel(blue)
}

/**
 * Maps the active [ThemeColors] onto [Seq3RasterTheme]. Reuses `ui/DiagramTheming.kt`'s
 * `toDiagramTheme()` rationale for which role backs which element rather than re-deriving it from
 * scratch — the same trade-offs still apply one package over:
 * - `lifeline` comes from `td` (dim text), not `br`: a lifeline is a faint guide, and on several
 *   presets `br` is nearly invisible against `bg` — identical reasoning to that function's own note.
 * - `headerFill`/`badgeBg` come from `p2` (this app's "raised chip" surface), so a lifeline header
 *   chip or a `×n` badge reads as sitting above the canvas instead of blending into it — the same
 *   contrast goal as that function's `participantFill = p2`.
 * - `fragmentBorder` comes from `seq1`: a `loop`/`alt`/`opt`/`par` fragment IS a sequence group,
 *   the same call as that function's `frame = seq1`.
 * - `noteFill`/`noteBorder` come from `seq2` (warm/amber in almost every preset — see [ThemeColors.
 *   warn]'s own derivation comment), landing a canvas note in the same warm family as `warn`/
 *   `warnBg` below rather than a cool, interactive-looking hue — both read as "needs your
 *   attention", just at different intensities.
 */
fun ThemeColors.toSeq3RasterTheme(): Seq3RasterTheme = Seq3RasterTheme(
    background = bg.toSeq3Argb(),
    lifeline = td.toSeq3Argb(),
    headerFill = p2.toSeq3Argb(),
    headerBorder = br.toSeq3Argb(),
    headerText = tx.toSeq3Argb(),
    arrow = ts.toSeq3Argb(),
    label = tx.toSeq3Argb(),
    badgeBg = p2.toSeq3Argb(),
    badgeText = ts.toSeq3Argb(),
    fragmentBorder = seq1.toSeq3Argb(),
    fragmentWash = seq1.toSeq3Argb(alpha = ALPHA_FRAGMENT_WASH),
    noteFill = seq2.toSeq3Argb(alpha = ALPHA_NOTE_FILL),
    noteBorder = seq2.toSeq3Argb(),
    noteText = tx.toSeq3Argb(),
    warn = warn.toSeq3Argb(),
    warnBg = warnBg.toSeq3Argb(),
)

/** Immutable display pair — mirrors [DiagramDisplay]: the [bitmap] is cached beside [rendered] so
 *  the canvas can draw the exact raster it hit-tests against, without a PNG encode/decode round trip. */
data class Seq3Display(val rendered: RenderedSeq3, val bitmap: ImageBitmap)

/**
 * Bounded LRU in front of [layoutSeq3] and [renderSeq3].
 *
 * Two independent caches, not one, because they vary on different things: [layoutSeq3]'s geometry
 * depends only on the document (and the fixed AWT metrics below), never on theme or scale, while
 * [renderSeq3]'s paint depends on a layout plus theme plus scale. Collapsing both into a single
 * (document, theme, scale) key would recompute the SAME geometry on every theme switch even though
 * nothing about it changed — defeating the whole point of `Seq3Layout.kt`'s own header comment:
 * "the single geometry source for both the Compose canvas and the PNG rasterizer" only holds if
 * every consumer, across every theme, can share the one cached layout.
 *
 * The render tier exists for the same reason [DiagramRenderCache] does (see that object's own
 * doc): the note-export path recomputes on EVERY debounced note edit (`AppState.
 * autoExportAnnotations`), and an unrelated edit anywhere else in the document must not re-rasterize
 * every open v3 diagram on a 400 ms cadence.
 */
object Seq3RenderCache {
    private const val MAX_ENTRIES = 24

    // LinkedHashMap's own default load factor — named here only because detekt flags an inline
    // literal, not because this cache tunes it away from the JDK default.
    private const val LOAD_FACTOR = 0.75f

    private fun <K, V> boundedLru(): MutableMap<K, V> =
        object : LinkedHashMap<K, V>(MAX_ENTRIES, LOAD_FACTOR, true) {
            override fun removeEldestEntry(eldest: MutableMap.MutableEntry<K, V>?): Boolean = size > MAX_ENTRIES
        }

    private data class RenderKey(val layout: Seq3Layout, val theme: Seq3RasterTheme, val scale: Float)

    // Shared across every layout() call in the process: Seq3AwtTextMetrics already caches its own
    // FontMetrics internally (see its own doc), so a second instance here would only duplicate that
    // cache for no benefit — one shared instance also matches layoutSeq3's own "measured exactly
    // once" discipline one level up.
    private val textMetrics = Seq3AwtTextMetrics()
    private val defaultLayoutOptions = Seq3LayoutOptions(textMetrics = textMetrics)

    private val layoutCache = boundedLru<Seq3Document, Seq3Layout>()
    private val rasterCache = boundedLru<RenderKey, RenderedSeq3>()
    private val displayCache = boundedLru<RenderKey, Seq3Display>()
    private val pngCache = boundedLru<RenderKey, ByteArray>()
    private val layoutMissCount = java.util.concurrent.atomic.AtomicInteger()
    private val renderMissCount = java.util.concurrent.atomic.AtomicInteger()

    /** Cached [layoutSeq3]. [options] defaults to the shared AWT-backed metrics — a caller only
     *  needs to pass its own when testing against a deterministic stub (see Seq3LayoutTest). */
    fun layout(document: Seq3Document, options: Seq3LayoutOptions = defaultLayoutOptions): Seq3Layout {
        synchronized(layoutCache) { layoutCache[document] }?.let { return it }
        layoutMissCount.incrementAndGet()
        val computed = layoutSeq3(document, options)
        synchronized(layoutCache) { layoutCache[document] = computed }
        return computed
    }

    fun render(layout: Seq3Layout, theme: Seq3RasterTheme, scale: Float = 2f): RenderedSeq3 {
        val key = RenderKey(layout, theme, scale)
        synchronized(rasterCache) { rasterCache[key] }?.let { return it }
        // Rasterize OUTSIDE the lock — see DiagramRenderCache.render's identical comment: a large
        // diagram takes tens of milliseconds, and holding the lock across it would stall the
        // composition thread behind an export-triggered render.
        renderMissCount.incrementAndGet()
        val rendered = renderSeq3(layout, theme, scale)
        synchronized(rasterCache) { rasterCache[key] = rendered }
        return rendered
    }

    fun display(layout: Seq3Layout, theme: Seq3RasterTheme, scale: Float = 2f): Seq3Display {
        val key = RenderKey(layout, theme, scale)
        synchronized(displayCache) { displayCache[key] }?.let { return it }
        val rendered = render(layout, theme, scale)
        val display = Seq3Display(rendered, rendered.image.toComposeImageBitmap())
        synchronized(displayCache) { displayCache[key] = display }
        return display
    }

    /** Convenience for a caller that only has the document — composes [layout] then [display] in
     *  one call, still going through both caches. */
    fun display(document: Seq3Document, theme: Seq3RasterTheme, scale: Float = 2f): Seq3Display =
        display(layout(document), theme, scale)

    fun pngBytes(layout: Seq3Layout, theme: Seq3RasterTheme, scale: Float = 2f): ByteArray {
        val key = RenderKey(layout, theme, scale)
        synchronized(pngCache) { pngCache[key] }?.let { return it }
        val png = render(layout, theme, scale).toPngBytes()
        synchronized(pngCache) { pngCache[key] = png }
        return png
    }

    /** Drops everything. Same escape-hatch rationale as [DiagramRenderCache.clear]: only needed if
     *  a future change makes one of the cache keys' equality unreliable. */
    fun clear() {
        synchronized(layoutCache) { layoutCache.clear() }
        synchronized(rasterCache) { rasterCache.clear() }
        synchronized(displayCache) { displayCache.clear() }
        synchronized(pngCache) { pngCache.clear() }
    }

    internal fun layoutMissCountForTest(): Int = layoutMissCount.get()

    internal fun renderMissCountForTest(): Int = renderMissCount.get()

    internal fun clearForTest() {
        clear()
        layoutMissCount.set(0)
        renderMissCount.set(0)
    }
}
