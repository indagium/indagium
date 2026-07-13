@file:OptIn(org.jetbrains.kotlin.gradle.ExperimentalKotlinGradlePluginApi::class)

import org.jetbrains.compose.desktop.application.dsl.TargetFormat

plugins {
    // Kotlin/Compose bumped 2.1.0 -> 2.4.0 so the app can consume the official Kotlin MCP SDK
    // (io.modelcontextprotocol:kotlin-sdk-server), which is built with Kotlin 2.4.0 and whose
    // metadata isn't consumable ~3 minor versions back. Compose Multiplatform 1.11.1 is the
    // matching pairing for Kotlin 2.4.0.
    kotlin("multiplatform") version "2.4.0"
    id("org.jetbrains.compose") version "1.11.1"
    id("org.jetbrains.kotlin.plugin.compose") version "2.4.0"
    id("io.gitlab.arturbosch.detekt") version "1.23.7"
    id("org.jlleitschuh.gradle.ktlint") version "12.1.2"
    // Kover 0.7.6 references KotlinJvmCompilation.compileKotlinTask, removed in the Kotlin 2.4.0
    // Gradle plugin — bumped to 0.9.8, which also uses the 0.8.0+ `kover { reports { ... } }` DSL.
    id("org.jetbrains.kotlinx.kover") version "0.9.8"
}

// MCP SDK 0.14.0 is built with Kotlin 2.3.21 (consumable by our 2.4.0 compiler) and Ktor 3.4.3;
// it exposes the mcpStreamableHttp {} Ktor helper the older 0.8.x line lacked.
val ktorVersion = "3.4.3"

val appVersion: String = providers.gradleProperty("app.version").get()
val appAuthor = "Roman Arnaut"
val generatedBuildInfoDir = layout.buildDirectory.dir("generated/openlogBuildInfo/desktopMain/kotlin")

val generateBuildInfo by tasks.registering {
    inputs.property("appVersion", appVersion)
    inputs.property("appAuthor", appAuthor)
    outputs.dir(generatedBuildInfoDir)
    doLast {
        val outputFile = generatedBuildInfoDir.get().file("com/openlog/generated/BuildInfo.kt").asFile
        outputFile.parentFile.mkdirs()
        outputFile.writeText(
            """
            package com.openlog.generated

            object BuildInfo {
                const val APP_VERSION: String = "$appVersion"
                const val APP_AUTHOR: String = "$appAuthor"
            }
            """.trimIndent() + "\n"
        )
    }
}

kotlin {
    // com.mikepenz:multiplatform-markdown-renderer (used for AI sidebar replies) ships class
    // files compiled for Java 21 (class file major version 65). Packaging with an older JDK
    // silently bundles a jlink runtime image that can't load it — the app builds and runs fine
    // until Markdown actually renders, then crashes with "compiled by a more recent version of
    // the Java Runtime". Pinning the toolchain here makes that a build-time requirement instead
    // of a runtime surprise, for `desktopRun`/`desktopTest` and for the packaging tasks alike.
    jvmToolchain(21)

    jvm("desktop") {
        mainRun {
            mainClass.set("MainKt")
        }
    }

    sourceSets {
        val desktopMain by getting {
            kotlin.srcDir(generatedBuildInfoDir)
            dependencies {
                implementation(compose.desktop.currentOs)
                implementation(compose.material3)
                implementation(compose.materialIconsExtended)
                implementation(compose.components.resources)
                implementation("org.apache.commons:commons-compress:1.28.0")
                implementation("org.tukaani:xz:1.10")
                // Native MCP server (ControlServer.kt): the app speaks MCP over Streamable HTTP
                // so clients connect by URL with no Node bridge. The SDK's mcpStreamableHttp {}
                // helper runs on Ktor (CIO engine); CORS lets browser-based MCP inspectors reach it.
                implementation("io.modelcontextprotocol:kotlin-sdk-server:0.14.0")
                implementation("io.ktor:ktor-server-cio:$ktorVersion")
                implementation("io.ktor:ktor-server-cors:$ktorVersion")
                // In-app AI providers use the same Ktor line as the MCP server. Keeping the
                // transport explicit avoids depending on the server engine transitively.
                implementation("io.ktor:ktor-client-cio:$ktorVersion")
                // Streaming AI answers are Markdown. This Compose Multiplatform renderer supports
                // the app's Material 3 stack without adding a web view or a second UI toolkit.
                implementation("com.mikepenz:multiplatform-markdown-renderer:0.41.0")
                implementation("com.mikepenz:multiplatform-markdown-renderer-m3:0.41.0")
            }
        }
        val desktopTest by getting
        desktopTest.dependencies {
            implementation(kotlin("test"))
            implementation("io.ktor:ktor-client-mock:$ktorVersion")
        }
    }
}

