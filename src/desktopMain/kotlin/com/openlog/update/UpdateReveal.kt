package com.openlog.update

import java.io.File

/**
 * Opens the platform file manager with [file] selected, mirroring the "reveal in Finder/Explorer"
 * affordance users expect once a manual update download finishes. Best-effort: any failure
 * (missing binary, headless environment, sandboxing) is swallowed via `runCatching` — the download
 * itself already succeeded, so a failed reveal should never surface as an error to the user.
 */
fun revealInFileManager(file: File) {
    val osName = System.getProperty("os.name").orEmpty().lowercase()
    runCatching {
        when {
            osName.contains("mac") -> ProcessBuilder("open", "-R", file.absolutePath).start()
            // `explorer /select,<path>` reports a non-zero exit code even on success (a long-
            // standing Explorer quirk) — deliberately not checked via waitFor()/exitValue().
            osName.contains("win") -> ProcessBuilder("explorer", "/select,${file.absolutePath}").start()
            else -> ProcessBuilder("xdg-open", (file.parentFile ?: file).absolutePath).start()
        }
    }
}
