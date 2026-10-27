@file:OptIn(org.jetbrains.kotlin.gradle.ExperimentalKotlinGradlePluginApi::class)

import org.gradle.api.tasks.Exec
import org.gradle.api.tasks.testing.logging.TestExceptionFormat
import org.gradle.api.tasks.testing.logging.TestLogEvent
import org.jetbrains.compose.desktop.application.dsl.TargetFormat
import java.io.File
import java.security.MessageDigest
import java.util.zip.ZipFile

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

// bytedeco/javacv 1.5.13 (com.indagium.video.VideoPlayerController) — FFmpeg natives ship inside
// the jar (no user install) and decode phone recordings including HEVC/.mov/WebM. The inspected
// FFmpeg classifier is shared and LGPLv3+; see the canonical media inventory and
// THIRD_PARTY_NOTICES.md for its complete component closure and redistribution rights.
// This beat VLCJ (GPLv3), JavaFX Media (no HEVC/.mov), and GStreamer/libVLC-direct (both need
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

// zstd-jni ships one classifier jar per OS/arch containing the Java classes plus only that
// platform's native library (the unclassified jar carries every platform's natives, ~7 MB).
// Same host-only resolution story as bytedecoPlatform above; the names follow zstd-jni's own
// classifier scheme (darwin_aarch64, win_amd64, ...), not bytedeco's.
val zstdJniPlatform: String = run {
    val os = org.gradle.internal.os.OperatingSystem.current()
    val arch = System.getProperty("os.arch")
    val isArm = arch == "aarch64" || arch == "arm64"
    when {
        os.isMacOsX && isArm -> "darwin_aarch64"
        os.isMacOsX -> "darwin_x86_64"
        os.isWindows -> "win_amd64"
        os.isLinux && isArm -> "linux_aarch64"
        os.isLinux -> "linux_amd64"
        else -> error("Unsupported OS/arch for zstd-jni natives: ${os.name}/$arch")
    }
}

val appVersion: String = providers.gradleProperty("app.version").get()
val appAuthor = "Roman Arnaut"
val distributionLicenseKind = providers.gradleProperty("indagium.license").getOrElse("polyform").also {
    require(it in setOf("polyform", "eula")) { "indagium.license must be polyform or eula (was $it)" }
}
val licenseDocument = if (distributionLicenseKind == "eula") "EULA.md" else "LICENSE"
val licenseDocumentVersion = if (distributionLicenseKind == "eula") "1.0.0" else "2026-07-19"
val licenseTitle = if (distributionLicenseKind == "eula") "Indagium End User License Agreement" else "Indagium Source License Agreement"
val licenseBody = file(licenseDocument).readText().replace("\r\n", "\n").trimEnd() +
    if (distributionLicenseKind == "polyform") "\n\n---\n\n" + file("NOTICE").readText().replace("\r\n", "\n").trimEnd() else ""
val licenseDocumentDigest = MessageDigest.getInstance("SHA-256").digest(licenseBody.toByteArray(Charsets.UTF_8))
    .joinToString("") { "%02x".format(it) }
val licenseVersion = "$distributionLicenseKind-$licenseDocumentVersion:$licenseDocumentDigest"
val renderedLicenseAgreement = "# $licenseTitle\n\nTerms version: $licenseVersion\n\n---\n\n$licenseBody\n"
val generatedBuildInfoDir = layout.buildDirectory.dir("generated/indagiumBuildInfo/desktopMain/kotlin")
val generatedLicenseResourcesDir = layout.buildDirectory.dir("generated/indagiumLicenseResources/desktopMain/resources")
val generatedNativeResourcesDir = layout.buildDirectory.dir("generated/indagiumNativeResources/desktopMain/resources")
val mediaLegalDirectory = file("licenses/third-party")
val mediaSourceScript = file("scripts/prepare_media_sources.py")
val pythonExecutable = if (org.gradle.internal.os.OperatingSystem.current().isWindows) "python" else "python3"
val mediaLicenseInventory = file("licenses/third-party/media-components.json")
val applicationLegalDirectory = file("licenses/application")
val applicationLicenseInventory = file("licenses/application/application-components.json")
val mediaSourceLock = file("licenses/third-party/media-source-lock.json")
val mediaPlatform = when (bytedecoPlatform) {
    "macosx-arm64" -> "macos-arm64"
    "windows-x86_64" -> "windows-x64"
    "linux-x86_64" -> "linux-x64"
    "linux-arm64" -> "linux-arm64"
    else -> "unsupported"
}
val scrcpyServerResource = layout.projectDirectory.file("src/desktopMain/resources/scrcpy/scrcpy-server-v4.1")
val scrcpyServerMetadata = layout.projectDirectory.file("src/desktopMain/resources/scrcpy/scrcpy-server.properties")
val isMacHost = org.gradle.internal.os.OperatingSystem.current().isMacOsX
val isWindowsHost = org.gradle.internal.os.OperatingSystem.current().isWindows
val isLinuxHost = org.gradle.internal.os.OperatingSystem.current().isLinux

