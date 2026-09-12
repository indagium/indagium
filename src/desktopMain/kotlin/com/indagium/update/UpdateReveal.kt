package com.indagium.update

import java.io.File

/**
 * Builds the native command used to reveal [file] in the current platform's file manager.
 *
 * This function deliberately has no process or desktop side effects. Keeping platform selection
 * here makes the update flow testable on every host and lets tests assert the exact command that
 * would be launched without opening Finder, Explorer, or a desktop file browser.
 */
fun revealInFileManagerCommand(file: File, osName: String = System.getProperty("os.name").orEmpty()): List<String> {
    val normalizedOsName = osName.lowercase()
    return when {
        normalizedOsName.contains("mac") -> listOf("open", "-R", file.absolutePath)
        normalizedOsName.contains("win") -> listOf("explorer", "/select,${file.absolutePath}")
        else -> listOf("xdg-open", (file.parentFile ?: file).absolutePath)
    }
}

/**
 * Opens the platform file manager with [file] selected, mirroring the "reveal in Finder/Explorer"
 * affordance users expect once a manual update download finishes. Best-effort: any failure
 * (missing binary, headless environment, sandboxing) is swallowed via `runCatching` — the download
 * itself already succeeded, so a failed reveal should never surface as an error to the user.
 */
fun revealInFileManager(file: File) {
    runCatching {
        // `explorer /select,<path>` reports a non-zero exit code even on success (a long-
        // standing Explorer quirk) — deliberately not checked via waitFor()/exitValue().
        ProcessBuilder(revealInFileManagerCommand(file)).start()
    }
}
