package com.openlog.source

import java.nio.file.Path
import kotlin.io.path.createDirectories
import kotlin.io.path.createTempDirectory
import kotlin.io.path.writeText
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

private fun Path.write(relPath: String, content: String) {
    val target = resolve(relPath)
    target.parent?.createDirectories()
    target.writeText(content)
}

class LogSourceResolverTest {
    @Test
    fun genericLiteralIsDroppedWhenASpecificMatchExists() {
        val dir = createTempDirectory("openlog-src")
        dir.write(
            "GenericVsSpecific.kt",
            """
            package demo

            class GenericVsSpecific {
                companion object {
                    private const val TAG = "Sync"
                }

                fun genericGreeting(n: Int) {
                    Log.d(TAG, "user " + n)
                }

                fun specificSync() {
                    Log.d(TAG, "user 42 sync done")
                }
            }
            """.trimIndent(),
        )

        val index = SourceIndexer.build(listOf(dir.toFile()))
        val generic = index.sites.single { it.methodName == "genericGreeting" }
        val specific = index.sites.single { it.methodName == "specificSync" }
        assertTrue(generic.literalLen < GENERIC_LITERAL_THRESHOLD)
        assertTrue(specific.literalLen >= GENERIC_LITERAL_THRESHOLD)

        // Sanity: both sites' matchers actually match the query on their own before ranking kicks in.
        assertTrue(Regex(generic.matcher).containsMatchIn("user 42 sync done"))
        assertTrue(Regex(specific.matcher).containsMatchIn("user 42 sync done"))

        val matches = LogSourceResolver(index).resolve("Sync", "user 42 sync done")
        assertEquals(1, matches.size)
        assertEquals("specificSync", matches.single().site.methodName)
    }

    @Test
    fun allGenericMatchesAreKeptButConfidenceCapped() {
        val dir = createTempDirectory("openlog-src")
        dir.write(
            "OnlyGeneric.kt",
            """
            package demo

            class OnlyGeneric {
                fun a() {
                    Log.d("Tag", "done")
                }
            }
            """.trimIndent(),
        )

        val index = SourceIndexer.build(listOf(dir.toFile()))
        val matches = LogSourceResolver(index).resolve("Tag", "done")
        assertEquals(1, matches.size)
        assertTrue(matches.single().confidence <= 0.3)
    }

    @Test
    fun tagDisambiguationRanksMatchingFileFirst() {
        val dir = createTempDirectory("openlog-src")
        dir.write(
            "X.kt",
            """
            package demo

            class X {
                fun handle() {
                    Log.d("TagX", "operation completed successfully")
                }
            }
            """.trimIndent(),
        )
        dir.write(
            "Y.kt",
            """
            package demo

            class Y {
                fun process() {
                    Log.d("TagY", "operation completed successfully")
                }
            }
            """.trimIndent(),
        )

        val index = SourceIndexer.build(listOf(dir.toFile()))
        val resolver = LogSourceResolver(index)

        val xMatches = resolver.resolve("TagX", "operation completed successfully")
        assertEquals(1, xMatches.size)
        assertEquals("handle", xMatches.single().site.methodName)

        val yMatches = resolver.resolve("TagY", "operation completed successfully")
        assertEquals(1, yMatches.size)
        assertEquals("process", yMatches.single().site.methodName)
    }

    @Test
    fun blankNullAndRawTagSearchAllBuckets() {
        val dir = createTempDirectory("openlog-src")
        dir.write(
            "X.kt",
            """
            package demo

            class X {
                fun handle() {
                    Log.d("TagX", "operation completed successfully")
                }
            }
            """.trimIndent(),
        )
        dir.write(
            "Y.kt",
            """
            package demo

            class Y {
                fun process() {
                    Log.d("TagY", "operation completed successfully")
                }
            }
            """.trimIndent(),
        )

        val index = SourceIndexer.build(listOf(dir.toFile()))
        val resolver = LogSourceResolver(index)
        val expected = setOf("handle", "process")

        val nullTag = resolver.resolve(null, "operation completed successfully")
        assertEquals(expected, nullTag.map { it.site.methodName }.toSet())

        val rawTag = resolver.resolve("RAW", "operation completed successfully")
        assertEquals(expected, rawTag.map { it.site.methodName }.toSet())

        val blankTag = resolver.resolve("", "operation completed successfully")
        assertEquals(expected, blankTag.map { it.site.methodName }.toSet())
    }

    @Test
    fun exactTagMatchWinsOverSameMessageNullTagSite() {
        val dir = createTempDirectory("openlog-src")
        dir.write(
            "Known.kt",
            """
            package demo

            class Known {
                fun handle() {
                    Log.d("Real", "operation completed successfully")
                }
            }
            """.trimIndent(),
        )
        dir.write(
            "Unknown.kt",
            """
            package demo

            class Unknown {
                fun handle(dynamicTag: String) {
                    Log.d(dynamicTag, "operation completed successfully")
                }
            }
            """.trimIndent(),
        )

        val index = SourceIndexer.build(listOf(dir.toFile()))
        val resolver = LogSourceResolver(index)

        val realMatches = resolver.resolve("Real", "operation completed successfully")
        assertEquals(1, realMatches.size)
        assertEquals("Known.kt", java.io.File(realMatches.single().site.filePath).name)

        val allBucketMatches = resolver.resolve(null, "operation completed successfully")
        assertEquals(2, allBucketMatches.size)

        val rawMatches = resolver.resolve("RAW", "operation completed successfully")
        assertEquals(2, rawMatches.size)
    }

    @Test
    fun nullTagSiteStillReturnedWhenQueryTagHasNoExactMatch() {
        val dir = createTempDirectory("openlog-src")
        dir.write(
            "Unknown.kt",
            """
            package demo

            class Unknown {
                fun handle(dynamicTag: String) {
                    Log.d(dynamicTag, "operation completed successfully")
                }
            }
            """.trimIndent(),
        )

        val index = SourceIndexer.build(listOf(dir.toFile()))
        val matches = LogSourceResolver(index).resolve("NoSuchTag", "operation completed successfully")
        assertEquals(1, matches.size)
        assertEquals("handle", matches.single().site.methodName)
    }

    @Test
    fun noMatchReturnsEmptyList() {
        val dir = createTempDirectory("openlog-src")
        dir.write(
            "X.kt",
            """
            package demo

            class X {
                fun handle() {
                    Log.d("TagX", "operation completed successfully")
                }
            }
            """.trimIndent(),
        )

        val index = SourceIndexer.build(listOf(dir.toFile()))
        val matches = LogSourceResolver(index).resolve("TagX", "totally unrelated message")
        assertTrue(matches.isEmpty())
    }
}