// The embedded mirror is deliberately offline-only: this exact, independently verified scrcpy
// server asset is packaged when present, but Gradle never downloads it. A source checkout with
// the asset removed remains buildable and the runtime reports an actionable message.
val validateScrcpyServerAsset by tasks.registering {
    inputs.file(scrcpyServerMetadata)
    doLast {
        if (!scrcpyServerResource.asFile.isFile) {
            logger.lifecycle("Embedded scrcpy mirror disabled: ${scrcpyServerResource.asFile} is absent")
            return@doLast
        }
        val expected = "deacb991ed2509715160ffdc7907e47b4160eb30d1566217e9047fd5b8850cae"
        val actual = MessageDigest.getInstance("SHA-256")
            .digest(scrcpyServerResource.asFile.readBytes())
            .joinToString("") { byte -> "%02x".format(byte) }
        check(actual == expected) {
            "Embedded scrcpy server checksum mismatch: expected $expected, got $actual"
        }
    }
}

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
// The readable legal/ folder goes inside jpackage's app directory on every OS: when an installer
// is built from --app-image, jpackage on Linux and Windows copies only its own layout (launchers,
// app, runtime), so a folder at the image root is missing from the .deb and .msi.
val mediaPackageRoot = when {
    isMacHost -> layout.buildDirectory.dir("compose/binaries/main/app/Indagium.app/Contents/app")
    isWindowsHost -> composeAppDirectory.map { it.dir("app") }
    else -> composeAppDirectory.map { it.dir("lib/app") }
}
val distributionImageRoot = if (isMacHost) {
    layout.buildDirectory.dir("compose/binaries/main/app/Indagium.app")
} else {
    composeAppDirectory
}
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

// Mirror-only macOS acceleration is a deliberately small JNI dylib: VideoToolbox decodes H.264
// into CVPixelBuffers and Metal presents them in a JAWT SurfaceLayers-hosted CAMetalLayer. It is
// generated during packaging just like the existing Apple Speech bridge, so a signed app contains
// one reviewable, host-native binary and no JOGL/VLC/GStreamer runtime.
val compileMacMirrorNative by tasks.registering(Exec::class) {
    onlyIf { isMacHost }
    val output = generatedNativeResourcesDir.get().file("native/macos/libindagium_mirror.dylib").asFile
    inputs.file("native/macos/indagium_mirror.mm")
    outputs.file(output)
    doFirst {
        output.parentFile.mkdirs()
        val javaHome = javaToolchains.launcherFor {
            languageVersion.set(JavaLanguageVersion.of(21))
        }.get().metadata.installationPath.asFile
        commandLine(
            "clang++", "-dynamiclib", "-std=c++17", "-fobjc-arc",
            "-I$javaHome/include", "-I$javaHome/include/darwin",
            "-L$javaHome/lib", "-ljawt",
            "-framework", "Foundation", "-framework", "AppKit", "-framework", "QuartzCore",
            "-framework", "VideoToolbox", "-framework", "CoreMedia", "-framework", "CoreVideo", "-framework", "Metal",
            "native/macos/indagium_mirror.mm", "-o", output.absolutePath,
        )
    }
}

// Linux mirror decode stays in the FFmpeg version already bundled by JavaCPP. The JNI bridge does
// not link to FFmpeg; it imports AVVAAPIDeviceContext from the pinned public 8.0.1 header, exports
// the VA surface as DRM PRIME, and presents it through EGL without a CPU pixel transfer.
val compileLinuxMirrorNative by tasks.registering(Exec::class) {
    onlyIf { isLinuxHost }
    val output = generatedNativeResourcesDir.get().file("native/linux/libindagium_mirror.so").asFile
    inputs.files("native/linux/indagium_mirror.cpp", "native/linux/include/libavutil/hwcontext_vaapi.h")
    outputs.file(output)
    doFirst {
        output.parentFile.mkdirs()
        val javaHome = javaToolchains.launcherFor {
            languageVersion.set(JavaLanguageVersion.of(21))
        }.get().metadata.installationPath.asFile
        commandLine(
            "c++", "-std=c++17", "-fPIC", "-shared",
            "-I${file("native/linux/include").absolutePath}",
            "-I$javaHome/include", "-I$javaHome/include/linux",
            "-L$javaHome/lib", "-L$javaHome/lib/server", "-Wl,-rpath,$javaHome/lib",
            "native/linux/indagium_mirror.cpp", "-o", output.absolutePath,
            "-ljawt", "-lEGL", "-lGLESv2", "-lX11", "-lva",
        )
    }
}