compose.desktop {
    application {
        mainClass = "MainKt"

        // Multi-GB logcat files materialize millions of parsed entries; the JVM default cap of
        // 25% of physical RAM leaves a 1.5GB file thrashing the GC on 8-16GB machines. Percentage
        // (not -Xmx) so small machines aren't over-committed; memory is only committed as used.
        jvmArgs("-XX:MaxRAMPercentage=50")

        // Linux only (jpackage builds for the host OS, so host OS == target OS here): lets
        // Main.kt overwrite sun.awt.X11.XToolkit.awtAppClassName so the window's WM_CLASS is
        // "openLog" instead of "MainKt" — required for docks/taskbars to match the running
        // window to the .desktop entry (StartupWMClass) and show the right name and icon.
        if (org.gradle.internal.os.OperatingSystem.current().isLinux) {
            jvmArgs("--add-opens=java.desktop/sun.awt.X11=ALL-UNNAMED")
        }

        nativeDistributions {
            targetFormats(TargetFormat.Dmg, TargetFormat.Msi, TargetFormat.Deb)
            packageName = "openLog"
            packageVersion = appVersion
            description = "Android logcat analysis tool"
            vendor = appAuthor
            copyright = "Copyright (C) 2026 $appAuthor"
            fileAssociation("text/plain", "log", "Log file")
            fileAssociation("text/plain", "txt", "Text log file")
            fileAssociation("text/plain", "logcat", "Android logcat file")
            fileAssociation("text/plain", "trace", "Trace log file")
            fileAssociation("text/plain", "out", "Output log file")
            // shared-mime-info (the freedesktop.org MIME database Linux distros ship) types
            // *.log as text/x-log, not text/plain — without this, .log (the app's primary
            // format) never matches on Linux even after the Exec %F / MimeType .deb fixes.
            // Linux-gated (jpackage always targets the host OS): the type means nothing on the
            // other two, and a second association claiming ".log" would hand the Windows MSI two
            // WiX Extension elements for one extension.
            if (org.gradle.internal.os.OperatingSystem.current().isLinux) {
                fileAssociation("text/x-log", "log", "Log file")
            }
            // Packaged builds ship a jlink-trimmed JVM, sized from jdeps' static analysis of our
            // jars. jdeps doesn't detect com.sun.net.httpserver.* (used by ControlServer.kt) as
            // a real dependency — it's a JDK-internal-looking package, not a public java.* API —
            // so without this the module (and the whole class) is silently missing at runtime:
            // NoClassDefFoundError: com/sun/net/httpserver/HttpServer the moment anyone enables
            // the MCP control server in a packaged .dmg/.deb/.msi. desktopRun never surfaces
            // this because it runs on your full local JDK, not the trimmed runtime image.
            modules("jdk.httpserver")
            macOS {
                bundleID = "com.romanarnaut.openlog"
                iconFile.set(project.file("icons/openlog.icns"))
            }
            windows {
                iconFile.set(project.file("icons/openlog.ico"))
            }
            linux {
                shortcut = true
                appCategory = "Development"
                menuGroup = "Development"
                iconFile.set(project.file("icons/openlog.png"))
            }
        }
    }
}

tasks.named("compileKotlinDesktop") {
    dependsOn(generateBuildInfo)
}

