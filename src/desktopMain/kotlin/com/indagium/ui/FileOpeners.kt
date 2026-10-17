package com.indagium.ui

import com.indagium.update.openFolderInFileManagerCommand
import com.indagium.update.revealInFileManagerCommand
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.awt.Desktop
import java.io.File
import java.io.IOException
import java.util.concurrent.TimeUnit

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

/** Extensions that are plain text, so the system text editor is a sensible way to open them when nothing else is registered. */
private val TEXT_LIKE_EXTENSIONS = setOf("jsonl", "ndjson", "json", "txt", "log", "md", "csv")

/** Text extensions that operating systems rarely associate with any application, so the default open would fail (macOS: error -1). */
private val USUALLY_UNASSOCIATED_EXTENSIONS = setOf("jsonl", "ndjson")

/** True for the plain-text files (.jsonl, .json, .txt, .log, .md, .csv, .ndjson) that [textEditorOpenCommand] can show. */
internal fun isTextLikeFile(file: File): Boolean = file.extension.lowercase() in TEXT_LIKE_EXTENSIONS

/** True for text files that normally have no default application, which are opened in the text editor straight away. */
internal fun usuallyHasNoDefaultApp(file: File): Boolean = file.extension.lowercase() in USUALLY_UNASSOCIATED_EXTENSIONS

/**
 * The native command that opens [file] in the system's default TEXT editor: `open -t` (TextEdit unless the user chose another) on
 * macOS, Notepad on Windows. Null on other platforms, where `xdg-open` (see [openFileCommand]) already resolves a text editor by
 * MIME type. No side effects, so tests assert the exact command.
 */
internal fun textEditorOpenCommand(file: File, osName: String = System.getProperty("os.name").orEmpty()): List<String>? {
    val os = osName.lowercase()
    return when {
        os.contains("mac") -> listOf("open", "-t", file.absolutePath)
        os.contains("win") -> listOf("notepad.exe", file.absolutePath)
        else -> null
    }
}

/** How long a launcher is awaited for an exit code: `open -t` returns at once, while an editor that stays running (Notepad) is a success. */
private const val LAUNCHER_WAIT_SECONDS = 5L

private fun runLauncher(command: List<String>) {
    val process = ProcessBuilder(command).redirectErrorStream(true).start()
    if (!process.waitFor(LAUNCHER_WAIT_SECONDS, TimeUnit.SECONDS)) return
    val exit = process.exitValue()
    if (exit != 0) {
        val detail = process.inputStream.bufferedReader().use { it.readText() }.trim().lineSequence().firstOrNull().orEmpty()
        throw IOException(if (detail.isEmpty()) "${command.first()} exited with code $exit." else detail)
    }
}

/**
 * Opens [file] with the system's default application. A plain-text file nothing is registered for (a .jsonl transcript on macOS makes
 * `Desktop.open` fail with error -1) is opened in the system text editor instead.
 */
internal suspend fun openWithSystemDefault(file: File): Result<Unit> = withContext(Dispatchers.IO) {
    runCatching {
        if (!file.exists()) throw IOException("${file.name} no longer exists.")
        val textEditor = if (isTextLikeFile(file)) textEditorOpenCommand(file) else null
        when {
            textEditor != null && usuallyHasNoDefaultApp(file) -> runLauncher(textEditor)
            else -> try {
                if (desktopCanOpen()) Desktop.getDesktop().open(file) else ProcessBuilder(openFileCommand(file)).start()
            } catch (error: IOException) {
                if (textEditor == null) throw error
                runLauncher(textEditor)
            }
        }
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
