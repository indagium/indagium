package com.openlog.source

import com.openlog.model.SourceLogConfiguration
import com.openlog.model.SourceWrapperRule
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

class SourceIndexerTest {
    @Test
    fun localTagConstantsAreNotShadowedByAnotherFilesTag() {
        val dir = createTempDirectory("openlog-src-local-tag-scope")
        dir.write(
            "Alpha.kt",
            """
            package demo

            class Alpha {
                companion object { private const val TAG = "AlphaTag" }
                fun log() { Log.d(TAG, "alpha message") }
            }
            """.trimIndent(),
        )
        dir.write(
            "Zebra.kt",
            """
            package demo

            class Zebra {
                companion object { private const val TAG = "ZebraTag" }
                fun log() { Log.d(TAG, "zebra message") }
            }
            """.trimIndent(),
        )

        val index = SourceIndexer.build(listOf(dir.toFile()))

        assertEquals(setOf("AlphaTag", "ZebraTag"), index.sites.mapNotNull { it.tag }.toSet())
        val resolver = LogSourceResolver(index)
        assertEquals(1, resolver.resolve("AlphaTag", "alpha message").size)
        assertEquals(1, resolver.resolve("ZebraTag", "zebra message").size)
    }

    @Test
    fun configuredWrapperUsesConfiguredArgumentsAndExistingMessageMatcher() {
        val dir = createTempDirectory("openlog-src-wrapper")
        dir.write(
            "Logger.kt",
            """
            package demo

            class Logger {
                fun write(code: String, tag: String, error: Throwable?, message: String) {
                    Log.d(tag, message, error)
                }
            }

            class Feature(private val logger: Logger) {
                fun run(id: String) {
                    logger.write("E1", "WrapperTag", null, "Failure ${'$'}id")
                }
            }
            """.trimIndent(),
        )

        val config = SourceLogConfiguration(
            id = "wrapper",
            name = "Wrapper",
            wrapperRules = listOf(SourceWrapperRule("Logger", "write", tagArgumentIndex = 1, messageArgumentIndex = 3, throwableArgumentIndex = 2)),
        )
        val index = SourceIndexer.build(
            listOf(dir.toFile()),
            options = SourceIndexBuildOptions(
                wrapperRules = config.wrapperRules,
                configurationFingerprint = sourceConfigurationFingerprint(listOf(config), autoDiscoveryEnabled = false),
            ),
        )

        assertEquals(1, index.sites.size)
        val site = index.sites.single()
        assertEquals("WrapperTag", site.tag)
        assertEquals("run", site.methodName)
        assertEquals(1, LogSourceResolver(index).resolve("WrapperTag", "Failure 42").size)
    }

    @Test
    fun sameMethodNameOnUnrelatedOwnerIsNotMatched() {
        val dir = createTempDirectory("openlog-src-wrapper-owner")
        dir.write(
            "Other.kt",
            """
            package demo

            class Other {
                fun write(tag: String, message: String) = Unit
            }

            class Feature(private val other: Other) {
                fun run() {
                    other.write("Wrong", "Must not match")
                }
            }
            """.trimIndent(),
        )
        val index = SourceIndexer.build(
            listOf(dir.toFile()),
            options = SourceIndexBuildOptions(
                wrapperRules = listOf(SourceWrapperRule("Logger", "write")),
            ),
        )

        assertTrue(index.sites.isEmpty())
    }

    @Test
    fun autoDiscoveryFollowsOneInterfaceImplementationHop() {
        val dir = createTempDirectory("openlog-src-discovery")
        dir.write(
            "Fixture.kt",
            """
            package demo

            interface FixtureLogger {
                fun debug(tag: String, message: String)
            }

            object Telemetry : FixtureLogger {
                override fun debug(tag: String, message: String) {
                    Log.d(tag, message)
                }
            }

            class Feature(private val logger: FixtureLogger) {
                fun load() {
                    logger.debug("Fixture", "Feature failed")
                }
            }
            """.trimIndent(),
        )

        val index = SourceIndexer.build(
            listOf(dir.toFile()),
            options = SourceIndexBuildOptions(autoDiscover = true),
        )

        assertEquals(1, index.sites.size)
        assertEquals("load", index.sites.single().methodName)
        assertEquals(1, LogSourceResolver(index).resolve("Fixture", "Feature failed").size)
    }