// jpackage/jlink bundle whatever JVM is running Gradle ITSELF into the native distribution's
// runtime image — this is completely independent of kotlin.jvmToolchain(21) above, which only
// pins the JDK used to compile/run our own code. A packaging run launched under an older JDK on
// PATH (e.g. a machine with both a Homebrew JDK 17 and IDE-managed JDK 21 installed, JAVA_HOME
// unset) silently bundles a JDK-17 runtime image: since our own compiled classes now target
// bytecode 65 (JDK 21, from the toolchain above), the packaged app fails to load its own MainKt
// and the .app appears to "immediately close" on launch with no visible error — this is the same
// root cause as the deferred Markdown-renderer crash fixed alongside this check, just hitting on
// the very first class load instead of a lazily-touched one. createRuntimeImage is the common
// ancestor of every packaging task (packageDmg/packageMsi/packageDeb/
// packageDistributionForCurrentOS), so gating there catches all of them in one place.
tasks.matching { it.name == "createRuntimeImage" }.configureEach {
    doFirst {
        check(JavaVersion.current().isCompatibleWith(JavaVersion.VERSION_21)) {
            "Packaging must be run with Gradle itself on JDK 21+ (currently ${JavaVersion.current()}) — " +
                "jpackage/jlink bundle whatever JVM launches Gradle, regardless of kotlin.jvmToolchain. " +
                "Set JAVA_HOME to a JDK 21+ install before running packageDmg/packageDeb/packageMsi/" +
                "packageDistributionForCurrentOS, e.g. on macOS: " +
                "JAVA_HOME=\$(/usr/libexec/java_home -v 21) ./gradlew packageDistributionForCurrentOS"
        }
    }
}

// Same heap headroom for dev runs (./gradlew desktopRun) as for the packaged app. Also forwards
// two optional -D properties from the Gradle command line into the app JVM (Gradle doesn't do
// this by itself): openlog.debugControl to enable the MCP control server (see Main.kt), and
// openlog.run.home to point user.home at a throwaway dir so automated/smoke runs don't touch
// the real ~/.openlog2 session state.
tasks.withType<JavaExec>().matching { it.name == "desktopRun" }.configureEach {
    jvmArgs("-XX:MaxRAMPercentage=50")
    if (org.gradle.internal.os.OperatingSystem.current().isLinux) {
        jvmArgs("--add-opens=java.desktop/sun.awt.X11=ALL-UNNAMED")
    }
    System.getProperty("openlog.debugControl")?.let { systemProperty("openlog.debugControl", it) }
    System.getProperty("openlog.run.home")?.let { systemProperty("user.home", it) }
}

// Manual large-file perf harness (LargeFilePerfHarness.kt) — activated by passing
// -Dopenlog.perf.file=<fixture path> to Gradle; needs a multi-GB heap for the ~1.5GB fixture.
// Normal test runs are unaffected (the property is blank and the harness returns immediately).
// Sandbox every test run's `user.home` to a throwaway dir under build/ — unconditionally, unlike
// desktopRun's opt-in openlog.run.home. Dozens of tests construct AppState() with no autosaveFile
// override, which otherwise resolves DesktopStorage.appDataDir() to the REAL
// ~/Library/Application Support/openLog2 (or platform equivalent): a test that calls
// autosaveNow() (directly, or via any state-mutating call — updateSettings, closeTab, etc.) then
// silently overwrites the developer's actual saved tabs/session/settings. Confirmed happening via
// AppStateBehaviorTest's autoExportNotes-toggle tests, which wiped a real autosave.cache.
val testHomeDir = layout.buildDirectory.dir("test-home").get().asFile
tasks.withType<Test>().configureEach {
    doFirst { testHomeDir.mkdirs() }
    systemProperty("user.home", testHomeDir.absolutePath)
}

val perfFixture: String? = System.getProperty("openlog.perf.file")
tasks.withType<Test>().configureEach {
    if (perfFixture != null) {
        maxHeapSize = "14g"
        systemProperty("openlog.perf.file", perfFixture)
        System.getProperty("openlog.perf.dense")?.let { systemProperty("openlog.perf.dense", it) }
        System.getProperty("openlog.perf.archive")?.let { systemProperty("openlog.perf.archive", it) }
    }
}