// The speech recognizer is a small app-owned JNI adapter around pinned whisper.cpp. FetchContent
// downloads and verifies the release source into build/native/whisper/_deps; no JNI/native binary
// is committed or fetched as a Maven runtime dependency. The release link statically includes
// Whisper and GGML so installers do not depend on a separately installed native runtime.
val whisperNativeBuildDir = layout.buildDirectory.dir("native/whisper")
val whisperSourceLicense = whisperNativeBuildDir.map { it.file("_deps/whispercpp-src/LICENSE") }
val whisperNativeResourceFile = when {
    isMacHost -> generatedNativeResourcesDir.get().file("native/macos/libindagium_whisper.dylib")
    isWindowsHost -> generatedNativeResourcesDir.get().file("native/windows/indagium_whisper.dll")
    isLinuxHost -> generatedNativeResourcesDir.get().file("native/linux/libindagium_whisper.so")
    else -> error("Local Whisper native bridge does not support this OS")
}
val java21Home = javaToolchains.launcherFor { languageVersion.set(JavaLanguageVersion.of(21)) }
val configureWhisperNative by tasks.registering(Exec::class) {
    group = "build"
    description = "Configures the pinned whisper.cpp and app-owned JNI bridge for this host."
    inputs.file("native/whisper/CMakeLists.txt")
    inputs.file("native/whisper/indagium_whisper.cpp")
    inputs.file("scripts/configure-windows-whisper.cmd")
    outputs.file(whisperNativeBuildDir.map { it.file("CMakeCache.txt") })
    doFirst {
        val jdkPath = java21Home.get().metadata.installationPath.asFile.absolutePath
        if (isWindowsHost) {
            // Git Bash does not inherit Visual Studio's C++ toolchain environment on hosted
            // Windows runners. Configure through the same VsDevCmd bootstrap used by the
            // D3D11 mirror bridge, so CMake can discover the VS generator reliably.
            val script = file("scripts/configure-windows-whisper.cmd")
            val configureCommand = listOf(
                "call",
                "\"${script.absolutePath}\"",
                "\"$jdkPath\"",
                "\"${file("native/whisper").absolutePath}\"",
                "\"${whisperNativeBuildDir.get().asFile.absolutePath}\"",
                "\"${whisperNativeResourceFile.asFile.parentFile.absolutePath}\"",
            ).joinToString(" ")
            commandLine(
                "cmd.exe", "/d", "/c",
                configureCommand,
            )
        } else {
            commandLine(
                "cmake", "-S", file("native/whisper").absolutePath,
                "-B", whisperNativeBuildDir.get().asFile.absolutePath,
                "-DCMAKE_BUILD_TYPE=Release",
                "-DCMAKE_POSITION_INDEPENDENT_CODE=ON",
                "-DCMAKE_LIBRARY_OUTPUT_DIRECTORY=${whisperNativeResourceFile.asFile.parentFile.absolutePath}",
                "-DCMAKE_RUNTIME_OUTPUT_DIRECTORY=${whisperNativeResourceFile.asFile.parentFile.absolutePath}",
                "-DCMAKE_LIBRARY_OUTPUT_DIRECTORY_RELEASE=${whisperNativeResourceFile.asFile.parentFile.absolutePath}",
                "-DCMAKE_RUNTIME_OUTPUT_DIRECTORY_RELEASE=${whisperNativeResourceFile.asFile.parentFile.absolutePath}",
                "-DJAVA_HOME=$jdkPath",
                "-G", "Unix Makefiles",
            )
        }
    }
}
val compileWhisperNative by tasks.registering(Exec::class) {
    group = "build"
    description = "Builds the app-owned local Whisper bridge and statically linked whisper.cpp."
    dependsOn(configureWhisperNative)
    inputs.file("native/whisper/CMakeLists.txt")
    inputs.file("native/whisper/indagium_whisper.cpp")
    outputs.file(whisperNativeResourceFile)
    doFirst { whisperNativeResourceFile.asFile.parentFile.mkdirs() }
    commandLine(
        "cmake", "--build", whisperNativeBuildDir.get().asFile.absolutePath,
        "--config", "Release", "--target", "indagium_whisper", "--parallel",
    )
}

// Windows uses the bundled FFmpeg D3D11VA decoder and a small D3D11 video-processor swapchain
// presenter. The Visual Studio C++ tools and Windows SDK on windows-latest provide the headers and
// import libraries; no native runtime dependency is added to the MSI.
val compileWindowsMirrorNative by tasks.registering(Exec::class) {
    onlyIf { isWindowsHost }
    val output = generatedNativeResourcesDir.get().file("native/windows/indagium_mirror.dll").asFile
    inputs.files("native/windows/indagium_mirror.cpp", "scripts/compile-windows-mirror.cmd")
    outputs.file(output)
    doFirst {
        val javaHome = javaToolchains.launcherFor {
            languageVersion.set(JavaLanguageVersion.of(21))
        }.get().metadata.installationPath.asFile
        val script = file("scripts/compile-windows-mirror.cmd")
        val source = file("native/windows/indagium_mirror.cpp")
        commandLine(
            "cmd.exe", "/d", "/c",
            "call \"${script.absolutePath}\" \"${javaHome.absolutePath}\" \"${source.absolutePath}\" \"${output.absolutePath}\"",
        )
    }
}

// Focused host-native checks for the custom Annex-B parser and VideoToolbox session lifecycle.
// These exercise the exact implementation compiled into the packaged dylib, including parameter
// set reconfiguration and teardown, rather than a Kotlin model of that code.
val macMirrorNativeTestExecutable = layout.buildDirectory.file("native-tests/indagium_mirror_native_test")
val compileMacMirrorNativeTests by tasks.registering(Exec::class) {
    onlyIf { isMacHost }
    inputs.file("native/macos/indagium_mirror.mm")
    inputs.file("native/macos/indagium_mirror_native_test.mm")
    outputs.file(macMirrorNativeTestExecutable)
    doFirst {
        val output = macMirrorNativeTestExecutable.get().asFile
        output.parentFile.mkdirs()
        val javaHome = javaToolchains.launcherFor {
            languageVersion.set(JavaLanguageVersion.of(21))
        }.get().metadata.installationPath.asFile
        commandLine(
            "clang++", "-std=c++17", "-fobjc-arc", "-fblocks",
            "-I$javaHome/include", "-I$javaHome/include/darwin",
            "-L$javaHome/lib", "-L$javaHome/lib/server",
            "-Wl,-rpath,$javaHome/lib", "-Wl,-rpath,$javaHome/lib/server", "-ljawt", "-ljvm",
            "-framework", "Foundation", "-framework", "AppKit", "-framework", "QuartzCore",
            "-framework", "VideoToolbox", "-framework", "CoreMedia", "-framework", "CoreVideo",
            "-framework", "Metal",
            "native/macos/indagium_mirror_native_test.mm", "-o", output.absolutePath,
        )
    }
}

tasks.register<Exec>("testMacMirrorNative") {
    group = "verification"
    description = "Runs focused macOS native mirror parser, VideoToolbox reconfiguration, and teardown checks."
    onlyIf { isMacHost }
    dependsOn(compileMacMirrorNativeTests)
    commandLine(macMirrorNativeTestExecutable.get().asFile.absolutePath)
}