    @Test
    fun autoDiscoveryFindsDirectKotlinObjectWrapperCalls() {
        val dir = createTempDirectory("openlog-src-object-wrapper")
        dir.write(
            "LogUtil.kt",
            """
            package demo.logging

            object LogUtil {
                fun d(tag: String, message: String) {
                    Log.d(tag, message)
                }
            }
            """.trimIndent(),
        )
        dir.write(
            "Feature.kt",
            """
            package demo.feature

            import demo.logging.LogUtil

            class Feature {
                fun run() {
                    LogUtil.d("ObjectWrapper", "Feature failed")
                }
            }
            """.trimIndent(),
        )

        val index = SourceIndexer.build(
            listOf(dir.toFile()),
            options = SourceIndexBuildOptions(autoDiscover = true),
        )

        val matches = LogSourceResolver(index).resolve("ObjectWrapper", "Feature failed")
        assertEquals(1, matches.size)
        assertEquals("run", matches.single().site.methodName)
    }

    @Test
    fun qualifiedConstantsResolveAcrossFiles() {
        val dir = createTempDirectory("openlog-src-qualified-constant")
        dir.write("Telemetry.kt", "package demo\nobject Telemetry { const val BUG = \"BugTag\" }")
        dir.write(
            "Feature.kt",
            """
            package demo

            class Feature {
                fun fail() {
                    Log.e(Telemetry.BUG, "Bug happened")
                }
            }
            """.trimIndent(),
        )

        val index = SourceIndexer.build(listOf(dir.toFile()))

        assertEquals(1, index.sites.size)
        assertEquals("BugTag", index.sites.single().tag)
    }

    @Test
    fun plainLiteralMatchResolvesToRightMethod() {
        val dir = createTempDirectory("openlog-src")
        dir.write(
            "Foo.kt",
            """
            package demo

            class Foo {
                fun bar() {
                    Log.d("TagX", "Hello world")
                }
            }
            """.trimIndent(),
        )

        val index = SourceIndexer.build(listOf(dir.toFile()))

        assertEquals(1, index.sites.size)
        val site = index.sites.single()
        assertEquals("bar", site.methodName)
        assertEquals("TagX", site.tag)

        val matches = LogSourceResolver(index).resolve("TagX", "Hello world")
        assertEquals(1, matches.size)
        assertEquals("bar", matches.single().site.methodName)
    }

    @Test
    fun interpolationHoleMatchesRuntimeValue() {
        val dir = createTempDirectory("openlog-src")
        dir.write(
            "Login.kt",
            """
            package demo

            class Login {
                companion object {
                    private const val TAG = "Net"
                }

                fun login(id: Int) {
                    Log.d(TAG, "User ${'$'}id logged in")
                }
            }
            """.trimIndent(),
        )

        val index = SourceIndexer.build(listOf(dir.toFile()))
        val site = index.sites.single()
        assertEquals("Net", site.tag)
        assertEquals("login", site.methodName)
        assertEquals("^\\QUser \\E.*?\\Q logged in\\E$", site.matcher)

        val matches = LogSourceResolver(index).resolve("Net", "User 42 logged in")
        assertEquals(1, matches.size)
        assertEquals("login", matches.single().site.methodName)

        assertEquals(0, LogSourceResolver(index).resolve("Net", "User logged in without id").size)
    }

    @Test
    fun javaConcatenationMatchesRuntimeValue() {
        val dir = createTempDirectory("openlog-src")
        dir.write(
            "Counter.java",
            """
            package demo;

            class Counter {
                private static final String TAG = "Cnt";

                void count(int n) {
                    Log.i(TAG, "count=" + n);
                }
            }
            """.trimIndent(),
        )

        val index = SourceIndexer.build(listOf(dir.toFile()))
        val site = index.sites.single()
        assertEquals("Cnt", site.tag)
        assertEquals("count", site.methodName)
        assertEquals("^\\Qcount=\\E.*?", site.matcher)

        val matches = LogSourceResolver(index).resolve("Cnt", "count=57")
        assertEquals(1, matches.size)
        assertEquals("count", matches.single().site.methodName)
    }