// ── Dependency locking ─────────────────────────────────────────────
// Locks only the desktop target's own compile/runtime/test classpaths, not
// dependencyLocking { lockAllConfigurations() }.
//
// compose.desktop.currentOs (desktopMain dependencies above) and its transitive Skiko runtime
// resolve to a different platform-specific artifact (desktop-jvm-macos-arm64 vs
// desktop-jvm-linux-x64 vs desktop-jvm-windows-x64, and the matching skiko-awt-runtime-*)
// depending on which OS Gradle runs on. A single shared gradle.lockfile can't hold "either
// this artifact or that one" for the same configuration — Gradle's lock validation requires
// every locked entry to actually be resolved, not just permits a subset — so locking a
// platform-specific artifact on one OS makes the lock file unsatisfiable on every other OS
// (this broke the Linux CI build: see the incident that added this comment). These two
// modules are excluded from locking entirely so each OS resolves its own native artifact
// freely; their version is already pinned via the Compose/Skiko plugin coordinates above, so
// the reproducibility loss is minimal. Regenerate with `./gradlew build --write-locks` after
// a dependency version bump.
dependencyLocking {
    ignoredDependencies.add("org.jetbrains.compose.desktop:desktop-jvm-*")
    ignoredDependencies.add("org.jetbrains.skiko:skiko-awt-runtime-*")
}

configurations.matching {
    it.name in
        setOf(
            "desktopCompileClasspath",
            "desktopRuntimeClasspath",
            "desktopTestCompileClasspath",
            "desktopTestRuntimeClasspath",
        )
}.configureEach {
    resolutionStrategy.activateDependencyLocking()
}

// ── Detekt ──────────────────────────────────────────────────────────
// Baselined (not ignoreFailures): pre-existing findings captured in config/detekt-baseline.xml
// stay suppressed, but any *new* finding not already in that baseline fails the build — this is
// what actually lets CI's `verify` job catch new debt instead of detekt findings being silently
// invisible to every build, local and CI, regardless of severity.
detekt {
    buildUponDefaultConfig = true
    config.setFrom(files("config/detekt.yml"))
    baseline = file("config/detekt-baseline.xml")
    source.setFrom("src/desktopMain/kotlin", "src/desktopTest/kotlin")
}

tasks.withType<io.gitlab.arturbosch.detekt.Detekt>().configureEach {
    reports {
        html.required.set(true)
        xml.required.set(true)
        txt.required.set(false)
        sarif.required.set(false)
    }
}

// ── ktlint ──────────────────────────────────────────────────────────
// Run: ./gradlew ktlintCheck --continue  (--continue writes all reports before failing)
ktlint {
    verbose.set(true)
    reporters {
        reporter(org.jlleitschuh.gradle.ktlint.reporter.ReporterType.HTML)
        reporter(org.jlleitschuh.gradle.ktlint.reporter.ReporterType.CHECKSTYLE)
    }
    filter {
        exclude("**/generated/**")
    }
}

tasks.matching { it.name == "runKtlintCheckOverDesktopMainSourceSet" }.configureEach {
    dependsOn(generateBuildInfo)
}

// ── Kover ───────────────────────────────────────────────────────────
// Run: ./gradlew koverHtmlReport
kover {
    reports {
        filters {
            excludes {
                // Exclude pure Compose UI files — these are rendering-only projections of
                // AppState and cannot be meaningfully unit-tested without a Compose harness.
                annotatedBy("androidx.compose.runtime.Composable")
                classes(
                    "com.openlog.ui.App*",
                    "com.openlog.ui.LogViewer*",
                    "com.openlog.ui.FilterPanel*",
                    "com.openlog.ui.AnnotationPanel*",
                    "com.openlog.ui.Components*",
                    "com.openlog.ui.Theme*",
                    "com.openlog.ui.MainKt",
                )
            }
        }
        total {
            html { onCheck = false }
            xml { onCheck = false }
        }
    }
}
