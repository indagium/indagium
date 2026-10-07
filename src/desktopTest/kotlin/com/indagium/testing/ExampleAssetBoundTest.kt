package com.indagium.testing

import com.indagium.testing.run.readBoundedExampleAsset
import java.io.File
import kotlin.io.path.createTempDirectory
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class ExampleAssetBoundTest {
    @Test
    fun assetReadRejectsFileThatGrewPastTheLimitAfterValidation() {
        val directory = createTempDirectory("example-asset-bound").toFile()
        try {
            val file = File(directory, "large.png")
            file.writeBytes(ByteArray(16 * 1024 * 1024 + 1))
            assertNull(readBoundedExampleAsset(file))
            file.writeBytes(byteArrayOf(1, 2, 3))
            assertEquals(3, readBoundedExampleAsset(file)?.size)
        } finally {
            directory.deleteRecursively()
        }
    }
}
