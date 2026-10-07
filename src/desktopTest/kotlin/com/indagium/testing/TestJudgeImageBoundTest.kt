package com.indagium.testing

import com.indagium.testing.run.boundedJudgeImage
import com.indagium.testing.store.MAX_GOLDEN_IMAGE_BYTES
import com.indagium.testing.store.readBoundedTestAsset
import java.awt.image.BufferedImage
import java.io.ByteArrayOutputStream
import java.io.File
import javax.imageio.ImageIO
import kotlin.io.path.createTempDirectory
import kotlin.test.Test
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class TestJudgeImageBoundTest {
    @Test
    fun judgeImagesDecodeStrictlyAndRejectOversizedOrMalformedFiles() {
        assertNull(boundedJudgeImage(byteArrayOf(0, 1, 2, 3)))
        assertNull(boundedJudgeImage(ByteArray(MAX_GOLDEN_IMAGE_BYTES.toInt() + 1)))
        val png = ByteArrayOutputStream().use { output ->
            ImageIO.write(BufferedImage(12, 8, BufferedImage.TYPE_INT_RGB), "png", output)
            output.toByteArray()
        }
        val result = boundedJudgeImage(png)
        assertNotNull(result)
        assertTrue(result!!.mimeType == "image/jpeg")
        assertTrue(result.bytes.size <= 2 * 1024 * 1024)
    }

    @Test
    fun assetFileReadIsBoundedEvenWhenTheFileGrowsAfterImport() {
        val directory = createTempDirectory("judge-asset-bound").toFile()
        try {
            val image = File(directory, "example.png")
            image.writeBytes(ByteArray(32))
            assertTrue(readBoundedTestAsset(image)!!.size == 32)
            image.writeBytes(ByteArray(MAX_GOLDEN_IMAGE_BYTES.toInt() + 1))
            assertNull(readBoundedTestAsset(image))
        } finally {
            directory.deleteRecursively()
        }
    }
}
