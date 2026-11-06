package com.indagium.ram

import com.indagium.utils.parseLogcat
import java.nio.file.Files
import java.nio.file.StandardOpenOption
import java.nio.file.attribute.FileTime
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFails
import kotlin.test.assertTrue

class DirectFileLogStoreTest {
    @Test
    fun matchesParserAcrossEncodingAndLineBoundaries() {
        val root = Files.createTempDirectory("direct-file-test-")
        try {
            val file = root.resolve("generated.log")
            val prefix = "\uFEFF----- beginning\r\n \t\r\n01-02 12:34:56.123 123 456 I Tag: Привіт 🌍\r\nRAW raw\rnext\n"
            val suffix = "\n" + "x".repeat(150_000) + "\nlast without newline"
            Files.write(file, prefix.toByteArray() + byteArrayOf(0xc3.toByte(), 0x28) + suffix.toByteArray())
            val expected = parseLogcat(file.toFile())
            DirectFileLogStore.open(root, file).use { store ->
                assertEquals(expected.size, store.rowCount)
                assertEquals(expected, store.entries().toList())
                assertEquals(expected, store.batchedEntries().toList())
                assertEquals(expected.reversed(), (expected.indices.reversed()).map { store[it] })
                assertEquals(expected.filter { it.msg.contains("last") }, store.entries().filter { it.msg.contains("last") }.toList())
                assertTrue(store.cacheBytes <= store.cacheBudgetBytes)
            }
        } finally {
            root.toFile().deleteRecursively()
        }
    }

    @Test
    fun batchedScanDetectsMutationBetweenBlocks() {
        val root = Files.createTempDirectory("direct-scan-change-")
        try {
            val file = Files.writeString(root.resolve("generated.log"), (1..300).joinToString("\n") { "row $it" })
            DirectFileLogStore.open(root, file).use { store ->
                val rows = store.batchedEntries().iterator()
                repeat(128) { rows.next() }
                Files.writeString(file, "changed", StandardOpenOption.TRUNCATE_EXISTING)
                assertFails { rows.hasNext() }
            }
        } finally {
            root.toFile().deleteRecursively()
        }
    }

    @Test
    fun rejectsOutsidePathsAndSymlinkEscapes() {
        val root = Files.createTempDirectory("direct-root-")
        val outside = Files.createTempDirectory("direct-outside-")
        try {
            val file = Files.writeString(outside.resolve("other.log"), "generated")
            assertFails { DirectFileLogStore.open(root, file) }
            val link = root.resolve("escape.log")
            Files.createSymbolicLink(link, file)
            assertFails { DirectFileLogStore.open(root, link) }
        } finally {
            root.toFile().deleteRecursively()
            outside.toFile().deleteRecursively()
        }
    }

    @Test
    fun rejectsTruncationAndClosesDeterministically() {
        val root = Files.createTempDirectory("direct-change-")
        try {
            val file = Files.writeString(root.resolve("generated.log"), "first\nsecond\n")
            val store = DirectFileLogStore.open(root, file)
            assertEquals("first", store[0].msg)
            Files.writeString(file, "x", StandardOpenOption.TRUNCATE_EXISTING)
            assertFails { store[0] }
            store.close()
            store.close()
            assertEquals(0L, store.cacheBytes)
            assertFails { store[0] }
        } finally {
            root.toFile().deleteRecursively()
        }
    }

    @Test
    fun cacheRemainsWithinBudgetWhenRowsExceedIt() {
        val root = Files.createTempDirectory("direct-cache-")
        try {
            val file = Files.writeString(root.resolve("generated.log"), "a".repeat(17 * 1024 * 1024))
            DirectFileLogStore.open(root, file).use { store ->
                assertEquals(1, store.rowCount)
                assertEquals(17 * 1024 * 1024, store[0].msg.length)
                assertTrue(store.cacheBytes <= store.cacheBudgetBytes)
            }
        } finally {
            root.toFile().deleteRecursively()
        }
    }

    @Test
    fun sharedBudgetEvictsAcrossStores() {
        val root = Files.createTempDirectory("direct-shared-")
        try {
            val content = "x".repeat(9 * 1024 * 1024)
            val a = Files.writeString(root.resolve("a.log"), content)
            val b = Files.writeString(root.resolve("b.log"), content)
            DirectFileLogStore.open(root, a).use { first ->
                DirectFileLogStore.open(root, b).use { second ->
                    first[0]
                    val oneBlockBytes = first.cacheBytes
                    assertTrue(oneBlockBytes > 0)
                    second[0]
                    assertEquals(oneBlockBytes, second.cacheBytes)
                    assertTrue(first.cacheBytes <= first.cacheBudgetBytes)
                    assertEquals(content, first[0].msg)
                    assertTrue(second.cacheBytes <= second.cacheBudgetBytes)
                }
            }
        } finally {
            root.toFile().deleteRecursively()
        }
    }

    @Test
    fun detectsSameSizeOverwriteAndReplacement() {
        val root = Files.createTempDirectory("direct-replace-")
        try {
            val file = Files.writeString(root.resolve("generated.log"), "first")
            DirectFileLogStore.open(root, file).use { store ->
                val oldTime = Files.getLastModifiedTime(file)
                Files.writeString(file, "other", StandardOpenOption.TRUNCATE_EXISTING)
                Files.setLastModifiedTime(file, FileTime.fromMillis(oldTime.toMillis() + 5000))
                assertFails { store[0] }
            }
            DirectFileLogStore.open(root, file).use { store ->
                val replacement = Files.writeString(root.resolve("replacement.log"), "third")
                Files.move(replacement, file, java.nio.file.StandardCopyOption.REPLACE_EXISTING)
                assertFails { store[0] }
            }
        } finally {
            root.toFile().deleteRecursively()
        }
    }
}
