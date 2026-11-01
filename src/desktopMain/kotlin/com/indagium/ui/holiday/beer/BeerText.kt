package com.indagium.ui.holiday.beer

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.asComposePath
import org.jetbrains.skia.Font
import org.jetbrains.skia.FontEdging
import org.jetbrains.skia.FontHinting
import org.jetbrains.skia.FontMgr
import org.jetbrains.skia.FontStyle
import org.jetbrains.skia.Typeface
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger

// Beer time's lettering (the swinging pub sign, the LAGER label, the neon "IT'S BEER TIME") is drawn as glyph OUTLINES, not with
// Canvas.drawString. A string drawn into a transformed canvas is rasterised glyph by glyph with hinting and pixel snapping, so while the sign
// swings every letter lands on its own sub-pixel and the word wobbles. An outline is one ordinary path: it goes through the same transform
// as the board behind it, so the sign stays rigid. The outlines are built once (Skia font with sub-pixel positioning on and hinting off) and cached.

/** The typefaces the lettering uses: the sign's bold serif and the neon's rounded bold sans. */
internal enum class BeerTypeface { SIGN, ROUNDED }

/** One piece of lettering as a path whose origin is the start of its baseline, and how wide it is (the last letter's spacing excluded). */
internal class BeerTextOutline(val path: Path, val width: Float)

internal object BeerTextPaths {
    private data class Key(val face: BeerTypeface, val text: String, val size: Float, val letterSpacing: Float)

    private val cache = ConcurrentHashMap<Key, BeerTextOutline>()
    private val builds = AtomicInteger()

    /** How many outlines were built so far (a repeated request for the same lettering is served from the cache and does not count). */
    val buildCount: Int get() = builds.get()

    private fun typeface(face: BeerTypeface): Typeface? = when (face) {
        BeerTypeface.SIGN -> FontMgr.default.matchFamilyStyle("Georgia", FontStyle.BOLD) ?: FontMgr.default.matchFamilyStyle("serif", FontStyle.BOLD)
        BeerTypeface.ROUNDED -> FontMgr.default.matchFamilyStyle("Arial Rounded MT Bold", FontStyle.BOLD)
            ?: FontMgr.default.matchFamilyStyle("Arial", FontStyle.BOLD)
            ?: FontMgr.default.matchFamilyStyle("sans-serif", FontStyle.BOLD)
    }

    /** The outline of [text] at [size] in [face], with [letterSpacing] added after every letter. Cached: the same arguments give the same instance. */
    fun outline(text: String, face: BeerTypeface, size: Float, letterSpacing: Float = 0f): BeerTextOutline =
        cache.getOrPut(Key(face, text, size, letterSpacing)) {
            builds.incrementAndGet()
            build(text, typeface(face), size, letterSpacing)
        }

    private fun build(text: String, typeface: Typeface?, size: Float, letterSpacing: Float): BeerTextOutline {
        val font = Font(typeface, size).apply {
            isSubpixel = true
            isLinearMetrics = true
            hinting = FontHinting.NONE
            edging = FontEdging.ANTI_ALIAS
        }
        try {
            val glyphs = font.getStringGlyphs(text)
            val advances = font.getWidths(glyphs)
            val path = Path()
            var x = 0f
            glyphs.forEachIndexed { index, glyph ->
                font.getPath(glyph)?.let { path.addPath(it.asComposePath(), Offset(x, 0f)) }
                x += advances[index] + letterSpacing
            }
            return BeerTextOutline(path, (x - letterSpacing).coerceAtLeast(0f))
        } finally {
            font.close()
        }
    }
}
