package com.indagium

import com.indagium.ui.configureDirectoryChooser
import com.indagium.ui.initialDirectoryForPicker
import com.indagium.ui.isMacOs
import com.indagium.ui.resolveChosenDirectory
import org.junit.Assume.assumeNoException
import java.awt.HeadlessException
import java.io.File
import javax.swing.JFileChooser
import kotlin.io.path.createTempDirectory
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertNull

class DirectoryPickerTest {
    @Test
    fun initialDirectoryKeepsAnExistingDirectory() {
        val directory = createTempDirectory("openlog-picker-directory").toFile()

        assertEquals(directory, initialDirectoryForPicker(directory))
    }

    @Test
    fun initialDirectoryUsesParentForLegacyFileSelection() {
        val directory = createTempDirectory("openlog-picker-legacy").toFile()
        val legacyFile = File(directory, "selected-file").apply { writeText("not a directory") }

        assertEquals(directory, initialDirectoryForPicker(legacyFile))
    }

    @Test
    fun initialDirectoryRejectsMissingPaths() {
        val missing = File(createTempDirectory("openlog-picker-missing").toFile(), "gone")

        assertNull(initialDirectoryForPicker(missing))
        assertNull(initialDirectoryForPicker(null))
    }

    @Test
    fun identifiesMacOsForTheNativeDirectoryPicker() {
        assertEquals(true, isMacOs("Mac OS X"))
        assertEquals(false, isMacOs("Windows 11"))
        assertEquals(false, isMacOs("Linux"))
    }

    // resolveChosenDirectory is the pure decision behind approving a Swing JFileChooser: a safety
    // net for a null selection (nothing chosen, fall back to the browsed directory) and for a
    // typed-in file path (resolve to its parent) rather than trusting selectedFile blindly.
    @Test
    fun resolveChosenDirectoryPrefersASelectedDirectory() {
        val selected = createTempDirectory("openlog-picker-selected").toFile()
        val current = createTempDirectory("openlog-picker-current").toFile()

        assertEquals(selected, resolveChosenDirectory(selected, current))
    }

    @Test
    fun resolveChosenDirectoryFallsBackToCurrentDirectoryWhenNothingSelected() {
        val current = createTempDirectory("openlog-picker-current").toFile()

        assertEquals(current, resolveChosenDirectory(null, current))
    }

    @Test
    fun resolveChosenDirectoryResolvesASelectedFileToItsParent() {
        val directory = createTempDirectory("openlog-picker-parent").toFile()
        val selectedFile = File(directory, "typed-name").apply { writeText("not a directory") }

        assertEquals(directory, resolveChosenDirectory(selectedFile, null))
    }

    @Test
    fun resolveChosenDirectoryReturnsNullWhenNothingUsableIsAvailable() {
        assertNull(resolveChosenDirectory(null, null))

        val missing = File(createTempDirectory("openlog-picker-missing").toFile(), "gone")
        assertNull(resolveChosenDirectory(missing, missing))
    }

    // configureDirectoryChooser pre-selects startDir (rather than opening inside it) so the
    // file-name field is non-empty before the user touches anything — see the function's own doc
    // for why GTK on Linux otherwise leaves that field, and the first Open press, dead.
    @Test
    fun configureDirectoryChooserPreselectsTheStartDirectoryFromItsParent() {
        val parent = createTempDirectory("openlog-picker-configure-parent").toFile()
        val startDir = File(parent, "child").apply { mkdir() }
        val chooser = newDirectoryChooserOrSkipTest()

        configureDirectoryChooser(chooser, "Pick a folder", startDir)

        assertEquals(startDir, chooser.selectedFile)
        assertEquals(parent, chooser.currentDirectory)
    }

    @Test
    fun configureDirectoryChooserHandlesANullStartDirectory() {
        val chooser = newDirectoryChooserOrSkipTest()

        configureDirectoryChooser(chooser, "Pick a folder", null)

        // Nothing to pre-select, but the directory-only setup must still land.
        assertEquals(JFileChooser.DIRECTORIES_ONLY, chooser.fileSelectionMode)
    }

    @Test
    fun configureDirectoryChooserHandlesAFilesystemRootStartDirectory() {
        val root = File("/")
        val chooser = newDirectoryChooserOrSkipTest()

        configureDirectoryChooser(chooser, "Pick a folder", root)

        // root.parentFile is null, so the `?: startDir` fallback is what's under test here; a
        // root selectedFile isn't its own parent, so JFileChooser's own bookkeeping is free to
        // re-home currentDirectory afterwards (observed: it lands on the user's home directory) —
        // that's a bounded, one-time Swing quirk, not the unbounded climb this test suite guards
        // against below. selectedFile is what the approve action actually reads, and it holds.
        assertEquals(root, chooser.selectedFile)
    }

    // Regression test for the runaway-navigation bug: a JFileChooser.setSelectedFile() call moves
    // currentDirectory to the file's parent whenever the file isn't already considered a child of
    // the current directory. configureDirectoryChooser sets currentDirectory to the parent BEFORE
    // selectedFile precisely so that check passes and nothing moves again — getting that order
    // backwards (or re-deriving currentDirectory from a listener after the fact, as an earlier
    // version of this fix did) sends currentDirectory climbing past the parent on every call.
    @Test
    fun configureDirectoryChooserDoesNotWalkCurrentDirectoryUpwards() {
        val parent = createTempDirectory("openlog-picker-configure-noclimb").toFile()
        val startDir = File(parent, "child").apply { mkdir() }
        val chooser = newDirectoryChooserOrSkipTest()

        configureDirectoryChooser(chooser, "Pick a folder", startDir)

        assertEquals(parent, chooser.currentDirectory)
        assertNotEquals(parent.parentFile, chooser.currentDirectory)
    }
}

// JFileChooser can throw HeadlessException on some CI/sandbox JVMs even with a default (Metal)
// look-and-feel; these tests are skipped rather than failed if construction itself isn't possible
// in that environment, since there's then no chooser left to exercise configureDirectoryChooser on.
private fun newDirectoryChooserOrSkipTest(): JFileChooser =
    try {
        JFileChooser()
    } catch (e: HeadlessException) {
        assumeNoException(e)
        throw e
    }
