package com.indagium.ui

import com.indagium.model.AppSettings
import com.indagium.model.LinuxFilePickerMode
import java.io.File

private const val GTK_FILE_DIALOGS_PROPERTY = "sun.awt.disableGtkFileDialogs"
private val AUTOMATIC_COMPATIBILITY_DISTRIBUTIONS = setOf("debian", "arch", "manjaro", "fedora")

/** The outcome of attempting to configure AWT's Linux file picker before AWT is initialized. */
internal sealed interface LinuxFilePickerPropertyResult {
    data object NotLinux : LinuxFilePickerPropertyResult

    data object ExistingJvmOverride : LinuxFilePickerPropertyResult

    data class Applied(val disableGtkFileDialogs: Boolean) : LinuxFilePickerPropertyResult
}

/**
 * Reads the exact `ID` field from os-release. `ID_LIKE` intentionally has no effect: derivatives
 * can use a different AWT/desktop stack than their upstream and must use the native automatic
 * default unless they explicitly select Compatibility X11 in Settings.
 */
internal fun linuxOsReleaseId(osRelease: String): String? = osRelease.lineSequence()
    .map { it.trim() }
    .firstOrNull { line -> line.startsWith("ID=") }
    ?.substringAfter('=')
    ?.trim()
    ?.removeSurrounding("\"")
    ?.removeSurrounding("'")
    ?.lowercase()
    ?.takeIf { it.isNotBlank() }

/** Resolves the persisted setting to the AWT property value, without touching JVM state. */
internal fun resolvesDisableGtkFileDialogs(mode: LinuxFilePickerMode, osRelease: String?): Boolean = when (mode) {
    LinuxFilePickerMode.COMPATIBILITY_X11 -> true
    LinuxFilePickerMode.NATIVE_GTK -> false
    LinuxFilePickerMode.AUTOMATIC -> linuxOsReleaseId(osRelease.orEmpty()) in AUTOMATIC_COMPATIBILITY_DISTRIBUTIONS
}

/**
 * Applies the preference before the first AWT FileDialog is created. A `-D` value always wins,
 * including an intentionally empty or non-boolean value, because it is an explicit launch choice.
 */
internal fun applyLinuxFilePickerMode(
    settings: AppSettings,
    osName: String = System.getProperty("os.name").orEmpty(),
    osReleaseReader: () -> String? = { runCatching { File("/etc/os-release").readText() }.getOrNull() },
    propertyReader: (String) -> String? = System::getProperty,
    propertyWriter: (String, String) -> Unit = { key, value -> System.setProperty(key, value) },
): LinuxFilePickerPropertyResult {
    if (!osName.contains("linux", ignoreCase = true)) return LinuxFilePickerPropertyResult.NotLinux
    if (propertyReader(GTK_FILE_DIALOGS_PROPERTY) != null) return LinuxFilePickerPropertyResult.ExistingJvmOverride

    val disableGtk = resolvesDisableGtkFileDialogs(settings.linuxFilePickerMode, osReleaseReader())
    propertyWriter(GTK_FILE_DIALOGS_PROPERTY, disableGtk.toString())
    return LinuxFilePickerPropertyResult.Applied(disableGtk)
}
