@file:OptIn(org.jetbrains.kotlin.gradle.ExperimentalKotlinGradlePluginApi::class)

import org.jetbrains.compose.desktop.application.dsl.TargetFormat

plugins {
    kotlin("multiplatform") version "2.1.0"
    id("org.jetbrains.compose") version "1.7.3"
    id("org.jetbrains.kotlin.plugin.compose") version "2.1.0"
    id("io.gitlab.arturbosch.detekt") version "1.23.7"
    id("org.jlleitschuh.gradle.ktlint") version "12.1.2"
    id("org.jetbrains.kotlinx.kover") version "0.7.6"
}

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
            }
        }
        val desktopTest by getting
        desktopTest.dependencies {
            implementation(kotlin("test"))
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

        nativeDistributions {
            targetFormats(TargetFormat.Dmg, TargetFormat.Msi, TargetFormat.Deb)
            packageName = "openLog"
            packageVersion = appVersion
            description = "Android logcat analysis tool"
            vendor = appAuthor
            copyright = "Copyright (C) 2026 $appAuthor"
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

// Same heap headroom for dev runs (./gradlew desktopRun) as for the packaged app. Also forwards
// two optional -D properties from the Gradle command line into the app JVM (Gradle doesn't do
// this by itself): openlog.debugControl to enable the MCP control server (see Main.kt), and
// openlog.run.home to point user.home at a throwaway dir so automated/smoke runs don't touch
// the real ~/.openlog2 session state.
tasks.withType<JavaExec>().matching { it.name == "desktopRun" }.configureEach {
    jvmArgs("-XX:MaxRAMPercentage=50")
    System.getProperty("openlog.debugControl")?.let { systemProperty("openlog.debugControl", it) }
    System.getProperty("openlog.run.home")?.let { systemProperty("user.home", it) }
}

// Manual large-file perf harness (LargeFilePerfHarness.kt) — activated by passing
// -Dopenlog.perf.file=<fixture path> to Gradle; needs a multi-GB heap for the ~1.5GB fixture.
// Normal test runs are unaffected (the property is blank and the harness returns immediately).
val perfFixture: String? = System.getProperty("openlog.perf.file")
tasks.withType<Test>().configureEach {
    if (perfFixture != null) {
        maxHeapSize = "14g"
        systemProperty("openlog.perf.file", perfFixture)
        System.getProperty("openlog.perf.dense")?.let { systemProperty("openlog.perf.dense", it) }
    }
}

// ── Detekt ──────────────────────────────────────────────────────────
detekt {
    buildUponDefaultConfig = true
    config.setFrom(files("config/detekt.yml"))
    source.setFrom("src/desktopMain/kotlin", "src/desktopTest/kotlin")
    ignoreFailures = true
}

tasks.withType<io.gitlab.arturbosch.detekt.Detekt>().configureEach {
    reports {
        html.required.set(true)
        xml.required.set(true)
        txt.required.set(false)
        sarif.required.set(false)
    }
}

tasks.withType<io.gitlab.arturbosch.detekt.DetektCreateBaselineTask>().configureEach {
    ignoreFailures = true
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
koverReport {
    defaults {
        html { onCheck = false }
        xml { onCheck = false }
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
    }
}
