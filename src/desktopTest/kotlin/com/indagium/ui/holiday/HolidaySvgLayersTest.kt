@file:Suppress("MagicNumber")

package com.indagium.ui.holiday

import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.skiaCanvas
import androidx.compose.ui.graphics.toPixelMap
import com.indagium.ui.holiday.xmas.XMAS_COMPANION
import com.indagium.ui.holiday.xmas.XMAS_TITLE
import org.jetbrains.skia.Data
import org.jetbrains.skia.svg.SVGDOM
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** The layered-art painter: animation matrices, still poses, text, the resources and the generic title-bar scene. */
class HolidaySvgLayersTest {
    private val identity = listOf(1f, 0f, 0f, 1f, 0f, 0f)

    private fun companion(t: Float, still: Boolean = false) = renderBitmap(240, 240) { drawHolidayLayers("xmas", XMAS_COMPANION, t, still) }

    @Test fun anAnimatedArtMovesBetweenTwoTimes() {
        assertNotNull(differingBounds(companion(.3f), companion(1.9f)), "ears, nose, eyes and snow move")
    }

    @Test fun theStillFrameIsStableAndSteadier() {
        assertNull(differingBounds(companion(HOLIDAY_STILL_TIME, still = true), companion(7f, still = true)), "still does not depend on the clock")
        assertNull(differingBounds(companion(1f, still = true), companion(1f, still = true)))
        assertNotNull(differingBounds(companion(1f, still = true), companion(1f)), "the still pose is not a moving frame")
    }

    @Test fun aLayerWithAnAnimationMovesOnlyItsOwnRegion() {
        val nose = HolidayLayerAnim("s-nose", 120f, 178f, identity)
        val base = listOf(HolidayArtLayer.Svg("companion-00"), HolidayArtLayer.Svg("companion-06"))
        val animated = listOf(HolidayArtLayer.Svg("companion-00"), HolidayArtLayer.Svg("companion-06", listOf(nose)))
        val still = renderBitmap(240, 240) { drawHolidayLayers("xmas", base, 0f, false) }
        val moved = renderBitmap(240, 240) { drawHolidayLayers("xmas", animated, .7f, false) }
        val bounds = assertNotNull(differingBounds(still, moved), "the nose pulses")
        assertTrue(Rect(70f, 130f, 170f, 226f).let { it.contains(bounds.topLeft) && it.contains(bounds.bottomRight) }, "only around the nose: $bounds")
    }

    @Test fun anAnimationOpacityFadesTheLayerAndAHiddenLayerDrawsNothing() {
        val layers = listOf(HolidayArtLayer.Svg("companion-06"))
        val full = renderBitmap(240, 240) { drawHolidayLayers("xmas", layers, 0f, true) }
        val blinking = listOf(HolidayArtLayer.Svg("companion-06", listOf(HolidayLayerAnim("s-light", 0f, 0f, identity))))
        val faded = renderBitmap(240, 240) { drawHolidayLayers("xmas", blinking, .8f, false) }
        val hidden = renderBitmap(240, 240) {
            drawHolidayLayers("xmas", listOf(HolidayArtLayer.Svg("companion-06", listOf(HolidayLayerAnim("s-rise", 0f, 0f, identity)))), 0f, false)
        }
        assertTrue(paintedPixels(full) > 0)
        assertTrue(paintedPixels(faded) in (paintedPixels(full) * 9 / 10)..paintedPixels(full), "the same shape at .35 alpha")
        val brightest = { img: ImageBitmap -> img.toPixelMap().let { p -> (0 until 240).maxOf { y -> (0 until 240).maxOf { x -> p[x, y].alpha } } } }
        assertTrue(brightest(faded) < brightest(full) - .3f, "faded ${brightest(faded)} vs ${brightest(full)}")
        assertEquals(0, paintedPixels(hidden), "s-rise starts invisible")
    }

