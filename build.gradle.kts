@file:OptIn(org.jetbrains.kotlin.gradle.ExperimentalKotlinGradlePluginApi::class)

import org.gradle.api.tasks.Exec
import org.gradle.api.tasks.testing.logging.TestExceptionFormat
import org.gradle.api.tasks.testing.logging.TestLogEvent
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

// bytedeco/javacv 1.5.13 (com.indagium.video.VideoPlayerController) — FFmpeg natives ship INSIDE
// the jar (no user install), decode every phone recording format incl. HEVC/.mov/WebM, and stay
// license-clean (Apache wrapper + LGPL FFmpeg build) — see the plan doc's "Decisions taken" for
// why this beat VLCJ (GPLv3), JavaFX Media (no HEVC/.mov), and GStreamer/libVLC-direct (both need
// a per-OS runtime install). javacppVersion tracks javacvVersion — bytedeco releases them in
// lockstep — while ffmpegVersion is "<ffmpeg-release>-<javacv-release>", bytedeco's own scheme for
// pinning which FFmpeg build a given javacv/javacpp pair was tested against.
val javacvVersion = "1.5.13"
val javacppVersion = "1.5.13"
val ffmpegVersion = "8.0.1-1.5.13"

// One native classifier per OS/arch, matching the packaging story already established for
// compose.desktop.currentOs/skiko below (each installer bundles only ITS OWN OS's natives — see
// "Artifact size = per-platform native classifiers" in the plan). Resolved from the machine
// running Gradle, exactly like jpackage building for the host OS only.
val bytedecoPlatform: String = run {
    val os = org.gradle.internal.os.OperatingSystem.current()
    val arch = System.getProperty("os.arch")
    val isArm = arch == "aarch64" || arch == "arm64"
    when {
        os.isMacOsX && isArm -> "macosx-arm64"
        os.isMacOsX -> "macosx-x86_64"
        os.isWindows -> "windows-x86_64"
        os.isLinux && isArm -> "linux-arm64"
        os.isLinux -> "linux-x86_64"
        else -> error("Unsupported OS/arch for bytedeco FFmpeg natives: ${os.name}/$arch")
    }
}

val appVersion: String = providers.gradleProperty("app.version").get()
val appAuthor = "Roman Arnaut"
val licenseVersion = "2026-07-19"
val generatedBuildInfoDir = layout.buildDirectory.dir("generated/indagiumBuildInfo/desktopMain/kotlin")
val generatedLicenseResourcesDir = layout.buildDirectory.dir("generated/indagiumLicenseResources/desktopMain/resources")
val generatedNativeResourcesDir = layout.buildDirectory.dir("generated/indagiumNativeResources/desktopMain/resources")
val isMacHost = org.gradle.internal.os.OperatingSystem.current().isMacOsX
val isWindowsHost = org.gradle.internal.os.OperatingSystem.current().isWindows
val isLinuxHost = org.gradle.internal.os.OperatingSystem.current().isLinux

// Compose Desktop's native distribution plugin delegates Linux installers to jpackage, which
// supports .deb but not portable AppImage or sandboxed Flatpak bundles.  These wrappers both use
// the same createDistributable output as jpackage, so all three Linux formats ship the identical
// app jars and jlink runtime.  AppImage/Flatpak architecture spellings follow their respective
// ecosystems (x86_64/aarch64 rather than Debian's amd64/arm64).
val linuxBundleArchitecture = when (System.getProperty("os.arch").lowercase()) {
    "amd64", "x86_64" -> "x86_64"
    "aarch64", "arm64" -> "aarch64"
    else -> "unsupported"
}
val composeAppDirectory = layout.buildDirectory.dir("compose/binaries/main/app/Indagium")
val appImageOutput = layout.buildDirectory.file(
    "compose/binaries/main/appimage/Indagium-$appVersion-$linuxBundleArchitecture.AppImage",
)
val flatpakOutput = layout.buildDirectory.file(
    "compose/binaries/main/flatpak/Indagium-$appVersion-$linuxBundleArchitecture.flatpak",
)

