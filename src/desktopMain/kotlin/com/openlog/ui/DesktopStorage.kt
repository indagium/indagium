package com.openlog.ui

import java.io.File

object DesktopStorage {
    private const val APP_DIR_NAME = "openLog2"

    fun appDataDir(
        osName: String = System.getProperty("os.name").orEmpty(),
        userHome: String = System.getProperty("user.home").orEmpty(),
        getenv: (String) -> String? = System::getenv,
    ): File {
        val os = osName.lowercase()
        return when {
            os.contains("mac") -> File(userHome, "Library/Application Support/$APP_DIR_NAME")
            os.contains("win") -> File(
                getenv("APPDATA") ?: File(userHome, "AppData/Roaming").absolutePath,
                APP_DIR_NAME,
            )
            else -> File(getenv("XDG_STATE_HOME") ?: File(userHome, ".local/state").absolutePath, APP_DIR_NAME)
        }
    }

    fun autosaveFile(): File = File(appDataDir(), "autosave.cache")

    fun archiveCacheDir(): File = File(appDataDir(), "archive-cache")

    fun notesDir(): File = File(appDataDir(), "notes")

    fun filterBackupsDir(): File = File(appDataDir(), "filter-backups")

    fun controlTokenFile(): File = File(appDataDir(), "control-token")

    fun legacyNotesDir(userHome: String = System.getProperty("user.home").orEmpty()): File =
        File(userHome, ".openlog2/notes")
}