    @Test fun anUnknownClassLeavesTheLayerStill() {
        val still = renderBitmap(240, 240) { drawHolidayLayers("xmas", listOf(HolidayArtLayer.Svg("companion-06")), 0f, false) }
        val odd = renderBitmap(240, 240) {
            drawHolidayLayers("xmas", listOf(HolidayArtLayer.Svg("companion-06", listOf(HolidayLayerAnim("s-nope", 0f, 0f, identity)))), 1.3f, false)
        }
        assertNull(differingBounds(still, odd))
    }

    @Test fun theAnimationMatrixKeepsItsOriginFixedInTheRoot() {
        val turn = HolidayAnimFrame(rotateDeg = 90f)
        val flat = holidayAnimMatrix(HolidayLayerAnim("x", 5f, 5f, identity), turn)
        flat.map(5.0, 5.0).let {
            assertEquals(5.0, it.first, 1e-6)
            assertEquals(5.0, it.second, 1e-6)
        }
        flat.map(10.0, 5.0).let {
            assertEquals(5.0, it.first, 1e-6)
            assertEquals(10.0, it.second, 1e-6)
        }
        // A parent that moves and doubles: the origin's image in the root stays where it is.
        val parent = listOf(2f, 0f, 0f, 2f, 50f, 20f)
        val matrix = holidayAnimMatrix(HolidayLayerAnim("x", 5f, 5f, parent), HolidayAnimFrame(rotateDeg = 33f, scaleX = 1.4f, scaleY = .6f))
        matrix.map(60.0, 30.0).let {
            assertEquals(60.0, it.first, 1e-6)
            assertEquals(30.0, it.second, 1e-6)
        }
        // A pure translation in the child is the same translation scaled by the parent.
        val shifted = holidayAnimMatrix(HolidayLayerAnim("x", 0f, 0f, parent), HolidayAnimFrame(translateX = 3f, translateY = -4f))
        shifted.map(0.0, 0.0).let {
            assertEquals(6.0, it.first, 1e-6)
            assertEquals(-8.0, it.second, 1e-6)
        }
        val round = HolidayAffine.of(parent) * assertNotNull(HolidayAffine.of(parent).inverse())
        assertEquals(1.0, round.a, 1e-9)
        assertEquals(0.0, round.e, 1e-9)
    }

    private fun text(
        s: String,
        gradient: HolidayTextGradient? = null,
        fill: Color? = Color.Red,
        stroke: Color? = null,
        anchor: HolidayTextAnchor = HolidayTextAnchor.START,
        alpha: Float = 1f,
        families: List<String> = listOf("sans-serif"),
        x: Float = 20f,
    ) = HolidayArtLayer.Text(s, x, 60f, identity, 48f, families, 700, false, fill, gradient, stroke, if (stroke != null) 3f else 0f, true, alpha, anchor)

    private fun render(layer: HolidayArtLayer) = renderBitmap(400, 100) { drawHolidayLayers("xmas", listOf(layer), 0f, true) }

    @Test fun textDrawsWithAPlainFillAndWithAGradient() {
        val plain = render(text("Ag2"))
        assertTrue(paintedPixels(plain) > 200, "plain fill: ${paintedPixels(plain)}")
        val gradient = HolidayTextGradient(0f, 0f, 1f, 0f, listOf(0f to Color.Blue, 1f to Color.Green))
        val painted = render(text("Ag2", gradient = gradient, fill = null))
        assertTrue(paintedPixels(painted) > 200, "gradient fill: ${paintedPixels(painted)}")
        val pixels = painted.toPixelMap()
        val opaque = (0 until 400).flatMap { x -> (0 until 100).filter { y -> pixels[x, y].alpha > .9f }.map { x } }
        val left = pixels[opaque.min(), (0 until 100).first { pixels[opaque.min(), it].alpha > .9f }]
        val right = pixels[opaque.max(), (0 until 100).first { pixels[opaque.max(), it].alpha > .9f }]
        assertTrue(left.blue > left.green && right.green > right.blue, "blue on the left, green on the right: $left $right")
    }

