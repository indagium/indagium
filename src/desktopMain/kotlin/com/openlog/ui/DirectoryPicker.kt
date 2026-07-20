package com.openlog.ui

import java.awt.FileDialog
import java.awt.Frame
import java.io.File
import javax.swing.JFileChooser

/** Cross-platform directory picker used whenever the app needs a folder rather than a file. */
internal fun interface DirectoryPicker {
    /** Returns an existing directory, or null if the user cancels the picker. */
    fun pick(title: String, initialDirectory: File?): File?
}

internal object PlatformDirectoryPicker : DirectoryPicker {
    override fun pick(title: String, initialDirectory: File?): File? =
        if (isMacOs()) MacDirectoryPicker.pick(title, initialDirectory) else SwingDirectoryPicker.pick(title, initialDirectory)
}

/** macOS's native file dialog supports directory-only selection through this Apple-specific flag. */
private object MacDirectoryPicker : DirectoryPicker {
    override fun pick(title: String, initialDirectory: File?): File? {
        val previous = System.getProperty(MAC_DIRECTORY_DIALOG_PROPERTY)
        System.setProperty(MAC_DIRECTORY_DIALOG_PROPERTY, "true")
        return try {
            val dialog = FileDialog(null as Frame?, title, FileDialog.LOAD).apply {
                initialDirectoryForPicker(initialDirectory)?.let { directory = it.absolutePath }
            }
            dialog.isVisible = true
            val directory = dialog.directory ?: return null
            val name = dialog.file ?: return null
            File(directory, name).takeIf(File::isDirectory)
        } finally {
            if (previous == null) System.clearProperty(MAC_DIRECTORY_DIALOG_PROPERTY)
            else System.setProperty(MAC_DIRECTORY_DIALOG_PROPERTY, previous)
        }
    }
}

/** Used on Linux and Windows, where AWT FileDialog has no directory-only mode. */
private object SwingDirectoryPicker : DirectoryPicker {
    override fun pick(title: String, initialDirectory: File?): File? {
        val chooser = JFileChooser(initialDirectoryForPicker(initialDirectory)).apply {
            dialogTitle = title
            fileSelectionMode = JFileChooser.DIRECTORIES_ONLY
            isAcceptAllFileFilterUsed = false
        }
        if (chooser.showOpenDialog(null) != JFileChooser.APPROVE_OPTION) return null
        return chooser.selectedFile?.takeIf(File::isDirectory)
    }
}

/**
 * Returns a usable starting directory for a picker. Older Linux releases could persist a selected
 * file instead of its containing folder, so an existing file intentionally resolves to its parent.
 */
internal fun initialDirectoryForPicker(path: File?): File? = when {
    path == null -> null
    path.isDirectory -> path
    path.isFile -> path.parentFile?.takeIf(File::isDirectory)
    else -> null
}

internal fun isMacOs(osName: String = System.getProperty("os.name").orEmpty()): Boolean =
    osName.contains("mac", ignoreCase = true)

private const val MAC_DIRECTORY_DIALOG_PROPERTY = "apple.awt.fileDialogForDirectories"