    @Test
    fun javaStringFormatMatchesDynamicToStringOutputByItsStaticPrefix() {
        val dir = createTempDirectory("openlog-src")
        dir.write(
            "ExampleLogger.java",
            """
            package demo;

            class ExampleLogger {
                private static final String TAG = "ExampleLogger";

                void log(ExampleInfo exampleInfo) {
                    Log.d(TAG, String.format("Example info: %s", exampleInfo));
                }
            }
            """.trimIndent(),
        )

        val index = SourceIndexer.build(listOf(dir.toFile()))
        val site = index.sites.single()
        assertEquals("ExampleLogger", site.tag)
        assertEquals("log", site.methodName)
        assertEquals("^\\QExample info: \\E.*?", site.matcher)

        val matches = LogSourceResolver(index).resolve(
            "ExampleLogger",
            "Example info: ExampleInfo{id=42, children=[first, second], details=lots of values}",
        )
        assertEquals(1, matches.size)
        assertEquals("log", matches.single().site.methodName)
    }

    @Test
    fun timberPlainAndTaggedChainAreBothIndexed() {
        val dir = createTempDirectory("openlog-src")
        dir.write(
            "Sync.kt",
            """
            package demo

            class Sync {
                fun run() {
                    Timber.d("starting sync")
                }

                fun net(code: Int) {
                    Timber.tag("Net").e("http ${'$'}code failed")
                }
            }
            """.trimIndent(),
        )

        val index = SourceIndexer.build(listOf(dir.toFile()))
        assertEquals(2, index.sites.size)

        val runSite = index.sites.single { it.methodName == "run" }
        assertNull(runSite.tag)

        val netSite = index.sites.single { it.methodName == "net" }
        assertEquals("Net", netSite.tag)

        val resolver = LogSourceResolver(index)
        assertEquals("run", resolver.resolve(null, "starting sync").single().site.methodName)
        assertEquals("net", resolver.resolve("Net", "http 500 failed").single().site.methodName)
    }

    // ── TAG resolution variants ──────────────────────────────────────────

    @Test
    fun topLevelConstValTagIsResolved() {
        val dir = createTempDirectory("openlog-src")
        dir.write(
            "ConstValTag.kt",
            """
            package demo

            private const val TAG = "TopLevelConst"

            class A {
                fun a1() {
                    Log.d(TAG, "a1 fired")
                }
            }
            """.trimIndent(),
        )

        val site = SourceIndexer.build(listOf(dir.toFile())).sites.single()
        assertEquals("TopLevelConst", site.tag)
    }

    @Test
    fun companionObjectValTagIsResolved() {
        val dir = createTempDirectory("openlog-src")
        dir.write(
            "CompanionTag.kt",
            """
            package demo

            class B {
                companion object {
                    private val TAG = "CompanionStyle"
                }

                fun b1() {
                    Log.d(TAG, "b1 fired")
                }
            }
            """.trimIndent(),
        )

        val site = SourceIndexer.build(listOf(dir.toFile())).sites.single()
        assertEquals("CompanionStyle", site.tag)
    }

    @Test
    fun javaStaticFinalStringTagIsResolved() {
        val dir = createTempDirectory("openlog-src")
        dir.write(
            "JavaStaticFinal.java",
            """
            package demo;

            class C {
                private static final String TAG = "JavaStyle";

                void c1() {
                    Log.d(TAG, "c1 fired");
                }
            }
            """.trimIndent(),
        )

        val site = SourceIndexer.build(listOf(dir.toFile())).sites.single()
        assertEquals("JavaStyle", site.tag)
    }

    @Test
    fun directStringLiteralFirstArgIsUsedAsTag() {
        val dir = createTempDirectory("openlog-src")
        dir.write(
            "DirectLiteral.kt",
            """
            package demo

            class D {
                fun d1() {
                    Log.d("DirectLiteral", "d1 fired")
                }
            }
            """.trimIndent(),
        )

        val site = SourceIndexer.build(listOf(dir.toFile())).sites.single()
        assertEquals("DirectLiteral", site.tag)
    }

    @Test
    fun unresolvedTagIdentifierYieldsNullTag() {
        val dir = createTempDirectory("openlog-src")
        dir.write(
            "UnresolvedTag.kt",
            """
            package demo

            class E {
                fun e1(dynamicTag: String) {
                    Log.d(dynamicTag, "e1 fired")
                }
            }
            """.trimIndent(),
        )

        val site = SourceIndexer.build(listOf(dir.toFile())).sites.single()
        assertNull(site.tag)
    }

