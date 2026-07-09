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

    @Test
    fun packagedBuildDeclaresTextXLogFileAssociation() {
        // .log is text/x-log in shared-mime-info, not text/plain — without this association
        // "Open With" never lists openLog as a candidate for the app's primary file type.
        val gradleFile = File("build.gradle.kts").readText()
        assertContains(gradleFile, """fileAssociation("text/x-log", "log"""")
    }

    @Test
    fun linuxDebPatchStepAppendsExecFieldCodeAndReplacesMimeInfo() {
        // Guards the CI post-build patch (.github/workflows/build.yml) that fixes the two
        // Linux-only defects source-level fixes can't reach: jpackage's Exec= has no %f/%F field
        // code (so a MimeType-declaring .desktop entry can never receive a file, per the Desktop
        // Entry Spec), and jpackage's generated MimeInfo XML re-declares text/plain five times.
        val workflowFile = File(".github/workflows/build.yml").readText()

        assertContains(workflowFile, """sed -i 's|^\(Exec=.*\)$|\1 %F|' "${'$'}desktop_file"""")
        assertContains(workflowFile, """cp packaging/linux/openlog-mimeinfo.xml "${'$'}mimeinfo_file"""")
    }
}
