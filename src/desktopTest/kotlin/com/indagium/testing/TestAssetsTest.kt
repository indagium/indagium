package com.indagium.testing

import com.indagium.testing.store.MAX_GOLDEN_IMAGE_BYTES
import com.indagium.testing.store.StoreResult
import com.indagium.testing.store.importGoldenImage
import com.indagium.testing.store.resolveTestAsset
import com.indagium.testing.store.testAssetDir
import java.io.File
import kotlin.io.path.createTempDirectory
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Golden-screenshot images: copied under the suite's asset folder with a safe, unused name; resolved without escaping it. */
class TestAssetsTest {
    private val root = createTempDirectory("test-assets").toFile()
    private val suiteId = "suite-1"

    private fun image(name: String, bytes: Int = 16): File = File(root, "src").apply { mkdirs() }.let { File(it, name).apply { writeBytes(ByteArray(bytes)) } }

    @Test
    fun anImageIsCopiedIntoTheSuitesAssetFolderAndResolvesBack() {
        val source = image("Home Screen (1).PNG")

        val result = importGoldenImage(root, suiteId, source)

        assertIs<StoreResult.Ok<String>>(result)
        assertEquals("Home_Screen__1.png", result.value)
        val stored = resolveTestAsset(root, suiteId, result.value)
        assertNotNull(stored)
        assertTrue(stored.isFile && stored.parentFile == testAssetDir(root, suiteId).canonicalFile)
        assertEquals(source.length(), stored.length())
    }

    @Test
    fun aSecondImageWithTheSameNameGetsADifferentFileInsteadOfOverwriting() {
        val first = importGoldenImage(root, suiteId, image("home.png")) as StoreResult.Ok
        val second = importGoldenImage(root, suiteId, image("home.png")) as StoreResult.Ok

        assertEquals("home.png", first.value)
        assertEquals("home-2.png", second.value)
    }

    @Test
    fun onlySmallPngJpgOrWebpImagesAreAccepted() {
        assertIs<StoreResult.Invalid>(importGoldenImage(root, suiteId, image("notes.txt")))
        assertIs<StoreResult.Invalid>(importGoldenImage(root, suiteId, File(root, "missing.png")))
        val big = image("big.png", bytes = (MAX_GOLDEN_IMAGE_BYTES + 1).toInt())
        assertIs<StoreResult.Invalid>(importGoldenImage(root, suiteId, big))
        assertIs<StoreResult.Ok<String>>(importGoldenImage(root, suiteId, image("a.jpeg")))
        assertIs<StoreResult.Ok<String>>(importGoldenImage(root, suiteId, image("b.webp")))
    }

    @Test
    fun anUnsafeSuiteIdIsRefusedAndPathsCannotEscapeTheAssetFolder() {
        assertIs<StoreResult.NotFound>(importGoldenImage(root, "../escape", image("a.png")))
        assertNull(resolveTestAsset(root, "../escape", "a.png"))
        assertNull(resolveTestAsset(root, suiteId, "../../library.json"))
        assertNull(resolveTestAsset(root, suiteId, "/etc/passwd"))
        assertNull(resolveTestAsset(root, suiteId, ""))
        assertNotNull(resolveTestAsset(root, suiteId, "sub/shot.png"))
    }
}
