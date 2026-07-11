package com.openlog.source

import java.io.File
import java.nio.file.Path
import kotlin.io.path.createDirectories
import kotlin.io.path.createTempDirectory
import kotlin.io.path.writeText
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

private fun Path.write(relPath: String, content: String) {
    val target = resolve(relPath)
    target.parent?.createDirectories()
    target.writeText(content)
}

class SourceIndexStoreTest {
    private fun sampleIndex(dir: Path): SourceIndex {
        dir.write(
            "Foo.kt",
            """
            package demo

            class Foo {
                fun bar(id: Int) {
                    Log.d("TagX", "User ${'$'}id logged in")
                }
            }
            """.trimIndent(),
        )
        dir.write(
            "Baz.java",
            """
            class Baz {
                void qux() {
                    Log.i("TagY", "Startup complete");
                }
            }
            """.trimIndent(),
        )
        return SourceIndexer.build(listOf(dir.toFile()))
    }

    @Test
    fun roundTripsFullIndexThroughSaveAndLoad() {
        val dir = createTempDirectory("openlog-src-store")
        val index = sampleIndex(dir)
        assertEquals(2, index.sites.size)
        assertEquals(2, index.fileMeta.size)

        val storeFile = File(createTempDirectory("openlog-src-store-out").toFile(), "source-index")
        SourceIndexStore.save(index, storeFile)
        val loaded = SourceIndexStore.load(storeFile)

        assertEquals(index, loaded)
    }

    @Test
    fun loadOfMissingFileReturnsNull() {
        val dir = createTempDirectory("openlog-src-store-missing").toFile()
        val missing = File(dir, "does-not-exist")

        assertNull(SourceIndexStore.load(missing))
    }

    @Test
    fun loadOfEmptyFileReturnsNull() {
        val dir = createTempDirectory("openlog-src-store-empty").toFile()
        val empty = File(dir, "source-index").apply { writeText("") }

        assertNull(SourceIndexStore.load(empty))
    }

    @Test
    fun loadWithWrongMagicReturnsNull() {
        val dir = createTempDirectory("openlog-src-store-magic").toFile()
        val file = File(dir, "source-index").apply {
            writeText("not-the-right-magic\nversion\t1\n")
        }

        assertNull(SourceIndexStore.load(file))
    }

    @Test
    fun loadWithMismatchedVersionReturnsNull() {
        val dir = createTempDirectory("openlog-src-store-version").toFile()
        val srcDir = createTempDirectory("openlog-src-store-version-src")
        val index = sampleIndex(srcDir).copy(version = SOURCE_INDEX_VERSION + 1)
        val file = File(dir, "source-index")
        SourceIndexStore.save(index, file)

        assertNull(SourceIndexStore.load(file))
    }

    @Test
    fun malformedLineIsSkippedWithoutThrowing() {
        val dir = createTempDirectory("openlog-src-store-garbled").toFile()
        val file = File(dir, "source-index").apply {
            writeText(
                buildString {
                    appendLine("openLog2-source-index-v1")
                    appendLine("version\t$SOURCE_INDEX_VERSION")
                    appendLine("builtAt\t1000")
                    // Truncated site line (missing fields) — must be skipped, not thrown.
                    appendLine("site\tonly-two\tfields")
                    // A well-formed root line after the garbled one must still parse.
                    appendLine("root\t${java.util.Base64.getUrlEncoder().withoutPadding().encodeToString("/tmp/src".toByteArray())}")
                },
            )
        }

        val loaded = SourceIndexStore.load(file)

        assertTrue(loaded != null)
        assertEquals(0, loaded.sites.size)
        assertEquals(listOf("/tmp/src"), loaded.roots)
    }
}
