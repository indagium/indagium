package com.indagium.ui

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
        val chooser = JFileChooser()
        configureDirectoryChooser(chooser, title, initialDirectoryForPicker(initialDirectory))
        if (chooser.showOpenDialog(null) != JFileChooser.APPROVE_OPTION) return null
        return resolveChosenDirectory(chooser.selectedFile, chooser.currentDirectory)
    }
}

/**
 * Swing's approve action reads the file-name text field, not selectedFile directly — and on GTK
 * (Linux) that field starts empty until a directory-changed/selected-file event has fired, so the
 * very first Open press on a freshly opened chooser silently did nothing. Pre-selecting startDir
 * (opening one level up, with startDir itself selected) populates the field immediately instead of
 * relying on the user navigating first.
 *
 * currentDirectory is set before selectedFile: JFileChooser.setSelectedFile() calls
 * setCurrentDirectory(file.parentFile) whenever the file isn't already inside the current
 * directory, so setting selectedFile first would immediately walk currentDirectory back up again.
 */
internal fun configureDirectoryChooser(chooser: JFileChooser, title: String, startDir: File?) {
    chooser.dialogTitle = title
    chooser.fileSelectionMode = JFileChooser.DIRECTORIES_ONLY
    chooser.isAcceptAllFileFilterUsed = false
    if (startDir != null) {
        chooser.currentDirectory = startDir.parentFile ?: startDir
        chooser.selectedFile = startDir
    }
}

/**
 * Decides which directory a Swing [JFileChooser] approval actually refers to. Pure so it's
 * unit-testable without a real dialog. A selected directory wins outright; a selected regular file
 * (possible if the user types a name directly into the field) resolves to its parent directory;
 * with nothing selected, the chooser's current directory is what was being browsed when the user
 * pressed Open.
 */
internal fun resolveChosenDirectory(selectedFile: File?, currentDirectory: File?): File? = when {
    selectedFile != null && selectedFile.isDirectory -> selectedFile
    selectedFile != null && selectedFile.isFile -> selectedFile.parentFile?.takeIf(File::isDirectory)
    else -> currentDirectory?.takeIf(File::isDirectory)
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

/**
 * Native single-FILE save prompt (used by the update-download flow in AppState.kt). Unlike
 * directory selection above, `FileDialog.SAVE` is already natively backed on every platform with
 * no directory-only gap to work around, so — unlike [PlatformDirectoryPicker] — this needs no
 * per-OS branching or Swing fallback. Returns the full chosen file (directory + name), or null if
 * the user cancels (AWT reports a cancel as either a null file or a null directory).
 */
internal fun pickSaveFile(title: String, suggestedName: String, initialDirectory: File?): File? {
    val dialog = FileDialog(null as Frame?, title, FileDialog.SAVE).apply {
        file = suggestedName
        initialDirectory?.let { directory = it.absolutePath }
        isVisible = true
    }
    val name = dialog.file ?: return null
    val dir = dialog.directory ?: return null
    return File(dir, name)
}