    // ── Class-name-derived tag resolution ───────────────────────────────────

    @Test
    fun inlineClassLiteralTagArgResolvesToSimpleName() {
        val dir = createTempDirectory("openlog-src")
        dir.write(
            "Inline.kt",
            """
            package demo

            class Foo {
                fun f1() {
                    Log.d(Foo::class.java.simpleName, "f1 fired")
                }

                fun f2() {
                    Log.d(Foo::class.simpleName, "f2 fired")
                }
            }
            """.trimIndent(),
        )

        val index = SourceIndexer.build(listOf(dir.toFile()))
        assertEquals(2, index.sites.size)
        assertTrue(index.sites.all { it.tag == "Foo" })
    }

    @Test
    fun kotlinConstFromClassLiteralIsResolved() {
        val dir = createTempDirectory("openlog-src")
        dir.write(
            "KotlinConstClassLiteral.kt",
            """
            package demo

            class Holder {
                private val TAG = Bar::class.java.simpleName

                fun log() {
                    Log.d(TAG, "x")
                }
            }

            class Bar
            """.trimIndent(),
        )

        val site = SourceIndexer.build(listOf(dir.toFile())).sites.single()
        assertEquals("Bar", site.tag)
    }

    @Test
    fun javaConstFromClassLiteralIsResolved() {
        val dir = createTempDirectory("openlog-src")
        dir.write(
            "JavaConstClassLiteral.java",
            """
            package demo;

            class Holder {
                private static final String TAG = Baz.class.getSimpleName();

                void log() {
                    Log.d(TAG, "x");
                }
            }
            """.trimIndent(),
        )

        val site = SourceIndexer.build(listOf(dir.toFile())).sites.single()
        assertEquals("Baz", site.tag)
    }

    @Test
    fun selfClassTagResolvesToEnclosingClassName() {
        val dir = createTempDirectory("openlog-src")
        dir.write(
            "Qux.kt",
            """
            package demo

            class Qux {
                private val TAG = javaClass.simpleName

                fun f() {
                    Log.d(TAG, "x")
                }
            }
            """.trimIndent(),
        )

        val site = SourceIndexer.build(listOf(dir.toFile())).sites.single()
        assertEquals("Qux", site.tag)
    }

    @Test
    fun companionClassLiteralConstIsResolved() {
        val dir = createTempDirectory("openlog-src")
        dir.write(
            "Widget.kt",
            """
            package demo

            class Widget {
                companion object {
                    private val TAG = Widget::class.java.simpleName
                }

                fun f() {
                    Log.d(TAG, "x")
                }
            }
            """.trimIndent(),
        )

        val site = SourceIndexer.build(listOf(dir.toFile())).sites.single()
        assertEquals("Widget", site.tag)
    }

    @Test
    fun companionSelfClassTagResolvesToOuterEnclosingClassName() {
        val dir = createTempDirectory("openlog-src")
        dir.write(
            "Screen.kt",
            """
            package demo

            class Screen {
                companion object {
                    private val TAG = this::class.java.simpleName
                }

                fun f() {
                    Log.d(TAG, "x")
                }
            }
            """.trimIndent(),
        )

        val site = SourceIndexer.build(listOf(dir.toFile())).sites.single()
        // `this::class` inside a companion object is textually ambiguous (the companion has no
        // runtime type of its own that matters here) — we resolve to the nearest *named* enclosing
        // type, which is the outer class. This matches how `javaClass.simpleName` is treated too,
        // and is the behavior developers intend when writing this pattern inside a companion.
        assertEquals("Screen", site.tag)
    }

    @Test
    fun plainLocalVariableTagStillYieldsNullTag() {
        val dir = createTempDirectory("openlog-src")
        dir.write(
            "PlainVar.kt",
            """
            package demo

            class PlainVar {
                fun f(someVar: String) {
                    Log.d(someVar, "x")
                }
            }
            """.trimIndent(),
        )

        val site = SourceIndexer.build(listOf(dir.toFile())).sites.single()
        assertNull(site.tag)
    }

