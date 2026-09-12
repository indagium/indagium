package com.indagium

import com.indagium.update.revealInFileManagerCommand
import java.io.File
import kotlin.io.path.createTempDirectory
import kotlin.test.Test
import kotlin.test.assertEquals

class UpdateRevealTest {
    @Test
    fun macOsCommandSelectsTheDownloadedFileInFinder() {
        val file = File(createTempDirectory("openlog-update-reveal-mac").toFile(), "update.dmg")

        assertEquals(
            listOf("open", "-R", file.absolutePath),
            revealInFileManagerCommand(file, osName = "Mac OS X"),
        )
    }

    @Test
    fun windowsCommandSelectsTheDownloadedFileInExplorer() {
        val file = File(createTempDirectory("openlog-update-reveal-windows").toFile(), "update.msi")

        assertEquals(
            listOf("explorer", "/select,${file.absolutePath}"),
            revealInFileManagerCommand(file, osName = "Windows 11"),
        )
    }

    @Test
    fun linuxCommandOpensTheDownloadedFilesParentDirectory() {
        val parent = createTempDirectory("openlog-update-reveal-linux").toFile()
        val file = File(parent, "update.deb")

        assertEquals(
            listOf("xdg-open", parent.absolutePath),
            revealInFileManagerCommand(file, osName = "Linux"),
        )
    }
}