// Apple Speech is called inside the JVM process through a very small Objective-C JNI bridge.
// Keeping it generated rather than committed as a binary makes the native code reviewable and
// lets macOS code-signing/notarization sign the exact dylib produced for the release.
val compileAppleSpeechNative by tasks.registering(Exec::class) {
    onlyIf { isMacHost }
    val output = generatedNativeResourcesDir.get().file("native/macos/libindagium_speech.dylib").asFile
    inputs.file("native/macos/indagium_speech.m")
    outputs.file(output)
    doFirst {
        output.parentFile.mkdirs()
        val javaHome = javaToolchains.launcherFor {
            languageVersion.set(JavaLanguageVersion.of(21))
        }.get().metadata.installationPath.asFile
        commandLine(
            "clang", "-dynamiclib", "-fobjc-arc", "-fblocks",
            "-I$javaHome/include", "-I$javaHome/include/darwin",
            "-framework", "Foundation", "-framework", "Speech", "-framework", "AVFoundation",
            "native/macos/indagium_speech.m", "-o", output.absolutePath,
        )
    }
}

val generateBuildInfo by tasks.registering {
    inputs.property("appVersion", appVersion)
    inputs.property("appAuthor", appAuthor)
    inputs.property("licenseVersion", licenseVersion)
    outputs.dir(generatedBuildInfoDir)
    doLast {
        val outputFile = generatedBuildInfoDir.get().file("com/indagium/generated/BuildInfo.kt").asFile
        outputFile.parentFile.mkdirs()
        outputFile.writeText(
            """
            package com.indagium.generated

            object BuildInfo {
                const val APP_VERSION: String = "$appVersion"
                const val APP_AUTHOR: String = "$appAuthor"
                const val LICENSE_VERSION: String = "$licenseVersion"
            }
            """.trimIndent() + "\n"
        )
    }
}