    @Test
    fun sameMessageDifferentClassDerivedTagsAreDisambiguated() {
        val dir = createTempDirectory("openlog-src")
        dir.write(
            "Alpha.kt",
            """
            package demo

            class Alpha {
                fun run() {
                    Log.d(javaClass.simpleName, "shared message")
                }
            }
            """.trimIndent(),
        )
        dir.write(
            "Beta.kt",
            """
            package demo

            class Beta {
                fun run() {
                    Log.d(javaClass.simpleName, "shared message")
                }
            }
            """.trimIndent(),
        )

        val index = SourceIndexer.build(listOf(dir.toFile()))
        assertEquals(2, index.sites.size)
        val tags = index.sites.map { it.tag }.toSet()
        assertEquals(setOf("Alpha", "Beta"), tags)

        val alphaSite = index.sites.single { it.tag == "Alpha" }
        val betaSite = index.sites.single { it.tag == "Beta" }
        assertEquals(alphaSite, LogSourceResolver(index).resolve("Alpha", "shared message").single().site)
        assertEquals(betaSite, LogSourceResolver(index).resolve("Beta", "shared message").single().site)
    }

    @Test
    fun sameNamedNullableClassLiteralTagsResolveWithinTheirOwnClasses() {
        val dir = createTempDirectory("openlog-src")
        dir.write(
            "SharedTags.kt",
            """
            package demo

            class First {
                private val TAG: String? = First::class.java.name

                fun log() {
                    Log.d(TAG, "same message")
                }
            }

            class Second {
                companion object {
                    private val TAG: String? = Second::class.java.canonicalName
                }

                fun log() {
                    Log.d(TAG, "same message")
                }
            }
            """.trimIndent(),
        )

        val index = SourceIndexer.build(listOf(dir.toFile()))
        assertEquals(setOf("demo.First", "demo.Second"), index.sites.mapNotNull { it.tag }.toSet())
        assertEquals(
            listOf("demo.First"),
            LogSourceResolver(index).resolve("demo.First", "same message").mapNotNull { it.site.tag },
        )
    }

    // ── Fully-qualified (canonical) class-name tag resolution ───────────────

    @Test
    fun kotlinInlineCanonicalNameClassLiteralResolvesToFqn() {
        val dir = createTempDirectory("openlog-src")
        dir.write(
            "Inline.kt",
            """
            package a.b

            class Foo {
                fun f1() {
                    Log.d(Foo::class.java.canonicalName, "f1 fired")
                }

                fun f2() {
                    Log.d(Foo::class.qualifiedName, "f2 fired")
                }
            }
            """.trimIndent(),
        )

        val index = SourceIndexer.build(listOf(dir.toFile()))
        assertEquals(2, index.sites.size)
        assertTrue(index.sites.all { it.tag == "a.b.Foo" })
    }

    @Test
    fun kotlinConstCanonicalNameClassLiteralResolvesToFqn() {
        val dir = createTempDirectory("openlog-src")
        dir.write(
            "KotlinConstCanonical.kt",
            """
            package a.b

            class Holder {
                private val TAG = Bar::class.java.canonicalName

                fun log() {
                    Log.d(TAG, "x")
                }
            }

            class Bar
            """.trimIndent(),
        )

        val site = SourceIndexer.build(listOf(dir.toFile())).sites.single()
        assertEquals("a.b.Bar", site.tag)
    }

    @Test
    fun kotlinConstQualifiedNameClassLiteralResolvesToFqn() {
        val dir = createTempDirectory("openlog-src")
        dir.write(
            "KotlinConstQualified.kt",
            """
            package a.b

            class Holder {
                private val TAG = Bar::class.qualifiedName

                fun log() {
                    Log.d(TAG, "x")
                }
            }

            class Bar
            """.trimIndent(),
        )

        val site = SourceIndexer.build(listOf(dir.toFile())).sites.single()
        assertEquals("a.b.Bar", site.tag)
    }

    @Test
    fun javaConstCanonicalNameClassLiteralResolvesToFqn() {
        val dir = createTempDirectory("openlog-src")
        dir.write(
            "JavaConstCanonical.java",
            """
            package a.b;

            class Holder {
                private static final String TAG = Baz.class.getCanonicalName();

                void log() {
                    Log.d(TAG, "x");
                }
            }
            """.trimIndent(),
        )

        val site = SourceIndexer.build(listOf(dir.toFile())).sites.single()
        assertEquals("a.b.Baz", site.tag)
    }

