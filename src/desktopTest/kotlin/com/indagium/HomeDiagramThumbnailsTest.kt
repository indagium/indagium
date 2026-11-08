package com.indagium

import com.indagium.diagram3.Seq3Document
import com.indagium.diagram3.Seq3Kind
import com.indagium.diagram3.Seq3Lifeline
import com.indagium.diagram3.Seq3Match
import com.indagium.diagram3.Seq3Message
import com.indagium.diagram3.Seq3Occurrence
import com.indagium.diagram3.Seq3RasterTheme
import com.indagium.ui.HOME_DIAGRAM_THUMB_MAX_H_PX
import com.indagium.ui.HOME_DIAGRAM_THUMB_MAX_W_PX
import com.indagium.ui.HomeDiagramThumbKey
import com.indagium.ui.HomeDiagramThumbnailCache
import com.indagium.ui.HomeDiagramThumbnails
import com.indagium.ui.homeDiagramThumbnailScale
import com.indagium.ui.renderHomeDiagramThumbnail
import kotlinx.coroutines.runBlocking
import java.awt.image.BufferedImage
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

/** The time of the first occurrence in milliseconds; each next one is one later. */
private const val BASE_TIME_MS = 1_000L

/** The Diagrams section's Grid previews: the LRU cache (pure), the thumbnail scale and the headless drawing. */
class HomeDiagramThumbnailsTest {
    private fun key(id: String, updatedAt: Long = 1L, variant: Int = 0) = HomeDiagramThumbKey(id, updatedAt, variant)

    private fun document(lifelines: Int = 2, messages: Int = 1): Seq3Document {
        val ls = (0 until lifelines).map { Seq3Lifeline("L$it", "Part$it", setOf("T$it"), it) }
        val ms = (0 until messages).map { index ->
            Seq3Message(
                id = "m$index",
                match = Seq3Match("T0", "call $index"),
                fromLifelineId = "L0",
                toLifelineId = "L${(index + 1) % lifelines}",
                labelTemplate = "call $index",
                kind = Seq3Kind.CALL,
                occurrences = listOf(Seq3Occurrence(index + 1, BASE_TIME_MS + index, "10:00:00.000", pid = 1, tid = 1, level = 'I', text = "call $index")),
            )
        }
        return Seq3Document(lifelines = ls, messages = if (lifelines >= 2) ms else emptyList())
    }

    // ---- cache --------------------------------------------------------------------------------------------------

    @Test
    fun aKeyIsTheIdTheRevisionAndTheVariant() {
        val cache = HomeDiagramThumbnailCache<String>()
        cache.put(key("a", 1, 0), "a1")
        assertEquals("a1", cache.get(key("a", 1, 0)))
        assertNull(cache.get(key("a", 2, 0)), "another revision is another picture")
        assertNull(cache.get(key("a", 1, 7)), "another variant (theme) is another picture")
        assertNull(cache.get(key("b", 1, 0)))
    }

    @Test
    fun puttingANewRevisionDropsTheOldPictureOfThatDiagramOnly() {
        val cache = HomeDiagramThumbnailCache<String>()
        cache.put(key("a", 1), "a-old")
        cache.put(key("b", 1), "b")
        cache.put(key("a", 2), "a-new")

        assertNull(cache.get(key("a", 1)), "an update invalidates the stale picture at once")
        assertEquals("a-new", cache.get(key("a", 2)))
        assertEquals("b", cache.get(key("b", 1)))
        assertEquals(2, cache.size)
    }

    @Test
    fun theLeastRecentlyUsedPictureIsEvictedAtTheBound() {
        val cache = HomeDiagramThumbnailCache<String>(maxEntries = 3)
        cache.put(key("a"), "a")
        cache.put(key("b"), "b")
        cache.put(key("c"), "c")
        assertEquals("a", cache.get(key("a")), "reading a makes it the newest")
        cache.put(key("d"), "d")

        assertEquals(3, cache.size)
        assertNull(cache.get(key("b")), "b was the least recently used")
        assertEquals(listOf(key("c"), key("a"), key("d")), cache.keys())
    }

    @Test
    fun theBoundNeverGoesBelowOneAndTheDefaultHoldsSixtyFour() {
        val one = HomeDiagramThumbnailCache<String>(maxEntries = 0)
        one.put(key("a"), "a")
        one.put(key("b"), "b")
        assertEquals(1, one.size)

        val cache = HomeDiagramThumbnailCache<Int>()
        repeat(100) { cache.put(key("d$it"), it) }
        assertEquals(64, cache.size)
        assertNull(cache.get(key("d0")))
        assertEquals(99, cache.get(key("d99")))
    }

    @Test
    fun invalidateAndClearEmptyTheCache() {
        val cache = HomeDiagramThumbnailCache<String>()
        cache.put(key("a"), "a")
        cache.put(key("b"), "b")
        cache.invalidate("a")
        assertNull(cache.get(key("a")))
        assertEquals("b", cache.get(key("b")))
        cache.clear()
        assertEquals(0, cache.size)
    }

    // ---- scale and drawing --------------------------------------------------------------------------------------

