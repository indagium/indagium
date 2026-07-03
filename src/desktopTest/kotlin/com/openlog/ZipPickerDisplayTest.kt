package com.openlog

import com.openlog.ui.zipEntryPathForDisplay
import kotlin.test.Test
import kotlin.test.assertTrue

class ZipPickerDisplayTest {
    @Test
    fun longZipEntryDisplayKeepsTheFilenameEndVisible() {
        val path = "FS/data/misc/logd/very-long-folder-name/very-long-file-name-that-keeps-important-suffix-main_log.txt"

        val display = zipEntryPathForDisplay(path, maxChars = 42)

        assertTrue(display.startsWith("..."), "Long zip entry paths should be collapsed at the start")
        assertTrue(display.endsWith("suffix-main_log.txt"), "Long zip entry paths should keep the file name end visible")
    }
}