    @Test fun textStrokesUnderOrOverTheFill() {
        val stroked = render(text("W", fill = Color.Red, stroke = Color.Blue))
        val fillOnly = render(text("W"))
        assertTrue(paintedPixels(stroked) > paintedPixels(fillOnly), "the stroke widens the glyph")
        // Paint order stroke: the fill covers the inner half of the stroke, so the glyph centre stays red.
        val pixels = stroked.toPixelMap()
        val reds = (0 until 400).sumOf { x -> (0 until 100).count { y -> pixels[x, y].let { it.alpha > .9f && it.red > .8f && it.blue < .2f } } }
        val blues = (0 until 400).sumOf { x -> (0 until 100).count { y -> pixels[x, y].let { it.alpha > .9f && it.blue > .8f && it.red < .2f } } }
        assertTrue(reds > 100 && blues > 50, "red fill $reds, blue outline $blues")
    }

    @Test fun textAnchorsAroundItsX() {
        fun inkRange(layer: HolidayArtLayer): Pair<Int, Int> {
            val p = render(layer).toPixelMap()
            val xs = (0 until 400).filter { x -> (0 until 100).any { y -> p[x, y].alpha > 0f } }
            return xs.min() to xs.max()
        }
        val start = inkRange(text("MMM", x = 200f))
        val middle = inkRange(text("MMM", x = 200f, anchor = HolidayTextAnchor.MIDDLE))
        val end = inkRange(text("MMM", x = 200f, anchor = HolidayTextAnchor.END))
        assertTrue(start.first >= 195 && start.second > 260, "start begins at x: $start")
        assertTrue(middle.first < 195 && middle.second > 205, "middle straddles x: $middle")
        assertTrue(end.second <= 205 && end.first < 140, "end finishes at x: $end")
    }

    @Test fun aTextClipStaysPutWhileTheTextMoves() {
        val scrolling = listOf(HolidayLayerAnim("s-scroll", 0f, 0f, identity))
        val clipped = text("MMMMMMMM").copy(anims = scrolling, clip = Rect(0f, 30f, 400f, 45f), clipRadius = 2f)

        fun inkRows(t: Float) = renderBitmap(400, 100) { drawHolidayLayers("xmas", listOf(clipped), t, false) }.toPixelMap().let { p ->
            (0 until 100).filter { y -> (0 until 400).any { x -> p[x, y].alpha > 0f } }
        }
        val rows = inkRows(0f)
        assertTrue(rows.isNotEmpty() && rows.all { it in 30..44 }, "ink only inside the clip: ${rows.minOrNull()}..${rows.maxOrNull()}")
        val unclipped = renderBitmap(400, 100) { drawHolidayLayers("xmas", listOf(text("MMMMMMMM")), 0f, false) }.toPixelMap()
        val outside = (0 until 100).filter { y -> y < 30 || y >= 45 }
        assertTrue(outside.any { y -> (0 until 400).any { x -> unclipped[x, y].alpha > 0f } }, "the same text pokes out without the clip")
        // 1.01 s into the 9-step scroll the text sits 18 up: still clipped to the same rows.
        assertTrue(inkRows(1.01f).all { it in 30..44 })
    }

    @Test fun textAlphaAndFontFamiliesApply() {
        val solid = render(text("MM"))
        val half = render(text("MM", alpha = .5f))
        assertEquals(paintedPixels(solid), paintedPixels(half))
        assertTrue(half.toPixelMap().let { p -> (0 until 400).all { x -> (0 until 100).all { y -> p[x, y].alpha <= .51f } } }, "half the alpha")
        val mono = render(text("iiii", families = listOf("monospace")))
        val sans = render(text("iiii", families = listOf("Helvetica Neue", "sans-serif")))
        assertTrue(paintedPixels(mono) > 0 && paintedPixels(sans) > 0)
        assertTrue(differingBounds(mono, sans) != null, "a monospace 'iiii' is wider than a proportional one")
        assertEquals(listOf("Menlo"), holidayFontCandidates(listOf("Menlo")))
        assertTrue("Menlo" in holidayFontCandidates(listOf("ui-monospace", "monospace")))
        assertTrue(holidayFontCandidates(listOf("system-ui", "-apple-system")).containsAll(listOf("Helvetica Neue", "Segoe UI")))
        assertTrue("Georgia" in holidayFontCandidates(listOf("serif")) && "Apple Chancery" in holidayFontCandidates(listOf("cursive")))
    }

