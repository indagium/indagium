package com.indagium

import com.indagium.ui.DesktopStorage
import java.io.File
import java.nio.file.Files
import kotlin.io.path.createTempDirectory
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull

/**
 * Launch-policy tests for the isolated app-data root used by a debug-control/MCP test instance.
 * These intentionally exercise only the resolver: installing global process state would make
 * unrelated desktop tests observe a different storage location when Gradle runs tests in parallel.
 */
class DebugAppDataIsolationTest {
    @Test
    fun explicitTempDirectoryIsIgnoredUnlessEphemeralDebugControlIsEnabled() {
        val requested = createTempDirectory("indagium-debug-data-disabled").toFile().canonicalFile

        assertNull(DesktopStorage.resolveDebugAppDataDirOverride(requested.absolutePath, debugControlEnabled = false))
    }

    @Test
    fun enabledDebugControlAcceptsOnlyAnEmptyCanonicalDirectoryInsideJvmTemp() {
        val requested = createTempDirectory("indagium-debug-data-empty").toFile().canonicalFile

        val resolved = DesktopStorage.resolveDebugAppDataDirOverride(requested.absolutePath, debugControlEnabled = true)

        assertEquals(requested.canonicalFile, resolved)
    }

    @Test
    fun enabledDebugControlRejectsRelativeDirectoryEvenBeforeTempRootValidation() {
        val error = assertFailsWith<IllegalArgumentException> {
            DesktopStorage.resolveDebugAppDataDirOverride("relative-test-data", debugControlEnabled = true)
        }

        assertEquals("INDAGIUM_DEBUG_APP_DATA_DIR must be an absolute path", error.message)
    }

    @Test
    fun enabledDebugControlRejectsNonEmptyDirectoryInsideApprovedTempRoot() {
        val requested = createTempDirectory("indagium-debug-data-nonempty").toFile().canonicalFile
        File(requested, "autosave.cache").writeText("indagium-cache-v1")

        val error = assertFailsWith<IllegalArgumentException> {
            DesktopStorage.resolveDebugAppDataDirOverride(requested.absolutePath, debugControlEnabled = true)
        }

        assertEquals(
            "INDAGIUM_DEBUG_APP_DATA_DIR must be empty to prevent restoring autosave or Recent state",
            error.message,
        )
    }

    @Test
    fun enabledDebugControlRejectsAFileInsideApprovedTempRoot() {
        val requested = createTempDirectory("indagium-debug-data-file").toFile().canonicalFile
        val file = File(requested, "not-a-directory").apply { writeText("x") }

        val error = assertFailsWith<IllegalArgumentException> {
            DesktopStorage.resolveDebugAppDataDirOverride(file.absolutePath, debugControlEnabled = true)
        }

        assertEquals("INDAGIUM_DEBUG_APP_DATA_DIR must name a directory, not a file", error.message)
    }

    @Test
    fun enabledDebugControlRejectsAbsoluteDirectoryOutsideApprovedTempRoots() {
        val unsafe = File(System.getProperty("user.home"), "indagium-debug-data-unsafe-${System.nanoTime()}")

        val error = assertFailsWith<IllegalArgumentException> {
            DesktopStorage.resolveDebugAppDataDirOverride(unsafe.absolutePath, debugControlEnabled = true)
        }

        assertEquals(
            "INDAGIUM_DEBUG_APP_DATA_DIR must be inside the JVM temporary directory or a macOS /private temporary root",
            error.message,
        )
    }

    @Test
    fun enabledDebugControlRejectsRequestedPathThatIsItselfASymlink() {
        val root = createTempDirectory("indagium-debug-data-link").toFile().canonicalFile
        val target = File(root, "target").apply { mkdirs() }
        val link = File(root, "link")
        Files.createSymbolicLink(link.toPath(), target.toPath())

        val error = assertFailsWith<IllegalArgumentException> {
            DesktopStorage.resolveDebugAppDataDirOverride(link.absolutePath, debugControlEnabled = true)
        }

        assertEquals("INDAGIUM_DEBUG_APP_DATA_DIR must not contain symlinked path components", error.message)
    }

    @Test
    fun enabledDebugControlRejectsNotYetCreatedTargetUnderASymlinkedAncestor() {
        val root = createTempDirectory("indagium-debug-data-link-ancestor").toFile().canonicalFile
        val targetParent = File(root, "target-parent").apply { mkdirs() }
        val link = File(root, "link-parent")
        Files.createSymbolicLink(link.toPath(), targetParent.toPath())
        val requested = File(link, "fresh-isolated-root")

        val error = assertFailsWith<IllegalArgumentException> {
            DesktopStorage.resolveDebugAppDataDirOverride(requested.absolutePath, debugControlEnabled = true)
        }

        assertEquals("INDAGIUM_DEBUG_APP_DATA_DIR must not contain symlinked path components", error.message)
    }
}
