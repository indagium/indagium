package com.indagium.source

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
    fun syntheticCallbackMethodsAndCallsRoundTripThroughStore() {
        val sourceDir = createTempDirectory("openlog-src-store-callback-src")
        sourceDir.write(
            "Callback.kt",
            """
            package demo

            class Screen {
                fun start() {
                    api.enqueue(object : Callback<String> {
                        override fun onResponse() { Log.d("Http", "success") }
                        override fun onFailure() { Log.e("Http", "failure") }
                    })
                }
            }
            """.trimIndent(),
        )
        val index = SourceIndexer.build(listOf(sourceDir.toFile()))
        assertTrue(index.methods.any { it.synthetic })
        assertTrue(index.calls.any { it.candidateCalleeMethodIds.size == 1 })

        val file = File(createTempDirectory("openlog-src-store-callback-out").toFile(), "source-index")
        SourceIndexStore.save(index, file)

        assertEquals(index, SourceIndexStore.load(file))
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

    // (indagium rename, Stage 2) save() now writes the "indagium-source-index-v1" magic, but an
    // index a shipped 1.7.9 build wrote under the old "openLog2-source-index-v1" magic must still
    // load without triggering a rebuild — a full re-index walks the user's whole source tree.
    @Test
    fun loadWithLegacyOpenLogMagicStillLoads() {
        val dir = createTempDirectory("openlog-src-store-legacy-magic").toFile()
        val file = File(dir, "source-index").apply {
            writeText(
                buildString {
                    appendLine("openLog2-source-index-v1")
                    appendLine("version\t$SOURCE_INDEX_VERSION")
                    appendLine("builtAt\t1000")
                    appendLine("root\t${java.util.Base64.getUrlEncoder().withoutPadding().encodeToString("/tmp/src".toByteArray())}")
                },
            )
        }

        val loaded = SourceIndexStore.load(file)

        assertTrue(loaded != null)
        assertEquals(listOf("/tmp/src"), loaded.roots)
    }

    @Test
    fun loadWithCurrentIndagiumMagicLoads() {
        val dir = createTempDirectory("openlog-src-store-new-magic").toFile()
        val file = File(dir, "source-index").apply {
            writeText(
                buildString {
                    appendLine("indagium-source-index-v1")
                    appendLine("version\t$SOURCE_INDEX_VERSION")
                    appendLine("builtAt\t1000")
                    appendLine("root\t${java.util.Base64.getUrlEncoder().withoutPadding().encodeToString("/tmp/src".toByteArray())}")
                },
            )
        }

        val loaded = SourceIndexStore.load(file)

        assertTrue(loaded != null)
        assertEquals(listOf("/tmp/src"), loaded.roots)
    }

    @Test
    fun saveWritesTheCurrentIndagiumMagic() {
        val dir = createTempDirectory("openlog-src-store-write-magic").toFile()
        val srcDir = createTempDirectory("openlog-src-store-write-magic-src")
        val index = sampleIndex(srcDir)
        val file = File(dir, "source-index")

        SourceIndexStore.save(index, file)

        assertEquals("indagium-source-index-v1", file.readLines().first())
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
    fun schemaV9IndexIsRejectedSoItWillBeRebuiltWithSourceMetadata() {
        val dir = createTempDirectory("openlog-src-store-v9").toFile()
        val file = File(dir, "source-index").apply {
            writeText(
                buildString {
                    appendLine("indagium-source-index-v1")
                    appendLine("version\t9")
                    appendLine("builtAt\t1000")
                },
            )
        }

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