    @Test
    fun javaConstGetNameClassLiteralResolvesToFqn() {
        val dir = createTempDirectory("openlog-src")
        dir.write(
            "JavaConstGetName.java",
            """
            package a.b;

            class Holder {
                private static final String TAG = Baz.class.getName();

                void log() {
                    Log.d(TAG, "x");
                }
            }
            """.trimIndent(),
        )

        val site = SourceIndexer.build(listOf(dir.toFile())).sites.single()
        assertEquals("a.b.Baz", site.tag)
    }

    @Test
    fun kotlinSelfCanonicalNameResolvesToEnclosingClassFqn() {
        val dir = createTempDirectory("openlog-src")
        dir.write(
            "SelfCanonical.kt",
            """
            package a.b

            class C {
                val TAG = javaClass.canonicalName

                fun f() {
                    Log.d(TAG, "x")
                }
            }
            """.trimIndent(),
        )

        val site = SourceIndexer.build(listOf(dir.toFile())).sites.single()
        assertEquals("a.b.C", site.tag)
    }

    @Test
    fun kotlinSelfThisClassQualifiedNameResolvesToEnclosingClassFqn() {
        val dir = createTempDirectory("openlog-src")
        dir.write(
            "SelfThisQualified.kt",
            """
            package a.b

            class C {
                val TAG = this::class.qualifiedName

                fun f() {
                    Log.d(TAG, "x")
                }
            }
            """.trimIndent(),
        )

        val site = SourceIndexer.build(listOf(dir.toFile())).sites.single()
        assertEquals("a.b.C", site.tag)
    }

    @Test
    fun kotlinSelfThisClassJavaCanonicalNameResolvesToEnclosingClassFqn() {
        val dir = createTempDirectory("openlog-src")
        dir.write(
            "SelfThisJavaCanonical.kt",
            """
            package a.b

            class C {
                val TAG = this::class.java.canonicalName

                fun f() {
                    Log.d(TAG, "x")
                }
            }
            """.trimIndent(),
        )

        val site = SourceIndexer.build(listOf(dir.toFile())).sites.single()
        assertEquals("a.b.C", site.tag)
    }

    @Test
    fun javaSelfGetClassCanonicalNameConstResolvesToEnclosingClassFqn() {
        val dir = createTempDirectory("openlog-src")
        dir.write(
            "SelfGetClass.java",
            """
            package a.b;

            class C {
                private final String TAG = getClass().getCanonicalName();

                void f() {
                    Log.d(TAG, "x");
                }
            }
            """.trimIndent(),
        )

        val site = SourceIndexer.build(listOf(dir.toFile())).sites.single()
        assertEquals("a.b.C", site.tag)
    }

    @Test
    fun javaSelfInlineGetClassCanonicalNameResolvesToEnclosingClassFqn() {
        val dir = createTempDirectory("openlog-src")
        dir.write(
            "SelfGetClassInline.java",
            """
            package a.b;

            class C {
                void f() {
                    Log.d(getClass().getCanonicalName(), "x");
                }
            }
            """.trimIndent(),
        )

        val site = SourceIndexer.build(listOf(dir.toFile())).sites.single()
        assertEquals("a.b.C", site.tag)
    }

    @Test
    fun javaSelfThisGetClassCanonicalNameResolvesToEnclosingClassFqn() {
        val dir = createTempDirectory("openlog-src")
        dir.write(
            "SelfThisGetClass.java",
            """
            package a.b;

            class C {
                void f() {
                    Log.d(this.getClass().getCanonicalName(), "x");
                }
            }
            """.trimIndent(),
        )

        val site = SourceIndexer.build(listOf(dir.toFile())).sites.single()
        assertEquals("a.b.C", site.tag)
    }

    @Test
    fun qualifiedAccessorWithNoPackageDeclarationYieldsSimpleNameOnly() {
        val dir = createTempDirectory("openlog-src")
        dir.write(
            "NoPackage.kt",
            """
            class Foo {
                fun f() {
                    Log.d(Foo::class.java.canonicalName, "x")
                }
            }
            """.trimIndent(),
        )

        val site = SourceIndexer.build(listOf(dir.toFile())).sites.single()
        assertEquals("Foo", site.tag)
    }

