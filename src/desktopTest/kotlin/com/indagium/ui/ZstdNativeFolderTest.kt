package com.indagium.ui

import java.io.File
import kotlin.io.path.createTempDirectory
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ZstdNativeFolderTest {
    private val root = createTempDirectory("zstd-native-folder").toFile()
    private val properties = HashMap<String, String>()
    private val now = 1_000_000_000L

    @AfterTest
    fun tearDown() {
        root.deleteRecursively()
    }

    private fun configure(directory: File) =
        configureZstdTempFolder(directory, now, properties::get) { key, value -> properties[key] = value }

    private fun leftover(directory: File, name: String, ageMs: Long) =
        File(directory, name).also {
            it.writeBytes(ByteArray(8))
            assertTrue(it.setLastModified(now - ageMs))
        }

    @Test
    fun createsTheFolderAndPointsZstdAtItWithoutTouchingTheRealTempDir() {
        val folder = File(root, "native-cache/zstd")

        assertTrue(configure(folder))

        assertTrue(folder.isDirectory)
        assertEquals(folder.absolutePath, properties[ZSTD_TEMP_FOLDER_PROPERTY])
    }

    @Test
    fun sweepsOnlyOldZstdNativeCopies() {
        val folder = File(root, "zstd").also { it.mkdirs() }
        val old = leftover(folder, "libzstd-jni-1.5.7-4123.dll", ZSTD_STALE_NATIVE_FILE_MS + 1_000)
        val oldSo = leftover(folder, "libzstd-jni-1.5.7-4999.so", ZSTD_STALE_NATIVE_FILE_MS * 3)
        val fresh = leftover(folder, "libzstd-jni-1.5.7-4555.dll", 1_000)
        val unrelated = leftover(folder, "keep-me.dll", ZSTD_STALE_NATIVE_FILE_MS * 3)

        assertTrue(configure(folder))

        assertFalse(old.exists())
        assertFalse(oldSo.exists())
        assertTrue(fresh.exists(), "a file a concurrent process may be unpacking right now is kept")
        assertTrue(unrelated.exists(), "only libzstd-jni* files are ever removed")
    }

    @Test
    fun anExplicitUserSettingWinsAndNothingIsCreatedOrDeleted() {
        val folder = File(root, "zstd")
        properties[ZSTD_TEMP_FOLDER_PROPERTY] = "/custom/zstd"

        assertFalse(configure(folder))

        assertEquals("/custom/zstd", properties[ZSTD_TEMP_FOLDER_PROPERTY])
        assertFalse(folder.exists())
    }

    @Test
    fun anUncreatableFolderLeavesTheDefaultBehaviour() {
        val blocker = File(root, "blocker").also { it.writeText("a file where the folder should be") }

        assertFalse(configure(File(blocker, "zstd")))

        assertNull(properties[ZSTD_TEMP_FOLDER_PROPERTY])
    }
}
