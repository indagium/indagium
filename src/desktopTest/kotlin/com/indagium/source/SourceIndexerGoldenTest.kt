package com.indagium.source

import kotlin.io.path.createTempDirectory
import kotlin.io.path.writeText
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * Safety net for the SourceIndexer performance pass (resolveDirectCalls / declaredOwnerCandidates
 * rewrite). This fixture deliberately exercises every call-resolution path the optimisation touches:
 * a member call through a typed `val` property, a member call through a typed function parameter,
 * a `this.` member call, a bare local call, Java field/parameter receivers, wrapper-rule
 * auto-discovery, and a file with no `package` declaration. The expected values below were captured
 * from the UNOPTIMISED implementation — if a later change to the resolution algorithms alters any of
 * them, that is a real behaviour change and this test must fail. The expected direct-call lists
 * also include the new unambiguous incoming caller edges used to connect a callee's log site back
 * to the class that invokes it.
 */
private data class DC(val owner: String, val method: String, val returnType: String?, val callLine: Int)

private data class S(
    val file: String,
    val tag: String?,
    val method: String,
    val owner: String?,
    val callLine: Int,
    val configDependent: Boolean,
    val directCalls: List<DC>,
)

class SourceIndexerGoldenTest {
    private fun Path.write(relPath: String, content: String) {
        val target = resolve(relPath)
        target.parent?.let { java.nio.file.Files.createDirectories(it) }
        target.writeText(content)
    }

    // Deliberately exercises every call-resolution path the optimisation touches: a member call
    // through a typed `val` property, a member call through a typed function parameter, a `this.`
    // member call, a bare local call, Java field/parameter receivers, wrapper-rule auto-discovery,
    // and a file with no `package` declaration.
    private fun writeGoldenFixture(dir: Path) {
        dir.write(
            "Alpha.kt",
            """
            package demo

            class Alpha {
                private val helper: Helper = Helper()

                fun run(id: String) {
                    val local: Local = Local()
                    helper.assist(id)
                    local.process(id)
                    this.selfCall(id)
                    selfCall(id)
                    Log.d(TAG, "run ${'$'}id")
                }

                fun selfCall(id: String) {
                    Log.d(TAG, "selfCall ${'$'}id")
                }

                fun withParam(worker: Helper) {
                    worker.assist("param-call")
                }

                companion object {
                    private const val TAG = "Alpha"
                }
            }

            class Helper {
                fun assist(id: String) {
                    Log.d("Helper", "assist ${'$'}id")
                }
            }

            class Local {
                fun process(id: String) {
                    Log.d("Local", "process ${'$'}id")
                }
            }
            """.trimIndent(),
        )

        dir.write(
            "Beta.java",
            """
            package demo;

            public class Beta {
                private Gamma gamma;

                public void run(Gamma param, String id) {
                    gamma.act(id);
                    param.act(id);
                    Log.d("Beta", "run " + id);
                }
            }

            class Gamma {
                void act(String id) {
                    Log.d("Gamma", "act " + id);
                }
            }
            """.trimIndent(),
        )

        dir.write(
            "Wrapper.kt",
            """
            package demo

            object Logger {
                fun logError(tag: String, message: String) {
                    Log.e(tag, message)
                }
            }

            class Delta {
                fun run(id: String) {
                    Logger.logError("Delta", "run ${'$'}id")
                }
            }
            """.trimIndent(),
        )

        dir.write(
            "NoPackage.kt",
            """
            class NoPackage {
                fun run(id: String) {
                    Log.d("NoPackage", "run ${'$'}id")
                }
            }
            """.trimIndent(),
        )
    }

