package com.openlog

import com.openlog.generated.BuildInfo
import java.io.File
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertTrue

class AppBuildInfoTest {
    @Test
    fun exposesPackagedAppVersionToRuntime() {
        // Asserts the generated format, not a hardcoded value — a literal version string here
        // would need updating on every release and go stale (see CLAUDE.md's Versioning rule).
        assertTrue(
            Regex("""\d+\.\d+\.\d+""").matches(BuildInfo.APP_VERSION),
            "Expected a semantic version, got \"${BuildInfo.APP_VERSION}\"",
        )
    }

    @Test
    fun exposesAppAuthorToRuntime() {
        assertTrue(BuildInfo.APP_AUTHOR.isNotBlank())
    }

    @Test
    fun packagedBuildDeclaresTextLogFileAssociations() {
        val gradleFile = File("build.gradle.kts").readText()

        listOf("log", "txt", "logcat", "trace", "out").forEach { ext ->
            assertContains(gradleFile, """fileAssociation("text/plain", "$ext"""")
        }
    }
}