    @Test
    fun theScaleFitsTheBoxAndNeverEnlarges() {
        assertEquals(1f, homeDiagramThumbnailScale(160.0, 100.0, 320, 200))
        assertEquals(0.5f, homeDiagramThumbnailScale(640.0, 100.0, 320, 200))
        assertEquals(0.25f, homeDiagramThumbnailScale(100.0, 800.0, 320, 200))
        assertEquals(1f, homeDiagramThumbnailScale(0.0, 0.0, 320, 200))
        assertEquals(1f, homeDiagramThumbnailScale(Double.NaN, 10.0, 320, 200))
    }

    @Test
    fun aThumbnailIsDrawnHeadlessAtMostThumbnailSized() {
        val image = renderHomeDiagramThumbnail(document(lifelines = 6, messages = 40), Seq3RasterTheme.DEFAULT_LIGHT)
        assertNotNull(image)
        assertTrue(image.width in 1..HOME_DIAGRAM_THUMB_MAX_W_PX, "width ${image.width}")
        assertTrue(image.height in 1..HOME_DIAGRAM_THUMB_MAX_H_PX, "height ${image.height}")
        assertTrue(image.width == HOME_DIAGRAM_THUMB_MAX_W_PX || image.height == HOME_DIAGRAM_THUMB_MAX_H_PX, "a big diagram fills one side")
        assertTrue(hasDrawnPixels(image), "something other than the background was drawn")
    }

    @Test
    fun aSmallDiagramIsNotEnlarged() {
        val image = assertNotNull(renderHomeDiagramThumbnail(document(), Seq3RasterTheme.DEFAULT_LIGHT, maxWidthPx = 4000, maxHeightPx = 4000))
        assertTrue(image.width < 4000 && image.height < 4000, "${image.width}x${image.height} is the diagram's own size")
    }

    @Test
    fun aDiagramWithoutLifelinesHasNoThumbnail() {
        assertNull(renderHomeDiagramThumbnail(Seq3Document(), Seq3RasterTheme.DEFAULT_LIGHT))
    }

    private fun hasDrawnPixels(image: BufferedImage): Boolean {
        val first = image.getRGB(0, 0)
        for (y in 0 until image.height) for (x in 0 until image.width) if (image.getRGB(x, y) != first) return true
        return false
    }

    // ---- loader -------------------------------------------------------------------------------------------------

    @Test
    fun theLoaderDrawsOnceThenServesTheCacheAndRedrawsAnUpdatedDiagram() {
        HomeDiagramThumbnails.clearForTest()
        try {
            val doc = document(lifelines = 3, messages = 3)
            val theme = { _: Seq3Document -> Seq3RasterTheme.DEFAULT_LIGHT }
            val first = runBlocking { HomeDiagramThumbnails.load(key("x", 5), theme) { doc } }
            assertNotNull(first)
            assertEquals(1, HomeDiagramThumbnails.renderCount)

            val again = runBlocking { HomeDiagramThumbnails.load(key("x", 5), theme) { error("must come from the cache") } }
            assertSame(first, again)
            assertEquals(1, HomeDiagramThumbnails.renderCount, "the second request did not draw")

            val updated = runBlocking { HomeDiagramThumbnails.load(key("x", 6), theme) { doc } }
            assertNotNull(updated)
            assertEquals(2, HomeDiagramThumbnails.renderCount, "a new updatedAt draws again")
            assertNull(HomeDiagramThumbnails.cache.get(key("x", 5)), "and the old picture is gone")
        } finally {
            HomeDiagramThumbnails.clearForTest()
        }
    }

    @Test
    fun aMissingOrEmptyDiagramLoadsNothingAndIsNotCached() {
        HomeDiagramThumbnails.clearForTest()
        try {
            val theme = { _: Seq3Document -> Seq3RasterTheme.DEFAULT_LIGHT }
            assertNull(runBlocking { HomeDiagramThumbnails.load(key("gone"), theme) { null } })
            assertNull(runBlocking { HomeDiagramThumbnails.load(key("empty"), theme) { Seq3Document() } })
            assertEquals(0, HomeDiagramThumbnails.cache.size)
        } finally {
            HomeDiagramThumbnails.clearForTest()
        }
    }

    @Test
    fun aThumbnailCostsMillisecondsAndAFewHundredKilobytes() {
        val doc = document(lifelines = 8, messages = 60)
        renderHomeDiagramThumbnail(doc, Seq3RasterTheme.DEFAULT_LIGHT) // warm up fonts and the JIT
        val runs = 20
        val start = System.nanoTime()
        var image: BufferedImage? = null
        repeat(runs) { image = renderHomeDiagramThumbnail(doc, Seq3RasterTheme.DEFAULT_LIGHT) }
        val perMs = (System.nanoTime() - start) / 1e6 / runs
        val bytes = image!!.width * image!!.height * 4
        println("home diagram thumbnail: %.1f ms each, %d x %d px, %d KB".format(perMs, image!!.width, image!!.height, bytes / 1024))
        assertTrue(perMs < 500.0, "a thumbnail took $perMs ms")
        assertTrue(bytes <= HOME_DIAGRAM_THUMB_MAX_W_PX * HOME_DIAGRAM_THUMB_MAX_H_PX * 4)
    }
}