    // File order below is whatever collectSourceFiles' (unspecified-order) directory walk produced
    // for this fixture on the machine the baseline was captured on; the indexer must reproduce it
    // exactly since it reuses the same traversal.
    @Suppress("MagicNumber") // literal line numbers are the expected data itself, not tunables
    private fun expectedGoldenSites(): List<S> = listOf(
        S(
            "Alpha.kt", "Alpha", "run", "demo.Alpha", 12, false,
            listOf(
                DC("demo.Helper", "assist", null, 8),
                DC("demo.Local", "process", null, 9),
                DC("demo.Alpha", "selfCall", null, 10),
                DC("demo.Alpha", "selfCall", null, 11),
            ),
        ),
        S(
            "Alpha.kt", "Alpha", "selfCall", "demo.Alpha", 16, false,
            listOf(
                DC("demo.Alpha", "selfCall", null, 10),
                DC("demo.Alpha", "selfCall", null, 11),
            ),
        ),
        S(
            "Alpha.kt", "Helper", "assist", "demo.Helper", 30, false,
            listOf(
                DC("demo.Helper", "assist", null, 8),
                DC("demo.Helper", "assist", null, 20),
            ),
        ),
        S(
            "Alpha.kt", "Local", "process", "demo.Local", 36, false,
            listOf(DC("demo.Local", "process", null, 9)),
        ),
        S(
            "Wrapper.kt", "Delta", "run", "demo.Delta", 11, true,
            listOf(DC("demo.Logger", "logError", null, 11)),
        ),
        S("NoPackage.kt", "NoPackage", "run", "NoPackage", 3, false, emptyList()),
        S(
            "Beta.java", "Beta", "run", "demo.Beta", 9, false,
            listOf(
                DC("demo.Gamma", "act", null, 7),
                DC("demo.Gamma", "act", null, 8),
            ),
        ),
        S(
            "Beta.java", "Gamma", "act", "demo.Gamma", 15, false,
            listOf(
                DC("demo.Gamma", "act", null, 7),
                DC("demo.Gamma", "act", null, 8),
            ),
        ),
    )

    @Test
    fun goldenFixtureTreeProducesExactlyTodaysIndex() {
        val dir = createTempDirectory("openlog-src-golden")
        writeGoldenFixture(dir)

        val index = SourceIndexer.build(
            listOf(dir.toFile()),
            options = SourceIndexBuildOptions(autoDiscover = true),
        )

        val basePath = dir.toFile().canonicalPath

        fun rel(path: String) = path.removePrefix(basePath).removePrefix(java.io.File.separator)

        val actual = index.sites.map { site ->
            S(
                rel(site.filePath),
                site.tag,
                site.methodName,
                site.owningType,
                site.callLine,
                site.configurationDependent,
                site.directCalls.map { DC(it.targetOwnerType, it.targetMethodName, it.targetDeclaredReturnType, it.callLine) },
            )
        }
        assertEquals(expectedGoldenSites(), actual)

        val expectedFileSizes = mapOf(
            "Alpha.kt" to 672L,
            "Wrapper.kt" to 207L,
            "NoPackage.kt" to 89L,
            "Beta.java" to 282L,
        )
        val actualFileSizes = index.fileMeta.entries.associate { (p, m) -> rel(p) to m.size }
        assertEquals(expectedFileSizes, actualFileSizes)
        assertEquals(expectedFileSizes.keys, index.fileMeta.keys.map(::rel).toSet())
    }

    // Task 1e: cancellationCheck is polled between phases and per file. A check that flips to
    // `true` partway through must stop the build before it ever reaches a normal `return
    // SourceIndex(...)` — surfaced as SourceIndexCancelledException so a caller such as
    // AppState.reindexSources (which wraps the call in runCatching { … }.getOrNull()) never sees a
    // completed-but-partial index and so never persists one.
    @Test
    fun cancellationCheckReturningTruePartwayStopsTheBuildEarly() {
        val dir = createTempDirectory("openlog-src-cancel")
        repeat(8) { i ->
            dir.write("File$i.kt", "package demo\nclass File$i { fun run() { Log.d(\"File$i\", \"run\") } }")
        }

        var calls = 0
        assertFailsWith<SourceIndexCancelledException> {
            SourceIndexer.build(listOf(dir.toFile()), cancellationCheck = { calls++; calls > 1 })
        }
        // More than one call proves the build actually started (the pre-flight check at the top of
        // build() passed) and was cut short by a later check, rather than never running at all.
        assertTrue(calls > 1)
    }

    // The default cancellationCheck ({ false }) must behave exactly as before task 1e for every
    // existing call site that never opts in — this is what keeps every other SourceIndexer.build(…)
    // call across the test suite (and the reindex_sources MCP route) compiling and passing unchanged.
    @Test
    fun defaultCancellationCheckNeverCancels() {
        val dir = createTempDirectory("openlog-src-no-cancel")
        dir.write("Solo.kt", "package demo\nclass Solo { fun run() { Log.d(\"Solo\", \"run\") } }")

        val index = SourceIndexer.build(listOf(dir.toFile()))

        assertEquals(1, index.sites.size)
    }
}

private typealias Path = java.nio.file.Path
