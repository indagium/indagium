package com.indagium.ui

import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.toComposeImageBitmap
import com.indagium.debug.encodeBoundedDeviceScreen
import java.io.File

private const val MAX_PREVIEW_IMAGE_BYTES = 16 * 1024 * 1024
private const val MAX_PREVIEW_IMAGE_PIXELS = 36_000_000L

/** Decode only after checking encoded size and source dimensions; never hand an unbounded image to Skia. */
internal fun decodeBoundedPreviewImage(bytes: ByteArray, maxPixels: Long = MAX_PREVIEW_IMAGE_PIXELS): ImageBitmap? = runCatching {
    require(bytes.size in 1..MAX_PREVIEW_IMAGE_BYTES) { "Image exceeds the safe preview size." }
    val bounded = encodeBoundedDeviceScreen(bytes, maxSourcePixels = maxPixels)
    org.jetbrains.skia.Image.makeFromEncoded(bounded.bytes).toComposeImageBitmap()
}.getOrNull()

/** Bounded file reader for library and report thumbnails. Call on an IO dispatcher. */
internal fun decodeBoundedPreviewImage(file: File, maxPixels: Long = MAX_PREVIEW_IMAGE_PIXELS): ImageBitmap? {
    if (!file.isFile || file.length() !in 1..MAX_PREVIEW_IMAGE_BYTES.toLong()) return null
    val bytes = runCatching {
        file.inputStream().use { it.readNBytes(MAX_PREVIEW_IMAGE_BYTES + 1).takeIf { data -> data.size in 1..MAX_PREVIEW_IMAGE_BYTES } }
    }.getOrNull() ?: return null
    return decodeBoundedPreviewImage(bytes, maxPixels)
}
