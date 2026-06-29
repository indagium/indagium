@file:OptIn(org.jetbrains.kotlin.gradle.ExperimentalKotlinGradlePluginApi::class)

import org.jetbrains.compose.desktop.application.dsl.TargetFormat

plugins {
    kotlin("multiplatform") version "2.1.0"
    id("org.jetbrains.compose") version "1.7.3"
    id("org.jetbrains.kotlin.plugin.compose") version "2.1.0"
}

val appVersion: String = providers.gradleProperty("app.version").get()
val generatedBuildInfoDir = layout.buildDirectory.dir("generated/openlogBuildInfo/desktopMain/kotlin")

val generateBuildInfo by tasks.registering {
    inputs.property("appVersion", appVersion)
    outputs.dir(generatedBuildInfoDir)
    doLast {
        val outputFile = generatedBuildInfoDir.get().file("com/openlog/generated/BuildInfo.kt").asFile
        outputFile.parentFile.mkdirs()
        outputFile.writeText(
            """
            package com.openlog.generated

            object BuildInfo {
                const val APP_VERSION: String = "$appVersion"
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

        nativeDistributions {
            targetFormats(TargetFormat.Dmg, TargetFormat.Msi, TargetFormat.Deb)
            packageName = "openLog"
            packageVersion = appVersion
            description = "Android logcat analysis tool"
            vendor = "Roman Arnaut"
            copyright = "Copyright (C) 2026 Roman Arnaut"
            macOS {
                bundleID = "com.romanarnaut.openlog"
                iconFile.set(project.file("icons/openlog.icns"))
            }
            windows {
                iconFile.set(project.file("icons/openlog.ico"))
            }
            linux {
                iconFile.set(project.file("icons/openlog.png"))
            }
        }
    }
}

tasks.named("compileKotlinDesktop") {
    dependsOn(generateBuildInfo)
}