val generateBuildInfo by tasks.registering {
    inputs.property("appVersion", appVersion)
    inputs.property("appAuthor", appAuthor)
    inputs.property("licenseVersion", licenseVersion)
    inputs.property("licenseKind", distributionLicenseKind)
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
                const val LICENSE_KIND: String = "$distributionLicenseKind"
                const val LICENSE_TITLE: String = "$licenseTitle"
                const val LICENSE_DOCUMENT_VERSION: String = "$licenseDocumentVersion"
                const val LICENSE_AGREEMENT_SHA256: String = "${MessageDigest.getInstance("SHA-256").digest(renderedLicenseAgreement.toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) }}"
            }
            """.trimIndent() + "\n"
        )
    }
}

val validateMediaLicenseInventory by tasks.registering(Exec::class) {
    group = "verification"
    description = "Validates the pinned media licence inventory, source closure, and native build evidence offline."
    inputs.files(mediaSourceScript, mediaLicenseInventory, mediaSourceLock)
    inputs.dir(mediaLegalDirectory)
    commandLine(
        pythonExecutable, mediaSourceScript.absolutePath,
        "--app-version", appVersion,
        "--ffmpeg-version", ffmpegVersion,
        "--javacv-version", javacvVersion,
        "--javacpp-version", javacppVersion,
        "--zstd-jni-version", "1.5.7-4",
        "--inventory-only",
    )
}

val prepareMediaSources by tasks.registering(Exec::class) {
    group = "distribution"
    description = "Builds the SHA-256-verified native media source archive and manifest under build/."
    dependsOn(validateMediaLicenseInventory)
    inputs.files(mediaSourceScript, mediaLicenseInventory, mediaSourceLock, "NOTICE", "THIRD_PARTY_NOTICES.md")
    inputs.dir(mediaLegalDirectory)
    // Always re-check the ignored source cache. Otherwise Gradle can reuse an earlier archive
    // without noticing a changed or corrupt cached source input.
    outputs.upToDateWhen { false }
    outputs.files(
        layout.buildDirectory.file("Indagium-$appVersion-media-sources.tar.gz"),
        layout.buildDirectory.file("Indagium-$appVersion-media-sources.tar.gz.sha256"),
        layout.buildDirectory.file("Indagium-$appVersion-media-sources.tar.gz.manifest.json"),
    )
    commandLine(
        pythonExecutable, mediaSourceScript.absolutePath,
        "--app-version", appVersion,
        "--ffmpeg-version", ffmpegVersion,
        "--javacv-version", javacvVersion,
        "--javacpp-version", javacppVersion,
        "--zstd-jni-version", "1.5.7-4",
        "--fetch-missing",
    )
}

val validateApplicationSourceInventory by tasks.registering(Exec::class) {
    group = "verification"
    inputs.files("scripts/prepare_application_sources.py", "licenses/application/application-source-lock.json", applicationLicenseInventory, "gradle.lockfile")
    commandLine(pythonExecutable, "scripts/prepare_application_sources.py", "--inventory-only")
}

tasks.register<Exec>("prepareApplicationSources") {
    group = "distribution"
    description = "Builds the pinned Java-library and complete JVM source/build-material archive."
    dependsOn(validateApplicationSourceInventory)
    outputs.upToDateWhen { false }
    commandLine(pythonExecutable, "scripts/prepare_application_sources.py", "--app-version", appVersion, "--fetch-missing")
}

val testDistributionLicensing by tasks.registering(Exec::class) {
    group = "verification"
    commandLine(pythonExecutable, "-m", "unittest", "discover", "-s", "scripts", "-p", "test_distribution_licensing.py")
}

val validateMediaRuntimeArtifacts by tasks.registering(Exec::class) {
    group = "verification"
    description = "Checks the resolved FFmpeg and zstd-jni classifier JARs against the inspected native artifacts."
    dependsOn(validateMediaLicenseInventory)
    inputs.files(mediaSourceScript, mediaLicenseInventory, mediaSourceLock)
    inputs.dir(mediaLegalDirectory)
    doFirst {
        val runtimeJars = configurations.getByName("desktopRuntimeClasspath").files
        val ffmpegPrefix = "ffmpeg-$ffmpegVersion-$bytedecoPlatform"
        val zstdPrefix = "zstd-jni-1.5.7-4-$zstdJniPlatform"
        val ffmpegJar = runtimeJars.singleOrNull { it.name.startsWith(ffmpegPrefix) }
            ?: error("Could not resolve exactly one FFmpeg classifier JAR for $bytedecoPlatform")
        val zstdJar = runtimeJars.singleOrNull { it.name.startsWith(zstdPrefix) }
            ?: error("Could not resolve exactly one zstd-jni classifier JAR for $zstdJniPlatform")
        commandLine(
            pythonExecutable, mediaSourceScript.absolutePath,
            "--app-version", appVersion,
            "--ffmpeg-version", ffmpegVersion,
            "--javacv-version", javacvVersion,
            "--javacpp-version", javacppVersion,
            "--zstd-jni-version", "1.5.7-4",
            "--validate-runtime-artifacts",
            "--platform", mediaPlatform,
            "--ffmpeg-jar", ffmpegJar.absolutePath,
            "--zstd-jar", zstdJar.absolutePath,
        )
    }
}

val validatePackagedMediaResources by tasks.registering(Exec::class) {
    group = "verification"
    description = "Checks the staged app image contains the inspected native classifiers and all pinned legal resources."
    inputs.files(mediaSourceScript, mediaLicenseInventory, mediaSourceLock)
    inputs.dir(mediaLegalDirectory)
    doFirst {
        val arguments = mutableListOf(
            pythonExecutable, mediaSourceScript.absolutePath,
            "--app-version", appVersion,
            "--ffmpeg-version", ffmpegVersion,
            "--javacv-version", javacvVersion,
            "--javacpp-version", javacppVersion,
            "--zstd-jni-version", "1.5.7-4",
            "--validate-package",
            "--platform", mediaPlatform,
            "--package-root", distributionImageRoot.get().asFile.absolutePath,
            "--license-kind", distributionLicenseKind,
        )
        if (mediaPlatform == "macos-arm64") {
            val runtimeJars = configurations.getByName("desktopRuntimeClasspath").files
            val ffmpegPrefix = "ffmpeg-$ffmpegVersion-$bytedecoPlatform"
            val zstdPrefix = "zstd-jni-1.5.7-4-$zstdJniPlatform"
            val ffmpegJar = runtimeJars.singleOrNull { it.name.startsWith(ffmpegPrefix) }
                ?: error("Could not resolve exactly one original FFmpeg classifier JAR for $bytedecoPlatform")
            val zstdJar = runtimeJars.singleOrNull { it.name.startsWith(zstdPrefix) }
                ?: error("Could not resolve exactly one original zstd-jni classifier JAR for $zstdJniPlatform")
            arguments += listOf("--ffmpeg-jar", ffmpegJar.absolutePath, "--zstd-jar", zstdJar.absolutePath)
        }
        commandLine(arguments)
    }
}

val testMediaSourceGenerator by tasks.registering(Exec::class) {
    group = "verification"
    description = "Runs fail-closed tests for missing, corrupt, or unsupported media-source inputs."
    inputs.files(mediaSourceScript, "scripts/test_prepare_media_sources.py", mediaLicenseInventory, mediaSourceLock)
    commandLine(pythonExecutable, file("scripts/test_prepare_media_sources.py").absolutePath)
}

// Every module that ships on the runtime classpath must be attributed in the application inventory
// (or, for the media closure, in media-components.json). A new or renamed dependency fails here
// until its licence has been reviewed, instead of silently shipping without its notices.
val validateApplicationLicenseInventory by tasks.registering {
    group = "verification"
    description = "Checks every runtime-classpath module is covered by licenses/application/application-components.json."
    val runtimeClasspath = configurations.named("desktopRuntimeClasspath")
    inputs.dir(applicationLegalDirectory)
    doLast {
        val inventory = groovy.json.JsonSlurper().parse(applicationLicenseInventory) as Map<*, *>
        val components = (inventory["components"] as List<*>).map { it as Map<*, *> }
        val graphicsProvenance = groovy.json.JsonSlurper().parse(file("licenses/application/graphics-notice-provenance.json")) as Map<*, *>
        (graphicsProvenance["packagedTextHashes"] as Map<*, *>).forEach { (path, expected) ->
            val digest = MessageDigest.getInstance("SHA-256").digest(file("licenses/$path").readBytes()).joinToString("") { "%02x".format(it) }
            check(digest == expected) { "Pinned native graphics/support notice changed: $path" }
        }
        val missingTexts = components.flatMap { component ->
            // Paths are resource paths under licenses/: third-party/ and application/ are copied
            // from the repository's licenses/ tree, anything else is a checked-in resource.
            (component["licenseFiles"] as List<*>).map { it as String }.filterNot { relative ->
                val source = if (relative.startsWith("third-party/") || relative.startsWith("application/")) {
                    file("licenses/$relative")
                } else {
                    file("src/desktopMain/resources/licenses/$relative")
                }
                source.isFile
            }.map { "${component["id"]}: $it" }
        }
        check(missingTexts.isEmpty()) { "Licence texts referenced by the application inventory are missing: $missingTexts" }
        val patterns = components.flatMap { component ->
            (component["modules"] as List<*>).map { it as String }.map { pattern ->
                Regex(pattern.split("*").joinToString(".*") { Regex.escape(it) })
            }
        }
        val modules = runtimeClasspath.get().incoming.resolutionResult.allComponents
            .filter { it.id is org.gradle.api.artifacts.component.ModuleComponentIdentifier }
            .mapNotNull { it.moduleVersion }
            .map { "${it.group}:${it.name}" }
            .toSortedSet()
        val uncovered = modules.filter { module -> patterns.none { it.matches(module) } }
        check(uncovered.isEmpty()) {
            "Runtime modules without a licence entry in licenses/application/application-components.json " +
                "(review each licence, add its notices, then list it): $uncovered"
        }
        val sourceLock = groovy.json.JsonSlurper().parse(file("licenses/application/application-source-lock.json")) as Map<*, *>
        val reviewed = groovy.json.JsonSlurper().parse(file("licenses/application/reviewed-runtime-modules.json")) as Map<*, *>
        val reviewedModules = (reviewed["modules"] as List<*>).map { it.toString() }.toSet()
        val sourceEvidence = (sourceLock["sourceArchives"] as List<*>).map { it as Map<*, *> }
            .filter { it["kind"] == "binary-evidence" }.associateBy { it["coordinate"].toString() }
        val artifacts = runtimeClasspath.get().resolvedConfiguration.resolvedArtifacts.map { artifact ->
            check(artifact.moduleVersion.id.toString() in reviewedModules) {
                "Runtime artifact ${artifact.moduleVersion.id} requires a licence review in reviewed-runtime-modules.json"
            }
            val module = "${artifact.moduleVersion.id.group}:${artifact.name}"
            val matched = components.filter { component ->
                (component["modules"] as List<*>).any { pattern ->
                    Regex((pattern as String).split("*").joinToString(".*") { Regex.escape(it) }).matches(module)
                }
            }
            val notices = ZipFile(artifact.file).use { jar ->
                jar.entries().asSequence().map { it.name }.filter {
                    it.substringAfterLast('/').contains(Regex("(?i)license|licence|notice|copying|copyright|readme"))
                }.sorted().toList()
            }
            val checksum = MessageDigest.getInstance("SHA-256").digest(artifact.file.readBytes()).joinToString("") { "%02x".format(it) }
            sourceEvidence[artifact.moduleVersion.id.toString()]?.let { evidence ->
                check(checksum == evidence["sha256"]) { "Resolved ${artifact.file.name} differs from its application source evidence" }
            }
            mapOf(
                "coordinate" to artifact.moduleVersion.id.toString(), "file" to artifact.file.name,
                "sha256" to checksum,
                "componentIds" to matched.map { it["id"] }, "licenseExpressions" to matched.map { it["licenseExpression"] },
                "embeddedNoticePaths" to notices,
            )
        }.sortedBy { it["file"].toString() }
        val report = layout.buildDirectory.file("reports/licenses/runtime-artifacts-$mediaPlatform.json").get().asFile
        report.parentFile.mkdirs()
        report.writeText(groovy.json.JsonOutput.prettyPrint(groovy.json.JsonOutput.toJson(artifacts)) + "\n")
    }
}

// Keep the in-app agreement derived from the repository's canonical legal documents. This avoids
// displaying one set of terms in packaged builds while publishing different terms at the project root.
val generateLicenseResources by tasks.registering {
    val licenseDocuments = listOf(licenseDocument, "NOTICE")
    inputs.files(licenseDocuments.map(::file))
    inputs.file("THIRD_PARTY_NOTICES.md")
    inputs.file(whisperSourceLicense)
    inputs.dir(mediaLegalDirectory)
    inputs.dir(applicationLegalDirectory)
    inputs.property("licenseKind", distributionLicenseKind)
    inputs.property("licenseVersion", licenseVersion)
    outputs.dir(generatedLicenseResourcesDir)
    dependsOn(configureWhisperNative)
    dependsOn(validateMediaLicenseInventory)
    dependsOn(validateApplicationLicenseInventory)
    dependsOn(validateApplicationSourceInventory)
    doLast {
        val licenseDirectory = generatedLicenseResourcesDir.get().dir("licenses").asFile
        // Remove stale documents when switching between source and official distribution modes.
        licenseDirectory.deleteRecursively()
        licenseDirectory.mkdirs()
        val outputFile = File(licenseDirectory, "indagium-license-agreement.md")
        outputFile.writeText(renderedLicenseAgreement, Charsets.UTF_8)
        File(licenseDirectory, "indagium-license-agreement.txt").writeText(renderedLicenseAgreement, Charsets.UTF_8)
        file(licenseDocument).copyTo(File(licenseDirectory, licenseDocument), overwrite = true)
        val metadata = mapOf(
            "schemaVersion" to 1, "kind" to distributionLicenseKind, "document" to licenseDocument,
            "documentVersion" to licenseDocumentVersion, "documentSha256" to licenseDocumentDigest,
            "identity" to licenseVersion,
            "agreementSha256" to MessageDigest.getInstance("SHA-256").digest(outputFile.readBytes()).joinToString("") { "%02x".format(it) },
        )
        File(licenseDirectory, "distribution-license.json").writeText(groovy.json.JsonOutput.prettyPrint(groovy.json.JsonOutput.toJson(metadata)) + "\n")
        val releaseUrl = "https://github.com/indagium/indagium/releases"
        val sourceAcquisition = mapOf(
            "schemaVersion" to 1, "applicationVersion" to appVersion,
            "releaseUrl" to "$releaseUrl/tag/v$appVersion",
            "archives" to listOf("media", "application").map { kind ->
                val name = "Indagium-$appVersion-$kind-sources.tar.gz"
                val url = "$releaseUrl/download/v$appVersion/$name"
                mapOf("name" to name, "url" to url, "checksumUrl" to "$url.sha256", "manifestUrl" to "$url.manifest.json")
            },
        )
        File(licenseDirectory, "source-acquisition.json").writeText(groovy.json.JsonOutput.prettyPrint(groovy.json.JsonOutput.toJson(sourceAcquisition)) + "\n")
        whisperSourceLicense.get().asFile.copyTo(File(licenseDirectory, "whisper.cpp-LICENSE.txt"), overwrite = true)
        file("THIRD_PARTY_NOTICES.md").copyTo(File(licenseDirectory, "THIRD_PARTY_NOTICES.md"), overwrite = true)
        file("NOTICE").copyTo(File(licenseDirectory, "NOTICE"), overwrite = true)
        val thirdPartyDestination = File(licenseDirectory, "third-party")
        thirdPartyDestination.deleteRecursively()
        mediaLegalDirectory.copyRecursively(thirdPartyDestination, overwrite = true)
        val applicationDestination = File(licenseDirectory, "application")
        applicationDestination.deleteRecursively()
        applicationLegalDirectory.copyRecursively(applicationDestination, overwrite = true)
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
                // Backs commons-compress' ZIP method 93 (zstd) reader/writer (compact capture archives).
                // 1.5.7-4 is the version commons-compress 1.28.0 itself declares (as optional).
                implementation("com.github.luben:zstd-jni:1.5.7-4:$zstdJniPlatform")
                implementation("org.tukaani:xz:1.10")
                // capture/WirelessAdb.kt: the Wi-Fi pairing dialog draws a QR code (ui/WirelessPairingDialogs.kt).
                // Only the encoder in `core` is used; there is no camera or image-IO dependency.
                implementation("com.google.zxing:core:3.5.3")
                // Official R8 Retrace API. Keep this pinned: mapping metadata and the in-process
                // retrace contract are versioned together, so a moving dependency can silently
                // change deobfuscation behavior between desktop releases.
                implementation("com.android.tools:r8:9.4.17")
                // Native MCP server (ControlServer.kt): the app speaks MCP over Streamable HTTP
                // so clients connect by URL with no Node bridge. The SDK's mcpStreamableHttp {}
                // helper runs on Ktor (CIO engine); CORS lets browser-based MCP inspectors reach it.
                implementation("io.modelcontextprotocol:kotlin-sdk-server:0.14.0")
                // testing/tracker/SdkTrackerMcpClient.kt: the AI test suites' issue tracker is a user-configured remote
                // MCP server reached over Streamable HTTP. Same SDK line as the server so one set of types is used.
                implementation("io.modelcontextprotocol:kotlin-sdk-client:0.14.0")
                implementation("io.ktor:ktor-server-cio:$ktorVersion")
                implementation("io.ktor:ktor-server-cors:$ktorVersion")
                // In-app AI providers use the same Ktor line as the MCP server. Keeping the
                // transport explicit avoids depending on the server engine transitively.
                implementation("io.ktor:ktor-client-cio:$ktorVersion")
                // Whisper and GGML are statically linked into an app-owned native bridge at build
                // time; the multilingual model is downloaded only after an explicit user action.
                // Embedded offline note proofreading: English resources only, no server or remote rules.
                implementation("org.languagetool:language-en:6.8") {
                    // English spelling uses Morfologik; Hunspell is only for languages not bundled
                    // here. Its JAR carries native libhunspell (MPL/GPL/LGPL tri-licence), so leave
                    // it out rather than ship unused copyleft natives without their source.
                    exclude(group = "com.gitlab.dumonts", module = "hunspell")
                }
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
            implementation(compose.desktop.uiTestJUnit4)
            implementation(compose.desktop.currentOs)
            implementation("io.ktor:ktor-client-mock:$ktorVersion")
            // Parser/render-backed validation for exported PlantUML sequence sources. Keeping this
            // on desktopTest avoids bundling the sizeable PlantUML runtime into the application.
            implementation("net.sourceforge.plantuml:plantuml:1.2025.3")
        }
    }
}

compose.desktop {
    application {
        mainClass = "MainKt"

        // Multi-GB logcat files materialize millions of parsed entries; the JVM default cap of
        // 25% of physical RAM leaves a 1.5GB file thrashing the GC on 8-16GB machines. Percentage
        // (not -Xmx) so small machines aren't over-committed; memory is only committed as used.
        // G1 only uncommits heap after a concurrent cycle or full GC, and an idle app never runs
        // one, so a closed multi-GB capture would keep its footprint forever: G1PeriodicGCInterval
        // makes an idle JVM return memory by itself (a concurrent cycle, the cheap backstop).
        // ExplicitGCInvokesConcurrent is deliberately NOT set: utils/HeapTrim issues a real full GC
        // (a short stop-the-world pause, ~0.1-0.3 s with a few hundred MB live) only at moments the
        // user just triggered (closing a big tab, Stop, export), because a concurrent cycle cannot
        // compact old regions that still hold garbage in an idle app, leaving gigabytes committed.
        // Max/MinHeapFreeRatio: after that full GC, G1 by default keeps up to 70% of the heap free
        // (measured: 272 MB committed for 60 MB live after a closed capture). 30/10 lets it shrink
        // close to the live set; resizing only happens at GC time, so streaming is unaffected.
        jvmArgs(
            "-XX:MaxRAMPercentage=50",
            "-XX:G1PeriodicGCInterval=30000",
            "-XX:MaxHeapFreeRatio=30",
            "-XX:MinHeapFreeRatio=10",
        )

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
            licenseFile.set(generatedLicenseResourcesDir.map { it.file("licenses/indagium-license-agreement.txt") })
            fileAssociation("text/plain", "log", "Log file")
            fileAssociation("text/plain", "txt", "Text log file")
            fileAssociation("text/plain", "logcat", "Android logcat file")
            fileAssociation("text/plain", "trace", "Trace log file")
            fileAssociation("text/plain", "out", "Output log file")
            fileAssociation("application/vnd.dlt", "dlt", "Diagnostic Log and Trace file")
            // Indagium Compact capture archive (a v5 capture ZIP, content-detected on open): lets a
            // double-click on a `.ind` file launch Indagium. Declared once on every OS (a single
            // WiX Extension for the MSI). Documents default to the app icon; do not pass the app's
            // own icons/indagium.icns as an association icon — Compose copies it into the bundle by
            // file name and createDistributable then fails ("destination file already exists").
            fileAssociation("application/x-indagium-capture", "ind", "Indagium capture")
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
            // HeapPressureMonitor reads GC notifications via com.sun.management.* (jdk.management);
            // declared explicitly so the trimmed runtime never lacks it, same jdeps caveat as above.
            modules("jdk.management")
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
                        <string>Indagium uses the microphone to record audio with a device capture or to turn your AI question into local text on this device.</string>
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
    dependsOn(compileWhisperNative)
    if (isMacHost) {
        dependsOn(compileAppleSpeechNative)
        dependsOn(compileMacMirrorNative)
    }
    if (isLinuxHost) dependsOn(compileLinuxMirrorNative)
    if (isWindowsHost) dependsOn(compileWindowsMirrorNative)
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
    dependsOn(validateMediaRuntimeArtifacts)
    doFirst {
        if (distributionLicenseKind == "eula") {
            val lock = groovy.json.JsonSlurper().parse(file("licenses/application/application-source-lock.json")) as Map<*, *>
            val runtime = lock["runtime"] as Map<*, *>
            check(System.getProperty("java.vendor") == runtime["vendor"] && System.getProperty("java.runtime.version") == runtime["runtimeVersion"]) {
                "Official EULA packages require Temurin ${runtime["runtimeVersion"]}; use scripts/prepare_application_sources.py --install-jdk. " +
                    "Gradle currently uses ${System.getProperty("java.vendor")} ${System.getProperty("java.runtime.version")}."
            }
        }
        check(JavaVersion.current().isCompatibleWith(JavaVersion.VERSION_21)) {
            "Packaging must be run with Gradle itself on JDK 21+ (currently ${JavaVersion.current()}) — " +
                "jpackage/jlink bundle whatever JVM launches Gradle, regardless of kotlin.jvmToolchain. " +
                "Set JAVA_HOME to a JDK 21+ install before running packageDmg/packageDeb/packageMsi/" +
                "packageDistributionForCurrentOS, e.g. on macOS: " +
                "JAVA_HOME=\$(/usr/libexec/java_home -v 21) ./gradlew packageDistributionForCurrentOS"
        }
    }
}

tasks.matching { it.name == "createDistributable" }.configureEach {
    dependsOn(validateMediaRuntimeArtifacts)
    finalizedBy(validatePackagedMediaResources)
    doLast {
        val destination = mediaPackageRoot.get().asFile.resolve("legal")
        destination.deleteRecursively()
        destination.mkdirs()
        val source = generatedLicenseResourcesDir.get().dir("licenses").asFile
        source.copyRecursively(destination, overwrite = true)
        source.resolve("indagium-license-agreement.md").copyTo(destination.resolve("indagium-license-agreement.txt"), overwrite = true)
        if (distributionLicenseKind == "eula") {
            val process = ProcessBuilder(
                pythonExecutable, file("scripts/prepare_application_sources.py").absolutePath,
                "--record-runtime", distributionImageRoot.get().asFile.absolutePath,
                "--jdk-home", System.getProperty("java.home"), "--platform", mediaPlatform,
                "--manifest", destination.resolve("runtime-license-manifest.json").absolutePath,
            ).inheritIO().start()
            check(process.waitFor() == 0) { "Could not record the packaged JVM source mapping and notices" }
        }
    }
}

tasks.matching { it.name == "check" }.configureEach {
    dependsOn(testMediaSourceGenerator)
    dependsOn(testDistributionLicensing)
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
    // Same heap-return flags as compose.desktop.application.jvmArgs above (see the comment there).
    jvmArgs("-XX:MaxRAMPercentage=50", "-XX:G1PeriodicGCInterval=30000", "-XX:MaxHeapFreeRatio=30", "-XX:MinHeapFreeRatio=10")
    if (org.gradle.internal.os.OperatingSystem.current().isLinux) {
        jvmArgs("--add-opens=java.desktop/sun.awt.X11=ALL-UNNAMED")
    }
    System.getProperty("indagium.debugControl")?.let { systemProperty("indagium.debugControl", it) }
    System.getProperty("indagium.run.home")?.let { systemProperty("user.home", it) }
    // AI test-suite edition limits: -Dindagium.edition=free|premium|friends_family|unlimited picks the
    // edition, and -Dindagium.dev=true lets a packaged-style run switch it at runtime (see edition/Edition.kt).
    System.getProperty("indagium.edition")?.let { systemProperty("indagium.edition", it) }
    System.getProperty("indagium.dev")?.let { systemProperty("indagium.dev", it) }
    // Test aid for the memory watchdog: ./gradlew desktopRun -Dindagium.run.xmx=768m forces a small
    // heap so the WARNING/CRITICAL limits are reached quickly. -Xmx wins over MaxRAMPercentage above.
    System.getProperty("indagium.run.xmx")?.let { jvmArgs("-Xmx$it") }
    // macOS mirror bisect switches: the underlay ordering (on by default — see
    // EmbeddedMirrorMacSurface.underlayRequested), its stderr diagnostics, Compose interop blending
    // and the skiko render API all change how the native mirror layer composes with Compose.
    listOf(
        "indagium.mirror.underlay",
        "indagium.mirror.stderr",
        "compose.interop.blending",
        "skiko.renderApi",
    ).forEach { key ->
        System.getProperty(key)?.let { systemProperty(key, it) }
    }
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

// Opt-in synthetic append-fast-path scenario (LargeFilePerfHarness.appendFastPathBenchmark): needs
// no fixture file. -Dindagium.perf.append=1 enables it; .rows/.batches/.batchSize tune it.
val perfAppend: String? = System.getProperty("indagium.perf.append")
tasks.withType<Test>().configureEach {
    if (perfAppend != null) {
        maxHeapSize = "6g"
        systemProperty("indagium.perf.append", perfAppend)
        listOf("indagium.perf.append.rows", "indagium.perf.append.batches", "indagium.perf.append.batchSize").forEach { key ->
            System.getProperty(key)?.let { systemProperty(key, it) }
        }
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

tasks.matching {
    it.name == "runKtlintCheckOverDesktopMainSourceSet" || it.name == "runKtlintFormatOverDesktopMainSourceSet"
}.configureEach {
    dependsOn(generateBuildInfo)
}

tasks.matching { it.name == "desktopProcessResources" || it.name == "desktopTest" }.configureEach {
    dependsOn(validateScrcpyServerAsset)
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