    @Test fun aGradientEllipseBecomesASoftPathNotAFlatDisc() {
        val svg = """<svg xmlns="http://www.w3.org/2000/svg" width="100" height="100"><defs><radialGradient id="g">""" +
            """<stop offset="0" stop-color="#000" stop-opacity=".3"/><stop offset="1" stop-color="#000" stop-opacity="0"/></radialGradient></defs>""" +
            """<ellipse cx="50" cy="50" rx="48" ry="30" fill="url(#g)" transform="translate(0 1)"/><ellipse cx="5" cy="5" rx="2" ry="2" fill="red"/></svg>"""
        val fixed = holidayEllipsesAsPaths(svg)
        assertTrue("<path d=\"M2.0 50.0A48.0 30.0 0 1 0 98.0 50.0A48.0 30.0 0 1 0 2.0 50.0Z\" fill=\"url(#g)\" transform=\"translate(0 1)\"/>" in fixed, fixed)
        assertTrue("""<ellipse cx="5" cy="5" rx="2" ry="2" fill="red"/>""" in fixed, "a plain ellipse is left alone")
        val dom = SVGDOM(Data.makeFromBytes(fixed.toByteArray()))
        val pixels = renderBitmap(100, 100) { drawIntoCanvas { dom.render(it.skiaCanvas) } }.toPixelMap()
        assertTrue(pixels[50, 51].alpha in .2f..0.35f, "the centre is the first stop's .3: ${pixels[50, 51].alpha}")
        assertTrue(pixels[80, 51].alpha < pixels[50, 51].alpha - .1f, "and it fades towards the rim: ${pixels[80, 51].alpha}")
        val shadow = renderBitmap(300, 420) { drawHolidayLayers("newyear", listOf(HolidayArtLayer.Svg("picture-05")), 0f, true) }.toPixelMap()
        assertTrue(shadow[150, 394].alpha < .5f, "the picture's shadow is translucent: ${shadow[150, 394].alpha}")
    }

    @Test fun theGeneratedTextLayersRenderInk() {
        val texts = GENERATED_ARTS.flatMap { art -> art.allLayers().filterIsInstance<HolidayArtLayer.Text>().map { art.folder to it } }
        assertTrue(texts.isNotEmpty())
        texts.forEach { (folder, layer) ->
            // Without its clip: the scrolling terminal's lower lines start below the screen and only scroll into view later.
            val image = renderBitmap(1700, 450) { drawHolidayLayers(folder, listOf(layer.copy(clip = null)), 0f, true) }
            // A text that the design hides at rest (alpha 0 or a rise) draws none; everything else leaves ink somewhere on the sheet.
            if (layer.alpha > 0f && layer.anims.none { it.cls.startsWith("s-rise") }) assertTrue(paintedPixels(image) > 0, "'${layer.text}' in $folder renders")
        }
    }

    @Test fun everyAnimationClassTheArtUsesIsKnown() {
        val used = GENERATED_ARTS.flatMap { it.allLayers() }.flatMap { it.anims }.map { it.cls }.toSet()
        assertTrue(used.isNotEmpty())
        used.forEach { assertTrue(isHolidayAnimClass(it), "$it has no keyframes") }
    }

    @Test fun everyGeneratedSvgResourceLoadsAndTheArtReferencesOnlyExistingOnes() {
        for (art in GENERATED_ARTS) {
            val dir = File("src/desktopMain/resources/holiday/${art.folder}")
            val names = dir.listFiles { f -> f.extension == "svg" }.orEmpty().map { it.nameWithoutExtension }
            assertTrue(names.isNotEmpty(), "${art.folder} has resources")
            for (name in names) {
                val dom = cachedHolidaySvg(art.folder, name)
                val size = dom.root?.let { it.width.value to it.height.value }
                assertTrue(size != null && size.first > 0f && size.second > 0f, "${art.folder}/$name has a size")
            }
            val referenced = art.allLayers().filterIsInstance<HolidayArtLayer.Svg>().map { it.name }.toSet()
            referenced.forEach { assertTrue(it in names, "${art.folder}/$it is referenced but missing") }
            // Every generated layer draws without error, in its own size.
            art.allLayers().filterIsInstance<HolidayArtLayer.Svg>().forEach { layer ->
                renderBitmap(320, 440) { drawHolidayLayers(art.folder, listOf(layer), 1f, true) }
            }
        }
    }

