package com.indagium.testing

import com.indagium.ui.decodeBoundedPreviewImage
import java.awt.image.BufferedImage
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.RandomAccessFile
import javax.imageio.ImageIO
import kotlin.io.path.createTempDirectory
import kotlin.test.Test
import kotlin.test.assertNotNull
import kotlin.test.assertNull

class TestImagePreviewTest {
    @Test
    fun previewChecksSourcePixelLimitBeforeSkiaDecode() {
        val image = BufferedImage(16, 12, BufferedImage.TYPE_INT_RGB)
        val output = ByteArrayOutputStream()
        assertNotNull(ImageIO.write(image, "png", output))
        assertNull(decodeBoundedPreviewImage(output.toByteArray(), maxPixels = 100))
        assertNotNull(decodeBoundedPreviewImage(output.toByteArray(), maxPixels = 1_000))
    }

    @Test
    fun previewRefusesOversizeFileBeforeReadingIt() {
        val dir = createTempDirectory("bounded-image-preview").toFile()
        try {
            val file = File(dir, "too-large.png")
            RandomAccessFile(file, "rw").use { it.setLength(16L * 1024 * 1024 + 1) }
            assertNull(decodeBoundedPreviewImage(file))
        } finally {
            dir.deleteRecursively()
        }
    }
}