    @Test
    fun threeFilesWithSameLogPatternResolveToDistinctFqnTags() {
        val dir = createTempDirectory("openlog-src")
        dir.write(
            "A.kt",
            """
            package p1

            class A {
                private val TAG = A::class.java.canonicalName

                fun initialize() {
                    Log.d(TAG, "initialize")
                }
            }
            """.trimIndent(),
        )
        dir.write(
            "B.kt",
            """
            package p2

            class B {
                private val TAG = B::class.java.canonicalName

                fun initialize() {
                    Log.d(TAG, "initialize")
                }
            }
            """.trimIndent(),
        )
        dir.write(
            "C.kt",
            """
            package p3

            class C {
                private val TAG = C::class.java.canonicalName

                fun initialize() {
                    Log.d(TAG, "initialize")
                }
            }
            """.trimIndent(),
        )

        val index = SourceIndexer.build(listOf(dir.toFile()))
        assertEquals(3, index.sites.size)
        val tags = index.sites.map { it.tag }.toSet()
        assertEquals(setOf("p1.A", "p2.B", "p3.C"), tags)

        val resolver = LogSourceResolver(index)
        assertEquals(1, resolver.resolve("p1.A", "initialize").size)
        assertEquals(1, resolver.resolve("p2.B", "initialize").size)
        assertEquals(1, resolver.resolve("p3.C", "initialize").size)
    }

    // ── `.name` (Class.name) accessor forms ──────────────────────────────────

    @Test
    fun kotlinInlineJavaClassNameResolvesToFqn() {
        val dir = createTempDirectory("openlog-src")
        dir.write(
            "Inline.kt",
            """
            package a.b

            class MyClass {
                fun f1() {
                    Log.d(MyClass::class.java.name, "x")
                }
            }
            """.trimIndent(),
        )

        val site = SourceIndexer.build(listOf(dir.toFile())).sites.single()
        assertEquals("a.b.MyClass", site.tag)
    }

    @Test
    fun kotlinConstJavaClassNameResolvesToEnclosingClassFqn() {
        val dir = createTempDirectory("openlog-src")
        dir.write(
            "C.kt",
            """
            package a.b

            class C {
                private val TAG = javaClass.name

                fun f() {
                    Log.d(TAG, "x")
                }
            }
            """.trimIndent(),
        )

        val site = SourceIndexer.build(listOf(dir.toFile())).sites.single()
        assertEquals("a.b.C", site.tag)
    }

    @Test
    fun javaSelfGetClassGetNameResolvesToEnclosingClassFqn() {
        val dir = createTempDirectory("openlog-src")
        dir.write(
            "C.java",
            """
            package a.b;

            class C {
                void f() {
                    Log.d(getClass().getName(), "x");
                }
            }
            """.trimIndent(),
        )

        val site = SourceIndexer.build(listOf(dir.toFile())).sites.single()
        assertEquals("a.b.C", site.tag)
    }

    @Test
    fun simpleNameFormsStillYieldSimpleNameAlongsideNameAccessor() {
        val dir = createTempDirectory("openlog-src")
        dir.write(
            "Mixed.kt",
            """
            package a.b

            class Mixed {
                fun f1() {
                    Log.d(Mixed::class.java.simpleName, "f1 fired")
                }

                fun f2() {
                    Log.d(Mixed::class.simpleName, "f2 fired")
                }
            }
            """.trimIndent(),
        )

        val index = SourceIndexer.build(listOf(dir.toFile()))
        assertEquals(2, index.sites.size)
        assertTrue(index.sites.all { it.tag == "Mixed" })
    }

    // ── Method boundaries ─────────────────────────────────────────────────

    @Test
    fun methodBoundariesSurviveNestedBracesAndStrayBraceInStringAndComment() {
        val dir = createTempDirectory("openlog-src")
        val source =
            """
            package demo

            class Widget {
                fun render(items: List<Int>) {
                    if (items.isNotEmpty()) {
                        items.forEach { x ->
                            if (x > 0) {
                                Log.d("UI", "rendering item ${'$'}x")
                            }
                        }
                    }
                    val weirdString = "a stray } brace inside a string"
                    // another stray } in a comment
                    Log.d("UI", "render done")
                } // END_RENDER
            }
            """.trimIndent()
        dir.write("Widget.kt", source)

        val lines = source.lines()
        val expectedStart = lines.indexOfFirst { it.contains("fun render") } + 1
        val expectedEnd = lines.indexOfFirst { it.contains("END_RENDER") } + 1

        val index = SourceIndexer.build(listOf(dir.toFile()))
        assertEquals(2, index.sites.size)
        for (site in index.sites) {
            assertEquals("render", site.methodName)
            assertEquals(expectedStart, site.methodStartLine)
            assertEquals(expectedEnd, site.methodEndLine)
        }
    }