// Keep the in-app agreement derived from the repository's canonical legal documents. This avoids
// displaying one set of terms in packaged builds while publishing different terms at the project root.
val generateLicenseResources by tasks.registering {
    val licenseDocuments = listOf("LICENSE", "NOTICE")
    inputs.files(licenseDocuments.map(::file))
    outputs.dir(generatedLicenseResourcesDir)
    doLast {
        val outputFile = generatedLicenseResourcesDir.get().file("licenses/indagium-license-agreement.md").asFile
        outputFile.parentFile.mkdirs()
        val agreement = licenseDocuments.joinToString(separator = "\n\n---\n\n") { path ->
            file(path).readText().trimEnd()
        }
        outputFile.writeText("# Indagium License Agreement\n\nTerms version: $licenseVersion\n\n---\n\n$agreement\n")
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
            resources.srcDir(generatedLicenseResourcesDir)
            resources.srcDir(generatedNativeResourcesDir)
            dependencies {
                implementation(compose.desktop.currentOs)
                implementation(compose.material3)
                implementation(compose.materialIconsExtended)
                implementation(compose.components.resources)
                implementation("org.apache.commons:commons-compress:1.28.0")
                implementation("org.tukaani:xz:1.12")
                // Native MCP server (ControlServer.kt): the app speaks MCP over Streamable HTTP
                // so clients connect by URL with no Node bridge. The SDK's mcpStreamableHttp {}
                // helper runs on Ktor (CIO engine); CORS lets browser-based MCP inspectors reach it.
                implementation("io.modelcontextprotocol:kotlin-sdk-server:0.14.0")
                implementation("io.ktor:ktor-server-cio:$ktorVersion")
                implementation("io.ktor:ktor-server-cors:$ktorVersion")
                // In-app AI providers use the same Ktor line as the MCP server. Keeping the
                // transport explicit avoids depending on the server engine transitively.
                implementation("io.ktor:ktor-client-cio:$ktorVersion")
                // Offline AI-composer dictation. The jar carries the macOS arm64/x64 whisper.cpp
                // natives; the multilingual model itself is deliberately downloaded only after an
                // explicit user action (see voice/VoiceModelCatalog.kt).
                implementation("io.github.givimad:whisper-jni:1.7.1")
                // Streaming AI answers are Markdown. This Compose Multiplatform renderer supports
                // the app's Material 3 stack without adding a web view or a second UI toolkit.
                implementation("com.mikepenz:multiplatform-markdown-renderer:0.41.0")
                implementation("com.mikepenz:multiplatform-markdown-renderer-m3:0.41.0")
                // The MCP SDK and Ktor both log through SLF4J internally; without a binding on the
                // classpath, every launch prints "No SLF4J providers were found" to the console.
                // The app itself never calls SLF4J directly and has no logging config to maintain,
                // so a no-op binding (silently discard) is the right fit here, not a real backend
                // like logback. Version pinned to match the resolved slf4j-api transitive version.
                runtimeOnly("org.slf4j:slf4j-nop:2.0.17")
                // video/VideoPlayerController.kt — see the bytedeco version block above for why
                // this trio (javacpp + ffmpeg's platform-neutral jar + ffmpeg's native classifier
                // jar) rather than javacv-platform/ffmpeg-platform, which would bundle every OS's
                // natives into a single installer instead of just this build's own.
                implementation("org.bytedeco:javacv:$javacvVersion") {
                    // javacv's own POM lists EVERY wrapped native library (opencv, ffmpeg, leptonica/
                    // tesseract, openblas, flycapture, librealsense(2), libdc1394, libfreenect(2),
                    // videoinput, artoolkitplus) as plain (non-optional) dependencies — this app
                    // only ever uses FFmpegFrameGrabber, so all of those are excluded; ffmpeg itself
                    // is re-added below as an explicit classifier pair instead of javacv's
                    // no-classifier (= "every OS's natives") transitive default.
                    exclude(group = "org.bytedeco", module = "ffmpeg")
                    exclude(group = "org.bytedeco", module = "opencv")
                    exclude(group = "org.bytedeco", module = "openblas")
                    exclude(group = "org.bytedeco", module = "leptonica")
                    exclude(group = "org.bytedeco", module = "tesseract")
                    exclude(group = "org.bytedeco", module = "flycapture")
                    exclude(group = "org.bytedeco", module = "libdc1394")
                    exclude(group = "org.bytedeco", module = "libfreenect")
                    exclude(group = "org.bytedeco", module = "libfreenect2")
                    exclude(group = "org.bytedeco", module = "librealsense")
                    exclude(group = "org.bytedeco", module = "librealsense2")
                    exclude(group = "org.bytedeco", module = "videoinput")
                    exclude(group = "org.bytedeco", module = "artoolkitplus")
                }
                implementation("org.bytedeco:javacpp:$javacppVersion")
                implementation("org.bytedeco:javacpp:$javacppVersion:$bytedecoPlatform")
                implementation("org.bytedeco:ffmpeg:$ffmpegVersion")
                implementation("org.bytedeco:ffmpeg:$ffmpegVersion:$bytedecoPlatform")
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

        // Company TLS-inspection roots are often installed only in the Windows certificate store,
        // not in the bundled JRE's static cacerts file. Let the Windows installer trust the same
        // roots as the rest of the OS, including when UpdateChecker follows GitHub's asset redirect.
        // The property must be present before Ktor creates its first TLS client; NONE stops JSSE
        // from attempting to load the default cacerts path as a Windows-native keystore.
        if (isWindowsHost) {
            jvmArgs(
                "-Djavax.net.ssl.trustStoreType=Windows-ROOT",
                "-Djavax.net.ssl.trustStore=NONE",
            )
        }

        // Linux only (jpackage builds for the host OS, so host OS == target OS here): lets
        // Main.kt overwrite sun.awt.X11.XToolkit.awtAppClassName so the window's WM_CLASS is
        // "Indagium" instead of "MainKt" — required for docks/taskbars to match the running
        // window to the .desktop entry (StartupWMClass) and show the right name and icon.
        if (org.gradle.internal.os.OperatingSystem.current().isLinux) {
            jvmArgs("--add-opens=java.desktop/sun.awt.X11=ALL-UNNAMED")
        }

        nativeDistributions {
            targetFormats(TargetFormat.Dmg, TargetFormat.Msi, TargetFormat.Deb)
            packageName = "Indagium"
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
            // Windows-ROOT is implemented by the SunMSCAPI provider. It is dynamically chosen
            // from the JVM trust-store properties above, so jdeps cannot discover it on its own.
            // Keep it Windows-only: the module does not exist in macOS/Linux JDKs.
            if (isWindowsHost) modules("jdk.crypto.mscapi")
            macOS {
                bundleID = "com.indagium.desktop"
                iconFile.set(project.file("icons/indagium.icns"))
                // jpackage's defaults cover JVM JIT, but microphone access from its hardened
                // launcher also needs the explicit audio-input entitlement. Keep the same file
                // on the bundled runtime so a release signing/notarization pass preserves it.
                entitlementsFile.set(project.file("macos/Indagium.entitlements"))
                runtimeEntitlementsFile.set(project.file("macos/Indagium.entitlements"))
                // macOS rejects microphone access from a bundled app without this purpose string.
                infoPlist {
                    extraKeysRawXml = """
                        <key>NSMicrophoneUsageDescription</key>
                        <string>Indagium uses the microphone only to turn your AI question into local text on this device.</string>
                        <key>NSSpeechRecognitionUsageDescription</key>
                        <string>Indagium uses Apple Speech only to turn your recorded AI question into text on this device.</string>
                    """.trimIndent()
                }
            }
            windows {
                iconFile.set(project.file("icons/indagium.ico"))
            }
            linux {
                shortcut = true
                appCategory = "Development"
                menuGroup = "Development"
                iconFile.set(project.file("icons/indagium.png"))
            }
        }
    }
}

tasks.named("compileKotlinDesktop") {
    dependsOn(generateBuildInfo)
    dependsOn(generateLicenseResources)
}

tasks.matching { it.name.contains("ProcessResources", ignoreCase = true) }.configureEach {
    dependsOn(generateLicenseResources)
    if (isMacHost) dependsOn(compileAppleSpeechNative)
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

// Linux portable packages.  Both scripts deliberately run outside Gradle's configuration phase:
// they download/build only when their task is explicitly requested, and they keep generated
// staging trees under build/ so a clean checkout contains only auditable source metadata.
tasks.register<Exec>("packageAppImage") {
    group = "distribution"
    description = "Packages the Linux Compose distributable as an AppImage (x86_64 or aarch64)."
    dependsOn("createDistributable")
    onlyIf("AppImage can only be built on Linux") { isLinuxHost }
    inputs.dir(composeAppDirectory)
    inputs.files(
        "scripts/package-appimage.sh",
        "packaging/linux/com.indagium.Indagium.desktop",
        "packaging/linux/indagium-mimeinfo.xml",
        "icons/indagium.png",
    )
    outputs.file(appImageOutput)
    workingDir(projectDir)
    commandLine(
        "bash",
        file("scripts/package-appimage.sh").absolutePath,
        "--input", composeAppDirectory.get().asFile.absolutePath,
        "--output", appImageOutput.get().asFile.absolutePath,
        "--version", appVersion,
        "--arch", linuxBundleArchitecture,
    )
}

tasks.register<Exec>("packageFlatpak") {
    group = "distribution"
    description = "Packages the Linux Compose distributable as a Flatpak bundle (x86_64 or aarch64)."
    dependsOn("createDistributable")
    onlyIf("Flatpak can only be built on Linux") { isLinuxHost }
    inputs.dir(composeAppDirectory)
    inputs.files(
        "scripts/package-flatpak.sh",
        "packaging/linux/com.indagium.Indagium.yml",
        "packaging/linux/com.indagium.Indagium.desktop",
        "packaging/linux/com.indagium.Indagium.metainfo.xml",
        "packaging/linux/com.indagium.Indagium.png",
        "packaging/linux/flatpak-launcher.sh",
        "packaging/linux/indagium-mimeinfo.xml",
        "packaging/linux/validate-flatpak-icon.py",
    )
    outputs.file(flatpakOutput)
    workingDir(projectDir)
    commandLine(
        "bash",
        file("scripts/package-flatpak.sh").absolutePath,
        "--input", composeAppDirectory.get().asFile.absolutePath,
        "--output", flatpakOutput.get().asFile.absolutePath,
        "--version", appVersion,
        "--arch", linuxBundleArchitecture,
    )
}

// Same heap headroom for dev runs (./gradlew desktopRun) as for the packaged app. Also forwards
// two optional -D properties from the Gradle command line into the app JVM (Gradle doesn't do
// this by itself): indagium.debugControl to enable the MCP control server (see Main.kt), and
// indagium.run.home to point user.home at a throwaway dir so automated/smoke runs don't touch
// the real ~/Library/Application Support/Indagium (or platform equivalent) session state.
tasks.withType<JavaExec>().matching { it.name == "desktopRun" }.configureEach {
    jvmArgs("-XX:MaxRAMPercentage=50")
    if (org.gradle.internal.os.OperatingSystem.current().isLinux) {
        jvmArgs("--add-opens=java.desktop/sun.awt.X11=ALL-UNNAMED")
    }
    System.getProperty("indagium.debugControl")?.let { systemProperty("indagium.debugControl", it) }
    System.getProperty("indagium.run.home")?.let { systemProperty("user.home", it) }
}

// Manual large-file perf harness (LargeFilePerfHarness.kt) — activated by passing
// -Dindagium.perf.file=<fixture path> to Gradle; needs a multi-GB heap for the ~1.5GB fixture.
// Normal test runs are unaffected (the property is blank and the harness returns immediately).
// Sandbox every test run's `user.home` to a throwaway dir under build/ — unconditionally, unlike
// desktopRun's opt-in indagium.run.home. Dozens of tests construct AppState() with no autosaveFile
// override, which otherwise resolves DesktopStorage.appDataDir() to the REAL
// ~/Library/Application Support/Indagium (or platform equivalent): a test that calls
// autosaveNow() (directly, or via any state-mutating call — updateSettings, closeTab, etc.) then
// silently overwrites the developer's actual saved tabs/session/settings. Confirmed happening via
// AppStateBehaviorTest's autoExportNotes-toggle tests, which wiped a real autosave.cache.
val testHomeDir = layout.buildDirectory.dir("test-home").get().asFile
tasks.withType<Test>().configureEach {
    doFirst { testHomeDir.mkdirs() }
    systemProperty("user.home", testHomeDir.absolutePath)
}

// Default testLogging only prints "<Test> FAILED" plus the exception's class/file/line — enough
// to point at a test, not enough to diagnose it. A CI-only failure (see the flaky-under-load note
// on AppStateBehaviorTest's waitUntil helper) is otherwise unforensicable after the runner is
// torn down: this is the only record once build/reports/tests isn't there to read locally. FULL
// prints the assertion's expected/actual message and the complete stack trace inline in the
// Gradle console log the CI workflow already captures, so a failure that only reproduces on a
// GitHub runner is diagnosable from that log alone, without rerunning or adding artifact uploads.
tasks.withType<Test>().configureEach {
    testLogging {
        events(TestLogEvent.FAILED)
        exceptionFormat = TestExceptionFormat.FULL
        showStackTraces = true
        showCauses = true
    }
}

val perfFixture: String? = System.getProperty("indagium.perf.file")
tasks.withType<Test>().configureEach {
    if (perfFixture != null) {
        maxHeapSize = "14g"
        systemProperty("indagium.perf.file", perfFixture)
        System.getProperty("indagium.perf.dense")?.let { systemProperty("indagium.perf.dense", it) }
        System.getProperty("indagium.perf.archive")?.let { systemProperty("indagium.perf.archive", it) }
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
    // NOTE: org.bytedeco:ffmpeg/javacpp are NOT added here despite also varying by OS/arch
    // (bytedecoPlatform above) — unlike skiko-awt-runtime-* (a genuinely different MODULE NAME
    // per OS: "skiko-awt-runtime-macos-arm64" vs "-linux-x64"), bytedeco publishes one module
    // (group:artifact:version) with per-platform CLASSIFIERS of that same module. Gradle's
    // dependency lock file is keyed at the module (group:artifact:version) level, not per
    // classifier, so the same lock entry is satisfied on every OS regardless of which classifier
    // gets pulled in — confirmed by `./gradlew build --write-locks` succeeding here unmodified.
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
                    "com.indagium.ui.App*",
                    "com.indagium.ui.LogViewer*",
                    "com.indagium.ui.FilterPanel*",
                    "com.indagium.ui.AnnotationPanel*",
                    "com.indagium.ui.Components*",
                    "com.indagium.ui.Theme*",
                )
            }
        }
        total {
            html { onCheck = false }
            xml { onCheck = false }
        }
    }
}