    @Test fun theArtsDrawSomethingInBothHalvesOfAFrame() {
        for (art in GENERATED_ARTS) {
            val picture = renderBitmap(300, 420) { drawHolidayLayers(art.folder, art.picture, 1f, false) }
            val companion = renderBitmap(240, 240) { drawHolidayLayers(art.folder, art.companion, 1f, false) }
            assertTrue(paintedPixels(picture) > 2000, "${art.folder} picture ${paintedPixels(picture)}")
            assertTrue(paintedPixels(companion) > 2000, "${art.folder} companion ${paintedPixels(companion)}")
        }
    }

    private val bandHeight = 30

    private fun band(items: List<HolidayBandItem>, width: Float, dark: Boolean = true) = HolidayBandScene("xmas", items).let { scene ->
        val layout = scene.layout(Rect(0f, 0f, width, bandHeight.toFloat()))
        layout to renderBitmap(width.toInt(), bandHeight) { scene.drawFrame(this, layout, dark, HOLIDAY_STILL_TIME) }
    }

    @Test fun aWideBandShowsTheBandPartsAndANarrowOneDropsTheLeftmostButKeepsTheBackdrops() {
        val parts = XMAS_TITLE.mapNotNull { it.partRef }.distinct()
        assertTrue(parts.isNotEmpty() && XMAS_TITLE.any { it.partKey == null }, "xmas has backdrop and part items")
        val (wide, wideImage) = band(XMAS_TITLE, 1600f)
        assertEquals(parts.size, wide.elementBounds.size, "every part fits a 1600 wide band")
        assertTrue(paintedPixels(wideImage) > 500)
        val (narrow, narrowImage) = band(XMAS_TITLE, 380f)
        assertTrue(narrow.elementBounds.size < wide.elementBounds.size, "a narrow band drops parts whole")
        assertTrue(narrow.elementBounds.all { it.left >= 0f && it.right <= 380f })
        assertTrue(paintedPixels(narrowImage) > 0, "the backdrops still draw in a narrow band")
        assertTrue(paintedPixels(narrowImage) < paintedPixels(wideImage))
    }

    @Test fun theBandDrawsItsDarkAndLightLayersAndAnimatesOnlyWithAnimatedItems() {
        val (_, dark) = band(XMAS_TITLE, 1600f, dark = true)
        val (_, light) = band(XMAS_TITLE, 1600f, dark = false)
        assertNotNull(differingBounds(dark, light), "the title uses theme colours")
        GENERATED_ARTS.filter { it.title.isNotEmpty() }.forEach { art ->
            val scene = HolidayBandScene(art.folder, art.title)
            val anyAnim = art.title.any { it.dark.anims.isNotEmpty() || it.light.anims.isNotEmpty() }
            assertEquals(anyAnim, scene.animated, art.folder)
            val layout = scene.layout(Rect(0f, 0f, 1600f, 30f))

            fun at(t: Float) = renderBitmap(1600, 30) { scene.drawFrame(this, layout, true, t) }
            if (anyAnim) assertNotNull(differingBounds(at(.3f), at(.8f)), "${art.folder}'s title moves")
            assertNull(differingBounds(at(HOLIDAY_STILL_TIME), renderBitmap(1600, 30) { scene.draw(this, layout, true) }), "${art.folder} still == draw")
        }
        assertEquals(0, HolidayBandScene("xmas", emptyList()).layout(Rect(0f, 0f, 1600f, 30f)).elementBounds.size)
    }
}
