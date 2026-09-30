package com.indagium

import com.indagium.utils.MAX_IMAGE_BYTES
import com.indagium.utils.MAX_IMAGE_DIMENSION
import com.indagium.utils.downscaleAndEncodeJpeg
import java.awt.Color
import java.awt.image.BufferedImage
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import javax.imageio.ImageIO
import kotlin.test.Test
import kotlin.test.assertNull
import kotlin.test.assertTrue

// Storage guard for AnnBlock.Image (see AnnotationManager.addImageBlock) — a raw video-frame
// capture must come out downscaled, re-encoded as JPEG, and under the size cap before it's ever
// stored in an Annotations tree that autosave re-serializes on every debounced edit.
class ImageDownscaleTest {
    private companion object {
        // Arbitrary distinct multipliers/stripe width for a synthetic gradient — a flat fill
        // compresses to near-nothing under any JPEG quality and would let a broken size-cap loop
        // pass by accident.
        const val STRIPE_WIDTH = 4
        const val RED_MULTIPLIER = 37
        const val GREEN_MULTIPLIER = 91
        const val BLUE_MULTIPLIER = 53
        const val COLOR_MOD = 255
    }

    private fun syntheticPngBytes(width: Int, height: Int, type: Int = BufferedImage.TYPE_INT_RGB): ByteArray {
        val img = BufferedImage(width, height, type)
        val g = img.createGraphics()
        for (x in 0 until width step STRIPE_WIDTH) {
            g.color = Color((x * RED_MULTIPLIER) % COLOR_MOD, (x * GREEN_MULTIPLIER) % COLOR_MOD, (x * BLUE_MULTIPLIER) % COLOR_MOD)
            g.fillRect(x, 0, STRIPE_WIDTH, height)
        }
        g.dispose()
        return ByteArrayOutputStream().use { out -> ImageIO.write(img, "png", out); out.toByteArray() }
    }

    @Test
    fun downscalesOversizedImageWithinDimensionAndByteCaps() {
        val big = syntheticPngBytes(3840, 2160) // a 4K frame, well over MAX_IMAGE_DIMENSION
        val encoded = downscaleAndEncodeJpeg(big)

        assertTrue(encoded != null, "a valid source image must always encode to something")
        requireNotNull(encoded)
        assertTrue(encoded.size <= MAX_IMAGE_BYTES, "encoded size ${encoded.size} must be within the cap")

        val decoded = ImageIO.read(ByteArrayInputStream(encoded))
        assertTrue(decoded != null, "the encoded output must itself be a valid, decodable JPEG")
        requireNotNull(decoded)
        assertTrue(decoded.width <= MAX_IMAGE_DIMENSION, "width ${decoded.width} must not exceed the cap")
        assertTrue(decoded.height <= MAX_IMAGE_DIMENSION, "height ${decoded.height} must not exceed the cap")
    }

    @Test
    fun leavesAnAlreadySmallImagesDimensionsUnscaled() {
        val small = syntheticPngBytes(200, 100)
        val encoded = downscaleAndEncodeJpeg(small)

        requireNotNull(encoded)
        val decoded = ImageIO.read(ByteArrayInputStream(encoded))
        requireNotNull(decoded)
        assertTrue(decoded.width == 200 && decoded.height == 100, "an already-small image shouldn't be upscaled or cropped")
    }

    @Test
    fun returnsNullForBytesThatAreNotAnImageAtAll() {
        val garbage = byteArrayOf(1, 2, 3, 4, 5)
        assertNull(downscaleAndEncodeJpeg(garbage))
    }

    // Regression test for the capture-screenshot "could not add it to Notes" bug: an Android
    // screencap decodes as ARGB/TYPE_4BYTE_ABGR (confirmed against a real emulator capture, which
    // came back as 8-bit RGBA), and JPEG has no alpha channel — an ImageIO JPEG writer given that
    // BufferedImage type directly returns false/throws rather than encoding. This must still
    // succeed because downscaleAndEncodeJpeg flattens to opaque RGB (see ImageDownscale.kt's
    // toOpaqueRgb) before it ever reaches the JPEG encoder.
    @Test
    fun encodesAnArgbSourceWithAnAlphaChannel() {
        val argb = syntheticPngBytes(1920, 1080, BufferedImage.TYPE_INT_ARGB)
        val encoded = downscaleAndEncodeJpeg(argb)

        assertTrue(encoded != null, "an ARGB source must still encode — JPEG has no alpha channel, so the alpha must be flattened away, not fail the encode")
        requireNotNull(encoded)
        val decoded = ImageIO.read(ByteArrayInputStream(encoded))
        assertTrue(decoded != null, "the encoded output must itself be a valid, decodable JPEG")
    }

    @Test
    fun enforcesCustomByteCapByReducingDimensionsFurther() {
        // The initial 1280px re-encode is deliberately too large for this small cap. A storage
        // guard must keep reducing it rather than returning the old implementation's best effort.
        val cap = 5 * 1024
        val encoded = downscaleAndEncodeJpeg(syntheticPngBytes(1920, 1080), maxBytes = cap)

        requireNotNull(encoded)
        assertTrue(encoded.size <= cap, "stored image ${encoded.size} must never exceed cap $cap")
    }
}
