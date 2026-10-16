package com.indagium.ui

import com.indagium.update.openFolderInFileManagerCommand
import com.indagium.update.revealInFileManagerCommand
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.awt.Desktop
import java.io.File
import java.io.IOException

// Opening files and folders with the operating system: the one place the Tests workspace (issue evidence, run transcripts and
// screenshots) goes through. Each call runs on IO and returns a Result so the caller can show the failure instead of swallowing it.

/**
 * The native command that opens [file] with its default application, for platforms where java.awt.Desktop cannot. No side effects,
 * so tests assert the exact command without launching anything.
 */
internal fun openFileCommand(file: File, osName: String = System.getProperty("os.name").orEmpty()): List<String> {
    val os = osName.lowercase()
    return when {
        os.contains("mac") -> listOf("open", file.absolutePath)
        os.contains("win") -> listOf("rundll32", "url.dll,FileProtocolHandler", file.absolutePath)
        else -> listOf("xdg-open", file.absolutePath)
    }
}

/** Opens [file] with the system's default application. */
internal suspend fun openWithSystemDefault(file: File): Result<Unit> = withContext(Dispatchers.IO) {
    runCatching {
        if (!file.exists()) throw IOException("${file.name} no longer exists.")
        if (desktopCanOpen()) Desktop.getDesktop().open(file) else ProcessBuilder(openFileCommand(file)).start()
        Unit
    }
}

/** Opens [folder] itself in the system file manager. */
internal suspend fun openFolder(folder: File): Result<Unit> = withContext(Dispatchers.IO) {
    runCatching {
        if (!folder.isDirectory) throw IOException("The folder ${folder.name} no longer exists.")
        if (desktopCanOpen()) Desktop.getDesktop().open(folder) else ProcessBuilder(openFolderInFileManagerCommand(folder)).start()
        Unit
    }
}

/** Shows [file] selected in the system file manager (Finder, Explorer, or its folder on Linux). */
internal suspend fun revealFile(file: File): Result<Unit> = withContext(Dispatchers.IO) {
    runCatching {
        if (!file.exists()) throw IOException("${file.name} no longer exists.")
        // Not waited for: `explorer /select,` exits non-zero even when it worked.
        ProcessBuilder(revealInFileManagerCommand(file)).start()
        Unit
    }
}

private fun desktopCanOpen(): Boolean = Desktop.isDesktopSupported() && Desktop.getDesktop().isSupported(Desktop.Action.OPEN)

/** A user-facing sentence for a failed open of [what] ("the transcript"): the reason when the exception has one. */
internal fun openFailureMessage(what: String, error: Throwable): String {
    val reason = error.message?.takeIf { it.isNotBlank() }
    return if (reason == null) "Could not open $what." else "Could not open $what: $reason"
}
