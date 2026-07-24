package com.openlog.utils

import java.awt.Image
import java.awt.RenderingHints
import java.awt.image.BufferedImage
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import javax.imageio.IIOImage
import javax.imageio.ImageIO
import javax.imageio.ImageWriteParam

// Storage guard for AnnBlock.Image (ui/AnnotationManager.kt addImageBlock): a raw captured frame
// (e.g. a full-resolution PNG from video/VideoPlayerController.grabCurrentFrame) re-serializes
// through autosave on every debounced edit (ui/AutosaveCodec.kt persistedSnapshot()), so it must
// be downscaled and re-encoded before it's ever stored in an Annotations tree.
const val MAX_IMAGE_DIMENSION = 1280
const val MAX_IMAGE_BYTES = 400 * 1024 // 400 KB cap on the re-encoded JPEG

// Quality steps tried in order, highest first — stops at the first step that fits maxBytes; the
// lowest step's output is returned even if it still doesn't fit, rather than failing the whole
// capture over a soft size target.
private val JPEG_QUALITY_STEPS = listOf(0.75f, 0.6f, 0.45f, 0.3f)

/**
 * Decodes [sourceBytes] as an image (any ImageIO-readable format — PNG from grabCurrentFrame),
 * downscales so neither dimension exceeds [maxDimension], and re-encodes as JPEG, stepping
 * quality down through [JPEG_QUALITY_STEPS] until the result fits [maxBytes]. Returns null only
 * if [sourceBytes] doesn't decode as an image at all. Pure (no file/network I/O) — safe to unit
 * test with a synthetic BufferedImage encoded to bytes.
 */
fun downscaleAndEncodeJpeg(
    sourceBytes: ByteArray,
    maxDimension: Int = MAX_IMAGE_DIMENSION,
    maxBytes: Int = MAX_IMAGE_BYTES,
): ByteArray? {
    val source = runCatching { ImageIO.read(ByteArrayInputStream(sourceBytes)) }.getOrNull() ?: return null
    val scaled = scaleToFit(source, maxDimension)
    var best: ByteArray? = null
    for (quality in JPEG_QUALITY_STEPS) {
        val encoded = encodeJpeg(scaled, quality) ?: continue
        best = encoded
        if (encoded.size <= maxBytes) return encoded
    }
    return best
}

private fun scaleToFit(source: BufferedImage, maxDimension: Int): BufferedImage {
    val longestSide = maxOf(source.width, source.height)
    val scale = (maxDimension.toFloat() / longestSide).coerceAtMost(1f)
    if (scale >= 1f) return toOpaqueRgb(source)
    val targetW = (source.width * scale).toInt().coerceAtLeast(1)
    val targetH = (source.height * scale).toInt().coerceAtLeast(1)
    val smoothScaled = source.getScaledInstance(targetW, targetH, Image.SCALE_SMOOTH)
    val out = BufferedImage(targetW, targetH, BufferedImage.TYPE_INT_RGB)
    val g = out.createGraphics()
    g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR)
    g.drawImage(smoothScaled, 0, 0, null)
    g.dispose()
    return out
}

// JPEG has no alpha channel, and ImageIO's JPEG writer throws on an ARGB source — video frames
// decode opaque anyway, but grabCurrentFrame's PNG round-trip can still yield one.
private fun toOpaqueRgb(source: BufferedImage): BufferedImage {
    if (source.type == BufferedImage.TYPE_INT_RGB) return source
    val out = BufferedImage(source.width, source.height, BufferedImage.TYPE_INT_RGB)
    val g = out.createGraphics()
    g.drawImage(source, 0, 0, null)
    g.dispose()
    return out
}

private fun encodeJpeg(image: BufferedImage, quality: Float): ByteArray? = runCatching {
    val writer = ImageIO.getImageWritersByFormatName("jpg").next()
    try {
        val param = writer.defaultWriteParam.apply {
            compressionMode = ImageWriteParam.MODE_EXPLICIT
            compressionQuality = quality
        }
        val out = ByteArrayOutputStream()
        ImageIO.createImageOutputStream(out).use { ios ->
            writer.output = ios
            writer.write(null, IIOImage(image, null, null), param)
        }
        out.toByteArray()
    } finally {
        writer.dispose()
    }
}.getOrNull()
