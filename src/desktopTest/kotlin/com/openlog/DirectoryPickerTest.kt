package com.openlog

import com.openlog.ui.initialDirectoryForPicker
import com.openlog.ui.isMacOs
import java.io.File
import kotlin.io.path.createTempDirectory
import kotlin.test.Test
import kotlin.test.assertEquals
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
}