    @Test
    fun initBlockCallResolvesToInitMethodNameWithMultiLineBounds() {
        val dir = createTempDirectory("openlog-src")
        dir.write(
            "InitBlock.kt",
            """
            package demo

            class InitBlock {
                companion object {
                    private const val TAG = "Init"
                }

                init {
                    Log.d(TAG, "created")
                }
            }
            """.trimIndent(),
        )

        val index = SourceIndexer.build(listOf(dir.toFile()))
        val site = index.sites.single()
        assertEquals("init", site.methodName)
        assertTrue(site.methodEndLine > site.methodStartLine)

        val matches = LogSourceResolver(index).resolve("Init", "created")
        assertEquals(1, matches.size)
        assertEquals("init", matches.single().site.methodName)
    }

    @Test
    fun secondaryConstructorCallResolvesToConstructorMethodNameWithMultiLineBounds() {
        val dir = createTempDirectory("openlog-src")
        dir.write(
            "SecondaryCtor.kt",
            """
            package demo

            class SecondaryCtor {
                companion object {
                    private const val TAG = "Ctor"
                }

                constructor(x: Int) {
                    Log.d(TAG, "ctor")
                }
            }
            """.trimIndent(),
        )

        val site = SourceIndexer.build(listOf(dir.toFile())).sites.single()
        assertEquals("constructor", site.methodName)
        assertTrue(site.methodEndLine > site.methodStartLine)
    }

    @Test
    fun regularFunStillResolvesToFunctionNameNextToInitAndConstructor() {
        val dir = createTempDirectory("openlog-src")
        dir.write(
            "PlainFun.kt",
            """
            package demo

            class PlainFun {
                fun doWork() {
                    Log.d("Tag", "working")
                }
            }
            """.trimIndent(),
        )

        val site = SourceIndexer.build(listOf(dir.toFile())).sites.single()
        assertEquals("doWork", site.methodName)
    }

    // ── Un-indexable / dynamic messages ─────────────────────────────────────

    @Test
    fun fullyDynamicMessageIsNotIndexed() {
        val dir = createTempDirectory("openlog-src")
        dir.write(
            "Dyn.kt",
            """
            package demo

            class Dyn {
                fun log(msg: String) {
                    Log.d(TAG, msg)
                }
            }
            """.trimIndent(),
        )

        val index = SourceIndexer.build(listOf(dir.toFile()))
        assertTrue(index.sites.isEmpty())
    }

    @Test
    fun interpolationWithNoStaticTextIsNotIndexed() {
        val dir = createTempDirectory("openlog-src")
        dir.write(
            "DynInterp.kt",
            """
            package demo

            class DynInterp {
                fun log(x: String) {
                    Log.d(TAG, "${'$'}x")
                }
            }
            """.trimIndent(),
        )

        val index = SourceIndexer.build(listOf(dir.toFile()))
        assertTrue(index.sites.isEmpty())
    }

    // ── Regex-injection safety ──────────────────────────────────────────────

    @Test
    fun matcherIsSafeAgainstRegexMetacharactersInLiteralText() {
        val dir = createTempDirectory("openlog-src")
        dir.write(
            "Price.kt",
            """
            package demo

            class Price {
                fun show() {
                    Log.d("Cost", "price (${'$'}5) is high")
                }
            }
            """.trimIndent(),
        )

        val index = SourceIndexer.build(listOf(dir.toFile()))
        // Fixture source literally contains: Log.d("Cost", "price ($5) is high") — the parens
        // must be treated as literal text (\Q...\E-quoted), not interpreted as a regex group.
        val matches = LogSourceResolver(index).resolve("Cost", "price ($5) is high")
        assertEquals(1, matches.size)
    }
}
